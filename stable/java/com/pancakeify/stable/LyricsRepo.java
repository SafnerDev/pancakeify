package com.pancakeify.stable;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics source for the Pancakeify player.
 *
 * 1. Spicy Lyrics API (https://developers.spicylyrics.org) -- syllable-synced lyrics with duet
 *    alignment and background vocals. Needs the secret key baked in at build time ({@link SpicyKey}).
 * 2. LRCLIB (https://lrclib.net) -- open, line-synced LRC, used when Spicy has nothing for the
 *    track or can't be reached.
 *
 * Results are cached on disk so a track is only looked up once.
 */
public final class LyricsRepo {
    private LyricsRepo() {}

    /** One timed syllable. {@code partOfWord}: it continues into the next syllable without a space. */
    public static final class Syl {
        public final String text;
        public final long startMs, endMs;
        public final boolean partOfWord;
        Syl(String text, long startMs, long endMs, boolean partOfWord) {
            this.text = text; this.startMs = startMs; this.endMs = endMs; this.partOfWord = partOfWord;
        }
    }

    /** A group of syllables (a lead vocal or one background vocal). */
    public static final class Part {
        public final List<Syl> syl;
        public final long startMs, endMs;
        Part(List<Syl> syl, long startMs, long endMs) { this.syl = syl; this.startMs = startMs; this.endMs = endMs; }
    }

    /** A Spicy Lyrics community member credited on a lyrics sheet. */
    public static final class Person {
        public final String name, url, avatar;
        Person(String name, String url, String avatar) { this.name = name; this.url = url; this.avatar = avatar; }
    }

    public static final class Line {
        public final long startMs;
        public long endMs;                  // end of the sung text (0 = unknown, LRC)
        public final String text;           // plain text of the lead vocal
        public List<Syl> syl;               // null for line-synced sources
        public boolean opposite;            // duet partner: right-aligned
        public List<Part> bg;               // background vocals (may be null)
        Line(long startMs, String text) { this.startMs = startMs; this.text = text; }
    }

    public static final class Result {
        public final List<Line> lines;       // synced lines (may contain empty "gap" lines)
        public final String plain;           // unsynced text, or null
        public final boolean instrumental;
        public final boolean notFound;
        public String source = "lrclib";     // "lrclib", or the Spicy API's own source id
        public boolean wordSynced;           // true when lines carry syllable timings
        public boolean fromSpicy;            // lyrics came from the Spicy Lyrics API (credits are shown)
        public List<String> writers;         // songwriters, may be null
        public Person maker, uploader;       // community credits, may be null
        Result(List<Line> lines, String plain, boolean instrumental, boolean notFound) {
            this.lines = lines; this.plain = plain; this.instrumental = instrumental; this.notFound = notFound;
        }
        public boolean synced() { return lines != null && !lines.isEmpty(); }
    }

    public interface Callback { void onResult(String key, Result r); }

    private static final ExecutorService pool = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Pattern TS = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final long NEG_CACHE_MS = 24L * 3600 * 1000;

    public static void fetch(final Context ctx, final String key, final String title, final String artist,
                             final String album, final long durationMs, final Callback cb) {
        fetch(ctx, key, title, artist, album, durationMs, false, cb);
    }

    /** {@code force} skips the disk cache (the "re-sync" button). */
    public static void fetch(final Context ctx, final String key, final String title, final String artist,
                             final String album, final long durationMs, final boolean force, final Callback cb) {
        pool.execute(() -> {
            Result r = null;
            try {
                r = force ? null : readCache(ctx, key);
                if (r == null) {
                    String id = trackId(key);
                    String raw = null;
                    boolean spicyDown = false;          // transient failure: don't cache the fallback
                    if (id != null && !SpicyKey.KEY.isEmpty()) {
                        try {
                            raw = spicyGet(id);          // null = not in the Spicy database
                        } catch (Throwable t) {
                            spicyDown = true;
                            Log.w(PancakeBootstrap.TAG, "spicy lyrics unavailable: " + t);
                        }
                    }
                    if (raw != null) {
                        try {
                            r = parseSpicy(new JSONObject(raw).getJSONObject("Body"));
                            if (r != null) writeSpicyCache(ctx, key, raw);
                        } catch (Throwable t) {
                            Log.w(PancakeBootstrap.TAG, "spicy lyrics parse failed: " + t);
                        }
                    }
                    if (r == null) {
                        r = query(title, artist, album, durationMs);
                        if (!spicyDown) writeCache(ctx, key, r);
                    }
                }
            } catch (Throwable t) {
                Log.w(PancakeBootstrap.TAG, "lyrics fetch failed: " + t);
                // network trouble: not cached, so it is retried next time
                r = new Result(null, null, false, true);
            }
            final Result out = r;
            main.post(() -> cb.onResult(key, out));
        });
    }

    /** "spotify:track:XXXX" -> "XXXX", anything else -> null. */
    private static String trackId(String key) {
        final String p = "spotify:track:";
        return key != null && key.startsWith(p) && key.length() > p.length() ? key.substring(p.length()) : null;
    }

    // ------------------------------------------------------------------------------ spicy

    /** Raw response body, null on 404, throws on rate limit / network / server errors. */
    private static String spicyGet(String id) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.spicylyrics.org/v1/lyrics/" + id).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Authorization", "Bearer " + SpicyKey.KEY);
        c.setRequestProperty("User-Agent", "Pancakeify/1.0 (Spotify mod)");
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new java.io.IOException("spicy HTTP " + code);
            return readAll(c);
        } finally {
            c.disconnect();
        }
    }

    private static long ms(double sec) { return Math.round(sec * 1000.0); }

    private static List<Syl> parseSyllables(JSONArray arr) {
        List<Syl> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("Text", "").trim();
            if (t.isEmpty()) continue;
            out.add(new Syl(t, ms(o.optDouble("StartTime", 0)), ms(o.optDouble("EndTime", 0)), o.optBoolean("IsPartOfWord", false)));
        }
        return out;
    }

    static String joinSyllables(List<Syl> syl) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < syl.size(); i++) {
            sb.append(syl.get(i).text);
            if (i + 1 < syl.size() && !syl.get(i).partOfWord) sb.append(' ');
        }
        return sb.toString();
    }

    /** Spicy "Body" -> Result. Handles the Syllable, Line and Static sync types. */
    static Result parseSpicy(JSONObject body) {
        String type = body.optString("Type", "");
        String source = body.optString("source", "spicy");
        if ("Static".equalsIgnoreCase(type)) {
            JSONArray ls = body.optJSONArray("Lines");
            if (ls == null) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ls.length(); i++) {
                JSONObject o = ls.optJSONObject(i);
                String t = o != null ? o.optString("Text", "") : ls.optString(i, "");
                sb.append(t).append('\n');
            }
            if (sb.toString().trim().isEmpty()) return null;
            Result r = new Result(null, sb.toString().trim(), false, false);
            r.source = source;
            fillCredits(r, body);
            return r;
        }
        JSONArray content = body.optJSONArray("Content");
        if (content == null || content.length() == 0) return null;
        List<Line> lines = new ArrayList<>();
        boolean word = false;
        for (int i = 0; i < content.length(); i++) {
            JSONObject it = content.optJSONObject(i);
            if (it == null) continue;
            Line l;
            JSONObject lead = it.optJSONObject("Lead");
            if (lead != null) {
                List<Syl> syl = parseSyllables(lead.optJSONArray("Syllables"));
                if (syl.isEmpty()) continue;
                l = new Line(ms(lead.optDouble("StartTime", syl.get(0).startMs / 1000.0)), joinSyllables(syl));
                l.endMs = ms(lead.optDouble("EndTime", syl.get(syl.size() - 1).endMs / 1000.0));
                l.syl = syl;
                word = true;
            } else {                                   // "Line" sync: plain text + start/end
                String t = it.optString("Text", "").trim();
                if (t.isEmpty()) continue;
                l = new Line(ms(it.optDouble("StartTime", 0)), t);
                l.endMs = ms(it.optDouble("EndTime", 0));
            }
            l.opposite = it.optBoolean("OppositeAligned", false);
            JSONArray bgs = it.optJSONArray("Background");
            if (bgs != null) {
                for (int b = 0; b < bgs.length(); b++) {
                    JSONObject bo = bgs.optJSONObject(b);
                    if (bo == null) continue;
                    List<Syl> bs = parseSyllables(bo.optJSONArray("Syllables"));
                    if (bs.isEmpty()) continue;
                    if (l.bg == null) l.bg = new ArrayList<>();
                    l.bg.add(new Part(bs, ms(bo.optDouble("StartTime", bs.get(0).startMs / 1000.0)),
                            ms(bo.optDouble("EndTime", bs.get(bs.size() - 1).endMs / 1000.0))));
                    if (!bs.isEmpty()) l.endMs = Math.max(l.endMs, bs.get(bs.size() - 1).endMs);
                }
            }
            lines.add(l);
        }
        if (lines.isEmpty()) return null;
        Collections.sort(lines, (a, b) -> Long.compare(a.startMs, b.startMs));
        Result r = new Result(lines, null, false, false);
        r.source = source;
        r.wordSynced = word;
        fillCredits(r, body);
        return r;
    }

    private static void fillCredits(Result r, JSONObject body) {
        r.fromSpicy = true;
        JSONArray w = body.optJSONArray("SongWriters");
        if (w != null && w.length() > 0) {
            r.writers = new ArrayList<>();
            for (int i = 0; i < w.length(); i++) {
                String n = w.optString(i, "").trim();
                if (!n.isEmpty()) r.writers.add(n);
            }
        }
        JSONObject ua = body.optJSONObject("UploadAttribution");
        if (ua != null) {
            r.uploader = person(ua.optJSONObject("Uploader"));
            r.maker = person(ua.optJSONObject("Maker"));
        }
    }

    private static Person person(JSONObject o) {
        if (o == null) return null;
        String name = o.optString("username", "");
        if (name.isEmpty()) return null;
        return new Person(name, o.optString("url", ""), o.optString("avatar", ""));
    }

    // ------------------------------------------------------------------------------ query

    private static Result query(String title, String artist, String album, long durationMs) throws Exception {
        List<String> artists = new ArrayList<>();
        artists.add(artist);
        for (String sep : new String[]{", ", " & ", " feat. ", " + "}) {
            int i = artist.indexOf(sep);
            if (i > 0) { artists.add(artist.substring(0, i)); break; }
        }
        long sec = Math.round(durationMs / 1000.0);
        for (String a : artists) {
            String url = "https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(a)
                    + (album != null && !album.isEmpty() ? "&album_name=" + enc(album) : "")
                    + (sec > 0 ? "&duration=" + sec : "");
            String body = http(url);
            if (body != null) {
                Result r = fromJson(new JSONObject(body));
                if (r != null) return r;
            }
        }
        // looser search: pick the closest-duration entry that has synced lyrics
        for (String a : artists) {
            String body = http("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(a));
            if (body == null) continue;
            JSONArray arr = new JSONArray(body);
            JSONObject best = null;
            double bestDiff = 1e9;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String synced = o.optString("syncedLyrics", "");
                if (synced.isEmpty() || o.isNull("syncedLyrics")) continue;
                double diff = Math.abs(o.optDouble("duration", 0) - sec);
                if (sec > 0 && diff > 6) continue;
                if (diff < bestDiff) { bestDiff = diff; best = o; }
            }
            if (best == null && arr.length() > 0) {
                JSONObject o = arr.getJSONObject(0);
                if (sec == 0 || Math.abs(o.optDouble("duration", 0) - sec) <= 6) best = o;
            }
            if (best != null) {
                Result r = fromJson(best);
                if (r != null) return r;
            }
        }
        return new Result(null, null, false, true);
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    /** Returns the body, or null on 404. */
    private static String http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Pancakeify/1.0 (Spotify mod)");
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            return readAll(c);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    private static Result fromJson(JSONObject o) {
        boolean instrumental = o.optBoolean("instrumental", false);
        String synced = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
        String plain = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "");
        List<Line> lines = parseLrc(synced);
        if (lines.isEmpty() && plain.isEmpty() && !instrumental) return null;
        return new Result(lines.isEmpty() ? null : lines, plain.isEmpty() ? null : plain, instrumental, false);
    }

    /** Parses "[mm:ss.xx] text" lines (several timestamps per line are allowed). */
    static List<Line> parseLrc(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return out;
        for (String raw : lrc.split("\n")) {
            Matcher m = TS.matcher(raw);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long frac = 0;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    frac = Long.parseLong(f);
                    if (f.length() == 1) frac *= 100; else if (f.length() == 2) frac *= 10;
                }
                stamps.add(min * 60000 + sec * 1000 + frac);
                end = m.end();
            }
            if (stamps.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : stamps) out.add(new Line(t, text));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.startMs, b.startMs));
        return out;
    }

    // ------------------------------------------------------------------------------ cache

    private static File cacheFile(Context ctx, String key) throws Exception {
        File dir = new File(ctx.getCacheDir(), "pancakeify_lyrics");
        if (!dir.exists()) dir.mkdirs();
        byte[] h = MessageDigest.getInstance("SHA-1").digest(("v2|" + key).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return new File(dir, sb + ".json");
    }

    private static Result readCache(Context ctx, String key) {
        try {
            File f = cacheFile(ctx, key);
            if (!f.exists()) return null;
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (o.has("spicy")) return parseSpicy(new JSONObject(o.getString("spicy")).getJSONObject("Body"));
            // LRCLIB / negative entries expire so a track Spicy adds later gets picked up
            if (System.currentTimeMillis() - f.lastModified() > NEG_CACHE_MS) return null;
            if (o.optBoolean("notFound", false)) return new Result(null, null, false, true);
            return fromJson(o);
        } catch (Throwable t) { return null; }
    }

    private static void writeSpicyCache(Context ctx, String key, String raw) {
        try {
            JSONObject o = new JSONObject();
            o.put("spicy", raw);
            try (FileOutputStream fo = new FileOutputStream(cacheFile(ctx, key))) {
                fo.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) { Log.w(PancakeBootstrap.TAG, "spicy cache write failed: " + t); }
    }

    private static void writeCache(Context ctx, String key, Result r) {
        try {
            JSONObject o = new JSONObject();
            if (r.notFound) o.put("notFound", true);
            else {
                o.put("instrumental", r.instrumental);
                if (r.plain != null) o.put("plainLyrics", r.plain);
                if (r.lines != null) {
                    StringBuilder sb = new StringBuilder();
                    for (Line l : r.lines) {
                        long t = l.startMs;
                        sb.append(String.format("[%02d:%02d.%03d]", t / 60000, (t / 1000) % 60, t % 1000))
                          .append(l.text).append('\n');
                    }
                    o.put("syncedLyrics", sb.toString());
                }
            }
            try (FileOutputStream fo = new FileOutputStream(cacheFile(ctx, key))) {
                fo.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) { Log.w(PancakeBootstrap.TAG, "lyrics cache write failed: " + t); }
    }
}

package com.pancakeify.stable;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.SystemClock;
import android.view.InputDevice;
import android.os.Looper;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import top.canyie.pine.Pine;

/**
 * Replacement for Spotify's Now Playing screen, in the style of Spicy Lyrics
 * (https://github.com/Spikerko/spicy-lyrics by Spikerko, used with the author's permission):
 * an animated, saturated "aurora" background derived from the cover (Kawarp-style, see
 * {@link AuroraView}), a compact cover + title header, big springy glowing lyrics
 * ({@link LyricsView}), and below them Spotify's own control block: seek bar, shuffle / previous /
 * play-pause / next / repeat, then the device, share and queue row.
 *
 * Spotify's own screen is a separate Activity (NowPlayingActivity). When "Enable SpicyLyrics" is
 * on we lay our view over its content as soon as it is created. All data and control go through
 * {@link MediaBridge}; lyrics come from {@link LyricsRepo}.
 */
public final class PancakePlayer {
    private PancakePlayer() {}

    private static final String NOW_PLAYING = ".NowPlayingActivity";
    private static final String TAG_ROOT = "pancake_player";
    static final String KEY_OFFSET = "lyrics_offset_ms";
    // where Spotify's own controls sit in its Now Playing window (fractions of width / height)
    private static final float NATIVE_MORE_X = 0.921f, NATIVE_MORE_Y = 0.105f;
    private static final float NATIVE_DEVICE_X = 0.076f, NATIVE_DEVICE_Y = 0.8985f;
    private static final float NATIVE_QUEUE_X = 0.920f, NATIVE_QUEUE_Y = 0.8985f;

    /** Hook Activity creation so we can cover Spotify's Now Playing screen. */
    public static void install() {
        try {
            HookEngine.hook(Activity.class.getDeclaredMethod("onPostCreate", Bundle.class), new HookEngine.Callback() {
                @Override public void after(Pine.CallFrame frame) {
                    if (frame.thisObject instanceof Activity) maybeTakeOver((Activity) frame.thisObject);
                }
            });
            Log.i(PancakeBootstrap.TAG, "PancakePlayer hook installed");
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "PancakePlayer install failed", t);
        }
    }

    /** Also callable from onResume as a fallback. */
    public static void maybeTakeOver(Activity act) {
        try {
            if (!act.getClass().getName().endsWith(NOW_PLAYING)) return;
            SharedPreferences sp = act.getSharedPreferences("pancakeify", Context.MODE_PRIVATE);
            if (!sp.getBoolean(PancakePrefsScreen.KEY_LYRICS, false)) return;
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            if (decor.findViewWithTag(TAG_ROOT) != null) return;
            if (!MediaBridge.isAvailable()) {
                Log.w(PancakeBootstrap.TAG, "PancakePlayer: no MediaSession yet, leaving Spotify's player");
                return;
            }
            // our view lives directly in the DecorView so it covers the whole window,
            // including the area behind the (transparent) system bars
            act.getWindow().setStatusBarColor(Color.TRANSPARENT);
            act.getWindow().setNavigationBarColor(Color.TRANSPARENT);
            PlayerRoot root = new PlayerRoot(act);
            root.setTag(TAG_ROOT);
            decor.addView(root, new FrameLayout.LayoutParams(-1, -1));
            Log.i(PancakeBootstrap.TAG, "PancakePlayer: took over Now Playing");
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "PancakePlayer takeover failed", t);
        }
    }

    // ===================================================================== the player view

    private static final class PlayerRoot extends FrameLayout implements MediaBridge.Listener, Choreographer.FrameCallback {
        private final Activity act;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final SharedPreferences prefs;

        private final AuroraView aurora;
        private final LinearLayout content;
        private final LinearLayout topRow, headerRow, info, bottomRow;
        private final View spacerTop, spacerBottom;
        private boolean bigCover;
        private boolean likeKnown, lastLiked, lastPlaying;
        private int repeatIcon;
        private long shownSec = -1;
        private ValueAnimator likeAnim;
        private IconView more;
        private final CoverView cover;
        private final TextView context, title, artist, explicit, timeNow, timeTotal, deviceText;
        private final IconView like, shuffle, prev, play, next, repeat, gear, deviceIcon, share, queueBtn;
        private final SeekBarView seek;
        private final LyricsView lyrics;

        private String trackKey = "";
        private String lastTitle = "", lastArtist = "", lastAlbum = "";
        private int accent;
        private boolean focusMode = false;
        private long durationMs = 0;
        private long lastDevicePoll = 0;
        private float touchY0;

        PlayerRoot(Activity a) {
            super(a);
            act = a;
            prefs = a.getSharedPreferences("pancakeify", Context.MODE_PRIVATE);
            accent = PancakePrefsScreen.mainColor(a);
            setBackgroundColor(0xFF0A0A0A);
            setClickable(true);

            aurora = new AuroraView(a);
            addView(aurora, new LayoutParams(-1, -1));
            View scrim = new View(a);                                     // a little depth behind the controls
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x00000000, 0x55000000}));
            addView(scrim, new LayoutParams(-1, -1));

            content = new LinearLayout(a);
            content.setOrientation(LinearLayout.VERTICAL);
            addView(content, new LayoutParams(-1, -1));

            // --- top row: chevron + "Playing from"
            topRow = new LinearLayout(a);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            IconView down = new IconView(a, IconView.CHEVRON_DOWN).glyph(0.5f);
            down.setOnClickListener(v -> act.finish());
            topRow.addView(down, new LinearLayout.LayoutParams(dp(52), dp(52)));
            topRow.addView(new View(a), new LinearLayout.LayoutParams(dp(44), 1));       // balances gear + dots on the right
            LinearLayout mid = new LinearLayout(a);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView from = PancakePrefsScreen.text(a, "Playing from", 12, 0xCCFFFFFF, false);
            from.setGravity(Gravity.CENTER_HORIZONTAL);
            mid.addView(from);
            context = PancakePrefsScreen.text(a, "", 14, Color.WHITE, true);
            context.setGravity(Gravity.CENTER_HORIZONTAL);
            context.setSingleLine(true);
            context.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(context);
            topRow.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
            gear = new IconView(a, IconView.GEAR).glyph(0.46f);
            gear.setOnClickListener(v -> showSpicySettings());                           // Spicy Lyrics settings
            topRow.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(52)));
            more = new IconView(a, IconView.MORE).glyph(0.42f);
            more.setOnClickListener(v -> tapNative(NATIVE_MORE_X, NATIVE_MORE_Y));   // Spotify's own track menu
            topRow.addView(more, new LinearLayout.LayoutParams(dp(52), dp(52)));
            content.addView(topRow, new LinearLayout.LayoutParams(-1, dp(52)));

            // --- header row: cover | title + artist | like
            headerRow = new LinearLayout(a);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            cover = new CoverView(a);
            cover.setElevation(dp(10));
            cover.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
            });
            cover.setClipToOutline(true);
            headerRow.addView(cover, new LinearLayout.LayoutParams(dp(138), dp(138)));
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = PancakePrefsScreen.text(a, "", 26, Color.WHITE, true);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout artistRow = new LinearLayout(a);
            artistRow.setGravity(Gravity.CENTER_VERTICAL);
            explicit = PancakePrefsScreen.text(a, "E", 10, 0xFF121212, true);
            explicit.setGravity(Gravity.CENTER);
            GradientDrawable eb = new GradientDrawable();
            eb.setColor(0xCCFFFFFF);
            eb.setCornerRadius(dp(2));
            explicit.setBackground(eb);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(16), dp(16));
            elp.rightMargin = dp(6);
            artistRow.addView(explicit, elp);
            artist = PancakePrefsScreen.text(a, "", 16, 0xCCFFFFFF, false);
            artist.setSingleLine(true);
            artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            artistRow.addView(artist);
            texts.addView(title);
            texts.addView(artistRow);
            info = new LinearLayout(a);                              // title block + like, moves under the cover in big mode
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            like = new IconView(a, IconView.HEART).glyph(0.56f);
            like.setOnClickListener(v -> {
                like.animate().cancel();
                like.setScaleX(0.8f); like.setScaleY(0.8f);                      // instant feedback, the state follows
                like.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
                MediaBridge.sendAction(MediaBridge.findAction("collection", "like", "save", "heart"));
            });
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(40), dp(40));
            ll.leftMargin = dp(8);
            info.addView(like, ll);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2, 1f);
            il.leftMargin = dp(16);
            headerRow.addView(info, il);
            spacerTop = new View(a);
            spacerTop.setVisibility(GONE);
            content.addView(spacerTop, new LinearLayout.LayoutParams(1, 0, 1f));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.leftMargin = hlp.rightMargin = dp(20);
            hlp.topMargin = dp(4);
            content.addView(headerRow, hlp);

            // --- lyrics
            lyrics = new LyricsView(a);
            lyrics.setSeekListener(ms -> MediaBridge.seekTo(ms));
            lyrics.setOffsetMs(prefs.getLong(KEY_OFFSET, 0));
            lyrics.setAnchor(0.14f);
            lyrics.showMessage("Loading lyrics…");
            LinearLayout.LayoutParams lyl = new LinearLayout.LayoutParams(-1, 0, 1f);
            lyl.topMargin = dp(8);
            content.addView(lyrics, lyl);
            spacerBottom = new View(a);
            spacerBottom.setVisibility(GONE);
            content.addView(spacerBottom, new LinearLayout.LayoutParams(1, 0, 1f));

            // --- seek bar with the times on either side (Spicy Player style)
            seek = new SeekBarView(a);
            timeNow = PancakePrefsScreen.text(a, "0:00", 12, 0xD9FFFFFF, false);
            timeTotal = PancakePrefsScreen.text(a, "0:00", 12, 0xD9FFFFFF, false);
            timeNow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            timeTotal.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout seekRow = new LinearLayout(a);
            seekRow.setGravity(Gravity.CENTER_VERTICAL);
            seekRow.addView(timeNow, new LinearLayout.LayoutParams(dp(42), -1));
            seekRow.addView(seek, new LinearLayout.LayoutParams(0, -1, 1f));
            seekRow.addView(timeTotal, new LinearLayout.LayoutParams(dp(42), -1));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(32));
            slp.leftMargin = slp.rightMargin = dp(20);
            content.addView(seekRow, slp);

            // --- controls: shuffle | previous | play | next | repeat
            LinearLayout controls = new LinearLayout(a);
            controls.setGravity(Gravity.CENTER);
            shuffle = new IconView(a, IconView.SHUFFLE).host("encore_icon_shuffle_24", IconView.SHUFFLE).glyph(0.5f);
            shuffle.setOnClickListener(v -> MediaBridge.sendAction(MediaBridge.findActionById("SHUFFLE")));
            prev = new IconView(a, IconView.REWIND).glyph(0.5f);
            prev.setOnClickListener(v -> MediaBridge.previous());
            play = new IconView(a, IconView.PLAY).glyph(0.6f);
            play.setOnClickListener(v -> MediaBridge.togglePlay());
            next = new IconView(a, IconView.FORWARD).glyph(0.5f);
            next.setOnClickListener(v -> MediaBridge.next());
            repeat = new IconView(a, IconView.REPEAT).host("encore_icon_repeat_24", IconView.REPEAT).glyph(0.5f);
            repeat.setOnClickListener(v -> MediaBridge.sendAction(MediaBridge.findActionById("REPEAT")));
            controls.addView(shuffle, new LinearLayout.LayoutParams(0, dp(56), 1f));
            controls.addView(prev, new LinearLayout.LayoutParams(0, dp(56), 1f));
            controls.addView(play, new LinearLayout.LayoutParams(dp(76), dp(76)));
            controls.addView(next, new LinearLayout.LayoutParams(0, dp(56), 1f));
            controls.addView(repeat, new LinearLayout.LayoutParams(0, dp(56), 1f));
            LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(-1, -2);
            colp.leftMargin = colp.rightMargin = dp(16);
            colp.topMargin = dp(4);
            content.addView(controls, colp);

            // --- bottom row (Spotify): device on the left, share + queue on the right
            bottomRow = new LinearLayout(a);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);
            deviceIcon = new IconView(a, IconView.DEVICE).glyph(0.8f).color(Color.WHITE);
            deviceText = PancakePrefsScreen.text(a, "", 14, accent, true);
            deviceText.setSingleLine(true);
            deviceText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            deviceIcon.setOnClickListener(v -> tapNative(NATIVE_DEVICE_X, NATIVE_DEVICE_Y));   // Spotify Connect picker
            deviceText.setOnClickListener(v -> tapNative(NATIVE_DEVICE_X, NATIVE_DEVICE_Y));
            bottomRow.addView(deviceIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1f);
            dl.leftMargin = dp(10);
            bottomRow.addView(deviceText, dl);
            share = new IconView(a, IconView.SHARE).host("encore_icon_share_android_24", IconView.SHARE).glyph(0.6f);
            share.setOnClickListener(v -> shareTrack());
            queueBtn = new IconView(a, IconView.QUEUE).host("encore_icon_queue_24", IconView.QUEUE).glyph(0.6f);
            queueBtn.setOnClickListener(v -> tapNative(NATIVE_QUEUE_X, NATIVE_QUEUE_Y));   // Spotify's own queue
            bottomRow.addView(share, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(dp(44), dp(44));
            ql.leftMargin = dp(6);
            bottomRow.addView(queueBtn, ql);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.leftMargin = dp(24);
            blp.rightMargin = dp(18);
            blp.topMargin = dp(6);
            blp.bottomMargin = dp(10);
            content.addView(bottomRow, blp);

            // swipe down on the header dismisses the player
            OnTouchListener swipe = (v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touchY0 = e.getRawY();
                if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getRawY() - touchY0 > dp(110)) {
                    act.finish();
                    return true;
                }
                return e.getActionMasked() == MotionEvent.ACTION_DOWN;
            };
            headerRow.setOnTouchListener(swipe);
            topRow.setOnTouchListener(swipe);

            setOnApplyWindowInsetsListener((v, ins) -> { applyInsets(); return ins; });
            if (LyricsSettings.focus) post(() -> setFocus(true));
        }

        /** Raw window insets work even when a parent consumed the dispatched ones. */
        private void applyInsets() {
            WindowInsets wi = getRootWindowInsets();
            if (wi == null) return;
            android.graphics.Insets b = wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            content.setPadding(b.left, b.top, b.right, b.bottom);
        }

        // ------------------------------------------------------------ lifecycle

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            // Spotify's own Now Playing keeps drawing underneath: alpha 0 makes the GPU skip it entirely, while it
            // still receives the touches we forward to it (tapNative); its sheets are separate windows.
            View nativeUi = act.findViewById(android.R.id.content);
            if (nativeUi != null) { nativeUi.setAlpha(0f); nativeUi.setVisibility(INVISIBLE); }
            applyInsets();
            MediaBridge.addListener(this);
            onMetadata(MediaBridge.metadata());
            onState(MediaBridge.state());
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            View nativeUi = act.findViewById(android.R.id.content);          // give Spotify's own screen back
            if (nativeUi != null) { nativeUi.setVisibility(VISIBLE); nativeUi.setAlpha(1f); }
            MediaBridge.removeListener(this);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        // ------------------------------------------------------------ data

        @Override public void onMetadata(MediaMetadata m) {
            if (m == null) return;
            String id = str(m, MediaMetadata.METADATA_KEY_MEDIA_ID);
            String t = str(m, MediaMetadata.METADATA_KEY_TITLE);
            String ar = str(m, MediaMetadata.METADATA_KEY_ARTIST);
            String al = str(m, MediaMetadata.METADATA_KEY_ALBUM);
            durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            title.setText(t);
            artist.setText(ar);
            explicit.setVisibility(m.getLong("android.media.IS_EXPLICIT") == 1L ? VISIBLE : GONE);
            String ctx = str(m, "com.spotify.music.extra.CONTEXT_TITLE");
            context.setText(ctx.isEmpty() ? al : ctx);
            timeTotal.setText(fmt(durationMs));
            lastTitle = t; lastArtist = ar; lastAlbum = al;

            String key = id.isEmpty() ? (t + "|" + ar) : id;
            if (!key.equals(trackKey)) {
                trackKey = key;
                setCover(m);
                lyrics.showMessage("Loading lyrics…");
                requestLyrics(false);
            }
        }

        private void requestLyrics(boolean force) {
            final String k = trackKey;
            LyricsRepo.fetch(act, k, lastTitle, lastArtist, lastAlbum, durationMs, force, (kk, r) -> {
                Log.i(PancakeBootstrap.TAG, "lyrics for " + kk + ": " + (r == null || r.notFound ? "none"
                        : r.source + (r.wordSynced ? " (syllable)" : r.synced() ? " (line)" : " (plain)")));
                if (!kk.equals(trackKey)) return;
                // nothing to show -> the regular Spotify look: one huge cover
                setBigCover(r == null || r.notFound || (r.instrumental && !r.synced() && r.plain == null));
                lyrics.setLyrics(r);
            });
        }

        private void reloadLyrics() {
            lyrics.showMessage("Loading lyrics…");
            requestLyrics(true);
        }

        @Override public void onState(PlaybackState s) {
            if (s == null) return;
            play.type(s.getState() == PlaybackState.STATE_PLAYING ? IconView.PAUSE : IconView.PLAY);
            boolean playingNow = s.getState() == PlaybackState.STATE_PLAYING;
            aurora.setPlaying(playingNow);
            if (playingNow && !lastPlaying) lyrics.snap();          // song (re)started: re-sync the lyrics at once
            lastPlaying = playingNow;
            // custom actions carry the current like / shuffle / repeat state in their ids
            PlaybackState.CustomAction like_ = MediaBridge.findAction("collection", "like", "save", "heart");
            if (like_ == null) like.setVisibility(INVISIBLE);
            else {
                like.setVisibility(VISIBLE);
                boolean liked = String.valueOf(like_.getAction()).toUpperCase().contains("CHECK")
                        || String.valueOf(like_.getName()).toLowerCase().startsWith("remove");
                if (!likeKnown || liked != lastLiked) setLike(liked, likeKnown);
                likeKnown = true;
                lastLiked = liked;
            }
            PlaybackState.CustomAction sh = MediaBridge.findActionById("SHUFFLE");
            boolean shuffleOn = sh != null && String.valueOf(sh.getAction()).toUpperCase().contains("OFF");
            shuffle.color(shuffleOn ? accent : Color.WHITE).dot(shuffleOn).setAlpha(sh == null ? 0.4f : 1f);
            PlaybackState.CustomAction rp = MediaBridge.findActionById("REPEAT");
            String rid = rp == null ? "" : String.valueOf(rp.getAction()).toUpperCase();
            boolean one = rid.contains("REPEAT_OFF"), all = rid.contains("REPEAT_ONE_ON");
            if (repeatIcon != (one ? 2 : 1)) {                       // swap the glyph only when it really changes
                repeatIcon = one ? 2 : 1;
                repeat.host(one ? "encore_icon_repeat_once_24" : "encore_icon_repeat_24", one ? IconView.REPEAT_ONE : IconView.REPEAT);
            }
            repeat.color(one || all ? accent : Color.WHITE).dot(one || all).setAlpha(rp == null ? 0.4f : 1f);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!isAttachedToWindow()) return;
            // DecorView draws its status/navigation bar background as extra children; stay above them
            ViewGroup par = (ViewGroup) getParent();
            if (par != null && par.indexOfChild(this) != par.getChildCount() - 1) bringToFront();
            // Spotify's activity re-applies its own bar colours after we start; keep them transparent
            if (act.getWindow().getStatusBarColor() != Color.TRANSPARENT) act.getWindow().setStatusBarColor(Color.TRANSPARENT);
            if (act.getWindow().getNavigationBarColor() != Color.TRANSPARENT) act.getWindow().setNavigationBarColor(Color.TRANSPARENT);
            long pos = MediaBridge.positionMs();
            if (!seek.dragging) {
                seek.setProgress(durationMs > 0 ? pos / (float) durationMs : 0f);
                long sec = pos / 1000;
                if (sec != shownSec) {                       // relayout the label once a second, not every frame
                    shownSec = sec;
                    timeNow.setText(fmt(pos));
                }
            }
            lyrics.setPosition(pos);
            long now = System.currentTimeMillis();
            if (now - lastDevicePoll > 2000) {
                lastDevicePoll = now;
                String d = MediaBridge.deviceName(act);
                if (!d.contentEquals(deviceText.getText())) deviceText.setText(d);
                deviceIcon.color(d.isEmpty() ? Color.WHITE : accent);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        // ------------------------------------------------------------ cover

        private void setCover(MediaMetadata m) {
            Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b != null) applyCover(b);
            String url = str(m, "com.spotify.music.extra.ART_HTTPS_URI");
            if (!url.isEmpty() && (b == null || b.getWidth() < 500)) {
                final String key = trackKey;
                new Thread(() -> {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(10000);
                        Bitmap hi = android.graphics.BitmapFactory.decodeStream(c.getInputStream());
                        if (hi != null) main.post(() -> { if (key.equals(trackKey)) applyCover(hi); });
                    } catch (Throwable t) { Log.w(PancakeBootstrap.TAG, "cover download failed: " + t); }
                }).start();
            }
        }

        private void applyCover(Bitmap b) {
            cover.setImageBitmap(b);
            aurora.setCover(b);
        }

        // ------------------------------------------------------------ actions & sheets

        /** Plus <-> check with a pop: the filled circle grows from the centre, the check is drawn, the icon bounces. */
        private void setLike(boolean liked, boolean animate) {
            if (likeAnim != null) likeAnim.cancel();
            like.animate().cancel();
            like.type(IconView.HEART).fill(0).ring(0).glyph(0.56f).color(liked ? accent : Color.WHITE);
            if (!animate) {
                like.heartFill(liked ? 1f : 0f).setRotation(0f);
                like.setScaleX(1f); like.setScaleY(1f);
                return;
            }
            likeAnim = ValueAnimator.ofFloat(0f, 1f);
            likeAnim.setDuration(liked ? 520 : 320);
            likeAnim.addUpdateListener(an -> {
                float t = (float) an.getAnimatedValue();
                if (liked) {
                    like.heartFill(Math.min(1f, t / 0.4f));                      // fills with colour...
                    float sc = 0.55f + 0.45f * easeOutBack(Math.min(1f, t / 0.8f));   // ...and pops with an overshoot
                    like.setScaleX(sc); like.setScaleY(sc);
                } else {
                    like.heartFill(1f - Math.min(1f, t / 0.5f));                 // colour drains out
                    float sc = 0.75f + 0.25f * easeOutBack(t);
                    like.setScaleX(sc); like.setScaleY(sc);
                }
            });
            likeAnim.start();
        }

        private float easeOutBack(float t) {
            final float c1 = 1.70158f, c3 = c1 + 1f;
            float u = t - 1f;
            return 1f + c3 * u * u * u + c1 * u * u;
        }

        /**
         * Presses a control of Spotify's own (hidden) Now Playing screen: the touch is dispatched straight to
         * the activity content, bypassing our overlay. Positions are fractions of the window, measured on the
         * reference device (see NATIVE_* constants).
         */
        private void tapNative(float fx, float fy) {
            try {
                final View content = act.findViewById(android.R.id.content);
                View decor = act.getWindow().getDecorView();
                if (content == null) return;
                content.setVisibility(VISIBLE);            // hidden views get no touches: show (alpha is 0) for a moment
                int[] loc = new int[2];
                content.getLocationInWindow(loc);
                final float x = fx * decor.getWidth() - loc[0], y = fy * decor.getHeight() - loc[1];
                final long t0 = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0);
                down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                content.dispatchTouchEvent(down);
                down.recycle();
                main.postDelayed(() -> {
                    MotionEvent up = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
                    up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    content.dispatchTouchEvent(up);
                    up.recycle();
                    main.postDelayed(() -> { if (isAttachedToWindow()) content.setVisibility(INVISIBLE); }, 400);
                }, 70);
            } catch (Throwable t) {
                Log.w(PancakeBootstrap.TAG, "tapNative failed: " + t);
            }
        }

        /** Swaps between the lyrics layout (small cover beside the title) and a huge cover with the title under it. */
        private void setBigCover(boolean on) {
            if (on == bigCover) return;
            bigCover = on;
            TransitionManager.beginDelayedTransition(content);
            LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) cover.getLayoutParams();
            LinearLayout.LayoutParams ilp = (LinearLayout.LayoutParams) info.getLayoutParams();
            LinearLayout.LayoutParams hlp = (LinearLayout.LayoutParams) headerRow.getLayoutParams();
            if (on) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int w = getWidth() > 0 ? getWidth() : dm.widthPixels;
                int side = Math.min(w - 2 * dp(24), (int) (dm.heightPixels * 0.44f));
                headerRow.setOrientation(LinearLayout.VERTICAL);
                clp.width = side; clp.height = side; clp.gravity = Gravity.CENTER_HORIZONTAL;
                ilp.width = -1; ilp.weight = 0f; ilp.leftMargin = 0; ilp.topMargin = dp(22);
                hlp.leftMargin = hlp.rightMargin = dp(24);
            } else {
                headerRow.setOrientation(LinearLayout.HORIZONTAL);
                clp.width = dp(138); clp.height = dp(138); clp.gravity = Gravity.NO_GRAVITY;
                ilp.width = 0; ilp.weight = 1f; ilp.leftMargin = dp(16); ilp.topMargin = 0;
                hlp.leftMargin = hlp.rightMargin = dp(20);
            }
            cover.setLayoutParams(clp);
            info.setLayoutParams(ilp);
            headerRow.setLayoutParams(hlp);
            int big = on ? VISIBLE : GONE;
            lyrics.setVisibility(on ? GONE : VISIBLE);
            spacerTop.setVisibility(big);
            spacerBottom.setVisibility(big);
        }

        private void setFocus(boolean on) {
            focusMode = on;
            TransitionManager.beginDelayedTransition(content);
            int v = on ? GONE : VISIBLE;
            topRow.setVisibility(v);
            headerRow.setVisibility(v);
            bottomRow.setVisibility(v);
        }

        private void shareTrack() {
            try {
                MediaMetadata m = MediaBridge.metadata();
                String id = str(m, MediaMetadata.METADATA_KEY_MEDIA_ID);
                String url = id.startsWith("spotify:track:") ? "https://open.spotify.com/track/" + id.substring(14) : "";
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, (lastTitle + " – " + lastArtist + (url.isEmpty() ? "" : "\n" + url)));
                act.startActivity(Intent.createChooser(i, null));
            } catch (Throwable t) { Log.w(PancakeBootstrap.TAG, "share failed: " + t); }
        }

        // ------------------------------------------------------------ Spicy Lyrics settings

        /** Spicy Lyrics' own settings: size, font, timing, blur, letter animation, focus mode, cache. */
        private void showSpicySettings() {
            final Sheet sheet = new Sheet();
            final LinearLayout list = new LinearLayout(act);
            list.setOrientation(LinearLayout.VERTICAL);

            // size
            final TextView sizeVal = PancakePrefsScreen.text(act, sizeLabel(), 16, Color.WHITE, true);
            list.addView(settingsRow("Lyrics size", stepper(sizeVal,
                    () -> changeSize(-LyricsSettings.SIZE_STEP, sizeVal), () -> changeSize(LyricsSettings.SIZE_STEP, sizeVal))));
            // font
            final TextView[] chips = new TextView[LyricsSettings.FONT_NAMES.length];
            LinearLayout fontRow = new LinearLayout(act);
            for (int i = 0; i < chips.length; i++) {
                final int idx = i;
                chips[i] = chip(LyricsSettings.FONT_NAMES[i], i == LyricsSettings.font);
                chips[i].setOnClickListener(v -> {
                    LyricsSettings.font = idx;
                    LyricsSettings.save(act);
                    for (int k = 0; k < chips.length; k++) styleChip(chips[k], k == idx);
                    lyrics.restyle();
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(34));
                cp.leftMargin = dp(6);
                fontRow.addView(chips[i], cp);
            }
            list.addView(settingsRow("Font", fontRow));
            // timing
            final TextView offVal = PancakePrefsScreen.text(act, offsetLabel(), 16, Color.WHITE, true);
            list.addView(settingsRow("Timing offset", stepper(offVal,
                    () -> { changeOffset(-100); offVal.setText(offsetLabel()); },
                    () -> { changeOffset(100); offVal.setText(offsetLabel()); })));
            // switches
            list.addView(settingsRow("Blur inactive lines", switchView(LyricsSettings.blur, on -> {
                LyricsSettings.blur = on; LyricsSettings.save(act); lyrics.restyle(); })));
            list.addView(settingsRow("Letter-by-letter on long notes", switchView(LyricsSettings.letters, on -> {
                LyricsSettings.letters = on; LyricsSettings.save(act); lyrics.restyle(); })));
            list.addView(settingsRow("Focus mode (lyrics only)", switchView(LyricsSettings.focus, on -> {
                LyricsSettings.focus = on; LyricsSettings.save(act); setFocus(on); })));
            // cache + actions
            long[] st = LyricsRepo.stats(act);
            final TextView cacheInfo = PancakePrefsScreen.text(act,
                    st[0] + (st[0] == 1 ? " track" : " tracks") + " · " + (st[1] / 1024) + " KB cached (kept 3 days)", 13, 0x99FFFFFF, false);
            cacheInfo.setPadding(0, dp(10), 0, dp(6));
            list.addView(cacheInfo);
            LinearLayout actions = new LinearLayout(act);
            TextView reload = pill("Reload lyrics", false);
            reload.setOnClickListener(v -> { sheet.close(); reloadLyrics(); });
            final TextView clear = pill("Clear lyrics cache", false);
            clear.setOnClickListener(v -> {
                LyricsRepo.clearAll(act);
                cacheInfo.setText("Cache cleared");
                reloadLyrics();
            });
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(44), 1f);
            ap.rightMargin = dp(6);
            actions.addView(reload, ap);
            LinearLayout.LayoutParams ap2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
            ap2.leftMargin = dp(6);
            actions.addView(clear, ap2);
            list.addView(actions);
            LinearLayout links = new LinearLayout(act);
            links.setGravity(Gravity.CENTER_VERTICAL);
            TextView reset = PancakePrefsScreen.text(act, "Reset these settings", 14, 0xCCFFFFFF, true);
            reset.setPadding(0, dp(16), dp(20), dp(4));
            reset.setOnClickListener(v -> {
                LyricsSettings.reset(act);
                lyrics.setOffsetMs(0);
                prefs.edit().putLong(KEY_OFFSET, 0).apply();
                setFocus(false);
                sheet.close();
                lyrics.restyle();
            });
            TextView prefsLink = PancakePrefsScreen.text(act, "Pancakeify preferences", 14, 0xCCFFFFFF, true);
            prefsLink.setPadding(0, dp(16), 0, dp(4));
            prefsLink.setOnClickListener(v -> { sheet.close(); PancakePrefsScreen.show(act); });
            links.addView(reset);
            links.addView(prefsLink);
            list.addView(links);

            LinearLayout body = new LinearLayout(act);
            body.setOrientation(LinearLayout.VERTICAL);
            body.addView(PancakePrefsScreen.text(act, "Spicy Lyrics settings", 20, Color.WHITE, true));
            MaxHeightScroll sv = new MaxHeightScroll(act, (int) (getHeight() * 0.74f));
            sv.setOverScrollMode(OVER_SCROLL_NEVER);
            sv.addView(list);
            LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(-1, -2);
            svp.topMargin = dp(8);
            body.addView(sv, svp);
            sheet.show(body);
        }

        private View settingsRow(String label, View trailing) {
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(54));
            TextView t = PancakePrefsScreen.text(act, label, 16, Color.WHITE, false);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(trailing, new LinearLayout.LayoutParams(-2, -2));
            return row;
        }

        private View stepper(TextView value, final Runnable minus, final Runnable plus) {
            LinearLayout box = new LinearLayout(act);
            box.setGravity(Gravity.CENTER_VERTICAL);
            IconView m = new IconView(act, IconView.MINUS).glyph(0.42f).fill(0x26FFFFFF).ring(0x59FFFFFF);
            IconView p = new IconView(act, IconView.PLUS).glyph(0.42f).fill(0x26FFFFFF).ring(0x59FFFFFF);
            m.setOnClickListener(v -> minus.run());
            p.setOnClickListener(v -> plus.run());
            value.setGravity(Gravity.CENTER);
            box.addView(m, new LinearLayout.LayoutParams(dp(38), dp(38)));
            box.addView(value, new LinearLayout.LayoutParams(dp(68), -2));
            box.addView(p, new LinearLayout.LayoutParams(dp(38), dp(38)));
            return box;
        }

        private View switchView(boolean on, final PancakePrefsScreen.Toggle.Listener l) {
            PancakePrefsScreen.Toggle t = new PancakePrefsScreen.Toggle(act, on);
            t.onChange = l;
            return t;
        }

        private TextView chip(String label, boolean selected) {
            TextView c = PancakePrefsScreen.text(act, label, 13, Color.WHITE, true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(12), 0, dp(12), 0);
            styleChip(c, selected);
            return c;
        }

        private void styleChip(TextView c, boolean selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(17));
            if (selected) bg.setColor(Color.WHITE); else { bg.setColor(0x1FFFFFFF); bg.setStroke(dp(1), 0x59FFFFFF); }
            c.setBackground(bg);
            c.setTextColor(selected ? 0xFF121212 : Color.WHITE);
        }

        private TextView pill(String label, boolean filled) {
            TextView b = PancakePrefsScreen.text(act, label, 14, filled ? 0xFF121212 : Color.WHITE, true);
            b.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            if (filled) bg.setColor(Color.WHITE); else { bg.setColor(0x1FFFFFFF); bg.setStroke(dp(1), 0x59FFFFFF); }
            b.setBackground(bg);
            return b;
        }

        private String sizeLabel() { return Math.round(LyricsSettings.size * 100f) + "%"; }

        private void changeSize(float delta, TextView label) {
            float v = Math.round((LyricsSettings.size + delta) / LyricsSettings.SIZE_STEP) * LyricsSettings.SIZE_STEP;
            LyricsSettings.size = Math.max(LyricsSettings.SIZE_MIN, Math.min(LyricsSettings.SIZE_MAX, v));
            LyricsSettings.save(act);
            label.setText(sizeLabel());
            lyrics.restyle();
        }

        /** A ScrollView that never grows past {@code max} px. */
        private final class MaxHeightScroll extends ScrollView {
            private final int max;
            MaxHeightScroll(Context c, int max) { super(c); this.max = max; }
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        }

        private String offsetLabel() {
            long o = prefs.getLong(KEY_OFFSET, 0);
            return (o > 0 ? "+" : "") + String.format("%.1f s", o / 1000f);
        }

        private void changeOffset(long delta) {
            long o = Math.max(-5000, Math.min(5000, prefs.getLong(KEY_OFFSET, 0) + delta));
            prefs.edit().putLong(KEY_OFFSET, o).apply();
            lyrics.setOffsetMs(o);
        }

        /** A translucent bottom sheet over the player. */
        private final class Sheet {
            private FrameLayout layer;

            void show(View body) {
                layer = new FrameLayout(act);
                layer.setBackgroundColor(0x80000000);
                layer.setClickable(true);
                layer.setOnClickListener(v -> close());
                LinearLayout sheet = new LinearLayout(act);
                sheet.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xF0161616);
                float r = dp(18);
                bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                sheet.setBackground(bg);
                sheet.setClickable(true);
                int pad = dp(20);
                sheet.setPadding(pad, pad, pad, pad + content.getPaddingBottom());
                sheet.addView(body);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
                lp.gravity = Gravity.BOTTOM;
                layer.addView(sheet, lp);
                PlayerRoot.this.addView(layer, new LayoutParams(-1, -1));
                layer.setAlpha(0f);
                layer.animate().alpha(1f).setDuration(160).start();
                sheet.setTranslationY(dp(400));
                sheet.animate().translationY(0).setDuration(220).start();
            }

            void close() {
                if (layer == null) return;
                final View l = layer;
                layer = null;
                l.animate().alpha(0f).setDuration(160).withEndAction(() -> PlayerRoot.this.removeView(l)).start();
            }
        }

        // ------------------------------------------------------------ helpers

        private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

        private static String str(MediaMetadata m, String key) {
            if (m == null) return "";
            CharSequence c = m.getText(key);
            return c == null ? "" : c.toString();
        }

        private static String fmt(long ms) {
            long s = Math.max(0, ms) / 1000;
            long sec = s % 60;
            return (s / 60) + (sec < 10 ? ":0" : ":") + sec;
        }

        void previewTime(float f) { timeNow.setText(fmt((long) (f * durationMs))); }

        void commitSeek(float f) { MediaBridge.seekTo((long) (f * durationMs)); }
    }

    /** Centre-cropped cover image. */
    private static final class CoverView extends android.widget.ImageView {
        CoverView(Context c) {
            super(c);
            setScaleType(ScaleType.CENTER_CROP);
            setBackgroundColor(0x22FFFFFF);
        }
    }

    // ======================================================================= seek bar

    private static final class SeekBarView extends View {
        boolean dragging;
        private float progress;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        SeekBarView(Context c) { super(c); }

        void setProgress(float f) {
            f = Math.max(0f, Math.min(1f, f));
            if (Math.abs(f - progress) < 0.0004f) return;          // sub-pixel change: nothing to redraw
            progress = f;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float h = 8f * d, cy = getHeight() / 2f, w = getWidth(), line = Math.max(1f, d);
            // translucent track with a lighter outline, solid white fill, thumb only while dragging
            r.set(line / 2, cy - h / 2 + line / 2, w - line / 2, cy + h / 2 - line / 2);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x24FFFFFF);
            cv.drawRoundRect(r, h / 2, h / 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(line);
            p.setColor(0x66FFFFFF);
            cv.drawRoundRect(r, h / 2, h / 2, p);
            p.setStyle(Paint.Style.FILL);
            float x = progress * w;
            r.set(0, cy - h / 2, Math.max(x, h), cy + h / 2);
            p.setColor(Color.WHITE);
            cv.drawRoundRect(r, h / 2, h / 2, p);
            if (dragging) cv.drawCircle(Math.max(h / 2, Math.min(w - h / 2, x)), cy, 8f * d, p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float f = Math.max(0f, Math.min(1f, e.getX() / getWidth()));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    setProgress(f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    setProgress(f);
                    ((PlayerRoot) getRootTag()).previewTime(f);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setProgress(f);
                    dragging = false;
                    ((PlayerRoot) getRootTag()).commitSeek(f);
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        private View getRootTag() {
            View v = this;
            while (v.getParent() instanceof View && !(v instanceof PlayerRoot)) v = (View) v.getParent();
            return v;
        }
    }
}

package com.pancakeify.stable;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Outline;
import android.net.Uri;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.UnderlineSpan;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Animated lyrics modelled on Spicy Lyrics (https://github.com/Spikerko/spicy-lyrics, by Spikerko,
 * AGPL-3.0 -- used here with the author's permission and credit). The behaviour follows its
 * LyricsAnimator: every word of the active line scales, lifts and glows while it is sung, driven
 * by damped springs (scale 0.88 Hz / 0.64, lift 1.45 Hz / 0.40, glow 1.18 Hz / 0.56) along the
 * same keyframe curves; sung text fills left to right, lines around the active one fade and blur
 * with distance, and instrumental gaps show three dots.
 *
 * The code itself is a fresh native implementation (Canvas + springs), not a port of the
 * TypeScript source. With Spicy API data every syllable is timed individually, duet partners are
 * right-aligned and background vocals sit under their line in smaller type; with line-synced
 * LRCLIB data the words inside a line are timed by length.
 */
public final class LyricsView extends ScrollView {
    public interface SeekListener { void seekTo(long ms); }

    private static final int WHITE = 0xFFFFFFFF;
    // Opacity model after Spicy Lyrics' (Mixed.css: not sung .51, active 1, sung .497 as row opacity), tuned
    // by eye against lyrics.png where lines below the active one stay clearly visible.
    //   live (active line):  unsung text A_UNSUNG, sung text A_SUNG   (+ glow)
    //   resting lines:       row opacity OP_* x text alpha S_*
    // The numbers are ordered so a line only gets brighter when it becomes active (no dip, no "flash").
    private static final float A_UNSUNG = 0.55f, A_SUNG = 0.95f, A_BG_UNSUNG = 0.40f, A_BG_SUNG = 0.70f;
    private static final float S_NOTSUNG = 0.60f, S_SUNG = 0.70f;
    private static final float OP_NOTSUNG = 0.60f, OP_SUNG = 0.75f;
    private static final float READ_TEXT = 0.85f, READ_ROW = 0.95f;      // free scrolling: everything readable
    private static final float FONT_DP = 29f;      // Spicy: clamp(1.85rem, 7cqw, 3.5rem) at phone width
    private static final long GAP_MS = 3000;           // Spicy getLyricsBetweenShow(): interludes from 3 s
    private static final long GAP_END_PAD_MS = 250;    // dots finish a little before the next line starts
    private static final int DOTS_H = 76;               // dp, full height of an open interlude row
    private static final float BG_SCALE = 0.75f;     // background vocals: 0.75x, weight 600 (Spicy)

    private final LinearLayout column;
    private final FrameLayout holder;
    private final TextView message;
    private final List<Row> rows = new ArrayList<>();
    private boolean synced;
    private int active = -1;
    private long userScrollUntil;
    private long offsetMs = 0;
    private ValueAnimator scrollAnim;
    private boolean snapNext = true;         // the very first position after (re)loading lands without animation
    private long lastRawPos = -1;
    private boolean freeMode;                // user is scrolling: everything readable, nothing blurred
    private SeekListener seekListener;
    private View topSp, botSp, creditsView;
    private float anchor = 0.14f;           // active line sits at this fraction of the height

    private static final class Row {
        long start, end;
        boolean gap;
        View view;
        LineView line;       // null for gaps
        float lastAlpha = -1f, lastScale = -1f;
        float blurNow = -1f, blurGoal = -1f;      // blur sigma in dp, animated
        ValueAnimator blurAnim;
    }

    private LyricsRepo.Result lastResult;

    public LyricsView(Context c) {
        super(c);
        LyricsSettings.load(c);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(dp(70));
        holder = new FrameLayout(c);
        holder.setClipChildren(false);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        holder.addView(column, new FrameLayout.LayoutParams(-1, -2));
        addView(holder, new FrameLayout.LayoutParams(-1, -2));
        message = new TextView(c);
        message.setTextColor(0xB3FFFFFF);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        message.setTypeface(LyricsFont.get(c, false));
        message.setGravity(Gravity.START);
    }

    public void setSeekListener(SeekListener l) { seekListener = l; }
    public void setAnchor(float a) { anchor = a; if (getHeight() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight()); }
    public void setOffsetMs(long ms) { offsetMs = ms; }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) userScrollUntil = SystemClock.uptimeMillis() + 4000;
        return super.onInterceptTouchEvent(e);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE)
            userScrollUntil = SystemClock.uptimeMillis() + 4000;
        return super.onTouchEvent(e);
    }

    // ------------------------------------------------------------------------- content

    public void showMessage(String text) {
        clearRows();
        message.setText(text);
        message.setPadding(dp(22), dp(40), dp(22), dp(40));
        column.addView(message, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Rebuilds the lines with the current settings (size / font / letter animation) and re-syncs. */
    public void restyle() {
        LyricsFont.reset();
        if (lastResult != null) setLyrics(lastResult);
    }

    public void setLyrics(LyricsRepo.Result r) {
        lastResult = r;
        clearRows();
        if (r == null || r.notFound) { showMessage("No lyrics found for this track"); return; }
        if (r.instrumental && !r.synced() && r.plain == null) { showMessage("Instrumental"); return; }
        final int pad = getHeight() > 0 ? getHeight() : dp(300);
        topSp = addSpacer((int) (pad * anchor));
        if (r.synced()) {
            synced = true;
            List<LyricsRepo.Line> ls = r.lines;
            if (ls.get(0).startMs >= GAP_MS) addGap(0, ls.get(0).startMs);
            for (int i = 0; i < ls.size(); i++) {
                LyricsRepo.Line l = ls.get(i);
                long next = i + 1 < ls.size() ? ls.get(i + 1).startMs : Math.max(l.endMs, l.startMs + 5000);
                if (l.text.isEmpty()) {
                    if (next - l.startMs >= GAP_MS) addGap(l.startMs, next);
                } else {
                    addRow(l.startMs, next, l, false);
                    // instrumental break after a line whose sung end we know (syllable / line data)
                    if (l.endMs > l.startMs && i + 1 < ls.size() && next - l.endMs >= GAP_MS)
                        addGap(l.endMs, next);
                }
            }
        } else {
            synced = false;
            for (String s : r.plain.split("\n")) {
                if (s.trim().isEmpty()) { addSpacer(dp(14)); continue; }
                Row row = addRow(0, 0, new LyricsRepo.Line(0, s.trim()), false);
                row.view.setAlpha(0.92f);
            }
        }
        if (r.fromSpicy) {
            creditsView = buildCredits(r);
            creditsView.addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, or, ob) -> updateBottomSpacer());
            column.addView(creditsView, new LinearLayout.LayoutParams(-1, -2));
        }
        botSp = addSpacer((int) (pad * (1f - anchor)));
        active = -1;
        snapNext = true;
        lastRawPos = -1;
        scrollTo(0, 0);
        applyStates(-1, true);
    }

    private void clearRows() {
        column.removeAllViews();
        topSp = botSp = creditsView = null;
        rows.clear();
        active = -1;
        synced = false;
        if (scrollAnim != null) scrollAnim.cancel();
        scrollTo(0, 0);
    }

    private View addSpacer(int h) {
        View v = new View(getContext());
        column.addView(v, new LinearLayout.LayoutParams(1, h));
        return v;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (topSp != null) topSp.getLayoutParams().height = (int) (h * anchor);
        updateBottomSpacer();
        column.requestLayout();
    }

    /**
     * Room under the last line so it can still reach the anchor position. The credits sheet already
     * fills part of that room, so with credits the spacer shrinks (no huge empty page at the end).
     */
    private void updateBottomSpacer() {
        if (botSp == null) return;
        int h = getHeight() > 0 ? getHeight() : dp(300);
        int want = (int) (h * (1f - anchor));
        if (creditsView != null && !rows.isEmpty()) {
            want = Math.max(dp(32), want - creditsView.getHeight() - rows.get(rows.size() - 1).view.getHeight());
        }
        if (botSp.getLayoutParams().height != want) {
            botSp.getLayoutParams().height = want;
            column.requestLayout();
        }
    }

    private void addGap(long start, long nextLineStart) {
        addRow(start, Math.max(start + 500, nextLineStart - GAP_END_PAD_MS), null, true);
    }

    private Row addRow(long start, long end, LyricsRepo.Line line, boolean gap) {
        final Row row = new Row();
        row.start = start; row.end = end; row.gap = gap;
        if (gap) {
            DotsView dv = new DotsView(getContext());
            row.view = dv;
            dv.setAlpha(0f);
            column.addView(dv, new LinearLayout.LayoutParams(-1, 0));       // collapsed until it is time
        } else {
            LineView lv = new LineView(getContext(), line);
            row.line = lv;
            row.view = lv;
            column.addView(lv, new LinearLayout.LayoutParams(-1, -2));
        }
        row.view.setPivotX(0f);
        if (synced || gap) {
            row.view.setOnClickListener(v -> { if (seekListener != null) seekListener.seekTo(row.start); });
        }
        rows.add(row);
        return row;
    }

    // ------------------------------------------------------------------------ animation

    /** Called every frame with the current playback position. */
    public void setPosition(long rawPosMs) {
        if (!synced || rows.isEmpty()) return;
        long posMs = rawPosMs + offsetMs;
        // a seek / restart / long stall: re-sync everything instead of animating through the skipped lines
        if (lastRawPos >= 0 && (rawPosMs - lastRawPos < -300 || rawPosMs - lastRawPos > 1500)) snap();
        lastRawPos = rawPosMs;
        boolean free = SystemClock.uptimeMillis() < userScrollUntil;
        if (free != freeMode) {                  // scrolling started / ended -> re-style all lines
            freeMode = free;
            applyStates(active, false);
            if (!free) scrollToActive();
        }
        int idx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).start <= posMs + 40) idx = i; else break;
        }
        if (idx != active) {
            int prev = active;
            active = idx;
            if (snapNext) {                          // re-sync: no animations, everything lands where it belongs
                snapNext = false;
                for (Row r : rows) if (r.line != null) r.line.reset();
                applyStates(idx, true);
                post(this::scrollInstantToActive);   // after layout: collapsed interludes just changed height
            } else {
                if (prev >= 0 && prev < rows.size()) deactivate(rows.get(prev));
                applyStates(idx, false);
                scrollToActive();
            }
        }
        // the active line plus the two before it: earlier lines may still be sung (duets, overlaps)
        // or settling their springs
        for (int k = Math.max(0, active - 2); k <= active && k >= 0; k++) {
            Row r = rows.get(k);
            if (r.gap) {
                ((DotsView) r.view).update(r.start, r.end, posMs, k == active);
            } else {
                r.line.update(posMs, k == active);
            }
        }
    }

    private void deactivate(Row r) {
        if (!r.gap) r.line.finish();
    }

    /** Forces the next position update to re-sync from scratch (new song, play after pause, seek). */
    public void snap() {
        snapNext = true;
        active = -2;
    }

    private void scrollInstantToActive() {
        if (active < 0 || active >= rows.size()) return;
        if (scrollAnim != null) scrollAnim.cancel();
        View v = rows.get(active).view;
        scrollTo(0, Math.max(0, v.getTop() - (int) (getHeight() * anchor)));
    }

    /**
     * Styles every row by its distance from the active one, with Spicy Lyrics' numbers: row opacity
     * .51 (not sung) / 1 (active) / .497 (sung), blur radius min(1.25 x distance, 7) px, the same on both
     * sides. While the user scrolls, everything is readable: no blur, near-full opacity.
     */
    private void applyStates(int idx, boolean instant) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int d = idx < 0 ? i + 1 : i - idx;          // > 0: not sung yet, < 0: sung
            if (r.gap) {                                // interludes only exist while they are happening
                ((DotsView) r.view).setOpen(synced && d == 0, instant);
                continue;
            }
            float alpha, scale, blurDp;
            if (!synced) { alpha = 0.92f; scale = 1f; blurDp = 0f; }
            else if (freeMode) { alpha = READ_ROW; scale = 1f; blurDp = 0f; }
            else if (d == 0) { alpha = 1f; scale = 1f; blurDp = 0f; }
            else {
                alpha = d > 0 ? OP_NOTSUNG : OP_SUNG;
                scale = 0.96f;
                blurDp = LyricsSettings.blur ? Math.min(1.25f * Math.abs(d), 7f) * 0.7f : 0f;   // CSS radius -> Gaussian sigma
            }
            if (r.line != null) {
                r.line.setSungState(!synced ? 1 : d == 0 ? 1 : d > 0 ? 0 : 2);
                r.line.setReadable(freeMode && synced);
            }
            if (r.lastAlpha != alpha || r.lastScale != scale) {
                if (instant) { r.view.setAlpha(alpha); r.view.setScaleX(scale); r.view.setScaleY(scale); }
                else r.view.animate().alpha(alpha).scaleX(scale).scaleY(scale).setDuration(freeMode ? 220 : 420).start();
                r.lastAlpha = alpha; r.lastScale = scale;
            }
            setBlur(r, blurDp, instant);
        }
    }

    /** Animates a row's blur so lines sharpen / soften smoothly instead of snapping. */
    private void setBlur(final Row r, float goalDp, boolean instant) {
        if (r.blurGoal == goalDp) return;
        r.blurGoal = goalDp;
        if (r.blurAnim != null) r.blurAnim.cancel();
        final float from = Math.max(0f, r.blurNow);
        if (instant || from == goalDp) { r.blurNow = goalDp; applyBlur(r.view, goalDp); return; }
        r.blurAnim = ValueAnimator.ofFloat(from, goalDp);
        r.blurAnim.setDuration(380);
        r.blurAnim.addUpdateListener(an -> {
            r.blurNow = (float) an.getAnimatedValue();
            applyBlur(r.view, r.blurNow);
        });
        r.blurAnim.start();
    }

    private void applyBlur(View v, float sigmaDp) {
        if (sigmaDp < 0.15f) v.setRenderEffect(null);
        else {
            float px = dp(1) * sigmaDp;
            v.setRenderEffect(RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP));
        }
    }

    /** Smoothly centres the active line; the target is re-read every frame because interludes open/close. */
    private void scrollToActive() {
        if (active < 0 || active >= rows.size()) return;
        if (SystemClock.uptimeMillis() < userScrollUntil) return;
        final View v = rows.get(active).view;
        if (scrollAnim != null) scrollAnim.cancel();
        final int from = getScrollY();
        scrollAnim = ValueAnimator.ofFloat(0f, 1f);
        scrollAnim.setDuration(600);
        scrollAnim.setInterpolator(new DecelerateInterpolator(1.8f));
        scrollAnim.addUpdateListener(an -> {
            int target = Math.max(0, v.getTop() - (int) (getHeight() * anchor));
            scrollTo(0, from + Math.round((target - from) * (float) an.getAnimatedValue()));
        });
        scrollAnim.start();
    }

    // ======================================================================== credits

    /** "Written by / Provided by / community" sheet shown after the last line (Spicy Lyrics style). */
    private View buildCredits(LyricsRepo.Result r) {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(34), dp(22), dp(12));
        if (r.writers != null && !r.writers.isEmpty()) {
            box.addView(creditText("Written by: " + android.text.TextUtils.join(", ", r.writers), 17, 0xB3FFFFFF), -1, -2);
        }
        box.addView(creditText("Provided by: " + providerName(r.source), 14, 0x99FFFFFF), -1, -2);
        if (r.maker != null || r.uploader != null) {
            View gapV = new View(c);
            box.addView(gapV, new LinearLayout.LayoutParams(1, dp(6)));
            box.addView(creditText("These lyrics have been provided by our community", 15, 0x99FFFFFF), -1, -2);
            if (r.maker != null) box.addView(personRow("Made by ", r.maker), -1, -2);
            if (r.uploader != null) box.addView(personRow("Uploaded by ", r.uploader), -1, -2);
        }
        return box;
    }

    private static String providerName(String src) {
        if (src == null) return "Spicy Lyrics";
        switch (src) {
            case "spicy_lyrics": return "Spicy Lyrics";
            case "apple_music": return "Apple Music";
            case "musixmatch": return "Musixmatch";
            case "netease": return "NetEase";
            default: return "Spicy Lyrics";
        }
    }

    private TextView creditText(String t, int sp, int color) {
        TextView tv = new TextView(getContext());
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(LyricsFont.get(getContext(), false));
        tv.setPadding(0, dp(3), 0, dp(3));
        return tv;
    }

    private View personRow(String label, final LyricsRepo.Person p) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        SpannableString ss = new SpannableString(label + "@" + p.name);
        int from = label.length();
        ss.setSpan(new UnderlineSpan(), from, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ClickableSpan() {
            @Override public void onClick(View w) {
                try {
                    if (!p.url.isEmpty()) {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(p.url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        w.getContext().startActivity(i);
                    }
                } catch (Throwable ignored) {}
            }
            @Override public void updateDrawState(android.text.TextPaint t) { t.setUnderlineText(true); }
        }, from, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView tv = creditText("", 15, 0x99FFFFFF);
        tv.setText(ss);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setHighlightColor(0x33FFFFFF);
        tv.setLinkTextColor(0x99FFFFFF);
        row.addView(tv);
        if (!p.avatar.isEmpty()) {
            final ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
            iv.setClipToOutline(true);
            iv.setAlpha(0.8f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
            lp.leftMargin = dp(8);
            row.addView(iv, lp);
            final String url = p.avatar.replaceAll("\\.(gif|webp)", ".png").replace("size=256", "size=64");
            new Thread(() -> {
                try {
                    java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    h.setConnectTimeout(6000);
                    h.setReadTimeout(8000);
                    final Bitmap bm = android.graphics.BitmapFactory.decodeStream(h.getInputStream());
                    if (bm != null) iv.post(() -> iv.setImageBitmap(bm));
                } catch (Throwable ignored) {}
            }).start();
        }
        return row;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ===================================================================== one lyric line

    /** Damped spring (frequency in Hz, damping ratio) like the ones Spicy Lyrics animates words with. */
    private static final class Spring {
        float x, v;
        void step(float target, float dt, float freq, float damping) {
            float w = (float) (2 * Math.PI * freq);
            int n = Math.max(1, (int) Math.ceil(dt / 0.008f));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                float a = w * w * (target - x) - 2f * damping * w * v;
                v += a * h;
                x += v * h;
            }
        }
        boolean settled(float target) { return Math.abs(x - target) < 0.002f && Math.abs(v) < 0.01f; }
    }

    /** One letter of a long held syllable (Spicy's letterGroup): its own springs, timed as an equal slice of the word. */
    private static final class Letter {
        long t0, t1;
        float prog, width;
        final Spring sc = new Spring(), yo = new Spring(), gl = new Spring();
    }

    private static final class Word {
        int s, e;
        long t0, t1;
        float prog, width;
        Letter[] letters;                       // non-null for syllables held >= 1 s
        final Spring sc = new Spring(), yo = new Spring(), gl = new Spring();
    }

    // Spicy Lyrics LetterScaleRange / LetterYOffsetRange (y in em, drawn x2), glow as for words
    private static final float[] LSC_T = {0f, 0.7f, 1f}, LSC_V = {0.95f, 1.175f, 1f};
    private static final float[] LYO_T = {0f, 0.9f, 1f}, LYO_V = {1f / 100f, -1f / 56f, 0f};
    private static final long LETTER_MIN_MS = 1000, LETTER_TAIL_MS = 250;

    private static boolean isRtl(String t) {
        for (int i = 0; i < t.length(); i++) {
            byte d = Character.getDirectionality(t.charAt(i));
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
        }
        return false;
    }

    private static float easeSinOut(float t) { return (float) Math.sin(Math.max(0f, Math.min(1f, t)) * Math.PI / 2.0); }

    private static float keyframe(float[] t, float[] v, float p) {
        for (int i = 1; i < t.length; i++) {
            if (p <= t[i]) {
                float f = (p - t[i - 1]) / (t[i] - t[i - 1]);
                f = f * f * (3 - 2 * f);                       // smoothstep between keys
                return v[i - 1] + (v[i] - v[i - 1]) * f;
            }
        }
        return v[v.length - 1];
    }

    // Spicy Lyrics LyricsAnimator keyframes (scale / y-offset in em / glow)
    private static final float[] SC_T = {0f, 0.7f, 1f}, SC_V = {0.95f, 1.0505f, 1f};
    private static final float[] YO_T = {0f, 0.9f, 1f}, YO_V = {0.01f, -1f / 60f, 0f};
    private static final float[] GL_T = {0f, 0.15f, 0.6f, 1f}, GL_V = {0f, 1f, 1f, 0f};

    /** One run of text inside a line: the lead vocal, or one background vocal in smaller type. */
    private final class Block {
        final String text;
        final boolean bg;
        final float fontPx;
        final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final List<Word> words = new ArrayList<>();
        final long start, end;
        StaticLayout layout;
        int top;

        Block(Context c, String text, boolean bg, List<LyricsRepo.Syl> syl, long start, long end) {
            this.text = text; this.bg = bg; this.start = start; this.end = end;
            fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    (bg ? FONT_DP * BG_SCALE : FONT_DP) * LyricsSettings.size, c.getResources().getDisplayMetrics());
            tp.setTextSize(fontPx);
            tp.setColor(WHITE);
            tp.setTypeface(LyricsFont.get(c, bg));
            if (syl != null) fromSyllables(syl); else estimateWords();
            for (Word w : words) {
                w.sc.x = SC_V[0];
                w.yo.x = YO_V[0] * fontPx;
            }
        }

        /** Every syllable is its own animated unit; its text range is located inside the joined text. */
        private void fromSyllables(List<LyricsRepo.Syl> syl) {
            int pos = 0;
            for (int i = 0; i < syl.size(); i++) {
                LyricsRepo.Syl sy = syl.get(i);
                Word w = new Word();
                w.s = pos;
                w.e = pos + sy.text.length();
                w.t0 = sy.startMs;
                w.t1 = sy.endMs;
                if (LyricsSettings.letters && sy.endMs - sy.startMs >= LETTER_MIN_MS && sy.text.length() > 1
                        && !isRtl(sy.text)) {
                    int n = sy.text.length();
                    long end = Math.max(sy.startMs + 200, sy.endMs - LETTER_TAIL_MS);
                    float ld = (end - sy.startMs) / (float) n;
                    w.letters = new Letter[n];
                    for (int k = 0; k < n; k++) {
                        Letter L = new Letter();
                        L.t0 = sy.startMs + Math.round(k * ld);
                        L.t1 = sy.startMs + Math.round((k + 1) * ld);
                        L.sc.x = LSC_V[0];
                        L.yo.x = LYO_V[0];
                        w.letters[k] = L;
                    }
                }
                words.add(w);
                pos = w.e + (i + 1 < syl.size() && !sy.partOfWord ? 1 : 0);
            }
        }

        /** Line-synced source: spread the line's time over its words by length. */
        private void estimateWords() {
            long span = end - start;
            long dur = Math.max(900, Math.min(span > 0 ? span - 80 : 3000, text.length() * 70L));
            int total = 0;
            int i = 0, n = text.length();
            List<int[]> spans = new ArrayList<>();
            while (i < n) {
                while (i < n && text.charAt(i) == ' ') i++;
                int s = i;
                while (i < n && text.charAt(i) != ' ') i++;
                if (i > s) spans.add(new int[]{s, i});
            }
            for (int[] sp : spans) total += sp[1] - sp[0] + 1;
            int acc = 0;
            for (int[] sp : spans) {
                Word w = new Word();
                w.s = sp[0]; w.e = sp[1];
                int len = sp[1] - sp[0] + 1;
                w.t0 = start + dur * acc / Math.max(1, total);
                w.t1 = start + dur * (acc + len) / Math.max(1, total);
                acc += len;
                words.add(w);
            }
        }

        /** How visible a background vocal is at {@code pos}: faint ahead of time, full while sung, then rests. */
        float visibility(long pos) {
            if (!bg) return 1f;
            if (pos < start - 300) return 0.25f;
            if (pos < start) return 0.25f + 0.75f * (pos - (start - 300)) / 300f;
            if (pos <= end) return 1f;
            return Math.max(0.6f, 1f - 0.4f * (pos - end) / 600f);
        }
    }

    private final class LineView extends View {
        private final List<Block> blocks = new ArrayList<>();
        private final Paint wp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean opposite;
        private final int padH, padV, blockGap;
        private final long lineEnd;
        private boolean live, everLive, readable;
        private int sungState;                   // 0 not sung yet, 1 active, 2 sung
        private long settleUntil, lastPos;
        private long lastNs;

        LineView(Context c, LyricsRepo.Line line) {
            super(c);
            padH = dp(22);
            padV = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, FONT_DP * 0.17f * LyricsSettings.size,
                    c.getResources().getDisplayMetrics()));
            blockGap = dp(2);
            opposite = line.opposite;
            long end = line.endMs > line.startMs ? line.endMs : line.startMs;
            blocks.add(new Block(c, line.text, false, line.syl, line.startMs, end));
            if (line.bg != null) {
                for (LyricsRepo.Part p : line.bg) {
                    blocks.add(new Block(c, LyricsRepo.joinSyllables(p.syl), true, p.syl, p.startMs, p.endMs));
                    end = Math.max(end, p.endMs);
                }
            }
            lineEnd = end;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            setPivotX(opposite ? w : 0f);            // duet partner scales around its right edge
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int inner = Math.max(1, w - 2 * padH);
            Layout.Alignment al = opposite ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
            int y = 0;
            for (Block b : blocks) {
                b.layout = StaticLayout.Builder.obtain(b.text, 0, b.text.length(), b.tp, inner)
                        .setAlignment(al)
                        .setLineSpacing(0f, 0.977f)           // Inter is 1.21em tall; Spicy's line-height is 1.1818
                        .setIncludePad(false)
                        .build();
                for (Word wd : b.words) {
                    wd.width = b.tp.measureText(b.text, wd.s, wd.e);
                    if (wd.letters != null)
                        for (int k = 0; k < wd.letters.length; k++)
                            wd.letters[k].width = b.tp.measureText(b.text, wd.s + k, wd.s + k + 1);
                }
                b.top = y;
                y += b.layout.getHeight() + blockGap;
            }
            setMeasuredDimension(w, y - blockGap + 2 * padV);
        }

        void update(long posMs, boolean isActive) {
            if (isActive) { live = true; everLive = true; }
            if (!live) return;
            lastPos = posMs;
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
            lastNs = now;
            for (Block b : blocks) {
                for (Word w : b.words) {
                    if (w.letters != null) { updateLetters(w, b, posMs, dt); continue; }
                    w.prog = w.t1 > w.t0 ? Math.max(0f, Math.min(1f, (posMs - w.t0) / (float) (w.t1 - w.t0)))
                                         : (posMs >= w.t0 ? 1f : 0f);
                    w.sc.step(keyframe(SC_T, SC_V, w.prog), dt, 0.88f, 0.64f);
                    w.yo.step(keyframe(YO_T, YO_V, w.prog) * b.fontPx, dt, 1.45f, 0.40f);
                    w.gl.step(keyframe(GL_T, GL_V, w.prog), dt, 1.18f, 0.56f);
                }
            }
            invalidate();
        }

        /** Spicy's letter proximity animation: the active letter pops, its neighbours follow with a steep falloff. */
        private void updateLetters(Word w, Block b, long posMs, float dt) {
            int n = w.letters.length;
            long start = w.t0, end = Math.max(start + 200, w.t1 - LETTER_TAIL_MS);
            float ld = (end - start) / (float) n;
            int act = -1;
            float actPct = 0f;
            if (posMs >= start && posMs < end) {
                act = Math.min(n - 1, (int) ((posMs - start) / ld));
                actPct = ((posMs - start) - act * ld) / ld;
            }
            for (int k = 0; k < n; k++) {
                Letter L = w.letters[k];
                int state = posMs < L.t0 ? 0 : (posMs >= L.t1 ? 2 : 1);
                float tSc = LSC_V[0], tYo = LYO_V[0], tGl = 0f;
                if (act >= 0) {
                    float dist = Math.abs(k - act);
                    float fall = (float) (1.0 / (1.0 + Math.pow(dist, 2.8)));
                    float gfall = 1f / (1f + dist * 0.9f);
                    tSc = LSC_V[0] + (keyframe(LSC_T, LSC_V, actPct) - LSC_V[0]) * fall;
                    tYo = LYO_V[0] + (keyframe(LYO_T, LYO_V, actPct) - LYO_V[0]) * fall;
                    tGl = keyframe(GL_T, GL_V, actPct) * gfall;
                }
                if (state == 0) { tSc = LSC_V[0]; tYo = LYO_V[0]; tGl = 0f; }      // not sung yet: resting
                else if (state == 2 && act == -1) tGl = 0.2f;                       // sung: faint afterglow
                L.prog = state == 0 ? 0f : state == 2 ? 1f : easeSinOut((posMs - L.t0) / (float) Math.max(1, L.t1 - L.t0));
                L.sc.step(tSc, dt, 0.88f, 0.64f);
                L.yo.step(tYo, dt, 1.45f, 0.40f);
                L.gl.step(tGl, dt, 1.18f, 0.56f);
            }
            w.prog = posMs < start ? 0f : posMs >= end ? 1f : (posMs - start) / (float) Math.max(1, end - start);
        }

        /** Back to the resting look (after a seek or restart). */
        void reset() {
            live = false; everLive = false; settleUntil = 0; lastNs = 0;
            for (Block b : blocks) {
                for (Word w : b.words) {
                    w.prog = 0f;
                    w.sc.x = SC_V[0]; w.sc.v = 0f;
                    w.yo.x = YO_V[0] * b.fontPx; w.yo.v = 0f;
                    w.gl.x = 0f; w.gl.v = 0f;
                    if (w.letters != null) for (Letter L : w.letters) {
                        L.prog = 0f;
                        L.sc.x = LSC_V[0]; L.sc.v = 0f;
                        L.yo.x = LYO_V[0]; L.yo.v = 0f;
                        L.gl.x = 0f; L.gl.v = 0f;
                    }
                }
            }
            invalidate();
        }

        void setSungState(int st) {
            if (st == sungState) return;
            sungState = st;
            if (!live) invalidate();
        }

        void setReadable(boolean r) {
            if (r == readable) return;
            readable = r;
            if (!live) invalidate();
        }

        /** Line is no longer the active one: let it finish being sung and the springs settle at rest. */
        void finish() {
            settleUntil = SystemClock.uptimeMillis() + 1100;
            live = true;
            everLive = true;
            lastNs = 0;
        }

        private boolean settled() {
            if (lastPos < lineEnd) return false;                     // still being sung (overlap / duet)
            for (Block b : blocks)
                for (Word w : b.words) {
                    if (w.letters != null) {
                        for (Letter L : w.letters)
                            if (!L.sc.settled(1f) || !L.yo.settled(LYO_V[2]) || !L.gl.settled(0.2f)) return false;
                        continue;
                    }
                    if (!w.sc.settled(1f) || !w.yo.settled(0f) || !w.gl.settled(0f)) return false;
                }
            return true;
        }

        /** Each letter of a long held syllable on its own: scale pop, tiny lift, fill sweep and a strong glow. */
        private void drawLetters(Canvas cv, Block b, Word w, int cSung, int cUn) {
            for (int i = 0; i < w.letters.length; i++) {
                Letter L = w.letters[i];
                int off = w.s + i;
                if (b.text.charAt(off) == ' ') continue;
                float x = padH + b.layout.getPrimaryHorizontal(off);
                float base = padV + b.top + b.layout.getLineBaseline(b.layout.getLineForOffset(off));
                cv.save();
                cv.translate(x + L.width / 2f, base + L.yo.x * 2f * b.fontPx);
                cv.scale(L.sc.x, L.sc.x, 0f, -b.fontPx * 0.3f);
                cv.translate(-L.width / 2f, 0f);
                if (L.prog <= 0f) { wp.setShader(null); wp.setColor(cUn); }
                else if (L.prog >= 1f) { wp.setShader(null); wp.setColor(cSung); }
                else {
                    wp.setColor(WHITE);
                    wp.setShader(new LinearGradient(0, 0, Math.max(1f, L.width), 0, new int[]{cSung, cSung, cUn, cUn},
                            new float[]{0f, L.prog, Math.min(1f, L.prog + 0.2f), 1f}, Shader.TileMode.CLAMP));
                }
                float g = Math.max(0f, Math.min(1f, L.gl.x));
                // Spicy: text-shadow 4 + 12g px at up to 185% opacity (we keep it a little softer)
                if (g > 0.03f) wp.setShadowLayer(dp(4) + dp(12) * g, 0f, 0f,
                        ((int) (Math.min(1f, g * 1.85f) * 0.75f * 255f) << 24) | 0xFFFFFF);
                else wp.clearShadowLayer();
                cv.drawText(b.text, off, off + 1, 0f, 0f, wp);
                cv.restore();
            }
            wp.clearShadowLayer();
        }

        private int alpha(int argb, float f) {
            return (Math.round((argb >>> 24) * f) << 24) | (argb & 0xFFFFFF);
        }

        @Override protected void onDraw(Canvas cv) {
            if (blocks.isEmpty() || blocks.get(0).layout == null) return;
            wp.setStyle(Paint.Style.FILL);
            if (live && settleUntil != 0 && SystemClock.uptimeMillis() > settleUntil
                    && (settled() || SystemClock.uptimeMillis() > settleUntil + 1500 + Math.max(0, lineEnd - lastPos))) {
                live = false;
                settleUntil = 0;
            }
            if (!live) {
                for (Block b : blocks) {
                    cv.save();
                    cv.translate(padH, padV + b.top);
                    float ta = readable ? READ_TEXT : (sungState == 2 ? S_SUNG : S_NOTSUNG);
                    if (b.bg) ta *= (everLive || sungState == 2) ? 0.7f : 0.85f;   // resting background vocal is dimmer
                    b.tp.setAlpha(Math.round(255 * ta));
                    b.layout.draw(cv);
                    cv.restore();
                }
                return;
            }
            for (Block b : blocks) {
                final float vis = b.visibility(lastPos);
                final float aSung = b.bg ? A_BG_SUNG : A_SUNG, aUn = b.bg ? A_BG_UNSUNG : A_UNSUNG;
                final int cSung = alpha(WHITE, aSung * vis), cUn = alpha(WHITE, aUn * vis);
                wp.setTextSize(b.fontPx);
                wp.setTypeface(b.tp.getTypeface());
                for (Word w : b.words) {
                    if (w.letters != null) { drawLetters(cv, b, w, cSung, cUn); continue; }
                    int ln = b.layout.getLineForOffset(w.s);
                    float x = padH + b.layout.getPrimaryHorizontal(w.s);
                    float base = padV + b.top + b.layout.getLineBaseline(ln);
                    cv.save();
                    cv.translate(x + w.width / 2f, base + w.yo.x);
                    cv.scale(w.sc.x, w.sc.x, 0f, -b.fontPx * 0.3f);
                    cv.translate(-w.width / 2f, 0f);
                    float p = w.prog;
                    if (p <= 0f) {
                        wp.setShader(null);
                        wp.setColor(cUn);                              // not yet sung
                    } else if (p >= 1f) {
                        wp.setShader(null);
                        wp.setColor(cSung);
                    } else {
                        float edge = Math.min(1f, p + 0.20f);          // Spicy: soft edge 20% of the word
                        // a shader's output is multiplied by the paint's own alpha: reset it, or the first word
                        // of a line inherits the previous word's (dim) colour and renders ghostly
                        wp.setColor(WHITE);
                        wp.setShader(new LinearGradient(0, 0, w.width, 0,
                                new int[]{cSung, cSung, cUn, cUn},
                                new float[]{0f, p, edge, 1f}, Shader.TileMode.CLAMP));
                    }
                    // Spicy: text-shadow blur 4 + 2*glow px, opacity min(glow * 35%, 100%)
                    float glow = Math.max(0f, Math.min(1f, w.gl.x)) * vis;
                    if (glow > 0.02f) wp.setShadowLayer(dp(4) + dp(2) * glow, 0f, 0f, ((int) (glow * 0.35f * 255f) << 24) | 0xFFFFFF);
                    else wp.clearShadowLayer();
                    cv.drawText(b.text, w.s, w.e, 0f, 0f, wp);
                    cv.restore();
                }
            }
            wp.clearShadowLayer();
            postInvalidateOnAnimation();
        }
    }

    // ================================================================= instrumental gap

    // Spicy Lyrics' DotAnimations: each dot sits at scale .75 / opacity .35 and swells (1.05 at 70%), lifts
    // (-0.12em at 90%) and glows while its third of the break is sung; the group pops in and out.
    private static final float[] DOT_SC_T = {0f, 0.7f, 1f}, DOT_SC_V = {0.75f, 1.05f, 1f};
    private static final float[] DOT_YO_T = {0f, 0.9f, 1f}, DOT_YO_V = {0f, -0.12f, 0f};
    private static final float[] DOT_GL_T = {0f, 0.6f, 1f}, DOT_GL_V = {0f, 1f, 1f};
    private static final float[] DOT_OP_T = {0f, 0.6f, 1f}, DOT_OP_V = {0.35f, 1f, 1f};

    /**
     * Three dots for an instrumental break. The row is collapsed (height 0) until the break starts and
     * closes again afterwards, like Spicy's musical line.
     */
    private final class DotsView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Spring grp = new Spring();
        private final Spring[] sc = {new Spring(), new Spring(), new Spring()};
        private final Spring[] yo = {new Spring(), new Spring(), new Spring()};
        private final Spring[] gl = {new Spring(), new Spring(), new Spring()};
        private final Spring[] op = {new Spring(), new Spring(), new Spring()};
        private final java.util.HashMap<Integer, BlurMaskFilter> blurs = new java.util.HashMap<>();
        private boolean open;
        private ValueAnimator anim;
        private long lastNs;

        /** Soft edge: radius in quarter-dp steps (cached, the filters are immutable). */
        private BlurMaskFilter softness(float radiusDp) {
            int key = Math.max(1, Math.round(radiusDp * 4f));
            BlurMaskFilter f = blurs.get(key);
            if (f == null) {
                f = new BlurMaskFilter(key / 4f * getResources().getDisplayMetrics().density, BlurMaskFilter.Blur.NORMAL);
                blurs.put(key, f);
            }
            return f;
        }

        DotsView(Context c) {
            super(c);
            for (int i = 0; i < 3; i++) { sc[i].x = DOT_SC_V[0]; op[i].x = DOT_OP_V[0]; }
        }

        void update(long start, long end, long pos, boolean isActive) {
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
            lastNs = now;
            boolean on = isActive && pos >= start && end - pos > 150;     // pops out just before the end
            grp.step(on ? 1f : 0f, dt, 5f, 0.7f);
            float prog = end > start ? Math.max(0f, Math.min(1f, (pos - start) / (float) (end - start))) : 1f;
            for (int i = 0; i < 3; i++) {
                float pi = isActive ? Math.max(0f, Math.min(1f, prog * 3f - i)) : 0f;
                sc[i].step(keyframe(DOT_SC_T, DOT_SC_V, pi), dt, 0.7f, 0.6f);
                yo[i].step(keyframe(DOT_YO_T, DOT_YO_V, pi), dt, 1.25f, 0.4f);
                gl[i].step(keyframe(DOT_GL_T, DOT_GL_V, pi), dt, 1f, 0.5f);
                op[i].step(keyframe(DOT_OP_T, DOT_OP_V, pi), dt, 1f, 0.5f);
            }
            if (getHeight() > 0) invalidate();
        }

        void setOpen(boolean want, boolean instant) {
            if (want == open) return;
            open = want;
            if (anim != null) anim.cancel();
            final float to = want ? 1f : 0f;
            final float from = getLayoutParams() == null ? 0f : getLayoutParams().height / (float) dp(DOTS_H);
            if (instant) { apply(to); return; }
            anim = ValueAnimator.ofFloat(from, to);
            anim.setDuration(want ? 380 : 320);
            anim.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
            anim.start();
        }

        private void apply(float f) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) getLayoutParams();
            if (lp == null) return;
            lp.height = Math.round(dp(DOTS_H) * Math.max(0f, Math.min(1f, f)));
            setAlpha(Math.max(0f, Math.min(1f, f)));
            setLayoutParams(lp);
        }

        @Override protected void onDraw(Canvas cv) {
            if (getHeight() <= 0 || grp.x < 0.01f) return;
            float d = getResources().getDisplayMetrics().density;
            float r = 9.5f * d, pitch = 2f * r + 9f * d, em = FONT_DP * 1.3f * d;
            float cy = getHeight() / 2f;
            float x0 = 22f * d + r;
            float gs = Math.max(0f, grp.x);
            cv.save();
            cv.scale(gs, gs, x0 - r, cy);                          // the group pops from its left edge
            for (int i = 0; i < 3; i++) {
                float s = sc[i].x, g = Math.max(0f, Math.min(1f, gl[i].x));
                float o = Math.max(0f, Math.min(1f, op[i].x)) * Math.min(1f, gs);
                float cx = x0 + i * pitch, y = cy + yo[i].x * em;
                if (g > 0.03f) {                                   // Spicy: text-shadow 4 + 6g px at up to 90%
                    p.setMaskFilter(softness(5f));                 // one soft halo, no hard ring edges
                    p.setColor(((int) (g * o * 0.42f * 255f) << 24) | 0xFFFFFF);
                    cv.drawCircle(cx, y, r * s + (3f + 4f * g) * d, p);
                    p.setMaskFilter(null);
                }
                // blurred like the lyrics around it: dots that are not sung yet are the softest
                float po = Math.max(0f, Math.min(1f, (op[i].x - DOT_OP_V[0]) / (1f - DOT_OP_V[0])));
                float soft = 3.6f * (1f - po);                     // a dot that has been filled in is sharp
                p.setMaskFilter(soft > 0.25f ? softness(soft) : null);
                p.setColor(((int) (o * 255f) << 24) | 0xFFFFFF);
                cv.drawCircle(cx, y, r * s, p);
                p.setMaskFilter(null);
            }
            cv.restore();
        }
    }
}

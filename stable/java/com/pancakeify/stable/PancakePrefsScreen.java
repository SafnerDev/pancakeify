package com.pancakeify.stable;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Pancakeify mod-settings page. Built entirely in code and shown as a full-screen Dialog
 * (no manifest &lt;activity&gt; and no layout resources needed in the patched APK) that
 * cross-fades over Spotify's Settings page, styled like Spotify's own Settings sub-pages
 * (same top bar, Spotify Mix font, pill toggles).
 *
 * Sections: Appearance (Main Color + Enable SpicyLyrics) and About (version + Discord).
 */
public final class PancakePrefsScreen {
    private PancakePrefsScreen() {}

    public static final String VERSION = "1.0.0";
    private static final String DISCORD_URL = "https://discord.gg/KQk4bFNHPP";

    private static final int BG = 0xFF121212;
    private static final int BAR = 0xFF1F1F1F;
    private static final int SHEET = 0xFF282828;
    private static final int FG = 0xFFFFFFFF;
    private static final int SUB = 0xFFB3B3B3;
    private static final int TRACK_OFF = 0xFF2A2A2A;

    /** Spotify's default green. */
    public static final int DEFAULT_COLOR = 0xFF1ED760;
    private static final String PREFS = "pancakeify";
    private static final String KEY_COLOR = "main_color";
    static final String KEY_LYRICS = "spicy_lyrics";

    private static final int[] PRESETS = {
            DEFAULT_COLOR, 0xFFE22134, 0xFFE91E8C, 0xFF8D67AB, 0xFF5038A0,
            0xFF2D46B9, 0xFF1E90FF, 0xFF00BFA5, 0xFFF59B23, 0xFFE8B04B,
    };

    private static volatile boolean showing = false;
    private static long shownAt = 0;

    /** Ignore taps in the first moments after the page appears (the tap that opened it). */
    private static boolean inputReady() {
        return android.os.SystemClock.uptimeMillis() - shownAt > 400;
    }
    private static int accent = DEFAULT_COLOR;
    private static int launchColor = DEFAULT_COLOR;
    /** Views that draw the accent colour; invalidated when it changes. */
    private static final List<View> accentViews = new ArrayList<>();

    /** The user's chosen Main Color (for the theme engine / other mod code). */
    public static int mainColor(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COLOR, DEFAULT_COLOR);
    }

    public static void show(Context ctx) {
        try {
            Activity act = activityOf(ctx);
            if (act == null || act.isFinishing()) return;
            act.runOnUiThread(() -> { if (!showing) build(act); });
        } catch (Throwable t) {
            android.util.Log.e(PancakeBootstrap.TAG, "PancakePrefs show failed", t);
        }
    }

    // ------------------------------------------------------------------------------ page

    private static void build(Activity act) {
        showing = true;
        shownAt = android.os.SystemClock.uptimeMillis();
        accent = mainColor(act);
        launchColor = MainColorMod.appliedColor();
        accentViews.clear();
        final SharedPreferences sp = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        final Dialog dlg = new Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar) {
            @Override public void onBackPressed() { dismissAnimated(this); }
        };
        dlg.setOnDismissListener(d -> { showing = false; accentViews.clear(); });

        final FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(BG);

        LinearLayout page = new LinearLayout(act);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // --- top bar (same metrics as Spotify's Settings bar) ---
        final FrameLayout bar = new FrameLayout(act);
        bar.setBackgroundColor(BAR);
        final int barH = dp(act, 56);
        page.addView(bar, new LinearLayout.LayoutParams(-1, barH));

        View back = new BackArrow(act);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(act, 56), barH);
        blp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        back.setOnClickListener(v -> dismissAnimated(dlg));
        bar.addView(back, blp);

        TextView title = text(act, "Pancakeify Preferences", 18, FG, true);
        title.setSingleLine(true);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
        tlp.gravity = Gravity.CENTER;
        bar.addView(title, tlp);

        // --- body ---
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(act, 8), 0, dp(act, 32));

        // --- hero: the Pancakeify logo
        LinearLayout hero = new LinearLayout(act);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(act, 16), dp(act, 20), dp(act, 16), dp(act, 12));
        IconView logo = new IconView(act, IconView.PANCAKE).glyph(0.92f);
        logo.setClickable(false);
        hero.addView(logo, new LinearLayout.LayoutParams(dp(act, 88), dp(act, 88)));
        TextView heroName = text(act, "Pancakeify", 24, FG, true);
        heroName.setPadding(0, dp(act, 8), 0, 0);
        heroName.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.addView(heroName);
        TextView heroSub = text(act, "Spicetify for Android Spotify", 14, SUB, false);
        heroSub.setPadding(0, dp(act, 4), 0, 0);
        heroSub.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.addView(heroSub);
        list.addView(hero);

        list.addView(header(act, "Appearance"));

        final Swatch swatch = new Swatch(act);
        accentViews.add(swatch);
        View.OnClickListener openPicker = v -> openPicker(act, root, sp);
        swatch.setOnClickListener(openPicker);
        list.addView(row(act, "Main Color", null, swatch, openPicker));

        final Toggle lyrics = new Toggle(act, sp.getBoolean(KEY_LYRICS, false));
        lyrics.onChange = on -> sp.edit().putBoolean(KEY_LYRICS, on).apply();
        accentViews.add(lyrics);
        list.addView(row(act, "Enable SpicyLyrics", null, lyrics, v -> lyrics.toggle()));

        list.addView(header(act, "About"));
        IconView aboutLogo = new IconView(act, IconView.PANCAKE).glyph(0.9f);
        aboutLogo.setClickable(false);
        aboutLogo.setMinimumWidth(dp(act, 36));
        aboutLogo.setMinimumHeight(dp(act, 36));
        list.addView(row(act, "Pancakeify", "Version " + VERSION, aboutLogo, null));
        list.addView(row(act, "Credits", "Lyrics player and lyrics data from Spicy Lyrics by Spikerko \u00b7 background based on Kawarp (MIT)", null, null));
        list.addView(actionButtons(act, root, sp, () -> lyrics.setChecked(false)));

        ScrollView scroll = new ScrollView(act);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets b = ins.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            bar.setPadding(0, b.top, 0, 0);
            bar.getLayoutParams().height = barH + b.top;
            list.setPadding(0, dp(act, 8), 0, dp(act, 32) + b.bottom);
            root.setTag(b.bottom);
            bar.requestLayout();
            return ins;
        });

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            // transparent window: the Settings page stays visible underneath while we fade in
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.setWindowAnimations(0);
            w.setDecorFitsSystemWindows(false);
        }
        root.setAlpha(0f);
        dlg.show();
        root.animate().alpha(1f).setDuration(220).start();
    }

    private static void dismissAnimated(Dialog dlg) {
        try {
            View root = dlg.findViewById(android.R.id.content);
            if (root == null) { dlg.dismiss(); return; }
            root.animate().alpha(0f).setDuration(180).withEndAction(dlg::dismiss).start();
        } catch (Throwable t) { dlg.dismiss(); }
    }

    // ------------------------------------------------------------------------------ rows

    private static View header(Context c, String s) {
        TextView t = text(c, s, 20, FG, true);
        t.setPadding(dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 8));
        return t;
    }

    /** A Spotify-style settings row: title (+ optional subtitle) with an optional trailing view. */
    private static View row(Context c, String title, String sub, View trailing, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        row.setMinimumHeight(dp(c, 64));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(c, title, 16, FG, false));
        if (sub != null) {
            TextView s = text(c, sub, 14, SUB, false);
            s.setPadding(0, dp(c, 4), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (trailing != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(c, 16);
            row.addView(trailing, lp);
        }
        if (click != null) row.setOnClickListener(click);
        return row;
    }

    private static final int DISCORD_BLUE = 0xFF5865F2;
    private static final int RESET_RED = 0xFFE91429;

    /** Rounded pill button with centred bold label. */
    private static TextView pillButton(Context c, String label, int fill, int fg) {
        TextView b = text(c, label, 16, fg, true);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(c, 24));
        b.setBackground(bg);
        b.setPadding(dp(c, 24), 0, dp(c, 24), 0);
        return b;
    }

    /** "Discord" (blurple) and "Reset all Settings" (red) side by side. */
    private static View actionButtons(Activity act, FrameLayout root, SharedPreferences sp,
                                      Runnable refreshToggles) {
        LinearLayout line = new LinearLayout(act);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), 0);

        TextView discord = pillButton(act, "Discord", DISCORD_BLUE, FG);
        discord.setOnClickListener(v -> {
            if (!inputReady()) return;
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_URL));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                v.getContext().startActivity(i);
            } catch (Throwable t) {
                android.util.Log.w(PancakeBootstrap.TAG, "open discord failed: " + t);
            }
        });
        line.addView(discord, new LinearLayout.LayoutParams(-2, dp(act, 48)));

        TextView reset = pillButton(act, "Reset all Settings", RESET_RED, FG);
        reset.setOnClickListener(v -> {
            if (!inputReady()) return;
            showConfirm(act, root, "confirm", "Reset all settings?",
                    "Main Color and SpicyLyrics go back to their defaults.",
                    "Cancel", "Reset", RESET_RED, FG, () -> {
                        sp.edit().clear().apply();
                        accent = DEFAULT_COLOR;
                        refreshToggles.run();
                        for (View av : accentViews) av.invalidate();
                        if ((launchColor & 0xFFFFFF) != (DEFAULT_COLOR & 0xFFFFFF)) showRestartPrompt(act, root);
                    });
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, dp(act, 48));
        rl.leftMargin = dp(act, 12);
        line.addView(reset, rl);
        return line;
    }

    // ----------------------------------------------------------------------- colour picker

    private static void applyColor(SharedPreferences sp, int color) {
        accent = color | 0xFF000000;
        if (accent == DEFAULT_COLOR) sp.edit().remove(KEY_COLOR).apply();
        else sp.edit().putInt(KEY_COLOR, accent).apply();
        for (View v : accentViews) v.invalidate();
    }

    /** Bottom sheet with an HSV picker, ready-made colours and Reset. Lives inside our page. */
    private static void openPicker(Activity act, FrameLayout root, SharedPreferences sp) {
        if (!inputReady()) return;
        if (root.findViewWithTag("picker") != null) return;
        final FrameLayout layer = new FrameLayout(act);
        layer.setTag("picker");
        layer.setBackgroundColor(0x99000000);
        layer.setClickable(true);

        final LinearLayout sheet = new LinearLayout(act);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(SHEET);
        float r = dp(act, 16);
        sbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sbg);
        sheet.setClickable(true);                      // swallow touches
        int pad = dp(act, 20);
        int navBottom = root.getTag() instanceof Integer ? (Integer) root.getTag() : 0;
        sheet.setPadding(pad, pad, pad, pad + navBottom);

        // title + hex
        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(act, "Main Color", 20, FG, true), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView hex = text(act, hexOf(accent), 14, SUB, false);
        head.addView(hex);
        sheet.addView(head);

        final float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);

        final SvView sv = new SvView(act, hsv);
        final HueView hue = new HueView(act, hsv);
        final List<PresetDot> dots = new ArrayList<>();

        final Runnable changed = () -> {
            int c = Color.HSVToColor(hsv);
            applyColor(sp, c);
            hex.setText(hexOf(c));
            sv.invalidate(); hue.invalidate();
            for (PresetDot d : dots) d.invalidate();
        };
        sv.onChange = changed;
        hue.onChange = changed;

        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(-1, dp(act, 170));
        svp.topMargin = dp(act, 4);
        svp.leftMargin = -dp(act, 12);
        svp.rightMargin = -dp(act, 12);
        sheet.addView(sv, svp);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(act, 28));
        hp.topMargin = dp(act, 14);
        sheet.addView(hue, hp);

        TextView ready = text(act, "Ready-made", 14, SUB, false);
        ready.setPadding(0, dp(act, 18), 0, dp(act, 8));
        sheet.addView(ready);

        for (int rowI = 0; rowI < 2; rowI++) {
            LinearLayout line = new LinearLayout(act);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 5; i++) {
                final int color = PRESETS[rowI * 5 + i];
                PresetDot dot = new PresetDot(act, color);
                dots.add(dot);
                dot.setOnClickListener(v -> {
                    Color.colorToHSV(color, hsv);
                    changed.run();
                });
                line.addView(dot, new LinearLayout.LayoutParams(0, dp(act, 48), 1f));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(act, 4);
            sheet.addView(line, lp);
        }

        // buttons
        LinearLayout btns = new LinearLayout(act);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        TextView reset = pill(act, "Reset", FG, 0x00000000, true);
        reset.setOnClickListener(v -> {
            Color.colorToHSV(DEFAULT_COLOR, hsv);
            changed.run();
        });
        TextView done = pill(act, "Done", 0xFF000000, FG, false);
        done.setOnClickListener(v -> closePicker(act, layer, sheet, root));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(act, 48), 1f);
        bl.rightMargin = dp(act, 8);
        btns.addView(reset, bl);
        LinearLayout.LayoutParams br = new LinearLayout.LayoutParams(0, dp(act, 48), 1f);
        br.leftMargin = dp(act, 8);
        btns.addView(done, br);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(act, 14);
        sheet.addView(btns, bp);

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2);
        slp.gravity = Gravity.BOTTOM;
        layer.addView(sheet, slp);
        layer.setOnClickListener(v -> closePicker(act, layer, sheet, root));
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));

        layer.setAlpha(0f);
        layer.animate().alpha(1f).setDuration(160).start();
        sheet.setTranslationY(dp(act, 500));
        sheet.animate().translationY(0).setDuration(220).start();
    }

    private static void closePicker(Activity act, View layer, View sheet, FrameLayout root) {
        sheet.animate().translationY(sheet.getHeight()).setDuration(180).start();
        layer.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            root.removeView(layer);
            if ((accent & 0xFFFFFF) != (launchColor & 0xFFFFFF)) showRestartPrompt(act, root);
        }).start();
    }

    /** The colour is applied at process start, so offer to close Spotify. */
    private static void showRestartPrompt(Activity act, FrameLayout root) {
        showConfirm(act, root, "restart", "Reopen to apply",
                "Spotify has to be closed and opened again to apply the new Main Color.",
                "Later", "Close Spotify", FG, 0xFF000000, () -> restartApp(act));
    }

    /** Bottom sheet with a title, a message and two pill buttons. */
    private static void showConfirm(Activity act, FrameLayout root, String tag, String title, String message,
                                    String leftLabel, String rightLabel, int rightFill, int rightFg,
                                    Runnable onRight) {
        if (root.findViewWithTag(tag) != null) return;
        final FrameLayout layer = new FrameLayout(act);
        layer.setTag(tag);
        layer.setBackgroundColor(0x99000000);
        layer.setClickable(true);

        LinearLayout sheet = new LinearLayout(act);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(SHEET);
        float r = dp(act, 16);
        sbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sbg);
        sheet.setClickable(true);
        int pad = dp(act, 20);
        int navBottom = root.getTag() instanceof Integer ? (Integer) root.getTag() : 0;
        sheet.setPadding(pad, pad, pad, pad + navBottom);

        sheet.addView(text(act, title, 20, FG, true));
        TextView msg = text(act, message, 14, SUB, false);
        msg.setPadding(0, dp(act, 8), 0, dp(act, 16));
        sheet.addView(msg);

        final Runnable dismiss = () -> layer.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> root.removeView(layer)).start();
        LinearLayout btns = new LinearLayout(act);
        TextView left = pill(act, leftLabel, FG, 0x00000000, true);
        left.setOnClickListener(v -> dismiss.run());
        TextView right = pill(act, rightLabel, rightFg, rightFill, false);
        right.setOnClickListener(v -> { dismiss.run(); onRight.run(); });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(act, 48), 1f);
        l1.rightMargin = dp(act, 8);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(act, 48), 1f);
        l2.leftMargin = dp(act, 8);
        btns.addView(left, l1);
        btns.addView(right, l2);
        sheet.addView(btns);

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2);
        slp.gravity = Gravity.BOTTOM;
        layer.addView(sheet, slp);
        layer.setOnClickListener(v -> dismiss.run());
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        layer.setAlpha(0f);
        layer.animate().alpha(1f).setDuration(160).start();
        sheet.setTranslationY(dp(act, 300));
        sheet.animate().translationY(0).setDuration(220).start();
    }

    /**
     * Close the app so the new colour is picked up on the next launch. (Relaunching ourselves is
     * not possible: Android blocks background activity starts, even from a PendingIntent.)
     */
    private static void restartApp(Activity act) {
        act.finishAndRemoveTask();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static TextView pill(Context c, String s, int fg, int fill, boolean outlined) {
        TextView t = text(c, s, 16, fg, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 24));
        if (outlined) g.setStroke(dp(c, 1), 0xFF7C7C7C);
        t.setBackground(g);
        return t;
    }

    private static String hexOf(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    /** Saturation/value square for the current hue. */
    private static final class SvView extends View {
        final float[] hsv;
        Runnable onChange;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final Path clip = new Path();

        SvView(Context c, float[] hsv) { super(c); this.hsv = hsv; }

        /** Gradient area, inset so the knob is never clipped at the edges. */
        private void area(RectF out) {
            float in = dp(getContext(), 12);
            out.set(in, in, getWidth() - in, getHeight() - in);
        }

        @Override protected void onDraw(Canvas cv) {
            area(rf);
            float rad = dp(getContext(), 12);
            clip.reset();
            clip.addRoundRect(rf, rad, rad, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            int pure = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(rf.left, 0, rf.right, 0, Color.WHITE, pure, Shader.TileMode.CLAMP));
            cv.drawRect(rf, p);
            p.setShader(new LinearGradient(0, rf.top, 0, rf.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
            cv.drawRect(rf, p);
            p.setShader(null);
            cv.restore();
            float cx = rf.left + hsv[1] * rf.width(), cy = rf.top + (1f - hsv[2]) * rf.height();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(getContext(), 3));
            p.setColor(Color.WHITE);
            cv.drawCircle(cx, cy, dp(getContext(), 10), p);
            p.setStrokeWidth(dp(getContext(), 1));
            p.setColor(0x66000000);
            cv.drawCircle(cx, cy, dp(getContext(), 12), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            area(rf);
            hsv[1] = clamp((e.getX() - rf.left) / rf.width());
            hsv[2] = 1f - clamp((e.getY() - rf.top) / rf.height());
            if (onChange != null) onChange.run();
            return true;
        }
    }

    /** Rainbow hue slider. */
    private static final class HueView extends View {
        final float[] hsv;
        Runnable onChange;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();

        HueView(Context c, float[] hsv) { super(c); this.hsv = hsv; }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            int[] cols = new int[7];
            for (int i = 0; i < 7; i++) cols[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 1f, 1f});
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, w, 0, cols, null, Shader.TileMode.CLAMP));
            rf.set(0, h * 0.25f, w, h * 0.75f);
            cv.drawRoundRect(rf, h / 4, h / 4, p);
            p.setShader(null);
            float cx = Math.max(h / 2, Math.min(w - h / 2, hsv[0] / 360f * w));
            p.setColor(Color.WHITE);
            cv.drawCircle(cx, h / 2, h / 2 - dp(getContext(), 1), p);
            p.setColor(Color.HSVToColor(new float[]{hsv[0], 1f, 1f}));
            cv.drawCircle(cx, h / 2, h / 2 - dp(getContext(), 5), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            hsv[0] = Math.min(359.9f, clamp(e.getX() / getWidth()) * 360f);
            if (onChange != null) onChange.run();
            return true;
        }
    }

    /** A ready-made colour circle; ringed when it is the current main colour. */
    private static final class PresetDot extends View {
        final int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        PresetDot(Context c, int color) { super(c); this.color = color; }
        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(getContext(), 17);
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(cx, cy, r, p);
            if ((accent & 0xFFFFFF) == (color & 0xFFFFFF)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(getContext(), 2));
                p.setColor(Color.WHITE);
                cv.drawCircle(cx, cy, r + dp(getContext(), 4), p);
            }
        }
    }

    private static float clamp(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    // ------------------------------------------------------------------------- small views

    /** Rounded square showing the current Main Color (trailing view of the Main Color row). */
    private static final class Swatch extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        Swatch(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 40), dp(getContext(), 40));
        }
        @Override protected void onDraw(Canvas cv) {
            float rad = dp(getContext(), 10);
            r.set(0, 0, getWidth(), getHeight());
            p.setStyle(Paint.Style.FILL);
            p.setColor(accent);
            cv.drawRoundRect(r, rad, rad, p);
            float s = dp(getContext(), 1);
            r.inset(s / 2, s / 2);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s);
            p.setColor(0x33FFFFFF);
            cv.drawRoundRect(r, rad, rad, p);
        }
    }

    /**
     * Spotify's toggle: filled with the Main Color and a dark knob when on; dark fill, grey
     * outline and a small grey knob when off.
     */
    static final class Toggle extends View {
        interface Listener { void onChange(boolean on); }
        Listener onChange;
        private boolean on;
        private float pos;                       // 0 = off, 1 = on (animated)
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on; this.pos = on ? 1f : 0f;
            setOnClickListener(v -> toggle());
        }
        /** Programmatic change (no listener callback, no input guard). */
        void setChecked(boolean v) {
            if (on == v) return;
            on = v;
            ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
            a.setDuration(150);
            a.addUpdateListener(u -> { pos = (Float) u.getAnimatedValue(); invalidate(); });
            a.start();
        }
        void toggle() {
            if (!inputReady()) return;
            on = !on;
            ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
            a.setDuration(150);
            a.addUpdateListener(v -> { pos = (Float) v.getAnimatedValue(); invalidate(); });
            a.start();
            if (onChange != null) onChange.onChange(on);
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 52), dp(getContext(), 32));
        }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), rad = h / 2;
            float stroke = dp(c, 2);
            // track: grey outline fades out while the Main Color fills in
            p.setStyle(Paint.Style.FILL);
            p.setColor(blend(TRACK_OFF, accent, pos));
            r.set(0, 0, w, h);
            cv.drawRoundRect(r, rad, rad, p);
            if (pos < 1f) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(stroke);
                p.setColor((((int) (0xFF * (1f - pos))) << 24) | 0xB3B3B3);
                r.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
                cv.drawRoundRect(r, rad, rad, p);
            }
            // knob: small grey (left) -> large dark (right)
            p.setStyle(Paint.Style.FILL);
            p.setColor(blend(0xFFB3B3B3, BG, pos));
            float kr = dp(c, 8) + (dp(c, 12) - dp(c, 8)) * pos;
            float cx = dp(c, 16) + (w - 2 * dp(c, 16)) * pos;
            cv.drawCircle(cx, h / 2, kr, p);
        }
    }

    /** Back arrow drawn like Spotify's toolbar arrow (no drawable resources needed). */
    private static final class BackArrow extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BackArrow(Context c) {
            super(c);
            p.setColor(FG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(c, 2));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setContentDescription("Back");
        }
        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(getContext(), 9);
            cv.drawLine(cx - r, cy, cx + r, cy, p);
            Path head = new Path();
            head.moveTo(cx - r + r * 0.75f, cy - r * 0.75f);
            head.lineTo(cx - r, cy);
            head.lineTo(cx - r + r * 0.75f, cy + r * 0.75f);
            cv.drawPath(head, p);
        }
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // ----------------------------------------------------------------------------- helpers

    private static Typeface regularFace, boldFace;

    /** Spotify's own UI font (res/font/spotify_mix_ui_*), falling back to the system font. */
    static Typeface face(Context c, boolean bold) {
        if (regularFace == null) {
            regularFace = loadFont(c, "spotify_mix_ui_regular", Typeface.DEFAULT);
            boldFace = loadFont(c, "spotify_mix_ui_bold", Typeface.DEFAULT_BOLD);
        }
        return bold ? boldFace : regularFace;
    }

    private static Typeface loadFont(Context c, String name, Typeface fallback) {
        try {
            int id = c.getResources().getIdentifier(name, "font", c.getPackageName());
            if (id != 0) {
                Typeface t = c.getResources().getFont(id);
                if (t != null) return t;
            }
        } catch (Throwable t) {
            android.util.Log.w(PancakeBootstrap.TAG, "font " + name + " failed: " + t);
        }
        return fallback;
    }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face(c, bold));
        return t;
    }

    static int dp(Context c, int dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    private static Activity activityOf(Context ctx) {
        Context c = ctx;
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}

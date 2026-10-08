package com.pancakeify.stable;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import top.canyie.pine.Pine;

/**
 * Recolours Spotify's brand green ("Main Color") app-wide.
 *
 * Spotify's UI takes the green from two places and we intercept both:
 *  - Jetpack Compose: every colour constant goes through Compose's {@code Color(long)}
 *    (obfuscated: {@code p.iae1.g(J)J}), called with the ARGB value in the low 32 bits.
 *    ~1300 call sites pass colours from the green family (#1ED760 and its dark/light shades),
 *    so one hook on that function recolours all of them.
 *  - Android resources (Encore colour tokens such as dark_base_essential_brightaccent, used by
 *    View-based screens): hooks on Resources.getColor/getColorStateList and TypedArray.
 *
 * Any colour in Spotify's green family (hue ~141 deg) is moved to the chosen hue, keeping its
 * relative saturation/brightness, so pressed/dark/tint shades stay consistent.
 *
 * Compose design tokens are cached in static fields, so the colour is applied at process start:
 * hooks are only installed when a non-default colour is saved, and changing it needs a restart.
 */
public final class MainColorMod {
    private MainColorMod() {}

    static final String PREFS = "pancakeify";
    static final String KEY_COLOR = "main_color";
    static final int DEFAULT_COLOR = 0xFF1ED760;

    // Spotify green in HSV (H deg, S, V) -- reference for relative scaling.
    private static final float REF_H = 141f, REF_S = 0.86f, REF_V = 0.84f;
    private static final float FAMILY_H_MIN = 138f, FAMILY_H_MAX = 144f;

    private static volatile boolean active = false;
    private static volatile int applied = DEFAULT_COLOR;
    private static float tH, tS, tV;

    /** The colour this process started with (DEFAULT_COLOR when the mod is off). */
    public static int appliedColor() { return applied; }

    public static void install() {
        try {
            Context c = PancakeBootstrap.appContext;
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!sp.contains(KEY_COLOR)) return;              // default green: nothing to do
            int color = sp.getInt(KEY_COLOR, DEFAULT_COLOR) | 0xFF000000;
            if (color == DEFAULT_COLOR) return;

            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            tH = hsv[0]; tS = hsv[1]; tV = hsv[2];
            applied = color;
            active = true;

            hookCompose(c.getClassLoader());
            hookResources();
            Log.i(PancakeBootstrap.TAG, "MainColor active: #" + Integer.toHexString(color & 0xFFFFFF));
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "MainColorMod install failed", t);
        }
    }

    // ------------------------------------------------------------------ colour mapping

    /** Maps an ARGB int; returns it unchanged when it is not in Spotify's green family. */
    static int map(int argb) {
        if (!active) return argb;
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        if (g < r || g < b) return argb;                      // cheap reject: not green-dominant
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        if (hsv[0] < FAMILY_H_MIN || hsv[0] > FAMILY_H_MAX || hsv[1] < 0.3f || hsv[2] < 0.15f)
            return argb;
        hsv[0] = (tH + (hsv[0] - REF_H) + 360f) % 360f;
        hsv[1] = Math.min(1f, hsv[1] * (tS / REF_S));
        hsv[2] = Math.min(1f, hsv[2] * (tV / REF_V));
        return Color.HSVToColor(argb >>> 24, hsv);
    }

    private static ColorStateList mapStateList(ColorStateList csl) {
        if (csl == null || !active) return csl;
        try {
            Field fc = ColorStateList.class.getDeclaredField("mColors");
            Field fs = ColorStateList.class.getDeclaredField("mStateSpecs");
            fc.setAccessible(true); fs.setAccessible(true);
            int[] colors = (int[]) fc.get(csl);
            int[][] states = (int[][]) fs.get(csl);
            if (colors == null || states == null) return csl;
            int[] out = null;
            for (int i = 0; i < colors.length; i++) {
                int m = map(colors[i]);
                if (m != colors[i]) {
                    if (out == null) out = colors.clone();
                    out[i] = m;
                }
            }
            return out == null ? csl : new ColorStateList(states, out);
        } catch (Throwable t) {
            return csl;
        }
    }

    // -------------------------------------------------------------------------- hooks

    /** Compose Color(long): the ARGB value lives in the low 32 bits of the argument. */
    private static void hookCompose(ClassLoader cl) throws Exception {
        Anchors an = Anchors.detect(PancakeBootstrap.appContext);
        if (an == null) { Log.w(PancakeBootstrap.TAG, "MainColor: no anchors for this Spotify version"); return; }
        Class<?> colorKt = Class.forName(an.colorClass, false, cl);
        for (Method m : colorKt.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals(an.colorMethod) && Modifier.isStatic(m.getModifiers())
                    && p.length == 1 && p[0] == long.class && m.getReturnType() == long.class) {
                m.setAccessible(true);
                HookEngine.hook(m, new HookEngine.Callback() {
                    @Override public void before(Pine.CallFrame frame) {
                        long v = (Long) frame.args[0];
                        if ((v >>> 32) != 0) return;
                        int mapped = map((int) v);
                        if (mapped != (int) v) frame.args[0] = (v & ~0xFFFFFFFFL) | (mapped & 0xFFFFFFFFL);
                    }
                });
                Log.i(PancakeBootstrap.TAG, "MainColor: hooked Compose Color(long) " + m);
                return;
            }
        }
        Log.w(PancakeBootstrap.TAG, "MainColor: Compose Color(long) not found");
    }

    private static void hookResources() {
        hookAfter(android.content.res.Resources.class, "getColor",
                new Class<?>[]{int.class, android.content.res.Resources.Theme.class}, true);
        hookAfter(android.content.res.Resources.class, "getColorStateList",
                new Class<?>[]{int.class, android.content.res.Resources.Theme.class}, false);
        hookAfter(android.content.res.TypedArray.class, "getColor",
                new Class<?>[]{int.class, int.class}, true);
        hookAfter(android.content.res.TypedArray.class, "getColorStateList",
                new Class<?>[]{int.class}, false);
        // VectorDrawable / shape XML read their fill colours via the hidden getComplexColor
        hookAfter(android.content.res.TypedArray.class, "getComplexColor",
                new Class<?>[]{int.class}, false);
        hookAfter(Color.class, "parseColor", new Class<?>[]{String.class}, true);
        // Lottie animations (download/heart/play-indicator icons) build their colours with Color.argb/rgb
        hookAfter(Color.class, "argb", new Class<?>[]{int.class, int.class, int.class, int.class}, true);
        hookAfter(Color.class, "rgb", new Class<?>[]{int.class, int.class, int.class}, true);
    }

    private static void hookAfter(Class<?> cls, String name, Class<?>[] params, final boolean isInt) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            HookEngine.hook(m, new HookEngine.Callback() {
                @Override public void after(Pine.CallFrame frame) {
                    Object r = frame.getResult();
                    if (isInt) {
                        int c = (Integer) r, mc = map(c);
                        if (mc != c) {
                            frame.setResult(mc);
                        }
                    } else if (r instanceof ColorStateList) {
                        ColorStateList mapped = mapStateList((ColorStateList) r);
                        if (mapped != r) {
                            frame.setResult(mapped);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(PancakeBootstrap.TAG, "MainColor: hook " + cls.getSimpleName() + "." + name + " failed: " + t);
        }
    }
}

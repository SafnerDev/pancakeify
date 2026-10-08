package com.pancakeify.stable;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

/**
 * Typeface for the lyrics. Spicy Lyrics uses Apple's San Francisco (proprietary, not bundled); the closest
 * open face is Inter (SIL OFL), shipped as assets/pancake/Inter.ttf and set to weight 700 like Spicy's CSS.
 * Falls back to the Spotify Mix bold face if the asset is missing.
 */
final class LyricsFont {
    private LyricsFont() {}

    private static final Typeface[] cache = new Typeface[8];

    /** Forget cached faces (after the font option changed). */
    static synchronized void reset() { java.util.Arrays.fill(cache, null); }

    /** The face for the chosen font option. {@code semibold}: weight 600 (background vocals), else 700. */
    static synchronized Typeface get(Context c, boolean semibold) {
        int font = LyricsSettings.font;
        int i = font * 2 + (semibold ? 1 : 0);
        if (cache[i] != null) return cache[i];
        int wght = semibold ? 600 : 700;
        Typeface t = null;
        try {
            switch (font) {
                case LyricsSettings.FONT_INTER:
                    t = new Typeface.Builder(c.getAssets(), "pancake/Inter.ttf")
                            .setFontVariationSettings("'wght' " + wght + ", 'opsz' 28").build();
                    break;
                case LyricsSettings.FONT_SYSTEM:
                    t = Typeface.create(Typeface.DEFAULT, wght, false);
                    break;
                case LyricsSettings.FONT_SERIF:
                    t = Typeface.create(Typeface.SERIF, wght, false);
                    break;
                default:
                    break;
            }
        } catch (Throwable e) {
            Log.w(PancakeBootstrap.TAG, "font " + font + " unavailable: " + e);
        }
        if (t == null) t = PancakePrefsScreen.face(c, true);      // Spotify Mix (also the fallback)
        cache[i] = t;
        return t;
    }
}

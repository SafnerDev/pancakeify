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

    private static final Typeface[] cache = new Typeface[2];

    /** {@code semibold}: weight 600 (background vocals), otherwise 700 like Spicy's lead lines. */
    static synchronized Typeface get(Context c, boolean semibold) {
        int i = semibold ? 1 : 0;
        if (cache[i] != null) return cache[i];
        try {
            cache[i] = new Typeface.Builder(c.getAssets(), "pancake/Inter.ttf")
                    .setFontVariationSettings("'wght' " + (semibold ? 600 : 700) + ", 'opsz' 28")
                    .build();
        } catch (Throwable t) {
            Log.w(PancakeBootstrap.TAG, "Inter font unavailable: " + t);
        }
        if (cache[i] == null) cache[i] = PancakePrefsScreen.face(c, true);
        return cache[i];
    }
}

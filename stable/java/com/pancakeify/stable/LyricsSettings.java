package com.pancakeify.stable;

import android.content.Context;
import android.content.SharedPreferences;

/** User-tunable lyrics options (the "Spicy Lyrics settings" sheet), persisted in the "pancakeify" prefs. */
final class LyricsSettings {
    private LyricsSettings() {}

    static final int FONT_INTER = 0, FONT_SPOTIFY = 1, FONT_SYSTEM = 2, FONT_SERIF = 3;
    static final String[] FONT_NAMES = {"Inter", "Spotify Mix", "System", "Serif"};
    static final float SIZE_MIN = 0.70f, SIZE_MAX = 1.45f, SIZE_STEP = 0.05f;

    static float size = 1f;             // multiplier of the default lyrics size
    static int font = FONT_INTER;
    static boolean blur = true;         // blur lines by distance from the active one
    static boolean letters = true;      // letter-by-letter animation on long held syllables
    static boolean focus = false;       // hide cover/title, lyrics only

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("pancakeify", Context.MODE_PRIVATE);
    }

    static void load(Context c) {
        SharedPreferences p = prefs(c);
        size = Math.max(SIZE_MIN, Math.min(SIZE_MAX, p.getFloat("lyrics_size", 1f)));
        font = Math.max(0, Math.min(FONT_NAMES.length - 1, p.getInt("lyrics_font", FONT_INTER)));
        blur = p.getBoolean("lyrics_blur", true);
        letters = p.getBoolean("lyrics_letters", true);
        focus = p.getBoolean("lyrics_focus", false);
    }

    static void save(Context c) {
        prefs(c).edit().putFloat("lyrics_size", size).putInt("lyrics_font", font)
                .putBoolean("lyrics_blur", blur).putBoolean("lyrics_letters", letters)
                .putBoolean("lyrics_focus", focus).apply();
    }

    static void reset(Context c) {
        size = 1f; font = FONT_INTER; blur = true; letters = true; focus = false;
        save(c);
    }
}

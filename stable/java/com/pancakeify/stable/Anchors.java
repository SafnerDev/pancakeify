package com.pancakeify.stable;

import android.content.Context;
import android.util.Log;

/**
 * Names of Spotify's OBFUSCATED classes the mod hooks, per Spotify version. R8 renames them in every release,
 * so each supported version has a row here; an unknown version simply gets no Settings row / Main Color (the
 * player, lyrics and everything built on Android APIs do not depend on this table).
 *
 * How to add a version: tools/find_anchors.py prints the candidates from the APK; see instruction.txt
 * ("UPDATING TO A NEW SPOTIFY VERSION").
 */
final class Anchors {
    /** Settings row builder: instance method {@code builderMethod(String id, int, <dest>)} returning the row. */
    final String builderClass, builderMethod;
    /** Title resolver: static {@code resolverMethod(<title model>, Context)} returning String. */
    final String resolverClass, resolverMethod;
    /** Row element model (has the link holder as field "b" and the static subtitle as field "f"). */
    final String modelClass;
    /** Link holder: constructor(String link), field "a" = link. */
    final String linkClass;
    /** Navigator implementors: {@code navMethod(String link, <ctx>, Bundle)}. */
    final String[] navClasses;
    final String navMethod;
    /** Compose Color(long): static (J)J. */
    final String colorClass, colorMethod;
    /**
     * How a built row reaches the page's list. Old layout: static {@code addMethod(<list>, row)} (null-safe add).
     * New layout: rows are collected in an array passed to static {@code addMethod(Object[])} (Arrays.asList).
     */
    final String addClass, addMethod;
    final boolean addArray;

    private Anchors(String bc, String bm, String rc, String rm, String model, String link, String[] navs, String nm,
                    String cc, String cm, String ac, String am, boolean arr) {
        builderClass = bc; builderMethod = bm; resolverClass = rc; resolverMethod = rm; modelClass = model;
        linkClass = link; navClasses = navs; navMethod = nm; colorClass = cc; colorMethod = cm;
        addClass = ac; addMethod = am; addArray = arr;
    }

    static Anchors detect(Context c) {
        String v = "?";
        try {
            v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {}
        Anchors a = forVersion(v);
        Log.i(PancakeBootstrap.TAG, "Spotify " + v + (a == null ? ": no anchors for this version (Settings row / Main Color off)"
                : ": anchors loaded"));
        return a;
    }

    static Anchors forVersion(String v) {
        if (v == null) return null;
        switch (v) {
            case "9.1.80.2221":
                return new Anchors("p.ion", "q", "p.e9e1", "s", "p.osa0", "p.hka1",
                        new String[]{"p.s4h0", "p.qa20", "p.w621"}, "b", "p.iae1", "g", "p.ion", "h", false);
            case "9.1.90.2270":
                return new Anchors("p.fcp", "q", "p.vgk1", "n", "p.wxd0", "p.e4g1",
                        new String[]{"p.l671", "p.xw40", "p.mmk0"}, "a", "p.k5k1", "e", "p.ay5", "m0", true);
            default:
                return null;
        }
    }
}

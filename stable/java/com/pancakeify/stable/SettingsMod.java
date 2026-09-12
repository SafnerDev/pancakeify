package com.pancakeify.stable;

import android.content.res.Resources;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import top.canyie.pine.Pine;

/**
 * Adds a "Pancakeify Preferences" row at the TOP of Spotify's Settings screen (above
 * "Account").
 *
 * Render path (9.1.80.2221): p.xlt.create() appends each row via static
 * p.ion.h(collector, gtl). Each row is a `p.gtl` whose FIRST String field is a stable id
 * ("accountPage", "playbackPage", …) and whose title lives in a `p.ftl` (Integer titleResId +
 * optional String). We hook ion.h; when the "accountPage" row is about to be added we build
 * our own row (a gtl cloned from it, with a fresh ftl whose title resolves — via a getText
 * sentinel — to our text) and add it first, so it lands immediately above Account.
 *
 * Anchors are the stable id string "accountPage" (not obfuscated class names). v1 click reuses
 * the Account row's action (placeholder → opens Account).
 */
public final class SettingsMod {
    static final String TITLE_TEXT = "Pancakeify Preferences";
    static final int TITLE_SENTINEL = 0x7effff02;
    static final String ACCOUNT_ID = "accountPage";
    static final String OUR_ID = "pancakeifyPrefs";

    private static Method addMethod;          // p.ion.h(collector, gtl)
    private static boolean reentrant = false;

    private SettingsMod() {}

    public static void install() {
        try {
            Class<?> ion = Class.forName("p.ion", false, PancakeBootstrap.appContext.getClassLoader());
            for (Method m : ion.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("h")
                        && m.getParameterTypes().length == 2) { addMethod = m; break; }
            }
            if (addMethod == null) { Log.w(PancakeBootstrap.TAG, "ion.h not found"); return; }
            addMethod.setAccessible(true);
            HookEngine.hook(addMethod, new HookEngine.Callback() {
                @Override public void before(Pine.CallFrame frame) {
                    try { maybeInsert(frame); } catch (Throwable t) {
                        Log.e(PancakeBootstrap.TAG, "settings insert error", t);
                    }
                }
            });
            Log.i(PancakeBootstrap.TAG, "settings row-add hook installed (ion.h)");
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "SettingsMod install failed", t);
        }
    }

    /** Our sentinel title id → our text (covers both getText and getString paths). */
    private static void hookTitle() throws Exception {
        HookEngine.Callback cb = new HookEngine.Callback() {
            @Override public void before(Pine.CallFrame frame) {
                if (frame.args.length >= 1 && frame.args[0] instanceof Integer
                        && (Integer) frame.args[0] == TITLE_SENTINEL) {
                    frame.setResult(TITLE_TEXT);
                }
            }
        };
        HookEngine.hook(Resources.class.getMethod("getText", int.class), cb);
        HookEngine.hook(Resources.class.getMethod("getString", int.class), cb);
    }

    private static void maybeInsert(Pine.CallFrame frame) throws Exception {
        if (reentrant) return;
        Object collector = frame.args[0];
        Object row = frame.args[1];
        if (row == null || !ACCOUNT_ID.equals(firstString(row))) return;

        dumpOnce(row);
        reentrant = true;
        try {
            Object our = buildOurRow(row);
            addMethod.invoke(null, collector, our);      // added before Account → lands above it
            Log.i(PancakeBootstrap.TAG, "🥞 inserted 'Pancakeify Preferences' above Account");
        } finally {
            reentrant = false;
        }
    }

    /** Clone the Account gtl: new id, and a new ftl whose title is ours (title-only, no subtitle). */
    private static Object buildOurRow(Object accountGtl) throws Exception {
        Map<String, Object> gtlOverrides = new HashMap<>();
        // gtl.b = row id (String), gtl.c = ftl (title holder) — stable names for 9.1.80.2221
        gtlOverrides.put("b", OUR_ID);
        Object ftlOrig = getField(accountGtl, "c");
        if (ftlOrig != null) {
            Map<String, Object> ftlOverrides = new HashMap<>();
            // Mirror the original Account row exactly: title is the resolved String in ftl.b,
            // with ftl.a (resId) null. Just swap the text; blank the subtitle.
            ftlOverrides.put("a", null);            // titleResId → none
            ftlOverrides.put("b", TITLE_TEXT);      // titleText (this is what renders)
            ftlOverrides.put("c", null);            // subtitle resId → none
            ftlOverrides.put("d", null);            // subtitle text → none
            gtlOverrides.put("c", reconstruct(ftlOrig, ftlOverrides));
        }
        Object row = reconstruct(accountGtl, gtlOverrides);
        Log.i(PancakeBootstrap.TAG, "built our row id=" + firstString(row)
                + " ftl.title=" + describeTitle(getField(row, "c")));
        return row;
    }

    private static boolean dumped = false;
    private static void dumpOnce(Object gtl) throws Exception {
        if (dumped) return; dumped = true;
        Log.i(PancakeBootstrap.TAG, "DUMP gtl " + gtl.getClass().getName());
        for (Field f : instanceFields(gtl)) {
            Object v = f.get(gtl);
            Log.i(PancakeBootstrap.TAG, "DUMP  gtl." + f.getName() + " (" + f.getType().getSimpleName()
                    + ") = " + brief(v));
            if (v != null && !(v instanceof String) && !(v instanceof Integer)) {
                for (Field g : instanceFields(v)) {
                    Log.i(PancakeBootstrap.TAG, "DUMP    " + f.getName() + "." + g.getName() + " ("
                            + g.getType().getSimpleName() + ") = " + brief(g.get(v)));
                }
            }
        }
    }
    private static String brief(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        return v.getClass().getSimpleName() + ":" + (s.length() > 60 ? s.substring(0, 60) : s);
    }

    private static Object getField(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static String describeTitle(Object ftl) throws Exception {
        if (ftl == null) return "null-ftl";
        return "a=" + getField(ftl, "a") + " b=" + getField(ftl, "b");
    }

    /** Rebuild `orig` via a matching constructor, applying field-name overrides. */
    private static Object reconstruct(Object orig, Map<String, Object> overrides) throws Exception {
        Class<?> rc = orig.getClass();
        Field[] fields = instanceFields(orig);
        Object[] vals = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            vals[i] = overrides.containsKey(fields[i].getName()) ? overrides.get(fields[i].getName())
                                                                 : fields[i].get(orig);
        }
        // exact arity, params fit the actual values
        for (Constructor<?> c : rc.getDeclaredConstructors()) {
            Class<?>[] pts = c.getParameterTypes();
            if (pts.length == fields.length && valuesFit(pts, vals, fields.length)) {
                c.setAccessible(true); return c.newInstance(vals);
            }
        }
        // Kotlin defaults: fields + trailing int mask
        for (Constructor<?> c : rc.getDeclaredConstructors()) {
            Class<?>[] pts = c.getParameterTypes();
            if (pts.length == fields.length + 1 && pts[pts.length - 1] == int.class
                    && valuesFit(pts, vals, fields.length)) {
                c.setAccessible(true);
                Object[] args = new Object[pts.length];
                System.arraycopy(vals, 0, args, 0, vals.length);
                args[args.length - 1] = 0;
                return c.newInstance(args);
            }
        }
        throw new NoSuchMethodException("no ctor for " + rc.getName());
    }

    private static Field[] instanceFields(Object o) {
        java.util.List<Field> out = new java.util.ArrayList<>();
        for (Field f : o.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            out.add(f);
        }
        return out.toArray(new Field[0]);
    }

    /** True if each non-null value is assignable to the corresponding constructor param. */
    private static boolean valuesFit(Class<?>[] pts, Object[] vals, int n) {
        for (int i = 0; i < n; i++) {
            Class<?> p = pts[i];
            Object v = vals[i];
            if (v == null) { if (p.isPrimitive()) return false; continue; }
            if (p.isPrimitive()) {
                if (!((p == int.class && v instanceof Integer) || (p == boolean.class && v instanceof Boolean)
                        || (p == long.class && v instanceof Long) || (p == float.class && v instanceof Float)
                        || (p == double.class && v instanceof Double))) return false;
            } else if (!p.isInstance(v)) return false;
        }
        return true;
    }

    private static String firstString(Object o) throws Exception {
        for (Field f : instanceFields(o)) {
            if (f.getType() == String.class) return (String) f.get(o);
        }
        return null;
    }
}

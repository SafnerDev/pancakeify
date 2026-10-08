package com.pancakeify.stable;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import top.canyie.pine.Pine;

/**
 * Adds a "Pancakeify Preferences" row at the TOP of Spotify's Settings screen (above
 * "Account").
 *
 * Instead of field-cloning a row (defeated by Compose: the title is resolved from a model
 * object `a9e1` — `v9v0`(resId)/`dp31`(String) — via `p.e9e1.s(a9e1, Context)`, not from plain
 * fields), we build our row with Spotify's OWN builder:
 *   - `p.ion.q(String id, int, p.pwk0 click)` builds a row (p.gtl); the title is resolved
 *     internally via `p.e9e1.s(a9e1, Context)` (not stored as a plain field).
 *   - We hook ion.q; when the Account row is built (arg0 id == "accountPage") we re-invoke
 *     ion.q with our id + the same args, while an `overrideTitle` flag makes our hooked
 *     `e9e1.s` return "Pancakeify Preferences" for that rebuild. The fresh row's own
 *     Compose lambdas therefore capture our title.
 *   - We hook the static `p.ion.h(collector, row)`; right before the Account row is added we
 *     add our stashed row so it lands above Account.
 *
 * Anchor: the stable string id "accountPage". Click: see {@link #armClick}. The subtitle is
 * still inherited (cosmetic follow-up).
 */
public final class SettingsMod {
    static final String TITLE_TEXT = "Pancakeify Preferences";
    static final String SUBTITLE_TEXT = "Main Color \u2022 Spicy Lyrics";
    static final String OUR_ID = "pancakeifyPrefs";
    static final String ACCOUNT_ID = "accountPage";

    private static Anchors an;          // obfuscated names for this Spotify version
    private static Method qMethod;      // builder: (String id, int, dest) -> row   (instance)
    private static Method addMethod;    // how the row reaches the list (see Anchors.addArray)
    private static Object pendingRow;
    private static volatile boolean building = false, adding = false, overrideTitle = false;

    private SettingsMod() {}

    static java.lang.ref.WeakReference<android.app.Activity> currentActivity = new java.lang.ref.WeakReference<>(null);

    public static void install() {
        try {
            trackActivity();
            an = Anchors.detect(PancakeBootstrap.appContext);
            if (an == null) return;
            ClassLoader cl = PancakeBootstrap.appContext.getClassLoader();
            Class<?> builder = Class.forName(an.builderClass, false, cl);
            for (Method m : builder.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(an.builderMethod) && p.length == 3 && p[0] == String.class && p[1] == int.class) {
                    qMethod = m;
                }
            }
            Class<?> adder = Class.forName(an.addClass, false, cl);
            for (Method m : adder.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || !m.getName().equals(an.addMethod)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (an.addArray ? (p.length == 1 && p[0] == Object[].class) : p.length == 2) addMethod = m;
            }
            if (qMethod == null || addMethod == null) {
                Log.w(PancakeBootstrap.TAG, "settings anchors not found (q=" + qMethod + " add=" + addMethod + ")"); return;
            }
            qMethod.setAccessible(true);
            addMethod.setAccessible(true);

            hookNavigators(cl);   // early: also used for debug/NowPlaying
            hookTitleResolver();
            hookBuilder();
            hookAdder();
            Log.i(PancakeBootstrap.TAG, "SettingsMod installed (" + an.builderClass + "." + an.builderMethod
                    + " build + " + (an.addArray ? "array" : "list") + " insert)");
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "SettingsMod install failed", t);
        }
    }

    /** e9e1.s(a9e1, Context): while we rebuild OUR row, make every title resolve to our text. */
    private static void hookTitleResolver() throws Exception {
        Class<?> resolver = Class.forName(an.resolverClass, false, PancakeBootstrap.appContext.getClassLoader());
        Method s = null;
        for (Method m : resolver.getDeclaredMethods()) {
            if (m.getName().equals(an.resolverMethod) && m.getParameterTypes().length == 2
                    && m.getReturnType() == String.class) { s = m; break; }
        }
        if (s == null) { Log.w(PancakeBootstrap.TAG, "title resolver not found"); return; }
        s.setAccessible(true);
        HookEngine.hook(s, new HookEngine.Callback() {
            @Override public void before(Pine.CallFrame frame) {
                if (overrideTitle) frame.setResult(TITLE_TEXT);
            }
        });
    }

    /** When the Account row is built, rebuild an identical row under our id with our title. */
    private static void hookBuilder() {
        HookEngine.hook(qMethod, new HookEngine.Callback() {
            @Override public void after(Pine.CallFrame frame) {
                if (building) return;
                try {
                    if (!ACCOUNT_ID.equals(frame.args[0])) return;   // arg0 is the row id
                    building = true;
                    overrideTitle = true;
                    pendingRow = qMethod.invoke(frame.thisObject, OUR_ID, frame.args[1], frame.args[2]);
                    Log.i(PancakeBootstrap.TAG, "built our row via ion.q (title overridden)");
                } catch (Throwable t) {
                    Log.e(PancakeBootstrap.TAG, "ion.q build error", t);
                } finally {
                    overrideTitle = false;
                    building = false;
                }
            }
        });
    }

    /** Insert our stashed row right before the Account row reaches the list. */
    private static void hookAdder() {
        HookEngine.hook(addMethod, new HookEngine.Callback() {
            @Override public void before(Pine.CallFrame frame) {
                if (adding || pendingRow == null) return;
                try {
                    if (an.addArray) {
                        // rows are gathered in an array handed to Arrays.asList-like m0(Object[]): rebuild the array
                        // with our row in front of Account's
                        if (!(frame.args[0] instanceof Object[])) return;
                        Object[] arr = (Object[]) frame.args[0];
                        int at = -1;
                        for (int i = 0; i < arr.length; i++) {
                            if (arr[i] != null && qMethod.getReturnType().isInstance(arr[i])
                                    && ACCOUNT_ID.equals(firstString(arr[i]))) { at = i; break; }
                        }
                        if (at < 0) return;
                        adding = true;
                        Object our = pendingRow;
                        Object[] out = (Object[]) java.lang.reflect.Array.newInstance(arr.getClass().getComponentType(), arr.length + 1);
                        System.arraycopy(arr, 0, out, 0, at);
                        out[at] = our;
                        System.arraycopy(arr, at, out, at + 1, arr.length - at);
                        frame.args[0] = out;
                        Log.i(PancakeBootstrap.TAG, "🥞 inserted 'Pancakeify Preferences' above Account");
                        armClick(our);
                        pendingRow = null;
                    } else {
                        if (!ACCOUNT_ID.equals(firstString(frame.args[1]))) return;
                        adding = true;
                        Object our = pendingRow;
                        addMethod.invoke(null, frame.args[0], our);   // ours first → above Account
                        Log.i(PancakeBootstrap.TAG, "🥞 inserted 'Pancakeify Preferences' above Account");
                        armClick(our);
                        pendingRow = null;
                    }
                } catch (Throwable t) {
                    Log.e(PancakeBootstrap.TAG, "insert error", t);
                } finally {
                    adding = false;
                }
            }
        });
    }

    private static void trackActivity() {
        try {
            HookEngine.hook(android.app.Activity.class.getDeclaredMethod("onResume"),
                new HookEngine.Callback() {
                    @Override public void after(Pine.CallFrame frame) {
                        if (frame.thisObject instanceof android.app.Activity) {
                            android.app.Activity a = (android.app.Activity) frame.thisObject;
                            currentActivity = new java.lang.ref.WeakReference<>(a);
                            PancakePlayer.maybeTakeOver(a);   // fallback if onPostCreate was too early
                        }
                    }
                });
        } catch (Throwable t) { Log.w(PancakeBootstrap.TAG, "trackActivity failed: " + t.getMessage()); }
    }

    static final String OUR_LINK = "spotify:internal:pancakeify:preferences";
    private static boolean navHooked = false;
    private static long lastShow = 0;

    /**
     * Make our row navigate to its OWN link. The row's click handler ends in
     * {@code tyh0.b(String link, mb40, Bundle)} with {@code link = osa0.b.a}, where osa0 is the
     * row's element model (reachable via gtl.g -> dtl.b (Function1) -> osa0). We swap the row's
     * hka1 (link holder) for one carrying OUR_LINK and intercept that link in the navigators.
     */
    private static void armClick(Object row) {
        try {
            ClassLoader cl = PancakeBootstrap.appContext.getClassLoader();
            Class<?> hka1 = Class.forName(an.linkClass, false, cl);
            Class<?> osa0 = Class.forName(an.modelClass, false, cl);
            Object link = hka1.getConstructor(String.class).newInstance(OUR_LINK);

            Object model = findModel(row, osa0);
            if (model == null) { Log.w(PancakeBootstrap.TAG, "row model (osa0) not found"); return; }
            Field f = osa0.getDeclaredField("b");
            f.setAccessible(true);
            f.set(model, link);
            // description under the title: osa0.f is the static subtitle (null -> falls back to Account's)
            Field sub = osa0.getDeclaredField("f");
            sub.setAccessible(true);
            sub.set(model, SUBTITLE_TEXT);
            hookNavigators(cl);
            Log.i(PancakeBootstrap.TAG, "row link retargeted to " + OUR_LINK);
        } catch (Throwable t) { Log.e(PancakeBootstrap.TAG, "armClick failed", t); }
    }

    /**
     * Breadth-first walk over instance fields (shortest path first, identity-visited, capped) looking for an
     * instance of {@code want}. The path from the row to its element model differs between Spotify versions, so it
     * is searched instead of hard-coded.
     */
    private static Object findModel(Object start, Class<?> want) throws Exception {
        java.util.ArrayDeque<Object[]> q = new java.util.ArrayDeque<>();     // {object, depth}
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        q.add(new Object[]{start, 0});
        seen.put(start, Boolean.TRUE);
        int nodes = 0;
        while (!q.isEmpty() && nodes++ < 4000) {
            Object[] e = q.poll();
            Object o = e[0];
            int depth = (Integer) e[1];
            if (want.isInstance(o)) return o;
            if (depth >= 5) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                String kn = k.getName();
                if (kn.startsWith("java.") || kn.startsWith("android.") || kn.startsWith("kotlin.")) break;
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) continue;
                    if (seen.put(v, Boolean.TRUE) == null) q.add(new Object[]{v, depth + 1});
                }
            }
        }
        return null;
    }

    /** Hook every p.tyh0 implementation's b(String, mb40, Bundle); swallow OUR_LINK. */
    private static synchronized void hookNavigators(ClassLoader cl) {
        if (navHooked) return;
        navHooked = true;
        for (String name : an.navClasses) {
            try {
                for (Method m : Class.forName(name, false, cl).getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (!m.getName().equals(an.navMethod) || p.length != 3 || p[0] != String.class) continue;
                    m.setAccessible(true);
                    HookEngine.hook(m, new HookEngine.Callback() {
                        @Override public void before(Pine.CallFrame frame) {
                            if (OUR_LINK.equals(frame.args[0])) {
                                frame.setResult(null);       // skip the real navigation
                                onOurRowClick();
                            }
                        }
                    });
                    Log.i(PancakeBootstrap.TAG, "navigator hooked: " + name);
                }
            } catch (Throwable t) { Log.w(PancakeBootstrap.TAG, "navigator " + name + ": " + t); }
        }
    }

    private static void onOurRowClick() {
        long now = System.currentTimeMillis();
        if (now - lastShow < 600) return;
        lastShow = now;
        android.app.Activity a = currentActivity.get();
        Log.i(PancakeBootstrap.TAG, "🥞 Pancakeify Preferences opened (activity=" + a + ")");
        if (a != null) PancakePrefsScreen.show(a);
    }

    private static Object getField(Object o, String name) throws Exception {
        if (o == null) return null;
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static String firstString(Object o) throws Exception {
        if (o == null) return null;
        for (Field f : o.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == String.class) { f.setAccessible(true); return (String) f.get(o); }
        }
        return null;
    }
}

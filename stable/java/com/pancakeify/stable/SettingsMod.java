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

    private static Method qMethod;      // p.ion.q(String id,int,pwk0) -> gtl   (instance)
    private static Method addMethod;    // p.ion.h(collector, gtl)              (static)
    private static Object pendingRow;
    private static volatile boolean building = false, adding = false, overrideTitle = false;

    private SettingsMod() {}

    static java.lang.ref.WeakReference<android.app.Activity> currentActivity = new java.lang.ref.WeakReference<>(null);

    public static void install() {
        try {
            trackActivity();
            Class<?> ion = Class.forName("p.ion", false, PancakeBootstrap.appContext.getClassLoader());
            for (Method m : ion.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("q") && p.length == 3 && p[0] == String.class && p[1] == int.class) {
                    qMethod = m;
                } else if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("h") && p.length == 2) {
                    addMethod = m;
                }
            }
            if (qMethod == null || addMethod == null) {
                Log.w(PancakeBootstrap.TAG, "ion.q/h not found (q=" + qMethod + " h=" + addMethod + ")"); return;
            }
            qMethod.setAccessible(true);
            addMethod.setAccessible(true);

            hookNavigators(PancakeBootstrap.appContext.getClassLoader());   // early: also used for debug/NowPlaying
            hookTitleResolver();
            hookBuilder();
            hookAdder();
            Log.i(PancakeBootstrap.TAG, "SettingsMod installed (ion.q build + ion.h insert)");
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "SettingsMod install failed", t);
        }
    }

    /** e9e1.s(a9e1, Context): while we rebuild OUR row, make every title resolve to our text. */
    private static void hookTitleResolver() throws Exception {
        Class<?> e9e1 = Class.forName("p.e9e1", false, PancakeBootstrap.appContext.getClassLoader());
        Method s = null;
        for (Method m : e9e1.getDeclaredMethods()) {
            if (m.getName().equals("s") && m.getParameterTypes().length == 2
                    && m.getReturnType() == String.class) { s = m; break; }
        }
        if (s == null) { Log.w(PancakeBootstrap.TAG, "e9e1.s not found"); return; }
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

    /** Insert our stashed row right before the Account row is added. */
    private static void hookAdder() {
        HookEngine.hook(addMethod, new HookEngine.Callback() {
            @Override public void before(Pine.CallFrame frame) {
                if (adding || pendingRow == null) return;
                try {
                    if (!ACCOUNT_ID.equals(firstString(frame.args[1]))) return;
                    adding = true;
                    Object our = pendingRow;
                    addMethod.invoke(null, frame.args[0], our);   // ours first → above Account
                    Log.i(PancakeBootstrap.TAG, "🥞 inserted 'Pancakeify Preferences' above Account");
                    armClick(our);
                    pendingRow = null;
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
            Class<?> hka1 = Class.forName("p.hka1", false, cl);
            Class<?> osa0 = Class.forName("p.osa0", false, cl);
            Object link = hka1.getConstructor(String.class).newInstance(OUR_LINK);

            Object model = findModel(getField(row, "g"), osa0, 0);
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

    /** Depth-limited walk over instance fields looking for an instance of {@code want}. */
    private static Object findModel(Object o, Class<?> want, int depth) throws Exception {
        if (o == null || depth > 3) return null;
        if (want.isInstance(o)) return o;
        for (Field f : o.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            Object v = f.get(o);
            if (v == null || v instanceof String) continue;
            Object r = findModel(v, want, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** Hook every p.tyh0 implementation's b(String, mb40, Bundle); swallow OUR_LINK. */
    private static synchronized void hookNavigators(ClassLoader cl) {
        if (navHooked) return;
        navHooked = true;
        String[] impls = {"p.s4h0", "p.qa20", "p.w621"};   // tyh0 implementors (9.1.80.2221)
        for (String name : impls) {
            try {
                for (Method m : Class.forName(name, false, cl).getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (!m.getName().equals("b") || p.length != 3 || p[0] != String.class) continue;
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

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
 * Anchor: the stable string id "accountPage". v1 click reuses the Account row's action
 * (opens Account) and the subtitle is inherited; both are cosmetic follow-ups.
 */
public final class SettingsMod {
    static final String TITLE_TEXT = "Pancakeify Preferences";
    static final String OUR_ID = "pancakeifyPrefs";
    static final String ACCOUNT_ID = "accountPage";

    private static Method qMethod;      // p.ion.q(String id,int,pwk0) -> gtl   (instance)
    private static Method addMethod;    // p.ion.h(collector, gtl)              (static)
    private static Object pendingRow;
    private static volatile boolean building = false, adding = false, overrideTitle = false;

    private SettingsMod() {}

    public static void install() {
        try {
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
                    addMethod.invoke(null, frame.args[0], pendingRow);   // ours first → above Account
                    Log.i(PancakeBootstrap.TAG, "🥞 inserted 'Pancakeify Preferences' above Account");
                    pendingRow = null;
                } catch (Throwable t) {
                    Log.e(PancakeBootstrap.TAG, "insert error", t);
                } finally {
                    adding = false;
                }
            }
        });
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

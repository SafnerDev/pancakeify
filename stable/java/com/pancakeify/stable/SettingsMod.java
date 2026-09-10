package com.pancakeify.stable;

import android.content.res.Resources;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import top.canyie.pine.Pine;

/**
 * Adds a "Pancakeify Preferences" row at the TOP of Spotify's Settings screen (above
 * "Account").
 *
 * The Settings screen is built as a page object (obf. `Lp/biy0;`) holding a `List` of row
 * objects (obf. `Lp/aky0;`), assembled by page factories (`Lp/op;` / `Lp/ka5;`, both
 * implementing `create()`). We hook `create()`, detect the MAIN page (the one whose row list
 * contains a row titled `R.string.settings_page_account_title` — resolved at runtime, so we
 * anchor on the stable resource name, not obfuscated class names), clone that row with our
 * title, and insert it at index 0.
 *
 * v1: the cloned row keeps the Account row's click action (placeholder → opens Account).
 * Opening our own screen is the next step.
 */
public final class SettingsMod {
    static final int TITLE_SENTINEL = 0x7effff02;
    static final String TITLE_TEXT = "Pancakeify Preferences";

    // Version-specific page factories (9.1.80.2221). Anchor: they implement create():biy0.
    private static final String[] FACTORY_CLASSES = {"p.op", "p.ka5"};

    private static int accountResId = 0;

    private SettingsMod() {}

    public static void install() {
        try {
            accountResId = PancakeBootstrap.appContext.getResources().getIdentifier(
                    "settings_page_account_title", "string", PancakeBootstrap.appContext.getPackageName());
            Log.i(PancakeBootstrap.TAG, "settings_page_account_title resId=" + Integer.toHexString(accountResId));
            if (accountResId == 0) { Log.w(PancakeBootstrap.TAG, "account resid not found; skip"); return; }
            hookTitle();
            for (String fqcn : FACTORY_CLASSES) hookFactory(fqcn);
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "SettingsMod install failed", t);
        }
    }

    private static void hookTitle() throws Exception {
        HookEngine.hook(Resources.class.getMethod("getText", int.class), new HookEngine.Callback() {
            @Override public void before(Pine.CallFrame frame) {
                if (frame.args.length == 1 && (Integer) frame.args[0] == TITLE_SENTINEL) {
                    frame.setResult(TITLE_TEXT);
                }
            }
        });
    }

    private static void hookFactory(String fqcn) {
        try {
            Class<?> cls = Class.forName(fqcn, false, PancakeBootstrap.appContext.getClassLoader());
            java.lang.reflect.Method create = cls.getDeclaredMethod("create");
            create.setAccessible(true);
            HookEngine.hook(create, new HookEngine.Callback() {
                @Override public void after(Pine.CallFrame frame) {
                    try { augment(frame); } catch (Throwable t) {
                        Log.e(PancakeBootstrap.TAG, "settings augment error", t);
                    }
                }
            });
            Log.i(PancakeBootstrap.TAG, "settings factory hook installed on " + fqcn + ".create()");
        } catch (Throwable t) {
            Log.w(PancakeBootstrap.TAG, "no factory " + fqcn + ": " + t.getMessage());
        }
    }

    private static void augment(Pine.CallFrame frame) throws Exception {
        Object page = frame.getResult();
        if (page == null) return;

        // Find the List field holding the rows, and the Account row within it.
        Field listField = null;
        List<?> rows = null;
        Object accountRow = null;
        for (Field f : page.getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            Object v = f.get(page);
            if (!(v instanceof List)) continue;
            for (Object row : (List<?>) v) {
                Integer title = firstMatchingInteger(row, accountResId);
                if (title != null) { listField = f; rows = (List<?>) v; accountRow = row; break; }
            }
            if (rows != null) break;
        }
        if (rows == null) return;                  // not the main settings page

        Object clone = cloneRow(accountRow, TITLE_SENTINEL);
        List<Object> newRows = new ArrayList<>();
        newRows.add(clone);                         // top, above Account
        //noinspection unchecked
        newRows.addAll((List<Object>) rows);

        Object newPage = rebuildPage(page, listField, newRows);
        frame.setResult(newPage);
        Log.i(PancakeBootstrap.TAG, "🥞 added 'Pancakeify Preferences' at top of Settings (page had "
                + rows.size() + " rows)");
    }

    /** Returns the value of the row's Integer field iff it equals wanted (the title). */
    private static Integer firstMatchingInteger(Object row, int wanted) throws Exception {
        if (row == null) return null;
        for (Field f : row.getClass().getDeclaredFields()) {
            if (f.getType() == Integer.class) {
                f.setAccessible(true);
                Object v = f.get(row);
                if (v instanceof Integer && (Integer) v == wanted) return (Integer) v;
            }
        }
        return null;
    }

    /** Clone a row via its constructor, feeding fields back in declared order, title swapped. */
    private static Object cloneRow(Object row, int titleId) throws Exception {
        Class<?> rc = row.getClass();
        List<Object> vals = new ArrayList<>();
        List<Field> instFields = new ArrayList<>();
        for (Field f : rc.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            instFields.add(f);
            vals.add(f.get(row));
        }
        // Pick the constructor whose leading params match the instance fields (+ trailing int mask).
        Constructor<?> ctor = null;
        for (Constructor<?> c : rc.getDeclaredConstructors()) {
            Class<?>[] pts = c.getParameterTypes();
            if (pts.length == instFields.size() + 1
                    && pts[pts.length - 1] == int.class) { ctor = c; break; }
        }
        if (ctor == null) throw new NoSuchMethodException("row ctor for " + rc.getName());
        ctor.setAccessible(true);

        Object[] args = new Object[instFields.size() + 1];
        for (int i = 0; i < instFields.size(); i++) args[i] = vals.get(i);
        // swap the title: the first Integer-typed field is the title (b).
        for (int i = 0; i < instFields.size(); i++) {
            if (instFields.get(i).getType() == Integer.class) { args[i] = titleId; break; }
        }
        args[args.length - 1] = 0;                   // defaults mask: none, we supply all
        return ctor.newInstance(args);
    }

    /** Rebuild the page via its (v8u, v9v0, erx, List, List) constructor with the new row list. */
    private static Object rebuildPage(Object page, Field listField, List<Object> newRows) throws Exception {
        Class<?> pc = page.getClass();
        List<Field> instFields = new ArrayList<>();
        for (Field f : pc.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            instFields.add(f);
        }
        // The real 5-arg ctor: same arity as fields, no trailing int.
        Constructor<?> ctor = null;
        for (Constructor<?> c : pc.getDeclaredConstructors()) {
            Class<?>[] pts = c.getParameterTypes();
            if (pts.length == instFields.size()
                    && pts[pts.length - 1] != int.class) { ctor = c; break; }
        }
        if (ctor == null) throw new NoSuchMethodException("page ctor for " + pc.getName());
        ctor.setAccessible(true);

        Object[] args = new Object[instFields.size()];
        for (int i = 0; i < instFields.size(); i++) {
            Field f = instFields.get(i);
            args[i] = f.equals(listField) ? newRows : f.get(page);
        }
        return ctor.newInstance(args);
    }
}

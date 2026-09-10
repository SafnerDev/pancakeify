package com.pancakeify.stable;

import android.content.res.Resources;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import top.canyie.pine.Pine;

/**
 * Injects a "Pancakeify Preferences" row into Spotify's account side drawer, right after
 * "Settings and privacy".
 *
 * Robustness strategy (see docs/SCOUTING_sidedrawer.md): we anchor on the STABLE string
 * resource `sidedrawer_link_settings` (resolved at runtime), not on obfuscated class names.
 * We hook the builder that returns the List of drawer rows, find the settings row, CLONE its
 * record with our title, and insert the clone. The drawer row record's fields all have
 * distinct types, so we copy them into the record constructor by TYPE — no hardcoded field
 * letters. The only version-specific anchor is the builder class name (`p.yoc` in 9.1.80.2221).
 *
 * v1: the cloned row's click still points at Spotify Settings (placeholder). Opening our own
 * screen is the next step.
 */
public final class SideDrawer {
    // Sentinel resource id (unused package 0x7e) → our title via a getText() hook.
    static final int TITLE_SENTINEL = 0x7effff01;
    static final String TITLE_TEXT = "Pancakeify Preferences";

    // Version-specific: the R8-merged lambda whose invoke() returns List<row>. Anchor to
    // replace with a resolver later. Only used to place the hook.
    private static final String BUILDER_CLASS = "p.yoc";

    private static int settingsResId = 0;
    private static boolean injected = false;

    private SideDrawer() {}

    public static void install() {
        try {
            settingsResId = PancakeBootstrap.appContext.getResources().getIdentifier(
                    "sidedrawer_link_settings", "string", PancakeBootstrap.appContext.getPackageName());
            Log.i(PancakeBootstrap.TAG, "sidedrawer_link_settings resId=" + Integer.toHexString(settingsResId));
            if (settingsResId == 0) {
                Log.w(PancakeBootstrap.TAG, "settings resid not found; drawer inject skipped");
                return;
            }
            hookTitle();
            hookBuilder();
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "SideDrawer install failed", t);
        }
    }

    /** Make our sentinel title id resolve to our text. */
    private static void hookTitle() throws Exception {
        java.lang.reflect.Method getText =
                Resources.class.getMethod("getText", int.class);
        HookEngine.hook(getText, new HookEngine.Callback() {
            @Override public void before(Pine.CallFrame frame) {
                if (frame.args.length == 1 && (Integer) frame.args[0] == TITLE_SENTINEL) {
                    frame.setResult(TITLE_TEXT); // setResult skips the original (would throw)
                }
            }
        });
    }

    /** Hook the row-list builder and append our cloned row. */
    private static void hookBuilder() throws Exception {
        Class<?> builder = Class.forName(BUILDER_CLASS, false, PancakeBootstrap.appContext.getClassLoader());
        java.lang.reflect.Method invoke = builder.getDeclaredMethod("invoke");
        invoke.setAccessible(true);
        HookEngine.hook(invoke, new HookEngine.Callback() {
            @Override public void after(Pine.CallFrame frame) {
                try { maybeInject(frame); } catch (Throwable t) {
                    Log.e(PancakeBootstrap.TAG, "drawer inject error", t);
                }
            }
        });
        Log.i(PancakeBootstrap.TAG, "drawer builder hook installed on " + BUILDER_CLASS + ".invoke()");
    }

    private static void maybeInject(Pine.CallFrame frame) throws Exception {
        Object res = frame.getResult();
        if (!(res instanceof List)) return;
        List<?> list = (List<?>) res;
        if (list.isEmpty()) return;

        int idx = -1;
        Object settingsRow = null, settingsRecord = null;
        for (int i = 0; i < list.size(); i++) {
            Object row = list.get(i);
            Object record = unwrapRecord(row);
            if (record == null) continue;
            Integer title = findIntegerField(record);
            if (title != null && title == settingsResId) {
                idx = i; settingsRow = row; settingsRecord = record; break;
            }
        }
        if (idx < 0) return;                       // not the drawer list
        if (injected) return;                       // idempotent per process is fine; list rebuilt each time
        Log.i(PancakeBootstrap.TAG, "found Settings row at index " + idx + " in list of " + list.size());

        Object cloneRecord = cloneRecordWithTitle(settingsRecord, TITLE_SENTINEL);
        Object cloneRow = wrapRecord(settingsRow.getClass(), cloneRecord);

        @SuppressWarnings("unchecked")
        List<Object> out = new ArrayList<>((List<Object>) list);
        out.add(idx + 1, cloneRow);
        frame.setResult(out);
        Log.i(PancakeBootstrap.TAG, "🥞 injected 'Pancakeify Preferences' row after Settings");
    }

    /** row (r301) wraps the record in its single reference field. */
    private static Object unwrapRecord(Object row) throws Exception {
        for (Field f : row.getClass().getDeclaredFields()) {
            if (f.getType().isPrimitive() || f.getType() == String.class) continue;
            f.setAccessible(true);
            Object v = f.get(row);
            if (v != null && findIntegerField(v) != null) return v; // the record has an Integer titleResId
        }
        return null;
    }

    private static Integer findIntegerField(Object record) throws Exception {
        for (Field f : record.getClass().getDeclaredFields()) {
            if (f.getType() == Integer.class) {
                f.setAccessible(true);
                return (Integer) f.get(record);
            }
        }
        return null;
    }

    /** Build a new record of the same class, copying every field by type, but with our title. */
    private static Object cloneRecordWithTitle(Object record, int titleId) throws Exception {
        Class<?> rc = record.getClass();
        Constructor<?> ctor = null;
        for (Constructor<?> c : rc.getDeclaredConstructors()) {
            if (c.getParameterTypes().length >= 5) { ctor = c; break; }
        }
        if (ctor == null) throw new NoSuchMethodException("record ctor");
        ctor.setAccessible(true);
        Class<?>[] pts = ctor.getParameterTypes();
        Object[] args = new Object[pts.length];
        for (int i = 0; i < pts.length; i++) {
            if (pts[i] == Integer.class) {
                args[i] = titleId;                 // our title
            } else {
                args[i] = firstFieldOfType(record, pts[i]);
            }
        }
        return ctor.newInstance(args);
    }

    private static Object firstFieldOfType(Object record, Class<?> type) throws Exception {
        for (Field f : record.getClass().getDeclaredFields()) {
            if (f.getType() == type) { f.setAccessible(true); return f.get(record); }
        }
        return null;                                // primitive/default params (e.g. flags) → null/0
    }

    /** Wrap our record into a new row (r301) via its single-arg constructor. */
    private static Object wrapRecord(Class<?> rowClass, Object record) throws Exception {
        for (Constructor<?> c : rowClass.getDeclaredConstructors()) {
            Class<?>[] pts = c.getParameterTypes();
            if (pts.length == 1 && pts[0].isInstance(record)) {
                c.setAccessible(true);
                return c.newInstance(record);
            }
        }
        throw new NoSuchMethodException("row ctor(record)");
    }
}

package com.pancakeify.stable;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

import top.canyie.pine.Pine;

/**
 * Pancakeify STABLE-layer entry. The patcher injects one call to
 * {@link #start(Application, Context)} into the host Application's attachBaseContext.
 *
 * M3 milestone: prove ART method hooking works on Android 17 via Pine. We hook
 * {@code Activity.onResume()} and log when it fires — if the host's own activities trip our
 * hook, inline hooking is live. Later milestones replace this demo with the dynamic-dex
 * load + real theme/plugin hooks.
 */
public final class PancakeBootstrap {
    public static final String TAG = "Pancakeify";

    private static boolean started = false;

    private PancakeBootstrap() {}

    public static synchronized void start(Application app, Context baseContext) {
        if (started) return;
        started = true;
        Log.i(TAG, "🥞 Pancakeify alive: bootstrap injected into host attachBaseContext");

        try {
            // Spotify release build is not debuggable.
            if (!HookEngine.init(/*targetDebuggable=*/false)) {
                Log.e(TAG, "HookEngine not ready; skipping M3 demo hook");
                return;
            }
            installDemoHook();
        } catch (Throwable t) {
            Log.e(TAG, "M3 bootstrap error (host untouched)", t);
        }
    }

    /** Hooks Activity.onResume to prove hooking is live. */
    private static void installDemoHook() throws NoSuchMethodException {
        Method onResume = Activity.class.getDeclaredMethod("onResume");
        HookEngine.hook(onResume, new HookEngine.Callback() {
            @Override public void before(Pine.CallFrame frame) {
                Object act = frame.thisObject;
                Log.i(TAG, "🥞 HOOK FIRED: Activity.onResume on "
                        + (act != null ? act.getClass().getName() : "null"));
            }
        });
        Log.i(TAG, "M3 demo hook installed on Activity.onResume");
    }
}

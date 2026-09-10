package com.pancakeify.stable;

import android.app.Application;
import android.content.Context;
import android.util.Log;

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

    public static Context appContext;

    private static boolean started = false;

    private PancakeBootstrap() {}

    public static synchronized void start(Application app, Context baseContext) {
        if (started) return;
        started = true;
        Context c = baseContext.getApplicationContext();
        appContext = (c != null) ? c : baseContext;
        Log.i(TAG, "🥞 Pancakeify alive: bootstrap injected into host attachBaseContext");

        try {
            // Spotify release build is not debuggable.
            if (!HookEngine.init(/*targetDebuggable=*/false)) {
                Log.e(TAG, "HookEngine not ready; skipping features");
                return;
            }
            SettingsMod.install();   // add "Pancakeify Preferences" at top of Settings
        } catch (Throwable t) {
            Log.e(TAG, "bootstrap error (host untouched)", t);
        }
    }
}

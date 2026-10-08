package com.pancakeify.stable;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * Pancakeify STABLE-layer entry. The patcher injects one call to
 * {@link #start(Application, Context)} into the host Application's attachBaseContext (or, when that is not
 * possible, {@link #early(Application)} into its constructor).
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

    /**
     * Alternative entry for hosts whose attachBaseContext cannot take our call (Spotify 9.1.90: it is a FINAL
     * method of a superclass that lives in a dex already at the 65 536-method limit). The patcher then injects
     * this call into the host Application's CONSTRUCTOR, which runs before attachBaseContext; we hook
     * ContextWrapper.attachBaseContext and run {@link #start} the moment it is called for our Application.
     */
    public static void early(final Application app) {
        try {
            if (!HookEngine.init(/*targetDebuggable=*/false)) return;
            java.lang.reflect.Method m = android.content.ContextWrapper.class
                    .getDeclaredMethod("attachBaseContext", Context.class);
            HookEngine.hook(m, new HookEngine.Callback() {
                @Override public void after(top.canyie.pine.Pine.CallFrame frame) {
                    if (frame.thisObject == app && frame.args != null && frame.args.length > 0
                            && frame.args[0] instanceof Context) {
                        start(app, (Context) frame.args[0]);
                    }
                }
            });
            Log.i(TAG, "early entry armed (waiting for attachBaseContext)");
        } catch (Throwable t) {
            Log.e(TAG, "early entry failed (host untouched)", t);
        }
    }

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
            MediaBridge.install();   // capture Spotify's MediaSession (now playing data + controls)
            PancakePlayer.install();  // Spicy-Lyrics style Now Playing screen (when enabled)
            MainColorMod.install();  // recolour Spotify's green to the user's Main Color
        } catch (Throwable t) {
            Log.e(TAG, "bootstrap error (host untouched)", t);
        }
    }
}

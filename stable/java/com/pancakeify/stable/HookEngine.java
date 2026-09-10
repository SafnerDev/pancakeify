package com.pancakeify.stable;

import android.util.Log;

import java.lang.reflect.Member;

import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;

/**
 * ART hooking façade for the Pancakeify stable layer, backed by Pine
 * (https://github.com/canyie/pine). This is the open-source replacement for windukk's
 * proprietary HookBridge. The façade is intentionally engine-agnostic: swapping Pine for
 * LSPlant later means reimplementing only this file — mod code keeps calling HookEngine.
 *
 * Pine 0.3.0's Java guard only rejects SDK < 19 (no upper bound), and it measures ArtMethod
 * layout at runtime (Pine's Ruler), so it has a real shot at working on Android 17 / SDK 37
 * where the old LSPatch hook layer crashed.
 */
public final class HookEngine {
    private static boolean ready = false;

    private HookEngine() {}

    public static synchronized boolean init(boolean targetDebuggable) {
        if (ready) return true;
        try {
            PineConfig.debug = true;                 // verbose Pine logging (logcat tag: Pine)
            PineConfig.debuggable = targetDebuggable; // false for release Spotify
            PineConfig.disableHiddenApiPolicy = true;
            PineConfig.disableHiddenApiPolicyForPlatformDomain = true;
            Pine.ensureInitialized();
            ready = true;
            Log.i(PancakeBootstrap.TAG, "HookEngine (Pine) initialized; sdk="
                    + android.os.Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            Log.e(PancakeBootstrap.TAG, "HookEngine init failed", t);
        }
        return ready;
    }

    public static boolean isReady() { return ready; }

    /** Xposed-style hook. Returns an Unhook handle, or null on failure. */
    public static Callback hook(Member target, final Callback cb) {
        if (!ready) { Log.w(PancakeBootstrap.TAG, "hook before init"); return null; }
        Pine.hook(target, new MethodHook() {
            @Override public void beforeCall(Pine.CallFrame frame) { cb.before(frame); }
            @Override public void afterCall(Pine.CallFrame frame) { cb.after(frame); }
        });
        return cb;
    }

    /** Minimal callback surface; Pine's CallFrame carries thisObject/args/result. */
    public interface Callback {
        default void before(Pine.CallFrame frame) {}
        default void after(Pine.CallFrame frame) {}
    }
}

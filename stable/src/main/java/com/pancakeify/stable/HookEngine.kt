package com.pancakeify.stable

import android.content.Context
import android.util.Log
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Thin façade over the ART hooking backend. Backed by **LSPlant**
 * (https://github.com/LSPosed/LSPlant) + an inline-hook impl (Dobby), shipped as
 * `lib/arm64-v8a/liblsplant.so` and initialized here.
 *
 * This is the open-source replacement for windukk's proprietary `com.windukk.hook.HookBridge`
 * (direct ArtMethod editing). LSPlant is actively maintained and tracks new ART internals,
 * which is why it works on Android 17 where the old LSPatch hook layer crashed.
 *
 * M3 wires the real JNI. Until then the methods are no-ops that log, so the rest of the
 * pipeline (bootstrap → dex load → Main.start) can be brought up and tested first.
 */
object HookEngine {
    private const val LIB = "lsplant"        // liblsplant.so
    private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        try {
            System.loadLibrary(LIB)
            nativeInit()
            ready = true
            Log.i(PancakeBootstrap.TAG, "HookEngine (LSPlant) initialized")
        } catch (t: Throwable) {
            Log.w(PancakeBootstrap.TAG, "HookEngine native not present yet (M3): ${t.message}")
        }
    }

    fun disableHiddenApiRestrictions(): Boolean {
        // LSPlant/its bootstrap exposes hidden-api unlock; stubbed until M3.
        return runCatching { if (ready) nativeDisableHiddenApi() else false }.getOrDefault(false)
    }

    /**
     * Hook [target], routing calls through [callback]. Returns an [Unhook] handle.
     * Xposed-style before/after semantics, matching the windukk API shape so mod code
     * written against it stays familiar.
     */
    fun hook(target: Member, callback: HookCallback): Unhook {
        check(ready) { "HookEngine not ready (M3 native pending)" }
        val backup = nativeHook(target, callback)
        return Unhook(target, backup)
    }

    interface HookCallback {
        fun before(param: Param) {}
        fun after(param: Param) {}
    }

    class Param(
        @JvmField val method: Member,
        @JvmField val thisObject: Any?,
        @JvmField val args: Array<Any?>,
    ) {
        @JvmField var result: Any? = null
    }

    class Unhook(private val target: Member, private val backup: Any?) {
        fun unhook() { if (backup != null) nativeUnhook(target, backup) }
    }

    // --- native (M3) ---
    private external fun nativeInit()
    private external fun nativeDisableHiddenApi(): Boolean
    private external fun nativeHook(target: Member, callback: HookCallback): Any?
    private external fun nativeUnhook(target: Member, backup: Any)

    // Kept to force-load the Method symbol referenced by JNI signature generation.
    @Suppress("unused")
    private val hookMethodMarker: Class<Method> = Method::class.java
}

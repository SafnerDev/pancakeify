package com.pancakeify.stable

import android.app.Application
import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Entry point of the Pancakeify STABLE layer.
 *
 * The patcher injects a single call to [start] into the host app's
 * `Application.attachBaseContext(Context)`. Everything else is discovered at runtime, so
 * this class is the only hard contract the patched APK depends on — mirroring windukk's
 * `com.windukk.stable.StableBootstrap.start(Application, Context)`.
 *
 * Responsibilities (kept deliberately tiny and stable):
 *  1. Guard against double init.
 *  2. Unlock hidden API + init the hook engine (LSPlant).
 *  3. Load the dynamic mod dex from `assets/pancake/pancake.dex`.
 *  4. Reflectively invoke [DYNAMIC_ENTRY].start(app, ctx).
 *
 * The heavy, fast-changing logic all lives in the dynamic layer so this rarely changes.
 */
object PancakeBootstrap {
    const val TAG = "Pancakeify"

    /** FQCN + method of the dynamic-layer entry. Kept out of the mod dex on purpose. */
    private const val DYNAMIC_ENTRY = "com.pancakeify.mod.Main"
    private const val DYNAMIC_ENTRY_METHOD = "start"

    @Volatile
    private var started = false

    @JvmStatic
    @Synchronized
    fun start(app: Application, baseContext: Context) {
        if (started) return
        started = true

        val ctx = baseContext.applicationContext ?: baseContext
        Env.appContext = ctx

        try {
            // 1. Hook engine up first: some hosts install their own hidden-api guard early,
            //    so we unlock and initialize before touching host internals.
            HookEngine.init(ctx)
            HookEngine.disableHiddenApiRestrictions()

            // 2. Load the dynamic mod dex into a child classloader whose parent is the
            //    host loader, so the mod can see host classes (and LSPlant can resolve
            //    host Methods to hook).
            val modLoader = DexLoader.loadDynamicDex(ctx, app.classLoader)

            // 3. Hand control to the dynamic layer.
            invokeDynamicEntry(modLoader, app, baseContext)

            Log.i(TAG, "Pancakeify stable layer up; dynamic entry started.")
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.cause ?: t
            Log.e(TAG, "Pancakeify bootstrap failed (host left untouched): ", cause)
            // Swallow — never crash the host if the mod fails to load.
        }
    }

    private fun invokeDynamicEntry(loader: ClassLoader, app: Application, ctx: Context) {
        val cls = Class.forName(DYNAMIC_ENTRY, true, loader)
        val m = cls.getMethod(DYNAMIC_ENTRY_METHOD, Application::class.java, Context::class.java)
        m.invoke(null, app, ctx)
    }
}

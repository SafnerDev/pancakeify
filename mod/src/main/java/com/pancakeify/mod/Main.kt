package com.pancakeify.mod

import android.app.Application
import android.content.Context
import android.util.Log

/**
 * DYNAMIC-layer entry point. The stable layer reflectively calls
 * [start] (App, Context) — this is the exact contract windukk uses via
 * `com.windukk.mod.Main.start(Application, Context)`.
 *
 * Everything here can change/ship as an update without re-patching the APK.
 */
object Main {
    const val TAG = "Pancakeify/mod"
    const val MOD_VERSION = "0.1.0-dev"

    @JvmStatic
    lateinit var appContext: Context
        private set

    @JvmStatic
    lateinit var application: Application
        private set

    @JvmStatic
    fun start(app: Application, baseContext: Context) {
        application = app
        appContext = baseContext.applicationContext ?: baseContext
        Log.i(TAG, "Pancakeify mod $MOD_VERSION starting")

        ThemeEngine.install()
        PluginLoader.loadAll(appContext)

        Log.i(TAG, "Pancakeify mod ready")
    }
}

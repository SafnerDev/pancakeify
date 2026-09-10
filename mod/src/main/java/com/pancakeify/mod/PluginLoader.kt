package com.pancakeify.mod

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Loads user plugins (Spicetify "extensions" analog) from external storage:
 *   /sdcard/Pancakeify/plugins/*.js   — JS injected into every WebView
 *   /sdcard/Pancakeify/themes/*.css   — themes selectable via ThemeStore
 *
 * Plugins are plain JS/CSS text (no code execution in the host process), so the trust
 * boundary is the WebView, not ART. Native/Java plugins are a later, separate mechanism.
 */
object PluginLoader {
    private val jsPlugins = mutableListOf<String>()

    fun loadAll(ctx: Context) {
        val root = File(Environment.getExternalStorageDirectory(), "Pancakeify")
        val pluginsDir = File(root, "plugins")
        val themesDir = File(root, "themes")

        jsPlugins.clear()
        pluginsDir.listFiles { f -> f.extension == "js" }?.sortedBy { it.name }?.forEach {
            runCatching { jsPlugins += it.readText() }
                .onFailure { e -> Log.w(Main.TAG, "plugin ${it} failed: ${e.message}") }
        }
        ThemeStore.refresh(ctx, themesDir)
        Log.i(Main.TAG, "Loaded ${jsPlugins.size} JS plugin(s)")
    }

    fun combinedJs(): String = jsPlugins.joinToString("\n;\n")
}

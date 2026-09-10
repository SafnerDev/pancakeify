package com.pancakeify.mod

import android.content.Context
import java.io.File

/** Holds the active theme CSS. Falls back to a bundled default asset. */
object ThemeStore {
    private var css: String = ""

    fun refresh(ctx: Context, themesDir: File) {
        // Preference for a user-selected theme; else first *.css; else bundled default.
        val userCss = themesDir.listFiles { f -> f.extension == "css" }
            ?.sortedBy { it.name }?.firstOrNull()
        css = when {
            userCss != null -> userCss.readText()
            else -> runCatching {
                ctx.assets.open("themes/default.css").use { it.readBytes().decodeToString() }
            }.getOrDefault("")
        }
    }

    fun activeCss(): String = css
}

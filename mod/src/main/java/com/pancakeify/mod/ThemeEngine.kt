package com.pancakeify.mod

import android.util.Log
import android.webkit.WebView
import com.pancakeify.stable.HookEngine

/**
 * The Spicetify-equivalent core: inject CSS/JS into Spotify's WebViews.
 *
 * Spotify Android renders large parts of its UI (and lyrics/now-playing surfaces) in
 * WebViews — 11 of 13 base dexes reference android.webkit.WebView. We hook
 * `WebView.loadUrl` / `WebViewClient.onPageFinished` and evaluate our theme CSS + plugin
 * JS once each page settles. This is how a "Pancake Lyrics" plugin can render synced
 * lyrics à la Apple Music.
 */
object ThemeEngine {

    fun install() {
        val hookEngineReady = runCatching { HookEngine::class.java }.isSuccess
        if (!hookEngineReady) return

        try {
            val loadUrl = WebView::class.java.getDeclaredMethod("loadUrl", String::class.java)
            HookEngine.hook(loadUrl, object : HookEngine.HookCallback {
                override fun after(param: HookEngine.Param) {
                    val wv = param.thisObject as? WebView ?: return
                    inject(wv)
                }
            })
            Log.i(Main.TAG, "ThemeEngine installed WebView hook")
        } catch (t: Throwable) {
            // M3: needs live HookEngine. Until then, log and continue.
            Log.w(Main.TAG, "ThemeEngine hook deferred (needs LSPlant): ${t.message}")
        }
    }

    /** Evaluates the active theme's CSS + enabled plugins' JS in the given WebView. */
    private fun inject(wv: WebView) {
        val css = ThemeStore.activeCss()
        val js = buildString {
            append("(function(){")
            append("var s=document.createElement('style');s.id='pancakeify';")
            append("s.textContent=").append(jsString(css)).append(";")
            append("document.documentElement.appendChild(s);")
            append(PluginLoader.combinedJs())
            append("})();")
        }
        wv.post { wv.evaluateJavascript(js, null) }
    }

    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

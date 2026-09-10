package com.pancakeify.stable

import android.content.Context

/** Tiny process-wide holder, equivalent to windukk's `a/g` singleton. */
object Env {
    @JvmStatic
    lateinit var appContext: Context

    const val STABLE_API_VERSION = 1
}

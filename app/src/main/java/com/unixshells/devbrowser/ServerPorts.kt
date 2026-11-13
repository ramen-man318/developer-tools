package com.unixshells.devbrowser

import android.content.Context
import android.content.pm.ApplicationInfo

/** 公式版とdebug版を同時に起動しても内部サーバーのポートが衝突しないようにする。 */
object ServerPorts {
    private fun debug(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun cdpHttp(context: Context): Int = if (debug(context)) 19222 else 9222
    fun cdpWs(context: Context): Int = if (debug(context)) 19223 else 9223
    fun devTools(context: Context): Int = if (debug(context)) 19224 else 9224
}

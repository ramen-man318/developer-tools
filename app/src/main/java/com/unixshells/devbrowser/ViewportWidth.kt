package com.unixshells.devbrowser

object ViewportWidth {
    const val MAX = 10_000_000

    fun parse(value: String): Int? {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        return value.toIntOrNull()?.takeIf { it in 1..MAX }
    }
}

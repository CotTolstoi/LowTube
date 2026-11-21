package com.lowtube.android.data

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal fun retryAfterSeconds(header: String?, fallback: Long, maximum: Long): Long {
    val value = header?.trim().orEmpty()
    if (value.isEmpty()) return fallback
    value.toLongOrNull()?.let { return it.coerceIn(1, maximum) }

    val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("GMT")
    }
    val position = ParsePosition(0)
    val date = format.parse(value, position) ?: return fallback
    if (position.index != value.length) return fallback
    val remainingMs = date.time - System.currentTimeMillis()
    return ((remainingMs.coerceAtLeast(0) + 999) / 1_000).coerceIn(1, maximum)
}

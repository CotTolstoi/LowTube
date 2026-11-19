package com.lowtube.android.data

/** Supported app-wide UI density presets; system font scaling remains multiplicative. */
object UiScaleOptions {
    val VALUES = listOf(0.85f, 1.0f, 1.15f, 1.30f)
    const val DEFAULT = 1.0f

    fun normalize(value: Float): Float = VALUES.firstOrNull { it == value } ?: DEFAULT
}

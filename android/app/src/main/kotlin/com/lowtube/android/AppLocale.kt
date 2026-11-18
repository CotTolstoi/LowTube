package com.lowtube.android

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLocale {
    private const val PREFERENCES = "lowtube_settings"
    private const val LANGUAGE = "language"
    private val supported = setOf("en", "uk", "ru")

    fun language(context: Context): String = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(LANGUAGE, "en")
        ?.takeIf { it in supported }
        ?: "en"

    fun setLanguage(context: Context, language: String) {
        require(language in supported)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE, language)
            .apply()
    }

    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(language(context))
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= 24) configuration.setLocales(LocaleList(locale))
        else configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }
}

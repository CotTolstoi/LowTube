package com.lowtube.android.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LanguageNamesLocalizationTest {
    @Test
    fun languagePickerNamesUseEachSelectedLanguage() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertLanguageNames(context, Locale.ENGLISH)
        assertLanguageNames(context, Locale("uk"))
        assertLanguageNames(context, Locale("ru"))
    }

    private fun assertLanguageNames(
        context: Context,
        locale: Locale,
    ) {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(configuration)

        assertEquals("English", localized.getString(R.string.language_english))
        assertEquals("Русский", localized.getString(R.string.language_russian))
        assertEquals("Українська", localized.getString(R.string.language_ukrainian))
    }
}

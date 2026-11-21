package com.lowtube.android.ui.downloads

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.lowtube.android.AppLocale
import com.lowtube.android.R
import com.lowtube.android.downloads.DownloadNotificationFactory
import org.junit.Rule
import org.junit.Test

class DownloadNotificationNavigationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun notificationContentActionOpensDownloads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = context.getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE)
        val hadSetup = settings.contains("first_run_complete")
        val previous = settings.getBoolean("first_run_complete", false)
        try {
            if (!previous) {
                check(settings.edit().putBoolean("first_run_complete", true).commit())
            }
            val localized = AppLocale.wrap(context)
            val title = localized.getString(R.string.downloads)
            DownloadNotificationFactory.build(localized, "Fixture", 0, 0, false).contentIntent.send()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(title).assertIsDisplayed()
        } finally {
            val editor = settings.edit()
            if (hadSetup) editor.putBoolean("first_run_complete", previous)
            else editor.remove("first_run_complete")
            check(editor.commit())
        }
    }
}

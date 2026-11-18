package com.lowtube.android.ui.main

import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class ProfileSupportedLinksCompatibilityTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun supportedLinksActionUsesTheIntentForThisAndroidVersion() {
        var launchedIntent: Intent? = null
        val context = object : ContextWrapper(composeTestRule.activity) {
            override fun startActivity(intent: Intent) {
                launchedIntent = intent
            }
        }
        composeTestRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalContext provides context) {
                    ProfileTab(
                        accountName = "",
                        accountEmail = "",
                        accountMessage = "",
                        signInBusy = false,
                        onSignIn = {},
                        onSignOut = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.open_link_settings)).performClick()

        val intent = launchedIntent
        assertNotNull(intent)
        val expectedAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        assertEquals(expectedAction, intent?.action)
        assertEquals("package", intent?.data?.scheme)
        assertEquals(composeTestRule.activity.packageName, intent?.data?.schemeSpecificPart)
    }
}

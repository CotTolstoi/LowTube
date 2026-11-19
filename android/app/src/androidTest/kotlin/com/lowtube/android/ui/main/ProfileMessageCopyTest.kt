package com.lowtube.android.ui.main

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ProfileMessageCopyTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun profileCopiesOnlyTheDisplayedAccountMessage() {
        val context = composeTestRule.activity
        val message = "Google authorization did not complete (RESULT_CANCELED). Provider: microG 0.3.18."
        val clipboardRef = AtomicReference<ClipboardManager>()
        val previousClipRef = AtomicReference<android.content.ClipData?>()
        composeTestRule.runOnUiThread {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardRef.set(clipboard)
            previousClipRef.set(clipboard.primaryClip)
        }

        try {
            composeTestRule.setContent {
                MaterialTheme {
                    ProfileTab(
                        accountName = "",
                        accountEmail = "",
                        accountMessage = message,
                        signInBusy = false,
                        onSignIn = {},
                        onSignOut = {},
                    )
                }
            }

            composeTestRule.onNodeWithText(context.getString(R.string.copy_account_message)).performClick()
            composeTestRule.waitForIdle()

            val copiedMessage = AtomicReference<String>()
            composeTestRule.runOnUiThread {
                copiedMessage.set(clipboardRef.get().primaryClip?.getItemAt(0)?.text?.toString())
            }
            assertEquals(message, copiedMessage.get())
        } finally {
            composeTestRule.runOnUiThread {
                val previousClip = previousClipRef.get()
                if (previousClip == null) clipboardRef.get().setPrimaryClip(android.content.ClipData.newPlainText("test", ""))
                else clipboardRef.get().setPrimaryClip(previousClip)
            }
        }
    }
}

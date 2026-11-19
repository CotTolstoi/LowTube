package com.lowtube.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAccountMessageStoreTest {
    @Test
    fun messageSurvivesStoreRecreationAndBlankClearsIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "google-account-message-${System.currentTimeMillis()}.txt"
        val store = GoogleAccountMessageStore(context, fileName)
        val message = "Google authorization did not complete (RESULT_CANCELED). Provider: microG 0.3.18."

        try {
            store.save(message)

            assertEquals(message, GoogleAccountMessageStore(context, fileName).load())
            GoogleAccountMessageStore(context, fileName).save("")
            assertTrue(store.load().isEmpty())
        } finally {
            store.save("")
        }
    }
}

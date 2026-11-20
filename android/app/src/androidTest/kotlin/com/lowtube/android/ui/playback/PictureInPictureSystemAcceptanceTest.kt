package com.lowtube.android.ui.playback

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PictureInPictureSystemAcceptanceTest {
    @Test
    fun eligibleMainActivityEntersSystemPictureInPictureOnApi26AndLater() {
        assumeTrue("System Picture-in-Picture requires Android 8.0/API26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val eligibility = MainActivity::class.java.getDeclaredMethod(
                    "setPictureInPictureEligibility",
                    Boolean::class.javaPrimitiveType,
                )
                eligibility.isAccessible = true
                eligibility.invoke(activity, true)
                val leaveHint = MainActivity::class.java.getDeclaredMethod("onUserLeaveHint")
                leaveHint.isAccessible = true
                leaveHint.invoke(activity)
            }

            scenario.onActivity { activity ->
                assertTrue("Eligible MainActivity should enter the Android system PiP mode", activity.isInPictureInPictureMode)
            }
        }
    }
}

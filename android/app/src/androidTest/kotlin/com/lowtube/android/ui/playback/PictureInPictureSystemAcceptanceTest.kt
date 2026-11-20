package com.lowtube.android.ui.playback

import android.os.Build
import android.app.PictureInPictureParams
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PictureInPictureSystemAcceptanceTest {
    @Test
    fun eligibleShortsUsePortraitSystemPictureInPictureOnApi26AndLater() {
        assumeTrue("System Picture-in-Picture requires Android 8.0/API26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val eligibility = MainActivity::class.java.getDeclaredMethod(
                    "setPictureInPictureEligibility",
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
                eligibility.isAccessible = true
                eligibility.invoke(activity, true, true)
                val paramsMethod = MainActivity::class.java.getDeclaredMethod("pictureInPictureParams")
                paramsMethod.isAccessible = true
                val params = paramsMethod.invoke(activity) as PictureInPictureParams
                val aspectRatio = params.aspectRatio ?: throw AssertionError("PiP aspect ratio is missing")
                assertEquals(9, aspectRatio.numerator)
                assertEquals(16, aspectRatio.denominator)
                val leaveHint = MainActivity::class.java.getDeclaredMethod("onUserLeaveHint")
                leaveHint.isAccessible = true
                leaveHint.invoke(activity)
            }

            scenario.onActivity { activity ->
                assertTrue("Eligible Shorts playback should enter Android's portrait PiP mode", activity.isInPictureInPictureMode)
            }
        }
    }
}

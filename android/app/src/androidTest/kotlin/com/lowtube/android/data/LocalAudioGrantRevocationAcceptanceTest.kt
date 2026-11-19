package com.lowtube.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: select a synthetic Downloads WAV with the system SAF picker first. */
@RunWith(AndroidJUnit4::class)
class LocalAudioGrantRevocationAcceptanceTest {
    @Test
    fun revokedSafGrantIsRejectedAndSavedPlaybackSessionIsCleared() = runBlocking {
        assumeTrue(
            "Pass -e runLocalAudioGrantRevocationAcceptance true after selecting the synthetic SAF fixture",
            InstrumentationRegistry.getArguments().getString("runLocalAudioGrantRevocationAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureTitle = InstrumentationRegistry.getArguments()
            .getString("localAudioRevocationFixtureTitle") ?: FIXTURE_TITLE
        val keepFixtureAfterRevoke = InstrumentationRegistry.getArguments()
            .getString("keepLocalAudioFixtureAfterRevoke") == "true"
        val library = LocalLibraryRepository(context)
        val track = library.localAudioTracks().singleOrNull { it.title == fixtureTitle }
            ?: throw AssertionError("The selected SAF fixture '$fixtureTitle' was not in the local audio library.")
        val uri = Uri.parse(track.uri)
        val resolver = context.contentResolver
        val sessionPreferences = context.getSharedPreferences("lowtube_playback_session", Context.MODE_PRIVATE)
        val previousSession = sessionPreferences.getString(SESSION_KEY, null)
        val sessionStore = PlaybackSessionStore(context)
        val video = VideoItem(
            id = "local:${track.uri}",
            title = track.title,
            kind = "local-audio",
            url = track.uri,
        )
        try {
            assertTrue("SAF picker grant was not persisted", LocalAudioAvailability.canRead(context, track.uri))
            sessionStore.save(video, null, track, null)
            assertNotNull("A readable SAF audio session should restore before revoke", sessionStore.load())

            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            assertFalse("Playback must reject a revoked SAF read grant", LocalAudioAvailability.canRead(context, track.uri))
            assertNull("A revoked SAF grant must not restore the saved playback session", sessionStore.load())
            assertNull("Rejected playback session must be cleared", sessionPreferences.getString(SESSION_KEY, null))
        } finally {
            if (!keepFixtureAfterRevoke) runCatching { library.removeLocalAudioTrack(track.uri) }
            runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sessionPreferences.edit().apply {
                if (previousSession == null) remove(SESSION_KEY) else putString(SESSION_KEY, previousSession)
            }.commit()
            library.close()
        }
    }

    companion object {
        private const val FIXTURE_TITLE = "LowTube-SAF-Acceptance.wav"
        private const val SESSION_KEY = "current_video_v1"
    }
}

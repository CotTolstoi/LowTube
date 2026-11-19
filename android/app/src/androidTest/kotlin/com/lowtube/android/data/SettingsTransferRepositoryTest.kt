package com.lowtube.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTransferRepositoryTest {
    private val defaults = SettingsTransferRepository.Snapshot("en", false, "science and technology", "any", 720, true, true, false)

    @Test
    fun importsPcSettingsButIgnoresCredentials() {
        val imported = SettingsTransferRepository.decode(
            """{"Language":"uk","Theme":"dark","DiscoveryTopic":"space","DiscoveryLanguage":"de","Height":480,"H264":false,"Fps30":false,"DownloadHeight":1080,"DownloadH264":false,"DownloadFps30":true,"ResumePlayback":false,"AutoRecover":false,"AutoNext":false,"PreloadNext":false,"BufferSeconds":120,"BufferMiB":128,"BackBufferMiB":8,"BufferStartSeconds":5,"FeedPreset":2,"PageSize":40,"Auth":"cookies","FirefoxProfile":"private"}""",
            defaults,
        )
        assertEquals("uk", imported.language)
        assertEquals("dark", if (imported.darkTheme) "dark" else "light")
        assertEquals("space", imported.discoveryTopic)
        assertEquals("de", imported.discoveryLanguage)
        assertEquals(480, imported.maxHeight)
        assertFalse(imported.h264Only)
        assertFalse(imported.fps30Only)
        assertEquals(1080, imported.downloadMaxHeight)
        assertFalse(imported.downloadH264Only)
        assertEquals(true, imported.downloadFps30Only)
        assertFalse(imported.resumePlayback)
        assertFalse(imported.autoRecover)
        assertFalse(imported.autoNext)
        assertFalse(imported.preloadNext)
        assertEquals(2, imported.feedPreset)
        assertEquals(120, imported.bufferSeconds)
        assertEquals(128, imported.bufferMiB)
        assertEquals(8, imported.backBufferMiB)
        assertEquals(5, imported.bufferStartSeconds)
        assertEquals(40, imported.pageSize)
    }

    @Test
    fun exportContainsOnlyAllowlistedSettings() {
        val changed = defaults.copy(downloadMaxHeight = 1080, downloadH264Only = false, downloadFps30Only = true, downloadLimitKiB = 512, resumePlayback = false, bufferSeconds = 120, bufferMiB = 128, backBufferMiB = 8, bufferStartSeconds = 5, autoRecover = false, autoNext = false, preloadNext = false, feedPreset = 0, pageSize = 50)
        val json = SettingsTransferRepository.encode(changed)
        assertEquals(changed, SettingsTransferRepository.decode(json, defaults))
        assertTrue(json.contains("ResumePlayback"))
        assertTrue(json.contains("AutoRecover"))
        assertTrue(json.contains("AutoNext"))
        assertTrue(json.contains("PreloadNext"))
        assertTrue(json.contains("FeedPreset"))
        assertTrue(json.contains("PageSize"))
        assertTrue(json.contains("BufferSeconds"))
        assertTrue(json.contains("BufferMiB"))
        assertTrue(json.contains("BackBufferMiB"))
        assertTrue(json.contains("BufferStartSeconds"))
        assertFalse(json.contains("Auth"))
        assertFalse(json.contains("FirefoxProfile"))
        assertFalse(json.contains("Cookie"))
    }

    @Test
    fun legacySettingsFileKeepsCurrentDownloadPreferences() {
        val fallback = defaults.copy(downloadMaxHeight = 1440, downloadH264Only = false, downloadFps30Only = true, resumePlayback = false, preloadNext = false)
        val imported = SettingsTransferRepository.decode("""{"Language":"en","Height":480}""", fallback)

        assertEquals(480, imported.maxHeight)
        assertEquals(1440, imported.downloadMaxHeight)
        assertFalse(imported.downloadH264Only)
        assertEquals(true, imported.downloadFps30Only)
        assertFalse(imported.resumePlayback)
        assertFalse(imported.preloadNext)
        assertEquals(fallback.feedPreset, imported.feedPreset)
        assertEquals(fallback.pageSize, imported.pageSize)
    }

    @Test
    fun downloadLimitUsesPcCompatibleSettingKey() {
        val imported = SettingsTransferRepository.decode("""{"DownloadLimitKiB":2048}""", defaults)
        assertEquals(2048, imported.downloadLimitKiB)
        assertEquals(2048, SettingsTransferRepository.decode(SettingsTransferRepository.encode(imported), defaults).downloadLimitKiB)
    }

    @Test
    fun convertsPcDefaultMemoryLimitToAndroidBoundedDefault() {
        val imported = SettingsTransferRepository.decode("""{"BufferMiB":0}""", defaults)
        assertEquals(PlaybackBufferPreferences.DEFAULT_MEMORY_LIMIT_MIB, imported.bufferMiB)

        val twelveSecondPreset = SettingsTransferRepository.decode("""{"BufferSeconds":12,"BufferMiB":0}""", defaults)
        assertEquals(32, twelveSecondPreset.bufferMiB)

        val twoMinutePreset = SettingsTransferRepository.decode("""{"BufferSeconds":120,"BufferMiB":0}""", defaults)
        assertEquals(128, twoMinutePreset.bufferMiB)
    }

    @Test
    fun rejectsInvalidValuesWithoutReturningPartialSettings() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsTransferRepository.decode("""{"Height":9999}""", defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsTransferRepository.decode("""{"Language":"xx"}""", defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsTransferRepository.decode("""{"Height":720.5}""", defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsTransferRepository.decode("""{"DownloadHeight":9999}""", defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsTransferRepository.decode("""{"BufferSeconds":4}""", defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsTransferRepository.decode("""{"BufferStartSeconds":20,"BufferSeconds":10}""", defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsTransferRepository.decode("""{"FeedPreset":7}""", defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsTransferRepository.decode("""{"PageSize":251}""", defaults)
        }
    }
}

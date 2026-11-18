package com.lowtube.android.data

import org.json.JSONException
import org.json.JSONObject

/** Allowlisted, secret-free transfer for the settings LowTube Android can honor. */
internal object SettingsTransferRepository {
    data class Snapshot(
        val language: String,
        val darkTheme: Boolean,
        val discoveryTopic: String,
        val discoveryLanguage: String,
        val maxHeight: Int,
        val h264Only: Boolean,
        val fps30Only: Boolean,
        val audioOnly: Boolean,
        val downloadMaxHeight: Int = 720,
        val downloadH264Only: Boolean = true,
        val downloadFps30Only: Boolean = false,
        val downloadLimitKiB: Int = 0,
        val resumePlayback: Boolean = true,
        val bufferSeconds: Int = PlaybackBufferPreferences.DEFAULT_MAXIMUM_SECONDS,
        val bufferMiB: Int = PlaybackBufferPreferences.DEFAULT_MEMORY_LIMIT_MIB,
        val backBufferMiB: Int = PlaybackBufferPreferences.DEFAULT_BACK_BUFFER_MIB,
        val bufferStartSeconds: Int = PlaybackBufferPreferences.DEFAULT_STARTUP_SECONDS,
        val autoRecover: Boolean = true,
        val autoNext: Boolean = true,
        val preloadNext: Boolean = true,
        val feedPreset: Int = 1,
    )

    fun encode(snapshot: Snapshot): String {
        validate(snapshot)
        return JSONObject()
            .put("SchemaVersion", 1)
            .put("Language", snapshot.language)
            .put("Theme", if (snapshot.darkTheme) "dark" else "light")
            .put("DiscoveryTopic", snapshot.discoveryTopic)
            .put("DiscoveryLanguage", snapshot.discoveryLanguage)
            .put("Height", snapshot.maxHeight)
            .put("H264", snapshot.h264Only)
            .put("Fps30", snapshot.fps30Only)
            .put("AudioOnly", snapshot.audioOnly)
            .put("DownloadHeight", snapshot.downloadMaxHeight)
            .put("DownloadH264", snapshot.downloadH264Only)
            .put("DownloadFps30", snapshot.downloadFps30Only)
            .put("DownloadLimitKiB", snapshot.downloadLimitKiB)
            .put("ResumePlayback", snapshot.resumePlayback)
            .put("BufferSeconds", snapshot.bufferSeconds)
            .put("BufferMiB", snapshot.bufferMiB)
            .put("BackBufferMiB", snapshot.backBufferMiB)
            .put("BufferStartSeconds", snapshot.bufferStartSeconds)
            .put("AutoRecover", snapshot.autoRecover)
            .put("AutoNext", snapshot.autoNext)
            .put("PreloadNext", snapshot.preloadNext)
            .put("FeedPreset", snapshot.feedPreset)
            .toString(2)
    }

    /** Reads only supported settings. PC credentials, browser paths, and unknown fields are ignored. */
    fun decode(json: String, fallback: Snapshot): Snapshot {
        require(json.length <= MAX_JSON_CHARS) { "Settings file is larger than 64 KiB." }
        val root = try {
            JSONObject(json)
        } catch (failure: JSONException) {
            throw IllegalArgumentException("Settings file is not valid JSON.", failure)
        }
        val bufferSeconds = int(root, "BufferSeconds", "bufferSeconds", fallback.bufferSeconds)
        val importedBufferMiB = int(root, "BufferMiB", "bufferMiB", fallback.bufferMiB)
        val result = Snapshot(
            language = string(root, "Language", "language", fallback.language),
            darkTheme = when (string(root, "Theme", "theme", if (fallback.darkTheme) "dark" else "light")) {
                "dark" -> true
                "light" -> false
                else -> throw IllegalArgumentException("Unsupported theme in settings file.")
            },
            discoveryTopic = string(root, "DiscoveryTopic", "discoveryTopic", fallback.discoveryTopic),
            discoveryLanguage = string(root, "DiscoveryLanguage", "discoveryLanguage", fallback.discoveryLanguage),
            maxHeight = int(root, "Height", "maxHeight", fallback.maxHeight),
            h264Only = bool(root, "H264", "h264Only", fallback.h264Only),
            fps30Only = bool(root, "Fps30", "fps30Only", fallback.fps30Only),
            audioOnly = bool(root, "AudioOnly", "audioOnly", fallback.audioOnly),
            downloadMaxHeight = int(root, "DownloadHeight", "downloadMaxHeight", fallback.downloadMaxHeight),
            downloadH264Only = bool(root, "DownloadH264", "downloadH264Only", fallback.downloadH264Only),
            downloadFps30Only = bool(root, "DownloadFps30", "downloadFps30Only", fallback.downloadFps30Only),
            downloadLimitKiB = int(root, "DownloadLimitKiB", "downloadLimitKiB", fallback.downloadLimitKiB),
            resumePlayback = bool(root, "ResumePlayback", "resumePlayback", fallback.resumePlayback),
            bufferSeconds = bufferSeconds,
            bufferMiB = if (importedBufferMiB == 0) PlaybackBufferPreferences.memoryLimitForPcDefault(bufferSeconds) else importedBufferMiB,
            backBufferMiB = int(root, "BackBufferMiB", "backBufferMiB", fallback.backBufferMiB),
            bufferStartSeconds = int(root, "BufferStartSeconds", "bufferStartSeconds", fallback.bufferStartSeconds),
            autoRecover = bool(root, "AutoRecover", "autoRecover", fallback.autoRecover),
            autoNext = bool(root, "AutoNext", "autoNext", fallback.autoNext),
            preloadNext = bool(root, "PreloadNext", "preloadNext", fallback.preloadNext),
            feedPreset = int(root, "FeedPreset", "feedPreset", fallback.feedPreset),
        )
        validate(result)
        return result
    }

    private fun string(root: JSONObject, pc: String, android: String, fallback: String): String {
        val key = when {
            root.has(pc) -> pc
            root.has(android) -> android
            else -> return fallback
        }
        return root.get(key) as? String ?: throw IllegalArgumentException("Invalid $pc setting.")
    }

    private fun int(root: JSONObject, pc: String, android: String, fallback: Int): Int {
        val key = when {
            root.has(pc) -> pc
            root.has(android) -> android
            else -> return fallback
        }
        val value = root.get(key)
        return when (value) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
                ?: throw IllegalArgumentException("Invalid $pc setting.")
            else -> throw IllegalArgumentException("Invalid $pc setting.")
        }
    }

    private fun bool(root: JSONObject, pc: String, android: String, fallback: Boolean): Boolean {
        val key = when {
            root.has(pc) -> pc
            root.has(android) -> android
            else -> return fallback
        }
        return root.get(key) as? Boolean ?: throw IllegalArgumentException("Invalid $pc setting.")
    }

    private fun validate(snapshot: Snapshot) {
        require(snapshot.language in LANGUAGES) { "Unsupported interface language." }
        require(snapshot.discoveryLanguage in DISCOVERY_LANGUAGES) { "Unsupported discovery language." }
        require(snapshot.discoveryTopic.length <= 200) { "Discovery topic is longer than 200 characters." }
        require(snapshot.maxHeight in PlaybackStreamPreferences.SUPPORTED_HEIGHTS) { "Unsupported playback quality cap." }
        require(snapshot.downloadMaxHeight in PlaybackStreamPreferences.SUPPORTED_HEIGHTS) { "Unsupported download quality cap." }
        require(snapshot.downloadLimitKiB in 0..1_048_576) { "Unsupported download speed limit." }
        require(snapshot.feedPreset in 0..2) { "Unsupported subscription feed refresh preset." }
        PlaybackBufferPreferences(
            maximumSeconds = snapshot.bufferSeconds,
            memoryLimitMiB = snapshot.bufferMiB,
            backBufferMiB = snapshot.backBufferMiB,
            startupSeconds = snapshot.bufferStartSeconds,
        )
    }

    private val LANGUAGES = setOf("en", "uk", "ru")
    private val DISCOVERY_LANGUAGES = setOf("any", "en", "uk", "ru", "pl", "de", "fr", "es", "it", "pt", "ja", "ko")
    private const val MAX_JSON_CHARS = 64 * 1024
}

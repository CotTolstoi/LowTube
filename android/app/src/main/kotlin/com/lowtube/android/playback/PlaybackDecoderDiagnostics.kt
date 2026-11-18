package com.lowtube.android.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener

internal data class VideoDecoderSnapshot(
    val name: String = "",
    val inputFormat: String = "",
    val hardwareStatus: Int = PlaybackDecoderDiagnostics.HARDWARE_STATUS_UNKNOWN,
)

/** Records the decoder Media3 actually initialized. No video ID, URL, or playback history is stored. */
internal object PlaybackDecoderDiagnostics {
    const val HARDWARE_STATUS_UNKNOWN = -1
    const val HARDWARE_STATUS_SOFTWARE = 0
    const val HARDWARE_STATUS_HARDWARE = 1

    fun analyticsListener(context: Context): AnalyticsListener {
        val appContext = context.applicationContext
        return object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                val status = hardwareStatus(
                    Build.VERSION.SDK_INT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) hardwareCodecFlags[decoderName] else null,
                )
                preferences(appContext).edit()
                    .putString(KEY_DECODER_NAME, decoderName.take(MAX_DECODER_NAME_LENGTH))
                    .putInt(KEY_HARDWARE_STATUS, status)
                    .apply()
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                val inputFormat = format.codecs?.takeIf(String::isNotBlank)
                    ?: format.sampleMimeType.orEmpty()
                val boundedFormat = inputFormat.take(MAX_INPUT_FORMAT_LENGTH)
                val storedPreferences = preferences(appContext)
                if (storedPreferences.getString(KEY_INPUT_FORMAT, "") != boundedFormat) {
                    storedPreferences.edit().putString(KEY_INPUT_FORMAT, boundedFormat).apply()
                }
            }
        }
    }

    fun read(preferences: SharedPreferences): VideoDecoderSnapshot = VideoDecoderSnapshot(
        name = preferences.getString(KEY_DECODER_NAME, "").orEmpty(),
        inputFormat = preferences.getString(KEY_INPUT_FORMAT, "").orEmpty(),
        hardwareStatus = preferences.getInt(KEY_HARDWARE_STATUS, HARDWARE_STATUS_UNKNOWN),
    )

    fun clear(context: Context) {
        preferences(context.applicationContext).edit()
            .remove(KEY_DECODER_NAME)
            .remove(KEY_INPUT_FORMAT)
            .remove(KEY_HARDWARE_STATUS)
            .apply()
    }

    internal fun hardwareStatus(androidApi: Int, hardwareAccelerated: Boolean?): Int {
        if (androidApi < Build.VERSION_CODES.Q || hardwareAccelerated == null) return HARDWARE_STATUS_UNKNOWN
        return if (hardwareAccelerated) HARDWARE_STATUS_HARDWARE else HARDWARE_STATUS_SOFTWARE
    }

    private val hardwareCodecFlags: Map<String, Boolean> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.associate { it.name to it.isHardwareAccelerated }
        }.getOrDefault(emptyMap())
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    const val PREFERENCES_NAME = "lowtube_playback_diagnostics"
    private const val KEY_DECODER_NAME = "video_decoder_name"
    private const val KEY_INPUT_FORMAT = "video_input_format"
    private const val KEY_HARDWARE_STATUS = "video_hardware_status"
    private const val MAX_DECODER_NAME_LENGTH = 256
    private const val MAX_INPUT_FORMAT_LENGTH = 128
}

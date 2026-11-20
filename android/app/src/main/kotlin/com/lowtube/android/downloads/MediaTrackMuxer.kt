package com.lowtube.android.downloads

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Losslessly combines supported YouTube video/audio tracks using Android's native muxer. */
internal object MediaTrackMuxer {
    fun merge(videoFile: File, audioFile: File, outputFile: File) {
        if (!videoFile.isFile || !audioFile.isFile || videoFile.length() <= 0L || audioFile.length() <= 0L) {
            throw IOException("A downloaded video or audio track is missing.")
        }
        outputFile.delete()
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        var containerName = "MP4"
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
            val audioTrack = findTrack(audioExtractor, "audio/")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            when {
                videoMime == "video/avc" && audioMime == "audio/mp4a-latm" -> Unit
                videoMime == "video/x-vnd.on2.vp8" && audioMime in setOf("audio/vorbis", "audio/opus") -> {
                    if (audioMime == "audio/opus" && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        throw IOException("Opus WebM muxing requires Android 10 or newer.")
                    }
                    outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                    containerName = "WebM"
                }
                videoMime == "video/x-vnd.on2.vp9" && audioMime in setOf("audio/vorbis", "audio/opus") -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                        throw IOException("VP9 WebM muxing requires Android 7 or newer.")
                    }
                    if (audioMime == "audio/opus" && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        throw IOException("Opus WebM muxing requires Android 10 or newer.")
                    }
                    outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                    containerName = "WebM"
                }
                else -> throw IOException("Unsupported track pair for native muxing: $videoMime + $audioMime.")
            }
            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            val muxVideoTrack = muxer.addTrack(videoFormat)
            val muxAudioTrack = muxer.addTrack(audioFormat)
            val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) videoFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (outputFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 && rotation in setOf(90, 180, 270)) {
                muxer.setOrientationHint(rotation)
            }
            muxer.start()
            started = true
            copySamples(videoExtractor, muxer, muxVideoTrack, videoFormat, containerName)
            copySamples(audioExtractor, muxer, muxAudioTrack, audioFormat, containerName)
            muxer.stop()
            started = false
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Android could not combine these tracks into a $containerName file.", failure)
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            outputFile.delete()
            throw IOException("Android produced an empty $containerName file from the selected tracks.")
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) {
                extractor.selectTrack(index)
                return index
            }
        }
        throw IOException("A downloaded track does not contain a $mimePrefix media stream.")
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        destinationTrack: Int,
        format: MediaFormat,
        containerName: String,
    ) {
        val requestedCapacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(DEFAULT_BUFFER_BYTES)
        } else DEFAULT_BUFFER_BYTES
        val capacity = requestedCapacity.coerceIn(DEFAULT_BUFFER_BYTES, MAX_BUFFER_BYTES)
        val buffer = ByteBuffer.allocateDirect(capacity)
        val info = MediaCodec.BufferInfo()
        var copiedSamples = 0
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            if (size == 0 || size > buffer.capacity()) throw IOException("A media sample exceeds the supported $containerName muxing buffer.")
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(destinationTrack, buffer, info)
            copiedSamples++
            if (!extractor.advance()) break
        }
        if (copiedSamples == 0) throw IOException("A downloaded media track contains no samples.")
    }

    private const val DEFAULT_BUFFER_BYTES = 2 * 1024 * 1024
    private const val MAX_BUFFER_BYTES = 16 * 1024 * 1024
}

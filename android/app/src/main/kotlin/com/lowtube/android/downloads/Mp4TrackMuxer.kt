package com.lowtube.android.downloads

import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Remuxes supported YouTube AVC/AAC tracks into one MP4 without decoding or quality loss. */
internal object Mp4TrackMuxer {
    fun merge(videoFile: File, audioFile: File, outputFile: File) {
        if (!videoFile.isFile || !audioFile.isFile || videoFile.length() <= 0L || audioFile.length() <= 0L) {
            throw IOException("A downloaded video or audio track is missing.")
        }
        outputFile.delete()
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/avc")
            val audioTrack = findTrack(audioExtractor, "audio/mp4a-latm")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxVideoTrack = muxer.addTrack(videoFormat)
            val muxAudioTrack = muxer.addTrack(audioFormat)
            val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) videoFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rotation in setOf(90, 180, 270)) muxer.setOrientationHint(rotation)
            muxer.start()
            started = true
            copySamples(videoExtractor, muxer, muxVideoTrack, videoFormat)
            copySamples(audioExtractor, muxer, muxAudioTrack, audioFormat)
            muxer.stop()
            started = false
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Android could not combine these AVC/AAC tracks into an MP4 file.", failure)
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            outputFile.delete()
            throw IOException("Android produced an empty MP4 file from the selected tracks.")
        }
    }

    private fun findTrack(extractor: MediaExtractor, expectedMime: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime == expectedMime) {
                extractor.selectTrack(index)
                return index
            }
        }
        throw IOException("A downloaded track does not contain the expected $expectedMime media.")
    }

    private fun copySamples(extractor: MediaExtractor, muxer: MediaMuxer, destinationTrack: Int, format: MediaFormat) {
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
            if (size == 0 || size > buffer.capacity()) throw IOException("A media sample exceeds the supported MP4 muxing buffer.")
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

package com.lowtube.android.downloads

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Bounded-memory MediaCodec decode followed by LAME encoding. Temporary PCM stays private. */
internal object AudioMp3Transcoder {
    fun transcode(source: File, destination: File, bitrateKbps: Int, cancelled: () -> Boolean) {
        val pcm = File(source.parentFile, source.name + ".pcm")
        pcm.delete()
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(source.absolutePath) }
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalArgumentException("The selected source has no decodable audio track.")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("Audio codec is unknown.")
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(channels in 1..2) { "MP3 conversion currently supports mono and stereo source tracks." }
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            var spins = 0
            FileOutputStream(pcm).buffered().use { sink ->
                val info = MediaCodec.BufferInfo()
                while (!outputEnded) {
                    if (cancelled()) throw InterruptedException("MP3 conversion cancelled.")
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(TimeUnit.MILLISECONDS.toMicros(20))
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex) ?: throw IllegalStateException("Audio decoder input is unavailable.")
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = codec.dequeueOutputBuffer(info, TimeUnit.MILLISECONDS.toMicros(20))) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            require(channels in 1..2) { "MP3 conversion currently supports mono and stereo source tracks." }
                            if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (++spins > 250 && inputEnded) throw IllegalStateException("Audio decoder stopped before completing the MP3 conversion.")
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            spins = 0
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                writePcm16(sink, outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN), pcmEncoding)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            if (cancelled()) throw InterruptedException("MP3 conversion cancelled.")
            LameMp3Encoder.encodePcmFile(pcm, destination, sampleRate, channels, bitrateKbps)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor?.release()
            pcm.delete()
        }
    }

    private fun writePcm16(output: OutputStream, input: ByteBuffer, encoding: Int) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val converted = ByteBuffer.allocate(input.remaining() / 2).order(ByteOrder.LITTLE_ENDIAN)
                while (input.remaining() >= 4) {
                    val value = input.float.coerceIn(-1f, 1f)
                    converted.putShort((value * if (value < 0f) 32768f else 32767f).toInt().toShort())
                }
                output.write(converted.array(), 0, converted.position())
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val converted = ByteArray(input.remaining() * 2)
                var index = 0
                while (input.hasRemaining()) {
                    val value = ((input.get().toInt() and 0xff) - 128) shl 8
                    converted[index++] = value.toByte()
                    converted[index++] = (value shr 8).toByte()
                }
                output.write(converted)
            }
            else -> {
                val converted = ByteArray(input.remaining())
                input.get(converted)
                output.write(converted)
            }
        }
    }
}

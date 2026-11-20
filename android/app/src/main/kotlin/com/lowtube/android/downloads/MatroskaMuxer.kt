package com.lowtube.android.downloads

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Streaming Matroska remuxer for the codec pairs exposed by the Android download picker. */
internal object MatroskaMuxer {
    fun muxSingle(input: File, output: File, cancelled: () -> Boolean = { false }) {
        requireReadable(input)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val tracks = buildTracks(extractor, extractor.trackCount, input)
            if (tracks.size != 2 || tracks.none { it.type == TRACK_VIDEO } || tracks.none { it.type == TRACK_AUDIO }) {
                throw IOException("The downloaded file must contain one supported video and one supported audio track.")
            }
            write(extractor, tracks, output, cancelled)
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun muxSeparate(videoFile: File, audioFile: File, output: File, cancelled: () -> Boolean = { false }) {
        requireReadable(videoFile)
        requireReadable(audioFile)
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val video = buildTracks(videoExtractor, videoExtractor.trackCount, videoFile).singleOrNull { it.type == TRACK_VIDEO }
                ?: throw IOException("The downloaded video track is missing or unsupported.")
            val audio = buildTracks(audioExtractor, audioExtractor.trackCount, audioFile).singleOrNull { it.type == TRACK_AUDIO }
                ?: throw IOException("The downloaded audio track is missing or unsupported.")
            writeSeparate(videoExtractor, video, audioExtractor, audio, output, cancelled)
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun write(extractor: MediaExtractor, tracks: List<Track>, output: File, cancelled: () -> Boolean) {
        prepareOutput(output)
        val byIndex = tracks.associateBy { it.index }
        val writer = EbmlWriter(output)
        try {
            tracks.forEach { extractor.selectTrack(it.index) }
            val originUs = extractor.sampleTime.coerceAtMost(0L)
            writer.writeDocument(tracks, originUs)
            val buffers = tracks.associate { it.index to sampleBuffer(it.format) }
            var samples = 0
            while (extractor.sampleTrackIndex >= 0) {
                checkCancelled(cancelled)
                val track = byIndex[extractor.sampleTrackIndex]
                    ?: throw IOException("The source file contains an unsupported extra media track.")
                val buffer = buffers.getValue(track.index)
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0 || size > buffer.capacity()) throw IOException("A source media sample exceeds the supported Matroska buffer.")
                buffer.position(0)
                buffer.limit(size)
                writer.writeSample(track, extractor.sampleTime, extractor.sampleFlags, buffer, originUs)
                samples++
                if (!extractor.advance()) break
            }
            if (samples == 0) throw IOException("The source file contains no media samples.")
            writer.finish()
        } catch (failure: Exception) {
            output.delete()
            if (failure is IOException || failure is java.util.concurrent.CancellationException) throw failure
            throw IOException("LowTube could not remux this file into Matroska.", failure)
        } finally {
            writer.close()
        }
        requireOutput(output)
    }

    private fun writeSeparate(
        videoExtractor: MediaExtractor,
        video: Track,
        audioExtractor: MediaExtractor,
        audio: Track,
        output: File,
        cancelled: () -> Boolean,
    ) {
        prepareOutput(output)
        val writer = EbmlWriter(output)
        val videoCursor = SampleCursor(videoExtractor, video)
        val audioCursor = SampleCursor(audioExtractor, audio)
        try {
            val originUs = listOf(videoCursor, audioCursor).filter { it.hasSample }.minOfOrNull { it.timeUs }?.coerceAtMost(0L) ?: 0L
            writer.writeDocument(listOf(video, audio), originUs)
            while (videoCursor.hasSample || audioCursor.hasSample) {
                checkCancelled(cancelled)
                val cursor = when {
                    !audioCursor.hasSample -> videoCursor
                    !videoCursor.hasSample -> audioCursor
                    videoCursor.timeUs <= audioCursor.timeUs -> videoCursor
                    else -> audioCursor
                }
                writer.writeSample(cursor.track, cursor.timeUs, cursor.flags, cursor.buffer, originUs)
                if (!cursor.advance()) Unit
            }
            if (videoCursor.samplesRead == 0 || audioCursor.samplesRead == 0) throw IOException("A downloaded track contains no media samples.")
            writer.finish()
        } catch (failure: Exception) {
            output.delete()
            if (failure is IOException || failure is java.util.concurrent.CancellationException) throw failure
            throw IOException("LowTube could not combine the downloaded tracks into Matroska.", failure)
        } finally {
            writer.close()
        }
        requireOutput(output)
    }

    private fun buildTracks(extractor: MediaExtractor, trackCount: Int, source: File): List<Track> {
        val tracks = mutableListOf<Track>()
        for (index in 0 until trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val type = when {
                mime.startsWith("video/") -> TRACK_VIDEO
                mime.startsWith("audio/") -> TRACK_AUDIO
                else -> continue
            }
            if (tracks.count { it.type == type } > 0) throw IOException("Multiple $mime tracks are not supported in a Matroska download.")
            tracks += createTrack(index, tracks.size + 1, type, format, mime, source)
        }
        if (tracks.size !in 1..2) throw IOException("The downloaded file contains no supported Matroska tracks.")
        return tracks
    }

    private fun createTrack(index: Int, number: Int, type: Int, format: MediaFormat, mime: String, source: File): Track {
        val private = codecPrivate(format) ?: if (mime == "audio/vorbis") webmVorbisCodecPrivate(source) else null
        val codecId = when (mime) {
            "video/avc" -> "V_MPEG4/ISO/AVC"
            "video/x-vnd.on2.vp8" -> "V_VP8"
            "video/x-vnd.on2.vp9" -> "V_VP9"
            "audio/mp4a-latm" -> {
                val config = private ?: throw IOException("AAC track is missing codec configuration data.")
                if (readAudioObjectType(config) != 2) throw IOException("Only AAC-LC can be stored in Matroska without transcoding.")
                "A_AAC"
            }
            "audio/opus" -> {
                if (private == null || !private.startsWithOpusHead()) throw IOException("Opus track is missing its OpusHead codec configuration.")
                "A_OPUS"
            }
            "audio/vorbis" -> {
                if (private == null) throw IOException("Vorbis track is missing codec configuration data.")
                "A_VORBIS"
            }
            else -> throw IOException("The $mime codec is not supported for Matroska remuxing.")
        }
        return Track(number, index, type, mime, codecId, private, format)
    }

    private fun codecPrivate(format: MediaFormat): ByteArray? {
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        return when (mime) {
            "video/avc" -> avcCodecPrivate(format)
            "audio/vorbis" -> vorbisCodecPrivate(format)
            else -> format.byteArray("csd-0")
        }
    }

    private fun avcCodecPrivate(format: MediaFormat): ByteArray {
        val csd0 = format.byteArray("csd-0") ?: throw IOException("AVC track is missing its sequence parameter set.")
        val csd1 = format.byteArray("csd-1")
        val parameterSets = (nalUnits(csd0) + listOfNotNull(csd1?.let(::nalUnits)?.firstOrNull())).filter { it.isNotEmpty() }
        val sps = parameterSets.filter { it[0].toInt() and 0x1f == 7 }
        val pps = parameterSets.filter { it[0].toInt() and 0x1f == 8 }
        val first = sps.firstOrNull() ?: throw IOException("AVC track is missing an SPS NAL unit.")
        if (first.size < 4 || sps.isEmpty() || pps.isEmpty() || sps.size > 31 || pps.size > 255) {
            throw IOException("AVC codec configuration is incomplete for Matroska.")
        }
        val bytes = ByteArrayOutputStream()
        bytes.write(byteArrayOf(1, first[1], first[2], first[3], 0xff.toByte(), (0xe0 or sps.size).toByte()))
        sps.forEach { writeUnsignedShort(bytes, it.size); bytes.write(it) }
        bytes.write(pps.size)
        pps.forEach { writeUnsignedShort(bytes, it.size); bytes.write(it) }
        return bytes.toByteArray()
    }

    private fun nalUnits(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index + 3 < data.size) {
            val length = when {
                data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte() -> 3
                index + 4 <= data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (length > 0) {
                starts += (index + length) to index
                index += length
            } else index++
        }
        if (starts.isEmpty()) return listOf(data.copyOf())
        return starts.mapIndexedNotNull { i, (start, _) ->
            val end = if (i + 1 < starts.size) starts[i + 1].second else data.size
            if (end > start) data.copyOfRange(start, end) else null
        }
    }

    private fun vorbisCodecPrivate(format: MediaFormat): ByteArray? {
        val headers = listOf("csd-0", "csd-1", "csd-2").map { format.byteArray(it) ?: return null }
        if (headers[0].size < 7 || headers[0][0] != 1.toByte() || headers[1].size < 7 || headers[1][0] != 3.toByte() || headers[2].size < 7 || headers[2][0] != 5.toByte()) return null
        val result = ByteArrayOutputStream()
        result.write(2)
        writeXiphLength(result, headers[0].size)
        writeXiphLength(result, headers[1].size)
        headers.forEach(result::write)
        return result.toByteArray()
    }

    /** API 23 exposes only Vorbis identification/setup CSD; recover the complete CodecPrivate from WebM. */
    private fun webmVorbisCodecPrivate(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val fileEnd = input.length()
            val segment = findElement(input, 0L, fileEnd, ID_SEGMENT) ?: return@use null
            val segmentEnd = segment.end ?: fileEnd
            val tracks = findElement(input, segment.dataStart, segmentEnd, ID_TRACKS) ?: return@use null
            var position = tracks.dataStart
            val tracksEnd = tracks.end ?: segmentEnd
            while (position < tracksEnd) {
                val entry = readElement(input, position, tracksEnd) ?: break
                position = entry.end ?: break
                if (entry.id != ID_TRACK_ENTRY) continue
                val entryEnd = position
                var child = entry.dataStart
                var codecId: String? = null
                var codecPrivate: ByteArray? = null
                while (child < entryEnd) {
                    val element = readElement(input, child, entryEnd) ?: break
                    child = element.end ?: break
                    when (element.id) {
                        ID_CODEC_ID -> {
                            val length = element.size.coerceAtMost(32L).toInt()
                            val bytes = ByteArray(length)
                            input.seek(element.dataStart)
                            input.readFully(bytes)
                            codecId = String(bytes, Charsets.US_ASCII)
                        }
                        ID_CODEC_PRIVATE -> {
                            if (element.size <= 64 * 1024) {
                                codecPrivate = ByteArray(element.size.toInt()).also {
                                    input.seek(element.dataStart)
                                    input.readFully(it)
                                }
                            }
                        }
                    }
                }
                if (codecId == "A_VORBIS" && codecPrivate.isValidVorbisPrivate()) return@use codecPrivate
            }
            null
        }
    }.getOrNull()

    private fun findElement(input: RandomAccessFile, start: Long, end: Long, id: Long): EbmlElement? {
        var position = start
        while (position < end) {
            val element = readElement(input, position, end) ?: return null
            if (element.id == id) return element
            position = element.end ?: return if (id == ID_SEGMENT) element else null
        }
        return null
    }

    private fun readElement(input: RandomAccessFile, position: Long, parentEnd: Long): EbmlElement? {
        if (position < 0L || position >= parentEnd) return null
        input.seek(position)
        val idFirst = input.readUnsignedByte()
        val idLength = vintLength(idFirst)
        if (idLength !in 1..4 || position + idLength + 1 > parentEnd) return null
        var id = idFirst.toLong()
        repeat(idLength - 1) { id = (id shl 8) or input.readUnsignedByte().toLong() }
        val sizeFirst = input.readUnsignedByte()
        val sizeLength = vintLength(sizeFirst)
        if (sizeLength !in 1..8 || position + idLength + sizeLength > parentEnd) return null
        var size = (sizeFirst and (0xff ushr sizeLength)).toLong()
        repeat(sizeLength - 1) { size = (size shl 8) or input.readUnsignedByte().toLong() }
        val unknown = size == (1L shl (sizeLength * 7)) - 1L
        val dataStart = input.filePointer
        val end = if (unknown) null else (dataStart + size).takeIf { it >= dataStart && it <= parentEnd }
        if (!unknown && end == null) return null
        return EbmlElement(id, size, dataStart, end)
    }

    private fun vintLength(firstByte: Int): Int {
        for (length in 1..8) if (firstByte and (1 shl (8 - length)) != 0) return length
        return 0
    }

    private fun ByteArray?.isValidVorbisPrivate(): Boolean = this != null && size > 7 && this[0] == 2.toByte()

    private data class EbmlElement(val id: Long, val size: Long, val dataStart: Long, val end: Long?)

    /** Converts MediaExtractor's Annex-B samples to Matroska's AVC length-prefixed access units. */
    private fun annexBNalUnits(buffer: ByteBuffer): List<Pair<Int, Int>>? {
        val limit = buffer.limit()
        val first = findStartCode(buffer, 0, limit) ?: return null
        for (index in 0 until first.first) {
            if (buffer.get(index) != 0.toByte()) return null
        }
        val result = mutableListOf<Pair<Int, Int>>()
        var start = first.first + first.second
        var next = findStartCode(buffer, start, limit)
        while (true) {
            var end = next?.first ?: limit
            while (end > start && buffer.get(end - 1) == 0.toByte()) end--
            if (end <= start) throw IOException("H.264 Annex-B sample contains an empty NAL unit.")
            result += start to (end - start)
            val marker = next ?: break
            start = marker.first + marker.second
            next = findStartCode(buffer, start, limit)
        }
        return result
    }

    private fun findStartCode(buffer: ByteBuffer, from: Int, limit: Int): Pair<Int, Int>? {
        var index = from.coerceAtLeast(0)
        while (index + 2 < limit) {
            if (buffer.get(index) == 0.toByte() && buffer.get(index + 1) == 0.toByte()) {
                if (index + 3 < limit && buffer.get(index + 2) == 0.toByte() && buffer.get(index + 3) == 1.toByte()) return index to 4
                if (buffer.get(index + 2) == 1.toByte()) return index to 3
            }
            index++
        }
        return null
    }

    private fun readAudioObjectType(config: ByteArray): Int {
        if (config.isEmpty()) return 0
        val first = config[0].toInt() and 0xff
        val type = first shr 3
        if (type != 31) return type
        if (config.size < 2) return 0
        return 32 + (((first and 7) shl 3) or ((config[1].toInt() and 0xff) shr 5))
    }

    private fun sampleBuffer(format: MediaFormat): ByteBuffer {
        val requested = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(DEFAULT_SAMPLE_BYTES)
        } else DEFAULT_SAMPLE_BYTES
        return ByteBuffer.allocateDirect(requested.coerceIn(DEFAULT_SAMPLE_BYTES, MAX_SAMPLE_BYTES))
    }

    private fun prepareOutput(output: File) {
        output.parentFile?.let { if (!it.isDirectory && !it.mkdirs() && !it.isDirectory) throw IOException("Could not prepare Matroska output storage.") }
        if (output.exists() && !output.delete()) throw IOException("Could not replace the temporary Matroska output.")
    }

    private fun requireReadable(file: File) {
        if (!file.isFile || file.length() <= 0L) throw IOException("A downloaded media file is missing or empty.")
    }

    private fun requireOutput(file: File) {
        if (!file.isFile || file.length() <= MIN_OUTPUT_BYTES) {
            file.delete()
            throw IOException("Android produced an empty Matroska file from the selected tracks.")
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw java.util.concurrent.CancellationException("Cancelled by user.")
    }

    private fun MediaFormat.byteArray(key: String): ByteArray? {
        if (!containsKey(key)) return null
        val source = runCatching { getByteBuffer(key)?.duplicate() }.getOrNull() ?: return null
        val result = ByteArray(source.remaining())
        source.get(result)
        return result
    }

    private fun ByteArray.startsWithOpusHead(): Boolean = size >= 19 && copyOfRange(0, 8).contentEquals(OPUS_HEAD)

    private data class Track(
        val number: Int,
        val index: Int,
        val type: Int,
        val mime: String,
        val codecId: String,
        val codecPrivate: ByteArray?,
        val format: MediaFormat,
    )

    private class SampleCursor(private val extractor: MediaExtractor, val track: Track) {
        val buffer: ByteBuffer = sampleBuffer(track.format)
        var timeUs: Long = 0L
            private set
        var flags: Int = 0
            private set
        var samplesRead = 0
            private set
        var hasSample = false
            private set

        init {
            extractor.selectTrack(track.index)
            load()
        }

        fun advance(): Boolean {
            if (!hasSample || !extractor.advance()) {
                hasSample = false
                return false
            }
            load()
            return hasSample
        }

        private fun load() {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                hasSample = false
                return
            }
            if (size == 0 || size > buffer.capacity()) throw IOException("A source media sample exceeds the supported Matroska buffer.")
            timeUs = extractor.sampleTime
            flags = extractor.sampleFlags
            buffer.position(0)
            buffer.limit(size)
            samplesRead++
            hasSample = true
        }
    }

    private class EbmlWriter(private val file: File) : AutoCloseable {
        private val stream = BufferedOutputStream(FileOutputStream(file), STREAM_BUFFER_BYTES)
        private val copyBuffer = ByteArray(OUTPUT_COPY_BYTES)
        private var clusterTimeMs = -1L
        private var clusterSizePosition = -1L
        private var clusterPayloadPosition = -1L
        private var segmentSizePosition = -1L
        private var segmentPayloadPosition = -1L
        private var position = 0L
        private var closed = false

        fun writeDocument(tracks: List<Track>, timelineOriginUs: Long) {
            element(ID_EBML, master(
                uint(ID_EBML_VERSION, 1), uint(ID_EBML_READ_VERSION, 1), uint(ID_EBML_MAX_ID_LENGTH, 4),
                uint(ID_EBML_MAX_SIZE_LENGTH, 8), text(ID_EBML_DOCTYPE, "matroska"), uint(ID_EBML_DOCTYPE_VERSION, 4),
                uint(ID_EBML_DOCTYPE_READ_VERSION, 2),
            ))
            writeId(ID_SEGMENT)
            segmentSizePosition = position
            writeRaw(UNKNOWN_SIZE_8)
            segmentPayloadPosition = position
            val info = master(
                uint(ID_TIMESTAMP_SCALE, 1_000_000),
                float(
                    ID_DURATION,
                    tracks.mapNotNull { it.format.longOrNull(MediaFormat.KEY_DURATION) }.maxOrNull()
                        ?.let { (it - timelineOriginUs).toDouble() / 1000.0 },
                ),
                text(ID_MUXING_APP, "LowTube"), text(ID_WRITING_APP, "LowTube Android"),
            )
            val trackData = master(*tracks.map(::trackEntry).toTypedArray())
            val seekHeadSize = elementBytes(ID_SEEK_HEAD, seekHead(0L, 0L)).size.toLong()
            val infoPosition = seekHeadSize
            val tracksPosition = infoPosition + elementBytes(ID_INFO, info).size
            element(ID_SEEK_HEAD, seekHead(infoPosition, tracksPosition.toLong()))
            element(ID_INFO, info)
            element(ID_TRACKS, trackData)
        }

        fun writeSample(track: Track, timeUs: Long, flags: Int, data: ByteBuffer, timelineOriginUs: Long) {
            val normalizedTimeUs = timeUs - timelineOriginUs
            if (normalizedTimeUs < 0L) throw IOException("Media timestamps could not be normalized for Matroska remuxing.")
            val timeMs = normalizedTimeUs / 1000L
            if (clusterTimeMs < 0L || timeMs - clusterTimeMs >= CLUSTER_SPAN_MS) {
                endCluster()
                writeId(ID_CLUSTER)
                clusterSizePosition = position
                writeRaw(UNKNOWN_SIZE_8)
                clusterPayloadPosition = position
                element(ID_CLUSTER_TIMESTAMP, unsignedBytes(timeMs))
                clusterTimeMs = timeMs
            }
            val relative = timeMs - clusterTimeMs
            if (relative !in 0L..Short.MAX_VALUE.toLong()) throw IOException("A media timestamp exceeds the Matroska cluster range.")
            val blockHeader = byteArrayOf(
                (0x80 or track.number).toByte(),
                (relative shr 8).toByte(), relative.toByte(),
                (if (track.type == TRACK_AUDIO || flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) 0x80 else 0).toByte(),
            )
            val nals = if (track.codecId == "V_MPEG4/ISO/AVC") annexBNalUnits(data) else null
            val frameSize = nals?.sumOf { it.second + 4 } ?: data.remaining()
            writeId(ID_SIMPLE_BLOCK)
            writeSize(blockHeader.size.toLong() + frameSize)
            writeRaw(blockHeader)
            if (nals == null) {
                writeBuffer(data)
            } else {
                nals.forEach { (start, length) ->
                    writeRaw(byteArrayOf((length shr 24).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte()))
                    writeBufferRange(data, start, length)
                }
            }
        }

        fun finish() {
            endCluster()
            stream.flush()
            patchSize(segmentSizePosition, segmentPayloadPosition, position)
            segmentSizePosition = -1L
        }

        private fun trackEntry(track: Track): ByteArray {
            val video = if (track.type == TRACK_VIDEO) master(
                uint(ID_PIXEL_WIDTH, track.format.intOrNull(MediaFormat.KEY_WIDTH)?.toLong() ?: throw IOException("Video track width is missing.")),
                uint(ID_PIXEL_HEIGHT, track.format.intOrNull(MediaFormat.KEY_HEIGHT)?.toLong() ?: throw IOException("Video track height is missing.")),
            ) else byteArrayOf()
            val audio = if (track.type == TRACK_AUDIO) {
                val sampleRate = track.format.intOrNull(MediaFormat.KEY_SAMPLE_RATE)?.toDouble() ?: 0.0
                val channels = track.format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.toLong() ?: 0L
                if (sampleRate <= 0.0 || channels <= 0L) throw IOException("Audio track sample rate or channel count is missing.")
                val opusHead = track.codecPrivate?.takeIf { track.codecId == "A_OPUS" }
                val codecDelay = if (opusHead != null) {
                    val preSkip = (opusHead[10].toInt() and 0xff) or ((opusHead[11].toInt() and 0xff) shl 8)
                    uint(ID_CODEC_DELAY, preSkip.toLong() * 1_000_000_000L / OPUS_SAMPLE_RATE) + uint(ID_SEEK_PRE_ROLL, OPUS_SEEK_PREROLL_NS)
                } else byteArrayOf()
                master(float(ID_SAMPLING_FREQUENCY, sampleRate), uint(ID_CHANNELS, channels), codecDelay)
            } else byteArrayOf()
            return elementBytes(ID_TRACK_ENTRY, master(
                uint(ID_TRACK_NUMBER, track.number.toLong()), uint(ID_TRACK_UID, track.number.toLong()),
                uint(ID_TRACK_TYPE, track.type.toLong()), uint(ID_FLAG_LACING, 0), text(ID_CODEC_ID, track.codecId),
                track.codecPrivate?.let { elementBytes(ID_CODEC_PRIVATE, it) } ?: byteArrayOf(), video, audio,
            ))
        }

        private fun seekHead(infoPosition: Long, tracksPosition: Long): ByteArray = master(
            elementBytes(ID_SEEK, master(elementBytes(ID_SEEK_ID, unsignedBytes(ID_INFO)), fixedUIntElement(ID_SEEK_POSITION, infoPosition))),
            elementBytes(ID_SEEK, master(elementBytes(ID_SEEK_ID, unsignedBytes(ID_TRACKS)), fixedUIntElement(ID_SEEK_POSITION, tracksPosition))),
        )

        private fun element(id: Long, payload: ByteArray) {
            writeId(id)
            writeSize(payload.size.toLong())
            writeRaw(payload)
        }

        private fun writeId(id: Long) {
            val bytes = unsignedBytes(id)
            writeRaw(bytes)
        }

        private fun writeSize(size: Long) {
            var length = 1
            while (length < 8 && size >= (1L shl (length * 7)) - 1L) length++
            if (length == 8 && size >= (1L shl 56) - 1L) throw IOException("Matroska element is too large.")
            val bytes = ByteArray(length)
            var remaining = size
            for (index in length - 1 downTo 0) {
                bytes[index] = remaining.toByte()
                remaining = remaining ushr 8
            }
            bytes[0] = (bytes[0].toInt() or (1 shl (8 - length))).toByte()
            writeRaw(bytes)
        }

        private fun endCluster() {
            if (clusterSizePosition < 0L) return
            stream.flush()
            patchSize(clusterSizePosition, clusterPayloadPosition, position)
            clusterSizePosition = -1L
            clusterPayloadPosition = -1L
        }

        private fun patchSize(sizePosition: Long, payloadPosition: Long, endPosition: Long) {
            if (sizePosition < 0L || payloadPosition < 0L) return
            val payloadSize = endPosition - payloadPosition
            if (payloadSize < 0L || payloadSize >= (1L shl 56) - 1L) throw IOException("Matroska cluster is too large.")
            RandomAccessFile(file, "rw").use { random ->
                random.seek(sizePosition)
                random.write(fixedEightByteSize(payloadSize))
            }
        }

        private fun writeRaw(bytes: ByteArray) = writeRaw(bytes, 0, bytes.size)

        private fun writeRaw(bytes: ByteArray, offset: Int, length: Int) {
            stream.write(bytes, offset, length)
            position += length.toLong()
        }

        private fun writeBuffer(buffer: ByteBuffer) {
            val source = buffer.duplicate()
            while (source.hasRemaining()) {
                val count = min(source.remaining(), copyBuffer.size)
                source.get(copyBuffer, 0, count)
                writeRaw(copyBuffer, 0, count)
            }
        }

        private fun writeBufferRange(buffer: ByteBuffer, start: Int, length: Int) {
            val source = buffer.duplicate()
            source.position(start)
            source.limit(start + length)
            writeBuffer(source)
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { stream.close() }
        }
    }

    private fun master(vararg elements: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        elements.forEach(bytes::write)
        return bytes.toByteArray()
    }

    private fun uint(id: Long, value: Long): ByteArray = elementBytes(id, unsignedBytes(value))
    private fun fixedUIntElement(id: Long, value: Long): ByteArray = elementBytes(
        id,
        ByteArray(8) { index -> (value ushr ((7 - index) * 8)).toByte() },
    )
    private fun text(id: Long, value: String): ByteArray = elementBytes(id, value.toByteArray(Charsets.UTF_8))
    private fun float(id: Long, value: Double?): ByteArray {
        if (value == null || value <= 0.0 || value.isNaN() || value.isInfinite()) return byteArrayOf()
        return elementBytes(id, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array())
    }

    private fun elementBytes(id: Long, payload: ByteArray): ByteArray {
        val result = ByteArrayOutputStream(payload.size + 16)
        val writer = HeaderWriter(result)
        writer.element(id, payload)
        return result.toByteArray()
    }

    private class HeaderWriter(private val output: ByteArrayOutputStream) {
        fun element(id: Long, payload: ByteArray) {
            output.write(unsignedBytes(id))
            var length = 1
            while (length < 8 && payload.size.toLong() >= (1L shl (length * 7)) - 1L) length++
            val size = ByteArray(length)
            var remaining = payload.size.toLong()
            for (index in length - 1 downTo 0) {
                size[index] = remaining.toByte()
                remaining = remaining ushr 8
            }
            size[0] = (size[0].toInt() or (1 shl (8 - length))).toByte()
            output.write(size)
            output.write(payload)
        }
    }

    private fun unsignedBytes(value: Long): ByteArray {
        var length = 1
        while (length < 8 && value ushr (length * 8) != 0L) length++
        val result = ByteArray(length)
        for (index in length - 1 downTo 0) result[index] = (value ushr ((length - index - 1) * 8)).toByte()
        return result
    }

    private fun fixedEightByteSize(value: Long): ByteArray {
        val bytes = ByteArray(8)
        var remaining = value
        for (index in 7 downTo 1) {
            bytes[index] = remaining.toByte()
            remaining = remaining ushr 8
        }
        bytes[0] = (remaining.toInt() or 1).toByte()
        return bytes
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    private fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    private fun writeUnsignedShort(stream: ByteArrayOutputStream, value: Int) {
        if (value !in 1..65535) throw IOException("A codec configuration NAL unit has an unsupported size.")
        stream.write((value shr 8) and 0xff)
        stream.write(value and 0xff)
    }

    private fun writeXiphLength(stream: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (remaining >= 255) {
            stream.write(255)
            remaining -= 255
        }
        stream.write(remaining)
    }

    private const val TRACK_VIDEO = 1
    private const val TRACK_AUDIO = 2
    private const val DEFAULT_SAMPLE_BYTES = 2 * 1024 * 1024
    private const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val OUTPUT_COPY_BYTES = 16 * 1024
    private const val CLUSTER_SPAN_MS = 30_000L
    private const val MIN_OUTPUT_BYTES = 128L
    private const val OPUS_SAMPLE_RATE = 48_000L
    private const val OPUS_SEEK_PREROLL_NS = 80_000_000L
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
    private val UNKNOWN_SIZE_8 = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_EBML_VERSION = 0x4286L
    private const val ID_EBML_READ_VERSION = 0x42F7L
    private const val ID_EBML_MAX_ID_LENGTH = 0x42F2L
    private const val ID_EBML_MAX_SIZE_LENGTH = 0x42F3L
    private const val ID_EBML_DOCTYPE = 0x4282L
    private const val ID_EBML_DOCTYPE_VERSION = 0x4287L
    private const val ID_EBML_DOCTYPE_READ_VERSION = 0x4285L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
    private const val ID_DURATION = 0x4489L
    private const val ID_MUXING_APP = 0x4D80L
    private const val ID_WRITING_APP = 0x5741L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_UID = 0x73C5L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_FLAG_LACING = 0x9CL
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXEL_WIDTH = 0xB0L
    private const val ID_PIXEL_HEIGHT = 0xBAL
    private const val ID_AUDIO = 0xE1L
    private const val ID_SAMPLING_FREQUENCY = 0xB5L
    private const val ID_CHANNELS = 0x9FL
    private const val ID_CODEC_DELAY = 0x56AAL
    private const val ID_SEEK_PRE_ROLL = 0x56BBL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMESTAMP = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
}

package com.louay.studiovoice.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Stateful linear resampler for streaming decode (48 kHz target). */
class StreamingResampler(private val from: Int, private val to: Int) {
    private var lastSample = 0f
    private var pos = 0.0 // position in input-sample units of the next output sample
    private var haveLast = false

    fun process(input: FloatArray): FloatArray {
        if (from == to) return input.copyOf()
        if (input.isEmpty()) return FloatArray(0)
        val ratio = from.toDouble() / to
        val out = ArrayList<Float>()
        var p = pos
        while (true) {
            val idx = p.toInt()
            if (idx + 1 >= input.size) break
            val frac = (p - idx).toFloat()
            val a = if (idx == -1) lastSample else input[idx]
            val b = input[idx + 1]
            out.add(a + (b - a) * frac)
            p += ratio
        }
        pos = p - input.size
        lastSample = input[input.size - 1]
        haveLast = true
        return FloatArray(out.size) { out[it] }
    }
}

/** Decode any audio file (or a video's audio track) straight to a 48 kHz mono WAV on disk. */
object StreamingDecode {
    fun decodeToWav(context: Context, uri: Uri, outFile: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1; var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; format = f; break
                }
            }
            require(track >= 0 && format != null) { "لا يوجد مسار صوتي في هذا الملف" }
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val resampler = StreamingResampler(srcRate, AudioIo.TARGET_RATE)
            var total = 0L
            WavWriter(outFile, AudioIo.TARGET_RATE).use { writer ->
                fun emit(mono: FloatArray) {
                    if (mono.isEmpty()) return
                    val r = resampler.process(mono)
                    if (r.isNotEmpty()) { writer.write(r); total += r.size }
                }
                if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                    val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                    val buf = ByteBuffer.allocate(256 * 1024)
                    while (true) {
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) break
                        buf.position(0); buf.limit(size)
                        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = sb.remaining() / channels
                        val mono = FloatArray(frames)
                        for (f in 0 until frames) {
                            var s = 0f
                            for (c in 0 until channels) s += sb.get(f * channels + c) / 32768f
                            mono[f] = s / channels
                        }
                        emit(mono)
                        extractor.advance()
                    }
                } else {
                    val codec = MediaCodec.createDecoderByType(mime)
                    try {
                        codec.configure(format, null, null, 0)
                        codec.start()
                        val info = MediaCodec.BufferInfo()
                        var inputDone = false; var outputDone = false
                        while (!outputDone) {
                            if (!inputDone) {
                                val inIdx = codec.dequeueInputBuffer(10_000)
                                if (inIdx >= 0) {
                                    val buf = codec.getInputBuffer(inIdx)!!
                                    val size = extractor.readSampleData(buf, 0)
                                    if (size < 0) {
                                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        inputDone = true
                                    } else {
                                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                        extractor.advance()
                                    }
                                }
                            }
                            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                            if (outIdx >= 0) {
                                val buf = codec.getOutputBuffer(outIdx)!!
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                val outFmt = codec.outputFormat
                                val outCh = if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                                    outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                                val shorts = ShortArray(info.size / 2)
                                buf.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                                val frames = shorts.size / outCh
                                val mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until outCh) s += shorts[f * outCh + c] / 32768f
                                    mono[f] = s / outCh
                                }
                                emit(mono)
                                codec.releaseOutputBuffer(outIdx, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            }
                        }
                    } finally {
                        try { codec.stop() } catch (_: Exception) {}
                        codec.release()
                    }
                }
            }
            return total
        } finally {
            extractor.release()
        }
    }
}

/** MP3 export via the device's own encoder if one exists (many phones have none). */
object Mp3Export {
    fun encoderAvailable(): Boolean {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals("audio/mp3", true) || it.equals("audio/mpeg", true)
                }
            }
        } catch (_: Exception) { false }
    }

    /** Encode a WAV file to a raw .mp3 elementary stream. Returns false if unsupported/failed. */
    fun encode(wavFile: File, outFile: File): Boolean {
        return try {
            val mime = "audio/mp3"
            val reader = WavReader(wavFile)
            val format = MediaFormat.createAudioFormat(mime, reader.sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val codec = MediaCodec.createEncoderByType(mime)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var pos = 0L; var inputDone = false; var outputDone = false
                val chunk = FloatArray(8192)
                outFile.outputStream().use { fos ->
                    while (!outputDone) {
                        if (!inputDone) {
                            val inIdx = codec.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val buf = codec.getInputBuffer(inIdx)!!
                                buf.clear()
                                val n = reader.read(pos, chunk)
                                if (n <= 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                    for (k in 0 until n) sb.put((chunk[k].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                                    val pts = pos * 1_000_000L / reader.sampleRate
                                    codec.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                                    pos += n
                                }
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                        if (outIdx >= 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            if (info.size > 0) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size); buf.get(bytes)
                                fos.write(bytes)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                true
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
                reader.close()
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** Chunked AAC (.m4a) encoding straight from a WAV file (bounded memory). */
object AacExport {
    fun encode(wavFile: File, outFile: File): Boolean {
        return try {
            val reader = WavReader(wavFile)
            try {
                val pcmInfo = reader
                val format = android.media.MediaFormat.createAudioFormat(
                    android.media.MediaFormat.MIMETYPE_AUDIO_AAC, pcmInfo.sampleRate, 1
                ).apply {
                    setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, 2)
                    setInteger(android.media.MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                }
                val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
                val muxer = android.media.MediaMuxer(outFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    val info = android.media.MediaCodec.BufferInfo()
                    var track = -1; var muxStarted = false
                    var pos = 0L; var inputDone = false; var outputDone = false
                    val chunk = FloatArray(8192)
                    while (!outputDone) {
                        if (!inputDone) {
                            val inIdx = codec.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val buf = codec.getInputBuffer(inIdx)!!
                                buf.clear()
                                val n = reader.read(pos, chunk)
                                if (n <= 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    val sb = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                    for (k in 0 until n) sb.put((chunk[k].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                                    val pts = pos * 1_000_000L / reader.sampleRate
                                    codec.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                                    pos += n
                                }
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                        if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(codec.outputFormat); muxer.start(); muxStarted = true
                        } else if (outIdx >= 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            if (info.size > 0 && muxStarted) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                } finally {
                    try { codec.stop() } catch (_: Exception) {}
                    codec.release()
                    try { muxer.stop() } catch (_: Exception) {}
                    muxer.release()
                }
                true
            } finally { reader.close() }
        } catch (_: Exception) { false }
    }
}

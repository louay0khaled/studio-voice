package com.louay.studiovoice.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Decoded mono audio at a fixed sample rate, samples in [-1, 1]. */
class Pcm(val samples: FloatArray, val sampleRate: Int)

object AudioIo {
    const val TARGET_RATE = 48000

    fun isVideoFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov") ||
            n.endsWith(".3gp") || n.endsWith(".webm")
    }

    /** Decode any audio file — or the audio track of a video — to 48 kHz mono PCM. */
    fun decodeToPcm(context: Context, uri: Uri): Pcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
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
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                // WAV: read samples directly (16-bit PCM assumed, the common case).
                val raw = readAllSamples(extractor)
                val mono = downmix16(raw, channels)
                return Pcm(resample(mono, srcRate, TARGET_RATE), TARGET_RATE)
            }
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val out = ArrayList<Float>(srcRate * 30)
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
                            outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channels
                        // Decoders output 16-bit PCM for AAC/MP3/Vorbis/Opus in practice.
                        val shorts = ShortArray(info.size / 2)
                        buf.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                        var i = 0
                        while (i < shorts.size) {
                            var s = 0f
                            for (c in 0 until outCh) s += shorts[i + c] / 32768f
                            out.add(s / outCh)
                            i += outCh
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                val mono = FloatArray(out.size) { out[it] }
                return Pcm(resample(mono, srcRate, TARGET_RATE), TARGET_RATE)
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun readAllSamples(extractor: MediaExtractor): ShortArray {
        val list = ArrayList<Short>(1_000_000)
        val buf = ByteBuffer.allocate(256 * 1024)
        while (true) {
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            buf.position(0); buf.limit(size)
            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            while (sb.hasRemaining()) list.add(sb.get())
            extractor.advance()
        }
        return ShortArray(list.size) { list[it] }
    }

    private fun downmix16(raw: ShortArray, channels: Int): FloatArray {
        if (channels <= 1) return FloatArray(raw.size) { raw[it] / 32768f }
        val frames = raw.size / channels
        return FloatArray(frames) { f ->
            var s = 0f
            for (c in 0 until channels) s += raw[f * channels + c] / 32768f
            s / channels
        }
    }

    /** Linear-interpolation resampler (adequate for speech; avoids extra deps). */
    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input.copyOf()
        val ratio = from.toDouble() / to
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input[min(idx, input.size - 1)]
            val b = input[min(idx + 1, input.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    fun writeWav(file: File, pcm: Pcm) {
        val dataSize = pcm.samples.size * 2
        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()); header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
            header.putInt(16); header.putShort(1); header.putShort(1)
            header.putInt(pcm.sampleRate); header.putInt(pcm.sampleRate * 2)
            header.putShort(2); header.putShort(16)
            header.put("data".toByteArray()); header.putInt(dataSize)
            fos.write(header.array())
            val chunk = ShortArray(8192)
            var i = 0
            val bytes = ByteBuffer.allocate(8192 * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (i < pcm.samples.size) {
                val n = min(chunk.size, pcm.samples.size - i)
                for (k in 0 until n) {
                    val v = max(-1f, min(1f, pcm.samples[i + k]))
                    chunk[k] = (v * 32767).toInt().toShort()
                }
                bytes.clear(); bytes.asShortBuffer().put(chunk, 0, n)
                fos.write(bytes.array(), 0, n * 2)
                i += n
            }
        }
    }

    fun readWav(file: File): Pcm {
        RandomAccessFile(file, "r").use { raf ->
            val all = ByteArray(raf.length().toInt())
            raf.readFully(all)
            // Find "data" chunk (our own WAVs + standard 44-byte headers).
            var dataOff = 44; var rate = TARGET_RATE
            if (all.size > 44) {
                rate = ByteBuffer.wrap(all, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                var p = 12
                while (p + 8 <= all.size) {
                    val id = String(all, p, 4)
                    val sz = ByteBuffer.wrap(all, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (id == "data") { dataOff = p + 8; break }
                    p += 8 + sz
                }
            }
            val count = (all.size - dataOff) / 2
            val samples = FloatArray(count)
            val sb = ByteBuffer.wrap(all, dataOff, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until count) samples[i] = sb.get(i) / 32768f
            return Pcm(samples, if (rate > 0) rate else TARGET_RATE)
        }
    }

    /** Encode PCM to AAC in an .m4a container (MediaCodec + MediaMuxer). */
    fun writeM4a(file: File, pcm: Pcm) {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, pcm.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, 2) // AAC-LC
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1; var muxStarted = false
            var sampleIdx = 0; var inputDone = false; var outputDone = false
            val frameSamples = 1024
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val remaining = pcm.samples.size - sampleIdx
                        if (remaining <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = min(frameSamples, remaining)
                            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            for (k in 0 until n) {
                                val v = max(-1f, min(1f, pcm.samples[sampleIdx + k]))
                                sb.put((v * 32767).toInt().toShort())
                            }
                            val pts = sampleIdx.toLong() * 1_000_000L / pcm.sampleRate
                            codec.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                            sampleIdx += n
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxStarted = true
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0 && muxStarted) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            if (muxerStarted(muxer)) try { muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }

    private fun muxerStarted(@Suppress("UNUSED_PARAMETER") m: MediaMuxer): Boolean = true
}

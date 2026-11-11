package com.louay.studiovoice.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Streaming 16-bit mono WAV writer (header patched on close). */
class WavWriter(private val file: File, private val sampleRate: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    var sampleCount = 0L
        private set

    init {
        raf.setLength(0)
        raf.write(ByteArray(44)) // placeholder header
    }

    fun write(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        val bytes = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        val sb = bytes.asShortBuffer()
        for (i in 0 until length) {
            val v = max(-1f, min(1f, samples[offset + i]))
            sb.put((v * 32767).toInt().toShort())
        }
        raf.write(bytes.array())
        dataBytes += length * 2L
        sampleCount += length
    }

    override fun close() {
        raf.seek(0)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
        header.putInt(16); header.putShort(1); header.putShort(1)
        header.putInt(sampleRate); header.putInt(sampleRate * 2)
        header.putShort(2); header.putShort(16)
        header.put("data".toByteArray()); header.putInt(dataBytes.toInt())
        raf.write(header.array())
        raf.close()
    }
}

/** Random-access 16-bit mono WAV reader for chunked processing. */
class WavReader(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    val sampleRate: Int
    val sampleCount: Long
    private val dataOffset: Long

    init {
        val head = ByteArray(64)
        raf.readFully(head)
        sampleRate = ByteBuffer.wrap(head, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        var off = 12L; var dataLen = 0L
        raf.seek(12)
        val chunkHead = ByteArray(8)
        while (raf.filePointer + 8 <= raf.length()) {
            raf.readFully(chunkHead)
            val id = String(chunkHead, 0, 4)
            val sz = ByteBuffer.wrap(chunkHead, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            if (id == "data") { dataLen = sz; off = raf.filePointer; break }
            raf.seek(raf.filePointer + sz)
        }
        dataOffset = off
        sampleCount = dataLen / 2
    }

    fun read(startSample: Long, out: FloatArray): Int {
        val available = sampleCount - startSample
        if (available <= 0) return 0
        val n = min(out.size.toLong(), available).toInt()
        raf.seek(dataOffset + startSample * 2)
        val bytes = ByteArray(n * 2)
        raf.readFully(bytes)
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until n) out[i] = sb.get(i) / 32768f
        return n
    }

    /** Frame-RMS noise-floor estimate (10th percentile of 20 ms frames), in dBFS. */
    fun estimateNoiseFloorDb(): Float {
        val frame = 960
        val buf = FloatArray(frame)
        val levels = ArrayList<Float>()
        var pos = 0L
        while (pos < sampleCount) {
            val n = read(pos, buf)
            if (n <= 0) break
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            levels.add((10 * kotlin.math.log10(sum / n + 1e-12)).toFloat())
            pos += frame * 10 // sample every 10th frame to bound cost
        }
        if (levels.isEmpty()) return -60f
        levels.sort()
        return levels[(levels.size * 0.10f).toInt().coerceIn(0, levels.size - 1)]
    }

    fun rmsDb(): Float {
        val buf = FloatArray(16384)
        var sum = 0.0; var count = 0L; var pos = 0L
        while (pos < sampleCount) {
            val n = read(pos, buf)
            if (n <= 0) break
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            count += n; pos += n
        }
        if (count == 0L) return -120f
        return (10 * kotlin.math.log10(sum / count + 1e-12)).toFloat()
    }

    override fun close() = raf.close()
}

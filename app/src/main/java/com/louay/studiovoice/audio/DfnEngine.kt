package com.louay.studiovoice.audio

import android.content.Context
import com.kaleyra.noise_filter.DeepFilterNet
import com.rikorose.deepfilternet.NativeDeepFilterNet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * DeepFilterNet AI denoise (KaleyraVideo AndroidDeepFilterNet 0.0.8) — the
 * «سريع» engine and the app default (owner ear-verdict: best of the lab A/B).
 *
 * Owner-mandated change vs the lab: attenuation is NOT capped at a timid
 * 15 dB. [attenuationDb] comes from the «قوة إزالة الضجة» slider with a
 * genuinely strong range (up to 60 dB).
 *
 * Audio contract (library README): 48 kHz, 16-bit PCM, mono; processFrame()
 * edits a direct ByteBuffer in place; buffer capacity = frameLength bytes.
 */
class KaleyraSession private constructor(private val dfn: DeepFilterNet) : AutoCloseable {
    private val frameBytes = dfn.frameLength.toInt()
    private val frameSamples = frameBytes / 2
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(frameBytes).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }
    private val pending = ArrayList<Float>()

    fun process(chunk: FloatArray): FloatArray {
        for (v in chunk) pending.add(v)
        val out = ArrayList<Float>()
        while (pending.size >= frameSamples) {
            val frame = FloatArray(frameSamples) { pending.removeAt(0) }
            runFrame(frame)
            for (v in frame) out.add(v)
        }
        return FloatArray(out.size) { out[it] }
    }

    fun finish(): FloatArray {
        if (pending.isEmpty()) return FloatArray(0)
        val frame = FloatArray(frameSamples) { i -> if (i < pending.size) pending[i] else 0f }
        val n = pending.size
        pending.clear()
        runFrame(frame)
        return frame.copyOf(n)
    }

    private fun runFrame(frame: FloatArray) {
        buffer.clear()
        val sb = buffer.asShortBuffer()
        for (k in 0 until frameSamples) {
            sb.put(k, (max(-1f, min(1f, frame[k])) * 32767).toInt().toShort())
        }
        buffer.position(0)
        dfn.processFrame(buffer)
        buffer.position(0)
        val rb = buffer.asShortBuffer()
        for (k in 0 until frameSamples) frame[k] = rb.get(k) / 32768f
    }

    override fun close() {
        try { dfn.release() } catch (_: Exception) {}
    }

    companion object {
        suspend fun create(context: Context, attenuationDb: Float): KaleyraSession? {
            return try {
                val dfn = NativeDeepFilterNet(context.applicationContext)
                val loaded = withTimeoutOrNull(30_000) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        dfn.onModelLoaded { cont.resume(true) }
                    }
                }
                if (loaded == true && dfn.frameLength > 0) {
                    // Only effective after load (per library source). Strong range:
                    // the slider maps to 5..60 dB; default is strong, not the lab's 15 dB.
                    dfn.setAttenuationLimit(attenuationDb.coerceIn(0f, 100f))
                    dfn.setPostFilterBeta(0.02f)
                    KaleyraSession(dfn)
                } else {
                    try { dfn.release() } catch (_: Exception) {}
                    null
                }
            } catch (t: Throwable) {
                null
            }
        }
    }
}

package com.louay.studiovoice.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * DPDFNet (Ceva, Apache-2.0) — the «احترافي» heavy engine.
 * Model: dpdfnet2_48khz_hr.onnx bundled in assets (48 kHz), from
 * huggingface.co/Ceva-IP/DPDFNet. Validated offline in Python with the
 * official reference algorithm before this port was written:
 * input SNR 3.52 dB -> 17.86 dB on a synthetic noisy harmonic signal.
 * (dpdfnet8_48khz_hr was also measured: ~2x slower AND lower SNR — not shipped.)
 *
 * Algorithm = the official offline path (onnx_model/infer_dpdfnet_onnx.py):
 * STFT win 960 / hop 480, Vorbis window, center=True (reflect pad at head),
 * streaming (spec, state) inference per frame, ISTFT with envelope
 * normalisation, fixed model delay of 4 hops = 1920 samples dropped at head.
 * Streamed in bounded memory, so 30+ minute files are fine.
 */
class DpdfSession private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val initState: FloatArray,
    private val specName: String,
    private val stateName: String
) : AutoCloseable {

    private var state: FloatArray = initState.copyOf()
    private val win = 960
    private val hop = 480
    private val bins = 481
    private val delay = 1920L
    private val window = FloatArray(win) {
        val h = win / 2.0
        val s = sin(0.5 * PI * (it + 0.5) / h)
        sin(0.5 * PI * s * s).toFloat()
    }

    private val history = ArrayList<Float>()
    private var historyBase = 0L
    private var fedTotal = 0L
    private var nextFrame = 0L

    private val ola = ArrayList<Float>()
    private val olaEnv = ArrayList<Float>()
    private var olaBase = 0L      // stream position of ola[0]
    private var emitted = 0L       // stream samples finalized so far
    private var contentOut = 0L    // content samples returned (post pad+delay drop)

    private fun sampleAt(idx: Long): Float {
        if (idx < 0) {
            // librosa reflect: x[-j] = x[j]
            val j = -idx
            val local = j - historyBase
            return if (local in 0 until history.size) history[local.toInt()] else 0f
        }
        val local = idx - historyBase
        return if (local in 0 until history.size) history[local.toInt()] else 0f
    }

    /** Feed the next chunk (any size). Returns newly available enhanced samples. */
    fun process(chunk: FloatArray): FloatArray {
        for (v in chunk) history.add(v)
        fedTotal += chunk.size
        runFrames()
        return drain(false)
    }

    private fun runFrames() {
        while (nextFrame * hop + hop <= fedTotal) {
            runOneFrame(nextFrame * hop - hop / 2)
            nextFrame++
        }
    }

    private val re = FloatArray(960)
    private val im = FloatArray(960)

    private fun runOneFrame(start: Long) {
        for (i in 0 until win) { re[i] = sampleAt(start + i) * window[i]; im[i] = 0f }
        FftAny.dft(re, im, false)
        val spec = FloatArray(bins * 2)
        for (k in 0 until bins) { spec[k * 2] = re[k]; spec[k * 2 + 1] = im[k] }
        val specTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), longArrayOf(1, 1, bins.toLong(), 2))
        val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(state.size.toLong()))
        try {
            session.run(mapOf(specName to specTensor, stateName to stateTensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val outSpec = (result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                state = (result[1].value as FloatArray).copyOf()
                for (k in 0 until bins) { re[k] = outSpec[k][0]; im[k] = outSpec[k][1] }
                for (k in bins until win) { re[k] = re[win - k]; im[k] = -im[win - k] }
            }
        } finally {
            specTensor.close(); stateTensor.close()
        }
        FftAny.idft(re, im)
        val pos = nextFrame * hop // stream position of this frame
        val local = (pos - olaBase).toInt()
        while (ola.size < local + win) { ola.add(0f); olaEnv.add(0f) }
        for (i in 0 until win) {
            ola[local + i] += re[i] * window[i]
            olaEnv[local + i] += window[i] * window[i]
        }
    }

    private fun drain(flushAll: Boolean): FloatArray {
        val out = ArrayList<Float>()
        // Stream sample s is final once frames covering it have all run:
        // conservatively s < nextFrame*hop - 480.
        val finalStream = if (flushAll) olaBase + ola.size else nextFrame * hop - hop
        while (emitted < finalStream && ola.isNotEmpty()) {
            val env = olaEnv.removeAt(0)
            val num = ola.removeAt(0)
            olaBase++
            val s = emitted++
            val v = if (env > 1e-10f) num / env else 0f
            val contentIdx = s - 480 // drop center pad
            if (contentIdx < 0) continue
            if (contentIdx < delay) continue // drop model delay
            out.add(v); contentOut++
        }
        // Bound history: frames only need samples >= nextFrame*hop - 480.
        val keepFrom = nextFrame * hop - hop
        if (keepFrom - historyBase > 16384) {
            val drop = min((keepFrom - historyBase).toInt(), history.size)
            repeat(drop) { history.removeAt(0) }
            historyBase += drop
        }
        return FloatArray(out.size) { out[it] }
    }

    /** Flush remaining samples at end of file (zero-pads like the reference). */
    fun finish(): FloatArray {
        val pad = FloatArray(win + delay.toInt())
        val a = process(pad)
        val b = drain(true)
        return FloatArray(a.size + b.size) { i -> if (i < a.size) a[i] else b[i - a.size] }
    }

    override fun close() {
        try { session.close() } catch (_: Exception) {}
    }

    companion object {
        fun create(context: Context): DpdfSession? {
            return try {
                val bytes = context.assets.open("dpdfnet2_48khz_hr.onnx").use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val session = env.createSession(bytes, opts)
                val meta = session.metadata.customMetadata
                val stateSize = meta["state_size"]!!.toInt()
                val erbN = meta["erb_norm_state_size"]!!.toInt()
                val specN = meta["spec_norm_state_size"]!!.toInt()
                val init = FloatArray(stateSize)
                val erb = meta["erb_norm_init"]!!.split(",").map { it.toFloat() }
                val specInit = meta["spec_norm_init"]!!.split(",").map { it.toFloat() }
                for (i in 0 until min(erbN, erb.size)) init[i] = erb[i]
                for (i in 0 until min(specN, specInit.size)) init[erbN + i] = specInit[i]
                val inputs = session.inputNames.toList()
                DpdfSession(env, session, init, inputs[0], inputs[1])
            } catch (t: Throwable) {
                null
            }
        }
    }
}

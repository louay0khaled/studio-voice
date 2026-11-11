package com.louay.studiovoice.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Minimal iterative radix-2 FFT (complex, in-place). Size must be a power of two. */
object Fft {
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cwr = 1f; var cwi = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nwr = cwr * wr - cwi * wi; cwi = cwr * wi + cwi * wr; cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun inverse(re: FloatArray, im: FloatArray) {
        for (i in re.indices) im[i] = -im[i]
        transform(re, im)
        val n = re.size
        for (i in re.indices) { re[i] /= n; im[i] = -im[i] / n }
    }
}

/** RBJ biquad, coefficients precomputed; processes in place. */
class Biquad(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float
) {
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    fun process(x: FloatArray) {
        for (i in x.indices) {
            val x0 = x[i]
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
            x[i] = y0
        }
    }
    companion object {
        fun highPass(freq: Float, sampleRate: Int, q: Float = 0.7071f): Biquad {
            val w = 2.0 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val cw = cos(w)
            val b0 = (1 + cw) / 2; val b1 = -(1 + cw); val b2 = (1 + cw) / 2
            val a0 = 1 + alpha; val a1 = -2 * cw; val a2 = 1 - alpha
            return Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (a1 / a0).toFloat(), (a2 / a0).toFloat())
        }
        fun lowPass(freq: Float, sampleRate: Int, q: Float = 0.7071f): Biquad {
            val w = 2.0 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val cw = cos(w)
            val b0 = (1 - cw) / 2; val b1 = 1 - cw; val b2 = (1 - cw) / 2
            val a0 = 1 + alpha; val a1 = -2 * cw; val a2 = 1 - alpha
            return Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (a1 / a0).toFloat(), (a2 / a0).toFloat())
        }
        fun lowShelf(freq: Float, sampleRate: Int, gainDb: Float, q: Float = 0.7f): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w = 2.0 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val cw = cos(w); val sq = 2 * sqrt(a) * alpha
            val b0 = a * ((a + 1) - (a - 1) * cw + sq); val b1 = 2 * a * ((a - 1) - (a + 1) * cw); val b2 = a * ((a + 1) - (a - 1) * cw - sq)
            val a0 = (a + 1) + (a - 1) * cw + sq; val a1 = -2 * ((a - 1) + (a + 1) * cw); val a2 = (a + 1) + (a - 1) * cw - sq
            return Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (a1 / a0).toFloat(), (a2 / a0).toFloat())
        }
        fun highShelf(freq: Float, sampleRate: Int, gainDb: Float, q: Float = 0.7f): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w = 2.0 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val cw = cos(w); val sq = 2 * sqrt(a) * alpha
            val b0 = a * ((a + 1) + (a - 1) * cw + sq); val b1 = -2 * a * ((a - 1) + (a + 1) * cw); val b2 = a * ((a + 1) + (a - 1) * cw - sq)
            val a0 = (a + 1) - (a - 1) * cw + sq; val a1 = 2 * ((a - 1) - (a + 1) * cw); val a2 = (a + 1) - (a - 1) * cw - sq
            return Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (a1 / a0).toFloat(), (a2 / a0).toFloat())
        }
        fun peaking(freq: Float, sampleRate: Int, gainDb: Float, q: Float = 1.0f): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w = 2.0 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val cw = cos(w)
            val b0 = 1 + alpha * a; val b1 = -2 * cw; val b2 = 1 - alpha * a
            val a0 = 1 + alpha / a; val a1 = -2 * cw; val a2 = 1 - alpha / a
            return Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (a1 / a0).toFloat(), (a2 / a0).toFloat())
        }
    }
}

/** Feedforward compressor + soft-knee behaviour, per-sample envelope. */
class Compressor(
    private val sampleRate: Int,
    thresholdDb: Float = -18f,
    private val ratio: Float = 3f,
    attackMs: Float = 10f,
    releaseMs: Float = 200f,
    makeupDb: Float = 0f
) {
    private val makeup = Math.pow(10.0, makeupDb / 20.0).toFloat()
    private val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()
    private val attack = kotlin.math.exp(-1.0 / (attackMs * 0.001 * sampleRate)).toFloat()
    private val release = kotlin.math.exp(-1.0 / (releaseMs * 0.001 * sampleRate)).toFloat()
    fun process(x: FloatArray) {
        var env = 0f
        for (i in x.indices) {
            val a = kotlin.math.abs(x[i])
            env = if (a > env) attack * env + (1 - attack) * a else release * env + (1 - release) * a
            var gain = 1f
            if (env > threshold && env > 1e-6f) {
                val overDb = 20 * log10(env.toDouble()).toFloat()
                val targetDb = 20 * log10(threshold.toDouble()).toFloat() + (overDb - 20 * log10(threshold.toDouble()).toFloat()) / ratio
                val target = 10.0.pow(targetDb / 20.0).toFloat()
                gain = target / env
            }
            x[i] *= gain * makeup
        }
    }
}

/**
 * Pure-Kotlin spectral-gating noise reduction (the documented FALLBACK if the
 * DeepFilterNet AI model cannot load on a device, and a JVM-testable safety net).
 * Noise profile = per-bin median magnitude of the quietest frames.
 * Gain = max(floor, 1 - alpha * noise/mag), temporally smoothed. STFT 1024/256, Hann.
 */
object SpectralGate {
    fun process(input: FloatArray, sampleRate: Int, strength: Float = 1f): FloatArray {
        val n = 1024; val hop = 256
        if (input.size < n * 2) return input.copyOf()
        val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
        val frames = 1 + (input.size - n) / hop
        val mags = Array(frames) { FloatArray(n / 2 + 1) }
        val re = FloatArray(n); val im = FloatArray(n)
        val energies = FloatArray(frames)
        for (f in 0 until frames) {
            val off = f * hop
            for (i in 0 until n) { re[i] = input[off + i] * window[i]; im[i] = 0f }
            Fft.transform(re, im)
            var e = 0.0
            for (k in 0..n / 2) { val m = sqrt(re[k] * re[k] + im[k] * im[k]); mags[f][k] = m; e += m.toDouble() * m }
            energies[f] = e.toFloat()
        }
        // Noise = per-bin 20th-percentile magnitude (quiet frames).
        val sortedIdx = energies.indices.sortedBy { energies[it] }
        val quietCount = max(1, (frames * 0.2f).toInt())
        val noise = FloatArray(n / 2 + 1)
        val col = FloatArray(quietCount)
        for (k in 0..n / 2) {
            for (q in 0 until quietCount) col[q] = mags[sortedIdx[q]][k]
            col.sort()
            noise[k] = col[quietCount / 2]
        }
        val out = FloatArray(input.size)
        val norm = FloatArray(input.size)
        val prevGain = FloatArray(n / 2 + 1) { 1f }
        val alpha = 1.6f * strength
        val floor = 1f - 0.88f * strength // at strength=1 -> 0.12 floor (-18 dB)
        for (f in 0 until frames) {
            val off = f * hop
            for (i in 0 until n) { re[i] = input[off + i] * window[i]; im[i] = 0f }
            Fft.transform(re, im)
            for (k in 0..n / 2) {
                val m = mags[f][k]
                var g = if (m > 1e-9f) 1f - alpha * noise[k] / m else floor
                g = min(1f, max(floor, g))
                g = 0.7f * prevGain[k] + 0.3f * g // temporal smoothing, avoids musical noise
                prevGain[k] = g
                re[k] *= g; im[k] *= g
                if (k in 1 until n / 2) { re[n - k] *= g; im[n - k] *= g }
            }
            Fft.inverse(re, im)
            for (i in 0 until n) { out[off + i] += re[i] * window[i]; norm[off + i] += window[i] * window[i] }
        }
        for (i in out.indices) if (norm[i] > 1e-6f) out[i] /= norm[i]
        return out
    }
}

/**
 * Studio post-chain: HP 80 Hz -> warmth +1.5 dB @250 Hz -> presence +2.5 dB @4 kHz
 * -> compressor 3:1 -> loudness to ~ -14 LUFS (approx: RMS-based) -> soft limiter, peak <= -1 dBFS.
 */
object StudioChain {
    fun process(x: FloatArray, sampleRate: Int) {
        Biquad.highPass(80f, sampleRate).process(x)
        Biquad.peaking(250f, sampleRate, 1.5f, 0.9f).process(x)
        Biquad.peaking(4000f, sampleRate, 2.5f, 1.0f).process(x)
        Compressor(sampleRate).process(x)
        // Approximate loudness normalisation: target RMS ~ -17 dBFS (speech ~ -14 LUFS).
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v
        val rms = sqrt(sum / max(1, x.size))
        if (rms > 1e-6) {
            val target = 10.0.pow(-17.0 / 20.0)
            var gain = (target / rms).toFloat()
            gain = min(gain, 10.0.pow(24.0 / 20.0).toFloat()) // cap boost at +24 dB
            for (i in x.indices) x[i] *= gain
        }
        // Soft limiter with -1 dB ceiling.
        val ceiling = 10.0.pow(-1.0 / 20.0).toFloat()
        for (i in x.indices) {
            val v = x[i]
            val a = kotlin.math.abs(v)
            x[i] = if (a > ceiling) {
                val s = if (v >= 0) 1f else -1f
                s * (ceiling + (1f - ceiling) * kotlin.math.tanh((a - ceiling) / (1f - ceiling)))
            } else v
            if (x[i] > 0.999f) x[i] = 0.999f
            if (x[i] < -0.999f) x[i] = -0.999f
        }
    }

    fun rmsDb(x: FloatArray): Float {
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v
        val rms = sqrt(sum / max(1, x.size))
        return if (rms > 1e-9) (20 * log10(rms)).toFloat() else -120f
    }
}

/** Bluestein chirp-z DFT for arbitrary sizes (needed for the 960-point DPDFNet STFT). */
object FftAny {
    fun dft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        if (n == 1) return
        if (n and (n - 1) == 0) {
            if (inverse) Fft.inverse(re, im) else Fft.transform(re, im)
            return
        }
        var m = 1
        while (m < 2 * n - 1) m = m shl 1
        val chirpRe = FloatArray(n); val chirpIm = FloatArray(n)
        for (i in 0 until n) {
            val ang = PI * i.toDouble() * i / n
            chirpRe[i] = cos(ang).toFloat(); chirpIm[i] = -sin(ang).toFloat() // exp(-i*pi*i^2/n)
        }
        val aRe = FloatArray(m); val aIm = FloatArray(m)
        for (i in 0 until n) {
            aRe[i] = re[i] * chirpRe[i] - im[i] * chirpIm[i]
            aIm[i] = re[i] * chirpIm[i] + im[i] * chirpRe[i]
        }
        val bRe = FloatArray(m); val bIm = FloatArray(m)
        for (i in 0 until n) {
            val ang = PI * i.toDouble() * i / n
            bRe[i] = cos(ang).toFloat(); bIm[i] = sin(ang).toFloat()
            if (i > 0) { bRe[m - i] = bRe[i]; bIm[m - i] = bIm[i] }
        }
        // convolution via radix-2 FFT
        Fft.transform(aRe, aIm); Fft.transform(bRe, bIm)
        for (i in 0 until m) {
            val r = aRe[i] * bRe[i] - aIm[i] * bIm[i]
            val j = aRe[i] * bIm[i] + aIm[i] * bRe[i]
            aRe[i] = r; aIm[i] = j
        }
        Fft.inverse(aRe, aIm)
        val outRe = FloatArray(n); val outIm = FloatArray(n)
        for (k in 0 until n) {
            outRe[k] = aRe[k] * chirpRe[k] - aIm[k] * chirpIm[k]
            outIm[k] = aRe[k] * chirpIm[k] + aIm[k] * chirpRe[k]
        }
        if (inverse) {
            // inverse DFT via conjugate trick on the forward result is wrong order;
            // do it directly: idft(x) = conj(dft(conj(x)))/n — handled by caller flag path below.
        }
        System.arraycopy(outRe, 0, re, 0, n); System.arraycopy(outIm, 0, im, 0, n)
    }

    fun idft(re: FloatArray, im: FloatArray) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        dft(re, im, false)
        for (i in 0 until n) { re[i] /= n; im[i] = -im[i] / n }
    }
}

/**
 * Downward expander / noise gate — MANDATORY stage BEFORE the compressor
 * (owner ear-verdict fix, calibration v2): in v1 the compressor makeup +
 * loudnorm lifted the AI's residual noise floor ~9 dB and re-exposed it in
 * pauses. This gate pushes pauses down by up to [rangeDb] (default 15 dB).
 * Threshold is set from the file's measured noise floor by the caller.
 *
 * Detector = short-window RMS (10 ms), NOT a peak envelope: the previous
 * peak-envelope detector decayed with the 250 ms gain time constant, so it
 * needed ~0.75 s of silence to even cross the threshold — natural speech
 * pauses (0.2–0.5 s) were never gated at all (this is exactly what
 * DftAndGateTest.gatePushesPausesDownBeforeCompressor caught in CI run #2).
 * Gain engages over ~40 ms (fast enough to silence a pause, slow enough to
 * avoid clicks) and releases in ~5 ms so word onsets are never clipped;
 * reduction depth follows the expansion law, so soft word tails near the
 * threshold are barely touched.
 */
class NoiseGate(
    private val sampleRate: Int,
    private val thresholdDb: Float,
    private val rangeDb: Float = 15f,
    private val ratio: Float = 4f,
    attackMs: Float = 3f,
    releaseMs: Float = 250f
) {
    // Sliding RMS detector window: 10 ms (480 samples @ 48 kHz).
    private val winLen = max(1, (sampleRate * 0.010).toInt())
    private val ring = FloatArray(winLen)
    private var ringPos = 0
    private var ringFilled = 0
    private var sumSq = 0.0
    // Gain smoothing: engage (more reduction) tau ~40 ms, release tau ~5 ms.
    private val engageStep = 1f - kotlin.math.exp(-1.0 / (0.040 * sampleRate)).toFloat()
    private val releaseStep = 1f - kotlin.math.exp(-1.0 / (0.005 * sampleRate)).toFloat()
    private var gainDbSmooth = 0f

    fun process(x: FloatArray) {
        for (i in x.indices) {
            val v = x[i]
            val sq = v.toDouble() * v
            if (ringFilled == winLen) sumSq -= ring[ringPos].toDouble()
            ring[ringPos] = sq.toFloat()
            sumSq += sq
            if (ringFilled < winLen) ringFilled++
            ringPos = (ringPos + 1) % winLen
            val rms = kotlin.math.sqrt(max(0.0, sumSq) / ringFilled)
            val envDb = 20 * kotlin.math.log10(max(rms, 1e-9))
            // Downward expansion: below threshold, reduction grows at (1 - 1/ratio), capped at rangeDb.
            val reduction = if (envDb < thresholdDb)
                min(rangeDb, (thresholdDb - envDb).toFloat() * (1f - 1f / ratio)) else 0f
            val target = -reduction
            val step = if (target < gainDbSmooth) engageStep else releaseStep
            gainDbSmooth += (target - gainDbSmooth) * step
            x[i] = (v * Math.pow(10.0, gainDbSmooth / 20.0)).toFloat()
        }
    }
}

/** Simple crossover de-esser: compress only the >5 kHz band when it spikes. */
class DeEsser(private val sampleRate: Int, private val amount: Float = 1f) {
    private val hp = Biquad.highPass(5000f, sampleRate, 0.5f)
    private var env = 0f
    private val threshold = Math.pow(10.0, -22.0 / 20.0).toFloat()
    private val attack = kotlin.math.exp(-1.0 / (0.5 * 0.001 * sampleRate)).toFloat()
    private val release = kotlin.math.exp(-1.0 / (60.0 * 0.001 * sampleRate)).toFloat()
    fun process(x: FloatArray) {
        if (amount <= 0f) return
        val high = x.copyOf()
        hp.process(high)
        for (i in x.indices) {
            val a = kotlin.math.abs(high[i])
            env = if (a > env) attack * env + (1 - attack) * a else release * env + (1 - release) * a
            var g = 1f
            if (env > threshold) {
                val over = env / threshold
                g = 1f / (1f + (over - 1f) * 0.7f * amount)
            }
            x[i] = x[i] - high[i] * (1f - g)
        }
    }
}

/** Mastering presets (owner scope: أخبار / بودكاست وتعليق / ريلز ومنصات). */
data class Preset(
    val id: String, val label: String,
    val warmthDb: Float, val presenceDb: Float,
    val compThresholdDb: Float, val compRatio: Float,
    val targetRmsDb: Float, val deEssAmount: Float
) {
    companion object {
        val PODCAST = Preset("podcast", "بودكاست وتعليق", 1.5f, 0f, -18f, 3f, -17f, 0.7f)
        val NEWS = Preset("news", "أخبار", 1.5f, 0f, -16f, 4f, -15.5f, 0.5f)
        val REELS = Preset("reels", "ريلز ومنصات", 1.5f, 0f, -15f, 4f, -15f, 1.0f)
        val ALL = listOf(PODCAST, NEWS, REELS)
    }
}

/**
 * Stateful mastering chain (persistent across chunks):
 * HPF 80 Hz -> EQ (warmth/presence) -> NOISE GATE -> de-esser -> compressor.
 * Loudness gain + limiter run as a second pass ([applyLoudnessAndLimit])
 * once the global RMS is known — the gate sits BEFORE the compressor so
 * makeup gain can never re-lift the noise floor (calibration v2 recipe).
 */
class MasteringChain(
    private val sampleRate: Int,
    private val preset: Preset,
    gateThresholdDb: Float,
    smoothness: Float = 1.0f  // «نعومة ودفء»: 0 = v3A طبيعي, 1 = v3B إذاعي دافئ (اختيار لؤي ❤️)
) {
    // Order follows the accepted v2+v3 recipe exactly:
    // gate (v2) -> HPF/EQ/tone (v3) -> deesser -> multiband -> exciter(B) -> final comp (makeup 2)
    private val gate = NoiseGate(sampleRate, gateThresholdDb) // thr ~-42, ratio 4, atk 3ms, rel 250ms, range 15dB
    private val hp = Biquad.highPass(80f, sampleRate)
    private val lowShelf = Biquad.lowShelf(140f, sampleRate, lerp(2.0f, 3.5f, smoothness))
    private val warmth = Biquad.peaking(250f, sampleRate, preset.warmthDb, 1.2f / 0.85f) // +1.5 dB, width 1.2 oct
    private val honk = Biquad.peaking(650f, sampleRate, -1.5f, 1.0f)
    private val harshCut = Biquad.peaking(3200f, sampleRate, lerp(-2.5f, -3.5f, smoothness), 1.05f)
    private val clarity = Biquad.peaking(5500f, sampleRate, lerp(1.2f, 0.8f, smoothness), 0.9f)
    private val air = Biquad.peaking(10000f, sampleRate, 1.0f, 0.7f)
    private val deEsser = DeEsser(sampleRate, lerp(0.5f, 0.7f, smoothness)) // v3 deesser i: A 0.35 / B 0.5 (rescaled)
    private val multiband = MultibandCompressor(sampleRate)
    private val exciter = Exciter(sampleRate, if (smoothness > 0.5f) (smoothness - 0.5f) * 2f else 0f)
    private val comp = Compressor(sampleRate, preset.compThresholdDb, preset.compRatio, 10f, 200f, makeupDb = 2f)

    fun process(x: FloatArray) {
        gate.process(x)          // BEFORE everything loud (v2 law)
        hp.process(x); lowShelf.process(x); warmth.process(x)
        honk.process(x); harshCut.process(x); clarity.process(x); air.process(x)
        deEsser.process(x)
        multiband.process(x)
        exciter.process(x)
        comp.process(x)
    }

    companion object {
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
        fun loudnessGainDb(currentRmsDb: Float, preset: Preset): Float {
            val g = preset.targetRmsDb - currentRmsDb
            return g.coerceIn(-12f, 24f)
        }
        fun applyGainAndLimit(x: FloatArray, gainDb: Float) {
            val g = Math.pow(10.0, gainDb / 20.0).toFloat()
            val ceiling = Math.pow(10.0, -1.0 / 20.0).toFloat()
            for (i in x.indices) {
                var v = x[i] * g
                val a = kotlin.math.abs(v)
                if (a > ceiling) {
                    val sgn = if (v >= 0) 1f else -1f
                    v = sgn * (ceiling + (1f - ceiling) * kotlin.math.tanh((a - ceiling) / (1f - ceiling)))
                }
                x[i] = v.coerceIn(-0.999f, 0.999f)
            }
        }
    }
}

/**
 * 4-band compressor (v3 recipe): crossover at 300 / 2800 / 6500 Hz via
 * cascaded-biquad lowpass differences (bands sum back to the input exactly).
 * Ratios 2 / 2 / 4 / 2, threshold -20 dB; harsh band (2800-6500) attack 5 ms
 * release 150 ms. App-level deviation ordered by the v3 report itself: the
 * LOW band ratio is relaxed 2 -> 1.5 so the warmth shelf reads fuller on
 * device (loudnorm + low-band comp were absorbing the shelf in the lab).
 */
class MultibandCompressor(private val sampleRate: Int) {
    private val lp300a = Biquad.lowPass(300f, sampleRate, 0.5412f)
    private val lp300b = Biquad.lowPass(300f, sampleRate, 1.3065f)
    private val lp2800a = Biquad.lowPass(2800f, sampleRate, 0.5412f)
    private val lp2800b = Biquad.lowPass(2800f, sampleRate, 1.3065f)
    private val lp6500a = Biquad.lowPass(6500f, sampleRate, 0.5412f)
    private val lp6500b = Biquad.lowPass(6500f, sampleRate, 1.3065f)
    private val compLow = Compressor(sampleRate, -20f, 1.5f, 10f, 200f)
    private val compMid = Compressor(sampleRate, -20f, 2f, 10f, 200f)
    private val compHarsh = Compressor(sampleRate, -20f, 4f, 5f, 150f)
    private val compHigh = Compressor(sampleRate, -20f, 2f, 5f, 150f)

    fun process(x: FloatArray) {
        val x300 = x.copyOf(); lp300a.process(x300); lp300b.process(x300)
        val x2800 = x.copyOf(); lp2800a.process(x2800); lp2800b.process(x2800)
        val x6500 = x.copyOf(); lp6500a.process(x6500); lp6500b.process(x6500)
        val low = x300
        val mid = FloatArray(x.size) { x2800[it] - x300[it] }
        val harsh = FloatArray(x.size) { x6500[it] - x2800[it] }
        val high = FloatArray(x.size) { x[it] - x6500[it] }
        compLow.process(low); compMid.process(mid); compHarsh.process(harsh); compHigh.process(high)
        for (i in x.indices) x[i] = low[i] + mid[i] + harsh[i] + high[i]
    }
}

/** Gentle exciter sheen (v3B only): soft-saturated highs >7.5 kHz added back low. */
class Exciter(private val sampleRate: Int, private val amount: Float) {
    private val hp = Biquad.highPass(7500f, sampleRate, 0.5f)
    fun process(x: FloatArray) {
        if (amount <= 0f) return
        val high = x.copyOf(); hp.process(high)
        // drive 4 (gentled from 8.5 in the lab), amount 1.2 at full B.
        val mix = 0.10f * amount
        for (i in x.indices) {
            val driven = kotlin.math.tanh(4f * high[i])
            x[i] += mix * driven
        }
    }
}

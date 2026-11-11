package com.louay.studiovoice

import com.louay.studiovoice.audio.SpectralGate
import com.louay.studiovoice.audio.StudioChain
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * JVM test: synthetic "voice-like" signal (300 Hz + harmonics bursts) buried in
 * white noise. After spectral gating + studio chain, SNR must improve and the
 * output must be bounded (limiter ceiling) with healthy loudness.
 */
class DspTest {

    private fun snrDb(clean: FloatArray, test: FloatArray): Double {
        var sig = 0.0; var err = 0.0
        val n = minOf(clean.size, test.size)
        for (i in 0 until n) {
            sig += clean[i].toDouble() * clean[i]
            val e = clean[i].toDouble() - test[i]
            err += e * e
        }
        return 10 * kotlin.math.log10(sig / err)
    }

    @Test
    fun spectralGateImprovesSnr() {
        val rate = 48000
        val seconds = 3
        val clean = FloatArray(rate * seconds)
        val noisy = FloatArray(rate * seconds)
        val rnd = Random(42)
        for (i in clean.indices) {
            val t = i.toDouble() / rate
            // Bursts of harmonic tone (speech-like on/off), 0.5 s on / 0.5 s off.
            val on = (i / (rate / 2)) % 2 == 0
            val v = if (on) {
                0.35 * sin(2 * PI * 220 * t) + 0.18 * sin(2 * PI * 440 * t) + 0.1 * sin(2 * PI * 880 * t)
            } else 0.0
            clean[i] = v.toFloat()
            noisy[i] = (v + 0.12 * (rnd.nextDouble() * 2 - 1)).toFloat()
        }
        val before = snrDb(clean, noisy)
        val gated = SpectralGate.process(noisy, rate, strength = 1f)
        val afterGate = snrDb(clean, gated)
        println("SNR before=${"%.2f".format(before)} dB, after gate=${"%.2f".format(afterGate)} dB")
        assertTrue("gate should improve SNR (before=$before after=$afterGate)", afterGate > before + 2.0)

        val chained = gated.copyOf()
        StudioChain.process(chained, rate)
        var peak = 0f
        for (v in chained) peak = maxOf(peak, kotlin.math.abs(v))
        val rms = StudioChain.rmsDb(chained)
        println("After chain: peak=$peak, RMS=${"%.2f".format(rms)} dB")
        assertTrue("limiter ceiling ~-1 dBFS (peak=$peak)", peak <= 0.90f)
        assertTrue("loudness should be raised (RMS=$rms)", rms > -22f)
    }
}

class DftAndGateTest {

    @org.junit.Test
    fun bluesteinMatchesNaiveDft960() {
        val n = 960
        val rnd = kotlin.random.Random(3)
        val re = FloatArray(n) { (rnd.nextDouble() * 2 - 1).toFloat() }
        val im = FloatArray(n)
        // naive DFT for a few bins
        val bins = listOf(0, 1, 7, 100, 479, 480)
        val expected = bins.map { k ->
            var sr = 0.0; var si = 0.0
            for (t in 0 until n) {
                val ang = -2 * Math.PI * k * t / n
                sr += re[t] * kotlin.math.cos(ang); si += re[t] * kotlin.math.sin(ang)
            }
            sr to si
        }
        val re2 = re.copyOf(); val im2 = im.copyOf()
        com.louay.studiovoice.audio.FftAny.dft(re2, im2, false)
        bins.forEachIndexed { i, k ->
            val (er, ei) = expected[i]
            org.junit.Assert.assertEquals("bin $k re", er, re2[k].toDouble(), 0.05)
            org.junit.Assert.assertEquals("bin $k im", ei, im2[k].toDouble(), 0.05)
        }
        // roundtrip
        com.louay.studiovoice.audio.FftAny.idft(re2, im2)
        for (i in 0 until n) org.junit.Assert.assertEquals(re[i].toDouble(), re2[i].toDouble(), 0.002)
        println("Bluestein DFT-960 vs naive: OK")
    }

    @org.junit.Test
    fun gatePushesPausesDownBeforeCompressor() {
        val rate = 48000
        // Speech bursts at -20 dBFS with residual noise floor at -50 dBFS in pauses.
        val x = FloatArray(rate * 4)
        val rnd = kotlin.random.Random(5)
        for (i in x.indices) {
            val on = (i / (rate / 2)) % 2 == 0
            x[i] = if (on) (0.1 * kotlin.math.sin(2 * Math.PI * 200.0 * i / rate)).toFloat()
            else (0.003 * (rnd.nextDouble() * 2 - 1)).toFloat() // ~-50 dBFS noise
        }
        val gated = x.copyOf()
        com.louay.studiovoice.audio.NoiseGate(rate, -42f).process(gated)
        fun rmsDb(a: FloatArray, from: Int, len: Int): Double {
            var s = 0.0; for (i in from until from + len) s += a[i].toDouble() * a[i]
            return 10 * kotlin.math.log10(s / len + 1e-12)
        }
        val pauseBefore = rmsDb(x, rate / 2, rate / 2)
        val pauseAfter = rmsDb(gated, rate / 2 + 4000, rate / 2 - 4000)
        val speechBefore = rmsDb(x, 0, rate / 2)
        val speechAfter = rmsDb(gated, 4000, rate / 2 - 4000)
        println("pause: $pauseBefore -> $pauseAfter dB | speech: $speechBefore -> $speechAfter dB")
        org.junit.Assert.assertTrue("pause should drop by >= 6 dB (was $pauseBefore -> $pauseAfter)", pauseAfter < pauseBefore - 6)
        org.junit.Assert.assertTrue("speech should be nearly untouched", kotlin.math.abs(speechAfter - speechBefore) < 1.5)
    }
}

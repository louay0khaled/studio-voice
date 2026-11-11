package com.louay.studiovoice.audio

import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.math.min

enum class EngineMode(val label: String) {
    FAST_DFN("سريع — DeepFilterNet"),
    PRO_DPDFNET("احترافي — DPDFNet"),
    DSP_FALLBACK("احتياطي DSP")
}

data class ProcessParams(
    val mode: EngineMode,
    val preset: Preset,
    val blendPercent: Int,       // مزج الأصلي/المحسّن
    val nrStrengthPercent: Int,  // قوة إزالة الضجة
    val smoothnessPercent: Int = 50 // نعومة ودفء

)

data class ProcessResult(
    val originalWav: File,
    val enhancedWav: File,
    val engineLabel: String,
    val requestedMode: EngineMode,
    val durationSec: Double,
    val inputRmsDb: Float,
    val outputRmsDb: Float,
    val gateThresholdDb: Float,
    val attenuationDb: Float
)

private interface ChunkEngine {
    fun process(chunk: FloatArray): FloatArray
    fun finish(): FloatArray
    fun close()
}

private class KaleyraEngine(private val s: KaleyraSession) : ChunkEngine {
    override fun process(chunk: FloatArray) = s.process(chunk)
    override fun finish() = s.finish()
    override fun close() = s.close()
}

private class DpdfEngine(private val s: DpdfSession) : ChunkEngine {
    override fun process(chunk: FloatArray) = s.process(chunk)
    override fun finish() = s.finish()
    override fun close() = s.close()
}

private class DspEngine : ChunkEngine {
    override fun process(chunk: FloatArray) = SpectralGate.process(chunk, AudioIo.TARGET_RATE, 1f)
    override fun finish() = FloatArray(0)
    override fun close() {}
}

/**
 * File-based chunked pipeline (bounded memory — 30+ minute files OK):
 * decode -> [engine denoise -> light 2nd-stage spectral gate -> blend]
 * -> mastering (HPF/EQ -> NOISE GATE -> de-esser -> compressor)
 * -> loudness gain + limiter (2nd pass) -> enhanced.wav
 *
 * The noise gate sits BEFORE the compressor (calibration v2 recipe, owner
 * ear-verdict): makeup/loudness gain can no longer re-lift the residual
 * noise floor in pauses. Engine outputs are delay-compensated inside each
 * engine, so dry/enhanced queues pair sample-for-sample for the blend.
 */
object Processor {
    private const val CHUNK = 48000 * 15

    suspend fun process(
        context: Context,
        sourceUri: Uri,
        params: ProcessParams,
        workDir: File,
        onProgress: (Float, String) -> Unit
    ): ProcessResult {
        workDir.mkdirs()
        val rate = AudioIo.TARGET_RATE
        onProgress(0.02f, "فكّ ترميز الصوت…")
        val originalWav = File(workDir, "original.wav")
        val totalSamples = StreamingDecode.decodeToWav(context, sourceUri, originalWav)
        require(totalSamples > 0) { "الملف فارغ أو غير مدعوم" }

        val reader = WavReader(originalWav)
        val inputRms = reader.rmsDb()
        val noiseFloor = reader.estimateNoiseFloorDb()
        // Gate threshold: ~12 dB above the measured floor, clamped sane.
        val gateThreshold = (noiseFloor + 12f).coerceIn(-46f, -40f) // v2 recipe: ~-42 dB

        // «قوة إزالة الضجة» -> strong attenuation range (NOT the lab's timid 15 dB).
        val attenuationDb = 5f + 0.55f * params.nrStrengthPercent.coerceIn(0, 100)
        val secondStageStrength = 0.15f + 0.004f * params.nrStrengthPercent // 0.15..0.55, light

        onProgress(0.10f, "تحميل محرك إزالة الضجة…")
        var engineLabel: String
        var engine: ChunkEngine
        when (params.mode) {
            EngineMode.PRO_DPDFNET -> {
                val s = DpdfSession.create(context)
                if (s != null) { engine = DpdfEngine(s); engineLabel = "DPDFNet احترافي (ONNX، على الجهاز)" }
                else {
                    val k = KaleyraSession.create(context, attenuationDb)
                    if (k != null) { engine = KaleyraEngine(k); engineLabel = "DeepFilterNet سريع (احتياطي — تعذّر تحميل DPDFNet)" }
                    else { engine = DspEngine(); engineLabel = "DSP احتياطي (تعذّر تحميل نماذج الذكاء)" }
                }
            }
            EngineMode.FAST_DFN, EngineMode.DSP_FALLBACK -> {
                val k = KaleyraSession.create(context, attenuationDb)
                if (k != null) { engine = KaleyraEngine(k); engineLabel = "DeepFilterNet (ذكاء اصطناعي، على الجهاز)" }
                else { engine = DspEngine(); engineLabel = "DSP احتياطي — بوابة طيفية (تعذّر تحميل DeepFilterNet)" }
            }
        }
        val isDsp = engine is DspEngine

        val blend = params.blendPercent.coerceIn(0, 100) / 100f
        val chain = MasteringChain(rate, params.preset, gateThreshold, params.smoothnessPercent.coerceIn(0, 100) / 100f)
        val stage1 = File(workDir, "stage1.wav")
        var stageRmsSum = 0.0
        var written = 0L

        try {
            WavWriter(stage1, rate).use { writer ->
                val dryQ = ArrayList<Float>()
                val enhQ = ArrayList<Float>()
                fun emitPairs() {
                    while (dryQ.isNotEmpty() && enhQ.isNotEmpty() && written < totalSamples) {
                        val take = min(min(dryQ.size, enhQ.size), (totalSamples - written).toInt())
                        val dry = FloatArray(take) { dryQ.removeAt(0) }
                        val enh = FloatArray(take) { enhQ.removeAt(0) }
                        val enh2 = if (isDsp) enh else SpectralGate.process(enh, rate, secondStageStrength)
                        val out = FloatArray(take) { i -> dry[i] * (1f - blend) + enh2[i] * blend }
                        chain.process(out)
                        for (v in out) stageRmsSum += v.toDouble() * v
                        writer.write(out)
                        written += take
                    }
                }
                val buf = FloatArray(CHUNK)
                var pos = 0L
                while (pos < totalSamples) {
                    val n = reader.read(pos, buf)
                    if (n <= 0) break
                    val chunk = buf.copyOf(n)
                    for (v in chunk) dryQ.add(v)
                    val enhOut = engine.process(chunk)
                    for (v in enhOut) enhQ.add(v)
                    pos += n
                    emitPairs()
                    onProgress(0.10f + 0.65f * (pos.toFloat() / totalSamples), "إزالة الضجة + المعالجة…")
                }
                val tail = engine.finish()
                for (v in tail) enhQ.add(v)
                emitPairs()
                // Any remainder (engine produced less): pad with gated silence via chain on dry*0.
                while (written < totalSamples) {
                    val take = min(CHUNK.toLong(), totalSamples - written).toInt()
                    val out = FloatArray(take)
                    chain.process(out)
                    writer.write(out)
                    written += take
                }
            }
        } finally {
            engine.close()
            reader.close()
        }

        val stageRmsDb = if (totalSamples > 0)
            (10 * kotlin.math.log10(stageRmsSum / totalSamples + 1e-12)).toFloat() else -120f
        val gainDb = MasteringChain.loudnessGainDb(stageRmsDb, params.preset)

        onProgress(0.82f, "ضبط مستوى الصوت + المحدّد…")
        val enhancedWav = File(workDir, "enhanced.wav")
        WavReader(stage1).use { s1 ->
            WavWriter(enhancedWav, rate).use { writer ->
                val buf = FloatArray(CHUNK)
                var pos = 0L
                while (pos < totalSamples) {
                    val n = s1.read(pos, buf)
                    if (n <= 0) break
                    val chunk = buf.copyOf(n)
                    MasteringChain.applyGainAndLimit(chunk, gainDb)
                    writer.write(chunk)
                    pos += n
                    onProgress(0.82f + 0.16f * (pos.toFloat() / totalSamples), "ضبط مستوى الصوت + المحدّد…")
                }
            }
        }
        stage1.delete()
        val outRms = WavReader(enhancedWav).use { it.rmsDb() }
        onProgress(1f, "تم")
        return ProcessResult(
            originalWav = originalWav,
            enhancedWav = enhancedWav,
            engineLabel = engineLabel,
            requestedMode = params.mode,
            durationSec = totalSamples.toDouble() / rate,
            inputRmsDb = inputRms,
            outputRmsDb = outRms,
            gateThresholdDb = gateThreshold,
            attenuationDb = attenuationDb
        )
    }
}

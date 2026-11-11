package com.louay.studiovoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.louay.studiovoice.audio.AudioIo
import com.louay.studiovoice.audio.AacExport
import com.louay.studiovoice.audio.EngineMode
import com.louay.studiovoice.audio.Mp3Export
import com.louay.studiovoice.audio.Preset
import com.louay.studiovoice.audio.ProcessParams
import com.louay.studiovoice.audio.ProcessResult
import com.louay.studiovoice.audio.Processor
import com.louay.studiovoice.audio.VideoExport
import com.louay.studiovoice.audio.WavWriter
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * استوديو الصوت — professional offline voice enhancement.
 * Engines: DeepFilterNet (fast, default) / DPDFNet ONNX (pro), with the
 * owner-accepted v2 noise recipe + «نعومة ودفء» tone layer.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusText: TextView
    private lateinit var fileText: TextView
    private lateinit var engineText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var levelBar: ProgressBar
    private lateinit var processBtn: Button
    private lateinit var recordBtn: Button

    private var nrBar: SeekBar? = null
    private var blendBar: SeekBar? = null
    private var smoothBar: SeekBar? = null
    private var selectedMode = EngineMode.FAST_DFN
    private var selectedPreset = Preset.PODCAST
    private val modeButtons = mutableListOf<Button>()
    private val presetButtons = mutableListOf<Button>()

    private var sourceUri: Uri? = null
    private var sourceName: String = ""
    private var sourceIsVideo: Boolean = false
    private var result: ProcessResult? = null
    private var player: MediaPlayer? = null

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var recordFile: File? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            setSource(uri, displayName(uri))
        }
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("يلزم إذن الميكروفون للتسجيل")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        buildUi()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(text: String, primary: Boolean = false): Button = Button(this).apply {
        this.text = text; textSize = 15f
        setTextColor(if (primary) Color.rgb(30, 22, 16) else Color.rgb(245, 230, 211))
        background = GradientDrawable().apply {
            setColor(if (primary) Color.rgb(232, 164, 76) else Color.rgb(62, 50, 41))
            cornerRadius = dp(12).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(5), 0, dp(5)) }
        minHeight = dp(50)
    }

    private fun chip(text: String, selected: Boolean): Button = Button(this).apply {
        this.text = text; textSize = 13f
        setTextColor(if (selected) Color.rgb(30, 22, 16) else Color.rgb(245, 230, 211))
        background = GradientDrawable().apply {
            setColor(if (selected) Color.rgb(232, 164, 76) else Color.rgb(62, 50, 41))
            cornerRadius = dp(20).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
        minHeight = dp(44)
    }

    private fun sliderRow(root: LinearLayout, label: String, default: Int, onChange: (Int) -> Unit): SeekBar {
        val lbl = TextView(this).apply {
            text = "$label: $default%"; textSize = 15f; setTextColor(Color.rgb(245, 230, 211))
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(lbl)
        val bar = SeekBar(this).apply {
            max = 100; progress = default
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    lbl.text = "$label: $p%"; onChange(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(bar)
        return bar
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 19, 16))
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(TextView(this).apply {
            text = "استوديو الصوت 🎙️"; textSize = 26f
            setTextColor(Color.rgb(245, 230, 211)); gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "تحسين صوت الكلام بجودة الاستوديو — يعمل بالكامل بدون إنترنت، بدون حساب"
            textSize = 13f; setTextColor(Color.rgb(196, 174, 148)); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "⚠️ التحسين مخصّص للكلام فقط — الموسيقى قد تضعف أو تتشوّه."
            textSize = 13f; setTextColor(Color.rgb(232, 164, 76)); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.rgb(43, 34, 29)); cornerRadius = dp(14).toFloat() }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        })

        fileText = TextView(this).apply {
            text = "لم يتم اختيار ملف بعد"; textSize = 15f
            setTextColor(Color.rgb(245, 230, 211)); setPadding(0, dp(14), 0, 0)
        }
        root.addView(fileText)
        root.addView(makeButton("📂 استيراد صوت أو فيديو").apply {
            setOnClickListener { picker.launch(arrayOf("audio/*", "video/*")) }
        })
        recordBtn = makeButton("🎤 تسجيل من الميكروفون")
        recordBtn.setOnClickListener {
            if (recording) stopRecording()
            else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        root.addView(recordBtn)
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = View.GONE
        }
        root.addView(levelBar)

        root.addView(TextView(this).apply {
            text = "محرك إزالة الضجة"; textSize = 16f; setTextColor(Color.rgb(245, 230, 211)); setPadding(0, dp(12), 0, 0)
        })
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        EngineMode.values().filter { it != EngineMode.DSP_FALLBACK }.forEach { mode ->
            val b = chip(mode.label, mode == selectedMode)
            b.setOnClickListener {
                selectedMode = mode
                modeButtons.forEachIndexed { i, btn ->
                    val m = EngineMode.values().filter { it != EngineMode.DSP_FALLBACK }[i]
                    styleChip(btn, m == selectedMode)
                }
            }
            modeButtons.add(b); modeRow.addView(b)
        }
        root.addView(modeRow)
        root.addView(TextView(this).apply {
            text = "«سريع» = DeepFilterNet (الأفضل في اختبار الأذن). «احترافي» = DPDFNet الأثقل — أبطأ، لمعالجة الملفات."
            textSize = 12f; setTextColor(Color.rgb(150, 135, 115))
        })

        root.addView(TextView(this).apply {
            text = "إعداد مسبق (Preset)"; textSize = 16f; setTextColor(Color.rgb(245, 230, 211)); setPadding(0, dp(12), 0, 0)
        })
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Preset.ALL.forEach { preset ->
            val b = chip(preset.label, preset == selectedPreset)
            b.setOnClickListener {
                selectedPreset = preset
                presetButtons.forEachIndexed { i, btn -> styleChip(btn, Preset.ALL[i] == selectedPreset) }
            }
            presetButtons.add(b); presetRow.addView(b)
        }
        root.addView(presetRow)

        nrBar = sliderRow(root, "قوة إزالة الضجة", 45) {}
        blendBar = sliderRow(root, "مزج الأصلي / المحسّن", 85) {}
        smoothBar = sliderRow(root, "نعومة ودفء (طبيعي ← إذاعي دافئ)", 100) {}

        processBtn = makeButton("✨ تحسين الصوت", primary = true)
        processBtn.setOnClickListener { runProcessing() }
        root.addView(processBtn)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = View.GONE
        }
        root.addView(progressBar)
        statusText = TextView(this).apply { text = ""; textSize = 14f; setTextColor(Color.rgb(196, 174, 148)) }
        root.addView(statusText)
        engineText = TextView(this).apply { text = ""; textSize = 13f; setTextColor(Color.rgb(150, 190, 150)) }
        root.addView(engineText)

        root.addView(TextView(this).apply {
            text = "المقارنة قبل / بعد"; textSize = 17f; setTextColor(Color.rgb(245, 230, 211)); setPadding(0, dp(14), 0, 0)
        })
        val abRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        abRow.addView(makeButton("▶ الأصلي").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(5), dp(4), dp(5)) }
            setOnClickListener { result?.let { playFile(it.originalWav) } ?: toast("عالج ملفاً أولاً") }
        })
        abRow.addView(makeButton("▶ المحسّن").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(5), dp(4), dp(5)) }
            setOnClickListener { result?.let { playFile(it.enhancedWav) } ?: toast("عالج ملفاً أولاً") }
        })
        root.addView(abRow)
        root.addView(makeButton("⏹ إيقاف التشغيل").apply { setOnClickListener { stopPlayback() } })

        root.addView(TextView(this).apply {
            text = "تصدير"; textSize = 17f; setTextColor(Color.rgb(245, 230, 211)); setPadding(0, dp(14), 0, 0)
        })
        root.addView(makeButton("💾 تصدير WAV").apply {
            setOnClickListener { result?.let { exportAndShare(it.enhancedWav, "audio/wav", "enhanced.wav") } ?: toast("عالج ملفاً أولاً") }
        })
        root.addView(makeButton("💾 تصدير M4A").apply { setOnClickListener { exportM4a() } })
        root.addView(makeButton("💾 تصدير MP3").apply { setOnClickListener { exportMp3() } })
        root.addView(makeButton("🎬 تصدير فيديو MP4 بالصوت المحسّن").apply { setOnClickListener { exportMp4() } })

        setContentView(scroll)
    }

    private fun styleChip(b: Button, selected: Boolean) {
        b.setTextColor(if (selected) Color.rgb(30, 22, 16) else Color.rgb(245, 230, 211))
        (b.background as? GradientDrawable)?.setColor(if (selected) Color.rgb(232, 164, 76) else Color.rgb(62, 50, 41))
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "ملف"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        } catch (_: Exception) {}
        return name
    }

    private fun setSource(uri: Uri, name: String) {
        sourceUri = uri; sourceName = name
        val type = contentResolver.getType(uri) ?: ""
        sourceIsVideo = type.startsWith("video/") || AudioIo.isVideoFile(name)
        fileText.text = "الملف: $name" + if (sourceIsVideo) " (فيديو)" else " (صوت)"
        result = null; engineText.text = ""
        statusText.text = "جاهز للتحسين"
    }

    // ---------- Recording with live level meter (AudioRecord -> WAV 48k) ----------
    private fun startRecording() {
        try {
            val dir = File(cacheDir, "work").apply { mkdirs() }
            recordFile = File(dir, "recording.wav")
            val rate = 48000
            val bufSize = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192)
            @Suppress("MissingPermission")
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            recording = true
            recordBtn.text = "⏹ إيقاف التسجيل"
            levelBar.visibility = View.VISIBLE
            statusText.text = "جارٍ التسجيل… تكلّم الآن"
            val rec = audioRecord!!
            rec.startRecording()
            recordThread = thread(name = "recorder") {
                val writer = WavWriter(recordFile!!, rate)
                val buf = ShortArray(4096)
                try {
                    while (recording) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            val samples = FloatArray(n) { buf[it] / 32768f }
                            writer.write(samples)
                            var peak = 0f
                            for (v in samples) peak = maxOf(peak, kotlin.math.abs(v))
                            val lvl = (peak * 140).toInt().coerceIn(0, 100)
                            runOnUiThread { levelBar.progress = lvl }
                        }
                    }
                } finally {
                    writer.close()
                }
            }
        } catch (e: Exception) {
            recording = false
            toast("فشل بدء التسجيل: ${e.message}")
        }
    }

    private fun stopRecording() {
        recording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        recordThread?.join(1500)
        audioRecord?.release(); audioRecord = null; recordThread = null
        recordBtn.text = "🎤 تسجيل من الميكروفون"
        levelBar.visibility = View.GONE; levelBar.progress = 0
        recordFile?.let { if (it.exists() && it.length() > 44) setSource(Uri.fromFile(it), "تسجيل الميكروفون.wav") }
    }

    // ---------- Processing ----------
    private fun runProcessing() {
        val uri = sourceUri ?: run { toast("اختر ملفاً أو سجّل أولاً"); return }
        val params = ProcessParams(
            mode = selectedMode,
            preset = selectedPreset,
            blendPercent = blendBar?.progress ?: 85,
            nrStrengthPercent = nrBar?.progress ?: 45,
            smoothnessPercent = smoothBar?.progress ?: 100
        )
        processBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        scope.launch {
            try {
                val workDir = File(cacheDir, "work")
                val res = withContext(Dispatchers.Default) {
                    Processor.process(this@MainActivity, uri, params, workDir) { p, msg ->
                        scope.launch {
                            progressBar.progress = (p * 100).toInt()
                            statusText.text = msg
                        }
                    }
                }
                result = res
                progressBar.progress = 100
                statusText.text = "اكتمل التحسين ✔  المدة: ${"%.1f".format(res.durationSec)} ثانية"
                engineText.text = "المحرك: ${res.engineLabel}\n" +
                    "توهين إزالة الضجة: ${"%.0f".format(res.attenuationDb)} dB | عتبة البوابة: ${"%.0f".format(res.gateThresholdDb)} dB\n" +
                    "مستوى الصوت: ${"%.1f".format(res.inputRmsDb)} ← ${"%.1f".format(res.outputRmsDb)} dB"
            } catch (e: Exception) {
                statusText.text = "حدث خطأ: ${e.message}"
                toast("فشل التحسين: ${e.message}")
            } finally {
                processBtn.isEnabled = true
            }
        }
    }

    private fun playFile(file: File) {
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stopPlayback() }
            prepare(); start()
        }
    }

    private fun stopPlayback() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null
    }

    private fun exportsDir(): File = File(getExternalFilesDir(null), "exports").apply { mkdirs() }

    private fun exportAndShare(src: File, mime: String, outName: String) {
        val out = File(exportsDir(), outName)
        src.copyTo(out, overwrite = true)
        shareFile(out, mime)
    }

    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "com.louay.studiovoice.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "مشاركة الملف المحسّن"))
    }

    private fun exportM4a() {
        val res = result ?: run { toast("عالج ملفاً أولاً"); return }
        statusText.text = "جارٍ تصدير M4A…"
        scope.launch {
            val out = File(exportsDir(), "enhanced.m4a")
            val ok = withContext(Dispatchers.Default) { AacExport.encode(res.enhancedWav, out) }
            if (ok) { statusText.text = "تم تصدير M4A ✔"; shareFile(out, "audio/mp4") }
            else toast("فشل تصدير M4A")
        }
    }

    private fun exportMp3() {
        val res = result ?: run { toast("عالج ملفاً أولاً"); return }
        if (!Mp3Export.encoderAvailable()) {
            toast("لا يوجد مشفّر MP3 على هذا الجهاز — صدّر M4A أو WAV (نفس الجودة عملياً)")
            return
        }
        statusText.text = "جارٍ تصدير MP3…"
        scope.launch {
            val out = File(exportsDir(), "enhanced.mp3")
            val ok = withContext(Dispatchers.Default) { Mp3Export.encode(res.enhancedWav, out) }
            if (ok && out.length() > 0) { statusText.text = "تم تصدير MP3 ✔"; shareFile(out, "audio/mpeg") }
            else toast("فشل تصدير MP3 على هذا الجهاز — استخدم M4A")
        }
    }

    private fun exportMp4() {
        val res = result ?: run { toast("عالج ملفاً أولاً"); return }
        val uri = sourceUri ?: run { toast("لا يوجد مصدر"); return }
        if (!sourceIsVideo) { toast("المصدر ليس فيديو — صدّر WAV أو M4A أو MP3"); return }
        statusText.text = "جارٍ تصدير الفيديو… قد يستغرق قليلاً"
        scope.launch {
            try {
                val out = File(exportsDir(), "enhanced_video.mp4")
                withContext(Dispatchers.Default) {
                    VideoExport.exportMp4(this@MainActivity, uri, res.enhancedWav, out)
                }
                statusText.text = "تم تصدير الفيديو ✔"
                shareFile(out, "video/mp4")
            } catch (e: Exception) { toast("فشل تصدير الفيديو: ${e.message}") }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        stopPlayback()
        if (recording) stopRecording()
        scope.cancel()
        super.onDestroy()
    }
}

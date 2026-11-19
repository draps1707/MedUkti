package com.voicerambler.free

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CompareActivity : AppCompatActivity() {

    private lateinit var recorder: WavRecorderHelper
    private var isRecording = false
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compare)

        recorder = WavRecorderHelper(this)
        val btn = findViewById<Button>(R.id.btnCompareRecord)
        val tv = findViewById<TextView>(R.id.tvCompareResults)

        btn.setOnClickListener {
            if (!isRecording) {
                try {
                    recorder.startRecording()
                    isRecording = true
                    btn.text = "Tap to Stop & Compare"
                    tv.text = "Listening..."
                } catch (e: Exception) {
                    tv.text = "Mic error: ${e.message}"
                }
            } else {
                val wav = recorder.stopRecording()
                isRecording = false
                btn.text = "Tap to Record Test Clip"
                if (wav == null) {
                    tv.text = "Recording too short."
                    return@setOnClickListener
                }
                tv.text = "Running engines..."

                uiScope.launch {
                    val out = StringBuilder()

                    val shrutiHi = OfflineAsrEngines.transcribe(this@CompareActivity, AsrEngineType.SHRUTI_HI_EN, wav)
                    out.appendLine("=== Shruti (Hindi/Hinglish/Eng) ===")
                    shrutiHi.onSuccess { raw ->
                        out.appendLine("Raw: $raw")
                        out.appendLine("Medical Layer: ${MedicalNormalizer.normalize(raw)}")
                    }.onFailure { out.appendLine("Error: ${it.message}") }
                    out.appendLine()

                    val shrutiOr = OfflineAsrEngines.transcribe(this@CompareActivity, AsrEngineType.SHRUTI_ODIA, wav)
                    out.appendLine("=== Shruti (Odia) ===")
                    shrutiOr.onSuccess { raw ->
                        out.appendLine("Raw: $raw")
                        out.appendLine("Medical Layer: ${MedicalNormalizer.normalize(raw)}")
                    }.onFailure { out.appendLine("Error: ${it.message}") }
                    out.appendLine()

                    val key = Prefs.getApiKey(this@CompareActivity)
                    if (key.isNotBlank()) {
                        val gem = OfflineAsrEngines.transcribe(this@CompareActivity, AsrEngineType.GEMINI_CLOUD, wav)
                        out.appendLine("=== Optional Gemini Cloud ===")
                        gem.onSuccess { out.appendLine(it) }.onFailure { out.appendLine("Error: ${it.message}") }
                    } else {
                        out.appendLine("=== Optional Gemini Cloud ===")
                        out.appendLine("(Skipped — no API key entered)")
                    }

                    wav.delete()
                    tv.text = out.toString()
                }
            }
        }
    }
}

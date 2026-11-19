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
                tv.text = "Running Hinglish & Odia..."

                uiScope.launch {
                    val out = StringBuilder()

                    val apex = OfflineAsrEngines.transcribe(this@CompareActivity, AsrEngineType.WHISPER_HINGLISH_APEX, wav)
                    out.appendLine("=== Hinglish Apex ===")
                    apex.onSuccess { out.appendLine(it) }.onFailure { out.appendLine("Skipped: ${it.message}") }
                    out.appendLine()

                    val indicOr = OfflineAsrEngines.transcribe(this@CompareActivity, AsrEngineType.INDIC_CONFORMER_OR, wav)
                    out.appendLine("=== Odia (IndicConformer 600M) ===")
                    indicOr.onSuccess { out.appendLine(it) }.onFailure { out.appendLine("Skipped: ${it.message}") }
                    out.appendLine()

                    val key = Prefs.getApiKey(this@CompareActivity)
                    if (key.isNotBlank()) {
                        val gem = OfflineAsrEngines.transcribe(this@CompareActivity, AsrEngineType.GEMINI_CLOUD, wav)
                        out.appendLine("=== Optional Gemini Cloud ===")
                        gem.onSuccess { out.appendLine(it) }.onFailure { out.appendLine("Error: ${it.message}") }
                    }

                    wav.delete()
                    tv.text = out.toString()
                }
            }
        }
    }
}

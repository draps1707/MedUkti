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
                tv.text = "Running installed engines..."

                uiScope.launch {
                    val out = StringBuilder()

                    val tests = listOf(
                        "Dual Pro (Parakeet 0.6B + MedASR)" to AsrEngineType.DUAL_PARAKEET_06B_MEDASR,
                        "Dual Fast (Parakeet 110M + MedASR)" to AsrEngineType.DUAL_PARAKEET_110M_MEDASR,
                        "Google MedASR Only" to AsrEngineType.MEDASR_CTC,
                        "Parakeet 0.6B v2 Only" to AsrEngineType.PARAKEET_06B_V2,
                        "Parakeet 110M Only" to AsrEngineType.PARAKEET_110M,
                        "Odia Dual (IndicConformer + SraVaani)" to AsrEngineType.DUAL_ODIA_INDIC_SRAVAANI,
                        "Odia (IndicConformer 600M)" to AsrEngineType.INDIC_CONFORMER_OR,
                        "Odia Natural (SraVaani 1.0)" to AsrEngineType.SRAVAANI_OR
                    )

                    for ((title, eng) in tests) {
                        val res = OfflineAsrEngines.transcribe(this@CompareActivity, eng, wav)
                        out.appendLine("=== $title ===")
                        res.onSuccess { out.appendLine(it) }.onFailure { out.appendLine("Skipped: ${it.message}") }
                        out.appendLine()
                    }

                    wav.delete()
                    tv.text = out.toString()
                }
            }
        }
    }
}

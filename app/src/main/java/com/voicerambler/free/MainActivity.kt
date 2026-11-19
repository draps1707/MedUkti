package com.voicerambler.free

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val spinnerEngine = findViewById<Spinner>(R.id.spinnerEngine)
        val etPersonalDict = findViewById<EditText>(R.id.etPersonalDict)
        val etHfToken = findViewById<EditText>(R.id.etHfToken)
        val switchAutoListen = findViewById<Switch>(R.id.switchAutoListen)
        val switchStreamingDraft = findViewById<Switch>(R.id.switchStreamingDraft)
        tvStatus = findViewById(R.id.tvStatus)

        spinnerEngine.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, AsrEngineType.entries.map { it.label })
        spinnerEngine.setSelection(AsrEngineType.entries.indexOf(Prefs.getEngine(this)).coerceAtLeast(0))

        switchAutoListen.isChecked = Prefs.isAutoListen(this)
        switchAutoListen.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAutoListen(this, isChecked)
        }
        switchStreamingDraft.isChecked = Prefs.isStreamingDraftEnabled(this)
        switchStreamingDraft.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setStreamingDraftEnabled(this, isChecked)
        }

        etHfToken.setText(Prefs.getHfToken(this))
        etPersonalDict.setText(Prefs.getPersonalDict(this))

        findViewById<Button>(R.id.btnEnableKb).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnPickKb).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btnDlWhisperTurbo).setOnClickListener {
            downloadBatch(
                listOf(
                    ModelDownloader.SILERO_VAD,
                    ModelDownloader.WHISPER_TURBO_TOKENS,
                    ModelDownloader.WHISPER_TURBO_DEC,
                    ModelDownloader.WHISPER_TURBO_ENC
                ),
                "Whisper Large-v3-Turbo"
            )
        }
        findViewById<Button>(R.id.btnDlStreamingDraft).setOnClickListener {
            downloadBatch(
                listOf(
                    ModelDownloader.SILERO_VAD,
                    ModelDownloader.STREAMING_ZIP_TOKENS,
                    ModelDownloader.STREAMING_ZIP_ENC,
                    ModelDownloader.STREAMING_ZIP_DEC,
                    ModelDownloader.STREAMING_ZIP_JOINER
                ),
                "Indian-EN Streaming Draft"
            )
        }
        findViewById<Button>(R.id.btnDlParakeetIndic).setOnClickListener {
            Prefs.setHfToken(this, etHfToken.text.toString())
            downloadBatch(
                listOf(
                    ModelDownloader.SILERO_VAD,
                    ModelDownloader.PARAKEET_INDIC_TOKENS,
                    ModelDownloader.PARAKEET_INDIC_DEC,
                    ModelDownloader.PARAKEET_INDIC_JOINER,
                    ModelDownloader.PARAKEET_INDIC_ENC
                ),
                "Parakeet-Indic v1"
            )
        }
        findViewById<Button>(R.id.btnDlMedAsr).setOnClickListener {
            downloadBatch(
                listOf(ModelDownloader.SILERO_VAD, ModelDownloader.MEDASR_TOKENS, ModelDownloader.MEDASR_MODEL),
                "Google MedASR CTC"
            )
        }
        findViewById<Button>(R.id.btnDlParakeet06b).setOnClickListener {
            downloadBatch(
                listOf(
                    ModelDownloader.SILERO_VAD,
                    ModelDownloader.PARAKEET_06B_TOKENS,
                    ModelDownloader.PARAKEET_06B_ENC,
                    ModelDownloader.PARAKEET_06B_DEC,
                    ModelDownloader.PARAKEET_06B_JOINER
                ),
                "Parakeet TDT 0.6B v2"
            )
        }
        findViewById<Button>(R.id.btnDlIndicOr).setOnClickListener {
            downloadBatch(
                listOf(ModelDownloader.SILERO_VAD, ModelDownloader.INDIC_TOKENS, ModelDownloader.INDIC_OR),
                "Odia Offline Pack"
            )
        }
        findViewById<Button>(R.id.btnDlSraVaani).setOnClickListener {
            downloadBatch(
                listOf(ModelDownloader.SILERO_VAD, ModelDownloader.SRAVAANI_DEC, ModelDownloader.SRAVAANI_ENC),
                "SraVaani 1.0 Odia Natural"
            )
        }
        findViewById<Button>(R.id.btnCompare).setOnClickListener {
            startActivity(Intent(this, CompareActivity::class.java))
        }

        findViewById<Button>(R.id.btnSaveStart).setOnClickListener {
            Prefs.setEngine(this, AsrEngineType.entries[spinnerEngine.selectedItemPosition])
            Prefs.setHfToken(this, etHfToken.text.toString())
            Prefs.setPersonalDict(this, etPersonalDict.text.toString())
            Prefs.setAutoListen(this, switchAutoListen.isChecked)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            updateStatus()
            uiScope.launch { OfflineAsrEngines.preload(this@MainActivity) }
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        }

        ModelDownloader.cleanupUnusedModels(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun downloadBatch(artifacts: List<ModelDownloader.ModelArtifact>, label: String) {
        uiScope.launch {
            for ((idx, art) in artifacts.withIndex()) {
                if (ModelDownloader.isInstalled(this@MainActivity, art)) continue
                tvStatus.text = "Downloading $label (${idx + 1}/${artifacts.size}: ${art.fileName})..."
                val res = ModelDownloader.download(this@MainActivity, art) { p ->
                    tvStatus.text = "Downloading ${art.fileName}: $p%"
                }
                if (res.isFailure) {
                    tvStatus.text = "Error: ${res.exceptionOrNull()?.message}"
                    Toast.makeText(this@MainActivity, "Failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            Toast.makeText(this@MainActivity, "$label ready offline!", Toast.LENGTH_SHORT).show()
            OfflineAsrEngines.preload(this@MainActivity)
            updateStatus()
        }
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun updateStatus() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val imeOk = isImeEnabled()
        val vadOk = ModelDownloader.isInstalled(this, ModelDownloader.SILERO_VAD)
        val turboOk = ModelDownloader.isWhisperTurboInstalled(this)
        val streamOk = ModelDownloader.isStreamingZipformerInstalled(this)
        val indicOk = ModelDownloader.isParakeetIndicInstalled(this)
        val medOk = ModelDownloader.isMedAsrInstalled(this)
        val p06bOk = ModelDownloader.isParakeet06bInstalled(this)
        val odiaOk = ModelDownloader.isOdiaInstalled(this)
        val sravaaniOk = ModelDownloader.isSraVaaniInstalled(this)

        tvStatus.text = buildString {
            appendLine("Active Mode: ${Prefs.getEngine(this@MainActivity).label}")
            appendLine("Keyboard Enabled: ${if (imeOk) "YES" else "Tap '1. Enable Keyboard'"} | Mic: ${if (micOk) "OK" else "Needed"}")
            appendLine("Whisper Large-v3-Turbo: ${if (turboOk) "Installed" else "Not downloaded"}")
            appendLine("Indian-EN Streaming Draft: ${if (streamOk) "Installed" else "Not downloaded"}")
            appendLine("Parakeet-Indic v1: ${if (indicOk) "Installed" else "Not downloaded"}")
            appendLine("Google MedASR CTC: ${if (medOk) "Installed" else "Not downloaded"}")
            appendLine("Parakeet TDT 0.6B v2: ${if (p06bOk) "Installed" else "Not downloaded"}")
            appendLine("Odia (IndicConformer): ${if (odiaOk) "Installed" else "Not downloaded"}")
            appendLine("Odia Natural (SraVaani 1.0): ${if (sravaaniOk) "Installed" else "Not downloaded"}")
            appendLine("Silero VAD: ${if (vadOk) "Installed" else "Not downloaded"}")
        }
    }
}

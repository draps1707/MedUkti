package com.voicerambler.free

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
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

    private var askedThisSession = false
    private lateinit var tvStatus: TextView
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateStatusAndAutoStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val spinnerEngine = findViewById<Spinner>(R.id.spinnerEngine)
        val etPersonalDict = findViewById<EditText>(R.id.etPersonalDict)
        val cbGemini = findViewById<CheckBox>(R.id.cbGeminiOptional)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val spinnerModel = findViewById<Spinner>(R.id.spinnerModel)
        tvStatus = findViewById(R.id.tvStatus)

        spinnerEngine.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, AsrEngineType.entries.map { it.label })
        spinnerEngine.setSelection(AsrEngineType.entries.indexOf(Prefs.getEngine(this)).coerceAtLeast(0))

        etPersonalDict.setText(Prefs.getPersonalDict(this))
        cbGemini.isChecked = Prefs.isGeminiPolishEnabled(this)
        etApiKey.setText(Prefs.getApiKey(this))
        spinnerModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.AVAILABLE_MODELS)
        spinnerModel.setSelection(Prefs.AVAILABLE_MODELS.indexOf(Prefs.getModel(this)).coerceAtLeast(0))

        findViewById<Button>(R.id.btnDlWhisperApex).setOnClickListener {
            downloadBatch(
                listOf(ModelDownloader.SILERO_VAD, ModelDownloader.WHISPER_APEX_TOKENS, ModelDownloader.WHISPER_APEX_ENC, ModelDownloader.WHISPER_APEX_DEC),
                "Hinglish Apex"
            )
        }
        findViewById<Button>(R.id.btnDlIndicOr).setOnClickListener {
            downloadBatch(
                listOf(ModelDownloader.SILERO_VAD, ModelDownloader.INDIC_TOKENS, ModelDownloader.INDIC_OR),
                "Odia Offline Pack"
            )
        }
        findViewById<Button>(R.id.btnCompare).setOnClickListener {
            startActivity(Intent(this, CompareActivity::class.java))
        }

        findViewById<Button>(R.id.btnSaveStart).setOnClickListener {
            Prefs.setEngine(this, AsrEngineType.entries[spinnerEngine.selectedItemPosition])
            Prefs.setPersonalDict(this, etPersonalDict.text.toString())
            Prefs.setGeminiPolishEnabled(this, cbGemini.isChecked)
            Prefs.setApiKey(this, etApiKey.text.toString())
            Prefs.setModel(this, Prefs.AVAILABLE_MODELS[spinnerModel.selectedItemPosition])
            askedThisSession = false
            checkAndRequestNextMissingPermission()
            updateStatusAndAutoStart()
            uiScope.launch { OfflineAsrEngines.preload(this@MainActivity) }
        }

        ModelDownloader.cleanupUnusedModels(this)
        checkAndRequestNextMissingPermission()
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
            updateStatusAndAutoStart()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusAndAutoStart()
    }

    private fun hasMicAndNotif(): Boolean {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return micOk && notifOk
    }

    private fun checkAndRequestNextMissingPermission() {
        if (askedThisSession) return
        askedThisSession = true

        if (!hasMicAndNotif()) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permLauncher.launch(perms.toTypedArray())
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        if (DictationAccessibilityService.instance == null) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun updateStatusAndAutoStart() {
        val micOk = hasMicAndNotif()
        val overlayOk = Settings.canDrawOverlays(this)
        val accOk = DictationAccessibilityService.instance != null
        val vadOk = ModelDownloader.isInstalled(this, ModelDownloader.SILERO_VAD)
        val apexOk = ModelDownloader.isWhisperApexInstalled(this)
        val odiaOk = ModelDownloader.isOdiaInstalled(this)

        tvStatus.text = buildString {
            appendLine("Active Language: ${Prefs.getEngine(this@MainActivity).label}")
            appendLine("Hinglish Apex: ${if (apexOk) "Installed" else "Not downloaded"}")
            appendLine("Odia (IndicConformer): ${if (odiaOk) "Installed" else "Not downloaded"}")
            appendLine("Silero VAD: ${if (vadOk) "Installed" else "Not downloaded"}")
            appendLine("Mic: ${if (micOk) "OK" else "Needed"} | Overlay: ${if (overlayOk) "OK" else "Needed"} | Accessibility: ${if (accOk) "OK" else "Needed"}")
        }

        if (micOk && overlayOk) {
            startForegroundService(Intent(this, FloatingMicService::class.java))
        }
    }
}

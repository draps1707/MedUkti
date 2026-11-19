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
        val cbGemini = findViewById<CheckBox>(R.id.cbGeminiOptional)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val spinnerModel = findViewById<Spinner>(R.id.spinnerModel)
        tvStatus = findViewById(R.id.tvStatus)

        spinnerEngine.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, AsrEngineType.entries.map { it.label })
        spinnerEngine.setSelection(AsrEngineType.entries.indexOf(Prefs.getEngine(this)).coerceAtLeast(0))

        cbGemini.isChecked = Prefs.isGeminiPolishEnabled(this)
        etApiKey.setText(Prefs.getApiKey(this))
        spinnerModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.AVAILABLE_MODELS)
        spinnerModel.setSelection(Prefs.AVAILABLE_MODELS.indexOf(Prefs.getModel(this)).coerceAtLeast(0))

        findViewById<Button>(R.id.btnDlShrutiHi).setOnClickListener {
            downloadPair(ModelDownloader.NEEDLE_BIN, ModelDownloader.SHRUTI_HI_EN, "Shruti Hindi/Hinglish")
        }
        findViewById<Button>(R.id.btnDlShrutiOr).setOnClickListener {
            downloadPair(ModelDownloader.NEEDLE_BIN, ModelDownloader.SHRUTI_ODIA, "Shruti Odia")
        }
        findViewById<Button>(R.id.btnDlIndicOr).setOnClickListener {
            downloadPair(ModelDownloader.INDIC_TOKENS, ModelDownloader.INDIC_OR, "IndicConformer Odia")
        }
        findViewById<Button>(R.id.btnCompare).setOnClickListener {
            startActivity(Intent(this, CompareActivity::class.java))
        }

        findViewById<Button>(R.id.btnSaveStart).setOnClickListener {
            Prefs.setEngine(this, AsrEngineType.entries[spinnerEngine.selectedItemPosition])
            Prefs.setGeminiPolishEnabled(this, cbGemini.isChecked)
            Prefs.setApiKey(this, etApiKey.text.toString())
            Prefs.setModel(this, Prefs.AVAILABLE_MODELS[spinnerModel.selectedItemPosition])
            askedThisSession = false
            checkAndRequestNextMissingPermission()
            updateStatusAndAutoStart()
        }

        checkAndRequestNextMissingPermission()
    }

    private fun downloadPair(
        first: ModelDownloader.ModelArtifact,
        second: ModelDownloader.ModelArtifact,
        label: String
    ) {
        uiScope.launch {
            tvStatus.text = "Downloading $label (part 1/2)..."
            val r1 = if (ModelDownloader.isInstalled(this@MainActivity, first)) {
                Result.success(ModelDownloader.getFile(this@MainActivity, first))
            } else {
                ModelDownloader.download(this@MainActivity, first) { p ->
                    tvStatus.text = "Downloading ${first.fileName}: $p%"
                }
            }
            if (r1.isFailure) {
                tvStatus.text = "Error: ${r1.exceptionOrNull()?.message}"
                return@launch
            }
            val r2 = ModelDownloader.download(this@MainActivity, second) { p ->
                tvStatus.text = "Downloading ${second.fileName}: $p%"
            }
            if (r2.isSuccess) {
                Toast.makeText(this@MainActivity, "$label ready offline!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Failed: ${r2.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
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
        val needleOk = ModelDownloader.isInstalled(this, ModelDownloader.NEEDLE_BIN)
        val shrutiHiOk = ModelDownloader.isInstalled(this, ModelDownloader.SHRUTI_HI_EN)
        val shrutiOrOk = ModelDownloader.isInstalled(this, ModelDownloader.SHRUTI_ODIA)
        val indicOrOk = ModelDownloader.isInstalled(this, ModelDownloader.INDIC_OR)

        tvStatus.text = buildString {
            appendLine("Active Engine: ${Prefs.getEngine(this@MainActivity).label}")
            appendLine("Shruti Engine (needle): ${if (needleOk) "Installed" else "Not downloaded"}")
            appendLine("Shruti Hindi/Hinglish: ${if (shrutiHiOk) "Installed" else "Not downloaded"}")
            appendLine("Shruti Odia: ${if (shrutiOrOk) "Installed" else "Not downloaded"}")
            appendLine("IndicConformer Odia ONNX: ${if (indicOrOk) "Installed" else "Not downloaded"}")
            appendLine("Mic: ${if (micOk) "OK" else "Needed"} | Overlay: ${if (overlayOk) "OK" else "Needed"} | Accessibility: ${if (accOk) "OK" else "Needed"}")
        }

        if (micOk && overlayOk) {
            startForegroundService(Intent(this, FloatingMicService::class.java))
        }
    }
}

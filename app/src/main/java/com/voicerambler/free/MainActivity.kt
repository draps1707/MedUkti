package com.voicerambler.free

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateStatus() }

    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val spinnerModel = findViewById<Spinner>(R.id.spinnerModel)
        val spinnerMode = findViewById<Spinner>(R.id.spinnerMode)
        val etGlossary = findViewById<EditText>(R.id.etGlossary)
        tvStatus = findViewById(R.id.tvStatus)

        etApiKey.setText(Prefs.getApiKey(this))
        etGlossary.setText(Prefs.getGlossary(this))

        spinnerModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.AVAILABLE_MODELS)
        spinnerModel.setSelection(Prefs.AVAILABLE_MODELS.indexOf(Prefs.getModel(this)).coerceAtLeast(0))

        val modes = DictationMode.entries.map { it.label }
        spinnerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        spinnerMode.setSelection(DictationMode.entries.indexOf(Prefs.getMode(this)).coerceAtLeast(0))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setApiKey(this, etApiKey.text.toString())
            Prefs.setModel(this, Prefs.AVAILABLE_MODELS[spinnerModel.selectedItemPosition])
            Prefs.setMode(this, DictationMode.entries[spinnerMode.selectedItemPosition])
            Prefs.setGlossary(this, etGlossary.text.toString())
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        findViewById<Button>(R.id.btnMicPerm).setOnClickListener {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permLauncher.launch(perms.toTypedArray())
        }

        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }

        findViewById<Button>(R.id.btnToggleService).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant Display Over Other Apps first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val svc = Intent(this, FloatingMicService::class.java)
            startForegroundService(svc)
            Toast.makeText(this, "Floating Mic started! Open any app to dictate.", Toast.LENGTH_SHORT).show()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accOk = DictationAccessibilityService.instance != null
        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val keyOk = Prefs.getApiKey(this).isNotBlank()

        tvStatus.text = buildString {
            appendLine("API Key: ${if (keyOk) "Set" else "Missing"}")
            appendLine("Overlay Permission: ${if (overlayOk) "Granted" else "Missing"}")
            appendLine("Accessibility Service: ${if (accOk) "Enabled" else "Disabled"}")
            appendLine("Unrestricted Battery: ${if (batteryOk) "Yes" else "No (recommended for Samsung)"}")
        }
    }
}

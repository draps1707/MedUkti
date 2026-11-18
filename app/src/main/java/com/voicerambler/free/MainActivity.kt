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
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var askedThisSession = false
    private lateinit var tvStatus: TextView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateStatusAndAutoStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val spinnerModel = findViewById<Spinner>(R.id.spinnerModel)
        tvStatus = findViewById(R.id.tvStatus)

        etApiKey.setText(Prefs.getApiKey(this))
        spinnerModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.AVAILABLE_MODELS)
        spinnerModel.setSelection(Prefs.AVAILABLE_MODELS.indexOf(Prefs.getModel(this)).coerceAtLeast(0))

        findViewById<Button>(R.id.btnSaveStart).setOnClickListener {
            Prefs.setApiKey(this, etApiKey.text.toString())
            Prefs.setModel(this, Prefs.AVAILABLE_MODELS[spinnerModel.selectedItemPosition])
            askedThisSession = false
            checkAndRequestNextMissingPermission()
            updateStatusAndAutoStart()
        }

        checkAndRequestNextMissingPermission()
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
            Toast.makeText(this, "Allow Display over other apps for the floating mic", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        if (DictationAccessibilityService.instance == null) {
            Toast.makeText(this, "Enable VoiceRambler Text Injector in Accessibility", Toast.LENGTH_LONG).show()
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
        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val keyOk = Prefs.getApiKey(this).isNotBlank()

        tvStatus.text = buildString {
            appendLine("API Key: ${if (keyOk) "Ready" else "Paste your key above"}")
            appendLine("Mic & Notifications: ${if (micOk) "Granted" else "Needed"}")
            appendLine("Overlay (Floating Mic): ${if (overlayOk) "Granted" else "Needed"}")
            appendLine("Accessibility Injector: ${if (accOk) "Enabled" else "Needed"}")
            appendLine("Unrestricted Battery: ${if (batteryOk) "Yes" else "Recommended"}")
        }

        if (micOk && overlayOk && keyOk) {
            startForegroundService(Intent(this, FloatingMicService::class.java))
        }
    }
}

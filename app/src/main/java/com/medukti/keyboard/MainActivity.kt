package com.medukti.keyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSetupBadge: TextView
    private lateinit var btnDlWhisperTurbo: TextView
    private lateinit var btnDlStreamingDraft: TextView
    private lateinit var btnDlParakeetIndic: TextView
    private lateinit var btnDlSraVaani: TextView
    private lateinit var etHfToken: EditText
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val spinnerEngine = findViewById<Spinner>(R.id.spinnerEngine)
        val etPersonalDict = findViewById<EditText>(R.id.etPersonalDict)
        etHfToken = findViewById(R.id.etHfToken)
        val switchAutoListen = findViewById<Switch>(R.id.switchAutoListen)
        val switchStreamingDraft = findViewById<Switch>(R.id.switchStreamingDraft)
        tvStatus = findViewById(R.id.tvStatus)
        tvSetupBadge = findViewById(R.id.tvSetupBadge)
        btnDlWhisperTurbo = findViewById(R.id.btnDlWhisperTurbo)
        btnDlStreamingDraft = findViewById(R.id.btnDlStreamingDraft)
        btnDlParakeetIndic = findViewById(R.id.btnDlParakeetIndic)
        btnDlSraVaani = findViewById(R.id.btnDlSraVaani)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, AsrEngineType.entries.map { it.label })
        spinnerEngine.adapter = adapter
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

        findViewById<View>(R.id.btnEnableKb).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.btnPickKb).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        btnDlWhisperTurbo.setOnClickListener {
            if (ModelDownloader.isWhisperTurboInstalled(this) && ModelDownloader.isInstalled(this, ModelDownloader.SILERO_VAD)) return@setOnClickListener
            startDownloadPack("whisper_turbo")
        }
        btnDlStreamingDraft.setOnClickListener {
            if (ModelDownloader.isStreamingZipformerInstalled(this) && ModelDownloader.isInstalled(this, ModelDownloader.SILERO_VAD)) return@setOnClickListener
            startDownloadPack("streaming_draft")
        }
        btnDlParakeetIndic.setOnClickListener {
            if (ModelDownloader.isParakeetIndicInstalled(this) && ModelDownloader.isInstalled(this, ModelDownloader.SILERO_VAD)) return@setOnClickListener
            Prefs.setHfToken(this, etHfToken.text.toString())
            startDownloadPack("parakeet_indic")
        }
        btnDlSraVaani.setOnClickListener {
            if (ModelDownloader.isSraVaaniInstalled(this) && ModelDownloader.isInstalled(this, ModelDownloader.SILERO_VAD)) return@setOnClickListener
            startDownloadPack("sravaani")
        }

        findViewById<View>(R.id.btnSaveStart).setOnClickListener {
            Prefs.setEngine(this, AsrEngineType.entries[spinnerEngine.selectedItemPosition])
            Prefs.setHfToken(this, etHfToken.text.toString())
            Prefs.setPersonalDict(this, etPersonalDict.text.toString())
            Prefs.setAutoListen(this, switchAutoListen.isChecked)
            requestNeededPermissions()
            updateStatus()
            uiScope.launch { OfflineAsrEngines.preload(this@MainActivity) }
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        }

        uiScope.launch {
            ModelDownloadService.state.collectLatest { st ->
                if (st.isRunning) {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = st.statusText
                    val target = when (st.packId) {
                        "whisper_turbo" -> btnDlWhisperTurbo
                        "streaming_draft" -> btnDlStreamingDraft
                        "parakeet_indic" -> btnDlParakeetIndic
                        "sravaani" -> btnDlSraVaani
                        else -> null
                    }
                    target?.text = "${st.percent}%"
                } else {
                    if (st.error != null) {
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = st.statusText
                    } else {
                        tvStatus.visibility = View.GONE
                    }
                    updateStatus()
                }
            }
        }

        ModelDownloader.cleanupUnusedModels(this)
        requestNeededPermissions()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun requestNeededPermissions() {
        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startDownloadPack(packId: String) {
        if (ModelDownloadService.state.value.isRunning) {
            Toast.makeText(this, "A download is already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        ModelDownloadService.startPack(this, packId)
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun styleModelBadge(view: TextView, installed: Boolean) {
        if (installed) {
            view.text = "✓ Ready"
            view.setBackgroundResource(R.drawable.bg_badge_ready)
            view.setTextColor(Color.parseColor("#6DD58C"))
        } else {
            view.text = "Download"
            view.setBackgroundResource(R.drawable.bg_btn_compact)
            view.setTextColor(Color.WHITE)
        }
    }

    private fun updateStatus() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val imeOk = isImeEnabled()
        val vadOk = ModelDownloader.isInstalled(this, ModelDownloader.SILERO_VAD)

        if (imeOk && micOk) {
            tvSetupBadge.text = "✓ Active"
            tvSetupBadge.setBackgroundResource(R.drawable.bg_badge_ready)
            tvSetupBadge.setTextColor(Color.parseColor("#6DD58C"))
        } else if (!imeOk) {
            tvSetupBadge.text = "Enable KB"
            tvSetupBadge.setBackgroundResource(R.drawable.bg_btn_compact)
            tvSetupBadge.setTextColor(Color.WHITE)
        } else {
            tvSetupBadge.text = "Grant Mic"
            tvSetupBadge.setBackgroundResource(R.drawable.bg_btn_compact)
            tvSetupBadge.setTextColor(Color.WHITE)
        }

        styleModelBadge(btnDlWhisperTurbo, vadOk && ModelDownloader.isWhisperTurboInstalled(this))
        styleModelBadge(btnDlStreamingDraft, vadOk && ModelDownloader.isStreamingZipformerInstalled(this))
        val indicOk = vadOk && ModelDownloader.isParakeetIndicInstalled(this)
        styleModelBadge(btnDlParakeetIndic, indicOk)
        etHfToken.visibility = if (indicOk) View.GONE else View.VISIBLE
        styleModelBadge(btnDlSraVaani, vadOk && ModelDownloader.isSraVaaniInstalled(this))
    }
}

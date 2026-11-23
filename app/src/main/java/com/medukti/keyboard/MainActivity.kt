package com.medukti.keyboard

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvSetupBadge: TextView
    private lateinit var etGroqKey: EditText
    private lateinit var etGeminiKey: EditText
    private lateinit var etGroqAsrModel: EditText
    private lateinit var etGroqLlmModel: EditText
    private lateinit var etGeminiModel: EditText
    private lateinit var tvKeyTestResult: TextView
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
        val switchAutoListen = findViewById<Switch>(R.id.switchAutoListen)
        val switchLiveStream = findViewById<Switch>(R.id.switchLiveStream)
        val switchAiPolish = findViewById<Switch>(R.id.switchAiPolish)
        val etPersonalDict = findViewById<EditText>(R.id.etPersonalDict)

        tvSetupBadge = findViewById(R.id.tvSetupBadge)
        etGroqKey = findViewById(R.id.etGroqKey)
        etGeminiKey = findViewById(R.id.etGeminiKey)
        etGroqAsrModel = findViewById(R.id.etGroqAsrModel)
        etGroqLlmModel = findViewById(R.id.etGroqLlmModel)
        etGeminiModel = findViewById(R.id.etGeminiModel)
        tvKeyTestResult = findViewById(R.id.tvKeyTestResult)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, AsrEngineType.entries.map { it.label })
        spinnerEngine.adapter = adapter
        spinnerEngine.setSelection(AsrEngineType.entries.indexOf(Prefs.getEngine(this)).coerceAtLeast(0))

        switchAutoListen.isChecked = Prefs.isAutoListen(this)
        switchLiveStream.isChecked = Prefs.isLiveStreamingEnabled(this)
        switchAiPolish.isChecked = Prefs.isAiPolishEnabled(this)

        etGroqKey.setText(Prefs.getGroqKey(this))
        etGeminiKey.setText(Prefs.getGeminiKey(this))
        etGroqAsrModel.setText(Prefs.getGroqAsrModel(this))
        etGroqLlmModel.setText(Prefs.getGroqLlmModel(this))
        etGeminiModel.setText(Prefs.getGeminiModel(this))
        etPersonalDict.setText(Prefs.getPersonalDict(this))

        findViewById<View>(R.id.btnEnableKb).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.btnPickKb).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<View>(R.id.btnGetGroqKey).setOnClickListener {
            openUrl("https://console.groq.com/keys")
        }
        findViewById<View>(R.id.btnGetGeminiKey).setOnClickListener {
            openUrl("https://aistudio.google.com/apikey")
        }
        findViewById<View>(R.id.btnPasteGroqKey).setOnClickListener {
            pasteInto(etGroqKey, "Groq key pasted")
        }
        findViewById<View>(R.id.btnPasteGeminiKey).setOnClickListener {
            pasteInto(etGeminiKey, "Gemini key pasted")
        }

        findViewById<View>(R.id.btnTestKeys).setOnClickListener {
            val gKey = etGroqKey.text.toString().trim()
            val mKey = etGeminiKey.text.toString().trim()
            Prefs.setGroqKey(this, gKey)
            Prefs.setGeminiKey(this, mKey)
            tvKeyTestResult.text = "Testing…"
            uiScope.launch {
                val (gRes, mRes) = withContext(Dispatchers.IO) {
                    CloudAsrClient.testGroqKey(gKey) to CloudAsrClient.testGeminiKey(mKey)
                }
                tvKeyTestResult.text = "$gRes  |  $mRes"
                updateStatus()
            }
        }

        findViewById<View>(R.id.btnSaveStart).setOnClickListener {
            Prefs.setEngine(this, AsrEngineType.entries[spinnerEngine.selectedItemPosition])
            Prefs.setAutoListen(this, switchAutoListen.isChecked)
            Prefs.setLiveStreamingEnabled(this, switchLiveStream.isChecked)
            Prefs.setAiPolishEnabled(this, switchAiPolish.isChecked)
            Prefs.setGroqKey(this, etGroqKey.text.toString())
            Prefs.setGeminiKey(this, etGeminiKey.text.toString())
            Prefs.setGroqAsrModel(this, etGroqAsrModel.text.toString())
            Prefs.setGroqLlmModel(this, etGroqLlmModel.text.toString())
            Prefs.setGeminiModel(this, etGeminiModel.text.toString())
            Prefs.setPersonalDict(this, etPersonalDict.text.toString())
            requestNeededPermissions()
            updateStatus()
            Toast.makeText(this, "Online settings saved!", Toast.LENGTH_SHORT).show()
        }

        requestNeededPermissions()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Open in browser: $url", Toast.LENGTH_LONG).show()
        }
    }

    private fun pasteInto(target: EditText, msg: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (txt.isNotEmpty()) {
            target.setText(txt)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNeededPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun updateStatus() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val imeOk = isImeEnabled()
        val keyOk = Prefs.getGroqKey(this).isNotBlank() || Prefs.getGeminiKey(this).isNotBlank()

        when {
            imeOk && micOk && keyOk -> {
                tvSetupBadge.text = "✓ Online Ready"
                tvSetupBadge.setBackgroundResource(R.drawable.bg_badge_ready)
                tvSetupBadge.setTextColor(Color.parseColor("#6DD58C"))
            }
            !imeOk -> {
                tvSetupBadge.text = "Enable KB"
                tvSetupBadge.setBackgroundResource(R.drawable.bg_btn_compact)
                tvSetupBadge.setTextColor(Color.WHITE)
            }
            !micOk -> {
                tvSetupBadge.text = "Grant Mic"
                tvSetupBadge.setBackgroundResource(R.drawable.bg_btn_compact)
                tvSetupBadge.setTextColor(Color.WHITE)
            }
            else -> {
                tvSetupBadge.text = "Add API Key"
                tvSetupBadge.setBackgroundResource(R.drawable.bg_btn_compact)
                tvSetupBadge.setTextColor(Color.WHITE)
            }
        }
    }
}

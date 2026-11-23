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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var tvDictSearchResult: TextView
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    private val groqAsrOptions = listOf(
        "Whisper Large-v3 (Best Accuracy)" to "whisper-large-v3",
        "Whisper Large-v3 Turbo (Fastest)" to "whisper-large-v3-turbo",
        "Custom…" to "__CUSTOM__"
    )
    private val groqLlmOptions = listOf(
        "GPT-OSS 120B (Recommended)" to "openai/gpt-oss-120b",
        "Llama 3.3 70B (Backup)" to "llama-3.3-70b-versatile",
        "Off (Raw Whisper + Dictionary only)" to "off",
        "Custom…" to "__CUSTOM__"
    )
    private val geminiOptions = listOf(
        "Gemini 3.5 Flash-Lite (Fastest)" to "gemini-3.5-flash-lite",
        "Gemini 3.8 Flash (Best Quality)" to "gemini-3.8-flash",
        "Custom…" to "__CUSTOM__"
    )

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rootScroll = findViewById<View>(R.id.rootScroll)
        ViewCompat.setOnApplyWindowInsetsListener(rootScroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val spinnerEngine = findViewById<Spinner>(R.id.spinnerEngine)
        val spinnerFocusPack = findViewById<Spinner>(R.id.spinnerFocusPack)
        val spinnerGroqAsr = findViewById<Spinner>(R.id.spinnerGroqAsr)
        val spinnerGroqLlm = findViewById<Spinner>(R.id.spinnerGroqLlm)
        val spinnerGeminiModel = findViewById<Spinner>(R.id.spinnerGeminiModel)

        val switchAutoListen = findViewById<Switch>(R.id.switchAutoListen)
        val switchLiveStream = findViewById<Switch>(R.id.switchLiveStream)
        val etPersonalDict = findViewById<EditText>(R.id.etPersonalDict)
        val etDictSearch = findViewById<EditText>(R.id.etDictSearch)
        val advancedContainer = findViewById<View>(R.id.advancedContainer)
        val btnToggleAdvanced = findViewById<TextView>(R.id.btnToggleAdvanced)

        tvSetupBadge = findViewById(R.id.tvSetupBadge)
        etGroqKey = findViewById(R.id.etGroqKey)
        etGeminiKey = findViewById(R.id.etGeminiKey)
        etGroqAsrModel = findViewById(R.id.etGroqAsrModel)
        etGroqLlmModel = findViewById(R.id.etGroqLlmModel)
        etGeminiModel = findViewById(R.id.etGeminiModel)
        tvKeyTestResult = findViewById(R.id.tvKeyTestResult)
        tvDictSearchResult = findViewById(R.id.tvDictSearchResult)

        btnToggleAdvanced.setOnClickListener {
            val open = advancedContainer.visibility != View.VISIBLE
            advancedContainer.visibility = if (open) View.VISIBLE else View.GONE
            btnToggleAdvanced.text = if (open) {
                "ADVANCED OPTIONS (MODELS & VOICE) ▴"
            } else {
                "ADVANCED OPTIONS (MODELS & VOICE) ▾"
            }
        }

        spinnerEngine.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, AsrEngineType.entries.map { it.label })
        spinnerEngine.setSelection(AsrEngineType.entries.indexOf(Prefs.getEngine(this)).coerceAtLeast(0))

        spinnerFocusPack.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FocusPack.entries.map { it.label })
        spinnerFocusPack.setSelection(FocusPack.entries.indexOf(Prefs.getFocusPack(this)).coerceAtLeast(0))

        bindModelSpinner(spinnerGroqAsr, etGroqAsrModel, groqAsrOptions, Prefs.getGroqAsrModel(this))
        val curLlm = if (!Prefs.isAiPolishEnabled(this)) "off" else Prefs.getGroqLlmModel(this)
        bindModelSpinner(spinnerGroqLlm, etGroqLlmModel, groqLlmOptions, curLlm)
        bindModelSpinner(spinnerGeminiModel, etGeminiModel, geminiOptions, Prefs.getGeminiModel(this))

        switchAutoListen.isChecked = Prefs.isAutoListen(this)
        switchLiveStream.isChecked = Prefs.isLiveStreamingEnabled(this)

        etGroqKey.setText(Prefs.getGroqKey(this))
        etGeminiKey.setText(Prefs.getGeminiKey(this))
        etPersonalDict.setText(Prefs.getPersonalDict(this))

        findViewById<View>(R.id.btnEnableKb).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.btnPickKb).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<View>(R.id.btnGetGroqKey).setOnClickListener { openUrl("https://console.groq.com/keys") }
        findViewById<View>(R.id.btnGetGeminiKey).setOnClickListener { openUrl("https://aistudio.google.com/apikey") }
        findViewById<View>(R.id.btnPasteGroqKey).setOnClickListener { pasteInto(etGroqKey, "Groq key pasted") }
        findViewById<View>(R.id.btnPasteGeminiKey).setOnClickListener { pasteInto(etGeminiKey, "Gemini key pasted") }

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

        findViewById<View>(R.id.btnDictSearch).setOnClickListener {
            val q = etDictSearch.text.toString().trim()
            if (q.isEmpty()) return@setOnClickListener
            uiScope.launch {
                val matches = withContext(Dispatchers.IO) {
                    MedicalNormalizer.searchDictionary(this@MainActivity, q)
                }
                if (matches.isEmpty()) {
                    tvDictSearchResult.text = "Not in built-in dictionary — add to Personal Fixes below if needed."
                    tvDictSearchResult.setTextColor(Color.parseColor("#FDD663"))
                } else {
                    tvDictSearchResult.text = "✓ Built-in: ${matches.joinToString(", ")}"
                    tvDictSearchResult.setTextColor(Color.parseColor("#6DD58C"))
                }
            }
        }

        findViewById<View>(R.id.btnSaveStart).setOnClickListener {
            Prefs.setEngine(this, AsrEngineType.entries[spinnerEngine.selectedItemPosition])
            Prefs.setFocusPack(this, FocusPack.entries[spinnerFocusPack.selectedItemPosition])
            Prefs.setAutoListen(this, switchAutoListen.isChecked)
            Prefs.setLiveStreamingEnabled(this, switchLiveStream.isChecked)

            Prefs.setGroqAsrModel(this, resolveModelValue(spinnerGroqAsr, etGroqAsrModel, groqAsrOptions))
            val llmChoice = resolveModelValue(spinnerGroqLlm, etGroqLlmModel, groqLlmOptions)
            if (llmChoice == "off") {
                Prefs.setAiPolishEnabled(this, false)
            } else {
                Prefs.setAiPolishEnabled(this, true)
                Prefs.setGroqLlmModel(this, llmChoice)
            }
            Prefs.setGeminiModel(this, resolveModelValue(spinnerGeminiModel, etGeminiModel, geminiOptions))

            Prefs.setGroqKey(this, etGroqKey.text.toString())
            Prefs.setGeminiKey(this, etGeminiKey.text.toString())
            Prefs.setPersonalDict(this, etPersonalDict.text.toString())
            requestNeededPermissions()
            updateStatus()
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        }

        requestNeededPermissions()
        updateStatus()
    }

    private fun bindModelSpinner(
        spinner: Spinner,
        customEdit: EditText,
        options: List<Pair<String, String>>,
        currentVal: String
    ) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.first })
        val idx = options.indexOfFirst { it.second.equals(currentVal, ignoreCase = true) }
        if (idx >= 0) {
            spinner.setSelection(idx)
            customEdit.visibility = View.GONE
        } else {
            spinner.setSelection(options.lastIndex)
            customEdit.setText(currentVal)
            customEdit.visibility = View.VISIBLE
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val isCustom = options[pos].second == "__CUSTOM__"
                customEdit.visibility = if (isCustom) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun resolveModelValue(
        spinner: Spinner,
        customEdit: EditText,
        options: List<Pair<String, String>>
    ): String {
        val sel = options[spinner.selectedItemPosition].second
        return if (sel == "__CUSTOM__") customEdit.text.toString().trim() else sel
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

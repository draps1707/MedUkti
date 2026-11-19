package com.voicerambler.free

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MedUktiImeService : InputMethodService() {

    private enum class MicUiState { IDLE, LISTENING, POLISHING }
    private enum class DrawerMode { VOICE, RX, NUMPAD, HISTORY }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isListening = AtomicBoolean(false)
    private var isProcessing = false
    private var lastCommittedLen = 0
    private var vadConsumerJob: Job? = null
    private var liveLoopJob: Job? = null

    private var rootView: View? = null
    private var btnModeToggle: TextView? = null
    private var tvImeStatus: TextView? = null
    private var micRingContainer: FrameLayout? = null
    private var chipRow: LinearLayout? = null
    private var keyPeriod: TextView? = null
    private var panelVoice: View? = null
    private var panelRx: View? = null
    private var panelNumpad: View? = null
    private var panelHistory: View? = null
    private var rxListContainer: LinearLayout? = null
    private var historyListContainer: LinearLayout? = null
    private var etSnippetSearch: EditText? = null
    private var activeDrawer = DrawerMode.VOICE

    private val idleSleepRunnable = Runnable {
        if (!isListening.get() && !isProcessing) {
            SherpaOnnxEngine.releaseRecognizer()
        }
    }

    private val processingWatchdog = Runnable {
        if (isProcessing && !isListening.get()) {
            isProcessing = false
            setMicUiState(MicUiState.IDLE)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (isListening.get()) stopContinuousDictation()
                SherpaOnnxEngine.releaseRecognizer()
            }
        }
    }

    private fun resetIdleSleepTimer() {
        mainHandler.removeCallbacks(idleSleepRunnable)
        mainHandler.postDelayed(idleSleepRunnable, 3 * 60 * 1000L)
    }

    private fun buzz(ms: Long = 16L) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        resetIdleSleepTimer()
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
        isListening.set(false)
        liveLoopJob?.cancel()
        vadConsumerJob?.cancel()
        SherpaOnnxEngine.releaseRecognizer()
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_medukti, null)
        rootView = view
        btnModeToggle = view.findViewById(R.id.btnModeToggle)
        tvImeStatus = view.findViewById(R.id.tvImeStatus)
        micRingContainer = view.findViewById(R.id.micRingContainer)
        chipRow = view.findViewById(R.id.chipRow)
        keyPeriod = view.findViewById(R.id.keyPeriod)
        panelVoice = view.findViewById(R.id.panelVoice)
        panelRx = view.findViewById(R.id.panelRx)
        panelNumpad = view.findViewById(R.id.panelNumpad)
        panelHistory = view.findViewById(R.id.panelHistory)
        rxListContainer = view.findViewById(R.id.rxListContainer)
        historyListContainer = view.findViewById(R.id.historyListContainer)
        etSnippetSearch = view.findViewById(R.id.etSnippetSearch)

        btnModeToggle?.setOnClickListener { toggleLanguage() }
        view.findViewById<View>(R.id.btnDrawerRx).setOnClickListener {
            switchDrawer(if (activeDrawer == DrawerMode.RX) DrawerMode.VOICE else DrawerMode.RX)
        }
        view.findViewById<View>(R.id.btnDrawerNum).setOnClickListener {
            switchDrawer(if (activeDrawer == DrawerMode.NUMPAD) DrawerMode.VOICE else DrawerMode.NUMPAD)
        }
        view.findViewById<View>(R.id.btnDrawerHistory).setOnClickListener {
            switchDrawer(if (activeDrawer == DrawerMode.HISTORY) DrawerMode.VOICE else DrawerMode.HISTORY)
        }
        view.findViewById<View>(R.id.btnOpenSettings).setOnClickListener {
            buzz()
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        micRingContainer?.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            buzz(22L)
            if (!isListening.get()) startContinuousDictation() else stopContinuousDictation()
        }

        etSnippetSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateRxList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        buildNumpad(view)

        view.findViewById<View>(R.id.keySwitchKb).setOnClickListener {
            buzz()
            if (isListening.get()) stopContinuousDictation()
            val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else false
            if (!switched) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }
        view.findViewById<View>(R.id.keyComma).setOnClickListener { commitQuickToken(", ") }
        view.findViewById<View>(R.id.keySpace).setOnClickListener { commitQuickToken(" ") }
        keyPeriod?.setOnClickListener {
            val ch = if (Prefs.isOdiaEngine(Prefs.getEngine(this))) "। " else ". "
            commitQuickToken(ch)
        }
        view.findViewById<View>(R.id.keyUndo).setOnClickListener {
            buzz(24L)
            undoLastCommitted()
        }

        val bsRepeatRunnable = object : Runnable {
            override fun run() {
                performBackspace()
                mainHandler.postDelayed(this, 65L)
            }
        }
        view.findViewById<View>(R.id.keyBackspace).setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    buzz(10L)
                    performBackspace()
                    mainHandler.postDelayed(bsRepeatRunnable, 350L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(bsRepeatRunnable)
                    true
                }
                else -> false
            }
        }

        view.findViewById<View>(R.id.keyEnter).setOnClickListener {
            buzz(15L)
            performEnterOrAction()
        }

        refreshModeUi()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lastCommittedLen = 0
        switchDrawer(DrawerMode.VOICE)
        refreshModeUi()
        resetIdleSleepTimer()
        serviceScope.launch { OfflineAsrEngines.preload(this@MedUktiImeService) }
        if (Prefs.isAutoListen(this) && !isListening.get() && !isProcessing) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startContinuousDictation()
            } else {
                tvImeStatus?.text = "Grant Mic permission in MedUkti app"
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (isListening.get()) {
            stopContinuousDictation()
        }
        resetIdleSleepTimer()
        super.onFinishInputView(finishingInput)
    }

    private fun switchDrawer(mode: DrawerMode) {
        activeDrawer = mode
        panelVoice?.visibility = if (mode == DrawerMode.VOICE) View.VISIBLE else View.GONE
        panelRx?.visibility = if (mode == DrawerMode.RX) View.VISIBLE else View.GONE
        panelNumpad?.visibility = if (mode == DrawerMode.NUMPAD) View.VISIBLE else View.GONE
        panelHistory?.visibility = if (mode == DrawerMode.HISTORY) View.VISIBLE else View.GONE
        if (mode == DrawerMode.RX) populateRxList(etSnippetSearch?.text?.toString() ?: "")
        if (mode == DrawerMode.HISTORY) populateHistoryList()
    }

    private fun refreshModeUi() {
        val eng = Prefs.getEngine(this)
        val isOdia = Prefs.isOdiaEngine(eng)
        btnModeToggle?.text = if (isOdia) "OD" else "MED"
        keyPeriod?.text = if (isOdia) "।" else "."
        populateShortcutChips(isOdia)
    }

    private fun populateShortcutChips(isOdia: Boolean) {
        val row = chipRow ?: return
        row.removeAllViews()
        val chips = if (isOdia) {
            listOf("।" to "। ", "?" to "? ", "," to ", ", "ହଁ" to "ହଁ ", "ନାଇଁ" to "ନାଇଁ ", "ଠିକ୍ ଅଛି" to "ଠିକ୍ ଅଛି ", "କ'ଣ" to "କ'ଣ ", "ଆଚ୍ଛା" to "ଆଚ୍ଛା ", "ଧନ୍ୟବାଦ" to "ଧନ୍ୟବାଦ ")
        } else {
            listOf(
                "Tab." to "Tab. ", "Inj." to "Inj. ", "Cap." to "Cap. ", "Syp." to "Syp. ",
                "OD" to "OD ", "BD" to "BD ", "TDS" to "TDS ", "SOS" to "SOS ", "IV" to "IV ",
                "BP" to "BP ", "SpO2" to "SpO2 ", "c/o" to "c/o ", "h/o" to "h/o ",
                "k/c/o" to "k/c/o ", "O/E:" to "O/E: ", "Dx:" to "Dx: ", "Rx:" to "Rx:\n", "Advice:" to "Advice:\n"
            )
        }
        val dp = resources.displayMetrics.density
        for ((label, insertText) in chips) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                setBackgroundColor(Color.parseColor("#263238"))
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * dp).toInt() }
                layoutParams = lp
                setOnClickListener {
                    buzz(10L)
                    commitQuickToken(insertText)
                }
            }
            row.addView(tv)
        }
    }

    private fun populateRxList(query: String) {
        val container = rxListContainer ?: return
        container.removeAllViews()
        val q = query.trim().lowercase()
        val items = Prefs.getSnippets(this).filter {
            q.isEmpty() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q)
        }
        val dp = resources.displayMetrics.density
        for ((label, expansion) in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1E242B"))
                setPadding((10 * dp).toInt(), (7 * dp).toInt(), (10 * dp).toInt(), (7 * dp).toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (5 * dp).toInt() }
                layoutParams = lp
                setOnClickListener {
                    buzz(14L)
                    commitQuickToken(if (expansion.endsWith("\n") || expansion.endsWith(" ")) expansion else "$expansion ")
                    switchDrawer(DrawerMode.VOICE)
                }
            }
            val title = TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#81D4FA"))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val preview = TextView(this).apply {
                text = expansion.replace("\n", " • ")
                setTextColor(Color.parseColor("#CFD8DC"))
                textSize = 11f
                maxLines = 2
            }
            card.addView(title)
            card.addView(preview)
            container.addView(card)
        }
    }

    private fun buildNumpad(view: View) {
        val r1 = view.findViewById<LinearLayout>(R.id.numRow1)
        val r2 = view.findViewById<LinearLayout>(R.id.numRow2)
        val r3 = view.findViewById<LinearLayout>(R.id.numRow3)
        val dp = resources.displayMetrics.density

        fun addRowKeys(row: LinearLayout, keys: List<Pair<String, String>>) {
            row.removeAllViews()
            for ((label, token) in keys) {
                val tv = TextView(this).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#263238"))
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = (2 * dp).toInt()
                        marginEnd = (2 * dp).toInt()
                    }
                    layoutParams = lp
                    setOnClickListener {
                        buzz(10L)
                        commitQuickToken(token)
                    }
                }
                row.addView(tv)
            }
        }

        addRowKeys(r1, (1..9).map { "$it" to "$it" } + ("0" to "0"))
        addRowKeys(r2, listOf("/" to "/", "." to ".", "%" to "%", "-" to "-", ":" to ":", "(" to "(", ")" to ")"))
        addRowKeys(r3, listOf("mg" to " mg ", "mcg" to " mcg ", "ml" to " ml ", "gm" to " gm ", "IU" to " IU "))
    }

    private fun populateHistoryList() {
        val container = historyListContainer ?: return
        container.removeAllViews()
        val items = Prefs.getRecentDictations(this)
        val dp = resources.displayMetrics.density
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No recent dictations yet."
                setTextColor(Color.parseColor("#90A4AE"))
                textSize = 12f
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            }
            container.addView(empty)
            return
        }
        for (item in items) {
            val tv = TextView(this).apply {
                text = item
                setTextColor(Color.WHITE)
                textSize = 12f
                maxLines = 2
                setBackgroundColor(Color.parseColor("#1E242B"))
                setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (5 * dp).toInt() }
                layoutParams = lp
                setOnClickListener {
                    buzz(14L)
                    commitQuickToken("$item ")
                    switchDrawer(DrawerMode.VOICE)
                }
            }
            container.addView(tv)
        }
    }

    private fun toggleLanguage() {
        if (isListening.get() || isProcessing) return
        val cur = Prefs.getEngine(this)
        val next = if (Prefs.isOdiaEngine(cur)) Prefs.getMedEngine(this) else Prefs.getOdiaEngine(this)
        Prefs.setEngine(this, next)
        refreshModeUi()
        buzz(25L)
        SherpaOnnxEngine.releaseInactiveModels(next)
        tvImeStatus?.text = if (Prefs.isOdiaEngine(next)) "Odia Ready" else "Medical Ready"
        serviceScope.launch { OfflineAsrEngines.preload(this@MedUktiImeService) }
    }

    private fun setMicUiState(state: MicUiState) {
        val res = when (state) {
            MicUiState.IDLE -> R.drawable.bg_mic_ring
            MicUiState.LISTENING -> R.drawable.bg_mic_ring_active
            MicUiState.POLISHING -> R.drawable.bg_mic_ring_polishing
        }
        micRingContainer?.setBackgroundResource(res)
        if (state != MicUiState.LISTENING) {
            micRingContainer?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        }
        tvImeStatus?.text = when (state) {
            MicUiState.IDLE -> "Tap mic to speak"
            MicUiState.LISTENING -> "Listening..."
            MicUiState.POLISHING -> "Finishing..."
        }
    }

    private fun commitQuickToken(token: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(token, 1)
        lastCommittedLen = token.length
    }

    private fun performBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        lastCommittedLen = 0
    }

    private fun undoLastCommitted() {
        val ic = currentInputConnection ?: return
        if (lastCommittedLen > 0) {
            ic.deleteSurroundingText(lastCommittedLen, 0)
            lastCommittedLen = 0
            tvImeStatus?.text = "Undid last phrase"
        }
    }

    private fun performEnterOrAction() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = info != null && (info.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        lastCommittedLen = 0
    }

    private fun commitSpokenText(phrase: String) {
        val rawClean = phrase.trim()
        if (rawClean.isEmpty()) return
        mainHandler.post {
            resetIdleSleepTimer()
            val ic = currentInputConnection ?: return@post

            val lowerCmd = rawClean.lowercase().trimEnd('.', ',', '!', '?')
            if (lowerCmd == "undo that" || lowerCmd == "delete last" || lowerCmd == "undo last") {
                undoLastCommitted()
                return@post
            }

            val before = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
            val trimmedBefore = before.trimEnd()
            val atSentenceStart = trimmedBefore.isEmpty() ||
                trimmedBefore.endsWith(".") ||
                trimmedBefore.endsWith("।") ||
                trimmedBefore.endsWith("?") ||
                trimmedBefore.endsWith("!") ||
                before.endsWith("\n")

            val formatted = if (atSentenceStart && rawClean[0] in 'a'..'z') {
                rawClean[0].uppercaseChar() + rawClean.substring(1)
            } else {
                rawClean
            }

            val startsWithPunct = formatted[0] in listOf('.', '।', ',', '?', '!', '\n')
            val needLeadingSpace = !startsWithPunct &&
                before.isNotEmpty() &&
                !before.endsWith(" ") &&
                !before.endsWith("\n")

            val outText = (if (needLeadingSpace) " " else "") + formatted
            ic.commitText(outText, 1)
            lastCommittedLen = outText.length
            Prefs.addRecentDictation(this, formatted)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startContinuousDictation() {
        resetIdleSleepTimer()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant Microphone permission in MedUkti app", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = Prefs.getEngine(this)
        val resolvedRes = OfflineAsrEngines.resolveEngine(this, selected)
        if (resolvedRes.isFailure) {
            Toast.makeText(this, resolvedRes.exceptionOrNull()?.message ?: "Download model pack first", Toast.LENGTH_LONG).show()
            return
        }
        val resolved = resolvedRes.getOrThrow()
        if (SherpaOnnxEngine.getOrCreateVad(this) == null) {
            Toast.makeText(this, "Download a model pack first (includes Silero VAD)", Toast.LENGTH_LONG).show()
            return
        }

        isListening.set(true)
        setMicUiState(MicUiState.LISTENING)
        val segmentChannel = Channel<FloatArray>(Channel.UNLIMITED)

        vadConsumerJob = serviceScope.launch(Dispatchers.IO) {
            try {
                for (seg in segmentChannel) {
                    val res = OfflineAsrEngines.transcribeSamples(this@MedUktiImeService, resolved, seg)
                    res.onSuccess { raw ->
                        val finalOut = raw.trim()
                        if (finalOut.isNotBlank()) {
                            commitSpokenText(finalOut)
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    mainHandler.removeCallbacks(processingWatchdog)
                    isProcessing = false
                    setMicUiState(MicUiState.IDLE)
                    resetIdleSleepTimer()
                }
            }
        }

        liveLoopJob = serviceScope.launch(Dispatchers.IO) {
            var ar: AudioRecord? = null
            try {
                val vad = SherpaOnnxEngine.getOrCreateVad(this@MedUktiImeService)
                    ?: throw IllegalStateException("Silero VAD not installed")
                vad.reset()

                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufSize = maxOf(minBuf, 4096)
                ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Mic failed to initialize")
                }
                ar.startRecording()

                val windowSize = 512
                val shortBuf = ShortArray(windowSize)
                val floatBuf = FloatArray(windowSize)
                var frameCount = 0

                while (isListening.get()) {
                    val read = ar.read(shortBuf, 0, windowSize)
                    if (read == windowSize) {
                        var sumSq = 0.0
                        for (i in 0 until windowSize) {
                            val f = shortBuf[i] / 32768.0f
                            floatBuf[i] = f
                            sumSq += f * f
                        }
                        frameCount++
                        if (frameCount % 3 == 0) {
                            val rms = sqrt(sumSq / windowSize).toFloat()
                            val scale = (1.0f + (rms * 4.0f).coerceAtMost(0.22f))
                            mainHandler.post {
                                if (isListening.get()) {
                                    micRingContainer?.scaleX = scale
                                    micRingContainer?.scaleY = scale
                                }
                            }
                        }
                        vad.acceptWaveform(floatBuf)
                        while (!vad.empty()) {
                            val seg = vad.front()
                            if (seg.samples.isNotEmpty()) {
                                segmentChannel.trySend(seg.samples)
                            }
                            vad.pop()
                        }
                    }
                }

                vad.flush()
                while (!vad.empty()) {
                    val seg = vad.front()
                    if (seg.samples.isNotEmpty()) {
                        segmentChannel.trySend(seg.samples)
                    }
                    vad.pop()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MedUktiImeService, e.message ?: "Mic error", Toast.LENGTH_SHORT).show()
                }
            } finally {
                try {
                    ar?.stop()
                    ar?.release()
                } catch (_: Exception) {
                }
                segmentChannel.close()
            }
        }
    }

    private fun stopContinuousDictation() {
        isListening.set(false)
        if (vadConsumerJob?.isActive == true) {
            isProcessing = true
            setMicUiState(MicUiState.POLISHING)
            mainHandler.removeCallbacks(processingWatchdog)
            mainHandler.postDelayed(processingWatchdog, 6000L)
        } else {
            isProcessing = false
            setMicUiState(MicUiState.IDLE)
        }
    }
}

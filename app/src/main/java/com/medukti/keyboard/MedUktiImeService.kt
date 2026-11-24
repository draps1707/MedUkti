package com.medukti.keyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.ClipboardManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class MedUktiImeService : InputMethodService() {

    private enum class MicUiState { IDLE, LISTENING, POLISHING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isListening = AtomicBoolean(false)
    private var isProcessing = false
    private var lastCommittedLen = 0
    private var lastCommittedPhrase = ""
    private var lastLiveDraftText = ""
    private var pendingWrongPhrase: String? = null
    private var pendingRightPhrase: String? = null
    private var saveFixBar: View? = null
    private var tvSaveFixLabel: TextView? = null
    private var vadConsumerJob: Job? = null
    private var liveLoopJob: Job? = null
    private val activeResolvedEngine = AtomicReference<AsrEngineType>(AsrEngineType.MEDICAL)

    private var btnModeOdia: TextView? = null
    private var btnModeMedical: TextView? = null
    private var btnModeHinglish: TextView? = null
    private var micRingContainer: LinearLayout? = null
    private var ivKeyboardMic: ImageView? = null
    private var llEqBars: LinearLayout? = null
    private var eqBar1: View? = null
    private var eqBar2: View? = null
    private var eqBar3: View? = null
    private var eqBar4: View? = null

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
            }
        }
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
        serviceScope.launch(Dispatchers.IO) {
            MedicalNormalizer.init(this@MedUktiImeService)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
        isListening.set(false)
        liveLoopJob?.cancel()
        vadConsumerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_medukti, null)
        btnModeOdia = view.findViewById(R.id.btnModeOdia)
        btnModeMedical = view.findViewById(R.id.btnModeMedical)
        btnModeHinglish = view.findViewById(R.id.btnModeHinglish)
        micRingContainer = view.findViewById(R.id.micRingContainer)
        ivKeyboardMic = view.findViewById(R.id.ivKeyboardMic)
        llEqBars = view.findViewById(R.id.llEqBars)
        eqBar1 = view.findViewById(R.id.eqBar1)
        eqBar2 = view.findViewById(R.id.eqBar2)
        eqBar3 = view.findViewById(R.id.eqBar3)
        eqBar4 = view.findViewById(R.id.eqBar4)

        fun selectMode(mode: AsrEngineType) {
            buzz(20L)
            val wasListening = isListening.get()
            val prevMode = Prefs.getEngine(this)
            Prefs.setEngine(this, mode)
            activeResolvedEngine.set(mode)
            refreshModeUi()
            if (wasListening && prevMode != mode) {
                stopContinuousDictation()
                mainHandler.postDelayed({
                    if (isInputViewShown && !isListening.get()) {
                        startContinuousDictation()
                    }
                }, 220L)
            } else if (!wasListening) {
                startContinuousDictation()
            }
        }
        val openSettingsLongClick = View.OnLongClickListener {
            buzz(18L)
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        }
        btnModeOdia?.setOnClickListener { selectMode(AsrEngineType.ODIA) }
        btnModeMedical?.setOnClickListener { selectMode(AsrEngineType.MEDICAL) }
        btnModeHinglish?.setOnClickListener { selectMode(AsrEngineType.HINGLISH) }
        btnModeOdia?.setOnLongClickListener(openSettingsLongClick)
        btnModeMedical?.setOnLongClickListener(openSettingsLongClick)
        btnModeHinglish?.setOnLongClickListener(openSettingsLongClick)

        micRingContainer?.setOnClickListener {
            buzz(22L)
            if (isProcessing && !isListening.get()) {
                mainHandler.removeCallbacks(processingWatchdog)
                vadConsumerJob?.cancel()
                isProcessing = false
                setMicUiState(MicUiState.IDLE)
                return@setOnClickListener
            }
            if (!isListening.get()) startContinuousDictation() else stopContinuousDictation()
        }

        view.findViewById<View>(R.id.keyPaste).setOnClickListener {
            buzz(14L)
            pasteFromClipboard()
        }
        view.findViewById<View>(R.id.keyPaste).setOnLongClickListener { anchor ->
            buzz(20L)
            showPasteHistoryMenu(anchor)
            true
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

        refreshModeUi()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lastCommittedLen = 0
        refreshModeUi()
        activeResolvedEngine.set(Prefs.getEngine(this))
        if (vadConsumerJob?.isActive != true) {
            isProcessing = false
            setMicUiState(MicUiState.IDLE)
        }
        if (Prefs.isAutoListen(this) && !isListening.get()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (isProcessing && vadConsumerJob?.isActive == true) {
                    serviceScope.launch {
                        vadConsumerJob?.join()
                        if (!isListening.get() && isInputViewShown) {
                            startContinuousDictation()
                        }
                    }
                } else {
                    startContinuousDictation()
                }
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isListening.get()) {
            stopContinuousDictation()
        }
    }

    private fun styleModePill(tv: TextView?, active: Boolean) {
        if (tv == null) return
        tv.setBackgroundResource(if (active) R.drawable.bg_key_accent else R.drawable.bg_key_dark)
        tv.setTextColor(android.graphics.Color.parseColor(if (active) "#FFFFFF" else "#9AA5B4"))
    }

    private fun refreshModeUi() {
        val engine = Prefs.getEngine(this)
        styleModePill(btnModeOdia, engine == AsrEngineType.ODIA)
        styleModePill(btnModeMedical, engine == AsrEngineType.MEDICAL)
        styleModePill(btnModeHinglish, engine == AsrEngineType.HINGLISH)
        setMicUiState(
            when {
                isListening.get() -> MicUiState.LISTENING
                isProcessing -> MicUiState.POLISHING
                else -> MicUiState.IDLE
            }
        )
    }

    private fun setMicUiState(state: MicUiState) {
        when (state) {
            MicUiState.IDLE -> {
                micRingContainer?.keepScreenOn = false
                micRingContainer?.alpha = 1.0f
                micRingContainer?.setBackgroundResource(R.drawable.bg_mic_pill_idle)
                llEqBars?.visibility = View.GONE
                ivKeyboardMic?.visibility = View.VISIBLE
                ivKeyboardMic?.scaleX = 1.0f
                ivKeyboardMic?.scaleY = 1.0f
                ivKeyboardMic?.setColorFilter(android.graphics.Color.parseColor("#2DD4BF"))
            }
            MicUiState.LISTENING -> {
                micRingContainer?.keepScreenOn = true
                micRingContainer?.alpha = 1.0f
                micRingContainer?.setBackgroundResource(R.drawable.bg_mic_pill_active)
                ivKeyboardMic?.visibility = View.GONE
                llEqBars?.visibility = View.VISIBLE
            }
            MicUiState.POLISHING -> {
                micRingContainer?.keepScreenOn = false
                micRingContainer?.alpha = 1.0f
                micRingContainer?.setBackgroundResource(R.drawable.bg_mic_pill_polishing)
                llEqBars?.visibility = View.GONE
                ivKeyboardMic?.visibility = View.VISIBLE
                ivKeyboardMic?.scaleX = 1.0f
                ivKeyboardMic?.scaleY = 1.0f
                ivKeyboardMic?.setColorFilter(android.graphics.Color.parseColor("#FFFFFF"))
            }
        }
    }

    private fun showPasteHistoryMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, -1, 0, "↺ Undo last phrase")
        val hist = Prefs.getHistory(this)
        hist.forEachIndexed { idx, item ->
            val preview = if (item.length > 42) item.take(40) + "…" else item
            popup.menu.add(0, idx, idx + 1, preview)
        }
        popup.setOnMenuItemClickListener { m ->
            if (m.itemId == -1) {
                undoLastCommitted()
            } else {
                val chosen = hist.getOrNull(m.itemId)
                if (!chosen.isNullOrEmpty()) {
                    commitQuickToken(chosen)
                }
            }
            true
        }
        popup.show()
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else ""
        if (text.isEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        val before = ic.getTextBeforeCursor(20, 0)?.toString() ?: ""
        val needSpace = before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n") &&
            !text.startsWith(" ") && !text.startsWith("\n")
        val out = (if (needSpace) " " else "") + text
        ic.commitText(out, 1)
        lastCommittedLen = out.length
    }

    private fun commitQuickToken(token: String) {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        ic.commitText(token, 1)
        lastCommittedLen = token.length
    }

    private fun performBackspace() {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
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
        ic.finishComposingText()
        if (lastCommittedLen > 0) {
            ic.deleteSurroundingText(lastCommittedLen, 0)
            lastCommittedLen = 0
            val candidate = lastCommittedPhrase.trim().trimEnd('.', ',', '!', '?')
            if (candidate.isNotEmpty() && candidate.length <= 40 && candidate.split(Regex("\\s+")).size <= 3) {
                pendingWrongPhrase = candidate
            }
            Toast.makeText(this, "Undid last phrase", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performEnterOrAction() {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = info != null && (info.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
        lastCommittedLen = 0
    }

    private fun updateLiveDraft(rawDraft: String) {
        val clean = rawDraft.trim()
        if (clean.isEmpty()) return
        lastLiveDraftText = clean
        mainHandler.post {
            val ic = currentInputConnection ?: return@post
            val before = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
            val needSpace = before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n")
            ic.setComposingText((if (needSpace) " " else "") + clean, 1)
        }
    }

    private fun lockOrClearLiveDraft() {
        val draft = lastLiveDraftText
        lastLiveDraftText = ""
        mainHandler.post {
            val ic = currentInputConnection ?: return@post
            ic.finishComposingText()
            if (draft.isNotEmpty()) {
                lastCommittedLen = draft.length
                lastCommittedPhrase = draft
            }
        }
    }

    private fun commitTranscript(text: String) {
        val isPureNewline = text == "\n" || text == "\n\n"
        val rawClean = if (isPureNewline) text else text.trim()
        if (rawClean.isEmpty()) return

        mainHandler.post {
            val ic = currentInputConnection ?: return@post
            lastLiveDraftText = ""
            ic.setComposingText("", 1)
            ic.finishComposingText()

            val lowerCmd = rawClean.lowercase().trimEnd('.', ',', '!', '?')
            if (lowerCmd == "undo that" || lowerCmd == "delete last" || lowerCmd == "undo last") {
                undoLastCommitted()
                return@post
            }

            if (isPureNewline) {
                ic.commitText(rawClean, 1)
                lastCommittedLen = rawClean.length
                return@post
            }

            val before = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
            val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
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

            val afterChar = after.firstOrNull()
            val needTrailingSpace = afterChar != null &&
                !afterChar.isWhitespace() &&
                afterChar !in listOf('.', '।', ',', '?', '!', ')', ']', ':') &&
                !formatted.endsWith(" ") &&
                !formatted.endsWith("\n")

            val outText = (if (needLeadingSpace) " " else "") + formatted + (if (needTrailingSpace) " " else "")
            ic.commitText(outText, 1)
            lastCommittedLen = outText.length

            val cleanCommitted = formatted.trim().trimEnd('.', ',', '!', '?')
            lastCommittedPhrase = cleanCommitted
            Prefs.appendHistory(this, outText)
            val wrong = pendingWrongPhrase
            if (wrong != null && cleanCommitted.isNotEmpty() && cleanCommitted.length <= 40 &&
                cleanCommitted.split(Regex("\\s+")).size <= 3 && !wrong.equals(cleanCommitted, ignoreCase = true)
            ) {
                pendingRightPhrase = cleanCommitted
                tvSaveFixLabel?.text = "Save fix: $wrong → $cleanCommitted"
                saveFixBar?.visibility = View.VISIBLE
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startContinuousDictation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant Microphone permission in MedUkti app", Toast.LENGTH_SHORT).show()
            return
        }
        if (!CloudAsrClient.isNetworkAvailable(this)) {
            Toast.makeText(this, "No internet — tap 🌐 to switch", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = Prefs.getEngine(this)
        if (selected == AsrEngineType.MEDICAL && Prefs.getGroqKey(this).isBlank()) {
            Toast.makeText(this, "Add your free Groq API key in MedUkti Settings", Toast.LENGTH_LONG).show()
            return
        }
        if (selected == AsrEngineType.HINGLISH && Prefs.getGeminiKey(this).isBlank()) {
            Toast.makeText(this, "Add your free Gemini API key in MedUkti Settings", Toast.LENGTH_LONG).show()
            return
        }
        if (selected == AsrEngineType.ODIA &&
            Prefs.getElevenLabsKey(this).isBlank() &&
            Prefs.getGeminiKey(this).isBlank()
        ) {
            Toast.makeText(this, "Add your ElevenLabs or Gemini API key in MedUkti Settings", Toast.LENGTH_LONG).show()
            return
        }

        activeResolvedEngine.set(selected)
        isListening.set(true)
        isProcessing = false
        setMicUiState(MicUiState.LISTENING)

        val segmentChannel = Channel<Pair<FloatArray, AsrEngineType>>(Channel.UNLIMITED)
        val liveStreamEnabled = Prefs.isLiveStreamingEnabled(this)

        vadConsumerJob = serviceScope.launch(Dispatchers.IO) {
            for ((seg, segEngine) in segmentChannel) {
                val res = CloudAsrClient.transcribeFinal(this@MedUktiImeService, segEngine, seg)
                res.onSuccess { text ->
                    if (text.isNotEmpty()) {
                        commitTranscript(text)
                    } else {
                        lockOrClearLiveDraft()
                    }
                }.onFailure { err ->
                    lockOrClearLiveDraft()
                    mainHandler.post {
                        Toast.makeText(this@MedUktiImeService, err.message ?: "Network error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            withContext(Dispatchers.Main + NonCancellable) {
                mainHandler.removeCallbacks(processingWatchdog)
                isProcessing = false
                if (!isListening.get()) {
                    setMicUiState(MicUiState.IDLE)
                }
            }
        }

        liveLoopJob = serviceScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val frameSamples = 512 // 32 ms
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(frameSamples * 4)

            var recorder: AudioRecord? = null
            var noiseSuppressor: NoiseSuppressor? = null
            val denoiserOn = Prefs.isDenoiserEnabled(this@MedUktiImeService)
            val sileroVad = SileroVadDetector.createOrNull(this@MedUktiImeService)
            val audioMgr = getSystemService(AUDIO_SERVICE) as? AudioManager
            var btScoStarted = false
            var btCommDeviceSet = false
            try {
                var preferredBt: AudioDeviceInfo? = null
                if (audioMgr != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val btDev = audioMgr.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        }
                        if (btDev != null) {
                            btCommDeviceSet = audioMgr.setCommunicationDevice(btDev)
                            preferredBt = btDev
                        }
                    } else if (audioMgr.isBluetoothScoAvailableOffCall) {
                        val hasBt = audioMgr.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        }
                        if (hasBt) {
                            @Suppress("DEPRECATION")
                            audioMgr.startBluetoothSco()
                            @Suppress("DEPRECATION")
                            audioMgr.isBluetoothScoOn = true
                            btScoStarted = true
                        }
                    }
                }
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Mic unavailable")
                }
                if (preferredBt != null) {
                    recorder.preferredDevice = preferredBt
                }
                if (denoiserOn) {
                    try {
                        if (NoiseSuppressor.isAvailable()) {
                            noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                        }
                    } catch (_: Exception) {
                    }
                }
                recorder.startRecording()

                val shortBuf = ShortArray(frameSamples)
                val floatBuf = FloatArray(frameSamples)

                val speechBuf = ArrayList<Float>(16000 * 10)
                val preRoll = ArrayDeque<FloatArray>(12)
                var inSpeech = false
                var consecSpeechFrames = 0
                var activeSpeechFrames = 0
                var silenceFrames = 0
                var peakSpeechRms = 0f
                var animTick = 0
                var lastPreviewSamples = 0
                var previewJob: Job? = null
                val maxSegSamples = sampleRate * 10
                var hpPrevX = 0f
                var hpPrevY = 0f
                val hpAlpha = 0.968f

                while (isListening.get()) {
                    val read = recorder.read(shortBuf, 0, frameSamples)
                    if (read <= 0) {
                        delay(10)
                        continue
                    }
                    for (i in 0 until read) {
                        val x = shortBuf[i] / 32768.0f
                        if (denoiserOn) {
                            val y = hpAlpha * (hpPrevY + x - hpPrevX)
                            hpPrevX = x
                            hpPrevY = y
                            floatBuf[i] = y
                        } else {
                            floatBuf[i] = x
                        }
                    }
                    val curEngine = activeResolvedEngine.get()
                    val speechRmsThreshold = when (curEngine) {
                        AsrEngineType.ODIA -> 0.005f
                        AsrEngineType.HINGLISH -> 0.007f
                        AsrEngineType.MEDICAL -> 0.0085f
                    }
                    val hangoverFrames = if (speechBuf.size >= sampleRate * 4) 14 else 20

                    val rms = CloudAsrClient.computeRms(floatBuf, 0, read)
                    val vadProb = if (sileroVad != null && read == frameSamples) sileroVad.predict(floatBuf) else 1.0f
                    val rawVadHit = if (sileroVad != null) {
                        vadProb >= 0.55f && rms >= 0.005f
                    } else {
                        rms >= speechRmsThreshold
                    }
                    // Relative Energy Endpointer: if speaker already reached strong volume, ignore softer bystander chatter
                    val isBystanderDrop = inSpeech && peakSpeechRms >= 0.020f && rms < peakSpeechRms * 0.25f
                    val isSpeechFrame = rawVadHit && !isBystanderDrop
                    if (isSpeechFrame && rms > peakSpeechRms) {
                        peakSpeechRms = rms
                    }

                    animTick++
                    if (animTick % 3 == 0) {
                        val level = (rms / 0.055f).coerceIn(0.08f, 1.0f)
                        val phase = (animTick / 3) % 4
                        val s1 = if (isSpeechFrame) (0.35f + level * (if (phase == 0) 0.80f else 0.45f)) else 0.25f
                        val s2 = if (isSpeechFrame) (0.40f + level * (if (phase == 1) 0.85f else 0.55f)) else 0.30f
                        val s3 = if (isSpeechFrame) (0.35f + level * (if (phase == 2) 0.80f else 0.50f)) else 0.25f
                        val s4 = if (isSpeechFrame) (0.35f + level * (if (phase == 3) 0.75f else 0.40f)) else 0.22f
                        mainHandler.post {
                            if (isListening.get()) {
                                eqBar1?.scaleY = s1
                                eqBar2?.scaleY = s2
                                eqBar3?.scaleY = s3
                                eqBar4?.scaleY = s4
                            }
                        }
                    }

                    if (!inSpeech) {
                        val copy = FloatArray(read)
                        System.arraycopy(floatBuf, 0, copy, 0, read)
                        if (preRoll.size >= 10) preRoll.removeFirst()
                        preRoll.addLast(copy)
                    }

                    if (isSpeechFrame) {
                        consecSpeechFrames++
                        if (inSpeech || consecSpeechFrames >= 2) activeSpeechFrames++
                        if (!inSpeech && consecSpeechFrames >= 2) {
                            inSpeech = true
                            while (preRoll.isNotEmpty()) {
                                val pf = preRoll.removeFirst()
                                for (v in pf) speechBuf.add(v)
                            }
                        } else if (inSpeech) {
                            for (i in 0 until read) speechBuf.add(floatBuf[i])
                        }
                        silenceFrames = 0
                    } else {
                        consecSpeechFrames = 0
                        if (inSpeech) {
                            silenceFrames++
                            for (i in 0 until read) speechBuf.add(floatBuf[i])
                        }
                    }

                    // Live streaming preview every ~2.2 seconds of active speech (both MED & OD)
                    if (liveStreamEnabled && inSpeech &&
                        speechBuf.size - lastPreviewSamples >= sampleRate * 2.2 &&
                        previewJob?.isActive != true
                    ) {
                        lastPreviewSamples = speechBuf.size
                        val snap = speechBuf.toFloatArray()
                        previewJob = launch(Dispatchers.IO) {
                            val pRes = CloudAsrClient.transcribeLivePreview(this@MedUktiImeService, curEngine, snap)
                            pRes.onSuccess { pText ->
                                if (isListening.get() && pText.isNotEmpty()) {
                                    updateLiveDraft(pText)
                                }
                            }
                        }
                    }

                    val segDone = inSpeech && (silenceFrames >= hangoverFrames || speechBuf.size >= maxSegSamples)
                    if (segDone) {
                        previewJob?.cancel()
                        if (speechBuf.size >= 8000 && activeSpeechFrames >= 8) {
                            segmentChannel.trySend(speechBuf.toFloatArray() to curEngine)
                        } else {
                            lockOrClearLiveDraft()
                        }
                        speechBuf.clear()
                        inSpeech = false
                        activeSpeechFrames = 0
                        silenceFrames = 0
                        peakSpeechRms = 0f
                        lastPreviewSamples = 0
                        sileroVad?.reset()
                    }
                }

                previewJob?.cancel()
                if (inSpeech && speechBuf.size >= 8000 && activeSpeechFrames >= 8) {
                    segmentChannel.trySend(speechBuf.toFloatArray() to activeResolvedEngine.get())
                } else if (lastLiveDraftText.isNotEmpty()) {
                    lockOrClearLiveDraft()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this@MedUktiImeService, e.message ?: "Mic error", Toast.LENGTH_SHORT).show()
                }
            } finally {
                segmentChannel.close()
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                try { noiseSuppressor?.release() } catch (_: Exception) {}
                try { sileroVad?.release() } catch (_: Exception) {}
                try {
                    if (btCommDeviceSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioMgr?.clearCommunicationDevice()
                    }
                    if (btScoStarted) {
                        @Suppress("DEPRECATION")
                        audioMgr?.isBluetoothScoOn = false
                        @Suppress("DEPRECATION")
                        audioMgr?.stopBluetoothSco()
                    }
                } catch (_: Exception) {}
                mainHandler.post {
                    isListening.set(false)
                    if (vadConsumerJob?.isActive == true) {
                        isProcessing = true
                        setMicUiState(MicUiState.POLISHING)
                        mainHandler.removeCallbacks(processingWatchdog)
                        mainHandler.postDelayed(processingWatchdog, 15000L)
                    } else {
                        isProcessing = false
                        setMicUiState(MicUiState.IDLE)
                    }
                }
            }
        }
    }

    private fun stopContinuousDictation() {
        isListening.set(false)
        setMicUiState(MicUiState.POLISHING)
        mainHandler.removeCallbacks(processingWatchdog)
        mainHandler.postDelayed(processingWatchdog, 15000L)
    }
}

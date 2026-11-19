package com.voicerambler.free

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class MedUktiImeService : InputMethodService() {

    private enum class MicUiState { IDLE, LISTENING, POLISHING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isListening = AtomicBoolean(false)
    private var isProcessing = false
    private var lastCommittedLen = 0
    private var vadConsumerJob: Job? = null
    private var liveLoopJob: Job? = null

    private var btnModeToggle: TextView? = null
    private var tvImeStatus: TextView? = null
    private var micRingContainer: LinearLayout? = null
    private var tvMicPillLabel: TextView? = null
    private var keyPeriod: TextView? = null

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
        btnModeToggle = view.findViewById(R.id.btnModeToggle)
        tvImeStatus = view.findViewById(R.id.tvImeStatus)
        micRingContainer = view.findViewById(R.id.micRingContainer)
        tvMicPillLabel = view.findViewById(R.id.tvMicPillLabel)
        keyPeriod = view.findViewById(R.id.keyPeriod)

        btnModeToggle?.setOnClickListener { toggleLanguage() }
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

        view.findViewById<View>(R.id.keyComma).setOnClickListener {
            buzz(10L)
            commitQuickToken(", ")
        }

        keyPeriod?.setOnClickListener {
            buzz(10L)
            val ch = if (Prefs.isOdiaEngine(Prefs.getEngine(this))) "। " else ". "
            commitQuickToken(ch)
        }

        // Gboard-style Spacebar: tap inserts space, swipe left/right moves cursor (keeps mic live)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val stepPx = 12f * resources.displayMetrics.density
        var spaceDownX = 0f
        var spaceLastStepX = 0f
        var spaceSwiping = false
        view.findViewById<View>(R.id.keySpace).setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    spaceDownX = ev.x
                    spaceLastStepX = ev.x
                    spaceSwiping = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dxTotal = ev.x - spaceDownX
                    if (!spaceSwiping && abs(dxTotal) > touchSlop) {
                        spaceSwiping = true
                        spaceLastStepX = ev.x
                    }
                    if (spaceSwiping) {
                        val delta = ev.x - spaceLastStepX
                        if (abs(delta) >= stepPx) {
                            val steps = (delta / stepPx).toInt()
                            val keyCode = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                            val ic = currentInputConnection
                            if (ic != null) {
                                ic.finishComposingText()
                                repeat(abs(steps)) {
                                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                                }
                            }
                            buzz(8L)
                            spaceLastStepX += steps * stepPx
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!spaceSwiping) {
                        buzz(10L)
                        commitQuickToken(" ")
                    }
                    spaceSwiping = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    spaceSwiping = false
                    true
                }
                else -> false
            }
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

    private fun refreshModeUi() {
        val eng = Prefs.getEngine(this)
        val isOdia = Prefs.isOdiaEngine(eng)
        btnModeToggle?.text = if (isOdia) "OD" else "MED"
        keyPeriod?.text = if (isOdia) "।" else "."
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
            MicUiState.IDLE -> R.drawable.bg_mic_pill_idle
            MicUiState.LISTENING -> R.drawable.bg_mic_pill_active
            MicUiState.POLISHING -> R.drawable.bg_mic_pill_polishing
        }
        micRingContainer?.setBackgroundResource(res)
        tvMicPillLabel?.text = when (state) {
            MicUiState.IDLE -> "Tap to Speak"
            MicUiState.LISTENING -> "Listening... (Tap to Stop)"
            MicUiState.POLISHING -> "Finishing..."
        }
        tvImeStatus?.text = when (state) {
            MicUiState.IDLE -> "Ready"
            MicUiState.LISTENING -> "Listening..."
            MicUiState.POLISHING -> "Finalizing..."
        }
    }

    // Apple-style live-mic editing: never stops mic when tapping punctuation/space/backspace/enter
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
            tvImeStatus?.text = "Undid last phrase"
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
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        lastCommittedLen = 0
    }

    private fun commitSpokenText(phrase: String) {
        if (phrase.isEmpty()) return
        val isPureNewline = phrase.all { it == '\n' }
        val rawClean = if (isPureNewline) phrase else phrase.trim()
        if (rawClean.isEmpty()) return

        mainHandler.post {
            resetIdleSleepTimer()
            val ic = currentInputConnection ?: return@post

            val lowerCmd = rawClean.lowercase().trimEnd('.', ',', '!', '?')
            if (lowerCmd == "undo that" || lowerCmd == "delete last" || lowerCmd == "undo last") {
                undoLastCommitted()
                return@post
            }

            if (isPureNewline) {
                ic.finishComposingText()
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

            // Apple-style smart cursor spacing: add trailing space only when inserting before a word
            val afterChar = after.firstOrNull()
            val needTrailingSpace = afterChar != null &&
                !afterChar.isWhitespace() &&
                afterChar !in listOf('.', '।', ',', '?', '!', ')', ']', ':') &&
                !formatted.endsWith(" ") &&
                !formatted.endsWith("\n")

            val outText = (if (needLeadingSpace) " " else "") + formatted + (if (needTrailingSpace) " " else "")
            ic.commitText(outText, 1)
            lastCommittedLen = outText.length
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

        val useStreamingDraft = Prefs.isStreamingDraftEnabled(this) && !Prefs.isOdiaEngine(resolved) && ModelDownloader.isStreamingZipformerInstalled(this)
        var streamObj = if (useStreamingDraft) StreamingZipformerEngine.createStream(this) else null
        val streamLock = Any()
        val pendingSegments = AtomicInteger(0)

        vadConsumerJob = serviceScope.launch(Dispatchers.IO) {
            try {
                for (seg in segmentChannel) {
                    pendingSegments.incrementAndGet()
                    val res = OfflineAsrEngines.transcribeSamples(this@MedUktiImeService, resolved, seg)
                    try {
                        res.onSuccess { raw ->
                            val isNewlineCmd = raw.isNotEmpty() && raw.all { it == '\n' }
                            val finalOut = if (isNewlineCmd) raw else raw.trim()
                            if (finalOut.isNotEmpty()) {
                                commitSpokenText(finalOut)
                            } else {
                                mainHandler.post { currentInputConnection?.finishComposingText() }
                            }
                        }.onFailure {
                            mainHandler.post { currentInputConnection?.finishComposingText() }
                        }
                    } finally {
                        pendingSegments.decrementAndGet()
                    }
                }
            } finally {
                synchronized(streamLock) {
                    try { streamObj?.release() } catch (_: Exception) {}
                    streamObj = null
                }
                withContext(Dispatchers.Main + NonCancellable) {
                    currentInputConnection?.finishComposingText()
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
                val bufSize = maxOf(minBuf * 4, 16384)
                ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Mic failed to initialize")
                }
                ar.startRecording()

                val windowSize = 512
                val shortBuf = ShortArray(windowSize)
                val floatBuf = FloatArray(windowSize)
                var frameCount = 0
                var lastDraft = ""

                while (isListening.get()) {
                    val read = ar.read(shortBuf, 0, windowSize)
                    if (read == windowSize) {
                        for (i in 0 until windowSize) {
                            floatBuf[i] = shortBuf[i] / 32768.0f
                        }
                        if (useStreamingDraft) {
                            val draftNow = synchronized(streamLock) {
                                streamObj?.let { StreamingZipformerEngine.acceptAndDecode(it, floatBuf) } ?: ""
                            }
                            frameCount++
                            if (frameCount % 5 == 0 && pendingSegments.get() == 0 && draftNow.isNotEmpty() && draftNow != lastDraft) {
                                lastDraft = draftNow
                                mainHandler.post {
                                    if (isListening.get()) {
                                        currentInputConnection?.setComposingText(draftNow, 1)
                                    }
                                }
                            }
                        }
                        vad.acceptWaveform(floatBuf)
                        var emitted = false
                        while (!vad.empty()) {
                            val seg = vad.front()
                            if (seg.samples.isNotEmpty()) {
                                segmentChannel.trySend(seg.samples)
                                emitted = true
                            }
                            vad.pop()
                        }
                        if (emitted && useStreamingDraft) {
                            synchronized(streamLock) {
                                try { streamObj?.release() } catch (_: Exception) {}
                                streamObj = StreamingZipformerEngine.createStream(this@MedUktiImeService)
                            }
                            lastDraft = ""
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

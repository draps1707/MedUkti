package com.voicerambler.free

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class FloatingMicService : Service() {

    private enum class MicUiState { IDLE, LISTENING, POLISHING }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var tvLangBadge: TextView? = null
    private lateinit var fallbackRecorder: WavRecorderHelper
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val isListening = AtomicBoolean(false)
    private var isProcessing = false
    private var isFallbackMode = false

    private var sessionBeforeCursor = ""
    private var sessionAfterCursor = ""
    private val committedText = StringBuilder()
    private var lastRenderedInserted = ""

    private var lastUndoFullText: String? = null
    private var lastUndoCursor: Int = 0

    private var liveLoopJob: Job? = null
    private var vadConsumerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fallbackRecorder = WavRecorderHelper(this)
        startForegroundNotification()
        showFloatingBubble()

        serviceScope.launch {
            OfflineAsrEngines.preload(this@FloatingMicService)
        }

        DictationAccessibilityService.onEditableFocusChanged = { focused ->
            mainHandler.post {
                if (focused) {
                    refreshLangBadge()
                    floatingView?.visibility = View.VISIBLE
                } else {
                    if (isListening.get()) {
                        stopContinuousDictation()
                    }
                    floatingView?.visibility = View.GONE
                }
            }
        }
    }

    private fun buzz(ms: Long = 20L) {
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

    private fun startForegroundNotification() {
        val channelId = "voice_rambler_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "VoiceRambler Mic", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("VoiceRambler Free (Hinglish & Odia)")
            .setContentText("Tap mic to dictate, tap HI/OD to switch language.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(101, notification)
    }

    private fun setMicUiState(state: MicUiState) {
        val res = when (state) {
            MicUiState.IDLE -> R.drawable.bg_mic_ring
            MicUiState.LISTENING -> R.drawable.bg_mic_ring_active
            MicUiState.POLISHING -> R.drawable.bg_mic_ring_polishing
        }
        floatingView?.setBackgroundResource(res)
    }

    private fun refreshLangBadge() {
        val eng = Prefs.getEngine(this)
        tvLangBadge?.text = when (eng) {
            AsrEngineType.INDIC_CONFORMER_OR -> "OD"
            AsrEngineType.GEMINI_CLOUD -> "GM"
            else -> "HI"
        }
    }

    private fun toggleLanguage() {
        if (isListening.get() || isProcessing) return
        val cur = Prefs.getEngine(this)
        val next = if (cur == AsrEngineType.INDIC_CONFORMER_OR) {
            if (ModelDownloader.isWhisperApexInstalled(this)) AsrEngineType.WHISPER_HINGLISH_APEX
            else AsrEngineType.WHISPER_HINGLISH_SWIFT
        } else {
            AsrEngineType.INDIC_CONFORMER_OR
        }
        Prefs.setEngine(this, next)
        refreshLangBadge()
        buzz(25L)
        Toast.makeText(
            this,
            if (next == AsrEngineType.INDIC_CONFORMER_OR) "Switched to Odia" else "Switched to Hinglish",
            Toast.LENGTH_SHORT
        ).show()
        serviceScope.launch { OfflineAsrEngines.preload(this@FloatingMicService) }
    }

    private fun showFloatingBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.layout_floating_mic, null)
        floatingView = view
        tvLangBadge = view.findViewById(R.id.tvLangBadge)
        refreshLangBadge()

        if (DictationAccessibilityService.instance != null && DictationAccessibilityService.instance?.getActiveFieldText() == null) {
            view.visibility = View.GONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.getBubbleX(this@FloatingMicService)
            y = Prefs.getBubbleY(this@FloatingMicService)
        }

        tvLangBadge?.setOnClickListener {
            toggleLanguage()
        }

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            if (!isDragging && !isListening.get() && !isProcessing) {
                longPressTriggered = true
                val undo = lastUndoFullText
                if (undo != null) {
                    buzz(35L)
                    DictationAccessibilityService.instance?.setFullTextWithCursor(undo, lastUndoCursor)
                    lastUndoFullText = null
                    Toast.makeText(this@FloatingMicService, "Restored previous text", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val micIcon = view.findViewById<View>(R.id.ivMicIcon) ?: view
        micIcon.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    isDragging = false
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 600L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        params.x = initX + dx
                        params.y = initY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (isDragging) {
                        Prefs.setBubblePos(this@FloatingMicService, params.x, params.y)
                    } else if (!longPressTriggered && !isProcessing) {
                        buzz(20L)
                        if (!isListening.get()) startContinuousDictation() else stopContinuousDictation()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
    }

    private fun renderInlineText() {
        val combined = committedText.toString().trim()
        if (combined == lastRenderedInserted || combined.isEmpty()) return
        lastRenderedInserted = combined

        val needLeadingSpace = sessionBeforeCursor.isNotEmpty() && !sessionBeforeCursor.endsWith(" ") && !sessionBeforeCursor.endsWith("\n")
        val needTrailingSpace = sessionAfterCursor.isNotEmpty() && !sessionAfterCursor.startsWith(" ") && !sessionAfterCursor.startsWith("\n")
        val mid = buildString {
            if (needLeadingSpace) append(" ")
            append(combined)
            if (needTrailingSpace) append(" ")
        }
        val full = sessionBeforeCursor + mid + sessionAfterCursor
        val cursorEnd = sessionBeforeCursor.length + (if (needLeadingSpace) 1 else 0) + combined.length
        mainHandler.post {
            DictationAccessibilityService.instance?.setFullTextWithCursor(full, cursorEnd)
        }
    }

    private fun startContinuousDictation() {
        val ctx = DictationAccessibilityService.instance?.getCursorContext()
        sessionBeforeCursor = ctx?.first ?: ""
        sessionAfterCursor = ctx?.second ?: ""
        lastUndoFullText = sessionBeforeCursor + sessionAfterCursor
        lastUndoCursor = sessionBeforeCursor.length
        committedText.clear()
        lastRenderedInserted = ""

        val selected = Prefs.getEngine(this)
        val resolvedRes = OfflineAsrEngines.resolveEngine(this, selected)
        if (resolvedRes.isFailure) {
            Toast.makeText(this, resolvedRes.exceptionOrNull()?.message ?: "Download Hinglish or Odia pack first", Toast.LENGTH_LONG).show()
            return
        }
        val resolved = resolvedRes.getOrThrow()

        if (SherpaOnnxEngine.getOrCreateVad(this) != null) {
            isFallbackMode = false
            startContinuousVadLoop(resolved)
        } else {
            isFallbackMode = true
            startFallbackTapToStop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startContinuousVadLoop(resolved: AsrEngineType) {
        isListening.set(true)
        setMicUiState(MicUiState.LISTENING)
        val segmentChannel = Channel<FloatArray>(Channel.UNLIMITED)
        val useOptionalGemini = Prefs.isGeminiPolishEnabled(this) && Prefs.getApiKey(this).isNotBlank()

        vadConsumerJob = serviceScope.launch(Dispatchers.IO) {
            for (seg in segmentChannel) {
                val res = OfflineAsrEngines.transcribeSamples(this@FloatingMicService, resolved, seg)
                res.onSuccess { raw ->
                    var finalOut = raw.trim()
                    if (useOptionalGemini && resolved != AsrEngineType.GEMINI_CLOUD && finalOut.isNotBlank()) {
                        GeminiClient.polishOfflineTranscript(Prefs.getApiKey(this@FloatingMicService), Prefs.getModel(this@FloatingMicService), finalOut)
                            .onSuccess { polished -> if (polished.isNotBlank()) finalOut = polished }
                    }
                    if (finalOut.isNotBlank()) {
                        if (committedText.isNotEmpty()) committedText.append(" ")
                        committedText.append(finalOut)
                        renderInlineText()
                    }
                }
            }
            withContext(Dispatchers.Main) {
                isProcessing = false
                setMicUiState(MicUiState.IDLE)
            }
        }

        liveLoopJob = serviceScope.launch(Dispatchers.IO) {
            var ar: AudioRecord? = null
            try {
                val vad = SherpaOnnxEngine.getOrCreateVad(this@FloatingMicService)
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

                while (isListening.get()) {
                    val read = ar.read(shortBuf, 0, windowSize)
                    if (read == windowSize) {
                        for (i in 0 until windowSize) {
                            floatBuf[i] = shortBuf[i] / 32768.0f
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
                    Toast.makeText(this@FloatingMicService, e.message ?: "VAD error", Toast.LENGTH_SHORT).show()
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

    private fun startFallbackTapToStop() {
        try {
            fallbackRecorder.startRecording()
            isListening.set(true)
            setMicUiState(MicUiState.LISTENING)
        } catch (e: Exception) {
            isListening.set(false)
            setMicUiState(MicUiState.IDLE)
            Toast.makeText(this, "Mic error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopContinuousDictation() {
        if (!isFallbackMode) {
            isListening.set(false)
            isProcessing = true
            setMicUiState(MicUiState.POLISHING)
            return
        }

        val wavFile = fallbackRecorder.stopRecording()
        isListening.set(false)
        if (wavFile == null || !wavFile.exists()) {
            setMicUiState(MicUiState.IDLE)
            return
        }

        isProcessing = true
        setMicUiState(MicUiState.POLISHING)

        val engine = Prefs.getEngine(this)
        val useOptionalGemini = Prefs.isGeminiPolishEnabled(this) && Prefs.getApiKey(this).isNotBlank()

        serviceScope.launch {
            val rawResult = OfflineAsrEngines.transcribe(this@FloatingMicService, engine, wavFile)
            wavFile.delete()

            rawResult.onSuccess { raw ->
                var finalOut = raw.trim()
                if (useOptionalGemini && engine != AsrEngineType.GEMINI_CLOUD && finalOut.isNotBlank()) {
                    GeminiClient.polishOfflineTranscript(Prefs.getApiKey(this@FloatingMicService), Prefs.getModel(this@FloatingMicService), finalOut)
                        .onSuccess { polished -> if (polished.isNotBlank()) finalOut = polished }
                }
                isProcessing = false
                setMicUiState(MicUiState.IDLE)
                if (finalOut.isNotBlank()) {
                    committedText.clear()
                    committedText.append(finalOut)
                    renderInlineText()
                }
            }.onFailure { err ->
                isProcessing = false
                setMicUiState(MicUiState.IDLE)
                Toast.makeText(this@FloatingMicService, err.message ?: "Transcription error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        DictationAccessibilityService.onEditableFocusChanged = null
        isListening.set(false)
        liveLoopJob?.cancel()
        vadConsumerJob?.cancel()
        fallbackRecorder.stopRecording()
        floatingView?.let { windowManager.removeView(it) }
        serviceScope.cancel()
        super.onDestroy()
    }
}

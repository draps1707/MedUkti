package com.voicerambler.free

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FloatingMicService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var liveClient: GeminiLiveClient? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isListening = false
    private var isProcessing = false
    private var prefixText = ""
    private var lastPartialText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        showFloatingBubble()
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
            .setContentTitle("VoiceRambler Free is ready")
            .setContentText("Tap the floating mic over any app for Gemini Live dictation.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(101, notification)
    }

    private fun showFloatingBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.layout_floating_mic, null)
        floatingView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 400
        }

        val btnMic = view.findViewById<ImageButton>(R.id.btnMicBubble)
        val tvMode = view.findViewById<TextView>(R.id.tvBubbleMode)
        tvMode.text = Prefs.getMode(this).label

        tvMode.setOnClickListener {
            if (isListening || isProcessing) return@setOnClickListener
            val modes = DictationMode.entries
            val next = modes[(modes.indexOf(Prefs.getMode(this)) + 1) % modes.size]
            Prefs.setMode(this, next)
            tvMode.text = next.label
        }

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - touchX).toInt()
                    params.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        btnMic.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            if (!isListening) {
                startDictation(tvMode)
            } else {
                stopDictation(tvMode)
            }
        }

        windowManager.addView(view, params)
    }

    private fun combineText(prefix: String, chunk: String): String {
        if (prefix.isBlank()) return chunk
        if (chunk.isBlank()) return prefix
        return if (prefix.endsWith(" ") || prefix.endsWith("\n")) "$prefix$chunk" else "$prefix $chunk"
    }

    private fun startDictation(tvMode: TextView) {
        val apiKey = Prefs.getApiKey(this)
        val mode = Prefs.getMode(this)
        prefixText = DictationAccessibilityService.instance?.getActiveFieldText() ?: ""
        lastPartialText = ""

        if (apiKey.isBlank()) {
            startFallbackRecognizer(tvMode, mode)
            return
        }

        isListening = true
        tvMode.text = "Live..."

        liveClient = GeminiLiveClient(
            apiKey = apiKey,
            glossary = Prefs.getGlossary(this),
            onLiveTranscript = { liveText ->
                mainHandler.post {
                    lastPartialText = liveText
                    if (mode != DictationMode.VOICE_EDIT) {
                        DictationAccessibilityService.instance?.insertText(
                            combineText(prefixText, liveText),
                            replaceAll = true
                        )
                    }
                }
            },
            onFinished = { finalText ->
                mainHandler.post {
                    isListening = false
                    if (finalText.isNotBlank()) {
                        polishWithGemini(finalText, mode, tvMode)
                    } else {
                        tvMode.text = Prefs.getMode(this@FloatingMicService).label
                    }
                }
            },
            onError = { _ ->
                mainHandler.post {
                    isListening = false
                    liveClient = null
                    // Seamless fallback to on-device partial recognizer + Gemini polish
                    startFallbackRecognizer(tvMode, mode)
                }
            }
        )
        liveClient?.start()
    }

    private fun stopDictation(tvMode: TextView) {
        isListening = false
        val lc = liveClient
        if (lc != null) {
            val captured = lc.stop()
            liveClient = null
            val textToPolish = captured.ifBlank { lastPartialText }
            if (textToPolish.isNotBlank()) {
                polishWithGemini(textToPolish, Prefs.getMode(this), tvMode)
            } else {
                tvMode.text = Prefs.getMode(this).label
            }
        } else {
            speechRecognizer?.stopListening()
        }
    }

    private fun startFallbackRecognizer(tvMode: TextView, mode: DictationMode) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvMode.text = Prefs.getMode(this).label
            return
        }

        speechRecognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = sr

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                tvMode.text = "Listening..."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    lastPartialText = partial
                    if (mode != DictationMode.VOICE_EDIT) {
                        DictationAccessibilityService.instance?.insertText(
                            combineText(prefixText, partial),
                            replaceAll = true
                        )
                    }
                }
            }

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val finalRaw = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() } ?: lastPartialText
                sr.destroy()
                speechRecognizer = null

                if (finalRaw.isBlank()) {
                    tvMode.text = Prefs.getMode(this@FloatingMicService).label
                    return
                }

                if (mode != DictationMode.VOICE_EDIT) {
                    DictationAccessibilityService.instance?.insertText(
                        combineText(prefixText, finalRaw),
                        replaceAll = true
                    )
                }

                polishWithGemini(finalRaw, mode, tvMode)
            }

            override fun onError(error: Int) {
                isListening = false
                val fallback = lastPartialText
                sr.destroy()
                speechRecognizer = null
                if (fallback.isNotBlank()) {
                    polishWithGemini(fallback, mode, tvMode)
                } else {
                    tvMode.text = Prefs.getMode(this@FloatingMicService).label
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        sr.startListening(intent)
    }

    private fun polishWithGemini(rawText: String, mode: DictationMode, tvMode: TextView) {
        val apiKey = Prefs.getApiKey(this)
        if (apiKey.isBlank()) {
            tvMode.text = Prefs.getMode(this).label
            return
        }

        isProcessing = true
        tvMode.text = "Polishing..."
        val model = Prefs.getModel(this)
        val glossary = Prefs.getGlossary(this)
        val existingForEdit = if (mode == DictationMode.VOICE_EDIT) prefixText else null

        serviceScope.launch {
            val result = GeminiClient.polishText(apiKey, model, rawText, mode, glossary, existingForEdit)
            isProcessing = false
            tvMode.text = Prefs.getMode(this@FloatingMicService).label

            result.onSuccess { cleaned ->
                val finalOutput = if (mode == DictationMode.VOICE_EDIT) {
                    cleaned
                } else {
                    combineText(prefixText, cleaned)
                }
                DictationAccessibilityService.instance?.insertText(finalOutput, replaceAll = true)
            }.onFailure { err ->
                Toast.makeText(this@FloatingMicService, "Kept live draft (Gemini: ${err.message})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        liveClient?.stop()
        speechRecognizer?.destroy()
        floatingView?.let { windowManager.removeView(it) }
        serviceScope.cancel()
        super.onDestroy()
    }
}

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
import android.widget.ImageView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingMicService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var ivMic: ImageView? = null
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
            .setContentTitle("VoiceRambler Free is active")
            .setContentText("Tap the floating mic to dictate; drag to move.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(101, notification)
    }

    private fun setMicActiveUi(active: Boolean) {
        ivMic?.setBackgroundResource(if (active) R.drawable.bg_mic_ring_active else R.drawable.bg_mic_ring)
    }

    private fun showFloatingBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.layout_floating_mic, null)
        floatingView = view
        ivMic = view.findViewById(R.id.ivMicIcon)

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

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        val touchTarget = ivMic ?: view
        touchTarget.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initX + dx
                        params.y = initY + dy
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        Prefs.setBubblePos(this@FloatingMicService, params.x, params.y)
                    } else if (!isProcessing) {
                        if (!isListening) startDictation() else stopDictation()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
    }

    private fun combineText(prefix: String, chunk: String): String {
        if (prefix.isBlank()) return chunk
        if (chunk.isBlank()) return prefix
        return if (prefix.endsWith(" ") || prefix.endsWith("\n")) "$prefix$chunk" else "$prefix $chunk"
    }

    private fun startDictation() {
        val apiKey = Prefs.getApiKey(this)
        prefixText = DictationAccessibilityService.instance?.getActiveFieldText() ?: ""
        lastPartialText = ""

        if (apiKey.isBlank()) {
            startFallbackRecognizer()
            return
        }

        isListening = true
        setMicActiveUi(true)

        liveClient = GeminiLiveClient(
            apiKey = apiKey,
            onLiveTranscript = { liveText ->
                mainHandler.post {
                    lastPartialText = liveText
                    DictationAccessibilityService.instance?.insertText(
                        combineText(prefixText, liveText),
                        replaceAll = true
                    )
                }
            },
            onFinished = { finalText ->
                mainHandler.post {
                    isListening = false
                    setMicActiveUi(false)
                    if (finalText.isNotBlank()) {
                        polishWithGemini(finalText)
                    }
                }
            },
            onError = { _ ->
                mainHandler.post {
                    isListening = false
                    liveClient = null
                    startFallbackRecognizer()
                }
            }
        )
        liveClient?.start()
    }

    private fun stopDictation() {
        isListening = false
        setMicActiveUi(false)
        val lc = liveClient
        if (lc != null) {
            val captured = lc.stop()
            liveClient = null
            val textToPolish = captured.ifBlank { lastPartialText }
            if (textToPolish.isNotBlank()) {
                polishWithGemini(textToPolish)
            }
        } else {
            speechRecognizer?.stopListening()
        }
    }

    private fun startFallbackRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setMicActiveUi(false)
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
                setMicActiveUi(true)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    lastPartialText = partial
                    DictationAccessibilityService.instance?.insertText(
                        combineText(prefixText, partial),
                        replaceAll = true
                    )
                }
            }

            override fun onEndOfSpeech() {
                isListening = false
                setMicActiveUi(false)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                setMicActiveUi(false)
                val finalRaw = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() } ?: lastPartialText
                sr.destroy()
                speechRecognizer = null

                if (finalRaw.isBlank()) return

                DictationAccessibilityService.instance?.insertText(
                    combineText(prefixText, finalRaw),
                    replaceAll = true
                )
                polishWithGemini(finalRaw)
            }

            override fun onError(error: Int) {
                isListening = false
                setMicActiveUi(false)
                val fallback = lastPartialText
                sr.destroy()
                speechRecognizer = null
                if (fallback.isNotBlank()) {
                    polishWithGemini(fallback)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        sr.startListening(intent)
    }

    private fun polishWithGemini(rawText: String) {
        val apiKey = Prefs.getApiKey(this)
        if (apiKey.isBlank()) return

        isProcessing = true
        val model = Prefs.getModel(this)

        serviceScope.launch {
            val result = GeminiClient.polishText(apiKey, model, rawText)
            isProcessing = false

            result.onSuccess { cleaned ->
                DictationAccessibilityService.instance?.insertText(
                    combineText(prefixText, cleaned),
                    replaceAll = true
                )
            }.onFailure { err ->
                Toast.makeText(this@FloatingMicService, "Kept live draft (${err.message})", Toast.LENGTH_SHORT).show()
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

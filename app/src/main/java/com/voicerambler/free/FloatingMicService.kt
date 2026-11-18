package com.voicerambler.free

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
    private lateinit var recorder: AudioRecorderHelper
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isRecording = false
    private var isProcessing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        recorder = AudioRecorderHelper(this)
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
            .setContentText("Tap the floating mic over any app to dictate.")
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

        // Tap mode label to cycle presets
        tvMode.setOnClickListener {
            val modes = DictationMode.entries
            val next = modes[(modes.indexOf(Prefs.getMode(this)) + 1) % modes.size]
            Prefs.setMode(this, next)
            tvMode.text = next.label
        }

        // Drag support on root
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
            if (!isRecording) {
                startDictation(tvMode)
            } else {
                stopAndTranscribe(tvMode)
            }
        }

        windowManager.addView(view, params)
    }

    private fun startDictation(tvMode: TextView) {
        try {
            recorder.startRecording()
            isRecording = true
            tvMode.text = "Listening..."
        } catch (e: Exception) {
            Toast.makeText(this, "Mic error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAndTranscribe(tvMode: TextView) {
        val file = recorder.stopRecording()
        isRecording = false
        if (file == null || !file.exists()) {
            tvMode.text = Prefs.getMode(this).label
            return
        }

        val apiKey = Prefs.getApiKey(this)
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Add your free Gemini API key in VoiceRambler first", Toast.LENGTH_LONG).show()
            file.delete()
            tvMode.text = Prefs.getMode(this).label
            return
        }

        isProcessing = true
        tvMode.text = "Polishing..."

        val mode = Prefs.getMode(this)
        val model = Prefs.getModel(this)
        val glossary = Prefs.getGlossary(this)
        val existingText = if (mode == DictationMode.VOICE_EDIT) {
            DictationAccessibilityService.instance?.getActiveFieldText()
        } else null

        serviceScope.launch {
            val result = GeminiClient.transcribeAudio(apiKey, model, file, mode, glossary, existingText)
            file.delete()
            isProcessing = false
            tvMode.text = Prefs.getMode(this@FloatingMicService).label

            result.onSuccess { cleaned ->
                val ok = DictationAccessibilityService.instance?.insertText(
                    cleaned,
                    replaceAll = (mode == DictationMode.VOICE_EDIT)
                ) ?: false
                if (!ok) {
                    Toast.makeText(this@FloatingMicService, "Enable Accessibility Service to auto-paste", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                Toast.makeText(this@FloatingMicService, "Gemini failed (${err.message}); using offline fallback", Toast.LENGTH_SHORT).show()
                runOfflineFallback()
            }
        }
    }

    private fun runOfflineFallback() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    DictationAccessibilityService.instance?.insertText(text)
                }
                sr.destroy()
            }
            override fun onError(error: Int) { sr.destroy() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(intent)
    }

    override fun onDestroy() {
        floatingView?.let { windowManager.removeView(it) }
        serviceScope.cancel()
        super.onDestroy()
    }
}

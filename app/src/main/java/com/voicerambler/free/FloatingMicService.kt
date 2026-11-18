package com.voicerambler.free

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
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
    private lateinit var recorder: AudioRecorderHelper
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isListening = false
    private var isProcessing = false
    private var beforeCursor = ""
    private var afterCursor = ""
    private var lastUndoFullText: String? = null
    private var lastUndoCursor: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        recorder = AudioRecorderHelper(this)
        startForegroundNotification()
        showFloatingBubble()

        DictationAccessibilityService.onEditableFocusChanged = { focused ->
            mainHandler.post {
                if (!isListening && !isProcessing) {
                    floatingView?.visibility = if (focused) View.VISIBLE else View.GONE
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
            .setContentTitle("VoiceRambler Free is active")
            .setContentText("Mic appears when typing. Tap to dictate, hold to undo, drag to move.")
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

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            if (!isDragging && !isListening && !isProcessing) {
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

        val touchTarget = ivMic ?: view
        touchTarget.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
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
                        if (!isListening) startDictation() else stopAndTranscribe()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
    }

    private fun spliceAtCursor(inserted: String): Pair<String, Int> {
        val needLeadingSpace = beforeCursor.isNotEmpty() && !beforeCursor.endsWith(" ") && !beforeCursor.endsWith("\n") && inserted.isNotEmpty()
        val needTrailingSpace = afterCursor.isNotEmpty() && !afterCursor.startsWith(" ") && !afterCursor.startsWith("\n") && inserted.isNotEmpty()
        val mid = buildString {
            if (needLeadingSpace) append(" ")
            append(inserted)
            if (needTrailingSpace) append(" ")
        }
        val full = beforeCursor + mid + afterCursor
        val cursorEnd = beforeCursor.length + (if (needLeadingSpace) 1 else 0) + inserted.length
        return Pair(full, cursorEnd)
    }

    private fun startDictation() {
        val ctx = DictationAccessibilityService.instance?.getCursorContext()
        beforeCursor = ctx?.first ?: ""
        afterCursor = ctx?.second ?: ""
        lastUndoFullText = beforeCursor + afterCursor
        lastUndoCursor = beforeCursor.length

        try {
            recorder.startRecording()
            isListening = true
            setMicActiveUi(true)
        } catch (e: Exception) {
            isListening = false
            setMicActiveUi(false)
            Toast.makeText(this, "Mic error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAndTranscribe() {
        val file = recorder.stopRecording()
        isListening = false
        setMicActiveUi(false)
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = Prefs.getApiKey(this)
        if (apiKey.isBlank()) {
            file.delete()
            Toast.makeText(this, "Add your Gemini API key in VoiceRambler first", Toast.LENGTH_LONG).show()
            return
        }

        isProcessing = true
        val model = Prefs.getModel(this)

        serviceScope.launch {
            val result = GeminiClient.transcribeAudio(apiKey, model, file)
            file.delete()
            isProcessing = false

            result.onSuccess { cleaned ->
                if (cleaned.isNotBlank()) {
                    val (full, pos) = spliceAtCursor(cleaned)
                    val ok = DictationAccessibilityService.instance?.setFullTextWithCursor(full, pos) ?: false
                    if (!ok) {
                        Toast.makeText(this@FloatingMicService, "Enable Accessibility Injector to paste", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { err ->
                Toast.makeText(this@FloatingMicService, "Error: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        DictationAccessibilityService.onEditableFocusChanged = null
        recorder.stopRecording()
        floatingView?.let { windowManager.removeView(it) }
        serviceScope.cancel()
        super.onDestroy()
    }
}

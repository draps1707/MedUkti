package com.voicerambler.free

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveClient(
    private val apiKey: String,
    private val onLiveTranscript: (String) -> Unit,
    private val onFinished: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private val isRunning = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    private val transcriptBuffer = StringBuilder()
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val setupTimeoutRunnable = Runnable {
        if (isRunning.get() && !isSetupComplete.get()) {
            abortWithError("Live setup timeout")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.getAndSet(true)) return
        isStopping.set(false)
        transcriptBuffer.clear()
        isSetupComplete.set(false)

        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        timeoutHandler.postDelayed(setupTimeoutRunnable, 5000L)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val inputConfig = JSONObject().apply {
                    put("customVocabulary", JSONArray(GeminiClient.MEDICAL_BIAS_TERMS))
                }

                val setupPayload = JSONObject().apply {
                    put("setup", JSONObject().apply {
                        put("model", "models/gemini-3.5-transcribe-live")
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", JSONArray().put("TEXT"))
                        })
                        put("inputAudioTranscription", inputConfig)
                    })
                }
                ws.send(setupPayload.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("setupComplete")) {
                        timeoutHandler.removeCallbacks(setupTimeoutRunnable)
                        isSetupComplete.set(true)
                        startPcmCapture(ws)
                        return
                    }

                    val serverContent = json.optJSONObject("serverContent") ?: return
                    val inputTrans = serverContent.optJSONObject("inputTranscription")
                    val chunk = inputTrans?.optString("text", "") ?: ""
                    if (chunk.isNotEmpty()) {
                        transcriptBuffer.append(chunk)
                        onLiveTranscript(transcriptBuffer.toString())
                    } else {
                        val modelTurn = serverContent.optJSONObject("modelTurn")
                        val parts = modelTurn?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val partText = parts.getJSONObject(0).optString("text", "")
                            if (partText.isNotEmpty()) {
                                transcriptBuffer.append(partText)
                                onLiveTranscript(transcriptBuffer.toString())
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                timeoutHandler.removeCallbacks(setupTimeoutRunnable)
                if (isStopping.get()) {
                    isRunning.set(false)
                    stopAudioOnly()
                    onFinished(transcriptBuffer.toString().trim())
                } else if (isRunning.getAndSet(false)) {
                    stopAudioOnly()
                    onError(t.message ?: "Live connection failed")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                timeoutHandler.removeCallbacks(setupTimeoutRunnable)
                if (isRunning.getAndSet(false)) {
                    stopAudioOnly()
                    onFinished(transcriptBuffer.toString().trim())
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startPcmCapture(ws: WebSocket) {
        Thread {
            try {
                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) {
                    abortWithError("Invalid audio buffer size")
                    return@Thread
                }
                val chunkBytes = 3200
                val bufSize = maxOf(minBuf, chunkBytes * 2)

                val ar = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    ar.release()
                    abortWithError("AudioRecord failed to initialize")
                    return@Thread
                }
                audioRecord = ar
                ar.startRecording()

                val buffer = ByteArray(chunkBytes)
                while (isRunning.get() && !isStopping.get()) {
                    val read = ar.read(buffer, 0, buffer.size)
                    if (read > 0 && isSetupComplete.get() && !isStopping.get()) {
                        val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        val msg = JSONObject().apply {
                            put("realtimeInput", JSONObject().apply {
                                put("audio", JSONObject().apply {
                                    put("mimeType", "audio/pcm;rate=16000")
                                    put("data", b64)
                                })
                            })
                        }
                        ws.send(msg.toString())
                    }
                }
            } catch (e: Exception) {
                abortWithError(e.message ?: "Mic capture error")
            }
        }.start()
    }

    private fun abortWithError(msg: String) {
        timeoutHandler.removeCallbacks(setupTimeoutRunnable)
        if (isRunning.getAndSet(false)) {
            stopAudioOnly()
            try {
                webSocket?.cancel()
            } catch (_: Exception) {
            }
            webSocket = null
            onError(msg)
        }
    }

    private fun stopAudioOnly() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    fun stopGracefully() {
        if (!isRunning.get() || isStopping.getAndSet(true)) return
        timeoutHandler.removeCallbacks(setupTimeoutRunnable)
        stopAudioOnly()

        try {
            val endMsg = JSONObject().apply {
                put("realtimeInput", JSONObject().put("audioStreamEnd", true))
            }
            webSocket?.send(endMsg.toString())
        } catch (_: Exception) {
        }

        // Wait briefly for trailing server transcription before closing
        timeoutHandler.postDelayed({
            if (isRunning.getAndSet(false)) {
                try {
                    webSocket?.close(1000, "Done")
                } catch (_: Exception) {
                }
                webSocket = null
                onFinished(transcriptBuffer.toString().trim())
            }
        }, 750L)
    }
}

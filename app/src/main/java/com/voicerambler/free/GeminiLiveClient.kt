package com.voicerambler.free

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    private val glossary: String,
    private val onLiveTranscript: (String) -> Unit,
    private val onFinished: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private val isRunning = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    private val transcriptBuffer = StringBuilder()

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.getAndSet(true)) return
        transcriptBuffer.clear()
        isSetupComplete.set(false)

        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val inputConfig = JSONObject()
                if (glossary.isNotBlank()) {
                    val terms = glossary.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(100)
                    if (terms.isNotEmpty()) {
                        inputConfig.put("customVocabulary", JSONArray(terms))
                    }
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
                if (isRunning.getAndSet(false)) {
                    stopAudioOnly()
                    onError(t.message ?: "Live connection failed")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
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
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val chunkBytes = 3200 // 100ms of 16kHz 16-bit mono PCM
            val bufSize = maxOf(minBuf, chunkBytes * 2)

            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            audioRecord = ar
            ar.startRecording()

            val buffer = ByteArray(chunkBytes)
            while (isRunning.get()) {
                val read = ar.read(buffer, 0, buffer.size)
                if (read > 0 && isSetupComplete.get()) {
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
        }.start()
    }

    private fun stopAudioOnly() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    fun stop(): String {
        val wasRunning = isRunning.getAndSet(false)
        stopAudioOnly()
        try {
            webSocket?.close(1000, "Done")
        } catch (_: Exception) {
        }
        webSocket = null
        return if (wasRunning) transcriptBuffer.toString().trim() else ""
    }
}

package com.medukti.keyboard

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MedUktiRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listening = AtomicBoolean(false)
    private var recJob: Job? = null
    private val captured = ArrayList<Float>()

    @SuppressLint("MissingPermission")
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        if (!CloudAsrClient.isNetworkAvailable(this)) {
            listener.error(SpeechRecognizer.ERROR_NETWORK)
            return
        }
        listening.set(true)
        synchronized(captured) { captured.clear() }
        listener.readyForSpeech(Bundle())
        recJob = scope.launch {
            val sr = 16000
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    listener.error(SpeechRecognizer.ERROR_AUDIO)
                    return@launch
                }
                rec.startRecording()
                listener.beginningOfSpeech()
                val buf = ShortArray(512)
                while (listening.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        synchronized(captured) {
                            for (i in 0 until n) captured.add(buf[i] / 32768.0f)
                        }
                    }
                }
            } catch (_: Exception) {
                listener.error(SpeechRecognizer.ERROR_AUDIO)
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
            }
        }
    }

    override fun onStopListening(listener: Callback?) {
        listening.set(false)
        scope.launch {
            recJob?.join()
            listener?.endOfSpeech()
            val samples = synchronized(captured) { captured.toFloatArray() }
            val engine = Prefs.getEngine(this@MedUktiRecognitionService)
            val res = CloudAsrClient.transcribeFinal(this@MedUktiRecognitionService, engine, samples)
            res.onSuccess { text ->
                val bundle = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                }
                listener?.results(bundle)
            }.onFailure {
                listener?.error(SpeechRecognizer.ERROR_NETWORK)
            }
        }
    }

    override fun onCancel(listener: Callback?) {
        listening.set(false)
        recJob?.cancel()
    }

    override fun onDestroy() {
        listening.set(false)
        scope.cancel()
        super.onDestroy()
    }
}

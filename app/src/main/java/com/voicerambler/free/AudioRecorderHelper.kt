package com.voicerambler.free

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorderHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L

    fun startRecording(): File {
        val file = File(context.cacheDir, "dictation_${System.currentTimeMillis()}.m4a")
        outputFile = file
        startTimeMs = System.currentTimeMillis()

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mr.apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(64000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mr
        return file
    }

    fun stopRecording(): File? {
        val duration = System.currentTimeMillis() - startTimeMs
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            if (duration < 400L) {
                outputFile?.delete()
                null
            } else {
                outputFile
            }
        } catch (_: Exception) {
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            null
        }
    }
}

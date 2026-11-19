package com.voicerambler.free

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class WavRecorderHelper(private val context: Context) {
    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var pcmStream = ByteArrayOutputStream()
    private var recordThread: Thread? = null
    private var startTimeMs = 0L

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording.getAndSet(true)) return
        pcmStream = ByteArrayOutputStream()
        startTimeMs = System.currentTimeMillis()

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, 4096)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            isRecording.set(false)
            ar.release()
            throw IllegalStateException("Mic failed to initialize")
        }
        audioRecord = ar
        ar.startRecording()

        recordThread = Thread {
            val buf = ByteArray(bufSize)
            while (isRecording.get()) {
                val read = ar.read(buf, 0, buf.size)
                if (read > 0) {
                    synchronized(pcmStream) {
                        pcmStream.write(buf, 0, read)
                    }
                }
            }
        }.apply { start() }
    }

    fun stopRecording(): File? {
        if (!isRecording.getAndSet(false)) return null
        val duration = System.currentTimeMillis() - startTimeMs
        try {
            recordThread?.join(500)
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        recordThread = null

        val pcmBytes = synchronized(pcmStream) { pcmStream.toByteArray() }
        if (duration < 350L || pcmBytes.size < 3200) return null

        val wavFile = File(context.cacheDir, "dictation_${System.currentTimeMillis()}.wav")
        writeWav(wavFile, pcmBytes, 16000, 1, 16)
        return wavFile
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val totalDataLen = pcm.size + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcm.size and 0xff).toByte()
        header[41] = ((pcm.size shr 8) and 0xff).toByte()
        header[42] = ((pcm.size shr 16) and 0xff).toByte()
        header[43] = ((pcm.size shr 24) and 0xff).toByte()

        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(pcm)
        }
    }
}

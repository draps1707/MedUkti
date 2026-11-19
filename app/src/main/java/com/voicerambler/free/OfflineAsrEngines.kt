package com.voicerambler.free

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object OfflineAsrEngines {

    fun resolveEngine(ctx: Context, engine: AsrEngineType): Result<AsrEngineType> {
        return when (engine) {
            AsrEngineType.WHISPER_HINGLISH_APEX -> {
                if (ModelDownloader.isWhisperApexInstalled(ctx)) Result.success(engine)
                else Result.failure(Exception("Download Hinglish Apex Pack first"))
            }
            AsrEngineType.INDIC_CONFORMER_OR -> {
                if (ModelDownloader.isOdiaInstalled(ctx)) Result.success(engine)
                else Result.failure(Exception("Download Odia Offline Pack first"))
            }
            AsrEngineType.GEMINI_CLOUD -> {
                if (Prefs.getApiKey(ctx).isNotBlank()) Result.success(engine)
                else Result.failure(Exception("Gemini API key not set"))
            }
        }
    }

    suspend fun preload(ctx: Context) = withContext(Dispatchers.IO) {
        val resolved = resolveEngine(ctx, Prefs.getEngine(ctx)).getOrNull() ?: return@withContext
        SherpaOnnxEngine.preload(ctx, resolved)
    }

    suspend fun transcribeSamples(
        ctx: Context,
        engine: AsrEngineType,
        samples: FloatArray
    ): Result<String> = withContext(Dispatchers.IO) {
        val resolved = resolveEngine(ctx, engine).getOrElse { return@withContext Result.failure(it) }
        when (resolved) {
            AsrEngineType.WHISPER_HINGLISH_APEX,
            AsrEngineType.INDIC_CONFORMER_OR -> SherpaOnnxEngine.transcribeSamples(ctx, resolved, samples)
            AsrEngineType.GEMINI_CLOUD -> {
                val key = Prefs.getApiKey(ctx)
                val tmp = File(ctx.cacheDir, "vad_seg_${System.currentTimeMillis()}.wav")
                writeFloatWav(tmp, samples)
                val res = GeminiClient.transcribeWav(key, Prefs.getModel(ctx), tmp)
                tmp.delete()
                res
            }
        }
    }

    suspend fun transcribe(
        ctx: Context,
        engine: AsrEngineType,
        wavFile: File
    ): Result<String> = withContext(Dispatchers.IO) {
        val resolved = resolveEngine(ctx, engine).getOrElse { return@withContext Result.failure(it) }
        when (resolved) {
            AsrEngineType.WHISPER_HINGLISH_APEX,
            AsrEngineType.INDIC_CONFORMER_OR -> SherpaOnnxEngine.transcribe(ctx, resolved, wavFile)
            AsrEngineType.GEMINI_CLOUD -> GeminiClient.transcribeWav(Prefs.getApiKey(ctx), Prefs.getModel(ctx), wavFile)
        }
    }

    private fun writeFloatWav(file: File, samples: FloatArray) {
        val pcmSize = samples.size * 2
        val totalDataLen = pcmSize + 36
        val sampleRate = 16000
        val byteRate = sampleRate * 2
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
        header[22] = 1; header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2; header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmSize and 0xff).toByte()
        header[41] = ((pcmSize shr 8) and 0xff).toByte()
        header[42] = ((pcmSize shr 16) and 0xff).toByte()
        header[43] = ((pcmSize shr 24) and 0xff).toByte()

        val pcm = ByteArray(pcmSize)
        var j = 0
        for (s in samples) {
            val v = (s * 32767.0f).toInt().coerceIn(-32768, 32767)
            pcm[j++] = (v and 0xff).toByte()
            pcm[j++] = ((v shr 8) and 0xff).toByte()
        }
        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(pcm)
        }
    }
}

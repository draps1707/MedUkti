package com.voicerambler.free

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        SherpaOnnxEngine.transcribeSamples(ctx, resolved, samples)
    }

    suspend fun transcribe(
        ctx: Context,
        engine: AsrEngineType,
        wavFile: File
    ): Result<String> = withContext(Dispatchers.IO) {
        val resolved = resolveEngine(ctx, engine).getOrElse { return@withContext Result.failure(it) }
        SherpaOnnxEngine.transcribe(ctx, resolved, wavFile)
    }
}

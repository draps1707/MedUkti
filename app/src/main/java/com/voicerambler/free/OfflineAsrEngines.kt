package com.voicerambler.free

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object OfflineAsrEngines {

    fun resolveEngine(ctx: Context, engine: AsrEngineType): Result<AsrEngineType> {
        return when (engine) {
            AsrEngineType.WHISPER_TURBO -> {
                if (ModelDownloader.isWhisperTurboInstalled(ctx)) Result.success(engine)
                else Result.failure(Exception("Download Whisper Large-v3-Turbo Pack first"))
            }
            AsrEngineType.PARAKEET_INDIC -> {
                if (ModelDownloader.isParakeetIndicInstalled(ctx)) Result.success(engine)
                else Result.failure(Exception("Download Parakeet-Indic v1 Pack first"))
            }
            AsrEngineType.SRAVAANI_OR -> {
                if (ModelDownloader.isSraVaaniInstalled(ctx)) Result.success(engine)
                else Result.failure(Exception("Download SraVaani 1.0 Odia Pack first"))
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

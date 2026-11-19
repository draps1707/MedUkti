package com.voicerambler.free

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object OfflineAsrEngines {

    suspend fun transcribe(
        ctx: Context,
        engine: AsrEngineType,
        wavFile: File
    ): Result<String> = withContext(Dispatchers.IO) {
        val resolved = if (engine == AsrEngineType.AUTO_OFFLINE) {
            when {
                ModelDownloader.isInstalled(ctx, ModelDownloader.WHISPER_SWIFT_ENC) &&
                    ModelDownloader.isInstalled(ctx, ModelDownloader.WHISPER_SWIFT_DEC) -> AsrEngineType.WHISPER_HINGLISH_SWIFT
                ModelDownloader.isInstalled(ctx, ModelDownloader.INDIC_HI) -> AsrEngineType.INDIC_CONFORMER_HI
                ModelDownloader.isInstalled(ctx, ModelDownloader.INDIC_OR) -> AsrEngineType.INDIC_CONFORMER_OR
                Prefs.getApiKey(ctx).isNotBlank() -> AsrEngineType.GEMINI_CLOUD
                else -> return@withContext Result.failure(Exception("Tap 'Download All Offline Models' in VoiceRambler first"))
            }
        } else {
            engine
        }

        when (resolved) {
            AsrEngineType.INDIC_CONFORMER_OR,
            AsrEngineType.INDIC_CONFORMER_HI,
            AsrEngineType.WHISPER_HINGLISH_SWIFT -> SherpaOnnxEngine.transcribe(ctx, resolved, wavFile)
            AsrEngineType.GEMINI_CLOUD -> {
                val key = Prefs.getApiKey(ctx)
                if (key.isBlank()) {
                    Result.failure(Exception("Gemini API key not set"))
                } else {
                    GeminiClient.transcribeWav(key, Prefs.getModel(ctx), wavFile)
                }
            }
            AsrEngineType.AUTO_OFFLINE -> Result.failure(Exception("No model installed"))
        }
    }
}

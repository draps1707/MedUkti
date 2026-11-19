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
        when (engine) {
            AsrEngineType.INDIC_CONFORMER_OR,
            AsrEngineType.INDIC_CONFORMER_HI,
            AsrEngineType.WHISPER_HINGLISH_SWIFT -> SherpaOnnxEngine.transcribe(ctx, engine, wavFile)
            AsrEngineType.SHRUTI_HI_EN -> runShrutiNeedle(ctx, ModelDownloader.SHRUTI_HI_EN, wavFile)
            AsrEngineType.SHRUTI_ODIA -> runShrutiNeedle(ctx, ModelDownloader.SHRUTI_ODIA, wavFile)
            AsrEngineType.GEMINI_CLOUD -> {
                val key = Prefs.getApiKey(ctx)
                if (key.isBlank()) {
                    Result.failure(Exception("Gemini API key not set"))
                } else {
                    GeminiClient.transcribeWav(key, Prefs.getModel(ctx), wavFile)
                }
            }
        }
    }

    private fun runShrutiNeedle(
        ctx: Context,
        modelArtifact: ModelDownloader.ModelArtifact,
        wavFile: File
    ): Result<String> {
        val bin = ModelDownloader.getFile(ctx, ModelDownloader.NEEDLE_BIN)
        val model = ModelDownloader.getFile(ctx, modelArtifact)
        if (!bin.exists() || !model.exists()) {
            return Result.failure(Exception("Download ${modelArtifact.fileName} & needle engine first"))
        }
        bin.setExecutable(true, false)

        return try {
            val pb = ProcessBuilder(
                bin.absolutePath,
                "--model", model.absolutePath,
                "--audio", wavFile.absolutePath
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            val exit = proc.waitFor()
            if (exit != 0) {
                Result.failure(Exception("Shruti exit $exit: $output"))
            } else {
                val cleaned = output.lines().lastOrNull { it.isNotBlank() }?.trim() ?: ""
                Result.success(cleaned)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

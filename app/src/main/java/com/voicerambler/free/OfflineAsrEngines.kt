package com.voicerambler.free

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object OfflineAsrEngines {

    fun resolveEngine(ctx: Context, engine: AsrEngineType): Result<AsrEngineType> {
        return when (engine) {
            AsrEngineType.INDIC_CONFORMER_OR -> {
                if (ModelDownloader.isOdiaInstalled(ctx)) Result.success(engine)
                else Result.failure(Exception("Download Odia Offline Pack first"))
            }
            AsrEngineType.SRAVAANI_OR -> {
                if (ModelDownloader.isSraVaaniInstalled(ctx)) Result.success(engine)
                else Result.failure(Exception("Download SraVaani 1.0 Odia Pack first"))
            }
            AsrEngineType.DUAL_ODIA_INDIC_SRAVAANI -> {
                val iOk = ModelDownloader.isOdiaInstalled(ctx)
                val sOk = ModelDownloader.isSraVaaniInstalled(ctx)
                when {
                    iOk && sOk -> Result.success(engine)
                    iOk -> Result.success(AsrEngineType.INDIC_CONFORMER_OR)
                    sOk -> Result.success(AsrEngineType.SRAVAANI_OR)
                    else -> Result.failure(Exception("Download Odia Offline Pack or SraVaani 1.0 first"))
                }
            }
            AsrEngineType.MEDASR_CTC -> {
                if (ModelDownloader.isMedAsrInstalled(ctx)) Result.success(engine)
                else Result.failure(Exception("Download Google MedASR Pack first"))
            }
            AsrEngineType.PARAKEET_110M -> {
                if (ModelDownloader.isParakeet110mInstalled(ctx)) Result.success(engine)
                else Result.failure(Exception("Download Parakeet 110M Pack first"))
            }
            AsrEngineType.PARAKEET_06B_V2 -> {
                if (ModelDownloader.isParakeet06bInstalled(ctx)) Result.success(engine)
                else Result.failure(Exception("Download Parakeet 0.6B v2 Pack first"))
            }
            AsrEngineType.DUAL_PARAKEET_110M_MEDASR -> {
                val pOk = ModelDownloader.isParakeet110mInstalled(ctx)
                val mOk = ModelDownloader.isMedAsrInstalled(ctx)
                when {
                    pOk && mOk -> Result.success(engine)
                    mOk -> Result.success(AsrEngineType.MEDASR_CTC)
                    pOk -> Result.success(AsrEngineType.PARAKEET_110M)
                    else -> Result.failure(Exception("Download Parakeet 110M & Google MedASR first"))
                }
            }
            AsrEngineType.DUAL_PARAKEET_06B_MEDASR -> {
                val pOk = ModelDownloader.isParakeet06bInstalled(ctx)
                val mOk = ModelDownloader.isMedAsrInstalled(ctx)
                when {
                    pOk && mOk -> Result.success(engine)
                    mOk -> Result.success(AsrEngineType.MEDASR_CTC)
                    pOk -> Result.success(AsrEngineType.PARAKEET_06B_V2)
                    else -> Result.failure(Exception("Download Parakeet 0.6B v2 & Google MedASR first"))
                }
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

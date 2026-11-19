package com.voicerambler.free

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {

    data class ModelArtifact(
        val id: String,
        val fileName: String,
        val url: String
    )

    val SILERO_VAD = ModelArtifact(
        "silero_vad",
        "silero_vad.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    )

    val INDIC_TOKENS = ModelArtifact(
        "indic_tokens",
        "indicconformer-tokens.txt",
        "https://huggingface.co/Docglasses-AI/indic-conformer-600m-sherpa-onnx-int8/resolve/main/indicconformer-tokens.txt"
    )

    val INDIC_OR = ModelArtifact(
        "indic_or",
        "indicconformer-or.int8.onnx",
        "https://huggingface.co/Docglasses-AI/indic-conformer-600m-sherpa-onnx-int8/resolve/main/indicconformer-or.int8.onnx"
    )

    val SRAVAANI_ENC = ModelArtifact(
        "sravaani_enc",
        "sravaani-encoder.onnx",
        "https://huggingface.co/killbanhar/sravaani-onnx/resolve/main/encoder.onnx"
    )

    val SRAVAANI_DEC = ModelArtifact(
        "sravaani_dec",
        "sravaani-decoder-joint.onnx",
        "https://huggingface.co/killbanhar/sravaani-onnx/resolve/main/decoder_joint.onnx"
    )

    val MEDASR_MODEL = ModelArtifact(
        "medasr_model",
        "medasr-model.int8.onnx",
        "https://huggingface.co/csukuangfj/sherpa-onnx-medasr-ctc-en-int8-2025-12-25/resolve/main/model.int8.onnx"
    )

    val MEDASR_TOKENS = ModelArtifact(
        "medasr_tokens",
        "medasr-tokens.txt",
        "https://huggingface.co/csukuangfj/sherpa-onnx-medasr-ctc-en-int8-2025-12-25/resolve/main/tokens.txt"
    )

    val PARAKEET_110M_ENC = ModelArtifact(
        "parakeet_110m_enc",
        "parakeet-110m-encoder.int8.onnx",
        "https://huggingface.co/akm9191/sherpa-onnx-parakeet-tdt-110m-int8/resolve/main/encoder.int8.onnx"
    )

    val PARAKEET_110M_DEC = ModelArtifact(
        "parakeet_110m_dec",
        "parakeet-110m-decoder.int8.onnx",
        "https://huggingface.co/akm9191/sherpa-onnx-parakeet-tdt-110m-int8/resolve/main/decoder.int8.onnx"
    )

    val PARAKEET_110M_JOINER = ModelArtifact(
        "parakeet_110m_joiner",
        "parakeet-110m-joiner.int8.onnx",
        "https://huggingface.co/akm9191/sherpa-onnx-parakeet-tdt-110m-int8/resolve/main/joiner.int8.onnx"
    )

    val PARAKEET_110M_TOKENS = ModelArtifact(
        "parakeet_110m_tokens",
        "parakeet-110m-tokens.txt",
        "https://huggingface.co/akm9191/sherpa-onnx-parakeet-tdt-110m-int8/resolve/main/tokens.txt"
    )

    val PARAKEET_06B_ENC = ModelArtifact(
        "parakeet_06b_enc",
        "parakeet-06b-encoder.int8.onnx",
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/encoder.int8.onnx"
    )

    val PARAKEET_06B_DEC = ModelArtifact(
        "parakeet_06b_dec",
        "parakeet-06b-decoder.int8.onnx",
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/decoder.int8.onnx"
    )

    val PARAKEET_06B_JOINER = ModelArtifact(
        "parakeet_06b_joiner",
        "parakeet-06b-joiner.int8.onnx",
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/joiner.int8.onnx"
    )

    val PARAKEET_06B_TOKENS = ModelArtifact(
        "parakeet_06b_tokens",
        "parakeet-06b-tokens.txt",
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/tokens.txt"
    )

    val STREAMING_ZIP_ENC = ModelArtifact(
        "streaming_zip_enc",
        "streaming-zipformer-en-encoder.int8.onnx",
        "https://huggingface.co/akm9191/sherpa-onnx-streaming-zipformer-en-int8/resolve/main/encoder.int8.onnx"
    )

    val STREAMING_ZIP_DEC = ModelArtifact(
        "streaming_zip_dec",
        "streaming-zipformer-en-decoder.int8.onnx",
        "https://huggingface.co/akm9191/sherpa-onnx-streaming-zipformer-en-int8/resolve/main/decoder.int8.onnx"
    )

    val STREAMING_ZIP_JOINER = ModelArtifact(
        "streaming_zip_joiner",
        "streaming-zipformer-en-joiner.int8.onnx",
        "https://huggingface.co/akm9191/sherpa-onnx-streaming-zipformer-en-int8/resolve/main/joiner.int8.onnx"
    )

    val STREAMING_ZIP_TOKENS = ModelArtifact(
        "streaming_zip_tok",
        "streaming-zipformer-en-tokens.txt",
        "https://huggingface.co/akm9191/sherpa-onnx-streaming-zipformer-en-int8/resolve/main/tokens.txt"
    )

    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }

    fun getFile(ctx: Context, artifact: ModelArtifact): File = File(modelsDir(ctx), artifact.fileName)

    fun isInstalled(ctx: Context, artifact: ModelArtifact): Boolean {
        val f = getFile(ctx, artifact)
        return f.exists() && f.length() > 512L
    }

    fun isOdiaInstalled(ctx: Context): Boolean =
        isInstalled(ctx, INDIC_OR) && isInstalled(ctx, INDIC_TOKENS)

    fun isSraVaaniInstalled(ctx: Context): Boolean =
        isInstalled(ctx, SRAVAANI_ENC) && isInstalled(ctx, SRAVAANI_DEC)

    fun isMedAsrInstalled(ctx: Context): Boolean =
        isInstalled(ctx, MEDASR_MODEL) && isInstalled(ctx, MEDASR_TOKENS)

    fun isParakeet110mInstalled(ctx: Context): Boolean =
        isInstalled(ctx, PARAKEET_110M_ENC) &&
            isInstalled(ctx, PARAKEET_110M_DEC) &&
            isInstalled(ctx, PARAKEET_110M_JOINER) &&
            isInstalled(ctx, PARAKEET_110M_TOKENS)

    fun isParakeet06bInstalled(ctx: Context): Boolean =
        isInstalled(ctx, PARAKEET_06B_ENC) &&
            isInstalled(ctx, PARAKEET_06B_DEC) &&
            isInstalled(ctx, PARAKEET_06B_JOINER) &&
            isInstalled(ctx, PARAKEET_06B_TOKENS)

    fun isStreamingZipformerInstalled(ctx: Context): Boolean =
        isInstalled(ctx, STREAMING_ZIP_ENC) &&
            isInstalled(ctx, STREAMING_ZIP_DEC) &&
            isInstalled(ctx, STREAMING_ZIP_JOINER) &&
            isInstalled(ctx, STREAMING_ZIP_TOKENS)

    fun cleanupUnusedModels(ctx: Context) {
        val keep = setOf(
            SILERO_VAD.fileName,
            INDIC_TOKENS.fileName,
            INDIC_OR.fileName,
            SRAVAANI_ENC.fileName,
            SRAVAANI_DEC.fileName,
            MEDASR_MODEL.fileName,
            MEDASR_TOKENS.fileName,
            PARAKEET_110M_ENC.fileName,
            PARAKEET_110M_DEC.fileName,
            PARAKEET_110M_JOINER.fileName,
            PARAKEET_110M_TOKENS.fileName,
            PARAKEET_06B_ENC.fileName,
            PARAKEET_06B_DEC.fileName,
            PARAKEET_06B_JOINER.fileName,
            PARAKEET_06B_TOKENS.fileName
        )
        modelsDir(ctx).listFiles()?.forEach { f ->
            if (f.name !in keep && !f.name.endsWith(".tmp")) {
                f.delete()
            }
        }
    }

    private fun openWithRedirects(startUrl: String): HttpURLConnection {
        var current = startUrl
        for (hop in 0..8) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", "VoiceRambler/1.0")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw Exception("Empty redirect for $current")
                current = URL(URL(current), loc).toString()
                continue
            }
            return conn
        }
        throw Exception("Too many redirects for $startUrl")
    }

    suspend fun download(
        ctx: Context,
        artifact: ModelArtifact,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val dest = getFile(ctx, artifact)
            val tmp = File(modelsDir(ctx), "${artifact.fileName}.tmp")
            conn = openWithRedirects(artifact.url)
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("Download HTTP $code for ${artifact.fileName}"))
            }

            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
            onProgress(100)
            Result.success(dest)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}

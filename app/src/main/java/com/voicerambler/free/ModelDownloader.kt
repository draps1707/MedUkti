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
        val url: String,
        val requiresAuth: Boolean = false
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

    // Indian-English Streaming Zipformer (~71 MB INT8)
    val STREAMING_ZIP_ENC = ModelArtifact(
        "streaming_zip_enc",
        "streaming-zipformer-in-encoder.int8.onnx",
        "https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en/resolve/main/encoder-epoch-10-avg-5-chunk-64-left-256.int8.onnx"
    )

    val STREAMING_ZIP_DEC = ModelArtifact(
        "streaming_zip_dec",
        "streaming-zipformer-in-decoder.int8.onnx",
        "https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en/resolve/main/decoder-epoch-10-avg-5-chunk-64-left-256.int8.onnx"
    )

    val STREAMING_ZIP_JOINER = ModelArtifact(
        "streaming_zip_joiner",
        "streaming-zipformer-in-joiner.int8.onnx",
        "https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en/resolve/main/joiner-epoch-10-avg-5-chunk-64-left-256.int8.onnx"
    )

    val STREAMING_ZIP_TOKENS = ModelArtifact(
        "streaming_zip_tok",
        "streaming-zipformer-in-tokens.txt",
        "https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en/resolve/main/tokens.txt"
    )

    // Whisper Large-v3-Turbo INT8 (~1.04 GB total)
    val WHISPER_TURBO_ENC = ModelArtifact(
        "whisper_turbo_enc",
        "whisper-turbo-encoder.int8.onnx",
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-encoder.int8.onnx"
    )

    val WHISPER_TURBO_DEC = ModelArtifact(
        "whisper_turbo_dec",
        "whisper-turbo-decoder.int8.onnx",
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-decoder.int8.onnx"
    )

    val WHISPER_TURBO_TOKENS = ModelArtifact(
        "whisper_turbo_tok",
        "whisper-turbo-tokens.txt",
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-tokens.txt"
    )

    // Parakeet-Indic v1 INT8 (~850 MB, gated on Hugging Face)
    val PARAKEET_INDIC_ENC = ModelArtifact(
        "parakeet_indic_enc",
        "parakeet-indic-encoder.int8.onnx",
        "https://huggingface.co/amn-raw/parakeet-indic-v1/resolve/main/onnx/encoder.int8.onnx",
        requiresAuth = true
    )

    val PARAKEET_INDIC_DEC = ModelArtifact(
        "parakeet_indic_dec",
        "parakeet-indic-decoder.int8.onnx",
        "https://huggingface.co/amn-raw/parakeet-indic-v1/resolve/main/onnx/decoder.int8.onnx",
        requiresAuth = true
    )

    val PARAKEET_INDIC_JOINER = ModelArtifact(
        "parakeet_indic_joiner",
        "parakeet-indic-joiner.int8.onnx",
        "https://huggingface.co/amn-raw/parakeet-indic-v1/resolve/main/onnx/joiner.int8.onnx",
        requiresAuth = true
    )

    val PARAKEET_INDIC_TOKENS = ModelArtifact(
        "parakeet_indic_tok",
        "parakeet-indic-tokens.txt",
        "https://huggingface.co/amn-raw/parakeet-indic-v1/resolve/main/onnx/tokens.txt",
        requiresAuth = true
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

    fun isWhisperTurboInstalled(ctx: Context): Boolean =
        isInstalled(ctx, WHISPER_TURBO_ENC) &&
            isInstalled(ctx, WHISPER_TURBO_DEC) &&
            isInstalled(ctx, WHISPER_TURBO_TOKENS)

    fun isParakeetIndicInstalled(ctx: Context): Boolean =
        isInstalled(ctx, PARAKEET_INDIC_ENC) &&
            isInstalled(ctx, PARAKEET_INDIC_DEC) &&
            isInstalled(ctx, PARAKEET_INDIC_JOINER) &&
            isInstalled(ctx, PARAKEET_INDIC_TOKENS)

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
            PARAKEET_06B_TOKENS.fileName,
            STREAMING_ZIP_ENC.fileName,
            STREAMING_ZIP_DEC.fileName,
            STREAMING_ZIP_JOINER.fileName,
            STREAMING_ZIP_TOKENS.fileName,
            WHISPER_TURBO_ENC.fileName,
            WHISPER_TURBO_DEC.fileName,
            WHISPER_TURBO_TOKENS.fileName,
            PARAKEET_INDIC_ENC.fileName,
            PARAKEET_INDIC_DEC.fileName,
            PARAKEET_INDIC_JOINER.fileName,
            PARAKEET_INDIC_TOKENS.fileName
        )
        modelsDir(ctx).listFiles()?.forEach { f ->
            if (f.name !in keep && !f.name.endsWith(".tmp")) {
                f.delete()
            }
        }
    }

    private fun openWithRedirects(startUrl: String, bearerToken: String?): HttpURLConnection {
        var current = startUrl
        for (hop in 0..8) {
            val urlObj = URL(current)
            val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", "MedUkti/4.1")
                if (!bearerToken.isNullOrBlank() && urlObj.host.endsWith("huggingface.co")) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw Exception("Empty redirect for $current")
                current = URL(urlObj, loc).toString()
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
            val token = if (artifact.requiresAuth) Prefs.getHfToken(ctx).trim() else ""
            if (artifact.requiresAuth && token.isEmpty()) {
                return@withContext Result.failure(Exception("Paste your Hugging Face Token (hf_...) in Settings first"))
            }
            conn = openWithRedirects(artifact.url, token)
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                return@withContext Result.failure(Exception("HF access denied ($code): check token & repo approval"))
            }
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

package com.medukti.keyboard

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
    fun isSraVaaniInstalled(ctx: Context): Boolean =
        isInstalled(ctx, SRAVAANI_ENC) && isInstalled(ctx, SRAVAANI_DEC)

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
            SRAVAANI_ENC.fileName,
            SRAVAANI_DEC.fileName,
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
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
            withContext(Dispatchers.Main) { onProgress(100) }
            Result.success(dest)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}

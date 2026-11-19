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

    val WHISPER_APEX_ENC = ModelArtifact(
        "whisper_apex_enc",
        "hinglish-apex-encoder.int8.onnx",
        "https://huggingface.co/NiranjanYadavIN/indicconformer-sherpa-onnx/resolve/main/hi-hinglish-apex/encoder.int8.onnx"
    )

    val WHISPER_APEX_DEC = ModelArtifact(
        "whisper_apex_dec",
        "hinglish-apex-decoder.int8.onnx",
        "https://huggingface.co/NiranjanYadavIN/indicconformer-sherpa-onnx/resolve/main/hi-hinglish-apex/decoder.int8.onnx"
    )

    val WHISPER_APEX_TOKENS = ModelArtifact(
        "whisper_apex_tok",
        "hinglish-apex-tokens.txt",
        "https://huggingface.co/NiranjanYadavIN/indicconformer-sherpa-onnx/resolve/main/hi-hinglish-apex/tokens.txt"
    )

    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }

    fun getFile(ctx: Context, artifact: ModelArtifact): File = File(modelsDir(ctx), artifact.fileName)

    fun isInstalled(ctx: Context, artifact: ModelArtifact): Boolean {
        val f = getFile(ctx, artifact)
        return f.exists() && f.length() > 512L
    }

    fun isWhisperApexInstalled(ctx: Context): Boolean =
        isInstalled(ctx, WHISPER_APEX_ENC) &&
            isInstalled(ctx, WHISPER_APEX_DEC) &&
            isInstalled(ctx, WHISPER_APEX_TOKENS)

    fun isOdiaInstalled(ctx: Context): Boolean =
        isInstalled(ctx, INDIC_OR) && isInstalled(ctx, INDIC_TOKENS)

    fun cleanupUnusedModels(ctx: Context) {
        val keep = setOf(
            SILERO_VAD.fileName,
            INDIC_TOKENS.fileName,
            INDIC_OR.fileName,
            WHISPER_APEX_ENC.fileName,
            WHISPER_APEX_DEC.fileName,
            WHISPER_APEX_TOKENS.fileName
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

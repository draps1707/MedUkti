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
        val executable: Boolean = false
    )

    val NEEDLE_BIN = ModelArtifact(
        "needle",
        "needle",
        "https://huggingface.co/Cactus-Compute/needle3/resolve/main/android-arm64/needle",
        executable = true
    )

    val SHRUTI_HI_EN = ModelArtifact(
        "shruti_hi",
        "shruti.cact",
        "https://huggingface.co/udgamlabs/Shruti/resolve/main/shruti.cact"
    )

    val SHRUTI_ODIA = ModelArtifact(
        "shruti_or",
        "shruti-odia.cact",
        "https://huggingface.co/udgamlabs/shruti-odia/resolve/main/shruti-odia.cact"
    )

    val INDIC_TOKENS = ModelArtifact(
        "indic_tokens",
        "indicconformer-tokens.txt",
        "https://huggingface.co/Docglasses-AI/indic-conformer-600m-sherpa-onnx-int8/resolve/main/indicconformer-tokens.txt"
    )

    val INDIC_HI = ModelArtifact(
        "indic_hi",
        "indicconformer-hi.int8.onnx",
        "https://huggingface.co/Docglasses-AI/indic-conformer-600m-sherpa-onnx-int8/resolve/main/indicconformer-hi.int8.onnx"
    )

    val INDIC_OR = ModelArtifact(
        "indic_or",
        "indicconformer-or.int8.onnx",
        "https://huggingface.co/Docglasses-AI/indic-conformer-600m-sherpa-onnx-int8/resolve/main/indicconformer-or.int8.onnx"
    )

    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }

    fun getFile(ctx: Context, artifact: ModelArtifact): File = File(modelsDir(ctx), artifact.fileName)

    fun isInstalled(ctx: Context, artifact: ModelArtifact): Boolean {
        val f = getFile(ctx, artifact)
        return f.exists() && f.length() > 1024L
    }

    suspend fun download(
        ctx: Context,
        artifact: ModelArtifact,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dest = getFile(ctx, artifact)
            val tmp = File(modelsDir(ctx), "${artifact.fileName}.tmp")
            val conn = (URL(artifact.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 60000
            }
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
            if (artifact.executable) {
                dest.setExecutable(true, false)
            }
            onProgress(100)
            Result.success(dest)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

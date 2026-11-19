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

    val SILERO_VAD = ModelArtifact(
        "silero_vad",
        "silero_vad.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    )

    val INDIAN_EN_ENC = ModelArtifact(
        "indian_en_enc",
        "indian-en-encoder.int8.onnx",
        "https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en/resolve/main/encoder-epoch-10-avg-5-chunk-64-left-256.int8.onnx"
    )

    val INDIAN_EN_DEC = ModelArtifact(
        "indian_en_dec",
        "indian-en-decoder.int8.onnx",
        "https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en/resolve/main/decoder-epoch-10-avg-5-chunk-64-left-256.int8.onnx"
    )

    val INDIAN_EN_JOINER = ModelArtifact(
        "indian_en_joiner",
        "indian-en-joiner.int8.onnx",
        "https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en/resolve/main/joiner-epoch-10-avg-5-chunk-64-left-256.int8.onnx"
    )

    val INDIAN_EN_TOKENS = ModelArtifact(
        "indian_en_tokens",
        "indian-en-tokens.txt",
        "https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en/resolve/main/tokens.txt"
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

    val WHISPER_SWIFT_ENC = ModelArtifact(
        "whisper_swift_enc",
        "hinglish-swift-encoder.int8.onnx",
        "https://huggingface.co/NiranjanYadavIN/indicconformer-sherpa-onnx/resolve/main/hi-hinglish-swift/encoder.int8.onnx"
    )

    val WHISPER_SWIFT_DEC = ModelArtifact(
        "whisper_swift_dec",
        "hinglish-swift-decoder.int8.onnx",
        "https://huggingface.co/NiranjanYadavIN/indicconformer-sherpa-onnx/resolve/main/hi-hinglish-swift/decoder.int8.onnx"
    )

    val WHISPER_SWIFT_TOKENS = ModelArtifact(
        "whisper_swift_tok",
        "hinglish-swift-tokens.txt",
        "https://huggingface.co/NiranjanYadavIN/indicconformer-sherpa-onnx/resolve/main/hi-hinglish-swift/tokens.txt"
    )

    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }

    fun getFile(ctx: Context, artifact: ModelArtifact): File = File(modelsDir(ctx), artifact.fileName)

    fun isInstalled(ctx: Context, artifact: ModelArtifact): Boolean {
        val f = getFile(ctx, artifact)
        return f.exists() && f.length() > 512L
    }

    fun isIndianEnInstalled(ctx: Context): Boolean =
        isInstalled(ctx, INDIAN_EN_ENC) &&
            isInstalled(ctx, INDIAN_EN_DEC) &&
            isInstalled(ctx, INDIAN_EN_JOINER) &&
            isInstalled(ctx, INDIAN_EN_TOKENS)

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

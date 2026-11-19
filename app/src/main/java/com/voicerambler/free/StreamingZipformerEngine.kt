package com.voicerambler.free

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

object StreamingZipformerEngine {

    private var recognizer: OnlineRecognizer? = null

    @Synchronized
    fun getOrCreate(ctx: Context): OnlineRecognizer? {
        if (!ModelDownloader.isStreamingZipformerInstalled(ctx)) return null
        recognizer?.let { return it }
        val enc = ModelDownloader.getFile(ctx, ModelDownloader.STREAMING_ZIP_ENC)
        val dec = ModelDownloader.getFile(ctx, ModelDownloader.STREAMING_ZIP_DEC)
        val joiner = ModelDownloader.getFile(ctx, ModelDownloader.STREAMING_ZIP_JOINER)
        val tok = ModelDownloader.getFile(ctx, ModelDownloader.STREAMING_ZIP_TOKENS)

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = enc.absolutePath,
                    decoder = dec.absolutePath,
                    joiner = joiner.absolutePath
                ),
                tokens = tok.absolutePath,
                numThreads = 2,
                debug = false
            ),
            enableEndpoint = false
        )
        return OnlineRecognizer(assetManager = null, config = config).also { recognizer = it }
    }

    fun createStream(ctx: Context): OnlineStream? = getOrCreate(ctx)?.createStream()

    fun acceptAndDecode(stream: OnlineStream, samples: FloatArray): String {
        val rec = recognizer ?: return ""
        stream.acceptWaveform(samples, 16000)
        while (rec.isReady(stream)) {
            rec.decode(stream)
        }
        return rec.getResult(stream).text.trim().lowercase()
    }

    @Synchronized
    fun release() {
        try {
            recognizer?.release()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}

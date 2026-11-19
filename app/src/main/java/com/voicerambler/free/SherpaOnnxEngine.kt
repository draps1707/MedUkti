package com.voicerambler.free

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

object SherpaOnnxEngine {

    private var cachedEngineType: AsrEngineType? = null
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    private fun readWavAsFloats(wavFile: File): FloatArray {
        val bytes = wavFile.readBytes()
        if (bytes.size <= 44) return FloatArray(0)
        val numSamples = (bytes.size - 44) / 2
        val floats = FloatArray(numSamples)
        var idx = 44
        for (i in 0 until numSamples) {
            val lo = bytes[idx].toInt() and 0xFF
            val hi = bytes[idx + 1].toInt()
            val sample = (hi shl 8) or lo
            floats[i] = sample / 32768.0f
            idx += 2
        }
        return floats
    }

    private fun extractSpeechWithVad(ctx: Context, samples: FloatArray): List<FloatArray> {
        if (!ModelDownloader.isInstalled(ctx, ModelDownloader.SILERO_VAD)) {
            return listOf(samples)
        }
        if (vad == null) {
            val vadFile = ModelDownloader.getFile(ctx, ModelDownloader.SILERO_VAD)
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = 0.45f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.20f,
                    windowSize = 512
                ),
                sampleRate = 16000,
                numThreads = 1,
                debug = false
            )
            vad = Vad(assetManager = null, config = vadConfig)
        }

        val v = vad ?: return listOf(samples)
        v.reset()
        val windowSize = 512
        var offset = 0
        while (offset + windowSize <= samples.size) {
            v.acceptWaveform(samples.copyOfRange(offset, offset + windowSize))
            offset += windowSize
        }
        v.flush()

        val segments = mutableListOf<FloatArray>()
        while (!v.empty()) {
            val seg = v.front()
            if (seg.samples.isNotEmpty()) {
                segments.add(seg.samples)
            }
            v.pop()
        }
        return if (segments.isEmpty()) emptyList() else segments
    }

    @Synchronized
    private fun getOrCreateRecognizer(ctx: Context, engine: AsrEngineType): OfflineRecognizer {
        if (recognizer != null && cachedEngineType == engine) {
            return recognizer!!
        }
        recognizer?.release()
        recognizer = null

        val modelConfig = when (engine) {
            AsrEngineType.INDIC_CONFORMER_OR -> {
                val model = ModelDownloader.getFile(ctx, ModelDownloader.INDIC_OR)
                val tokens = ModelDownloader.getFile(ctx, ModelDownloader.INDIC_TOKENS)
                if (!model.exists() || !tokens.exists()) {
                    throw IllegalStateException("Download IndicConformer Odia model first")
                }
                OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                    tokens = tokens.absolutePath,
                    numThreads = 4,
                    debug = false
                )
            }
            AsrEngineType.INDIC_CONFORMER_HI -> {
                val model = ModelDownloader.getFile(ctx, ModelDownloader.INDIC_HI)
                val tokens = ModelDownloader.getFile(ctx, ModelDownloader.INDIC_TOKENS)
                if (!model.exists() || !tokens.exists()) {
                    throw IllegalStateException("Download IndicConformer Hindi model first")
                }
                OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                    tokens = tokens.absolutePath,
                    numThreads = 4,
                    debug = false
                )
            }
            AsrEngineType.WHISPER_HINGLISH_SWIFT -> {
                val enc = ModelDownloader.getFile(ctx, ModelDownloader.WHISPER_SWIFT_ENC)
                val dec = ModelDownloader.getFile(ctx, ModelDownloader.WHISPER_SWIFT_DEC)
                val tok = ModelDownloader.getFile(ctx, ModelDownloader.WHISPER_SWIFT_TOKENS)
                if (!enc.exists() || !dec.exists() || !tok.exists()) {
                    throw IllegalStateException("Download Whisper Hinglish Swift model first")
                }
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = enc.absolutePath,
                        decoder = dec.absolutePath,
                        language = "en",
                        task = "transcribe"
                    ),
                    tokens = tok.absolutePath,
                    numThreads = 4,
                    debug = false
                )
            }
            else -> throw IllegalArgumentException("Unsupported sherpa-onnx engine: $engine")
        }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig
        )
        val rec = OfflineRecognizer(assetManager = null, config = config)
        recognizer = rec
        cachedEngineType = engine
        return rec
    }

    fun transcribe(ctx: Context, engine: AsrEngineType, wavFile: File): Result<String> {
        return try {
            val samples = readWavAsFloats(wavFile)
            if (samples.isEmpty()) return Result.success("")

            val speechSegments = extractSpeechWithVad(ctx, samples)
            if (speechSegments.isEmpty()) {
                return Result.success("")
            }

            val rec = getOrCreateRecognizer(ctx, engine)
            val sb = StringBuilder()
            for (seg in speechSegments) {
                val stream = rec.createStream()
                stream.acceptWaveform(seg, 16000)
                rec.decode(stream)
                val text = rec.getResult(stream).text.trim()
                stream.release()
                if (text.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(text)
                }
            }
            Result.success(sb.toString().trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

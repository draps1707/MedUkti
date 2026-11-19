package com.voicerambler.free

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

object SherpaOnnxEngine {

    private var cachedEngineType: AsrEngineType? = null
    private var recognizer: OfflineRecognizer? = null
    private var onlineRecognizer: OnlineRecognizer? = null
    private var vad: Vad? = null

    fun readWavAsFloats(wavFile: File): FloatArray {
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

    @Synchronized
    fun getOrCreateVad(ctx: Context): Vad? {
        if (!ModelDownloader.isInstalled(ctx, ModelDownloader.SILERO_VAD)) {
            return null
        }
        if (vad == null) {
            val vadFile = ModelDownloader.getFile(ctx, ModelDownloader.SILERO_VAD)
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = 0.45f,
                    minSilenceDuration = 0.45f,
                    minSpeechDuration = 0.20f,
                    windowSize = 512
                ),
                sampleRate = 16000,
                numThreads = 1,
                debug = false
            )
            vad = Vad(assetManager = null, config = vadConfig)
        }
        return vad
    }

    private fun extractSpeechWithVad(ctx: Context, samples: FloatArray): List<FloatArray> {
        val v = getOrCreateVad(ctx) ?: return listOf(samples)
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
    fun getOrCreateOnlineRecognizer(ctx: Context): OnlineRecognizer {
        if (onlineRecognizer != null) return onlineRecognizer!!
        if (!ModelDownloader.isIndianEnInstalled(ctx)) {
            throw IllegalStateException("Download English (India) Live Pack first")
        }
        val enc = ModelDownloader.getFile(ctx, ModelDownloader.INDIAN_EN_ENC)
        val dec = ModelDownloader.getFile(ctx, ModelDownloader.INDIAN_EN_DEC)
        val joiner = ModelDownloader.getFile(ctx, ModelDownloader.INDIAN_EN_JOINER)
        val tok = ModelDownloader.getFile(ctx, ModelDownloader.INDIAN_EN_TOKENS)
        val bpeOk = ModelDownloader.isInstalled(ctx, ModelDownloader.INDIAN_EN_BPE)
        val bpeFile = ModelDownloader.getFile(ctx, ModelDownloader.INDIAN_EN_BPE)
        val hotwordsFile = if (bpeOk) MedicalNormalizer.ensureHotwordsFile(ctx) else null

        val endpointCfg = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0.0f),
            rule2 = EndpointRule(true, 1.0f, 0.0f),
            rule3 = EndpointRule(false, 0.0f, 20.0f)
        )

        val rec = if (bpeOk && hotwordsFile != null && hotwordsFile.exists()) {
            try {
                OnlineRecognizer(
                    assetManager = null,
                    config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = enc.absolutePath,
                                decoder = dec.absolutePath,
                                joiner = joiner.absolutePath
                            ),
                            tokens = tok.absolutePath,
                            numThreads = 4,
                            debug = false,
                            modelType = "zipformer2",
                            modelingUnit = "bpe",
                            bpeVocab = bpeFile.absolutePath
                        ),
                        endpointConfig = endpointCfg,
                        enableEndpoint = true,
                        decodingMethod = "modified_beam_search",
                        maxActivePaths = 4,
                        hotwordsFile = hotwordsFile.absolutePath,
                        hotwordsScore = 2.0f
                    )
                )
            } catch (_: Exception) {
                buildPlainOnlineRecognizer(enc, dec, joiner, tok, endpointCfg)
            }
        } else {
            buildPlainOnlineRecognizer(enc, dec, joiner, tok, endpointCfg)
        }
        onlineRecognizer = rec
        return rec
    }

    private fun buildPlainOnlineRecognizer(
        enc: File,
        dec: File,
        joiner: File,
        tok: File,
        endpointCfg: EndpointConfig
    ): OnlineRecognizer {
        return OnlineRecognizer(
            assetManager = null,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = enc.absolutePath,
                        decoder = dec.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tok.absolutePath,
                    numThreads = 4,
                    debug = false,
                    modelType = "zipformer2"
                ),
                endpointConfig = endpointCfg,
                enableEndpoint = true,
                decodingMethod = "greedy_search"
            )
        )
    }

    @Synchronized
    private fun getOrCreateRecognizer(ctx: Context, engine: AsrEngineType): OfflineRecognizer {
        if (recognizer != null && cachedEngineType == engine) {
            return recognizer!!
        }
        recognizer?.release()
        recognizer = null

        val modelConfig = when (engine) {
            AsrEngineType.MEDASR_EN -> {
                val model = ModelDownloader.getFile(ctx, ModelDownloader.MEDASR_MODEL)
                val tokens = ModelDownloader.getFile(ctx, ModelDownloader.MEDASR_TOKENS)
                if (!model.exists() || !tokens.exists()) {
                    throw IllegalStateException("Download Google MedASR Medical Pack first")
                }
                OfflineModelConfig(
                    medasr = OfflineMedAsrCtcModelConfig(model = model.absolutePath),
                    tokens = tokens.absolutePath,
                    numThreads = 4,
                    debug = false
                )
            }
            AsrEngineType.PARAKEET_TDT_110M -> {
                val enc = ModelDownloader.getFile(ctx, ModelDownloader.PARAKEET_ENC)
                val dec = ModelDownloader.getFile(ctx, ModelDownloader.PARAKEET_DEC)
                val joiner = ModelDownloader.getFile(ctx, ModelDownloader.PARAKEET_JOINER)
                val tokens = ModelDownloader.getFile(ctx, ModelDownloader.PARAKEET_TOKENS)
                if (!enc.exists() || !dec.exists() || !joiner.exists() || !tokens.exists()) {
                    throw IllegalStateException("Download Parakeet TDT 110M Pack first")
                }
                OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = enc.absolutePath,
                        decoder = dec.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tokens.absolutePath,
                    modelType = "nemo_transducer",
                    numThreads = 4,
                    debug = false
                )
            }
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

    fun preload(ctx: Context, engine: AsrEngineType) {
        try {
            MedicalNormalizer.init(ctx)
            getOrCreateVad(ctx)
            when (engine) {
                AsrEngineType.INDIAN_EN_STREAMING -> getOrCreateOnlineRecognizer(ctx)
                AsrEngineType.MEDASR_EN,
                AsrEngineType.PARAKEET_TDT_110M,
                AsrEngineType.WHISPER_HINGLISH_SWIFT,
                AsrEngineType.INDIC_CONFORMER_HI,
                AsrEngineType.INDIC_CONFORMER_OR -> getOrCreateRecognizer(ctx, engine)
                else -> {}
            }
        } catch (_: Exception) {
        }
    }

    fun transcribeOnlineSamples(ctx: Context, samples: FloatArray): Result<String> {
        return try {
            if (samples.isEmpty()) return Result.success("")
            val rec = getOrCreateOnlineRecognizer(ctx)
            val stream = rec.createStream()
            stream.acceptWaveform(samples, 16000)
            stream.acceptWaveform(FloatArray(6400), 16000)
            while (rec.isReady(stream)) {
                rec.decode(stream)
            }
            val text = rec.getResult(stream).text.trim()
            stream.release()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun transcribeSamples(ctx: Context, engine: AsrEngineType, samples: FloatArray): Result<String> {
        return try {
            if (samples.isEmpty()) return Result.success("")
            if (engine == AsrEngineType.INDIAN_EN_STREAMING) {
                return transcribeOnlineSamples(ctx, samples)
            }
            val rec = getOrCreateRecognizer(ctx, engine)
            val stream = rec.createStream()
            stream.acceptWaveform(samples, 16000)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            stream.release()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun transcribe(ctx: Context, engine: AsrEngineType, wavFile: File): Result<String> {
        return try {
            val samples = readWavAsFloats(wavFile)
            if (samples.isEmpty()) return Result.success("")

            if (engine == AsrEngineType.INDIAN_EN_STREAMING) {
                return transcribeOnlineSamples(ctx, samples)
            }

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

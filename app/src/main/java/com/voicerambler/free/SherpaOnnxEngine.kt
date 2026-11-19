package com.voicerambler.free

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

object SherpaOnnxEngine {

    private val recognizers = ConcurrentHashMap<String, OfflineRecognizer>()
    private var vad: Vad? = null

    @Synchronized
    fun releaseRecognizer() {
        for (rec in recognizers.values) {
            try {
                rec.release()
            } catch (_: Exception) {
            }
        }
        recognizers.clear()
        SraVaaniEngine.release()
        StreamingZipformerEngine.release()
    }

    @Synchronized
    fun releaseInactiveModels(active: AsrEngineType) {
        val keepKeys = when (active) {
            AsrEngineType.INDIC_CONFORMER_OR -> setOf("odia")
            AsrEngineType.SRAVAANI_OR -> emptySet()
            AsrEngineType.WHISPER_TURBO -> setOf("whisper_turbo")
            AsrEngineType.PARAKEET_INDIC -> setOf("parakeet_indic")
        }
        val toRemove = recognizers.keys.filter { it !in keepKeys }
        for (k in toRemove) {
            try { recognizers.remove(k)?.release() } catch (_: Exception) {}
        }
        if (active != AsrEngineType.SRAVAANI_OR) {
            SraVaaniEngine.release()
        }
    }

    private fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) sumSq += s * s
        return sqrt(sumSq / samples.size).toFloat()
    }

    private fun normalizeGainAndPad(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val rms = computeRms(samples)
        val gain = if (rms in 0.0012f..0.08f) (0.08f / rms).coerceAtMost(6.0f) else 1.0f

        val leadPad = 2560  // 0.16s leading silence so soft plosives ("Pa-") aren't clipped
        val tailPad = 4800  // 0.30s trailing silence
        val out = FloatArray(leadPad + samples.size + tailPad)
        for (i in samples.indices) {
            out[leadPad + i] = (samples[i] * gain).coerceIn(-0.95f, 0.95f)
        }
        return out
    }

    private val EMOJI_MAP = linkedMapOf(
        "laughing emoji" to "😂",
        "smile emoji" to "😊",
        "smiling emoji" to "😊",
        "thumbs up" to "👍",
        "heart emoji" to "❤️",
        "love emoji" to "❤️",
        "namaste emoji" to "🙏",
        "folded hands" to "🙏",
        "sad emoji" to "😢",
        "crying emoji" to "😭",
        "fire emoji" to "🔥",
        "clap emoji" to "👏",
        "ok emoji" to "👌"
    )

    private fun capitalizeSentences(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        var capNext = true
        for (ch in input) {
            if (capNext && ch in 'a'..'z') {
                sb.append(ch.uppercaseChar())
                capNext = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '?' || ch == '!' || ch == '\n') {
                    capNext = true
                } else if (!ch.isWhitespace()) {
                    capNext = false
                }
            }
        }
        return sb.toString()
    }

    fun postProcessTranscript(ctx: Context, raw: String, engine: AsrEngineType): String {
        var text = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        if (text.isEmpty()) return ""

        text = text
            .replace(Regex("<[a-z]{2,3}>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\[[^\\]]*\\]?"), " ")
            .replace(Regex("\\{[^}]*\\}?"), " ")
            .replace(Regex("\\bAIName\\]?\\s*", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s*[\\]\\}]\\s*"), " ")
            .replace(Regex("\\b(\\w+)\\b(?:[.,!?\\s]+\\1\\b)+", RegexOption.IGNORE_CASE), "$1")

        text = text.replace(Regex("(?i)\\b(?:umm+|uhh+|err+m*|ahh+)\\b[,\\s]*"), " ")

        val selfCorr = Regex("(?i).*?\\b(?:wait no|sorry i mean|i mean)\\b[,\\s]+(.+)$").find(text)
        if (selfCorr != null) {
            text = selfCorr.groupValues[1].trim()
        }

        val fullStopChar = if (Prefs.isOdiaEngine(engine)) "।" else "."
        text = text
            .replace(Regex("\\s*\\b(?:new|next) paragraph\\b\\s*", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("\\s*\\b(?:new|next) line\\b\\s*", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("\\s*\\b(?:bullet point|next point)\\b\\s*", RegexOption.IGNORE_CASE), "\n• ")
            .replace(Regex("\\s*\\b(?:full stop|purna chheda)\\b[.!]*", RegexOption.IGNORE_CASE), fullStopChar)
            .replace(Regex("\\s*\\bquestion mark\\b[.?]*", RegexOption.IGNORE_CASE), "?")
            .replace(Regex("\\s*\\bcomma\\b,*", RegexOption.IGNORE_CASE), ",")

        for ((phrase, emoji) in EMOJI_MAP) {
            text = text.replace(Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE), emoji)
        }

        text = MedicalNormalizer.normalize(ctx, text, engine)


        val dictRaw = Prefs.getPersonalDict(ctx)
        if (dictRaw.isNotBlank()) {
            for (line in dictRaw.lines()) {
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val wrong = line.substring(0, idx).trim()
                    val right = line.substring(idx + 1).trim()
                    if (wrong.isNotEmpty() && right.isNotEmpty()) {
                        text = text.replace(Regex(Regex.escape(wrong), RegexOption.IGNORE_CASE), Regex.escapeReplacement(right))
                    }
                }
            }
        }

        var finalOut = Normalizer.normalize(
            text.replace(Regex("[ \\t]{2,}"), " ").let { if (it.all { ch -> ch == '\n' }) it else it.trim() },
            Normalizer.Form.NFC
        )
        if (Prefs.isOdiaEngine(engine) && finalOut.isNotEmpty()) {
            val words = finalOut.split(Regex("\\s+"))
            val lastCh = finalOut.last()
            if (words.size >= 4 && lastCh !in setOf('।', '.', '?', '!', ',')) {
                val isQuestion = words.any {
                    it in setOf("କିଏ", "କଣ", "କ'ଣ", "କେମିତି", "କେବେ", "କାହିଁକି", "କୁଆଡ଼େ", "କେତେ")
                }
                finalOut += if (isQuestion) "?" else "।"
            }
        }
        return finalOut
    }

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
                    threshold = 0.38f,
                    minSilenceDuration = 0.55f,
                    minSpeechDuration = 0.14f,
                    maxSpeechDuration = 6.0f,
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
    private fun getOrCreateModel(ctx: Context, key: String): OfflineRecognizer {
        recognizers[key]?.let { return it }

        val modelConfig = when (key) {
            "odia" -> {
                val model = ModelDownloader.getFile(ctx, ModelDownloader.INDIC_OR)
                val tokens = ModelDownloader.getFile(ctx, ModelDownloader.INDIC_TOKENS)
                if (!model.exists() || !tokens.exists()) {
                    throw IllegalStateException("Download Odia Offline Pack first")
                }
                OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                    tokens = tokens.absolutePath,
                    numThreads = 4,
                    debug = false
                )
            }
            "whisper_turbo" -> {
                val enc = ModelDownloader.getFile(ctx, ModelDownloader.WHISPER_TURBO_ENC)
                val dec = ModelDownloader.getFile(ctx, ModelDownloader.WHISPER_TURBO_DEC)
                val tok = ModelDownloader.getFile(ctx, ModelDownloader.WHISPER_TURBO_TOKENS)
                if (!enc.exists() || !dec.exists() || !tok.exists()) {
                    throw IllegalStateException("Download Whisper Large-v3-Turbo Pack first")
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
            "parakeet_indic" -> {
                val enc = ModelDownloader.getFile(ctx, ModelDownloader.PARAKEET_INDIC_ENC)
                val dec = ModelDownloader.getFile(ctx, ModelDownloader.PARAKEET_INDIC_DEC)
                val joiner = ModelDownloader.getFile(ctx, ModelDownloader.PARAKEET_INDIC_JOINER)
                val tok = ModelDownloader.getFile(ctx, ModelDownloader.PARAKEET_INDIC_TOKENS)
                if (!enc.exists() || !dec.exists() || !joiner.exists() || !tok.exists()) {
                    throw IllegalStateException("Download Parakeet-Indic v1 Pack first")
                }
                OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = enc.absolutePath,
                        decoder = dec.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tok.absolutePath,
                    modelType = "nemo_transducer",
                    numThreads = 4,
                    debug = false
                )
            }
            else -> throw IllegalArgumentException("Unknown model key: $key")
        }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig
        )
        val rec = OfflineRecognizer(assetManager = null, config = config)
        recognizers[key] = rec
        return rec
    }

    private fun decodeSingle(rec: OfflineRecognizer, boosted: FloatArray): String {
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(boosted, 16000)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun decodeRaw(ctx: Context, engine: AsrEngineType, boosted: FloatArray): String {
        return when (engine) {
            AsrEngineType.INDIC_CONFORMER_OR -> decodeSingle(getOrCreateModel(ctx, "odia"), boosted)
            AsrEngineType.SRAVAANI_OR -> SraVaaniEngine.transcribeSamples(ctx, boosted)
            AsrEngineType.WHISPER_TURBO -> decodeSingle(getOrCreateModel(ctx, "whisper_turbo"), boosted)
            AsrEngineType.PARAKEET_INDIC -> decodeSingle(getOrCreateModel(ctx, "parakeet_indic"), boosted)
        }
    }

    fun preload(ctx: Context, engine: AsrEngineType) {
        try {
            MedicalNormalizer.init(ctx)
            getOrCreateVad(ctx)
            when (engine) {
                AsrEngineType.INDIC_CONFORMER_OR -> getOrCreateModel(ctx, "odia")
                AsrEngineType.SRAVAANI_OR -> SraVaaniEngine.preload(ctx)
                AsrEngineType.WHISPER_TURBO -> getOrCreateModel(ctx, "whisper_turbo")
                AsrEngineType.PARAKEET_INDIC -> getOrCreateModel(ctx, "parakeet_indic")
            }
        } catch (_: Exception) {
        }
    }

    fun transcribeSamples(ctx: Context, engine: AsrEngineType, samples: FloatArray): Result<String> {
        return try {
            if (samples.size < 2400 || computeRms(samples) < 0.001f) {
                return Result.success("")
            }
            val boosted = normalizeGainAndPad(samples)
            val raw = decodeRaw(ctx, engine, boosted)
            Result.success(postProcessTranscript(ctx, raw, engine))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun transcribe(ctx: Context, engine: AsrEngineType, wavFile: File): Result<String> {
        return try {
            val samples = readWavAsFloats(wavFile)
            if (samples.isEmpty()) return Result.success("")

            val speechSegments = extractSpeechWithVad(ctx, samples)
            if (speechSegments.isEmpty()) {
                return Result.success("")
            }

            val sb = StringBuilder()
            for (seg in speechSegments) {
                if (seg.size < 4800 || computeRms(seg) < 0.003f) continue
                val boosted = normalizeGainAndPad(seg)
                val raw = decodeRaw(ctx, engine, boosted)
                val text = postProcessTranscript(ctx, raw, engine)
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

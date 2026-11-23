package com.medukti.keyboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import kotlin.math.sqrt

object CloudAsrClient {

    private const val GROQ_WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models"

    private const val BASE_WHISPER_PROMPT =
        "Indian clinical note: Sacubitril/valsartan, Dapagliflozin, Empagliflozin, Apixaban, Atorvastatin, " +
            "Etoricoxib, Celecoxib, Pregabalin, Nortriptyline, Duloxetine, Thiocolchicoside, Cefixime, " +
            "Ceftazidime, Cefoperazone, Sulbactam, Piperacillin, Tazobactam, Piptaz, Monocef, Augmentin, " +
            "Pan-D, Telma, Glycomet, Dolo, Forxiga, Jardiance, Scrub typhus, Kyphosis, Lordosis, Rectus sheath, " +
            "Fitzpatrick, Beta-lactamase, Procalcitonin, Troponin-I, NT-proBNP, Hyperkalaemia, Dyspnoea, Oedema."

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

    fun isNetworkAvailable(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    fun computeRms(samples: FloatArray, start: Int = 0, len: Int = samples.size): Float {
        if (len <= 0 || start + len > samples.size) return 0f
        var sumSq = 0.0
        for (i in start until start + len) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        return sqrt(sumSq / len).toFloat()
    }

    private fun normalizeGain(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val rms = computeRms(samples)
        val gain = if (rms in 0.0015f..0.055f) (0.065f / rms).coerceAtMost(4.0f) else 1.0f
        if (gain == 1.0f) return samples
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i] * gain).coerceIn(-0.95f, 0.95f)
        }
        return out
    }

    private fun encodeWav16kMono(samples: FloatArray): ByteArray {
        val boosted = normalizeGain(samples)
        val leadPad = 1600
        val tailPad = 1600
        val totalSamples = leadPad + boosted.size + tailPad
        val dataBytes = totalSamples * 2
        val baos = ByteArrayOutputStream(44 + dataBytes)
        val dos = DataOutputStream(baos)

        fun writeIntLE(v: Int) {
            dos.write(v and 0xFF)
            dos.write((v shr 8) and 0xFF)
            dos.write((v shr 16) and 0xFF)
            dos.write((v shr 24) and 0xFF)
        }

        fun writeShortLE(v: Int) {
            dos.write(v and 0xFF)
            dos.write((v shr 8) and 0xFF)
        }

        dos.writeBytes("RIFF")
        writeIntLE(36 + dataBytes)
        dos.writeBytes("WAVEfmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(1)
        writeIntLE(16000)
        writeIntLE(32000)
        writeShortLE(2)
        writeShortLE(16)
        dos.writeBytes("data")
        writeIntLE(dataBytes)

        for (i in 0 until leadPad) writeShortLE(0)
        for (s in boosted) {
            writeShortLE((s.coerceIn(-0.99f, 0.99f) * 32767.0f).toInt())
        }
        for (i in 0 until tailPad) writeShortLE(0)
        dos.flush()
        return baos.toByteArray()
    }

    fun testGroqKey(apiKey: String): String {
        if (apiKey.isBlank()) return "Groq: missing key"
        return try {
            val conn = (URL(GROQ_MODELS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
            val code = conn.responseCode
            if (code in 200..299) "Groq: ✓ OK" else "Groq: HTTP $code"
        } catch (e: Exception) {
            "Groq: ${e.message?.take(40) ?: "error"}"
        }
    }

    fun testGeminiKey(apiKey: String): String {
        if (apiKey.isBlank()) return "Gemini: missing key"
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey.trim()}"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            val code = conn.responseCode
            if (code in 200..299) "Gemini: ✓ OK" else "Gemini: HTTP $code"
        } catch (e: Exception) {
            "Gemini: ${e.message?.take(40) ?: "error"}"
        }
    }

    private fun buildWhisperPrompt(ctx: Context): String {
        val personal = Prefs.getPersonalDict(ctx)
        if (personal.isBlank()) return BASE_WHISPER_PROMPT
        val customTerms = personal.lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx > 0) line.substring(idx + 1).trim().takeIf { it.isNotEmpty() } else null
            }
            .distinct()
            .take(25)
        return if (customTerms.isEmpty()) {
            BASE_WHISPER_PROMPT
        } else {
            (customTerms.joinToString(", ") + ". " + BASE_WHISPER_PROMPT).take(750)
        }
    }

    private fun callGroqWhisper(ctx: Context, wavBytes: ByteArray, model: String, isLivePreview: Boolean): String {
        val apiKey = Prefs.getGroqKey(ctx)
        if (apiKey.isBlank()) {
            throw IllegalStateException("Add your free Groq API key in MedUkti Settings")
        }

        val boundary = "----MedUktiBoundary${System.currentTimeMillis()}"
        val conn = (URL(GROQ_WHISPER_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = if (isLivePreview) 7000 else 20000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        conn.outputStream.use { out ->
            fun writeField(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                out.write(value.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray())
            }

            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".toByteArray())
            out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            out.write(wavBytes)
            out.write("\r\n".toByteArray())

            writeField("model", model)
            writeField("response_format", "json")
            writeField("temperature", "0")
            writeField("prompt", buildWhisperPrompt(ctx))
            out.write("--$boundary--\r\n".toByteArray())
        }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("Groq STT error ($code): ${body.take(180)}")
        }
        return JSONObject(body).optString("text", "").trim()
    }

    private fun callGroqPolish(ctx: Context, rawText: String): String {
        val apiKey = Prefs.getGroqKey(ctx)
        if (apiKey.isBlank() || rawText.isBlank()) return rawText

        val model = Prefs.getGroqLlmModel(ctx)
        val personal = Prefs.getPersonalDict(ctx).trim()
        val sysPrompt = buildString {
            append("You are an ultra-fast medical & Hinglish transcription polisher for an Indian doctor. ")
            append("Fix punctuation, capitalization, filler words (umm, uh), and self-corrections. ")
            append("Fix acoustic mishearings of generic drugs, Indian brand names, and clinical terms using sentence context ")
            append("(e.g. 'epixabin'->Apixaban, 'Sacubitril ablastin'->Sacubitril/valsartan, 'by basilar'->bibasilar, ")
            append("'insuring Glargine'->Insulin glargine, 'according' vs 'accordioning', 'monitor' vs 'Mononitrate'). ")
            append("Preserve natural Romanized Hinglish words if spoken. ")
            if (personal.isNotEmpty()) {
                append("Always apply these exact user overrides: ${personal.replace("\n", "; ")}. ")
            }
            append("Output ONLY the final cleaned text with zero commentary or quotes.")
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", 512)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sysPrompt))
                put(JSONObject().put("role", "user").put("content", rawText))
            })
        }

        val conn = (URL(GROQ_CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            return rawText
        }
        val choices = JSONObject(body).optJSONArray("choices") ?: return rawText
        if (choices.length() == 0) return rawText
        val content = choices.getJSONObject(0)
            .optJSONObject("message")
            ?.optString("content", "")
            ?.trim()
            .orEmpty()
        return content.ifBlank { rawText }
    }

    private fun callGeminiOdia(ctx: Context, wavBytes: ByteArray): String {
        val apiKey = Prefs.getGeminiKey(ctx)
        if (apiKey.isBlank()) {
            throw IllegalStateException("Add your free Gemini API key in MedUkti Settings for Odia")
        }
        val model = Prefs.getGeminiModel(ctx)
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val b64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val prompt = buildString {
            append("Transcribe this audio verbatim. ")
            append("If spoken in Odia, write accurate Odia script (ଓଡ଼ିଆ)—never Malayalam, Gujarati, Bengali, or Devanagari. ")
            append("Keep English medical terms, drug names, and numbers in accurate English/digits. ")
            append("If spoken in Hinglish or English, preserve natural text. ")
            append("Output ONLY the transcribed text, no explanations.")
        }

        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/wav")
                        put("data", b64Audio)
                    }))
                }))
            })
            put("generationConfig", JSONObject().put("temperature", 0.0))
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 9000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("Gemini STT error ($code): ${body.take(180)}")
        }
        val candidates = JSONObject(body).optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts") ?: return ""
        if (parts.length() == 0) return ""
        return parts.getJSONObject(0).optString("text", "").trim()
    }

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
            .replace(Regex("\\s*[\\]\\}]\\s*"), " ")
            .replace(Regex("\\b(\\w+)\\b(?:[.,!?\\s]+\\1\\b)+", RegexOption.IGNORE_CASE), "$1")
            .replace(Regex("(?i)\\b(?:umm+|uhh+|err+m*|ahh+)\\b[,\\s]*"), " ")

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

        if (!Prefs.isOdiaEngine(engine)) {
            text = capitalizeSentences(text)
        }

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

        return Normalizer.normalize(
            text.replace(Regex("\\s+([.,?!।])"), "$1")
                .replace(Regex("[ \\t]{2,}"), " ")
                .let { if (it.all { ch -> ch == '\n' }) it else it.trim() },
            Normalizer.Form.NFC
        )
    }

    fun transcribeLivePreview(ctx: Context, engine: AsrEngineType, samples: FloatArray): Result<String> {
        return try {
            if (samples.size < 4800 || computeRms(samples) < 0.002f) return Result.success("")
            if (engine == AsrEngineType.GEMINI_ODIA) return Result.success("")
            val wav = encodeWav16kMono(samples)
            val raw = callGroqWhisper(ctx, wav, "whisper-large-v3-turbo", isLivePreview = true)
            Result.success(postProcessTranscript(ctx, raw, engine))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun transcribeFinal(ctx: Context, engine: AsrEngineType, samples: FloatArray): Result<String> {
        return try {
            if (samples.size < 3200 || computeRms(samples) < 0.002f) return Result.success("")
            val wav = encodeWav16kMono(samples)
            val raw = when (engine) {
                AsrEngineType.GROQ_MED -> {
                    val stt = callGroqWhisper(ctx, wav, Prefs.getGroqAsrModel(ctx), isLivePreview = false)
                    if (stt.isNotBlank() && Prefs.isAiPolishEnabled(ctx)) {
                        callGroqPolish(ctx, stt)
                    } else {
                        stt
                    }
                }
                AsrEngineType.GEMINI_ODIA -> {
                    callGeminiOdia(ctx, wav)
                }
            }
            Result.success(postProcessTranscript(ctx, raw, engine))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

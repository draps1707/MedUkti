package com.medukti.keyboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
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
        if (computeRms(samples) < 0.006f) return samples
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

    fun testElevenLabsKey(apiKey: String): String {
        if (apiKey.isBlank()) return "ElevenLabs: missing"
        return try {
            val conn = (URL("https://api.elevenlabs.io/v1/user").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("xi-api-key", apiKey.trim())
            }
            val code = conn.responseCode
            if (code in 200..299) "11Labs: ✓ OK" else "11Labs: HTTP $code"
        } catch (e: Exception) {
            "11Labs: ${e.message?.take(40) ?: "error"}"
        }
    }

    private fun buildWhisperPrompt(ctx: Context): String {
        val universalTerms = "Tab., Inj., Cap., SpO2, BP, Dyspnoea, Oedema, Ceftriaxone, Piperacillin, Tazobactam, Augmentin, Azithromycin, Empagliflozin, Dapagliflozin, Apixaban, Sacubitril/valsartan, Alendronate, Prednisolone, Methotrexate, Sulfasalazine, Metformin, Paracetamol"
        val personal = Prefs.getPersonalDict(ctx)
        val customTerms = if (personal.isBlank()) emptyList() else personal.lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx > 0) line.substring(idx + 1).trim().takeIf { it.isNotEmpty() } else null
            }
            .distinct()
            .take(25)
        val combined = if (customTerms.isEmpty()) {
            universalTerms
        } else {
            "${customTerms.joinToString(", ")}, $universalTerms"
        }
        return combined.take(750)
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
            writeField("language", "en")
            writeField("response_format", "verbose_json")
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
        val root = JSONObject(body)
        val segs = root.optJSONArray("segments")
        if (segs != null && segs.length() > 0) {
            val kept = ArrayList<String>(segs.length())
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONObject(i) ?: continue
                val noSpeech = seg.optDouble("no_speech_prob", 0.0)
                val avgLogProb = seg.optDouble("avg_logprob", 0.0)
                val segText = seg.optString("text", "").trim()
                if (segText.isNotEmpty() && noSpeech <= 0.60 && avgLogProb >= -1.0) {
                    kept.add(segText)
                }
            }
            return kept.joinToString(" ").trim()
        }
        return root.optString("text", "").trim()
    }

    private fun callGroqPolish(ctx: Context, rawText: String): String {
        val apiKey = Prefs.getGroqKey(ctx)
        if (apiKey.isBlank() || rawText.isBlank()) return rawText
        val wordCount = rawText.trim().split(Regex("\\s+")).count { tok -> tok.any { it.isLetterOrDigit() } }
        if (wordCount < 2) return rawText

        val model = Prefs.getGroqLlmModel(ctx)
        val personal = Prefs.getPersonalDict(ctx).trim()
        val candidates = MedicalNormalizer.findCandidateHints(ctx, rawText)
        val sysPrompt = buildString {
            append("You are an ultra-fast medical & Hinglish transcription polisher for an Indian doctor. ")
            append("Fix punctuation (split run-on clauses into clean sentences with periods and commas), capitalization, and self-corrections—keep spoken 'um' and 'uh' fillers as-is. ")
            append("Watch for Indian-English ASR phonetic shifts: bh<->v (oseltamibhir->Oseltamivir, Bupibhaken->Bupivacaine), ")
            append("z<->j (carbimajol->Carbimazole, omeprajol->Omeprazole), ph/p<->f (epixabin->Apixaban, phosphormycin->Fosfomycin), ")
            append("dropped -r endings (acyclovia->Acyclovir, tenofova->Tenofovir), and 2-word splits (by basilar->bibasilar, Sacubitril ablastin->Sacubitril/valsartan). ")
            append("Use sentence context so normal English words (monitor, according, patients) are never wrongly replaced. ")
            append("The speaker is an Indian doctor dictating clinical notes in Indian-accented English with medical and drug terms. If the input is noise or unclear, return it unchanged—never ask for text or reply conversationally. ")
            append("Output clean clinical English and accurate drug/medical terms only—never output Devanagari, Korean, Japanese, or other foreign scripts. ")
            if (candidates.isNotEmpty()) {
                append("Candidate Indian formulary/clinical terms that may match this audio: ${candidates.joinToString(", ")}. ")
            }
            if (personal.isNotEmpty()) {
                append("Always apply these exact user overrides: ${personal.replace("\n", "; ")}. ")
            }
            append("Output ONLY the final cleaned text with zero commentary or quotes.")
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("reasoning_effort", "low")
            put("max_tokens", 1024)
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
        if (content.isBlank()) return rawText
        val low = content.lowercase()
        if (low.startsWith("please provide") ||
            low.startsWith("i'm sorry") ||
            low.startsWith("i cannot") ||
            low.startsWith("here is") ||
            low.startsWith("sure,") ||
            low.contains("transcription you would like") ||
            content.length > rawText.length * 3 + 40
        ) {
            return rawText
        }
        return content
    }

    private fun callGeminiIndic(ctx: Context, wavBytes: ByteArray, isHinglish: Boolean): String {
        val apiKey = Prefs.getGeminiKey(ctx)
        if (apiKey.isBlank()) {
            throw IllegalStateException("Add your free Gemini API key in MedUkti Settings for Odia/Hindi")
        }
        val primaryModel = Prefs.getGeminiModel(ctx)
        return try {
            callGeminiWithModel(apiKey, primaryModel, wavBytes, useMinimalThinking = !primaryModel.contains("lite", ignoreCase = true), isHinglish = isHinglish)
        } catch (e: Exception) {
            if (primaryModel != "gemini-3.5-flash-lite" || !primaryModel.contains("lite", ignoreCase = true)) {
                callGeminiWithModel(apiKey, "gemini-3.5-flash-lite", wavBytes, useMinimalThinking = false, isHinglish = isHinglish)
            } else {
                throw e
            }
        }
    }

    private fun callGeminiWithModel(apiKey: String, model: String, wavBytes: ByteArray, useMinimalThinking: Boolean, isHinglish: Boolean): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val b64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val prompt = if (isHinglish) {
            buildString {
                append("Transcribe ONLY the spoken audio verbatim. The speaker is an Indian doctor speaking natural Hinglish (mixed Hindi and English) with medical terms. ")
                append("Write Hindi words in natural Romanized Hinglish and English/medical terms in accurate English, add clean periods and commas between sentences, and keep spoken 'um' and 'uh' fillers as-is—NEVER output Devanagari, Korean, Japanese, or other non-Latin scripts. ")
                append("If the audio is silence, breath, filler, or unintelligible noise, output an empty string. Never invent or repeat example sentences. ")
                append("Output ONLY the spoken transcript with zero commentary.")
            }
        } else {
            buildString {
                append("Transcribe ONLY the spoken audio verbatim. The speaker is from Odisha speaking casual conversational Odia (ଓଡ଼ିଆ) with occasional English medical terms. ")
                append("Write Odia speech in clean Odia script (ଓଡ଼ିଆ) keeping colloquial phrasing natural, use '।' or '?' for punctuation (never '|'), and keep English medical terms and digits in English. ")
                append("NEVER output Bengali, Devanagari, Malayalam, or Gujarati characters. ")
                append("If the audio is silence, breath, filler, or unintelligible noise, output an empty string. ")
                append("Output ONLY the spoken transcript with zero commentary.")
            }
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
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
                if (useMinimalThinking) {
                    put("thinkingConfig", JSONObject().put("thinkingLevel", "MINIMAL"))
                }
            })
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 9000
            readTimeout = 12000
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

    private val WHISPER_HALLUCINATIONS = listOf(
        Regex("""(?i)\b(?:please\s+)?(?:like,?\s+)?(?:share,?\s+)?(?:and\s+)?subscribe!?\b"""),
        Regex("""(?i)\bthank(?:s|\s+you)\s+for\s+watching!?\b"""),
        Regex("""(?i)\band\s+many\s+more\.?\b"""),
        Regex("""(?i)\bsubtitles\s+by\b[^.]*\.?"""),
        Regex("""(?i)\bamara\.org\b"""),
        Regex("""(?i)\btape[-\s]*system\b"""),
        Regex("""(?i)\btap\s+tape\b"""),
        Regex("""(?i)\bthe\s+current\s+medical\s+diagnosis\s+and\s+treatment\.?"""),
        Regex("""(?i)\bhi\s+guys!?"""),
        Regex("""(?i)\b(?:transcription|transcribed|translation|translated|subtitles|captioned)\s+by\b[^.?!\n]*[.?!]?"""),
        Regex("""(?i)^\s*(?:hello!?\s*){2,}$""")
    )

    fun postProcessTranscript(ctx: Context, raw: String, engine: AsrEngineType): String {
        var text = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        val lowCheck = text.lowercase()
        if (lowCheck.startsWith("there is no audio") ||
            lowCheck.startsWith("i'm sorry") ||
            lowCheck.startsWith("i cannot") ||
            lowCheck.contains("please provide the transcription")
        ) return "" 
        if (text.isEmpty()) return ""
        if (Prefs.isIndicScriptEngine(engine)) {
            text = text.replace(" |", "।").replace("|", "।")
            // Strip accidental Spanish/French accented hallucinations (e.g. "Por el bien y la mejoración")
            if (text.any { it in '\u00C0'..'\u024F' }) {
                text = text.replace(Regex("\\b[A-Za-z\u00C0-\u024F]*[\u00C0-\u024F][A-Za-z\u00C0-\u024F]*\\b"), " ")
                    .replace(Regex("(?i)\\bpor\\s+el\\s+bien\\b[^।?!]*"), " ")
                    .replace(Regex("[ \t]{2,}"), " ").trim()
            }
        }
        if (!Prefs.isIndicScriptEngine(engine)) {
            for (rx in WHISPER_HALLUCINATIONS) {
                text = text.replace(rx, " ")
            }
            text = text.replace(Regex("[ \t]{2,}"), " ").trim()
            if (text.isEmpty() || text.all { !it.isLetterOrDigit() }) return ""
        }

        text = text
            .replace(Regex("<[a-z]{2,3}>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\[[^\\]]*\\]?"), " ")
            .replace(Regex("\\{[^}]*\\}?"), " ")
            .replace(Regex("\\s*[\\]\\}]\\s*"), " ")
            .replace(Regex("\\b(\\w+)\\b(?:[.,!?\\s]+\\1\\b)+", RegexOption.IGNORE_CASE), "$1")

        val selfCorr = Regex("(?i).*?\\b(?:wait no|sorry i mean|i mean)\\b[,\\s]+(.+)$").find(text)
        if (selfCorr != null) {
            text = selfCorr.groupValues[1].trim()
        }

        val fullStopChar = if (Prefs.isIndicScriptEngine(engine)) "।" else "."
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

        if (!Prefs.isIndicScriptEngine(engine)) {
            text = text.replace(Regex("[^\\u0000-\\u024F\\s.,?!:;/'\"()\\-+%°]+"), " ").replace(Regex("[ \t]{2,}"), " ").trim()
            if (text.isEmpty()) return ""
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

    private fun callElevenLabsOdia(ctx: Context, wavBytes: ByteArray): String {
        val apiKey = Prefs.getElevenLabsKey(ctx)
        if (apiKey.isBlank()) {
            throw IllegalStateException("Add your ElevenLabs API key in MedUkti Settings")
        }
        val boundary = "----MedUkti11Labs${System.currentTimeMillis()}"
        val url = URL("https://api.elevenlabs.io/v1/speech-to-text")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        DataOutputStream(conn.outputStream).use { out ->
            fun writeField(name: String, value: String) {
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.write(value.toByteArray(Charsets.UTF_8))
                out.writeBytes("\r\n")
            }
            writeField("model_id", "scribe_v2")
            writeField("language_code", "or")
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
            out.writeBytes("Content-Type: audio/wav\r\n\r\n")
            out.write(wavBytes)
            out.writeBytes("\r\n--$boundary--\r\n")
        }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("ElevenLabs STT error ($code): ${body.take(160)}")
        }
        return JSONObject(body).optString("text", "").trim()
    }

    private fun transcribeOdiaBest(ctx: Context, wavBytes: ByteArray): String {
        val backend = Prefs.getOdiaBackend(ctx)
        if (backend == OdiaBackend.ELEVENLABS) {
            val elKey = Prefs.getElevenLabsKey(ctx)
            if (elKey.isNotBlank()) {
                return try {
                    callElevenLabsOdia(ctx, wavBytes)
                } catch (e: Exception) {
                    if (Prefs.getGeminiKey(ctx).isNotBlank()) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(ctx.applicationContext, "11Labs busy — used Gemini backup", Toast.LENGTH_SHORT).show()
                        }
                        callGeminiIndic(ctx, wavBytes, isHinglish = false)
                    } else {
                        throw e
                    }
                }
            }
            if (Prefs.getGeminiKey(ctx).isNotBlank()) {
                return callGeminiIndic(ctx, wavBytes, isHinglish = false)
            }
            throw IllegalStateException("Add your ElevenLabs or Gemini API key in MedUkti Settings")
        }
        return callGeminiIndic(ctx, wavBytes, isHinglish = false)
    }

    private inline fun <T> runWithOneNetworkRetry(block: () -> T): T {
        return try {
            block()
        } catch (e: IOException) {
            try { Thread.sleep(700L) } catch (_: InterruptedException) {}
            block()
        }
    }

    fun transcribeLivePreview(ctx: Context, engine: AsrEngineType, samples: FloatArray): Result<String> {
        return try {
            val minRms = when (engine) { AsrEngineType.ODIA -> 0.002f; AsrEngineType.HINGLISH -> 0.004f; AsrEngineType.MEDICAL -> 0.006f }
            if (samples.size < 4800 || computeRms(samples) < minRms) return Result.success("")
            val wav = encodeWav16kMono(samples)
            val raw = when (engine) {
                AsrEngineType.MEDICAL ->
                    callGroqWhisper(ctx, wav, "whisper-large-v3-turbo", isLivePreview = true)
                AsrEngineType.HINGLISH -> {
                    val key = Prefs.getGeminiKey(ctx)
                    if (key.isBlank()) "" else callGeminiWithModel(key, "gemini-3.5-flash-lite", wav, useMinimalThinking = false, isHinglish = true)
                }
                AsrEngineType.ODIA -> {
                    if (Prefs.getOdiaBackend(ctx) == OdiaBackend.GEMINI) {
                        val key = Prefs.getGeminiKey(ctx)
                        if (key.isBlank()) "" else callGeminiWithModel(key, "gemini-3.5-flash-lite", wav, useMinimalThinking = false, isHinglish = false)
                    } else ""
                }
            }
            Result.success(postProcessTranscript(ctx, raw, engine))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun transcribeFinal(ctx: Context, engine: AsrEngineType, samples: FloatArray): Result<String> {
        return try {
            if (samples.size < 3200 || computeRms(samples) < 0.002f) return Result.success("")
            val wav = encodeWav16kMono(samples)
            val raw = runWithOneNetworkRetry {
                when (engine) {
                AsrEngineType.MEDICAL -> {
                    val stt = callGroqWhisper(ctx, wav, Prefs.getGroqAsrModel(ctx), isLivePreview = false)
                    if (stt.isNotBlank() && Prefs.isAiPolishEnabled(ctx)) {
                        callGroqPolish(ctx, stt)
                    } else {
                        stt
                    }
                }
                AsrEngineType.HINGLISH -> callGeminiIndic(ctx, wav, isHinglish = true)
                AsrEngineType.ODIA -> transcribeOdiaBest(ctx, wav)
                }
            }
            Result.success(postProcessTranscript(ctx, raw, engine))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

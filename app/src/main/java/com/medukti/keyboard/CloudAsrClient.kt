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
        val universalTerms = "Tab., Inj., Cap., Syp., SpO2, BP, Dyspnoea, Oedema, Haemoptysis, Scrub typhus, Rectus sheath, Troponin-I, NT-proBNP, Ceftriaxone, Piperacillin, Tazobactam, Augmentin, Azithromycin, Empagliflozin, Dapagliflozin, Apixaban, Sacubitril/valsartan, Atorvastatin, Telmisartan, Pantoprazole, Metformin, Paracetamol"
        val personal = Prefs.getPersonalDict(ctx)
        val customTerms = if (personal.isBlank()) emptyList() else personal.lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx > 0) line.substring(idx + 1).trim().takeIf { it.isNotEmpty() } else null
            }
            .distinct()
            .take(25)
        val combined = if (customTerms.isEmpty()) {
            "Indian clinical note: $universalTerms"
        } else {
            "Indian clinical note: ${customTerms.joinToString(", ")}, $universalTerms"
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

    private fun callGroqPolish(ctx: Context, rawText: String, isHinglish: Boolean): String {
        val apiKey = Prefs.getGroqKey(ctx)
        if (apiKey.isBlank() || rawText.isBlank()) return rawText

        val model = Prefs.getGroqLlmModel(ctx)
        val personal = Prefs.getPersonalDict(ctx).trim()
        val candidates = MedicalNormalizer.findCandidateHints(ctx, rawText)
        val sysPrompt = buildString {
            append("You are an ultra-fast medical & Hinglish transcription polisher for an Indian doctor. ")
            append("Fix punctuation, capitalization, filler words (umm, uh), and self-corrections. ")
            append("Watch for Indian-English ASR phonetic shifts: bh<->v (oseltamibhir->Oseltamivir, Bupibhaken->Bupivacaine), ")
            append("z<->j (carbimajol->Carbimazole, omeprajol->Omeprazole), ph/p<->f (epixabin->Apixaban, phosphormycin->Fosfomycin), ")
            append("dropped -r endings (acyclovia->Acyclovir, tenofova->Tenofovir), and 2-word splits (by basilar->bibasilar, Sacubitril ablastin->Sacubitril/valsartan). ")
            append("Use sentence context so normal English words (monitor, according, patients) are never wrongly replaced. ")
            if (isHinglish) {
                append("The speaker is speaking Hinglish (mixed Hindi + English) with medical terms. Keep casual Hindi words in natural Romanized Hinglish (e.g. 'Patient ko kal raat se fever hai') and medical/drug terms in accurate English. Never output Devanagari, French, or Spanish. ")
            } else {
                append("Output strictly in English (and Romanized Hinglish only if clearly spoken; never Devanagari, French, Spanish, or other foreign languages). ")
            }
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
        return content.ifBlank { rawText }
    }

    private fun callGeminiIndic(ctx: Context, wavBytes: ByteArray, isHindi: Boolean): String {
        val apiKey = Prefs.getGeminiKey(ctx)
        if (apiKey.isBlank()) {
            throw IllegalStateException("Add your free Gemini API key in MedUkti Settings for Odia/Hindi")
        }
        val primaryModel = Prefs.getGeminiModel(ctx)
        return try {
            callGeminiWithModel(apiKey, primaryModel, wavBytes, useMinimalThinking = !primaryModel.contains("lite", ignoreCase = true), isHindi = isHindi)
        } catch (e: Exception) {
            if (primaryModel != "gemini-3.5-flash-lite" || !primaryModel.contains("lite", ignoreCase = true)) {
                callGeminiWithModel(apiKey, "gemini-3.5-flash-lite", wavBytes, useMinimalThinking = false, isHindi = isHindi)
            } else {
                throw e
            }
        }
    }

    private fun callGeminiWithModel(apiKey: String, model: String, wavBytes: ByteArray, useMinimalThinking: Boolean, isHindi: Boolean): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val b64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val prompt = if (isHindi) {
            buildString {
                append("The speaker is speaking Hindi (हिन्दी) with occasional English medical or drug terms. ")
                append("Transcribe Hindi speech in clean Devanagari script (हिन्दी)—never transliterate Hindi into Roman letters. ")
                append("Write English medical terms, drug/brand names (e.g. Ceftriaxone, Piptaz, SpO2, ECG), and digits in English. ")
                append("If the audio is only breath, filler ('hmm', 'uh'), or background noise, return an empty string. ")
                append("Output ONLY the transcribed text with zero commentary.")
            }
        } else {
            buildString {
                append("The speaker is speaking Odia (ଓଡ଼ିଆ) with occasional English medical or drug terms. ")
                append("Always transcribe Odia speech in pure Odia script (ଓଡ଼ିଆ)—never transliterate Odia into Roman/Hinglish letters, ")
                append("and NEVER output Malayalam, Gujarati, Bengali, or Devanagari characters. ")
                append("Write only actual English medical terms, drug/brand names (e.g. Piptaz, Monocef, SpO2, ECG), and digits in English. ")
                append("If the audio is only breath, filler ('hmm', 'humm', 'uh'), or background noise, return an empty string. ")
                append("Output ONLY the transcribed text with zero commentary.")
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
        Regex("""(?i)\bamara\.org\b""")
    )

    fun postProcessTranscript(ctx: Context, raw: String, engine: AsrEngineType): String {
        var text = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        if (text.isEmpty()) return ""
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
            .replace(Regex("(?i)\\b(?:umm+|uhh+|err+m*|ahh+)\\b[,\\s]*"), " ")

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
            text = text.replace(Regex("[\u0900-\u0DFF]+"), " ").replace(Regex("[ \t]{2,}"), " ").trim()
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

    @Volatile
    private var cachedBhashiniCallbackUrl: String? = null
    @Volatile
    private var cachedBhashiniAuthHeader: String? = null
    @Volatile
    private var cachedBhashiniAuthValue: String? = null
    @Volatile
    private var cachedBhashiniServiceId: String? = null

    private fun ensureBhashiniOdiaConfig(userId: String, apiKey: String) {
        if (!cachedBhashiniCallbackUrl.isNullOrBlank() &&
            !cachedBhashiniAuthValue.isNullOrBlank() &&
            !cachedBhashiniServiceId.isNullOrBlank()
        ) return

        val url = URL("https://meity-auth.ulcacontrib.org/ulca/apis/v0/model/getModelsPipeline")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("userID", userId)
            setRequestProperty("ulcaApiKey", apiKey)
        }
        val reqJson = JSONObject().apply {
            put("pipelineTasks", JSONArray().apply {
                put(JSONObject().apply {
                    put("taskType", "asr")
                    put("config", JSONObject().apply {
                        put("language", JSONObject().put("sourceLanguage", "or"))
                    })
                })
            })
            put("pipelineRequestConfig", JSONObject().put("pipelineId", "64392f96daac500b55c543cd"))
        }
        conn.outputStream.use { it.write(reqJson.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = readHttpBody(conn, code)
        if (code !in 200..299) {
            throw IllegalStateException("BHASHINI config failed ($code)")
        }
        val root = JSONObject(body)
        val ep = root.getJSONObject("pipelineInferenceAPIEndPoint")
        val callbackUrl = ep.getString("callbackUrl")
        val authKeyObj = ep.getJSONObject("inferenceApiKey")
        val authName = authKeyObj.getString("name")
        val authVal = authKeyObj.getString("value")
        val serviceId = root.getJSONArray("pipelineResponseConfig")
            .getJSONObject(0)
            .getJSONArray("config")
            .getJSONObject(0)
            .getString("serviceId")

        cachedBhashiniCallbackUrl = callbackUrl
        cachedBhashiniAuthHeader = authName
        cachedBhashiniAuthValue = authVal
        cachedBhashiniServiceId = serviceId
    }

    private fun callBhashiniOdia(ctx: Context, wavBytes: ByteArray): String {
        val userId = Prefs.getBhashiniUserId(ctx)
        val apiKey = Prefs.getBhashiniApiKey(ctx)
        if (userId.isBlank() || apiKey.isBlank()) {
            throw IllegalStateException("Add your free BHASHINI User ID & API Key in MedUkti Settings")
        }
        ensureBhashiniOdiaConfig(userId, apiKey)
        val callbackUrl = cachedBhashiniCallbackUrl ?: throw IllegalStateException("BHASHINI endpoint unavailable")
        val authName = cachedBhashiniAuthHeader ?: "Authorization"
        val authVal = cachedBhashiniAuthValue ?: ""
        val serviceId = cachedBhashiniServiceId ?: ""

        val b64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("pipelineTasks", JSONArray().apply {
                put(JSONObject().apply {
                    put("taskType", "asr")
                    put("config", JSONObject().apply {
                        put("language", JSONObject().put("sourceLanguage", "or"))
                        put("serviceId", serviceId)
                        put("audioFormat", "wav")
                        put("samplingRate", 16000)
                    })
                })
            })
            put("inputData", JSONObject().apply {
                put("audio", JSONArray().apply {
                    put(JSONObject().put("audioContent", b64Audio))
                })
            })
        }

        val conn = (URL(callbackUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (authVal.isNotBlank()) setRequestProperty(authName, authVal)
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = readHttpBody(conn, code)
        if (code !in 200..299) {
            cachedBhashiniCallbackUrl = null
            throw IllegalStateException("BHASHINI ASR error ($code)")
        }
        val resp = JSONObject(body)
        val outputArr = resp.optJSONArray("pipelineResponse")
            ?.optJSONObject(0)
            ?.optJSONArray("output")
        return outputArr?.optJSONObject(0)?.optString("source")?.trim() ?: ""
    }

    fun transcribeLivePreview(ctx: Context, engine: AsrEngineType, samples: FloatArray): Result<String> {
        return try {
            val minRms = if (!Prefs.isIndicScriptEngine(engine)) 0.006f else 0.002f
            if (samples.size < 4800 || computeRms(samples) < minRms) return Result.success("")
            val wav = encodeWav16kMono(samples)
            val raw = when (engine) {
                AsrEngineType.GROQ_MED, AsrEngineType.GROQ_HINGLISH ->
                    callGroqWhisper(ctx, wav, "whisper-large-v3-turbo", isLivePreview = true)
                AsrEngineType.GEMINI_ODIA -> {
                    if (Prefs.getOdiaBackend(ctx) == OdiaBackend.GEMINI) {
                        val key = Prefs.getGeminiKey(ctx)
                        if (key.isBlank()) "" else callGeminiWithModel(key, "gemini-3.5-flash-lite", wav, useMinimalThinking = false, isHindi = false)
                    } else ""
                }
                AsrEngineType.GEMINI_HINDI -> {
                    val key = Prefs.getGeminiKey(ctx)
                    if (key.isBlank()) "" else callGeminiWithModel(key, "gemini-3.5-flash-lite", wav, useMinimalThinking = false, isHindi = true)
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
            val raw = when (engine) {
                AsrEngineType.GROQ_MED, AsrEngineType.GROQ_HINGLISH -> {
                    val stt = callGroqWhisper(ctx, wav, Prefs.getGroqAsrModel(ctx), isLivePreview = false)
                    if (stt.isNotBlank() && Prefs.isAiPolishEnabled(ctx)) {
                        callGroqPolish(ctx, stt, engine == AsrEngineType.GROQ_HINGLISH)
                    } else {
                        stt
                    }
                }
                AsrEngineType.GEMINI_ODIA -> when (Prefs.getOdiaBackend(ctx)) {
                    OdiaBackend.BHASHINI -> callBhashiniOdia(ctx, wav)
                    OdiaBackend.GEMINI, OdiaBackend.ANDROID_NATIVE -> callGeminiIndic(ctx, wav, isHindi = false)
                }
                AsrEngineType.GEMINI_HINDI -> callGeminiIndic(ctx, wav, isHindi = true)
            }
            Result.success(postProcessTranscript(ctx, raw, engine))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

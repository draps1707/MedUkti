package com.voicerambler.free

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiClient {

    fun buildSystemPrompt(mode: DictationMode, glossary: String): String {
        val modeRules = when (mode) {
            DictationMode.HINGLISH_CHAT -> """
                - Language: Mixed English, Hindi, and Hinglish.
                - Script rule: Write English and Hinglish in natural Roman script (do NOT convert Hinglish to Devanagari unless the speaker clearly dictated pure formal Hindi).
                - Tone: Casual, crisp, natural chat style.
            """.trimIndent()

            DictationMode.ODIA_ENGLISH -> """
                - Language: Mixed Odia, Hindi, and English.
                - Script rule: Write Odia phrases in Odia script (ଓଡ଼ିଆ) and English words/technical terms in English script. Fix any phonetic English misrecognitions of common Odia or Hindi words.
                - Tone: Natural and accurate.
            """.trimIndent()

            DictationMode.FORMAL_ENGLISH -> """
                - Language: Clean, polished English (smooth out Hindi or Odia fillers if needed, or preserve proper nouns).
                - Tone: Professional, structured, ready to send as an email or clinical/work note. Format lists as bullet points if spoken as a list.
            """.trimIndent()

            DictationMode.VOICE_EDIT -> """
                - Task: You will be given EXISTING TEXT from a text box and a SPOKEN VOICE COMMAND.
                - Apply the spoken command (e.g., "make this shorter", "fix grammar", "translate to formal English", "change Tuesday to Wednesday") to the existing text.
                - Return ONLY the final updated text.
            """.trimIndent()
        }

        val glossaryBlock = if (glossary.isNotBlank()) {
            "\nPersonal vocabulary / exact spellings to prefer when heard: $glossary"
        } else ""

        return """
            You are an ultra-accurate voice dictation cleanup engine like Google Rambler and Wispr Flow.
            Core rules:
            1. Remove all filler words (um, uh, ah, matlab, basically, you know), false starts, and self-corrections (keep only the speaker's final intended phrasing).
            2. Add accurate punctuation, capitalization, and paragraph breaks.
            3. Output ONLY the final cleaned text. Never add quotes, explanations, or preambles.
            $modeRules$glossaryBlock
        """.trimIndent()
    }

    suspend fun polishText(
        apiKey: String,
        preferredModel: String,
        rawTranscript: String,
        mode: DictationMode,
        glossary: String,
        existingText: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val modelsToTry = (listOf(preferredModel) + Prefs.AVAILABLE_MODELS).distinct()
        var lastError: Exception = Exception("Unknown error")

        for (model in modelsToTry) {
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val url = URL(endpoint)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connectTimeout = 10000
                    readTimeout = 20000
                    doOutput = true
                }

                val userInstruction = if (mode == DictationMode.VOICE_EDIT && !existingText.isNullOrBlank()) {
                    "EXISTING TEXT:\n$existingText\n\nSPOKEN COMMAND:\n$rawTranscript"
                } else {
                    "Clean up and format this live speech transcript:\n$rawTranscript"
                }

                val payload = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", buildSystemPrompt(mode, glossary))))
                    })
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", userInstruction)))
                    }))
                    if (model.startsWith("gemini-3.8")) {
                        put("generationConfig", JSONObject().apply {
                            put("thinkingConfig", JSONObject().apply {
                                put("thinkingLevel", "LOW")
                            })
                        })
                    }
                }

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream.bufferedReader().use { it.readText() }

                if (code == 503 || code == 429 || code == 404) {
                    lastError = Exception("HTTP $code on $model")
                    continue
                }
                if (code !in 200..299) {
                    return@withContext Result.failure(Exception("HTTP $code: $body"))
                }

                val json = JSONObject(body)
                val text = json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()

                return@withContext Result.success(text)
            } catch (e: Exception) {
                lastError = e
            }
        }
        Result.failure(lastError)
    }
}

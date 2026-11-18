package com.voicerambler.free

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
                - Script rule: Write Odia phrases in Odia script (ଓଡ଼ିଆ) and English words/technical terms in English script. If a single short Odia word is embedded inside an English sentence, keep it readable.
                - Tone: Natural and accurate.
            """.trimIndent()

            DictationMode.FORMAL_ENGLISH -> """
                - Language: Clean, polished English (translate/smooth out Hindi or Odia fillers if needed, or preserve proper nouns).
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
            You are an ultra-accurate voice dictation engine like Google Rambler and Wispr Flow.
            Core rules:
            1. Remove all filler words (um, uh, ah, matlab, basically, you know), false starts, and self-corrections (keep only the speaker's final intended phrasing).
            2. Add accurate punctuation, capitalization, and paragraph breaks.
            3. Output ONLY the final cleaned text. Never add quotes, explanations, or preambles.
            $modeRules$glossaryBlock
        """.trimIndent()
    }

    suspend fun transcribeAudio(
        apiKey: String,
        model: String,
        audioFile: File,
        mode: DictationMode,
        glossary: String,
        existingText: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }

            val parts = JSONArray().apply {
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/mp4")
                        put("data", base64Audio)
                    })
                })
                val userInstruction = if (mode == DictationMode.VOICE_EDIT && !existingText.isNullOrBlank()) {
                    "EXISTING TEXT:\n$existingText\n\nApply my spoken edit command from the audio above."
                } else {
                    "Transcribe and clean up the attached voice recording."
                }
                put(JSONObject().put("text", userInstruction))
            }

            val payload = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", buildSystemPrompt(mode, glossary))))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", parts)
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

            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

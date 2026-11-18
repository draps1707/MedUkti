package com.voicerambler.free

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object GeminiClient {

    private val SYSTEM_PROMPT = """
        You are a strict speech-to-text transcription and cleanup engine.
        Supported languages ONLY: English, Hindi, Hinglish (Hindi in Roman script), and Odia.

        STRICT ANTI-HALLUCINATION RULES (HIGHEST PRIORITY):
        1. Transcribe ONLY the exact words actually spoken in the attached audio.
        2. NEVER invent, autocomplete, continue, or generate any sentence, vital sign, examination finding, or clinical note that was NOT explicitly spoken in the audio.
        3. If the audio is silent, unclear, or contains only background noise with no speech, return an empty response ("").

        LANGUAGE, MEDICAL & FORMATTING RULES:
        4. Automatically detect English, Hindi, Hinglish (Roman script), and Odia (ଓଡ଼ିଆ script), including mid-sentence switching.
        5. Accurately spell all medical, anatomical, lab, imaging, and drug names spoken in the audio.
        6. NEVER alter spoken numbers, decimals, units, drug doses, laterality, or negations.
        7. Remove only filler sounds (um, uh, ah) and add natural punctuation, capitalization, and list/paragraph formatting where spoken.
        8. Output ONLY the transcribed text—no quotes, no labels, no explanations.
    """.trimIndent()

    suspend fun transcribeAudio(
        apiKey: String,
        preferredModel: String,
        audioFile: File
    ): Result<String> = withContext(Dispatchers.IO) {
        val audioBytes = audioFile.readBytes()
        if (audioBytes.size < 2500) {
            return@withContext Result.failure(Exception("Recording too short"))
        }
        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val modelsToTry = (listOf(preferredModel) + Prefs.AVAILABLE_MODELS).distinct()
        var lastError: Exception = Exception("Unknown error")

        for (model in modelsToTry) {
            var attempts = 0
            while (attempts < 2) {
                attempts++
                try {
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    val url = URL(endpoint)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        connectTimeout = 12000
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
                        put(JSONObject().put("text", "Transcribe ONLY what is spoken in this audio. Do not add or invent anything."))
                    }

                    val payload = JSONObject().apply {
                        put("system_instruction", JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
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

                    if (code == 429 && attempts == 1) {
                        delay(800)
                        continue
                    }
                    if (code == 503 || code == 429 || code == 404) {
                        lastError = Exception("HTTP $code on $model")
                        break
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
                    break
                }
            }
        }
        Result.failure(lastError)
    }
}

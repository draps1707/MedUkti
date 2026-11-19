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
        You are an ultra-accurate multilingual and medical text cleanup engine.
        Supported languages ONLY: English, Hindi, Hinglish (Hindi in Roman script), and Odia.

        STRICT CLINICAL & ANTI-HALLUCINATION RULES:
        1. Output ONLY what was spoken or provided—NEVER invent, autocomplete, or add clinical findings.
        2. NEVER alter numbers, decimals, lab values, vitals, units, laterality, or negations (no, not, nil, absent, nahi).
        3. Keep English in English, Hinglish in Roman script, and Odia in Odia script (ଓଡ଼ିଆ).
        4. Add natural punctuation, capitalization, and list/paragraph formatting.
        5. Return ONLY the cleaned text with no quotes or explanations.
    """.trimIndent()

    suspend fun transcribeWav(
        apiKey: String,
        preferredModel: String,
        wavFile: File
    ): Result<String> {
        val bytes = wavFile.readBytes()
        if (bytes.size < 3200) return Result.failure(Exception("Recording too short"))
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val parts = JSONArray().apply {
            put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "audio/wav")
                    put("data", b64)
                })
            })
            put(JSONObject().put("text", "Transcribe ONLY what is spoken in this audio."))
        }
        return callGenerateContent(apiKey, preferredModel, parts)
    }

    suspend fun polishOfflineTranscript(
        apiKey: String,
        preferredModel: String,
        rawText: String
    ): Result<String> {
        val parts = JSONArray().put(
            JSONObject().put("text", "Clean punctuation and medical spelling without changing any numbers or negations:\n$rawText")
        )
        return callGenerateContent(apiKey, preferredModel, parts)
    }

    private suspend fun callGenerateContent(
        apiKey: String,
        preferredModel: String,
        parts: JSONArray
    ): Result<String> = withContext(Dispatchers.IO) {
        val modelsToTry = (listOf(preferredModel) + Prefs.AVAILABLE_MODELS).distinct()
        var lastError: Exception = Exception("Unknown error")

        for (model in modelsToTry) {
            var attempts = 0
            while (attempts < 2) {
                attempts++
                try {
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        connectTimeout = 12000
                        readTimeout = 30000
                        doOutput = true
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

                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

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

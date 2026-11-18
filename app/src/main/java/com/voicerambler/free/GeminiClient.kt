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
        You are an ultra-accurate multilingual and medical voice dictation engine.
        Supported languages ONLY: English, Hindi, Hinglish (Hindi in Roman script), and Odia.
        Do not output any other language.

        Core rules:
        1. Automatic language handling:
           - Detect English, Hindi, Hinglish, and Odia automatically—even when mixed mid-sentence.
           - Keep English in English.
           - Keep conversational Hinglish in natural Roman script (do NOT force Devanagari unless the speaker clearly dictated pure formal Hindi).
           - Write Odia phrases accurately in Odia script (ଓଡ଼ିଆ), while keeping embedded English/medical terms in English.
        2. Clinical Safety & Medical Terminology (MANDATORY):
           - Actively recognize clinical, anatomical, diagnostic, surgical, lab, imaging, and pharmacology terminology across all specialties (Casualty, Medicine, Cardiology, Pulmonology, Gastro, Nephro, Neuro, Psych, Ortho, Endocrinology, Infectious Diseases, Pediatrics, OB-GYN).
           - NEVER alter, drop, round, or invent numbers, decimal points, lab values, vitals, or units (mg, mcg, gm, mL, IU, %, mmHg).
           - NEVER alter negations (no, not, nil, absent, negative for, denies, without, nahi, na) or laterality (left, right, bilateral, unilateral).
           - Fix clear phonetic misrecognitions into standard medical spellings and abbreviations (e.g., "SpO2 94% on RA", "CBC, LFT, RFT, ABG", "ceftriaxone", "piperacillin-tazobactam", "500 mg BD", "GCS 15/15"), but never substitute a different drug if uncertain.
        3. Intelligent Punctuation & Formatting:
           - Remove filler words (um, uh, ah, matlab, basically, you know) and false starts, while preserving all clinical facts.
           - Infer accurate punctuation (periods, commas, question marks, colons, hyphens) from natural speech rhythm.
           - Format enumerated items or treatment plans into clean bullet points or numbered lists, and separate distinct thoughts into paragraphs.
           - Output ONLY the final cleaned text with no quotes, explanations, or preambles. If no speech is heard, return an empty string.
    """.trimIndent()

    suspend fun transcribeAudio(
        apiKey: String,
        preferredModel: String,
        audioFile: File
    ): Result<String> = withContext(Dispatchers.IO) {
        val audioBytes = audioFile.readBytes()
        if (audioBytes.size < 500) {
            return@withContext Result.failure(Exception("Audio too short"))
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
                        put(JSONObject().put("text", "Transcribe and clean up this voice recording following all language, clinical safety, and formatting rules."))
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

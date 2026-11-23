package com.medukti.keyboard

import android.content.Context

enum class AsrEngineType(val label: String, val badge: String) {
    CLINICAL("🩺 — English + Hinglish + Medical (Groq Whisper + LLM)", "🩺"),
    ODIA("ଓ — Conversational Odia (ElevenLabs Scribe / Gemini)", "ଓ"),
}

enum class OdiaBackend(val label: String) {
    ELEVENLABS("ElevenLabs Scribe v2 (Fastest & Best Casual Odia)"),
    GEMINI("Gemini 3.8 Flash (Tuned Casual Odisha Odia)")
}

object Prefs {
    private const val FILE = "medukti_prefs"
    private const val KEY_ENGINE = "asr_engine"
    private const val KEY_GROQ_KEY = "groq_api_key"
    private const val KEY_GEMINI_KEY = "gemini_api_key"
    private const val KEY_ELEVENLABS_KEY = "elevenlabs_api_key"
    private const val KEY_GROQ_ASR_MODEL = "groq_asr_model"
    private const val KEY_GROQ_LLM_MODEL = "groq_llm_model"
    private const val KEY_GEMINI_MODEL = "gemini_model"
    private const val KEY_ODIA_BACKEND = "odia_backend"
    private const val KEY_AI_POLISH = "ai_polish_enabled"
    private const val KEY_AUTO_LISTEN = "auto_listen_ime"
    private const val KEY_LIVE_STREAM = "live_streaming_preview_v2"
    private const val KEY_PERSONAL_DICT = "personal_dictionary"

    fun getEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, AsrEngineType.CLINICAL.name)
        return when (raw) {
            "ODIA", "GEMINI_ODIA" -> AsrEngineType.ODIA
            else -> AsrEngineType.CLINICAL
        }
    }

    fun isOdiaEngine(e: AsrEngineType): Boolean = e == AsrEngineType.ODIA

    fun isIndicScriptEngine(e: AsrEngineType): Boolean = e == AsrEngineType.ODIA

    fun setEngine(ctx: Context, engine: AsrEngineType) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENGINE, engine.name)
            .apply()
    }

    fun getOdiaBackend(ctx: Context): OdiaBackend {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ODIA_BACKEND, OdiaBackend.ELEVENLABS.name)
        return OdiaBackend.entries.firstOrNull { it.name == raw }
            ?: OdiaBackend.ELEVENLABS
    }

    fun setOdiaBackend(ctx: Context, backend: OdiaBackend) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ODIA_BACKEND, backend.name)
            .apply()
    }

    fun getGroqKey(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_GROQ_KEY, "") ?: ""

    fun setGroqKey(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_GROQ_KEY, value.trim())
            .apply()
    }

    fun getGeminiKey(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_GEMINI_KEY, "") ?: ""

    fun setGeminiKey(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_GEMINI_KEY, value.trim())
            .apply()
    }

    fun getElevenLabsKey(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ELEVENLABS_KEY, "") ?: ""

    fun setElevenLabsKey(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ELEVENLABS_KEY, value.trim())
            .apply()
    }

    fun getGroqAsrModel(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_GROQ_ASR_MODEL, "whisper-large-v3")
            ?.ifBlank { "whisper-large-v3" } ?: "whisper-large-v3"

    fun setGroqAsrModel(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_GROQ_ASR_MODEL, value.trim().ifBlank { "whisper-large-v3" })
            .apply()
    }

    fun getGroqLlmModel(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_GROQ_LLM_MODEL, "openai/gpt-oss-120b")
            ?.ifBlank { "openai/gpt-oss-120b" } ?: "openai/gpt-oss-120b"

    fun setGroqLlmModel(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_GROQ_LLM_MODEL, value.trim().ifBlank { "openai/gpt-oss-120b" })
            .apply()
    }

    fun getGeminiModel(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_GEMINI_MODEL, "gemini-3.8-flash")
            ?.ifBlank { "gemini-3.8-flash" } ?: "gemini-3.8-flash"

    fun setGeminiModel(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_GEMINI_MODEL, value.trim().ifBlank { "gemini-3.8-flash" })
            .apply()
    }

    fun isAiPolishEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AI_POLISH, true)

    fun setAiPolishEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AI_POLISH, enabled)
            .apply()
    }

    fun isAutoListen(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_LISTEN, false)

    fun setAutoListen(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_LISTEN, enabled)
            .apply()
    }

    fun isLiveStreamingEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_LIVE_STREAM, false)

    fun setLiveStreamingEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LIVE_STREAM, enabled)
            .apply()
    }

    fun getPersonalDict(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PERSONAL_DICT, "") ?: ""

    fun setPersonalDict(ctx: Context, raw: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_PERSONAL_DICT, raw.trim())
            .apply()
    }

    fun appendPersonalFix(ctx: Context, wrong: String, right: String) {
        val w = wrong.trim()
        val r = right.trim()
        if (w.isEmpty() || r.isEmpty() || w.equals(r, ignoreCase = true)) return
        val existing = getPersonalDict(ctx).lines().filter { it.isNotBlank() }.toMutableList()
        val prefix = "${w.lowercase()}="
        existing.removeAll { it.substringBefore('=').trim().lowercase() + "=" == prefix }
        existing.add("$w = $r")
        setPersonalDict(ctx, existing.joinToString("\n"))
    }
}

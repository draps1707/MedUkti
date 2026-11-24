package com.medukti.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class AsrEngineType(val label: String, val badge: String) {
    ODIA("ଓ — Odia Conversational (ElevenLabs Scribe v2)", "ଓ"),
    MEDICAL("🩺 — English + Medical (Groq Whisper + GPT-OSS 120B)", "🩺"),
    HINGLISH("हin — Hinglish + Medical (Gemini 3.8 Flash)", "हin"),
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
    private const val KEY_BACKUP_GROQ = "vault_backup_groq"
    private const val KEY_BACKUP_GEMINI = "vault_backup_gemini"
    private const val KEY_BACKUP_ELEVENLABS = "vault_backup_elevenlabs"
    private const val KEY_GROQ_ASR_MODEL = "groq_asr_model_v2"
    private const val KEY_GROQ_LLM_MODEL = "groq_llm_model"
    private const val KEY_GEMINI_MODEL = "gemini_model_v2"
    private const val KEY_ODIA_BACKEND = "odia_backend"
    private const val KEY_AI_POLISH = "ai_polish_enabled"
    private const val KEY_AUTO_LISTEN = "auto_listen_ime"
    private const val KEY_LIVE_STREAM = "live_streaming_preview_v2"
    private const val KEY_DENOISER = "neural_gtcrn_denoiser_v1"
    private const val KEY_VOICEPRINT_LOCK = "voiceprint_lock_enabled"
    private const val KEY_VOICEPRINT_1 = "voiceprint_emb_1"
    private const val KEY_VOICEPRINT_2 = "voiceprint_emb_2"
    private const val KEY_VOICEPRINT_THRESHOLD = "voiceprint_threshold"
    private const val KEY_PERSONAL_DICT = "personal_dictionary"
    private const val KEY_DICTATION_HISTORY = "dictation_history_v1"

    fun getEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, AsrEngineType.MEDICAL.name)
        return when (raw) {
            "ODIA", "GEMINI_ODIA" -> AsrEngineType.ODIA
            "HINGLISH", "GROQ_HINGLISH" -> AsrEngineType.HINGLISH
            else -> AsrEngineType.MEDICAL
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
        val v = value.trim()
        val ed = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_GROQ_KEY, v)
        if (v.isNotBlank()) ed.putString(KEY_BACKUP_GROQ, v)
        ed.apply()
    }

    fun getGeminiKey(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_GEMINI_KEY, "") ?: ""

    fun setGeminiKey(ctx: Context, value: String) {
        val v = value.trim()
        val ed = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_GEMINI_KEY, v)
        if (v.isNotBlank()) ed.putString(KEY_BACKUP_GEMINI, v)
        ed.apply()
    }

    fun getElevenLabsKey(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ELEVENLABS_KEY, "") ?: ""

    fun setElevenLabsKey(ctx: Context, value: String) {
        val v = value.trim()
        val ed = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_ELEVENLABS_KEY, v)
        if (v.isNotBlank()) ed.putString(KEY_BACKUP_ELEVENLABS, v)
        ed.apply()
    }

    fun restoreKeysFromVault(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val g = sp.getString(KEY_BACKUP_GROQ, "") ?: ""
        val m = sp.getString(KEY_BACKUP_GEMINI, "") ?: ""
        val e = sp.getString(KEY_BACKUP_ELEVENLABS, "") ?: ""
        var count = 0
        val ed = sp.edit()
        if (g.isNotBlank()) { ed.putString(KEY_GROQ_KEY, g); count++ }
        if (m.isNotBlank()) { ed.putString(KEY_GEMINI_KEY, m); count++ }
        if (e.isNotBlank()) { ed.putString(KEY_ELEVENLABS_KEY, e); count++ }
        ed.apply()
        return count
    }

    fun exportAllBundle(ctx: Context): String {
        val obj = JSONObject()
        obj.put("groq", getGroqKey(ctx))
        obj.put("gemini", getGeminiKey(ctx))
        obj.put("elevenlabs", getElevenLabsKey(ctx))
        obj.put("fixes", getPersonalDict(ctx))
        return obj.toString()
    }

    fun importAllBundle(ctx: Context, rawJson: String): Boolean {
        return try {
            val obj = JSONObject(rawJson.trim())
            val g = obj.optString("groq", "").trim()
            val m = obj.optString("gemini", "").trim()
            val e = obj.optString("elevenlabs", "").trim()
            val f = obj.optString("fixes", "").trim()
            if (g.isNotBlank()) setGroqKey(ctx, g)
            if (m.isNotBlank()) setGeminiKey(ctx, m)
            if (e.isNotBlank()) setElevenLabsKey(ctx, e)
            if (f.isNotBlank()) {
                val merged = (getPersonalDict(ctx).lines() + f.lines())
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && "=" in it }
                    .distinctBy { it.substringBefore('=').trim().lowercase() }
                    .joinToString("\n")
                setPersonalDict(ctx, merged)
            }
            g.isNotBlank() || m.isNotBlank() || e.isNotBlank() || f.isNotBlank()
        } catch (_: Exception) {
            false
        }
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

    fun isDenoiserEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DENOISER, true)

    fun setDenoiserEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DENOISER, enabled)
            .apply()
    }


    fun isVoiceprintLockEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VOICEPRINT_LOCK, true)

    fun setVoiceprintLockEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VOICEPRINT_LOCK, enabled)
            .apply()
    }

    fun getVoiceprintThreshold(ctx: Context): Float =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat(KEY_VOICEPRINT_THRESHOLD, 0.42f)

    fun getVoiceprint(ctx: Context, slot: Int): FloatArray? {
        val key = if (slot == 1) KEY_VOICEPRINT_1 else KEY_VOICEPRINT_2
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            if (arr.length() == 0) return null
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (_: Exception) {
            null
        }
    }

    fun setVoiceprint(ctx: Context, slot: Int, emb: FloatArray?) {
        val key = if (slot == 1) KEY_VOICEPRINT_1 else KEY_VOICEPRINT_2
        val ed = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (emb == null) {
            ed.remove(key)
        } else {
            val arr = JSONArray()
            for (v in emb) arr.put(v.toDouble())
            ed.putString(key, arr.toString())
        }
        ed.apply()
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

    fun appendHistory(ctx: Context, text: String) {
        val clean = text.trim()
        if (clean.length < 4) return
        val list = getHistory(ctx).toMutableList()
        list.removeAll { it == clean }
        list.add(0, clean)
        val trimmed = list.take(15)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it) }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_DICTATION_HISTORY, arr.toString())
            .apply()
    }

    fun getHistory(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_DICTATION_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}

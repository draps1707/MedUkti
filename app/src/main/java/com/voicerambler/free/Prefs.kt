package com.voicerambler.free

import android.content.Context
import org.json.JSONArray

enum class AsrEngineType(val label: String) {
    WHISPER_TURBO("Indian/Medical Pro — Whisper Large-v3-Turbo"),
    PARAKEET_INDIC("Indian English Fast — Parakeet-Indic v1"),
    DUAL_PARAKEET_110M_MEDASR("Medical Dual Fast — Parakeet 110M + Google MedASR"),
    DUAL_PARAKEET_06B_MEDASR("Medical Dual Pro — Parakeet 0.6B v2 + Google MedASR"),
    MEDASR_CTC("Medical Only — Google MedASR CTC"),
    PARAKEET_110M("English Fast — Parakeet TDT 110M"),
    PARAKEET_06B_V2("English Pro — Parakeet TDT 0.6B v2"),
    INDIC_CONFORMER_OR("Odia — AI4Bharat IndicConformer 600M (~661 MB)"),
    SRAVAANI_OR("Odia Natural — SraVaani 1.0 (~1.81 GB)")
}

object Prefs {
    private const val FILE = "voice_rambler_prefs"
    private const val KEY_ENGINE = "asr_engine"
    private const val KEY_MED_ENGINE = "med_asr_engine"
    private const val KEY_ODIA_ENGINE = "odia_asr_engine"
    private const val KEY_PERSONAL_DICT = "personal_dictionary"
    private const val KEY_HF_TOKEN = "hf_token"
    private const val KEY_AUTO_LISTEN = "auto_listen_ime"
    private const val KEY_STREAMING_DRAFT = "streaming_draft_med"
    private const val KEY_SNIPPETS = "rx_snippets"
    private const val KEY_RECENT_DICTATIONS = "recent_dictations_json"

    val DEFAULT_SNIPPETS = ""

    fun getEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, AsrEngineType.WHISPER_TURBO.name)
        return AsrEngineType.entries.firstOrNull { it.name == raw }
            ?: AsrEngineType.WHISPER_TURBO
    }

    fun isOdiaEngine(e: AsrEngineType): Boolean =
        e == AsrEngineType.INDIC_CONFORMER_OR || e == AsrEngineType.SRAVAANI_OR

    fun setEngine(ctx: Context, engine: AsrEngineType) {
        val editor = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENGINE, engine.name)
        if (isOdiaEngine(engine)) {
            editor.putString(KEY_ODIA_ENGINE, engine.name)
        } else {
            editor.putString(KEY_MED_ENGINE, engine.name)
        }
        editor.apply()
    }

    fun getMedEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MED_ENGINE, AsrEngineType.WHISPER_TURBO.name)
        val parsed = AsrEngineType.entries.firstOrNull { it.name == raw }
        return if (parsed != null && !isOdiaEngine(parsed)) parsed else AsrEngineType.WHISPER_TURBO
    }

    fun getOdiaEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ODIA_ENGINE, AsrEngineType.INDIC_CONFORMER_OR.name)
        val parsed = AsrEngineType.entries.firstOrNull { it.name == raw }
        return if (parsed != null && isOdiaEngine(parsed)) parsed else AsrEngineType.INDIC_CONFORMER_OR
    }

    fun isAutoListen(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_LISTEN, true)

    fun setAutoListen(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_LISTEN, enabled).apply()
    }

    fun isStreamingDraftEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_STREAMING_DRAFT, false)

    fun setStreamingDraftEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_STREAMING_DRAFT, enabled).apply()
    }

    fun getHfToken(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_HF_TOKEN, "") ?: ""

    fun setHfToken(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_HF_TOKEN, value.trim()).apply()
    }

    fun getPersonalDict(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PERSONAL_DICT, "") ?: ""

    fun setPersonalDict(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_PERSONAL_DICT, value).apply()
    }

    fun getSnippetsRaw(ctx: Context): String {
        val saved = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SNIPPETS, null)
        return if (saved.isNullOrBlank()) DEFAULT_SNIPPETS else saved
    }

    fun setSnippetsRaw(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_SNIPPETS, value).apply()
    }

    fun getSnippets(ctx: Context): List<Pair<String, String>> = emptyList()

    fun getRecentDictations(ctx: Context): List<String> = emptyList()

    fun addRecentDictation(ctx: Context, text: String) {}
}

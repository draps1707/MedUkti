package com.voicerambler.free

import android.content.Context

enum class AsrEngineType(val label: String) {
    WHISPER_HINGLISH_APEX("Hinglish — Whisper Apex (Offline ~1.03 GB)"),
    WHISPER_HINGLISH_SWIFT("Hinglish — Whisper Swift (Offline ~152 MB)"),
    INDIC_CONFORMER_OR("Odia — AI4Bharat IndicConformer 600M (Offline ~661 MB)"),
    GEMINI_CLOUD("Gemini Cloud Audio (Optional)")
}

object Prefs {
    private const val FILE = "voice_rambler_prefs"
    private const val KEY_ENGINE = "asr_engine"
    private const val KEY_GEMINI_OPTIONAL = "gemini_optional_polish"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"

    val AVAILABLE_MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash"
    )

    fun getEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ENGINE, AsrEngineType.WHISPER_HINGLISH_APEX.name)
        return AsrEngineType.entries.firstOrNull { it.name == raw } ?: AsrEngineType.WHISPER_HINGLISH_APEX
    }

    fun setEngine(ctx: Context, engine: AsrEngineType) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, engine.name).apply()
    }

    fun isGeminiPolishEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_GEMINI_OPTIONAL, false)

    fun setGeminiPolishEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_GEMINI_OPTIONAL, enabled).apply()
    }

    fun getApiKey(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_API, "") ?: ""

    fun setApiKey(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_API, value.trim()).apply()
    }

    fun getModel(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODEL, AVAILABLE_MODELS[0]) ?: AVAILABLE_MODELS[0]

    fun setModel(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MODEL, value).apply()
    }

    fun getBubbleX(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_BUBBLE_X, 40)

    fun getBubbleY(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_BUBBLE_Y, 400)

    fun setBubblePos(ctx: Context, x: Int, y: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BUBBLE_X, x)
            .putInt(KEY_BUBBLE_Y, y)
            .apply()
    }
}

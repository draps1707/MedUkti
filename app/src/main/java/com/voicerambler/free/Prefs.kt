package com.voicerambler.free

import android.content.Context

enum class DictationMode(val label: String) {
    HINGLISH_CHAT("Hinglish"),
    ODIA_ENGLISH("Odia+Eng"),
    FORMAL_ENGLISH("Formal"),
    VOICE_EDIT("Voice Edit")
}

object Prefs {
    private const val FILE = "voice_rambler_prefs"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"
    private const val KEY_MODE = "dictation_mode"
    private const val KEY_GLOSSARY = "personal_glossary"

    val AVAILABLE_MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash"
    )

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

    fun getMode(ctx: Context): DictationMode {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODE, DictationMode.HINGLISH_CHAT.name)
        return DictationMode.entries.firstOrNull { it.name == raw } ?: DictationMode.HINGLISH_CHAT
    }

    fun setMode(ctx: Context, mode: DictationMode) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getGlossary(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_GLOSSARY, "") ?: ""

    fun setGlossary(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_GLOSSARY, value.trim()).apply()
    }
}

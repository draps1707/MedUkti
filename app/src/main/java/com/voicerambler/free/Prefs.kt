package com.voicerambler.free

import android.content.Context

enum class AsrEngineType(val label: String) {
    WHISPER_HINGLISH_APEX("Hinglish — Whisper Apex (Offline ~1.03 GB)"),
    INDIC_CONFORMER_OR("Odia — AI4Bharat IndicConformer 600M (Offline ~661 MB)")
}

object Prefs {
    private const val FILE = "voice_rambler_prefs"
    private const val KEY_ENGINE = "asr_engine"
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"
    private const val KEY_PERSONAL_DICT = "personal_dictionary"

    fun getEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ENGINE, AsrEngineType.WHISPER_HINGLISH_APEX.name)
        return AsrEngineType.entries.firstOrNull { it.name == raw } ?: AsrEngineType.WHISPER_HINGLISH_APEX
    }

    fun setEngine(ctx: Context, engine: AsrEngineType) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, engine.name).apply()
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

    fun getPersonalDict(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PERSONAL_DICT, "") ?: ""

    fun setPersonalDict(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_PERSONAL_DICT, value).apply()
    }
}

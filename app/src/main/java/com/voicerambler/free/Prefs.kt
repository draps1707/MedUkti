package com.voicerambler.free

import android.content.Context

enum class AsrEngineType(val label: String) {
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
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"
    private const val KEY_PERSONAL_DICT = "personal_dictionary"

    fun getEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, AsrEngineType.DUAL_PARAKEET_06B_MEDASR.name)
        return AsrEngineType.entries.firstOrNull { it.name == raw }
            ?: AsrEngineType.DUAL_PARAKEET_06B_MEDASR
    }

    fun setEngine(ctx: Context, engine: AsrEngineType) {
        val editor = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENGINE, engine.name)
        if (engine == AsrEngineType.INDIC_CONFORMER_OR || engine == AsrEngineType.SRAVAANI_OR) {
            editor.putString(KEY_ODIA_ENGINE, engine.name)
        } else {
            editor.putString(KEY_MED_ENGINE, engine.name)
        }
        editor.apply()
    }

    fun getMedEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MED_ENGINE, AsrEngineType.DUAL_PARAKEET_06B_MEDASR.name)
        val parsed = AsrEngineType.entries.firstOrNull { it.name == raw }
        return if (parsed != null && parsed != AsrEngineType.INDIC_CONFORMER_OR && parsed != AsrEngineType.SRAVAANI_OR) {
            parsed
        } else {
            AsrEngineType.DUAL_PARAKEET_06B_MEDASR
        }
    }

    fun isOdiaEngine(e: AsrEngineType): Boolean =
        e == AsrEngineType.INDIC_CONFORMER_OR || e == AsrEngineType.SRAVAANI_OR

    fun getOdiaEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ODIA_ENGINE, AsrEngineType.INDIC_CONFORMER_OR.name)
        val parsed = AsrEngineType.entries.firstOrNull { it.name == raw }
        return if (parsed != null && isOdiaEngine(parsed)) parsed else AsrEngineType.INDIC_CONFORMER_OR
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

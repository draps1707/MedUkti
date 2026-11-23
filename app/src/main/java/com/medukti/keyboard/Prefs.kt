package com.medukti.keyboard

import android.content.Context

enum class AsrEngineType(val label: String, val badge: String) {
    GROQ_MED("MED — English + Medical (Groq Whisper + GPT-OSS 120B)", "MED"),
    GROQ_HINGLISH("HINGLISH — Hinglish + Medical (Groq Whisper + GPT-OSS 120B)", "HIN"),
    GEMINI_ODIA("OD — Odia + Medical (Gemini Flash)", "OD"),
    GEMINI_HINDI("HI — Hindi + Medical (Gemini Flash)", "HI"),
}

enum class FocusPack(val label: String, val terms: String) {
    GENERAL(
        "General / Casualty & ICU",
        "Sacubitril/valsartan, Dapagliflozin, Empagliflozin, Apixaban, Atorvastatin, Piptaz, Monocef, Augmentin, Pan-D, Telma, Glycomet, Dolo, Scrub typhus, Kyphosis, Lordosis, Rectus sheath, Procalcitonin, Troponin-I, NT-proBNP, Hyperkalaemia, Dyspnoea, Oedema"
    ),
    CARDIO_RENAL(
        "Cardio / Diabetes / Renal",
        "Sacubitril/valsartan, Dapagliflozin, Empagliflozin, Sitagliptin, Vildagliptin, Teneligliptin, Glimepiride, Gliclazide, Metformin, Glargine, Apixaban, Dabigatran, Rivaroxaban, Clopidogrel, Ticagrelor, Atorvastatin, Rosuvastatin, Telmisartan, Metoprolol, Bisoprolol, Diltiazem, Furosemide, Torsemide, Spironolactone, NT-proBNP, Troponin-I, HbA1c"
    ),
    ANTIMICROBIALS(
        "Antimicrobials / Infectious",
        "Amoxicillin, Clavulanic acid, Augmentin, Azithromycin, Clarithromycin, Cefixime, Cefotaxime, Ceftriaxone, Cefuroxime, Ceftazidime, Cefoperazone, Sulbactam, Piperacillin, Tazobactam, Piptaz, Meropenem, Imipenem, Ertapenem, Faropenem, Tigecycline, Linezolid, Vancomycin, Teicoplanin, Fosfomycin, Cotrimoxazole, Isoniazid, Rifampicin, Pyrazinamide, Ethambutol, Bedaquiline, Oseltamivir, Valacyclovir, Favipiravir, Tenofovir, Entecavir, Lamivudine, Voriconazole, Amphotericin B"
    ),
    ORTHO_DERM_NEURO(
        "Ortho / Derm / Neuropsych",
        "Pregabalin, Gabapentin, Amitriptyline, Nortriptyline, Duloxetine, Venlafaxine, Mirtazapine, Bupropion, Quetiapine, Levetiracetam, Lamotrigine, Baclofen, Thiocolchicoside, Etoricoxib, Celecoxib, Aceclofenac, Nimesulide, Ketorolac, Tramadol, Tapentadol, Rizatriptan, Fitzpatrick, Thoracic kyphosis, Lumbar lordosis, Cervical spondylosis, Luliconazole, Clobetasol"
    )
}

object Prefs {
    private const val FILE = "medukti_prefs"
    private const val KEY_ENGINE = "asr_engine"
    private const val KEY_FOCUS_PACK = "focus_pack"
    private const val KEY_GROQ_KEY = "groq_api_key"
    private const val KEY_GEMINI_KEY = "gemini_api_key"
    private const val KEY_GROQ_ASR_MODEL = "groq_asr_model"
    private const val KEY_GROQ_LLM_MODEL = "groq_llm_model"
    private const val KEY_GEMINI_MODEL = "gemini_model"
    private const val KEY_AI_POLISH = "ai_polish_enabled"
    private const val KEY_AUTO_LISTEN = "auto_listen_ime"
    private const val KEY_LIVE_STREAM = "live_streaming_preview"
    private const val KEY_PERSONAL_DICT = "personal_dictionary"

    fun getEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, AsrEngineType.GROQ_MED.name)
        return AsrEngineType.entries.firstOrNull { it.name == raw }
            ?: AsrEngineType.GROQ_MED
    }

    fun isOdiaEngine(e: AsrEngineType): Boolean = e == AsrEngineType.GEMINI_ODIA

    fun isGeminiEngine(e: AsrEngineType): Boolean =
        e == AsrEngineType.GEMINI_ODIA || e == AsrEngineType.GEMINI_HINDI

    fun isIndicScriptEngine(e: AsrEngineType): Boolean = isGeminiEngine(e)

    fun setEngine(ctx: Context, engine: AsrEngineType) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENGINE, engine.name)
            .apply()
    }

    fun getFocusPack(ctx: Context): FocusPack {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_FOCUS_PACK, FocusPack.GENERAL.name)
        return FocusPack.entries.firstOrNull { it.name == raw }
            ?: FocusPack.GENERAL
    }

    fun setFocusPack(ctx: Context, pack: FocusPack) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_FOCUS_PACK, pack.name)
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
            .getString(KEY_GEMINI_MODEL, "gemini-3.5-flash-lite")
            ?.ifBlank { "gemini-3.5-flash-lite" } ?: "gemini-3.5-flash-lite"

    fun setGeminiModel(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_GEMINI_MODEL, value.trim().ifBlank { "gemini-3.5-flash-lite" })
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
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_LISTEN, true)

    fun setAutoListen(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_LISTEN, enabled)
            .apply()
    }

    fun isLiveStreamingEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_LIVE_STREAM, true)

    fun setLiveStreamingEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LIVE_STREAM, enabled)
            .apply()
    }

    fun getPersonalDict(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PERSONAL_DICT, "") ?: ""

    fun setPersonalDict(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_PERSONAL_DICT, value)
            .apply()
    }
}

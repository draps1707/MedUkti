package com.voicerambler.free

import android.content.Context
import org.json.JSONArray

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
    private const val KEY_PERSONAL_DICT = "personal_dictionary"
    private const val KEY_AUTO_LISTEN = "auto_listen_ime"
    private const val KEY_STREAMING_DRAFT = "streaming_draft_med"
    private const val KEY_SNIPPETS = "rx_snippets"
    private const val KEY_RECENT_DICTATIONS = "recent_dictations_json"

    val DEFAULT_SNIPPETS = """
        OD @ 8AM | OD (ONCE A DAY) @ 8AM [ଦିନକୁ ଥରେ (ସକାଳେ ୮ଟା) | दिन में 1 बार (सुबह 8 बजे)]
        BD AF 8-8 | BD (TWICE A DAY) AF (AFTER FOOD) @ 8AM - 8PM [ଦିନକୁ ୨ ଥର - ଖାଇବା ପରେ (ସକାଳେ ୮ଟା - ରାତି ୮ଟା) | दिन में 2 बार - खाने के बाद (सुबह 8 बजे - रात 8 बजे)]
        BD AF (Short) | BD (TWICE A DAY) AF (AFTER FOOD) @ 8AM - 8PM [ଦିନକୁ ୨ ଥର - ଖାଇବା ପରେ | दिन में 2 बार - खाने के बाद]
        BD BF 7-7 | BD (TWICE A DAY) BF (BEFORE FOOD) @ 7AM - 7PM [ଦିନକୁ ୨ ଥର - ଖାଇବା ପୂର୍ବରୁ (ସକାଳେ ୭ଟା - ରାତି ୭ଟା) | दिन में 2 बार - खाने से पहले (सुबह 7 बजे - रात 7 बजे)]
        BD BF (Short) | BD (TWICE A DAY) BF (BEFORE FOOD) @ 7AM - 7PM [ଦିନକୁ ୨ ଥର - ଖାଇବା ପୂର୍ବରୁ | दिन में 2 बार - खाने से पहले]
        HS @ 10PM | HS (AT BEDTIME) @ 10PM [ରାତିରେ ଶୋଇବା ପୂର୍ବରୁ (ରାତି ୧୦ଟା) | रात को सोने से पहले (रात 10 बजे)]
        OD BBF 7AM | OD (ONCE A DAY) BBF (BEFORE BREAKFAST) @ 7AM [ଦିନକୁ ଥରେ - ଜଳଖିଆ ପୂର୍ବରୁ / ଖାଲି ପେଟରେ (ସକାଳେ ୭ଟା) | दिन में 1 बार - नाश्ते से पहले / खाली पेट (सुबह 7 बजे)]
        Template: OPD Note | Chief Complaints:\nHistory of Present Illness:\nOn Examination:\nVitals: BP  | PR  | SpO2 %\nProvisional Diagnosis:\nRx:\nAdvice:
        Template: Casualty Note | Presenting Complaints:\nVitals on Arrival: BP  | PR  | RR  | SpO2 % | GRBS \nPrimary Survey & O/E:\nProvisional Diagnosis:\nTreatment Given:\nPlan / Disposition:
        Template: Discharge Summary | Final Diagnosis:\nChief Complaints & Course in Hospital:\nSignificant Investigations:\nCondition at Discharge:\nDischarge Medications:\nFollow-up Advice:
    """.trimIndent()

    fun getEngine(ctx: Context): AsrEngineType {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, AsrEngineType.DUAL_PARAKEET_06B_MEDASR.name)
        return AsrEngineType.entries.firstOrNull { it.name == raw }
            ?: AsrEngineType.DUAL_PARAKEET_06B_MEDASR
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
            .getString(KEY_MED_ENGINE, AsrEngineType.DUAL_PARAKEET_06B_MEDASR.name)
        val parsed = AsrEngineType.entries.firstOrNull { it.name == raw }
        return if (parsed != null && !isOdiaEngine(parsed)) parsed else AsrEngineType.DUAL_PARAKEET_06B_MEDASR
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

    fun getSnippets(ctx: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (rawLine in getSnippetsRaw(ctx).lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val sepIdx = when {
                '|' in line -> line.indexOf('|')
                '\t' in line -> line.indexOf('\t')
                '=' in line -> line.indexOf('=')
                else -> -1
            }
            if (sepIdx > 0 && sepIdx < line.length - 1) {
                val label = line.substring(0, sepIdx).trim()
                val expansion = line.substring(sepIdx + 1).trim().replace("\\n", "\n")
                if (label.isNotEmpty() && expansion.isNotEmpty()) {
                    out.add(label to expansion)
                }
            }
        }
        return out
    }

    fun getRecentDictations(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_RECENT_DICTATIONS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { idx -> arr.getString(idx) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecentDictation(ctx: Context, text: String) {
        val clean = text.trim()
        if (clean.length < 2) return
        val list = getRecentDictations(ctx).toMutableList()
        list.remove(clean)
        list.add(0, clean)
        while (list.size > 8) list.removeAt(list.lastIndex)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_RECENT_DICTATIONS, arr.toString()).apply()
    }
}

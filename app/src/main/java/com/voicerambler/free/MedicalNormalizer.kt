package com.voicerambler.free

import android.content.Context

object MedicalNormalizer {

    private val canonicalTerms = LinkedHashMap<String, String>()
    @Volatile
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                ctx.assets.open("medical_dictionary.txt").bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        val term = raw.trim()
                        if (term.isNotEmpty()) {
                            canonicalTerms[term.lowercase()] = term
                        }
                    }
                }
            } catch (_: Exception) {
            }
            loaded = true
        }
    }

    private val PHONETIC_CORRECTIONS = linkedMapOf(
        "s p o 2" to "SpO2",
        "spo 2" to "SpO2",
        "spo2" to "SpO2",
        "c b c" to "CBC",
        "l f t" to "LFT",
        "r f t" to "RFT",
        "k f t" to "KFT",
        "a b g" to "ABG",
        "e c g" to "ECG",
        "h b a 1 c" to "HbA1c",
        "hba1c" to "HbA1c",
        "d dimer" to "D-dimer",
        "nt pro bnp" to "NT-proBNP",
        "g c s" to "GCS",
        "c o p d" to "COPD",
        "a k i" to "AKI",
        "c k d" to "CKD",
        "d k a" to "DKA",
        "u t i" to "UTI",
        "s t e m i" to "STEMI",
        "n s t e m i" to "NSTEMI",
        "pep taz" to "Piptaz",
        "pip taz" to "Piptaz",
        "sef triaxone" to "Ceftriaxone",
        "cef triaxone" to "Ceftriaxone",
        "mono sef" to "Monocef",
        "taxim o" to "Taxim O",
        "moxikind cv" to "Moxikind CV",
        "glycomet gp" to "Glycomet GP",
        "telma am" to "Telma AM",
        "metolar xl" to "Metolar XL",
        "pan d" to "Pan D",
        "once daily" to "OD",
        "twice daily" to "BD",
        "thrice daily" to "TDS",
        "four times daily" to "QID",
        "milligram" to "mg",
        "milligrams" to "mg",
        "microgram" to "mcg",
        "micrograms" to "mcg",
        "milliliter" to "mL",
        "milliliters" to "mL"
    )

    /**
     * Conservative offline normalizer:
     * 1. Fixes known phonetic splits/abbreviations.
     * 2. Restores canonical casing/spelling for any matched term from medical_dictionary.txt.
     * 3. NEVER alters existing digits, decimals, doses, or negations.
     */
    fun normalize(ctx: Context, raw: String): String {
        init(ctx)
        var text = raw.trim()
        if (text.isEmpty()) return ""

        for ((bad, good) in PHONETIC_CORRECTIONS) {
            val regex = Regex("\\b${Regex.escape(bad)}\\b", RegexOption.IGNORE_CASE)
            text = regex.replace(text, good)
        }

        // Apply single-word and multi-word canonical casing from Dr. Shukla's dictionary
        for ((lowerKey, canonical) in canonicalTerms) {
            if (lowerKey.length < 2) continue
            if (lowerKey.all { it.code < 128 }) {
                val regex = Regex("\\b${Regex.escape(lowerKey)}\\b", RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(text)) {
                    text = regex.replace(text, Regex.escapeReplacement(canonical))
                }
            }
        }

        if (text.isNotEmpty() && text[0] in 'a'..'z') {
            text = text[0].uppercaseChar() + text.substring(1)
        }

        return text.replace(Regex("\\s{2,}"), " ").trim()
    }
}

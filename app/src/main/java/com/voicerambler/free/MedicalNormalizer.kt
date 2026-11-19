package com.voicerambler.free

import android.content.Context
import java.io.File
import kotlin.math.abs

object MedicalNormalizer {

    private val singleWordMap = HashMap<String, String>()
    private val multiWordRules = ArrayList<Pair<Regex, String>>()
    private val fuzzyCandidates = ArrayList<Pair<String, String>>()

    @Volatile
    private var loaded = false

    private val PROTECTED_WORDS = setOf(
        "no", "not", "nil", "none", "never", "without", "negative", "positive",
        "normal", "absent", "present", "mild", "moderate", "severe",
        "mg", "mcg", "ml", "gm", "kg", "iu", "od", "bd", "tds", "qid", "hs", "sos", "stat",
        "iv", "im", "sc", "po", "pr", "sl"
    )

    private val PHONETIC_AND_SPELLING_BRIDGES = linkedMapOf(
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
        "pipp taz" to "Piptaz",
        "peptas" to "Piptaz",
        "sef triaxone" to "Ceftriaxone",
        "cef triaxone" to "Ceftriaxone",
        "safe triaxone" to "Ceftriaxone",
        "ceftryaxone" to "Ceftriaxone",
        "mono sef" to "Monocef",
        "taxim o" to "Taxim O",
        "moxikind cv" to "Moxikind CV",
        "glycomet gp" to "Glycomet GP",
        "telma am" to "Telma AM",
        "metolar xl" to "Metolar XL",
        "pan d" to "Pan D",
        "met for min" to "Metformin",
        "metformine" to "Metformin",
        "pan to prazole" to "Pantoprazole",
        "pantoprazol" to "Pantoprazole",
        "crea tinine" to "Creatinine",
        "create in in" to "Creatinine",
        "dyspnea" to "Dyspnoea",
        "dispnea" to "Dyspnoea",
        "orthopnea" to "Orthopnoea",
        "tachypnea" to "Tachypnoea",
        "bradypnea" to "Bradypnoea",
        "apnea" to "Apnoea",
        "edema" to "Oedema",
        "anemia" to "Anaemia",
        "hemoptysis" to "Haemoptysis",
        "hematemesis" to "Haematemesis",
        "hematochezia" to "Haematochezia",
        "diarrhea" to "Diarrhoea",
        "hyperkalemia" to "Hyperkalaemia",
        "hypokalemia" to "Hypokalaemia",
        "hypernatremia" to "Hypernatraemia",
        "hyponatremia" to "Hyponatraemia",
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

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            for ((k, v) in PHONETIC_AND_SPELLING_BRIDGES) {
                if (' ' in k) {
                    multiWordRules.add(Regex("\\b${Regex.escape(k)}\\b", RegexOption.IGNORE_CASE) to Regex.escapeReplacement(v))
                } else {
                    singleWordMap[k] = v
                }
            }
            try {
                ctx.assets.open("medical_dictionary.txt").bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        val term = raw.trim()
                        val lower = term.lowercase()
                        if (lower.length >= 2 && lower.all { it.code < 128 }) {
                            if (' ' in lower) {
                                multiWordRules.add(Regex("\\b${Regex.escape(lower)}\\b", RegexOption.IGNORE_CASE) to Regex.escapeReplacement(term))
                            } else {
                                singleWordMap.putIfAbsent(lower, term)
                                if (lower.length >= 6 && lower !in PROTECTED_WORDS) {
                                    fuzzyCandidates.add(lower to term)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            loaded = true
        }
    }

    /** Generates a hotwords.txt file in app filesDir for sherpa-onnx beam-search biasing. */
    fun ensureHotwordsFile(ctx: Context): File {
        init(ctx)
        val out = File(ModelDownloader.modelsDir(ctx), "medical_hotwords.txt")
        if (out.exists() && out.length() > 100L) return out
        val terms = LinkedHashSet<String>()
        try {
            ctx.assets.open("medical_dictionary.txt").bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val t = raw.trim()
                    if (t.length >= 4 && t.all { it.code < 128 }) {
                        terms.add(t.lowercase())
                    }
                }
            }
        } catch (_: Exception) {
        }
        out.writeText(terms.joinToString("\n"))
        return out
    }

    private fun levenshtein(a: String, b: String, maxDist: Int): Int {
        if (abs(a.length - b.length) > maxDist) return maxDist + 1
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = i - 1
            dp[0] = i
            var rowMin = dp[0]
            for (j in 1..b.length) {
                val cur = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = cur
                if (dp[j] < rowMin) rowMin = dp[j]
            }
            if (rowMin > maxDist) return maxDist + 1
        }
        return dp[b.length]
    }

    private fun fuzzyLookup(lowerWord: String): String? {
        if (lowerWord.length < 6 || lowerWord in PROTECTED_WORDS) return null
        val maxDist = if (lowerWord.length >= 9) 2 else 1
        val firstChar = lowerWord[0]
        var bestTerm: String? = null
        var bestDist = maxDist + 1
        var tie = false

        for ((candLower, candCanonical) in fuzzyCandidates) {
            if (candLower[0] != firstChar) continue
            if (abs(candLower.length - lowerWord.length) > maxDist) continue
            val d = levenshtein(lowerWord, candLower, maxDist)
            if (d < bestDist) {
                bestDist = d
                bestTerm = candCanonical
                tie = false
            } else if (d == bestDist && d <= maxDist && bestTerm != candCanonical) {
                tie = true
            }
        }
        return if (!tie && bestDist <= maxDist) bestTerm else null
    }

    /**
     * Conservative offline medical normalizer:
     * 1. Applies fast pre-compiled phonetic & UK/US medical bridges.
     * 2. Collapses split syllables ("pan to prazole" -> "Pantoprazole") when safe.
     * 3. Applies O(1) dictionary casing + conservative fuzzy matching (edit distance <= 1-2).
     * 4. NEVER alters digits, decimals, doses, units, or negations.
     */
    fun normalize(ctx: Context, raw: String): String {
        init(ctx)
        var text = raw.trim()
        if (text.isEmpty()) return ""

        for ((regex, replacement) in multiWordRules) {
            if (regex.containsMatchIn(text)) {
                text = regex.replace(text, replacement)
            }
        }

        val tokens = text.split(Regex("\\s+")).toMutableList()
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val w1 = tokens[i]
            val clean1 = w1.trimEnd('.', ',', ';', ':', '?', '!')
            val punct1 = w1.substring(clean1.length)
            val low1 = clean1.lowercase()

            // Try joining 2 adjacent pure-alpha tokens (e.g. "panto prazole" -> "Pantoprazole")
            if (i + 1 < tokens.size && clean1.all { it in 'a'..'z' || it in 'A'..'Z' } && low1 !in PROTECTED_WORDS) {
                val w2 = tokens[i + 1]
                val clean2 = w2.trimEnd('.', ',', ';', ':', '?', '!')
                val punct2 = w2.substring(clean2.length)
                val low2 = clean2.lowercase()
                if (clean2.all { it in 'a'..'z' || it in 'A'..'Z' } && low2 !in PROTECTED_WORDS) {
                    val joined = low1 + low2
                    val hit = singleWordMap[joined]
                    if (hit != null) {
                        out.add(hit + punct2)
                        i += 2
                        continue
                    }
                }
            }

            if (clean1.any { it.isDigit() } || low1 in PROTECTED_WORDS) {
                out.add(w1)
            } else {
                val exact = singleWordMap[low1]
                if (exact != null) {
                    out.add(exact + punct1)
                } else {
                    val fuzzy = fuzzyLookup(low1)
                    out.add((fuzzy ?: clean1) + punct1)
                }
            }
            i++
        }

        text = out.joinToString(" ")
        if (text.isNotEmpty() && text[0] in 'a'..'z') {
            text = text[0].uppercaseChar() + text.substring(1)
        }
        return text.replace(Regex("\\s{2,}"), " ").trim()
    }
}

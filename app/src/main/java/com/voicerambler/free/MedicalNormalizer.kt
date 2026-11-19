package com.voicerambler.free

import android.content.Context
import kotlin.math.abs

object MedicalNormalizer {

    private val singleWordMap = HashMap<String, String>()
    private val multiWordRules = ArrayList<Pair<Regex, String>>()
    private val fuzzyBuckets = HashMap<Char, ArrayList<Pair<String, String>>>()
    private val skeletonMap = HashMap<String, String>()

    @Volatile
    private var loaded = false

    private val PROTECTED_WORDS = setOf(
        "no", "not", "nil", "none", "never", "without", "negative", "positive",
        "normal", "absent", "present", "mild", "moderate", "severe",
        "mg", "mcg", "ml", "gm", "kg", "iu", "od", "bd", "tds", "qid", "hs", "sos", "stat",
        "iv", "im", "sc", "po", "pr", "sl",
        // Everyday Hinglish words that must never be overwritten by medical acronyms
        "aap", "hai", "hain", "main", "mere", "meri", "mera", "sahi", "nahi", "nahin", "haan",
        "kya", "kyu", "kyon", "bhai", "yaar", "kar", "karo", "kiya", "bana", "banaaya",
        "raha", "rahi", "rahe", "gaya", "gayi", "gaye", "lena", "dena", "wala", "wali", "wale",
        "abhi", "kab", "jab", "tab", "par", "aur", "bhi", "tak", "liye", "saath", "baat",
        "kaam", "din", "raat", "ghar", "log", "sab", "kuch", "koi", "yeh", "woh", "yah", "vah",
        "hum", "tum", "iss", "uss", "kis", "jis", "se", "ko", "ka", "ke", "ki", "na", "ho", "to", "ha", "me"
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
        "tapa glyphlogin" to "Dapagliflozin",
        "tapa gliflozin" to "Dapagliflozin",
        "dapa gliflozin" to "Dapagliflozin",
        "sita glyptin" to "Sitagliptin",
        "sita gliptin" to "Sitagliptin",
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

    private val ODIA_SUFFIXES = listOf("ଗୁଡ଼ିକ", "ଗୁଡିକ", "ଠାରୁ", "ରେ", "କୁ", "ରୁ", "ର", "ଟା", "ଟି", "ଙ୍କ")

    /** Curated Odia-script -> English stems (works with suffixes like ଡ୍ୟୁଟିରେ -> dutyରେ). */
    private val ODIA_TO_ENGLISH_BRIDGE = linkedMapOf(
        "ଓକେ" to "ok",
        "ସରି" to "sorry",
        "ଥ୍ୟାଙ୍କ ୟୁ" to "thank you",
        "ଥ୍ୟାଙ୍କ୍ସ" to "thanks",
        "ପ୍ଲିଜ" to "please",
        "ହେଲୋ" to "hello",
        "ହାଇ" to "hi",
        "ଗୁଡ ମର୍ଣ୍ଣିଂ" to "good morning",
        "ଗୁଡ ନାଇଟ" to "good night",
        "ମର୍ଣ୍ଣିଂ" to "morning",
        "ନାଇଟ" to "night",
        "ଟାଇମ" to "time",
        "ଫୋନ" to "phone",
        "କଲ" to "call",
        "ମେସେଜ" to "message",
        "ଆପ୍ପୁ" to "app ru",
        "ଆପ୍" to "app",
        "ଆପ" to "app",
        "ହ୍ୱାଟ୍ସଆପ" to "WhatsApp",
        "ହ୍ୱାଟ୍ସଆପ୍" to "WhatsApp",
        "ଫଟୋ" to "photo",
        "ଭିଡିଓ" to "video",
        "ଡ୍ୟୁଟି" to "duty",
        "କ୍ଲାସ" to "class",
        "କ୍ଲାସ୍" to "class",
        "ୱାର୍ଡ" to "ward",
        "କ୍ୟାଜୁଆଲ୍ଟି" to "Casualty",
        "ଡକ୍ଟର" to "doctor",
        "ଡାକ୍ତର" to "doctor",
        "ହସ୍ପିଟାଲ" to "hospital",
        "ହସ୍ପିଟାଲ୍" to "hospital",
        "କ୍ଲିନିକ" to "clinic",
        "ଓପିଡି" to "OPD",
        "ଆଇସିୟୁ" to "ICU",
        "ଏମରଜେନ୍ସି" to "emergency",
        "ପେସେଣ୍ଟ" to "patient",
        "ରିପୋର୍ଟ" to "report",
        "ଟେଷ୍ଟ" to "test",
        "ବ୍ଲଡ ଟେଷ୍ଟ" to "blood test",
        "ବ୍ଲଡ ପ୍ରେସର" to "blood pressure",
        "ବିପି" to "BP",
        "ସୁଗାର" to "sugar",
        "ଡାଇବେଟିସ" to "Diabetes",
        "ଡାଇବେଟିସ୍" to "Diabetes",
        "ହାଇପରଟେନସନ" to "Hypertension",
        "ଥାଇରଏଡ" to "Thyroid",
        "ଆଜମା" to "Asthma",
        "ନିମୋନିଆ" to "Pneumonia",
        "ମ୍ୟାଲେରିଆ" to "Malaria",
        "ଟାଇଫଏଡ" to "Typhoid",
        "ଡେଙ୍ଗୁ" to "Dengue",
        "ଜଣ୍ଡିସ" to "Jaundice",
        "ଫିଭର" to "fever",
        "ପେନ" to "pain",
        "ଏକ୍ସରେ" to "X-ray",
        "ସିଟି ସ୍କାନ" to "CT scan",
        "ଏମଆରଆଇ" to "MRI",
        "ଅଲଟ୍ରାସାଉଣ୍ଡ" to "ultrasound",
        "ଇସିଜି" to "ECG",
        "ମେଡିସିନ" to "medicine",
        "ଟାବଲେଟ" to "tablet",
        "ଟାବଲେଟ୍" to "tablet",
        "କ୍ୟାପସୁଲ" to "capsule",
        "ସିରପ" to "syrup",
        "ଇଞ୍ଜେକ୍ସନ" to "injection",
        "ଡ୍ରପ" to "drop",
        "ଆଣ୍ଟିବାୟୋଟିକ" to "antibiotic",
        "ପାରାସିଟାମୋଲ" to "Paracetamol",
        "ମେଟଫର୍ମିନ" to "Metformin",
        "ପ୍ୟାଣ୍ଟୋପ୍ରାଜୋଲ" to "Pantoprazole",
        "ଆମୋକ୍ସିସିଲିନ" to "Amoxicillin",
        "ଆଜିଥ୍ରୋମାଇସିନ" to "Azithromycin",
        "ସେଫଟ୍ରାଇଆକ୍ସୋନ" to "Ceftriaxone",
        "ପିପଟାଜ" to "Piptaz",
        "ମୋନୋସେଫ" to "Monocef",
        "ଡୋଲୋ" to "Dolo",
        "ସର୍ଜରୀ" to "surgery",
        "ଅପରେସନ" to "operation",
        "ଆଡମିଟ" to "admit",
        "ଡିସଚାର୍ଜ" to "discharge",
        "ଡିସ୍ଚାର୍ଜ" to "discharge",
        "ରେଫର" to "refer",
        "ସିକଲ ସେଲ" to "Sickle cell",
        "ସ୍କ୍ରବ ଟାଇଫସ" to "Scrub typhus",
        "ସାପ କାମୁଡ଼ା" to "snakebite",
        "ଅର୍ଗାନୋଫସଫେଟ" to "Organophosphate",
        "ପଏଜନିଂ" to "poisoning",
        "ସେଲୁଲାଇଟିସ" to "Cellulitis",
        "ଆସାଇଟିସ" to "Ascites",
        "ପ୍ଲୁରାଲ ଇଫ୍ୟୁଜନ" to "Pleural effusion",
        "ଆଇଭି ଫ୍ଲୁଇଡ" to "IV fluid",
        "ଅକ୍ସିଜେନ" to "oxygen",
        "ନେବୁଲାଇଜେସନ" to "nebulisation",
        "କ୍ୟାଥେଟର" to "catheter",
        "ଏନଜି ଟ୍ୟୁବ" to "NG tube",
        "ବିଏସକେୱାଇ" to "BSKY",
        "ନିରାମୟ" to "Niramaya",
        "ଏମଏଲସି" to "MLC",
        "ଲାମା" to "LAMA"
    )

    private fun englishSkeleton(word: String): String {
        val norm = word.lowercase().replace("ph", "f")
        val sb = StringBuilder()
        var prev = '\u0000'
        for (ch in norm) {
            val mapped = when (ch) {
                'a', 'e', 'i', 'o', 'u', 'y', 'h', 'w' -> continue
                'c', 'k', 'q' -> 'k'
                'p', 'f' -> 'p'
                'b', 'v' -> 'b'
                't', 'd' -> 't'
                's', 'z' -> 's'
                'g', 'j' -> 'j'
                else -> ch
            }
            if (mapped != prev) {
                sb.append(mapped)
                prev = mapped
            }
        }
        return sb.toString()
    }

    private fun odiaSkeleton(word: String): String {
        val sb = StringBuilder()
        var prev = '\u0000'
        for (ch in word) {
            val mapped = when (ch) {
                'କ', 'ଖ' -> 'k'
                'ଗ', 'ଘ', 'ଜ', 'ଝ' -> 'j'
                'ଚ', 'ଛ' -> 'k'
                'ଟ', 'ଠ', 'ଡ', 'ଢ', '\u0B5C', '\u0B5D', 'ତ', 'ଥ', 'ଦ', 'ଧ' -> 't'
                'ଣ', 'ନ' -> 'n'
                'ପ', 'ଫ' -> 'p'
                'ବ', 'ଭ' -> 'b'
                'ମ' -> 'm'
                'ର' -> 'r'
                'ଲ', 'ଳ' -> 'l'
                'ଶ', 'ଷ', 'ସ' -> 's'
                else -> continue
            }
            if (mapped != prev) {
                sb.append(mapped)
                prev = mapped
            }
        }
        return sb.toString()
    }

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
            val coreSkeletons = HashMap<String, String>()
            val extraSkelCounts = HashMap<String, Int>()
            val extraSkelTerm = HashMap<String, String>()
            var lineNo = 0
            try {
                ctx.assets.open("medical_dictionary.txt").bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        lineNo++
                        val term = raw.trim()
                        val lower = term.lowercase()
                        if (lower.length >= 2 && lower !in PROTECTED_WORDS && lower.all { it.code < 128 }) {
                            if (' ' in lower) {
                                multiWordRules.add(Regex("\\b${Regex.escape(lower)}\\b", RegexOption.IGNORE_CASE) to Regex.escapeReplacement(term))
                            } else {
                                singleWordMap.putIfAbsent(lower, term)
                                if (lower.length >= 8) {
                                    fuzzyBuckets.getOrPut(lower[0]) { ArrayList() }.add(lower to term)
                                }
                                if (lower.length >= 7) {
                                    val sk = englishSkeleton(lower)
                                    if (sk.length >= 5) {
                                        if (lineNo <= 1500) {
                                            coreSkeletons.putIfAbsent(sk, term)
                                        } else {
                                            extraSkelCounts[sk] = (extraSkelCounts[sk] ?: 0) + 1
                                            extraSkelTerm[sk] = term
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                for ((sk, cnt) in extraSkelCounts) {
                    if (cnt == 1) {
                        skeletonMap[sk] = extraSkelTerm[sk]!!
                    }
                }
                skeletonMap.putAll(coreSkeletons)
            } catch (_: Exception) {
            }
            loaded = true
        }
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
        if (lowerWord.length < 8 || lowerWord in PROTECTED_WORDS) return null
        val firstChar = lowerWord[0]
        var bestTerm: String? = null
        var bestDist = 2
        var tie = false

        val bucket = fuzzyBuckets[firstChar] ?: return null
        for ((candLower, candCanonical) in bucket) {
            if (abs(candLower.length - lowerWord.length) > 1) continue
            val d = levenshtein(lowerWord, candLower, 1)
            if (d < bestDist) {
                bestDist = d
                bestTerm = candCanonical
                tie = false
            } else if (d == bestDist && d <= 1 && bestTerm != candCanonical) {
                tie = true
            }
        }
        return if (!tie && bestDist <= 1) bestTerm else null
    }

    private fun mapOdiaWordWithSuffix(word: String): String {
        ODIA_TO_ENGLISH_BRIDGE[word]?.let { return it }

        var stem = word
        var suffix = ""
        for (suf in ODIA_SUFFIXES) {
            if (word.endsWith(suf) && word.length > suf.length + 1) {
                stem = word.substring(0, word.length - suf.length)
                suffix = suf
                break
            }
        }

        ODIA_TO_ENGLISH_BRIDGE[stem]?.let { return it + suffix }

        if (stem.length >= 7 && stem.any { it in '\u0B00'..'\u0B7F' }) {
            val sk = odiaSkeleton(stem)
            val hit = if (sk.length >= 5) skeletonMap[sk] else null
            if (hit != null) return hit + suffix
        }
        return word
    }

    fun normalize(ctx: Context, raw: String, engine: AsrEngineType): String {
        init(ctx)
        var text = raw.trim()
        if (text.isEmpty()) return ""

        if (engine == AsrEngineType.INDIC_CONFORMER_OR) {
            for ((odia, eng) in ODIA_TO_ENGLISH_BRIDGE) {
                if (' ' in odia && odia in text) {
                    text = text.replace(odia, eng)
                }
            }
            val words = text.split(Regex("\\s+"))
            val out = ArrayList<String>(words.size)
            for (w in words) {
                out.add(mapOdiaWordWithSuffix(w))
            }
            return out.joinToString(" ").replace(Regex("\\s{2,}"), " ").trim()
        }

        for ((regex, replacement) in multiWordRules) {
            if (regex.containsMatchIn(text)) {
                text = regex.replace(text, replacement)
            }
        }

        val tokens = text.split(Regex("\\s+"))
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val w1 = tokens[i]
            val clean1 = w1.trimEnd('.', ',', ';', ':', '?', '!')
            val punct1 = w1.substring(clean1.length)
            val low1 = clean1.lowercase()

            if (i + 1 < tokens.size && clean1.all { it in 'a'..'z' || it in 'A'..'Z' } && low1 !in PROTECTED_WORDS) {
                val w2 = tokens[i + 1]
                val clean2 = w2.trimEnd('.', ',', ';', ':', '?', '!')
                val punct2 = w2.substring(clean2.length)
                val low2 = clean2.lowercase()
                if (clean2.all { it in 'a'..'z' || it in 'A'..'Z' } && low2 !in PROTECTED_WORDS) {
                    val joined = low1 + low2
                    val hit = singleWordMap[joined]
                        ?: (if (joined.length >= 8) fuzzyLookup(joined) else null)
                        ?: (if (joined.length >= 9) skeletonMap[englishSkeleton(joined)] else null)
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

        return out.joinToString(" ").replace(Regex("\\s{2,}"), " ").trim()
    }
}

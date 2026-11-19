package com.voicerambler.free

object MedicalNormalizer {

    private val PHONETIC_CORRECTIONS = linkedMapOf(
        // Labs & Vitals
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
        // Common Drugs & Misrecognitions
        "pep taz" to "Piptaz",
        "pip taz" to "Piptaz",
        "piperacillin tazobactam" to "piperacillin-tazobactam",
        "sef triaxone" to "ceftriaxone",
        "cef triaxone" to "ceftriaxone",
        "azithro mycin" to "azithromycin",
        "amoxycillin" to "amoxicillin",
        "panto prazole" to "pantoprazole",
        "para cetamol" to "paracetamol",
        "furo semide" to "furosemide",
        "met formin" to "metformin",
        "amlo dipine" to "amlodipine",
        "telmi sartan" to "telmisartan",
        "atorva statin" to "atorvastatin",
        "clopido grel" to "clopidogrel",
        "ondan setron" to "ondansetron",
        "dexametha sone" to "dexamethasone",
        "hydro cortisone" to "hydrocortisone",
        "levo cetirizine" to "levocetirizine",
        "salbu tamol" to "salbutamol",
        "enoxa parin" to "enoxaparin",
        // Dosing abbreviations
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

    private val NUMBER_WORDS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "ten" to "10", "twenty" to "20", "thirty" to "30", "forty" to "40",
        "fifty" to "50", "hundred" to "100", "five hundred" to "500"
    )

    /**
     * Runs 100% offline in <2ms on output from ANY ASR engine (Shruti, IndicConformer, or Gemini).
     * Never alters existing digits, decimals, or negations (no, not, nil, absent, nahi).
     */
    fun normalize(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        // Convert spoken number words only when followed by a clinical unit
        for ((word, digit) in NUMBER_WORDS) {
            val regex = Regex("\\b$word\\s+(mg|mcg|mL|ml|gm|g|IU|%)\\b", RegexOption.IGNORE_CASE)
            text = regex.replace(text) { m -> "$digit ${m.groupValues[1]}" }
        }

        for ((bad, good) in PHONETIC_CORRECTIONS) {
            val regex = Regex("\\b${Regex.escape(bad)}\\b", RegexOption.IGNORE_CASE)
            text = regex.replace(text, good)
        }

        // Capitalize first character if Latin lowercase
        if (text.isNotEmpty() && text[0] in 'a'..'z') {
            text = text[0].uppercaseChar() + text.substring(1)
        }

        return text.replace(Regex("\\s{2,}"), " ").trim()
    }
}

package com.medukti.keyboard

import android.content.Context
import kotlin.math.abs

object MedicalNormalizer {

    private val singleWordMap = HashMap<String, String>()
    private val multiWordRules = ArrayList<Pair<Regex, String>>()
    private val multiWordTerms = LinkedHashSet<String>()
    private val fuzzyBuckets = HashMap<Char, ArrayList<Pair<String, String>>>()
    private val skeletonMap = HashMap<String, String>()
    private val coreSkeletonMap = HashMap<String, String>()
    private val drugTermsLower = HashSet<String>()

    @Volatile
    private var loaded = false

    private val PROTECTED_WORDS = setOf(
        "no", "not", "nil", "none", "never", "without", "negative", "positive",
        "normal", "absent", "present", "mild", "moderate", "severe",
        "mg", "mcg", "ml", "gm", "kg", "iu", "od", "bd", "tds", "qid", "hs", "sos", "stat",
        "iv", "im", "sc", "po", "pr", "sl",
        "excellent", "present", "presents", "presented", "precede", "preceded", "patient", "patients",
        "disease", "increase", "decrease", "release", "please", "purpose", "diagnose", "prognosis",
        "message", "passage", "average", "package", "bandage", "dosage", "damage", "storage",
        "function", "treatment", "infection", "recurrent", "structural", "infectious", "clinical",
        "adequate", "aerosol", "alkaline", "urea", "iron", "calcium", "zinc", "glucose", "oxygen",
        "routine", "baseline", "medicine", "hormone", "tissue", "engine", "combine", "examine",
        "define", "outline", "decline", "vaccine", "feeds", "patrick", "seed", "seeth",
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
        "uti" to "UTI",
        "vur" to "VUR",
        "rbs" to "RBS",
        "grbs" to "GRBS",
        "ckd" to "CKD",
        "aki" to "AKI",
        "copd" to "COPD",
        "dka" to "DKA",
        "stemi" to "STEMI",
        "nstemi" to "NSTEMI",
        "nicu" to "NICU",
        "picu" to "PICU",
        "opd" to "OPD",
        "icu" to "ICU",
        "ecg" to "ECG",
        "mri" to "MRI",
        "cbc" to "CBC",
        "lft" to "LFT",
        "rft" to "RFT",
        "kft" to "KFT",
        "abg" to "ABG",
        "lscs" to "LSCS",
        "bsky" to "BSKY",
        "mlc" to "MLC",
        "lama" to "LAMA",
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
        "septriaxone" to "Ceftriaxone",
        "tajovactam" to "Tazobactam",
        "cetaglifatin" to "Sitagliptin",
        "augmentine" to "Augmentin",
        "niphedipine" to "Nifedipine",
        "nemodipine" to "Nimodipine",
        "selectoxib" to "Celecoxib",
        "etericoxib" to "Etoricoxib",
        "pentoprazole" to "Pantoprazole",
        "pentoprazol" to "Pantoprazole",
        "raveprazol" to "Rabeprazole",
        "raveprazole" to "Rabeprazole",
        "rectus seeth" to "rectus sheath",
        "erectus seed" to "rectus sheath",
        "titanos" to "Tetanus",
        "scroftipus" to "Scrub typhus",
        "dengu" to "Dengue",
        "lumbar lorosis" to "lumbar lordosis",
        "lorosis" to "lordosis",
        "thoracic gaipo sis" to "thoracic kyphosis",
        "gaipo sis" to "kyphosis",
        "phronchogenic" to "Bronchogenic",
        "victa lactamage" to "beta-lactamase",
        "lactamage" to "lactamase",
        "procloreperagin" to "Prochlorperazine",
        "feeds patrick" to "Fitzpatrick",
        "phronchogenic carcinoma" to "Bronchogenic carcinoma",
        "scrub tipus" to "Scrub typhus",
        "bupibhaken" to "Bupivacaine",
        "oseltamibhir" to "Oseltamivir",
        "carbimajol" to "Carbimazole",
        "lamotrizin" to "Lamotrigine",
        "diltia gem" to "Diltiazem",
        "ondan cetron" to "Ondansetron",
        "granny cetron" to "Granisetron",
        "raja tripton" to "Rizatriptan",
        "livocetti regin" to "Levocetirizine",
        "pandy" to "Pan-D",
        "pan dee" to "Pan-D",
        "tell ma" to "Telma",
        "mono safe" to "Monocef",
        "do low" to "Dolo",
        "glyco met" to "Glycomet",
        "for ziga" to "Forxiga",
        "jar diance" to "Jardiance",
        "bhin blastin" to "Vinblastine",
        "prajocin" to "Prazosin",
        "omeprajol" to "Omeprazole",
        "jolendronate" to "Zoledronate",
        "seti regin" to "Cetirizine",
        "jafir lucas" to "Zafirlukast",
        "vanilla paksin" to "Venlafaxine",
        "lamy-woodin" to "Lamivudine",
        "lamy woodin" to "Lamivudine",
        "carpil jomib" to "Carfilzomib",
        "obinitu jumab" to "Obinutuzumab",
        "naltrex zone" to "Naltrexone",
        "faevukso-start" to "Febuxostat",
        "faevukso start" to "Febuxostat",
        "sacubitril ablastin" to "Sacubitril/valsartan",
        "insuring glargine" to "Insulin glargine",
        "theochallic oxide" to "Thiocolchicoside",
        "lama vadini" to "Lamivudine",
        "alpha terrison b" to "Amphotericin B",
        "warwickonazole" to "Voriconazole",
        "n-terminal probe b-type" to "N-terminal pro-B-type",
        "by basilar" to "bibasilar",
        "adepnam" to "Ertapenem",
        "nortriptifin" to "Nortriptyline",
        "nortryptoline" to "Nortriptyline",
        "augmenting injection" to "Augmentin injection",
        "augmenting 625" to "Augmentin 625",
        "tab augmenting" to "Tab. Augmentin",
        "tablet augmenting" to "Tab. Augmentin",
        "inj augmenting" to "Inj. Augmentin",
        "injection augmenting" to "Inj. Augmentin",
        "ace inhibitors" to "ACE inhibitors",
        "ace inhibitor" to "ACE inhibitor",
        "parasetamol" to "Paracetamol",
        "para setamol" to "Paracetamol",
        "para sitamol" to "Paracetamol",
        "paracitamol" to "Paracetamol",
        "para cetamol" to "Paracetamol",
        "dapaglyplogen" to "Dapagliflozin",
        "dappa glyphlogine" to "Dapagliflozin",
        "dapa griplogen" to "Dapagliflozin",
        "herrick carboxymaltouch" to "Ferric carboxymaltose",
        "carboxymaltouch" to "Ferric carboxymaltose",
        "hitori cocksib" to "Etoricoxib",
        "hitori coxib" to "Etoricoxib",
        "etori cocksib" to "Etoricoxib",
        "sella coccib" to "Celecoxib",
        "sella cocksib" to "Celecoxib",
        "sela coccib" to "Celecoxib",
        "glyprogene" to "Dapagliflozin",
        "dapaglyphrogen" to "Dapagliflozin",
        "tapagliprogene" to "Dapagliflozin",
        "pagluprogen" to "Dapagliflozin",
        "semaglott" to "Semaglutide",
        "proclor peragin" to "Prochlorperazine",
        "chlorperagine" to "Chlorpromazine",
        "clover sol" to "Clobetasol",
        "rectal sheets" to "rectus sheath",
        "rectus seath" to "rectus sheath",
        "rectus sheet" to "rectus sheath",
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
        val norm = word.lowercase()
            .replace("ph", "f")
            .replace("x", "ks")
            .replace(Regex("c(?=[eiy])"), "s")
        val sb = StringBuilder()
        var prev = '\u0000'
        for (ch in norm) {
            val mapped = when (ch) {
                'a', 'e', 'i', 'o', 'u', 'y', 'h' -> continue
                'c', 'k', 'q' -> 'k'
                'p', 'f' -> 'p'
                'b', 'v', 'w' -> 'b'
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
                    multiWordTerms.add(v)
                } else {
                    singleWordMap[k] = v
                }
            }
            val coreSkeletons = HashMap<String, String>()
            val extraSkelCounts = HashMap<String, Int>()
            val extraSkelTerm = HashMap<String, String>()
            // Layer 1: 100% of core_drugs.txt into coreSkeletons (no line cap)
            try {
                ctx.assets.open("core_drugs.txt").bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        val term = raw.trim()
                        val lower = term.lowercase()
                        if (lower.length >= 3 && lower !in PROTECTED_WORDS && lower.all { it.code < 128 }) {
                            drugTermsLower.add(lower)
                            if (' ' in lower) {
                                multiWordRules.add(Regex("\\b${Regex.escape(lower)}\\b", RegexOption.IGNORE_CASE) to Regex.escapeReplacement(term))
                                multiWordTerms.add(term)
                            } else {
                                singleWordMap.putIfAbsent(lower, term)
                                if (lower.length >= 7) {
                                    fuzzyBuckets.getOrPut(lower[0]) { ArrayList() }.add(lower to term)
                                    val sk = englishSkeleton(lower)
                                    if (sk.length >= 4) {
                                        coreSkeletons.putIfAbsent(sk, term)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            // Layer 2: medical_dictionary.txt (clinical terms + 98k open list)
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
                                multiWordTerms.add(term)
                            } else {
                                if (lineNo <= 3000 || !(term.length >= 6 && term.all { it.isUpperCase() })) {
                                    singleWordMap.putIfAbsent(lower, term)
                                }
                                if (lower.length >= 8) {
                                    fuzzyBuckets.getOrPut(lower[0]) { ArrayList() }.add(lower to term)
                                }
                                if (lower.length >= 7) {
                                    val sk = englishSkeleton(lower)
                                    if (sk.length >= 4) {
                                        if (lineNo <= 3000) {
                                            coreSkeletons.putIfAbsent(sk, term)
                                        } else if (sk.length >= 5) {
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
                coreSkeletonMap.putAll(coreSkeletons)
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
            val hit = if (sk.length >= 5) coreSkeletonMap[sk] else null
            if (hit != null) return hit + suffix
        }
        return word
    }

    fun normalize(ctx: Context, raw: String, engine: AsrEngineType): String {
        init(ctx)
        var text = raw.trim()
        if (text.isEmpty()) return ""

        if (Prefs.isOdiaEngine(engine)) {
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
            return cleanBrokenOdiaMatras(out.joinToString(" "))
        }

        // Split glued "tablet..." prefixes (e.g. "Tabletapagliprogene" -> "Tab. Dapagliflozin")
        text = text.replace(Regex("(?i)\\btablet([a-z]{6,})\\b")) { m ->
            "Tab. " + m.groupValues[1]
        }

        for ((regex, replacement) in multiWordRules) {
            if (regex.containsMatchIn(text)) {
                text = regex.replace(text, replacement)
            }
        }

        val tokens = text.split(Regex("\\s+"))
        val out = ArrayList<String>(tokens.size)
        var ti = 0
        var prevCleanLow = ""
        while (ti < tokens.size) {
            val w1 = tokens[ti]
            val clean1 = w1.trimEnd('.', ',', ';', ':', '?', '!')
            val punct1 = w1.substring(clean1.length)
            val low1 = clean1.lowercase()
            val dehyphen1 = if ('-' in low1) low1.replace("-", "") else ""
            val inRxContext = prevCleanLow in RX_CONTEXT_TRIGGERS

            // 3-word split-drug joiner (exact skeleton match for >= 10 char splits like "empa gli flozin")
            if (ti + 2 < tokens.size && punct1.isEmpty() && clean1.length >= 2 && clean1.all { it.isLetter() } && low1 !in PROTECTED_WORDS) {
                val w2 = tokens[ti + 1]
                val clean2 = w2.trimEnd('.', ',', ';', ':', '?', '!')
                val punct2 = w2.substring(clean2.length)
                val low2 = clean2.lowercase()
                val w3 = tokens[ti + 2]
                val clean3 = w3.trimEnd('.', ',', ';', ':', '?', '!')
                val punct3 = w3.substring(clean3.length)
                val low3 = clean3.lowercase()
                if (punct2.isEmpty() && clean2.length >= 2 && clean2.all { it.isLetter() } && low2 !in PROTECTED_WORDS &&
                    clean3.length >= 2 && clean3.all { it.isLetter() } && low3 !in PROTECTED_WORDS) {
                    val joined3 = low1 + low2 + low3
                    if (joined3.length >= 10) {
                        val sk3 = englishSkeleton(joined3)
                        val hit3 = singleWordMap[joined3] ?: (if (sk3.length >= 5) coreSkeletonMap[sk3] else null)
                        if (hit3 != null) {
                            out.add(preserveClinicalCase(hit3, clean1) + punct3)
                            prevCleanLow = hit3.lowercase()
                            ti += 3
                            continue
                        }
                    }
                }
            }

            // 2-word skeleton sliding window (exact + distance-1 fuzzy for >= 9 char splits like "by basilar")
            if (ti + 1 < tokens.size && punct1.isEmpty() && clean1.length >= 2 && clean1.all { it.isLetter() } && low1 !in PROTECTED_WORDS) {
                val w2 = tokens[ti + 1]
                val clean2 = w2.trimEnd('.', ',', ';', ':', '?', '!')
                val punct2 = w2.substring(clean2.length)
                val low2 = clean2.lowercase()
                if (clean2.length >= 3 && clean2.all { it.isLetter() } && low2 !in PROTECTED_WORDS) {
                    val joined = low1 + low2
                    if (joined.length >= 8) {
                        val sk2 = englishSkeleton(joined)
                        val hit2 = singleWordMap[joined]
                            ?: (if (sk2.length >= 5) coreSkeletonMap[sk2] else null)
                            ?: (if (joined.length >= 9 && sk2.length >= 6) fuzzyCoreSkeleton(sk2, 1, joined) else null)
                        if (hit2 != null) {
                            out.add(preserveClinicalCase(hit2, clean1) + punct2)
                            prevCleanLow = hit2.lowercase()
                            ti += 2
                            continue
                        }
                    }
                }
            }

            if (clean1.any { it.isDigit() } || low1 in PROTECTED_WORDS) {
                out.add(w1)
                prevCleanLow = low1
            } else {
                val exact = singleWordMap[low1] ?: (if (dehyphen1.length >= 8) singleWordMap[dehyphen1] else null)
                if (exact != null) {
                    out.add(preserveClinicalCase(exact, clean1) + punct1)
                    prevCleanLow = exact.lowercase()
                } else {
                    val rescued = rescueDrugToken(low1, inRxContext)
                    val finalTok = if (rescued != null) preserveClinicalCase(rescued, clean1) else clean1
                    out.add(finalTok + punct1)
                    prevCleanLow = finalTok.lowercase()
                }
            }
            ti++
        }

        var res = out.joinToString(" ").replace(Regex("[ \\t]{2,}"), " ").trim()
        res = formatClinicalShorthand(res)
        return res
    }

    private val SECTION_HEADINGS = linkedMapOf(
        "chief complaints" to "Chief Complaints:",
        "history of present illness" to "History of Present Illness:",
        "past history" to "Past History:",
        "on examination" to "On Examination:",
        "systemic examination" to "Systemic Examination:",
        "provisional diagnosis" to "Provisional Diagnosis:",
        "final diagnosis" to "Final Diagnosis:",
        "investigations" to "Investigations:",
        "treatment given" to "Treatment Given:",
        "discharge advice" to "Discharge Advice:",
        "follow up advice" to "Follow-up Advice:"
    )

    private fun formatClinicalShorthand(input: String): String {
        var t = input
        // BP 130 by 80 / 130 over 80 -> BP 130/80
        t = t.replace(Regex("(?i)\\b(bp\\s+)?(\\d{2,3})\\s+(?:by|over)\\s+(\\d{2,3})\\b")) { m ->
            val bpPrefix = if (m.groupValues[1].isNotEmpty()) "BP " else ""
            "$bpPrefix${m.groupValues[2]}/${m.groupValues[3]}"
        }
        // Decimal numbers: "10 point 2" -> "10.2"
        t = t.replace(Regex("(?i)(\\d+)\\s+point\\s+(\\d+)"), "$1.$2")
        // SpO2 98 percent -> SpO2 98%
        t = t.replace(Regex("(?i)\\bspo2\\s+(\\d{2,3})\\s+percent\\b"), "SpO2 $1%")
        t = t.replace(Regex("(?i)(\\d{1,3}(?:\\.\\d+)?)\\s+percent\\b"), "$1%")
        // Fix common Rx mishearings
        t = t.replace(Regex("(?i)\\b(?:one|1)\\s+(?:tub|tap)\\b"), "1 tab")
        t = t.replace(Regex("(?i)\\bdoublet\\s+(?=[A-Za-z])"), "Tab. ")
        t = t.replace(Regex("(?i)\\bodi\\b"), "OD")
        t = t.replace(Regex("(?i)\\btub\\s+(od|bd|tds|qid)\\b")) { m -> "tab " + m.groupValues[1].uppercase() }
        t = t.replace(Regex("(?i)\\bb\\s+d\\b"), "BD")
        t = t.replace(Regex("(?i)\\bo\\s+d\\b"), "OD")
        t = t.replace(Regex("(?i)\\bt\\s+d\\s+s\\b"), "TDS")
        t = t.replace(Regex("(?i)\\b(?:vanampyl|one ample|1 ample)\\b"), "1 ampoule")
        // Rx prefixes before drug/word
        t = t.replace(Regex("(?i)\\b(?:tab|tablet)\\s+(?=[A-Za-z])"), "Tab. ")
        t = t.replace(Regex("(?i)\\b(?:inj|injection)\\s+(?=[A-Za-z])"), "Inj. ")
        t = t.replace(Regex("(?i)\\b(?:cap|capsule)\\s+(?=[A-Za-z])"), "Cap. ")
        t = t.replace(Regex("(?i)\\b(?:syp|syrup)\\s+(?=[A-Za-z])"), "Syp. ")
        // Uppercase dosing/route tokens when following dose/drug
        t = t.replace(Regex("(?i)\\b(od|bd|tds|qid|hs|sos|iv|im|sc)\\b(?=[.,;:\\s]|$)")) { m ->
            m.value.uppercase()
        }
        // Discharge summary & case history section headers
        for ((phrase, heading) in SECTION_HEADINGS) {
            t = t.replace(Regex("(?i)\\s*\\b${Regex.escape(phrase)}\\b[:.,]*\\s*"), "\n$heading ")
        }
        return t.trim()
    }

    private val RX_CONTEXT_TRIGGERS = setOf(
        "tab", "tablet", "cap", "capsule", "inj", "injection", "syp", "syrup",
        "drop", "drops", "oint", "ointment", "cream", "gel", "spray", "inhaler",
        "started", "start", "given", "give", "prescribed", "prescribe",
        "advise", "advised", "continue", "continued", "stop", "stopped",
        "add", "added", "dose", "iv", "im", "sc", "po"
    )

    private val DRUG_SUFFIXES = listOf(
        "xone", "bactam", "gliptin", "gliflozin", "dipine", "coxib",
        "prazole", "prazol", "prazan", "glutide", "sartan", "statin", "mycin", "cillin",
        "zolam", "pril", "olol", "fibrate", "glitazone", "glinide",
        "azine", "agin", "majol", "trizin", "triptan", "setron",
        "cetron", "cycline", "conazole", "floxacin", "caine",
        "sone", "solone", "lukast", "tidine", "dopa", "ase",
        "mab", "nib", "vir", "navir", "previr", "buvir", "trelvir",
        "parib", "ciclib", "zomib", "tinib", "zolamide", "prost", "relin"
    )

    private val CLINICAL_SUFFIXES = listOf(
        "osis", "itis", "oma", "genic", "pathy", "scopy", "ectomy",
        "otomy", "ostomy", "plasty", "megaly", "sclerosis", "stenosis",
        "algia", "emia", "aemia", "uria", "plasia", "blastoma",
        "sarcoma", "carcinoma", "pnoea", "pnea", "rrhoea", "rrhea"
    )

    private fun preserveClinicalCase(canonical: String, original: String): String {
        val lowCan = canonical.lowercase()
        // Keep acronyms (ECG, DKA, SpO2) and drugs/brands capitalized
        if (canonical.count { it.isUpperCase() } >= 2 || lowCan in drugTermsLower) {
            return canonical
        }
        // For general clinical nouns (dyspnoea, oedema, ultrasonography), preserve lowercase if spoken lowercase
        if (original.isNotEmpty() && original[0].isLowerCase()) {
            return lowCan
        }
        return canonical
    }

    private fun hasDrugSuffix(low: String): Boolean =
        DRUG_SUFFIXES.any { low.endsWith(it) }

    private fun rescueDrugToken(low: String, rxBoost: Boolean = false): String? {
        val minLen = if (rxBoost) 5 else 7
        if (low.length < minLen || low in PROTECTED_WORDS) return null
        val sk = englishSkeleton(low)
        if (sk.length < 4) return null
        coreSkeletonMap[sk]?.let { return it }
        // In Rx context, also try Indian z/j swap on exact skeleton
        if (rxBoost && ('j' in low || 'z' in low)) {
            val swapped = low.replace('j', 'z')
            coreSkeletonMap[englishSkeleton(swapped)]?.let { return it }
        }
        if (sk.length >= 5) {
            val maxDist = if (rxBoost && sk.length >= 7 && hasDrugSuffix(low)) 2 else 1
            return fuzzyCoreSkeleton(sk, maxDist, low)
        }
        return null
    }

    private fun fuzzyCoreSkeleton(skel: String, maxDist: Int, rawLower: String): String? {
        if (skel.length < 5) return null
        var best: String? = null
        var bestD = maxDist + 1
        var tie = false
        for ((k, v) in coreSkeletonMap) {
            if (abs(k.length - skel.length) > maxDist) continue
            if (abs(v.length - rawLower.length) > 3) continue
            val d = levenshtein(skel, k, maxDist)
            if (d < bestD) {
                bestD = d
                best = v
                tie = false
            } else if (d == bestD && d <= maxDist && best != v) {
                tie = true
            }
        }
        if (tie || bestD > maxDist || best == null) return null
        // Sanity check: share first consonant or >= 50% character overlap so everyday English isn't hijacked
        val bestLow = best.lowercase()
        val sameFirst = skel[0] == englishSkeleton(bestLow).firstOrNull()
        val common = rawLower.toSet().intersect(bestLow.toSet()).size
        return if (sameFirst || common * 2 >= bestLow.toSet().size) best else null
    }

    fun searchDictionary(ctx: Context, query: String): List<String> {
        init(ctx)
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val hits = LinkedHashSet<String>()
        singleWordMap[q]?.let { hits.add(it) }
        for (phrase in multiWordTerms) {
            if (phrase.lowercase() == q) {
                hits.add(phrase)
            }
        }
        for ((k, v) in singleWordMap) {
            if (k.startsWith(q)) {
                hits.add(v)
                if (hits.size >= 8) break
            }
        }
        if (hits.size < 8) {
            for (phrase in multiWordTerms) {
                if (phrase.lowercase().contains(q)) {
                    hits.add(phrase)
                    if (hits.size >= 8) break
                }
            }
        }
        return hits.take(8)
    }

    fun findCandidateHints(ctx: Context, rawText: String): List<String> {
        init(ctx)
        if (rawText.isBlank()) return emptyList()
        val tokens = rawText.split(Regex("\\s+")).map {
            it.trim().trimEnd('.', ',', ';', ':', '?', '!', ')').trimStart('(').lowercase()
        }.filter { it.length >= 4 }

        val hints = LinkedHashSet<String>()
        fun addSkeletonMatches(word: String) {
            if (word in PROTECTED_WORDS) return
            singleWordMap[word]?.let { hints.add(it); return }
            if (word.length < 6) return
            val sk = englishSkeleton(word)
            if (sk.length < 4) return
            coreSkeletonMap[sk]?.let { hints.add(it) }
            if (sk.length >= 5) {
                for ((k, v) in coreSkeletonMap) {
                    if (abs(k.length - sk.length) <= 1 && levenshtein(sk, k, 1) <= 1) {
                        hints.add(v)
                        if (hints.size >= 15) return
                    }
                }
            }
        }

        for (i in tokens.indices) {
            addSkeletonMatches(tokens[i])
            if (i + 1 < tokens.size) {
                val joined = tokens[i] + tokens[i + 1]
                if (joined.length >= 8) addSkeletonMatches(joined)
            }
            if (hints.size >= 15) break
        }
        return hints.take(15)
    }

    fun isKnownMedicalWord(lowerWord: String): Boolean =
        lowerWord !in PROTECTED_WORDS && (singleWordMap.containsKey(lowerWord) || fuzzyLookup(lowerWord) != null)

    fun mergeParakeetAndMedAsr(ctx: Context, parakeetRaw: String, medAsrRaw: String): String {
        init(ctx)
        val pTrim = parakeetRaw.trim()
        val mTrim = medAsrRaw.trim()
        if (pTrim.isEmpty()) return mTrim
        if (mTrim.isEmpty()) return pTrim

        val medTokens = mTrim.split(Regex("\\s+")).map { it.trimEnd('.', ',', ';', ':', '?', '!') }
        val medTerms = ArrayList<String>()
        var mi = 0
        while (mi < medTokens.size) {
            val w1 = medTokens[mi].lowercase()
            if (mi + 1 < medTokens.size) {
                val w2 = medTokens[mi + 1].lowercase()
                val joined = w1 + w2
                val hit2 = singleWordMap[joined] ?: (if (joined.length >= 8) fuzzyLookup(joined) else null)
                if (hit2 != null && w1 !in PROTECTED_WORDS && w2 !in PROTECTED_WORDS) {
                    medTerms.add(hit2)
                    mi += 2
                    continue
                }
            }
            val hit1 = if (w1.length >= 5 && w1 !in PROTECTED_WORDS) {
                singleWordMap[w1] ?: (if (w1.length >= 8) fuzzyLookup(w1) else null)
            } else null
            if (hit1 != null) medTerms.add(hit1)
            mi++
        }

        if (medTerms.isEmpty()) return pTrim

        val pWords = pTrim.split(Regex("\\s+")).toMutableList()
        val pLowerAll = pTrim.lowercase()

        for (term in medTerms) {
            if (term.lowercase() in pLowerAll) continue
            val targetSkel = englishSkeleton(term)
            if (targetSkel.length < 4) continue

            var bestIdx = -1
            var bestSpan = 1
            val maxAllowed = if (targetSkel.length >= 6) 2 else 1
            var bestDist = maxAllowed + 1

            for (i in pWords.indices) {
                for (span in 1..3) {
                    if (i + span > pWords.size) break
                    val slice = pWords.subList(i, i + span).map { it.trimEnd('.', ',', ';', ':', '?', '!') }
                    if (slice.any { it.lowercase() in PROTECTED_WORDS || it.any(Char::isDigit) }) continue
                    if (span == 1 && isKnownMedicalWord(slice[0].lowercase())) continue
                    val joined = slice.joinToString("")
                    if (joined.length < 4) continue
                    val sk = englishSkeleton(joined)
                    val d = levenshtein(sk, targetSkel, maxAllowed)
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = i
                        bestSpan = span
                    }
                }
            }

            if (bestIdx >= 0) {
                val lastRaw = pWords[bestIdx + bestSpan - 1]
                val lastClean = lastRaw.trimEnd('.', ',', ';', ':', '?', '!')
                val punct = lastRaw.substring(lastClean.length)
                pWords[bestIdx] = term + punct
                for (r in 1 until bestSpan) {
                    pWords.removeAt(bestIdx + 1)
                }
            }
        }

        return pWords.joinToString(" ")
    }

    private val BROKEN_ODIA_MATRA = Regex("(?<=[\\s^])[\\u0B3E-\\u0B4C\\u0B56\\u0B57]|([\\u0B3E-\\u0B4C\\u0B56\\u0B57])\\1+")

    fun cleanBrokenOdiaMatras(text: String): String {
        return text
            // Remove stray Devanagari leaks (e.g. हम्म, कथू)
            .replace(Regex("[\\u0900-\\u09FF\\u0A00-\\u0AFF\\u0B80-\\u0DFF\\u1C50-\\u1C7F]+"), " ")
            // Remove standalone candrabindu/anusvara tokens (e.g. ଁଁଁଁ)
            .replace(Regex("(?<=^|\\s)[\\u0B01-\\u0B03]+(?=\\s|$)"), " ")
            // Collapse repeated candrabindu/anusvara
            .replace(Regex("([\\u0B01-\\u0B03])\\1+"), "$1")
            // Strip leading orphaned matras at word start
            .replace(Regex("(?<=^|\\s)[\\u0B3E-\\u0B4C\\u0B56\\u0B57]+"), "")
            // Collapse any stacked dependent vowel signs (e.g. େି -> େ, ିୁ -> ି)
            .replace(Regex("([\\u0B3E-\\u0B4C\\u0B56\\u0B57])[\\u0B3E-\\u0B4C\\u0B56\\u0B57]+"), "$1")
            // Strip vowel sign immediately after independent vowel (e.g. ଓୋ -> ଓ)
            .replace(Regex("([\\u0B05-\\u0B14])[\\u0B3E-\\u0B4C\\u0B56\\u0B57]+"), "$1")
            // Collapse repeated filler loops (ହୁଁ ହୁଁ -> single or drop)
            .replace(Regex("(?<=^|\\s)(?:ହୁଁ|ଉଁ)(?:\\s+(?:ହୁଁ|ଉଁ))+(?=\\s|$)"), " ")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
    }

    fun mergeOdiaIndicAndSraVaani(indicRaw: String, sravaaniRaw: String): String {
        val iClean = cleanBrokenOdiaMatras(indicRaw)
        val sClean = cleanBrokenOdiaMatras(sravaaniRaw)
        if (iClean.isEmpty()) return sClean
        if (sClean.isEmpty()) return iClean

        val iWords = iClean.split(Regex("\\s+")).toMutableList()
        val sWords = sClean.split(Regex("\\s+"))

        // If SraVaani has English/Latin words (e.g. "slip", "back"), prefer them over Odia transliterations
        val sEnglish = sWords.filter { it.any { c -> c in 'a'..'z' || c in 'A'..'Z' } }
        for (eng in sEnglish) {
            val engSk = englishSkeleton(eng.trimEnd('.', ',', '?', '!'))
            if (engSk.length < 2) continue
            for (idx in iWords.indices) {
                val w = iWords[idx]
                if (w.any { it in '\u0B00'..'\u0B7F' }) {
                    val oSk = odiaSkeleton(w)
                    if (oSk == engSk || (engSk.length >= 3 && levenshtein(oSk, engSk, 1) <= 1)) {
                        iWords[idx] = eng
                        break
                    }
                }
            }
        }

        // Replace broken/single-char Odia fragments with aligned SraVaani word if length ratio matches
        val ratio = sWords.size.toDouble() / iWords.size.coerceAtLeast(1)
        for (idx in iWords.indices) {
            val w = iWords[idx]
            val hasBroken = BROKEN_ODIA_MATRA.containsMatchIn(w) || (w.length == 1 && w[0] in '\u0B00'..'\u0B7F' && w !in setOf("ଓ", "ଏ", "ଆ", "ଇ", "ଉ"))
            if (hasBroken) {
                val sIdx = (idx * ratio).toInt().coerceIn(0, sWords.lastIndex)
                val cand = sWords[sIdx]
                if (cand.length >= 2) {
                    iWords[idx] = cand
                }
            }
        }

        return iWords.joinToString(" ").replace(Regex("[ \\t]{2,}"), " ").trim()
    }
}

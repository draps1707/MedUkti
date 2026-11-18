package com.voicerambler.free

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiClient {

    val MEDICAL_BIAS_TERMS = listOf(
        "BP", "HR", "PR", "RR", "SpO2", "FiO2", "GCS", "AVPU", "CBS", "GRBS", "RBS", "FBS", "PPBS",
        "Temp", "afebrile", "febrile", "pallor", "icterus", "cyanosis", "clubbing", "lymphadenopathy",
        "edema", "pedal edema", "anasarca", "dehydration", "tachycardia", "bradycardia", "tachypnea",
        "hypotension", "hypertension", "hypoxia", "hypercapnia", "shock", "anaphylaxis", "sepsis",
        "septic shock", "MODS", "SIRS", "CPR", "ROSC", "intubation", "extubation", "tracheostomy",
        "Ryle's tube", "Foley's catheter", "central line", "CVP", "ABG", "VBG", "lactate", "triage",
        "CBC", "Hb", "TLC", "DLC", "PLT", "MCV", "MCH", "MCHC", "RDW", "ESR", "CRP", "PCT", "procalcitonin",
        "LFT", "SGOT", "SGPT", "AST", "ALT", "ALP", "GGT", "bilirubin", "albumin", "INR", "PT", "aPTT",
        "RFT", "KFT", "BUN", "creatinine", "eGFR", "urea", "uric acid", "electrolytes", "Na", "K", "Cl",
        "calcium", "magnesium", "phosphorus", "HbA1c", "TSH", "FT3", "FT4", "lipid profile", "LDL", "HDL",
        "triglycerides", "Troponin I", "Troponin T", "CK-MB", "BNP", "NT-proBNP", "D-dimer", "ferritin",
        "LDH", "amylase", "lipase", "urine R/M", "urine C/S", "blood C/S", "sputum AFB", "CBNAAT", "GeneXpert",
        "ECG", "2D Echo", "LVEF", "CXR", "HRCT", "CT brain", "NCCT", "CECT", "MRI", "MRCP", "ERCP", "USG",
        "ACS", "STEMI", "NSTEMI", "unstable angina", "CAD", "CHF", "HFrEF", "HFpEF", "cardiogenic shock",
        "AFib", "atrial fibrillation", "SVT", "VT", "VF", "heart block", "LBBB", "RBBB", "pericarditis",
        "pericardial effusion", "tamponade", "infective endocarditis", "DVT", "pulmonary embolism", "RHD",
        "COPD", "acute exacerbation", "bronchial asthma", "status asthmaticus", "pneumonia", "CAP", "HAP", "VAP",
        "pleural effusion", "empyema", "pneumothorax", "hydropneumothorax", "bronchiectasis", "ILD",
        "pulmonary fibrosis", "ARDS", "respiratory failure", "type 1", "type 2", "BiPAP", "CPAP", "NIV",
        "nebulization", "crepitations", "rhonchi", "wheeze", "stridor", "dyspnea", "orthopnea", "PND", "hemoptysis",
        "GERD", "gastritis", "peptic ulcer", "UGI bleed", "hematemesis", "melena", "hematochezia",
        "CLD", "cirrhosis", "portal hypertension", "ascites", "SBP", "hepatic encephalopathy", "varices",
        "pancreatitis", "cholecystitis", "choledocholithiasis", "hepatitis", "hepatomegaly", "splenomegaly",
        "appendicitis", "intestinal obstruction", "perforation peritonitis", "IBD", "ulcerative colitis", "Crohn's",
        "AKI", "CKD", "ESRD", "nephrotic syndrome", "nephritic syndrome", "glomerulonephritis", "pyelonephritis",
        "UTI", "urosepsis", "hyperkalemia", "hyponatremia", "metabolic acidosis", "hemodialysis", "AV fistula",
        "CVA", "ischemic stroke", "hemorrhagic stroke", "ICH", "SAH", "SDH", "EDH", "TIA", "hemiparesis",
        "hemiplegia", "paraplegia", "quadriparesis", "aphasia", "dysarthria", "ataxia", "seizure", "status epilepticus",
        "meningitis", "encephalitis", "GBS", "myasthenia gravis", "Parkinson's", "neuropathy", "vertigo", "syncope",
        "Babinski", "Kernig's", "Brudzinski's", "CSF", "lumbar puncture", "papilledema",
        "T2DM", "T1DM", "DKA", "HHS", "hypoglycemia", "hypothyroidism", "hyperthyroidism", "thyroid storm",
        "Cushing's", "Addison's", "malaria", "dengue", "typhoid", "enteric fever", "scrub typhus", "leptospirosis",
        "chikungunya", "tuberculosis", "MDR-TB", "HIV", "viral hepatitis", "cellulitis", "necrotizing fasciitis",
        "schizophrenia", "bipolar disorder", "MDD", "GAD", "panic attack", "OCD", "delirium", "alcohol withdrawal",
        "delirium tremens", "EPS", "akathisia", "NMS", "serotonin syndrome", "fracture", "dislocation",
        "osteoarthritis", "rheumatoid arthritis", "osteomyelitis", "septic arthritis", "spondylosis", "sciatica",
        "ORIF", "POP cast", "slab", "traction", "compartment syndrome",
        "paracetamol", "ibuprofen", "diclofenac", "aceclofenac", "tramadol", "morphine", "fentanyl",
        "pantoprazole", "omeprazole", "rabeprazole", "ondansetron", "metoclopramide", "domperidone",
        "amoxicillin", "clavulanate", "Augmentin", "ceftriaxone", "cefotaxime", "cefuroxime", "cefixime",
        "azithromycin", "doxycycline", "ciprofloxacin", "levofloxacin", "moxifloxacin", "metronidazole",
        "piperacillin", "tazobactam", "Piptaz", "cefoperazone", "sulbactam", "meropenem", "imipenem",
        "vancomycin", "linezolid", "amikacin", "gentamicin", "clindamycin", "fluconazole", "voriconazole",
        "acyclovir", "oseltamivir", "metformin", "glimepiride", "sitagliptin", "empagliflozin", "dapagliflozin",
        "insulin", "regular insulin", "glargine", "amlodipine", "telmisartan", "losartan", "ramipril",
        "enalapril", "metoprolol", "bisoprolol", "carvedilol", "furosemide", "Lasix", "torsemide",
        "spironolactone", "aspirin", "clopidogrel", "ticagrelor", "atorvastatin", "rosuvastatin",
        "heparin", "LMWH", "enoxaparin", "warfarin", "apixaban", "rivaroxaban", "noradrenaline", "dopamine",
        "dobutamine", "adrenaline", "atropine", "amiodarone", "adenosine", "nitroglycerin", "isosorbide",
        "hydrocortisone", "dexamethasone", "methylprednisolone", "prednisolone", "budesonide",
        "salbutamol", "ipratropium", "Duolin", "tiotropium", "montelukast", "levocetirizine", "pheniramine",
        "phenytoin", "levetiracetam", "sodium valproate", "carbamazepine", "lorazepam", "midazolam",
        "haloperidol", "risperidone", "olanzapine", "quetiapine", "fluoxetine", "sertraline", "escitalopram",
        "ORS", "NS", "RL", "DNS", "D5", "D25", "KCl", "calcium gluconate", "sodium bicarbonate",
        "mg", "mcg", "gm", "mL", "IU", "OD", "BD", "TDS", "QID", "HS", "SOS", "stat", "IV", "IM", "SC", "PO"
    )

    private val SYSTEM_PROMPT = """
        You are an ultra-accurate multilingual and medical voice dictation engine.
        Supported languages ONLY: English, Hindi, Hinglish (Hindi in Roman script), and Odia.
        Do not output any other language.

        Core rules:
        1. Automatic language handling:
           - Detect English, Hindi, Hinglish, and Odia automatically—even when mixed mid-sentence.
           - Keep English in English.
           - Keep conversational Hinglish in natural Roman script (do NOT force Devanagari unless the speaker clearly dictated pure formal Hindi).
           - Write Odia phrases accurately in Odia script (ଓଡ଼ିଆ), while keeping embedded English/medical terms in English.
        2. Clinical Safety & Medical Terminology (MANDATORY):
           - NEVER alter, drop, round, or invent numbers, decimal points, lab values, vitals, or units (mg, mcg, gm, mL, IU, %, mmHg).
           - NEVER alter negations (no, not, nil, absent, negative for, denies, without, nahi, na) or laterality (left, right, bilateral, unilateral).
           - Recognize clinical terminology across all specialties and fix only clear phonetic misspellings into standard medical terms (e.g., "piperacillin-tazobactam", "SpO2", "CBC, LFT, RFT"). Never substitute a different drug if uncertain.
        3. Intelligent Punctuation & Formatting:
           - Remove filler words (um, uh, ah, matlab, basically, you know) and false starts, while preserving all clinical facts.
           - Infer accurate punctuation (periods, commas, question marks, colons, hyphens) from natural speech rhythm.
           - Format enumerated items or treatment steps into clean bullet points or numbered lists, and separate distinct thoughts into paragraphs.
           - Output ONLY the final cleaned text with no quotes, explanations, or preambles.
    """.trimIndent()

    suspend fun polishText(
        apiKey: String,
        preferredModel: String,
        rawTranscript: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val modelsToTry = (listOf(preferredModel) + Prefs.AVAILABLE_MODELS).distinct()
        var lastError: Exception = Exception("Unknown error")

        for (model in modelsToTry) {
            var attempts = 0
            while (attempts < 2) {
                attempts++
                try {
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    val url = URL(endpoint)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        connectTimeout = 10000
                        readTimeout = 20000
                        doOutput = true
                    }

                    val payload = JSONObject().apply {
                        put("system_instruction", JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
                        })
                        put("contents", JSONArray().put(JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", "Clean up, preserve all clinical numbers/negations/doses, and format:\n$rawTranscript")))
                        }))
                        if (model.startsWith("gemini-3.8")) {
                            put("generationConfig", JSONObject().apply {
                                put("thinkingConfig", JSONObject().apply {
                                    put("thinkingLevel", "LOW")
                                })
                            })
                        }
                    }

                    conn.outputStream.use { os ->
                        os.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream.bufferedReader().use { it.readText() }

                    if (code == 429 && attempts == 1) {
                        delay(800)
                        continue
                    }
                    if (code == 503 || code == 429 || code == 404) {
                        lastError = Exception("HTTP $code on $model")
                        break
                    }
                    if (code !in 200..299) {
                        return@withContext Result.failure(Exception("HTTP $code: $body"))
                    }

                    val json = JSONObject(body)
                    val text = json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()

                    return@withContext Result.success(text)
                } catch (e: Exception) {
                    lastError = e
                    break
                }
            }
        }
        Result.failure(lastError)
    }
}

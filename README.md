# Medଉକ୍ତି (MedUkti) v8.3.0 — Medical, Hinglish & Odia Voice Keyboard

**Medଉକ୍ତି** (*Med* + *ଉକ୍ତି* / speech) is a fast, 100% free-tier Android voice-typing keyboard (`InputMethodService`) built for Indian doctors and Odia speakers. It runs with **<20 MB RAM, zero phone heating, and zero Accessibility permissions** (so banking and UPI apps never show security warnings).

---

## The 3-Mode Architecture (`ଓ` | `🩺` | `हin` | `🎤` | `📋` | `⌫`)

Each mode is routed to the strongest free-tier cloud model for that language, paired with an ultra-fast **on-device clinical normalizer**:

| Mode | Pill | Speech / AI Engine | Best For |
| :--- | :---: | :--- | :--- |
| **Odia** | `ଓ` | **ElevenLabs `scribe_v2`** (`language_code="or"`) + automatic **Google `gemini-3.8-flash`** fallback | Casual conversational Odisha Odia (*Puri, Cuttack, Berhampur, Sambalpuri, Rourkela*) + English code-switching |
| **Medical** | `🩺` | **Groq `whisper-large-v3`** (`language="en"`) + **`openai/gpt-oss-120b`** polish + **`MedicalNormalizer`** | Indian-accented English clinical notes, OPD prescriptions, discharge summaries, and drug names |
| **Hinglish** | `हin` | **Google `gemini-3.8-flash`** + **`MedicalNormalizer`** | Natural mixed Hindi + English in Roman script (*"Patient ko Ceftriaxone ke saath Azithromycin dena hai"*) |

---

## Key Features in v8.3.0

* **1-Tap Auto-Listen Bar:** Tapping `ଓ`, `🩺`, or `हin` switches modes and starts listening immediately. Long-pressing any mode pill opens Settings.
* **Midnight Clinical Minimal UI:** Single slim row (`#0B0E14` Obsidian, `#1E2430` Charcoal, `#0D9488` Clinical Teal active pill, `#10B981` Emerald listening pulse, `#D97706` Amber polishing).
* **On-Device Clinical Brain (`<2 ms`, 0 network lag):**
  * **1,056 India-available generic drugs** (brand-free, updated through Nov 2026 CDSCO approvals) + **1,830 clinical terms** + 98k open medical lexicon compiled at build time.
  * **Context-Locked Rescue:** Triggers fuzzy/skeleton drug rescue only after prescribing cues (`Tab.`, `Inj.`, `started on`, `gave him`) or on words with real drug/clinical suffixes (`-mycin`, `-gliflozin`, `-prazan`, `-mab`, `-itis`), preventing false overwrites of everyday words like `elderly`, `abdomen`, or `weakness`.
  * **Medical Prefix Joiner & UK Spellings:** Rejoins split terms (`hepato megaly` → `hepatomegaly`, `pneumo thorax` → `pneumothorax`), enforces Indian/British clinical spellings (`oedema`, `dyspnoea`, `haematemesis`, `oesophagus`), and formats labs/acronyms (`USG abdomen`, `HRCT chest`, `2D Echo`, `LRTI`, `GERD`, `DVT`).
* **Bluetooth Mic Auto-Routing:** Automatically routes `AudioRecord` to connected **Galaxy Buds 2 Pro / JBL / Sony** headset mics and restores normal phone audio on stop.
* **1-Tap `📋` Paste + Last-15 Dictation History:** Tap `📋` to paste clipboard; **long-press `📋`** for `↺ Undo last phrase` and your last 15 dictated notes.
* **Protected API Keys Vault & Backup:** Keys are tucked inside **Advanced Options** with accidental-paste confirmation, automatic background vault mirroring, and 1-tap `Backup All` / `Restore All` (JSON bundle).
* **Built-in Test Sandbox & `keepScreenOn`:** Test all 3 modes right inside the app; screen stays awake automatically while listening.

---

## Choosing Your Architecture: 100% Online vs. Hybrid vs. 100% Offline

### 1. 100% Online (Current `v8.3.0` — Recommended)
* **How to get it:** Use this `main` branch / `v8.3.0` APK directly.
* **What you need:** Free-tier API keys from [Groq](https://console.groq.com/keys), [Google AI Studio](https://aistudio.google.com/apikey), and [ElevenLabs](https://elevenlabs.io/).
* **Pros:** `<20 MB` RAM, zero overheating, instant startup, handles 5+ Odisha dialects and 1,056+ drugs effortlessly.
* **Cons:** Requires internet; ElevenLabs free tier has a monthly credit cap (covered automatically by Gemini 3.8 fallback).

### 2. 100% Offline (No Cloud APIs — `v4.2.4` / `v4.2.5`)
* **How to get it:** Checkout the `v4.2.4` / `v4.2.5` release/commit (powered by `sherpa-onnx`, Silero VAD, `Whisper Large-v3-Turbo INT8 ~1.04 GB`, and `SraVaani 1.0 ONNX ~1.81 GB`).
* **Pros:** Zero internet required, zero API keys, strong offline Odia (`SraVaani 1.0`).
* **Cons (Why we moved off it):** Uses **2.8–4 GB RAM** on-device, causes heavy thermal throttling/battery drain, lags on older/midrange phones, and can freeze or crash background apps during long dictations.

### 3. Hybrid (Best of Both Worlds)
* **Light Hybrid (already in `v8.3.0`):** Cloud ASR (`Groq`/`Gemini`/`ElevenLabs`) + **Offline `MedicalNormalizer`** (`<15 MB` RAM) + local `AudioRecord` DSP/pre-roll.
* **Heavy Hybrid (`v4.2.x` + `v6.x` mix):** If you have a 12 GB+ RAM device and want **Offline Odia (`SraVaani 1.0`)** with **Online Medical (`Groq Whisper`)**, combine `ModelDownloader.kt` + `sherpa-onnx` from `v4.2.5` for `ODIA` mode while keeping `CloudAsrClient.kt` for `MEDICAL` and `HINGLISH`. Expect ~2 GB RAM usage when Odia is loaded.

---

## Quick Setup
1. Install the APK from [GitHub Actions / Releases](../../releases).
2. Open **Medଉକ୍ତି** → tap **Enable Keyboard** → enable **Medଉକ୍ତି Voice** → tap **Switch Keyboard**.
3. Expand **Advanced Options (Keys, Models & Voice)** → paste your **Groq**, **Gemini**, and **ElevenLabs** keys → tap **Test Keys ✓** → tap **Save Settings**.
4. Test all 3 modes right inside the **Test Voice Typing** box on the main screen.

---

## Acknowledgments
* **Speech & AI:** [Groq](https://console.groq.com/), [Google Gemini](https://aistudio.google.com/), [ElevenLabs Scribe](https://elevenlabs.io/), and [Project Vaani / SraVaani 1.0](https://vaani.iisc.ac.in/models/sravaani).
* **Medical Lexicons:** [NLEM 2022](https://nppa.gov.in/nlem2022), CDSCO 2024–2026 approvals, KD Tripathi Pharmacology index, and [glutanimate/wordlist-medicalterms-en](https://github.com/glutanimate/wordlist-medicalterms-en).

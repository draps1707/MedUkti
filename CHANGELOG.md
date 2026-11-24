# Medଉକ୍ତି (MedUkti) — Complete Version History, Wins, Misses & Lessons

This document tracks every iteration from **v4.2.4** through **v8.1.0**—including what worked, what broke, and why each architectural decision was made.

---

## Phase 1: 100% Offline Era (`v4.2.4` – `v4.2.5`)
* **What we built:** On-device `sherpa-onnx` inference with Silero VAD, `Whisper Large-v3-Turbo (INT8, 1.04 GB)`, `Indian-EN Streaming Zipformer (71 MB)`, and `SraVaani 1.0 ONNX (1.81 GB)`.
* **What worked:** Strong offline casual Odisha Odia (`SraVaani 1.0`) with zero internet needed.
* **What failed / Cons:** Consumed **3–4 GB RAM** inside an Android keyboard (`InputMethodService`), severely heated up a Galaxy S23 Ultra, froze background apps, and even caused device shutdowns under sustained load. Whisper INT8 also hallucinated YouTube outros (*"Please like and subscribe"*) on quiet audio.

## Phase 2: Transition to 100% Online (`v5.0` – `v6.2.0`)
* **What we built:** Stripped out the 2.8 GB offline models (`<20 MB` RAM), moved ASR to **Groq Whisper** (`MED` / `HIN`) and **Gemini Flash** (`OD` / `HI`), added live streaming previews, Gboard-style spacebar swipe, and 1-tap `Save fix?` bar.
* **What worked:** Zero heating, instant keyboard launch, clean UI.
* **What failed / Cons:** Gemini Flash handled textbook/grammar Odia well (~85–90%) but struggled with fast casual Odisha speech (~50–60%) and occasionally emitted `|` pipes or split compound words.

## Phase 3: Drug Dictionary & Detection Overhaul (`v6.3.0` – `v6.5.1`)
* **v6.3.0:** Upgraded `core_drugs.txt` from **691 → 1,056 India-available brand-free generics** (merging KD Tripathi, NLEM 2022, AIIMS EML, and 2024–Nov 2026 CDSCO approvals like oral *semaglutide*, *resmetirom*, *tegoprazan*, *cenobamate*, *tislelizumab*). Fixed `` escape bug in `CloudAsrClient.kt`.
* **v6.4.0 – v6.4.1:** Added 3-word split-drug joiner (`"empa gli flozin"` → `Empagliflozin`), Rx-context booster, and 2024–2026 drug suffixes (`-mab`, `-nib`, `-prazan`, `-glutide`). Removed the `Posting Focus Pack` dropdown in favor of a universal clinical hint + Personal Fixes. Fixed multi-word phrase lookup in the **Check** box (`rectus sheath`).
* **v6.5.0 – v6.5.1 (Tested & Rejected):** Added **BHASHINI** and **Android Native `or-IN`** as online Odia experiments.
  * *Why rejected:* BHASHINI required multi-level govt portal approvals (`Manager` + `CEO` approval pending). Android `or-IN` silently fell back to US English on Samsung (`"Are you going to Concord Georgia moms..."`). Both were cleanly removed in `v7.0.0`.

## Phase 4: Minimalism, ElevenLabs Odia & Language Separation (`v7.0.0` – `v7.4.0`)
* **v7.0.0 – v7.0.1:** Integrated **ElevenLabs `scribe_v2`** for `OD` (with Gemini fallback). Immediately solved casual Odisha dialect recognition across **Puri, Cuttack, Berhampur, Sambalpuri, and Talcher** (~95–99% accurate). Fixed `Prefs.kt` newline escape bug.
* **v7.1.0 – v7.1.3:**
  * Redesigned keyboard to an ultra-minimal **1-row bar** (`🩺` / `ଓ` | pure Mic icon | `⌫`).
  * Switched default Groq ASR back from `turbo` to **`whisper-large-v3`** (priority: accuracy over raw speed) and turned **Live Streaming Preview OFF** to stop `lockOrClearLiveDraft()` from erasing text ("vanishing text bug").
  * Added `keepScreenOn = true` while listening so the screen never sleeps mid-dictation.
  * *Lesson learned:* Removing `language="en"` on Whisper for auto-detect Hinglish caused Korean (`버림`), Japanese (`こんにちは`), and obscure Latin hallucinations (`aceturate`, `abductores`).
* **v7.2.0 – v7.2.1:** Split into **3 dedicated modes on 3 separate free-tier providers** (`ଓ` ElevenLabs | `🩺` Groq locked to `language="en"` | `हin` Gemini 3.8 Flash), all visible as 1-tap pills. Fixed `selectMode()` flushing mid-dictation.
* **v7.3.0 – v7.3.1:** Applied **Midnight Clinical** palette (`#0B0E14` Obsidian, `#0D9488` Teal, `#10B981` Emerald, `#D97706` Amber), added **1-tap `📋` Paste** + **Copy Backup / Paste Restore** for Personal Fixes. Fixed duplicate `ClipboardManager` import.
* **v7.4.0:** Added **1-tap auto-listen on mode switch**, moved all API keys off the main screen into **Advanced Options** with accidental-paste confirmation dialogs, added silent **Background Key Vault** + `Backup All` / `Restore All`, and added the **Test Voice Typing** box on the main screen.

## Phase 5: Surgical Medical Normalizer & Audio Streamlining (`v7.5.0` – `v8.1.0`)
* **v7.5.0 (Critical Fix):** Discovered `MedicalNormalizer`'s consonant skeleton (`coreSkeletonMap`) was hijacking everyday English/Hinglish words (`elderly` → `Olodaterol`, `weakness` → `abbokinase`, `abdomen` → `Dobutamine`, `followed` → `folvite`, `bloating` → `ablating`). Locked skeleton/fuzzy rescue strictly to **Rx context or drug/clinical suffixes with matching first letter**.
* **v7.6.0:** Added 2-word Rx triggers (`started on`, `gave him`), exam/symptom triggers (`complains of`, `bilateral`, `tenderness`), medical-prefix joiner (`hepato-`, `pneumo-`, `chole-`), UK/Indian clinical spellings (`oesophagus`, `haemorrhage`, `ischaemia`), and lab/acronym formatting (`USG abdomen`, `HRCT chest`, `2D Echo`, `LRTI`, `GERD`, `DVT`).
* **v7.7.0 – v7.7.1 (Experimental DSP — Reverted):** Added Android `NoiseSuppressor`, `AGC`, `80 Hz` HPF, `ZCR` gate, and `trimSilenceEdges()`.
  * *Why reverted:* Legacy Android DSP muffled soft Odia consonants (`naka` → `ନାଗ`, `kariba` → `କରିଆ`, `"tapare pijuli khaibi"` → Spanish `"Por el bien y la mejoración"`). Kept the **live mic-icon voice pulse** and **1x network retry**.
* **v8.0.0 (Gold Master Audio):** Reverted `v7.7` audio filters back to clean raw `VOICE_RECOGNITION` + `320 ms` pre-roll + `normalizeGain()`, added a **0.5-second minimum speech guard**, and stripped accented foreign hallucinations in `ODIA`.
* **v8.1.0 (Final Release):** Added **Auto Bluetooth Mic Routing** (for Galaxy Buds 2 Pro, JBL Tune Buds, Sony WH-XB910N via `AudioManager`), **Last-15 Dictation History** on long-press `📋`, and complete GitHub documentation.

* **v8.1.1 (Prompt-Leak & Hallucination Fix):** Removed all `(e.g. '...')` example sentences from Gemini/Groq prompts (fixing the echoed Hinglish example sentence), added `<2-word` and chatbot-reply guards to `callGroqPolish()` (fixing *"Please provide the transcription..."*), and raised `MEDICAL` `minRms` to `0.006f` to block quiet-hum hallucinations.
* **v8.2.0 (Silero VAD + Root Hallucination Killer):** Bundled on-device **Silero VAD (`silero_vad.onnx`, 629 KB via ONNX Runtime)** to block breaths, fans, and desk bumps across all 3 modes; switched Groq Whisper to `verbose_json` (`no_speech_prob <= 0.60`, `avg_logprob >= -1.0`) + wildcard subtitle-credit filtering; capped `normalizeGain()` so quiet room hum is never 8x-amplified; enforced `0.5s` (`8000` samples + `>= 8` active speech frames) even on Stop; added transparent fallback toast when ElevenLabs switches to Gemini; and guarded `"odi" -> "OD"` shorthand in Hinglish.

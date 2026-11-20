# Medଉକ୍ତି (MedUkti) — Offline Medical & Odia Voice Keyboard

**Medଉକ୍ତି** (*Med* + *ଉକ୍ତି* / speech) is a **100% offline Android voice-typing keyboard** (`InputMethodService`) built for Indian doctors and Odia speakers. It runs completely on-device with **zero cloud APIs, zero subscriptions, and zero Accessibility permissions** (so banking and UPI apps never show security warnings).

---

## Key Features
* **Indian-English Clinical Dictation (`MED` mode):** Powered by **Whisper Large-v3-Turbo (INT8)** with optional live **Indian-English Streaming Zipformer** draft typing, plus a clinical normalizer for drug spellings, acronyms (`UTI`, `VUR`, `RBS`, `BP`, `SpO2`, `CKD`, `NICU`), and Rx shorthand (`Tab.`, `Inj.`, `OD`/`BD`/`TDS`).
* **Regional & Conversational Odia (`OD` mode):** Powered by **SraVaani 1.0**, trained on district-level Indian speech including coastal and western/southern Odisha dialects (*Berhampuria, Rayagadia, Sambalpuri, Shuddha Odia*), with automatic Odia punctuation (`।` / `?`).
* **Ultra-Compact 2-Row Keyboard:**
  * Live `OD` ↔ `MED` switching even while listening.
  * Apple-style live-mic editing (`⌫`, `Undo`, `Space`, `,`, `.`, `↵` work without stopping the mic).
  * Gboard-style Spacebar cursor swipe (slide left/right on `Space` to move the cursor).
  * One-tap `🌐` switch back to Samsung Keyboard or Gboard.
* **Personal Dictionary:** Fix custom local place names, abbreviations, or personal phrases (`wrong = right`) locally on your device.

---

## Quick Start for Doctors
1. **Install APK:** Download the latest `MedUkti-Android-APK` from [GitHub Releases](../../releases) (or Actions) and install it.
2. **Enable Keyboard:** Open **Medଉକ୍ତି**, tap **Enable Keyboard**, and toggle on **Medଉକ୍ତି Voice**.
3. **Download Offline Models (One-Time):**
   * Tap **Download** on **Whisper Large-v3-Turbo** (`~1.04 GB`) for Indian-English Medical dictation.
   * Tap **Download** on **SraVaani 1.0** (`~1.81 GB`) for Odia dictation.
   * *(Optional)* Download **Indian-EN Streaming Draft** (`~71 MB`) if you want instant live words as you speak.
4. **Dictate Anywhere:** Tap **Switch Keyboard** (or Android's bottom keyboard icon) to switch between Samsung/Gboard and **Medଉକ୍ତି Voice** anytime.

---

## Built With & Acknowledgments
* **AI Collaboration:** Built and iteratively refined with **Gemini** (architecture, audio/VAD tuning, clinical normalizer, and UI/UX).
* **Inference & VAD:** [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), [ONNX Runtime](https://onnxruntime.ai/), and Silero VAD.
* **Speech Models:**
  * [Whisper Large-v3-Turbo (sherpa-onnx INT8)](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo)
  * [Indian-English Streaming Zipformer](https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en)
  * [Parakeet-Indic v1](https://huggingface.co/amn-raw/parakeet-indic-v1)
  * [SraVaani 1.0 (ARTPARK-IISc / Project Vaani)](https://huggingface.co/ARTPARK-IISc/SraVaani-1.0)
* **Medical Lexicon:** [glutanimate/wordlist-medicalterms-en](https://github.com/glutanimate/wordlist-medicalterms-en) + curated Indian clinical terms.

---

## License
Released under the [MIT License](LICENSE).

# VoiceRambler Free (Samsung / Android)

A 100% free, zero-subscription AI voice dictation app powered by the **Google AI Studio Free Tier** (Gemini 3.8 Flash / 3.5 Flash-Lite) with native support for **mixed English, Hindi, Hinglish (Roman script), and Odia**.

## Features
- **Runs in the background** over Samsung Keyboard (or any keyboard) via a draggable floating mic bubble (`FloatingMicService` + `AccessibilityService`).
- **4 Dictation Modes** (tap the mode pill next to the mic to cycle):
  - `Hinglish`: English + Hindi/Hinglish in Roman script, removes fillers.
  - `Odia+Eng`: Odia in Odia script + English terms, removes fillers.
  - `Formal`: Polished, structured English.
  - `Voice Edit`: Reads the current text box and applies your spoken edit command (Rambler-style).
- **Personal Glossary**: Add custom names, places, and technical terms so Gemini spells them right every time.
- **16 kHz Mono AAC compression**: Tiny audio uploads for fast mobile-data response.
- **Offline Fallback**: Falls back to Android's built-in `SpeechRecognizer` if offline.

## How to Build & Install on Your Samsung Phone
1. Unzip this folder and open it in **Android Studio** (Jellyfish / Koala / Ladybug or newer).
2. Let Gradle sync, then connect your Samsung phone via USB (with USB Debugging enabled) and click **Run** (or **Build > Build Bundle(s) / APK(s) > Build APK(s)** to sideload).
3. Get a free API key (no credit card needed) from [Google AI Studio](https://aistudio.google.com/).
4. Open **VoiceRambler Free** on your phone, paste your API key, add your Personal Glossary, and grant the 4 permissions shown on screen.
5. Tap **Start Floating Mic Bubble** and dictate in any app!

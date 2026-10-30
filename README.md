# Project T.E.S.S.A.R.A.C.T. – Local-First AI Voice Assistant

> **A privacy-first, zero-telemetry Android voice assistant that executes hardware controls and chained routines 100% locally on-device, seamlessly falling back to Nvidia Nemotron on Nebius Token Factory for complex multi-step intelligence via Bring-Your-Own-Token (BYOT).**

Built for the **Nvidia x Nebius Cloud Hackathon**.

---

## ⚡ Core Philosophy: Local-First + BYOT Nemotron

Voice assistants should not transmit everyday actions—like adjusting screen brightness, toggling a flashlight, or launching an app—to a remote cloud server. Everyday tasks belong locally on the user's silicon.

Project **T.E.S.S.A.R.A.C.T.** (*Tactile Edge Sensory Speech Acoustic Reactive Assistant & Controller Terminal*) solves the latency and privacy problem with a two-tier architecture:

1. **Deterministic Local Execution (Tier 1)**: Simple system commands, volume, brightness, phone calls, application launches, tactile haptics, flashlight controls, and multi-action macros ("Routines") execute **100% on-device** with **0ms cloud latency** and **zero internet access required**.
2. **Nvidia Nemotron on Nebius Cloud (Tier 2 Fallback)**: When a request is conversational, complex, multi-step, or requires deep world knowledge, the engine routes the prompt to **Nvidia Nemotron-4-340B-Instruct** (or 70B/4B) deployed on **Nebius Token Factory / Studio**.
3. **Zero Developer Risk & Total Privacy (BYOT)**: No shared developer API keys or payment credentials exist in the source code or binary. Users and hackathon evaluators provide their own Nebius API key via an in-app setup panel, guaranteeing complete data sovereignty and zero vendor lock-in.

---

## 🎙️ Speech Recognition Engine: Built-In Android SpeechRecognizer

T.E.S.S.A.R.A.C.T. intentionally uses Android's native speech recognition pipeline (`android.speech.SpeechRecognizer`):

- **Offline Processing Priority**: Every audio capture intent explicitly passes `RecognizerIntent.EXTRA_PREFER_OFFLINE = true` (`android.speech.extra.PREFER_OFFLINE`). On devices with on-device speech packs installed, audio never leaves the phone.
- **Zero Forced Downloads**: Unlike heavy frameworks (Vosk, Whisper.tflite, Porcupine) that force users to download multi-gigabyte models before first launch, the app starts instantly and remains under ~15MB.
- **Natural Silence Watchdog**: Features adaptive silence thresholds (4.5s in low-power standby, 1.8s in active command mode) to prevent premature cut-offs while speaking.
- **Resilient Recovery**: Auto-reconnects and resets binders on system busy (`ERROR_RECOGNIZER_BUSY`, `ERROR_CLIENT`, `ERROR_SERVER_DISCONNECTED`) with exponential backoff.

---

## 🏗️ Architecture Pipeline

```text
                     [User Voice / Ambient Acoustics]
                                    │
                                    ▼
                        [Android SpeechRecognizer]
                     • EXTRA_PREFER_OFFLINE = true
                     • Zero external model downloads
                                    │
                                    ▼
                         [LocalComponentRouter]
                     • Custom Wake Words & Sentences
                     • Regex & Fuzzy Phonetic Matcher
                                    │
            ┌───────────────────────┴───────────────────────┐
            ▼                                               ▼
    [Local Match Found]                            [No Match / Complex Query]
    • Volume / Brightness (0-100%)                          │
    • Flashlight & Tactile Vibration                        ▼
    • App Launcher & Phone Calls                    [Nebius Cloud]
    • Chained Routines ("Movie Time")          Token Factory / Studio Endpoint
    • Screen Scrape / OCR Hierarchy                         │
            │                                               ▼
            │                                       [Nvidia Nemotron]
            │                                   • nemotron-4-340b-instruct
            │                                   • llama-3.1-nemotron-70b-instruct
            │                                   • nemotron-mini-4b-instruct
            │                                               │
            └───────────────────────┬───────────────────────┘
                                    ▼
                         [Acoustic Text-to-Speech]
                                    │
                                    ▼
                      [Glassmorphic 4D Hypercube HUD]
                   (Siri-style floating obsidian overlay)
```

---

## 🚀 Key Features

### 1. Custom Wake Words & Full Sentences
- Configure custom trigger phrases: single words (`"Jarvis"`, `"Computer"`) or full conversational sentences (`"Wake up buddy"`, `"Activate core"`, `"Hey Tesseract"`).
- Dual matcher combines acoustic waveform energy spikes with fast phonetic substring recognition.

### 2. Multi-Action Chained Routines (Macros)
Chain multiple actions into a single voice trigger:
- **"Movie Time"** (Default Built-In Routine):
  1. Sets Volume to **89%**.
  2. Sets Brightness to **76%**.
  3. Launches **Netflix** (`com.netflix.mediaclient`).
  4. Connects **Bluetooth** to user's headphones.
  5. Speaks voice confirmation: *"Movie mode engaged. Enjoy your film."*
- **"Good Morning"**: Volume 50%, Brightness 80%, greeting announcement.
- **"Night Owl"**: Volume 15%, Brightness 10%, Flashlight OFF, enters standby sleep.
- **Custom Routine Creator**: Build custom macros with visual action steps and one-tap test execution.

### 3. Google-Style "Circle to Search" + Nvidia Nemotron Analysis
- Circle or lasso any area on the phone screen with a glowing neon lasso.
- Crops the exact spatial bounding box, extracts text via accessibility node trees, and offers:
  - 📋 **Copy Text**
  - 🔍 **Search Google**
  - 🔊 **Read Aloud**
  - ⚡ **Ask Nvidia Nemotron AI**: Transmits the cropped context directly to Nemotron on Nebius Token Factory for intelligent explanation.

### 4. Privacy Shield & Sensitive Screen Exclusion
- **Financial App Protection**: Screen scraping and HUD overlay are **strictly blocked** when banking or payment apps are foregrounded (`Paytm`, `PhonePe`, `Google Pay`, `BHIM`, `CRED`, crypto wallets, and password managers).
- **Settings & System UI Exclusion**: `com.android.settings` and system security centers suppress all screen capture and overlay display.
- **Lockscreen / Keyguard Auto-Hide**: Overlay immediately pauses when the phone is locked.

### 5. Thermal & Battery Guardian
- Continuously tracks battery percentage and Android `PowerManager` thermal status.
- When battery falls below the user threshold (default 15%) or thermal state rises to `SEVERE` / `CRITICAL`, it duty-cycles acoustic monitoring from 80ms to 300ms, saving CPU power and stopping thermal throttling.
- User-adjustable toggle and slider in the Daemon dashboard.

### 6. Acoustic Calibration Engine
- Samples ambient room noise floor (dBFS) and computes spectral clarity.
- Dynamically calculates the optimal voice surge threshold for accurate wake detection.

### 7. Live Web Search (Tavily BYOK)
For factual and real-time questions, T.E.S.S.A.R.A.C.T. first tries Tavily Search (Bring-Your-Own-Key).  
If Tavily is not configured or cannot answer, it automatically falls back to Nvidia Nemotron on Nebius.

---

## 🧪 Hackathon Judge Testing Guide: Nebius Nemotron Connection

Evaluators can verify the Nvidia Nemotron integration on Nebius Cloud in under 2 minutes:

### Step 1: Obtain a Nebius API Key
1. Visit [Nebius Token Factory](https://tokenfactory.nebius.ai) (or [Nebius Studio](https://studio.nebius.ai)).
2. Sign in or create a developer account.
3. In the navigation sidebar, navigate to **API Keys** -> **Create API Key**.
4. Copy the generated secret key.

### Step 2: Configure in T.E.S.S.A.R.A.C.T.
1. Launch the app and select the **Commands & AI** tab (second icon on the bottom navigation bar).
2. Tap the **Nemotron AI** sub-tab.
3. Paste your key into the **Nebius API Key** field (tap the clipboard paste icon).
4. Select the endpoint:
   - `https://api.tokenfactory.nebius.ai/v1` (Token Factory)
   - `https://api.studio.nebius.ai/v1` (Studio)
5. Select a model chip:
   - `nvidia/nemotron-4-340b-instruct` (Flagship 340B)
   - `nvidia/llama-3.1-nemotron-70b-instruct` (High performance 70B)
   - `nvidia/nemotron-mini-4b-instruct` (Fast edge 4B)
6. Tap **Test Connection**. A live HTTP ping measures response latency (typically 200–450ms) and confirms connectivity.
7. Tap **Save Config**.

### Step 3: Test Local vs Cloud Fallback
- **Test Local (Offline)**: Say *"Set volume to 89%"* or *"Turn on flashlight"* or *"Movie Time"*. Notice instantaneous 0ms execution without network traffic.
- **Test Cloud (Nvidia Nemotron)**: Say *"Explain the difference between quantum entanglement and quantum superposition"*. The app detects that no local hardware command matches, queries Nemotron on Nebius Token Factory, displays the response, and reads it aloud via TTS.
- **Test Circle to Search**: Circle any text on your screen and tap **Ask Nvidia Nemotron AI**.

---

## 🔒 Privacy & Zero-Data-Leak Statement

- **Zero Cloud Telemetry**: T.E.S.S.A.R.A.C.T. has no analytics SDKs, tracking pixels, or third-party telemetry.
- **Local Storage**: Preferences, custom routines, wake phrases, and the user's Nebius API key are stored strictly in private device storage (`Context.MODE_PRIVATE`).
- **Direct Cloud Traffic**: When AI queries are initiated, the HTTPS request travels directly from the user's Android device to the user's selected Nebius endpoint (`api.tokenfactory.nebius.ai` or `api.studio.nebius.ai`). No middleman server exists.

---

## 🛠️ Android Studio Setup & Compilation

### Prerequisites
- **Android Studio** (Ladybug / Koala / Hedgehog or newer)
- **Android SDK Platform 35 / 36**
- **JDK 17 or JDK 21**
- **Gradle 8.9+** (handled automatically by Gradle wrapper)

### Build Instructions
1. Clone or download this project.
2. Open the project root in Android Studio (`File` -> `Open...`).
3. Let Gradle sync project dependencies.
4. Connect an Android device (Android 7.0 / API 24+) or launch an emulator.
5. Click **Run** (`Shift + F10`) or execute in terminal:
   ```bash
   gradle assembleDebug
   ```
6. Grant the requested runtime permissions:
   - **Microphone**: For speech recognition and ambient acoustic calibration.
   - **Display over other apps**: For the floating 4D glassmorphic HUD overlay.
   - **Accessibility Service** *(Optional but recommended)*: For Circle-to-Search localized text scraping and screen automation.

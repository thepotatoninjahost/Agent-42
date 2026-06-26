# Agent 42

An on-device AI agent for Android that reasons, learns, adapts, and improves with every interaction. Built with Kotlin and Compose for Samsung Galaxy S25 and other Android devices.

## Features

- **On-device LLM inference** via Nexa SDK (Qwen3-8B-NPU) — no cloud required
- **Real reasoning** — chain-of-thought, decomposition, and reflective modes
- **Persistent memory** — encrypted local database with embeddings for semantic recall
- **Self-modification** — proposes behavior changes, learns from feedback, auto-rolls back bad changes
- **Voice I/O** — speech-to-text input and text-to-speech output
- **Thermal-aware** — throttles processing when the phone gets hot

## Building from GitHub (smartphone only)

This project uses a GitHub Actions workflow that you can trigger manually to build the APK.

### 1. Push code to GitHub

Use the GitHub mobile app or any Git client on your phone to push changes to your repository.

### 2. Trigger the build

1. Open your repository on GitHub (mobile web or app)
2. Go to **Actions** → **Build APK**
3. Tap **Run workflow**
4. Choose **debug** (for testing) or **release** (for distribution)
5. Tap **Run workflow**

### 3. Download the APK

After the workflow completes (usually 5–10 minutes):
1. Go to the completed workflow run
2. Scroll down to **Artifacts**
3. Download `agent42-debug-apk` or `agent42-release-apk`
4. Install the APK on your phone

## Model Setup (Qwen3-8B-NPU)

The app requires the Qwen3-8B-NPU model files from the Nexa SDK. These files are too large for GitHub (≈ 4–5 GB), so you must transfer them to your phone manually.

### Option A: Download via HuggingFace / Nexa

1. Download the Qwen3-8B-NPU model files from the official Nexa model repository:
   - Search for "qwen3-8b-npu" on HuggingFace or the Nexa model hub
   - Download the `.nexa` model file and supporting files

2. Place the model files in this directory on your phone:
   ```
   /Android/data/com.agent42/files/models/Qwen3-8B-NPU/
   ```
   The expected file is `files-1-1.nexa` inside that directory.

### Option B: adb push (if you have access to a PC)

```bash
adb push files-1-1.nexa /sdcard/Android/data/com.agent42/files/models/Qwen3-8B-NPU/
```

### Option C: Direct download within the app (future feature)

A future update will add an in-app model downloader. For now, use Option A or B.

## Project Structure

```
app/src/main/java/com/agent42/
├── core/           # ViewModel, context manager, thermal manager
├── memory/         # Room database, memory system, embeddings
├── reasoning/      # Reasoning engine (chain-of-thought, decomposition)
├── selfmodification/ # Approval gate, code modification engine
├── security/       # SQLCipher encryption layer
├── nexa/           # Nexa SDK adapter wrapper
├── voice/          # Speech-to-text and text-to-speech
└── ui/             # Compose screens (chat, memory, learning, approval)
```

## Development Notes

- **minSdk**: 29 (Android 10)
- **targetSdk**: 34 (Android 14)
- **Kotlin**: 2.0.0
- **Compose**: Material3 with custom dark theme
- **Database**: Room + SQLCipher (encrypted)
- **LLM**: Nexa SDK with NPU acceleration on supported devices (Snapdragon 8 Gen 3+)

## License

Apache 2.0

# MotionSpeak Mobile

Native Android application for offline-first Filipino Sign Language (FSL) recognition, facial emotion detection, and vocabulary learning.

## Features

- **Live & Tap Camera-Based Sign Translation**: Real-time sign translation with text display and Text-to-Speech (TTS) audio feedback.
- **Facial Emotion & Tone Recognition**: Detects facial expressions concurrently with hand gestures to provide emotional context and expressive speech.
- **Dynamic Siri Glow Meter**: Fluid visual feedback showing gesture buffer progression and confidence state.
- **Overhauled Camera Controls**: Floating action buttons for flipping camera, clearing text, repeating speech, and copying translation to clipboard.
- **Comprehensive Word Bank**: Searchable FSL catalog organized by categories, complete with demonstration videos, download capabilities, and favorites.
- **Emergency SOS Alert**: Two-second press-and-hold audible alarm and visual alert for quick assistance.
- **On-Device Offline Inference**: Runs entirely local models powered by TensorFlow Lite and Google MediaPipe—zero latency and fully functional without an internet connection.
- **Over-The-Air (OTA) Model Synchronization**: Seamlessly checks and downloads updated model binaries and word bank definitions when online.
- **Device-Local Translation History & Settings**: Persistent local history storage (up to 200 entries), voice customization, and theme preferences.

## Run Locally

1. Open `motionspeak-mobile` in Android Studio (Ladybug / Hedgehog, SDK 34).
2. Set the debug `BASE_URL` in `app/build.gradle.kts` to your reachable backend API host (e.g., `http://10.0.2.2:3000/api/` for emulator or `http://<LAN_IP>:3000/api/` for physical device).
3. Build and run the `app` configuration.

From the terminal:

```powershell
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

## Main Source Architecture

- `MainActivity.kt` — CameraX pipeline, gesture buffer, tone capture, and floating controls
- `PredictionService.kt` — On-device TFLite model inference for words and fingerspelled letters
- `HandLandmarkHelper.kt` — MediaPipe hand landmark extraction
- `EmotionClassifier.kt` / `ToneMapper.kt` — Facial expression detection and tone inference
- `SiriDetectionMeterView.kt` — Animated gradient indicator and gesture buffer meter
- `WordBankActivity.kt` & `WordDetailActivity.kt` — Word catalog, video playback, and category management
- `ModelUpdateManager.kt` — OTA model check, SHA-256 validation, and hot-swap
- `TranslationHistoryActivity.kt` — Local translation history records
- `SosActivity.kt` / `SosHelper.kt` — Emergency SOS alarm system
- `SettingsActivity.kt` — User preferences, speech volume, voice options, and onboarding replay

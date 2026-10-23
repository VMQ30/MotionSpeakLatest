<div align="center">

<img src="motionspeak-mobile/app/src/main/ic_launcher-playstore.png" alt="MotionSpeak logo" width="160">

# MotionSpeak

**An Offline-First Filipino Sign Language (FSL) Translator Mobile Application with Facial Emotion & Tone Recognition**

Translate Filipino Sign Language gestures and facial expressions into spoken and written words, entirely on-device.

[![Android](https://img.shields.io/badge/Android-7.0%2B-13306B?style=flat-square&logo=android&logoColor=white&labelColor=0F1B33)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-13306B?style=flat-square&logo=kotlin&logoColor=white&labelColor=0F1B33)](https://kotlinlang.org/)
[![MediaPipe](https://img.shields.io/badge/MediaPipe-Vision%20%26%20Pose-13306B?style=flat-square&logo=google&logoColor=white&labelColor=0F1B33)](https://developers.google.com/mediapipe)
[![TensorFlow Lite](https://img.shields.io/badge/TensorFlow%20Lite-On--Device%20ML-FF6F00?style=flat-square&logo=tensorflow&logoColor=white&labelColor=0F1B33)](https://www.tensorflow.org/lite)
[![Python](https://img.shields.io/badge/Python-FastAPI%20%26%20ML-1E3A8A?style=flat-square&logo=python&logoColor=white&labelColor=0F1B33)](https://fastapi.tiangolo.com/)

[Key Features](#-key-features) · [System Architecture](#-system-architecture) · [Installation and Setup](#-installation-and-setup) · [Usage Guidelines](#-usage-guidelines) · [Project Structure](#-project-structure)

</div>

---

## 📘 Project Overview

**MotionSpeak** is an assistive communication system bridging the gap between individuals who sign in Filipino Sign Language (FSL) and the hearing/non-signing community. By pairing real-time camera computer vision with on-device machine learning, MotionSpeak translates both hand gestures and facial expressions into clear text and natural synthesized speech in real time.

### Why MotionSpeak?
- **100% On-Device Translation**: Inference executes locally via TensorFlow Lite and Google MediaPipe. No internet connection is needed to translate signs, ensuring real-time response times, complete privacy, and reliability anywhere.
- **Emotion & Tone Awareness**: Sign language is multi-modal. MotionSpeak analyzes facial landmarks to classify emotional nuances (Neutral, Happy, Questioning, Surprised) and maps them to contextual speech inflection.
- **Offline Word Bank**: Searchable FSL catalog organized by categories, complete with demonstration videos and local favorites.
- **LLM Sentence Synthesis Ready**: Built-in architecture designed to assemble raw sequential sign glosses and emotional tone into fluent, grammatically cohesive sentences.

---

## ✨ Key Features

### 📱 Mobile Application (`motionspeak-mobile/`)
- **Real-Time Translation Modes**:
  - **Live Mode**: Continuous sign recognition with dynamic gesture buffering and visual progress indicators.
  - **Tap Mode**: On-demand gesture capture for deliberate, single-sign recognition.
  - **Words & Letters**: Dedicated models for full sign vocabulary and fingerspelled alphabet.
- **Facial Emotion Recognition**: Multi-point facial expression detector running alongside hand tracking to detect expressiveness and tone.
- **Interactive UI with Siri Glow Meter**: Fluid, animated gradient progress meter indicating gesture buffer fullness and recognition confidence.
- **Floating Controls**: Quick-action buttons for flipping between front and rear cameras, copying output to clipboard, repeating speech, and clearing the message stream.
- **Offline Word Bank & Video Demonstrations**: Browse sign categories, search vocabulary, save favorites, and download demonstration videos for offline study.
- **Emergency SOS Alert**: Rapid 2-second press-and-hold audible alarm and visual prompt for immediate assistance.
- **Translation History**: Device-local log of recent translations with management and export capabilities.

### 🧠 ML Service (`motionspeak-ml/`)
- **Landmark Extraction**: Automated pipelines using MediaPipe Holistic/Hands/Pose to extract 3D spatial coordinate sequences.
- **Model Architectures**: Deep learning sequence classification models (LSTM / GRU / Bidirectional) optimized and quantized for mobile TFLite runtime.
- **Facial Expression Pipeline**: Real-time facial action unit analysis and emotion classification.

---

## 🏛️ System Architecture

```text
 ┌────────────────────────────────────────────────────────┐
 │                   MotionSpeak Mobile                   │
 │  (Kotlin · CameraX · MediaPipe · TensorFlow Lite)       │
 └───────────────────────────┬────────────────────────────┘
                             │
                      On-Device Inference
                     (No Network Required)
                             │
                             ▼
                     Real-Time Translation
                     Text & Spoken Audio
                     + Emotion & Tone
                             ▲
                             │
                   Models & Landmark Pipeline
                             │
 ┌───────────────────────────┴────────────────────────────┐
 │                       ML Service                       │
 │                   (Python · FastAPI)                   │
 └────────────────────────────────────────────────────────┘
```

---

## 🎨 Design System & Theme

MotionSpeak features a high-contrast, accessible design palette tailored for readability and visual clarity:

| Swatch | Role | Hex | Purpose |
|---|---|---|---|
| ![](https://img.shields.io/badge/-%20%20%20%20-13306B?style=flat-square) | **Brand Navy** | `#13306B` | Primary action buttons, mobile headers, and core identity |
| ![](https://img.shields.io/badge/-%20%20%20%20-1E3A8A?style=flat-square) | **Secondary Navy** | `#1E3A8A` | Navigation, links, and secondary accents |
| ![](https://img.shields.io/badge/-%20%20%20%20-EEF3FC?style=flat-square) | **Light Tint** | `#EEF3FC` | Card surfaces, container tints, and highlighted sections |
| ![](https://img.shields.io/badge/-%20%20%20%20-0F1B33?style=flat-square) | **Ink Text** | `#0F1B33` | Primary typography and icon color |
| ![](https://img.shields.io/badge/-%20%20%20%20-0C1430?style=flat-square) | **Tonal Dark** | `#0C1430` | Dark mode background and camera drawer surfaces |

---

## ⚙️ Installation and Setup

### Prerequisites

| Component | Minimum Version | Required For |
|---|---|---|
| **Android Studio** | Ladybug / Hedgehog (SDK 34) | Mobile app (`minSdk 24`, Android 7.0+) |
| **Python** | 3.10 | ML Service |

---

### 1. Clone the Repository

```bash
git clone https://github.com/VMQ30/MotionSpeakLatest.git motionspeak
cd motionspeak
```

---

### 2. Mobile Application

1. Launch **Android Studio** and select **Open**, pointing to `motionspeak-mobile/`.
2. Ensure Android SDK platform 34 and Build Tools are installed.
3. Build and install on an emulator or physical device:
   ```powershell
   cd motionspeak-mobile
   .\gradlew.bat assembleDebug
   ```

---

### 3. Machine Learning Service

```bash
cd motionspeak-ml
python -m venv venv

# Windows:
venv\Scripts\activate
# macOS/Linux:
# source venv/bin/activate

pip install -r requirements.txt
cp .env.example .env
```

Download the required MediaPipe model assets into `motionspeak-ml/app/models/` following [`motionspeak-ml/app/models/README.md`](motionspeak-ml/app/models/README.md).

Start the FastAPI service:
```bash
uvicorn app.main:app --reload --port 8000 --host 0.0.0.0
```

---

## 🚀 Usage Guidelines

### Running Locally

```bash
# Terminal 1: ML Pipeline & Training Service
cd motionspeak-ml && venv\Scripts\activate && uvicorn app.main:app --reload --port 8000

# Terminal 2: Android App Build / Run
cd motionspeak-mobile && .\gradlew.bat installDebug
```

---

## 📂 Project Structure

```text
MotionSpeakLatest/
├── motionspeak-mobile/          # Native Android Application (Kotlin)
│   ├── app/src/main/kotlin/     # CameraX, TFLite Predictor, MediaPipe, UI Activities
│   ├── app/src/main/res/        # Layouts, vector drawables, themes, strings
│   ├── model_file_v1/           # Default offline TFLite model, labels, and metadata
│   └── tools/                   # App icon and asset generation utilities
├── motionspeak-ml/              # Machine Learning Engine (Python + FastAPI)
│   ├── app/api/                 # Training and extraction endpoints
│   ├── app/services/            # MediaPipe landmark extractors, TFLite exporters
│   └── tools/                   # Facial expression detection & dataset tooling
└── docs-internal/               # Architectural specs, diagrams, and sprint plans
```

---

## 🧪 Testing

```bash
# Android unit tests
cd motionspeak-mobile
.\gradlew.bat testDebugUnitTest

# ML service unit tests
cd motionspeak-ml
pytest
```

---

<div align="center">

<sub>MotionSpeak · Filipino Sign Language translation for everyday communication</sub>

</div>

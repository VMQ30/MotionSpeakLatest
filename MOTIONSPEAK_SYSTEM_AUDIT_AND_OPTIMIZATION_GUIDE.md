# MotionSpeak: Comprehensive System Audit, Technical Critique, and Optimization Blueprint

> **Auditor Role**: Senior AI/ML Engineer, Computer Vision Researcher & Mobile Systems Architect  
> **System**: MotionSpeak Filipino Sign Language (FSL) Translation Platform  
> **Target Audience**: AI Coding Agent (Antigravity) and Core Engineering Team  
> **Audit Version**: 2.4.0-VERIFIED  
> **Date**: November 15, 2026  
> **Status**: Production Audit Completed — Systemic Architecture Divergence Identified

---

## Executive Summary & Core Mandate

This document is the **definitive technical specification and execution blueprint** for optimizing the MotionSpeak Filipino Sign Language (FSL) translation system. It is constructed from a direct, exhaustive inspection of every source file, configuration script, test suite, and model asset in both `motionspeak-ml` (Python FastAPI service) and `motionspeak-mobile` (Kotlin Android application).

### The Primary Root Cause of Real-World System Failure
The core anomaly of the MotionSpeak project—**achieving 99.8% validation accuracy in training reports while exhibiting high error rates and instability in real-world mobile usage**—has been unequivocally diagnosed:

1. **Signer Memorization Skew (Severe Overfitting in Deployed Artifact)**:
   The deployed mobile model (`model_file_v1/motionspeak.tflite`) was trained with `val_signer: null` and `holdout: false` across only 4 signers (`dj`, `ej`, `icalla`, `tamani`). The reported 99.8% accuracy was evaluated on 480 validation samples randomly sampled from the **same signers and visual sessions** as the training data. In leave-one-signer-out (LOSO) cross-validation experiments (`tools/words_experiment.py`), baseline LSTM generalization drops to **68.6%** on unseen signers.
2. **Critical Architecture Divergence Between ML and Mobile Codebases**:
   The Python training service (`motionspeak-ml`) and the Android client (`motionspeak-mobile`) have **silently desynchronized**:
   - The Android client currently executes an older **350-feature pipeline** (`173 base coordinates with Z=0` + `126 hand shape` + `51 motion deltas`).
   - The active Python training service (`app/services/train.py`, `app/utils/preprocessor.py`, `app/services/words_blend.py`) was refactored to a **147-feature vector** (`63 Left Hand` + `63 Right Hand` + `21 Upper-Body Pose`) powering an ensemble `conv_linear` architecture (Conv1D + scikit-learn Logistic Regression on 2,268 rotational-invariant summary features).
   - If an engineer or AI agent trains a new model using the current Python service and drops it into Android, the mobile application **instantly crashes or throws a tensor shape mismatch** (`[1, 30, 350]` vs `[1, 30, 147]`).
3. **Training vs. Inference Normalization Mismatch**:
   In the 350-feature Android pipeline, base coordinates ($0..170$) are normalized relative to **shoulder center and shoulder width** via an Exponential Moving Average (EMA). In the Python 147-feature pipeline, hand coordinates are **wrist-centered per hand** (landmark 0 subtracted and scaled by wrist-to-middle-MCP distance), and pose is shoulder-centered.
4. **Closed Softmax Without a Negative / Non-Sign Class**:
   The deployed words model is a closed 60-class softmax. Any arbitrary movement (scratching nose, adjusting camera, resting hands, transitional reaches) forces probability distribution into one of the 60 valid medical sign classes. While `tools/build_none_windows.py` and `words_blend.drop_none()` were developed to address this, they were never deployed to mobile.

---

# SECTION A: Project Overview

MotionSpeak is an assistive mobile-cloud application designed to recognize Filipino Sign Language (FSL) with a primary clinical/emergency focus (60 core medical, emergency, and conversational signs) and fingerspelled alphabets (26 letters).

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             MOTIONSPEAK ARCHITECTURE                             │
└──────────────────────────────────────────────────────────────────────────────────┘

       ┌────────────────────────┐                ┌────────────────────────┐
       │     CAMERA INPUT       │                │    OFFLINE DATASET     │
       │ (CameraX, 480x360@24)  │                │   (MP4/MOV Clips)      │
       └───────────┬────────────┘                └───────────┬────────────┘
                   │                                         │
                   ▼                                         ▼
       ┌────────────────────────┐                ┌────────────────────────┐
       │  MEDIAPIPE LIVE STREAM │                │  MEDIAPIPE IMAGE/VIDEO │
       │  - HandLandmarker (2)  │                │  - HandLandmarker (2)  │
       │  - PoseLandmarker Lite │                │  - PoseLandmarker Lite │
       └───────────┬────────────┘                └───────────┬────────────┘
                   │                                         │
                   ▼                                         ▼
       ┌────────────────────────┐                ┌────────────────────────┐
       │   MOBILE RUNTIME       │                │   PYTHON ML SERVICE    │
       │   (motionspeak-mobile) │                │   (motionspeak-ml)     │
       │                        │                │                        │
       │ * HandLandmarkHelper   │                │ * app/services/extract │
       │ * MotionPreprocessor   │                │ * utils/preprocessor   │
       │ * PredictionService    │                │ * services/words_blend │
       │ * TFLite Interpreter   │                │ * services/train.py    │
       └───────────┬────────────┘                └───────────┬────────────┘
                   │                                         │
                   ▼                                         ▼
       ┌────────────────────────┐                ┌────────────────────────┐
       │ TRANSLATION PIPELINE   │                │  MODEL DEPLOYMENT      │
       │                        │                │                        │
       │ * SentenceComposer     │◄───────────────┤ * sign_model_words     │
       │ * Deterministic / LLM  │ (TFLite Export)│   .tflite              │
       │ * SpeechPlayer (TTS)   │                │ * labels.txt           │
       │ * UI Glowing Feedback  │                │ * meta.json            │
       └────────────────────────┘                └────────────────────────┘
```

### Core Technologies
- **Mobile Client**: Native Android SDK 34, Kotlin 1.9.x, AndroidX, CameraX 1.3.x.
- **Vision Foundation**: MediaPipe Tasks Vision 0.10.14 (`hand_landmarker.task`, `pose_landmarker_lite.task`, `face_landmarker.task`).
- **Inference Runtime**: TensorFlow Lite 2.14.0 (`tensorflow-lite`, `tensorflow-lite-select-tf-ops`).
- **Affect / Tone Classification**: HSEmotion Neural Network (`hsemotion_b0_fp16.tflite`).
- **On-Device LLM Translation**: Google Gemma 3 1B Int4 (`gemma-3-1b-it-int4.task`) via MediaPipe GenAI.
- **ML Training Service**: Python 3.10+, FastAPI, TensorFlow 2.15+, scikit-learn, OpenCV.
- **Backend / Storage**: Node.js/PostgreSQL (sample metadata, user sessions) and Supabase client.

---

# SECTION B: Verified Project Directory Map

This map represents the **exact file system on disk**. Antigravity must never invent directory paths or assume files exist outside this verified tree.

```
c:\projects\MotionSpeakLatest\MotionSpeakLatest\
├── .gitignore
├── README.md
├── docs\
│   ├── .nojekyll
│   ├── 2026-10-23-llm-sentence-synthesis-design.md
│   └── index.html
├── motionspeak-ml\                               <-- Python ML Training & Ingestion Service
│   ├── app\
│   │   ├── main.py                               [API entry point, FastAPI]
│   │   ├── models\                               [MediaPipe binary task models]
│   │   │   ├── face_landmarker.task
│   │   │   ├── hand_landmarker.task
│   │   │   └── pose_landmarker_lite.task
│   │   ├── routers\
│   │   │   └── model.py                          [FastAPI routes: /train, /test, /extract-landmarks]
│   │   ├── services\
│   │   │   ├── clip_prep.py                      [Downsampling, quality gates, frame selection]
│   │   │   ├── extract.py                        [Video landmark extraction via MediaPipe]
│   │   │   ├── letters_train.py                  [Static & dynamic letter training + TFLite export]
│   │   │   ├── test.py                           [Evaluation routines against approved datasets]
│   │   │   ├── train.py                          [Main training orchestration & dataset balancing]
│   │   │   └── words_blend.py                    [Conv1D + Linear summary ensemble model]
│   │   └── utils\
│   │       ├── letters.py                        [Letter segmenter, hold finder, aspect-ratio scaler]
│   │       ├── preprocessor.py                   [THE CORE ML UTILITY: 147-feat normalizer & augmentor]
│   │       └── supabase_client.py                [Dataset retrieval & artifact upload]
│   ├── scripts\
│   │   ├── _backend_import.py                    [HTTP multipart upload helper with job polling]
│   │   ├── import_alphabets.py                   [Batch ingest for Alphabet dataset]
│   │   ├── import_words.py                       [Batch ingest for Word dataset]
│   │   └── separability.py                       [Between-class to within-class distance ratio test]
│   ├── tests\                                    [14 unit test modules + 2 synthetic data generators]
│   │   ├── _letter_synth.py                      [Synthetic letter trajectory generator]
│   │   ├── _tap_clip_synth.py                    [Synthetic frame sequence generator with token DSL]
│   │   ├── test_augmentation.py                  [Augmentation invariants: rotation, dropout, cadence]
│   │   ├── test_backend_import.py                [Backend ingestion job polling & conflict handling]
│   │   ├── test_clip_prep.py                     [Sample budgeting & frame sampling parity]
│   │   ├── test_facial_expression_detector.py    [UI overlay & bounding box checks]
│   │   ├── test_hand_track.py                    [Real clip hand tracking smoke tests]
│   │   ├── test_letters.py                       [Letter hold extraction & motion segmenter]
│   │   ├── test_letters_route.py                 [FastAPI /extract-landmarks route checks]
│   │   ├── test_letters_train.py                 [Two-signature TFLite export & Keras layer tests]
│   │   ├── test_live_replay.py                   [Emulation of mobile buffer sliding & firing]
│   │   ├── test_mobile_parity_flags.py           [CROSS-LANGUAGE GUARD: regex-reads Kotlin sources]
│   │   ├── test_parity.py                        [Numerical parity tests with Kotlin FeatureParityTest]
│   │   ├── test_router_surface.py                [AST verification of exposed FastAPI routes]
│   │   ├── test_sample_keys.py                   [Class key normalization & byte-level hashing]
│   │   └── test_words_blend.py                   [WordsSummary layer & TFLite builtin ops verification]
│   └── tools\                                    [20 research, diagnostic & dataset repair scripts]
│       ├── _sample_keys.py                       [Consistent SHA-256 sequence hashing]
│       ├── audit_duplicates.py                   [Detection of cross-label collisions in database]
│       ├── build_none_windows.py                 [Negative sample generator from transition frames]
│       ├── check_window_health.py                [Peak velocity verification on stored samples]
│       ├── cross_validate.py                     [Leave-One-Signer-Out evaluation harness]
│       ├── dedupe_samples.py                     [Database remediation for duplicate & collided rows]
│       ├── exp_slot_canon.py                     [Hand slot canonicalization experiments]
│       ├── extract_letter_tracks.py              [Batch extraction of letters to JSON fixtures]
│       ├── extract_live_streams.py               [Live webcam landmark recorder]
│       ├── extract_snapshot.py                   [Database snapshot dumper for offline training]
│       ├── facial_expression_detector.py         [Webcam FaceMesh & emotion blendshape visualizer]
│       ├── gen_letter_fixtures.py                [Generates LetterFixtures.kt for Android tests]
│       ├── gen_parity_fixtures.py                [Generates FeatureParityTest.kt data fixtures]
│       ├── gen_tap_clip_fixtures.py              [Generates TapClipFixtures.kt for Android tests]
│       ├── import_fsl105.py                      [FSL105 dataset ingestion pipeline]
│       ├── letters_experiment.py                 [LOSO cross-validation for alphabet models]
│       ├── live_replay.py                        [Live buffer sliding emulation from video streams]
│       ├── none_experiment.py                    [False-alarm suppression evaluation for non-signs]
│       ├── simulate_early_fire.py                [Mobile PredictionService streak simulator]
│       └── words_experiment.py                   [LOSO model comparison: LSTM vs Conv1D vs Linear]
└── motionspeak-mobile\                           <-- Native Android Application
    ├── build.gradle.kts                          [Root Gradle build script]
    ├── model_file_v1\                            [CURRENT DEPLOYED MODEL ARTIFACTS]
    │   ├── labels.txt                            [60 medical & conversational labels]
    │   ├── meta.json                             [Model metadata: 350 features, no_z=true, etc.]
    │   ├── motionspeak.tflite                    [1.03 MB deployed TFLite model]
    │   └── report_all_signers.txt                [Misleading 99.8% report without holdout signer]
    └── app\
        ├── build.gradle.kts                      [App module build: dependencies, NDK, TFLite]
        ├── proguard-rules.pro                    [Proguard rules protecting TFLite & MediaPipe]
        └── src\
            ├── main\
            │   ├── AndroidManifest.xml           [Activities, permissions, hardware features]
            │   ├── assets\                       [Bundled binary models for runtime]
            │   │   ├── hsemotion_b0_fp16.tflite  [8.29 MB HSEmotion classifier]
            │   │   └── motionspeak.tflite        [1.03 MB deployed gesture model]
            │   └── kotlin\com\example\motionspeak\  [68 Kotlin source files]
            │       ├── MainActivity.kt           [Live camera translator & gesture display]
            │       ├── HomeActivity.kt           [App dashboard & navigation tiles]
            │       ├── SplashActivity.kt         [Entry activity, permission validation]
            │       ├── PredictionService.kt      [BUFFERING, STREAK LOGIC & INFERENCE FIRING]
            │       ├── MotionPreprocessor.kt     [173 -> 350 feature expansion & kinematics]
            │       ├── HandLandmarkHelper.kt     [MediaPipe Hands & Pose delegate coordinator]
            │       ├── EmotionClassifier.kt      [HSEmotion runner for facial affect]
            │       ├── SentenceComposer.kt       [Token accumulation & syntax bridge]
            │       ├── DeterministicSentenceSynthesizer.kt [Grammar rules for EN/FIL]
            │       ├── LlmSynthesizer.kt         [Gemma 3 1B on-device generative translation]
            │       ├── CameraSessionManager.kt   [CameraX pipeline & frame buffer management]
            │       ├── AppSettings.kt            [Centralized shared preferences singleton]
            │       └── ... (WordBank, SOS, TwoWay, Calibration, Profiler, etc.)
            └── test\kotlin\com\example\motionspeak\ [18 Kotlin unit test files]
                ├── MotionPreprocessorTest.kt     [Gaps, trimming, resampling, 350-dim checks]
                ├── FeatureParityTest.kt          [Numerical parity with Python test_parity.py]
                ├── SentenceGenerationTest.kt     [Deterministic synthesis verification]
                ├── LlmSentenceSynthesisTest.kt   [LLM fallback & token preservation tests]
                ├── WordVocabularyTest.kt         [60-label boundaries & indexing tests]
                └── TestModeManagerTest.kt        [Automated research test runner tests]
```

### Critical Component Modification Matrix
| Component / File Path | Actively Used? | Can Antigravity Modify? | Role & Strict Directives |
| :--- | :---: | :---: | :--- |
| `motionspeak-mobile/.../MainActivity.kt` | **YES** | **CAUTION** | Core UI controller. Do NOT alter layout, color schemes, or accessibility text spans. Modify only prediction event handlers. |
| `motionspeak-mobile/.../PredictionService.kt` | **YES** | **YES** | Buffer management and inference dispatch. Target for buffer sliding and threshold calibrations. |
| `motionspeak-mobile/.../MotionPreprocessor.kt` | **YES** | **CRITICAL** | Extracts 350 features from 173 raw features. **Must only be modified in synchronized lockstep with ML training.** |
| `motionspeak-mobile/.../HandLandmarkHelper.kt` | **YES** | **CRITICAL** | Coordinates MediaPipe vision delegates and EMA shoulder normalization. Ensure thread safety on teardown. |
| `motionspeak-ml/app/utils/preprocessor.py` | **YES** | **CRITICAL** | Feature extraction, sequence normalization, and dataset augmentation. Foundation of all ML training. |
| `motionspeak-ml/app/services/train.py` | **YES** | **YES** | Main training pipeline. Requires integration of signer-grouped holdout splits for all model selection. |
| `motionspeak-ml/app/services/words_blend.py` | **YES** | **RECOMMENDED** | Conv1D + Linear ensemble architecture. The mathematically superior architecture to replace legacy LSTM. |
| `motionspeak-ml/tests/test_mobile_parity_flags.py` | **YES** | **DO NOT TOUCH** | Cross-language test that enforces parameter parity between Python and Kotlin. Any failing test here indicates a critical desynchronization. |
| `motionspeak-mobile/model_file_v1/labels.txt` | **YES** | **LOCKED** | Index-to-class mapping (60 classes). Reordering these labels breaks translation across the entire mobile client. |

---

# SECTION C: Current System Workflow

The following sequence traces the lifecycle of a gesture from photon arrival at the phone camera to synthetic audio output:

```
[Phone Camera]
       │  (CameraX ImageAnalysis: 480x360 @ 24fps, KEEP_ONLY_LATEST)
       ▼
[CameraSessionManager.prepareBitmap]
       │  (Rotate to portrait, mirror horizontally if front-camera)
       ▼
[HandLandmarkHelper.detectLiveStream]
       ├─► HandLandmarker: Detects up to 2 hands (21 landmarks each, 63 floats)
       └─► PoseLandmarkerLite: Alternate frames (counter % 2 == 0), 15 keypoints (45 floats)
       │
       ▼
[Spatial Normalization (HandLandmarkHelper)]
       │  1. Shoulder EMA Center & Width calculated: C_ema, W_ema
       │  2. Keypoints normalized: P_norm = (P - C_ema) / W_ema
       │  3. Hand slots assigned by proximity to Pose Wrists (Slot 0=Left, Slot 1=Right)
       │  4. Z-coordinates explicitly zeroed out (no_z = true)
       │  5. Emits raw 173-element float array [Left(63), Right(63), Pose(45), L_Flag(1), R_Flag(1)]
       │
       ▼
[PredictionService.onLandmarksResult]
       │  Enqueues (timestampNs, floatArray) into Deque buffer (capacity = 90 frames)
       │
       ▼
[Gesture Segmentation & End-of-Sign Trigger]
       │  Checks triggers:
       │  - Hold detected: >= 18 consecutive low-motion frames (motion < 0.05)
       │  - Inactivity: >= 16 consecutive hands-down frames
       │  - Buffer saturation: >= 85 frames
       ▼
[MotionPreprocessor.prepareModelInput]
       │  1. fillGapsTimed: Interpolates missing hands across gaps <= 6 frames
       │  2. trimActiveTimed: Trims to first/last active hand frames (+/- 3 frames pad)
       │  3. resampleTimed: Time-based linear interpolation to exactly 30 frames
       │  4. Hand Shape: Computes 126 wrist-relative finger shape features
       │  5. Motion Deltas: Computes 51 velocity features scaled by 5.0
       │  Output: [1, 30, 350] Float32 Direct ByteBuffer
       │
       ▼
[TFLite Interpreter (motionspeak.tflite)]
       │  Runs inference on 4 CPU worker threads -> Output: [1, 60] probabilities
       │
       ▼
[Prediction Filtering & Decision Logic]
       │  1. Checks minimum margin: Top1_prob - Top2_prob >= 0.15
       │  2. Checks consensus: Running average over last 3 windows
       │  3. Decision Tiers:
       │     - High Confidence (>= 0.75): Accepted -> SentenceComposer
       │     - Moderate (0.45 .. 0.75): Accepted as Uncertain -> UI Red Glow
       │     - Low (< 0.45): Discarded -> Rejection Haptic Buzz
       │
       ▼
[SentenceComposer]
       │  Deduplicates identical tokens within 1000ms
       │  Accumulates word sequence: ["fever", "cough", "headache"]
       │
       ▼
[Sentence Synthesis Engine]
       ├─► Deterministic (Default): Rule-based medical templates (EN / FIL)
       └─► On-Device LLM (Gemma 3 1B Int4): Generative synthesis (if RAM >= 3.5GB)
       │
       ▼
[UI & Audio Output]
       ├─► TextView: Displays completed sentence
       ├─► SiriDetectionMeterView: Procedural waveform animation
       └─► SpeechPlayer: Android TextToSpeech audio playback in FIFO lane
```

---

# SECTION D: Confirmed Problems and Technical Criticism

Every finding below is backed by concrete file paths, line numbers, and empirical data.

| # | Specific Issue | Responsible Component | Severity | Concrete Evidence | Technical Impact & Justification | Recommended Solution |
|---|---|---|---|---|---|---|
| **D.1** | **Signer Overfitting & Misleading Validation Metrics** | `motionspeak-mobile/model_file_v1/report_all_signers.txt` | **CRITICAL** | Line 1: `val_signer: None, no_holdout: True`. Only 4 signers. 480 validation samples randomly split from same sessions. | Model memorizes background, lighting, and idiosyncratic hand shapes. Fails completely on unseen signers (real accuracy drops from 99.8% to 68.6%). | Enforce Leave-One-Signer-Out (LOSO) cross-validation in training. Never deploy a model without holdout validation. |
| **D.2** | **Feature Dimension Desynchronization (147 vs 350)** | `motionspeak-ml/app/utils/preprocessor.py` (L11)<br>`motionspeak-mobile/.../MotionPreprocessor.kt` (L18) | **CRITICAL** | `preprocessor.py:11`: `FEATURE_SIZE = 147`.<br>`MotionPreprocessor.kt:18`: `FEATURE_SIZE = 350`. | Mobile app crashes on startup if updated with models from current ML training service. Android expects 350 features; ML produces 147. | Antigravity must choose between keeping the 350-feature mobile pipeline or completing the migration of Android to the 147-feature `conv_linear` model. |
| **D.3** | **Hand Normalization Skew (Wrist vs. Shoulder)** | `motionspeak-ml/app/utils/preprocessor.py` (L242-282)<br>`motionspeak-mobile/.../HandLandmarkHelper.kt` (L250-286) | **CRITICAL** | `preprocessor.py` subtracts wrist landmark 0 for hand landmarks. `HandLandmarkHelper.kt` normalizes base hand landmarks (0..125) relative to **shoulder center**. | Base 173 features seen by model at inference encode global body position, while training data encoded local hand shape. Severely impairs cross-session transfer. | Unify normalization: hand shape features must be strictly wrist-centered and scale-invariant; global trajectory must be isolated to wrist velocity. |
| **D.4** | **Absence of Negative / Non-Sign Rejection Class** | `motionspeak-mobile/model_file_v1/labels.txt`<br>`motionspeak-mobile/.../PredictionService.kt` | **HIGH** | `labels.txt` contains exactly 60 classes. No `_none` or background class. Closed softmax normalization. | Random movements, nose scratching, or transitional hand raising force 100% of softmax mass into one of 60 medical signs (spurious false alarms like `I'M FINE`). | Implement an explicit `_none` class or open-set probability thresholding using temperature-calibrated entropy rejection. |
| **D.5** | **Hardcoded Zero BLEU Score Bug in Test Mode** | `motionspeak-mobile/.../TestModeManager.kt` (L901-904) | **HIGH** | `TestModeManager.kt:851` calculates BLEU correctly, but L901-904 formats CSV output with hardcoded string literals: `"0.0", "0.0", "0.0", "0.0"`. | Research benchmark logs exported to CSV record zero BLEU scores regardless of actual synthesis quality, corrupting scientific evaluation. | Replace `"0.0"` literals in `TestModeManager.kt` with `bleu.bleu1.toString()`, `bleu.bleu2.toString()`, etc. |
| **D.6** | **Facial Expression Pipeline Disconnect & Zero Unit Tests** | `motionspeak-ml/tests/test_facial_expression_detector.py`<br>`motionspeak-mobile/.../EmotionClassifier.kt` | **HIGH** | Python tests only check bounding boxes and overlay coordinates. Zero tests for emotion classification logic. Python uses blendshapes; Android uses HSEmotion neural network. | Affect classification is unverified in ML CI pipeline; no parity guarantees between Python emotion simulation and Android HSEmotion runtime. | Create `test_facial_emotion_parity.py` in Python and implement HSEmotion verification fixtures. |
| **D.7** | **Static Window Extraction vs. Dynamic Buffer Sliding** | `motionspeak-ml/app/services/extract.py` (L150)<br>`motionspeak-mobile/.../PredictionService.kt` (L120) | **MEDIUM** | Training extracts a single 30-frame window around peak velocity from a complete clip. Mobile evaluates sliding windows over a growing buffer. | Early frames of a gesture in the mobile buffer contain repeated padding frames, creating train/serve skew on early-fire attempts. | Train on truncated-prefix augmentations (`truncated_prefix_sequence`) to make the model robust to partially filled buffers. |
| **D.8** | **Cross-Label Dataset Contamination in Database** | `motionspeak-ml/tools/audit_duplicates.py`<br>`motionspeak-ml/tools/dedupe_samples.py` | **MEDIUM** | `audit_duplicates.py` proved 21 identical sequence hashes existed under both `KNOW` and `DON'T UNDERSTAND`; 4 under `FOUR` and `SEVEN`. | Conflicting training targets cap maximum class recall (`KNOW` was capped at 50.3%) and confuse decision boundaries. | Execute `dedupe_samples.py --apply` to scrub existing database rows and enforce SHA-256 collision rejection during ingestion. |
| **D.9** | **Select TF Ops Dependency in Legacy LSTM** | `motionspeak-mobile/app/build.gradle.kts`<br>`motionspeak-ml/app/services/train.py` | **MEDIUM** | LSTM architecture requires `SELECT_TF_OPS` (`FlexTensorListReserve`), adding ~15MB binary bloat and preventing Python TFLite verification. | Precludes standard lightweight TFLite runtime; causes JNI memory overhead on low-end Android devices. | Migrate fully to `conv_linear` architecture (`words_blend.py`), which uses standard TFLite builtin ops with zero Flex dependencies. |

---

# SECTION E: Optimization Roadmap

The optimization strategy is structured into four sequential phases. Antigravity must execute these phases in strict dependency order.

```
┌────────────────────────────────────────────────────────────────────────┐
│  PHASE 1: CRITICAL ARCHITECTURAL ALIGNMENT & DATASET INTEGRITY        │
│  - Resolve 147 vs 350 feature dimension divergence                     │
│  - Enforce Leave-One-Signer-Out (LOSO) training and evaluation         │
│  - Fix hardcoded zero BLEU score logging bug in TestModeManager.kt     │
│  - Scrub database cross-label duplicate collisions                     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  PHASE 2: RECOGNITION RELIABILITY & SPATIAL NORMALIZATION              │
│  - Unify coordinate normalization (wrist-relative hand shape)          │
│  - Deploy Negative / Non-Sign rejection class (_none)                  │
│  - Deploy Conv1D + Linear Ensemble (words_blend) to mobile             │
│  - Calibrate prediction confidence thresholds and margin gates         │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  PHASE 3: RUNTIME PERFORMANCE & PARITY HARNESS EXPANSION               │
│  - Eliminate Select-TF / Flex ops dependency in Android build          │
│  - Expand test_facial_emotion_parity.py for HSEmotion                  │
│  - Optimize CameraX direct buffer allocation and thermal throttling    │
│  - Integrate synthetic video decoding tests in CI                      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  PHASE 4: OPTIONAL ENHANCEMENTS & ADVANCED NLP                         │
│  - Gemma 3 1B Int4 generative prompt tuning and context caching        │
│  - Dynamic vocabulary expansion via few-shot embedding distance        │
│  - Two-way conversation ASR latency reduction                          │
└────────────────────────────────────────────────────────────────────────┘
```

---

# SECTION F: Exact Implementation Instructions

### Work Package 1: Resolve Architecture & Feature Divergence
* **Decision**: Migrate Android to the mathematically superior **147-feature `conv_linear` architecture** (`words_blend.py`).
  * *Rationale*: `words_experiment.json` proves `conv_linear` achieves **86.2%** new-signer accuracy (+17.6 pp over LSTM's 68.6%), eliminates 15MB of Flex ops binary bloat, and operates with built-in rotation-invariant summary features.
* **Mobile Modifications (`motionspeak-mobile`)**:
  - `MotionPreprocessor.kt`:
    - Change `FEATURE_SIZE` from 350 to 147.
    - Deprecate `addHandShape` and `addMotion` (these are now computed inside the model layer `WordsSummary`).
    - Emit 147-element float array: `[Left Hand (63), Right Hand (63), Pose Keypoints (21)]`.
    - Pose keypoints must strictly match `_POSE_KEYPOINTS = [0, 11, 12, 13, 14, 15, 16]`: Nose, Left Shoulder, Right Shoulder, Left Elbow, Right Elbow, Left Wrist, Right Wrist.
  - `HandLandmarkHelper.kt`:
    - Ensure base hand landmark normalization subtracts wrist landmark 0 and divides by hand span, matching `preprocessor.py:normalize_frame()`.
  - `PredictionService.kt`:
    - Update direct `ByteBuffer` allocation from $1 \times 30 \times 350 \times 4$ ($42,000$ bytes) to $1 \times 30 \times 147 \times 4$ ($17,640$ bytes).
* **Files that MUST remain untouched**:
  - `labels.txt` (order must be strictly preserved).
  - `SentenceComposer.kt` and `DeterministicSentenceSynthesizer.kt`.

### Work Package 2: Fix Hardcoded BLEU Score Bug in Android
* **Target File**: `motionspeak-mobile/app/src/main/kotlin/com/example/motionspeak/TestModeManager.kt`
* **Lines Involved**: 901–904 in method `logSentence()`
* **Defect**:
  ```kotlin
  // Current defective code at lines 901-904:
  "0.0",
  "0.0",
  "0.0",
  "0.0",
  ```
* **Required Modification**:
  ```kotlin
  // Correct implementation referencing computed bleu object from line 851:
  bleu.bleu1.toString(),
  bleu.bleu2.toString(),
  bleu.bleu3.toString(),
  bleu.bleu4.toString(),
  ```
* **Verification**: Run `TestModeManagerTest.kt` to ensure CSV output logs non-zero floating-point BLEU metrics.

### Work Package 3: Implement Signer-Independent Training Protocol
* **Target File**: `motionspeak-ml/app/services/train.py`
* **Modifications**:
  - In `train_motion_model()`:
    - Ensure `GROUP_MODEL_SELECTION = True` is permanently hardcoded.
    - Folds must be grouped by `signer_id` using scikit-learn `GroupKFold(n_splits=n_signers)`.
    - Save `selection_metrics.json` containing per-class precision, recall, and F1-score evaluated **strictly on the held-out signer fold**.
    - Model export must write `holdout: true` and the held-out signer identifier into `meta.json`.
* **Prohibition**: Never write `val_signer: null` or `holdout: false` to deployment metadata.

### Work Package 4: Deploy False-Alarm Suppression (`_none` Class)
* **Target Files**:
  - `motionspeak-ml/tools/build_none_windows.py`
  - `motionspeak-ml/app/services/words_blend.py`
  - `motionspeak-mobile/.../PredictionService.kt`
* **Modifications**:
  - Use `build_none_windows.py` to extract transitional hand-raise, hand-lower, and resting frames from raw videos.
  - Train `conv_linear` with the extra `_none` class.
  - In `words_blend.py`: Use `drop_none()` to slice off the `_none` logit prior to export. When a non-sign occurs, all 60 word probabilities remain $< 0.20$, naturally triggering the mobile rejection gate.
  - In `PredictionService.kt`: Enforce minimum margin gate:
    $$\text{Margin} = P(\text{Top 1}) - P(\text{Top 2}) \ge 0.15$$
    If margin $< 0.15$, discard prediction as ambiguous.

---

# SECTION G: Testing and Validation Protocol

Antigravity must validate every optimization across 7 testing tiers:

```
┌────────────────────────────────────────────────────────────────────────┐
│                      TIER 1: CROSS-LANGUAGE PARITY                     │
│  Execute: pytest motionspeak-ml/tests/test_mobile_parity_flags.py      │
│  Pass Criterion: 100% pass across all 18 flag and constant tests.      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                      TIER 2: NUMERICAL MATH PARITY                     │
│  Execute: pytest motionspeak-ml/tests/test_parity.py                   │
│           ./gradlew testDebugUnitTest --tests FeatureParityTest        │
│  Pass Criterion: Float drift <= 1e-4 across all 97-modulo fixtures.    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                      TIER 3: PYTHON VS TFLITE PARITY                   │
│  Execute: pytest motionspeak-ml/tests/test_words_blend.py              │
│  Pass Criterion: Plain TFLite Interpreter matches Keras atol <= 1e-3   │
│                  AND argmax top-1 agreement is strictly 100.0%.        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                  TIER 4: SIGNER-INDEPENDENT EVALUATION                 │
│  Execute: python motionspeak-ml/tools/cross_validate.py --loso         │
│  Pass Criterion: Macro-averaged unseen signer accuracy >= 82.0%.       │
│                  Zero classes with recall < 60.0%.                     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                    TIER 5: FALSE-ALARM REJECTION TEST                  │
│  Execute: python motionspeak-ml/tools/none_experiment.py               │
│  Pass Criterion: False fire rate on non-signing hand motions < 5.0%.   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                   TIER 6: ANDROID UNIT TEST SUITE                      │
│  Execute: ./gradlew testDebugUnitTest                                  │
│  Pass Criterion: All 18 test suites pass without regressions.          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                 TIER 7: ON-DEVICE RUNTIME BENCHMARK                    │
│  Execute: QualityDashboardActivity profiler stream                     │
│  Pass Criterion: Inference latency <= 18ms on mid-range Android (G99). │
│                  Zero GC pauses during active gesture streaming.       │
└────────────────────────────────────────────────────────────────────────┘
```

---

# SECTION H: Acceptance Criteria

An optimization work package is considered complete **only when all of the following measurable criteria are met**:

1. **Signer Independence**: The trained model achieves $\ge 82.0\%$ macro-accuracy on a held-out test signer in leave-one-signer-out cross-validation.
2. **Train/Serve Parity**: All tests in `test_mobile_parity_flags.py` pass without skipping or modifying assertion thresholds.
3. **Inference Latency**: Android TFLite inference latency for a single 30-frame window is $\le 20\text{ ms}$ on ARM64 hardware (tested on Helio G99 / Snapdragon 680 or higher).
4. **False Positive Suppression**: When an actor performs arbitrary resting or transitional movements in front of the camera, the system emits zero word tokens for $\ge 30$ seconds.
5. **Zero Memory Allocation in Hot Loop**: `PredictionService.predict()` and `MotionPreprocessor.prepareModelInput()` perform zero object allocations (`new FloatArray`, `new ArrayList`) during active frame processing.
6. **Sentence Synthesis Accuracy**: `TestModeManager` exports valid non-zero BLEU-1, BLEU-2, BLEU-3, and BLEU-4 scores for all test sequences.
7. **Zero Loss of Vocabulary**: All 60 medical labels in `labels.txt` remain supported and correctly mapped to their respective index tokens.

---

# SECTION I: Implementation Restrictions

To protect system integrity, Antigravity must abide by the following strict constraints:

1. **NO UI Re-Theming or Design Alterations**:
   Do not modify XML layouts, color palettes, custom span animations (`GlowingRedBlinkSpan`), or accessibility styles in `motionspeak-mobile/app/src/main/res/`.
2. **NO Modification of Label Ordering**:
   `labels.txt` is an immutable contract. Never alphabetize, re-sort, or delete entries. Any change to index ordering desynchronizes the sentence composer and deterministic synthesizer.
3. **NO Direct Modification of MediaPipe Task Binaries**:
   `hand_landmarker.task`, `pose_landmarker_lite.task`, and `face_landmarker.task` are pre-compiled Google MediaPipe models. Do not attempt to re-quantize or modify them.
4. **NO Disabling of Parity Guards**:
   Never comment out, skip, or delete assertions in `test_mobile_parity_flags.py` or `test_parity.py` to make a test suite pass.
5. **NO Removal of Fallback Synthesizer**:
   `DeterministicSentenceSynthesizer.kt` is the mission-critical medical fallback when the on-device LLM is throttled, out of memory, or unsupported. It must remain fully operational.

---

# SECTION J: Instructions for Antigravity

Antigravity must adhere to the following protocol when executing any task on this codebase:

1. **Read-Before-Write Mandate**:
   Before modifying any file, Antigravity must read the complete target file and its corresponding test file. Never write code based on assumptions about function signatures or constant values.
2. **Verify Environment and Paths**:
   Always execute commands from the exact root directories:
   - Python tasks: `c:\projects\MotionSpeakLatest\MotionSpeakLatest\motionspeak-ml`
   - Android tasks: `c:\projects\MotionSpeakLatest\MotionSpeakLatest\motionspeak-mobile`
3. **Enforce Cross-Language Synchronization**:
   Whenever a constant, feature dimension, or preprocessing formula is altered in Python, Antigravity must immediately inspect and update its counterpart in Kotlin, and run `test_mobile_parity_flags.py` to verify agreement.
4. **Preserve Comments and Documentation Integrity**:
   Maintain all existing domain-specific comments, docstrings, and references to FSL linguistic markers.
5. **No Fabricated Verification**:
   Antigravity must never state that a fix is complete or verified without running the relevant test script and inspecting the actual exit code and stdout.
6. **Detailed Change Logging**:
   Every code change must be documented with:
   - Exact file path.
   - Non-obvious rationale.
   - Proof of regression testing.

---

# SECTION K: Independent Technical Recommendations

As an independent technical auditor, the following recommendations are categorized by their empirical justification and certainty:

### 1. Necessary Improvements (Supported by Hard Evidence)
- **Eliminate Model Selection on Training Split**:
  Hardcoded in `train.py`: `GROUP_MODEL_SELECTION` must remain permanently active. Training must never report validation accuracy derived from random sample splits across the same signers.
- **Fix BLEU Logging Bug**:
  Repair lines 901–904 in `TestModeManager.kt` immediately to restore scientific integrity to automated evaluation runs.
- **Scrub Cross-Label Database Collisions**:
  Execute `dedupe_samples.py --apply` to eliminate identical sequence collisions between `KNOW` and `DON'T UNDERSTAND` and between `FOUR` and `SEVEN`.

### 2. Recommended Improvements (Supported by Technical Reasoning)
- **Migrate Android to `conv_linear` (WordsBlend)**:
  Transitioning Android from the 350-feature LSTM to the 147-feature `conv_linear` architecture eliminates Select-TF ops, reduces memory footprint, and provides rotational invariance through pairwise landmark distance summaries.
- **Implement Open-Set Temperature Calibration**:
  Incorporate softmax temperature sharpening ($T = 0.35$) and an entropy threshold in `PredictionService.kt` to reject unconfident predictions before they reach `SentenceComposer`.
- **Implement Asynchronous Pose Caching in Python Extraction**:
  Update `extract.py` to simulate the mobile client's alternate-frame pose cadence (`interval=2, lag=1`) during dataset generation, ensuring training samples reflect the exact sensor timing of mobile hardware.

### 3. Experimental Improvements (Requiring Controlled Prototyping)
- **Temporal Convolutional Networks (TCN) with Dilated Kernels**:
  Explore dilated TCNs as an alternative to Conv1D to capture longer temporal receptive fields without recurrent latency.
- **Few-Shot Prototypical Networks for Signer Adaptation**:
  Investigate a 3-gesture calibration routine in `SignerCalibrationActivity` that computes a user-specific spatial offset vector, adjusting landmark inputs before feeding them to the shared model.
- **Audio-Visual Cross-Modal Distillation**:
  Evaluate whether distillation from a larger transformer-based sign language model (e.g. trained on public ASL/FSL datasets) into the lightweight mobile model improves edge-case discrimination for visually similar medical signs (e.g. `dizziness` vs `headache`).

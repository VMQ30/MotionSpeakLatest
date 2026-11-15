# MotionSpeak: Prioritized Optimization Checklist for Antigravity

This checklist provides a step-by-step, actionable roadmap for AI coding agents (Antigravity) and engineers. **Do not begin a task until its prerequisites are completed.**

---

## Phase 1: Critical Architectural Alignment & Integrity (Must Do First)

- [ ] **Task 1.1: Fix BLEU Score Logging Bug in Android**
  - **Target File**: `motionspeak-mobile/app/src/main/kotlin/com/example/motionspeak/TestModeManager.kt`
  - **Action**: Replace `"0.0", "0.0", "0.0", "0.0"` at lines 901–904 with `bleu.bleu1.toString()`, `bleu.bleu2.toString()`, `bleu.bleu3.toString()`, `bleu.bleu4.toString()`.
  - **Verification**: Run `./gradlew testDebugUnitTest --tests TestModeManagerTest`.
  - **Prerequisite**: None.

- [ ] **Task 1.2: Scrub Database Cross-Label Duplicate Collisions**
  - **Target File**: `motionspeak-ml/tools/dedupe_samples.py`
  - **Action**: Run `python tools/dedupe_samples.py --backup backup_samples.json --apply` to delete contested sample IDs between `KNOW` and `DON'T UNDERSTAND` and between `FOUR` and `SEVEN`.
  - **Verification**: Run `python tools/audit_duplicates.py` and confirm 0 collisions reported.
  - **Prerequisite**: Database connection (`PG_URI`).

- [ ] **Task 1.3: Enforce Leave-One-Signer-Out (LOSO) Training Protocol**
  - **Target File**: `motionspeak-ml/app/services/train.py`
  - **Action**: Hardcode `GROUP_MODEL_SELECTION = True`. Ensure validation data is 100% held-out real data from an unseen signer. Write `holdout: true` and signer ID to `meta.json`.
  - **Verification**: Run training and assert `selection_metrics.json` records holdout evaluation.
  - **Prerequisite**: Task 1.2.

- [ ] **Task 1.4: Decide Feature Dimension Standardization (147 vs 350)**
  - **Recommendation**: Standardize on **147 features** (`conv_linear` architecture from `words_blend.py`).
  - **Action**: Align `motionspeak-mobile/.../MotionPreprocessor.kt` (`FEATURE_SIZE = 147`) and update `PredictionService.kt` byte buffer to $1 \times 30 \times 147 \times 4$ ($17,640$ bytes).
  - **Verification**: Run `pytest motionspeak-ml/tests/test_mobile_parity_flags.py`.
  - **Prerequisite**: Tasks 1.1, 1.3.

---

## Phase 2: Recognition Reliability & Spatial Normalization

- [ ] **Task 2.1: Synchronize Hand Coordinate Normalization**
  - **Target Files**:
    - `motionspeak-mobile/.../HandLandmarkHelper.kt`
    - `motionspeak-ml/app/utils/preprocessor.py`
  - **Action**: In `HandLandmarkHelper.kt`, subtract wrist landmark 0 per hand and divide by hand span, matching `preprocessor.py:normalize_frame()`.
  - **Verification**: Run `FeatureParityTest.kt` in Android and `test_parity.py` in Python; ensure float drift $\le 10^{-4}$.
  - **Prerequisite**: Task 1.4.

- [ ] **Task 2.2: Train and Export `conv_linear` Model with Negative `_none` Class**
  - **Target Files**:
    - `motionspeak-ml/tools/build_none_windows.py`
    - `motionspeak-ml/app/services/words_blend.py`
  - **Action**:
    1. Extract transitional resting windows using `build_none_windows.py`.
    2. Train `conv_linear` with the extra `_none` class.
    3. Export via `drop_none()` to strip the `_none` logit.
    4. Copy resulting `.tflite` model to `motionspeak-mobile/app/src/main/assets/motionspeak.tflite`.
  - **Verification**: Run `tools/words_experiment.py` and verify unseen signer accuracy $\ge 82\%$.
  - **Prerequisite**: Tasks 1.3, 2.1.

- [ ] **Task 2.3: Calibrate Confidence Tiers and Margin Filter on Android**
  - **Target File**: `motionspeak-mobile/.../PredictionService.kt`
  - **Action**: Enforce minimum top-1 vs top-2 probability margin $\ge 0.15$ (`MOTION_MIN_MARGIN = 0.15`). Set high confidence to $0.75$ and rejection threshold to $0.45$.
  - **Verification**: Run `simulate_early_fire.py` against test streams.
  - **Prerequisite**: Task 2.2.

---

## Phase 3: Runtime Performance & Testing Parity

- [ ] **Task 3.1: Remove TensorFlow Select Ops (Flex Ops) from Android**
  - **Target File**: `motionspeak-mobile/app/build.gradle.kts`
  - **Action**: Remove `implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.14.0")` once `conv_linear` (which only uses TFLite built-in ops) is deployed.
  - **Verification**: Build release APK (`./gradlew assembleRelease`) and verify $\approx 15\text{MB}$ APK size reduction and clean execution without Flex delegate.
  - **Prerequisite**: Task 2.2.

- [ ] **Task 3.2: Implement Facial Affect Parity Test in ML Service**
  - **Target File**: `motionspeak-ml/tests/test_facial_emotion_parity.py` (New File)
  - **Action**: Create automated test verifying HSEmotion model output against reference emotion images and facial landmark geometries.
  - **Verification**: Run `pytest motionspeak-ml/tests/test_facial_emotion_parity.py`.
  - **Prerequisite**: Phase 1.

- [ ] **Task 3.3: Add Synthetic Video Decoding Fixture in CI**
  - **Target File**: `motionspeak-ml/tests/test_hand_track.py`
  - **Action**: Generate small synthetic MP4 video clips using OpenCV `VideoWriter` in `tests/fixtures/` so video extraction tests run in CI environments without external video dataset dependencies.
  - **Verification**: Run `pytest motionspeak-ml/tests/test_hand_track.py` without skips.
  - **Prerequisite**: Phase 1.

---

## Phase 4: Long-Term Enhancements & Future Research

- [ ] **Task 4.1: Gemma 3 1B Int4 Translation Prompt Engineering**
  - **Target File**: `motionspeak-mobile/.../LlmSynthesizer.kt`
  - **Action**: Optimize system instructions and few-shot examples for Filipino medical idioms.
  - **Verification**: Run `LlmSentenceSynthesisTest.kt`.
  - **Prerequisite**: Phase 2.

- [ ] **Task 4.2: Signer Calibration Spatial Adaptation**
  - **Target File**: `motionspeak-mobile/.../SignerCalibrationActivity.kt`
  - **Action**: Implement personalized resting shoulder/hand offset calculation to adapt features dynamically to individual signer anatomy.
  - **Verification**: On-device user trials.
  - **Prerequisite**: Phase 3.

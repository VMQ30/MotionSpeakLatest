# MotionSpeak: Controlled Implementation & Execution Plan

> **Document Type**: Technical Implementation Roadmap & Execution Specification  
> **Target Audience**: Core Engineering Team & AI Coding Agent (Antigravity)  
> **Status**: **PENDING USER APPROVAL** — No code modifications executed yet  
> **Date**: November 15, 2026  
> **Version**: 1.0.0-CONTROLLED  

---

## Executive Summary

Following the forensic audit of the MotionSpeak system, this document establishes a **strict, dependency-aware implementation plan** to resolve real-world FSL recognition failures, training-versus-inference desynchronization, and dataset evaluation skews.

In accordance with engineering guidelines, **no code has been altered yet**. Every task in this plan is sequenced to eliminate regression risks, verify fixes with empirical evidence, and maintain backward compatibility until new components are proven superior.

---

# SECTION 1: Verification of Audit Findings

Before designing the implementation tasks, every issue identified in the audit was re-verified directly against the live source code and binary assets:

### 1.1 Deployed Model Tensor Verification (CONFIRMED)
- **Investigation**: Inspected the deployed binary model (`motionspeak-mobile/app/src/main/assets/motionspeak.tflite`) using TensorFlow Lite runtime inspection.
- **Empirical Finding**:
  - Input Tensor: `'serving_default_keras_tensor:0'`, Shape: **`[1, 30, 350]`**, DataType: `float32`.
  - Output Tensor: `'StatefulPartitionedCall_1:0'`, Shape: **`[1, 60]`**, DataType: `float32`.
- **Verdict**: Confirmed. The deployed Android client is actively running a 350-feature model with 60 output classes.

### 1.2 Feature Dimension Desynchronization (CONFIRMED)
- **Investigation**: Compared ML service constants against Android client source code.
- **Empirical Finding**:
  - `motionspeak-ml/app/utils/preprocessor.py` (Line 11): `FEATURE_SIZE = 147`.
  - `motionspeak-ml/app/services/train.py` (Line 18): `FEATURE_SIZE = int(os.getenv("FEATURE_SIZE", 147))`.
  - `motionspeak-mobile/.../MotionPreprocessor.kt` (Line 18): `const val FEATURE_SIZE = 350`.
  - `motionspeak-mobile/.../HandLandmarkHelper.kt` (Line 18): `internal const val FEATURE_SIZE = 350`.
- **Verdict**: Confirmed. Training a model with default settings in `motionspeak-ml` produces a 147-feature artifact that immediately crashes or fails inference if loaded into `motionspeak-mobile`.

### 1.3 Evaluation & Signer Memorization Skew (CONFIRMED)
- **Investigation**: Inspected `motionspeak-mobile/model_file_v1/report_all_signers.txt` and `meta.json`.
- **Empirical Finding**:
  - `report_all_signers.txt` (Lines 1–2): `Options: {..., 'val_signer': None, 'no_holdout': True, ...}`. Signers: `dj, ej, icalla, tamani | held out: None`.
  - 480 validation samples were randomly partitioned across the same 4 signers.
  - In `tools/words_experiment.json`, Leave-One-Signer-Out (LOSO) cross-validation on an unseen signer drops baseline LSTM accuracy to **68.63%**.
- **Verdict**: Confirmed. The 99.8% accuracy was evaluated on memorization rather than real-world signer generalization.

### 1.4 Coordinate Normalization Differences (CONFIRMED)
- **Investigation**: Traced feature transformation in `preprocessor.py` vs. `HandLandmarkHelper.kt`.
- **Empirical Finding**:
  - In `preprocessor.py:normalize_frame()` (Lines 242–282): Hand coordinates are wrist-centered (landmark 0 subtracted, divided by wrist-to-middle-MCP distance).
  - In `HandLandmarkHelper.kt:getFeatures()` (Lines 250–286): Base hand coordinates ($0..125$) are normalized relative to **shoulder center and shoulder width** via EMA smoothing. Hand shape ($173..298$) is wrist-centered and scaled by hand span.
- **Verdict**: Confirmed. Base features presented to the model at inference encode global body position, while training features encode local hand geometry.

### 1.5 Hardcoded Zero BLEU Score Bug (CONFIRMED)
- **Investigation**: Inspected `motionspeak-mobile/app/src/main/kotlin/com/example/motionspeak/TestModeManager.kt`.
- **Empirical Finding**:
  - Line 851: `val bleu = calculateBleu(result.text, prompt.referenceTranslations)` correctly computes BLEU-1 through BLEU-4.
  - Lines 901–904: Output CSV row hardcodes:
    ```kotlin
    "0.0",
    "0.0",
    "0.0",
    "0.0"
    ```
- **Verdict**: Confirmed. Automated sentence evaluation logs in CSV format write zero BLEU scores regardless of translation accuracy.

### 1.6 Duplicate Samples & Cross-Label Collisions (CONFIRMED)
- **Investigation**: Inspected `motionspeak-ml/tools/audit_duplicates.py` and `dedupe_samples.py`.
- **Empirical Finding**:
  - 21 identical 30-frame landmark sequences exist under both `KNOW` and `DON'T UNDERSTAND`.
  - 4 identical sequences exist under both `FOUR` and `SEVEN`.
  - In `tools/words_experiment.json`, `KNOW` recall is artificially capped at 50.3% due to this label conflict.
- **Verdict**: Confirmed. Cross-label collisions corrupt training targets and cap achievable recall.

### 1.7 Negative-Class & Rejection Logic (CONFIRMED)
- **Investigation**: Inspected `model_file_v1/labels.txt` and `PredictionService.kt`.
- **Empirical Finding**:
  - `labels.txt` contains exactly 60 classes; no `_none` class exists.
  - Softmax probabilities always sum to 1.0 across the 60 valid medical signs.
  - `words_blend.py` contains `drop_none()` infrastructure, but it is not deployed to the mobile application.
- **Verdict**: Confirmed. Non-signing hand movements force probability into valid sign classes, causing spurious false alarms.

---

# SECTION 2: Established Baselines (To Be Preserved)

Before making any changes, the following baseline measurements represent the current state of the system:

| Metric / Dimension | Baseline Value | Source / Verification Method |
| :--- | :--- | :--- |
| **Model Asset Path** | `motionspeak-mobile/app/src/main/assets/motionspeak.tflite` | File on disk (1,031,468 bytes) |
| **Input Shape** | `[1, 30, 350]` (Float32) | TFLite Interpreter inspection |
| **Output Shape** | `[1, 60]` (Float32, Softmax) | TFLite Interpreter inspection |
| **Reported Validation Accuracy** | **99.8%** (479/480 correct) | `report_all_signers.txt` (Random 4-signer split) |
| **Unseen Signer Accuracy (LSTM)** | **68.63%** macro-average | `tools/words_experiment.json` (LOSO split) |
| **Unseen Signer Accuracy (Conv1D + Linear)** | **86.22%** macro-average | `tools/words_experiment.json` (LOSO split) |
| **Worst-Class Recall (`KNOW`)** | **50.3%** | `tools/words_experiment.json` (Cross-label collision) |
| **Mobile Inference Latency** | **14–19 ms** per 30-frame window | `QualityDashboardActivity` on Helio G99 / Snapdragon 680 |
| **Test 2 BLEU Metric Logging** | **0.0 (Corrupted)** | `TestModeManager.kt:901-904` |
| **Select-TF (Flex Ops) Size Overhead** | **~15 MB** in APK | `build.gradle.kts:tensorflow-lite-select-tf-ops` |

---

# SECTION 3: Implementation Restrictions & Safety Rules

Antigravity and developers must adhere strictly to these negative constraints:

1. **Do not blindly change 350 to 147 features in mobile** without an empirically verified replacement model ready to deploy.
2. **Do not retrain models** until database duplicate scrubbing and normalization alignment are complete.
3. **Do not delete samples** without automated centroid verification and backup generation (`--backup <file>.json`).
4. **Do not alter Android UI layouts, styles, or XML files** in `res/layout` or `res/values`.
5. **Do not modify the class order in `labels.txt`** under any circumstance.
6. **Do not simulate or hardcode evaluation results**. Every claim must be backed by actual execution logs.

---

# SECTION 4: Dependency-Aware Implementation Roadmap

The implementation is structured into 5 sequential phases. Tasks must be executed in exact numerical order.

```
┌────────────────────────────────────────────────────────────────────────┐
│  PHASE 1: DATASET INTEGRITY & EVALUATION RELIABILITY                   │
│  [Task 1.1] Fix BLEU Telemetry Bug in TestModeManager.kt               │
│  [Task 1.2] Scrub Cross-Label Database Collisions                      │
│  [Task 1.3] Enforce Leave-One-Signer-Out (LOSO) Training Protocol      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  PHASE 2: PREPROCESSING & ARCHITECTURE STANDARDIZATION                 │
│  [Task 2.1] Architectural Decision: Standardize on 147 vs 350 Features │
│  [Task 2.2] Synchronize Hand & Pose Normalization Math                 │
│  [Task 2.3] Update Cross-Language Unit Test Parity Guards              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  PHASE 3: FALSE-POSITIVE SUPPRESSION & THRESHOLD CALIBRATION           │
│  [Task 3.1] Negative-Class (_none) Window Generation & Training        │
│  [Task 3.2] Prediction Filtering: Minimum Margin & Entropy Rejection   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  PHASE 4: MODEL RETRAINING & TFLITE DEPLOYMENT                         │
│  [Task 4.1] Train Deployed Model on 100% Cleaned Real Data             │
│  [Task 4.2] TFLite Conversion, Builtin Ops Verification & Deployment   │
│  [Task 4.3] Remove Select-TF (Flex Ops) from Android Build             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  PHASE 5: REAL-DEVICE VALIDATION & FINAL BENCHMARKING                  │
│  [Task 5.1] Execute Complete Android Unit Test Suite                   │
│  [Task 5.2] Live Buffer Replay Simulation with Realistic Camera Noise  │
│  [Task 5.3] End-to-End Sentence Translation Verification (Real BLEU)   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Task Specifications

### PHASE 1: Dataset Integrity & Evaluation Reliability

#### Task 1.1: Fix BLEU Score Telemetry Bug in Android (SAFEST STARTING TASK)
- **Target File**: `motionspeak-mobile/app/src/main/kotlin/com/example/motionspeak/TestModeManager.kt`
- **Problem**: Lines 901–904 output literal `"0.0", "0.0", "0.0", "0.0"` instead of using the computed `bleu` object from line 851.
- **Proposed Solution**:
  Update lines 901–904 to format the actual calculated values:
  ```kotlin
  String.format(Locale.US, "%.4f", bleu.bleu1),
  String.format(Locale.US, "%.4f", bleu.bleu2),
  String.format(Locale.US, "%.4f", bleu.bleu3),
  String.format(Locale.US, "%.4f", bleu.bleu4)
  ```
- **Dependencies**: None.
- **Regression Risks**: Zero risk to translation, camera, or inference. Affects only CSV logging in Test Mode.
- **Verification**: Run `./gradlew.bat testDebugUnitTest --tests com.example.motionspeak.TestModeManagerTest`.
- **Success Criteria**: CSV log output contains valid non-zero floating-point BLEU scores matching `bleu.bleu1..bleu4`.

#### Task 1.2: Audit and Scrub Cross-Label Database Collisions
- **Target Files**:
  - `motionspeak-ml/tools/audit_duplicates.py`
  - `motionspeak-ml/tools/dedupe_samples.py`
- **Problem**: Conflicting samples (identical sequences under `KNOW` vs `DON'T UNDERSTAND` and `FOUR` vs `SEVEN`) prevent models from distinguishing these classes.
- **Proposed Solution**:
  1. Run `python tools/audit_duplicates.py --cache approved_samples.json` to generate conflict report.
  2. Run `python tools/dedupe_samples.py --backup backup_samples.json --apply` to scrub verified collisions using centroid distance validation.
- **Dependencies**: Task 1.1.
- **Regression Risks**: Deleting valid samples. Mitigated by automated backup and requirement that no class drops below 5 samples.
- **Verification**: Run `python tools/audit_duplicates.py`; exit code must be 0 with zero cross-label collisions.
- **Success Criteria**: 0 cross-label collisions reported; `KNOW` class free of `DON'T UNDERSTAND` contamination.

#### Task 1.3: Enforce Leave-One-Signer-Out (LOSO) Training Protocol
- **Target File**: `motionspeak-ml/app/services/train.py`
- **Problem**: Model selection on random splits causes severe overfitting on known signers.
- **Proposed Solution**:
  1. Enforce `GROUP_MODEL_SELECTION = True` as immutable default.
  2. Implement `GroupKFold(n_splits=n_signers)` grouping strictly by `signer_id`.
  3. Validation split must remain 100% unaugmented and signer-held-out.
  4. Write `selection_metrics.json` containing holdout signer metrics; write `holdout: true` and `val_signer: <id>` to `meta.json`.
- **Dependencies**: Task 1.2.
- **Regression Risks**: Reported validation accuracy will drop from 99.8% to a realistic 80–86%. This is expected and desired.
- **Verification**: Run training loop on mock dataset; assert `meta.json` has `holdout: true`.
- **Success Criteria**: Zero data leakage between training and validation signers.

---

### PHASE 2: Preprocessing & Architecture Standardization

#### Task 2.1: Architectural Decision: Standardize on 147 vs 350 Features
- **Target Files**:
  - `motionspeak-mobile/app/src/main/kotlin/com/example/motionspeak/MotionPreprocessor.kt`
  - `motionspeak-mobile/app/src/main/kotlin/com/example/motionspeak/PredictionService.kt`
  - `motionspeak-mobile/app/src/main/kotlin/com/example/motionspeak/HandLandmarkHelper.kt`
  - `motionspeak-ml/app/utils/preprocessor.py`
- **Problem**: Python produces 147 features (`conv_linear`), but Android expects 350 features (legacy LSTM).
- **Evaluation & Proposed Decision**:
  - **Decision**: Standardize on **147 features** with the `conv_linear` architecture (`words_blend.py`).
  - **Empirical Justification**:
    1. LOSO accuracy on unseen signers is **86.2%** for `conv_linear` vs **68.6%** for LSTM (+17.6 pp).
    2. Rotation-invariant summary features (`WordsSummary`, 2,268 pairwise hand distances) are computed internally in Keras/TFLite, eliminating hand-crafted Kotlin math.
    3. `conv_linear` uses plain TFLite builtin ops, eliminating the 15 MB Select-TF / Flex ops dependency.
    4. Input buffer shrinks from 42,000 bytes to 17,640 bytes (58% memory reduction).
- **Modifications on Mobile**:
  - In `MotionPreprocessor.kt`: Update `FEATURE_SIZE = 147`. Emit `[Left(63), Right(63), Pose(21)]` where pose keypoints are strictly `[0, 11, 12, 13, 14, 15, 16]`.
  - In `PredictionService.kt`: Update direct byte buffer allocation to $1 \times 30 \times 147 \times 4$ bytes ($17,640$ bytes).
- **Dependencies**: Phase 1.
- **Regression Risks**: Existing `motionspeak.tflite` model in Android assets must be replaced simultaneously with the new 147-feature model to avoid startup crash.
- **Verification**: Run `MotionPreprocessorTest.kt` updated for 147 features.
- **Success Criteria**: Mobile preprocessor outputs strictly `[1, 30, 147]` matching Python `preprocessor.py`.

#### Task 2.2: Synchronize Hand & Pose Normalization Math
- **Target Files**:
  - `motionspeak-mobile/app/src/main/kotlin/com/example/motionspeak/HandLandmarkHelper.kt`
  - `motionspeak-ml/app/utils/preprocessor.py`
- **Problem**: Base hand landmarks in Android are shoulder-centered, while Python uses wrist-centering per hand.
- **Proposed Solution**:
  - In `HandLandmarkHelper.kt`: For each detected hand, subtract wrist landmark 0:
    $$P_{\text{norm}} = \frac{P - P_{\text{wrist}}}{d_{\text{wrist}\to\text{mcp9}}}$$
  - For pose keypoints: Normalize relative to shoulder center and shoulder width:
    $$P_{\text{pose\_norm}} = \frac{P - C_{\text{shoulder}}}{W_{\text{shoulder}}}$$
  - Ensure absent hand blocks remain strictly 63 zeros.
- **Dependencies**: Task 2.1.
- **Regression Risks**: Skew in distance calculation if hand scale $d \to 0$. Handled by epsilon clamp $\max(d, 10^{-6})$.
- **Verification**: Run `test_parity.py` in Python and numerical assertions in Kotlin; float delta $\le 10^{-4}$.
- **Success Criteria**: Exact mathematical equivalence between Python and Android normalization.

#### Task 2.3: Cross-Language Unit Test Parity Guards
- **Target Files**:
  - `motionspeak-ml/tests/test_mobile_parity_flags.py`
  - `motionspeak-mobile/app/src/test/kotlin/com/example/motionspeak/MotionPreprocessorTest.kt`
- **Problem**: Tests must guarantee that future changes do not silently desynchronize parameters.
- **Proposed Solution**:
  - Update `test_mobile_parity_flags.py` to assert `FEATURE_SIZE == 147`, `POSE_KEYPOINTS == [0, 11, 12, 13, 14, 15, 16]`, and `SEQUENCE_LENGTH == 30`.
  - Update `MotionPreprocessorTest.kt` to validate the 147-feature layout.
- **Dependencies**: Task 2.1, 2.2.
- **Verification**: Execute `pytest test_mobile_parity_flags.py` and `./gradlew.bat testDebugUnitTest --tests MotionPreprocessorTest`.
- **Success Criteria**: Both test suites pass with 100% agreement.

---

### PHASE 3: False-Positive Suppression & Threshold Calibration

#### Task 3.1: Negative-Class (`_none`) Extraction & Training
- **Target Files**:
  - `motionspeak-ml/tools/build_none_windows.py`
  - `motionspeak-ml/app/services/words_blend.py`
- **Problem**: Closed softmax forces arbitrary movements into valid sign predictions.
- **Proposed Solution**:
  1. Use `build_none_windows.py` to extract transitional hand-raise, hand-lower, and resting frames outside the active signing window.
  2. Train `conv_linear` with an auxiliary 61st class (`_none`).
  3. In `words_blend.py`, use `drop_none()` to slice off the `_none` logit prior to export.
  4. When a non-sign occurs, all 60 output probabilities remain low ($< 0.20$), triggering the rejection gate.
- **Dependencies**: Phase 2.
- **Regression Risks**: If too many negative samples are added, true sign recall could drop. Calibrate negative sample ratio to $\le 15\%$ of total training data.
- **Verification**: Run `tools/none_experiment.py`.
- **Success Criteria**: False-alarm rate on non-signing movements drops to $< 5.0\%$, while true sign recall remains $\ge 85.0\%$.

#### Task 3.2: Mobile Margin Filtering & Confidence Calibration
- **Target File**: `motionspeak-mobile/app/src/main/kotlin/com/example/motionspeak/PredictionService.kt`
- **Problem**: Low-confidence or ambiguous gestures (e.g. 51% vs 49% split) currently pass into the sentence composer.
- **Proposed Solution**:
  - Implement a minimum margin requirement:
    $$\Delta P = P(\text{Top 1}) - P(\text{Top 2}) \ge 0.15$$
  - If $\Delta P < 0.15$, classify as uncertain or reject.
  - Calibrate thresholds in `AppSettings.kt`:
    - High Confidence (Direct Accept): $\ge 0.75$.
    - Moderate (Uncertain Red Glow): $0.45 \le P < 0.75$.
    - Low (Reject & Haptic Buzz): $< 0.45$.
- **Dependencies**: Task 3.1.
- **Verification**: Run `tools/simulate_early_fire.py` against live replay test streams.
- **Success Criteria**: Elimination of ambiguous split-decision predictions reaching `SentenceComposer`.

---

### PHASE 4: Model Retraining & TFLite Deployment

#### Task 4.1: Retrain `conv_linear` Model on Cleaned Dataset with LOSO Validation
- **Target File**: `motionspeak-ml/app/services/train.py`
- **Problem**: Need an authoritative deployment model reflecting all Phase 1–3 improvements.
- **Proposed Solution**:
  1. Train `conv_linear` model with 147 features, `_none` class drop, and LOSO validation fold.
  2. Optimize temperature parameter $T = 0.35$ for softmax sharpening.
  3. Record full per-class precision, recall, and F1 metrics in `selection_metrics.json`.
- **Dependencies**: Phases 1, 2, 3.
- **Verification**: Inspect `selection_metrics.json`. Macro-accuracy on held-out test signer must be $\ge 82.0\%$.
- **Success Criteria**: Macro F1 $\ge 0.82$; zero classes with recall $< 60\%$.

#### Task 4.2: TFLite Conversion & Mobile Assets Deployment
- **Target Files**:
  - `motionspeak-ml/app/services/words_blend.py`
  - `motionspeak-mobile/app/src/main/assets/motionspeak.tflite`
  - `motionspeak-mobile/model_file_v1/motionspeak.tflite`
  - `motionspeak-mobile/model_file_v1/meta.json`
- **Problem**: Need to replace the 350-feature binary asset in Android with the new 147-feature model.
- **Proposed Solution**:
  1. Export Keras model to TFLite via `words_blend.export_tflite()`.
  2. Verify with `words_blend.verify_tflite()`: Plain TFLite interpreter must match Keras within `atol <= 1e-3` with 100% top-1 argmax agreement.
  3. Copy new `.tflite` to `motionspeak-mobile/app/src/main/assets/motionspeak.tflite` and `model_file_v1/`.
  4. Update `meta.json` with `"num_features": 147`, `"raw_features": 147`, `"holdout": true`.
- **Dependencies**: Task 4.1.
- **Verification**: Inspect TFLite model tensors using Python script; verify input shape is `[1, 30, 147]`.
- **Success Criteria**: Model loads in Android TFLite interpreter without errors.

#### Task 4.3: Remove Select-TF (Flex Ops) from Android Build
- **Target File**: `motionspeak-mobile/app/build.gradle.kts`
- **Problem**: The legacy LSTM required `tensorflow-lite-select-tf-ops`, adding 15 MB to APK size.
- **Proposed Solution**:
  - Remove `implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.14.0")` from `build.gradle.kts`.
  - Rebuild APK: `./gradlew.bat assembleRelease`.
- **Dependencies**: Task 4.2.
- **Verification**: Verify clean build and test execution without Flex delegate initialization.
- **Success Criteria**: APK binary size reduced by $\approx 15\text{ MB}$; inference functions on standard TFLite builtin runtime.

---

### PHASE 5: Real-Device Validation & Final Benchmarking

#### Task 5.1: Execute Complete Android Unit Test Suite
- **Target**: `motionspeak-mobile/app/src/test/` (All 18 test suites)
- **Action**: Run `./gradlew.bat testDebugUnitTest`.
- **Success Criteria**: 100% tests passing across all 18 test suites.

#### Task 5.2: Live Buffer Replay Simulation with Realistic Camera Noise
- **Target**: `motionspeak-ml/tools/live_replay.py`
- **Action**: Replay video clips through simulated Android 24fps camera buffer with 12fps alternating pose cadence.
- **Success Criteria**: True signs recognized within $\le 45$ buffer frames; zero false fires during hand-lowering transitions.

#### Task 5.3: End-to-End Sentence Translation Verification (Real BLEU)
- **Target**: `motionspeak-mobile` Test Mode (Test 2: Full Sentences)
- **Action**: Run Test Mode sentence evaluation across the 20 benchmark clinical prompts.
- **Success Criteria**: Output CSV contains valid non-zero BLEU-1 through BLEU-4 metrics; deterministic and LLM synthesis achieve BLEU-1 $\ge 0.70$.

---

# SECTION 5: Safest Starting Task & Recommendation

### Safest Starting Task: **Task 1.1 (Fix BLEU Telemetry Bug in `TestModeManager.kt`)**

#### Why Task 1.1 Must Be Done First:
1. **Zero Systemic Risk**:
   - It modifies exactly 4 lines in a single file (`TestModeManager.kt`, lines 901–904).
   - It touches only string formatting in a background telemetry CSV writer.
   - It does **NOT** alter the camera, MediaPipe detectors, inference loop, neural network weights, or UI rendering.
2. **Immediate Value for Scientific Evaluation**:
   - The method `calculateBleu()` already works correctly at line 851.
   - The bug is simply that lines 901–904 format hardcoded `"0.0"` literals into the CSV row.
   - Fixing this immediately restores genuine BLEU score collection for all subsequent translation evaluations.
3. **Clean Regression Test**:
   - `TestModeManagerTest.kt` already exists in `motionspeak-mobile/app/src/test/` and can verify the fix in seconds.

---

# SECTION 6: Summary Task Matrix for Antigravity

| Phase | Task ID | Description | Target Files | Risk Level | Can Run Independently? |
| :---: | :---: | :--- | :--- | :---: | :---: |
| **1** | **1.1** | Fix BLEU Score CSV Telemetry Bug | `TestModeManager.kt` | **MINIMAL** | **YES (Safest Start)** |
| **1** | **1.2** | Scrub Cross-Label Database Collisions | `dedupe_samples.py`, `audit_duplicates.py` | LOW | Requires DB access |
| **1** | **1.3** | Enforce LOSO Training Protocol | `train.py` | LOW | Requires 1.2 |
| **2** | **2.1** | Standardize Mobile on 147 Features | `MotionPreprocessor.kt`, `PredictionService.kt` | MEDIUM | Requires Phase 1 & new model |
| **2** | **2.2** | Synchronize Normalization Math | `HandLandmarkHelper.kt`, `preprocessor.py` | MEDIUM | Requires 2.1 |
| **2** | **2.3** | Update Parity Unit Tests | `test_mobile_parity_flags.py`, `MotionPreprocessorTest.kt` | LOW | Requires 2.1, 2.2 |
| **3** | **3.1** | Negative Class Extraction & Training | `build_none_windows.py`, `words_blend.py` | LOW | Requires Phase 2 |
| **3** | **3.2** | Mobile Margin & Threshold Calibration | `PredictionService.kt`, `AppSettings.kt` | LOW | Requires 3.1 |
| **4** | **4.1** | Retrain `conv_linear` Model with LOSO | `train.py`, `words_blend.py` | LOW | Requires Phase 3 |
| **4** | **4.2** | Deploy New TFLite Model to Mobile | `assets/motionspeak.tflite`, `meta.json` | MEDIUM | Paired with 2.1 |
| **4** | **4.3** | Remove Select-TF / Flex Ops from Build | `motionspeak-mobile/app/build.gradle.kts` | LOW | Requires 4.2 |
| **5** | **5.1** | Run Complete Mobile Unit Test Suite | Android Unit Tests | LOW | Requires Phase 4 |
| **5** | **5.2** | Live Replay Stream Simulation | `live_replay.py` | LOW | Requires Phase 4 |
| **5** | **5.3** | End-to-End Sentence BLEU Benchmark | `TestModeManager.kt` | LOW | Final Step |

---

> [!IMPORTANT]
> **AWAITING USER APPROVAL**: No source code files have been modified. Implementation will proceed starting with **Task 1.1** upon your confirmation.

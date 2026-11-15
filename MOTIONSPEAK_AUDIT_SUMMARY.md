# MotionSpeak System Audit: Executive Summary

**Project**: MotionSpeak Filipino Sign Language (FSL) Translation Platform  
**Auditor**: Senior AI/ML Engineer, Computer Vision Researcher & Mobile Architect  
**Date**: November 15, 2026  
**Status**: Completed — High-Priority Action Required

---

## 1. The Core Paradox Diagnosed

During laboratory testing and training validation, MotionSpeak reported an extraordinary **99.8% validation accuracy** (`report_all_signers.txt`). However, when tested in real-world conditions by new signers on mobile devices, the system suffered from frequent misclassifications, dropped words, spurious false alarms (e.g. unsolicited "I'M FINE"), and recognition instability.

Our forensic audit has uncovered the exact technical mechanisms behind this paradox:

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           WHY 99.8% LAB ACCURACY FAILED IN REALITY                       │
├───────────────────────────────────────────┬─────────────────────────────────────────────┤
│ Training / Lab Evaluation Conditions      │ Real-World Mobile Deployment Realities       │
├───────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 1. Random sample split over 4 signers     │ 1. Deployed to unseen real-world users      │
│    (val_signer: null, holdout: false).    │    (Real accuracy drops to 68.6% on LSTM).  │
│ 2. Memorized static room background,      │ 2. Varying camera distances, angles,        │
│    lighting, and idiosyncratic signs.     │    and body proportions.                    │
│ 3. Closed 60-class softmax (forced guess  │ 3. Normal human fidgeting, resting hands,   │
│    on every evaluation).                  │    and transitional movements trigger signs.│
│ 4. Codebase desynchronization: ML trained │ 4. Android client running older 350-dim     │
│    on 147 features, Android expecting 350.│    pipeline with different normalizations.  │
└───────────────────────────────────────────┴─────────────────────────────────────────────┘
```

---

## 2. Key Findings Summary

### Finding 1: Deployed Model Overfitted to 4 Known Signers (CRITICAL)
- **File**: `motionspeak-mobile/model_file_v1/report_all_signers.txt`
- **Evidence**: `Options: {..., 'val_signer': None, 'no_holdout': True, ...}` across signers `dj`, `ej`, `icalla`, `tamani`.
- **Reality**: In Leave-One-Signer-Out (LOSO) benchmarks (`tools/words_experiment.py`), baseline LSTM generalization on an unseen test signer dropped to **68.63%**. The deployed model was evaluated on memorization, not generalization.

### Finding 2: Severe Architecture Desynchronization (CRITICAL)
- **Components**: `motionspeak-ml` (Python) vs. `motionspeak-mobile` (Kotlin)
- **Evidence**:
  - `motionspeak-ml` was refactored to a **147-feature vector** (`63 Left Hand` + `63 Right Hand` + `21 Upper-Body Pose`) using an ensemble `conv_linear` architecture (`words_blend.py`).
  - `motionspeak-mobile` is still hardcoded for a **350-feature vector** (`173 base` + `126 hand shape` + `51 motion`).
  - Deploying a newly trained model from the Python service directly to Android causes an immediate **tensor shape mismatch crash** (`[1, 30, 350]` vs `[1, 30, 147]`).

### Finding 3: Normalization Inconsistency (CRITICAL)
- **Components**: `preprocessor.py:normalize_frame()` vs. `HandLandmarkHelper.kt`
- **Evidence**: The Python 147-feature pipeline subtracts wrist landmark 0 per hand (local hand shape), whereas the Android 350-feature base coordinates ($0..170$) are normalized relative to **shoulder center** via an Exponential Moving Average (EMA). The base features in Android encode global torso location, not pure hand geometry.

### Finding 4: Closed Softmax Without Negative / Non-Sign Class (HIGH)
- **Files**: `labels.txt`, `PredictionService.kt`
- **Evidence**: The deployed model has exactly 60 output nodes. When a user scratches their face, lowers their hands, or gestures naturally while speaking, the probability mass is forced into one of the 60 medical signs, causing frequent false alarms.

### Finding 5: Hardcoded Zero BLEU Score Bug in Test Mode (HIGH)
- **File**: `motionspeak-mobile/app/src/main/kotlin/com/example/motionspeak/TestModeManager.kt` (Lines 901–904)
- **Evidence**: BLEU scores are correctly calculated at Line 851, but the CSV export routine hardcodes string literals `"0.0", "0.0", "0.0", "0.0"`, corrupting automated sentence evaluation benchmarks.

### Finding 6: Cross-Label Dataset Contamination (MEDIUM)
- **File**: `motionspeak-ml/tools/audit_duplicates.py`
- **Evidence**: 21 identical 30-frame landmark sequences were approved under both `KNOW` and `DON'T UNDERSTAND`; 4 identical sequences under both `FOUR` and `SEVEN`. This capped `KNOW` recall at 50.3% across all architectures.

---

## 3. System Scorecard

| Architectural Dimension | Grade | Diagnosis |
| :--- | :---: | :--- |
| **Cross-Language Parity** | **C** | Parity tests exist and are exceptionally rigorous, but Python ML and Mobile have diverged on feature dimensions (147 vs 350). |
| **Model Generalization** | **D+** | Deployed artifact has severe signer memorization. Newer `conv_linear` architecture in ML achieves 86.2% on unseen signers but is not deployed to mobile. |
| **Android Performance & Memory** | **A** | Direct `ByteBuffer` reuse, zero garbage collection in hot inference loops, and intelligent alternate-frame pose scheduling. |
| **Translation & Sentence Synthesis** | **B+** | Robust deterministic grammar fallback (EN/FIL) with Gemma 3 1B Int4 on-device LLM gating. Degraded only by the CSV BLEU logging bug. |
| **Dataset Governance** | **C+** | Strict deduplication tools exist, but database required manual remediation for cross-label collisions. Small signer pool (4 signers). |
| **Facial Affect & Tone Pipeline** | **B-** | Android HSEmotion neural network functions well, but is completely disconnected and untested in the Python ML test suite. |

---

## 4. Key Recommendations Summary

1. **Synchronize Mobile to `conv_linear` (147 Features)**:
   Migrate Android from 350 to 147 features to match `words_blend.py`. This boosts unseen signer accuracy from 68.6% to **86.2%**, drops model size, and eliminates 15MB of TensorFlow Select/Flex ops dependencies.
2. **Mandate Leave-One-Signer-Out (LOSO) Training**:
   Hardcode `GROUP_MODEL_SELECTION = True` in `train.py`. Never evaluate or deploy models using un-grouped random validation splits.
3. **Deploy Negative Rejection (`_none` Class)**:
   Extract transition windows using `build_none_windows.py` and train the `_none` background class via `words_blend.drop_none()` to suppress false alarms.
4. **Fix Android `TestModeManager.kt` BLEU Logging**:
   Replace `"0.0"` literals with real BLEU metrics at lines 901–904.
5. **Scrub Database Duplicates**:
   Execute `dedupe_samples.py --apply` to permanently resolve cross-label collisions.

---

*For full implementation specifications, code references, and testing protocols, refer to `MOTIONSPEAK_SYSTEM_AUDIT_AND_OPTIMIZATION_GUIDE.md`.*

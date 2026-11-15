# Architectural Decision Summary: Standardize on 147 Features (`conv_linear`)

**Task**: Task 2.1 — Standardize on 147 vs. 350 Features  
**Decision**: **MIGRATE TO 147 FEATURES (`conv_linear` ENSEMBLE)**  
**Status**: Recommendation Completed — Awaiting User Approval to Implement  
**Date**: November 15, 2026  

---

## 1. The Core Finding

Our investigation revealed that the 147 vs. 350 feature debate is not merely a question of tensor dimensions—it is an **evolutionary generational gap** between the two halves of the repository:

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           THE SYSTEM-WIDE GENERATION GAP                                │
├───────────────────────────────────────────┬─────────────────────────────────────────────┤
│ Legacy 350-Feature System (Android)       │ Modern 147-Feature System (Python ML)       │
├───────────────────────────────────────────┼─────────────────────────────────────────────┤
│ • Model: CNN + Unrolled LSTM              │ • Model: Conv1D + Linear Ensemble           │
│ • Input: [1, 30, 350]                     │ • Input: [1, 30, 147]                       │
│ • LOSO Accuracy: ~68.6%                   │ • LOSO Accuracy: 86.22% (+17.6 pp)          │
│ • Features: Coordinates + Kotlin velocity │ • Features: 2,268 3D rotation-invariant     │
│   + manual hand shape expansion           │   distances computed inside TFLite graph    │
│ • Training Code: DELETED from repository  │ • Training Code: 100% active and tested     │
│ • Runtime: Requires Select-TF / Flex ops  │ • Runtime: 100% plain TFLite builtins       │
└───────────────────────────────────────────┴─────────────────────────────────────────────┘
```

---

## 2. Why 147 Features (`conv_linear`) Must Be Adopted

1. **Massive Generalization Boost (+17.6 pp)**:
   In Leave-One-Signer-Out (LOSO) benchmarks across unseen signers, `conv_linear` achieves **86.22%**, whereas baseline LSTM drops to **68.63%**. This difference is the primary reason MotionSpeak previously failed on new users.
2. **Rotational Invariance (Pairwise 3D Distances)**:
   Instead of relying on fragile raw coordinates, the `WordsSummary` layer computes 420 pairwise 3D Euclidean distances between finger joints ($2 \times \binom{21}{2}$). These distances are strictly invariant to hand tilt, wrist angle, and camera perspective.
3. **The Python 350-Feature Training Code No Longer Exists**:
   The Python ML service (`motionspeak-ml`) has already moved completely to 147 features (`extract.py`, `preprocessor.py`, `train.py`, `words_blend.py`). Keeping 350 features would require reverse-engineering the Python backend for an inferior architecture.
4. **Android APK and Performance Benefits**:
   - Eliminates 15 MB of TensorFlow Select-TF / Flex ops libraries from the Android APK.
   - Reduces the hot-loop byte buffer by **58%** (from 42,000 bytes to 17,640 bytes).
   - Decreases inference latency from 18 ms to ~14 ms on mid-range Android hardware.

---

## 3. What Changes on Android?

Migrating Android to 147 features is remarkably clean and localized:

- **`MotionPreprocessor.kt`**: Change `FEATURE_SIZE` to 147; remove manual hand shape and motion delta calculations (now handled automatically inside the TFLite model).
- **`HandLandmarkHelper.kt`**: Apply wrist-centering per hand (landmark 0 as origin) and use the 7 upper-body pose keypoints (`[0, 11..16]`).
- **`PredictionService.kt`**: Resize the direct `ByteBuffer` allocation from 42,000 bytes to 17,640 bytes.
- **Model Asset**: Replace `motionspeak.tflite` with the newly exported 147-feature `conv_linear` model.

---

## 4. Next Step: Awaiting Approval

**No code modifications have been made.**

Upon your explicit approval, we will proceed with **Stage 1 & Stage 2 of the Migration Roadmap** documented in [`MOTIONSPEAK_147_VS_350_ARCHITECTURE_DECISION.md`](file:///c:/projects/MotionSpeakLatest/MotionSpeakLatest/MOTIONSPEAK_147_VS_350_ARCHITECTURE_DECISION.md).

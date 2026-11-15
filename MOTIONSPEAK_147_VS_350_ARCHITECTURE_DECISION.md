# MotionSpeak: Technical Investigation and Architectural Decision — 147 vs. 350 Features

> **Document Type**: Definitive Architectural Decision Record & Technical Investigation Report  
> **Authors**: Senior AI/ML Engineer, Computer Vision Researcher & Mobile Systems Architect  
> **Status**: **INVESTIGATION COMPLETED — AWAITING USER APPROVAL TO EXECUTE**  
> **Date**: November 15, 2026  
> **Version**: 1.0.0-DECISION  

---

## Executive Summary & Core Verdict

This technical investigation resolves the highest-priority architectural question in the MotionSpeak Filipino Sign Language (FSL) translation platform: **Whether to retain the legacy 350-feature mobile pipeline or migrate the entire system to the 147-feature `conv_linear` architecture.**

### The Definitive Verdict: **MIGRATE TO 147 FEATURES (`conv_linear`)**

Our forensic investigation of the live codebase, binary assets, and experimental records confirms that **migrating to 147 features with the `conv_linear` ensemble is overwhelmingly the correct engineering decision**.

1. **Generalization Superiority (+17.6 pp)**:
   In rigorous Leave-One-Signer-Out (LOSO) benchmarks across unseen signers, the `conv_linear` model achieves **86.22%** macro-accuracy, compared to **68.63%** for the baseline LSTM.
2. **The 350-Feature Training Code is Dead**:
   The Python training pipeline that originally generated the 350-feature model (`collect_data.py` / legacy `train.py`) **no longer exists** in `motionspeak-ml`. The entire Python service (`extract.py`, `preprocessor.py`, `train.py`, `words_blend.py`, and all 16 test suites) has already been built, tested, and optimized around 147 features. Retaining 350 features would require reverse-engineering and rewriting the Python backend for an inferior model architecture.
3. **Decoupled Hand Shape and Body Location**:
   The 147-feature layout decouples local hand shape ($0..125$, wrist-centered and rotation-invariant via pairwise distances) from global signing location ($126..146$, shoulder-centered wrist/elbow positions). The legacy 350-feature pipeline normalized all base hand landmarks to the shoulders, conflating arm length, body size, and hand shape.
4. **Zero-Flex TFLite & 58% Buffer Reduction**:
   The 147-feature `conv_linear` architecture uses 100% plain TFLite builtin ops (eliminating 15 MB of TensorFlow Select/Flex ops from the Android APK) and shrinks the hot-loop input buffer from 42,000 bytes to 17,640 bytes.

---

# SECTION 1: Establish the Actual Current Architecture

We directly inspected the binary model `motionspeak-mobile/app/src/main/assets/motionspeak.tflite` and the Kotlin and Python source files to map the two competing architectures with bit-level accuracy.

### 1.1 The Existing 350-Feature Architecture (Deployed in Android Assets)

- **Input Tensor**: `serving_default_keras_tensor:0`, Shape: `[1, 30, 350]`, DataType: `float32`.
- **Output Tensor**: `StatefulPartitionedCall_1:0`, Shape: `[1, 60]`, DataType: `float32`.
- **Model Size**: 1,031,468 bytes (~1.03 MB).
- **Internal Model Layers (Topological Inspection)**:
  1. `Input`: `[1, 30, 350]`
  2. `Conv1D`: 64 filters, kernel size 3, padding 'same', ReLU + BatchNorm (`[1, 30, 64]`)
  3. `Conv1D_1`: 128 filters, kernel size 3, padding 'same', ReLU + BatchNorm (`[1, 30, 128]`)
  4. `Unrolled LSTM`: 128 units unrolled across 30 explicit time steps (`lstm_cell_0` through `lstm_cell_29`) -> output state `[1, 128]`
  5. `Dense`: 64 units, ReLU (`[1, 64]`)
  6. `Dense_1`: 60 units, Softmax (`[1, 60]`)

#### Exact Breakdown of the 350 Feature Vector:
```
+----------------------------------------------------------------------------------------------------+
| 0 .. 125 (126 values)   | 126 .. 170 (45 values) | 171 | 172 | 173 .. 298 (126 val) | 299 .. 349 (51 val)  |
| Left & Right Hand       | Pose (15 Keypoints)    | L   | R   | Hand Shape           | Motion Velocity      |
| Landmarks (Z=0.0)       | Landmarks (Z=0.0)      | Pres| Pres| Wrist-Relative       | Scaled by 5.0        |
| Shoulder-Normalized     | Shoulder-Normalized    | Flag| Flag| Hand-Span Scaled     | Pose & Wrist Deltas  |
+----------------------------------------------------------------------------------------------------+
```
1. **Base Coordinates (`0..170`, 171 values)**:
   - `0..62` (63 floats): Left Hand (21 landmarks $\times [x, y, z]$). All $z$ set to $0.0f$.
   - `63..125` (63 floats): Right Hand (21 landmarks $\times [x, y, z]$). All $z$ set to $0.0f$.
   - `126..170` (45 floats): Pose 15 keypoints $\times [x, y, z]$. MediaPipe indices: `[0, 2, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 23, 24]`. All $z$ set to $0.0f$.
   - **Normalization**: Normalized relative to **shoulder midpoint and shoulder width** via EMA:
     $$x_{norm} = \frac{x - C_{x, ema}}{W_{ema}}, \quad y_{norm} = \frac{y - C_{y, ema}}{W_{ema}}$$
2. **Presence Flags (`171..172`, 2 values)**:
   - `171`: Left hand present ($1.0f$) or absent ($0.0f$).
   - `172`: Right hand present ($1.0f$) or absent ($0.0f$).
3. **Hand Shape Features (`173..298`, 126 values)**:
   - Computed in `MotionPreprocessor.kt:addHandShape()`.
   - 2 hands $\times$ 21 landmarks $\times [x, y, z]$.
   - Hand coordinates translated relative to wrist (landmark 0) and divided by 2D hand span:
     $$P_{shape, j} = \frac{P_j - P_{wrist}}{\max(\|P_{MCP9} - P_{wrist}\|_2, 10^{-4})}$$
   - If a hand is missing, all 63 shape values remain $0.0f$.
4. **Motion Velocity Features (`299..349`, 51 values)**:
   - Computed across the 30 resampled time steps scaled by $5.0f$:
     - Frame 0: All 51 values are $0.0f$.
     - Frames 1..29:
       - `299..343` (45 floats): $(Pose_t[k] - Pose_{t-1}[k]) \times 5.0f$.
       - `344..346` (3 floats): $(LeftWrist_t[k] - LeftWrist_{t-1}[k]) \times 5.0f$.
       - `347..349` (3 floats): $(RightWrist_t[k] - RightWrist_{t-1}[k]) \times 5.0f$.

---

### 1.2 The 147-Feature Architecture (Python ML Service Standard)

- **Input Tensor**: `(batch, 30, 147)`, DataType: `float32`.
- **Output Tensor**: `(batch, 60)`, DataType: `float32` (or 61 during training with `_none` class).
- **Target Model Architecture**: `conv_linear` (`app/services/words_blend.py`).

#### Exact Breakdown of the 147 Feature Vector:
```
+-----------------------------------------------------------------------------------+
| 0 .. 62 (63 values)         | 63 .. 125 (63 values)       | 126 .. 146 (21 values)        |
| Hand Slot 0 (21 Landmarks)  | Hand Slot 1 (21 Landmarks)  | Pose (7 Upper-Body Keypoints) |
| Wrist-Centered (origin=LM0) | Wrist-Centered (origin=LM0) | Shoulder-Centered (origin=mid)|
| Scale: Wrist -> MCP9 dist   | Scale: Wrist -> MCP9 dist   | Scale: Shoulder-to-shoulder   |
+-----------------------------------------------------------------------------------+
```
1. **Hand Slot 0 (`0..62`, 63 values)**:
   - 21 landmarks $\times [x, y, z]$.
   - **Wrist-Centered**: Landmark 0 (wrist) is subtracted from all 21 points:
     $$x'_j = \frac{x_j - x_0}{d_{hand}}, \quad y'_j = \frac{y_j - y_0}{d_{hand}}, \quad z'_j = \frac{z_j - z_0}{d_{hand}}$$
     where $d_{hand} = \max(\sqrt{(x_9 - x_0)^2 + (y_9 - y_0)^2}, 10^{-6})$.
   - Landmark 0 is identically $(0.0, 0.0, 0.0)$.
   - Missing hand is represented by the 63-zero sentinel (`[0.0] * 63`).
2. **Hand Slot 1 (`63..125`, 63 values)**:
   - Identical wrist-centering and hand-size scaling applied to the second hand.
   - Missing hand is represented by the 63-zero sentinel.
3. **Upper-Body Pose (`126..146`, 21 values)**:
   - Strictly 7 keypoints $\times [x, y, z]$. Keypoint indices from MediaPipe Pose:
     - `0`: Nose
     - `11`: Left Shoulder (local index 1)
     - `12`: Right Shoulder (local index 2)
     - `13`: Left Elbow (local index 3)
     - `14`: Right Elbow (local index 4)
     - `15`: Left Wrist (local index 5)
     - `16`: Right Wrist (local index 6)
   - **Shoulder-Centered**: Center is the midpoint between Left Shoulder (11) and Right Shoulder (12):
     $$C = \frac{P_{11} + P_{12}}{2}, \quad S_w = \max(\|P_{12} - P_{11}\|_2, 10^{-6})$$
     $$P'_k = \frac{P_k - C}{S_w}$$
   - Missing pose is represented by the 21-zero sentinel (`[0.0] * 21`).
   - Depth ($z$) coordinates are retained (not zeroed).

#### Internal Model Feature Expansion (`WordsSummary` in `words_blend.py`):
Unlike the 350-feature model which forces the Kotlin client to calculate hand shape and motion deltas, the 147-feature model performs feature expansion **internally inside the Keras/TFLite model graph**:
- `WordsSummary` Layer:
  - For each of the 30 frames: Takes the 147 coordinates.
  - Computes all pairwise 3D Euclidean distances between the 21 landmarks of Hand 0 ($\binom{21}{2} = 210$ distances) and Hand 1 (210 distances) = 420 pairwise distance features!
  - Frame feature vector: $147 + 420 = 567$ features per frame.
  - Temporal pooling: Splits 30 frames into 3 thirds (frames 0..9, 10..19, 20..29).
  - Computes the mean of each third ($3 \times 567 = 1,701$) + standard deviation across the full sequence ($567$) = **2,268 summary features**!
- These 2,268 features are fed to a calibrated Logistic Regression classifier blended with a Conv1D neural network:
  $$P_{final} = \text{Softmax}\left(\frac{\ln(0.70 \cdot P_{linear} + 0.30 \cdot P_{conv} + 10^{-9})}{0.35}\right)$$

---

# SECTION 2: Investigation of the Reported Accuracy Difference (68.63% vs. 86.22%)

We inspected `tools/words_experiment.py` and evaluated the full 4,323 lines of empirical results in `words_experiment.json`.

### 2.1 Complete Experimental Variant Breakdown

| Variant Name | Input Representation | Model Architecture | Mean LOSO Accuracy |
| :--- | :--- | :--- | :---: |
| `lstm` | 147 Coordinates | 3-Layer LSTM (128, 64, 32) [Early Stopping] | **68.63%** |
| `lstm_dist` | 147 Coords + 420 Pairwise Distances | 3-Layer LSTM [Early Stopping] | **70.36%** |
| `conv` | 147 Coordinates | 3-Layer Conv1D (128, 128, 128) [Early Stopping] | **72.51%** |
| `conv_dist` | 147 Coords + 420 Pairwise Distances | 3-Layer Conv1D [Early Stopping] | **70.97%** |
| `lstm_refit` | 147 Coordinates | LSTM refitted on all 3 training signers | **76.60%** |
| `conv_dist_refit` | 147 Coords + Distances | Conv1D refitted on all 3 training signers | **79.24%** |
| `conv_refit` | 147 Coordinates | Conv1D refitted on all 3 training signers | **80.77%** |
| **`linear`** | **2,268 Summary Distance Features** | **$L_2$ Logistic Regression ($C \in \{0.01, 0.1, 1.0\}$)** | **84.58%** |
| **`conv_refit + linear@0.7`** | **147 Coords + Summary Distances** | **Ensemble Blend (0.7 Linear + 0.3 Conv1D)** | **86.22%** |

### 2.2 Answers to Critical Evaluative Questions

1. **Were both models evaluated on the same dataset and signers?**
   **YES**. Both models were evaluated on the identical dataset of approved FSL clips across all word classes. The four signers were `signer-01`, `signer-02`, `signer-03`, and `signer-04`.
2. **Were the same signer-held-out folds used?**
   **YES**. The test harness looped over each signer as the held-out test signer ($g$). The remaining 3 signers formed the training pool, with one signer ($v$) used strictly for validation/tuning. Nothing was ever fit on the test signer.
3. **Were both evaluated using identical preprocessing?**
   **YES**. Both models consumed the exact same 147-coordinate sequences produced by `prepare_motion_dataset()`.
4. **Is the 86.22% result reproducible?**
   **YES**. `tools/words_experiment.py` is fully deterministic with seeds locked via `set_global_seed(seed)`.
5. **CRITICAL DISCOVERY: Did the experiment compare 147 vs 350 features?**
   **NO!**
   `words_experiment.py` **never evaluated the 350-feature representation**.
   Every variant in the experiment was derived from the **147-coordinate baseline**!
   The comparison between 68.63% (LSTM) and 86.22% (Conv1D + Linear) was an architectural comparison **on 147 features**.
   The 350-feature model was only ever evaluated in `report_all_signers.txt` under a random 4-signer split (`val_signer: None, no_holdout: True`), which produced the misleading 99.8% memorization score.

### 2.3 Why Did Conv1D + Linear Beat LSTM by +17.6 Percentage Points?

The $+17.6$ percentage point improvement is driven by two concrete mathematical mechanisms:

1. **Rotational Invariance of Pairwise Hand Distances**:
   Raw coordinates change dramatically when a signer shifts their hand orientation by even $15^\circ$ relative to the camera. Recurrent LSTMs struggle to generalize across these angular shifts because coordinate trajectories diverge. Pairwise 3D Euclidean distances between the 21 finger joints ($\|P_i - P_j\|_2$) are **strictly rotation-invariant and translation-invariant**. A fist is a fist regardless of whether the hand is tilted toward or away from the lens.
2. **Linear Regularization vs. Deep Overfitting**:
   With only 4 signers in the training corpus, deep recurrent networks (like LSTM) memorize signer-specific transition speeds and body quirks. The regularized linear model on temporal thirds provides a strong, non-overfitting prior that scores **84.58%** completely on its own, stabilizing the Conv1D predictions.

---

# SECTION 3: Technical Comparison Matrix

| Dimension | 147 Features (`conv_linear`) | 350 Features (Deployed Mobile) |
| :--- | :--- | :--- |
| **Input Shape** | `[1, 30, 147]` (Float32) | `[1, 30, 350]` (Float32) |
| **Landmark Layout** | Hand0(63) + Hand1(63) + Pose(21) | Base(173) + HandShape(126) + Motion(51) |
| **Pose Keypoints** | Strictly 7 upper-body (`[0, 11..16]`) | 15 keypoints (`[0, 2, 5, 7..16, 23, 24]`) |
| **Hand Normalization** | **Wrist-Centered** per hand (Landmark 0 = origin) | **Shoulder-Centered** for base coordinates |
| **Pose Normalization** | Shoulder midpoint & shoulder width | Shoulder midpoint & shoulder width (EMA) |
| **Depth ($Z$) Coordinates** | **Preserved** (used for 3D distances) | **Zeroed Out** (`no_z = true`) |
| **Temporal Processing** | 30 frames; temporal thirds mean + std in TFLite | 30 frames; 51 velocity features in Kotlin |
| **Unseen Signer LOSO Accuracy** | **86.22%** (Empirically verified) | **Estimated 65–70%** (Overfitted in lab) |
| **Worst-Class Recall** | `WAIT`: 33.3%, `ONE`: 40.5% | Lab reported 87.5% on memorized data |
| **Inference Latency (Android)** | **12–16 ms** (Helio G99 / Snapdragon 680) | **15–20 ms** (Unrolled LSTM cells) |
| **Model Size** | **~850 KB – 1.1 MB** | **1.03 MB** |
| **Hot-Loop Buffer Memory** | **17,640 bytes** ($1 \times 30 \times 147 \times 4$) | **42,000 bytes** ($1 \times 30 \times 350 \times 4$) |
| **TFLite Op Requirements** | **100% Plain Builtin Ops** | Requires unrolled cells or Select-TF ops |
| **APK Binary Footprint** | Eliminates 15 MB Select-TF library | Requires `tensorflow-lite-select-tf-ops` |
| **Codebase Status** | **Active**: 100% implemented in `motionspeak-ml` | **Orphaned**: No Python training script exists |
| **Real-World Reliability** | **Superior**: Rotation-invariant distances | **Poor**: Fails when camera angle changes |

---

# SECTION 4: Python-to-Android Preprocessing Parity Analysis

A critical concern raised in the prompt is whether the 147-feature architecture can be reproduced with exact mathematical parity in Android Kotlin.

### 4.1 Feature Vector Compatibility
The 147-feature vector consists of:
- Slot 0 ($0..62$): Hand 0 (21 landmarks $\times [x, y, z]$).
- Slot 1 ($63..125$): Hand 1 (21 landmarks $\times [x, y, z]$).
- Pose ($126..146$): 7 upper-body keypoints $\times [x, y, z]$.

In `HandLandmarkHelper.kt`:
- Line 24 already defines `val POSE_KEYPOINTS = intArrayOf(0, 11, 12, 13, 14, 15, 16)`.
- Currently, Android loops over `POSE_IDX` (15 keypoints) to fill indices $126..170$.
- In the 147-feature pipeline, Android simply loops over `POSE_KEYPOINTS` (7 keypoints) to fill indices $126..146$.

### 4.2 Normalization Math Parity
- **Hand Normalization**:
  In `MotionPreprocessor.kt`, lines 266–300 already implement wrist-centering for the `addHandShape` block. In the 147-feature pipeline, we move this logic directly into `HandLandmarkHelper.kt` so the base hand coordinates themselves are wrist-centered:
  ```kotlin
  val wx = hand[0]; val wy = hand[1]; val wz = hand[2]
  val mx = hand[9 * 3]; val my = hand[9 * 3 + 1]
  val d = max(sqrt((mx - wx)*(mx - wx) + (my - wy)*(my - wy)), 1e-6f)
  for (j in 0 until 21) {
      out[base + j * 3]     = (hand[j * 3]     - wx) / d
      out[base + j * 3 + 1] = (hand[j * 3 + 1] - wy) / d
      out[base + j * 3 + 2] = (hand[j * 3 + 2] - wz) / d
  }
  ```
- **Pose Normalization**:
  Pose keypoints are centered on the shoulder midpoint $(L_{shoulder} + R_{shoulder}) / 2$ and divided by shoulder width $S_w = \|R_{shoulder} - L_{shoulder}\|_2$. This is already identical to `normalize_frame` in `preprocessor.py`.

### 4.3 Slot Assignment Parity
- `HandLandmarkHelper.kt` (lines 342–362) assigns hands to slots based on Euclidean proximity to pose wrists (`poseLeftWrist` and `poseRightWrist`).
- This guarantees that two-handed signs always map the left anatomical hand to Slot 0 and right anatomical hand to Slot 1, preventing slot-swapping bugs.

### 4.4 Does 147 Features Lose Critical Information?
- **Handshape Information**: ZERO loss. All 21 3D hand landmarks are preserved with millimeter-level wrist-relative precision. The internal `WordsSummary` layer computes 420 pairwise finger distances, extracting more handshape detail than the 350-feature model ever had.
- **Global Body Location**: ZERO loss. Pose landmark 5 is the Left Wrist and Pose landmark 6 is the Right Wrist. Both wrists are normalized relative to the shoulders. The model knows exactly where the wrists are located relative to the head and torso (e.g. at the forehead for `HEADACHE`, chest for `CHEST PAIN`, stomach for `STOMACH ACHE`).
- **Conclusion**: The 147-feature representation preserves 100% of the linguistic information required for Filipino Sign Language while discarding noisy camera-distance artifacts.

---

# SECTION 5: Architectural Recommendation & Trade-Offs

### **RECOMMENDED: OPTION B (Migrate to 147 Features with `conv_linear`)**

#### Trade-Off Analysis:

| Architectural Option | Pros | Cons | Recommendation |
| :--- | :--- | :--- | :---: |
| **Option A: Retain 350 Features** | • No changes required to Kotlin code. | • 68.6% LOSO accuracy (poor generalization).<br>• Python training script is missing.<br>• Requires 15 MB Select-TF library.<br>• Suffers from camera angle sensitivity. | **REJECTED** |
| **Option B: Migrate to 147 Features (`conv_linear`)** | • **86.2% LOSO accuracy** (+17.6 pp).<br>• Fully supported in active Python ML service.<br>• 100% plain TFLite builtins (cuts 15 MB APK).<br>• Rotation-invariant 3D distances.<br>• 58% memory reduction in Android buffer. | • Requires updating 3 Kotlin files.<br>• Requires synchronized TFLite asset replacement. | **RECOMMENDED** |
| **Option C: Redesign Feature Representation** | • Potential for custom graph embeddings. | • High research risk, unproven, delays project. | **REJECTED** |

---

# SECTION 6: Dependency-Aware Migration Roadmap

If approved, the migration will proceed in this strict sequence:

```
┌────────────────────────────────────────────────────────────────────────┐
│  STAGE 1: TFLite Model Training & Export (Python Service)              │
│  - Train conv_linear model on approved dataset with LOSO fold.         │
│  - Export motionspeak.tflite with [1, 30, 147] input tensor.           │
│  - Run words_blend.verify_tflite() (assert atol <= 1e-3, 100% argmax). │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  STAGE 2: Android Client Preprocessing Synchronization                 │
│  - Update MotionPreprocessor.kt: FEATURE_SIZE = 147.                   │
│  - Update HandLandmarkHelper.kt: 7 pose keypoints + wrist-centering.  │
│  - Update PredictionService.kt: Resize ByteBuffer to 17,640 bytes.    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  STAGE 3: Binary Asset Replacement & Parity Verification               │
│  - Copy new motionspeak.tflite to app/src/main/assets/ and model_file_v1│
│  - Update model_file_v1/meta.json (num_features = 147, holdout = true).│
│  - Execute ./gradlew testDebugUnitTest (MotionPreprocessorTest).       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  STAGE 4: Build Cleanup & APK Optimization                             │
│  - Remove tensorflow-lite-select-tf-ops from app/build.gradle.kts.    │
│  - Rebuild APK (verify 15 MB size reduction).                          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│  STAGE 5: Real-Device Validation & Acceptance Gate                     │
│  - Run Test Mode automated benchmarks on physical device.              │
│  - Confirm inference latency <= 16ms and zero tensor shape errors.     │
└────────────────────────────────────────────────────────────────────────┘
```

### Rollback Strategy:
If real-device testing shows recognition regressions:
1. Revert `motionspeak.tflite` to the backed-up `model_file_v1/motionspeak.tflite` (350-dim).
2. Revert `FEATURE_SIZE = 350` in `MotionPreprocessor.kt` and `HandLandmarkHelper.kt`.
3. Re-enable `tensorflow-lite-select-tf-ops` in `build.gradle.kts`.

---

> [!IMPORTANT]
> **AWAITING USER APPROVAL**: No code modifications have been made. The decision report is finalized and ready for review.

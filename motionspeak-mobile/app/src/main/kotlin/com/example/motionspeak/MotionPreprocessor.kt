package com.example.motionspeak

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Preprocessing pipeline strictly matching train.py and collect_data.py:
 * - fillGaps: Linearly interpolate missing hand stretches (<= 6 frames)
 * - trimActive: Keep only frames from first to last active hand (+/- 3 padding)
 * - resample: Evenly sample 30 frames
 * - addHandShape: 126 finger shape features relative to wrist divided by hand size
 * - noZ: Zero out camera-dependent depth values for base 171 points
 * - addMotion: 51 motion deltas computed across the 30 resampled time steps scaled by 5.0
 */
object MotionPreprocessor {
    const val SEQUENCE_LENGTH = 30
    const val RAW_FEATURES = 173
    const val FEATURE_SIZE = 350
    const val LEFT_FLAG = 171
    const val RIGHT_FLAG = 172
    const val MAX_GAP = 6
    const val PAD = 3
    const val MIN_ACTIVE = 6
    const val MOTION_SCALE = 5.0f

    fun fillGaps(frames: List<FloatArray>, maxGap: Int = MAX_GAP): List<FloatArray> {
        if (frames.size < 2) return frames.map { it.clone() }
        val out = frames.map { it.clone() }

        for (handIdx in 0..1) {
            val offset = handIdx * 63
            val flag = if (handIdx == 0) LEFT_FLAG else RIGHT_FLAG

            val present = mutableListOf<Int>()
            for (i in out.indices) {
                if (out[i][flag] > 0f) present.add(i)
            }

            for (p in 0 until present.size - 1) {
                val a = present[p]
                val b = present[p + 1]
                val gap = b - a - 1
                if (gap in 1..maxGap) {
                    for (k in 1..gap) {
                        val w = k.toFloat() / (gap + 1).toFloat()
                        val frame = out[a + k]
                        val frameA = out[a]
                        val frameB = out[b]
                        for (c in 0 until 63) {
                            frame[offset + c] = (1f - w) * frameA[offset + c] + w * frameB[offset + c]
                        }
                        frame[flag] = 1.0f
                    }
                }
            }
        }
        return out
    }

    fun trimActive(frames: List<FloatArray>, minActive: Int = MIN_ACTIVE, pad: Int = PAD): List<FloatArray>? {
        var firstActive = -1
        var lastActive = -1
        var activeCount = 0

        for (i in frames.indices) {
            val f = frames[i]
            if (f[LEFT_FLAG] > 0f || f[RIGHT_FLAG] > 0f) {
                if (firstActive == -1) firstActive = i
                lastActive = i
                activeCount++
            }
        }

        if (activeCount < minActive || firstActive == -1) return null

        val start = maxOf(0, firstActive - pad)
        val end = minOf(frames.size - 1, lastActive + pad)
        return frames.subList(start, end + 1)
    }

    fun resample(frames: List<FloatArray>, targetLength: Int = SEQUENCE_LENGTH): Array<FloatArray> {
        val n = frames.size
        val out = Array(targetLength) { FloatArray(RAW_FEATURES) }
        for (i in 0 until targetLength) {
            val idx = (i.toFloat() / (targetLength - 1).toFloat() * (n - 1).toFloat()).roundToInt().coerceIn(0, n - 1)
            System.arraycopy(frames[idx], 0, out[i], 0, RAW_FEATURES)
        }
        return out
    }

    fun prepareModelInput(
        resampled: Array<FloatArray>,
        target: Array<FloatArray>,
        numFeatures: Int = target[0].size,
        handShape: Boolean = (numFeatures >= 299),
        motion: Boolean = (numFeatures >= 350),
        noZ: Boolean = true,
        motionScale: Float = MOTION_SCALE
    ) {
        val m = resampled.size
        // 1. Copy base features
        val copyCount = minOf(RAW_FEATURES, numFeatures)
        for (i in 0 until m) {
            System.arraycopy(resampled[i], 0, target[i], 0, copyCount)
            if (noZ) {
                // Zero out depth (Z_COLS = 2, 5, 8, ... 170) -> no_z = true
                for (c in 2 until minOf(171, numFeatures) step 3) {
                    target[i][c] = 0f
                }
            }
        }

        // 2. Add hand shape (126 features: 173..298)
        if (handShape && numFeatures >= 299) {
            for (k in 0..1) {
                val rawOffset = k * 63
                val extraOffset = 173 + k * 63
                val flag = if (k == 0) LEFT_FLAG else RIGHT_FLAG

                for (i in 0 until m) {
                    val raw = resampled[i]
                    if (raw[flag] > 0f) {
                        val wx = raw[rawOffset]
                        val wy = raw[rawOffset + 1]
                        val wz = raw[rawOffset + 2]
                        val mx = raw[rawOffset + 9 * 3]
                        val my = raw[rawOffset + 9 * 3 + 1]
                        val mz = raw[rawOffset + 9 * 3 + 2]
                        val dx = mx - wx
                        val dy = my - wy
                        val dz = mz - wz
                        var size = sqrt(dx * dx + dy * dy + dz * dz)
                        if (size < 1e-6f) size = 1e-6f

                        for (j in 0 until 21) {
                            target[i][extraOffset + j * 3]     = (raw[rawOffset + j * 3] - wx) / size
                            target[i][extraOffset + j * 3 + 1] = (raw[rawOffset + j * 3 + 1] - wy) / size
                            target[i][extraOffset + j * 3 + 2] = (raw[rawOffset + j * 3 + 2] - wz) / size
                        }
                    } else {
                        for (c in 0 until 63) {
                            target[i][extraOffset + c] = 0f
                        }
                    }
                }
            }
        }

        // 3. Add motion features (51 features: 299..349)
        if (motion && numFeatures >= 350) {
            for (c in 299 until numFeatures) {
                target[0][c] = 0f
            }
            for (i in 1 until m) {
                val curr = resampled[i]
                val prev = resampled[i - 1]

                // 15 body/face points movement (45 features at 299..343)
                for (k in 0 until 45) {
                    target[i][299 + k] = (curr[126 + k] - prev[126 + k]) * motionScale
                }

                // Left wrist movement (3 features at 344..346)
                if (curr[LEFT_FLAG] > 0f && prev[LEFT_FLAG] > 0f) {
                    target[i][344] = (curr[0] - prev[0]) * motionScale
                    target[i][345] = (curr[1] - prev[1]) * motionScale
                    target[i][346] = (curr[2] - prev[2]) * motionScale
                } else {
                    target[i][344] = 0f
                    target[i][345] = 0f
                    target[i][346] = 0f
                }

                // Right wrist movement (3 features at 347..349)
                if (curr[RIGHT_FLAG] > 0f && prev[RIGHT_FLAG] > 0f) {
                    target[i][347] = (curr[63] - prev[63]) * motionScale
                    target[i][348] = (curr[64] - prev[64]) * motionScale
                    target[i][349] = (curr[65] - prev[65]) * motionScale
                } else {
                    target[i][347] = 0f
                    target[i][348] = 0f
                    target[i][349] = 0f
                }
            }
        }
    }
}

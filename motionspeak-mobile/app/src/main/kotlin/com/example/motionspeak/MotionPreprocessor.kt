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

    data class KinematicTrajectory(
        val dominantDirectionX: Float,
        val dominantDirectionY: Float,
        val movementMagnitude: Float,
        val isStationaryStroke: Boolean
    )

    fun analyzeKinematicTrajectory(frames: List<FloatArray>): KinematicTrajectory {
        if (frames.size < 2) return KinematicTrajectory(0f, 0f, 0f, true)
        var totalDx = 0f
        var totalDy = 0f
        var count = 0

        for (i in 1 until frames.size) {
            val curr = frames[i]
            val prev = frames[i - 1]
            if (curr[RIGHT_FLAG] > 0f && prev[RIGHT_FLAG] > 0f) {
                totalDx += (curr[63] - prev[63])
                totalDy += (curr[64] - prev[64])
                count++
            } else if (curr[LEFT_FLAG] > 0f && prev[LEFT_FLAG] > 0f) {
                totalDx += (curr[0] - prev[0])
                totalDy += (curr[1] - prev[1])
                count++
            }
        }
        if (count == 0) return KinematicTrajectory(0f, 0f, 0f, true)
        val avgDx = totalDx / count
        val avgDy = totalDy / count
        val mag = sqrt(avgDx * avgDx + avgDy * avgDy)
        return KinematicTrajectory(
            dominantDirectionX = avgDx,
            dominantDirectionY = avgDy,
            movementMagnitude = mag,
            isStationaryStroke = mag < 0.005f
        )
    }

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

    fun fillGapsTimed(frames: List<Pair<Long, FloatArray>>, maxGap: Int = MAX_GAP): List<Pair<Long, FloatArray>> {
        if (frames.size < 2) return frames.map { it.first to it.second.clone() }
        val featureArrays = frames.map { it.second }
        val filledFeatures = fillGaps(featureArrays, maxGap)
        return frames.indices.map { i -> frames[i].first to filledFeatures[i] }
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

    fun trimActiveTimed(frames: List<Pair<Long, FloatArray>>, minActive: Int = MIN_ACTIVE, pad: Int = PAD): List<Pair<Long, FloatArray>>? {
        var firstActive = -1
        var lastActive = -1
        var activeCount = 0

        for (i in frames.indices) {
            val f = frames[i].second
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

    /**
     * Resamples a sequence of frames with explicit timestamps into a fixed [targetLength] sequence
     * using physical time (∆t) continuous linear interpolation.
     * Prevents velocity squashing caused by index-based upsampling when frame rates vary or drop.
     */
    fun resampleTimed(
        timedFrames: List<Pair<Long, FloatArray>>,
        targetLength: Int = SEQUENCE_LENGTH
    ): Array<FloatArray> {
        val n = timedFrames.size
        val out = Array(targetLength) { FloatArray(RAW_FEATURES) }
        if (n == 0) return out
        if (n == 1) {
            val f0 = timedFrames[0].second
            for (i in 0 until targetLength) {
                System.arraycopy(f0, 0, out[i], 0, RAW_FEATURES)
            }
            return out
        }

        val tStart = timedFrames.first().first
        val tEnd = timedFrames.last().first
        val totalDurationMs = maxOf(1L, tEnd - tStart)

        for (i in 0 until targetLength) {
            val targetTimeMs = tStart + (i.toDouble() / (targetLength - 1).toDouble() * totalDurationMs.toDouble()).toLong()

            var k = 0
            while (k < n - 2 && timedFrames[k + 1].first < targetTimeMs) {
                k++
            }
            val kNext = minOf(k + 1, n - 1)

            val tk0 = timedFrames[k].first
            val tk1 = timedFrames[kNext].first
            val dt = tk1 - tk0

            val alpha = if (dt > 0L) {
                ((targetTimeMs - tk0).toDouble() / dt.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }

            val f0 = timedFrames[k].second
            val f1 = timedFrames[kNext].second
            val dest = out[i]

            for (c in 0 until 171) {
                dest[c] = (1f - alpha) * f0[c] + alpha * f1[c]
            }
            dest[LEFT_FLAG] = if ((1f - alpha) * f0[LEFT_FLAG] + alpha * f1[LEFT_FLAG] >= 0.5f) 1.0f else 0.0f
            dest[RIGHT_FLAG] = if ((1f - alpha) * f0[RIGHT_FLAG] + alpha * f1[RIGHT_FLAG] >= 0.5f) 1.0f else 0.0f
        }
        return out
    }

    fun resample(
        frames: List<FloatArray>,
        timestamps: List<Long>? = null,
        targetLength: Int = SEQUENCE_LENGTH
    ): Array<FloatArray> {
        if (timestamps != null && timestamps.size == frames.size && frames.isNotEmpty()) {
            return resampleTimed(timestamps.zip(frames), targetLength)
        }
        val n = frames.size
        val out = Array(targetLength) { FloatArray(RAW_FEATURES) }
        if (n == 0) return out
        if (n == 1) {
            for (i in 0 until targetLength) {
                System.arraycopy(frames[0], 0, out[i], 0, RAW_FEATURES)
            }
            return out
        }

        for (i in 0 until targetLength) {
            val t = (i.toFloat() / (targetLength - 1).toFloat()) * (n - 1).toFloat()
            val i0 = t.toInt().coerceIn(0, n - 1)
            val i1 = (i0 + 1).coerceIn(0, n - 1)
            val alpha = (t - i0).coerceIn(0f, 1f)

            val f0 = frames[i0]
            val f1 = frames[i1]
            val dest = out[i]

            // Linearly interpolate continuous spatial coordinates (0..170)
            for (c in 0 until 171) {
                dest[c] = (1f - alpha) * f0[c] + alpha * f1[c]
            }
            // Hand presence flags (LEFT_FLAG = 171, RIGHT_FLAG = 172)
            dest[LEFT_FLAG] = if ((1f - alpha) * f0[LEFT_FLAG] + alpha * f1[LEFT_FLAG] >= 0.5f) 1.0f else 0.0f
            dest[RIGHT_FLAG] = if ((1f - alpha) * f0[RIGHT_FLAG] + alpha * f1[RIGHT_FLAG] >= 0.5f) 1.0f else 0.0f
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
        motionScale: Float = MOTION_SCALE,
        signerHandScale: Float = 1.0f,
        signerSpeedFactor: Float = 1.0f
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
                // Guard against uninitialized landmark spikes
                for (k in 0 until 45) {
                    val currVal = curr[126 + k]
                    val prevVal = prev[126 + k]
                    if (currVal != 0f && prevVal != 0f) {
                        target[i][299 + k] = (currVal - prevVal) * motionScale
                    } else {
                        target[i][299 + k] = 0f
                    }
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

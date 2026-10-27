package com.example.motionspeak

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.sqrt

private const val TAG = "HandLandmarkHelper"

internal const val FEATURE_SIZE = 350
private const val LEFT_FLAG = 171
private const val RIGHT_FLAG = 172
private const val MOTION_SCALE = 5.0f

private val POSE_IDX = intArrayOf(0, 2, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 23, 24)

// Standard persistence for non-overlapping tracking micro-dropouts (~230ms at 30fps)
private const val MAX_PERSIST_FRAMES = 7

// Extended persistence for overlapping hands where one hand occludes the other (~1.15s at 30fps)
private const val MAX_OVERLAP_FRAMES = 35

// Proximity threshold (in normalized coordinates) to detect touching/overlapping hands
private const val OVERLAP_PROXIMITY_THRESHOLD = 0.38f

data class LandmarkResult(
    val handsDetected: Int,
    val features: FloatArray,
    val landmarks: List<FloatArray> = emptyList(),
    val faceLandmarks: List<FloatArray> = emptyList(),
    val sourceWidth: Int = 1,
    val sourceHeight: Int = 1,
    val handSlots: List<Int> = emptyList() // 0 = Left (green), 1 = Right (orange)
)

internal data class RawHandData(
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    val isReportedLeft: Boolean,
    val leftConfidence: Float,
    val detectionScore: Float = 0.5f
)

internal class HandTrackState {
    var isTracked = false
    var missingFrames = 0
    var isOverlapped = false
    var overlapFrames = 0

    // Interleaved x, y, z for 21 landmarks = 63 floats
    val smoothCoords = FloatArray(63)
    val velocity = FloatArray(63)

    // Finger positions relative to wrist for shape reconstruction during overlap
    val shapeRelative = FloatArray(63)

    fun reset() {
        isTracked = false
        missingFrames = 0
        isOverlapped = false
        overlapFrames = 0
        smoothCoords.fill(0f)
        velocity.fill(0f)
        shapeRelative.fill(0f)
    }

    fun updateDetected(rawX: FloatArray, rawY: FloatArray, rawZ: FloatArray) {
        missingFrames = 0
        isOverlapped = false
        overlapFrames = 0

        if (!isTracked) {
            isTracked = true
            for (j in 0 until 21) {
                smoothCoords[j * 3]     = rawX[j]
                smoothCoords[j * 3 + 1] = rawY[j]
                smoothCoords[j * 3 + 2] = rawZ[j]
                velocity[j * 3]     = 0f
                velocity[j * 3 + 1] = 0f
                velocity[j * 3 + 2] = 0f
            }
        } else {
            val dx = rawX[0] - smoothCoords[0]
            val dy = rawY[0] - smoothCoords[1]
            val dist = sqrt(dx * dx + dy * dy)
            // Adaptive smoothing: fast motion updates immediately, static hand dampens jitter
            val alpha = (0.75f + dist * 3.0f).coerceIn(0.75f, 0.92f)

            for (j in 0 until 21) {
                val vx = rawX[j] - smoothCoords[j * 3]
                val vy = rawY[j] - smoothCoords[j * 3 + 1]
                val vz = rawZ[j] - smoothCoords[j * 3 + 2]

                smoothCoords[j * 3]     += alpha * vx
                smoothCoords[j * 3 + 1] += alpha * vy
                smoothCoords[j * 3 + 2] += alpha * vz

                velocity[j * 3]     = vx
                velocity[j * 3 + 1] = vy
                velocity[j * 3 + 2] = vz
            }
        }

        // Cache relative finger positions to wrist
        val wx = smoothCoords[0]; val wy = smoothCoords[1]; val wz = smoothCoords[2]
        for (j in 0 until 21) {
            shapeRelative[j * 3]     = smoothCoords[j * 3] - wx
            shapeRelative[j * 3 + 1] = smoothCoords[j * 3 + 1] - wy
            shapeRelative[j * 3 + 2] = smoothCoords[j * 3 + 2] - wz
        }
    }

    fun updateOverlapped(anchorWristX: Float, anchorWristY: Float, maxOverlap: Int): Boolean {
        if (!isTracked) return false
        isOverlapped = true
        overlapFrames++
        missingFrames++

        if (overlapFrames <= maxOverlap) {
            val targetWx = anchorWristX.coerceIn(0f, 1f)
            val targetWy = anchorWristY.coerceIn(0f, 1f)

            val curWx = smoothCoords[0]
            val curWy = smoothCoords[1]
            val newWx = curWx + 0.45f * (targetWx - curWx)
            val newWy = curWy + 0.45f * (targetWy - curWy)
            val newWz = smoothCoords[2]

            smoothCoords[0] = newWx
            smoothCoords[1] = newWy
            smoothCoords[2] = newWz

            // Reconstruct all 21 finger landmarks using preserved shape relative to wrist
            for (j in 1 until 21) {
                smoothCoords[j * 3]     = (newWx + shapeRelative[j * 3]).coerceIn(0f, 1f)
                smoothCoords[j * 3 + 1] = (newWy + shapeRelative[j * 3 + 1]).coerceIn(0f, 1f)
                smoothCoords[j * 3 + 2] = newWz + shapeRelative[j * 3 + 2]
            }

            val vx = newWx - curWx
            val vy = newWy - curWy
            for (j in 0 until 21) {
                velocity[j * 3]     = vx
                velocity[j * 3 + 1] = vy
                velocity[j * 3 + 2] = 0f
            }
            return true
        } else {
            reset()
            return false
        }
    }

    fun updateMissing(maxPersist: Int): Boolean {
        if (!isTracked) return false
        missingFrames++
        if (missingFrames <= maxPersist) {
            // Keep points persistently attached during momentary dropouts
            for (j in 0 until 21) {
                smoothCoords[j * 3]     = (smoothCoords[j * 3] + velocity[j * 3] * 0.4f).coerceIn(0f, 1f)
                smoothCoords[j * 3 + 1] = (smoothCoords[j * 3 + 1] + velocity[j * 3 + 1] * 0.4f).coerceIn(0f, 1f)
                velocity[j * 3]     *= 0.5f
                velocity[j * 3 + 1] *= 0.5f
            }
            return true
        } else {
            reset()
            return false
        }
    }
}

internal class DualHandTracker(
    private val maxPersistFrames: Int = MAX_PERSIST_FRAMES,
    private val maxOverlapFrames: Int = MAX_OVERLAP_FRAMES,
    val normalizeCoordinates: Boolean = false
) {
    companion object {
        const val LEFT_FLAG_INDEX = 171
        const val RIGHT_FLAG_INDEX = 172
        const val TOTAL_FEATURES = 350
    }

    internal val leftHandState = HandTrackState()
    internal val rightHandState = HandTrackState()
    private var dualHandStreak = 0

    // Preserved offset of Left wrist relative to Right wrist during dual hand tracking
    private var lastLeftToRightOffsetX = -0.15f
    private var lastLeftToRightOffsetY = 0f

    // EMA shoulder reference for position- and scale-invariant normalization matching train.py
    private var smoothedCenterX = 0.5f
    private var smoothedCenterY = 0.45f
    private var smoothedCenterZ = 0.0f
    private var smoothedShoulderWidth = 0.25f
    private var hasShoulderRef = false

    val isShoulderRefReady: Boolean get() = hasShoulderRef
    val currentShoulderCenterX: Float get() = smoothedCenterX
    val currentShoulderCenterY: Float get() = smoothedCenterY
    val currentShoulderWidth: Float get() = smoothedShoulderWidth

    private val baseFeat = FloatArray(173)
    private val currentRawCoords = FloatArray(171)
    private val handShapeFeat = FloatArray(126)
    private val motionFeat = FloatArray(51)
    private val prevRawCoords = FloatArray(171)
    private var hasPrevRawCoords = false
    private var prevLeftActive = false
    private var prevRightActive = false

    fun reset() {
        leftHandState.reset()
        rightHandState.reset()
        dualHandStreak = 0
        lastLeftToRightOffsetX = -0.15f
        lastLeftToRightOffsetY = 0f
        smoothedCenterX = 0.5f
        smoothedCenterY = 0.45f
        smoothedCenterZ = 0.0f
        smoothedShoulderWidth = 0.25f
        hasShoulderRef = false
        hasPrevRawCoords = false
        prevLeftActive = false
        prevRightActive = false
        baseFeat.fill(0f)
        currentRawCoords.fill(0f)
        prevRawCoords.fill(0f)
        handShapeFeat.fill(0f)
        motionFeat.fill(0f)
    }

    private fun isHandsInContactOrOverlap(
        hand: RawHandData,
        otherState: HandTrackState
    ): Boolean {
        if (!otherState.isTracked) return false
        if (otherState.isOverlapped) return true

        // Wrist to wrist distance
        val distWrist = dist2D(hand.x[0], hand.y[0], otherState.smoothCoords[0], otherState.smoothCoords[1])
        if (distWrist < OVERLAP_PROXIMITY_THRESHOLD) return true

        // Palm centroid (middle MCP, index 9) distance for palm/fist contact
        val distPalm = dist2D(hand.x[9], hand.y[9], otherState.smoothCoords[9 * 3], otherState.smoothCoords[9 * 3 + 1])
        if (distPalm < 0.30f) return true

        // Middle fingertip (index 12) distance for fingertip contact
        val distTips = dist2D(hand.x[12], hand.y[12], otherState.smoothCoords[12 * 3], otherState.smoothCoords[12 * 3 + 1])
        if (distTips < 0.25f) return true

        return false
    }

    private fun computeHandSize(hand: RawHandData): Float {
        val dx = hand.x[9] - hand.x[0]
        val dy = hand.y[9] - hand.y[0]
        return sqrt(dx * dx + dy * dy)
    }

    private fun validateAndFilterDualHands(
        detected: List<RawHandData>,
        pLx: Float, pLy: Float,
        pRx: Float, pRy: Float,
        hasPose: Boolean
    ): List<RawHandData> {
        if (detected.size < 2) return detected

        val h0 = detected[0]
        val h1 = detected[1]

        // Check 1: Duplicate ghost detection of the same hand (same reported handedness and virtually identical coordinates)
        val sameReportedHandedness = h0.isReportedLeft == h1.isReportedLeft
        val wristDist = dist2D(h0.x[0], h0.y[0], h1.x[0], h1.y[0])
        val palmDist = dist2D(h0.x[9], h0.y[9], h1.x[9], h1.y[9])
        if (sameReportedHandedness && wristDist < 0.030f && palmDist < 0.040f) {
            // Duplicate bounding box: return only the higher-confidence hand
            return if (h0.detectionScore >= h1.detectionScore) listOf(h0) else listOf(h1)
        }

        val size0 = computeHandSize(h0)
        val size1 = computeHandSize(h1)

        // Check 2: Scale anomaly (tiny background false positive or immense screen artifact)
        if (size0 > 0.02f && size1 > 0.005f) {
            val ratio = size1 / size0
            if (ratio < 0.28f && h1.detectionScore < 0.70f) {
                // h1 is a tiny background false positive, not a real second hand
                return listOf(h0)
            }
            if (ratio > 3.5f && h0.detectionScore >= 0.50f) {
                // h1 is a huge artifact
                return listOf(h0)
            }
            val invRatio = size0 / size1
            if (invRatio < 0.28f && h0.detectionScore < 0.70f) {
                return listOf(h1)
            }
        }

        // Check 3: Anatomical Pose reachability (identifying what is connected to the body and what is not)
        if (hasPose) {
            val d0ToLeft = dist2D(h0.x[0], h0.y[0], pLx, pLy)
            val d0ToRight = dist2D(h0.x[0], h0.y[0], pRx, pRy)
            val d1ToLeft = dist2D(h1.x[0], h1.y[0], pLx, pLy)
            val d1ToRight = dist2D(h1.x[0], h1.y[0], pRx, pRy)

            val min0 = minOf(d0ToLeft, d0ToRight)
            val min1 = minOf(d1ToLeft, d1ToRight)

            // If one hand matches a pose wrist, but the second candidate is far disconnected from both wrists
            // and neither tracked slot was at that position:
            val prevL1 = if (leftHandState.isTracked) dist2D(h1.x[0], h1.y[0], leftHandState.smoothCoords[0], leftHandState.smoothCoords[1]) else 1f
            val prevR1 = if (rightHandState.isTracked) dist2D(h1.x[0], h1.y[0], rightHandState.smoothCoords[0], rightHandState.smoothCoords[1]) else 1f
            if (min0 < 0.30f && min1 > 0.50f && prevL1 > 0.35f && prevR1 > 0.35f) {
                return listOf(h0)
            }

            val prevL0 = if (leftHandState.isTracked) dist2D(h0.x[0], h0.y[0], leftHandState.smoothCoords[0], leftHandState.smoothCoords[1]) else 1f
            val prevR0 = if (rightHandState.isTracked) dist2D(h0.x[0], h0.y[0], rightHandState.smoothCoords[0], rightHandState.smoothCoords[1]) else 1f
            if (min1 < 0.30f && min0 > 0.50f && prevL0 > 0.35f && prevR0 > 0.35f) {
                return listOf(h1)
            }
        }

        // Both candidates are valid hands of the user
        return detected
    }

    fun process(
        detectedHands: List<RawHandData>,
        poseLandmarks: List<FloatArray>?,
        sourceWidth: Int = 1,
        sourceHeight: Int = 1
    ): LandmarkResult {
        // Update shoulder reference if pose landmarks 11 & 12 (Left/Right Shoulder) are available
        if (poseLandmarks != null && poseLandmarks.size > 12) {
            val lSh = poseLandmarks[11]
            val rSh = poseLandmarks[12]
            val cx = (lSh[0] + rSh[0]) * 0.5f
            val cy = (lSh[1] + rSh[1]) * 0.5f
            val cz = (lSh[2] + rSh[2]) * 0.5f
            val dx = lSh[0] - rSh[0]
            val dy = lSh[1] - rSh[1]
            val dz = lSh[2] - rSh[2]
            val w = sqrt(dx * dx + dy * dy + dz * dz)

            if (w > 0.05f) {
                if (!hasShoulderRef) {
                    smoothedCenterX = cx
                    smoothedCenterY = cy
                    smoothedCenterZ = cz
                    smoothedShoulderWidth = w
                    hasShoulderRef = true
                } else {
                    smoothedCenterX += 0.2f * (cx - smoothedCenterX)
                    smoothedCenterY += 0.2f * (cy - smoothedCenterY)
                    smoothedCenterZ += 0.2f * (cz - smoothedCenterZ)
                    smoothedShoulderWidth += 0.2f * (w - smoothedShoulderWidth)
                }
            }
        }

        val hasPoseWrists = poseLandmarks != null && poseLandmarks.size > 16
        val pLx = if (hasPoseWrists) poseLandmarks!![15][0] else -1f
        val pLy = if (hasPoseWrists) poseLandmarks!![15][1] else -1f
        val pRx = if (hasPoseWrists) poseLandmarks!![16][0] else -1f
        val pRy = if (hasPoseWrists) poseLandmarks!![16][1] else -1f

        // Check if both arms are active in the gesture area
        val bothPoseWristsRaised = hasPoseWrists && pLy in 0.05f..0.92f && pRy in 0.05f..0.92f

        // Filter and validate dual hand candidates to distinguish true second hand from artifacts
        val validHands = validateAndFilterDualHands(detectedHands, pLx, pLy, pRx, pRy, hasPoseWrists)

        if (validHands.size >= 2) {
            dualHandStreak++
            val h0 = validHands[0]
            val h1 = validHands[1]

            // Assignment A: h0 -> Left, h1 -> Right
            // Assignment B: h1 -> Left, h0 -> Right
            var scoreA = h0.leftConfidence + (1f - h1.leftConfidence)
            var scoreB = h1.leftConfidence + (1f - h0.leftConfidence)

            if (hasPoseWrists) {
                val costA = dist2D(h0.x[0], h0.y[0], pLx, pLy) + dist2D(h1.x[0], h1.y[0], pRx, pRy)
                val costB = dist2D(h1.x[0], h1.y[0], pLx, pLy) + dist2D(h0.x[0], h0.y[0], pRx, pRy)
                scoreA += (costB - costA) * 3.5f
            }

            if (leftHandState.isTracked && rightHandState.isTracked) {
                val prevCostA = dist2D(h0.x[0], h0.y[0], leftHandState.smoothCoords[0], leftHandState.smoothCoords[1]) +
                                dist2D(h1.x[0], h1.y[0], rightHandState.smoothCoords[0], rightHandState.smoothCoords[1])
                val prevCostB = dist2D(h1.x[0], h1.y[0], leftHandState.smoothCoords[0], leftHandState.smoothCoords[1]) +
                                dist2D(h0.x[0], h0.y[0], rightHandState.smoothCoords[0], rightHandState.smoothCoords[1])
                scoreA += (prevCostB - prevCostA) * 3.5f
            }

            val assignedLeft = if (scoreA >= scoreB) h0 else h1
            val assignedRight = if (scoreA >= scoreB) h1 else h0

            leftHandState.updateDetected(assignedLeft.x, assignedLeft.y, assignedLeft.z)
            rightHandState.updateDetected(assignedRight.x, assignedRight.y, assignedRight.z)

            // Track relative offset between Left and Right wrists
            lastLeftToRightOffsetX = leftHandState.smoothCoords[0] - rightHandState.smoothCoords[0]
            lastLeftToRightOffsetY = leftHandState.smoothCoords[1] - rightHandState.smoothCoords[1]

        } else if (validHands.size == 1) {
            val wasDualTracked = (dualHandStreak >= 1 || leftHandState.isOverlapped || rightHandState.isOverlapped) &&
                                 leftHandState.isTracked && rightHandState.isTracked
            dualHandStreak = 0

            val h0 = validHands[0]
            var leftEvidence = h0.leftConfidence

            if (hasPoseWrists) {
                val dL = dist2D(h0.x[0], h0.y[0], pLx, pLy)
                val dR = dist2D(h0.x[0], h0.y[0], pRx, pRy)
                // Strong anatomical assignment when close to one wrist and far from the other
                if (dL < 0.22f && dR > 0.35f) {
                    leftEvidence = 1.0f
                } else if (dR < 0.22f && dL > 0.35f) {
                    leftEvidence = 0.0f
                } else {
                    val poseDiff = (dR - dL) * 2.5f
                    leftEvidence += poseDiff
                }
            }

            // Hysteresis: only resist flipping if pose doesn't clearly indicate otherwise
            if (leftHandState.isTracked && !rightHandState.isTracked) {
                if (!hasPoseWrists || dist2D(h0.x[0], h0.y[0], pLx, pLy) <= dist2D(h0.x[0], h0.y[0], pRx, pRy) + 0.10f) {
                    leftEvidence += 0.30f
                }
            } else if (rightHandState.isTracked && !leftHandState.isTracked) {
                if (!hasPoseWrists || dist2D(h0.x[0], h0.y[0], pRx, pRy) <= dist2D(h0.x[0], h0.y[0], pLx, pLy) + 0.10f) {
                    leftEvidence -= 0.30f
                }
            } else if (leftHandState.isTracked && rightHandState.isTracked) {
                val dL = dist2D(h0.x[0], h0.y[0], leftHandState.smoothCoords[0], leftHandState.smoothCoords[1])
                val dR = dist2D(h0.x[0], h0.y[0], rightHandState.smoothCoords[0], rightHandState.smoothCoords[1])
                if (dL < dR - 0.04f) leftEvidence += 0.30f
                else if (dR < dL - 0.04f) leftEvidence -= 0.30f
            }

            if (leftEvidence >= 0.5f) {
                leftHandState.updateDetected(h0.x, h0.y, h0.z)

                // Overlap can ONLY happen if dual hands were actively tracked prior to this frame!
                val rightArmRaised = !hasPoseWrists || (pRy in 0.05f..0.88f)
                val isOverlap = wasDualTracked && rightArmRaised && (
                    isHandsInContactOrOverlap(h0, rightHandState) || (hasPoseWrists && bothPoseWristsRaised)
                )

                if (isOverlap) {
                    val relAnchorX = h0.x[0] - lastLeftToRightOffsetX
                    val relAnchorY = h0.y[0] - lastLeftToRightOffsetY
                    val anchorX = if (hasPoseWrists && pRx > 0f && dist2D(pRx, pRy, relAnchorX, relAnchorY) < 0.25f) {
                        relAnchorX * 0.75f + pRx * 0.25f
                    } else {
                        relAnchorX
                    }
                    val anchorY = if (hasPoseWrists && pRy > 0f && dist2D(pRx, pRy, relAnchorX, relAnchorY) < 0.25f) {
                        relAnchorY * 0.75f + pRy * 0.25f
                    } else {
                        relAnchorY
                    }
                    rightHandState.updateOverlapped(anchorX, anchorY, maxOverlapFrames)
                } else {
                    // Single hand mode: do not create or preserve duplicate ghost hand
                    rightHandState.reset()
                }
            } else {
                rightHandState.updateDetected(h0.x, h0.y, h0.z)

                // Overlap can ONLY happen if dual hands were actively tracked prior to this frame!
                val leftArmRaised = !hasPoseWrists || (pLy in 0.05f..0.88f)
                val isOverlap = wasDualTracked && leftArmRaised && (
                    isHandsInContactOrOverlap(h0, leftHandState) || (hasPoseWrists && bothPoseWristsRaised)
                )

                if (isOverlap) {
                    val relAnchorX = h0.x[0] + lastLeftToRightOffsetX
                    val relAnchorY = h0.y[0] + lastLeftToRightOffsetY
                    val anchorX = if (hasPoseWrists && pLx > 0f && dist2D(pLx, pLy, relAnchorX, relAnchorY) < 0.25f) {
                        relAnchorX * 0.75f + pLx * 0.25f
                    } else {
                        relAnchorX
                    }
                    val anchorY = if (hasPoseWrists && pLy > 0f && dist2D(pLx, pLy, relAnchorX, relAnchorY) < 0.25f) {
                        relAnchorY * 0.75f + pLy * 0.25f
                    } else {
                        relAnchorY
                    }
                    leftHandState.updateOverlapped(anchorX, anchorY, maxOverlapFrames)
                } else {
                    // Single hand mode: do not create or preserve duplicate ghost hand
                    leftHandState.reset()
                }
            }
        } else {
            // No detected hands in this frame
            val wasBothTracked = leftHandState.isTracked && rightHandState.isTracked
            val distBetweenHands = if (wasBothTracked) {
                dist2D(leftHandState.smoothCoords[0], leftHandState.smoothCoords[1],
                       rightHandState.smoothCoords[0], rightHandState.smoothCoords[1])
            } else 1.0f

            val isOverlapContact = wasBothTracked && (
                distBetweenHands < OVERLAP_PROXIMITY_THRESHOLD ||
                leftHandState.isOverlapped ||
                rightHandState.isOverlapped ||
                (hasPoseWrists && bothPoseWristsRaised)
            )

            if (isOverlapContact && (leftHandState.isOverlapped || rightHandState.isOverlapped)) {
                val anchorLx = if (hasPoseWrists && pLx > 0f) pLx else leftHandState.smoothCoords[0]
                val anchorLy = if (hasPoseWrists && pLy > 0f) pLy else leftHandState.smoothCoords[1]
                val anchorRx = if (hasPoseWrists && pRx > 0f) pRx else rightHandState.smoothCoords[0]
                val anchorRy = if (hasPoseWrists && pRy > 0f) pRy else rightHandState.smoothCoords[1]

                leftHandState.updateOverlapped(anchorLx, anchorLy, maxOverlapFrames)
                rightHandState.updateOverlapped(anchorRx, anchorRy, maxOverlapFrames)
            } else {
                leftHandState.updateMissing(maxPersistFrames)
                rightHandState.updateMissing(maxPersistFrames)
            }
        }

        val leftActive = leftHandState.isTracked
        val rightActive = rightHandState.isTracked

        baseFeat.fill(0f)
        currentRawCoords.fill(0f)
        handShapeFeat.fill(0f)
        motionFeat.fill(0f)

        val effCenterX = if (hasShoulderRef) smoothedCenterX else 0.5f
        val effCenterY = if (hasShoulderRef) smoothedCenterY else 0.45f
        val effCenterZ = if (hasShoulderRef) smoothedCenterZ else 0.0f
        val effWidth = if (hasShoulderRef && smoothedShoulderWidth > 1e-4f) smoothedShoulderWidth else 0.25f

        if (leftActive) {
            baseFeat[LEFT_FLAG] = 1.0f
            val coords = leftHandState.smoothCoords
            for (j in 0 until 21) {
                val x = coords[j * 3]
                val y = coords[j * 3 + 1]
                val z = coords[j * 3 + 2]
                if (normalizeCoordinates) {
                    baseFeat[j * 3]     = (x - effCenterX) / effWidth
                    baseFeat[j * 3 + 1] = (y - effCenterY) / effWidth
                    baseFeat[j * 3 + 2] = (z - effCenterZ) / effWidth
                } else {
                    baseFeat[j * 3]     = x
                    baseFeat[j * 3 + 1] = y
                    baseFeat[j * 3 + 2] = z
                }
                currentRawCoords[j * 3]     = baseFeat[j * 3]
                currentRawCoords[j * 3 + 1] = baseFeat[j * 3 + 1]
                currentRawCoords[j * 3 + 2] = baseFeat[j * 3 + 2]
            }
        }

        if (rightActive) {
            baseFeat[RIGHT_FLAG] = 1.0f
            val coords = rightHandState.smoothCoords
            for (j in 0 until 21) {
                val x = coords[j * 3]
                val y = coords[j * 3 + 1]
                val z = coords[j * 3 + 2]
                if (normalizeCoordinates) {
                    baseFeat[63 + j * 3]     = (x - effCenterX) / effWidth
                    baseFeat[63 + j * 3 + 1] = (y - effCenterY) / effWidth
                    baseFeat[63 + j * 3 + 2] = (z - effCenterZ) / effWidth
                } else {
                    baseFeat[63 + j * 3]     = x
                    baseFeat[63 + j * 3 + 1] = y
                    baseFeat[63 + j * 3 + 2] = z
                }
                currentRawCoords[63 + j * 3]     = baseFeat[63 + j * 3]
                currentRawCoords[63 + j * 3 + 1] = baseFeat[63 + j * 3 + 1]
                currentRawCoords[63 + j * 3 + 2] = baseFeat[63 + j * 3 + 2]
            }
        }

        if (poseLandmarks != null) {
            for (k in POSE_IDX.indices) {
                val idx = POSE_IDX[k]
                if (idx < poseLandmarks.size) {
                    val p = poseLandmarks[idx]
                    val px = p[0]
                    val py = p[1]
                    val pz = p[2]
                    if (normalizeCoordinates) {
                        baseFeat[126 + k * 3]     = (px - effCenterX) / effWidth
                        baseFeat[126 + k * 3 + 1] = (py - effCenterY) / effWidth
                        baseFeat[126 + k * 3 + 2] = (pz - effCenterZ) / effWidth
                    } else {
                        baseFeat[126 + k * 3]     = px
                        baseFeat[126 + k * 3 + 1] = py
                        baseFeat[126 + k * 3 + 2] = pz
                    }
                    currentRawCoords[126 + k * 3]     = baseFeat[126 + k * 3]
                    currentRawCoords[126 + k * 3 + 1] = baseFeat[126 + k * 3 + 1]
                    currentRawCoords[126 + k * 3 + 2] = baseFeat[126 + k * 3 + 2]
                }
            }
        }

        for (i in 0 until 171 step 3) baseFeat[i + 2] = 0f

        if (leftActive) {
            val coords = leftHandState.smoothCoords
            val wx = coords[0]; val wy = coords[1]; val wz = coords[2]
            val mx = coords[9 * 3]; val my = coords[9 * 3 + 1]; val mz = coords[9 * 3 + 2]
            val dx = mx - wx; val dy = my - wy; val dz = mz - wz
            var size = sqrt(dx * dx + dy * dy + dz * dz)
            if (size < 1e-6f) size = 1e-6f

            for (j in 0 until 21) {
                handShapeFeat[j * 3]     = (coords[j * 3] - wx) / size
                handShapeFeat[j * 3 + 1] = (coords[j * 3 + 1] - wy) / size
                handShapeFeat[j * 3 + 2] = (coords[j * 3 + 2] - wz) / size
            }
        }

        if (rightActive) {
            val coords = rightHandState.smoothCoords
            val wx = coords[0]; val wy = coords[1]; val wz = coords[2]
            val mx = coords[9 * 3]; val my = coords[9 * 3 + 1]; val mz = coords[9 * 3 + 2]
            val dx = mx - wx; val dy = my - wy; val dz = mz - wz
            var size = sqrt(dx * dx + dy * dy + dz * dz)
            if (size < 1e-6f) size = 1e-6f

            for (j in 0 until 21) {
                handShapeFeat[63 + j * 3]     = (coords[j * 3] - wx) / size
                handShapeFeat[63 + j * 3 + 1] = (coords[j * 3 + 1] - wy) / size
                handShapeFeat[63 + j * 3 + 2] = (coords[j * 3 + 2] - wz) / size
            }
        }

        if (hasPrevRawCoords) {
            for (k in 0 until 15) {
                motionFeat[k * 3]     = (currentRawCoords[126 + k * 3] - prevRawCoords[126 + k * 3]) * MOTION_SCALE
                motionFeat[k * 3 + 1] = (currentRawCoords[126 + k * 3 + 1] - prevRawCoords[126 + k * 3 + 1]) * MOTION_SCALE
                motionFeat[k * 3 + 2] = (currentRawCoords[126 + k * 3 + 2] - prevRawCoords[126 + k * 3 + 2]) * MOTION_SCALE
            }
            if (leftActive && prevLeftActive) {
                motionFeat[45] = (currentRawCoords[0] - prevRawCoords[0]) * MOTION_SCALE
                motionFeat[46] = (currentRawCoords[1] - prevRawCoords[1]) * MOTION_SCALE
                motionFeat[47] = (currentRawCoords[2] - prevRawCoords[2]) * MOTION_SCALE
            }
            if (rightActive && prevRightActive) {
                motionFeat[48] = (currentRawCoords[63] - prevRawCoords[63]) * MOTION_SCALE
                motionFeat[49] = (currentRawCoords[64] - prevRawCoords[64]) * MOTION_SCALE
                motionFeat[50] = (currentRawCoords[65] - prevRawCoords[65]) * MOTION_SCALE
            }
        }
        System.arraycopy(currentRawCoords, 0, prevRawCoords, 0, 171)
        hasPrevRawCoords = true
        prevLeftActive = leftActive
        prevRightActive = rightActive

        val finalFeatures = FloatArray(FEATURE_SIZE)
        System.arraycopy(baseFeat, 0, finalFeatures, 0, 173)
        System.arraycopy(handShapeFeat, 0, finalFeatures, 173, 126)
        System.arraycopy(motionFeat, 0, finalFeatures, 299, 51)

        val overlayLandmarks = ArrayList<FloatArray>(2)
        val overlaySlots = ArrayList<Int>(2)

        if (leftActive) {
            val coords = FloatArray(42)
            val smooth = leftHandState.smoothCoords
            for (j in 0 until 21) {
                coords[j * 2]     = smooth[j * 3]
                coords[j * 2 + 1] = smooth[j * 3 + 1]
            }
            overlayLandmarks.add(coords)
            overlaySlots.add(0)
        }

        if (rightActive) {
            val coords = FloatArray(42)
            val smooth = rightHandState.smoothCoords
            for (j in 0 until 21) {
                coords[j * 2]     = smooth[j * 3]
                coords[j * 2 + 1] = smooth[j * 3 + 1]
            }
            overlayLandmarks.add(coords)
            overlaySlots.add(1)
        }

        val overlayFaceLandmarks = ArrayList<FloatArray>(1)
        if (poseLandmarks != null && poseLandmarks.size > 10) {
            val coords = FloatArray(22)
            for (j in 0..10) {
                coords[j * 2]     = poseLandmarks[j][0]
                coords[j * 2 + 1] = poseLandmarks[j][1]
            }
            overlayFaceLandmarks.add(coords)
        }

        val totalHands = (if (leftActive) 1 else 0) + (if (rightActive) 1 else 0)

        return LandmarkResult(
            handsDetected = totalHands,
            features = finalFeatures,
            landmarks = overlayLandmarks,
            faceLandmarks = overlayFaceLandmarks,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            handSlots = overlaySlots
        )
    }

    private fun dist2D(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}

class HandLandmarkHelper(
    private val context: Context,
    private val onResult: ((LandmarkResult) -> Unit)? = null
) {
    @Volatile var resultSink: ((LandmarkResult) -> Unit)? = onResult
    private var landmarker: HandLandmarker? = null
    @Volatile private var poseLandmarker: PoseLandmarker? = null
    @Volatile private var lastPoseResult: PoseLandmarkerResult? = null

    @Volatile private var lastSubmitNs = 0L
    @Volatile private var lastSourceWidth = 1
    @Volatile private var lastSourceHeight = 1
    @Volatile private var lastFrameTimeMs = 0L
    private var frameCounter = 0L

    private val tracker = DualHandTracker(MAX_PERSIST_FRAMES, MAX_OVERLAP_FRAMES, normalizeCoordinates = true)

    init {
        try {
            val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(Delegate.GPU).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.30f)
                .setMinHandPresenceConfidence(0.30f)
                .setMinTrackingConfidence(0.30f)
                .setResultListener { result, _ -> resultSink?.invoke(parseResult(result, lastPoseResult)) }
                .build()
            landmarker = HandLandmarker.createFromOptions(context, handOptions)
        } catch (e: Exception) {
            Log.w(TAG, "HandLandmarker GPU init failed, trying CPU: ${e.message}")
            try {
                val handOptionsCpu = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.30f)
                    .setMinHandPresenceConfidence(0.30f)
                    .setMinTrackingConfidence(0.30f)
                    .setResultListener { result, _ -> resultSink?.invoke(parseResult(result, lastPoseResult)) }
                    .build()
                landmarker = HandLandmarker.createFromOptions(context, handOptionsCpu)
            } catch (e2: Exception) {
                Log.e(TAG, "HandLandmarker CPU init failed: ${e2.message}", e2)
            }
        }

        try {
            val poseOptions = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.GPU).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.35f)
                .setMinPosePresenceConfidence(0.35f)
                .setMinTrackingConfidence(0.35f)
                .setResultListener { result, _ -> lastPoseResult = result }
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(context, poseOptions)
        } catch (e: Exception) {
            Log.w(TAG, "PoseLandmarker GPU init failed, trying CPU: ${e.message}")
            try {
                val poseOptionsCpu = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.35f)
                    .setMinPosePresenceConfidence(0.35f)
                    .setMinTrackingConfidence(0.35f)
                    .setResultListener { result, _ -> lastPoseResult = result }
                    .build()
                poseLandmarker = PoseLandmarker.createFromOptions(context, poseOptionsCpu)
            } catch (e2: Exception) {
                Log.e(TAG, "PoseLandmarker CPU init failed: ${e2.message}", e2)
            }
        }
    }

    fun detectAsync(bitmap: Bitmap, frameTimestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        lastSourceWidth = bitmap.width
        lastSourceHeight = bitmap.height
        lastSubmitNs = System.nanoTime()

        val ts = maxOf(frameTimestampMs, lastFrameTimeMs + 1)
        lastFrameTimeMs = ts

        try {
            landmarker?.detectAsync(mpImage, ts)
        } catch (e: Exception) {
            Log.e(TAG, "hand detectAsync error: ${e.message}")
        }

        frameCounter++
        // Submit pose on alternate frames to reduce GPU contention and allow hand tracking maximum speed
        if (frameCounter % 2L == 0L) {
            try {
                poseLandmarker?.detectAsync(mpImage, ts)
            } catch (e: Exception) {
                Log.e(TAG, "pose detectAsync error: ${e.message}")
            }
        }
    }

    private fun parseResult(
        result: HandLandmarkerResult,
        pose: PoseLandmarkerResult?
    ): LandmarkResult {
        val numRawHands = minOf(result.landmarks().size, 2)
        val detectedHands = ArrayList<RawHandData>(numRawHands)
        for (i in 0 until numRawHands) {
            val lms = result.landmarks()[i]
            val x = FloatArray(21)
            val y = FloatArray(21)
            val z = FloatArray(21)
            for (j in 0 until 21) {
                x[j] = lms[j].x()
                y[j] = lms[j].y()
                z[j] = lms[j].z()
            }
            val cat = result.handednesses().getOrNull(i)?.getOrNull(0)
            val isLeft = cat?.categoryName() == "Left"
            val score = cat?.score() ?: 0.5f
            val leftConf = if (isLeft) score else (1.0f - score)
            detectedHands.add(RawHandData(x, y, z, isLeft, leftConf, score))
        }

        val poseLms = pose?.landmarks()?.firstOrNull()
        val poseCoords: List<FloatArray>? = poseLms?.map {
            floatArrayOf(it.x(), it.y(), it.z())
        }

        return tracker.process(detectedHands, poseCoords, lastSourceWidth, lastSourceHeight)
    }

    fun reset() {
        tracker.reset()
    }

    fun close() {
        landmarker?.close()
        poseLandmarker?.close()
        reset()
    }

    object Cache {
        @Volatile private var instance: HandLandmarkHelper? = null
        private val buildLock = Any()

        @Volatile private var prewarmInFlight = false

        fun prewarm(context: Context) {
            if (instance != null || prewarmInFlight) return
            val appCtx = context.applicationContext
            synchronized(buildLock) {
                if (instance != null || prewarmInFlight) return
                prewarmInFlight = true
            }
            Thread({
                try {
                    synchronized(buildLock) {
                        if (instance == null) {
                            instance = HandLandmarkHelper(appCtx)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "prewarm failed: ${e.message}", e)
                } finally {
                    prewarmInFlight = false
                }
            }, "sigla-prewarm").apply { isDaemon = true; start() }
        }

        fun acquire(context: Context, onResult: (LandmarkResult) -> Unit): HandLandmarkHelper {
            instance?.let {
                it.reset()
                return install(it, onResult)
            }
            synchronized(buildLock) {
                instance?.let {
                    it.reset()
                    return install(it, onResult)
                }
                val created = HandLandmarkHelper(context.applicationContext, onResult)
                instance = created
                return created
            }
        }

        @Synchronized
        private fun install(helper: HandLandmarkHelper, onResult: (LandmarkResult) -> Unit): HandLandmarkHelper {
            helper.resultSink = onResult
            return helper
        }

        @Synchronized
        fun release(sink: ((LandmarkResult) -> Unit)?) {
            val current = instance ?: return
            if (sink == null || current.resultSink === sink) {
                current.resultSink = null
                current.reset()
            }
        }
    }
}

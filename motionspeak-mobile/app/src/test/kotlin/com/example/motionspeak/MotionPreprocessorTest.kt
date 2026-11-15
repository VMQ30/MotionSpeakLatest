package com.example.motionspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class MotionPreprocessorTest {

    @Test
    fun fillGapsInterpolatesSmallGapAndSetsFlag() {
        // 10 frames: hand present at frame 2 and frame 6 (gap of 3 frames: 3, 4, 5)
        val frames = List(10) { FloatArray(MotionPreprocessor.LEGACY_RAW_FEATURES) }
        frames[2][0] = 0.20f // Left wrist X
        frames[2][MotionPreprocessor.LEFT_FLAG] = 1.0f
        frames[6][0] = 0.60f
        frames[6][MotionPreprocessor.LEFT_FLAG] = 1.0f

        val filled = MotionPreprocessor.fillGaps(frames, maxGap = 6)

        assertEquals(10, filled.size)
        // Frame 2
        assertEquals(0.20f, filled[2][0], 0.001f)
        assertEquals(1.0f, filled[2][MotionPreprocessor.LEFT_FLAG], 0.001f)

        // Frame 4 (midpoint of gap: k=2 out of gap+1=4 -> w = 0.5)
        assertEquals(0.40f, filled[4][0], 0.001f)
        assertEquals(1.0f, filled[4][MotionPreprocessor.LEFT_FLAG], 0.001f)

        // Frame 6
        assertEquals(0.60f, filled[6][0], 0.001f)
        assertEquals(1.0f, filled[6][MotionPreprocessor.LEFT_FLAG], 0.001f)

        // Frame 1 and 7 were not in a gap
        assertEquals(0.0f, filled[1][MotionPreprocessor.LEFT_FLAG], 0.001f)
        assertEquals(0.0f, filled[7][MotionPreprocessor.LEFT_FLAG], 0.001f)
    }

    @Test
    fun fillGapsIgnoresLargeGapsExceedingMaxGap() {
        val frames = List(15) { FloatArray(MotionPreprocessor.LEGACY_RAW_FEATURES) }
        frames[1][0] = 0.10f
        frames[1][MotionPreprocessor.LEFT_FLAG] = 1.0f
        frames[10][0] = 0.90f // Gap is 8 frames (2..9) > MAX_GAP (6)
        frames[10][MotionPreprocessor.LEFT_FLAG] = 1.0f

        val filled = MotionPreprocessor.fillGaps(frames, maxGap = 6)
        // Frames in between must remain 0.0f
        for (i in 2..9) {
            assertEquals(0.0f, filled[i][MotionPreprocessor.LEFT_FLAG], 0.001f)
            assertEquals(0.0f, filled[i][0], 0.001f)
        }
    }

    @Test
    fun trimActiveKeepsPaddingAndDropsTooShortSequences() {
        // Less than 6 active frames should return null
        val shortFrames = List(20) { FloatArray(MotionPreprocessor.LEGACY_RAW_FEATURES) }
        for (i in 5..8) { // Only 4 active frames
            shortFrames[i][MotionPreprocessor.RIGHT_FLAG] = 1.0f
        }
        assertNull(MotionPreprocessor.trimActive(shortFrames, minActive = 6))

        // 10 active frames (indices 10..19 in 40 frames)
        val validFrames = List(40) { FloatArray(MotionPreprocessor.LEGACY_RAW_FEATURES) }
        for (i in 10..19) {
            validFrames[i][MotionPreprocessor.RIGHT_FLAG] = 1.0f
        }
        val trimmed = MotionPreprocessor.trimActive(validFrames, minActive = 6, pad = 3)
        assertNotNull(trimmed)
        // Expected start: 10 - 3 = 7. Expected end: 19 + 3 = 22. Total = 22 - 7 + 1 = 16
        assertEquals(16, trimmed!!.size)
    }

    @Test
    fun resampleProducesTargetFrameCountWithEvenDistribution() {
        val frames = List(50) { i ->
            val arr = FloatArray(MotionPreprocessor.RAW_FEATURES)
            arr[0] = i.toFloat()
            arr
        }
        val resampled = MotionPreprocessor.resample(frames, targetLength = 30)
        assertEquals(30, resampled.size)
        // First frame must correspond to index 0
        assertEquals(0.0f, resampled[0][0], 0.001f)
        // Last frame must correspond to index 49
        assertEquals(49.0f, resampled[29][0], 0.001f)
    }

    @Test
    fun prepareModelInputGeneratesExact350FeatureMatrix() {
        val resampled = Array(30) { i ->
            val arr = FloatArray(MotionPreprocessor.LEGACY_RAW_FEATURES)
            // Left hand active
            arr[MotionPreprocessor.LEFT_FLAG] = 1.0f
            // Left wrist at (0.2, 0.4, 0.1)
            arr[0] = 0.20f + i * 0.01f
            arr[1] = 0.40f
            arr[2] = 0.10f
            // Left index 9 (middle MCP) at (0.20 + i * 0.01, 0.48, 0.10)
            arr[9 * 3] = arr[0]
            arr[9 * 3 + 1] = 0.48f
            arr[9 * 3 + 2] = 0.10f

            // Pose point 0 (Nose) at (0.5, 0.2, 0.0)
            arr[126] = 0.50f + i * 0.005f
            arr[127] = 0.20f
            arr[128] = 0.0f
            arr
        }

        val target = Array(30) { FloatArray(MotionPreprocessor.LEGACY_FEATURE_SIZE) }
        MotionPreprocessor.prepareModelInput(resampled, target, numFeatures = MotionPreprocessor.LEGACY_FEATURE_SIZE)

        for (i in 0 until 30) {
            // 1. Base 173 features
            assertEquals(1.0f, target[i][MotionPreprocessor.LEFT_FLAG], 0.001f)
            // Depth (Z_COLS: 2, 5, 8...) must be 0.0f (no_z = true)
            for (c in 2 until 171 step 3) {
                assertEquals(0.0f, target[i][c], 0.0001f)
            }

            // 2. Hand shape features (173..298)
            // Landmark 0 relative to wrist must be (0, 0, 0)
            assertEquals(0.0f, target[i][173], 0.001f)
            assertEquals(0.0f, target[i][174], 0.001f)
            assertEquals(0.0f, target[i][175], 0.001f)
            // Landmark 9 relative to wrist divided by size (0.08) -> Y should be 1.0f
            assertEquals(0.0f, target[i][173 + 9 * 3], 0.001f)
            assertEquals(1.0f, target[i][173 + 9 * 3 + 1], 0.01f)
            assertEquals(0.0f, target[i][173 + 9 * 3 + 2], 0.001f)
        }

        // 3. Motion features (299..349)
        // Step 0 must have zero motion
        for (c in 299 until MotionPreprocessor.LEGACY_FEATURE_SIZE) {
            assertEquals(0.0f, target[0][c], 0.0001f)
        }

        // Step 1: Pose point 0 moved by 0.005 * MOTION_SCALE (5.0f) = 0.025f
        assertEquals(0.025f, target[1][299], 0.001f)
        // Step 1: Left wrist moved by 0.01 * MOTION_SCALE (5.0f) = 0.050f
        assertEquals(0.050f, target[1][344], 0.001f)
    }

    @Test
    fun prepareModelInputGeneratesExact147FeatureMatrix() {
        val resampled = Array(30) { i ->
            val arr = FloatArray(147)
            // Hand 0 present
            arr[0] = 0.1f * i
            arr[1] = 0.2f
            arr[2] = 0.3f
            // Hand 1 present
            arr[63] = 0.4f
            arr[64] = 0.5f * i
            arr[65] = 0.6f
            // Pose present
            arr[126] = 0.7f
            arr[127] = 0.8f
            arr[128] = 0.9f
            arr
        }

        val target = Array(30) { FloatArray(147) }
        MotionPreprocessor.prepareModelInput(resampled, target, numFeatures = 147)

        for (i in 0 until 30) {
            assertEquals(147, target[i].size)
            assertEquals(0.1f * i, target[i][0], 0.001f)
            assertEquals(0.2f, target[i][1], 0.001f)
            assertEquals(0.3f, target[i][2], 0.001f) // Z preserved in 147 features
            assertEquals(0.4f, target[i][63], 0.001f)
            assertEquals(0.5f * i, target[i][64], 0.001f)
            assertEquals(0.6f, target[i][65], 0.001f)
            assertEquals(0.7f, target[i][126], 0.001f)
        }
    }

    @Test
    fun fillGapsWorksFor147FeaturesWithoutFlags() {
        val frames = List(10) { FloatArray(147) }
        // Hand present at frame 2 and frame 6
        frames[2][0] = 0.20f
        frames[2][1] = 0.30f
        frames[6][0] = 0.60f
        frames[6][1] = 0.70f

        val filled = MotionPreprocessor.fillGaps(frames, maxGap = 6)
        assertEquals(10, filled.size)
        // Midpoint at frame 4: w = 0.5
        assertEquals(0.40f, filled[4][0], 0.001f)
        assertEquals(0.50f, filled[4][1], 0.001f)
        // Empty frame 0 remains empty
        assertEquals(0.0f, filled[0][0], 0.001f)
    }

    @Test
    fun trimActiveWorksFor147FeaturesWithoutFlags() {
        val frames = List(30) { FloatArray(147) }
        // Active from frame 10 to 19
        for (i in 10..19) {
            frames[i][0] = 0.5f
        }
        val trimmed = MotionPreprocessor.trimActive(frames, minActive = 6, pad = 3)
        assertNotNull(trimmed)
        assertEquals(16, trimmed!!.size) // 10..19 padded by 3 -> 7..22 -> 16 frames
    }
}

package com.example.motionspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPredictionFilterTest {

    @Test
    fun computeNormalizedEntropy_uniformDistributionHasMaxEntropy() {
        // Uniform distribution over 60 classes: p_i = 1/60
        val probs = FloatArray(60) { 1f / 60f }
        val entropy = MotionPreprocessor.computeNormalizedEntropy(probs)
        assertEquals(1.0f, entropy, 0.001f)
    }

    @Test
    fun computeNormalizedEntropy_sharpDistributionHasNearZeroEntropy() {
        val probs = FloatArray(60)
        probs[0] = 1.0f
        val entropy = MotionPreprocessor.computeNormalizedEntropy(probs)
        assertEquals(0.0f, entropy, 0.001f)
    }

    @Test
    fun computeNormalizedEntropy_diffuseVsConfidentDistribution() {
        // Confident sign (top = 0.85, small tail)
        val confident = FloatArray(60)
        confident[0] = 0.85f
        confident[1] = 0.05f
        confident[2] = 0.05f
        confident[3] = 0.05f
        val hConfident = MotionPreprocessor.computeNormalizedEntropy(confident)

        // Diffuse ambiguous transition (top = 0.30, 0.25, 0.20, 0.15, 0.10)
        val diffuse = FloatArray(60)
        diffuse[0] = 0.30f
        diffuse[1] = 0.25f
        diffuse[2] = 0.20f
        diffuse[3] = 0.15f
        diffuse[4] = 0.10f
        val hDiffuse = MotionPreprocessor.computeNormalizedEntropy(diffuse)

        assertTrue("Confident entropy ($hConfident) should be much lower than diffuse ($hDiffuse)", hConfident < 0.25f)
        assertTrue("Diffuse entropy ($hDiffuse) should exceed threshold", hDiffuse > 0.35f)
    }

    @Test
    fun computeMargin_calculatesDifferenceBetweenTopTwo() {
        val probs = FloatArray(60)
        probs[5] = 0.65f
        probs[12] = 0.40f
        val margin = MotionPreprocessor.computeMargin(probs)
        assertEquals(0.25f, margin, 0.001f)
    }

    @Test
    fun evaluateConfidenceTier_demotesNarrowMarginToLowTier() {
        // High confidence (>= 0.75) always passes to HIGH
        val tierHigh = MotionPreprocessor.evaluateConfidenceTier(
            topConf = 0.82f,
            margin = 0.60f,
            normalizedEntropy = 0.15f
        )
        assertEquals(ConfidenceTier.HIGH, tierHigh)

        // Moderate confidence (0.60) with solid margin (0.25) and low entropy -> MODERATE
        val tierModPassing = MotionPreprocessor.evaluateConfidenceTier(
            topConf = 0.60f,
            margin = 0.25f,
            normalizedEntropy = 0.40f
        )
        assertEquals(ConfidenceTier.MODERATE, tierModPassing)

        // Moderate confidence (0.50) but narrow margin (0.05) -> DEMOTED TO LOW (reject ambiguous)
        val tierModNarrowMargin = MotionPreprocessor.evaluateConfidenceTier(
            topConf = 0.50f,
            margin = 0.05f,
            normalizedEntropy = 0.45f
        )
        assertEquals(ConfidenceTier.LOW, tierModNarrowMargin)

        // Moderate confidence (0.55) but high diffuse entropy (0.75) -> DEMOTED TO LOW
        val tierModHighEntropy = MotionPreprocessor.evaluateConfidenceTier(
            topConf = 0.55f,
            margin = 0.20f,
            normalizedEntropy = 0.75f
        )
        assertEquals(ConfidenceTier.LOW, tierModHighEntropy)
    }

    @Test
    fun evaluateSpatialGating_preservesAbdominalAndHeadSigns() {
        // Test 1: Abdominal sign (e.g. pregnant, diarrhea) where wrist is around Y = 1.0
        val rawAbdominal = FloatArray(MotionPreprocessor.RAW_FEATURES)
        rawAbdominal[MotionPreprocessor.RIGHT_FLAG] = 1.0f
        rawAbdominal[63] = 0.1f // X
        rawAbdominal[64] = 1.0f // Y (abdomen)
        val gateAbdominal = MotionPreprocessor.evaluateSpatialGating(rawAbdominal)
        assertTrue(gateAbdominal.hasValidSigningHand)
        assertFalse(gateAbdominal.isLapResting)

        // Test 2: Head/face sign (e.g. headache, fever) where wrist is around Y = -0.6
        val rawHead = FloatArray(MotionPreprocessor.RAW_FEATURES)
        rawHead[MotionPreprocessor.RIGHT_FLAG] = 1.0f
        rawHead[63] = 0.2f
        rawHead[64] = -0.6f
        val gateHead = MotionPreprocessor.evaluateSpatialGating(rawHead)
        assertTrue(gateHead.hasValidSigningHand)
        assertFalse(gateHead.isLapResting)
    }

    @Test
    fun evaluateSpatialGating_detectsLapRestingPosition() {
        // Hands resting deep on lap (Y = 2.5 > lapBoundary 2.0)
        val rawLap = FloatArray(MotionPreprocessor.RAW_FEATURES)
        rawLap[MotionPreprocessor.LEFT_FLAG] = 1.0f
        rawLap[MotionPreprocessor.RIGHT_FLAG] = 1.0f
        rawLap[0] = -0.5f; rawLap[1] = 2.4f // Left wrist on lap
        rawLap[63] = 0.5f; rawLap[64] = 2.5f // Right wrist on lap
        val gateLap = MotionPreprocessor.evaluateSpatialGating(rawLap)
        assertTrue(gateLap.isLapResting)
        assertFalse(gateLap.hasValidSigningHand)
    }

    @Test
    fun evaluateSpatialGating_oneHandRestingOtherSigning_isAccepted() {
        // One hand on lap (Y = 2.4), other hand signing at chest (Y = 0.2)
        val rawMixed = FloatArray(MotionPreprocessor.RAW_FEATURES)
        rawMixed[MotionPreprocessor.LEFT_FLAG] = 1.0f
        rawMixed[MotionPreprocessor.RIGHT_FLAG] = 1.0f
        rawMixed[0] = -0.5f; rawMixed[1] = 2.4f // Left wrist resting
        rawMixed[63] = 0.2f; rawMixed[64] = 0.2f // Right wrist signing at chest
        val gateMixed = MotionPreprocessor.evaluateSpatialGating(rawMixed)
        assertFalse(gateMixed.isLapResting)
        assertTrue(gateMixed.hasValidSigningHand)
        assertTrue(gateMixed.rightInSigningSpace)
        assertFalse(gateMixed.leftInSigningSpace)
    }

    @Test
    fun isDownwardRetraction_identifiesDroppingHands() {
        assertTrue(MotionPreprocessor.isDownwardRetraction(currWristY = 1.20f, prevWristY = 1.10f, threshold = 0.06f))
        assertFalse(MotionPreprocessor.isDownwardRetraction(currWristY = 1.12f, prevWristY = 1.10f, threshold = 0.06f))
        assertFalse(MotionPreprocessor.isDownwardRetraction(currWristY = 0.90f, prevWristY = 1.10f, threshold = 0.06f))
    }
}

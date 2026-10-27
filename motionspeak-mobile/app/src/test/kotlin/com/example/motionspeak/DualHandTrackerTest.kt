package com.example.motionspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DualHandTrackerTest {

    private lateinit var tracker: DualHandTracker

    @Before
    fun setUp() {
        tracker = DualHandTracker(maxPersistFrames = 7, maxOverlapFrames = 35)
    }

    private fun createHand(
        wristX: Float,
        wristY: Float,
        wristZ: Float = 0f,
        isLeft: Boolean = true,
        confidence: Float = 0.9f,
        detectionScore: Float = 0.9f
    ): RawHandData {
        val x = FloatArray(21) { wristX + it * 0.005f }
        val y = FloatArray(21) { wristY + it * 0.005f }
        val z = FloatArray(21) { wristZ }
        return RawHandData(
            x = x,
            y = y,
            z = z,
            isReportedLeft = isLeft,
            leftConfidence = if (isLeft) confidence else 1f - confidence,
            detectionScore = detectionScore
        )
    }

    @Test
    fun featureVectorHasExactly350Features() {
        val hand = createHand(0.3f, 0.4f, isLeft = true)
        val res = tracker.process(listOf(hand), null)

        assertEquals(DualHandTracker.TOTAL_FEATURES, res.features.size)
        assertEquals(1, res.handsDetected)
        assertEquals(1.0f, res.features[DualHandTracker.LEFT_FLAG_INDEX], 0.0001f)
        assertEquals(0.0f, res.features[DualHandTracker.RIGHT_FLAG_INDEX], 0.0001f)
        assertEquals(listOf(0), res.handSlots)
    }

    @Test
    fun dualHandCollisionDisambiguationAssignsBothSlots() {
        val handA = createHand(0.2f, 0.5f, isLeft = true, confidence = 0.95f)
        val handB = createHand(0.8f, 0.5f, isLeft = true, confidence = 0.55f)

        val res = tracker.process(listOf(handA, handB), null)

        assertEquals(2, res.handsDetected)
        assertEquals(1.0f, res.features[DualHandTracker.LEFT_FLAG_INDEX], 0.0001f)
        assertEquals(1.0f, res.features[DualHandTracker.RIGHT_FLAG_INDEX], 0.0001f)

        // Hand A has higher left confidence, so Hand A -> Left, Hand B -> Right
        assertEquals(0.2f, res.features[0], 0.05f)   // Left wrist X
        assertEquals(0.8f, res.features[63], 0.05f)  // Right wrist X

        assertEquals(listOf(0, 1), res.handSlots)
        assertEquals(2, res.landmarks.size)
    }

    @Test
    fun poseWristProximityDisambiguatesDualHands() {
        val handA = createHand(0.3f, 0.6f, isLeft = false, confidence = 0.51f)
        val handB = createHand(0.7f, 0.6f, isLeft = false, confidence = 0.51f)

        val pose = ArrayList<FloatArray>()
        for (i in 0 until 33) {
            when (i) {
                15 -> pose.add(floatArrayOf(0.3f, 0.62f, 0f)) // Left wrist near hand A
                16 -> pose.add(floatArrayOf(0.7f, 0.62f, 0f)) // Right wrist near hand B
                else -> pose.add(floatArrayOf(0.5f, 0.5f, 0f))
            }
        }

        val res = tracker.process(listOf(handA, handB), pose)

        assertEquals(2, res.handsDetected)
        assertEquals(1.0f, res.features[DualHandTracker.LEFT_FLAG_INDEX], 0.0001f)
        assertEquals(1.0f, res.features[DualHandTracker.RIGHT_FLAG_INDEX], 0.0001f)
        assertEquals(0.3f, res.features[0], 0.05f)
        assertEquals(0.7f, res.features[63], 0.05f)
    }

    @Test
    fun handOverlappingPreservesOccludedHandWithoutHiccups() {
        // Step 1: Both hands are active and moving towards each other (overlap)
        val leftHand = createHand(0.48f, 0.50f, isLeft = true, confidence = 0.9f)
        val rightHand = createHand(0.52f, 0.50f, isLeft = false, confidence = 0.9f)

        val res1 = tracker.process(listOf(leftHand, rightHand), null)
        assertEquals(2, res1.handsDetected)
        assertTrue(tracker.leftHandState.isTracked)
        assertTrue(tracker.rightHandState.isTracked)

        // Step 2: Hand 1 (Right) overlaps Hand 2 (Left), MediaPipe detects ONLY Hand 1
        // We simulate 25 consecutive frames of overlap (~830ms):
        val rightOnly = createHand(0.50f, 0.50f, isLeft = false, confidence = 0.9f)

        for (frame in 1..25) {
            val resOverlap = tracker.process(listOf(rightOnly), null)

            // CRITICAL TEST: Hand 2 (Left) MUST NOT disappear when overlapped by Hand 1!
            assertEquals("Frame $frame: Both hands must stay detected during overlap", 2, resOverlap.handsDetected)
            assertTrue("Frame $frame: Left hand must remain tracked during overlap", tracker.leftHandState.isTracked)
            assertTrue("Frame $frame: Left hand must be marked as overlapped", tracker.leftHandState.isOverlapped)
            assertEquals("Frame $frame: LEFT_FLAG must remain 1.0f", 1.0f, resOverlap.features[DualHandTracker.LEFT_FLAG_INDEX], 0.0001f)
            assertEquals("Frame $frame: RIGHT_FLAG must remain 1.0f", 1.0f, resOverlap.features[DualHandTracker.RIGHT_FLAG_INDEX], 0.0001f)
            assertEquals("Frame $frame: Both hands must remain in overlay", 2, resOverlap.landmarks.size)
        }

        // Step 3: Hands separate again, MediaPipe detects both hands
        val leftSeparated = createHand(0.40f, 0.50f, isLeft = true, confidence = 0.9f)
        val rightSeparated = createHand(0.60f, 0.50f, isLeft = false, confidence = 0.9f)

        val resSeparated = tracker.process(listOf(leftSeparated, rightSeparated), null)
        assertEquals(2, resSeparated.handsDetected)
        assertFalse("Left hand must exit overlap state upon redetection", tracker.leftHandState.isOverlapped)
        assertEquals(1.0f, resSeparated.features[DualHandTracker.LEFT_FLAG_INDEX], 0.0001f)
        assertEquals(1.0f, resSeparated.features[DualHandTracker.RIGHT_FLAG_INDEX], 0.0001f)
    }

    @Test
    fun nonOverlappingLoweredHandDropsCleanly() {
        // User signs with Right hand only on the right side of the screen
        val rightHand = createHand(0.85f, 0.40f, isLeft = false, confidence = 0.9f)
        val res1 = tracker.process(listOf(rightHand), null)

        assertEquals(1, res1.handsDetected)
        assertFalse(tracker.leftHandState.isTracked)
        assertTrue(tracker.rightHandState.isTracked)
        assertEquals(0.0f, res1.features[DualHandTracker.LEFT_FLAG_INDEX], 0.0001f)
        assertEquals(1.0f, res1.features[DualHandTracker.RIGHT_FLAG_INDEX], 0.0001f)

        // Lowering hand away from camera (missing frames 1..7 remain persistent to prevent micro-dropouts)
        for (f in 1..7) {
            tracker.process(emptyList(), null)
            assertTrue("Frame $f: hand should remain persistent through micro-dropouts", tracker.rightHandState.isTracked)
        }
        val resEnd = tracker.process(emptyList(), null) // frame 8 (> maxPersistFrames=7)

        assertEquals(0, resEnd.handsDetected)
        assertFalse(tracker.rightHandState.isTracked)
        assertEquals(0.0f, resEnd.features[DualHandTracker.RIGHT_FLAG_INDEX], 0.0001f)
    }

    @Test
    fun rightHandOnlyMaintainsRightSlotIdentity() {
        val rightHand = createHand(0.7f, 0.4f, isLeft = false, confidence = 0.9f)
        val res = tracker.process(listOf(rightHand), null)

        assertEquals(1, res.handsDetected)
        assertEquals(0.0f, res.features[DualHandTracker.LEFT_FLAG_INDEX], 0.0001f)
        assertEquals(1.0f, res.features[DualHandTracker.RIGHT_FLAG_INDEX], 0.0001f)
        assertEquals(listOf(1), res.handSlots)
    }

    @Test
    fun motionFeaturesAvoidFalseSpikesOnInitialDetection() {
        val hand1 = createHand(0.5f, 0.5f, isLeft = true)

        val res1 = tracker.process(listOf(hand1), null)
        assertEquals(0f, res1.features[299 + 45], 0.0001f) // left wrist motion X

        val hand2 = createHand(0.52f, 0.5f, isLeft = true)
        val res2 = tracker.process(listOf(hand2), null)

        val motionX = res2.features[299 + 45]
        assertTrue("Expected non-zero smooth motion, got $motionX", motionX > 0.05f && motionX < 0.15f)
    }

    @Test
    fun fingertipContactOverlapPreservesHand2() {
        // Both hands active, wrists far apart (0.32f) but fingertips touching
        val leftHand = createHand(0.35f, 0.50f, isLeft = true)
        // Position fingertip (index 12) near (0.50f, 0.45f)
        leftHand.x[12] = 0.49f
        leftHand.y[12] = 0.45f

        val rightHand = createHand(0.67f, 0.50f, isLeft = false)
        rightHand.x[12] = 0.51f
        rightHand.y[12] = 0.45f

        val res1 = tracker.process(listOf(leftHand, rightHand), null)
        assertEquals(2, res1.handsDetected)

        // Hand 2 is occluded at fingertips
        val rightOnly = createHand(0.67f, 0.50f, isLeft = false)
        rightOnly.x[12] = 0.51f
        rightOnly.y[12] = 0.45f

        val res2 = tracker.process(listOf(rightOnly), null)
        assertEquals("Left hand must not disappear during fingertip overlap", 2, res2.handsDetected)
        assertTrue(tracker.leftHandState.isTracked)
        assertTrue(tracker.leftHandState.isOverlapped)
    }

    @Test
    fun duplicateGhostHandDetectionFilteredToSingleHand() {
        val realHand = createHand(0.40f, 0.50f, isLeft = true, confidence = 0.95f, detectionScore = 0.95f)
        // A duplicate bounding box overlapping the same hand (wrist 0.01 away, palm 0.01 away)
        val ghostHand = createHand(0.41f, 0.505f, isLeft = true, confidence = 0.60f, detectionScore = 0.60f)

        val res = tracker.process(listOf(realHand, ghostHand), null)
        assertEquals("Ghost duplicate must be filtered out", 1, res.handsDetected)
        assertEquals(listOf(0), res.handSlots)
    }

    @Test
    fun tinyBackgroundArtifactRejectedAsSecondHand() {
        val primaryHand = createHand(0.35f, 0.50f, isLeft = true, confidence = 0.95f, detectionScore = 0.95f)
        // Set realistic hand size (wrist to MCP ~0.08)
        primaryHand.x[9] = 0.35f + 0.08f
        primaryHand.y[9] = 0.50f + 0.08f

        // Candidate 2 is a tiny noise speck (wrist to MCP = 0.005) with low score
        val noiseSpeck = createHand(0.80f, 0.20f, isLeft = false, confidence = 0.52f, detectionScore = 0.38f)
        noiseSpeck.x[9] = 0.80f + 0.005f
        noiseSpeck.y[9] = 0.20f + 0.005f

        val res = tracker.process(listOf(primaryHand, noiseSpeck), null)
        assertEquals("Tiny background artifact must not be counted as 2nd hand", 1, res.handsDetected)
        assertEquals(listOf(0), res.handSlots)
    }

    @Test
    fun disconnectedBystanderRejectedWhenPoseWristAvailable() {
        val primaryHand = createHand(0.35f, 0.55f, isLeft = true, confidence = 0.90f, detectionScore = 0.90f)
        // Candidate 2 is far away at (0.95, 0.10) (e.g. bystander in background)
        val bystander = createHand(0.95f, 0.10f, isLeft = false, confidence = 0.55f, detectionScore = 0.55f)

        val pose = ArrayList<FloatArray>()
        for (i in 0 until 33) {
            when (i) {
                15 -> pose.add(floatArrayOf(0.35f, 0.56f, 0f)) // Left wrist matches primary hand
                16 -> pose.add(floatArrayOf(0.55f, 0.88f, 0f)) // Right arm is down at waist
                else -> pose.add(floatArrayOf(0.5f, 0.5f, 0f))
            }
        }

        val res = tracker.process(listOf(primaryHand, bystander), pose)
        assertEquals("Disconnected candidate far from pose wrists must be rejected", 1, res.handsDetected)
        assertEquals(listOf(0), res.handSlots)
    }

    @Test
    fun legitimateSecondHandAcceptedAndTracked() {
        val leftHand = createHand(0.35f, 0.55f, isLeft = true, confidence = 0.90f, detectionScore = 0.90f)
        val rightHand = createHand(0.65f, 0.55f, isLeft = false, confidence = 0.90f, detectionScore = 0.90f)

        val pose = ArrayList<FloatArray>()
        for (i in 0 until 33) {
            when (i) {
                15 -> pose.add(floatArrayOf(0.35f, 0.56f, 0f))
                16 -> pose.add(floatArrayOf(0.65f, 0.56f, 0f))
                else -> pose.add(floatArrayOf(0.5f, 0.5f, 0f))
            }
        }

        val res = tracker.process(listOf(leftHand, rightHand), pose)
        assertEquals("Legitimate dual hands must both be detected", 2, res.handsDetected)
        assertEquals(listOf(0, 1), res.handSlots)
    }

    @Test
    fun singleHandNeverMistakenForAnotherHandOrSpawnsGhost() {
        val handPos = listOf(
            0.65f to 0.50f,
            0.64f to 0.49f,
            0.66f to 0.52f,
            0.63f to 0.53f,
            0.65f to 0.50f
        )

        for ((idx, pos) in handPos.withIndex()) {
            val rightHand = createHand(pos.first, pos.second, isLeft = false, confidence = 0.85f)
            val res = tracker.process(listOf(rightHand), null)

            assertEquals("Frame $idx: Exactly 1 hand must be detected for single hand", 1, res.handsDetected)
            assertFalse("Frame $idx: Left hand state must NEVER be tracked when using only Right hand", tracker.leftHandState.isTracked)
            assertTrue("Frame $idx: Right hand state must be tracked", tracker.rightHandState.isTracked)
            assertEquals("Frame $idx: LEFT_FLAG must remain 0.0f", 0.0f, res.features[DualHandTracker.LEFT_FLAG_INDEX], 0.0001f)
            assertEquals("Frame $idx: RIGHT_FLAG must be 1.0f", 1.0f, res.features[DualHandTracker.RIGHT_FLAG_INDEX], 0.0001f)
            assertEquals("Frame $idx: Overlay must have exactly 1 hand", 1, res.landmarks.size)
            assertEquals("Frame $idx: Hand slot must be 1 (Right)", listOf(1), res.handSlots)
        }
    }

    @Test
    fun shoulderNormalizationMatchesDatasetSpecification() {
        val normTracker = DualHandTracker(maxPersistFrames = 7, maxOverlapFrames = 35, normalizeCoordinates = true)

        val pose = ArrayList<FloatArray>()
        for (i in 0 until 33) {
            when (i) {
                11 -> pose.add(floatArrayOf(0.40f, 0.40f, 0.0f)) // Left shoulder
                12 -> pose.add(floatArrayOf(0.60f, 0.40f, 0.0f)) // Right shoulder
                15 -> pose.add(floatArrayOf(0.30f, 0.40f, 0.0f)) // Left wrist
                16 -> pose.add(floatArrayOf(0.70f, 0.40f, 0.0f)) // Right wrist
                else -> pose.add(floatArrayOf(0.50f, 0.50f, 0.0f))
            }
        }

        // Left hand wrist at (0.30, 0.40). Center is (0.50, 0.40), width is 0.20.
        // Expected normalized X: (0.30 - 0.50) / 0.20 = -1.0f
        // Expected normalized Y: (0.40 - 0.40) / 0.20 = 0.0f
        val leftHand = createHand(0.30f, 0.40f, isLeft = true, confidence = 0.95f)
        val res = normTracker.process(listOf(leftHand), pose)

        assertTrue(normTracker.isShoulderRefReady)
        assertEquals(0.50f, normTracker.currentShoulderCenterX, 0.01f)
        assertEquals(0.40f, normTracker.currentShoulderCenterY, 0.01f)
        assertEquals(0.20f, normTracker.currentShoulderWidth, 0.01f)

        // Feature vector receives shoulder-normalized coordinates for model
        assertEquals(-1.0f, res.features[0], 0.05f)
        assertEquals(0.0f, res.features[1], 0.05f)

        // Overlay landmarks MUST remain in raw screen coordinates [0, 1] for pixel-perfect camera rendering
        assertEquals(0.30f, res.landmarks[0][0], 0.01f)
        assertEquals(0.40f, res.landmarks[0][1], 0.01f)
    }

    @Test
    fun shoulderNormalizationPersistsAcrossFrameDropouts() {
        val normTracker = DualHandTracker(maxPersistFrames = 7, maxOverlapFrames = 35, normalizeCoordinates = true)

        val pose = ArrayList<FloatArray>()
        for (i in 0 until 33) {
            when (i) {
                11 -> pose.add(floatArrayOf(0.40f, 0.40f, 0.0f))
                12 -> pose.add(floatArrayOf(0.60f, 0.40f, 0.0f))
                else -> pose.add(floatArrayOf(0.50f, 0.50f, 0.0f))
            }
        }

        val hand = createHand(0.30f, 0.40f, isLeft = true, confidence = 0.95f)

        // Frame 1: pose available
        val res1 = normTracker.process(listOf(hand), pose)
        assertEquals(-1.0f, res1.features[0], 0.05f)

        // Frame 2: pose is null (e.g. alternate frame execution)
        val res2 = normTracker.process(listOf(hand), null)
        assertTrue(normTracker.isShoulderRefReady)
        assertEquals(-1.0f, res2.features[0], 0.05f)
    }

    @Test
    fun singleHandAnatomicalWristProximityEnforcesCorrectSlotEvenWithMisreportedHandedness() {
        val pose = ArrayList<FloatArray>()
        for (i in 0 until 33) {
            when (i) {
                15 -> pose.add(floatArrayOf(0.20f, 0.85f, 0.0f)) // Left wrist lowered at waist
                16 -> pose.add(floatArrayOf(0.70f, 0.45f, 0.0f)) // Right wrist raised for signing
                else -> pose.add(floatArrayOf(0.50f, 0.50f, 0.0f))
            }
        }

        // Hand is physically right at the right pose wrist (0.70, 0.45), but MediaPipe reported isLeft = true
        val hand = createHand(0.70f, 0.45f, isLeft = true, confidence = 0.90f)
        val res = tracker.process(listOf(hand), pose)

        // Anatomy dictates it must be assigned to Right Hand slot (index 1)
        assertEquals(1, res.handsDetected)
        assertTrue("Right hand state must be tracked", tracker.rightHandState.isTracked)
        assertFalse("Left hand state must NOT be tracked", tracker.leftHandState.isTracked)
        assertEquals(listOf(1), res.handSlots)
        assertEquals(1.0f, res.features[DualHandTracker.RIGHT_FLAG_INDEX], 0.0001f)
        assertEquals(0.0f, res.features[DualHandTracker.LEFT_FLAG_INDEX], 0.0001f)
    }
}

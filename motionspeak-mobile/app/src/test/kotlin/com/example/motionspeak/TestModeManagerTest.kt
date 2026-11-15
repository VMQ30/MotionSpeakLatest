package com.example.motionspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TestModeManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBleuScoreExactMatch() {
        val candidate = "Where is the bathroom?"
        val references = listOf(
            "Where is the bathroom?",
            "Where is the comfort room?",
            "Where is the toilet?"
        )
        val score = TestModeManager.calculateBleu(candidate, references)
        assertTrue("Exact match BLEU-1 should be ~1.0, got ${score.bleu1}", score.bleu1 >= 0.99)
        assertTrue("Exact match BLEU-4 should be ~1.0, got ${score.bleu4}", score.bleu4 >= 0.99)
        assertEquals(1.0, score.brevityPenalty, 0.001)
    }

    @Test
    fun testBleuScoreAlternativeValidReference() {
        val candidate = "Where is the toilet"
        val references = listOf(
            "Where is the bathroom?",
            "Where is the toilet?"
        )
        val score = TestModeManager.calculateBleu(candidate, references)
        assertTrue("Alternative reference BLEU-4 should be >= 0.90, got ${score.bleu4}", score.bleu4 >= 0.90)
    }

    @Test
    fun testBleuScoreEmptyCandidate() {
        val score = TestModeManager.calculateBleu("", listOf("Where is the bathroom?"))
        assertEquals(0.0, score.bleu1, 0.0001)
        assertEquals(0.0, score.bleu4, 0.0001)
    }

    @Test
    fun testSingleSignPromptsCoverage() {
        val prompts = TestModeManager.SINGLE_SIGN_PROMPTS
        assertEquals("Should provide all 60 vocabulary signs, found ${prompts.size}", 60, prompts.size)
        for (p in prompts) {
            assertTrue("Gloss should not be blank", p.gloss.isNotBlank())
            assertTrue("Translation should not be blank", p.translation.isNotBlank())
            assertNotNull(p.expression)
            assertNotNull(p.expectedAffect)
            assertTrue("Expected affect should follow [STATE] convention", p.expectedAffect.startsWith("[") && p.expectedAffect.endsWith("]"))
        }
    }

    @Test
    fun testSentencePromptsCoverageAndMultipleReferences() {
        val sentences = TestModeManager.FULL_SENTENCE_PROMPTS
        assertTrue("Should provide at least 20 continuous sentences, found ${sentences.size}", sentences.size >= 20)
        for (s in sentences) {
            assertTrue("Prompt text should not be blank", s.promptText.isNotBlank())
            assertTrue("Gloss list should not be empty", s.glosses.isNotEmpty())
            assertTrue("Must provide 2+ human translated references per sentence", s.referenceTranslations.size >= 2)
            assertNotNull(s.expectedAffect)
        }
    }

    @Test
    fun testBreakingPointPromptsCoverage() {
        val signs = TestModeManager.BREAKING_POINT_SIGNS
        assertEquals("Should provide 10 breaking point signs", 10, signs.size)
        assertTrue("Should include HELLO", signs.contains("HELLO"))
        assertTrue("Should include EMERGENCY", signs.contains("EMERGENCY"))
    }

    @Test
    fun testLogFileLifecycleAndDeletionOnDisable() {
        val baseDir = tempFolder.newFolder("mock_files")

        // 1. Initially, no logs exist
        assertFalse("Should not have logs initially", TestModeManager.hasLogsInDir(baseDir))

        // 2. Initializing file writes header only
        val logFile = TestModeManager.getLogFile(baseDir)
        assertTrue(logFile.exists())
        assertFalse("Only header exists, hasLogs should still be false", TestModeManager.hasLogsInDir(baseDir))

        // 3. Appending a telemetry row creates active logs
        logFile.appendText("2026-11-12T12:00:00.000Z,1000,test1_single_signs,SALAMAT,,SALAMAT,,0.95,HIGH,SALAMAT:0.95,0.88,15.2,28.4,18.1,140,28.5,350.0,1.00,0,0,Plain wall,Signer_01,Mid-range,GPU,1,,,,\n")
        assertTrue("HasLogs should be true after logging a trial", TestModeManager.hasLogsInDir(baseDir))

        // 4. Turning off test mode clears all logs and deletes the folder
        TestModeManager.clearLogsInDir(baseDir)
        assertFalse("HasLogs should be false after clearLogs", TestModeManager.hasLogsInDir(baseDir))
        assertFalse("Log directory should be deleted", File(baseDir, "test_mode_logs").exists())
    }

    @Test
    fun testOperationalBoundaryVariables() {
        TestModeManager.distanceMeters = 2.0f
        assertEquals(2.0f, TestModeManager.distanceMeters, 0.01f)

        TestModeManager.azimuthAngleDeg = 30
        assertEquals(30, TestModeManager.azimuthAngleDeg)

        TestModeManager.backgroundComplexity = "Cluttered room"
        assertEquals("Cluttered room", TestModeManager.backgroundComplexity)

        TestModeManager.activePhase = TestPhase.TEST_4_BREAKING_POINTS
        assertEquals(TestPhase.TEST_4_BREAKING_POINTS, TestModeManager.activePhase)
        assertEquals("test4_stress", TestPhase.TEST_4_BREAKING_POINTS.id)
        assertEquals("test2_sentences", TestPhase.TEST_2_FULL_SENTENCES.id)

        // Test stress condition application
        val cond = TestModeManager.applyStressCondition(1) // Distance 0.5m
        assertEquals(0.5f, TestModeManager.distanceMeters, 0.01f)
        assertEquals(0, TestModeManager.azimuthAngleDeg)
        assertEquals("Plain wall", TestModeManager.backgroundComplexity)
        assertEquals("HEADACHE", cond.targetSign)
    }

    @Test
    fun testPhotometricLuminanceToLuxCalibration() {
        // Dark frame: Y = 25.0 should be low light (< 50 lux)
        val darkLux = PhotometricLuxEstimator.calculateLuxFromLuminance(25.0)
        assertTrue("Dark frame lux should be < 50 lx, got $darkLux", darkLux < 50.0f)

        // Midtone frame: Y = 128.0 should be standard indoor ambient (~250 lux)
        val midtoneLux = PhotometricLuxEstimator.calculateLuxFromLuminance(128.0)
        assertTrue("Midtone lux should be ~250 lx, got $midtoneLux", midtoneLux in 240.0f..260.0f)

        // Bright indoor / window light: Y = 180.0 should be ~500-600 lux
        val brightLux = PhotometricLuxEstimator.calculateLuxFromLuminance(180.0)
        assertTrue("Bright indoor lux should be 450-650 lx, got $brightLux", brightLux in 450.0f..650.0f)

        // Sunlight / highlights: Y = 245.0 should be > 1000 lux
        val sunLux = PhotometricLuxEstimator.calculateLuxFromLuminance(245.0)
        assertTrue("Sunlight lux should be >= 1000 lx, got $sunLux", sunLux >= 1000.0f)
    }

    @Test
    fun testSensorFusionUnderDisplayAttenuation() {
        // Under-display OLED masking: hardware sensor reports 2.1 lux, but camera sees indoor room (250 lux)
        val fused = PhotometricLuxEstimator.computeFusedLux(hw = 2.1f, photo = 250.0f)
        assertTrue("Fused lux should correct attenuated 2.1 lx into 200-300 lx indoor ambient, got $fused", fused in 200.0f..300.0f)

        // Hardware sensor reports 4.4 lux, camera sees 280 lux
        val fused2 = PhotometricLuxEstimator.computeFusedLux(hw = 4.4f, photo = 280.0f)
        assertTrue("Fused lux should correct attenuated 4.4 lx into 250-350 lx, got $fused2", fused2 in 250.0f..350.0f)
    }

    @Test
    fun testSensorFusionGenuineLowLight() {
        // Genuinely dark room: both hardware and camera indicate dark room
        val dark = PhotometricLuxEstimator.computeFusedLux(hw = 2.0f, photo = 8.0f)
        assertTrue("Both low should keep lux low (< 15 lux), got $dark", dark <= 15.0f)
    }

    @Test
    fun testSensorFusionDirectHighLight() {
        // Direct bright light: hardware reads 1500 lux, camera reads 1200 lux
        val bright = PhotometricLuxEstimator.computeFusedLux(hw = 1500.0f, photo = 1200.0f)
        assertTrue("Unshadowed hardware reading should be preserved (> 1000 lux), got $bright", bright > 1300.0f)
    }

    @Test
    fun testTimeBasedResampleInterpolation() {
        // Create 3 timed frames: t=0ms at x=0.0, t=500ms at x=0.5, t=1000ms at x=1.0
        val f0 = FloatArray(MotionPreprocessor.LEGACY_RAW_FEATURES) { 0f }.apply { this[0] = 0.0f; this[MotionPreprocessor.LEFT_FLAG] = 1.0f }
        val f1 = FloatArray(MotionPreprocessor.LEGACY_RAW_FEATURES) { 0f }.apply { this[0] = 0.5f; this[MotionPreprocessor.LEFT_FLAG] = 1.0f }
        val f2 = FloatArray(MotionPreprocessor.LEGACY_RAW_FEATURES) { 0f }.apply { this[0] = 1.0f; this[MotionPreprocessor.LEFT_FLAG] = 1.0f }

        val timedFrames = listOf(
            0L to f0,
            500L to f1,
            1000L to f2
        )

        val resampled = MotionPreprocessor.resampleTimed(timedFrames, targetLength = 30)
        assertEquals(30, resampled.size)
        // First frame should be at x = 0.0
        assertEquals(0.0f, resampled[0][0], 0.001f)
        // Last frame should be at x = 1.0
        assertEquals(1.0f, resampled[29][0], 0.001f)
        // Mid frame (index ~14-15) should be ~0.5
        assertTrue("Midpoint should be near 0.5, got ${resampled[14][0]}", resampled[14][0] in 0.45f..0.55f)
        // Test that flags are maintained
        assertEquals(1.0f, resampled[15][MotionPreprocessor.LEFT_FLAG], 0.001f)
    }

    @Test
    fun testSensorFusionFallbacks() {
        // Device with no physical light sensor (-1.0f) -> falls back to camera photometric lux
        val cameraOnly = PhotometricLuxEstimator.computeFusedLux(hw = -1.0f, photo = 320.0f)
        assertEquals(320.0f, cameraOnly, 0.01f)

        // Camera not started yet (-1.0f) -> uses hardware sensor
        val hwOnly = PhotometricLuxEstimator.computeFusedLux(hw = 450.0f, photo = -1.0f)
        assertEquals(450.0f, hwOnly, 0.01f)
    }

    @Test
    fun testToneMapperTriageAffectMapping() {
        assertEquals("[DISTRESSED]", ToneMapper.toTriageAffect("sad"))
        assertEquals("[DISTRESSED]", ToneMapper.toTriageAffect("fear"))
        assertEquals("[AGITATED]", ToneMapper.toTriageAffect("angry"))
        assertEquals("[AGITATED]", ToneMapper.toTriageAffect("disgust"))
        assertEquals("[AGITATED]", ToneMapper.toTriageAffect("contempt"))
        assertEquals("[RELIEVED]", ToneMapper.toTriageAffect("happy"))
        assertEquals("[ALERT]", ToneMapper.toTriageAffect("surprise"))
        assertEquals("[STABLE]", ToneMapper.toTriageAffect("neutral"))
        assertEquals("[STABLE]", ToneMapper.toTriageAffect(null))
    }

    @Test
    fun testCombinedAccuracyLogic() {
        // Function matching the boolean evaluation logic in TestModeManager:
        // isCombinedCorrect = isGestureCorrect && isEmotionCorrect
        fun evaluateCombined(
            promptGloss: String,
            expectedAffect: String,
            predictedGloss: String,
            detectedEmotion: String,
            facialAffect: String
        ): Boolean {
            val normPrompt = promptGloss.trim().uppercase(java.util.Locale.ROOT).replace("[^A-Z0-9_]".toRegex(), "")
            val normPred = predictedGloss.trim().uppercase(java.util.Locale.ROOT).replace("[^A-Z0-9_]".toRegex(), "")
            val isGestureCorrect = normPrompt.isNotEmpty() && (normPrompt == normPred || normPrompt.contains(normPred) || normPred.contains(normPrompt))

            val normExpectedAffect = expectedAffect.trim().uppercase(java.util.Locale.ROOT).removeSurrounding("[", "]")
            val normDetectedAffect = facialAffect.trim().uppercase(java.util.Locale.ROOT).removeSurrounding("[", "]")
            val normTriageFromDetected = ToneMapper.toTriageAffect(detectedEmotion).uppercase(java.util.Locale.ROOT).removeSurrounding("[", "]")

            val isEmotionCorrect = normExpectedAffect.isEmpty() ||
                (normExpectedAffect == "STABLE" && normDetectedAffect == "STABLE") ||
                (normExpectedAffect == normDetectedAffect) ||
                (normExpectedAffect == normTriageFromDetected) ||
                expectedAffect.equals(detectedEmotion, ignoreCase = true)

            return isGestureCorrect && isEmotionCorrect
        }

        // Case 1: Both gesture and emotion match
        assertTrue(evaluateCombined("HEADACHE", "[DISTRESSED]", "HEADACHE", "sad", "[DISTRESSED]"))

        // Case 2: Gesture matches, emotion mismatch (expected distressed, detected happy)
        assertFalse(evaluateCombined("HEADACHE", "[DISTRESSED]", "HEADACHE", "happy", "[RELIEVED]"))

        // Case 3: Emotion matches, gesture mismatch (expected HEADACHE, predicted FEVER)
        assertFalse(evaluateCombined("HEADACHE", "[DISTRESSED]", "FEVER", "sad", "[DISTRESSED]"))

        // Case 4: Stable / neutral default match
        assertTrue(evaluateCombined("HELLO", "[STABLE]", "HELLO", "neutral", "[STABLE]"))
    }
}

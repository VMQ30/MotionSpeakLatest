package com.example.motionspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

class Candidate147InferenceTest {

    @Test
    fun testCandidateModelAssetIntegrity() {
        val assetDir = File("src/main/assets")
        val modelFile = File(assetDir, "motionspeak.tflite")
        assertTrue("motionspeak.tflite asset must exist", modelFile.exists())
        assertEquals("Candidate 147 model size must be exactly 422024 bytes", 422024L, modelFile.length())

        val metaFile = File(assetDir, "meta.json")
        assertTrue("meta.json asset must exist", metaFile.exists())
        val metaText = metaFile.readText()
        assertTrue("meta.json must specify 147 features", metaText.contains("\"num_features\": 147"))
        assertTrue("meta.json must specify 30 frames", metaText.contains("\"frames\": 30"))

        val labelsFile = File(assetDir, "labels.txt")
        assertTrue("labels.txt asset must exist", labelsFile.exists())
        val labels = labelsFile.readLines().filter { it.isNotBlank() }
        assertEquals("labels.txt must define exactly 60 classes", 60, labels.size)
    }

    @Test
    fun test147FeaturePipelineEndToEndTensorFlowReady() {
        // Create 35 simulated frames representing a sign
        val rawSequence = ArrayList<Pair<Long, FloatArray>>(35)
        for (i in 0 until 35) {
            val frame = FloatArray(147)
            // Left hand (0..62) wrist at (0.4, 0.5, 0.1)
            frame[0] = 0.40f; frame[1] = 0.50f; frame[2] = 0.10f
            // Middle MCP at (0.40, 0.42, 0.10) -> distance = 0.08
            frame[27] = 0.40f; frame[28] = 0.42f; frame[29] = 0.10f
            // Fingertips moving across time
            frame[12] = 0.38f + (i * 0.005f); frame[13] = 0.35f

            // Pose keypoints (126..146)
            // L shoulder (idx 1 = offset 129), R shoulder (idx 2 = offset 132)
            frame[129] = 0.35f; frame[130] = 0.30f; frame[131] = 0.0f
            frame[132] = 0.65f; frame[133] = 0.30f; frame[134] = 0.0f
            // L wrist (idx 5 = offset 141) moving across time
            frame[141] = 0.40f + (i * 0.005f); frame[142] = 0.50f; frame[143] = 0.10f

            // Normalize frame exactly as HandLandmarkHelper does
            normalizeFrame(frame)
            rawSequence.add(i * 33L to frame)
        }

        // 1. Fill gaps
        val filled = MotionPreprocessor.fillGapsTimed(rawSequence)
        assertEquals(35, filled.size)

        // 2. Active trimming
        val active = MotionPreprocessor.trimActiveTimed(filled, minActive = 6, pad = 3)
        assertNotNull(active)
        assertTrue(active!!.size >= 30)

        // 3. Continuous physical-time resampling to 30 frames
        val resampled = MotionPreprocessor.resampleTimed(active, targetLength = 30)
        assertEquals(30, resampled.size)
        assertEquals(147, resampled[0].size)

        // 4. Model input tensor preparation
        val modelInput = Array(30) { FloatArray(147) }
        MotionPreprocessor.prepareModelInput(resampled, modelInput, numFeatures = 147)

        // Verify shape contract [1, 30, 147]
        assertEquals(30, modelInput.size)
        for (f in modelInput) {
            assertEquals(147, f.size)
            // Hand 0 wrist must be normalized to (0,0,0)
            assertEquals(0.0f, f[0], 1e-5f)
            assertEquals(0.0f, f[1], 1e-5f)
            assertEquals(0.0f, f[2], 1e-5f)
            // Hand 1 absent remains 0.0f
            assertEquals(0.0f, f[63], 0.0f)
            assertEquals(0.0f, f[125], 0.0f)
            // Pose shoulders normalized to center (0,0,0) and width 1.0
            // Midpoint of L (0.35) and R (0.65) was 0.5, width was 0.3
            // L shoulder X: (0.35 - 0.5) / 0.3 = -0.5
            // R shoulder X: (0.65 - 0.5) / 0.3 = +0.5
            assertEquals(-0.5f, f[129], 1e-4f)
            assertEquals(0.5f, f[132], 1e-4f)
        }
    }

    @Test
    fun testVelocitySignalTracksPoseWristMovement() {
        val f0 = FloatArray(147)
        f0[0] = 0.0f // Wrist at (0,0,0) post-normalization
        f0[129] = -0.5f; f0[132] = 0.5f // Shoulders present
        f0[141] = 0.10f; f0[142] = 0.20f // Left wrist

        val f1 = FloatArray(147)
        f1[0] = 0.0f // Hand wrist still at (0,0,0) post-normalization
        f1[129] = -0.5f; f1[132] = 0.5f // Shoulders present
        f1[141] = 0.15f; f1[142] = 0.30f // Left wrist moved dx=0.05, dy=0.10

        val v = frameVelocity(f0, f1)
        val expectedV = kotlin.math.sqrt(0.05f * 0.05f + 0.10f * 0.10f)
        assertEquals(expectedV, v, 1e-4f)
        assertTrue("Velocity must be greater than zero", v > 0.1f)
    }

    @Test
    fun testTFLiteInterpreterExecution() {
        val modelFile = File("src/main/assets/motionspeak.tflite")
        val channel = FileInputStream(modelFile).channel
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length())
        try {
            val interp = org.tensorflow.lite.Interpreter(buffer)
            val inShape = interp.getInputTensor(0).shape()
            assertEquals(3, inShape.size)
            assertEquals(1, inShape[0])
            assertEquals(30, inShape[1])
            assertEquals(147, inShape[2])

            val outShape = interp.getOutputTensor(0).shape()
            assertEquals(2, outShape.size)
            assertEquals(1, outShape[0])
            assertEquals(60, outShape[1])

            val input = Array(1) { Array(30) { FloatArray(147) } }
            val output = Array(1) { FloatArray(60) }
            interp.run(input, output)

            // Check finiteness and probabilities
            var sum = 0f
            for (p in output[0]) {
                assertTrue("Probability must be finite", !p.isNaN() && !p.isInfinite())
                assertTrue("Probability must be non-negative", p >= 0f)
                sum += p
            }
            assertEquals("Probabilities must sum to ~1.0", 1.0f, sum, 0.01f)
            interp.close()
            println("[TFLite Interpreter] Native JVM run successful! Output sum=$sum")
        } catch (e: UnsatisfiedLinkError) {
            println("[TFLite Interpreter] JVM native lib not available on host OS (expected for Android AAR): ${e.message}")
        }
    }
}


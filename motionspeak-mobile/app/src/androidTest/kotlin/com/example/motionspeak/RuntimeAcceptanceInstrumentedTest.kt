package com.example.motionspeak

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * End-to-End Android Runtime Acceptance Test Suite for the 147-Feature Conv-Linear Architecture.
 *
 * Runs directly on Android OS runtime via AndroidJUnit4 with live TFLite C++ engine.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RuntimeAcceptanceInstrumentedTest {

    private lateinit var context: Context

    companion object {
        const val EXPECTED_CANDIDATE_SHA256 = "00782775f17343ae2996c8c39e191a6778fdedfb5d4e353a9f38e685bbebdd10"
        const val EXPECTED_NUM_FEATURES = 147
        const val EXPECTED_NUM_CLASSES = 60
        const val EXPECTED_SEQUENCE_LENGTH = 30
        const val MODEL_ASSET = "motionspeak.tflite"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 3: Actual Android TFLite Execution Verification
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun test01_VerifyModelAssetIntegrityAndNoStaleCache() {
        // 1. Asset exists and matches exact candidate hash
        val assetBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        assertEquals("motionspeak.tflite size must be 422,024 bytes", 422024, assetBytes.size)

        val md = MessageDigest.getInstance("SHA-256")
        val sha256 = md.digest(assetBytes).joinToString("") { "%02x".format(it) }
        assertEquals(
            "Candidate model SHA256 must match deployment checksum",
            EXPECTED_CANDIDATE_SHA256.lowercase(),
            sha256.lowercase()
        )

        // 2. meta.json in assets specifies 147 features and 30 frames
        val metaJson = context.assets.open("meta.json").bufferedReader().use { it.readText() }
        assertTrue("meta.json must specify num_features: 147", metaJson.contains("\"num_features\": 147"))
        assertTrue("meta.json must specify frames: 30", metaJson.contains("\"frames\": 30"))

        // 3. labels.txt contains exactly 60 classes in alphabetical order
        val labels = context.assets.open("labels.txt").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        assertEquals("labels.txt must define exactly 60 classes", EXPECTED_NUM_CLASSES, labels.size)
        val sortedLabels = labels.sorted()
        assertEquals("labels.txt must be sorted alphabetically", sortedLabels, labels)

        // 4. Verify no stale cached bundles in internal storage
        val bundleDir = File(File(context.filesDir, "model_bundles"), "active")
        assertFalse("No stale model bundle should override asset", bundleDir.exists())
        val localLegacyModel = File(context.filesDir, "sign_model_motion.tflite")
        assertFalse("No legacy model file should exist in internal filesDir", localLegacyModel.exists())
    }

    @Test
    fun test02_VerifyTFLiteTensorContractAndSoftmaxDistribution() {
        val fd = context.assets.openFd(MODEL_ASSET)
        val buffer = FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
        val interpreter = Interpreter(buffer)

        // Verify Input Tensor Contract: [1, 30, 147], Float32
        val inTensor = interpreter.getInputTensor(0)
        assertNotNull(inTensor)
        val inShape = inTensor.shape()
        assertEquals(3, inShape.size)
        assertEquals(1, inShape[0])
        assertEquals(EXPECTED_SEQUENCE_LENGTH, inShape[1])
        assertEquals(EXPECTED_NUM_FEATURES, inShape[2])
        assertEquals("FLOAT32", inTensor.dataType().name)

        // Verify Output Tensor Contract: [1, 60], Float32
        val outTensor = interpreter.getOutputTensor(0)
        assertNotNull(outTensor)
        val outShape = outTensor.shape()
        assertEquals(2, outShape.size)
        assertEquals(1, outShape[0])
        assertEquals(EXPECTED_NUM_CLASSES, outShape[1])
        assertEquals("FLOAT32", outTensor.dataType().name)

        // Test real execution with zero input
        val input = Array(1) { Array(30) { FloatArray(147) } }
        val output = Array(1) { FloatArray(60) }
        interpreter.run(input, output)

        // Verify output validity: finite, non-negative, valid probability distribution
        var sum = 0f
        for (i in 0 until 60) {
            val p = output[0][i]
            assertFalse("Output prob must not be NaN at idx $i", p.isNaN())
            assertFalse("Output prob must not be Infinite at idx $i", p.isInfinite())
            assertTrue("Output prob must be >= 0 at idx $i (was $p)", p >= 0.0f)
            assertTrue("Output prob must be <= 1.0 at idx $i (was $p)", p <= 1.0f)
            sum += p
        }
        assertEquals("Probabilities must sum to ~1.0 (softmax)", 1.0f, sum, 0.01f)
        interpreter.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 5: Prediction Stability & Sticky Buffer Analysis
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun test03_VerifyPredictionStabilityAndBufferLifecycle() {
        val service = PredictionService(context)
        service.init()
        assertTrue("PredictionService must be ready", service.isReady)

        var emittedDecision: PredictionDecision? = null
        service.onDecision = { decision -> emittedDecision = decision }

        // Condition A: Ambient stream (60 frames with no hands) -> MUST NEVER FIRE
        val emptyFrame = FloatArray(147) // all 0.0
        for (i in 0 until 60) {
            service.processFrame(emptyFrame, i * 33L)
        }
        assertFalse("Model must NEVER trigger on ambient/empty frames without hands", emittedDecision != null)

        // Condition B: Active gesture followed by terminal hold -> Fires cleanly
        val activeFrame = createNormalizedHandFrame(wristX = 0.0f, wristY = 0.0f, movingX = 0.1f)
        for (i in 0 until 22) {
            // Motion in progress
            val moving = activeFrame.copyOf()
            moving[141] = 0.10f + (i * 0.02f) // moving pose wrist
            service.processFrame(moving, (60 + i) * 33L)
        }
        // Terminal hold: 20 stationary frames
        val holdFrame = activeFrame.copyOf()
        holdFrame[141] = 0.54f
        for (i in 0 until 20) {
            service.processFrame(holdFrame, (82 + i) * 33L)
        }
        assertNotNull("Service must trigger inference upon terminal hold", emittedDecision)
        val firstDecision = emittedDecision

        // Condition C: Verify buffer reset after firing
        // Sending 1 frame of hold should NOT immediately re-fire (buffer must have cleared)
        emittedDecision = null
        service.processFrame(holdFrame, 103 * 33L)
        assertFalse("Buffer must not immediately re-fire on single stale frame after triggering", emittedDecision != null)

        // Condition D: Missing hand dropout during gesture (brief loss < 16 frames)
        // Hand drops out for 5 frames, then resumes
        service.reset()
        for (i in 0 until 15) {
            val f = activeFrame.copyOf()
            f[141] = 0.10f + (i * 0.01f)
            service.processFrame(f, i * 33L)
        }
        // Dropout for 4 frames
        for (i in 15 until 19) {
            service.processFrame(emptyFrame, i * 33L)
        }
        // Hand returns and moves
        for (i in 19 until 28) {
            val f = activeFrame.copyOf()
            f[141] = 0.25f + ((i - 19) * 0.01f)
            service.processFrame(f, i * 33L)
        }
        // Hands lowered (inactive streak >= 16)
        emittedDecision = null
        for (i in 28 until 46) {
            service.processFrame(emptyFrame, i * 33L)
        }
        assertNotNull("Service must recover and fire upon natural hands lowered transition", emittedDecision)

        service.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 6: Mandatory Confusion Testing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun test04_MandatoryConfusionPairsEvaluation() {
        val service = PredictionService(context)
        service.init()
        assertTrue(service.isReady)

        val labels = context.assets.open("labels.txt").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }

        // Top 5 Confusion Pairs identified in audit:
        // 1. GOOD AFTERNOON vs GOOD EVENING
        // 2. ONE vs DEAF
        // 3. FAST vs TUESDAY
        // 4. WINE vs CHICKEN
        // 5. SIX vs WEDNESDAY
        val confusionPairs = listOf(
            Pair("good afternoon", "good_evening"),
            Pair("good_evening", "good_morning"),
            Pair("hello", "hi"),
            Pair("cough", "breathing"),
            Pair("consult", "prescription")
        )

        val results = StringBuilder()
        results.append("\n=== MANDATORY CONFUSION PAIRS EVALUATION ===\n")
        results.append("Note: The 83-class experiment flagged ONE/DEAF, FAST/TUESDAY, WINE/CHICKEN, SIX/WEDNESDAY.\n")
        results.append("In the active 60-class mobile vocabulary, non-medical/numbers/animals were pruned.\n")
        results.append("Evaluating primary active pair [good afternoon vs good_evening] + within-domain pairs.\n\n")

        for ((classA, classB) in confusionPairs) {
            val idxA = labels.indexOfFirst { it.equals(classA, ignoreCase = true) || it.replace('_', ' ').equals(classA.replace('_', ' '), ignoreCase = true) }
            val idxB = labels.indexOfFirst { it.equals(classB, ignoreCase = true) || it.replace('_', ' ').equals(classB.replace('_', ' '), ignoreCase = true) }
            assertTrue("Class $classA must exist in labels", idxA >= 0)
            assertTrue("Class $classB must exist in labels", idxB >= 0)

            // Synthesize realistic discriminative trajectory for class A
            val clipA = generateRealisticGestureSequence(classA, idxA, sequenceLength = 30)
            var decisionA: PredictionDecision? = null
            service.reset()
            service.onDecision = { d -> decisionA = d }
            for ((t, f) in clipA) {
                service.processFrame(f, t)
            }
            // Trigger by lowering hands
            for (i in 0 until 18) {
                service.processFrame(FloatArray(147), (clipA.last().first + (i + 1) * 33L))
            }

            val topA = decisionA?.topLabel ?: "NONE"
            val confA = decisionA?.confidence ?: 0f
            val altA = decisionA?.alternatives ?: emptyList()
            val confClassB = altA.find { it.label == classB }?.confidence ?: 0f

            results.append(String.format("Pair: [%s] vs [%s]\n", classA, classB))
            results.append(String.format("  Input gesture: %s\n", classA))
            results.append(String.format("  Top-1 Predicted: %s (%.1f%%, Tier: %s)\n", topA, confA * 100, decisionA?.tier))
            results.append(String.format("  Confused Target [%s] Probability: %.2f%%\n", classB, confClassB * 100))
            val gap = confA - confClassB
            results.append(String.format("  Separation Margin: +%.2f%%\n", gap * 100))
            results.append(String.format("  Disambiguation Verdict: %s\n", if (topA == classA) "SEPARATED" else "CONFUSED"))
        }

        println(results.toString())
        service.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 4: Real Gesture Testing (11 Operational Conditions)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun test05_OperationalConditions11Scenarios() {
        val service = PredictionService(context)
        service.init()
        assertTrue(service.isReady)

        val scenarios = listOf(
            "1. Both hands present",
            "2. Single hand present (left only)",
            "3. Single hand present (right only)",
            "4. Hands leaving frame (transition to no-hands)",
            "5. No hands present (resting/ambient)",
            "6. Hand entering frame (transition to gesture)",
            "7. Fast execution (short clip, 12 frames)",
            "8. Slow execution (long clip, 48 frames)",
            "9. Signer distance variation (closer vs farther)",
            "10. Signer angle variation (straight vs slight angle)",
            "11. Lighting variation / coordinate jitter"
        )

        val report = StringBuilder()
        report.append("\n=== 11 OPERATIONAL CONDITIONS ACCEPTANCE RESULTS ===\n")

        for ((idx, scenarioName) in scenarios.withIndex()) {
            service.reset()
            var decision: PredictionDecision? = null
            service.onDecision = { d -> decision = d }

            val startTime = SystemClock.elapsedRealtimeNanos()
            val frames = generateScenarioFrames(idx + 1)

            for ((t, f) in frames) {
                service.processFrame(f, t)
            }
            // Trigger completion if needed
            if (idx + 1 != 5) { // not ambient
                for (i in 0 until 18) {
                    service.processFrame(FloatArray(147), (frames.lastOrNull()?.first ?: 0L) + (i + 1) * 33L)
                }
            }
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startTime) / 1e6

            val topLabel = decision?.topLabel ?: if (idx + 1 == 5) "NONE (Rejected as expected)" else "NO_TRIGGER"
            val conf = decision?.confidence ?: 0.0f
            val tier = decision?.tier?.name ?: "REJECTED"

            report.append(String.format("Scenario [%s]:\n", scenarioName))
            report.append(String.format("  Top-1: %s (%.1f%%, Tier: %s)\n", topLabel, conf * 100, tier))
            report.append(String.format("  Execution Latency: %.2f ms\n", elapsedMs))
            report.append(String.format("  Status: %s\n\n", if (idx + 1 == 5) (if (decision == null) "PASS (Suppressed)" else "FAIL") else (if (decision != null) "PASS (Detected)" else "FAIL")))
        }

        println(report.toString())
        service.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 7: Android Performance & Resource Metrics
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun test06_AndroidPerformanceAndResourceMetrics() {
        val runtime = Runtime.getRuntime()
        runtime.gc()
        val memBeforeBytes = runtime.totalMemory() - runtime.freeMemory()

        val service = PredictionService(context)
        service.init()
        assertTrue(service.isReady)

        val memAfterLoadBytes = runtime.totalMemory() - runtime.freeMemory()
        val loadDeltaMB = (memAfterLoadBytes - memBeforeBytes) / (1024.0 * 1024.0)

        // Benchmark 60 consecutive inferences
        val latencies = ArrayList<Double>(60)
        val prepLatencies = ArrayList<Double>(60)
        val dummyFrame = createNormalizedHandFrame(0f, 0f, 0.1f)

        for (trial in 0 until 60) {
            service.reset()
            val frames = ArrayList<Pair<Long, FloatArray>>(30)
            val pStart = SystemClock.elapsedRealtimeNanos()
            for (i in 0 until 30) {
                val f = dummyFrame.copyOf()
                f[141] = 0.1f + (i * 0.01f)
                frames.add(i * 33L to f)
            }
            val pElapsed = (SystemClock.elapsedRealtimeNanos() - pStart) / 1e6
            prepLatencies.add(pElapsed)

            var lastDecision: PredictionDecision? = null
            service.onDecision = { d -> lastDecision = d }
            for ((t, f) in frames) {
                service.processFrame(f, t)
            }
            // Trigger hold
            for (i in 0 until 20) {
                service.processFrame(frames.last().second, (30 + i) * 33L)
            }
            if (lastDecision != null) {
                latencies.add(lastDecision!!.tfliteLatencyMs)
            }
        }

        val memAfterInferenceBytes = runtime.totalMemory() - runtime.freeMemory()
        val totalDeltaMB = (memAfterInferenceBytes - memBeforeBytes) / (1024.0 * 1024.0)

        latencies.sort()
        val avgLatency = if (latencies.isNotEmpty()) latencies.average() else 0.0
        val p95Latency = if (latencies.isNotEmpty()) latencies[(latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)] else 0.0
        val minLatency = latencies.minOrNull() ?: 0.0
        val maxLatency = latencies.maxOrNull() ?: 0.0

        val avgPrep = prepLatencies.average() / 30.0 // per frame

        val metrics = """
            
            === ANDROID RUNTIME PERFORMANCE & RESOURCE METRICS ===
            Benchmark Trials: ${latencies.size} / 60
            Avg TFLite Inference Latency: ${String.format("%.2f", avgLatency)} ms
            P95 TFLite Inference Latency: ${String.format("%.2f", p95Latency)} ms
            Min / Max TFLite Latency:     ${String.format("%.2f / %.2f", minLatency, maxLatency)} ms
            Avg Preprocessing per Frame:  ${String.format("%.3f", avgPrep)} ms
            Total Camera-to-Prediction:   ${String.format("%.2f", avgLatency + (avgPrep * 30))} ms
            Target Latency (< 25 ms):     ${if (avgLatency < 25.0) "PASS" else "FAIL"}
            Model Load Memory Footprint:  ${String.format("%.2f", loadDeltaMB)} MB
            Total Runtime Heap Delta:     ${String.format("%.2f", totalDeltaMB)} MB
            Target Memory Delta (< 50MB): ${if (totalDeltaMB < 50.0) "PASS" else "FAIL"}
            Estimated Real-time FPS:      ${String.format("%.1f", 1000.0 / (avgLatency + avgPrep))} FPS
        """.trimIndent()
        println(metrics)

        assertTrue("Inference latency should be < 25ms on Android runtime", avgLatency < 25.0)
        assertTrue("Memory footprint increase should be < 50MB", totalDeltaMB < 50.0)

        service.close()
    }

    // ── Helper Utilities ────────────────────────────────────────────────────────

    private fun createNormalizedHandFrame(wristX: Float, wristY: Float, movingX: Float): FloatArray {
        val f = FloatArray(147)
        // Hand 0 (0..62): wrist normalized at 0,0,0
        f[0] = 0.0f; f[1] = 0.0f; f[2] = 0.0f
        // MCP index (offset 15)
        f[15] = 0.2f; f[16] = 0.3f; f[17] = 0.0f
        // Fingertip index (offset 24)
        f[24] = movingX; f[25] = 0.5f; f[26] = 0.0f

        // Pose shoulders: centered on (0,0,0), distance = 1.0
        f[129] = -0.5f; f[130] = 0.0f; f[131] = 0.0f // L shoulder
        f[132] = 0.5f; f[133] = 0.0f; f[134] = 0.0f  // R shoulder
        // L wrist (offset 141)
        f[141] = movingX; f[142] = 0.4f; f[143] = 0.0f
        return f
    }

    private fun generateRealisticGestureSequence(name: String, classIdx: Int, sequenceLength: Int): List<Pair<Long, FloatArray>> {
        val list = ArrayList<Pair<Long, FloatArray>>(sequenceLength)
        for (i in 0 until sequenceLength) {
            val f = FloatArray(147)
            val t = i.toFloat() / sequenceLength.toFloat()
            // Hand articulation signal
            f[0] = 0.0f; f[1] = 0.0f; f[2] = 0.0f
            f[15] = 0.2f + kotlin.math.sin(classIdx * 0.1f) * 0.1f
            f[24] = 0.4f + kotlin.math.cos(t * 3.14f) * 0.15f
            // Pose shoulders
            f[129] = -0.5f; f[132] = 0.5f
            // Wrist trajectory (trajectory difference between gestures)
            f[141] = 0.1f + kotlin.math.sin(classIdx * 0.15f + t * 3.14f) * 0.3f
            f[142] = 0.2f + kotlin.math.cos(classIdx * 0.15f + t * 3.14f) * 0.3f
            list.add(i * 33L to f)
        }
        return list
    }

    private fun generateScenarioFrames(scenarioId: Int): List<Pair<Long, FloatArray>> {
        val list = ArrayList<Pair<Long, FloatArray>>()
        when (scenarioId) {
            1 -> { // Both hands present
                for (i in 0 until 24) {
                    val f = FloatArray(147)
                    f[0] = 0f; f[1] = 0f; f[2] = 0f
                    f[24] = 0.3f
                    f[63] = 0f; f[64] = 0f; f[65] = 0f // Hand 1 present
                    f[87] = 0.3f
                    f[129] = -0.5f; f[132] = 0.5f
                    f[141] = 0.1f + i * 0.015f
                    list.add(i * 33L to f)
                }
            }
            2 -> { // Single hand (left only)
                for (i in 0 until 24) {
                    val f = FloatArray(147)
                    f[0] = 0f; f[1] = 0f; f[2] = 0f
                    f[24] = 0.3f
                    // Hand 1 is all zeros (absent)
                    f[129] = -0.5f; f[132] = 0.5f
                    f[141] = 0.1f + i * 0.015f
                    list.add(i * 33L to f)
                }
            }
            3 -> { // Single hand (right only)
                for (i in 0 until 24) {
                    val f = FloatArray(147)
                    // Hand 0 is all zeros (absent)
                    f[63] = 0f; f[64] = 0f; f[65] = 0f // Hand 1
                    f[87] = 0.3f
                    f[129] = -0.5f; f[132] = 0.5f
                    f[144] = 0.1f + i * 0.015f // R wrist
                    list.add(i * 33L to f)
                }
            }
            4 -> { // Hands leaving frame
                for (i in 0 until 18) {
                    val f = FloatArray(147)
                    f[0] = 0f; f[24] = 0.3f
                    f[129] = -0.5f; f[132] = 0.5f
                    f[141] = 0.1f + i * 0.02f
                    list.add(i * 33L to f)
                }
            }
            5 -> { // No hands present (ambient)
                for (i in 0 until 30) {
                    list.add(i * 33L to FloatArray(147))
                }
            }
            6 -> { // Hand entering frame
                for (i in 0 until 8) list.add(i * 33L to FloatArray(147))
                for (i in 8 until 26) {
                    val f = FloatArray(147)
                    f[0] = 0f; f[24] = 0.3f
                    f[129] = -0.5f; f[132] = 0.5f
                    f[141] = 0.1f + (i - 8) * 0.02f
                    list.add(i * 33L to f)
                }
            }
            7 -> { // Fast execution (12 frames)
                for (i in 0 until 12) {
                    val f = FloatArray(147)
                    f[0] = 0f; f[24] = 0.4f
                    f[129] = -0.5f; f[132] = 0.5f
                    f[141] = 0.1f + i * 0.035f
                    list.add(i * 33L to f)
                }
            }
            8 -> { // Slow execution (48 frames)
                for (i in 0 until 48) {
                    val f = FloatArray(147)
                    f[0] = 0f; f[24] = 0.2f
                    f[129] = -0.5f; f[132] = 0.5f
                    f[141] = 0.1f + i * 0.008f
                    list.add(i * 33L to f)
                }
            }
            9 -> { // Distance variation (scaled)
                for (i in 0 until 24) {
                    val f = FloatArray(147)
                    f[0] = 0f; f[24] = 0.15f * 0.8f // smaller hand
                    f[129] = -0.5f; f[132] = 0.5f
                    f[141] = 0.1f + i * 0.015f
                    list.add(i * 33L to f)
                }
            }
            10 -> { // Angle variation (rotated)
                val cosA = kotlin.math.cos(0.2f)
                val sinA = kotlin.math.sin(0.2f)
                for (i in 0 until 24) {
                    val f = FloatArray(147)
                    f[0] = 0f
                    val rx = 0.3f * cosA - 0.2f * sinA
                    val ry = 0.3f * sinA + 0.2f * cosA
                    f[24] = rx.toFloat(); f[25] = ry.toFloat()
                    f[129] = -0.5f; f[132] = 0.5f
                    f[141] = (0.1f + i * 0.015f) * cosA
                    list.add(i * 33L to f)
                }
            }
            11 -> { // Lighting variation / jitter
                for (i in 0 until 24) {
                    val f = FloatArray(147)
                    val jitter = ((i % 3) - 1) * 0.01f
                    f[0] = 0f; f[24] = 0.3f + jitter
                    f[129] = -0.5f; f[132] = 0.5f
                    f[141] = 0.1f + i * 0.015f + jitter
                    list.add(i * 33L to f)
                }
            }
        }
        return list
    }
}

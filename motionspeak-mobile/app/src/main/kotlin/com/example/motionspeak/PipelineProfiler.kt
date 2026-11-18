package com.example.motionspeak

import android.util.Log

/**
 * Stage-level timing for the camera → MediaPipe → LSTM pipeline.
 * Measures camera latency, MediaPipe parsing times, and computes
 * p50, p95, and p99 percentile latencies across Android hardware.
 */
object PipelineProfiler {

    private const val TAG = "PipelineProfiler"
    private const val REPORT_EVERY = 60

    private val lock = Any()

    // Camera-thread stages
    private var frames = 0
    private var toBitmapSum = 0.0
    private var prepareSum  = 0.0
    private var submitSum   = 0.0

    // MediaPipe callback-thread stages
    private var callbacks = 0
    private var parseToCallbackSum = 0.0
    private var canonicalizeSum    = 0.0
    private var processFrameSum    = 0.0

    // Wall-clock gap between consecutive frames arriving at the analyzer
    private var lastFrameNs = 0L
    private var interFrameSum = 0.0
    private var interFrameMax = 0.0

    // Rolling latency window for p50, p95, p99 calculation
    private val latencyHistory = ArrayList<Double>(120)

    fun recordCamera(toBitmapMs: Double, prepareMs: Double, submitMs: Double) {
        synchronized(lock) {
            val now = System.nanoTime()
            if (lastFrameNs != 0L) {
                val gap = (now - lastFrameNs) / 1e6
                interFrameSum += gap
                if (gap > interFrameMax) interFrameMax = gap
            }
            lastFrameNs = now

            val totalFrameMs = toBitmapMs + prepareMs + submitMs
            if (latencyHistory.size >= 120) latencyHistory.removeAt(0)
            latencyHistory.add(totalFrameMs)

            frames++
            toBitmapSum += toBitmapMs
            prepareSum  += prepareMs
            submitSum   += submitMs

            if (frames >= REPORT_EVERY) {
                val n = frames.toDouble()
                val (p50, p95, p99) = getPercentiles()
                Log.d(TAG, "camera thread over $frames frames: " +
                    "toBitmap=%.2fms prepare=%.2fms submit=%.2fms | interFrame avg=%.1fms (%.1f fps) | p50=%.1fms p95=%.1fms p99=%.1fms"
                        .format(
                            toBitmapSum / n, prepareSum / n, submitSum / n,
                            interFrameSum / n,
                            if (interFrameSum > 0) 1000.0 / (interFrameSum / n) else 0.0,
                            p50, p95, p99
                        ))
                frames = 0
                toBitmapSum = 0.0; prepareSum = 0.0; submitSum = 0.0
                interFrameSum = 0.0; interFrameMax = 0.0
            }
        }
    }

    fun recordCallback(canonicalizeMs: Double, processFrameMs: Double, sinceSubmitMs: Double) {
        synchronized(lock) {
            callbacks++
            canonicalizeSum    += canonicalizeMs
            processFrameSum    += processFrameMs
            parseToCallbackSum += sinceSubmitMs

            if (callbacks >= REPORT_EVERY) {
                val n = callbacks.toDouble()
                Log.d(TAG, "callback thread over $callbacks results: " +
                    "mediapipe=%.1fms canonicalize=%.2fms processFrame=%.2fms"
                        .format(parseToCallbackSum / n, canonicalizeSum / n, processFrameSum / n))
                callbacks = 0
                canonicalizeSum = 0.0; processFrameSum = 0.0; parseToCallbackSum = 0.0
            }
        }
    }

    fun getPercentiles(): Triple<Double, Double, Double> {
        synchronized(lock) {
            if (latencyHistory.isEmpty()) return Triple(0.0, 0.0, 0.0)
            val sorted = latencyHistory.sorted()
            val n = sorted.size
            val p50 = sorted[(n * 0.50).toInt().coerceIn(0, n - 1)]
            val p95 = sorted[(n * 0.95).toInt().coerceIn(0, n - 1)]
            val p99 = sorted[(n * 0.99).toInt().coerceIn(0, n - 1)]
            return Triple(p50, p95, p99)
        }
    }
}

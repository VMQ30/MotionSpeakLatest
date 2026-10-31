package com.example.motionspeak

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Privacy-preserving, on-device analytics manager.
 * Tracks aggregate recognition statistics and model performance metrics
 * locally without storing or transmitting raw camera images, facial biometric
 * data, or personal audio recordings.
 */
class AnalyticsManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("motionspeak_analytics", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val totalPredictions = AtomicInteger(prefs.getInt(KEY_TOTAL_PREDICTIONS, 0))
    private val highConfidenceCount = AtomicInteger(prefs.getInt(KEY_HIGH_CONF_COUNT, 0))
    private val moderateConfidenceCount = AtomicInteger(prefs.getInt(KEY_MODERATE_CONF_COUNT, 0))
    private val lowConfidenceCount = AtomicInteger(prefs.getInt(KEY_LOW_CONF_COUNT, 0))

    private val latencySumMs = AtomicLong(prefs.getLong(KEY_LATENCY_SUM, 0L))
    private val latencyCount = AtomicLong(prefs.getLong(KEY_LATENCY_COUNT, 0L))

    // Class prediction frequencies: label -> count
    private val classFrequency = ConcurrentHashMap<String, Int>()

    init {
        val savedFreq = prefs.getString(KEY_CLASS_FREQ, null)
        if (savedFreq != null) {
            try {
                val type = object : TypeToken<Map<String, Int>>() {}.type
                val map: Map<String, Int> = gson.fromJson(savedFreq, type)
                classFrequency.putAll(map)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val KEY_TOTAL_PREDICTIONS = "total_predictions"
        private const val KEY_HIGH_CONF_COUNT = "high_conf_count"
        private const val KEY_MODERATE_CONF_COUNT = "moderate_conf_count"
        private const val KEY_LOW_CONF_COUNT = "low_conf_count"
        private const val KEY_LATENCY_SUM = "latency_sum_ms"
        private const val KEY_LATENCY_COUNT = "latency_count"
        private const val KEY_CLASS_FREQ = "class_frequency_json"

        @Volatile private var INSTANCE: AnalyticsManager? = null

        fun getInstance(context: Context): AnalyticsManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AnalyticsManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    fun recordPrediction(decision: PredictionDecision, latencyMs: Long) {
        totalPredictions.incrementAndGet()
        when (decision.tier) {
            ConfidenceTier.HIGH -> highConfidenceCount.incrementAndGet()
            ConfidenceTier.MODERATE -> moderateConfidenceCount.incrementAndGet()
            ConfidenceTier.LOW -> lowConfidenceCount.incrementAndGet()
        }

        if (latencyMs > 0) {
            latencySumMs.addAndGet(latencyMs)
            latencyCount.incrementAndGet()
        }

        classFrequency.compute(decision.topLabel) { _, current -> (current ?: 0) + 1 }

        // Periodically persist to SharedPreferences
        if (totalPredictions.get() % 5 == 0) {
            persist()
        }
    }

    @Synchronized
    fun persist() {
        prefs.edit()
            .putInt(KEY_TOTAL_PREDICTIONS, totalPredictions.get())
            .putInt(KEY_HIGH_CONF_COUNT, highConfidenceCount.get())
            .putInt(KEY_MODERATE_CONF_COUNT, moderateConfidenceCount.get())
            .putInt(KEY_LOW_CONF_COUNT, lowConfidenceCount.get())
            .putLong(KEY_LATENCY_SUM, latencySumMs.get())
            .putLong(KEY_LATENCY_COUNT, latencyCount.get())
            .putString(KEY_CLASS_FREQ, gson.toJson(classFrequency))
            .apply()
    }

    fun getSummary(): AnalyticsSummary {
        val total = totalPredictions.get()
        val count = latencyCount.get()
        val avgLatency = if (count > 0) latencySumMs.get().toFloat() / count else 0f
        return AnalyticsSummary(
            totalInferences = total,
            highConfidenceCount = highConfidenceCount.get(),
            moderateConfidenceCount = moderateConfidenceCount.get(),
            lowConfidenceCount = lowConfidenceCount.get(),
            averageLatencyMs = avgLatency,
            topSigns = classFrequency.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        )
    }

    @Synchronized
    fun reset() {
        totalPredictions.set(0)
        highConfidenceCount.set(0)
        moderateConfidenceCount.set(0)
        lowConfidenceCount.set(0)
        latencySumMs.set(0L)
        latencyCount.set(0L)
        classFrequency.clear()
        prefs.edit().clear().apply()
    }
}

data class AnalyticsSummary(
    val totalInferences: Int,
    val highConfidenceCount: Int,
    val moderateConfidenceCount: Int,
    val lowConfidenceCount: Int,
    val averageLatencyMs: Float,
    val topSigns: List<Pair<String, Int>>
)

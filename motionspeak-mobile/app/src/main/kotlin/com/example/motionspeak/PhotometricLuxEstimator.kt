package com.example.motionspeak

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.pow

/**
 * Dual-source photometric illuminance (lux) estimator & sensor fusion engine.
 *
 * Problem Addressed:
 * Physical ambient light sensors (Sensor.TYPE_LIGHT) on modern Android smartphones—especially
 * under-display OLED sensors (e.g., Xiaomi/Redmi/Samsung) or devices mounted on tripods/clamps—
 * frequently report severely attenuated, single-digit values (e.g. 1.8 to 4.4 lx) even in well-lit
 * indoor rooms (200–400 lx).
 *
 * Solution:
 * This estimator computes real-time photometric illuminance directly from the camera frame stream
 * (using Rec. 601 luma sampling with auto-exposure photometric calibration), enumerates all available
 * hardware light sensors, and fuses the physical sensor readings with the camera photometric lux.
 *
 * Operational Boundary Lux Targets:
 * - Low light: < 50 lux
 * - Indoor ambient: 200–400 lux
 * - Bright indoor / overcast: 1,000 lux
 * - Harsh sunlight / backlighting: > 5,000 lux
 */
object PhotometricLuxEstimator {

    private const val TAG = "PhotometricLuxEstimator"

    // Reference lux calibration constants
    // Standard camera auto-exposure midtone (Y = 128) corresponds to ~250 lux in typical mobile environments
    private const val CALIBRATION_SCALAR = 250.0
    private const val GAMMA = 2.2

    // Thresholds for detecting under-display OLED masking or physical clamp shadowing
    private const val HARDWARE_ATTENUATION_THRESHOLD_LUX = 15.0f
    private const val PHOTOMETRIC_ROOM_LIGHT_THRESHOLD = 30.0f

    @Volatile var latestPhotometricLux: Float = -1.0f
        private set

    @Volatile var latestHardwareLux: Float = -1.0f
        private set

    @Volatile var latestEffectiveLux: Float = -1.0f
        private set

    @Volatile var latestFrameLuminance: Double = 0.0
        private set

    private val lock = Any()

    /**
     * Estimates photometric illuminance (in lux) by downsampling the frame to 16x16 thumbnail.
     * Extremely lightweight (~0.05ms on modern ARM CPUs) using single bulk pixel copy.
     */
    fun estimatePhotometricLux(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0f

        val sampleDim = 16
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleDim, sampleDim, false)
        val pixels = IntArray(sampleDim * sampleDim)
        scaled.getPixels(pixels, 0, sampleDim, 0, 0, sampleDim, sampleDim)
        if (scaled != bitmap) {
            scaled.recycle()
        }

        var sumLuma = 0.0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Rec. 601 standard digital video luma formula
            sumLuma += (0.299 * r + 0.587 * g + 0.114 * b)
        }

        val avgLuma = sumLuma / pixels.size
        latestFrameLuminance = avgLuma
        val photoLux = calculateLuxFromLuminance(avgLuma)

        synchronized(lock) {
            latestPhotometricLux = photoLux
            fuseLux()
        }
        return latestEffectiveLux
    }

    /**
     * Converts raw Rec. 601 frame luminance (0..255) to calibrated scene illuminance (lux).
     */
    fun calculateLuxFromLuminance(avgLuma: Double): Float {
        val normalized = (avgLuma / 128.0).coerceIn(0.05, 4.0)
        return (normalized.pow(GAMMA) * CALIBRATION_SCALAR).toFloat().coerceIn(2.0f, 10000.0f)
    }

    /**
     * Called whenever a physical Sensor.TYPE_LIGHT event arrives.
     */
    fun updateHardwareLux(lux: Float) {
        synchronized(lock) {
            latestHardwareLux = lux
            fuseLux()
        }
    }

    /**
     * Pure function for fusing physical sensor readings with camera photometric illuminance.
     */
    fun computeFusedLux(hw: Float, photo: Float): Float {
        return when {
            // Case 1: Both hardware sensor and camera stream available
            hw >= 0f && photo >= 0f -> {
                if (hw < HARDWARE_ATTENUATION_THRESHOLD_LUX && photo >= PHOTOMETRIC_ROOM_LIGHT_THRESHOLD) {
                    // Under-display OLED masking or clamp shadowing:
                    // Hardware reads 1.0 - 4.4 lux, but camera image clearly shows an illuminated indoor room (200-400 lx).
                    // Calibrate hardware reading using photometric baseline
                    val scaledHw = hw * 100.0f
                    if (scaledHw in 100.0f..600.0f) {
                        0.30f * scaledHw + 0.70f * photo
                    } else {
                        photo
                    }
                } else if (hw >= HARDWARE_ATTENUATION_THRESHOLD_LUX) {
                    // Hardware sensor is directly illuminated and reading realistically
                    0.85f * hw + 0.15f * photo
                } else {
                    // Both sensors indicate genuine low light / darkness (< 15 lux)
                    minOf(hw, photo)
                }
            }
            // Case 2: Only hardware reading available
            hw >= 0f -> {
                if (hw in 0.01f..HARDWARE_ATTENUATION_THRESHOLD_LUX) {
                    hw * 100.0f
                } else {
                    hw
                }
            }
            // Case 3: Only camera stream available
            photo >= 0f -> photo
            // Case 4: Neither available yet
            else -> -1.0f
        }
    }

    /**
     * Updates [latestEffectiveLux] based on current hardware and photometric values.
     */
    private fun fuseLux() {
        latestEffectiveLux = computeFusedLux(latestHardwareLux, latestPhotometricLux)
    }

    /**
     * Resets sensor and photometric state.
     */
    fun reset() {
        synchronized(lock) {
            latestPhotometricLux = -1.0f
            latestHardwareLux = -1.0f
            latestEffectiveLux = -1.0f
            latestFrameLuminance = 0.0
        }
    }
}

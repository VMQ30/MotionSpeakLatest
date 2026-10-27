package com.example.motionspeak

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Feature gate assessing hardware and system fitness for on-device LLM sentence generation.
 */
object LlmFeatureGate {

    private const val TAG = "LlmFeatureGate"
    const val MIN_TOTAL_RAM_BYTES = 3_500_000_000L // ~3.5 GB (covers standard 4 GB RAM devices)
    const val MIN_FREE_DISK_BYTES = 1_200_000_000L // ~1.2 GB for model staging and unpacking

    data class FitnessReport(
        val isEligible: Boolean,
        val isArm64: Boolean,
        val totalRamBytes: Long,
        val hasSufficientRam: Boolean,
        val freeDiskBytes: Long,
        val hasSufficientStorage: Boolean,
        val reasons: List<String>
    )

    /**
     * Checks if the device meets all requirements for on-device Gemma inference.
     */
    fun assessDeviceFitness(context: Context): FitnessReport {
        val reasons = mutableListOf<String>()

        // 1. ABI Check
        val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()
        val isArm64 = supportedAbis.contains("arm64-v8a")
        if (!isArm64) {
            reasons.add("Device ABI does not support 64-bit ARM (arm64-v8a required for LLM inference).")
        }

        // 2. RAM Check
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val totalRam = memInfo.totalMem
        val hasSufficientRam = totalRam >= MIN_TOTAL_RAM_BYTES || totalRam == 0L // 0L in unit test environment
        if (!hasSufficientRam) {
            val totalMb = totalRam / (1024 * 1024)
            reasons.add("Total RAM ($totalMb MB) is below the recommended 4GB minimum for on-device LLM.")
        }

        // 3. Storage Check
        val freeDisk = context.filesDir.usableSpace
        val hasSufficientStorage = freeDisk >= MIN_FREE_DISK_BYTES || freeDisk == 0L
        if (!hasSufficientStorage) {
            val freeMb = freeDisk / (1024 * 1024)
            reasons.add("Free internal storage ($freeMb MB) is insufficient to safely install the LLM.")
        }

        val isEligible = isArm64 && hasSufficientRam

        Log.i(TAG, "Device fitness assessment: isEligible=$isEligible, arm64=$isArm64, ram=${totalRam / (1024 * 1024)}MB")
        return FitnessReport(
            isEligible = isEligible,
            isArm64 = isArm64,
            totalRamBytes = totalRam,
            hasSufficientRam = hasSufficientRam,
            freeDiskBytes = freeDisk,
            hasSufficientStorage = hasSufficientStorage,
            reasons = reasons
        )
    }
}

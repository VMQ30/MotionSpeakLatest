package com.example.motionspeak

import android.content.Context
import android.util.Log

/**
 * Factory creating the appropriate [SentenceSynthesizer] instance based on
 * user settings, device hardware eligibility, and model availability.
 */
object SynthesizerFactory {

    private const val TAG = "SynthesizerFactory"

    /**
     * Creates and returns the active [SentenceSynthesizer].
     * Defaults to [DeterministicSentenceSynthesizer] if LLM is disabled, unsupported,
     * or the model file has not yet been downloaded.
     */
    fun createSynthesizer(
        context: Context,
        appSettings: AppSettings,
        wordBank: List<WordBankWord> = defaultWordBank()
    ): SentenceSynthesizer {
        val fallback = DeterministicSentenceSynthesizer(wordBank)

        if (!appSettings.isLlmSynthesisEnabled) {
            Log.i(TAG, "LLM synthesis disabled by user setting; using deterministic synthesizer.")
            return fallback
        }

        val fitness = LlmFeatureGate.assessDeviceFitness(context)
        if (!fitness.isEligible) {
            Log.w(TAG, "Device ineligible for on-device LLM: ${fitness.reasons.joinToString("; ")}; using deterministic synthesizer.")
            return fallback
        }

        if (!LlmModelManager.hasModel(context)) {
            Log.i(TAG, "LLM model not downloaded; using deterministic synthesizer.")
            return fallback
        }

        val modelFile = LlmModelManager.getModelFile(context)
        Log.i(TAG, "Configuring LlmSynthesizer with local model at ${modelFile.name}")
        return LlmSynthesizer(
            context = context.applicationContext,
            modelFile = modelFile,
            fallbackSynthesizer = fallback
        )
    }
}

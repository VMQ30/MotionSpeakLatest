package com.example.motionspeak

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Best-effort Male/Female voice selection prioritizing Filipino (Tagalog) speech.
 * Android's TextToSpeech API exposes no gender metadata on Voice, so this checks
 * voice attributes and naming conventions, prioritizing Filipino (fil-PH / tl-PH)
 * voices and falling back gracefully to available system voices if a device lacks
 * offline Filipino voice data.
 */
object TtsVoiceHelper {

    // Resolving a voice requires engine.voices — a synchronous binder call into
    // the TTS engine process that returns the entire voice set (often 100+
    // Voice objects) before we filter and sort it. That is far too expensive to
    // repeat, so the result is cached against the preference it was resolved
    // for. Volatile because resolution happens off the main thread while
    // playback is triggered from it.
    @Volatile private var cachedVoice: Voice? = null
    @Volatile private var cachedForVoiceType: String? = null

    /**
     * Applies the preferred Filipino voice and locale to [tts].
     *
     * Only queries the engine when the preference changed since the last
     * resolution (or nothing is cached yet). **Call this off the main thread**
     * the first time — the underlying query blocks.
     */
    fun applyPreferredVoice(tts: TextToSpeech?, appSettings: AppSettings) {
        val engine = tts ?: return
        val voiceType = appSettings.voiceType

        // Fast path: preference unchanged, so reuse the voice we already picked.
        cachedVoice?.let {
            if (cachedForVoiceType == voiceType) {
                engine.voice = it
                return
            }
        }

        val preferFemale = voiceType == AppSettings.VOICE_FEMALE
        val allVoices = try {
            engine.voices?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

        // 1. Prioritize Filipino / Tagalog voices (fil-PH, tl-PH, fil, tl)
        val filVoices = allVoices.filter { v ->
            val lang = v.locale.language.lowercase()
            val tag = v.locale.toLanguageTag().lowercase()
            lang == "fil" || lang == "tl" || tag.startsWith("fil") || tag.startsWith("tl")
        }.sortedBy { it.name }

        val candidateVoices = if (filVoices.isNotEmpty()) {
            filVoices
        } else {
            // Fallback to English voices if the device engine has no Filipino voice pack installed
            allVoices.filter { it.locale.language.lowercase() == "en" }.sortedBy { it.name }
                .ifEmpty { allVoices.sortedBy { it.name } }
        }

        if (candidateVoices.isEmpty()) {
            applyFilipinoLocale(engine)
            return
        }

        val byName = candidateVoices.firstOrNull { v ->
            val n = v.name.lowercase()
            val features = try { v.features?.map { it.lowercase() }.orEmpty() } catch (e: Exception) { emptyList() }
            val isFemale = n.contains("female") || n.contains("fem") || n.contains("-fie") || n.contains("-fic") || n.contains("-fia") || features.contains("female")
            val isMale = (n.contains("male") || n.contains("-fim") || n.contains("-fib") || n.contains("-fid") || features.contains("male")) && !n.contains("female")

            if (preferFemale) isFemale else isMale
        }

        val fallback = if (candidateVoices.size > 1) {
            if (preferFemale) candidateVoices.last() else candidateVoices.first()
        } else {
            candidateVoices.first()
        }

        val chosen = byName ?: fallback
        cachedVoice        = chosen
        cachedForVoiceType = voiceType

        try {
            engine.language = chosen.locale
        } catch (ignored: Exception) {}

        engine.voice = chosen
    }

    /**
     * Applies the best available Filipino locale to [engine].
     */
    fun applyFilipinoLocale(engine: TextToSpeech) {
        val locales = listOf(
            Locale("fil", "PH"),
            Locale("tl", "PH"),
            Locale("fil"),
            Locale("tl")
        )
        for (loc in locales) {
            try {
                if (engine.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = loc
                    return
                }
            } catch (ignored: Exception) {}
        }
        try {
            engine.setLanguage(Locale("fil", "PH"))
        } catch (ignored: Exception) {}
    }

    /**
     * Drops the cached voice so the next [applyPreferredVoice] re-resolves.
     * Call when the user changes the voice preference.
     */
    fun invalidate() {
        cachedVoice = null
        cachedForVoiceType = null
    }
}

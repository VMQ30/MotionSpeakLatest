package com.example.motionspeak

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.button.MaterialButton
import com.example.motionspeak.MotionSpeakApplication.Companion.wrapFontScale

/**
 * The profile card's name line: the stored name, or a friendly fallback when
 * none has been set (spec §5's onboarding name step is optional).
 */
internal fun profileDisplayName(userName: String?): String =
    userName?.trim()?.takeIf { it.isNotEmpty() } ?: "Add your name"

/**
 * The profile card's avatar initial: the first letter of the stored name,
 * upper-cased, or a generic mark when there is no name to draw one from.
 */
internal fun profileInitial(userName: String?): String =
    userName?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

/**
 * SettingsActivity.kt
 *
 * Preferences managed:
 *   - Volume level (0–100%)
 *   - Voice type: Male / Female (for TTS)
 *   - Font size: 80–125% via SeekBar + Small / Normal / Large presets
 *   - Dark / Light mode toggle
 *   - Reset to default
 *   - Replay onboarding tutorial
 *   - Name (profile card, "Tap to change your name")
 */
class SettingsActivity : BaseActivity() {

    private lateinit var appSettings: AppSettings
    private var speech: SpeechPlayer? = null

    // In-memory state, mirrors AppSettings (the store MainActivity/WordDetailActivity's
    // TTS actually reads — everything here must go through it, not a separate prefs file)
    private var currentVolume   = 0
    private var currentVoice    = "MALE"
    private var currentDarkMode = false
    private var currentFontSize = AppSettings.FONT_SIZE_NORMAL
    private var currentHaptic   = AppSettings.HAPTIC_LIGHT
    private var currentLlmSynthesisEnabled = true
    private var currentSentenceLanguage = "en"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        appSettings = AppSettings.getInstance(this)
        speech = SpeechPlayer(this, appSettings, lifecycleScope)

        BottomNavHelper.setup(this, Tab.SETTINGS)
        loadPreferences()
        bindProfileCard()
        bindVolumeSeekBar()
        bindVoiceToggle()
        bindTestAudio()
        bindFontSizeControls()
        bindDarkModeSwitch()
        bindHapticToggle()
        bindSentenceSynthesisControls()
        bindPrivacyControls()
        bindAdvancedTools()
        bindResetButton()
        bindReplayTutorial()
    }

    override fun onResume() {
        super.onResume()
        // The name can also change from the onboarding replay flow, which
        // returns here without recreating this Activity.
        refreshProfileCard()
        refreshLlmModelStatus()
    }

    // ── Load saved preferences ────────────────────────────────────────────────

    private fun loadPreferences() {
        currentVolume = appSettings.volume
        currentVoice = if (appSettings.voiceType == AppSettings.VOICE_FEMALE) "FEMALE" else "MALE"
        currentDarkMode = appSettings.isDarkMode
        currentFontSize = appSettings.fontSize
        currentHaptic = appSettings.hapticFeedback
        currentLlmSynthesisEnabled = appSettings.isLlmSynthesisEnabled
        currentSentenceLanguage = appSettings.sentenceLanguage

        // Volume
        findViewById<SeekBar>(R.id.seekVolume)?.progress = currentVolume
        findViewById<TextView>(R.id.tvVolumeValue)?.text = "$currentVolume%"

        // Voice
        applyVoiceSelection(currentVoice, animate = false)

        // Font size
        applyFontSizeSelection(currentFontSize, animate = false)

        // Haptic feedback
        applyHapticSelection(currentHaptic)

        // Sentence generation
        findViewById<SwitchMaterial>(R.id.switchLlmSynthesis)?.isChecked = currentLlmSynthesisEnabled
        applySentenceLanguageSelection(currentSentenceLanguage)
        refreshLlmModelStatus()

        // Dark mode
        val darkSwitch = findViewById<SwitchMaterial>(R.id.switchDarkMode)
        darkSwitch?.isChecked = currentDarkMode
        updateDarkModeSubtitle(currentDarkMode)

        // Privacy controls
        findViewById<SwitchMaterial>(R.id.switchRecordHistory)?.isChecked = appSettings.recordTranslationHistory
        findViewById<SwitchMaterial>(R.id.switchFacialAnalysis)?.isChecked = appSettings.isFacialAnalysisEnabled
    }

    // ── Volume ────────────────────────────────────────────────────────────────

    private fun bindVolumeSeekBar() {
        val seekBar = findViewById<SeekBar>(R.id.seekVolume)
        val tvValue = findViewById<TextView>(R.id.tvVolumeValue)

        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                currentVolume = progress
                tvValue?.text = "$progress%"
                if (fromUser) {
                    appSettings.volume = currentVolume
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { /* volume is local-only, no server sync */ }
        })
    }

    // ── Voice type ────────────────────────────────────────────────────────────

    private fun bindVoiceToggle() {
        findViewById<View>(R.id.btnVoiceMale)?.setOnClickListener {
            applyVoiceSelection("MALE")
            appSettings.voiceType = AppSettings.VOICE_MALE
            // The chosen Voice is cached against the preference, so changing it
            // has to drop the cache or playback would keep the old voice.
            TtsVoiceHelper.invalidate()
            speech?.applyVoice()
        }
        findViewById<View>(R.id.btnVoiceFemale)?.setOnClickListener {
            applyVoiceSelection("FEMALE")
            appSettings.voiceType = AppSettings.VOICE_FEMALE
            TtsVoiceHelper.invalidate()
            speech?.applyVoice()
        }
    }

    private fun applyVoiceSelection(voice: String, animate: Boolean = true) {
        currentVoice = voice
        val male   = findViewById<TextView>(R.id.btnVoiceMale)
        val female = findViewById<TextView>(R.id.btnVoiceFemale)
        val onColor  = getColor(R.color.ms_cat_violet_text)
        val offColor = getColor(R.color.ms_cat_violet_accent)
        val onTint   = getColorStateList(R.color.ms_cat_violet_bg)
        val offTint  = getColorStateList(R.color.ms_cat_violet_tint)

        if (voice == "MALE") {
            male?.backgroundTintList = onTint
            male?.setTextColor(onColor)
            female?.backgroundTintList = offTint
            female?.setTextColor(offColor)
        } else {
            female?.backgroundTintList = onTint
            female?.setTextColor(onColor)
            male?.backgroundTintList = offTint
            male?.setTextColor(offColor)
        }
    }

    // ── Test audio ────────────────────────────────────────────────────────────

    private fun bindTestAudio() {
        findViewById<View>(R.id.btnTestAudio)?.setOnClickListener {
            Haptics.tick(it)
            speech?.speak("Kamusta, magandang araw!", queueUntilReady = true)
            Toast.makeText(this, "Testing audio: \"Kamusta, magandang araw!\"", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Font size ─────────────────────────────────────────────────────────────

    private fun bindFontSizeControls() {
        val seekBar   = findViewById<SeekBar>(R.id.seekFontSize)
        val btnSmall  = findViewById<TextView>(R.id.btnFontSmall)
        val btnNormal = findViewById<TextView>(R.id.btnFontNormal)
        val btnLarge  = findViewById<TextView>(R.id.btnFontLarge)

        // SeekBar spans FONT_SIZE_SMALL..FONT_SIZE_LARGE (80..125), max = range size (45).
        seekBar?.max = AppSettings.FONT_SIZE_LARGE - AppSettings.FONT_SIZE_SMALL
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress + AppSettings.FONT_SIZE_SMALL
                currentFontSize = pct
                findViewById<TextView>(R.id.tvFontSizeValue)?.text = "$pct%"
                if (fromUser) {
                    applyFontSizeButtons(pct)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val pct = (sb?.progress ?: 0) + AppSettings.FONT_SIZE_SMALL
                saveFontSize(pct)
            }
        })

        btnSmall?.setOnClickListener  { applyFontSizeSelection(AppSettings.FONT_SIZE_SMALL);  saveFontSize(AppSettings.FONT_SIZE_SMALL) }
        btnNormal?.setOnClickListener { applyFontSizeSelection(AppSettings.FONT_SIZE_NORMAL); saveFontSize(AppSettings.FONT_SIZE_NORMAL) }
        btnLarge?.setOnClickListener  { applyFontSizeSelection(AppSettings.FONT_SIZE_LARGE);  saveFontSize(AppSettings.FONT_SIZE_LARGE) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun applyFontSizeSelection(pct: Int, animate: Boolean = true) {
        currentFontSize = pct
        // SeekBar progress = pct − FONT_SIZE_SMALL, so 80→0, 100→20, 125→45
        findViewById<SeekBar>(R.id.seekFontSize)?.progress = pct - AppSettings.FONT_SIZE_SMALL
        findViewById<TextView>(R.id.tvFontSizeValue)?.text = "$pct%"
        applyFontSizeButtons(pct)
    }

    /** Highlights whichever preset button exactly matches [pct]. */
    private fun applyFontSizeButtons(pct: Int) {
        val btnSmall  = findViewById<TextView>(R.id.btnFontSmall)
        val btnNormal = findViewById<TextView>(R.id.btnFontNormal)
        val btnLarge  = findViewById<TextView>(R.id.btnFontLarge)
        val onColor  = getColor(R.color.ms_cat_violet_text)
        val offColor = getColor(R.color.ms_cat_violet_accent)
        val onTint   = getColorStateList(R.color.ms_cat_violet_bg)
        val offTint  = getColorStateList(R.color.ms_cat_violet_tint)

        fun style(btn: TextView?, active: Boolean) {
            btn?.backgroundTintList = if (active) onTint else offTint
            btn?.setTextColor(if (active) onColor else offColor)
        }
        style(btnSmall,  pct == AppSettings.FONT_SIZE_SMALL)
        style(btnNormal, pct == AppSettings.FONT_SIZE_NORMAL)
        style(btnLarge,  pct == AppSettings.FONT_SIZE_LARGE)
    }

    /**
     * Persists the new font size, then recreates so the settings screen
     * re-inflates at the updated scale immediately. All other screens apply
     * the new scale via BaseActivity.
     */
    private fun saveFontSize(pct: Int) {
        if (appSettings.fontSize == pct) return
        appSettings.fontSize = pct
        MotionSpeakApplication.applyFontScale(applicationContext, pct)
        recreate()
    }

    // ── Dark mode ─────────────────────────────────────────────────────────────

    private fun bindDarkModeSwitch() {
        val switch = findViewById<SwitchMaterial>(R.id.switchDarkMode)
        switch?.setOnCheckedChangeListener { _, isChecked ->
            currentDarkMode = isChecked
            updateDarkModeSubtitle(isChecked)
            appSettings.isDarkMode = isChecked
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun updateDarkModeSubtitle(isDark: Boolean) {
        val label = if (isDark) "Currently using Dark mode" else "Currently using Light mode"
        findViewById<TextView>(R.id.tvDarkModeSubtitle)?.text = label
    }

    // ── Haptic feedback ────────────────────────────────────────────────────────

    private fun bindHapticToggle() {
        findViewById<View>(R.id.btnHapticOff)?.setOnClickListener {
            applyHapticSelection(AppSettings.HAPTIC_OFF)
            appSettings.hapticFeedback = AppSettings.HAPTIC_OFF
        }
        findViewById<View>(R.id.btnHapticLight)?.setOnClickListener {
            applyHapticSelection(AppSettings.HAPTIC_LIGHT)
            appSettings.hapticFeedback = AppSettings.HAPTIC_LIGHT
            Haptics.trigger(this, it, AppSettings.HAPTIC_LIGHT)
        }
        findViewById<View>(R.id.btnHapticMax)?.setOnClickListener {
            applyHapticSelection(AppSettings.HAPTIC_MAX)
            appSettings.hapticFeedback = AppSettings.HAPTIC_MAX
            Haptics.trigger(this, it, AppSettings.HAPTIC_MAX)
        }
    }

    private fun applyHapticSelection(mode: String) {
        currentHaptic = mode
        val btnOff   = findViewById<TextView>(R.id.btnHapticOff)
        val btnLight = findViewById<TextView>(R.id.btnHapticLight)
        val btnMax   = findViewById<TextView>(R.id.btnHapticMax)
        val onColor  = getColor(R.color.ms_cat_violet_text)
        val offColor = getColor(R.color.ms_cat_violet_accent)
        val onTint   = getColorStateList(R.color.ms_cat_violet_bg)
        val offTint  = getColorStateList(R.color.ms_cat_violet_tint)

        fun style(btn: TextView?, active: Boolean) {
            btn?.backgroundTintList = if (active) onTint else offTint
            btn?.setTextColor(if (active) onColor else offColor)
        }
        style(btnOff,   mode == AppSettings.HAPTIC_OFF)
        style(btnLight, mode == AppSettings.HAPTIC_LIGHT)
        style(btnMax,   mode == AppSettings.HAPTIC_MAX)
    }

    // ── Privacy & Advanced Tools ──────────────────────────────────────────

    private fun bindPrivacyControls() {
        val switchHistory = findViewById<SwitchMaterial>(R.id.switchRecordHistory)
        switchHistory?.isChecked = appSettings.recordTranslationHistory
        switchHistory?.setOnCheckedChangeListener { _, isChecked ->
            appSettings.recordTranslationHistory = isChecked
        }

        val switchFacial = findViewById<SwitchMaterial>(R.id.switchFacialAnalysis)
        switchFacial?.isChecked = appSettings.isFacialAnalysisEnabled
        switchFacial?.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isFacialAnalysisEnabled = isChecked
        }

        val switchGpu = findViewById<SwitchMaterial>(R.id.switchGpuInference)
        switchGpu?.isChecked = appSettings.isInferenceGpuEnabled
        switchGpu?.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isInferenceGpuEnabled = isChecked
        }
    }

    private fun bindAdvancedTools() {
        findViewById<View>(R.id.rowSignerCalibration)?.setOnClickListener {
            startActivity(Intent(this, SignerCalibrationActivity::class.java))
        }

        findViewById<View>(R.id.rowTwoWayConversation)?.setOnClickListener {
            startActivity(Intent(this, TwoWayConversationActivity::class.java))
        }

        findViewById<View>(R.id.rowDiagnostics)?.setOnClickListener {
            startActivity(Intent(this, QualityDashboardActivity::class.java))
        }
    }

    private fun showDiagnosticsDialog() {
        val (p50, p95, p99) = PipelineProfiler.getPercentiles()
        val summary = AnalyticsManager.getInstance(this).getSummary()

        val msg = """
• Model: motionspeak.tflite (On-device TFLite)
• Security: Hardware-backed KeyStore encryption
• Total Inferences: ${summary.totalInferences}
• Average Latency: ${"%.1f".format(summary.averageLatencyMs)} ms
• Pipeline Latency Percentiles:
    p50: ${"%.1f".format(p50)} ms | p95: ${"%.1f".format(p95)} ms | p99: ${"%.1f".format(p99)} ms
• Confidence Distribution:
    High: ${summary.highConfidenceCount} | Moderate: ${summary.moderateConfidenceCount} | Low: ${summary.lowConfidenceCount}
• Signer Calibration:
    Hand Scale: ${"%.2f".format(appSettings.signerHandScale)}x | Speed Factor: ${"%.2f".format(appSettings.signerSpeedFactor)}x
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("System & Model Diagnostics")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
    }

    // ── Sentence Generation (LLM) ─────────────────────────────────────────────

    private fun bindSentenceSynthesisControls() {
        val switchLlm = findViewById<SwitchMaterial>(R.id.switchLlmSynthesis)
        switchLlm?.setOnCheckedChangeListener { _, isChecked ->
            currentLlmSynthesisEnabled = isChecked
            appSettings.isLlmSynthesisEnabled = isChecked
        }

        findViewById<View>(R.id.btnLangEnglish)?.setOnClickListener {
            applySentenceLanguageSelection("en")
            appSettings.sentenceLanguage = "en"
        }

        findViewById<View>(R.id.btnLangFilipino)?.setOnClickListener {
            applySentenceLanguageSelection("fil")
            appSettings.sentenceLanguage = "fil"
        }
    }

    private fun applySentenceLanguageSelection(lang: String) {
        currentSentenceLanguage = lang
        val isEnglish = lang.equals("en", ignoreCase = true)
        val btnEn = findViewById<TextView>(R.id.btnLangEnglish)
        val btnFil = findViewById<TextView>(R.id.btnLangFilipino)

        val onColor  = getColor(R.color.ms_cat_violet_text)
        val offColor = getColor(R.color.ms_cat_violet_accent)
        val onTint   = getColorStateList(R.color.ms_cat_violet_bg)
        val offTint  = getColorStateList(R.color.ms_cat_violet_tint)

        btnEn?.setTextColor(if (isEnglish) onColor else offColor)
        btnEn?.backgroundTintList = if (isEnglish) onTint else offTint

        btnFil?.setTextColor(if (!isEnglish) onColor else offColor)
        btnFil?.backgroundTintList = if (!isEnglish) onTint else offTint
    }

    private fun refreshLlmModelStatus() {
        val isInstalled = LlmModelManager.hasModel(this)
        val size = LlmModelManager.getFormattedModelSize(this)
        findViewById<TextView>(R.id.tvLlmModelStatus)?.text =
            if (isInstalled) "Installed ($size) • Fully Offline" else "Not downloaded • Using deterministic fallback"

        val fitness = LlmFeatureGate.assessDeviceFitness(this)
        findViewById<TextView>(R.id.tvLlmHardwareFitness)?.text =
            if (fitness.isEligible) "Device compatible (${if (fitness.isArm64) "64-bit ARM" else ""})"
            else "Device note: ${fitness.reasons.firstOrNull() ?: "May fall back to deterministic mode"}"
    }

    // ── Reset to default ──────────────────────────────────────────────────────

    private fun bindResetButton() {
        findViewById<View>(R.id.btnResetDefaults).setOnClickListener {
            val view = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
            val dialog = AlertDialog.Builder(this).setView(view).create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            view.findViewById<TextView>(R.id.tvConfirmTitle).text = "Reset to default?"
            view.findViewById<TextView>(R.id.tvConfirmMessage).text =
                "All settings will be restored to their original configuration."
            view.findViewById<MaterialButton>(R.id.btnConfirmAction).text = "Reset"

            view.findViewById<MaterialButton>(R.id.btnConfirmCancel).setOnClickListener { dialog.dismiss() }
            view.findViewById<MaterialButton>(R.id.btnConfirmAction).setOnClickListener {
                appSettings.resetToDefault()
                MotionSpeakApplication.applyFontScale(applicationContext, AppSettings.FONT_SIZE_NORMAL)
                TtsVoiceHelper.invalidate()   // resets voiceType as well
                loadPreferences()
                refreshProfileCard()

                AppCompatDelegate.setDefaultNightMode(
                    if (currentDarkMode) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )

                Toast.makeText(this, "Settings reset to default.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                // Recreate so font scale is re-applied immediately after reset.
                recreate()
            }

            dialog.show()
        }
    }

    // ── Profile (name) ────────────────────────────────────────────────────────

    private fun bindProfileCard() {
        refreshProfileCard()
        findViewById<View>(R.id.rowProfile).setOnClickListener { showEditNameDialog() }
    }

    private fun refreshProfileCard() {
        val name = appSettings.userName
        findViewById<TextView>(R.id.tvProfileName).text = profileDisplayName(name)
        findViewById<TextView>(R.id.tvProfileInitial).text = profileInitial(name)
    }

    private fun showEditNameDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_field, null, false)
        val til = dialogView.findViewById<TextInputLayout>(R.id.tilDialogField)
        val et = dialogView.findViewById<TextInputEditText>(R.id.etDialogField)
        val current = appSettings.userName.orEmpty()

        til.hint = "Your first name"
        til.counterMaxLength = 40
        til.isCounterEnabled = true
        til.boxStrokeColor = getColor(R.color.ms_cat_violet_bg)
        til.setBoxStrokeColorStateList(getColorStateList(R.color.ms_cat_violet_bg))
        til.defaultHintTextColor = getColorStateList(R.color.ms_cat_violet_accent)
        et.setText(current)
        et.setSelection(current.length)

        val dialog = AlertDialog.Builder(this)
            .setTitle("What should we call you?")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            et.requestFocus()
            val violetColor = getColor(R.color.ms_cat_violet_accent)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(violetColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(violetColor)
            // A blank name is a valid choice here (it clears the greeting back
            // to "no name"), unlike WordBankActivity's category-name dialogs.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                appSettings.userName = et.text.toString()
                refreshProfileCard()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    // ── Replay tutorial ───────────────────────────────────────────────────────

    private fun bindReplayTutorial() {
        findViewById<View>(R.id.rowReplayTutorial).setOnClickListener {
            appSettings.isReplayTutorialActive = true
            appSettings.hasCompletedInitialOnboarding = true
            appSettings.isCameraTooltipEligible = false
            appSettings.isOnboardingDone = false
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                putExtra(OnboardingActivity.EXTRA_IS_REPLAY, true)
            })
            finish()
        }
    }

    override fun onDestroy() {
        speech?.release()
        speech = null
        super.onDestroy()
    }

    // ── Back press ────────────────────────────────────────────────────────────

    @Deprecated("Use OnBackPressedDispatcher instead")
    override fun onBackPressed() {
        BottomNavHelper.open(this, Tab.HOME)
    }
}

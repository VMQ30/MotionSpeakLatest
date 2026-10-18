package com.example.motionspeak

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/**
 * Base activity for all SigLa / MotionSpeak screens.
 *
 * Ensures the user's chosen font-size scale (Settings -> Font size) is applied
 * consistently across every page:
 *   - [attachBaseContext] wraps the context with the scaled configuration before layout inflation.
 *   - [applyOverrideConfiguration] preserves the font scale if Android applies configuration overrides.
 *   - [onResume] detects if the font scale changed while another screen (like Settings) was active
 *     and recreates the screen so its layouts re-inflate with the updated scale.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var appliedFontScale: Float = 1.0f

    override fun attachBaseContext(newBase: Context) {
        val settings = AppSettings.getInstance(newBase)
        val scale = settings.fontSize / 100f
        appliedFontScale = scale
        super.attachBaseContext(SiglaApplication.wrapFontScale(newBase, settings.fontSize))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            val scale = AppSettings.getInstance(this).fontSize / 100f
            overrideConfiguration.fontScale = scale
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onResume() {
        super.onResume()
        val expectedScale = AppSettings.getInstance(this).fontSize / 100f
        if (Math.abs(appliedFontScale - expectedScale) > 0.01f) {
            appliedFontScale = expectedScale
            recreate()
        }
    }
}

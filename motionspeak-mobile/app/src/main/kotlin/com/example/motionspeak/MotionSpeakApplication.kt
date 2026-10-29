package com.example.motionspeak

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * AppCompatDelegate.setDefaultNightMode() only sets an in-memory flag for
 * the current process — it isn't restored automatically on a fresh launch.
 * Re-applying the saved preference here, before any Activity is created,
 * is what makes Dark Mode stick across app restarts.
 *
 * The same logic applies to font scale: we override the application-level
 * Configuration so every Activity inherits the right density before it
 * inflates its layout.
 */
class MotionSpeakApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = AppSettings.getInstance(this)

        // Dark mode
        AppCompatDelegate.setDefaultNightMode(
            if (settings.isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        // Font scale — apply once at process start; activities call wrapFontScale() in
        // attachBaseContext() to keep the per-window configuration in sync.
        with(this) { applyFontScale(settings.fontSize) }
    }

    companion object {
        /**
         * Wraps [base] with the font scale derived from [fontSizePercent].
         * Call this from every Activity's [attachBaseContext].
         */
        fun wrapFontScale(base: Context, fontSizePercent: Int): Context {
            val scale = fontSizePercent / 100f
            val config = Configuration(base.resources.configuration)
            config.fontScale = scale
            return base.createConfigurationContext(config)
        }

        /** Applies [fontSizePercent] to the application-level configuration. */
        fun Application.applyFontScale(fontSizePercent: Int) {
            MotionSpeakApplication.applyFontScale(this, fontSizePercent)
        }

        /** Applies [fontSizePercent] to the configuration of [context] (such as Application context). */
        fun applyFontScale(context: Context, fontSizePercent: Int) {
            val scale = fontSizePercent / 100f
            val config = context.resources.configuration
            if (Math.abs(config.fontScale - scale) > 0.01f) {
                config.fontScale = scale
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(config, context.resources.displayMetrics)
            }
        }
    }
}

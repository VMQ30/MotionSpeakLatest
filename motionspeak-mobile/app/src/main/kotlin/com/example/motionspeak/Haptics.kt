package com.example.motionspeak

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptic feedback and vibration utility for MotionSpeak / SigLa.
 * Supports configurable vibration levels: Off, Light vibration, and Max vibration.
 */
object Haptics {

    /** Triggers haptic feedback based on the user's stored setting. */
    fun trigger(view: View) {
        val appSettings = AppSettings.getInstance(view.context)
        trigger(view.context, view, appSettings.hapticFeedback)
    }

    /** Triggers haptic feedback with optional explicit mode override. */
    fun trigger(
        context: Context,
        view: View? = null,
        mode: String = AppSettings.getInstance(context).hapticFeedback
    ) {
        when (mode) {
            AppSettings.HAPTIC_OFF -> return
            AppSettings.HAPTIC_LIGHT -> vibrateLight(context, view)
            AppSettings.HAPTIC_MAX -> vibrateMax(context, view)
        }
    }

    /** Tap recording started or stopped for recognition. */
    fun tick(view: View) {
        trigger(view)
    }

    /** A word was recognized. */
    fun confirm(view: View) {
        trigger(view)
    }

    /** Low-confidence or rejected gesture. */
    fun reject(view: View) {
        trigger(view.context, view, AppSettings.HAPTIC_MAX)
    }

    private fun vibrateLight(context: Context, view: View?) {
        try {
            view?.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Throwable) {}

        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(25L, 100))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(25L)
            }
        } catch (_: Throwable) {}
    }

    private fun vibrateMax(context: Context, view: View?) {
        try {
            view?.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Throwable) {}

        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(80L, 255))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(80L)
            }
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun getVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        }
    }
}

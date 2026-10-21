package com.example.motionspeak

import android.content.Context
import android.content.Intent

/**
 * Handles SOS emergency trigger across the app.
 * Upon a 4-second hold on the circular hand button, launches the scarlet
 * SOS emergency window with unlimited audio loop, haptic vibration, and forced max volume.
 */
object SosHelper {
    fun trigger(context: Context) {
        val intent = Intent(context, SosActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}

package com.example.motionspeak

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton

enum class Tab { HOME, WORD_BANK, HISTORY, SETTINGS }

/**
 * Wires view_bottom_nav.xml on a tab screen (spec §3).
 *
 * Tabs are separate Activities. Switching reorders the existing instance to the
 * front with no animation, so a tab keeps its scroll position and state and the
 * switch feels like a tab change. The centre button opens the translator on top.
 */
object BottomNavHelper {

    private class TabViews(val tab: Tab, val root: Int, val icon: Int, val label: Int)

    private val TABS = listOf(
        TabViews(Tab.HOME, R.id.tabHome, R.id.tabHomeIcon, R.id.tabHomeLabel),
        TabViews(Tab.WORD_BANK, R.id.tabWordBank, R.id.tabWordBankIcon, R.id.tabWordBankLabel),
        TabViews(Tab.HISTORY, R.id.tabHistory, R.id.tabHistoryIcon, R.id.tabHistoryLabel),
        TabViews(Tab.SETTINGS, R.id.tabSettings, R.id.tabSettingsIcon, R.id.tabSettingsLabel),
    )

    internal fun activityFor(tab: Tab): Class<out Activity> = when (tab) {
        Tab.HOME -> HomeActivity::class.java
        Tab.WORD_BANK -> WordBankActivity::class.java
        Tab.HISTORY -> TranslationHistoryActivity::class.java
        Tab.SETTINGS -> SettingsActivity::class.java
    }

    fun setup(activity: Activity, current: Tab) {
        val on = ContextCompat.getColor(
            activity,
            when (current) {
                Tab.SETTINGS -> R.color.ms_cat_violet_accent
                Tab.HOME -> R.color.ms_home_accent
                else -> R.color.ms_brand_text
            }
        )
        val off = ContextCompat.getColor(activity, R.color.ms_text_secondary)
        val semibold = ResourcesCompat.getFont(activity, R.font.poppins_semibold)
        val regular = ResourcesCompat.getFont(activity, R.font.poppins_regular)
        for (t in TABS) {
            val selected = t.tab == current
            activity.findViewById<ImageView>(t.icon).imageTintList =
                ColorStateList.valueOf(if (selected) on else off)
            activity.findViewById<TextView>(t.label).apply {
                setTextColor(if (selected) on else off)
                typeface = if (selected) semibold else regular
            }
            activity.findViewById<View>(t.root).apply {
                isSelected = selected
                setOnClickListener { open(activity, t.tab) }
            }
        }
        val fab = activity.findViewById<View>(R.id.fabTranslate)
        if (fab != null) {
            val glowFab = fab as? SiriGlowFloatingActionButton
            val normalBg = ContextCompat.getColor(activity, R.color.ms_brand)
            val fullRed = ContextCompat.getColor(activity, R.color.ms_scarlet)

            var sosTriggered = false
            var redFadeAnimator: ValueAnimator? = null

            // Runnable that starts fading the circular button to red 2 seconds before SOS activates
            val startFadeRunnable = Runnable {
                redFadeAnimator?.cancel()
                redFadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 2000L // 2-second fade from t=2s to t=4s
                    interpolator = LinearInterpolator()
                    addUpdateListener { anim ->
                        val fraction = anim.animatedValue as Float
                        if (glowFab != null) {
                            glowFab.setSosTransitionProgress(fraction)
                        } else if (fab is FloatingActionButton) {
                            val blended = ColorUtils.blendARGB(normalBg, fullRed, fraction)
                            fab.backgroundTintList = ColorStateList.valueOf(blended)
                        }
                    }
                    start()
                }
                Haptics.trigger(activity, fab, AppSettings.HAPTIC_LIGHT)
            }

            // Runnable that triggers SOS at 4 seconds
            val sosRunnable = Runnable {
                sosTriggered = true
                if (glowFab != null) {
                    glowFab.setSosTransitionProgress(1f)
                } else if (fab is FloatingActionButton) {
                    fab.backgroundTintList = ColorStateList.valueOf(fullRed)
                }
                SosHelper.trigger(activity)
            }

            fun resetSosState(animateBack: Boolean) {
                fab.removeCallbacks(startFadeRunnable)
                fab.removeCallbacks(sosRunnable)
                redFadeAnimator?.cancel()
                val currentProgress = glowFab?.sosTransitionProgress ?: 0f
                if (animateBack && currentProgress > 0f) {
                    redFadeAnimator = ValueAnimator.ofFloat(currentProgress, 0f).apply {
                        duration = 200L
                        addUpdateListener { anim ->
                            val fraction = anim.animatedValue as Float
                            if (glowFab != null) {
                                glowFab.setSosTransitionProgress(fraction)
                            } else if (fab is FloatingActionButton) {
                                val blended = ColorUtils.blendARGB(normalBg, fullRed, fraction)
                                fab.backgroundTintList = ColorStateList.valueOf(blended)
                            }
                        }
                        start()
                    }
                } else {
                    if (glowFab != null) {
                        glowFab.setSosTransitionProgress(0f)
                    } else if (fab is FloatingActionButton) {
                        fab.backgroundTintList = ColorStateList.valueOf(normalBg)
                    }
                }
            }

            // Initial reset to ensure clean state
            resetSosState(animateBack = false)

            fab.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        sosTriggered = false
                        resetSosState(animateBack = false)
                        v.postDelayed(startFadeRunnable, 2000L)
                        v.postDelayed(sosRunnable, 4000L)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val slop = ViewConfiguration.get(v.context).scaledTouchSlop * 2
                        if (event.x < -slop || event.x > v.width + slop ||
                            event.y < -slop || event.y > v.height + slop) {
                            v.isPressed = false
                            resetSosState(animateBack = true)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        val triggered = sosTriggered
                        val wasFading = (glowFab?.sosTransitionProgress ?: 0f) > 0f
                        resetSosState(animateBack = false)
                        if (!triggered && !wasFading) {
                            Haptics.trigger(v)
                            openTranslator(activity)
                            v.performClick()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        sosTriggered = false
                        resetSosState(animateBack = true)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    fun open(activity: Activity, tab: Tab) {
        val target = activityFor(tab)
        if (activity.javaClass == target) return
        activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        @Suppress("DEPRECATION")
        // Tabs crossfade instead of opening like a new screen (app-motion spec §4.1).
        activity.overridePendingTransition(R.anim.tab_fade_in, R.anim.tab_fade_out)
    }

    fun openWordBankSearch(activity: Activity) {
        activity.startActivity(
            Intent(activity, WordBankActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(WordBankActivity.EXTRA_FOCUS_SEARCH, true)
        )
        @Suppress("DEPRECATION")
        // Tabs crossfade instead of opening like a new screen (app-motion spec §4.1).
        activity.overridePendingTransition(R.anim.tab_fade_in, R.anim.tab_fade_out)
    }

    fun openTranslator(activity: Activity) {
        activity.startActivity(Intent(activity, MainActivity::class.java))
    }
}

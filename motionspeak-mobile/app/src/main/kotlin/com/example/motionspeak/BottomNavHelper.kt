package com.example.motionspeak

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
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

            // ── State ──────────────────────────────────────────────────────────
            var sosTriggered = false
            var redFadeAnimator: ValueAnimator? = null
            var pressStartMs = 0L

            // Guard: true only while the finger is physically down on the FAB.
            // Every delayed runnable checks this before doing any work so that
            // a runnable that fires *after* the finger is lifted is a no-op.
            var isHolding = false

            // Pulse state
            var pulseRunnable: Runnable? = null
            var pulseScheduled = false

            // Red overlay state
            var overlayView: View? = null
            var overlayAnimator: ValueAnimator? = null

            // ── Overlay helpers ────────────────────────────────────────────────

            fun getDecorViewGroup(): ViewGroup =
                activity.window.decorView as ViewGroup

            fun showRedOverlay() {
                if (overlayView != null) return
                val overlay = View(activity).apply {
                    setBackgroundColor(Color.RED)
                    alpha = 0f
                    // Block touches so taps on the overlay don't fall through to
                    // the button (SOS is already triggered; overlay is purely visual)
                    isClickable = false
                    isFocusable = false
                }
                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                getDecorViewGroup().addView(overlay, params)
                overlayView = overlay
            }

            fun removeOverlay() {
                val ov = overlayView ?: return
                overlayAnimator?.cancel()
                overlayAnimator = null
                ValueAnimator.ofFloat(ov.alpha, 0f).apply {
                    duration = 300L
                    interpolator = LinearInterpolator()
                    addUpdateListener { ov.alpha = it.animatedValue as Float }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) {
                            (ov.parent as? ViewGroup)?.removeView(ov)
                        }
                    })
                    start()
                }
                overlayView = null
            }

            // ── Pulse helpers ──────────────────────────────────────────────────

            /**
             * Emit one expanding red ring pulse centred on the FAB.
             * The ring is added to the DecorView so it appears above the FAB.
             */
            fun emitPulseRing(intensity: Float) {
                val decor = getDecorViewGroup()
                val fabLoc = IntArray(2)
                fab.getLocationOnScreen(fabLoc)
                val fabCx = fabLoc[0] + fab.width / 2
                val fabCy = fabLoc[1] + fab.height / 2

                val baseSizePx = fab.width.coerceAtLeast(fab.height)
                // Maximum ring size grows with intensity (1.5x → 3.5x FAB diameter)
                val maxScale = 1.5f + intensity * 2.0f

                val ring = View(activity).apply {
                    background = ContextCompat.getDrawable(
                        activity, R.drawable.bg_sos_pulse_ring
                    )
                    alpha = 0.85f + intensity * 0.15f // starts more opaque at high intensity
                }
                val startSize = baseSizePx
                val params = FrameLayout.LayoutParams(startSize, startSize)
                // Position so the ring centre aligns with the FAB centre
                val decorLoc = IntArray(2)
                decor.getLocationOnScreen(decorLoc)
                params.leftMargin = fabCx - decorLoc[0] - startSize / 2
                params.topMargin = fabCy - decorLoc[1] - startSize / 2
                decor.addView(ring, params)

                val animator = ValueAnimator.ofFloat(1f, maxScale).apply {
                    duration = (500L - (intensity * 250L).toLong()).coerceAtLeast(150L)
                    interpolator = AccelerateInterpolator(1.2f)
                    addUpdateListener { anim ->
                        val scale = anim.animatedValue as Float
                        val newSize = (startSize * scale).toInt()
                        val lp = ring.layoutParams as FrameLayout.LayoutParams
                        lp.width = newSize
                        lp.height = newSize
                        lp.leftMargin = fabCx - decorLoc[0] - newSize / 2
                        lp.topMargin = fabCy - decorLoc[1] - newSize / 2
                        ring.layoutParams = lp
                        ring.alpha = (1f - anim.animatedFraction) *
                            (0.85f + intensity * 0.15f)
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) {
                            (ring.parent as? ViewGroup)?.removeView(ring)
                        }
                    })
                    start()
                }
            }

            // ── Scheduled runnables ────────────────────────────────────────────

            // Button color fades from brand → scarlet over 3 s (t=2 s → t=5 s)
            val startFadeRunnable = Runnable {
                if (!isHolding) return@Runnable
                redFadeAnimator?.cancel()
                redFadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 3000L // 3-second fade from t=2s to t=5s
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

            // Pulsating rings start at t=2 s; a recursive runnable re-posts itself
            // at a shrinking interval (600 ms → 150 ms) as intensity grows.
            val startPulseRunnable = Runnable {
                if (!isHolding) return@Runnable
                pulseScheduled = true

                fun schedulePulse(handler: View) {
                    val elapsed = System.currentTimeMillis() - pressStartMs
                    // intensity 0 at t=2 s, 1 at t=5 s
                    val intensity = ((elapsed - 2000L) / 3000f).coerceIn(0f, 1f)
                    // interval shrinks from 600 ms to 150 ms as intensity grows
                    val interval = (600L - (intensity * 450L).toLong()).coerceAtLeast(150L)

                    // emit visual ring
                    emitPulseRing(intensity)

                    // haptic: light until near trigger, then max
                    val hapticMode = if (intensity > 0.7f) AppSettings.HAPTIC_MAX
                    else AppSettings.HAPTIC_LIGHT
                    Haptics.trigger(activity, fab, hapticMode)

                    if (pulseScheduled) {
                        val next: Runnable = object : Runnable {
                            override fun run() {
                                if (!pulseScheduled) return
                                val el2 = System.currentTimeMillis() - pressStartMs
                                val int2 = ((el2 - 2000L) / 3000f).coerceIn(0f, 1f)
                                val iv2 = (600L - (int2 * 450L).toLong()).coerceAtLeast(150L)

                                emitPulseRing(int2)
                                val hm = if (int2 > 0.7f) AppSettings.HAPTIC_MAX
                                else AppSettings.HAPTIC_LIGHT
                                Haptics.trigger(activity, fab, hm)

                                if (pulseScheduled) handler.postDelayed(this, iv2)
                            }
                        }
                        handler.postDelayed(next, interval)
                        pulseRunnable = next
                    }
                }

                schedulePulse(fab)
            }

            // Red screen overlay fades in from t=3 s to t=5 s (max alpha ≈ 0.55)
            val startOverlayRunnable = Runnable {
                if (!isHolding) return@Runnable
                showRedOverlay()
                overlayAnimator?.cancel()
                overlayAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 2000L // spans t=3s → t=5s
                    interpolator = LinearInterpolator()
                    addUpdateListener { anim ->
                        val f = anim.animatedValue as Float
                        overlayView?.alpha = f * 0.55f // cap at ~55% opacity
                    }
                    start()
                }
            }

            // SOS triggers at 5 seconds
            val sosRunnable = Runnable {
                if (!isHolding) return@Runnable
                sosTriggered = true
                if (glowFab != null) {
                    glowFab.setSosTransitionProgress(1f)
                } else if (fab is FloatingActionButton) {
                    fab.backgroundTintList = ColorStateList.valueOf(fullRed)
                }
                SosHelper.trigger(activity)
            }

            // ── Reset helper ───────────────────────────────────────────────────

            fun resetSosState(animateBack: Boolean) {
                // Cancel all scheduled runnables
                // Stop holding — must be cleared BEFORE removeCallbacks so that any
                // runnable that fires on the same looper tick sees isHolding=false.
                isHolding = false

                fab.removeCallbacks(startFadeRunnable)
                fab.removeCallbacks(startPulseRunnable)
                fab.removeCallbacks(startOverlayRunnable)
                fab.removeCallbacks(sosRunnable)

                // Stop pulse loop
                pulseScheduled = false
                pulseRunnable?.let { fab.removeCallbacks(it) }
                pulseRunnable = null

                // Cancel / remove overlay
                overlayAnimator?.cancel()
                overlayAnimator = null
                removeOverlay()

                // Revert button colour
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

            // ── Touch handler ──────────────────────────────────────────────────

            fab.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        sosTriggered = false
                        pressStartMs = System.currentTimeMillis()
                        resetSosState(animateBack = false)
                        isHolding = true                            // arm the guard
                        v.postDelayed(startFadeRunnable, 2000L)   // button fade starts at 2 s
                        v.postDelayed(startPulseRunnable, 2000L)  // pulse rings start at 2 s
                        v.postDelayed(startOverlayRunnable, 3000L) // red overlay starts at 3 s
                        v.postDelayed(sosRunnable, 5000L)          // SOS triggers at 5 s
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

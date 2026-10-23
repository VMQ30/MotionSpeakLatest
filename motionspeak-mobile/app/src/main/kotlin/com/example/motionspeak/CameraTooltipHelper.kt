package com.example.motionspeak

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * Manages the "click here to open the camera" discovery tooltip for the nav bar's
 * center hand sign button (fabTranslate).
 *
 * Consistently appears after every 15 seconds of user inactivity (no touch interaction)
 * on any screen with the bottom navigation bar. Dismisses on any touch and restarts
 * the 15-second idle timer.
 */
object CameraTooltipHelper {

    private const val IDLE_DELAY_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private var pendingActivity: WeakReference<Activity>? = null
    private var currentTooltipView: WeakReference<CameraTooltipView>? = null
    var isTooltipShowing = false
        private set

    private val idleRunnable = Runnable {
        val activity = pendingActivity?.get() ?: return@Runnable
        if (activity.isFinishing || activity.isDestroyed) return@Runnable

        // Do not display if already on camera translator screen
        if (activity is MainActivity) return@Runnable

        val fab = activity.findViewById<View>(R.id.fabTranslate) ?: return@Runnable
        if (fab.visibility != View.VISIBLE) return@Runnable

        showTooltip(activity, fab)
    }

    /**
     * Determines whether the tooltip is eligible to show.
     * Always eligible for screens with the translation FAB after 15 seconds of inactivity.
     */
    fun isEligible(settings: AppSettings? = null): Boolean = true

    /**
     * Called when an activity with the bottom navigation bar is resumed.
     * Arms the 15-second idle timer.
     */
    fun onActivityResumed(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity is MainActivity) {
            dismissTooltip(activity, animated = false)
            return
        }
        pendingActivity = WeakReference(activity)
        scheduleIdle()
    }

    /**
     * Called when the active screen is paused or backgrounded.
     * Cancels any pending idle timer and removes the tooltip view.
     */
    fun onActivityPaused(activity: Activity) {
        if (pendingActivity?.get() == activity) {
            handler.removeCallbacks(idleRunnable)
            pendingActivity = null
        }
        dismissTooltip(activity, animated = false)
    }

    /**
     * Called on touch events on the screen.
     * If the tooltip is currently displayed, dismisses it.
     * Resets the 15-second idle countdown on any user interaction.
     */
    fun onUserInteraction(activity: Activity, event: MotionEvent?) {
        if (event == null) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (isTooltipShowing) {
                    dismissTooltip(activity, animated = true)
                }
                pendingActivity = WeakReference(activity)
                scheduleIdle()
            }
            MotionEvent.ACTION_MOVE -> {
                // Keep resetting while user is actively dragging or scrolling
                scheduleIdle()
            }
        }
    }

    /**
     * Called whenever the user opens the camera (via FAB, home hero button, etc.).
     */
    fun onCameraOpened(activity: Activity) {
        handler.removeCallbacks(idleRunnable)
        pendingActivity = null
        dismissTooltip(activity, animated = true)
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, IDLE_DELAY_MS)
    }

    /**
     * Displays the tailed tooltip directly above the given anchor view (fabTranslate).
     * Anchors to android.R.id.content as the root container to ensure rock-solid,
     * status-bar-independent coordinate positioning.
     */
    fun showTooltip(activity: Activity, anchorView: View) {
        if (isTooltipShowing) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity is MainActivity) return

        if (anchorView.width <= 0 || anchorView.height <= 0 || !anchorView.isAttachedToWindow) {
            anchorView.post {
                val act = pendingActivity?.get() ?: activity
                if (!act.isFinishing && !act.isDestroyed) {
                    showTooltip(act, anchorView)
                }
            }
            return
        }

        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // Clean up any lingering view instance
        currentTooltipView?.get()?.let { old ->
            (old.parent as? ViewGroup)?.removeView(old)
        }

        val tooltip = CameraTooltipView(activity).apply {
            setOnClickListener {
                onCameraOpened(activity)
                BottomNavHelper.openTranslator(activity)
            }
        }

        val density = activity.resources.displayMetrics.density
        val screenWidth = activity.resources.displayMetrics.widthPixels

        val maxWidth = (screenWidth - 32 * density).toInt()
        tooltip.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val tooltipWidth = tooltip.measuredWidth
        val tooltipHeight = tooltip.measuredHeight

        val anchorLoc = IntArray(2)
        anchorView.getLocationInWindow(anchorLoc)
        val anchorCenterX = anchorLoc[0] + anchorView.width / 2
        val anchorTop = anchorLoc[1]

        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)

        val relCenterX = anchorCenterX - rootLoc[0]
        val relAnchorTop = anchorTop - rootLoc[1]

        val rootWidth = if (root.width > 0) root.width else screenWidth
        val marginPx = (8 * density).toInt()
        val rawLeft = relCenterX - tooltipWidth / 2
        val left = rawLeft.coerceIn(
            (12 * density).toInt(),
            rootWidth - tooltipWidth - (12 * density).toInt()
        )
        val top = relAnchorTop - tooltipHeight - marginPx

        // Tail aligns directly with the center of the circular hand button
        tooltip.tailCenterX = (relCenterX - left).toFloat()

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left.coerceAtLeast(0)
            topMargin = top.coerceAtLeast(0)
        }

        root.addView(tooltip, lp)
        tooltip.bringToFront()
        currentTooltipView = WeakReference(tooltip)
        isTooltipShowing = true

        tooltip.showWithAnimation()
    }

    /**
     * Dismisses and removes the tooltip view from the screen.
     */
    fun dismissTooltip(@Suppress("UNUSED_PARAMETER") activity: Activity? = null, animated: Boolean = true) {
        val tooltip = currentTooltipView?.get()
        currentTooltipView = null
        isTooltipShowing = false
        if (tooltip != null) {
            if (animated) {
                tooltip.dismissWithAnimation()
            } else {
                (tooltip.parent as? ViewGroup)?.removeView(tooltip)
            }
        }
    }
}

/**
 * Eligibility rule function.
 */
internal fun isCameraTooltipEligible(
    hasSeenCameraTooltip: Boolean = false,
    isReplayTutorialActive: Boolean = false
): Boolean = true

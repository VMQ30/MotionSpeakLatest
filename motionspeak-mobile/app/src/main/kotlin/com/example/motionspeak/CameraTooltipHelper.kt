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
 * Manages the first-time onboarding discovery tooltip for the nav bar's hand sign button.
 *
 * Requirements:
 *   - Only appears after the onboarding tutorial for the first time user.
 *   - Only appears when the phone has been idle (no touch interaction) for 15 seconds.
 *   - Never appears when replaying onboarding (via Settings -> Replay tutorial).
 *   - Dismisses immediately upon any user touch or interaction, or when the camera is opened.
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

        val settings = AppSettings.getInstance(activity)
        if (!isEligible(settings)) return@Runnable

        val fab = activity.findViewById<View>(R.id.fabTranslate) ?: return@Runnable
        showTooltip(activity, fab)
    }

    /**
     * Determines whether the tooltip is eligible to show.
     * True if:
     *   1. The user has not seen the camera tooltip yet (!hasSeenCameraTooltip).
     *   2. The user is NOT currently replaying onboarding (!isReplayTutorialActive).
     */
    fun isEligible(settings: AppSettings): Boolean = isCameraTooltipEligible(
        hasSeenCameraTooltip = settings.hasSeenCameraTooltip,
        isReplayTutorialActive = settings.isReplayTutorialActive
    )

    /**
     * Called when an activity with the bottom navigation bar is resumed.
     * Arms the 15-second idle timer if the user is eligible for the tooltip.
     */
    fun onActivityResumed(activity: Activity) {
        val settings = AppSettings.getInstance(activity)
        if (!isEligible(settings)) {
            return
        }
        if (activity.findViewById<View>(R.id.fabTranslate) == null) return
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
     * Called on every touch event on the screen.
     * If the tooltip is currently displayed, dismisses it and marks it completed.
     * If the tooltip is pending, resets the 15-second idle countdown.
     */
    fun onUserInteraction(activity: Activity, event: MotionEvent?) {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            if (isTooltipShowing) {
                dismissTooltip(activity, animated = true)
                markCompleted(activity)
            } else {
                val settings = AppSettings.getInstance(activity)
                if (isEligible(settings)) {
                    scheduleIdle()
                }
            }
        }
    }

    /**
     * Called whenever the user opens the camera (via FAB, home hero button, etc.).
     * Cancels the tooltip permanently so the user is never prompted again.
     */
    fun onCameraOpened(activity: Activity) {
        handler.removeCallbacks(idleRunnable)
        pendingActivity = null
        dismissTooltip(activity, animated = true)
        markCompleted(activity)
    }

    private fun markCompleted(activity: Activity) {
        val settings = AppSettings.getInstance(activity)
        settings.hasSeenCameraTooltip = true
        settings.isCameraTooltipEligible = false
        settings.hasCompletedInitialOnboarding = true
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

        if (anchorView.width <= 0 || anchorView.height <= 0 || !anchorView.isAttachedToWindow) {
            anchorView.post { showTooltip(activity, anchorView) }
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
        markCompleted(activity)
    }

    /**
     * Dismisses and removes the tooltip view from the screen.
     */
    fun dismissTooltip(@Suppress("UNUSED_PARAMETER") activity: Activity? = null, animated: Boolean = true) {
        val tooltip = currentTooltipView?.get()
        if (tooltip != null) {
            currentTooltipView = null
            isTooltipShowing = false
            if (animated) {
                tooltip.dismissWithAnimation()
            } else {
                (tooltip.parent as? ViewGroup)?.removeView(tooltip)
            }
        }
    }
}

/**
 * Pure eligibility rule function for direct unit testing.
 */
internal fun isCameraTooltipEligible(
    hasSeenCameraTooltip: Boolean,
    isReplayTutorialActive: Boolean
): Boolean {
    if (hasSeenCameraTooltip) return false
    if (isReplayTutorialActive) return false
    return true
}

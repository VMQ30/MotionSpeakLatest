package com.example.motionspeak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTooltipEligibilityTest {

    @Test
    fun eligibleForFirstTimeUserWhoHasNotSeenTooltip() {
        val eligible = isCameraTooltipEligible(
            hasSeenCameraTooltip = false,
            isReplayTutorialActive = false
        )
        assertTrue(eligible)
    }

    @Test
    fun notEligibleDuringOrAfterReplayTutorial() {
        val eligible = isCameraTooltipEligible(
            hasSeenCameraTooltip = false,
            isReplayTutorialActive = true
        )
        assertFalse("Replay tutorial must NEVER trigger the camera tooltip", eligible)
    }

    @Test
    fun notEligibleOnceTooltipHasBeenSeen() {
        val eligible = isCameraTooltipEligible(
            hasSeenCameraTooltip = true,
            isReplayTutorialActive = false
        )
        assertFalse("Once seen, camera tooltip must not show again", eligible)
    }
}

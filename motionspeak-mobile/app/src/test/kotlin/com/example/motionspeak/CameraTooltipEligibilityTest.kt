package com.example.motionspeak

import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTooltipEligibilityTest {

    @Test
    fun eligibleAfterInactivity() {
        val eligible = isCameraTooltipEligible(
            hasSeenCameraTooltip = false,
            isReplayTutorialActive = false
        )
        assertTrue(eligible)
    }

    @Test
    fun eligibleConsistentlyAfterInactivityEvenIfSeen() {
        val eligible = isCameraTooltipEligible(
            hasSeenCameraTooltip = true,
            isReplayTutorialActive = false
        )
        assertTrue(eligible)
    }
}

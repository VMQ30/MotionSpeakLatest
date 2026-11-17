package com.example.motionspeak

import org.junit.Assert.assertEquals
import org.junit.Test

class Mp4UploadModeTest {

    @Test
    fun testAspectScaleWhenVideoWiderThanView() {
        // View: 1080 x 1920 (aspect 9/16 = 0.5625)
        // Video: 1920 x 1080 (landscape, aspect 16/9 = 1.7778)
        val (scaleX, scaleY) = calculateFillCenterScale(
            viewWidth = 1080f,
            viewHeight = 1920f,
            videoWidth = 1920f,
            videoHeight = 1080f
        )
        // Video is wider than view -> scale height to fill (scaleY = 1.0), scaleX expands width
        val expectedScaleX = (1920f / 1080f) / (1080f / 1920f)
        assertEquals(expectedScaleX, scaleX, 0.001f)
        assertEquals(1.0f, scaleY, 0.001f)
    }

    @Test
    fun testAspectScaleWhenVideoTallerThanView() {
        // View: 1080 x 1080 (aspect 1.0)
        // Video: 1080 x 1920 (portrait, aspect 0.5625)
        val (scaleX, scaleY) = calculateFillCenterScale(
            viewWidth = 1080f,
            viewHeight = 1080f,
            videoWidth = 1080f,
            videoHeight = 1920f
        )
        // Video is taller than view -> scale width to fill (scaleX = 1.0), scaleY expands height
        assertEquals(1.0f, scaleX, 0.001f)
        val expectedScaleY = 1.0f / (1080f / 1920f)
        assertEquals(expectedScaleY, scaleY, 0.001f)
    }

    @Test
    fun testAspectScaleWhenIdenticalAspectRatio() {
        // View and video both 720 x 1280
        val (scaleX, scaleY) = calculateFillCenterScale(
            viewWidth = 720f,
            viewHeight = 1280f,
            videoWidth = 1080f,
            videoHeight = 1920f
        )
        assertEquals(1.0f, scaleX, 0.001f)
        assertEquals(1.0f, scaleY, 0.001f)
    }

    @Test
    fun testAspectScaleWithInvalidDimensionsFallback() {
        val (scaleX, scaleY) = calculateFillCenterScale(0f, 0f, 0f, 0f)
        assertEquals(1.0f, scaleX, 0.001f)
        assertEquals(1.0f, scaleY, 0.001f)
    }

    @Test
    fun testExtractVideoDisplayName() {
        assertEquals("sign_test.mp4", extractVideoDisplayName("content://com.android.providers.media/sign_test.mp4"))
        assertEquals("sample.mp4", extractVideoDisplayName("/storage/emulated/0/DCIM/sample.mp4"))
        assertEquals("my_video.mp4", extractVideoDisplayName("https://example.com/videos/my_video.mp4?token=123#t=10"))
        assertEquals("Uploaded MP4", extractVideoDisplayName(null))
        assertEquals("Uploaded MP4", extractVideoDisplayName(""))
    }
}

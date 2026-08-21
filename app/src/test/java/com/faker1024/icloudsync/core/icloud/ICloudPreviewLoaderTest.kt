package com.faker1024.icloudsync.core.icloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ICloudPreviewLoaderTest {
    @Test
    fun `recognizes common iCloud photo formats case insensitively`() {
        assertTrue(isPreviewableImage("IMG_0001.HEIC"))
        assertTrue(isPreviewableImage("scan.dng"))
        assertTrue(isPreviewableImage("graphic.webp"))
    }

    @Test
    fun `does not try to decode arbitrary documents as images`() {
        assertFalse(isPreviewableImage("archive.zip"))
        assertFalse(isPreviewableImage("photo.jpg.exe"))
        assertFalse(isPreviewableImage("README"))
    }
}

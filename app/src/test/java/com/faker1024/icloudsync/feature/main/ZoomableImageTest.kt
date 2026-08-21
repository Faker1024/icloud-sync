package com.faker1024.icloudsync.feature.main

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomableImageTest {
    @Test
    fun initialImagePageSelectsMatchingImage() {
        assertEquals(1, initialImagePage(listOf("first", "selected", "last"), "selected"))
    }

    @Test
    fun initialImagePageFallsBackToFirstImage() {
        assertEquals(0, initialImagePage(listOf("first", "last"), "missing"))
    }
}

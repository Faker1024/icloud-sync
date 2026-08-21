package com.faker1024.icloudsync.feature.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomableImageTest {
    @Test
    fun `offset resets at original scale`() {
        assertEquals(Offset.Zero, clampImageOffset(Offset(50f, -20f), 1f, IntSize(200, 100)))
    }

    @Test
    fun `offset stays inside scaled viewport bounds`() {
        assertEquals(
            Offset(200f, -100f),
            clampImageOffset(Offset(999f, -999f), 3f, IntSize(200, 100)),
        )
    }
}

package com.faker1024.icloudsync.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudBrowserSettingsTest {
    @Test
    fun `icon size is constrained to supported range`() {
        assertEquals(MIN_ICON_SIZE, clampIconSize(12f))
        assertEquals(96f, clampIconSize(96f))
        assertEquals(MAX_ICON_SIZE, clampIconSize(500f))
    }
}

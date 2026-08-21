package com.faker1024.icloudsync.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalBrowserSettingsTest {
    @Test
    fun `valid persisted values are restored`() {
        assertEquals(
            LocalBrowserPreferences(
                layout = LocalBrowserLayout.LIST,
                sortField = LocalSortField.SIZE,
                sortDirection = LocalSortDirection.DESCENDING,
            ),
            parseLocalBrowserPreferences("LIST", "SIZE", "DESCENDING"),
        )
    }

    @Test
    fun `unknown persisted values fall back safely`() {
        assertEquals(LocalBrowserPreferences(), parseLocalBrowserPreferences("BAD", null, "SIDEWAYS"))
    }
}

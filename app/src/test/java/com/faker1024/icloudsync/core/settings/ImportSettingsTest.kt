package com.faker1024.icloudsync.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSettingsTest {
    @Test
    fun sanitizeAlbumName_removesUnsafeCharactersAndWhitespace() {
        assertEquals("家庭 相册 2026", ImportSettings.sanitizeAlbumName("  家庭/相册:*? 2026  "))
    }

    @Test
    fun sanitizeAlbumName_usesDefaultWhenEmpty() {
        assertEquals(ImportSettings.DEFAULT_ALBUM_NAME, ImportSettings.sanitizeAlbumName(" /:*? "))
    }
}

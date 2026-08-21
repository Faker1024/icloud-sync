package com.faker1024.icloudsync.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ICloudDownloadStoreTest {
    @Test
    fun `download path preserves safe nested directory hierarchy`() {
        assertEquals(
            "Download/iCloud Drive/图库/2026_夏天/",
            buildDownloadRelativePath(listOf("图库", "2026/夏天")),
        )
    }

    @Test
    fun `directory name rejects traversal and reserved characters`() {
        assertEquals("_.._家庭_照片", sanitizeCloudDirectoryName("../../家庭:照片"))
    }
}

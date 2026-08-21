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

    @Test
    fun `cloud modified time parses ISO instant and timezone offset`() {
        assertEquals(1_710_930_000L, parseCloudModifiedAtSeconds("2024-03-20T10:20:00Z"))
        assertEquals(1_710_930_000L, parseCloudModifiedAtSeconds("2024-03-20T18:20:00+08:00"))
    }

    @Test
    fun `cloud modified time accepts epoch seconds and milliseconds`() {
        assertEquals(1_710_930_000L, parseCloudModifiedAtSeconds("1710930000"))
        assertEquals(1_710_930_000L, parseCloudModifiedAtSeconds("1710930000123"))
    }

    @Test
    fun `cloud modified time rejects missing invalid and negative values`() {
        assertEquals(null, parseCloudModifiedAtSeconds(null))
        assertEquals(null, parseCloudModifiedAtSeconds("not-a-time"))
        assertEquals(null, parseCloudModifiedAtSeconds("-1"))
    }

    @Test
    fun `filesystem timestamp comparison tolerates coarse two second precision`() {
        assertEquals(true, modifiedTimeMatches(100L, 102L))
        assertEquals(false, modifiedTimeMatches(100L, 103L))
    }
}

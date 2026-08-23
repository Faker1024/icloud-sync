package com.faker1024.icloudsync.core.sync

import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
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

    @Test
    fun `remote fingerprint rejects same size file with a newer cloud modification`() {
        val item = cloudFile(modifiedAt = "2024-03-20T10:20:05Z")
        assertEquals(
            false,
            remoteFileFingerprintMatches(
                storedRemoteId = item.id,
                storedSize = item.size,
                storedModifiedAtMillis = 1_710_930_000_000L,
                item = item,
                actualSize = item.size,
            ),
        )
    }

    @Test
    fun `remote fingerprint reuses exact file when id size and modification match`() {
        val item = cloudFile(modifiedAt = "2024-03-20T10:20:00Z")
        assertEquals(
            true,
            remoteFileFingerprintMatches(
                storedRemoteId = item.id,
                storedSize = item.size,
                storedModifiedAtMillis = 1_710_930_000_000L,
                item = item,
                actualSize = item.size,
            ),
        )
    }

    @Test
    fun `legacy migration fingerprint can be claimed only when size and time match`() {
        val item = cloudFile(modifiedAt = "2024-03-20T10:20:00Z")
        assertEquals(
            true,
            remoteFileFingerprintMatches(
                storedRemoteId = "legacy:content://downloads/42",
                storedSize = item.size,
                storedModifiedAtMillis = 1_710_930_000_000L,
                item = item,
                actualSize = item.size,
            ),
        )
    }

    private fun cloudFile(modifiedAt: String?) = ICloudDriveItem(
        id = "FILE::com.apple.CloudDocs::document-1",
        name = "example.bin",
        type = "FILE",
        size = 4_096L,
        modifiedAt = modifiedAt,
        childCount = 0L,
    )
}

package com.faker1024.icloudsync.core.icloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ICloudDownloadProtocolTest {
    @Test
    fun `content range parses resumable response`() {
        assertEquals(
            DownloadContentRange(start = 1_024L, endInclusive = 2_047L, totalLength = 4_096L),
            parseDownloadContentRange("bytes 1024-2047/4096"),
        )
    }

    @Test
    fun `content range accepts unknown total but rejects invalid bounds`() {
        assertEquals(
            DownloadContentRange(start = 10L, endInclusive = 19L, totalLength = null),
            parseDownloadContentRange("bytes 10-19/*"),
        )
        assertNull(parseDownloadContentRange("bytes 20-10/100"))
        assertNull(parseDownloadContentRange("bytes 0-100/100"))
        assertNull(parseDownloadContentRange("invalid"))
    }

    @Test
    fun `work item serialization preserves download fingerprint`() {
        val item = ICloudDriveItem(
            id = "FILE::com.apple.CloudDocs::文档-1",
            name = "照片 01.jpg",
            type = "FILE",
            size = 12_345L,
            modifiedAt = "2026-08-24T12:30:00Z",
            childCount = 0L,
        )
        assertEquals(item, iCloudDriveItemFromWorkPayload(item.toWorkPayload()))
    }

    @Test
    fun `work item parser rejects folders and malformed data`() {
        val folder = ICloudDriveItem("folder", "图库", "FOLDER", 0L, null, 1L)
        assertNull(iCloudDriveItemFromWorkPayload(folder.toWorkPayload()))
        assertNull(iCloudDriveItemFromWorkPayload("not-a-payload"))
    }
}

package com.faker1024.icloudsync.core.icloud

import com.faker1024.icloudsync.core.settings.CloudSortDirection
import com.faker1024.icloudsync.core.settings.CloudSortField
import org.junit.Assert.assertEquals
import org.junit.Test

class ICloudItemSortingTest {
    @Test
    fun `folders stay before files while names follow selected direction`() {
        val sorted = sortCloudDriveItems(
            listOf(file("a.txt"), folder("资料"), file("b.txt")),
            CloudSortField.NAME,
            CloudSortDirection.DESCENDING,
        )

        assertEquals(listOf("资料", "b.txt", "a.txt"), sorted.map(ICloudDriveItem::name))
    }

    @Test
    fun `size descending puts larger files first`() {
        val sorted = sortCloudDriveItems(
            listOf(file("small.zip", size = 2), file("large.zip", size = 20)),
            CloudSortField.SIZE,
            CloudSortDirection.DESCENDING,
        )

        assertEquals(listOf("large.zip", "small.zip"), sorted.map(ICloudDriveItem::name))
    }

    @Test
    fun `items without modified date remain last in either direction`() {
        val items = listOf(
            file("unknown.jpg", modifiedAt = null),
            file("new.jpg", modifiedAt = "2026-08-20T10:00:00Z"),
            file("old.jpg", modifiedAt = "2024-01-01T10:00:00Z"),
        )

        val ascending = sortCloudDriveItems(items, CloudSortField.MODIFIED_TIME, CloudSortDirection.ASCENDING)
        val descending = sortCloudDriveItems(items, CloudSortField.MODIFIED_TIME, CloudSortDirection.DESCENDING)

        assertEquals(listOf("old.jpg", "new.jpg", "unknown.jpg"), ascending.map(ICloudDriveItem::name))
        assertEquals(listOf("new.jpg", "old.jpg", "unknown.jpg"), descending.map(ICloudDriveItem::name))
    }

    @Test
    fun `file type groups extensions and uses name as stable fallback`() {
        val sorted = sortCloudDriveItems(
            listOf(file("z.png"), file("b.jpg"), file("a.jpg")),
            CloudSortField.FILE_TYPE,
            CloudSortDirection.ASCENDING,
        )

        assertEquals(listOf("a.jpg", "b.jpg", "z.png"), sorted.map(ICloudDriveItem::name))
    }

    private fun file(name: String, size: Long = 1, modifiedAt: String? = null) = ICloudDriveItem(
        id = "file-$name",
        name = name,
        type = "FILE",
        size = size,
        modifiedAt = modifiedAt,
        childCount = 0,
    )

    private fun folder(name: String) = ICloudDriveItem(
        id = "folder-$name",
        name = name,
        type = "FOLDER",
        size = 0,
        modifiedAt = null,
        childCount = 1,
    )
}

package com.faker1024.icloudsync.core.local

import com.faker1024.icloudsync.core.settings.LocalSortDirection
import com.faker1024.icloudsync.core.settings.LocalSortField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedFileModelsTest {
    @Test
    fun `media store relative paths keep cloud folder hierarchy`() {
        assertEquals(emptyList<String>(), parseSyncedDirectories("Download/iCloud Drive/"))
        assertEquals(
            listOf("图库", "2026"),
            parseSyncedDirectories("Download/iCloud Drive/图库/2026/"),
        )
        assertNull(parseSyncedDirectories("Download/Other/"))
        assertNull(parseSyncedDirectories("Download/iCloud Drive/../Other/"))
    }

    @Test
    fun `browser shows immediate folders and direct files`() {
        val files = listOf(
            file("root.txt", emptyList(), modifiedAt = 10),
            file("one.jpg", listOf("图库"), modifiedAt = 20),
            file("two.jpg", listOf("图库", "2026"), modifiedAt = 30),
            file("note.pdf", listOf("文稿"), modifiedAt = 40),
        )

        val root = buildSyncedBrowserEntries(files, emptyList())
        assertEquals(listOf("图库", "文稿"), root.filterIsInstance<SyncedBrowserEntry.Folder>().map { it.name })
        assertEquals(listOf(2, 1), root.filterIsInstance<SyncedBrowserEntry.Folder>().map { it.descendantFileCount })
        assertEquals(listOf("root.txt"), root.filterIsInstance<SyncedBrowserEntry.File>().map { it.item.displayName })

        val gallery = buildSyncedBrowserEntries(files, listOf("图库"))
        assertEquals("2026", (gallery.first() as SyncedBrowserEntry.Folder).name)
        assertEquals("one.jpg", (gallery.last() as SyncedBrowserEntry.File).item.displayName)
    }

    @Test
    fun `common image extensions remain previewable without mime type`() {
        assertTrue(file("photo.HEIC", emptyList(), mimeType = null).isImage)
    }

    @Test
    fun `files support size descending while folders remain first`() {
        val entries = buildSyncedBrowserEntries(
            files = listOf(
                file("small.zip", emptyList(), size = 2),
                file("large.zip", emptyList(), size = 20),
                file("inside.txt", listOf("小目录"), size = 5),
                file("one.bin", listOf("大目录"), size = 8),
                file("two.bin", listOf("大目录"), size = 9),
            ),
            currentPath = emptyList(),
            sortField = LocalSortField.SIZE,
            sortDirection = LocalSortDirection.DESCENDING,
        )

        assertEquals(
            listOf("大目录", "小目录"),
            entries.filterIsInstance<SyncedBrowserEntry.Folder>().map { it.name },
        )
        assertEquals(
            listOf("large.zip", "small.zip"),
            entries.filterIsInstance<SyncedBrowserEntry.File>().map { it.item.displayName },
        )
    }

    @Test
    fun `modified entries with unknown dates stay last in either direction`() {
        val files = listOf(
            file("unknown.jpg", emptyList(), modifiedAt = 0),
            file("old.jpg", emptyList(), modifiedAt = 10),
            file("new.jpg", emptyList(), modifiedAt = 20),
        )

        val ascending = buildSyncedBrowserEntries(
            files,
            emptyList(),
            LocalSortField.MODIFIED_TIME,
            LocalSortDirection.ASCENDING,
        )
        val descending = buildSyncedBrowserEntries(
            files,
            emptyList(),
            LocalSortField.MODIFIED_TIME,
            LocalSortDirection.DESCENDING,
        )

        assertEquals(listOf("old.jpg", "new.jpg", "unknown.jpg"), fileNames(ascending))
        assertEquals(listOf("new.jpg", "old.jpg", "unknown.jpg"), fileNames(descending))
    }

    @Test
    fun `search filters immediate entries without flattening nested folders`() {
        val files = listOf(
            file("vacation.jpg", emptyList()),
            file("invoice.pdf", emptyList()),
            file("child.jpg", listOf("图库")),
        )

        assertEquals(
            listOf("vacation.jpg"),
            fileNames(buildSyncedBrowserEntries(files, emptyList(), query = "VACA")),
        )
        assertTrue(buildSyncedBrowserEntries(files, emptyList(), query = "child").isEmpty())
    }

    @Test
    fun `name and file type sorting honor direction with stable name fallback`() {
        val files = listOf(
            file("z.png", emptyList()),
            file("b.jpg", emptyList()),
            file("a.jpg", emptyList()),
        )

        assertEquals(
            listOf("z.png", "b.jpg", "a.jpg"),
            fileNames(
                buildSyncedBrowserEntries(
                    files,
                    emptyList(),
                    LocalSortField.NAME,
                    LocalSortDirection.DESCENDING,
                ),
            ),
        )
        assertEquals(
            listOf("a.jpg", "b.jpg", "z.png"),
            fileNames(
                buildSyncedBrowserEntries(
                    files,
                    emptyList(),
                    LocalSortField.FILE_TYPE,
                    LocalSortDirection.ASCENDING,
                ),
            ),
        )
    }

    private fun fileNames(entries: List<SyncedBrowserEntry>): List<String> =
        entries.filterIsInstance<SyncedBrowserEntry.File>().map { it.item.displayName }

    private fun file(
        name: String,
        directories: List<String>,
        modifiedAt: Long = 0,
        mimeType: String? = "application/octet-stream",
        size: Long = 1,
    ) = SyncedFile(
        id = name.hashCode().toLong(),
        contentUri = "content://downloads/$name",
        displayName = name,
        mimeType = mimeType,
        size = size,
        modifiedAtMillis = modifiedAt,
        directories = directories,
    )
}

package com.faker1024.icloudsync.core.local

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

    private fun file(
        name: String,
        directories: List<String>,
        modifiedAt: Long = 0,
        mimeType: String? = "application/octet-stream",
    ) = SyncedFile(
        id = name.hashCode().toLong(),
        contentUri = "content://downloads/$name",
        displayName = name,
        mimeType = mimeType,
        size = 1,
        modifiedAtMillis = modifiedAt,
        directories = directories,
    )
}

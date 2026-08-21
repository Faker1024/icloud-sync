package com.faker1024.icloudsync.core.local

import com.faker1024.icloudsync.core.database.SyncedFileMetadataEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncedFileMetadataTest {
    @Test
    fun `matching cloud metadata overrides MediaStore modified time`() {
        val metadata = metadata().copy(relativePath = "Download/iCloud Drive/图库")

        assertEquals(
            1_710_930_000_000L,
            resolveSyncedModifiedAtMillis(
                indexedModifiedAtMillis = 1_800_000_000_000L,
                metadata = metadata,
                contentUri = metadata.contentUri,
                displayName = metadata.displayName,
                relativePath = "Download/iCloud Drive/图库/",
                size = metadata.size,
            ),
        )
    }

    @Test
    fun `stale cloud metadata falls back to MediaStore modified time`() {
        val metadata = metadata()
        val indexed = 1_800_000_000_000L

        assertEquals(
            indexed,
            resolveSyncedModifiedAtMillis(
                indexedModifiedAtMillis = indexed,
                metadata = metadata,
                contentUri = metadata.contentUri,
                displayName = metadata.displayName,
                relativePath = metadata.relativePath,
                size = metadata.size + 1L,
            ),
        )
    }

    @Test
    fun `missing cloud modified time falls back to MediaStore`() {
        val metadata = metadata().copy(remoteModifiedAtMillis = 0L)
        val indexed = 1_800_000_000_000L

        assertEquals(
            indexed,
            resolveSyncedModifiedAtMillis(
                indexedModifiedAtMillis = indexed,
                metadata = metadata,
                contentUri = metadata.contentUri,
                displayName = metadata.displayName,
                relativePath = metadata.relativePath,
                size = metadata.size,
            ),
        )
    }

    private fun metadata() = SyncedFileMetadataEntity(
        contentUri = "content://media/external/downloads/42",
        remoteItemId = "FILE::com.apple.CloudDocs::42",
        displayName = "照片.jpg",
        relativePath = "Download/iCloud Drive/图库/",
        size = 2_048L,
        remoteModifiedAtMillis = 1_710_930_000_000L,
        updatedAt = 1_800_000_000_000L,
    )
}

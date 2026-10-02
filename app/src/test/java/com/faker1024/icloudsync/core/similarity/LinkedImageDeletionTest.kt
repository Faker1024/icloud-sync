package com.faker1024.icloudsync.core.similarity

import com.faker1024.icloudsync.core.database.SyncedFileMetadataEntity
import com.faker1024.icloudsync.core.local.SyncedFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedImageDeletionTest {
    @Test
    fun `cloud succeeds and is journaled before local unlink`() = runBlocking {
        val calls = mutableListOf<String>()
        performLinkedDeletion(false, { calls += "cloud" }, { calls += "journal" }, { calls += "local" }, { calls += "complete" })
        assertEquals(listOf("cloud", "journal", "local", "complete"), calls)
    }

    @Test
    fun `cloud failure leaves local content and metadata intact`() = runBlocking {
        val calls = mutableListOf<String>()
        val failure = runCatching {
            performLinkedDeletion(false, { error("offline") }, { calls += "journal" }, { calls += "local" }, { calls += "complete" })
        }
        assertTrue(failure.isFailure)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `failure to persist cloud confirmation must keep local file`() = runBlocking {
        var localDeleted = false
        val failure = runCatching {
            performLinkedDeletion(false, {}, { error("database unavailable") }, { localDeleted = true }, {})
        }
        assertTrue(failure.isFailure)
        assertEquals(false, localDeleted)
    }

    @Test
    fun `local failure keeps cloud confirmation for restart recovery`() = runBlocking {
        val calls = mutableListOf<String>()
        runCatching {
            performLinkedDeletion(false, { calls += "cloud" }, { calls += "journal" }, { error("read only") }, { calls += "complete" })
        }
        assertEquals(listOf("cloud", "journal"), calls)
        performLinkedDeletion(true, { error("must not call cloud again") }, { error("already journaled") }, { calls += "local" }, { calls += "complete" })
        assertEquals(listOf("cloud", "journal", "local", "complete"), calls)
    }

    @Test
    fun `mapping accepts exact legacy record without account binding for cloud preflight`() {
        validateDeletionMapping(file, metadata, "account-one")
    }

    @Test
    fun `retaining a local copy protects its shared remote file`() {
        val copyUri = "content://private-files/another/one.jpg"
        val records = listOf(metadata, metadata.copy(contentUri = copyUri))
        assertEquals(setOf(metadata.remoteItemId), retainedRemoteIds(records, setOf(file.contentUri), setOf(file.contentUri, copyUri)))
        assertTrue(retainedRemoteIds(records, setOf(file.contentUri, copyUri), setOf(file.contentUri, copyUri)).isEmpty())
        assertTrue(retainedRemoteIds(records, setOf(file.contentUri), setOf(file.contentUri)).isEmpty())
    }

    @Test
    fun `mapping rejects cross account stale size path and missing remote identity`() {
        val invalid = listOf(
            metadata.copy(accountKey = "account-two"),
            metadata.copy(size = 1),
            metadata.copy(remoteModifiedAtMillis = 0),
            metadata.copy(remoteModifiedAtMillis = 456_000),
            metadata.copy(contentUri = "content://other/file"),
            metadata.copy(relativePath = "Download/iCloud Drive/Elsewhere/"),
            metadata.copy(remoteItemId = "legacy:content://downloads/1"),
            metadata.copy(remoteItemId = "FOLDER::com.apple.CloudDocs::id"),
        )
        invalid.forEach { record ->
            assertTrue(runCatching { validateDeletionMapping(file, record, "account-one") }.isFailure)
        }
    }

    private val file = SyncedFile(1, "content://private-files/one.jpg", "one.jpg", "image/jpeg", 1234, 123_000, listOf("图库"))
    private val metadata = SyncedFileMetadataEntity(
        file.contentUri, "FILE::com.apple.CloudDocs::id", file.displayName,
        "Download/iCloud Drive/图库/", file.size, file.modifiedAtMillis, 456_000,
    )
}

package com.faker1024.icloudsync.feature.main

import com.faker1024.icloudsync.core.local.SyncedFile
import com.faker1024.icloudsync.core.similarity.SimilarImageGroup
import com.faker1024.icloudsync.core.similarity.ImageDeletionResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSimilaritySelectionTest {
    @Test
    fun `selection always keeps at least one image in each group`() {
        val groups = listOf(group("one", "a", "b", "c"), group("two", "d", "e"))
        assertTrue(canSelectSimilarityFile(groups, emptySet(), "a"))
        assertTrue(canSelectSimilarityFile(groups, setOf("a"), "b"))
        assertFalse(canSelectSimilarityFile(groups, setOf("a", "b"), "c"))
        assertTrue(canSelectSimilarityFile(groups, setOf("a", "b"), "d"))
        assertFalse(canSelectSimilarityFile(groups, setOf("a", "b", "d"), "e"))
    }

    @Test
    fun `unknown or stale selection cannot be submitted for deletion`() {
        val groups = listOf(group("one", "a", "b"))
        assertFalse(canSelectSimilarityFile(groups, emptySet(), "missing"))
        assertFalse(isSimilaritySelectionSafe(groups, setOf("a", "b")))
        assertFalse(isSimilaritySelectionSafe(groups, setOf("missing")))
        assertTrue(isSimilaritySelectionSafe(groups, setOf("a")))
    }

    @Test
    fun `images shared between groups cannot remove the final keeper in either group`() {
        val groups = listOf(group("one", "a", "b"), group("two", "b", "c"))
        assertFalse(canSelectSimilarityFile(groups, setOf("a"), "b"))
        assertTrue(canSelectSimilarityFile(groups, setOf("a"), "c"))
    }

    @Test
    fun `journal confirmed pending files can be retried without a similarity group`() {
        assertTrue(canSelectSimilarityFile(emptyList(), emptySet(), "pending", setOf("pending")))
        assertTrue(isSimilaritySelectionSafe(emptyList(), setOf("pending"), setOf("pending")))
        assertFalse(canSelectSimilarityFile(emptyList(), emptySet(), "unknown", setOf("pending")))
    }

    @Test
    fun `untrusted failure flag does not authorize standalone local cleanup`() {
        val file = group("one", "failed").files.single()
        val state = ImageSimilarityUiState(
            deletionFailures = listOf(ImageDeletionResult(file, true, false, "failed")),
            selectedUris = setOf(file.contentUri),
        )
        assertTrue(state.confirmedPendingUris.isEmpty())
        assertTrue(state.selectedFiles.isEmpty())
        assertFalse(isSimilaritySelectionSafe(state.groups, state.selectedUris, state.confirmedPendingUris))
    }

    @Test
    fun `pending cleanup exemption never allows selecting all regular group members`() {
        val groups = listOf(group("one", "a", "b"))
        assertTrue(isSimilaritySelectionSafe(groups, setOf("a", "pending"), setOf("pending")))
        assertFalse(isSimilaritySelectionSafe(groups, setOf("a", "b", "pending"), setOf("pending")))
    }

    @Test
    fun `already cloud deleted image cannot serve as keeper for a regular deletion`() {
        val groups = listOf(group("one", "pending", "keeper"))
        assertTrue(isSimilaritySelectionSafe(groups, setOf("pending"), setOf("pending")))
        assertFalse(canSelectSimilarityFile(groups, setOf("pending"), "keeper", setOf("pending")))
        assertFalse(canSelectSimilarityFile(groups, emptySet(), "keeper", setOf("pending")))
    }

    private fun group(id: String, vararg uris: String) = SimilarImageGroup(
        id = id,
        files = uris.map { SyncedFile(it.hashCode().toLong(), it, "$it.jpg", "image/jpeg", 100, 1, emptyList()) },
        score = 99,
    )
}

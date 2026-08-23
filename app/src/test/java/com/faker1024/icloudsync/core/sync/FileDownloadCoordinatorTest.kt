package com.faker1024.icloudsync.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileDownloadCoordinatorTest {
    @Test
    fun `work tag safely round trips unicode remote id`() {
        val itemId = "FILE::com.apple.CloudDocs::照片-一"
        val encoded = FileDownloadCoordinator.fileIdTag(itemId).substringAfter(':')
        assertEquals(itemId, FileDownloadCoordinator.decodeItemId(encoded))
    }

    @Test
    fun `invalid work tag payload is rejected`() {
        assertNull(FileDownloadCoordinator.decodeItemId("%%%"))
    }
}

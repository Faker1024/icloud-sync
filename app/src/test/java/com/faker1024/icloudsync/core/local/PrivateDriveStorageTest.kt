package com.faker1024.icloudsync.core.local

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateDriveStorageTest {
    @Test
    fun `stable collision suffix preserves extension`() {
        val first = addStablePrivateFileSuffix("家庭照片.jpg", "remote-id")
        val second = addStablePrivateFileSuffix("家庭照片.jpg", "remote-id")

        assertEquals(first, second)
        assertTrue(first.startsWith("家庭照片 (iCloud-"))
        assertTrue(first.endsWith(".jpg"))
    }

    @Test
    fun `private directory parser returns safe nested path`() {
        val root = Files.createTempDirectory("private-drive-root").toFile()
        try {
            val file = root.resolve("图库/2026/照片.jpg")
            assertTrue(checkNotNull(file.parentFile).mkdirs())
            assertTrue(file.createNewFile())

            assertEquals(listOf("图库", "2026"), privateDriveDirectories(root, file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `private directory parser rejects file outside root`() {
        val root = Files.createTempDirectory("private-drive-root").toFile()
        val outside = Files.createTempFile("outside-drive", ".jpg").toFile()
        try {
            assertNull(privateDriveDirectories(root, outside))
        } finally {
            outside.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun `migration digest is deterministic`() {
        val input = "icloud-private-file".toByteArray()

        assertTrue(
            sha256(ByteArrayInputStream(input)).contentEquals(
                sha256(ByteArrayInputStream(input)),
            ),
        )
    }
}

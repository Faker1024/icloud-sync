package com.faker1024.icloudsync.core.media

import com.faker1024.icloudsync.domain.model.MediaKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaTypeDetectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsJpegBySignature() {
        val file = temporaryFolder.newFile("photo.bin").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00))
        }

        val detected = MediaTypeDetector.detect(file, "photo.bin")

        assertEquals(MediaKind.IMAGE, detected?.kind)
        assertEquals("image/jpeg", detected?.mimeType)
    }

    @Test
    fun doesNotTrustJpegExtensionWithoutSignature() {
        val file = temporaryFolder.newFile("fake.jpg").apply { writeText("not an image") }

        assertNull(MediaTypeDetector.detect(file, "fake.jpg"))
    }

    @Test
    fun identifiesZipFromMagicBytes() {
        val file = temporaryFolder.newFile("download.bin").apply {
            writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0))
        }

        assertTrue(MediaTypeDetector.looksLikeZip(file, "download.bin", null))
        assertFalse(MediaTypeDetector.looksLikeZip(File(file.parentFile, "missing"), null, null))
    }
}

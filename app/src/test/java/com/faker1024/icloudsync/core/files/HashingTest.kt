package com.faker1024.icloudsync.core.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HashingTest {
    @Test
    fun copyAndDigest_copiesEveryByteAndReturnsKnownSha256() {
        val input = "icloud-photo-import".toByteArray()
        val output = ByteArrayOutputStream()

        val result = Hashing.copyAndDigest(ByteArrayInputStream(input), output)

        assertArrayEquals(input, output.toByteArray())
        assertEquals(input.size.toLong(), result.byteCount)
        assertEquals(
            "1f0f38cb78e74bb92c58788b06a3b3f6a8acbca54415c463a2e0a01b92cd6858",
            result.sha256,
        )
    }

    @Test
    fun sha256_emptyInputReturnsStandardDigest() {
        val result = Hashing.sha256(ByteArrayInputStream(byteArrayOf()))

        assertEquals(0, result.byteCount)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            result.sha256,
        )
    }
}

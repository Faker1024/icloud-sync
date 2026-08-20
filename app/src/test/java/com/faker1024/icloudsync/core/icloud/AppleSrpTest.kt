package com.faker1024.icloudsync.core.icloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppleSrpTest {
    @Test
    fun `derives Apple s2k password material`() {
        val result = AppleSrp.derivePassword("test_password", SALT, 1000, "s2k")
        assertEquals("8bfd9c828eefeafd5482cd1a6ce1335154348b310dda0092bd936889993187c3", result.hex())
    }

    @Test
    fun `derives Apple s2k fo password material`() {
        val result = AppleSrp.derivePassword("test_password", SALT, 1000, "s2k_fo")
        assertEquals("8a16082c4f4999ab710d849aa148d203f2770e14e46fc9217cd4b5c1a48c2dc6", result.hex())
    }

    @Test
    fun `matches independent SRP proof vector`() {
        val client = AppleSrp(ByteArray(32) { (it + 1).toByte() })
        assertEquals(256, client.publicValue().size)
        val proofs = client.proofs(
            accountName = "TEST@example.com",
            password = "test_password",
            salt = SALT,
            iterations = 1000,
            protocol = "s2k",
            serverPublicValue = ByteArray(256) { 0x7f },
        )
        assertArrayEquals("dba31948e79db3fea7c5e04e12588e25ef1cd86a6dc31e5ea082a6af16e98272".hexBytes(), proofs.m1)
        assertArrayEquals("33cbfe8626894c832618f4c3925ee7549c4f5ae7a6270e7f2f5e170d63ca70f9".hexBytes(), proofs.m2)
    }

    @Test
    fun `rejects invalid server public value`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppleSrp(ByteArray(32) { 1 }).proofs(
                "test@example.com",
                "password",
                SALT,
                1000,
                "s2k",
                ByteArray(256),
            )
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        val SALT = "test_salt_value!".toByteArray()
    }
}

package com.faker1024.icloudsync.core.icloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ICloudPcsProtocolTest {
    @Test
    fun `waits while trusted device has not approved`() {
        assertEquals(ICloudPcsPollResult.WAITING, ICloudPcsProtocol.evaluate("pending", false))
        assertEquals(ICloudPcsPollResult.WAITING, ICloudPcsProtocol.evaluate("", false))
    }

    @Test
    fun `requires documents cookie after successful approval`() {
        val error = assertThrows(ICloudApiException::class.java) {
            ICloudPcsProtocol.evaluate("success", false)
        }
        assertEquals(ICloudError.INVALID_RESPONSE, error.reason)
    }

    @Test
    fun `accepts approval only with documents cookie`() {
        assertEquals(ICloudPcsPollResult.APPROVED, ICloudPcsProtocol.evaluate("SUCCESS", true))
    }
}

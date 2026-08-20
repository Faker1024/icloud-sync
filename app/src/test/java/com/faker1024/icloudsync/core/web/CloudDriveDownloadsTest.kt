package com.faker1024.icloudsync.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDriveDownloadsTest {
    @Test
    fun `sanitizes path separators and reserved characters`() {
        assertEquals("_.._secret_.pdf", sanitizeCloudFileName("../../secret?.pdf"))
    }

    @Test
    fun `falls back when file name contains no usable characters`() {
        assertEquals("iCloud-file", sanitizeCloudFileName("..."))
    }

    @Test
    fun `only accepts exact Apple domain suffixes`() {
        assertTrue(isTrustedCloudDownloadHost("cvws.icloud-content.com.cn"))
        assertTrue(isTrustedCloudDownloadHost("www.icloud.com.cn"))
        assertTrue(isTrustedCloudDownloadHost("assets.apple-cloudkit.com"))
        assertTrue(isTrustedCloudDownloadHost("setup.icloud.com.cn"))
        assertFalse(isTrustedCloudDownloadHost("icloud.com.cn.example.com"))
        assertFalse(isTrustedCloudDownloadHost("evilicloud.com.cn"))
        assertFalse(isTrustedCloudDownloadHost(null))
    }
}

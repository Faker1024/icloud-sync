package com.faker1024.icloudsync.core.icloud

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ICloudCookieJarTest {
    @Test
    fun `stores and detects PCS documents cookie`() {
        val jar = ICloudCookieJar()
        val url = "https://setup.icloud.com.cn/setup/ws/1/requestPCS".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(
                Cookie.Builder()
                    .name("X-APPLE-WEBAUTH-PCS-Documents")
                    .value("encrypted-authorization")
                    .domain("icloud.com.cn")
                    .path("/")
                    .secure()
                    .build(),
            ),
        )
        assertTrue(jar.contains("X-APPLE-WEBAUTH-PCS-Documents"))
        assertTrue(jar.headerFor("https://drivews.icloud.com.cn/retrieve".toHttpUrl()).contains("PCS-Documents"))
    }

    @Test
    fun `does not accept expired PCS cookie`() {
        val jar = ICloudCookieJar()
        val url = "https://setup.icloud.com.cn/".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(
                Cookie.Builder()
                    .name("X-APPLE-WEBAUTH-PCS-Documents")
                    .value("expired")
                    .domain("icloud.com.cn")
                    .path("/")
                    .expiresAt(1L)
                    .build(),
            ),
        )
        assertFalse(jar.contains("X-APPLE-WEBAUTH-PCS-Documents"))
    }
}

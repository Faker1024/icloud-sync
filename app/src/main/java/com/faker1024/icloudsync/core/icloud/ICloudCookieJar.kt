package com.faker1024.icloudsync.core.icloud

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

internal class ICloudCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach { incoming ->
            this.cookies.removeAll {
                it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path
            }
            if (incoming.expiresAt > now && incoming.value.isNotEmpty()) this.cookies += incoming
        }
        this.cookies.removeAll { it.expiresAt <= now }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun replace(values: List<Cookie>) {
        cookies.clear()
        cookies += values.filter { it.expiresAt > System.currentTimeMillis() }
    }

    @Synchronized
    fun snapshot(): List<Cookie> = cookies.toList()

    @Synchronized
    fun clear() = cookies.clear()

    @Synchronized
    fun contains(name: String): Boolean = cookies.any {
        it.name == name && it.value.isNotEmpty() && it.expiresAt > System.currentTimeMillis()
    }

    @Synchronized
    fun headerFor(url: HttpUrl): String = loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }
}

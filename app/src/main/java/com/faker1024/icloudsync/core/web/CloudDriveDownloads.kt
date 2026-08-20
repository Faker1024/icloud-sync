package com.faker1024.icloudsync.core.web

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import java.io.File

object CloudDriveDownloads {
    const val PUBLIC_DOWNLOAD_PATH = "Download/iCloud Drive/"

    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): Result<String> = runCatching {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && isTrustedCloudDownloadHost(uri.host)) {
            "iCloud 返回了无法安全下载的链接"
        }

        val fileName = uniqueFileName(
            sanitizeCloudFileName(URLUtil.guessFileName(url, contentDisposition, mimeType)),
        )
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setDescription("正在保存到 $PUBLIC_DOWNLOAD_PATH")
            .setMimeType(mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "iCloud Drive/$fileName",
            )

        CookieManager.getInstance().getCookie(url)
            ?.takeIf { it.isNotBlank() }
            ?.let { request.addRequestHeader("Cookie", it) }
        userAgent?.takeIf { it.isNotBlank() }
            ?.let { request.addRequestHeader("User-Agent", it) }
        request.addRequestHeader("Referer", CHINA_ICLOUD_DRIVE_URL)

        val manager = context.getSystemService(DownloadManager::class.java)
            ?: error("系统下载服务不可用")
        check(manager.enqueue(request) >= 0) { "系统下载服务拒绝了任务" }
        fileName
    }

    @Suppress("DEPRECATION")
    private fun uniqueFileName(fileName: String): String {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "iCloud Drive",
        )
        if (!File(directory, fileName).exists()) return fileName

        val dot = fileName.lastIndexOf('.').takeIf { it > 0 }
        val base = dot?.let { fileName.substring(0, it) } ?: fileName
        val extension = dot?.let { fileName.substring(it) }.orEmpty()
        for (index in 2..9999) {
            val candidate = "$base ($index)$extension"
            if (!File(directory, candidate).exists()) return candidate
        }
        return "${System.currentTimeMillis()}-$fileName"
    }
}

const val CHINA_ICLOUD_DRIVE_URL = "https://www.icloud.com.cn/iclouddrive/"

internal fun sanitizeCloudFileName(value: String): String {
    val sanitized = value
        .replace(Regex("""[\\/:*?"<>|\p{Cc}]"""), "_")
        .trim()
        .trimStart('.')
        .trimEnd('.')
        .ifBlank { "iCloud-file" }
    if (sanitized.length <= MAX_FILE_NAME_LENGTH) return sanitized

    val extensionIndex = sanitized.lastIndexOf('.').takeIf { it in 1 until sanitized.lastIndex }
    val extension = extensionIndex?.let(sanitized::substring).orEmpty().take(20)
    return sanitized.take(MAX_FILE_NAME_LENGTH - extension.length) + extension
}

internal fun isTrustedCloudDownloadHost(hostValue: String?): Boolean {
    val host = hostValue?.lowercase() ?: return false
    return TRUSTED_DOWNLOAD_SUFFIXES.any { suffix ->
        host == suffix || host.endsWith(".$suffix")
    }
}

private const val MAX_FILE_NAME_LENGTH = 180
private val TRUSTED_DOWNLOAD_SUFFIXES = setOf(
    "icloud.com.cn",
    "icloud-content.com.cn",
    "icloud-content.com",
    "apple-cloudkit.com",
    "apzones.com",
    "cdn-apple.com",
    "apple.com",
)

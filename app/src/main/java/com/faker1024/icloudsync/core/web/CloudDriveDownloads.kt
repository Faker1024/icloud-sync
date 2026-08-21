package com.faker1024.icloudsync.core.web

import com.faker1024.icloudsync.core.icloud.isTrustedAppleHost

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
    val host = hostValue ?: return false
    return isTrustedAppleHost(host)
}

private const val MAX_FILE_NAME_LENGTH = 180

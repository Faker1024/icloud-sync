package com.faker1024.icloudsync.core.icloud

import java.util.Base64

data class ICloudDriveItem(
    val id: String,
    val name: String,
    val type: String,
    val size: Long,
    val modifiedAt: String?,
    val childCount: Long,
) {
    val isFolder: Boolean
        get() = type == "FOLDER" || type == "APP_CONTAINER" || type == "APP_LIBRARY"
}

internal fun ICloudDriveItem.toWorkPayload(): String = listOf(
    WORK_PAYLOAD_VERSION,
    encodeWorkValue(id),
    encodeWorkValue(name),
    encodeWorkValue(type),
    size.toString(),
    encodeWorkValue(modifiedAt.orEmpty()),
    childCount.toString(),
).joinToString(WORK_PAYLOAD_SEPARATOR)

internal fun iCloudDriveItemFromWorkPayload(value: String?): ICloudDriveItem? = runCatching {
    val parts = value?.split(WORK_PAYLOAD_SEPARATOR) ?: return@runCatching null
    if (parts.size != WORK_PAYLOAD_PARTS || parts[0] != WORK_PAYLOAD_VERSION) return@runCatching null
    ICloudDriveItem(
        id = decodeWorkValue(parts[1]),
        name = decodeWorkValue(parts[2]),
        type = decodeWorkValue(parts[3]),
        size = parts[4].toLong(),
        modifiedAt = decodeWorkValue(parts[5]).takeIf(String::isNotBlank),
        childCount = parts[6].toLong(),
    ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() && !it.isFolder }
}.getOrNull()

private fun encodeWorkValue(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodeWorkValue(value: String): String =
    String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

private const val WORK_PAYLOAD_VERSION = "1"
private const val WORK_PAYLOAD_SEPARATOR = "|"
private const val WORK_PAYLOAD_PARTS = 7

data class ICloudFolder(val id: String, val name: String)

data class TrustedPhone(
    val id: Int,
    val label: String,
    val mode: String = "sms",
)

sealed interface ICloudLoginResult {
    data object Authenticated : ICloudLoginResult
    data object NeedsPcsApproval : ICloudLoginResult
    data class NeedsTwoFactor(val phones: List<TrustedPhone>) : ICloudLoginResult
}

enum class ICloudPcsPollResult { APPROVED, WAITING }

internal data class ICloudSession(
    val appleId: String,
    var sessionToken: String = "",
    var scnt: String = "",
    var sessionId: String = "",
    var accountCountry: String = "",
    var trustToken: String = "",
    var authAttributes: String = "",
    val frameId: String,
    var driveEndpoint: String = "",
    var docsEndpoint: String = "",
    var drivePcsRequired: Boolean = false,
)

class ICloudApiException(
    val reason: ICloudError,
    message: String,
) : Exception(message)

enum class ICloudError {
    NETWORK,
    BAD_CREDENTIALS,
    BAD_CODE,
    SESSION_EXPIRED,
    RATE_LIMITED,
    ADVANCED_DATA_PROTECTION,
    SERVICE_UNAVAILABLE,
    INVALID_RESPONSE,
}

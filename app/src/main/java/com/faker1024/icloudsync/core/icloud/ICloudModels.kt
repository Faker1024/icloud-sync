package com.faker1024.icloudsync.core.icloud

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

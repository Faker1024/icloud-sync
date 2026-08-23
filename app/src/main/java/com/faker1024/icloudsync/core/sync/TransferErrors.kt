package com.faker1024.icloudsync.core.sync

import com.faker1024.icloudsync.core.icloud.ICloudApiException
import com.faker1024.icloudsync.core.icloud.ICloudError

fun Throwable.isRetryableTransferError(): Boolean = when ((this as? ICloudApiException)?.reason) {
    ICloudError.BAD_CREDENTIALS,
    ICloudError.BAD_CODE,
    ICloudError.SESSION_EXPIRED,
    ICloudError.ADVANCED_DATA_PROTECTION,
    ICloudError.INVALID_RESPONSE,
    -> false
    else -> true
}

package com.faker1024.icloudsync.core.web

import android.webkit.CookieManager
import android.webkit.WebStorage

object CloudDriveSession {
    fun clear(onComplete: (() -> Unit)? = null) {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            onComplete?.invoke()
        }
    }
}

package com.faker1024.icloudsync.core.icloud

import android.content.Context
import android.webkit.MimeTypeMap
import com.faker1024.icloudsync.core.web.CloudDriveDownloads
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ICloudDriveRepository @Inject internal constructor(
    private val api: ICloudApiClient,
    @param:ApplicationContext private val context: Context,
) {
    suspend fun restoreSession(): Boolean = withContext(Dispatchers.IO) { api.restoreSession() }

    suspend fun login(accountName: String, password: String): ICloudLoginResult =
        withContext(Dispatchers.IO) { api.beginLogin(accountName, password) }

    suspend fun verifyCode(code: String, phoneId: Int?) =
        withContext(Dispatchers.IO) { api.verifyTwoFactor(code, phoneId) }

    suspend fun resendTrustedDeviceCode() = withContext(Dispatchers.IO) { api.requestTrustedDeviceCode() }

    suspend fun requestSmsCode(phoneId: Int) = withContext(Dispatchers.IO) { api.requestSmsCode(phoneId) }

    suspend fun listFolder(folderId: String): List<ICloudDriveItem> =
        withContext(Dispatchers.IO) { api.listFolder(folderId) }

    suspend fun download(item: ICloudDriveItem): String = withContext(Dispatchers.IO) {
        val ticket = api.downloadTicket(item)
        CloudDriveDownloads.enqueue(
            context = context,
            url = ticket.url,
            fileName = item.name,
            mimeType = mimeTypeFor(item.name),
            cookieHeader = ticket.cookieHeader,
        ).getOrThrow()
    }

    fun logout() = api.logout()

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    companion object {
        const val ROOT_ID = "FOLDER::com.apple.CloudDocs::root"
    }
}

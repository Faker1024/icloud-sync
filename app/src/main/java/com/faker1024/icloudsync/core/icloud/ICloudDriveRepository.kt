package com.faker1024.icloudsync.core.icloud

import android.graphics.Bitmap
import com.faker1024.icloudsync.core.sync.DownloadStoreResult
import com.faker1024.icloudsync.core.sync.DownloadWriteProgress
import com.faker1024.icloudsync.core.sync.ICloudDownloadStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ICloudDriveRepository @Inject internal constructor(
    private val api: ICloudApiClient,
    private val previewLoader: ICloudPreviewLoader,
    private val downloadStore: ICloudDownloadStore,
) {
    suspend fun restoreSession(): Boolean = withContext(Dispatchers.IO) { api.restoreSession() }

    suspend fun login(accountName: String, password: String): ICloudLoginResult =
        withContext(Dispatchers.IO) { api.beginLogin(accountName, password) }

    suspend fun verifyCode(code: String, phoneId: Int?): ICloudLoginResult =
        withContext(Dispatchers.IO) { api.verifyTwoFactor(code, phoneId) }

    suspend fun needsPcsApproval(): Boolean = withContext(Dispatchers.IO) { api.needsPcsApproval() }

    suspend fun pollPcsApproval(): ICloudPcsPollResult = withContext(Dispatchers.IO) { api.pollPcsApproval() }

    suspend fun resendTrustedDeviceCode() = withContext(Dispatchers.IO) { api.requestTrustedDeviceCode() }

    suspend fun requestSmsCode(phoneId: Int) = withContext(Dispatchers.IO) { api.requestSmsCode(phoneId) }

    suspend fun listFolder(folderId: String): List<ICloudDriveItem> =
        withContext(Dispatchers.IO) { api.listFolder(folderId) }

    suspend fun loadImagePreview(item: ICloudDriveItem, targetPixels: Int): Bitmap =
        previewLoader.load(item, targetPixels)

    suspend fun prepareImagePreviewSource(item: ICloudDriveItem): File =
        previewLoader.prepareSource(item)

    suspend fun saveSyncedFile(
        item: ICloudDriveItem,
        directories: List<String>,
        onProgress: suspend (DownloadWriteProgress) -> Unit = {},
    ): DownloadStoreResult = withContext(Dispatchers.IO) {
        downloadStore.save(
            item = item,
            directories = directories,
            onProgress = onProgress,
            sourceProvider = { offset -> api.openDownload(item, offset) },
        )
    }

    fun logout() {
        api.logout()
        previewLoader.clear()
    }

    companion object {
        const val ROOT_ID = "FOLDER::com.apple.CloudDocs::root"
    }
}

package com.faker1024.icloudsync.core.icloud

import android.graphics.Bitmap
import com.faker1024.icloudsync.core.sync.DownloadStoreResult
import com.faker1024.icloudsync.core.sync.DownloadWriteProgress
import com.faker1024.icloudsync.core.sync.ICloudDownloadStore
import com.faker1024.icloudsync.core.sync.ICloudFileMutationGuard
import com.faker1024.icloudsync.core.database.ImageDeletionDao
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
    private val mutationGuard: ICloudFileMutationGuard,
    private val imageDeletionDao: ImageDeletionDao,
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
    ): DownloadStoreResult = withContext(Dispatchers.IO) { mutationGuard.transfer {
        val accountKey = api.accountKey()
            ?: throw ICloudApiException(ICloudError.SESSION_EXPIRED, "请先登录 iCloud")
        if (imageDeletionDao.isDeleted(item.id, accountKey) > 0) {
            throw ICloudApiException(ICloudError.INVALID_RESPONSE, "该图片已通过相似图片清理删除，请刷新云盘目录")
        }
        downloadStore.save(
            item = item,
            directories = directories,
            accountKey = accountKey,
            onProgress = onProgress,
            sourceProvider = { offset -> api.openDownload(item, offset, accountKey) },
        )
    } }

    suspend fun logout() = withContext(Dispatchers.IO) {
        api.logout()
        previewLoader.clear()
    }

    companion object {
        const val ROOT_ID = "FOLDER::com.apple.CloudDocs::root"
    }
}

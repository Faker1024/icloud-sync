package com.faker1024.icloudsync.core.similarity

import android.content.Context
import androidx.work.WorkManager
import com.faker1024.icloudsync.core.database.ImageDeletionDao
import com.faker1024.icloudsync.core.database.ImageDeletionEntity
import com.faker1024.icloudsync.core.database.SyncFailureDao
import com.faker1024.icloudsync.core.database.SyncedFileMetadataDao
import com.faker1024.icloudsync.core.database.SyncedFileMetadataEntity
import com.faker1024.icloudsync.core.icloud.ICloudApiClient
import com.faker1024.icloudsync.core.local.PrivateStorageMigrationCoordinator
import com.faker1024.icloudsync.core.local.SyncedFile
import com.faker1024.icloudsync.core.local.SyncedFileRepository
import com.faker1024.icloudsync.core.local.privateDriveFile
import com.faker1024.icloudsync.core.local.privateDriveRoot
import com.faker1024.icloudsync.core.local.privateDriveUri
import com.faker1024.icloudsync.core.sync.FileDownloadCoordinator
import com.faker1024.icloudsync.core.sync.FolderSyncCoordinator
import com.faker1024.icloudsync.core.sync.ICloudFileMutationGuard
import com.faker1024.icloudsync.core.sync.buildDownloadRelativePath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DeletionProgress(val completed: Int, val total: Int)

data class ImageDeletionResult(
    val file: SyncedFile,
    val cloudDeleted: Boolean,
    val localDeleted: Boolean,
    val message: String,
)

/** Called only after explicit per-image selection and the two-location confirmation dialog. */
@Singleton
class SimilarImageDeletionRepository @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ICloudApiClient,
    private val metadataDao: SyncedFileMetadataDao,
    private val deletionDao: ImageDeletionDao,
    private val failureDao: SyncFailureDao,
    private val localFiles: SyncedFileRepository,
    private val mutationGuard: ICloudFileMutationGuard,
) {
    suspend fun pendingLocalDeletions(files: List<SyncedFile>): List<ImageDeletionResult> = withContext(Dispatchers.IO) {
        val pending = deletionDao.pendingLocalDeletions().associateBy(ImageDeletionEntity::contentUri)
        files.mapNotNull { file ->
            val entry = pending[file.contentUri] ?: return@mapNotNull null
            if (entry.size != file.size || entry.modifiedAtMillis != file.modifiedAtMillis) return@mapNotNull null
            ImageDeletionResult(file, true, false, "云端已移入最近删除，本地副本等待清理")
        }
    }

    suspend fun delete(
        files: List<SyncedFile>,
        onProgress: suspend (DeletionProgress) -> Unit = {},
    ): List<ImageDeletionResult> = withContext(Dispatchers.IO) {
        mutationGuard.deletion {
            checkNoBackgroundWrites()
            val selected = files.distinctBy(SyncedFile::contentUri)
            val protectedRemoteIds = retainedRemoteIds(
                metadataDao.listAll(),
                selected.mapTo(mutableSetOf(), SyncedFile::contentUri),
                localFiles.listFiles().mapTo(mutableSetOf(), SyncedFile::contentUri),
            )
            val results = mutableListOf<ImageDeletionResult>()
            onProgress(DeletionProgress(0, selected.size))
            for (file in selected) {
                currentCoroutineContext().ensureActive()
                // Finish and journal the current item even if its screen is closed mid-request.
                results += withContext(NonCancellable) { deleteOne(file, protectedRemoteIds) }
                onProgress(DeletionProgress(results.size, selected.size))
            }
            results
        }
    }

    private fun checkNoBackgroundWrites() {
        val manager = WorkManager.getInstance(context)
        val tags = listOf(
            FolderSyncCoordinator.FOLDER_SYNC_TAG,
            FileDownloadCoordinator.FILE_DOWNLOAD_TAG,
            PrivateStorageMigrationCoordinator.MIGRATION_TAG,
        )
        check(tags.none { tag -> manager.getWorkInfosByTag(tag).get().any { !it.state.isFinished } }) {
            "还有下载、同步或迁移任务，请完成或暂停这些任务后再删除"
        }
    }

    private suspend fun deleteOne(file: SyncedFile, protectedRemoteIds: Set<String>): ImageDeletionResult {
        var cloudDeleted = false
        var localDeleted = false
        try {
            require(file.isImage) { "只能清理已选择的图片" }
            val target = privateTarget(file)
            val previous = deletionDao.get(file.contentUri)
            if (previous?.state == COMPLETE) {
                if (!target.exists()) return ImageDeletionResult(file, true, true, "两端文件已完成删除")
                val replacement = metadataDao.get(file.contentUri)
                check(replacement != null && replacement.remoteItemId != previous.remoteItemId) {
                    "此位置重新出现了文件，旧删除记录不能用于删除新文件，请重新同步并检查"
                }
                // A different synced ID at a reused path needs its own fresh cloud preflight below.
            }
            val continuing = previous?.takeIf { it.state == CLOUD_DELETED }
            val entry: ImageDeletionEntity
            if (continuing != null) {
                require(continuing.size == file.size && continuing.modifiedAtMillis == file.modifiedAtMillis) {
                    "本地记录已改变，请重新扫描"
                }
                cloudDeleted = true
                entry = continuing
                val metadata = checkNotNull(metadataDao.get(file.contentUri)) { "无法核对待清理本地副本，请重新检查" }
                validateDeletionMapping(file, metadata, entry.accountKey)
                require(metadata.remoteItemId == entry.remoteItemId) { "本地对应的云端文件已改变，已停止清理" }
                if (target.exists()) validateLocalFile(target, entry.size, entry.localModifiedAtMillis)
            } else {
                check(api.restoreSession()) { "请在云盘页登录原来的 iCloud 账户后再删除" }
                val accountKey = checkNotNull(api.accountKey()) { "请先登录 iCloud" }
                val metadata = checkNotNull(metadataDao.get(file.contentUri)) {
                    "缺少云端对应记录，请先重新同步该图片"
                }
                validateDeletionMapping(file, metadata, accountKey)
                require(metadata.remoteItemId !in protectedRemoteIds) {
                    "有未选中的本地副本对应同一云端文件，已保留两端文件"
                }
                validateLocalFile(target, file.size, file.localModifiedAtMillis)
                entry = ImageDeletionEntity(
                    contentUri = file.contentUri,
                    remoteItemId = metadata.remoteItemId,
                    accountKey = accountKey,
                    size = metadata.size,
                    modifiedAtMillis = metadata.remoteModifiedAtMillis,
                    localModifiedAtMillis = target.lastModified(),
                    state = PREPARED,
                    updatedAt = System.currentTimeMillis(),
                )
                deletionDao.upsert(entry)
            }
            performLinkedDeletion(
                cloudAlreadyDeleted = cloudDeleted,
                trashCloud = {
                    api.trashFile(entry.remoteItemId, entry.size, entry.modifiedAtMillis, entry.accountKey)
                    cloudDeleted = true
                },
                recordCloudDeletion = {
                    deletionDao.upsert(entry.copy(state = CLOUD_DELETED, updatedAt = System.currentTimeMillis()))
                },
                deleteLocal = {
                    if (target.exists()) {
                        validateLocalFile(target, entry.size, entry.localModifiedAtMillis)
                        check(target.delete()) { "云端已移入最近删除，本地删除失败，请重试" }
                    }
                    localDeleted = true
                },
                recordComplete = {
                    deletionDao.upsert(entry.copy(state = COMPLETE, updatedAt = System.currentTimeMillis()))
                    metadataDao.delete(file.contentUri)
                    failureDao.deleteRemoteItem(entry.remoteItemId)
                },
            )
            return ImageDeletionResult(file, true, true, "本地已删除 · 云端已移入最近删除")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val prefix = if (cloudDeleted) "云端已移入最近删除；" else "本地已保留；"
            return ImageDeletionResult(file, cloudDeleted, localDeleted, prefix + (error.message ?: "删除未完成，请重试"))
        }
    }

    private fun privateTarget(file: SyncedFile): File {
        val target = privateDriveFile(context, file.directories, file.displayName).canonicalFile
        val root = privateDriveRoot(context).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "文件不在私密目录内" }
        require(privateDriveUri(context, target).toString() == file.contentUri) {
            "请先在设置中将旧版公共文件迁移到私密存储，再同步删除"
        }
        return target
    }

    private fun validateLocalFile(target: File, expectedSize: Long, expectedMtime: Long?) {
        check(target.isFile && target.length() == expectedSize) { "本地文件已改变，请重新扫描" }
        if (expectedMtime != null) check(target.lastModified() == expectedMtime) {
            "本地文件在删除期间发生变化，已保留，请重新检查"
        }
    }

    private companion object {
        const val PREPARED = "PREPARED"
        const val CLOUD_DELETED = "CLOUD_DELETED"
        const val COMPLETE = "COMPLETE"
    }
}

internal fun retainedRemoteIds(
    metadata: List<SyncedFileMetadataEntity>,
    selectedUris: Set<String>,
    existingUris: Set<String>,
): Set<String> = metadata.asSequence()
    .filter { it.contentUri in existingUris && it.contentUri !in selectedUris }
    .map(SyncedFileMetadataEntity::remoteItemId)
    .toSet()

internal fun validateDeletionMapping(file: SyncedFile, metadata: SyncedFileMetadataEntity, accountKey: String) {
    require(metadata.accountKey == null || metadata.accountKey == accountKey) {
        "该图片属于另一个 iCloud 账户，请切换回原账户"
    }
    require(metadata.remoteItemId.startsWith("FILE::") && metadata.remoteItemId.split("::").size == 3) {
        "旧版图片缺少可靠的云端 ID，请先重新同步该图片"
    }
    require(
        metadata.contentUri == file.contentUri && metadata.displayName == file.displayName &&
            metadata.relativePath.trimEnd('/') == buildDownloadRelativePath(file.directories).trimEnd('/') &&
            metadata.size == file.size && metadata.remoteModifiedAtMillis == file.modifiedAtMillis &&
            metadata.remoteModifiedAtMillis > 0,
    ) { "本地图片与同步记录不一致，请先重新同步该图片" }
}

/** Cloud failure must never remove local content; successful cloud deletion is durable before unlink. */
internal suspend fun performLinkedDeletion(
    cloudAlreadyDeleted: Boolean,
    trashCloud: suspend () -> Unit,
    recordCloudDeletion: suspend () -> Unit,
    deleteLocal: suspend () -> Unit,
    recordComplete: suspend () -> Unit,
) {
    if (!cloudAlreadyDeleted) {
        trashCloud()
        recordCloudDeletion()
    }
    deleteLocal()
    recordComplete()
}

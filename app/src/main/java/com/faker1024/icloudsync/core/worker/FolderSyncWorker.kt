package com.faker1024.icloudsync.core.worker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.faker1024.icloudsync.R
import com.faker1024.icloudsync.core.icloud.ICloudApiException
import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
import com.faker1024.icloudsync.core.icloud.ICloudDriveRepository
import com.faker1024.icloudsync.core.icloud.ICloudError
import com.faker1024.icloudsync.core.sync.FolderSyncProgressKeys
import com.faker1024.icloudsync.core.sync.FolderSyncStage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

@HiltWorker
class FolderSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ICloudDriveRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val rootFolderId = inputData.getString(KEY_FOLDER_ID) ?: return Result.failure()
        val rootFolderName = inputData.getString(KEY_FOLDER_NAME) ?: "iCloud 文件夹"
        val localRootPath = parsePath(inputData.getString(KEY_LOCAL_PATH_JSON))
        setForeground(foregroundInfo(FolderSyncStage.QUEUED, rootFolderName, 0, 0))

        if (!runCatching { repository.restoreSession() }.getOrDefault(false)) {
            return fail("iCloud 登录已过期，请打开 App 重新登录", rootFolderName)
        }
        return try {
            val files = scanTree(rootFolderId, localRootPath, rootFolderName)
            downloadFiles(files, rootFolderName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (error.isRetryable() && runAttemptCount < MAX_TASK_RETRIES) {
                updateProgress(
                    stage = FolderSyncStage.RETRYING,
                    currentFile = "连接中断，系统将自动重试",
                )
                Result.retry()
            } else {
                fail(error.safeMessage(), rootFolderName)
            }
        }
    }

    private suspend fun scanTree(
        rootFolderId: String,
        localRootPath: List<String>,
        rootFolderName: String,
    ): List<RemoteFile> {
        val pending = ArrayDeque<RemoteFolder>()
        val visited = mutableSetOf<String>()
        val files = mutableListOf<RemoteFile>()
        pending.add(RemoteFolder(rootFolderId, localRootPath))
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val folder = pending.removeFirst()
            if (!visited.add(folder.id)) continue
            val items = retryOperation { repository.listFolder(folder.id) }
            items.forEach { item ->
                if (item.isFolder) {
                    pending.add(RemoteFolder(item.id, folder.localPath + item.name))
                } else {
                    files += RemoteFile(item, folder.localPath)
                    if (files.size > MAX_FILES_PER_SYNC) {
                        throw FolderSyncFatalException("文件夹超过 $MAX_FILES_PER_SYNC 个文件，请拆分后同步")
                    }
                }
            }
            updateProgress(
                stage = FolderSyncStage.SCANNING,
                scannedFolders = visited.size,
                totalFiles = files.size,
                currentFile = "正在扫描 ${folder.localPath.lastOrNull() ?: rootFolderName}",
            )
            setForeground(foregroundInfo(FolderSyncStage.SCANNING, rootFolderName, files.size, 0))
        }
        return files
    }

    private suspend fun downloadFiles(files: List<RemoteFile>, rootFolderName: String): Result {
        val totalBytes = files.fold(0L) { total, file -> safeAdd(total, file.item.size.coerceAtLeast(0L)) }
        var completedFiles = 0
        var completedBytes = 0L
        val failures = mutableListOf<String>()
        if (files.isEmpty()) {
            updateProgress(stage = FolderSyncStage.COMPLETE, totalFiles = 0, completedFiles = 0)
            notifyFinished(rootFolderName, 0, success = true, message = "文件夹为空，无需下载")
            return Result.success(successData(0, 0L))
        }

        files.forEach { remote ->
            currentCoroutineContext().ensureActive()
            val fileName = remote.item.name.take(MAX_PROGRESS_FILE_NAME)
            updateProgress(
                stage = FolderSyncStage.DOWNLOADING,
                totalFiles = files.size,
                completedFiles = completedFiles,
                failedFiles = failures.size,
                totalBytes = totalBytes,
                completedBytes = completedBytes,
                currentFile = fileName,
            )
            val outcome = runCatching {
                retryOperation { repository.saveSyncedFile(remote.item, remote.localPath) }
            }
            outcome.onSuccess { saved ->
                completedFiles++
                completedBytes = safeAdd(completedBytes, saved.bytes)
            }.onFailure { error ->
                if (!error.isRetryable()) throw error
                failures += fileName
            }
            updateProgress(
                stage = FolderSyncStage.DOWNLOADING,
                totalFiles = files.size,
                completedFiles = completedFiles,
                failedFiles = failures.size,
                totalBytes = totalBytes,
                completedBytes = completedBytes,
                currentFile = fileName,
            )
            setForeground(
                foregroundInfo(
                    stage = FolderSyncStage.DOWNLOADING,
                    folderName = rootFolderName,
                    totalFiles = files.size,
                    completedFiles = completedFiles,
                    failedFiles = failures.size,
                    currentFile = fileName,
                ),
            )
        }

        if (failures.isNotEmpty()) {
            if (runAttemptCount < MAX_TASK_RETRIES) {
                updateProgress(
                    stage = FolderSyncStage.RETRYING,
                    totalFiles = files.size,
                    completedFiles = completedFiles,
                    failedFiles = failures.size,
                    totalBytes = totalBytes,
                    completedBytes = completedBytes,
                    currentFile = "${failures.size} 个文件失败，系统将自动重试",
                )
                return Result.retry()
            }
            val message = "仍有 ${failures.size} 个文件未能下载：${failures.take(3).joinToString("、")}"
            return fail(message, rootFolderName, files.size, completedFiles, failures.size)
        }

        updateProgress(
            stage = FolderSyncStage.COMPLETE,
            totalFiles = files.size,
            completedFiles = completedFiles,
            totalBytes = totalBytes,
            completedBytes = completedBytes,
        )
        notifyFinished(rootFolderName, completedFiles, success = true, message = "所有文件均已校验并保存")
        return Result.success(successData(completedFiles, completedBytes))
    }

    private suspend fun <T> retryOperation(block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(PER_OPERATION_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (!error.isRetryable()) throw error
                lastError = error
                if (attempt < PER_OPERATION_ATTEMPTS - 1) {
                    delay(PER_OPERATION_RETRY_DELAY_MS shl attempt)
                }
            }
        }
        throw lastError ?: IllegalStateException("同步操作失败")
    }

    private suspend fun updateProgress(
        stage: FolderSyncStage,
        scannedFolders: Int = 0,
        totalFiles: Int = 0,
        completedFiles: Int = 0,
        failedFiles: Int = 0,
        totalBytes: Long = 0L,
        completedBytes: Long = 0L,
        currentFile: String = "",
    ) {
        setProgress(
            workDataOf(
                FolderSyncProgressKeys.STAGE to stage.name,
                FolderSyncProgressKeys.SCANNED_FOLDERS to scannedFolders,
                FolderSyncProgressKeys.TOTAL_FILES to totalFiles,
                FolderSyncProgressKeys.COMPLETED_FILES to completedFiles,
                FolderSyncProgressKeys.FAILED_FILES to failedFiles,
                FolderSyncProgressKeys.TOTAL_BYTES to totalBytes,
                FolderSyncProgressKeys.COMPLETED_BYTES to completedBytes,
                FolderSyncProgressKeys.CURRENT_FILE to currentFile.take(MAX_PROGRESS_FILE_NAME),
            ),
        )
    }

    private fun fail(
        message: String,
        folderName: String,
        totalFiles: Int = 0,
        completedFiles: Int = 0,
        failedFiles: Int = 0,
    ): Result {
        notifyFinished(folderName, completedFiles, success = false, message = message)
        return Result.failure(
            workDataOf(
                FolderSyncProgressKeys.STAGE to FolderSyncStage.FAILED.name,
                FolderSyncProgressKeys.TOTAL_FILES to totalFiles,
                FolderSyncProgressKeys.COMPLETED_FILES to completedFiles,
                FolderSyncProgressKeys.FAILED_FILES to failedFiles,
                FolderSyncProgressKeys.ERROR to message.take(300),
            ),
        )
    }

    private fun successData(completedFiles: Int, completedBytes: Long) = workDataOf(
        FolderSyncProgressKeys.STAGE to FolderSyncStage.COMPLETE.name,
        FolderSyncProgressKeys.TOTAL_FILES to completedFiles,
        FolderSyncProgressKeys.COMPLETED_FILES to completedFiles,
        FolderSyncProgressKeys.COMPLETED_BYTES to completedBytes,
    )

    private fun foregroundInfo(
        stage: FolderSyncStage,
        folderName: String,
        totalFiles: Int,
        completedFiles: Int,
        failedFiles: Int = 0,
        currentFile: String = "",
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val content = when (stage) {
            FolderSyncStage.QUEUED -> "正在准备同步"
            FolderSyncStage.SCANNING -> "正在扫描目录，已发现 $totalFiles 个文件"
            FolderSyncStage.DOWNLOADING -> buildString {
                append("$completedFiles/$totalFiles")
                if (failedFiles > 0) append(" · 暂时失败 $failedFiles")
                if (currentFile.isNotBlank()) append(" · $currentFile")
            }
            FolderSyncStage.RETRYING -> "下载中断，等待自动重试"
            FolderSyncStage.COMPLETE -> "同步完成"
            FolderSyncStage.FAILED -> "同步未完成"
        }
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            FolderSyncNotifications.CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("同步 $folderName")
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(totalFiles, completedFiles, totalFiles <= 0)
            .addAction(0, "取消", cancelIntent)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + id.hashCode().and(0x0FFF),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun notifyFinished(folderName: String, count: Int, success: Boolean, message: String) {
        val notification = NotificationCompat.Builder(applicationContext, FolderSyncNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (success) "$folderName 同步完成" else "$folderName 同步未完成")
            .setContentText(if (success) "$count 个文件已保存到 Download/iCloud Drive/" else message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            ?.notify(COMPLETION_NOTIFICATION_ID_BASE + id.hashCode().and(0x0FFF), notification)
    }

    private fun Throwable.isRetryable(): Boolean = when ((this as? ICloudApiException)?.reason) {
        ICloudError.BAD_CREDENTIALS,
        ICloudError.BAD_CODE,
        ICloudError.SESSION_EXPIRED,
        ICloudError.ADVANCED_DATA_PROTECTION,
        ICloudError.INVALID_RESPONSE,
        -> false
        else -> this !is FolderSyncFatalException
    }

    private fun Throwable.safeMessage(): String = message?.takeIf(String::isNotBlank)
        ?: "文件夹同步失败，请打开 App 后重试"

    private fun parsePath(value: String?): List<String> = runCatching {
        val array = JSONArray(value ?: "[]")
        List(array.length()) { index -> array.getString(index) }
    }.getOrDefault(emptyList())

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private data class RemoteFolder(val id: String, val localPath: List<String>)
    private data class RemoteFile(val item: ICloudDriveItem, val localPath: List<String>)
    private class FolderSyncFatalException(message: String) : Exception(message)

    companion object {
        const val KEY_FOLDER_ID = "folder_id"
        const val KEY_FOLDER_NAME = "folder_name"
        const val KEY_LOCAL_PATH_JSON = "local_path_json"
        private const val MAX_FILES_PER_SYNC = 100_000
        private const val PER_OPERATION_ATTEMPTS = 3
        private const val MAX_TASK_RETRIES = 5
        private const val PER_OPERATION_RETRY_DELAY_MS = 1_000L
        private const val MAX_PROGRESS_FILE_NAME = 160
        private const val NOTIFICATION_ID_BASE = 30_000
        private const val COMPLETION_NOTIFICATION_ID_BASE = 40_000
    }
}

object FolderSyncNotifications {
    const val CHANNEL_ID = "icloud_folder_sync"
}

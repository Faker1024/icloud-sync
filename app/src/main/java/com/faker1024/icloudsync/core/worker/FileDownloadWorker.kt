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
import com.faker1024.icloudsync.core.icloud.ICloudDriveRepository
import com.faker1024.icloudsync.core.icloud.iCloudDriveItemFromWorkPayload
import com.faker1024.icloudsync.core.local.PRIVATE_DRIVE_DISPLAY_PATH
import com.faker1024.icloudsync.core.sync.FileDownloadProgressKeys
import com.faker1024.icloudsync.core.sync.FileDownloadStage
import com.faker1024.icloudsync.core.sync.isRetryableTransferError
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.json.JSONArray

@HiltWorker
class FileDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ICloudDriveRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val item = iCloudDriveItemFromWorkPayload(inputData.getString(KEY_ITEM_PAYLOAD))
            ?: return Result.failure()
        val localPath = parsePath(inputData.getString(KEY_LOCAL_PATH_JSON))
        updateProgress(FileDownloadStage.QUEUED, item.name, 0L, item.size, 0L)
        setForeground(foregroundInfo(item.name, 0L, item.size, 0L, FileDownloadStage.QUEUED))
        if (!runCatching { repository.restoreSession() }.getOrDefault(false)) {
            return fail(item.name, "iCloud 登录已过期，请打开 APP 重新登录")
        }
        var latestBytes = 0L
        var latestTotalBytes = item.size.coerceAtLeast(0L)
        var latestResumedBytes = 0L
        return try {
            val result = repository.saveSyncedFile(item, localPath) { progress ->
                latestBytes = progress.bytesWritten
                latestTotalBytes = (progress.totalBytes ?: item.size).coerceAtLeast(0L)
                latestResumedBytes = progress.resumedBytes
                updateProgress(
                    FileDownloadStage.DOWNLOADING,
                    item.name,
                    progress.bytesWritten,
                    progress.totalBytes ?: item.size,
                    progress.resumedBytes,
                )
                setForeground(
                    foregroundInfo(
                        item.name,
                        progress.bytesWritten,
                        progress.totalBytes ?: item.size,
                        progress.resumedBytes,
                        FileDownloadStage.DOWNLOADING,
                    ),
                )
            }
            notifyFinished(item.name, true, if (result.skipped) "本地文件已是最新版本" else "文件已保存到私密存储")
            Result.success(
                workDataOf(
                    FileDownloadProgressKeys.STAGE to FileDownloadStage.COMPLETE.name,
                    FileDownloadProgressKeys.FILE_NAME to item.name,
                    FileDownloadProgressKeys.BYTES to result.bytes,
                    FileDownloadProgressKeys.TOTAL_BYTES to result.bytes,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = error.message?.takeIf(String::isNotBlank) ?: "文件下载失败"
            if (error.isRetryableTransferError() && runAttemptCount < MAX_TASK_RETRIES) {
                updateProgress(
                    FileDownloadStage.RETRYING,
                    item.name,
                    latestBytes,
                    latestTotalBytes,
                    latestResumedBytes,
                    message,
                )
                Result.retry()
            } else {
                fail(item.name, message)
            }
        }
    }

    private suspend fun updateProgress(
        stage: FileDownloadStage,
        fileName: String,
        bytes: Long,
        totalBytes: Long,
        resumedBytes: Long,
        error: String? = null,
    ) {
        setProgress(
            workDataOf(
                FileDownloadProgressKeys.STAGE to stage.name,
                FileDownloadProgressKeys.FILE_NAME to fileName.take(MAX_FILE_NAME),
                FileDownloadProgressKeys.BYTES to bytes.coerceAtLeast(0L),
                FileDownloadProgressKeys.TOTAL_BYTES to totalBytes.coerceAtLeast(0L),
                FileDownloadProgressKeys.RESUMED_BYTES to resumedBytes.coerceAtLeast(0L),
                FileDownloadProgressKeys.ERROR to error,
            ),
        )
    }

    private fun fail(fileName: String, message: String): Result {
        notifyFinished(fileName, false, message)
        return Result.failure(
            workDataOf(
                FileDownloadProgressKeys.STAGE to FileDownloadStage.FAILED.name,
                FileDownloadProgressKeys.FILE_NAME to fileName.take(MAX_FILE_NAME),
                FileDownloadProgressKeys.ERROR to message.take(MAX_ERROR_LENGTH),
            ),
        )
    }

    private fun foregroundInfo(
        fileName: String,
        bytes: Long,
        totalBytes: Long,
        resumedBytes: Long,
        stage: FileDownloadStage,
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val total = totalBytes.coerceAtLeast(0L)
        val progress = if (total > 0L) {
            ((bytes.coerceAtLeast(0L).toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val detail = when (stage) {
            FileDownloadStage.QUEUED -> "等待网络和后台执行条件"
            FileDownloadStage.RETRYING -> "连接中断，等待自动重试"
            else -> buildString {
                append("$progress%")
                if (resumedBytes > 0L) append(" · 已从断点续传")
            }
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, FolderSyncNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("下载 $fileName")
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, total <= 0L)
            .addAction(0, "暂停", cancelIntent)
            .build()
        return ForegroundInfo(
            FILE_NOTIFICATION_BASE + id.hashCode().and(0x0FFF),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun notifyFinished(fileName: String, success: Boolean, message: String) {
        val notification = NotificationCompat.Builder(applicationContext, FolderSyncNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (success) "$fileName 下载完成" else "$fileName 下载失败")
            .setContentText(if (success) "$message · $PRIVATE_DRIVE_DISPLAY_PATH" else message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            ?.notify(FILE_COMPLETE_NOTIFICATION_BASE + id.hashCode().and(0x0FFF), notification)
    }

    private fun parsePath(value: String?): List<String> = runCatching {
        val array = JSONArray(value ?: "[]")
        List(array.length()) { index -> array.getString(index) }
    }.getOrDefault(emptyList())

    companion object {
        const val KEY_ITEM_PAYLOAD = "file_download_item_payload"
        const val KEY_LOCAL_PATH_JSON = "file_download_local_path_json"
        private const val MAX_TASK_RETRIES = 5
        private const val MAX_FILE_NAME = 160
        private const val MAX_ERROR_LENGTH = 300
        private const val FILE_NOTIFICATION_BASE = 60_000
        private const val FILE_COMPLETE_NOTIFICATION_BASE = 70_000
    }
}

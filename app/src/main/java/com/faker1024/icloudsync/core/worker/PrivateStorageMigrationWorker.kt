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
import com.faker1024.icloudsync.core.local.PublicFileMigrationStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@HiltWorker
class PrivateStorageMigrationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val migrationStore: PublicFileMigrationStore,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        setForeground(foregroundInfo(0, 0, 0, "正在检查公共文件"))
        val files = migrationStore.listMigratableFiles()
        if (files.isEmpty()) return Result.success(successData(0, 0L))
        setForeground(foregroundInfo(files.size, 0, 0, "正在准备迁移"))
        var completed = 0
        var completedBytes = 0L
        var failed = 0
        val failedNames = mutableListOf<String>()
        files.forEach { file ->
            currentCoroutineContext().ensureActive()
            updateProgress(files.size, completed, failed, completedBytes, file.displayName)
            try {
                val bytes = migrationStore.migrate(file)
                completed++
                completedBytes = safeAdd(completedBytes, bytes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                failed++
                failedNames += file.displayName
            }
            updateProgress(files.size, completed, failed, completedBytes, file.displayName)
            setForeground(foregroundInfo(files.size, completed, failed, file.displayName))
        }
        if (failed > 0) {
            if (runAttemptCount < MAX_RETRIES) return Result.retry()
            val message = "仍有 $failed 个公共文件未能迁移：${failedNames.take(3).joinToString("、")}"
            notifyFinished(false, message)
            return Result.failure(
                workDataOf(
                    PrivateStorageMigrationKeys.TOTAL to files.size,
                    PrivateStorageMigrationKeys.COMPLETED to completed,
                    PrivateStorageMigrationKeys.FAILED to failed,
                    PrivateStorageMigrationKeys.ERROR to message,
                ),
            )
        }
        notifyFinished(true, "$completed 个文件已迁移到 APP 私密存储")
        return Result.success(successData(completed, completedBytes))
    }

    private suspend fun updateProgress(
        total: Int,
        completed: Int,
        failed: Int,
        completedBytes: Long,
        currentFile: String,
    ) {
        setProgress(
            workDataOf(
                PrivateStorageMigrationKeys.TOTAL to total,
                PrivateStorageMigrationKeys.COMPLETED to completed,
                PrivateStorageMigrationKeys.FAILED to failed,
                PrivateStorageMigrationKeys.COMPLETED_BYTES to completedBytes,
                PrivateStorageMigrationKeys.CURRENT_FILE to currentFile.take(MAX_FILE_NAME),
            ),
        )
    }

    private fun foregroundInfo(
        total: Int,
        completed: Int,
        failed: Int,
        currentFile: String,
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val content = buildString {
            append("$completed/$total")
            if (failed > 0) append(" · 暂时失败 $failed")
            if (currentFile.isNotBlank()) append(" · $currentFile")
        }
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            FolderSyncNotifications.CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("迁移到私密存储")
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, completed, total <= 0)
            .addAction(0, "取消", cancelIntent)
            .build()
        return ForegroundInfo(
            MIGRATION_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun notifyFinished(success: Boolean, message: String) {
        val notification = NotificationCompat.Builder(
            applicationContext,
            FolderSyncNotifications.CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (success) "私密迁移完成" else "私密迁移未完成")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            ?.notify(MIGRATION_COMPLETE_NOTIFICATION_ID, notification)
    }

    private fun successData(completed: Int, completedBytes: Long) = workDataOf(
        PrivateStorageMigrationKeys.TOTAL to completed,
        PrivateStorageMigrationKeys.COMPLETED to completed,
        PrivateStorageMigrationKeys.COMPLETED_BYTES to completedBytes,
    )

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right.coerceAtLeast(0L)

    companion object {
        private const val MAX_RETRIES = 2
        private const val MAX_FILE_NAME = 160
        private const val MIGRATION_NOTIFICATION_ID = 51_001
        private const val MIGRATION_COMPLETE_NOTIFICATION_ID = 51_002
    }
}

object PrivateStorageMigrationKeys {
    const val TOTAL = "private_migration_total"
    const val COMPLETED = "private_migration_completed"
    const val FAILED = "private_migration_failed"
    const val COMPLETED_BYTES = "private_migration_completed_bytes"
    const val CURRENT_FILE = "private_migration_current_file"
    const val ERROR = "private_migration_error"
}

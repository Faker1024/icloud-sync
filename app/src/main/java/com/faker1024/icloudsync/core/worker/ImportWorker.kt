package com.faker1024.icloudsync.core.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.faker1024.icloudsync.R
import com.faker1024.icloudsync.core.importer.ImportProcessor
import com.faker1024.icloudsync.domain.model.ImportErrorCode
import com.faker1024.icloudsync.domain.model.ImportException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val processor: ImportProcessor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID) ?: return Result.failure()
        setForeground(createForegroundInfo())
        return try {
            processor.process(batchId)
            Result.success()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { processor.cancelBatch(batchId) }
            throw cancellation
        } catch (exception: ImportException) {
            processor.failBatch(batchId, exception.code)
            Result.failure()
        } catch (_: Exception) {
            processor.failBatch(batchId, ImportErrorCode.UNKNOWN)
            Result.failure()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            ImportNotifications.CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.import_notification_title))
            .setContentText("正在校验并保存下载文件")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(0, "取消", cancelIntent)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + id.hashCode().and(0x0FFF),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_BATCH_ID = "batch_id"
        private const val NOTIFICATION_ID_BASE = 20_000
    }
}

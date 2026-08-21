package com.faker1024.icloudsync.core.local

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.faker1024.icloudsync.core.worker.PrivateStorageMigrationWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class PrivateStorageMigrationCoordinator @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun enqueue(): UUID = withContext(Dispatchers.IO) {
        val existing = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
            .firstOrNull { !it.state.isFinished }
        if (existing != null) return@withContext existing.id
        val request = OneTimeWorkRequestBuilder<PrivateStorageMigrationWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(MIGRATION_TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        request.id
    }

    fun observe(): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkLiveData(UNIQUE_WORK_NAME).asFlow()

    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    companion object {
        const val MIGRATION_TAG = "icloud-private-storage-migration"
        private const val UNIQUE_WORK_NAME = "icloud-private-storage-migration-v1"
    }
}

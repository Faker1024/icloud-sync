package com.faker1024.icloudsync.core.sync

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.faker1024.icloudsync.core.database.SyncFailureDao
import com.faker1024.icloudsync.core.settings.CloudBrowserSettings
import com.faker1024.icloudsync.core.local.PRIVATE_DRIVE_DISPLAY_PATH
import com.faker1024.icloudsync.core.worker.FolderSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Singleton
class FolderSyncCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: CloudBrowserSettings,
    private val failureDao: SyncFailureDao,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun enqueue(
        folderId: String,
        folderName: String,
        localPath: List<String>,
    ): UUID = withContext(Dispatchers.IO) {
        val uniqueName = uniqueWorkName(folderId)
        val existing = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            .firstOrNull { !it.state.isFinished }
        val workId = if (existing != null) {
            existing.id
        } else {
            val request = OneTimeWorkRequestBuilder<FolderSyncWorker>()
                .setInputData(
                    workDataOf(
                        FolderSyncWorker.KEY_FOLDER_ID to folderId,
                        FolderSyncWorker.KEY_FOLDER_NAME to folderName,
                        FolderSyncWorker.KEY_LOCAL_PATH_JSON to JSONArray(localPath).toString(),
                        FolderSyncWorker.KEY_RETRY_FAILURES_ONLY to false,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(FOLDER_SYNC_TAG)
                .addTag(uniqueName)
                .build()
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
            request.id
        }
        settings.setLastSync(
            id = workId.toString(),
            folderId = folderId,
            folderName = folderName,
            displayPath = displayPath(localPath),
        )
        workId
    }

    fun observe(workId: UUID): Flow<WorkInfo?> = workManager.getWorkInfoByIdLiveData(workId).asFlow()

    fun observeFailures(folderId: String): Flow<List<SyncFailureDetail>> =
        failureDao.observeForScope(folderId).map { failures ->
            failures.map { failure ->
                SyncFailureDetail(
                    fileName = failure.displayName,
                    localPath = runCatching {
                        val array = JSONArray(failure.localPathJson)
                        List(array.length()) { index -> array.getString(index) }
                    }.getOrDefault(emptyList()),
                    error = failure.errorMessage,
                )
            }
        }

    suspend fun retryFailures(
        folderId: String,
        folderName: String,
        displayPath: String,
    ): UUID = withContext(Dispatchers.IO) {
        val uniqueName = uniqueWorkName(folderId)
        val existing = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            .firstOrNull { !it.state.isFinished }
        val workId = if (existing != null) {
            existing.id
        } else {
            val request = OneTimeWorkRequestBuilder<FolderSyncWorker>()
                .setInputData(
                    workDataOf(
                        FolderSyncWorker.KEY_FOLDER_ID to folderId,
                        FolderSyncWorker.KEY_FOLDER_NAME to folderName,
                        FolderSyncWorker.KEY_LOCAL_PATH_JSON to "[]",
                        FolderSyncWorker.KEY_RETRY_FAILURES_ONLY to true,
                    ),
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(FOLDER_SYNC_TAG)
                .addTag(uniqueName)
                .build()
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
            request.id
        }
        settings.setLastSync(workId.toString(), folderId, folderName, displayPath)
        workId
    }

    fun cancel(workId: UUID) {
        workManager.cancelWorkById(workId)
    }

    private fun uniqueWorkName(folderId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(folderId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "icloud-folder-sync-${digest.take(24)}"
    }

    private fun displayPath(localPath: List<String>): String = buildString {
        append(PRIVATE_DRIVE_DISPLAY_PATH)
        if (localPath.isNotEmpty()) append(localPath.joinToString("/")).append('/')
    }

    companion object {
        const val FOLDER_SYNC_TAG = "icloud-folder-sync"
    }
}

enum class FolderSyncStage {
    QUEUED,
    SCANNING,
    DOWNLOADING,
    RETRYING,
    COMPLETE,
    FAILED,
}

object FolderSyncProgressKeys {
    const val STAGE = "sync_stage"
    const val SCANNED_FOLDERS = "sync_scanned_folders"
    const val TOTAL_FILES = "sync_total_files"
    const val COMPLETED_FILES = "sync_completed_files"
    const val FAILED_FILES = "sync_failed_files"
    const val TOTAL_BYTES = "sync_total_bytes"
    const val COMPLETED_BYTES = "sync_completed_bytes"
    const val CURRENT_FILE = "sync_current_file"
    const val ERROR = "sync_error"
}

data class SyncFailureDetail(
    val fileName: String,
    val localPath: List<String>,
    val error: String,
)

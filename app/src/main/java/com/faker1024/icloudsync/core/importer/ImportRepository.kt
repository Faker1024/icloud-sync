package com.faker1024.icloudsync.core.importer

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.faker1024.icloudsync.core.database.ImportBatchDao
import com.faker1024.icloudsync.core.database.ImportBatchEntity
import com.faker1024.icloudsync.core.database.ImportedMediaDao
import com.faker1024.icloudsync.core.database.ImportedMediaEntity
import com.faker1024.icloudsync.core.worker.ImportWorker
import com.faker1024.icloudsync.domain.model.ImportBatchState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class ImportRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val batchDao: ImportBatchDao,
    private val mediaDao: ImportedMediaDao,
) {
    private val workManager = WorkManager.getInstance(context)

    fun observeRecentBatches(): Flow<List<ImportBatchEntity>> = batchDao.observeRecent()

    fun observeBatchItems(batchId: String): Flow<List<ImportedMediaEntity>> =
        mediaDao.observeForBatch(batchId)

    suspend fun enqueue(uri: Uri): String {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val metadata = queryMetadata(uri)
        val now = System.currentTimeMillis()
        val batchId = UUID.randomUUID().toString()
        batchDao.insert(
            ImportBatchEntity(
                id = batchId,
                sourceUri = uri.toString(),
                sourceDisplayName = metadata.displayName,
                sourceMimeType = context.contentResolver.getType(uri),
                sourceSize = metadata.size,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(Data.Builder().putString(ImportWorker.KEY_BATCH_ID, batchId).build())
            .addTag(batchId)
            .addTag(IMPORT_TAG)
            .build()
        workManager.enqueueUniqueWork(
            IMPORT_QUEUE,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return batchId
    }

    suspend fun cancel(batchId: String) {
        workManager.cancelAllWorkByTag(batchId)
        val now = System.currentTimeMillis()
        batchDao.updateState(
            id = batchId,
            state = ImportBatchState.CANCELLED.name,
            errorCode = "USER_CANCELLED",
            updatedAt = now,
            finishedAt = now,
        )
    }

    suspend fun deleteHistory(batchId: String) {
        batchDao.hide(batchId, System.currentTimeMillis())
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var displayName: String? = null
        var size: Long? = null
        val cursor: Cursor? = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
        cursor?.use {
            if (it.moveToFirst()) {
                displayName = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { index -> index >= 0 }
                    ?.let(it::getString)
                size = it.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { index -> index >= 0 && !it.isNull(index) }
                    ?.let(it::getLong)
            }
        }
        return SourceMetadata(displayName, size?.takeIf { it >= 0 })
    }

    private data class SourceMetadata(val displayName: String?, val size: Long?)

    private companion object {
        const val IMPORT_QUEUE = "photo-import-queue"
        const val IMPORT_TAG = "photo-import"
    }
}

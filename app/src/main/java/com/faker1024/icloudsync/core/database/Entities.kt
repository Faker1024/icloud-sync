package com.faker1024.icloudsync.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.faker1024.icloudsync.domain.model.ImportBatchState
import com.faker1024.icloudsync.domain.model.ImportedMediaState
import com.faker1024.icloudsync.domain.model.MediaKind

@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val sourceUri: String,
    val sourceDisplayName: String?,
    val sourceMimeType: String?,
    val sourceSize: Long?,
    val stagedPath: String? = null,
    val state: ImportBatchState = ImportBatchState.QUEUED,
    val totalCount: Int = 0,
    val importedCount: Int = 0,
    val duplicateCount: Int = 0,
    val failedCount: Int = 0,
    val unsupportedCount: Int = 0,
    val processedBytes: Long = 0,
    val totalBytes: Long? = sourceSize,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    val errorCode: String? = null,
    val hidden: Boolean = false,
) {
    val processedCount: Int
        get() = importedCount + duplicateCount + failedCount + unsupportedCount
}

@Entity(
    tableName = "imported_media",
    foreignKeys = [
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("batchId"),
        Index(value = ["sha256", "size"]),
        Index("destinationUri"),
    ],
)
data class ImportedMediaEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val entryName: String,
    val displayName: String,
    val mimeType: String?,
    val size: Long,
    val sha256: String?,
    val mediaKind: MediaKind,
    val captureTime: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val livePhotoGroupKey: String?,
    val destinationUri: String?,
    val state: ImportedMediaState,
    val errorCode: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "synced_file_metadata",
    indices = [Index("remoteItemId")],
)
data class SyncedFileMetadataEntity(
    @PrimaryKey val contentUri: String,
    val remoteItemId: String,
    val displayName: String,
    val relativePath: String,
    val size: Long,
    val remoteModifiedAtMillis: Long,
    val updatedAt: Long,
)

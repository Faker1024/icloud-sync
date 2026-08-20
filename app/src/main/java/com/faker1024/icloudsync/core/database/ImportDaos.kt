package com.faker1024.icloudsync.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportBatchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(batch: ImportBatchEntity)

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun get(id: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batches WHERE hidden = 0 ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ImportBatchEntity>>

    @Query(
        """
        UPDATE import_batches
        SET state = :state, errorCode = :errorCode, updatedAt = :updatedAt,
            finishedAt = :finishedAt
        WHERE id = :id
        """,
    )
    suspend fun updateState(
        id: String,
        state: String,
        errorCode: String?,
        updatedAt: Long,
        finishedAt: Long?,
    )

    @Query(
        """
        UPDATE import_batches
        SET stagedPath = :stagedPath, processedBytes = :processedBytes,
            totalBytes = :totalBytes, state = :state, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateStaging(
        id: String,
        stagedPath: String?,
        processedBytes: Long,
        totalBytes: Long?,
        state: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE import_batches
        SET totalCount = :totalCount, totalBytes = :totalBytes,
            processedBytes = 0, state = :state, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun prepareImport(
        id: String,
        totalCount: Int,
        totalBytes: Long?,
        state: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE import_batches
        SET importedCount = importedCount + :imported,
            duplicateCount = duplicateCount + :duplicate,
            failedCount = failedCount + :failed,
            unsupportedCount = unsupportedCount + :unsupported,
            processedBytes = processedBytes + :bytes,
            state = :state,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun recordOutcome(
        id: String,
        imported: Int,
        duplicate: Int,
        failed: Int,
        unsupported: Int,
        bytes: Long,
        state: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE import_batches
        SET hidden = 1, sourceUri = '', sourceDisplayName = NULL,
            sourceMimeType = NULL, stagedPath = NULL, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun hide(id: String, updatedAt: Long)
}

@Dao
interface ImportedMediaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ImportedMediaEntity)

    @Query(
        """
        SELECT * FROM imported_media
        WHERE sha256 = :sha256 AND size = :size AND state = 'IMPORTED'
        ORDER BY createdAt DESC
        """,
    )
    suspend fun findImportedCandidates(sha256: String, size: Long): List<ImportedMediaEntity>

    @Query(
        """
        SELECT COUNT(*) FROM imported_media
        WHERE displayName = :displayName AND state = 'IMPORTED'
        """,
    )
    suspend fun countImportedWithName(displayName: String): Int

    @Query(
        """
        SELECT errorCode FROM imported_media
        WHERE batchId = :batchId AND errorCode IS NOT NULL
        ORDER BY createdAt LIMIT 1
        """,
    )
    suspend fun firstErrorCode(batchId: String): String?

    @Query("SELECT * FROM imported_media WHERE batchId = :batchId ORDER BY createdAt")
    fun observeForBatch(batchId: String): Flow<List<ImportedMediaEntity>>
}

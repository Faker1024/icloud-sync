package com.faker1024.icloudsync.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncedFileMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: SyncedFileMetadataEntity)

    @Query("SELECT * FROM synced_file_metadata")
    suspend fun listAll(): List<SyncedFileMetadataEntity>

    @Query("SELECT * FROM synced_file_metadata WHERE contentUri = :contentUri LIMIT 1")
    suspend fun get(contentUri: String): SyncedFileMetadataEntity?

    @Query("DELETE FROM synced_file_metadata WHERE contentUri = :contentUri")
    suspend fun delete(contentUri: String)
}

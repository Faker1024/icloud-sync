package com.faker1024.icloudsync.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImageDeletionDao {
    @Query("SELECT * FROM image_deletions WHERE contentUri = :uri LIMIT 1")
    suspend fun get(uri: String): ImageDeletionEntity?

    @Query("SELECT * FROM image_deletions WHERE state = 'CLOUD_DELETED'")
    suspend fun pendingLocalDeletions(): List<ImageDeletionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ImageDeletionEntity)

    @Query("SELECT COUNT(*) FROM image_deletions WHERE remoteItemId = :remoteId AND accountKey = :accountKey AND state IN ('CLOUD_DELETED', 'COMPLETE')")
    suspend fun isDeleted(remoteId: String, accountKey: String): Int
}

package com.faker1024.icloudsync.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncFailureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(failure: SyncFailureEntity)

    @Query("DELETE FROM sync_failures WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sync_failures WHERE scopeId = :scopeId")
    suspend fun deleteScope(scopeId: String)

    @Query("DELETE FROM sync_failures WHERE remoteItemId = :remoteId")
    suspend fun deleteRemoteItem(remoteId: String)

    @Query("SELECT * FROM sync_failures WHERE scopeId = :scopeId ORDER BY updatedAt DESC")
    suspend fun listForScope(scopeId: String): List<SyncFailureEntity>

    @Query("SELECT * FROM sync_failures WHERE scopeId = :scopeId ORDER BY updatedAt DESC")
    fun observeForScope(scopeId: String): Flow<List<SyncFailureEntity>>
}

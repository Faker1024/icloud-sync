package com.faker1024.icloudsync.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ImportBatchEntity::class, ImportedMediaEntity::class, SyncedFileMetadataEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun importedMediaDao(): ImportedMediaDao
    abstract fun syncedFileMetadataDao(): SyncedFileMetadataDao
}

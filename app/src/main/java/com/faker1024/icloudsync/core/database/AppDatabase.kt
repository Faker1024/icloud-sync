package com.faker1024.icloudsync.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ImportBatchEntity::class,
        ImportedMediaEntity::class,
        SyncedFileMetadataEntity::class,
        SyncFailureEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun importedMediaDao(): ImportedMediaDao
    abstract fun syncedFileMetadataDao(): SyncedFileMetadataDao
    abstract fun syncFailureDao(): SyncFailureDao
}

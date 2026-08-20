package com.faker1024.icloudsync.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ImportBatchEntity::class, ImportedMediaEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun importedMediaDao(): ImportedMediaDao
}

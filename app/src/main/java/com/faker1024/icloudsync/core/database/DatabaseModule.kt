package com.faker1024.icloudsync.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "icloud-import.db").build()

    @Provides
    fun provideImportBatchDao(database: AppDatabase): ImportBatchDao = database.importBatchDao()

    @Provides
    fun provideImportedMediaDao(database: AppDatabase): ImportedMediaDao = database.importedMediaDao()

    @Provides
    fun provideSyncedFileMetadataDao(database: AppDatabase): SyncedFileMetadataDao =
        database.syncedFileMetadataDao()
}

package com.faker1024.icloudsync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import com.faker1024.icloudsync.core.worker.ImportNotifications
import com.faker1024.icloudsync.core.worker.FolderSyncNotifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ICloudSyncApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        val importChannel = NotificationChannel(
            ImportNotifications.CHANNEL_ID,
            getString(R.string.import_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示本地照片导入进度"
            setShowBadge(false)
        }
        val syncChannel = NotificationChannel(
            FolderSyncNotifications.CHANNEL_ID,
            getString(R.string.folder_sync_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 iCloud 私密同步、校验与文件迁移进度"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(importChannel, syncChannel),
        )
    }
}

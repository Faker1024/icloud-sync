package com.faker1024.icloudsync.core.sync

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
import com.faker1024.icloudsync.core.icloud.toWorkPayload
import com.faker1024.icloudsync.core.worker.FileDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Singleton
class FileDownloadCoordinator @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun enqueue(item: ICloudDriveItem, localPath: List<String>): UUID = withContext(Dispatchers.IO) {
        val uniqueName = uniqueWorkName(item.id)
        val existing = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            .firstOrNull { !it.state.isFinished }
        if (existing != null) return@withContext existing.id
        val request = OneTimeWorkRequestBuilder<FileDownloadWorker>()
            .setInputData(
                workDataOf(
                    FileDownloadWorker.KEY_ITEM_PAYLOAD to item.toWorkPayload(),
                    FileDownloadWorker.KEY_LOCAL_PATH_JSON to JSONArray(localPath).toString(),
                ),
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(FILE_DOWNLOAD_TAG)
            .addTag(fileIdTag(item.id))
            .build()
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
        request.id
    }

    fun observeAll(): Flow<List<WorkInfo>> =
        workManager.getWorkInfosByTagLiveData(FILE_DOWNLOAD_TAG).asFlow()

    fun cancel(workId: UUID) {
        workManager.cancelWorkById(workId)
    }

    private fun uniqueWorkName(itemId: String): String =
        "icloud-file-download-${stableDigest(itemId).take(24)}"

    companion object {
        const val FILE_DOWNLOAD_TAG = "icloud-file-download"
        private const val FILE_ID_TAG_PREFIX = "icloud-file-id:"

        fun itemId(workInfo: WorkInfo): String? = workInfo.tags
            .firstOrNull { it.startsWith(FILE_ID_TAG_PREFIX) }
            ?.removePrefix(FILE_ID_TAG_PREFIX)
            ?.let(::decodeItemId)

        internal fun fileIdTag(itemId: String): String = FILE_ID_TAG_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(itemId.toByteArray(Charsets.UTF_8))

        internal fun decodeItemId(value: String): String? = runCatching {
            String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        }.getOrNull()?.takeIf(String::isNotBlank)

        private fun stableDigest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

enum class FileDownloadStage { QUEUED, DOWNLOADING, RETRYING, COMPLETE, FAILED }

object FileDownloadProgressKeys {
    const val STAGE = "file_download_stage"
    const val FILE_NAME = "file_download_name"
    const val BYTES = "file_download_bytes"
    const val TOTAL_BYTES = "file_download_total_bytes"
    const val RESUMED_BYTES = "file_download_resumed_bytes"
    const val ERROR = "file_download_error"
}

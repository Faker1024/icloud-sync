package com.faker1024.icloudsync.core.sync

import android.content.Context
import android.net.Uri
import com.faker1024.icloudsync.core.database.SyncedFileMetadataDao
import com.faker1024.icloudsync.core.database.SyncedFileMetadataEntity
import com.faker1024.icloudsync.core.icloud.ICloudDownloadSource
import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
import com.faker1024.icloudsync.core.local.addStablePrivateFileSuffix
import com.faker1024.icloudsync.core.local.privateDriveFile
import com.faker1024.icloudsync.core.local.privateDriveUri
import com.faker1024.icloudsync.core.web.sanitizeCloudFileName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DownloadStoreResult(
    val uri: Uri,
    val bytes: Long,
    val skipped: Boolean,
)

@Singleton
class ICloudDownloadStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val metadataDao: SyncedFileMetadataDao,
) {
    internal suspend fun save(
        item: ICloudDriveItem,
        directories: List<String>,
        sourceProvider: () -> ICloudDownloadSource,
    ): DownloadStoreResult = withContext(Dispatchers.IO) {
        val relativePath = buildDownloadRelativePath(directories)
        val requestedName = sanitizeCloudFileName(item.name)
        val target = resolvePrivateDestination(item, directories, requestedName)
        val displayName = target.name
        val remoteModifiedAtSeconds = parseCloudModifiedAtSeconds(item.modifiedAt)
        val itemExpectedSize = item.size.takeIf { it >= 0L }
        if (canReusePrivateFile(target, item, itemExpectedSize)) {
            preserveRemoteModifiedTimeBestEffort(target, remoteModifiedAtSeconds)
            recordMetadata(
                item = item,
                file = target,
                displayName = displayName,
                relativePath = relativePath,
                remoteModifiedAtSeconds = remoteModifiedAtSeconds,
            )
            return@withContext DownloadStoreResult(
                privateDriveUri(context, target),
                target.length(),
                skipped = true,
            )
        }

        sourceProvider().use { source ->
            val expectedSize = source.contentLength.takeIf { it >= 0L } ?: itemExpectedSize
            if (canReusePrivateFile(target, item, expectedSize)) {
                preserveRemoteModifiedTimeBestEffort(target, remoteModifiedAtSeconds)
                recordMetadata(
                    item = item,
                    file = target,
                    displayName = displayName,
                    relativePath = relativePath,
                    remoteModifiedAtSeconds = remoteModifiedAtSeconds,
                )
                return@withContext DownloadStoreResult(
                    privateDriveUri(context, target),
                    target.length(),
                    skipped = true,
                )
            }

            target.parentFile?.let { parent ->
                check(parent.isDirectory || parent.mkdirs()) { "Android 无法创建 iCloud 私密目录" }
            }
            val temporary = File.createTempFile(".icloud-", ".part", target.parentFile)
            var copied = 0L
            try {
                val output = FileOutputStream(temporary)
                output.buffered().use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val input = source.inputStream
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        stream.write(buffer, 0, count)
                        copied += count
                    }
                    stream.flush()
                    output.fd.sync()
                }
                if (expectedSize != null && copied != expectedSize) {
                    error("文件下载不完整：预期 $expectedSize 字节，实际 $copied 字节")
                }
                replacePrivateFile(temporary, target)
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
            preserveRemoteModifiedTimeBestEffort(target, remoteModifiedAtSeconds)
            recordMetadata(
                item = item,
                file = target,
                displayName = displayName,
                relativePath = relativePath,
                remoteModifiedAtSeconds = remoteModifiedAtSeconds,
            )
            DownloadStoreResult(privateDriveUri(context, target), copied, skipped = false)
        }
    }

    private suspend fun resolvePrivateDestination(
        item: ICloudDriveItem,
        directories: List<String>,
        requestedName: String,
    ): File {
        val exact = privateDriveFile(context, directories, requestedName)
        if (canUsePrivateDestination(exact, item)) return exact
        val candidate = privateDriveFile(
            context,
            directories,
            addStablePrivateFileSuffix(requestedName, item.id),
        )
        if (canUsePrivateDestination(candidate, item)) return candidate
        return privateDriveFile(
            context,
            directories,
            addStablePrivateFileSuffix(requestedName, "${item.id}#fallback"),
        )
    }

    private suspend fun canUsePrivateDestination(file: File, item: ICloudDriveItem): Boolean {
        if (!file.exists()) return true
        val uri = privateDriveUri(context, file).toString()
        val metadata = metadataDao.get(uri)
        if (metadata?.remoteItemId == item.id) return true
        return metadata == null && item.size >= 0L && file.length() == item.size
    }

    private suspend fun canReusePrivateFile(
        file: File,
        item: ICloudDriveItem,
        expectedSize: Long?,
    ): Boolean {
        if (!file.isFile || expectedSize == null || file.length() != expectedSize) return false
        val metadata = metadataDao.get(privateDriveUri(context, file).toString())
        return metadata == null || metadata.remoteItemId == item.id
    }

    private suspend fun recordMetadata(
        item: ICloudDriveItem,
        file: File,
        displayName: String,
        relativePath: String,
        remoteModifiedAtSeconds: Long?,
    ) {
        val uri = privateDriveUri(context, file).toString()
        val modifiedAtMillis = remoteModifiedAtSeconds
            ?.times(MILLIS_PER_SECOND)
            ?: file.lastModified().coerceAtLeast(0L)
        metadataDao.upsert(
            SyncedFileMetadataEntity(
                contentUri = uri,
                remoteItemId = item.id,
                displayName = displayName,
                relativePath = relativePath,
                size = file.length().coerceAtLeast(0L),
                remoteModifiedAtMillis = modifiedAtMillis,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun preserveRemoteModifiedTimeBestEffort(file: File, remoteSeconds: Long?) {
        val desiredSeconds = remoteSeconds ?: return
        try {
            file.setLastModified(desiredSeconds * MILLIS_PER_SECOND)
        } catch (_: Exception) {
            // The Room metadata remains the source of truth shown inside the app.
        }
    }

    private fun replacePrivateFile(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal fun parseCloudModifiedAtSeconds(value: String?): Long? {
    val timestamp = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val numeric = timestamp.toLongOrNull()
    val seconds = if (numeric != null) {
        if (numeric >= MILLIS_TIMESTAMP_THRESHOLD) numeric / MILLIS_PER_SECOND else numeric
    } else {
        val instant = runCatching { Instant.parse(timestamp) }
            .recoverCatching {
                OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            }
            .getOrNull()
            ?: return null
        instant.epochSecond
    }
    return seconds.takeIf { it in 0L..MAX_FILE_TIMESTAMP_SECONDS }
}

internal fun modifiedTimeMatches(actualSeconds: Long, expectedSeconds: Long): Boolean {
    val difference = if (actualSeconds >= expectedSeconds) {
        actualSeconds - expectedSeconds
    } else {
        expectedSeconds - actualSeconds
    }
    return difference <= FILE_TIME_TOLERANCE_SECONDS
}

fun buildDownloadRelativePath(directories: List<String>): String {
    val nested = directories
        .map(::sanitizeCloudDirectoryName)
        .filter(String::isNotBlank)
        .joinToString("/")
    return buildString {
        append(PUBLIC_DOWNLOAD_DIRECTORY)
        append("/iCloud Drive/")
        if (nested.isNotBlank()) append(nested).append('/')
    }
}

internal fun sanitizeCloudDirectoryName(value: String): String = sanitizeCloudFileName(value)
    .take(MAX_DIRECTORY_NAME_LENGTH)
    .ifBlank { "iCloud-folder" }

private const val MAX_DIRECTORY_NAME_LENGTH = 80
private const val PUBLIC_DOWNLOAD_DIRECTORY = "Download"
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_TIMESTAMP_THRESHOLD = 10_000_000_000L
private const val MAX_FILE_TIMESTAMP_SECONDS = Long.MAX_VALUE / MILLIS_PER_SECOND
private const val FILE_TIME_TOLERANCE_SECONDS = 2L

package com.faker1024.icloudsync.core.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import com.faker1024.icloudsync.core.database.SyncedFileMetadataDao
import com.faker1024.icloudsync.core.database.SyncedFileMetadataEntity
import com.faker1024.icloudsync.core.icloud.ICloudDownloadSource
import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
import com.faker1024.icloudsync.core.web.sanitizeCloudFileName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
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
        fallbackMimeType: String,
        sourceProvider: () -> ICloudDownloadSource,
    ): DownloadStoreResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = buildDownloadRelativePath(directories)
        val target = resolveDestination(
            requested = sanitizeCloudFileName(item.name),
            relativePath = relativePath,
            itemId = item.id,
        )
        val displayName = target.displayName
        val remoteModifiedAtSeconds = parseCloudModifiedAtSeconds(item.modifiedAt)
        val existing = target.existing
        val itemExpectedSize = item.size.takeIf { it >= 0L }
        if (existing != null && existing.isOwnedByApp && !existing.pending &&
            itemExpectedSize != null && existing.size == itemExpectedSize
        ) {
            recordMetadata(
                item = item,
                uri = existing.uri,
                displayName = displayName,
                relativePath = relativePath,
                size = existing.size,
                remoteModifiedAtSeconds = remoteModifiedAtSeconds,
            )
            preserveRemoteModifiedTimeBestEffort(existing.uri, remoteModifiedAtSeconds)
            return@withContext DownloadStoreResult(existing.uri, existing.size, skipped = true)
        }

        sourceProvider().use { source ->
            val expectedSize = source.contentLength.takeIf { it >= 0L } ?: itemExpectedSize
            if (existing != null && existing.isOwnedByApp && !existing.pending &&
                expectedSize != null && existing.size == expectedSize
            ) {
                recordMetadata(
                    item = item,
                    uri = existing.uri,
                    displayName = displayName,
                    relativePath = relativePath,
                    size = existing.size,
                    remoteModifiedAtSeconds = remoteModifiedAtSeconds,
                )
                preserveRemoteModifiedTimeBestEffort(existing.uri, remoteModifiedAtSeconds)
                return@withContext DownloadStoreResult(existing.uri, existing.size, skipped = true)
            }
            if (existing != null && existing.isOwnedByApp) {
                resolver.delete(existing.uri, null, null)
                deleteMetadataBestEffort(existing.uri)
            }

            val mimeType = source.mimeType ?: fallbackMimeType
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val destination = resolver.insert(collection, values)
                ?: error("Android 无法在下载目录创建文件")
            var copied = 0L
            try {
                val output = resolver.openOutputStream(destination, "w")
                    ?: error("Android 无法写入下载文件")
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
                }
                if (expectedSize != null && copied != expectedSize) {
                    error("文件下载不完整：预期 $expectedSize 字节，实际 $copied 字节")
                }
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(destination, published, null, null) == 1) {
                    "Android 无法发布下载文件"
                }
            } catch (error: Throwable) {
                resolver.delete(destination, null, null)
                throw error
            }
            recordMetadata(
                item = item,
                uri = destination,
                displayName = displayName,
                relativePath = relativePath,
                size = copied,
                remoteModifiedAtSeconds = remoteModifiedAtSeconds,
            )
            preserveRemoteModifiedTimeBestEffort(destination, remoteModifiedAtSeconds)
            DownloadStoreResult(destination, copied, skipped = false)
        }
    }

    private fun resolveDestination(
        requested: String,
        relativePath: String,
        itemId: String,
    ): ResolvedDestination {
        val exact = findExisting(requested, relativePath)
        if (exact == null || exact.isOwnedByApp) return ResolvedDestination(requested, exact)
        val suffix = ownershipHash(itemId).take(12)
        val candidate = addFileNameSuffix(requested, " (iCloud-$suffix)")
        val conflict = findExisting(candidate, relativePath)
        if (conflict == null || conflict.isOwnedByApp) {
            return ResolvedDestination(candidate, conflict)
        }
        val fallback = addFileNameSuffix(requested, " (iCloud-${ownershipHash(itemId).take(20)})")
        return ResolvedDestination(fallback, findExisting(fallback, relativePath))
    }

    private fun findExisting(displayName: String, relativePath: String): ExistingDownload? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.IS_PENDING,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        return context.contentResolver.query(
            collection,
            projection,
            selection,
            arrayOf(displayName, relativePath),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(0)
            ExistingDownload(
                uri = ContentUris.withAppendedId(collection, id),
                size = cursor.getLong(1),
                isOwnedByApp = cursor.getString(2) == context.packageName,
                pending = cursor.getInt(3) != 0,
            )
        }
    }

    private suspend fun recordMetadata(
        item: ICloudDriveItem,
        uri: Uri,
        displayName: String,
        relativePath: String,
        size: Long,
        remoteModifiedAtSeconds: Long?,
    ) {
        val modifiedAtSeconds = remoteModifiedAtSeconds ?: return
        metadataDao.upsert(
            SyncedFileMetadataEntity(
                contentUri = uri.toString(),
                remoteItemId = item.id,
                displayName = displayName,
                relativePath = relativePath,
                size = size,
                remoteModifiedAtMillis = modifiedAtSeconds * MILLIS_PER_SECOND,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun deleteMetadataBestEffort(uri: Uri) {
        try {
            metadataDao.delete(uri.toString())
        } catch (_: Exception) {
            // Stale metadata cannot affect a replacement because its content URI is different.
        }
    }

    private fun preserveRemoteModifiedTimeBestEffort(uri: Uri, remoteSeconds: Long?) {
        val desiredSeconds = remoteSeconds ?: return
        try {
            setFilesystemModifiedTime(resolveFilesystemFile(uri), desiredSeconds)
        } catch (_: Exception) {
            // Scoped storage and some vendor MediaProviders reject filesystem timestamp changes.
        }
        try {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.DATE_MODIFIED, desiredSeconds) },
                null,
                null,
            )
        } catch (_: Exception) {
            // DATE_MODIFIED is a read-only index on many Android versions.
        }
    }

    private fun setFilesystemModifiedTime(file: File, desiredSeconds: Long) {
        val desiredMillis = desiredSeconds * MILLIS_PER_SECOND
        check(file.setLastModified(desiredMillis) ||
            modifiedTimeMatches(file.lastModified() / MILLIS_PER_SECOND, desiredSeconds)
        ) {
            "Android 无法写入 iCloud 文件修改时间"
        }
    }

    private fun resolveFilesystemFile(uri: Uri): File {
        val path = context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
            runCatching { Os.readlink("/proc/self/fd/${descriptor.fd}") }.getOrNull()
        }?.takeIf { it.startsWith('/') }
            ?: error("Android 无法定位同步文件，未能保留 iCloud 修改时间")
        return File(path)
    }

    private fun ownershipHash(itemId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(itemId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class ExistingDownload(
        val uri: Uri,
        val size: Long,
        val isOwnedByApp: Boolean,
        val pending: Boolean,
    )

    private data class ResolvedDestination(
        val displayName: String,
        val existing: ExistingDownload?,
    )
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

private fun addFileNameSuffix(fileName: String, suffix: String): String {
    val dot = fileName.lastIndexOf('.').takeIf { it > 0 }
    val base = dot?.let { fileName.substring(0, it) } ?: fileName
    val extension = dot?.let { fileName.substring(it) }.orEmpty()
    return sanitizeCloudFileName("$base$suffix$extension")
}

private const val MAX_DIRECTORY_NAME_LENGTH = 80
private const val PUBLIC_DOWNLOAD_DIRECTORY = "Download"
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_TIMESTAMP_THRESHOLD = 10_000_000_000L
private const val MAX_FILE_TIMESTAMP_SECONDS = Long.MAX_VALUE / MILLIS_PER_SECOND
private const val FILE_TIME_TOLERANCE_SECONDS = 2L

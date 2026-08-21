package com.faker1024.icloudsync.core.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
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
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

data class DownloadStoreResult(
    val uri: Uri,
    val bytes: Long,
    val skipped: Boolean,
)

@Singleton
class ICloudDownloadStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    internal suspend fun save(
        item: ICloudDriveItem,
        directories: List<String>,
        source: ICloudDownloadSource,
        mimeType: String,
    ): DownloadStoreResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = buildDownloadRelativePath(directories)
        val expectedSize = source.contentLength.takeIf { it >= 0L }
            ?: item.size.takeIf { it >= 0L }
        val displayName = resolveDisplayName(
            requested = sanitizeCloudFileName(item.name),
            relativePath = relativePath,
            itemId = item.id,
        )
        val remoteModifiedAtSeconds = parseCloudModifiedAtSeconds(item.modifiedAt)
        val existing = findExisting(displayName, relativePath)
        if (existing != null && existing.isOwnedByApp && !existing.pending &&
            expectedSize != null && existing.size == expectedSize
        ) {
            preserveRemoteModifiedTime(
                uri = existing.uri,
                indexedSeconds = existing.modifiedAtSeconds,
                remoteSeconds = remoteModifiedAtSeconds,
                mimeType = mimeType,
            )
            return@withContext DownloadStoreResult(existing.uri, existing.size, skipped = true)
        }
        if (existing != null && existing.isOwnedByApp) {
            resolver.delete(existing.uri, null, null)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(collection, values)
            ?: error("Android 无法在下载目录创建文件")
        try {
            val output = resolver.openOutputStream(destination, "w")
                ?: error("Android 无法写入下载文件")
            var copied = 0L
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
            remoteModifiedAtSeconds?.let { setFilesystemModifiedTime(destination, it) }
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(destination, published, null, null) == 1) {
                "Android 无法发布下载文件"
            }
            preserveRemoteModifiedTime(
                uri = destination,
                indexedSeconds = readIndexedModifiedTime(destination),
                remoteSeconds = remoteModifiedAtSeconds,
                mimeType = mimeType,
            )
            DownloadStoreResult(destination, copied, skipped = false)
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    private fun resolveDisplayName(
        requested: String,
        relativePath: String,
        itemId: String,
    ): String {
        val exact = findExisting(requested, relativePath)
        if (exact == null || exact.isOwnedByApp) return requested
        val suffix = ownershipHash(itemId).take(12)
        val candidate = addFileNameSuffix(requested, " (iCloud-$suffix)")
        val conflict = findExisting(candidate, relativePath)
        if (conflict == null || conflict.isOwnedByApp) {
            return candidate
        }
        return addFileNameSuffix(requested, " (iCloud-${ownershipHash(itemId).take(20)})")
    }

    private fun findExisting(displayName: String, relativePath: String): ExistingDownload? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.DATE_MODIFIED,
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
                modifiedAtSeconds = cursor.getLong(4).coerceAtLeast(0L),
            )
        }
    }

    private suspend fun preserveRemoteModifiedTime(
        uri: Uri,
        indexedSeconds: Long,
        remoteSeconds: Long?,
        mimeType: String,
    ) {
        val desiredSeconds = remoteSeconds ?: return
        val resolver = context.contentResolver
        val file = resolveFilesystemFile(uri)
        val filesystemMatches =
            modifiedTimeMatches(file.lastModified() / MILLIS_PER_SECOND, desiredSeconds)
        if (!filesystemMatches) setFilesystemModifiedTime(file, desiredSeconds)
        if (filesystemMatches && modifiedTimeMatches(indexedSeconds, desiredSeconds)) return

        // Some MediaProvider versions accept this owner-only update even though the column is
        // documented as read-only. The filesystem timestamp above remains the source of truth.
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.DATE_MODIFIED, desiredSeconds) },
                null,
                null,
            )
        }
        if (!modifiedTimeMatches(readIndexedModifiedTime(uri), desiredSeconds)) {
            withTimeout(MEDIA_SCAN_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf(mimeType),
                    ) { _, _ ->
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }
        check(modifiedTimeMatches(readIndexedModifiedTime(uri), desiredSeconds)) {
            "Android 未能刷新 iCloud 文件修改时间"
        }
        check(modifiedTimeMatches(file.lastModified() / MILLIS_PER_SECOND, desiredSeconds)) {
            "Android 未能保留 iCloud 文件修改时间"
        }
    }

    private fun setFilesystemModifiedTime(uri: Uri, desiredSeconds: Long) {
        setFilesystemModifiedTime(resolveFilesystemFile(uri), desiredSeconds)
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

    private fun readIndexedModifiedTime(uri: Uri): Long = context.contentResolver.query(
        uri,
        arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
    } ?: 0L

    private fun ownershipHash(itemId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(itemId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class ExistingDownload(
        val uri: Uri,
        val size: Long,
        val isOwnedByApp: Boolean,
        val pending: Boolean,
        val modifiedAtSeconds: Long,
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
private const val MEDIA_SCAN_TIMEOUT_MILLIS = 15_000L

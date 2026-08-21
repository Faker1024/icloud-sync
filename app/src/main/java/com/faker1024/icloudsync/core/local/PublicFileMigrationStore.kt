package com.faker1024.icloudsync.core.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.faker1024.icloudsync.core.database.SyncedFileMetadataDao
import com.faker1024.icloudsync.core.database.SyncedFileMetadataEntity
import com.faker1024.icloudsync.core.sync.buildDownloadRelativePath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PublicFileMigrationSummary(
    val fileCount: Int,
    val totalBytes: Long,
)

data class PublicFileMigrationItem(
    val contentUri: String,
    val displayName: String,
    val size: Long,
    val modifiedAtMillis: Long,
    val relativePath: String,
    val directories: List<String>,
)

@Singleton
class PublicFileMigrationStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val metadataDao: SyncedFileMetadataDao,
) {
    suspend fun summary(): PublicFileMigrationSummary = withContext(Dispatchers.IO) {
        val files = listMigratableFiles()
        PublicFileMigrationSummary(
            fileCount = files.size,
            totalBytes = files.fold(0L) { total, file -> safeAdd(total, file.size) },
        )
    }

    suspend fun listMigratableFiles(): List<PublicFileMigrationItem> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) AND " +
            "${MediaStore.MediaColumns.IS_PENDING} = 0"
        val arguments = arrayOf(SYNCED_FILES_PUBLIC_PATH, "$SYNCED_FILES_PUBLIC_PATH%")
        buildList {
            resolver.query(
                collection,
                projection,
                selection,
                arguments,
                "${MediaStore.MediaColumns.DATE_ADDED} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val relativePath = cursor.getString(4) ?: continue
                    val directories = parseSyncedDirectories(relativePath) ?: continue
                    val id = cursor.getLong(0)
                    add(
                        PublicFileMigrationItem(
                            contentUri = ContentUris.withAppendedId(collection, id).toString(),
                            displayName = cursor.getString(1)?.takeIf(String::isNotBlank)
                                ?: "未命名文件",
                            size = cursor.getLong(2).coerceAtLeast(0L),
                            modifiedAtMillis = cursor.getLong(3).coerceAtLeast(0L) * MILLIS_PER_SECOND,
                            relativePath = relativePath,
                            directories = directories,
                        ),
                    )
                }
            }
        }
    }

    suspend fun migrate(item: PublicFileMigrationItem): Long = withContext(Dispatchers.IO) {
        val sourceUri = item.contentUri.toUri()
        val sourceMetadata = metadataDao.get(item.contentUri)
        val stableId = sourceMetadata?.remoteItemId ?: "legacy:${item.contentUri}"
        val target = resolveTarget(item, stableId, sourceUri)
        val targetUri = privateDriveUri(context, target).toString()

        if (!sameContent(sourceUri, target)) {
            copyAndVerify(sourceUri, target, item.size)
        }
        val modifiedAtMillis = sourceMetadata?.remoteModifiedAtMillis
            ?.takeIf { it > 0L }
            ?: item.modifiedAtMillis.takeIf { it > 0L }
            ?: target.lastModified().coerceAtLeast(0L)
        if (modifiedAtMillis > 0L) runCatching { target.setLastModified(modifiedAtMillis) }
        metadataDao.upsert(
            SyncedFileMetadataEntity(
                contentUri = targetUri,
                remoteItemId = stableId,
                displayName = target.name,
                relativePath = buildDownloadRelativePath(item.directories),
                size = target.length().coerceAtLeast(0L),
                remoteModifiedAtMillis = modifiedAtMillis,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        check(context.contentResolver.delete(sourceUri, null, null) == 1) {
            "Android 未允许删除公共副本"
        }
        runCatching { metadataDao.delete(item.contentUri) }
        target.length().coerceAtLeast(0L)
    }

    private suspend fun resolveTarget(
        item: PublicFileMigrationItem,
        stableId: String,
        sourceUri: Uri,
    ): File {
        val exact = privateDriveFile(context, item.directories, item.displayName)
        if (!exact.exists() || sameContent(sourceUri, exact)) return exact
        val candidate = privateDriveFile(
            context,
            item.directories,
            addStablePrivateFileSuffix(item.displayName, stableId),
        )
        if (!candidate.exists() || sameContent(sourceUri, candidate)) return candidate
        return privateDriveFile(
            context,
            item.directories,
            addStablePrivateFileSuffix(item.displayName, "$stableId#fallback"),
        )
    }

    private fun sameContent(sourceUri: Uri, target: File): Boolean {
        if (!target.isFile) return false
        val sourceLength = context.contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use {
            it.length
        } ?: -1L
        if (sourceLength >= 0L && sourceLength != target.length()) return false
        val sourceDigest = context.contentResolver.openInputStream(sourceUri)?.buffered()?.use(::sha256)
            ?: return false
        val targetDigest = FileInputStream(target).buffered().use(::sha256)
        return sourceDigest.contentEquals(targetDigest)
    }

    private suspend fun copyAndVerify(sourceUri: Uri, target: File, expectedSize: Long) {
        target.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Android 无法创建 iCloud 私密目录" }
        }
        val temporary = File.createTempFile(".migration-", ".part", target.parentFile)
        try {
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: error("Android 无法读取公共文件")
            input.buffered().use { source ->
                val output = FileOutputStream(temporary)
                output.buffered().use { destination ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                        sourceDigest.update(buffer, 0, count)
                        copied += count
                    }
                    destination.flush()
                    output.fd.sync()
                }
            }
            if (expectedSize > 0L) check(copied == expectedSize) {
                "公共文件读取不完整：预期 $expectedSize 字节，实际 $copied 字节"
            }
            val destinationDigest = FileInputStream(temporary).buffered().use(::sha256)
            check(sourceDigest.digest().contentEquals(destinationDigest)) { "私密副本校验失败" }
            replaceFile(temporary, target)
        } finally {
            temporary.delete()
        }
    }
}

internal fun sha256(input: java.io.InputStream): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest()
}

private fun replaceFile(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun safeAdd(left: Long, right: Long): Long =
    if (right > 0L && Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right.coerceAtLeast(0L)

private const val MILLIS_PER_SECOND = 1_000L
private const val COPY_BUFFER_BYTES = 256 * 1_024

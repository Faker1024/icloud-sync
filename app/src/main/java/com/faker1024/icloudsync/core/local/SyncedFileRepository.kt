package com.faker1024.icloudsync.core.local

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.net.toUri
import com.faker1024.icloudsync.core.database.SyncedFileMetadataDao
import com.faker1024.icloudsync.core.database.SyncedFileMetadataEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SyncedFileRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val metadataDao: SyncedFileMetadataDao,
) {
    private val bitmapCache = object : LruCache<String, Bitmap>(BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun listFiles(): List<SyncedFile> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val metadataByUri = metadataDao.listAll().associateBy(SyncedFileMetadataEntity::contentUri)
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
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
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val relativePath = cursor.getString(pathIndex) ?: continue
                    val directories = parseSyncedDirectories(relativePath) ?: continue
                    val id = cursor.getLong(idIndex)
                    val contentUri = ContentUris.withAppendedId(collection, id).toString()
                    val displayName = cursor.getString(nameIndex)?.takeIf(String::isNotBlank)
                        ?: "未命名文件"
                    val size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    val indexedModifiedAtMillis =
                        cursor.getLong(modifiedIndex).coerceAtLeast(0L) * MILLIS_PER_SECOND
                    add(
                        SyncedFile(
                            id = id,
                            contentUri = contentUri,
                            displayName = displayName,
                            mimeType = cursor.getString(mimeIndex),
                            size = size,
                            modifiedAtMillis = resolveSyncedModifiedAtMillis(
                                indexedModifiedAtMillis = indexedModifiedAtMillis,
                                metadata = metadataByUri[contentUri],
                                contentUri = contentUri,
                                displayName = displayName,
                                relativePath = relativePath,
                                size = size,
                            ),
                            directories = directories,
                        ),
                    )
                }
            }
        }
    }

    suspend fun loadImage(file: SyncedFile, targetPixels: Int): Bitmap = withContext(Dispatchers.IO) {
        require(file.isImage) { "该文件不是可预览图片" }
        val safeTarget = targetPixels.coerceIn(MIN_IMAGE_TARGET, MAX_IMAGE_TARGET)
        val cacheKey = "${file.contentUri}:${file.modifiedAtMillis}:$safeTarget"
        bitmapCache.get(cacheKey)?.let { return@withContext it }
        val source = ImageDecoder.createSource(context.contentResolver, file.contentUri.toUri())
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longestEdge = max(info.size.width, info.size.height).coerceAtLeast(1)
            if (longestEdge > safeTarget) {
                val scale = safeTarget.toFloat() / longestEdge
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
        bitmapCache.put(cacheKey, bitmap)
        bitmap
    }

    fun openExternally(file: SyncedFile): Result<Unit> = runCatching {
        val uri = file.contentUri.toUri()
        val type = resolveMimeType(file)
        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newUri(context.contentResolver, file.displayName, uri) }
        val chooser = Intent.createChooser(viewIntent, "打开 ${file.displayName}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }

    fun share(file: SyncedFile): Result<Unit> = runCatching {
        val uri = file.contentUri.toUri()
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType(resolveMimeType(file))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newUri(context.contentResolver, file.displayName, uri) }
        val chooser = Intent.createChooser(sendIntent, "分享 ${file.displayName}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }

    private fun resolveMimeType(file: SyncedFile): String {
        val uri = file.contentUri.toUri()
        return file.mimeType?.takeIf(String::isNotBlank)
            ?: context.contentResolver.getType(uri)
            ?: "application/octet-stream"
    }

    companion object {
        private const val BITMAP_CACHE_BYTES = 64 * 1024 * 1024
        private const val MIN_IMAGE_TARGET = 96
        private const val MAX_IMAGE_TARGET = 4096
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

internal fun resolveSyncedModifiedAtMillis(
    indexedModifiedAtMillis: Long,
    metadata: SyncedFileMetadataEntity?,
    contentUri: String,
    displayName: String,
    relativePath: String,
    size: Long,
): Long = metadata
    ?.takeIf {
        it.contentUri == contentUri &&
            it.displayName == displayName &&
            normalizeMetadataPath(it.relativePath) == normalizeMetadataPath(relativePath) &&
            it.size == size &&
            it.remoteModifiedAtMillis > 0L
    }
    ?.remoteModifiedAtMillis
    ?: indexedModifiedAtMillis

private fun normalizeMetadataPath(value: String): String =
    value.replace('\\', '/').trim('/')

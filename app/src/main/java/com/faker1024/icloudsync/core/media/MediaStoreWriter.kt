package com.faker1024.icloudsync.core.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.faker1024.icloudsync.core.database.ImportedMediaDao
import com.faker1024.icloudsync.domain.model.ImportErrorCode
import com.faker1024.icloudsync.domain.model.ImportException
import com.faker1024.icloudsync.domain.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Singleton
class MediaStoreWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mediaDao: ImportedMediaDao,
) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun write(
        source: File,
        media: DetectedMedia,
        metadata: MediaMetadata,
        albumName: String,
    ): Pair<Uri, String> {
        val displayName = uniqueDisplayName(sanitizeDisplayName(media.originalName))
        val collection = when (media.kind) {
            MediaKind.IMAGE, MediaKind.RAW ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            MediaKind.VIDEO ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            MediaKind.UNKNOWN -> throw ImportException(
                ImportErrorCode.UNSUPPORTED_MEDIA,
                "无法识别媒体类型",
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$albumName/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            metadata.captureTime?.let { put(MediaStore.Images.ImageColumns.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(collection, values) ?: throw ImportException(
            ImportErrorCode.MEDIASTORE_WRITE_FAILED,
            "系统相册拒绝创建文件",
        )

        try {
            val coroutineContext = currentCoroutineContext()
            val bytesWritten = resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                    total
                }
            } ?: throw ImportException(
                ImportErrorCode.MEDIASTORE_WRITE_FAILED,
                "无法打开系统相册输出流",
            )
            if (bytesWritten != source.length()) {
                throw ImportException(
                    ImportErrorCode.MEDIASTORE_WRITE_FAILED,
                    "写入字节数与源文件不一致",
                )
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return uri to displayName
        } catch (exception: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            if (exception is ImportException) throw exception
            throw ImportException(
                ImportErrorCode.MEDIASTORE_WRITE_FAILED,
                "保存到系统相册失败",
                exception,
            )
        }
    }

    fun exists(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        return runCatching {
            resolver.openAssetFileDescriptor(uri.toUri(), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private suspend fun uniqueDisplayName(original: String): String {
        if (mediaDao.countImportedWithName(original) == 0) return original
        val extension = original.substringAfterLast('.', missingDelimiterValue = "")
        val base = if (extension.isBlank()) original else original.dropLast(extension.length + 1)
        var index = 1
        while (index < 10_000) {
            val candidate = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
            if (mediaDao.countImportedWithName(candidate) == 0) return candidate
            index++
        }
        return "${base}_${System.currentTimeMillis()}${if (extension.isBlank()) "" else ".$extension"}"
    }

    private fun sanitizeDisplayName(value: String): String {
        val name = value.replace('\\', '/').substringAfterLast('/')
            .replace(Regex("[\\p{Cntrl}/]"), "_")
            .trim()
            .take(180)
        return name.ifBlank { "icloud_${System.currentTimeMillis()}.bin" }
    }
}

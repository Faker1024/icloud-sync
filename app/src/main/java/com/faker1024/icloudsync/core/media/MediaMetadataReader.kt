package com.faker1024.icloudsync.core.media

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.faker1024.icloudsync.domain.model.MediaKind
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object MediaMetadataReader {
    fun read(file: File, media: DetectedMedia, fallbackTime: Long?): MediaMetadata = when (media.kind) {
        MediaKind.IMAGE, MediaKind.RAW -> readImage(file, media.originalName, fallbackTime)
        MediaKind.VIDEO -> readVideo(file, media.originalName, fallbackTime)
        MediaKind.UNKNOWN -> MediaMetadata(fallbackTime, null, null, null, null)
    }

    private fun readImage(file: File, originalName: String, fallbackTime: Long?): MediaMetadata {
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        val captureTime = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?.let(::parseExifDate)
            ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
                ?.let(::parseExifDate)
            ?: fallbackTime
        return MediaMetadata(
            captureTime = captureTime,
            width = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)?.takeIf { it > 0 },
            height = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)?.takeIf { it > 0 },
            durationMs = null,
            livePhotoGroupKey = livePhotoGroupKey(originalName),
        )
    }

    private fun readVideo(file: File, originalName: String, fallbackTime: Long?): MediaMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            MediaMetadata(
                captureTime = fallbackTime,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull(),
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull(),
                livePhotoGroupKey = livePhotoGroupKey(originalName),
            )
        } catch (_: RuntimeException) {
            MediaMetadata(fallbackTime, null, null, null, livePhotoGroupKey(originalName))
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun parseExifDate(value: String): Long? = runCatching {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            isLenient = false
        }.parse(value)?.time
    }.getOrNull()

    private fun livePhotoGroupKey(name: String): String? {
        val base = name.substringAfterLast('/').substringBeforeLast('.', missingDelimiterValue = "")
        return base.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
    }
}

package com.faker1024.icloudsync.core.icloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class ICloudPreviewLoader @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ICloudApiClient,
) {
    private val downloadMutex = Mutex()
    private val memoryCache = object : LruCache<String, Bitmap>(memoryCacheKilobytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1024)
    }

    suspend fun load(item: ICloudDriveItem, targetPixels: Int): Bitmap = withContext(Dispatchers.IO) {
        require(isPreviewableImage(item.name)) { "此文件不是可预览的图片" }
        val boundedTarget = targetPixels.coerceIn(MIN_PREVIEW_PIXELS, MAX_PREVIEW_PIXELS)
        val fileKey = cacheKey(item)
        val bitmapKey = "$fileKey@$boundedTarget"
        memoryCache.get(bitmapKey)?.let { return@withContext it }

        val destination = ensurePreviewFile(item, fileKey)
        val oriented = downloadMutex.withLock {
            val decoded = decodeSampled(destination, boundedTarget)
                ?: throw ICloudApiException(ICloudError.INVALID_RESPONSE, "Android 无法解码这张图片")
            applyExifRotation(decoded, destination)
        }
        memoryCache.put(bitmapKey, oriented)
        oriented
    }

    suspend fun prepareSource(item: ICloudDriveItem): File = withContext(Dispatchers.IO) {
        require(isPreviewableImage(item.name)) { "此文件不是可预览的图片" }
        ensurePreviewFile(item, cacheKey(item))
    }

    fun clear() {
        memoryCache.evictAll()
        previewDirectory().listFiles()?.forEach(File::delete)
    }

    private suspend fun ensurePreviewFile(item: ICloudDriveItem, fileKey: String): File =
        downloadMutex.withLock {
            previewDirectory().mkdirs()
            val destination = File(previewDirectory(), "$fileKey.bin")
            if (!destination.isFile || destination.length() <= 0L) {
                downloadPreview(item, destination)
                pruneDiskCache(destination)
            }
            destination.setLastModified(System.currentTimeMillis())
            destination
        }

    private fun downloadPreview(item: ICloudDriveItem, destination: File) {
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()
        try {
            api.openDownload(item).use { source ->
                var copied = 0L
                FileOutputStream(partial).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val input = source.inputStream
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                    }
                    output.flush()
                }
                val expected = source.contentLength.takeIf { it >= 0L }
                    ?: item.size.takeIf { it > 0L }
                if (expected != null && copied != expected) {
                    throw ICloudApiException(ICloudError.NETWORK, "图片预览下载不完整，将自动重试")
                }
            }
            check(partial.length() > 0L) { "图片预览内容为空" }
            if (destination.exists()) destination.delete()
            check(partial.renameTo(destination)) { "无法保存图片预览缓存" }
        } finally {
            partial.delete()
        }
    }

    private fun decodeSampled(file: File, targetPixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetPixels ||
            bounds.outHeight / (sampleSize * 2) >= targetPixels
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun applyExifRotation(bitmap: Bitmap, file: File): Bitmap {
        val degrees = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        if (degrees == 0) return bitmap
        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(degrees.toFloat()) },
            true,
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun previewDirectory(): File = File(context.cacheDir, "icloud-image-previews")

    private fun pruneDiskCache(protectedFile: File) {
        val files = previewDirectory().listFiles()?.filter(File::isFile).orEmpty()
        var total = files.sumOf(File::length)
        if (total <= MAX_DISK_CACHE_BYTES) return
        files.sortedBy(File::lastModified).forEach { file ->
            if (file != protectedFile && total > DISK_CACHE_TARGET_BYTES) {
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }

    private fun cacheKey(item: ICloudDriveItem): String {
        val input = "${item.id}\u0000${item.modifiedAt.orEmpty()}\u0000${item.size}"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun memoryCacheKilobytes(): Int {
        val available = Runtime.getRuntime().maxMemory() / 8L / 1024L
        return available.coerceIn(8L * 1024L, 48L * 1024L).toInt()
    }

    private companion object {
        const val MIN_PREVIEW_PIXELS = 64
        const val MAX_PREVIEW_PIXELS = 4096
        const val MAX_DISK_CACHE_BYTES = 384L * 1024L * 1024L
        const val DISK_CACHE_TARGET_BYTES = 288L * 1024L * 1024L
    }
}

fun isPreviewableImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "dng", "bmp", "tif", "tiff", "avif",
)

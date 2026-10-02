package com.faker1024.icloudsync.core.similarity

import android.content.Context
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import com.faker1024.icloudsync.core.local.SyncedFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

@Singleton
class ImageSimilarityRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val fingerprints = object : LinkedHashMap<String, ImageFingerprint>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageFingerprint>?): Boolean =
            size > MAX_CACHED_FINGERPRINTS
    }

    suspend fun scan(
        files: List<SyncedFile>,
        level: SimilarityLevel,
        onProgress: suspend (SimilarityScanProgress) -> Unit,
    ): SimilarityScanResult = withContext(Dispatchers.Default) {
        val images = files.filter(SyncedFile::isImage).distinctBy(SyncedFile::contentUri)
            .sortedBy(SyncedFile::contentUri)
        val grouper = SimilarityGrouper<SyncedFile>(level)
        var skipped = 0
        onProgress(SimilarityScanProgress(0, images.size, skipped))
        val scanContext = currentCoroutineContext()
        images.forEachIndexed { index, file ->
            scanContext.ensureActive()
            val fingerprint = if (file.size <= 0) null else try {
                fingerprint(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                // Includes unsupported formats, permission denial, and files removed during a scan.
                null
            }
            if (fingerprint == null) {
                skipped++
            } else {
                grouper.add(file, fingerprint) { scanContext.ensureActive() }
            }
            if ((index + 1) % PROGRESS_INTERVAL == 0 || index == images.lastIndex) {
                onProgress(SimilarityScanProgress(index + 1, images.size, skipped))
            }
            yield()
        }
        val groups = grouper.result().map { group ->
            scanContext.ensureActive()
            val members = group.members.map { it.first }.sortedWith(
                compareByDescending<SyncedFile> { it.size }.thenBy { it.contentUri },
            )
            SimilarImageGroup(
                id = UUID.nameUUIDFromBytes(members.map { it.contentUri }.sorted().joinToString("\u0000").toByteArray()).toString(),
                files = members,
                score = group.score,
            )
        }.sortedWith(compareByDescending<SimilarImageGroup> { it.score }.thenByDescending { it.files.size })
        SimilarityScanResult(groups, images.size - skipped, skipped)
    }

    private suspend fun fingerprint(file: SyncedFile): ImageFingerprint {
        val key = "${file.contentUri}\u0000${file.size}\u0000${file.modifiedAtMillis}\u0000${file.localModifiedAtMillis}"
        synchronized(fingerprints) { fingerprints[key] }?.let { return it }
        val sample = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            var aspectRatio = 1.0
            val source = ImageDecoder.createSource(context.contentResolver, file.contentUri.toUri())
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Identical first frames do not imply identical animations; exclude them from deletion suggestions.
                if (info.isAnimated) throw IOException("Animated images are excluded from similarity scanning")
                aspectRatio = info.size.width.toDouble() / info.size.height.coerceAtLeast(1)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
                val longest = max(info.size.width, info.size.height).coerceAtLeast(1)
                if (longest > MAX_DECODE_EDGE) {
                    val scale = MAX_DECODE_EDGE.toDouble() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                PixelSample(pixels, bitmap.width, bitmap.height, aspectRatio)
            } finally {
                bitmap.recycle()
            }
        }
        currentCoroutineContext().ensureActive()
        val fingerprint = PerceptualImageHash.fingerprint(sample.pixels, sample.width, sample.height, sample.aspectRatio)
        synchronized(fingerprints) { fingerprints[key] = fingerprint }
        return fingerprint
    }

    private data class PixelSample(val pixels: IntArray, val width: Int, val height: Int, val aspectRatio: Double)

    companion object {
        private const val MAX_DECODE_EDGE = 256
        private const val MAX_CACHED_FINGERPRINTS = 4_096
        private const val PROGRESS_INTERVAL = 8
    }
}

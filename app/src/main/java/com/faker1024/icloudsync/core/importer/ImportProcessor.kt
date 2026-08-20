package com.faker1024.icloudsync.core.importer

import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.faker1024.icloudsync.core.database.AppDatabase
import com.faker1024.icloudsync.core.database.ImportBatchDao
import com.faker1024.icloudsync.core.database.ImportedMediaDao
import com.faker1024.icloudsync.core.database.ImportedMediaEntity
import com.faker1024.icloudsync.core.files.Hashing
import com.faker1024.icloudsync.core.files.ZipSafetyValidator
import com.faker1024.icloudsync.core.media.MediaMetadataReader
import com.faker1024.icloudsync.core.media.MediaStoreWriter
import com.faker1024.icloudsync.core.media.MediaTypeDetector
import com.faker1024.icloudsync.core.settings.ImportSettings
import com.faker1024.icloudsync.domain.model.ImportBatchState
import com.faker1024.icloudsync.domain.model.ImportErrorCode
import com.faker1024.icloudsync.domain.model.ImportException
import com.faker1024.icloudsync.domain.model.ImportOutcome
import com.faker1024.icloudsync.domain.model.ImportedMediaState
import com.faker1024.icloudsync.domain.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Singleton
class ImportProcessor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val batchDao: ImportBatchDao,
    private val mediaDao: ImportedMediaDao,
    private val mediaStoreWriter: MediaStoreWriter,
    private val settings: ImportSettings,
) {
    suspend fun process(batchId: String) = withContext(Dispatchers.IO) {
        val initialBatch = batchDao.get(batchId) ?: return@withContext
        val batchDirectory = File(context.filesDir, "imports/$batchId")
        if (!batchDirectory.exists() && !batchDirectory.mkdirs()) {
            throw ImportException(ImportErrorCode.NO_SPACE, "无法创建导入暂存目录")
        }
        val sourceFile = File(batchDirectory, "source")
        try {
            stageSource(initialBatch.sourceUri, sourceFile, batchId, initialBatch.sourceSize)
            val isZip = MediaTypeDetector.looksLikeZip(
                sourceFile,
                initialBatch.sourceDisplayName,
                initialBatch.sourceMimeType,
            )
            if (isZip) {
                processZip(batchId, sourceFile, batchDirectory)
            } else {
                val name = initialBatch.sourceDisplayName ?: "icloud-media"
                batchDao.prepareImport(
                    id = batchId,
                    totalCount = 1,
                    totalBytes = sourceFile.length(),
                    state = ImportBatchState.IMPORTING.name,
                    updatedAt = now(),
                )
                processFile(
                    batchId = batchId,
                    file = sourceFile,
                    entryName = name,
                    fallbackTime = initialBatch.createdAt,
                    digest = null,
                )
            }
            finishBatch(batchId)
            batchDirectory.deleteRecursively()
        } finally {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    initialBatch.sourceUri.toUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    suspend fun failBatch(batchId: String, errorCode: ImportErrorCode) {
        val batch = batchDao.get(batchId)
        val now = now()
        batchDao.updateState(
            id = batchId,
            state = if (batch != null && batch.importedCount + batch.duplicateCount > 0) {
                ImportBatchState.PARTIAL_FAILED.name
            } else {
                ImportBatchState.FAILED.name
            },
            errorCode = errorCode.name,
            updatedAt = now,
            finishedAt = now,
        )
    }

    suspend fun cancelBatch(batchId: String) {
        val now = now()
        batchDao.updateState(
            id = batchId,
            state = ImportBatchState.CANCELLED.name,
            errorCode = ImportErrorCode.USER_CANCELLED.name,
            updatedAt = now,
            finishedAt = now,
        )
        File(context.filesDir, "imports/$batchId").deleteRecursively()
    }

    private suspend fun stageSource(
        sourceUri: String,
        destination: File,
        batchId: String,
        expectedSize: Long?,
    ) {
        ensureAvailableSpace(destination.parentFile ?: context.filesDir, expectedSize ?: MIN_FREE_BYTES)
        batchDao.updateStaging(
            id = batchId,
            stagedPath = destination.absolutePath,
            processedBytes = 0,
            totalBytes = expectedSize,
            state = ImportBatchState.STAGING.name,
            updatedAt = now(),
        )
        val input = context.contentResolver.openInputStream(sourceUri.toUri())
            ?: throw ImportException(ImportErrorCode.SOURCE_UNREADABLE, "无法读取所选文件")
        try {
            input.buffered().use { source ->
                FileOutputStream(destination).buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    var lastReported = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        total += read
                        if (total - lastReported >= PROGRESS_STEP_BYTES) {
                            ensureAvailableSpace(destination.parentFile ?: context.filesDir, MIN_FREE_BYTES)
                            batchDao.updateStaging(
                                id = batchId,
                                stagedPath = destination.absolutePath,
                                processedBytes = total,
                                totalBytes = expectedSize,
                                state = ImportBatchState.STAGING.name,
                                updatedAt = now(),
                            )
                            lastReported = total
                        }
                    }
                    output.flush()
                    if (expectedSize != null && expectedSize > 0 && total != expectedSize) {
                        throw ImportException(
                            ImportErrorCode.SOURCE_INCOMPLETE,
                            "文件大小与下载记录不一致",
                        )
                    }
                    batchDao.updateStaging(
                        id = batchId,
                        stagedPath = destination.absolutePath,
                        processedBytes = total,
                        totalBytes = total,
                        state = ImportBatchState.PREFLIGHT.name,
                        updatedAt = now(),
                    )
                }
            }
        } catch (exception: ImportException) {
            throw exception
        } catch (exception: IOException) {
            throw ImportException(ImportErrorCode.SOURCE_UNREADABLE, "暂存下载文件失败", exception)
        }
    }

    private suspend fun processZip(batchId: String, source: File, batchDirectory: File) {
        val zipFile = try {
            ZipFile(source)
        } catch (exception: ZipException) {
            throw ImportException(ImportErrorCode.SOURCE_INCOMPLETE, "ZIP 文件损坏或尚未下载完成", exception)
        }
        zipFile.use { archive ->
            val entries = archive.entries().asSequence()
                .filterNot(ZipEntry::isDirectory)
                .filterNot { ZipSafetyValidator.shouldIgnore(it.name) }
                .toList()
            if (entries.size > ZipSafetyValidator.MAX_ENTRIES) {
                throw ImportException(ImportErrorCode.TOO_MANY_ENTRIES, "ZIP 文件数量超过安全限制")
            }
            entries.firstOrNull { !ZipSafetyValidator.validateEntryName(it.name) }?.let {
                throw ImportException(
                    ImportErrorCode.UNSAFE_ARCHIVE_PATH,
                    "ZIP 包含不安全路径：${it.name.take(80)}",
                )
            }
            if (entries.isEmpty()) {
                throw ImportException(ImportErrorCode.UNSUPPORTED_ARCHIVE, "ZIP 中没有可导入文件")
            }
            val hasUnknownSize = entries.any { it.size < 0 }
            val declaredTotal = entries.filter { it.size >= 0 }.sumOf { it.size }
            if (!hasUnknownSize) ensureAvailableSpace(batchDirectory, declaredTotal.coerceAtMost(MAX_PREFLIGHT_BYTES))
            batchDao.prepareImport(
                id = batchId,
                totalCount = entries.size,
                totalBytes = declaredTotal.takeUnless { hasUnknownSize },
                state = ImportBatchState.SCANNING.name,
                updatedAt = now(),
            )
            batchDao.updateState(
                id = batchId,
                state = ImportBatchState.IMPORTING.name,
                errorCode = null,
                updatedAt = now(),
                finishedAt = null,
            )

            entries.forEachIndexed { index, entry ->
                coroutineContext.ensureActive()
                val temporary = File(batchDirectory, "entry-$index.tmp")
                try {
                    ensureAvailableSpace(batchDirectory, entry.size.takeIf { it > 0 } ?: MIN_FREE_BYTES)
                    val digest = try {
                        val currentContext = coroutineContext
                        var lastSpaceCheck = 0L
                        archive.getInputStream(entry).buffered().use { input ->
                            FileOutputStream(temporary).buffered().use { output ->
                                Hashing.copyAndDigest(input, output) { copiedBytes ->
                                    currentContext.ensureActive()
                                    if (copiedBytes - lastSpaceCheck >= SPACE_CHECK_STEP_BYTES) {
                                        ensureAvailableSpace(batchDirectory, MIN_FREE_BYTES)
                                        lastSpaceCheck = copiedBytes
                                    }
                                }
                            }
                        }
                    } catch (exception: ZipException) {
                        throw ImportException(
                            ImportErrorCode.ENCRYPTED_ARCHIVE,
                            "ZIP 条目无法读取，可能使用了密码",
                            exception,
                        )
                    }
                    processFile(
                        batchId = batchId,
                        file = temporary,
                        entryName = entry.name,
                        fallbackTime = entry.time.takeIf { it > 0 },
                        digest = digest,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    if ((exception as? ImportException)?.code == ImportErrorCode.NO_SPACE) {
                        throw exception
                    }
                    recordFailedFile(batchId, entry.name, temporary, exception)
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    private suspend fun processFile(
        batchId: String,
        file: File,
        entryName: String,
        fallbackTime: Long?,
        digest: com.faker1024.icloudsync.core.files.DigestResult?,
    ) {
        val computedDigest = digest ?: try {
            val currentContext = coroutineContext
            file.inputStream().buffered().use { input ->
                Hashing.sha256(input) { currentContext.ensureActive() }
            }
        } catch (exception: IOException) {
            throw ImportException(ImportErrorCode.HASH_FAILED, "无法校验文件", exception)
        }
        val existing = mediaDao.findImportedCandidates(computedDigest.sha256, computedDigest.byteCount)
            .firstOrNull { mediaStoreWriter.exists(it.destinationUri) }
        if (existing != null) {
            saveOutcome(
                item = mediaRecord(
                    batchId = batchId,
                    entryName = entryName,
                    displayName = entryName.substringAfterLast('/').substringAfterLast('\\'),
                    size = computedDigest.byteCount,
                    sha256 = computedDigest.sha256,
                    state = ImportedMediaState.DUPLICATE,
                    mediaKind = existing.mediaKind,
                    mimeType = existing.mimeType,
                    errorCode = null,
                ),
                outcome = ImportOutcome.DUPLICATE,
                bytes = computedDigest.byteCount,
            )
            return
        }

        val detected = MediaTypeDetector.detect(file, entryName)
        if (detected == null) {
            saveOutcome(
                item = mediaRecord(
                    batchId = batchId,
                    entryName = entryName,
                    displayName = entryName.substringAfterLast('/').substringAfterLast('\\'),
                    size = computedDigest.byteCount,
                    sha256 = computedDigest.sha256,
                    state = ImportedMediaState.UNSUPPORTED,
                    mediaKind = MediaKind.UNKNOWN,
                    mimeType = null,
                    errorCode = ImportErrorCode.UNSUPPORTED_MEDIA.name,
                ),
                outcome = ImportOutcome.UNSUPPORTED,
                bytes = computedDigest.byteCount,
            )
            return
        }

        val metadata = MediaMetadataReader.read(file, detected, fallbackTime)
        val albumName = settings.currentAlbumName()
        val (destinationUri, displayName) = mediaStoreWriter.write(file, detected, metadata, albumName)
        saveOutcome(
            item = ImportedMediaEntity(
                id = UUID.randomUUID().toString(),
                batchId = batchId,
                entryName = entryName,
                displayName = displayName,
                mimeType = detected.mimeType,
                size = computedDigest.byteCount,
                sha256 = computedDigest.sha256,
                mediaKind = detected.kind,
                captureTime = metadata.captureTime,
                width = metadata.width,
                height = metadata.height,
                durationMs = metadata.durationMs,
                livePhotoGroupKey = metadata.livePhotoGroupKey,
                destinationUri = destinationUri.toString(),
                state = ImportedMediaState.IMPORTED,
                errorCode = null,
                createdAt = now(),
            ),
            outcome = ImportOutcome.IMPORTED,
            bytes = computedDigest.byteCount,
        )
    }

    private suspend fun recordFailedFile(
        batchId: String,
        entryName: String,
        file: File,
        exception: Exception,
    ) {
        val code = (exception as? ImportException)?.code ?: ImportErrorCode.UNKNOWN
        saveOutcome(
            item = mediaRecord(
                batchId = batchId,
                entryName = entryName,
                displayName = entryName.substringAfterLast('/').substringAfterLast('\\'),
                size = file.length(),
                sha256 = null,
                state = ImportedMediaState.FAILED,
                mediaKind = MediaKind.UNKNOWN,
                mimeType = null,
                errorCode = code.name,
            ),
            outcome = ImportOutcome.FAILED,
            bytes = file.length(),
        )
    }

    private suspend fun saveOutcome(
        item: ImportedMediaEntity,
        outcome: ImportOutcome,
        bytes: Long,
    ) {
        database.withTransaction {
            mediaDao.insert(item)
            batchDao.recordOutcome(
                id = item.batchId,
                imported = if (outcome == ImportOutcome.IMPORTED) 1 else 0,
                duplicate = if (outcome == ImportOutcome.DUPLICATE) 1 else 0,
                failed = if (outcome == ImportOutcome.FAILED) 1 else 0,
                unsupported = if (outcome == ImportOutcome.UNSUPPORTED) 1 else 0,
                bytes = bytes,
                state = ImportBatchState.IMPORTING.name,
                updatedAt = now(),
            )
        }
    }

    private suspend fun finishBatch(batchId: String) {
        val batch = batchDao.get(batchId) ?: return
        val hasSuccess = batch.importedCount > 0 || batch.duplicateCount > 0
        val hasProblem = batch.failedCount > 0 || batch.unsupportedCount > 0
        val finalState = when {
            hasProblem && hasSuccess -> ImportBatchState.PARTIAL_FAILED
            hasProblem -> ImportBatchState.FAILED
            else -> ImportBatchState.COMPLETED
        }
        val now = now()
        val firstErrorCode = mediaDao.firstErrorCode(batchId)
        batchDao.updateState(
            id = batchId,
            state = finalState.name,
            errorCode = if (hasProblem) firstErrorCode ?: ImportErrorCode.UNKNOWN.name else null,
            updatedAt = now,
            finishedAt = now,
        )
    }

    private fun mediaRecord(
        batchId: String,
        entryName: String,
        displayName: String,
        size: Long,
        sha256: String?,
        state: ImportedMediaState,
        mediaKind: MediaKind,
        mimeType: String?,
        errorCode: String?,
    ) = ImportedMediaEntity(
        id = UUID.randomUUID().toString(),
        batchId = batchId,
        entryName = entryName,
        displayName = displayName,
        mimeType = mimeType,
        size = size,
        sha256 = sha256,
        mediaKind = mediaKind,
        captureTime = null,
        width = null,
        height = null,
        durationMs = null,
        livePhotoGroupKey = null,
        destinationUri = null,
        state = state,
        errorCode = errorCode,
        createdAt = now(),
    )

    private fun ensureAvailableSpace(directory: File, requiredBytes: Long) {
        val safeRequired = requiredBytes.coerceAtLeast(MIN_FREE_BYTES)
        val storageManager = context.getSystemService(StorageManager::class.java)
        val allocatableBytes = runCatching {
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(directory))
        }.getOrElse { directory.usableSpace }
        if (allocatableBytes < safeRequired + MIN_FREE_BYTES) {
            throw ImportException(ImportErrorCode.NO_SPACE, "设备存储空间不足")
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val BUFFER_SIZE = 128 * 1024
        const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024
        const val SPACE_CHECK_STEP_BYTES = 16L * 1024 * 1024
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
        const val MAX_PREFLIGHT_BYTES = 2L * 1024 * 1024 * 1024
    }
}

package com.faker1024.icloudsync.core.local

import com.faker1024.icloudsync.core.settings.LocalSortDirection
import com.faker1024.icloudsync.core.settings.LocalSortField
import java.text.Collator
import java.util.Locale

data class SyncedFile(
    val id: Long,
    val contentUri: String,
    val displayName: String,
    val mimeType: String?,
    val size: Long,
    val modifiedAtMillis: Long,
    val directories: List<String>,
    val localModifiedAtMillis: Long = modifiedAtMillis,
) {
    val isImage: Boolean
        get() = mimeType?.startsWith("image/", ignoreCase = true) == true ||
            displayName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
}

sealed interface SyncedBrowserEntry {
    val key: String

    data class Folder(
        val name: String,
        val path: List<String>,
        val descendantFileCount: Int,
        val totalBytes: Long,
        val modifiedAtMillis: Long,
    ) : SyncedBrowserEntry {
        override val key: String = "folder:${path.joinToString("/")}"
    }

    data class File(val item: SyncedFile) : SyncedBrowserEntry {
        override val key: String = "file:${item.contentUri}"
    }
}

internal fun parseSyncedDirectories(relativePath: String): List<String>? {
    val normalized = relativePath.replace('\\', '/').trim('/')
    if (normalized == SYNCED_FILES_ROOT_RELATIVE_PATH) return emptyList()
    val prefix = "$SYNCED_FILES_ROOT_RELATIVE_PATH/"
    if (!normalized.startsWith(prefix)) return null
    return normalized.removePrefix(prefix)
        .split('/')
        .filter(String::isNotBlank)
        .takeIf { segments -> segments.none { it == "." || it == ".." } }
}

fun buildSyncedBrowserEntries(
    files: List<SyncedFile>,
    currentPath: List<String>,
    sortField: LocalSortField = LocalSortField.NAME,
    sortDirection: LocalSortDirection = LocalSortDirection.ASCENDING,
    query: String = "",
): List<SyncedBrowserEntry> {
    val collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.SECONDARY
    }
    val descendants = files.filter { file ->
        file.directories.size >= currentPath.size &&
            file.directories.take(currentPath.size) == currentPath
    }
    val folders = descendants
        .filter { it.directories.size > currentPath.size }
        .groupBy { it.directories[currentPath.size] }
        .map { (name, children) ->
            SyncedBrowserEntry.Folder(
                name = name,
                path = currentPath + name,
                descendantFileCount = children.size,
                totalBytes = children.fold(0L) { total, file -> safeAdd(total, file.size) },
                modifiedAtMillis = children.maxOfOrNull(SyncedFile::modifiedAtMillis) ?: 0L,
            )
        }
        .sortedWith { left, right ->
            val missingModified = if (sortField == LocalSortField.MODIFIED_TIME) {
                compareMissingModified(left.modifiedAtMillis, right.modifiedAtMillis)
            } else {
                0
            }
            if (missingModified != 0) {
                missingModified
            } else {
                val primary = when (sortField) {
                    LocalSortField.NAME -> collator.compare(left.name, right.name)
                    LocalSortField.MODIFIED_TIME -> left.modifiedAtMillis.compareTo(right.modifiedAtMillis)
                    LocalSortField.SIZE -> left.totalBytes.compareTo(right.totalBytes)
                    LocalSortField.FILE_TYPE -> 0
                }
                directedComparison(primary, sortDirection).takeIf { it != 0 }
                    ?: collator.compare(left.name, right.name).takeIf { it != 0 }
                    ?: left.key.compareTo(right.key)
            }
        }
    val directFiles = descendants
        .filter { it.directories.size == currentPath.size }
        .sortedWith { left, right ->
            val missingModified = if (sortField == LocalSortField.MODIFIED_TIME) {
                compareMissingModified(left.modifiedAtMillis, right.modifiedAtMillis)
            } else {
                0
            }
            if (missingModified != 0) {
                missingModified
            } else {
                val primary = when (sortField) {
                    LocalSortField.NAME -> collator.compare(left.displayName, right.displayName)
                    LocalSortField.MODIFIED_TIME -> left.modifiedAtMillis.compareTo(right.modifiedAtMillis)
                    LocalSortField.SIZE -> left.size.compareTo(right.size)
                    LocalSortField.FILE_TYPE -> collator.compare(fileTypeKey(left), fileTypeKey(right))
                }
                directedComparison(primary, sortDirection).takeIf { it != 0 }
                    ?: collator.compare(left.displayName, right.displayName).takeIf { it != 0 }
                    ?: left.contentUri.compareTo(right.contentUri)
            }
        }
        .map { SyncedBrowserEntry.File(it) }
    val normalizedQuery = query.trim()
    return (folders + directFiles).filter { entry ->
        normalizedQuery.isBlank() || when (entry) {
            is SyncedBrowserEntry.Folder -> entry.name.contains(normalizedQuery, ignoreCase = true)
            is SyncedBrowserEntry.File -> entry.item.displayName.contains(normalizedQuery, ignoreCase = true)
        }
    }
}

private fun compareMissingModified(left: Long, right: Long): Int = when {
    left <= 0L && right > 0L -> 1
    left > 0L && right <= 0L -> -1
    else -> 0
}

private fun directedComparison(value: Int, direction: LocalSortDirection): Int = when {
    value == 0 -> 0
    direction == LocalSortDirection.ASCENDING -> value
    value < 0 -> 1
    else -> -1
}

private fun fileTypeKey(file: SyncedFile): String =
    file.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)

private fun safeAdd(left: Long, right: Long): Long =
    if (right > 0L && Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right.coerceAtLeast(0L)

const val SYNCED_FILES_PUBLIC_PATH = "Download/iCloud Drive/"
private const val SYNCED_FILES_ROOT_RELATIVE_PATH = "Download/iCloud Drive"

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "tif", "tiff",
)

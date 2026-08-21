package com.faker1024.icloudsync.core.local

data class SyncedFile(
    val id: Long,
    val contentUri: String,
    val displayName: String,
    val mimeType: String?,
    val size: Long,
    val modifiedAtMillis: Long,
    val directories: List<String>,
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
): List<SyncedBrowserEntry> {
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
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SyncedBrowserEntry.Folder::name))
    val directFiles = descendants
        .filter { it.directories.size == currentPath.size }
        .sortedWith(
            compareByDescending<SyncedFile> { it.modifiedAtMillis }
                .thenBy(String.CASE_INSENSITIVE_ORDER, SyncedFile::displayName),
        )
        .map { SyncedBrowserEntry.File(it) }
    return folders + directFiles
}

const val SYNCED_FILES_PUBLIC_PATH = "Download/iCloud Drive/"
private const val SYNCED_FILES_ROOT_RELATIVE_PATH = "Download/iCloud Drive"

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "tif", "tiff",
)

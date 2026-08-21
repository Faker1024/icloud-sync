package com.faker1024.icloudsync.core.local

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.faker1024.icloudsync.core.sync.sanitizeCloudDirectoryName
import com.faker1024.icloudsync.core.web.sanitizeCloudFileName
import java.io.File
import java.security.MessageDigest

const val PRIVATE_DRIVE_DISPLAY_PATH = "私密存储/iCloud Drive/"
const val PRIVATE_DRIVE_DIRECTORY_NAME = "icloud-drive"

fun privateDriveRoot(context: Context): File =
    File(context.filesDir, PRIVATE_DRIVE_DIRECTORY_NAME)

fun privateDriveFile(
    context: Context,
    directories: List<String>,
    fileName: String,
): File {
    val root = privateDriveRoot(context)
    val parent = directories.fold(root) { current, segment ->
        File(current, sanitizeCloudDirectoryName(segment))
    }
    return File(parent, sanitizeCloudFileName(fileName))
}

fun privateDriveUri(context: Context, file: File): Uri {
    val root = privateDriveRoot(context).canonicalFile
    val target = file.canonicalFile
    require(target.path.startsWith(root.path + File.separator)) { "文件不在 iCloud 私密目录中" }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.private-files",
        target,
    )
}

fun privateDriveDirectories(root: File, file: File): List<String>? {
    val target = file.canonicalFile
    if (!target.isFile) return null
    val relative = runCatching { target.relativeTo(root.canonicalFile) }.getOrNull()
        ?: return null
    if (relative.path.startsWith("..")) return null
    return relative.parentFile?.invariantSeparatorsPath
        ?.split('/')
        ?.filter(String::isNotBlank)
        .orEmpty()
}

fun addStablePrivateFileSuffix(fileName: String, stableKey: String): String {
    val suffix = MessageDigest.getInstance("SHA-256")
        .digest(stableKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(12)
    val dot = fileName.lastIndexOf('.').takeIf { it > 0 }
    val base = dot?.let { fileName.substring(0, it) } ?: fileName
    val extension = dot?.let { fileName.substring(it) }.orEmpty()
    return sanitizeCloudFileName("$base (iCloud-$suffix)$extension")
}

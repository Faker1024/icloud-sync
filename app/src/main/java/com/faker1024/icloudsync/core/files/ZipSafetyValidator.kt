package com.faker1024.icloudsync.core.files

object ZipSafetyValidator {
    const val MAX_ENTRIES = 5_000

    fun validateEntryName(entryName: String): Boolean {
        if (entryName.isBlank() || entryName.indexOf('\u0000') >= 0) return false
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith('/') || DRIVE_PREFIX.matches(normalized)) return false
        var depth = 0
        normalized.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    depth--
                    if (depth < 0) return false
                }
                else -> depth++
            }
        }
        return true
    }

    fun shouldIgnore(entryName: String): Boolean {
        val normalized = entryName.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')
        return normalized.startsWith("__MACOSX/") ||
            fileName == ".DS_Store" ||
            fileName.startsWith("._")
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:/.*")
}

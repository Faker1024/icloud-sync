package com.faker1024.icloudsync.core.icloud

import com.faker1024.icloudsync.core.settings.CloudSortDirection
import com.faker1024.icloudsync.core.settings.CloudSortField
import java.text.Collator
import java.time.Instant
import java.util.Locale

fun sortCloudDriveItems(
    items: List<ICloudDriveItem>,
    field: CloudSortField,
    direction: CloudSortDirection,
): List<ICloudDriveItem> {
    val collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.SECONDARY
    }
    return items.sortedWith { left, right ->
        val category = left.isFolder.compareTo(right.isFolder)
        if (category != 0) {
            -category
        } else {
            val missingModified = if (field == CloudSortField.MODIFIED_TIME) {
                when {
                    left.modifiedAt == null && right.modifiedAt != null -> 1
                    left.modifiedAt != null && right.modifiedAt == null -> -1
                    else -> 0
                }
            } else {
                0
            }
            if (missingModified != 0) {
                missingModified
            } else {
                val primary = when (field) {
                    CloudSortField.NAME -> collator.compare(left.name, right.name)
                    CloudSortField.MODIFIED_TIME -> compareModified(left.modifiedAt, right.modifiedAt)
                    CloudSortField.SIZE -> left.size.compareTo(right.size)
                    CloudSortField.FILE_TYPE -> collator.compare(fileTypeKey(left), fileTypeKey(right))
                }
                val directed = when {
                    primary == 0 -> 0
                    direction == CloudSortDirection.ASCENDING -> primary
                    primary < 0 -> 1
                    else -> -1
                }
                when {
                    directed != 0 -> directed
                    else -> {
                        val byName = collator.compare(left.name, right.name)
                        if (byName != 0) byName else left.id.compareTo(right.id)
                    }
                }
            }
        }
    }
}

private fun compareModified(left: String?, right: String?): Int {
    if (left == null && right == null) return 0
    if (left == null) return 1
    if (right == null) return -1
    val leftInstant = runCatching { Instant.parse(left) }.getOrNull()
    val rightInstant = runCatching { Instant.parse(right) }.getOrNull()
    return if (leftInstant != null && rightInstant != null) {
        leftInstant.compareTo(rightInstant)
    } else {
        left.compareTo(right, ignoreCase = true)
    }
}

private fun fileTypeKey(item: ICloudDriveItem): String = if (item.isFolder) {
    item.type
} else {
    item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

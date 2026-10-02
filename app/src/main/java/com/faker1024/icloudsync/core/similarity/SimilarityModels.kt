package com.faker1024.icloudsync.core.similarity

import com.faker1024.icloudsync.core.local.SyncedFile

data class SimilarImageGroup(
    val id: String,
    val files: List<SyncedFile>,
    /** Algorithm agreement score, not a probability that deletion is safe. */
    val score: Int,
)

data class SimilarityScanProgress(val processed: Int, val total: Int, val skipped: Int)

data class SimilarityScanResult(
    val groups: List<SimilarImageGroup>,
    /** Number of images decoded and compared successfully. */
    val scanned: Int,
    val skipped: Int,
)

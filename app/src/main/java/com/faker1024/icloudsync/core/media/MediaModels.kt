package com.faker1024.icloudsync.core.media

import com.faker1024.icloudsync.domain.model.MediaKind

data class DetectedMedia(
    val kind: MediaKind,
    val mimeType: String,
    val originalName: String,
)

data class MediaMetadata(
    val captureTime: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val livePhotoGroupKey: String?,
)

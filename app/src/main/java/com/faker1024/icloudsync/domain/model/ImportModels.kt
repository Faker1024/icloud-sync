package com.faker1024.icloudsync.domain.model

enum class ImportBatchState {
    QUEUED,
    STAGING,
    PREFLIGHT,
    SCANNING,
    IMPORTING,
    COMPLETED,
    PARTIAL_FAILED,
    FAILED,
    CANCELLED,
}

enum class ImportedMediaState {
    IMPORTED,
    DUPLICATE,
    FAILED,
    UNSUPPORTED,
}

enum class MediaKind {
    IMAGE,
    VIDEO,
    RAW,
    UNKNOWN,
}

enum class ImportOutcome {
    IMPORTED,
    DUPLICATE,
    FAILED,
    UNSUPPORTED,
}

enum class ImportErrorCode {
    SOURCE_UNREADABLE,
    SOURCE_INCOMPLETE,
    UNSUPPORTED_ARCHIVE,
    ENCRYPTED_ARCHIVE,
    UNSAFE_ARCHIVE_PATH,
    TOO_MANY_ENTRIES,
    NO_SPACE,
    UNSUPPORTED_MEDIA,
    HASH_FAILED,
    MEDIASTORE_WRITE_FAILED,
    TASK_INTERRUPTED,
    USER_CANCELLED,
    UNKNOWN,
}

class ImportException(
    val code: ImportErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

fun ImportBatchState.isFinished(): Boolean = when (this) {
    ImportBatchState.COMPLETED,
    ImportBatchState.PARTIAL_FAILED,
    ImportBatchState.FAILED,
    ImportBatchState.CANCELLED,
    -> true

    else -> false
}

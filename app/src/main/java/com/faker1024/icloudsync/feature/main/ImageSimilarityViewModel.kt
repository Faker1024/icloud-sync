package com.faker1024.icloudsync.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faker1024.icloudsync.core.local.SyncedFile
import com.faker1024.icloudsync.core.local.SyncedFileRepository
import com.faker1024.icloudsync.core.similarity.DeletionProgress
import com.faker1024.icloudsync.core.similarity.ImageDeletionResult
import com.faker1024.icloudsync.core.similarity.ImageSimilarityRepository
import com.faker1024.icloudsync.core.similarity.SimilarImageDeletionRepository
import com.faker1024.icloudsync.core.similarity.SimilarImageGroup
import com.faker1024.icloudsync.core.similarity.SimilarityLevel
import com.faker1024.icloudsync.core.similarity.SimilarityScanProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImageSimilarityUiState(
    val level: SimilarityLevel = SimilarityLevel.BALANCED,
    val groups: List<SimilarImageGroup> = emptyList(),
    val selectedUris: Set<String> = emptySet(),
    val scanning: Boolean = false,
    val deleting: Boolean = false,
    val scanProgress: SimilarityScanProgress? = null,
    val deletionProgress: DeletionProgress? = null,
    val scanned: Int = 0,
    val skipped: Int = 0,
    val hasScanned: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val deletionFailures: List<ImageDeletionResult> = emptyList(),
    val pendingLocalDeletions: List<ImageDeletionResult> = emptyList(),
) {
    val confirmedPendingUris: Set<String>
        get() = pendingLocalDeletions.filter { it.cloudDeleted && !it.localDeleted }
            .mapTo(mutableSetOf()) { it.file.contentUri }

    val selectedFiles: List<SyncedFile>
        get() = (groups.flatMap(SimilarImageGroup::files) + pendingLocalDeletions.map { it.file })
            .filter { it.contentUri in selectedUris }.distinctBy(SyncedFile::contentUri)

    val busy: Boolean get() = scanning || deleting
}

@HiltViewModel
class ImageSimilarityViewModel @Inject constructor(
    private val files: SyncedFileRepository,
    private val similarity: ImageSimilarityRepository,
    private val deletion: SimilarImageDeletionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ImageSimilarityUiState())
    val state = _state.asStateFlow()
    private var scanJob: Job? = null
    private var pendingGeneration = 0

    init { refreshPendingLocalCleanup() }

    fun refreshPendingLocalCleanup() {
        if (_state.value.busy) return
        val generation = ++pendingGeneration
        viewModelScope.launch {
            try {
                val pending = deletion.pendingLocalDeletions(files.listFiles())
                if (generation == pendingGeneration) {
                    _state.update { it.withPendingLocalDeletions(pending) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == pendingGeneration) {
                    _state.update { it.copy(error = "待清理记录暂时无法读取，请重新扫描后重试。") }
                }
            }
        }
    }

    fun setLevel(level: SimilarityLevel) {
        if (_state.value.busy || _state.value.level == level) return
        _state.update { it.copy(level = level, groups = emptyList(), selectedUris = emptySet(),
            hasScanned = false, error = null, message = null, deletionFailures = emptyList()) }
    }

    fun scan() {
        if (_state.value.busy) return
        pendingGeneration++
        val level = _state.value.level
        _state.update { it.copy(scanning = true, scanProgress = null, error = null, message = null,
            groups = emptyList(), selectedUris = emptySet(), deletionFailures = emptyList(), hasScanned = false) }
        scanJob = viewModelScope.launch {
            try {
                val result = similarity.scan(files.listFiles().filter(SyncedFile::isImage), level) { progress ->
                    _state.update { it.copy(scanProgress = progress) }
                }
                val pending = deletion.pendingLocalDeletions(files.listFiles())
                _state.update { it.copy(scanning = false, groups = result.groups, scanned = result.scanned,
                    skipped = result.skipped, hasScanned = true).withPendingLocalDeletions(pending) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(scanning = false, message = "扫描已取消") }
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(scanning = false, error = "无法完成图片扫描，请刷新本地文件后重试。") }
            }
        }
    }

    fun cancelScan() { scanJob?.cancel() }

    fun toggle(file: SyncedFile) {
        if (_state.value.busy) return
        _state.update { current ->
            if (file.contentUri in current.selectedUris) {
                current.copy(selectedUris = current.selectedUris - file.contentUri, message = null)
            } else if (canSelectSimilarityFile(current.groups, current.selectedUris, file.contentUri,
                    current.confirmedPendingUris)) {
                current.copy(selectedUris = current.selectedUris + file.contentUri, message = null)
            } else {
                current.copy(message = "每组至少保留一张图片，请先取消选择要保留的图片。")
            }
        }
    }

    fun clearSelection() {
        if (!_state.value.busy) _state.update { it.copy(selectedUris = emptySet(), message = null) }
    }

    fun selectFailedFiles() {
        if (_state.value.busy) return
        _state.update { current ->
            val selected = current.deletionFailures.fold(emptySet<String>()) { selected, result ->
                if (canSelectSimilarityFile(current.groups, selected, result.file.contentUri,
                        current.confirmedPendingUris)) {
                    selected + result.file.contentUri
                } else selected
            }
            current.copy(selectedUris = selected)
        }
    }

    fun selectPendingLocalFiles() {
        if (_state.value.busy) return
        _state.update { it.copy(selectedUris = it.confirmedPendingUris, message = null) }
    }

    fun deleteSelected() {
        val current = _state.value
        if (current.busy) return
        val selected = current.selectedFiles
        if (selected.isEmpty() || !isSimilaritySelectionSafe(current.groups, current.selectedUris,
                current.confirmedPendingUris)) return
        pendingGeneration++
        _state.update { it.copy(deleting = true, deletionProgress = DeletionProgress(0, selected.size),
            error = null, message = null) }
        viewModelScope.launch {
            try {
                val results = deletion.delete(selected) { progress ->
                    _state.update { it.copy(deletionProgress = progress) }
                }
                val deletedUris = results.filter { it.localDeleted && it.cloudDeleted }
                    .mapTo(mutableSetOf()) { it.file.contentUri }
                val failures = results.filterNot { it.localDeleted && it.cloudDeleted }
                val pending = try {
                    deletion.pendingLocalDeletions(files.listFiles())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    current.pendingLocalDeletions.filterNot { it.file.contentUri in deletedUris }
                }
                _state.update { state ->
                    val groups = state.groups.map { group ->
                        group.copy(files = group.files.filterNot { it.contentUri in deletedUris })
                    }.filter { it.files.size > 1 }
                    state.copy(deleting = false, groups = groups, selectedUris = emptySet(),
                        deletionFailures = state.deletionFailures.filterNot { old ->
                            selected.any { it.contentUri == old.file.contentUri }
                        } + failures,
                        message = "已同步删除 ${deletedUris.size} 张图片" +
                            if (failures.isEmpty()) "" else "，${failures.size} 张未完成，可重试。")
                        .withPendingLocalDeletions(pending)
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(deleting = false) }
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(deleting = false,
                    error = if (error.message == ACTIVE_TRANSFERS_MESSAGE) ACTIVE_TRANSFERS_MESSAGE
                    else "删除未完成。请检查网络和 iCloud 登录状态后重试。") }
            }
        }
    }

    suspend fun loadImage(file: SyncedFile, targetPixels: Int) = files.loadImage(file, targetPixels)

    companion object {
        private const val ACTIVE_TRANSFERS_MESSAGE = "还有下载、同步或迁移任务，请完成或暂停这些任务后再删除"
    }
}

private fun ImageSimilarityUiState.withPendingLocalDeletions(
    pending: List<ImageDeletionResult>,
): ImageSimilarityUiState {
    val previousByUri = (pendingLocalDeletions + deletionFailures).associateBy { it.file.contentUri }
    val confirmed = pending.filter { it.cloudDeleted && !it.localDeleted }
        .distinctBy { it.file.contentUri }
        .map { entry ->
            val previous = previousByUri[entry.file.contentUri]
            entry.copy(message = previous?.message ?: entry.message)
        }
    val confirmedUris = confirmed.mapTo(mutableSetOf()) { it.file.contentUri }
    val remainingGroups = groups.map { group ->
        group.copy(files = group.files.filterNot { it.contentUri in confirmedUris })
    }.filter { it.files.size > 1 }
    val validUris = remainingGroups.flatMap { it.files }.mapTo(mutableSetOf()) { it.contentUri } + confirmedUris
    return copy(groups = remainingGroups, pendingLocalDeletions = confirmed,
        selectedUris = selectedUris.intersect(validUris),
        deletionFailures = deletionFailures.filterNot { it.file.contentUri in confirmedUris })
}

internal fun canSelectSimilarityFile(
    groups: List<SimilarImageGroup>,
    selectedUris: Set<String>,
    candidateUri: String,
    confirmedPendingUris: Set<String> = emptySet(),
): Boolean {
    if (candidateUri !in confirmedPendingUris && groups.none { group ->
            group.files.any { it.contentUri == candidateUri }
        }) return false
    return isSimilaritySelectionSafe(groups, selectedUris + candidateUri, confirmedPendingUris)
}

internal fun isSimilaritySelectionSafe(
    groups: List<SimilarImageGroup>,
    selectedUris: Set<String>,
    confirmedPendingUris: Set<String> = emptySet(),
): Boolean {
    val regularSelection = selectedUris - confirmedPendingUris
    return regularSelection.all { uri -> groups.any { group -> group.files.any { it.contentUri == uri } } } &&
        groups.all { group ->
            group.files.none { it.contentUri in regularSelection } || group.files.any {
                it.contentUri !in selectedUris && it.contentUri !in confirmedPendingUris
            }
        }
}

package com.faker1024.icloudsync.feature.main

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faker1024.icloudsync.core.database.ImportBatchEntity
import com.faker1024.icloudsync.core.database.ImportedMediaEntity
import com.faker1024.icloudsync.core.importer.ImportRepository
import com.faker1024.icloudsync.core.local.SyncedFile
import com.faker1024.icloudsync.core.local.SyncedFileRepository
import com.faker1024.icloudsync.core.settings.ImportSettings
import com.faker1024.icloudsync.core.settings.LocalBrowserLayout
import com.faker1024.icloudsync.core.settings.LocalBrowserPreferences
import com.faker1024.icloudsync.core.settings.LocalBrowserSettings
import com.faker1024.icloudsync.core.settings.LocalSortDirection
import com.faker1024.icloudsync.core.settings.LocalSortField
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: ImportRepository,
    private val settings: ImportSettings,
    private val syncedFileRepository: SyncedFileRepository,
    private val localBrowserSettings: LocalBrowserSettings,
) : ViewModel() {
    val batches: StateFlow<List<ImportBatchEntity>> = repository.observeRecentBatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albumName: StateFlow<String> = settings.albumName
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ImportSettings.DEFAULT_ALBUM_NAME,
        )

    val localBrowserPreferences: StateFlow<LocalBrowserPreferences> = localBrowserSettings.preferences
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LocalBrowserPreferences(),
        )

    val selectedBatchId = MutableStateFlow<String?>(null)
    val selectedBatchItems: StateFlow<List<ImportedMediaEntity>> = selectedBatchId
        .flatMapLatest { batchId ->
            if (batchId == null) flowOf(emptyList()) else repository.observeBatchItems(batchId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val eventChannel = Channel<MainEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private val _syncedFiles = MutableStateFlow(SyncedFilesUiState())
    val syncedFiles: StateFlow<SyncedFilesUiState> = _syncedFiles
    private var syncedFilesRefreshJob: Job? = null

    init {
        refreshSyncedFiles()
    }

    fun enqueueImport(uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.enqueue(uri) }
                .onSuccess { eventChannel.send(MainEvent.Message("已加入导入队列")) }
                .onFailure { eventChannel.send(MainEvent.Message("无法读取所选文件，请重新选择")) }
        }
    }

    fun cancel(batchId: String) {
        viewModelScope.launch {
            repository.cancel(batchId)
            eventChannel.send(MainEvent.Message("已取消导入"))
        }
    }

    fun deleteHistory(batchId: String) {
        viewModelScope.launch {
            if (selectedBatchId.value == batchId) selectedBatchId.value = null
            repository.deleteHistory(batchId)
            eventChannel.send(MainEvent.Message("已清除该条历史，不会删除系统相册文件"))
        }
    }

    fun showBatchDetails(batchId: String) {
        selectedBatchId.value = batchId
    }

    fun closeBatchDetails() {
        selectedBatchId.value = null
    }

    fun saveAlbumName(value: String) {
        viewModelScope.launch {
            settings.setAlbumName(value)
            eventChannel.send(MainEvent.Message("相册名称已保存"))
        }
    }

    fun refreshSyncedFiles() {
        syncedFilesRefreshJob?.cancel()
        syncedFilesRefreshJob = viewModelScope.launch {
            _syncedFiles.value = _syncedFiles.value.copy(isLoading = true, error = null)
            runCatching { syncedFileRepository.listFiles() }
                .onSuccess { files ->
                    _syncedFiles.value = SyncedFilesUiState(files = files)
                }
                .onFailure {
                    _syncedFiles.value = _syncedFiles.value.copy(
                        isLoading = false,
                        error = "无法读取 Download/iCloud Drive/，请稍后重试",
                    )
                }
        }
    }

    suspend fun loadSyncedImage(file: SyncedFile, targetPixels: Int): Bitmap =
        syncedFileRepository.loadImage(file, targetPixels)

    fun openSyncedFile(file: SyncedFile) {
        syncedFileRepository.openExternally(file)
            .onFailure {
                viewModelScope.launch {
                    eventChannel.send(MainEvent.Message("没有可打开此文件的应用，或文件已被移除"))
                }
            }
    }

    fun setLocalBrowserLayout(layout: LocalBrowserLayout) {
        viewModelScope.launch { localBrowserSettings.setLayout(layout) }
    }

    fun setLocalSorting(field: LocalSortField, direction: LocalSortDirection) {
        viewModelScope.launch { localBrowserSettings.setSorting(field, direction) }
    }
}

data class SyncedFilesUiState(
    val files: List<SyncedFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface MainEvent {
    data class Message(val text: String) : MainEvent
}

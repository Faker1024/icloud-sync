package com.faker1024.icloudsync.feature.main

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.faker1024.icloudsync.core.database.ImportBatchEntity
import com.faker1024.icloudsync.core.database.ImportedMediaEntity
import com.faker1024.icloudsync.core.importer.ImportRepository
import com.faker1024.icloudsync.core.local.SyncedFile
import com.faker1024.icloudsync.core.local.SyncedFileRepository
import com.faker1024.icloudsync.core.local.PrivateStorageMigrationCoordinator
import com.faker1024.icloudsync.core.local.PublicFileMigrationStore
import com.faker1024.icloudsync.core.settings.ImportSettings
import com.faker1024.icloudsync.core.settings.LocalBrowserLayout
import com.faker1024.icloudsync.core.settings.LocalBrowserPreferences
import com.faker1024.icloudsync.core.settings.LocalBrowserSettings
import com.faker1024.icloudsync.core.settings.LocalSortDirection
import com.faker1024.icloudsync.core.settings.LocalSortField
import com.faker1024.icloudsync.core.worker.PrivateStorageMigrationKeys
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: ImportRepository,
    private val settings: ImportSettings,
    private val syncedFileRepository: SyncedFileRepository,
    private val localBrowserSettings: LocalBrowserSettings,
    private val privateStorageMigrationCoordinator: PrivateStorageMigrationCoordinator,
    private val publicFileMigrationStore: PublicFileMigrationStore,
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
    private val _privateStorage = MutableStateFlow(PrivateStorageUiState())
    val privateStorage: StateFlow<PrivateStorageUiState> = _privateStorage
    private var migrationWorkId: UUID? = null

    init {
        refreshSyncedFiles()
        refreshPrivateStorageStatus()
        observePrivateStorageMigration()
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
                        error = "无法读取 iCloud 私密文件，请稍后重试",
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

    fun shareSyncedFile(file: SyncedFile) {
        syncedFileRepository.share(file)
            .onFailure {
                viewModelScope.launch {
                    eventChannel.send(MainEvent.Message("没有可接收此文件的应用，或文件已被移除"))
                }
            }
    }

    fun setLocalBrowserLayout(layout: LocalBrowserLayout) {
        viewModelScope.launch { localBrowserSettings.setLayout(layout) }
    }

    fun setLocalSorting(field: LocalSortField, direction: LocalSortDirection) {
        viewModelScope.launch { localBrowserSettings.setSorting(field, direction) }
    }

    fun refreshPrivateStorageStatus() {
        viewModelScope.launch {
            _privateStorage.value = _privateStorage.value.copy(isScanning = true, error = null)
            runCatching { publicFileMigrationStore.summary() }
                .onSuccess { summary ->
                    _privateStorage.value = _privateStorage.value.copy(
                        publicFileCount = summary.fileCount,
                        publicBytes = summary.totalBytes,
                        isScanning = false,
                    )
                }
                .onFailure {
                    _privateStorage.value = _privateStorage.value.copy(
                        isScanning = false,
                        error = "无法检查公共下载文件",
                    )
                }
        }
    }

    fun migratePublicFiles() {
        if (_privateStorage.value.isMigrating) return
        viewModelScope.launch {
            runCatching { privateStorageMigrationCoordinator.enqueue() }
                .onSuccess { id ->
                    migrationWorkId = id
                    _privateStorage.value = _privateStorage.value.copy(
                        isMigrating = true,
                        error = null,
                    )
                    eventChannel.send(MainEvent.Message("已开始迁移，校验完成后才会删除公共副本"))
                }
                .onFailure {
                    eventChannel.send(MainEvent.Message("无法启动私密迁移，请稍后重试"))
                }
        }
    }

    fun cancelPublicFileMigration() {
        migrationWorkId?.let(privateStorageMigrationCoordinator::cancel)
        eventChannel.trySend(MainEvent.Message("已请求取消私密迁移"))
    }

    private fun observePrivateStorageMigration() {
        viewModelScope.launch {
            privateStorageMigrationCoordinator.observe().collect { workInfos ->
                val workInfo = migrationWorkId?.let { id -> workInfos.firstOrNull { it.id == id } }
                    ?: workInfos.firstOrNull { !it.state.isFinished }
                    ?: return@collect
                migrationWorkId = workInfo.id
                val data = if (workInfo.state.isFinished) workInfo.outputData else workInfo.progress
                _privateStorage.value = _privateStorage.value.copy(
                    isMigrating = !workInfo.state.isFinished,
                    migrationTotal = data.getInt(PrivateStorageMigrationKeys.TOTAL, 0),
                    migrationCompleted = data.getInt(PrivateStorageMigrationKeys.COMPLETED, 0),
                    migrationFailed = data.getInt(PrivateStorageMigrationKeys.FAILED, 0),
                    currentFile = data.getString(PrivateStorageMigrationKeys.CURRENT_FILE).orEmpty(),
                    error = data.getString(PrivateStorageMigrationKeys.ERROR),
                )
                if (workInfo.state.isFinished) {
                    migrationWorkId = null
                    refreshPrivateStorageStatus()
                    refreshSyncedFiles()
                    val message = when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> "公共文件已迁移到私密存储"
                        WorkInfo.State.CANCELLED -> "私密迁移已取消，未完成的公共文件仍保留"
                        else -> "部分公共文件未能迁移，请重试"
                    }
                    eventChannel.send(MainEvent.Message(message))
                }
            }
        }
    }
}

data class SyncedFilesUiState(
    val files: List<SyncedFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class PrivateStorageUiState(
    val publicFileCount: Int = 0,
    val publicBytes: Long = 0L,
    val isScanning: Boolean = false,
    val isMigrating: Boolean = false,
    val migrationTotal: Int = 0,
    val migrationCompleted: Int = 0,
    val migrationFailed: Int = 0,
    val currentFile: String = "",
    val error: String? = null,
)

sealed interface MainEvent {
    data class Message(val text: String) : MainEvent
}

package com.faker1024.icloudsync.feature.main

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
import com.faker1024.icloudsync.core.icloud.ICloudDriveRepository
import com.faker1024.icloudsync.core.icloud.ICloudApiException
import com.faker1024.icloudsync.core.icloud.ICloudError
import com.faker1024.icloudsync.core.icloud.ICloudFolder
import com.faker1024.icloudsync.core.icloud.ICloudLoginResult
import com.faker1024.icloudsync.core.icloud.ICloudPcsPollResult
import com.faker1024.icloudsync.core.icloud.TrustedPhone
import com.faker1024.icloudsync.core.local.PRIVATE_DRIVE_DISPLAY_PATH
import com.faker1024.icloudsync.core.settings.CloudBrowserLayout
import com.faker1024.icloudsync.core.settings.CloudBrowserSettings
import com.faker1024.icloudsync.core.settings.CloudSortDirection
import com.faker1024.icloudsync.core.settings.CloudSortField
import com.faker1024.icloudsync.core.sync.FolderSyncCoordinator
import com.faker1024.icloudsync.core.sync.FolderSyncProgressKeys
import com.faker1024.icloudsync.core.sync.FolderSyncStage
import com.faker1024.icloudsync.core.sync.FileDownloadCoordinator
import com.faker1024.icloudsync.core.sync.FileDownloadProgressKeys
import com.faker1024.icloudsync.core.sync.FileDownloadStage
import com.faker1024.icloudsync.core.sync.SyncFailureDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class CloudDriveViewModel @Inject constructor(
    private val repository: ICloudDriveRepository,
    private val browserSettings: CloudBrowserSettings,
    private val folderSyncCoordinator: FolderSyncCoordinator,
    private val fileDownloadCoordinator: FileDownloadCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(CloudDriveUiState())
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()
    private var pcsApprovalJob: Job? = null
    private var syncObservationJob: Job? = null
    private var failureObservationJob: Job? = null
    private var observedSyncId: UUID? = null

    init {
        observeFileDownloads()
        viewModelScope.launch {
            browserSettings.preferences.collectLatest { preferences ->
                _state.update {
                    it.copy(
                        layout = preferences.layout,
                        iconSize = preferences.iconSize,
                        sortField = preferences.sortField,
                        sortDirection = preferences.sortDirection,
                    )
                }
                val syncId = preferences.lastSyncId?.let { value ->
                    runCatching { UUID.fromString(value) }.getOrNull()
                }
                if (syncId != null && syncId != observedSyncId) {
                    observedSyncId = syncId
                    observeFolderSync(
                        id = syncId,
                        folderId = preferences.lastSyncFolderId.orEmpty(),
                        folderName = preferences.lastSyncFolderName ?: "iCloud 文件夹",
                        displayPath = preferences.lastSyncDisplayPath ?: PRIVATE_DRIVE_DISPLAY_PATH,
                    )
                }
            }
        }
        viewModelScope.launch {
            val restored = runCatching { repository.restoreSession() }.getOrDefault(false)
            if (restored) {
                if (runCatching { repository.needsPcsApproval() }.getOrDefault(false)) {
                    startPcsApproval()
                } else {
                    openRootFolder()
                }
            } else {
                _state.update { it.copy(phase = CloudDrivePhase.LOGGED_OUT) }
            }
        }
    }

    fun login(accountName: String, password: String) {
        if (_state.value.isBusy) return
        if (accountName.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "请输入 Apple 账户和密码") }
            return
        }
        _state.update { it.copy(phase = CloudDrivePhase.AUTHENTICATING, error = null, isBusy = true) }
        viewModelScope.launch {
            runCatching { repository.login(accountName, password) }
                .onSuccess { result ->
                    handleLoginResult(result)
                }
                .onFailure { error ->
                    repository.logout()
                    _state.value = loggedOutState(error.userMessage())
                }
        }
    }

    fun verifyCode(code: String) {
        if (_state.value.phase != CloudDrivePhase.TWO_FACTOR || _state.value.isBusy) return
        _state.update { it.copy(isBusy = true, error = null) }
        val phoneId = _state.value.selectedPhoneId
        viewModelScope.launch {
            runCatching { repository.verifyCode(code, phoneId) }
                .onSuccess(::handleLoginResult)
                .onFailure { error ->
                    _state.update { it.copy(isBusy = false, error = error.userMessage()) }
                }
        }
    }

    fun resendTrustedDeviceCode() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, error = null, selectedPhoneId = null) }
        viewModelScope.launch {
            runCatching { repository.resendTrustedDeviceCode() }
                .onSuccess {
                    _state.update { it.copy(isBusy = false) }
                    _events.emit("验证码已请求，请查看受信任的 Apple 设备")
                }
                .onFailure { error -> _state.update { it.copy(isBusy = false, error = error.userMessage()) } }
        }
    }

    fun requestSmsCode(phone: TrustedPhone) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.requestSmsCode(phone.id) }
                .onSuccess {
                    _state.update { it.copy(isBusy = false, selectedPhoneId = phone.id) }
                    _events.emit("短信验证码已发送至 ${phone.label}")
                }
                .onFailure { error -> _state.update { it.copy(isBusy = false, error = error.userMessage()) } }
        }
    }

    fun openFolder(item: ICloudDriveItem) {
        if (!item.isFolder || _state.value.isBusy) return
        loadFolder(ICloudFolder(item.id, item.name), replacePath = false)
    }

    fun navigateBack(): Boolean {
        val path = _state.value.path
        if (path.size <= 1 || _state.value.isBusy) return false
        loadFolder(path[path.lastIndex - 1], replacePath = false, navigateUp = true)
        return true
    }

    fun refresh() {
        _state.value.path.lastOrNull()?.let { loadFolder(it, replacePath = false, refreshOnly = true) }
    }

    fun download(item: ICloudDriveItem) {
        if (item.isFolder || item.id in _state.value.downloadingIds) return
        _state.update { it.copy(downloadingIds = it.downloadingIds + item.id) }
        val localPath = _state.value.path.drop(1).map(ICloudFolder::name)
        viewModelScope.launch {
            runCatching { fileDownloadCoordinator.enqueue(item, localPath) }
                .onSuccess { _events.emit("${item.name} 已加入后台下载，可在通知中查看进度") }
                .onFailure { error ->
                    _state.update { it.copy(downloadingIds = it.downloadingIds - item.id) }
                    _events.emit(error.userMessage())
                }
        }
    }

    fun cancelFileDownload(itemId: String) {
        val task = _state.value.fileDownloads[itemId] ?: return
        fileDownloadCoordinator.cancel(task.workId)
        _events.tryEmit("已暂停下载，重新点击时会尝试从断点继续")
    }

    fun syncFolder(item: ICloudDriveItem) {
        if (!item.isFolder) return
        val localPath = _state.value.path.drop(1).map(ICloudFolder::name) + item.name
        viewModelScope.launch {
            runCatching { folderSyncCoordinator.enqueue(item.id, item.name, localPath) }
                .onSuccess { _events.emit("已开始同步 ${item.name}，失败文件会自动重试") }
                .onFailure { _events.emit(it.userMessage()) }
        }
    }

    fun cancelFolderSync() {
        val workId = _state.value.folderSync?.workId ?: return
        folderSyncCoordinator.cancel(workId)
        _events.tryEmit("已取消文件夹同步")
    }

    fun retryFailedFolderFiles() {
        val sync = _state.value.folderSync ?: return
        if (sync.isActive || sync.failures.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                folderSyncCoordinator.retryFailures(sync.folderId, sync.folderName, sync.displayPath)
            }.onSuccess { id ->
                observedSyncId = id
                observeFolderSync(id, sync.folderId, sync.folderName, sync.displayPath)
                _events.emit("已重新加入 ${sync.failures.size} 个失败文件")
            }.onFailure { error ->
                _events.emit(error.userMessage())
            }
        }
    }

    fun setLayout(layout: CloudBrowserLayout) {
        _state.update { it.copy(layout = layout) }
        viewModelScope.launch { browserSettings.setLayout(layout) }
    }

    fun setIconSize(value: Float) {
        _state.update { it.copy(iconSize = value) }
        viewModelScope.launch { browserSettings.setIconSize(value) }
    }

    fun setSorting(field: CloudSortField, direction: CloudSortDirection) {
        _state.update { it.copy(sortField = field, sortDirection = direction) }
        viewModelScope.launch { browserSettings.setSorting(field, direction) }
    }

    suspend fun loadImagePreview(item: ICloudDriveItem, targetPixels: Int): Bitmap =
        repository.loadImagePreview(item, targetPixels)

    suspend fun prepareImagePreviewSource(item: ICloudDriveItem): File =
        repository.prepareImagePreviewSource(item)

    fun retryPcsApproval() = startPcsApproval()

    fun logout() {
        pcsApprovalJob?.cancel()
        pcsApprovalJob = null
        _state.value.folderSync?.takeIf(FolderSyncUiState::isActive)?.let {
            folderSyncCoordinator.cancel(it.workId)
        }
        _state.value.fileDownloads.values.forEach { fileDownloadCoordinator.cancel(it.workId) }
        syncObservationJob?.cancel()
        syncObservationJob = null
        failureObservationJob?.cancel()
        failureObservationJob = null
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            repository.logout()
            _state.value = loggedOutState()
            _events.tryEmit("已清除本机 iCloud 登录会话")
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun handleLoginResult(result: ICloudLoginResult) {
        when (result) {
            ICloudLoginResult.Authenticated -> openRootFolder()
            ICloudLoginResult.NeedsPcsApproval -> startPcsApproval()
            is ICloudLoginResult.NeedsTwoFactor -> _state.update {
                it.copy(
                    phase = CloudDrivePhase.TWO_FACTOR,
                    phones = result.phones,
                    selectedPhoneId = null,
                    isBusy = false,
                    error = null,
                )
            }
        }
    }

    private fun openRootFolder() {
        _state.update { it.copy(phase = CloudDrivePhase.BROWSING, isBusy = false, error = null) }
        loadFolder(ICloudFolder(ICloudDriveRepository.ROOT_ID, "iCloud Drive"), replacePath = true)
    }

    private fun startPcsApproval() {
        pcsApprovalJob?.cancel()
        _state.update {
            it.copy(
                phase = CloudDrivePhase.PCS_APPROVAL,
                isBusy = true,
                error = null,
                pcsAttempt = 0,
                pcsMessage = "正在向受信任设备请求 iCloud Drive 访问授权…",
            )
        }
        pcsApprovalJob = viewModelScope.launch {
            for (attempt in 1..PCS_MAX_ATTEMPTS) {
                _state.update {
                    it.copy(
                        phase = CloudDrivePhase.PCS_APPROVAL,
                        isBusy = true,
                        pcsAttempt = attempt,
                        pcsMessage = if (attempt == 1) {
                            "正在发送授权请求…"
                        } else {
                            "正在检查设备批准结果…"
                        },
                    )
                }
                val result = try {
                    repository.pollPcsApproval()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    _state.update {
                        it.copy(isBusy = false, error = error.userMessage(), pcsMessage = "授权请求暂未完成")
                    }
                    return@launch
                }
                if (result == ICloudPcsPollResult.APPROVED) {
                    _events.emit("受信任设备已批准 iCloud Drive 访问")
                    openRootFolder()
                    return@launch
                }
                _state.update {
                    it.copy(
                        pcsMessage = "请在受信任的 iPhone、iPad 或 Mac 上批准临时访问",
                        isBusy = true,
                    )
                }
                if (attempt < PCS_MAX_ATTEMPTS) delay(PCS_POLL_INTERVAL_MS)
            }
            _state.update {
                it.copy(
                    isBusy = false,
                    error = "等待设备批准已超时。确认已允许 iCloud.com 数据访问后重试。",
                    pcsMessage = "尚未收到设备批准",
                )
            }
        }
    }

    private fun loadFolder(
        folder: ICloudFolder,
        replacePath: Boolean,
        navigateUp: Boolean = false,
        refreshOnly: Boolean = false,
    ) {
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.listFolder(folder.id) }
                .onSuccess { items ->
                    _state.update { current ->
                        val nextPath = when {
                            replacePath -> listOf(folder)
                            refreshOnly -> current.path
                            navigateUp -> current.path.dropLast(1)
                            else -> current.path + folder
                        }
                        current.copy(
                            phase = CloudDrivePhase.BROWSING,
                            path = nextPath,
                            items = items,
                            isBusy = false,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    val message = error.userMessage()
                    if ((error as? ICloudApiException)?.reason == ICloudError.SESSION_EXPIRED) {
                        repository.logout()
                        _state.value = loggedOutState(message)
                    } else {
                        _state.update { it.copy(isBusy = false, error = message) }
                    }
                }
        }
    }

    private fun observeFolderSync(
        id: UUID,
        folderId: String,
        folderName: String,
        displayPath: String,
    ) {
        syncObservationJob?.cancel()
        failureObservationJob?.cancel()
        _state.update {
            it.copy(
                folderSync = FolderSyncUiState(
                    workId = id,
                    folderId = folderId,
                    folderName = folderName,
                    displayPath = displayPath,
                    stage = FolderSyncStage.QUEUED,
                ),
            )
        }
        syncObservationJob = viewModelScope.launch {
            folderSyncCoordinator.observe(id).collect { info ->
                if (info != null) {
                    _state.update {
                        val mapped = info.toFolderSyncUi(folderId, folderName, displayPath)
                        val failures = it.folderSync?.takeIf { current -> current.folderId == folderId }?.failures.orEmpty()
                        it.copy(folderSync = mapped.copy(failures = failures))
                    }
                }
            }
        }
        failureObservationJob = viewModelScope.launch {
            folderSyncCoordinator.observeFailures(folderId).collect { failures ->
                _state.update { current ->
                    val sync = current.folderSync
                    if (sync?.folderId == folderId) current.copy(folderSync = sync.copy(failures = failures))
                    else current
                }
            }
        }
    }

    private fun observeFileDownloads() {
        viewModelScope.launch {
            fileDownloadCoordinator.observeAll().collectLatest { workInfos ->
                val active = workInfos
                    .asSequence()
                    .filterNot { it.state.isFinished }
                    .mapNotNull { info -> info.toFileDownloadUi() }
                    .associateBy(FileDownloadUiState::itemId)
                _state.update {
                    it.copy(
                        downloadingIds = active.keys,
                        fileDownloads = active,
                    )
                }
            }
        }
    }

    private fun loggedOutState(error: String? = null): CloudDriveUiState = CloudDriveUiState(
        phase = CloudDrivePhase.LOGGED_OUT,
        error = error,
        layout = _state.value.layout,
        iconSize = _state.value.iconSize,
        sortField = _state.value.sortField,
        sortDirection = _state.value.sortDirection,
    )
}

data class CloudDriveUiState(
    val phase: CloudDrivePhase = CloudDrivePhase.RESTORING,
    val path: List<ICloudFolder> = emptyList(),
    val items: List<ICloudDriveItem> = emptyList(),
    val phones: List<TrustedPhone> = emptyList(),
    val selectedPhoneId: Int? = null,
    val downloadingIds: Set<String> = emptySet(),
    val isBusy: Boolean = false,
    val error: String? = null,
    val pcsAttempt: Int = 0,
    val pcsMessage: String = "",
    val layout: CloudBrowserLayout = CloudBrowserLayout.GRID,
    val iconSize: Float = 88f,
    val sortField: CloudSortField = CloudSortField.NAME,
    val sortDirection: CloudSortDirection = CloudSortDirection.ASCENDING,
    val folderSync: FolderSyncUiState? = null,
    val fileDownloads: Map<String, FileDownloadUiState> = emptyMap(),
)

data class FileDownloadUiState(
    val workId: UUID,
    val itemId: String,
    val stage: FileDownloadStage,
    val bytes: Long = 0L,
    val totalBytes: Long = 0L,
    val resumedBytes: Long = 0L,
    val error: String? = null,
) {
    val progress: Float?
        get() = totalBytes.takeIf { it > 0L }
            ?.let { (bytes.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }
}

data class FolderSyncUiState(
    val workId: UUID,
    val folderId: String,
    val folderName: String,
    val displayPath: String,
    val stage: FolderSyncStage,
    val scannedFolders: Int = 0,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val failedFiles: Int = 0,
    val totalBytes: Long = 0L,
    val completedBytes: Long = 0L,
    val currentFile: String = "",
    val error: String? = null,
    val failures: List<SyncFailureDetail> = emptyList(),
) {
    val isActive: Boolean
        get() = stage in setOf(
            FolderSyncStage.QUEUED,
            FolderSyncStage.SCANNING,
            FolderSyncStage.DOWNLOADING,
            FolderSyncStage.RETRYING,
        )
}

private fun WorkInfo.toFileDownloadUi(): FileDownloadUiState? {
    val itemId = FileDownloadCoordinator.itemId(this) ?: return null
    val data = if (state.isFinished) outputData else progress
    val stage = data.getString(FileDownloadProgressKeys.STAGE)?.let { value ->
        runCatching { FileDownloadStage.valueOf(value) }.getOrNull()
    } ?: when (state) {
        WorkInfo.State.RUNNING -> FileDownloadStage.DOWNLOADING
        WorkInfo.State.SUCCEEDED -> FileDownloadStage.COMPLETE
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> FileDownloadStage.FAILED
        else -> FileDownloadStage.QUEUED
    }
    return FileDownloadUiState(
        workId = id,
        itemId = itemId,
        stage = stage,
        bytes = data.getLong(FileDownloadProgressKeys.BYTES, 0L),
        totalBytes = data.getLong(FileDownloadProgressKeys.TOTAL_BYTES, 0L),
        resumedBytes = data.getLong(FileDownloadProgressKeys.RESUMED_BYTES, 0L),
        error = data.getString(FileDownloadProgressKeys.ERROR),
    )
}

enum class CloudDrivePhase { RESTORING, LOGGED_OUT, AUTHENTICATING, TWO_FACTOR, PCS_APPROVAL, BROWSING }

private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "操作失败，请稍后重试"

private fun WorkInfo.toFolderSyncUi(
    folderId: String,
    folderName: String,
    displayPath: String,
): FolderSyncUiState {
    val data = if (state.isFinished) outputData else progress
    val reportedStage = data.getString(FolderSyncProgressKeys.STAGE)?.let { value ->
        runCatching { FolderSyncStage.valueOf(value) }.getOrNull()
    }
    val stage = reportedStage ?: when (state) {
        WorkInfo.State.SUCCEEDED -> FolderSyncStage.COMPLETE
        WorkInfo.State.FAILED,
        WorkInfo.State.CANCELLED,
        -> FolderSyncStage.FAILED
        WorkInfo.State.RUNNING -> FolderSyncStage.DOWNLOADING
        else -> FolderSyncStage.QUEUED
    }
    return FolderSyncUiState(
        workId = id,
        folderId = folderId,
        folderName = folderName,
        displayPath = displayPath,
        stage = stage,
        scannedFolders = data.getInt(FolderSyncProgressKeys.SCANNED_FOLDERS, 0),
        totalFiles = data.getInt(FolderSyncProgressKeys.TOTAL_FILES, 0),
        completedFiles = data.getInt(FolderSyncProgressKeys.COMPLETED_FILES, 0),
        failedFiles = data.getInt(FolderSyncProgressKeys.FAILED_FILES, 0),
        totalBytes = data.getLong(FolderSyncProgressKeys.TOTAL_BYTES, 0L),
        completedBytes = data.getLong(FolderSyncProgressKeys.COMPLETED_BYTES, 0L),
        currentFile = data.getString(FolderSyncProgressKeys.CURRENT_FILE).orEmpty(),
        error = when {
            state == WorkInfo.State.CANCELLED -> "同步已取消"
            else -> data.getString(FolderSyncProgressKeys.ERROR)
        },
    )
}

private const val PCS_MAX_ATTEMPTS = 30
private const val PCS_POLL_INTERVAL_MS = 10_000L

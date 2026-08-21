package com.faker1024.icloudsync.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
import com.faker1024.icloudsync.core.icloud.ICloudDriveRepository
import com.faker1024.icloudsync.core.icloud.ICloudApiException
import com.faker1024.icloudsync.core.icloud.ICloudError
import com.faker1024.icloudsync.core.icloud.ICloudFolder
import com.faker1024.icloudsync.core.icloud.ICloudLoginResult
import com.faker1024.icloudsync.core.icloud.ICloudPcsPollResult
import com.faker1024.icloudsync.core.icloud.TrustedPhone
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class CloudDriveViewModel @Inject constructor(
    private val repository: ICloudDriveRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CloudDriveUiState())
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()
    private var pcsApprovalJob: Job? = null

    init {
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
                    _state.update {
                        CloudDriveUiState(phase = CloudDrivePhase.LOGGED_OUT, error = error.userMessage())
                    }
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
        viewModelScope.launch {
            runCatching { repository.download(item) }
                .onSuccess { fileName -> _events.emit("已开始下载 $fileName，保存至 Download/iCloud Drive/") }
                .onFailure { error -> _events.emit(error.userMessage()) }
            _state.update { it.copy(downloadingIds = it.downloadingIds - item.id) }
        }
    }

    fun retryPcsApproval() = startPcsApproval()

    fun logout() {
        pcsApprovalJob?.cancel()
        pcsApprovalJob = null
        repository.logout()
        _state.value = CloudDriveUiState(phase = CloudDrivePhase.LOGGED_OUT)
        _events.tryEmit("已清除本机 iCloud 登录会话")
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
                        _state.value = CloudDriveUiState(phase = CloudDrivePhase.LOGGED_OUT, error = message)
                    } else {
                        _state.update { it.copy(isBusy = false, error = message) }
                    }
                }
        }
    }
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
)

enum class CloudDrivePhase { RESTORING, LOGGED_OUT, AUTHENTICATING, TWO_FACTOR, PCS_APPROVAL, BROWSING }

private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "操作失败，请稍后重试"

private const val PCS_MAX_ATTEMPTS = 30
private const val PCS_POLL_INTERVAL_MS = 10_000L

package com.faker1024.icloudsync.feature.main

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faker1024.icloudsync.core.database.ImportBatchEntity
import com.faker1024.icloudsync.core.database.ImportedMediaEntity
import com.faker1024.icloudsync.core.importer.ImportRepository
import com.faker1024.icloudsync.core.settings.ImportSettings
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: ImportRepository,
    private val settings: ImportSettings,
) : ViewModel() {
    val batches: StateFlow<List<ImportBatchEntity>> = repository.observeRecentBatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albumName: StateFlow<String> = settings.albumName
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ImportSettings.DEFAULT_ALBUM_NAME,
        )

    val selectedBatchId = MutableStateFlow<String?>(null)
    val selectedBatchItems: StateFlow<List<ImportedMediaEntity>> = selectedBatchId
        .flatMapLatest { batchId ->
            if (batchId == null) flowOf(emptyList()) else repository.observeBatchItems(batchId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val eventChannel = Channel<MainEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

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
}

sealed interface MainEvent {
    data class Message(val text: String) : MainEvent
}

package com.faker1024.icloudsync.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faker1024.icloudsync.BuildConfig
import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
import com.faker1024.icloudsync.core.database.ImportBatchEntity
import com.faker1024.icloudsync.core.database.ImportedMediaEntity
import com.faker1024.icloudsync.domain.model.ImportBatchState
import com.faker1024.icloudsync.domain.model.ImportedMediaState
import com.faker1024.icloudsync.domain.model.isFinished
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class MainSection(val label: String, val symbol: String) {
    CLOUD_DRIVE("云盘", "☁"),
    IMPORT("导入", "⇩"),
    HISTORY("历史", "↻"),
    SETTINGS("设置", "⚙"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    cloudDriveViewModel: CloudDriveViewModel,
    onSyncFolder: (ICloudDriveItem) -> Unit,
    onSelectFile: () -> Unit,
) {
    val batches by viewModel.batches.collectAsStateWithLifecycle()
    val albumName by viewModel.albumName.collectAsStateWithLifecycle()
    val selectedBatchId by viewModel.selectedBatchId.collectAsStateWithLifecycle()
    val selectedBatchItems by viewModel.selectedBatchItems.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(MainSection.CLOUD_DRIVE) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MainEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(sectionTitle(section)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                MainSection.entries.forEach { item ->
                    NavigationBarItem(
                        selected = section == item,
                        onClick = { section = item },
                        icon = { Text(item.symbol, style = MaterialTheme.typography.titleMedium) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (section) {
            MainSection.CLOUD_DRIVE -> CloudDrivePage(
                viewModel = cloudDriveViewModel,
                modifier = Modifier.padding(padding),
                onMessage = { message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
                onSyncFolder = onSyncFolder,
            )

            MainSection.IMPORT -> ImportPage(
                modifier = Modifier.padding(padding),
                batches = batches,
                onSelectFile = onSelectFile,
                onCancel = viewModel::cancel,
            )

            MainSection.HISTORY -> HistoryPage(
                modifier = Modifier.padding(padding),
                batches = batches,
                onDelete = viewModel::deleteHistory,
                selectedBatchId = selectedBatchId,
                selectedItems = selectedBatchItems,
                onShowDetails = viewModel::showBatchDetails,
                onCloseDetails = viewModel::closeBatchDetails,
                onSelectFile = onSelectFile,
            )

            MainSection.SETTINGS -> SettingsPage(
                modifier = Modifier.padding(padding),
                albumName = albumName,
                onSaveAlbumName = viewModel::saveAlbumName,
                onClearICloudSession = {
                    cloudDriveViewModel.logout()
                    scope.launch { snackbarHostState.showSnackbar("已清除本机 iCloud 登录会话") }
                },
            )
        }
    }
}

@Composable
private fun ImportPage(
    modifier: Modifier,
    batches: List<ImportBatchEntity>,
    onSelectFile: () -> Unit,
    onCancel: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "云盘下载的所有文件会保留在 Download/iCloud Drive/。如需让照片或视频出现在系统相册，可在这里继续导入。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            Button(
                onClick = onSelectFile,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("选择照片、视频或 ZIP 导入相册")
            }
        }
        if (batches.isEmpty()) {
            item {
                EmptyHistoryCard()
            }
        } else {
            item {
                Text("最近任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(batches.take(5), key = { it.id }) { batch ->
                ImportBatchCard(batch = batch, onCancel = { onCancel(batch.id) })
            }
        }
    }
}

@Composable
private fun ImportBatchCard(batch: ImportBatchEntity, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    batch.sourceDisplayName ?: "下载文件",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(statusText(batch.state), color = statusColor(batch.state))
            }
            val progress = batchProgress(batch)
            if (!batch.state.isFinished()) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "新增 ${batch.importedCount} · 重复 ${batch.duplicateCount} · " +
                    "失败 ${batch.failedCount} · 不支持 ${batch.unsupportedCount}",
                style = MaterialTheme.typography.bodySmall,
            )
            val progressText = when {
                batch.totalCount > 0 -> "${batch.processedCount}/${batch.totalCount} 项"
                batch.totalBytes != null -> "${formatBytes(batch.processedBytes)}/${formatBytes(batch.totalBytes)}"
                else -> "正在准备"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(progressText, style = MaterialTheme.typography.labelMedium)
                if (!batch.state.isFinished()) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun HistoryPage(
    modifier: Modifier,
    batches: List<ImportBatchEntity>,
    onDelete: (String) -> Unit,
    selectedBatchId: String?,
    selectedItems: List<ImportedMediaEntity>,
    onShowDetails: (String) -> Unit,
    onCloseDetails: () -> Unit,
    onSelectFile: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ImportBatchEntity?>(null) }
    if (selectedBatchId != null) {
        val selectedBatch = batches.firstOrNull { it.id == selectedBatchId }
        AlertDialog(
            onDismissRequest = onCloseDetails,
            title = { Text(selectedBatch?.sourceDisplayName ?: "导入详情") },
            text = {
                if (selectedItems.isEmpty()) {
                    Text("当前任务还没有文件明细。")
                } else {
                    LazyColumn(
                        modifier = Modifier.height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(selectedItems, key = { it.id }) { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.displayName, maxLines = 2, fontWeight = FontWeight.Medium)
                                Text(
                                    mediaStateText(item.state) +
                                        (item.errorCode?.let { " · ${errorText(it)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (item.state == ImportedMediaState.FAILED) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onCloseDetails) { Text("关闭") }
            },
        )
    }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("清除导入记录？") },
            text = { Text("只会删除这条历史和统计信息，不会删除系统相册中的照片或原始 ZIP。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(pendingDelete!!.id)
                    pendingDelete = null
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
    if (batches.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有导入记录")
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(batches, key = { it.id }) { batch ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(batch.sourceDisplayName ?: "下载文件", fontWeight = FontWeight.SemiBold)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(batch.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${statusText(batch.state)} · 新增 ${batch.importedCount} · 重复 ${batch.duplicateCount} · " +
                            "失败 ${batch.failedCount + batch.unsupportedCount}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    batch.errorCode?.let {
                        Text("错误：${errorText(it)}", color = MaterialTheme.colorScheme.error)
                    }
                    Row(modifier = Modifier.align(Alignment.End)) {
                        if (
                            batch.state == ImportBatchState.FAILED ||
                            batch.state == ImportBatchState.PARTIAL_FAILED ||
                            batch.state == ImportBatchState.CANCELLED
                        ) {
                            TextButton(onClick = onSelectFile) { Text("重新选择文件") }
                        }
                        TextButton(onClick = { onShowDetails(batch.id) }) { Text("查看详情") }
                        if (batch.state.isFinished()) {
                            TextButton(onClick = { pendingDelete = batch }) { Text("清除记录") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    modifier: Modifier,
    albumName: String,
    onSaveAlbumName: (String) -> Unit,
    onClearICloudSession: () -> Unit,
) {
    var value by remember(albumName) { mutableStateOf(albumName) }
    var showClearSessionDialog by remember { mutableStateOf(false) }
    if (showClearSessionDialog) {
        AlertDialog(
            onDismissRequest = { showClearSessionDialog = false },
            title = { Text("退出 iCloud 登录？") },
            text = { Text("将清除 App 加密保存的 iCloud 会话令牌和 Cookie。已经下载到设备的文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearICloudSession()
                    showClearSessionDialog = false
                }) { Text("清除并退出") }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionDialog = false }) { Text("取消") }
            },
        )
    }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("云盘下载位置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("所有文件按原格式保存到系统公共目录 Download/iCloud Drive/，可在系统“文件”或“下载”中查看。")
        Text("照片和视频不会自动写入 DCIM；需要进入系统相册时，再使用“导入”功能。")
        HorizontalDivider()
        Text("照片导入位置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.take(64) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("相册名称") },
            supportingText = { Text("媒体将保存到 DCIM/${value.ifBlank { "iCloud Photos" }}/") },
            singleLine = true,
        )
        Button(onClick = { onSaveAlbumName(value) }, modifier = Modifier.align(Alignment.End)) {
            Text("保存")
        }
        HorizontalDivider()
        Text("隐私与安全", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("App 使用自己编写的登录和云盘界面，后台固定连接 iCloud 中国区服务，不打开或嵌入网页。")
        Text("密码只在内存中用于 SRP 登录证明，不落盘、不明文发送；验证码不保存。会话令牌和 Cookie 使用 Android Keystore 加密后保存在本机。")
        Text("Apple 未提供访问整个 iCloud Drive 的公开 Android API，因此这里使用网页私有接口；Apple 调整接口后可能需要升级 App。")
        OutlinedButton(
            onClick = { showClearSessionDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("清除 iCloud 登录数据")
        }
        Text("本产品为独立第三方工具，与 Apple Inc. 无关联或授权关系。")
        Spacer(Modifier.height(8.dp))
        Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyHistoryCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("还没有导入任务", fontWeight = FontWeight.SemiBold)
            Text("先在“云盘”中登录 iCloud 中国区并下载文件，然后返回这里选择照片、视频或 ZIP。")
        }
    }
}

private fun sectionTitle(section: MainSection): String = when (section) {
    MainSection.CLOUD_DRIVE -> "iCloud 中国区云盘"
    MainSection.IMPORT -> "照片导入（可选）"
    MainSection.HISTORY -> "导入历史"
    MainSection.SETTINGS -> "设置"
}

private fun statusText(state: ImportBatchState): String = when (state) {
    ImportBatchState.QUEUED -> "等待中"
    ImportBatchState.STAGING -> "正在读取"
    ImportBatchState.PREFLIGHT -> "正在校验"
    ImportBatchState.SCANNING -> "正在扫描"
    ImportBatchState.IMPORTING -> "正在导入"
    ImportBatchState.COMPLETED -> "已完成"
    ImportBatchState.PARTIAL_FAILED -> "部分完成"
    ImportBatchState.FAILED -> "失败"
    ImportBatchState.CANCELLED -> "已取消"
}

@Composable
private fun statusColor(state: ImportBatchState) = when (state) {
    ImportBatchState.FAILED -> MaterialTheme.colorScheme.error
    ImportBatchState.PARTIAL_FAILED -> MaterialTheme.colorScheme.tertiary
    ImportBatchState.COMPLETED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun batchProgress(batch: ImportBatchEntity): Float {
    val raw = if (batch.totalCount > 0) {
        batch.processedCount.toFloat() / batch.totalCount
    } else {
        val totalBytes = batch.totalBytes ?: return 0f
        if (totalBytes <= 0) 0f else batch.processedBytes.toFloat() / totalBytes
    }
    return raw.coerceIn(0f, 1f)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    do {
        value /= 1024.0
        index++
    } while (value >= 1024 && index < units.lastIndex)
    val rounded = (value * 10).roundToInt() / 10.0
    return "$rounded ${units[index]}"
}

private fun errorText(errorCode: String): String = when (errorCode) {
    "SOURCE_UNREADABLE" -> "无法读取所选文件"
    "SOURCE_INCOMPLETE" -> "下载文件不完整或已损坏"
    "UNSUPPORTED_ARCHIVE" -> "ZIP 中没有可导入内容"
    "ENCRYPTED_ARCHIVE" -> "暂不支持带密码的 ZIP"
    "UNSAFE_ARCHIVE_PATH" -> "ZIP 包含不安全路径"
    "TOO_MANY_ENTRIES" -> "ZIP 文件数量过多"
    "NO_SPACE" -> "设备存储空间不足"
    "UNSUPPORTED_MEDIA" -> "没有发现支持的媒体文件"
    "MEDIASTORE_WRITE_FAILED" -> "无法保存到系统相册"
    "USER_CANCELLED" -> "用户取消"
    else -> "处理失败，请重试"
}

private fun mediaStateText(state: ImportedMediaState): String = when (state) {
    ImportedMediaState.IMPORTED -> "已导入"
    ImportedMediaState.DUPLICATE -> "已跳过重复文件"
    ImportedMediaState.FAILED -> "导入失败"
    ImportedMediaState.UNSUPPORTED -> "不支持的格式"
}

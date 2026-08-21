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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
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

private enum class MainSection(val label: String) {
    CLOUD_DRIVE("云盘"),
    LOCAL_FILES("本地"),
    IMPORT("导入"),
    HISTORY("历史"),
    SETTINGS("设置"),
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
    val syncedFiles by viewModel.syncedFiles.collectAsStateWithLifecycle()
    val localBrowserPreferences by viewModel.localBrowserPreferences.collectAsStateWithLifecycle()
    val privateStorage by viewModel.privateStorage.collectAsStateWithLifecycle()
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
    LaunchedEffect(section) {
        if (section == MainSection.LOCAL_FILES) viewModel.refreshSyncedFiles()
        if (section == MainSection.SETTINGS) viewModel.refreshPrivateStorageStatus()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        sectionTitle(section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                shadowElevation = 10.dp,
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                        MainSection.entries.forEach { item ->
                            NavigationBarItem(
                                selected = section == item,
                                onClick = { section = item },
                                icon = {
                                    Icon(
                                        mainSectionIcon(item),
                                        contentDescription = item.label,
                                        modifier = Modifier.size(23.dp),
                                    )
                                },
                                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = Color.Transparent,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
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

            MainSection.LOCAL_FILES -> SyncedFilesPage(
                modifier = Modifier.padding(padding),
                state = syncedFiles,
                preferences = localBrowserPreferences,
                onRefresh = viewModel::refreshSyncedFiles,
                onOpenFile = viewModel::openSyncedFile,
                onShareFile = viewModel::shareSyncedFile,
                onSetLayout = viewModel::setLocalBrowserLayout,
                onSetSorting = viewModel::setLocalSorting,
                loadImage = viewModel::loadSyncedImage,
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
                privateStorage = privateStorage,
                onSaveAlbumName = viewModel::saveAlbumName,
                onMigratePublicFiles = viewModel::migratePublicFiles,
                onCancelMigration = viewModel::cancelPublicFileMigration,
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
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            IosGroupedSurface {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IosIconTile(Icons.Rounded.PhotoLibrary, contentDescription = null)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("导入系统相册", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "将已下载的照片、视频或 ZIP 安全导入 DCIM，原始云盘文件保持不变。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            IosPrimaryButton(
                onClick = onSelectFile,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("选择文件并导入")
            }
        }
        if (batches.isEmpty()) {
            item {
                EmptyHistoryCard()
            }
        } else {
            item {
                IosSectionHeader("最近任务")
            }
            items(batches.take(5), key = { it.id }) { batch ->
                ImportBatchCard(batch = batch, onCancel = { onCancel(batch.id) })
            }
        }
    }
}

@Composable
private fun ImportBatchCard(batch: ImportBatchEntity, onCancel: () -> Unit) {
    IosGroupedSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IosIconTile(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null)
                    Text(
                        batch.sourceDisplayName ?: "下载文件",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                IosStatusPill(statusText(batch.state), statusColor(batch.state))
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
        Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            IosHero(
                title = "暂无导入记录",
                message = "完成照片或视频导入后，任务状态会显示在这里。",
                icon = Icons.Rounded.History,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(batches, key = { it.id }) { batch ->
            IosGroupedSurface {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IosIconTile(Icons.Rounded.History, contentDescription = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(batch.sourceDisplayName ?: "下载文件", fontWeight = FontWeight.SemiBold)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(batch.createdAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IosStatusPill(statusText(batch.state), statusColor(batch.state))
                    }
                    Text(
                        "新增 ${batch.importedCount} · 重复 ${batch.duplicateCount} · " +
                            "失败 ${batch.failedCount + batch.unsupportedCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            TextButton(onClick = { pendingDelete = batch }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("清除")
                            }
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
    privateStorage: PrivateStorageUiState,
    onSaveAlbumName: (String) -> Unit,
    onMigratePublicFiles: () -> Unit,
    onCancelMigration: () -> Unit,
    onClearICloudSession: () -> Unit,
) {
    var value by remember(albumName) { mutableStateOf(albumName) }
    var showClearSessionDialog by remember { mutableStateOf(false) }
    var showMigrationDialog by remember { mutableStateOf(false) }
    if (showMigrationDialog) {
        AlertDialog(
            onDismissRequest = { showMigrationDialog = false },
            title = { Text("迁移到私密存储？") },
            text = {
                Text(
                    "将迁移 ${privateStorage.publicFileCount} 个公共文件" +
                        "（${formatBytes(privateStorage.publicBytes)}）。每个文件复制并校验成功后，" +
                        "才会删除 Download/iCloud Drive/ 中的对应公共文件。请确认该目录中的文件都需要迁移。\n\n" +
                        "私密文件不会被 QQ、微信或系统相册扫描，但卸载本 APP 时也会被删除。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMigrationDialog = false
                    onMigratePublicFiles()
                }) { Text("开始迁移") }
            },
            dismissButton = {
                TextButton(onClick = { showMigrationDialog = false }) { Text("取消") }
            },
        )
    }
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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosSectionHeader("文件存储")
        IosGroupedSurface {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                IosIconTile(Icons.Rounded.Folder, contentDescription = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("iCloud Drive 私密存储", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "新下载和同步的文件只保存在 APP 内，不写入公共 Download 或 MediaStore，其他软件无法主动扫描。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "只有主动选择“打开”“分享”或导入相册时，指定文件才会获得临时访问权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        privateStorage.isScanning -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                            Text("正在检查旧版公共文件…", style = MaterialTheme.typography.bodySmall)
                        }
                        privateStorage.isMigrating -> {
                            val total = privateStorage.migrationTotal.coerceAtLeast(1)
                            LinearProgressIndicator(
                                progress = { privateStorage.migrationCompleted.toFloat() / total },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                            Text(
                                "已完成 ${privateStorage.migrationCompleted}/${privateStorage.migrationTotal}" +
                                    if (privateStorage.migrationFailed > 0) {
                                        " · 暂时失败 ${privateStorage.migrationFailed}"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            privateStorage.currentFile.takeIf(String::isNotBlank)?.let {
                                Text(
                                    it,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = onCancelMigration) { Text("取消迁移") }
                        }
                        privateStorage.publicFileCount > 0 -> {
                            Text(
                                "检测到 ${privateStorage.publicFileCount} 个旧版公共文件" +
                                    "（${formatBytes(privateStorage.publicBytes)}）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(
                                onClick = { showMigrationDialog = true },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            ) { Text("迁移并清理公共副本") }
                        }
                        else -> {
                            Text(
                                "没有检测到可被其他软件扫描的旧版公共副本。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    privateStorage.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        IosSectionHeader("照片导入")
        IosGroupedSurface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IosIconTile(Icons.Rounded.PhotoLibrary, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("目标相册", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "DCIM/${value.ifBlank { "iCloud Photos" }}/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(64) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("相册名称") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                IosPrimaryButton(
                    onClick = { onSaveAlbumName(value) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("保存相册设置") }
            }
        }

        Spacer(Modifier.height(8.dp))
        IosSectionHeader("隐私与安全")
        IosGroupedSurface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    IosIconTile(Icons.Rounded.Lock, contentDescription = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("仅在本机处理", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "密码只用于设备端 SRP 登录证明，不落盘、不明文发送；会话令牌由 Android Keystore 加密。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "App 使用原生界面直连 iCloud 中国区私有网页接口，不嵌入网页，也不经过开发者服务器。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { showClearSessionDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(8.dp))
            Text("清除 iCloud 登录数据", color = MaterialTheme.colorScheme.error)
        }
        Text(
            "独立第三方工具，与 Apple Inc. 无关联或授权关系。\n版本 ${BuildConfig.VERSION_NAME}",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyHistoryCard() {
    IosGroupedSurface {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IosIconTile(Icons.Rounded.History, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("还没有导入任务", fontWeight = FontWeight.SemiBold)
                Text(
                    "先从云盘下载文件，再返回这里选择照片、视频或 ZIP。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sectionTitle(section: MainSection): String = when (section) {
    MainSection.CLOUD_DRIVE -> "iCloud 中国区云盘"
    MainSection.LOCAL_FILES -> "本地文件"
    MainSection.IMPORT -> "照片导入（可选）"
    MainSection.HISTORY -> "导入历史"
    MainSection.SETTINGS -> "设置"
}

private fun mainSectionIcon(section: MainSection): ImageVector = when (section) {
    MainSection.CLOUD_DRIVE -> Icons.Rounded.Cloud
    MainSection.LOCAL_FILES -> Icons.Rounded.Folder
    MainSection.IMPORT -> Icons.Rounded.PhotoLibrary
    MainSection.HISTORY -> Icons.Rounded.History
    MainSection.SETTINGS -> Icons.Rounded.Settings
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

package com.faker1024.icloudsync.feature.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Tune
import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
import com.faker1024.icloudsync.core.icloud.TrustedPhone
import com.faker1024.icloudsync.core.icloud.isPreviewableImage
import com.faker1024.icloudsync.core.icloud.sortCloudDriveItems
import com.faker1024.icloudsync.core.settings.CloudBrowserLayout
import com.faker1024.icloudsync.core.settings.CloudSortDirection
import com.faker1024.icloudsync.core.settings.CloudSortField
import com.faker1024.icloudsync.core.settings.MAX_ICON_SIZE
import com.faker1024.icloudsync.core.settings.MIN_ICON_SIZE
import com.faker1024.icloudsync.core.sync.FolderSyncStage
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

@Composable
fun CloudDrivePage(
    viewModel: CloudDriveViewModel,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit,
    onSyncFolder: (ICloudDriveItem) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.events.collect(onMessage) }
    BackHandler(enabled = state.phase == CloudDrivePhase.BROWSING && state.path.size > 1) {
        viewModel.navigateBack()
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (state.phase) {
            CloudDrivePhase.RESTORING -> LoadingPage("正在恢复加密登录会话…")
            CloudDrivePhase.LOGGED_OUT,
            CloudDrivePhase.AUTHENTICATING,
            -> LoginPage(state, viewModel::login, viewModel::clearError)
            CloudDrivePhase.TWO_FACTOR -> TwoFactorPage(
                state = state,
                onVerify = viewModel::verifyCode,
                onTrustedDevice = viewModel::resendTrustedDeviceCode,
                onSms = viewModel::requestSmsCode,
                onCancel = viewModel::logout,
                onClearError = viewModel::clearError,
            )
            CloudDrivePhase.PCS_APPROVAL -> PcsApprovalPage(
                state = state,
                onRetry = viewModel::retryPcsApproval,
                onCancel = viewModel::logout,
                onClearError = viewModel::clearError,
            )
            CloudDrivePhase.BROWSING -> BrowserPage(
                state = state,
                onBack = viewModel::navigateBack,
                onRefresh = viewModel::refresh,
                onOpen = viewModel::openFolder,
                onDownload = viewModel::download,
                onSyncFolder = onSyncFolder,
                onCancelSync = viewModel::cancelFolderSync,
                onSetLayout = viewModel::setLayout,
                onSetIconSize = viewModel::setIconSize,
                onSetSorting = viewModel::setSorting,
                loadPreview = viewModel::loadImagePreview,
                onLogout = viewModel::logout,
                onClearError = viewModel::clearError,
            )
        }
    }
}

@Composable
private fun PcsApprovalPage(
    state: CloudDriveUiState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IosHero(
            title = "批准云盘访问",
            message = "此账户启用了高级数据保护，需要在受信任设备上批准临时访问。",
            icon = Icons.Rounded.Security,
        )
        IosSectionHeader("在受信任设备上操作")
        IosGroupedSurface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PcsStep(1, "打开“设置 → Apple 账户 → iCloud → iCloud.com”")
                PcsStep(2, "确认“允许访问 iCloud 数据”已开启，高级数据保护可以保持开启")
                PcsStep(3, "收到提示后，批准本次 iCloud 数据访问")
            }
        }
        IosGroupedSurface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IosIconTile(Icons.Rounded.Devices, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(state.pcsMessage, fontWeight = FontWeight.Medium)
                        if (state.pcsAttempt > 0) {
                            Text(
                                "已检查 ${state.pcsAttempt}/30 次 · 每 10 秒自动检查",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.error?.let { ErrorCard(it, onClearError) }
        IosPrimaryButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("我已批准，立即检查")
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("取消并退出登录")
        }
    }
}

@Composable
private fun LoginPage(
    state: CloudDriveUiState,
    onLogin: (String, String) -> Unit,
    onClearError: () -> Unit,
) {
    var account by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IosHero(
            title = "登录 iCloud",
            message = "使用中国大陆 Apple 账户，直接连接 iCloud 中国区服务。",
            icon = Icons.Rounded.Cloud,
        )
        IosSectionHeader("账户")
        IosGroupedSurface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it.take(160); onClearError() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Apple 账户") },
                    placeholder = { Text("name@example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    enabled = !state.isBusy,
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(256); onClearError() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !state.isBusy,
                    shape = MaterialTheme.shapes.medium,
                )
            }
        }
        IosGroupedSurface {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.isBusy) { accepted = !accepted }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(checked = accepted, onCheckedChange = { accepted = it }, enabled = !state.isBusy)
                Text(
                    "我了解本工具使用 Apple 未公开的网页接口；接口变化可能影响可用性，账户数据仅在本机处理。",
                    modifier = Modifier.padding(top = 10.dp, end = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.error?.let { ErrorCard(it, onClearError) }
        IosPrimaryButton(
            onClick = {
                onLogin(account, password)
                password = ""
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = accepted && account.isNotBlank() && password.isNotBlank() && !state.isBusy,
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("登录")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
            Text(
                "密码通过 SRP 在本机生成登录证明，不会明文发送或保存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TwoFactorPage(
    state: CloudDriveUiState,
    onVerify: (String) -> Unit,
    onTrustedDevice: () -> Unit,
    onSms: (TrustedPhone) -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IosHero(
            title = "双重认证",
            message = "输入发送到受信任设备或手机号的 6 位验证码。",
            icon = Icons.Rounded.Key,
        )
        IosGroupedSurface {
            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.filter(Char::isDigit).take(6); onClearError() },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                label = { Text("6 位验证码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                enabled = !state.isBusy,
                shape = MaterialTheme.shapes.medium,
            )
        }
        state.error?.let { ErrorCard(it, onClearError) }
        IosPrimaryButton(
            onClick = { onVerify(code); code = "" },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = code.length == 6 && !state.isBusy,
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            }
            else Text("验证并进入云盘")
        }
        IosGroupedSurface {
            Column(Modifier.padding(vertical = 4.dp)) {
                TextButton(
                    onClick = onTrustedDevice,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isBusy,
                ) {
                    Icon(Icons.Rounded.Devices, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("重新发送到受信任设备")
                }
                state.phones.forEach { phone ->
                    TextButton(
                        onClick = { onSms(phone) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isBusy,
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("发送短信到 ${phone.label}")
                    }
                }
            }
        }
        TextButton(
            onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
        ) { Text("取消登录") }
    }
}

@Composable
private fun BrowserPage(
    state: CloudDriveUiState,
    onBack: () -> Boolean,
    onRefresh: () -> Unit,
    onOpen: (ICloudDriveItem) -> Unit,
    onDownload: (ICloudDriveItem) -> Unit,
    onSyncFolder: (ICloudDriveItem) -> Unit,
    onCancelSync: () -> Unit,
    onSetLayout: (CloudBrowserLayout) -> Unit,
    onSetIconSize: (Float) -> Unit,
    onSetSorting: (CloudSortField, CloudSortDirection) -> Unit,
    loadPreview: suspend (ICloudDriveItem, Int) -> android.graphics.Bitmap,
    onLogout: () -> Unit,
    onClearError: () -> Unit,
) {
    var pendingSyncFolder by remember { mutableStateOf<ICloudDriveItem?>(null) }
    var previewItem by remember { mutableStateOf<ICloudDriveItem?>(null) }
    var showIconSizeDialog by rememberSaveable { mutableStateOf(false) }
    var pendingIconSize by rememberSaveable { mutableFloatStateOf(state.iconSize) }
    var showSortDialog by rememberSaveable { mutableStateOf(false) }
    var pendingSortField by rememberSaveable { mutableStateOf(state.sortField) }
    var pendingSortDirection by rememberSaveable { mutableStateOf(state.sortDirection) }
    val displayedItems = remember(state.items, state.sortField, state.sortDirection) {
        sortCloudDriveItems(state.items, state.sortField, state.sortDirection)
    }

    pendingSyncFolder?.let { folder ->
        val relative = state.path.drop(1).map { it.name } + folder.name
        val destination = "Download/iCloud Drive/${relative.joinToString("/")}/"
        AlertDialog(
            onDismissRequest = { pendingSyncFolder = null },
            title = { Text("同步“${folder.name}”到本地？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("将递归下载文件夹内的全部文件，并保留云端目录层级。")
                    Text(destination, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "文件会先完整写入并校验字节数，再对系统可见；失败项会自动重试，已校验文件不会重复写入。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSyncFolder(folder)
                    pendingSyncFolder = null
                }) { Text("开始同步") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSyncFolder = null }) { Text("取消") }
            },
        )
    }
    if (showIconSizeDialog) {
        AlertDialog(
            onDismissRequest = { showIconSizeDialog = false },
            title = { Text("调整图标和缩略图大小") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${pendingIconSize.roundToInt()} dp")
                    Slider(
                        value = pendingIconSize,
                        onValueChange = { pendingIconSize = it },
                        valueRange = MIN_ICON_SIZE..MAX_ICON_SIZE,
                        steps = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetIconSize(pendingIconSize)
                    showIconSizeDialog = false
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { showIconSizeDialog = false }) { Text("取消") }
            },
        )
    }
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("排序方式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CloudSortField.entries.forEach { field ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { pendingSortField = field },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = pendingSortField == field,
                                onClick = { pendingSortField = field },
                            )
                            Text(sortFieldLabel(field))
                        }
                    }
                    Text(
                        "排列顺序",
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    CloudSortDirection.entries.forEach { direction ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { pendingSortDirection = direction },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = pendingSortDirection == direction,
                                onClick = { pendingSortDirection = direction },
                            )
                            Text(sortDirectionLabel(direction, pendingSortField))
                        }
                    }
                    Text(
                        "文件夹始终显示在文件前面",
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetSorting(pendingSortField, pendingSortDirection)
                    showSortDialog = false
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { showSortDialog = false }) { Text("取消") }
            },
        )
    }
    previewItem?.let { item ->
        ImagePreviewDialog(
            item = item,
            loadPreview = loadPreview,
            onDismiss = { previewItem = null },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.path.size > 1) {
                IconButton(onClick = { onBack() }, enabled = !state.isBusy) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
            } else {
                IosIconTile(Icons.Rounded.Cloud, contentDescription = null)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    state.path.lastOrNull()?.name ?: "iCloud Drive",
                    maxLines = 1,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${state.items.size} 项 · ${sortFieldShortLabel(state.sortField)}${sortDirectionArrow(state.sortDirection)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = onRefresh, enabled = !state.isBusy) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = onLogout, enabled = !state.isBusy) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "退出登录", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.path.forEachIndexed { index, folder ->
                if (index > 0) {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Text(
                    folder.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (index == state.path.lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                )
            }
        }
        IosGroupedSurface(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Download/iCloud Drive/",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                CloudToolbarAction(
                    icon = Icons.AutoMirrored.Rounded.Sort,
                    label = "排序",
                    onClick = {
                        pendingSortField = state.sortField
                        pendingSortDirection = state.sortDirection
                        showSortDialog = true
                    },
                )
                CloudToolbarAction(
                    icon = if (state.layout == CloudBrowserLayout.LIST) {
                        Icons.Rounded.GridView
                    } else {
                        Icons.AutoMirrored.Rounded.ViewList
                    },
                    label = if (state.layout == CloudBrowserLayout.LIST) "网格" else "列表",
                    onClick = {
                        onSetLayout(
                            if (state.layout == CloudBrowserLayout.LIST) CloudBrowserLayout.GRID
                            else CloudBrowserLayout.LIST,
                        )
                    },
                )
                CloudToolbarAction(
                    icon = Icons.Rounded.Tune,
                    label = "大小",
                    onClick = {
                        pendingIconSize = state.iconSize
                        showIconSizeDialog = true
                    },
                )
            }
        }
        state.folderSync?.let { sync ->
            FolderSyncStatusCard(sync = sync, onCancel = onCancelSync)
        }
        if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { ErrorCard(it, onClearError, Modifier.padding(horizontal = 12.dp)) }
        if (state.items.isEmpty() && !state.isBusy) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                IosHero(
                    title = "这个文件夹是空的",
                    message = "这里暂时没有文件或子文件夹。",
                    icon = Icons.Rounded.Folder,
                )
            }
        } else {
            if (state.layout == CloudBrowserLayout.LIST) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listItems(displayedItems, key = ICloudDriveItem::id) { item ->
                        DriveItemRow(
                            item = item,
                            iconSize = state.iconSize,
                            downloading = item.id in state.downloadingIds,
                            syncing = state.folderSync?.let { it.folderId == item.id && it.isActive } == true,
                            onOpen = { onOpen(item) },
                            onPreview = { previewItem = item },
                            onLongPressFolder = { pendingSyncFolder = item },
                            onDownload = { onDownload(item) },
                            loadPreview = loadPreview,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive((state.iconSize + 72f).dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(displayedItems, key = ICloudDriveItem::id) { item ->
                        DriveItemGridCell(
                            item = item,
                            iconSize = state.iconSize,
                            downloading = item.id in state.downloadingIds,
                            syncing = state.folderSync?.let { it.folderId == item.id && it.isActive } == true,
                            onOpen = { onOpen(item) },
                            onPreview = { previewItem = item },
                            onLongPressFolder = { pendingSyncFolder = item },
                            onDownload = { onDownload(item) },
                            loadPreview = loadPreview,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveItemRow(
    item: ICloudDriveItem,
    iconSize: Float,
    downloading: Boolean,
    syncing: Boolean,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onLongPressFolder: () -> Unit,
    onDownload: () -> Unit,
    loadPreview: suspend (ICloudDriveItem, Int) -> android.graphics.Bitmap,
) {
    val canPreview = !item.isFolder && isPreviewableImage(item.name)
    IosGroupedSurface(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            enabled = item.isFolder || canPreview,
            onClick = { if (item.isFolder) onOpen() else onPreview() },
            onLongClick = { if (item.isFolder) onLongPressFolder() },
            onLongClickLabel = if (item.isFolder) "同步此文件夹到本地" else null,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DriveItemVisual(item, iconSize, loadPreview)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.name, fontWeight = FontWeight.Medium, maxLines = 2)
                Text(
                    if (item.isFolder) {
                        "${item.childCount} 项${if (syncing) " · 正在同步" else " · 长按同步"}"
                    } else {
                        fileDetails(item)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.isFolder) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = "打开文件夹",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = onDownload, enabled = !downloading) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(if (downloading) "准备中" else "下载")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveItemGridCell(
    item: ICloudDriveItem,
    iconSize: Float,
    downloading: Boolean,
    syncing: Boolean,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onLongPressFolder: () -> Unit,
    onDownload: () -> Unit,
    loadPreview: suspend (ICloudDriveItem, Int) -> android.graphics.Bitmap,
) {
    val canPreview = !item.isFolder && isPreviewableImage(item.name)
    IosGroupedSurface(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            enabled = item.isFolder || canPreview,
            onClick = { if (item.isFolder) onOpen() else onPreview() },
            onLongClick = { if (item.isFolder) onLongPressFolder() },
            onLongClickLabel = if (item.isFolder) "同步此文件夹到本地" else null,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DriveItemVisual(item, iconSize, loadPreview)
            Text(item.name, fontWeight = FontWeight.Medium, maxLines = 2)
            Text(
                if (item.isFolder) {
                    "${item.childCount} 项${if (syncing) " · 同步中" else ""}"
                } else {
                    fileDetails(item)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (!item.isFolder) {
                TextButton(onClick = onDownload, enabled = !downloading) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(if (downloading) "准备中" else "下载")
                }
            } else {
                Text(
                    "长按同步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DriveItemVisual(
    item: ICloudDriveItem,
    iconSize: Float,
    loadPreview: suspend (ICloudDriveItem, Int) -> android.graphics.Bitmap,
) {
    val rounded = RoundedCornerShape((iconSize / 6f).dp)
    Box(
        modifier = Modifier
            .size(iconSize.dp)
            .clip(rounded)
            .background(
                if (item.isFolder) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!item.isFolder && isPreviewableImage(item.name)) {
            RemoteImage(
                item = item,
                targetPixels = (iconSize * 3f).roundToInt(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loadPreview = loadPreview,
                fallback = {
                    Icon(
                        Icons.Rounded.Image,
                        contentDescription = null,
                        tint = fileIconTint(item),
                        modifier = Modifier.size((iconSize * 0.54f).dp),
                    )
                },
            )
        } else {
            Icon(
                fileIcon(item),
                contentDescription = null,
                tint = fileIconTint(item),
                modifier = Modifier.size((iconSize * 0.56f).dp),
            )
        }
    }
}

@Composable
private fun FolderSyncStatusCard(sync: FolderSyncUiState, onCancel: () -> Unit) {
    val failed = sync.stage == FolderSyncStage.FAILED
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IosIconTile(
                    icon = when {
                        failed -> Icons.Rounded.ErrorOutline
                        sync.stage == FolderSyncStage.COMPLETE -> Icons.Rounded.CheckCircle
                        else -> Icons.Rounded.CloudDownload
                    },
                    contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(sync.folderName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        syncStageText(sync.stage),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (sync.isActive) TextButton(onClick = onCancel) { Text("取消") }
            }
            if (sync.isActive) {
                if (sync.totalFiles > 0) {
                    LinearProgressIndicator(
                        progress = { (sync.completedFiles.toFloat() / sync.totalFiles).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            val countText = when (sync.stage) {
                FolderSyncStage.SCANNING -> "已扫描 ${sync.scannedFolders} 个文件夹，发现 ${sync.totalFiles} 个文件"
                FolderSyncStage.QUEUED -> "等待网络和后台执行条件"
                else -> "已完成 ${sync.completedFiles}/${sync.totalFiles} 个文件" +
                    if (sync.failedFiles > 0) " · 暂时失败 ${sync.failedFiles}" else ""
            }
            Text(countText, style = MaterialTheme.typography.bodySmall)
            if (sync.currentFile.isNotBlank()) {
                Text(sync.currentFile, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            sync.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                sync.displayPath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ImagePreviewDialog(
    item: ICloudDriveItem,
    loadPreview: suspend (ICloudDriveItem, Int) -> android.graphics.Bitmap,
    onDismiss: () -> Unit,
) {
    val zoomState = rememberImageZoomState(item.id)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.name,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        maxLines = 2,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${zoomState.percentage}%",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = zoomState::reset, enabled = zoomState.canReset) {
                        Text("还原", color = if (zoomState.canReset) Color.White else Color.Gray)
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(Color.White.copy(alpha = 0.14f), CircleShape),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭预览", tint = Color.White)
                    }
                }
                RemoteImage(
                    item = item,
                    targetPixels = 2560,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    loadPreview = loadPreview,
                    zoomState = zoomState,
                    fallback = {
                        Text("图片加载失败，可返回后重试", color = Color.White)
                    },
                )
                Text(
                    "${fileDetails(item)} · 双指缩放 / 双击切换",
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RemoteImage(
    item: ICloudDriveItem,
    targetPixels: Int,
    contentScale: ContentScale,
    modifier: Modifier,
    loadPreview: suspend (ICloudDriveItem, Int) -> android.graphics.Bitmap,
    zoomState: ImageZoomState? = null,
    fallback: @Composable () -> Unit,
) {
    val loadState by produceState<RemoteImageState>(
        initialValue = RemoteImageState.Loading,
        key1 = item.id,
        key2 = item.modifiedAt,
        key3 = targetPixels,
    ) {
        value = try {
            RemoteImageState.Ready(loadPreview(item, targetPixels))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            RemoteImageState.Failed
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val current = loadState) {
            RemoteImageState.Loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            RemoteImageState.Failed -> fallback()
            is RemoteImageState.Ready -> if (zoomState != null) {
                ZoomableBitmapImage(
                    bitmap = current.bitmap,
                    contentDescription = item.name,
                    state = zoomState,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
            }
        }
    }
}

private sealed interface RemoteImageState {
    data object Loading : RemoteImageState
    data object Failed : RemoteImageState
    data class Ready(val bitmap: android.graphics.Bitmap) : RemoteImageState
}

private fun syncStageText(stage: FolderSyncStage): String = when (stage) {
    FolderSyncStage.QUEUED -> "等待同步"
    FolderSyncStage.SCANNING -> "扫描目录"
    FolderSyncStage.DOWNLOADING -> "下载并校验"
    FolderSyncStage.RETRYING -> "等待自动重试"
    FolderSyncStage.COMPLETE -> "同步完成"
    FolderSyncStage.FAILED -> "同步未完成"
}

private fun sortFieldLabel(field: CloudSortField): String = when (field) {
    CloudSortField.NAME -> "文件名称"
    CloudSortField.MODIFIED_TIME -> "修改时间"
    CloudSortField.SIZE -> "文件大小"
    CloudSortField.FILE_TYPE -> "文件类型"
}

private fun sortFieldShortLabel(field: CloudSortField): String = when (field) {
    CloudSortField.NAME -> "名称"
    CloudSortField.MODIFIED_TIME -> "时间"
    CloudSortField.SIZE -> "大小"
    CloudSortField.FILE_TYPE -> "类型"
}

private fun sortDirectionLabel(direction: CloudSortDirection, field: CloudSortField): String =
    when (field) {
        CloudSortField.NAME,
        CloudSortField.FILE_TYPE,
        -> if (direction == CloudSortDirection.ASCENDING) "升序（A → Z）" else "降序（Z → A）"
        CloudSortField.MODIFIED_TIME -> if (direction == CloudSortDirection.ASCENDING) {
            "升序（最早优先）"
        } else {
            "降序（最新优先）"
        }
        CloudSortField.SIZE -> if (direction == CloudSortDirection.ASCENDING) {
            "升序（最小优先）"
        } else {
            "降序（最大优先）"
        }
    }

private fun sortDirectionArrow(direction: CloudSortDirection): String =
    if (direction == CloudSortDirection.ASCENDING) "↑" else "↓"

@Composable
private fun PcsStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CloudToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.size(56.dp, 52.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LoadingPage(label: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IosHero(
            title = "iCloud Drive",
            message = label,
            icon = Icons.Rounded.Cloud,
        )
        Spacer(Modifier.height(22.dp))
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

private fun fileDetails(item: ICloudDriveItem): String {
    val size = formatFileSize(item.size)
    val date = item.modifiedAt?.take(10)
    return listOfNotNull(size, date).joinToString(" · ")
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "文件"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024
        unit++
    } while (value >= 1024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}

private fun fileIcon(item: ICloudDriveItem): ImageVector {
    if (item.isFolder) return Icons.Rounded.Folder
    return when (item.name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "heic", "heif", "dng", "webp", "avif" -> Icons.Rounded.Image
        "mp4", "mov", "m4v" -> Icons.Rounded.Movie
        "zip", "rar", "7z", "tar", "gz" -> Icons.Rounded.Archive
        "pdf" -> Icons.Rounded.PictureAsPdf
        "xls", "xlsx", "numbers", "csv" -> Icons.Rounded.TableChart
        "doc", "docx", "pages", "txt", "md" -> Icons.Rounded.Description
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

@Composable
private fun fileIconTint(item: ICloudDriveItem): Color {
    if (item.isFolder) return MaterialTheme.colorScheme.primary
    return when (item.name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> MaterialTheme.colorScheme.error
        "xls", "xlsx", "numbers", "csv" -> MaterialTheme.colorScheme.tertiary
        "mp4", "mov", "m4v" -> MaterialTheme.colorScheme.secondary
        "jpg", "jpeg", "png", "gif", "heic", "heif", "dng", "webp", "avif" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

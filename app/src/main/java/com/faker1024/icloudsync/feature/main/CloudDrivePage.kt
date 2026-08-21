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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
import com.faker1024.icloudsync.core.icloud.TrustedPhone
import com.faker1024.icloudsync.core.icloud.isPreviewableImage
import com.faker1024.icloudsync.core.settings.CloudBrowserLayout
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("批准 iCloud Drive 访问", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "你的账户启用了高级数据保护。解密密钥只在受信任设备上，Apple 需要你批准这次临时访问。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. 在 iPhone 或 iPad 打开“设置 → Apple 账户 → iCloud → iCloud.com”。")
                Text("2. 确认“允许访问 iCloud 数据”已经开启。高级数据保护可以保持开启。")
                Text("3. 在受信任设备出现提示后，批准本次 iCloud 数据访问。")
            }
        }
        Spacer(Modifier.height(20.dp))
        if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(state.pcsMessage, fontWeight = FontWeight.Medium)
        if (state.pcsAttempt > 0) {
            Text(
                "已检查 ${state.pcsAttempt}/30 次 · App 每 10 秒自动检查",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { ErrorCard(it, onClearError) }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("登录 iCloud 中国区", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "使用中国大陆 Apple 账户登录。界面由本 App 提供，底层直连 iCloud 中国区服务。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = account,
            onValueChange = { account = it.take(160); onClearError() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Apple 账户") },
            placeholder = { Text("name@example.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            enabled = !state.isBusy,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(256); onClearError() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            enabled = !state.isBusy,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = !state.isBusy) { accepted = !accepted },
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = accepted, onCheckedChange = { accepted = it }, enabled = !state.isBusy)
            Text(
                "我了解：本工具使用 Apple 未公开的网页接口，接口变化可能导致登录或云盘功能暂时失效；账户数据只在本机处理。",
                modifier = Modifier.padding(top = 11.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.error?.let { ErrorCard(it, onClearError) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                onLogin(account, password)
                password = ""
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = accepted && account.isNotBlank() && password.isNotBlank() && !state.isBusy,
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text("登录")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "密码通过 SRP 在本机生成登录证明，不会明文发送或保存；登录成功后仅加密保存会话令牌。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("双重认证", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("请输入 Apple 发送到受信任设备或手机号的 6 位验证码。")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { value -> code = value.filter(Char::isDigit).take(6); onClearError() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("验证码") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            enabled = !state.isBusy,
        )
        state.error?.let { ErrorCard(it, onClearError) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onVerify(code); code = "" },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = code.length == 6 && !state.isBusy,
        ) {
            if (state.isBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text("验证并进入云盘")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onTrustedDevice,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isBusy,
        ) { Text("重新发送到受信任设备") }
        state.phones.forEach { phone ->
            TextButton(
                onClick = { onSms(phone) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
            ) { Text("发送短信到 ${phone.label}") }
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("取消登录") }
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
    loadPreview: suspend (ICloudDriveItem, Int) -> android.graphics.Bitmap,
    onLogout: () -> Unit,
    onClearError: () -> Unit,
) {
    var pendingSyncFolder by remember { mutableStateOf<ICloudDriveItem?>(null) }
    var previewItem by remember { mutableStateOf<ICloudDriveItem?>(null) }
    var showIconSizeDialog by rememberSaveable { mutableStateOf(false) }
    var pendingIconSize by rememberSaveable { mutableFloatStateOf(state.iconSize) }

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
    previewItem?.let { item ->
        ImagePreviewDialog(
            item = item,
            loadPreview = loadPreview,
            onDismiss = { previewItem = null },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onBack() }, enabled = state.path.size > 1 && !state.isBusy) { Text("‹ 返回") }
            Text(
                state.path.lastOrNull()?.name ?: "iCloud Drive",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onRefresh, enabled = !state.isBusy) { Text("刷新") }
            TextButton(onClick = onLogout, enabled = !state.isBusy) { Text("退出") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.path.forEachIndexed { index, folder ->
                if (index > 0) Text("  ›  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(folder.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        Text(
            "下载位置：Download/iCloud Drive/ · 文件夹同步会保留目录层级",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${state.items.size} 项",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    onSetLayout(
                        if (state.layout == CloudBrowserLayout.LIST) CloudBrowserLayout.GRID
                        else CloudBrowserLayout.LIST,
                    )
                },
            ) {
                Text(if (state.layout == CloudBrowserLayout.LIST) "▦ 网格" else "☷ 列表")
            }
            OutlinedButton(onClick = {
                pendingIconSize = state.iconSize
                showIconSizeDialog = true
            }) { Text("大小") }
        }
        state.folderSync?.let { sync ->
            FolderSyncStatusCard(sync = sync, onCancel = onCancelSync)
        }
        if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { ErrorCard(it, onClearError, Modifier.padding(horizontal = 12.dp)) }
        if (state.items.isEmpty() && !state.isBusy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("这个文件夹是空的") }
        } else {
            if (state.layout == CloudBrowserLayout.LIST) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listItems(state.items, key = ICloudDriveItem::id) { item ->
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(state.items, key = ICloudDriveItem::id) { item ->
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
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            enabled = item.isFolder || canPreview,
            onClick = { if (item.isFolder) onOpen() else onPreview() },
            onLongClick = { if (item.isFolder) onLongPressFolder() },
            onLongClickLabel = if (item.isFolder) "同步此文件夹到本地" else null,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                Text("›", style = MaterialTheme.typography.headlineSmall)
            } else {
                TextButton(onClick = onDownload, enabled = !downloading) {
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
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            enabled = item.isFolder || canPreview,
            onClick = { if (item.isFolder) onOpen() else onPreview() },
            onLongClick = { if (item.isFolder) onLongPressFolder() },
            onLongClickLabel = if (item.isFolder) "同步此文件夹到本地" else null,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
    val rounded = RoundedCornerShape((iconSize / 7f).dp)
    Box(
        modifier = Modifier
            .size(iconSize.dp)
            .clip(rounded)
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
                    Text("🖼", fontSize = (iconSize * 0.48f).sp)
                },
            )
        } else {
            Text(
                if (item.isFolder) "📁" else fileSymbol(item.name),
                fontSize = (iconSize * 0.48f).sp,
            )
        }
    }
}

@Composable
private fun FolderSyncStatusCard(sync: FolderSyncUiState, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${sync.folderName} · ${syncStageText(sync.stage)}",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
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
                color = MaterialTheme.colorScheme.onSecondaryContainer,
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
                    TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
                }
                RemoteImage(
                    item = item,
                    targetPixels = 2560,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    loadPreview = loadPreview,
                    fallback = {
                        Text("图片加载失败，可返回后重试", color = Color.White)
                    },
                )
                Text(
                    fileDetails(item),
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
            is RemoteImageState.Ready -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
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

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable
private fun LoadingPage(label: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(label)
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

private fun fileSymbol(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "heic", "heif", "dng" -> "🖼"
    "mp4", "mov", "m4v" -> "🎞"
    "zip", "rar", "7z", "tar", "gz" -> "🗜"
    "pdf" -> "📕"
    "doc", "docx", "pages", "txt", "md" -> "📄"
    "xls", "xlsx", "numbers", "csv" -> "📊"
    else -> "📄"
}

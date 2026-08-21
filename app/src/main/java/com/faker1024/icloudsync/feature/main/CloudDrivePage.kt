package com.faker1024.icloudsync.feature.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faker1024.icloudsync.core.icloud.ICloudDriveItem
import com.faker1024.icloudsync.core.icloud.TrustedPhone

@Composable
fun CloudDrivePage(
    viewModel: CloudDriveViewModel,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit,
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
    onLogout: () -> Unit,
    onClearError: () -> Unit,
) {
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
            "下载位置：Download/iCloud Drive/",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { ErrorCard(it, onClearError, Modifier.padding(horizontal = 12.dp)) }
        if (state.items.isEmpty() && !state.isBusy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("这个文件夹是空的") }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = ICloudDriveItem::id) { item ->
                    DriveItemRow(
                        item = item,
                        downloading = item.id in state.downloadingIds,
                        onOpen = { onOpen(item) },
                        onDownload = { onDownload(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DriveItemRow(
    item: ICloudDriveItem,
    downloading: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = item.isFolder, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (item.isFolder) "📁" else fileSymbol(item.name), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.name, fontWeight = FontWeight.Medium, maxLines = 2)
                Text(
                    if (item.isFolder) "${item.childCount} 项" else fileDetails(item),
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

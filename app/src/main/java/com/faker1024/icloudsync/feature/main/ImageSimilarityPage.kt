package com.faker1024.icloudsync.feature.main

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.faker1024.icloudsync.core.local.SyncedFile
import com.faker1024.icloudsync.core.similarity.SimilarityLevel
import java.text.DecimalFormat
import kotlinx.coroutines.CancellationException

@Composable
internal fun ImageSimilarityPage(
    modifier: Modifier,
    state: ImageSimilarityUiState,
    onClose: () -> Unit,
    onLevelChange: (SimilarityLevel) -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onToggle: (SyncedFile) -> Unit,
    onClearSelection: () -> Unit,
    onSelectFailures: () -> Unit,
    onSelectPending: () -> Unit,
    onDelete: () -> Unit,
    onOpenFile: (SyncedFile) -> Unit,
    onShareFile: (SyncedFile) -> Unit,
    loadImage: suspend (SyncedFile, Int) -> Bitmap,
) {
    var confirmationFiles by remember { mutableStateOf<List<SyncedFile>>(emptyList()) }
    var preview by remember { mutableStateOf<Pair<List<SyncedFile>, SyncedFile>?>(null) }
    val selectedFiles = state.selectedFiles
    val confirmedPendingUris = state.confirmedPendingUris
    preview?.let { (images, file) ->
        SyncedImagePreviewDialog(
            files = images,
            selectedFile = file,
            loadImage = loadImage,
            onOpenExternally = onOpenFile,
            onShare = onShareFile,
            onDismiss = { preview = null },
        )
    }
    if (confirmationFiles.isNotEmpty()) {
        val pendingCount = confirmationFiles.count { it.contentUri in confirmedPendingUris }
        val onlyPending = pendingCount == confirmationFiles.size
        AlertDialog(
            onDismissRequest = { confirmationFiles = emptyList() },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text(if (onlyPending) "继续清理 ${confirmationFiles.size} 张本地图片？"
                else "删除本地和 iCloud 中的 ${confirmationFiles.size} 张图片？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (onlyPending) "这些图片的云端文件已确认移入 iCloud“最近删除”。本次仅继续删除尚未清理的本地文件。"
                        else "确认后，将先把云端对应文件移入 iCloud“最近删除”，再删除本地文件。云端删除失败时会保留本地文件。")
                    if (!onlyPending && pendingCount > 0) Text("其中 $pendingCount 张云端删除已完成，仅继续清理本地。")
                    if (!onlyPending) Text("每组至少保留一张。相似度仅供参考，请先打开图片确认。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(confirmationFiles, key = SyncedFile::contentUri) { file ->
                            Text((file.directories + file.displayName).joinToString("/"),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmationFiles = emptyList(); onDelete() }) {
                    Text(if (onlyPending) "清理本地文件" else "删除两端文件", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmationFiles = emptyList() }) { Text("取消") } },
        )
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, enabled = !state.deleting) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回本地文件")
            }
            Column(Modifier.weight(1f)) {
                Text("相似图片", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("范围：全部已同步到本地的图片", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                IosGroupedSurface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IosIconTile(Icons.Rounded.PhotoLibrary, contentDescription = null)
                            Column {
                                Text("找出重复或近似的图片", fontWeight = FontWeight.SemiBold)
                                Text("图片比较在设备内完成", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SimilarityLevel.entries.forEach { level ->
                                FilterChip(selected = state.level == level, onClick = { onLevelChange(level) },
                                    enabled = !state.busy, label = { Text(similarityLevelLabel(level)) })
                            }
                        }
                        Text(when (state.level) {
                            SimilarityLevel.STRICT -> "严格：优先查找几乎相同的图片。"
                            SimilarityLevel.BALANCED -> "均衡：查找压缩、缩放后仍相似的图片。"
                            SimilarityLevel.LOOSE -> "宽松：包含更多视觉近似的图片，请仔细确认。"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("动画、空文件或无法读取的图片会跳过。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.scanning) {
                            state.scanProgress?.let { progress ->
                                LinearProgressIndicator(progress = { if (progress.total == 0) 0f
                                else progress.processed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                                Text("已检查 ${progress.processed}/${progress.total} 张 · 跳过 ${progress.skipped} 张",
                                    style = MaterialTheme.typography.bodySmall)
                            } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = onCancelScan) { Text("取消扫描") }
                        } else {
                            IosPrimaryButton(onClick = onScan, enabled = !state.deleting, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.hasScanned) "重新扫描" else "开始扫描")
                            }
                        }
                    }
                }
            }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            state.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodyMedium) } }
            if (state.pendingLocalDeletions.isNotEmpty()) {
                item {
                    IosGroupedSurface {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("继续清理已确认项 · ${state.pendingLocalDeletions.size} 张", fontWeight = FontWeight.SemiBold)
                            Text("这些图片已从 iCloud 移入最近删除，本地尚未清理完成。退出或重启后仍可在这里继续。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onSelectPending, enabled = !state.busy) { Text("选择待清理项") }
                        }
                    }
                }
                items(state.pendingLocalDeletions, key = { "pending:${it.file.contentUri}" }) { result ->
                    IosGroupedSurface {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SimilarityFileRow(file = result.file,
                                selected = result.file.contentUri in state.selectedUris, enabled = !state.busy,
                                onToggle = { onToggle(result.file) },
                                onPreview = { preview = state.pendingLocalDeletions.map { it.file } to result.file },
                                loadImage = loadImage)
                            Text(result.message, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (state.deletionFailures.isNotEmpty()) {
                item {
                    IosGroupedSurface {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${state.deletionFailures.size} 张图片删除未完成", color = MaterialTheme.colorScheme.error)
                            state.deletionFailures.take(5).forEach { result ->
                                Text("${result.file.displayName}：${result.message}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (state.deletionFailures.size > 5) Text("还有 ${state.deletionFailures.size - 5} 项",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onSelectFailures, enabled = !state.busy) { Text("选择未完成项重试") }
                        }
                    }
                }
            }
            if (state.hasScanned) {
                item {
                    IosSectionHeader("${state.groups.size} 组相似图片",
                        caption = "已扫描 ${state.scanned} 张 · 跳过 ${state.skipped} 张。点缩略图查看，勾选要删除的图片。")
                }
                if (state.groups.isEmpty()) item {
                    IosGroupedSurface {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text("没有找到相似图片", fontWeight = FontWeight.SemiBold)
                            Text(if (state.skipped > 0) "部分图片无法读取或不支持比较，可刷新后重试。"
                                else "可以调整为更宽松的范围后重新扫描。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            state.groups.forEachIndexed { groupIndex, group ->
                item(key = "group:${group.id}") {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("第 ${groupIndex + 1} 组 · ${group.files.size} 张图片", fontWeight = FontWeight.SemiBold)
                            IosStatusPill("相似度 ${group.score}%", MaterialTheme.colorScheme.primary)
                        }
                        Text("每组至少保留一张 · 分数仅供参考", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(group.files, key = { "${group.id}:${it.contentUri}" }) { file ->
                    IosGroupedSurface {
                        Box(Modifier.padding(10.dp)) {
                            SimilarityFileRow(file = file, selected = file.contentUri in state.selectedUris,
                                enabled = !state.busy, onToggle = { onToggle(file) },
                                onPreview = { preview = group.files to file }, loadImage = loadImage)
                        }
                    }
                }
            }
        }
        if (state.deleting || selectedFiles.isNotEmpty()) {
            IosGroupedSurface(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.deleting) {
                        val progress = state.deletionProgress
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在同步删除 ${progress?.completed ?: 0}/${progress?.total ?: 0}，请保持应用打开",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("已选 ${selectedFiles.size} 张 · ${similarityBytes(selectedFiles.sumOf { it.size })}",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onClearSelection) { Text("取消选择") }
                        }
                        IosPrimaryButton(onClick = { confirmationFiles = selectedFiles },
                            enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(if (selectedFiles.all { it.contentUri in confirmedPendingUris }) "继续清理本地文件"
                                else "删除本地及云端图片")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarityFileRow(
    file: SyncedFile,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
    loadImage: suspend (SyncedFile, Int) -> Bitmap,
) {
    var failed by remember(file.contentUri, file.modifiedAtMillis) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, file.contentUri, file.modifiedAtMillis) {
        value = try { loadImage(file, 240) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true; null }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onPreview),
            contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = "预览 ${file.displayName}",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: if (failed) Icon(Icons.Rounded.Image, contentDescription = "预览加载失败")
                else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Column(Modifier.weight(1f).clickable(onClick = onPreview), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(file.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(similarityBytes(file.size), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(file.directories.joinToString("/").ifEmpty { "iCloud Drive" }, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

private fun similarityLevelLabel(level: SimilarityLevel) = when (level) {
    SimilarityLevel.STRICT -> "严格"
    SimilarityLevel.BALANCED -> "均衡"
    SimilarityLevel.LOOSE -> "宽松"
}

private fun similarityBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return "${DecimalFormat("0.#").format(value)} ${units[unit]}"
}

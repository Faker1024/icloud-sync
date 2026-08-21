package com.faker1024.icloudsync.feature.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.faker1024.icloudsync.core.local.SYNCED_FILES_PUBLIC_PATH
import com.faker1024.icloudsync.core.local.SyncedBrowserEntry
import com.faker1024.icloudsync.core.local.SyncedFile
import com.faker1024.icloudsync.core.local.buildSyncedBrowserEntries
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

@Composable
internal fun SyncedFilesPage(
    modifier: Modifier,
    state: SyncedFilesUiState,
    onRefresh: () -> Unit,
    onOpenFile: (SyncedFile) -> Unit,
    loadImage: suspend (SyncedFile, Int) -> android.graphics.Bitmap,
) {
    var encodedPath by rememberSaveable { mutableStateOf("") }
    val currentPath = remember(encodedPath) { decodeLocalPath(encodedPath) }
    val entries = remember(state.files, currentPath) {
        buildSyncedBrowserEntries(state.files, currentPath)
    }
    var previewFile by remember { mutableStateOf<SyncedFile?>(null) }
    previewFile?.let { file ->
        SyncedImagePreviewDialog(
            file = file,
            loadImage = loadImage,
            onOpenExternally = { onOpenFile(file) },
            onDismiss = { previewFile = null },
        )
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (currentPath.isNotEmpty()) {
                IconButton(onClick = { encodedPath = encodeLocalPath(currentPath.dropLast(1)) }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回上一级")
                }
            } else {
                IosIconTile(Icons.Rounded.Folder, contentDescription = null)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    currentPath.lastOrNull() ?: "已同步文件",
                    maxLines = 1,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (currentPath.isEmpty()) {
                        "${state.files.size} 个本地文件"
                    } else {
                        "${entries.size} 项 · 保留云端目录层级"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = onRefresh, enabled = !state.isLoading) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新本地文件")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "iCloud Drive",
                modifier = Modifier.clickable { encodedPath = "" },
                style = MaterialTheme.typography.bodySmall,
                color = if (currentPath.isEmpty()) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary,
            )
            currentPath.forEachIndexed { index, directory ->
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    directory,
                    modifier = Modifier.clickable {
                        encodedPath = encodeLocalPath(currentPath.take(index + 1))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (index == currentPath.lastIndex) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        Text(
            SYNCED_FILES_PUBLIC_PATH,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { message ->
            IosGroupedSurface(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh) { Text("重试") }
                }
            }
        }
        when {
            state.files.isEmpty() && !state.isLoading && state.error == null -> {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    IosHero(
                        title = "还没有同步文件",
                        message = "在云盘中长按文件夹开始同步；完成后可以在这里浏览和预览图片。",
                        icon = Icons.Rounded.Folder,
                    )
                }
            }
            entries.isEmpty() && !state.isLoading && state.error == null -> {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    IosHero(
                        title = "文件夹为空",
                        message = "文件可能已在系统文件管理器中被移动或删除，请刷新后重试。",
                        icon = Icons.Rounded.Folder,
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries, key = SyncedBrowserEntry::key) { entry ->
                        SyncedEntryCard(
                            entry = entry,
                            onOpenFolder = { folder -> encodedPath = encodeLocalPath(folder.path) },
                            onOpenFile = { file ->
                                if (file.isImage) previewFile = file else onOpenFile(file)
                            },
                            loadImage = loadImage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedEntryCard(
    entry: SyncedBrowserEntry,
    onOpenFolder: (SyncedBrowserEntry.Folder) -> Unit,
    onOpenFile: (SyncedFile) -> Unit,
    loadImage: suspend (SyncedFile, Int) -> android.graphics.Bitmap,
) {
    val onClick = when (entry) {
        is SyncedBrowserEntry.Folder -> ({ onOpenFolder(entry) })
        is SyncedBrowserEntry.File -> ({ onOpenFile(entry.item) })
    }
    IosGroupedSurface(modifier = Modifier.clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            when (entry) {
                is SyncedBrowserEntry.Folder -> SyncedFolderVisual(entry)
                is SyncedBrowserEntry.File -> SyncedFileVisual(entry.item, loadImage)
            }
            Text(
                when (entry) {
                    is SyncedBrowserEntry.Folder -> entry.name
                    is SyncedBrowserEntry.File -> entry.item.displayName
                },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Start,
            )
            Text(
                when (entry) {
                    is SyncedBrowserEntry.Folder -> "${entry.descendantFileCount} 个文件"
                    is SyncedBrowserEntry.File -> localFileDetails(entry.item)
                },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncedFolderVisual(folder: SyncedBrowserEntry.Folder) {
    Box(
        modifier = Modifier.fillMaxWidth().height(112.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = "打开 ${folder.name}",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(62.dp),
        )
    }
}

@Composable
private fun SyncedFileVisual(
    file: SyncedFile,
    loadImage: suspend (SyncedFile, Int) -> android.graphics.Bitmap,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(112.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (file.isImage) {
            SyncedBitmapImage(
                file = file,
                targetPixels = 640,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loadImage = loadImage,
                fallback = {
                    Icon(
                        Icons.Rounded.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(54.dp),
                    )
                },
            )
        } else {
            Icon(
                syncedFileIcon(file),
                contentDescription = null,
                tint = syncedFileIconTint(file),
                modifier = Modifier.size(54.dp),
            )
        }
    }
}

@Composable
private fun SyncedImagePreviewDialog(
    file: SyncedFile,
    loadImage: suspend (SyncedFile, Int) -> android.graphics.Bitmap,
    onOpenExternally: () -> Unit,
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
                        file.displayName,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        color = Color.White,
                        maxLines = 2,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(onClick = onOpenExternally) { Text("其他应用", color = Color.White) }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(Color.White.copy(alpha = 0.14f), CircleShape),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭预览", tint = Color.White)
                    }
                }
                SyncedBitmapImage(
                    file = file,
                    targetPixels = 2560,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    loadImage = loadImage,
                    fallback = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(42.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("图片加载失败，文件可能已被移动或损坏", color = Color.White)
                        }
                    },
                )
                Text(
                    localFileDetails(file),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SyncedBitmapImage(
    file: SyncedFile,
    targetPixels: Int,
    contentScale: ContentScale,
    modifier: Modifier,
    loadImage: suspend (SyncedFile, Int) -> android.graphics.Bitmap,
    fallback: @Composable () -> Unit,
) {
    val loadState by produceState<SyncedImageState>(
        initialValue = SyncedImageState.Loading,
        key1 = file.contentUri,
        key2 = file.modifiedAtMillis,
        key3 = targetPixels,
    ) {
        value = try {
            SyncedImageState.Ready(loadImage(file, targetPixels))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            SyncedImageState.Failed
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val current = loadState) {
            SyncedImageState.Loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            SyncedImageState.Failed -> fallback()
            is SyncedImageState.Ready -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = file.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

private sealed interface SyncedImageState {
    data object Loading : SyncedImageState
    data object Failed : SyncedImageState
    data class Ready(val bitmap: android.graphics.Bitmap) : SyncedImageState
}

private fun syncedFileIcon(file: SyncedFile) = when {
    file.mimeType?.startsWith("video/") == true -> Icons.Rounded.Movie
    file.mimeType == "application/pdf" || file.displayName.endsWith(".pdf", true) -> Icons.Rounded.PictureAsPdf
    file.displayName.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS -> Icons.Rounded.Archive
    file.displayName.substringAfterLast('.', "").lowercase() in TABLE_EXTENSIONS -> Icons.Rounded.TableChart
    file.displayName.substringAfterLast('.', "").lowercase() in DOCUMENT_EXTENSIONS -> Icons.Rounded.Description
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

@Composable
private fun syncedFileIconTint(file: SyncedFile): Color = when {
    file.mimeType?.startsWith("video/") == true -> MaterialTheme.colorScheme.secondary
    file.mimeType == "application/pdf" || file.displayName.endsWith(".pdf", true) -> MaterialTheme.colorScheme.error
    file.displayName.substringAfterLast('.', "").lowercase() in TABLE_EXTENSIONS -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun localFileDetails(file: SyncedFile): String {
    val date = file.modifiedAtMillis.takeIf { it > 0L }?.let {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
    }
    return listOfNotNull(formatSyncedBytes(file.size), date).joinToString(" · ")
}

private fun formatSyncedBytes(bytes: Long): String {
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

private fun encodeLocalPath(path: List<String>): String = path.joinToString(LOCAL_PATH_SEPARATOR)

private fun decodeLocalPath(value: String): List<String> =
    value.takeIf(String::isNotBlank)?.split(LOCAL_PATH_SEPARATOR) ?: emptyList()

private const val LOCAL_PATH_SEPARATOR = "\u001F"
private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2")
private val TABLE_EXTENSIONS = setOf("xls", "xlsx", "csv", "numbers")
private val DOCUMENT_EXTENSIONS = setOf("doc", "docx", "txt", "rtf", "pages", "md")

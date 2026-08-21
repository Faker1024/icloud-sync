package com.faker1024.icloudsync.feature.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.faker1024.icloudsync.core.local.SYNCED_FILES_PUBLIC_PATH
import com.faker1024.icloudsync.core.local.SyncedBrowserEntry
import com.faker1024.icloudsync.core.local.SyncedFile
import com.faker1024.icloudsync.core.local.buildSyncedBrowserEntries
import com.faker1024.icloudsync.core.settings.LocalBrowserLayout
import com.faker1024.icloudsync.core.settings.LocalBrowserPreferences
import com.faker1024.icloudsync.core.settings.LocalSortDirection
import com.faker1024.icloudsync.core.settings.LocalSortField
import com.github.panpf.zoomimage.ZoomImageView
import com.github.panpf.zoomimage.subsampling.ImageSource
import com.github.panpf.zoomimage.subsampling.fromContent
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncedFilesPage(
    modifier: Modifier,
    state: SyncedFilesUiState,
    preferences: LocalBrowserPreferences,
    onRefresh: () -> Unit,
    onOpenFile: (SyncedFile) -> Unit,
    onShareFile: (SyncedFile) -> Unit,
    onSetLayout: (LocalBrowserLayout) -> Unit,
    onSetSorting: (LocalSortField, LocalSortDirection) -> Unit,
    loadImage: suspend (SyncedFile, Int) -> android.graphics.Bitmap,
) {
    var encodedPath by rememberSaveable { mutableStateOf("") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSortDialog by remember { mutableStateOf(false) }
    var pendingSortField by remember(preferences.sortField) { mutableStateOf(preferences.sortField) }
    var pendingSortDirection by remember(preferences.sortDirection) {
        mutableStateOf(preferences.sortDirection)
    }
    val currentPath = remember(encodedPath) { decodeLocalPath(encodedPath) }
    val totalBytes = remember(state.files) { totalSyncedBytes(state.files) }
    val entriesState by produceState<SyncedEntriesState>(
        initialValue = SyncedEntriesState.Loading,
        key1 = state.files,
        key2 = encodedPath to searchQuery,
        key3 = preferences,
    ) {
        value = SyncedEntriesState.Loading
        value = SyncedEntriesState.Ready(
            withContext(Dispatchers.Default) {
                buildSyncedBrowserEntries(
                    files = state.files,
                    currentPath = currentPath,
                    sortField = preferences.sortField,
                    sortDirection = preferences.sortDirection,
                    query = searchQuery,
                )
            },
        )
    }
    val entries = (entriesState as? SyncedEntriesState.Ready)?.entries.orEmpty()
    val previewImages = remember(entries) {
        entries.mapNotNull { (it as? SyncedBrowserEntry.File)?.item }.filter(SyncedFile::isImage)
    }
    val isOrganizing = entriesState == SyncedEntriesState.Loading
    var previewFile by remember { mutableStateOf<SyncedFile?>(null) }
    var actionFile by remember { mutableStateOf<SyncedFile?>(null) }
    if (showSortDialog) {
        LocalSortDialog(
            field = pendingSortField,
            direction = pendingSortDirection,
            onFieldChange = { pendingSortField = it },
            onDirectionChange = { pendingSortDirection = it },
            onApply = {
                onSetSorting(pendingSortField, pendingSortDirection)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false },
        )
    }
    previewFile?.let { file ->
        SyncedImagePreviewDialog(
            files = previewImages,
            selectedFile = file,
            loadImage = loadImage,
            onOpenExternally = onOpenFile,
            onShare = onShareFile,
            onDismiss = { previewFile = null },
        )
    }
    actionFile?.let { file ->
        SyncedFileActionsSheet(
            file = file,
            onOpenExternally = {
                actionFile = null
                onOpenFile(file)
            },
            onShare = {
                actionFile = null
                onShareFile(file)
            },
            onDismiss = { actionFile = null },
        )
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (currentPath.isNotEmpty()) {
                IconButton(onClick = {
                    encodedPath = encodeLocalPath(currentPath.dropLast(1))
                    searchQuery = ""
                }) {
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
                        "${state.files.size} 个文件 · ${formatSyncedBytes(totalBytes)}"
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
                modifier = Modifier.clickable {
                    encodedPath = ""
                    searchQuery = ""
                },
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
                        searchQuery = ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (index == currentPath.lastIndex) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it.take(MAX_SEARCH_LENGTH) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("搜索当前文件夹") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isBlank()) null else ({
                IconButton(onClick = { searchQuery = "" }) {
                    Icon(Icons.Rounded.Close, contentDescription = "清除搜索")
                }
            }),
            shape = MaterialTheme.shapes.medium,
        )
        IosGroupedSurface(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "${localSortFieldLabel(preferences.sortField)}${localSortDirectionArrow(preferences.sortDirection)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        if (searchQuery.isBlank()) SYNCED_FILES_PUBLIC_PATH else "找到 ${entries.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                LocalToolbarAction(
                    icon = Icons.AutoMirrored.Rounded.Sort,
                    label = "排序",
                    onClick = {
                        pendingSortField = preferences.sortField
                        pendingSortDirection = preferences.sortDirection
                        showSortDialog = true
                    },
                )
                LocalToolbarAction(
                    icon = if (preferences.layout == LocalBrowserLayout.GRID) {
                        Icons.AutoMirrored.Rounded.ViewList
                    } else {
                        Icons.Rounded.GridView
                    },
                    label = if (preferences.layout == LocalBrowserLayout.GRID) "列表" else "网格",
                    onClick = {
                        onSetLayout(
                            if (preferences.layout == LocalBrowserLayout.GRID) LocalBrowserLayout.LIST
                            else LocalBrowserLayout.GRID,
                        )
                    },
                )
            }
        }
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (isOrganizing && !state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
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
            isOrganizing -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            entries.isEmpty() && !state.isLoading && state.error == null -> {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    IosHero(
                        title = if (searchQuery.isBlank()) "文件夹为空" else "没有找到文件",
                        message = if (searchQuery.isBlank()) {
                            "文件可能已在系统文件管理器中被移动或删除，请刷新后重试。"
                        } else {
                            "当前文件夹中没有名称包含“$searchQuery”的项目。"
                        },
                        icon = if (searchQuery.isBlank()) Icons.Rounded.Folder else Icons.Rounded.Search,
                    )
                }
            }
            else -> {
                val openFolder: (SyncedBrowserEntry.Folder) -> Unit = { folder ->
                    encodedPath = encodeLocalPath(folder.path)
                    searchQuery = ""
                }
                val openFile: (SyncedFile) -> Unit = { file ->
                    if (file.isImage) previewFile = file else onOpenFile(file)
                }
                if (preferences.layout == LocalBrowserLayout.GRID) {
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
                                onOpenFolder = openFolder,
                                onOpenFile = openFile,
                                onShowActions = { actionFile = it },
                                loadImage = loadImage,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listItems(entries, key = SyncedBrowserEntry::key) { entry ->
                            SyncedEntryListRow(
                                entry = entry,
                                onOpenFolder = openFolder,
                                onOpenFile = openFile,
                                onShowActions = { actionFile = it },
                                loadImage = loadImage,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncedEntryCard(
    entry: SyncedBrowserEntry,
    onOpenFolder: (SyncedBrowserEntry.Folder) -> Unit,
    onOpenFile: (SyncedFile) -> Unit,
    onShowActions: (SyncedFile) -> Unit,
    loadImage: suspend (SyncedFile, Int) -> android.graphics.Bitmap,
) {
    val onClick = when (entry) {
        is SyncedBrowserEntry.Folder -> ({ onOpenFolder(entry) })
        is SyncedBrowserEntry.File -> ({ onOpenFile(entry.item) })
    }
    IosGroupedSurface(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { (entry as? SyncedBrowserEntry.File)?.item?.let(onShowActions) },
            onLongClickLabel = if (entry is SyncedBrowserEntry.File) "更多文件操作" else null,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            when (entry) {
                is SyncedBrowserEntry.Folder -> SyncedFolderVisual(
                    entry,
                    Modifier.fillMaxWidth().height(112.dp),
                )
                is SyncedBrowserEntry.File -> SyncedFileVisual(
                    file = entry.item,
                    loadImage = loadImage,
                    modifier = Modifier.fillMaxWidth().height(112.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (entry) {
                        is SyncedBrowserEntry.Folder -> entry.name
                        is SyncedBrowserEntry.File -> entry.item.displayName
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Start,
                )
                if (entry is SyncedBrowserEntry.File) {
                    IconButton(
                        onClick = { onShowActions(entry.item) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "更多文件操作",
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
            Text(
                when (entry) {
                    is SyncedBrowserEntry.Folder -> localFolderDetails(entry)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncedEntryListRow(
    entry: SyncedBrowserEntry,
    onOpenFolder: (SyncedBrowserEntry.Folder) -> Unit,
    onOpenFile: (SyncedFile) -> Unit,
    onShowActions: (SyncedFile) -> Unit,
    loadImage: suspend (SyncedFile, Int) -> android.graphics.Bitmap,
) {
    val onClick = when (entry) {
        is SyncedBrowserEntry.Folder -> ({ onOpenFolder(entry) })
        is SyncedBrowserEntry.File -> ({ onOpenFile(entry.item) })
    }
    IosGroupedSurface(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { (entry as? SyncedBrowserEntry.File)?.item?.let(onShowActions) },
            onLongClickLabel = if (entry is SyncedBrowserEntry.File) "更多文件操作" else null,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (entry) {
                is SyncedBrowserEntry.Folder -> SyncedFolderVisual(entry, Modifier.size(62.dp))
                is SyncedBrowserEntry.File -> SyncedFileVisual(
                    file = entry.item,
                    loadImage = loadImage,
                    modifier = Modifier.size(62.dp),
                    targetPixels = 360,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    when (entry) {
                        is SyncedBrowserEntry.Folder -> entry.name
                        is SyncedBrowserEntry.File -> entry.item.displayName
                    },
                    maxLines = 2,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when (entry) {
                        is SyncedBrowserEntry.Folder -> localFolderDetails(entry)
                        is SyncedBrowserEntry.File -> localFileDetails(entry.item)
                    },
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry is SyncedBrowserEntry.File) {
                IconButton(onClick = { onShowActions(entry.item) }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多文件操作")
                }
            } else {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = "打开文件夹",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncedFolderVisual(
    folder: SyncedBrowserEntry.Folder,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
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
    modifier: Modifier,
    targetPixels: Int = 640,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (file.isImage) {
            SyncedBitmapImage(
                file = file,
                targetPixels = targetPixels,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncedFileActionsSheet(
    file: SyncedFile,
    onOpenExternally: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text(file.displayName, maxLines = 2, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(localFileDetails(file)) },
                leadingContent = {
                    IosIconTile(
                        icon = if (file.isImage) Icons.Rounded.Image else syncedFileIcon(file),
                        contentDescription = null,
                        tint = if (file.isImage) MaterialTheme.colorScheme.secondary else syncedFileIconTint(file),
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("使用其他应用打开") },
                supportingContent = { Text("选择设备上支持此格式的应用") },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Rounded.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier.clickable(onClick = onOpenExternally),
            )
            ListItem(
                headlineContent = { Text("分享或发送文件") },
                supportingContent = { Text("发送到聊天、网盘、编辑器或其他应用") },
                leadingContent = {
                    Icon(Icons.Rounded.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable(onClick = onShare),
            )
            Text(
                "目标应用只会获得该文件的临时只读访问权限。",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncedImagePreviewDialog(
    files: List<SyncedFile>,
    selectedFile: SyncedFile,
    loadImage: suspend (SyncedFile, Int) -> android.graphics.Bitmap,
    onOpenExternally: (SyncedFile) -> Unit,
    onShare: (SyncedFile) -> Unit,
    onDismiss: () -> Unit,
) {
    val images = remember(files, selectedFile) {
        files.takeIf { it.isNotEmpty() } ?: listOf(selectedFile)
    }
    val initialPage = remember(images, selectedFile.contentUri) {
        initialImagePage(images.map(SyncedFile::contentUri), selectedFile.contentUri)
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { images.size }
    val viewers = remember { mutableStateMapOf<String, ZoomImageView>() }
    val zoomPercents = remember { mutableStateMapOf<String, Int>() }
    val currentFile = images.getOrElse(pagerState.currentPage) { selectedFile }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsEpoch by remember { mutableIntStateOf(0) }
    val currentViewer = viewers[currentFile.contentUri]
    LaunchedEffect(pagerState.currentPage) {
        controlsVisible = true
        controlsEpoch++
    }
    LaunchedEffect(controlsVisible, controlsEpoch, currentViewer) {
        if (controlsVisible && currentViewer != null) {
            delay(IMAGE_VIEWER_CONTROLS_TIMEOUT_MILLIS)
            controlsVisible = false
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ImmersiveViewerSystemBars(controlsVisible)
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { index -> images[index].contentUri },
                ) { page ->
                    val file = images[page]
                    LocalTiledImagePage(
                        file = file,
                        loadImage = loadImage,
                        onViewerChanged = { viewer ->
                            if (viewer == null) viewers.remove(file.contentUri)
                            else viewers[file.contentUri] = viewer
                        },
                        onZoomPercentChanged = { percent ->
                            val previous = zoomPercents.put(file.contentUri, percent)
                            if (images.getOrNull(pagerState.currentPage)?.contentUri == file.contentUri &&
                                previous != null && previous != percent
                            ) {
                                controlsVisible = false
                            }
                        },
                        onTap = {
                            controlsVisible = !controlsVisible
                            controlsEpoch++
                        },
                    )
                }
                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(color = Color.Black.copy(alpha = 0.58f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                currentFile.displayName,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                                color = Color.White,
                                maxLines = 1,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${pagerState.currentPage + 1}/${images.size} · ${zoomPercents[currentFile.contentUri] ?: 100}%",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            IconButton(onClick = {
                                viewers[currentFile.contentUri]?.zoomable?.reset()
                                controlsEpoch++
                            }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "还原缩放", tint = Color.White)
                            }
                            IconButton(onClick = { onShare(currentFile) }) {
                                Icon(Icons.Rounded.Share, contentDescription = "分享文件", tint = Color.White)
                            }
                            IconButton(onClick = { onOpenExternally(currentFile) }) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.InsertDriveFile,
                                    contentDescription = "使用其他应用打开",
                                    tint = Color.White,
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.background(Color.White.copy(alpha = 0.14f), CircleShape),
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "关闭预览", tint = Color.White)
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.BottomCenter).zIndex(1f),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(color = Color.Black.copy(alpha = 0.58f)) {
                        Text(
                            "${localFileDetails(currentFile)} · 左右滑动切换 · 双指缩放 / 双击放大",
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalTiledImagePage(
    file: SyncedFile,
    loadImage: suspend (SyncedFile, Int) -> android.graphics.Bitmap,
    onViewerChanged: (ZoomImageView?) -> Unit,
    onZoomPercentChanged: (Int) -> Unit,
    onTap: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var retryKey by remember(file.contentUri) { mutableIntStateOf(0) }
    val loadState by produceState<SyncedImageState>(
        initialValue = SyncedImageState.Loading,
        key1 = file.contentUri,
        key2 = file.modifiedAtMillis,
        key3 = retryKey,
    ) {
        value = try {
            SyncedImageState.Ready(loadImage(file, LOCAL_VIEWER_THUMBNAIL_PIXELS))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            SyncedImageState.Failed
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val current = loadState) {
            SyncedImageState.Loading -> CircularProgressIndicator(color = Color.White)
            SyncedImageState.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("图片加载失败，文件可能已被移动或损坏", color = Color.White)
                TextButton(onClick = { retryKey++ }) { Text("重试") }
            }
            is SyncedImageState.Ready -> TiledZoomImage(
                thumbnail = current.bitmap,
                imageSource = remember(file.contentUri) {
                    ImageSource.fromContent(context, file.contentUri.toUri())
                },
                contentDescription = file.displayName,
                modifier = Modifier.fillMaxSize(),
                onViewerChanged = onViewerChanged,
                onZoomPercentChanged = onZoomPercentChanged,
                onTap = onTap,
            )
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

private const val LOCAL_VIEWER_THUMBNAIL_PIXELS = 1280
private const val IMAGE_VIEWER_CONTROLS_TIMEOUT_MILLIS = 2_500L

private sealed interface SyncedImageState {
    data object Loading : SyncedImageState
    data object Failed : SyncedImageState
    data class Ready(val bitmap: android.graphics.Bitmap) : SyncedImageState
}

private sealed interface SyncedEntriesState {
    data object Loading : SyncedEntriesState
    data class Ready(val entries: List<SyncedBrowserEntry>) : SyncedEntriesState
}

@Composable
private fun LocalSortDialog(
    field: LocalSortField,
    direction: LocalSortDirection,
    onFieldChange: (LocalSortField) -> Unit,
    onDirectionChange: (LocalSortDirection) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地文件排序") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LocalSortField.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onFieldChange(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = field == option,
                            onClick = { onFieldChange(option) },
                        )
                        Text(localSortFieldLabel(option))
                    }
                }
                Text(
                    "排序方向",
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LocalSortDirection.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onDirectionChange(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = direction == option,
                            onClick = { onDirectionChange(option) },
                        )
                        Text(if (option == LocalSortDirection.ASCENDING) "升序" else "降序")
                    }
                }
                Text(
                    "文件夹始终显示在文件前面；文件夹大小为其全部子文件总和。",
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun LocalToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(21.dp),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
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

private fun localFolderDetails(folder: SyncedBrowserEntry.Folder): String {
    val date = folder.modifiedAtMillis.takeIf { it > 0L }?.let {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
    }
    return listOfNotNull(
        "${folder.descendantFileCount} 个文件",
        formatSyncedBytes(folder.totalBytes),
        date,
    ).joinToString(" · ")
}

private fun localSortFieldLabel(field: LocalSortField): String = when (field) {
    LocalSortField.NAME -> "名称"
    LocalSortField.MODIFIED_TIME -> "修改时间"
    LocalSortField.SIZE -> "大小"
    LocalSortField.FILE_TYPE -> "文件类型"
}

private fun localSortDirectionArrow(direction: LocalSortDirection): String =
    if (direction == LocalSortDirection.ASCENDING) " ↑" else " ↓"

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

private fun totalSyncedBytes(files: List<SyncedFile>): Long = files.fold(0L) { total, file ->
    if (Long.MAX_VALUE - total < file.size) Long.MAX_VALUE else total + file.size
}

private fun encodeLocalPath(path: List<String>): String = path.joinToString(LOCAL_PATH_SEPARATOR)

private fun decodeLocalPath(value: String): List<String> =
    value.takeIf(String::isNotBlank)?.split(LOCAL_PATH_SEPARATOR) ?: emptyList()

private const val LOCAL_PATH_SEPARATOR = "\u001F"
private const val MAX_SEARCH_LENGTH = 80
private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2")
private val TABLE_EXTENSIONS = setOf("xls", "xlsx", "csv", "numbers")
private val DOCUMENT_EXTENSIONS = setOf("doc", "docx", "txt", "rtf", "pages", "md")

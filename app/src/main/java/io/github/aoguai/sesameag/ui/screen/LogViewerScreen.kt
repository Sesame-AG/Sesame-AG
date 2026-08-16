package io.github.aoguai.sesameag.ui.screen

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.aoguai.sesameag.ui.compose.CommonAlertDialog
import io.github.aoguai.sesameag.ui.screen.components.DelayedLoadingIndicator
import io.github.aoguai.sesameag.ui.viewmodel.LogViewerViewModel
import io.github.aoguai.sesameag.util.LogCatalog
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun LogViewerScreen(
    filePath: String,
    onBackClick: () -> Unit,
) {

    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModelFactory = remember(application) { LogViewerViewModel.factory(application) }
    val viewModel: LogViewerViewModel = viewModel(
        factory = viewModelFactory,
    )
    val state by viewModel.uiState.collectAsState()
    val floatValue by viewModel.fontSize.collectAsState()
    val currentFontSize = floatValue.sp
    val fileMeta = remember(filePath) { LogCatalog.findByFileName(File(filePath).name) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 菜单显示状态
    var showMenu by rememberSaveable(filePath) { mutableStateOf(false) }
    var isSearchActive by rememberSaveable(filePath) { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    var showClearDialog by rememberSaveable(filePath) { mutableStateOf(false) }

    // 拦截返回键
    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        viewModel.search("")
    }

    DisposableEffect(filePath, viewModel) {
        viewModel.loadLogs(filePath)
        onDispose { viewModel.stopLoading() }
    }

    // 自动滚动逻辑
    LaunchedEffect(filePath, viewModel) {
        viewModel.scrollEvent.collectLatest { index ->
            if (index >= 0 && index < viewModel.uiState.value.totalCount) {
                try {
                    listState.scrollToItem(index)
                } catch (_: CancellationException) {
                    // 新的滚动事件会抢占旧协程，属于正常行为，不记为错误。
                } catch (e: Exception) {
                    Log.printStackTrace("LogViewerScreen", "scrollToItem failed", e)
                }
            }
        }
    }

    // 智能自动滚动控制
    LaunchedEffect(listState.canScrollForward, listState.isScrollInProgress) {
        if (!state.isLoading && state.totalCount > 0) {
            if (!listState.canScrollForward) {
                viewModel.toggleAutoScroll(true)
            } else if (listState.isScrollInProgress) {
                viewModel.toggleAutoScroll(false)
            }
        }
    }

    // 自动聚焦
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            delay(100)
            focusRequester.requestFocus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    // ✅ 统一使用 Surface (背景) 和 OnSurface (前景)
                    containerColor = MaterialTheme.colorScheme.background,
//                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
//                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
//                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                        tooltip = { PlainTooltip { Text("返回") } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = {
                            if (isSearchActive) {
                                isSearchActive = false
                                viewModel.search("")
                            } else {
                                onBackClick()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                title = {
                    AnimatedContent(
                        targetState = isSearchActive,
                        transitionSpec = {
                            if (targetState) {
                                (slideInHorizontally { it } + fadeIn()).togetherWith(slideOutHorizontally { -it } + fadeOut())
                            } else {
                                (slideInHorizontally { -it } + fadeIn()).togetherWith(slideOutHorizontally { it } + fadeOut())
                            }
                        },
                        label = "TitleAnimation"
                    ) { searching ->
                        if (searching) {
                            TextField(
                                value = state.searchQuery,
                                onValueChange = { viewModel.search(it) },
                                placeholder = {
                                    Text(
                                        "Search...",
                                        // ✅ 修正颜色：使用 onSurfaceVariant (灰色)，因为背景是 Surface
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    // ✅ 修正光标颜色
                                    cursorColor = MaterialTheme.colorScheme.onSurface
                                ),
                                // ✅ 修正输入文字颜色
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { /* 收起键盘逻辑 */ })
                            )
                        } else {
                            Column {
                                Text(
                                    fileMeta?.displayName ?: File(filePath).name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    // ✅ 统一颜色
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    buildString {
                                        if (state.isExporting) {
                                            append("导出中...")
                                        } else if (state.isLoading) {
                                            append("Loading...")
                                        } else {
                                            append("${state.totalCount} lines")
                                        }
                                        fileMeta?.let { meta ->
                                            append(" · ${meta.moduleDomain.displayName}")
                                            append(" · ${meta.techKind.displayName}")
                                            append(" · ${File(filePath).name}")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    // ✅ 统一颜色
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                fileMeta?.description?.let { description ->
                                    Text(
                                        text = description,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    AnimatedContent(
                        targetState = isSearchActive,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "ActionAnimation"
                    ) { searching ->
                        if (searching) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                                tooltip = { PlainTooltip { Text("退出搜索") } },
                                state = rememberTooltipState()
                            ) {
                                IconButton(onClick = { viewModel.search("") }) {
                                    Icon(Icons.Default.Close, "Clear")
                                }
                            }
                        } else {
                            Row {
                                Box {
                                    TooltipBox(
                                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                                        tooltip = { PlainTooltip { Text("更多选项") } },
                                        state = rememberTooltipState()
                                    ) {
                                        IconButton(
                                            onClick = { showMenu = true },
                                            enabled = !state.isExporting
                                        ) {
                                            Icon(Icons.Default.MoreVert, "More Options")
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        // ... 菜单项保持不变，它们会自动使用 Theme 样式 ...
                                        DropdownMenuItem(
                                            text = { Text("搜索日志") },
                                            onClick = { showMenu = false; isSearchActive = true },
                                            leadingIcon = { Icon(Icons.Default.Search, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("自动滚动") },
                                            onClick = { viewModel.toggleAutoScroll(!state.autoScroll) },
                                            trailingIcon = {
                                                Switch(
                                                    checked = state.autoScroll,
                                                    onCheckedChange = null,
                                                )
                                            },
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("滑动到顶部") },
                                            onClick = { showMenu = false; scope.launch { listState.scrollToItem(0) } },
                                            leadingIcon = { Icon(Icons.Default.VerticalAlignTop, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("滑动到底部") },
                                            onClick = {
                                                showMenu = false
                                                scope.launch {
                                                    val lastIndex = (state.totalCount - 1).coerceAtLeast(0)
                                                    listState.scrollToItem(lastIndex)
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Default.VerticalAlignBottom, null) }
                                        )
                                        HorizontalDivider()

                                        // 二级菜单逻辑 ...
                                        var showFontSubMenu by rememberSaveable(filePath) { mutableStateOf(false) }
                                        Box {
                                            DropdownMenuItem(
                                                text = { Text("字体设置") },
                                                onClick = { showFontSubMenu = true },
                                                leadingIcon = { Icon(Icons.Default.FontDownload, null) }
                                            )
                                            DropdownMenu(
                                                expanded = showFontSubMenu,
                                                onDismissRequest = { showFontSubMenu = false },
                                                offset = DpOffset(x = 10.dp, y = 0.dp)
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("放大字体") },
                                                    onClick = { viewModel.increaseFontSize() },
                                                    leadingIcon = { Icon(Icons.Default.Add, null) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("缩小字体") },
                                                    onClick = { viewModel.decreaseFontSize() },
                                                    leadingIcon = { Icon(Icons.Default.Remove, null) }
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text("重置大小") },
                                                    onClick = { viewModel.resetFontSize(); showFontSubMenu = false },
                                                    leadingIcon = { Icon(Icons.Default.Refresh, null) }
                                                )
                                            }
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("导出文件") },
                                            enabled = !state.isExporting,
                                            onClick = { showMenu = false; viewModel.exportLogFile(context) },
                                            leadingIcon = { Icon(Icons.Default.Share, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("清空日志", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                showMenu = false
                                                showClearDialog= true
                                            },
                                            leadingIcon = { Icon(Icons.Default.CleaningServices, null, tint = MaterialTheme.colorScheme.error) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    )
    { padding ->
        // Body 内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) viewModel.scaleFontSize(zoom)
                    }
                }
        ) {
            if (state.isExporting || (state.isLoading && state.totalCount == 0)) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DelayedLoadingIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (state.isExporting) "导出中..." else "Loading...",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            } else {
                SelectionContainer {
                    val listBottomPadding = if (!state.autoScroll && !state.isSearching) 120.dp else 24.dp
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 2.dp, bottom = listBottomPadding)
                    ) {
                        items(
                            count = state.totalCount,
                            key = { index -> index },
                            contentType = { 1 }
                        ) { index ->
                            LogLineItem(
                                line = viewModel.getLineContent(index),
                                searchQuery = state.searchQuery,
                                fontSize = currentFontSize,
                                textColor = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
                DraggableScrollbar(listState = listState, totalItems = state.totalCount, modifier = Modifier.align(Alignment.CenterEnd))
            }

            if (!state.autoScroll && !state.isSearching) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp, bottom = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (listState.canScrollBackward) {
                        SmallFloatingActionButton(
                            onClick = { scope.launch { listState.scrollToItem(0) } },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, "Top")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (listState.canScrollForward) {
                        SmallFloatingActionButton(
                            onClick = { viewModel.toggleAutoScroll(true) },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, "Bottom")
                        }
                    }
                }
            }

            if (state.isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                        .pointerInput(Unit) {}
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        DelayedLoadingIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Searching...",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }


    }
    CommonAlertDialog(
        showDialog = showClearDialog,
        onDismissRequest = { showClearDialog = false },
        onConfirm = {
            viewModel.clearLogFile(context)
        },
        title = "清空日志",
        text = "确认清空当前日志文件？此操作无法撤销。",
        icon = Icons.Default.CleaningServices,
        iconTint = MaterialTheme.colorScheme.error,
        confirmText = "确认清空",
        confirmButtonColor = MaterialTheme.colorScheme.error
    )
}


@Composable
fun LogLineItem(line: String, searchQuery: String, fontSize: TextUnit, textColor: Color) {
    // 获取高亮颜色
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val onHighlightColor = MaterialTheme.colorScheme.onTertiary

    // 只有当 line 或 searchQuery 变化时，才会重新执行 block 里的计算逻辑
    val annotatedString = remember(line, searchQuery, highlightColor, onHighlightColor) {
        if (searchQuery.isNotEmpty()) {
            buildAnnotatedString {
                val lowerLine = line.lowercase()
                val lowerQuery = searchQuery.lowercase()
                var startIndex = 0
                // 安全限制：防止极长行导致的死循环或超时
                val maxSearchLength = 2000
                val safeLineLength = line.length.coerceAtMost(maxSearchLength)

                while (true) {
                    val index = lowerLine.indexOf(lowerQuery, startIndex)
                    if (index == -1 || index >= safeLineLength) {
                        append(line.substring(startIndex))
                        break
                    }
                    // 添加普通文本
                    append(line.substring(startIndex, index))
                    // 添加高亮文本
                    withStyle(style = SpanStyle(background = highlightColor, color = onHighlightColor)) {
                        append(line.substring(index, index + searchQuery.length))
                    }
                    startIndex = index + searchQuery.length
                }
            }
        } else {
            // 如果没有搜索，直接返回普通 AnnotatedString，开销极小
            // 注意：这里不用 buildAnnotatedString { append(line) }
            // 而是直接用 AnnotatedString(line) 构造，省去 Builder 开销
            AnnotatedString(line)
        }
    }

    Text(
        text = annotatedString,
        color = textColor,
        fontSize = fontSize,
        style = MaterialTheme.typography.bodyMedium.copy(
            lineHeight = fontSize * 1.2f
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.dp) // 减少不必要的 padding
    )
}

@Composable
fun DraggableScrollbar(listState: LazyListState, totalItems: Int, modifier: Modifier = Modifier) {
    if (totalItems <= 0) return
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var isVisible by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(listState.isScrollInProgress, isDragging) {
        if (listState.isScrollInProgress || isDragging) {
            isVisible = true
        } else {
            delay(1500); isVisible = false
        }
    }

    val alpha by animateFloatAsState(targetValue = if (isVisible) 1f else 0f, animationSpec = tween(durationMillis = 300), label = "Alpha")

    val scrollbarInfo by remember(totalItems, trackHeightPx) {
        derivedStateOf {
            if (trackHeightPx == 0f) return@derivedStateOf null
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) return@derivedStateOf null
            val visibleCount = visibleItemsInfo.size
            val firstVisible = listState.firstVisibleItemIndex
            val thumbSizeRatio = (visibleCount.toFloat() / totalItems.toFloat()).coerceIn(0.05f, 1f)
            val thumbHeightPx = trackHeightPx * thumbSizeRatio
            val scrollableHeightPx = trackHeightPx - thumbHeightPx
            val progress = firstVisible.toFloat() / max(1, totalItems - visibleCount).toFloat()
            val thumbOffsetPx = scrollableHeightPx * progress.coerceIn(0f, 1f)
            Pair(thumbHeightPx, thumbOffsetPx)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(48.dp)
            .alpha(alpha)
            .onGloballyPositioned { trackHeightPx = it.size.height.toFloat() }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    isDragging = true
                    if (trackHeightPx > 0f) {
                        val dragRatio = delta / trackHeightPx
                        val targetIndex = (listState.firstVisibleItemIndex + (dragRatio * totalItems)).toInt().coerceIn(0, totalItems - 1)
                        coroutineScope.launch { listState.scrollToItem(targetIndex) }
                    }
                },
                onDragStopped = { isDragging = false }
            )
    ) {
        if (scrollbarInfo != null) {
            val (thumbHeightPx, thumbOffsetPx) = scrollbarInfo!!
            val thumbHeightDp = with(density) { thumbHeightPx.toDp() }
            val thumbOffsetDp = with(density) { thumbOffsetPx.toDp() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .width(4.dp)
                    .height(thumbHeightDp)
                    .offset(y = thumbOffsetDp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            )
        }
    }
}


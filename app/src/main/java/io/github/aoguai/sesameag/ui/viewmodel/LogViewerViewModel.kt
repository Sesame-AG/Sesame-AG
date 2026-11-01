package io.github.aoguai.sesameag.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.FileObserver
import androidx.core.content.edit
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aoguai.sesameag.R
import io.github.aoguai.sesameag.SesameApplication.Companion.PREFERENCES_KEY
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Logback
import io.github.aoguai.sesameag.util.ModuleDiagnostics
import io.github.aoguai.sesameag.util.PermissionUtil
import io.github.aoguai.sesameag.util.ToastUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.aoguai.sesameag.util.SerialTaskQueue
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 日志 UI 状态
 */
data class LogUiState(
    val isLoading: Boolean = true,
    val isExporting: Boolean = false,
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val totalCount: Int = 0,
    val autoScroll: Boolean = true
)

/**
 * 日志查看器 ViewModel。
 * 只读取当前活动日志文件，文件变化时重新加载当前段。
 */
class LogViewerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val tag = "LogViewerViewModel"

    private val prefs = application.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
    private val logFontSizeKey = "pref_font_size"

    private val _uiState = MutableStateFlow(
        LogUiState(searchQuery = savedStateHandle.get<String>(STATE_SEARCH_QUERY).orEmpty())
    )
    val uiState = _uiState.asStateFlow()

    private val _fontSize = MutableStateFlow(prefs.getFloat(logFontSizeKey, 12f))
    val fontSize = _fontSize.asStateFlow()

    private val _scrollEvent = Channel<Int>(Channel.BUFFERED)
    val scrollEvent = _scrollEvent.receiveAsFlow()

    private val fileUpdateChannel = Channel<Unit>(Channel.CONFLATED)
    private var fileObserver: FileObserver? = null
    private var currentFilePath: String? = null
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var exportJob: Job? = null
    private var updateJob: Job? = null

    private data class LogLineRange(val start: Long, val end: Long, val endsLine: Boolean)

    private val allLines = mutableListOf<LogLineRange>()
    private var displayLines by mutableStateOf<List<LogLineRange>>(emptyList(), neverEqualPolicy())
    // 只缓存按需显示的文本；历史内容留在文件中，搜索使用位置索引。
    private val lineCache = LinkedHashMap<LogLineRange, MutableState<String>>(16, 0.75f, true)

    private val contentQueue = SerialTaskQueue()
    private val forceFullReload = AtomicBoolean(true)
    @Volatile
    private var readOffset = 0L
    private var pendingLineOffset = 0L
    private var knownContentMarker: Long? = null

    @OptIn(FlowPreview::class)
    fun loadLogs(path: String) {
        if (currentFilePath == path && loadJob?.isActive == true) return

        if (currentFilePath != path) ModuleDiagnostics.event("open_log", "opened", "file=${File(path).name}")
        currentFilePath = path
        loadJob?.cancel()
        updateJob?.cancel()
        stopFileObserver()

        updateJob = viewModelScope.launch {
            fileUpdateChannel.receiveAsFlow()
                // 合并异步日志落盘产生的连续文件通知，避免每次小写入都重读列表。
                .debounce(200)
                .collectLatest {
                    reloadCurrentFile(forceFullReload.getAndSet(false))
                }
        }

        loadJob = viewModelScope.launch {
            contentQueue.submit {
                allLines.clear()
                displayLines = emptyList()
                lineCache.clear()
                readOffset = 0L
                pendingLineOffset = 0L
                knownContentMarker = null
            }
            forceFullReload.set(true)
            _uiState.update { it.copy(isLoading = true, totalCount = 0) }
            reloadCurrentFile(forceFull = true)
            forceFullReload.set(false)
            startFileObserver(path)
        }
    }

    private suspend fun reloadCurrentFile(forceFull: Boolean = false) = withContext(Dispatchers.IO) {
        val path = currentFilePath ?: return@withContext
        val file = File(path)
        val activeContext = currentCoroutineContext()

        try {
            contentQueue.submit {
                activeContext.ensureActive()
                if (currentFilePath != path) return@submit
                val invalidateCache: Boolean
                if (!file.exists() || !file.canRead()) {
                    allLines.clear()
                    pendingLineOffset = 0L
                    readOffset = 0L
                    knownContentMarker = null
                    invalidateCache = true
                } else {
                    val fileLength = file.length()
                    val currentContentMarker = contentMarker(file, readOffset)
                    val contentWasReplaced = readOffset > 0L &&
                        (knownContentMarker == null || currentContentMarker == null ||
                            currentContentMarker != knownContentMarker)
                    val needsFullReload = forceFull || fileLength < readOffset || readOffset == 0L || contentWasReplaced
                    invalidateCache = needsFullReload
                    if (needsFullReload) {
                        loadWholeFile(file, fileLength)
                    } else if (fileLength > readOffset) {
                        appendFileRange(file, fileLength)
                    } else {
                        return@submit
                    }
                }
                activeContext.ensureActive()
                refreshListLocked(invalidateCache)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(tag, "读取日志失败: ${file.absolutePath}", e)
            _uiState.update { it.copy(isLoading = false, isSearching = false) }
        }
    }

    private suspend fun loadWholeFile(file: File, fileLength: Long) {
        allLines.clear()
        readOffset = 0L
        pendingLineOffset = 0L
        appendFileRange(file, fileLength)
    }

    private suspend fun appendFileRange(file: File, fileLength: Long) {
        val activeContext = currentCoroutineContext()
        val startOffset = pendingLineOffset
        val ranges = ArrayList<LogLineRange>()
        var lineStart = startOffset
        RandomAccessFile(file, "r").use { input ->
            input.seek(startOffset)
            val buffer = ByteArray(DEFAULT_READ_BUFFER_SIZE)
            var offset = startOffset
            // 只处理本次快照的长度，避免追逐持续追加的文件直到耗尽内存。
            while (offset < fileLength) {
                activeContext.ensureActive()
                val count = minOf(buffer.size.toLong(), fileLength - offset).toInt()
                input.readFully(buffer, 0, count)
                for (index in 0 until count) {
                    val byte = buffer[index].toInt() and 0xff
                    val position = offset + index
                    // 超长物理行分段显示，在 UTF-8 字符边界切分，不丢弃正文。
                    if (position - lineStart >= MAX_DISPLAY_LINE_BYTES &&
                        byte != '\n'.code && (byte and 0xc0) != 0x80
                    ) {
                        ranges.add(LogLineRange(lineStart, position, false))
                        lineStart = position
                    }
                    if (byte == '\n'.code) {
                        ranges.add(LogLineRange(lineStart, position + 1, true))
                        lineStart = position + 1
                    }
                }
                offset += count
            }
        }
        if (lineStart < fileLength) ranges.add(LogLineRange(lineStart, fileLength, false))
        activeContext.ensureActive()
        if (currentFilePath != file.path) return
        // 重新读取上一段未结束的行，也重新解码其可能未写完整的 UTF-8 字符。
        if (allLines.lastOrNull()?.start == startOffset) allLines.removeAt(allLines.lastIndex)
        allLines.addAll(ranges)
        pendingLineOffset = lineStart
        readOffset = fileLength
        knownContentMarker = contentMarker(file, fileLength)
    }

    private suspend fun refreshList() = withContext(Dispatchers.IO) {
        contentQueue.submit {
            refreshListLocked()
        }
    }

    private suspend fun refreshListLocked(invalidateCache: Boolean = false) {
        val activeContext = currentCoroutineContext()
        val path = currentFilePath ?: return
        val query = _uiState.value.searchQuery.trim()
        val sourceLines = allLines.toList()
        val resultLines = if (query.isEmpty() || sourceLines.isEmpty()) {
            sourceLines
        } else {
            val matches = ArrayList<LogLineRange>()
            RandomAccessFile(path, "r").use { input ->
                var firstPart = 0
                var matched = false
                var tail = ""
                sourceLines.forEachIndexed { index, range ->
                    activeContext.ensureActive()
                    if (!matched) {
                        val buffer = ByteArray((range.end - range.start).toInt())
                        input.seek(range.start)
                        input.readFully(buffer)
                        val text = tail + buffer.toString(Charsets.UTF_8).removeSuffix("\n").removeSuffix("\r")
                        matched = text.contains(query, ignoreCase = true)
                        tail = if (matched) "" else text.takeLast(query.length - 1)
                    }
                    if (range.endsLine || index == sourceLines.lastIndex) {
                        // 跨显示分段的关键词仍可匹配，命中后保留整条物理日志。
                        if (matched) matches.addAll(sourceLines.subList(firstPart, index + 1))
                        firstPart = index + 1
                        matched = false
                        tail = ""
                    }
                }
            }
            matches
        }
        activeContext.ensureActive()
        withContext(Dispatchers.Main) {
            if (currentFilePath != path) return@withContext
            if (invalidateCache) lineCache.clear()
            displayLines = resultLines
            _uiState.update {
                it.copy(
                    totalCount = resultLines.size,
                    isLoading = false,
                    isSearching = false,
                )
            }
            if (_uiState.value.autoScroll && resultLines.isNotEmpty()) {
                _scrollEvent.send(resultLines.size - 1)
            }
        }
    }

    fun getLineContent(position: Int): String {
        val lines = displayLines
        val range = lines.getOrNull(position) ?: return ""
        val path = currentFilePath ?: return ""
        lineCache[range]?.let { return it.value }
        // 未结束的尾行追加时先保留已显示的正文，避免异步读取期间行高归零。
        val previousText = lineCache.entries.lastOrNull { it.key.start == range.start }?.value?.value.orEmpty()
        if (lineCache.size >= MAX_CACHED_LINES) {
            val oldest = lineCache.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        val content = mutableStateOf(previousText)
        lineCache[range] = content
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    RandomAccessFile(path, "r").use { input ->
                        val buffer = ByteArray((range.end - range.start).toInt())
                        input.seek(range.start)
                        input.readFully(buffer)
                        buffer.toString(Charsets.UTF_8).removeSuffix("\n").removeSuffix("\r")
                    }
                }
                if (currentFilePath == path && lineCache[range] === content) {
                    content.value = text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.printStackTrace(tag, "读取日志行失败: $path", e)
            }
        }
        return content.value
    }

    private fun startFileObserver(path: String) {
        val file = File(path)
        val parent = file.parentFile ?: return
        val targetName = file.name
        val eventMask =
            FileObserver.MODIFY or
                FileObserver.CREATE or
                FileObserver.CLOSE_WRITE or
                FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO

        stopFileObserver()
        fileObserver = object : FileObserver(parent, eventMask) {
            override fun onEvent(event: Int, changedPath: String?) {
                if (changedPath == null || changedPath == targetName) {
                    val isFileReplaced = event.and(FileObserver.CREATE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO) != 0
                    val isTruncated = event.and(FileObserver.MODIFY) != 0 && file.length() < readOffset
                    if (isFileReplaced || isTruncated) {
                        forceFullReload.set(true)
                    }
                    fileUpdateChannel.trySend(Unit)
                }
            }
        }
        fileObserver?.startWatching()
    }

    fun search(query: String) {
        searchJob?.cancel()
        savedStateHandle[STATE_SEARCH_QUERY] = query
        _uiState.update { it.copy(searchQuery = query, isSearching = true) }
        searchJob = viewModelScope.launch {
            // 搜索输入停止 300ms 后再过滤，避免每次击键扫描日志；清空搜索立即刷新。
            if (query.isNotEmpty()) delay(300)
            refreshList()
        }
    }

    fun clearLogFile(context: Context) {
        val path = currentFilePath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cleared = if (path == Logback.systemLogFile?.absolutePath) {
                    ModuleDiagnostics.clear(context)
                } else Files.clearFile(File(path))
                ModuleDiagnostics.event("clear_log", if (cleared) "completed" else "failed", "file=${File(path).name}")
                if (cleared) {
                    forceFullReload.set(true)
                    fileUpdateChannel.trySend(Unit)
                    withContext(Dispatchers.Main) {
                        ToastUtil.showUiToast(context, "文件已清空")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        ToastUtil.showUiToast(context, "清空失败")
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(tag, "Clear error", e)
                withContext(Dispatchers.Main) {
                    ToastUtil.showUiToast(context, "清空异常: ${e.message}")
                }
            }
        }
    }

    fun exportLogFile(context: Context) {
        val path = currentFilePath ?: return
        if (exportJob?.isActive == true || _uiState.value.isExporting) return
        exportJob = viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            ModuleDiagnostics.event("export_log", "requested", "file=${File(path).name}")
            try {
                val sourceFile = File(path)
                if (!sourceFile.exists()) {
                    ModuleDiagnostics.event("export_log", "source_missing", "file=${sourceFile.name}")
                    ToastUtil.showUiToast(context, "源文件不存在")
                    return@launch
                }
                val exportedFile = withContext(Dispatchers.IO) {
                    Files.exportFile(context, sourceFile, hasTime = true, mimeType = "application/octet-stream")
                } ?: error("无法保存到下载目录")
                ModuleDiagnostics.event("export_log", "completed", "file=${exportedFile.name}")
                val relativePath = exportedFile.relativeTo(android.os.Environment.getExternalStorageDirectory())
                ToastUtil.makeText(
                    context,
                    "${context.getString(R.string.file_exported)}：${relativePath.parent}/\n${relativePath.name}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ModuleDiagnostics.event("export_log", "failed", "error=${e.javaClass.simpleName}")
                Log.printStackTrace(tag, "Export error", e)
                ToastUtil.showUiToast(context, "导出异常: ${e.message}")
            } finally {
                _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    private fun saveFontSize(size: Float) {
        prefs.edit { putFloat(logFontSizeKey, size) }
    }

    fun increaseFontSize() {
        _fontSize.update { current ->
            val newValue = (current + 2f).coerceAtMost(30f)
            saveFontSize(newValue)
            newValue
        }
    }

    fun decreaseFontSize() {
        _fontSize.update { current ->
            val newValue = (current - 2f).coerceAtLeast(8f)
            saveFontSize(newValue)
            newValue
        }
    }

    fun scaleFontSize(factor: Float) {
        _fontSize.update { current ->
            val newValue = (current * factor).coerceIn(8f, 50f)
            saveFontSize(newValue)
            newValue
        }
    }

    fun resetFontSize() {
        _fontSize.value = 12f
        saveFontSize(12f)
    }

    fun toggleAutoScroll(enabled: Boolean) {
        _uiState.update { it.copy(autoScroll = enabled) }
        if (enabled) viewModelScope.launch {
            val size = _uiState.value.totalCount
            if (size > 0) _scrollEvent.send(size - 1)
        }
    }

    private fun stopFileObserver() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    fun stopLoading() {
        currentFilePath = null
        stopFileObserver()
        loadJob?.cancel()
        searchJob?.cancel()
        updateJob?.cancel()
        loadJob = null
        searchJob = null
        updateJob = null
        fileUpdateChannel.tryReceive()
        allLines.clear()
        displayLines = emptyList()
        lineCache.clear()
        readOffset = 0L
        pendingLineOffset = 0L
        knownContentMarker = null
        forceFullReload.set(true)
        _uiState.update {
            it.copy(
                isLoading = true,
                isSearching = false,
                totalCount = 0,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLoading()
        exportJob?.cancel()
    }

    private fun contentMarker(file: File, contentLength: Long): Long? = runCatching {
        if (contentLength == 0L) return@runCatching 0L
        val sampleSize = minOf(contentLength, CONTENT_MARKER_SAMPLE_BYTES.toLong()).toInt()
        val sampleOffsets = longArrayOf(
            0L,
            (contentLength - sampleSize) / 2,
            contentLength - sampleSize,
        ).distinct()
        RandomAccessFile(file, "r").use { randomAccessFile ->
            sampleOffsets.fold(1_125_899_906_842_597L) { hash, offset ->
                val buffer = ByteArray(sampleSize)
                randomAccessFile.seek(offset)
                randomAccessFile.readFully(buffer)
                buffer.fold(hash) { currentHash, byte -> currentHash * 31 + byte.toLong() }
            }
        }
    }.getOrNull()

    companion object {
        private const val STATE_SEARCH_QUERY = "log_viewer_search_query"
        private const val DEFAULT_READ_BUFFER_SIZE = 8192
        private const val MAX_DISPLAY_LINE_BYTES = 16 * 1024
        private const val MAX_CACHED_LINES = 512
        private const val CONTENT_MARKER_SAMPLE_BYTES = 1024

        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LogViewerViewModel(
                    application = application,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}

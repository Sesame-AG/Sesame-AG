package io.github.aoguai.sesameag.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.FileObserver
import androidx.core.content.edit
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val lineLock = Any()
    private val allLines = mutableListOf<String>()
    private var displayLines: List<String> = emptyList()

    private val contentMutex = Mutex()
    private val forceFullReload = AtomicBoolean(true)
    @Volatile
    private var readOffset = 0L
    private var pendingLine = ""
    private var knownContentMarker: Long? = null

    @OptIn(FlowPreview::class)
    fun loadLogs(path: String) {
        if (currentFilePath == path && loadJob?.isActive == true) return

        currentFilePath = path
        loadJob?.cancel()
        updateJob?.cancel()
        stopFileObserver()

        updateJob = viewModelScope.launch {
            fileUpdateChannel.receiveAsFlow()
                .debounce(200)
                .collectLatest {
                    reloadCurrentFile(forceFullReload.getAndSet(false))
                }
        }

        loadJob = viewModelScope.launch {
            synchronized(lineLock) {
                allLines.clear()
                displayLines = emptyList()
            }
            readOffset = 0L
            pendingLine = ""
            knownContentMarker = null
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
            contentMutex.withLock {
                activeContext.ensureActive()
                if (!file.exists() || !file.canRead()) {
                    synchronized(lineLock) {
                        allLines.clear()
                        displayLines = emptyList()
                    }
                    pendingLine = ""
                    readOffset = 0L
                    knownContentMarker = null
                } else {
                    val fileLength = file.length()
                    val currentContentMarker = contentMarker(file, readOffset)
                    val contentWasReplaced = readOffset > 0L &&
                        (knownContentMarker == null || currentContentMarker == null ||
                            currentContentMarker != knownContentMarker)
                    val needsFullReload = forceFull || fileLength < readOffset || readOffset == 0L || contentWasReplaced
                    if (needsFullReload) {
                        loadWholeFile(file, fileLength)
                    } else if (fileLength > readOffset) {
                        appendFileRange(file, fileLength)
                    }
                }
                activeContext.ensureActive()
                refreshListLocked()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(tag, "读取日志失败: ${file.absolutePath}", e)
            _uiState.update { it.copy(isLoading = false, isSearching = false) }
        }
    }

    private suspend fun loadWholeFile(file: File, fileLength: Long) {
        val activeContext = currentCoroutineContext()
        val loadedLines = ArrayList<String>()
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                activeContext.ensureActive()
                val line = reader.readLine() ?: break
                loadedLines.add(line)
            }
        }
        val endsWithLineBreak = fileLength == 0L || RandomAccessFile(file, "r").use { randomAccessFile ->
            randomAccessFile.seek(fileLength - 1)
            randomAccessFile.readByte().toInt() == '\n'.code
        }
        pendingLine = if (!endsWithLineBreak && loadedLines.isNotEmpty()) {
            loadedLines.removeAt(loadedLines.lastIndex)
        } else {
            ""
        }
        synchronized(lineLock) {
            allLines.clear()
            allLines.addAll(loadedLines)
        }
        readOffset = fileLength
        knownContentMarker = contentMarker(file, fileLength)
    }

    private suspend fun appendFileRange(file: File, fileLength: Long) {
        val activeContext = currentCoroutineContext()
        val appendedText = RandomAccessFile(file, "r").use { randomAccessFile ->
            randomAccessFile.seek(readOffset)
            val buffer = ByteArray(DEFAULT_READ_BUFFER_SIZE)
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                activeContext.ensureActive()
                val count = randomAccessFile.read(buffer)
                if (count <= 0) break
                bytes.write(buffer, 0, count)
            }
            bytes.toString(Charsets.UTF_8.name())
        }
        readOffset = fileLength
        knownContentMarker = contentMarker(file, fileLength)
        if (appendedText.isEmpty()) return

        val combined = pendingLine + appendedText
        val parts = combined.split('\n')
        val completeLines = parts.dropLast(1).map { it.removeSuffix("\r") }
        pendingLine = if (combined.endsWith('\n')) "" else parts.last().removeSuffix("\r")
        if (completeLines.isNotEmpty()) {
            synchronized(lineLock) {
                allLines.addAll(completeLines)
            }
        }
    }

    private suspend fun refreshList() = withContext(Dispatchers.IO) {
        contentMutex.withLock {
            refreshListLocked()
        }
    }

    private suspend fun refreshListLocked() {
        val activeContext = currentCoroutineContext()
        val query = _uiState.value.searchQuery.trim()
        val resultLines = synchronized(lineLock) {
            val sourceLines = if (pendingLine.isEmpty()) allLines else allLines + pendingLine
            if (query.isEmpty()) {
                sourceLines
            } else {
                sourceLines.filter { line ->
                    activeContext.ensureActive()
                    line.contains(query, ignoreCase = true)
                }
            }.also { displayLines = it }
        }

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

    fun getLineContent(position: Int): String = synchronized(lineLock) {
        displayLines.getOrNull(position).orEmpty()
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
            if (query.isNotEmpty()) delay(300)
            refreshList()
        }
    }

    fun clearLogFile(context: Context) {
        val path = currentFilePath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (Files.clearFile(File(path))) {
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
            try {
                val sourceFile = File(path)
                if (!sourceFile.exists()) {
                    ToastUtil.showUiToast(context, "源文件不存在")
                    return@launch
                }
                val exportFile = withContext(Dispatchers.IO) {
                    Files.exportFile(sourceFile, true)
                }

                if (exportFile != null && exportFile.exists()) {
                    val msg = "${context.getString(R.string.file_exported)} ${exportFile.path}"
                    ToastUtil.showUiToast(context, msg)
                } else {
                    ToastUtil.showUiToast(context, "导出失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
        if (_uiState.value.autoScroll == enabled) return
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
        synchronized(lineLock) {
            allLines.clear()
            displayLines = emptyList()
        }
        readOffset = 0L
        pendingLine = ""
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

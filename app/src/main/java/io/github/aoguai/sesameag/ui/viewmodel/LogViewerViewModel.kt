package io.github.aoguai.sesameag.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.FileObserver
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 日志 UI 状态
 */
data class LogUiState(
    val mappingList: List<Int> = emptyList(),
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
class LogViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "LogViewerViewModel"

    private val prefs = application.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
    private val logFontSizeKey = "pref_font_size"

    private val _uiState = MutableStateFlow(LogUiState())
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

    @Volatile
    private var allLines: List<String> = emptyList()

    @Volatile
    private var displayLines: List<String> = emptyList()

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
                    reloadCurrentFile()
                }
        }

        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, mappingList = emptyList(), totalCount = 0) }
            reloadCurrentFile()
            startFileObserver(path)
        }
    }

    private suspend fun reloadCurrentFile() = withContext(Dispatchers.IO) {
        val path = currentFilePath ?: return@withContext
        val file = File(path)

        try {
            ensureActive()
            val lines = if (file.exists() && file.canRead()) {
                file.readLines(Charsets.UTF_8)
            } else {
                emptyList()
            }
            ensureActive()
            allLines = lines
            refreshList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(tag, "读取日志失败: ${file.absolutePath}", e)
            _uiState.update { it.copy(isLoading = false, isSearching = false) }
        }
    }

    private suspend fun refreshList() {
        val query = _uiState.value.searchQuery.trim()
        val resultLines = withContext(Dispatchers.IO) {
            if (query.isEmpty()) {
                allLines.toList()
            } else {
                allLines.filter { line ->
                    ensureActive()
                    line.contains(query, ignoreCase = true)
                }
            }
        }

        displayLines = resultLines
        val newMapping = List(resultLines.size) { it }

        _uiState.update {
            it.copy(
                mappingList = newMapping,
                totalCount = resultLines.size,
                isLoading = false,
                isSearching = false
            )
        }

        if (_uiState.value.autoScroll && resultLines.isNotEmpty()) {
            _scrollEvent.send(resultLines.size - 1)
        }
    }

    fun getLineContent(position: Int): String = displayLines.getOrNull(position).orEmpty()

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
                    fileUpdateChannel.trySend(Unit)
                }
            }
        }
        fileObserver?.startWatching()
    }

    fun search(query: String) {
        searchJob?.cancel()
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
            val size = _uiState.value.mappingList.size
            if (size > 0) _scrollEvent.send(size - 1)
        }
    }

    private fun stopFileObserver() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    override fun onCleared() {
        super.onCleared()
        stopFileObserver()
        loadJob?.cancel()
        searchJob?.cancel()
        exportJob?.cancel()
        updateJob?.cancel()
    }
}

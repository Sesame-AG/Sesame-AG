package io.github.aoguai.sesameag.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.model.CustomSettings
import io.github.aoguai.sesameag.model.modelFieldExt.TimeFieldMeta
import io.github.aoguai.sesameag.ui.screen.components.DelayedLoadingIndicator
import io.github.aoguai.sesameag.ui.screen.components.SettingsExitDraftDialog
import io.github.aoguai.sesameag.ui.screen.components.SettingsSaveIconButton
import io.github.aoguai.sesameag.ui.screen.components.TimeRuleEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class OnceDailyModule(val id: String, val name: String)

private data class OnceDailyDraft(
    val enabled: Boolean,
    val automaticFullRun: Boolean,
    val selectedModules: Set<String>,
    val timeWindows: List<Pair<String, String>>,
)

private data class OnceDailyLoadedState(
    val modules: List<OnceDailyModule>,
    val draft: OnceDailyDraft,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnceDailySettingsScreen(
    userId: String,
    userName: String,
    onBack: () -> Unit,
) {
    var enabled by rememberSaveable(userId) { mutableStateOf(false) }
    var automaticFullRun by rememberSaveable(userId) { mutableStateOf(false) }
    var selectedModules by rememberSaveable(userId) { mutableStateOf<Set<String>>(emptySet()) }
    var timeWindows by rememberSaveable(userId) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var draftInitialized by rememberSaveable(userId) { mutableStateOf(false) }
    var modules by remember(userId) { mutableStateOf<List<OnceDailyModule>>(emptyList()) }
    var errorMessage by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var savedMessage by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var savedDraft by remember(userId) { mutableStateOf<OnceDailyDraft?>(null) }
    var showExitDialog by rememberSaveable(userId) { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var loadAttempt by rememberSaveable(userId) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(userId, loadAttempt) {
        errorMessage = null
        runCatching {
            withContext(Dispatchers.IO) {
                CustomSettings.load(userId)
                val loadedEnabled = CustomSettings.onlyOnceDaily.value == true
                val loadedAutomaticFullRun = loadedEnabled && CustomSettings.autoHandleOnceDaily.value == true
                val loadedSelectedModules = CustomSettings.onlyOnceDailyList.value
                    .orEmpty()
                    .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                    .toSet()
                val loadedTimeWindows = parseTimeWindows(CustomSettings.autoHandleOnceDailyTimes.value)
                OnceDailyLoadedState(
                    modules = CustomSettings.onlyOnceDailyList.getExpandValue()
                        .orEmpty()
                        .map { OnceDailyModule(it.id, it.name) },
                    draft = OnceDailyDraft(
                        enabled = loadedEnabled,
                        automaticFullRun = loadedAutomaticFullRun,
                        selectedModules = loadedSelectedModules,
                        timeWindows = loadedTimeWindows,
                    ),
                )
            }
        }.onSuccess { loaded ->
            modules = loaded.modules
            savedDraft = loaded.draft
            if (!draftInitialized) {
                enabled = loaded.draft.enabled
                automaticFullRun = loaded.draft.automaticFullRun
                selectedModules = loaded.draft.selectedModules
                timeWindows = loaded.draft.timeWindows
                draftInitialized = true
            }
        }.onFailure { errorMessage = it.message ?: "加载失败" }
    }

    val currentDraft = OnceDailyDraft(enabled, automaticFullRun, selectedModules, timeWindows)
    val isReady = savedDraft != null
    val isDirty = savedDraft?.let { it != currentDraft } == true

    LaunchedEffect(isDirty) {
        if (isDirty) savedMessage = null
    }

    fun save(onSuccess: (() -> Unit)? = null) {
        if (!isReady || isSaving) return
        val normalizedWindows = mutableListOf<Pair<String, String>>()
        timeWindows.forEachIndexed { index, (start, end) ->
            val normalizedStart = normalizeTimeToken(start)
            if (normalizedStart == null) {
                savedMessage = null
                errorMessage = "第 ${index + 1} 个开始时间无效"
                return
            }
            val normalizedEnd = normalizeTimeToken(end, allowDayEnd = true)
            if (normalizedEnd == null) {
                savedMessage = null
                errorMessage = "第 ${index + 1} 个结束时间无效"
                return
            }
            if (normalizedStart == normalizedEnd) {
                savedMessage = null
                errorMessage = "第 ${index + 1} 个时间段不能相同"
                return
            }
            normalizedWindows += normalizedStart to normalizedEnd
        }
        scope.launch {
            isSaving = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CustomSettings.onlyOnceDaily.setObjectValue(enabled)
                    CustomSettings.autoHandleOnceDaily.setObjectValue(automaticFullRun)
                    CustomSettings.onlyOnceDailyList.setObjectValue(LinkedHashSet(selectedModules))
                    CustomSettings.autoHandleOnceDailyTimes.setConfigValue(
                        if (normalizedWindows.isEmpty()) {
                            "-1"
                        } else {
                            normalizedWindows.joinToString(",") { (start, end) -> "$start-$end" }
                        }
                    )
                    check(CustomSettings.trySave(userId)) { "配置文件保存失败" }
                }
            }
            result.onSuccess {
                timeWindows = normalizedWindows.toList()
                savedDraft = OnceDailyDraft(
                    enabled = enabled,
                    automaticFullRun = automaticFullRun,
                    selectedModules = selectedModules.toSet(),
                    timeWindows = normalizedWindows.toList(),
                )
                errorMessage = null
                savedMessage = "已保存"
                onSuccess?.invoke()
            }.onFailure {
                savedMessage = null
                errorMessage = it.message ?: "保存失败"
            }
            isSaving = false
        }
    }

    fun requestBack() {
        if (isSaving) return
        if (isDirty || (draftInitialized && !isReady)) showExitDialog = true else onBack()
    }

    LaunchedEffect(isReady, isDirty, showExitDialog) {
        if (showExitDialog && isReady && !isDirty) {
            showExitDialog = false
            onBack()
        }
    }

    BackHandler(onBack = ::requestBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("每日单次运行")
                        Text(
                            text = userName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    SettingsSaveIconButton(
                        isDirty = isDirty,
                        isSaving = isSaving,
                        enabled = isReady,
                        onSave = { save() },
                    )
                },
            )
        },
        floatingActionButton = {
            if (isReady) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.scrollToItem(0) } },
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "滚动到顶部")
                    }
                    SmallFloatingActionButton(
                        onClick = {
                            val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            scope.launch { listState.scrollToItem(lastIndex) }
                        },
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "滚动到底部")
                    }
                }
            }
        },
    ) { padding ->
        when {
            !isReady && errorMessage == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                DelayedLoadingIndicator()
            }
            !isReady -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = errorMessage ?: "加载失败",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { loadAttempt += 1 }) {
                    Text("重试")
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    ToggleRow(
                        title = "启用每日单次运行",
                        supportingText = "今日完成首次全量运行后，后续按已选模块跳过。",
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            if (!checked) automaticFullRun = false
                        },
                    )
                }
                item {
                    ToggleRow(
                        title = "定时自动全量运行",
                        supportingText = "命中下方时段时，临时放开单次运行限制。",
                        checked = automaticFullRun,
                        onCheckedChange = { automaticFullRun = it },
                        enabled = enabled,
                    )
                }
                item {
                    Text("跳过模块", style = MaterialTheme.typography.titleMedium)
                }
                items(modules, key = { it.id }) { module ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = module.id in selectedModules,
                            onCheckedChange = { checked ->
                                selectedModules = if (checked) {
                                    selectedModules + module.id
                                } else {
                                    selectedModules - module.id
                                }
                            },
                        )
                        Text(module.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                item {
                    Text("自动全量时段", style = MaterialTheme.typography.titleMedium)
                    TimeRuleEditor(
                        type = "TIME_WINDOW_LIST",
                        value = timeWindows.joinToString(",") { (start, end) -> "$start-$end" },
                        meta = (CustomSettings.autoHandleOnceDailyTimes.getEditorMeta() as? TimeFieldMeta)
                            ?.copy(allowDisable = false)
                            ?: TimeFieldMeta(allowWindows = true, displayMode = "range-list"),
                        onValueChange = { rawValue ->
                            timeWindows = parseTimeWindows(rawValue)
                        },
                    )
                }
                errorMessage?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                savedMessage?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
    if (showExitDialog) {
        SettingsExitDraftDialog(
            isSaving = isSaving,
            saveEnabled = isReady && isDirty,
            onSave = {
                showExitDialog = false
                save(onSuccess = onBack)
            },
            onDiscard = {
                showExitDialog = false
                onBack()
            },
            onContinue = { showExitDialog = false },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

private fun parseTimeWindows(rawValue: String?): List<Pair<String, String>> = rawValue
    .orEmpty()
    .takeUnless { it == "-1" }
    .orEmpty()
    .split(',')
    .mapNotNull { token ->
        val parts = token.trim().split('-', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }

private fun normalizeTimeToken(raw: String, allowDayEnd: Boolean = false): String? {
    val digits = raw.filter(Char::isDigit)
    if (digits.length !in setOf(3, 4)) return null
    val normalized = digits.padStart(4, '0')
    val hour = normalized.substring(0, 2).toIntOrNull() ?: return null
    val minute = normalized.substring(2, 4).toIntOrNull() ?: return null
    if (allowDayEnd && hour == 24 && minute == 0) return "2400"
    if (hour !in 0..23 || minute !in 0..59) return null
    return normalized
}

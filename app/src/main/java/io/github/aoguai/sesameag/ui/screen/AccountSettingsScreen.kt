package io.github.aoguai.sesameag.ui.screen

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.aoguai.sesameag.entity.KVMap
import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.entity.friend.FriendRelation
import io.github.aoguai.sesameag.entity.friend.FriendRelationFilter
import io.github.aoguai.sesameag.entity.friend.FriendSelectionCountSpec
import io.github.aoguai.sesameag.entity.friend.FriendSelectionScope
import io.github.aoguai.sesameag.entity.friend.FriendSelectionSpec
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceSwitchMeta
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.modelFieldExt.TimeFieldMeta
import io.github.aoguai.sesameag.ui.screen.components.DelayedLoadingIndicator
import io.github.aoguai.sesameag.ui.screen.components.SettingsExitDraftDialog
import io.github.aoguai.sesameag.ui.screen.components.SettingsSaveIconButton
import io.github.aoguai.sesameag.ui.screen.components.TimeRuleEditor
import io.github.aoguai.sesameag.ui.viewmodel.AccountModelUiModel
import io.github.aoguai.sesameag.ui.viewmodel.AccountSettingsUiState
import io.github.aoguai.sesameag.ui.viewmodel.AccountSettingsViewModel
import io.github.aoguai.sesameag.ui.viewmodel.FieldEditorUiModel
import io.github.aoguai.sesameag.ui.viewmodel.FieldKey
import io.github.aoguai.sesameag.ui.viewmodel.FieldOptionUiModel
import io.github.aoguai.sesameag.ui.viewmodel.FieldOptionsState
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.friend.FriendSelectionResolver
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferExportMode
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
private data object AccountModelListRoute : NavKey

@Serializable
private data class AccountModelDetailRoute(val modelCode: String) : NavKey

private enum class AccountDialog { EXIT, IMPORT_OVERWRITE, EXPORT_MODE, DELETE_CONFIG }

private data class SelectionRequest(
    val field: FieldEditorUiModel,
    val single: Boolean,
    val withCount: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AccountSettingsScreen(
    userId: String,
    userName: String,
    onBack: () -> Unit,
    onOpenFriendCenter: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val accountViewModel: AccountSettingsViewModel = viewModel(
        key = "account-settings-$userId",
        factory = AccountSettingsViewModel.factory(application, userId),
    )
    val state by accountViewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val modelListState = rememberLazyListState()
    val modelFieldsState = rememberLazyListState()

    val modelBackStack = rememberNavBackStack(AccountModelListRoute)
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()
    val selectedModelCode = (modelBackStack.lastOrNull() as? AccountModelDetailRoute)?.modelCode
    var menuExpanded by rememberSaveable(userId) { mutableStateOf(false) }
    var activeDialog by rememberSaveable(userId) { mutableStateOf<AccountDialog?>(null) }
    var pendingExportMode by rememberSaveable(userId) { mutableStateOf(SettingsTransferExportMode.SHARE) }
    var selectionModelCode by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var selectionFieldCode by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var friendModelCode by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var friendFieldCode by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var localMessage by rememberSaveable(userId) { mutableStateOf<String?>(null) }

    val selectionRequest = state.findField(selectionModelCode, selectionFieldCode)?.let(::selectionRequestFor)
    val activeFriendField = state.findField(friendModelCode, friendFieldCode)
    val activeListState = if (selectedModelCode?.let(state::findModel) != null) {
        modelFieldsState
    } else {
        modelListState
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                scope.launch {
                    val operation = accountViewModel.import(context, uri)
                    localMessage = operation.exceptionOrNull()?.message
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                scope.launch {
                    val operation = accountViewModel.export(context, uri, pendingExportMode)
                    localMessage = operation.fold(
                        onSuccess = { "导出成功" },
                        onFailure = { it.message ?: "导出失败" },
                    )
                }
            }
        }
    }

    fun saveAccount() {
        scope.launch {
            val result = accountViewModel.save(context)
            localMessage = result.exceptionOrNull()?.message
        }
    }

    fun requestBack() {
        if (modelBackStack.size > 1) {
            modelBackStack.removeLastOrNull()
        } else if (state.isDirty) {
            activeDialog = AccountDialog.EXIT
        } else {
            onBack()
        }
    }

    fun launchImport() {
        importLauncher.launch(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
                putExtra(Intent.EXTRA_TITLE, "settings.json")
            }
        )
    }

    fun launchExport(mode: SettingsTransferExportMode) {
        pendingExportMode = mode
        val safeName = userName.takeIf(String::isNotBlank) ?: userId.ifBlank { "default" }
        val fileName = when (mode) {
            SettingsTransferExportMode.SHARE -> "settings-share.json"
            SettingsTransferExportMode.BACKUP -> "[$safeName]-settings-backup.json"
        }
        exportLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
        )
    }

    BackHandler(enabled = activeFriendField == null, onBack = ::requestBack)

    if (activeFriendField != null) {
        FriendSelectionEditorScreen(
            field = activeFriendField,
            value = state.drafts[activeFriendField.key].orEmpty(),
            userId = userId,
            onBack = {
                friendModelCode = null
                friendFieldCode = null
            },
            onSave = { value ->
                accountViewModel.updateDraft(activeFriendField.key, value)
                friendModelCode = null
                friendFieldCode = null
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = selectedModelCode?.let { code -> state.findModel(code)?.name } ?: "账号配置",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = userName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (selectedModelCode == null) {
                        IconButton(onClick = ::requestBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                },
                actions = {
                    SettingsSaveIconButton(
                        isDirty = state.isDirty,
                        isSaving = state.isSaving,
                        enabled = !state.isLoading && !state.isSaving && state.unsupportedFields.isEmpty(),
                        contentDescription = "保存配置",
                        onSave = ::saveAccount,
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("导入配置") },
                                leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    if (state.isDirty) activeDialog = AccountDialog.IMPORT_OVERWRITE else launchImport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导出配置") },
                                leadingIcon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    if (state.isDirty) {
                                        localMessage = "请先保存当前草稿再导出"
                                    } else {
                                        activeDialog = AccountDialog.EXPORT_MODE
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制账号 ID") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    copyText(context, "Alipay user ID", userId)
                                    localMessage = "账号 ID 已复制"
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("好友中心") },
                                leadingIcon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenFriendCenter()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除配置", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    activeDialog = AccountDialog.DELETE_CONFIG
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.isLoading && state.loadError == null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { activeListState.scrollToItem(0) } },
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "滚动到顶部")
                    }
                    SmallFloatingActionButton(
                        onClick = {
                            val lastIndex = (activeListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            scope.launch { activeListState.scrollToItem(lastIndex) }
                        },
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "滚动到底部")
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DelayedLoadingIndicator()
                }
                state.loadError != null -> BlockingMessage(
                    title = "无法载入账号配置",
                    message = state.loadError.orEmpty(),
                    actionLabel = "重试",
                    onAction = accountViewModel::reload,
                )
                else -> {
                    NavDisplay(
                        backStack = modelBackStack,
                        sceneStrategy = listDetailStrategy,
                        onBack = ::requestBack,
                        entryProvider = entryProvider {
                            entry<AccountModelListRoute>(
                                metadata = ListDetailSceneStrategy.listPane(
                                    detailPlaceholder = { BlockingMessage("选择模块", "从左侧选择一个模块以编辑字段。") },
                                ),
                            ) {
                                ModelList(
                                    state = state,
                                    listState = modelListState,
                                    selectedModelCode = selectedModelCode,
                                    onSelectModel = {
                                        if (selectedModelCode != it) modelBackStack.add(AccountModelDetailRoute(it))
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            entry<AccountModelDetailRoute>(
                                metadata = ListDetailSceneStrategy.detailPane(),
                            ) { route ->
                                val selectedModel = state.findModel(route.modelCode)
                                if (selectedModel == null) {
                                    BlockingMessage("模块不存在", "当前配置中找不到该模块。")
                                } else {
                                    ModelFields(
                                        model = selectedModel,
                                        state = state,
                                        listState = modelFieldsState,
                                        viewModel = accountViewModel,
                                        onOpenSelection = {
                                            selectionModelCode = it.field.key.modelCode
                                            selectionFieldCode = it.field.key.fieldCode
                                        },
                                        onOpenFriendSelection = {
                                            friendModelCode = it.key.modelCode
                                            friendFieldCode = it.key.fieldCode
                                        },
                                        onClearAudit = accountViewModel::clearFieldAudit,
                                        onMessage = { localMessage = it },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    selectionRequest?.let { request ->
        SelectionEditorDialog(
            request = request,
            value = state.drafts[request.field.key].orEmpty(),
            optionsState = state.fieldOptions[request.field.key] ?: FieldOptionsState.NotRequested,
            onLoad = { accountViewModel.loadFieldOptions(request.field.key) },
            onDismiss = {
                selectionModelCode = null
                selectionFieldCode = null
            },
            onSave = { value ->
                accountViewModel.updateDraft(request.field.key, value)
                selectionModelCode = null
                selectionFieldCode = null
            },
        )
    }

    when (activeDialog) {
        AccountDialog.EXIT -> SettingsExitDraftDialog(
            isSaving = state.isSaving,
            onSave = {
                scope.launch {
                    val result = accountViewModel.save(context)
                    if (result.isSuccess) {
                        onBack()
                    } else {
                        activeDialog = null
                        localMessage = result.exceptionOrNull()?.message
                    }
                }
            },
            onDiscard = {
                accountViewModel.discardDrafts()
                activeDialog = null
                onBack()
            },
            onContinue = { activeDialog = null },
        )
        AccountDialog.IMPORT_OVERWRITE -> ConfirmDialog(
            title = "覆盖当前草稿",
            message = "导入会覆盖当前页面尚未保存的修改。",
            confirmText = "继续导入",
            danger = true,
            onDismiss = { activeDialog = null },
            onConfirm = {
                activeDialog = null
                launchImport()
            },
        )
        AccountDialog.EXPORT_MODE -> ExportModeDialog(
            onDismiss = { activeDialog = null },
            onSelect = { mode ->
                activeDialog = null
                launchExport(mode)
            },
        )
        AccountDialog.DELETE_CONFIG -> ConfirmDialog(
            title = "删除账号配置",
            message = "将删除该账号的配置目录。此操作无法撤销。",
            confirmText = "删除",
            danger = true,
            onDismiss = { activeDialog = null },
            onConfirm = {
                activeDialog = null
                scope.launch {
                    val result = accountViewModel.deleteConfig()
                    if (result.isSuccess) onDeleted() else localMessage = result.exceptionOrNull()?.message
                }
            },
        )
        null -> Unit
    }

    val visibleMessage = localMessage ?: state.operationMessage
    if (visibleMessage != null) {
        AlertDialog(
            onDismissRequest = {
                localMessage = null
                accountViewModel.clearMessage()
            },
            title = { Text(if (visibleMessage.contains("成功")) "操作完成" else "操作结果") },
            text = { Text(visibleMessage) },
            confirmButton = {
                TextButton(onClick = {
                    localMessage = null
                    accountViewModel.clearMessage()
                }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun ModelList(
    state: AccountSettingsUiState,
    listState: LazyListState,
    selectedModelCode: String?,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
    ) {
        if (state.unsupportedFields.isNotEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                        Text(
                            "发现 ${state.unsupportedFields.size} 个缺少编辑器的字段，修复前无法保存。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        state.groups.forEach { group ->
            item(key = "group-${group.code}") {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(group.models, key = { it.code }) { model ->
                ListItem(
                    supportingContent = { Text("${model.fields.size} 个字段") },
                    trailingContent = {
                        if (model.fields.any { state.savedValues[it.key] != state.drafts[it.key] }) {
                            Text("已修改", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.clickable { onSelectModel(model.code) },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = if (model.code == selectedModelCode) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Text(model.name)
                }
            }
        }
    }
}

@Composable
private fun ModelFields(
    model: AccountModelUiModel,
    state: AccountSettingsUiState,
    listState: LazyListState,
    viewModel: AccountSettingsViewModel,
    onOpenSelection: (SelectionRequest) -> Unit,
    onOpenFriendSelection: (FieldEditorUiModel) -> Unit,
    onClearAudit: (FieldKey) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(model.name, style = MaterialTheme.typography.titleLarge)
        }
        items(model.fields, key = { "${it.key.modelCode}-${it.key.fieldCode}" }) { field ->
            FieldEditor(
                field = field,
                value = state.drafts[field.key].orEmpty(),
                modelField = viewModel.currentField(field.key),
                onValueChange = { viewModel.updateDraft(field.key, it) },
                onOpenSelection = { single, withCount ->
                    onOpenSelection(SelectionRequest(field, single, withCount))
                },
                onOpenFriendSelection = { onOpenFriendSelection(field) },
                auditClearPending = field.key in state.pendingAuditClearKeys,
                onClearAudit = { onClearAudit(field.key) },
                onRunAction = {
                    scope.launch {
                        onMessage(
                            viewModel.runFieldAction(field.key).fold(
                                onSuccess = { it },
                                onFailure = { it.message ?: "操作失败" },
                            )
                        )
                    }
                },
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun FieldEditor(
    field: FieldEditorUiModel,
    value: String,
    modelField: io.github.aoguai.sesameag.model.ModelField<*>?,
    onValueChange: (String) -> Unit,
    onOpenSelection: (single: Boolean, withCount: Boolean) -> Unit,
    onOpenFriendSelection: () -> Unit,
    auditClearPending: Boolean,
    onClearAudit: () -> Unit,
    onRunAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(field.name, style = MaterialTheme.typography.titleMedium)
        if (field.desc.isNotBlank()) {
            Text(field.desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (field.todayInactive) {
            Text(
                field.todayInactiveReason.ifBlank { "当前字段今日暂不生效" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        field.audit?.let { audit ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        audit.title.ifBlank { "历史好友配置已失效" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        buildString {
                            append(audit.message)
                            if (audit.staleCount > 0) append("（历史项 ${audit.staleCount} 个）")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    TextButton(
                        onClick = onClearAudit,
                        enabled = !auditClearPending && audit.clearValue.isNotBlank(),
                    ) {
                        Text(if (auditClearPending) "保存后生效" else "清理并改写为空配置")
                    }
                }
            }
        }
        when (field.type) {
            "BOOLEAN" -> {
                val checked = value.equals("true", ignoreCase = true)
                FieldSwitch(
                    checked = checked,
                    stateText = if (checked) "已开启" else "已关闭",
                    onCheckedChange = { onValueChange(it.toString()) },
                )
            }
            "CHOICE" -> {
                val switchMeta = field.editorMeta as? ChoiceSwitchMeta
                if (switchMeta == null) {
                    ChoiceField(field, value, onValueChange)
                } else {
                    ChoiceSwitchField(field, value, switchMeta, onValueChange)
                }
            }
            "SELECT_ONE" -> ValueButton(selectionSummary(value, emptyList(), true)) { onOpenSelection(true, false) }
            "INTEGER", "MULTIPLY_INTEGER" -> IntegerField(value, modelField as? IntegerModelField, onValueChange)
            "STRING" -> OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(field.name) },
                singleLine = true,
            )
            "LIST" -> ListField(value, onValueChange)
            "TEXT", "READ_TEXT" -> ReadOnlyValue(decodeJsonString(value))
            "URL_TEXT" -> UrlValue(decodeJsonString(value))
            "SELECT" -> ValueButton(selectionSummary(value, emptyList(), false)) { onOpenSelection(false, false) }
            "SELECT_AND_COUNT" -> ValueButton(mapSummary(value)) { onOpenSelection(false, true) }
            "SELECT_AND_COUNT_ONE" -> ValueButton(singleCountSummary(value)) { onOpenSelection(true, true) }
            "FRIEND_SELECTION", "FRIEND_SELECTION_COUNT" -> ValueButton(friendSummary(value, field.type)) {
                onOpenFriendSelection()
            }
            "TIME_POINT", "TIME_POINT_LIST", "TIME_WINDOW_LIST", "TIME_TRIGGER", "HOUR_OF_DAY" ->
                TimeField(field, value, onValueChange)
            "EMPTY" -> {
                if (field.hasAction) {
                    Button(onClick = onRunAction) {
                        Icon(Icons.Outlined.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("执行")
                    }
                } else {
                    ReadOnlyValue(field.desc.ifBlank { "无配置项" })
                }
            }
            else -> Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("当前版本缺少编辑器", color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "模块：${field.modelName}\n字段：${field.key.fieldCode}\n类型：${field.type}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun FieldSwitch(
    checked: Boolean,
    stateText: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier =
            Modifier.toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stateText,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun ChoiceSwitchField(
    field: FieldEditorUiModel,
    value: String,
    meta: ChoiceSwitchMeta,
    onValueChange: (String) -> Unit,
) {
    val selected = value.toIntOrNull() ?: meta.offIndex
    val checked = selected == meta.onIndex
    val stateIndex = if (checked) meta.onIndex else meta.offIndex
    val stateText = field.expandKey.getOrNull(stateIndex)
        ?.takeIf(String::isNotBlank)
        ?: if (checked) "已开启" else "已关闭"
    FieldSwitch(
        checked = checked,
        stateText = stateText,
        onCheckedChange = { enabled ->
            onValueChange((if (enabled) meta.onIndex else meta.offIndex).toString())
        },
    )
}

@Composable
private fun ChoiceField(field: FieldEditorUiModel, value: String, onValueChange: (String) -> Unit) {
    val choices = field.expandKey
    val selected = value.toIntOrNull() ?: 0
    if (choices.size <= 4) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(choices) { index, label ->
                FilterChip(
                    selected = selected == index,
                    onClick = { onValueChange(index.toString()) },
                    label = { Text(label) },
                )
            }
        }
    } else {
        var expanded by rememberSaveable(field.key.modelCode, field.key.fieldCode) { mutableStateOf(false) }
        ValueButton(choices.getOrNull(selected) ?: "选择") { expanded = true }
        if (expanded) {
            AlertDialog(
                onDismissRequest = { expanded = false },
                title = { Text(field.name) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(choices) { index, label ->
                            ListItem(
                                leadingContent = { RadioButton(selected == index, onClick = null) },
                                modifier = Modifier.clickable {
                                    onValueChange(index.toString())
                                    expanded = false
                                },
                            ) {
                                Text(label)
                            }
                        }
                    }
                },
                confirmButton = {},
            )
        }
    }
}

@Composable
private fun IntegerField(value: String, field: IntegerModelField?, onValueChange: (String) -> Unit) {
    val parsed = value.toIntOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onValueChange(((parsed ?: 0) - 1).toString()) },
            enabled = field?.minLimit == null || (parsed ?: 0) > field.minLimit!!,
        ) { Icon(Icons.Outlined.Remove, contentDescription = "减少") }
        OutlinedTextField(
            value = value,
            onValueChange = { raw -> if (raw.isEmpty() || raw == "-" || raw.toIntOrNull() != null) onValueChange(raw) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            isError = value.toIntOrNull() == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                val limits = listOfNotNull(field?.minLimit?.let { "最小 $it" }, field?.maxLimit?.let { "最大 $it" })
                if (limits.isNotEmpty()) Text(limits.joinToString("，"))
            },
        )
        IconButton(
            onClick = { onValueChange(((parsed ?: 0) + 1).toString()) },
            enabled = field?.maxLimit == null || (parsed ?: 0) < field.maxLimit!!,
        ) { Icon(Icons.Outlined.Add, contentDescription = "增加") }
    }
}

@Composable
private fun ListField(value: String, onValueChange: (String) -> Unit) {
    var display by rememberSaveable {
        mutableStateOf(listDisplayValue(value))
    }
    LaunchedEffect(value) {
        if (serializeListValue(display) != value) {
            display = listDisplayValue(value)
        }
    }
    OutlinedTextField(
        value = display,
        onValueChange = { text ->
            display = text
            onValueChange(serializeListValue(text))
        },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        label = { Text("每行一项") },
    )
}

@Composable
private fun TimeField(field: FieldEditorUiModel, value: String, onValueChange: (String) -> Unit) {
    val meta = field.editorMeta as? TimeFieldMeta
    TimeRuleEditor(
        type = field.type,
        value = value,
        meta = meta,
        onValueChange = onValueChange,
    )
}

@Composable
private fun ReadOnlyValue(value: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(value.ifBlank { "无内容" }, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UrlValue(value: String) {
    val uriHandler = LocalUriHandler.current
    Surface(
        onClick = { if (value.isNotBlank()) uriHandler.openUri(value) },
        enabled = value.isNotBlank(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value.ifBlank { "无链接" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "打开链接")
        }
    }
}

@Composable
private fun ValueButton(summary: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(summary.ifBlank { "选择" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SelectionEditorDialog(
    request: SelectionRequest,
    value: String,
    optionsState: FieldOptionsState,
    onLoad: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    LaunchedEffect(request.field.key) { onLoad() }
    var search by rememberSaveable(request.field.key.modelCode, request.field.key.fieldCode) { mutableStateOf("") }
    var values by rememberSaveable(request.field.key.modelCode, request.field.key.fieldCode, value) {
        mutableStateOf(parseSelectionValues(value, request))
    }
    val options = (optionsState as? FieldOptionsState.Ready)?.options.orEmpty()
    val filtered = options.filter { it.name.contains(search, true) || it.id.contains(search, true) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .imePadding()
                    .padding(16.dp)
            ) {
                Text(request.field.name, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    label = { Text("搜索") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                when (optionsState) {
                    FieldOptionsState.NotRequested, FieldOptionsState.Loading ->
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            DelayedLoadingIndicator()
                        }
                    is FieldOptionsState.Error -> BlockingMessage("选项加载失败", optionsState.message, "重试", onLoad)
                    is FieldOptionsState.Ready -> LazyColumn(Modifier.weight(1f)) {
                        items(filtered, key = { it.id }) { option ->
                            SelectionOptionRow(
                                option = option,
                                selected = option.id in values,
                                count = values[option.id],
                                single = request.single,
                                withCount = request.withCount,
                                onChange = { selected, count ->
                                    values = when {
                                        !selected -> values - option.id
                                        request.single -> mapOf(option.id to count)
                                        else -> values + (option.id to count)
                                    }
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = { onSave(serializeSelectionValues(values, request)) },
                        enabled = optionsState is FieldOptionsState.Ready,
                    ) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun SelectionOptionRow(
    option: FieldOptionUiModel,
    selected: Boolean,
    count: Int?,
    single: Boolean,
    withCount: Boolean,
    onChange: (Boolean, Int) -> Unit,
) {
    ListItem(
        supportingContent = { if (option.name != option.id) Text(option.id) },
        leadingContent = {
            if (single) {
                RadioButton(
                    selected = selected,
                    onClick = { onChange(!selected, count ?: 1) },
                )
            } else {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { checked -> onChange(checked, count ?: 1) },
                )
            }
        },
        trailingContent = if (withCount && selected) {
            {
                OutlinedTextField(
                    value = (count ?: 1).toString(),
                    onValueChange = { raw -> raw.toIntOrNull()?.let { onChange(true, it) } },
                    modifier = Modifier.width(88.dp),
                    label = { Text("次数") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        } else null,
    ) {
        Text(option.name)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendSelectionEditorScreen(
    field: FieldEditorUiModel,
    value: String,
    userId: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
) {
    val countEnabled = field.type == "FRIEND_SELECTION_COUNT"
    val parsed = remember(field.key, value) { parseFriendDraft(value, countEnabled) }
    var scopeValue by rememberSaveable(field.key.modelCode, field.key.fieldCode) {
        mutableStateOf(parsed.selection.selectionScope)
    }
    var relation by rememberSaveable(field.key.modelCode, field.key.fieldCode) {
        mutableStateOf(parsed.selection.relationFilter)
    }
    var includeUsers by rememberSaveable(field.key.modelCode, field.key.fieldCode) {
        mutableStateOf(parsed.selection.includeUserIds.toSet())
    }
    var includeGroups by rememberSaveable(field.key.modelCode, field.key.fieldCode) {
        mutableStateOf(parsed.selection.includeGroupIds.toSet())
    }
    var excludeUsers by rememberSaveable(field.key.modelCode, field.key.fieldCode) {
        mutableStateOf(parsed.selection.excludeUserIds.toSet())
    }
    var excludeGroups by rememberSaveable(field.key.modelCode, field.key.fieldCode) {
        mutableStateOf(parsed.selection.excludeGroupIds.toSet())
    }
    var defaultCount by rememberSaveable(field.key.modelCode, field.key.fieldCode) {
        mutableStateOf(parsed.defaultCount.toString())
    }
    var userCounts by rememberSaveable(field.key.modelCode, field.key.fieldCode) {
        mutableStateOf(parsed.userCountOverrides.toMap())
    }
    var groupCounts by rememberSaveable(field.key.modelCode, field.key.fieldCode) {
        mutableStateOf(parsed.groupCountOverrides.toMap())
    }
    var editExclusions by rememberSaveable(field.key.modelCode, field.key.fieldCode) { mutableStateOf(false) }
    var search by rememberSaveable(field.key.modelCode, field.key.fieldCode) { mutableStateOf("") }
    var showExitDialog by rememberSaveable(field.key.modelCode, field.key.fieldCode) { mutableStateOf(false) }
    val friendConfig = remember(userId) { FriendRepository.current(userId) }

    fun selection(): FriendSelectionSpec = FriendSelectionSpec(
        selectionScope = scopeValue,
        includeUserIds = LinkedHashSet(includeUsers),
        includeGroupIds = LinkedHashSet(includeGroups),
        excludeUserIds = LinkedHashSet(excludeUsers),
        excludeGroupIds = LinkedHashSet(excludeGroups),
        relationFilter = relation,
        capabilityFilter = parsed.selection.capabilityFilter,
    )

    fun countSpec() = FriendSelectionCountSpec(
        selection = selection(),
        defaultCount = defaultCount.toIntOrNull() ?: 0,
        groupCountOverrides = LinkedHashMap(groupCounts),
        userCountOverrides = LinkedHashMap(userCounts),
    )

    val currentSelection = selection()
    val currentCountSpec = countSpec()
    val isDirty = if (countEnabled) currentCountSpec != parsed else currentSelection != parsed.selection

    fun saveFriendSelection() {
        onSave(JsonUtil.formatJson(if (countEnabled) currentCountSpec else currentSelection, false))
    }

    fun requestBack() {
        if (isDirty) showExitDialog = true else onBack()
    }

    val preview = if (countEnabled) {
        FriendSelectionResolver.previewCount(currentCountSpec, userId)
    } else {
        FriendSelectionResolver.preview(currentSelection, userId)
    }

    BackHandler(onBack = ::requestBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(field.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    SettingsSaveIconButton(
                        isDirty = isDirty,
                        contentDescription = "保存好友选择",
                        onSave = ::saveFriendSelection,
                    )
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    FriendRuleEditor(
                        stateKey = "${field.key.modelCode}\u0000${field.key.fieldCode}",
                        friendConfig = friendConfig,
                        search = search,
                        onSearchChange = { search = it },
                        selectionScope = scopeValue,
                        onScopeChange = { scopeValue = it },
                        relation = relation,
                        onRelationChange = { relation = it },
                        editExclusions = editExclusions,
                        onEditExclusionsChange = { editExclusions = it },
                        includedUsers = includeUsers,
                        onIncludedUsersChange = { includeUsers = it },
                        excludedUsers = excludeUsers,
                        onExcludedUsersChange = { excludeUsers = it },
                        includedGroups = includeGroups,
                        onIncludedGroupsChange = { includeGroups = it },
                        excludedGroups = excludeGroups,
                        onExcludedGroupsChange = { excludeGroups = it },
                        countEnabled = countEnabled,
                        defaultCount = defaultCount,
                        onDefaultCountChange = { defaultCount = it },
                        userCounts = userCounts,
                        onUserCountsChange = { userCounts = it },
                        groupCounts = groupCounts,
                        onGroupCountsChange = { groupCounts = it },
                        capabilityText = capabilityText(parsed.selection),
                        modifier = Modifier.weight(1f),
                    )
                    VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                    FriendPreview(
                        preview = preview,
                        modifier = Modifier.width(340.dp).fillMaxHeight(),
                        scrollable = true,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        FriendRuleEditor(
                            stateKey = "${field.key.modelCode}\u0000${field.key.fieldCode}",
                            friendConfig = friendConfig,
                            search = search,
                            onSearchChange = { search = it },
                            selectionScope = scopeValue,
                            onScopeChange = { scopeValue = it },
                            relation = relation,
                            onRelationChange = { relation = it },
                            editExclusions = editExclusions,
                            onEditExclusionsChange = { editExclusions = it },
                            includedUsers = includeUsers,
                            onIncludedUsersChange = { includeUsers = it },
                            excludedUsers = excludeUsers,
                            onExcludedUsersChange = { excludeUsers = it },
                            includedGroups = includeGroups,
                            onIncludedGroupsChange = { includeGroups = it },
                            excludedGroups = excludeGroups,
                            onExcludedGroupsChange = { excludeGroups = it },
                            countEnabled = countEnabled,
                            defaultCount = defaultCount,
                            onDefaultCountChange = { defaultCount = it },
                            userCounts = userCounts,
                            onUserCountsChange = { userCounts = it },
                            groupCounts = groupCounts,
                            onGroupCountsChange = { groupCounts = it },
                            capabilityText = capabilityText(parsed.selection),
                        )
                    }
                    item { FriendPreview(preview) }
                }
            }
        }
    }
    if (showExitDialog) {
        SettingsExitDraftDialog(
            onSave = ::saveFriendSelection,
            onDiscard = {
                showExitDialog = false
                onBack()
            },
            onContinue = { showExitDialog = false },
        )
    }
}

@Composable
private fun FriendRuleEditor(
    stateKey: String,
    friendConfig: io.github.aoguai.sesameag.entity.friend.FriendCenterConfig,
    search: String,
    onSearchChange: (String) -> Unit,
    selectionScope: FriendSelectionScope,
    onScopeChange: (FriendSelectionScope) -> Unit,
    relation: FriendRelationFilter,
    onRelationChange: (FriendRelationFilter) -> Unit,
    editExclusions: Boolean,
    onEditExclusionsChange: (Boolean) -> Unit,
    includedUsers: Set<String>,
    onIncludedUsersChange: (Set<String>) -> Unit,
    excludedUsers: Set<String>,
    onExcludedUsersChange: (Set<String>) -> Unit,
    includedGroups: Set<String>,
    onIncludedGroupsChange: (Set<String>) -> Unit,
    excludedGroups: Set<String>,
    onExcludedGroupsChange: (Set<String>) -> Unit,
    countEnabled: Boolean,
    defaultCount: String,
    onDefaultCountChange: (String) -> Unit,
    userCounts: Map<String, Int>,
    onUserCountsChange: (Map<String, Int>) -> Unit,
    groupCounts: Map<String, Int>,
    onGroupCountsChange: (Map<String, Int>) -> Unit,
    capabilityText: String?,
    modifier: Modifier = Modifier,
) {
    val profiles = friendConfig.profiles.values
        .filter { it.relation != FriendRelation.SELF }
        .filter { it.displayName.contains(search, true) || it.userId.contains(search, true) }
        .sortedBy { it.displayName.ifBlank { it.userId } }
    var batchMode by rememberSaveable(stateKey) { mutableStateOf(false) }
    var batchSelectedUsers by rememberSaveable(stateKey) { mutableStateOf(setOf<String>()) }
    var batchCount by rememberSaveable(stateKey) {
        mutableStateOf(defaultCount.toIntOrNull()?.takeIf { it >= 0 }?.toString() ?: "1")
    }
    val visibleUserIds = profiles.map { it.userId }.toSet()

    fun exitBatchMode() {
        batchMode = false
        batchSelectedUsers = emptySet()
    }

    fun includeBatch() {
        if (batchSelectedUsers.isEmpty()) return
        onIncludedUsersChange(includedUsers + batchSelectedUsers)
        onExcludedUsersChange(excludedUsers - batchSelectedUsers)
    }

    fun excludeBatch() {
        if (batchSelectedUsers.isEmpty()) return
        onExcludedUsersChange(excludedUsers + batchSelectedUsers)
        onIncludedUsersChange(includedUsers - batchSelectedUsers)
    }

    fun clearBatchRules() {
        if (batchSelectedUsers.isEmpty()) return
        onIncludedUsersChange(includedUsers - batchSelectedUsers)
        onExcludedUsersChange(excludedUsers - batchSelectedUsers)
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("选择范围", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FriendSelectionScope.entries) { item ->
                FilterChip(
                    selected = selectionScope == item,
                    onClick = { onScopeChange(item) },
                    label = { Text(if (item == FriendSelectionScope.ALL_FRIENDS) "全部好友" else "显式选择") },
                )
            }
        }
        Text("关系过滤", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FriendRelationFilter.entries) { item ->
                FilterChip(
                    selected = relation == item,
                    onClick = { onRelationChange(item) },
                    label = { Text(relationLabel(item)) },
                )
            }
        }
        capabilityText?.let {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                Text(it, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = !editExclusions,
                    onClick = { onEditExclusionsChange(false) },
                    label = { Text("包含") },
                )
            }
            item {
                FilterChip(
                    selected = editExclusions,
                    onClick = { onEditExclusionsChange(true) },
                    label = { Text("排除") },
                )
            }
        }
        if (countEnabled) {
            OutlinedTextField(
                value = defaultCount,
                onValueChange = { if (it.isEmpty() || it.toIntOrNull() != null) onDefaultCountChange(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("默认次数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Text("好友分组", style = MaterialTheme.typography.titleMedium)
        friendConfig.groups.forEach { group ->
            val selected = if (editExclusions) {
                group.id in excludedGroups
            } else {
                group.id in includedGroups
            }
            SelectionCheckRow(
                title = group.name.ifBlank { group.id },
                subtitle = "${group.memberIds.size} 位好友",
                selected = selected,
                onSelectedChange = { checked ->
                    if (editExclusions) {
                        onExcludedGroupsChange(if (checked) excludedGroups + group.id else excludedGroups - group.id)
                        if (checked) onIncludedGroupsChange(includedGroups - group.id)
                    } else {
                        onIncludedGroupsChange(if (checked) includedGroups + group.id else includedGroups - group.id)
                        if (checked) onExcludedGroupsChange(excludedGroups - group.id)
                    }
                },
                count = if (countEnabled && !editExclusions && selected) {
                    groupCounts[group.id] ?: defaultCount.toIntOrNull() ?: 1
                } else {
                    null
                },
                onCountChange = { count -> onGroupCountsChange(groupCounts + (group.id to count)) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("好友", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (batchMode) {
                Text(
                    "已选 ${batchSelectedUsers.size} 人",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { if (batchMode) exitBatchMode() else batchMode = true }) {
                Icon(
                    Icons.Outlined.Checklist,
                    contentDescription = if (batchMode) "退出批量选择" else "批量选择好友",
                )
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text("搜索好友") },
            singleLine = true,
        )
        if (batchMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { batchSelectedUsers = visibleUserIds },
                    enabled = visibleUserIds.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("全选")
                }
                OutlinedButton(
                    onClick = {
                        batchSelectedUsers = linkedSetOf<String>().apply {
                            addAll(batchSelectedUsers)
                            visibleUserIds.forEach { userId ->
                                if (!add(userId)) remove(userId)
                            }
                        }.toSet()
                    },
                    enabled = visibleUserIds.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("反选")
                }
                OutlinedButton(
                    onClick = ::exitBatchMode,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("退出")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectionScope == FriendSelectionScope.EXPLICIT) {
                    Button(
                        onClick = ::includeBatch,
                        enabled = batchSelectedUsers.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("设为包含")
                    }
                }
                OutlinedButton(
                    onClick = ::excludeBatch,
                    enabled = batchSelectedUsers.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("设为排除")
                }
                OutlinedButton(
                    onClick = ::clearBatchRules,
                    enabled = batchSelectedUsers.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(if (selectionScope == FriendSelectionScope.ALL_FRIENDS) "取消排除" else "取消设置")
                }
            }
            if (countEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = batchCount,
                        onValueChange = { raw ->
                            if (raw.isEmpty() || raw.all { char -> char.isDigit() }) batchCount = raw
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("批量次数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Button(
                        onClick = {
                            batchCount.toIntOrNull()?.takeIf { it >= 0 }?.let { count ->
                                onUserCountsChange(userCounts + batchSelectedUsers.associateWith { count })
                            }
                        },
                        enabled = batchSelectedUsers.isNotEmpty() &&
                            batchCount.toIntOrNull()?.let { it >= 0 } == true,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("设置次数")
                    }
                }
            }
        }
        profiles.forEach { profile ->
            val selected = if (editExclusions) {
                profile.userId in excludedUsers
            } else {
                profile.userId in includedUsers
            }
            val batchSelected = profile.userId in batchSelectedUsers
            val ruleStatus = when {
                profile.userId in excludedUsers -> "已排除"
                selectionScope == FriendSelectionScope.ALL_FRIENDS -> "动态包含"
                profile.userId in includedUsers -> "已包含"
                else -> "未单独设置"
            }
            SelectionCheckRow(
                title = profile.displayName.ifBlank { profile.userId },
                subtitle = buildString {
                    append(relationLabel(profile.relation))
                    if (profile.globalBlocked) append(" · 全局黑名单")
                    if (profile.removed) append(" · 已失效")
                    if (batchMode) append(" · $ruleStatus")
                },
                selected = if (batchMode) batchSelected else selected,
                onSelectedChange = { checked ->
                    if (batchMode) {
                        batchSelectedUsers = if (checked) {
                            batchSelectedUsers + profile.userId
                        } else {
                            batchSelectedUsers - profile.userId
                        }
                    } else if (editExclusions) {
                        onExcludedUsersChange(if (checked) excludedUsers + profile.userId else excludedUsers - profile.userId)
                        if (checked) onIncludedUsersChange(includedUsers - profile.userId)
                    } else {
                        onIncludedUsersChange(if (checked) includedUsers + profile.userId else includedUsers - profile.userId)
                        if (checked) onExcludedUsersChange(excludedUsers - profile.userId)
                    }
                },
                count = if (countEnabled && !batchMode && !editExclusions && selected) {
                    userCounts[profile.userId] ?: defaultCount.toIntOrNull() ?: 1
                } else {
                    null
                },
                onCountChange = { count -> onUserCountsChange(userCounts + (profile.userId to count)) },
            )
        }
    }
}

@Composable
private fun SelectionCheckRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    count: Int?,
    onCountChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(selected, onCheckedChange = onSelectedChange)
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable { onSelectedChange(!selected) }
                .padding(vertical = 8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (count != null) {
            OutlinedTextField(
                value = count.toString(),
                onValueChange = { it.toIntOrNull()?.let(onCountChange) },
                modifier = Modifier.width(88.dp),
                label = { Text("次数") },
                singleLine = true,
            )
        }
    }
}

@Composable
private fun FriendPreview(
    preview: io.github.aoguai.sesameag.util.friend.FriendSelectionPreview,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
) {
    val contentModifier = if (scrollable) {
        modifier.verticalScroll(rememberScrollState())
    } else {
        modifier
    }
    Column(contentModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("生效预览", style = MaterialTheme.typography.titleMedium)
        Text(
            "已配置 ${preview.summary.selectedCount}，生效 ${preview.summary.effectiveCount}，失效 ${preview.summary.inactiveCount}",
            style = MaterialTheme.typography.bodyMedium,
        )
        preview.items.filter { it.effective || it.inactiveReason.isNotBlank() }.take(40).forEach { item ->
            ListItem(
                supportingContent = {
                    Text(if (item.effective) "生效${item.count?.let { " · $it 次" }.orEmpty()}" else item.inactiveReason)
                },
                trailingContent = {
                    Icon(
                        if (item.effective) Icons.Outlined.Check else Icons.Outlined.ErrorOutline,
                        contentDescription = if (item.effective) "生效" else "未生效",
                        tint = if (item.effective) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                },
            ) {
                Text(item.displayName.ifBlank { item.userId })
            }
        }
        if (preview.items.size > 40) {
            Text("另有 ${preview.items.size - 40} 项未展开", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BlockingMessage(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ExportModeDialog(onDismiss: () -> Unit, onSelect: (SettingsTransferExportMode) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出配置") },
        text = {
            Column {
                ListItem(
                    supportingContent = { Text("不包含账号私有字段") },
                    modifier = Modifier.clickable { onSelect(SettingsTransferExportMode.SHARE) },
                ) {
                    Text("分享通用配置")
                }
                ListItem(
                    supportingContent = { Text("备份当前账号及好友中心数据") },
                    modifier = Modifier.clickable { onSelect(SettingsTransferExportMode.BACKUP) },
                ) {
                    Text("完整备份")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun AccountSettingsUiState.findModel(code: String): AccountModelUiModel? =
    groups.asSequence().flatMap { it.models.asSequence() }.firstOrNull { it.code == code }

private fun AccountSettingsUiState.findField(modelCode: String?, fieldCode: String?): FieldEditorUiModel? {
    if (modelCode == null || fieldCode == null) return null
    return findModel(modelCode)?.fields?.firstOrNull { it.key.fieldCode == fieldCode }
}

private fun selectionRequestFor(field: FieldEditorUiModel): SelectionRequest? = when (field.type) {
    "SELECT_ONE" -> SelectionRequest(field, single = true, withCount = false)
    "SELECT" -> SelectionRequest(field, single = false, withCount = false)
    "SELECT_AND_COUNT" -> SelectionRequest(field, single = false, withCount = true)
    "SELECT_AND_COUNT_ONE" -> SelectionRequest(field, single = true, withCount = true)
    else -> null
}

private fun parseSelectionValues(value: String, request: SelectionRequest): Map<String, Int> {
    if (!request.withCount) {
        if (request.single) {
            val selectedId = decodeJsonString(value)
            return selectedId.takeIf(String::isNotBlank)?.let { mapOf(it to 1) }.orEmpty()
        }
        val node = JsonUtil.toNode(value)
        return if (node?.isArray == true) node.associate { it.asText() to 1 } else emptyMap()
    }
    val node = JsonUtil.toNode(value)
    if (node?.isObject != true) return emptyMap()
    return if (request.single) {
        val key = node.get("key")?.asText().orEmpty()
        if (key.isBlank()) emptyMap() else mapOf(key to (node.get("value")?.asInt() ?: 1))
    } else {
        node.properties().associate { it.key to it.value.asInt() }
    }
}

private fun serializeSelectionValues(values: Map<String, Int>, request: SelectionRequest): String = when {
    request.single && request.withCount -> values.entries.firstOrNull()?.let { JsonUtil.formatJson(KVMap(it.key, it.value), false) }.orEmpty()
    request.single -> values.keys.firstOrNull()?.let { JsonUtil.formatJson(it, false) }.orEmpty()
    request.withCount -> JsonUtil.formatJson(LinkedHashMap(values), false)
    else -> JsonUtil.formatJson(values.keys.toList(), false)
}

private fun parseFriendDraft(value: String, countEnabled: Boolean): FriendSelectionCountSpec = runCatching {
    if (countEnabled) {
        JsonUtil.parseObject(value, FriendSelectionCountSpec::class.java)
    } else {
        FriendSelectionCountSpec(selection = JsonUtil.parseObject(value, FriendSelectionSpec::class.java))
    }
}.getOrElse { FriendSelectionCountSpec() }

private fun selectionSummary(value: String, options: List<FieldOptionUiModel>, single: Boolean): String {
    if (single) {
        val selectedId = decodeJsonString(value)
        return options.firstOrNull { it.id == selectedId }?.name ?: selectedId.ifBlank { "未选择" }
    }
    val count = JsonUtil.toNode(value)?.takeIf { it.isArray }?.size() ?: 0
    return if (count == 0) "未选择" else "已选择 $count 项"
}

private fun mapSummary(value: String): String {
    val count = JsonUtil.toNode(value)?.takeIf { it.isObject }?.size() ?: 0
    return if (count == 0) "未选择" else "已选择 $count 项并设置次数"
}

private fun singleCountSummary(value: String): String {
    val node = JsonUtil.toNode(value)?.takeIf { it.isObject } ?: return "未选择"
    val key = node.get("key")?.asText().orEmpty()
    return if (key.isBlank()) "未选择" else "$key · ${node.get("value")?.asInt() ?: 0} 次"
}

private fun friendSummary(value: String, type: String): String {
    val spec = parseFriendDraft(value, type == "FRIEND_SELECTION_COUNT")
    val selection = spec.selection
    val included = selection.includeUserIds.size + selection.includeGroupIds.size
    val excluded = selection.excludeUserIds.size + selection.excludeGroupIds.size
    val scope = if (selection.selectionScope == FriendSelectionScope.ALL_FRIENDS) "全部好友" else "显式 $included 项"
    return if (excluded > 0) "$scope，排除 $excluded 项" else scope
}

private fun capabilityText(selection: FriendSelectionSpec): String? {
    val filter = selection.capabilityFilter ?: return null
    val modules = filter.moduleKeys.joinToString("、")
    val states = filter.requiredStates.joinToString("、") { capabilityLabel(it) }
    return "任务能力约束：${modules.ifBlank { "未指定模块" }}；允许状态：$states；未知状态${if (filter.includeUnknown) "保留" else "排除"}。该约束由业务字段维护。"
}

private fun relationLabel(value: FriendRelationFilter): String = when (value) {
    FriendRelationFilter.MUTUAL_ONLY -> "仅双向好友"
    FriendRelationFilter.ALL_KNOWN -> "全部已知好友"
    FriendRelationFilter.INCLUDE_SELF -> "包含自己"
}

private fun relationLabel(value: FriendRelation): String = when (value) {
    FriendRelation.SELF -> "自己"
    FriendRelation.MUTUAL -> "双向好友"
    FriendRelation.ONE_WAY -> "单向关系"
    FriendRelation.REMOVED -> "已失效"
    FriendRelation.UNKNOWN -> "关系未知"
}

private fun capabilityLabel(value: FriendCapabilityState): String = when (value) {
    FriendCapabilityState.UNKNOWN -> "未知"
    FriendCapabilityState.OPEN -> "已开通"
    FriendCapabilityState.NOT_OPEN -> "未开通"
    FriendCapabilityState.UNAVAILABLE -> "不可用"
}

private fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun decodeJsonString(value: String): String {
    val node = JsonUtil.toNode(value)
    return if (node?.isTextual == true) node.asText() else value
}

private fun listDisplayValue(value: String): String =
    JsonUtil.toNode(value)
        ?.takeIf { it.isArray }
        ?.map { it.asText() }
        ?.joinToString("\n")
        ?: value

private fun serializeListValue(value: String): String = JsonUtil.formatJson(
    value.lines().map(String::trim).filter(String::isNotEmpty),
    false,
)

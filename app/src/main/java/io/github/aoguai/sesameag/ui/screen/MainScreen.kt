package io.github.aoguai.sesameag.ui.screen

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.aoguai.sesameag.SesameApplication.Companion.PREFERENCES_KEY
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.hook.AccountSlotSnapshot
import io.github.aoguai.sesameag.task.customTasks.CustomTask
import io.github.aoguai.sesameag.ui.MainActivity
import io.github.aoguai.sesameag.ui.compose.CommonAlertDialog
import io.github.aoguai.sesameag.ui.navigation.AppRoute
import io.github.aoguai.sesameag.ui.navigation.LogSource
import io.github.aoguai.sesameag.ui.navigation.TopLevelDestination
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.ui.screen.content.AutomationContent
import io.github.aoguai.sesameag.ui.screen.content.HomeContent
import io.github.aoguai.sesameag.ui.screen.content.LogsContent
import io.github.aoguai.sesameag.ui.screen.content.MoreContent
import io.github.aoguai.sesameag.ui.screen.components.DelayedLoadingIndicator
import io.github.aoguai.sesameag.ui.theme.ThemeManager
import io.github.aoguai.sesameag.ui.viewmodel.MainViewModel
import io.github.aoguai.sesameag.util.CommandUtil.serviceStatus
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.LogChannel
import kotlinx.serialization.serializer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    oneWord: String,
    activeUserName: String,
    hasActiveUser: Boolean,
    moduleStatus: MainViewModel.ModuleStatus,
    permissionHealth: PermissionHealthSnapshot,
    viewModel: MainViewModel,
    isDynamicColor: Boolean,
    userList: List<UserEntity>,
    accountSlots: AccountSlotSnapshot,
    onPrepareManualTasks: suspend () -> Unit,
    onRunManualTask: (CustomTask, Map<String, Any>) -> LogSource?,
    clearModuleDataFailurePaths: List<String>,
    onDismissClearModuleDataFailure: () -> Unit,
    onEvent: (MainActivity.MainUiEvent) -> Unit,
    onExitRequested: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
    }
    var isIconHidden by rememberSaveable {
        mutableStateOf(prefs.getBoolean("is_icon_hidden", false))
    }
    var selectedName by rememberSaveable {
        mutableStateOf(TopLevelDestination.OVERVIEW.name)
    }
    var showClearAllLogsDialog by rememberSaveable { mutableStateOf(false) }

    val overviewStack = rememberAppRouteBackStack(AppRoute.Overview)
    val automationStack = rememberAppRouteBackStack(AppRoute.Automation)
    val logsStack = rememberAppRouteBackStack(AppRoute.Logs)
    val moreStack = rememberAppRouteBackStack(AppRoute.More)
    val stacks = remember(overviewStack, automationStack, logsStack, moreStack) {
        mapOf(
            TopLevelDestination.OVERVIEW to overviewStack,
            TopLevelDestination.AUTOMATION to automationStack,
            TopLevelDestination.LOGS to logsStack,
            TopLevelDestination.MORE to moreStack,
        )
    }
    val overviewEntryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<AppRoute>(),
        rememberViewModelStoreNavEntryDecorator<AppRoute>(),
    )
    val automationEntryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<AppRoute>(),
        rememberViewModelStoreNavEntryDecorator<AppRoute>(),
    )
    val logsEntryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<AppRoute>(),
        rememberViewModelStoreNavEntryDecorator<AppRoute>(),
    )
    val moreEntryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<AppRoute>(),
        rememberViewModelStoreNavEntryDecorator<AppRoute>(),
    )
    val selected = TopLevelDestination.entries.firstOrNull { it.name == selectedName }
        ?: TopLevelDestination.OVERVIEW
    val currentStack = stacks.getValue(selected)
    val isTopLevelRoute = currentStack.size == 1

    fun navigate(route: AppRoute) {
        if (currentStack.lastOrNull() != route) currentStack.add(route)
    }

    fun openForUser(user: UserEntity, destination: (String, String) -> AppRoute) {
        val userId = user.userId?.trim().orEmpty()
        if (userId.isEmpty()) return
        val userName = user.showName.ifBlank { user.account ?: userId }
        navigate(destination(userId, userName))
    }

    val serviceState by serviceStatus.collectAsStateWithLifecycle()
    val isOneWordLoading by viewModel.isOneWordLoading.collectAsStateWithLifecycle()
    val isLegalAccepted by viewModel.isLegalAccepted.collectAsStateWithLifecycle()

    val interceptedEvent: (MainActivity.MainUiEvent) -> Unit = { event ->
        when (event) {
            is MainActivity.MainUiEvent.OpenLog -> navigate(
                AppRoute.LogDetails(LogSource.Channel(event.channel.name))
            )
            MainActivity.MainUiEvent.OpenExtend -> navigate(AppRoute.ExtendTools)
            else -> onEvent(event)
        }
    }

    val title = if (isTopLevelRoute) {
        when (selected) {
            TopLevelDestination.OVERVIEW -> if (hasActiveUser) activeUserName else "未载入账号"
            TopLevelDestination.AUTOMATION -> "自动化"
            TopLevelDestination.LOGS -> "日志中心"
            TopLevelDestination.MORE -> "更多"
        }
    } else {
        null
    }

    val appEntryProvider = entryProvider {
        entry<AppRoute.Overview> {
            HomeContent(
                hasActiveUser = hasActiveUser,
                moduleStatus = moduleStatus,
                serviceStatus = serviceState,
                permissionHealth = permissionHealth,
                oneWord = oneWord,
                isOneWordLoading = isOneWordLoading,
                isLegalAccepted = isLegalAccepted,
                onLegalAcceptedChange = viewModel::setLegalAccepted,
                onOneWordClick = { interceptedEvent(MainActivity.MainUiEvent.RefreshOneWord) },
                onEvent = interceptedEvent,
            )
        }
        entry<AppRoute.Automation> {
            AutomationContent(
                userList = userList,
                accountSlots = accountSlots,
                onOpenSettings = { user ->
                    openForUser(user) { id, name -> AppRoute.AccountSettings(id, name) }
                },
                onOpenFriendCenter = { user ->
                    openForUser(user) { id, name -> AppRoute.FriendCenter(id, name) }
                },
                onSetExecutableSlot = viewModel::setExecutableAccountSlot,
                onClearAllTodayFlags = viewModel::clearAllTodayFlags,
            )
        }
        entry<AppRoute.Logs> {
            LogsContent(onEvent = interceptedEvent)
        }
        entry<AppRoute.More> {
            MoreContent(
                userList = userList,
                isDynamicColor = isDynamicColor,
                isIconHidden = isIconHidden,
                onToggleDynamicColor = ThemeManager::setDynamicColor,
                onToggleIconHidden = { hidden ->
                    isIconHidden = hidden
                    interceptedEvent(MainActivity.MainUiEvent.ToggleIconHidden(hidden))
                },
                onOpenFriendCenter = { user ->
                    openForUser(user) { id, name -> AppRoute.FriendCenter(id, name) }
                },
                onOpenOnceDaily = { user ->
                    openForUser(user) { id, name -> AppRoute.OnceDailySettings(id, name) }
                },
                onOpenManualTasks = { navigate(AppRoute.ManualTasks) },
                onOpenRpcDebug = { navigate(AppRoute.RpcDebug) },
                onOpenExtendTools = { navigate(AppRoute.ExtendTools) },
                onClearModuleData = {
                    interceptedEvent(MainActivity.MainUiEvent.ClearConfig)
                },
                clearModuleDataFailurePaths = clearModuleDataFailurePaths,
                onDismissClearModuleDataFailure = onDismissClearModuleDataFailure,
            )
        }
        entry<AppRoute.FriendCenter> { route ->
            FriendCenterScreen(
                userId = route.userId,
                userName = route.userName,
                onBack = { currentStack.removeLastOrNull() },
            )
        }
        entry<AppRoute.ManualTasks> {
            var isManualTaskConfigReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                runCatching { onPrepareManualTasks() }
                isManualTaskConfigReady = true
            }
            if (isManualTaskConfigReady) {
                ManualTaskScreen(
                    onBackClick = { currentStack.removeLastOrNull() },
                    onTaskClick = { task, params ->
                        onRunManualTask(task, params)?.let { source ->
                            navigate(AppRoute.LogDetails(source))
                        }
                    },
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DelayedLoadingIndicator()
                }
            }
        }
        entry<AppRoute.RpcDebug> {
            RpcDebugScreen(
                onBack = { currentStack.removeLastOrNull() },
                onOpenLog = { path ->
                    navigate(AppRoute.LogDetails(LogSource.FilePath(path)))
                },
            )
        }
        entry<AppRoute.ExtendTools> {
            ExtendScreen(
                onBackClick = { currentStack.removeLastOrNull() },
                onOpenLog = { path ->
                    navigate(AppRoute.LogDetails(LogSource.FilePath(path)))
                },
            )
        }
        entry<AppRoute.LogDetails> { route ->
            LogViewerScreen(
                filePath = route.source.resolvePath(),
                onBackClick = { currentStack.removeLastOrNull() },
            )
        }
        entry<AppRoute.AccountSettings> { route ->
            AccountSettingsScreen(
                userId = route.userId,
                userName = route.userName,
                onBack = { currentStack.removeLastOrNull() },
                onOpenFriendCenter = {
                    navigate(AppRoute.FriendCenter(route.userId, route.userName))
                },
                onDeleted = {
                    viewModel.refreshUserConfigs()
                    currentStack.removeLastOrNull()
                },
            )
        }
        entry<AppRoute.OnceDailySettings> { route ->
            OnceDailySettingsScreen(
                userId = route.userId,
                userName = route.userName,
                onBack = { currentStack.removeLastOrNull() },
            )
        }
    }
    val overviewEntries = rememberDecoratedNavEntries(
        backStack = overviewStack,
        entryDecorators = overviewEntryDecorators,
        entryProvider = appEntryProvider,
    )
    val automationEntries = rememberDecoratedNavEntries(
        backStack = automationStack,
        entryDecorators = automationEntryDecorators,
        entryProvider = appEntryProvider,
    )
    val logsEntries = rememberDecoratedNavEntries(
        backStack = logsStack,
        entryDecorators = logsEntryDecorators,
        entryProvider = appEntryProvider,
    )
    val moreEntries = rememberDecoratedNavEntries(
        backStack = moreStack,
        entryDecorators = moreEntryDecorators,
        entryProvider = appEntryProvider,
    )
    val selectedEntries = when (selected) {
        TopLevelDestination.OVERVIEW -> overviewEntries
        TopLevelDestination.AUTOMATION -> automationEntries
        TopLevelDestination.LOGS -> logsEntries
        TopLevelDestination.MORE -> moreEntries
    }

    BackHandler(enabled = isTopLevelRoute) {
        if (selected == TopLevelDestination.OVERVIEW) {
            onExitRequested()
        } else {
            selectedName = TopLevelDestination.OVERVIEW.name
        }
    }

    AdaptiveAppShell(
        selected = selected,
        title = title,
        topBarActions = {
            if (selected == TopLevelDestination.LOGS && isTopLevelRoute) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "更多日志操作",
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "清空所有日志",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteForever,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                expanded = false
                                showClearAllLogsDialog = true
                            },
                        )
                    }
                }
            }
        },
        onSelected = { destination ->
            if (destination == selected) {
                stacks.getValue(destination).popToRoot()
            } else {
                selectedName = destination.name
            }
        },
    ) { modifier ->
        key(selected) {
            NavDisplay(
                entries = selectedEntries,
                modifier = modifier.fillMaxSize(),
                onBack = { currentStack.removeLastOrNull() },
            )
        }
    }

    CommonAlertDialog(
        showDialog = showClearAllLogsDialog,
        onDismissRequest = { showClearAllLogsDialog = false },
        onConfirm = { viewModel.clearAllLogs(context) },
        title = "清空所有日志",
        text = "确认清空所有日志文件？此操作无法撤销。",
        icon = Icons.Outlined.DeleteForever,
        iconTint = MaterialTheme.colorScheme.error,
        confirmText = "确认清空",
        confirmButtonColor = MaterialTheme.colorScheme.error,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
private fun AdaptiveAppShell(
    selected: TopLevelDestination,
    title: String?,
    topBarActions: @Composable RowScope.() -> Unit = {},
    onSelected: (TopLevelDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                item(
                    selected = selected == destination,
                    onClick = { onSelected(destination) },
                    icon = {
                        Icon(
                            imageVector = destination.icon(),
                            contentDescription = destination.label,
                        )
                    },
                    label = { Text(destination.label) },
                    alwaysShowLabel = true,
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (title != null) {
                    CenterAlignedTopAppBar(
                        title = { Text(title) },
                        actions = topBarActions,
                    )
                }
            },
        ) { innerPadding ->
            content(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            )
        }
    }
}

private fun TopLevelDestination.icon() = when (this) {
    TopLevelDestination.OVERVIEW -> Icons.Outlined.Dashboard
    TopLevelDestination.AUTOMATION -> Icons.Outlined.AutoMode
    TopLevelDestination.LOGS -> Icons.Outlined.Description
    TopLevelDestination.MORE -> Icons.Outlined.MoreHoriz
}

@Composable
private fun rememberAppRouteBackStack(vararg elements: AppRoute): NavBackStack<AppRoute> {
    return rememberSerializable(serializer = serializer()) {
        NavBackStack(*elements)
    }
}

private fun NavBackStack<AppRoute>.popToRoot() {
    while (size > 1) removeLastOrNull()
}

private fun LogSource.resolvePath(): String = when (this) {
    is LogSource.Channel -> runCatching {
        Files.getLogFile(LogChannel.valueOf(name)).absolutePath
    }.getOrDefault("")
    is LogSource.FilePath -> path
}

package io.github.aoguai.sesameag.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aoguai.sesameag.SesameApplication.Companion.PREFERENCES_KEY
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.ui.MainActivity
import io.github.aoguai.sesameag.ui.compose.CommonAlertDialog
import io.github.aoguai.sesameag.ui.navigation.BottomNavItem
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.ui.screen.content.HomeContent
import io.github.aoguai.sesameag.ui.screen.content.LogsContent
import io.github.aoguai.sesameag.ui.screen.content.SettingsContent
import io.github.aoguai.sesameag.ui.theme.ThemeManager
import io.github.aoguai.sesameag.ui.viewmodel.MainViewModel
import io.github.aoguai.sesameag.util.CommandUtil.serviceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    oneWord: String,
    activeUserName: String,
    moduleStatus: MainViewModel.ModuleStatus,
    permissionHealth: PermissionHealthSnapshot,
    viewModel: MainViewModel,
    isDynamicColor: Boolean, // 传给 MainScreen
    userList: List<UserEntity>, // 🔥 确保 userList 被传入 MainScreen
    onNavigateToSettings: (UserEntity) -> Unit, // 🔥 新增回调：跳转设置
    onEvent: (MainActivity.MainUiEvent) -> Unit,
) {
    val context = LocalContext.current

    var currentScreen by remember { mutableStateOf<BottomNavItem>(BottomNavItem.Home) } // 默认显示主页


    val serviceStatus by serviceStatus.collectAsStateWithLifecycle()

    val isOneWordLoading by viewModel.isOneWordLoading.collectAsStateWithLifecycle()
    val isLegalAccepted by viewModel.isLegalAccepted.collectAsStateWithLifecycle()
    val prefs = context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
    var isIconHidden by remember { mutableStateOf(prefs.getBoolean("is_icon_hidden", false)) }
    var showMenu by remember { mutableStateOf(false) }
    var showClearAllLogsDialog by remember { mutableStateOf(false) }
//    var showUserDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = when (currentScreen) {
                            BottomNavItem.Home -> activeUserName
                            BottomNavItem.Logs -> "日志中心"
                            BottomNavItem.Settings -> "模块设置"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {

                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }

                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {

                        if (currentScreen == BottomNavItem.Logs) {
                            DropdownMenuItem(
                                text = { Text("清空所有日志", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showClearAllLogsDialog = true
                                }
                            )
                            HorizontalDivider()
                        }

                        DropdownMenuItem(
                            text = { Text(if (isIconHidden) "显示应用图标" else "隐藏应用图标") },
                            onClick = {
                                isIconHidden = !isIconHidden
                                onEvent(MainActivity.MainUiEvent.ToggleIconHidden(isIconHidden))
                                showMenu = false
                            }
                        )
                    }

                }
            )
        },
        bottomBar = {
            NavigationBar {
                val items = listOf(BottomNavItem.Logs, BottomNavItem.Home, BottomNavItem.Settings)
                items.forEach { item ->
                    val selected = currentScreen == item
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentScreen = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        // 🔥 关键：只有选中时才显示文字
                        alwaysShowLabel = false
                    )
                }
            }
        }
    )
    { innerPadding ->
        // 使用 Crossfade 做简单的切换动画 (可选)
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when (currentScreen) {
                BottomNavItem.Home -> HomeContent(
                    moduleStatus = moduleStatus,
                    serviceStatus = serviceStatus,
                    permissionHealth = permissionHealth,
                    oneWord = oneWord,
                    isOneWordLoading = isOneWordLoading,
                    isLegalAccepted = isLegalAccepted,
                    onLegalAcceptedChange = viewModel::setLegalAccepted,
                    onOneWordClick = { onEvent(MainActivity.MainUiEvent.RefreshOneWord) },
                    onEvent = onEvent
                )

                BottomNavItem.Logs -> LogsContent(
                    onEvent = onEvent
                )

                BottomNavItem.Settings -> SettingsContent(
                    userList = userList,
                    isDynamicColor = isDynamicColor, // 传给 MainScreen
                    onToggleDynamicColor = ThemeManager::setDynamicColor, // 传入回调
                    onNavigateToSettings = onNavigateToSettings,
                    onEvent = onEvent
                )
            }
        }
    }

    CommonAlertDialog(
        showDialog = showClearAllLogsDialog,
        onDismissRequest = { showClearAllLogsDialog = false },
        onConfirm = { viewModel.clearAllLogs(context) },
        title = "⚠️ 警告",
        text = "🤔 确认清空所有日志文件？此操作无法撤销。",
        confirmText = "确认清空",
        confirmButtonColor = MaterialTheme.colorScheme.error
    )
}


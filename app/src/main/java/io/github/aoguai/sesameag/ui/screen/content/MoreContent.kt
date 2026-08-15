package io.github.aoguai.sesameag.ui.screen.content

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.BuildConfig
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.ui.compose.CommonAlertDialog
import io.github.aoguai.sesameag.ui.screen.components.SettingsItem
import io.github.aoguai.sesameag.ui.screen.components.SettingsSwitchItem
import io.github.aoguai.sesameag.ui.screen.components.UserSelectionDialog

private enum class AccountTarget { FRIENDS, ONCE_DAILY }

@Composable
fun MoreContent(
    userList: List<UserEntity>,
    isDynamicColor: Boolean,
    isIconHidden: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleIconHidden: (Boolean) -> Unit,
    onOpenFriendCenter: (UserEntity) -> Unit,
    onOpenOnceDaily: (UserEntity) -> Unit,
    onOpenManualTasks: () -> Unit,
    onOpenRpcDebug: () -> Unit,
    onOpenExtendTools: () -> Unit,
    onClearModuleData: () -> Unit,
    clearModuleDataFailurePaths: List<String>,
    onDismissClearModuleDataFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var accountTarget by rememberSaveable { mutableStateOf<AccountTarget?>(null) }

    fun openForAccount(target: AccountTarget) {
        when (userList.size) {
            0 -> Toast.makeText(context, "请先载入账号", Toast.LENGTH_SHORT).show()
            1 -> when (target) {
                AccountTarget.FRIENDS -> onOpenFriendCenter(userList.first())
                AccountTarget.ONCE_DAILY -> onOpenOnceDaily(userList.first())
            }
            else -> accountTarget = target
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionTitle("外观") }
        item {
            SettingsSwitchItem(
                title = "动态取色",
                subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "使用系统壁纸配色"
                } else {
                    "当前系统使用品牌配色"
                },
                icon = Icons.Outlined.ColorLens,
                checked = isDynamicColor,
                onCheckedChange = onToggleDynamicColor,
            )
        }
        item {
            SettingsSwitchItem(
                title = "隐藏桌面图标",
                subtitle = "LSPosed 管理器中的模块设置入口不受影响",
                icon = Icons.Outlined.VisibilityOff,
                checked = isIconHidden,
                onCheckedChange = onToggleIconHidden,
            )
        }

        item { Spacer(Modifier.height(8.dp)); SectionTitle("工具") }
        item {
            SettingsItem(
                title = "好友中心",
                subtitle = "查看好友、分组、关系与全局黑名单",
                icon = Icons.Outlined.Groups,
                onClick = { openForAccount(AccountTarget.FRIENDS) },
            )
        }
        item {
            SettingsItem(
                title = "手动任务",
                subtitle = "按当前可执行账号发送既有任务广播",
                icon = Icons.Outlined.RocketLaunch,
                onClick = onOpenManualTasks,
            )
        }
        item {
            SettingsItem(
                title = "RPC 调试",
                subtitle = "编辑 rpcRequest.json 并查看执行结果",
                icon = Icons.Outlined.BugReport,
                onClick = onOpenRpcDebug,
            )
        }
        item {
            SettingsItem(
                title = "扩展工具",
                subtitle = "查询、缓存、统计与 Shizuku 工具",
                icon = Icons.Outlined.Extension,
                onClick = onOpenExtendTools,
            )
        }
        item {
            SettingsItem(
                title = "每日单次运行设置",
                subtitle = "管理已有的单次运行跳过列表",
                icon = Icons.Outlined.Schedule,
                onClick = { openForAccount(AccountTarget.ONCE_DAILY) },
            )
        }

        item { Spacer(Modifier.height(8.dp)); SectionTitle("应用") }
        item {
            SettingsItem(
                title = "清除模块数据",
                subtitle = "清除模块配置、状态、日志和 RPC 调试数据",
                icon = Icons.Outlined.DeleteForever,
                isDanger = true,
                onClick = { showClearDialog = true },
            )
        }
        item {
            SettingsItem(
                title = "版本 ${BuildConfig.VERSION_NAME}",
                subtitle = "构建 ${BuildConfig.BUILD_DATE} ${BuildConfig.BUILD_TIME}",
                icon = Icons.Outlined.Info,
            )
        }

        item { Spacer(Modifier.height(8.dp)); SectionTitle("支持") }
        item {
            SettingsItem(
                title = "GitHub",
                subtitle = General.PROJECT_HOMEPAGE_URL,
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                onClick = { uriHandler.openUri(General.PROJECT_HOMEPAGE_URL) },
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showClearDialog) {
        CommonAlertDialog(
            showDialog = true,
            onDismissRequest = { showClearDialog = false },
            onConfirm = {
                showClearDialog = false
                onClearModuleData()
            },
            title = "清除全部模块数据",
            text = "将清除账号配置、状态、JSON、RPC 调试数据、日志和抓包。导出的备份和系统授权不会清除。此操作无法撤销。",
            icon = Icons.Outlined.DeleteForever,
            iconTint = MaterialTheme.colorScheme.error,
            confirmText = "清除",
            confirmButtonColor = MaterialTheme.colorScheme.error,
        )
    }

    if (accountTarget != null) {
        UserSelectionDialog(
            userList = userList,
            onDismissRequest = { accountTarget = null },
            onUserSelected = { user ->
                when (accountTarget) {
                    AccountTarget.FRIENDS -> onOpenFriendCenter(user)
                    AccountTarget.ONCE_DAILY -> onOpenOnceDaily(user)
                    null -> Unit
                }
                accountTarget = null
            },
        )
    }

    if (clearModuleDataFailurePaths.isNotEmpty()) {
        CommonAlertDialog(
            showDialog = true,
            onDismissRequest = onDismissClearModuleDataFailure,
            onConfirm = onDismissClearModuleDataFailure,
            title = "部分模块数据清除失败",
            text = clearModuleDataFailurePaths.mapIndexed { index, path ->
                "${index + 1}. $path"
            }.joinToString("\n"),
            confirmText = "知道了",
            showCancelButton = false,
        )
    }
}

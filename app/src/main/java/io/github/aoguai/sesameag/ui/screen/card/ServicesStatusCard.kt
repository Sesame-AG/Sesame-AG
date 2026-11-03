package io.github.aoguai.sesameag.ui.screen.card

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.hook.keepalive.PersistentLaunchPolicy
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthItem
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.ui.permissions.PermissionRequirement
import io.github.aoguai.sesameag.ui.permissions.PermissionStatus
import io.github.aoguai.sesameag.ui.screen.components.DelayedLoadingIndicator
import io.github.aoguai.sesameag.util.CommandUtil.ServiceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServicesStatusCard(
    status: ServiceStatus,
    permissionHealth: PermissionHealthSnapshot,
    ownerUserId: String?,
    expanded: Boolean,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    onRequest: (PermissionRequirement) -> Unit,
) {
    val requiredItems = permissionHealth.items.filter { it.isRequired }
    val busy = permissionHealth.items.any { it.status == PermissionStatus.REQUESTING }
    val fileGranted = permissionHealth.item(PermissionRequirement.MODULE_FILE)?.isGranted == true
    var persistentForegroundLaunchEnabled by remember(ownerUserId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(ownerUserId, expanded, fileGranted) {
        persistentForegroundLaunchEnabled = if (expanded && fileGranted && ownerUserId != null) {
            withContext(Dispatchers.IO) { PersistentLaunchPolicy.isForegroundLaunchEnabled(ownerUserId) }
        } else {
            null
        }
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("权限设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(
                        text = when {
                            requiredItems.isEmpty() -> "正在检查"
                            requiredItems.all { it.isGranted } -> "必需权限已就绪"
                            requiredItems.any { it.status == PermissionStatus.REQUESTING } -> "正在处理必需权限"
                            else -> "必需项目待处理"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !busy) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重新检查权限")
                }
            }
            requiredItems.forEach { item ->
                PermissionHealthRow(item, status, !busy, onRequest)
            }
            TextButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp)
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            ) {
                Text("可选增强", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
            ) {
                Column {
                    permissionHealth.items.filterNot { it.isRequired }.forEach { item ->
                        PermissionHealthRow(item, status, !busy, onRequest)
                    }
                    if (persistentForegroundLaunchEnabled == false) {
                        Text(
                            "打开目标应用后继续任务",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionHealthRow(
    item: PermissionHealthItem,
    serviceStatus: ServiceStatus,
    actionEnabled: Boolean,
    onRequest: (PermissionRequirement) -> Unit,
) {
    val requesting = item.status == PermissionStatus.REQUESTING
    val canOpenSettings = item.actionLabel != null && when (item.requirement) {
        PermissionRequirement.MODULE_NOTIFICATION,
        PermissionRequirement.MODULE_BATTERY,
        PermissionRequirement.TARGET_BATTERY -> true
        PermissionRequirement.MODULE_EXACT_ALARM,
        PermissionRequirement.TARGET_EXACT_ALARM -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        else -> false
    }
    val blocking = item.isRequired && !item.isGranted && !requesting
    val statusColor = when {
        blocking -> MaterialTheme.colorScheme.error
        item.isGranted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when {
        requesting -> "处理中"
        item.requirement == PermissionRequirement.SHELL_EXECUTOR -> {
            if (item.isGranted && serviceStatus is ServiceStatus.Active) "${serviceStatus.type} 已连接" else "未连接"
        }
        item.requirement == PermissionRequirement.MODULE_FILE -> if (item.isGranted) "已就绪" else "待迁移"
        item.isGranted -> if (item.requirement == PermissionRequirement.LSPOSED_TARGET_SCOPE) "已添加" else "已授权"
        item.status == PermissionStatus.UNAVAILABLE -> "待确认"
        item.status == PermissionStatus.UNSUPPORTED -> "当前不可用"
        item.isRequired -> "待授权"
        else -> "未开启"
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = {
            if (requesting) {
                DelayedLoadingIndicator(modifier = Modifier.size(24.dp).semantics { stateDescription = "处理中" })
            } else {
                Icon(
                    imageVector = when {
                        item.isGranted -> Icons.Outlined.CheckCircle
                        blocking -> Icons.Outlined.Warning
                        else -> Icons.Outlined.Info
                    },
                    contentDescription = null,
                    tint = statusColor,
                )
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(statusText, color = statusColor, style = MaterialTheme.typography.labelLarge)
                Text(item.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (canOpenSettings || item.canRequest ||
                    (item.requirement == PermissionRequirement.MODULE_FILE && item.actionLabel != null)) {
                    TextButton(
                        onClick = { onRequest(item.requirement) },
                        enabled = if (canOpenSettings) !requesting else actionEnabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (canOpenSettings) "打开设置" else item.actionLabel.orEmpty())
                    }
                } else if (requesting) {
                    Spacer(Modifier.height(48.dp))
                }
            }
        },
    ) {
        Text(item.title)
    }
}

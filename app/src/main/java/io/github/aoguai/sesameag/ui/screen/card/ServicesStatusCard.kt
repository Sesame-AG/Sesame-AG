package io.github.aoguai.sesameag.ui.screen.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthItem
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.ui.permissions.PermissionPolicy
import io.github.aoguai.sesameag.ui.permissions.PermissionStatus
import io.github.aoguai.sesameag.util.CommandUtil.ServiceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesStatusCard(
    status: ServiceStatus, // 使用新定义的状态
    permissionHealth: PermissionHealthSnapshot,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val hasPermissionIssue = permissionHealth.attentionCount > 0 || permissionHealth.hasCriticalIssue
    val shellReady = status is ServiceStatus.Active
    val loading = status is ServiceStatus.Loading || permissionHealth.totalCount == 0
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp), // 稍微调整间距
        colors = CardDefaults.elevatedCardColors(
            containerColor = when (status) {
                is ServiceStatus.Loading -> MaterialTheme.colorScheme.surfaceVariant
                else -> when {
                    hasPermissionIssue -> MaterialTheme.colorScheme.errorContainer
                    shellReady -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            }
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else if (hasPermissionIssue || !shellReady) {
                    Icon(Icons.Outlined.Warning, "权限待处理")
                } else {
                    Icon(Icons.Outlined.CheckCircle, "权限正常")
                }
                Column(Modifier.padding(start = 20.dp)) {
                    Text(
                        text = when {
                            loading -> "正在检查运行权限..."
                            hasPermissionIssue -> "运行与权限待处理"
                            shellReady -> "运行与权限正常"
                            else -> "运行权限待确认"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "权限 ${permissionHealth.grantedCount}/${permissionHealth.totalCount} · ${shellStatusText(status)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (permissionHealth.hasRequestableIssue) {
                            "可处理项 ${permissionHealth.requestableCount}"
                        } else {
                            "权限状态已同步"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // 展开内容：故障排查
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(300))
            ) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(text = "运行权限", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (permissionHealth.items.isEmpty()) {
                        Text(
                            text = "权限快照尚未生成，请稍后重试。",
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            permissionHealth.items.forEach { item ->
                                PermissionHealthRow(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionHealthRow(item: PermissionHealthItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.policy == PermissionPolicy.AUTO_CRITICAL) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = permissionStatusText(item.status),
                style = MaterialTheme.typography.labelMedium
            )
        }
        Text(
            text = item.description,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp
        )
    }
}

private fun shellStatusText(status: ServiceStatus): String {
    return when (status) {
        is ServiceStatus.Active -> if (status.type == "Root") {
            "Root Shell 已连接"
        } else {
            "Shizuku Shell 已连接"
        }

        is ServiceStatus.Inactive -> "Shell 服务不可用"
        is ServiceStatus.Loading -> "Shell 检查中"
        is ServiceStatus.Error -> "Shell 服务异常"
    }
}

private fun permissionStatusText(status: PermissionStatus): String {
    return when (status) {
        PermissionStatus.GRANTED -> "已授权"
        PermissionStatus.MISSING -> "缺失"
        PermissionStatus.REQUESTING -> "请求中"
        PermissionStatus.DENIED -> "已拒绝"
        PermissionStatus.UNAVAILABLE -> "不可用"
        PermissionStatus.UNSUPPORTED -> "不支持"
    }
}

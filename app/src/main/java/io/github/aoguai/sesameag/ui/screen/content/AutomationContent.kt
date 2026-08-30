package io.github.aoguai.sesameag.ui.screen.content

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.hook.AccountSlotSnapshot
import io.github.aoguai.sesameag.ui.compose.CommonAlertDialog

@Composable
fun AutomationContent(
    userList: List<UserEntity>,
    accountSlots: AccountSlotSnapshot,
    onOpenSettings: (UserEntity) -> Unit,
    onOpenFriendCenter: (UserEntity) -> Unit,
    onSetExecutableSlot: (String?, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingSlotRemoval by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle(
                title = "账号与可执行槽位",
                supportingText = "账号配置按当前同步结果展示，移出槽位不会删除账号数据。",
            )
        }

        if (userList.isEmpty()) {
            item {
                Text(
                    text = "还没有已载入的账号。请先打开目标应用，再返回模块同步当前账号。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(
                items = userList,
                key = { index, user ->
                    "account-${user.userId?.trim()?.takeIf(String::isNotEmpty) ?: "unknown"}-$index"
                },
            ) { _, user ->
                val userId = user.userId?.trim().orEmpty()
                AccountCard(
                    user = user,
                    isExecutable = userId in accountSlots.activeUserIds,
                    onOpenSettings = { onOpenSettings(user) },
                    onOpenFriendCenter = { onOpenFriendCenter(user) },
                    onSetExecutableSlot = { enabled ->
                        if (enabled) {
                            onSetExecutableSlot(userId, true)
                        } else {
                            pendingSlotRemoval = userId
                        }
                    },
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    pendingSlotRemoval?.let { userId ->
        CommonAlertDialog(
            showDialog = true,
            onDismissRequest = { pendingSlotRemoval = null },
            onConfirm = {
                onSetExecutableSlot(userId, false)
                pendingSlotRemoval = null
            },
            title = "移出可执行槽位",
            text = "该账号将无法执行自动任务，但账号配置和历史数据会保留。",
            icon = Icons.Outlined.DeleteOutline,
            iconTint = MaterialTheme.colorScheme.error,
            confirmText = "移出",
            confirmButtonColor = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun AccountCard(
    user: UserEntity,
    isExecutable: Boolean,
    onOpenSettings: () -> Unit,
    onOpenFriendCenter: () -> Unit,
    onSetExecutableSlot: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val userId = user.userId?.trim().orEmpty()
    val displayName = user.showName.ifBlank { user.account ?: userId.ifBlank { "未知账号" } }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(maskUserId(userId))
                        append(if (isExecutable) " · 可执行" else " · 仅保留配置")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isExecutable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "$displayName 配置")
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "$displayName 更多操作")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("配置") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenSettings()
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
                    text = { Text(if (isExecutable) "移出槽位" else "移入槽位") },
                    leadingIcon = {
                        Icon(
                            if (isExecutable) Icons.Outlined.DeleteOutline else Icons.Outlined.Add,
                            contentDescription = null,
                            tint = if (isExecutable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onSetExecutableSlot(!isExecutable)
                    },
                )
                DropdownMenuItem(
                    text = { Text("复制账号 ID") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    enabled = userId.isNotBlank(),
                    onClick = {
                        menuExpanded = false
                        copyAccountId(context, userId)
                    },
                )
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, supportingText: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun copyAccountId(context: Context, userId: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Alipay user ID", userId))
    Toast.makeText(context, "账号 ID 已复制", Toast.LENGTH_SHORT).show()
}

private fun maskUserId(userId: String): String = when {
    userId.isBlank() -> "账号 ID 未载入"
    userId.length <= 4 -> "****"
    else -> "***${userId.takeLast(4)}"
}

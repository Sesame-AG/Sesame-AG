package io.github.aoguai.sesameag.ui.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.aoguai.sesameag.entity.friend.FriendRelation
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.ui.viewmodel.FriendCenterFilter
import io.github.aoguai.sesameag.ui.viewmodel.FriendCenterViewModel
import io.github.aoguai.sesameag.ui.viewmodel.FriendGroupUiItem
import io.github.aoguai.sesameag.ui.viewmodel.FriendProfileUiItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendCenterScreen(
    userId: String,
    userName: String,
    onBack: () -> Unit,
    viewModel: FriendCenterViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var newGroupName by remember { mutableStateOf("") }
    var editingGroupId by remember { mutableStateOf<String?>(null) }
    var editingGroupName by remember { mutableStateOf("") }
    var showGroups by remember { mutableStateOf(false) }
    var showInactive by remember { mutableStateOf(false) }
    var showChanges by remember { mutableStateOf(false) }
    var showGroupMembers by remember { mutableStateOf(false) }
    var showAllProfiles by remember { mutableStateOf(true) }
    var batchMode by remember { mutableStateOf(false) }
    var batchSelectedUserIds by remember { mutableStateOf(setOf<String>()) }
    var groupPickerTargetUserIds by remember { mutableStateOf(setOf<String>()) }
    var groupPickerTitle by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(userId) {
        viewModel.load(userId)
        viewModel.requestRefreshAvailability(context)
    }

    DisposableEffect(lifecycleOwner, userId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.load(userId)
                viewModel.requestRefreshAvailability(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(context, userId) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ApplicationHookConstants.BroadcastActions.HOOK_READY_RESULT -> viewModel.handleRefreshAvailabilityResult(
                        resultUserId = intent.getStringExtra("userId").orEmpty(),
                        ready = intent.getBooleanExtra("ready", false),
                        message = intent.getStringExtra("message").orEmpty(),
                        currentUserId = intent.getStringExtra("currentUserId").orEmpty(),
                        timestamp = intent.getLongExtra("timestamp", 0L)
                    )

                    ApplicationHookConstants.BroadcastActions.REFRESH_FRIENDS_RESULT -> viewModel.handleRefreshResult(
                        resultUserId = intent.getStringExtra("userId").orEmpty(),
                        success = intent.getBooleanExtra("success", false),
                        message = intent.getStringExtra("message").orEmpty(),
                        profiles = intent.getIntExtra("profiles", 0),
                        groups = intent.getIntExtra("groups", 0),
                        timestamp = intent.getLongExtra("timestamp", 0L)
                    )
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ApplicationHookConstants.BroadcastActions.HOOK_READY_RESULT)
            addAction(ApplicationHookConstants.BroadcastActions.REFRESH_FRIENDS_RESULT)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    LaunchedEffect(state.profiles, batchMode) {
        if (!batchMode) {
            if (batchSelectedUserIds.isNotEmpty()) {
                batchSelectedUserIds = emptySet()
            }
            return@LaunchedEffect
        }
        val visibleIds = state.profiles.mapTo(linkedSetOf()) { it.userId }
        batchSelectedUserIds = batchSelectedUserIds.filterTo(linkedSetOf()) { visibleIds.contains(it) }
    }

    if (groupPickerTargetUserIds.isNotEmpty()) {
        GroupPickerDialog(
            title = groupPickerTitle,
            groups = state.groups,
            selectedUserIds = groupPickerTargetUserIds,
            onDismiss = {
                groupPickerTargetUserIds = emptySet()
                groupPickerTitle = ""
            },
            onToggle = { groupId, checked ->
                viewModel.setMemberBatch(groupId, groupPickerTargetUserIds, checked)
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (userName.isBlank()) "好友中心" else "好友中心 | $userName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (batchMode) {
                        TextButton(onClick = {
                            batchSelectedUserIds = state.profiles.mapTo(linkedSetOf()) { it.userId }
                        }) {
                            Text("全选")
                        }
                        TextButton(onClick = {
                            val visibleIds = state.profiles.mapTo(linkedSetOf()) { it.userId }
                            batchSelectedUserIds = visibleIds.filterTo(linkedSetOf()) { !batchSelectedUserIds.contains(it) }
                        }) {
                            Text("反选")
                        }
                        TextButton(onClick = {
                            batchMode = false
                            batchSelectedUserIds = emptySet()
                        }) {
                            Text("取消")
                        }
                    } else {
                        val refreshEnabled = state.refreshAvailable &&
                            !state.checkingRefreshAvailability &&
                            !state.refreshing
                        val refreshDescription = when {
                            state.refreshing -> "正在刷新好友"
                            state.checkingRefreshAvailability -> "正在检测目标应用"
                            !state.refreshAvailable -> "请先打开目标应用并回到模块，再刷新好友列表"
                            else -> "刷新好友"
                        }
                        IconButton(
                            onClick = { viewModel.requestRefreshFromAlipay(context) },
                            enabled = refreshEnabled
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = refreshDescription)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.scrollToItem(0) } }
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "滚动到顶部")
                }
                SmallFloatingActionButton(
                    onClick = {
                        val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                        scope.launch { listState.scrollToItem(lastIndex) }
                    }
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "滚动到底部")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
            item {
                Spacer(Modifier.height(4.dp))
                if (state.message.isNotBlank()) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                }
                StatsRow(
                    total = state.stats.total,
                    available = state.stats.available,
                    blocked = state.stats.blocked,
                    groups = state.stats.groups,
                    inactive = state.stats.inactive
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.searchQuery,
                    onValueChange = viewModel::updateSearch,
                    singleLine = true,
                    label = { Text("搜索好友、ID 或分组") }
                )
                Spacer(Modifier.height(8.dp))
                FilterRow(
                    selected = state.filter,
                    onSelected = viewModel::updateFilter
                )
            }

            item {
                SectionHeader(
                    title = "全部好友",
                    countText = "${state.profiles.size}/${state.stats.total}",
                    expanded = showAllProfiles,
                    onClick = { showAllProfiles = !showAllProfiles }
                )
            }

            if (showAllProfiles) {
                if (state.profiles.isEmpty()) {
                    item {
                        Text(
                            if (state.stats.total == 0 && state.searchQuery.isBlank() && state.filter == FriendCenterFilter.ALL) {
                                "还没有好友快照。请先打开目标应用并点右上角刷新，再回到这里查看。"
                            } else {
                                "无匹配好友。"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.profiles, key = { it.userId }) { profile ->
                        val checked = batchSelectedUserIds.contains(profile.userId)
                        FriendProfileRow(
                            profile = profile,
                            checked = checked,
                            showCheckbox = batchMode,
                            onCheckedChange = { isChecked ->
                                batchSelectedUserIds = if (isChecked) {
                                    batchSelectedUserIds + profile.userId
                                } else {
                                    batchSelectedUserIds - profile.userId
                                }
                            },
                            onBlockedChange = { blocked ->
                                if (batchMode && batchSelectedUserIds.isNotEmpty()) {
                                    viewModel.setGlobalBlockedBatch(batchSelectedUserIds, blocked)
                                } else {
                                    viewModel.setGlobalBlocked(profile.userId, blocked)
                                }
                            },
                            onGroupClick = {
                                if (batchMode && batchSelectedUserIds.isNotEmpty()) {
                                    groupPickerTargetUserIds = batchSelectedUserIds
                                    groupPickerTitle = "批量设置分组 | ${batchSelectedUserIds.size} 人"
                                } else {
                                    groupPickerTargetUserIds = setOf(profile.userId)
                                    groupPickerTitle = "设置分组 | ${profile.displayName}"
                                }
                            },
                            onLongPress = {
                                batchMode = true
                                batchSelectedUserIds = batchSelectedUserIds + profile.userId
                            },
                            onRowClickInBatch = {
                                if (batchMode) {
                                    batchSelectedUserIds = if (checked) {
                                        batchSelectedUserIds - profile.userId
                                    } else {
                                        batchSelectedUserIds + profile.userId
                                    }
                                }
                            }
                        )
                    }
                }
            }

            item {
                HorizontalDivider()
                SectionHeader(
                    title = "好友分组",
                    countText = "${state.groups.size}",
                    expanded = showGroups,
                    onClick = { showGroups = !showGroups }
                )
            }

            if (showGroups) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = newGroupName,
                            onValueChange = { newGroupName = it },
                            singleLine = true,
                            label = { Text("新分组名称") }
                        )
                        Button(onClick = {
                            viewModel.addGroup(newGroupName)
                            newGroupName = ""
                            showGroupMembers = true
                        }) {
                            Text("新增")
                        }
                    }
                }
                if (state.groups.isEmpty()) {
                    item {
                        Text("暂无分组。创建后可在好友行或分组编辑区添加成员。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(state.groups, key = { it.id }) { group ->
                        GroupRow(
                            group = group,
                            selected = group.id == state.selectedGroupId,
                            editing = group.id == editingGroupId,
                            editingName = editingGroupName,
                            onSelect = {
                                viewModel.selectGroup(group.id)
                                showGroupMembers = true
                            },
                            onEditName = {
                                editingGroupId = group.id
                                editingGroupName = group.name
                                viewModel.selectGroup(group.id)
                            },
                            onEditingNameChange = { editingGroupName = it },
                            onCancelEdit = {
                                editingGroupId = null
                                editingGroupName = ""
                            },
                            onSaveEdit = {
                                viewModel.renameGroup(group.id, editingGroupName)
                                editingGroupId = null
                                editingGroupName = ""
                            },
                            onDelete = {
                                if (editingGroupId == group.id) {
                                    editingGroupId = null
                                    editingGroupName = ""
                                }
                                viewModel.deleteGroup(group.id)
                            }
                        )
                    }
                    item {
                        SectionHeader(
                            title = state.selectedGroup?.let { "编辑成员 | ${it.name}" } ?: "编辑成员",
                            countText = state.selectedGroup?.let { "${it.memberCount}" }.orEmpty(),
                            expanded = showGroupMembers,
                            onClick = { showGroupMembers = !showGroupMembers }
                        )
                    }
                    if (showGroupMembers) {
                        if (state.selectedGroup == null) {
                            item {
                                Text("先选择一个分组。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            items(state.selectedGroupMembers, key = { "group-${it.userId}" }) { profile ->
                                FriendProfileRow(
                                    profile = profile,
                                    checked = state.selectedGroup?.memberIds?.contains(profile.userId) == true,
                                    onCheckedChange = { checked -> viewModel.toggleMember(profile.userId, checked) },
                                    onBlockedChange = { blocked -> viewModel.setGlobalBlocked(profile.userId, blocked) },
                                    onGroupClick = {
                                        groupPickerTargetUserIds = setOf(profile.userId)
                                        groupPickerTitle = "设置分组 | ${profile.displayName}"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                SectionHeader(
                    title = "单向/失效好友",
                    countText = "${state.inactiveProfiles.count { !it.globalBlocked }}",
                    expanded = showInactive,
                    onClick = { showInactive = !showInactive }
                )
            }

            if (showInactive) {
                val inactive = state.inactiveProfiles.filter { !it.globalBlocked }
                if (inactive.isEmpty()) {
                    item {
                        Text("暂无单向或失效好友。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(inactive, key = { "inactive-${it.userId}" }) { profile ->
                        FriendProfileRow(
                            profile = profile,
                            checked = false,
                            showCheckbox = false,
                            onCheckedChange = {},
                            onBlockedChange = { blocked -> viewModel.setGlobalBlocked(profile.userId, blocked) },
                            onGroupClick = {
                                groupPickerTargetUserIds = setOf(profile.userId)
                                groupPickerTitle = "设置分组 | ${profile.displayName}"
                            }
                        )
                    }
                }
            }

            item {
                HorizontalDivider()
                SectionHeader(
                    title = "最近变化/异常状态",
                    countText = "${state.inactiveProfiles.size}",
                    expanded = showChanges,
                    onClick = { showChanges = !showChanges }
                )
            }

            if (showChanges) {
                if (state.inactiveProfiles.isEmpty()) {
                    item {
                        Text("暂无异常状态。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(state.inactiveProfiles, key = { "change-${it.userId}" }) { profile ->
                        FriendProfileRow(
                            profile = profile,
                            checked = false,
                            showCheckbox = false,
                            onCheckedChange = {},
                            onBlockedChange = { blocked -> viewModel.setGlobalBlocked(profile.userId, blocked) },
                            onGroupClick = {
                                groupPickerTargetUserIds = setOf(profile.userId)
                                groupPickerTitle = "设置分组 | ${profile.displayName}"
                            }
                        )
                    }
                }
            }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StatsRow(
    total: Int,
    available: Int,
    blocked: Int,
    groups: Int,
    inactive: Int
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { StatCard("全部", total.toString()) }
        item { StatCard("互关可用", available.toString()) }
        item { StatCard("已拉黑", blocked.toString()) }
        item { StatCard("分组", groups.toString()) }
        item { StatCard("单向失效", inactive.toString()) }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    selected: FriendCenterFilter,
    onSelected: (FriendCenterFilter) -> Unit
) {
    val options = listOf(
        FriendCenterFilter.ALL to "全部",
        FriendCenterFilter.AVAILABLE to "互关可用",
        FriendCenterFilter.BLOCKED to "已拉黑",
        FriendCenterFilter.INACTIVE to "不可用"
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first.name }) { option ->
            FilterChip(
                selected = selected == option.first,
                onClick = { onSelected(option.first) },
                label = { Text(option.second) }
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    countText: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        if (countText.isNotBlank()) {
            Text(
                text = countText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = if (expanded) " 收起" else " 展开",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun GroupRow(
    group: FriendGroupUiItem,
    selected: Boolean,
    editing: Boolean,
    editingName: String,
    onSelect: () -> Unit,
    onEditName: () -> Unit,
    onEditingNameChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "成员 ${group.memberCount} 人 | 生效 ${group.effectiveCount} 人 | 未生效 ${group.inactiveCount} 人",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onEditName) {
                    Text("重命名")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除分组")
                }
            }
            if (editing) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = editingName,
                    onValueChange = onEditingNameChange,
                    singleLine = true,
                    label = { Text("分组名称") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancelEdit) {
                        Text("取消")
                    }
                    TextButton(onClick = onSaveEdit) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendProfileRow(
    profile: FriendProfileUiItem,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onBlockedChange: (Boolean) -> Unit,
    onGroupClick: () -> Unit,
    showCheckbox: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    onRowClickInBatch: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onRowClickInBatch?.invoke() },
                onLongClick = { onLongPress?.invoke() }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.effective) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        DisableSelection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showCheckbox) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = onCheckedChange
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.displayName.ifBlank { profile.userId }, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${profile.userId} | ${relationText(profile.relation)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val groupText = profile.groupNames.takeIf { it.isNotEmpty() }?.joinToString("、")
                    if (!groupText.isNullOrBlank()) {
                        Text(
                            "分组：$groupText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (profile.capabilitySummary.isNotBlank()) {
                        Text(
                            profile.capabilitySummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        if (profile.effective) "当前可生效" else "不生效：${profile.inactiveReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (profile.effective) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onBlockedChange(!profile.globalBlocked) }) {
                        Text(if (profile.globalBlocked) "移出全局黑名单" else "加入全局黑名单")
                    }
                    TextButton(onClick = onGroupClick) {
                        Text("分组")
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupPickerDialog(
    title: String,
    groups: List<FriendGroupUiItem>,
    selectedUserIds: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (groups.isEmpty()) {
                Text("暂无分组，请先在好友分组中创建。")
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    groups.forEach { group ->
                        val checked = selectedUserIds.isNotEmpty() && selectedUserIds.all { userId ->
                            group.memberIds.contains(userId)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggle(group.id, it) }
                            )
                            Column {
                                Text(group.name)
                                Text(
                                    "成员 ${group.memberCount} 人 | 生效 ${group.effectiveCount} 人",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}

private fun relationText(relation: FriendRelation): String {
    return when (relation) {
        FriendRelation.SELF -> "当前账号"
        FriendRelation.MUTUAL -> "互关好友"
        FriendRelation.ONE_WAY -> "单向好友"
        FriendRelation.REMOVED -> "已失效"
        FriendRelation.UNKNOWN -> "未知"
    }
}

package io.github.aoguai.sesameag.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.entity.friend.FriendCenterConfig
import io.github.aoguai.sesameag.entity.friend.FriendModuleCapability
import io.github.aoguai.sesameag.entity.friend.FriendGroup
import io.github.aoguai.sesameag.entity.friend.FriendProfile
import io.github.aoguai.sesameag.entity.friend.FriendRelation
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class FriendCenterFilter {
    ALL,
    AVAILABLE,
    BLOCKED,
    INACTIVE
}

data class FriendCenterStats(
    val total: Int = 0,
    val available: Int = 0,
    val blocked: Int = 0,
    val groups: Int = 0,
    val inactive: Int = 0
)

data class FriendGroupUiItem(
    val id: String = "",
    val name: String = "",
    val memberIds: Set<String> = emptySet(),
    val memberCount: Int = 0,
    val effectiveCount: Int = 0,
    val inactiveCount: Int = 0
)

data class FriendProfileUiItem(
    val userId: String = "",
    val displayName: String = "",
    val relation: FriendRelation = FriendRelation.UNKNOWN,
    val globalBlocked: Boolean = false,
    val removed: Boolean = false,
    val effective: Boolean = false,
    val inactiveReason: String = "",
    val groupNames: List<String> = emptyList(),
    val capabilitySummary: String = ""
)

data class FriendCenterUiState(
    val userId: String = "",
    val groups: List<FriendGroupUiItem> = emptyList(),
    val profiles: List<FriendProfileUiItem> = emptyList(),
    val inactiveProfiles: List<FriendProfileUiItem> = emptyList(),
    val selectedGroupId: String? = null,
    val selectedGroupMembers: List<FriendProfileUiItem> = emptyList(),
    val stats: FriendCenterStats = FriendCenterStats(),
    val searchQuery: String = "",
    val filter: FriendCenterFilter = FriendCenterFilter.ALL,
    val refreshAvailable: Boolean = false,
    val checkingRefreshAvailability: Boolean = false,
    val refreshing: Boolean = false,
    val lastRefreshMessage: String = "",
    val message: String = ""
) {
    val selectedGroup: FriendGroupUiItem?
        get() = groups.firstOrNull { it.id == selectedGroupId }
}

class FriendCenterViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(FriendCenterUiState())
    val uiState: StateFlow<FriendCenterUiState> = _uiState.asStateFlow()
    private var refreshAvailabilityToken: Long = 0L
    private var showRefreshAvailabilityMessage: Boolean = false
    private var refreshRequestToken: Long = 0L

    companion object {
        private const val REFRESH_UNAVAILABLE_MESSAGE = "请先打开目标应用并回到模块，再刷新好友列表"
    }

    fun load(userId: String) {
        if (userId.isBlank()) {
            _uiState.value = FriendCenterUiState(message = "当前账号尚未载入，请先打开目标应用并返回模块")
            return
        }
        UserMap.setCurrentUserId(userId)
        UserMap.load(userId)
        // 以当前账号 friend.json 快照为准，同步时把缺失好友标记为失效，避免长期残留“伪有效”关系。
        FriendRepository.mergeFromUserMap(userId, allowPruneMissing = true)
        refresh(
            userId,
            refreshAvailable = false,
            checkingRefreshAvailability = false,
            lastRefreshMessage = ""
        )
    }

    fun requestRefreshAvailability(context: Context, showUnavailableMessage: Boolean = false) {
        val state = _uiState.value
        val currentUserId = state.userId.trim()
        if (currentUserId.isEmpty()) {
            refreshAvailabilityToken = 0L
            showRefreshAvailabilityMessage = false
            _uiState.value = state.copy(
                refreshAvailable = false,
                checkingRefreshAvailability = false
            )
            return
        }

        val token = System.currentTimeMillis()
        refreshAvailabilityToken = token
        showRefreshAvailabilityMessage = showUnavailableMessage
        _uiState.value = state.copy(
            refreshAvailable = false,
            checkingRefreshAvailability = true
        )

        try {
            context.applicationContext.sendBroadcast(Intent(ApplicationHookConstants.BroadcastActions.HOOK_READY).apply {
                putExtra("userId", currentUserId)
            })
        } catch (t: Throwable) {
            refreshAvailabilityToken = 0L
            showRefreshAvailabilityMessage = false
            val errorMessage = "检测目标应用状态失败：${t.message ?: t.javaClass.simpleName}"
            _uiState.value = _uiState.value.copy(
                refreshAvailable = false,
                checkingRefreshAvailability = false,
                lastRefreshMessage = if (showUnavailableMessage) errorMessage else _uiState.value.lastRefreshMessage,
                message = if (showUnavailableMessage) errorMessage else _uiState.value.message
            )
            return
        }

        viewModelScope.launch {
            delay(2_000L)
            val latest = _uiState.value
            if (
                refreshAvailabilityToken != token ||
                latest.userId != currentUserId ||
                !latest.checkingRefreshAvailability
            ) {
                return@launch
            }
            refreshAvailabilityToken = 0L
            val shouldShowMessage = showRefreshAvailabilityMessage
            showRefreshAvailabilityMessage = false
            _uiState.value = latest.copy(
                refreshAvailable = false,
                checkingRefreshAvailability = false,
                lastRefreshMessage = if (shouldShowMessage) REFRESH_UNAVAILABLE_MESSAGE else latest.lastRefreshMessage,
                message = if (shouldShowMessage) REFRESH_UNAVAILABLE_MESSAGE else latest.message
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun handleRefreshAvailabilityResult(
        resultUserId: String,
        ready: Boolean,
        message: String,
        currentUserId: String,
        timestamp: Long
    ) {
        val state = _uiState.value
        val pageUserId = state.userId.trim()
        val normalizedResultUserId = resultUserId.trim()
        if (pageUserId.isNotEmpty() && normalizedResultUserId.isNotEmpty() && pageUserId != normalizedResultUserId) {
            return
        }

        refreshAvailabilityToken = 0L
        val shouldShowMessage = showRefreshAvailabilityMessage
        showRefreshAvailabilityMessage = false
        val normalizedMessage = message.ifBlank {
            if (ready) "" else REFRESH_UNAVAILABLE_MESSAGE
        }
        _uiState.value = state.copy(
            refreshAvailable = ready,
            checkingRefreshAvailability = false,
            lastRefreshMessage = if (!ready && shouldShowMessage) normalizedMessage else state.lastRefreshMessage,
            message = when {
                ready && state.message == REFRESH_UNAVAILABLE_MESSAGE -> ""
                ready -> state.message
                shouldShowMessage -> normalizedMessage
                else -> state.message
            }
        )
    }

    fun requestRefreshFromAlipay(context: Context) {
        val state = _uiState.value
        val currentUserId = state.userId.trim()
        if (currentUserId.isEmpty()) {
            _uiState.value = state.copy(message = "当前账号尚未载入，请先打开目标应用并返回模块")
            return
        }
        if (!state.refreshAvailable) {
            _uiState.value = state.copy(
                lastRefreshMessage = REFRESH_UNAVAILABLE_MESSAGE,
                message = REFRESH_UNAVAILABLE_MESSAGE
            )
            requestRefreshAvailability(context, showUnavailableMessage = true)
            return
        }
        if (state.refreshing) return

        val token = System.currentTimeMillis()
        refreshRequestToken = token
        _uiState.value = state.copy(
            refreshing = true,
            lastRefreshMessage = "正在刷新好友...",
            message = "正在刷新好友..."
        )

        try {
            context.applicationContext.sendBroadcast(Intent(ApplicationHookConstants.BroadcastActions.REFRESH_FRIENDS).apply {
                putExtra("userId", currentUserId)
                putExtra("manual", true)
            })
        } catch (t: Throwable) {
            refreshRequestToken = 0L
            _uiState.value = _uiState.value.copy(
                refreshing = false,
                lastRefreshMessage = "发送刷新指令失败：${t.message ?: t.javaClass.simpleName}",
                message = "发送刷新指令失败：${t.message ?: t.javaClass.simpleName}"
            )
            return
        }

        viewModelScope.launch {
            delay(10_000L)
            val latest = _uiState.value
            if (!latest.refreshing || refreshRequestToken != token || latest.userId != currentUserId) {
                return@launch
            }
            refreshRequestToken = 0L
            UserMap.setCurrentUserId(currentUserId)
            UserMap.load(currentUserId)
            FriendRepository.mergeFromUserMap(currentUserId, allowPruneMissing = true)
            refresh(
                currentUserId,
                latest.selectedGroupId,
                message = "已发送刷新指令，未收到完成回执",
                refreshing = false,
                lastRefreshMessage = "已发送刷新指令，未收到完成回执"
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun handleRefreshResult(
        resultUserId: String,
        success: Boolean,
        message: String,
        profiles: Int,
        groups: Int,
        timestamp: Long
    ) {
        val state = _uiState.value
        val currentUserId = state.userId.trim()
        val normalizedResultUserId = resultUserId.trim()
        if (currentUserId.isNotEmpty() && normalizedResultUserId.isNotEmpty() && currentUserId != normalizedResultUserId) {
            return
        }

        val targetUserId = normalizedResultUserId.ifEmpty { currentUserId }
        if (targetUserId.isEmpty()) {
            _uiState.value = state.copy(
                refreshing = false,
                lastRefreshMessage = message.ifBlank { "刷新好友失败：账号为空" },
                message = message.ifBlank { "刷新好友失败：账号为空" }
            )
            return
        }

        refreshRequestToken = 0L
        val normalizedMessage = message.ifBlank {
            if (success) {
                "好友刷新完成: profiles=$profiles, groups=$groups"
            } else {
                "好友刷新失败"
            }
        }
        if (success) {
            UserMap.setCurrentUserId(targetUserId)
            UserMap.load(targetUserId)
            FriendRepository.mergeFromUserMap(targetUserId, allowPruneMissing = true)
            refresh(
                targetUserId,
                state.selectedGroupId,
                message = normalizedMessage,
                refreshing = false,
                lastRefreshMessage = normalizedMessage
            )
        } else {
            _uiState.value = state.copy(
                refreshing = false,
                lastRefreshMessage = normalizedMessage,
                message = normalizedMessage
            )
        }
    }

    fun updateSearch(query: String) {
        val state = _uiState.value
        if (state.searchQuery == query) return
        refresh(state.userId, state.selectedGroupId, searchQuery = query)
    }

    fun updateFilter(filter: FriendCenterFilter) {
        val state = _uiState.value
        if (state.filter == filter) return
        refresh(state.userId, state.selectedGroupId, filter = filter)
    }

    fun selectGroup(groupId: String) {
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isEmpty()) return
        refresh(_uiState.value.userId, normalizedGroupId)
    }

    fun addGroup(name: String) {
        val state = _uiState.value
        val normalizedName = name.trim()
        if (state.userId.isBlank() || normalizedName.isBlank()) return
        val now = System.currentTimeMillis()
        val group = FriendGroup(
            id = UUID.randomUUID().toString(),
            name = normalizedName,
            createdAt = now,
            updatedAt = now
        )
        FriendRepository.upsertGroup(state.userId, group)
        refresh(state.userId, group.id)
    }

    fun deleteGroup(groupId: String) {
        val state = _uiState.value
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isEmpty()) return
        if (state.userId.isBlank()) return
        FriendRepository.deleteGroup(state.userId, normalizedGroupId)
        Config.load(state.userId)
        if (Config.sanitizeFriendSelectionFieldsForUser(state.userId)) {
            Config.save(state.userId, false)
        }
        refresh(state.userId)
    }

    fun renameGroup(groupId: String, name: String) {
        val state = _uiState.value
        val normalizedGroupId = groupId.trim()
        val normalizedName = name.trim()
        if (state.userId.isBlank() || normalizedGroupId.isEmpty() || normalizedName.isBlank()) return
        val group = FriendRepository.current(state.userId).groups.firstOrNull { it.id == normalizedGroupId } ?: return
        FriendRepository.upsertGroup(
            state.userId,
            group.copy(name = normalizedName)
        )
        refresh(state.userId, normalizedGroupId)
    }

    fun toggleMember(friendUserId: String, checked: Boolean) {
        val state = _uiState.value
        val groupId = state.selectedGroupId ?: return
        setMember(groupId, friendUserId, checked)
    }

    fun setMember(groupId: String, friendUserId: String, checked: Boolean) {
        val state = _uiState.value
        val normalizedGroupId = groupId.trim()
        val normalizedFriendUserId = friendUserId.trim()
        if (normalizedGroupId.isEmpty() || normalizedFriendUserId.isEmpty()) return
        if (state.userId.isBlank()) return
        val group = FriendRepository.current(state.userId).groups.firstOrNull { it.id == normalizedGroupId } ?: return
        val nextMembers = linkedSetOf<String>().apply { addAll(group.memberIds) }
        if (checked) {
            nextMembers.add(normalizedFriendUserId)
        } else {
            nextMembers.remove(normalizedFriendUserId)
        }
        FriendRepository.upsertGroup(
            state.userId,
            group.copy(memberIds = nextMembers)
        )
        refresh(state.userId, normalizedGroupId)
    }

    fun setMemberBatch(groupId: String, friendUserIds: Collection<String>, checked: Boolean) {
        val state = _uiState.value
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isEmpty() || state.userId.isBlank()) return
        val normalizedIds = friendUserIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalizedIds.isEmpty()) return
        val group = FriendRepository.current(state.userId).groups.firstOrNull { it.id == normalizedGroupId } ?: return
        val nextMembers = linkedSetOf<String>().apply { addAll(group.memberIds) }
        if (checked) {
            nextMembers.addAll(normalizedIds)
        } else {
            nextMembers.removeAll(normalizedIds.toSet())
        }
        if (nextMembers == group.memberIds) return
        FriendRepository.upsertGroup(
            state.userId,
            group.copy(memberIds = nextMembers)
        )
        refresh(state.userId, normalizedGroupId)
    }

    fun setGlobalBlocked(friendUserId: String, blocked: Boolean) {
        val state = _uiState.value
        val normalizedFriendUserId = friendUserId.trim()
        if (normalizedFriendUserId.isEmpty()) return
        if (state.userId.isBlank()) return
        val currentConfig = FriendRepository.current(state.userId)
        val profile = currentConfig.profiles[normalizedFriendUserId] ?: return
        if (profile.globalBlocked == blocked) return
        val optimisticConfig = currentConfig.deepCopy().apply {
            profiles[normalizedFriendUserId] = profile.copy(globalBlocked = blocked)
        }
        _uiState.value = buildState(
            userId = state.userId,
            preferredGroupId = state.selectedGroupId,
            config = optimisticConfig,
            searchQuery = state.searchQuery,
            filter = state.filter
        )
        if (FriendRepository.save(state.userId, optimisticConfig)) {
            refresh(state.userId, state.selectedGroupId)
        } else {
            _uiState.value = buildState(
                userId = state.userId,
                preferredGroupId = state.selectedGroupId,
                config = currentConfig,
                searchQuery = state.searchQuery,
                filter = state.filter,
                message = "黑名单保存失败，请稍后重试"
            )
        }
    }

    fun setGlobalBlockedBatch(friendUserIds: Collection<String>, blocked: Boolean) {
        val state = _uiState.value
        if (state.userId.isBlank()) return
        val normalizedIds = friendUserIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalizedIds.isEmpty()) return
        val currentConfig = FriendRepository.current(state.userId)
        val changedIds = normalizedIds.filter { userId ->
            currentConfig.profiles[userId]?.globalBlocked != blocked
        }
        if (changedIds.isEmpty()) return
        val optimisticConfig = currentConfig.deepCopy().apply {
            changedIds.forEach { userId ->
                val profile = profiles[userId] ?: return@forEach
                profiles[userId] = profile.copy(globalBlocked = blocked)
            }
        }
        _uiState.value = buildState(
            userId = state.userId,
            preferredGroupId = state.selectedGroupId,
            config = optimisticConfig,
            searchQuery = state.searchQuery,
            filter = state.filter
        )
        if (FriendRepository.save(state.userId, optimisticConfig)) {
            refresh(state.userId, state.selectedGroupId)
        } else {
            _uiState.value = buildState(
                userId = state.userId,
                preferredGroupId = state.selectedGroupId,
                config = currentConfig,
                searchQuery = state.searchQuery,
                filter = state.filter,
                message = "批量黑名单保存失败，请稍后重试"
            )
        }
    }

    private fun refresh(
        userId: String,
        preferredGroupId: String? = null,
        searchQuery: String = _uiState.value.searchQuery,
        filter: FriendCenterFilter = _uiState.value.filter,
        refreshAvailable: Boolean = _uiState.value.refreshAvailable,
        checkingRefreshAvailability: Boolean = _uiState.value.checkingRefreshAvailability,
        refreshing: Boolean = _uiState.value.refreshing,
        lastRefreshMessage: String = _uiState.value.lastRefreshMessage,
        message: String = ""
    ) {
        if (userId.isBlank()) return
        _uiState.value = buildState(
            userId = userId,
            preferredGroupId = preferredGroupId,
            config = FriendRepository.current(userId),
            searchQuery = searchQuery,
            filter = filter,
            refreshAvailable = refreshAvailable,
            checkingRefreshAvailability = checkingRefreshAvailability,
            refreshing = refreshing,
            lastRefreshMessage = lastRefreshMessage,
            message = message
        )
    }

    private fun buildState(
        userId: String,
        preferredGroupId: String?,
        config: FriendCenterConfig,
        searchQuery: String,
        filter: FriendCenterFilter,
        refreshAvailable: Boolean = _uiState.value.refreshAvailable,
        checkingRefreshAvailability: Boolean = _uiState.value.checkingRefreshAvailability,
        refreshing: Boolean = _uiState.value.refreshing,
        lastRefreshMessage: String = _uiState.value.lastRefreshMessage,
        message: String = ""
    ): FriendCenterUiState {
        val groupNamesByUser = linkedMapOf<String, MutableList<String>>()
        config.groups.forEach { group ->
            group.memberIds.forEach { memberId ->
                groupNamesByUser.getOrPut(memberId) { mutableListOf() }.add(group.name)
            }
        }
        val allProfiles = config.profiles.values
            .filter { it.relation != FriendRelation.SELF }
            .map { profile ->
                val relation = if (profile.removed) FriendRelation.REMOVED else profile.relation
                val effective = relation == FriendRelation.MUTUAL && !profile.removed && !profile.globalBlocked
                FriendProfileUiItem(
                    userId = profile.userId,
                    displayName = profile.displayName.ifBlank { profile.userId },
                    relation = relation,
                    globalBlocked = profile.globalBlocked,
                    removed = profile.removed,
                    effective = effective,
                    inactiveReason = inactiveReason(relation, profile.removed, profile.globalBlocked),
                    groupNames = groupNamesByUser[profile.userId].orEmpty().sorted(),
                    capabilitySummary = profile.capabilities.entries
                        .sortedBy { it.key }
                        .joinToString(" | ") { "${it.key}:${it.value.state.name}" }
                )
            }
            .sortedWith(
                compareByDescending<FriendProfileUiItem> { it.effective }
                    .thenByDescending { it.globalBlocked }
                    .thenBy { it.displayName }
                    .thenBy { it.userId }
            )
        val allProfileById = allProfiles.associateBy { it.userId }
        val groups = config.groups
            .sortedBy { it.name }
            .map { group ->
                val effectiveCount = group.memberIds.count { allProfileById[it]?.effective == true }
                FriendGroupUiItem(
                    id = group.id,
                    name = group.name,
                    memberIds = group.memberIds.toSet(),
                    memberCount = group.memberIds.size,
                    effectiveCount = effectiveCount,
                    inactiveCount = group.memberIds.size - effectiveCount
                )
            }
        val selected = when {
            preferredGroupId != null && groups.any { it.id == preferredGroupId } -> preferredGroupId
            groups.any { it.id == _uiState.value.selectedGroupId } -> _uiState.value.selectedGroupId
            else -> groups.firstOrNull()?.id
        }
        val selectedGroupIds = groups.firstOrNull { it.id == selected }?.memberIds.orEmpty()
        val searchedProfiles = allProfiles.filter { it.matches(searchQuery) }
        val filteredProfiles = searchedProfiles.filter {
            when (filter) {
                FriendCenterFilter.ALL -> true
                FriendCenterFilter.AVAILABLE -> it.effective
                FriendCenterFilter.BLOCKED -> it.globalBlocked
                FriendCenterFilter.INACTIVE -> !it.effective && !it.globalBlocked
            }
        }
        val selectedGroupMembers = searchedProfiles.sortedWith(
            compareByDescending<FriendProfileUiItem> { selectedGroupIds.contains(it.userId) }
                .thenByDescending { it.effective }
                .thenBy { it.displayName }
                .thenBy { it.userId }
        )
        return FriendCenterUiState(
            userId = userId,
            groups = groups,
            profiles = filteredProfiles,
            inactiveProfiles = allProfiles.filter { !it.effective },
            selectedGroupId = selected,
            selectedGroupMembers = selectedGroupMembers,
            stats = FriendCenterStats(
                total = allProfiles.size,
                available = allProfiles.count { it.effective },
                blocked = allProfiles.count { it.globalBlocked },
                groups = groups.size,
                inactive = allProfiles.count { !it.effective && !it.globalBlocked }
            ),
            searchQuery = searchQuery,
            filter = filter,
            refreshAvailable = refreshAvailable,
            checkingRefreshAvailability = checkingRefreshAvailability,
            refreshing = refreshing,
            lastRefreshMessage = lastRefreshMessage,
            message = message
        )
    }

    private fun FriendProfileUiItem.matches(query: String): Boolean {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return true
        return displayName.contains(normalizedQuery, ignoreCase = true) ||
            userId.contains(normalizedQuery, ignoreCase = true) ||
            groupNames.any { it.contains(normalizedQuery, ignoreCase = true) }
    }

    private fun inactiveReason(
        relation: FriendRelation,
        removed: Boolean,
        globalBlocked: Boolean
    ): String {
        return when {
            globalBlocked -> "全局黑名单"
            removed || relation == FriendRelation.REMOVED -> "已失效"
            relation == FriendRelation.ONE_WAY -> "单向好友"
            relation == FriendRelation.UNKNOWN -> "关系未知"
            relation == FriendRelation.SELF -> "当前账号"
            else -> ""
        }
    }

    private fun FriendCenterConfig.deepCopy(): FriendCenterConfig {
        return FriendCenterConfig(
            schemaVersion = schemaVersion,
            userId = userId,
            groups = groups.map { group ->
                FriendGroup(
                    id = group.id,
                    name = group.name,
                    memberIds = linkedSetOf<String>().apply { addAll(group.memberIds) },
                    createdAt = group.createdAt,
                    updatedAt = group.updatedAt
                )
            }.toMutableList(),
            profiles = linkedMapOf<String, FriendProfile>().apply {
                this@deepCopy.profiles.forEach { (id, profile) ->
                    put(
                        id,
                        FriendProfile(
                            userId = profile.userId,
                            displayName = profile.displayName,
                            friendStatus = profile.friendStatus,
                            relation = profile.relation,
                            globalBlocked = profile.globalBlocked,
                            globalPinned = profile.globalPinned,
                            removed = profile.removed,
                            capabilities = linkedMapOf<String, FriendModuleCapability>().apply {
                                profile.capabilities.forEach { (moduleKey, capability) ->
                                    put(moduleKey, capability.copy())
                                }
                            }
                        )
                    )
                }
            }
        )
    }
}

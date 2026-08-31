package io.github.aoguai.sesameag.ui.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.entity.friend.FriendCenterConfig
import io.github.aoguai.sesameag.entity.friend.FriendModuleCapability
import io.github.aoguai.sesameag.entity.friend.FriendGroup
import io.github.aoguai.sesameag.entity.friend.FriendProfile
import io.github.aoguai.sesameag.entity.friend.FriendRelation
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val message: String = ""
) {
    val selectedGroup: FriendGroupUiItem?
        get() = groups.firstOrNull { it.id == selectedGroupId }
}

class FriendCenterViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    private val _uiState = MutableStateFlow(
        FriendCenterUiState(
            selectedGroupId = savedStateHandle[STATE_SELECTED_GROUP],
            searchQuery = savedStateHandle.get<String>(STATE_SEARCH_QUERY).orEmpty(),
            filter = savedStateHandle.get<String>(STATE_FILTER)
                ?.let { runCatching { FriendCenterFilter.valueOf(it) }.getOrNull() }
                ?: FriendCenterFilter.ALL,
        )
    )
    val uiState: StateFlow<FriendCenterUiState> = _uiState.asStateFlow()
    private val friendRefreshCoordinator = FriendRefreshCoordinator(viewModelScope)
    val refreshState: StateFlow<FriendRefreshUiState> = friendRefreshCoordinator.state

    companion object {
        private const val STATE_SEARCH_QUERY = "friend_center_search_query"
        private const val STATE_FILTER = "friend_center_filter"
        private const val STATE_SELECTED_GROUP = "friend_center_selected_group"
    }

    suspend fun load(userId: String) {
        friendRefreshCoordinator.bindUser(userId)
        if (userId.isBlank()) {
            _uiState.value = FriendCenterUiState(message = "当前账号尚未载入，请先打开目标应用并返回模块")
            return
        }
        val previousState = _uiState.value
        val loadedState = loadStateFromStorage(
            userId = userId,
            previousState = previousState,
        )
        _uiState.value = loadedState
        savedStateHandle[STATE_SELECTED_GROUP] = loadedState.selectedGroupId
    }

    fun requestRefreshAvailability(context: Context, showUnavailableMessage: Boolean = false) {
        friendRefreshCoordinator.requestRefreshAvailability(context, showUnavailableMessage)
    }

    fun handleRefreshAvailabilityResult(
        resultUserId: String,
        ready: Boolean,
        message: String,
    ) {
        friendRefreshCoordinator.handleRefreshAvailabilityResult(resultUserId, ready, message)
    }

    fun requestRefreshFromAlipay(context: Context) {
        friendRefreshCoordinator.requestRefresh(context)
    }

    fun handleRefreshResult(
        resultUserId: String,
        success: Boolean,
        message: String,
        profiles: Int,
        groups: Int,
    ) {
        val completion = friendRefreshCoordinator.handleRefreshResult(
            resultUserId = resultUserId,
            success = success,
            message = message,
            profiles = profiles,
            groups = groups,
        ) ?: return
        if (!completion.success || completion.userId.isEmpty()) return

        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState.userId.isNotEmpty() && currentState.userId != completion.userId) {
                return@launch
            }
            val loadedState = loadStateFromStorage(
                userId = completion.userId,
                previousState = currentState,
                message = completion.message,
            )
            _uiState.value = loadedState
            savedStateHandle[STATE_SELECTED_GROUP] = loadedState.selectedGroupId
        }
    }

    fun updateSearch(query: String) {
        val state = _uiState.value
        if (state.searchQuery == query) return
        savedStateHandle[STATE_SEARCH_QUERY] = query
        refresh(state.userId, state.selectedGroupId, searchQuery = query)
    }

    fun updateFilter(filter: FriendCenterFilter) {
        val state = _uiState.value
        if (state.filter == filter) return
        savedStateHandle[STATE_FILTER] = filter.name
        refresh(state.userId, state.selectedGroupId, filter = filter)
    }

    fun selectGroup(groupId: String) {
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isEmpty()) return
        savedStateHandle[STATE_SELECTED_GROUP] = normalizedGroupId
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

    private suspend fun loadStateFromStorage(
        userId: String,
        previousState: FriendCenterUiState,
        message: String = "",
    ): FriendCenterUiState = withContext(Dispatchers.IO) {
        UserMap.setCurrentUserId(userId)
        UserMap.load(userId)
        // 以当前账号 friend.json 快照为准，同步时把缺失好友标记为失效，避免长期残留“伪有效”关系。
        FriendRepository.mergeFromUserMap(userId, allowPruneMissing = true)
        buildState(
            userId = userId,
            preferredGroupId = previousState.selectedGroupId,
            config = FriendRepository.current(userId),
            searchQuery = previousState.searchQuery,
            filter = previousState.filter,
            message = message,
        )
    }

    private fun refresh(
        userId: String,
        preferredGroupId: String? = null,
        searchQuery: String = _uiState.value.searchQuery,
        filter: FriendCenterFilter = _uiState.value.filter,
        message: String = ""
    ) {
        if (userId.isBlank()) return
        _uiState.value = buildState(
            userId = userId,
            preferredGroupId = preferredGroupId,
            config = FriendRepository.current(userId),
            searchQuery = searchQuery,
            filter = filter,
            message = message
        )
        savedStateHandle[STATE_SELECTED_GROUP] = _uiState.value.selectedGroupId
    }

    private fun buildState(
        userId: String,
        preferredGroupId: String?,
        config: FriendCenterConfig,
        searchQuery: String,
        filter: FriendCenterFilter,
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

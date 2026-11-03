package io.github.aoguai.sesameag.util.friend

import io.github.aoguai.sesameag.entity.AlipayUser
import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.entity.friend.FriendCenterConfig
import io.github.aoguai.sesameag.entity.friend.FriendProfile
import io.github.aoguai.sesameag.entity.friend.FriendRelation
import io.github.aoguai.sesameag.entity.friend.FriendRelationFilter
import io.github.aoguai.sesameag.entity.friend.FriendSelectionCountSpec
import io.github.aoguai.sesameag.entity.friend.FriendSelectionScope
import io.github.aoguai.sesameag.entity.friend.FriendSelectionSpec
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionCountModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.util.maps.UserMap

data class FriendSelectionPreview(
    val items: List<FriendSelectionPreviewItem> = emptyList(),
    val summary: FriendSelectionPreviewSummary = FriendSelectionPreviewSummary(),
)

data class FriendSelectionPreviewItem(
    val userId: String = "",
    val displayName: String = "",
    val selectedByAllFriends: Boolean = false,
    val selectedDirectly: Boolean = false,
    val selectedByGroups: List<String> = emptyList(),
    val selectedByGroupIds: List<String> = emptyList(),
    val excludedDirectly: Boolean = false,
    val excludedByGroups: List<String> = emptyList(),
    val excludedByGroupIds: List<String> = emptyList(),
    val globalBlocked: Boolean = false,
    val relation: FriendRelation = FriendRelation.UNKNOWN,
    val capabilityState: String = "UNKNOWN",
    val effective: Boolean = false,
    val inactiveReason: String = "",
    val count: Int? = null,
    val countSource: String = "",
)

data class FriendSelectionPreviewSummary(
    val allFriendsSelection: Boolean = false,
    val selectedCount: Int = 0,
    val directSelectedCount: Int = 0,
    val groupSelectedCount: Int = 0,
    val excludedCount: Int = 0,
    val effectiveCount: Int = 0,
    val inactiveCount: Int = 0,
    val blockedInactiveCount: Int = 0,
    val relationInactiveCount: Int = 0,
    val capabilityInactiveCount: Int = 0,
    val countInactiveCount: Int = 0,
    val hasAdvancedRules: Boolean = false,
)

object FriendSelectionResolver {
    @JvmStatic
    fun resolveIds(field: FriendSelectionModelField?): Set<String> = resolveIds(field?.value)

    @JvmStatic
    fun resolveIds(spec: FriendSelectionSpec?): Set<String> = resolveIds(spec, FriendRepository.current())

    internal fun resolveIds(
        spec: FriendSelectionSpec?,
        config: FriendCenterConfig,
    ): Set<String> {
        val selection = spec ?: return emptySet()
        val included = selectedUserIds(selection, config)
        if (included.isEmpty()) return emptySet()

        val excluded = expandUsers(selection.excludeUserIds, selection.excludeGroupIds, config)
        val result = linkedSetOf<String>()
        for (userId in included) {
            if (excluded.contains(userId)) continue
            val profile = config.profiles[userId] ?: continue
            if (!passesRelation(profile, selection.relationFilter)) continue
            if (!passesCapability(profile, selection)) continue
            if (profile.globalBlocked) continue
            result.add(userId)
        }
        return result
    }

    @JvmStatic
    fun contains(
        field: FriendSelectionModelField?,
        userId: String?,
    ): Boolean = contains(field?.value, userId)

    @JvmStatic
    fun contains(
        spec: FriendSelectionSpec?,
        userId: String?,
    ): Boolean {
        val normalized = userId?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        val selection = spec ?: return false
        val config = FriendRepository.current()
        val profile = config.profiles[normalized] ?: return false
        val groupMembersById = config.groups.associate { it.id to it.memberIds }
        if (!isSelected(selection, normalized, groupMembersById, config.profiles.keys)) return false
        if (isExcluded(selection, normalized, groupMembersById)) return false
        if (!passesRelation(profile, selection.relationFilter)) return false
        if (!passesCapability(profile, selection)) return false
        if (profile.globalBlocked) return false
        return true
    }

    @JvmStatic
    fun containsConfigured(
        spec: FriendSelectionSpec?,
        userId: String?,
    ): Boolean {
        val normalized = userId?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        val selection = spec ?: return false
        val config = FriendRepository.current()
        val groupMembersById = config.groups.associate { it.id to it.memberIds }
        return isSelected(selection, normalized, groupMembersById, config.profiles.keys) &&
            !isExcluded(selection, normalized, groupMembersById)
    }

    @JvmStatic
    fun resolveCountMap(field: FriendSelectionCountModelField?): Map<String, Int> = resolveCountMap(field?.value)

    @JvmStatic
    fun resolveCountMap(spec: FriendSelectionCountSpec?): Map<String, Int> = resolveCountMap(spec, FriendRepository.current())

    internal fun resolveCountMap(
        spec: FriendSelectionCountSpec?,
        config: FriendCenterConfig,
    ): Map<String, Int> {
        val countSpec = spec ?: return emptyMap()
        val ids = resolveIds(countSpec.selection, config)
        if (ids.isEmpty()) return emptyMap()
        val groupMembers =
            countSpec.selection.includeGroupIds.associateWith { groupId ->
                config.groups.firstOrNull { it.id == groupId }?.memberIds ?: emptySet()
            }
        val result = linkedMapOf<String, Int>()
        for (userId in ids) {
            var groupCount: Int? = null
            for (groupId in countSpec.selection.includeGroupIds) {
                val members = groupMembers[groupId] ?: continue
                if (!members.contains(userId)) continue
                val overrideCount = countSpec.groupCountOverrides[groupId] ?: continue
                groupCount = overrideCount
                break
            }
            val count =
                countSpec.userCountOverrides[userId]
                    ?: groupCount
                    ?: countSpec.defaultCount
            if (count > 0) {
                result[userId] = count
            }
        }
        return result
    }

    @JvmStatic
    fun preview(
        spec: FriendSelectionSpec?,
        userId: String? = UserMap.currentUid,
    ): FriendSelectionPreview {
        val selection = spec ?: FriendSelectionSpec()
        val config = FriendRepository.current(userId)
        return buildPreview(selection, null, config)
    }

    @JvmStatic
    fun previewCount(
        spec: FriendSelectionCountSpec?,
        userId: String? = UserMap.currentUid,
    ): FriendSelectionPreview {
        val countSpec = spec ?: FriendSelectionCountSpec()
        val config = FriendRepository.current(userId)
        return buildPreview(countSpec.selection, countSpec, config)
    }

    @JvmStatic
    fun availableFriendOptions(): List<AlipayUser> =
        FriendRepository
            .listProfiles()
            .filter { passesRelation(it, FriendRelationFilter.MUTUAL_ONLY) && !it.globalBlocked }
            .map { AlipayUser(it.userId, it.displayName.ifBlank { it.userId }) }

    @JvmStatic
    fun shouldSkipFriend(userId: String?): Boolean {
        val normalized = userId?.trim().orEmpty()
        if (normalized.isEmpty()) return true
        if (normalized == UserMap.currentUid) return true
        val profile = FriendRepository.current().profiles[normalized] ?: return true
        return !passesRelation(profile, FriendRelationFilter.MUTUAL_ONLY) || profile.globalBlocked
    }

    private fun selectedUserIds(
        selection: FriendSelectionSpec,
        config: FriendCenterConfig,
    ): LinkedHashSet<String> {
        if (selection.selectionScope == FriendSelectionScope.ALL_FRIENDS) {
            return config.profiles.keys.mapNotNullTo(linkedSetOf()) { it.trim().takeIf(String::isNotEmpty) }
        }
        return expandUsers(selection.includeUserIds, selection.includeGroupIds, config)
    }

    private fun expandUsers(
        userIds: Set<String>,
        groupIds: Set<String>,
        config: FriendCenterConfig,
    ): LinkedHashSet<String> {
        val result = linkedSetOf<String>()
        userIds.mapNotNullTo(result) { it.trim().takeIf(String::isNotEmpty) }
        groupIds.forEach { groupId ->
            config.groups.firstOrNull { it.id == groupId }?.memberIds?.forEach { memberId ->
                memberId.trim().takeIf(String::isNotEmpty)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun isSelected(
        selection: FriendSelectionSpec,
        userId: String,
        groupMembersById: Map<String, Set<String>>,
        profileIds: Set<String>,
    ): Boolean =
        (selection.selectionScope == FriendSelectionScope.ALL_FRIENDS && profileIds.contains(userId)) ||
            containsUserId(selection.includeUserIds, userId) ||
            groupsContainUser(selection.includeGroupIds, userId, groupMembersById)

    private fun isExcluded(
        selection: FriendSelectionSpec,
        userId: String,
        groupMembersById: Map<String, Set<String>>,
    ): Boolean =
        containsUserId(selection.excludeUserIds, userId) ||
            groupsContainUser(selection.excludeGroupIds, userId, groupMembersById)

    private fun containsUserId(
        userIds: Set<String>,
        userId: String,
    ): Boolean = userIds.any { it.trim() == userId }

    private fun groupsContainUser(
        groupIds: Set<String>,
        userId: String,
        groupMembersById: Map<String, Set<String>>,
    ): Boolean {
        for (rawGroupId in groupIds) {
            val groupId = rawGroupId.trim()
            if (groupId.isEmpty()) continue
            val members = groupMembersById[groupId] ?: continue
            if (members.any { it.trim() == userId }) {
                return true
            }
        }
        return false
    }

    private fun buildPreview(
        selection: FriendSelectionSpec,
        countSpec: FriendSelectionCountSpec?,
        config: FriendCenterConfig,
    ): FriendSelectionPreview {
        val includeUserIds = selection.includeUserIds.mapNotNullTo(linkedSetOf<String>()) { it.trim().takeIf(String::isNotEmpty) }
        val includeGroupIds = selection.includeGroupIds.mapNotNullTo(linkedSetOf<String>()) { it.trim().takeIf(String::isNotEmpty) }
        val excludeUserIds = selection.excludeUserIds.mapNotNullTo(linkedSetOf<String>()) { it.trim().takeIf(String::isNotEmpty) }
        val excludeGroupIds = selection.excludeGroupIds.mapNotNullTo(linkedSetOf<String>()) { it.trim().takeIf(String::isNotEmpty) }
        val includeGroupMap =
            config.groups
                .filter { includeGroupIds.contains(it.id) }
                .associateBy { it.id }
        val excludeGroupMap =
            config.groups
                .filter { excludeGroupIds.contains(it.id) }
                .associateBy { it.id }
        val selectsAllFriends = selection.selectionScope == FriendSelectionScope.ALL_FRIENDS
        val itemList =
            config.profiles.values
                .filter {
                    it.relation != FriendRelation.SELF ||
                        selection.relationFilter == FriendRelationFilter.INCLUDE_SELF
                }.map { profile ->
                    val selectedByGroupIds =
                        includeGroupMap.values
                            .filter { it.memberIds.contains(profile.userId) }
                            .map { it.id }
                    val selectedByGroups = selectedByGroupIds.mapNotNull { includeGroupMap[it]?.name }
                    val excludedByGroupIds =
                        excludeGroupMap.values
                            .filter { it.memberIds.contains(profile.userId) }
                            .map { it.id }
                    val excludedByGroups = excludedByGroupIds.mapNotNull { excludeGroupMap[it]?.name }
                    val selectedByAllFriends = selectsAllFriends
                    val selectedDirectly = includeUserIds.contains(profile.userId)
                    val excludedDirectly = excludeUserIds.contains(profile.userId)
                    val selected = selectedByAllFriends || selectedDirectly || selectedByGroupIds.isNotEmpty()
                    val excluded = excludedDirectly || excludedByGroupIds.isNotEmpty()
                    val relationReason = relationInactiveReason(profile, selection.relationFilter)
                    val capabilityReason = capabilityInactiveReason(profile, selection)
                    val countResult = countSpec?.let { resolveCountForPreview(profile.userId, selectedByGroupIds, it, includeGroupMap) }
                    val countReason =
                        when {
                            countSpec == null -> ""
                            !selected || excluded || relationReason != null || capabilityReason != null || profile.globalBlocked -> ""
                            (countResult?.count ?: 0) <= 0 -> "次数为 0"
                            else -> ""
                        }
                    val inactiveReason =
                        when {
                            !selected -> ""
                            excluded -> "已排除"
                            profile.globalBlocked -> "全局黑名单"
                            relationReason != null -> relationReason
                            capabilityReason != null -> capabilityReason
                            countReason.isNotBlank() -> countReason
                            else -> ""
                        }
                    val effective = selected && inactiveReason.isBlank()
                    FriendSelectionPreviewItem(
                        userId = profile.userId,
                        displayName = profile.displayName.ifBlank { profile.userId },
                        selectedByAllFriends = selectedByAllFriends,
                        selectedDirectly = selectedDirectly,
                        selectedByGroups = selectedByGroups,
                        selectedByGroupIds = selectedByGroupIds,
                        excludedDirectly = excludedDirectly,
                        excludedByGroups = excludedByGroups,
                        excludedByGroupIds = excludedByGroupIds,
                        globalBlocked = profile.globalBlocked,
                        relation = if (profile.removed) FriendRelation.REMOVED else profile.relation,
                        capabilityState = capabilityStateText(profile, selection),
                        effective = effective,
                        inactiveReason = inactiveReason,
                        count = countResult?.count,
                        countSource = countResult?.source.orEmpty(),
                    )
                }.sortedWith(
                    compareByDescending<FriendSelectionPreviewItem> { it.effective }
                        .thenByDescending {
                            it.selectedByAllFriends || it.selectedDirectly || it.selectedByGroupIds.isNotEmpty()
                        }.thenByDescending { it.excludedDirectly || it.excludedByGroupIds.isNotEmpty() }
                        .thenBy { it.displayName }
                        .thenBy { it.userId },
                )
        return FriendSelectionPreview(
            items = itemList,
            summary = buildSummary(selection, itemList),
        )
    }

    private data class PreviewCountResult(
        val count: Int,
        val source: String,
    )

    private fun resolveCountForPreview(
        userId: String,
        selectedByGroupIds: List<String>,
        countSpec: FriendSelectionCountSpec,
        includeGroupMap: Map<String, io.github.aoguai.sesameag.entity.friend.FriendGroup>,
    ): PreviewCountResult {
        countSpec.userCountOverrides[userId]?.let { return PreviewCountResult(it, "个人次数") }
        for (groupId in countSpec.selection.includeGroupIds) {
            if (!selectedByGroupIds.contains(groupId)) continue
            val count = countSpec.groupCountOverrides[groupId] ?: continue
            val groupName = includeGroupMap[groupId]?.name.orEmpty()
            return PreviewCountResult(count, if (groupName.isBlank()) "分组次数" else "分组次数:$groupName")
        }
        return PreviewCountResult(countSpec.defaultCount, "默认次数")
    }

    private fun buildSummary(
        selection: FriendSelectionSpec,
        items: List<FriendSelectionPreviewItem>,
    ): FriendSelectionPreviewSummary {
        val selectedItems =
            items.filter {
                it.selectedByAllFriends || it.selectedDirectly || it.selectedByGroupIds.isNotEmpty()
            }
        val inactiveItems = selectedItems.filter { !it.effective }
        return FriendSelectionPreviewSummary(
            allFriendsSelection = selection.selectionScope == FriendSelectionScope.ALL_FRIENDS,
            selectedCount = selectedItems.size,
            directSelectedCount = items.count { it.selectedDirectly },
            groupSelectedCount = items.count { it.selectedByGroupIds.isNotEmpty() },
            excludedCount = items.count { it.excludedDirectly || it.excludedByGroupIds.isNotEmpty() },
            effectiveCount = selectedItems.count { it.effective },
            inactiveCount = inactiveItems.size,
            blockedInactiveCount = inactiveItems.count { it.inactiveReason == "全局黑名单" },
            relationInactiveCount = inactiveItems.count { it.inactiveReason == "单向/失效好友" || it.inactiveReason == "关系不匹配" },
            capabilityInactiveCount = inactiveItems.count { it.inactiveReason == "未确认开通该玩法" || it.inactiveReason == "未开通或不可用" },
            countInactiveCount = inactiveItems.count { it.inactiveReason == "次数为 0" },
            hasAdvancedRules = selection.relationFilter != FriendRelationFilter.MUTUAL_ONLY,
        )
    }

    private fun passesRelation(
        profile: FriendProfile,
        filter: FriendRelationFilter,
    ): Boolean {
        val removed = profile.removed || profile.relation == FriendRelation.REMOVED
        return when (filter) {
            FriendRelationFilter.MUTUAL_ONLY -> {
                profile.relation == FriendRelation.MUTUAL && !removed
            }

            FriendRelationFilter.ALL_KNOWN -> {
                profile.relation != FriendRelation.UNKNOWN &&
                    profile.relation != FriendRelation.SELF &&
                    !removed
            }

            FriendRelationFilter.INCLUDE_SELF -> {
                profile.relation == FriendRelation.SELF ||
                    (profile.relation == FriendRelation.MUTUAL && !removed)
            }
        }
    }

    private fun relationInactiveReason(
        profile: FriendProfile,
        filter: FriendRelationFilter,
    ): String? {
        if (passesRelation(profile, filter)) return null
        val removed = profile.removed || profile.relation == FriendRelation.REMOVED
        return if (removed || profile.relation == FriendRelation.ONE_WAY || profile.relation == FriendRelation.UNKNOWN) {
            "单向/失效好友"
        } else {
            "关系不匹配"
        }
    }

    private fun passesCapability(
        profile: FriendProfile,
        selection: FriendSelectionSpec,
    ): Boolean {
        val filter = selection.capabilityFilter ?: return true
        if (filter.moduleKeys.isEmpty()) return true
        val requiredStates = filter.requiredStates.ifEmpty { linkedSetOf(FriendCapabilityState.OPEN) }
        return filter.moduleKeys.all { moduleKey ->
            val state = profile.capabilities[moduleKey]?.state ?: FriendCapabilityState.UNKNOWN
            when {
                state == FriendCapabilityState.UNKNOWN -> filter.includeUnknown
                else -> requiredStates.contains(state)
            }
        }
    }

    private fun capabilityInactiveReason(
        profile: FriendProfile,
        selection: FriendSelectionSpec,
    ): String? {
        val filter = selection.capabilityFilter ?: return null
        if (filter.moduleKeys.isEmpty()) return null
        val requiredStates = filter.requiredStates.ifEmpty { linkedSetOf(FriendCapabilityState.OPEN) }
        for (moduleKey in filter.moduleKeys) {
            val state = profile.capabilities[moduleKey]?.state ?: FriendCapabilityState.UNKNOWN
            if (state == FriendCapabilityState.UNKNOWN && !filter.includeUnknown) {
                return "未确认开通该玩法"
            }
            if (state != FriendCapabilityState.UNKNOWN && !requiredStates.contains(state)) {
                return "未开通或不可用"
            }
        }
        return null
    }

    private fun capabilityStateText(
        profile: FriendProfile,
        selection: FriendSelectionSpec,
    ): String {
        val moduleKeys = selection.capabilityFilter?.moduleKeys.orEmpty()
        if (moduleKeys.isEmpty()) {
            return ""
        }
        return moduleKeys.joinToString(" | ") { moduleKey ->
            "$moduleKey:${profile.capabilities[moduleKey]?.state ?: FriendCapabilityState.UNKNOWN}"
        }
    }
}

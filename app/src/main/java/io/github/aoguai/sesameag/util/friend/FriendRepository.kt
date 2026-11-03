package io.github.aoguai.sesameag.util.friend

import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.entity.friend.FriendCenterConfig
import io.github.aoguai.sesameag.entity.friend.FriendGroup
import io.github.aoguai.sesameag.entity.friend.FriendModuleCapability
import io.github.aoguai.sesameag.entity.friend.FriendProfile
import io.github.aoguai.sesameag.entity.friend.FriendRelation
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.UserMap
import java.util.UUID

object FriendRepository {
    private const val TAG = "FriendRepository"
    const val SCHEMA_VERSION = 1
    const val MUTUAL_FRIEND_STATUS = 1

    @JvmStatic
    fun load(userId: String? = UserMap.currentUid): FriendCenterConfig {
        val owner = userId?.trim().orEmpty()
        if (owner.isEmpty()) return FriendCenterConfig()
        return Files.withAccountLock(owner) {
            val file = Files.getFriendCenterFile(owner)
            val body = Files.readText(file)
            val config = if (body.isBlank()) {
                FriendCenterConfig(userId = owner)
            } else {
                requireNotNull(JsonUtil.parseObject(body, object : TypeReference<FriendCenterConfig>() {})) {
                    "好友中心格式无效"
                }
            }
            require(config.userId.isBlank() || config.userId == owner) { "好友中心账号不匹配" }
            normalizeConfig(config, owner)
            config
        }
    }

    @JvmStatic
    fun save(userId: String?, config: FriendCenterConfig): Boolean {
        val owner = userId?.trim().orEmpty()
        if (owner.isEmpty()) return false
        return try {
            Files.withAccountLock(owner) {
                require(config.userId.isBlank() || config.userId == owner) { "好友中心账号不匹配" }
                val target = copyConfig(config)
                normalizeConfig(target, owner)
                val file = Files.getFriendCenterFile(owner)
                Files.writeAtomically(JsonUtil.formatJson(target), file)
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "保存好友中心失败", e)
            false
        }
    }

    /** 修改必须在账号锁内重新读取，不能把 UI 的旧整份档案覆盖到磁盘。 */
    fun update(userId: String, change: (FriendCenterConfig) -> Unit): Boolean = try {
        Files.withAccountLock(userId) {
            val config = load(userId)
            change(config)
            save(userId, config)
        }
    } catch (e: Exception) {
        Log.printStackTrace(TAG, "更新好友中心失败，保留原配置", e)
        false
    }

    @JvmStatic
    fun current(userId: String? = UserMap.currentUid): FriendCenterConfig = load(userId)

    @JvmStatic
    fun mergeFromUserMap(
        userId: String? = UserMap.currentUid,
        previousUsers: Map<String, UserEntity> = emptyMap(),
        allowPruneMissing: Boolean = false,
        currentUsers: Map<String, UserEntity> = UserMap.getUserMap(userId),
        refreshRelations: Boolean = false
    ): FriendCenterConfig {
        val owner = userId?.trim().orEmpty()
        if (owner.isEmpty()) return FriendCenterConfig()
        val snapshot = currentUsers.toMap()
        return Files.withAccountLock(owner) {
            val config = load(owner)
            if (snapshot.isEmpty()) return@withAccountLock config
            if (allowPruneMissing) {
                (previousUsers.keys + config.profiles.keys).forEach { id ->
                    if (id.isNotBlank() && id != owner && id !in snapshot) {
                        config.profiles[id]?.takeIf {
                            it.relation != FriendRelation.UNKNOWN && it.relation != FriendRelation.SELF
                        }?.apply {
                            removed = true
                            relation = FriendRelation.REMOVED
                        }
                    }
                }
            }
            var changedRelations = 0
            snapshot.forEach { (id, entity) ->
                if (id.isBlank()) return@forEach
                val existing = config.profiles[id]
                val profile = existing ?: FriendProfile(userId = id)
                // 本地缓存仅补档案，不能覆盖已确认的关系；实时观察也不能用未知状态清除失效。
                if (existing == null || (refreshRelations && (id == owner || entity.friendStatus != null))) {
                    val before = profile.relation
                    applyUserEntity(profile, entity, owner)
                    if (before != profile.relation) changedRelations++
                }
                config.profiles[id] = profile
            }
            pruneGroups(config)
            check(save(owner, config)) { "friendCenter.json 保存失败" }
            Log.runtime(TAG, "好友中心刷新完成: input=${snapshot.size}, relationChanges=$changedRelations, profiles=${config.profiles.size}, groups=${config.groups.size}, saved=true")
            config
        }
    }

    @JvmStatic
    fun listProfiles(userId: String? = UserMap.currentUid): List<FriendProfile> {
        return current(userId).profiles.values.sortedWith(compareByDescending<FriendProfile> {
            it.globalPinned
        }.thenBy { it.displayName.ifBlank { it.userId } })
    }

    @JvmStatic
    fun relationOf(userId: String?): FriendRelation {
        val normalized = userId?.trim().orEmpty()
        if (normalized.isEmpty()) return FriendRelation.UNKNOWN
        if (normalized == UserMap.currentUid) return FriendRelation.SELF
        current().profiles[normalized]?.let { profile ->
            if (profile.removed || profile.relation == FriendRelation.REMOVED) {
                return FriendRelation.REMOVED
            }
            if (profile.relation != FriendRelation.UNKNOWN) {
                return profile.relation
            }
        }
        val userEntity = UserMap.get(normalized) ?: return FriendRelation.UNKNOWN
        return when {
            userEntity.friendStatus == MUTUAL_FRIEND_STATUS -> FriendRelation.MUTUAL
            userEntity.friendStatus != null -> FriendRelation.ONE_WAY
            else -> FriendRelation.UNKNOWN
        }
    }

    @JvmStatic
    fun isGlobalBlocked(userId: String?): Boolean {
        val normalized = userId?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        return current().profiles[normalized]?.globalBlocked == true
    }

    @JvmStatic
    fun markRemoved(ownerUserId: String?, friendUserId: String?): Boolean {
        val owner = ownerUserId?.trim().orEmpty()
        val friend = friendUserId?.trim().orEmpty()
        if (owner.isEmpty() || friend.isEmpty() || owner == friend) return false
        return update(owner) { config ->
            val profile = config.profiles.getOrPut(friend) { FriendProfile(userId = friend) }
            profile.removed = true
            profile.relation = FriendRelation.REMOVED
        }
    }

    @JvmStatic
    fun upsertGroup(userId: String?, group: FriendGroup): FriendGroup? {
        val owner = userId?.trim().orEmpty()
        if (owner.isEmpty()) return null
        val now = System.currentTimeMillis()
        val normalized = group.copy(
            id = group.id.trim().ifBlank { UUID.randomUUID().toString() },
            name = group.name.trim().ifBlank { "未命名分组" },
            memberIds = LinkedHashSet(group.memberIds.map { it.trim() }.filter { it.isNotEmpty() }),
            createdAt = if (group.createdAt > 0L) group.createdAt else now,
            updatedAt = now
        )
        return if (update(owner) { config ->
            val index = config.groups.indexOfFirst { it.id == normalized.id }
            if (index >= 0) config.groups[index] = normalized else config.groups.add(normalized)
        }) normalized else null
    }

    @JvmStatic
    fun deleteGroup(userId: String?, groupId: String): Boolean {
        val owner = userId?.trim().orEmpty()
        if (owner.isEmpty() || groupId.isBlank()) return false
        return update(owner) { config -> config.groups.removeAll { it.id == groupId.trim() } }
    }

    @JvmStatic
    fun recordCapability(
        ownerUserId: String?,
        friendUserId: String?,
        moduleKey: String,
        capability: FriendModuleCapability
    ): Boolean {
        val owner = ownerUserId?.trim().orEmpty()
        val friend = friendUserId?.trim().orEmpty()
        val module = moduleKey.trim()
        if (owner.isEmpty() || friend.isEmpty() || module.isEmpty()) return false
        return update(owner) { config ->
            val profile = config.profiles.getOrPut(friend) { FriendProfile(userId = friend) }
            if (!profile.removed && profile.relation != FriendRelation.REMOVED) {
                UserMap.getUserMap(owner)[friend]?.let { applyUserEntity(profile, it, owner) }
            }
            val previous = profile.capabilities[module]
            if (previous == null || previous.state != capability.state || previous.source != capability.source || previous.reason != capability.reason) {
                profile.capabilities[module] = capability
            }
        }
    }

    private fun normalizeConfig(config: FriendCenterConfig, userId: String) {
        config.schemaVersion = SCHEMA_VERSION
        config.userId = userId
        val normalizedProfiles = linkedMapOf<String, FriendProfile>()
        for ((rawId, rawProfile) in config.profiles) {
            val normalizedId = rawProfile.userId.ifBlank { rawId }.trim()
            if (normalizedId.isEmpty()) continue
            rawProfile.userId = normalizedId
            rawProfile.displayName = rawProfile.displayName.trim()
            when {
                normalizedId == userId -> {
                    rawProfile.removed = false
                    rawProfile.relation = FriendRelation.SELF
                }
                rawProfile.removed || rawProfile.relation == FriendRelation.REMOVED -> {
                    rawProfile.removed = true
                    rawProfile.relation = FriendRelation.REMOVED
                }
            }
            normalizedProfiles[normalizedId] = rawProfile
        }
        config.profiles = normalizedProfiles
        pruneGroups(config)
    }

    private fun copyConfig(config: FriendCenterConfig): FriendCenterConfig = config.copy(
        profiles = LinkedHashMap(config.profiles.mapValues { (_, profile) ->
            profile.copy(capabilities = LinkedHashMap(profile.capabilities.mapValues { it.value.copy() }))
        }),
        groups = config.groups.map { it.copy(memberIds = LinkedHashSet(it.memberIds)) }.toMutableList()
    )

    private fun applyUserEntity(profile: FriendProfile, entity: UserEntity, currentUserId: String) {
        val userId = entity.userId?.trim().orEmpty()
        profile.userId = userId
        profile.displayName = entity.showName.ifBlank { entity.fullName }.ifBlank { userId }
        profile.friendStatus = entity.friendStatus
        profile.removed = false
        profile.relation = when {
            userId == currentUserId -> FriendRelation.SELF
            entity.friendStatus == MUTUAL_FRIEND_STATUS -> FriendRelation.MUTUAL
            entity.friendStatus != null -> FriendRelation.ONE_WAY
            else -> FriendRelation.UNKNOWN
        }
    }

    private fun pruneGroups(config: FriendCenterConfig) {
        val knownIds = config.profiles.keys
        val normalizedGroups = mutableListOf<FriendGroup>()
        val seenGroupIds = linkedSetOf<String>()
        config.groups.forEach { group ->
            val normalizedGroupId = group.id.trim()
            if (normalizedGroupId.isEmpty()) {
                return@forEach
            }
            if (!seenGroupIds.add(normalizedGroupId)) {
                Log.runtime(TAG, "检测到重复分组ID[$normalizedGroupId]，仅保留首个分组")
                return@forEach
            }
            group.id = normalizedGroupId
            group.name = group.name.trim().ifBlank { "未命名分组" }
            group.memberIds = linkedSetOf<String>().apply {
                group.memberIds
                    .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                    .filter { knownIds.isEmpty() || knownIds.contains(it) }
                    .forEach { add(it) }
            }
            normalizedGroups.add(group)
        }
        config.groups.clear()
        config.groups.addAll(normalizedGroups)
    }
}

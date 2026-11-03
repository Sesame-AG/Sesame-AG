package io.github.aoguai.sesameag.util.maps

import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.util.Collections

/**
 * 用于管理和操作用户数据的映射关系。
 * 转换为 Kotlin object 单例模式。
 */
object UserMap {
    private val TAG = UserMap::class.java.simpleName

    @Volatile
    private var userSnapshot: Pair<String?, Map<String, UserEntity>> = null to emptyMap()
    private val userMap: Map<String, UserEntity>
        get() = getUserMap()

    /**
     * 当前用户ID
     * 使用 @JvmStatic 和 @JvmField 保持 Java 互操作性
     */
    @Volatile
    var currentUid: String? = null
        private set // 外部只能通过 setCurrentUserId 修改

    /**
     * 获取只读的用户信息映射
     */
    @JvmStatic
    fun getUserMap(userId: String? = currentUid): Map<String, UserEntity> {
        val snapshot = userSnapshot
        return if (snapshot.first == userId) snapshot.second else emptyMap()
    }

    @JvmStatic
    fun replace(userId: String, users: Map<String, UserEntity>) {
        userSnapshot = userId to Collections.unmodifiableMap(LinkedHashMap(users))
    }

    /**
     * 获取所有用户ID的集合
     */
    @JvmStatic
    fun getUserIdSet(): Set<String> {
        return userMap.keys
    }

    /**
     * 设置当前用户ID
     */
    @JvmStatic
    fun setCurrentUserId(userId: String?) {
        currentUid = if (userId.isNullOrEmpty()) null else userId
    }

    /**
     * 获取当前用户的掩码名称
     * 修复：如果 currentUid 为 null，直接返回 null，避免 ConcurrentHashMap 崩溃
     */
    @JvmStatic
    fun getCurrentMaskName(): String? {
        return getMaskName(currentUid)
    }

    /**
     * 获取指定用户的掩码名称
     * 修复：增加了 userId 判空检查
     */
    @JvmStatic
    fun getMaskName(userId: String?): String? {
        if (userId == null) {
            Log.record(TAG, "getMaskName: userId is null")
            return null
        } // 关键修复：防止 userMap.get(null) 崩溃
        return userMap[userId]?.maskName
    }

    /**
     * 获取指定用户实体
     */
    @JvmStatic
    fun get(userId: String?): UserEntity? {
        if (userId == null) return null // 关键修复
        return userMap[userId]
    }

    /**
     * 添加用户到映射
     */
    @JvmStatic
    fun add(userEntity: UserEntity?): Unit = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
        if (userEntity == null) return@transaction
        val uid = userEntity.userId
        if (!uid.isNullOrEmpty()) {
            currentUid?.let { replace(it, userMap + (uid to userEntity)) }
        }
    }

    /**
     * 从映射中移除指定用户
     */
    @JvmStatic
    fun remove(userId: String?): Unit = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
        if (userId != null) {
            currentUid?.let { replace(it, userMap - userId) }
        }
    }

    /**
     * 加载用户数据
     */
    @JvmStatic
    fun load(userId: String?): Map<String, UserEntity> {
        require(!userId.isNullOrBlank()) { "加载好友失败：账号为空" }
        return Files.withAccountLock(userId) {
            val file = requireNotNull(Files.getFriendIdMapFile(userId))
            val body = Files.readText(file)
            val users = linkedMapOf<String, UserEntity>()
            if (body.isNotBlank()) {
                val dtos = JsonUtil.parseObject(body, object : TypeReference<Map<String, UserEntity.UserDto>>() {})
                requireNotNull(dtos) { "好友缓存格式无效" }.values.forEach { dto ->
                    val id = dto.userId
                    require(!id.isNullOrBlank()) { "好友缓存包含空账号" }
                    users[id] = dto.toEntity()
                }
            }
            replace(userId, users)
            getUserMap(userId)
        }
    }

    /**
     * 卸载用户数据
     */
    @JvmStatic
    fun unload() {
        userSnapshot = null to emptyMap()
    }

    /**
     * 保存用户数据到文件
     */
    @JvmStatic
    fun save(userId: String?, users: Map<String, UserEntity> = getUserMap(userId)): Boolean {
        if (userId.isNullOrBlank()) return false
        return Files.withAccountLock(userId) {
            Files.writeAtomically(JsonUtil.formatJson(users), requireNotNull(Files.getFriendIdMapFile(userId)))
        }
    }

    /**
     * 加载当前用户的数据
     */
    @JvmStatic
    fun loadSelf(userId: String?) {
        if (userId.isNullOrEmpty()) return
        Files.withAccountLock(userId) {
            val entity = readStoredSnapshot(userId)
            replace(userId, entity?.let { mapOf(userId to it) }.orEmpty())
        }
    }

    @JvmStatic
    fun readSelf(userId: String?): UserEntity? {
        if (userId.isNullOrEmpty()) return null
        return Files.withAccountLock(userId) {
            getUserMap(userId)[userId] ?: readStoredSnapshot(userId)
        }
    }

    private fun readSelfFromFile(userId: String): UserEntity? {
        return try {
            val body = Files.readFromFile(Files.getSelfIdFile(userId)!!)
            if (body.isEmpty()) {
                null
            } else {
                JsonUtil.parseObject(
                    body,
                    object : TypeReference<UserEntity.UserDto>() {}
                )?.toEntity()
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
            null
        }
    }

    private fun readFriendEntityFromFile(userId: String): UserEntity? {
        return try {
            val body = Files.readFromFile(Files.getFriendIdMapFile(userId)!!)
            if (body.isEmpty()) {
                null
            } else {
                val dtoMap: Map<String, UserEntity.UserDto>? = JsonUtil.parseObject(
                    body,
                    object : TypeReference<Map<String, UserEntity.UserDto>>() {}
                )
                val dto = dtoMap?.get(userId) ?: dtoMap?.values?.firstOrNull { it.userId == userId }
                dto?.toEntity()
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
            null
        }
    }

    private fun readStoredSnapshot(userId: String): UserEntity? {
        val selfEntity = readSelfFromFile(userId)
        val friendEntity = readFriendEntityFromFile(userId)
        if (selfEntity == null && friendEntity == null) {
            return null
        }
        return mergeUserEntity(userId, selfEntity, friendEntity)
    }

    private fun normalizedText(value: String?): String? {
        return value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun mergeUserEntity(userId: String, primary: UserEntity?, secondary: UserEntity? = null): UserEntity {
        val safeUserId = userId.trim()
        require(safeUserId.isNotEmpty()) { "userId must not be blank" }

        val primaryEntity = primary?.takeIf { it.userId?.trim() == safeUserId }
        val secondaryEntity = secondary?.takeIf { it.userId?.trim() == safeUserId }

        val realName = normalizedText(primaryEntity?.realName)
            ?: normalizedText(secondaryEntity?.realName)
        val remarkName = normalizedText(primaryEntity?.remarkName)
            ?: normalizedText(secondaryEntity?.remarkName)
        val nickName = normalizedText(primaryEntity?.nickName)
            ?: normalizedText(secondaryEntity?.nickName)
            ?: remarkName
            ?: realName
            ?: safeUserId

        return UserEntity(
            userId = safeUserId,
            account = normalizedText(primaryEntity?.account)
                ?: normalizedText(secondaryEntity?.account),
            friendStatus = primaryEntity?.friendStatus ?: secondaryEntity?.friendStatus,
            realName = realName,
            nickName = nickName,
            remarkName = remarkName
        )
    }

    @JvmStatic
    fun saveMinimalSelf(userId: String) {
        val safeUserId = userId.trim()
        if (safeUserId.isEmpty()) return
        val entity = mergeUserEntity(
            safeUserId,
            userMap[safeUserId],
            readStoredSnapshot(safeUserId)
        )
        saveSelf(entity)
    }

    /**
     * 保存当前用户数据到文件
     */
    @JvmStatic
    fun saveSelf(userEntity: UserEntity?): Boolean {
        val entity = userEntity ?: return false
        val safeUserId = entity.userId?.trim().orEmpty()
        if (safeUserId.isEmpty()) return false
        return Files.withAccountLock(safeUserId) {
            val existing = mergeUserEntity(safeUserId, getUserMap(safeUserId)[safeUserId], readStoredSnapshot(safeUserId))
            val mergedEntity = mergeUserEntity(safeUserId, entity, existing)
            val saved = Files.writeAtomically(JsonUtil.formatJson(mergedEntity), requireNotNull(Files.getSelfIdFile(safeUserId)))
            if (saved && currentUid == safeUserId) {
                updateActiveUser(mergedEntity)
            } else if (!saved) {
                Log.record(TAG, "self_snapshot_write_failed")
            }
            saved
        }
    }

    @JvmStatic
    fun updateActiveUser(userEntity: UserEntity?): Unit = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
        val entity = userEntity ?: return@transaction
        val safeUserId = entity.userId?.trim().orEmpty()
        if (safeUserId.isEmpty()) return@transaction
        if (currentUid != safeUserId) return@transaction
        replace(safeUserId, userMap + (safeUserId to entity))
        DataStore.put("activedUser", entity)
        Log.record(TAG, "update now active user: $entity")
    }

    @JvmStatic
    fun ensureActiveUserSnapshot(userId: String, fallback: UserEntity? = null): UserEntity {
        val safeUserId = userId.trim()
        require(safeUserId.isNotEmpty()) { "userId must not be blank" }
        val existing = mergeUserEntity(
            safeUserId,
            userMap[safeUserId],
            readStoredSnapshot(safeUserId)
        )
        val entity = mergeUserEntity(safeUserId, fallback, existing)
        saveSelf(entity)
        return entity
    }
}


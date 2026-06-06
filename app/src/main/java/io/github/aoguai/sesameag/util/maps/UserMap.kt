package io.github.aoguai.sesameag.util.maps

import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.entity.UserEntity
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 用于管理和操作用户数据的映射关系。
 * 转换为 Kotlin object 单例模式。
 */
object UserMap {
    private val TAG = UserMap::class.java.simpleName

    // 存储用户信息的线程安全映射
    // ConcurrentHashMap 不允许 key 或 value 为 null
    private val userMap = ConcurrentHashMap<String, UserEntity>()

    /**
     * 当前用户ID
     * 使用 @JvmStatic 和 @JvmField 保持 Java 互操作性
     */
    var currentUid: String? = null
        private set // 外部只能通过 setCurrentUserId 修改

    /**
     * 获取只读的用户信息映射
     */
    @JvmStatic
    fun getUserMap(): Map<String, UserEntity> {
        return Collections.unmodifiableMap(userMap)
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
    @Synchronized
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
    @Synchronized
    fun add(userEntity: UserEntity?) {
        if (userEntity == null) return
        val uid = userEntity.userId
        if (!uid.isNullOrEmpty()) {
            userMap[uid] = userEntity
        }
    }

    /**
     * 从映射中移除指定用户
     */
    @JvmStatic
    @Synchronized
    fun remove(userId: String?) {
        if (userId != null) {
            userMap.remove(userId)
        }
    }

    /**
     * 加载用户数据
     */
    @JvmStatic
    @Synchronized
    fun load(userId: String?) {
        userMap.clear()
        if (userId.isNullOrEmpty()) {
            Log.error(TAG, "Skip loading user map for empty userId")
            return
        }
        try {
            val friendIdMapFile = Files.getFriendIdMapFile(userId)
            if (friendIdMapFile == null) {
                Log.error(TAG, "Friend ID map file is null for userId: $userId")
                return
            }
            val body = Files.readFromFile(friendIdMapFile)
            if (body.isNotEmpty()) {
                // Kotlin 中使用 TypeReference 的方式
                val dtoMap: Map<String, UserEntity.UserDto>? = JsonUtil.parseObject(
                    body,
                    object : TypeReference<Map<String, UserEntity.UserDto>>() {}
                )

                dtoMap?.values?.forEach { dto ->
                    // 再次确保 Key 和 Value 不为 null
                    val uid = dto.userId
                    val entity = dto.toEntity()
                    if (!uid.isNullOrEmpty()) {
                        userMap[uid] = entity
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    /**
     * 卸载用户数据
     */
    @JvmStatic
    @Synchronized
    fun unload() {
        userMap.clear()
    }

    /**
     * 保存用户数据到文件
     */
    @JvmStatic
    @Synchronized
    fun save(userId: String?): Boolean {
        if (userId.isNullOrEmpty()) return false
        return Files.write2File(JsonUtil.formatJson(userMap), Files.getFriendIdMapFile(userId)!!)
    }

    /**
     * 加载当前用户的数据
     */
    @JvmStatic
    @Synchronized
    fun loadSelf(userId: String?) {
        userMap.clear()
        if (userId.isNullOrEmpty()) return

        val entity = readStoredSnapshot(userId)
        if (entity != null) {
            userMap[userId] = entity
        }
    }

    @JvmStatic
    @Synchronized
    fun readSelf(userId: String?): UserEntity? {
        if (userId.isNullOrEmpty()) return null
        val cached = userMap[userId]
        if (cached != null) {
            return cached
        }
        return readStoredSnapshot(userId)
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
    @Synchronized
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
    @Synchronized
    fun saveSelf(userEntity: UserEntity?) {
        val entity = userEntity ?: return
        val safeUserId = entity.userId?.trim().orEmpty()
        if (safeUserId.isEmpty()) return
        val existing = mergeUserEntity(
            safeUserId,
            userMap[safeUserId],
            readStoredSnapshot(safeUserId)
        )
        val mergedEntity = mergeUserEntity(safeUserId, entity, existing)
        updateActiveUser(mergedEntity)
        val body = JsonUtil.formatJson(mergedEntity)
        Files.write2File(body, Files.getSelfIdFile(safeUserId)!!)
    }

    @JvmStatic
    @Synchronized
    fun updateActiveUser(userEntity: UserEntity?) {
        val entity = userEntity ?: return
        val safeUserId = entity.userId?.trim().orEmpty()
        if (safeUserId.isEmpty()) return
        userMap[safeUserId] = entity
        DataStore.put("activedUser", entity)
        Log.record(TAG, "update now active user: $entity")
    }

    @JvmStatic
    @Synchronized
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


package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.entity.friend.FriendRelation
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.UserMap

/**
 * 好友关系守卫：
 * - 排除当前账号自己
 * - 排除单向好友/已失效好友
 */
object FriendGuard {
    const val MUTUAL_FRIEND_STATUS = 1

    @JvmStatic
    fun normalizeUserId(userId: String?): String? {
        val normalized = userId?.trim().orEmpty()
        return normalized.ifEmpty { null }
    }

    @JvmStatic
    fun isSelf(userId: String?): Boolean {
        val normalized = normalizeUserId(userId) ?: return false
        return normalized == UserMap.currentUid
    }

    @JvmStatic
    fun isMutualFriend(userId: String?): Boolean {
        val normalized = normalizeUserId(userId) ?: return false
        if (normalized == UserMap.currentUid) {
            return false
        }
        return FriendRepository.relationOf(normalized) == FriendRelation.MUTUAL
    }

    @JvmStatic
    fun shouldSkipFriend(
        userId: String?,
        logTag: String,
        sceneName: String,
        skipSelf: Boolean = true
    ): Boolean {
        val normalized = normalizeUserId(userId)
        if (normalized == null) {
            Log.record(logTag, "$sceneName 跳过：userId为空")
            return true
        }
        if (skipSelf && normalized == UserMap.currentUid) {
            Log.record(logTag, "$sceneName 跳过自己账号[$normalized]")
            return true
        }
        val relation = FriendRepository.relationOf(normalized)
        if (relation == FriendRelation.UNKNOWN) {
            Log.record(logTag, "$sceneName 跳过[$normalized]：好友信息不存在")
            return true
        }
        if (FriendRepository.isGlobalBlocked(normalized)) {
            val maskName = UserMap.getMaskName(normalized) ?: normalized
            Log.record(logTag, "$sceneName 跳过[$maskName]：好友中心全局黑名单")
            return true
        }
        if (relation != FriendRelation.MUTUAL) {
            val maskName = UserMap.getMaskName(normalized) ?: normalized
            Log.record(
                logTag,
                "$sceneName 跳过[$maskName]：单向好友或已失效(relation=$relation)"
            )
            return true
        }
        return false
    }
}

package io.github.aoguai.sesameag.util.friend

import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.entity.friend.FriendModuleCapability
import io.github.aoguai.sesameag.util.maps.UserMap

object FriendCapabilityRecorder {
    @JvmStatic
    fun record(
        friendUserId: String?,
        moduleKey: String,
        state: FriendCapabilityState,
        source: String,
        reason: String = "",
        ownerUserId: String? = UserMap.currentUid
    ): Boolean {
        return FriendRepository.recordCapability(
            ownerUserId = ownerUserId,
            friendUserId = friendUserId,
            moduleKey = moduleKey,
            capability = FriendModuleCapability(
                state = state,
                source = source,
                reason = reason,
                observedAt = System.currentTimeMillis()
            )
        )
    }
}


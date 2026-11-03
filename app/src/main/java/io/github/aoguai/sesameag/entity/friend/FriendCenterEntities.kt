package io.github.aoguai.sesameag.entity.friend

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.io.Serializable

enum class FriendRelation {
    SELF,
    MUTUAL,
    ONE_WAY,
    REMOVED,
    UNKNOWN,
}

enum class FriendRelationFilter {
    MUTUAL_ONLY,
    ALL_KNOWN,
    INCLUDE_SELF,
}

enum class FriendSelectionScope {
    EXPLICIT,
    ALL_FRIENDS,
    ;

    companion object {
        /**
         * Selection data is user-editable JSON; treating an unrecognized future value as explicit
         * avoids accidentally broadening a task to every friend after a downgrade or malformed import.
         */
        @JvmStatic
        @com.fasterxml.jackson.annotation.JsonCreator
        fun fromJson(rawValue: String?): FriendSelectionScope =
            entries.firstOrNull { it.name.equals(rawValue?.trim(), ignoreCase = true) }
                ?: EXPLICIT
    }
}

enum class FriendCapabilityState {
    UNKNOWN,
    OPEN,
    NOT_OPEN,
    UNAVAILABLE,
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class FriendCapabilityFilter(
    var moduleKeys: LinkedHashSet<String> = linkedSetOf(),
    var requiredStates: LinkedHashSet<FriendCapabilityState> = linkedSetOf(FriendCapabilityState.OPEN),
    var includeUnknown: Boolean = true,
) : Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
data class FriendModuleCapability(
    var state: FriendCapabilityState = FriendCapabilityState.UNKNOWN,
    var source: String = "",
    var reason: String = "",
    var observedAt: Long = 0L,
) : Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
data class FriendProfile(
    var userId: String = "",
    var displayName: String = "",
    var friendStatus: Int? = null,
    var relation: FriendRelation = FriendRelation.UNKNOWN,
    var globalBlocked: Boolean = false,
    var globalPinned: Boolean = false,
    var removed: Boolean = false,
    var capabilities: LinkedHashMap<String, FriendModuleCapability> = linkedMapOf(),
) : Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
data class FriendGroup(
    var id: String = "",
    var name: String = "",
    var memberIds: LinkedHashSet<String> = linkedSetOf(),
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
) : Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
data class FriendCenterConfig(
    var schemaVersion: Int = 1,
    var userId: String = "",
    var groups: MutableList<FriendGroup> = mutableListOf(),
    var profiles: LinkedHashMap<String, FriendProfile> = linkedMapOf(),
) : Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
data class FriendSelectionSpec(
    var selectionScope: FriendSelectionScope = FriendSelectionScope.EXPLICIT,
    var includeUserIds: LinkedHashSet<String> = linkedSetOf(),
    var includeGroupIds: LinkedHashSet<String> = linkedSetOf(),
    var excludeUserIds: LinkedHashSet<String> = linkedSetOf(),
    var excludeGroupIds: LinkedHashSet<String> = linkedSetOf(),
    var relationFilter: FriendRelationFilter = FriendRelationFilter.MUTUAL_ONLY,
    var capabilityFilter: FriendCapabilityFilter? = null,
) : Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
data class FriendSelectionCountSpec(
    var selection: FriendSelectionSpec = FriendSelectionSpec(),
    var defaultCount: Int = 1,
    var groupCountOverrides: LinkedHashMap<String, Int> = linkedMapOf(),
    var userCountOverrides: LinkedHashMap<String, Int> = linkedMapOf(),
) : Serializable

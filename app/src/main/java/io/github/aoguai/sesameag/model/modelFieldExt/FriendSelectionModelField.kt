package io.github.aoguai.sesameag.model.modelFieldExt

import io.github.aoguai.sesameag.entity.friend.FriendCapabilityFilter
import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.entity.friend.FriendCenterConfig
import io.github.aoguai.sesameag.entity.friend.FriendRelationFilter
import io.github.aoguai.sesameag.entity.friend.FriendSelectionCountSpec
import io.github.aoguai.sesameag.entity.friend.FriendSelectionScope
import io.github.aoguai.sesameag.entity.friend.FriendSelectionSpec
import io.github.aoguai.sesameag.model.ConfigPortScope
import io.github.aoguai.sesameag.model.ModelField
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.SettingsFieldAudit
import io.github.aoguai.sesameag.util.SettingsFieldAuditRegistry
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.friend.FriendSelectionResolver
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject

open class FriendSelectionModelField(
    code: String,
    name: String,
    value: FriendSelectionSpec = FriendSelectionSpec(),
) : ModelField<FriendSelectionSpec>(code, name, value) {
    init {
        configPortScope = ConfigPortScope.ACCOUNT_PRIVATE
    }

    override fun getType(): String = "FRIEND_SELECTION"

    override fun getEditorMeta(): Any = FriendSelectionEditorMeta(countEnabled = false)

    override fun setObjectValue(objectValue: Any?) {
        clearAudit()
        value = normalizeSpec(objectValue)
    }

    override fun setConfigValue(configValue: String?) {
        if (configValue.isNullOrBlank()) {
            clearAudit()
            reset()
            return
        }
        value =
            try {
                clearAudit()
                sanitizeSpec(JsonUtil.parseObject(configValue, FriendSelectionSpec::class.java))
            } catch (t: Throwable) {
                reportInvalidConfig(
                    configValue,
                    "好友选择配置[$code]已失效，已重置为空；请在好友中心重新选择",
                )
                FriendSelectionSpec()
            }
    }

    override fun getConfigValue(): String? {
        value = value?.let { sanitizeSpec(it) }
        return super.getConfigValue()
    }

    fun contains(userId: String?): Boolean = FriendSelectionResolver.contains(value, userId)

    fun containsConfigured(userId: String?): Boolean = FriendSelectionResolver.containsConfigured(value, userId)

    fun resolvedIds(): Set<String> = FriendSelectionResolver.resolveIds(value)

    fun isEmpty(): Boolean = resolvedIds().isEmpty()

    fun sanitizeForUser(userId: String?) {
        sanitizeForUser(userId, null)
    }

    fun sanitizeForUser(
        userId: String?,
        capabilityModuleKey: String?,
    ) {
        value = value?.let { sanitizeSpec(it, userId, capabilityModuleKey) }
    }

    private fun normalizeSpec(raw: Any?): FriendSelectionSpec =
        sanitizeSpec(
            when (raw) {
                null -> {
                    FriendSelectionSpec()
                }

                is FriendSelectionSpec -> {
                    raw
                }

                else -> {
                    try {
                        clearAudit()
                        JsonUtil.parseObject(raw, FriendSelectionSpec::class.java)
                    } catch (_: Throwable) {
                        reportInvalidObjectValue("好友选择配置[$code]格式无效，已重置为空；请在好友中心重新选择")
                        FriendSelectionSpec()
                    }
                }
            },
        )

    private fun clearAudit() {
        SettingsFieldAuditRegistry.clear(UserMap.currentUid, code)
    }

    private fun reportInvalidObjectValue(message: String) {
        clearAudit()
        val clearValue = JsonUtil.formatJson(FriendSelectionSpec(), false)
        SettingsFieldAuditRegistry.save(
            UserMap.currentUid,
            code,
            SettingsFieldAudit(
                title = "历史好友配置已失效",
                message = message,
                staleCount = 1,
                clearValue = clearValue,
            ),
        )
        Log.runtime("FriendSelectionModelField", message)
        Log.record("FriendSelectionModelField", message)
    }

    private fun reportInvalidConfig(
        rawConfig: String,
        genericMessage: String,
    ) {
        clearAudit()
        val staleCount = estimateLegacySelectionCount(rawConfig)
        val legacyMessage =
            if (looksLikeLegacyFriendSelectionConfig(rawConfig, FRIEND_SELECTION_SPEC_KEYS)) {
                "检测到旧版好友数组/Map配置[$code]，当前版本已改为好友中心规则；旧配置不会继续生效，请重新选择"
            } else {
                genericMessage
            }
        SettingsFieldAuditRegistry.save(
            UserMap.currentUid,
            code,
            SettingsFieldAudit(
                title = "历史好友配置已失效",
                message = legacyMessage,
                staleCount = staleCount,
                clearValue = JsonUtil.formatJson(FriendSelectionSpec(), false),
            ),
        )
        Log.runtime("FriendSelectionModelField", legacyMessage)
        Log.record("FriendSelectionModelField", legacyMessage)
    }
}

class FriendSelectionCountModelField(
    code: String,
    name: String,
    value: FriendSelectionCountSpec = FriendSelectionCountSpec(),
) : ModelField<FriendSelectionCountSpec>(code, name, value) {
    init {
        configPortScope = ConfigPortScope.ACCOUNT_PRIVATE
    }

    override fun getType(): String = "FRIEND_SELECTION_COUNT"

    override fun getEditorMeta(): Any = FriendSelectionEditorMeta(countEnabled = true)

    override fun setObjectValue(objectValue: Any?) {
        clearAudit()
        value = normalizeSpec(objectValue)
    }

    override fun setConfigValue(configValue: String?) {
        if (configValue.isNullOrBlank()) {
            clearAudit()
            reset()
            return
        }
        value =
            try {
                clearAudit()
                sanitizeCountSpec(JsonUtil.parseObject(configValue, FriendSelectionCountSpec::class.java))
            } catch (t: Throwable) {
                reportInvalidConfig(
                    configValue,
                    "好友计数选择配置[$code]已失效，已重置为空；请在好友中心重新设置",
                )
                FriendSelectionCountSpec()
            }
    }

    override fun getConfigValue(): String? {
        value = value?.let { sanitizeCountSpec(it) }
        return super.getConfigValue()
    }

    fun contains(userId: String?): Boolean = FriendSelectionResolver.resolveCountMap(value).containsKey(userId?.trim())

    fun resolvedCountMap(): Map<String, Int> = FriendSelectionResolver.resolveCountMap(value)

    fun isEmpty(): Boolean = resolvedCountMap().isEmpty()

    fun sanitizeForUser(userId: String?) {
        sanitizeForUser(userId, null)
    }

    fun sanitizeForUser(
        userId: String?,
        capabilityModuleKey: String?,
    ) {
        value = value?.let { sanitizeCountSpec(it, userId, capabilityModuleKey) }
    }

    private fun normalizeSpec(raw: Any?): FriendSelectionCountSpec =
        sanitizeCountSpec(
            when (raw) {
                null -> {
                    FriendSelectionCountSpec()
                }

                is FriendSelectionCountSpec -> {
                    raw
                }

                else -> {
                    try {
                        clearAudit()
                        JsonUtil.parseObject(raw, FriendSelectionCountSpec::class.java)
                    } catch (_: Throwable) {
                        reportInvalidObjectValue("好友计数选择配置[$code]格式无效，已重置为空；请在好友中心重新设置")
                        FriendSelectionCountSpec()
                    }
                }
            },
        )

    private fun clearAudit() {
        SettingsFieldAuditRegistry.clear(UserMap.currentUid, code)
    }

    private fun reportInvalidObjectValue(message: String) {
        clearAudit()
        val clearValue = JsonUtil.formatJson(FriendSelectionCountSpec(), false)
        SettingsFieldAuditRegistry.save(
            UserMap.currentUid,
            code,
            SettingsFieldAudit(
                title = "历史好友配置已失效",
                message = message,
                staleCount = 1,
                clearValue = clearValue,
            ),
        )
        Log.runtime("FriendSelectionCountModelField", message)
        Log.record("FriendSelectionCountModelField", message)
    }

    private fun reportInvalidConfig(
        rawConfig: String,
        genericMessage: String,
    ) {
        clearAudit()
        val staleCount = estimateLegacySelectionCount(rawConfig)
        val legacyMessage =
            if (looksLikeLegacyFriendSelectionConfig(rawConfig, FRIEND_SELECTION_COUNT_SPEC_KEYS)) {
                "检测到旧版好友数组/Map配置[$code]，当前版本已改为好友中心规则；旧配置不会继续生效，请重新设置"
            } else {
                genericMessage
            }
        SettingsFieldAuditRegistry.save(
            UserMap.currentUid,
            code,
            SettingsFieldAudit(
                title = "历史好友配置已失效",
                message = legacyMessage,
                staleCount = staleCount,
                clearValue = JsonUtil.formatJson(FriendSelectionCountSpec(), false),
            ),
        )
        Log.runtime("FriendSelectionCountModelField", legacyMessage)
        Log.record("FriendSelectionCountModelField", legacyMessage)
    }
}

data class FriendSelectionEditorMeta(
    val kind: String = "FRIEND_SELECTION",
    val countEnabled: Boolean,
    val relationDefault: FriendRelationFilter = FriendRelationFilter.MUTUAL_ONLY,
)

private val MANDATORY_CAPABILITY_MODULE_KEYS =
    linkedSetOf(
        "FARM",
        "FOREST",
        "STALL",
        "DODO",
        "OCEAN",
        "SPORTS",
    )

private val FRIEND_SELECTION_SPEC_KEYS =
    linkedSetOf(
        "selectionScope",
        "includeUserIds",
        "includeGroupIds",
        "excludeUserIds",
        "excludeGroupIds",
        "relationFilter",
        "capabilityFilter",
    )

private val FRIEND_SELECTION_COUNT_SPEC_KEYS =
    linkedSetOf(
        "selection",
        "defaultCount",
        "groupCountOverrides",
        "userCountOverrides",
    )

private fun sanitizeSpec(
    spec: FriendSelectionSpec,
    userId: String? = null,
    capabilityModuleKey: String? = null,
): FriendSelectionSpec {
    val friendConfig = resolveFriendConfig(userId)
    val knownUserIds = friendConfig?.profiles?.keys.orEmpty()
    val knownGroupIds = friendConfig?.groups?.mapTo(linkedSetOf<String>()) { it.id }.orEmpty()
    val shouldPruneUsers = friendConfig?.userId?.isNotBlank() == true && knownUserIds.isNotEmpty()
    val shouldPruneGroups = friendConfig?.userId?.isNotBlank() == true
    val normalizedCapabilityKey =
        capabilityModuleKey
            ?.trim()
            ?.uppercase()
            ?.takeIf { MANDATORY_CAPABILITY_MODULE_KEYS.contains(it) }
    val mandatoryCapabilityFilter =
        normalizedCapabilityKey?.let { moduleKey ->
            FriendCapabilityFilter(
                moduleKeys = linkedSetOf(moduleKey),
                requiredStates = linkedSetOf(FriendCapabilityState.OPEN),
                includeUnknown = true,
            )
        }
    return FriendSelectionSpec(
        selectionScope = spec.selectionScope,
        includeUserIds = sanitizeStringSet(spec.includeUserIds, knownUserIds.takeIf { shouldPruneUsers }),
        includeGroupIds = sanitizeStringSet(spec.includeGroupIds, knownGroupIds.takeIf { shouldPruneGroups }),
        excludeUserIds = sanitizeStringSet(spec.excludeUserIds, knownUserIds.takeIf { shouldPruneUsers }),
        excludeGroupIds = sanitizeStringSet(spec.excludeGroupIds, knownGroupIds.takeIf { shouldPruneGroups }),
        relationFilter = spec.relationFilter,
        capabilityFilter = mandatoryCapabilityFilter,
    )
}

private fun sanitizeCountSpec(
    spec: FriendSelectionCountSpec,
    userId: String? = null,
    capabilityModuleKey: String? = null,
): FriendSelectionCountSpec {
    val selection = sanitizeSpec(spec.selection, userId, capabilityModuleKey)
    val friendConfig = resolveFriendConfig(userId)
    val canResolveGroups = friendConfig?.userId?.isNotBlank() == true
    val groups = if (canResolveGroups) friendConfig?.groups.orEmpty() else emptyList()
    val validUserIds =
        linkedSetOf<String>().apply {
            if (selection.selectionScope == FriendSelectionScope.ALL_FRIENDS && canResolveGroups) {
                addAll(friendConfig?.profiles?.keys.orEmpty())
            } else {
                addAll(selection.includeUserIds)
            }
            if (canResolveGroups) {
                selection.includeGroupIds.forEach { groupId ->
                    groups.firstOrNull { it.id == groupId }?.memberIds?.let { addAll(it) }
                }
            }
        }
    val validGroupIds = selection.includeGroupIds.toSet()
    return FriendSelectionCountSpec(
        selection = selection,
        defaultCount = spec.defaultCount,
        groupCountOverrides =
            sanitizeCountMap(
                spec.groupCountOverrides,
                validGroupIds.takeIf { canResolveGroups },
            ),
        userCountOverrides =
            sanitizeCountMap(
                spec.userCountOverrides,
                validUserIds.takeIf { canResolveGroups },
            ),
    )
}

private fun sanitizeStringSet(
    values: Set<String?>?,
    allowedValues: Set<String>? = null,
): LinkedHashSet<String> =
    linkedSetOf<String>().apply {
        values.orEmpty().mapNotNullTo(this) { rawValue ->
            rawValue
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.takeIf { allowedValues == null || allowedValues.contains(it) }
        }
    }

private fun sanitizeCountMap(
    values: Map<*, *>?,
    allowedKeys: Set<String>?,
): LinkedHashMap<String, Int> =
    linkedMapOf<String, Int>().apply {
        values.orEmpty().forEach { (rawKey, rawCount) ->
            val key = rawKey?.toString()?.trim().orEmpty()
            val count =
                when (rawCount) {
                    is Number -> rawCount.toInt()
                    is String -> rawCount.trim().toIntOrNull()
                    else -> null
                }
            if (key.isNotEmpty() && count != null && (allowedKeys == null || allowedKeys.contains(key))) {
                put(key, count)
            }
        }
    }

private fun resolveFriendConfig(userId: String?): FriendCenterConfig? {
    val safeUserId = userId?.trim().orEmpty()
    if (safeUserId.isEmpty()) {
        return null
    }
    return runCatching {
        FriendRepository.current(safeUserId)
    }.getOrNull()
}

private fun estimateLegacySelectionCount(rawConfig: String?): Int {
    val normalized = rawConfig?.trim().orEmpty()
    if (normalized.isEmpty()) {
        return 0
    }
    return runCatching { JSONArray(normalized).length() }
        .getOrElse {
            runCatching { JSONObject(normalized).length() }.getOrDefault(1)
        }.coerceAtLeast(1)
}

private fun looksLikeLegacyFriendSelectionConfig(
    rawConfig: String?,
    knownKeys: Set<String>,
): Boolean {
    val normalized = rawConfig?.trim().orEmpty()
    if (normalized.startsWith("[")) {
        return true
    }
    if (!normalized.startsWith("{")) {
        return false
    }
    return runCatching {
        val jsonObject = JSONObject(normalized)
        if (jsonObject.length() == 0) {
            false
        } else {
            jsonObject.keys().asSequence().none { knownKeys.contains(it) }
        }
    }.getOrDefault(false)
}

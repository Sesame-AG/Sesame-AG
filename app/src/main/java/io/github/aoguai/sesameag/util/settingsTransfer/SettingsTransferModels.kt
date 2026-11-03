package io.github.aoguai.sesameag.util.settingsTransfer

import io.github.aoguai.sesameag.entity.friend.FriendCenterConfig

enum class SettingsTransferExportMode {
    SHARE,
    BACKUP;

    fun displayName(): String {
        return when (this) {
            SHARE -> "分享通用配置"
            BACKUP -> "完整备份"
        }
    }
}

enum class SettingsTransferSourceFormat {
    PACKAGE_V1,
    LEGACY_CONFIG_V2
}

data class ConfigTransferSnapshot(
    var modelFields: LinkedHashMap<String, LinkedHashMap<String, String?>> = linkedMapOf()
) {
    fun fieldCount(): Int = modelFields.values.sumOf { it.size }

    fun isEmpty(): Boolean = modelFields.isEmpty() || fieldCount() <= 0
}

data class SettingsTransferCacheEntry(
    var key: String = "",
    var fileName: String = "",
    var content: String = ""
)

data class SettingsTransferPackageV1(
    var schemaVersion: Int = SettingsTransferCodec.SCHEMA_VERSION,
    var exportMode: SettingsTransferExportMode = SettingsTransferExportMode.SHARE,
    var createdAt: Long = System.currentTimeMillis(),
    var appVersion: String = "",
    var sourceAccountFingerprint: String? = null,
    var genericConfig: ConfigTransferSnapshot = ConfigTransferSnapshot(),
    var customSettings: LinkedHashMap<String, Any?>? = null,
    var genericCaches: MutableList<SettingsTransferCacheEntry> = mutableListOf(),
    var privateConfig: ConfigTransferSnapshot? = null,
    var privateCaches: MutableList<SettingsTransferCacheEntry> = mutableListOf(),
    var friendCenter: FriendCenterConfig? = null
)

data class IpChouChouLeLegacyCacheSnapshot(
    var shopItems: LinkedHashMap<String, String> = linkedMapOf()
)

data class ResolvedSettingsImport(
    val format: SettingsTransferSourceFormat,
    val exportMode: SettingsTransferExportMode,
    val schemaVersion: Int?,
    val genericConfig: ConfigTransferSnapshot,
    val customSettings: LinkedHashMap<String, Any?>?,
    val genericCaches: List<SettingsTransferCacheEntry>,
    val privateConfig: ConfigTransferSnapshot?,
    val privateCaches: List<SettingsTransferCacheEntry>,
    val friendCenter: FriendCenterConfig?,
    val canApplyPrivate: Boolean
)

data class SettingsTransferApplyResult(
    val success: Boolean,
    val message: String
)

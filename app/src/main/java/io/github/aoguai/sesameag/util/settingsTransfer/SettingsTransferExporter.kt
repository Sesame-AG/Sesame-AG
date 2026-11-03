package io.github.aoguai.sesameag.util.settingsTransfer

import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.BuildConfig
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.entity.friend.FriendCenterConfig
import io.github.aoguai.sesameag.model.ConfigPortScope
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.friend.FriendRepository
import java.io.File

object SettingsTransferExporter {
    fun exportJson(userId: String?, exportMode: SettingsTransferExportMode): String {
        val snapshot = Config.withLoadedAccount(userId) {
            if (userId.isNullOrBlank()) buildPackage(null, exportMode)
            else Files.withAccountLock(userId) { buildPackage(userId, exportMode) }
        }
        return SettingsTransferCodec.encodePackage(snapshot)
    }

    fun snapshotCurrentConfig(scope: ConfigPortScope): ConfigTransferSnapshot {
        val snapshot = ConfigTransferSnapshot()
        Model.getModelConfigMap().forEach { (modelCode, modelConfig) ->
            val fieldMap = linkedMapOf<String, String?>()
            modelConfig.fields.values.forEach { field ->
                if (field.configPortScope != scope) {
                    return@forEach
                }
                field.getConfigValue()?.let {
                    fieldMap[field.code] = it
                }
            }
            if (fieldMap.isNotEmpty()) {
                snapshot.modelFields[modelCode] = LinkedHashMap(fieldMap)
            }
        }
        return snapshot
    }

    private fun buildPackage(userId: String?, exportMode: SettingsTransferExportMode): SettingsTransferPackageV1 {
        val safeUserId = userId?.trim().orEmpty()
        val canExportPrivate = exportMode == SettingsTransferExportMode.BACKUP && safeUserId.isNotEmpty()
        return SettingsTransferPackageV1(
            schemaVersion = SettingsTransferCodec.SCHEMA_VERSION,
            exportMode = exportMode,
            createdAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            sourceAccountFingerprint = if (canExportPrivate) SettingsTransferCodec.fingerprintUser(safeUserId) else null,
            genericConfig = snapshotCurrentConfig(ConfigPortScope.GENERIC),
            customSettings = readCustomSettings(safeUserId),
            genericCaches = SettingsTransferCacheRegistry.exportCaches(safeUserId, ConfigPortScope.GENERIC).toMutableList(),
            privateConfig = if (canExportPrivate) snapshotCurrentConfig(ConfigPortScope.ACCOUNT_PRIVATE) else null,
            privateCaches = if (canExportPrivate) {
                SettingsTransferCacheRegistry.exportCaches(safeUserId, ConfigPortScope.ACCOUNT_PRIVATE).toMutableList()
            } else {
                mutableListOf()
            },
            friendCenter = if (canExportPrivate) copyFriendCenter(safeUserId) else null
        )
    }

    private fun readCustomSettings(userId: String): LinkedHashMap<String, Any?>? {
        if (userId.isBlank()) {
            return null
        }
        val file = Files.child(Files.getUserConfigDir(userId), "customset.json")
        val body = if (file.exists()) Files.readText(file) else ""
        if (body.isBlank()) {
            return linkedMapOf()
        }
        return JsonUtil.parseObject(body, object : TypeReference<LinkedHashMap<String, Any?>>() {})
    }

    private fun copyFriendCenter(userId: String): FriendCenterConfig {
        val raw = JsonUtil.formatJson(FriendRepository.current(userId), false)
        return JsonUtil.parseObject(raw, FriendCenterConfig::class.java)
    }
}

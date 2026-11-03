package io.github.aoguai.sesameag.util.settingsTransfer

import com.fasterxml.jackson.databind.JsonNode
import io.github.aoguai.sesameag.model.ConfigPortScope
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.util.JsonUtil

object SettingsTransferImporter {
    fun resolve(json: String, targetUserId: String?): ResolvedSettingsImport {
        val body = json.trim()
        require(body.isNotEmpty()) { "导入文件为空" }
        val rootNode = JsonUtil.toNode(body) ?: error("导入文件不是有效 JSON")
        if (hasTransferPackageMarker(rootNode) && !looksLikeTransferPackage(rootNode)) {
            error("新版交换包缺少必要字段或结构无效")
        }
        return if (looksLikeTransferPackage(rootNode)) {
            resolvePackage(body, rootNode, targetUserId)
        } else {
            resolveLegacy(body)
        }
    }

    private fun resolvePackage(json: String, rootNode: JsonNode, targetUserId: String?): ResolvedSettingsImport {
        validatePackageStructure(rootNode)
        val packageV1 = SettingsTransferCodec.decodePackage(json)
        require(packageV1.schemaVersion == SettingsTransferCodec.SCHEMA_VERSION) {
            "不支持的配置交换版本: ${packageV1.schemaVersion}"
        }
        packageV1.genericConfig = filterSnapshotByScope(
            packageV1.genericConfig,
            ConfigPortScope.GENERIC
        )
        packageV1.privateConfig = packageV1.privateConfig
            ?.let { filterSnapshotByScope(it, ConfigPortScope.ACCOUNT_PRIVATE) }
            ?.takeUnless { it.isEmpty() }
        SettingsTransferCacheRegistry.validateEntries(
            packageV1.genericCaches,
            ConfigPortScope.GENERIC
        )
        SettingsTransferCacheRegistry.validateEntries(
            packageV1.privateCaches,
            ConfigPortScope.ACCOUNT_PRIVATE
        )
        require(!packageV1.genericConfig.isEmpty()) { "新版交换包不包含可导入的通用配置" }
        val safeTargetUserId = targetUserId?.trim().orEmpty()
        val privatePayloadPresent = hasPrivatePayload(packageV1)
        if (packageV1.exportMode == SettingsTransferExportMode.BACKUP) {
            require(!(privatePayloadPresent && packageV1.sourceAccountFingerprint.isNullOrBlank())) {
                "备份包缺少账号指纹，无法校验账号私有配置"
            }
            require(packageV1.sourceAccountFingerprint.isNullOrBlank() || privatePayloadPresent) {
                "备份包缺少账号私有配置内容"
            }
            if (!packageV1.sourceAccountFingerprint.isNullOrBlank()) {
                require(rootNode.path("privateConfig").isObject) { "备份包缺少 privateConfig" }
                require(rootNode.path("friendCenter").isObject) { "备份包缺少 friendCenter" }
            }
        }
        val canApplyPrivate = privatePayloadPresent &&
            packageV1.exportMode == SettingsTransferExportMode.BACKUP &&
            safeTargetUserId.isNotEmpty() &&
            packageV1.sourceAccountFingerprint == SettingsTransferCodec.fingerprintUser(safeTargetUserId)
        return ResolvedSettingsImport(
            format = SettingsTransferSourceFormat.PACKAGE_V1,
            exportMode = packageV1.exportMode,
            schemaVersion = packageV1.schemaVersion,
            genericConfig = packageV1.genericConfig,
            customSettings = packageV1.customSettings,
            genericCaches = packageV1.genericCaches,
            privateConfig = packageV1.privateConfig,
            privateCaches = packageV1.privateCaches,
            friendCenter = packageV1.friendCenter,
            canApplyPrivate = canApplyPrivate
        )
    }

    private fun resolveLegacy(json: String): ResolvedSettingsImport {
        val legacySnapshot = filterSnapshotByScope(
            SettingsTransferCodec.parseLegacyConfigSnapshot(json),
            ConfigPortScope.GENERIC
        )
        require(!legacySnapshot.isEmpty()) { "旧版配置文件结构无效或不包含可导入的通用配置" }
        return ResolvedSettingsImport(
            format = SettingsTransferSourceFormat.LEGACY_CONFIG_V2,
            exportMode = SettingsTransferExportMode.SHARE,
            schemaVersion = null,
            genericConfig = legacySnapshot,
            customSettings = null,
            genericCaches = emptyList(),
            privateConfig = null,
            privateCaches = emptyList(),
            friendCenter = null,
            canApplyPrivate = false
        )
    }

    private fun looksLikeTransferPackage(rootNode: JsonNode): Boolean {
        return rootNode.isObject && rootNode.has("schemaVersion") && rootNode.has("exportMode") && rootNode.has("genericConfig")
    }

    private fun hasTransferPackageMarker(rootNode: JsonNode): Boolean {
        return rootNode.isObject && (
            rootNode.has("schemaVersion") ||
                rootNode.has("exportMode") ||
                rootNode.has("genericConfig")
            )
    }

    private fun validatePackageStructure(rootNode: JsonNode) {
        require(rootNode.path("schemaVersion").isIntegralNumber) { "新版交换包缺少 schemaVersion" }
        require(rootNode.path("exportMode").isTextual) { "新版交换包缺少 exportMode" }
        require(rootNode.path("createdAt").isIntegralNumber) { "新版交换包缺少 createdAt" }
        require(rootNode.path("appVersion").isTextual) { "新版交换包缺少 appVersion" }
        require(rootNode.has("sourceAccountFingerprint")) { "新版交换包缺少 sourceAccountFingerprint" }
        require(rootNode.path("genericConfig").isObject) { "新版交换包缺少 genericConfig" }
        require(rootNode.path("genericCaches").isArray) { "新版交换包缺少 genericCaches" }
        require(rootNode.has("customSettings")) { "新版交换包缺少 customSettings" }
        require(rootNode.has("privateConfig")) { "新版交换包缺少 privateConfig" }
        require(rootNode.has("privateCaches")) { "新版交换包缺少 privateCaches" }
        require(rootNode.has("friendCenter")) { "新版交换包缺少 friendCenter" }
        require(
            rootNode.path("sourceAccountFingerprint").isTextual ||
                rootNode.path("sourceAccountFingerprint").isNull
        ) { "新版交换包 sourceAccountFingerprint 结构无效" }
        require(
            rootNode.path("customSettings").isObject ||
                rootNode.path("customSettings").isNull
        ) { "新版交换包 customSettings 结构无效" }
        require(
            rootNode.path("privateConfig").isObject ||
                rootNode.path("privateConfig").isNull
        ) { "新版交换包 privateConfig 结构无效" }
        require(
            rootNode.path("privateCaches").isArray
        ) { "新版交换包 privateCaches 结构无效" }
        require(
            rootNode.path("friendCenter").isObject ||
                rootNode.path("friendCenter").isNull
        ) { "新版交换包 friendCenter 结构无效" }
    }

    private fun hasPrivatePayload(settingsPackage: SettingsTransferPackageV1): Boolean {
        return !(settingsPackage.privateConfig?.isEmpty() ?: true) ||
            settingsPackage.privateCaches.isNotEmpty() ||
            settingsPackage.friendCenter != null
    }

    private fun filterSnapshotByScope(snapshot: ConfigTransferSnapshot, scope: ConfigPortScope): ConfigTransferSnapshot {
        val filtered = ConfigTransferSnapshot()
        snapshot.modelFields.forEach { (modelCode, fieldMap) ->
            val modelConfig = Model.getModelConfigMap()[modelCode] ?: return@forEach
            val scopedFields = linkedMapOf<String, String?>()
            fieldMap.forEach { (fieldCode, configValue) ->
                val field = modelConfig.getModelField(fieldCode) ?: return@forEach
                if (field.configPortScope == scope) {
                    scopedFields[fieldCode] = configValue
                }
            }
            if (scopedFields.isNotEmpty()) {
                filtered.modelFields[modelCode] = LinkedHashMap(scopedFields)
            }
        }
        return filtered
    }
}

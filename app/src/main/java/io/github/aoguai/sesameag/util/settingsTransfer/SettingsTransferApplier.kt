package io.github.aoguai.sesameag.util.settingsTransfer

import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.entity.friend.FriendCenterConfig
import io.github.aoguai.sesameag.model.ConfigPortScope
import io.github.aoguai.sesameag.model.CustomSettings
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.friend.FriendRepository
import java.io.File

object SettingsTransferApplier {
    private const val TAG = "SettingsTransferApplier"

    fun apply(userId: String?, resolvedImport: ResolvedSettingsImport): SettingsTransferApplyResult = try {
        val target = userId?.trim()?.takeIf { it.isNotEmpty() }
        Config.withLoadedAccount(target) {
            if (target == null) applyLoaded(null, resolvedImport)
            else Files.withAccountLock(target) { applyLoaded(target, resolvedImport) }
        }
    } catch (failure: Throwable) {
        Log.printStackTrace(TAG, "apply import failed", failure)
        SettingsTransferApplyResult(false, "导入失败：${failure.message ?: "无法读取目标账号配置"}")
    }

    private fun applyLoaded(userId: String?, resolved: ResolvedSettingsImport): SettingsTransferApplyResult {
        validateScope(ConfigPortScope.GENERIC, resolved.genericConfig)
        if (resolved.canApplyPrivate) {
            resolved.privateConfig?.let { validateScope(ConfigPortScope.ACCOUNT_PRIVATE, it) }
        }
        val customSettings = resolved.customSettings?.let(CustomSettings::normalizeForImport)
        val friendCenter = resolved.friendCenter?.takeIf { resolved.canApplyPrivate }?.let {
            JsonUtil.parseObject(JsonUtil.formatJson(it, false), FriendCenterConfig::class.java)
        }
        val beforeGeneric = SettingsTransferExporter.snapshotCurrentConfig(ConfigPortScope.GENERIC)
        val beforePrivate = SettingsTransferExporter.snapshotCurrentConfig(ConfigPortScope.ACCOUNT_PRIVATE)
        val applyChanges = {
            applyScopeSnapshot(ConfigPortScope.GENERIC, resolved.genericConfig)
            if (resolved.canApplyPrivate) {
                applyScopeSnapshot(ConfigPortScope.ACCOUNT_PRIVATE, resolved.privateConfig ?: ConfigTransferSnapshot())
            }
            if (userId != null && friendCenter != null) {
                check(FriendRepository.save(userId, friendCenter)) { "保存好友中心失败" }
            }
            check(Config.save(userId, true)) { "保存配置失败" }
            if (userId != null) {
                customSettings?.let {
                    check(Files.writeAtomically(JsonUtil.formatJson(it), requireNotNull(Files.getCustomSetFile(userId)))) {
                        "保存自定义设置失败"
                    }
                }
                if (resolved.format == SettingsTransferSourceFormat.PACKAGE_V1) {
                    check(SettingsTransferCacheRegistry.writeCaches(userId, resolved.genericCaches, ConfigPortScope.GENERIC)) {
                        "写入通用缓存失败"
                    }
                    if (resolved.canApplyPrivate) {
                        check(SettingsTransferCacheRegistry.writeCaches(userId, resolved.privateCaches, ConfigPortScope.ACCOUNT_PRIVATE)) {
                            "写入账号私有缓存失败"
                        }
                    }
                }
            }
        }
        return try {
            if (userId == null) {
                applyChanges()
            } else {
                Files.withAccountTransaction(userId, targetFiles(userId, resolved), applyChanges)
            }
            SettingsTransferApplyResult(true, "设置已保存，尚未确认运行态")
        } catch (failure: Throwable) {
            Log.printStackTrace(TAG, "apply import failed", failure)
            var message = "导入失败：${failure.message ?: "保存设置时出错"}"
            try {
                applyScopeSnapshot(ConfigPortScope.GENERIC, beforeGeneric)
                applyScopeSnapshot(ConfigPortScope.ACCOUNT_PRIVATE, beforePrivate)
            } catch (restore: Throwable) {
                Config.unload()
                message += "；内存配置恢复失败，请重新打开目标账号"
                Log.printStackTrace(TAG, "restore config failed", restore)
            }
            SettingsTransferApplyResult(false, message)
        }
    }

    private fun validateScope(scope: ConfigPortScope, snapshot: ConfigTransferSnapshot) {
        snapshot.modelFields.forEach { (modelCode, fieldMap) ->
            val model = Model.getModelConfigMap()[modelCode] ?: return@forEach
            fieldMap.forEach { (fieldCode, configValue) ->
                val field = model.getModelField(fieldCode) ?: return@forEach
                if (field.configPortScope == scope) {
                    try {
                        SettingsTransferCodec.validateConfigValue(field, configValue)
                    } catch (failure: Exception) {
                        throw IllegalArgumentException("配置字段 $modelCode.$fieldCode 内容无效", failure)
                    }
                }
            }
        }
    }

    private fun applyScopeSnapshot(scope: ConfigPortScope, snapshot: ConfigTransferSnapshot) {
        Model.getModelConfigMap().values.forEach { model ->
            model.fields.values.filter { it.configPortScope == scope }.forEach { it.reset() }
        }
        snapshot.modelFields.forEach { (modelCode, fieldMap) ->
            val model = Model.getModelConfigMap()[modelCode] ?: return@forEach
            fieldMap.forEach { (fieldCode, configValue) ->
                val field = model.getModelField(fieldCode) ?: return@forEach
                if (field.configPortScope == scope) {
                    if (configValue == null) field.reset()
                    else field.setConfigValue(if (field is BooleanModelField) configValue.trim() else configValue)
                }
            }
        }
    }

    private fun targetFiles(userId: String, resolved: ResolvedSettingsImport): List<File> {
        val files = linkedSetOf(Files.getConfigV2File(userId))
        val directory = Files.getUserConfigDir(userId)
        if (resolved.customSettings != null) files.add(Files.child(directory, "customset.json"))
        if (resolved.canApplyPrivate && resolved.friendCenter != null) files.add(Files.child(directory, "friendCenter.json"))
        if (resolved.format == SettingsTransferSourceFormat.PACKAGE_V1) {
            files.addAll(SettingsTransferCacheRegistry.targetFilesForScope(userId, ConfigPortScope.GENERIC, resolved.genericCaches))
            if (resolved.canApplyPrivate) {
                files.addAll(SettingsTransferCacheRegistry.targetFilesForScope(userId, ConfigPortScope.ACCOUNT_PRIVATE, resolved.privateCaches))
            }
        }
        return files.toList()
    }
}

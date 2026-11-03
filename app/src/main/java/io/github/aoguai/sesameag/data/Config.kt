package io.github.aoguai.sesameag.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.aoguai.sesameag.BuildConfig
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionCountModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.UserMap
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 配置类，负责加载、保存、管理应用的配置数据。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class Config private constructor() {

    /** 是否初始化标志 */
    @JsonIgnore
    @Volatile
    var isInit: Boolean = false
        private set

    @JsonIgnore
    @Volatile
    private var loadedUserId: String? = null

    /** 存储模型字段的映射 */
    private val modelFieldsMap: MutableMap<String, ModelFields> = ConcurrentHashMap()

    /** 用户最近一次确认 LEGAL 说明时对应的应用版本。 */
    var legalAcceptedAppVersion: String? = null
    
    /**
     * 获取模型字段映射（用于序列化）
     */
    fun getModelFieldsMap(): MutableMap<String, ModelFields> = modelFieldsMap
    
    /**
     * 设置模型字段映射（用于反序列化）
     * 这个方法会在Jackson反序列化时被调用
     */
    fun setModelFieldsMap(newModels: Map<String, ModelFields>?) {
        modelFieldsMap.clear()
        val modelConfigMap = Model.getModelConfigMap()
        val models = newModels ?: emptyMap()

        // 遍历所有模型配置，从ModelConfig.fields复制字段
        for ((modelCode, modelConfig) in modelConfigMap.entries) {
            val newModelFields = ModelFields()
            val configModelFields = modelConfig.fields
            val modelFields = models[modelCode]

            if (modelFields != null) {
                // 如果JSON中有这个模型的配置，用JSON的值覆盖
                for (configModelField in configModelFields.values) {
                    val modelField = modelFields[configModelField.code]
                    try {
                        if (modelField != null) {
                            val value = modelField.value
                            if (value != null) {
                                configModelField.setObjectValue(value)
                            }
                        }
                    } catch (e: Exception) {
                        Log.printStackTrace(e)
                    }
                    newModelFields.addField(configModelField)
                }
            } else {
                // 如果JSON中没有这个模型，直接复制ModelConfig的字段
                for (configModelField in configModelFields.values) {
                    newModelFields.addField(configModelField)
                }
            }
            modelFieldsMap[modelCode] = newModelFields
        }
    }

    @JsonIgnore
    fun hasAcceptedLegalForCurrentVersion(): Boolean {
        return legalAcceptedAppVersion?.trim().orEmpty() == BuildConfig.VERSION_NAME
    }

    fun updateLegalAcceptedForCurrentVersion(accepted: Boolean) {
        legalAcceptedAppVersion = if (accepted) BuildConfig.VERSION_NAME else ""
    }

    private fun sanitizeFriendSelectionFields(userId: String?) {
        if (userId.isNullOrBlank()) return
        val modelConfigMap = Model.getModelConfigMap()
        for ((modelCode, modelFields) in modelFieldsMap) {
            val capabilityModuleKey = modelConfigMap[modelCode]?.group?.code
            for (modelField in modelFields.values) {
                when (modelField) {
                    is FriendSelectionModelField -> modelField.sanitizeForUser(userId, capabilityModuleKey)
                    is FriendSelectionCountModelField -> modelField.sanitizeForUser(userId, capabilityModuleKey)
                }
            }
        }
    }

    companion object {
        private const val TAG = "Config"
        private const val LEGAL_ACCEPTED_APP_VERSION_FIELD = "legalAcceptedAppVersion"

        /** 单例实例 */
        @JvmField
        val INSTANCE = Config()

        /**
         * 判断配置文件是否已修改
         *
         * @param userId 用户 ID
         * @return 是否已修改
         */
        @JvmStatic
        fun isModify(userId: String?): Boolean = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
            if (!isLoadedFor(userId)) return@transaction true
            INSTANCE.sanitizeFriendSelectionFields(userId)
            val configV2File = if (userId.isNullOrEmpty()) {
                Files.getDefaultConfigV2File()
            } else {
                Files.getConfigV2File(userId)
            }

            if (!configV2File.exists()) {
                return@transaction true
            }

            val json = Files.readFromFile(configV2File) ?: return@transaction true
            val formatted = JsonUtil.formatJson(INSTANCE) ?: return@transaction true
            return@transaction formatted != json
        }

        /**
         * 保存配置文件
         *
         * @param userId 用户 ID
         * @param force  是否强制保存
         * @return 保存是否成功
         */
        @JvmStatic
        fun save(userId: String?, force: Boolean): Boolean = try {
            withLoadedAccount(userId) {
                if (userId.isNullOrBlank()) saveLoaded(null, force)
                else Files.withAccountLock(userId) { saveLoaded(userId, force) }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "保存目标账号配置失败", e)
            false
        }

        private fun saveLoaded(userId: String?, force: Boolean): Boolean {
            io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.ensureCurrent(userId)
            INSTANCE.sanitizeFriendSelectionFields(userId)
            if (!force && !isModify(userId)) {
                return true
            }

            val json = try {
                JsonUtil.formatJson(INSTANCE)
                    ?: throw IllegalStateException("配置格式化失败，返回的 JSON 为空")
            } catch (e: Exception) {
                Log.printStackTrace(TAG, e)
                Log.runtime(TAG, "保存用户配置失败，格式化 JSON 时出错")
                return false
            }

            return try {
                val actualUserId = userId?.takeIf { it.isNotEmpty() } ?: "默认"
                val file = if (userId.isNullOrEmpty()) Files.getDefaultConfigV2File() else Files.getConfigV2File(userId)
                val success = Files.writeAtomically(json, file)

                if (!success) {
                    throw IOException("配置文件保存失败")
                }

                val userName = if (actualUserId == "默认") {
                    "默认用户"
                } else {
                    UserMap.get(actualUserId)?.showName ?: "默认"
                }

                Log.runtime(TAG, "保存 [$userName] 配置")
                true
            } catch (e: Exception) {
                Log.printStackTrace(TAG, e)
                Log.runtime(TAG, "保存用户配置失败")
                false
            }
        }

        /**
         * 检查配置是否已加载
         *
         * @return 是否已加载
         */
        @JvmStatic
        fun isLoaded(): Boolean = INSTANCE.isInit

        fun isLoadedFor(userId: String?): Boolean = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
            INSTANCE.isInit && INSTANCE.loadedUserId == userId?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun <T> withLoadedAccount(userId: String?, action: () -> T): T =
            io.github.aoguai.sesameag.storage.StoreAccess.transaction {
                check(isLoadedFor(userId)) { "账号配置已变化，请重新打开目标账号后重试" }
                action()
            }

        @JvmStatic
        fun isLegalAcceptedForCurrentVersion(): Boolean = INSTANCE.hasAcceptedLegalForCurrentVersion()

        @JvmStatic
        fun setLegalAcceptedForCurrentVersion(accepted: Boolean) {
            INSTANCE.updateLegalAcceptedForCurrentVersion(accepted)
        }

        @JvmStatic
        fun sanitizeFriendSelectionFieldsForUser(userId: String?): Boolean = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
            val safeUserId = userId?.trim().orEmpty()
            if (safeUserId.isEmpty() || INSTANCE.loadedUserId != safeUserId) return@transaction false
            INSTANCE.sanitizeFriendSelectionFields(userId)
            return@transaction true
        }

        @JvmStatic
        fun readLegalAcceptedForCurrentVersion(userId: String?): Boolean {
            val readableFile = resolveReadableConfigFile(userId) ?: return false
            val acceptedVersion = readLegalAcceptedAppVersion(Files.readFromFile(readableFile))
            return acceptedVersion?.trim().orEmpty() == BuildConfig.VERSION_NAME
        }

        @JvmStatic
        fun saveLegalAcceptedForCurrentVersion(userId: String?, accepted: Boolean): Boolean = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
            val targetFile = if (userId.isNullOrEmpty()) {
                Files.getDefaultConfigV2File()
            } else {
                Files.getConfigV2File(userId)
            }

            val baseJson = readBaseConfigJsonForLegalWrite(userId, targetFile) ?: return@transaction false
            val mapper = JsonUtil.copyMapper()
            val rootNode = (mapper.readTree(baseJson) as? ObjectNode) ?: mapper.createObjectNode()
            rootNode.put(LEGAL_ACCEPTED_APP_VERSION_FIELD, if (accepted) BuildConfig.VERSION_NAME else "")
            val updatedJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode)

            return@transaction if (userId.isNullOrEmpty()) {
                Files.setDefaultConfigV2File(updatedJson)
            } else {
                Files.setConfigV2File(userId, updatedJson)
            }
        }

        @JvmStatic
        fun saveLegalAcceptedForUsers(userIds: Collection<String>, accepted: Boolean): Boolean = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
            val normalizedUserIds = userIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val defaultSaved = saveLegalAcceptedForCurrentVersion(null, accepted)
            val userResults = normalizedUserIds.map { userId ->
                userId to saveLegalAcceptedForCurrentVersion(userId, accepted)
            }
            val failedUsers = userResults.filterNot { it.second }.map { it.first }
            if (!defaultSaved || failedUsers.isNotEmpty()) {
                Log.runtime(TAG, "协议确认保存失败: defaultSaved=$defaultSaved failedUsers=$failedUsers")
                return@transaction false
            }
            Log.runtime(TAG, "协议确认已保存: users=$normalizedUserIds defaultSaved=$defaultSaved")
            return@transaction true
        }

        /**
         * 加载配置文件
         *
         * @param userId 用户 ID
         * @return 配置实例
         */
        @JvmStatic
        fun load(userId: String?): Config = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
            val target = userId?.trim()?.takeIf { it.isNotEmpty() }
            return@transaction if (target == null) loadAccount(null)
            else Files.withAccountLock(target) { loadAccount(target) }
        }

        private fun loadAccount(userId: String?): Config {
            val file = if (userId == null) Files.getDefaultConfigV2File() else Files.getConfigV2File(userId)
            val source = if (file.exists()) file else Files.getDefaultConfigV2File()
            val json = source.takeIf { it.exists() }?.let(Files::readText)
            if (json != null) require(JsonUtil.toNode(json)?.isObject == true) { "配置文件内容无效: ${source.name}" }
            val previousJson = toSaveStr()
            val previousUserId = INSTANCE.loadedUserId
            val previousInit = INSTANCE.isInit
            try {
                unload()
                INSTANCE.setModelFieldsMap(null)
                if (json != null) {
                    JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(json, Config::class.java)
                    syncExtraFieldsFromJson(json)
                }
                INSTANCE.sanitizeFriendSelectionFields(userId)
                if (!file.exists()) {
                    check(Files.writeAtomically(requireNotNull(toSaveStr()), file)) { "创建配置文件失败" }
                }
                INSTANCE.loadedUserId = userId
                INSTANCE.isInit = true
                TaskCommon.update()
                Log.record(TAG, "已加载配置: ${userId ?: "默认"}")
                return INSTANCE
            } catch (failure: Throwable) {
                unload()
                if (previousInit && previousJson != null) {
                    try {
                        JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(previousJson, Config::class.java)
                        syncExtraFieldsFromJson(previousJson)
                        INSTANCE.loadedUserId = previousUserId
                        INSTANCE.isInit = true
                    } catch (restore: Throwable) {
                        failure.addSuppressed(restore)
                        INSTANCE.isInit = false
                    }
                }
                Log.printStackTrace(TAG, "加载配置失败，未覆盖原文件", failure)
                throw failure
            }
        }

        /**
         * 卸载当前配置
         */
        @JvmStatic
        fun unload(): Unit = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
            Model.getModelConfigMap().values.forEach { model -> model.fields.values.forEach { it.reset() } }
            for (modelFields in INSTANCE.modelFieldsMap.values) {
                for (modelField in modelFields.values) {
                    modelField?.reset()
                }
            }
            INSTANCE.legalAcceptedAppVersion = null
            INSTANCE.loadedUserId = null
            INSTANCE.isInit = false
        }

        /**
         * 转换为保存字符串
         *
         * @return JSON字符串
         */
        @JvmStatic
        fun toSaveStr(): String? = JsonUtil.formatJson(INSTANCE)

        private fun syncExtraFieldsFromJson(json: String?) {
            val acceptedVersion = when {
                json.isNullOrBlank() -> null
                else -> {
                    val rootNode = JsonUtil.toNode(json)
                    if (rootNode?.has(LEGAL_ACCEPTED_APP_VERSION_FIELD) == true) {
                        rootNode.path(LEGAL_ACCEPTED_APP_VERSION_FIELD).asText("")
                    } else {
                        null
                    }
                }
            }
            INSTANCE.legalAcceptedAppVersion = acceptedVersion
        }

        private fun resolveReadableConfigFile(userId: String?): File? {
            val targetFile = if (userId.isNullOrEmpty()) {
                Files.getDefaultConfigV2File()
            } else {
                Files.getConfigV2File(userId)
            }
            if (targetFile.exists()) {
                return targetFile
            }
            if (!userId.isNullOrEmpty()) {
                val defaultFile = Files.getDefaultConfigV2File()
                if (defaultFile.exists()) {
                    return defaultFile
                }
            }
            return null
        }

        private fun readLegalAcceptedAppVersion(json: String?): String? {
            return when {
                json.isNullOrBlank() -> null
                else -> {
                    val rootNode = JsonUtil.toNode(json)
                    if (rootNode?.has(LEGAL_ACCEPTED_APP_VERSION_FIELD) == true) {
                        rootNode.path(LEGAL_ACCEPTED_APP_VERSION_FIELD).asText("")
                    } else {
                        null
                    }
                }
            }
        }

        private fun readBaseConfigJsonForLegalWrite(userId: String?, targetFile: File): String? {
            if (targetFile.exists()) {
                val targetJson = Files.readFromFile(targetFile)
                if (targetJson.isNotBlank()) {
                    return targetJson
                }
            }
            if (!userId.isNullOrEmpty()) {
                val defaultFile = Files.getDefaultConfigV2File()
                if (defaultFile.exists()) {
                    val defaultJson = Files.readFromFile(defaultFile)
                    if (defaultJson.isNotBlank()) {
                        return defaultJson
                    }
                }
            }
            return createConfigSkeletonForLegalWrite(userId)
        }

        private fun createConfigSkeletonForLegalWrite(userId: String?): String? {
            val previousJson = toSaveStr()
            val previousInit = INSTANCE.isInit
            val previousUserId = INSTANCE.loadedUserId

            return try {
                load(userId)
                toSaveStr()
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "初始化法律确认配置骨架失败", t)
                previousJson
            } finally {
                try {
                    if (!previousJson.isNullOrBlank()) {
                        JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(previousJson, Config::class.java)
                        syncExtraFieldsFromJson(previousJson)
                    } else {
                        unload()
                    }
                } catch (restoreEx: Exception) {
                    Log.printStackTrace(TAG, "恢复 Config 单例状态失败", restoreEx)
                } finally {
                    INSTANCE.isInit = previousInit
                    INSTANCE.loadedUserId = previousUserId
                }
            }
        }

    }
}


package io.github.aoguai.sesameag.util.settingsTransfer

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.ModelField
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.modelFieldExt.StringModelField
import io.github.aoguai.sesameag.model.modelFieldExt.TextModelField
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.security.MessageDigest

object SettingsTransferCodec {
    private const val TAG = "SettingsTransferCodec"
    const val SCHEMA_VERSION = 1

    fun encodePackage(settingsPackage: SettingsTransferPackageV1): String {
        return JsonUtil.copyMapper()
            .apply { setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS) }
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(settingsPackage)
    }

    fun decodePackage(json: String): SettingsTransferPackageV1 {
        return JsonUtil.parseObject(json, SettingsTransferPackageV1::class.java)
    }

    fun parseLegacyConfigSnapshot(json: String): ConfigTransferSnapshot {
        val mapper = JsonUtil.copyMapper()
        val rootNode = mapper.readTree(json)
        val modelsNode = when {
            rootNode.has("modelFieldsMap") && rootNode.path("modelFieldsMap").isObject -> rootNode.path("modelFieldsMap")
            else -> rootNode
        }
        val knownModelCodes = Model.getModelConfigMap().keys
        val snapshot = ConfigTransferSnapshot()
        modelsNode.properties().forEach { (modelCode, modelNode) ->
            if (!knownModelCodes.contains(modelCode) || !modelNode.isObject) {
                return@forEach
            }
            val fieldMap = linkedMapOf<String, String?>()
            modelNode.properties().forEach { (fieldCode, fieldNode) ->
                val field = Model.getModelConfigMap()[modelCode]?.getModelField(fieldCode)
                fieldMap[fieldCode] = extractLegacyConfigValue(fieldNode, field, mapper)
            }
            if (fieldMap.isNotEmpty()) {
                snapshot.modelFields[modelCode] = LinkedHashMap(fieldMap)
            }
        }
        return snapshot
    }

    fun validateConfigValue(field: ModelField<*>, configValue: String?) {
        if (configValue.isNullOrBlank()) return
        when (field) {
            is StringModelField, is TextModelField -> Unit
            is BooleanModelField -> require(
                configValue.trim().lowercase() in setOf("true", "false", "1", "0")
            ) { "${field.code} 需要有效布尔值" }
            is ChoiceModelField, is IntegerModelField -> require(configValue.trim().toIntOrNull() != null) {
                "${field.code} 需要有效整数"
            }
            else -> {
                val mapper = JsonUtil.copyMapper()
                    .apply {
                        setConfig(deserializationConfig.without(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS))
                    }
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                requireNotNull(mapper.readValue<Any>(configValue, mapper.typeFactory.constructType(field.valueType))) {
                    "${field.code} 内容无效"
                }
            }
        }
    }

    fun fingerprintUser(userId: String?): String? {
        val safeUserId = userId?.trim().orEmpty()
        if (safeUserId.isEmpty()) {
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("sesame-ag:$safeUserId".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun extractLegacyConfigValue(
        fieldNode: JsonNode,
        modelField: ModelField<*>?,
        mapper: com.fasterxml.jackson.databind.ObjectMapper
    ): String? {
        val valueNode = when {
            fieldNode.isObject && fieldNode.has("value") -> fieldNode.get("value")
            else -> fieldNode
        }
        if (valueNode == null || valueNode.isNull) {
            return null
        }
        return runCatching {
            if (modelField == null) {
                mapper.writeValueAsString(valueNode)
            } else {
                encodeConfigValue(
                    modelField,
                    mapper.convertValue(valueNode, mapper.typeFactory.constructType(modelField.valueType))
                )
            }
        }.onFailure {
            Log.printStackTrace(
                TAG,
                "legacy config field parse fallback: ${modelField?.code ?: "<unknown>"}",
                it
            )
        }.getOrElse {
            mapper.writeValueAsString(valueNode)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeConfigValue(
        modelField: ModelField<*>,
        rawValue: Any?
    ): String? {
        val configValue = (modelField as ModelField<Any?>).toConfigValue(rawValue) ?: return null
        return if (usesPlainConfigString(modelField)) {
            configValue.toString()
        } else {
            JsonUtil.formatJson(configValue)
        }
    }

    private fun usesPlainConfigString(modelField: ModelField<*>): Boolean {
        return modelField is BooleanModelField ||
            modelField is ChoiceModelField ||
            modelField is IntegerModelField ||
            modelField is StringModelField ||
            modelField is TextModelField
    }
}

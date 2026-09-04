package io.github.aoguai.sesameag.ui.dto

import io.github.aoguai.sesameag.data.TodayFlagKey
import io.github.aoguai.sesameag.model.ModelField
import io.github.aoguai.sesameag.model.ModelFields
import java.io.Serializable

/**
 * 模型字段展示数据传输对象。
 * 用于封装模型字段的展示信息，包括字段代码、名称、类型、扩展键和配置值。
 *
 * @property code 字段代码
 * @property name 字段名称
 * @property type 字段类型
 * @property expandKey 扩展键，用于存储额外的信息
 * @property configValue 配置值，用于存储字段的配置信息
 * @property desc 字段描述
 * @property todayInactive 字段对应任务在今日是否已停止执行
 * @property todayInactiveReason 字段对应任务在今日停止执行的原因
 */
data class ModelFieldShowDto(
    var code: String = "",
    var name: String = "",
    var type: String = "",
    var expandKey: Any? = null,
    var editorMeta: Any? = null,
    var configValue: String = "",
    var desc: String = "",
    var todayInactive: Boolean = false,
    var todayInactiveReason: String = "",
    var todayClearableFlagKeys: List<TodayFlagKey> = emptyList(),
) : Serializable {

    constructor() : this("", "", "", null, null, "", "", false, "", emptyList())

    companion object {
        /**
         * 将ModelField对象转换为ModelFieldShowDto对象。
         * 这是一个静态工厂方法，用于创建ModelFieldShowDto实例。
         *
         * @param modelCode 模型代码
         * @param modelFields 模型全部字段，用于解析跨字段的今日状态
         * @param modelField ModelField对象
         * @return ModelFieldShowDto对象
         */
        @JvmStatic
        fun toShowDto(modelCode: String, modelFields: ModelFields, modelField: ModelField<*>): ModelFieldShowDto {
            val todayState = ModelFieldTodayStateResolver.resolve(modelCode, modelFields, modelField)
            return ModelFieldShowDto(
                code = modelField.code,
                name = modelField.name,
                type = modelField.getType(),
                expandKey = modelField.getExpandKey(),
                editorMeta = modelField.getEditorMeta(),
                configValue = modelField.getConfigValue() ?: "",
                desc = modelField.desc,
                todayInactive = todayState.inactive,
                todayInactiveReason = todayState.reason,
                todayClearableFlagKeys = todayState.clearableFlagKeys.toList(),
            )
        }
    }
}


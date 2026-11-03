package io.github.aoguai.sesameag.model

import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import java.io.Serializable

/**
 * 模型配置类，用于保存每个模型的配置信息。
 * 包括模型的基本信息（如名称、组、字段等），并提供方法来访问和操作这些配置项。
 */
data class ModelConfig(
    /** 模型的唯一标识符，通常是模型类名 */
    var code: String = "",
    /** 模型名称 */
    var name: String = "",
    /** 模型所属的组 */
    var group: ModelGroup? = null,
    /** 模型图标（可选） */
    var icon: String = ""
) : Serializable {

    /** 模型的所有字段 */
    val fields: ModelFields = ModelFields()

    /**
     * 使用给定的模型实例来初始化模型配置
     *
     * @param model 模型实例
     */
    constructor(model: Model) : this() {
        // 设置模型的简单类名作为唯一标识符
        this.code = model.javaClass.simpleName
        // 设置模型的名称
        this.name = model.getName() ?: ""
        // 设置模型所属组
        this.group = model.getGroup()
        // 设置模型的图标文件名称（图标位置app/src/main/assets/web/images/icon/model[/selected]，正常状态和选中状态）
        // 无图标定义时使用default.svg
        this.icon = model.getIcon() ?: "default.svg"
        // 获取模型的启用字段，并将其加入到字段列表中
        val enableField: BooleanModelField = model.enableField
        fields[enableField.code] = enableField
        // 获取模型的其他字段，并将其加入到字段列表中
        val modelFields: ModelFields? = model.getFields()
        modelFields?.forEach { (_, modelField) ->
            fields[modelField.code] = modelField
        }
    }

    /**
     * 获取指定字段代码的模型字段
     *
     * @param fieldCode 字段代码
     * @return 返回指定字段代码的模型字段
     */
    fun getModelField(fieldCode: String): ModelField<*>? = fields[fieldCode]

    /**
     * 获取指定字段代码的模型字段扩展类型
     *
     * @param fieldCode 字段代码
     * @param T 字段类型
     * @return 返回指定字段代码的模型字段扩展类型
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : ModelField<*>> getModelFieldExt(fieldCode: String): T? = fields[fieldCode] as? T

    companion object {
        private const val serialVersionUID = 1L
    }
}


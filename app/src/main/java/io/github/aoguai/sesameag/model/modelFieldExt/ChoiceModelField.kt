package io.github.aoguai.sesameag.model.modelFieldExt

import io.github.aoguai.sesameag.model.ModelField

data class ChoiceSwitchMeta(
    val offIndex: Int = 0,
    val onIndex: Int = 1,
)

/**
 * 选择型字段，用于在多个选项中选择一个
 */
class ChoiceModelField : ModelField<Int> {
    
    private var choiceArray: Array<out String?>? = null
    private var switchMeta: ChoiceSwitchMeta? = null

    constructor(code: String, name: String, value: Int) : super(code, name, value) {
        valueType = Int::class.java
    }

    constructor(code: String, name: String, value: Int, choiceArray: Array<out String?>) : super(code, name, value) {
        this.choiceArray = choiceArray
        valueType = Int::class.java
    }

    constructor(code: String, name: String, value: Int, desc: String) : super(code, name, value, desc) {
        valueType = Int::class.java
    }

    constructor(code: String, name: String, value: Int, choiceArray: Array<out String?>, desc: String) 
        : super(code, name, value, desc) {
        this.choiceArray = choiceArray
        valueType = Int::class.java
    }

    override fun getType(): String = "CHOICE"

    override fun getExpandKey(): Array<out String?>? = choiceArray

    fun asSwitch(offIndex: Int = 0, onIndex: Int = 1): ChoiceModelField = apply {
        require(choiceArray?.getOrNull(offIndex) != null) { "Switch off option is missing for $code" }
        require(choiceArray?.getOrNull(onIndex) != null) { "Switch on option is missing for $code" }
        switchMeta = ChoiceSwitchMeta(offIndex = offIndex, onIndex = onIndex)
    }

    override fun getEditorMeta(): Any? = switchMeta

    private fun parseChoiceValue(objectValue: Any?): Int? {
        return when (objectValue) {
            null -> null
            is Number -> objectValue.toInt()
            is Boolean -> if (objectValue) 1 else 0
            is String -> objectValue.trim().toIntOrNull()
            else -> objectValue.toString().trim().toIntOrNull()
        }
    }

    private fun normalizeChoiceValue(rawValue: Int?): Int {
        val fallback = defaultValue ?: 0
        val parsedValue = rawValue ?: fallback
        val lastIndex = (choiceArray?.size ?: 0) - 1
        return if (lastIndex >= 0) parsedValue.coerceIn(0, lastIndex) else parsedValue
    }

    override fun setObjectValue(objectValue: Any?) {
        value = normalizeChoiceValue(parseChoiceValue(objectValue))
    }
    
    /**
     * 设置配置值
     * 直接解析整数值，避免父类的类型推断错误
     */
    override fun setConfigValue(configValue: String?) {
        value = normalizeChoiceValue(parseChoiceValue(configValue))
    }
    
    /**
     * 获取配置值
     */
    override fun getConfigValue(): String? = value?.toString()

}


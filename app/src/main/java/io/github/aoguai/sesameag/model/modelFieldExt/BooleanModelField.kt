package io.github.aoguai.sesameag.model.modelFieldExt

import io.github.aoguai.sesameag.model.ModelField

/**
 * Boolean类型字段类
 * 该类用于表示布尔值字段，使用Switch控件进行展示
 */
class BooleanModelField(code: String, name: String, value: Boolean) : ModelField<Boolean>(code, name, value) {
    
    init {
        // 强制设置Boolean类型，避免Xposed环境下泛型推断失败
        valueType = Boolean::class.java
    }

    /**
     * 获取字段类型
     *
     * @return 字段类型字符串
     */
    override fun getType(): String = "BOOLEAN"
    
    /**
     * 设置配置值
     * 直接解析布尔值，避免父类的类型推断错误
     */
    override fun setConfigValue(configValue: String?) {
        value = when {
            configValue.isNullOrBlank() -> defaultValue
            configValue.equals("true", ignoreCase = true) || configValue == "1" -> true
            configValue.equals("false", ignoreCase = true) || configValue == "0" -> false
            else -> {
                try {
                    configValue.toBoolean()
                } catch (e: Exception) {
                    defaultValue
                }
            }
        }
    }
    
    /**
     * 获取配置值
     */
    override fun getConfigValue(): String? = value?.toString()

}


package io.github.aoguai.sesameag.model

import com.fasterxml.jackson.annotation.JsonIgnore
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TypeUtil
import org.json.JSONException
import java.io.Serializable
import java.lang.reflect.Type

/**
 * 模型字段类
 * 用于封装模型中的各种字段配置
 *
 * @param T 字段值的类型
 */
open class ModelField<T> : Serializable {
    
    /** 存储字段值的类型 */
    @get:JsonIgnore
    var valueType: Type
    
    /** 字段代码 */
    @JsonIgnore
    var code: String = ""
    
    /** 字段名称 */
    @JsonIgnore
    var name: String = ""
    
    /** 默认值 */
    @JsonIgnore
    var defaultValue: T? = null
    
    /** 字段描述 */
    @JsonIgnore
    var desc: String = ""

    @get:JsonIgnore
    var configPortScope: ConfigPortScope = ConfigPortScope.GENERIC
    
    /** 当前值 */
    @Volatile
    var value: T? = null

    /**
     * 默认构造函数，初始化字段值类型
     */
    constructor() {
        valueType = TypeUtil.getTypeArgument(this.javaClass.genericSuperclass, 0) ?: Any::class.java
    }

    /**
     * 构造函数，接受初始值
     *
     * @param value 初始值
     */
    constructor(value: T?) : this("", "", value)

    /**
     * 构造函数，接受字段代码、名称和初始值
     *
     * @param code  字段代码
     * @param name  字段名称
     * @param value 字段初始值
     */
    constructor(code: String, name: String, value: T?) : this() {
        this.code = code
        this.name = name
        this.defaultValue = value
        this.desc = ""
        // 如果反射获取类型失败（返回Any、Void或void），从value推断类型
        if (valueType == Any::class.java || valueType == Void::class.java || valueType == Void.TYPE) {
            valueType = when (value) {
                null -> Any::class.java
                else -> value.javaClass
            }
        }
        setObjectValue(value)
    }

    /**
     * 完整构造函数
     *
     * @param code  字段代码
     * @param name  字段名称
     * @param value 字段初始值
     * @param desc  字段描述
     */
    constructor(code: String, name: String, value: T?, desc: String) : this() {
        this.code = code
        this.name = name
        this.defaultValue = value
        this.desc = desc
        // 如果反射获取类型失败（返回Any、Void或void），从value推断类型
        if (valueType == Any::class.java || valueType == Void::class.java || valueType == Void.TYPE) {
            valueType = when (value) {
                null -> Any::class.java
                else -> value.javaClass
            }
        }
        setObjectValue(value)
    }

    /**
     * 设置当前值
     *
     * @param objectValue 要设置的值
     */
    open fun setObjectValue(objectValue: Any?) {
        if (objectValue == null) {
            reset() // 如果传入值为 null，则重置为默认值
            return
        }
        
        // 处理Boolean到Integer的转换
        val processedValue = if (valueType == Integer::class.java && objectValue is Boolean) {
            if (objectValue) 1 else 0
        } else {
            objectValue
        }
        
        value = JsonUtil.parseObject(processedValue, valueType) // 解析并设置当前值
    }

    /**
     * 获取字段类型
     *
     * @return 字段类型字符串
     */
    @JsonIgnore
    open fun getType(): String {
        return "DEFAULT" // 默认返回类型
    }

    /**
     * 获取扩展键
     *
     * @return 扩展键
     */
    @JsonIgnore
    open fun getExpandKey(): Any? {
        return null // 默认返回 null
    }

    /**
     * 获取扩展值
     *
     * @return 扩展值
     * @throws JSONException JSON异常
     */
    @JsonIgnore
    @Throws(JSONException::class)
    open fun getExpandValue(): Any? {
        return null // 默认返回 null
    }

    /**
     * 获取编辑器元数据。
     *
     * 主要用于 Web 配置页按字段语义渲染专用组件。
     */
    @JsonIgnore
    open fun getEditorMeta(): Any? {
        return null
    }

    /**
     * 将当前值转换为配置值
     *
     * @param value 当前值
     * @return 配置值
     */
    open fun toConfigValue(value: T?): Any? {
        return value // 默认返回当前值
    }

    /**
     * 从配置值转换为对象值
     *
     * @param value 配置值
     * @return 对象值
     */
    open fun fromConfigValue(value: String?): Any? {
        return value // 默认返回配置值
    }

    /**
     * 获取当前值的配置字符串表示
     *
     * @return 配置字符串
     */
    @JsonIgnore
    open fun getConfigValue(): String? {
        val v = value ?: return null
        val configValue = toConfigValue(v) ?: return null
        return JsonUtil.formatJson(configValue) // 转换为 JSON 字符串
    }

    /**
     * 设置配置值
     *
     * @param configValue 配置值字符串
     */
    @JsonIgnore
    open fun setConfigValue(configValue: String?) {
        if (configValue.isNullOrBlank()) {
            reset() // 如果配置值为 null 或空，则重置为默认值
            return
        }
        
        val objectValue = fromConfigValue(configValue) // 从配置值转换为对象值
        if (objectValue == null) {
            reset() // 如果转换后的对象值为 null，则重置为默认值
            return
        }
        
        // DEBUG: 记录类型信息
        val valueTypeBefore = valueType
        val isVoidType = (valueType == Void::class.java || valueType == Void.TYPE)
        
        // 如果反射类型推断失败，从objectValue推断真实类型
        if (valueType == Any::class.java || valueType == Void::class.java || valueType == Void.TYPE) {
            valueType = objectValue.javaClass
            Log.runtime(TAG_FIELD, "setConfigValue: 类型已修复 $code: $valueTypeBefore -> $valueType (objectValue类型=${objectValue.javaClass})")
        }
        
        // 如果对象值与配置值相等，则直接解析配置值
        value = if (objectValue == configValue) {
            JsonUtil.parseObject(configValue, valueType) ?: run {
                reset()
                return
            }
        } else {
            // 将objectValue转换为JSON字符串再解析，避免传入null
            val jsonValue = JsonUtil.formatJson(objectValue)
            if (!jsonValue.isNullOrBlank()) {
                JsonUtil.parseObject(jsonValue, valueType) ?: run {
                    reset()
                    return
                }
            } else {
                reset()
                return
            }
        }
    }
    
    companion object {
        private const val TAG_FIELD = "ModelField"
    }

    /**
     * 重置当前值为默认值
     */
    open fun reset() {
        value = defaultValue // 设置当前值为默认值
    }

}

/**
 * 链式设置字段描述，便于在字段声明处按需补充说明文案。
 */
fun <F : ModelField<*>> F.withDesc(desc: String?): F = apply {
    this.desc = desc?.trim().orEmpty()
}

fun <F : ModelField<*>> F.withPortScope(scope: ConfigPortScope): F = apply {
    this.configPortScope = scope
}


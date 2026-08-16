package io.github.aoguai.sesameag.model.modelFieldExt

import com.fasterxml.jackson.annotation.JsonIgnore
import io.github.aoguai.sesameag.model.ModelField

/**
 * 空模型字段，用于显示按钮但不存储值
 *
 * @property clickRunner 点击按钮时执行的操作，如果为null则显示"无配置项"提示
 */
class EmptyModelField : ModelField<Any?> {
    
    private val clickRunner: Runnable?

    constructor(code: String, name: String) : super(code, name, null) {
        this.clickRunner = null
    }

    constructor(code: String, name: String, clickRunner: Runnable) : super(code, name, null) {
        this.clickRunner = clickRunner
    }

    override fun getType(): String = "EMPTY"

    override fun setObjectValue(objectValue: Any?) {
        // 空实现，不存储值
    }
    
    override fun setConfigValue(configValue: String?) {
        // 空实现，EmptyModelField不需要存储配置值
    }
    
    override fun getConfigValue(): String? {
        // 返回null，EmptyModelField没有配置值
        return null
    }

    @JsonIgnore
    fun hasAction(): Boolean = clickRunner != null

    @JsonIgnore
    fun runAction() {
        clickRunner?.run()
    }

}


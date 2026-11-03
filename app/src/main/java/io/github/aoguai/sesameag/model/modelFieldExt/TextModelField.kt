package io.github.aoguai.sesameag.model.modelFieldExt

import io.github.aoguai.sesameag.model.ModelField

/**
 * 文本字段类
 */
open class TextModelField(code: String, name: String, value: String) : ModelField<String>(code, name, value) {

    override fun getType(): String = "TEXT"

    override fun getConfigValue(): String? = value

    override fun setConfigValue(configValue: String?) {
        value = configValue
    }

    /**
     * URL文本字段，点击打开网页
     */
    class UrlTextModelField(code: String, name: String, value: String) : ReadOnlyTextModelField(code, name, value) {

        override fun getType(): String = "URL_TEXT"

    }

    /**
     * 只读文本字段
     */
    open class ReadOnlyTextModelField(code: String, name: String, value: String) : TextModelField(code, name, value) {

        override fun getType(): String = "READ_TEXT"

        override fun setConfigValue(configValue: String?) {
            // 只读，不设置值
        }
    }
}


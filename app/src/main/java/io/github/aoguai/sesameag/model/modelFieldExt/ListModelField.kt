package io.github.aoguai.sesameag.model.modelFieldExt

import io.github.aoguai.sesameag.model.ModelField

/**
 * 表示一个存储字符串列表的字段模型，用于管理和展示列表数据。
 * 提供基本的获取类型、配置值以及视图展示的方法。
 */
open class ListModelField(code: String, name: String, value: MutableList<String>) : ModelField<MutableList<String>>(code, name, value) {

    override fun getType(): String = "LIST"

}


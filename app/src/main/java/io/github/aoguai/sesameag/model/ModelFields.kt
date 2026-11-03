package io.github.aoguai.sesameag.model

import java.util.LinkedHashMap

/**
 * 模型字段集合
 * 继承自LinkedHashMap，用于保存模型的所有字段，保持插入顺序
 */
class ModelFields : LinkedHashMap<String, ModelField<*>>() {
    
    /**
     * 添加字段到集合中
     *
     * @param modelField 要添加的模型字段
     */
    fun addField(modelField: ModelField<*>) {
        put(modelField.code, modelField)
    }
}


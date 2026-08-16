package io.github.aoguai.sesameag.model.modelFieldExt

import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.model.ModelField
import io.github.aoguai.sesameag.model.SelectModelFieldFunc
import io.github.aoguai.sesameag.util.JsonUtil

/**
 * 数据结构说明
 * Map<String, Integer> 表示已选择的数据与已经设置的数量映射关系
 * List<? extends IdAndName> 需要选择的数据
 */
class SelectAndCountModelField : ModelField<MutableMap<String?, Int?>>, SelectModelFieldFunc {
    
    private val selectListFunc: SelectListFunc?
    private val expandValueList: List<MapperEntity>?

    constructor(code: String, name: String, value: MutableMap<String?, Int?>, expandValue: List<MapperEntity>) : super(code, name, value) {
        this.expandValueList = expandValue
        this.selectListFunc = null
        valueType = value.javaClass
    }

    constructor(code: String, name: String, value: MutableMap<String?, Int?>, selectListFunc: SelectListFunc) : super(code, name, value) {
        this.selectListFunc = selectListFunc
        this.expandValueList = null
        valueType = value.javaClass
    }

    constructor(code: String, name: String, value: MutableMap<String?, Int?>, expandValue: List<MapperEntity>, desc: String) : super(code, name, value, desc) {
        this.expandValueList = expandValue
        this.selectListFunc = null
        valueType = value.javaClass
    }

    constructor(code: String, name: String, value: MutableMap<String?, Int?>, selectListFunc: SelectListFunc, desc: String) : super(code, name, value, desc) {
        this.selectListFunc = selectListFunc
        this.expandValueList = null
        valueType = value.javaClass
    }

    override fun getType(): String = "SELECT_AND_COUNT"

    override fun getExpandValue(): List<MapperEntity>? {
        return selectListFunc?.getList() ?: expandValueList
    }

    private fun normalizeId(rawId: Any?): String? {
        return rawId?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeCount(rawCount: Any?): Int {
        return when (rawCount) {
            null -> 0
            is Number -> rawCount.toInt()
            is Boolean -> if (rawCount) 1 else 0
            is String -> rawCount.trim().toIntOrNull() ?: 0
            else -> rawCount.toString().trim().toIntOrNull() ?: 0
        }
    }

    private fun sanitizeSelection(rawSelection: Any?): MutableMap<String?, Int?> {
        val result = LinkedHashMap<String?, Int?>()
        if (rawSelection is Map<*, *>) {
            rawSelection.forEach { (rawId, rawCount) ->
                val normalizedId = normalizeId(rawId) ?: return@forEach
                result[normalizedId] = normalizeCount(rawCount)
            }
        }
        return result
    }

    override fun setObjectValue(objectValue: Any?) {
        if (objectValue == null) {
            reset()
            return
        }
        value = sanitizeSelection(objectValue)
    }
    
    /**
     * 设置配置值
     * 直接解析Map类型，避免父类的类型推断错误
     */
    override fun setConfigValue(configValue: String?) {
        if (configValue.isNullOrBlank()) {
            reset()
            return
        }
        val parsedValue = try {
            JsonUtil.parseObject(configValue, object : TypeReference<LinkedHashMap<String?, Int?>>() {})
        } catch (e: Exception) {
            defaultValue ?: LinkedHashMap()
        }
        setObjectValue(parsedValue)
    }

    override fun clear() {
        value?.clear()
    }

    override fun get(id: String?): Int? {
        return normalizeId(id)?.let { value?.get(it) }
    }

    override fun add(id: String?, count: Int?) {
        val normalizedId = normalizeId(id)
        if (normalizedId != null && count != null) {
            value?.set(normalizedId, count)
        }
    }

    override fun remove(id: String?) {
        normalizeId(id)?.let { value?.remove(it) }
    }

    override fun contains(id: String?): Boolean {
        return normalizeId(id)?.let { value?.containsKey(it) } == true
    }

    fun interface SelectListFunc {
        fun getList(): List<MapperEntity>
    }
}


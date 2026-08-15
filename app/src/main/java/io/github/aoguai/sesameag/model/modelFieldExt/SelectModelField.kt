package io.github.aoguai.sesameag.model.modelFieldExt

import com.fasterxml.jackson.core.type.TypeReference
import org.json.JSONException
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.model.ModelField
import io.github.aoguai.sesameag.model.SelectModelFieldFunc
import io.github.aoguai.sesameag.util.JsonUtil

/**
 * 数据结构说明
 * Set<String> 表示已选择的数据
 * List<? extends IdAndName> 需要选择的数据
 */
class SelectModelField : ModelField<MutableSet<String?>>, SelectModelFieldFunc {
    
    private val selectListFunc: SelectListFunc?
    private val expandValueList: List<MapperEntity>?

    constructor(code: String, name: String, value: MutableSet<String?>, expandValue: List<MapperEntity>) : super(code, name, value) {
        this.expandValueList = expandValue
        this.selectListFunc = null
        valueType = value.javaClass
    }

    constructor(code: String, name: String, value: MutableSet<String?>, selectListFunc: SelectListFunc) : super(code, name, value) {
        this.selectListFunc = selectListFunc
        this.expandValueList = null
        valueType = value.javaClass
    }

    constructor(code: String, name: String, value: MutableSet<String?>, expandValue: List<MapperEntity>, desc: String) : super(code, name, value, desc) {
        this.expandValueList = expandValue
        this.selectListFunc = null
        valueType = value.javaClass
    }

    constructor(code: String, name: String, value: MutableSet<String?>, selectListFunc: SelectListFunc, desc: String) : super(code, name, value, desc) {
        this.selectListFunc = selectListFunc
        this.expandValueList = null
        valueType = value.javaClass
    }

    override fun getType(): String = "SELECT"

    @Throws(JSONException::class)
    override fun getExpandValue(): List<MapperEntity>? {
        return selectListFunc?.getList() ?: expandValueList
    }

    private fun normalizeId(rawId: Any?): String? {
        return rawId?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun sanitizeSelection(rawSelection: Any?): MutableSet<String?> {
        val result = LinkedHashSet<String?>()
        when (rawSelection) {
            is Iterable<*> -> rawSelection.forEach { item ->
                normalizeId(item)?.let { result.add(it) }
            }
            is Array<*> -> rawSelection.forEach { item ->
                normalizeId(item)?.let { result.add(it) }
            }
            else -> normalizeId(rawSelection)?.let { result.add(it) }
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
     * 直接解析Set类型，避免父类的类型推断错误
     */
    override fun setConfigValue(configValue: String?) {
        if (configValue.isNullOrBlank()) {
            reset()
            return
        }
        val parsedValue = try {
            JsonUtil.parseObject(configValue, object : TypeReference<LinkedHashSet<String?>>() {})
        } catch (e: Exception) {
            defaultValue ?: LinkedHashSet()
        }
        setObjectValue(parsedValue)
    }

    override fun clear() {
        value?.clear()
    }

    override fun get(id: String?): Int? = 0

    override fun add(id: String?, count: Int?) {
        normalizeId(id)?.let { value?.add(it) }
    }

    override fun remove(id: String?) {
        normalizeId(id)?.let { value?.remove(it) }
    }

    override fun contains(id: String?): Boolean {
        return normalizeId(id)?.let { value?.contains(it) } == true
    }

    fun interface SelectListFunc {
        @Throws(JSONException::class)
        fun getList(): List<MapperEntity>
    }
}


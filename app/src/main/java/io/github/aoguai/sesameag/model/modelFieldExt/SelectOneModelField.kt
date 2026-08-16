package io.github.aoguai.sesameag.model.modelFieldExt

import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.model.ModelField
import io.github.aoguai.sesameag.model.SelectModelFieldFunc

/**
 * 单选字段，从列表中选择一个选项
 */
class SelectOneModelField : ModelField<String>, SelectModelFieldFunc {
    
    private val selectListFunc: SelectListFunc?
    private val expandValueList: List<MapperEntity>?

    constructor(code: String, name: String, value: String, expandValue: List<MapperEntity>) : super(code, name, value) {
        this.expandValueList = expandValue
        this.selectListFunc = null
    }

    constructor(code: String, name: String, value: String, selectListFunc: SelectListFunc) : super(code, name, value) {
        this.selectListFunc = selectListFunc
        this.expandValueList = null
    }

    override fun getType(): String = "SELECT_ONE"

    override fun getExpandValue(): List<MapperEntity>? {
        return selectListFunc?.getList() ?: expandValueList
    }

    override fun clear() {
        value = defaultValue
    }

    override fun get(id: String?): Int? = 0

    override fun add(id: String?, count: Int?) {
        value = id ?: ""
    }

    override fun remove(id: String?) {
        if (value == id) {
            value = defaultValue
        }
    }

    override fun contains(id: String?): Boolean = value == id

    /**
     * 选择列表函数接口
     */
    fun interface SelectListFunc {
        fun getList(): List<MapperEntity>
    }
}


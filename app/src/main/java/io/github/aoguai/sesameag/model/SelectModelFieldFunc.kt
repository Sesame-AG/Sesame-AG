package io.github.aoguai.sesameag.model

interface SelectModelFieldFunc {
    fun clear()
    fun get(id: String?): Int?
    fun add(id: String?, count: Int?)
    fun remove(id: String?)
    fun contains(id: String?): Boolean
}


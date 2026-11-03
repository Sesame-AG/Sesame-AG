package io.github.aoguai.sesameag.util

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.aoguai.sesameag.storage.StoreAccess
import java.io.File

object DataStore {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private var storageFile: File? = null
    private var listener: (() -> Unit)? = null
    private var stopObserving: (() -> Unit)? = null

    fun setOnChangeListener(listener: () -> Unit) { this.listener = listener }
    fun shutdown() = shutdownWatcher()
    fun shutdownWatcher() { stopObserving?.invoke(); stopObserving = null }

    fun init(dir: File) {
        StoreAccess.transaction {
            check(dir.isDirectory || dir.mkdirs()) { "无法创建配置目录" }
            storageFile = Files.child(dir, "DataStore.json")
            if (!storageFile!!.exists()) save(emptyMap()) else load()
        }
        shutdownWatcher()
        stopObserving = StoreAccess.observe { change ->
            if (change.collection == 1 && (change.name == "DataStore.json" || change.name.isEmpty())) listener?.invoke()
        }
    }

    private fun load(): MutableMap<String, Any> {
        val file = checkNotNull(storageFile) { "DataStore 未初始化" }
        return mapper.readValue(Files.readText(file), object : TypeReference<MutableMap<String, Any>>() {})
    }
    private fun save(data: Map<String, Any>) {
        check(Files.writeAtomically(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data), checkNotNull(storageFile)))
    }

    inline fun <reified T : Any> getOrCreate(key: String): T = getOrCreate(key, object : TypeReference<T>() {})
    fun <T> get(key: String, clazz: Class<T>): T? = StoreAccess.transaction {
        load()[key]?.let { mapper.convertValue(it, clazz) }
    }
    fun <T : Any> getOrCreate(key: String, typeRef: TypeReference<T>): T = StoreAccess.transaction {
        val data = load()
        data[key]?.let { return@transaction mapper.convertValue(it, typeRef) }
        val value: T = defaultValue(typeRef)
        data[key] = value
        save(data)
        value
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> defaultValue(typeRef: TypeReference<T>): T {
        val type = mapper.typeFactory.constructType(typeRef).rawClass
        return when (type) {
            List::class.java -> arrayListOf<Any>()
            Set::class.java -> linkedSetOf<Any>()
            Map::class.java -> linkedMapOf<String, Any>()
            String::class.java -> ""
            Boolean::class.java, Boolean::class.javaObjectType -> false
            Int::class.java, Int::class.javaObjectType -> 0
            Long::class.java, Long::class.javaObjectType -> 0L
            else -> type.getDeclaredConstructor().newInstance()
        } as T
    }
    fun <T : Any> update(key: String, typeRef: TypeReference<T>, transform: (T?) -> T?): T? = StoreAccess.transaction {
        val data = load()
        val current: T? = data[key]?.let { mapper.convertValue(it, typeRef) }
        val updated = transform(current)
        if (updated != current) {
            if (updated == null) data.remove(key) else data[key] = updated
            save(data)
        }
        updated
    }
    fun put(key: String, value: Any) = StoreAccess.transaction { val data = load(); data[key] = value; save(data) }
    fun remove(key: String) = StoreAccess.transaction { val data = load(); if (data.remove(key) != null) save(data) }
}

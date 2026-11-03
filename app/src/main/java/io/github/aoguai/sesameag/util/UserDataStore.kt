package io.github.aoguai.sesameag.util

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.aoguai.sesameag.storage.StoreAccess
import java.io.File

/** Per-account data; all read/modify/write sequences hold the module storage transaction. */
class UserDataStore(val uid: String, private val storageFile: File) {
    companion object {
        private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    private var listener: (() -> Unit)? = null
    private val stopObserving: () -> Unit
    init {
        StoreAccess.transaction {
            if (!storageFile.exists()) check(Files.writeAtomically("{}", storageFile))
            load()
        }
        stopObserving = StoreAccess.observe { change ->
            if (change.collection == 2 && change.owner == uid &&
                (change.name == storageFile.name || change.name.isEmpty())) listener?.invoke()
        }
    }
    fun setOnChangeListener(listener: () -> Unit) { this.listener = listener }
    fun shutdown() { stopObserving() }
    private fun load(): MutableMap<String, Any> = mapper.readValue(Files.readText(storageFile), object : TypeReference<MutableMap<String, Any>>() {})
    private fun save(data: Map<String, Any>) {
        check(Files.writeAtomically(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data), storageFile)) { "保存账号数据失败" }
    }
    inline fun <reified T : Any> getOrCreate(key: String): T = getOrCreate(key, object : TypeReference<T>() {})
    fun <T> get(key: String, clazz: Class<T>): T? = StoreAccess.transaction {
        load()[key]?.let { mapper.convertValue(it, clazz) }
    }
    fun <T : Any> getOrCreate(key: String, typeRef: TypeReference<T>): T = StoreAccess.transaction {
        val data = load()
        data[key]?.let { return@transaction mapper.convertValue(it, typeRef) }
        @Suppress("UNCHECKED_CAST")
        val default = when (val type = mapper.typeFactory.constructType(typeRef).rawClass) {
            List::class.java -> arrayListOf<Any>()
            Set::class.java -> linkedSetOf<Any>()
            Map::class.java -> linkedMapOf<String, Any>()
            String::class.java -> ""
            Boolean::class.java, Boolean::class.javaObjectType -> false
            Int::class.java, Int::class.javaObjectType -> 0
            Long::class.java, Long::class.javaObjectType -> 0L
            else -> type.getDeclaredConstructor().newInstance()
        } as T
        data[key] = default
        save(data)
        default
    }
    fun put(key: String, value: Any) = put(key, value, requirePersistence = true)
    fun put(key: String, value: Any, requirePersistence: Boolean) = StoreAccess.transaction {
        // Every private-store write is atomic and synced, including required pending-action records.
        val data = load(); data[key] = value; save(data)
    }
    fun remove(key: String) = StoreAccess.transaction { val data = load(); if (data.remove(key) != null) save(data) }
    fun setPersistentFlag(flag: String, expiryTimeMillis: Long) = StoreAccess.transaction {
        val flags = getOrCreate("persistent_flags", object : TypeReference<MutableMap<String, Long>>() {}).toMutableMap()
        flags[flag] = expiryTimeMillis
        put("persistent_flags", flags)
    }
    fun hasPersistentFlag(flag: String): Boolean = StoreAccess.transaction {
        val flags = load()["persistent_flags"] as? Map<*, *> ?: return@transaction false
        val expiry = (flags[flag] as? Number)?.toLong() ?: return@transaction false
        expiry == -1L || System.currentTimeMillis() < expiry
    }
    fun removePersistentFlag(flag: String) = StoreAccess.transaction {
        val flags = getOrCreate("persistent_flags", object : TypeReference<MutableMap<String, Long>>() {}).toMutableMap()
        if (flags.remove(flag) != null) put("persistent_flags", flags)
    }
}

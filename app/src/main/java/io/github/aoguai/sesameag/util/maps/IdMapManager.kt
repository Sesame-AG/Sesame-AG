package io.github.aoguai.sesameag.util.maps

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 抽象ID映射工具类
 * 提供通用的线程安全的ID映射功能，并支持单例管理
 */
abstract class IdMapManager {
    
    /**
     * 存储ID映射的并发HashMap
     */
    private val idMap = java.util.concurrent.atomic.AtomicReference<Map<String, String>>(emptyMap())
    
    /**
     * 只读的ID映射
     */
    /**
     * 强制子类提供文件名
     *
     * @return 文件名
     */
    protected abstract fun thisFileName(): String
    
    /**
     * 获取只读的ID映射
     * Kotlin属性访问：map
     * Java方法访问：getMap()
     */
    val map: Map<String, String>
        get() = idMap.get()
    
    /**
     * 根据键获取值
     *
     * @param key 键
     * @return 键对应的值，如果不存在则返回null
     */
    operator fun get(key: String): String? {
        return idMap.get()[key]
    }
    
    /**
     * 添加或更新ID映射
     *
     * @param key   键
     * @param value 值
     */
    fun add(key: String, value: String) {
        idMap.updateAndGet { it + (key to value) }
    }
    
    /**
     * 从ID映射中删除键值对
     *
     * @param key 键
     */
    fun remove(key: String) {
        idMap.updateAndGet { it - key }
    }
    
    /**
     * 从文件加载ID映射
     *
     * @param userId 用户ID
     */
    fun load(userId: String?): Unit = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
        if (userId.isNullOrEmpty()) {
            Log.runtime(TAG, "Skip loading map for empty userId")
            load()
        } else {
            val loadedMap = linkedMapOf<String, String>()
            try {
                val file = Files.getTargetFileofUser(userId, thisFileName())
                checkNotNull(file)
                val body = Files.readFromFile(file)
                if (body.isNotEmpty()) {
                    val objectMapper = ObjectMapper()
                    val newMap: Map<String, String> = objectMapper.readValue(
                        body,
                        object : TypeReference<Map<String, String>>() {}
                    )
                    loadedMap.putAll(newMap)
                }
            } catch (e: Exception) {
                Log.printStackTrace(e)
            }
            idMap.set(loadedMap.toMap())
        }
    }
    
    fun load(): Unit = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
        // Two legacy map locations are both storage resources; migration must not create the
        // new file before deciding whether an older copy contains the user's data.
        val loadedMap = linkedMapOf<String, String>()
        try {
            val newFile = Files.child(CONFIG_DIR, thisFileName())
            val oldFile = Files.child(OLD_CONFIG_DIR, thisFileName())

            // 1. 新文件存在，直接加载
            if (newFile.exists()) {
                val body = Files.readFromFile(newFile)
                if (body.isNotEmpty()) {
                    val objectMapper = ObjectMapper()
                    val newMap: Map<String, String> = objectMapper.readValue(
                        body,
                        object : TypeReference<Map<String, String>>() {}
                    )
                    loadedMap.putAll(newMap)
                }
                if (oldFile.exists()) {
                    oldFile.delete()
                }
                idMap.set(loadedMap.toMap())
                return@transaction
            }

            // 2. 新文件不存在，检查旧文件是否存在
            if (oldFile.exists()) {
                Log.runtime(TAG, "Old configuration file found, migrating to new path...")

                try {
                    val body = Files.readFromFile(oldFile)
                    if (body.isNotEmpty()) {
                        val objectMapper = ObjectMapper()
                        val newMap: Map<String, String> = objectMapper.readValue(
                            body,
                            object : TypeReference<Map<String, String>>() {}
                        )
                        val json = JsonUtil.formatJson(newMap)
                        if (!json.isNullOrEmpty()) {
                            Files.write2File(json, newFile)
                            oldFile.delete()
                            loadedMap.putAll(newMap)
                        }
                    }
                } catch (e: Exception) {
                    Log.error(TAG, "Migration of old configuration files failed：${e.message}")
                }
            }

            // 3. 新旧文件都不存在，初始化默认配置
            if (!newFile.exists()) {
                Log.runtime(TAG, "Configuration file not found, initializing an empty configuration file...")
                val json = JsonUtil.formatJson(loadedMap)
                if (!json.isNullOrEmpty()) {
                    Files.write2File(json, newFile)
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "ID映射管理器初始化失败：", e)
        }
        idMap.set(loadedMap.toMap())
    }
    
    /**
     * 将ID映射保存到文件
     *
     * @param userId 用户ID
     * @return 如果保存成功返回true，否则返回false
     */
    open fun save(userId: String?): Boolean = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
        return@transaction try {
            val file = Files.getTargetFileofUser(userId, thisFileName()) ?: run {
                Log.error("IdMapManager", "无法获取目标文件")
                return@transaction false
            }
            val json = JsonUtil.formatJson(idMap.get())
            Files.write2File(json, file)
        } catch (e: Exception) {
            Log.error("IdMapManager", "保存IdMap失败: ${e.message}")
            Log.printStackTrace("IdMapManager", e)
            false
        }
    }
    
    fun save(): Boolean = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
        return@transaction try {
            val json = JsonUtil.formatJson(idMap.get())
            val file = Files.getTargetFileofDir(CONFIG_DIR, thisFileName())
            Files.write2File(json, file)
        } catch (e: Exception) {
            Log.printStackTrace(e)
            false
        }
    }
    
    /**
     * 清除ID映射
     */
    fun clear() {
        idMap.set(emptyMap())
    }
    
    companion object {
        private val TAG = IdMapManager::class.java.simpleName
        
        private val OLD_CONFIG_DIR: File = Files.MAIN_DIR // 旧配置目录
        private val CONFIG_DIR: File = Files.CONFIG_DIR // 配置目录
        
        private val instances = ConcurrentHashMap<Class<out IdMapManager>, IdMapManager>()
        
        @JvmStatic
        fun <T : IdMapManager> getInstance(clazz: Class<T>): T = checkNotNull(
            clazz.cast(
                instances.computeIfAbsent(clazz) {
                    try { it.getDeclaredConstructor().newInstance() }
                    catch (e: Exception) { throw RuntimeException("Failed to create instance for ${clazz.name}", e) }
                }
            )
        )
    }
}


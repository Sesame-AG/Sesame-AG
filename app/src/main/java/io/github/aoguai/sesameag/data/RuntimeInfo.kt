package io.github.aoguai.sesameag.data

import org.json.JSONException
import org.json.JSONObject
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.UserMap

/**
 * RuntimeInfo 用于存储和管理运行时的配置信息。
 * 该类提供了获取、保存、更新运行时信息的功能，并基于用户 ID 区分不同的配置。
 */
class RuntimeInfo private constructor() {
    
    // 当前用户 ID
    private val userId: String
    
    // 存储所有运行时信息的 JSON 对象
    private val joAll: JSONObject
    
    // 存储当前用户运行时信息的 JSON 对象
    private val joCurrent: JSONObject

    /**
     * 枚举类型，定义所有可以存储和获取的运行时信息的键
     */
    enum class RuntimeInfoKey {
        /** 森林暂停时间 */
        ForestPauseTime
    }

    init {
        userId = UserMap.currentUid ?: ""
        val file = Files.runtimeInfoFile(userId)
        val content = if (file != null) {
            Files.readFromFile(file)
        } else {
            ""
        }
        
        // 如果文件读取成功，则解析 JSON 数据，否则初始化为空的 JSON 对象
        joAll = try {
            JSONObject(content)
        } catch (ignored: Exception) {
            JSONObject()
        }

        // 确保 "joAll" 中包含当前用户的条目
        try {
            if (!joAll.has(userId)) {
                joAll.put(userId, JSONObject())
            }
        } catch (ignored: Exception) {
        }

        // 获取当前用户的运行时信息
        joCurrent = try {
            joAll.getJSONObject(userId)
        } catch (ignored: Exception) {
            JSONObject()
        }
    }

    /**
     * 将运行时信息保存到文件中。
     */
    @Synchronized
    private fun save() {
        val file = Files.runtimeInfoFile(userId) ?: return
        Files.write2File(joAll.toString(), file)
    }

    /**
     * 根据枚举键获取对应的 long 值。如果键不存在，返回默认值 0L。
     *
     * @param key 键（枚举值）
     * @return 对应的 long 值
     */
    fun getLong(key: RuntimeInfoKey): Long = joCurrent.optLong(key.name, 0L)

    /**
     * 使用枚举键将值存储到当前用户的运行时信息中。
     *
     * @param key   键（枚举值）
     * @param value 存储的值
     */
    fun put(key: RuntimeInfoKey, value: Any?) {
        put(key.name, value)
    }

    /**
     * 根据键将值存储到当前用户的运行时信息中。
     *
     * @param key   键
     * @param value 存储的值
     */
    private fun put(key: String, value: Any?) {
        try {
            joCurrent.put(key, value)
            joAll.put(userId, joCurrent)
        } catch (e: JSONException) {
            // 错误日志
            Log.runtime(TAG, "put err:")
            Log.printStackTrace(TAG, e)
        }
        // 保存数据到文件
        save()
    }

    companion object {
        private val TAG = RuntimeInfo::class.java.simpleName

        // 当前单例实例
        @Volatile
        private var instance: RuntimeInfo? = null

        /**
         * 获取 RuntimeInfo 的单例实例。
         * 如果当前用户的 ID 与之前不同，则会重新创建实例。
         *
         * @return 返回 RuntimeInfo 的单例实例
         */
        @JvmStatic
        fun getInstance(): RuntimeInfo {
            val currentInstance = instance
            val currentUserId = UserMap.currentUid ?: ""
            
            if (currentInstance == null || currentInstance.userId != currentUserId) {
                synchronized(this) {
                    val newInstance = instance
                    if (newInstance == null || newInstance.userId != currentUserId) {
                        val newRuntimeInfo = RuntimeInfo()
                        instance = newRuntimeInfo
                        return newRuntimeInfo
                    }
                    return newInstance
                }
            }
            return currentInstance
        }
    }
}


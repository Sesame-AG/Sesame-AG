package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.util.maps.UserMap
import java.util.concurrent.ConcurrentHashMap

/**
 * UserDataStore 管理器
 * 负责维护各账号 UserDataStore 实例的生命周期，确保路径隔离与按需加载。
 */
object UserDataStoreManager {
    private val stores = ConcurrentHashMap<String, UserDataStore>()

    /**
     * 获取指定 UID 的 UserDataStore 实例。
     * 采用“独占式”管理策略：仅为真实有效 UID 创建实例，且切换时自动释放。
     */
    @JvmStatic
    @Synchronized
    fun getInstance(uid: String?): UserDataStore? {
        // 过滤无效或占位 UID
        if (uid.isNullOrEmpty() || uid == "default") return null
        
        // 自动清理逻辑：如果当前缓存了其他真实 UID，则在切换时自动释放
        val it = stores.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key != uid) {
                entry.value.shutdown()
                it.remove()
            }
        }

        return stores.getOrPut(uid) {
            val userDir = Files.getUserConfigDir(uid)
            val file = java.io.File(userDir, "UserDataStore.json")
            UserDataStore(uid, file)
        }
    }

    /**
     * 获取当前账号的 UserDataStore 实例。
     */
    @JvmStatic
    fun getCurrentInstance(): UserDataStore? {
        return getInstance(UserMap.currentUid)
    }

    /**
     * 释放特定用户的 DataStore 实例。
     */
    @JvmStatic
    @Synchronized
    fun releaseInstance(uid: String?) {
        if (uid.isNullOrEmpty()) return
        stores.remove(uid)?.shutdown()
    }

    /**
     * 关机/重启时释放所有实例。
     */
    @JvmStatic
    @Synchronized
    fun shutdownAll() {
        val it = stores.values.iterator()
        while (it.hasNext()) {
            it.next().shutdown()
            it.remove()
        }
    }
}

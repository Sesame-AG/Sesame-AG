package io.github.aoguai.sesameag.storage

import android.content.Context
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.storage.migration.LegacyStorageMigration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

object PrivateStorage {
    data class State(val ready: Boolean = false, val busy: Boolean = false, val message: String = "正在检查配置目录", val cleanupPending: Boolean = false,
        val needsInspection: Boolean = false)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private lateinit var appContext: Context
    val isReady: Boolean get() = mutableState.value.ready
    val root: File get() = File(appContext.noBackupFilesDir, "store")
    internal val context: Context get() = appContext

    @Synchronized fun initialize(context: Context) {
        check(context.packageName == General.MODULE_PACKAGE_NAME)
        appContext = context.applicationContext
        val restoreCleaned = try { StoreAccess.recoverSnapshot(appContext) } catch (failure: Exception) {
            update(State(message = "私有数据恢复未完成：${failure.message}"))
            return
        }
        if (File(root, ".store-ready").isFile) {
            update(State(ready = true, message = if (restoreCleaned) "配置已保存到应用私有目录" else "配置可用，旧恢复副本待下次启动清理",
                cleanupPending = LegacyStorageMigration.hasCleanup(context)))
        } else {
            update(State(message = "需要检查是否有原有配置", needsInspection = true))
        }
    }

    fun requireReady() { if (!isReady) throw IOException("请在模块应用中完成配置迁移：${mutableState.value.message}") }
    internal fun update(state: State) { mutableState.value = state }
    suspend fun inspect() = LegacyStorageMigration.inspect(appContext)
    suspend fun migrate() = LegacyStorageMigration.run(appContext)
    suspend fun backupLegacy(uri: android.net.Uri) = LegacyStorageMigration.backup(appContext, uri)
    suspend fun restoreLegacy(uri: android.net.Uri) = LegacyStorageMigration.restore(appContext, uri)
    suspend fun retryCleanup() = LegacyStorageMigration.cleanup(appContext)

    fun clearContents(): List<String> = StoreAccess.transaction {
        val failures = mutableListOf<String>()
        // The completion marker survives an in-app clear, so legacy leftovers are never imported again.
        val children = root.listFiles() ?: throw IOException("无法读取私有目录")
        children.filter { it.name != ".store-ready" }.forEach {
            if (!it.deleteRecursively()) failures.add(it.path)
        }
        StoreAccess.syncDirectory(root)
        failures
    }
}

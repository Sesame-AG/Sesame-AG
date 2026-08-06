package io.github.aoguai.sesameag.service

import android.content.Context
import android.content.pm.PackageManager
import com.niki.cmd.Shell
import com.niki.cmd.ShizukuShell
import com.niki.cmd.model.bean.ShellResult
import io.github.aoguai.sesameag.service.patch.SafeRootShell
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

class ShellManager(context: Context) {

    companion object {
        private const val TAG = "ShellManager"
    }

    var onStateChanged: ((String) -> Unit)? = null

    // 1. 移除 UserShell，只保留特权 Shell
    private val rootShell = SafeRootShell()
    private val shizukuShell = ShizukuShell(context)

    // 使用 Volatile 确保多线程下的可见性
    @Volatile
    private var selectedShell: Shell? = null
    @Volatile
    private var lastNotifiedType: String? = null
    private val selectionMutex = Mutex()

    /**
     * 获取当前使用的 Shell 名称
     */
    val selectedName: String
        get() = selectedShell?.javaClass?.simpleName ?: "no_executor"


    private fun notifyChange(force: Boolean = false) {
        val currentType = selectedName // 获取当前类型 (SafeRootShell/Shizuku/no_executor)
        if (!force && currentType == lastNotifiedType) {
            return
        }
        lastNotifiedType = currentType
        Log.d(TAG, "Shell状态变更 -> $currentType")
        onStateChanged?.invoke(currentType)
    }

    private suspend fun selectExecutor(notifyUnavailable: Boolean = true) {
        selectionMutex.withLock {
            // 如果已经选中且可用，直接返回
            if (selectedShell != null && selectedShell!!.isAvailable()) return

            Log.d(TAG, "正在寻找可用的 Root 或 Shizuku Shell...")

            val shizukuReady = isShizukuReady()
            val executors = if (shizukuReady) {
                listOf<Shell>(shizukuShell, rootShell)
            } else {
                listOf<Shell>(rootShell, shizukuShell)
            }

            for (shell in executors) {
                try {
                    if (shell is ShizukuShell) {
                        if (!shizukuReady) {
                            Log.d(TAG, "跳过 ShizukuShell: 未授权或服务未运行")
                            continue
                        }
                    }

                    if (shell.isAvailable()) {
                        selectedShell = shell
                        notifyChange() // 🔥 通知：选中了新 Shell
                        Log.i(TAG, "✅ 成功选中 Shell: ${shell.javaClass.simpleName}")
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Shell ${shell.javaClass.simpleName} 检测失败: ${e.message}")
                }
            }

            // 如果都失败了，置空
            selectedShell = null
            if (notifyUnavailable) {
                notifyChange() // 🔥 通知：变成 None 了
            }
        }
    }

    suspend fun refreshSelection(notifyUnavailable: Boolean = true): String {
        selectExecutor(notifyUnavailable)
        return selectedName
    }

    /**
     * 检查 Shizuku 是否就绪
     */
    fun isShizukuReady(): Boolean {
        return try {
            val isBinderAlive = Shizuku.pingBinder()
            val hasPermission = if (isBinderAlive) Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED else false
            Log.d(TAG, "ShizukuCheck: isBinderAlive: $isBinderAlive, hasPermission: $hasPermission, PID: ${android.os.Process.myPid()}")
            return isBinderAlive && hasPermission
        } catch (e: Exception) {
            Log.e(TAG, "isShizukuReady", e)
            false
        }
    }

    /**
     * 执行命令
     */
    suspend fun exec(command: String): ShellResult {
        selectExecutor()
        val shell = selectedShell ?: return ShellResult( "", "No valid Root/Shizuku shell found.",-1)
        Log.d(TAG, "执行命令: $command (via $selectedName)")
        return shell.exec(command, 5_000L)
    }
}


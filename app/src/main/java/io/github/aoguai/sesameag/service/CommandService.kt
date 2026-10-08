package io.github.aoguai.sesameag.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import io.github.aoguai.sesameag.ICallback
import io.github.aoguai.sesameag.ICommandService
import io.github.aoguai.sesameag.IStatusListener
import io.github.aoguai.sesameag.R
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.hook.keepalive.SystemWakeScheduler
import io.github.aoguai.sesameag.ui.MainActivity
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.PermissionUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

/**
 * 命令执行服务（前台服务）
 * 负责通过 ShellManager 执行底层命令
 */
class CommandService : Service() {

    companion object {
        private const val TAG = "CommandService"
        private const val NOTIFICATION_ID = 1001

        // 统一 ID 和 名称
        private const val CHANNEL_ID = "SesameCommandService"
        private const val CHANNEL_NAME = "Sesame-AG 命令服务"
        private const val NOTIFICATION_TITLE = "Sesame-AG 命令服务"
        private const val NOTIFICATION_CONTENT = "服务正在运行，等待执行命令..."

        // 设置命令执行超时时间，例如 15 秒
        private const val COMMAND_TIMEOUT_MS = 15000L
    }

    /**
     * 用于管理跨进程回调的列表
     */
    private val listeners = RemoteCallbackList<IStatusListener>()

    // 使用 SupervisorJob，确保单个任务崩溃不影响整个作用域
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val commandMutex = Mutex()
    private val pendingCommandCount = AtomicInteger(0)

    // ShellManager 实例
    @Volatile
    private var shellManager: ShellManager? = null

    @Volatile
    private var stopWhenIdle = false

    private val binder = object : ICommandService.Stub() {
        override fun executeCommand(command: String, callback: ICallback?) {
            pendingCommandCount.incrementAndGet()
            serviceScope.launch {
                try {
                    commandMutex.withLock {
                        try {
                            ensureShellManager()
                            shellManager?.onStateChanged = { newType ->
                                dispatchStatusChange(newType)
                            }

                            if (shellManager?.selectedName == "no_executor") {
                                if (command == io.github.aoguai.sesameag.util.CommandUtil.DIAGNOSTIC_LOGCAT_COMMAND) {
                                    safeCallbackError(callback, "executor_unavailable")
                                    return@withLock
                                }
                                val refreshedType = shellManager?.refreshSelection(notifyUnavailable = false)
                                if (refreshedType == "no_executor") {
                                    dispatchStatusChange("no_executor")
                                    safeCallbackError(callback, "无 Root/Shizuku 权限")
                                    return@withLock
                                }
                            }

                            // 执行
                            val result = withTimeout(COMMAND_TIMEOUT_MS) {
                                shellManager!!.exec(
                                    command,
                                    refreshExecutor = command != io.github.aoguai.sesameag.util.CommandUtil.DIAGNOSTIC_LOGCAT_COMMAND,
                                )
                            }

                            if (result.isSuccess) {
                                val output = if (command == io.github.aoguai.sesameag.util.CommandUtil.DIAGNOSTIC_LOGCAT_COMMAND) {
                                    val bytes = result.stdout.toByteArray(Charsets.UTF_8)
                                    if (bytes.size > 256 * 1024) {
                                        bytes.copyOf(256 * 1024 - 64).toString(Charsets.UTF_8)
                                            .substringBeforeLast('\n') + "\n[diagnostic_output_truncated]"
                                    } else result.stdout
                                } else result.stdout
                                safeCallbackSuccess(callback, output.trim())
                            } else {
                                // 优化错误信息返回，区分是 Shell 找不到还是命令执行错
                                val errorMsg = if (
                                    command == io.github.aoguai.sesameag.util.CommandUtil.DIAGNOSTIC_LOGCAT_COMMAND &&
                                    result.stderr.contains("Permission denied", ignoreCase = true)
                                ) {
                                    "permission_denied"
                                } else if (result.exitCode == -1 && result.stderr.contains("No valid")) {
                                    "无 Root/Shizuku 权限"
                                } else {
                                    "Code:${result.exitCode}, Err:${result.stderr}"
                                }
                                safeCallbackError(callback, errorMsg)
                            }
                        } catch (e: Exception) {
                            // ... 异常处理 ...
                            Log.e(TAG, "执行异常", e)
                            safeCallbackError(callback,
                                if (e is kotlinx.coroutines.TimeoutCancellationException) "timeout" else e.message ?: "Service Error")
                        }
                    }
                } finally {
                    pendingCommandCount.decrementAndGet()
                    stopSelfIfIdle()
                }
            }
        }


        /**
         * 实现注册
         */
        override fun registerListener(listener: IStatusListener?) {
            listeners.register(listener)
            val currentType = shellManager?.selectedName
            if (currentType.isNullOrBlank() || currentType == "no_executor") {
                listener?.onStatusChanged("loading")
                serviceScope.launch {
                    try {
                        ensureShellManager()
                        shellManager?.onStateChanged = { newType ->
                            dispatchStatusChange(newType)
                        }
                        val refreshedType = shellManager?.refreshSelection(notifyUnavailable = false)
                        if (refreshedType == "no_executor") {
                            listener?.onStatusChanged("no_executor")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "注册监听后刷新 Shell 状态失败", e)
                    }
                }
            } else {
                listener?.onStatusChanged(currentType)
            }
        }

        /**
         * 实现注销
         */
        override fun unregisterListener(listener: IStatusListener?) {
            listeners.unregister(listener)
        }

        override fun schedulePersistentAlarm(lane: Int, triggerAtMs: Long, windowMs: Long, userExact: Boolean): Boolean {
            val callerUid = Binder.getCallingUid()
            @Suppress("DEPRECATION")
            val targetUid = runCatching { packageManager.getPackageUid(General.PACKAGE_NAME, 0) }.getOrNull()
            if (callerUid != Process.myUid() && callerUid != targetUid) {
                throw SecurityException("Untrusted persistent schedule caller")
            }
            require(lane == SystemWakeScheduler.LANE_EXACT || lane == SystemWakeScheduler.LANE_FLEXIBLE)
            val identity = Binder.clearCallingIdentity()
            return try {
                SystemWakeScheduler.scheduleLane(this@CommandService, lane, triggerAtMs, windowMs, userExact)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun isExecutionAllowed(userId: String?): Boolean {
            val activeUserId = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val manager = shellManager ?: return false
            if (manager.selectedName == "no_executor") return false
            return runCatching {
                LsposedServiceManager.refreshScope()
                PermissionUtil.checkFilePermissions(this@CommandService) &&
                    LsposedServiceManager.hasTargetScope(General.PACKAGE_NAME) &&
                    Config.readLegalAcceptedForCurrentVersion(activeUserId)
            }.getOrDefault(false)
        }
    }

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "CommandService onCreate")
        // 立即启动前台服务，避免超时
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        LsposedServiceManager.init()
        // 延迟初始化 ShellManager（不阻塞前台服务启动）
        serviceScope.launch {
            try {
                ensureShellManager()
                shellManager?.onStateChanged = { newType ->
                    dispatchStatusChange(newType)
                }
                shellManager?.refreshSelection(notifyUnavailable = false)
            } catch (e: Exception) {
                Log.e(TAG, "ShellManager 初始化失败", e)
            }
        }
    }

    /**
     * 分发状态给所有客户端
     */
    private fun dispatchStatusChange(type: String) {
        io.github.aoguai.sesameag.util.ModuleDiagnostics.event("executor_selection", "changed", "type=$type")
        val count = listeners.beginBroadcast()
        for (i in 0 until count) {
            try {
                listeners.getBroadcastItem(i).onStatusChanged(type)
            } catch (e: Exception) {
                // 客户端可能死掉了，RemoteCallbackList 会自动清理
            }
        }
        listeners.finishBroadcast()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "CommandService onBind")
        io.github.aoguai.sesameag.util.ModuleDiagnostics.event("command_service", "bound", "action=${intent?.action}")
        stopWhenIdle = false
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopWhenIdle = true
        stopSelfIfIdle()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: 当前模块侧命令服务既承担 UI 命令执行，也会影响“用户划掉模块后台后是否仍被系统保活”。
        // 现象上，用户希望保留必要的后台能力，但在主动划掉任务时应允许真正退出。
        // 后续可增加独立配置开关，决定 CommandService 使用 START_STICKY 还是 START_NOT_STICKY，
        // 以及在 onTaskRemoved 时是否 stopSelf()。
        // 命令服务仅用于模块侧 UI / 命令执行，不需要在任务被用户划掉后持续保活。
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "CommandService onTaskRemoved, stop self")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
//        Log.d(TAG, "CommandService onDestroy")
        stopForeground(STOP_FOREGROUND_REMOVE)
        shellManager = null
        serviceScope.cancel() // 销毁时取消所有协程任务
    }

    /**
     * 确保 ShellManager 已初始化
     */
    private fun ensureShellManager() {
        if (shellManager == null) {
            try {
                shellManager = ShellManager(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "ShellManager init error", e)
            }
        }
    }

    /**
     * 安全回调 Success，处理 DeadObjectException
     */
    private fun safeCallbackSuccess(callback: ICallback?, result: String) {
        if (callback == null) return
        try {
            callback.onSuccess(result)
        } catch (e: RemoteException) {
            Log.w(TAG, "回调失败(客户端已死亡): ${e.message}")
        }
    }

    /**
     * 安全回调 Error，处理 DeadObjectException
     */
    private fun safeCallbackError(callback: ICallback?, error: String) {
        if (callback == null) return
        try {
            callback.onError(error)
        } catch (e: RemoteException) {
            Log.w(TAG, "回调失败(客户端已死亡): ${e.message}")
        }
    }

    /**
     * 创建通知渠道（Android 8.0+ 需要）
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW // 低优先级，不发出声音
        ).apply {
            description = "用于维持模块命令执行服务的运行"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_CONTENT)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // 禁止用户侧滑删除
            .build()
    }

    private fun stopSelfIfIdle() {
        if (!stopWhenIdle || pendingCommandCount.get() > 0) {
            return
        }
        stopSelf()
    }
}


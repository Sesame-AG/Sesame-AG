package io.github.aoguai.sesameag.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import io.github.aoguai.sesameag.ICallback
import io.github.aoguai.sesameag.ICaptureSession
import io.github.aoguai.sesameag.ICommandService
import io.github.aoguai.sesameag.IStatusListener
import io.github.aoguai.sesameag.R
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.hook.AccountSlotRegistry
import io.github.aoguai.sesameag.hook.keepalive.SystemWakeScheduler
import io.github.aoguai.sesameag.ui.MainActivity
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.PermissionUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.github.aoguai.sesameag.util.SerialTaskQueue
import kotlinx.coroutines.withTimeout
import java.io.File
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
    private val commandQueue = SerialTaskQueue()
    private val pendingCommandCount = AtomicInteger(0)

    // ShellManager 实例
    @Volatile
    private var shellManager: ShellManager? = null

    @Volatile
    private var stopWhenIdle = false

    @Volatile
    private var captureSessionProvider: ICaptureSession? = null

    private val binder = object : ICommandService.Stub() {
        override fun beginStoreTransaction(client: IBinder): io.github.aoguai.sesameag.IStoreTransaction {
            val uid = enforceTrustedCaller()
            return io.github.aoguai.sesameag.storage.StoreAccess.begin(client, uid)
        }
        override fun isStoreReady(): Boolean {
            enforceTrustedCaller()
            return io.github.aoguai.sesameag.storage.PrivateStorage.isReady
        }
        override fun registerStoreListener(listener: io.github.aoguai.sesameag.IStoreListener) {
            enforceTrustedCaller()
            io.github.aoguai.sesameag.storage.StoreAccess.register(listener)
        }
        override fun unregisterStoreListener(listener: io.github.aoguai.sesameag.IStoreListener) {
            enforceTrustedCaller()
            io.github.aoguai.sesameag.storage.StoreAccess.unregister(listener)
        }
        override fun openLogStream(channel: String, processName: String): android.os.ParcelFileDescriptor {
            enforceTrustedCaller()
            return io.github.aoguai.sesameag.storage.StoreLogs.open(channel, processName)
        }

        override fun executeCommand(command: String, callback: ICallback?) {
            enforceTrustedCaller()
            pendingCommandCount.incrementAndGet()
            serviceScope.launch {
                try {
                    commandQueue.submit {
                        try {
                            ensureShellManager()
                            shellManager?.onStateChanged = { newType ->
                                dispatchStatusChange(newType)
                            }

                            if (shellManager?.selectedName == "no_executor") {
                                if (command == io.github.aoguai.sesameag.util.CommandUtil.DIAGNOSTIC_LOGCAT_COMMAND) {
                                    safeCallbackError(callback, "executor_unavailable")
                                    return@submit
                                }
                                val refreshedType = shellManager?.refreshSelection(notifyUnavailable = false)
                                if (refreshedType == "no_executor") {
                                    dispatchStatusChange("no_executor")
                                    safeCallbackError(callback, "无 Root/Shizuku 权限")
                                    return@submit
                                }
                            }

                            // 命令执行设协程超时预算，避免无响应任务长期占用串行队列；不是执行前后的固定等待。
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
            enforceTrustedCaller()
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
            enforceTrustedCaller()
            listeners.unregister(listener)
        }

        override fun getPersistentScheduleAlarmIntent(lane: Int): PendingIntent {
            enforceTrustedCaller()
            require(lane == SystemWakeScheduler.LANE_EXACT || lane == SystemWakeScheduler.LANE_FLEXIBLE)
            val identity = Binder.clearCallingIdentity()
            return try {
                SystemWakeScheduler.createAlarmIntent(this@CommandService, lane)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun verifyRuntimeIdentity(expectedModuleUid: Int, expectedModuleSourceDir: String?): Boolean {
            val callerUid = enforceTrustedCaller()
            if (expectedModuleUid !in 0 until 100_000 || expectedModuleSourceDir.isNullOrBlank()) return false
            val identity = Binder.clearCallingIdentity()
            return try {
                @Suppress("DEPRECATION")
                val moduleInfo = packageManager.getApplicationInfo(General.MODULE_PACKAGE_NAME, 0)
                @Suppress("DEPRECATION")
                val targetInfo = packageManager.getApplicationInfo(General.PACKAGE_NAME, 0)
                val moduleApk = moduleInfo.sourceDir?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
                val targetApk = targetInfo.sourceDir?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
                val contextApk = applicationInfo.sourceDir?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
                val accepted = packageName == General.MODULE_PACKAGE_NAME &&
                    applicationInfo.packageName == General.MODULE_PACKAGE_NAME &&
                    applicationInfo.uid == Process.myUid() &&
                    moduleApk != null && moduleApk.isFile && moduleApk == contextApk &&
                    targetApk != null && targetApk.isFile && targetApk != moduleApk &&
                    moduleInfo.uid != targetInfo.uid &&
                    moduleInfo.packageName == General.MODULE_PACKAGE_NAME &&
                    moduleInfo.uid == Process.myUid() &&
                    moduleInfo.uid == expectedModuleUid &&
                    moduleInfo.sourceDir == expectedModuleSourceDir &&
                    targetInfo.packageName == General.PACKAGE_NAME &&
                    targetInfo.uid in 0 until 100_000 &&
                    callerUid == targetInfo.uid &&
                    !targetInfo.sourceDir.isNullOrBlank()
                if (!accepted) {
                    Log.w(TAG, "runtime_identity_rejected: callerUid=$callerUid targetUid=${targetInfo.uid} " +
                        "moduleUid=${moduleInfo.uid} expectedModuleUid=$expectedModuleUid " +
                        "moduleSource=${moduleInfo.sourceDir} expectedModuleSource=$expectedModuleSourceDir")
                }
                accepted
            } catch (e: Exception) {
                Log.e(TAG, "运行身份安装信息查询失败", e)
                false
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun setCaptureSessionProvider(provider: ICaptureSession?) {
            val callerUid = enforceTrustedCaller()
            if (callerUid == Process.myUid()) throw SecurityException("Capture session must come from target")
            captureSessionProvider = provider
        }

        override fun getCaptureSession(): Bundle? {
            enforceTrustedCaller()
            val provider = captureSessionProvider ?: return null
            val identity = Binder.clearCallingIdentity()
            return try {
                if (!provider.asBinder().isBinderAlive ||
                    shellManager?.selectedName.let { it == null || it == "no_executor" } ||
                    !io.github.aoguai.sesameag.storage.PrivateStorage.isReady ||
                    !LsposedServiceManager.hasTargetScope(General.PACKAGE_NAME)
                ) return null
                val session = provider.currentSession ?: return null
                val userId = session.getString("userId")?.takeIf { it.isNotBlank() } ?: return null
                if (session.getLong("sessionEpoch") <= 0L ||
                    !AccountSlotRegistry.isExecutableUser(userId) ||
                    !Config.readLegalAcceptedForCurrentVersion(userId)
                ) return null
                if (captureSessionProvider !== provider) return null
                session
            } catch (e: Exception) {
                Log.w(TAG, "抓包实时会话不可用: ${e.message}")
                null
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun isExecutionAllowed(userId: String?): Boolean {
            enforceTrustedCaller()
            val activeUserId = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val manager = shellManager ?: return false
            if (manager.selectedName == "no_executor") return false
            val identity = Binder.clearCallingIdentity()
            return try {
                runCatching {
                    LsposedServiceManager.refreshScope()
                    io.github.aoguai.sesameag.storage.PrivateStorage.isReady &&
                        LsposedServiceManager.hasTargetScope(General.PACKAGE_NAME) &&
                        Config.readLegalAcceptedForCurrentVersion(activeUserId)
                }.getOrDefault(false)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    /** 在切换协程或清除 Binder 身份前校验调用者，避免转借执行器权限。 */
    private fun enforceTrustedCaller(): Int {
        val callerUid = Binder.getCallingUid()
        if (Process.myUid() !in 0 until 100_000 ||
            packageName != General.MODULE_PACKAGE_NAME || applicationInfo.uid != Process.myUid()
        ) {
            throw SecurityException("Untrusted command service instance")
        }
        if (callerUid == Process.myUid()) return callerUid
        val identity = Binder.clearCallingIdentity()
        try {
            @Suppress("DEPRECATION")
            val target = packageManager.getApplicationInfo(General.PACKAGE_NAME, 0)
            if (callerUid != target.uid || target.uid !in 0 until 100_000 ||
                target.packageName != General.PACKAGE_NAME || target.sourceDir.isNullOrBlank()
            ) {
                throw SecurityException("Untrusted command service caller")
            }
            return callerUid
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            throw SecurityException("Target application is not installed", e)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "CommandService onCreate")
        LsposedServiceManager.init()
        // 身份确认也会绑定此服务；执行器只在实际启动命令服务或注册监听时初始化。
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

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 普通命令路径通过 startForegroundService 启动；仅身份绑定不提升为前台服务。
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        if (shellManager == null) {
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
        captureSessionProvider = null
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


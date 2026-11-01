package io.github.aoguai.sesameag.util

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import io.github.aoguai.sesameag.BuildConfig
import io.github.aoguai.sesameag.ICallback
import io.github.aoguai.sesameag.ICaptureSession
import io.github.aoguai.sesameag.ICommandService
import io.github.aoguai.sesameag.IStatusListener
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.HookUtil
import io.github.aoguai.sesameag.hook.RuntimeIdentityGuard
import io.github.aoguai.sesameag.hook.keepalive.SystemWakeScheduler
import io.github.aoguai.sesameag.service.CommandService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 命令服务客户端工具类
 * 负责与 CommandService 建立连接并通过 AIDL 发送指令
 * 支持从宿主应用（目标应用）进程跨进程绑定到模块的 Service
 */
object CommandUtil {

    private const val TAG = "CommandUtil"
    internal const val DIAGNOSTIC_LOGCAT_COMMAND =
        "logcat -b main -b system -b crash -d -t 64 -v epoch -v uid SesameLog:W LibXposedRuntime:W ApplicationHook:W AndroidRuntime:W '*:S'"
    private const val ACTION_BIND = "io.github.aoguai.sesameag.action.BIND_COMMAND_SERVICE"
    private const val BIND_TIMEOUT_MS = 5000L      // 绑定超时时间
    private const val EXEC_TIMEOUT_MS = 15000L     // 命令执行超时时间
    private const val BIND_RETRY_COUNT = 3
    private const val BIND_RETRY_DELAY_MS = 600L

    // 全局协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- 状态定义 ---
    sealed class ServiceStatus {
        data object Loading : ServiceStatus()
        data class Active(val type: String) : ServiceStatus() // type = "Root" or "Shizuku"
        data object Inactive : ServiceStatus()
        data class Error(val msg: String) : ServiceStatus()
    }

    // 状态流 (UI 直接观察这个)
    private val _serviceStatus = MutableStateFlow<ServiceStatus>(ServiceStatus.Loading)
    val serviceStatus = _serviceStatus.asStateFlow()

    fun hasStorageConnection(): Boolean = commandService?.asBinder()?.isBinderAlive == true

    fun storageService(): ICommandService = commandService?.takeIf { it.asBinder().isBinderAlive }
        ?: throw java.io.IOException("模块存储服务未连接")

    suspend fun connectStorage(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        bindRequested = true
        if (!ensureServiceBound(appContext, identityOnly = true)) { bindRequested = false; return false }
        return try {
            if (!storageService().isStoreReady) {
                bindRequested = false
                unbind(appContext)
                return false
            }
            io.github.aoguai.sesameag.storage.StoreAccess.useRemote()
            io.github.aoguai.sesameag.storage.StoreAccess.attachRemote()
            // 身份确认后可能复用已有连接，不能只依赖连接回调发布主进程会话。
            if (RuntimeIdentityGuard.isTrustedForExecution()) {
                storageService().setCaptureSessionProvider(captureSessionProvider)
            }
            true
        } catch (failure: Exception) {
            android.util.Log.e(TAG, "模块私有存储不可用", failure)
            bindRequested = false
            unbind(appContext)
            false
        }
    }

    // AIDL 接口实例
    @Volatile
    private var commandService: ICommandService? = null
    private val alarmIntents = java.util.concurrent.ConcurrentHashMap<Int, PendingIntent>()

    fun getPersistentScheduleAlarmIntent(lane: Int): PendingIntent? {
        require(lane == SystemWakeScheduler.LANE_EXACT || lane == SystemWakeScheduler.LANE_FLEXIBLE)
        alarmIntents[lane]?.let { return it }
        val service = commandService ?: return null
        return try {
            service.getPersistentScheduleAlarmIntent(lane)?.also { alarmIntents[lane] = it }
        } catch (e: RemoteException) {
            handleServiceLost()
            null
        } catch (e: Exception) {
            Log.e(TAG, "获取持久调度令牌失败", e)
            null
        }
    }

    // 连接状态管理
    private val bindQueue = SerialTaskQueue()
    private val isBound = AtomicBoolean(false)
    private val pendingCommands = AtomicInteger(0)
    private var connectionDeferred: CompletableDeferred<Boolean>? = null
    @Volatile
    private var bindRequested = false
    @Volatile
    private var boundContext: Context? = null
    @Volatile
    private var lastStatusType: String? = null
    @Volatile
    private var identityOnlyBinding = false

    private val captureSessionProvider = object : ICaptureSession.Stub() {
        override fun getCurrentSession(): Bundle? {
            val identity = RuntimeIdentityGuard.trustedIdentity() ?: return null
            if (Binder.getCallingUid() != identity.moduleUid) throw SecurityException("Untrusted capture session caller")
            val token = Binder.clearCallingIdentity()
            return try {
                if (!RuntimeIdentityGuard.isTrustedForExecution() ||
                    commandService?.asBinder()?.isBinderAlive != true
                ) return null
                val session = AccountSessionCoordinator.currentSession() ?: return null
                val loader = ApplicationHook.classLoader ?: return null
                if (!session.legalAccepted || HookUtil.getUserId(loader) != session.userId ||
                    !AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch)
                ) return null
                Bundle().apply {
                    putString("userId", session.userId)
                    putLong("sessionEpoch", session.sessionEpoch)
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
    }

    // --- 监听器实现 ---
    private val statusListener = object : IStatusListener.Stub() {
        override fun onStatusChanged(type: String) {
            val previousType = lastStatusType
            lastStatusType = type
            if (previousType != type || (type != "loading" && type != "no_executor")) {
                Log.i(TAG, "收到服务端状态推送: $type")
            }
            // 更新 Flow (StateFlow 是线程安全的)
            _serviceStatus.value = mapTypeToStatus(type)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "✅ CommandService 已连接: $name")
            ModuleDiagnostics.event("command_bind", "connected", "component=$name")
            try {
                val identityOnly = identityOnlyBinding
                commandService = ICommandService.Stub.asInterface(service)
                    ?: throw IllegalStateException("CommandService binder is null")

                // 1. 监听服务端死亡
                service?.linkToDeath({
                    Log.w(TAG, "💀 CommandService 远程进程死亡")
                    handleServiceLost()
                }, 0)

                // 身份确认不注册执行器监听，也不触发宿主的任务调度。
                if (!identityOnly) {
                    commandService?.registerListener(statusListener)
                }
                // 抓包会话与执行器监听独立，身份/存储连接也需要恢复主进程提供者。
                if (RuntimeIdentityGuard.isTrustedForExecution()) {
                    commandService?.setCaptureSessionProvider(captureSessionProvider)
                }

                if (io.github.aoguai.sesameag.storage.StoreAccess.isRemote) {
                    val storage = checkNotNull(commandService)
                    scope.launch {
                        try {
                            check(storage.isStoreReady) { "模块私有存储尚未就绪" }
                            io.github.aoguai.sesameag.storage.StoreAccess.attachRemote()
                            io.github.aoguai.sesameag.util.Logback.reloadFileLogging()
                        } catch (failure: Exception) {
                            android.util.Log.e(TAG, "恢复私有存储连接失败", failure)
                        }
                    }
                }
                isBound.set(true)
                connectionDeferred?.complete(true)
                if (!identityOnly) {
                    boundContext?.let { appContext ->
                        scope.launch {
                            SystemWakeScheduler.refresh(appContext)
                        }
                    }
                }
                if (!bindRequested && pendingCommands.get() == 0) {
                    boundContext?.let { appContext ->
                        scope.launch {
                            unbind(appContext)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "服务连接初始化失败", e)
                connectionDeferred?.complete(false)
                handleServiceLost()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "❌ CommandService 已断开连接: $name")
            handleServiceLost()
        }
    }

    private fun handleServiceLost(updateStatus: Boolean = true) {
        ModuleDiagnostics.event("command_bind", "lost", "updateStatus=$updateStatus")
        commandService = null
        alarmIntents.clear()
        isBound.set(false)
        connectionDeferred?.complete(false)
        connectionDeferred = null
        lastStatusType = null
        if (updateStatus) {
            _serviceStatus.value = ServiceStatus.Inactive // 更新状态为断开
        }
    }

    private fun mapTypeToStatus(typeName: String): ServiceStatus {
        return when (typeName) {
            "loading" -> ServiceStatus.Loading
            "SafeRootShell", "RootShell" -> ServiceStatus.Active("Root")
            "ShizukuShell" -> ServiceStatus.Active("Shizuku")
            "no_executor", "Unknown" -> ServiceStatus.Inactive
            else -> ServiceStatus.Inactive
        }
    }

    private fun buildServiceIntent(): Intent {
        return Intent().apply {
            component = ComponentName(BuildConfig.APPLICATION_ID, CommandService::class.java.name)
            action = ACTION_BIND
        }
    }

    private fun startCommandService(context: Context, intent: Intent) {
        try {
            (context.applicationContext ?: context).startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startService 失败: ${e.message}")
        }
    }

    /**
     * 触发连接 (供 ViewModel 初始化调用)
     */
    fun connect(context: Context) {
        bindRequested = true
        val appContext = context.applicationContext
        boundContext = appContext
        scope.launch {
            val bound = ensureServiceBound(appContext)
            if (bound && !bindRequested && pendingCommands.get() == 0) {
                unbind(appContext)
            }
        }
    }

    /**
     * 绑定服务 (线程安全)
     */
    private suspend fun ensureServiceBound(context: Context, identityOnly: Boolean = false): Boolean {
        if (isBound.get() && commandService?.asBinder()?.isBinderAlive == true &&
            (identityOnly || !identityOnlyBinding)
        ) {
            return true
        }

        return bindQueue.submit {
            if (isBound.get() && commandService?.asBinder()?.isBinderAlive == true) {
                if (!identityOnly && identityOnlyBinding) {
                    try {
                        startCommandService(context, buildServiceIntent())
                        commandService?.registerListener(statusListener) ?: return@submit false
                        if (RuntimeIdentityGuard.isTrustedForExecution()) {
                            commandService?.setCaptureSessionProvider(captureSessionProvider)
                        }
                        identityOnlyBinding = false
                        val appContext = context.applicationContext ?: context
                        scope.launch { SystemWakeScheduler.refresh(appContext) }
                    } catch (e: Exception) {
                        Log.e(TAG, "启用命令服务执行器监听失败", e)
                        handleServiceLost()
                        return@submit false
                    }
                }
                return@submit true
            }

            // 开始连接前，状态置为 Loading
            _serviceStatus.value = ServiceStatus.Loading

            handleServiceLost(updateStatus = false)
            connectionDeferred = CompletableDeferred()
            val appContext = context.applicationContext ?: context
            boundContext = appContext
            val intent = buildServiceIntent()
            var lastFailure = "not_attempted"
            val bindContext = "caller=${appContext.packageName} uid=${android.os.Process.myUid()} component=${intent.component} action=$ACTION_BIND"

            try {
                repeat(BIND_RETRY_COUNT) { attempt ->
                    identityOnlyBinding = identityOnly
                    if (!identityOnly) startCommandService(appContext, intent)
                    if (attempt > 0) {
                        // 命令服务绑定失败后按公共重试间隔退避，让前次绑定/解绑有机会完成，避免连续重绑。
                        delay(BIND_RETRY_DELAY_MS)
                    }

                    val bindResult = appContext.bindService(
                        intent,
                        serviceConnection,
                        Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                    )

                    if (bindResult) {
                        // bindService 异步回调确认连接，最多等到绑定超时；连接完成会立即唤醒。
                        val success = withTimeoutOrNull(BIND_TIMEOUT_MS) {
                            connectionDeferred?.await()
                        } ?: false

                        if (success) {
                            return@submit true
                        }

                        lastFailure = "bind_timeout"
                        Log.w(TAG, "command_bind reason=$lastFailure attempt=${attempt + 1} timeoutMs=$BIND_TIMEOUT_MS $bindContext")
                        ModuleDiagnostics.event("command_bind", lastFailure, "attempt=${attempt + 1} $bindContext")
                        try {
                            appContext.unbindService(serviceConnection)
                        } catch (_: Exception) {
                            // ignore
                        }
                        handleServiceLost(updateStatus = false)
                        connectionDeferred = CompletableDeferred()
                        return@repeat
                    }

                    lastFailure = "bind_false"
                    Log.w(TAG, "command_bind reason=$lastFailure attempt=${attempt + 1} $bindContext")
                    ModuleDiagnostics.event("command_bind", lastFailure, "attempt=${attempt + 1} $bindContext")
                }

                Log.e(TAG, "command_bind failed reason=$lastFailure $bindContext")
                _serviceStatus.value = ServiceStatus.Error("服务绑定失败")
                return@submit false
            } catch (e: CancellationException) {
                runCatching { appContext.unbindService(serviceConnection) }
                handleServiceLost()
                throw e
            } catch (e: Exception) {
                ModuleDiagnostics.event("command_bind", "exception", "$bindContext error=${e.javaClass.simpleName}")
                Log.e(TAG, "绑定异常 $bindContext", e)
                _serviceStatus.value = ServiceStatus.Error(e.message ?: "未知错误")
                return@submit false
            }
        }
    }

    /**
     * 执行命令
     */
    suspend fun executeCommand(context: Context, command: String): String? = withContext(Dispatchers.IO) {
        pendingCommands.incrementAndGet()
        try {
            if (!ensureServiceBound(context)) return@withContext null
            val service = commandService ?: return@withContext null
            val resultDeferred = CompletableDeferred<String?>()
            val callback = object : ICallback.Stub() {
                override fun onSuccess(output: String) {
                    resultDeferred.complete(output)
                }
                override fun onError(error: String) {
                    Log.e(TAG, "Cmd Error: $error")
                    resultDeferred.complete(null)
                }
            }
            service.executeCommand(command, callback)
            // 等待跨进程命令回调并设执行上限；收到成功/失败结果立即返回，不固定等满超时。
            withTimeoutOrNull(EXEC_TIMEOUT_MS) { resultDeferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            handleServiceLost()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Cmd Exception", e)
            null
        } finally {
            if (pendingCommands.decrementAndGet() == 0 && !bindRequested) unbind(context)
        }
    }

    /** 只消费已就绪的执行器；诊断读取不绑定服务或发起授权。 */
    internal suspend fun readDiagnosticLogcat(): Result<String> = withContext(Dispatchers.IO) {
        val service = commandService
        if (service == null || serviceStatus.value !is ServiceStatus.Active) {
            return@withContext Result.failure(IllegalStateException("executor_unavailable"))
        }
        val response = CompletableDeferred<Result<String>>()
        try {
            service.executeCommand(DIAGNOSTIC_LOGCAT_COMMAND, object : ICallback.Stub() {
                override fun onSuccess(output: String) {
                    response.complete(Result.success(output))
                }
                override fun onError(error: String) {
                    response.complete(Result.failure(java.io.IOException(error.take(256))))
                }
            })
            // 诊断读取同样等待命令回调；超时仅限制无响应命令，不额外延后读取结果。
            withTimeout(EXEC_TIMEOUT_MS) { response.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyRuntimeIdentity(
        context: Context,
        expectedModuleUid: Int,
        expectedModuleSourceDir: String,
    ): Boolean = withContext(Dispatchers.IO) {
        pendingCommands.incrementAndGet()
        try {
            if (!ensureServiceBound(context, identityOnly = true)) return@withContext false
            val service = commandService ?: return@withContext false
            service.verifyRuntimeIdentity(expectedModuleUid, expectedModuleSourceDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            handleServiceLost()
            false
        } catch (e: Exception) {
            Log.e(TAG, "模块运行身份确认失败", e)
            false
        } finally {
            if (pendingCommands.decrementAndGet() == 0 && !bindRequested) unbind(context)
        }
    }

    /** 仅在开始抓包时检查许可，Lite 使用短暂身份连接。 */
    suspend fun authorizeCapture(
        context: Context,
        captureRequested: (String) -> Boolean,
    ): String? = withContext(Dispatchers.IO) {
        if (RuntimeIdentityGuard.trustedIdentity() == null) return@withContext null
        pendingCommands.incrementAndGet()
        try {
            if (!ensureServiceBound(context, identityOnly = true)) return@withContext null
            val session = currentCaptureSession() ?: run {
                Log.w(TAG, "抓包未启动：实时账号会话不可用")
                return@withContext null
            }
            if (!captureRequested(session.userId)) return@withContext null
            if (!isExecutionAllowed(session.userId)) {
                Log.w(TAG, "抓包未启动：当前账号执行条件不满足")
                return@withContext null
            }
            session.userId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "抓包启动许可检查失败: ${e.message}")
            null
        } finally {
            if (pendingCommands.decrementAndGet() == 0 && !bindRequested) unbind(context)
        }
    }

    /** 启动检查从主进程取得账号，不依赖持久化的上次登录账号。 */
    private fun currentCaptureSession(): AccountSessionIdentity? {
        if (RuntimeIdentityGuard.trustedIdentity() == null) return null
        val service = commandService ?: return null
        return try {
            val session = service.captureSession ?: return null
            val userId = session.getString("userId")?.takeIf { it.isNotBlank() } ?: return null
            val epoch = session.getLong("sessionEpoch")
            if (epoch <= 0L) null else AccountSessionIdentity(userId, epoch)
        } catch (e: RemoteException) {
            handleServiceLost()
            null
        } catch (e: Exception) {
            Log.w(TAG, "读取抓包实时会话失败: ${e.message}")
            null
        }
    }

    fun isExecutionAllowed(userId: String): Boolean {
        val service = commandService ?: return false
        return try {
            service.isExecutionAllowed(userId)
        } catch (e: RemoteException) {
            handleServiceLost()
            false
        } catch (e: Exception) {
            Log.e(TAG, "执行条件查询失败", e)
            false
        }
    }

    /**
     * 等待服务状态从 Loading 落定，供宿主进程复用与首页一致的执行器状态。
     */
    suspend fun awaitServiceStatus(
        context: Context,
        timeoutMs: Long = BIND_TIMEOUT_MS + 1500L
    ): ServiceStatus = withContext(Dispatchers.IO) {
        bindRequested = true
        if (!ensureServiceBound(context)) {
            return@withContext _serviceStatus.value
        }

        val currentStatus = _serviceStatus.value
        if (currentStatus !is ServiceStatus.Loading) {
            return@withContext currentStatus
        }

        // 只等 Loading 状态落定，超时后返回当前状态，避免调用方无限等待服务。
        return@withContext withTimeoutOrNull(timeoutMs) {
            serviceStatus.first { it !is ServiceStatus.Loading }
        } ?: _serviceStatus.value
    }

    /**
     * 手动解绑服务
     */
    fun unbind(context: Context) {
        bindRequested = false
        if (pendingCommands.get() > 0) return
        val appContext = boundContext ?: context.applicationContext ?: context
        boundContext = appContext
        if (isBound.compareAndSet(true, false)) {
            try {
                if (RuntimeIdentityGuard.isMainProcess()) {
                    runCatching { commandService?.setCaptureSessionProvider(null) }
                }
                // 身份确认连接没有注册执行器监听。
                if (!identityOnlyBinding) {
                    try { commandService?.unregisterListener(statusListener) } catch (_: Exception) {}
                }

                runCatching { commandService?.unregisterStoreListener(io.github.aoguai.sesameag.storage.StoreAccess.remoteListener()) }
                appContext.unbindService(serviceConnection)
                Log.d(TAG, "已解绑服务")
            } catch (e: Exception) {
                Log.w(TAG, "解绑异常: ${e.message}")
            } finally {
                handleServiceLost()
            }
        }
    }
}


package io.github.aoguai.sesameag.hook

import android.annotation.SuppressLint
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.aoguai.sesameag.BuildConfig
import io.github.aoguai.sesameag.SesameApplication
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.Status.Companion.load
import io.github.aoguai.sesameag.data.Status.Companion.save
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.AlipayVersion
import io.github.aoguai.sesameag.hook.Toast.show
import io.github.aoguai.sesameag.hook.TokenHooker.start
import io.github.aoguai.sesameag.hook.XposedEnv.processName
import io.github.aoguai.sesameag.hook.internal.AlipayMiniMarkHelper
import io.github.aoguai.sesameag.hook.internal.LocationHelper
import io.github.aoguai.sesameag.hook.internal.AuthCodeHelper
import io.github.aoguai.sesameag.hook.internal.SecurityBodyHelper
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.hook.rpc.bridge.NewRpcBridge
import io.github.aoguai.sesameag.hook.rpc.bridge.OldRpcBridge
import io.github.aoguai.sesameag.hook.rpc.bridge.RpcBridge
import io.github.aoguai.sesameag.hook.rpc.bridge.RpcVersion
import io.github.aoguai.sesameag.hook.rpc.intervallimit.RpcIntervalLimit.clearIntervalLimit
import io.github.aoguai.sesameag.hook.server.ModuleHttpServerManager.startIfNeeded
import io.github.aoguai.sesameag.model.BaseModel.Companion.batteryPerm
import io.github.aoguai.sesameag.model.BaseModel.Companion.checkInterval
import io.github.aoguai.sesameag.model.BaseModel.Companion.debugMode
import io.github.aoguai.sesameag.model.BaseModel.Companion.destroyData
import io.github.aoguai.sesameag.model.BaseModel.Companion.execAtTimeList
import io.github.aoguai.sesameag.model.BaseModel.Companion.manualTriggerAutoSchedule
import io.github.aoguai.sesameag.model.BaseModel.Companion.newRpc
import io.github.aoguai.sesameag.model.BaseModel.Companion.sendHookData
import io.github.aoguai.sesameag.model.BaseModel.Companion.sendHookDataUrl
import io.github.aoguai.sesameag.model.BaseModel.Companion.wakenAtTimeList
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.CoroutineTaskRunner
import io.github.aoguai.sesameag.task.MainTask
import io.github.aoguai.sesameag.task.ModelTask.Companion.stopAllTask
import io.github.aoguai.sesameag.util.DataStore.init
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.GlobalThreadPools.execute
import io.github.aoguai.sesameag.util.GlobalThreadPools.shutdownAndRestart
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Log.error
import io.github.aoguai.sesameag.util.Log.printStackTrace
import io.github.aoguai.sesameag.util.Log.record
import io.github.aoguai.sesameag.util.ModuleStatus
import io.github.aoguai.sesameag.util.Notify
import io.github.aoguai.sesameag.util.Notify.stopRunning
import io.github.aoguai.sesameag.util.Notify.updateRunningStatus
import io.github.aoguai.sesameag.util.PermissionUtil.checkBatteryPermissions
import io.github.aoguai.sesameag.util.TimeTriggerEvaluator
import io.github.aoguai.sesameag.util.TimeTriggerParser
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.WorkflowRootGuard
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.util.maps.UserMap.currentUid
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.AutoCloseable
import java.lang.reflect.Method
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

class ApplicationHook {
    var xposedInterface: XposedInterface? = null
        set(value) {
            field = value
            updateFrameworkRuntimeInfo(value)
        }

    private class TaskLock : AutoCloseable {
        private val acquired: Boolean

        init {
            synchronized(taskLock) {
                if (isTaskRunning) {
                    acquired = false
                    throw IllegalStateException("任务已在运行中")
                }
                isTaskRunning = true
                acquired = true
            }
        }

        override fun close() {
            if (acquired) {
                synchronized(taskLock) {
                    isTaskRunning = false
                }
            }
        }
    }

    // --- 入口方法 ---
    fun loadPackage(lpparam: PackageReadyParam) {
        if (General.PACKAGE_NAME != lpparam.packageName) return
        handleHookLogic(
            lpparam.classLoader,
            lpparam.packageName,
            lpparam.applicationInfo.sourceDir
        )
    }

    @SuppressLint("PrivateApi")
    private fun handleHookLogic(loader: ClassLoader?, packageName: String, apkPath: String) {
        val activeLoader = loader ?: return
        classLoader = activeLoader
        val framework = resolveCurrentFrameworkName(activeLoader)

        // 1. 初始化配置读取
        remotePreferences = loadRemotePreferences(framework)

        // 2. 进程检查
        finalProcessName = processName
        if (!shouldHookProcess()) return

        init(Files.CONFIG_DIR)
        if (isHooked) return
        isHooked = true

        // 3. 基础环境 Hook
        ModuleStatusReporter.updateNow(framework = framework, packageName = packageName, reason = "hook_detect")
        VersionHook.installHook(activeLoader)
        initReflection(activeLoader)

        // 4. 核心生命周期 Hook
        hookApplicationAttach(packageName)
        hookLauncherResume()
        hookLoginResume()
        hookServiceLifecycle(apkPath)

        HookUtil.hookOtherService(activeLoader)
    }

    private fun loadRemotePreferences(framework: String): SharedPreferences? {
        val frameworkProperties = getFrameworkRuntimeInfo()?.properties
        if (frameworkProperties == null) {
            logFramework(android.util.Log.INFO, "无法读取 $framework 的 capability，跳过远程偏好读取")
            return null
        }
        if (frameworkProperties.and(XposedInterface.PROP_CAP_REMOTE) == 0L) {
            logFramework(android.util.Log.INFO, "$framework 未声明 remote capability，跳过远程偏好读取")
            return null
        }
        return runCatching {
            requireXposedInterface().getRemotePreferences(SesameApplication.PREFERENCES_KEY)
        }.onFailure {
            logFramework(android.util.Log.WARN, "读取远程偏好失败: ${it.message}", it)
        }.getOrNull()
    }

    private fun shouldHookProcess(): Boolean {
        val isMainProcess = General.PACKAGE_NAME == finalProcessName
        return isMainProcess
//            record(TAG, "跳过辅助进程: $finalProcessName")
    }

    private fun initReflection(loader: ClassLoader) {
        try {
            loadClass(loader, ApplicationHookConstants.AlipayClasses.APPLICATION)
            loadClass(loader, ApplicationHookConstants.AlipayClasses.SOCIAL_SDK)
        } catch (_: Throwable) {
            // ignore
        }

        try {
            @SuppressLint("PrivateApi") val loadedApkClass = loader.loadClass(ApplicationHookConstants.AlipayClasses.LOADED_APK)
            deoptimizeClass(loadedApkClass)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun hookApplicationAttach(packageName: String?) {
        try {
            val attachMethod = findMethod(Application::class.java, "attach", Context::class.java)
            requireXposedInterface().hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                val context = chain.args[0] as? Context ?: return@intercept result
                appContext = context
                mainHandler = Handler(Looper.getMainLooper())
                Log.init(context)
                ensureScheduler()

                SecurityBodyHelper.init(classLoader!!)
                AlipayMiniMarkHelper.init(classLoader!!)
                LocationHelper.init(classLoader!!)
                AuthCodeHelper.init(classLoader!!)

                initVersionInfo(packageName)
                if (VersionHook.hasVersion() && alipayVersion.compareTo(AlipayVersion("10.7.26.8100")) == 0) {
                    HookUtil.bypassAccountLimit(classLoader!!)
                }
                result
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "Hook attach failed", e)
        }
    }

    private fun hookLauncherResume() {
        try {
            val launcherClass = loadClass(classLoader!!, ApplicationHookConstants.AlipayClasses.LAUNCHER_ACTIVITY)
            val onResumeMethod = findMethod(launcherClass, "onResume")
            requireXposedInterface().hook(onResumeMethod).intercept { chain ->
                val result = chain.proceed()
                ApplicationHookConstants.submitEntry("launcher_onResume") {
                            val targetUid = HookUtil.getUserId(classLoader!!) ?: run {
                                show("用户未登录")
                                return@submitEntry
                            }

                            if (!init) {
                                if (initHandler("onResume")) init = true
                                return@submitEntry
                            }

                            val currentUid = currentUid
                            if (targetUid != currentUid) {
                                if (currentUid != null) {
                                    initHandler("user_switch")
                                    lastExecTime = 0
                                    show("用户已切换")
                                    return@submitEntry
                                }
                                refreshFriendsFromAlipayIfNeeded(targetUid, force = false, source = "launcher_onResume")
                            }

                            val recoveredFromOffline = ApplicationResumeCoordinator.tryRecoverOffline("onResume")

                            val resumeAt = System.currentTimeMillis()
                            val resumedFromBackground = ApplicationResumeCoordinator.consumeHostAppBackgrounded()
                            val moduleInitiatedResume = ApplicationResumeCoordinator.consumeModuleForegroundResume(resumeAt)
                            val recentlyReopenedByModule = ApplicationResumeCoordinator.wasRecentlyReopenedByModule(resumeAt)
                            if (
                                resumedFromBackground &&
                                !moduleInitiatedResume &&
                                !recentlyReopenedByModule &&
                                !recoveredFromOffline &&
                                manualTriggerAutoSchedule.value == true
                            ) {
                                record(TAG, "检测到手动回到目标应用，补触发一次任务执行")
                                ApplicationHookCore.requestExecution(
                                    ApplicationHookConstants.TriggerInfo(
                                        type = ApplicationHookConstants.TriggerType.ON_RESUME,
                                        priority = ApplicationHookConstants.TriggerPriority.NORMAL,
                                        reason = "manual_on_resume",
                                        dedupeKey = "manual_on_resume"
                                    )
                                )
                            }
                }
                result
            }
        } catch (t: Throwable) {
            printStackTrace(TAG, "Hook Launcher failed", t)
        }
    }

    /**
     * AlipayLogin 的 onResume 在部分版本/场景下比 LauncherActivity 更稳定（reOpenApp 也会显式拉起该 Activity）。
     * 用于兜底：当出现“需要验证/访问被拒绝”进入离线模式后，用户手动完成验证再回到 App 时可自动退出离线恢复任务链路。
     */
    private fun hookLoginResume() {
        try {
            val loginActivityClass = loadClass(classLoader!!, General.CURRENT_USING_ACTIVITY)
            val onResumeMethod = findMethod(loginActivityClass, "onResume")
            requireXposedInterface().hook(onResumeMethod).intercept { chain ->
                val result = chain.proceed()
                ApplicationHookConstants.submitEntry("login_onResume") {
                            if (!init) return@submitEntry
                            ApplicationResumeCoordinator.tryRecoverOffline("login.onResume")
                }
                result
            }
        } catch (t: Throwable) {
            printStackTrace(TAG, "Hook Login failed", t)
        }
    }

    private fun hookServiceLifecycle(apkPath: String) {
        try {
            val serviceClass = loadClass(classLoader!!, ApplicationHookConstants.AlipayClasses.SERVICE)
            val onCreateMethod = findMethod(serviceClass, "onCreate")
            requireXposedInterface().hook(onCreateMethod).intercept { chain ->
                val result = chain.proceed()
                val appService = chain.getThisObject() as? Service ?: return@intercept result
                if (General.CURRENT_USING_SERVICE != appService.javaClass.getCanonicalName()) {
                    return@intercept result
                }

                service = appService
                appContext = appService.applicationContext
                ensureScheduler()

                ensureMainTask()
                dayCalendar = Calendar.getInstance()
                val initReason = pendingInitReason ?: "service_onCreate"
                if (!init || pendingInit) {
                    if (initHandler(initReason)) {
                        init = true
                    }
                } else {
                    // 已经初始化过，避免重复初始化导致重复 Toast、重置线程池等副作用
                    pendingInit = false
                    pendingInitReason = null
                }
                result
            }

            val onDestroyMethod = findMethod(serviceClass, "onDestroy")
            requireXposedInterface().hook(onDestroyMethod).intercept { chain ->
                val result = chain.proceed()
                val s = chain.getThisObject() as? Service ?: return@intercept result
                if (General.CURRENT_USING_SERVICE == s.javaClass.getCanonicalName()) {
                        // TODO: 目前观察到用户手动划掉支付宝后台时，也会走到这里。
                        // 如果直接 restartByBroadcast()/reOpenApp()，会把“用户主动退出”误判成“异常退出需要恢复”，
                        // 进而出现支付宝/模块后台被反复复活的问题。后续可增加独立配置开关，
                        // 由用户决定“宿主前台服务销毁后是否自动恢复目标应用/执行链路”。
                        updateRunningStatus("目标应用前台服务被销毁")
                        destroyHandler()
                        service = null
                        mainTask = null
                        record(TAG, "🛑 目标应用前台服务已销毁，停止当前模块运行，不再自动重启目标应用")
                    }
                result
            }
        } catch (t: Throwable) {
            printStackTrace(TAG, "Hook Service failed", t)
        }
    }

    private fun initVersionInfo(packageName: String?) {
        if (VersionHook.hasVersion()) {
            alipayVersion = VersionHook.getCapturedVersion() ?: AlipayVersion("")
            record(TAG, "📦 目标应用版本(Hook): $alipayVersion")
        } else {
            try {
                val pInfo: PackageInfo = appContext!!.packageManager.getPackageInfo(packageName!!, 0)
                alipayVersion = AlipayVersion(pInfo.versionName.toString())
            } catch (_: Exception) {
                alipayVersion = AlipayVersion("")
            }
        }
    }

    // --- 广播接收器 ---
    internal class AlipayBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            ApplicationBroadcastDispatcher.handleReceive(context, intent)
        }
    }

    companion object {
        private data class FrameworkRuntimeInfo(
            val name: String?,
            val version: String?,
            val versionCode: Long?,
            val apiVersion: Int?,
            val properties: Long?
        )

        const val TAG: String = "ApplicationHook" // 简化TAG
        var finalProcessName: String? = ""

        // 广播接收器实例，用于注销
        private var mBroadcastReceiver: AlipayBroadcastReceiver? = null

        @JvmField
        var classLoader: ClassLoader? = null

        @JvmField
        @get:JvmStatic
        @Volatile
        var appContext: Context? = null

        // 任务锁
        private val taskLock = Any()

        @Volatile
        private var isTaskRunning = false

        @JvmStatic
        var alipayVersion: AlipayVersion = AlipayVersion("")

        @get:JvmStatic
        @Volatile
        var isHooked: Boolean = false
            private set

        @Volatile
        private var init = false

        @Volatile
        private var pendingInit = false

        @Volatile
        private var pendingInitReason: String? = null

        @Volatile
        private var rootCheckInProgress = false

        @Volatile
        var dayCalendar: Calendar?

        @Volatile
        private var batteryPermissionChecked = false

        @SuppressLint("StaticFieldLeak")
        @Volatile
        var service: Service? = null

        var mainHandler: Handler? = null

        var mainTask: MainTask? = null

        private data class ReloadResumeDecision(
            val reason: String,
            val shouldResume: Boolean,
            val mainTaskRunning: Boolean,
            val pendingTriggers: Int,
            val schedulerTasks: Int
        )

        @Volatile
        private var reloadResumeDecision: ReloadResumeDecision? = null

        private fun ensureMainTask() {
            if (mainTask == null) {
                mainTask = MainTask("主任务") { runMainTaskLogic() }
            }
        }

        internal fun isReadyForExec(): Boolean {
            return init &&
                Config.isLoaded() &&
                Config.isLegalAcceptedForCurrentVersion() &&
                service != null &&
                WorkflowRootGuard.hasGrantedRoot()
        }

        internal fun readinessSummary(): String {
            return "init=$init " +
                "loaded=${Config.isLoaded()} " +
                "legalAccepted=${Config.isLegalAcceptedForCurrentVersion()} " +
                "service=${service != null} " +
                "rootGranted=${WorkflowRootGuard.hasGrantedRoot()} " +
                "mainTask=${mainTask != null} " +
                "mainTaskRunning=${mainTask?.isRunning == true} " +
                "offline=${ApplicationHookConstants.isOffline()} " +
                "pending=${ApplicationHookConstants.pendingTriggerCount()} " +
                "schedulerTasks=${UnifiedScheduler.activeTaskCount()}"
        }

        private fun shouldCaptureReloadState(reason: String): Boolean {
            return reason == "config_reload" || reason == "broadcast_restart"
        }

        private fun captureReloadResumeDecision(reason: String) {
            val running = mainTask?.isRunning == true
            val pending = ApplicationHookConstants.pendingTriggerCount()
            val scheduled = UnifiedScheduler.activeTaskCount()
            val decision = ReloadResumeDecision(
                reason = reason,
                shouldResume = running || pending > 0 || scheduled > 0,
                mainTaskRunning = running,
                pendingTriggers = pending,
                schedulerTasks = scheduled
            )
            reloadResumeDecision = decision
            record(
                TAG,
                "reload snapshot: reason=$reason shouldResume=${decision.shouldResume} " +
                    "mainTaskRunning=$running pending=$pending schedulerTasks=$scheduled"
            )
        }

        internal fun consumeReloadResumeDecision(reason: String): Boolean {
            val decision = reloadResumeDecision
            if (decision == null || decision.reason != reason) {
                if (shouldCaptureReloadState(reason)) {
                    record(TAG, "reload completed without snapshot, keep idle: reason=$reason")
                }
                return false
            }
            reloadResumeDecision = null
            if (!decision.shouldResume) {
                record(
                    TAG,
                    "reload idle preserved: reason=$reason mainTaskRunning=${decision.mainTaskRunning} " +
                        "pending=${decision.pendingTriggers} schedulerTasks=${decision.schedulerTasks}"
                )
                return false
            }
            record(
                TAG,
                "reload resume workflow: reason=$reason mainTaskRunning=${decision.mainTaskRunning} " +
                    "pending=${decision.pendingTriggers} schedulerTasks=${decision.schedulerTasks}"
            )
            return true
        }

        internal fun restartWorkflow(reason: String): Boolean = initHandler(reason)

        internal fun ensureLegalAcceptedForWorkflow(): Boolean = ensureLegalAcceptanceForWorkflow()

        internal fun refreshFriendsFromAlipayIfNeeded(
            userId: String,
            force: Boolean,
            source: String
        ): HookUtil.FriendRefreshResult {
            val safeUserId = userId.trim()
            if (safeUserId.isEmpty()) {
                return HookUtil.FriendRefreshResult(success = false, message = "刷新好友失败：账号为空")
            }
            val loader = classLoader ?: return HookUtil.FriendRefreshResult(
                success = false,
                userId = safeUserId,
                message = "刷新好友失败：Hook classLoader 不可用"
            )

            UserMap.setCurrentUserId(safeUserId)
            runCatching { UserMap.load(safeUserId) }.onFailure {
                Log.printStackTrace(TAG, "刷新好友前加载本地好友快照失败", it)
            }
            runCatching { load(safeUserId, false) }.onFailure {
                Log.printStackTrace(TAG, "刷新好友前加载每日状态失败", it)
            }

            if (!force && Status.hasFlagToday(StatusFlags.FLAG_FRIEND_CENTER_SYNC_TODAY)) {
                val config = FriendRepository.current(safeUserId)
                val message = "好友中心今日已刷新，跳过自动同步[$source]"
                record(TAG, message)
                return HookUtil.FriendRefreshResult(
                    success = true,
                    userId = safeUserId,
                    message = message,
                    profiles = config.profiles.size,
                    groups = config.groups.size
                )
            }

            val result = HookUtil.hookUser(loader)
            if (result.success) {
                Status.setFlagToday(StatusFlags.FLAG_FRIEND_CENTER_SYNC_TODAY)
                record(TAG, "好友中心刷新完成[$source]: ${result.message}")
            } else {
                record(TAG, "好友中心刷新未完成[$source]: ${result.message}")
            }
            return result
        }

        @Volatile
        var rpcBridge: RpcBridge? = null
        private val rpcBridgeLock = Any()

        private var rpcVersion: RpcVersion? = null

        @Volatile
        var lastExecTime: Long = 0

        @Volatile
        var nextExecutionTime: Long = 0

        private val appVisibilityCallbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    ApplicationResumeCoordinator.markHostAppBackgrounded()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() = Unit
        }

        @Volatile
        private var appVisibilityCallbacksRegistered = false

        init {
            dayCalendar = Calendar.getInstance()
            ApplicationHookUtils.resetToMidnight(dayCalendar!!)
        }

        private suspend fun runMainTaskLogic() = withContext(Dispatchers.IO) {
            try {
                TaskLock().use { _ ->
                    if (!init || !Config.isLoaded()) return@withContext
                    if (!WorkflowRootGuard.hasRoot(forceRefresh = true, reason = "main_task")) {
                        record(TAG, "⛔ 可用执行权限不可用，终止主任务执行")
                        ApplicationHookConstants.clearPendingTriggers("root_denied")
                        destroyHandler()
                        return@withContext
                    }
                    if (!ensureLegalAcceptanceForWorkflow()) {
                        return@withContext
                    }

                    val trigger = ApplicationHookConstants.consumePendingTrigger()
                    record(TAG, "🎯 本次执行触发: ${trigger?.summary() ?: "<none>"}")

                    val currentTime = System.currentTimeMillis()
                    val elapsedSinceLastExec = currentTime - lastExecTime
                    if (elapsedSinceLastExec < 2000) {
                        record(TAG, "⚠️ 间隔过短，跳过")
                        // 间隔保护仅用于防抖，重试应尽快（补足到 2s），而不是跟随执行间隔（如 50min）
                        val retryDelayMs = (2000L - elapsedSinceLastExec).coerceAtLeast(200L)
                        UnifiedScheduler.scheduleLongDelay(retryDelayMs, "间隔重试") {
                            ApplicationHookEntry.onIntervalRetry()
                        }
                        return@withContext
                    }

                    val currentUid = currentUid
                    val targetUid = HookUtil.getUserId(classLoader!!)
                    if (targetUid == null || targetUid != currentUid) {
                        reOpenApp()
                        return@withContext
                    }

                    lastExecTime = currentTime

                    val models = Model.modelArray.filterNotNull()
                    CoroutineTaskRunner(models).run(isFirst = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                record(TAG, "⚠️ " + e.message)
            } catch (e: Exception) {
                Log.printStackTrace(TAG, e)
            }
        }

        // --- 辅助方法 ---
        private fun ensureScheduler() {
            if (appContext != null) {
                UnifiedScheduler.initialize(appContext!!)
            }
        }

        fun deoptimizeClass(c: Class<*>) {
            for (m in c.getDeclaredMethods()) {
                if (m.name == "makeApplicationInner") {
                    frameworkInterface?.deoptimize(m)
                }
            }
        }


        fun scheduleNextExecutionInternal(baseTime: Long) {
            try {
                checkInactiveTime()
                val checkInterval = checkInterval.value ?: 0
                val execScheduleField = execAtTimeList
                var delayMillis = checkInterval.toLong()
                var targetTime: Long = 0
                if (execScheduleField.isDisabled()) {
                    record(TAG, "定时执行已关闭，保留轮询间隔调度")
                } else {
                    val intervalTargetTime = baseTime + checkInterval.toLong()
                    val nextPointAt = TimeTriggerEvaluator.nextCheckpointAt(
                        execScheduleField.getTriggerSpec(),
                        baseTime
                    )
                    if (nextPointAt != null && nextPointAt < intervalTargetTime) {
                        record(TAG, "设置定时执行:${TimeUtil.getCommonDate(nextPointAt)}")
                        targetTime = nextPointAt
                        delayMillis = targetTime - baseTime
                    }
                }
                nextExecutionTime = if (targetTime > 0) targetTime else (baseTime + delayMillis)
                ensureScheduler()
                UnifiedScheduler.scheduleLongDelay(delayMillis, "轮询任务") {
                    ApplicationHookEntry.onPollAlarm()
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "scheduleNextExecution failed", e)
            }
        }

        // --- 初始化核心逻辑 ---
        @Synchronized
        private fun initHandler(reason: String): Boolean {
            try {
                // 启动阶段可能出现 Service.onCreate 与 Launcher.onResume 并发触发 initHandler 的竞态：
                // - onResume 线程看到 init=false -> 进入 initHandler 等锁
                // - Service 线程完成初始化并将 init=true
                // - onResume 获锁后会因 init=true 走 destroyHandler()，导致二次初始化与双 Toast
                //
                // 对于“启动类触发”(service_onCreate/onResume)且当前已就绪的场景，直接判定为重复触发并跳过。
                if (init && isReadyForExec() && (reason == "onResume" || reason == "service_onCreate")) {
                    pendingInit = false
                    pendingInitReason = null
                    record(TAG, "✅ 已初始化完成，忽略重复初始化触发: $reason")
                    return true
                }

                if (init) {
                    if (shouldCaptureReloadState(reason)) {
                        captureReloadResumeDecision(reason)
                    } else {
                        reloadResumeDecision = null
                    }
                    destroyHandler()
                }

                // 初始化广播（RPC 调试 / 手动任务等功能依赖）
                try {
                    registerBroadcastReceiver(appContext!!)
                } catch (_: Throwable) { /* ignore */ }
                try {
                    registerAppVisibilityCallbacks(appContext!!)
                } catch (_: Throwable) { /* ignore */ }

                ensureScheduler()
                Model.initAllModel()

                if (service == null) {
                    pendingInit = true
                    pendingInitReason = reason
                    record(TAG, "⏳ Service 未就绪，延后初始化: $reason")
                    return false
                }
                pendingInit = false
                pendingInitReason = null

                val userId = HookUtil.getUserId(classLoader!!)
                if (userId == null) {
                    show("用户未登录")
                    return false
                }

                UserMap.setCurrentUserId(userId)
                load(userId)
                refreshFriendsFromAlipayIfNeeded(userId, force = false, source = reason)
                record(TAG, "Sesame-AG 开始初始化...")
                if (!ensureRootAccessForWorkflow(reason)) {
                    return false
                }

                Config.load(userId)
                if (!Config.isLoaded()) return false
                if (!ensureLegalAcceptanceForWorkflow()) {
                    return false
                }

                // Phase 7：DataStore watcher 生命周期治理（用户切换/重载后重启 watcher，避免丢失跨进程同步能力）
                try {
                    init(Files.CONFIG_DIR)
                } catch (_: Throwable) { /* ignore */ }

                // 仅在用户开启“抓包调试模式”时启动调试 HTTP 服务（release 也可用）
                try {
                    if (debugMode.value == true) {
                        startIfNeeded(8080, "ET3vB^#td87sQqKaY*eMUJXP", processName, General.PACKAGE_NAME)
                    } else {
                        io.github.aoguai.sesameag.hook.server.ModuleHttpServerManager.stop()
                    }
                } catch (_: Throwable) { /* ignore */ }

                Notify.startRunning(service!!)
                setWakenAtTimeAlarm()

                synchronized(rpcBridgeLock) {
                    rpcBridge = if (newRpc.value == true) NewRpcBridge() else OldRpcBridge()
                    rpcBridge!!.load()
                    rpcVersion = rpcBridge!!.getVersion()
                }

                if (newRpc.value == true && debugMode.value == true) {
                    HookUtil.hookRpcBridgeExtension(classLoader!!, sendHookData.value == true, sendHookDataUrl.value ?: "")
                    HookUtil.hookDefaultBridgeCallback(classLoader!!)
                }

                start(userId)
                checkBatteryPermission()

                Model.bootAllModel(classLoader)
                updateDay()

                val successMsg = "Loaded SesameAG " + BuildConfig.VERSION_NAME + "✨"
                record(successMsg)
                show(successMsg)

                ApplicationHookConstants.setOffline(false)
                init = true
                pendingInit = false
                pendingInitReason = null
                ModuleStatusReporter.requestUpdate(reason = "ready")
                ApplicationHookEntry.onInitCompleted(reason)
                return true
            } catch (th: Throwable) {
                printStackTrace(TAG, "startHandler", th)
                return false
            }
        }

        private fun checkBatteryPermission() {
            if (batteryPerm.value != true || batteryPermissionChecked) return
            batteryPermissionChecked = true
            val context = appContext ?: return
            if (!checkBatteryPermissions(context, BuildConfig.APPLICATION_ID)) {
                record(TAG, "模块缺少忽略电池优化权限，请在模块界面内完成授权；自动链路不会主动申请")
            }
        }

        @Synchronized
        fun destroyHandler() {
            try {
                init = false
                pendingInit = false
                pendingInitReason = null
                rootCheckInProgress = false
                ApplicationResumeCoordinator.reset()
                lastExecTime = 0
                try {
                    io.github.aoguai.sesameag.util.DataStore.shutdown()
                } catch (_: Throwable) {
                    // ignore
                }
                shutdownAndRestart()

                if (service != null) {
                    stopHandler()
                    destroyData()
                    Status.unload()
                    stopRunning()
                    clearIntervalLimit()
                    Config.unload()
                    UserMap.unload()
                }

                UnifiedScheduler.cleanup()

                // 注销广播接收器
                unregisterBroadcastReceiver(appContext)
                unregisterAppVisibilityCallbacks(appContext)

                synchronized(rpcBridgeLock) {
                    if (rpcBridge != null) {
                        rpcVersion = null
                        rpcBridge!!.unload()
                        rpcBridge = null
                    }
                    stopAllTask()
                }

                WorkflowRootGuard.invalidate()
            } catch (th: Throwable) {
                printStackTrace(TAG, "stopHandler err:", th)
            }
        }

        private fun ensureRootAccessForWorkflow(reason: String): Boolean {
            if (WorkflowRootGuard.hasGrantedRoot()) {
                pendingInit = false
                pendingInitReason = null
                return true
            }

            pendingInit = true
            pendingInitReason = reason
            if (rootCheckInProgress) {
                return false
            }

            rootCheckInProgress = true
            record(TAG, "⏳ 正在检查执行权限，暂不启动工作流: $reason")
            execute {
                try {
                    val granted = WorkflowRootGuard.hasRoot(forceRefresh = true, reason = reason)
                    if (!granted) {
                        updateRunningStatus("未检测到可用执行权限，已禁止工作流")
                        ApplicationHookConstants.clearPendingTriggers("root_denied")
                        return@execute
                    }

                    val retryReason = pendingInitReason ?: reason
                    rootCheckInProgress = false
                    if (service != null && !init) {
                        record(TAG, "✅ 执行权限检查通过，继续初始化: $retryReason")
                        if (initHandler(retryReason)) {
                            init = true
                        }
                    }
                } catch (th: Throwable) {
                    printStackTrace(TAG, "checkExecutionPermission", th)
                } finally {
                    rootCheckInProgress = false
                }
            }
            return false
        }

        private fun ensureLegalAcceptanceForWorkflow(): Boolean {
            if (!Config.isLoaded()) {
                val activeClassLoader = classLoader ?: return false
                val userId = HookUtil.getUserId(activeClassLoader) ?: return false
                Config.load(userId)
            }
            if (Config.isLegalAcceptedForCurrentVersion()) {
                return true
            }

            pendingInit = false
            pendingInitReason = null
            val message = "未勾选已阅读 LICENSE 与 LEGAL 说明，已禁止工作流"
            record(TAG, "⛔ $message")
            updateRunningStatus(message)
            ApplicationHookConstants.clearPendingTriggers("legal_unaccepted")
            return false
        }

        private fun stopHandler() {
            if (mainTask != null) mainTask!!.stopTask()
            stopAllTask()
        }

        // --- 杂项方法 ---
        private fun checkInactiveTime() {
            if (lastExecTime == 0L) return
            val inactiveTime: Long = System.currentTimeMillis() - lastExecTime
            if (inactiveTime > ApplicationHookConstants.MAX_INACTIVE_TIME) {
                record(TAG, "⚠️ 检测到长时间未执行(" + inactiveTime / 60000 + "m)，重新登录")
                reOpenApp()
            }
        }

        fun updateDay() {
            val now = Calendar.getInstance()
            if (dayCalendar == null || dayCalendar!!.get(Calendar.DAY_OF_MONTH) != now.get(Calendar.DAY_OF_MONTH)) {
                dayCalendar = now.clone() as Calendar
                ApplicationHookUtils.resetToMidnight(dayCalendar!!)
                record(TAG, "日期更新")
                setWakenAtTimeAlarm()
            }
            try {
                save(now)
            } catch (_: Exception) {
            }
        }

        fun sendBroadcast(action: String?) {
            if (appContext != null) appContext!!.sendBroadcast(Intent(action))
        }

        fun sendBroadcastShell(api: String?, message: String?) {
            if (appContext == null) return
            val intent = Intent("io.github.aoguai.sesameag.SHELL")
            intent.putExtra(api, message)
            appContext!!.sendBroadcast(intent, null)
        }

        @JvmStatic
        fun reLoginByBroadcast() {
            sendBroadcast(ApplicationHookConstants.BroadcastActions.RE_LOGIN)
        }

        fun restartByBroadcast() {
            sendBroadcast(ApplicationHookConstants.BroadcastActions.RESTART)
        }

        fun reOpenApp() {
            ensureScheduler()
            UnifiedScheduler.scheduleLongDelay(20000L, "重新登录") {
                try {
                    ApplicationResumeCoordinator.recordReOpenAppLaunch()
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY)
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (appContext != null) appContext!!.startActivity(intent)
                } catch (e: Exception) {
                    error(TAG, "重启Activity失败: " + e.message)
                }
            }
        }

        // --- 定时唤醒 ---
        private fun setWakenAtTimeAlarm() {
            if (appContext == null) return
            ensureScheduler()

            val wakeField = wakenAtTimeList
            if (wakeField.isDisabled()) {
                UnifiedScheduler.cancelNamedTask("每日0点任务")
                UnifiedScheduler.cancelNamedTask("自定义唤醒任务")
                return
            }

            // 1. 每日0点
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            ApplicationHookUtils.resetToMidnight(calendar)
            val delayToMidnight = calendar.getTimeInMillis() - System.currentTimeMillis()

                if (delayToMidnight > 0) {
                    UnifiedScheduler.scheduleLongDelay(delayToMidnight, "每日0点任务") {
                        record(TAG, "⏰ 0点任务触发")
                        updateDay()
                        ApplicationHookEntry.onWakeupMidnight()
                        setWakenAtTimeAlarm() // 递归设置明天
                    }
                }

            // 2. 下一次自定义唤醒（必要时跨日）
            UnifiedScheduler.cancelNamedTask("自定义唤醒任务")
            val nextWakeAt = wakeField.nextPointAt() ?: return
            val now = System.currentTimeMillis()
            val delay = nextWakeAt - now
            if (delay <= 0) return

            val targetCalendar = Calendar.getInstance().apply { timeInMillis = nextWakeAt }
            val secondOfDay = targetCalendar.get(Calendar.HOUR_OF_DAY) * 3600 +
                targetCalendar.get(Calendar.MINUTE) * 60 +
                targetCalendar.get(Calendar.SECOND)
            val timeToken = TimeTriggerParser.formatSecondOfDay(
                secondOfDay,
                useSeconds = targetCalendar.get(Calendar.SECOND) != 0
            )
            UnifiedScheduler.scheduleLongDelay(delay, "自定义唤醒任务") {
                record(TAG, "? 自定义触发: $timeToken")
                ApplicationHookEntry.onWakeupCustom(timeToken)
                setWakenAtTimeAlarm()
            }
            return
        }

        fun registerBroadcastReceiver(context: Context) {
            if (mBroadcastReceiver != null) return  // 防止重复注册

            try {
                mBroadcastReceiver = AlipayBroadcastReceiver()
                val filter = IntentFilter()
                filter.addAction(ApplicationHookConstants.BroadcastActions.RESTART)
                filter.addAction(ApplicationHookConstants.BroadcastActions.EXECUTE)
                filter.addAction(ApplicationHookConstants.BroadcastActions.PRE_WAKEUP)
                filter.addAction(ApplicationHookConstants.BroadcastActions.RE_LOGIN)
                filter.addAction(ApplicationHookConstants.BroadcastActions.RPC_TEST)
                filter.addAction(ApplicationHookConstants.BroadcastActions.MANUAL_TASK)
                filter.addAction(ApplicationHookConstants.BroadcastActions.HOOK_READY)
                filter.addAction(ApplicationHookConstants.BroadcastActions.REFRESH_FRIENDS)
                filter.addAction(ApplicationHookConstants.BroadcastActions.REFRESH_EXCHANGE_OPTIONS)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    ContextCompat.registerReceiver(
                        context,
                        mBroadcastReceiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                }
                record(TAG, "BroadcastReceiver registered")
            } catch (th: Throwable) {
                mBroadcastReceiver = null
                printStackTrace(TAG, "Register Receiver failed", th)
            }
        }

        fun registerAppVisibilityCallbacks(context: Context) {
            if (appVisibilityCallbacksRegistered) return
            try {
                context.registerComponentCallbacks(appVisibilityCallbacks)
                ApplicationResumeCoordinator.clearBackgroundFlag()
                appVisibilityCallbacksRegistered = true
            } catch (th: Throwable) {
                appVisibilityCallbacksRegistered = false
                printStackTrace(TAG, "Register app visibility callbacks failed", th)
            }
        }

        fun unregisterBroadcastReceiver(context: Context?) {
            if (mBroadcastReceiver == null || context == null) return
            try {
                context.unregisterReceiver(mBroadcastReceiver)
                record(TAG, "BroadcastReceiver unregistered")
            } catch (_: Throwable) {
                // ignore: receiver not registered
            } finally {
                mBroadcastReceiver = null
            }
        }

        fun unregisterAppVisibilityCallbacks(context: Context?) {
            if (!appVisibilityCallbacksRegistered || context == null) return
            try {
                context.unregisterComponentCallbacks(appVisibilityCallbacks)
            } catch (_: Throwable) {
                // ignore: callbacks not registered
            } finally {
                appVisibilityCallbacksRegistered = false
                ApplicationResumeCoordinator.clearBackgroundFlag()
            }
        }

        @Volatile
        private var frameworkInterface: XposedInterface? = null

        @Volatile
        private var frameworkRuntimeInfo: FrameworkRuntimeInfo? = null

        @Volatile
        private var remotePreferences: SharedPreferences? = null

        internal fun requireXposedInterface(): XposedInterface {
            return frameworkInterface ?: throw IllegalStateException("XposedInterface 未初始化")
        }

        private fun getFrameworkRuntimeInfo(): FrameworkRuntimeInfo? {
            return frameworkRuntimeInfo
        }

        internal fun resolveCurrentFrameworkName(loader: ClassLoader? = classLoader): String {
            return ModuleStatus.resolveFrameworkName(frameworkRuntimeInfo?.name, loader)
        }

        private fun updateFrameworkRuntimeInfo(xposedInterface: XposedInterface?) {
            frameworkInterface = xposedInterface
            frameworkRuntimeInfo = xposedInterface?.let { framework ->
                FrameworkRuntimeInfo(
                    name = runCatching { framework.frameworkName }.getOrNull(),
                    version = runCatching { framework.frameworkVersion }.getOrNull(),
                    versionCode = runCatching { framework.frameworkVersionCode }.getOrNull(),
                    apiVersion = runCatching { framework.apiVersion }.getOrNull(),
                    properties = runCatching { framework.frameworkProperties }.getOrNull()
                )
            }
            ModuleStatusReporter.setBaseInfo(
                framework = frameworkRuntimeInfo?.name,
                packageName = null
            )
        }

        private fun loadClass(loader: ClassLoader, className: String): Class<*> {
            return Class.forName(className, false, loader)
        }

        private fun findMethod(targetClass: Class<*>, name: String, vararg parameterTypes: Class<*>): Method {
            var current: Class<*>? = targetClass
            while (current != null) {
                runCatching {
                    return current.getDeclaredMethod(name, *parameterTypes).apply {
                        isAccessible = true
                    }
                }
                current = current.superclass
            }
            return targetClass.getMethod(name, *parameterTypes).apply {
                isAccessible = true
            }
        }

        private fun logFramework(priority: Int, message: String, throwable: Throwable? = null) {
            val logger = frameworkInterface ?: return
            if (throwable != null) {
                logger.log(priority, TAG, message, throwable)
            } else {
                logger.log(priority, TAG, message)
            }
        }
    }
}


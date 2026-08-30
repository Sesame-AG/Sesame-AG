package io.github.aoguai.sesameag.hook

import android.annotation.SuppressLint
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInfo
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
import io.github.aoguai.sesameag.hook.internal.AuthCodeHelper
import io.github.aoguai.sesameag.hook.internal.LocationHelper
import io.github.aoguai.sesameag.hook.internal.SecurityBodyHelper
import io.github.aoguai.sesameag.hook.keepalive.PersistentLaunchPolicy
import io.github.aoguai.sesameag.hook.keepalive.PersistentReconcileMode
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleDefaults
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleKind
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.hook.keepalive.ScheduledTaskRouter
import io.github.aoguai.sesameag.hook.keepalive.SystemWakeScheduler
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.hook.rpc.bridge.AriverRpcBridge
import io.github.aoguai.sesameag.hook.rpc.bridge.RpcBridge
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture
import io.github.aoguai.sesameag.hook.rpc.intervallimit.RpcIntervalLimit.clearIntervalLimit
import io.github.aoguai.sesameag.hook.server.ModuleHttpServerManager.startIfNeeded
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.BaseModel.Companion.batteryPerm
import io.github.aoguai.sesameag.model.BaseModel.Companion.checkInterval
import io.github.aoguai.sesameag.model.BaseModel.Companion.debugMode
import io.github.aoguai.sesameag.model.BaseModel.Companion.destroyData
import io.github.aoguai.sesameag.model.BaseModel.Companion.execAtTimeList
import io.github.aoguai.sesameag.model.BaseModel.Companion.manualTriggerAutoSchedule
import io.github.aoguai.sesameag.model.BaseModel.Companion.wakenAtTimeList
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.CoroutineTaskRunner
import io.github.aoguai.sesameag.task.MainTask
import io.github.aoguai.sesameag.task.ModelTask.Companion.stopAllTask
import io.github.aoguai.sesameag.task.antForest.EnergyWaitingManager
import io.github.aoguai.sesameag.util.DataStore.init
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.GlobalThreadPools.execute
import io.github.aoguai.sesameag.util.GlobalThreadPools.shutdownAndRestart
import io.github.aoguai.sesameag.util.LocaleSettingsApplier
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Log.error
import io.github.aoguai.sesameag.util.Log.printStackTrace
import io.github.aoguai.sesameag.util.Log.record
import io.github.aoguai.sesameag.util.Logback
import io.github.aoguai.sesameag.util.ModuleStatus
import io.github.aoguai.sesameag.util.Notify
import io.github.aoguai.sesameag.util.Notify.stopRunning
import io.github.aoguai.sesameag.util.Notify.updateRunningStatus
import io.github.aoguai.sesameag.util.PermissionUtil.checkBatteryPermissions
import io.github.aoguai.sesameag.util.TimeTriggerEvaluator
import io.github.aoguai.sesameag.util.TimeTriggerParser
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.WakeLockManager
import io.github.aoguai.sesameag.util.WorkflowRootGuard
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.util.maps.UserMap.currentUid
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.AutoCloseable
import java.lang.reflect.Method
import java.util.Calendar
import kotlin.concurrent.Volatile

class ApplicationHook {
    private var xposedInterface: XposedInterface? = null
        set(value) {
            field = value
            updateFrameworkRuntimeInfo(value)
        }

    internal fun attachLibXposedRuntime(runtime: XposedInterface) {
        xposedInterface = runtime
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
        if (General.PACKAGE_NAME != lpparam.packageName || !RuntimeIdentityGuard.isPackageReady()) return
        handleHookLogic(
            lpparam.classLoader,
            lpparam.packageName,
            lpparam.applicationInfo.sourceDir,
        )
    }

    @SuppressLint("PrivateApi")
    private fun handleHookLogic(
        loader: ClassLoader?,
        packageName: String,
        apkPath: String,
    ) {
        val activeLoader = loader ?: return
        classLoader = activeLoader
        finalProcessName = processName
        val frameworkInfo = resolveCurrentFrameworkInfo()
        val framework = frameworkInfo.displayName
        val frameworkApiVersion = getFrameworkRuntimeInfo()?.apiVersion ?: 0
        if (!ModuleStatus.isSupportedLsposedFramework(framework, frameworkApiVersion)) {
            remotePreferences = null
            ModuleStatusReporter.updateNow(framework = framework, packageName = packageName, reason = "unsupported_libxposed_runtime")
            record(TAG, "⛔ 检测到不受支持的 $framework 运行时 (API $frameworkApiVersion)，停止安装 Hook")
            return
        }

        // 1. 初始化配置读取
        remotePreferences = loadRemotePreferences(framework)

        // 2. 进程检查。Lite 子进程只安装自然 RPC 抓包，不得进入任务运行时。
        if (shouldHookCaptureOnlyProcess()) {
            installCaptureOnlyHooks(activeLoader)
            hookCaptureOnlyProcessAttach()
            return
        }
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
            Log.runtime(TAG, "无法读取 $framework 的 capability，跳过远程偏好读取")
            return null
        }
        if (frameworkProperties.and(XposedInterface.PROP_CAP_REMOTE) == 0L) {
            Log.runtime(TAG, "$framework 未声明 remote capability，跳过远程偏好读取")
            return null
        }
        return runCatching {
            requireXposedInterface().getRemotePreferences(SesameApplication.PREFERENCES_KEY)
        }.onFailure {
            logFrameworkWarning("读取远程偏好失败: ${it.message}", it)
        }.getOrNull()
    }

    private fun shouldHookProcess(): Boolean =
        RuntimeIdentityGuard.isMainProcess() && RuntimeIdentityGuard.isPackageReady()

    private fun shouldHookCaptureOnlyProcess(): Boolean =
        RuntimeIdentityGuard.isCaptureOnlyProcess() && RuntimeIdentityGuard.isPackageReady()

    private fun initReflection(loader: ClassLoader) {
        try {
            loadClass(loader, ApplicationHookConstants.AlipayClasses.APPLICATION)
            loadClass(loader, ApplicationHookConstants.AlipayClasses.SOCIAL_SDK)
        } catch (_: Throwable) {
            // ignore
        }

        try {
            @SuppressLint("PrivateApi")
            val loadedApkClass = loader.loadClass(ApplicationHookConstants.AlipayClasses.LOADED_APK)
            deoptimizeClass(loadedApkClass)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun installCaptureOnlyHooks(activeLoader: ClassLoader) {
        val installed = RpcTrafficCapture.installForCaptureOnlyProcess(activeLoader)
        val application = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
        application?.let { Log.init(it) }
        Log.runtime(
            TAG,
            "capture_only_hook_install: process=$finalProcessName installed=$installed applicationReady=${application != null}",
        )
    }

    private fun hookCaptureOnlyProcessAttach() {
        try {
            val attachMethod = findMethod(Application::class.java, "attach", Context::class.java)
            requireXposedInterface().hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                val context = chain.args[0] as? Context ?: return@intercept result
                val identityDecision = RuntimeIdentityGuard.verifyApplicationAttach(context)
                if (!identityDecision.accepted || !RuntimeIdentityGuard.isCaptureOnlyProcess()) {
                    android.util.Log.w(TAG, "capture_instance_rejected: ${identityDecision.reasonCode}")
                    return@intercept result
                }
                XposedEnv.runtimeIdentity = RuntimeIdentityGuard.trustedIdentity()
                Log.init(context)
                val activeLoader = classLoader ?: return@intercept result
                val installed = RpcTrafficCapture.installForCaptureOnlyProcess(activeLoader)
                Log.runtime(
                    TAG,
                    "capture_only_hook_ready: process=$finalProcessName installed=$installed",
                )
                result
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "Capture-only attach hook failed", e)
        }
    }

    private fun hookApplicationAttach(packageName: String?) {
        try {
            val attachMethod = findMethod(Application::class.java, "attach", Context::class.java)
            requireXposedInterface().hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                val context = chain.args[0] as? Context ?: return@intercept result
                val application = chain.getThisObject() as? Application
                val identityDecision = RuntimeIdentityGuard.verifyApplicationAttach(context)
                if (!identityDecision.accepted) {
                    android.util.Log.w(TAG, "instance_rejected: ${identityDecision.reasonCode}")
                    return@intercept result
                }
                XposedEnv.runtimeIdentity = RuntimeIdentityGuard.trustedIdentity()
                appContext = context
                mainHandler = Handler(Looper.getMainLooper())
                Log.init(context)
                // Application.attach 位于宿主启动主路径；反射安装和调度恢复延后到入口线程，
                // 避免慢设备在 attach 阶段被这些非必要初始化阻塞。
                ApplicationHookConstants.submitEntry("application_attach_init") {
                    ensureScheduler()
                    application?.let { PageStructureSnapshotter.install(it) }
                    RpcTrafficCapture.install(classLoader!!)

                    SecurityBodyHelper.init(classLoader!!)
                    AlipayMiniMarkHelper.init(classLoader!!)
                    LocationHelper.init(classLoader!!)
                    AuthCodeHelper.init(classLoader!!)

                    initVersionInfo(packageName)
                }
                result
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "Hook attach failed", e)
        }
    }

    private fun consumePersistentAlarmLaunch(intent: Intent?): Boolean {
        val activityIntent = intent ?: return false
        val persistentAlarmLaunch =
            activityIntent.getBooleanExtra(SystemWakeScheduler.EXTRA_PERSISTENT_ALARM_LAUNCH, false)
        if (!persistentAlarmLaunch) return false

        ApplicationResumeCoordinator.recordReOpenAppLaunch()
        val scheduleId =
            activityIntent
                .getStringExtra(SystemWakeScheduler.EXTRA_SCHEDULE_ID)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        pendingPersistentLaunchScheduleId = scheduleId
        pendingPersistentLaunchBatch =
            activityIntent.getBooleanExtra(SystemWakeScheduler.EXTRA_PLANNED_BATCH, false)
        scheduleId?.let { ScheduledTaskRouter.confirmTargetLaunch(it) }
        activityIntent.removeExtra(SystemWakeScheduler.EXTRA_PERSISTENT_ALARM_LAUNCH)
        activityIntent.removeExtra(SystemWakeScheduler.EXTRA_SCHEDULE_ID)
        activityIntent.removeExtra(SystemWakeScheduler.EXTRA_PLANNED_BATCH)
        return true
    }

    private fun consumePersistentAlarmLaunch(activity: android.app.Activity?): Boolean = consumePersistentAlarmLaunch(activity?.intent)

    private fun handlePersistentAlarmLaunch(source: String) {
        if (!init) {
            initHandler("onResume")
            return
        }
        reconcilePersistentAlarmLaunch(source)
    }

    private fun reconcilePersistentAlarmLaunch(source: String) {
        val context = appContext
        if (isReadyForExec() && context != null) {
            record(TAG, "持久调度唤醒目标应用，检查到期任务: $source")
            val launchScheduleId = pendingPersistentLaunchScheduleId
            if (pendingPersistentLaunchBatch) {
                val dueCount = PersistentScheduleRegistry.fireDueSchedules(context, "$source:planned_batch")
                record(TAG, "持久调度物理唤醒批量路由完成，计划数=$dueCount")
                pendingPersistentLaunchBatch = false
                pendingPersistentLaunchScheduleId = null
                return
            }
            if (!launchScheduleId.isNullOrBlank()) {
                val schedule = PersistentScheduleRegistry.get(launchScheduleId)
                if (schedule != null) {
                    if (ScheduledTaskRouter.fire(context, schedule, source)) {
                        pendingPersistentLaunchScheduleId = null
                    }
                } else {
                    record(TAG, "持久调度唤醒目标应用但任务不存在[$launchScheduleId]")
                    pendingPersistentLaunchScheduleId = null
                }
                return
            }
            UnifiedScheduler.reconcilePersistentSchedules(
                context,
                mode = PersistentReconcileMode.FIRE_ALARM_DUE,
            )
        } else {
            record(TAG, "持久调度唤醒目标应用但工作流未就绪[$source]: ${readinessSummary()}")
        }
    }

    private fun hookLauncherResume() {
        try {
            val launcherClass = loadClass(classLoader!!, ApplicationHookConstants.AlipayClasses.LAUNCHER_ACTIVITY)
            hookPersistentAlarmNewIntent(launcherClass, "launcher_onNewIntent")
            val onResumeMethod = findMethod(launcherClass, "onResume")
            requireXposedInterface().hook(onResumeMethod).intercept { chain ->
                val result = chain.proceed()
                val persistentAlarmLaunch =
                    consumePersistentAlarmLaunch(chain.getThisObject() as? android.app.Activity)
                ApplicationResumeCoordinator.markHostAppForegrounded()
                ApplicationHookConstants.submitEntry("launcher_onResume") {
                    val targetUid =
                        HookUtil.getUserId(classLoader!!) ?: run {
                            show("用户未登录")
                            return@submitEntry
                        }

                    if (!init) {
                        if (persistentAlarmLaunch) {
                            handlePersistentAlarmLaunch("launcher_onResume:init")
                        } else if (initHandler("onResume")) {
                            init = true
                        }
                        return@submitEntry
                    }

                    val activeSessionUserId = AccountSessionCoordinator.currentUserId() ?: currentUid
                    if (targetUid != activeSessionUserId) {
                        if (activeSessionUserId != null) {
                            initHandler("user_switch")
                            lastExecTime = 0
                            show("用户已切换")
                            return@submitEntry
                        }
                        refreshFriendsFromAlipayIfNeeded(targetUid, force = false, source = "launcher_onResume")
                    }

                    val recoveredFromOffline = ApplicationResumeCoordinator.tryRecoverOffline("onResume")
                    if (persistentAlarmLaunch) {
                        reconcilePersistentAlarmLaunch("launcher_onResume")
                    }

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
                                dedupeKey = "manual_on_resume",
                                ownerUserId = AccountSessionCoordinator.currentUserId(),
                                sessionEpoch = AccountSessionCoordinator.currentSessionEpoch(),
                            ),
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
            hookPersistentAlarmNewIntent(loginActivityClass, "login_onNewIntent")
            val onResumeMethod = findMethod(loginActivityClass, "onResume")
            requireXposedInterface().hook(onResumeMethod).intercept { chain ->
                val result = chain.proceed()
                val persistentAlarmLaunch =
                    consumePersistentAlarmLaunch(chain.getThisObject() as? android.app.Activity)
                ApplicationResumeCoordinator.markHostAppForegrounded()
                ApplicationHookConstants.submitEntry("login_onResume") {
                    if (!init) {
                        if (persistentAlarmLaunch) {
                            handlePersistentAlarmLaunch("login_onResume:init")
                        } else if (initHandler("onResume")) {
                            init = true
                        }
                        return@submitEntry
                    }
                    if (persistentAlarmLaunch) {
                        reconcilePersistentAlarmLaunch("login_onResume")
                    }
                    ApplicationResumeCoordinator.tryRecoverOffline("login.onResume")
                }
                result
            }
        } catch (t: Throwable) {
            printStackTrace(TAG, "Hook Login failed", t)
        }
    }

    private fun hookPersistentAlarmNewIntent(
        activityClass: Class<*>,
        source: String,
    ) {
        runCatching { findMethod(activityClass, "onNewIntent", Intent::class.java) }
            .onSuccess { method ->
                requireXposedInterface().hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.getThisObject() as? android.app.Activity ?: return@intercept result
                    if (!activityClass.isAssignableFrom(activity.javaClass)) {
                        return@intercept result
                    }
                    val newIntent = chain.args.getOrNull(0) as? Intent
                    val persistentAlarmLaunch = consumePersistentAlarmLaunch(newIntent)
                    if (persistentAlarmLaunch) {
                        activity.setIntent(newIntent)
                    }
                    if (persistentAlarmLaunch) {
                        ApplicationHookConstants.submitEntry(source) {
                            handlePersistentAlarmLaunch(source)
                        }
                    }
                    result
                }
            }.onFailure {
                record(TAG, "未找到 $source 钩子入口: ${it.message}")
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
                // 宿主 Service 生命周期回调必须快速返回；完整初始化仅在入口后台线程执行。
                ApplicationHookConstants.submitEntry("service_onCreate") {
                    if (service !== appService) return@submitEntry
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
                }
                result
            }

            val onDestroyMethod = findMethod(serviceClass, "onDestroy")
            requireXposedInterface().hook(onDestroyMethod).intercept { chain ->
                val result = chain.proceed()
                val s = chain.getThisObject() as? Service ?: return@intercept result
                if (General.CURRENT_USING_SERVICE == s.javaClass.getCanonicalName()) {
                    // TODO: 目前观察到用户手动划掉目标应用后台时，也会走到这里。
                    // 如果直接 reOpenApp()，会把“用户主动退出”误判成“异常退出需要恢复”，
                    // 进而出现目标应用/模块后台被反复复活的问题。后续可增加独立配置开关，
                    // 由用户决定“宿主前台服务销毁后是否自动恢复目标应用/执行链路”。
                    updateRunningStatus("目标应用前台服务被销毁")
                    destroyHandler()
                    record(TAG, "🛑 目标应用前台服务已销毁，已投递后台停止任务，不再自动重启目标应用")
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
        override fun onReceive(
            context: Context?,
            intent: Intent,
        ) {
            ApplicationBroadcastDispatcher.handleReceive(context, intent)
        }
    }

    companion object {
        private data class FrameworkRuntimeInfo(
            val name: String?,
            val version: String?,
            val versionCode: Long?,
            val apiVersion: Int?,
            val properties: Long?,
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
            val schedulerTasks: Int,
        )

        @Volatile
        private var reloadResumeDecision: ReloadResumeDecision? = null

        @Volatile
        private var pendingPersistentLaunchScheduleId: String? = null

        @Volatile
        private var pendingPersistentLaunchBatch: Boolean = false

        private fun ensureMainTask() {
            if (mainTask == null) {
                mainTask = MainTask("主任务") { runMainTaskLogic() }
            }
        }

        internal fun isReadyForExec(): Boolean {
            val session = AccountSessionCoordinator.currentSession()
            return RuntimeIdentityGuard.isTrustedForExecution() &&
                AccountSlotRegistry.isExecutableUser(session?.userId) &&
                init &&
                Config.isLoaded() &&
                service != null &&
                WorkflowRootGuard.hasGrantedRoot() &&
                !ApplicationHookConstants.isOffline() &&
                session?.workflowAllowed == true &&
                session.userId == currentUid
        }

        internal fun readinessSummary(): String {
            val session = AccountSessionCoordinator.currentSession()
            return "init=$init " +
                "loaded=${Config.isLoaded()} " +
                "legalAccepted=${session?.legalAccepted ?: Config.isLegalAcceptedForCurrentVersion()} " +
                "service=${service != null} " +
                "rootGranted=${WorkflowRootGuard.hasGrantedRoot()} " +
                "sessionUser=${session?.userId ?: "null"} " +
                "sessionEpoch=${session?.sessionEpoch ?: 0L} " +
                "switching=${AccountSessionCoordinator.isSwitching()} " +
                "workflowAllowed=${session?.workflowAllowed ?: false} " +
                "mainTask=${mainTask != null} " +
                "mainTaskRunning=${mainTask?.isRunning == true} " +
                "offline=${ApplicationHookConstants.isOffline()} " +
                "pending=${ApplicationHookConstants.pendingTriggerCount()} " +
                "schedulerTasks=${UnifiedScheduler.activeTaskCount()}"
        }

        internal fun discardCoveredAlarmPollTriggers(): Int {
            val runStartedAt = mainTaskRunStartedAtMs
            val runCompletedAt = mainTaskRunCompletedAtMs
            val scheduleUpdatedAt = mainTaskNextScheduleUpdatedAtMs
            if (runStartedAt <= 0 || runCompletedAt <= 0 || scheduleUpdatedAt < runStartedAt) {
                return 0
            }

            val removed =
                ApplicationHookConstants.removePendingTriggers("alarm_poll_covered_by_main_task") { trigger ->
                    trigger.type == ApplicationHookConstants.TriggerType.ALARM_POLL &&
                        trigger.priority == ApplicationHookConstants.TriggerPriority.LOW &&
                        trigger.createdAtMs <= runCompletedAt
                }
            val context = appContext
            removed.forEach { trigger ->
                trigger.persistentScheduleId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { scheduleId -> PersistentScheduleRegistry.markFired(context, scheduleId) }
            }
            return removed.size
        }

        private fun shouldCaptureReloadState(reason: String): Boolean = reason == "config_reload" || reason == "broadcast_restart"

        private fun captureReloadResumeDecision(reason: String) {
            val running = mainTask?.isRunning == true
            val pending = ApplicationHookConstants.pendingTriggerCount()
            val scheduled = UnifiedScheduler.activeTaskCount()
            val decision =
                ReloadResumeDecision(
                    reason = reason,
                    // Scheduled timers also exist in the normal waiting state; they must not turn
                    // a config reload into an immediate main workflow run.
                    shouldResume = running || pending > 0,
                    mainTaskRunning = running,
                    pendingTriggers = pending,
                    schedulerTasks = scheduled,
                )
            reloadResumeDecision = decision
            record(
                TAG,
                "reload snapshot: reason=$reason shouldResume=${decision.shouldResume} " +
                    "mainTaskRunning=$running pending=$pending schedulerTasks=$scheduled",
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
                        "pending=${decision.pendingTriggers} schedulerTasks=${decision.schedulerTasks}",
                )
                return false
            }
            record(
                TAG,
                "reload resume workflow: reason=$reason mainTaskRunning=${decision.mainTaskRunning} " +
                    "pending=${decision.pendingTriggers} schedulerTasks=${decision.schedulerTasks}",
            )
            return true
        }

        internal fun restartWorkflow(reason: String): Boolean = initHandler(reason)

        internal fun ensureLegalAcceptedForWorkflow(): Boolean = ensureLegalAcceptanceForWorkflow()

        internal fun refreshFriendsFromAlipayIfNeeded(
            userId: String,
            force: Boolean,
            source: String,
        ): HookUtil.FriendRefreshResult {
            val safeUserId = userId.trim()
            if (safeUserId.isEmpty()) {
                return HookUtil.FriendRefreshResult(success = false, message = "刷新好友失败：账号为空")
            }
            val loader =
                classLoader ?: return HookUtil.FriendRefreshResult(
                    success = false,
                    userId = safeUserId,
                    message = "刷新好友失败：Hook classLoader 不可用",
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
                AccountSessionCoordinator.ensureActiveUserSnapshot(safeUserId, loader)
                val message = "好友中心今日已刷新，跳过自动同步[$source]"
                record(TAG, message)
                return HookUtil.FriendRefreshResult(
                    success = true,
                    userId = safeUserId,
                    message = message,
                    profiles = config.profiles.size,
                    groups = config.groups.size,
                )
            }

            val result = HookUtil.hookUser(loader)
            AccountSessionCoordinator.ensureActiveUserSnapshot(safeUserId, loader)
            if (result.success) {
                Status.setFlagToday(StatusFlags.FLAG_FRIEND_CENTER_SYNC_TODAY)
                record(TAG, "好友中心刷新完成[$source]: ${result.message}")
            } else {
                record(TAG, "好友中心刷新未完成[$source]: ${result.message}")
            }
            return result
        }

        /**
         * 冷启动首次好友实时同步的后移延时(毫秒)。
         * 用于规避目标应用社交库(AliAccountDaoOp)在冷启动期未加载完时 getAllFriends() 返回“非空但不完整”
         * 快照被误判为可信全量。可按实测调大；现有“重新登录”延时为 20s 作参考。
         */
        private const val FRIEND_CENTER_FIRST_SYNC_DEFER_MS: Long = 30_000L
        private val MIN_SUPPORTED_RPC_VERSION = AlipayVersion("10.3.96.8100")

        private fun ensureRpcVersionSupported(): Boolean {
            val currentVersion = alipayVersion
            if (currentVersion.versionString.isBlank()) {
                record(TAG, "⚠️ 无法识别目标应用版本，继续尝试初始化 RPC")
                return true
            }
            if (currentVersion >= MIN_SUPPORTED_RPC_VERSION) {
                return true
            }

            val message = "目标应用版本过低，不支持当前 RPC 结构，最低版本 ${MIN_SUPPORTED_RPC_VERSION.versionString}"
            record(TAG, message)
            Log.runtime(TAG, "rpc unsupported host version: current=$currentVersion min=${MIN_SUPPORTED_RPC_VERSION.versionString}")
            updateRunningStatus(message)
            ApplicationHookConstants.clearPendingTriggers("unsupported_host_version")
            AccountSessionCoordinator.blockWorkflow(appContext, "unsupported_host_version")
            return false
        }

        /**
         * 后移“从目标应用侧获取好友实时快照”的时机：init 阶段不再同步拉取，改为延时后在后台执行，
         * 让社交库就绪概率更高时再读取。手动刷新(force=true)走广播同步路径，不经此后移。
         */
        private fun scheduleDeferredFriendCenterSync(
            userId: String,
            reason: String,
        ) {
            val safeUserId = userId.trim()
            if (safeUserId.isEmpty()) return
            UnifiedScheduler.scheduleLongDelay(
                FRIEND_CENTER_FIRST_SYNC_DEFER_MS,
                "好友中心首次同步",
            ) {
                // scheduleLongDelay 系统计时模式在 Main 执行，这里只做轻量派发，
                // 把 hookUser(反射+DB读+JSON写)放到后台单线程入口执行，避免阻塞主线程。
                ApplicationHookConstants.submitEntry("friend_center_first_sync") {
                    // 后移期间账号可能切换 → 执行前重新校验当前账号，避免给旧账号误同步。
                    val activeUserId = AccountSessionCoordinator.currentUserId() ?: currentUid
                    val liveUserId = runCatching { classLoader?.let { HookUtil.getUserId(it) } }.getOrNull()
                    if (safeUserId != activeUserId || (liveUserId != null && liveUserId != safeUserId)) {
                        record(
                            TAG,
                            "好友中心首次同步已取消：账号已切换(expect=$safeUserId, active=$activeUserId, live=$liveUserId)",
                        )
                        return@submitEntry
                    }
                    refreshFriendsFromAlipayIfNeeded(safeUserId, force = false, source = "$reason:deferred")
                }
            }
        }

        @Volatile
        var rpcBridge: RpcBridge? = null
        private val rpcBridgeLock = Any()

        @Volatile
        var lastExecTime: Long = 0

        @Volatile
        var nextExecutionTime: Long = 0

        @Volatile
        private var mainTaskRunStartedAtMs: Long = 0

        @Volatile
        private var mainTaskRunCompletedAtMs: Long = 0

        @Volatile
        private var mainTaskNextScheduleUpdatedAtMs: Long = 0

        private val appVisibilityCallbacks =
            object : ComponentCallbacks2 {
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

        private suspend fun runMainTaskLogic() =
            withContext(Dispatchers.IO) {
                var activePersistentScheduleId: String? = null
                try {
                    TaskLock().use { _ ->
                        if (!init || !Config.isLoaded()) return@withContext
                        if (!WorkflowRootGuard.hasRoot(forceRefresh = true, reason = "main_task")) {
                            record(TAG, "⛔ 可用执行权限不可用，终止主任务执行")
                            ApplicationHookConstants.clearPendingTriggers("root_denied")
                            execute {
                                destroyHandler()
                            }
                            return@withContext
                        }
                        if (!ensureLegalAcceptanceForWorkflow()) {
                            return@withContext
                        }

                        val trigger = ApplicationHookConstants.consumePendingTrigger()
                        activePersistentScheduleId = trigger?.persistentScheduleId?.takeIf { it.isNotBlank() }
                        if (trigger != null && !AccountSessionCoordinator.shouldAcceptTrigger(trigger)) {
                            record(TAG, "⏭ 丢弃过期触发: ${trigger.summary()}")
                            return@withContext
                        }
                        record(TAG, "🎯 本次执行触发: ${trigger?.summary() ?: "<none>"}")

                        val currentTime = System.currentTimeMillis()
                        val elapsedSinceLastExec = currentTime - lastExecTime
                        if (elapsedSinceLastExec < 2000) {
                            record(TAG, "⚠️ 间隔过短，跳过")
                            // 间隔保护仅用于防抖，重试应尽快（补足到 2s），而不是跟随执行间隔（如 50min）
                            val retryDelayMs = (2000L - elapsedSinceLastExec).coerceAtLeast(200L)
                            UnifiedScheduler.scheduleLongDelay(retryDelayMs, "间隔重试") {
                                ApplicationHookCore.requestExecution(
                                    ApplicationHookConstants.TriggerInfo(
                                        type = ApplicationHookConstants.TriggerType.INTERVAL_RETRY,
                                        priority = ApplicationHookConstants.TriggerPriority.LOW,
                                        dedupeKey = "interval_retry",
                                        persistentScheduleId = trigger?.persistentScheduleId,
                                        ownerUserId = trigger?.ownerUserId,
                                        sessionEpoch = trigger?.sessionEpoch ?: 0L,
                                    ),
                                )
                            }
                            return@withContext
                        }

                        val currentUid = currentUid
                        val targetUid = HookUtil.getUserId(classLoader!!)
                        if (targetUid == null || targetUid != currentUid) {
                            activePersistentScheduleId?.let { scheduleId ->
                                PersistentScheduleRegistry.rescheduleDeferred(appContext, scheduleId, "target_user_mismatch")
                            }
                            activePersistentScheduleId = null
                            reOpenApp()
                            return@withContext
                        }

                        lastExecTime = currentTime
                        mainTaskRunStartedAtMs = currentTime
                        mainTaskRunCompletedAtMs = 0
                        activePersistentScheduleId?.let { scheduleId ->
                            PersistentScheduleRegistry.markRunning(scheduleId)
                        }

                        val models = Model.modelArray.filterNotNull()
                        // 保持唤醒是显式的耗电策略；默认关闭时不应隐式持有覆盖整轮任务的 10 分钟 CPU 锁。
                        // 定时 Receiver 仍以短锁完成路由，任务需要持续后台执行时由用户开启 stayAwake。
                        val executionLease =
                            appContext
                                ?.takeIf { BaseModel.stayAwake.value == true }
                                ?.let { context ->
                                    WakeLockManager.acquire(
                                        context = context,
                                        timeoutMs = PersistentScheduleDefaults.TASK_EXECUTION_WAKELOCK_MS,
                                        source = "main_task",
                                        scheduleId = activePersistentScheduleId,
                                    )
                                }
                        try {
                            CoroutineTaskRunner(models).run(isFirst = true)
                            mainTaskRunCompletedAtMs = System.currentTimeMillis()
                        } finally {
                            executionLease?.close()
                        }
                        activePersistentScheduleId?.let { scheduleId ->
                            PersistentScheduleRegistry.markFired(appContext, scheduleId)
                            activePersistentScheduleId = null
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    activePersistentScheduleId?.let { scheduleId ->
                        PersistentScheduleRegistry.markFailed(appContext, scheduleId, "main_task_cancelled")
                    }
                    throw e
                } catch (e: IllegalStateException) {
                    activePersistentScheduleId?.let { scheduleId ->
                        PersistentScheduleRegistry.markFailed(appContext, scheduleId, e.message ?: e.javaClass.name)
                    }
                    record(TAG, "⚠️ " + e.message)
                } catch (e: Exception) {
                    activePersistentScheduleId?.let { scheduleId ->
                        PersistentScheduleRegistry.markFailed(appContext, scheduleId, e.message ?: e.javaClass.name)
                    }
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
                    val nextPointAt =
                        TimeTriggerEvaluator.nextCheckpointAt(
                            execScheduleField.getTriggerSpec(),
                            baseTime,
                        )
                    if (nextPointAt != null && nextPointAt < intervalTargetTime) {
                        record(TAG, "设置定时执行:${TimeUtil.getCommonDate(nextPointAt)}")
                        targetTime = nextPointAt
                        delayMillis = targetTime - baseTime
                    }
                }
                nextExecutionTime = if (targetTime > 0) targetTime else (baseTime + delayMillis)
                mainTaskNextScheduleUpdatedAtMs = System.currentTimeMillis()
                ensureScheduler()
                val context = appContext
                if (context != null) {
                    val triggerAt = nextExecutionTime
                    val activeSession = AccountSessionCoordinator.currentSession()
                    val schedule =
                        UnifiedScheduler.schedulePersistentTrigger(
                            context = context,
                            name = "轮询任务",
                            kind = PersistentScheduleKind.GLOBAL_POLL,
                            triggerAtMs = triggerAt,
                            dedupeKey = "alarm_poll",
                            payloadJson = "{}",
                            toleranceMs = maxOf(checkInterval.toLong(), PersistentScheduleDefaults.DEFAULT_TOLERANCE_MS),
                            ownerUserId = activeSession?.userId ?: currentUid,
                            sessionEpoch = activeSession?.sessionEpoch ?: AccountSessionCoordinator.currentSessionEpoch(),
                        )
                    if (schedule.lastError != null) {
                        if (PersistentLaunchPolicy.isFrontLaunchDisabled(schedule.lastError)) {
                            record(TAG, "已禁止系统调度前台拉起目标应用，轮询任务降级为仅进程存活时等待")
                        }
                        UnifiedScheduler.scheduleLongDelay(delayMillis, "轮询任务") {
                            ApplicationHookEntry.onPollAlarm()
                        }
                    } else {
                        UnifiedScheduler.cancelNamedTask("轮询任务")
                    }
                } else {
                    UnifiedScheduler.scheduleLongDelay(delayMillis, "轮询任务") {
                        ApplicationHookEntry.onPollAlarm()
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "scheduleNextExecution failed", e)
            }
        }

        // --- 初始化核心逻辑 ---
        @Synchronized
        private fun initHandler(reason: String): Boolean {
            var sessionApplied = false
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

                if (service == null) {
                    pendingInit = true
                    pendingInitReason = reason
                    record(TAG, "⏳ Service 未就绪，延后初始化: $reason")
                    return false
                }
                if (!RuntimeIdentityGuard.isTrustedForExecution()) {
                    record(TAG, "instance_rejected: ${RuntimeIdentityGuard.lastReasonCode() ?: "identity_not_verified"}")
                    return false
                }

                val activeClassLoader = classLoader ?: return false
                val userId = HookUtil.getUserId(activeClassLoader)
                if (userId == null) {
                    show("用户未登录")
                    return false
                }

                val allowPersistedSessionReuse =
                    !init &&
                        (reason == "onResume" || reason == "service_onCreate")
                AccountSessionCoordinator.beginSessionSwitch(
                    reason,
                    userId,
                    allowPersistedReuse = allowPersistedSessionReuse,
                )
                when (val admission = AccountSlotRegistry.admitRuntimeUser(userId)) {
                    is AccountSlotAdmission.Denied -> {
                        record(TAG, "execution_gate_denied: ${admission.reasonCode}")
                        destroyHandlerInternal("account_slot_${admission.reasonCode}", invalidateSession = true)
                        return false
                    }

                    is AccountSlotAdmission.Allowed -> Unit
                }
                if (init) {
                    if (shouldCaptureReloadState(reason)) {
                        captureReloadResumeDecision(reason)
                    } else {
                        reloadResumeDecision = null
                    }
                    destroyHandlerInternal("reinit_$reason", invalidateSession = false)
                } else {
                    reloadResumeDecision = null
                    EnergyWaitingManager.resetForSessionSwitch(reason)
                }

                // 初始化广播（RPC 调试 / 手动任务等功能依赖）
                try {
                    registerBroadcastReceiver(appContext!!)
                } catch (_: Throwable) {
                    // ignore
                }
                try {
                    registerAppVisibilityCallbacks(appContext!!)
                } catch (_: Throwable) {
                    // ignore
                }

                ensureScheduler()
                Model.initAllModel()

                pendingInit = false
                pendingInitReason = null
                UserMap.setCurrentUserId(userId)
                load(userId)
                // 冷启动期目标应用社交库(AliAccountDaoOp)可能尚未加载完，过早 getAllFriends() 会拿到
                // “非空但不完整”的好友快照，被 mergeFromUserMap(allowPruneMissing=true) 误剪为 REMOVED 并持久化。
                // 这里先同步加载本地已持久化的好友身份(上次完整快照)，供本会话首轮任务使用；
                // 真正从目标应用侧获取实时快照后移到社交库就绪概率更高的时机再做。手动刷新(force=true)不走此路径，仍立即执行。
                runCatching { UserMap.load(userId) }.onFailure {
                    printStackTrace(TAG, "初始化加载本地好友快照失败", it)
                }
                scheduleDeferredFriendCenterSync(userId, reason)
                record(TAG, "Sesame-AG 开始初始化...")

                Config.load(userId)
                LocaleSettingsApplier.apply(appContext)
                Logback.reloadFileLogging(enableCaptureAppender = true)
                val activeUserSnapshot = AccountSessionCoordinator.ensureActiveUserSnapshot(userId, activeClassLoader)
                val legalAccepted = Config.isLoaded() && Config.isLegalAcceptedForCurrentVersion()
                val workflowAllowed =
                    RuntimeIdentityGuard.isTrustedForExecution() &&
                        AccountSlotRegistry.isExecutableUser(userId) &&
                        WorkflowRootGuard.hasGrantedRoot() &&
                        legalAccepted &&
                        !ApplicationHookConstants.isOffline()
                val executionCheck = AccountSlotRegistry.checkExecutableUser(userId)
                if (executionCheck !is AccountSlotExecutionCheck.Allowed) {
                    record(TAG, "execution_gate_rejected_before_apply: $executionCheck")
                    destroyHandlerInternal("account_slot_recheck", invalidateSession = true)
                    return false
                }
                AccountSessionCoordinator.applySession(
                    context = appContext,
                    userId = userId,
                    activeUserSnapshot = activeUserSnapshot,
                    legalAccepted = legalAccepted,
                    workflowAllowed = workflowAllowed,
                    reason = reason,
                )
                sessionApplied = true

                if (!Config.isLoaded()) return false
                if (!ensureRootAccessForWorkflow(reason)) {
                    return false
                }
                if (!ensureLegalAcceptanceForWorkflow()) {
                    return false
                }
                if (!ensureRpcVersionSupported()) {
                    return false
                }

                // Phase 7：DataStore watcher 生命周期治理（用户切换/重载后重启 watcher，避免丢失跨进程同步能力）
                try {
                    init(Files.CONFIG_DIR)
                } catch (_: Throwable) {
                    // ignore
                }

                // 仅在用户开启“抓包调试模式”时启动调试 HTTP 服务（release 也可用）
                try {
                    if (debugMode.value == true) {
                        startIfNeeded(8080, "ET3vB^#td87sQqKaY*eMUJXP", processName, General.PACKAGE_NAME)
                    } else {
                        io.github.aoguai.sesameag.hook.server.ModuleHttpServerManager
                            .stop()
                    }
                } catch (_: Throwable) {
                    // ignore
                }

                Notify.startRunning(service!!)
                setWakenAtTimeAlarm()

                synchronized(rpcBridgeLock) {
                    rpcBridge = AriverRpcBridge()
                    rpcBridge!!.load()
                }

                start(userId)
                checkBatteryPermission()

                Model.bootAllModel(classLoader)
                updateDay()

                val successMsg = "Loaded SesameAG " + BuildConfig.VERSION_NAME + "✨"
                record(successMsg)
                show(successMsg)

                ApplicationHookConstants.setOffline(false)
                AccountSessionCoordinator.refreshWorkflowState(appContext, "init_ready", legalAccepted = true)
                init = true
                pendingInit = false
                pendingInitReason = null
                EnergyWaitingManager.restoreForCurrentSession("init_ready")
                val deferGenericStartupTrigger = handlePersistentLaunchAfterInit(appContext!!)
                ModuleStatusReporter.requestUpdate(reason = "ready")
                ApplicationHookEntry.onInitCompleted(reason, deferGenericStartupTrigger)
                return true
            } catch (th: Throwable) {
                if (sessionApplied) {
                    destroyHandlerInternal("init_exception:$reason", invalidateSession = true)
                } else {
                    PersistentScheduleRegistry.clearAll(appContext)
                    AccountSessionCoordinator.cancelSessionSwitch("init_exception:$reason")
                }
                printStackTrace(TAG, "startHandler", th)
                return false
            }
        }

        private fun handlePersistentLaunchAfterInit(context: Context): Boolean {
            val launchScheduleId = pendingPersistentLaunchScheduleId
            if (launchScheduleId.isNullOrBlank()) {
                UnifiedScheduler.reconcilePersistentSchedules(
                    context,
                    mode = PersistentReconcileMode.FIRE_ALARM_DUE,
                )
                return PersistentScheduleRegistry.hasActiveModuleChild(
                    AccountSessionCoordinator.currentUserId(),
                    AccountSessionCoordinator.currentSessionEpoch(),
                )
            }
            record(TAG, "初始化完成，处理持久调度唤醒任务[$launchScheduleId]")
            val schedule = PersistentScheduleRegistry.get(launchScheduleId)
            if (schedule != null) {
                if (ScheduledTaskRouter.fire(context, schedule, "init_ready")) {
                    pendingPersistentLaunchScheduleId = null
                }
            } else {
                record(TAG, "初始化完成但持久调度任务不存在[$launchScheduleId]")
                pendingPersistentLaunchScheduleId = null
            }
            return PersistentScheduleRegistry.hasActiveModuleChild(
                AccountSessionCoordinator.currentUserId(),
                AccountSessionCoordinator.currentSessionEpoch(),
            )
        }

        private fun checkBatteryPermission() {
            if (batteryPerm.value != true || batteryPermissionChecked) return
            batteryPermissionChecked = true
            val context = appContext ?: return
            if (!checkBatteryPermissions(context, General.MODULE_PACKAGE_NAME)) {
                record(TAG, "模块缺少忽略电池优化权限，请在模块界面内完成授权；自动链路不会主动申请")
            }
            if (!checkBatteryPermissions(context, General.PACKAGE_NAME)) {
                record(TAG, "目标应用缺少忽略电池优化权限，请在系统设置中手动检查；自动链路不会主动申请")
            }
        }

        @Synchronized
        fun destroyHandler() {
            // 允许任意 Hook 线程请求销毁，但绝不让生命周期/主线程同步执行整套清理。
            ApplicationHookConstants.submitEntry("destroy_handler") {
                destroyHandlerInternal("destroy_handler", invalidateSession = true)
            }
        }

        private fun destroyHandlerInternal(
            reason: String,
            invalidateSession: Boolean,
        ) {
            try {
                if (invalidateSession) {
                    AccountSessionCoordinator.clearRuntimeSession(reason)
                }
                init = false
                pendingInit = false
                pendingInitReason = null
                rootCheckInProgress = false
                pendingPersistentLaunchScheduleId = null
                ApplicationHookConstants.clearPendingTriggers(reason)
                if (invalidateSession) {
                    PersistentScheduleRegistry.clearAll(appContext)
                }
                ApplicationResumeCoordinator.reset()
                lastExecTime = 0
                EnergyWaitingManager.resetForSessionSwitch(reason)
                try {
                    io.github.aoguai.sesameag.util.DataStore
                        .shutdown()
                } catch (_: Throwable) {
                    // ignore
                }
                try {
                    io.github.aoguai.sesameag.util.UserDataStoreManager
                        .shutdownAll()
                } catch (_: Throwable) {
                    // ignore
                }
                shutdownAndRestart()
                stopHandler()

                if (service != null) {
                    destroyData()
                    Status.unload()
                    stopRunning()
                    clearIntervalLimit()
                    Config.unload()
                    UserMap.setCurrentUserId(null)
                    UserMap.unload()
                }

                UnifiedScheduler.cleanup()

                // 注销广播接收器
                unregisterBroadcastReceiver(appContext)
                unregisterAppVisibilityCallbacks(appContext)

                synchronized(rpcBridgeLock) {
                    if (rpcBridge != null) {
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
                AccountSessionCoordinator.refreshWorkflowState(appContext, "root_granted_cached")
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
                        AccountSessionCoordinator.refreshWorkflowState(appContext, "root_denied")
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
                LocaleSettingsApplier.apply(appContext)
            }
            if (Config.isLegalAcceptedForCurrentVersion()) {
                AccountSessionCoordinator.refreshWorkflowState(appContext, "legal_accepted", legalAccepted = true)
                return true
            }

            pendingInit = false
            pendingInitReason = null
            val message = "未勾选已阅读 LICENSE 与 LEGAL 说明，已禁止工作流"
            record(TAG, "⛔ $message")
            updateRunningStatus(message)
            ApplicationHookConstants.clearPendingTriggers("legal_unaccepted")
            AccountSessionCoordinator.refreshWorkflowState(appContext, "legal_unaccepted", legalAccepted = false)
            return false
        }

        private fun stopHandler() {
            // 销毁只能请求取消，不能在宿主生命周期或入口线程同步等待协程结束。
            runCatching { mainTask?.stopTask() }
                .onFailure { Log.printStackTrace(TAG, "请求停止主任务失败", it) }
            runCatching { stopAllTask() }
                .onFailure { Log.printStackTrace(TAG, "请求停止模型任务失败", it) }
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
                val context = appContext
                if (init && context != null) {
                    UnifiedScheduler.reconcilePersistentSchedules(context)
                }
            }
            try {
                save(now)
            } catch (_: Exception) {
            }
        }

        fun reOpenApp() {
            ensureScheduler()
            UnifiedScheduler.scheduleLongDelay(20000L, "重新登录") {
                val ownerUserId = AccountSessionCoordinator.currentUserId() ?: currentUid
                if (!ScheduledTaskRouter.allowRuntimeForegroundLaunch(ownerUserId, "reopen_app")) {
                    record(TAG, "已跳过重新拉起目标应用：前台拉起开关关闭或处于频控")
                    return@scheduleLongDelay
                }
                try {
                    ApplicationResumeCoordinator.recordReOpenAppLaunch()
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY)
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext?.startActivity(intent)
                } catch (e: Exception) {
                    ScheduledTaskRouter.recordRuntimeForegroundLaunchFailure(ownerUserId, "reopen_app", e)
                    error(TAG, "重启Activity失败: " + e.message)
                }
            }
        }

        // --- 定时唤醒 ---
        internal fun setWakenAtTimeAlarm() {
            if (appContext == null) return
            ensureScheduler()
            val context = appContext!!

            val wakeField = wakenAtTimeList
            if (wakeField.isDisabled()) {
                UnifiedScheduler.cancelNamedTask("每日0点任务")
                UnifiedScheduler.cancelNamedTask("自定义唤醒任务")
                UnifiedScheduler.cancelPersistentByName(context, "每日0点任务")
                UnifiedScheduler.cancelPersistentByName(context, "自定义唤醒任务")
                return
            }

            // 1. 每日0点
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            ApplicationHookUtils.resetToMidnight(calendar)
            val delayToMidnight = calendar.getTimeInMillis() - System.currentTimeMillis()
            if (delayToMidnight > 0) {
                val activeSession = AccountSessionCoordinator.currentSession()
                val midnightSchedule =
                    UnifiedScheduler.schedulePersistentTrigger(
                        context = context,
                        name = "每日0点任务",
                        kind = PersistentScheduleKind.GLOBAL_WAKEUP,
                        triggerAtMs = calendar.timeInMillis,
                        dedupeKey = "wakeup_midnight",
                        payloadJson = """{"waken_time":"0000","wake_type":"midnight"}""",
                        toleranceMs = PersistentScheduleDefaults.DEFAULT_TOLERANCE_MS,
                        ownerUserId = activeSession?.userId ?: currentUid,
                        sessionEpoch = activeSession?.sessionEpoch ?: AccountSessionCoordinator.currentSessionEpoch(),
                    )
                if (midnightSchedule.lastError != null) {
                    if (PersistentLaunchPolicy.isFrontLaunchDisabled(midnightSchedule.lastError)) {
                        record(TAG, "已禁止系统调度前台拉起目标应用，每日0点任务降级为仅进程存活时等待")
                    }
                    UnifiedScheduler.scheduleLongDelay(delayToMidnight, "每日0点任务") {
                        record(TAG, "⏰ 0点任务触发")
                        updateDay()
                        ApplicationHookEntry.onWakeupMidnight()
                        setWakenAtTimeAlarm()
                    }
                } else {
                    UnifiedScheduler.cancelNamedTask("每日0点任务")
                }
            }

            // 2. 下一次自定义唤醒（必要时跨日）
            UnifiedScheduler.cancelNamedTask("自定义唤醒任务")
            val nextWakeAt = wakeField.nextPointAt() ?: return
            val now = System.currentTimeMillis()
            val delay = nextWakeAt - now
            if (delay <= 0) return

            val targetCalendar = Calendar.getInstance().apply { timeInMillis = nextWakeAt }
            val secondOfDay =
                targetCalendar.get(Calendar.HOUR_OF_DAY) * 3600 +
                    targetCalendar.get(Calendar.MINUTE) * 60 +
                    targetCalendar.get(Calendar.SECOND)
            val timeToken =
                TimeTriggerParser.formatSecondOfDay(
                    secondOfDay,
                    useSeconds = targetCalendar.get(Calendar.SECOND) != 0,
                )
            val customWakeSchedule =
                UnifiedScheduler.schedulePersistentTrigger(
                    context = context,
                    name = "自定义唤醒任务",
                    kind = PersistentScheduleKind.GLOBAL_WAKEUP,
                    triggerAtMs = nextWakeAt,
                    dedupeKey = "wakeup_custom",
                    payloadJson = """{"waken_time":"$timeToken","wake_type":"custom"}""",
                    toleranceMs = PersistentScheduleDefaults.DEFAULT_TOLERANCE_MS,
                    ownerUserId = AccountSessionCoordinator.currentUserId() ?: currentUid,
                    sessionEpoch = AccountSessionCoordinator.currentSessionEpoch(),
                )
            if (customWakeSchedule.lastError != null) {
                if (PersistentLaunchPolicy.isFrontLaunchDisabled(customWakeSchedule.lastError)) {
                    record(TAG, "已禁止系统调度前台拉起目标应用，自定义唤醒任务降级为仅进程存活时等待[$timeToken]")
                }
                UnifiedScheduler.scheduleLongDelay(delay, "自定义唤醒任务") {
                    record(TAG, "? 自定义触发: $timeToken")
                    ApplicationHookEntry.onWakeupCustom(timeToken)
                    setWakenAtTimeAlarm()
                }
            } else {
                UnifiedScheduler.cancelPersistentByNameExceptDedupeKey(
                    context,
                    "自定义唤醒任务",
                    "wakeup_custom",
                    silent = true,
                )
            }
            return
        }

        fun registerBroadcastReceiver(context: Context) {
            if (mBroadcastReceiver != null) return // 防止重复注册

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
                filter.addAction(ApplicationHookConstants.BroadcastActions.PERMISSION_SNAPSHOT)
                filter.addAction(ApplicationHookConstants.BroadcastActions.CAPTURE_PAGE_SNAPSHOT)
                filter.addAction(ApplicationHookConstants.BroadcastActions.REFRESH_FRIENDS)
                filter.addAction(ApplicationHookConstants.BroadcastActions.REFRESH_EXCHANGE_OPTIONS)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    ContextCompat.registerReceiver(
                        context,
                        mBroadcastReceiver,
                        filter,
                        // 该接收器依赖模块进程发起的自定义广播，必须允许跨 App 投递。
                        ContextCompat.RECEIVER_EXPORTED,
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

        internal fun requireXposedInterface(): XposedInterface = frameworkInterface ?: throw IllegalStateException("XposedInterface 未初始化")

        private fun getFrameworkRuntimeInfo(): FrameworkRuntimeInfo? = frameworkRuntimeInfo

        internal fun resolveCurrentFrameworkInfo(): ModuleStatus.FrameworkInfo =
            ModuleStatus.resolveFrameworkInfo(frameworkRuntimeInfo?.name)

        internal fun hasSupportedLibXposedRuntime(): Boolean {
            val runtimeInfo = frameworkRuntimeInfo ?: return false
            return ModuleStatus.isSupportedLsposedFramework(runtimeInfo.name, runtimeInfo.apiVersion ?: 0)
        }

        private fun updateFrameworkRuntimeInfo(xposedInterface: XposedInterface?) {
            frameworkInterface = xposedInterface
            frameworkRuntimeInfo =
                xposedInterface?.let { framework ->
                    FrameworkRuntimeInfo(
                        name = runCatching { framework.frameworkName }.getOrNull(),
                        version = runCatching { framework.frameworkVersion }.getOrNull(),
                        versionCode = runCatching { framework.frameworkVersionCode }.getOrNull(),
                        apiVersion = runCatching { framework.apiVersion }.getOrNull(),
                        properties = runCatching { framework.frameworkProperties }.getOrNull(),
                    )
                }
            ModuleStatusReporter.setBaseInfo(
                framework = frameworkRuntimeInfo?.name,
                packageName = null,
            )
        }

        private fun loadClass(
            loader: ClassLoader,
            className: String,
        ): Class<*> = Class.forName(className, false, loader)

        private fun findMethod(
            targetClass: Class<*>,
            name: String,
            vararg parameterTypes: Class<*>,
        ): Method {
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

        private fun logFrameworkWarning(
            message: String,
            throwable: Throwable? = null,
        ) {
            val logger = frameworkInterface ?: return
            if (throwable != null) {
                logger.log(android.util.Log.WARN, TAG, message, throwable)
            } else {
                logger.log(android.util.Log.WARN, TAG, message)
            }
        }
    }
}

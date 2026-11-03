package io.github.aoguai.sesameag.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.keepalive.PersistentSchedule
import io.github.aoguai.sesameag.hook.keepalive.PersistentSchedulePrecisionPolicy
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleState
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskExecutionReport
import kotlin.concurrent.Volatile

@SuppressLint("StaticFieldLeak")
object Notify {
    private val TAG: String = Notify::class.java.simpleName

    private const val RUNNING_NOTIFICATION_ID = 99
    private const val ALERT_NOTIFICATION_ID = 98
    private const val RUNNING_CHANNEL_ID = "io.github.aoguai.sesameag.RUNNING_STATUS"
    private const val ALERT_CHANNEL_ID = "io.github.aoguai.sesameag.ALERTS"
    private const val RUNNING_CHANNEL_NAME = "模块运行状态"
    private const val ALERT_CHANNEL_NAME = "模块异常告警"
    private const val SUB_TEXT = "Sesame-AG"

    @SuppressLint("StaticFieldLeak")
    var context: Context? = null

    private var notificationManager: NotificationManager? = null

    @SuppressLint("StaticFieldLeak")
    private var runningBuilder: NotificationCompat.Builder? = null

    @Volatile
    private var isNotificationStarted = false

    private var lastUpdateTime: Long = 0
    private var nextExecTimeCache: Long = 0
    private var persistentScheduleText: String? = null
    @Volatile
    private var globalStatusText: String? = null
    @Volatile
    private var globalStatusAtMs: Long = 0L
    @Volatile
    private var globalStatusTtlMs: Long = 0L
    private var lastExecText: String = ""
    private data class RunningTasks(
        val names: List<String> = emptyList(),
        val displayOrder: Map<String, Int> = emptyMap(),
    )
    private val runningTasks = java.util.concurrent.atomic.AtomicReference(RunningTasks())

    private const val STARTUP_TITLE = "模块启动中"
    private const val RUNNING_TITLE = "模块运行中"
    private const val WAITING_TITLE = "等待下次执行"
    private const val MULTI_RUNNING_PREFIX = "多个任务运行中："

    /** 瞬态状态文案（如恢复成功提示）的默认展示时长，过期后回落到实时状态标题 */
    private const val TRANSIENT_STATUS_TTL_MS = 10 * 60_000L

    private fun checkPermission(context: Context, silent: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!silent) {
                    Log.error(TAG, "Missing POST_NOTIFICATIONS permission to send notification: $context")
                    Toast.show("请在设置中开启目标应用通知权限")
                }
                return false
            }
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            if (!silent) {
                Log.error(TAG, "Notifications are disabled for this app: $context")
                Toast.show("请在设置中开启目标应用通知权限")
            }
            return false
        }
        return true
    }

    private fun createChannels(manager: NotificationManager) {
        val runningChannel = NotificationChannel(
            RUNNING_CHANNEL_ID,
            RUNNING_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            description = "模块运行中状态通知"
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            ALERT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setShowBadge(true)
            description = "模块异常、风控、离线与 RPC 告警通知"
        }

        manager.createNotificationChannel(runningChannel)
        manager.createNotificationChannel(alertChannel)
    }

    private fun normalizeTaskName(taskName: String?): String? {
        return taskName?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun clearRunningTasks() {
        runningTasks.updateAndGet { it.copy(names = emptyList()) }
    }

    private fun clearRunningTaskDisplayOrder() {
        runningTasks.updateAndGet { it.copy(displayOrder = emptyMap()) }
    }

    private fun addRunningTask(taskName: String?) {
        val normalizedName = normalizeTaskName(taskName) ?: return
        runningTasks.updateAndGet { it.copy(names = it.names.filterNot { name -> name == normalizedName } + normalizedName) }
    }

    private fun removeRunningTask(taskName: String?) {
        val normalizedName = normalizeTaskName(taskName) ?: return
        runningTasks.updateAndGet { it.copy(names = it.names.filterNot { name -> name == normalizedName }) }
    }

    private fun snapshotRunningTasks(): List<String> {
        val state = runningTasks.get()
        if (state.names.size <= 1 || state.displayOrder.isEmpty()) return state.names
        val insertionOrder = state.names.withIndex().associate { it.value to it.index }
        return state.names.sortedWith(
            compareBy<String>(
                { state.displayOrder[it] ?: Int.MAX_VALUE },
                { insertionOrder[it] ?: Int.MAX_VALUE },
            ),
        )
    }

    @JvmStatic
    fun updateRunningTaskOrder(taskNames: List<String?>) {
        val order = LinkedHashMap<String, Int>()
        taskNames.forEachIndexed { index, taskName ->
            val name = normalizeTaskName(taskName) ?: return@forEachIndexed
            order.putIfAbsent(name, index)
        }
        runningTasks.updateAndGet { it.copy(displayOrder = order) }
    }

    @JvmStatic
    fun startRunning(context: Context) {
        try {
            if (!checkPermission(context)) {
                return
            }
            startRunningInternal(context)
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    /**
     * 常驻通知自愈入口：目标应用通知权限被拒导致链路未启动时，在后续状态更新或
     * 用户回到目标应用时静默重试，授权后自动恢复，无需等待目标进程重启。
     */
    @JvmStatic
    fun ensureStarted() {
        if (isNotificationStarted) {
            return
        }
        try {
            val ctx = context ?: return
            if (!checkPermission(ctx, silent = true)) {
                return
            }
            startRunningInternal(ctx)
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    private fun startRunningInternal(context: Context) {
        Notify.context = context
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(notificationManager!!)

        globalStatusText = null
        lastExecText = ""
        nextExecTimeCache = 0
        persistentScheduleText = null
        clearRunningTasks()
        clearRunningTaskDisplayOrder()
        lastUpdateTime = System.currentTimeMillis()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = "alipays://platformapi/startapp?appId=".toUri()
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        runningBuilder = NotificationCompat.Builder(context, RUNNING_CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, android.R.drawable.sym_def_app_icon))
            .setContentTitle(STARTUP_TITLE)
            .setContentText("暂无执行记录")
            .setSubText(SUB_TEXT)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)

        if (BaseModel.enableOnGoing.value == true) {
            runningBuilder!!.setOngoing(true)
        }

        isNotificationStarted = true
        render(force = true)
    }

    @JvmStatic
    fun stopRunning() {
        try {
            val ctx = context ?: return
            if (ctx is Service) {
                ctx.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            }
            NotificationManagerCompat.from(ctx).cancel(RUNNING_NOTIFICATION_ID)
            notificationManager = null
            runningBuilder = null
            globalStatusText = null
            lastExecText = ""
            nextExecTimeCache = 0
            persistentScheduleText = null
            clearRunningTasks()
            clearRunningTaskDisplayOrder()
            isNotificationStarted = false
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    @JvmStatic
    fun updateRunningStatus(status: String?, ttlMs: Long = TRANSIENT_STATUS_TTL_MS) {
        if (!isNotificationStarted) {
            ensureStarted()
            if (!isNotificationStarted) {
                return
            }
        }
        try {
            globalStatusText = status?.takeIf { it.isNotBlank() }
            globalStatusAtMs = System.currentTimeMillis()
            globalStatusTtlMs = ttlMs
            render(force = true)
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    @JvmStatic
    fun startTaskRunning(taskName: String?) {
        if (!isNotificationStarted) {
            ensureStarted()
            if (!isNotificationStarted) {
                return
            }
        }
        try {
            addRunningTask(taskName)
            render(force = true)
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    @JvmStatic
    fun finishTaskRunning(taskName: String?) {
        if (!isNotificationStarted) {
            ensureStarted()
            if (!isNotificationStarted) {
                return
            }
        }
        try {
            removeRunningTask(taskName)
            render(force = true)
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    fun updatePersistentSchedule(
        schedule: PersistentSchedule?,
        backgroundScheduled: Boolean = true,
        exactAlarmAvailable: Boolean = true,
    ) {
        if (!isNotificationStarted) {
            ensureStarted()
            if (!isNotificationStarted) return
        }
        nextExecTimeCache = schedule?.takeIf { it.state == PersistentScheduleState.SCHEDULED }?.triggerAtMs ?: 0L
        persistentScheduleText = when (schedule?.state) {
            PersistentScheduleState.SCHEDULED -> when {
                schedule.lastError == "launch_pending" -> "正在唤醒，等待任务接收"
                schedule.lastError == "delivery_pending" -> "已投递，等待任务接收"
                schedule.lastError != null -> "已延期，预计 ${TimeUtil.getTimeStr(schedule.triggerAtMs)} 重试"
                !backgroundScheduled -> "应用运行时执行 ${TimeUtil.getTimeStr(schedule.triggerAtMs)}"
                schedule.effectivePrecisionPolicy() == PersistentSchedulePrecisionPolicy.FLEXIBLE_POLL ->
                    "预计执行 ${TimeUtil.getTimeStr(schedule.triggerAtMs)} 至 ${TimeUtil.getTimeStr(schedule.deadlineAtMs())}"
                !exactAlarmAvailable -> "预计执行 ${TimeUtil.getTimeStr(schedule.triggerAtMs)}（未获精确闹钟权限）"
                else -> "下次执行 ${TimeUtil.getTimeStr(schedule.triggerAtMs)}"
            }
            PersistentScheduleState.QUEUED -> "已到期，等待执行"
            PersistentScheduleState.RUNNING -> "本次计划执行中"
            PersistentScheduleState.FAILED -> "本次计划未能执行"
            PersistentScheduleState.EXPIRED -> "本次计划已失效"
            else -> null
        }
        render(force = true)
    }

    @JvmStatic
    fun updateRunningNextExec(nextExecTime: Long) {
        if (!isNotificationStarted) {
            ensureStarted()
            if (!isNotificationStarted) {
                return
            }
        }
        try {
            if (nextExecTime != -1L) {
                nextExecTimeCache = nextExecTime
                persistentScheduleText = null
            }
            render(force = false)
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    @JvmStatic
    fun updateRunningLastExec(content: String?) {
        if (!isNotificationStarted) {
            ensureStarted()
            if (!isNotificationStarted) {
                return
            }
        }
        try {
            val body = content?.trim().orEmpty()
            lastExecText = if (body.isBlank()) {
                "上次执行 ${TimeUtil.getTimeStr(System.currentTimeMillis())}"
            } else {
                "上次执行 ${TimeUtil.getTimeStr(System.currentTimeMillis())}\n$body"
            }
            render(force = false)
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    /**
     * 统一合成常驻通知：标题按「任务异常暂停（多任务聚合） > 离线 > 任务运行中 > 瞬态状态 > 等待下次执行 >
     * 启动中」的优先级实时合成，暂停/离线直接读实时状态源，不依赖粘滞文本；瞬态状态
     * （如恢复成功提示）带 TTL 自动过期。内容用 BigTextStyle 同时展示“下次执行”和
     * “上次执行”，三个来源字段互不覆盖。
     */
    @JvmStatic
    private fun render(force: Boolean) {
        if (TaskExecutionReport.current() != null) {
            // 通知读取当前账号状态，不继承触发刷新时可能已取消的业务执行上下文。
            val ctx = context ?: return
            ContextCompat.getMainExecutor(ctx).execute { render(force) }
            return
        }
        val builder = runningBuilder ?: return
        val manager = notificationManager ?: return
        if (!isNotificationStarted) {
            return
        }
        try {
            if (!force && System.currentTimeMillis() - lastUpdateTime < 500) {
                return
            }
            lastUpdateTime = System.currentTimeMillis()

            val now = System.currentTimeMillis()
            val pausedTasks = ModelTask.activeTaskPauseMap().entries.sortedBy { it.value }
            val explicitStatus = globalStatusText?.takeIf {
                it.isNotBlank() && (globalStatusTtlMs <= 0L || now - globalStatusAtMs < globalStatusTtlMs)
            }
            val offlinePaused = ApplicationHookConstants.isOffline()
            val runningTasks = snapshotRunningTasks()
            val runningSummary = when {
                runningTasks.isEmpty() -> null
                runningTasks.size == 1 -> "${runningTasks.first()} 运行中"
                else -> MULTI_RUNNING_PREFIX + runningTasks.joinToString("、")
            }
            val title = when {
                pausedTasks.size == 1 -> {
                    pausedTasks.first().key + " 异常暂停，恢复时间 " + TimeUtil.getCommonDate(pausedTasks.first().value)
                }
                pausedTasks.size > 1 -> {
                    pausedTasks.size.toString() + " 个任务异常暂停"
                }
                offlinePaused -> {
                    when (ApplicationHookConstants.offlineReason) {
                        "auth_like" -> "已暂停（风控/验证）"
                        "rpc_error_threshold", "network_error_threshold" -> "已暂停（网络离线）"
                        else -> "已暂停（离线冷却）"
                    }
                }
                runningTasks.isNotEmpty() -> {
                    if (runningTasks.size == 1) {
                        "${runningTasks.first()} 运行中"
                    } else {
                        "$RUNNING_TITLE（${runningTasks.size}）"
                    }
                }
                explicitStatus != null -> {
                    explicitStatus
                }
                nextExecTimeCache > 0 -> WAITING_TITLE
                persistentScheduleText != null -> persistentScheduleText!!
                else -> STARTUP_TITLE
            }

            val lines = buildList {
                if (pausedTasks.size > 1) {
                    add("暂停: " + pausedTasks.joinToString("、") { it.key + "(" + TimeUtil.getCommonDate(it.value) + ")" })
                }
                if (runningTasks.size > 1) {
                    add(runningSummary!!)
                }
                if (persistentScheduleText != null) {
                    add(persistentScheduleText!!)
                } else if (nextExecTimeCache > 0) {
                    add("下次执行 ${TimeUtil.getTimeStr(nextExecTimeCache)}")
                }
                if (lastExecText.isNotBlank()) {
                    add(lastExecText)
                }
            }
            val content = if (lines.isEmpty()) (runningSummary ?: RUNNING_TITLE) else lines.joinToString("\n")

            builder.setContentTitle(title)
            builder.setContentText(lines.firstOrNull() ?: runningSummary ?: content)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content))
            manager.notify(RUNNING_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    @JvmStatic
    fun sendAlert(title: String?, content: String?) {
        sendAlertNotification(title, content)
    }

    private fun sendAlertNotification(title: String?, content: String?) {
        try {
            val ctx = context
            if (ctx == null) {
                Log.error(TAG, "Context is null in sendAlertNotification, cannot proceed.")
                return
            }
            if (!checkPermission(ctx)) {
                return
            }

            val manager = notificationManager
                ?: (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.also {
                    notificationManager = it
                }
                ?: return

            createChannels(manager)

            val alertBuilder = NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setLargeIcon(BitmapFactory.decodeResource(ctx.resources, android.R.drawable.sym_def_app_icon))
                .setContentTitle(title?.ifBlank { "模块异常通知" } ?: "模块异常通知")
                .setContentText(content?.ifBlank { "请打开错误日志查看详情" } ?: "请打开错误日志查看详情")
                .setSubText(SUB_TEXT)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            NotificationManagerCompat.from(ctx).notify(ALERT_NOTIFICATION_ID, alertBuilder.build())
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }
}

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
import io.github.aoguai.sesameag.data.RuntimeInfo
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.model.BaseModel
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
    @Volatile
    private var globalStatusText: String? = null
    private var lastExecText: String = ""
    private val runningTaskLock = Any()
    private val runningTaskNames = LinkedHashSet<String>()

    private const val STARTUP_TITLE = "模块启动中"
    private const val RUNNING_TITLE = "模块运行中"
    private const val WAITING_TITLE = "等待下次执行"
    private const val MULTI_RUNNING_PREFIX = "多个任务运行中："

    private fun checkPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.error(TAG, "Missing POST_NOTIFICATIONS permission to send notification: $context")
                Toast.show("请在设置中开启目标应用通知权限")
                return false
            }
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.error(TAG, "Notifications are disabled for this app: $context")
            Toast.show("请在设置中开启目标应用通知权限")
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
        synchronized(runningTaskLock) {
            runningTaskNames.clear()
        }
    }

    private fun addRunningTask(taskName: String?) {
        val normalizedName = normalizeTaskName(taskName) ?: return
        synchronized(runningTaskLock) {
            runningTaskNames.remove(normalizedName)
            runningTaskNames.add(normalizedName)
        }
    }

    private fun removeRunningTask(taskName: String?) {
        val normalizedName = normalizeTaskName(taskName) ?: return
        synchronized(runningTaskLock) {
            runningTaskNames.remove(normalizedName)
        }
    }

    private fun snapshotRunningTasks(): List<String> {
        return synchronized(runningTaskLock) {
            runningTaskNames.toList()
        }
    }

    @JvmStatic
    fun startRunning(context: Context) {
        try {
            if (!checkPermission(context)) {
                return
            }

            Notify.context = context
            notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannels(notificationManager!!)

            globalStatusText = null
            lastExecText = ""
            nextExecTimeCache = 0
            clearRunningTasks()
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
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
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
            clearRunningTasks()
            isNotificationStarted = false
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    @JvmStatic
    fun updateRunningStatus(status: String?) {
        if (!isNotificationStarted) {
            return
        }
        try {
            globalStatusText = status?.takeIf { it.isNotBlank() }
            render(force = true)
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    @JvmStatic
    fun startTaskRunning(taskName: String?) {
        if (!isNotificationStarted) {
            return
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
            return
        }
        try {
            removeRunningTask(taskName)
            render(force = true)
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    @JvmStatic
    fun updateRunningNextExec(nextExecTime: Long) {
        if (!isNotificationStarted) {
            return
        }
        try {
            if (nextExecTime != -1L) {
                nextExecTimeCache = nextExecTime
            }
            render(force = false)
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    @JvmStatic
    fun updateRunningLastExec(content: String?) {
        if (!isNotificationStarted) {
            return
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
     * 统一合成常驻通知：标题反映当前运行/暂停状态，内容用 BigTextStyle 同时展示
     * “下次执行”和“上次执行”，三个来源字段互不覆盖。
     */
    @JvmStatic
    private fun render(force: Boolean) {
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

            val pauseTime = RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime)
            val explicitStatus = globalStatusText?.takeIf { it.isNotBlank() }
            val runningTasks = snapshotRunningTasks()
            val title = when {
                pauseTime > System.currentTimeMillis() -> {
                    "异常暂停，恢复时间 ${TimeUtil.getCommonDate(pauseTime)}"
                }
                explicitStatus != null -> {
                    explicitStatus
                }
                runningTasks.isNotEmpty() -> {
                    if (runningTasks.size == 1) {
                        "${runningTasks.first()} 运行中"
                    } else {
                        MULTI_RUNNING_PREFIX + runningTasks.joinToString("、")
                    }
                }
                nextExecTimeCache > 0 -> WAITING_TITLE
                else -> STARTUP_TITLE
            }

            val lines = buildList {
                if (nextExecTimeCache > 0) {
                    add("下次执行 ${TimeUtil.getTimeStr(nextExecTimeCache)}")
                }
                if (lastExecText.isNotBlank()) {
                    add(lastExecText)
                }
            }
            val content = if (lines.isEmpty()) RUNNING_TITLE else lines.joinToString("\n")

            builder.setContentTitle(title)
            builder.setContentText(lines.firstOrNull() ?: content)
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

package io.github.aoguai.sesameag.hook.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.util.CommandUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify
import io.github.aoguai.sesameag.util.PermissionUtil
import io.github.aoguai.sesameag.util.TimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object SystemWakeScheduler {
    private const val TAG = "SystemWakeScheduler"
    private const val MIN_FLEXIBLE_WINDOW_MS = 10 * 60 * 1000L
    internal const val ACTION_TRIGGER = "io.github.aoguai.sesameag.action.PERSISTENT_SCHEDULE_TRIGGER"
    internal const val EXTRA_SCHEDULE_ID = "schedule_id"
    internal const val EXTRA_PERSISTENT_ALARM_LAUNCH = "persistent_alarm_launch"
    internal const val EXTRA_PLANNED_BATCH = "persistent_planned_batch"
    internal const val EXTRA_CONFIRMATION_AT = "persistent_confirmation_at"
    const val LANE_EXACT = 0
    const val LANE_FLEXIBLE = 1
    private const val PLANNER_REQUEST_CODE = 0x53534147
    private val plannerLock = Any()
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var localJob: Job? = null
    private var localSchedule: PersistentSchedule? = null
    @Volatile
    private var targetContext: Context? = null

    fun createAlarmIntent(context: Context, lane: Int): PendingIntent {
        require(context.packageName == General.MODULE_PACKAGE_NAME)
        require(lane == LANE_EXACT || lane == LANE_FLEXIBLE)
        val intent = Intent(context, ScheduledTriggerReceiver::class.java).apply {
            action = ACTION_TRIGGER
            data = Uri.Builder().scheme("sesameag").authority("persistent-schedule-lane")
                .appendPath(lane.toString()).build()
            putExtra(EXTRA_SCHEDULE_ID, "lane:$lane")
            putExtra(EXTRA_PLANNED_BATCH, true)
        }
        return PendingIntent.getBroadcast(
            context, PLANNER_REQUEST_CODE + 1 + lane, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun refresh(context: Context) {
        schedule(context, PersistentSchedule(), silent = true)
    }

    internal fun onRegistryChanged() {
        val context = targetContext ?: return
        timerScope.launch { refresh(context) }
    }

    /** 两个精度通道互不阻挡；目标进程仅保留最近计划的一个轻量计时器。 */
    fun schedule(context: Context, schedule: PersistentSchedule, silent: Boolean = false): Boolean =
        synchronized(plannerLock) {
            val appContext = context.applicationContext ?: context
            val snapshot = PersistentScheduleRegistry.list()
            val scheduled = snapshot.filter { it.state == PersistentScheduleState.SCHEDULED }
            val localScheduled = updateLocalTimer(appContext, scheduled)
            var systemScheduled = true
            for (lane in listOf(LANE_EXACT, LANE_FLEXIBLE)) {
                val primary = scheduled.filter { laneFor(it) == lane }.minByOrNull { it.triggerAtMs }
                val triggerAt = primary?.triggerAtMs?.coerceAtLeast(System.currentTimeMillis()) ?: 0L
                val window = primary?.toleranceMs?.coerceAtLeast(MIN_FLEXIBLE_WINDOW_MS) ?: MIN_FLEXIBLE_WINDOW_MS
                val userExact = primary?.effectivePrecisionPolicy() == PersistentSchedulePrecisionPolicy.USER_EXACT
                try {
                    // Register in the module UID: stopping Alipay must not cancel its recovery alarm.
                    // Pass values only; the Binder service must not reenter the registry or planner lock.
                    val armed = if (appContext.packageName == General.MODULE_PACKAGE_NAME) {
                        scheduleLane(appContext, lane, triggerAt, window, userExact)
                    } else {
                        CommandUtil.schedulePersistentAlarm(lane, triggerAt, window, userExact)
                    }
                    if (!armed) systemScheduled = false
                    if (armed && primary != null && !silent) {
                        Log.runtime(TAG, "已重排系统闹钟[${primary.name}] ${TimeUtil.getCommonDate(triggerAt)} lane=$lane")
                    }
                } catch (t: Throwable) {
                    systemScheduled = false
                    Log.printStackTrace(TAG, "重排系统闹钟失败[${primary?.name ?: schedule.name}] lane=$lane", t)
                }
            }
            if (systemScheduled) cancelLegacyPlanner(appContext)
            if (!systemScheduled && localScheduled) {
                Log.runtime(TAG, "系统闹钟暂不可用，已保留同一持久任务的进程内计时")
            }
            if ((systemScheduled || localScheduled) && appContext.packageName == General.PACKAGE_NAME) {
                val session = AccountSessionCoordinator.currentSession()
                val polls = snapshot.filter {
                    it.kind == PersistentScheduleKind.GLOBAL_POLL &&
                        it.ownerUserId == session?.userId && it.sessionEpoch == session?.sessionEpoch
                }
                val poll = polls.filter { it.state == PersistentScheduleState.SCHEDULED }.minByOrNull { it.triggerAtMs }
                    ?: polls.filter { it.state in setOf(PersistentScheduleState.QUEUED, PersistentScheduleState.RUNNING) }
                        .maxByOrNull { it.updatedAtMs }
                    ?: polls.maxByOrNull { it.updatedAtMs }
                ApplicationHook.nextExecutionTime = poll?.takeIf {
                    it.state == PersistentScheduleState.SCHEDULED
                }?.triggerAtMs ?: 0L
                Notify.updatePersistentSchedule(
                    poll, backgroundScheduled = systemScheduled,
                    exactAlarmAvailable = PermissionUtil.checkAlarmPermissions(appContext),
                )
            }
            systemScheduled || localScheduled
        }

    /** Only touches AlarmManager; safe to call while the host holds its registry lock. */
    fun scheduleLane(context: Context, lane: Int, triggerAtMs: Long, windowMs: Long, userExact: Boolean): Boolean {
        require(context.packageName == General.MODULE_PACKAGE_NAME)
        require(lane == LANE_EXACT || lane == LANE_FLEXIBLE)
        require(triggerAtMs >= 0 && windowMs >= 0)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val token = createAlarmIntent(context, lane)
        if (triggerAtMs == 0L) {
            alarmManager.cancel(token)
        } else if (lane == LANE_FLEXIBLE) {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMs, windowMs.coerceAtLeast(MIN_FLEXIBLE_WINDOW_MS), token)
        } else if (!PermissionUtil.checkAlarmPermissions(context)) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, token)
            Log.record(TAG, "精确闹钟权限不可用，已降级投递")
        } else if (userExact) {
            // OnePlus widens ordinary exact alarms even for allowlisted apps.
            val showIntent = PendingIntent.getActivity(
                context, 0, Intent(context, io.github.aoguai.sesameag.ui.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMs, showIntent), token)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, token)
        }
        return true
    }

    private fun laneFor(schedule: PersistentSchedule): Int =
        if (schedule.attemptCount > 0 || schedule.lastError in setOf("launch_pending", "delivery_pending") ||
            PersistentSchedulePrecisionPolicy.isStrict(schedule.precisionPolicy, schedule.kind)
        ) LANE_EXACT else LANE_FLEXIBLE

    private fun updateLocalTimer(context: Context, schedules: List<PersistentSchedule>): Boolean {
        if (context.packageName != General.PACKAGE_NAME) return false
        targetContext = context
        val session = AccountSessionCoordinator.currentSession()
        val next = schedules.filter {
            it.ownerUserId == session?.userId && it.sessionEpoch == session?.sessionEpoch
        }.minByOrNull { it.triggerAtMs }
        if (next == localSchedule && localJob?.isActive == true) return true
        localJob?.cancel()
        localJob = null
        localSchedule = next
        if (next == null) return schedules.isEmpty()
        val job = timerScope.launch(start = CoroutineStart.LAZY) {
            delay((next.triggerAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
            val current = synchronized(plannerLock) {
                if (localSchedule != next) false else {
                    localSchedule = null
                    localJob = null
                    true
                }
            }
            if (current) PersistentScheduleRegistry.fireDueSchedules(context, "process_timer")
        }
        localJob = job
        job.start()
        return true
    }

    internal fun cancelLocalTimer() = synchronized(plannerLock) {
        localJob?.cancel()
        localJob = null
        localSchedule = null
        targetContext = null
    }

    // 兼容本进程中尚未清理的旧命名计时任务；新确认由持久记录和精确通道承担。
    internal fun cancelLaunchConfirmationTimeout(scheduleId: String) {
        UnifiedScheduler.cancelNamedTask("persistent_launch_timeout:$scheduleId")
    }

    private fun cancelLegacyPlanner(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        val receiverIntent = Intent(context, ScheduledTriggerReceiver::class.java).apply {
            action = ACTION_TRIGGER
            data = Uri.Builder().scheme("sesameag").authority("persistent-schedule-plan").build()
        }
        val launchIntent = if (context.packageName == General.PACKAGE_NAME) {
            Intent(Intent.ACTION_VIEW).setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY)
        } else {
            context.packageManager.getLaunchIntentForPackage(General.PACKAGE_NAME)
                ?: Intent(Intent.ACTION_VIEW).setPackage(General.PACKAGE_NAME)
        }
        launchIntent.data = Uri.Builder().scheme("sesameag").authority("persistent-schedule-plan-launch").build()
        runCatching {
            listOfNotNull(
                PendingIntent.getBroadcast(context, PLANNER_REQUEST_CODE, receiverIntent, flags),
                PendingIntent.getActivity(context, PLANNER_REQUEST_CODE, launchIntent, flags),
            ).forEach {
                alarmManager.cancel(it)
                it.cancel()
            }
        }.onFailure { Log.printStackTrace(TAG, "取消旧物理闹钟失败", it) }
    }

    suspend fun launchTargetNow(context: Context, schedule: PersistentSchedule): Boolean {
        val component = context.packageManager.getLaunchIntentForPackage(General.PACKAGE_NAME)
            ?.resolveActivity(context.packageManager) ?: return false
        if (component.packageName != General.PACKAGE_NAME) return false
        val userId = android.os.Process.myUserHandle().hashCode()
        val command = listOf(
            "am", "start", "--user", userId.toString(), "-n", component.flattenToString(),
            "-a", Intent.ACTION_MAIN, "-f", Intent.FLAG_ACTIVITY_NEW_TASK.toString(),
            "--es", EXTRA_SCHEDULE_ID, schedule.id,
            "--ez", EXTRA_PERSISTENT_ALARM_LAUNCH, "true",
            "--el", EXTRA_CONFIRMATION_AT, schedule.updatedAtMs.toString(),
        )
        // Shizuku splits argv directly; shell quotes would become literal characters.
        if (command.any { !it.matches(Regex("[A-Za-z0-9_./:$-]+")) }) return false
        return try {
            withTimeoutOrNull(5_000L) {
                val output = CommandUtil.executeCommand(context, command.joinToString(" "))
                if (output == null) {
                    Log.record(TAG, "定向启动请求失败[${schedule.name}]")
                    false
                } else {
                    Log.record(TAG, "已请求定向启动，等待持久任务确认[${schedule.name}]")
                    true
                }
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "定向启动目标应用失败[${schedule.name}]", e)
            false
        }
    }
}

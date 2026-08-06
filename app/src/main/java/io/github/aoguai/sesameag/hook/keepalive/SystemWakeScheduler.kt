package io.github.aoguai.sesameag.hook.keepalive

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.PermissionUtil
import io.github.aoguai.sesameag.util.TimeUtil
import kotlin.math.absoluteValue

object SystemWakeScheduler {
    private const val TAG = "SystemWakeScheduler"
    private const val MIN_FLEXIBLE_WINDOW_MS = 10 * 60 * 1000L
    internal const val ACTION_TRIGGER = "io.github.aoguai.sesameag.action.PERSISTENT_SCHEDULE_TRIGGER"
    internal const val EXTRA_SCHEDULE_ID = "schedule_id"
    internal const val EXTRA_PERSISTENT_ALARM_LAUNCH = "persistent_alarm_launch"
    internal const val EXTRA_PLANNED_BATCH = "persistent_planned_batch"
    private const val PLANNER_REQUEST_CODE = 0x53534147
    private const val LAUNCH_CONFIRMATION_TIMEOUT_MS = 30_000L
    private val launchConfirmationLock = Any()
    private val launchConfirmationWatchdogs = mutableMapOf<String, LaunchConfirmationWatchdog>()
    private var plannerLaunchScheduleId: String? = null

    private data class LaunchConfirmationWatchdog(
        val absoluteDeadlineMs: Long,
        val triggerAtMs: Long,
        val updatedAtMs: Long,
    )

    private data class AlarmPlan(
        val primary: PersistentSchedule,
        val triggerAtMs: Long,
        val precisionPolicy: String,
    )

    /**
     * The registry is the durable source of truth.  AlarmManager receives only its next physical
     * wake-up, so concurrent child schedules share one receiver/launch instead of one alarm each.
     */
    fun schedule(
        context: Context,
        schedule: PersistentSchedule,
        silent: Boolean = false,
    ): Boolean {
        val plan =
            selectPlan(PersistentScheduleRegistry.list()) ?: run {
                cancelPlanner(context, silent = true)
                return true
            }
        val alarmContexts = resolveAlarmContexts(context)
        if (alarmContexts.isEmpty()) return false
        alarmContexts.forEachIndexed { index, alarmContext ->
            if (schedulePlanOnContext(alarmContext, plan, silent)) {
                return true
            }
            if (index == 0 && alarmContexts.size > 1) {
                Log.runtime(TAG, "模块系统闹钟注册失败，尝试目标应用拉起调度[${schedule.name}]")
            }
        }
        return false
    }

    private fun selectPlan(schedules: List<PersistentSchedule>): AlarmPlan? {
        val scheduled = schedules.filter { it.state == PersistentScheduleState.SCHEDULED }
        if (scheduled.isEmpty()) return null
        val strict =
            scheduled
                .filter { PersistentSchedulePrecisionPolicy.isStrict(it.effectivePrecisionPolicy(), it.kind) }
                .minByOrNull { it.triggerAtMs }
        val primary = strict ?: scheduled.minByOrNull { it.triggerAtMs } ?: return null
        return AlarmPlan(
            primary = primary,
            triggerAtMs = primary.triggerAtMs.coerceAtLeast(System.currentTimeMillis()),
            precisionPolicy = primary.effectivePrecisionPolicy(),
        )
    }

    private fun schedulePlanOnContext(
        alarmContext: Context,
        plan: AlarmPlan,
        silent: Boolean,
    ): Boolean {
        val alarmManager =
            alarmContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false
        return try {
            val pendingIntent =
                buildPlannerPendingIntent(alarmContext, plan.primary, PendingIntent.FLAG_UPDATE_CURRENT)
                    ?: return false
            if (PersistentSchedulePrecisionPolicy.isStrict(plan.precisionPolicy, plan.primary.kind)) {
                if (PermissionUtil.checkAlarmPermissions(alarmContext)) {
                    scheduleExact(alarmManager, plan.triggerAtMs, pendingIntent)
                } else {
                    scheduleStrictFallback(alarmManager, plan.triggerAtMs, pendingIntent)
                }
            } else {
                scheduleFlexible(alarmManager, plan.triggerAtMs, plan.primary.toleranceMs, pendingIntent)
            }
            updatePlannerLaunchConfirmationTimeout(alarmContext, plan.primary, plan.triggerAtMs)
            if (!silent) {
                Log.runtime(
                    TAG,
                    "已重排物理系统闹钟[${plan.primary.name}] ${TimeUtil.getCommonDate(plan.triggerAtMs)} policy=${plan.precisionPolicy}",
                )
            }
            true
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "重排物理系统闹钟失败[${plan.primary.name}]", t)
            false
        }
    }

    private fun scheduleExact(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            return
        }
        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    private fun scheduleFlexible(
        alarmManager: AlarmManager,
        triggerAt: Long,
        toleranceMs: Long,
        pendingIntent: PendingIntent,
    ) {
        // targetSdk 36 设备上窗口过小会被系统延长；主动给出 10 分钟容差，避免普通轮询穿透 Doze。
        val safeTolerance = toleranceMs.coerceAtLeast(MIN_FLEXIBLE_WINDOW_MS)
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, safeTolerance, pendingIntent)
    }

    private fun scheduleStrictFallback(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelPlanner(
        context: Context,
        silent: Boolean,
    ) {
        clearPlannerLaunchConfirmationTimeout()
        val marker = PersistentSchedule(id = "persistent-alarm-plan")
        resolveAlarmContexts(context).forEach { alarmContext ->
            val alarmManager = alarmContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@forEach
            val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            val receiverIntent =
                Intent(alarmContext, ScheduledTriggerReceiver::class.java).apply {
                    action = ACTION_TRIGGER
                    data =
                        Uri
                            .Builder()
                            .scheme("sesameag")
                            .authority("persistent-schedule-plan")
                            .build()
                }
            val launchIntent =
                buildTargetLaunchIntent(alarmContext, marker).apply {
                    data =
                        Uri
                            .Builder()
                            .scheme("sesameag")
                            .authority("persistent-schedule-plan-launch")
                            .build()
                }
            listOfNotNull(
                PendingIntent.getBroadcast(alarmContext, PLANNER_REQUEST_CODE, receiverIntent, flags),
                PendingIntent.getActivity(alarmContext, PLANNER_REQUEST_CODE, launchIntent, flags),
            ).forEach { pendingIntent ->
                runCatching {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                }.onFailure { error ->
                    Log.printStackTrace(TAG, "取消物理系统闹钟失败", error)
                }
            }
            if (!silent) {
                Log.runtime(TAG, "已取消物理系统闹钟 package=${alarmContext.packageName}")
            }
        }
    }

    private fun buildPlannerPendingIntent(
        context: Context,
        primary: PersistentSchedule,
        updateFlag: Int,
    ): PendingIntent? {
        val intent =
            if (context.packageName == General.MODULE_PACKAGE_NAME || !shouldLaunchTarget(primary)) {
                Intent(context, ScheduledTriggerReceiver::class.java).apply {
                    action = ACTION_TRIGGER
                    data =
                        Uri
                            .Builder()
                            .scheme("sesameag")
                            .authority("persistent-schedule-plan")
                            .build()
                    putExtra(EXTRA_SCHEDULE_ID, primary.id)
                    putExtra(EXTRA_PLANNED_BATCH, true)
                }
            } else {
                buildTargetLaunchIntent(context, primary).apply {
                    data =
                        Uri
                            .Builder()
                            .scheme("sesameag")
                            .authority("persistent-schedule-plan-launch")
                            .build()
                    putExtra(EXTRA_PLANNED_BATCH, true)
                }
            }
        val flags = updateFlag or PendingIntent.FLAG_IMMUTABLE
        return try {
            if (context.packageName == General.MODULE_PACKAGE_NAME || !shouldLaunchTarget(primary)) {
                PendingIntent.getBroadcast(context, PLANNER_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getActivity(
                    context,
                    PLANNER_REQUEST_CODE,
                    intent,
                    flags,
                    creatorLaunchOptions(allowAlways = true),
                )
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "创建物理系统闹钟 PendingIntent 失败", t)
            null
        }
    }

    private fun shouldLaunchTarget(schedule: PersistentSchedule): Boolean = PersistentLaunchPolicy.shouldLaunchTarget(schedule)

    private fun buildTargetLaunchPendingIntent(
        context: Context,
        schedule: PersistentSchedule,
        updateFlag: Int,
        allowBackgroundAlways: Boolean,
    ): PendingIntent? {
        val intent = buildTargetLaunchIntent(context, schedule)
        val flags = updateFlag or PendingIntent.FLAG_IMMUTABLE
        return try {
            PendingIntent.getActivity(
                context,
                requestCodeFor(schedule.id),
                intent,
                flags,
                creatorLaunchOptions(allowAlways = allowBackgroundAlways),
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "创建目标应用 PendingIntent 失败[${schedule.name}]", t)
            null
        }
    }

    fun launchTargetNow(
        context: Context,
        schedule: PersistentSchedule,
        allowBackgroundAlways: Boolean = false,
    ): Boolean {
        val pendingIntent =
            buildTargetLaunchPendingIntent(
                context.applicationContext ?: context,
                schedule,
                PendingIntent.FLAG_UPDATE_CURRENT,
                allowBackgroundAlways = allowBackgroundAlways,
            ) ?: return false
        return try {
            pendingIntent.send(
                context,
                0,
                null,
                null,
                null,
                null,
                senderLaunchOptions(allowAlways = allowBackgroundAlways),
            )
            scheduleLaunchConfirmationTimeout(context, schedule, System.currentTimeMillis())
            Log.record(TAG, "已通过 PendingIntent 请求拉起目标应用[${schedule.name}]")
            true
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "PendingIntent 拉起目标应用失败[${schedule.name}]", t)
            false
        }
    }

    fun scheduleLaunchConfirmationTimeout(
        context: Context,
        schedule: PersistentSchedule,
        triggerAtMs: Long = schedule.triggerAtMs,
    ) {
        synchronized(launchConfirmationLock) {
            scheduleLaunchConfirmationTimeoutLocked(context, schedule, triggerAtMs)
        }
    }

    internal fun cancelLaunchConfirmationTimeout(scheduleId: String) {
        if (scheduleId.isBlank()) return
        synchronized(launchConfirmationLock) {
            cancelLaunchConfirmationTimeoutLocked(scheduleId)
            if (plannerLaunchScheduleId == scheduleId) {
                plannerLaunchScheduleId = null
            }
        }
    }

    private fun updatePlannerLaunchConfirmationTimeout(
        context: Context,
        schedule: PersistentSchedule,
        triggerAtMs: Long,
    ) {
        synchronized(launchConfirmationLock) {
            val nextScheduleId = schedule.id.takeIf { shouldLaunchTarget(schedule) }
            val previousScheduleId = plannerLaunchScheduleId
            if (previousScheduleId != null && previousScheduleId != nextScheduleId) {
                cancelLaunchConfirmationTimeoutLocked(previousScheduleId)
            }
            plannerLaunchScheduleId = nextScheduleId
            if (nextScheduleId != null) {
                scheduleLaunchConfirmationTimeoutLocked(context, schedule, triggerAtMs)
            }
        }
    }

    private fun clearPlannerLaunchConfirmationTimeout() {
        synchronized(launchConfirmationLock) {
            plannerLaunchScheduleId?.let(::cancelLaunchConfirmationTimeoutLocked)
            plannerLaunchScheduleId = null
        }
    }

    private fun scheduleLaunchConfirmationTimeoutLocked(
        context: Context,
        schedule: PersistentSchedule,
        triggerAtMs: Long,
    ) {
        val absoluteDeadlineMs = triggerAtMs + LAUNCH_CONFIRMATION_TIMEOUT_MS
        val existingWatchdog = launchConfirmationWatchdogs[schedule.id]
        if (existingWatchdog != null) {
            if (
                existingWatchdog.absoluteDeadlineMs == absoluteDeadlineMs &&
                existingWatchdog.triggerAtMs == schedule.triggerAtMs &&
                existingWatchdog.updatedAtMs == schedule.updatedAtMs
            ) {
                return
            }
            // 同一计划已发生改期或状态更新，旧 watchdog 的状态快照不能再驱动延期。
            cancelLaunchConfirmationTimeoutLocked(schedule.id)
        }
        val watchdog =
            LaunchConfirmationWatchdog(
                absoluteDeadlineMs = absoluteDeadlineMs,
                triggerAtMs = schedule.triggerAtMs,
                updatedAtMs = schedule.updatedAtMs,
            )
        launchConfirmationWatchdogs[schedule.id] = watchdog
        val delayMs = (absoluteDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val scheduledTaskId = UnifiedScheduler.scheduleLongDelay(delayMs, launchConfirmationTaskName(schedule.id)) {
            val shouldReschedule =
                synchronized(launchConfirmationLock) {
                    if (launchConfirmationWatchdogs[schedule.id] !== watchdog) {
                        false
                    } else {
                        launchConfirmationWatchdogs.remove(schedule.id)
                        if (plannerLaunchScheduleId == schedule.id) {
                            plannerLaunchScheduleId = null
                        }
                        true
                    }
                }
            if (!shouldReschedule) {
                return@scheduleLongDelay
            }
            val current = PersistentScheduleRegistry.get(schedule.id) ?: return@scheduleLongDelay
            val now = System.currentTimeMillis()
            if (
                current.state == PersistentScheduleState.SCHEDULED &&
                current.triggerAtMs == watchdog.triggerAtMs &&
                current.updatedAtMs == watchdog.updatedAtMs &&
                current.triggerAtMs <= now
            ) {
                PersistentScheduleRegistry.rescheduleDeferred(
                    context.applicationContext ?: context,
                    current.id,
                    "launch_unconfirmed",
                    now,
                )
            }
        }
        if (scheduledTaskId == -1 && launchConfirmationWatchdogs[schedule.id] === watchdog) {
            launchConfirmationWatchdogs.remove(schedule.id)
        }
    }

    private fun cancelLaunchConfirmationTimeoutLocked(scheduleId: String) {
        launchConfirmationWatchdogs.remove(scheduleId)
        UnifiedScheduler.cancelNamedTask(launchConfirmationTaskName(scheduleId))
    }

    private fun launchConfirmationTaskName(scheduleId: String): String =
        "persistent_launch_timeout:$scheduleId"

    private fun buildTargetLaunchIntent(
        context: Context,
        schedule: PersistentSchedule,
    ): Intent {
        val launchIntent =
            if (context.packageName == General.PACKAGE_NAME) {
                Intent(Intent.ACTION_VIEW).apply {
                    setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY)
                }
            } else {
                context.packageManager.getLaunchIntentForPackage(General.PACKAGE_NAME)
                    ?: Intent(Intent.ACTION_VIEW).apply { setPackage(General.PACKAGE_NAME) }
            }
        return Intent(launchIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data =
                Uri
                    .Builder()
                    .scheme("sesameag")
                    .authority("persistent-schedule-launch")
                    .appendPath(schedule.id)
                    .build()
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
            putExtra(EXTRA_PERSISTENT_ALARM_LAUNCH, true)
        }
    }

    private fun creatorLaunchOptions(allowAlways: Boolean): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && !allowAlways) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        return ActivityOptions
            .makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(mode)
            .toBundle()
    }

    private fun senderLaunchOptions(allowAlways: Boolean): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && !allowAlways) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        return ActivityOptions
            .makeBasic()
            .setPendingIntentBackgroundActivityStartMode(mode)
            .toBundle()
    }

    private fun requestCodeFor(id: String): Int {
        val hash = id.hashCode()
        return if (hash == Int.MIN_VALUE) 0 else hash.absoluteValue
    }

    private fun resolveAlarmContexts(context: Context): List<Context> {
        val appContext = context.applicationContext ?: context
        val contexts = linkedSetOf<Context>()
        if (appContext.packageName == General.MODULE_PACKAGE_NAME) {
            contexts.add(appContext)
        } else {
            // PendingIntent creator package must belong to the current UID; Hook target processes
            // cannot safely create a module-package receiver PendingIntent.
            if (currentUidOwnsPackage(appContext, General.MODULE_PACKAGE_NAME)) {
                resolveModuleContext(appContext)?.let { contexts.add(it) }
            }
            if (appContext.packageName == General.PACKAGE_NAME) {
                contexts.add(appContext)
            }
        }
        return contexts.toList()
    }

    @Suppress("DEPRECATION")
    private fun currentUidOwnsPackage(
        context: Context,
        packageName: String,
    ): Boolean =
        try {
            context.packageManager.getPackageUid(packageName, 0) == Process.myUid()
        } catch (_: Throwable) {
            false
        }

    private fun resolveModuleContext(context: Context): Context? {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName == General.MODULE_PACKAGE_NAME) {
            return appContext
        }
        return try {
            appContext.createPackageContext(General.MODULE_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "无法创建模块 Context", t)
            null
        }
    }
}

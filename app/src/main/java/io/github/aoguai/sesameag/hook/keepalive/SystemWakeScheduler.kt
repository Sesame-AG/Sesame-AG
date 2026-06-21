package io.github.aoguai.sesameag.hook.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.ActivityOptions
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
    internal const val ACTION_TRIGGER = "io.github.aoguai.sesameag.action.PERSISTENT_SCHEDULE_TRIGGER"
    internal const val EXTRA_SCHEDULE_ID = "schedule_id"
    internal const val EXTRA_PERSISTENT_ALARM_LAUNCH = "persistent_alarm_launch"

    fun schedule(context: Context, schedule: PersistentSchedule, silent: Boolean = false): Boolean {
        val alarmContexts = resolveAlarmContexts(context)
        if (alarmContexts.isEmpty()) return false
        alarmContexts.forEachIndexed { index, alarmContext ->
            if (scheduleOnContext(alarmContext, schedule, silent)) {
                return true
            }
            if (index == 0 && alarmContexts.size > 1) {
                Log.runtime(TAG, "模块系统闹钟注册失败，尝试目标应用拉起调度[${schedule.name}]")
            }
        }
        return false
    }

    private fun scheduleOnContext(alarmContext: Context, schedule: PersistentSchedule, silent: Boolean): Boolean {
        val alarmManager = alarmContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Log.error(TAG, "无法获取 AlarmManager，持久调度失败: ${schedule.name}")
            return false
        }

        val triggerAt = schedule.triggerAtMs.coerceAtLeast(System.currentTimeMillis())
        return try {
            val pendingIntent = buildPendingIntent(alarmContext, schedule, PendingIntent.FLAG_UPDATE_CURRENT)
                ?: run {
                    Log.error(TAG, "创建 PendingIntent 失败，持久调度失败: ${schedule.name}")
                    return false
                }
            if (PermissionUtil.checkAlarmPermissions(alarmContext)) {
                scheduleExact(alarmManager, triggerAt, pendingIntent)
                if (!silent) {
                    Log.runtime(TAG, "已注册精确系统闹钟[${schedule.name}] ${TimeUtil.getCommonDate(triggerAt)}")
                }
            } else {
                scheduleFallback(alarmManager, triggerAt, schedule.toleranceMs, pendingIntent)
                if (!silent) {
                    Log.runtime(TAG, "精确闹钟权限缺失，已降级注册系统闹钟[${schedule.name}] ${TimeUtil.getCommonDate(triggerAt)}")
                }
            }
            true
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "注册系统闹钟失败[${schedule.name}]", t)
            false
        }
    }

    fun cancel(context: Context, schedule: PersistentSchedule, silent: Boolean = false) {
        resolveAlarmContexts(context).forEach { alarmContext ->
            val alarmManager = alarmContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@forEach
            var cancelled = false
            listOfNotNull(
                buildReceiverPendingIntent(alarmContext, schedule, PendingIntent.FLAG_NO_CREATE),
                buildTargetLaunchPendingIntent(
                    alarmContext,
                    schedule,
                    PendingIntent.FLAG_NO_CREATE,
                    allowBackgroundAlways = true
                )
            ).forEach { pendingIntent ->
                try {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                    cancelled = true
                } catch (t: Throwable) {
                    Log.printStackTrace(TAG, "取消系统闹钟失败[${schedule.name}]", t)
                }
            }
            if (cancelled && !silent) {
                Log.runtime(TAG, "已取消系统闹钟[${schedule.name}] package=${alarmContext.packageName}")
            }
        }
    }

    private fun scheduleExact(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            return
        }
        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    private fun scheduleFallback(
        alarmManager: AlarmManager,
        triggerAt: Long,
        toleranceMs: Long,
        pendingIntent: PendingIntent
    ) {
        val safeTolerance = toleranceMs.coerceAtLeast(60_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            return
        }
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, safeTolerance, pendingIntent)
    }

    private fun buildPendingIntent(
        context: Context,
        schedule: PersistentSchedule,
        updateFlag: Int
    ): PendingIntent? {
        return if (context.packageName == General.MODULE_PACKAGE_NAME) {
            buildReceiverPendingIntent(context, schedule, updateFlag)
        } else if (shouldLaunchTarget(schedule)) {
            buildTargetLaunchPendingIntent(
                context,
                schedule,
                updateFlag,
                allowBackgroundAlways = true
            )
        } else {
            buildReceiverPendingIntent(context, schedule, updateFlag)
        }
    }

    private fun shouldLaunchTarget(schedule: PersistentSchedule): Boolean {
        return PersistentLaunchPolicy.shouldLaunchTarget(schedule)
    }

    private fun buildReceiverPendingIntent(
        context: Context,
        schedule: PersistentSchedule,
        updateFlag: Int
    ): PendingIntent? {
        val intent = Intent(context, ScheduledTriggerReceiver::class.java).apply {
            action = ACTION_TRIGGER
            data = Uri.Builder()
                .scheme("sesameag")
                .authority("persistent-schedule")
                .appendPath(schedule.id)
                .build()
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
        }
        val flags = updateFlag or PendingIntent.FLAG_IMMUTABLE
        return try {
            PendingIntent.getBroadcast(context, requestCodeFor(schedule.id), intent, flags)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "创建 PendingIntent 失败[${schedule.name}]", t)
            null
        }
    }

    private fun buildTargetLaunchPendingIntent(
        context: Context,
        schedule: PersistentSchedule,
        updateFlag: Int,
        allowBackgroundAlways: Boolean
    ): PendingIntent? {
        val intent = buildTargetLaunchIntent(context, schedule)
        val flags = updateFlag or PendingIntent.FLAG_IMMUTABLE
        return try {
            PendingIntent.getActivity(
                context,
                requestCodeFor(schedule.id),
                intent,
                flags,
                creatorLaunchOptions(allowAlways = allowBackgroundAlways)
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "创建目标应用 PendingIntent 失败[${schedule.name}]", t)
            null
        }
    }

    fun launchTargetNow(context: Context, schedule: PersistentSchedule, allowBackgroundAlways: Boolean = false): Boolean {
        val pendingIntent = buildTargetLaunchPendingIntent(
            context.applicationContext ?: context,
            schedule,
            PendingIntent.FLAG_UPDATE_CURRENT,
            allowBackgroundAlways = allowBackgroundAlways
        ) ?: return false
        return try {
            pendingIntent.send(
                context,
                0,
                null,
                null,
                null,
                null,
                senderLaunchOptions(allowAlways = allowBackgroundAlways)
            )
            Log.record(TAG, "已通过 PendingIntent 拉起目标应用[${schedule.name}]")
            true
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "PendingIntent 拉起目标应用失败[${schedule.name}]", t)
            false
        }
    }

    private fun buildTargetLaunchIntent(context: Context, schedule: PersistentSchedule): Intent {
        val launchIntent = if (context.packageName == General.PACKAGE_NAME) {
            Intent(Intent.ACTION_VIEW).apply {
                setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(General.PACKAGE_NAME)
                ?: Intent(Intent.ACTION_VIEW).apply { setPackage(General.PACKAGE_NAME) }
        }
        return Intent(launchIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.Builder()
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
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && !allowAlways) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            @Suppress("DEPRECATION")
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        return ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(mode)
            .toBundle()
    }

    private fun senderLaunchOptions(allowAlways: Boolean): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && !allowAlways) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            @Suppress("DEPRECATION")
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        return ActivityOptions.makeBasic()
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
    private fun currentUidOwnsPackage(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageUid(packageName, 0) == Process.myUid()
        } catch (_: Throwable) {
            false
        }
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

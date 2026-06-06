package io.github.aoguai.sesameag.hook.keepalive

import android.content.Context
import android.content.Intent
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.ApplicationHookCore
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.antFarm.AntFarm
import io.github.aoguai.sesameag.task.antForest.EnergyWaitingManager
import io.github.aoguai.sesameag.task.antStall.AntStall
import io.github.aoguai.sesameag.task.antSports.AntSports
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject

object ScheduledTaskRouter {
    private const val TAG = "ScheduledTaskRouter"
    private const val REOPEN_AT_PREFIX = "persistentScheduleReopenAt::"
    private const val REOPEN_FAILURE_COUNT_PREFIX = "persistentScheduleReopenFailureCount::"
    private const val REOPEN_FAILURE_AT_PREFIX = "persistentScheduleReopenFailureAt::"
    internal const val EXTRA_PERSISTENT_KIND = "persistent_schedule_kind"
    internal const val EXTRA_PERSISTENT_ID = "persistent_schedule_id"
    internal const val EXTRA_PERSISTENT_NAME = "persistent_schedule_name"

    private enum class RouteResult {
        HANDLED,
        CONSUMED,
        DEFERRED,
        FAILED,
        SKIPPED
    }

    fun fire(context: Context, schedule: PersistentSchedule, source: String): Boolean {
        return try {
            val appContext = context.applicationContext ?: context
            if (schedule.state != PersistentScheduleState.SCHEDULED) {
                Log.record(TAG, "持久任务[${schedule.name}]状态为${schedule.state}，忽略 source=$source")
                return true
            }
            val now = System.currentTimeMillis()
            if (schedule.triggerAtMs > now) {
                SystemWakeScheduler.schedule(appContext, schedule)
                Log.runtime(TAG, "持久任务[${schedule.name}]未到触发时间，已重排 source=$source")
                return true
            }
            val toleranceMs = schedule.toleranceMs.coerceAtLeast(0L)
            if (now - schedule.triggerAtMs > toleranceMs) {
                PersistentScheduleRegistry.markExpired(appContext, schedule.id, now)
                Log.record(TAG, "持久任务[${schedule.name}]超过触发窗口，已过期 source=$source")
                return true
            }
            val targetProcess = isTargetProcess(appContext)
            val routeResult = routeInternal(context, schedule, source)
            when (routeResult) {
                RouteResult.HANDLED -> Unit
                RouteResult.CONSUMED -> if (targetProcess) {
                    PersistentScheduleRegistry.markFired(appContext, schedule.id)
                }
                RouteResult.DEFERRED -> Unit
                RouteResult.FAILED -> if (targetProcess) {
                    PersistentScheduleRegistry.markFailed(appContext, schedule.id, "unhandled kind=${schedule.kind}")
                }
                RouteResult.SKIPPED -> if (targetProcess) {
                    PersistentScheduleRegistry.markFired(appContext, schedule.id)
                }
            }
            routeResult != RouteResult.FAILED
        } catch (t: Throwable) {
            val appContext = context.applicationContext ?: context
            if (isTargetProcess(appContext)) {
                PersistentScheduleRegistry.markFailed(appContext, schedule.id, t.message ?: t.javaClass.name)
            }
            Log.printStackTrace(TAG, "持久任务路由失败[${schedule.name}]", t)
            false
        }
    }

    fun route(context: Context, schedule: PersistentSchedule, source: String): Boolean {
        return routeInternal(context, schedule, source) != RouteResult.FAILED
    }

    private fun routeInternal(context: Context, schedule: PersistentSchedule, source: String): RouteResult {
        val appContext = context.applicationContext ?: context
        val targetProcess = isTargetProcess(appContext)
        val currentSession = AccountSessionCoordinator.currentSession()
        if (targetProcess && currentSession == null) {
            Log.record(
                TAG,
                "当前会话尚未建立，延后持久任务[${schedule.name}] source=$source"
            )
            return RouteResult.DEFERRED
        }
        if (targetProcess && !AccountSessionCoordinator.isScheduleRoutable(schedule)) {
            Log.record(
                TAG,
                "持久任务会话不匹配，丢弃调度[${schedule.name}] owner=${schedule.ownerUserId} session=${schedule.sessionEpoch} current=$currentSession"
            )
            return RouteResult.SKIPPED
        }
        if (targetProcess && ApplicationHookConstants.isOffline()) {
            Log.record(TAG, "离线状态中，延后持久任务[${schedule.name}] source=$source")
            return RouteResult.DEFERRED
        }
        return when (schedule.kind) {
            PersistentScheduleKind.GLOBAL_POLL -> routeResult(
                dispatchExecute(appContext, schedule, source, false, null)
            )
            PersistentScheduleKind.GLOBAL_WAKEUP -> {
                val payload = payloadOf(schedule)
                routeResult(
                    dispatchExecute(
                        appContext,
                        schedule,
                        source,
                        wakenAtTime = true,
                        wakenTime = payload.optString("waken_time").takeIf { it.isNotBlank() }
                    )
                )
            }
            PersistentScheduleKind.GLOBAL_PREWAKEUP -> routeResult(dispatchPreWakeup(appContext, schedule, source))
            PersistentScheduleKind.MODULE_CHILD -> dispatchModuleChild(appContext, schedule, source)
            else -> {
                Log.record(TAG, "未知持久任务类型[${schedule.kind}] name=${schedule.name}")
                RouteResult.FAILED
            }
        }
    }

    private fun routeResult(handled: Boolean): RouteResult {
        return if (handled) RouteResult.HANDLED else RouteResult.FAILED
    }

    private fun dispatchExecute(
        context: Context,
        schedule: PersistentSchedule,
        source: String,
        wakenAtTime: Boolean,
        wakenTime: String?
    ): Boolean {
        val intent = Intent(ApplicationHookConstants.BroadcastActions.EXECUTE).apply {
            setPackage(General.PACKAGE_NAME)
            putExtra("alarm_triggered", true)
            putExtra("waken_at_time", wakenAtTime)
            if (!wakenTime.isNullOrBlank()) {
                putExtra("waken_time", wakenTime)
            }
            putExtra(EXTRA_PERSISTENT_KIND, schedule.kind)
            putExtra(EXTRA_PERSISTENT_ID, schedule.id)
            putExtra(EXTRA_PERSISTENT_NAME, schedule.name)
        }

        if (isTargetProcess(context)) {
            requestExecutionInCurrentProcess(schedule, source, wakenAtTime, wakenTime)
            return true
        }

        maybeLaunchTarget(context, schedule, source)
        sendTargetBroadcast(context, intent, schedule, source)
        return true
    }

    private fun dispatchPreWakeup(context: Context, schedule: PersistentSchedule, source: String): Boolean {
        val payload = payloadOf(schedule)
        val executionTime = payload.optLong("execution_time", schedule.triggerAtMs)
        val intent = Intent(ApplicationHookConstants.BroadcastActions.PRE_WAKEUP).apply {
            setPackage(General.PACKAGE_NAME)
            putExtra("execution_time", executionTime)
            putExtra("force_execute", true)
            putExtra(EXTRA_PERSISTENT_KIND, schedule.kind)
            putExtra(EXTRA_PERSISTENT_ID, schedule.id)
            putExtra(EXTRA_PERSISTENT_NAME, schedule.name)
        }

        if (isTargetProcess(context)) {
            requestExecutionInCurrentProcess(
                schedule = schedule,
                source = source,
                wakenAtTime = false,
                wakenTime = null,
                executionTime = executionTime
            )
            return true
        }

        maybeLaunchTarget(context, schedule, source)
        sendTargetBroadcast(context, intent, schedule, source)
        return true
    }

    private fun dispatchModuleChild(context: Context, schedule: PersistentSchedule, source: String): RouteResult {
        val payload = payloadOf(schedule)
        val childKind = payload.optString("child_kind", "unknown")
        val ownerUserId = schedule.ownerUserId?.trim().orEmpty()
        val payloadOwnerUserId = payload.optString("owner_user_id").trim()
        val targetProcess = isTargetProcess(context)
        val currentOwnerUserId = AccountSessionCoordinator.currentUserId() ?: UserMap.currentUid
        val expectedOwnerUserId = ownerUserId.ifBlank { payloadOwnerUserId }
        if (targetProcess && expectedOwnerUserId.isNotEmpty() && expectedOwnerUserId != currentOwnerUserId) {
            Log.record(
                TAG,
                "模块持久子任务账号不匹配，标记完成[${schedule.name}] owner=$expectedOwnerUserId current=$currentOwnerUserId"
            )
            return RouteResult.SKIPPED
        }
        Log.record(
            TAG,
            "模块持久子任务到期[${schedule.name}] kind=$childKind source=$source"
        )
        if (childKind == EnergyWaitingManager.PERSISTENT_CHILD_KIND) {
            val taskId = payload.optString("task_id").trim()
            if (taskId.isBlank()) {
                Log.record(TAG, "森林蹲点持久任务缺少 task_id: ${schedule.name}")
                return RouteResult.FAILED
            }
            if (targetProcess) {
                return routeResult(
                    EnergyWaitingManager.triggerPersistentWaitingTask(taskId, schedule.payloadJson, source)
                )
            }
            return routeResult(dispatchExecute(context, schedule, source, wakenAtTime = false, wakenTime = null))
        }
        if (childKind == AntFarm.PERSISTENT_CHILD_KIND) {
            val childId = payload.optString("child_id").trim()
            val group = payload.optString("group").trim()
            if (childId.isBlank() || group.isBlank()) {
                Log.record(TAG, "庄园持久子任务缺少 child_id/group: ${schedule.name}")
                return RouteResult.FAILED
            }
            if (targetProcess) {
                val antFarm = Model.getModel(AntFarm::class.java)
                if (antFarm != null) {
                    if (!antFarm.isEnable()) {
                        Log.record(TAG, "庄园持久子任务触发时模块已关闭，标记完成: ${schedule.name}")
                        return RouteResult.SKIPPED
                    }
                    return if (antFarm.triggerPersistentChildTask(childId, group, schedule.payloadJson, source)) {
                        RouteResult.DEFERRED
                    } else {
                        RouteResult.FAILED
                    }
                }
                Log.record(TAG, "庄园实例尚未就绪，延后持久子任务: ${schedule.name}")
                return RouteResult.DEFERRED
            }
            return routeResult(dispatchExecute(context, schedule, source, wakenAtTime = false, wakenTime = null))
        }
        if (childKind == AntStall.PERSISTENT_CHILD_KIND) {
            val childId = payload.optString("child_id").trim()
            val group = payload.optString("group").trim()
            if (childId.isBlank() || group.isBlank()) {
                Log.record(TAG, "新村持久子任务缺少 child_id/group: ${schedule.name}")
                return RouteResult.FAILED
            }
            if (targetProcess) {
                val antStall = Model.getModel(AntStall::class.java)
                if (antStall != null) {
                    if (!antStall.isEnable()) {
                        Log.record(TAG, "新村持久子任务触发时模块已关闭，标记完成: ${schedule.name}")
                        return RouteResult.SKIPPED
                    }
                    return if (antStall.triggerPersistentChildTask(childId, group, schedule.payloadJson, source)) {
                        RouteResult.DEFERRED
                    } else {
                        RouteResult.FAILED
                    }
                }
                Log.record(TAG, "新村实例尚未就绪，延后持久子任务: ${schedule.name}")
                return RouteResult.DEFERRED
            }
            return routeResult(dispatchExecute(context, schedule, source, wakenAtTime = false, wakenTime = null))
        }
        if (childKind == AntSports.PERSISTENT_CHILD_KIND) {
            val childId = payload.optString("child_id").trim()
            val group = payload.optString("group").trim()
            if (childId.isBlank() || group.isBlank()) {
                Log.record(TAG, "运动持久子任务缺少 child_id/group: ${schedule.name}")
                return RouteResult.FAILED
            }
            if (targetProcess) {
                val antSports = Model.getModel(AntSports::class.java)
                if (antSports != null) {
                    if (!antSports.isEnable()) {
                        Log.record(TAG, "运动持久子任务触发时模块已关闭，标记完成: ${schedule.name}")
                        return RouteResult.SKIPPED
                    }
                    return if (antSports.triggerPersistentChildTask(childId, group, schedule.payloadJson, source)) {
                        RouteResult.DEFERRED
                    } else {
                        RouteResult.FAILED
                    }
                }
                Log.record(TAG, "运动实例尚未就绪，延后持久子任务: ${schedule.name}")
                return RouteResult.DEFERRED
            }
            return routeResult(dispatchExecute(context, schedule, source, wakenAtTime = false, wakenTime = null))
        }
        Log.record(TAG, "未知模块持久子任务类型[$childKind] name=${schedule.name}")
        return RouteResult.FAILED
    }

    private fun routeResult(result: EnergyWaitingManager.PersistentTriggerResult): RouteResult {
        return when (result) {
            EnergyWaitingManager.PersistentTriggerResult.HANDLED -> RouteResult.CONSUMED
            EnergyWaitingManager.PersistentTriggerResult.DEFERRED -> RouteResult.DEFERRED
            EnergyWaitingManager.PersistentTriggerResult.FAILED -> RouteResult.FAILED
        }
    }

    private fun sendTargetBroadcast(
        context: Context,
        intent: Intent,
        schedule: PersistentSchedule,
        source: String
    ) {
        try {
            context.sendBroadcast(intent)
            Log.record(TAG, "已投递持久任务广播[${schedule.name}] source=$source")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "投递持久任务广播失败[${schedule.name}]", t)
        }
    }

    private fun requestExecutionInCurrentProcess(
        schedule: PersistentSchedule,
        source: String,
        wakenAtTime: Boolean,
        wakenTime: String?,
        executionTime: Long? = null
    ) {
        val triggerType = when (schedule.kind) {
            PersistentScheduleKind.GLOBAL_POLL -> ApplicationHookConstants.TriggerType.ALARM_POLL
            PersistentScheduleKind.GLOBAL_WAKEUP -> ApplicationHookConstants.TriggerType.ALARM_WAKEUP
            PersistentScheduleKind.GLOBAL_PREWAKEUP -> ApplicationHookConstants.TriggerType.BROADCAST_PREWAKEUP
            else -> ApplicationHookConstants.TriggerType.BROADCAST_EXECUTE
        }
        val priority = when (triggerType) {
            ApplicationHookConstants.TriggerType.ALARM_POLL -> ApplicationHookConstants.TriggerPriority.LOW
            ApplicationHookConstants.TriggerType.BROADCAST_EXECUTE -> ApplicationHookConstants.TriggerPriority.NORMAL
            else -> ApplicationHookConstants.TriggerPriority.HIGH
        }
        val reason = if (executionTime != null && executionTime > 0L) {
            "prewakeup_to_${TimeUtil.getCommonDate(executionTime)}"
        } else {
            "persistent_${schedule.kind.lowercase()}_$source"
        }

        if (schedule.kind == PersistentScheduleKind.GLOBAL_WAKEUP) {
            if (wakenTime == "0000") {
                ApplicationHook.updateDay()
            }
            ApplicationHook.setWakenAtTimeAlarm()
        }

        ApplicationHookCore.requestExecution(
            ApplicationHookConstants.TriggerInfo(
                type = triggerType,
                priority = priority,
                alarmTriggered = true,
                wakenAtTime = wakenAtTime,
                wakenTime = wakenTime,
                reason = reason,
                dedupeKey = schedule.dedupeKey.ifBlank { schedule.id },
                persistentScheduleId = schedule.id,
                ownerUserId = schedule.ownerUserId,
                sessionEpoch = schedule.sessionEpoch
            )
        )
    }

    private fun maybeLaunchTarget(context: Context, schedule: PersistentSchedule, source: String): Boolean {
        if (!shouldLaunchTarget(schedule)) {
            return false
        }
        if (!consumeLaunchQuota(schedule)) {
            Log.record(TAG, "持久任务拉起目标应用被频控[${schedule.name}]")
            return false
        }
        val launched = SystemWakeScheduler.launchTargetNow(
            context,
            schedule,
            allowBackgroundAlways = source == "alarm"
        )
        if (launched) {
            clearLaunchFailures(schedule)
            return true
        }
        recordLaunchFailure(schedule, RuntimeException("pending_intent_launch_failed"))
        return false
    }

    private fun consumeLaunchQuota(schedule: PersistentSchedule): Boolean {
        val key = REOPEN_AT_PREFIX + schedule.dedupeKey.ifBlank { schedule.id }
        val now = System.currentTimeMillis()
        if (isInFailureCooldown(schedule, now)) {
            return false
        }
        val last = runCatching { DataStore.get(key, Long::class.javaObjectType) ?: 0L }
            .getOrDefault(0L)
        if (last > 0L && now - last < PersistentScheduleDefaults.REOPEN_COOLDOWN_MS) {
            return false
        }
        runCatching { DataStore.put(key, now) }
        return true
    }

    private fun isInFailureCooldown(schedule: PersistentSchedule, now: Long): Boolean {
        val key = schedule.dedupeKey.ifBlank { schedule.id }
        val failureCount = runCatching {
            DataStore.get(REOPEN_FAILURE_COUNT_PREFIX + key, Int::class.javaObjectType) ?: 0
        }.getOrDefault(0)
        if (failureCount < PersistentScheduleDefaults.REOPEN_FAILURE_THRESHOLD) {
            return false
        }
        val failureAt = runCatching {
            DataStore.get(REOPEN_FAILURE_AT_PREFIX + key, Long::class.javaObjectType) ?: 0L
        }.getOrDefault(0L)
        if (failureAt <= 0L) return false
        val cooldownElapsed = now - failureAt >= PersistentScheduleDefaults.REOPEN_FAILURE_COOLDOWN_MS
        if (cooldownElapsed) {
            clearLaunchFailures(schedule)
            return false
        }
        Log.record(TAG, "持久任务拉起目标应用处于失败冷却[${schedule.name}] count=$failureCount")
        return true
    }

    private fun recordLaunchFailure(schedule: PersistentSchedule, error: Throwable) {
        val key = schedule.dedupeKey.ifBlank { schedule.id }
        val countKey = REOPEN_FAILURE_COUNT_PREFIX + key
        val atKey = REOPEN_FAILURE_AT_PREFIX + key
        val count = runCatching { DataStore.get(countKey, Int::class.javaObjectType) ?: 0 }
            .getOrDefault(0) + 1
        runCatching {
            DataStore.put(countKey, count)
            DataStore.put(atKey, System.currentTimeMillis())
        }
        if (count >= PersistentScheduleDefaults.REOPEN_FAILURE_THRESHOLD) {
            Log.record(TAG, "持久任务拉起目标应用连续失败进入冷却[${schedule.name}] count=$count error=${error.message}")
        }
    }

    private fun clearLaunchFailures(schedule: PersistentSchedule) {
        val key = schedule.dedupeKey.ifBlank { schedule.id }
        runCatching {
            DataStore.remove(REOPEN_FAILURE_COUNT_PREFIX + key)
            DataStore.remove(REOPEN_FAILURE_AT_PREFIX + key)
        }
    }

    private fun isTargetProcess(context: Context): Boolean {
        return context.packageName == General.PACKAGE_NAME
    }

    private fun shouldLaunchTarget(schedule: PersistentSchedule): Boolean {
        return payloadOf(schedule).optBoolean("launch_target", false)
    }

    private fun payloadOf(schedule: PersistentSchedule): JSONObject {
        return try {
            JSONObject(schedule.payloadJson.ifBlank { "{}" })
        } catch (_: Throwable) {
            JSONObject()
        }
    }
}

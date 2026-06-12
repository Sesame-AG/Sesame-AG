package io.github.aoguai.sesameag.hook.keepalive

import android.content.Context
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object UnifiedScheduler {
    private const val TAG = "UnifiedScheduler"

    private var _scope: CoroutineScope? = null
    private val scope: CoroutineScope
        get() {
            val existing = _scope
            if (existing != null && existing.isActive) return existing
            return CoroutineScope(Dispatchers.Default + SupervisorJob()).also { _scope = it }
        }

    private data class ScheduledTask(
        val id: Int,
        val taskName: String,
        val job: Job? = null,
        val smartSchedulerId: Int = -1
    )

    private val taskIdGenerator = AtomicInteger(0)
    private val taskMap = ConcurrentHashMap<Int, ScheduledTask>()
    private val namedTasks = ConcurrentHashMap<String, Int>()

    fun initialize(context: Context) {
        SmartSchedulerManager.initialize(context)
    }

    fun scheduleLongDelay(delayMillis: Long, taskName: String = "未命名任务", block: () -> Unit): Int {
        cancelNamedTask(taskName)
        val finalDelay = delayMillis.coerceAtLeast(0L)
        return if (BaseModel.timedTaskModel.value == BaseModel.TimedTaskModel.PROGRAM) {
            scheduleProgramDelay(finalDelay, taskName, block)
        } else {
            scheduleSystemDelay(finalDelay, taskName, block)
        }
    }

    fun scheduleKeepAlive(delayMillis: Long, taskName: String = "未命名任务"): Int {
        if (BaseModel.timedTaskModel.value != BaseModel.TimedTaskModel.PROGRAM) {
            return -1
        }
        return scheduleLongDelay(delayMillis, taskName) {}
    }

    fun cancelTask(taskId: Int) {
        if (taskId == -1) return
        val task = taskMap.remove(taskId) ?: return
        if (task.smartSchedulerId != -1) {
            SmartSchedulerManager.cancelTask(task.smartSchedulerId)
        }
        task.job?.cancel()
        namedTasks.remove(task.taskName, taskId)
    }

    fun cancelNamedTask(taskName: String) {
        val taskId = namedTasks.remove(taskName) ?: return
        cancelTask(taskId)
    }

    fun cancelAll() {
        taskMap.keys.toList().forEach { cancelTask(it) }
        namedTasks.clear()
    }

    fun activeTaskCount(): Int = taskMap.size

    fun hasScheduledTasks(): Boolean = taskMap.isNotEmpty()

    fun schedulePersistentTrigger(
        context: Context,
        name: String,
        kind: String,
        triggerAtMs: Long,
        dedupeKey: String,
        payloadJson: String = "{}",
        toleranceMs: Long = PersistentScheduleDefaults.DEFAULT_TOLERANCE_MS,
        ownerUserId: String? = null,
        sessionEpoch: Long = 0L
    ): PersistentSchedule {
        val boundOwnerUserId = ownerUserId?.trim()?.takeIf { it.isNotEmpty() }
            ?: AccountSessionCoordinator.currentUserId()
            ?: UserMap.currentUid?.trim()?.takeIf { it.isNotEmpty() }
        val boundSessionEpoch = if (sessionEpoch > 0L) {
            sessionEpoch
        } else {
            AccountSessionCoordinator.currentSessionEpoch()
        }
        val schedule = PersistentSchedule(
            name = name,
            kind = kind,
            triggerAtMs = triggerAtMs,
            toleranceMs = toleranceMs,
            dedupeKey = dedupeKey,
            payloadJson = payloadJson,
            ownerUserId = boundOwnerUserId,
            sessionEpoch = boundSessionEpoch
        )
        if (boundOwnerUserId.isNullOrBlank() || boundSessionEpoch <= 0L) {
            Log.record(
                TAG,
                "拒绝注册缺少会话归属的持久调度[$name] kind=$kind owner=$boundOwnerUserId session=$boundSessionEpoch"
            )
            return schedule.withFailure("missing_session_context")
        }
        return PersistentScheduleRegistry.upsert(context, schedule)
    }

    fun cancelPersistentByDedupeKey(context: Context?, dedupeKey: String): Int {
        return PersistentScheduleRegistry.removeByDedupeKey(context, dedupeKey)
    }

    fun cancelPersistentByName(context: Context?, name: String): Int {
        return PersistentScheduleRegistry.removeByName(context, name)
    }

    fun reconcilePersistentSchedules(
        context: Context,
        mode: PersistentReconcileMode = PersistentReconcileMode.RESCHEDULE_ONLY
    ): PersistentScheduleRegistry.ReconcileResult {
        val result = PersistentScheduleRegistry.reconcile(context, mode = mode)
        result.dueSchedules.forEach { schedule ->
            ScheduledTaskRouter.fire(context, schedule, "manual_reconcile")
        }
        return result
    }

    fun cleanup() {
        cancelAll()
        _scope?.cancel()
        _scope = null
        SmartSchedulerManager.cleanup()
    }

    private fun scheduleProgramDelay(delayMillis: Long, taskName: String, block: () -> Unit): Int {
        val taskId = taskIdGenerator.incrementAndGet()
        val finalDelay = delayMillis.coerceAtLeast(0L)
        val taskFinished = AtomicBoolean(false)
        val smartSchedulerId = SmartSchedulerManager.schedule(finalDelay, taskName) {
            try {
                block()
            } finally {
                taskFinished.set(true)
                clearFinishedTask(taskId, taskName)
            }
        }
        if (smartSchedulerId == -1) {
            clearFinishedTask(taskId, taskName)
            return -1
        }

        taskMap[taskId] = ScheduledTask(taskId, taskName, smartSchedulerId = smartSchedulerId)
        namedTasks[taskName] = taskId
        if (taskFinished.get()) {
            clearFinishedTask(taskId, taskName)
        }
        return taskId
    }

    private fun scheduleSystemDelay(delayMillis: Long, taskName: String, block: () -> Unit): Int {
        val taskId = taskIdGenerator.incrementAndGet()
        val finalDelay = delayMillis.coerceAtLeast(0L)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            Log.record(TAG, "⏳ 系统计时任务: [$taskName] | ID:$taskId | 延迟: ${TimeUtil.formatDuration(finalDelay)}")
            try {
                delay(finalDelay)
                if (isActive) {
                    // 调度回调不触碰 UI，保持在后台调度执行，避免占用宿主主线程造成 ANR。
                    try {
                        block()
                    } catch (e: Exception) {
                        Log.error(TAG, "❌ 任务执行异常 [$taskName]: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                Log.record(TAG, "🚫 系统计时任务已取消: [$taskName] | ID:$taskId")
            } finally {
                clearFinishedTask(taskId, taskName)
            }
        }

        taskMap[taskId] = ScheduledTask(taskId, taskName, job = job)
        namedTasks[taskName] = taskId
        job.start()
        return taskId
    }

    private fun clearFinishedTask(taskId: Int, taskName: String) {
        taskMap.remove(taskId)
        namedTasks.remove(taskName, taskId)
    }
}

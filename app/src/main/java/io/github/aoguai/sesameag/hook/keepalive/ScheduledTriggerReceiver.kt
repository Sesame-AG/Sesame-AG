package io.github.aoguai.sesameag.hook.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.WakeLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ScheduledTriggerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScheduledTriggerReceiver"
        private const val RECEIVER_TIMEOUT_MS = 8_000L
        private const val RECEIVER_WAKELOCK_MS = 10_000L
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        val ctx = context?.applicationContext ?: context ?: return
        val scheduleId = intent?.getStringExtra(SystemWakeScheduler.EXTRA_SCHEDULE_ID).orEmpty()
        val plannedBatch = intent?.getBooleanExtra(SystemWakeScheduler.EXTRA_PLANNED_BATCH, false) == true
        if (scheduleId.isBlank()) {
            Log.record(TAG, "收到空的持久调度广播，忽略")
            return
        }

        // 广播主线程仅建立异步工作；不得等待全局入口队列、初始化或主任务执行。
        val pendingResult = goAsync()
        val wakeLock =
            WakeLockManager.acquire(
                context = ctx,
                timeoutMs = RECEIVER_WAKELOCK_MS,
                source = "persistent_alarm_receiver",
                scheduleId = scheduleId,
            )
        receiverScope.launch {
            try {
                withTimeout(RECEIVER_TIMEOUT_MS) {
                    if (plannedBatch) {
                        Log.record(TAG, "物理系统闹钟到达[id=$scheduleId] state=batch source=alarm owner=unknown session=unknown")
                        val dueCount = PersistentScheduleRegistry.fireDueSchedules(ctx, "alarm_batch")
                        Log.record(TAG, "物理系统闹钟到达，已路由到期计划数=$dueCount")
                        return@withTimeout
                    }
                    val schedule = PersistentScheduleRegistry.get(scheduleId)
                    if (schedule == null) {
                        Log.record(TAG, "找不到持久调度[$scheduleId]，忽略系统广播")
                        return@withTimeout
                    }
                    if (schedule.state != PersistentScheduleState.SCHEDULED) {
                        Log.record(
                            TAG,
                            "持久调度状态[id=${schedule.id}] state=${schedule.state} kind=${schedule.kind} source=alarm owner=${schedule.ownerUserId} session=${schedule.sessionEpoch}，忽略系统广播",
                        )
                        return@withTimeout
                    }
                    Log.record(
                        TAG,
                        "系统闹钟到达[id=${schedule.id}] state=${schedule.state} kind=${schedule.kind} source=alarm owner=${schedule.ownerUserId} session=${schedule.sessionEpoch}",
                    )
                    ScheduledTaskRouter.fire(ctx, schedule, "alarm")
                }
            } catch (_: TimeoutCancellationException) {
                Log.record(
                    TAG,
                    "持久调度广播处理超时[$scheduleId] pending=${ApplicationHookConstants.pendingTriggerCount()}",
                )
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "持久调度广播处理失败[$scheduleId]", t)
            } finally {
                wakeLock.close()
                runCatching { pendingResult.finish() }
            }
        }
    }
}

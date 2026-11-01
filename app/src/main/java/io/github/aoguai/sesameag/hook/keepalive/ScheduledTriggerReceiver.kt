package io.github.aoguai.sesameag.hook.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.WakeLockManager
import kotlinx.coroutines.CancellationException
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
                // 限制单次闹钟广播的异步处理时间，便于结束广播和释放唤醒锁；不是业务睡眠。
                withTimeout(RECEIVER_TIMEOUT_MS) {
                    val source = if (plannedBatch) "alarm_batch" else "alarm"
                    val dueCount = ScheduledTaskRouter.fireDueFromModule(ctx, source)
                    Log.record(TAG, "系统闹钟到达[id=$scheduleId] source=$source 已处理计划数=$dueCount")
                }
            } catch (_: TimeoutCancellationException) {
                android.util.Log.w(TAG, "持久调度广播处理超时[$scheduleId]")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "持久调度广播处理失败[$scheduleId]", t)
            } finally {
                runCatching { wakeLock.close() }
                runCatching { pendingResult.finish() }
            }
        }
    }
}

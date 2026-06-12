package io.github.aoguai.sesameag.hook.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.WakeLockManager

class ScheduledTriggerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScheduledTriggerReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context?.applicationContext ?: context ?: return
        val scheduleId = intent?.getStringExtra(SystemWakeScheduler.EXTRA_SCHEDULE_ID).orEmpty()
        if (scheduleId.isBlank()) {
            Log.record(TAG, "收到空的持久调度广播，忽略")
            return
        }

        // onReceive 运行在主线程，注册表读写与路由含磁盘 IO，必须转后台执行，
        // 否则在只收能量窗口高频闹钟扇出时会反复阻塞宿主主线程导致 ANR。
        val pendingResult = goAsync()
        val wakeLockToken = Any()
        WakeLockManager.acquire(ctx, PersistentScheduleDefaults.DEFAULT_EXECUTION_WAKELOCK_MS, wakeLockToken)
        ApplicationHookConstants.submitEntry("persistent_alarm_fire") {
            try {
                val schedule = PersistentScheduleRegistry.get(scheduleId)
                if (schedule == null) {
                    Log.record(TAG, "找不到持久调度[$scheduleId]，忽略系统广播")
                    return@submitEntry
                }
                if (schedule.state != PersistentScheduleState.SCHEDULED) {
                    Log.record(TAG, "持久调度[${schedule.name}]状态为${schedule.state}，忽略系统广播")
                    return@submitEntry
                }
                Log.record(TAG, "系统闹钟到达[${schedule.name}] kind=${schedule.kind}")
                ScheduledTaskRouter.fire(ctx, schedule, "alarm")
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "持久调度广播处理失败[$scheduleId]", t)
            } finally {
                WakeLockManager.release(wakeLockToken)
                runCatching { pendingResult.finish() }
            }
        }
    }
}

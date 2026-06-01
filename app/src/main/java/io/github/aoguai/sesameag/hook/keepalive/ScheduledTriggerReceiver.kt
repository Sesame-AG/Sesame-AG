package io.github.aoguai.sesameag.hook.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

        val schedule = PersistentScheduleRegistry.get(scheduleId)
        if (schedule == null) {
            Log.record(TAG, "找不到持久调度[$scheduleId]，忽略系统广播")
            return
        }
        if (schedule.state != PersistentScheduleState.SCHEDULED) {
            Log.record(TAG, "持久调度[${schedule.name}]状态为${schedule.state}，忽略系统广播")
            return
        }

        val wakeLockToken = Any()
        WakeLockManager.acquire(ctx, PersistentScheduleDefaults.DEFAULT_EXECUTION_WAKELOCK_MS, wakeLockToken)
        Log.record(TAG, "系统闹钟到达[${schedule.name}] kind=${schedule.kind}")
        try {
            ScheduledTaskRouter.fire(ctx, schedule, "alarm")
        } finally {
            WakeLockManager.release(wakeLockToken)
        }
    }
}

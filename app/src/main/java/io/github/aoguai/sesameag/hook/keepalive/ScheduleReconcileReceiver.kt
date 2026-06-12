package io.github.aoguai.sesameag.hook.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.util.Log

class ScheduleReconcileReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScheduleReconcileReceiver"
        private const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED
        )
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context?.applicationContext ?: context ?: return
        val action = intent?.action.orEmpty()
        if (action !in SUPPORTED_ACTIONS) {
            Log.record(TAG, "忽略非持久调度恢复广播: $action")
            return
        }
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.record(TAG, "收到锁屏启动广播，等待用户解锁后的 BOOT_COMPLETED 再恢复持久调度")
            return
        }
        // reconcile 含整表磁盘读写，onReceive 在主线程，必须转后台执行避免阻塞。
        val pendingResult = goAsync()
        ApplicationHookConstants.submitEntry("persistent_reconcile") {
            try {
                Log.record(TAG, "收到系统恢复广播: $action")
                val result = PersistentScheduleRegistry.reconcile(
                    ctx,
                    mode = PersistentReconcileMode.RESCHEDULE_ONLY
                )
                Log.record(
                    TAG,
                    "持久调度重排完成 due=${result.dueSchedules.size} rescheduled=${result.rescheduledCount} expired=${result.expiredCount}"
                )
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "系统恢复广播处理失败", t)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }
}

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

class ScheduleReconcileReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScheduleReconcileReceiver"
        private const val RECEIVER_TIMEOUT_MS = 8_000L
        private const val RECEIVER_WAKELOCK_MS = 20_000L
        private const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        private val SUPPORTED_ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                ACTION_EXACT_ALARM_PERMISSION_CHANGED,
            )
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
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

        val pendingResult = goAsync()
        val wakeLock =
            WakeLockManager.acquire(
                context = ctx,
                timeoutMs = RECEIVER_WAKELOCK_MS,
                source = "persistent_reconcile_receiver",
            )
        receiverScope.launch {
            try {
                // 异步广播恢复工作设超时预算，便于结束广播和释放唤醒锁；成功完成后不等满预算。
                withTimeout(RECEIVER_TIMEOUT_MS) {
                    Log.record(TAG, "收到系统恢复广播: $action")
                    val result =
                        PersistentScheduleRegistry.reconcile(
                            ctx,
                            mode = PersistentReconcileMode.RESCHEDULE_ONLY,
                        )
                    Log.record(
                        TAG,
                        "持久调度重排完成 due=${result.dueSchedules.size} rescheduled=${result.rescheduledCount} expired=${result.expiredCount}",
                    )
                }
            } catch (_: TimeoutCancellationException) {
                Log.record(
                    TAG,
                    "持久调度恢复广播处理超时[action=$action pending=${ApplicationHookConstants.pendingTriggerCount()}]",
                )
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "系统恢复广播处理失败", t)
            } finally {
                wakeLock.close()
                runCatching { pendingResult.finish() }
            }
        }
    }
}

package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.hook.keepalive.PersistentReconcileMode
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.task.ModelTask.Companion.stopAllTask
import io.github.aoguai.sesameag.util.Log.record
import io.github.aoguai.sesameag.util.Notify.updateRunningStatus

internal object ApplicationResumeCoordinator {
    private const val TAG = ApplicationHook.TAG
    private const val AUTH_LIKE_AUTO_RECOVER_GUARD_MS: Long = 1500L
    private const val MODULE_FOREGROUND_RESUME_GUARD_MS: Long = 15_000L

    @Volatile
    private var hostAppWentBackground = false

    @Volatile
    private var pendingModuleForegroundResume = false

    @Volatile
    private var lastReOpenAppLaunchAtMs: Long = 0L

    fun reset() {
        hostAppWentBackground = false
        pendingModuleForegroundResume = false
    }

    fun clearBackgroundFlag() {
        hostAppWentBackground = false
    }

    fun markHostAppBackgrounded() {
        hostAppWentBackground = true
    }

    fun consumeHostAppBackgrounded(): Boolean {
        val wasBackgrounded = hostAppWentBackground
        hostAppWentBackground = false
        return wasBackgrounded
    }

    fun recordReOpenAppLaunch(now: Long = System.currentTimeMillis()) {
        lastReOpenAppLaunchAtMs = now
        pendingModuleForegroundResume = true
    }

    fun wasRecentlyReopenedByModule(now: Long): Boolean {
        val lastLaunchAt = lastReOpenAppLaunchAtMs
        return lastLaunchAt > 0L && (now - lastLaunchAt) in 0..AUTH_LIKE_AUTO_RECOVER_GUARD_MS
    }

    fun consumeModuleForegroundResume(now: Long): Boolean {
        val pendingResume = if (!pendingModuleForegroundResume) {
            false
        } else {
            val lastLaunchAt = lastReOpenAppLaunchAtMs
            val delta = now - lastLaunchAt
            val active = lastLaunchAt > 0L && delta in 0..MODULE_FOREGROUND_RESUME_GUARD_MS
            if (!active) {
                pendingModuleForegroundResume = false
            }
            active
        }

        val moduleInitiatedResume = pendingResume || wasRecentlyReopenedByModule(now)
        if (moduleInitiatedResume) {
            pendingModuleForegroundResume = false
        }
        return moduleInitiatedResume
    }

    fun tryRecoverOffline(resumeSource: String): Boolean {
        if (!ApplicationHookConstants.isOffline()) return false

        val reason = ApplicationHookConstants.offlineReason
        val untilMs = ApplicationHookConstants.offlineUntilMs
        val now = System.currentTimeMillis()
        val cooldownExpired = untilMs > 0L && now >= untilMs

        val lastReopenAt = lastReOpenAppLaunchAtMs
        val autoResumeByReopen =
            reason == "auth_like" &&
                !cooldownExpired &&
                lastReopenAt > 0L &&
                (now - lastReopenAt) in 0..AUTH_LIKE_AUTO_RECOVER_GUARD_MS
        if (autoResumeByReopen) {
            record(TAG, "检测到 auth_like 离线，但 $resumeSource 由 reOpenApp 触发(${now - lastReopenAt}ms)，保持离线等待用户完成验证")
            return false
        }

        val shouldRecover = cooldownExpired || when (reason) {
            "auth_like",
            "system_busy",
            "login_timeout",
            "network_error_threshold",
            "rpc_error_threshold" -> true
            else -> false
        }
        if (!shouldRecover) return false

        record(TAG, "检测到 ${reason ?: "unknown"} 离线状态，尝试在 $resumeSource 退出离线并恢复任务执行")
        ApplicationHookConstants.setOffline(false)
        UnifiedScheduler.cancelNamedTask("重新登录")
        ApplicationHook.lastExecTime = 0

        val statusMsg = when (reason) {
            "auth_like" -> "✅ 验证已解除，恢复执行"
            "system_busy" -> "✅ 已解除系统繁忙/验证态，恢复执行"
            "login_timeout" -> "✅ 登录已恢复，继续执行"
            else -> "✅ 离线已解除，恢复执行"
        }
        updateRunningStatus(statusMsg)

        val triggerReason =
            if (reason == "auth_like") "auth_like_recovered"
            else "offline_recovered:${reason ?: "unknown"}"
        val dedupeKey =
            if (reason == "auth_like") "auth_like_recovered"
            else "offline_recovered"

        val runningMainTask = ApplicationHook.mainTask
        if (runningMainTask?.isRunning == true) {
            record(TAG, "🔄 离线恢复：取消当前主任务以便立即恢复执行")
            runningMainTask.stopTask()
        }
        stopAllTask()
        ApplicationHookConstants.clearPendingTriggers("offline_recover")

        ApplicationHook.appContext?.let { context ->
            UnifiedScheduler.reconcilePersistentSchedules(
                context,
                mode = PersistentReconcileMode.FIRE_ALARM_DUE
            )
        }

        ApplicationHookCore.requestExecution(
            ApplicationHookConstants.TriggerInfo(
                type = ApplicationHookConstants.TriggerType.ON_RESUME,
                priority = ApplicationHookConstants.TriggerPriority.HIGH,
                reason = triggerReason,
                dedupeKey = dedupeKey,
                ownerUserId = AccountSessionCoordinator.currentUserId(),
                sessionEpoch = AccountSessionCoordinator.currentSessionEpoch()
            )
        )
        return true
    }
}

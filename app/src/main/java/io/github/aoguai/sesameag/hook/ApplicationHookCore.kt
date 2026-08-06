package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.hook.ApplicationHookConstants.TriggerInfo
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.util.Log.record

object ApplicationHookCore {
    private const val TAG = "ApplicationHookCore"

    fun requestExecution(trigger: TriggerInfo): Boolean {
        val boundTrigger = AccountSessionCoordinator.bindTrigger(trigger)
        if (!AccountSessionCoordinator.shouldAcceptTrigger(boundTrigger)) {
            record(TAG, "ignore trigger due to stale/switching session: ${boundTrigger.summary()}")
            return false
        }
        val queueResult = ApplicationHookConstants.setPendingTrigger(boundTrigger)
        queueResult.displaced
            ?.persistentScheduleId
            ?.takeIf { it.isNotBlank() }
            ?.let { scheduleId ->
                // setPendingTrigger 已退出 triggerLock；持久化和 AlarmManager 操作不得在队列锁内执行。
                PersistentScheduleRegistry.rescheduleDeferred(
                    ApplicationHook.appContext,
                    scheduleId,
                    "trigger_queue_full",
                )
            }
        dispatchIfNeeded()
        return queueResult.accepted
    }

    fun dispatchIfNeeded() {
        ApplicationHookConstants.submitEntry("dispatch_pending_triggers") {
            dispatchPendingTriggers()
        }
    }

    private fun dispatchPendingTriggers() {
        ApplicationHook.discardCoveredAlarmPollTriggers()
        ApplicationHookConstants.removePendingTriggers("stale_session_trigger") { trigger ->
            !AccountSessionCoordinator.shouldAcceptTrigger(trigger)
        }
        if (!ApplicationHookConstants.hasPendingTriggers()) return

        if (AccountSessionCoordinator.isSwitching()) {
            record(TAG, "session switching, skip dispatch: ${ApplicationHook.readinessSummary()}")
            return
        }

        if (ApplicationHookConstants.isOffline()) {
            record(TAG, "offline active, skip dispatch: ${ApplicationHook.readinessSummary()}")
            return
        }

        val mainTask = ApplicationHook.mainTask
        if (mainTask == null) {
            record(TAG, "mainTask is null: ${ApplicationHook.readinessSummary()}")
            return
        }

        if (!ApplicationHook.isReadyForExec()) {
            record(TAG, "not ready for exec: ${ApplicationHook.readinessSummary()}")
            return
        }

        if (mainTask.isRunning) {
            record(TAG, "mainTask is running, pending=${ApplicationHookConstants.pendingTriggerCount()}")
            return
        }

        val currentOwnerUserId = AccountSessionCoordinator.currentUserId()
        val currentSessionEpoch = AccountSessionCoordinator.currentSessionEpoch()
        if (PersistentScheduleRegistry.hasActiveModuleChild(currentOwnerUserId, currentSessionEpoch)) {
            record(
                TAG,
                "module persistent child is active, defer mainTask: pending=${ApplicationHookConstants.pendingTriggerCount()} owner=$currentOwnerUserId session=$currentSessionEpoch",
            )
            return
        }

        record(TAG, "▶️ dispatch mainTask, pending=${ApplicationHookConstants.pendingTriggerCount()}")
        val job = mainTask.startTask(force = false, rounds = 1)
        job.invokeOnCompletion {
            dispatchIfNeeded()
        }
    }
}

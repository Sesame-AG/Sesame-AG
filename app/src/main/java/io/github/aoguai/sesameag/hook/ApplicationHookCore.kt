package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.hook.ApplicationHookConstants.TriggerInfo
import io.github.aoguai.sesameag.util.Log.record

object ApplicationHookCore {
    private const val TAG = "ApplicationHookCore"

    fun requestExecution(trigger: TriggerInfo) {
        val boundTrigger = AccountSessionCoordinator.bindTrigger(trigger)
        if (!AccountSessionCoordinator.shouldAcceptTrigger(boundTrigger)) {
            record(TAG, "ignore trigger due to stale/switching session: ${boundTrigger.summary()}")
            return
        }
        ApplicationHookConstants.setPendingTrigger(boundTrigger)
        dispatchIfNeeded()
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

        record(TAG, "▶️ dispatch mainTask, pending=${ApplicationHookConstants.pendingTriggerCount()}")
        val job = mainTask.startTask(force = false, rounds = 1)
        job.invokeOnCompletion {
            dispatchIfNeeded()
        }
    }
}


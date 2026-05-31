package io.github.aoguai.sesameag.hook

object ApplicationHookEntry {

    fun onInitCompleted(reason: String) {
        if (reason == "broadcast_restart" || reason == "config_reload") {
            if (!ApplicationHook.consumeReloadResumeDecision(reason)) {
                ApplicationHookCore.dispatchIfNeeded()
                return
            }
            ApplicationHookCore.requestExecution(
                ApplicationHookConstants.TriggerInfo(
                    type = ApplicationHookConstants.TriggerType.INIT,
                    priority = ApplicationHookConstants.TriggerPriority.HIGH,
                    reason = reason,
                    dedupeKey = "${reason}_resume"
                )
            )
            return
        }

        val type = when (reason) {
            "onResume", "user_switch" -> ApplicationHookConstants.TriggerType.ON_RESUME
            else -> ApplicationHookConstants.TriggerType.INIT
        }
        val dedupeKey = when (type) {
            ApplicationHookConstants.TriggerType.ON_RESUME -> "on_resume"
            else -> "init"
        }
        ApplicationHookCore.requestExecution(
            ApplicationHookConstants.TriggerInfo(
                type = type,
                priority = ApplicationHookConstants.TriggerPriority.HIGH,
                reason = reason,
                dedupeKey = dedupeKey
            )
        )
    }

    fun onPollAlarm() {
        ApplicationHookCore.requestExecution(
            ApplicationHookConstants.TriggerInfo(
                type = ApplicationHookConstants.TriggerType.ALARM_POLL,
                priority = ApplicationHookConstants.TriggerPriority.LOW,
                alarmTriggered = true,
                dedupeKey = "alarm_poll"
            )
        )
    }

    fun onIntervalRetry() {
        ApplicationHookCore.requestExecution(
            ApplicationHookConstants.TriggerInfo(
                type = ApplicationHookConstants.TriggerType.INTERVAL_RETRY,
                priority = ApplicationHookConstants.TriggerPriority.LOW,
                dedupeKey = "interval_retry"
            )
        )
    }

    fun onWakeupMidnight() {
        ApplicationHookCore.requestExecution(
            ApplicationHookConstants.TriggerInfo(
                type = ApplicationHookConstants.TriggerType.ALARM_WAKEUP,
                priority = ApplicationHookConstants.TriggerPriority.HIGH,
                alarmTriggered = true,
                wakenAtTime = true,
                wakenTime = "0000",
                dedupeKey = "wakeup_midnight"
            )
        )
    }

    fun onWakeupCustom(timeStr: String) {
        ApplicationHookCore.requestExecution(
            ApplicationHookConstants.TriggerInfo(
                type = ApplicationHookConstants.TriggerType.ALARM_WAKEUP,
                priority = ApplicationHookConstants.TriggerPriority.HIGH,
                alarmTriggered = true,
                wakenAtTime = true,
                wakenTime = timeStr,
                dedupeKey = "wakeup_$timeStr"
            )
        )
    }
}


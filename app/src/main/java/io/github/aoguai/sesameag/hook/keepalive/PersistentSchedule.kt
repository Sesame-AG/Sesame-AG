package io.github.aoguai.sesameag.hook.keepalive

import java.util.UUID

object PersistentScheduleKind {
    const val GLOBAL_POLL = "GLOBAL_POLL"
    const val GLOBAL_WAKEUP = "GLOBAL_WAKEUP"
    const val GLOBAL_PREWAKEUP = "GLOBAL_PREWAKEUP"
    const val MODULE_CHILD = "MODULE_CHILD"
}

object PersistentScheduleState {
    const val SCHEDULED = "SCHEDULED"
    const val FIRED = "FIRED"
    const val FAILED = "FAILED"
    const val EXPIRED = "EXPIRED"
}

enum class PersistentReconcileMode {
    RESCHEDULE_ONLY,
    FIRE_ALARM_DUE
}

data class PersistentSchedule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: String = "",
    val triggerAtMs: Long = 0L,
    val toleranceMs: Long = PersistentScheduleDefaults.DEFAULT_TOLERANCE_MS,
    val dedupeKey: String = "",
    val payloadJson: String = "{}",
    val ownerUserId: String? = null,
    val sessionEpoch: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val state: String = PersistentScheduleState.SCHEDULED,
    val attemptCount: Int = 0,
    val lastFireAtMs: Long = 0L,
    val lastError: String? = null
) {
    fun withScheduleState(state: String, now: Long = System.currentTimeMillis()): PersistentSchedule {
        return copy(
            state = state,
            updatedAtMs = now
        )
    }

    fun withFailure(error: String, now: Long = System.currentTimeMillis()): PersistentSchedule {
        return copy(
            state = PersistentScheduleState.FAILED,
            updatedAtMs = now,
            attemptCount = attemptCount + 1,
            lastError = error
        )
    }

    fun withFired(now: Long = System.currentTimeMillis()): PersistentSchedule {
        return copy(
            state = PersistentScheduleState.FIRED,
            updatedAtMs = now,
            attemptCount = attemptCount + 1,
            lastFireAtMs = now,
            lastError = null
        )
    }
}

object PersistentScheduleDefaults {
    const val DEFAULT_TOLERANCE_MS: Long = 10 * 60 * 1000L
    const val DEFAULT_EXECUTION_WAKELOCK_MS: Long = 2 * 60 * 1000L
    const val REOPEN_COOLDOWN_MS: Long = 5 * 60 * 1000L
    const val REOPEN_FAILURE_COOLDOWN_MS: Long = 30 * 60 * 1000L
    const val REOPEN_FAILURE_THRESHOLD: Int = 3
}

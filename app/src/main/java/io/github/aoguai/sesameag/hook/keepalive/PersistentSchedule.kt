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
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val FIRED = "FIRED"
    const val FAILED = "FAILED"
    const val EXPIRED = "EXPIRED"
}

/**
 * Alarm precision is infrastructure metadata, never business payload.  Existing records omit the
 * field, so their policy is derived from kind to preserve prior wake-up semantics on migration.
 */
object PersistentSchedulePrecisionPolicy {
    const val FLEXIBLE_POLL = "FLEXIBLE_POLL"
    const val USER_EXACT = "USER_EXACT"
    const val HARD_DEADLINE_CHILD = "HARD_DEADLINE_CHILD"

    fun defaultForKind(kind: String): String =
        when (kind) {
            PersistentScheduleKind.GLOBAL_POLL -> FLEXIBLE_POLL

            PersistentScheduleKind.MODULE_CHILD -> HARD_DEADLINE_CHILD

            PersistentScheduleKind.GLOBAL_WAKEUP,
            PersistentScheduleKind.GLOBAL_PREWAKEUP,
            -> USER_EXACT

            else -> USER_EXACT
        }

    fun normalize(
        policy: String?,
        kind: String,
    ): String =
        when (policy) {
            FLEXIBLE_POLL,
            USER_EXACT,
            HARD_DEADLINE_CHILD,
            -> policy

            else -> defaultForKind(kind)
        }

    fun isStrict(
        policy: String?,
        kind: String,
    ): Boolean = normalize(policy, kind) != FLEXIBLE_POLL
}

enum class PersistentReconcileMode {
    RESCHEDULE_ONLY,
    FIRE_ALARM_DUE,
}

data class PersistentSchedule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: String = "",
    val triggerAtMs: Long = 0L,
    val toleranceMs: Long = PersistentScheduleDefaults.DEFAULT_TOLERANCE_MS,
    val precisionPolicy: String = "",
    val dedupeKey: String = "",
    val payloadJson: String = "{}",
    val ownerUserId: String? = null,
    val sessionEpoch: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val state: String = PersistentScheduleState.SCHEDULED,
    val attemptCount: Int = 0,
    val lastFireAtMs: Long = 0L,
    val lastError: String? = null,
) {
    fun effectivePrecisionPolicy(): String = PersistentSchedulePrecisionPolicy.normalize(precisionPolicy, kind)

    fun firstDueAtMs(): Long = lastFireAtMs.takeIf { it > 0L } ?: triggerAtMs

    fun deadlineAtMs(): Long = firstDueAtMs() + toleranceMs.coerceAtLeast(0L)

    fun withScheduleState(
        state: String,
        now: Long = System.currentTimeMillis(),
    ): PersistentSchedule =
        copy(
            state = state,
            updatedAtMs = now,
        )

    fun withFailure(
        error: String,
        now: Long = System.currentTimeMillis(),
    ): PersistentSchedule =
        copy(
            state = PersistentScheduleState.FAILED,
            updatedAtMs = now,
            attemptCount = attemptCount + 1,
            lastError = error,
        )

    fun withQueued(now: Long = System.currentTimeMillis()): PersistentSchedule =
        copy(
            state = PersistentScheduleState.QUEUED,
            updatedAtMs = now,
            // 重试会改写 triggerAtMs；首次到期时间必须保留用于截止窗口判断。
            lastFireAtMs = lastFireAtMs.takeIf { it > 0L } ?: triggerAtMs,
            lastError = null,
        )

    fun withRunning(now: Long = System.currentTimeMillis()): PersistentSchedule =
        copy(
            state = PersistentScheduleState.RUNNING,
            updatedAtMs = now,
        )

    fun withDeferredRetry(
        nextTriggerAtMs: Long,
        error: String,
        now: Long = System.currentTimeMillis(),
    ): PersistentSchedule =
        copy(
            triggerAtMs = nextTriggerAtMs,
            state = PersistentScheduleState.SCHEDULED,
            updatedAtMs = now,
            attemptCount = attemptCount + 1,
            lastFireAtMs = lastFireAtMs.takeIf { it > 0L } ?: triggerAtMs,
            lastError = error,
        )

    fun withFired(now: Long = System.currentTimeMillis()): PersistentSchedule =
        copy(
            state = PersistentScheduleState.FIRED,
            updatedAtMs = now,
            attemptCount = attemptCount + 1,
            lastFireAtMs = firstDueAtMs(),
            lastError = null,
        )
}

object PersistentScheduleDefaults {
    const val DEFAULT_TOLERANCE_MS: Long = 10 * 60 * 1000L
    const val DEFAULT_EXECUTION_WAKELOCK_MS: Long = 2 * 60 * 1000L
    const val TASK_EXECUTION_WAKELOCK_MS: Long = 10 * 60 * 1000L
    const val REOPEN_COOLDOWN_MS: Long = 5 * 60 * 1000L
    const val REOPEN_FAILURE_COOLDOWN_MS: Long = 30 * 60 * 1000L
    const val REOPEN_FAILURE_THRESHOLD: Int = 3
}

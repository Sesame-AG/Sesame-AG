package io.github.aoguai.sesameag.task

import android.os.SystemClock
import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class TaskExecutionOutcome {
    SUCCESS,
    FAILED,
    SKIPPED,
    CANCELLED,
    /** 本轮存在有到期时间的业务等待。 */
    DEFERRED,
}

/** 每次模块执行独立收集结果；协程切换线程时保留归属，不传给独立调度的子任务。 */
internal class TaskExecutionReport(
    val identity: AccountSessionIdentity = current()?.identity
        ?: AccountSessionCoordinator.currentSession()?.let { AccountSessionIdentity(it.userId, it.sessionEpoch) }
        ?: AccountSessionIdentity("", 0L),
) {
    var executionJob: Job? = current()?.executionJob
    @Volatile
    var startupStage: String = "等待任务协程启动"
    private val activityUntil = AtomicLong(SystemClock.elapsedRealtime())

    val activityUntilElapsedMs: Long
        get() = activityUntil.get()

    // 显式等待期间沿用调用方的时长预算，不把正常请求间隔算作停滞。
    fun recordActivity(expectedWaitMs: Long = 0L) {
        val until = SystemClock.elapsedRealtime() + expectedWaitMs.coerceAtLeast(0L)
        activityUntil.updateAndGet { previous -> maxOf(previous, until) }
    }

    fun ensureSessionActive(userId: String? = null) {
        if (Thread.currentThread().isInterrupted || executionJob?.isActive == false) {
            throw CancellationException("任务已取消")
        }
        if (AccountSessionCoordinator.checkSessionIdentity(identity.userId, identity.sessionEpoch) !is
            io.github.aoguai.sesameag.hook.AccountSessionCheck.Current ||
            (userId != null && identity.userId != userId)
        ) throw CancellationException("任务账号会话已失效")
    }
    fun ensureCurrent(userId: String? = null) {
        ensureSessionActive(userId)
        if (!io.github.aoguai.sesameag.hook.AccountSlotRegistry.isExecutableUser(identity.userId)) {
            throw CancellationException("任务账号会话已失效")
        }
    }

    companion object {
        private val activeReport = ThreadLocal<TaskExecutionReport?>()

        fun current(): TaskExecutionReport? = activeReport.get()
    }

    val context = activeReport.asContextElement(this)
    private val flows = ConcurrentHashMap<Triple<TaskFlowExecutionState, String, String>, TaskFlowRunResult>()
    private val exception = AtomicBoolean(false)

    val hasException: Boolean
        get() = exception.get()

    fun recordException() {
        exception.set(true)
    }

    fun recordFlow(state: TaskFlowExecutionState, module: String, flow: String, result: TaskFlowRunResult) {
        flows.compute(Triple(state, module, flow)) { _, previous ->
            result.copy(
                failureCount = (previous?.failureCount ?: 0) + result.failureCount,
                progressChanged = previous?.progressChanged == true || result.progressChanged,
                actionAttempted = previous?.actionAttempted == true || result.actionAttempted,
            )
        }
        recordActivity()
    }

    fun outcome(): TaskExecutionOutcome = when {
        hasException || flows.values.any { it.failureCount > 0 } -> TaskExecutionOutcome.FAILED
        flows.values.any { it.interrupted } -> TaskExecutionOutcome.CANCELLED
        flows.values.any { it.deferredUntil != null } -> TaskExecutionOutcome.DEFERRED
        else -> TaskExecutionOutcome.SUCCESS
    }
}

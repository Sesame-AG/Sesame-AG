package io.github.aoguai.sesameag.task

import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class TaskExecutionOutcome {
    SUCCESS,
    FAILED,
    SKIPPED,
    CANCELLED,
    /** 本轮尚未完成；不表示已经注册持久续接，也不代替具体失败原因。 */
    DEFERRED,
}

/** 每次模块执行独立收集结果；协程切换线程时保留归属，不传给独立调度的子任务。 */
internal class TaskExecutionReport(
    val identity: AccountSessionIdentity = current()?.identity
        ?: AccountSessionCoordinator.currentSession()?.let { AccountSessionIdentity(it.userId, it.sessionEpoch) }
        ?: AccountSessionIdentity("", 0L),
) {
    var executionJob: Job? = current()?.executionJob

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
        flows[Triple(state, module, flow)] = result
    }

    fun outcome(): TaskExecutionOutcome = when {
        hasException || flows.values.any { it.failureCount > 0 } -> TaskExecutionOutcome.FAILED
        flows.values.any { it.interrupted } -> TaskExecutionOutcome.CANCELLED
        flows.values.any { !it.completed } -> TaskExecutionOutcome.DEFERRED
        else -> TaskExecutionOutcome.SUCCESS
    }
}

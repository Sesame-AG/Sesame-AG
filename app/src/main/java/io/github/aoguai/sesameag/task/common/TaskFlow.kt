package io.github.aoguai.sesameag.task.common

import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
import io.github.aoguai.sesameag.hook.AccountSessionCheck
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import io.github.aoguai.sesameag.task.TaskExecutionReport
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.TimeUtil
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlin.random.Random

enum class TaskRpcFailureType {
    TERMINAL_DONE,
    BUSINESS_LIMIT,
    UNSUPPORTED_NO_CLOSURE,
    NON_RETRYABLE_INVALID,
    RETRYABLE_RPC,
    UNKNOWN_NEEDS_REVIEW,
}

enum class TaskFlowPhase {
    REWARD_READY,
    READY_TO_COMPLETE,
    SIGNUP_REQUIRED,
    SIGNUP_COMPLETE,
    TERMINAL,
    BUSINESS_ACTION,
    UNSUPPORTED,
    UNKNOWN,
}

enum class TaskFlowAction(
    val logName: String,
) {
    RECEIVE("receive"),
    COMPLETE("complete"),
    SIGNUP("signup"),
    SEND("send"),
}

enum class DeferredReason {
    TIME_WINDOW,
    CAPACITY_LIMIT,
    /** 仅用于已受理动作的本轮回查；未达进度按失败收尾，不跨轮保存。 */
    STATE_CONFIRMATION,
    PREREQUISITE_PENDING,
    CHILD_TASK_PENDING,
    NO_PROGRESS_COOLDOWN,
}

enum class TaskFlowDecision {
    BLACKLIST,
    STOP_TODAY_OR_CURRENT_CHAIN,
    MARK_HANDLED,
}

data class TaskFlowItem(
    val id: String,
    val title: String,
    val status: String,
    val type: String = "",
    val sceneCode: String = "",
    val actionType: String = "",
    val blacklistKeys: List<String> = listOf(id, title).filter { it.isNotBlank() },
    val raw: JSONObject? = null,
    val progress: String = "",
    val current: Int? = null,
    val limit: Int? = null,
)

data class TaskFlowActionResult(
    val success: Boolean,
    val failureType: TaskRpcFailureType? = null,
    val code: String = "",
    val message: String = "",
    val rpc: String = "",
    val raw: String = "",
    val detail: String = "",
    val stopCurrentRound: Boolean = false,
    // 单个任务的可重试失败不一定要停止同一快照里的其他独立任务。
    val continueCurrentRoundOnFailure: Boolean = false,
    // 警告：只有成功提交才能刷新后续状态。失败禁止回查、补提或转成下轮恢复。
    val refreshAfterAction: Boolean = false,
    // RPC 成功不一定代表服务端任务状态已经推进；用于控制进展统计和重复动作保护。
    val progressChanged: Boolean = true,
    val deferredReason: DeferredReason? = null,
    val deferredUntil: Long? = null,
    /** null 表示该调用方未提供提交证据，不能据此重放。 */
    val submissionStarted: Boolean? = null,
) {
    companion object {
        fun success(
            refreshAfterAction: Boolean = false,
            progressChanged: Boolean = true,
        ): TaskFlowActionResult =
            TaskFlowActionResult(
                success = true,
                refreshAfterAction = refreshAfterAction,
                progressChanged = progressChanged,
            )

        fun failure(
            failureType: TaskRpcFailureType,
            code: String = "",
            message: String = "",
            rpc: String = "",
            raw: String = "",
            detail: String = "",
            stopCurrentRound: Boolean = false,
            continueCurrentRoundOnFailure: Boolean = false,
        ): TaskFlowActionResult =
            TaskFlowActionResult(
                success = false,
                failureType = failureType,
                code = code,
                message = message,
                rpc = rpc,
                raw = raw,
                detail = detail,
                stopCurrentRound = stopCurrentRound,
                continueCurrentRoundOnFailure = continueCurrentRoundOnFailure,
                progressChanged = false,
            )

        fun defer(
            deferredReason: DeferredReason,
            message: String = "",
            rpc: String = "",
            raw: String = "",
            detail: String = "",
            deferredUntil: Long? = null,
            stopCurrentRound: Boolean = false,
            refreshAfterAction: Boolean = false,
            progressChanged: Boolean = false,
        ): TaskFlowActionResult =
            TaskFlowActionResult(
                success = false,
                message = message,
                rpc = rpc,
                raw = raw,
                detail = detail,
                stopCurrentRound = stopCurrentRound,
                refreshAfterAction = refreshAfterAction,
                progressChanged = progressChanged,
                deferredReason = deferredReason,
                deferredUntil = deferredUntil,
            )
    }
}

data class TaskFlowSnapshot(
    val totalTasks: Int,
    val completedTasks: Int,
    val availableTasks: Int,
    val unresolvedTasks: Int = 0,
) {
    val isComplete: Boolean
        get() = completedTasks >= totalTasks && availableTasks == 0 && unresolvedTasks == 0
}

data class TaskFlowRoundAction(
    val action: String,
    val taskName: String = "",
) {
    fun describe(): String = if (taskName.isBlank()) action else "$action：$taskName"
}

data class TaskFlowRunResult(
    val completed: Boolean,
    val progressed: Boolean,
    val stopped: Boolean,
    val rounds: Int,
    val actionAttempted: Boolean = false,
    val progressChanged: Boolean = progressed,
    val interrupted: Boolean = false,
    val deferredCount: Int = 0,
    val deferredReasonCounts: Map<DeferredReason, Int> = emptyMap(),
    val failureCount: Int = 0,
    val deferredUntil: Long? = null,
)

private data class TaskFlowActionCandidate(
    val index: Int,
    val item: TaskFlowItem,
    val initialAction: TaskFlowAction,
)

/**
 * 动作去重状态的生命周期由调用方决定；默认每次任务流运行独立创建。
 */
class TaskFlowExecutionState {
    internal val deferredActionKeys = mutableMapOf<String, Long>()
    internal val deferredActionReasons = mutableMapOf<String, DeferredReason>()
    internal val executedActionKeys = mutableSetOf<String>()
}

interface TaskFlowAdapter {
    val moduleName: String
    val flowName: String

    /** 区分同一模块的不同业务入口。 */
    val stateScope: String
        get() = flowName

    /** 单项请求失败后能否继续处理其他独立任务。 */
    val continueCurrentRoundOnRetryableFailure: Boolean
        get() = false

    fun query(): JSONObject

    fun isQuerySuccess(response: JSONObject): Boolean = true

    fun isQueryComplete(response: JSONObject): Boolean = isQuerySuccess(response)

    fun extractItems(response: JSONObject): List<TaskFlowItem>

    fun mapPhase(item: TaskFlowItem): TaskFlowPhase

    fun isFlowHandledToday(): Boolean = false

    fun onFlowHandledToday() {
        logInfo("$flowName[今日完成标记已存在，跳过任务流]")
    }

    fun shouldSkipByTodayState(item: TaskFlowItem): Boolean = false

    /** 身份比较不包含阶段和进度；有多条业务实例的适配器补充服务端实例标识。 */
    fun isSameTask(previous: TaskFlowItem, current: TaskFlowItem): Boolean =
        previous.id.isNotBlank() && blacklistKey(previous, TaskFlowAction.COMPLETE) ==
            blacklistKey(current, TaskFlowAction.COMPLETE)

    fun hasProgress(previous: TaskFlowItem, current: TaskFlowItem): Boolean =
        (previous.current != null && current.current != null && current.current > previous.current) ||
            (previous.progress.isNotBlank() && current.progress.isNotBlank() && current.progress != previous.progress)

    /** 由所属业务确认本轮提交的结果；列表缺项本身不是完成依据。 */
    fun isPendingActionResolved(
        item: TaskFlowItem,
        action: TaskFlowAction,
        response: JSONObject,
        items: List<TaskFlowItem>,
    ): Boolean = false

    /** false 表示本轮未确认进展，null 则由任务流按阶段和进度确认。 */
    fun isPendingActionResolved(
        item: TaskFlowItem,
        action: TaskFlowAction,
        response: JSONObject,
        items: List<TaskFlowItem>,
        submitted: JSONObject?,
    ): Boolean? = isPendingActionResolved(item, action, response, items).takeIf { it }

    fun shouldSkip(item: TaskFlowItem): Boolean = false

    /** A conservatively skipped item that still needs service-side or manual progress blocks completion. */
    fun isUnresolvedWhenSkipped(item: TaskFlowItem): Boolean = false

    fun receive(item: TaskFlowItem): TaskFlowActionResult = unsupportedAction(item, TaskFlowAction.RECEIVE)

    fun complete(item: TaskFlowItem): TaskFlowActionResult = unsupportedAction(item, TaskFlowAction.COMPLETE)

    fun signup(item: TaskFlowItem): TaskFlowActionResult = unsupportedAction(item, TaskFlowAction.SIGNUP)

    fun send(item: TaskFlowItem): TaskFlowActionResult = unsupportedAction(item, TaskFlowAction.SEND)

    fun actionKey(
        item: TaskFlowItem,
        action: TaskFlowAction,
    ): String {
        val progressKey = item.current?.toString() ?: item.progress.ifBlank { "NO_PROGRESS" }
        val typeKey = item.actionType.ifBlank { item.type.ifBlank { "NO_TYPE" } }
        return "${action.logName}:${item.id}:$progressKey:$typeKey"
    }

    fun isBlacklisted(item: TaskFlowItem): Boolean = item.blacklistKeys.any { TaskBlacklist.isTaskInBlacklist(moduleName, it) }

    fun blacklistKey(item: TaskFlowItem, action: TaskFlowAction): String {
        if (item.id.isBlank()) return ""
        val instance = item.raw?.optString("recordId").orEmpty()
        val reward = if (action == TaskFlowAction.RECEIVE) item.raw?.optString("assetId").orEmpty() else ""
        return org.json.JSONArray(listOf(stateScope, item.sceneCode, item.type, item.id, instance, action == TaskFlowAction.RECEIVE, reward)).toString()
    }

    fun afterSuccess(
        item: TaskFlowItem,
        action: TaskFlowAction,
        result: TaskFlowActionResult,
    ) = Unit

    fun afterFailure(
        item: TaskFlowItem,
        action: TaskFlowAction,
        result: TaskFlowActionResult,
        decision: TaskFlowDecision,
    ) = Unit

    fun afterDeferred(
        item: TaskFlowItem,
        action: TaskFlowAction,
        result: TaskFlowActionResult,
    ) = Unit

    fun onAllTasksDone(snapshot: TaskFlowSnapshot) = Unit

    fun onQueryFailed(response: JSONObject) {
        logError("$flowName[任务查询失败] raw=$response")
    }

    fun onUnknownPhase(
        item: TaskFlowItem,
        phase: TaskFlowPhase,
    ) {
        logError("$flowName[未知状态：${item.title}，状态：${item.status}，phase=$phase]")
    }

    fun logInfo(message: String)

    fun logError(message: String)

    private fun unsupportedAction(
        item: TaskFlowItem,
        action: TaskFlowAction,
    ): TaskFlowActionResult =
        TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            message = "adapter未实现${action.logName}",
            rpc = "TaskFlowAdapter.${action.logName}",
            detail = "taskId=${item.id} taskName=${item.title} status=${item.status}",
        )
}

/** 统一任务执行流程：执行、领奖、刷新和确认进展；不负责注册持久任务调度。 */
class TaskFlowEngine(
    private val adapter: TaskFlowAdapter,
    private val roundSleepMs: Long = 850L,
    private val executionState: TaskFlowExecutionState = TaskFlowExecutionState(),
) {
    private companion object {
        const val ROUND_SLEEP_JITTER_MS = 1275L
    }

    fun run(): TaskFlowRunResult {
        val session = AccountSessionCoordinator.currentSession()
        val identity = session?.let { AccountSessionIdentity(it.userId, it.sessionEpoch) }
        try {
            return runFlow(identity, TimeUtil.getDateStr())
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (t: Throwable) {
            TaskExecutionReport.current()?.recordException()
            throw t
        }
    }

    private fun runFlow(identity: AccountSessionIdentity?, day: String): TaskFlowRunResult {
        val executed = executionState.executedActionKeys
        val pending = mutableMapOf<String, Triple<TaskFlowItem, TaskFlowAction, TaskFlowActionResult>>()
        val failures = mutableSetOf<String>()
        val loggedSkips = mutableSetOf<String>()
        var rounds = 0
        var progressed = false
        var actionAttempted = false
        var stopActions = false

        fun fail(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
            TaskExecutionReport.current()?.ensureCurrent()
            val key = adapter.blacklistKey(item, action)
            if (!failures.add(key.ifBlank { actionSnapshotKey(item, action) })) return
            val type = result.failureType ?: TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            val decision = decideFailure(type)
            if (decision != TaskFlowDecision.MARK_HANDLED && identity != null && key.isNotBlank()) {
                AccountSessionCoordinator.withCurrentSession(identity) {
                    TaskBlacklist.addToBlacklist(adapter.moduleName, key, item.title)
                }
            }
            logFailure(item, action, result, type, decision)
            adapter.afterFailure(item, action, result.copy(failureType = type), decision)
        }

        fun finish(
            interrupted: Boolean = false,
            queryFailed: Boolean = false,
            waiting: Map<String, Long> = emptyMap(),
        ): TaskFlowRunResult {
            if (queryFailed && !interrupted) {
                for ((item, action, submitted) in pending.values) {
                    fail(item, action, submitted.copy(
                        success = false, failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        message = "提交后业务回查失败；${submitted.message}",
                        progressChanged = false, deferredReason = null, deferredUntil = null,
                        refreshAfterAction = false,
                    ))
                }
                pending.clear()
            }
            val counts = waiting.keys.mapNotNull { executionState.deferredActionReasons[it] }.groupingBy { it }.eachCount()
            val result = TaskFlowRunResult(
                completed = failures.isEmpty() && !stopActions && !interrupted && !queryFailed && waiting.isEmpty(),
                progressed = progressed,
                stopped = stopActions || interrupted || queryFailed,
                rounds = rounds,
                actionAttempted = actionAttempted,
                interrupted = interrupted,
                deferredCount = waiting.size,
                deferredReasonCounts = counts,
                failureCount = if (queryFailed) maxOf(1, failures.size) else failures.size,
                deferredUntil = waiting.values.minOrNull(),
            )
            logFinalSummary(result)
            TaskExecutionReport.current()?.recordFlow(executionState, adapter.moduleName, adapter.flowName, result)
            return result
        }

        while (true) {
            TaskExecutionReport.current()?.ensureCurrent()
            if (Thread.currentThread().isInterrupted) throw InterruptedException("任务流已中断")
            if (rounds > 0 && roundSleepMs > 0L) {
                CoroutineUtils.sleepCompat(roundSleepMs + Random.nextLong(0, ROUND_SLEEP_JITTER_MS))
            }
            if (TimeUtil.getDateStr() != day || ApplicationHookConstants.isOffline() || identity != null &&
                AccountSessionCoordinator.checkCurrentSession(identity.userId, identity.sessionEpoch) !is AccountSessionCheck.Current
            ) return finish(interrupted = true)
            if (pending.isEmpty() && rounds == 0 && adapter.isFlowHandledToday()) {
                adapter.onFlowHandledToday()
                return finish()
            }

            rounds++
            val response = try {
                adapter.query()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (t: Throwable) {
                if (ApplicationHookConstants.isOffline()) return finish(interrupted = true)
                adapter.logError("${adapter.flowName}[任务查询异常] ${t.message}")
                return finish(queryFailed = true)
            }
            TaskExecutionReport.current()?.ensureCurrent()
            if (identity != null &&
                AccountSessionCoordinator.checkCurrentSession(identity.userId, identity.sessionEpoch) !is AccountSessionCheck.Current
            ) return finish(interrupted = true)
            RpcOfflineRisk.enterOfflineIfNeeded(adapter.flowName, response)
            if (RpcDailyCircuit.isStopResponse(response) || ApplicationHookConstants.isOffline()) {
                return finish(interrupted = true)
            }
            if (!adapter.isQuerySuccess(response)) {
                adapter.onQueryFailed(response)
                return finish(queryFailed = true)
            }

            val items = adapter.extractItems(response)
            for ((previous, action, result) in pending.values) {
                val refreshed = items.firstOrNull { adapter.isSameTask(previous, it) }
                val details = JSONObject().put("submissionStarted", result.submissionStarted ?: JSONObject.NULL)
                    .put("rpc", result.rpc).put("code", result.code).put("raw", result.raw)
                    .put("detail", result.detail).put("message", result.message)
                val resolved = try {
                    adapter.isPendingActionResolved(previous, action, response, items, details)
                } catch (t: Throwable) {
                    if (t is CancellationException || t is InterruptedException) throw t
                    if (ApplicationHookConstants.isOffline()) return finish(interrupted = true)
                    fail(previous, action, result.copy(
                        success = false, failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = "提交后的业务查询异常：${t.message}", progressChanged = false,
                        deferredReason = null, deferredUntil = null, refreshAfterAction = false,
                    ))
                    continue
                }
                val phase = refreshed?.let(adapter::mapPhase)
                val phaseConfirmed = when (action) {
                    TaskFlowAction.RECEIVE -> phase == TaskFlowPhase.TERMINAL
                    TaskFlowAction.COMPLETE, TaskFlowAction.SEND -> phase == TaskFlowPhase.REWARD_READY ||
                        phase == TaskFlowPhase.TERMINAL ||
                        phase == TaskFlowPhase.BUSINESS_ACTION && adapter.mapPhase(previous) != TaskFlowPhase.BUSINESS_ACTION
                    TaskFlowAction.SIGNUP -> phase == TaskFlowPhase.SIGNUP_COMPLETE || phase == TaskFlowPhase.READY_TO_COMPLETE ||
                        phase == TaskFlowPhase.REWARD_READY || phase == TaskFlowPhase.TERMINAL ||
                        phase == TaskFlowPhase.BUSINESS_ACTION && adapter.mapPhase(previous) != TaskFlowPhase.BUSINESS_ACTION
                }
                val progressConfirmed = refreshed != null && adapter.hasProgress(previous, refreshed)
                // 必须以本次查询的业务进度为准，不能用提交时的标记撤销去重后反复提交。
                if (resolved ?: (phaseConfirmed || progressConfirmed)) {
                    progressed = true
                    adapter.logInfo("${adapter.flowName}[回查确认${action.logName}进展：${previous.title}]")
                } else {
                    fail(previous, action, result.copy(
                        success = false, failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = "本轮回查未达到业务进度；${result.message}",
                        progressChanged = false, deferredReason = null, deferredUntil = null,
                        refreshAfterAction = false,
                    ))
                }
            }
            pending.clear()
            val autoBlacklist = TaskBlacklist.getBlacklist(adapter.moduleName)

            var refresh = false
            val waiting = linkedMapOf<String, Long>()
            val roundActions = mutableListOf<TaskFlowRoundAction>()
            for ((_, item, action) in buildActionCandidates(items)) {
                if (ApplicationHookConstants.isOffline()) return finish(interrupted = true)
                val key = actionSnapshotKey(item, action)
                val blacklistKey = adapter.blacklistKey(item, action)
                val waitKey = "${action.name}:${blacklistKey.ifBlank { item.title }}"
                if (blacklistKey.ifBlank { key } in failures || blacklistKey in autoBlacklist) {
                    executionState.deferredActionKeys.remove(waitKey)
                    executionState.deferredActionReasons.remove(waitKey)
                    if (loggedSkips.add(blacklistKey.ifBlank { key })) {
                        adapter.logInfo("${adapter.flowName}[当日黑名单跳过${action.logName}：${item.title}] taskId=${item.id}")
                    }
                    continue
                }
                val until = executionState.deferredActionKeys[waitKey]
                if (until != null && until > System.currentTimeMillis()) {
                    waiting[waitKey] = until
                    continue
                }
                if (key in executed || stopActions && action != TaskFlowAction.RECEIVE) continue
                executionState.deferredActionKeys.remove(waitKey)
                executionState.deferredActionReasons.remove(waitKey)

                val result = executeAction(item, action)
                TaskExecutionReport.current()?.ensureCurrent()
                if (Thread.currentThread().isInterrupted) throw InterruptedException("任务流已中断")
                if (identity != null &&
                    AccountSessionCoordinator.checkCurrentSession(identity.userId, identity.sessionEpoch) !is AccountSessionCheck.Current
                ) return finish(interrupted = true)
                if (ApplicationHookConstants.isOffline() ||
                    runCatching { RpcDailyCircuit.isStopResponse(JSONObject(result.raw)) }.getOrDefault(false)
                ) return finish(interrupted = true)
                val reason = result.deferredReason
                val resumeAt = result.deferredUntil
                // 只有尚未到期的真实计时业务可以等待；失败和过期时间不得重新登记子任务。
                if (result.failureType == null && resumeAt != null && reason != null && resumeAt > System.currentTimeMillis() &&
                    reason in setOf(DeferredReason.TIME_WINDOW, DeferredReason.CHILD_TASK_PENDING)
                ) {
                    executionState.deferredActionKeys[waitKey] = resumeAt
                    executionState.deferredActionReasons[waitKey] = reason
                    waiting[waitKey] = resumeAt
                    adapter.afterDeferred(item, action, result)
                    logDeferred(item, action, result, reason)
                    roundActions += TaskFlowRoundAction(deferredActionText(action, reason, result.refreshAfterAction), item.title)
                    if (result.stopCurrentRound) break
                    continue
                }

                executed += key
                val terminal = result.failureType == TaskRpcFailureType.TERMINAL_DONE
                val confirmation = result.failureType == null &&
                    (reason == DeferredReason.STATE_CONFIRMATION ||
                        result.success && !result.progressChanged && result.submissionStarted != false)
                val skipped = result.failureType == null && !result.success && !terminal && !confirmation &&
                    reason != null && reason !in setOf(DeferredReason.TIME_WINDOW, DeferredReason.CHILD_TASK_PENDING) &&
                    result.submissionStarted != true
                if (!skipped && result.submissionStarted != false) actionAttempted = true
                when {
                    terminal -> {
                        logFailure(item, action, result, TaskRpcFailureType.TERMINAL_DONE, TaskFlowDecision.MARK_HANDLED)
                        adapter.afterFailure(item, action, result, TaskFlowDecision.MARK_HANDLED)
                        progressed = true
                        refresh = true
                        roundActions += TaskFlowRoundAction("终态成功", item.title)
                    }
                    confirmation -> {
                        if (result.success) adapter.afterSuccess(item, action, result)
                        // 成功提交后的确认仅限本轮，不调用延后钩子登记恢复任务。
                        pending[key] = Triple(item, action, result)
                        refresh = true
                        roundActions += TaskFlowRoundAction("${action.logName}本轮回查", item.title)
                        if (result.stopCurrentRound) stopActions = true
                    }
                    skipped || result.success && !result.progressChanged && result.submissionStarted == false -> {
                        roundActions += TaskFlowRoundAction("跳过${action.logName}", item.title)
                    }
                    result.success -> {
                        adapter.afterSuccess(item, action, result)
                        progressed = progressed || result.progressChanged
                        refresh = true
                        roundActions += TaskFlowRoundAction(successActionText(action), item.title)
                        if (result.stopCurrentRound) stopActions = true
                    }
                    else -> {
                        fail(item, action, result)
                        roundActions += TaskFlowRoundAction("${action.logName}失败", item.title)
                        if (result.stopCurrentRound || !result.continueCurrentRoundOnFailure &&
                            result.failureType in setOf(TaskRpcFailureType.BUSINESS_LIMIT, TaskRpcFailureType.RETRYABLE_RPC)
                        ) {
                            // 警告：失败当场结束该动作，不为它发起列表回查。
                            stopActions = true
                            break
                        }
                    }
                }
                if (result.stopCurrentRound && stopActions) break
            }
            adapter.logInfo("${adapter.flowName}[轮次:$rounds][本轮动作:${describeRoundActions(roundActions)}]")
            if (refresh) continue

            val queryComplete = adapter.isQueryComplete(response)
            val snapshot = buildSnapshot(items)
            if (queryComplete && snapshot.isComplete && failures.isEmpty() && waiting.isEmpty() && !stopActions) {
                adapter.onAllTasksDone(snapshot)
            }
            return finish(queryFailed = !queryComplete, waiting = waiting)
        }
    }

    private fun actionSnapshotKey(
        item: TaskFlowItem,
        action: TaskFlowAction,
    ): String =
        listOf(
            adapter.moduleName,
            adapter.flowName,
            action.logName,
            adapter.actionKey(item, action),
            adapter.blacklistKey(item, action).ifBlank { item.title },
            item.status,
            item.type,
            item.sceneCode,
            item.actionType,
            item.current?.toString().orEmpty(),
            item.limit?.toString().orEmpty(),
            item.progress,
        ).joinToString("|")

    private fun buildSnapshot(items: List<TaskFlowItem>): TaskFlowSnapshot {
        var totalTasks = 0
        var completedTasks = 0
        var availableTasks = 0
        var unresolvedTasks = 0
        for (item in items) {
            if (shouldSkipItem(item)) {
                if (adapter.isUnresolvedWhenSkipped(item)) {
                    totalTasks++
                    unresolvedTasks++
                }
                continue
            }

            val phase = adapter.mapPhase(item)
            totalTasks++
            when (phase) {
                TaskFlowPhase.TERMINAL -> completedTasks++

                TaskFlowPhase.REWARD_READY,
                TaskFlowPhase.READY_TO_COMPLETE,
                TaskFlowPhase.SIGNUP_REQUIRED,
                TaskFlowPhase.SIGNUP_COMPLETE,
                -> availableTasks++

                TaskFlowPhase.BUSINESS_ACTION,
                TaskFlowPhase.UNSUPPORTED,
                TaskFlowPhase.UNKNOWN,
                -> unresolvedTasks++
            }
        }
        return TaskFlowSnapshot(totalTasks, completedTasks, availableTasks, unresolvedTasks)
    }

    private fun buildActionCandidates(items: List<TaskFlowItem>): List<TaskFlowActionCandidate> {
        val candidates = mutableListOf<TaskFlowActionCandidate>()
        for ((index, item) in items.withIndex()) {
            if (shouldSkipItem(item)) continue

            val phase = adapter.mapPhase(item)
            val action = phase.toAction() ?: if (
                (phase == TaskFlowPhase.BUSINESS_ACTION ||
                    (phase == TaskFlowPhase.UNKNOWN && item.status in setOf("TODO", "WAIT_COMPLETE"))) &&
                ThirdPartyTask.resolve(item) != null
            ) {
                TaskFlowAction.COMPLETE
            } else null
            if (action == null) {
                if (phase == TaskFlowPhase.UNKNOWN) {
                    adapter.onUnknownPhase(item, phase)
                } else if (phase == TaskFlowPhase.BUSINESS_ACTION) {
                    adapter.logInfo("${adapter.flowName}[无自动动作，本轮跳过：${item.title}]")
                }
                continue
            }

            candidates.add(TaskFlowActionCandidate(index, item, action))
        }
        // 优先领奖，同一状态和进度下的动作只执行一次。
        return candidates
            .sortedWith(
                compareBy<TaskFlowActionCandidate> { actionPriority(it.initialAction) }
                    .thenBy { it.index },
            ).distinctBy { candidate ->
                actionSnapshotKey(candidate.item, candidate.initialAction)
            }
    }

    private fun shouldSkipItem(item: TaskFlowItem): Boolean {
        if (adapter.shouldSkipByTodayState(item)) return true
        if (shouldSkipBlacklisted(item)) return true
        return adapter.shouldSkip(item)
    }

    private fun shouldSkipBlacklisted(item: TaskFlowItem): Boolean {
        if (adapter.mapPhase(item) == TaskFlowPhase.REWARD_READY) return false
        return adapter.isBlacklisted(item)
    }

    private fun executeAction(
        item: TaskFlowItem,
        action: TaskFlowAction,
    ): TaskFlowActionResult {
        TaskExecutionReport.current()?.ensureCurrent()
        val result =
            try {
                when (action) {
                    TaskFlowAction.RECEIVE -> adapter.receive(item)
                    TaskFlowAction.COMPLETE -> ThirdPartyTask.resolve(item)?.let(ThirdPartyTask::complete) ?: adapter.complete(item)
                    TaskFlowAction.SIGNUP -> adapter.signup(item)
                    TaskFlowAction.SEND -> adapter.send(item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (t: Throwable) {
                TaskExecutionReport.current()?.recordException()
                TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    message = t.message.orEmpty(),
                    rpc = "TaskFlowEngine.${action.logName}",
                    raw = t.toString(),
                )
            }
        return if (adapter.continueCurrentRoundOnRetryableFailure &&
            !result.stopCurrentRound &&
            result.failureType == TaskRpcFailureType.RETRYABLE_RPC &&
            !result.continueCurrentRoundOnFailure
        ) {
            result.copy(continueCurrentRoundOnFailure = true)
        } else {
            result
        }
    }

    private fun actionPriority(action: TaskFlowAction): Int =
        when (action) {
            TaskFlowAction.RECEIVE -> 0
            TaskFlowAction.SEND -> 1
            TaskFlowAction.SIGNUP -> 2
            TaskFlowAction.COMPLETE -> 3
        }

    private fun describeRoundActions(actions: List<TaskFlowRoundAction>): String {
        if (actions.isEmpty()) {
            return "无可执行动作"
        }
        val visibleLimit = 8
        val visibleActions =
            actions
                .take(visibleLimit)
                .joinToString("；") { it.describe() }
        return if (actions.size > visibleLimit) {
            "$visibleActions；... 共${actions.size}个动作"
        } else {
            visibleActions
        }
    }

    private fun decideFailure(failureType: TaskRpcFailureType): TaskFlowDecision =
        when (failureType) {
            TaskRpcFailureType.TERMINAL_DONE -> TaskFlowDecision.MARK_HANDLED

            TaskRpcFailureType.BUSINESS_LIMIT -> TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN

            TaskRpcFailureType.RETRYABLE_RPC,
            TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
            TaskRpcFailureType.NON_RETRYABLE_INVALID,
            -> TaskFlowDecision.BLACKLIST
        }

    private fun logDeferred(
        item: TaskFlowItem,
        action: TaskFlowAction,
        result: TaskFlowActionResult,
        deferredReason: DeferredReason,
    ) {
        val message =
            buildString {
                append(adapter.flowName)
                append("[")
                append(item.title)
                append("] defer=")
                append(deferredReason)
                append(" module=")
                append(adapter.moduleName)
                append(" taskId=")
                append(item.id.ifBlank { "UNKNOWN" })
                append(" taskName=")
                append(item.title.ifBlank { "UNKNOWN" })
                append(" status=")
                append(item.status.ifBlank { "UNKNOWN" })
                append(" action=")
                append(action.logName)
                append(" rpc=")
                append(result.rpc.ifBlank { action.logName })
                append(" msg=")
                append(result.message.ifBlank { deferredReasonLabel(deferredReason) })
                result.deferredUntil?.let {
                    append(" deferredUntil=")
                    append(it)
                }
                if (result.detail.isNotBlank()) {
                    append(" ")
                    append(result.detail)
                }
                if (result.raw.isNotBlank()) {
                    append(" raw=")
                    append(result.raw)
                }
            }
        adapter.logInfo(message)
    }

    private fun logFailure(
        item: TaskFlowItem,
        action: TaskFlowAction,
        result: TaskFlowActionResult,
        failureType: TaskRpcFailureType,
        decision: TaskFlowDecision,
    ) {
        val message =
            buildString {
                append(adapter.flowName)
                append("[")
                append(item.title)
                append("] classification=")
                append(failureType)
                append(" decision=")
                append(decision)
                append(" module=")
                append(adapter.moduleName)
                append(" taskId=")
                append(item.id.ifBlank { "UNKNOWN" })
                append(" taskName=")
                append(item.title.ifBlank { "UNKNOWN" })
                append(" status=")
                append(item.status.ifBlank { "UNKNOWN" })
                append(" action=")
                append(action.logName)
                append(" rpc=")
                append(result.rpc.ifBlank { action.logName })
                append(" code=")
                append(result.code.ifBlank { "UNKNOWN" })
                append(" msg=")
                append(result.message.ifBlank { "UNKNOWN" })
                result.submissionStarted?.let { append(" submitted=").append(it) }
                if (result.detail.isNotBlank()) append(" ").append(result.detail)
                if (result.raw.isNotBlank()) append(" raw=").append(result.raw)
            }

        if (failureType == TaskRpcFailureType.TERMINAL_DONE) {
            adapter.logInfo(message)
        } else {
            adapter.logError(message)
        }
    }

    private fun logFinalSummary(result: TaskFlowRunResult) {
        val status = when {
            result.failureCount > 0 -> "本轮失败"
            result.interrupted -> "本轮中断"
            result.deferredUntil != null -> "等待到期"
            !result.actionAttempted && !result.progressed -> "无可执行动作"
            else -> "本轮完成"
        }
        val summary = "${adapter.flowName}[$status][轮次:${result.rounds}][动作:${result.actionAttempted}]" +
            "[有进展:${result.progressed}][明确延后:${describeDeferredReasonCounts(result.deferredReasonCounts)}]" +
            "[当前任务失败:${result.failureCount}]"
        if (result.failureCount == 0) adapter.logInfo(summary) else adapter.logError(summary)
    }

    private fun TaskFlowPhase.toAction(): TaskFlowAction? =
        when (this) {
            TaskFlowPhase.REWARD_READY -> TaskFlowAction.RECEIVE

            TaskFlowPhase.READY_TO_COMPLETE -> TaskFlowAction.COMPLETE

            TaskFlowPhase.SIGNUP_REQUIRED -> TaskFlowAction.SIGNUP

            TaskFlowPhase.SIGNUP_COMPLETE -> TaskFlowAction.SEND

            TaskFlowPhase.TERMINAL,
            TaskFlowPhase.BUSINESS_ACTION,
            TaskFlowPhase.UNSUPPORTED,
            TaskFlowPhase.UNKNOWN,
            -> null
        }

    private fun successActionText(action: TaskFlowAction): String =
        when (action) {
            TaskFlowAction.RECEIVE -> "领取奖励"
            TaskFlowAction.COMPLETE -> "完成任务"
            TaskFlowAction.SIGNUP -> "报名"
            TaskFlowAction.SEND -> "发送任务"
        }

    private fun describeDeferredReasonCounts(reasonCounts: Map<DeferredReason, Int>): String {
        if (reasonCounts.isEmpty()) {
            return "0"
        }
        return reasonCounts.entries.joinToString("，") { (reason, count) ->
            "${deferredReasonLabel(reason)}x$count"
        }
    }

    private fun deferredReasonLabel(reason: DeferredReason): String =
        when (reason) {
            DeferredReason.TIME_WINDOW -> "时间窗口"
            DeferredReason.CAPACITY_LIMIT -> "容量限制"
            DeferredReason.STATE_CONFIRMATION -> "状态确认"
            DeferredReason.PREREQUISITE_PENDING -> "前置待满足"
            DeferredReason.CHILD_TASK_PENDING -> "子任务待完成"
            DeferredReason.NO_PROGRESS_COOLDOWN -> "无进展冷却"
        }

    private fun deferredActionText(
        action: TaskFlowAction,
        reason: DeferredReason,
        refreshRequested: Boolean,
    ): String {
        val reasonText = deferredReasonLabel(reason)
        return if (refreshRequested) {
            "${action.logName}明确延后($reasonText，补1次回查)"
        } else {
            "${action.logName}明确延后($reasonText)"
        }
    }


}

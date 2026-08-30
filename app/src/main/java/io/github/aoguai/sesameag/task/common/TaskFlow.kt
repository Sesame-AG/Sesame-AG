package io.github.aoguai.sesameag.task.common

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import io.github.aoguai.sesameag.util.TaskBlacklist
import org.json.JSONObject
import kotlin.math.max

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
    STATE_CONFIRMATION,
    PREREQUISITE_PENDING,
    CHILD_TASK_PENDING,
    NO_PROGRESS_COOLDOWN,
}

enum class TaskFlowDecision {
    BLACKLIST,
    RETRY_LATER,
    STOP_TODAY_OR_CURRENT_CHAIN,
    MARK_HANDLED,
    LOG_ONLY,
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
    // 延后动作按需请求回查；成功动作由引擎在当前动作批次结束后统一回查服务端状态。
    val refreshAfterAction: Boolean = false,
    // RPC 成功不一定代表服务端任务状态已经推进；用于控制进展统计和重复动作保护。
    val progressChanged: Boolean = true,
    val deferredReason: DeferredReason? = null,
    val deferredUntil: Long? = null,
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
    val noProgressSuccess: Boolean = false,
    val interrupted: Boolean = false,
    val deferredCount: Int = 0,
    val deferredReasonCounts: Map<DeferredReason, Int> = emptyMap(),
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
    internal val failedActionKeys = mutableSetOf<String>()
    internal val deferredActionKeys = mutableSetOf<String>()
    internal val executedActionSnapshotKeys = mutableSetOf<String>()
    internal val noProgressConfirmationSnapshotKeys = mutableSetOf<String>()
}

interface TaskFlowAdapter {
    val moduleName: String
    val flowName: String

    /** A retryable action can be deferred while independent candidates keep running. */
    val continueCurrentRoundOnRetryableFailure: Boolean
        get() = false

    fun query(): JSONObject

    fun isQuerySuccess(response: JSONObject): Boolean = true

    fun extractItems(response: JSONObject): List<TaskFlowItem>

    fun mapPhase(item: TaskFlowItem): TaskFlowPhase

    fun isFlowHandledToday(): Boolean = false

    fun onFlowHandledToday() {
        logInfo("$flowName[今日完成标记已存在，跳过任务流]")
    }

    fun shouldSkipByTodayState(item: TaskFlowItem): Boolean = false

    fun shouldSkip(item: TaskFlowItem): Boolean = false

    /** A conservatively skipped item that still needs service-side or manual progress blocks completion. */
    fun isUnresolvedWhenSkipped(item: TaskFlowItem): Boolean = false

    fun receive(item: TaskFlowItem): TaskFlowActionResult = unsupportedAction(item, TaskFlowAction.RECEIVE)

    fun complete(item: TaskFlowItem): TaskFlowActionResult = unsupportedAction(item, TaskFlowAction.COMPLETE)

    fun signup(item: TaskFlowItem): TaskFlowActionResult = unsupportedAction(item, TaskFlowAction.SIGNUP)

    fun send(item: TaskFlowItem): TaskFlowActionResult = unsupportedAction(item, TaskFlowAction.SEND)

    fun estimateRoundLimit(items: List<TaskFlowItem>): Int {
        var visibleTaskCount = 0
        var pendingTransitions = 0
        for (item in items) {
            if (shouldSkipByTodayState(item)) continue
            val phase = mapPhase(item)
            if ((phase != TaskFlowPhase.REWARD_READY && isBlacklisted(item)) || shouldSkip(item)) continue
            visibleTaskCount++
            when (phase) {
                TaskFlowPhase.REWARD_READY -> {
                    pendingTransitions += 1
                }

                TaskFlowPhase.READY_TO_COMPLETE -> {
                    val current = item.current ?: 0
                    val limit = item.limit ?: (current + 1)
                    pendingTransitions += max(1, limit - current) * 2
                }

                TaskFlowPhase.SIGNUP_REQUIRED -> {
                    pendingTransitions += 3
                }

                TaskFlowPhase.SIGNUP_COMPLETE -> {
                    pendingTransitions += 2
                }

                else -> {
                    Unit
                }
            }
        }
        return max(1, pendingTransitions + visibleTaskCount)
    }

    fun actionKey(
        item: TaskFlowItem,
        action: TaskFlowAction,
    ): String {
        val progressKey = item.current?.toString() ?: item.progress.ifBlank { "NO_PROGRESS" }
        val typeKey = item.actionType.ifBlank { item.type.ifBlank { "NO_TYPE" } }
        return "${action.logName}:${item.id}:$progressKey:$typeKey"
    }

    fun isBlacklisted(item: TaskFlowItem): Boolean = item.blacklistKeys.any { TaskBlacklist.isTaskInBlacklist(moduleName, it) }

    fun blacklist(
        item: TaskFlowItem,
        result: TaskFlowActionResult,
    ) {
        val taskId = item.id.trim()
        if (taskId.isBlank()) return
        if (result.code.isNotBlank()) {
            TaskBlacklist.autoAddToBlacklist(moduleName, taskId, errorCode = result.code)
        }
        TaskBlacklist.addToBlacklist(moduleName, taskId)
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

    fun onQueryFailed(response: JSONObject) = Unit

    fun onUnknownPhase(
        item: TaskFlowItem,
        phase: TaskFlowPhase,
    ) {
        logError("$flowName[未知状态：${item.title}，状态：${item.status}，phase=$phase]")
    }

    fun onRoundLimit(roundLimit: Int) {
        logError("$flowName[达到动态轮次上限$roundLimit，停止以避免重复循环]")
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

class TaskFlowEngine(
    private val adapter: TaskFlowAdapter,
    private val roundSleepMs: Long = 1000L,
    private val executionState: TaskFlowExecutionState = TaskFlowExecutionState(),
) {
    private companion object {
        const val MAX_DYNAMIC_ROUND_LIMIT = 64
        const val DYNAMIC_ROUND_LIMIT_EXTRA = 6
    }

    fun run(): TaskFlowRunResult {
        // 动作去重状态可由同一业务运行内的多个引擎实例共享。
        var round = 1
        var roundLimit = 1
        var hardRoundLimit = 1
        var progressedAny = false
        var actionAttemptedAny = false
        var noProgressSuccessAny = false
        var deferredCountAny = 0
        val deferredReasonCountsAny = linkedMapOf<DeferredReason, Int>()
        var failureCountAny = 0
        var tailFollowUpRefreshBudget = 1
        var confirmationRefreshOnlyRound = false

        while (round <= roundLimit) {
            if (adapter.isFlowHandledToday()) {
                adapter.onFlowHandledToday()
                return finishRunResult(
                    completed = true,
                    progressed = progressedAny,
                    stopped = false,
                    rounds = round - 1,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = false,
                    deferredCount = deferredCountAny,
                    deferredReasonCounts = deferredReasonCountsAny,
                    failureCount = failureCountAny,
                )
            }

            if (ApplicationHookConstants.isOffline()) {
                adapter.logInfo("${adapter.flowName}[检测到离线模式，中断任务流]")
                return finishRunResult(
                    completed = false,
                    progressed = progressedAny,
                    stopped = true,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = true,
                    deferredCount = deferredCountAny,
                    deferredReasonCounts = deferredReasonCountsAny,
                    failureCount = failureCountAny,
                )
            }

            val response =
                try {
                    adapter.query()
                } catch (t: Throwable) {
                    adapter.logError("${adapter.flowName}[查询异常：${t.message}]")
                    return finishRunResult(
                        completed = false,
                        progressed = progressedAny,
                        stopped = true,
                        rounds = round,
                        actionAttempted = actionAttemptedAny,
                        noProgressSuccess = noProgressSuccessAny,
                        interrupted = ApplicationHookConstants.isOffline(),
                        deferredCount = deferredCountAny,
                        deferredReasonCounts = deferredReasonCountsAny,
                        failureCount = failureCountAny + 1,
                    )
                }

            RpcOfflineRisk.enterOfflineIfNeeded(adapter.flowName, response)
            if (ApplicationHookConstants.isOffline()) {
                adapter.logInfo("${adapter.flowName}[查询后检测到离线模式，中断任务流]")
                return finishRunResult(
                    completed = false,
                    progressed = progressedAny,
                    stopped = true,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = true,
                    deferredCount = deferredCountAny,
                    deferredReasonCounts = deferredReasonCountsAny,
                    failureCount = failureCountAny,
                )
            }

            if (!adapter.isQuerySuccess(response)) {
                adapter.onQueryFailed(response)
                return finishRunResult(
                    completed = false,
                    progressed = progressedAny,
                    stopped = true,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = ApplicationHookConstants.isOffline(),
                    deferredCount = deferredCountAny,
                    deferredReasonCounts = deferredReasonCountsAny,
                    failureCount = failureCountAny + 1,
                )
            }

            val items = adapter.extractItems(response)
            if (round == 1) {
                roundLimit =
                    adapter
                        .estimateRoundLimit(items)
                        .coerceAtLeast(1)
                        .coerceAtMost(MAX_DYNAMIC_ROUND_LIMIT)
                hardRoundLimit = calculateHardRoundLimit(roundLimit)
            }

            val snapshot = buildSnapshot(items)
            var progressed = false
            var stopCurrentRound = false
            var refreshRequested = false
            var noProgressConfirmationRefreshRequested = false
            var refreshBoundaryAction: TaskFlowAction? = null
            val roundActions = mutableListOf<TaskFlowRoundAction>()
            val roundDeferredReasonCounts = linkedMapOf<DeferredReason, Int>()
            val candidates =
                if (confirmationRefreshOnlyRound) {
                    adapter.logInfo("${adapter.flowName}[达到动态轮次上限，保留本轮服务端确认查询]")
                    confirmationRefreshOnlyRound = false
                    emptyList()
                } else {
                    buildActionCandidates(items)
                }

            for (candidate in candidates) {
                if (ApplicationHookConstants.isOffline()) {
                    stopCurrentRound = true
                    roundActions.add(TaskFlowRoundAction("离线中断"))
                    break
                }

                val item = candidate.item
                val action = candidate.initialAction
                if (refreshBoundaryAction != null && refreshBoundaryAction != action) {
                    break
                }
                if (shouldSkipItem(item)) continue

                val actionKey = adapter.actionKey(item, action)
                val actionSnapshotKey = actionSnapshotKey(item, action)
                if (actionKey in executionState.failedActionKeys) {
                    adapter.logInfo("${adapter.flowName}[本轮已跳过${action.logName}失败任务：${item.title}]")
                    roundActions.add(TaskFlowRoundAction("跳过已失败${action.logName}", item.title))
                    continue
                }
                if (actionSnapshotKey in executionState.noProgressConfirmationSnapshotKeys) {
                    adapter.logInfo("${adapter.flowName}[回查后未确认进展，跳过重复${action.logName}：${item.title}]")
                    roundActions.add(TaskFlowRoundAction("跳过未确认进展${action.logName}", item.title))
                    continue
                }
                if (actionSnapshotKey in executionState.executedActionSnapshotKeys) {
                    adapter.logInfo("${adapter.flowName}[快照未变化，跳过重复${action.logName}：${item.title}]")
                    roundActions.add(TaskFlowRoundAction("跳过未变化快照${action.logName}", item.title))
                    continue
                }
                if (actionKey in executionState.deferredActionKeys) {
                    adapter.logInfo("${adapter.flowName}[本轮已跳过明确延后任务：${item.title}]")
                    roundActions.add(TaskFlowRoundAction("跳过已延后${action.logName}", item.title))
                    continue
                }

                val result = executeAction(item, action)
                actionAttemptedAny = true
                val deferredReason = result.deferredReason
                val requiresStateConfirmation = deferredReason == DeferredReason.STATE_CONFIRMATION
                val failureType = result.failureType ?: TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
                if (ApplicationHookConstants.isOffline()) {
                    stopCurrentRound = true
                    roundActions.add(TaskFlowRoundAction("离线中断", item.title))
                    break
                }
                if (deferredReason != null) {
                    adapter.afterDeferred(item, action, result)
                    if (!requiresStateConfirmation) {
                        executionState.deferredActionKeys.add(actionKey)
                    }
                    deferredCountAny++
                    deferredReasonCountsAny[deferredReason] =
                        (deferredReasonCountsAny[deferredReason] ?: 0) + 1
                    roundDeferredReasonCounts[deferredReason] =
                        (roundDeferredReasonCounts[deferredReason] ?: 0) + 1
                    if (result.progressChanged) {
                        progressed = true
                        progressedAny = true
                    }
                    logDeferred(item, action, result, deferredReason)
                    roundActions.add(
                        TaskFlowRoundAction(
                            deferredActionText(
                                action,
                                deferredReason,
                                result.refreshAfterAction || requiresStateConfirmation,
                            ),
                            item.title,
                        ),
                    )
                    if (requiresStateConfirmation) {
                        executionState.noProgressConfirmationSnapshotKeys.add(actionSnapshotKey)
                        noProgressConfirmationRefreshRequested = true
                    }
                    if (result.stopCurrentRound) {
                        stopCurrentRound = true
                        break
                    }
                    if (result.refreshAfterAction || requiresStateConfirmation) {
                        refreshRequested = true
                        refreshBoundaryAction = action
                    }
                    continue
                }
                if (result.success) {
                    executionState.executedActionSnapshotKeys.add(actionSnapshotKey)
                    adapter.afterSuccess(item, action, result)
                    if (result.progressChanged) {
                        progressed = true
                        progressedAny = true
                    } else {
                        noProgressSuccessAny = true
                    }
                    roundActions.add(TaskFlowRoundAction(successActionText(action), item.title))
                    if (!result.progressChanged) {
                        executionState.noProgressConfirmationSnapshotKeys.add(actionSnapshotKey)
                        noProgressConfirmationRefreshRequested = true
                    }
                    refreshRequested = true
                    refreshBoundaryAction = action
                    continue
                }

                if (failureType == TaskRpcFailureType.TERMINAL_DONE) {
                    logFailure(item, action, result, failureType, TaskFlowDecision.MARK_HANDLED)
                    adapter.afterFailure(item, action, result, TaskFlowDecision.MARK_HANDLED)
                    executionState.failedActionKeys.add(actionKey)
                    progressed = true
                    progressedAny = true
                    roundActions.add(TaskFlowRoundAction("终态成功", item.title))
                    continue
                }

                val decision = decideFailure(failureType)
                if (decision == TaskFlowDecision.BLACKLIST) {
                    adapter.blacklist(item, result)
                }
                failureCountAny++
                logFailure(item, action, result, failureType, decision)
                adapter.afterFailure(item, action, result, decision)
                executionState.failedActionKeys.add(actionKey)
                val shouldStopAfterFailure =
                    result.stopCurrentRound ||
                        (decision == TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN &&
                            !result.continueCurrentRoundOnFailure) ||
                        (decision == TaskFlowDecision.RETRY_LATER && !result.continueCurrentRoundOnFailure)
                roundActions.add(
                    TaskFlowRoundAction(
                        failureActionText(action, decision, shouldStopAfterFailure),
                        item.title,
                    ),
                )
                if (shouldStopAfterFailure) {
                    stopCurrentRound = true
                    break
                }
            }

            adapter.logInfo(
                "${adapter.flowName}刷新进度[轮次:$round/$roundLimit]" +
                    "[处理前已完成:${snapshot.completedTasks}/${snapshot.totalTasks}]" +
                    "[处理前待处理:${snapshot.availableTasks}]" +
                    "[本轮动作:${describeRoundActions(roundActions)}]" +
                    "[本轮明确延后:${describeDeferredReasonCounts(roundDeferredReasonCounts)}]" +
                    "[本轮批量后刷新:$refreshRequested]" +
                    "[本轮有进展:$progressed]",
            )

            if (refreshRequested &&
                !stopCurrentRound &&
                !ApplicationHookConstants.isOffline()
            ) {
                val confirmationOnly = round >= hardRoundLimit
                val requiredRound = round + 1
                val maximumRefreshRound =
                    if (confirmationOnly) hardRoundLimit + 1 else hardRoundLimit
                if (requiredRound > roundLimit) {
                    roundLimit = requiredRound.coerceAtMost(maximumRefreshRound)
                }
                if (confirmationOnly) {
                    confirmationRefreshOnlyRound = true
                }
                val reason =
                    if (noProgressConfirmationRefreshRequested) {
                        "动作已受理但未确认进展"
                    } else {
                        "动作已推进"
                    }
                adapter.logInfo("${adapter.flowName}[$reason，先回查服务端任务列表]")
                GlobalThreadPools.sleepCompat(roundSleepMs)
                round++
                continue
            }

            if (!stopCurrentRound &&
                !ApplicationHookConstants.isOffline() &&
                snapshot.isComplete
            ) {
                adapter.onAllTasksDone(snapshot)
                return finishRunResult(
                    completed = true,
                    progressed = progressedAny,
                    stopped = false,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = false,
                    deferredCount = deferredCountAny,
                    deferredReasonCounts = deferredReasonCountsAny,
                    failureCount = failureCountAny,
                )
            }

            if (stopCurrentRound || !progressed) {
                return finishRunResult(
                    completed = false,
                    progressed = progressedAny,
                    stopped = stopCurrentRound,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = ApplicationHookConstants.isOffline(),
                    deferredCount = deferredCountAny,
                    deferredReasonCounts = deferredReasonCountsAny,
                    failureCount = failureCountAny,
                )
            }

            if (executionState.deferredActionKeys.isNotEmpty() && tailFollowUpRefreshBudget > 0) {
                adapter.logInfo("${adapter.flowName}[检测到前置状态已推进，允许1次尾部补收刷新]")
                executionState.deferredActionKeys.clear()
                tailFollowUpRefreshBudget--
            }

            val extendedRoundLimit = extendRoundLimitIfNeeded(round, roundLimit, hardRoundLimit, progressed)
            if (extendedRoundLimit != roundLimit) {
                adapter.logInfo(
                    "${adapter.flowName}[动态任务仍有进展，轮次上限延长:$roundLimit->$extendedRoundLimit/$hardRoundLimit]",
                )
                roundLimit = extendedRoundLimit
            }

            GlobalThreadPools.sleepCompat(roundSleepMs)
            round++
        }

        adapter.onRoundLimit(roundLimit)
        return finishRunResult(
            completed = false,
            progressed = progressedAny,
            stopped = true,
            rounds = roundLimit,
            actionAttempted = actionAttemptedAny,
            noProgressSuccess = noProgressSuccessAny,
            interrupted = ApplicationHookConstants.isOffline(),
            deferredCount = deferredCountAny,
            deferredReasonCounts = deferredReasonCountsAny,
            failureCount = failureCountAny + 1,
        )
    }

    private fun calculateHardRoundLimit(initialRoundLimit: Int): Int {
        val normalizedInitialLimit = max(1, initialRoundLimit)
        val extendedRoundLimit =
            max(
                normalizedInitialLimit * 3,
                normalizedInitialLimit + DYNAMIC_ROUND_LIMIT_EXTRA,
            )
        return extendedRoundLimit
            .coerceAtMost(MAX_DYNAMIC_ROUND_LIMIT)
            .coerceAtLeast(normalizedInitialLimit)
    }

    private fun extendRoundLimitIfNeeded(
        round: Int,
        roundLimit: Int,
        hardRoundLimit: Int,
        progressed: Boolean,
    ): Int {
        if (!progressed || round < roundLimit || roundLimit >= hardRoundLimit) {
            return roundLimit
        }
        return (roundLimit + 1).coerceAtMost(hardRoundLimit)
    }

    private fun buildRunResult(
        completed: Boolean,
        progressed: Boolean,
        stopped: Boolean,
        rounds: Int,
        actionAttempted: Boolean,
        noProgressSuccess: Boolean,
        interrupted: Boolean = false,
        deferredCount: Int = 0,
        deferredReasonCounts: Map<DeferredReason, Int> = emptyMap(),
    ): TaskFlowRunResult =
        TaskFlowRunResult(
            completed = completed,
            progressed = progressed,
            stopped = stopped,
            rounds = rounds,
            actionAttempted = actionAttempted,
            progressChanged = progressed,
            noProgressSuccess = noProgressSuccess,
            interrupted = interrupted,
            deferredCount = deferredCount,
            deferredReasonCounts = deferredReasonCounts,
        )

    private fun finishRunResult(
        completed: Boolean,
        progressed: Boolean,
        stopped: Boolean,
        rounds: Int,
        actionAttempted: Boolean,
        noProgressSuccess: Boolean,
        interrupted: Boolean = false,
        deferredCount: Int = 0,
        deferredReasonCounts: Map<DeferredReason, Int> = emptyMap(),
        failureCount: Int = 0,
    ): TaskFlowRunResult {
        logFinalSummary(
            completed = completed,
            progressed = progressed,
            stopped = stopped,
            rounds = rounds,
            actionAttempted = actionAttempted,
            noProgressSuccess = noProgressSuccess,
            interrupted = interrupted,
            deferredCount = deferredCount,
            deferredReasonCounts = deferredReasonCounts,
            failureCount = failureCount,
        )
        return buildRunResult(
            completed = completed,
            progressed = progressed,
            stopped = stopped,
            rounds = rounds,
            actionAttempted = actionAttempted,
            noProgressSuccess = noProgressSuccess,
            interrupted = interrupted,
            deferredCount = deferredCount,
            deferredReasonCounts = deferredReasonCounts,
        )
    }

    private fun actionSnapshotKey(
        item: TaskFlowItem,
        action: TaskFlowAction,
    ): String =
        listOf(
            action.logName,
            adapter.actionKey(item, action),
            item.id.ifBlank { item.title },
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
            val action = phase.toAction()
            if (action == null) {
                if (phase == TaskFlowPhase.UNKNOWN) {
                    adapter.onUnknownPhase(item, phase)
                }
                continue
            }

            candidates.add(TaskFlowActionCandidate(index, item, action))
        }
        // 候选动作按领奖优先排序；同一快照的相同稳定动作只执行一次。
        return candidates
            .sortedWith(
                compareBy<TaskFlowActionCandidate> { actionPriority(it.initialAction) }
                    .thenBy { it.index },
            ).distinctBy { candidate ->
                adapter.actionKey(candidate.item, candidate.initialAction)
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
        val result =
            try {
                when (action) {
                    TaskFlowAction.RECEIVE -> adapter.receive(item)
                    TaskFlowAction.COMPLETE -> adapter.complete(item)
                    TaskFlowAction.SIGNUP -> adapter.signup(item)
                    TaskFlowAction.SEND -> adapter.send(item)
                }
            } catch (t: Throwable) {
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

            TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
            TaskRpcFailureType.NON_RETRYABLE_INVALID,
            -> TaskFlowDecision.BLACKLIST

            TaskRpcFailureType.RETRYABLE_RPC -> TaskFlowDecision.RETRY_LATER

            TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW -> TaskFlowDecision.LOG_ONLY
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
                if (result.detail.isNotBlank()) {
                    append(" ")
                    append(result.detail)
                }
                if (result.raw.isNotBlank()) {
                    append(" raw=")
                    append(result.raw)
                }
            }

        if (failureType == TaskRpcFailureType.TERMINAL_DONE) {
            adapter.logInfo(message)
        } else {
            adapter.logError(message)
        }
    }

    private fun logFinalSummary(
        completed: Boolean,
        progressed: Boolean,
        stopped: Boolean,
        rounds: Int,
        actionAttempted: Boolean,
        noProgressSuccess: Boolean,
        interrupted: Boolean,
        deferredCount: Int,
        deferredReasonCounts: Map<DeferredReason, Int>,
        failureCount: Int,
    ) {
        val summary =
            buildString {
                append(adapter.flowName)
                when {
                    completed -> append("[本轮完成]")
                    noProgressSuccess && !progressed -> append("[本轮成功但未确认进展]")
                    !interrupted && failureCount == 0 -> append("[本轮明确延后]")
                    else -> append("[本轮动作失败(含可重试)]")
                }
                append("[轮次:")
                append(rounds)
                append("]")
                append("[动作:")
                append(actionAttempted)
                append("]")
                append("[有进展:")
                append(progressed)
                append("]")
                append("[无确认进展成功:")
                append(noProgressSuccess)
                append("]")
                append("[明确延后:")
                append(describeDeferredReasonCounts(deferredReasonCounts))
                append("]")
                append("[动作失败(含可重试):")
                append(failureCount)
                append("]")
                if (stopped) {
                    append("[已停止当前链路]")
                }
                if (interrupted) {
                    append("[中断]")
                }
            }
        if (!interrupted && failureCount == 0) {
            adapter.logInfo(summary)
        } else {
            adapter.logError(summary)
        }
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

    private fun failureActionText(
        action: TaskFlowAction,
        decision: TaskFlowDecision,
        stopped: Boolean,
    ): String =
        when (decision) {
            TaskFlowDecision.RETRY_LATER -> if (stopped) "止损停止" else "${action.logName}失败待重试"
            TaskFlowDecision.BLACKLIST -> "${action.logName}失败，已加入自动跳过列表(黑名单)"
            TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN -> "${action.logName}业务止损"
            TaskFlowDecision.MARK_HANDLED -> "终态成功"
            TaskFlowDecision.LOG_ONLY -> "${action.logName}失败"
        }
}

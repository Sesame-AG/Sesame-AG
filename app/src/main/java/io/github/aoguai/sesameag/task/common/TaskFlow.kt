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
    UNKNOWN_NEEDS_REVIEW
}

enum class TaskFlowPhase {
    REWARD_READY,
    READY_TO_COMPLETE,
    SIGNUP_REQUIRED,
    SIGNUP_COMPLETE,
    TERMINAL,
    BUSINESS_ACTION,
    UNSUPPORTED,
    UNKNOWN
}

enum class TaskFlowAction(val logName: String) {
    RECEIVE("receive"),
    COMPLETE("complete"),
    SIGNUP("signup"),
    SEND("send")
}

enum class TaskFlowDecision {
    BLACKLIST,
    RETRY_LATER,
    STOP_TODAY_OR_CURRENT_CHAIN,
    MARK_HANDLED,
    LOG_ONLY
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
    val limit: Int? = null
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
    // 默认批量处理当前查询快照，只有强依赖服务端新状态的动作才要求立即刷新。
    val refreshAfterAction: Boolean = false,
    // RPC 成功不一定代表服务端任务状态已经推进；无进展成功不继续驱动刷新闭环。
    val progressChanged: Boolean = true
) {
    companion object {
        fun success(
            refreshAfterAction: Boolean = false,
            progressChanged: Boolean = true
        ): TaskFlowActionResult {
            return TaskFlowActionResult(
                success = true,
                refreshAfterAction = refreshAfterAction,
                progressChanged = progressChanged
            )
        }

        fun failure(
            failureType: TaskRpcFailureType,
            code: String = "",
            message: String = "",
            rpc: String = "",
            raw: String = "",
            detail: String = "",
            stopCurrentRound: Boolean = false,
            continueCurrentRoundOnFailure: Boolean = false
        ): TaskFlowActionResult {
            return TaskFlowActionResult(
                success = false,
                failureType = failureType,
                code = code,
                message = message,
                rpc = rpc,
                raw = raw,
                detail = detail,
                stopCurrentRound = stopCurrentRound,
                continueCurrentRoundOnFailure = continueCurrentRoundOnFailure
            )
        }
    }
}

data class TaskFlowSnapshot(
    val totalTasks: Int,
    val completedTasks: Int,
    val availableTasks: Int
)

data class TaskFlowRoundAction(
    val action: String,
    val taskName: String = ""
) {
    fun describe(): String {
        return if (taskName.isBlank()) action else "$action：$taskName"
    }
}

data class TaskFlowRunResult(
    val completed: Boolean,
    val progressed: Boolean,
    val stopped: Boolean,
    val rounds: Int,
    val actionAttempted: Boolean = false,
    val progressChanged: Boolean = progressed,
    val noProgressSuccess: Boolean = false,
    val interrupted: Boolean = false
)

private data class TaskFlowActionCandidate(
    val index: Int,
    val item: TaskFlowItem,
    val initialAction: TaskFlowAction
)

interface TaskFlowAdapter {
    val moduleName: String
    val flowName: String

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
                TaskFlowPhase.REWARD_READY -> pendingTransitions += 1
                TaskFlowPhase.READY_TO_COMPLETE -> {
                    val current = item.current ?: 0
                    val limit = item.limit ?: (current + 1)
                    pendingTransitions += max(1, limit - current) * 2
                }
                TaskFlowPhase.SIGNUP_REQUIRED -> pendingTransitions += 3
                TaskFlowPhase.SIGNUP_COMPLETE -> pendingTransitions += 2
                else -> Unit
            }
        }
        return max(1, pendingTransitions + visibleTaskCount)
    }

    fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
        val progressKey = item.current?.toString() ?: item.progress.ifBlank { "NO_PROGRESS" }
        val typeKey = item.actionType.ifBlank { item.type.ifBlank { "NO_TYPE" } }
        return "${action.logName}:${item.id.ifBlank { item.title }}:$progressKey:$typeKey"
    }

    fun isBlacklisted(item: TaskFlowItem): Boolean {
        return item.blacklistKeys.any { TaskBlacklist.isTaskInBlacklist(moduleName, it) }
    }

    fun blacklist(item: TaskFlowItem, result: TaskFlowActionResult) {
        val taskId = item.id.ifBlank { item.title }
        if (taskId.isBlank()) return
        if (result.code.isNotBlank()) {
            TaskBlacklist.autoAddToBlacklist(moduleName, taskId, item.title, result.code)
        }
        TaskBlacklist.addToBlacklist(moduleName, taskId, item.title)
    }

    fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) = Unit

    fun afterFailure(
        item: TaskFlowItem,
        action: TaskFlowAction,
        result: TaskFlowActionResult,
        decision: TaskFlowDecision
    ) = Unit

    fun onAllTasksDone(snapshot: TaskFlowSnapshot) = Unit

    fun onQueryFailed(response: JSONObject) = Unit

    fun onUnknownPhase(item: TaskFlowItem, phase: TaskFlowPhase) {
        logError("$flowName[未知状态：${item.title}，状态：${item.status}，phase=$phase]")
    }

    fun onRoundLimit(roundLimit: Int) {
        logError("$flowName[达到动态轮次上限$roundLimit，停止以避免重复循环]")
    }

    fun logInfo(message: String)

    fun logError(message: String)

    private fun unsupportedAction(item: TaskFlowItem, action: TaskFlowAction): TaskFlowActionResult {
        return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            message = "adapter未实现${action.logName}",
            rpc = "TaskFlowAdapter.${action.logName}",
            detail = "taskId=${item.id} taskName=${item.title} status=${item.status}"
        )
    }
}

class TaskFlowEngine(
    private val adapter: TaskFlowAdapter,
    private val roundSleepMs: Long = 1000L
) {
    fun run(): TaskFlowRunResult {
        val failedActionKeys = mutableSetOf<String>()
        var round = 1
        var roundLimit = 1
        var progressedAny = false
        var actionAttemptedAny = false
        var noProgressSuccessAny = false

        while (round <= roundLimit) {
            if (adapter.isFlowHandledToday()) {
                adapter.onFlowHandledToday()
                return buildRunResult(
                    completed = true,
                    progressed = progressedAny,
                    stopped = false,
                    rounds = 0,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny
                )
            }

            if (ApplicationHookConstants.isOffline()) {
                adapter.logInfo("${adapter.flowName}[检测到离线模式，中断任务流]")
                return buildRunResult(
                    completed = false,
                    progressed = progressedAny,
                    stopped = true,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = true
                )
            }

            val response = try {
                adapter.query()
            } catch (t: Throwable) {
                adapter.logError("${adapter.flowName}[查询异常：${t.message}]")
                return buildRunResult(
                    completed = false,
                    progressed = progressedAny,
                    stopped = true,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = ApplicationHookConstants.isOffline()
                )
            }

            RpcOfflineRisk.enterOfflineIfNeeded(adapter.flowName, response)
            if (ApplicationHookConstants.isOffline()) {
                adapter.logInfo("${adapter.flowName}[查询后检测到离线模式，中断任务流]")
                return buildRunResult(
                    completed = false,
                    progressed = progressedAny,
                    stopped = true,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = true
                )
            }

            if (!adapter.isQuerySuccess(response)) {
                adapter.onQueryFailed(response)
                return buildRunResult(
                    completed = false,
                    progressed = progressedAny,
                    stopped = true,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = ApplicationHookConstants.isOffline()
                )
            }

            val items = adapter.extractItems(response)
            if (round == 1) {
                roundLimit = adapter.estimateRoundLimit(items)
            }

            val snapshot = buildSnapshot(items)
            var progressed = false
            var stopCurrentRound = false
            var refreshRequested = false
            val roundActions = mutableListOf<TaskFlowRoundAction>()
            val candidates = buildActionCandidates(items)

            for (candidate in candidates) {
                if (ApplicationHookConstants.isOffline()) {
                    stopCurrentRound = true
                    roundActions.add(TaskFlowRoundAction("离线中断"))
                    break
                }

                val item = candidate.item
                if (adapter.shouldSkipByTodayState(item)) {
                    continue
                }
                if (shouldSkipBlacklisted(item)) {
                    continue
                }

                if (adapter.shouldSkip(item)) {
                    continue
                }

                val action = candidate.initialAction

                val actionKey = adapter.actionKey(item, action)
                if (actionKey in failedActionKeys) {
                    adapter.logInfo("${adapter.flowName}[本轮已跳过${action.logName}失败任务：${item.title}]")
                    roundActions.add(TaskFlowRoundAction("跳过已失败${action.logName}", item.title))
                    continue
                }

                val result = executeAction(item, action)
                actionAttemptedAny = true
                val failureType = result.failureType ?: TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
                if (ApplicationHookConstants.isOffline()) {
                    stopCurrentRound = true
                    roundActions.add(TaskFlowRoundAction("离线中断", item.title))
                    break
                }
                if (result.success) {
                    adapter.afterSuccess(item, action, result)
                    if (result.progressChanged) {
                        progressed = true
                        progressedAny = true
                    } else {
                        noProgressSuccessAny = true
                    }
                    roundActions.add(TaskFlowRoundAction(successActionText(action), item.title))
                    if (result.refreshAfterAction) {
                        refreshRequested = true
                        break
                    }
                    continue
                }

                if (failureType == TaskRpcFailureType.TERMINAL_DONE) {
                    logFailure(item, action, result, failureType, TaskFlowDecision.MARK_HANDLED)
                    adapter.afterFailure(item, action, result, TaskFlowDecision.MARK_HANDLED)
                    failedActionKeys.add(actionKey)
                    progressed = true
                    progressedAny = true
                    roundActions.add(TaskFlowRoundAction("终态成功", item.title))
                    continue
                }

                val decision = decideFailure(failureType)
                if (decision == TaskFlowDecision.BLACKLIST) {
                    adapter.blacklist(item, result)
                }
                logFailure(item, action, result, failureType, decision)
                adapter.afterFailure(item, action, result, decision)
                failedActionKeys.add(actionKey)
                val shouldStopAfterFailure =
                    result.stopCurrentRound ||
                        decision == TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN ||
                        (decision == TaskFlowDecision.RETRY_LATER && !result.continueCurrentRoundOnFailure)
                roundActions.add(
                    TaskFlowRoundAction(
                        failureActionText(action, decision, shouldStopAfterFailure),
                        item.title
                    )
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
                    "[本轮批量后刷新:${progressed && !stopCurrentRound}]" +
                    "[立即刷新请求:$refreshRequested]" +
                    "[本轮有进展:$progressed]"
            )

            if (!stopCurrentRound &&
                !ApplicationHookConstants.isOffline() &&
                snapshot.totalTasks > 0 &&
                snapshot.completedTasks >= snapshot.totalTasks &&
                snapshot.availableTasks == 0
            ) {
                adapter.onAllTasksDone(snapshot)
                return buildRunResult(
                    completed = true,
                    progressed = progressedAny,
                    stopped = false,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny
                )
            }

            if (stopCurrentRound || !progressed) {
                return buildRunResult(
                    completed = false,
                    progressed = progressedAny,
                    stopped = stopCurrentRound,
                    rounds = round,
                    actionAttempted = actionAttemptedAny,
                    noProgressSuccess = noProgressSuccessAny,
                    interrupted = ApplicationHookConstants.isOffline()
                )
            }

            GlobalThreadPools.sleepCompat(roundSleepMs)
            round++
        }

        adapter.onRoundLimit(roundLimit)
        return buildRunResult(
            completed = false,
            progressed = progressedAny,
            stopped = true,
            rounds = roundLimit,
            actionAttempted = actionAttemptedAny,
            noProgressSuccess = noProgressSuccessAny,
            interrupted = ApplicationHookConstants.isOffline()
        )
    }

    private fun buildRunResult(
        completed: Boolean,
        progressed: Boolean,
        stopped: Boolean,
        rounds: Int,
        actionAttempted: Boolean,
        noProgressSuccess: Boolean,
        interrupted: Boolean = false
    ): TaskFlowRunResult {
        return TaskFlowRunResult(
            completed = completed,
            progressed = progressed,
            stopped = stopped,
            rounds = rounds,
            actionAttempted = actionAttempted,
            progressChanged = progressed,
            noProgressSuccess = noProgressSuccess,
            interrupted = interrupted
        )
    }

    private fun buildSnapshot(items: List<TaskFlowItem>): TaskFlowSnapshot {
        var totalTasks = 0
        var completedTasks = 0
        var availableTasks = 0
        for (item in items) {
            if (adapter.shouldSkipByTodayState(item)) {
                continue
            }
            if (shouldSkipBlacklisted(item)) {
                continue
            }
            if (adapter.shouldSkip(item)) continue

            val phase = adapter.mapPhase(item)
            totalTasks++
            when (phase) {
                TaskFlowPhase.TERMINAL -> completedTasks++
                TaskFlowPhase.REWARD_READY,
                TaskFlowPhase.READY_TO_COMPLETE,
                TaskFlowPhase.SIGNUP_REQUIRED,
                TaskFlowPhase.SIGNUP_COMPLETE -> availableTasks++
                else -> Unit
            }
        }
        return TaskFlowSnapshot(totalTasks, completedTasks, availableTasks)
    }

    private fun buildActionCandidates(items: List<TaskFlowItem>): List<TaskFlowActionCandidate> {
        val candidates = mutableListOf<TaskFlowActionCandidate>()
        for ((index, item) in items.withIndex()) {
            if (adapter.shouldSkipByTodayState(item)) continue
            if (shouldSkipBlacklisted(item)) continue
            if (adapter.shouldSkip(item)) continue

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
        // 候选动作按领奖优先排序；黑名单仅拦截主动推进，待领奖任务继续放行。
        return candidates.sortedWith(
            compareBy<TaskFlowActionCandidate> { actionPriority(it.initialAction) }
                .thenBy { it.index }
        )
    }

    private fun shouldSkipBlacklisted(item: TaskFlowItem): Boolean {
        if (adapter.mapPhase(item) == TaskFlowPhase.REWARD_READY) return false
        return adapter.isBlacklisted(item)
    }

    private fun executeAction(item: TaskFlowItem, action: TaskFlowAction): TaskFlowActionResult {
        return try {
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
                raw = t.toString()
            )
        }
    }

    private fun actionPriority(action: TaskFlowAction): Int {
        return when (action) {
            TaskFlowAction.RECEIVE -> 0
            TaskFlowAction.SEND -> 1
            TaskFlowAction.SIGNUP -> 2
            TaskFlowAction.COMPLETE -> 3
        }
    }

    private fun describeRoundActions(actions: List<TaskFlowRoundAction>): String {
        if (actions.isEmpty()) {
            return "无可执行动作"
        }
        val visibleLimit = 8
        val visibleActions = actions
            .take(visibleLimit)
            .joinToString("；") { it.describe() }
        return if (actions.size > visibleLimit) {
            "$visibleActions；... 共${actions.size}个动作"
        } else {
            visibleActions
        }
    }

    private fun decideFailure(failureType: TaskRpcFailureType): TaskFlowDecision {
        return when (failureType) {
            TaskRpcFailureType.TERMINAL_DONE -> TaskFlowDecision.MARK_HANDLED
            TaskRpcFailureType.BUSINESS_LIMIT -> TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN
            TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
            TaskRpcFailureType.NON_RETRYABLE_INVALID -> TaskFlowDecision.BLACKLIST
            TaskRpcFailureType.RETRYABLE_RPC -> TaskFlowDecision.RETRY_LATER
            TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW -> TaskFlowDecision.LOG_ONLY
        }
    }

    private fun logFailure(
        item: TaskFlowItem,
        action: TaskFlowAction,
        result: TaskFlowActionResult,
        failureType: TaskRpcFailureType,
        decision: TaskFlowDecision
    ) {
        val message = buildString {
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

    private fun TaskFlowPhase.toAction(): TaskFlowAction? {
        return when (this) {
            TaskFlowPhase.REWARD_READY -> TaskFlowAction.RECEIVE
            TaskFlowPhase.READY_TO_COMPLETE -> TaskFlowAction.COMPLETE
            TaskFlowPhase.SIGNUP_REQUIRED -> TaskFlowAction.SIGNUP
            TaskFlowPhase.SIGNUP_COMPLETE -> TaskFlowAction.SEND
            TaskFlowPhase.TERMINAL,
            TaskFlowPhase.BUSINESS_ACTION,
            TaskFlowPhase.UNSUPPORTED,
            TaskFlowPhase.UNKNOWN -> null
        }
    }

    private fun successActionText(action: TaskFlowAction): String {
        return when (action) {
            TaskFlowAction.RECEIVE -> "领取奖励"
            TaskFlowAction.COMPLETE -> "完成任务"
            TaskFlowAction.SIGNUP -> "报名"
            TaskFlowAction.SEND -> "发送任务"
        }
    }

    private fun failureActionText(
        action: TaskFlowAction,
        decision: TaskFlowDecision,
        stopped: Boolean
    ): String {
        return when (decision) {
            TaskFlowDecision.RETRY_LATER -> if (stopped) "止损停止" else "${action.logName}失败待重试"
            TaskFlowDecision.BLACKLIST -> "${action.logName}失败并黑名单"
            TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN -> "${action.logName}业务止损"
            TaskFlowDecision.MARK_HANDLED -> "终态成功"
            TaskFlowDecision.LOG_ONLY -> "${action.logName}失败"
        }
    }
}

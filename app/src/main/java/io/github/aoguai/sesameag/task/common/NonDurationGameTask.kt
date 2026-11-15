package io.github.aoguai.sesameag.task.common

import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import org.json.JSONObject

/** 游戏协议由 GameTask 定义，完成与领奖仍由所属模块回查。 */
object NonDurationGameTask {
    data class Contract(
        val descriptor: GameCenterPlayRpcCall.GameTaskDescriptor,
        val channel: String,
        val actions: Map<String, Int>,
        val componentTasks: Map<String, Set<String>>,
        val componentKey: String,
        val raw: String,
        val failure: TaskFlowActionResult? = null,
    )

    /** 执行和所属模块回查使用同一份动作合同。 */
    fun resolve(game: GameTask, vararg roots: JSONObject?, channel: String = "", detail: String = ""): Contract {
        val descriptor = GameCenterPlayRpcCall.describeTask(*roots)
        val actionChannel = descriptor.urlParameters["channel"].orEmpty()
            .ifBlank { channel.ifBlank { game.channel } }
        val objects = descriptor.objects.toMutableList()
        val legacyGame = game in listOf(
            GameTask.Orchard_ncscc, GameTask.Farm_ddply, GameTask.Forest_slxcc, GameTask.Forest_sljyd,
        )
        val originalMap = descriptor.urlParameters["originalMap"]?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        val componentScene = descriptor.componentScene
        val componentContract = componentScene in setOf("ladder_task", "EXTERNAL_GAME_CENTER_TASK")
        val queryComponent = !legacyGame || componentContract
        originalMap?.let { objects += it }
        objects += JSONObject(descriptor.urlParameters)
        val actions = linkedMapOf<String, Int>()
        for (task in objects) {
            val (action, count) = GameCenterPlayRpcCall.nonDurationAction(task, game.appId) ?: continue
            actions[action] = maxOf(actions[action] ?: 0, count)
        }
        val countedActions = objects.filter { it.optString("taskId").contains("__") }
            .mapNotNull { GameCenterPlayRpcCall.nonDurationAction(it, game.appId)?.first }.toSet()
        if (actions.isEmpty() && game.appId == "2060170000365923" && objects.any { task ->
                listOf(task.optString("taskId"), task.optString("taskType"), task.optString("iepTaskId"))
                    .contains("LMCT_QDRW_LEYUAN")
            }) {
            actions["lmct_game_order_every_1"] = 3
        }
        if (actions.isEmpty() && !componentContract) game.actions.forEach { actions[it] = -1 }
        val componentTasks = linkedMapOf<String, Set<String>>()
        var componentKey = ""
        var componentRaw = ""
        if (queryComponent) {
            val supplied = objects.firstOrNull {
                it.optJSONObject("externalGameCenterTaskComponentVO") != null ||
                    it.optJSONObject("ladderTaskComponentVO") != null
            }
            val data = if (supplied != null) {
                componentRaw = JSONObject().put("data", supplied).toString()
                supplied
            } else {
                val ack = GameCenterPlayRpcCall.queryNonDurationTasks(descriptor, actionChannel)
                if (!ack.accepted) {
                    Log.error("NonDurationGameTask", "游戏组件查询失败：${ack.raw} $detail")
                    return Contract(descriptor, actionChannel, actions, componentTasks, componentKey, ack.raw,
                        TaskFlowActionResult.failure(ack.failureType, raw = ack.raw,
                            message = "游戏组件查询失败", rpc = "queryGameComponent", detail = detail))
                }
                componentRaw = ack.raw
                ack.response?.optJSONObject("data")
            }
            componentKey = when (componentScene) {
                "EXTERNAL_GAME_CENTER_TASK" -> "externalGameCenterTaskComponentVO"
                "ladder_task" -> "ladderTaskComponentVO"
                else -> if (data?.has("externalGameCenterTaskComponentVO") == true)
                    "externalGameCenterTaskComponentVO" else "ladderTaskComponentVO"
            }
            val tasks = data?.optJSONObject(componentKey)?.optJSONArray("mainTaskList")
            val requestedTaskIds = objects.map { it.optString("taskId") }.filter(String::isNotBlank).toSet()
            val matching = (0 until (tasks?.length() ?: 0)).mapNotNull { tasks?.optJSONObject(it) }
                .filter { it.optString("appId") == game.appId }
                .mapNotNull { task -> GameCenterPlayRpcCall.nonDurationAction(task, game.appId)?.let { task to it } }
                .filter { (_, action) -> actions.isEmpty() ||
                    componentContract && action.first in actions && action.first !in countedActions }
            val grouped = matching.groupBy { it.second.first }
            for ((action, entries) in grouped) {
                val selected = entries.filter { it.first.optString("taskId") in requestedTaskIds }
                    .ifEmpty { entries }
                val remaining = selected.maxOf { it.second.second }
                // 普通组件中的已完成阶梯不能耗尽原场景的独立权益。
                if (!componentContract && remaining == 0) continue
                actions[action] = remaining
                componentTasks[action] = selected.map { it.first.optString("taskId") }.toSet()
            }
        }
        if (actions.isEmpty()) {
            game.actions.forEach { actions[it] = -1 }
        }
        if (actions.isEmpty()) {
            val missingDetail = "$detail appId=${game.appId} channel=$actionChannel taskIds=" +
                objects.map { it.optString("taskId").ifBlank { it.optString("taskType") } }
                    .filter { it.isNotBlank() }.distinct().joinToString()
            Log.error("NonDurationGameTask", "游戏任务缺少明确动作协议，尚未发起厂商上报：$missingDetail raw=$componentRaw")
            return Contract(descriptor, actionChannel, actions, componentTasks, componentKey, componentRaw,
                TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = "GAME_ACTION_CONTRACT_MISSING",
                message = "缺少任务动作、游戏组件动作或该任务的固定动作映射，尚未发起厂商上报",
                rpc = "queryGameComponent", raw = componentRaw, detail = missingDetail))
        }
        return Contract(descriptor, actionChannel, actions, componentTasks, componentKey, componentRaw)
    }

    suspend fun advance(
        game: GameTask,
        vararg roots: JSONObject?,
        channel: String = "",
        detail: String = "",
        logger: (String) -> Unit,
        confirmProgress: (suspend () -> Boolean?)? = null,
    ): TaskFlowActionResult {
        val session = AccountSessionCoordinator.currentSession()
        if (ApplicationHookConstants.isOffline() || session == null) {
            return TaskFlowActionResult.failure(
                TaskRpcFailureType.BUSINESS_LIMIT, message = "当前没有在线账号会话，停止游戏上报",
                rpc = "GameTask.reportDetailed", detail = detail, stopCurrentRound = true,
            )
        }
        val contract = resolve(game, *roots, channel = channel, detail = detail)
        contract.failure?.let { return it.copy(submissionStarted = false) }
        val descriptor = contract.descriptor
        val actionChannel = contract.channel
        val actions = contract.actions
        val componentTasks = contract.componentTasks
        val componentKey = contract.componentKey
        if (!AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch) || ApplicationHookConstants.isOffline()) {
            return TaskFlowActionResult.failure(TaskRpcFailureType.BUSINESS_LIMIT,
                message = "账号会话已变化或已离线", detail = detail, stopCurrentRound = true)
        }
        if (actions.values.all { it == 0 }) {
            return TaskFlowActionResult.defer(DeferredReason.PREREQUISITE_PENDING,
                message = "对应游戏组件已无剩余动作，刷新原场景任务与奖励；本轮未发起上报",
                rpc = "queryGameComponent", raw = contract.raw, detail = detail, refreshAfterAction = true)
                .copy(submissionStarted = false)
        }
        var businessConfirmed = false
        var contractProgressed = false
        var remainingContract = false
        var reportsAccepted = 0
        var reportsAttempted = 0
        for ((action, count) in actions) {
            if (count == 0) continue
            var actionRemaining = count
            val report = game.reportDetailed(
                1, actionFinishChannel = actionChannel, logger = logger,
                actionCode = action, reportCount = count.takeIf { it >= 0 },
                confirmProgress = {
                    if (ApplicationHookConstants.isOffline() ||
                        !AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch)) null
                    else {
                        val confirmed = confirmProgress?.invoke()
                        if (confirmed == true) businessConfirmed = true
                        val actionComplete = if (action in componentTasks && confirmed != true) {
                            val refreshed = GameCenterPlayRpcCall.queryNonDurationTasks(descriptor, actionChannel)
                            val tasks = refreshed.response?.optJSONObject("data")
                                ?.optJSONObject(componentKey)?.optJSONArray("mainTaskList")
                            if (!refreshed.accepted || tasks == null) null
                            else {
                                val matching = (0 until tasks.length()).mapNotNull { tasks.optJSONObject(it) }
                                    .filter { it.optString("appId") == game.appId &&
                                        it.optString("taskId") in componentTasks.getValue(action) }
                                    .mapNotNull { GameCenterPlayRpcCall.nonDurationAction(it, game.appId) }
                                    .filter { it.first == action }
                                if (matching.isEmpty()) null else {
                                    val remaining = matching.maxOf { it.second }
                                    contractProgressed = contractProgressed || remaining < count
                                    actionRemaining = remaining
                                    remaining == 0
                                }
                            }
                        } else false
                        if (ApplicationHookConstants.isOffline() ||
                            !AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch)) null
                        else if (confirmed == true) true
                        else if (confirmProgress != null && confirmed == null || action in componentTasks && actionComplete == null) null
                        else actionComplete == true
                    }
                },
            )
            reportsAccepted += report.successfulReports
            reportsAttempted += report.attemptedReports
            val actionDetail = "$detail appId=${game.appId} channel=$actionChannel action=$action reports=${report.successfulReports}/${report.requiredSuccesses}"
            if (!report.accepted) {
                Log.error("NonDurationGameTask", "${report.failureMessage} code=${report.failureCode} raw=${report.failureRaw} $actionDetail")
                if (report.resultUnknown) {
                    return TaskFlowActionResult.defer(
                        DeferredReason.STATE_CONFIRMATION, message = report.failureMessage,
                        rpc = "GameTask.reportDetailed", detail = actionDetail,
                        refreshAfterAction = true, stopCurrentRound = report.stopCurrentRound,
                        raw = report.failureRaw,
                    ).copy(submissionStarted = reportsAttempted > 0)
                }
                val response = runCatching { JSONObject(report.failureRaw) }.getOrNull()
                return TaskFlowActionResult.failure(
                    failureType = if (response?.let(RpcOfflineRisk::isOfflineRisk) == true ||
                        report.failureCode in setOf("401", "403")) TaskRpcFailureType.BUSINESS_LIMIT
                        else if (report.failureCode in setOf("10005", "isv.code-invalid")) TaskRpcFailureType.NON_RETRYABLE_INVALID
                        else GameCenterPlayRpcCall.classify(report.failureRaw),
                    code = report.failureCode, message = report.failureMessage, raw = report.failureRaw,
                    rpc = "GameTask.reportDetailed", detail = actionDetail,
                    stopCurrentRound = report.stopCurrentRound,
                    continueCurrentRoundOnFailure = !report.stopCurrentRound,
                ).copy(refreshAfterAction = reportsAttempted > 0 || reportsAccepted > 0, progressChanged = false,
                    submissionStarted = reportsAttempted > 0)
            }
            remainingContract = remainingContract || action in componentTasks && actionRemaining > 0
            if (businessConfirmed) break
        }
        val needsAccumulation = !businessConfirmed && contractProgressed && remainingContract
        return TaskFlowActionResult.defer(
            if (needsAccumulation) DeferredReason.NO_PROGRESS_COOLDOWN else DeferredReason.STATE_CONFIRMATION,
            message = if (businessConfirmed) "游戏业务状态已推进，继续回查并领奖"
                else if (needsAccumulation) "阶梯进度已增加，续接合同内剩余动作"
                else "本批合同动作已执行，等待原业务状态确认，不重复上报",
            rpc = "GameTask.reportDetailed", detail = "$detail appId=${game.appId} reports=$reportsAccepted",
            refreshAfterAction = true, progressChanged = businessConfirmed,
            deferredUntil = if (needsAccumulation) System.currentTimeMillis() else null,
        ).copy(submissionStarted = reportsAttempted > 0)
    }
}

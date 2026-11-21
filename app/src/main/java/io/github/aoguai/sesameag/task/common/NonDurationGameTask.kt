package io.github.aoguai.sesameag.task.common

import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.MiniGameVendorReporter
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
        val descriptor = GameCenterPlayRpcCall.describeTask(*roots).copy(gameAppId = game.appId)
        val actionChannel = descriptor.urlParameters["channel"].orEmpty()
            .ifBlank { channel.ifBlank { game.channel } }
        val objects = descriptor.objects.toMutableList()
        val originalMap = descriptor.urlParameters["originalMap"]?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        originalMap?.let { objects += it }
        objects += JSONObject(descriptor.urlParameters)
        val componentScene = descriptor.componentScene
        val supplied = objects.firstOrNull {
            it.optJSONObject("externalGameCenterTaskComponentVO") != null ||
                it.optJSONObject("ladderTaskComponentVO") != null
        }
        val componentContract = supplied != null || componentScene in setOf("ladder_task", "EXTERNAL_GAME_CENTER_TASK")
        val actions = linkedMapOf<String, Int>()
        for (task in objects) {
            val (action, count) = GameCenterPlayRpcCall.nonDurationAction(task, game.appId) ?: continue
            actions[action] = maxOf(actions[action] ?: -1, count)
        }
        val countedActions = objects.filter { it.optString("taskId").contains("__") }
            .mapNotNull { GameCenterPlayRpcCall.nonDurationAction(it, game.appId)?.takeIf { action -> action.second >= 0 }?.first }
            .toSet()
        if (actions.isEmpty() && game.appId == "2060170000365923" && objects.any { task ->
                listOf(task.optString("taskId"), task.optString("taskType"), task.optString("iepTaskId"))
                    .contains("LMCT_QDRW_LEYUAN")
            }) {
            actions["lmct_game_order_every_1"] = -1
        }
        val componentTasks = linkedMapOf<String, Set<String>>()
        var componentRaw = ""
        val data = if (supplied != null) {
            componentRaw = JSONObject().put("data", supplied).toString()
            supplied
        } else {
            val ack = GameCenterPlayRpcCall.queryNonDurationTasks(descriptor, actionChannel)
            if (!ack.accepted) {
                Log.error("NonDurationGameTask", "游戏组件查询失败：${ack.raw} $detail")
                return Contract(descriptor, actionChannel, actions, componentTasks, "", ack.raw,
                    TaskFlowActionResult.failure(ack.failureType, raw = ack.raw,
                        message = "游戏组件查询失败", rpc = "queryGameComponent", detail = detail))
            }
            componentRaw = ack.raw
            ack.response?.optJSONObject("data")
        }
        val componentKey = when (componentScene) {
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
            .filter { (task, action) -> actions.isEmpty() || action.first in actions &&
                (task.optString("taskId") in requestedTaskIds || componentContract && action.first !in countedActions) }
        val grouped = matching.groupBy { it.second.first }
        for ((action, entries) in grouped) {
            val selected = entries.filter { it.first.optString("taskId") in requestedTaskIds }
                .ifEmpty { entries }
            if (componentContract || selected.any { it.first.optString("taskId") in requestedTaskIds }) {
                actions[action] = if (selected.any { it.second.second < 0 }) -1 else selected.maxOf { it.second.second }
                componentTasks[action] = selected.map { it.first.optString("taskId") }.toSet()
            } else if (action !in actions) {
                // 其他场景的组件只能补充动作码，不能借用其进度或已完成状态。
                actions[action] = -1
            }
        }
        if (actions.isEmpty()) {
            val channelAction = game.protocolParameters["action.$actionChannel"].orEmpty()
            if (channelAction.isNotBlank()) {
                actions[channelAction] = -1
            } else {
                game.actions.forEach { actions[it] = -1 }
            }
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
        val initialTasks = runCatching {
            JSONObject(contract.raw).optJSONObject("data")?.optJSONObject(componentKey)?.optJSONArray("mainTaskList")
        }.getOrNull()
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
                actionFinishChannel = actionChannel, logger = logger,
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
                                    .mapNotNull { task -> GameCenterPlayRpcCall.nonDurationAction(task, game.appId)?.let { task to it } }
                                    .filter { it.second.first == action }
                                if (matching.isEmpty()) null else {
                                    val remaining = if (matching.any { it.second.second < 0 }) -1 else matching.maxOf { it.second.second }
                                    val currentProgressIncreased = matching.any { (task, _) ->
                                        val previous = (0 until (initialTasks?.length() ?: 0)).mapNotNull { initialTasks?.optJSONObject(it) }
                                            .firstOrNull { it.optString("appId") == game.appId &&
                                                it.optString("taskId") == task.optString("taskId") }
                                        val before = previous?.optJSONObject("taskProgressVO")?.optInt("currentProgress", -1) ?: -1
                                        val after = task.optJSONObject("taskProgressVO")?.optInt("currentProgress", -1) ?: -1
                                        before >= 0 && after > before
                                    }
                                    contractProgressed = contractProgressed || currentProgressIncreased ||
                                        remaining == 0 || count >= 0 && remaining >= 0 && remaining < count
                                    actionRemaining = remaining
                                    if (count >= 0 && remaining < 0) null else remaining == 0
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
            val actionDetail = "$detail appId=${game.appId} channel=$actionChannel action=$action remaining=${count.takeIf { it >= 0 }?.toString() ?: "unknown"} reports=${report.successfulReports}/${report.reportLimit}"
            if (!report.accepted) {
                if (report.failureCode == MiniGameVendorReporter.ROLE_CONTEXT_MISSING && reportsAttempted == 0) {
                    logger("游戏任务[本轮跳过] ${report.failureMessage} $actionDetail")
                    return TaskFlowActionResult.success(progressChanged = false).copy(submissionStarted = false)
                }
                Log.error("NonDurationGameTask", "${report.failureMessage} code=${report.failureCode} raw=${report.failureRaw} $actionDetail")
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
            remainingContract = remainingContract || action in componentTasks && actionRemaining != 0
            if (businessConfirmed || game.sdk == "tuyoo" || game.sdk == "yxmys") break
        }
        val needsAccumulation = !businessConfirmed && contractProgressed && remainingContract
        if (businessConfirmed) return TaskFlowActionResult.success(refreshAfterAction = true)
            .copy(submissionStarted = reportsAttempted > 0, rpc = "GameTask.reportDetailed",
                detail = "$detail appId=${game.appId} reports=$reportsAccepted")
        return TaskFlowActionResult.defer(
            DeferredReason.STATE_CONFIRMATION,
            message = if (needsAccumulation) "游戏进度已增加，刷新任务后继续"
                else if (contractProgressed) "游戏组件动作已完成，回查所属任务及奖励"
                else "本批动作已受理，本轮回查原业务状态",
            rpc = "GameTask.reportDetailed", detail = "$detail appId=${game.appId} reports=$reportsAccepted",
            refreshAfterAction = true, progressChanged = contractProgressed,
        ).copy(submissionStarted = reportsAttempted > 0)
    }
}

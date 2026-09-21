package io.github.aoguai.sesameag.task.antForestPatrol

import android.net.Uri
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal object MonopolyPatrolWorkflow {
    private const val TAG = "AntForestPatrol"

    fun run(tasksEnabled: Boolean) {
        var state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
        if (!ResChecker.checkRes(TAG, "查询新巡护入口失败:", state)) return
        val region = state.optJSONObject("regionInfo")
        val map = state.optJSONObject("mapInfo")
        if (region == null || map == null || state.optJSONObject("userInfo") == null) {
            Log.error(TAG, "新巡护入口缺少区域、地图或用户状态:$state")
            return
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            isLenient = false
        }
        for (activity in listOf(region, map)) {
            val start = activity.optString("startDate").takeIf { it.isNotBlank() }?.let { dateFormat.parse(it)?.time }
            val end = activity.optString("endDate").takeIf { it.isNotBlank() }?.let { dateFormat.parse(it)?.time }
            val now = System.currentTimeMillis()
            if ((start != null && now < start) || (end != null && now >= end)) {
                Log.forestPatrol("新巡护当前区域或地图不在开放期，停止本轮")
                return
            }
        }
        val taskState = TaskFlowExecutionState()
        var guideRoll = state.optJSONObject("userInfo")?.optBoolean("firstEnterMonopoly", false) == true
        var pendingEvent = state.optJSONObject("eventInfo")
        if (tasksEnabled) runTasks(region.optString("regionCode"), taskState)
        if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
        val props = parsePatrolResponse(AntForestPatrolRpcCall.triggerMonopolyHomeProps())
        if (!ResChecker.checkRes(TAG, "新巡护首页道具处理失败:", props)) return
        state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
        if (!ResChecker.checkRes(TAG, "刷新新巡护状态失败:", state)) return
        pendingEvent = pendingEvent ?: state.optJSONObject("eventInfo")
        val confirmedEvents = mutableSetOf<String>()
        var tasksMayHaveChanged = false
        while (!ApplicationHookConstants.isOffline() && !Thread.currentThread().isInterrupted) {
            val event = pendingEvent
            if (event?.optBoolean("needConfirm", false) == true) {
                val eventId = event.optString("eventId")
                val action = when (event.optString("eventType")) {
                    "CHARITY" -> "confirm"
                    "SPECIAL" -> "skip"
                    else -> null
                }
                if (eventId.isBlank() || action == null ||
                    event.optJSONObject("displayInfo")?.optString("flowType") != "SINGLE_ACTION_CONFIRM") {
                    Log.error(TAG, "新巡护事件缺少可执行决策，保留当前事件:$event")
                    return
                }
                if (eventId in confirmedEvents) {
                    Log.forestPatrol("新巡护事件[$eventId]已提交确认，等待后续调度刷新")
                    return
                }
                val confirmation = parsePatrolResponse(AntForestPatrolRpcCall.confirmMonopolyEvent(eventId, action))
                if (!ResChecker.checkRes(TAG, "新巡护事件确认失败:", confirmation)) return
                confirmedEvents.add(eventId)
                Log.forestPatrol("新巡护事件完成[$eventId] 奖励=${confirmation.optJSONArray("eventResult") ?: JSONArray()}")
                state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
                if (!ResChecker.checkRes(TAG, "刷新新巡护事件状态失败:", state)) return
                pendingEvent = state.optJSONObject("eventInfo")
                tasksMayHaveChanged = true
                continue
            }
            val diceCount = state.optInt("totalDiceCount", -1)
            if (diceCount < 0) {
                Log.error(TAG, "新巡护缺少可用骰子数量:$state")
                return
            }
            if (diceCount == 0) {
                if (tasksEnabled && tasksMayHaveChanged) {
                    runTasks(state.optJSONObject("regionInfo")?.optString("regionCode").orEmpty(), taskState)
                    tasksMayHaveChanged = false
                    if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
                    state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
                    if (!ResChecker.checkRes(TAG, "刷新新巡护任务奖励失败:", state)) return
                    pendingEvent = state.optJSONObject("eventInfo")
                    continue
                }
                Log.forestPatrol("新巡护当前无可用骰子，后续调度继续查询")
                return
            }
            val previousSteps = state.optJSONObject("userInfo")?.optInt("stepCount", -1) ?: -1
            val roll = parsePatrolResponse(AntForestPatrolRpcCall.rollMonopolyDice(guideRoll))
            if (!ResChecker.checkRes(TAG, "新巡护掷骰失败:", roll)) return
            guideRoll = false
            pendingEvent = roll.optJSONObject("eventInfo")
            val steps = roll.optJSONObject("userInfo")?.optInt("stepCount", -1) ?: -1
            if (roll.optInt("totalDiceCount", -1) == diceCount && steps == previousSteps && pendingEvent == null) {
                Log.error(TAG, "新巡护掷骰后未确认状态变化，保留后续调度:$roll")
                return
            }
            tasksMayHaveChanged = true
            Log.forestPatrol("新巡护掷骰🎲[${roll.optInt("diceNumber")}] 剩余${roll.optInt("totalDiceCount")}次")
            GlobalThreadPools.sleepCompat(500L)
            state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
            if (!ResChecker.checkRes(TAG, "刷新新巡护掷骰状态失败:", state)) return
            pendingEvent = pendingEvent ?: state.optJSONObject("eventInfo")
        }
    }

    private fun runTasks(regionCode: String, executionState: TaskFlowExecutionState) {
        val sceneCode = when (regionCode) {
            "hongshandongwuyuan" -> "ANTFOREST_MONOPOLY_TASK_HSDWY"
            else -> {
                Log.forestPatrol("当前区域[$regionCode]没有任务场景，继续地图巡护")
                return
            }
        }
        try {
            TaskFlowEngine(MonopolyTaskAdapter(regionCode, sceneCode), executionState = executionState).run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "新版巡护任务失败，保留地图机会处理", e)
        }
    }

    fun exchangeCertificate() {
        val entry = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
        if (!ResChecker.checkRes(TAG, "查询巡护证书入口失败:", entry)) return
        val redirect = entry.optJSONObject("displayInfo")?.optJSONObject("mapDisplay")
            ?.optJSONObject("protectionGuideDisplay")?.optString("redirectUrl").orEmpty()
        if (redirect.isBlank()) {
            Log.forestPatrol("当前地图没有证书项目")
            return
        }
        val uri = Uri.parse(redirect)
        val projectId = uri.getQueryParameter("projectId") ?: uri.getQueryParameter("url")?.let {
            Uri.parse(it).getQueryParameter("projectId")
        }
        if (projectId.isNullOrBlank()) {
            Log.error(TAG, "当前地图证书链接缺少 projectId:$redirect")
            return
        }
        val before = parsePatrolResponse(AntForestPatrolRpcCall.queryCertificate(projectId))
        if (!ResChecker.checkRes(TAG, "查询巡护证书资格失败:", before)) return
        val project = before.optJSONObject("exchangeableTree") ?: run {
            Log.error(TAG, "证书查询缺少 exchangeableTree:$before")
            return
        }
        if (before.optString("applyAction") != "AVAILABLE" || project.optInt("certCount", 0) > 0) {
            Log.forestPatrol("当前证书不可兑换或已领取[${before.optString("applyAction")}]")
            return
        }
        val cost = project.optLong("energy", -1L)
        val balance = before.optLong("currentEnergy", -1L)
        if (cost < 0 || balance < 0) {
            Log.error(TAG, "证书查询缺少实时成本或余额:$before")
            return
        }
        if (balance < cost || project.optBoolean("overLimit") || !project.optBoolean("hasBudget", true)) {
            Log.forestPatrol("当前证书资源或额度不足，成本${cost}g，余额${balance}g")
            return
        }
        if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
        try {
            val exchanged = parsePatrolResponse(AntForestPatrolRpcCall.exchangeCertificate(project.getLong("projectId")))
            if (ResChecker.checkRes(TAG, "兑换巡护证书失败:", exchanged)) {
                Log.forestPatrol("保护证书兑换请求成功[${project.optString("projectName")}]，成本${cost}g")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "巡护证书兑换结果不确定，先回查", e)
        }
        if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
        val after = parsePatrolResponse(AntForestPatrolRpcCall.queryCertificate(projectId))
        if (ResChecker.checkRes(TAG, "回查巡护证书失败:", after)) {
            if ((after.optJSONObject("exchangeableTree")?.optInt("certCount", 0) ?: 0) > project.optInt("certCount", 0)) {
                Log.forestPatrol("已确认获得当前地图保护证书")
            } else {
                Log.forestPatrol("证书回查状态[${after.optString("applyAction")}]，本轮不再兑换")
            }
        }
        val refreshed = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
        if (ResChecker.checkRes(TAG, "回查巡护地图失败:", refreshed)) {
            Log.forestPatrol("当前巡护地图[${refreshed.optJSONObject("userInfo")?.optString("currentMapCode")}]")
        }
    }

    private class MonopolyTaskAdapter(
        private val regionCode: String,
        private val sceneCode: String,
    ) : TaskFlowAdapter {
        override val moduleName: String = "AntForestPatrol"
        override val flowName: String = "新版巡护任务"

        override fun query(): JSONObject = parsePatrolResponse(AntForestPatrolRpcCall.listMonopolyTasks(regionCode, sceneCode))
        override fun isQuerySuccess(response: JSONObject): Boolean =
            response.optBoolean("success") && response.optJSONArray("taskInfoList") != null

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val tasks = response.getJSONArray("taskInfoList")
            return (0 until tasks.length()).map { index ->
                val task = tasks.getJSONObject(index)
                val base = task.getJSONObject("taskBaseInfo")
                val rights = task.getJSONObject("taskRights")
                val type = base.getString("taskType")
                val taskScene = base.getString("sceneCode")
                require(type.isNotBlank() && taskScene == sceneCode) { "巡护任务场景或标识无效:$task" }
                val bizInfo = base.optString("bizInfo").takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                TaskFlowItem(
                    id = type,
                    title = bizInfo?.optString("title")?.takeIf { it.isNotBlank() } ?: type,
                    status = base.getString("taskStatus"),
                    type = type,
                    sceneCode = taskScene,
                    actionType = base.optString("taskProdPlayType"),
                    raw = task,
                    current = rights.optInt("rightsTimes"),
                    limit = rights.optInt("rightsTimesLimit"),
                    progress = "rights=${rights.optInt("rightsTimes")}/${rights.optInt("rightsTimesLimit")} " +
                        "received=${rights.optInt("alreadyReceiveAwardCount")} " +
                        "task=${base.optInt("taskProgress")}/${base.optInt("taskRequire")}",
                )
            }
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase = when (item.status) {
            "FINISHED" -> TaskFlowPhase.REWARD_READY
            "RECEIVED" -> TaskFlowPhase.TERMINAL
            "TODO" -> if (item.raw?.optJSONObject("taskBaseInfo")?.optString("taskMode") == "NORMAL" &&
                item.actionType == "VISIT_FLOAT_BALL") TaskFlowPhase.READY_TO_COMPLETE else TaskFlowPhase.BUSINESS_ACTION
            else -> TaskFlowPhase.UNKNOWN
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val base = requireNotNull(item.raw).getJSONObject("taskBaseInfo")
            val seconds = JSONObject(base.getString("prodPlayParam")).getLong("timeCount")
            require(seconds > 0 && seconds <= Long.MAX_VALUE / 1000) { "巡护浏览时长无效:$base" }
            GlobalThreadPools.sleepCompat(seconds * 1000)
            if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) {
                return TaskFlowActionResult.failure(TaskRpcFailureType.RETRYABLE_RPC, message = "巡护已中断", stopCurrentRound = true)
            }
            return actionResult(AntForestPatrolRpcCall.finishMonopolyTask(item.type, item.sceneCode), "finishTaskopengreen")
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult =
            actionResult(AntForestPatrolRpcCall.receiveMonopolyTask(item.type, item.sceneCode), "receiveTaskAwardopengreen")

        private fun actionResult(raw: String, rpc: String): TaskFlowActionResult {
            val response = parsePatrolResponse(raw)
            if (response.optBoolean("success")) return TaskFlowActionResult.success()
            val failure = when {
                response.optBoolean("retriable") || response.optBoolean("canRetry") -> TaskRpcFailureType.RETRYABLE_RPC
                response.opt("retriable") == false || response.opt("canRetry") == false -> TaskRpcFailureType.NON_RETRYABLE_INVALID
                else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            }
            return TaskFlowActionResult.failure(
                failureType = failure,
                code = response.optString("resultCode", response.optString("errorCode")),
                message = response.optString("resultDesc", response.optString("errorMsg")),
                rpc = rpc,
                raw = response.toString(),
                continueCurrentRoundOnFailure = true,
            ).copy(refreshAfterAction = true, progressChanged = false)
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String =
            "${action.logName}:${item.sceneCode}:${item.id}:${item.status}:${item.progress}"
        override fun onQueryFailed(response: JSONObject) = Log.error(TAG, "巡护任务查询失败或缺少任务列表:$response")
        override fun logInfo(message: String) = Log.forestPatrol(message)
        override fun logError(message: String) = Log.error(TAG, message)
    }
}

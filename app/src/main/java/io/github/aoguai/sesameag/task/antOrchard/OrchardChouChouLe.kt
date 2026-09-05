package io.github.aoguai.sesameag.task.antOrchard

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import org.json.JSONObject

private const val TAG = "OrchardChouChouLe"

internal fun AntOrchard.runOrchardChouChouLe(userId: String) {
    try {
        require(userId.isNotBlank()) { "农场抽抽乐缺少 userId" }
        if (ApplicationHookConstants.isOffline()) return

        val entry = JSONObject(AntOrchardRpcCall.enterDrawActivity())
        if (!ResChecker.checkRes(TAG, entry)) {
            Log.error(TAG, "农场抽抽乐活动查询失败 raw=$entry")
            return
        }
        val activityId = entry.getJSONObject("drawActivity").getString("activityId")
        require(activityId.isNotBlank()) { "农场抽抽乐缺少 activityId" }
        require(entry.getJSONObject("drawAsset").getInt("blance") >= 0) { "农场抽抽乐余额无效" }

        val taskResult = TaskFlowEngine(
            OrchardDrawTaskFlowAdapter(this),
            roundSleepMs = executeIntervalInt.toLong(),
        ).run()
        if (taskResult.interrupted || ApplicationHookConstants.isOffline()) return

        val synced = JSONObject(AntOrchardRpcCall.syncDrawBalance(activityId))
        if (!ResChecker.checkRes(TAG, synced)) {
            Log.error(TAG, "农场抽抽乐余额同步失败 raw=$synced")
            return
        }
        var balance = synced.getJSONObject("drawAsset").getInt("blance")
        require(balance >= 0) { "农场抽抽乐同步余额无效" }
        while (balance > 0 && !ApplicationHookConstants.isOffline()) {
            val response = JSONObject(AntOrchardRpcCall.batchDraw(activityId, balance, userId))
            if (!ResChecker.checkRes(TAG, response)) {
                Log.error(TAG, "农场抽抽乐抽奖失败 activityId=$activityId times=$balance raw=$response")
                return
            }
            val prizes = response.optJSONArray("drawResultList")
            if (prizes != null) {
                for (index in 0 until prizes.length()) {
                    val prize = prizes.optJSONObject(index)?.optJSONObject("prizeVO") ?: continue
                    Log.orchard("农场抽抽乐[${prize.optString("prizeName")}] × ${prize.optInt("prizeNum", 1)}")
                }
            }
            balance = response.getJSONObject("drawAsset").getInt("blance")
            require(balance >= 0) { "农场抽抽乐抽奖后余额无效" }
            Log.orchard("农场抽抽乐剩余次数: $balance")
            if (balance > 0) CoroutineUtils.sleepCompat(executeIntervalInt.toLong())
        }
    } catch (t: Throwable) {
        Log.printStackTrace(TAG, "农场抽抽乐处理异常:", t)
    }
}

private class OrchardDrawTaskFlowAdapter(private val orchard: AntOrchard) : TaskFlowAdapter {
    override val moduleName: String = "芭芭农场"
    override val flowName: String = "农场抽抽乐任务"
    override val continueCurrentRoundOnRetryableFailure: Boolean = true

    override fun query(): JSONObject = JSONObject(AntOrchardRpcCall.listDrawTasks())

    override fun isQuerySuccess(response: JSONObject): Boolean =
        orchard.isOrchardRpcSuccessResponse(response) && response.optJSONArray("taskInfoList") != null

    override fun extractItems(response: JSONObject): List<TaskFlowItem> {
        val tasks = response.getJSONArray("taskInfoList")
        val items = mutableListOf<TaskFlowItem>()
        for (index in 0 until tasks.length()) {
            val task = tasks.getJSONObject(index)
            val base = task.getJSONObject("taskBaseInfo")
            val taskType = base.getString("taskType")
            val sceneCode = base.getString("sceneCode")
            require(taskType.isNotBlank() && sceneCode.isNotBlank()) { "农场抽抽乐任务缺少 sceneCode/taskType: $task" }
            val bizInfo = base.optString("bizInfo").takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            val rights = task.optJSONObject("taskRights")
            val taskProgress = base.optInt("taskProgress")
            val taskRequire = base.optInt("taskRequire")
            val rightsTimes = rights?.optInt("rightsTimes") ?: 0
            val rightsLimit = rights?.optInt("rightsTimesLimit") ?: 0
            items.add(
                TaskFlowItem(
                    id = taskType,
                    title = bizInfo?.optString("title")?.takeIf { it.isNotBlank() } ?: taskType,
                    status = base.getString("taskStatus"),
                    type = taskType,
                    sceneCode = sceneCode,
                    actionType = base.optString("taskProdPlayType"),
                    raw = task,
                    progress = "task=$taskProgress/$taskRequire rights=$rightsTimes/$rightsLimit " +
                        "received=${rights?.optInt("alreadyReceiveAwardCount") ?: 0}",
                    current = if (taskRequire > 0) taskProgress else rightsTimes,
                    limit = if (taskRequire > 0) taskRequire else rightsLimit,
                ),
            )
        }
        return items
    }

    override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
        when (item.status) {
            "FINISHED" -> TaskFlowPhase.REWARD_READY
            "TODO" -> TaskFlowPhase.READY_TO_COMPLETE
            "RECEIVED" -> TaskFlowPhase.TERMINAL
            else -> TaskFlowPhase.UNKNOWN
        }

    override fun complete(item: TaskFlowItem): TaskFlowActionResult {
        val base = requireNotNull(item.raw).getJSONObject("taskBaseInfo")
        if (item.actionType == "VISIT_FLOAT_BALL") {
            val playParam = JSONObject(base.getString("prodPlayParam"))
            val seconds = playParam.getLong("timeCount")
            require(seconds > 0 && seconds <= Long.MAX_VALUE / 1000) { "农场抽抽乐浏览时长无效: $playParam" }
            CoroutineUtils.sleepCompat(seconds * 1000)
        }
        val response = JSONObject(AntOrchardRpcCall.finishDrawTask(item.sceneCode, item.type))
        if (orchard.isOrchardRpcSuccessResponse(response)) {
            Log.orchard("农场抽抽乐任务完成请求已接受[${item.title}]")
            return TaskFlowActionResult.success()
        }
        return orchard.buildOrchardTaskFailureResult(
            response = response,
            taskId = item.id,
            title = item.title,
            action = "complete",
            rpc = "AntOrchardRpcCall.finishDrawTask",
            item = item,
        )
    }

    override fun receive(item: TaskFlowItem): TaskFlowActionResult {
        val response = JSONObject(AntOrchardRpcCall.receiveDrawTaskAward(item.sceneCode, item.type))
        if (orchard.isOrchardRpcSuccessResponse(response)) {
            val count = response.optInt("incAwardCount")
            Log.orchard("农场抽抽乐领取任务奖励[${item.title}] 增加${count}次机会")
            return TaskFlowActionResult.success()
        }
        return orchard.buildOrchardTaskFailureResult(
            response = response,
            taskId = item.id,
            title = item.title,
            action = "receive",
            rpc = "AntOrchardRpcCall.receiveDrawTaskAward",
            item = item,
        )
    }

    override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String =
        "${action.logName}:${item.sceneCode}:${item.id}:${item.status}:${item.progress}"

    override fun onQueryFailed(response: JSONObject) {
        Log.error(TAG, "农场抽抽乐任务查询失败或缺少 taskInfoList raw=$response")
    }

    override fun logInfo(message: String) = Log.orchard(message)

    override fun logError(message: String) = Log.error(TAG, message)
}

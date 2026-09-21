package io.github.aoguai.sesameag.task.antOrchard

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.UserDataStore
import io.github.aoguai.sesameag.util.UserDataStoreManager
import org.json.JSONObject

private const val TAG = "OrchardChouChouLe"
private const val ACTIVITY_STATE_KEY = "orchard_draw_activity"

internal fun AntOrchard.runOrchardChouChouLe(userId: String) {
    try {
        require(userId.isNotBlank()) { "农场抽抽乐缺少 userId" }
        if (ApplicationHookConstants.isOffline()) return

        val dataStore = UserDataStoreManager.getInstance(userId) ?: return
        val activityState = dataStore.get(ACTIVITY_STATE_KEY, String::class.java)?.let(::JSONObject) ?: JSONObject()
        val adapter = OrchardDrawTaskFlowAdapter(this, dataStore, activityState)
        if (!adapter.isActivityActive()) return
        val entry = JSONObject(AntOrchardRpcCall.enterDrawActivity())
        adapter.recordActivityResponse(entry)
        if (!ResChecker.checkRes(TAG, entry)) {
            Log.error(TAG, "农场抽抽乐活动查询失败 raw=$entry")
            return
        }
        val activity = entry.getJSONObject("drawActivity")
        val activityId = activity.getString("activityId")
        require(activityId.isNotBlank()) { "农场抽抽乐缺少 activityId" }
        activityState.put("activityId", activityId)
            .put("startTime", activity.optLong("startTime"))
            .put("endTime", activity.optLong("endTime"))
        dataStore.put(ACTIVITY_STATE_KEY, activityState.toString())
        if (!adapter.isActivityActive()) return
        require(entry.getJSONObject("drawAsset").getInt("blance") >= 0) { "农场抽抽乐余额无效" }

        val taskResult = TaskFlowEngine(
            adapter,
            roundSleepMs = executeIntervalInt.toLong(),
        ).run()
        if (taskResult.interrupted || ApplicationHookConstants.isOffline() || !adapter.isActivityActive()) return

        val synced = JSONObject(AntOrchardRpcCall.syncDrawBalance(activityId))
        adapter.recordActivityResponse(synced)
        if (!ResChecker.checkRes(TAG, synced)) {
            Log.error(TAG, "农场抽抽乐余额同步失败 raw=$synced")
            return
        }
        var balance = synced.getJSONObject("drawAsset").getInt("blance")
        require(balance >= 0) { "农场抽抽乐同步余额无效" }
        while (balance > 0 && !ApplicationHookConstants.isOffline() && adapter.isActivityActive()) {
            val previousBalance = balance
            val response = JSONObject(AntOrchardRpcCall.batchDraw(activityId, balance, userId))
            adapter.recordActivityResponse(response)
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
            if (balance >= previousBalance) {
                Log.error(TAG, "农场抽抽乐余额未推进，保留后续调度回查 raw=$response")
                return
            }
            if (balance > 0) CoroutineUtils.sleepCompat(executeIntervalInt.toLong())
        }
    } catch (t: Throwable) {
        Log.printStackTrace(TAG, "农场抽抽乐处理异常:", t)
    }
}

private class OrchardDrawTaskFlowAdapter(
    private val orchard: AntOrchard,
    private val dataStore: UserDataStore,
    private val activityState: JSONObject,
) : TaskFlowAdapter {
    override val moduleName: String = "芭芭农场"
    override val flowName: String = "农场抽抽乐任务"
    override val continueCurrentRoundOnRetryableFailure: Boolean = true

    fun isActivityActive(): Boolean {
        if (activityState.optBoolean("closed")) return false
        val now = System.currentTimeMillis()
        val endTime = activityState.optLong("endTime")
        if (endTime > 0 && now >= endTime) {
            activityState.put("closed", true).put("reason", "活动已到结束时间")
            dataStore.put(ACTIVITY_STATE_KEY, activityState.toString())
            Log.orchard("农场抽抽乐活动已结束 activityId=${activityState.optString("activityId")} endTime=$endTime")
            return false
        }
        return activityState.optLong("startTime") <= now
    }

    fun recordActivityResponse(response: JSONObject) {
        if (response.optBoolean("activityExpire") ||
            (response.optString("code") == "2600000010" && !response.optBoolean("retriable", true))
        ) {
            response.optJSONObject("drawActivity")?.optString("activityId")?.takeIf { it.isNotBlank() }?.let {
                activityState.put("activityId", it)
            }
            activityState.put("closed", true)
                .put("code", response.optString("code"))
                .put("reason", response.optString("desc"))
            dataStore.put(ACTIVITY_STATE_KEY, activityState.toString())
            Log.error(TAG, "农场抽抽乐活动停止，保留关闭状态 activity=$activityState raw=$response")
        }
    }

    override fun isFlowHandledToday(): Boolean = !isActivityActive()

    override fun onFlowHandledToday() = Log.orchard("农场抽抽乐活动不可执行，停止当前任务流")

    override fun query(): JSONObject {
        check(isActivityActive()) { "农场抽抽乐活动不可执行" }
        return JSONObject(AntOrchardRpcCall.listDrawTasks()).also(::recordActivityResponse)
    }

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
        if (!isActivityActive()) return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.NON_RETRYABLE_INVALID,
            message = "农场抽抽乐活动已停止",
        )
        val response = JSONObject(AntOrchardRpcCall.finishDrawTask(item.sceneCode, item.type))
        recordActivityResponse(response)
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
        if (!isActivityActive()) return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.NON_RETRYABLE_INVALID,
            message = "农场抽抽乐活动已停止",
        )
        val response = JSONObject(AntOrchardRpcCall.receiveDrawTaskAward(item.sceneCode, item.type))
        recordActivityResponse(response)
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

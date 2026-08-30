package io.github.aoguai.sesameag.task.goldenBean

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TaskBlacklist
import org.json.JSONArray
import org.json.JSONObject

internal const val GOLDEN_BEAN_BLACKLIST_MODULE = "金豆夺宝"
internal const val MARKETING_POPUP_CLICKED = "MARKETING_POPUP_CLICKED"
internal const val GOLDEN_BEAN_GAME_CHANNEL = "goldenbean"
internal const val GOLDEN_BEAN_CONVERGENCE_LIMIT = 64

/**
 * Module-local orchestration for the Golden Bean Treasure domain. Task-list
 * transitions reuse TaskFlowEngine; mining and the game-centre draw use their
 * own server state because neither belongs to the task-list lifecycle.
 */
internal suspend fun GoldenBeanTreasure.runGoldenBeanTreasure() {
    if (Status.hasFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_TASKS_DONE)) {
        Log.goldenBean("金豆夺宝[今日已处理，跳过]")
        return
    }

    val indexResponse = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index())
    if (indexResponse == null || !GoldenBeanTreasureSupport.isSuccess(indexResponse)) {
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝首页查询失败 raw=${indexResponse ?: "EMPTY"}")
        return
    }

    val signSyncResponse = GoldenBeanTreasureSupport.handleDailySign(indexResponse)
    val marketingSource =
        signSyncResponse?.takeIf { it.optJSONObject("marketingPopupTask") != null } ?: indexResponse
    GoldenBeanTreasureSupport.handleMarketingPopup(marketingSource)
    GoldenBeanTreasureSupport.queryMallItems()

    val taskFlowAdapter = GoldenBeanTaskFlowAdapter()
    fun runTaskFlow() =
        TaskFlowEngine(
            taskFlowAdapter,
            roundSleepMs = executeIntervalInt.toLong().coerceAtLeast(500L),
        ).run()

    var convergenceRound = 1
    var taskResult = runTaskFlow()
    var gameResult = GoldenBeanTreasureSupport.runGameCenterOpportunityFlow()
    while (!gameResult.blocked &&
        (taskResult.progressed || gameResult.progressed) &&
        convergenceRound < GOLDEN_BEAN_CONVERGENCE_LIMIT
    ) {
        convergenceRound++
        taskResult = runTaskFlow()
        gameResult = GoldenBeanTreasureSupport.runGameCenterOpportunityFlow()
    }
    if (convergenceRound >= GOLDEN_BEAN_CONVERGENCE_LIMIT &&
        (taskResult.progressed || gameResult.progressed)
    ) {
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝达到收敛轮次上限$GOLDEN_BEAN_CONVERGENCE_LIMIT")
    }

    val hasUnresolvedTasks = taskFlowAdapter.hasUnresolvedTasks()
    if (taskResult.completed &&
        !taskResult.stopped &&
        gameResult.completed &&
        !hasUnresolvedTasks
    ) {
        Status.setFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_TASKS_DONE)
    } else if (!hasUnresolvedTasks) {
        Log.goldenBean("金豆夺宝任务未写入当日完成标记：任务流或金豆乐园状态尚未完成确认")
    } else {
        Log.goldenBean("金豆夺宝仍有非黑名单待完成或待领奖任务，不写入今日完成标记")
    }

    if (taskFlowAdapter.isMinerEntryReceived()) {
        GoldenBeanTreasureSupport.runMiner()
    }
}

private class GoldenBeanTaskFlowAdapter : TaskFlowAdapter {
    private val loggedDeferredTaskIds = mutableSetOf<String>()
    private var latestTaskResponse = JSONObject()
    private var nextQuerySyncTypes = TASK_STATUS_SYNC_TYPES

    override val moduleName: String = GOLDEN_BEAN_BLACKLIST_MODULE
    override val flowName: String = "金豆夺宝任务"
    override val continueCurrentRoundOnRetryableFailure: Boolean = true

    override fun query(): JSONObject {
        val syncTypes = nextQuerySyncTypes
        nextQuerySyncTypes = TASK_STATUS_SYNC_TYPES
        return GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.sync(syncTypes))
            ?: JSONObject().put("resultCode", "").put("resultDesc", "goldenbean.sync返回空")
    }

    override fun isQuerySuccess(response: JSONObject): Boolean = GoldenBeanTreasureSupport.isSuccess(response)

    override fun extractItems(response: JSONObject): List<TaskFlowItem> {
        latestTaskResponse = response
        val taskList = response.optJSONArray("taskList") ?: return emptyList()
        val items = mutableListOf<TaskFlowItem>()
        for (index in 0 until taskList.length()) {
            val task = taskList.optJSONObject(index) ?: continue
            val sceneCode = task.optString("sceneCode").trim()
            if (sceneCode != GoldenBeanRpcCall.TASK_SCENE_CODE) {
                if (sceneCode.isBlank()) {
                    Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝任务缺少sceneCode raw=$task")
                }
                continue
            }

            val taskId = task.optString("taskId").trim()
            val groupId = task.optString("groupId").trim()
            val stableId = taskId.ifBlank { groupId }.ifBlank { "UNKNOWN_$index" }
            val rightsTimesLimit = task.optInt("rightsTimesLimit", 0)
            val rightsTimes = task.optInt("rightsTimes", 0)

            items.add(
                TaskFlowItem(
                    id = stableId,
                    // Display copy is deliberately not used by the execution or blacklist path.
                    title = stableId,
                    status = task.optString("taskStatus").trim(),
                    type = taskId,
                    sceneCode = sceneCode,
                    actionType = task.optString("actionType").trim(),
                    blacklistKeys =
                        listOf(taskId, groupId)
                            .filter { it.isNotBlank() }
                            .map { stableBlacklistKey(sceneCode, it) },
                    raw = task,
                    progress = buildTaskProgress(task),
                    current = rightsTimes.takeIf { rightsTimesLimit > 0 },
                    limit = rightsTimesLimit.takeIf { it > 0 },
                ),
            )
        }
        return items
    }

    override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
        when (item.status.uppercase()) {
            "FINISHED", "TO_RECEIVE" -> TaskFlowPhase.REWARD_READY
            "RECEIVED", "DONE" -> TaskFlowPhase.TERMINAL
            "TODO" -> {
                if (hasCapturedFinishContract(item)) {
                    TaskFlowPhase.READY_TO_COMPLETE
                } else {
                    TaskFlowPhase.UNKNOWN
                }
            }

            else -> TaskFlowPhase.UNKNOWN
        }

    override fun isFlowHandledToday(): Boolean =
        Status.hasFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_TASKS_DONE)

    override fun shouldSkip(item: TaskFlowItem): Boolean {
        if (item.status.uppercase() != "TODO") {
            return false
        }
        if (hasCapturedFinishContract(item)) {
            return false
        }
        if (loggedDeferredTaskIds.add(item.id)) {
            Log.goldenBean(
                "金豆夺宝任务⏭️[taskId=${item.id} actionType=${item.actionType.ifBlank { "UNKNOWN" }} " +
                    "sceneCode=${item.sceneCode.ifBlank { "UNKNOWN" }}] 未捕获主动动作，仅保留服务端状态",
            )
        }
        return true
    }

    override fun isUnresolvedWhenSkipped(item: TaskFlowItem): Boolean =
        item.status.uppercase() !in setOf("RECEIVED", "DONE") && !isBlacklisted(item)

    override fun receive(item: TaskFlowItem): TaskFlowActionResult {
        if (item.type.isBlank()) {
            return GoldenBeanTreasureSupport.missingTaskTypeFailure(item, "receive")
        }
        val response = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.receiveTaskAward(item.type))
            ?: return GoldenBeanTreasureSupport.emptyResponseFailure(item, "receive")
        if (GoldenBeanTreasureSupport.isSuccess(response)) {
            nextQuerySyncTypes = RECEIVE_TASK_SYNC_TYPES
        }
        return GoldenBeanTreasureSupport.actionResult(item, response, "receive")
    }

    override fun complete(item: TaskFlowItem): TaskFlowActionResult {
        if (item.type.isBlank()) {
            return GoldenBeanTreasureSupport.missingTaskTypeFailure(item, "complete")
        }
        val response = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.finishTask(item.type))
            ?: return GoldenBeanTreasureSupport.emptyResponseFailure(item, "complete")
        if (GoldenBeanTreasureSupport.isSuccess(response)) {
            nextQuerySyncTypes = TASK_STATUS_SYNC_TYPES
        }
        return GoldenBeanTreasureSupport.actionResult(item, response, "complete")
    }

    override fun actionKey(
        item: TaskFlowItem,
        action: TaskFlowAction,
    ): String = "${action.logName}:${item.id}:${item.sceneCode}:${item.actionType}:${item.status}"

    override fun blacklist(
        item: TaskFlowItem,
        result: TaskFlowActionResult,
    ) {
        val taskId = item.type.ifBlank { item.id }
        if (taskId.isBlank() || taskId.startsWith("UNKNOWN_")) {
            return
        }
        val stableId = stableBlacklistKey(item.sceneCode, taskId)
        if (result.code.isNotBlank()) {
            TaskBlacklist.autoAddToBlacklist(moduleName, stableId, errorCode = result.code)
        }
        TaskBlacklist.addToBlacklist(moduleName, stableId)
    }

    override fun onUnknownPhase(
        item: TaskFlowItem,
        phase: TaskFlowPhase,
    ) {
        Log.error(
            GOLDEN_BEAN_BLACKLIST_MODULE,
            "金豆夺宝任务未知状态 taskId=${item.id} status=${item.status} " +
                "actionType=${item.actionType.ifBlank { "UNKNOWN" }} phase=$phase raw=${item.raw ?: "EMPTY"}",
        )
    }

    override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
        Log.goldenBean("金豆夺宝任务列表已无可自动推进或待领取任务")
    }

    override fun onQueryFailed(response: JSONObject) {
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝任务查询失败 raw=$response")
    }

    override fun logInfo(message: String) {
        Log.goldenBean(message)
    }

    override fun logError(message: String) {
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, message)
    }

    fun hasUnresolvedTasks(): Boolean {
        val taskList = latestTaskResponse.optJSONArray("taskList") ?: return false
        for (index in 0 until taskList.length()) {
            val task = taskList.optJSONObject(index) ?: continue
            if (task.optString("sceneCode") != GoldenBeanRpcCall.TASK_SCENE_CODE) {
                continue
            }
            if (task.optString("taskStatus").uppercase() !in setOf("RECEIVED", "DONE") &&
                !isTaskBlacklisted(task)
            ) {
                return true
            }
        }
        return false
    }

    fun isMinerEntryReceived(): Boolean {
        val taskList = latestTaskResponse.optJSONArray("taskList") ?: return false
        for (index in 0 until taskList.length()) {
            val task = taskList.optJSONObject(index) ?: continue
            if (task.optString("sceneCode") == GoldenBeanRpcCall.TASK_SCENE_CODE &&
                task.optString("taskId") == GoldenBeanRpcCall.WAKUANG_TASK_TYPE
            ) {
                return task.optString("taskStatus") in setOf("RECEIVED", "DONE")
            }
        }
        return false
    }

    private fun isTaskBlacklisted(task: JSONObject): Boolean {
        val sceneCode = task.optString("sceneCode")
        return listOf(task.optString("taskId"), task.optString("groupId"))
            .filter { it.isNotBlank() }
            .map { stableBlacklistKey(sceneCode, it) }
            .any { TaskBlacklist.isTaskInBlacklist(moduleName, it) }
    }

    private fun hasCapturedFinishContract(item: TaskFlowItem): Boolean {
        if (item.sceneCode != GoldenBeanRpcCall.TASK_SCENE_CODE || item.type.isBlank()) {
            return false
        }
        return item.actionType == GoldenBeanRpcCall.WAKUANG_ACTION_TYPE ||
            item.actionType == GoldenBeanRpcCall.JINDOULEYUAN_ACTION_TYPE
    }

    private fun buildTaskProgress(task: JSONObject): String {
        val parts = mutableListOf<String>()
        val rightsTimesLimit = task.optInt("rightsTimesLimit", 0)
        if (rightsTimesLimit > 0) {
            parts.add("rights=${task.optInt("rightsTimes", 0)}/$rightsTimesLimit")
        }
        val taskRequire = task.optInt("taskRequire", 0)
        if (taskRequire > 0) {
            parts.add("task=${task.optInt("taskProgress", 0)}/$taskRequire")
        }
        return parts.joinToString(" ")
    }

    private fun stableBlacklistKey(
        sceneCode: String,
        identifier: String,
    ): String = "$sceneCode:$identifier"

    private companion object {
        val TASK_STATUS_SYNC_TYPES = listOf("FARM_TASK", "TASK_LIST")
        val RECEIVE_TASK_SYNC_TYPES = listOf("JAR_INFO", "TASK_LIST")
    }
}

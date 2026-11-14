package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal fun AntFarm.runOrdinaryOrnamentTasks() {
    try {
        var pageNo = 1
        do {
            if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
            val response = JSONObject(AntFarmRpcCall.listOrnaments("NORMALSETS", pageNo))
            if (!ResChecker.checkRes("AntFarm", response)) return
            val ornaments = response.optJSONArray("ornaments") ?: return
            for (index in 0 until ornaments.length()) {
                if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted) return
                val ornament = ornaments.optJSONObject(index) ?: continue
                if (ornament.optBoolean("acquired") || ornament.optString("activityActionType") != "CHIP_TASK") continue
                val activityId = ornament.optString("activityId")
                val ornamentId = ornament.optString("id")
                if (activityId.isBlank() || ornamentId.isBlank()) continue
                TaskFlowEngine(
                    OrdinaryOrnamentTaskAdapter(
                        this, activityId, ornamentId, ornament.optString("name"), pageNo,
                        completeTasks = doFarmTask?.value == true,
                    ),
                ).run()
            }
            pageNo += 1
        } while (response.optBoolean("hasMore") && ornaments.length() > 0)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.printStackTrace("AntFarm", "普通套装任务失败", t)
    }
}

private class OrdinaryOrnamentTaskAdapter(
    private val farm: AntFarm,
    private val activityId: String,
    private val ornamentId: String,
    private val ornamentName: String,
    private val pageNo: Int,
    private val completeTasks: Boolean,
) : TaskFlowAdapter {
    override val moduleName = "蚂蚁庄园"
    override val flowName = "普通套装[$ornamentName]"
    override val stateScope = "antFarm:ornament:$activityId"

    private var detail: JSONObject? = null

    override fun query(): JSONObject =
        JSONObject(AntFarmRpcCall.ornamentActivityDetail(activityId, ornamentId)).also { detail = it }

    override fun isQuerySuccess(response: JSONObject): Boolean = ResChecker.checkRes("AntFarm", response)

    override fun extractItems(response: JSONObject): List<TaskFlowItem> {
        val tasks = response.optJSONArray("tasks") ?: return emptyList()
        return (0 until tasks.length()).mapNotNull { index ->
            val task = tasks.optJSONObject(index) ?: return@mapNotNull null
            val taskId = task.optString("taskId")
            if (taskId.isBlank()) return@mapNotNull null
            TaskFlowItem(
                id = taskId,
                title = task.optString("label"),
                status = task.optString("status"),
                type = task.optString("taskType"),
                current = task.optInt("progress"),
                limit = task.optInt("condition"),
                sceneCode = "ANTFARM",
                blacklistKeys = listOf(taskId, activityId, ornamentId),
                raw = task,
            )
        }
    }

    override fun mapPhase(item: TaskFlowItem): TaskFlowPhase = when {
        item.status == "FINISH" -> TaskFlowPhase.TERMINAL
        item.status == "READY" -> TaskFlowPhase.REWARD_READY
        item.status == "DOING" && item.raw?.optString("ornamentTaskType") == "TOUCH_LINK" -> TaskFlowPhase.READY_TO_COMPLETE
        else -> TaskFlowPhase.UNSUPPORTED
    }

    override fun shouldSkip(item: TaskFlowItem): Boolean {
        val phase = mapPhase(item)
        return phase == TaskFlowPhase.UNSUPPORTED || (phase == TaskFlowPhase.READY_TO_COMPLETE && !completeTasks)
    }

    override fun isUnresolvedWhenSkipped(item: TaskFlowItem): Boolean = mapPhase(item) != TaskFlowPhase.TERMINAL

    override fun complete(item: TaskFlowItem): TaskFlowActionResult {
        val response = JSONObject(AntFarmRpcCall.completeOrnamentTask(activityId, item.id))
        if (!ResChecker.checkRes("AntFarm", response)) {
            return farm.farmResourceFailure("com.alipay.antfarm.completeOrnamentTask", response)
        }
        return TaskFlowActionResult.defer(
            DeferredReason.STATE_CONFIRMATION,
            message = "浏览已提交，等待任务状态确认",
            rpc = "com.alipay.antfarm.completeOrnamentTask",
            raw = response.toString(),
            refreshAfterAction = true,
        )
    }

    override fun receive(item: TaskFlowItem): TaskFlowActionResult {
        val tasks = detail?.optJSONArray("tasks") ?: return TaskFlowActionResult.defer(
            DeferredReason.PREREQUISITE_PENDING, message = "缺少普通套装活动详情",
        )
        val completeActivity = (0 until tasks.length()).all { index ->
            val task = tasks.optJSONObject(index)
            task != null && (task.optString("taskId") == item.id || task.optString("status") == "FINISH")
        }
        val response = JSONObject(AntFarmRpcCall.receiveOrnamentTaskAward(item.id, completeActivity))
        if (!ResChecker.checkRes("AntFarm", response)) {
            return farm.farmResourceFailure("com.alipay.antfarm.receiveUserAward", response)
        }
        return TaskFlowActionResult.defer(
            DeferredReason.STATE_CONFIRMATION,
            message = "碎片奖励已提交领取，等待状态确认",
            rpc = "com.alipay.antfarm.receiveUserAward",
            raw = response.toString(),
            refreshAfterAction = true,
        )
    }

    override fun isPendingActionResolved(
        item: TaskFlowItem,
        action: TaskFlowAction,
        response: JSONObject,
        items: List<TaskFlowItem>,
    ): Boolean {
        if (items.any { it.id == item.id } || action != TaskFlowAction.RECEIVE) return false
        val ornamentsResponse = JSONObject(AntFarmRpcCall.listOrnaments("NORMALSETS", pageNo))
        if (!ResChecker.checkRes("AntFarm", ornamentsResponse)) return false
        val ornaments = ornamentsResponse.optJSONArray("ornaments") ?: return false
        return (0 until ornaments.length()).any { index ->
            val ornament = ornaments.optJSONObject(index)
            ornament != null && ornament.optString("id") == ornamentId && ornament.optBoolean("acquired")
        }
    }

    override fun logInfo(message: String) { Log.farm(message) }

    override fun logError(message: String) { Log.error("AntFarm", message) }
}

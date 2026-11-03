package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleKind
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleState
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.Log
import org.json.JSONArray
import org.json.JSONObject

internal fun AntForest.waterMemberPlant(): TaskFlowActionResult {
    var result = TaskFlowActionResult.defer(DeferredReason.PREREQUISITE_PENDING, "绿植等待种植或浇水条件")
    try {
        var response = JSONObject(AntForestRpcCall.memberForestSignin())
        while (!ApplicationHookConstants.isOffline()) {
            if (!response.optBoolean("success") || response.optString("resultCode") != "SUCCESS") {
                Log.error("ForestPlant", "绿植状态查询失败 raw=$response")
                return TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = response.optString("resultCode"), message = "绿植状态查询失败", raw = response.toString())
            }
            val data = response.optJSONObject("resultData") ?: return result
            if (data.optJSONObject("plantChannelResponse")?.optBoolean("access") != true) return result
            val plant = data.optJSONObject("plantInfo") ?: return result
            if (plant.optString("status") != "in_progress") return result
            val info = plant.optJSONObject("plantInfoMap") ?: return result
            val water = info.optJSONObject("WATER") ?: return result
            val pot = info.optJSONObject("WATERING_POT") ?: return result
            val ext = pot.optJSONObject("extInfo") ?: return result
            val available = JSONArray(ext.optString("availablePots").ifBlank { "[]" })
            val speeds = (0 until available.length()).map { available.getString(it) }
            val costPerWater = ext.optLong("costPerWater", 0L)
            val balance = water.optLong("value", 0L)
            val speed = when {
                "five" in speeds && costPerWater > 0 && balance >= costPerWater * 5L -> "five"
                "one" in speeds && costPerWater > 0 && balance >= costPerWater -> "one"
                else -> return result
            }
            val progress = info.optJSONObject("PLANT_PROGRESS_V2")?.optJSONObject("extInfo")
            val beforeTotal = progress?.optLong("totalWaterG", -1L) ?: -1L
            val beforeBar = progress?.optLong("currentBarProgressG", -1L) ?: -1L
            if (beforeTotal < 0L) {
                Log.error("ForestPlant", "绿植缺少累计浇水进度 raw=$response")
                return TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    message = "绿植缺少累计浇水进度", raw = response.toString())
            }
            val award = JSONObject(AntForestRpcCall.plantAward(speed))
            if (!award.optBoolean("success") || award.optString("resultCode") != "SUCCESS") {
                Log.error("ForestPlant", "绿植浇水失败 speed=$speed raw=$award")
            }
            response = JSONObject(AntForestRpcCall.memberForestSignin(water.optString("darwinVersion", "plant_black_v2")))
            val afterInfo = response.optJSONObject("resultData")?.optJSONObject("plantInfo")?.optJSONObject("plantInfoMap")
            val afterProgress = afterInfo?.optJSONObject("PLANT_PROGRESS_V2")?.optJSONObject("extInfo")
            val afterTotal = afterProgress?.optLong("totalWaterG", -1L) ?: -1L
            val afterBalance = afterInfo?.optJSONObject("WATER")?.optLong("value", -1L) ?: -1L
            if (!response.optBoolean("success") || afterTotal <= beforeTotal || afterBalance < 0L || afterBalance >= balance) {
                Log.error("ForestPlant", "绿植浇水进度未确认 beforeTotal=$beforeTotal beforeWater=$balance raw=$response")
                return TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    message = "绿植浇水进度未确认", raw = response.toString())
            }
            result = TaskFlowActionResult.success()
            Log.forest("绿植浇水🌱累计${beforeTotal}→${afterTotal}，水滴${balance}→${afterBalance}，段进度${beforeBar}→${afterProgress?.optLong("currentBarProgressG")}")
        }
    } catch (t: Throwable) {
        if (t is java.util.concurrent.CancellationException || t is InterruptedException) throw t
        Log.printStackTrace("ForestPlant", "绿植浇水处理异常", t)
        return TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, message = t.message.orEmpty())
    }
    return result
}

internal const val FOREST_PLANT_CHILD_KIND = "forest_plant_task"

internal fun AntForest.runMemberPlantTasks(continuation: JSONObject? = null): TaskFlowRunResult {
    return TaskFlowEngine(object : TaskFlowAdapter {
        override val moduleName = "蚂蚁森林"
        override val flowName = "绿植浏览任务"

        override fun isFlowHandledToday() = Status.hasFlagToday(StatusFlags.FLAG_FOREST_PLANT_BROWSE_DONE)

        override fun query() = JSONObject(AntForestRpcCall.queryPlantTaskList())

        override fun isQuerySuccess(response: JSONObject) =
            response.optBoolean("success") || response.optString("resultCode") == "SUCCESS"

        override fun isQueryComplete(response: JSONObject) = isQuerySuccess(response) && response.has("taskList")

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val list = response.optJSONArray("taskList") ?: return emptyList()
            return (0 until list.length()).mapNotNull { index ->
                val task = list.optJSONObject(index) ?: return@mapNotNull null
                if (task.optString("taskCode") != "GUANG_TASK") return@mapNotNull null
                TaskFlowItem(
                    id = "GUANG_TASK",
                    title = task.optString("title").ifBlank { "绿植浏览" },
                    status = if (task.optBoolean("completed")) "COMPLETED" else "TODO",
                    raw = task,
                )
            }
        }

        override fun mapPhase(item: TaskFlowItem) =
            if (item.status == "COMPLETED") TaskFlowPhase.TERMINAL else TaskFlowPhase.READY_TO_COMPLETE

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val context = ApplicationHook.appContext ?: return TaskFlowActionResult.failure(
                TaskRpcFailureType.RETRYABLE_RPC, message = "绿植任务缺少应用上下文",
            )
            val owner = AccountSessionCoordinator.currentUserId().orEmpty()
            val epoch = AccountSessionCoordinator.currentSessionEpoch()
            if (owner.isBlank() || epoch <= 0L) return TaskFlowActionResult.failure(
                TaskRpcFailureType.RETRYABLE_RPC, message = "绿植任务缺少账号会话",
            )
            val dedupePrefix = "forest_plant_${owner}::${item.id}::"
            if (continuation == null) {
                val pending = PersistentScheduleRegistry.list().firstOrNull {
                    it.dedupeKey.startsWith(dedupePrefix) && it.ownerUserId == owner && it.sessionEpoch == epoch &&
                        it.state in setOf(PersistentScheduleState.SCHEDULED, PersistentScheduleState.QUEUED, PersistentScheduleState.RUNNING)
                }
                if (pending != null) return TaskFlowActionResult.defer(
                    DeferredReason.CHILD_TASK_PENDING, message = "绿植浏览续接处理中", deferredUntil = pending.triggerAtMs,
                )
            }
            val stage = continuation?.optString("stage") ?: "BROWSE"
            val page = continuation?.optInt("page", 2) ?: 2
            if (stage !in setOf("BROWSE", "AWARD") || (stage == "BROWSE" && page !in 2..3)) {
                return TaskFlowActionResult.failure(TaskRpcFailureType.NON_RETRYABLE_INVALID, message = "绿植续接步骤无效")
            }
            val result = JSONObject(
                if (stage == "AWARD") AntForestRpcCall.receivePlantTaskAward(item.id)
                else AntForestRpcCall.queryPlantFeeds(page),
            )
            if (!isQuerySuccess(result)) return TaskFlowActionResult.failure(
                TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = result.optString("resultCode"),
                message = "绿植${if (stage == "AWARD") "领奖" else "浏览"}失败",
                raw = result.toString(),
            )
            if (stage == "AWARD") {
                Log.forest("绿植浏览🌱领奖响应：${result.opt("awardResult") ?: "待回查任务状态"}")
                return TaskFlowActionResult.success()
            }
            val items = result.optJSONArray("itemDTOList") ?: return TaskFlowActionResult.failure(
                TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, message = "绿植浏览缺少商品列表", raw = result.toString(),
            )
            val now = System.currentTimeMillis()
            val startedAt = continuation?.optLong("started_at", now) ?: now
            val nextPage = page == 2 && items.length() > 0
            val nextStage = if (nextPage) "BROWSE" else "AWARD"
            // 已确认的页面流程为第2、3页间隔7秒，末页停留后领奖，总时长不少于35秒。
            val readyAt = if (nextPage) now + 7_000L else maxOf(startedAt + 35_000L, now + 21_000L)
            val payload = JSONObject()
                .put("child_kind", FOREST_PLANT_CHILD_KIND)
                .put("owner_user_id", owner)
                .put("session_epoch", epoch)
                .put("stage", nextStage)
                .put("page", 3)
                .put("started_at", startedAt)
            val schedule = UnifiedScheduler.schedulePersistentTrigger(
                context = context,
                name = "绿植浏览:${if (nextPage) "下一页" else "领奖"}",
                kind = PersistentScheduleKind.MODULE_CHILD,
                triggerAtMs = readyAt,
                dedupeKey = dedupePrefix + if (nextPage) "page3" else "award",
                payloadJson = payload.toString(),
                ownerUserId = owner,
                sessionEpoch = epoch,
            )
            if (schedule.state != PersistentScheduleState.SCHEDULED) return TaskFlowActionResult.failure(
                TaskRpcFailureType.RETRYABLE_RPC, message = "绿植续接注册失败：${schedule.lastError}",
            )
            return TaskFlowActionResult.defer(
                DeferredReason.CHILD_TASK_PENDING, message = "绿植浏览已登记后续步骤", deferredUntil = readyAt,
            )
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            if (snapshot.totalTasks > 0) {
                Status.setFlagToday(StatusFlags.FLAG_FOREST_PLANT_BROWSE_DONE)
                Log.forest("绿植浏览🌱服务端已确认任务完成")
            }
        }

        override fun logInfo(message: String) = Log.forest(message)
        override fun logError(message: String) = Log.error("ForestPlant", message)
    }).run()
}

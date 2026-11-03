package io.github.aoguai.sesameag.task.antOrchard

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.GameCenterPlayRpcCall
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.UserDataStoreManager
import org.json.JSONObject

/** 首页广告可在发奖后继续展示，完成依据保留为本次任务的业务发奖结果。 */
internal class OrchardHomeGameTaskFlowAdapter(private val orchard: AntOrchard) : TaskFlowAdapter {
    override val moduleName = "芭芭农场"
    override val flowName = "农场首页浮球任务"
    override val continueCurrentRoundOnRetryableFailure = true
    private val identity = RpcDailyCircuit.captureIdentity()
    private val store = requireNotNull(UserDataStoreManager.getInstance(identity.userId))
    private val stateKey = "orchard_home_games"
    private val state = store.get(stateKey, String::class.java)?.let(::JSONObject)?.takeIf {
        TimeUtil.isSameDay(it.optLong("date"), System.currentTimeMillis())
    } ?: JSONObject().put("date", System.currentTimeMillis())
    var nextRunAt: Long? = null
        private set

    override fun query(): JSONObject {
        val result = JSONObject()
        val queries = listOf(
            "feeds" to { AntOrchardRpcCall.orchardIndexFeeds() },
            "delivery" to { AntOrchardRpcCall.orchardIndexDelivery() },
        )
        for ((name, request) in queries) {
            try {
                val response = JSONObject(request())
                check(RpcDailyCircuit.isCurrent(identity)) { "农场首页任务账号会话已变化" }
                result.put(name, response)
                if (!sourceLoaded(response, name)) logError("$flowName[$name 查询不完整] raw=$response")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException || t is InterruptedException ||
                    !RpcDailyCircuit.isCurrent(identity)) throw t
                logError("$flowName[$name 查询异常] ${t.message}")
                result.put(name, JSONObject().put("success", false))
            }
        }
        return result
    }

    private fun sourceLoaded(response: JSONObject, source: String): Boolean =
        orchard.isOrchardRpcSuccessResponse(response) &&
            response.optJSONArray(if (source == "feeds") "feedsInfoList" else "indexDeliveryList") != null

    override fun isQuerySuccess(response: JSONObject): Boolean =
        sourceLoaded(response.getJSONObject("feeds"), "feeds") ||
            sourceLoaded(response.getJSONObject("delivery"), "delivery")

    override fun isQueryComplete(response: JSONObject): Boolean =
        sourceLoaded(response.getJSONObject("feeds"), "feeds") &&
            sourceLoaded(response.getJSONObject("delivery"), "delivery")

    override fun extractItems(response: JSONObject): List<TaskFlowItem> {
        val tasks = linkedMapOf<String, TaskFlowItem>()
        for (source in listOf("feeds", "delivery")) {
            val page = response.getJSONObject(source)
            if (!sourceLoaded(page, source)) continue
            val entries = page.getJSONArray(if (source == "feeds") "feedsInfoList" else "indexDeliveryList")
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val benefit = if (source == "feeds") {
                    val raw = entry.optJSONObject("itemOutExtMap")?.opt("orchardBenefitInfo")
                    when (raw) {
                        is JSONObject -> raw
                        is String -> JSONObject(raw)
                        else -> null
                    }
                } else entry.optJSONObject("deliveryBenefitInfo")
                if (benefit?.optString("benefitType") != "FLOAT_BALL_TASK") continue
                val game = entry.optJSONObject("gameItem")
                val appId = if (source == "feeds") game?.optString("gameItemId").orEmpty() else entry.optString("contentId")
                val taskId = benefit.getString("iepTaskId")
                val scene = benefit.getString("iepSceneCode")
                val duration = benefit.getJSONObject("bizInfo").getInt("floatBallDuration")
                require(appId.isNotBlank() && taskId.isNotBlank() && scene.isNotBlank() && duration > 0) {
                    "农场首页浮球任务合同不完整: $entry"
                }
                val key = "$scene:$taskId:$appId"
                val title = game?.optString("gameName")?.takeIf { it.isNotBlank() } ?: taskId
                val task = JSONObject().put("appId", appId).put("duration", duration)
                    .put("sceneCode", scene).put("taskId", taskId)
                tasks[key] = TaskFlowItem(
                    id = key, title = title, type = taskId, sceneCode = scene,
                    status = if (Status.hasFlagToday(StatusFlags.FLAG_ANTORCHARD_HOME_GAME_DONE_PREFIX + key)) "DONE" else "TODO",
                    blacklistKeys = listOf(taskId, appId), raw = task,
                    progress = "duration=$duration stage=${state.optJSONObject(key)?.optInt("stage") ?: 0}",
                )
            }
        }
        return tasks.values.toList()
    }

    override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
        if (item.status == "DONE") TaskFlowPhase.TERMINAL else TaskFlowPhase.READY_TO_COMPLETE

    override fun complete(item: TaskFlowItem): TaskFlowActionResult {
        check(RpcDailyCircuit.isCurrent(identity)) { "农场首页任务账号会话已变化" }
        val task = requireNotNull(item.raw)
        val appId = task.getString("appId")
        val duration = task.getInt("duration")
        val saved = state.optJSONObject(item.id) ?: JSONObject()
            .put("outBizNo", "${item.type}_${System.currentTimeMillis()}_$appId")
        state.put(item.id, saved)
        if (saved.optBoolean("finishSubmitted")) {
            return TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "完成请求已提交但发奖未确认，保留结果待核对", rpc = "AntOrchardRpcCall.finishTask",
                raw = saved.optString("finishResponse"), detail = item.id, continueCurrentRoundOnFailure = true)
        }
        val outBizNo = saved.getString("outBizNo")
        val entrySteps = listOf(
            "AntOrchardRpcCall.noticeGame" to { AntOrchardRpcCall.noticeGame(appId, "ch_appcenter__chsub_9patch") },
            "AntOrchardRpcCall.enterHomeGame" to { AntOrchardRpcCall.enterHomeGame(appId) },
            "AntOrchardRpcCall.reportHomeGameEvent" to { AntOrchardRpcCall.reportHomeGameEvent(appId, "GAME_FIRST_FRAME", "${outBizNo}_frame") },
            "AntOrchardRpcCall.reportHomeGameEvent" to { AntOrchardRpcCall.reportHomeGameEvent(appId, "loading_completed", "${outBizNo}_loaded") },
        )
        for (step in saved.optInt("stage") until entrySteps.size) {
            val (rpc, request) = entrySteps[step]
            val response = JSONObject(request())
            check(RpcDailyCircuit.isCurrent(identity)) { "农场首页任务账号会话已变化" }
            if (!orchard.isOrchardRpcSuccessResponse(response)) {
                return orchard.buildOrchardTaskFailureResult(response, item.id, item.title, "enter", rpc, item)
            }
            saved.put("stage", step + 1)
            store.put(stateKey, state.toString(), requirePersistence = true)
        }
        val durationKey = "orchard_home_game_duration:${item.id}"
        val timed = GameCenterPlayRpcCall.advanceDuration(durationKey,
            GameCenterPlayRpcCall.Contract(appId, duration, "lianyun_nc_sydb"))
        if (!timed.success) return timed
        if (saved.optInt("stage") == entrySteps.size) {
            val played = JSONObject(AntOrchardRpcCall.reportHomeGameEvent(appId, "game_play", "${outBizNo}_played", duration * 1000L))
            check(RpcDailyCircuit.isCurrent(identity)) { "农场首页任务账号会话已变化" }
            if (!orchard.isOrchardRpcSuccessResponse(played)) {
                return orchard.buildOrchardTaskFailureResult(played, item.id, item.title, "play", "AntOrchardRpcCall.reportHomeGameEvent", item)
            }
            saved.put("stage", entrySteps.size + 1)
        }
        // 请求前持久化待确认态，进程中断后不能把未知发奖结果当成可重新领取。
        saved.put("finishSubmitted", true)
        store.put(stateKey, state.toString(), requirePersistence = true)
        val finished = JSONObject(AntOrchardRpcCall.finishTask("", item.sceneCode, item.type, outBizNo = outBizNo))
        check(RpcDailyCircuit.isCurrent(identity)) { "农场首页任务账号会话已变化" }
        saved.put("finishResponse", finished.toString())
        store.put(stateKey, state.toString(), requirePersistence = true)
        if (!orchard.isOrchardRpcSuccessResponse(finished)) {
            val failure = orchard.buildOrchardTaskFailureResult(finished, item.id, item.title, "complete", "AntOrchardRpcCall.finishTask", item)
            if (failure.failureType != TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW) {
                saved.remove("finishSubmitted")
                store.put(stateKey, state.toString(), requirePersistence = true)
            }
            return failure
        }
        val award = finished.optJSONObject("finishAwardResultVO")?.optInt("deltaAwardCount", 0) ?: 0
        if (award <= 0) return TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            message = "完成请求已受理，未取得业务发奖确认", rpc = "AntOrchardRpcCall.finishTask",
            raw = finished.toString(), detail = item.id, continueCurrentRoundOnFailure = true)
        Status.setFlagToday(StatusFlags.FLAG_ANTORCHARD_HOME_GAME_DONE_PREFIX + item.id)
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTORCHARD_HOME_GAME_DONE_PREFIX + item.id)) {
            state.remove(item.id)
            store.put(stateKey, state.toString(), requirePersistence = true)
            store.remove(durationKey)
        }
        logInfo("农场首页游戏[${item.title}] 获得${award}g肥料")
        return TaskFlowActionResult.success()
    }

    override fun afterDeferred(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
        if (result.deferredReason == DeferredReason.TIME_WINDOW) {
            result.deferredUntil?.let { nextRunAt = minOf(nextRunAt ?: it, it) }
        }
    }

    override fun logInfo(message: String) = Log.orchard(message)
    override fun logError(message: String) = Log.error("AntOrchard", message)
}

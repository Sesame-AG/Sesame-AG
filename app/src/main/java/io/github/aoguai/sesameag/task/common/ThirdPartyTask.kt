package io.github.aoguai.sesameag.task.common

import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.internal.MtopTaskCall
import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** 第三方完成上报只提供受理结果，业务完成与领奖由原任务列表确认。 */
object ThirdPartyTask {
    private const val LIFE_API = "mtop.aplatform.taobaolife.get"
    private const val FARM_API = "mtop.tmall.farm.orchard.task.notifyAlipayTaskFinish"
    private const val SCHEMA_API = "alipay.antmember.callApp.queryCallAppSchema"
    private const val TTID = "1588718881807@taobao_android_10.68.10"
    private val selfScenes = setOf(
        "ANTORCHARD_DRAW_TIMES", "ANTFOREST_ACTIVITY_DRAW_TASK", "FUGUO", "ORCHARD",
        "ANTFOREST_VITALITY_TASK", "ANTFARM_ORCHARD_TASK_V2", "ANTORCHARD_DRAW_TIMES_TASK",
        "ANTFOREST_NORMAL_DRAW_TASK", "FOREST_CONTINUOUS_COLLECT_ENERGY_7", "2026cc_1000lyb_A&h",
        "ANTFARM_pay_6", "20260525_chouchoulechoukuan2", "IP_chouchoule_juankuan", "HEART_DONATE",
        "HEART_DONATION_ADVANCED_FOOD_V2", "MYZY_pay_xjsl_xddq", "monthlyCard", "freeze",
    )

    data class Contract(val sceneCode: String, val taskType: String, val token: String, val api: String)

    fun resolve(item: TaskFlowItem): Contract? {
        val descriptor = GameCenterPlayRpcCall.describeTask(item.raw)
        val parameters = descriptor.urlParameters
        fun value(vararg keys: String): String = keys.asSequence().mapNotNull { key ->
            parameters[key]?.trim()?.trim('"', '\'')?.takeIf {
                it.isNotBlank() && it != "\${$key}" && it != "null" && it != "undefined"
            }
        }.firstOrNull().orEmpty()
        val scene = value("sceneCode")
        val taskType = value("iepTaskType", "taskType", "third_task_type")
        val token = value("userToken", "user_token", "third_token", "tToken", "token")
        val businessScene = value("iepTaskSceneCode", "third_scene_code")
        if (businessScene.isNotBlank() && taskType.isNotBlank() && token.isNotBlank()) {
            return Contract(businessScene, taskType, token, LIFE_API)
        }
        if (scene in selfScenes && taskType.isNotBlank() && token.isNotBlank()) {
            return Contract(scene, taskType, token, LIFE_API)
        }
        val declaredCallApp = descriptor.objects.any { it.optString("taskPlayType") == "CALL_APP_OUT_TASK" } ||
            parameters["taskPlayType"] == "CALL_APP_OUT_TASK" ||
            (parameters["appId"] == "20002133" && !parameters["jumpAction"].isNullOrBlank())
        if (scene.isBlank() || scene in selfScenes || !declaredCallApp) return null
        return Contract(scene, taskType, token, SCHEMA_API)
    }

    fun complete(contract: Contract): TaskFlowActionResult? {
        val session = AccountSessionCoordinator.currentSession()
        if (session == null || ApplicationHookConstants.isOffline()) {
            return TaskFlowActionResult.failure(TaskRpcFailureType.BUSINESS_LIMIT,
                message = "当前没有在线账号会话，停止第三方上报", stopCurrentRound = true)
        }
        if (contract.sceneCode.isBlank()) {
            return TaskFlowActionResult.failure(TaskRpcFailureType.NON_RETRYABLE_INVALID,
                message = "第三方任务链接缺少 sceneCode", detail = "CALL_APP_OUT_TASK")
        }
        var api = contract.api
        var writeStarted = false
        try {
            var token = contract.token
            if (contract.api == SCHEMA_API) {
                api = SCHEMA_API
                val raw = RequestManager.requestString(SCHEMA_API,
                    JSONArray().put(JSONObject().put("sceneCode", contract.sceneCode)).toString())
                val response = runCatching { JSONObject(raw) }.getOrNull()
                if (response == null || !response.optBoolean("success")) {
                    return TaskFlowActionResult.failure(
                        if (response?.optBoolean("retriable", true) == false ||
                            response?.optBoolean("retryable", true) == false) TaskRpcFailureType.NON_RETRYABLE_INVALID
                        else GameCenterPlayRpcCall.classify(raw),
                        code = response?.optString("resultCode").orEmpty(),
                        message = "第三方落地链接查询失败", rpc = SCHEMA_API, raw = raw,
                        detail = "sceneCode=${contract.sceneCode}")
                }
                val links = mutableListOf(response.optString("channelLandingUrlLink"))
                val schemas = response.optJSONArray("schemaConfigList")
                for (index in 0 until (schemas?.length() ?: 0)) {
                    schemas?.optJSONObject(index)?.let {
                        links += it.optString("channelDeepLink")
                        links += it.optString("channelMarketLink")
                    }
                }
                val landedToken = links.asSequence().filter { it.isNotBlank() }.map { link ->
                    val parameters = GameCenterPlayRpcCall.describeTask(JSONObject().put("targetUrl", link)).urlParameters
                    val scene = parameters["sceneCode"] ?: parameters["iepTaskSceneCode"] ?: parameters["third_scene_code"]
                    if (scene != contract.sceneCode) "" else {
                        listOf("userToken", "user_token", "third_token", "tToken", "token").firstNotNullOfOrNull { key ->
                            parameters[key]?.trim()?.trim('"', '\'')?.takeIf {
                                it.isNotBlank() && it != "\${$key}" && it != "null" && it != "undefined"
                            }
                        }.orEmpty()
                    }
                }.firstOrNull { it.isNotBlank() }.orEmpty()
                if (landedToken.isBlank()) {
                    Log.record("ThirdPartyTask", "落地配置未提供场景匹配的完成回传合同，继续原模块动作 sceneCode=${contract.sceneCode}")
                    return null
                }
                token = landedToken
                api = FARM_API
            }
            if (token.isBlank() || (api == LIFE_API && contract.taskType.isBlank())) {
                return TaskFlowActionResult.failure(TaskRpcFailureType.NON_RETRYABLE_INVALID,
                    message = "第三方任务缺少 userToken 或 taskType", rpc = api,
                    detail = "sceneCode=${contract.sceneCode}")
            }
            if (!AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch) ||
                ApplicationHookConstants.isOffline()) {
                return TaskFlowActionResult.failure(TaskRpcFailureType.BUSINESS_LIMIT,
                    message = "账号会话已变化或已离线", stopCurrentRound = true)
            }
            val data = if (api == FARM_API) {
                JSONObject().put("sceneCode", "FUGUO").put("convertTag", contract.sceneCode)
                    .put("userToken", token).put("ttid", TTID)
            } else {
                val business = JSONObject().put("source", "taobao").put("token", token)
                    .put("taskType", contract.taskType).put("sceneCode", contract.sceneCode)
                JSONObject().put("bizType", "achievement.antFinishTask").put("bizParam", business.toString())
                    .put("ttid", TTID)
            }
            val referer = if (api == FARM_API) "https://pages-fast.m.taobao.com/wow/z/hdwk/farm-ssr/tmfarm"
                else "https://pages-fast.m.taobao.com/wow/z/tblife/achievement-i/JX4ezmspfN8txPMWyawe"
            val raw = MtopTaskCall.call(api, data, referer) { writeStarted = true }
            val response = runCatching { JSONObject(raw) }.getOrNull()
            val ret = response?.optJSONArray("ret")
            val codes = (0 until (ret?.length() ?: 0)).map { ret?.optString(it).orEmpty().substringBefore("::") }
            val business = response?.optJSONObject("data")
            val businessFailed = (business?.has("success") == true && !business.optBoolean("success")) ||
                (business?.has("isSuccess") == true && !business.optBoolean("isSuccess"))
            val explicitFailure = codes.any { it.isNotBlank() && it != "SUCCESS" } || businessFailed
            if (explicitFailure) {
                val failureRaw = business?.takeIf { businessFailed }?.toString() ?: raw
                return TaskFlowActionResult.failure(GameCenterPlayRpcCall.classify(failureRaw),
                    code = codes.firstOrNull { it != "SUCCESS" }.orEmpty(), message = "第三方完成回传失败",
                    rpc = api, raw = raw).copy(refreshAfterAction = true)
            }
            if (codes.isEmpty()) Log.error("ThirdPartyTask", "MTOP 响应未确认受理，回查业务列表：$raw")
            return TaskFlowActionResult.defer(DeferredReason.STATE_CONFIRMATION,
                message = if (codes.isEmpty()) "第三方回传结果未知，回查原业务任务" else "第三方回传已受理，回查原业务任务及奖励",
                rpc = api, raw = raw, refreshAfterAction = true).copy(submissionStarted = writeStarted)
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Throwable) {
            if (!AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch) ||
                ApplicationHookConstants.isOffline()) {
                return if (writeStarted) TaskFlowActionResult.defer(DeferredReason.STATE_CONFIRMATION,
                    message = "账号会话已变化或已离线，回传结果待回查", rpc = api, raw = error.toString(),
                    refreshAfterAction = true, stopCurrentRound = true).copy(submissionStarted = true)
                else TaskFlowActionResult.failure(TaskRpcFailureType.BUSINESS_LIMIT,
                    message = "账号会话已变化或已离线，尚未发起回传",
                    rpc = api, raw = error.toString(), stopCurrentRound = true)
            }
            Log.error("ThirdPartyTask", "第三方回传异常：$error")
            return if (writeStarted) TaskFlowActionResult.defer(DeferredReason.STATE_CONFIRMATION,
                message = "第三方回传结果未知，回查原业务任务", rpc = api, raw = error.toString(), refreshAfterAction = true)
                .copy(submissionStarted = true)
            else TaskFlowActionResult.failure(TaskRpcFailureType.NON_RETRYABLE_INVALID,
                message = "第三方回传调用前失败，尚未发起回传", rpc = api, raw = error.toString())
        }
    }
}

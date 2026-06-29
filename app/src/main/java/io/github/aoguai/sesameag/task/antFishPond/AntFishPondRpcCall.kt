package io.github.aoguai.sesameag.task.antFishPond

import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.util.RandomUtil
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 福气鱼池 RPC 调用封装。
 *
 * 参数来自 2026-04/2026-05 抓包，当前只封装已形成闭环的基础链路。
 */
object AntFishPondRpcCall {
    private const val VERSION = "20260211.01"
    private const val SOURCE_FARMPOOL = "farmpool"
    private const val SCENE_GAME_CENTER = "GameCenter"
    private const val SCENE_TASK = "ANTFISHPOND_TASK"
    private const val SOURCE_IEP_FINISH = "ADBASICLIB"

    private const val METHOD_INDEX = "com.alipay.antfishpond.fishpondIndex"
    private const val METHOD_SYNC_INDEX = "com.alipay.antfishpond.fishpondSyncIndex"
    private const val METHOD_QUERY_SUBPLOTS = "com.alipay.antfishpond.querySubplotsActivity"
    private const val METHOD_TRIGGER_SUBPLOTS = "com.alipay.antfishpond.triggerSubplotsActivity"
    private const val METHOD_LIST_TASK = "com.alipay.antfishpond.listTask"
    private const val METHOD_SIGN = "com.alipay.antfishpond.sign"
    private const val METHOD_EXCHANGE_REWARD = "com.alipay.antfishpond.fishpondExchangeReward"
    private const val METHOD_AD_NOTICE = "com.alipay.antfishpond.fishpondAdNotice"
    private const val METHOD_ANGLE = "com.alipay.antfishpond.fishpondAngle"
    private const val METHOD_ROD_POSITIONING = "com.alipay.antfishpond.fishpondAngleRodPositioning"
    private const val METHOD_FINISH_TASK = "com.alipay.antiep.finishTask"
    private const val METHOD_RECEIVE_TASK_AWARD = "com.alipay.antiep.receiveTaskAward"

    private fun baseArgs(source: String = SOURCE_FARMPOOL): JSONObject {
        return JSONObject()
            .put("requestType", "NORMAL")
            .put("sceneCode", SCENE_GAME_CENTER)
            .put("source", source)
            .put("version", VERSION)
    }

    private fun baseIndexArgs(source: String = SOURCE_FARMPOOL): JSONObject {
        return baseArgs(source)
            .put("appMode", "normal")
    }

    private fun request(method: String, args: JSONObject): String {
        return RequestManager.requestString(method, JSONArray().put(args).toString())
    }

    @JvmStatic
    fun fishpondIndex(): String {
        val args = baseIndexArgs()
            .put("darwinSceneList", JSONArray().put("taskFullAreaClick"))
        return request(METHOD_INDEX, args)
    }

    @JvmStatic
    fun fishpondSyncIndex(syncTypeList: List<String> = emptyList()): String {
        val args = baseIndexArgs()
        val syncArray = JSONArray()
        for (syncType in syncTypeList) {
            syncArray.put(syncType)
        }
        args.put("syncTypeList", syncArray)
        return request(METHOD_SYNC_INDEX, args)
    }

    @JvmStatic
    fun querySubplotsActivity(): String {
        return request(METHOD_QUERY_SUBPLOTS, baseIndexArgs())
    }

    @JvmStatic
    fun triggerSubplotsActivity(
        activityType: String,
        actionType: String,
        source: String = SOURCE_FARMPOOL
    ): String {
        val response = request(
            METHOD_TRIGGER_SUBPLOTS,
            baseArgs(source)
                .put("activityType", activityType)
                .put("actionType", actionType)
        )
        return response
    }

    @JvmStatic
    fun listTask(): String {
        return request(METHOD_LIST_TASK, baseIndexArgs())
    }

    @JvmStatic
    fun sign(signKey: String = todaySignKey()): String {
        val response = request(
            METHOD_SIGN,
            baseArgs()
                .put("signKey", signKey)
        )
        return response
    }

    @JvmStatic
    fun fishpondExchangeReward(): String {
        return request(METHOD_EXCHANGE_REWARD, baseArgs())
    }

    @JvmStatic
    fun fishpondAdNotice(adBizNo: String): String {
        return request(
            METHOD_AD_NOTICE,
            baseArgs()
                .put("adBizNo", adBizNo)
        )
    }

    @JvmStatic
    fun finishTask(taskType: String, adBizNo: String, sceneCode: String = SCENE_TASK): String {
        val outBizNo = "${taskType}_${System.currentTimeMillis()}_${RandomUtil.getRandomString(8)}"
        val response = request(
            METHOD_FINISH_TASK,
            JSONObject()
                .put("finishBusinessInfo", JSONObject().put("pwPreBizId", adBizNo))
                .put("outBizNo", outBizNo)
                .put("requestType", "RPC")
                .put("sceneCode", sceneCode)
                .put("source", SOURCE_IEP_FINISH)
                .put("taskType", taskType)
        )
        return response
    }

    @JvmStatic
    fun receiveTaskAward(taskType: String, sceneCode: String = SCENE_TASK): String {
        val response = request(
            METHOD_RECEIVE_TASK_AWARD,
            JSONObject()
                .put("ignoreLimit", false)
                .put("requestType", "NORMAL")
                .put("sceneCode", sceneCode)
                .put("source", SOURCE_FARMPOOL)
                .put("taskType", taskType)
                .put("version", VERSION)
        )
        return response
    }

    @JvmStatic
    fun fishpondAngle(riskToken: String): String {
        val response = request(
            METHOD_ANGLE,
            baseArgs()
                .put("bizNo", "")
                .put("riskToken", riskToken)
        )
        return response
    }

    @JvmStatic
    fun fishpondAngleRodPositioning(bizNo: String, areaType: String): String {
        val response = request(
            METHOD_ROD_POSITIONING,
            baseArgs()
                .put("bizNo", bizNo)
                .put("areaType", areaType)
        )
        return response
    }

    private fun todaySignKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }
}

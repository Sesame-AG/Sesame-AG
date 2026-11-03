package io.github.aoguai.sesameag.task.youthPrivilege

import io.github.aoguai.sesameag.hook.RequestManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 青春特权当前入口的最小 RPC 集合。
 *
 * 请求字段仅保留抓包中稳定出现的参数；任务参数始终由
 * queryTaskModel 的服务端下发结果提供，不能由本地文案推断。
 */
object YouthPrivilegeRpcCall {
    const val CH_INFO = "searchxsth"

    private const val RPC_PREFIX = "com.alipay.mobileopl.youthprivilege.rpc.mgw."

    fun queryCheckInModel(): String =
        request(
            "queryCheckInModel",
            JSONObject().apply {
                put("chInfo", CH_INFO)
                put("queryAd", true)
                put("skipTaskModule", false)
            },
        )

    fun checkIn(): String =
        request(
            "checkIn",
            JSONObject().apply {
                put("source", CH_INFO)
            },
        )

    fun queryTaskModel(): String =
        request(
            "queryTaskModel",
            JSONObject().apply {
                put("chInfo", CH_INFO)
                put("skipTaskList", false)
            },
        )

    fun taskSignUp(
        taskCode: String,
        taskSource: String,
        taskType: String,
    ): String = taskAction("taskSignUp", taskCode, taskSource, taskType)

    fun taskComplete(
        taskCode: String,
        taskSource: String,
        taskType: String,
    ): String = taskAction("taskComplete", taskCode, taskSource, taskType)


    fun queryLotteryIndex(): String = RequestManager.requestString(
        "alipay.membertangram.biz.rpc.student.queryLotteryIndex",
        JSONArray().put(JSONObject().put("sceneCode", "STUDENT_MONEY_CHECK_IN")).toString(),
    )

    fun multiDrawsLottery(): String = RequestManager.requestString(
        "alipay.membertangram.biz.rpc.student.multiDrawsLottery",
        JSONArray().put(JSONObject()
            .put("sceneCode", "STUDENT_MONEY_CHECK_IN")
            .put("systemVersion", android.os.Build.VERSION.RELEASE)).toString(),
    )

    fun triggerFeedsPrize(): String = RequestManager.requestString(
        "alipay.membertangram.biz.rpc.student.triggerPointPrize",
        JSONArray().put(JSONObject().put("bizId", "DO_FEEDS_TASK").put("sceneCode", "STUDENT_MONEY_CHECK_IN")).toString(),
    )

    fun queryYouth100(): String {
        val cityCode = io.github.aoguai.sesameag.hook.internal.LocationHelper.requireCityCode()
        return request("youth100.homepage.query", JSONObject()
            .put("sceneCode", "YOUTH100").put("chInfo", CH_INFO).put("adCode", cityCode).apply {
                io.github.aoguai.sesameag.hook.internal.LocationHelper.getLocation()?.let { location ->
                    put("latitude", location.getDouble("latitude"))
                    put("longitude", location.getDouble("longitude"))
                }
            })
    }

    fun receiveMonthlyPrivilege(itemId: String, moduleCode: String): String = request(
        "youth100.privilege.receive", JSONObject().put("itemId", itemId).put("moduleCode", moduleCode),
    )

    fun queryTrialPrizes(month: Boolean): String {
        val day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
        val start = if (month) day.withDayOfMonth(1) else day
        val end = if (month) day.withDayOfMonth(day.lengthOfMonth()) else day
        return RequestManager.requestString("com.alipay.yebpromobff.promosdk2024.prize.query",
            JSONArray().put(JSONObject().put("playEntrance", "YEB_YONG_TYJ_PROMO")
                .put("playActionCode", if (month) "CAMP_MONTH_QUERY" else "CAMP_DAY_QUERY")
                .put("startTime", "$start 00:00:00").put("endTime", "$end 23:59:59")).toString())
    }

    fun triggerTrialPrize(): String = RequestManager.requestString(
        "com.alipay.yebpromobff.promosdk2024.prize.trigger",
        JSONArray().put(JSONObject().put("playEntrance", "YEB_YONG_TYJ_PROMO").put("playActionCode", "CAMP_TRIGGER")).toString(),
    )

    private fun taskAction(
        method: String,
        taskCode: String,
        taskSource: String,
        taskType: String,
    ): String =
        request(
            method,
            JSONObject().apply {
                put("taskCode", taskCode)
                put("taskSource", taskSource)
                put("taskType", taskType)
            },
        )

    private fun request(
        method: String,
        payload: JSONObject,
    ): String = RequestManager.requestString(RPC_PREFIX + method, JSONArray().put(payload).toString())
}

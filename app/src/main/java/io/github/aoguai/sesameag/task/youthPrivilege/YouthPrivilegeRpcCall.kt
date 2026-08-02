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
    const val CH_INFO = "ch_appcollect__chsub_my-recentlyUsed"

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

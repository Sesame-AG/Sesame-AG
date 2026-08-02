package io.github.aoguai.sesameag.task.antOrchard

import io.github.aoguai.sesameag.hook.RequestManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Golden Bean Treasure uses a separate protocol domain from the legacy orchard
 * and orchard game-center calls. Keep its stable request fields isolated here
 * so a version change in one domain cannot silently affect the other.
 */
internal object GoldenBeanRpcCall {
    internal const val TASK_SCENE_CODE = "GOLDEN_BEAN_MASTER_TASK"
    internal const val TASK_SOURCE = "babafarm"
    internal const val VERSION = "20260723.01"
    internal const val MINER_SOURCE =
        "ch_url-https://render.alipay.com/p/yuyan/180020010001291350/index.html"
    internal const val GAME_SCENE_CODE = "GOLDENBEAN"
    private const val GAME_QUERY_VERSION = "10.8.20.8000"
    internal const val WAKUANG_TASK_TYPE = "GOLDEN_BEAN_TASK_WAKUANG"
    internal const val JINDOULEYUAN_TASK_TYPE = "JINDOULEYUAN_TRIGGER"
    internal const val MANURE_EXCHANGE_TASK_TYPE = "MANURE_EXCHANGE"
    internal const val WAKUANG_ACTION_TYPE = "TRIGGER"
    internal const val JINDOULEYUAN_ACTION_TYPE = "GAMECENTER_TRIGGER"

    fun index(): String =
        request(
            "com.alipay.goldenbean.index",
            JSONObject().apply {
                put("bizType", "MASTER")
                put("darwinSceneList", JSONArray())
                put("source", TASK_SOURCE)
                put("version", VERSION)
            },
        )

    fun sync(
        syncTypeList: List<String>,
        source: String = TASK_SOURCE,
    ): String =
        request(
            "com.alipay.goldenbean.sync",
            JSONObject().apply {
                put("bizType", "MASTER")
                put("source", source)
                put("syncTypeList", JSONArray(syncTypeList))
                put("version", VERSION)
            },
        )

    fun sign(signKey: String): String =
        request(
            "com.alipay.goldenbean.sign",
            JSONObject().apply {
                put("bizType", "MASTER")
                put("signKey", signKey)
                put("source", TASK_SOURCE)
                put("version", VERSION)
            },
        )

    fun manureExchange(exchangeBeanAmount: Int): String =
        request(
            "com.alipay.goldenbean.manureExchange",
            JSONObject().apply {
                put("bizType", "MASTER")
                put("exchangeBeanAmount", exchangeBeanAmount)
                put("source", TASK_SOURCE)
                put("version", VERSION)
            },
        )

    fun trigger(
        taskId: String,
        triggerType: String,
    ): String =
        request(
            "com.alipay.goldenbean.trigger",
            JSONObject().apply {
                put("bizType", "MASTER")
                put("source", TASK_SOURCE)
                put("taskId", taskId)
                put("triggerType", triggerType)
                put("version", VERSION)
            },
        )

    fun finishTask(taskType: String): String =
        request(
            "com.alipay.antieptask.finishTaskantorchard",
            JSONObject().apply {
                put("bizType", "MASTER")
                put("finishBusinessInfo", JSONObject().put("bizType", "MASTER"))
                put("outBizNo", System.currentTimeMillis().toString())
                put("sceneCode", TASK_SCENE_CODE)
                put("source", TASK_SOURCE)
                put("taskType", taskType)
                put("version", VERSION)
            },
        )

    fun receiveTaskAward(taskType: String): String =
        request(
            "com.alipay.antieptask.receiveTaskAwardantorchard",
            JSONObject().apply {
                put("bizInfo", JSONObject().put("bizType", "MASTER"))
                put("bizType", "MASTER")
                put("ignoreLimit", true)
                put("sceneCode", TASK_SCENE_CODE)
                put("source", TASK_SOURCE)
                put("taskType", taskType)
                put("version", VERSION)
            },
        )

    fun minerIndex(): String =
        request(
            "com.alipay.goldenbean.miner.index",
            JSONObject().apply {
                put("bizType", "MASTER")
                put("source", MINER_SOURCE)
                put("version", VERSION)
            },
        )

    fun minerGrab(
        grabResult: String,
        itemId: String = "",
    ): String =
        request(
            "com.alipay.goldenbean.miner.grab",
            JSONObject().apply {
                put("bizType", "MASTER")
                put("grabId", java.util.UUID.randomUUID().toString())
                put("grabResult", grabResult)
                if (itemId.isNotBlank()) {
                    put("itemId", itemId)
                }
                put("source", MINER_SOURCE)
                put("version", VERSION)
            },
        )

    fun queryGameList(): String =
        request(
            "com.alipay.charitygamecenter.queryGameList",
            JSONObject().apply {
                put("bizType", "GOLDENBEAN")
                put(
                    "commonDegradeFilterRequest",
                    JSONObject().apply {
                        put("deviceLevel", "high")
                        put("platform", "Android")
                        put("unityDeviceLevel", "high")
                    },
                )
                put("requestType", "RPC")
                put("sceneCode", GAME_SCENE_CODE)
                put("source", TASK_SOURCE)
                put("version", GAME_QUERY_VERSION)
            },
        )

    fun drawGameCenterAward(): String =
        request(
            "com.alipay.charitygamecenter.drawGameCenterAward",
            JSONObject().apply {
                put("batchDrawCount", 1)
                put("bizType", "GOLDENBEAN")
                put("requestType", "RPC")
                put("sceneCode", GAME_SCENE_CODE)
                put("source", TASK_SOURCE)
                put("version", VERSION)
            },
        )

    fun listTopItemsByScene(): String =
        request(
            "com.alipay.antiep.listTopItemsByScene",
            JSONObject().apply {
                put("bizType", "MASTER")
                put("itemSceneList", JSONArray().put("OPERATION_STRATEGY"))
                put("requestType", "RPC")
                put("sceneCode", "ANTORCHARD_JINDOU_MALL")
                put("source", "MASTER")
                put("subChannel", TASK_SOURCE)
                put("version", VERSION)
            },
        )

    fun itemList(
        startIndex: Int = 0,
        pageSize: Int = 20,
    ): String =
        request(
            "com.alipay.antiep.itemList",
            JSONObject().apply {
                put("bizType", "MASTER")
                put("pageSize", pageSize)
                put("requestType", "RPC")
                put("sceneCode", "ANTORCHARD_JINDOU_MALL")
                put("source", "MASTER")
                put("startIndex", startIndex)
                put("version", VERSION)
            },
        )

    private fun request(
        method: String,
        requestData: JSONObject,
    ): String = RequestManager.requestString(method, JSONArray().put(requestData).toString())
}

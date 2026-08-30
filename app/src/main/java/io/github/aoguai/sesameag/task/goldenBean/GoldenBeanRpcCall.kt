package io.github.aoguai.sesameag.task.goldenBean

import io.github.aoguai.sesameag.hook.RequestManager
import org.json.JSONArray
import org.json.JSONObject

internal enum class GoldenBeanExchangeKind {
    MANURE,
    SESAME_GRAIN,
}

/**
 * Server-verified Golden Bean entry parameters. The two entry types share
 * rewards, but their task scenes and exchange units are independent.
 */
internal data class GoldenBeanEntry(
    val bizType: String,
    val source: String,
    val taskSceneCode: String,
    val exchangeKind: GoldenBeanExchangeKind,
)

/**
 * Golden Bean Treasure has distinct entry contracts for Babafarm and Sesame
 * Alchemy. Keep those request fields together so a call cannot mix entrances.
 */
internal object GoldenBeanRpcCall {
    internal val MASTER_ENTRY =
        GoldenBeanEntry(
            bizType = "MASTER",
            source = "babafarm",
            taskSceneCode = "GOLDEN_BEAN_MASTER_TASK",
            exchangeKind = GoldenBeanExchangeKind.MANURE,
        )
    internal val ZHIMA_ENTRY =
        GoldenBeanEntry(
            bizType = "ZHIMA",
            source = "lianjin",
            taskSceneCode = "GOLDEN_BEAN_ZHIMA_LIST",
            exchangeKind = GoldenBeanExchangeKind.SESAME_GRAIN,
        )
    internal val ENTRIES = listOf(MASTER_ENTRY, ZHIMA_ENTRY)

    internal const val TASK_SCENE_CODE = "GOLDEN_BEAN_MASTER_TASK"
    internal const val VERSION = "20260803.01"
    internal const val MINER_SOURCE =
        "ch_url-https://render.alipay.com/p/yuyan/180020010001291350/index.html"
    internal const val GAME_SCENE_CODE = "GOLDENBEAN"
    private const val GAME_QUERY_VERSION = "10.8.20.8000"
    internal const val WAKUANG_TASK_TYPE = "GOLDEN_BEAN_TASK_WAKUANG"
    internal const val JINDOULEYUAN_TASK_TYPE = "JINDOULEYUAN_TRIGGER"
    internal const val MANURE_EXCHANGE_TASK_TYPE = "MANURE_EXCHANGE"
    internal const val WAKUANG_ACTION_TYPE = "TRIGGER"
    internal const val JINDOULEYUAN_ACTION_TYPE = "GAMECENTER_TRIGGER"

    fun index(entry: GoldenBeanEntry = MASTER_ENTRY): String =
        request(
            "com.alipay.goldenbean.index",
            JSONObject().apply {
                put("bizType", entry.bizType)
                put("darwinSceneList", JSONArray())
                put("source", entry.source)
                put("version", VERSION)
            },
        )

    fun sync(
        syncTypeList: List<String>,
        entry: GoldenBeanEntry = MASTER_ENTRY,
        sourceOverride: String? = null,
    ): String =
        request(
            "com.alipay.goldenbean.sync",
            JSONObject().apply {
                put("bizType", entry.bizType)
                put("source", sourceOverride ?: entry.source)
                put("syncTypeList", JSONArray(syncTypeList))
                put("version", VERSION)
            },
        )

    fun sign(
        signKey: String,
        entry: GoldenBeanEntry = MASTER_ENTRY,
    ): String =
        request(
            "com.alipay.goldenbean.sign",
            JSONObject().apply {
                put("bizType", entry.bizType)
                put("signKey", signKey)
                put("source", entry.source)
                put("version", VERSION)
            },
        )

    fun manureExchange(
        exchangeBeanAmount: Int,
        entry: GoldenBeanEntry = MASTER_ENTRY,
    ): String =
        request(
            "com.alipay.goldenbean.manureExchange",
            JSONObject().apply {
                put("bizType", entry.bizType)
                put("exchangeBeanAmount", exchangeBeanAmount)
                put("source", entry.source)
                put("version", VERSION)
            },
        )

    fun trigger(
        taskId: String,
        triggerType: String,
        entry: GoldenBeanEntry = MASTER_ENTRY,
    ): String =
        request(
            "com.alipay.goldenbean.trigger",
            JSONObject().apply {
                put("bizType", entry.bizType)
                put("source", entry.source)
                put("taskId", taskId)
                put("triggerType", triggerType)
                put("version", VERSION)
            },
        )

    fun finishTask(
        taskType: String,
        entry: GoldenBeanEntry = MASTER_ENTRY,
    ): String =
        request(
            "com.alipay.antieptask.finishTaskantorchard",
            JSONObject().apply {
                put("bizType", entry.bizType)
                put("finishBusinessInfo", JSONObject().put("bizType", entry.bizType))
                put("outBizNo", System.currentTimeMillis().toString())
                put("sceneCode", entry.taskSceneCode)
                put("source", entry.source)
                put("taskType", taskType)
                put("version", VERSION)
            },
        )

    fun receiveTaskAward(
        taskType: String,
        entry: GoldenBeanEntry = MASTER_ENTRY,
    ): String =
        request(
            "com.alipay.antieptask.receiveTaskAwardantorchard",
            JSONObject().apply {
                put("bizInfo", JSONObject().put("bizType", entry.bizType))
                put("bizType", entry.bizType)
                put("ignoreLimit", true)
                put("sceneCode", entry.taskSceneCode)
                put("source", entry.source)
                put("taskType", taskType)
                put("version", VERSION)
            },
        )

    fun minerIndex(): String =
        request(
            "com.alipay.goldenbean.miner.index",
            JSONObject().apply {
                put("bizType", MASTER_ENTRY.bizType)
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
                put("bizType", MASTER_ENTRY.bizType)
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
                put("source", MASTER_ENTRY.source)
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
                put("source", MASTER_ENTRY.source)
                put("version", VERSION)
            },
        )

    fun listTopItemsByScene(): String =
        request(
            "com.alipay.antiep.listTopItemsByScene",
            JSONObject().apply {
                put("bizType", MASTER_ENTRY.bizType)
                put("itemSceneList", JSONArray().put("OPERATION_STRATEGY"))
                put("requestType", "RPC")
                put("sceneCode", "ANTORCHARD_JINDOU_MALL")
                put("source", MASTER_ENTRY.bizType)
                put("subChannel", MASTER_ENTRY.source)
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
                put("bizType", MASTER_ENTRY.bizType)
                put("pageSize", pageSize)
                put("requestType", "RPC")
                put("sceneCode", "ANTORCHARD_JINDOU_MALL")
                put("source", MASTER_ENTRY.bizType)
                put("startIndex", startIndex)
                put("version", VERSION)
            },
        )

    private fun request(
        method: String,
        requestData: JSONObject,
    ): String = RequestManager.requestString(method, JSONArray().put(requestData).toString())
}

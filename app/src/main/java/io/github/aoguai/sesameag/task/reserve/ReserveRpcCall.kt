package io.github.aoguai.sesameag.task.reserve

import io.github.aoguai.sesameag.hook.RequestManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 保护地RPC调用
 */
object ReserveRpcCall {

    private const val VERSION = "20230501"
    private const val VERSION2 = "20230522"

    /**
     * 查询可兑换的树木列表
     *
     * @return RPC响应字符串
     */
    @JvmStatic
    fun queryTreeItemsForExchange(): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.queryTreeItemsForExchange",
            "[{\"cityCode\":\"370100\",\"itemTypes\":\"\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"version\":\"$VERSION2\"}]"
        )
    }

    /**
     * 查询指定项目的兑换树木信息
     *
     * @param projectId 项目ID
     * @return RPC响应字符串
     */
    @JvmStatic
    fun queryTreeForExchange(projectId: String): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.queryTreeForExchange",
            "[{\"projectId\":\"$projectId\",\"version\":\"$VERSION\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\"}]"
        )
    }

    /**
     * 兑换树木
     *
     * @param projectId 项目ID
     * @return RPC响应字符串
     */
    @JvmStatic
    fun exchangeTree(projectId: String): String {
        val projectIdNum = projectId.toInt()
        return RequestManager.requestString(
            "alipay.antmember.forest.h5.exchangeTree",
            "[{\"projectId\":$projectIdNum,\"sToken\":\"${System.currentTimeMillis()}\",\"version\":\"$VERSION\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\"}]"
        )
    }

    fun applyGoldAnimalCert(projectId: String): String = RequestManager.requestString(
        "alipay.antforest.forest.h5.applyGoldAnimalCert",
        JSONArray().put(JSONObject().put("projectId", projectId)).toString(),
    )

    /**
     * 查询地图树苗
     *
     * @return RPC响应字符串
     */
    @JvmStatic
    fun queryAreaTrees(): String {
        return RequestManager.requestString("alipay.antmember.forest.h5.queryAreaTrees", "[{}]")
    }

    /**
     * 查询指定类型的可兑换树木
     *
     * @param applyActions 应用操作
     * @param itemTypes 物品类型
     * @return RPC响应字符串
     */
    @JvmStatic
    fun queryTreeItemsForExchange(applyActions: String, itemTypes: String): String {
        val args = "[{\"applyActions\":\"$applyActions\",\"itemTypes\":\"$itemTypes\"}]"
        return RequestManager.requestString("alipay.antforest.forest.h5.queryTreeItemsForExchange", args)
    }
}


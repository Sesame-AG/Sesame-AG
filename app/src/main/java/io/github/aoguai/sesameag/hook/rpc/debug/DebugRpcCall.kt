package io.github.aoguai.sesameag.hook.rpc.debug

import io.github.aoguai.sesameag.hook.RequestManager

/**
 * 调试RPC调用工具类
 */
object DebugRpcCall {
    /** 行走格子 */
    @JvmStatic
    fun walkGrid(): String? = RequestManager.requestString(
        "com.alipay.neverland.biz.rpc.walkGrid",
        "[{\"drilling\":false,\"mapId\":\"MF1\",\"source\":\"fuqiTown\"}]"
    )

    /** 小游戏 */
    @JvmStatic
    fun miniGameFinish(gameId: String, gameKey: String): String? = RequestManager.requestString(
        "com.alipay.neverland.biz.rpc.miniGameFinish",
        "[{\"gameId\":\"$gameId\",\"gameKey\":\"$gameKey\",\"mapId\":\"MF1\",\"score\":490,\"source\":\"fuqiTown\"}]"
    )

    @JvmStatic
    fun taskFinish(bizId: String): String? = RequestManager.requestString(
        "com.alipay.adtask.biz.mobilegw.service.task.finish",
        "[{\"bizId\":\"$bizId\"}]"
    )

    @JvmStatic
    fun queryAdFinished(bizId: String, scene: String): String? = RequestManager.requestString(
        "com.alipay.neverland.biz.rpc.queryAdFinished",
        "[{\"adBizNo\":\"$bizId\",\"scene\":\"$scene\",\"source\":\"fuqiTown\"}]"
    )

}


package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.hook.RequestManager

/**
 * 达达日报RPC调用
 */
object DadaDailyRpcCall {

    /**
     * 获取达达日报首页信息
     *
     * @param activityId 活动ID
     * @return RPC响应字符串
     */
    @JvmStatic
    fun home(activityId: String?): String {
        return RequestManager.requestString(
            "com.alipay.reading.game.dadaDaily.home",
            "[{\"activityId\":$activityId,\"dadaVersion\":\"1.3.0\",\"version\":1}]"
        )
    }

    /**
     * 提交达达日报答案
     *
     * @param activityId 活动ID
     * @param answer 答案
     * @param questionId 问题ID
     * @return RPC响应字符串
     */
    @JvmStatic
    fun submit(activityId: String?, answer: String?, questionId: Long): String {
        return RequestManager.requestString(
            "com.alipay.reading.game.dadaDaily.submit",
            "[{\"activityId\":$activityId,\"answer\":\"$answer\",\"dadaVersion\":\"1.3.0\",\"questionId\":$questionId,\"version\":1}]"
        )
    }
}


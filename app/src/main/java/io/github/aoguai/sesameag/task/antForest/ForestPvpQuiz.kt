package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import io.github.aoguai.sesameag.task.AnswerAI.AnswerAI
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.LogChannel
import io.github.aoguai.sesameag.util.ResChecker
import org.json.JSONObject

internal object ForestPvpQuiz {
    private const val TAG = "ForestPvpQuiz"

    fun run(forest: AntForest, home: JSONObject) {
        val identity = RpcDailyCircuit.captureIdentity()
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTFOREST_ENERGY_PVP_QUIZ_DONE)) return
        val slot = home.optJSONObject("quizAdSlotInfo") ?: return
        val adSlotId = slot.optString("adSlotId")
        val adId = slot.optString("defaultAdId").ifBlank { slot.optJSONArray("adIdList")?.optString(0).orEmpty() }
        require(adSlotId.isNotBlank() && adId.isNotBlank()) { "竞猜入口不完整 raw=$home" }

        val response = JSONObject(AntForestRpcCall.queryPvpQuizContent(adId, adSlotId))
            .let { it.optJSONObject("resData") ?: it }
        check(ResChecker.checkRes(TAG, "竞猜题目查询失败:", response)) { "竞猜题目查询失败 raw=$response" }
        require(response.optString("contentType") == "PVP_RECYCLE_QUIZ") { "竞猜题目类型无效 raw=$response" }
        val content = requireNotNull(response.optJSONObject("content")) { "竞猜题目缺失 raw=$response" }
        require(content.has("answered") && content.optString("adId") == adId) { "竞猜题目结构不完整 raw=$response" }

        if (content.getBoolean("answered")) {
            Log.forest("每日竞猜已答题")
        } else {
            val answers = requireNotNull(content.optJSONArray("answers")) { "竞猜选项缺失 raw=$content" }
            val options = (0 until answers.length()).map { answers.getJSONObject(it) }
            val question = content.optString("questionText")
            val questionId = content.optString("questionId")
            val waterEnergy = content.optLong("waterEnergy", -1L)
            require(question.isNotBlank() && questionId.isNotBlank() && waterEnergy >= 0L && options.isNotEmpty() &&
                options.all { it.optString("answerId").isNotBlank() && it.optString("text").isNotBlank() }) {
                "竞猜题目或选项不完整 raw=$content"
            }
            require(home.has("userEnergy")) { "竞猜缺少能量余额 raw=$home" }
            check(home.getLong("userEnergy") >= waterEnergy) { "竞猜需要浇水${waterEnergy}g，当前能量不足" }

            val texts = options.map { it.getString("text") }
            val suppliedAnswer = content.optJSONObject("correctAnswer")?.optString("answerId")
            val selected = options.firstOrNull { it.getString("answerId") == suppliedAnswer } ?: run {
                val answer = AnswerAI.getAnswer(question, texts, LogChannel.FOREST.loggerName)
                options.firstOrNull { it.getString("text") == answer } ?: options.first()
            }
            val submitted = JSONObject(AntForestRpcCall.submitPvpQuizAnswer(adId, questionId, selected.getString("answerId")))
                .let { it.optJSONObject("resData") ?: it }
            check(ResChecker.checkRes(TAG, "竞猜提交失败:", submitted)) {
                "竞猜提交失败[${forest.classifyForestTaskFailure(submitted)}] raw=$submitted"
            }
            val correctId = submitted.optJSONObject("correctAnswer")?.optString("answerId")
            val correct = options.firstOrNull { it.getString("answerId") == correctId }
            if (correct != null) AnswerAI.rememberAnswer(question, texts, correct.getString("text"), LogChannel.FOREST.loggerName)
            else if (submitted.has("correct") && !submitted.getBoolean("correct")) {
                AnswerAI.removeCachedAnswer(question, LogChannel.FOREST.loggerName)
            }
            require(submitted.has("triggered")) { "竞猜提交响应缺少发奖结果 raw=$submitted" }
            Log.forest("竞猜${if (submitted.optBoolean("correct")) "答对" else "已提交"}，" +
                "选择${selected.getString("answerId")}，浇水${waterEnergy}g")

            if (submitted.getBoolean("triggered")) {
                val outBizNo = submitted.optString("outBizNo")
                require(outBizNo.isNotBlank()) { "竞猜已触发奖品但未返回业务号 raw=$submitted" }
                val rewardResponse = JSONObject(AntForestRpcCall.queryPvpQuizReward(outBizNo))
                    .let { it.optJSONObject("resData") ?: it }
                check(ResChecker.checkRes(TAG, "竞猜奖品查询失败:", rewardResponse)) { "竞猜奖品查询失败 raw=$rewardResponse" }
                val reward = requireNotNull(rewardResponse.optJSONObject("combineHandlerVOMap")
                    ?.optJSONObject("queryWelfareLotteryResult")) { "竞猜奖品回查缺少业务结果 raw=$rewardResponse" }
                check(ResChecker.checkRes(TAG, "竞猜发奖失败:", reward)) { "竞猜发奖失败 raw=$rewardResponse" }
                val draws = requireNotNull(reward.optJSONArray("drawInfoList")) { "竞猜奖品列表缺失 raw=$rewardResponse" }
                val draw = requireNotNull((0 until draws.length()).mapNotNull { draws.optJSONObject(it) }
                    .firstOrNull { it.optString("bizId") == outBizNo }) { "竞猜未返回本次业务奖品 raw=$rewardResponse" }
                check(draw.has("canGet") && !draw.getBoolean("canGet") && draw.optString("orderNo").isNotBlank() &&
                    draw.optString("prizeId").isNotBlank() && draw.optInt("prizeNum") > 0) {
                    "竞猜自动发奖回执未确认 raw=$rewardResponse"
                }
                val refreshedHome = JSONObject(AntForestRpcCall.queryPvpHomeInfo()).let { it.optJSONObject("resData") ?: it }
                check(ResChecker.checkRes(TAG, "竞猜资产刷新失败:", refreshedHome)) { "竞猜资产刷新失败 raw=$refreshedHome" }
                val energyText = draw.optJSONObject("extInfo")?.optString("energyNum").orEmpty()
                if (energyText.isNotBlank()) {
                    val energy = energyText.removeSuffix("g").toLongOrNull()
                    require(energy != null && energy > 0L) { "竞猜奖品能量无效 raw=$draw" }
                    val before = submitted.optLong("userEnergy", -1L)
                    val after = refreshedHome.optLong("userEnergy", -1L)
                    check(before >= 0L && after >= before + energy * draw.getInt("prizeNum")) {
                        "竞猜能量到账未确认，业务号$outBizNo，提交后能量$before，当前$after raw=$draw"
                    }
                } else {
                    check(forest.queryPropList(true) != null) { "竞猜自动发奖回执已返回，道具刷新失败 raw=$draw" }
                }
                Log.forest("竞猜奖品已确认：${draw.optString("prizeName", draw.getString("prizeId"))}×${draw.getInt("prizeNum")}，" +
                    "业务号$outBizNo，订单${draw.getString("orderNo")}")
            } else {
                Log.forest("竞猜答题已结束，本次未触发奖品")
            }
        }
        AccountSessionCoordinator.withCurrentSession(identity) {
            Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_ENERGY_PVP_QUIZ_DONE, expectedUserId = identity.userId)
        }
    }
}

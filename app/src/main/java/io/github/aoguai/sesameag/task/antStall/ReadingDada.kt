package io.github.aoguai.sesameag.task.antStall

import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.task.AnswerAI.AnswerAI
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.LogChannel
import org.json.JSONObject

/**
 * @file ReadingDada.kt
 * @brief 阅读答题功能模块
 */
object ReadingDada {
    private const val TAG = "ReadingDada"

    val group: ModelGroup = ModelGroup.STALL

    enum class AnswerQuestionStatus {
        CONFIRMED_SUCCESS,
        CONFIRMED_WRONG,
        SUBMIT_SENT_WAIT_REFRESH,
        RPC_FAILED
    }

    data class AnswerQuestionResult(
        val status: AnswerQuestionStatus,
        val message: String = "",
        val raw: String = ""
    )

    /**
     * @brief 回答问题
     * @param bizInfo 业务信息JSON对象
     * @return 答题动作结果；任务是否最终推进由调用方结合任务刷新闭环确认
     */
    fun answerQuestion(bizInfo: JSONObject): AnswerQuestionResult {
        try {
            // 获取任务跳转URL
            val taskJumpUrl = bizInfo.optString("taskJumpUrl").takeIf { it.isNotEmpty() }
                ?: bizInfo.getString("targetUrl")

            // 解析活动ID
            val activityId = taskJumpUrl.split("activityId%3D")[1].split("%26")[0]

            // 解析外部业务ID
            val outBizId = if (taskJumpUrl.contains("outBizId%3D")) {
                taskJumpUrl.split("outBizId%3D")[1].split("%26")[0]
            } else {
                ""
            }

            // 获取问题
            val questionResponse = ReadingDadaRpcCall.getQuestion(activityId)
            val questionJson = JSONObject(questionResponse)

            if (questionJson.getString("resultCode") == "200") {
                val options = questionJson.getJSONArray("options")
                val question = questionJson.getString("title")
                val answerList = JsonUtil.jsonArrayToList(options)

                // 当前新村读书链路没有目标端预告答案缓存；AnswerAI 内部会先查已验证正确缓存，再请求 AI。
                var answer = AnswerAI.getAnswer(
                    question,
                    answerList,
                    LogChannel.STALL.loggerName
                )

                // AnswerAI 内部已做最终兜底；这里保留空值兜底，避免异常路径提交空答案。
                if (answer.isNullOrEmpty()) {
                    answer = options.getString(0)
                }

                // 提交答案
                val submitResponse = ReadingDadaRpcCall.submitAnswer(
                    activityId,
                    outBizId,
                    questionJson.getString("questionId"),
                    answer
                )

                val submitJson = JSONObject(submitResponse)
                val submitSuccess = submitJson.optString("resultCode") == "200"
                if (!submitSuccess) {
                    Log.error(TAG, "答题失败")
                    return AnswerQuestionResult(
                        status = AnswerQuestionStatus.RPC_FAILED,
                        message = submitJson.optString("resultDesc", "答题失败"),
                        raw = submitResponse
                    )
                }

                return if (submitJson.has("correct")) {
                    val confirmedCorrect = submitJson.optBoolean("correct", false)
                    if (confirmedCorrect) {
                        AnswerAI.rememberAnswer(question, answerList, answer, LogChannel.STALL.loggerName)
                        Log.stall("答题完成")
                        AnswerQuestionResult(status = AnswerQuestionStatus.CONFIRMED_SUCCESS, raw = submitResponse)
                    } else {
                        AnswerAI.removeCachedAnswer(question, LogChannel.STALL.loggerName)
                        Log.error(TAG, "答题完成但答案错误")
                        AnswerQuestionResult(status = AnswerQuestionStatus.CONFIRMED_WRONG, raw = submitResponse)
                    }
                } else {
                    Log.stall("答题已提交，等待任务刷新确认")
                    AnswerQuestionResult(
                        status = AnswerQuestionStatus.SUBMIT_SENT_WAIT_REFRESH,
                        raw = submitResponse
                    )
                }
            } else {
                Log.error(TAG, "获取问题失败")
                return AnswerQuestionResult(
                    status = AnswerQuestionStatus.RPC_FAILED,
                    message = questionJson.optString("resultDesc", "获取问题失败"),
                    raw = questionResponse
                )
            }
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "answerQuestion err:", e)
            return AnswerQuestionResult(
                status = AnswerQuestionStatus.RPC_FAILED,
                message = e.message.orEmpty(),
                raw = bizInfo.toString()
            )
        }
    }
}


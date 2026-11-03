package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.TaskExecutionReport
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max

/**
 * 6秒拼手速打地鼠。
 */
object WhackMole {
    private const val TAG = "WhackMole"
    private const val SOURCE = "senlinguangchangdadishu"

    @Volatile
    private var isRunning = false

    suspend fun startSuspend(): Boolean = withContext(Dispatchers.IO) {
        val report = TaskExecutionReport.current()
        report?.ensureCurrent()
        val state = TaskFlowExecutionState()
        if (isRunning) {
            Log.forest("⏭️ 打地鼠游戏正在运行中，跳过重复启动")
            report?.recordFlow(state, "蚂蚁森林", TAG, TaskFlowRunResult(
                completed = false, progressed = false, stopped = false, rounds = 0,
                deferredCount = 1, deferredReasonCounts = mapOf(DeferredReason.PREREQUISITE_PENDING to 1),
            ))
            return@withContext false
        }
        isRunning = true

        try {
            val executed = runWhackMole()
            report?.ensureCurrent()
            if (executed) {
                Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_WHACK_MOLE_EXECUTED)
            }
            val completed = executed || Status.hasFlagToday(StatusFlags.FLAG_ANTFOREST_WHACK_MOLE_EXECUTED)
            report?.recordFlow(state, "蚂蚁森林", TAG, TaskFlowRunResult(
                completed = completed, progressed = executed, stopped = !completed, rounds = 1,
                actionAttempted = true, failureCount = if (completed) 0 else 1,
            ))
            return@withContext completed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report?.recordException()
            Log.printStackTrace(TAG, "打地鼠异常: ", e)
            return@withContext false
        } finally {
            isRunning = false
            Log.forest("🎮 打地鼠运行状态已重置")
        }
    }

    private suspend fun runWhackMole(): Boolean {
        try {
            val response = JSONObject(AntForestRpcCall.startWhackMole(SOURCE))
            if (!ResChecker.checkRes(TAG, response)) {
                Log.forest(response.optString("resultDesc", "开始失败"))
                return false
            }
            if (!response.optBoolean("canPlayToday", true)) {
                Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_WHACK_MOLE_EXECUTED)
                logTodaySettleInfo(response)
                return false
            }

            val moleInfoArray = response.optJSONArray("moleInfo")
            val token = response.optString("token")
            if (moleInfoArray == null || moleInfoArray.length() == 0 || token.isEmpty()) {
                Log.error(TAG, "拼手速未返回可结算地鼠信息 raw=$response")
                return false
            }

            val config = response.optJSONObject("config") ?: run {
                Log.error(TAG, "拼手速开局缺少本局时间配置")
                return false
            }
            val durationMillis = config.getLong("duration") * 1000L
            val intervalMillis = config.getLong("intervalTime")
            require(durationMillis > 0L && intervalMillis > 0L) { "拼手速时间配置无效: $config" }
            val startedAt = System.currentTimeMillis()
            val settlesAt = startedAt + durationMillis
            val settlementIds = mutableListOf<String>()
            for (index in 0 until moleInfoArray.length()) {
                val appearsAt = startedAt + index * intervalMillis
                if (appearsAt >= settlesAt) break
                // 按本局服务端 intervalTime 对齐地鼠出现时点，避免在出现前发送击打请求。
                delay(max(0L, appearsAt - System.currentTimeMillis()))
                if (System.currentTimeMillis() >= settlesAt) break
                TaskExecutionReport.current()?.ensureCurrent()
                val mole = moleInfoArray.getJSONObject(index)
                val moleId = mole.getLong("id")
                if (mole.optString("type") == "NORMAL" && !mole.has("bubbleId")) {
                    settlementIds.add(moleId.toString())
                }
                if (mole.has("bubbleId")) {
                    try {
                        val whackResp = JSONObject(AntForestRpcCall.whackMole(moleId, token, SOURCE))
                        if (ResChecker.checkRes(TAG, whackResp)) {
                            Log.forest("森林能量⚡️[打地鼠:$moleId +${whackResp.optInt("energyAmount", 0)}g]")
                        } else {
                            TaskExecutionReport.current()?.recordException()
                            Log.error(TAG, "打地鼠命中失败 moleId=$moleId raw=$whackResp")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        TaskExecutionReport.current()?.recordException()
                        Log.printStackTrace(TAG, "打地鼠命中失败 moleId=$moleId", t)
                    }
                }
            }
            // 等满本局服务端 duration 后再结算；仅补足未经过的游戏时间。
            delay(max(0L, settlesAt - System.currentTimeMillis()))

            TaskExecutionReport.current()?.ensureCurrent()
            val settleResp = JSONObject(AntForestRpcCall.settlementWhackMole(token, settlementIds, SOURCE))
            if (ResChecker.checkRes(TAG, settleResp)) {
                val total = settleResp.optInt("totalEnergy", 0)
                Log.forest("森林能量⚡️[拼手速完成(打${settlementIds.size}个) 总能量+${total}g]")
                return true
            }
            Log.error(TAG, "拼手速结算未通过，本局配置=$config，地鼠=$moleInfoArray，普通地鼠=$settlementIds，开局响应后耗时=${System.currentTimeMillis() - startedAt}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.error(TAG, "拼手速出错: ${t.message}")
        }
        return false
    }

    private fun logTodaySettleInfo(response: JSONObject) {
        val settleInfo = response.optJSONObject("currentDaySettleInfoVO")
        val totalEnergy = settleInfo?.optInt("totalEnergy", 0) ?: 0
        val totalFriendNums = settleInfo?.optInt("totalFriendNums", 0) ?: 0
        if (totalEnergy > 0 || totalFriendNums > 0) {
            Log.forest("🎮 拼手速今日次数已用尽，今日已结算${totalFriendNums}个好友，总能量+${totalEnergy}g")
        } else {
            Log.forest("🎮 拼手速今日次数已用尽，跳过")
        }
    }
}

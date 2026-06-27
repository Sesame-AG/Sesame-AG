package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 绿色生活任务
 */
object GreenLife {

    private const val TAG = "GreenLife"
    private const val MARKET_OVER_LIMIT_CODE = "USER_SEND_OVER_LIMIT"
    private val unsupportedMarketWarnings = ConcurrentHashMap.newKeySet<String>()
    private val unsupportedMarketSourcesThisRound = ConcurrentHashMap.newKeySet<String>()

    private data class MarketFailure(
        val code: String,
        val message: String,
        val unsupported: Boolean
    )

    private enum class MarketConsultDecision {
        CAN_SEND,
        OVER_LIMIT,
        UNSUPPORTED,
        FAILED
    }

    private data class MarketConsultResult(
        val decision: MarketConsultDecision,
        val failure: MarketFailure? = null
    )

    private data class MarketAttemptResult(
        val collected: Boolean,
        val shouldContinue: Boolean
    )

    /**
     * 森林集市 - 通过逛街获取能量
     *
     * @param sourceType 来源类型
     */
    @JvmStatic
    fun ForestMarket(vararg sourceTypes: String): Boolean {
        val candidates = sourceTypes
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("GREEN_LIFE") }

        candidates.forEachIndexed { index, sourceType ->
            if (unsupportedMarketSourcesThisRound.contains(sourceType)) {
                Log.forest("森林集市[$sourceType] 本轮已判定来源未支持，跳过重复探测")
                return@forEachIndexed
            }
            val attemptResult = tryForestMarket(sourceType)
            if (attemptResult.collected) {
                return true
            }
            if (!attemptResult.shouldContinue) {
                return false
            }
            if (index < candidates.lastIndex) {
                Log.forest("森林集市[$sourceType] 未获得能量，继续尝试备用来源")
            }
        }
        return false
    }

    @JvmStatic
    fun resetForestMarketRound() {
        unsupportedMarketWarnings.clear()
        unsupportedMarketSourcesThisRound.clear()
    }

    private fun tryForestMarket(sourceType: String): MarketAttemptResult {
        try {
            val consultResponse = JSONObject(AntForestRpcCall.consultForSendEnergyByAction(sourceType))
            val consultResult = classifyConsultResult(consultResponse)
            when (consultResult.decision) {
                MarketConsultDecision.CAN_SEND -> Unit
                MarketConsultDecision.OVER_LIMIT -> {
                    Log.forest("森林集市[$sourceType] 今日可领额度已达上限")
                    return MarketAttemptResult(collected = false, shouldContinue = false)
                }
                MarketConsultDecision.UNSUPPORTED -> {
                    logMarketFailure(sourceType, "consult", consultResult.failure)
                    CoroutineUtils.sleepCompat(300)
                    return MarketAttemptResult(collected = false, shouldContinue = false)
                }
                MarketConsultDecision.FAILED -> {
                    logMarketFailure(sourceType, "consult", consultResult.failure)
                    CoroutineUtils.sleepCompat(300)
                    return MarketAttemptResult(collected = false, shouldContinue = true)
                }
            }

            var response = JSONObject(AntForestRpcCall.sendEnergyByAction(sourceType))
            if (!isSuccessResponse(response)) {
                logMarketFailure(sourceType, "send", extractMarketFailure(response))
                return MarketAttemptResult(collected = false, shouldContinue = true)
            }

            val sendData = response.optJSONObject("data")
            val receivedEnergyAmount = sendData?.let {
                it.optInt("receivedEnergyAmount", it.optInt("energyAmount", 0))
            } ?: 0
            if (receivedEnergyAmount > 0) {
                Log.forest("集市逛街🛍[来源:$sourceType][获得:能量${receivedEnergyAmount}g]")
                return MarketAttemptResult(collected = true, shouldContinue = false)
            }

            Log.forest("森林集市[$sourceType] 请求成功，但未获得能量")
            return MarketAttemptResult(collected = false, shouldContinue = true)
        } catch (t: Throwable) {
            Log.runtime(TAG, "ForestMarket err: sourceType=$sourceType")
            Log.printStackTrace(TAG, t)
            return MarketAttemptResult(collected = false, shouldContinue = true)
        }
    }

    private fun classifyConsultResult(response: JSONObject): MarketConsultResult {
        val data = response.optJSONObject("data")
        val failure = extractMarketFailure(response)
        if (!isSuccessResponse(response)) {
            return if (failure.unsupported) {
                MarketConsultResult(MarketConsultDecision.UNSUPPORTED, failure)
            } else {
                MarketConsultResult(MarketConsultDecision.FAILED, failure)
            }
        }

        val resultCode = data?.optString("resultCode").orEmpty()
        if (resultCode == MARKET_OVER_LIMIT_CODE) {
            return MarketConsultResult(MarketConsultDecision.OVER_LIMIT, failure)
        }

        val canSend = data?.optBoolean("canSendEnergy", false) == true
        if (canSend) {
            return MarketConsultResult(MarketConsultDecision.CAN_SEND)
        }

        return MarketConsultResult(MarketConsultDecision.FAILED, failure)
    }

    private fun isSuccessResponse(response: JSONObject): Boolean {
        if (response.optBoolean("success") || response.optBoolean("isSuccess")) {
            return true
        }

        val data = response.optJSONObject("data")
        val successCodes = listOf(
            response.opt("resultCode"),
            response.opt("code"),
            data?.opt("resultCode"),
            data?.opt("code")
        )
        return successCodes.any { code ->
            when (code) {
                is Int -> code == 200 || code == 100
                is String -> code.equals("SUCCESS", ignoreCase = true) || code == "100"
                else -> false
            }
        }
    }

    private fun extractMarketFailure(response: JSONObject): MarketFailure {
        val data = response.optJSONObject("data")
        val code = sequenceOf(
            response.optString("errorCode"),
            data?.optString("errorCode").orEmpty(),
            response.optString("code"),
            data?.optString("code").orEmpty(),
            response.optString("resultCode"),
            data?.optString("resultCode").orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val message = sequenceOf(
            response.optString("errorMsg"),
            data?.optString("errorMsg").orEmpty(),
            response.optString("resultDesc"),
            data?.optString("resultDesc").orEmpty(),
            response.optString("desc"),
            data?.optString("desc").orEmpty()
        ).firstOrNull { it.isNotBlank() } ?: "未返回可识别错误信息"
        val unsupported = code == "REMOTE_INVOKE_EXCEPTION" || message.contains("二级能量类目未生效")
        return MarketFailure(code, message, unsupported)
    }

    private fun logMarketFailure(sourceType: String, phase: String, failure: MarketFailure?) {
        if (failure == null) {
            Log.runtime(TAG, "森林集市[$sourceType][$phase] 失败: 未返回可识别错误信息")
            return
        }

        if (failure.unsupported) {
            unsupportedMarketSourcesThisRound.add(sourceType)
            val warnKey = "$sourceType|$phase|${failure.code}|${failure.message}"
            if (unsupportedMarketWarnings.add(warnKey)) {
                val detail = buildString {
                    if (failure.code.isNotBlank()) {
                        append("code=").append(failure.code)
                    }
                    if (failure.message.isNotBlank()) {
                        if (isNotEmpty()) {
                            append(", ")
                        }
                        append("msg=").append(failure.message)
                    }
                }
                Log.runtime(TAG, "森林集市[$sourceType][$phase] 备用来源未生效[未支持]: $detail")
            }
            return
        }

        val logSuffix = buildString {
            if (failure.code.isNotBlank()) {
                append("code=").append(failure.code)
            }
            if (failure.message.isNotBlank()) {
                if (isNotEmpty()) {
                    append(", ")
                }
                append("msg=").append(failure.message)
            }
        }
        val message = if (logSuffix.isNotEmpty()) {
            logSuffix
        } else {
            "未返回可识别错误信息"
        }
        Log.runtime(TAG, "森林集市[$sourceType][$phase] 失败: $message")
    }
}


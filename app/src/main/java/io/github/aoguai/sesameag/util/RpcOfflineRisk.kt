package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import org.json.JSONObject

object RpcOfflineRisk {
    private const val TAG = "风控"
    private val directRiskCodes = setOf("1009")
    private val offlineModeCodes = setOf("I07")
    private val adTrafficRiskCodes = setOf("217", "61002")

    private val riskKeywords = listOf(
        "需要验证",
        "需要驗證",
        "進行驗證",
        "进行验证",
        "保障你的正常存取",
        "保障你的正常访问",
        "访问受限",
        "訪問受限",
        "访问被拒绝",
        "訪問被拒絕",
        "访问异常",
        "訪問異常"
    )

    fun hasRiskKeyword(message: String): Boolean {
        if (message.isBlank()) {
            return false
        }
        return riskKeywords.any { keyword -> message.contains(keyword, ignoreCase = true) }
    }

    fun isOfflineRisk(code: String, message: String): Boolean {
        val normalizedCode = code.trim()
        val normalizedMessage = message.trim()
        if (offlineModeCodes.contains(normalizedCode) || normalizedMessage.contains("离线模式")) {
            return false
        }
        if (directRiskCodes.contains(normalizedCode)) {
            return true
        }
        return hasRiskKeyword(normalizedMessage)
    }

    fun isOfflineRisk(jsonObject: JSONObject): Boolean {
        val code = extractCode(jsonObject)
        val message = extractMessage(jsonObject)
        if (isOfflineRisk(code, message)) {
            return true
        }
        val stopObject = jsonObject.optJSONObject("_taskFlowStopObject")
        return stopObject != null && isOfflineRisk(stopObject)
    }

    fun isAdTrafficRisk(jsonObject: JSONObject): Boolean {
        if (!hasAdTrafficContext(jsonObject)) {
            return false
        }
        val signalText = buildAdTrafficSignalText(jsonObject)
        return adTrafficRiskCodes.any { code -> signalText.contains(code, ignoreCase = true) } ||
            signalText.contains("cheating traffic", ignoreCase = true)
    }

    fun enterOfflineIfNeeded(source: String, code: String, message: String): Boolean {
        if (!isOfflineRisk(code, message)) {
            return false
        }
        if (!ApplicationHookConstants.isOffline()) {
            val detail = buildDetail(source, code, message)
            // 风控/验证(含滑块)命中时显式落错误日志，保留触发响应的原始线索，
            // 避免仅静默进入离线模式而在日志中查不到任何风控痕迹。
            Log.error(TAG, "命中风控/验证拦截，进入离线 | $detail")
            ApplicationHookConstants.enterOffline(
                ApplicationHookConstants.getOfflineCooldownMs(),
                "auth_like",
                detail
            )
        }
        return true
    }

    fun enterOfflineIfNeeded(source: String, jsonObject: JSONObject): Boolean {
        val code = extractCode(jsonObject)
        val message = extractMessage(jsonObject)
        if (enterOfflineIfNeeded(source, code, message)) {
            return true
        }
        val stopObject = jsonObject.optJSONObject("_taskFlowStopObject") ?: return false
        return enterOfflineIfNeeded(source, stopObject)
    }

    fun extractCode(jsonObject: JSONObject): String {
        return sequenceOf(
            jsonObject.opt("resultCode")?.toString(),
            jsonObject.opt("errorCode")?.toString(),
            jsonObject.opt("error")?.toString(),
            jsonObject.opt("errorTip")?.toString(),
            jsonObject.opt("code")?.toString(),
            jsonObject.opt("retCode")?.toString(),
            jsonObject.opt("sspErrorCode")?.toString(),
            jsonObject.opt("errCode")?.toString()
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    fun extractMessage(jsonObject: JSONObject): String {
        return sequenceOf(
            jsonObject.optString("resultDesc"),
            jsonObject.optString("resultView"),
            jsonObject.optString("memo"),
            jsonObject.optString("desc"),
            jsonObject.optString("errorMessage"),
            jsonObject.optString("errorMsg"),
            jsonObject.optString("sspErrorMsg"),
            jsonObject.optString("message")
        ).filter { it.isNotBlank() }
            .joinToString(" | ")
    }

    private fun hasAdTrafficContext(jsonObject: JSONObject): Boolean {
        if (hasAdTrafficFields(jsonObject)) {
            return true
        }
        val resData = jsonObject.optJSONObject("resData") ?: return false
        return hasAdTrafficFields(resData)
    }

    private fun hasAdTrafficFields(jsonObject: JSONObject): Boolean {
        return jsonObject.has("xlightRequestId") ||
            jsonObject.has("sspErrorCode") ||
            jsonObject.has("sspErrorMsg") ||
            jsonObject.has("adList") ||
            jsonObject.has("playingResult") ||
            jsonObject.has("enableNewPlayingProto")
    }

    private fun buildAdTrafficSignalText(jsonObject: JSONObject): String {
        return buildString {
            appendAdTrafficSignals(jsonObject)
            jsonObject.optJSONObject("resData")?.let { resData ->
                append(' ')
                appendAdTrafficSignals(resData)
            }
        }
    }

    private fun StringBuilder.appendAdTrafficSignals(jsonObject: JSONObject) {
        append(extractCode(jsonObject))
        append(' ')
        append(extractMessage(jsonObject))
        append(' ')
        append(jsonObject.optString("retCode"))
        append(' ')
        append(jsonObject.optString("sspErrorCode"))
        append(' ')
        append(jsonObject.optString("sspErrorMsg"))
    }

    private fun buildDetail(source: String, code: String, message: String): String {
        return buildString {
            append(source)
            if (code.isNotBlank()) {
                append(" code=")
                append(code)
            }
            if (message.isNotBlank()) {
                append(" msg=")
                append(message.take(300))
            }
        }
    }
}

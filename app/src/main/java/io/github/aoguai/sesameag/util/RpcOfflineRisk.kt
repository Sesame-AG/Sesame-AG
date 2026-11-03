package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import org.json.JSONObject

object RpcOfflineRisk {
    private const val TAG = "风控"
    private val directRiskCodes = setOf("1009")
    private val offlineModeCodes = setOf("I07")
    private const val XLIGHT_TRAFFIC_RESTRICTION_CODE = "217"
    private const val XLIGHT_TRAFFIC_RESTRICTION_SSP_CODE = "61002"

    private val verificationKeywords =
        listOf(
            "需要验证", "需要驗證", "進行驗證", "进行验证",
            "保障你的正常存取", "保障你的正常访问",
        )

    private val recoverableVerificationKeywords = verificationKeywords +
        listOf("滑块", "滑塊", "验证码", "驗證碼", "captcha")

    private val hardBlockKeywords =
        listOf("访问受限", "訪問受限", "访问被拒绝", "訪問被拒絕")

    private val riskKeywords = verificationKeywords + hardBlockKeywords + listOf("访问异常", "訪問異常")

    /** 只用于本次真实响应；I07 的历史源错误不能归因到当前 RPC。 */
    fun isHardBlocked(jsonObject: JSONObject): Boolean {
        val code = extractCode(jsonObject).trim()
        val message = extractMessage(jsonObject)
        if (recoverableVerificationKeywords.any { message.contains(it, ignoreCase = true) }) return false
        if (code in offlineModeCodes || code == "2000" || message.contains("离线模式") || message.contains("登录超时")) return false
        if (jsonObject.optBoolean("success", false) || jsonObject.optBoolean("isSuccess", false)) return false
        return code in directRiskCodes || hardBlockKeywords.any { message.contains(it) }
    }

    fun hasRiskKeyword(message: String): Boolean {
        if (message.isBlank()) {
            return false
        }
        return riskKeywords.any { keyword -> message.contains(keyword, ignoreCase = true) }
    }

    fun isOfflineRisk(
        code: String,
        message: String,
    ): Boolean {
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
        // RPC 触发全局离线后，任务层收到的是 I07 包装响应；真实风控码保存在源错误字段中。
        val sourceCode = jsonObject.optString("offlineSourceCode")
        val sourceMessage = jsonObject.optString("offlineSourceMessage")
        if (sourceCode.isNotBlank() && isOfflineRisk(sourceCode, sourceMessage)) {
            return true
        }
        val stopObject = jsonObject.optJSONObject("_taskFlowStopObject")
        return stopObject != null && isOfflineRisk(stopObject)
    }

    fun isAdTrafficRisk(jsonObject: JSONObject): Boolean =
        sequenceOf(jsonObject, jsonObject.optJSONObject("resData"))
            .filterNotNull()
            .any { response ->
                response.optString("retCode") == XLIGHT_TRAFFIC_RESTRICTION_CODE &&
                    response.optString("sspErrorCode") == XLIGHT_TRAFFIC_RESTRICTION_SSP_CODE
            }

    fun enterOfflineIfNeeded(
        source: String,
        code: String,
        message: String,
    ): Boolean {
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
                detail,
            )
        }
        return true
    }

    fun enterOfflineIfNeeded(
        source: String,
        jsonObject: JSONObject,
    ): Boolean {
        val code = extractCode(jsonObject)
        val message = extractMessage(jsonObject)
        if (enterOfflineIfNeeded(source, code, message)) {
            return true
        }
        val stopObject = jsonObject.optJSONObject("_taskFlowStopObject") ?: return false
        return enterOfflineIfNeeded(source, stopObject)
    }

    fun extractCode(jsonObject: JSONObject): String =
        sequenceOf(
            jsonObject.opt("resultCode")?.toString(),
            jsonObject.opt("errorCode")?.toString(),
            jsonObject.opt("error")?.toString(),
            jsonObject.opt("errorTip")?.toString(),
            jsonObject.opt("code")?.toString(),
            jsonObject.opt("retCode")?.toString(),
            jsonObject.opt("sspErrorCode")?.toString(),
            jsonObject.opt("errCode")?.toString(),
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

    fun extractMessage(jsonObject: JSONObject): String =
        sequenceOf(
            jsonObject.optString("resultDesc"),
            jsonObject.optString("resultView"),
            jsonObject.optString("memo"),
            jsonObject.optString("desc"),
            jsonObject.optString("errorMessage"),
            jsonObject.optString("errorMsg"),
            jsonObject.optString("sspErrorMsg"),
            jsonObject.optString("message"),
        ).filter { it.isNotBlank() }
            .joinToString(" | ")

    private fun buildDetail(
        source: String,
        code: String,
        message: String,
    ): String =
        buildString {
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

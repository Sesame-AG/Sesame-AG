package io.github.aoguai.sesameag.util

import org.json.JSONObject

object RpcOfflineRisk {
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

    /**
     * 命中风控后按分级交给 [io.github.aoguai.sesameag.hook.rpc.RiskControlGuard]：
     * 滑块/验证码属于可恢复的软验证（暂停 + 探活 + 自动续跑），
     * 1009/访问受限属于硬拦截（更长暂停 + 更稀疏探活）。
     */
    fun enterOfflineIfNeeded(
        source: String,
        code: String,
        message: String,
    ): Boolean {
        if (!isOfflineRisk(code, message)) {
            return false
        }
        if (isVerificationRisk(message)) {
            io.github.aoguai.sesameag.hook.rpc.RiskControlGuard.onVerificationRisk(source, code, message)
        } else {
            io.github.aoguai.sesameag.hook.rpc.RiskControlGuard.onHardBlockRisk(source, code, message)
        }
        return true
    }
    /** 软验证判定：文案里出现滑块/验证码/需要验证等可恢复信号。 */
    fun isVerificationRisk(message: String): Boolean {
        if (message.isBlank()) {
            return false
        }
        return recoverableVerificationKeywords.any { message.contains(it, ignoreCase = true) }
    }

    /**
     * 风控分级入口：返回 null 表示这条响应不是风控/验证。
     * 1009 这类"请进行验证后继续"属于可恢复验证，不能按硬拦截处理。
     */
    fun classifyRisk(
        code: String,
        message: String,
    ): io.github.aoguai.sesameag.hook.rpc.RiskTier? {
        if (!isOfflineRisk(code, message)) {
            return null
        }
        return if (isVerificationRisk(message)) {
            io.github.aoguai.sesameag.hook.rpc.RiskTier.SOFT_VERIFY
        } else {
            io.github.aoguai.sesameag.hook.rpc.RiskTier.HARD_BLOCK
        }
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
}

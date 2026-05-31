package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import org.json.JSONObject

object RpcOfflineRisk {
    private val directRiskCodes = setOf("1009")
    private val offlineModeCodes = setOf("I07")

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

    fun enterOfflineIfNeeded(source: String, code: String, message: String): Boolean {
        if (!isOfflineRisk(code, message)) {
            return false
        }
        if (!ApplicationHookConstants.isOffline()) {
            ApplicationHookConstants.enterOffline(
                ApplicationHookConstants.getOfflineCooldownMs(),
                "auth_like",
                buildDetail(source, code, message)
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
            jsonObject.opt("code")?.toString()
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
            jsonObject.optString("message")
        ).filter { it.isNotBlank() }
            .joinToString(" | ")
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

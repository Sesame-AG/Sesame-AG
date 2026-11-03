package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal sealed class RpcRequestOutcome {
    data class Success(val body: String) : RpcRequestOutcome()
    data class Failure(val reason: String) : RpcRequestOutcome()
    data class Stopped(val body: String) : RpcRequestOutcome()
}

internal class ExchangeSettingsRefreshContext(val identity: AccountSessionIdentity) {
    private val methods = linkedSetOf<String>()
    var failed: Boolean = false

    fun recordMethod(method: String) {
        methods.add(method)
    }

    fun observeResponse(response: JSONObject) {
        val code = response.optString("resultCode")
        val failedExplicitly = response.opt("success") == false || response.opt("isSuccess") == false ||
            response.has("error") || RpcDailyCircuit.isStopResponse(response)
        val success = response.optBoolean("success") || response.optBoolean("isSuccess") ||
            code == "100" || code == "200" || code.equals("SUCCESS", ignoreCase = true) ||
            response.optString("code") == "100000000" ||
            response.optString("memo").equals("SUCCESS", ignoreCase = true) ||
            sequenceOf("resultDesc", "desc", "resultView").any { key ->
                response.optString(key) in setOf("成功", "处理成功")
            }
        if (failedExplicitly || !success) failed = true
    }

    fun complete() {
        check(RpcDailyCircuit.isCurrent(identity)) { "兑换列表刷新期间账号会话已变化" }
        check(!failed) { "兑换列表未完整刷新，保留 RPC 每日停止标识" }
        methods.forEach { RpcDailyCircuit.clearBlockedToday(identity.userId, it) }
    }
}

internal class RpcLogLimiter(private val intervalMs: Long) {
    private val lastLogAtMs = AtomicLong(0)

    fun shouldLog(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastLogAtMs.get()
        return if (last == 0L || now - last >= intervalMs) {
            lastLogAtMs.set(now)
            true
        } else {
            false
        }
    }
}

internal object RpcFallbackJsonFactory {
    fun buildDailyRiskStop(method: String?): String = JSONObject().apply {
        put("success", false)
        put("resultCode", "I07")
        put("memo", "该 RPC 今日因硬阻塞停止自动请求")
        put("resultDesc", "该 RPC 今日因硬阻塞停止自动请求")
        put("rpcMethod", method.orEmpty())
        put("offlineReason", RpcDailyCircuit.STOP_REASON)
    }.toString()

    fun build(reason: String, method: String?): String {
        val message = "$reason，请稍后再试"
        val currentOfflineReason = ApplicationHookConstants.offlineReason
        val currentOfflineDetail = ApplicationHookConstants.offlineReasonDetail
        val currentOfflineUntilMs = ApplicationHookConstants.offlineUntilMs
        val authLikeSnapshot = ApplicationHookConstants.getLatestAuthLikeOfflineSnapshot()
        return try {
            JSONObject().apply {
                put("success", false)
                put("memo", message)
                put("resultDesc", message)
                put("desc", message)
                put("resultCode", "I07")
                if (!method.isNullOrBlank()) {
                    put("rpcMethod", method)
                }
                if (!currentOfflineReason.isNullOrBlank()) {
                    put("offlineReason", currentOfflineReason)
                } else if (authLikeSnapshot != null) {
                    put("offlineReason", "auth_like")
                }
                if (!currentOfflineDetail.isNullOrBlank()) {
                    put("offlineReasonDetail", currentOfflineDetail)
                } else if (authLikeSnapshot != null && authLikeSnapshot.detail.isNotBlank()) {
                    put("offlineReasonDetail", authLikeSnapshot.detail)
                }
                if (currentOfflineUntilMs > 0L) {
                    put("offlineUntilMs", currentOfflineUntilMs)
                } else if (authLikeSnapshot?.active == true && authLikeSnapshot.untilMs > 0L) {
                    put("offlineUntilMs", authLikeSnapshot.untilMs)
                }
                if (authLikeSnapshot != null) {
                    if (authLikeSnapshot.method.isNotBlank()) {
                        put("offlineSourceMethod", authLikeSnapshot.method)
                    }
                    if (authLikeSnapshot.code.isNotBlank()) {
                        put("offlineSourceCode", authLikeSnapshot.code)
                    }
                    if (authLikeSnapshot.message.isNotBlank()) {
                        put("offlineSourceMessage", authLikeSnapshot.message)
                    }
                }
            }.toString()
        } catch (_: Throwable) {
            """{"success":false,"memo":"$message","resultDesc":"$message","desc":"$message","resultCode":"I07"}"""
        }
    }
}

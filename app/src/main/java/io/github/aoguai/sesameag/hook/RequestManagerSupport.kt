package io.github.aoguai.sesameag.hook

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal sealed class RpcRequestOutcome {
    data class Success(val body: String) : RpcRequestOutcome()
    data class Failure(val reason: String) : RpcRequestOutcome()
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

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
            }.toString()
        } catch (_: Throwable) {
            """{"success":false,"memo":"$message","resultDesc":"$message","desc":"$message","resultCode":"I07"}"""
        }
    }
}

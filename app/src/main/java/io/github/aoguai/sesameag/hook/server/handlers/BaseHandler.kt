package io.github.aoguai.sesameag.hook.server.handlers

import io.github.aoguai.sesameag.hook.server.ServerCommon
import io.github.aoguai.sesameag.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

abstract class BaseHandler(private val secretToken: String) : HttpHandler {
    companion object {
        private const val TAG = "BaseHandler"
    }

    // 直接使用单例
    protected val mapper = ServerCommon.jsonMapper

    /**
     * 模板方法：统一处理鉴权和异常
     */
    final override fun handle(session: IHTTPSession, body: String?): Response {
        return try {
            if (!verifyToken(session)) {
                return unauthorized()
            }

            when (session.method) {
                Method.GET -> onGet(session)
                Method.POST -> onPost(session, body)
                else -> methodNotAllowed()
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "HTTP handler failed", e)
            json(Response.Status.INTERNAL_ERROR, mapOf("status" to "error", "message" to (e.message ?: "Unknown error")))
        }
    }

    private fun verifyToken(session: IHTTPSession): Boolean {
        // 失败关闭：没有配置 token 时一律拒绝，而不是"默认放行"。
        // 该接口能直接驱动宿主业务 RPC，放行等于把账号交给本机任意应用。
        if (secretToken.isBlank()) {
            Log.error(TAG, "调试接口未配置 token，已拒绝请求 uri=${session.uri}")
            return false
        }

        val authHeader = session.headers["authorization"] ?: return false
        // 允许 Bearer 或直接 Token
        val token = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
            authHeader.substring(7).trim()
        } else {
            authHeader.trim()
        }
        if (token.isEmpty()) {
            return false
        }
        // 常量时间比较，避免按字节提前返回带来的时序侧信道。
        return MessageDigest.isEqual(
            token.toByteArray(StandardCharsets.UTF_8),
            secretToken.toByteArray(StandardCharsets.UTF_8),
        )
    }

    // 默认实现改为 405 Method Not Allowed，而不是 404 Not Found
    open fun onGet(session: IHTTPSession): Response = methodNotAllowed()
    open fun onPost(session: IHTTPSession, body: String?): Response = methodNotAllowed()

    // --- 响应辅助方法 ---

    protected fun json(status: Response.Status, data: Any): Response {
        val jsonText = data as? String ?: mapper.writeValueAsString(data)
        return NanoHTTPD.newFixedLengthResponse(status, ServerCommon.MIME_JSON, jsonText)
    }

    protected fun ok(data: Any): Response = json(Response.Status.OK, data)

    protected fun badRequest(message: String): Response =
        json(Response.Status.BAD_REQUEST, mapOf("status" to "error", "message" to message))

    protected fun unauthorized(): Response =
        json(Response.Status.UNAUTHORIZED, mapOf("status" to "unauthorized"))

    protected fun methodNotAllowed(): Response =
        json(Response.Status.METHOD_NOT_ALLOWED, mapOf("status" to "method_not_allowed"))

    protected fun notFound(): Response =
        json(Response.Status.NOT_FOUND, mapOf("status" to "not_found"))
}


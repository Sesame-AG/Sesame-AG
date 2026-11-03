package io.github.aoguai.sesameag.hook.server.handlers

import io.github.aoguai.sesameag.hook.server.ServerCommon
import io.github.aoguai.sesameag.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response

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
        // 如果未设置 Token，默认通过（或者是禁止？视需求而定，这里假设未设置则不验证）
        if (secretToken.isBlank()) return true

        val authHeader = session.headers["authorization"] ?: return false
        // 允许 Bearer 或直接 Token
        val token = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
            authHeader.substring(7).trim()
        } else {
            authHeader.trim()
        }
        return token == secretToken
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


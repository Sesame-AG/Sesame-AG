package io.github.aoguai.sesameag.hook.server.handlers

import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.hook.server.ServerCommon.MIME_JSON
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

class DebugHandler(secretToken: String) : BaseHandler(secretToken) {

    override fun onPost(session: IHTTPSession, body: String?): Response {
        if (body.isNullOrBlank()) {
            return badRequest("Empty body")
        }

        val request: RpcRequest = try {
            mapper.readValue(body, RpcRequest::class.java)
        } catch (e: Exception) {
            return badRequest("Invalid JSON: ${e.message}")
        }

        val dataStr = request.getRequestDataString(mapper)

        if (request.methodName.isBlank() || dataStr.isBlank()) {
            return badRequest("Fields cannot be empty")
        }

        return try {
            val result = RequestManager.requestString(request.methodName, dataStr)

            if (result.isBlank()) {
                json(Response.Status.OK, mapOf("status" to "empty"))
            } else {
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_JSON, result)
            }
        } catch (e: Exception) {
            badRequest("RPC Error: ${e.message}")
        }
    }
}

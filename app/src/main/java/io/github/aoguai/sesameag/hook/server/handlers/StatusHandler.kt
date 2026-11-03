package io.github.aoguai.sesameag.hook.server.handlers

import io.github.aoguai.sesameag.hook.ModuleStatusReporter
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

class StatusHandler(secretToken: String) : BaseHandler(secretToken) {

    override fun onGet(session: IHTTPSession): Response {
        val snapshot = ModuleStatusReporter.getStatusSnapshot(refresh = true, reason = "http_status")
        return ok(snapshot)
    }
}



package io.github.aoguai.sesameag.hook.rpc.capture

import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.HookSender
import io.github.aoguai.sesameag.hook.RuntimeIdentityGuard
import io.github.aoguai.sesameag.hook.TokenHooker
import io.github.aoguai.sesameag.hook.XposedEnv
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.io.File
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

object RpcTrafficCapture {
    private const val TAG = "RpcTrafficCapture"
    private const val CAPTURE_SESSION_FILE_NAME = "rpc_capture_session.json"
    private const val PENDING_RESPONSE_TIMEOUT_MS = 60_000L

    private enum class TrafficSource {
        ARIVER_RPC,
        H5_RPC,
        MODULE_ACTIVE
    }

    private enum class TrafficPhase {
        REQUEST,
        RESPONSE,
        ERROR,
        SESSION_START
    }

    private data class RpcTrafficEvent(
        val title: String,
        val phase: TrafficPhase,
        val source: TrafficSource,
        val method: String,
        val requestId: String? = null,
        val attemptId: String? = null,
        val payload: String? = null,
        val elapsedMs: Long = -1L,
        val note: String? = null,
        val correlationStatus: String? = null,
    )

    private data class PendingHostRequest(
        val requestId: String,
        val method: String,
        val requestPayload: String?,
        val responseBeforeRequest: String? = null,
        val startedAtMs: Long,
        val source: TrafficSource
    )

    private class WeakIdentityKey(
        callback: Any,
        queue: ReferenceQueue<Any>? = null,
    ) : WeakReference<Any>(callback, queue) {
        private val identityHashCode = System.identityHashCode(callback)

        override fun hashCode(): Int = identityHashCode

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WeakIdentityKey) return false
            val callback = get() ?: return false
            return callback === other.get()
        }
    }

    private data class CaptureEnabledCache(
        val fingerprint: String,
        val enabled: Boolean,
        val expiresAtMs: Long,
    )

    private data class CaptureSessionCache(
        val sessionId: String,
        val lastModifiedMs: Long,
        val expiresAtMs: Long,
    )

    private val installLock = Any()
    private val ariverRequestHookInstalled = AtomicBoolean(false)
    private val mtopRequestHookInstalled = AtomicBoolean(false)
    private val ariverResponseHookInstalled = AtomicBoolean(false)
    private val h5HookInstalled = AtomicBoolean(false)
    private val pendingAriverRequestQueue = ReferenceQueue<Any>()
    private val pendingAriverRequests = ConcurrentHashMap<WeakIdentityKey, ConcurrentLinkedQueue<PendingHostRequest>>()
    private val pendingAriverRequestsById = ConcurrentHashMap<String, PendingHostRequest>()
    private val eventSequence = AtomicLong(0L)
    private val sessionLock = Any()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var sessionStarted = false

    @Volatile
    private var captureEnabledProvider: () -> Boolean = { BaseModel.debugMode.value == true }

    @Volatile
    private var captureEnabledCache: CaptureEnabledCache? = null

    @Volatile
    private var captureSessionCache: CaptureSessionCache? = null

    private val structuredReferenceKeys = setOf(
        "taskType", "sceneCode", "taskStatus", "taskProgress", "taskRequire", "taskId", "taskGroupId",
        "game_id", "gameAppId", "algGameId", "actionType", "finishTaskActionType", "chInfo", "playTime",
        "resultCode", "resultDesc", "success", "awardCount", "incAwardCount", "rightsTimes",
        "alreadyReceiveAwardCount", "receiveStatus", "propGroup", "propType", "propId", "holdsNum",
        "userDayLeftAmount", "type", "apiName", "apiVersion", "appId", "outBizNo", "adBizNo", "taskToken"
    )

    private val structuredReferenceContainers = listOf(
        "", "taskBaseInfo", "taskInfo", "prodPlayParam", "prodPlayParam.taskCategorization", "data", "result",
        "params", "fetchedData", "fetchedData.params"
    )

    fun install(
        classLoader: ClassLoader,
        enabledProvider: () -> Boolean = { BaseModel.debugMode.value == true },
    ): Boolean {
        synchronized(installLock) {
            captureEnabledProvider = enabledProvider
            var installedAny = false
            val ariverRequestInstalled = installAriverRequestHook(classLoader)
            val mtopRequestInstalled = installMtopRequestHook(classLoader)
            if (ariverRequestInstalled || mtopRequestInstalled) {
                installedAny = true
                installAriverResponseHook(classLoader)
            }
            if (installH5Hook(classLoader)) {
                installedAny = true
            }
            if (installedAny) {
                if (RuntimeIdentityGuard.isMainProcess() && isCaptureEnabled()) {
                    activeSessionId()
                }
                Log.runtime(TAG, "已安装 RPC 抓包 Hook")
            } else {
                Log.runtime(TAG, "未找到可安装的 RPC 抓包 Hook")
            }
            return installedAny
        }
    }

    /**
     * Lite processes do not initialize the task runtime. Read the existing capture switch directly
     * from persistent configuration so they can remain capture-only.
     */
    fun installForCaptureOnlyProcess(classLoader: ClassLoader): Boolean =
        install(classLoader, ::isCaptureEnabledInPersistentConfig)

    fun newRequestId(): String = UUID.randomUUID().toString()

    fun newAttemptId(): String = UUID.randomUUID().toString()

    fun recordModuleRequest(method: String?, payload: String?): String =
        recordModuleRequest(method, payload, newRequestId())

    fun recordModuleRequest(method: String?, payload: String?, requestId: String): String {
        if (!isCaptureEnabled() || method.isNullOrBlank()) {
            return requestId
        }
        emit(
            RpcTrafficEvent(
                title = "Module RPC",
                phase = TrafficPhase.REQUEST,
                source = TrafficSource.MODULE_ACTIVE,
                method = method,
                requestId = requestId,
                payload = payload,
            ),
        )
        return requestId
    }

    fun recordModuleAttempt(
        method: String?,
        payload: String?,
        requestId: String,
        attemptId: String,
        attempt: Int,
    ) {
        if (!isCaptureEnabled() || method.isNullOrBlank()) {
            return
        }
        emit(
            RpcTrafficEvent(
                title = "Module RPC",
                phase = TrafficPhase.REQUEST,
                source = TrafficSource.MODULE_ACTIVE,
                method = method,
                requestId = requestId,
                attemptId = attemptId,
                payload = payload,
                note = "attempt=$attempt",
            ),
        )
    }

    fun recordModuleResponse(
        method: String?,
        payload: String?,
        elapsedMs: Long,
        requestId: String?,
        attemptId: String?,
    ) {
        if (!isCaptureEnabled() || method.isNullOrBlank()) {
            return
        }
        emit(
            RpcTrafficEvent(
                title = "Module RPC",
                phase = TrafficPhase.RESPONSE,
                source = TrafficSource.MODULE_ACTIVE,
                method = method,
                requestId = requestId,
                attemptId = attemptId,
                payload = payload,
                elapsedMs = elapsedMs,
                correlationStatus = "MATCHED",
            ),
        )
    }

    fun recordModuleError(
        method: String?,
        payload: String?,
        elapsedMs: Long,
        note: String,
        requestId: String?,
        attemptId: String?,
    ) {
        if (!isCaptureEnabled() || method.isNullOrBlank()) {
            return
        }
        emit(
            RpcTrafficEvent(
                title = "Module RPC",
                phase = TrafficPhase.ERROR,
                source = TrafficSource.MODULE_ACTIVE,
                method = method,
                requestId = requestId,
                attemptId = attemptId,
                payload = payload,
                elapsedMs = elapsedMs,
                note = note,
                correlationStatus = "MATCHED",
            ),
        )
    }

    private fun installAriverRequestHook(classLoader: ClassLoader): Boolean {
        if (ariverRequestHookInstalled.get()) {
            return true
        }
        return runCatching {
            val bridgeClass = Class.forName(
                "com.alibaba.ariver.commonability.network.rpc.RpcBridgeExtension",
                false,
                classLoader
            )
            val rpcMethod = findMethod(bridgeClass, "rpc", 16, String::class.java)
                ?: error("RpcBridgeExtension#rpc 未找到")
            ApplicationHook.requireXposedInterface().hook(rpcMethod).intercept { chain ->
                val args = chain.args
                val methodName = args.getOrNull(0) as? String
                val callback = args.getOrNull(15)
                val captureEnabled = isCaptureEnabled() && !methodName.isNullOrBlank()
                val requestPayload = if (captureEnabled) args.getOrNull(4)?.toString() else null
                dispatchTokenHook(methodName, requestPayload)

                val pending = if (captureEnabled) {
                    PendingHostRequest(
                        requestId = newRequestId(),
                        method = methodName ?: "unknown",
                        requestPayload = requestPayload,
                        responseBeforeRequest = callback?.let(::readAriverResponseField),
                        startedAtMs = System.currentTimeMillis(),
                        source = TrafficSource.ARIVER_RPC,
                    ).also { request ->
                        recordHostRequest(request)
                        if (callback != null) {
                            putPendingAriverRequest(callback, request)
                        } else {
                            recordHostError(
                                pending = request,
                                note = "callback_missing",
                                correlationStatus = "NO_CALLBACK",
                            )
                        }
                    }
                } else {
                    null
                }

                val result = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    pending?.let { request ->
                        callback?.let { removePendingAriverRequest(it, request.requestId) }
                        recordHostError(
                            pending = request,
                            note = "RpcBridgeExtension.rpc threw ${t.javaClass.simpleName}",
                        )
                    }
                    throw t
                }
                if (callback != null) {
                    tryRecordAriverResponseFromField(callback)
                }
                result
            }
            ariverRequestHookInstalled.set(true)
            Log.runtime(TAG, "已安装 RpcBridgeExtension#rpc 抓包")
            true
        }.onFailure {
            Log.runtime(TAG, "安装 RpcBridgeExtension#rpc 抓包失败: ${it.message}")
        }.getOrDefault(false)
    }

    private fun installMtopRequestHook(classLoader: ClassLoader): Boolean {
        if (mtopRequestHookInstalled.get()) {
            return true
        }
        return runCatching {
            val bridgeClass = Class.forName(
                "com.alibaba.ariver.jsapi.mtop.MtopBridgeExtention",
                false,
                classLoader,
            )
            val jsonClass = Class.forName(General.JSON_OBJECT_NAME, false, classLoader)
            val callbackClass = Class.forName(
                "com.alibaba.ariver.engine.api.bridge.extension.BridgeCallback",
                false,
                classLoader,
            )
            val mtopMethod = (bridgeClass.methods.asSequence() + bridgeClass.declaredMethods.asSequence())
                .firstOrNull { method ->
                    method.name == "sendMtop" &&
                        method.parameterTypes.any(jsonClass::isAssignableFrom) &&
                        method.parameterTypes.any(callbackClass::isAssignableFrom)
                }
                ?.apply { isAccessible = true }
                ?: error("MtopBridgeExtention#sendMtop 未找到可关联的请求签名")
            val payloadIndex = mtopMethod.parameterTypes.indexOfFirst(jsonClass::isAssignableFrom)
            val callbackIndex = mtopMethod.parameterTypes.indexOfFirst(callbackClass::isAssignableFrom)
            ApplicationHook.requireXposedInterface().hook(mtopMethod).intercept { chain ->
                val args = chain.args
                val callback = args.getOrNull(callbackIndex)
                val requestPayload = args.getOrNull(payloadIndex)?.toString()
                val pending = if (isCaptureEnabled()) {
                    PendingHostRequest(
                        requestId = newRequestId(),
                        method = extractMtopApiName(requestPayload) ?: "mtop.sendMtop",
                        requestPayload = requestPayload,
                        responseBeforeRequest = callback?.let(::readAriverResponseField),
                        startedAtMs = System.currentTimeMillis(),
                        source = TrafficSource.ARIVER_RPC,
                    ).also { request ->
                        recordHostRequest(request)
                        if (callback != null) {
                            putPendingAriverRequest(callback, request)
                        } else {
                            recordHostError(
                                pending = request,
                                note = "MtopBridgeExtention.sendMtop callback_missing",
                                correlationStatus = "NO_CALLBACK",
                            )
                        }
                    }
                } else {
                    null
                }

                val result = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    pending?.let { request ->
                        callback?.let { removePendingAriverRequest(it, request.requestId) }
                        recordHostError(
                            pending = request,
                            note = "MtopBridgeExtention.sendMtop threw ${t.javaClass.simpleName}",
                        )
                    }
                    throw t
                }
                callback?.let(::tryRecordAriverResponseFromField)
                result
            }
            mtopRequestHookInstalled.set(true)
            Log.runtime(TAG, "已安装 MtopBridgeExtention#sendMtop 抓包")
            true
        }.onFailure {
            Log.runtime(TAG, "安装 MtopBridgeExtention#sendMtop 抓包失败: ${it.message}")
        }.getOrDefault(false)
    }

    private fun installAriverResponseHook(classLoader: ClassLoader): Boolean {
        if (ariverResponseHookInstalled.get()) {
            return true
        }
        return runCatching {
            val callbackClass = Class.forName(
                "com.alibaba.ariver.engine.common.bridge.internal.DefaultBridgeCallback",
                false,
                classLoader
            )
            val jsonClass = Class.forName(General.JSON_OBJECT_NAME, false, classLoader)
            val sendJsonResponseMethod = findMethod(callbackClass, "sendJSONResponse", 1, jsonClass)
                ?: error("DefaultBridgeCallback#sendJSONResponse 未找到")
            ApplicationHook.requireXposedInterface().hook(sendJsonResponseMethod).intercept { chain ->
                val callback = chain.getThisObject()
                val responsePayload = chain.args.getOrNull(0)?.toString()
                val matched = if (callback != null && !responsePayload.isNullOrBlank()) {
                    // remove(requestId) in recordHostResponse guarantees that a reused callback or
                    // a second response path cannot produce a duplicate terminal event.
                    recordPendingAriverResponse(callback, responsePayload)
                } else {
                    false
                }
                if (!matched && isCaptureEnabled() && !responsePayload.isNullOrBlank()) {
                    emit(
                        RpcTrafficEvent(
                            title = "Host RPC",
                            phase = TrafficPhase.RESPONSE,
                            source = TrafficSource.ARIVER_RPC,
                            method = extractMtopApiName(responsePayload) ?: "unknown",
                            payload = responsePayload,
                            correlationStatus = "UNMATCHED",
                        ),
                    )
                }
                chain.proceed()
            }
            ariverResponseHookInstalled.set(true)
            Log.runtime(TAG, "已安装 DefaultBridgeCallback#sendJSONResponse 抓包")
            true
        }.onFailure {
            Log.runtime(TAG, "安装 DefaultBridgeCallback#sendJSONResponse 抓包失败: ${it.message}")
        }.getOrDefault(false)
    }

    private fun installH5Hook(classLoader: ClassLoader): Boolean {
        if (h5HookInstalled.get()) {
            return true
        }
        return runCatching {
            val rpcUtilClass = Class.forName(
                "com.alipay.mobile.nebulaappproxy.api.rpc.H5RpcUtil",
                false,
                classLoader
            )
            val rpcCallMethod = findMethod(rpcUtilClass, "rpcCall", 13, String::class.java)
                ?: findMethod(rpcUtilClass, "rpcCall", 12, String::class.java)
                ?: error("H5RpcUtil#rpcCall 未找到")
            ApplicationHook.requireXposedInterface().hook(rpcCallMethod).intercept { chain ->
                val args = chain.args
                val methodName = args.getOrNull(0) as? String
                val captureEnabled = isCaptureEnabled() && !methodName.isNullOrBlank()
                val requestPayload = if (captureEnabled) args.getOrNull(1)?.toString() else null
                val pending = if (captureEnabled) {
                    PendingHostRequest(
                        requestId = newRequestId(),
                        method = methodName ?: "unknown",
                        requestPayload = requestPayload,
                        startedAtMs = System.currentTimeMillis(),
                        source = TrafficSource.H5_RPC,
                    ).also(::recordHostRequest)
                } else {
                    null
                }

                val result = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    pending?.let { request ->
                        recordHostError(
                            pending = request,
                            note = "H5RpcUtil.rpcCall threw ${t.javaClass.simpleName}",
                        )
                    }
                    throw t
                }
                pending?.let { request ->
                    val responsePayload = extractH5Response(result)
                    if (responsePayload != null) {
                        recordHostResponse(request, responsePayload)
                    } else {
                        recordHostError(
                            pending = request,
                            note = "H5Response.getResponse 返回空",
                            correlationStatus = "EMPTY_RESPONSE",
                        )
                    }
                }
                result
            }
            h5HookInstalled.set(true)
            Log.runtime(TAG, "已安装 H5RpcUtil#rpcCall 抓包")
            true
        }.onFailure {
            Log.runtime(TAG, "安装 H5RpcUtil#rpcCall 抓包失败: ${it.message}")
        }.getOrDefault(false)
    }

    private fun dispatchTokenHook(methodName: String?, requestPayload: String?) {
        if (RuntimeIdentityGuard.isCaptureOnlyProcess() || !isCaptureEnabled()) {
            return
        }
        if (methodName.isNullOrBlank() || requestPayload.isNullOrBlank()) {
            return
        }
        if (!requestPayload.trim().startsWith("{")) {
            return
        }
        runCatching {
            TokenHooker.handleRpc(methodName, JSONObject(requestPayload))
        }.onFailure {
            Log.runtime(TAG, "TokenHooker 处理失败: ${it.message}")
        }
    }

    private fun extractMtopApiName(payload: String?): String? {
        if (payload.isNullOrBlank()) {
            return null
        }
        return runCatching {
            val root = JSONObject(payload)
            listOf("apiName", "params.apiName", "fetchedData.params.apiName")
                .firstNotNullOfOrNull { path -> stringAtPath(root, path) }
        }.getOrNull()
    }

    private fun stringAtPath(root: JSONObject, path: String): String? {
        val separator = path.lastIndexOf('.')
        val container = if (separator < 0) {
            root
        } else {
            objectAtPath(root, path.substring(0, separator)) ?: return null
        }
        return container.optString(path.substring(separator + 1))
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun tryRecordAriverResponseFromField(callback: Any) {
        if (!isCaptureEnabled()) {
            removePendingAriverRequests(callback)
            return
        }
        val pending = peekPendingAriverRequest(callback) ?: return
        val responsePayload = readAriverResponseField(callback)
        if (!responsePayload.isNullOrBlank() && responsePayload != pending.responseBeforeRequest) {
            recordPendingAriverResponse(callback, responsePayload)
        }
    }

    private fun recordPendingAriverResponse(callback: Any, responsePayload: String): Boolean {
        if (!isCaptureEnabled()) {
            removePendingAriverRequests(callback)
            return false
        }
        val pending = removePendingAriverRequest(callback) ?: return false
        return recordHostResponse(pending, responsePayload)
    }

    private fun recordHostRequest(pending: PendingHostRequest) {
        if (!isCaptureEnabled()) {
            return
        }
        expirePendingAriverRequests()
        pendingAriverRequestsById[pending.requestId] = pending
        emit(
            RpcTrafficEvent(
                title = "Host RPC",
                phase = TrafficPhase.REQUEST,
                source = pending.source,
                method = pending.method,
                requestId = pending.requestId,
                payload = pending.requestPayload,
            ),
        )
    }

    private fun recordHostResponse(pending: PendingHostRequest, responsePayload: String?): Boolean {
        if (!isCaptureEnabled() || !pendingAriverRequestsById.remove(pending.requestId, pending)) {
            return false
        }
        emit(
            RpcTrafficEvent(
                title = "Host RPC",
                phase = TrafficPhase.RESPONSE,
                source = pending.source,
                method = pending.method,
                requestId = pending.requestId,
                payload = responsePayload,
                elapsedMs = System.currentTimeMillis() - pending.startedAtMs,
                correlationStatus = "MATCHED",
            ),
        )
        forwardCapturedHostResponse(
            source = pending.source,
            method = pending.method,
            requestPayload = pending.requestPayload,
            responsePayload = responsePayload,
            startedAtMs = pending.startedAtMs,
            requestId = pending.requestId,
        )
        return true
    }

    private fun recordHostError(
        pending: PendingHostRequest,
        note: String,
        correlationStatus: String? = null,
    ) {
        if (!isCaptureEnabled() || !pendingAriverRequestsById.remove(pending.requestId, pending)) {
            return
        }
        emit(
            RpcTrafficEvent(
                title = "Host RPC",
                phase = TrafficPhase.ERROR,
                source = pending.source,
                method = pending.method,
                requestId = pending.requestId,
                elapsedMs = System.currentTimeMillis() - pending.startedAtMs,
                note = note,
                correlationStatus = correlationStatus ?: "MATCHED",
            ),
        )
    }

    private fun forwardCapturedHostResponse(
        source: TrafficSource,
        method: String,
        requestPayload: String?,
        responsePayload: String?,
        startedAtMs: Long,
        requestId: String,
    ) {
        if (RuntimeIdentityGuard.isCaptureOnlyProcess() || !isCaptureEnabled()) {
            return
        }
        if (BaseModel.sendHookData.value != true) {
            return
        }
        val url = BaseModel.sendHookDataUrl.value?.trim().orEmpty()
        if (url.isEmpty()) {
            return
        }
        runCatching {
            HookSender.sendHookData(
                JSONObject().apply {
                    put("TimeStamp", startedAtMs)
                    put("Method", method)
                    put("Params", requestPayload ?: JSONObject.NULL)
                    put("Data", responsePayload ?: JSONObject.NULL)
                    put("Source", source.name)
                    put("RequestId", requestId)
                },
                url
            )
        }.onFailure {
            Log.runtime(TAG, "转发 Host RPC 抓包失败: ${it.message}")
        }
    }

    private fun emit(event: RpcTrafficEvent) {
        val payload = event.payload?.let(::parsePayloadForEvent)
        val message = JSONObject().apply {
            put("schema", "rpc-capture")
            put("sessionId", activeSessionId())
            put("eventId", UUID.randomUUID().toString())
            put("requestId", event.requestId ?: JSONObject.NULL)
            put("attemptId", event.attemptId ?: JSONObject.NULL)
            put("timestampMs", System.currentTimeMillis())
            put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            put("processName", currentProcessName())
            put("pid", android.os.Process.myPid())
            put("processSequence", eventSequence.incrementAndGet())
            put("source", event.source.name)
            put("phase", event.phase.name)
            put("method", event.method)
            put("elapsedMs", event.elapsedMs)
            put("title", event.title)
            put("refs", extractStructuredRefs(payload))
            put("payload", payload ?: JSONObject.NULL)
            if (!event.note.isNullOrBlank()) {
                put("note", event.note)
            }
            if (!event.correlationStatus.isNullOrBlank()) {
                put("correlationStatus", event.correlationStatus)
            }
        }.toString()
        Log.capture(message)
    }

    @JvmStatic
    fun resetCaptureSession() {
        synchronized(sessionLock) {
            sessionId = null
            sessionStarted = false
            eventSequence.set(0L)
            captureSessionCache = null
            runCatching { File(Files.CONFIG_DIR, CAPTURE_SESSION_FILE_NAME).delete() }
        }
    }

    private fun activeSessionId(): String =
        synchronized(sessionLock) {
            val persisted = readPersistedSessionId()
            val resolved = when {
                persisted != null -> persisted
                RuntimeIdentityGuard.isMainProcess() -> UUID.randomUUID().toString().also(::persistSessionId)
                else -> sessionId ?: UUID.randomUUID().toString()
            }
            if (sessionId != resolved) {
                sessionId = resolved
                sessionStarted = false
                eventSequence.set(0L)
            }
            if (!sessionStarted) {
                sessionStarted = true
                Log.capture(
                    JSONObject().apply {
                        put("schema", "rpc-capture")
                        put("sessionId", resolved)
                        put("eventId", UUID.randomUUID().toString())
                        put("requestId", JSONObject.NULL)
                        put("attemptId", JSONObject.NULL)
                        put("timestampMs", System.currentTimeMillis())
                        put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                        put("processName", currentProcessName())
                        put("pid", android.os.Process.myPid())
                        put("processSequence", eventSequence.incrementAndGet())
                        put("source", "MODULE_ACTIVE")
                        put("phase", TrafficPhase.SESSION_START.name)
                        put("method", "capture.session")
                        put("elapsedMs", -1L)
                        put("refs", JSONObject())
                        put("payload", JSONObject.NULL)
                    }.toString(),
                )
            }
            resolved
        }

    private fun readPersistedSessionId(): String? =
        runCatching {
            val now = System.currentTimeMillis()
            val file = File(Files.CONFIG_DIR, CAPTURE_SESSION_FILE_NAME)
            val lastModifiedMs = file.lastModified()
            captureSessionCache
                ?.takeIf { cache ->
                    cache.lastModifiedMs == lastModifiedMs && now < cache.expiresAtMs
                }
                ?.let { cache -> return@runCatching cache.sessionId }
            if (!file.isFile) {
                captureSessionCache = null
                null
            } else {
                JSONObject(file.readText(Charsets.UTF_8))
                    .optString("sessionId")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.also { value ->
                        captureSessionCache = CaptureSessionCache(
                            sessionId = value,
                            lastModifiedMs = lastModifiedMs,
                            expiresAtMs = now + 1_000L,
                        )
                    }
            }
        }.getOrNull()

    private fun persistSessionId(value: String) {
        runCatching {
            val file = File(Files.CONFIG_DIR, CAPTURE_SESSION_FILE_NAME)
            if (Files.write2File(
                    JSONObject().put("sessionId", value).put("createdAtMs", System.currentTimeMillis()).toString(),
                    file,
                )
            ) {
                captureSessionCache = CaptureSessionCache(
                    sessionId = value,
                    lastModifiedMs = file.lastModified(),
                    expiresAtMs = System.currentTimeMillis() + 1_000L,
                )
            }
        }
    }

    private fun parsePayloadForEvent(payload: String): Any =
        runCatching {
            val trimmed = payload.trim()
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> payload
            }
        }.getOrDefault(payload)

    private fun extractStructuredRefs(payload: Any?): JSONObject {
        val refs = JSONObject()
        val root = payload as? JSONObject ?: return refs
        structuredReferenceContainers.forEach { containerPath ->
            val container = objectAtPath(root, containerPath) ?: return@forEach
            structuredReferenceKeys.forEach { key ->
                if (!refs.has(key) && container.has(key) && !container.isNull(key)) {
                    refs.put(key, container.opt(key))
                }
            }
        }
        return refs
    }

    private fun objectAtPath(root: JSONObject, path: String): JSONObject? {
        if (path.isEmpty()) {
            return root
        }
        var current = root
        path.split('.').forEach { key ->
            current = current.optJSONObject(key) ?: return null
        }
        return current
    }

    private fun putPendingAriverRequest(callback: Any, pending: PendingHostRequest) {
        drainCollectedPendingAriverRequests()
        expirePendingAriverRequests()
        pendingAriverRequests
            .computeIfAbsent(WeakIdentityKey(callback, pendingAriverRequestQueue)) { ConcurrentLinkedQueue() }
            .add(pending)
    }

    private fun peekPendingAriverRequest(callback: Any): PendingHostRequest? {
        drainCollectedPendingAriverRequests()
        return pendingAriverRequests[WeakIdentityKey(callback)]?.peek()
    }

    private fun removePendingAriverRequest(callback: Any, requestId: String? = null): PendingHostRequest? {
        drainCollectedPendingAriverRequests()
        val key = WeakIdentityKey(callback)
        val queue = pendingAriverRequests[key] ?: return null
        val pending = if (requestId == null) {
            queue.poll()
        } else {
            queue.firstOrNull { it.requestId == requestId }?.also(queue::remove)
        }
        if (queue.isEmpty()) {
            pendingAriverRequests.remove(key, queue)
        }
        return pending
    }

    private fun expirePendingAriverRequests() {
        val now = System.currentTimeMillis()
        pendingAriverRequestsById.values.toList()
            .filter { now - it.startedAtMs >= PENDING_RESPONSE_TIMEOUT_MS }
            .forEach { pending ->
                if (!pendingAriverRequestsById.remove(pending.requestId, pending)) {
                    return@forEach
                }
                pendingAriverRequests.entries.forEach { (key, queue) ->
                    queue.remove(pending)
                    if (queue.isEmpty()) {
                        pendingAriverRequests.remove(key, queue)
                    }
                }
                emit(
                    RpcTrafficEvent(
                        title = "Host RPC",
                        phase = TrafficPhase.ERROR,
                        source = pending.source,
                        method = pending.method,
                        requestId = pending.requestId,
                        elapsedMs = now - pending.startedAtMs,
                        note = "response_timeout",
                        correlationStatus = "TIMEOUT",
                    ),
                )
            }
    }

    private fun removePendingAriverRequests(callback: Any) {
        drainCollectedPendingAriverRequests()
        pendingAriverRequests.remove(WeakIdentityKey(callback))?.forEach { pending ->
            pendingAriverRequestsById.remove(pending.requestId)
        }
    }

    private fun drainCollectedPendingAriverRequests() {
        while (true) {
            val key = pendingAriverRequestQueue.poll() as? WeakIdentityKey ?: return
            pendingAriverRequests.remove(key)?.forEach { pending ->
                pendingAriverRequestsById.remove(pending.requestId)
            }
        }
    }

    private fun clearPendingAriverRequests() {
        drainCollectedPendingAriverRequests()
        pendingAriverRequests.clear()
        pendingAriverRequestsById.clear()
    }

    private fun isCaptureEnabled(): Boolean {
        drainCollectedPendingAriverRequests()
        val enabled = runCatching { captureEnabledProvider.invoke() }.getOrDefault(false)
        if (!enabled) {
            clearPendingAriverRequests()
        }
        return enabled
    }

    private fun isCaptureEnabledInPersistentConfig(): Boolean {
        val previous = captureEnabledCache
        return runCatching {
            val now = System.currentTimeMillis()
            val configDir = Files.CONFIG_DIR
            val dataStoreFile = File(configDir, "DataStore.json")
            val activeUserId = JsonUtil
                .toNode(Files.readFromFile(dataStoreFile))
                ?.path("activedUser")
                ?.path("userId")
                ?.asText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val userConfigFile = activeUserId
                ?.let { userId -> File(File(configDir, userId), "config_v2.json") }
                ?.takeIf { it.isFile }
            val configFile = userConfigFile ?: Files.getDefaultConfigV2File().takeIf { it.isFile }
            val fingerprint = listOf(
                dataStoreFile.lastModified(),
                configFile?.absolutePath.orEmpty(),
                configFile?.lastModified() ?: -1L,
            ).joinToString(":")
            captureEnabledCache
                ?.takeIf { cache -> cache.fingerprint == fingerprint && now < cache.expiresAtMs }
                ?.let { cache -> return@runCatching cache.enabled }

            val debugModeValue = configFile?.let { file ->
                JsonUtil
                    .toNode(Files.readFromFile(file))
                    ?.path("modelFieldsMap")
                    ?.path("BaseModel")
                    ?.path("debugMode")
                    ?.path("value")
                    ?: error("debugMode 配置缺失")
            }
            val enabled = when {
                configFile == null -> false
                debugModeValue == null || debugModeValue.isMissingNode || debugModeValue.isNull ->
                    error("debugMode 配置缺失")
                else -> debugModeValue.asBoolean(false)
            }
            captureEnabledCache = CaptureEnabledCache(
                fingerprint = fingerprint,
                enabled = enabled,
                expiresAtMs = now + 1_000L,
            )
            enabled
        }.getOrElse { previous?.enabled ?: false }
    }

    private fun currentProcessName(): String =
        runCatching { XposedEnv.processName }.getOrDefault("unknown")

    private fun readAriverResponseField(callback: Any): String? =
        runCatching {
            findField(callback.javaClass, "mJSONResponse")?.get(callback)?.toString()
        }.getOrNull()

    private fun extractH5Response(responseObject: Any?): String? {
        val target = responseObject ?: return null
        return runCatching {
            findMethod(target.javaClass, "getResponse", 0)?.invoke(target) as? String
        }.getOrNull()
    }

    private fun findMethod(
        targetClass: Class<*>,
        name: String,
        parameterCount: Int,
        firstParameterType: Class<*>? = null
    ): Method? {
        val methods = linkedSetOf<Method>()
        var current: Class<*>? = targetClass
        while (current != null) {
            methods.addAll(current.declaredMethods)
            current = current.superclass
        }
        methods.addAll(targetClass.methods)
        return methods.firstOrNull { method ->
            method.name == name &&
                method.parameterCount == parameterCount &&
                (firstParameterType == null || method.parameterTypes.firstOrNull() == firstParameterType)
        }?.apply {
            isAccessible = true
        }
    }

    private fun findField(targetClass: Class<*>, name: String): Field? {
        var current: Class<*>? = targetClass
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply {
                    isAccessible = true
                }
            }
            current = current.superclass
        }
        return null
    }
}

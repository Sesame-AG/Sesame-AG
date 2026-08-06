package io.github.aoguai.sesameag.hook.rpc.capture

import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.HookSender
import io.github.aoguai.sesameag.hook.TokenHooker
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

object RpcTrafficCapture {
    private const val TAG = "RpcTrafficCapture"

    private enum class TrafficSource {
        ARIVER_RPC,
        H5_RPC,
        MODULE_ACTIVE
    }

    private enum class TrafficPhase {
        REQUEST,
        RESPONSE,
        ERROR
    }

    private data class RpcTrafficEvent(
        val title: String,
        val phase: TrafficPhase,
        val source: TrafficSource,
        val method: String,
        val payload: String? = null,
        val elapsedMs: Long = -1L,
        val note: String? = null
    )

    private data class PendingHostRequest(
        val method: String,
        val requestPayload: String?,
        val startedAtMs: Long,
        val source: TrafficSource
    )

    private val installLock = Any()
    private val ariverRequestHookInstalled = AtomicBoolean(false)
    private val ariverResponseHookInstalled = AtomicBoolean(false)
    private val h5HookInstalled = AtomicBoolean(false)
    private val pendingAriverRequests = ConcurrentHashMap<Any, PendingHostRequest>()

    fun install(classLoader: ClassLoader): Boolean {
        synchronized(installLock) {
            var installedAny = false
            if (installAriverRequestHook(classLoader)) {
                installedAny = true
                installAriverResponseHook(classLoader)
            }
            if (installH5Hook(classLoader)) {
                installedAny = true
            }
            if (installedAny) {
                Log.runtime(TAG, "已安装 RPC 抓包 Hook")
            } else {
                Log.runtime(TAG, "未找到可安装的 RPC 抓包 Hook")
            }
            return installedAny
        }
    }

    fun recordModuleRequest(method: String?, payload: String?) {
        if (!isCaptureEnabled() || method.isNullOrBlank()) {
            return
        }
        emit(
            RpcTrafficEvent(
                title = "Module RPC",
                phase = TrafficPhase.REQUEST,
                source = TrafficSource.MODULE_ACTIVE,
                method = method,
                payload = payload
            )
        )
    }

    fun recordModuleResponse(method: String?, payload: String?, elapsedMs: Long) {
        if (!isCaptureEnabled() || method.isNullOrBlank()) {
            return
        }
        emit(
            RpcTrafficEvent(
                title = "Module RPC",
                phase = TrafficPhase.RESPONSE,
                source = TrafficSource.MODULE_ACTIVE,
                method = method,
                payload = payload,
                elapsedMs = elapsedMs
            )
        )
    }

    fun recordModuleError(method: String?, payload: String?, elapsedMs: Long, note: String) {
        if (!isCaptureEnabled() || method.isNullOrBlank()) {
            return
        }
        emit(
            RpcTrafficEvent(
                title = "Module RPC",
                phase = TrafficPhase.ERROR,
                source = TrafficSource.MODULE_ACTIVE,
                method = method,
                payload = payload,
                elapsedMs = elapsedMs,
                note = note
            )
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
                val requestPayload = args.getOrNull(4)?.toString()
                val callback = args.getOrNull(15)
                dispatchTokenHook(methodName, requestPayload)
                val captureEnabled = isCaptureEnabled() && !methodName.isNullOrBlank()

                if (captureEnabled) {
                    val startedAtMs = System.currentTimeMillis()
                    recordHostRequest(TrafficSource.ARIVER_RPC, methodName, requestPayload)
                    if (callback != null) {
                        pendingAriverRequests[callback] = PendingHostRequest(
                            method = methodName,
                            requestPayload = requestPayload,
                            startedAtMs = startedAtMs,
                            source = TrafficSource.ARIVER_RPC
                        )
                    }
                }

                val result = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    if (captureEnabled) {
                        val startedAtMs = if (callback != null) {
                            pendingAriverRequests.remove(callback)?.startedAtMs ?: System.currentTimeMillis()
                        } else {
                            System.currentTimeMillis()
                        }
                        recordHostError(
                            source = TrafficSource.ARIVER_RPC,
                            method = methodName,
                            startedAtMs = startedAtMs,
                            note = "RpcBridgeExtension.rpc threw ${t.javaClass.simpleName}"
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
                if (callback != null && !responsePayload.isNullOrBlank()) {
                    recordPendingAriverResponse(callback, responsePayload)
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
                val requestPayload = args.getOrNull(1)?.toString()
                val startedAtMs = if (isCaptureEnabled() && !methodName.isNullOrBlank()) {
                    val now = System.currentTimeMillis()
                    recordHostRequest(TrafficSource.H5_RPC, methodName, requestPayload)
                    now
                } else {
                    0L
                }

                val result = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    if (startedAtMs > 0L && !methodName.isNullOrBlank()) {
                        recordHostError(
                            source = TrafficSource.H5_RPC,
                            method = methodName,
                            startedAtMs = startedAtMs,
                            note = "H5RpcUtil.rpcCall threw ${t.javaClass.simpleName}"
                        )
                    }
                    throw t
                }
                if (startedAtMs > 0L && !methodName.isNullOrBlank()) {
                    val responsePayload = extractH5Response(result)
                    if (responsePayload != null) {
                        recordHostResponse(
                            source = TrafficSource.H5_RPC,
                            method = methodName,
                            requestPayload = requestPayload,
                            responsePayload = responsePayload,
                            startedAtMs = startedAtMs
                        )
                    } else {
                        recordHostError(
                            source = TrafficSource.H5_RPC,
                            method = methodName,
                            startedAtMs = startedAtMs,
                            note = "H5Response.getResponse 返回空"
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
        if (!isCaptureEnabled()) {
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

    private fun tryRecordAriverResponseFromField(callback: Any) {
        if (!isCaptureEnabled()) {
            pendingAriverRequests.remove(callback)
            return
        }
        if (pendingAriverRequests[callback] == null) {
            return
        }
        val responsePayload = runCatching {
            findField(callback.javaClass, "mJSONResponse")?.get(callback)?.toString()
        }.getOrNull()
        if (!responsePayload.isNullOrBlank()) {
            recordPendingAriverResponse(callback, responsePayload)
            return
        }
    }

    private fun recordPendingAriverResponse(callback: Any, responsePayload: String) {
        if (!isCaptureEnabled()) {
            pendingAriverRequests.remove(callback)
            return
        }
        val pending = pendingAriverRequests.remove(callback) ?: return
        recordHostResponse(
            source = pending.source,
            method = pending.method,
            requestPayload = pending.requestPayload,
            responsePayload = responsePayload,
            startedAtMs = pending.startedAtMs
        )
    }

    private fun recordHostRequest(
        source: TrafficSource,
        method: String,
        requestPayload: String?
    ) {
        if (!isCaptureEnabled()) {
            return
        }
        emit(
            RpcTrafficEvent(
                title = "Host RPC",
                phase = TrafficPhase.REQUEST,
                source = source,
                method = method,
                payload = requestPayload
            )
        )
    }

    private fun recordHostResponse(
        source: TrafficSource,
        method: String,
        requestPayload: String?,
        responsePayload: String?,
        startedAtMs: Long
    ) {
        if (!isCaptureEnabled()) {
            return
        }
        emit(
            RpcTrafficEvent(
                title = "Host RPC",
                phase = TrafficPhase.RESPONSE,
                source = source,
                method = method,
                payload = responsePayload,
                elapsedMs = System.currentTimeMillis() - startedAtMs
            )
        )
        forwardCapturedHostResponse(source, method, requestPayload, responsePayload, startedAtMs)
    }

    private fun recordHostError(
        source: TrafficSource,
        method: String,
        startedAtMs: Long,
        note: String
    ) {
        if (!isCaptureEnabled()) {
            return
        }
        emit(
            RpcTrafficEvent(
                title = "Host RPC",
                phase = TrafficPhase.ERROR,
                source = source,
                method = method,
                elapsedMs = System.currentTimeMillis() - startedAtMs,
                note = note
            )
        )
    }

    private fun forwardCapturedHostResponse(
        source: TrafficSource,
        method: String,
        requestPayload: String?,
        responsePayload: String?,
        startedAtMs: Long
    ) {
        if (!isCaptureEnabled()) {
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
                },
                url
            )
        }.onFailure {
            Log.runtime(TAG, "转发 Host RPC 抓包失败: ${it.message}")
        }
    }

    private fun emit(event: RpcTrafficEvent) {
        val message = buildString {
            append(event.title)
            append(" [")
            append(event.phase.name)
            append("] source=")
            append(event.source.name)
            append(" method=")
            append(event.method)
            append(" length=")
            append(event.payload?.length ?: 0)
            append(" elapsed=")
            append(if (event.elapsedMs >= 0L) "${event.elapsedMs}ms" else "-")
            if (!event.note.isNullOrBlank()) {
                append(" note=")
                append(event.note)
            }
            if (!event.payload.isNullOrEmpty()) {
                append('\n')
                append(event.payload)
            }
        }
        Log.capture(TAG, message)
    }

    private fun isCaptureEnabled(): Boolean {
        val enabled = BaseModel.debugMode.value == true
        if (!enabled && pendingAriverRequests.isNotEmpty()) {
            pendingAriverRequests.clear()
        }
        return enabled
    }

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

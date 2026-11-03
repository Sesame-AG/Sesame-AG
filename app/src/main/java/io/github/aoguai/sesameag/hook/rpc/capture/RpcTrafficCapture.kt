package io.github.aoguai.sesameag.hook.rpc.capture

import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.data.General
import io.github.libxposed.api.XposedInterface
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.HookSender
import io.github.aoguai.sesameag.hook.RuntimeIdentityGuard
import io.github.aoguai.sesameag.hook.TokenHooker
import io.github.aoguai.sesameag.hook.XposedEnv
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.CommandUtil
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import java.io.File
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import java.nio.ByteBuffer
import dalvik.system.BaseDexClassLoader
import org.json.JSONArray
import org.json.JSONObject

object RpcTrafficCapture {
    private const val TAG = "RpcTrafficCapture"
    private const val CAPTURE_SESSION_FILE_NAME = "rpc_capture_session.json"
    private const val PENDING_RESPONSE_TIMEOUT_MS = 60_000L
    private const val MINI_REQUEST_CLASS =
        "com.alipay.android.phone.wallet.aompnetwork.request.AsyncRequestHandler"
    private const val MINI_RESPONSE_CLASS =
        "com.alipay.android.phone.event.ariver.network.HttpRequestResponseTrigger"
    private const val H5_NETWORK_CLASS = "com.alipay.mobile.common.transport.h5.H5NetworkManager"
    private const val H5_REQUEST_CLASS = "com.alipay.mobile.common.transport.h5.H5HttpUrlRequest"
    private const val XRIVER_REQUEST_CLASS = "com.alibaba.xriver.android.proxy.CRVRequestProxy"
    private const val MTOP_REQUEST_CLASS = "com.alibaba.ariver.jsapi.mtop.MtopBridgeExtention"

    private enum class TrafficSource(val title: String) {
        ARIVER_RPC("Host RPC"),
        H5_RPC("Host RPC"),
        MODULE_ACTIVE("Module RPC"),
        MINI_HTTP("Mini-program HTTP"),
        MODULE_HTTP("Module HTTP"),
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
        val source: TrafficSource,
        val startedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        @Volatile
        var lastActivityElapsedMs: Long = startedAtElapsedMs
        @Volatile
        var callbackKept = false
        @Volatile
        var httpMethod: String? = null
    }

    private class HttpBodyObservation(
        val pending: PendingHostRequest,
        val session: String,
        val streamIdentity: Int,
        val afterNoBodyResponse: Boolean,
    ) {
        var position = 0L
        var markPosition: Long? = null
        @Volatile
        var incomplete = false
        var finished = false
        @Volatile
        var lastActivityElapsedMs = SystemClock.elapsedRealtime()
    }

    private class PendingRequestLinks {
        var body: HttpBodyObservation? = null
        val requests = mutableListOf<PendingHostRequest>()
        // 终态仅保留弱对象标记，避免同一回调的另一条观察路径重复输出。
        var completed = false
        var source: TrafficSource? = null
        // 共享回调出现重叠请求后，即使只剩一个，也不能推断迟到响应属于它。
        var ambiguous = false
    }

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

    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var authorizationJob: Job? = null
    private var captureContext: Context? = null
    @Volatile
    private var captureUserId: String? = null

    private val installLock = Any()
    private val ariverRequestHookInstalled = AtomicBoolean(false)
    private val mtopRequestHookInstalled = AtomicBoolean(false)
    private val h5HookInstalled = AtomicBoolean(false)
    private val captureHooks = ConcurrentHashMap<Method, XposedInterface.HookHandle>()
    // false 仅表示类入口正在安装，不把安装占位当作成功。
    private val miniHttpClassInstallations = ConcurrentHashMap<Class<*>, Boolean>()
    private val reportedHttpClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val reportedHttpCallSites = AtomicReference<Set<String>>(emptySet())
    private val httpClassDeclarations = ConcurrentHashMap<Class<*>, String>()
    private val responseHookCalls = ThreadLocal<IdentityHashMap<Any, Boolean>>()
    private val bodyAccessorCalls = ThreadLocal<IdentityHashMap<PendingHostRequest, Boolean>>()
    private val pendingLock = Any()
    private val pendingAriverRequestQueue = ReferenceQueue<Any>()
    private val pendingAriverRequests = ConcurrentHashMap<WeakIdentityKey, PendingRequestLinks>()
    private val pendingAriverRequestsById = ConcurrentHashMap<String, PendingHostRequest>()
    private val pendingCleanupHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pendingCleanup = object : Runnable {
        override fun run() {
            runCatching {
                if (isCaptureEnabled()) {
                    activeSessionId()
                    drainCollectedPendingAriverRequests()
                    expirePendingAriverRequests()
                }
            }.onFailure {
                runCatching { Log.runtime(TAG, "抓包上下文清理失败: ${it.stackTraceToString()}") }
            }
            synchronized(pendingLock) { schedulePendingCleanupLocked() }
        }
    }
    private val eventSequence = AtomicLong(0L)
    private val sessionLock = Any()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var sessionStarted = false

    @Volatile
    private var captureEnabledProvider: (String) -> Boolean = { Config.isLoaded() && BaseModel.debugMode.value == true }

    @Volatile
    private var captureEnabledCache: CaptureEnabledCache? = null

    @Volatile
    private var captureSessionCache: CaptureSessionCache? = null

    private var captureClassLoader: ClassLoader? = null
    private var stopCaptureConfigObservation: (() -> Unit)? = null
    private val captureConfigRefresh = Runnable {
        captureScope.launch {
            runCatching { refreshCaptureState() }.onFailure {
                Log.error(TAG, "同步抓包开关失败: ${it.stackTraceToString()}")
            }
        }
    }

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

    @Synchronized
    fun install(
        classLoader: ClassLoader,
        context: Context,
        enabledProvider: (String) -> Boolean = { Config.isLoaded() && BaseModel.debugMode.value == true },
    ): Boolean {
        captureClassLoader = classLoader
        captureContext = context.applicationContext ?: context
        captureEnabledProvider = enabledProvider
        // 主进程在账号会话建立后启动抓包，attach 阶段仅保存安装上下文。
        return if (RuntimeIdentityGuard.isMainProcess()) false else refreshCaptureState()
    }

    @Synchronized
    fun refreshCaptureState(): Boolean {
        val classLoader = captureClassLoader ?: return false
        captureEnabledCache = null
        if (captureUserId == null && authorizationJob?.isActive != true) {
            val context = captureContext ?: return false
            authorizationJob = captureScope.launch {
                val userId = CommandUtil.authorizeCapture(context, captureEnabledProvider)
                if (userId != null) {
                    synchronized(this@RpcTrafficCapture) {
                        captureUserId = userId
                        refreshCaptureState()
                    }
                }
            }
        }
        if (!isCaptureEnabled()) {
            captureUserId = null
            synchronized(installLock) {
                captureHooks.entries.toList().forEach { (method, handle) ->
                    runCatching { handle.unhook() }.onSuccess {
                        captureHooks.remove(method, handle)
                    }.onFailure {
                        Log.error(TAG, "卸载抓包 Hook 失败: $method ${it.stackTraceToString()}")
                    }
                }
                ariverRequestHookInstalled.set(false)
                mtopRequestHookInstalled.set(false)
                h5HookInstalled.set(false)
                miniHttpClassInstallations.clear()
            }
            if (RuntimeIdentityGuard.isMainProcess()) {
                resetCaptureSession()
            } else {
                synchronized(sessionLock) {
                    clearPendingAriverRequests()
                    sessionId = null
                    sessionStarted = false
                    captureSessionCache = null
                    reportedHttpClasses.clear()
                    reportedHttpCallSites.set(emptySet())
                }
            }
            return false
        }
        // 类解析可能触发宿主的类加载回调，不持有安装锁等待 ClassLoader。
        val miniHttpInstalled = runCatching { installMiniHttpHooks(classLoader) }.onFailure {
            Log.runtime(TAG, "安装小程序 HTTP 抓包失败: ${it.message}")
        }.getOrDefault(false)
        val ariverInstalled = installAriverRequestHook(classLoader)
        val mtopInstalled = runCatching {
            installMtopRequestHook(Class.forName(MTOP_REQUEST_CLASS, false, classLoader))
        }.onFailure {
            if (it is ClassNotFoundException) {
                Log.runtime(TAG, "Mtop 抓包入口尚未加载，等待类加载后安装: $MTOP_REQUEST_CLASS")
            } else {
                Log.error(TAG, "解析 Mtop 抓包入口失败: ${it.stackTraceToString()}")
            }
        }.getOrDefault(false)
        if (ariverInstalled || mtopInstalled) installAriverResponseHook(classLoader)
        val h5Installed = installH5Hook(classLoader)
        val installed = miniHttpInstalled || ariverInstalled || mtopInstalled || h5Installed
        if (installed && RuntimeIdentityGuard.isMainProcess() && isCaptureEnabled()) activeSessionId()
        return installed
    }

    private fun installCaptureHook(method: Method, hooker: XposedInterface.Hooker): Boolean =
        synchronized(installLock) {
            if (!isCaptureEnabled()) return@synchronized false
            if (!captureHooks.containsKey(method)) {
                captureHooks[method] = ApplicationHook.requireXposedInterface().hook(method).intercept(hooker)
            }
            true
        }

    /** Lite 仅获取已确认会话的许可和配置，不初始化业务任务。 */
    fun installForCaptureOnlyProcess(classLoader: ClassLoader, context: Context): Boolean =
        install(classLoader, context, ::isCaptureEnabledInPersistentConfig)

    private fun observeCaptureConfig(configDir: File, userDir: File?) {
        if (stopCaptureConfigObservation != null) return
        stopCaptureConfigObservation = io.github.aoguai.sesameag.storage.StoreAccess.observe { change ->
            if (change.collection == 0 || (change.name.isNotEmpty() &&
                    change.name != "config_v2.json" && change.name != "DataStore.json")) return@observe
            captureEnabledCache = null
            pendingCleanupHandler.removeCallbacks(captureConfigRefresh)
            pendingCleanupHandler.post(captureConfigRefresh)
        }
    }

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

    fun recordModuleHttpRequest(connection: HttpURLConnection, body: String): String? =
        runCatching {
            if (!isCaptureEnabled()) return@runCatching null
            val payload = JSONObject()
                .put("url", connection.url.toExternalForm())
                .put("method", connection.requestMethod)
                .put("headers", JSONObject(connection.requestProperties))
                .put("body", body)
                .toString()
            PendingHostRequest(
                requestId = newRequestId(),
                method = "${connection.requestMethod} ${connection.url}",
                requestPayload = payload,
                startedAtMs = System.currentTimeMillis(),
                source = TrafficSource.MODULE_HTTP,
            ).also(::recordHostRequest).requestId
        }.onFailure {
            runCatching { Log.runtime(TAG, "记录模块 HTTP 请求失败: ${it.message}") }
        }.getOrNull()

    fun recordModuleHttpResponse(
        requestId: String?,
        connection: HttpURLConnection,
        status: Int,
        body: String,
    ) {
        runCatching {
            if (!isCaptureEnabled()) return@runCatching
            val headers = JSONArray()
            connection.headerFields.forEach { (name, values) ->
                headers.put(
                    JSONObject()
                        .put("name", name ?: JSONObject.NULL)
                        .put("values", JSONArray(values)),
                )
            }
            val payload = JSONObject()
                .put("url", connection.url.toExternalForm())
                .put("status", status)
                .put("headers", headers)
                .put("body", body)
                .toString()
            val pending = requestId?.let(pendingAriverRequestsById::get)
            if ((pending == null || !recordHostResponse(pending, payload)) && isCaptureEnabled()) {
                emit(
                    RpcTrafficEvent(
                        title = TrafficSource.MODULE_HTTP.title,
                        phase = TrafficPhase.RESPONSE,
                        source = TrafficSource.MODULE_HTTP,
                        method = "${connection.requestMethod} ${connection.url}",
                        payload = payload,
                        correlationStatus = "UNMATCHED",
                    ),
                )
            }
        }.onFailure {
            runCatching { Log.runtime(TAG, "记录模块 HTTP 响应失败: ${it.message}") }
        }
    }

    fun recordModuleHttpError(requestId: String?, error: Throwable) {
        runCatching {
            if (requestId == null || !isCaptureEnabled()) return@runCatching
            val note = "${error.javaClass.name}: ${error.message.orEmpty()}"
            val pending = pendingAriverRequestsById[requestId]
            if (pending != null) {
                recordHostError(pending, note)
            } else {
                emit(
                    RpcTrafficEvent(
                        title = TrafficSource.MODULE_HTTP.title,
                        phase = TrafficPhase.ERROR,
                        source = TrafficSource.MODULE_HTTP,
                        method = "HttpURLConnection",
                        note = note,
                        correlationStatus = "UNMATCHED",
                    ),
                )
            }
        }.onFailure {
            runCatching { Log.runtime(TAG, "记录模块 HTTP 异常失败: ${it.message}") }
        }
    }

    private fun installMiniHttpHooks(classLoader: ClassLoader): Boolean {
        if (!isCaptureEnabled()) return false
        var listenerInstalled = false
        var requestInstalled = false
        var responseInstalled = false
        var networkInstalled = false
        var xriverInstalled = false
        val loaderClasses = linkedSetOf<Class<*>>(ClassLoader::class.java, BaseDexClassLoader::class.java)
        var current: Class<*>? = classLoader.javaClass
        while (current != null && ClassLoader::class.java.isAssignableFrom(current)) {
            loaderClasses.add(current)
            current = current.superclass
        }
        loaderClasses.forEach { loaderClass ->
            runCatching {
                loaderClass.declaredMethods.filter { method ->
                    method.name == "loadClass" && method.parameterTypes.firstOrNull() == String::class.java
                }.forEach methodLoop@ { method ->
                    if (captureHooks.containsKey(method)) {
                        listenerInstalled = true
                        return@methodLoop
                    }
                    runCatching {
                        method.isAccessible = true
                        if (!installCaptureHook(method) intercept@ { chain ->
                            val result = chain.proceed()
                            if (result is Class<*> &&
                                (result.name == MINI_REQUEST_CLASS || result.name == MINI_RESPONSE_CLASS ||
                                    result.name == H5_NETWORK_CLASS || result.name == XRIVER_REQUEST_CLASS ||
                                    result.name == MTOP_REQUEST_CLASS)
                            ) {
                                runCatching { installMiniHttpClass(result) }.onFailure {
                                    runCatching { Log.runtime(TAG, "延迟安装小程序 HTTP 抓包失败: ${it.stackTraceToString()}") }
                                }
                            }
                            result
                        }) return@runCatching
                        listenerInstalled = true
                        Log.runtime(TAG, "已安装小程序 HTTP 类加载监听: $method")
                    }.onFailure {
                        runCatching { Log.runtime(TAG, "安装小程序 HTTP 类加载监听失败: $method ${it.stackTraceToString()}") }
                    }
                }
            }.onFailure {
                runCatching { Log.runtime(TAG, "读取类加载入口失败: ${loaderClass.name} ${it.stackTraceToString()}") }
            }
        }
        listOf(MINI_REQUEST_CLASS, MINI_RESPONSE_CLASS, H5_NETWORK_CLASS, XRIVER_REQUEST_CLASS).forEach { name ->
            runCatching {
                val installed = installMiniHttpClass(Class.forName(name, false, classLoader))
                when (name) {
                    MINI_REQUEST_CLASS -> requestInstalled = installed
                    MINI_RESPONSE_CLASS -> responseInstalled = installed
                    H5_NETWORK_CLASS -> networkInstalled = installed
                    XRIVER_REQUEST_CLASS -> xriverInstalled = installed
                }
            }.onFailure {
                runCatching { Log.runtime(TAG, "小程序 HTTP 入口暂未安装: $name ${it.stackTraceToString()}") }
            }
        }
        Log.runtime(
            TAG,
            "小程序 HTTP 安装状态: listener=$listenerInstalled, request=$requestInstalled, response=$responseInstalled, " +
                "network=$networkInstalled, xriver=$xriverInstalled, process=${currentProcessName()}, " +
                "loader=${classLoader.javaClass.name}@${System.identityHashCode(classLoader)}",
        )
        return listenerInstalled || requestInstalled || responseInstalled || networkInstalled || xriverInstalled
    }

    private fun installMiniHttpClass(targetClass: Class<*>): Boolean {
        if (targetClass.name == MTOP_REQUEST_CLASS) return installMtopRequestHook(targetClass)
        reportMiniHttpClass(targetClass)
        if (targetClass.name == H5_NETWORK_CLASS) return installH5NetworkHook(targetClass)
        if (targetClass.name == XRIVER_REQUEST_CLASS) return installXriverHttpHooks(targetClass)
        val isRequest = when (targetClass.name) {
            MINI_REQUEST_CLASS -> true
            MINI_RESPONSE_CLASS -> false
            else -> return false
        }
        synchronized(installLock) {
            if (!isCaptureEnabled()) return false
            miniHttpClassInstallations.putIfAbsent(targetClass, false)?.let { return it }
        }
        val methodName = if (isRequest) "processAsyncRequest" else "onHandleResponse"
        val loaderIdentity = targetClass.classLoader?.let {
            "${it.javaClass.name}@${System.identityHashCode(it)}"
        } ?: "bootstrap"
        return try {
            val methods = linkedMapOf<List<Class<*>>, Method>()
            var current: Class<*>? = targetClass
            while (current != null && current != Any::class.java) {
                current.declaredMethods.filter {
                    it.name == methodName && !Modifier.isAbstract(it.modifiers)
                }.forEach { method ->
                    methods.putIfAbsent(method.parameterTypes.toList(), method)
                }
                current = current.superclass
            }
            if (methods.isEmpty()) error("${targetClass.name}#$methodName 未找到可安装方法")
            methods.values.forEach { method ->
                if (captureHooks.containsKey(method)) return@forEach
                runCatching {
                    val callbackIndex = if (isRequest) method.parameterTypes.indexOfFirst {
                        it.name == "com.alibaba.ariver.engine.api.bridge.extension.BridgeCallback"
                    } else -1
                    method.isAccessible = true
                    if (!installCaptureHook(method) intercept@ { chain ->
                        val callback = chain.args.getOrNull(callbackIndex)
                        var linkedCallback: Any? = null
                        val pending = runCatching capture@ {
                            if (!isCaptureEnabled()) return@capture null
                            val args = chain.args.toList()
                            val responseNote = if (!isRequest) {
                                if (reportedHttpClasses.add(targetClass)) {
                                    listOf(MINI_REQUEST_CLASS, H5_NETWORK_CLASS, XRIVER_REQUEST_CLASS).forEach { name ->
                                        runCatching {
                                            installMiniHttpClass(Class.forName(name, false, targetClass.classLoader))
                                        }.onFailure {
                                            runCatching { Log.runtime(TAG, "响应入口补装 HTTP 抓包失败: $name ${it.stackTraceToString()}") }
                                        }
                                    }
                                }
                                recordHttpResponseCallSite(method)
                            } else null
                            val dataArguments = if (isRequest) method.parameterTypes.indices.filter { index ->
                                val parameter = method.parameterTypes[index]
                                parameter.name == "com.alipay.android.phone.wallet.aompnetwork.request.RequestModel" ||
                                    parameter.name == "com.alipay.android.phone.wallet.aompnetwork.request.ParamsModel"
                            }.toSet() else emptySet()
                            val payload = snapshotMiniHttpArguments(method, args, dataArguments)
                            if (isRequest) {
                                val modelIndex = method.parameterTypes.indexOfFirst {
                                    it.name == "com.alipay.android.phone.wallet.aompnetwork.request.RequestModel"
                                }
                                val transportRequest = args.getOrNull(modelIndex)?.let { model ->
                                    findField(model.javaClass, "h5HttpUrlRequest")?.get(model)
                                }
                                val completed = transportRequest != null && synchronized(pendingLock) {
                                    pendingAriverRequests[WeakIdentityKey(transportRequest)]?.completed == true
                                }
                                if (completed) return@capture null
                                val existing = transportRequest?.let(::peekPendingAriverRequest)
                                (existing ?: PendingHostRequest(
                                    requestId = newRequestId(),
                                    method = "${targetClass.name}#$methodName",
                                    requestPayload = payload,
                                    responseBeforeRequest = callback?.let(::readAriverResponseField),
                                    startedAtMs = System.currentTimeMillis(),
                                    source = TrafficSource.MINI_HTTP,
                                ).also(::recordHostRequest)).also { request ->
                                    transportRequest?.let { putPendingAriverRequest(it, request) }
                                    if (callback != null) {
                                        installAriverResponseClass(callback.javaClass)
                                        putPendingAriverRequest(callback, request)
                                        linkedCallback = callback
                                    }
                                }
                            } else {
                                emit(
                                    RpcTrafficEvent(
                                        title = TrafficSource.MINI_HTTP.title,
                                        phase = TrafficPhase.RESPONSE,
                                        source = TrafficSource.MINI_HTTP,
                                        method = args.getOrNull(0) as? String ?: "${targetClass.name}#$methodName",
                                        payload = payload,
                                        note = responseNote,
                                        correlationStatus = "UNMATCHED",
                                    ),
                                )
                                null
                            }
                        }.onFailure { error ->
                            runCatching { Log.runtime(TAG, "记录小程序 HTTP $methodName 失败: ${error.stackTraceToString()}") }
                            runCatching {
                                if (isCaptureEnabled()) emit(
                                    RpcTrafficEvent(
                                        title = TrafficSource.MINI_HTTP.title,
                                        phase = TrafficPhase.ERROR,
                                        source = TrafficSource.MINI_HTTP,
                                        method = "${targetClass.name}#$methodName",
                                        note = "capture_record_error: ${error.stackTraceToString()}",
                                        correlationStatus = "UNMATCHED",
                                    ),
                                )
                            }
                        }.getOrNull()
                        val associatedCallback = linkedCallback
                        val result = try {
                            chain.proceed()
                        } catch (error: Throwable) {
                            runCatching {
                                if (isCaptureEnabled()) {
                                    val note = "${error.javaClass.name}: ${error.message.orEmpty()}"
                                    if (pending != null) {
                                        recordHostError(pending, note)
                                    } else {
                                        emit(
                                            RpcTrafficEvent(
                                                title = TrafficSource.MINI_HTTP.title,
                                                phase = TrafficPhase.ERROR,
                                                source = TrafficSource.MINI_HTTP,
                                                method = pending?.method ?: "${targetClass.name}#$methodName",
                                                requestId = pending?.requestId,
                                                elapsedMs = pending?.let { System.currentTimeMillis() - it.startedAtMs } ?: -1L,
                                                note = note,
                                                correlationStatus = "UNMATCHED",
                                            ),
                                        )
                                    }
                                }
                            }.onFailure {
                                runCatching { Log.runtime(TAG, "记录小程序 HTTP 异常失败: ${it.stackTraceToString()}") }
                            }
                            throw error
                        }
                        if (associatedCallback != null) {
                            runCatching { tryRecordAriverResponseFromField(associatedCallback) }.onFailure {
                                runCatching { Log.runtime(TAG, "读取小程序 HTTP 回调失败: ${it.stackTraceToString()}") }
                            }
                        }
                        result
                    }) return@runCatching
                    Log.runtime(
                        TAG,
                        "已安装小程序 HTTP ${if (isRequest) "请求" else "响应"}抓包: $method; " +
                            "process=${currentProcessName()}, loader=$loaderIdentity",
                    )
                }.onFailure {
                    runCatching { Log.runtime(TAG, "安装小程序 HTTP 抓包失败: $method; loader=$loaderIdentity ${it.stackTraceToString()}") }
                }
            }
            synchronized(installLock) {
                if (isCaptureEnabled() && methods.values.all { captureHooks.containsKey(it) }) {
                    miniHttpClassInstallations[targetClass] = true
                }
            }
            methods.values.any { captureHooks.containsKey(it) }
        } catch (error: Throwable) {
            runCatching { Log.runtime(TAG, "安装 ${targetClass.name}#$methodName 失败: loader=$loaderIdentity ${error.stackTraceToString()}") }
            false
        } finally {
            miniHttpClassInstallations.remove(targetClass, false)
        }
    }

    private fun installXriverHttpHooks(targetClass: Class<*>): Boolean {
        synchronized(installLock) {
            if (!isCaptureEnabled()) return false
            miniHttpClassInstallations.putIfAbsent(targetClass, false)?.let { return it }
        }
        return try {
            val loader = targetClass.classLoader
            val methods = listOf(
                targetClass.getDeclaredMethod(
                    "handleRequest",
                    Class.forName("com.alibaba.ariver.app.api.Page", false, loader),
                    Class.forName("com.alibaba.fastjson.JSONObject", false, loader),
                    java.lang.Long.TYPE,
                ),
                targetClass.getDeclaredMethod(
                    "handleResponse",
                    String::class.java,
                    String::class.java,
                    Class.forName("com.alibaba.xriver.android.node.CRVPage", false, loader),
                ),
            )
            methods.forEach { method ->
                val isRequest = method.name == "handleRequest"
                val expectedReturnType = if (isRequest) java.lang.Boolean.TYPE else java.lang.Void.TYPE
                require(Modifier.isStatic(method.modifiers) && method.returnType == expectedReturnType) {
                    "XRiver HTTP 签名不符: $method"
                }
                if (captureHooks.containsKey(method)) return@forEach
                runCatching {
                    method.isAccessible = true
                    if (!installCaptureHook(method) intercept@ { chain ->
                        val startedAt = SystemClock.elapsedRealtime()
                        var callSession: String? = null
                        val event = runCatching capture@ {
                            if (!isCaptureEnabled()) return@capture null
                            callSession = activeSessionId()
                            RpcTrafficEvent(
                                title = TrafficSource.MINI_HTTP.title,
                                phase = if (isRequest) TrafficPhase.REQUEST else TrafficPhase.RESPONSE,
                                source = TrafficSource.MINI_HTTP,
                                method = "${targetClass.name}#${method.name}",
                                requestId = if (isRequest) newRequestId() else null,
                                payload = snapshotMiniHttpArguments(method, chain.args.toList()),
                                note = if (isRequest) "response_correlation_unavailable" else
                                    "response_correlation_unavailable; ${recordHttpResponseCallSite(method)}",
                                correlationStatus = if (isRequest) null else "UNMATCHED",
                            ).also(::emit)
                        }.onFailure {
                            runCatching { Log.runtime(TAG, "记录 XRiver HTTP 调用失败: $method ${it.stackTraceToString()}") }
                        }.getOrNull()
                        val result = try {
                            chain.proceed()
                        } catch (error: Throwable) {
                            runCatching {
                                if (event != null && isCaptureEnabled() && activeSessionId() == callSession) {
                                    emit(event.copy(
                                        phase = TrafficPhase.ERROR,
                                        payload = null,
                                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                                        note = "${error.javaClass.name}: ${error.message.orEmpty()}",
                                    ))
                                }
                            }.onFailure {
                                runCatching { Log.runtime(TAG, "记录 XRiver HTTP 异常失败: ${it.stackTraceToString()}") }
                            }
                            throw error
                        }
                        if (isRequest && event != null) runCatching {
                            if (isCaptureEnabled() && activeSessionId() == callSession) {
                                // boolean 是桥调用的返回值；异步 HTTP 终态需要独立的关联依据。
                                emit(event.copy(
                                    payload = JSONObject().put("returnValue", result ?: JSONObject.NULL).toString(),
                                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                                    note = "bridge_dispatch_return_not_http_response",
                                ))
                            }
                        }.onFailure {
                            runCatching { Log.runtime(TAG, "记录 XRiver HTTP 返回值失败: ${it.stackTraceToString()}") }
                        }
                        result
                    }) return@runCatching
                    Log.runtime(TAG, "已安装 XRiver HTTP 抓包: $method; loader=$loader, process=${currentProcessName()}")
                }.onFailure {
                    runCatching { Log.runtime(TAG, "安装 XRiver HTTP 抓包失败: $method ${it.stackTraceToString()}") }
                }
            }
            synchronized(installLock) {
                val installed = isCaptureEnabled() && methods.all { captureHooks.containsKey(it) }
                if (installed) miniHttpClassInstallations[targetClass] = true
                installed
            }
        } catch (error: Throwable) {
            runCatching { Log.runtime(TAG, "安装 XRiver HTTP 抓包失败: ${targetClass.name} ${error.stackTraceToString()}") }
            false
        } finally {
            miniHttpClassInstallations.remove(targetClass, false)
        }
    }

    private fun recordHttpResponseCallSite(method: Method): String {
        activeSessionId()
        val loader = method.declaringClass.classLoader
        val observer = "observer=$method; loader=${loader?.javaClass?.name}@${System.identityHashCode(loader)}"
        if (reportedHttpCallSites.get().size >= 32) return observer
        val trace = Throwable().stackTrace.drop(1).joinToString("\n")
        val declaration = "$observer\n$trace"
        while (true) {
            val reported = reportedHttpCallSites.get()
            if (reported.size >= 32 || declaration in reported) return observer
            if (reportedHttpCallSites.compareAndSet(reported, reported + declaration)) break
        }
        runCatching { Log.runtime(TAG, "HTTP 响应观察入口: $declaration") }
        // 随本次响应保存调用位置，避免仅有 runtime 的首个样本而漏掉其他实际入口。
        return declaration
    }

    private fun reportMiniHttpClass(targetClass: Class<*>) {
        if (httpClassDeclarations.containsKey(targetClass)) return
        runCatching {
            val declaration = buildString {
                append("HTTP 接口待确认: ").append(targetClass.name)
                append("; loader=").append(targetClass.classLoader)
                var owner: Class<*>? = targetClass
                while (owner != null && owner != Any::class.java) {
                    append("\nclass=").append(owner.name)
                    owner.interfaces.forEach { contract ->
                        append("\ninterface=").append(contract.name)
                        contract.methods.forEach { append("\n").append(it) }
                    }
                    owner.declaredMethods.forEach { append("\n").append(it) }
                    owner.declaredFields.forEach { append("\n").append(it) }
                    owner = owner.superclass
                }
            }
            if (httpClassDeclarations.putIfAbsent(targetClass, declaration) == null) {
                Log.runtime(TAG, declaration)
                if (isCaptureEnabled()) {
                    val alreadyStarted = sessionStarted
                    activeSessionId()
                    if (alreadyStarted) emit(
                        RpcTrafficEvent(
                            title = TrafficSource.MINI_HTTP.title,
                            phase = TrafficPhase.SESSION_START,
                            source = TrafficSource.MINI_HTTP,
                            method = "capture.http.interface",
                            payload = JSONObject().put("declaration", declaration).toString(),
                        ),
                    )
                }
            }
        }.onFailure {
            runCatching { Log.runtime(TAG, "读取 HTTP 接口声明失败: ${targetClass.name} ${it.stackTraceToString()}") }
        }
    }

    private fun installH5NetworkHook(targetClass: Class<*>): Boolean {
        synchronized(installLock) {
            if (!isCaptureEnabled()) return false
            miniHttpClassInstallations.putIfAbsent(targetClass, false)?.let { return it }
        }
        return try {
            val methods = linkedMapOf<List<Class<*>>, Method>()
            var owner: Class<*>? = targetClass
            while (owner != null && owner != Any::class.java) {
                owner.declaredMethods.filter {
                    it.name == "enqueue" && it.parameterCount == 1 &&
                        Future::class.java.isAssignableFrom(it.returnType) && !Modifier.isAbstract(it.modifiers)
                }.forEach { methods.putIfAbsent(it.parameterTypes.toList(), it) }
                owner = owner.superclass
            }
            if (methods.isEmpty()) error("${targetClass.name}#enqueue 未找到 Request -> Future 签名")
            methods.values.forEach { method ->
                if (captureHooks.containsKey(method)) return@forEach
                runCatching {
                    method.isAccessible = true
                    if (!installCaptureHook(method) intercept@ { chain ->
                        val requestObject = chain.args.getOrNull(0)
                        val pending = runCatching capture@ {
                            if (requestObject == null || !isCaptureEnabled()) return@capture null
                            val payload = snapshotMiniHttpArguments(method, chain.args.toList(), setOf(0))
                            val httpMethod = findField(requestObject.javaClass, "requestMethod")?.get(requestObject) as? String
                            val existing = peekPendingAriverRequest(requestObject)
                            val request = existing ?: PendingHostRequest(
                                requestId = newRequestId(),
                                method = "${targetClass.name}#enqueue",
                                requestPayload = payload,
                                startedAtMs = System.currentTimeMillis(),
                                source = TrafficSource.MINI_HTTP,
                            ).also(::recordHostRequest)
                            if (existing != null) emit(
                                RpcTrafficEvent(
                                    title = request.source.title,
                                    phase = TrafficPhase.REQUEST,
                                    source = request.source,
                                    method = request.method,
                                    requestId = request.requestId,
                                    payload = payload,
                                    note = "transport_request",
                                    correlationStatus = "MATCHED",
                                ),
                            )
                            request.httpMethod = httpMethod
                            putPendingAriverRequest(requestObject, request)
                            request
                        }.onFailure {
                            runCatching { Log.runtime(TAG, "记录 HTTP 传输请求失败: ${it.stackTraceToString()}") }
                        }.getOrNull()
                        val result = try {
                            chain.proceed()
                        } catch (error: Throwable) {
                            runCatching {
                                pending?.let { recordHostError(it, "${error.javaClass.name}: ${error.message.orEmpty()}") }
                            }.onFailure {
                                runCatching { Log.runtime(TAG, "记录 HTTP 传输异常失败: ${it.stackTraceToString()}") }
                            }
                            throw error
                        }
                        if (pending != null) runCatching {
                            if (result is Future<*>) {
                                installHttpFutureHooks(result.javaClass)
                                putPendingAriverRequest(result, pending)
                            } else {
                                recordHostError(pending, "capture_future_missing", "NO_CALLBACK")
                            }
                        }.onFailure {
                            runCatching { Log.runtime(TAG, "关联 HTTP Future 失败: ${it.stackTraceToString()}") }
                        }
                        result
                    }) return@runCatching
                    Log.runtime(TAG, "已安装公共 HTTP 请求抓包: $method; loader=${targetClass.classLoader}")
                }.onFailure {
                    runCatching { Log.runtime(TAG, "安装公共 HTTP 请求抓包失败: $method ${it.stackTraceToString()}") }
                }
            }
            synchronized(installLock) {
                val installed = isCaptureEnabled() && methods.values.all { captureHooks.containsKey(it) }
                if (installed) miniHttpClassInstallations[targetClass] = true
                installed
            }
        } catch (error: Throwable) {
            runCatching { Log.runtime(TAG, "读取公共 HTTP 接口失败: ${error.stackTraceToString()}") }
            false
        } finally {
            miniHttpClassInstallations.remove(targetClass, false)
        }
    }

    private fun installHttpFutureHooks(futureClass: Class<*>) {
        val methods = linkedMapOf<Pair<String, List<Class<*>>>, Method>()
        var owner: Class<*>? = futureClass
        while (owner != null && owner != Any::class.java) {
            owner.declaredMethods.filter {
                !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                    ((it.name == "get" && (it.parameterCount == 0 ||
                        (it.parameterTypes.toList() == listOf(Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java)))) ||
                        (it.name == "cancel" && it.parameterTypes.toList() == listOf(Boolean::class.javaPrimitiveType)))
            }.forEach { methods.putIfAbsent(it.name to it.parameterTypes.toList(), it) }
            owner = owner.superclass
        }
        if (methods.isEmpty()) error("${futureClass.name} 没有可观察的 Future.get/cancel")
        methods.values.forEach { method ->
            if (captureHooks.containsKey(method)) return@forEach
            runCatching {
                method.isAccessible = true
                if (!installCaptureHook(method) intercept@ { chain ->
                    val future = chain.getThisObject() ?: return@intercept chain.proceed()
                    val observed = runCatching {
                        synchronized(pendingLock) {
                            pendingAriverRequests[WeakIdentityKey(future)]?.let { links ->
                                Triple(if (links.ambiguous) null else links.requests.singleOrNull(), links.completed, links.ambiguous)
                            }
                        }
                    }.getOrNull()
                    if (observed == null || observed.second) return@intercept chain.proceed()
                    val calls = responseHookCalls.get() ?: IdentityHashMap<Any, Boolean>().also(responseHookCalls::set)
                    if (calls.put(future, true) != null) return@intercept chain.proceed()
                    val pending = observed.first
                    val capturedSession = sessionId
                    try {
                        val result = try {
                            chain.proceed()
                        } catch (error: Throwable) {
                            runCatching {
                                if (isCaptureEnabled() && activeSessionId() == capturedSession) {
                                    val waitOnly = error is InterruptedException || error is java.util.concurrent.TimeoutException
                                    if (pending != null && method.name == "get" && !waitOnly) {
                                        recordHostError(
                                            pending,
                                            error.stackTraceToString(),
                                            if (error is java.util.concurrent.CancellationException) "CANCELLED" else null,
                                        )
                                    } else {
                                        emit(
                                            RpcTrafficEvent(
                                                title = TrafficSource.MINI_HTTP.title,
                                                phase = TrafficPhase.ERROR,
                                                source = TrafficSource.MINI_HTTP,
                                                method = pending?.method ?: "${futureClass.name}#${method.name}",
                                                requestId = pending?.requestId,
                                                note = error.stackTraceToString(),
                                                correlationStatus = when {
                                                    error is InterruptedException -> "WAIT_INTERRUPTED"
                                                    error is java.util.concurrent.TimeoutException -> "WAIT_TIMEOUT"
                                                    method.name == "cancel" -> "CANCEL_FAILED"
                                                    else -> "UNMATCHED"
                                                },
                                            ),
                                        )
                                    }
                                }
                            }.onFailure {
                                runCatching { Log.runtime(TAG, "记录 HTTP Future 异常失败: ${it.stackTraceToString()}") }
                            }
                            throw error
                        }
                        runCatching {
                            val completed = synchronized(pendingLock) {
                                pendingAriverRequests[WeakIdentityKey(future)]?.completed == true
                            }
                            if (isCaptureEnabled() && activeSessionId() == capturedSession && !completed) {
                                if (method.name == "cancel") {
                                    if (result == true && pending != null) recordHostError(pending, "request_cancelled", "CANCELLED")
                                } else {
                                    if (pending != null) {
                                        observeHttpResponseBody(pending, result, capturedSession)
                                    } else emit(
                                        RpcTrafficEvent(
                                            title = TrafficSource.MINI_HTTP.title,
                                            phase = TrafficPhase.RESPONSE,
                                            source = TrafficSource.MINI_HTTP,
                                            method = "${futureClass.name}#get",
                                            payload = snapshotMiniHttpValue(result, IdentityHashMap(), expandFields = true).toString(),
                                            note = "response_correlation_unavailable",
                                            correlationStatus = if (observed.third) "AMBIGUOUS" else "UNMATCHED",
                                        ),
                                    )
                                }
                            }
                        }.onFailure {
                            runCatching { Log.runtime(TAG, "记录 HTTP Future 结果失败: ${it.stackTraceToString()}") }
                        }
                        result
                    } finally {
                        calls.remove(future)
                        if (calls.isEmpty()) responseHookCalls.remove()
                    }
                }) return@runCatching
                Log.runtime(TAG, "已安装 HTTP Future 观察: $method; loader=${futureClass.classLoader}")
            }.onFailure {
                runCatching { Log.runtime(TAG, "安装 HTTP Future 观察失败: $method ${it.stackTraceToString()}") }
            }
        }
    }

    private fun observeHttpResponseBody(pending: PendingHostRequest, value: Any?, capturedSession: String?) {
        if (capturedSession == null || !isCaptureEnabled() || activeSessionId() != capturedSession) return
        val calls = bodyAccessorCalls.get() ?: IdentityHashMap<PendingHostRequest, Boolean>().also(bodyAccessorCalls::set)
        val alreadyObserving = calls.put(pending, true) != null
        try {
            val streams = mutableListOf<java.io.InputStream>()
            val bodyObjects = mutableListOf<Any>()
            val snapshot = snapshotMiniHttpValue(value, IdentityHashMap(), streams, expandFields = true, bodyObjects = bodyObjects)
            // 状态来自 HttpResponse/StatusLine 契约快照，不从宿主私有字段猜测正文是否完整。
            val status = ((snapshot as? JSONObject)?.optJSONObject("properties")
                ?.optJSONObject("getStatusLine")?.optJSONObject("properties")
                ?.opt("getStatusCode") as? Number)?.toInt()
            val noBody = (pending.httpMethod == "HEAD" && value != null) || status == 204 || status == 205 || status == 304 ||
                (status != null && status in 100..199)
            val buffered = value is ByteArray || value is String || (value is ByteBuffer && value.position() == 0)
            val terminal = noBody || buffered
            val note = when {
                noBody -> "network_response_has_no_body"
                buffered -> "response_body_buffered"
                else -> "response_body_pending"
            }
            val payload = (snapshot as? JSONObject ?: JSONObject().put("body", snapshot))
                .put("complete", if (terminal) true else JSONObject.NULL).toString()
            if (!recordHostResponse(pending, payload, terminal, note)) {
                emit(RpcTrafficEvent(
                    title = pending.source.title, phase = TrafficPhase.RESPONSE, source = pending.source,
                    method = pending.method, requestId = pending.requestId, payload = payload,
                    note = "capture_context_expired; $note", correlationStatus = "LATE_RESPONSE",
                ))
                return
            }
            if (buffered && !noBody) return
            if (!noBody && streams.isEmpty()) {
                if (value != null && value !is java.io.InputStream) bodyObjects.add(value)
                val observedObjects = IdentityHashMap<Any, Boolean>()
                bodyObjects.forEach { bodyObject ->
                    if (observedObjects.put(bodyObject, true) == null) {
                        putPendingAriverRequest(bodyObject, pending)
                        installHttpBodyAccessorHooks(bodyObject.javaClass)
                    }
                }
            }
            val observedStreams = IdentityHashMap<java.io.InputStream, Boolean>()
            streams.forEach { stream ->
                if (observedStreams.put(stream, true) == null) {
                    val installed = installHttpStreamHooks(stream.javaClass)
                    if (!installed) emit(RpcTrafficEvent(
                        title = pending.source.title, phase = TrafficPhase.ERROR, source = pending.source,
                        method = pending.method, requestId = pending.requestId,
                        note = "stream_observer_incomplete: ${stream.javaClass.name}",
                        correlationStatus = "BODY_OBSERVER_UNAVAILABLE",
                    ))
                    if (!noBody) putPendingAriverRequest(stream, pending)
                    synchronized(pendingLock) {
                        val links = pendingAriverRequests.getOrPut(WeakIdentityKey(stream, pendingAriverRequestQueue)) {
                            PendingRequestLinks()
                        }
                        if (!links.ambiguous && links.body == null) {
                            links.body = HttpBodyObservation(pending, capturedSession, System.identityHashCode(stream), noBody)
                                .also { it.incomplete = !installed }
                            schedulePendingCleanupLocked()
                        } else if (links.ambiguous || links.body?.pending !== pending) {
                            links.body = null
                            links.ambiguous = true
                        }
                    }
                }
            }
        } finally {
            if (!alreadyObserving) calls.remove(pending)
            if (calls.isEmpty()) bodyAccessorCalls.remove()
        }
    }

    private fun installHttpBodyAccessorHooks(bodyClass: Class<*>) {
        val contracts = listOf("org.apache.http.HttpResponse", "org.apache.http.HttpEntity").mapNotNull { name ->
            runCatching { Class.forName(name, false, bodyClass.classLoader) }.getOrNull()
        }
        val methods = bodyClass.methods.filter { method ->
            !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) && method.parameterCount == 0 &&
                (java.io.InputStream::class.java.isAssignableFrom(method.returnType) ||
                    contracts.any { it.isAssignableFrom(method.returnType) })
        }
        methods.forEach { method ->
            if (captureHooks.containsKey(method)) return@forEach
            runCatching {
                method.isAccessible = true
                if (!installCaptureHook(method) intercept@ { chain ->
                    val owner = chain.getThisObject() ?: return@intercept chain.proceed()
                    val pending = runCatching { peekPendingAriverRequest(owner) }.getOrNull()
                        ?: return@intercept chain.proceed()
                    // 已绑定实际流时继续沿原观察路径，避免包装流再生成一个永远等不到 EOF 的上下文。
                    val observingStream = synchronized(pendingLock) {
                        pendingAriverRequests.values.any { it.body?.let { body -> body.pending === pending && !body.finished } == true }
                    }
                    if (observingStream) return@intercept chain.proceed()
                    val capturedSession = runCatching { if (isCaptureEnabled()) activeSessionId() else null }.getOrNull()
                        ?: return@intercept chain.proceed()
                    val calls = bodyAccessorCalls.get() ?: IdentityHashMap<PendingHostRequest, Boolean>().also(bodyAccessorCalls::set)
                    // 同一请求的实体/取流嵌套只绑定最外层结果，不抑制宿主实际读取流时的观察。
                    if (calls.put(pending, true) != null) return@intercept chain.proceed()
                    try {
                        val result = try {
                            chain.proceed()
                        } catch (error: Throwable) {
                            runCatching {
                                if (isCaptureEnabled() && activeSessionId() == capturedSession) {
                                    recordHostError(pending, "body_accessor_error: ${error.stackTraceToString()}", "BODY_INCOMPLETE")
                                }
                            }.onFailure { runCatching { Log.runtime(TAG, "记录 HTTP 取流异常失败: ${it.stackTraceToString()}") } }
                            throw error
                        }
                        runCatching { observeHttpResponseBody(pending, result, capturedSession) }.onFailure {
                            runCatching { Log.runtime(TAG, "记录 HTTP 延迟正文失败: ${it.stackTraceToString()}") }
                        }
                        result
                    } finally {
                        calls.remove(pending)
                        if (calls.isEmpty()) bodyAccessorCalls.remove()
                    }
                }) return@runCatching
                Log.runtime(TAG, "已安装 HTTP 实体/取流观察: $method; loader=${bodyClass.classLoader}")
            }.onFailure {
                runCatching { Log.runtime(TAG, "安装 HTTP 实体/取流观察失败: $method ${it.stackTraceToString()}") }
            }
        }
    }

    private fun installHttpStreamHooks(streamClass: Class<*>): Boolean {
        val methods = streamClass.methods.filter { method ->
            if (Modifier.isAbstract(method.modifiers) || Modifier.isStatic(method.modifiers)) return@filter false
            val types = method.parameterTypes.toList()
            when (method.name) {
                "read" -> types.isEmpty() || types == listOf(ByteArray::class.java) ||
                    types == listOf(ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                "skip" -> types == listOf(Long::class.javaPrimitiveType)
                "mark" -> types == listOf(Int::class.javaPrimitiveType)
                "reset", "close" -> types.isEmpty()
                else -> false
            }
        }
        methods.forEach { method ->
            val installation = synchronized(installLock) {
                if (captureHooks.containsKey(method)) return@forEach
                runCatching {
                    method.isAccessible = true
                    if (!installCaptureHook(method) intercept@ { chain ->
                        val stream = chain.getThisObject() ?: return@intercept chain.proceed()
                        val body = runCatching {
                            // 包括继承的方法；未关联的流直接执行宿主调用，不读取配置或流内容。
                            val observed = synchronized(pendingLock) {
                                pendingAriverRequests[WeakIdentityKey(stream)]?.takeUnless { it.ambiguous }?.body
                            } ?: return@runCatching null
                            if (!isCaptureEnabled() || activeSessionId() != observed.session) null else observed
                        }.getOrNull() ?: return@intercept chain.proceed()
                        val calls = responseHookCalls.get() ?: IdentityHashMap<Any, Boolean>().also(responseHookCalls::set)
                        // 同一请求的包装流相互委托时，只记录最外层交给宿主的字节。
                        if (calls.put(body.pending, true) != null) return@intercept chain.proceed()
                        try {
                            val result = try {
                                chain.proceed()
                            } catch (error: Throwable) {
                                runCatching {
                                    synchronized(body) {
                                        body.incomplete = true
                                        recordHttpBody(
                                            body, JSONObject().put("error", error.stackTraceToString()), "body_operation_error",
                                            method.name == "read" || method.name == "close",
                                        )
                                    }
                                }.onFailure {
                                    runCatching { Log.runtime(TAG, "记录 HTTP 流异常失败: ${it.stackTraceToString()}") }
                                }
                                throw error
                            }
                            runCatching {
                                synchronized(body) {
                                    when (method.name) {
                                        "read" -> {
                                            val count = result as Int
                                            if (count < 0) {
                                                recordHttpBody(body, JSONObject(), "body_eof", true)
                                            } else if (method.parameterCount == 0 || count > 0) {
                                                val bytes = if (method.parameterCount == 0) {
                                                    byteArrayOf(count.toByte())
                                                } else {
                                                    val buffer = chain.args[0] as ByteArray
                                                    val offset = if (method.parameterCount == 3) chain.args[1] as Int else 0
                                                    buffer.copyOfRange(offset, offset + count)
                                                }
                                                val payload = JSONObject().put("offset", if (body.position < 0) JSONObject.NULL else body.position)
                                                    .put("bytes", snapshotMiniHttpValue(bytes, IdentityHashMap()))
                                                if (body.position >= 0) body.position += bytes.size
                                                recordHttpBody(body, payload, "body_chunk")
                                            }
                                        }
                                        "skip" -> {
                                            val skipped = result as Long
                                            if (skipped > 0) {
                                                body.incomplete = true
                                                val payload = JSONObject().put("offset", if (body.position < 0) JSONObject.NULL else body.position)
                                                    .put("skipped", skipped)
                                                if (body.position >= 0) body.position += skipped
                                                recordHttpBody(body, payload, "body_skipped")
                                            }
                                        }
                                        "mark" -> body.markPosition = body.position.takeIf { it >= 0 }
                                        "reset" -> {
                                            val position = body.markPosition
                                            if (position == null) {
                                                body.incomplete = true
                                                body.position = -1L
                                            } else body.position = position
                                            recordHttpBody(body, JSONObject().put("offset", position ?: JSONObject.NULL), "body_reset")
                                        }
                                        "close" -> recordHttpBody(
                                            body, JSONObject(),
                                            if (body.incomplete) "body_closed_before_eof" else "body_closed_completion_unknown",
                                            terminal = true, complete = null,
                                        )
                                    }
                                }
                            }.onFailure { error ->
                                body.incomplete = true
                                runCatching {
                                    if (isCaptureEnabled() && activeSessionId() == body.session) emit(
                                        RpcTrafficEvent(
                                            title = body.pending.source.title,
                                            phase = TrafficPhase.ERROR,
                                            source = body.pending.source,
                                            method = body.pending.method,
                                            requestId = body.pending.requestId,
                                            note = "capture_body_error: ${error.stackTraceToString()}",
                                            correlationStatus = "CAPTURE_ERROR",
                                        ),
                                    )
                                    Log.runtime(TAG, "记录 HTTP 流读取失败: ${error.stackTraceToString()}")
                                }
                            }
                            result
                        } finally {
                            calls.remove(body.pending)
                            if (calls.isEmpty()) responseHookCalls.remove()
                        }
                    }) return@runCatching
                }
            }
            installation.onSuccess {
                if (captureHooks.containsKey(method)) runCatching { Log.runtime(TAG, "已安装 HTTP 流观察: $method; loader=${streamClass.classLoader}") }
            }.onFailure {
                runCatching { Log.runtime(TAG, "安装 HTTP 流观察失败: $method ${it.stackTraceToString()}") }
            }
        }
        return methods.any { it.name == "read" } && methods.all { captureHooks.containsKey(it) }
    }

    private fun recordHttpBody(
        body: HttpBodyObservation,
        payload: JSONObject,
        note: String,
        terminal: Boolean = false,
        complete: Boolean? = true,
    ) {
        if (body.finished || !isCaptureEnabled() || activeSessionId() != body.session) return
        val attached = synchronized(pendingLock) {
            pendingAriverRequests.values.any { it.body === body && !it.ambiguous }
        }
        if (!attached && !(terminal && body.afterNoBodyResponse)) return
        body.lastActivityElapsedMs = SystemClock.elapsedRealtime()
        payload.put("streamIdentity", body.streamIdentity)
            .put("bodySource", if (body.afterNoBodyResponse) "representation_after_no_body_response" else "response_stream")
        val bodyComplete = if (body.incomplete) false else complete
        if (terminal) payload.put("complete", bodyComplete ?: JSONObject.NULL)
            .put("position", if (body.position < 0) JSONObject.NULL else body.position)
        if (body.afterNoBodyResponse) {
            emit(
                RpcTrafficEvent(
                    title = body.pending.source.title,
                    phase = TrafficPhase.RESPONSE,
                    source = body.pending.source,
                    method = body.pending.method,
                    requestId = body.pending.requestId,
                    payload = payload.toString(),
                    note = note,
                    correlationStatus = "MATCHED",
                ),
            )
        } else {
            val awaitingOtherBody = synchronized(pendingLock) {
                pendingAriverRequests.values.any {
                    val other = it.body
                    other != null && other !== body && other.pending === body.pending && !other.finished
                }
            }
            val recorded = recordHostResponse(body.pending, payload.toString(), terminal && bodyComplete != false && !awaitingOtherBody, note)
            if (terminal && bodyComplete == false && recorded) {
                recordHostError(body.pending, note, "BODY_INCOMPLETE")
            }
        }
        if (terminal) synchronized(pendingLock) {
            body.finished = true
            pendingAriverRequests.values.forEach { if (it.body === body) it.body = null }
            schedulePendingCleanupLocked()
        }
    }

    private fun snapshotMiniHttpArguments(method: Method, args: List<Any?>, dataArguments: Set<Int> = emptySet()): String {
        val values = JSONArray()
        val visited = IdentityHashMap<Any, Boolean>()
        args.forEachIndexed { index, value ->
            val entry = JSONObject()
                .put("index", index)
                .put("type", method.parameterTypes.getOrNull(index)?.name ?: JSONObject.NULL)
            runCatching {
                entry.put("value", snapshotMiniHttpValue(value, visited, expandFields = index in dataArguments))
            }.onFailure {
                entry.put("readError", "${it.javaClass.name}: ${it.message.orEmpty()}")
            }
            values.put(entry)
        }
        return JSONObject().put("signature", method.toString()).put("arguments", values).toString()
    }

    private fun snapshotMiniHttpValue(
        value: Any?,
        visited: IdentityHashMap<Any, Boolean>,
        streams: MutableList<java.io.InputStream>? = null,
        expandFields: Boolean = false,
        bodyObjects: MutableList<Any>? = null,
    ): Any {
        when (value) {
            null -> return JSONObject.NULL
            is String, is Number, is Boolean -> return value
            is java.net.URL -> return value.toExternalForm()
            is java.net.URI -> return value.toString()
            is Char -> return value.toString()
            is Enum<*> -> return value.name
            is JSONObject, is JSONArray -> return value.toString()
            is ByteArray -> return JSONObject()
                .put("encoding", "base64")
                .put("size", value.size)
                .put("data", Base64.encodeToString(value, Base64.NO_WRAP))
            is ByteBuffer -> {
                val buffer = value.asReadOnlyBuffer()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                return JSONObject()
                    .put("position", value.position())
                    .put("limit", value.limit())
                    .put("bytes", snapshotMiniHttpValue(bytes, visited))
            }
        }
        val type = value.javaClass
        val reference = JSONObject().put("type", type.name).put("identity", System.identityHashCode(value))
        if (visited.put(value, true) != null) return reference
        return try {
            when {
                value is Map<*, *> -> JSONArray().apply {
                    value.forEach { (key, item) ->
                        put(
                            JSONObject()
                                .put("key", snapshotMiniHttpValue(key, visited, streams, bodyObjects = bodyObjects))
                                .put("value", snapshotMiniHttpValue(item, visited, streams, bodyObjects = bodyObjects)),
                        )
                    }
                }
                value is Collection<*> -> JSONArray().apply {
                    value.forEach { put(snapshotMiniHttpValue(it, visited, streams, bodyObjects = bodyObjects)) }
                }
                type.isArray -> JSONArray().apply {
                    for (index in 0 until java.lang.reflect.Array.getLength(value)) {
                        put(snapshotMiniHttpValue(java.lang.reflect.Array.get(value, index), visited, streams, bodyObjects = bodyObjects))
                    }
                }
                value is java.io.InputStream -> {
                    streams?.add(value)
                    reference.put("note", "stream_not_consumed")
                }
                value is java.io.OutputStream -> reference.put("note", "stream_not_consumed")
                type.name == "org.apache.http.message.HeaderGroup" -> snapshotMiniHttpValue(
                    type.getMethod("getAllHeaders").invoke(value), visited, streams, bodyObjects = bodyObjects,
                )
                runCatching {
                    Class.forName("org.apache.http.Header", false, type.classLoader).isInstance(value)
                }.getOrDefault(false) -> reference
                    .put("name", findMethod(type, "getName", 0)?.invoke(value) ?: JSONObject.NULL)
                    .put("value", findMethod(type, "getValue", 0)?.invoke(value) ?: JSONObject.NULL)
                else -> {
                    val properties = JSONObject()
                    val contracts = listOf(
                        "org.apache.http.NameValuePair" to listOf("getName", "getValue"),
                        "org.apache.http.HttpMessage" to listOf("getProtocolVersion", "getAllHeaders"),
                        "org.apache.http.HttpRequest" to listOf("getRequestLine"),
                        "org.apache.http.HttpResponse" to listOf("getStatusLine", "getEntity"),
                        "org.apache.http.RequestLine" to listOf("getMethod", "getUri", "getProtocolVersion"),
                        "org.apache.http.StatusLine" to listOf("getStatusCode", "getReasonPhrase", "getProtocolVersion"),
                        "org.apache.http.ProtocolVersion" to listOf("getProtocol", "getMajor", "getMinor"),
                        "org.apache.http.HttpEntity" to listOf(
                            "getContentLength", "getContentType", "getContentEncoding", "isChunked", "isStreaming", "isRepeatable",
                        ),
                    )
                    contracts.forEach { (name, methods) ->
                        val contract = runCatching { Class.forName(name, false, type.classLoader) }.getOrNull()
                        if (contract != null && contract.isInstance(value)) {
                            if (name == "org.apache.http.HttpResponse" || name == "org.apache.http.HttpEntity") {
                                bodyObjects?.add(value)
                            }
                            methods.forEach { name ->
                                runCatching {
                                    properties.put(name, snapshotMiniHttpValue(
                                        contract.getMethod(name).invoke(value), visited, streams, bodyObjects = bodyObjects,
                                    ))
                                }.onFailure {
                                    properties.put(name, JSONObject().put("readError", "${it.javaClass.name}: ${it.message.orEmpty()}"))
                                }
                            }
                            if (name == "org.apache.http.HttpEntity") reference.put("note", "entity_body_not_read_by_snapshot")
                        }
                    }
                    if (properties.length() > 0) reference.put("properties", properties)
                    // 根对象由实际调用契约确定；已有嵌套数据模型继续展开，运行时引用不任意递归。
                    val readFields = expandFields || generateSequence<Class<*>>(type) { it.superclass }.any {
                        it.name == H5_REQUEST_CLASS ||
                            it.name == "com.alipay.mobile.common.transport.h5.H5HttpUrlResponse" ||
                            it.name == "com.alipay.mobile.common.transport.http.HttpUrlRequest" ||
                            it.name == "com.alipay.mobile.common.transport.http.HttpUrlResponse" ||
                            it.name == "com.alipay.mobile.common.transport.http.HttpUrlHeader" ||
                            it.name == "org.apache.http.entity.ByteArrayEntity"
                    }
                    if (readFields) {
                        val fields = JSONArray()
                        var current: Class<*>? = type
                        while (current != null && current != Any::class.java) {
                            current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
                                val entry = JSONObject()
                                    .put("owner", field.declaringClass.name)
                                    .put("name", field.name)
                                    .put("type", field.type.name)
                                runCatching {
                                    field.isAccessible = true
                                    entry.put("value", snapshotMiniHttpValue(field.get(value), visited, streams, bodyObjects = bodyObjects))
                                }.onFailure {
                                    entry.put("readError", "${it.javaClass.name}: ${it.message.orEmpty()}")
                                }
                                fields.put(entry)
                            }
                            current = current.superclass
                        }
                        reference.put("fields", fields)
                    } else if (properties.length() == 0) {
                        reference.put("note", "object_fields_not_observed")
                    }
                    reference
                }
            }
        } finally {
            visited.remove(value)
        }
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
            if (!installCaptureHook(rpcMethod) intercept@ { chain ->
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
                            installAriverResponseClass(callback.javaClass)
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
            }) return@runCatching false
            ariverRequestHookInstalled.set(true)
            Log.runtime(TAG, "已安装 RpcBridgeExtension#rpc 抓包")
            true
        }.onFailure {
            Log.runtime(TAG, "安装 RpcBridgeExtension#rpc 抓包失败: ${it.message}")
        }.getOrDefault(false)
    }

    private fun installMtopRequestHook(bridgeClass: Class<*>): Boolean {
        synchronized(installLock) {
            if (!isCaptureEnabled()) return false
            if (mtopRequestHookInstalled.get()) return true
            miniHttpClassInstallations.putIfAbsent(bridgeClass, false)?.let { return it }
        }
        return runCatching {
            val classLoader = bridgeClass.classLoader
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
            if (!installCaptureHook(mtopMethod) intercept@ { chain ->
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
                            installAriverResponseClass(callback.javaClass)
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
            }) return@runCatching false
            mtopRequestHookInstalled.set(true)
            Log.runtime(TAG, "已安装 MtopBridgeExtention#sendMtop 抓包")
            true
        }.onFailure {
            Log.error(TAG, "安装 MtopBridgeExtention#sendMtop 抓包失败: ${it.stackTraceToString()}")
        }.getOrDefault(false).also { installed ->
            if (installed) {
                miniHttpClassInstallations[bridgeClass] = true
            } else {
                miniHttpClassInstallations.remove(bridgeClass, false)
            }
        }
    }

    private fun installAriverResponseHook(classLoader: ClassLoader): Boolean = runCatching {
        installAriverResponseClass(
            Class.forName("com.alibaba.ariver.engine.common.bridge.internal.DefaultBridgeCallback", false, classLoader),
        )
    }.onFailure {
        runCatching { Log.runtime(TAG, "安装 BridgeCallback 抓包失败: ${it.stackTraceToString()}") }
    }.getOrDefault(false)

    private fun installAriverResponseClass(callbackClass: Class<*>): Boolean = runCatching {
        val methods = linkedMapOf<Pair<String, List<Class<*>>>, Method>()
        var owner: Class<*>? = callbackClass
        while (owner != null && owner != Any::class.java) {
            owner.declaredMethods.filter { method ->
                !Modifier.isAbstract(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                    ((method.name == "sendJSONResponse" &&
                        method.parameterTypes.firstOrNull()?.name == General.JSON_OBJECT_NAME &&
                        (method.parameterCount == 1 ||
                            (method.parameterCount == 2 && method.parameterTypes[1] == Boolean::class.javaPrimitiveType))) ||
                        (method.name == "sendBridgeResponse" && method.parameterCount == 1 &&
                            method.parameterTypes[0].name == "com.alibaba.ariver.engine.api.bridge.extension.BridgeResponse"))
            }.forEach { methods.putIfAbsent(it.name to it.parameterTypes.toList(), it) }
            owner = owner.superclass
        }
        methods.values.forEach { method ->
            if (captureHooks.containsKey(method)) return@forEach
            runCatching {
                method.isAccessible = true
                if (!installCaptureHook(method) intercept@ { chain ->
                    val callback = chain.getThisObject()
                    val calls = responseHookCalls.get() ?: IdentityHashMap<Any, Boolean>().also(responseHookCalls::set)
                    val outermost = callback != null && calls.put(callback, true) == null
                    try {
                        if (outermost) runCatching {
                            if (isCaptureEnabled()) {
                                val result = chain.args.getOrNull(0)
                                val responsePayload = if (method.name == "sendBridgeResponse") {
                                    val bridgeResult = result ?: if (method.name == "sendBridgeResponse") {
                                        method.parameterTypes[0].getField("SUCCESS").get(null)
                                    } else null
                                    bridgeResult?.let { findMethod(it.javaClass, "get", 0)?.invoke(it)?.toString() }
                                } else result?.toString()
                                val kept = if (method.parameterCount == 2) {
                                    chain.args.getOrNull(1) as? Boolean ?: false
                                } else {
                                    findMethod(callback.javaClass, "isSticky", 0)?.invoke(callback) as? Boolean ?: false
                                }
                                val matched = responsePayload != null &&
                                    recordPendingAriverResponse(callback, responsePayload, terminal = !kept)
                                val links = synchronized(pendingLock) {
                                    pendingAriverRequests[WeakIdentityKey(callback)]?.let {
                                        Triple(it.completed, it.ambiguous, it.source to it.requests.map { request -> request.requestId })
                                    }
                                }
                                if (!matched && links?.first != true && responsePayload != null) {
                                    val source = links?.third?.first ?: TrafficSource.ARIVER_RPC
                                    emit(
                                        RpcTrafficEvent(
                                            title = source.title,
                                            phase = TrafficPhase.RESPONSE,
                                            source = source,
                                            method = extractMtopApiName(responsePayload) ?: "unknown",
                                            payload = responsePayload,
                                            note = if (links?.second == true) {
                                                "ambiguous_callback: ${links.third.second}"
                                            } else null,
                                            correlationStatus = if (links?.second == true) "AMBIGUOUS" else "UNMATCHED",
                                        ),
                                    )
                                }
                            }
                        }.onFailure {
                            runCatching { Log.runtime(TAG, "记录 BridgeCallback 响应失败: ${it.stackTraceToString()}") }
                        }
                        chain.proceed()
                    } finally {
                        if (outermost) calls.remove(callback)
                        if (calls.isEmpty()) responseHookCalls.remove()
                    }
                }) return@runCatching
                Log.runtime(TAG, "已安装 BridgeCallback 响应抓包: $method; loader=${callbackClass.classLoader}")
            }.onFailure {
                runCatching { Log.runtime(TAG, "安装 BridgeCallback 响应抓包失败: $method ${it.stackTraceToString()}") }
            }
        }
        methods.isNotEmpty() && methods.values.all { captureHooks.containsKey(it) }
    }.onFailure {
        runCatching { Log.runtime(TAG, "读取 BridgeCallback 接口失败: ${callbackClass.name} ${it.stackTraceToString()}") }
    }.getOrDefault(false)

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
            if (!installCaptureHook(rpcCallMethod) intercept@ { chain ->
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
            }) return@runCatching false
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
        if (pending.callbackKept) return
        val responsePayload = readAriverResponseField(callback)
        if (!responsePayload.isNullOrBlank() && responsePayload != pending.responseBeforeRequest) {
            recordPendingAriverResponse(callback, responsePayload)
        }
    }

    private fun recordPendingAriverResponse(callback: Any, responsePayload: String, terminal: Boolean = true): Boolean {
        if (!isCaptureEnabled()) {
            removePendingAriverRequests(callback)
            return false
        }
        val pending = peekPendingAriverRequest(callback) ?: return false
        if (!terminal) pending.callbackKept = true
        return recordHostResponse(pending, responsePayload, terminal)
    }

    private fun recordHostRequest(pending: PendingHostRequest) {
        if (!isCaptureEnabled()) {
            return
        }
        // 会话切换先清理旧上下文，再登记本次请求。
        activeSessionId()
        drainCollectedPendingAriverRequests()
        emit(
            RpcTrafficEvent(
                title = pending.source.title,
                phase = TrafficPhase.REQUEST,
                source = pending.source,
                method = pending.method,
                requestId = pending.requestId,
                payload = pending.requestPayload,
            ),
        )
        synchronized(pendingLock) {
            pendingAriverRequestsById[pending.requestId] = pending
            schedulePendingCleanupLocked()
        }
    }

    private fun recordHostResponse(
        pending: PendingHostRequest,
        responsePayload: String?,
        terminal: Boolean = true,
        note: String? = null,
    ): Boolean {
        if (!isCaptureEnabled()) return false
        activeSessionId()
        val recorded = if (terminal) {
            takePendingHostRequest(pending, completed = true)
        } else synchronized(pendingLock) {
            if (pendingAriverRequestsById[pending.requestId] !== pending) {
                false
            } else {
                pending.lastActivityElapsedMs = SystemClock.elapsedRealtime()
                schedulePendingCleanupLocked()
                true
            }
        }
        if (!recorded) return false
        emit(
            RpcTrafficEvent(
                title = pending.source.title,
                phase = TrafficPhase.RESPONSE,
                source = pending.source,
                method = pending.method,
                requestId = pending.requestId,
                payload = responsePayload,
                elapsedMs = SystemClock.elapsedRealtime() - pending.startedAtElapsedMs,
                note = note ?: if (terminal) null else "callback_kept",
                correlationStatus = "MATCHED",
            ),
        )
        if (terminal) forwardCapturedHostResponse(
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
        if (!isCaptureEnabled()) return
        activeSessionId()
        if (!takePendingHostRequest(pending, completed = correlationStatus == null || correlationStatus == "CANCELLED")) return
        emit(
            RpcTrafficEvent(
                title = pending.source.title,
                phase = TrafficPhase.ERROR,
                source = pending.source,
                method = pending.method,
                requestId = pending.requestId,
                elapsedMs = SystemClock.elapsedRealtime() - pending.startedAtElapsedMs,
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
        if (source == TrafficSource.MINI_HTTP || source == TrafficSource.MODULE_HTTP ||
            RuntimeIdentityGuard.isCaptureOnlyProcess() || !isCaptureEnabled()
        ) {
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
        if (!isCaptureEnabled()) return
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
        if (isCaptureEnabled()) Log.capture(message)
    }

    @JvmStatic
    fun resetCaptureSession() {
        synchronized(sessionLock) {
            clearPendingAriverRequests()
            sessionId = null
            sessionStarted = false
            eventSequence.set(0L)
            captureSessionCache = null
            reportedHttpClasses.clear()
            reportedHttpCallSites.set(emptySet())
            runCatching { Files.child(Files.CONFIG_DIR, CAPTURE_SESSION_FILE_NAME).delete() }
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
                clearPendingAriverRequests()
                reportedHttpClasses.clear()
                reportedHttpCallSites.set(emptySet())
                sessionId = resolved
                sessionStarted = false
                eventSequence.set(0L)
            }
            if (!sessionStarted && isCaptureEnabled()) {
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
                        put("payload", JSONObject().apply {
                            put("httpInterfaces", JSONArray(httpClassDeclarations.values.toList()))
                            put("httpHooks", JSONArray(captureHooks.keys.map { it.toString() }))
                        })
                    }.toString(),
                )
            }
            resolved
        }

    private fun readPersistedSessionId(): String? =
        runCatching {
            val now = System.currentTimeMillis()
            val file = Files.child(Files.CONFIG_DIR, CAPTURE_SESSION_FILE_NAME)
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
                JSONObject(Files.readText(file))
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
            val file = Files.child(Files.CONFIG_DIR, CAPTURE_SESSION_FILE_NAME)
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
        synchronized(pendingLock) {
            if (pendingAriverRequestsById[pending.requestId] !== pending) return
            val links = pendingAriverRequests.getOrPut(WeakIdentityKey(callback, pendingAriverRequestQueue)) {
                PendingRequestLinks()
            }
            if (links.requests.any { it.requestId == pending.requestId }) return
            if (links.completed) {
                links.completed = false
                links.ambiguous = false
            }
            if (links.requests.isEmpty()) {
                links.source = pending.source
            } else {
                links.ambiguous = true
                if (links.source != pending.source) links.source = null
            }
            links.requests.add(pending)
        }
    }

    private fun peekPendingAriverRequest(callback: Any): PendingHostRequest? = synchronized(pendingLock) {
        val links = pendingAriverRequests[WeakIdentityKey(callback)] ?: return@synchronized null
        if (links.ambiguous) null else links.requests.singleOrNull()
    }

    private fun removePendingAriverRequest(callback: Any, requestId: String? = null): PendingHostRequest? =
        synchronized(pendingLock) {
            val key = WeakIdentityKey(callback)
            val links = pendingAriverRequests[key] ?: return@synchronized null
            val pending = if (requestId == null) {
                if (links.ambiguous) null else links.requests.singleOrNull()
            } else links.requests.firstOrNull { it.requestId == requestId }
            if (pending != null) links.requests.remove(pending)
            if (links.requests.isEmpty()) pendingAriverRequests.remove(key, links)
            pending
        }

    private fun takePendingHostRequest(pending: PendingHostRequest, completed: Boolean = false): Boolean = synchronized(pendingLock) {
        if (!pendingAriverRequestsById.remove(pending.requestId, pending)) return@synchronized false
        pendingAriverRequests.values.forEach { links ->
            if (links.body?.pending === pending && links.body?.afterNoBodyResponse == false) links.body = null
            if (links.requests.remove(pending) && links.requests.isEmpty()) {
                links.completed = completed
                if (!completed) links.ambiguous = true
            }
        }
        schedulePendingCleanupLocked()
        true
    }

    private fun schedulePendingCleanupLocked() {
        pendingCleanupHandler.removeCallbacks(pendingCleanup)
        val nextActivity = (pendingAriverRequestsById.values.map { it.lastActivityElapsedMs } +
            pendingAriverRequests.values.mapNotNull { it.body?.lastActivityElapsedMs }).minOrNull() ?: return
        val delay = (nextActivity + PENDING_RESPONSE_TIMEOUT_MS - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        // 仅有待响应请求时工作；同时及时发现关闭抓包、会话切换及弱引用回收。
        pendingCleanupHandler.postDelayed(pendingCleanup, minOf(delay, 5_000L))
    }

    private fun expirePendingAriverRequests() {
        val now = SystemClock.elapsedRealtime()
        val expiredBodies = synchronized(pendingLock) {
            pendingAriverRequests.values.mapNotNull { it.body }.filter {
                it.afterNoBodyResponse && now - it.lastActivityElapsedMs >= PENDING_RESPONSE_TIMEOUT_MS
            }.distinct()
        }
        expiredBodies.forEach { body ->
            synchronized(body) {
                body.incomplete = true
                recordHttpBody(body, JSONObject(), "body_observation_timeout", true)
            }
        }
        pendingAriverRequestsById.values.toList()
            .filter { now - it.lastActivityElapsedMs >= PENDING_RESPONSE_TIMEOUT_MS }
            .forEach { pending ->
                recordHostError(pending, "capture_response_timeout", "TIMEOUT")
            }
    }

    private fun removePendingAriverRequests(callback: Any) {
        val requests = synchronized(pendingLock) {
            pendingAriverRequests.remove(WeakIdentityKey(callback))?.requests?.toList().orEmpty()
        }
        requests.forEach { takePendingHostRequest(it) }
    }

    private fun drainCollectedPendingAriverRequests() {
        val lost = mutableListOf<PendingHostRequest>()
        val lostBodies = mutableListOf<HttpBodyObservation>()
        synchronized(pendingLock) {
            while (true) {
                val key = pendingAriverRequestQueue.poll() as? WeakIdentityKey ?: break
                val links = pendingAriverRequests.remove(key) ?: continue
                links.body?.takeIf { it.afterNoBodyResponse }?.let(lostBodies::add)
                links.requests.forEach { pending ->
                    val stillLinked = pendingAriverRequests.any { (otherKey, links) ->
                        otherKey.get() != null && pending in links.requests
                    }
                    if (!stillLinked && takePendingHostRequest(pending)) lost.add(pending)
                }
            }
        }
        lostBodies.forEach { body ->
            synchronized(body) {
                body.incomplete = true
                recordHttpBody(body, JSONObject(), "body_context_collected", true)
            }
        }
        lost.forEach { pending ->
            emit(
                RpcTrafficEvent(
                    title = pending.source.title,
                    phase = TrafficPhase.ERROR,
                    source = pending.source,
                    method = pending.method,
                    requestId = pending.requestId,
                    elapsedMs = SystemClock.elapsedRealtime() - pending.startedAtElapsedMs,
                    note = "capture_context_collected",
                    correlationStatus = "CONTEXT_LOST",
                ),
            )
        }
    }

    private fun clearPendingAriverRequests() {
        synchronized(pendingLock) {
            pendingCleanupHandler.removeCallbacks(pendingCleanup)
            pendingAriverRequests.clear()
            pendingAriverRequestsById.clear()
            while (pendingAriverRequestQueue.poll() != null) { /* 清空旧会话引用，不产生新会话事件。 */ }
        }
    }

    private fun isCaptureEnabled(): Boolean {
        val enabled = captureUserId?.let { userId ->
            runCatching { captureEnabledProvider(userId) }.getOrDefault(false)
        } ?: false
        if (!enabled) clearPendingAriverRequests()
        return enabled
    }

    private fun isCaptureEnabledInPersistentConfig(activeUserId: String): Boolean {
        return runCatching {
            val now = System.currentTimeMillis()
            val configDir = Files.CONFIG_DIR
            val userConfigFile = Files.child(Files.child(configDir, activeUserId), "config_v2.json").takeIf { it.isFile }
            observeCaptureConfig(configDir, Files.child(configDir, activeUserId))
            val configFile = userConfigFile ?: Files.getDefaultConfigV2File().takeIf { it.isFile }
            val fingerprint = listOf(
                activeUserId,
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
        }.getOrElse {
            captureEnabledCache = null
            false
        }
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

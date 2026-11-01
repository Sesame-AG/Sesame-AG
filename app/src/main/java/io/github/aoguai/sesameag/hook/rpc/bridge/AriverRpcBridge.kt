package io.github.aoguai.sesameag.hook.rpc.bridge

import io.github.aoguai.sesameag.data.General
import io.github.aoguai.sesameag.entity.RpcEntity
import io.github.aoguai.sesameag.hook.RpcFallbackJsonFactory
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import org.json.JSONObject
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture
import io.github.aoguai.sesameag.hook.rpc.intervallimit.RpcIntervalLimit
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify
import io.github.aoguai.sesameag.util.RandomUtil
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.WorkflowRootGuard
import kotlinx.coroutines.CancellationException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private fun truncateForLog(
    value: String?,
    limit: Int,
): String {
    if (value == null) return ""
    if (value.length <= limit) return value
    return value.substring(0, limit) + "..."
}

private fun getErrorValueAsString(
    obj: Any,
    key: String,
): String =
    runCatching {
        callMethod(obj, "getString", key) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching {
            callMethod(obj, "get", key)?.toString()
        }.getOrNull().orEmpty()

private fun loadClass(
    classLoader: ClassLoader,
    className: String,
): Class<*> = Class.forName(className, false, classLoader)

private fun callMethod(
    target: Any,
    name: String,
    vararg args: Any?,
): Any? = findCompatibleMethod(target.javaClass, name, *args).invoke(target, *args)

private fun callStaticMethod(
    targetClass: Class<*>,
    name: String,
    vararg args: Any?,
): Any? = findCompatibleMethod(targetClass, name, *args).invoke(null, *args)

private fun findCompatibleMethod(
    targetClass: Class<*>,
    name: String,
    vararg args: Any?,
): Method {
    val candidates = linkedSetOf<Method>()
    candidates.addAll(targetClass.methods)
    var current: Class<*>? = targetClass
    while (current != null) {
        candidates.addAll(current.declaredMethods)
        current = current.superclass
    }
    return candidates
        .firstOrNull { method ->
            method.name == name &&
                method.parameterCount == args.size &&
                method.parameterTypes.indices.all { index ->
                    isArgumentCompatible(method.parameterTypes[index], args[index])
                }
        }?.apply {
            isAccessible = true
        } ?: throw NoSuchMethodException("${targetClass.name}#$name(${args.size})")
}

private fun isArgumentCompatible(
    parameterType: Class<*>,
    argument: Any?,
): Boolean {
    if (argument == null) {
        return !parameterType.isPrimitive
    }
    return boxType(parameterType).isAssignableFrom(argument.javaClass)
}

private fun boxType(type: Class<*>): Class<*> =
    when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Void.TYPE -> java.lang.Void::class.java
        else -> type
    }

/**
 * 当前 RPC 桥接实现，最低支持目标应用版本 v10.3.96.8100。
 */
class AriverRpcBridge : RpcBridge {
    private var loader: ClassLoader? = null
    private var rpcBridgeExtensionInstance: Any? = null
    private var parseObjectMethod: Method? = null
    private var bridgeCallbackClazzArray: Array<Class<*>>? = null
    private var rpcCallMethod: Method? = null
    private val maxErrorCount = AtomicInteger(0)
    private val maxErrorThreshold: Int = BaseModel.setMaxErrorCount.value ?: 10

    private val errorMark = arrayListOf("1004", "46", "48")
    private val errorStringMark = arrayListOf("繁忙", "拒绝", "网络不可用", "重试")
    private val retryableErrorMessageKeywords =
        arrayListOf("繁忙", "网络不可用", "重试", "稍後再試", "稍后再试", "再試", "再试", "頻繁", "频繁")

    private val lastNullResponseLogAtMs = ConcurrentHashMap<String, Long>()
    private val nullResponseLogIntervalMs = NULL_RESPONSE_LOG_INTERVAL_MS

    private data class ErrorSummaryWindowStat(
        val windowStartMs: Long,
        val count: Int,
    )

    private val errorSummaryWindowStats = ConcurrentHashMap<String, AtomicReference<ErrorSummaryWindowStat>>()
    private val errorSummaryWindowMs = ERROR_SUMMARY_WINDOW_MS
    private val lastErrorNotifyAtMs = AtomicLong(0)
    private val errorNotifyIntervalMs = ERROR_NOTIFY_INTERVAL_MS

    private val lastReloginAtMs = AtomicLong(0)
    private val reloginIntervalMs = RELOGIN_INTERVAL_MS

    private fun offlineCooldownMs(): Long =
        io.github.aoguai.sesameag.hook.ApplicationHookConstants
            .getOfflineCooldownMs()

    // 公共失败退避：使用显式基础间隔或默认随机抖动，随错误数/尝试次数增长，最多十五秒，避免错误后连续重试。
    private fun computeRetryDelayMs(
        retryInterval: Int,
        attempt: Int,
    ): Long {
        val baseMs =
            if (retryInterval > 0) {
                retryInterval.toLong()
            } else {
                600L + RandomUtil.delay().toLong()
            }

        val errorExp = maxErrorCount.get().coerceAtLeast(0).coerceAtMost(4)
        val attemptExp = (attempt - 1).coerceAtLeast(0).coerceAtMost(2)
        val totalExp = (errorExp + attemptExp).coerceAtMost(5)
        val factor = 1L shl totalExp

        return minOf(baseMs * factor, 15000L)
    }

    private fun isAuthLikeError(
        errorCode: String,
        errorMessage: String,
    ): Boolean = RpcOfflineRisk.isOfflineRisk(errorCode, errorMessage)

    private fun isRetryableRpcError(
        errorCode: String,
        errorMessage: String,
    ): Boolean =
        errorMark.contains(errorCode) ||
            errorStringMark.contains(errorMessage) ||
            retryableErrorMessageKeywords.any { keyword -> errorMessage.contains(keyword, ignoreCase = true) }

    private fun buildOfflineDetail(
        methodName: String?,
        errorCode: String,
        errorMessage: String,
        reason: String,
    ): String =
        buildString {
            if (!methodName.isNullOrBlank()) {
                append("method=")
                append(methodName)
            }
            if (errorCode.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append("code=")
                append(errorCode)
            }
            if (errorMessage.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append("msg=")
                append(errorMessage.take(300))
            }
            if (reason.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append("reason=")
                append(reason)
            }
        }

    private fun handleAuthLikeError(
        rpcEntity: RpcEntity,
        methodName: String?,
        reason: String,
        offlineDetail: String = reason,
        count: Int,
    ): RpcEntity? {
        maxErrorCount.set(0)
        val wasOffline = io.github.aoguai.sesameag.hook.ApplicationHookConstants.offline
        val cooldownMs = offlineCooldownMs()
        // 常驻标题与离线告警统一由 enterOffline 单点驱动，此处不再重复设置/发送
        if (!wasOffline) {
            io.github.aoguai.sesameag.hook.ApplicationHookConstants.enterOffline(
                cooldownMs,
                "auth_like",
                offlineDetail,
            )
        }

        val shouldTryRelogin =
            BaseModel.timeoutRestart.value == true &&
                shouldNotifyNow(lastReloginAtMs, reloginIntervalMs)
        if (!wasOffline && shouldTryRelogin) {
            Log.record(TAG, "尝试重新登录")
            io.github.aoguai.sesameag.hook.ApplicationHookUtils
                .reLoginByBroadcast()
        }

        logNullResponse(rpcEntity, reason, count)
        return null
    }

    override fun load() {
        loader = io.github.aoguai.sesameag.hook.ApplicationHook.classLoader
        val classLoader =
            loader ?: run {
                Log.error(TAG, "ClassLoader为null，无法加载NewRpcBridge")
                return
            }

        try {
            val service =
                callStaticMethod(
                    loadClass(classLoader, "com.alipay.mobile.nebulacore.Nebula"),
                    "getService",
                )
            val extensionManager =
                service?.let { callMethod(it, "getExtensionManager") }
                    ?: throw RuntimeException("getExtensionManager is null")
            val getExtensionByName =
                extensionManager.javaClass.getDeclaredMethod(
                    "createExtensionInstance",
                    Class::class.java,
                )
            getExtensionByName.isAccessible = true
            rpcBridgeExtensionInstance =
                getExtensionByName.invoke(
                    null,
                    classLoader.loadClass("com.alibaba.ariver.commonability.network.rpc.RpcBridgeExtension"),
                )

            if (rpcBridgeExtensionInstance == null) {
                val nodeExtensionMap = callMethod(extensionManager, "getNodeExtensionMap")
                if (nodeExtensionMap != null) {
                    @Suppress("UNCHECKED_CAST")
                    val map = nodeExtensionMap as Map<Any, Map<String, Any>>
                    for ((_, innerMap) in map) {
                        for ((key, value) in innerMap) {
                            if (key == "com.alibaba.ariver.commonability.network.rpc.RpcBridgeExtension") {
                                rpcBridgeExtensionInstance = value
                                break
                            }
                        }
                    }
                }
                if (rpcBridgeExtensionInstance == null) {
                    Log.runtime(TAG, "get rpcBridgeExtensionInstance null")
                    throw RuntimeException("get rpcBridgeExtensionInstance is null")
                }
            }

            parseObjectMethod =
                classLoader
                    .loadClass("com.alibaba.fastjson.JSON")
                    .getMethod("parseObject", String::class.java)
            val bridgeCallbackClazz =
                classLoader.loadClass(
                    "com.alibaba.ariver.engine.api.bridge.extension.BridgeCallback",
                )
            bridgeCallbackClazzArray = arrayOf(bridgeCallbackClazz)

            val rpcInstance = rpcBridgeExtensionInstance ?: throw RuntimeException("rpcBridgeExtensionInstance is null")
            rpcCallMethod =
                rpcInstance.javaClass.getMethod(
                    "rpc",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    classLoader.loadClass(General.JSON_OBJECT_NAME),
                    String::class.java,
                    classLoader.loadClass(General.JSON_OBJECT_NAME),
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    classLoader.loadClass("com.alibaba.ariver.app.api.App"),
                    classLoader.loadClass("com.alibaba.ariver.app.api.Page"),
                    classLoader.loadClass("com.alibaba.ariver.engine.api.bridge.model.ApiContext"),
                    bridgeCallbackClazz,
                )
            Log.runtime(TAG, "get rpcCallMethod successfully")
        } catch (e: Exception) {
            Log.runtime(TAG, "get rpcCallMethod err:")
            throw e
        }
    }

    override fun unload() {
        rpcCallMethod = null
        bridgeCallbackClazzArray = null
        parseObjectMethod = null
        rpcBridgeExtensionInstance = null
        loader = null
    }

    override fun requestString(
        rpcEntity: RpcEntity,
        tryCount: Int,
        retryInterval: Int,
    ): String? = requestObject(rpcEntity, tryCount, retryInterval)?.responseString

    override fun requestObject(
        rpcEntity: RpcEntity,
        tryCount: Int,
        retryInterval: Int,
    ): RpcEntity? {
        val identity = rpcEntity.requestIdentity ?: RpcDailyCircuit.captureIdentity().also {
            rpcEntity.requestIdentity = it
        }
        if (!RpcDailyCircuit.isSameSession(identity)) {
            rpcEntity.settingsRefresh?.failed = true
            return null
        }
        // 方法开始时，将成员变量赋值给局部变量，以避免在方法执行期间因其他线程的unload()调用而导致成员变量变为null
        var localRpcCallMethod = rpcCallMethod
        var localParseObjectMethod = parseObjectMethod
        var localRpcBridgeExtensionInstance = rpcBridgeExtensionInstance
        var localLoader = loader
        var localBridgeCallbackClazzArray = bridgeCallbackClazzArray
        val captureMethodName = rpcEntity.requestMethod
        val captureModuleTraffic = BaseModel.debugMode.value == true && !captureMethodName.isNullOrBlank()
        val captureRequestId = if (captureModuleTraffic) RpcTrafficCapture.newRequestId() else null
        val captureStartedAtMs = if (captureModuleTraffic) System.currentTimeMillis() else 0L
        var captureSucceeded = false
        var captureNote: String? = null
        var captureAttemptId: String? = null
        var captureAttemptResponseRecorded = false
        var hardBlocked = false

        if (captureModuleTraffic) {
            RpcTrafficCapture.recordModuleRequest(captureMethodName, rpcEntity.requestData, captureRequestId!!)
        }

        if (io.github.aoguai.sesameag.hook.ApplicationHookConstants
                .shouldBlockRpc()
        ) {
            captureNote = "blocked_by_offline"
            return null
        }

        val normalizedTryCount = tryCount.coerceAtLeast(1)

        // 如果RPC组件未准备好，尝试重新初始化一次
        if (localRpcCallMethod == null) {
            Log.debug(TAG, "RPC方法为null，尝试重新初始化...")
            try {
                load()
                // 重新加载初始化后的变量
                localRpcCallMethod = rpcCallMethod
                localParseObjectMethod = parseObjectMethod
                localRpcBridgeExtensionInstance = rpcBridgeExtensionInstance
                localLoader = loader
                localBridgeCallbackClazzArray = bridgeCallbackClazzArray
                Log.debug(TAG, "RPC重新初始化成功")
            } catch (e: Exception) {
                Log.error(TAG, "RPC重新初始化失败:")
                Log.printStackTrace(e)
                logNullResponse(rpcEntity, "RPC组件初始化失败", 0)
                captureNote = "rpc_component_init_failed"
                return null
            }
        }

        if (localRpcCallMethod == null || localParseObjectMethod == null ||
            localRpcBridgeExtensionInstance == null || localLoader == null || localBridgeCallbackClazzArray == null
        ) {
            logNullResponse(rpcEntity, "RPC组件不完整", 0)
            captureNote = "rpc_component_incomplete"
            return null
        }

        try {
            var count = 0
            requestLoop@ do {
                count++
                rpcEntity.hasResult = false
                rpcEntity.hasError = false
                rpcEntity.responseObject = null
                rpcEntity.responseString = null
                try {
                    val requestMethod =
                        rpcEntity.requestMethod ?: run {
                            Log.error(TAG, "requestMethod为null")
                            return null
                        }
                    val attemptStartedAtMs = if (captureModuleTraffic) System.currentTimeMillis() else 0L
                    val attemptId = if (captureModuleTraffic) RpcTrafficCapture.newAttemptId() else null
                    captureAttemptId = attemptId
                    if (captureModuleTraffic) {
                        RpcTrafficCapture.recordModuleAttempt(
                            captureMethodName,
                            rpcEntity.requestData,
                            captureRequestId!!,
                            attemptId!!,
                            count,
                        )
                    }
                    val parsedRequest = localParseObjectMethod.invoke(null, rpcEntity.rpcFullRequestData)
                    val sent = RpcIntervalLimit.dispatch(requestMethod) {
                    if (!RpcDailyCircuit.isSameSession(identity)) {
                        rpcEntity.settingsRefresh?.failed = true
                        return@dispatch false
                    }
                    if (rpcEntity.settingsRefresh == null && RpcDailyCircuit.isBlockedToday(identity.userId, requestMethod)) {
                        captureNote = "blocked_by_daily_risk_stop"
                        rpcEntity.setResponseObject(null, RpcFallbackJsonFactory.buildDailyRiskStop(requestMethod))
                        rpcEntity.setError()
                        return@dispatch false
                    }
                    if (io.github.aoguai.sesameag.hook.ApplicationHookConstants.shouldBlockRpc()) {
                        rpcEntity.settingsRefresh?.failed = true
                        captureNote = "blocked_by_offline"
                        return@dispatch false
                    }
                    if (!WorkflowRootGuard.isExecutionAllowed()) {
                        Log.record(TAG, "必需权限或使用协议未就绪，已拒绝 RPC 请求")
                        rpcEntity.settingsRefresh?.failed = true
                        captureNote = "blocked_by_execution_prerequisites"
                        return@dispatch false
                    }
                    // 权限查询跨进程返回后，仍须确认请求没有跨账号或被取消。
                    if (!RpcDailyCircuit.isSameSession(identity) ||
                        io.github.aoguai.sesameag.hook.ApplicationHookConstants.shouldBlockRpc()) {
                        rpcEntity.settingsRefresh?.failed = true
                        return@dispatch false
                    }
                    val finalLocalBridgeCallbackClazzArray = localBridgeCallbackClazzArray
                    rpcEntity.settingsRefresh?.recordMethod(requestMethod)
                    localRpcCallMethod.invoke(
                        localRpcBridgeExtensionInstance,
                        rpcEntity.requestMethod,
                        false,
                        false,
                        "json",
                        parsedRequest,
                        "",
                        null,
                        true,
                        false,
                        0,
                        false,
                        "",
                        null,
                        null,
                        null,
                        Proxy.newProxyInstance(
                            localLoader,
                            localBridgeCallbackClazzArray,
                        ) { proxy, method, args ->
                            when (method.name) {
                                "equals" -> {
                                    proxy === args?.get(0)
                                }

                                "hashCode" -> {
                                    System.identityHashCode(proxy)
                                }

                                "toString" -> {
                                    "Proxy for ${finalLocalBridgeCallbackClazzArray[0].name}"
                                }

                                "sendJSONResponse" -> {
                                    if (args != null && args.isNotEmpty()) {
                                        try {
                                            val obj = args[0]
                                            // 获取JSON字符串，失败时重试一次。
                                            val jsonString =
                                                try {
                                                    callMethod(obj, "toJSONString") as String
                                                } catch (e: Exception) {
                                                    try {
                                                        // 宿主响应序列化失败时只保留一次 100ms 本地重试缓冲，仍失败则交给外层 RPC 失败处理。
                                                        GlobalThreadPools.sleepCompat(100L)
                                                        callMethod(obj, "toJSONString") as String
                                                    } catch (retryException: Exception) {
                                                        // 重试后仍失败，记录日志并标记错误，触发外层RPC重试
                                                        Log.runtime(TAG, "toJSONString 重试后仍然失败，将触发整个 RPC 请求重试: ${retryException.message}")
                                                        rpcEntity.setResponseObject(obj, null)
                                                        rpcEntity.setError()
                                                        return@newProxyInstance null
                                                    }
                                                }

                                            rpcEntity.setResponseObject(obj, jsonString)
                                            if (captureModuleTraffic) {
                                                RpcTrafficCapture.recordModuleResponse(
                                                    captureMethodName,
                                                    jsonString,
                                                    System.currentTimeMillis() - attemptStartedAtMs,
                                                    captureRequestId,
                                                    attemptId,
                                                )
                                                captureAttemptResponseRecorded = true
                                            }
                                            if (!(callMethod(obj, "containsKey", "success") as Boolean) &&
                                                !(callMethod(obj, "containsKey", "isSuccess") as Boolean)
                                            ) {
                                                rpcEntity.setError()
                                                val methodName = rpcEntity.requestMethod ?: "unknown"
                                                val errorCode = getErrorValueAsString(obj, "error")
                                                val errorMessage = getErrorValueAsString(obj, "errorMessage")

                                                val isMarkedNetworkError = isRetryableRpcError(errorCode, errorMessage)
                                                if (isMarkedNetworkError) {
                                                    logErrorSummary(methodName, errorCode, errorMessage)
                                                } else {
                                                    val message =
                                                        buildString {
                                                            append("rpc response1 | id: ")
                                                            append(rpcEntity.hashCode())
                                                            append(" | method: ")
                                                            append(rpcEntity.requestMethod)
                                                            append("\n")
                                                            append("args: ")
                                                            append(truncateForLog(rpcEntity.requestData, 1024))
                                                            append(" |\n")
                                                            append("data: ")
                                                            append(
                                                                truncateForLog(
                                                                    rpcEntity.responseString,
                                                                    4096,
                                                                ),
                                                            )
                                                        }
                                                    Log.error(TAG, message)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            rpcEntity.setError()
                                            Log.error(
                                                TAG,
                                                "rpc response2 | id: ${rpcEntity.hashCode()} | method: ${rpcEntity.requestMethod} err:",
                                            )
                                            Log.printStackTrace(e)
                                        }
                                    }
                                    null
                                }

                                else -> {
                                    null
                                }
                            }
                        },
                    )
                    true
                    }
                    if (!sent) return if (captureNote == "blocked_by_daily_risk_stop") rpcEntity else null

                    if (!rpcEntity.hasResult) {
                        rpcEntity.settingsRefresh?.failed = true
                        logNullResponse(rpcEntity, "无响应结果", count)
                        if (count < normalizedTryCount) {
                            // RPC 未返回结果时采用公共指数退避，避免无响应情况下立即连发。
                            CoroutineUtils.sleepCompat(computeRetryDelayMs(retryInterval, count))
                            continue@requestLoop
                        }
                        return null
                    }

                    if (!RpcDailyCircuit.isCurrent(identity)) {
                        rpcEntity.settingsRefresh?.failed = true
                        return null
                    }
                    val rawResponse = JSONObject(rpcEntity.responseString.orEmpty())
                    rpcEntity.settingsRefresh?.observeResponse(rawResponse)
                    if (RpcOfflineRisk.isHardBlocked(rawResponse)) {
                        hardBlocked = true
                        RpcDailyCircuit.markBlockedToday(identity, requestMethod)
                        rpcEntity.setError()
                        val code = RpcOfflineRisk.extractCode(rawResponse)
                        val message = RpcOfflineRisk.extractMessage(rawResponse)
                        handleAuthLikeError(
                            rpcEntity, requestMethod, "硬阻塞: $code/$message",
                            buildOfflineDetail(requestMethod, code, message, "访问受限"), count,
                        )
                        // 保留本次真实响应，不依赖随后 I07 包装中的全局历史来源。
                        return rpcEntity
                    }

                    val responseCode = rawResponse.optString("resultCode")
                    val businessResult = when (rpcEntity.requestMethod) {
                        "alipay.antocean.ocean.h5.giveFriendPiece" -> when (responseCode) {
                            "PIECE_HAVE_GAVE" -> "TERMINAL_DONE"
                            "RECEIVE_PIECE_LIMIT" -> "BUSINESS_LIMIT"
                            else -> null
                        }
                        "alipay.antocean.ocean.h5.cleanFriendOcean" ->
                            if (responseCode == "HELP_CLEAN_ALL_FRIEND_LIMIT") "BUSINESS_LIMIT" else null
                        else -> null
                    }
                    if (businessResult != null && rawResponse.opt("success") == false) {
                        maxErrorCount.set(0)
                        rpcEntity.setError()
                        Log.error(
                            TAG,
                            "RPC业务状态 classification=$businessResult method=${rpcEntity.requestMethod} " +
                                "code=$responseCode raw=$rawResponse",
                        )
                        captureSucceeded = true
                        return rpcEntity
                    }
                    val explicitRetryable = rawResponse.opt("retryable") as? Boolean
                        ?: rawResponse.opt("retriable") as? Boolean
                    val explicitFailure = rawResponse.opt("success") == false || rawResponse.opt("isSuccess") == false
                    if (!rpcEntity.hasError && !(explicitRetryable != null && explicitFailure)) {
                        maxErrorCount.set(0)
                        captureSucceeded = true
                        return rpcEntity
                    }

                    try {
                        val responseObj = rpcEntity.responseObject
                        val errorCode =
                            if (responseObj != null) getErrorValueAsString(responseObj, "error") else ""
                        val errorMessage =
                            if (responseObj != null) getErrorValueAsString(responseObj, "errorMessage") else ""
                        val response = rpcEntity.responseString
                        val methodName = rpcEntity.requestMethod

                        if (isAuthLikeError(errorCode, errorMessage)) {
                            return handleAuthLikeError(
                                rpcEntity = rpcEntity,
                                methodName = methodName,
                                reason = "访问受限: $errorCode/$errorMessage",
                                offlineDetail = buildOfflineDetail(methodName, errorCode, errorMessage, "访问受限"),
                                count = count,
                            )
                        }

                        if (errorCode == "2000" && errorMessage.contains("登录超时")) {
                            return handleAuthLikeError(
                                rpcEntity = rpcEntity,
                                methodName = methodName,
                                reason = "登录超时: $errorCode/$errorMessage",
                                count = count,
                            )
                        }

                        if (explicitRetryable != null) {
                            rpcEntity.setError()
                            Log.error(TAG, "RPC业务失败 method=$methodName retryable=$explicitRetryable raw=$response")
                            if (explicitRetryable && count < normalizedTryCount) {
                                // 仅在响应明确允许重试且次数未耗尽时退避，给可恢复的业务失败留出恢复窗口。
                                CoroutineUtils.sleepCompat(computeRetryDelayMs(retryInterval, count))
                                continue@requestLoop
                            }
                            return rpcEntity
                        }
                        if (isRetryableRpcError(errorCode, errorMessage)) {
                            val currentErrorCount = maxErrorCount.incrementAndGet()
                            if (!io.github.aoguai.sesameag.hook.ApplicationHookConstants.offline) {
                                var enteredOffline = false
                                if (currentErrorCount > maxErrorThreshold) {
                                    // 常驻标题与告警统一由 enterOffline 单点驱动
                                    io.github.aoguai.sesameag.hook.ApplicationHookConstants.enterOffline(
                                        offlineCooldownMs(),
                                        "network_error_threshold",
                                        "current=$currentErrorCount threshold=$maxErrorThreshold",
                                    )
                                    enteredOffline = true
                                }

                                if (!enteredOffline &&
                                    BaseModel.errNotify.value == true &&
                                    shouldNotifyNow(lastErrorNotifyAtMs, errorNotifyIntervalMs)
                                ) {
                                    Notify.sendAlert(
                                        "${TimeUtil.getTimeStr()} | 网络异常: $methodName",
                                        response,
                                    )
                                }

                                val shouldTryRelogin =
                                    BaseModel.timeoutRestart.value == true &&
                                        shouldNotifyNow(lastReloginAtMs, reloginIntervalMs) &&
                                        errorCode != "1004" &&
                                        !enteredOffline
                                if (shouldTryRelogin) {
                                    Log.record(TAG, "尝试重新登录")
                                    io.github.aoguai.sesameag.hook.ApplicationHookUtils
                                        .reLoginByBroadcast()
                                }
                            }

                            logNullResponse(rpcEntity, "网络错误: $errorCode/$errorMessage", count)
                            if (count < normalizedTryCount) {
                                // 网络类错误采用公共退避，避免故障期间连续重发请求。
                                CoroutineUtils.sleepCompat(computeRetryDelayMs(retryInterval, count))
                                continue@requestLoop
                            }
                            return null
                        }
                        captureSucceeded = true
                        return rpcEntity
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    } catch (e: Exception) {
                        Log.error(
                            TAG,
                            "rpc response | id: ${rpcEntity.hashCode()} | method: ${rpcEntity.requestMethod} get err:",
                        )
                        Log.printStackTrace(e)
                        captureNote = "response_parse_exception:${e.javaClass.simpleName}"
                    }

                    if (count < normalizedTryCount) {
                        // 响应解析异常后按公共重试策略等待，再重新请求，而非对成功响应加延迟。
                        CoroutineUtils.sleepCompat(computeRetryDelayMs(retryInterval, count))
                    }
                } catch (t: Throwable) {
                    rpcEntity.settingsRefresh?.failed = true
                    if (hardBlocked) return rpcEntity
                    if (t is CancellationException) throw t
                    if (t is InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw t
                    }
                    Log.error(
                        TAG,
                        "rpc request | id: ${rpcEntity.hashCode()} | method: ${rpcEntity.requestMethod} err:",
                    )
                    Log.printStackTrace(t)
                    captureNote = "request_exception:${t.javaClass.simpleName}"

                    if (count < normalizedTryCount) {
                        // 请求抛出异常后保留公共退避；取消和线程中断已在上方直接退出。
                        CoroutineUtils.sleepCompat(computeRetryDelayMs(retryInterval, count))
                    }
                }
            } while (count < normalizedTryCount)

            logNullResponse(rpcEntity, "重试次数耗尽", normalizedTryCount)
            captureNote = captureNote ?: "retries_exhausted"
            return null
        } finally {
            if (captureModuleTraffic) {
                val elapsedMs =
                    if (captureStartedAtMs > 0L) {
                        System.currentTimeMillis() - captureStartedAtMs
                    } else {
                        -1L
                    }
                if (captureAttemptResponseRecorded) {
                    // attempt 层已完整记录该请求响应，避免 finally 重复记录或误报为 null
                } else if (captureSucceeded && !rpcEntity.hasError) {
                    RpcTrafficCapture.recordModuleResponse(
                        captureMethodName,
                        rpcEntity.responseString,
                        elapsedMs,
                        captureRequestId,
                        captureAttemptId,
                    )
                } else {
                    RpcTrafficCapture.recordModuleError(
                        captureMethodName,
                        rpcEntity.responseString,
                        elapsedMs,
                        captureNote ?: if (rpcEntity.hasError) "rpc_entity_error" else "request_returned_null",
                        captureRequestId,
                        captureAttemptId,
                    )
                }
            }
        }
    }

    private fun logNullResponse(
        rpcEntity: RpcEntity?,
        reason: String,
        count: Int,
    ) {
        val methodName = rpcEntity?.requestMethod ?: "unknown"
        val now = System.currentTimeMillis()
        val last = lastNullResponseLogAtMs[methodName]
        if (last == null || now - last >= nullResponseLogIntervalMs) {
            lastNullResponseLogAtMs[methodName] = now
            Log.error(TAG, "RPC返回null | 方法: $methodName | 原因: $reason | 重试: $count")
        }
    }

    private fun logErrorSummary(
        methodName: String,
        errorCode: String,
        errorMessage: String,
    ) {
        val key = "$methodName|$errorCode"
        val now = System.currentTimeMillis()
        val stat = errorSummaryWindowStats.computeIfAbsent(key) {
            AtomicReference(ErrorSummaryWindowStat(windowStartMs = now, count = 0))
        }.getAndUpdate { previous ->
            if (now - previous.windowStartMs >= errorSummaryWindowMs) {
                ErrorSummaryWindowStat(now, 1)
            } else previous.copy(count = previous.count + 1)
        }
        val expired = now - stat.windowStartMs >= errorSummaryWindowMs
        val shouldLogDetail = expired || stat.count == 0
        val summaryLog = if (expired) {
            "RPC错误汇总 | 方法: $methodName | 错误: $errorCode/$errorMessage | 次数: ${stat.count} | 窗口: ${errorSummaryWindowMs}ms"
        } else null
        summaryLog?.let { Log.error(TAG, it) }
        if (shouldLogDetail) {
            Log.error(TAG, "RPC错误 | 方法: $methodName | 错误: $errorCode/$errorMessage")
        }
    }

    private fun shouldNotifyNow(
        lastMs: AtomicLong,
        intervalMs: Long,
    ): Boolean {
        val now = System.currentTimeMillis()
        val last = lastMs.get()
        if (now - last < intervalMs) return false
        return lastMs.compareAndSet(last, now)
    }

    companion object {
        private val TAG = AriverRpcBridge::class.java.simpleName

        private const val NULL_RESPONSE_LOG_INTERVAL_MS: Long = 5_000L
        private const val ERROR_SUMMARY_WINDOW_MS: Long = 10 * 60_000L
        private const val ERROR_NOTIFY_INTERVAL_MS: Long = 60_000L
        private const val RELOGIN_INTERVAL_MS: Long = 120_000L
    }
}

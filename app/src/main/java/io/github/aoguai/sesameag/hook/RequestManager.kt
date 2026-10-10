package io.github.aoguai.sesameag.hook

import android.Manifest
import androidx.annotation.RequiresPermission
import io.github.aoguai.sesameag.entity.RpcEntity
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import io.github.aoguai.sesameag.hook.rpc.bridge.RpcBridge
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.NetworkUtils
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import io.github.aoguai.sesameag.util.WorkflowRootGuard
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * RPC 请求管理器 (带熔断与兜底机制)
 */
object RequestManager {

    private const val TAG = "RequestManager"
    private val settingsRefreshContext = ThreadLocal<ExchangeSettingsRefreshContext>()

    internal fun <T> withExchangeSettingsRefresh(block: () -> T): T {
        val previous = settingsRefreshContext.get()
        if (previous != null) return block()
        val context = ExchangeSettingsRefreshContext(RpcDailyCircuit.captureIdentity())
        settingsRefreshContext.set(context)
        return try {
            val result = block()
            context.complete()
            result
        } finally {
            settingsRefreshContext.remove()
        }
    }

    internal fun failExchangeSettingsRefresh() {
        val context = settingsRefreshContext.get() ?: return
        context.failed = true
        throw IllegalStateException("兑换列表未完整刷新，保留 RPC 每日停止标识")
    }

    // reOpenApp() 会通过 SmartScheduler 延迟 20s 拉起 Activity。
    // 若恢复冷却时间小于 20s，会导致“每 15s 触发一次恢复 -> 取消并重新调度 20s 任务”，
    // 从而永远无法真正执行 reOpenApp（日志表现为反复 RPC 拦截 + 20s 延迟，直到用户手动重启 App）。
    private const val OFFLINE_RECOVERY_COOLDOWN_MS = 25_000L
    private const val RPC_BRIDGE_NULL_LOG_INTERVAL_MS: Long = 5_000L
    private const val RPC_BLOCKED_LOG_INTERVAL_MS: Long = 5_000L
    private const val OFFLINE_RECOVERY_COOLDOWN_LOG_INTERVAL_MS: Long = 5_000L

    // 连续失败计数器
    private val errorCount = AtomicInteger(0)

    private val rpcBridgeNullLogLimiter = RpcLogLimiter(RPC_BRIDGE_NULL_LOG_INTERVAL_MS)
    private val rpcBlockedLogLimiter = RpcLogLimiter(RPC_BLOCKED_LOG_INTERVAL_MS)
    private val offlineRecoveryCooldownLogLimiter = RpcLogLimiter(OFFLINE_RECOVERY_COOLDOWN_LOG_INTERVAL_MS)

    private val rpcRequestCount = AtomicLong(0)
    private val rpcBlockedCount = AtomicLong(0)
    private val rpcBridgeNullCount = AtomicLong(0)

    @Volatile
    private var lastOfflineRecoveryTime = 0L

    /**
     * 核心执行函数 (内联优化)
     * 流程：离线检查 -> 获取 Bridge -> 执行请求 -> 结果校验 -> 错误计数/重置
     */
    private fun tryBlockByOffline(methodLog: String?): RpcRequestOutcome.Failure? {
        if (!ApplicationHookConstants.shouldBlockRpc()) return null

        rpcBlockedCount.incrementAndGet()

        if (rpcBlockedLogLimiter.shouldLog()) {
            val untilMs = ApplicationHookConstants.offlineUntilMs
            val remainMs = if (untilMs > 0L) {
                (untilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            } else {
                -1L
            }
            val reason = ApplicationHookConstants.offlineReason
            val detail = ApplicationHookConstants.offlineReasonDetail

            Log.record(
                TAG,
                "RPC 被离线拦截: $methodLog | remainMs=$remainMs untilMs=$untilMs reason=${reason ?: "null"} detail=${detail ?: "null"}"
            )
            ModuleStatusReporter.requestUpdate(reason = "rpc_blocked")
        }

        handleOfflineRecovery()
        return RpcRequestOutcome.Failure("离线模式")
    }

    private fun normalizeTryCount(value: Int): Int = value.coerceAtLeast(1)

    private inline fun executeRpcOnce(
        methodLog: String?,
        identity: AccountSessionIdentity,
        block: (RpcBridge) -> String?
    ): RpcRequestOutcome {
        val blocked = tryBlockByOffline(methodLog)
        if (blocked != null) return blocked
        if (!WorkflowRootGuard.isExecutionAllowed()) {
            return RpcRequestOutcome.Stopped(RpcFallbackJsonFactory.build("本地执行条件未就绪", methodLog))
        }

        // 2. 获取 Bridge (包含网络检查)
        // 如果这里获取失败，也视为一次错误
        val bridge = getRpcBridge()
        if (!RpcDailyCircuit.isCurrent(identity)) {
            return RpcRequestOutcome.Stopped(RpcFallbackJsonFactory.build("账号会话已变化", methodLog))
        }
        if (bridge == null) {
            rpcBridgeNullCount.incrementAndGet()
            if (rpcBridgeNullLogLimiter.shouldLog()) {
                Log.record(TAG, "RpcBridge 不可用: $methodLog")
                ModuleStatusReporter.requestUpdate(reason = "rpc_bridge_null")
            }
            handleFailure(methodLog ?: "Network/Bridge Unavailable", "网络或Bridge不可用")
            return RpcRequestOutcome.Failure("网络或Bridge不可用")
        }

        // 3. 执行请求
        val result = try {
            block(bridge)
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "RPC 执行异常: $methodLog", e)
            null // 异常视为 null，触发失败逻辑
        }

        // 4. 结果校验与状态维护
        if (!RpcDailyCircuit.isCurrent(identity)) {
            return RpcRequestOutcome.Stopped(RpcFallbackJsonFactory.build("账号会话已变化", methodLog))
        }
        if (result.isNullOrBlank()) {
            // 本地门禁拦截没有发起 RPC，不能累计为远端失败并触发永久离线。
            if (!WorkflowRootGuard.isExecutionAllowed()) {
                return RpcRequestOutcome.Stopped(RpcFallbackJsonFactory.build("本地执行条件未就绪", methodLog))
            }
            // 在途请求可能因其他 RPC 触发离线而返回空值，不应再次熔断并覆盖风控原因。
            if (ApplicationHookConstants.isOffline()) {
                return RpcRequestOutcome.Stopped(RpcFallbackJsonFactory.build("离线模式", methodLog))
            }
            // 失败：增加计数，检查兜底
            handleFailure(methodLog ?: "Unknown", "返回数据为空")
            return RpcRequestOutcome.Failure("返回数据为空")
        } else {
            val response = runCatching { JSONObject(result) }.getOrNull()
            if (response != null && (RpcDailyCircuit.isStopResponse(response) || RpcOfflineRisk.isHardBlocked(response))) {
                return RpcRequestOutcome.Stopped(result)
            }
            // 成功：重置计数器
            if (errorCount.get() > 0) {
                errorCount.set(0)
                Log.record(TAG, "RPC 恢复正常，错误计数重置")
            }
            return RpcRequestOutcome.Success(result)
        }
    }

    /**
     * 处理失败逻辑：计数、报警、熔断
     */
    private fun handleFailure(method: String, reason: String) {
        val currentCount = errorCount.incrementAndGet()
        // 假设 BaseModel 有个方法获取这个配置，或者直接用常量
        val maxCount = BaseModel.setMaxErrorCount.value ?: 8

        Log.error(TAG, "RPC 失败 ($currentCount/$maxCount) | Method: $method | Reason: $reason")

        // 触发兜底阈值
        if (currentCount >= maxCount) {
            Log.record(TAG, "🔴 连续失败次数达到阈值，触发熔断兜底机制！")
            // 1. 设置离线状态，停止后续任务
            ApplicationHookConstants.setOffline(
                true,
                "rpc_error_threshold",
                "method=$method current=$currentCount threshold=$maxCount reason=$reason"
            )
            // 立即尝试一次恢复（告警由 enterOffline 单点发送）
            handleOfflineRecovery()
        }
    }

    /**
     * 处理离线恢复逻辑
     * 可以是发送广播、拉起 App 等
     */
    private fun handleOfflineRecovery() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastOfflineRecoveryTime
        if (elapsed in 0 until OFFLINE_RECOVERY_COOLDOWN_MS) {
            if (offlineRecoveryCooldownLogLimiter.shouldLog()) {
                Log.record(TAG, "离线恢复冷却中，跳过恢复（${elapsed}ms < ${OFFLINE_RECOVERY_COOLDOWN_MS}ms）")
            }
            return
        }
        lastOfflineRecoveryTime = now

        Log.record(TAG, "正在尝试执行离线恢复策略...")
        // 策略 A: 重新拉起 App (推荐)
        ApplicationHook.reOpenApp()
    }

    /**
     * 获取 RpcBridge 实例
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun getRpcBridge(): RpcBridge? {
        if (!NetworkUtils.isNetworkAvailable()) {
            Log.record(TAG, "网络不可用，尝试等待 5秒...")
            CoroutineUtils.sleepCompat(5000)
            if (!NetworkUtils.isNetworkAvailable()) {
                return null
            }
        }

        var bridge = ApplicationHook.rpcBridge
        if (bridge == null) {
            Log.record(TAG, "RpcBridge 未初始化，尝试等待 5秒...")
            CoroutineUtils.sleepCompat(5000)
            bridge = ApplicationHook.rpcBridge
        }

        return bridge
    }

    @JvmStatic
    fun getMetricsSnapshot(): Map<String, Any?> {
        return linkedMapOf(
            "requestCount" to rpcRequestCount.get(),
            "blockedCount" to rpcBlockedCount.get(),
            "bridgeNullCount" to rpcBridgeNullCount.get(),
            "errorCount" to errorCount.get()
        )
    }

    private fun requestStringWithPolicy(
        rpcEntity: RpcEntity,
        tryCount: Int,
        retryInterval: Int,
    ): String {
        rpcRequestCount.incrementAndGet()
        val method = rpcEntity.requestMethod
        val identity = RpcDailyCircuit.captureIdentity()
        val refresh = settingsRefreshContext.get()
        check(refresh?.failed != true) { "兑换列表刷新已失败，停止后续请求" }
        rpcEntity.requestIdentity = identity
        rpcEntity.settingsRefresh = refresh

        if (!RpcDailyCircuit.isCurrent(identity) || (refresh != null && !RpcDailyCircuit.isCurrent(refresh.identity))) {
            refresh?.failed = true
            return RpcFallbackJsonFactory.build("账号会话已变化或未就绪", method)
        }
        if (refresh == null && RpcDailyCircuit.isBlockedToday(identity.userId, method)) {
            rpcBlockedCount.incrementAndGet()
            return RpcFallbackJsonFactory.buildDailyRiskStop(method)
        }
        val blocked = tryBlockByOffline(method)
        if (blocked != null) {
            refresh?.failed = true
            return RpcFallbackJsonFactory.build(blocked.reason, method)
        }

        return when (val result = executeRpcOnce(method, identity) { bridge ->
            bridge.requestString(rpcEntity, normalizeTryCount(tryCount), retryInterval)
        }) {
            is RpcRequestOutcome.Success -> result.body
            is RpcRequestOutcome.Stopped -> {
                refresh?.failed = true
                result.body
            }
            is RpcRequestOutcome.Failure -> {
                refresh?.failed = true
                RpcFallbackJsonFactory.build(result.reason, method)
            }
        }
    }

    @JvmStatic
    fun requestString(rpcEntity: RpcEntity): String =
        requestStringWithPolicy(rpcEntity, RpcBridge.DEFAULT_TRY_COUNT, RpcBridge.DEFAULT_RETRY_INTERVAL)

    @JvmStatic
    fun requestString(rpcEntity: RpcEntity, tryCount: Int, retryInterval: Int): String =
        requestStringWithPolicy(rpcEntity, tryCount, retryInterval)

    @JvmStatic
    fun requestString(method: String?, data: String?): String =
        requestString(RpcEntity(method, data))

    @JvmStatic
    fun requestString(
        method: String?,
        data: String?,
        appName: String?,
        methodName: String?,
        facadeName: String?
    ): String = requestString(RpcEntity(method, data, appName, methodName, facadeName))

    @JvmStatic
    fun requestString(method: String?, data: String?, tryCount: Int, retryInterval: Int): String =
        requestStringWithPolicy(RpcEntity(method, data), tryCount, retryInterval)
}

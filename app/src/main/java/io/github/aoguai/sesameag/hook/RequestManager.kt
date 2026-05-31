package io.github.aoguai.sesameag.hook

import android.Manifest
import androidx.annotation.RequiresPermission
import io.github.aoguai.sesameag.entity.RpcEntity
import io.github.aoguai.sesameag.hook.rpc.bridge.RpcBridge
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.NetworkUtils
import io.github.aoguai.sesameag.util.Notify
import io.github.aoguai.sesameag.util.TimeUtil
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * RPC 请求管理器 (带熔断与兜底机制)
 */
object RequestManager {

    private const val TAG = "RequestManager"
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

    private inline fun executeRpcOnce(methodLog: String?, block: (RpcBridge) -> String?): RpcRequestOutcome {
        val blocked = tryBlockByOffline(methodLog)
        if (blocked != null) return blocked

        // 2. 获取 Bridge (包含网络检查)
        // 如果这里获取失败，也视为一次错误
        val bridge = getRpcBridge()
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
        if (result.isNullOrBlank()) {
            // 失败：增加计数，检查兜底
            handleFailure(methodLog ?: "Unknown", "返回数据为空")
            return RpcRequestOutcome.Failure("返回数据为空")
        } else {
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
            // 2. 发送通知 (根据用户配置)
            if (BaseModel.errNotify.value == true) {
                val msg = "${TimeUtil.getTimeStr()} | 网络异常次数超过阈值[$maxCount]"
                Notify.sendAlert(msg, "RPC 连续失败，脚本已暂停")
            }
            // 3. 立即尝试一次恢复
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
        // 策略 B: 发送重登录广播 (如果宿主还能响应广播)
        // ApplicationHook.reLoginByBroadcast()
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

    private inline fun requestStringWithPolicy(
        method: String?,
        tryCount: Int,
        retryInterval: Int,
        crossinline block: (RpcBridge, Int, Int) -> String?
    ): String {
        rpcRequestCount.incrementAndGet()

        // 离线优先：避免“脚本暂停”时仍继续执行业务
        val blocked = tryBlockByOffline(method)
        if (blocked != null) {
            return RpcFallbackJsonFactory.build(blocked.reason, method)
        }

        val normalizedTryCount = normalizeTryCount(tryCount)

        val result = executeRpcOnce(method) { bridge ->
            block(bridge, normalizedTryCount, retryInterval)
        }
        return when (result) {
            is RpcRequestOutcome.Success -> result.body
            is RpcRequestOutcome.Failure -> RpcFallbackJsonFactory.build(result.reason, method)
        }
    }

    // ================== 公开 API (保持不变) ==================

    @JvmStatic
    fun requestString(rpcEntity: RpcEntity): String {
        val method = rpcEntity.requestMethod
        return requestStringWithPolicy(method, RpcBridge.DEFAULT_TRY_COUNT, RpcBridge.DEFAULT_RETRY_INTERVAL) { bridge, tc, ri ->
            bridge.requestString(rpcEntity, tc, ri)
        }
    }

    @JvmStatic
    fun requestString(rpcEntity: RpcEntity, tryCount: Int, retryInterval: Int): String {
        val method = rpcEntity.requestMethod
        return requestStringWithPolicy(method, tryCount, retryInterval) { bridge, tc, ri ->
            bridge.requestString(rpcEntity, tc, ri)
        }
    }

    @JvmStatic
    fun requestString(method: String?, data: String?): String {
        return requestStringWithPolicy(method, RpcBridge.DEFAULT_TRY_COUNT, RpcBridge.DEFAULT_RETRY_INTERVAL) { bridge, tc, ri ->
            bridge.requestString(method, data, tc, ri)
        }
    }

    @JvmStatic
    fun requestString(method: String?, data: String?, relation: String?): String {
        return requestStringWithPolicy(method, RpcBridge.DEFAULT_TRY_COUNT, RpcBridge.DEFAULT_RETRY_INTERVAL) { bridge, tc, ri ->
            bridge.requestString(method, data, relation, tc, ri)
        }
    }

    @JvmStatic
    fun requestString(
        method: String?,
        data: String?,
        appName: String?,
        methodName: String?,
        facadeName: String?
    ): String {
        return requestStringWithPolicy(method, RpcBridge.DEFAULT_TRY_COUNT, RpcBridge.DEFAULT_RETRY_INTERVAL) { bridge, _, _ ->
            bridge.requestString(method, data, appName, methodName, facadeName)
        }
    }

    @JvmStatic
    fun requestString(method: String?, data: String?, tryCount: Int, retryInterval: Int): String {
        return requestStringWithPolicy(method, tryCount, retryInterval) { bridge, tc, ri ->
            bridge.requestString(method, data, tc, ri)
        }
    }

    @JvmStatic
    fun requestString(
        method: String?,
        data: String?,
        relation: String?,
        tryCount: Int,
        retryInterval: Int
    ): String {
        return requestStringWithPolicy(method, tryCount, retryInterval) { bridge, tc, ri ->
            bridge.requestString(method, data, relation, tc, ri)
        }
    }

    @JvmStatic
    fun requestObject(rpcEntity: RpcEntity?, tryCount: Int, retryInterval: Int) {
        if (rpcEntity == null) return
        // requestObject 不涉及返回值判断，但同样需要离线检查
        if (tryBlockByOffline(rpcEntity.requestMethod ?: rpcEntity.methodName) != null) {
            return
        }

        val bridge = getRpcBridge()
        if (bridge == null) {
            handleFailure("requestObject", "Bridge Unavailable")
            return
        }

        try {
            bridge.requestObject(rpcEntity, normalizeTryCount(tryCount), retryInterval)
            // requestObject 没有返回值，假设只要不抛异常就算成功？
            // 或者保守一点，不重置 errorCount，也不增加 errorCount
            errorCount.set(0)
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "requestObject 异常: ${rpcEntity.methodName}", e)
            handleFailure(rpcEntity.methodName ?: "Unknown", "Exception")
        }
    }
}


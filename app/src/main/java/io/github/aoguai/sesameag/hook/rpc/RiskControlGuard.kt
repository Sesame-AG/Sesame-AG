package io.github.aoguai.sesameag.hook.rpc

import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.ApplicationHookConstants.TriggerInfo
import io.github.aoguai.sesameag.hook.ApplicationHookConstants.TriggerPriority
import io.github.aoguai.sesameag.hook.ApplicationHookConstants.TriggerType
import io.github.aoguai.sesameag.hook.ApplicationHookCore
import io.github.aoguai.sesameag.hook.ModuleStatusReporter
import io.github.aoguai.sesameag.hook.keepalive.PersistentReconcileMode
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.task.antForest.AntForestRpcCall
import io.github.aoguai.sesameag.task.antSesameCredit.AntSesameCreditRpcCall
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** 风控分级：滑块/验证码属于可恢复的软验证，1009/访问受限属于硬拦截。 */
enum class RiskTier {
    SOFT_VERIFY,
    HARD_BLOCK,
}

/**
 * 风控守卫：命中风控后不再"停到第二天"，而是暂停 -> 只读探活 -> 自动续跑。
 *
 * 设计约束：
 * 1. 暂停仍然复用 [ApplicationHookConstants.enterOffline]，因为 RPC 拦截、任务停止、状态上报都已挂在该状态上；
 * 2. 探活必须绕过离线拦截（[ApplicationHookConstants.withProbeBypass]），否则永远探不出去；
 * 3. 探活只打只读接口，且失败不计入 RPC 连续失败阈值，避免把暂停升级成硬阻塞；
 * 4. 每次探活前都重新确认账号会话，防止切号后把旧账号的恢复动作套到新账号上。
 */
object RiskControlGuard {
    private const val TAG = "RiskControlGuard"

    /** 与 logcat 一致的标签：`adb logcat -s SesameLog` 可直接看到风控事件。 */
    private const val LOGCAT_TAG = "SesameLog"

    /** 软验证（滑块/验证码）最少暂停时长，短于用户完成验证通常也来不及。 */
    private const val SOFT_MIN_COOLDOWN_MS = 3 * 60_000L

    /** 硬拦截（1009/访问受限）最少暂停时长，这类拦截恢复通常更慢。 */
    private const val HARD_MIN_COOLDOWN_MS = 30 * 60_000L

    /**
     * 暂停时长上限。冷却值原来直接取 checkInterval（默认 50 分钟），
     * 状态栏会显示"离线 50 分钟"，而实际探活 1 分钟就可能解除，两个数字对不上。
     */
    private const val SOFT_MAX_COOLDOWN_MS = 10 * 60_000L
    private const val HARD_MAX_COOLDOWN_MS = 90 * 60_000L

    /** 同一暂停窗口内的重复风控信号只续期，不重建探活计划，避免信号风暴把退避重置回 60s。 */
    private const val REPEAT_SIGNAL_WINDOW_MS = 5 * 60_000L

    /**
     * 方法级风控隔离：触发风控的那个 RPC 方法在隔离期内直接跳过。
     * 支付宝的风控是"按场景/接口"生效的，别的方法能通不代表被打的方法能通；
     * 不做隔离就会出现"探活通过 → 立刻重试同方法 → 又 1009"的来回撞门。
     */
    private const val METHOD_QUARANTINE_MS = 20 * 60_000L

    /** 短时间内反复命中风控时升级为硬拦截，避免每 40~60 秒撞一次。 */
    private const val ESCALATION_WINDOW_MS = 10 * 60_000L
    private const val ESCALATION_HIT_COUNT = 3

    /** 硬拦截的探活更稀疏，避免在明确被封时反复撞门。 */
    private const val HARD_DELAY_MULTIPLIER = 3L

    private val probeDelaysMs = longArrayOf(60_000L, 180_000L, 600_000L, 1_800_000L)

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("RiskControlGuard"))
    private val probeJob = AtomicReference<Job?>(null)

    @Volatile
    private var lastSignalAtMs: Long = 0L

    @Volatile
    private var pausedUserId: String? = null

    @Volatile
    private var pausedSessionEpoch: Long = 0L

    /** 方法名 -> 隔离截止时间（毫秒时间戳）。 */
    private val quarantinedMethods: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /** 最近的风控命中时间，用于判断是否需要在短时间内升级为硬拦截。 */
    private val recentHits = java.util.Collections.synchronizedList(mutableListOf<Long>())

    private val probeFetchers: List<() -> String> by lazy {
        listOf(
            { AntForestRpcCall.queryHomePage() },
            { AntSesameCreditRpcCall.queryHome() },
        )
    }

    /** 软验证：滑块 / 验证码 / 需要验证。 */
    fun onVerificationRisk(
        source: String,
        code: String,
        message: String,
        method: String? = null,
    ) {
        val detail = buildDetail(source, code, message)
        val escalated = escalateIfRepeated()
        if (escalated) {
            trace("短时间内重复命中风控，升级为硬拦截: $detail")
            onHardBlockRisk(source, code, message, method)
            return
        }
        method?.let { quarantineMethod(it, "soft_risk") }
        val cooldownMs = maxOf(ApplicationHookConstants.getOfflineCooldownMs(), SOFT_MIN_COOLDOWN_MS)
            .coerceAtMost(SOFT_MAX_COOLDOWN_MS)
        if (!ApplicationHookConstants.isOffline()) {
            ApplicationHookConstants.enterOffline(cooldownMs, REASON_VERIFY, detail)
            trace("风控验证暂停 tier=SOFT cooldownMs=$cooldownMs $detail")
        } else {
            // 已在暂停中：重复的同类响应只续期探活，不再重复停任务/发通知。
            trace("风控验证信号重复 tier=SOFT $detail")
        }
        startProbeCycle(RiskTier.SOFT_VERIFY, detail)
        ModuleStatusReporter.requestUpdate("risk_verify")
    }

    /** 硬拦截：访问受限 / 1009 之类的明确风控码。 */
    fun onHardBlockRisk(
        source: String,
        code: String,
        message: String,
        method: String? = null,
    ) {
        val detail = buildDetail(source, code, message)
        method?.let { quarantineMethod(it, "hard_risk") }
        val cooldownMs = maxOf(ApplicationHookConstants.getOfflineCooldownMs(), HARD_MIN_COOLDOWN_MS)
            .coerceAtMost(HARD_MAX_COOLDOWN_MS)
        if (!ApplicationHookConstants.isOffline()) {
            ApplicationHookConstants.enterOffline(cooldownMs, REASON_HARD_BLOCK, detail)
            trace("风控硬拦截暂停 tier=HARD cooldownMs=$cooldownMs $detail")
        } else {
            trace("风控硬拦截信号重复 tier=HARD $detail")
        }
        startProbeCycle(RiskTier.HARD_BLOCK, detail)
        ModuleStatusReporter.requestUpdate("risk_hard_block")
    }

    /** 触发风控的方法在隔离期内不再发送。 */
    fun quarantineMethod(
        method: String,
        reason: String,
    ) {
        if (method.isBlank()) return
        val until = System.currentTimeMillis() + METHOD_QUARANTINE_MS
        quarantinedMethods[method] = until
        trace("风控方法隔离 method=$method task=${currentTaskLabel()} reason=$reason until=$until")
    }

    fun isMethodQuarantined(method: String?): Boolean {
        if (method.isNullOrBlank()) return false
        val until = quarantinedMethods[method] ?: return false
        if (until > System.currentTimeMillis()) return true
        quarantinedMethods.remove(method, until)
        return false
    }

    /** 是否有正在运行的探活计划；用于阻止 onResume 在验证完成前提前解除暂停。 */
    fun isProbeActive(): Boolean = probeJob.get()?.isActive == true

    private fun escalateIfRepeated(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(recentHits) {
            recentHits.removeAll { now - it > ESCALATION_WINDOW_MS }
            recentHits.add(now)
            return recentHits.size >= ESCALATION_HIT_COUNT
        }
    }

    /**
     * 由外部信号（如 H5 验证页 URL）触发的软暂停。
     *
     * 页面级验证不会产生 RPC 错误码，模块只会表现成"任务状态不推进"；
     * 因此这里不等离线态，直接按软验证处理：暂停 + 探活 + 自动续跑。
     */
    fun onExternalVerificationSignal(
        source: String,
        detail: String,
    ) {
        if (ApplicationHookConstants.isOffline()) {
            // 已经离线时只续期探活窗口，不重复停任务。
            trace("收到外部验证信号，续期探活: $source / $detail")
            startProbeCycle(RiskTier.SOFT_VERIFY, "$source $detail")
            return
        }
        trace("收到外部验证信号，按软验证暂停: $source / $detail")
        onVerificationRisk(source, "", "页面验证: $detail")
    }

    /** 账号切换/模块销毁时释放探活协程。 */
    fun cancelProbe() {
        probeJob.getAndSet(null)?.cancel()
        pausedUserId = null
        pausedSessionEpoch = 0L
        lastSignalAtMs = 0L
    }

    private fun startProbeCycle(
        tier: RiskTier,
        detail: String,
    ) {
        val session = AccountSessionCoordinator.currentSession()
        if (session == null) {
            trace("无活动账号会话，跳过探活计划: $detail")
            return
        }
        val now = System.currentTimeMillis()
        val samePause = pausedUserId == session.userId && pausedSessionEpoch == session.sessionEpoch
        val repeated = samePause && (now - lastSignalAtMs) in 0 until REPEAT_SIGNAL_WINDOW_MS
        lastSignalAtMs = now
        pausedUserId = session.userId
        pausedSessionEpoch = session.sessionEpoch
        if (repeated && probeJob.get()?.isActive == true) {
            trace("同一暂停窗口内的重复风控信号，沿用现有探活计划 tier=$tier")
            return
        }
        probeJob.getAndSet(
            probeScope.launch {
                runProbeCycle(session.userId, session.sessionEpoch, tier)
            },
        )?.cancel()
    }

    private suspend fun runProbeCycle(
        userId: String,
        sessionEpoch: Long,
        tier: RiskTier,
    ) {
        val multiplier = if (tier == RiskTier.HARD_BLOCK) HARD_DELAY_MULTIPLIER else 1L
        for ((index, baseDelayMs) in probeDelaysMs.withIndex()) {
            val delayMs = baseDelayMs * multiplier + Random.nextLong(0L, baseDelayMs / 5 + 1L)
            delay(delayMs)
            if (!isSameSession(userId, sessionEpoch)) {
                trace("账号会话已变化，终止探活")
                return
            }
            if (!ApplicationHookConstants.isOffline()) {
                trace("暂停状态已由其他路径解除，终止探活")
                return
            }
            if (probeUnblocked()) {
                resumeAfterProbe(tier)
                return
            }
            trace("第${index + 1}/${probeDelaysMs.size}次探活仍被验证/风控拦截 tier=$tier")
        }
        trace("探活次数用尽，保持暂停等待用户完成验证 tier=$tier")
    }

    /**
     * 只读探活。判定标准不是"业务成功"，而是"响应里没有验证/封锁信号"——
     * 任务不存在、城市不支持之类的普通业务错误同样说明请求已经正常到达服务端。
     */
    private fun probeUnblocked(): Boolean {
        for ((index, fetch) in probeFetchers.withIndex()) {
            val raw = runCatching {
                ApplicationHookConstants.withProbeBypass { fetch() }
            }.onFailure {
                Log.printStackTrace(TAG, "探活请求异常 index=$index", it)
            }.getOrNull()
            val response = raw
                ?.takeIf { it.isNotBlank() }
                ?.let { text -> runCatching { JSONObject(text) }.getOrNull() }
            if (response == null) {
                trace("探活[$index] 无有效响应，继续等待")
                continue
            }
            if (isRiskResponse(response)) {
                trace("探活[$index] 仍处于验证/风控态")
                continue
            }
            trace("探活[$index] 已恢复正常")
            return true
        }
        return false
    }

    private fun isRiskResponse(response: JSONObject): Boolean =
        RpcDailyCircuit.isStopResponse(response) ||
            RpcOfflineRisk.isOfflineRisk(response) ||
            RpcOfflineRisk.isHardBlocked(response)

    private fun resumeAfterProbe(tier: RiskTier) {
        if (!ApplicationHookConstants.isOffline()) return
        val session = AccountSessionCoordinator.currentSession() ?: return
        trace("探活通过，解除暂停并续跑 tier=$tier userId=${session.userId} epoch=${session.sessionEpoch}",
        )
        ApplicationHookConstants.setOffline(false)
        runCatching { UnifiedScheduler.cancelNamedTask("重新登录") }
        ApplicationHook.lastExecTime = 0L
        Notify.updateRunningStatus("✅ 验证已解除，恢复执行")
        runCatching { ApplicationHookConstants.clearPendingTriggers("risk_recovered") }
        ApplicationHook.appContext?.let { context ->
            runCatching {
                UnifiedScheduler.reconcilePersistentSchedules(
                    context,
                    mode = PersistentReconcileMode.FIRE_ALARM_DUE,
                )
            }.onFailure { Log.printStackTrace(TAG, "恢复持久调度失败", it) }
        }
        runCatching {
            ApplicationHookCore.requestExecution(
                TriggerInfo(
                    type = TriggerType.ON_RESUME,
                    priority = TriggerPriority.HIGH,
                    reason = "risk_recovered",
                    dedupeKey = "risk_recovered",
                    ownerUserId = session.userId,
                    sessionEpoch = session.sessionEpoch,
                ),
            )
        }.onFailure { Log.printStackTrace(TAG, "恢复执行触发失败", it) }
        pausedUserId = null
        pausedSessionEpoch = 0L
        lastSignalAtMs = 0L
    }

    private fun isSameSession(
        userId: String,
        sessionEpoch: Long,
    ): Boolean {
        val current = AccountSessionCoordinator.currentSession() ?: return false
        return current.userId == userId && current.sessionEpoch == sessionEpoch
    }

    /**
     * 风控事件同时写两处：
     * 1. 模块日志 record.log（受"记录 record 日志"开关控制，可在 App 内日志查看器搜索）；
     * 2. logcat（tag=SesameLog），无需预先打开任何设置即可用 adb 观察。
     */
    private fun trace(message: String) {
        Log.record(TAG, message)
        runCatching { android.util.Log.i(LOGCAT_TAG, "$TAG: $message") }
    }

    private fun buildDetail(
        source: String,
        code: String,
        message: String,
    ): String =
        buildString {
            // 归因：哪个任务触发的风控（模块/流程），排查时一眼能看出问题任务。
            append("task=").append(currentTaskLabel()).append(' ')
            append(source)
            if (code.isNotBlank()) append(" code=").append(code)
            if (message.isNotBlank()) append(" msg=").append(message.take(200))
        }

    private fun currentTaskLabel(): String =
        runCatching {
            io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.taskLabel()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

    const val REASON_VERIFY: String = "auth_like"
    const val REASON_HARD_BLOCK: String = "risk_hard_block"
}

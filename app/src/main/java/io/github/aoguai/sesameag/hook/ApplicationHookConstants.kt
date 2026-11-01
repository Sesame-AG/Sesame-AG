package io.github.aoguai.sesameag.hook

import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.task.ModelTask.Companion.stopAllTask
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log.record
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

object ApplicationHookConstants {
    private const val TAG = "ApplicationHook"

    object BroadcastActions {
        const val RESTART: String = "com.eg.android.AlipayGphone.sesame.restart"
        const val EXECUTE: String = "com.eg.android.AlipayGphone.sesame.execute"
        const val PRE_WAKEUP: String = "com.eg.android.AlipayGphone.sesame.prewakeup"
        const val RE_LOGIN: String = "com.eg.android.AlipayGphone.sesame.reLogin"
        const val RPC_TEST: String = "com.eg.android.AlipayGphone.sesame.rpctest"
        const val MANUAL_TASK: String = "com.eg.android.AlipayGphone.sesame.manual_task"
        const val HOOK_READY: String = "com.eg.android.AlipayGphone.sesame.hook_ready"
        const val HOOK_READY_RESULT: String = "com.eg.android.AlipayGphone.sesame.hook_ready_result"
        const val PERMISSION_SNAPSHOT: String = "com.eg.android.AlipayGphone.sesame.permission_snapshot"
        const val PERMISSION_SNAPSHOT_RESULT: String = "com.eg.android.AlipayGphone.sesame.permission_snapshot_result"
        const val CAPTURE_PAGE_SNAPSHOT: String = "com.eg.android.AlipayGphone.sesame.capture_page_snapshot"
        const val REFRESH_FRIENDS: String = "com.eg.android.AlipayGphone.sesame.refresh_friends"
        const val REFRESH_FRIENDS_RESULT: String = "com.eg.android.AlipayGphone.sesame.refresh_friends_result"
        const val REFRESH_EXCHANGE_OPTIONS: String = "com.eg.android.AlipayGphone.sesame.refresh_exchange_options"
        const val REFRESH_EXCHANGE_OPTIONS_RESULT: String = "com.eg.android.AlipayGphone.sesame.refresh_exchange_options_result"
        const val ACCOUNT_CONTEXT_CHANGED: String = "com.eg.android.AlipayGphone.sesame.account_context_changed"
    }

    object AlipayClasses {
        const val APPLICATION: String = "com.alipay.mobile.framework.AlipayApplication"
        const val SOCIAL_SDK: String = "com.alipay.mobile.personalbase.service.SocialSdkContactService"
        const val LAUNCHER_ACTIVITY: String = "com.alipay.mobile.quinox.LauncherActivity"
        const val SERVICE: String = "android.app.Service"
        const val LOADED_APK: String = "android.app.LoadedApk"
    }

    const val MAX_INACTIVE_TIME: Long = 3600000L

    // --------------------------------------------------------------------
    // Offline policy (Phase 3)
    // 目标：在不改变现有熔断阈值/通知逻辑的前提下，补齐离线原因/事件队列等可观测能力，
    // 并提供统一的 shouldBlockRpc() gating 点，避免“部分入口绕过离线”。
    // --------------------------------------------------------------------

    @Volatile
    var nowProvider: () -> Long = { System.currentTimeMillis() }

    @Volatile
    var offline: Boolean = false
        private set

    @JvmStatic
    fun isOffline(): Boolean = offline

    @Volatile
    var offlineUntilMs: Long = 0L
        private set

    @Volatile
    var offlineReason: String? = null
        private set

    @Volatile
    var offlineReasonDetail: String? = null
        private set

    val offlineEnterCount: AtomicInteger = AtomicInteger(0)
    val offlineExitCount: AtomicInteger = AtomicInteger(0)

    @Volatile
    var lastOfflineEnterAtMs: Long = 0L
        private set

    @Volatile
    var lastOfflineExitAtMs: Long = 0L
        private set

    @Volatile
    var lastOfflineEnterReason: String? = null
        private set

    @Volatile
    var lastOfflineEnterReasonDetail: String? = null
        private set

    enum class OfflineEventType {
        ENTER,
        REFRESH,
        EXIT,
    }

    data class OfflineEvent(
        val type: OfflineEventType,
        val atMs: Long,
        val cooldownMs: Long,
        val untilMs: Long,
        val reason: String?,
        val detail: String?,
    )

    data class AuthLikeOfflineSnapshot(
        val active: Boolean,
        val untilMs: Long,
        val detail: String,
        val method: String,
        val code: String,
        val message: String,
    )

    private const val OFFLINE_EVENT_MAX = 64
    private val offlineEvents = ConcurrentLinkedQueue<OfflineEvent>()
    private val offlineEventSize = AtomicInteger(0)

    private fun addOfflineEvent(event: OfflineEvent) {
        offlineEvents.add(event)
        val size = offlineEventSize.incrementAndGet()
        if (size <= OFFLINE_EVENT_MAX) return

        while (offlineEventSize.get() > OFFLINE_EVENT_MAX) {
            val removed = offlineEvents.poll() ?: break
            offlineEventSize.decrementAndGet()
            if (removed === event) break
        }
    }

    @JvmStatic
    fun getOfflineEventsSnapshot(): List<OfflineEvent> = offlineEvents.toList()

    private fun extractOfflineDetailValue(
        detail: String,
        key: String,
        vararg nextKeys: String,
    ): String {
        val marker = "$key="
        val start = detail.indexOf(marker)
        if (start < 0) {
            return ""
        }
        val valueStart = start + marker.length
        var valueEnd = detail.length
        for (nextKey in nextKeys) {
            val candidate = detail.indexOf(" $nextKey=", valueStart)
            if (candidate >= 0 && candidate < valueEnd) {
                valueEnd = candidate
            }
        }
        return detail.substring(valueStart, valueEnd).trim()
    }

    private fun extractOfflineMethod(detail: String): String {
        val explicitMethod = extractOfflineDetailValue(detail, "method", "code", "msg", "reason")
        if (explicitMethod.isNotBlank()) {
            return explicitMethod
        }
        val firstMarker =
            sequenceOf(" code=", " msg=", " reason=")
                .map { detail.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull()
                ?: -1
        if (firstMarker <= 0) {
            return ""
        }
        val candidate = detail.substring(0, firstMarker).trim()
        return candidate.takeIf { it.contains(".") && !it.contains(" ") }.orEmpty()
    }

    @JvmStatic
    fun getLatestAuthLikeOfflineSnapshot(): AuthLikeOfflineSnapshot? {
        val active = offline && offlineReason == "auth_like"
        val detail =
            when {
                active -> offlineReasonDetail
                lastOfflineEnterReason == "auth_like" -> lastOfflineEnterReasonDetail
                else -> null
            }?.trim().orEmpty()
        if (detail.isBlank()) {
            return null
        }
        return AuthLikeOfflineSnapshot(
            active = active,
            untilMs = if (active) offlineUntilMs else 0L,
            detail = detail,
            method = extractOfflineMethod(detail),
            code = extractOfflineDetailValue(detail, "code", "msg", "reason"),
            message = extractOfflineDetailValue(detail, "msg", "reason"),
        )
    }

    @JvmStatic
    fun enterOffline(
        cooldownMs: Long,
        reason: String? = null,
        detail: String? = null,
    ) {
        val wasOffline = offline
        val now = nowProvider()

        offline = true
        offlineReason = reason
        offlineReasonDetail = detail

        if (!wasOffline) {
            offlineEnterCount.incrementAndGet()
            lastOfflineEnterAtMs = now
            lastOfflineEnterReason = reason
            lastOfflineEnterReasonDetail = detail
        }

        offlineUntilMs = if (cooldownMs > 0) (now + cooldownMs) else 0L

        record(
            TAG,
            "enterOffline: type=${if (wasOffline) OfflineEventType.REFRESH else OfflineEventType.ENTER} cooldownMs=$cooldownMs untilMs=$offlineUntilMs reason=${reason ?: "null"} detail=${detail ?: "null"}",
        )

        addOfflineEvent(
            OfflineEvent(
                type = if (wasOffline) OfflineEventType.REFRESH else OfflineEventType.ENTER,
                atMs = now,
                cooldownMs = cooldownMs,
                untilMs = offlineUntilMs,
                reason = reason,
                detail = detail,
            ),
        )

        if (!wasOffline) {
            val runningMainTask = ApplicationHook.mainTask
            if (runningMainTask?.isRunning == true) {
                record(TAG, "offline entered, stop current mainTask to pause workflow")
                runningMainTask.stopTask()
            }
            stopAllTask()
            // 离线/风控发生时实时通知用户（含滑块验证 auth_like），补齐“风控无感知”缺口。
            if (BaseModel.errNotify.value == true) {
                val title =
                    when (reason) {
                        "auth_like" -> "检测到风控/验证，已暂停"
                        "rpc_error_threshold" -> "RPC 连续失败，已暂停"
                        "network_error_threshold" -> "网络异常次数超过阈值，已暂停"
                        else -> "模块已进入离线，已暂停"
                    }
                runCatching {
                    io.github.aoguai.sesameag.util.Notify.sendAlert(
                        title,
                        detail?.takeIf { it.isNotBlank() } ?: "请打开目标应用完成验证或查看错误日志",
                    )
                }
            }
        }

        AccountSessionCoordinator.refreshWorkflowState(
            ApplicationHook.appContext,
            if (wasOffline) "offline_refresh" else "offline_enter",
        )
        ModuleStatusReporter.requestUpdate(if (wasOffline) "offline_refresh" else "offline_enter")
    }

    @JvmStatic
    fun getOfflineCooldownMs(): Long {
        val configured = BaseModel.offlineCooldown.value?.toLong() ?: 0L
        if (configured > 0L) {
            return maxOf(configured, 180000L)
        }

        return maxOf(
            BaseModel.checkInterval.value?.toLong() ?: 180000L,
            180000L,
        )
    }

    @JvmStatic
    fun setOffline(value: Boolean) {
        setOffline(value, null, null)
    }

    @JvmStatic
    fun setOffline(
        value: Boolean,
        reason: String?,
        detail: String?,
    ) {
        if (value) {
            enterOffline(0L, reason, detail)
        } else {
            exitOffline()
        }
    }

    @JvmStatic
    fun exitOffline() {
        if (!offline) return

        val now = nowProvider()
        val enterAtMs = lastOfflineEnterAtMs
        val durationMs = if (enterAtMs > 0L) (now - enterAtMs).coerceAtLeast(0L) else -1L
        val enterReason = offlineReason
        val enterDetail = offlineReasonDetail

        offline = false
        offlineUntilMs = 0L
        offlineReason = null
        offlineReasonDetail = null

        offlineExitCount.incrementAndGet()
        lastOfflineExitAtMs = now

        addOfflineEvent(
            OfflineEvent(
                type = OfflineEventType.EXIT,
                atMs = now,
                cooldownMs = 0L,
                untilMs = 0L,
                reason = enterReason,
                detail = enterDetail,
            ),
        )

        record(TAG, "exitOffline: type=EXIT durationMs=$durationMs reason=${enterReason ?: "null"} detail=${enterDetail ?: "null"}")

        AccountSessionCoordinator.refreshWorkflowState(
            ApplicationHook.appContext,
            "offline_exit",
        )
        ModuleStatusReporter.requestUpdate("offline_exit")
    }

    @JvmStatic
    fun shouldBlockRpc(): Boolean = offline

    enum class TriggerPriority(
        val weight: Int,
    ) {
        HIGH(2),
        NORMAL(1),
        LOW(0),
    }

    enum class TriggerType {
        ON_RESUME,
        INIT,
        ALARM_POLL,
        ALARM_WAKEUP,
        BROADCAST_EXECUTE,
        BROADCAST_PREWAKEUP,
        INTERVAL_RETRY,
    }

    data class TriggerInfo(
        val type: TriggerType,
        val priority: TriggerPriority = TriggerPriority.NORMAL,
        val createdAtMs: Long = System.currentTimeMillis(),
        val alarmTriggered: Boolean = false,
        val wakenAtTime: Boolean = false,
        val wakenTime: String? = null,
        val reason: String? = null,
        val dedupeKey: String? = null,
        val persistentScheduleId: String? = null,
        val ownerUserId: String? = null,
        val sessionEpoch: Long = 0L,
    ) {
        fun summary(): String {
            val parts = mutableListOf<String>()
            parts.add(type.name)
            parts.add("p=${priority.name}")
            if (alarmTriggered) parts.add("alarm")
            if (wakenAtTime) parts.add("wakenAtTime")
            if (!wakenTime.isNullOrBlank()) parts.add("wakenTime=$wakenTime")
            if (!reason.isNullOrBlank()) parts.add("reason=$reason")
            if (!persistentScheduleId.isNullOrBlank()) parts.add("schedule=$persistentScheduleId")
            if (!ownerUserId.isNullOrBlank()) parts.add("owner=$ownerUserId")
            if (sessionEpoch > 0L) parts.add("session=$sessionEpoch")
            return parts.joinToString(" ")
        }
    }

    private const val MAX_TRIGGER_QUEUE_SIZE = 32
    private data class TriggerState(
        val pending: TriggerInfo? = null,
        val queued: List<TriggerInfo> = emptyList(),
        val lastConsumed: TriggerInfo? = null,
    )
    private val triggers = java.util.concurrent.atomic.AtomicReference(TriggerState())

    val lastConsumedTrigger: TriggerInfo?
        get() = triggers.get().lastConsumed

    fun hasPendingTriggers(): Boolean = triggers.get().let { it.pending != null || it.queued.isNotEmpty() }

    fun pendingTriggerCount(): Int = triggers.get().let { (if (it.pending == null) 0 else 1) + it.queued.size }

    data class TriggerQueueResult(
        val accepted: Boolean,
        val displaced: TriggerInfo? = null,
        val replaced: List<TriggerInfo> = emptyList(),
    )

    fun setPendingTrigger(trigger: TriggerInfo): TriggerQueueResult {
        while (true) {
            val before = triggers.get()
            val key = trigger.dedupeKey
            val replaced = if (key.isNullOrBlank()) emptyList() else
                (listOfNotNull(before.pending) + before.queued).filter { it.dedupeKey == key }
            var pending = before.pending?.takeUnless { candidate -> replaced.any { it === candidate } }
            val queued = before.queued.filterNot { candidate -> replaced.any { it === candidate } }.toMutableList()
            val toQueue = when {
                pending == null -> { pending = trigger; null }
                trigger.priority.weight > pending.priority.weight -> pending.also { pending = trigger }
                else -> trigger
            }
            var displaced: TriggerInfo? = null
            if (toQueue != null) {
                if (queued.size >= MAX_TRIGGER_QUEUE_SIZE) {
                    val index = queued.indexOfFirst {
                        it.type == TriggerType.ALARM_POLL && it.priority == TriggerPriority.LOW
                    }.takeIf { it >= 0 } ?: 0
                    displaced = queued.removeAt(index)
                }
                queued.add(toQueue)
            }
            val after = before.copy(pending = pending, queued = queued.toList())
            if (!triggers.compareAndSet(before, after)) continue
            displaced?.let { record(TAG, "🗑️ trigger queue full, defer displaced: ${it.summary()}") }
            record(TAG, "📥 trigger queued: ${trigger.summary()} | pending=${pending?.summary()} | q=${queued.size}")
            return TriggerQueueResult(accepted = true, displaced = displaced, replaced = replaced)
        }
    }

    fun consumePendingTrigger(): TriggerInfo? {
        while (true) {
            val before = triggers.get()
            val trigger = before.pending ?: before.queued.maxByOrNull { it.priority.weight } ?: return null
            val after = if (before.pending != null) {
                before.copy(pending = null, lastConsumed = trigger)
            } else {
                val queued = before.queued.toMutableList()
                queued.removeAt(queued.indexOfFirst { it === trigger })
                before.copy(queued = queued.toList(), lastConsumed = trigger)
            }
            if (!triggers.compareAndSet(before, after)) continue
            record(TAG, "📤 trigger consumed: ${trigger.summary()} | remain=${(if (after.pending == null) 0 else 1) + after.queued.size}")
            return trigger
        }
    }

    fun clearPendingTriggers(reason: String? = null) {
        val before = triggers.getAndUpdate { it.copy(pending = null, queued = emptyList()) }
        record(TAG, "🧹 trigger cleared${if (reason.isNullOrBlank()) "" else ": $reason"} | before=${(if (before.pending == null) 0 else 1) + before.queued.size} after=0")
    }

    fun removePendingTriggers(
        reason: String,
        predicate: (TriggerInfo) -> Boolean,
    ): List<TriggerInfo> {
        val snapshot = triggers.get()
        val rejected = (listOfNotNull(snapshot.pending) + snapshot.queued).filter(predicate)
        while (true) {
            val before = triggers.get()
            val removed = (listOfNotNull(before.pending) + before.queued).filter { candidate ->
                rejected.any { it === candidate }
            }
            if (removed.isEmpty()) return emptyList()
            val after = before.copy(
                pending = before.pending?.takeUnless { candidate -> removed.any { it === candidate } },
                queued = before.queued.filterNot { candidate -> removed.any { it === candidate } },
            )
            if (!triggers.compareAndSet(before, after)) continue
            record(TAG, "🧹 trigger removed: $reason | count=${removed.size} remain=${(if (after.pending == null) 0 else 1) + after.queued.size}")
            return removed
        }
    }

    private const val DEFAULT_ENTRY_DEBOUNCE_MS: Long = 150L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val entryDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val debouncedJobs = ConcurrentHashMap<String, Job>()

    fun submitEntry(
        name: String,
        block: () -> Unit,
    ): Job =
        GlobalThreadPools.execute(entryDispatcher + CoroutineName("Entry:$name")) {
            block()
        }

    fun submitEntryDebounced(
        name: String,
        debounceMs: Long = DEFAULT_ENTRY_DEBOUNCE_MS,
        block: () -> Unit,
    ): Job {
        debouncedJobs.remove(name)?.cancel()

        val job =
            GlobalThreadPools.execute(entryDispatcher + CoroutineName("Entry:$name")) {
                // 同名入口取消旧任务后等待防抖窗口，只执行最后一次触发，避免重复信号启动相同工作流。
                if (debounceMs > 0) delay(debounceMs)
                block()
            }

        debouncedJobs[name] = job
        job.invokeOnCompletion { debouncedJobs.remove(name, job) }
        return job
    }
}

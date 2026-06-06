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
        AUTO_EXIT
    }

    data class OfflineEvent(
        val type: OfflineEventType,
        val atMs: Long,
        val cooldownMs: Long,
        val untilMs: Long,
        val reason: String?,
        val detail: String?
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
    fun getOfflineEventsSnapshot(): List<OfflineEvent> {
        return offlineEvents.toList()
    }

    @JvmStatic
    fun enterOffline(cooldownMs: Long, reason: String? = null, detail: String? = null) {
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
            "enterOffline: type=${if (wasOffline) OfflineEventType.REFRESH else OfflineEventType.ENTER} cooldownMs=$cooldownMs untilMs=$offlineUntilMs reason=${reason ?: "null"} detail=${detail ?: "null"}"
        )

        addOfflineEvent(
            OfflineEvent(
                type = if (wasOffline) OfflineEventType.REFRESH else OfflineEventType.ENTER,
                atMs = now,
                cooldownMs = cooldownMs,
                untilMs = offlineUntilMs,
                reason = reason,
                detail = detail
            )
        )

        if (!wasOffline) {
            val runningMainTask = ApplicationHook.mainTask
            if (runningMainTask?.isRunning == true) {
                record(TAG, "offline entered, stop current mainTask to pause workflow")
                runningMainTask.stopTask()
            }
            stopAllTask()
        }

        AccountSessionCoordinator.refreshWorkflowState(
            ApplicationHook.appContext,
            if (wasOffline) "offline_refresh" else "offline_enter"
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
            180000L
        )
    }

    @JvmStatic
    fun setOffline(value: Boolean) {
        setOffline(value, null, null)
    }

    @JvmStatic
    fun setOffline(value: Boolean, reason: String?, detail: String?) {
        if (value) {
            enterOffline(0L, reason, detail)
        } else {
            exitOffline()
        }
    }

    @JvmStatic
    fun exitOffline() {
        exitOfflineInternal(OfflineEventType.EXIT)
    }

    private fun exitOfflineInternal(type: OfflineEventType) {
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
                type = type,
                atMs = now,
                cooldownMs = 0L,
                untilMs = 0L,
                reason = enterReason,
                detail = enterDetail
            )
        )

        record(TAG, "exitOffline: type=$type durationMs=$durationMs reason=${enterReason ?: "null"} detail=${enterDetail ?: "null"}")

        AccountSessionCoordinator.refreshWorkflowState(
            ApplicationHook.appContext,
            when (type) {
                OfflineEventType.EXIT -> "offline_exit"
                OfflineEventType.AUTO_EXIT -> "offline_auto_exit"
                else -> "offline_exit"
            }
        )
        ModuleStatusReporter.requestUpdate(
            when (type) {
                OfflineEventType.EXIT -> "offline_exit"
                OfflineEventType.AUTO_EXIT -> "offline_auto_exit"
                else -> "offline_exit"
            }
        )
    }

    private const val ENABLE_OFFLINE_AUTO_EXIT = false

    @JvmStatic
    fun shouldBlockRpc(): Boolean {
        if (!offline) return false

        if (!ENABLE_OFFLINE_AUTO_EXIT) {
            return true
        }

        val untilMs = offlineUntilMs
        if (untilMs <= 0L) return true

        val now = nowProvider()
        if (now < untilMs) return true

        exitOfflineInternal(OfflineEventType.AUTO_EXIT)
        return false
    }

    enum class TriggerPriority(val weight: Int) {
        HIGH(2),
        NORMAL(1),
        LOW(0)
    }

    enum class TriggerType {
        ON_RESUME,
        INIT,
        ALARM_POLL,
        ALARM_WAKEUP,
        BROADCAST_EXECUTE,
        BROADCAST_PREWAKEUP,
        INTERVAL_RETRY
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
        val sessionEpoch: Long = 0L
    ) {
        fun summary(): String {
            val parts = mutableListOf<String>()
            parts.add(type.name)
            parts.add("p=${priority.name}")
            if (alarmTriggered) parts.add("alarm")
            if (wakenAtTime) parts.add("wakenAtTime")
            if (!wakenTime.isNullOrBlank()) parts.add("wakenTime=$wakenTime")
            if (!reason.isNullOrBlank()) parts.add("reason=$reason")
            if (!ownerUserId.isNullOrBlank()) parts.add("owner=$ownerUserId")
            if (sessionEpoch > 0L) parts.add("session=$sessionEpoch")
            return parts.joinToString(" ")
        }
    }

    private const val MAX_TRIGGER_QUEUE_SIZE = 32
    private val triggerLock = Any()
    private var pendingTrigger: TriggerInfo? = null
    private val triggerQueue = ArrayDeque<TriggerInfo>(MAX_TRIGGER_QUEUE_SIZE)

    @Volatile
    var lastConsumedTrigger: TriggerInfo? = null
        private set

    fun hasPendingTriggers(): Boolean = synchronized(triggerLock) {
        pendingTrigger != null || triggerQueue.isNotEmpty()
    }

    fun pendingTriggerCount(): Int = synchronized(triggerLock) {
        (if (pendingTrigger == null) 0 else 1) + triggerQueue.size
    }

    fun setPendingTrigger(trigger: TriggerInfo) {
        synchronized(triggerLock) {
            val dedupeKey = trigger.dedupeKey
            if (!dedupeKey.isNullOrBlank()) {
                if (pendingTrigger?.dedupeKey == dedupeKey) {
                    pendingTrigger = null
                }
                if (triggerQueue.isNotEmpty()) {
                    val it = triggerQueue.iterator()
                    while (it.hasNext()) {
                        if (it.next().dedupeKey == dedupeKey) {
                            it.remove()
                        }
                    }
                }
            }

            val currentPending = pendingTrigger
            if (currentPending == null) {
                pendingTrigger = trigger
            } else if (trigger.priority.weight > currentPending.priority.weight) {
                enqueueLocked(currentPending)
                pendingTrigger = trigger
            } else {
                enqueueLocked(trigger)
            }

            record(TAG, "📥 trigger queued: ${trigger.summary()} | pending=${pendingTrigger?.summary()} | q=${triggerQueue.size}")
        }
    }

    fun consumePendingTrigger(): TriggerInfo? {
        synchronized(triggerLock) {
            val trigger = pendingTrigger?.also { pendingTrigger = null } ?: dequeueHighestPriorityLocked()
            if (trigger != null) {
                lastConsumedTrigger = trigger
                record(TAG, "📤 trigger consumed: ${trigger.summary()} | remain=${pendingTriggerCount()}")
            }
            return trigger
        }
    }

    fun clearPendingTriggers(reason: String? = null) {
        synchronized(triggerLock) {
            val before = pendingTriggerCount()
            pendingTrigger = null
            triggerQueue.clear()
            record(TAG, "🧹 trigger cleared${if (reason.isNullOrBlank()) "" else ": $reason"} | before=$before after=0")
        }
    }

    fun removePendingTriggers(reason: String, predicate: (TriggerInfo) -> Boolean): List<TriggerInfo> {
        return synchronized(triggerLock) {
            val removed = mutableListOf<TriggerInfo>()
            val currentPending = pendingTrigger
            if (currentPending != null && predicate(currentPending)) {
                removed.add(currentPending)
                pendingTrigger = null
            }
            if (triggerQueue.isNotEmpty()) {
                val it = triggerQueue.iterator()
                while (it.hasNext()) {
                    val trigger = it.next()
                    if (predicate(trigger)) {
                        removed.add(trigger)
                        it.remove()
                    }
                }
            }
            if (removed.isNotEmpty()) {
                record(TAG, "🧹 trigger removed: $reason | count=${removed.size} remain=${pendingTriggerCount()}")
            }
            removed
        }
    }

    private fun enqueueLocked(trigger: TriggerInfo) {
        if (triggerQueue.size >= MAX_TRIGGER_QUEUE_SIZE) {
            val dropped = triggerQueue.pollFirst()
            if (dropped != null) {
                record(TAG, "🗑️ trigger queue full, drop oldest: ${dropped.summary()}")
            }
        }
        triggerQueue.addLast(trigger)
    }

    private fun dequeueHighestPriorityLocked(): TriggerInfo? {
        if (triggerQueue.isEmpty()) return null

        var best: TriggerInfo? = null
        for (t in triggerQueue) {
            val b = best
            if (b == null || t.priority.weight > b.priority.weight) {
                best = t
            }
        }
        if (best != null) {
            triggerQueue.remove(best)
        }
        return best
    }

    private const val DEFAULT_ENTRY_DEBOUNCE_MS: Long = 150L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val entryDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val debouncedJobs = ConcurrentHashMap<String, Job>()

    fun submitEntry(name: String, block: () -> Unit): Job {
        return GlobalThreadPools.execute(entryDispatcher + CoroutineName("Entry:$name")) { block() }
    }

    fun submitEntryDebounced(
        name: String,
        debounceMs: Long = DEFAULT_ENTRY_DEBOUNCE_MS,
        block: () -> Unit
    ): Job {
        debouncedJobs.remove(name)?.cancel()

        val job = GlobalThreadPools.execute(entryDispatcher + CoroutineName("Entry:$name")) {
            if (debounceMs > 0) delay(debounceMs)
            block()
        }

        debouncedJobs[name] = job
        job.invokeOnCompletion { debouncedJobs.remove(name, job) }
        return job
    }
}


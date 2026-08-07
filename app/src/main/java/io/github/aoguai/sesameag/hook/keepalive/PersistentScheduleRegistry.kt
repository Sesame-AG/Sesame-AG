package io.github.aoguai.sesameag.hook.keepalive

import android.content.Context
import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSlotRegistry
import io.github.aoguai.sesameag.hook.ApplicationHookCore
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.RandomAccessFile

object PersistentScheduleRegistry {
    private const val TAG = "PersistentScheduleRegistry"
    private const val STORE_KEY = "persistentSchedules"
    private const val RETAIN_FINISHED_MS = 24 * 60 * 60 * 1000L
    private const val MAX_DEFERRED_ATTEMPTS = 3
    private val activeModuleChildStates = setOf(
        PersistentScheduleState.QUEUED,
        PersistentScheduleState.RUNNING,
    )
    private val terminalScheduleStates = setOf(
        PersistentScheduleState.FIRED,
        PersistentScheduleState.FAILED,
        PersistentScheduleState.EXPIRED,
    )

    private val scheduleListType = object : TypeReference<MutableList<PersistentSchedule>>() {}

    @Volatile
    private var storageReady = false

    // 内存缓存：注册表的真实来源。读路径(get/list/markFired 等)只读缓存副本，
    // 避免每次从磁盘整表解析；写路径写穿透到磁盘，保留进程被杀后的持久恢复能力。
    private val cacheLock = Any()
    private var cache: MutableList<PersistentSchedule>? = null
    private val registryMutationLock = Any()
    private val registryLockDepth = ThreadLocal<Int>()

    data class ReconcileResult(
        val dueSchedules: List<PersistentSchedule>,
        val rescheduledCount: Int,
        val expiredCount: Int,
    )

    fun upsert(
        context: Context,
        schedule: PersistentSchedule,
    ): PersistentSchedule = withRegistryLock { upsertUnlocked(context, schedule) }

    private fun upsertUnlocked(
        context: Context,
        schedule: PersistentSchedule,
    ): PersistentSchedule {
        if (!AccountSlotRegistry.isExecutableUser(schedule.ownerUserId)) {
            return schedule.withFailure("account_slot_inactive")
        }
        if (!ensureStorage()) {
            return schedule.withFailure("persistent_storage_unavailable")
        }
        val now = System.currentTimeMillis()
        val normalized =
            schedule.copy(
                updatedAtMs = now,
                state = PersistentScheduleState.SCHEDULED,
                lastError = null,
            )
        val prepared = PersistentLaunchPolicy.prepareScheduleForRegistration(context, normalized)
        val effectiveSchedule = prepared.schedule
        val schedules = loadMutable()
        val removed =
            schedules.filter {
                it.id == effectiveSchedule.id ||
                    (effectiveSchedule.dedupeKey.isNotBlank() && it.dedupeKey == effectiveSchedule.dedupeKey)
            }
        if (prepared.blockedReason != null) {
            if (removed.isNotEmpty()) {
                schedules.removeAll(removed.toSet())
                save(schedules)
                removed.forEach { SystemWakeScheduler.cancelLaunchConfirmationTimeout(it.id) }
                SystemWakeScheduler.schedule(context, effectiveSchedule, silent = true)
            }
            Log.record(TAG, "持久调度已因禁止系统调度前台拉起目标应用而未注册[${effectiveSchedule.name}]")
            return effectiveSchedule.withFailure(prepared.blockedReason, now)
        }
        removed.firstOrNull { isSameScheduledTask(it, effectiveSchedule) }?.let { existing ->
            return existing
        }

        // 先把候选计划同步写入注册表，再让 AlarmManager 根据整个快照规划唯一的下一次物理唤醒。
        // 这样同窗计划不会各自注册闹钟，同时失败时仍可原子恢复旧快照。
        val previousSchedules = schedules.toList()
        schedules.removeAll(removed.toSet())
        schedules.add(effectiveSchedule)
        save(schedules)
        removed.forEach { SystemWakeScheduler.cancelLaunchConfirmationTimeout(it.id) }
        if (SystemWakeScheduler.schedule(context, effectiveSchedule, silent = true)) {
            Log.runtime(TAG, "${if (removed.isEmpty()) "新增" else "替换"}持久调度[${effectiveSchedule.name}] 已重排物理闹钟")
            return effectiveSchedule
        }

        save(previousSchedules)
        SystemWakeScheduler.schedule(context, effectiveSchedule, silent = true)
        Log.record(TAG, "持久调度注册失败，已恢复旧调度[${effectiveSchedule.name}]")
        return effectiveSchedule.withFailure("system_alarm_schedule_failed", now)
    }

    fun removeByDedupeKey(
        context: Context?,
        dedupeKey: String,
    ): Int = withRegistryLock { removeByDedupeKeyUnlocked(context, dedupeKey) }

    private fun removeByDedupeKeyUnlocked(
        context: Context?,
        dedupeKey: String,
    ): Int {
        if (dedupeKey.isBlank()) return 0
        if (!ensureStorage()) return 0
        val schedules = loadMutable()
        val removed = schedules.filter { it.dedupeKey == dedupeKey }
        if (removed.isEmpty()) return 0
        schedules.removeAll(removed.toSet())
        save(schedules)
        removed.forEach { SystemWakeScheduler.cancelLaunchConfirmationTimeout(it.id) }
        context?.let { ctx -> SystemWakeScheduler.schedule(ctx, removed.first(), silent = true) }
        if (removed.any { it.kind == PersistentScheduleKind.MODULE_CHILD }) {
            ApplicationHookCore.dispatchIfNeeded()
        }
        return removed.size
    }

    fun removeByName(
        context: Context?,
        name: String,
    ): Int = withRegistryLock { removeByNameUnlocked(context, name) }

    private fun removeByNameUnlocked(
        context: Context?,
        name: String,
    ): Int {
        if (name.isBlank()) return 0
        if (!ensureStorage()) return 0
        val schedules = loadMutable()
        val removed = schedules.filter { it.name == name }
        if (removed.isEmpty()) return 0
        schedules.removeAll(removed.toSet())
        save(schedules)
        removed.forEach { SystemWakeScheduler.cancelLaunchConfirmationTimeout(it.id) }
        context?.let { ctx -> SystemWakeScheduler.schedule(ctx, removed.first(), silent = true) }
        return removed.size
    }

    fun removeByNameExceptDedupeKey(
        context: Context?,
        name: String,
        keepDedupeKey: String,
        silent: Boolean = false,
    ): Int = withRegistryLock { removeByNameExceptDedupeKeyUnlocked(context, name, keepDedupeKey, silent) }

    private fun removeByNameExceptDedupeKeyUnlocked(
        context: Context?,
        name: String,
        keepDedupeKey: String,
        silent: Boolean = false,
    ): Int {
        if (name.isBlank() || keepDedupeKey.isBlank()) return 0
        if (!ensureStorage()) return 0
        val schedules = loadMutable()
        val removed = schedules.filter { it.name == name && it.dedupeKey != keepDedupeKey }
        if (removed.isEmpty()) return 0
        schedules.removeAll(removed.toSet())
        save(schedules)
        removed.forEach { SystemWakeScheduler.cancelLaunchConfirmationTimeout(it.id) }
        context?.let { ctx -> SystemWakeScheduler.schedule(ctx, removed.first(), silent = silent) }
        return removed.size
    }

    fun cancelByOwner(
        context: Context?,
        ownerUserId: String,
    ): Int = withRegistryLock { cancelByOwnerUnlocked(context, ownerUserId) }

    private fun cancelByOwnerUnlocked(
        context: Context?,
        ownerUserId: String,
    ): Int {
        val safeOwnerUserId = ownerUserId.trim()
        if (safeOwnerUserId.isEmpty() || !ensureStorage()) return 0
        val schedules = loadMutable()
        val removed = schedules.filter { schedule ->
            schedule.ownerUserId?.trim() == safeOwnerUserId
        }
        if (removed.isEmpty()) return 0
        schedules.removeAll(removed.toSet())
        save(schedules)
        removed.forEach { SystemWakeScheduler.cancelLaunchConfirmationTimeout(it.id) }
        context?.let { ctx ->
            SystemWakeScheduler.schedule(ctx, schedules.firstOrNull() ?: PersistentSchedule(), silent = true)
        }
        if (removed.any { it.kind == PersistentScheduleKind.MODULE_CHILD }) {
            ApplicationHookCore.dispatchIfNeeded()
        }
        return removed.size
    }

    fun get(id: String): PersistentSchedule? {
        if (id.isBlank()) return null
        if (!ensureStorage()) return null
        return loadMutable().firstOrNull { it.id == id }
    }

    fun list(): List<PersistentSchedule> {
        if (!ensureStorage()) return emptyList()
        return loadMutable().toList()
    }

    /**
     * Checks only the current session identity and persisted execution state.  Task names and
     * payload text are intentionally not part of the admission decision.
     */
    fun hasActiveModuleChild(
        ownerUserId: String?,
        sessionEpoch: Long,
    ): Boolean {
        val safeOwnerUserId = ownerUserId?.trim().orEmpty()
        if (safeOwnerUserId.isEmpty() || sessionEpoch <= 0L || !ensureStorage()) return false
        return loadMutable().any { schedule ->
                schedule.kind == PersistentScheduleKind.MODULE_CHILD &&
                schedule.ownerUserId?.trim() == safeOwnerUserId &&
                schedule.sessionEpoch == sessionEpoch &&
                schedule.state in activeModuleChildStates
        }
    }

    /**
     * One physical Alarm may represent several due schedules.  Each schedule still passes through
     * its existing Router state machine, preserving per-id queueing, session validation and retry.
     */
    fun fireDueSchedules(
        context: Context,
        source: String,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val due =
            list()
                .asSequence()
                .filter { schedule ->
                    schedule.state == PersistentScheduleState.SCHEDULED &&
                        schedule.triggerAtMs <= now &&
                        now - schedule.triggerAtMs <= schedule.toleranceMs.coerceAtLeast(0L)
                }.sortedWith(
                    compareBy<PersistentSchedule> {
                        when (it.effectivePrecisionPolicy()) {
                            PersistentSchedulePrecisionPolicy.HARD_DEADLINE_CHILD -> 0
                            PersistentSchedulePrecisionPolicy.USER_EXACT -> 1
                            else -> 2
                        }
                    }.thenBy { it.triggerAtMs },
                ).toList()
        due.forEach { schedule -> ScheduledTaskRouter.fire(context, schedule, source) }
        return due.size
    }

    fun clearAll(context: Context?) = withRegistryLock { clearAllUnlocked(context) }

    private fun clearAllUnlocked(context: Context?) {
        if (!ensureStorage()) return
        val schedules = loadMutable()
        if (schedules.isEmpty()) return
        save(emptyList())
        schedules.forEach { SystemWakeScheduler.cancelLaunchConfirmationTimeout(it.id) }
        context?.let { ctx -> SystemWakeScheduler.schedule(ctx, PersistentSchedule(), silent = true) }
    }

    fun activateSession(
        context: Context,
        ownerUserId: String,
        sessionEpoch: Long,
        now: Long = System.currentTimeMillis(),
    ) = withRegistryLock { activateSessionUnlocked(context, ownerUserId, sessionEpoch, now) }

    private fun activateSessionUnlocked(
        context: Context,
        ownerUserId: String,
        sessionEpoch: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!ensureStorage()) return
        val safeOwnerUserId = ownerUserId.trim()
        if (
            safeOwnerUserId.isEmpty() ||
            sessionEpoch <= 0L ||
            !AccountSlotRegistry.isExecutableUser(safeOwnerUserId)
        ) {
            return
        }
        val schedules = loadMutable()
        if (schedules.isEmpty()) return
        val retained = mutableListOf<PersistentSchedule>()
        for (schedule in schedules) {
            val scheduleOwnerUserId = schedule.ownerUserId?.trim().orEmpty()
            val isCurrentSessionSchedule =
                scheduleOwnerUserId.isNotEmpty() &&
                    scheduleOwnerUserId == safeOwnerUserId &&
                    schedule.sessionEpoch == sessionEpoch
            if (!isCurrentSessionSchedule) {
                SystemWakeScheduler.cancelLaunchConfirmationTimeout(schedule.id)
                continue
            }

            if (schedule.state == PersistentScheduleState.SCHEDULED && schedule.triggerAtMs > now) {
                val prepared = PersistentLaunchPolicy.prepareScheduleForRegistration(context, schedule)
                if (prepared.blockedReason != null) {
                    SystemWakeScheduler.cancelLaunchConfirmationTimeout(schedule.id)
                    Log.record(TAG, "持久调度已因禁止系统调度前台拉起目标应用而停用[${schedule.name}]")
                    continue
                }
                retained.add(prepared.schedule)
            } else {
                SystemWakeScheduler.cancelLaunchConfirmationTimeout(schedule.id)
                retained.add(schedule)
            }
        }
        save(retained)
        SystemWakeScheduler.schedule(context, retained.firstOrNull() ?: PersistentSchedule(), silent = true)
    }

    fun markFired(
        id: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val schedule = get(id)
        val shouldDispatch = schedule?.kind == PersistentScheduleKind.MODULE_CHILD &&
            schedule.state !in terminalScheduleStates
        updateSchedule(id) { it.withFired(now) }
        if (shouldDispatch) {
            ApplicationHookCore.dispatchIfNeeded()
        }
    }

    fun markFired(
        context: Context?,
        id: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val schedule = get(id)
        markFired(id, now)
        if (context != null && schedule != null) {
            SystemWakeScheduler.schedule(context, schedule, silent = true)
        }
    }

    fun markQueued(
        context: Context?,
        id: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (id.isBlank()) return false
        val schedule = get(id) ?: return false
        if (schedule.state != PersistentScheduleState.SCHEDULED) return false
        updateSchedule(id) { it.withQueued(now) }
        context?.let { SystemWakeScheduler.schedule(it, schedule, silent = true) }
        return true
    }

    fun markRunning(
        id: String,
        now: Long = System.currentTimeMillis(),
    ) {
        updateSchedule(id) { schedule ->
            if (schedule.state == PersistentScheduleState.QUEUED || schedule.state == PersistentScheduleState.SCHEDULED) {
                schedule.withRunning(now)
            } else {
                schedule
            }
        }
    }

    fun confirmTargetLaunch(
        id: String,
        now: Long = System.currentTimeMillis(),
    ) {
        updateSchedule(id) { schedule ->
            if (schedule.state == PersistentScheduleState.SCHEDULED) {
                schedule.copy(
                    triggerAtMs = now,
                    updatedAtMs = now,
                    lastError = null,
                )
            } else {
                schedule
            }
        }
    }

    fun rescheduleDeferred(
        context: Context?,
        id: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = withRegistryLock { rescheduleDeferredUnlocked(context, id, reason, now) }

    private fun rescheduleDeferredUnlocked(
        context: Context?,
        id: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (id.isBlank() || context == null) return false
        val schedule = get(id) ?: return false
        if (schedule.state !in setOf(PersistentScheduleState.SCHEDULED, PersistentScheduleState.QUEUED)) {
            return false
        }

        val firstDueAt = schedule.lastFireAtMs.takeIf { it > 0L } ?: schedule.triggerAtMs
        val nextAttempt = schedule.attemptCount + 1
        val delayMs =
            when (nextAttempt) {
                1 -> 15_000L
                2 -> 30_000L
                else -> 60_000L
            }
        val nextTriggerAt = now + delayMs
        val deadline = firstDueAt + schedule.toleranceMs.coerceAtLeast(0L)
        if (nextAttempt > MAX_DEFERRED_ATTEMPTS || nextTriggerAt > deadline) {
            markFailed(context, id, "deferred_exhausted:$reason", now)
            return false
        }

        val updated = schedule.withDeferredRetry(nextTriggerAt, reason, now)
        updateSchedule(id) { current ->
            if (current.id == schedule.id) updated else current
        }
        if (SystemWakeScheduler.schedule(context, updated)) {
            Log.record(
                TAG,
                "持久调度延后重试[${updated.name}] attempt=$nextAttempt at=${TimeUtil.getCommonDate(nextTriggerAt)} reason=$reason",
            )
            return true
        }
        markFailed(context, id, "deferred_alarm_schedule_failed:$reason", now)
        return false
    }

    fun markFailed(
        id: String,
        error: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val schedule = get(id)
        val shouldDispatch = schedule?.kind == PersistentScheduleKind.MODULE_CHILD &&
            schedule.state !in terminalScheduleStates
        updateSchedule(id) { it.withFailure(error, now) }
        if (shouldDispatch) {
            ApplicationHookCore.dispatchIfNeeded()
        }
    }

    fun markFailed(
        context: Context?,
        id: String,
        error: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val schedule = get(id)
        markFailed(id, error, now)
        if (context != null && schedule != null) {
            SystemWakeScheduler.schedule(context, schedule, silent = true)
        }
    }

    fun markExpired(
        context: Context?,
        id: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val schedule = get(id)
        val shouldDispatch = schedule?.kind == PersistentScheduleKind.MODULE_CHILD &&
            schedule.state !in terminalScheduleStates
        updateSchedule(id) { it.withScheduleState(PersistentScheduleState.EXPIRED, now) }
        if (context != null && schedule != null) {
            SystemWakeScheduler.schedule(context, schedule, silent = true)
        }
        if (shouldDispatch) {
            ApplicationHookCore.dispatchIfNeeded()
        }
    }

    fun reconcile(
        context: Context,
        now: Long = System.currentTimeMillis(),
        mode: PersistentReconcileMode = PersistentReconcileMode.RESCHEDULE_ONLY,
    ): ReconcileResult = withRegistryLock { reconcileUnlocked(context, now, mode) }

    private fun reconcileUnlocked(
        context: Context,
        now: Long = System.currentTimeMillis(),
        mode: PersistentReconcileMode = PersistentReconcileMode.RESCHEDULE_ONLY,
    ): ReconcileResult {
        if (!ensureStorage()) {
            return ReconcileResult(emptyList(), 0, 0)
        }
        val schedules = loadMutable()
        if (schedules.isEmpty()) {
            return ReconcileResult(emptyList(), 0, 0)
        }
        val activeSession =
            AccountSessionCoordinator.currentOrPersistedSessionIdentity() ?: run {
                Log.record(TAG, "当前无可恢复会话，跳过持久调度恢复重排")
                return ReconcileResult(emptyList(), 0, 0)
            }
        val due = mutableListOf<PersistentSchedule>()
        var rescheduled = 0
        var expired = 0
        val retained = mutableListOf<PersistentSchedule>()

        for (schedule in schedules) {
            val ownerUserId = schedule.ownerUserId?.trim().orEmpty()
            val isCurrentSessionSchedule =
                ownerUserId.isNotEmpty() &&
                    ownerUserId == activeSession.userId &&
                    schedule.sessionEpoch == activeSession.sessionEpoch

            if (schedule.state != PersistentScheduleState.SCHEDULED) {
                SystemWakeScheduler.cancelLaunchConfirmationTimeout(schedule.id)
                if (isCurrentSessionSchedule && now - schedule.updatedAtMs <= RETAIN_FINISHED_MS) {
                    retained.add(schedule)
                }
                continue
            }

            if (!isCurrentSessionSchedule) {
                SystemWakeScheduler.cancelLaunchConfirmationTimeout(schedule.id)
                continue
            }

            if (schedule.triggerAtMs <= now) {
                val graceMs = schedule.toleranceMs.coerceAtLeast(0L)
                if (now - schedule.triggerAtMs <= graceMs) {
                    if (mode == PersistentReconcileMode.FIRE_ALARM_DUE) {
                        due.add(schedule)
                        retained.add(schedule)
                        Log.record(TAG, "发现到期持久任务[${schedule.name}] ${TimeUtil.getCommonDate(schedule.triggerAtMs)}")
                    } else {
                        expired++
                        SystemWakeScheduler.cancelLaunchConfirmationTimeout(schedule.id)
                        retained.add(schedule.withScheduleState(PersistentScheduleState.EXPIRED, now))
                        Log.runtime(TAG, "恢复重排跳过已到期持久任务[${schedule.name}] ${TimeUtil.getCommonDate(schedule.triggerAtMs)}")
                    }
                } else {
                    expired++
                    SystemWakeScheduler.cancelLaunchConfirmationTimeout(schedule.id)
                    retained.add(schedule.withScheduleState(PersistentScheduleState.EXPIRED, now))
                    Log.record(TAG, "持久任务已过期[${schedule.name}] ${TimeUtil.getCommonDate(schedule.triggerAtMs)}")
                }
                continue
            }

            val prepared = PersistentLaunchPolicy.prepareScheduleForRegistration(context, schedule)
            if (prepared.blockedReason != null) {
                SystemWakeScheduler.cancelLaunchConfirmationTimeout(schedule.id)
                Log.record(TAG, "持久调度已因禁止系统调度前台拉起目标应用而停用[${schedule.name}]")
                continue
            }
            retained.add(prepared.schedule)
        }

        save(retained)
        val replanSucceeded =
            SystemWakeScheduler.schedule(context, retained.firstOrNull() ?: PersistentSchedule(), silent = true)
        if (replanSucceeded && retained.any { it.state == PersistentScheduleState.SCHEDULED && it.triggerAtMs > now }) {
            rescheduled = 1
        }
        return ReconcileResult(
            dueSchedules = due,
            rescheduledCount = rescheduled,
            expiredCount = expired,
        )
    }

    private fun isSameScheduledTask(
        left: PersistentSchedule,
        right: PersistentSchedule,
    ): Boolean =
        left.state == PersistentScheduleState.SCHEDULED &&
            right.state == PersistentScheduleState.SCHEDULED &&
            left.name == right.name &&
            left.kind == right.kind &&
            left.triggerAtMs == right.triggerAtMs &&
            left.toleranceMs == right.toleranceMs &&
            left.dedupeKey == right.dedupeKey &&
            canonicalPayloadJson(left.payloadJson) == canonicalPayloadJson(right.payloadJson) &&
            left.ownerUserId?.trim().orEmpty() == right.ownerUserId?.trim().orEmpty() &&
            left.sessionEpoch == right.sessionEpoch

    private fun canonicalPayloadJson(payloadJson: String): String {
        val trimmed = payloadJson.trim().ifBlank { "{}" }
        return try {
            canonicalJsonValue(JSONObject(trimmed))
        } catch (_: Throwable) {
            trimmed
        }
    }

    private fun canonicalJsonValue(value: Any?): String =
        when (value) {
            null, JSONObject.NULL -> {
                "null"
            }

            is JSONObject -> {
                val keys = mutableListOf<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    keys.add(iterator.next())
                }
                keys.sorted().joinToString(prefix = "{", postfix = "}") { key ->
                    JSONObject.quote(key) + ":" + canonicalJsonValue(value.opt(key))
                }
            }

            is JSONArray -> {
                (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
                    canonicalJsonValue(value.opt(index))
                }
            }

            is String -> {
                JSONObject.quote(value)
            }

            is Number,
            is Boolean,
            -> {
                value.toString()
            }

            else -> {
                JSONObject.quote(value.toString())
            }
        }

    private fun updateSchedule(
        id: String,
        updater: (PersistentSchedule) -> PersistentSchedule,
    ) {
        if (id.isBlank()) return
        withRegistryLock {
            if (!ensureStorage()) return@withRegistryLock
            val schedules = loadMutable()
            val index = schedules.indexOfFirst { it.id == id }
            if (index < 0) return@withRegistryLock
            val previous = schedules[index]
            val updated = updater(previous)
            if (updated == previous) return@withRegistryLock
            schedules[index] = updated
            save(schedules)
            if (previous.state == PersistentScheduleState.SCHEDULED &&
                (updated.state != PersistentScheduleState.SCHEDULED || updated.triggerAtMs != previous.triggerAtMs)
            ) {
                SystemWakeScheduler.cancelLaunchConfirmationTimeout(previous.id)
            }
        }
    }

    private fun loadMutable(): MutableList<PersistentSchedule> {
        if (!ensureStorage()) return mutableListOf()
        // 命中缓存直接返回副本，避免每次从磁盘整表解析。
        synchronized(cacheLock) {
            cache?.let { return it.map { s -> s }.toMutableList() }
        }
        // 缓存缺失：在锁外读取 DataStore，避免与 DataStore 文件监听回调(invalidateCache)形成锁顺序死锁。
        val loaded =
            try {
                DataStore.getOrCreate(STORE_KEY, scheduleListType)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "读取持久调度列表失败", t)
                mutableListOf()
            }
        synchronized(cacheLock) {
            if (cache == null) {
                cache = loaded.toMutableList()
            }
        }
        return loaded.map { it }.toMutableList()
    }

    private fun save(schedules: List<PersistentSchedule>) {
        if (!ensureStorage()) return
        val snapshot = schedules.toMutableList()
        // DataStore 写入会触发跨进程缓存失效回调；必须先落盘，避免 cacheLock 与 DataStore 写锁反向等待。
        DataStore.put(STORE_KEY, snapshot)
        synchronized(cacheLock) {
            cache = snapshot
        }
    }

    private fun ensureStorage(): Boolean {
        if (storageReady) return true
        return synchronized(this) {
            if (storageReady) return@synchronized true
            val initialized =
                runCatching { DataStore.init(Files.CONFIG_DIR) }
                    .onFailure { Log.printStackTrace(TAG, "初始化持久调度存储失败", it) }
                    .isSuccess &&
                    Files.CONFIG_DIR.exists() &&
                    java.io.File(Files.CONFIG_DIR, "DataStore.json").exists()
            if (initialized) {
                storageReady = true
                // 跨进程：当底层 DataStore 文件被其它进程改动并重载时，失效本地缓存，
                // 下次读取重新从 DataStore 取最新数据，避免缓存陈旧导致调度丢失/误取消。
                runCatching {
                    DataStore.setOnChangeListener { invalidateCache() }
                }
            }
            initialized
        }
    }

    private fun <T> withRegistryLock(block: () -> T): T {
        synchronized(registryMutationLock) {
            if ((registryLockDepth.get() ?: 0) > 0) {
                return block()
            }
            if (!ensureStorage()) {
                return block()
            }
            val lockFile = java.io.File(Files.CONFIG_DIR, ".persistent-schedules.lock")
            lockFile.parentFile?.mkdirs()
            val file =
                try {
                    RandomAccessFile(lockFile, "rw")
                } catch (t: java.io.IOException) {
                    Log.printStackTrace(TAG, "打开持久调度锁文件失败，退回进程内互斥", t)
                    return block()
                }
            file.use { randomAccessFile ->
                val fileLock =
                    try {
                        randomAccessFile.channel.lock()
                    } catch (t: java.io.IOException) {
                        Log.printStackTrace(TAG, "获取持久调度跨进程锁失败，退回进程内互斥", t)
                        return block()
                    }
                fileLock.use {
                    registryLockDepth.set(1)
                    try {
                        // 锁内一律舍弃本地快照，整表读改写以最新磁盘状态为起点。
                        invalidateCache()
                        return block()
                    } finally {
                        registryLockDepth.remove()
                    }
                }
            }
        }
    }

    private fun invalidateCache() {
        synchronized(cacheLock) {
            cache = null
        }
    }
}

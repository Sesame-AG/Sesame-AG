package io.github.aoguai.sesameag.hook.keepalive

import android.content.Context
import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import org.json.JSONArray
import org.json.JSONObject

object PersistentScheduleRegistry {
    private const val TAG = "PersistentScheduleRegistry"
    private const val STORE_KEY = "persistentSchedules"
    private const val RETAIN_FINISHED_MS = 24 * 60 * 60 * 1000L

    private val scheduleListType = object : TypeReference<MutableList<PersistentSchedule>>() {}

    @Volatile
    private var storageReady = false

    // 内存缓存：注册表的真实来源。读路径(get/list/markFired 等)只读缓存副本，
    // 避免每次从磁盘整表解析；写路径写穿透到磁盘，保留进程被杀后的持久恢复能力。
    private val cacheLock = Any()
    private var cache: MutableList<PersistentSchedule>? = null
    private val alarmEnsureLock = Any()
    private val ensuredAlarmKeys = mutableSetOf<String>()

    private enum class AlarmScheduleResult {
        SCHEDULED,
        ALREADY_CONFIRMED,
        BLOCKED_BY_POLICY,
        FAILED
    }

    data class ReconcileResult(
        val dueSchedules: List<PersistentSchedule>,
        val rescheduledCount: Int,
        val expiredCount: Int
    )

    fun upsert(context: Context, schedule: PersistentSchedule): PersistentSchedule {
        if (!ensureStorage()) {
            return schedule.withFailure("persistent_storage_unavailable")
        }
        val now = System.currentTimeMillis()
        val normalized = schedule.copy(
            updatedAtMs = now,
            state = PersistentScheduleState.SCHEDULED,
            lastError = null
        )
        val prepared = PersistentLaunchPolicy.prepareScheduleForRegistration(context, normalized)
        val effectiveSchedule = prepared.schedule
        val schedules = loadMutable()
        val removed = schedules.filter {
            it.id == effectiveSchedule.id ||
                (effectiveSchedule.dedupeKey.isNotBlank() && it.dedupeKey == effectiveSchedule.dedupeKey)
        }
        if (prepared.blockedReason != null) {
            removed.forEach { cancelSystemAlarm(context, it, silent = true) }
            if (removed.isNotEmpty()) {
                schedules.removeAll(removed.toSet())
                save(schedules)
            }
            Log.record(TAG, "持久调度已因禁止系统调度前台拉起目标应用而未注册[${effectiveSchedule.name}]")
            return effectiveSchedule.withFailure(prepared.blockedReason, now)
        }
        removed.firstOrNull { isSameScheduledTask(it, effectiveSchedule) }?.let { existing ->
            return existing
        }
        val action = if (removed.isEmpty()) "新增持久调度" else "替换持久调度"
        if (ensureSystemAlarm(context, effectiveSchedule, action, skipIfAlreadyEnsured = false) != AlarmScheduleResult.FAILED) {
            removed
                .filter { it.id != effectiveSchedule.id }
                .forEach { cancelSystemAlarm(context, it, silent = true) }
            schedules.removeAll(removed.toSet())
            schedules.add(effectiveSchedule)
            save(schedules)
            return effectiveSchedule
        }

        val failed = effectiveSchedule.withFailure("system_alarm_schedule_failed", now)
        if (removed.isEmpty()) {
            schedules.add(failed)
            save(schedules)
        } else {
            Log.record(TAG, "持久调度注册失败，保留旧调度[${effectiveSchedule.name}]")
        }
        return failed
    }

    fun removeById(context: Context?, id: String): Boolean {
        if (id.isBlank()) return false
        if (!ensureStorage()) return false
        val schedules = loadMutable()
        val removed = schedules.filter { it.id == id }
        if (removed.isEmpty()) return false
        schedules.removeAll(removed.toSet())
        save(schedules)
        context?.let { ctx -> removed.forEach { cancelSystemAlarm(ctx, it) } }
        return true
    }

    fun removeByDedupeKey(context: Context?, dedupeKey: String): Int {
        if (dedupeKey.isBlank()) return 0
        if (!ensureStorage()) return 0
        val schedules = loadMutable()
        val removed = schedules.filter { it.dedupeKey == dedupeKey }
        if (removed.isEmpty()) return 0
        schedules.removeAll(removed.toSet())
        save(schedules)
        context?.let { ctx -> removed.forEach { cancelSystemAlarm(ctx, it) } }
        return removed.size
    }

    fun removeByName(context: Context?, name: String): Int {
        if (name.isBlank()) return 0
        if (!ensureStorage()) return 0
        val schedules = loadMutable()
        val removed = schedules.filter { it.name == name }
        if (removed.isEmpty()) return 0
        schedules.removeAll(removed.toSet())
        save(schedules)
        context?.let { ctx -> removed.forEach { cancelSystemAlarm(ctx, it) } }
        return removed.size
    }

    fun removeByNameExceptDedupeKey(
        context: Context?,
        name: String,
        keepDedupeKey: String,
        silent: Boolean = false
    ): Int {
        if (name.isBlank() || keepDedupeKey.isBlank()) return 0
        if (!ensureStorage()) return 0
        val schedules = loadMutable()
        val removed = schedules.filter { it.name == name && it.dedupeKey != keepDedupeKey }
        if (removed.isEmpty()) return 0
        schedules.removeAll(removed.toSet())
        save(schedules)
        context?.let { ctx -> removed.forEach { cancelSystemAlarm(ctx, it, silent = silent) } }
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

    fun clearAll(context: Context?) {
        if (!ensureStorage()) return
        val schedules = loadMutable()
        if (schedules.isEmpty()) return
        save(emptyList())
        context?.let { ctx ->
            schedules.forEach { cancelSystemAlarm(ctx, it) }
        }
    }

    fun activateSession(
        context: Context,
        ownerUserId: String,
        sessionEpoch: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (!ensureStorage()) return
        val safeOwnerUserId = ownerUserId.trim()
        if (safeOwnerUserId.isEmpty() || sessionEpoch <= 0L) return
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
                cancelSystemAlarm(context, schedule)
                continue
            }

            if (schedule.state == PersistentScheduleState.SCHEDULED && schedule.triggerAtMs > now) {
                val prepared = PersistentLaunchPolicy.prepareScheduleForRegistration(context, schedule)
                if (prepared.blockedReason != null) {
                    cancelSystemAlarm(context, schedule)
                    Log.record(TAG, "持久调度已因禁止系统调度前台拉起目标应用而停用[${schedule.name}]")
                    continue
                }
                ensureSystemAlarm(context, prepared.schedule, "恢复持久调度", skipIfAlreadyEnsured = true)
                retained.add(prepared.schedule)
            } else {
                cancelSystemAlarm(context, schedule)
                retained.add(schedule)
            }
        }
        save(retained)
    }

    fun markFired(id: String, now: Long = System.currentTimeMillis()) {
        updateSchedule(id) { it.withFired(now) }
    }

    fun markFired(context: Context?, id: String, now: Long = System.currentTimeMillis()) {
        val schedule = get(id)
        markFired(id, now)
        if (context != null && schedule != null) {
            cancelSystemAlarm(context, schedule)
        }
    }

    fun markFailed(id: String, error: String, now: Long = System.currentTimeMillis()) {
        updateSchedule(id) { it.withFailure(error, now) }
    }

    fun markFailed(context: Context?, id: String, error: String, now: Long = System.currentTimeMillis()) {
        val schedule = get(id)
        markFailed(id, error, now)
        if (context != null && schedule != null) {
            cancelSystemAlarm(context, schedule)
        }
    }

    fun markExpired(context: Context?, id: String, now: Long = System.currentTimeMillis()) {
        val schedule = get(id)
        updateSchedule(id) { it.withScheduleState(PersistentScheduleState.EXPIRED, now) }
        if (context != null && schedule != null) {
            cancelSystemAlarm(context, schedule)
        }
    }

    fun reconcile(
        context: Context,
        now: Long = System.currentTimeMillis(),
        mode: PersistentReconcileMode = PersistentReconcileMode.RESCHEDULE_ONLY
    ): ReconcileResult {
        if (!ensureStorage()) {
            return ReconcileResult(emptyList(), 0, 0)
        }
        val schedules = loadMutable()
        if (schedules.isEmpty()) {
            return ReconcileResult(emptyList(), 0, 0)
        }
        val activeSession = AccountSessionCoordinator.currentOrPersistedSessionIdentity() ?: run {
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
                if (isCurrentSessionSchedule && now - schedule.updatedAtMs <= RETAIN_FINISHED_MS) {
                    retained.add(schedule)
                }
                continue
            }

            if (!isCurrentSessionSchedule) {
                cancelSystemAlarm(context, schedule)
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
                        cancelSystemAlarm(context, schedule)
                        retained.add(schedule.withScheduleState(PersistentScheduleState.EXPIRED, now))
                        Log.runtime(TAG, "恢复重排跳过已到期持久任务[${schedule.name}] ${TimeUtil.getCommonDate(schedule.triggerAtMs)}")
                    }
                } else {
                    expired++
                    cancelSystemAlarm(context, schedule)
                    retained.add(schedule.withScheduleState(PersistentScheduleState.EXPIRED, now))
                    Log.record(TAG, "持久任务已过期[${schedule.name}] ${TimeUtil.getCommonDate(schedule.triggerAtMs)}")
                }
                continue
            }

            val prepared = PersistentLaunchPolicy.prepareScheduleForRegistration(context, schedule)
            if (prepared.blockedReason != null) {
                cancelSystemAlarm(context, schedule)
                Log.record(TAG, "持久调度已因禁止系统调度前台拉起目标应用而停用[${schedule.name}]")
                continue
            }

            when (ensureSystemAlarm(context, prepared.schedule, "恢复持久调度", skipIfAlreadyEnsured = true)) {
                AlarmScheduleResult.SCHEDULED -> rescheduled++
                AlarmScheduleResult.ALREADY_CONFIRMED,
                AlarmScheduleResult.BLOCKED_BY_POLICY,
                AlarmScheduleResult.FAILED -> Unit
            }
            retained.add(prepared.schedule)
        }

        save(retained)
        return ReconcileResult(
            dueSchedules = due,
            rescheduledCount = rescheduled,
            expiredCount = expired
        )
    }

    private fun isSameScheduledTask(left: PersistentSchedule, right: PersistentSchedule): Boolean {
        return left.state == PersistentScheduleState.SCHEDULED &&
            right.state == PersistentScheduleState.SCHEDULED &&
            left.name == right.name &&
            left.kind == right.kind &&
            left.triggerAtMs == right.triggerAtMs &&
            left.toleranceMs == right.toleranceMs &&
            left.dedupeKey == right.dedupeKey &&
            canonicalPayloadJson(left.payloadJson) == canonicalPayloadJson(right.payloadJson) &&
            left.ownerUserId?.trim().orEmpty() == right.ownerUserId?.trim().orEmpty() &&
            left.sessionEpoch == right.sessionEpoch
    }

    private fun ensureSystemAlarm(
        context: Context,
        schedule: PersistentSchedule,
        action: String,
        skipIfAlreadyEnsured: Boolean
    ): AlarmScheduleResult {
        val blockedReason = PersistentLaunchPolicy.prepareScheduleForRegistration(context, schedule).blockedReason
        if (blockedReason != null) {
            cancelSystemAlarm(context, schedule, silent = true)
            return AlarmScheduleResult.BLOCKED_BY_POLICY
        }
        val key = alarmKey(schedule)
        if (skipIfAlreadyEnsured && isAlarmEnsured(key)) {
            return AlarmScheduleResult.ALREADY_CONFIRMED
        }
        if (!SystemWakeScheduler.schedule(context, schedule, silent = true)) {
            return AlarmScheduleResult.FAILED
        }
        rememberAlarm(schedule)
        Log.runtime(
            TAG,
            "$action[${schedule.name}] ${TimeUtil.getCommonDate(schedule.triggerAtMs)} dedupeKey=${schedule.dedupeKey}"
        )
        return AlarmScheduleResult.SCHEDULED
    }

    private fun cancelSystemAlarm(context: Context, schedule: PersistentSchedule, silent: Boolean = false) {
        forgetAlarm(schedule)
        SystemWakeScheduler.cancel(context, schedule, silent = silent)
    }

    private fun isAlarmEnsured(key: String): Boolean {
        return synchronized(alarmEnsureLock) { ensuredAlarmKeys.contains(key) }
    }

    private fun rememberAlarm(schedule: PersistentSchedule) {
        synchronized(alarmEnsureLock) {
            ensuredAlarmKeys.add(alarmKey(schedule))
        }
    }

    private fun forgetAlarm(schedule: PersistentSchedule) {
        synchronized(alarmEnsureLock) {
            ensuredAlarmKeys.remove(alarmKey(schedule))
        }
    }

    private fun alarmKey(schedule: PersistentSchedule): String {
        return listOf(
            schedule.id,
            schedule.kind,
            schedule.name,
            schedule.triggerAtMs.toString(),
            schedule.toleranceMs.toString(),
            schedule.dedupeKey,
            canonicalPayloadJson(schedule.payloadJson),
            schedule.ownerUserId?.trim().orEmpty(),
            schedule.sessionEpoch.toString()
        ).joinToString("|")
    }

    private fun canonicalPayloadJson(payloadJson: String): String {
        val trimmed = payloadJson.trim().ifBlank { "{}" }
        return try {
            canonicalJsonValue(JSONObject(trimmed))
        } catch (_: Throwable) {
            trimmed
        }
    }

    private fun canonicalJsonValue(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
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

            is String -> JSONObject.quote(value)
            is Number,
            is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }

    private fun updateSchedule(id: String, updater: (PersistentSchedule) -> PersistentSchedule) {
        if (id.isBlank()) return
        if (!ensureStorage()) return
        val schedules = loadMutable()
        val index = schedules.indexOfFirst { it.id == id }
        if (index < 0) return
        schedules[index] = updater(schedules[index])
        save(schedules)
    }

    private fun loadMutable(): MutableList<PersistentSchedule> {
        if (!ensureStorage()) return mutableListOf()
        // 命中缓存直接返回副本，避免每次从磁盘整表解析。
        synchronized(cacheLock) {
            cache?.let { return it.map { s -> s }.toMutableList() }
        }
        // 缓存缺失：在锁外读取 DataStore，避免与 DataStore 文件监听回调(invalidateCache)形成锁顺序死锁。
        val loaded = try {
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
        // 先更新缓存，再在锁外写穿透到 DataStore（持久化保留进程被杀后的恢复能力）。
        synchronized(cacheLock) {
            cache = snapshot
        }
        DataStore.put(STORE_KEY, snapshot)
    }

    private fun ensureStorage(): Boolean {
        if (storageReady) return true
        return synchronized(this) {
            if (storageReady) return@synchronized true
            val initialized = runCatching { DataStore.init(Files.CONFIG_DIR) }
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

    private fun invalidateCache() {
        synchronized(cacheLock) {
            cache = null
        }
        synchronized(alarmEnsureLock) {
            ensuredAlarmKeys.clear()
        }
    }
}

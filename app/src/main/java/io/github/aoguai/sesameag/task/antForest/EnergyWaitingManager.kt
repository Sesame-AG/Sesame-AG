package io.github.aoguai.sesameag.task.antForest

import android.annotation.SuppressLint
import android.content.Context
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.keepalive.PersistentSchedule
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleKind
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleState
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.random.Random

/**
 * 能量收取回调接口
 */
interface EnergyCollectCallback {
    /**
     * 收取指定用户的能量（蹲点专用）
     * @param task 蹲点任务信息
     * @return 收取结果信息
     */
    suspend fun collectUserEnergyForWaiting(task: EnergyWaitingManager.WaitingTask): CollectResult

    /**
     * 添加能量到总计数
     * @param energyCount 要添加的能量数量
     */
    fun addToTotalCollected(energyCount: Int)

    /**
     * 获取蹲点收取延迟时间配置
     * @return 延迟时间（毫秒）
     */
    fun getWaitingCollectDelay(): Long

    /**
     * 当前是否允许执行蹲点收取。
     */
    fun canRunWaitingCollection(): Boolean = true
}

/**
 * 收取结果数据类
 */
data class CollectResult(
    val success: Boolean,
    val userName: String?,
    val message: String = "",
    val hasShield: Boolean = false,
    val hasBomb: Boolean = false,
    val energyCount: Int = 0,
    val totalCollected: Int = 0,  // 累加后的总能量
    val failedBubbleIds: Set<Long> = emptySet(),
    val allRequestedBubblesFailed: Boolean = false,
    val paused: Boolean = false
)

/**
 * 智能重试策略
 */
class SmartRetryStrategy {
    companion object {
        private val retryDelays = listOf(10000L, 30000L, 60000L, 180000L) // 10秒，30秒，1分钟，3分钟
    }

    /**
     * 获取重试延迟时间
     */
    fun getRetryDelay(retryCount: Int, lastError: String?): Long {
        val baseDelay = retryDelays.getOrElse(retryCount) { 180000L }

        // 根据错误类型调整延迟
        val multiplier = when {
            lastError?.contains("网络") == true -> 2.0 // 网络错误：延长等待
            lastError?.contains("频繁") == true -> 3.0 // 频繁请求：大幅延长
            lastError?.contains("保护") == true -> 1.0 // 保护状态：正常等待
            else -> 1.0
        }

        // 添加随机抖动，避免同时重试
        val jitter = Random.nextLong(-2000L, 2000L)
        return (baseDelay * multiplier).toLong() + jitter
    }

    /**
     * 判断是否应该重试
     */
    fun shouldRetry(retryCount: Int, error: String?, timeToTarget: Long): Boolean {
        if (retryCount >= 3) return false // 最多重试3次
        if (timeToTarget < 10000L) return false // 剩余时间不足10秒不重试

        // 根据错误类型决定是否重试
        return when {
            error?.contains("网络") == true -> true // 网络错误可重试
            error?.contains("临时") == true -> true // 临时错误可重试
            error?.contains("保护") == true -> false // 保护状态不重试，等保护结束
            else -> retryCount < 2 // 其他错误最多重试2次
        }
    }
}

/**
 * 能量球蹲点管理器（精确时机版）
 *
 * 单一职责：精确管理能量球的蹲点时机
 * 核心原则：
 * 1. 无保护时：严格按能量球成熟时间收取
 * 2. 有保护时：等到保护结束后立即收取
 * 3. 不提前收取：避免无效请求
 * 4. 精确时机：确保在正确的时间点执行收取
 *
 * @author Sesame-AG
 */
object EnergyWaitingManager {
    private const val TAG = "EnergyWaitingManager"
    private const val FAILED_BUBBLE_STALE_GRACE_MS = 2 * 60 * 1000L
    private const val FOREST_WAITING_DEDUPE_PREFIX = "forest_waiting_"
    private const val FOREST_WAITING_TOLERANCE_MS = 60 * 60 * 1000L
    private const val MAX_PERSISTENT_WAITING_ALARMS = 16
    const val WAITING_PAUSED_COLLECT_DISABLED = "收集能量开关关闭，暂停蹲点收取"
    const val PERSISTENT_CHILD_KIND = "forest_energy_waiting"

    enum class PersistentTriggerResult {
        HANDLED,
        DEFERRED,
        FAILED
    }

    /**
     * 等待任务数据类
     */
    data class WaitingTask(
        val userId: String,
        val userName: String,
        val bubbleId: Long,
        val produceTime: Long,
        val fromTag: String,
        val sessionEpoch: Long = 0L,
        val retryCount: Int = 0,
        val maxRetries: Int = 3,
        val shieldEndTime: Long = 0, // 保护罩结束时间
        val bombEndTime: Long = 0     // 炸弹卡结束时间
    ) {
        val taskId: String = "${userId}_${bubbleId}"

        fun withRetry(): WaitingTask = this.copy(retryCount = retryCount + 1)

        /**
         * 检查是否是自己的账号
         */
        fun isSelf(): Boolean {
            return userId == io.github.aoguai.sesameag.util.maps.UserMap.currentUid
        }

        /**
         * 检查是否来自森林 PK 榜。
         */
        fun isPkContest(): Boolean {
            return userName.startsWith("PK榜好友|") || fromTag.contains("PK榜")
        }

        /**
         * 检查是否有保护（保护罩或炸弹卡）
         */
        fun hasProtection(currentTime: Long = System.currentTimeMillis()): Boolean {
            return shieldEndTime > currentTime || bombEndTime > currentTime
        }

        /**
         * 获取保护结束时间（取最晚的时间）
         */
        fun getProtectionEndTime(): Long {
            return maxOf(shieldEndTime, bombEndTime)
        }

        /**
         * 获取用户类型标签（用于日志）
         */
        fun getUserTypeTag(): String {
            return if (isSelf()) "⭐️主号|" else "好友|"
        }
    }

    // 蹲点任务存储
    private val waitingTasks = ConcurrentHashMap<String, WaitingTask>()
    private val waitingJobs = ConcurrentHashMap<String, Job>()

    // 智能重试策略
    private val smartRetryStrategy = SmartRetryStrategy()

    // 协程作用域
    private val managerScope = CoroutineScope(
        Dispatchers.Default +
                SupervisorJob() +
                CoroutineName("PreciseEnergyWaitingManager")
    )

    // 互斥锁，防止并发操作
    private val taskMutex = Mutex()

    // 最后执行时间，用于间隔控制
    private val lastExecuteTime = AtomicLong(0)

    // 最小间隔时间（毫秒） - 精确蹲点模式，快速收取
    private const val MIN_INTERVAL_MS = 500L  // 最小0.5秒（精确蹲点模式）
    private const val MAX_INTERVAL_MS = 1500L // 最大1.5秒（精确蹲点模式）

    // 最大等待时间（毫秒） - 8小时
    private const val MAX_WAIT_TIME_MS = 8 * 60 * 60 * 1000L

    // 基础检查间隔（毫秒）
    private const val BASE_CHECK_INTERVAL_MS = 30000L // 30秒检查一次
    private const val LONG_WAIT_SCHEDULE_THRESHOLD_MS = 2 * 60 * 1000L

    // 精确时机计算 - 能量成熟或保护结束后立即收取
    private fun calculatePreciseCollectTime(task: WaitingTask): Long {
        // 自己的账号：不考虑保护罩，直接在能量成熟时收取
        if (task.isSelf()) {
            return task.produceTime
        }

        // 好友账号：考虑保护罩
        val protectionEndTime = task.getProtectionEndTime()

        return maxOf(task.produceTime, protectionEndTime)
    }

    // 获取清理任务间隔 - 固定间隔清理过期任务
    private fun getCleanupInterval(): Long {
        return BASE_CHECK_INTERVAL_MS // 30秒清理一次
    }

    // 能量收取回调
    private var energyCollectCallback: EnergyCollectCallback? = null

    @Volatile
    private var restoredSessionEpoch: Long = 0L

    private fun appContext(): Context? {
        return ApplicationHook.appContext
    }

    private fun currentOwnerUserId(): String {
        return AccountSessionCoordinator.currentUserId()?.takeIf { it.isNotBlank() }
            ?: UserMap.currentUid?.takeIf { it.isNotBlank() }
            ?: "default"
    }

    private fun currentSessionEpoch(): Long {
        return AccountSessionCoordinator.currentSession()?.sessionEpoch ?: 0L
    }

    private fun isRunSessionCurrent(ownerUserId: String, sessionEpoch: Long): Boolean {
        return ownerUserId.isNotBlank() &&
            sessionEpoch > 0L &&
            AccountSessionCoordinator.isCurrentSession(ownerUserId, sessionEpoch)
    }

    private fun forestWaitingDedupeKey(taskId: String): String {
        return FOREST_WAITING_DEDUPE_PREFIX + currentOwnerUserId() + "::" + taskId
    }

    private fun taskIdFromForestWaitingDedupeKey(dedupeKey: String): String {
        val body = dedupeKey.removePrefix(FOREST_WAITING_DEDUPE_PREFIX)
        return body.substringAfter("::", body)
    }

    private fun isReadyForWaitingExecution(): Boolean {
        val session = AccountSessionCoordinator.currentSession() ?: return false
        return energyCollectCallback != null &&
            session.workflowAllowed &&
            session.userId.isNotBlank()
    }

    private fun isWaitingCollectionAllowed(): Boolean {
        return energyCollectCallback?.canRunWaitingCollection() == true
    }

    private fun logWaitingCollectionPaused(source: String, task: WaitingTask? = null) {
        val taskText = task?.let { "[${it.taskId}][${it.getUserTypeTag()}${it.userName}]" } ?: ""
        Log.forest("森林蹲点暂停$taskText：收集能量未开启，保留任务等待下次收能量链路 source=$source")
    }

    private fun createPersistentSchedule(task: WaitingTask): PersistentSchedule? {
        val ownerUserId = currentOwnerUserId()
        val sessionEpoch = task.sessionEpoch
        if (!isRunSessionCurrent(ownerUserId, sessionEpoch)) {
            Log.forest("森林蹲点持久调度会话无效，跳过注册[${task.taskId}][owner=$ownerUserId][session=$sessionEpoch]")
            return null
        }
        val payload = JSONObject().apply {
            put("child_kind", PERSISTENT_CHILD_KIND)
            put("task_id", task.taskId)
            put("user_id", task.userId)
            put("user_name", task.userName)
            put("bubble_id", task.bubbleId)
            put("produce_time", task.produceTime)
            put("from_tag", task.fromTag)
            put("retry_count", task.retryCount)
            put("max_retries", task.maxRetries)
            put("shield_end_time", task.shieldEndTime)
            put("bomb_end_time", task.bombEndTime)
            put("owner_user_id", ownerUserId)
            put("session_epoch", sessionEpoch)
            put("launch_target", true)
        }
        return PersistentSchedule(
            name = "森林蹲点:${task.taskId}",
            kind = PersistentScheduleKind.MODULE_CHILD,
            triggerAtMs = calculatePreciseCollectTime(task),
            toleranceMs = FOREST_WAITING_TOLERANCE_MS,
            dedupeKey = forestWaitingDedupeKey(task.taskId),
            payloadJson = payload.toString(),
            ownerUserId = ownerUserId,
            sessionEpoch = sessionEpoch
        )
    }

    private fun syncPersistentWaitingSchedule(task: WaitingTask) {
        val context = appContext() ?: return
        val triggerAt = calculatePreciseCollectTime(task)
        val now = System.currentTimeMillis()
        if (triggerAt <= now) {
            cancelPersistentWaitingSchedule(task.taskId)
            return
        }
        try {
            val schedule = createPersistentSchedule(task) ?: run {
                cancelPersistentWaitingSchedule(task.taskId)
                return
            }
            PersistentScheduleRegistry.upsert(context, schedule)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "注册森林蹲点系统闹钟失败[${task.taskId}]", t)
        }
    }

    private fun cancelPersistentWaitingSchedule(taskId: String) {
        val context = appContext()
        runCatching {
            PersistentScheduleRegistry.removeByDedupeKey(context, forestWaitingDedupeKey(taskId))
            PersistentScheduleRegistry.list()
                .filter { schedule ->
                    schedule.kind == PersistentScheduleKind.MODULE_CHILD &&
                        schedule.dedupeKey.startsWith(FOREST_WAITING_DEDUPE_PREFIX) &&
                        taskIdFromForestWaitingDedupeKey(schedule.dedupeKey) == taskId &&
                        schedule.ownerUserId == currentOwnerUserId()
                }
                .forEach { schedule ->
                    PersistentScheduleRegistry.removeByDedupeKey(context, schedule.dedupeKey)
                }
        }.onFailure {
            Log.printStackTrace(TAG, "取消森林蹲点系统闹钟失败[$taskId]", it)
        }
    }

    private fun syncPersistentWaitingSchedules() {
        val context = appContext() ?: return
        val selectedTasks = waitingTasks.values
            .filter { calculatePreciseCollectTime(it) > System.currentTimeMillis() }
            .sortedBy { calculatePreciseCollectTime(it) }
            .take(MAX_PERSISTENT_WAITING_ALARMS)
        val selectedTaskIds = selectedTasks.mapTo(mutableSetOf()) { it.taskId }
        val currentOwnerId = currentOwnerUserId()
        try {
            PersistentScheduleRegistry.list()
                .filter { schedule ->
                    schedule.kind == PersistentScheduleKind.MODULE_CHILD &&
                        schedule.dedupeKey.startsWith(FOREST_WAITING_DEDUPE_PREFIX) &&
                        schedule.ownerUserId == currentOwnerId
                }
                .forEach { schedule ->
                    val taskId = taskIdFromForestWaitingDedupeKey(schedule.dedupeKey)
                    if (taskId !in selectedTaskIds ||
                        schedule.state != PersistentScheduleState.SCHEDULED ||
                        schedule.dedupeKey != forestWaitingDedupeKey(taskId)
                    ) {
                        PersistentScheduleRegistry.removeByDedupeKey(context, schedule.dedupeKey)
                    }
                }
            selectedTasks.forEach { syncPersistentWaitingSchedule(it) }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "同步森林蹲点系统闹钟失败", t)
        }
    }

    private fun removeWaitingTask(taskId: String): WaitingTask? {
        waitingJobs.remove(taskId)?.cancel()
        val removed = waitingTasks.remove(taskId)
        cancelPersistentWaitingSchedule(taskId)
        if (removed != null) {
            syncPersistentWaitingSchedules()
        }
        return removed
    }

    fun triggerPersistentWaitingTask(taskId: String, payloadJson: String, source: String): PersistentTriggerResult {
        if (taskId.isBlank()) return PersistentTriggerResult.FAILED
        val task = waitingTasks[taskId]
        if (task == null) {
            Log.forest("森林蹲点持久任务到期但内存任务不存在[$taskId]，尝试恢复")
            restorePersistedTaskById(taskId, payloadJson, source)
            return PersistentTriggerResult.DEFERRED
        }
        if (!isReadyForWaitingExecution()) {
            Log.forest("森林蹲点持久任务[$taskId]等待回调/账号就绪 source=$source")
            return PersistentTriggerResult.DEFERRED
        }
        if (!isWaitingCollectionAllowed()) {
            logWaitingCollectionPaused("persistent:$source", task)
            return PersistentTriggerResult.DEFERRED
        }
        startPreciseWaitingCoroutine(task)
        Log.forest("森林蹲点持久任务触发[$taskId] source=$source")
        return PersistentTriggerResult.HANDLED
    }

    private fun restorePersistedTaskById(taskId: String, payloadJson: String, source: String) {
        managerScope.launch {
            taskMutex.withLock {
                if (waitingTasks.containsKey(taskId)) return@withLock
                val task = EnergyWaitingPersistence.loadTasks().firstOrNull { it.taskId == taskId }
                    ?: restoreTaskFromPayload(payloadJson)
                    ?: restoreTaskFromPersistentSchedule(taskId)
                if (task == null) {
                    cancelPersistentWaitingSchedule(taskId)
                    Log.forest("森林蹲点持久任务[$taskId]未找到持久化记录，已取消系统闹钟")
                    return@withLock
                }
                waitingTasks[task.taskId] = task
                EnergyWaitingPersistence.saveTasks(waitingTasks)
                if (isReadyForWaitingExecution()) {
                    if (isWaitingCollectionAllowed()) {
                        startPreciseWaitingCoroutine(task)
                        Log.forest("森林蹲点持久任务[$taskId]已从持久化恢复并触发 source=$source")
                    } else {
                        logWaitingCollectionPaused("restore:$source", task)
                    }
                } else {
                    Log.forest("森林蹲点持久任务[$taskId]已恢复，等待回调/账号就绪 source=$source")
                }
            }
        }
    }

    private fun restoreTaskFromPayload(payloadJson: String): WaitingTask? {
        return parseWaitingTaskPayload(payloadJson)
    }

    private fun restoreTaskFromPersistentSchedule(taskId: String): WaitingTask? {
        return try {
            val schedule = PersistentScheduleRegistry.list().firstOrNull {
                it.dedupeKey == forestWaitingDedupeKey(taskId)
            } ?: return null
            parseWaitingTaskPayload(schedule.payloadJson)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "从持久调度恢复森林蹲点任务失败[$taskId]", t)
            null
        }
    }

    private fun parseWaitingTaskPayload(payloadJson: String): WaitingTask? {
        return try {
            val payload = JSONObject(payloadJson.ifBlank { "{}" })
            val ownerUserId = payload.optString("owner_user_id").trim()
            val userId = payload.optString("user_id").takeIf { it.isNotBlank() } ?: return null
            val bubbleId = payload.optLong("bubble_id", 0L).takeIf { it > 0L } ?: return null
            val produceTime = payload.optLong("produce_time", 0L).takeIf { it > 0L } ?: return null
            val sessionEpoch = payload.optLong("session_epoch", 0L)
            if (!isRunSessionCurrent(ownerUserId, sessionEpoch)) {
                return null
            }
            WaitingTask(
                userId = userId,
                userName = payload.optString("user_name", "未知用户"),
                bubbleId = bubbleId,
                produceTime = produceTime,
                fromTag = payload.optString("from_tag", "蹲点收取"),
                sessionEpoch = sessionEpoch,
                retryCount = payload.optInt("retry_count", 0),
                maxRetries = payload.optInt("max_retries", 3),
                shieldEndTime = payload.optLong("shield_end_time", 0L),
                bombEndTime = payload.optLong("bomb_end_time", 0L)
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "解析森林蹲点持久任务 payload 失败", t)
            null
        }
    }

    /**
     * 添加蹲点任务（带重复检查优化和智能保护判断）
     *
     * @param userId 用户ID
     * @param userName 用户名称
     * @param bubbleId 能量球ID
     * @param produceTime 能量球成熟时间
     * @param fromTag 来源标记
     * @param shieldEndTime 保护罩结束时间（可选，如果为0则会自动获取）
     * @param bombEndTime 炸弹卡结束时间（可选，如果为0则会自动获取）
     * @param userHomeObj 用户主页数据（可选，用于自动获取保护时间）
     */
    fun addWaitingTask(
        userId: String,
        userName: String,
        bubbleId: Long,
        produceTime: Long,
        fromTag: String = "waiting",
        shieldEndTime: Long = 0,
        bombEndTime: Long = 0,
        userHomeObj: JSONObject? = null
    ) {
        managerScope.launch {
            taskMutex.withLock {
                val currentTime = System.currentTimeMillis()
                val taskId = "${userId}_${bubbleId}"

                // 检查是否已存在相同的任务
                val existingTask = waitingTasks[taskId]
                if (existingTask != null) {
                    // 如果已存在且时间相同，跳过添加
                    if (existingTask.produceTime == produceTime) {
                         Log.forest("蹲点任务[$taskId][userId=$userId,bubbleId=$bubbleId,from=$fromTag]已存在且时间相同，跳过重复添加")
                        return@withLock
                    }
                    // 如果时间不同，记录更新信息
                     Log.forest("更新蹲点任务[$taskId][userId=$userId,bubbleId=$bubbleId,from=$fromTag]：时间从[${TimeUtil.getCommonDate(existingTask.produceTime)}]更新为[${TimeUtil.getCommonDate(produceTime)}]")
                }

                // 检查是否是自己的账号
                val isSelf = userId == io.github.aoguai.sesameag.util.maps.UserMap.currentUid

                // 智能获取保护时间（自己的账号不需要保护时间）
                var finalShieldEndTime = shieldEndTime
                var finalBombEndTime = bombEndTime

                if (userHomeObj != null && !isSelf) {
                    // 只为好友账号获取保护时间
                    finalShieldEndTime = ForestUtil.getShieldEndTime(userHomeObj)
                    finalBombEndTime = ForestUtil.getBombCardEndTime(userHomeObj)

                    // 智能判断是否应该跳过蹲点（好友账号）
                    if (ForestUtil.shouldSkipWaitingDueToProtection(userHomeObj, produceTime)) {
                        val protectionEndTime = ForestUtil.getProtectionEndTime(userHomeObj)
                        val timeDifference = protectionEndTime - produceTime
                        val formattedTimeDifference = formatTime(timeDifference)
                        Log.forest("智能跳过蹲点：[好友|$userName]球[$bubbleId][taskId=$taskId]的保护罩比能量球晚到期${formattedTimeDifference}，无法收取，已跳过。"
                        )
                        // 移除无效的蹲点任务
                        removeWaitingTask(taskId)
                        EnergyWaitingPersistence.saveTasks(waitingTasks)
                        return@withLock
                    }
                } else if (isSelf) {
                    // 自己的账号：不获取保护时间，直接设置为0
                    finalShieldEndTime = 0L
                    finalBombEndTime = 0L
                     Log.forest("⭐️ [主号|$userName]不检查保护罩，到时间直接收取")
                }

                // 注释：原本的时间有效性检查已删除
                // 因为 addWaitingTask 只在 produceTime > serverTime 时被调用
                // 所以 produceTime <= currentTime 的情况几乎不会发生

                // 检查等待时间是否过长
                val waitTime = produceTime - currentTime
                if (waitTime > MAX_WAIT_TIME_MS) {
                    // 移除过长的任务
                    removeWaitingTask(taskId)
                    Log.forest("能量球[$bubbleId][taskId=$taskId][user=$userName]等待时间过长(${waitTime / 1000 / 60}分钟 > ${MAX_WAIT_TIME_MS / 1000 / 60}分钟)，本次不加入蹲点队列，当前有效任务${waitingTasks.size}个"
                    )
                    EnergyWaitingPersistence.saveTasks(waitingTasks)
                    return@withLock
                }


                val task = WaitingTask(
                    userId = userId,
                    userName = userName,
                    bubbleId = bubbleId,
                    produceTime = produceTime,
                    fromTag = fromTag,
                    sessionEpoch = currentSessionEpoch(),
                    shieldEndTime = finalShieldEndTime,
                    bombEndTime = finalBombEndTime
                )

                // 移除旧任务（如果存在），避免同一 taskId 同时存在多个协程流程
                removeWaitingTask(taskId)

                // 添加/覆盖任务
                waitingTasks[taskId] = task

                val actionText = if (existingTask != null) "更新" else "添加"
                val waitTimeMinutes = (produceTime - currentTime) / 1000 / 60

                // 自己的账号：不显示保护罩信息
                // 好友账号：如果有保护罩，显示保护罩到期时间
                val protectionStatus = if (!task.isSelf()) {
                    val protectionEndTime = task.getProtectionEndTime()
                    if (protectionEndTime > currentTime) {
                        " 保护罩到期：" + TimeUtil.getCommonDate(protectionEndTime)
                    } else {
                        ""
                    }
                } else {
                    ""
                }

                Log.forest("${actionText}蹲点：[${task.getUserTypeTag()}${fromTag}|${userName}]球[${bubbleId}]#任务[$taskId]在[${TimeUtil.getCommonDate(produceTime)}]成熟(等待${waitTimeMinutes}分钟)${protectionStatus}"
                )

                // 保存到持久化存储
                EnergyWaitingPersistence.saveTasks(waitingTasks)
                syncPersistentWaitingSchedules()

                // 启动精确蹲点协程
                startPreciseWaitingCoroutine(task)
            }
        }
    }

    /**
     * 查询指定能量球的蹲点任务是否已存在。
     * 可选地校验成熟时间，用于过滤恢复后的重复添加请求。
     */
    fun hasWaitingTask(userId: String, bubbleId: Long, produceTime: Long? = null): Boolean {
        val existingTask = waitingTasks["${userId}_${bubbleId}"] ?: return false
        return produceTime == null || existingTask.produceTime == produceTime
    }

    /**
     * 启动精确蹲点协程
     * 核心原则：不提前收取，严格按时机执行
     */
    private fun startPreciseWaitingCoroutine(task: WaitingTask) {
        val ownerUserId = currentOwnerUserId()
        val sessionEpoch = task.sessionEpoch
        if (!isRunSessionCurrent(ownerUserId, sessionEpoch)) {
            Log.forest("森林蹲点任务[${task.taskId}]会话无效，跳过启动[user=$ownerUserId][session=$sessionEpoch]")
            return
        }
        if (!isWaitingCollectionAllowed()) {
            logWaitingCollectionPaused("start", task)
            return
        }
        waitingJobs.remove(task.taskId)?.cancel()
        val job = managerScope.launch {
            try {
                if (!isRunSessionCurrent(ownerUserId, sessionEpoch)) {
                    return@launch
                }
                if (!isWaitingCollectionAllowed()) {
                    logWaitingCollectionPaused("run", task)
                    return@launch
                }
                val currentTime = System.currentTimeMillis()
                val preciseCollectTime = calculatePreciseCollectTime(task)
                val waitTime = preciseCollectTime - currentTime

                if (waitTime > 0) {
                    // 需要等待的任务
                    val protectionInfo = if (task.isSelf()) {
                        "能量成熟"
                    } else if (task.hasProtection(currentTime)) {
                        "保护结束"
                    } else {
                        "能量成熟"
                    }
                    val waitMinutes = waitTime / 1000 / 60
                    Log.forest("🕐 蹲点[${task.getUserTypeTag()}${task.userName}]等待${waitMinutes}分钟(${protectionInfo}→${TimeUtil.getCommonDate(preciseCollectTime)})")

                    // 倒计时前2分钟验证策略
                    val twoMinutes = 2 * 60 * 1000L
                    var remainingWait = waitTime

                    // 阶段1：长等待先交给统一调度；最后2分钟保留原有短周期检查。
                    if (waitTime > twoMinutes && !task.isSelf()) {
                        val waitBeforeValidation = waitTime - twoMinutes
                         Log.forest("蹲点[${task.getUserTypeTag()}${task.userName}]将在${(waitBeforeValidation/1000/60).toInt()}分钟后验证")
                        scheduleWaitingDelay(waitBeforeValidation, "ForestWaitingValidate:${task.taskId}")

                        if (!isRunSessionCurrent(ownerUserId, sessionEpoch)) {
                            return@launch
                        }
                        // 检查任务是否被移除
                        if (!waitingTasks.containsKey(task.taskId)) {
                            Log.forest("⚠️ 蹲点[${task.getUserTypeTag()}${task.userName}]已被移除")
                            return@launch
                        }

                        // 倒计时2分钟验证：查询好友保护罩状态
                        if (!isWaitingCollectionAllowed()) {
                            logWaitingCollectionPaused("validate", task)
                            return@launch
                        }
                        Log.forest("🔍 倒计时2分钟验证[${task.getUserTypeTag()}${task.userName}]保护罩状态...")
                        try {
                            val safeUserId = FriendGuard.normalizeUserId(task.userId)
                            if (safeUserId == null) {
                                Log.forest("❌ 验证失败[${task.getUserTypeTag()}${task.userName}]：userId无效，取消蹲点")
                                removeWaitingTask(task.taskId)
                                EnergyWaitingPersistence.saveTasks(waitingTasks)
                                return@launch
                            }
                            if (task.isPkContest()) {
                                if (FriendGuard.isSelf(safeUserId)) {
                                    Log.forest("❌ 验证失败[${task.getUserTypeTag()}${task.userName}]：PK榜返回自己账号，取消蹲点")
                                    removeWaitingTask(task.taskId)
                                    EnergyWaitingPersistence.saveTasks(waitingTasks)
                                    return@launch
                                }
                            } else if (FriendGuard.shouldSkipFriend(safeUserId, TAG, "验证蚂蚁森林蹲点好友")) {
                                Log.forest("❌ 验证失败[${task.getUserTypeTag()}${task.userName}]：好友关系无效，取消蹲点")
                                removeWaitingTask(task.taskId)
                                EnergyWaitingPersistence.saveTasks(waitingTasks)
                                return@launch
                            }
                            val userHomeResponse = AntForestRpcCall.queryFriendHomePage(
                                safeUserId,
                                if (task.isPkContest()) "PKContest" else null
                            )
                            if (!userHomeResponse.isNullOrEmpty()) {
                                val userHomeObj = JSONObject(userHomeResponse)
                                if (ForestUtil.shouldSkipWaitingDueToProtection(userHomeObj, task.produceTime)) {
                                    // 有保护罩覆盖，取消蹲点
                                    val shieldEnd = ForestUtil.getShieldEndTime(userHomeObj)
                                    val bombEnd = ForestUtil.getBombCardEndTime(userHomeObj)
                                    val protectionEnd = maxOf(shieldEnd, bombEnd)
                                    val coverMinutes = (protectionEnd - task.produceTime) / 1000 / 60
                                    Log.forest("❌ 验证失败[${task.getUserTypeTag()}${task.userName}]球[${task.bubbleId}]：保护罩覆盖${coverMinutes}分钟，取消蹲点")
                                    removeWaitingTask(task.taskId)
                                    EnergyWaitingPersistence.saveTasks(waitingTasks)
                                    return@launch
                                } else {
                                    // 无保护罩，继续等待
                                    Log.forest("✅ 验证通过[${task.getUserTypeTag()}${task.userName}]：无保护罩，继续等待2分钟")
                                }
                            } else {
                                 Log.forest("验证[${task.getUserTypeTag()}${task.userName}]：无法获取主页信息，继续执行")
                            }
                        } catch (e: Exception) {
                             Log.forest("验证[${task.getUserTypeTag()}${task.userName}]出错: ${e.message}，继续执行")
                        }

                        // 更新剩余等待时间为2分钟
                        remainingWait = twoMinutes
                    } else if (waitTime > twoMinutes) {
                        val waitBeforeFinalCheck = waitTime - twoMinutes
                        scheduleWaitingDelay(waitBeforeFinalCheck, "ForestWaitingFinal:${task.taskId}")
                        if (!isRunSessionCurrent(ownerUserId, sessionEpoch)) {
                            return@launch
                        }
                        if (!waitingTasks.containsKey(task.taskId)) {
                            Log.forest("⚠️ 蹲点[${task.getUserTypeTag()}${task.userName}]已被移除")
                            return@launch
                        }
                        remainingWait = twoMinutes
                    }

                    // 阶段2：最后阶段等待（主号全程或好友验证后的2分钟）
                    val checkInterval = 30000L // 30秒检查一次
                    while (remainingWait > 0 && isActive) {
                        val currentWait = minOf(remainingWait, checkInterval)
                        delay(currentWait)
                        remainingWait -= currentWait

                        if (!isRunSessionCurrent(ownerUserId, sessionEpoch)) {
                            return@launch
                        }
                        // 检查任务是否仍然有效
                        if (!waitingTasks.containsKey(task.taskId)) {
                            Log.forest("⚠️ 蹲点[${task.getUserTypeTag()}${task.userName}]已被移除")
                            return@launch
                        }

                        // 仅在最后1分钟显示倒计时
                        if (remainingWait in 1..60000L) {
                             Log.forest("蹲点[${task.getUserTypeTag()}${task.userName}]倒计时${remainingWait/1000}秒")
                        }
                    }

                    // 等待完成，最终检查任务有效性
                    if (!isRunSessionCurrent(ownerUserId, sessionEpoch)) {
                        return@launch
                    }
                    if (!waitingTasks.containsKey(task.taskId)) {
                        Log.forest("⚠️ 蹲点[${task.getUserTypeTag()}${task.userName}]等待过程中被移除")
                        return@launch
                    }

                    Log.forest("✅ 蹲点[${task.getUserTypeTag()}${task.userName}]等待完成，开始收取")
                } else {
                    // 已经到时间的任务，立即执行
                    val overdueMinutes = (-waitTime) / 1000 / 60
                    if (overdueMinutes > 2) {
                        // 超时超过2分钟，记录警告
                        Log.forest("⚡ 蹲点[${task.getUserTypeTag()}${task.userName}]已超时${overdueMinutes}分钟，立即收取")
                    } else {
                        // 刚到时间或刚超时，正常执行
                        Log.forest("✅ 蹲点[${task.getUserTypeTag()}${task.userName}]时间已到，立即收取")
                    }
                }

                // 执行收取任务
                if (!isRunSessionCurrent(ownerUserId, sessionEpoch)) {
                    return@launch
                }
                if (!isWaitingCollectionAllowed()) {
                    logWaitingCollectionPaused("execute", task)
                    return@launch
                }
                executePreciseWaitingTask(task)

            } catch (_: CancellationException) {
                 Log.forest("精确蹲点任务[${task.taskId}]被取消")
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "精确蹲点任务[${task.taskId}]执行异常", e)

                // 精确重试逻辑
                val currentTime = System.currentTimeMillis()
                val timeToTarget = calculatePreciseCollectTime(task) - currentTime

                if (smartRetryStrategy.shouldRetry(task.retryCount, e.message, timeToTarget)) {
                    val retryTask = task.withRetry()
                    waitingTasks[task.taskId] = retryTask
                    syncPersistentWaitingSchedules()

                    // 重试延迟
                    val retryDelay = smartRetryStrategy.getRetryDelay(task.retryCount, e.message)
                     Log.forest("精确蹲点任务[${task.taskId}]将在${retryDelay/1000}秒后重试")
                    delay(retryDelay)
                    startPreciseWaitingCoroutine(retryTask)
                } else {
                    Log.error(TAG, "精确蹲点任务[${task.taskId}]不满足重试条件，放弃")
                    removeWaitingTask(task.taskId)
                    EnergyWaitingPersistence.saveTasks(waitingTasks)
                }
            } finally {
                val runningJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                if (runningJob != null) {
                    waitingJobs.remove(task.taskId, runningJob)
                } else {
                    waitingJobs.remove(task.taskId)
                }
            }
        }
        waitingJobs[task.taskId] = job
    }

    private suspend fun scheduleWaitingDelay(delayMillis: Long, taskName: String) {
        val finalDelay = delayMillis.coerceAtLeast(0L)
        if (finalDelay < LONG_WAIT_SCHEDULE_THRESHOLD_MS) {
            delay(finalDelay)
            return
        }

        if (BaseModel.timedTaskModel.value != BaseModel.TimedTaskModel.PROGRAM) {
            delay(finalDelay)
            return
        }

        val schedulerId = UnifiedScheduler.scheduleKeepAlive(finalDelay, taskName)
        try {
            delay(finalDelay)
        } finally {
            UnifiedScheduler.cancelTask(schedulerId)
        }
    }

    /**
     * 执行精确蹲点收取任务
     * 核心原则：在正确的时机执行，不提前不延后
     */
    @SuppressLint("SimpleDateFormat")
    private suspend fun executePreciseWaitingTask(task: WaitingTask) {
        taskMutex.withLock {
            try {
                // 检查任务是否仍然有效
                if (!waitingTasks.containsKey(task.taskId)) {
                     Log.forest("精确蹲点任务[${task.taskId}]已被移除，跳过执行")
                    return@withLock
                }
                if (!isWaitingCollectionAllowed()) {
                    logWaitingCollectionPaused("precise", task)
                    return@withLock
                }

                // 随机间隔控制：防止频繁请求，使用随机间隔更自然
                val currentTime = System.currentTimeMillis()
                val lastExecute = lastExecuteTime.get()

                if (lastExecute == 0L) {
                    // 第一次执行，立即收取
                    Log.forest("⚡ 首次蹲点收取，立即执行任务[${task.taskId}]")
                } else {
                    // 非首次执行，应用随机间隔控制
                    val timeSinceLastExecute = currentTime - lastExecute

                    // 生成随机间隔时间（0.5-1.5秒，精确蹲点模式）
                    val randomIntervalMs = Random.nextLong(MIN_INTERVAL_MS, MAX_INTERVAL_MS + 1)

                    if (timeSinceLastExecute < randomIntervalMs) {
                        val delayTime = randomIntervalMs - timeSinceLastExecute
                        Log.forest("🎲 随机间隔控制：延迟${delayTime / 1000}秒执行蹲点任务[${task.taskId}]（随机间隔${randomIntervalMs/1000}秒）")
                        delay(delayTime)
                    } else {
                         Log.forest("⚡ 无需延迟：距离上次执行已超过${timeSinceLastExecute/1000}秒")
                    }
                }

                // 更新最后执行时间
                lastExecuteTime.set(System.currentTimeMillis())
                // 验证执行时机是否正确
                val actualTime = System.currentTimeMillis()
                val energyTimeRemain = (task.produceTime - actualTime) / 1000
                val isEnergyMature = task.produceTime <= actualTime
                // 自己的账号：只检查能量成熟时间，不检查保护
                // 好友账号：检查能量成熟和保护结束
                val protectionEndTime = if (task.isSelf()) 0L else task.getProtectionEndTime()
                val isProtectionEnd = if (task.isSelf()) true else protectionEndTime <= actualTime
                if (energyTimeRemain > 300) { // 如果还有超过5分钟才成熟，直接跳过
                     Log.forest("⚠️ 能量距离成熟还有${energyTimeRemain}秒，时机过早，跳过本次收取")
                    return@withLock
                }
                // 判断是否需要详细日志（未成熟或刚成熟2分钟内）
                val needDetailLog = !isEnergyMature || (!task.isSelf() && !isProtectionEnd) || energyTimeRemain > -120
                if (needDetailLog) {
                    // 详细调试日志：用于未成熟或刚成熟的任务
                    Log.forest("🔍 蹲点任务[${task.getUserTypeTag()}${task.userName}]时机检查详情：")
                    Log.forest("  系统当前时间: ${System.currentTimeMillis()} (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())})")
                    Log.forest("  实际执行时间: $actualTime (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(actualTime))})")
                    Log.forest("  能量成熟时间: ${task.produceTime} (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(task.produceTime))})")
                    if (!task.isSelf()) {
                        Log.forest("  保护结束时间: $protectionEndTime (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(protectionEndTime))})")
                    }
                    Log.forest("  时间差异: 系统时间与执行时间差${System.currentTimeMillis() - actualTime}ms")
                    Log.forest("  能量剩余时间: ${energyTimeRemain}秒")
                    Log.forest("  能量是否成熟: $isEnergyMature")
                    if (!task.isSelf()) {
                        Log.forest("  保护是否结束: $isProtectionEnd")
                    }
                } else {
                    // 简化日志：用于已成熟超过2分钟的任务
                    val matureTime = (-energyTimeRemain) / 60 // 成熟了多少分钟
                    Log.forest("⚡ 蹲点任务[${task.getUserTypeTag()}${task.userName}]已成熟${matureTime.toInt()}分钟，直接收取")
                }

                // 最终时机检查
                if (!isEnergyMature || !isProtectionEnd) {
                    val additionalWait = if (task.isSelf()) {
                        // 自己的账号：只等待能量成熟
                        task.produceTime - actualTime
                    } else {
                        // 好友账号：等待能量成熟和保护结束的较晚时间
                        max(protectionEndTime - actualTime, task.produceTime - actualTime)
                    }

                    if (additionalWait in 1..<1800000L) { // 最多额外等待30分钟
                        val waitReason = if (!isEnergyMature) "能量未成熟" else ""
                        val protectionReason = if (!task.isSelf() && !isProtectionEnd) "保护未结束" else ""
                        val combinedReason = listOf(waitReason, protectionReason).filter { it.isNotEmpty() }.joinToString("且")

                        Log.forest("⏳ 最终时机检查：等待${additionalWait/1000}秒 ($combinedReason)")
                        delay(additionalWait)

                        // 等待后重新检查
                        val newActualTime = System.currentTimeMillis()
                        val newIsEnergyMature = task.produceTime <= newActualTime
                        if (task.isSelf()) {
                            Log.forest("⏳ 等待完成：能量成熟[$newIsEnergyMature]")
                        } else {
                            val newIsProtectionEnd = task.getProtectionEndTime() <= newActualTime
                            Log.forest("⏳ 等待完成：能量成熟[$newIsEnergyMature] 保护结束[$newIsProtectionEnd]")
                        }
                    } else if (additionalWait > 1800000L) {
                        Log.error(TAG, "⚠️ 等待时间过长(${additionalWait/60000}分钟)，跳过收取")
                        return@withLock
                    }
                }

                // 执行收取
                val startTime = System.currentTimeMillis()
                val result = collectEnergyFromWaiting(task)
                val executeTime = System.currentTimeMillis() - startTime
                if (result.paused) {
                    Log.forest("⏸ 蹲点收取[${task.getUserTypeTag()}${task.userName}]暂停：${result.message.ifBlank { WAITING_PAUSED_COLLECT_DISABLED }}")
                    syncPersistentWaitingSchedules()
                    return@withLock
                }

                // 更新用户模式数据
                UserEnergyPatternManager.updateUserPattern(task.userId, result, executeTime)
                // 处理结果

                if (result.success) {
                    if (result.energyCount > 0) {
                        Log.forest("✅ 蹲点收取[${task.getUserTypeTag()}${task.userName}]成功${result.energyCount}g(耗时${executeTime}ms)")
                        removeWaitingTask(task.taskId) // 成功后移除任务
                        EnergyWaitingPersistence.saveTasks(waitingTasks) // 保存更新
                    } else {
                        Log.forest("⚠️ 蹲点收取[${task.getUserTypeTag()}${task.userName}]异常：返回0能量(${result.message})")

                        // 判断是否需要重试
                        if (task.retryCount < task.maxRetries) {
                            val retryTask = task.withRetry()
                            waitingTasks[task.taskId] = retryTask
                            syncPersistentWaitingSchedules()
                            val retryDelay = 5000L // 5秒后重试
                            Log.forest("  → 5秒后重试(${retryTask.retryCount}/${task.maxRetries})")

                            managerScope.launch {
                                delay(retryDelay)
                                startPreciseWaitingCoroutine(retryTask)
                            }
                        } else {
                            Log.forest("  → 已达最大重试次数")
                            removeWaitingTask(task.taskId)
                            EnergyWaitingPersistence.saveTasks(waitingTasks)
                        }
                    }
                } else {
                    Log.forest("❌ 蹲点收取[${task.getUserTypeTag()}${task.userName}]失败：${result.message}")

                    // 根据失败原因决定是否重试
                    val failedBubbleStale = result.allRequestedBubblesFailed &&
                        System.currentTimeMillis() - task.produceTime >= FAILED_BUBBLE_STALE_GRACE_MS
                    when {
                        failedBubbleStale -> {
                            Log.forest("  → 服务端标记目标能量球收取失败且任务已成熟超过宽限期，移除蹲点任务 failedBubbleIds=${result.failedBubbleIds}")
                            removeWaitingTask(task.taskId)
                            for (bubbleId in result.failedBubbleIds) {
                                removeWaitingTask("${task.userId}_${bubbleId}")
                            }
                            EnergyWaitingPersistence.saveTasks(waitingTasks)
                        }
                        result.hasShield || result.hasBomb -> {
                            Log.forest("  → 检测到保护罩/炸弹卡")
                            removeWaitingTask(task.taskId)
                            EnergyWaitingPersistence.saveTasks(waitingTasks) // 保存更新
                        }
                        result.message.contains("用户无可收取的能量球") -> {
                            Log.forest("  → 能量球已不存在，移除任务")
                            removeWaitingTask(task.taskId)
                            EnergyWaitingPersistence.saveTasks(waitingTasks) // 保存更新
                        }
                        result.message.contains("无法查询用户能量信息") -> {
                            Log.forest("  → 用户能量信息查询失败，移除任务")
                            removeWaitingTask(task.taskId)
                            EnergyWaitingPersistence.saveTasks(waitingTasks) // 保存更新
                        }
                        else -> {
                            // 可重试的错误，主动触发重试
                            if (task.retryCount < task.maxRetries) {
                                val retryTask = task.withRetry()
                                waitingTasks[task.taskId] = retryTask
                                syncPersistentWaitingSchedules()

                                // 根据错误类型决定重试延迟
                                val retryDelay = when {
                                    result.message.contains("网络") -> 5000L // 5秒
                                    result.message.contains("频繁") -> 10000L // 10秒
                                    else -> 5000L // 默认5秒
                                }

                                Log.forest("  → ${retryDelay/1000}秒后重试(${retryTask.retryCount}/${task.maxRetries})")

                                managerScope.launch {
                                    delay(retryDelay)
                                    if (waitingTasks.containsKey(task.taskId)) {
                                        startPreciseWaitingCoroutine(retryTask)
                                    }
                                }
                            } else {
                                Log.forest("  → 已达最大重试次数")
                                removeWaitingTask(task.taskId)
                                EnergyWaitingPersistence.saveTasks(waitingTasks)
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.printStackTrace(TAG, "执行精确蹲点任务异常", e)
                throw e
            }
        }
    }

    /**
     * 收取等待的能量（通过回调调用AntForest）
     */
    private suspend fun collectEnergyFromWaiting(task: WaitingTask): CollectResult {
        return try {
            val callback = energyCollectCallback
            if (callback != null) {
                // 通过回调调用AntForest的收取方法
                callback.collectUserEnergyForWaiting(task)
            } else {
                 Log.forest("能量收取回调未设置，跳过收取：用户[${task.userId}] 能量球[${task.bubbleId}]")
                CollectResult(
                    success = false,
                    userName = task.userName,
                    message = "回调未设置"
                )
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "收取能量失败", e)
            CollectResult(
                success = false,
                userName = task.userName,
                message = "异常：${e.message}"
            )
        }
    }

    // 类成员变量区域
    private var lastCleanTime: Long = 0 // 记录上次清理的时间
    private const val CLEAN_COOLDOWN = 30 * 60 * 1000L // 冷却时间：30分钟 (如果你想要30秒，改成 30 * 1000L)

    /**
     * 清理过期任务
     * @param enableRevalidation 是否强制执行全面验证（如果是true，将忽略冷却时间）
     */
    fun cleanExpiredTasks(enableRevalidation: Boolean = false) {
        val currentTime = System.currentTimeMillis()

        // --- 冷却检查 ---
        // 如果不是强制验证，且距离上次清理不足冷却时间，直接返回
        if (!enableRevalidation && currentTime - lastCleanTime < CLEAN_COOLDOWN) {
            // Log.verbose(TAG, "清理任务处于冷却中，跳过执行")
            return
        }

        // 更新最后清理时间（防止并在发调用）
        lastCleanTime = currentTime

        managerScope.launch {
            // 获取锁，确保线程安全
            taskMutex.withLock {
                val now = System.currentTimeMillis()

                // 1. 找出已经成熟超过2分钟但未执行的任务（僵尸任务检测）
                // 逻辑：保护期结束时间 或 产出时间 已经过去很久了，但任务还在列表中
                val matureTasks = waitingTasks.filter { (_, task) ->
                    val protectionEndTime = task.getProtectionEndTime()
                    // 取保护结束时间和产出时间中较大的一个作为“应该收取的时间”
                    val collectTime = maxOf(task.produceTime, protectionEndTime)
                    now > collectTime + 2 * 60 * 1000L // 晚了2分钟以上
                }

                // 重新触发已成熟任务（尝试唤醒僵尸任务）
                if (matureTasks.isNotEmpty()) {
                    val taskNames = matureTasks.values
                        .take(3)
                        .joinToString(",") { "${it.userName}/球[${it.bubbleId}]/任务[${it.taskId}]" }
                    val moreText = if (matureTasks.size > 3) "等${matureTasks.size}个" else ""
                    if (isWaitingCollectionAllowed()) {
                        Log.forest("🔄 重新触发蹲点：[${taskNames}${moreText}]已成熟但未执行")
                        matureTasks.forEach { (_, task) ->
                            // 重新启动倒计时协程
                            startPreciseWaitingCoroutine(task)
                        }
                    } else {
                        Log.forest("森林蹲点暂停：[${taskNames}${moreText}]已成熟但收集能量未开启，保留任务等待下次收能量链路")
                    }
                }

                // 2. 找出真正过期的任务（成熟超过1小时）
                // 逻辑：这种任务通常已经失效或无法收取，需要从内存中移除
                val expiredTasks = waitingTasks.filter { (_, task) ->
                    val collectTime = maxOf(task.produceTime, task.getProtectionEndTime())
                    now > collectTime + 60 * 60 * 1000L // 超过应收取时间1小时
                }

                if (expiredTasks.isNotEmpty()) {
                    val taskNames = expiredTasks.values.map { it.userName }.take(3).joinToString(",")
                    val moreText = if (expiredTasks.size > 3) "等${expiredTasks.size}个" else ""

                    Log.forest("🧹 清理过期蹲点：[${taskNames}${moreText}]")

                    // 执行移除
                    expiredTasks.forEach { (taskId, _) ->
                        removeWaitingTask(taskId)
                    }

                    // 持久化保存更改
                    EnergyWaitingPersistence.saveTasks(waitingTasks)
                    syncPersistentWaitingSchedules()
                } else {
                    syncPersistentWaitingSchedules()
                    // 仅在手动调试或强制模式下打印此日志，避免刷屏
                    if (enableRevalidation) {
                         Log.forest("定期清理检查：无过期任务")
                    }
                }

                // 3. 手动触发全面验证（仅在手动启用时执行）
                if (enableRevalidation) {
                    if (waitingTasks.isNotEmpty()) {
                        if (isWaitingCollectionAllowed()) {
                            Log.forest("🔍 手动全面验证：开始检查所有蹲点任务保护罩状态...")
                            revalidateAllWaitingTasks()
                        } else {
                            Log.forest("森林蹲点复核暂停：收集能量未开启，跳过好友主页验证")
                        }
                    }
                }

                // 日志摘要
                if (waitingTasks.isNotEmpty()) {
                    // 如果是定时任务且没有做任何操作，可以考虑降低日志级别或不打印
                    if (matureTasks.isNotEmpty() || expiredTasks.isNotEmpty() || enableRevalidation) {
                         Log.forest("清理维护完成，当前活跃蹲点${waitingTasks.size}个")
                    }
                } else {
                     Log.forest("清理维护完成，当前无活跃蹲点任务")
                }
            }
        }
    }

    /**
     * 设置能量收取回调
     */
    fun setEnergyCollectCallback(callback: EnergyCollectCallback) {
        energyCollectCallback = callback
        restoreForCurrentSession("callback_ready")
        triggerDueWaitingTasks("callback_ready")
        Log.forest("已设置能量收取回调")
    }

    fun resetForSessionSwitch(reason: String) {
        waitingJobs.values.toList().forEach { it.cancel() }
        waitingJobs.clear()
        waitingTasks.clear()
        restoredSessionEpoch = 0L
        Log.forest("森林蹲点会话已重置[$reason]")
    }

    fun restoreForCurrentSession(reason: String) {
        val session = AccountSessionCoordinator.currentSession()
        if (session == null) {
            Log.forest("森林蹲点恢复跳过：当前无激活会话[$reason]")
            return
        }
        if (!isReadyForWaitingExecution()) {
            Log.forest("森林蹲点恢复延后：等待回调/工作流就绪[user=${session.userId}][$reason]")
            return
        }
        if (restoredSessionEpoch == session.sessionEpoch) {
            triggerDueWaitingTasks("restore_skip_$reason")
            return
        }
        restoredSessionEpoch = session.sessionEpoch
        restoreTasksFromPersistence(reason, session.userId, session.sessionEpoch)
    }

    private fun triggerDueWaitingTasks(source: String) {
        if (!isReadyForWaitingExecution()) return
        if (!isWaitingCollectionAllowed()) {
            logWaitingCollectionPaused("triggerDue:$source")
            return
        }
        managerScope.launch {
            taskMutex.withLock {
                val now = System.currentTimeMillis()
                waitingTasks.values
                    .filter { calculatePreciseCollectTime(it) <= now }
                    .forEach { task ->
                        Log.forest("触发已到期森林蹲点任务[${task.taskId}] source=$source")
                        startPreciseWaitingCoroutine(task)
                    }
            }
        }
    }

    /**
     * 启动定期清理任务
     */
    fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                try {
                    // 使用动态间隔进行清理
                    val cleanupInterval = getCleanupInterval()
                    delay(cleanupInterval)
                    cleanExpiredTasks()

                    // 定期清理用户模式数据
                    UserEnergyPatternManager.cleanupExpiredPatterns()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.printStackTrace(TAG, "定期清理任务异常", e)
                }
            }
        }
    }

    /**
     * 获取当前正在等待的蹲点任务数量
     * @return 蹲点任务数量
     */
    fun getWaitingTaskCount(): Int {
        return waitingTasks.size
    }

    /**
     * 获取蹲点任务详细状态（仅显示最近的3个）
     */
    fun getWaitingTasksStatus(): String {
        val currentTime = System.currentTimeMillis()
        if (waitingTasks.isEmpty()) {
            return "无蹲点任务"
        }

        val statusBuilder = StringBuilder()
        val sortedTasks = waitingTasks.values.sortedBy { it.produceTime }
        val displayCount = minOf(3, sortedTasks.size)

        statusBuilder.append("蹲点任务状态 (${waitingTasks.size}个，显示最近${displayCount}个):\n")

        sortedTasks.take(displayCount).forEach { task ->
            val status = formatTimeStatus(currentTime, task.produceTime)
            val executeTime = TimeUtil.getCommonDate(task.produceTime)

            val protectionEndTime = task.getProtectionEndTime()
            val hasProtection = protectionEndTime > currentTime
            val protectionInfo = if (hasProtection) {
                val protectionStatus = formatTimeStatus(currentTime, protectionEndTime)
                " (保护${protectionStatus.removePrefix("剩余")})"
            } else {
                ""
            }

            statusBuilder.append("  - [${task.userName}] 球[${task.bubbleId}] 来源[${task.fromTag}] 任务[${task.taskId}] $status$protectionInfo → $executeTime\n")
        }

        if (sortedTasks.size > displayCount) {
            statusBuilder.append("  ... 还有${sortedTasks.size - displayCount}个任务")
        }

        return statusBuilder.toString().trimEnd()
    }

    /**
     * 重新验证所有蹲点任务的有效性（第2层防护）
     * 适用于管理器启动或任务恢复后的场景，确保移除已有保护罩覆盖的任务
     */
    private fun revalidateAllWaitingTasks() {
        managerScope.launch {
            taskMutex.withLock {
                if (waitingTasks.isEmpty()) {
                     Log.forest("无需验证：当前无蹲点任务")
                    return@withLock
                }
                if (!isWaitingCollectionAllowed()) {
                    Log.forest("森林蹲点复核暂停：收集能量未开启，跳过好友主页验证")
                    return@withLock
                }

                val tasksToRevalidate = waitingTasks.values.toList()
                val tasksToRemove = mutableListOf<String>()

                Log.forest("🔄 开始重新验证${tasksToRevalidate.size}个蹲点任务...")

                tasksToRevalidate.forEach { task ->
                    try {
                        // 自己的账号：无论是否有保护罩都保留（到时间后直接收取）
                        if (task.isSelf()) {
                            Log.forest("  ⭐️ 保留[${task.getUserTypeTag()}${task.userName}]球[${task.bubbleId}]：到时间直接收取")
                            return@forEach
                        }

                        // 好友账号：重新查询用户主页以获取最新的保护罩状态
                        val safeUserId = FriendGuard.normalizeUserId(task.userId)
                        if (safeUserId == null) {
                            Log.forest("  ❌ 移除[${task.getUserTypeTag()}${task.userName}]球[${task.bubbleId}]：userId无效")
                            tasksToRemove.add(task.taskId)
                            return@forEach
                        }
                        if (task.isPkContest()) {
                            if (FriendGuard.isSelf(safeUserId)) {
                                Log.forest("  ❌ 移除[${task.getUserTypeTag()}${task.userName}]球[${task.bubbleId}]：PK榜返回自己账号")
                                tasksToRemove.add(task.taskId)
                                return@forEach
                            }
                        } else if (FriendGuard.shouldSkipFriend(safeUserId, TAG, "复核蚂蚁森林蹲点好友")) {
                            Log.forest("  ❌ 移除[${task.getUserTypeTag()}${task.userName}]球[${task.bubbleId}]：好友关系无效")
                            tasksToRemove.add(task.taskId)
                            return@forEach
                        }

                        val userHomeResponse = AntForestRpcCall.queryFriendHomePage(
                            safeUserId,
                            if (task.isPkContest()) "PKContest" else null
                        )

                        if (userHomeResponse.isNullOrEmpty()) {
                             Log.forest("  验证[${task.getUserTypeTag()}${task.userName}]：无法获取主页信息，保留任务")
                            return@forEach
                        }

                        val userHomeObj = JSONObject(userHomeResponse)

                        // 好友账号：如果保护罩覆盖能量成熟期则移除
                        if (ForestUtil.shouldSkipWaitingDueToProtection(userHomeObj, task.produceTime)) {
                            val protectionEndTime = ForestUtil.getProtectionEndTime(userHomeObj)
                            val timeDifference = protectionEndTime - task.produceTime
                            val formattedTimeDifference = formatTime(timeDifference)

                            Log.forest("  ❌ 移除[${task.getUserTypeTag()}${task.userName}]球[${task.bubbleId}]：保护罩覆盖能量成熟期($formattedTimeDifference)"
                            )
                            tasksToRemove.add(task.taskId)
                        } else {
                            Log.forest("  ✅ 保留[${task.getUserTypeTag()}${task.userName}]球[${task.bubbleId}]：可正常收取")
                        }

                        // 添加短暂延迟，避免请求过快
                        delay(200)
                    } catch (e: Exception) {
                         Log.forest("  验证任务[${task.taskId}]时出错: ${e.message}，保留任务")
                    }
                }

                // 批量移除无效任务
                tasksToRemove.forEach { taskId ->
                    removeWaitingTask(taskId)
                }

                val validCount = tasksToRevalidate.size - tasksToRemove.size
                if (tasksToRemove.isNotEmpty()) {
                    Log.forest("🧹 验证完成：移除${tasksToRemove.size}个无效任务，保留${validCount}个有效任务")
                    EnergyWaitingPersistence.saveTasks(waitingTasks) // 保存更新
                    syncPersistentWaitingSchedules()
                } else {
                    Log.forest("✅ 验证完成：所有${validCount}个任务均有效")
                }
            }
        }
    }

    /**
     * 格式化时间为人性化的字符串
     * @param milliseconds 毫秒数
     * @return 格式化后的时间字符串
     */
    private fun formatTime(milliseconds: Long): String {
        val hours = milliseconds / (1000 * 60 * 60)
        val minutes = (milliseconds % (1000 * 60 * 60)) / (1000 * 60)
        return when {
            hours > 0 -> "${hours}小时${minutes}分钟"
            minutes > 0 -> "${minutes}分钟"
            else -> "${milliseconds / 1000}秒"
        }
    }

    /**
     * 格式化剩余时间状态
     * @param currentTime 当前时间
     * @param targetTime 目标时间
     * @return 格式化后的状态字符串（如："剩余2分19秒" 或 "已成熟1分5秒"）
     */
    private fun formatTimeStatus(currentTime: Long, targetTime: Long): String {
        val timeRemainMs = targetTime - currentTime
        val timeRemainSeconds = timeRemainMs / 1000
        val timeRemainMinutes = timeRemainSeconds / 60

        return if (timeRemainMs > 0) {
            if (timeRemainMinutes > 0) {
                "剩余${timeRemainMinutes}分${timeRemainSeconds % 60}秒"
            } else {
                "剩余${timeRemainSeconds}秒"
            }
        } else {
            val overTimeMinutes = (-timeRemainSeconds) / 60
            if (overTimeMinutes > 0) {
                "已成熟${overTimeMinutes}分${(-timeRemainSeconds) % 60}秒"
            } else {
                "已成熟${-timeRemainSeconds}秒"
            }
        }
    }

    /**
     * 从持久化存储恢复蹲点任务（内部方法）
     */
    private fun restoreTasksFromPersistence(reason: String, ownerUserId: String, sessionEpoch: Long) {
        managerScope.launch {
            try {
                delay(1000)
                if (!isRunSessionCurrent(ownerUserId, sessionEpoch) || !isReadyForWaitingExecution()) {
                    Log.forest("森林蹲点恢复取消：会话已切换或回调未就绪[user=$ownerUserId][session=$sessionEpoch][$reason]")
                    return@launch
                }

                // 加载持久化的任务
                val loadedTasks = EnergyWaitingPersistence.loadTasks()

                if (loadedTasks.isEmpty()) {
                     Log.forest("持久化存储中无任务需要恢复[user=$ownerUserId][session=$sessionEpoch][$reason]")
                    return@launch
                }

                Log.forest("🔄 从持久化存储恢复${loadedTasks.size}个蹲点任务[user=$ownerUserId][session=$sessionEpoch][$reason]...")

                // 验证并重新添加任务
                val verifyRemoteHome = isWaitingCollectionAllowed()
                if (!verifyRemoteHome) {
                    logWaitingCollectionPaused("restoreValidate:$reason")
                }
                val restoredCount = EnergyWaitingPersistence.validateAndRestoreTasks(
                    loadedTasks,
                    verifyRemoteHome = verifyRemoteHome
                ) { task ->
                    taskMutex.withLock {
                        try {
                            if (!isRunSessionCurrent(ownerUserId, sessionEpoch)) {
                                return@withLock false
                            }
                            // 检查任务是否已经存在（避免重复添加）
                            if (waitingTasks.containsKey(task.taskId)) {
                                 Log.forest("任务[${task.taskId}]已存在，跳过重复添加")
                                return@withLock false
                            }

                            // 添加任务到内存
                            waitingTasks[task.taskId] = task

                            if (isWaitingCollectionAllowed()) {
                                // 启动蹲点协程
                                startPreciseWaitingCoroutine(task)
                            } else {
                                logWaitingCollectionPaused("restorePersistence:$reason", task)
                            }

                            true
                        } catch (e: Exception) {
                            Log.error(TAG, "恢复任务[${task.taskId}]失败: ${e.message}")
                            false
                        }
                    }
                }

                if (restoredCount > 0) {
                    Log.forest("✅ 成功恢复${restoredCount}个蹲点任务，避免重新遍历好友")
                    // 保存更新后的任务列表
                    EnergyWaitingPersistence.saveTasks(waitingTasks)
                    syncPersistentWaitingSchedules()
                }

            } catch (e: Exception) {
                Log.error(TAG, "恢复蹲点任务失败: ${e.message}")
                Log.printStackTrace(TAG, e)
            }
        }
    }

    init {
        // 启动定期清理任务
        startPeriodicCleanup()

        Log.forest("精确能量球蹲点管理器已初始化（支持持久化）")
    }
}

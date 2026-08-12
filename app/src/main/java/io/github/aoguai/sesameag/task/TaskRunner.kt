package io.github.aoguai.sesameag.task

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.AccountSessionCheck
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.CustomRpcScheduler
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.CustomSettings
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.antFarm.AntFarm
import io.github.aoguai.sesameag.task.antFishPond.AntFishPond
import io.github.aoguai.sesameag.task.antForest.AntForest
import io.github.aoguai.sesameag.task.antMember.AntMember
import io.github.aoguai.sesameag.task.antOcean.AntOcean
import io.github.aoguai.sesameag.task.antOrchard.AntOrchard
import io.github.aoguai.sesameag.task.antSesameCredit.AntSesameCredit
import io.github.aoguai.sesameag.task.antSports.AntSports
import io.github.aoguai.sesameag.task.customTasks.ManualTask
import io.github.aoguai.sesameag.task.youthPrivilege.YouthPrivilege
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify.updateRunningTaskOrder
import io.github.aoguai.sesameag.util.Notify.updateRunningNextExec
import io.github.aoguai.sesameag.util.TimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 协程任务执行器 (优化版)
 *
 * 核心改进:
 * 1. **并发执行**: 支持任务并发运行，缩短总耗时。
 * 2. **生命周期**: 绑定到调用者的生命周期，防止泄漏。
 * 3. **逻辑简化**: 移除复杂的宽限期嵌套，使用标准的协程超时机制。
 */
class CoroutineTaskRunner(allModels: List<Model>) {

    companion object {
        private const val TAG = "CoroutineTaskRunner"
        private const val DEFAULT_TASK_TIMEOUT = 10 * 60 * 1000L // 10分钟

        private const val DEFAULT_MAX_CONCURRENCY = 1
    }

    private val taskList: List<ModelTask> = allModels.filterIsInstance<ModelTask>()

    // 统计数据
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val skippedCount = AtomicInteger(0)
    private val taskExecutionTimes = ConcurrentHashMap<String, Long>()
    private val longRunningJobs = ConcurrentLinkedQueue<LongRunningJob>()
    private var runSessionOwnerUserId: String? = null
    private var runSessionEpoch: Long = 0L
    private var maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY
    private lateinit var taskConcurrencyLimiter: Semaphore
    private lateinit var longRunningTaskLimiter: Semaphore

    private data class LongRunningJob(
        val taskId: String,
        val startTime: Long,
        val task: ModelTask,
        val job: Job,
        val releaseResources: () -> Unit
    ) {
        private val released = AtomicBoolean(false)

        fun releaseResourcesOnce() {
            if (released.compareAndSet(false, true)) {
                releaseResources()
            }
        }
    }

    /**
     * 启动任务执行流程
     * 注意：现在这是一个 suspend 函数，需要在一个协程作用域内调用
     */
    suspend fun run(
        isFirst: Boolean = true,
        rounds: Int = BaseModel.taskExecutionRounds.value ?: 1
    ) = coroutineScope { // 使用 coroutineScope 创建子作用域
        val startTime = System.currentTimeMillis()
        val activeSession = AccountSessionCoordinator.currentSession()
        runSessionOwnerUserId = activeSession?.userId
        runSessionEpoch = activeSession?.sessionEpoch ?: 0L

        // 【互斥检查】如果手动任务流正在运行，则跳过本次自动执行
        if (ManualTask.isManualRunning) {
            Log.record(TAG, "⏸ 检测到“手动庄园任务流”正在运行中，跳过本次自动任务调度")
            return@coroutineScope
        }

        val startSessionCheck = runSessionCheck()
        if (startSessionCheck !is AccountSessionCheck.Current) {
            logSessionInvalid("runner_start", startSessionCheck)
            return@coroutineScope
        }

        if (isFirst) {
            ApplicationHook.updateDay()
            resetCounters()
        }

        try {
            maxConcurrency = BaseModel.taskMaxConcurrency.value ?: DEFAULT_MAX_CONCURRENCY
            taskConcurrencyLimiter = Semaphore(maxConcurrency)
            longRunningTaskLimiter = Semaphore(1)
            Log.record(TAG, "🚀 开始执行任务流程 (并发数: $maxConcurrency)")

            CustomSettings.loadForTaskRunner()
            val status = CustomSettings.getOnceDailyStatus(enableLog = true)

            // 自定义 RPC（配置文件 + 定时执行）：每个调度周期执行一次（对每条最多执行 1 次）
            if (ApplicationHookConstants.isOffline()) {
                Log.record(TAG, "⏸ 检测到离线模式，跳过自定义 RPC 与后续任务流程")
            } else {
                CustomRpcScheduler.runIfEnabled()
            }

            // 执行多轮任务
            for (roundIndex in 0 until rounds) {
                val roundSessionCheck = runSessionCheck()
                if (roundSessionCheck !is AccountSessionCheck.Current) {
                    logSessionInvalid("before_round_${roundIndex + 1}", roundSessionCheck)
                    break
                }
                if (ApplicationHookConstants.isOffline()) {
                    Log.record(TAG, "⏸ 检测到离线模式，停止后续轮次")
                    break
                }
                val round = roundIndex + 1
                executeRound(round, rounds, status)
            }

            awaitLongRunningJobs()

            val afterLongRunningSessionCheck = runSessionCheck()
            if (afterLongRunningSessionCheck !is AccountSessionCheck.Current) {
                logSessionInvalid("after_long_running_jobs", afterLongRunningSessionCheck)
            } else if (CustomSettings.onlyOnceDaily.value == true) {
                // 确保时间状态是最新的
                TaskCommon.update()
                if (ApplicationHookConstants.isOffline()) {
                    Log.record(TAG, "⏸ 检测到离线模式，不设置 ${StatusFlags.FLAG_ONCE_DAILY_FINISHED} 标记")
                } else if (TaskCommon.IS_MODULE_SLEEP_TIME) {
                    Log.record(TAG, "💤 当前处于模块休眠时间，不设置 ${StatusFlags.FLAG_ONCE_DAILY_FINISHED} 标记")
                } else {
                    Status.setFlagToday(StatusFlags.FLAG_ONCE_DAILY_FINISHED)
                }
            }

        } catch (e: CancellationException) {
            Log.record(TAG, "🚫 任务流程被取消")
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "任务流程异常", e)
        } finally {
            withContext(NonCancellable) {
                awaitLongRunningJobs()
            }
            val finalSessionCheck = runSessionCheck()
            if (finalSessionCheck is AccountSessionCheck.Current) {
                scheduleNext()
            } else {
                Log.record(
                    TAG,
                    "⏭ ${runSessionCheckDescription(finalSessionCheck)}，跳过下次调度 owner=$runSessionOwnerUserId session=$runSessionEpoch",
                )
            }
            printExecutionSummary(startTime, System.currentTimeMillis())
        }
    }

    /**
     * 执行一轮任务 (并发模式)
     */
    private suspend fun executeRound(round: Int, totalRounds: Int, status: CustomSettings.OnceDailyStatus) = coroutineScope {
        val roundStartTime = System.currentTimeMillis()
        val roundSessionCheck = runSessionCheck()
        if (roundSessionCheck !is AccountSessionCheck.Current) {
            logSessionInvalid("round_$round", roundSessionCheck)
            return@coroutineScope
        }
        if (ApplicationHookConstants.isOffline()) {
            Log.record(TAG, "⏸ [第 $round/$totalRounds 轮] 检测到离线模式，跳过本轮")
            return@coroutineScope
        }

        TaskCommon.update()
        val energyOnlyMode = TaskCommon.IS_ENERGY_TIME

        // 1. 筛选任务
        val tasksToRun = taskList.filter { task ->
            task.isEnable() &&
                !CustomSettings.isOnceDailyBlackListed(task.getName(), status) &&
                (!energyOnlyMode || task is AntForest)
        }

        val excludedCount = taskList.count { it.isEnable() } - tasksToRun.size
        if (excludedCount > 0) skippedCount.addAndGet(excludedCount)

        if (energyOnlyMode) {
            Log.record(TAG, "⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，本轮仅保留蚂蚁森林任务")
        }

        val taskBatches = buildExecutionBatches(tasksToRun)
        updateRunningTaskOrder(taskBatches.flatten().map { it.getName() })
        Log.record(TAG, "🔄 [第 $round/$totalRounds 轮] 开始，共 ${tasksToRun.size} 个任务，分 ${taskBatches.size} 个批次")

        // 2. 按依赖顺序逐批执行；仅同批中明确独立的任务允许受全局信号量控制并行。
        //    不能把后续批次提前入队，否则前置模块的状态刷新尚未落地就会放大 RPC 并发。
        for ((batchIndex, batchTasks) in taskBatches.withIndex()) {
            if (ApplicationHookConstants.isOffline()) {
                Log.record(TAG, "⏸ [第 $round/$totalRounds 轮] 检测到离线模式，停止后续批次")
                break
            }
            scheduleTaskBatch(round, totalRounds, batchIndex + 1, taskBatches.size, batchTasks).joinAll()
        }

        val roundTime = System.currentTimeMillis() - roundStartTime
        Log.record(TAG, "✅ [第 $round/$totalRounds 轮] 结束，耗时: ${roundTime}ms")
    }

    private fun CoroutineScope.scheduleTaskBatch(
        round: Int,
        totalRounds: Int,
        batchIndex: Int,
        totalBatches: Int,
        tasks: List<ModelTask>
    ): List<Job> {
        if (tasks.isEmpty()) {
            return emptyList()
        }
        val batchSessionCheck = runSessionCheck()
        if (batchSessionCheck !is AccountSessionCheck.Current) {
            logSessionInvalid("batch_${round}_$batchIndex", batchSessionCheck)
            skippedCount.addAndGet(tasks.size)
            return emptyList()
        }
        if (ApplicationHookConstants.isOffline()) {
            skippedCount.addAndGet(tasks.size)
            Log.record(TAG, "⏸ [第 $round/$totalRounds 轮][批次 $batchIndex/$totalBatches] 检测到离线模式，跳过批次")
            return emptyList()
        }

        Log.record(
            TAG,
            "🧩 [第 $round/$totalRounds 轮][批次 $batchIndex/$totalBatches] ${tasks.joinToString("、") { it.getName().orEmpty() }}"
        )

        return tasks.map { task ->
            async {
                val taskSessionCheck = runSessionCheck()
                if (taskSessionCheck !is AccountSessionCheck.Current) {
                    Log.record(TAG, "⏸ 任务 ${task.getName()} 因${runSessionCheckDescription(taskSessionCheck)}而中止")
                    skippedCount.incrementAndGet()
                    return@async
                }
                if (ManualTask.isManualRunning) {
                    Log.record(TAG, "⏸ 任务 ${task.getName()} 因手动模式启动而中止")
                    return@async
                }
                executeSingleTask(task, round)
            }
        }
    }

    private fun buildExecutionBatches(tasks: List<ModelTask>): List<List<ModelTask>> {
        if (tasks.isEmpty()) {
            return emptyList()
        }

        val remainingTasks = tasks.toMutableList()
        fun takeBatch(predicate: (ModelTask) -> Boolean): List<ModelTask> {
            val matched = remainingTasks.filter(predicate)
            if (matched.isNotEmpty()) {
                remainingTasks.removeAll(matched)
            }
            return matched
        }

        return buildList {
            // 1) 运动先跑：步数改动需要尽早落地，供后续联动模块消费。
            takeBatch { it is AntSports }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 2) 青春特权先领取可供森林消费的道具。
            takeBatch { it is YouthPrivilege }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 3) 森林承接青春特权已确认的道具状态。
            takeBatch { it is AntForest }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 4) 海洋尽量承接前面模块已完成的联动任务状态，减少碎片奖励漏领。
            takeBatch { it is AntOcean }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 5) 芭芭农场先跑：施肥会先产出庄园做美食所需食材。
            takeBatch { it is AntOrchard }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 6) 福气鱼池放在农场之后，保持独立玩法批次。
            takeBatch { it is AntFishPond }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 7) 庄园尽量承接前面模块已完成的联动任务状态，减少碎片奖励漏领。
            takeBatch { it is AntFarm }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 8) 会员与芝麻信用放在联动行为之后。
            takeBatch { it is AntMember || it is AntSesameCredit }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            if (remainingTasks.isNotEmpty()) {
                add(remainingTasks.toList())
            }
        }
    }

    /**
     * 执行单个任务
     */
    private suspend fun executeSingleTask(task: ModelTask, round: Int) {
        val taskName = task.getName() ?: "未知任务"
        val taskId = "$taskName-R$round"
        val startTime = System.currentTimeMillis()

        val taskSessionCheck = runSessionCheck()
        if (taskSessionCheck !is AccountSessionCheck.Current) {
            skippedCount.incrementAndGet()
            Log.record(TAG, "⏸ ${runSessionCheckDescription(taskSessionCheck)}，跳过: $taskName")
            return
        }

        if (ApplicationHookConstants.isOffline()) {
            skippedCount.incrementAndGet()
            Log.record(TAG, "⏸ 检测到离线模式，跳过: $taskName")
            return
        }

        TaskCommon.update()
        if (TaskCommon.IS_ENERGY_TIME && task !is AntForest) {
            skippedCount.incrementAndGet()
            Log.record(TAG, "⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，跳过: $taskName")
            return
        }

        val isLongRunning = isLongRunningTask(task)
        val timeout = (BaseModel.taskTimeout.value ?: DEFAULT_TASK_TIMEOUT).toLong()
        var acquiredConcurrencySlot = false
        var acquiredLongRunningSlot = false
        var trackedLongRunningJob = false

        fun releaseTaskSlots() {
            if (acquiredConcurrencySlot) {
                taskConcurrencyLimiter.release()
                acquiredConcurrencySlot = false
            }
            if (acquiredLongRunningSlot) {
                longRunningTaskLimiter.release()
                acquiredLongRunningSlot = false
            }
        }

        try {
            if (isLongRunning) {
                longRunningTaskLimiter.acquire()
                acquiredLongRunningSlot = true
            }
            taskConcurrencyLimiter.acquire()
            acquiredConcurrencySlot = true

            val acquiredSlotSessionCheck = runSessionCheck()
            if (acquiredSlotSessionCheck !is AccountSessionCheck.Current) {
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏸ 任务 ${task.getName()} 在等待并发槽位后因${runSessionCheckDescription(acquiredSlotSessionCheck)}而中止")
                return
            }
            if (ManualTask.isManualRunning) {
                Log.record(TAG, "⏸ 任务 ${task.getName()} 在等待并发槽位后因手动模式启动而中止")
                return
            }
            if (ApplicationHookConstants.isOffline()) {
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏸ 任务 ${task.getName()} 在等待并发槽位后因离线模式启动而中止")
                return
            }

            Log.record(TAG, "▶️ 启动: $taskId")
            task.addRunCents()

            val job = task.startTask(force = false, rounds = 1)
            if (isLongRunning) {
                val longRunningJob = LongRunningJob(taskId, startTime, task, job) { releaseTaskSlots() }
                longRunningJobs.add(longRunningJob)
                trackedLongRunningJob = true
                job.invokeOnCompletion {
                    longRunningJob.releaseResourcesOnce()
                }
                if (job.isActive) {
                    Log.record(TAG, "✨ $taskId 启动成功 (后台运行中)")
                }
                return
            } else {
                withTimeout(timeout) {
                    job.join()
                }
            }

            // 成功
            val time = System.currentTimeMillis() - startTime
            val completedTaskSessionCheck = runSessionCheck()
            if (completedTaskSessionCheck !is AccountSessionCheck.Current) {
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏸ ${runSessionCheckDescription(completedTaskSessionCheck)}，中断: $taskId (耗时: ${time}ms)")
            } else if (ApplicationHookConstants.isOffline()) {
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏸ 离线模式中断: $taskId (耗时: ${time}ms)")
            } else {
                successCount.incrementAndGet()
                taskExecutionTimes[taskId] = time
                Log.record(TAG, "✅ 完成: $taskId (耗时: ${time}ms)")
            }

        } catch (e: TimeoutCancellationException) {
            val time = System.currentTimeMillis() - startTime

            failureCount.incrementAndGet()
            Log.error(TAG, "⏰ 超时: $taskId (${time}ms > ${timeout}ms)")
            // 尝试停止任务
            task.stopTask()

        } catch (e: Exception) {
            val time = System.currentTimeMillis() - startTime
            failureCount.incrementAndGet()
            Log.error(TAG, "❌ 失败: $taskId (${e.message})")
        } finally {
            if (!trackedLongRunningJob) {
                releaseTaskSlots()
            }
        }
    }

    private suspend fun awaitLongRunningJobs() {
        var loggedWait = false
        while (true) {
            val longRunningJob = longRunningJobs.peek() ?: break
            val longRunningSessionCheck = runSessionCheck()
            if (longRunningSessionCheck !is AccountSessionCheck.Current) {
                Log.record(TAG, "⏸ ${runSessionCheckDescription(longRunningSessionCheck)}，中断长任务: ${longRunningJob.taskId}")
                longRunningJob.task.stopTask()
                longRunningJob.job.cancel()
                longRunningJob.releaseResourcesOnce()
                longRunningJobs.remove(longRunningJob)
                skippedCount.incrementAndGet()
                continue
            }
            if (ApplicationHookConstants.isOffline()) {
                Log.record(TAG, "⏸ 离线模式中断长任务: ${longRunningJob.taskId}")
                longRunningJob.task.stopTask()
                longRunningJob.job.cancel()
                longRunningJob.releaseResourcesOnce()
                longRunningJobs.remove(longRunningJob)
                skippedCount.incrementAndGet()
                continue
            }
            if (!loggedWait) {
                loggedWait = true
                Log.record(TAG, "⏳ 等待长任务完成后再调度下次执行")
            }
            longRunningJob.job.join()
            longRunningJob.releaseResourcesOnce()
            longRunningJobs.remove(longRunningJob)
            val time = System.currentTimeMillis() - longRunningJob.startTime
            val completedLongRunningSessionCheck = runSessionCheck()
            if (completedLongRunningSessionCheck !is AccountSessionCheck.Current) {
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏸ ${runSessionCheckDescription(completedLongRunningSessionCheck)}，中断: ${longRunningJob.taskId} (耗时: ${time}ms)")
            } else if (ApplicationHookConstants.isOffline()) {
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏸ 离线模式中断: ${longRunningJob.taskId} (耗时: ${time}ms)")
            } else {
                successCount.incrementAndGet()
                taskExecutionTimes[longRunningJob.taskId] = time
                Log.record(TAG, "✅ 完成: ${longRunningJob.taskId} (耗时: ${time}ms)")
            }
        }
    }

    private fun isLongRunningTask(task: ModelTask): Boolean {
        return task is AntForest ||
            task is AntFarm ||
            task is AntSports
    }

    private fun scheduleNext() {
        try {
            ApplicationHook.scheduleNextExecutionInternal(System.currentTimeMillis())
            updateRunningNextExec(ApplicationHook.nextExecutionTime)
            Log.record(TAG, "📅 已调度下次执行")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "调度失败", e)
        }
    }

    private fun resetCounters() {
        successCount.set(0)
        failureCount.set(0)
        skippedCount.set(0)
        taskExecutionTimes.clear()
    }

    private fun runSessionCheck(): AccountSessionCheck =
        AccountSessionCoordinator.checkCurrentSession(runSessionOwnerUserId, runSessionEpoch)

    private fun runSessionCheckDescription(check: AccountSessionCheck): String =
        when (check) {
            AccountSessionCheck.NoCurrentSession -> "当前会话不存在"
            is AccountSessionCheck.AccountMismatch -> {
                "账号已切换(expected=${check.requestedUserId.ifBlank { "UNKNOWN" }} current=${check.currentUserId})"
            }
            is AccountSessionCheck.EpochMismatch -> {
                "会话轮次已切换(expected=${check.requestedEpoch} current=${check.currentEpoch})"
            }
            AccountSessionCheck.RuntimeIdentityUntrusted -> "运行时身份不可信"
            is AccountSessionCheck.AccountSlotInactive -> "执行账号槽位已撤销(user=${check.userId})"
            AccountSessionCheck.AccountSlotRegistryUnavailable -> "执行授权注册表不可用"
            is AccountSessionCheck.Current -> "会话状态已恢复(user=${check.session.userId} epoch=${check.session.sessionEpoch})"
        }

    private fun logSessionInvalid(
        stage: String,
        check: AccountSessionCheck,
    ) {
        Log.record(
            TAG,
            "⏹ ${runSessionCheckDescription(check)}，停止统一任务闭环: stage=$stage owner=$runSessionOwnerUserId session=$runSessionEpoch current=${AccountSessionCoordinator.currentSession()}",
        )
    }

    @SuppressLint("DefaultLocale")
    private fun printExecutionSummary(startTime: Long, endTime: Long) {
        val totalTime = endTime - startTime
        val avgTime = if (taskExecutionTimes.isNotEmpty()) taskExecutionTimes.values.average() else 0.0

        Log.summary(TAG, "=== 执行统计 (并发模式) ===")
        Log.summary(TAG, "总耗时: ${totalTime}ms")
        Log.summary(TAG, "成功: ${successCount.get()} | 失败: ${failureCount.get()} | 跳过: ${skippedCount.get()}")
        if (taskExecutionTimes.isNotEmpty()) {
            Log.summary(TAG, "平均耗时: %.0fms".format(avgTime))
        }

        val nextTime = ApplicationHook.nextExecutionTime
        if (nextTime > 0) {
            Log.summary(TAG, "下次: ${TimeUtil.getCommonDate(nextTime)}")
        }
        Log.summary(TAG, "============================")
    }
}


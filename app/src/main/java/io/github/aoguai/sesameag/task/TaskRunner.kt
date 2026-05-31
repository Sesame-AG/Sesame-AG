package io.github.aoguai.sesameag.task

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
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
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify.updateRunningNextExec
import io.github.aoguai.sesameag.util.TimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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

        // 最大并发数，防止请求过于频繁触发风控
        // 可以做成配置项，目前硬编码为 3
        private const val MAX_CONCURRENCY = 3

        private val TIMEOUT_WHITELIST = setOf("蚂蚁森林", "蚂蚁庄园", "运动")
    }

    private val taskList: List<ModelTask> = allModels.filterIsInstance<ModelTask>()

    // 统计数据
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val skippedCount = AtomicInteger(0)
    private val taskExecutionTimes = ConcurrentHashMap<String, Long>()
    private val longRunningJobs = ConcurrentLinkedQueue<LongRunningJob>()

    private data class LongRunningJob(
        val taskId: String,
        val startTime: Long,
        val task: ModelTask,
        val job: Job
    )

    /**
     * 启动任务执行流程
     * 注意：现在这是一个 suspend 函数，需要在一个协程作用域内调用
     */
    suspend fun run(
        isFirst: Boolean = true,
        rounds: Int = BaseModel.taskExecutionRounds.value ?: 1
    ) = coroutineScope { // 使用 coroutineScope 创建子作用域
        val startTime = System.currentTimeMillis()

        // 【互斥检查】如果手动任务流正在运行，则跳过本次自动执行
        if (ManualTask.isManualRunning) {
            Log.record(TAG, "⏸ 检测到“手动庄园任务流”正在运行中，跳过本次自动任务调度")
            return@coroutineScope
        }

        if (isFirst) {
            ApplicationHook.updateDay()
            resetCounters()
        }

        try {
            Log.record(TAG, "🚀 开始执行任务流程 (并发数: $MAX_CONCURRENCY)")

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
                if (ApplicationHookConstants.isOffline()) {
                    Log.record(TAG, "⏸ 检测到离线模式，停止后续轮次")
                    break
                }
                val round = roundIndex + 1
                executeRound(round, rounds, status)
            }

            awaitLongRunningJobs()

            if (CustomSettings.onlyOnceDaily.value == true) {
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
            scheduleNext()
            printExecutionSummary(startTime, System.currentTimeMillis())
        }
    }

    /**
     * 执行一轮任务 (并发模式)
     */
    private suspend fun executeRound(round: Int, totalRounds: Int, status: CustomSettings.OnceDailyStatus) = coroutineScope {
        val roundStartTime = System.currentTimeMillis()
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
        Log.record(TAG, "🔄 [第 $round/$totalRounds 轮] 开始，共 ${tasksToRun.size} 个任务，分 ${taskBatches.size} 个批次")

        // 2. 分批执行，每批内部保留并发能力
        for ((batchIndex, batchTasks) in taskBatches.withIndex()) {
            if (ApplicationHookConstants.isOffline()) {
                Log.record(TAG, "⏸ [第 $round/$totalRounds 轮] 检测到离线模式，停止后续批次")
                break
            }
            executeTaskBatch(round, totalRounds, batchIndex + 1, taskBatches.size, batchTasks)
        }

        val roundTime = System.currentTimeMillis() - roundStartTime
        Log.record(TAG, "✅ [第 $round/$totalRounds 轮] 结束，耗时: ${roundTime}ms")
    }

    private suspend fun executeTaskBatch(
        round: Int,
        totalRounds: Int,
        batchIndex: Int,
        totalBatches: Int,
        tasks: List<ModelTask>
    ) = coroutineScope {
        if (tasks.isEmpty()) {
            return@coroutineScope
        }
        if (ApplicationHookConstants.isOffline()) {
            skippedCount.addAndGet(tasks.size)
            Log.record(TAG, "⏸ [第 $round/$totalRounds 轮][批次 $batchIndex/$totalBatches] 检测到离线模式，跳过批次")
            return@coroutineScope
        }

        Log.record(
            TAG,
            "🧩 [第 $round/$totalRounds 轮][批次 $batchIndex/$totalBatches] ${tasks.joinToString("、") { it.getName().orEmpty() }}"
        )

        val semaphore = Semaphore(MAX_CONCURRENCY)
        val deferreds = tasks.map { task ->
            async {
                if (ManualTask.isManualRunning) {
                    Log.record(TAG, "⏸ 任务 ${task.getName()} 因手动模式启动而中止")
                    return@async
                }
                semaphore.withPermit {
                    if (ApplicationHookConstants.isOffline()) {
                        skippedCount.incrementAndGet()
                        Log.record(TAG, "⏸ 任务 ${task.getName()} 因离线模式启动而中止")
                        return@withPermit
                    }
                    executeSingleTask(task, round)
                }
            }
        }

        deferreds.awaitAll()
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

            // 2) 森林 作为联动前置批次。
            takeBatch { it is AntForest }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 3) 海洋尽量承接前面模块已完成的联动任务状态，减少碎片奖励漏领。
            takeBatch { it is AntOcean }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 4) 芭芭农场先跑：施肥会先产出庄园做美食所需食材。
            takeBatch { it is AntOrchard }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 5) 福气鱼池放在农场之后，保持独立玩法批次。
            takeBatch { it is AntFishPond }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 6) 庄园尽量承接前面模块已完成的联动任务状态，减少碎片奖励漏领。
            takeBatch { it is AntFarm }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 7) 会员与芝麻信用放在联动行为之后。
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

        val isWhitelist = isLongRunningTask(task, taskName)

        val timeout = (BaseModel.taskTimeout.value ?: DEFAULT_TASK_TIMEOUT).toLong()

        try {
            Log.record(TAG, "▶️ 启动: $taskId")
            task.addRunCents()

            val job = task.startTask(force = false, rounds = 1)
            if (isWhitelist) {
                longRunningJobs.add(LongRunningJob(taskId, startTime, task, job))
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
            if (ApplicationHookConstants.isOffline()) {
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
        }
    }

    private suspend fun awaitLongRunningJobs() {
        var loggedWait = false
        while (true) {
            val longRunningJob = longRunningJobs.peek() ?: break
            if (ApplicationHookConstants.isOffline()) {
                Log.record(TAG, "⏸ 离线模式中断白名单长任务: ${longRunningJob.taskId}")
                longRunningJob.task.stopTask()
                longRunningJob.job.cancel()
                longRunningJobs.remove(longRunningJob)
                skippedCount.incrementAndGet()
                continue
            }
            if (!loggedWait) {
                loggedWait = true
                Log.record(TAG, "⏳ 等待白名单长任务完成后再调度下次执行")
            }
            longRunningJob.job.join()
            longRunningJobs.remove(longRunningJob)
            val time = System.currentTimeMillis() - longRunningJob.startTime
            if (ApplicationHookConstants.isOffline()) {
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏸ 离线模式中断: ${longRunningJob.taskId} (耗时: ${time}ms)")
            } else {
                successCount.incrementAndGet()
                taskExecutionTimes[longRunningJob.taskId] = time
                Log.record(TAG, "✅ 完成: ${longRunningJob.taskId} (耗时: ${time}ms)")
            }
        }
    }

    private fun isLongRunningTask(task: ModelTask, taskName: String): Boolean {
        return task is AntForest ||
            task is AntFarm ||
            task is AntSports ||
            TIMEOUT_WHITELIST.contains(taskName)
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


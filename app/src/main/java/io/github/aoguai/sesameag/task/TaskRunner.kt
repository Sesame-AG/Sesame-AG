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
import io.github.aoguai.sesameag.task.antDodo.AntDodo
import io.github.aoguai.sesameag.task.antFarm.AntFarm
import io.github.aoguai.sesameag.task.antForest.AntForest
import io.github.aoguai.sesameag.task.antForestPatrol.AntForestPatrol
import io.github.aoguai.sesameag.task.antMember.AntMember
import io.github.aoguai.sesameag.task.antOcean.AntOcean
import io.github.aoguai.sesameag.task.antOrchard.AntOrchard
import io.github.aoguai.sesameag.task.antSesameCredit.AntSesameCredit
import io.github.aoguai.sesameag.task.goldenBean.GoldenBeanTreasure
import io.github.aoguai.sesameag.task.antSports.AntSports
import io.github.aoguai.sesameag.task.customTasks.ManualTask
import io.github.aoguai.sesameag.task.youthPrivilege.YouthPrivilege
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify.updateRunningTaskOrder
import io.github.aoguai.sesameag.util.TimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 统一任务调度：管理模块执行顺序、并发、超时、取消和执行结果。
 * 具体业务进展由模块及统一任务执行流程确认；持久续接须显式注册到持久任务调度。
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
    private val cancelledCount = AtomicInteger(0)
    private val deferredCount = AtomicInteger(0)
    private val allTasksSucceeded = AtomicBoolean(true)
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
        val job: Deferred<TaskExecutionOutcome>,
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

        val startSessionCheck = runSessionCheck()
        if (startSessionCheck !is AccountSessionCheck.Current) {
            logSessionInvalid("runner_start", startSessionCheck)
            return@coroutineScope
        }

        try {
            // 手动任务优先，但合法跳过仍须经过 finally 安排下一轮。
            if (ManualTask.isManualRunning) {
                Log.record(TAG, "⏸ 检测到“手动庄园任务流”正在运行中，跳过本轮自动执行并保留下轮调度")
                return@coroutineScope
            }
            if (isFirst) {
                ApplicationHook.updateDay()
                resetCounters()
            }

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
                    allTasksSucceeded.set(false)
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
                } else if (!allTasksSucceeded.get()) {
                    Log.record(TAG, "仍有失败、中断或待续接任务，不设置 ${StatusFlags.FLAG_ONCE_DAILY_FINISHED} 标记")
                } else {
                    Status.setFlagToday(StatusFlags.FLAG_ONCE_DAILY_FINISHED)
                }
            }

        } catch (e: CancellationException) {
            allTasksSucceeded.set(false)
            longRunningJobs.forEach {
                it.task.stopTask()
                it.job.cancel()
            }
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

        // 保留依赖批次屏障；只有明确独立的普通任务可以跨批补位。
        val pendingTasks = taskBatches.flatten().toMutableSet()
        val runningTasks = mutableMapOf<ModelTask, Job>()
        var batchIndex = 0
        while (batchIndex < taskBatches.size) {
            runningTasks.entries.removeAll { it.value.isCompleted }
            while (batchIndex < taskBatches.size &&
                taskBatches[batchIndex].none { it in pendingTasks || it in runningTasks }
            ) {
                batchIndex++
            }
            if (batchIndex == taskBatches.size) break
            if (ApplicationHookConstants.isOffline()) {
                Log.record(TAG, "⏸ [第 $round/$totalRounds 轮] 检测到离线模式，停止后续批次")
                break
            }

            // 当前批次优先，按剩余容量补入后续独立任务，避免等待者占用调度名额。
            for (readyBatchIndex in batchIndex until taskBatches.size) {
                val availableSlots = maxConcurrency - runningTasks.size
                if (availableSlots <= 0) break
                val readyTasks = taskBatches[readyBatchIndex].filter { task ->
                    task in pendingTasks && (readyBatchIndex == batchIndex ||
                        (task.javaClass in BaseModel.taskOrderModelClasses && !isLongRunningTask(task)))
                }.take(availableSlots)
                if (readyTasks.isEmpty()) continue

                pendingTasks.removeAll(readyTasks)
                val jobs = scheduleTaskBatch(round, totalRounds, readyBatchIndex + 1, taskBatches.size, readyTasks)
                readyTasks.zip(jobs).forEach { (task, job) -> runningTasks[task] = job }
            }

            // 任一任务结束就重新计算就绪任务，让依赖链恢复及空闲槽位补位都能及时发生。
            if (runningTasks.isNotEmpty()) {
                select<Unit> {
                    runningTasks.values.forEach { job -> job.onJoin {} }
                }
            }
        }
        runningTasks.values.joinAll()

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
            allTasksSucceeded.set(false)
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
                    allTasksSucceeded.set(false)
                    Log.record(TAG, "⏸ 任务 ${task.getName()} 因${runSessionCheckDescription(taskSessionCheck)}而中止")
                    skippedCount.incrementAndGet()
                    return@async
                }
                if (ManualTask.isManualRunning) {
                    allTasksSucceeded.set(false)
                    skippedCount.incrementAndGet()
                    Log.record(TAG, "⏸ 任务 ${task.getName()} 因手动模式启动而中止")
                    return@async
                }
                executeSingleTask(task, round)
                if (isLongRunningTask(task)) {
                    // 长任务启动返回后仍占用调度名额，主流程结束才允许依赖批次推进。
                    awaitLongRunningJobs()
                }
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

        // 默认依赖批次；手动模式据此重排并拆分，不扩大并发许可，也不清除每日标识或补跑模块。
        // 运动、森林、庄园保留长任务超时策略，本轮主流程结束后才允许后续依赖批次执行。
        val batches = buildList<List<ModelTask>> {
            // 1) 固定：运动优先更新步数，并处理森林可用道具兑换；不能随机到消费者之后。
            takeBatch { it is AntSports }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 2) 固定：青春特权先领取森林可用道具，森林再检查库存并使用。
            takeBatch { it is YouthPrivilege }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 3) 固定：森林消费前置道具并收能量，优先于巡护和末批森林能量消费模块。
            takeBatch { it is AntForest }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 4) 固定：巡护使用步数、森林能量和动物状态，但不依赖森林模块必须启用。
            takeBatch { it is AntForestPatrol }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 5) 固定：海洋保留联动行为之后的查询/领奖位置，避免随机提前后漏领本轮碎片。
            takeBatch { it is AntOcean }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 6) 固定：农场施肥需先为金豆留出配置额度，信用联动施肥复用相同资源约束。
            takeBatch { it is AntOrchard }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 7) 固定：庄园保留联动行为之后的位置，信用的大表鸽链路也会使用庄园状态。
            takeBatch { it is AntFarm }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 8) 固定且单独等待：会员领取积分和兑换道具后，再执行信用的农场施肥。
            // 信用施肥缺肥时会经 ExchangeReplenisher 使用会员积分补兑，二者不能同批随机/并发。
            takeBatch { it is AntMember }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 9) 固定：物种缺货补兑来自森林和会员，须在两者本轮主流程结束后执行。
            takeBatch { it is AntDodo }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 10) 固定：信用在会员之后、金豆之前，保留联动施肥及芝麻炼金的余额产出顺序。
            takeBatch { it is AntSesameCredit }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 11) 固定：金豆使用农场肥料和信用炼金已确认的余额，不能移到这两个来源之前。
            takeBatch { it is GoldenBeanTreasure }
                .takeIf { it.isNotEmpty() }
                ?.let(::add)

            // 12) 默认末批仅新村、绿色经营、网商福利金允许随机交换位置及跨批补位：
            // 新村金币/肥料、经营金币、网商福利金分别独立；
            // 合种、古树、保护地共用森林能量，不纳入候选，保持原槽位和相对入队顺序；
            // 同批仍受原并发控制，这不代表三者严格串行，也不改变它们已有的消费额度。
            // 默认模式不移动未列入 taskOrderModelClasses 的模块；手动排序也不将其视为独立补位任务。
            if (remainingTasks.isNotEmpty()) {
                add(remainingTasks.toList())
            }
        }

        if (BaseModel.taskOrderMode.value == BaseModel.TaskOrderMode.MANUAL) {
            val priorities = BaseModel.taskOrderPriority.value.orEmpty().toMap()
            val orderedTasks = batches.flatMapIndexed { batchIndex, batch ->
                batch.map { task -> batchIndex to task }
            }.sortedBy { (_, task) ->
                priorities[task.javaClass.simpleName]?.takeIf { it > 0 }?.toLong() ?: Long.MAX_VALUE
            }
            val orderedBatches = mutableListOf<MutableList<ModelTask>>()
            var previousBatchIndex: Int? = null
            for ((batchIndex, task) in orderedTasks) {
                // 只合并相邻的同源批次任务，保留手动顺序，不新增原本跨批的并发组合。
                if (batchIndex != previousBatchIndex) {
                    orderedBatches.add(mutableListOf())
                }
                orderedBatches.last().add(task)
                previousBatchIndex = batchIndex
            }
            return orderedBatches
        }

        return batches.map { batch ->
            val positions = batch.indices.filter { batch[it].javaClass in BaseModel.taskOrderModelClasses }
            if (positions.size < 2) return@map batch
            val orderedTasks = positions.map { batch[it] }.shuffled()
            // 默认只重排同批独立模块，保留依赖批次和资源消费模块的原位置。
            batch.toMutableList().apply {
                positions.forEachIndexed { index, position -> this[position] = orderedTasks[index] }
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
            allTasksSucceeded.set(false)
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
            allTasksSucceeded.set(false)
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
                allTasksSucceeded.set(false)
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏸ 任务 ${task.getName()} 在等待并发槽位后因${runSessionCheckDescription(acquiredSlotSessionCheck)}而中止")
                return
            }
            if (ManualTask.isManualRunning) {
                allTasksSucceeded.set(false)
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏸ 任务 ${task.getName()} 在等待并发槽位后因手动模式启动而中止")
                return
            }
            if (ApplicationHookConstants.isOffline()) {
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏸ 任务 ${task.getName()} 在等待并发槽位后因离线模式启动而中止")
                return
            }

            Log.record(TAG, "▶️ 启动: $taskId")

            val job = task.startTask(force = false, rounds = 1)
            if (isLongRunning) {
                val longRunningJob = LongRunningJob(taskId, startTime, task, job) { releaseTaskSlots() }
                longRunningJobs.add(longRunningJob)
                trackedLongRunningJob = true
                job.invokeOnCompletion {
                    longRunningJob.releaseResourcesOnce()
                }
                if (job.isActive) {
                    Log.record(TAG, "✨ $taskId 已启动，等待本轮执行结果")
                }
                return
            }
            val outcome = withTimeout(timeout) {
                awaitTaskOutcome(job, taskId)
            }
            recordTaskOutcome(taskId, startTime, outcome)

        } catch (e: TimeoutCancellationException) {
            val time = System.currentTimeMillis() - startTime

            allTasksSucceeded.set(false)
            failureCount.incrementAndGet()
            Log.error(TAG, "⏰ 超时: $taskId (${time}ms > ${timeout}ms)")
            // 尝试停止任务
            task.stopTask()

        } catch (e: CancellationException) {
            allTasksSucceeded.set(false)
            cancelledCount.incrementAndGet()
            Log.record(TAG, "⏸ 任务取消: $taskId (${e.message})")
            task.stopTask()
            throw e
        } catch (e: Exception) {
            val time = System.currentTimeMillis() - startTime
            allTasksSucceeded.set(false)
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
                recordTaskOutcome(longRunningJob.taskId, longRunningJob.startTime, TaskExecutionOutcome.CANCELLED)
                continue
            }
            if (ApplicationHookConstants.isOffline()) {
                Log.record(TAG, "⏸ 离线模式中断长任务: ${longRunningJob.taskId}")
                longRunningJob.task.stopTask()
                longRunningJob.job.cancel()
                longRunningJob.releaseResourcesOnce()
                longRunningJobs.remove(longRunningJob)
                recordTaskOutcome(longRunningJob.taskId, longRunningJob.startTime, TaskExecutionOutcome.CANCELLED)
                continue
            }
            if (!loggedWait) {
                loggedWait = true
                Log.record(TAG, "⏳ 等待前置长任务本轮主流程完成")
            }
            val outcome = awaitTaskOutcome(longRunningJob.job, longRunningJob.taskId)
            longRunningJob.releaseResourcesOnce()
            longRunningJobs.remove(longRunningJob)
            recordTaskOutcome(longRunningJob.taskId, longRunningJob.startTime, outcome)
        }
    }

    private suspend fun awaitTaskOutcome(job: Deferred<TaskExecutionOutcome>, taskId: String): TaskExecutionOutcome =
        try {
            // 等待前置任务的真实完成结果后再衔接依赖任务；没有人为固定睡眠时长。
            job.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            TaskExecutionOutcome.CANCELLED
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "任务执行异常: $taskId", e)
            TaskExecutionOutcome.FAILED
        }

    private fun recordTaskOutcome(taskId: String, startTime: Long, result: TaskExecutionOutcome) {
        val time = System.currentTimeMillis() - startTime
        val outcome = if (result != TaskExecutionOutcome.FAILED &&
            (runSessionCheck() !is AccountSessionCheck.Current || ApplicationHookConstants.isOffline())
        ) TaskExecutionOutcome.CANCELLED else result
        if (outcome != TaskExecutionOutcome.SUCCESS) allTasksSucceeded.set(false)
        when (outcome) {
            TaskExecutionOutcome.SUCCESS -> {
                successCount.incrementAndGet()
                taskExecutionTimes[taskId] = time
                Log.record(TAG, "✅ 完成: $taskId (耗时: ${time}ms)")
            }
            TaskExecutionOutcome.FAILED -> {
                failureCount.incrementAndGet()
                Log.error(TAG, "❌ 执行失败: $taskId (耗时: ${time}ms)，详见任务错误记录")
            }
            TaskExecutionOutcome.SKIPPED -> {
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏭ 未执行: $taskId (耗时: ${time}ms)")
            }
            TaskExecutionOutcome.CANCELLED -> {
                cancelledCount.incrementAndGet()
                Log.record(TAG, "⏸ 中断: $taskId (耗时: ${time}ms)")
            }
            TaskExecutionOutcome.DEFERRED -> {
                deferredCount.incrementAndGet()
                Log.record(TAG, "⏳ 待续接: $taskId (耗时: ${time}ms)")
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
            if (ApplicationHook.nextExecutionTime > 0L) {
                Log.record(TAG, "📅 下次有效计划: ${TimeUtil.getCommonDate(ApplicationHook.nextExecutionTime)}")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "调度失败", e)
        }
    }

    private fun resetCounters() {
        successCount.set(0)
        failureCount.set(0)
        skippedCount.set(0)
        cancelledCount.set(0)
        deferredCount.set(0)
        allTasksSucceeded.set(true)
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
        Log.summary(TAG, "成功: ${successCount.get()} | 失败: ${failureCount.get()} | 跳过: ${skippedCount.get()} | 中断: ${cancelledCount.get()} | 待续接: ${deferredCount.get()}")
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


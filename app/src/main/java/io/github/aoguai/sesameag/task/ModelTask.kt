package io.github.aoguai.sesameag.task

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.data.RuntimeInfo
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleDefaults
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.ModelType
import io.github.aoguai.sesameag.task.antForest.AntForest
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify.finishTaskRunning
import io.github.aoguai.sesameag.util.Notify.sendAlert
import io.github.aoguai.sesameag.util.Notify.startTaskRunning
import io.github.aoguai.sesameag.util.Notify.updateRunningNextExec
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.WakeLockManager
import io.github.aoguai.sesameag.util.WorkflowRootGuard
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONException
import org.json.JSONObject

/**
 * 基于协程的抽象任务模型类
 *
 * 这是 Sesame-AG 框架中的核心任务执行类，提供了以下功能：
 * 1. 基于Kotlin协程的任务生命周期管理（启动、停止、暂停）
 * 2. 协程子任务管理（添加、移除、执行）
 * 3. 任务执行统计和监控
 * 4. 统一的多轮顺序执行模式
 * 5. 协程调度器管理和任务调度
 * 6. 结构化并发和错误处理
 *
 * 主要组件：
 * - taskScope: 任务协程作用域
 * - childTaskMap: 子任务映射表
 * - executionQueue: 完整任务按提交顺序执行
 * - runCents: 任务运行次数计数器
 *
 * 使用方式：
 * 继承此类并实现抽象方法：getName(), getFields(), check(), run()
 *
 */
abstract class ModelTask : Model() {
    private data class TaskState(
        val scope: CoroutineScope? = null,
        val startJob: Deferred<TaskExecutionOutcome>? = null,
        val predecessors: List<Job> = emptyList(),
    )
    private val taskState = java.util.concurrent.atomic.AtomicReference(TaskState())
    private val currentStartJob: Deferred<TaskExecutionOutcome>?
        get() = taskState.get().startJob
    private val executionQueue = io.github.aoguai.sesameag.util.SerialTaskQueue()

    /** 子任务映射表，存储当前任务的所有子任务 */
    private val childTaskMap: MutableMap<String, ChildModelTask> = ConcurrentHashMap()
    
    /** 任务运行次数计数器 */
    var runCents: Int = 0
        private set
    
    /** 任务是否正在运行 */
    @Volatile
    var isRunning = false
        protected set

    /** 连续执行失败计数（内存，进程重启清零），达到阈值后自动挂起本任务 */
    private val consecutiveFailures = AtomicInteger(0)

    /** 增加任务运行次数 */
    fun addRunCents() {
        this.runCents += 1
    }

    /**
     * 准备任务执行环境
     */
    override fun prepare() {
        ensureTaskScope()
    }

    private fun ensureTaskScope(): TaskState {
        TaskExecutionReport.current()?.ensureCurrent()
        while (true) {
            val state = taskState.get()
            if (state.scope?.isActive == true) return state
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("Task-$id"))
            val next = state.copy(scope = scope, predecessors = state.predecessors.filterNot { it.isCompleted })
            if (taskState.compareAndSet(state, next)) return next
            scope.cancel()
        }
    }

    val id: String
        /** 获取任务ID，默认使用toString()方法  */
        get() = toString()

    /** 获取模型类型，固定返回TASK  */
    override fun getType(): ModelType {
        return ModelType.TASK
    }

    /** 获取任务名称，子类必须实现  */
    abstract override fun getName(): String?

    /** 获取任务字段配置，子类必须实现  */
    abstract override fun getFields(): ModelFields?

    private fun runningStatusName(): String? {
        val name = getName()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when (name) {
            "主任务", "MAIN_TASK" -> null
            else -> name
        }
    }

    private fun recordModuleCheckMessage(msg: String) {
        when (getName()) {
            "蚂蚁森林", "蚂蚁森林合种", "保护地", "生态保护" -> Log.forest(msg)
            "保护地巡护" -> Log.forestPatrol(msg)
            "农场", "芭芭农场" -> Log.orchard(msg)
            "金豆夺宝" -> Log.goldenBean(msg)
            "蚂蚁庄园" -> Log.farm(msg)
            "新村", "蚂蚁新村" -> Log.stall(msg)
            "海洋", "神奇海洋" -> Log.ocean(msg)
            "神奇物种" -> Log.dodo(msg)
            "会员" -> Log.member(msg)
            "运动" -> Log.sports(msg)
            "绿色经营" -> Log.greenFinance(msg)
            "芝麻信用" -> Log.sesame(msg)
            else -> when (getGroup()) {
                ModelGroup.FOREST -> Log.forest(msg)
                ModelGroup.FOREST_PATROL -> Log.forestPatrol(msg)
                ModelGroup.ORCHARD -> Log.orchard(msg)
                ModelGroup.GOLDEN_BEAN -> Log.goldenBean(msg)
                ModelGroup.FARM -> Log.farm(msg)
                ModelGroup.STALL -> Log.stall(msg)
                ModelGroup.DODO -> Log.dodo(msg)
                ModelGroup.SPORTS -> Log.sports(msg)
                ModelGroup.MEMBER -> Log.member(msg)
                ModelGroup.SESAME_CREDIT -> Log.sesame(msg)
                else -> Log.record(getName() ?: "Task", msg)
            }
        }
    }

    /** 检查任务是否可以执行  */
    open fun check(): Boolean {
        TaskCommon.update()

        // 任务级异常暂停：未到期则拦截执行；到期项由 activeTaskPauseMap 惰性清理，通知随之恢复
        val pausedUntil = activeTaskPauseMap()[getName()]
        if (pausedUntil != null) {
            recordModuleCheckMessage("⏸ 异常暂停中，恢复时间 " + TimeUtil.getCommonDate(pausedUntil) + "，暂不执行检测！")
            return false
        }

        // 只有蚂蚁森林启用且当前不是蚂蚁森林任务时，才拦截能量时间
        if (getName() != "蚂蚁森林") {
            val antForest = getModel(AntForest::class.java)
            if (antForest != null && antForest.isEnable()) {
                if (TaskCommon.IS_ENERGY_TIME) {
                    recordModuleCheckMessage("⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，停止执行${getName()}任务！")
                    return false
                }
            }
        }

        // 模块休眠检查
        if (TaskCommon.IS_MODULE_SLEEP_TIME) {
            recordModuleCheckMessage("💤 模块休眠时间【${BaseModel.modelSleepTime.value}】停止执行${getName()}任务！")
            return false
        }
        return true
    }

    /** 任务级异常暂停：写入持久化暂停表（按用户隔离），基类 check() 与常驻通知据此拦截/展示 */
    protected fun pauseSelfUntil(untilMs: Long): Unit = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
        val name = getName() ?: return@transaction
        val runtimeInfo = RuntimeInfo.getInstance()
        val jo = try {
            JSONObject(runtimeInfo.getString(RuntimeInfo.RuntimeInfoKey.TaskPauseMap))
        } catch (e: JSONException) {
            JSONObject()
        }
        jo.put(name, untilMs)
        runtimeInfo.put(RuntimeInfo.RuntimeInfoKey.TaskPauseMap, jo.toString())
    }

    /** 连续执行失败达到阈值后自动挂起本任务，避免反复异常空转 */
    private fun onTaskRunFailure() {
        val name = getName() ?: return
        val failures = consecutiveFailures.incrementAndGet()
        val threshold = BaseModel.exceptionPauseThreshold.value ?: 0
        val waitMs = (BaseModel.waitWhenException.value ?: 0).toLong()
        if (threshold >= 1 && failures >= threshold && waitMs > 0 && consecutiveFailures.compareAndSet(failures, 0)) {
            val until = System.currentTimeMillis() + waitMs
            pauseSelfUntil(until)
            Log.record(TAG, name + " 连续失败 " + failures + " 次，已自动挂起至 " + TimeUtil.getCommonDate(until))
            sendAlert("任务连续异常挂起", "「" + name + "」连续失败 " + failures + " 次，已挂起至 " + TimeUtil.getCommonDate(until))
        }
    }

    /** 
     * 执行任务的具体逻辑（协程版本）
     * Kotlin子类应该覆盖此方法
     */
    protected open suspend fun runSuspend() {
        // 默认调用 Java 兼容的 run 方法
        // 使用 runInterruptible 让取消/停止能通过线程中断尽快打断阻塞型逻辑（sleep/IO/while循环等）
        runInterruptible(Dispatchers.IO) { runJava() }
    }

    /** 
     * 执行任务的具体逻辑（Java兼容版本）
     * Java子类应该覆盖此方法
     */
    protected open fun runJava() {
        // 子类必须覆盖 runSuspend() 或 runJava() 之一
        throw NotImplementedError("子类必须实现 runSuspend() 或 runJava() 方法")
    }

    /** 
     * 最终调用的run方法
     * 子类不应该直接覆盖此方法
     */
    suspend fun run() {
        currentCoroutineContext().ensureActive()
        TaskExecutionReport.current()?.ensureCurrent()
        if (!WorkflowRootGuard.isExecutionAllowed()) {
            throw CancellationException("必需权限或使用协议未就绪，任务已取消")
        }
        runSuspend()
    }

    /** 检查是否存在指定ID的子任务 */
    fun hasChildTask(childId: String): Boolean {
        return childTaskMap.containsKey(childId)
    }

    /** 已开始的任务仍归 taskScope 管理；释放标识，允许它登记同一目标的下一次等待。 */
    internal fun detachChildTask(childTask: ChildModelTask) {
        childTaskMap.remove(childTask.id, childTask)
    }

    /**
     * 添加子任务（协程版本，内部使用）
     * @param childTask 要添加的子任务
     */
    private suspend fun addChildTaskSuspend(childTask: ChildModelTask) {
        currentCoroutineContext().ensureActive()
        val childId = childTask.id
        childTask.modelTask = this
        childTaskMap.put(childId, childTask)?.cancel()

        // 在协程作用域中启动子任务
        val job = CoroutineScope(currentCoroutineContext()).launch {
            try {
                childTask.run()
            } catch (e: Exception) {
                val taskName = getName() ?: "未知任务"
                // 检查是否是协程取消相关的异常
                if (e.javaClass.name.contains("CancellationException") || 
                    e.message?.contains("cancelled") == true ||
                    e.message?.contains("StandaloneCoroutine") == true) {
                    Log.record("子任务协程被取消: $taskName-$childId - ${e.message}")
                    // 协程取消是正常现象，不需要打印堆栈
                } else {
                    Log.printStackTrace("addChildTaskSuspend 子任务执行异常1: $taskName-$childId", e)
                }
            } finally {
//                childTaskMap.remove(childId)
                childTaskMap.remove(childTask.id, childTask)


            }
        }

        childTask.job = job
        job.join() // 挂起直到子任务完成
    }

    /**
     * 添加子任务（Java/Kotlin通用入口）
     * 
     * **示例用法：**
     * ```kotlin
     * // Kotlin挂起函数
     * addChildTask(ChildModelTask("task1", "GROUP") {
     *     Log.record("执行成功")
     * }, execTime = System.currentTimeMillis() + 5000)
     * 
     * // Java Runnable
     * addChildTask(new ChildModelTask("task2", "GROUP", () -> {
     *     Log.record("Java任务");
     * }, System.currentTimeMillis() + 3000))
     * ```
     * 
     * @return 始终返回true
     */
    fun addChildTask(childTask: ChildModelTask): Boolean {
        childTask.executionReport.ensureCurrent()
        val state = ensureTaskScope()
        val scope = checkNotNull(state.scope)
        childTask.parentJob = scope.coroutineContext[Job]
        scope.launch(childTask.executionReport.context, start = CoroutineStart.UNDISPATCHED) {
            childTask.executionReport.executionJob = currentCoroutineContext()[Job]
            state.predecessors.joinAll()
            addChildTaskSuspend(childTask)
        }
        return true
    }

    /** 业务回查仍由模块完成；持久认领与本地计时回退共用一次执行的收尾。 */
    protected fun executePersistentChild(
        scheduleId: String,
        ownerUserId: String,
        sessionEpoch: Long,
        source: String,
        workflowQueue: io.github.aoguai.sesameag.util.SerialTaskQueue,
        persistent: Boolean = true,
        afterExecution: suspend (TaskExecutionOutcome) -> Unit = {},
        block: suspend () -> TaskExecutionOutcome,
    ): Boolean {
        val context = ApplicationHook.appContext ?: return false
        val schedule = if (persistent) PersistentScheduleRegistry.get(scheduleId) ?: return false else null
        if (schedule != null &&
            (schedule.ownerUserId != ownerUserId || schedule.sessionEpoch != sessionEpoch ||
                !PersistentScheduleRegistry.markRunning(scheduleId, source = source, expected = schedule))
        ) return false
        val report = TaskExecutionReport(AccountSessionIdentity(ownerUserId, sessionEpoch))
        val worker = runCatching {
            report.ensureCurrent()
            val state = ensureTaskScope()
            checkNotNull(state.scope).launch(report.context, start = CoroutineStart.LAZY) {
                state.predecessors.joinAll()
                report.executionJob = currentCoroutineContext()[Job]
                var lease: WakeLockManager.WakeLockLease? = null
                try {
                    workflowQueue.submit {
                        ensureActive()
                        if (!AccountSessionCoordinator.isCurrentSession(ownerUserId, sessionEpoch) ||
                            ApplicationHookConstants.isOffline() || !WorkflowRootGuard.isExecutionAllowed()
                        ) throw CancellationException("persistent_child_session_unavailable")
                        if (schedule != null && System.currentTimeMillis() > schedule.deadlineAtMs()) {
                            PersistentScheduleRegistry.markFailed(context, scheduleId, "worker_start_after_deadline", source = source)
                            Log.error(TAG, "持久任务等待执行时超过截止时间[${getName()}] id=$scheduleId")
                            return@submit
                        }
                        // 排队期间可能进入休眠；只在真正执行前判断并获取唤醒锁。
                        val result = if (BaseModel.modelSleepTime.isActive(System.currentTimeMillis())) {
                            recordModuleCheckMessage("💤 模块休眠时间【${BaseModel.modelSleepTime.value}】停止执行${getName()}持久子任务！")
                            TaskExecutionOutcome.SKIPPED
                        } else {
                            lease = WakeLockManager.acquire(context, PersistentScheduleDefaults.TASK_EXECUTION_WAKELOCK_MS,
                                source = source, scheduleId = scheduleId)
                            withContext(report.context) { block() }
                        }
                        ensureActive()
                        if (!AccountSessionCoordinator.isCurrentSession(ownerUserId, sessionEpoch) ||
                            ApplicationHookConstants.isOffline()
                        ) throw CancellationException("persistent_child_session_lost")
                        val reported = report.outcome()
                        val outcome = if (result in setOf(TaskExecutionOutcome.SUCCESS, TaskExecutionOutcome.DEFERRED) &&
                            reported != TaskExecutionOutcome.SUCCESS
                        ) reported else result
                        when (outcome) {
                            TaskExecutionOutcome.SUCCESS,
                            TaskExecutionOutcome.DEFERRED -> {
                                // FIRED 表示本次执行结束；待确认数据和下一次回查仍由模块保留。
                                if (persistent) PersistentScheduleRegistry.markFired(context, scheduleId, source = source)
                                if (outcome == TaskExecutionOutcome.DEFERRED) {
                                    Log.record(TAG, "持久任务本次执行结束，业务待续接[${getName()}] id=$scheduleId")
                                }
                            }
                            TaskExecutionOutcome.FAILED -> {
                                Log.error(TAG, "持久任务执行失败[${getName()}] id=$scheduleId，详见业务错误日志")
                                if (persistent) PersistentScheduleRegistry.markFailed(context, scheduleId, "business_execution_failed", source = source)
                            }
                            TaskExecutionOutcome.CANCELLED,
                            TaskExecutionOutcome.SKIPPED -> {
                                Log.record(TAG, "持久任务未完成执行[${getName()}] id=$scheduleId outcome=$outcome")
                                if (persistent) PersistentScheduleRegistry.markFailed(context, scheduleId, "execution_$outcome", source = source)
                            }
                        }
                        if (outcome != TaskExecutionOutcome.CANCELLED && outcome != TaskExecutionOutcome.SKIPPED) {
                            afterExecution(outcome)
                        }
                    }
                } catch (e: CancellationException) {
                    if (persistent) PersistentScheduleRegistry.markWorkerFailedIfActive(context, scheduleId, "worker_cancelled:${e.message}", source = source)
                    Log.record(TAG, "持久任务中断[${getName()}] id=$scheduleId reason=${e.message}")
                    throw e
                } catch (t: Throwable) {
                    report.recordException()
                    Log.printStackTrace(TAG, "持久任务执行异常[${getName()}] id=$scheduleId", t)
                    if (persistent) PersistentScheduleRegistry.markFailed(context, scheduleId, t.message ?: t.javaClass.name, source = source)
                } finally {
                    lease?.close()
                    if (report.hasException) onTaskRunFailure()
                }
            }
        }.onFailure {
            Log.printStackTrace(TAG, "持久任务提交失败[${getName()}] id=$scheduleId", it)
            if (persistent) PersistentScheduleRegistry.markWorkerFailedIfActive(context, scheduleId, "worker_submit_failed", source = source)
        }.getOrNull() ?: return false
        worker.invokeOnCompletion { error ->
            if (persistent) PersistentScheduleRegistry.markWorkerFailedIfActive(context, scheduleId,
                "worker_completed_without_terminal_state:${error?.javaClass?.simpleName ?: "none"}", source = source)
        }
        worker.start()
        return true
    }

    /**
     * 启动任务（协程版本）
     * @param force 是否强制重启
     * @param rounds 执行轮数，默认2轮
     */
    fun startTask(
        force: Boolean = false,
        rounds: Int = 2
    ): Deferred<TaskExecutionOutcome> {
        return scheduleTask(
            force = force,
            rounds = rounds,
            bypassExecutionChecks = false,
            rejectExisting = false,
        ) ?: error("无法创建自动任务启动任务")
    }

    /**
     * 启动一次手动任务。
     *
     * 手动入口跳过自动任务的启用状态和调度检查，但仍复用任务 Job、互斥、运行状态和停止流程。
     * @return 新启动的 Job；如果任务已经运行或正在等待启动则返回 null
     */
    fun startManualTask(rounds: Int = 1): Job? {
        if (isRunning) {
            Log.record(TAG, "任务 ${getName()} 正在运行，跳过手动启动")
            return null
        }
        return scheduleTask(
            force = false,
            rounds = rounds,
            bypassExecutionChecks = true,
            rejectExisting = true,
        )
    }

    private fun scheduleTask(
        force: Boolean,
        rounds: Int,
        bypassExecutionChecks: Boolean,
        rejectExisting: Boolean,
    ): Deferred<TaskExecutionOutcome>? {
        val report = TaskExecutionReport()
        while (true) {
            report.ensureCurrent()
            val state = ensureTaskScope()
            val existingJob = state.startJob
            if (!force && existingJob != null && !existingJob.isCompleted) {
                Log.record(TAG, "任务 ${getName()} 正在运行或等待启动，跳过${if (rejectExisting) "手动" else "重复"}启动")
                return if (rejectExisting) null else existingJob
            }
            val scope = if (force) {
                CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("Task-$id"))
            } else checkNotNull(state.scope)
            val stopping = if (force) state.scope?.coroutineContext?.get(Job) else null
            val predecessors = (state.predecessors + listOfNotNull(stopping)).filterNot { it.isCompleted }
            val job = scope.async(report.context, start = CoroutineStart.LAZY) {
                report.executionJob = currentCoroutineContext()[Job]
                predecessors.joinAll()
                executionQueue.submit {
                    report.ensureCurrent()
                    if (!WorkflowRootGuard.isExecutionAllowed()) {
                        Log.record(TAG, "必需权限或使用协议未就绪，拒绝启动任务 ${getName()}")
                        return@submit TaskExecutionOutcome.SKIPPED
                    }
                    if (!bypassExecutionChecks && (!isEnable() || !check())) {
                        Log.record(TAG, "任务 ${getName()} 不满足执行条件")
                        return@submit TaskExecutionOutcome.SKIPPED
                    }
                    val runningName = runningStatusName()
                    try {
                        isRunning = true
                        addRunCents()
                        startTaskRunning(runningName)
                        withContext(report.context) {
                            executeMultiRoundTask(rounds)
                            ensureActive()
                        }
                    } catch (e: CancellationException) {
                        Log.record(TAG, "任务被取消: ${getName()}")
                        throw e
                    } catch (e: Exception) {
                        Log.printStackTrace("startTask err: ${getName()}", e)
                        report.recordException()
                    } finally {
                        isRunning = false
                        finishTaskRunning(runningName)
                        updateRunningNextExec(-1)
                    }
                    if (report.hasException) onTaskRunFailure() else consecutiveFailures.set(0)
                    report.outcome()
                }
            }
            if (!taskState.compareAndSet(state, TaskState(scope, job, predecessors))) {
                job.cancel()
                if (force) scope.cancel()
                continue
            }
            job.invokeOnCompletion {
                taskState.updateAndGet { current -> if (current.startJob === job) current.copy(startJob = null) else current }
            }
            if (stopping != null) {
                stoppingJobs.add(stopping)
                stopping.invokeOnCompletion { stoppingJobs.remove(stopping) }
                stopping.cancel()
            }
            job.start()
            return job
        }
    }

    /**
     * 执行多轮任务
     */
    private suspend fun executeMultiRoundTask(rounds: Int) {
        val startTime = System.currentTimeMillis()
        val stats = TaskExecutionStats()
        
        for (round in 1..rounds) {
            if (getName() != "MAIN_TASK") {
                Log.debug(TAG, "开始执行第${round}轮任务: ${getName()}")
            }
            // 统一使用顺序执行
            executeSequential(round, stats)
        }
        
        val endTime = System.currentTimeMillis()
        // 完成统计，补充结束时间
        stats.complete()
        if (getName() != "MAIN_TASK") {
            Log.debug(TAG, "任务 ${getName()} 完成，总耗时: ${endTime - startTime}ms")
            Log.debug(TAG, stats.summary)
        }
    }

    /**
     * 顺序执行
     */
    private suspend fun executeSequential(round: Int, stats: TaskExecutionStats) {
        stats.recordTaskStart("${getName()}-Round$round")
        try {
            run()
            currentCoroutineContext().ensureActive()
            when (TaskExecutionReport.current()?.outcome() ?: TaskExecutionOutcome.SUCCESS) {
                TaskExecutionOutcome.SUCCESS -> stats.recordTaskEnd("${getName()}-Round$round", true)
                TaskExecutionOutcome.FAILED -> stats.recordTaskEnd("${getName()}-Round$round", false)
                else -> stats.recordSkipped("${getName()}-Round$round")
            }
        } catch (e: CancellationException) {
            stats.recordSkipped("${getName()}-Round$round")
            Log.debug(TAG, "任务本轮被取消: ${getName()}-Round$round")
            throw e
        } catch (e: Exception) {
            stats.recordTaskEnd("${getName()}-Round$round", false)
            throw e
        }
    }

    /**
     * 停止任务（协程版本）
     * 注意：此方法只负责发出取消信号；如需等待退出，请调用 stopTaskAndWait。
     */
    open fun stopTask() {
        isRunning = false
        val previous = taskState.getAndUpdate { state ->
            TaskState(predecessors = (state.predecessors + listOfNotNull(state.scope?.coroutineContext?.get(Job)))
                .filterNot { it.isCompleted })
        }
        val stopping = previous.scope?.coroutineContext?.get(Job)
        if (stopping != null) {
            stoppingJobs.add(stopping)
            stopping.invokeOnCompletion { stoppingJobs.remove(stopping) }
            stopping.cancel()
            childTaskMap.values.filter { it.parentJob === stopping }.forEach { childTask ->
                try { childTask.cancel() }
                catch (e: Exception) { Log.printStackTrace("stopTask err", e) }
                finally { childTaskMap.remove(childTask.id, childTask) }
            }
        }
    }

    suspend fun stopTaskAndWait(timeoutMs: Long = 5_000L): Boolean {
        val state = taskState.get()
        stopTask()
        val jobs = state.predecessors + listOfNotNull(state.scope?.coroutineContext?.get(Job))
        return withTimeoutOrNull(timeoutMs) { jobs.joinAll(); true } ?: false
    }

    /**
     * 任务执行统计类
     */
    class TaskExecutionStats {
        private val startTime: Long = System.currentTimeMillis()
        private var endTime: Long = 0
        private val taskExecutionTimes: ConcurrentHashMap<String, Long> =
            ConcurrentHashMap<String, Long>()
        private val successCount = AtomicInteger(0)
        private val failureCount = AtomicInteger(0)
        private val skippedCount = AtomicInteger(0)

        fun recordTaskStart(taskName: String?) {
            taskName?.let { taskExecutionTimes.put(it, System.currentTimeMillis()) }
        }

        fun recordTaskEnd(taskName: String?, success: Boolean) {
            val startTime = taskExecutionTimes[taskName]
            if (startTime != null) {
                val executionTime = System.currentTimeMillis() - startTime
                if (success) {
                    successCount.incrementAndGet()
                    Log.debug("任务[" + taskName + "]执行成功，耗时: " + executionTime + "ms")
                } else {
                    failureCount.incrementAndGet()
                    Log.error("任务[" + taskName + "]执行失败，耗时: " + executionTime + "ms")
                }
            }
        }

        fun recordSkipped(taskName: String?) {
            skippedCount.incrementAndGet()
            Log.debug("任务[$taskName]被跳过")
        }

        fun complete() {
            this.endTime = System.currentTimeMillis()
        }

        @get:SuppressLint("DefaultLocale")
        val summary: String
            get() {
                val totalTime = endTime - startTime
                return String.format(
                    "任务执行统计 - 总耗时: %dms, 成功: %d, 失败: %d, 跳过: %d",
                    totalTime, successCount.get(), failureCount.get(), skippedCount.get()
                )
            }
    }

    open fun getTaskName(): String = id

    /**
     * 协程子任务类
     */
    open class ChildModelTask(
        val id: String,
        val group: String = "DEFAULT",
        private val suspendRunnable: (suspend () -> Unit)? = null,
        val execTime: Long = 0L,
        // 任务结束时的回调（isSuccess 代表是否正常执行完）
        var onCompleted: ((isSuccess: Boolean) -> Unit)? = null
    ) {
        internal val executionReport = TaskExecutionReport()
        var modelTask: ModelTask? = null
        internal var parentJob: Job? = null
        
        /** 协程任务Job */
        var job: Job? = null
        
        /** 是否已取消 */
        @Volatile
        var isCancelled: Boolean = false
            private set

        /** 外部调度器ID */
        private var schedulerId: Int = -1

        companion object {
            /** 存储当前正在等待（delay中）的子任务 */
            private val waitingTasks = ConcurrentHashMap<String, ChildModelTask>()

            /** 获取当前等待中的任务总数 */
            @JvmStatic
            fun getWaitingCount(): Int = waitingTasks.size

            /** 获取当前所有正在等待的任务列表 */
            @JvmStatic
            fun getWaitingTasks(): List<ChildModelTask> = waitingTasks.values.toList()
        }

        // 兼容构造函数
        constructor(id: String, runnable: Runnable?) : this(
            id = if (id.isEmpty()) "task-${System.currentTimeMillis()}" else id,
            group = "DEFAULT",
            suspendRunnable = runnable?.let { r -> { r.run() } },
            execTime = 0L
        )
        
        constructor(id: String, execTime: Long) : this(
            id = if (id.isEmpty()) "task-${System.currentTimeMillis()}" else id,
            group = "DEFAULT",
            suspendRunnable = null,
            execTime = execTime
        )
        
        // Java完全兼容的构造函数
        constructor(id: String, group: String, runnable: Runnable, execTime: Long) : this(
            id = if (id.isEmpty()) "task-${System.currentTimeMillis()}" else id,
            group = if (group.isEmpty()) "DEFAULT" else group,
            suspendRunnable = { runnable.run() },
            execTime = execTime
        )

        /**
         * 执行子任务
         */
        suspend fun run() {
            if (isCancelled) return
            var isSuccess = false
            val delayTime = execTime - System.currentTimeMillis()
            var isWaitingRegistered = false
            try {
                if (delayTime > 0) {
                    waitingTasks[id] = this
                    isWaitingRegistered = true

                    // 程序计时模式下注册空调度任务作为保活；系统计时模式只保留普通 delay 等待。
                    schedulerId = UnifiedScheduler.scheduleKeepAlive(delayTime, "WakeLock:$id")

                    // 公共子任务计时：等待调用方指定的 execTime 到点，不提前执行尚未到窗口的任务。
                    delay(delayTime)

                    waitingTasks.remove(id)
                    isWaitingRegistered = false
                }

                if (isCancelled) return
                if (!WorkflowRootGuard.isExecutionAllowed()) {
                    throw CancellationException("必需权限或使用协议未就绪，子任务已取消")
                }

                executionReport.ensureCurrent()
                if (BaseModel.modelSleepTime.isActive(System.currentTimeMillis())) {
                    Log.record("💤 模块休眠时间【${BaseModel.modelSleepTime.value}】停止执行子任务[${modelTask?.getName()}-$id]！")
                    isCancelled = true
                    return
                }
                // 执行任务逻辑
                suspendRunnable?.invoke() ?: defaultRun()
                isSuccess = true // 标记为成功执行
            } catch (_: CancellationException) {
                // 任务被取消是正常的协程控制流程，记录日志但不需要打印堆栈
                isCancelled = true
                val parentTaskName = modelTask?.getName() ?: "未知任务"
                Log.record("子任务被取消: $parentTaskName-$id")
                // 不重新抛出异常，让任务正常结束
                return
            } catch (e: Exception) {
                val parentTaskName = modelTask?.getName() ?: "未知任务"
                // 检查是否是协程取消相关的异常
                if (e.javaClass.name.contains("CancellationException") ||
                    e.message?.contains("cancelled") == true ||
                    e.message?.contains("StandaloneCoroutine") == true) {
                    isCancelled = true
                    Log.record("子任务协程被取消: $parentTaskName-$id - ${e.message}")
                    // 协程取消是正常现象，不需要打印堆栈
                    return
                } else {
                    Log.printStackTrace("run err: $parentTaskName-$id", e)
                    throw e
                }
            } finally {
                if (isWaitingRegistered) {
                    waitingTasks.remove(id, this)
                }
                if (schedulerId != -1) {
                    UnifiedScheduler.cancelTask(schedulerId)
                    schedulerId = -1
                }
                // 仅在成功执行完或明确被取消时回调，失败时不提示“已取消”
                if (isSuccess || isCancelled) {
                    onCompleted?.invoke(isSuccess)
                }
            }
        }

        /**
         * 默认执行逻辑
         */
        protected open suspend fun defaultRun() {
            Log.debug("子任务[$id]使用默认空实现运行")
        }

        /**
         * 取消子任务
         */
        fun cancel() {
            isCancelled = true
            job?.cancel()
            // 如果存在外部调度任务，一并取消
            if (schedulerId != -1) {
                UnifiedScheduler.cancelTask(schedulerId)
                schedulerId = -1
            }
        }
    }

    companion object {
        /** 日志标签 */
        private const val TAG = "ModelTask"

        /**
         * 读取未过期的任务级异常暂停表（任务名 → 恢复时间），顺手移除已过期项。
         */
        @JvmStatic
        fun activeTaskPauseMap(): Map<String, Long> = io.github.aoguai.sesameag.storage.StoreAccess.transaction {
            val runtimeInfo = RuntimeInfo.getInstance()
            val raw = runtimeInfo.getString(RuntimeInfo.RuntimeInfoKey.TaskPauseMap)
            if (raw.isBlank()) return@transaction emptyMap()
            val jo = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                return@transaction emptyMap()
            }
            val now = System.currentTimeMillis()
            val active = mutableMapOf<String, Long>()
            var expired = false
            for (key in jo.keys()) {
                val until = jo.optLong(key, 0L)
                if (until > now) {
                    active[key] = until
                } else {
                    expired = true
                }
            }
            if (expired) {
                runtimeInfo.put(RuntimeInfo.RuntimeInfoKey.TaskPauseMap, JSONObject(active).toString())
            }
            return@transaction active
        }
        
        /** 全局任务管理器协程作用域 */
        private val globalTaskScope = CoroutineScope(
            Dispatchers.Default + SupervisorJob() + CoroutineName("GlobalTaskManager")
        )

        /**
         * 停止所有任务（协程版本）
         */
        @JvmStatic
        fun stopAllTask() {
            modelArray.filterIsInstance<ModelTask>().forEach { model ->
                try {
                    model.stopTask()
                } catch (e: Exception) {
                    Log.printStackTrace("停止任务异常", e)
                }
            }
        }

        private val stoppingJobs = ConcurrentHashMap.newKeySet<Job>()

        /** 仅发出取消信号；所有父作用域（含独立子任务）退出后异步续接。 */
        internal fun stopAllTaskAndNotify(mainTask: ModelTask?, onStopped: () -> Unit): Boolean {
            mainTask?.stopTask()
            stopAllTask()
            val jobs = stoppingJobs.toList()
            if (jobs.all { it.isCompleted }) return true
            globalTaskScope.launch {
                jobs.joinAll()
                onStopped()
            }
            return false
        }

        @JvmStatic
        suspend fun stopAllTaskAndWait(timeoutMs: Long = 5_000L): Boolean {
            val runningJobs = mutableListOf<Job>()
            var allStopped = true
            for (model in modelArray) {
                if (model is ModelTask) {
                    val stopped = try {
                        val state = model.taskState.get()
                        val jobs = state.predecessors + listOfNotNull(state.scope?.coroutineContext?.get(Job))
                        model.stopTask()
                        runningJobs += jobs
                        true
                    } catch (e: Exception) {
                        Log.printStackTrace("停止任务异常", e)
                        false
                    }
                    if (!stopped) {
                        allStopped = false
                    }
                }
            }
            if (runningJobs.isEmpty()) {
                return allStopped
            }
            val joined = withTimeoutOrNull(timeoutMs) {
                runningJobs.forEach { job ->
                    try {
                        job.join()
                    } catch (_: CancellationException) {
                        // ignore cancellation raised by cooperative shutdown
                    }
                }
                true
            } ?: false
            return allStopped && joined
        }

    }
}


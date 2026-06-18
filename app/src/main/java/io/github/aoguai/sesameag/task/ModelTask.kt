package io.github.aoguai.sesameag.task

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.ModelType
import io.github.aoguai.sesameag.task.antForest.AntForest
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify.finishTaskRunning
import io.github.aoguai.sesameag.util.Notify.startTaskRunning
import io.github.aoguai.sesameag.util.Notify.updateRunningNextExec
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
 * - executionMutex: 执行互斥锁
 * - runCents: 任务运行次数计数器
 *
 * 使用方式：
 * 继承此类并实现抽象方法：getName(), getFields(), check(), run()
 *
 * @author Sesame-AG
 */
abstract class ModelTask : Model() {
    /** 任务协程作用域 */
    private var taskScope: CoroutineScope? = null
    
    /** 子任务映射表，存储当前任务的所有子任务 */
    private val childTaskMap: MutableMap<String, ChildModelTask> = ConcurrentHashMap()
    
    /** 执行互斥锁，防止重复执行 */
    private val executionMutex = Mutex()

    /** 当前已提交的启动 Job，覆盖“等待互斥锁”和“正在运行”两个阶段 */
    @Volatile
    private var currentStartJob: Job? = null

    private val startJobLock = Any()
    
    /** 任务运行次数计数器 */
    var runCents: Int = 0
        private set
    
    /** 任务是否正在运行 */
    @Volatile
    var isRunning = false
        protected set

    /** 增加任务运行次数 */
    fun addRunCents() {
        this.runCents += 1
    }

    /**
     * 准备任务执行环境
     */
    override fun prepare() {
        if (taskScope == null) {
            taskScope = CoroutineScope(
                Dispatchers.Default + 
                SupervisorJob() + 
                CoroutineName("ModelTask-${getName()}")
            )
        }
    }

    /**
     * 确保协程作用域初始化
     */
    private fun ensureTaskScope() {
        if (taskScope == null || !taskScope!!.isActive) {
            taskScope =
                CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("Task-$id"))
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
            "农场", "芭芭农场" -> Log.orchard(msg)
            "蚂蚁庄园" -> Log.farm(msg)
            "新村", "蚂蚁新村" -> Log.stall(msg)
            "海洋", "神奇海洋" -> Log.ocean(msg)
            "神奇物种" -> Log.dodo(msg)
            "会员" -> Log.member(msg)
            "福气鱼池" -> Log.fishpond(msg)
            "运动" -> Log.sports(msg)
            "绿色经营" -> Log.greenFinance(msg)
            "芝麻信用" -> Log.sesame(msg)
            else -> when (getGroup()) {
                ModelGroup.FOREST -> Log.forest(msg)
                ModelGroup.ORCHARD -> Log.orchard(msg)
                ModelGroup.FARM -> Log.farm(msg)
                ModelGroup.STALL -> Log.stall(msg)
                ModelGroup.DODO -> Log.dodo(msg)
                ModelGroup.FISHPOND -> Log.fishpond(msg)
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
        runSuspend()
    }

    /** 检查是否存在指定ID的子任务 */
    fun hasChildTask(childId: String): Boolean {
        return childTaskMap.containsKey(childId)
    }

    /**
     * 添加子任务（协程版本，内部使用）
     * @param childTask 要添加的子任务
     */
    private suspend fun addChildTaskSuspend(childTask: ChildModelTask) {
        ensureTaskScope()
        val childId = childTask.id
        
        // 取消已存在的同ID任务
        childTaskMap[childId]?.cancel()
        
        // 设置父任务引用
        childTask.modelTask = this
        childTaskMap[childTask.id] = childTask

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
        ensureTaskScope()
        taskScope!!.launch(start = CoroutineStart.UNDISPATCHED) {
            addChildTaskSuspend(childTask)
        }
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
    ): Job {
        ensureTaskScope()

        val startJob = synchronized(startJobLock) {
            val existingJob = currentStartJob
            if (!force && existingJob != null && !existingJob.isCompleted) {
                Log.record(TAG, "任务 ${getName()} 正在运行或等待启动，跳过重复启动")
                existingJob
            } else {
                taskScope!!.launch(start = CoroutineStart.LAZY) {
                    executionMutex.withLock {
                        if (isRunning && !force) {
                            Log.record(TAG, "任务 ${getName()} 正在运行，跳过启动")
                            return@withLock
                        }
                        if (isRunning && force) {
                            Log.record(TAG, "强制重启任务 ${getName()}")
                            stopTask()
                        }
                        if (!isEnable() || !check()) {
                            Log.record(TAG, "任务 ${getName()} 不满足执行条件")
                            return@withLock
                        }
                        val runningName = runningStatusName()
                        try {
                            isRunning = true
                            addRunCents()
                            startTaskRunning(runningName)
                            executeMultiRoundTask(rounds)
                        } catch (_: CancellationException) {
                            // 协程取消属于正常控制流程（如停止任务/切换用户），不视为错误
                            Log.record(TAG, "任务被取消: ${getName()}")
                        } catch (e: Exception) {
                            Log.printStackTrace("startTask err: ${getName()}", e)
                        } finally {
                            isRunning = false
                            finishTaskRunning(runningName)
                            updateRunningNextExec(-1)
                        }
                    }
                }.also { job ->
                    currentStartJob = job
                    job.invokeOnCompletion {
                        synchronized(startJobLock) {
                            if (currentStartJob === job) {
                                currentStartJob = null
                            }
                        }
                    }
                }
            }
        }
        startJob.start()
        return startJob
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
            stats.recordTaskEnd("${getName()}-Round$round", true)
        } catch (_: CancellationException) {
            // 本轮被取消，记录为跳过而非失败
            stats.recordSkipped("${getName()}-Round$round")
            Log.debug(TAG, "任务本轮被取消: ${getName()}-Round$round")
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
        // 立即标记为非运行状态
        isRunning = false

        // 取消协程作用域（这会自动取消所有子协程）
        taskScope?.cancel()
        taskScope = null

        try {
            childTaskMap.values.forEach { childTask ->
                try {
                    childTask.cancel()
                } catch (e: Exception) {
                    Log.printStackTrace("stopTask err", e)
                }
            }
        } finally {
            childTaskMap.clear()
        }
    }

    suspend fun stopTaskAndWait(timeoutMs: Long = 5_000L): Boolean {
        val runningJob = synchronized(startJobLock) { currentStartJob }
        stopTask()
        if (runningJob == null) {
            return true
        }
        return withTimeoutOrNull(timeoutMs) {
            try {
                runningJob.join()
                true
            } catch (_: CancellationException) {
                true
            }
        } ?: false
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
        var modelTask: ModelTask? = null
        
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

                    delay(delayTime)

                    waitingTasks.remove(id)
                    isWaitingRegistered = false
                }

                if (isCancelled) return

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
        
        /** 全局任务管理器协程作用域 */
        private val globalTaskScope = CoroutineScope(
            Dispatchers.Default + SupervisorJob() + CoroutineName("GlobalTaskManager")
        )

        /**
         * 停止所有任务（协程版本）
         */
        @JvmStatic
        fun stopAllTask() {
            globalTaskScope.launch {
                for (model in modelArray) {
                    if (model is ModelTask) {
                        try {
                            model.stopTask()
                        } catch (e: Exception) {
                            Log.printStackTrace("停止任务异常", e)
                        }
                    }
                }
            }
        }

        @JvmStatic
        suspend fun stopAllTaskAndWait(timeoutMs: Long = 5_000L): Boolean {
            val runningJobs = mutableListOf<Job>()
            var allStopped = true
            for (model in modelArray) {
                if (model is ModelTask) {
                    val stopped = try {
                        val runningJob = synchronized(model.startJobLock) { model.currentStartJob }
                        model.stopTask()
                        if (runningJob != null) {
                            runningJobs += runningJob
                        }
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


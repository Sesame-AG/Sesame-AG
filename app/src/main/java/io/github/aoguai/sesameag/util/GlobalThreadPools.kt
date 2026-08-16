package io.github.aoguai.sesameag.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * @author: ghostxx
 * @date: 2025/9/17
 * @description: 全局协程调度器，用于统一管理应用内的协程，提供结构化并发和生命周期管理。
 * @update: 修复了 R8 编译器在处理 Java 兼容方法时的元数据崩溃问题
 */
object GlobalThreadPools {
    private const val TAG = "GlobalThreadPools"

    /**
     * CPU核心数
     */
    private val CPU_COUNT = Runtime.getRuntime().availableProcessors()

    /**
     * 计算密集型任务并行度
     * 根据CPU核心数动态计算，保证最佳性能。
     */
    private val COMPUTE_PARALLELISM = max(2, min(CPU_COUNT - 1, 4))

    /**
     * 创建一个新的协程作用域。
     * 这是一个私有辅助函数，用于在初始化和重置时创建作用域。
     */
    private fun createScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() +
                Dispatchers.Default +
                CoroutineName("SesameGlobalScope")
    )

    /**
     * 全局协程作用域
     * 用于启动不绑定到特定生命周期的长寿命协程
     * 使用 @Volatile 以支持在会话切换时进行重置
     */
    @Volatile
    private var globalScope = createScope()

    /**
     * 计算密集型任务调度器
     * 适用于CPU密集型操作，如复杂计算、数据处理等
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val computeDispatcher = Dispatchers.Default.limitedParallelism(COMPUTE_PARALLELISM)

    /**
     * 在全局协程作用域中执行一个任务 (Kotlin 专用)。
     *
     * @param block 要执行的挂起函数代码块
     * @param context 可选的协程上下文，默认使用计算调度器
     * @return 代表任务的Job对象
     */
    fun execute(
        context: CoroutineContext = computeDispatcher,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return globalScope.launch(context) {
            try {
                block()
            } catch (_: CancellationException) {
                // 协程取消异常，正常流程，不记录
            } catch (e: Exception) {
                Log.error(TAG, "执行任务异常: ${e.message}")
                Log.printStackTrace(e)
            }
        }
    }

    /**
     * 提交一个可返回结果的任务 (Kotlin 专用)。
     *
     * @param T 结果类型
     * @param context 可选的协程上下文，默认使用计算调度器
     * @param block 要执行的挂起函数代码块，返回类型为T
     * @return 代表任务结果的Deferred对象
     */
    fun <T> submit(
        context: CoroutineContext = computeDispatcher,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> {
        val deferred = globalScope.async(context) {
            block()
        }
        observeUnhandledCompletion(deferred)
        return deferred
    }

    /**
     * 兼容Java的执行方法
     * 【修复】直接调用 launch，而不是嵌套调用 execute(block)，避免 R8 元数据错误
     *
     * @param command 要执行的Runnable任务
     * @return 代表任务的Job对象
     */
    @JvmOverloads
    fun execute(command: Runnable?, context: CoroutineContext = computeDispatcher): Job {
        return globalScope.launch(context) {
            try {
                command?.run()
            } catch (_: CancellationException) {
                // 忽略
            } catch (e: Exception) {
                Log.error(TAG, "Java任务执行异常: ${e.message}")
                Log.printStackTrace(e)
            }
        }
    }

    /**
     * 兼容Java的提交方法
     * 【修复】直接调用 async，而不是嵌套调用 submit(block)
     *
     * @param task 要提交的Runnable任务
     * @return 代表任务的Deferred对象
     */
    @JvmOverloads
    fun submit(task: Runnable?, context: CoroutineContext = computeDispatcher): Deferred<Unit> {
        val deferred = globalScope.async(context) {
            task?.run()
            Unit
        }
        observeUnhandledCompletion(deferred)
        return deferred
    }

    private fun observeUnhandledCompletion(job: Job) {
        job.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                Log.error(TAG, "提交任务执行异常: ${cause.javaClass.simpleName}")
            }
        }
    }

    /**
     * 关闭并重启全局协程作用域。
     * 主要用于用户切换账号等需要彻底清理会话资源的场景。
     */
    @Synchronized
    fun shutdownAndRestart() {
        Log.record(TAG, "正在关闭并重启全局协程池...")
        if (globalScope.isActive) {
            globalScope.cancel("User session changed. Resetting coroutine scope.")
        }
        globalScope = createScope()
        Log.record(TAG, "全局协程池已重置。")
    }

    /**
     * 协程兼容的暂停方法
     */
    @JvmStatic
    fun sleepCompat(millis: Long) {
        CoroutineUtils.sleepCompat(millis)
    }
}

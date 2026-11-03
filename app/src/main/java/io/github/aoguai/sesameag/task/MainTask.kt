package io.github.aoguai.sesameag.task

import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主任务类 - 用于 ApplicationHook 中的主任务调度
 * 
 * 这是一个轻量级的 ModelTask 实现，用于替代原来的 BaseTask。
 * 使用协程而不是传统线程，提供更好的性能和资源管理。
 * 
 * @param taskId 任务ID
 * @param taskRunnable 要执行的任务逻辑
 */
class MainTask(
    private val taskId: String,
    private val taskRunnable: suspend () -> Unit
) : ModelTask() {
    
    init {
        // 主任务始终启用，不受配置控制
        enableField.value = true
    }
    
    companion object {
        private const val TAG = "MainTask"
        
        /**
         * 工厂方法：从 Java Runnable 创建 MainTask

         * 
         * @param id 任务ID
         * @param runnable Java Runnable 对象
         * @return MainTask 实例
         */
        @JvmStatic
        fun newInstance(id: String, runnable: Runnable): MainTask {
            return MainTask(id) {
                withContext(Dispatchers.IO) {
                    runnable.run()
                }
            }
        }

    }
    
    /**
     * 获取任务名称
     */
    override fun getName(): String {
        return taskId
    }
    
    /**
     * 获取任务所属组（主任务属于基础组）
     */
    override fun getGroup(): ModelGroup {
        return ModelGroup.BASE
    }
    
    /**
     * 获取任务图标（主任务使用默认图标）
     */
    override fun getIcon(): String {
        return "default.svg"
    }
    
    /**
     * 获取字段配置（主任务不需要配置字段）
     */
    override fun getFields(): ModelFields? {
        return null
    }
    
    /**
     * 检查任务是否可以执行
     * 主任务始终返回 true
     */
    override fun check(): Boolean {
        return true
    }
    
    /**
     * 执行任务逻辑（协程版本）
     */
    override suspend fun runSuspend() {
        try {
            taskRunnable()
        } catch (e: CancellationException) {
            // 协程取消是正常的控制流，不应该捕获，需要重新抛出
            Log.record(TAG, "任务被取消: $taskId")
            throw e // 重新抛出 CancellationException 以保持协程取消语义
        } catch (e: Exception) {
            // 只捕获真正的异常，不捕获 CancellationException
            Log.printStackTrace(TAG, "主任务执行异常", e)
        }
    }
    
    /**
     * Java 兼容的 startTask 方法
     */
    fun startTask(force: Boolean) {
        startTask(force, 1)
    }
}

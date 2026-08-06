package io.github.aoguai.sesameag.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 协程工具类
 *
 * 提供协程相关的通用功能，用于替代传统的线程操作
 */
object CoroutineUtils {

    /**
     * 兼容性延迟方法（同步版本）
     *
     * 在当前线程中执行延迟，自动处理协程和非协程环境
     *
     * @param millis 延迟毫秒数
     */
    @JvmStatic
    fun sleepCompat(millis: Long) {
        try {
            runBlocking {
                delay(millis)
            }
        } catch (e: Exception) {
            // 降级到传统的 Thread.sleep()
            Log.printStackTrace("协程延迟异常,已尝试降级到 Thread.sleep()", e)
            try {
                Thread.sleep(millis)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.record("CoroutineUtils", "延迟被中断: ${ie.message}")
            }
        }
    }

    /**
     * 同步执行协程代码块
     *
     * 警告：此方法会阻塞当前线程，仅在必要时使用
     */
    @JvmStatic
    fun <T> runBlockingSafe(
        timeout: Long = 30000, // 30秒默认超时
        block: suspend CoroutineScope.() -> T
    ): T? {
        return try {
            runBlocking {
                withTimeout(timeout) {
                    block()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.error("CoroutineUtils", "协程执行超时: ${timeout}ms")
            null
        } catch (e: Exception) {
            Log.printStackTrace("协程同步执行异常", e)
            null
        }
    }
}


package io.github.aoguai.sesameag.util

import kotlinx.coroutines.CancellationException
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
     * 在当前线程中执行延迟，自动处理协程和非协程环境。
     * 保留通用暂停适配，时长和业务理由由调用方决定；runBlocking 会阻塞调用线程。
     *
     * @param millis 延迟毫秒数
     */
    @JvmStatic
    fun sleepCompat(millis: Long) {
        try {
            runBlocking {
                delay(millis)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 协程暂停异常时由传统线程暂停承接同一 millis 请求；正常路径不会重复执行此等待。
            Log.printStackTrace("协程延迟异常,已尝试降级到 Thread.sleep()", e)
            try {
                Thread.sleep(millis)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ie
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
                // 同步桥接必须有执行超时上限，防止调用线程永久阻塞；代码块完成后立即返回。
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


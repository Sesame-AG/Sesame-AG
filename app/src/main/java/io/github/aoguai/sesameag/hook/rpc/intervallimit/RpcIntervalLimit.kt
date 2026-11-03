package io.github.aoguai.sesameag.hook.rpc.intervallimit

import io.github.aoguai.sesameag.util.SerialTaskQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.aoguai.sesameag.util.Log
import java.util.concurrent.ConcurrentHashMap

object RpcIntervalLimit {
    private const val TAG = "RpcIntervalLimit"
    private const val DEFAULT_INTERVAL = 425
    private val DEFAULT_INTERVAL_LIMIT = FixedOrRangeIntervalLimit("425-500", DEFAULT_INTERVAL, 500)
    private val intervalLimitMap = ConcurrentHashMap<String, IntervalLimit>()
    private val dispatchQueues = ConcurrentHashMap<IntervalLimit, SerialTaskQueue>()

    /**
     * 为指定方法添加间隔限制。
     *
     * @param method 方法名称
     * @param interval 间隔时间（毫秒）
     */
    fun addIntervalLimit(method: String, interval: Int) {
        addIntervalLimit(method, DefaultIntervalLimit(interval))
    }

    /**
     * 为指定方法添加自定义间隔限制对象。
     *
     * @param method 方法名称
     * @param intervalLimit 自定义的间隔限制对象
     */
    fun addIntervalLimit(method: String, intervalLimit: IntervalLimit) {
        if (intervalLimitMap.putIfAbsent(method, intervalLimit) != null) {
            Log.record(TAG, "方法：$method 间隔限制已存在")
            throw IllegalArgumentException("方法：$method 间隔限制已存在")
        }
    }

    /**
     * 更新指定方法的间隔限制。
     *
     * @param method 方法名称
     * @param interval 新的间隔时间（毫秒）
     */
    fun updateIntervalLimit(method: String, interval: Int) {
        updateIntervalLimit(method, DefaultIntervalLimit(interval))
    }

    /**
     * 更新指定方法的间隔限制对象。
     *
     * @param method 方法名称
     * @param intervalLimit 新的自定义间隔限制对象
     */
    fun updateIntervalLimit(method: String, intervalLimit: IntervalLimit) {
        intervalLimitMap[method] = intervalLimit
    }

    /**
     * 进入指定方法的间隔限制，确保调用间隔时间不小于设定值。
     *
     * @param method 方法名称
     */
    fun <T> dispatch(method: String, send: () -> T): T {
        val intervalLimit = intervalLimitMap.getOrDefault(method, DEFAULT_INTERVAL_LIMIT)
        return runBlocking {
            dispatchQueues.computeIfAbsent(intervalLimit) { SerialTaskQueue() }.submit {
                val interval = intervalLimit.interval ?: DEFAULT_INTERVAL_LIMIT.interval
                val remaining = interval - (System.currentTimeMillis() - intervalLimit.time)
                // 公共 RPC 限速：只补足当前方法配置间隔的剩余时间，避免同一限速队列连续突发请求。
                if (remaining > 0) delay(remaining)
                intervalLimit.time = System.currentTimeMillis()
                send()
            }
        }
    }

    /**
     * 清除所有方法的间隔限制。
     */
    fun clearIntervalLimit() {
        intervalLimitMap.clear()
    }
}

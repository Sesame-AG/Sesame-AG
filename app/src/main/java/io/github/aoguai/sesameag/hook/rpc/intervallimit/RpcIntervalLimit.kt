package io.github.aoguai.sesameag.hook.rpc.intervallimit

import io.github.aoguai.sesameag.util.SerialTaskQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.aoguai.sesameag.util.Log
import java.util.concurrent.ConcurrentHashMap

object RpcIntervalLimit {
    private const val TAG = "RpcIntervalLimit"
    private const val DEFAULT_INTERVAL = 425

    // 拟人节律采样参数：中位数 = max(基础间隔 * 3, 1200ms)，sigma=0.7 的长尾
    private const val HUMAN_PACE_MEDIAN_FACTOR = 3.0
    private const val HUMAN_PACE_MIN_BASE_MS = 300
    private const val HUMAN_PACE_MIN_MEDIAN_MS = 1200.0
    private const val HUMAN_PACE_SIGMA = 0.7
    private const val HUMAN_PACE_GAUSS_SCALE = 1.1547
    private const val HUMAN_PACE_MIN_INTERVAL_MS = 600L
    private const val HUMAN_PACE_MAX_INTERVAL_MS = 45_000L
    private val DEFAULT_INTERVAL_LIMIT = FixedOrRangeIntervalLimit("425-500", DEFAULT_INTERVAL, 500)
    private val intervalLimitMap = ConcurrentHashMap<String, IntervalLimit>()
    private val dispatchQueues = ConcurrentHashMap<IntervalLimit, SerialTaskQueue>()

    /** 当前线程是否已经在限速队列内，用于识别 send() 内的同 key 重入。 */
    private val dispatchDepth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

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
        // 同一限速 key 在 send() 里重入会等待自己这次的 completion，runBlocking 下直接死锁。
        // 重入时按"已经在队列里"处理：补足间隔后直接执行，不再排队。
        if ((dispatchDepth.get() ?: 0) > 0) {
            val configuredInterval = intervalLimit.interval ?: DEFAULT_INTERVAL_LIMIT.interval
            val remaining = configuredInterval - (System.currentTimeMillis() - intervalLimit.time)
            if (remaining > 0) {
                runBlocking { delay(remaining) }
            }
            intervalLimit.time = System.currentTimeMillis()
            return send()
        }
        return runBlocking {
            val previousDepth = dispatchDepth.get() ?: 0
            dispatchDepth.set(previousDepth + 1)
            try {
                dispatchQueues.computeIfAbsent(intervalLimit) { SerialTaskQueue() }.submit {
                    val configuredInterval = intervalLimit.interval ?: DEFAULT_INTERVAL_LIMIT.interval
                    // 拟人节律开启时按对数正态采样：中位数约为配置间隔的3倍，长尾可到几十秒。
                    val interval = if (io.github.aoguai.sesameag.model.BaseModel.humanPace.value == true) {
                        sampleHumanPaceInterval(configuredInterval)
                    } else {
                        configuredInterval
                    }
                    val remaining = interval - (System.currentTimeMillis() - intervalLimit.time)
                    // 公共 RPC 限速：只补足当前方法配置间隔的剩余时间，避免同一限速队列连续突发请求。
                    if (remaining > 0) delay(remaining)
                    intervalLimit.time = System.currentTimeMillis()
                    send()
                }
            } finally {
                dispatchDepth.set(previousDepth)
            }
        }
    }

    /**
     * 人类操作间隔不是固定值：多数在 1~3 秒，偶尔几秒到几十秒。
     * 用对数正态采样近似这种长尾分布，避免"固定 425-500ms 匀速"成为机器特征。
     */
    private fun sampleHumanPaceInterval(baseIntervalMs: Int): Int {
        val median = (baseIntervalMs.coerceAtLeast(HUMAN_PACE_MIN_BASE_MS).toDouble() * HUMAN_PACE_MEDIAN_FACTOR)
            .coerceAtLeast(HUMAN_PACE_MIN_MEDIAN_MS)
        // 三个均匀分布之和近似标准正态（Irwin–Hall），避免额外依赖。
        val gauss = (kotlin.random.Random.nextDouble() + kotlin.random.Random.nextDouble() +
            kotlin.random.Random.nextDouble() - 1.5) * HUMAN_PACE_GAUSS_SCALE
        val sampled = median * kotlin.math.exp(HUMAN_PACE_SIGMA * gauss)
        return sampled.toLong()
            .coerceIn(HUMAN_PACE_MIN_INTERVAL_MS, HUMAN_PACE_MAX_INTERVAL_MS)
            .toInt()
    }

    /**
     * 清除所有方法的间隔限制。
     */
    fun clearIntervalLimit() {
        intervalLimitMap.clear()
    }
}

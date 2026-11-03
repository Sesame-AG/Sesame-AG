package io.github.aoguai.sesameag.hook.rpc.intervallimit

import java.util.concurrent.ThreadLocalRandom

/**
 * 为已有的 [IntervalLimit] 增加随机区间内的最小间隔兜底。
 *
 * 典型场景：用户配置的区间过小导致访问过快触发风控，但仍希望保留可配置性。
 */
class MinIntervalLimit(
    private val delegate: IntervalLimit,
    private val minIntervalMs: Int,
    private val maxIntervalMs: Int
) : IntervalLimit {

    init {
        require(minIntervalMs > 0 && maxIntervalMs > minIntervalMs)
    }

    override val interval: Int?
        get() {
            val minimum = ThreadLocalRandom.current().nextLong(minIntervalMs.toLong(), maxIntervalMs.toLong() + 1).toInt()
            return maxOf(delegate.interval ?: minimum, minimum)
        }

    override var time: Long = 0
}



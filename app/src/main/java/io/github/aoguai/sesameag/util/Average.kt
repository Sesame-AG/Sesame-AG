package io.github.aoguai.sesameag.util

/** 平均值计算工具类 */
class Average(size: Int) {

    /** 使用一个循环队列来存储固定数量的数值 */
    private val queue: CircularFifoQueue<Int> = CircularFifoQueue(size)

    /** 数值的总和，用于计算平均值 */
    private var sum: Double = 0.0

    /** 当前的平均值 */
    private var average: Double = 0.0

    /**
     * 计算下一个数值加入后的新平均值
     *
     * @param value 新加入的数值
     * @return 当前的平均值
     */
    fun nextDouble(value: Int): Double {
        // 将新值添加到队列中，并移除队列中的旧值（如果有的话）
        val last: Int? = queue.push(value)
        // 如果队列中有旧值，则从总和中减去它
        if (last != null) {
            sum -= last.toDouble()
        }
        // 将新值加入到总和中
        sum += value.toDouble()
        // 计算并返回新的平均值
        average = sum / queue.size
        return average
    }

    /**
     * 计算下一个数值加入后的新平均值（返回整数）
     *
     * @param value 新加入的数值
     * @return 当前的平均值（整数）
     */
    fun nextInteger(value: Int): Int {
        return nextDouble(value).toInt()
    }

    /**
     * 获取当前的平均值（浮动型）
     *
     * @return 当前的平均值
     */
    fun averageDouble(): Double {
        return average
    }

    /**
     * 获取当前的平均值（整数型）
     *
     * @return 当前的平均值（整数）
     */
    fun getAverageInteger(): Int {
        return average.toInt()
    }

    /**
     * 清除队列和重置所有统计数据
     */
    fun clear() {
        queue.clear()
        sum = 0.0
        average = 0.0
    }
}



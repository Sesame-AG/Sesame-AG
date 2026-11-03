package io.github.aoguai.sesameag.entity

/**
 * 表示目标应用版本的实体类，可进行版本比较。
 */
class AlipayVersion(val versionString: String) : Comparable<AlipayVersion> {

    // 版本号列表，用于比较
    // 解析版本字符串
    // Kotlin 的 split(String) 默认按字面量分割，不需要正则转义
    private val versionParts: List<Int> = versionString.split(".").map { part ->
        part.toIntOrNull() ?: Int.MAX_VALUE
    }

    /**
     * 实现版本比较逻辑。
     * @param other 需要比较的另一个 AlipayVersion 实例
     * @return -1 表示当前版本小于对比版本，1 表示大于，0 表示相等
     */
    override fun compareTo(other: AlipayVersion): Int {
        val thisSize = versionParts.size
        val thatSize = other.versionParts.size

        // 如果前面都相等，这就作为最终结果 (长度长的版本更大)
        // 例如: 1.0 vs 1.0.1 -> 1.0.1 更大
        val lengthCompare = thisSize.compareTo(thatSize)

        val minLength = minOf(thisSize, thatSize)

        // 逐段比较
        for (i in 0 until minLength) {
            val thisPart = versionParts[i]
            val thatPart = other.versionParts[i]
            if (thisPart != thatPart) {
                return thisPart.compareTo(thatPart)
            }
        }

        // 如果所有对应段都相等，返回长度比较结果
        return lengthCompare
    }

    override fun toString(): String {
        return versionString
    }
}

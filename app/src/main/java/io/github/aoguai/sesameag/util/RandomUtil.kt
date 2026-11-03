package io.github.aoguai.sesameag.util

import kotlin.random.Random

/**
 * 随机数工具类，提供生成随机数和随机字符串的方法。
 *
 * **迁移说明**:
 * - Kotlin代码推荐使用 `kotlin.random.Random` 的扩展函数
 * - Java代码可继续使用 `RandomUtil.xxx()` 方法
 */
object RandomUtil {

    /**
     * 生成一个随机延迟时间（100到300毫秒之间）。
     *
     * @return 生成的随机延迟时间（毫秒）
     */
    @JvmStatic
    fun delay(): Int = nextInt(100, 300)

    /**
     * 生成一个指定范围内的随机整数。
     *
     * @param min 最小值（包含）
     * @param max 最大值（不包含）
     * @return 生成的随机整数
     */
    @JvmStatic
    fun nextInt(min: Int, max: Int): Int {
        if (min >= max) return min
        return Random.nextInt(min, max)
    }

    /**
     * 生成一个随机的长整数。
     *
     * @return 生成的随机长整数
     */
    @JvmStatic
    fun nextLong(): Long = Random.nextLong()

    /**
     * 生成一个指定范围内的随机长整数。
     *
     * **Bug修复**: 原Java实现使用模运算可能产生负数，已修复
     *
     * @param min 最小值（包含）
     * @param max 最大值（不包含）
     * @return 生成的随机长整数
     */
    @JvmStatic
    fun nextLong(min: Long, max: Long): Long {
        if (min >= max) return min
        return Random.nextLong(min, max)
    }

    /**
     * 生成一个随机的双精度浮点数（0.0到1.0之间）。
     *
     * @return 生成的随机双精度浮点数
     */
    @JvmStatic
    fun nextDouble(): Double = Random.nextDouble()

    /**
     * 生成一个指定长度的随机数字字符串。
     *
     * @param len 随机字符串的长度
     * @return 生成的随机数字字符串
     */
    @JvmStatic
    fun getRandomInt(len: Int): String {
        return buildString {
            repeat(len) {
                append(Random.nextInt(10))
            }
        }
    }

    /**
     * 生成一个指定长度的随机字符串，包含小写字母和数字。
     *
     * @param length 随机字符串的长度
     * @return 生成的随机字符串
     */
    @JvmStatic
    fun getRandomString(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(length) {
                append(chars[Random.nextInt(chars.length)])
            }
        }
    }

    /**
     * 生成一个随机标签（时间戳+随机字符串）。
     *
     * @return 生成的随机标签
     */
    @JvmStatic
    fun getRandomTag(): String {
        return "_${System.currentTimeMillis()}_${getRandomString(8)}"
    }
}


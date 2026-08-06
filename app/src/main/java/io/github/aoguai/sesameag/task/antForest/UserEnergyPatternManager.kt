package io.github.aoguai.sesameag.task.antForest

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户能量收取模式数据类
 * 用于分析用户的能量收取习惯，但不影响蹲点时机
 */
data class UserEnergyPattern(
    val userId: String,
    val collectSuccessRate: Double = 0.8, // 收取成功率
    val avgResponseTime: Long = 1000L,    // 平均响应时间
    val lastCollectTime: Long = 0L,       // 上次收取时间
    val isActiveUser: Boolean = true      // 是否活跃用户
)

/**
 * 用户能量模式管理器
 * 单一职责：管理用户的能量收取模式和统计数据
 */
object UserEnergyPatternManager {
    // 用户模式存储
    private val userPatterns = ConcurrentHashMap<String, UserEnergyPattern>()

    /**
     * 获取用户模式
     */
    fun getUserPattern(userId: String): UserEnergyPattern {
        return userPatterns[userId] ?: UserEnergyPattern(userId)
    }

    /**
     * 更新用户模式（基于收取结果）
     */
    @SuppressLint("DefaultLocale")
    fun updateUserPattern(userId: String, result: CollectResult, responseTime: Long) {
        val currentPattern = getUserPattern(userId)
        val currentTime = System.currentTimeMillis()

        // 使用指数移动平均更新成功率
        val alpha = 0.1
        val newSuccessRate = if (result.success) {
            currentPattern.collectSuccessRate * (1 - alpha) + alpha
        } else {
            currentPattern.collectSuccessRate * (1 - alpha)
        }

        // 更新平均响应时间
        val newAvgResponseTime = if (responseTime > 0) {
            (currentPattern.avgResponseTime * 0.8 + responseTime * 0.2).toLong()
        } else {
            currentPattern.avgResponseTime
        }

        // 判断用户活跃度（24小时内有活动）
        val timeSinceLastCollect = currentTime - currentPattern.lastCollectTime
        val isActive = timeSinceLastCollect < 24 * 60 * 60 * 1000L

        val updatedPattern = currentPattern.copy(
            collectSuccessRate = newSuccessRate,
            avgResponseTime = newAvgResponseTime,
            lastCollectTime = if (result.success) currentTime else currentPattern.lastCollectTime,
            isActiveUser = isActive
        )

        userPatterns[userId] = updatedPattern
         Log.forest("更新用户[${userId}]模式：成功率[${String.format("%.2f", newSuccessRate)}] 响应时间[${newAvgResponseTime}ms] 活跃[${isActive}]")
    }

    /**
     * 清理过期的用户模式数据
     */
    fun cleanupExpiredPatterns() {
        val currentTime = System.currentTimeMillis()
        val expireTime = 30 * 24 * 60 * 60 * 1000L // 30天

        val expiredUsers = userPatterns.filter { (_, pattern) ->
            currentTime - pattern.lastCollectTime > expireTime
        }.keys

        expiredUsers.forEach { userId ->
            userPatterns.remove(userId)
        }

        if (expiredUsers.isNotEmpty()) {
             Log.forest("清理过期用户模式数据：${expiredUsers.size}个用户")
        }
    }
}

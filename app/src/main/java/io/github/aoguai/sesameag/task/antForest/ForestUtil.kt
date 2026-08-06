@file:JvmName("ForestUtil")

package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object ForestUtil {

    /**
     * 用户频率限制信息
     * @param failCount 失败次数（1-3）
     * @param cooldownUntil 冷却结束时间（毫秒时间戳）
     */
    private data class FrequencyLimitInfo(
        var failCount: Int = 0,
        var cooldownUntil: Long = 0L
    )

    /**
     * 存储每个用户的频率限制信息
     * Key: userId, Value: FrequencyLimitInfo
     */
    private val userFrequencyMap = ConcurrentHashMap<String, FrequencyLimitInfo>()

    /**
     * 检查用户是否在"手速太快"冷却期中
     * @param userId 用户ID
     * @return true表示在冷却期，应该跳过；false表示可以处理
     */
    @JvmStatic
    fun isUserInFrequencyCooldown(userId: String?): Boolean {
        if (userId == null) return false

        val info = userFrequencyMap[userId] ?: return false
        val currentTime = System.currentTimeMillis()
        if (currentTime < info.cooldownUntil) {
            val remainingMinutes = (info.cooldownUntil - currentTime) / 60000
            val remainingSeconds = ((info.cooldownUntil - currentTime) % 60000) / 1000
            Log.forest("[${UserMap.getMaskName(userId)}] 手速太快冷却中，还需等待 ${remainingMinutes}分${remainingSeconds}秒")
            return true
        }
        // 冷却期结束，清除记录
        if (info.cooldownUntil > 0) {
            userFrequencyMap.remove(userId)
            Log.forest("[${UserMap.getMaskName(userId)}] 冷却期结束，恢复正常处理")
        }
        return false
    }

    /**
     * 检测是否为"手速太快"相关错误
     * @param resultCode 返回码
     * @param resultDesc 返回描述
     * @return true表示是频率限制错误，false表示不是
     */
    @JvmStatic
    fun isFrequencyError(resultCode: String?, resultDesc: String?): Boolean {
        if (resultCode == null && resultDesc == null) return false

        return resultCode == "PLUGIN_FREQUENCY_INTERCEPT" ||
                resultDesc?.contains("手速太快") == true ||
                resultDesc?.contains("频繁") == true ||
                resultDesc?.contains("操作过于频繁") == true
    }

    /**
     * 检测 JSONObject 是否为"手速太快"相关错误
     * @param jo JSON对象
     * @return true表示是频率限制错误，false表示不是
     */
    @JvmStatic
    fun isFrequencyError(jo: JSONObject?): Boolean {
        if (jo == null) return false
        val resultCode = jo.optString("resultCode", "")
        val resultDesc = jo.optString("resultDesc", "")
        return isFrequencyError(resultCode, resultDesc)
    }

    /**
     * 记录用户"手速太快"错误，并设置相应的冷却时间
     * @param userId 用户ID
     * @return true表示记录成功，false表示参数无效
     */
    @JvmStatic
    fun recordFrequencyError(userId: String?): Boolean {
        if (userId == null) return false
        val userName = UserMap.getMaskName(userId)
        val currentTime = System.currentTimeMillis()
        val info = userFrequencyMap.getOrPut(userId) { FrequencyLimitInfo() }
        // 增加失败次数
        info.failCount++
        // 根据失败次数设置冷却时间
        val cooldownMinutes = when (info.failCount) {
            1 -> {
                info.cooldownUntil = currentTime + 2 * 60 * 1000L  // 2分钟
                2
            }
            2 -> {
                info.cooldownUntil = currentTime + 10 * 60 * 1000L  // 10分钟
                10
            }
            else -> {
                info.cooldownUntil = currentTime + 30 * 60 * 1000L  // 30分钟
                30
            }
        }

        Log.forest("⚠️ [$userName] 手速太快！第${info.failCount}次异常，休息${cooldownMinutes}分钟，下次暂不处理")

        return true
    }

    /**
     * 检测并记录"手速太快"错误（组合方法）
     * @param userId 用户ID
     * @param jo JSON对象
     * @return true表示检测到频率错误并已记录，false表示不是频率错误
     */
    @JvmStatic
    fun checkAndRecordFrequencyError(userId: String?, jo: JSONObject?): Boolean {
        if (isFrequencyError(jo)) {
            recordFrequencyError(userId)
            return true
        }
        return false
    }

    /**
     * 清除所有用户的频率限制记录（用于任务结束时清理）
     */
    @JvmStatic
    fun clearAllFrequencyLimits() {
        val count = userFrequencyMap.size
        if (count > 0) {
            userFrequencyMap.clear()
            Log.forest("已清除${count}个用户的频率限制记录")
        }
    }

    /**
     * 手动清除指定用户的频率限制（用于测试或手动恢复）
     * @param userId 用户ID
     */
    @JvmStatic
    fun clearUserFrequencyLimit(userId: String?) {
        if (userId != null && userFrequencyMap.remove(userId) != null) {
            Log.forest("[${UserMap.getMaskName(userId)}] 频率限制已清除")
        }
    }

    @JvmStatic
    fun hasShield(userHomeObj: JSONObject, serverTime: Long): Boolean {
        return hasPropGroup(userHomeObj, "shield", serverTime)
    }

    @JvmStatic
    fun hasBombCard(userHomeObj: JSONObject, serverTime: Long): Boolean {
        return hasPropGroup(userHomeObj, "energyBombCard", serverTime)
    }

    /**
     * 获取保护罩结束时间
     * @param userHomeObj 用户主页数据
     * @return 保护罩结束时间戳，如果没有保护罩则返回0
     */
    @JvmStatic
    fun getShieldEndTime(userHomeObj: JSONObject): Long {
        return getPropGroupEndTime(userHomeObj, "shield")
    }

    /**
     * 获取炸弹卡结束时间
     * @param userHomeObj 用户主页数据
     * @return 炸弹卡结束时间戳，如果没有炸弹卡则返回0
     */
    @JvmStatic
    fun getBombCardEndTime(userHomeObj: JSONObject): Long {
        return getPropGroupEndTime(userHomeObj, "energyBombCard")
    }

    /**
     * 获取用户的保护结束时间（取保护罩和炸弹卡中最晚的时间）
     * @param userHomeObj 用户主页数据
     * @return 保护结束时间戳，如果都没有则返回0
     */
    @JvmStatic
    fun getProtectionEndTime(userHomeObj: JSONObject): Long {
        val shieldEndTime = getShieldEndTime(userHomeObj)
        val bombEndTime = getBombCardEndTime(userHomeObj)
        return maxOf(shieldEndTime, bombEndTime)
    }

    /**
     * 检查是否应该跳过蹲点（保护时间比能量球成熟时间长）
     * @param userHomeObj 用户主页数据
     * @param energyMaturityTime 能量球成熟时间
     * @return true表示应该跳过蹲点，false表示可以蹲点
     */
    @JvmStatic
    fun shouldSkipWaitingDueToProtection(userHomeObj: JSONObject, energyMaturityTime: Long): Boolean {
        val protectionEndTime = getProtectionEndTime(userHomeObj)
        return protectionEndTime > energyMaturityTime
    }

    private fun hasPropGroup(userHomeObj: JSONObject, group: String, serverTime: Long): Boolean {
        val props = userHomeObj.optJSONArray("usingUserProps")
            ?: userHomeObj.optJSONArray("usingUserPropsNew")
            ?: return false
        return (0 until props.length()).any { i ->
            val prop = props.optJSONObject(i)
            prop?.optString("propGroup") == group && prop.optLong("endTime", 0L) > serverTime
        }
    }

    /**
     * 获取指定道具组的结束时间
     * @param userHomeObj 用户主页数据
     * @param group 道具组名称（如"shield"、"energyBombCard"）
     * @return 结束时间戳，如果没有该道具则返回0
     */
    private fun getPropGroupEndTime(userHomeObj: JSONObject, group: String): Long {
        val props = userHomeObj.optJSONArray("usingUserProps")
            ?: userHomeObj.optJSONArray("usingUserPropsNew")
            ?: return 0L

        var latestEndTime = 0L
        for (i in 0 until props.length()) {
            val prop = props.optJSONObject(i) ?: continue
            if (prop.optString("propGroup") == group) {
                val endTime = prop.optLong("endTime", 0L)
                latestEndTime = maxOf(latestEndTime, endTime)
            }
        }
        return latestEndTime
    }
}

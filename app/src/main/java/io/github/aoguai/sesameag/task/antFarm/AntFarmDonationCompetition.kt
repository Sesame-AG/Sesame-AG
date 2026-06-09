package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.antFarm.AntFarm.Companion.TAG
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * 捐蛋排位赛管理子模块：支持单次蹲点和轮询蹲点
 */

private const val DONATION_COMPETITION_FIRST_SEEN_KEY_PREFIX = "antFarmDonationCompetitionStableFirstSeen::"
private const val DONATION_COMPETITION_SETTLE_HOUR = 20
private const val TOP_LEVEL_STAR_SENTINEL = 10000
private const val DAY_MS = 24 * 60 * 60 * 1000L
private val DONATION_COMPETITION_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

private data class DonationAwardSnapshot(
    val activityId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val currentLevelId: Int,
    val currentLevelName: String,
    val starsToHighest: Int,
    val totalStarsToHighest: Int,
    val allRewardsReceived: Boolean
)

private data class StableDonationPlan(
    val completed: Boolean = false,
    val fallbackAggressive: Boolean = false,
    val requiredStarsToday: Int = 0,
    val starsToHighest: Int = 0,
    val remainingRounds: Int = 0,
    val bufferDays: Int = 0,
    val maxDailyStars: Int = 0,
    val currentLevelName: String = ""
)

private data class DonationRankTarget(
    val rank: Int,
    val eggsNeeded: Int,
    val stars: Int
)

internal fun AntFarm.handleDonationCompetition() {
    if (donationCompetition?.value != true) return

    if (receiveDonationCompetitionAward?.value == true &&
        !Status.hasFlagToday(StatusFlags.FLAG_FARM_DONATION_COMPETITION_AWARD_RECEIVED)
    ) {
        receiveCompetitionAwards()
    }

    val endTimeStr = "2000"
    val endCal = TimeUtil.getTodayCalendarByTimeStr(endTimeStr) ?: return
    val now = System.currentTimeMillis()
    val checkIntervalMs = BaseModel.checkInterval.value!!.toLong()
    val creationWindowMs = 2 * checkIntervalMs
    val creationStartTime = endCal.timeInMillis - creationWindowMs

    if (now > endCal.timeInMillis) return
    if (isStableDonationCompetitionMode() && stableDonationCompetitionAnytimeCheck?.value == true) {
        if (hasCompletedStableDonationCompetition()) {
            return
        }
        runStableDonationAnytimeCheck(endCal.timeInMillis)
    }

    if (now < creationStartTime) {
        Log.record(TAG, "当前不在排位赛任务调度时间内")
        return
    }
    if (isStableDonationCompetitionMode() && hasCompletedStableDonationCompetition()) {
        return
    }

    try {
        val res = AntFarmRpcCall.enterDonationCompetitionRank()
        val jo = JSONObject(res)
        if (!ResChecker.checkRes(TAG, jo)) {
            val classification = classifyFarmRpcFailure(jo)
            Log.record(
                TAG,
                "进入捐蛋排位赛失败: ${formatFarmHighRiskFailure("enterDonationCompetitionRank", jo, classification)}"
            )
            return
        }

        scheduleDonationCompetitionTask(endCal.timeInMillis)

    } catch (e: Exception) {
        Log.printStackTrace(TAG, "handleDonationCompetition err:", e)
    }
}

/**
 * 遍历奖励列表并领取
 */
private fun AntFarm.receiveCompetitionAwards(): Int {
    try {
        val res = AntFarmRpcCall.enterCompetitionAwardPage()
        val jo = JSONObject(res)
        if (!ResChecker.checkRes(TAG, jo)) {
            val classification = classifyFarmRpcFailure(jo)
            Log.record(
                TAG,
                "进入排位赛奖励页失败：${formatFarmHighRiskFailure("enterCompetitionAwardPage", jo, classification)}"
            )
            return 0
        }

        val userLevelInfo = jo.optJSONObject("userDonationLevelInfo") ?: return 0
        val currentLevelId = userLevelInfo.optInt("levelId")
        val currentLevelName = userLevelInfo.optString("levelName")
        val lightStars = userLevelInfo.optInt("levelLightStarNum", 0)

        val awardList = jo.optJSONArray("levelAwardInfoList") ?: return 0

        var highestLevelName = "未知"
        var starsToHighest = 0
        var nextLevelName = "已达顶峰"

        for (i in 0 until awardList.length()) {
            val levelItem = awardList.getJSONObject(i)
            val lId = levelItem.optInt("levelId")

            if (i == 0) highestLevelName = levelItem.optString("levelName")

            if (lId == currentLevelId + 1) {
                nextLevelName = levelItem.optString("levelName")
            }

            if (lId >= currentLevelId) {
                val upNum = levelItem.optInt("levelStarUpNum")
                if (upNum < 10000) {
                    starsToHighest += upNum
                }
            }
        }

        val starsToNext = userLevelInfo.optInt("levelStarUpNum") - lightStars
        starsToHighest -= lightStars

        val endTime = jo.optJSONObject("donationCompetitionActivityConf")?.optLong("endTime") ?: 0L

        Log.record(TAG, "--- 🏆 排位赛赛季简报 ---")
        Log.record(TAG, "📅 赛季结束：${TimeUtil.getFormatTime(endTime, "yyyy-MM-dd HH:mm:ss")}")
        Log.record(TAG, "📈 当前段位：$currentLevelName")
        Log.record(TAG, "🏹 下一段位：$nextLevelName (差 ${starsToNext}🌟)")
        Log.record(TAG, "👑 最高段位：$highestLevelName (总差距 ${starsToHighest}🌟)")
        Log.record(TAG, "------------------------")

        var claimableCount = 0
        var receivedCount = 0
        for (i in 0 until awardList.length()) {
            val award = awardList.getJSONObject(i)
            if (!award.optString("status").equals("unreceived", ignoreCase = true)) continue

            claimableCount++
            val rightsId = award.optString("rightsId")
            val levelName = award.optString("levelName", "未知段位")
            if (rightsId.isBlank()) {
                Log.record(TAG, "发现可领取排位奖励但缺少 rightsId：$levelName")
                continue
            }

            Log.record(TAG, "发现可领取排位奖励：$levelName")
            val receiveRes = AntFarmRpcCall.receiveDonationLevelReward(rightsId)
            val receiveJo = JSONObject(receiveRes)

            if (ResChecker.checkRes(TAG, receiveJo)) {
                receivedCount++
                Log.record(TAG, "🎉 成功领取 $levelName 段位奖励")
            } else {
                val classification = classifyFarmRpcFailure(receiveJo)
                if (classification == TaskRpcFailureType.TERMINAL_DONE) {
                    receivedCount++
                }
                Log.record(
                    TAG,
                    "领取 $levelName 段位奖励失败：${formatFarmHighRiskFailure("receiveDonationLevelReward", receiveJo, classification)}"
                )
            }
        }

        if (claimableCount == 0) {
            Log.record(TAG, "当前没有可领取的排位赛段位奖励")
            Status.setFlagToday(StatusFlags.FLAG_FARM_DONATION_COMPETITION_AWARD_RECEIVED)
        } else if (receivedCount == claimableCount) {
            Status.setFlagToday(StatusFlags.FLAG_FARM_DONATION_COMPETITION_AWARD_RECEIVED)
        } else {
            Log.record(TAG, "排位赛段位奖励仍有${claimableCount - receivedCount}个未领取，保留后续重试")
        }
        return receivedCount
    } catch (e: Exception) {
        Log.printStackTrace(TAG, "receiveCompetitionAwards err:", e)
    }
    return 0
}

private fun AntFarm.isStableDonationCompetitionMode(): Boolean {
    return donationCompetitionMode?.value == AntFarm.DonationCompetitionMode.STABLE
}

private fun AntFarm.hasCompletedStableDonationCompetition(): Boolean {
    val snapshot = queryDonationAwardSnapshot() ?: return false
    if (!snapshot.allRewardsReceived || snapshot.starsToHighest > 0) return false
    Log.record(TAG, "排位赛稳定模式：最高段位奖励已领取完成，跳过排位赛处理")
    return true
}

private fun AntFarm.queryDonationAwardSnapshot(): DonationAwardSnapshot? {
    return try {
        val res = AntFarmRpcCall.enterCompetitionAwardPage()
        val jo = JSONObject(res)
        if (!ResChecker.checkRes(TAG, jo)) {
            val classification = classifyFarmRpcFailure(jo)
            Log.record(
                TAG,
                "进入排位赛奖励页失败：${formatFarmHighRiskFailure("enterCompetitionAwardPage", jo, classification)}"
            )
            return null
        }
        parseDonationAwardSnapshot(jo)
    } catch (e: Exception) {
        Log.printStackTrace(TAG, "queryDonationAwardSnapshot err:", e)
        null
    }
}

private fun parseDonationAwardSnapshot(jo: JSONObject): DonationAwardSnapshot? {
    val userLevelInfo = jo.optJSONObject("userDonationLevelInfo") ?: return null
    val awardList = jo.optJSONArray("levelAwardInfoList") ?: return null
    val activityConf = jo.optJSONObject("donationCompetitionActivityConf")

    val currentLevelId = userLevelInfo.optInt("levelId")
    val currentLevelName = userLevelInfo.optString("levelName", "未知")
    val lightStars = userLevelInfo.optInt("levelLightStarNum", 0)

    var starsToHighest = 0
    var totalStarsToHighest = 0
    var validAwardCount = 0
    var receivedAwardCount = 0

    for (i in 0 until awardList.length()) {
        val levelItem = awardList.optJSONObject(i) ?: continue
        val upNum = levelItem.optInt("levelStarUpNum", 0)
        if (levelItem.optString("rightsId").isNotBlank()) {
            validAwardCount++
            if (levelItem.optString("status").equals("received", ignoreCase = true)) {
                receivedAwardCount++
            }
        }
        if (upNum >= TOP_LEVEL_STAR_SENTINEL) continue

        totalStarsToHighest += upNum
        if (levelItem.optInt("levelId") >= currentLevelId) {
            starsToHighest += upNum
        }
    }

    starsToHighest = (starsToHighest - lightStars).coerceAtLeast(0)
    return DonationAwardSnapshot(
        activityId = activityConf?.optString("activityId")?.takeIf { it.isNotBlank() } ?: "unknown",
        startTimeMs = activityConf?.optLong("startTime", 0L) ?: 0L,
        endTimeMs = activityConf?.optLong("endTime", 0L) ?: 0L,
        currentLevelId = currentLevelId,
        currentLevelName = currentLevelName,
        starsToHighest = starsToHighest,
        totalStarsToHighest = totalStarsToHighest,
        allRewardsReceived = validAwardCount > 0 && receivedAwardCount == validAwardCount
    )
}

private fun AntFarm.buildStableDonationPlan(rankList: JSONArray): StableDonationPlan? {
    val snapshot = queryDonationAwardSnapshot() ?: return null
    val maxDailyStars = maxRewardStars(rankList)
    if (snapshot.starsToHighest <= 0) {
        return StableDonationPlan(
            completed = true,
            starsToHighest = snapshot.starsToHighest,
            maxDailyStars = maxDailyStars,
            currentLevelName = snapshot.currentLevelName
        )
    }
    if (snapshot.endTimeMs <= 0L || maxDailyStars <= 0) {
        return StableDonationPlan(
            fallbackAggressive = true,
            starsToHighest = snapshot.starsToHighest,
            maxDailyStars = maxDailyStars,
            currentLevelName = snapshot.currentLevelName
        )
    }

    val now = System.currentTimeMillis()
    val firstSeenMs = resolveDonationCompetitionFirstSeen(snapshot)
    val totalRounds = countDonationCompetitionRounds(firstSeenMs, snapshot.endTimeMs).coerceAtLeast(1)
    val averageStars = snapshot.totalStarsToHighest.toDouble() / totalRounds
    val bufferDays = if (averageStars <= 2.0) 7 else 3
    val targetEndTimeMs = snapshot.endTimeMs - bufferDays * DAY_MS
    val remainingRounds = countDonationCompetitionRounds(now, targetEndTimeMs)
    if (remainingRounds <= 0) {
        return StableDonationPlan(
            fallbackAggressive = true,
            starsToHighest = snapshot.starsToHighest,
            remainingRounds = remainingRounds,
            bufferDays = bufferDays,
            maxDailyStars = maxDailyStars,
            currentLevelName = snapshot.currentLevelName
        )
    }

    val requiredStarsToday = ceil(snapshot.starsToHighest.toDouble() / remainingRounds).toInt().coerceAtLeast(1)
    val mustTakeTopEveryDay = requiredStarsToday >= maxDailyStars ||
        snapshot.starsToHighest > maxDailyStars * remainingRounds
    return StableDonationPlan(
        fallbackAggressive = mustTakeTopEveryDay,
        requiredStarsToday = requiredStarsToday,
        starsToHighest = snapshot.starsToHighest,
        remainingRounds = remainingRounds,
        bufferDays = bufferDays,
        maxDailyStars = maxDailyStars,
        currentLevelName = snapshot.currentLevelName
    )
}

private fun AntFarm.resolveDonationCompetitionFirstSeen(snapshot: DonationAwardSnapshot): Long {
    if (snapshot.startTimeMs > 0L) return snapshot.startTimeMs

    val uid = UserMap.currentUid ?: ownerFarmId ?: "unknown"
    val firstSeenKey = "$DONATION_COMPETITION_FIRST_SEEN_KEY_PREFIX$uid::${snapshot.activityId}"
    val stored = DataStore.get(firstSeenKey, Long::class.javaObjectType)?.takeIf { it > 0L }
    if (stored != null) return stored

    val now = System.currentTimeMillis()
    DataStore.put(firstSeenKey, now)
    return now
}

private fun countDonationCompetitionRounds(fromMs: Long, untilMs: Long): Int {
    if (fromMs <= 0L || untilMs <= 0L || untilMs <= fromMs) return 0

    val from = Instant.ofEpochMilli(fromMs).atZone(DONATION_COMPETITION_ZONE)
    val until = Instant.ofEpochMilli(untilMs).atZone(DONATION_COMPETITION_ZONE)
    var firstRoundDate: LocalDate = from.toLocalDate()
    if (from.hour >= DONATION_COMPETITION_SETTLE_HOUR) {
        firstRoundDate = firstRoundDate.plusDays(1)
    }

    var lastRoundDate: LocalDate = until.toLocalDate()
    if (until.hour < DONATION_COMPETITION_SETTLE_HOUR) {
        lastRoundDate = lastRoundDate.minusDays(1)
    }

    return (ChronoUnit.DAYS.between(firstRoundDate, lastRoundDate) + 1).toInt().coerceAtLeast(0)
}

private fun maxRewardStars(rankList: JSONArray): Int {
    var maxStars = 0
    for (i in 0 until rankList.length()) {
        val user = rankList.optJSONObject(i) ?: continue
        maxStars = maxOf(maxStars, user.optInt("rewardStarNum", 0))
    }
    return maxStars
}

private fun selectAggressiveDonationTarget(
    rankList: JSONArray,
    myRank: Int,
    myStars: Int,
    serverDonationTotal: Int,
    effectiveRemainingQuota: Int,
    overtakeAmount: Int
): DonationRankTarget? {
    var target: DonationRankTarget? = null

    for (i in 0 until rankList.length()) {
        val other = rankList.getJSONObject(i)
        val otherRank = other.getInt("rankOrder")
        if (otherRank >= myRank) break

        val otherStars = other.optInt("rewardStarNum", 0)
        if (otherStars <= myStars) continue

        val eggsNeeded = other.getInt("donationNum") - serverDonationTotal + overtakeAmount
        if (eggsNeeded <= 0 || eggsNeeded > effectiveRemainingQuota) continue

        val currentTarget = target
        if (currentTarget == null ||
            otherStars > currentTarget.stars ||
            (otherStars == currentTarget.stars && eggsNeeded < currentTarget.eggsNeeded)
        ) {
            target = DonationRankTarget(otherRank, eggsNeeded, otherStars)
        }
    }
    return target
}

private fun selectStableDonationTarget(
    rankList: JSONArray,
    myRank: Int,
    myStars: Int,
    serverDonationTotal: Int,
    effectiveRemainingQuota: Int,
    requiredStarsToday: Int,
    overtakeAmount: Int
): DonationRankTarget? {
    var target: DonationRankTarget? = null

    for (i in 0 until rankList.length()) {
        val other = rankList.getJSONObject(i)
        val otherRank = other.getInt("rankOrder")
        if (otherRank >= myRank) break

        val otherStars = other.optInt("rewardStarNum", 0)
        if (otherStars <= myStars || otherStars < requiredStarsToday) continue

        val eggsNeeded = other.getInt("donationNum") - serverDonationTotal + overtakeAmount
        if (eggsNeeded <= 0 || eggsNeeded > effectiveRemainingQuota) continue

        val currentTarget = target
        if (currentTarget == null ||
            eggsNeeded < currentTarget.eggsNeeded ||
            (eggsNeeded == currentTarget.eggsNeeded && otherStars < currentTarget.stars)
        ) {
            target = DonationRankTarget(otherRank, eggsNeeded, otherStars)
        }
    }
    return target
}

private fun AntFarm.runStableDonationAnytimeCheck(endTimeMs: Long): Boolean {
    if (!isStableDonationCompetitionMode()) return false
    if (stableDonationCompetitionAnytimeCheck?.value != true) return false
    if (System.currentTimeMillis() >= endTimeMs) return false

    val taskId = "DR|$ownerFarmId"
    if (hasChildTask(taskId)) {
        Log.record(TAG, "排位赛稳定模式非蹲点评估：已存在蹲点任务，跳过本轮评估")
        return false
    }

    val uid = UserMap.currentUid ?: return false
    val maxDonation = maxDailyDonationCompetitionCount?.value ?: -1
    val currentDonated = Status.getDailyDonationTotal(uid)
    if (maxDonation >= 0 && currentDonated >= maxDonation) {
        Log.record(TAG, "排位赛稳定模式非蹲点评估：今日已捐蛋总数($currentDonated)已达每日捐蛋上限($maxDonation)，跳过")
        return false
    }

    val remainingQuota = if (maxDonation < 0) {
        Int.MAX_VALUE
    } else {
        maxDonation - currentDonated
    }
    Log.record(TAG, "排位赛稳定模式非蹲点评估：按今日目标星数检查是否需要补捐")
    return checkRankAndDonate(remainingQuota, allowAggressiveFallback = false)
}

/**
 * 统一调度蹲点任务
 * 逻辑：轮询蹲点开启时忽略单次蹲点，否则按单次蹲点时间运行
 */
private fun AntFarm.scheduleDonationCompetitionTask(endTimeMs: Long) {
    val taskId = "DR|$ownerFarmId"
    if (hasChildTask(taskId)) return

    val uid = UserMap.currentUid
    val maxDonation = maxDailyDonationCompetitionCount?.value ?: -1
    val currentDonated = Status.getDailyDonationTotal(uid)
    if (maxDonation >= 0 && currentDonated >= maxDonation) {
        Log.record(TAG, "今日已捐蛋总数($currentDonated)已达每日捐蛋上限($maxDonation)，跳过任务调度")
        return
    }

    val isPollingMode = watchDonationRank?.value == true
    val execTime: Long

    if (isPollingMode) {
        val advanceMin = watchDonationAdvanceTime?.value ?: 2
        execTime = endTimeMs - advanceMin * 60 * 1000L
    } else {
        val timeCfg = donationCompetitionTime?.value ?: "1958"
        execTime = if (timeCfg.length >= 3) {
            TimeUtil.getTodayCalendarByTimeStr(timeCfg)?.timeInMillis ?: 0L
        } else {
            val min = timeCfg.toIntOrNull() ?: 2
            endTimeMs - min * 60 * 1000L
        }
    }

    val now = System.currentTimeMillis()
    val finalExecTime = if (execTime <= now) {
        if (now >= endTimeMs) return
        Log.record(TAG, "⏰ 当前已过捐蛋排行榜预设启动时间，立即执行")
        now
    } else {
        execTime
    }

    val modeName = if (isPollingMode) "轮询蹲点" else "单次蹲点"

    val task = ModelTask.ChildModelTask(
        id = taskId,
        group = "DR",
        suspendRunnable = {
            cancelPersistentChildTask(taskId)
            runDonationCompetitionChildTask(endTimeMs, isPollingMode)
        },
        execTime = finalExecTime
    )

    addChildTask(task)
    registerPersistentChildTask(
        taskId,
        "DR",
        finalExecTime,
        JSONObject()
            .put("end_time_ms", endTimeMs)
            .put("polling_mode", isPollingMode)
    )
    if (finalExecTime == now) {
        Log.record(TAG, "✅ 已创建立即执行任务")
    } else {
        Log.record(TAG, "✅ 已创建${modeName}任务，执行时间: ${TimeUtil.getCommonDate(finalExecTime)}")
    }
}

internal suspend fun AntFarm.runDonationCompetitionPersistentTask(payload: JSONObject) {
    val fallbackEndTime = TimeUtil.getTodayCalendarByTimeStr("2000")?.timeInMillis ?: 0L
    val endTimeMs = payload.optLong("end_time_ms", fallbackEndTime)
    if (endTimeMs <= 0L) {
        Log.record(TAG, "庄园排位赛持久任务缺少有效 end_time_ms，跳过")
        return
    }
    val isPollingMode = payload.optBoolean("polling_mode", watchDonationRank?.value == true)
    runDonationCompetitionChildTask(endTimeMs, isPollingMode)
}

private suspend fun AntFarm.runDonationCompetitionChildTask(endTimeMs: Long, isPollingMode: Boolean) {
    if (isPollingMode) {
        runDonationRankWatchLoop(endTimeMs)
    } else {
        Log.record(TAG, "🔔 执行单次排位赛捐赠检查")
        val uid = UserMap.currentUid ?: return
        val donationsMadeToday = Status.getDailyDonationTotal(uid)
        val maxDonation = maxDailyDonationCompetitionCount?.value ?: -1
        if (maxDonation < 0) {
            checkRankAndDonate(Int.MAX_VALUE)
        } else if (donationsMadeToday < maxDonation) {
            checkRankAndDonate(maxDonation - donationsMadeToday)
        }
    }
    val reportTime = endTimeMs + 10000L
    val waitToReport = reportTime - System.currentTimeMillis()
    if (waitToReport > 0) {
        Log.record(TAG, "📊 等待结算完成，等待10s获取战报统计")
        delay(waitToReport)
    }
    printDonationReport()
}

private suspend fun AntFarm.runDonationRankWatchLoop(endTimeMs: Long) {
    val refreshSec = watchDonationRefreshInterval?.value ?: 10
    val lastGapSec = watchDonationLastRefreshSecondsBeforeEnd?.value ?: 2
    val lastRefreshTime = endTimeMs - lastGapSec * 1000L

    Log.record(TAG, "🚀 开始排位赛轮询蹲点，间隔 ${refreshSec}s，最后一次刷新时间点：${TimeUtil.getFormatTime(lastRefreshTime, "HH:mm:ss")}")

    while (true) {
        val now = System.currentTimeMillis()
        if (now >= endTimeMs) break

        val uid = UserMap.currentUid ?: break
        val currentDonated = Status.getDailyDonationTotal(uid)
        val maxDonation = maxDailyDonationCompetitionCount?.value ?: -1

        if (maxDonation >= 0 && currentDonated >= maxDonation) {
            Log.record(TAG, "已达到每日捐蛋上限($maxDonation)，结束蹲点。")
            break
        }

        if (maxDonation < 0) {
            checkRankAndDonate(Int.MAX_VALUE)
        } else {
            checkRankAndDonate(maxDonation - currentDonated)
        }

        val afterDonateNow = System.currentTimeMillis()
        if (afterDonateNow >= endTimeMs) break

        val nextNormalRefresh = afterDonateNow + refreshSec * 1000L

        if (nextNormalRefresh >= lastRefreshTime) {
            val waitToFinal = lastRefreshTime - System.currentTimeMillis()
            if (waitToFinal > 0) {
                Log.record(TAG, "⏳ 等待最后一次刷新 (距离结束 $lastGapSec 秒)")
                delay(waitToFinal)

                if (System.currentTimeMillis() < endTimeMs) {
                    Log.record(TAG, "⚡ 执行最后一次刷新")
                    val finalCurrentDonated = Status.getDailyDonationTotal(uid)
                    if (maxDonation < 0) {
                        checkRankAndDonate(Int.MAX_VALUE)
                    } else if (finalCurrentDonated < maxDonation) {
                        checkRankAndDonate(maxDonation - finalCurrentDonated)
                    }
                }
            }
            break // 执行完收官动作后退出循环
        } else {
            delay(refreshSec * 1000L)
        }
    }
    Log.record(TAG, "🏁 轮询蹲点结束")
}

private fun AntFarm.checkRankAndDonate(
    remainingQuota: Int,
    allowAggressiveFallback: Boolean = true
): Boolean {
    try {
        val myUid = UserMap.currentUid ?: return false
        val res = AntFarmRpcCall.enterDonationCompetitionRank()
        val jo = JSONObject(res)
        if (ResChecker.checkRes(TAG, jo)) {
            syncAnimalStatus(ownerFarmId)

            val rankInfo = jo.optJSONObject("donationRankHomeInfo") ?: return false
            val rankList = rankInfo.optJSONArray("userDonationRankList") ?: return false

            if (rankList.length() > 0) {
                var myData: JSONObject? = null
                for (i in 0 until rankList.length()) {
                    val user = rankList.getJSONObject(i)
                    if (user.getString("userId") == myUid) {
                        myData = user
                        break
                    }
                }

                if (myData == null) {
                    Log.record(TAG, "未在排行榜中找到个人数据，可能尚未初始化")
                    return false
                }

                val serverDonationTotal = myData.getInt("donationNum")
                val localDonationTotal = Status.getDailyDonationTotal(myUid)
                if (serverDonationTotal > localDonationTotal) {
                    Status.updateDailyDonationTotal(myUid, serverDonationTotal, incremental = false)
                }
                val dailyDonationTotal = maxOf(serverDonationTotal, localDonationTotal)
                val maxDonation = maxDailyDonationCompetitionCount?.value ?: -1
                val effectiveRemainingQuota = if (maxDonation < 0) {
                    remainingQuota
                } else {
                    minOf(remainingQuota, maxDonation - dailyDonationTotal)
                }
                if (effectiveRemainingQuota <= 0) {
                    Log.record(TAG, "今日已捐蛋总数($dailyDonationTotal)已达每日捐蛋上限($maxDonation)，放弃补捐")
                    return false
                }

                val myRank = myData.getInt("rankOrder")
                val myStars = myData.optInt("rewardStarNum", 0)
                val overtakeAmount = donationCompetitionOvertakeAmount?.value ?: 1

                // 如果已经是第一名，根据策略不做处理
                if (myRank == 1) {
                    Log.record(TAG, "当前已是第一名(${serverDonationTotal}蛋)，无需捐赠")
                    return false
                }

                val stablePlan = if (isStableDonationCompetitionMode()) {
                    buildStableDonationPlan(rankList)
                } else {
                    null
                }
                if (isStableDonationCompetitionMode() && !allowAggressiveFallback && stablePlan == null) {
                    Log.record(TAG, "排位赛稳定模式非蹲点评估：无法计算稳定目标，跳过本轮评估")
                    return false
                }
                if (stablePlan?.completed == true) {
                    Log.record(TAG, "排位赛稳定模式：已达最高段位奖励目标，跳过今日排位赛捐赠")
                    return false
                }

                val target = if (stablePlan != null && !stablePlan.fallbackAggressive) {
                    if (myStars >= stablePlan.requiredStarsToday) {
                        Log.record(
                            TAG,
                            "排位赛稳定模式：当前奖励${myStars}星已达到今日目标${stablePlan.requiredStarsToday}星，跳过捐赠"
                        )
                        return false
                    }

                    selectStableDonationTarget(
                        rankList,
                        myRank,
                        myStars,
                        serverDonationTotal,
                        effectiveRemainingQuota,
                        stablePlan.requiredStarsToday,
                        overtakeAmount
                    ) ?: run {
                        if (!allowAggressiveFallback) {
                            Log.record(
                                TAG,
                                "排位赛稳定模式非蹲点评估：剩余配额(${effectiveRemainingQuota})无法达到今日目标${stablePlan.requiredStarsToday}星，跳过本轮评估"
                            )
                            return false
                        }
                        Log.record(
                            TAG,
                            "排位赛稳定模式：剩余配额(${effectiveRemainingQuota})无法达到今日目标${stablePlan.requiredStarsToday}星，回退激进评估"
                        )
                        selectAggressiveDonationTarget(
                            rankList,
                            myRank,
                            myStars,
                            serverDonationTotal,
                            effectiveRemainingQuota,
                            overtakeAmount
                        )
                    }
                } else {
                    if (stablePlan?.fallbackAggressive == true) {
                        if (!allowAggressiveFallback) {
                            Log.record(
                                TAG,
                                "排位赛稳定模式非蹲点评估：剩余${stablePlan.starsToHighest}星/${stablePlan.remainingRounds}轮，" +
                                    "需要每日${stablePlan.requiredStarsToday}星，需回退激进，跳过本轮评估"
                            )
                            return false
                        }
                        Log.record(
                            TAG,
                            "排位赛稳定模式：剩余${stablePlan.starsToHighest}星/${stablePlan.remainingRounds}轮，" +
                                "需要每日${stablePlan.requiredStarsToday}星，回退激进模式"
                        )
                    }
                    selectAggressiveDonationTarget(
                        rankList,
                        myRank,
                        myStars,
                        serverDonationTotal,
                        effectiveRemainingQuota,
                        overtakeAmount
                    )
                }

                if (target != null) {
                    val plan = stablePlan
                    if (plan != null && !plan.fallbackAggressive) {
                        Log.record(
                            TAG,
                            "稳定捐赠：当前奖励${myStars}星，今日目标${plan.requiredStarsToday}星，" +
                                "目标排名${target.rank}(奖励${target.stars}星)，需反超${target.eggsNeeded}个蛋"
                        )
                    } else {
                        Log.record(
                            TAG,
                            "精明捐赠：当前奖励${myStars}星，目标排名${target.rank}(奖励${target.stars}星)，需反超${target.eggsNeeded}个蛋"
                        )
                    }
                    if (donateForCompetition(target.eggsNeeded)) {
                        // 捐赠成功后立即手动更新一次本地计数
                        Status.updateDailyDonationTotal(myUid, target.eggsNeeded, incremental = true)
                        return true
                    }
                } else {
                    Log.record(TAG, "评估结论：剩余配额(${effectiveRemainingQuota})不足以提升星星奖励，放弃捐赠")
                }
            }
        } else {
            val classification = classifyFarmRpcFailure(jo)
            Log.record(
                TAG,
                "刷新捐蛋排位赛失败: ${formatFarmHighRiskFailure("enterDonationCompetitionRank", jo, classification)}"
            )
        }
    } catch (e: Exception) {
        Log.printStackTrace(TAG, "checkRankAndDonate err:", e)
    }
    return false
}

private fun AntFarm.donateForCompetition(count: Int): Boolean {
    try {
        if (harvestBenevolenceScore < count) {
            if (benevolenceScore >= 1.0) {
                Log.record(TAG, "排位反超蛋数不足(当前:$harvestBenevolenceScore)，发现有待收取蛋($benevolenceScore)，尝试先收获...")
                harvestProduce(ownerFarmId)
            }

            if (harvestBenevolenceScore < count &&
                !tryUseSpecialFoodForCompetition(count)
            ) {
                Log.record(TAG, "排位反超🥚[鸡蛋不足(当前:$harvestBenevolenceScore)，需要:$count，跳过本次捐赠]")
                return false
            }
        }

        val endCal = TimeUtil.getTodayCalendarByTimeStr("2000")
        if (endCal != null && System.currentTimeMillis() >= endCal.timeInMillis) {
            Log.record(TAG, "⏰ 捐赠中止：当前已过 20:00 结算时间，放弃捐赠")
            return false
        }

        val s = AntFarmRpcCall.listActivityInfo()
        val jo = JSONObject(s)
        if (!ResChecker.checkRes(TAG, jo)) {
            val classification = classifyFarmRpcFailure(jo)
            Log.record(
                TAG,
                "查询公益捐蛋项目失败: ${formatFarmHighRiskFailure("listActivityInfo", jo, classification)}"
            )
            return false
        }

        val jaActivityInfos = jo.optJSONArray("activityInfos") ?: return false
        if (jaActivityInfos.length() == 0) return false

        // 寻找第一个未满的项目进行捐赠
        for (i in 0 until jaActivityInfos.length()) {
            val projectJo = jaActivityInfos.getJSONObject(i)
            val donationTotal = projectJo.optDouble("donationTotal", 0.0)
            val donationLimit = projectJo.optDouble("donationLimit", 0.0)
            if (donationTotal >= donationLimit) continue

            val activityId = projectJo.getString("activityId")
            val activityName = projectJo.optString("projectName", activityId)

            val donationResult = performDonationDetailed(activityId, activityName, count)
            return donationResult.success
        }
    } catch (e: Exception) {
        Log.printStackTrace(TAG, "donateForCompetition err:", e)
    }
    return false
}

private fun AntFarm.tryUseSpecialFoodForCompetition(requiredEggCount: Int): Boolean {
    if (harvestBenevolenceScore >= requiredEggCount) {
        return true
    }
    if (!isAutoUseSpecialFoodEnabled()) {
        Log.record(TAG, "排位反超蛋数不足，未开启“使用特殊食品”，跳过特殊食品补蛋")
        return false
    }
    if (donationCompetitionTrySpecialFood?.value != true) {
        return false
    }
    if (isOwnerAnimalSleeping()) {
        Log.record(TAG, "排位反超蛋数不足，小鸡正在睡觉，无法通过特殊食品补蛋")
        return false
    }
    if (!isOwnerAnimalAtHome()) {
        Log.record(TAG, "排位反超蛋数不足，小鸡不在庄园，暂不尝试特殊食品补蛋")
        return false
    }

    val usageCountFlag = StatusFlags.FLAG_FARM_SPECIAL_FOOD_DONATION_COMPETITION_DAILY_COUNT
    val usageLimitFlag = StatusFlags.FLAG_FARM_SPECIAL_FOOD_DONATION_COMPETITION_LIMIT
    val dailyLimit = donationCompetitionSpecialFoodCount?.value ?: -1

    val usedToday = Status.getIntFlagToday(usageCountFlag) ?: 0
    if (dailyLimit > 0 &&
        (Status.hasFlagToday(usageLimitFlag) || usedToday >= dailyLimit)
    ) {
        Status.setFlagToday(usageLimitFlag)
        Log.record(TAG, "排位赛特殊食品今日已使用${usedToday}个，达到上限${dailyLimit}个，停止补蛋")
        return false
    }

    val cuisineList = fetchCuisineListForCompetition() ?: return false
    val remainingDailyQuota = if (dailyLimit > 0) dailyLimit - usedToday else -1
    if (remainingDailyQuota == 0) {
        Status.setFlagToday(usageLimitFlag)
        Log.record(TAG, "排位赛特殊食品今日已无剩余额度，停止补蛋")
        return false
    }

    val eggGap = (requiredEggCount - harvestBenevolenceScore).coerceAtLeast(0.0)
    if (eggGap <= 0.0) {
        return true
    }

    val usedCount = useSpecialFood(
        cuisineList = cuisineList,
        maxUsage = remainingDailyQuota,
        usageCountFlag = usageCountFlag,
        usageLimitFlag = usageLimitFlag,
        usageDailyLimit = dailyLimit,
        usageLabel = "排位赛特殊食品",
        targetEggGap = eggGap
    )
    if (usedCount <= 0) {
        Log.record(TAG, "排位反超蛋数不足，特殊食品调用未成功，停止补蛋")
        return false
    }

    if (isOwnerAnimalSleeping()) {
        Log.record(TAG, "排位反超蛋数不足，尝试补蛋后小鸡进入睡眠，停止继续补蛋")
        return false
    }
    if (!isOwnerAnimalAtHome()) {
        Log.record(TAG, "排位反超蛋数不足，尝试补蛋后小鸡离开庄园，停止继续补蛋")
        return false
    }

    if (benevolenceScore >= 1.0) {
        harvestProduce(ownerFarmId)
    }
    syncAnimalStatus(ownerFarmId)
    return harvestBenevolenceScore >= requiredEggCount
}

/**
 * 任务结束汇报：统计今日产出
 */
private fun AntFarm.printDonationReport() {
    try {
        val uid = UserMap.currentUid ?: return
        val res = AntFarmRpcCall.enterDonationCompetitionRank()
        val jo = JSONObject(res)
        if (ResChecker.checkRes(TAG, jo)) {
            val rankInfo = jo.optJSONObject("donationRankHomeInfo")
            val rankList = rankInfo?.optJSONArray("userDonationRankList") ?: return

            var myData: JSONObject? = null
            for (i in 0 until rankList.length()) {
                val user = rankList.getJSONObject(i)
                if (user.getString("userId") == uid) {
                    myData = user
                    break
                }
            }

            if (myData != null) {
                val totalDonated = Status.getDailyDonationTotal(uid)
                val finalRank = myData.optInt("rankOrder")
                val earnedStars = myData.optInt("rewardStarNum", 0)

                Log.record(TAG, "--- 📊 今日排位赛战报 ---")
                Log.record(TAG, "🥚 今日累计捐赠：$totalDonated 枚")
                Log.record(TAG, "🏆 最终预计排名：第 $finalRank 名")
                Log.record(TAG, "🌟 今日预计获星：$earnedStars 颗")
                Log.record(TAG, "-------------------------")
            }
        } else {
            val classification = classifyFarmRpcFailure(jo)
            Log.record(
                TAG,
                "刷新捐蛋排位赛战报失败: ${formatFarmHighRiskFailure("enterDonationCompetitionRank", jo, classification)}"
            )
        }
    } catch (_: Exception) {
    }
}

private fun AntFarm.fetchCuisineListForCompetition(): JSONArray? {
    val uid = UserMap.currentUid
    if (uid.isNullOrBlank()) {
        Log.record(TAG, "排位赛读取特殊食品库存失败：当前用户ID为空")
        return null
    }
    return try {
        val jo = JSONObject(AntFarmRpcCall.enterFarm(uid, uid))
        if (!ResChecker.checkRes(TAG, jo)) {
            Log.record(TAG, "排位赛读取特殊食品库存失败: ${jo.optString("memo").ifBlank { jo.optString("resultDesc") }}")
            null
        } else {
            val farmVO = jo.optJSONObject("farmVO")
            if (farmVO != null) {
                harvestBenevolenceScore = farmVO.optDouble("harvestBenevolenceScore", harvestBenevolenceScore)
            }
            val cuisineList = jo.optJSONArray("cuisineList")
            if (cuisineList == null) {
                Log.record(TAG, "排位赛读取特殊食品库存失败：cuisineList 为空")
            }
            cuisineList
        }
    } catch (e: Exception) {
        Log.printStackTrace(TAG, "fetchCuisineListForCompetition err:", e)
        null
    }
}

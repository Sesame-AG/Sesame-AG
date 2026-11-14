package io.github.aoguai.sesameag.task.antOrchard


import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.task.common.NonDurationGameTask
import io.github.aoguai.sesameag.util.Log
import org.json.JSONObject

internal suspend fun AntOrchard.runOrchardRewardWorkflow(indexJson: JSONObject, userId: String) {
    tryReceiveSpreadManureActivityAward(indexJson)

    if (receiveSevenDayGift.value == true) {
        if (indexJson.has("lotteryPlusInfo")) {
            drawLotteryPlus(indexJson.getJSONObject("lotteryPlusInfo"))
        } else {
            checkLotteryPlus()
        }
    }

    extraInfoGet("entry")

    indexJson.optJSONObject("goldenEggInfo")?.let { initialEggInfo ->
        try {
            var eggInfo = initialEggInfo
            val attemptedSnapshots = mutableSetOf<String>()
            while (!io.github.aoguai.sesameag.hook.ApplicationHookConstants.isOffline()) {
                val unsmashed = eggInfo.optInt("unsmashedGoldenEggs").coerceAtLeast(0)
                val smashed = eggInfo.optInt("smashedGoldenEggs")
                val limit = eggInfo.optInt("goldenEggLimit")
                if (unsmashed <= 0 && smashed >= limit) break
                if (!attemptedSnapshots.add("$smashed:$unsmashed:$limit")) {
                    Log.orchard("金蛋任务回查未确认进展，保留后续调度")
                    break
                }
                if (unsmashed > 0) {
                    smashedGoldenEgg(unsmashed)
                } else {
                    val action = NonDurationGameTask.advance(
                        GameTask.Orchard_ncscc, channel = GameTask.Orchard_ncscc.channel,
                        detail = "sceneCode=ORCHARD taskId=goldenEgg", logger = Log::orchard,
                        confirmProgress = {
                            val response = JSONObject(AntOrchardRpcCall.orchardIndex())
                            val current = response.optJSONObject("goldenEggInfo")
                            if (response.optString("resultCode") != "100" ||
                                current == null || !current.has("unsmashedGoldenEggs")
                            ) {
                                Log.error("AntOrchard", "金蛋上报后首页状态查询失败:$response")
                                null
                            } else current.optInt("unsmashedGoldenEggs") > unsmashed ||
                                current.has("smashedGoldenEggs") && current.has("goldenEggLimit") &&
                                current.optInt("smashedGoldenEggs") >= current.optInt("goldenEggLimit")
                        },
                    )
                    if (action.stopCurrentRound) return
                    if (!action.refreshAfterAction) break
                }
                val refreshedIndex = JSONObject(AntOrchardRpcCall.orchardIndex())
                if (refreshedIndex.optString("resultCode") != "100") {
                    Log.error("AntOrchard", "金蛋动作后首页回查失败 raw=$refreshedIndex")
                    break
                }
                val refreshedEggInfo = refreshedIndex.optJSONObject("goldenEggInfo")
                if (refreshedEggInfo == null || !refreshedEggInfo.has("unsmashedGoldenEggs")) {
                    Log.error("AntOrchard", "金蛋动作后首页回查缺少goldenEggInfo.unsmashedGoldenEggs")
                    break
                }
                eggInfo = refreshedEggInfo
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException || t is InterruptedException) throw t
            Log.printStackTrace("AntOrchard", "金蛋奖励处理异常:", t)
        }
    }

    if (receiveOrchardTaskAward.value == true) {
        syncTaobaoLimitBalloon()
        doOrchardDailyTask(userId)
        doOrchardGameCenterTasks()
        doOrchardHomeGameTasks()
        receiveLeyuanDailyTaskAwards()
        runCampTakeover()
    }

    if (orchardChouChouLe.value == true) {
        runOrchardChouChouLe(userId)
    }

    receiveMoneyTreeReward()

    if (!Status.hasFlagToday(StatusFlags.FLAG_ANTORCHARD_WIDGET_DAILY_AWARD)) {
        receiveOrchardVisitAward()
    }

    limitedTimeChallenge()
}

internal fun AntOrchard.runOrchardCultivationWorkflow() {
    if ((orchardSpreadManureCountMain.value ?: 0) != 0 || (orchardSpreadManureCountYeb.value ?: 0) != 0) {
        orchardSpreadManure()
        tryReceiveSpreadManureActivityAwardByQueryIndex()
    }

    val wateredMain = Status.getIntFlagToday(StatusFlags.FLAG_ANTORCHARD_SPREAD_MANURE_COUNT) ?: 0
    if (wateredMain in 3..<10) {
        querySubplotsActivity(3)
    } else if (wateredMain >= 10) {
        querySubplotsActivity(10)
    }

    orchardAssistFriend()
}

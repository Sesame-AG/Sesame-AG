package io.github.aoguai.sesameag.task.antOrchard

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.GameTask
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

    indexJson.optJSONObject("goldenEggInfo")?.let { goldenEggInfo ->
        try {
            val unsmashed = goldenEggInfo.optInt("unsmashedGoldenEggs")
            if (unsmashed > 0) {
                smashedGoldenEgg(unsmashed)
                return@let
            }
            val remain = goldenEggInfo.optInt("goldenEggLimit") - goldenEggInfo.optInt("smashedGoldenEggs")
            if (remain <= 0) return@let
            if (!GameTask.Orchard_ncscc.report(remain)) {
                Log.error("AntOrchard", "金蛋游戏上报失败")
                return@let
            }
            val refreshedIndex = JSONObject(AntOrchardRpcCall.orchardIndex())
            if (refreshedIndex.optString("resultCode") != "100") {
                Log.error("AntOrchard", "金蛋游戏上报后首页回查失败 raw=$refreshedIndex")
                return@let
            }
            val refreshedGoldenEggInfo = refreshedIndex.optJSONObject("goldenEggInfo")
            if (refreshedGoldenEggInfo == null || !refreshedGoldenEggInfo.has("unsmashedGoldenEggs")) {
                Log.error("AntOrchard", "金蛋游戏上报后首页回查缺少goldenEggInfo.unsmashedGoldenEggs")
                return@let
            }
            val refreshedUnsmashed = refreshedGoldenEggInfo.optInt("unsmashedGoldenEggs").coerceAtLeast(0)
            if (refreshedUnsmashed > 0) smashedGoldenEgg(refreshedUnsmashed)
        } catch (t: Throwable) {
            Log.printStackTrace("AntOrchard", "金蛋奖励处理异常:", t)
        }
    }

    if (receiveOrchardTaskAward.value == true) {
        syncTaobaoLimitBalloon()
        doOrchardDailyTask(userId)
        receiveLeyuanDailyTaskAwards()
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
        CoroutineUtils.sleepCompat(200)
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

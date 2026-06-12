package io.github.aoguai.sesameag.task.antOrchard

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.GameTask
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

    val goldenEggInfo = indexJson.optJSONObject("goldenEggInfo")
    if (goldenEggInfo != null) {
        val unsmashed = goldenEggInfo.optInt("unsmashedGoldenEggs")
        val limit = goldenEggInfo.optInt("goldenEggLimit")
        val smashed = goldenEggInfo.optInt("smashedGoldenEggs")

        if (unsmashed > 0) {
            smashedGoldenEgg(unsmashed)
        } else {
            val remain = limit - smashed
            if (remain > 0) {
                GameTask.Orchard_ncscc.report(remain)
            }
        }
    }

    if (receiveOrchardTaskAward.value == true) {
        syncTaobaoLimitBalloon()
        doOrchardDailyTask(userId)
        receiveLeyuanDailyTaskAwards()
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

package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeCounter

internal suspend fun AntFarm.runFarmLifecycleWorkflow(tc: TimeCounter): Boolean {
    if (enterFarm() == null) {
        return false
    }

    if (sendBackAnimal?.value == true) {
        sendBackAnimal()
        tc.countDebug("遣返")
    }
    recallAnimal()
    tc.countDebug("召回小鸡")

    if (useDailySpecialFoodIfNeeded() > 0) {
        tc.countDebug("特殊食品")
    }

    if (shouldHireAnimalNow()) {
        hireAnimal()
    }

    if (shouldRunNpcAnimalLogic()) {
        handleNpcAnimalLogic()
        tc.countDebug("NPC小鸡任务")
    }
    return true
}

internal suspend fun AntFarm.runFarmTaskWorkflow(tc: TimeCounter, userId: String?): Boolean {
    var pendingFarmTaskFinalization = false

    if (doFarmTask?.value == true && !Status.hasFlagToday(StatusFlags.FLAG_FARM_TASK_FINISHED)) {
        pendingFarmTaskFinalization = triggerFarmTaskIfNeeded(tc)
    }

    handleAutoFeedAnimal()
    tc.countDebug("喂食")

    preloadFarmTools()
    tc.countDebug("装载道具信息")

    if (rewardFriend?.value == true) {
        rewardFriend()
        tc.countDebug("打赏好友")
    }

    if (receiveFarmToolReward?.value == true) {
        receiveToolTaskReward()
        tc.countDebug("收取道具奖励")
    }
    if (recordFarmGame?.value == true) {
        tc.countDebug("游戏改分(星星球、登山赛、飞行赛、揍小鸡)")
        if (!Status.hasFlagToday(StatusFlags.FLAG_FARM_GAME_FINISHED)) {
            FarmGame.run(this)
        }
    }

    if (chickenDiary?.value == true) {
        doChickenDiary()
        tc.countDebug("小鸡日记")
    }

    if (kitchen?.value == true) {
        if (isOwnerAnimalSleeping()) {
            Log.farm("小鸡厨房🐔[小鸡正在睡觉中，跳过厨房功能]")
        } else if (!ensureOwnerAnimalAtHome("小鸡厨房")) {
            Log.farm("小鸡厨房🐔[小鸡不在庄园，跳过厨房功能]")
        } else {
            collectDailyFoodMaterial()
            collectDailyLimitedFoodMaterial()
            cook()
            refreshFarmStatus("厨房流程后")
        }
        tc.countDebug("小鸡厨房")
    }

    if (useNewEggCard?.value == true) {
        useFarmTool(ownerFarmId, AntFarm.ToolType.NEWEGGTOOL)
        syncAnimalStatus(ownerFarmId)
        tc.countDebug("使用新蛋卡")
    }
    if (shouldHarvestProduceNow()) {
        Log.farm("有可收取的爱心鸡蛋")
        harvestProduce(ownerFarmId)
        tc.countDebug("收鸡蛋")
    }
    if (donation?.value == true && shouldDonateEggNow(userId)) {
        val publicDonationMade = handleDonation()
        tc.countDebug("每日捐蛋")
        val dailyDonationMarkedDone = !userId.isNullOrBlank() &&
            Status.hasFlagToday(StatusFlags.FLAG_FARM_DAILY_DONATION_DONE_PREFIX + userId)
        if (publicDonationMade) {
            if (dailyDonationMarkedDone) {
                Log.farm("今日捐蛋完成")
            } else {
                Log.farm("公益捐蛋部分完成，保留后续重试")
            }
            if (family?.value == true) {
                AntFarmFamily.confirmDailyDonateTaskAfterPublicDonation()
            }
        } else if (dailyDonationMarkedDone) {
            Log.farm("今日捐蛋完成")
        } else if (!lastDonationNoMoreActivities) {
            Log.farm("公益捐蛋未完成，保留后续重试")
        }
    } else if (donation?.value == true &&
        !userId.isNullOrBlank() &&
        !Status.hasFlagToday(StatusFlags.FLAG_FARM_DAILY_DONATION_DONE_PREFIX + userId)
    ) {
        val amount = donationAmount?.value ?: 1
        val dailyLimit = maxDailyDonationCompetitionCount?.value ?: -1
        val remainingQuota = dailyLimit - Status.getDailyDonationTotal(userId)
        if (dailyLimit >= 0 && remainingQuota < amount) {
            if (remainingQuota <= 0) {
                Log.farm("今日已捐蛋总数已达每日捐蛋上限($dailyLimit)，跳过普通每日捐蛋")
            } else {
                Log.farm("今日捐蛋剩余额度不足单次捐蛋量，跳过普通每日捐蛋：剩余${remainingQuota}颗，单次需要${amount}颗")
            }
        } else if (harvestBenevolenceScore < amount) {
            Log.farm("可用爱心蛋不足，跳过普通每日捐蛋：当前${harvestBenevolenceScore}颗，需要${amount}颗")
        }
    }

    if (receiveFarmTaskAward?.value == true) {
        receiveFarmAwards()
        tc.countDebug("收取饲料奖励")
    }

    handleDonationCompetition()

    return pendingFarmTaskFinalization
}

internal suspend fun AntFarm.runFarmSocialWorkflow(
    tc: TimeCounter,
    pendingFarmTaskFinalization: Boolean
): Boolean {
    var pendingFinalization = pendingFarmTaskFinalization

    if (visitAnimal?.value == true) {
        visitAnimal()
        tc.countDebug("到访小鸡送礼")
        visit()
        tc.countDebug("送麦子")
    }

    if (family?.value == true) {
        AntFarmFamily.run(
            familyOptions!!,
            notInviteList!!,
            familyAssignStrategy?.value ?: AntFarm.FamilyAssignStrategy.RANDOM
        )
        tc.countDebug("家庭任务")
    }

    feedFriend()
    tc.countDebug("帮好友喂鸡")

    if (notifyFriend?.value == true) {
        notifyFriend()
        tc.countDebug("通知好友赶鸡")
    }

    if (enableChouchoule?.value == true) {
        tc.countDebug("抽抽乐")
        ChouChouLe().run(this)
        handleMultiStageTasksLoop()
        if (pendingFinalization) {
            pendingFinalization = finalizeFarmTaskAfterMultiStage("抽抽乐流程后")
        }
        refreshFarmStatus("抽抽乐流程后")
    }

    if (getFeed?.value == true) {
        letsGetChickenFeedTogether()
        tc.countDebug("一起拿饲料")
    }
    if (enableDdrawGameCenterAward?.value == true) {
        FarmGame.drawGameCenterAward()
        tc.countDebug("开宝箱")
        if (pendingFinalization) {
            pendingFinalization = finalizeFarmTaskAfterMultiStage("开宝箱流程后")
        }
    }
    if (paradiseCoinExchangeBenefit?.value == true) {
        paradiseCoinExchangeBenefit()
        tc.countDebug("小鸡乐园道具兑换")
    }

    if (queryOrnamentMall?.value == true) {
        handleOrnamentMall()
        tc.countDebug("装扮商城")
    }

    return pendingFinalization
}

internal suspend fun AntFarm.runFarmFinalizeWorkflow(
    tc: TimeCounter,
    pendingFarmTaskFinalization: Boolean
) {
    animalSleepAndWake()
    tc.countDebug("小鸡睡觉&起床")

    if (pendingFarmTaskFinalization) {
        finalizeFarmTaskAfterMultiStage("睡觉流程后")
    }

    syncAnimalStatus(ownerFarmId)
    if (isOwnerAnimalSleeping()) {
        Log.farm("小鸡正在睡觉，领取饲料")
        receiveFarmAwards()
    }

    tc.stop()
}

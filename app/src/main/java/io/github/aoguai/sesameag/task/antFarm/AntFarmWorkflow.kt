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
    var familyDailyDonateResult = AntFarmFamily.DailyDonateTaskResult.NOT_HANDLED
    var familyDonationActivityIds: Set<String> = emptySet()
    var attemptedPublicDonation = false
    var skipPublicDonationThisRun = false
    if (family?.value == true && donation?.value == true) {
        val singleDonationMode = donationCount?.value != AntFarm.DonationCount.ALL
        familyDailyDonateResult = AntFarmFamily.handleDailyDonateTask(this)
        if (familyDailyDonateResult.consumedDonation) {
            familyDonationActivityIds = lastDonationActivityIds
        }
        if (singleDonationMode && familyDailyDonateResult.consumedDonation) {
            Status.donationEgg(userId)
            skipPublicDonationThisRun = true
        }
        if (familyDailyDonateResult == AntFarmFamily.DailyDonateTaskResult.DONATED_CONFIRMED) {
            if (donationCount?.value == AntFarm.DonationCount.ALL) {
                Log.farm("家庭任务已优先完成首笔捐蛋，继续按配置处理后续公益捐蛋")
            } else {
                Log.farm("家庭任务优先完成本轮捐蛋，跳过公益捐蛋")
            }
        } else if (familyDailyDonateResult == AntFarmFamily.DailyDonateTaskResult.DONATED_UNCONFIRMED) {
            if (singleDonationMode) {
                Log.farm("家庭任务已执行捐蛋但完成状态未确认，本轮按已消耗一次捐蛋处理，跳过公益捐蛋")
            } else {
                Log.farm("家庭任务已执行捐蛋但完成状态未确认，继续按配置处理剩余公益捐蛋")
            }
        } else if (familyDailyDonateResult == AntFarmFamily.DailyDonateTaskResult.ALREADY_COMPLETED
            && donationCount?.value != AntFarm.DonationCount.ALL
        ) {
            Log.farm("家庭任务已显示今日捐蛋完成，继续按原有公益捐蛋逻辑处理")
        }
    }
    if (!skipPublicDonationThisRun && shouldDonateEggNow(userId)) {
        attemptedPublicDonation = true
        val publicDonationDone = handleDonation(
            donationCount?.value ?: 0,
            skipActivityIds = familyDonationActivityIds
        )
        tc.countDebug("每日捐蛋")
        if (publicDonationDone) {
            Log.farm("今日捐蛋完成")
        } else if (!lastDonationNoMoreActivities) {
            Log.farm("公益捐蛋未完成，保留后续重试")
        }
    }
    if (familyDailyDonateResult.consumedDonation && !userId.isNullOrBlank() && Status.canDonationEgg(userId)) {
        val noFurtherDonationNeeded =
            if (!familyDailyDonateResult.shouldMarkDonationDone) {
                false
            } else {
                donationCount?.value != AntFarm.DonationCount.ALL ||
                    !shouldDonateEggNow(userId) ||
                    (attemptedPublicDonation && lastDonationNoMoreActivities)
            }
        if (noFurtherDonationNeeded) {
            Status.donationEgg(userId)
        }
    }

    if (receiveFarmTaskAward?.value == true) {
        receiveFarmAwards()
        tc.countDebug("收取饲料奖励")
    }

    return pendingFarmTaskFinalization
}

internal suspend fun AntFarm.runFarmSocialWorkflow(
    tc: TimeCounter,
    pendingFarmTaskFinalization: Boolean
) {
    var pendingFinalization = pendingFarmTaskFinalization

    if (visitAnimal?.value == true) {
        visitAnimal()
        tc.countDebug("到访小鸡送礼")
        visit()
        tc.countDebug("送麦子")
    }

    if (family?.value == true) {
        AntFarmFamily.run(familyOptions!!, notInviteList!!)
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
    }
    if (paradiseCoinExchangeBenefit?.value == true) {
        paradiseCoinExchangeBenefit()
        tc.countDebug("小鸡乐园道具兑换")
    }

    if (queryOrnamentMall?.value == true) {
        handleOrnamentMall()
        tc.countDebug("装扮商城")
    }
}

internal suspend fun AntFarm.runFarmFinalizeWorkflow(tc: TimeCounter) {
    animalSleepAndWake()
    tc.countDebug("小鸡睡觉&起床")

    syncAnimalStatus(ownerFarmId)
    if (isOwnerAnimalSleeping()) {
        Log.farm("小鸡正在睡觉，领取饲料")
        receiveFarmAwards()
    }

    tc.stop()
}

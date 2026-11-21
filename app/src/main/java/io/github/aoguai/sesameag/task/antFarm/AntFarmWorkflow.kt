package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.util.TimeCounter
import io.github.aoguai.sesameag.util.maps.UserMap

internal suspend fun AntFarm.runFarmLifecycleWorkflow(tc: TimeCounter): Boolean {
    if (enterFarm() == null) {
        return false
    }

    wakeFarmIfDue()
    if (sendBackAnimal?.value == true) {
        sendBackAnimal()
        tc.countDebug("遣返")
    }
    recallAnimal()
    tc.countDebug("召回小鸡")
    handleAutoFeedAnimal()

    if (useDailySpecialFoodIfNeeded() > 0) {
        tc.countDebug("特殊食品")
    }

    if (shouldRunNpcAnimalLogic()) {
        handleNpcAnimalLogic()
        tc.countDebug("NPC小鸡任务")
    }

    return true
}

internal suspend fun AntFarm.runFarmTaskWorkflow(
    tc: TimeCounter,
    userId: String?,
): TaskFlowRunResult {
    val result = triggerFarmTaskIfNeeded(tc)
    if (ApplicationHookConstants.isOffline()) return result.copy(interrupted = true)
    runFarmResourceWork()
    tc.countDebug("照料、厨房与独立小游戏")

    if (rewardFriend?.value == true) {
        rewardFriend()
        tc.countDebug("打赏好友")
    }

    if (diaryTietie?.value == true || (collectChickenDiary?.value ?: AntFarm.collectChickenDiaryType.CLOSE) != AntFarm.collectChickenDiaryType.CLOSE) {
        doChickenDiary()
        tc.countDebug("小鸡日记")
    }

    if (useNewEggCard?.value == true) {
        useFarmTool(ownerFarmId, AntFarm.ToolType.NEWEGGTOOL)
        syncAnimalStatus(ownerFarmId)
        tc.countDebug("使用新蛋卡")
    }
    if (useFenceTool?.value == true) {
        useFarmTool(ownerFarmId, AntFarm.ToolType.FENCETOOL)
        syncAnimalStatus(ownerFarmId)
        tc.countDebug("使用篱笆卡")
    }
    if (useDollTool?.value == true) {
        supplementDolls(ownerFarmId)
        tc.countDebug("数字公仔补签")
    }
    if (shouldHarvestProduceNow()) {
        Log.farm("有可收取的爱心鸡蛋")
        harvestProduce(ownerFarmId)
        tc.countDebug("收鸡蛋")
    }
    runFarmPriorityDonations(userId)
    tc.countDebug("每日捐蛋")
    if (loveChickenGathering?.value == true && !ApplicationHookConstants.isOffline()) {
        runLoveChickenGatheringWorkflow()
        tc.countDebug("爱心鸡结号")
    } else if (loveChickenGathering?.value != true) cancelLoveChickenSchedule()
    return result
}

internal suspend fun AntFarm.runFarmPriorityDonations(userId: String? = UserMap.currentUid) {
    if (ApplicationHookConstants.isOffline()) return
    if (donation?.value == true && shouldDonateEggNow(userId)) {
        val publicDonationMade = handleDonation()
        val dailyDonationMarkedDone =
            !userId.isNullOrBlank() &&
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

    if (!ApplicationHookConstants.isOffline()) runOrdinaryOrnamentTasks()
}

internal suspend fun AntFarm.runFarmSocialWorkflow(
    tc: TimeCounter,
    pendingFarmTaskFinalization: TaskFlowRunResult,
): TaskFlowRunResult {
    if (ApplicationHookConstants.isOffline()) return pendingFarmTaskFinalization.copy(interrupted = true)

    if (visitAnimal?.value == true) {
        visitAnimal()
        tc.countDebug("到访小鸡送礼")
        visit()
        tc.countDebug("送麦子")
    }

    if (family?.value == true) {
        AntFarmFamily.run(
            familyOptions!!,
            familyShareList!!,
            familyShareMode?.value ?: AntFarm.FamilyShareMode.INVITE_SELECTED,
            familyAssignStrategy?.value ?: AntFarm.FamilyAssignStrategy.RANDOM,
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
        refreshFarmStatus("抽抽乐流程后")
    }

    if (getFeed?.value == true) {
        letsGetChickenFeedTogether()
        tc.countDebug("一起拿饲料")
    }
    if (enableDdrawGameCenterAward?.value == true) {
        cancelPersistentChildTask("game_center")
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

    return pendingFarmTaskFinalization.copy(interrupted = ApplicationHookConstants.isOffline())
}

internal suspend fun AntFarm.runFarmFinalizeWorkflow(
    tc: TimeCounter,
    pendingFarmTaskFinalization: TaskFlowRunResult,
) {
    if (pendingFarmTaskFinalization.interrupted || ApplicationHookConstants.isOffline()) {
        tc.stop()
        return
    }
    runDueFarmWork()
    runFarmResourceWork(includeGames = false)
    if (!ApplicationHookConstants.isOffline()) {
        refreshFarmStatus("家庭、抽奖、宝箱和兑换后补蛋")
        if (shouldHarvestProduceNow()) harvestProduce(ownerFarmId)
        runFarmPriorityDonations()
    }
    if (loveChickenGathering?.value == true && !ApplicationHookConstants.isOffline()) {
        runLoveChickenGatheringWorkflow()
        tc.countDebug("爱心鸡结号")
    }
    animalSleepAndWake()
    tc.countDebug("小鸡睡觉&起床")

    // 厨房与乐园动作可能刚推进大表鸽任务，收尾统一补收并回查 NPC 产出。
    runNpcTaskFlow()

    refreshFarmFeedingSchedule()
    if (isOwnerAnimalSleeping()) {
        Log.farm("小鸡正在睡觉，领取饲料")
        receiveFarmAwards()
    }

    scheduleFarmPendingWork()
    tc.stop()
}

internal suspend fun AntFarm.runFarmResourceWork(
    includeGames: Boolean = true,
    feedAfterAwards: Boolean = true,
): Boolean {
    if (ApplicationHookConstants.isOffline()) return false
    val stockBefore = AntFarm.foodStock
    var progressed = false
    var aiKitchenShareFailure: TaskFlowActionResult? = null
    var hireResult: TaskFlowActionResult? = null
    val hireCandidates = mutableListOf<org.json.JSONObject>()
    val visitedHireFriends = mutableSetOf<String>()
    preloadFarmTools()
    if (includeGames && recordFarmGame?.value == true) FarmGame.run(this)
    if (ApplicationHookConstants.isOffline()) return false
    receiveToolTaskReward()
    if (ApplicationHookConstants.isOffline()) return false
    do {
        val roundStock = AntFarm.foodStock
        receiveFarmAwards()
        if (ApplicationHookConstants.isOffline()) break
        handleMultiStageTasksLoop()
        if (ApplicationHookConstants.isOffline()) break
        var statusFailure: TaskFlowActionResult? = null
        syncAnimalStatus(ownerFarmId) { statusFailure = it }
        if (statusFailure != null) {
            Log.error("AntFarm", "资源照料状态刷新失败: $statusFailure")
            break
        }
        if (feedAfterAwards && !isOwnerAnimalSleeping()) {
            if (feedAnimal?.value == true) handleAutoFeedAnimal()
            if (ApplicationHookConstants.isOffline()) break
            if (shouldHireAnimalNow() && hireResult?.stopCurrentRound != true) {
                runHireAnimalFeedTaskFlow()
                if (ApplicationHookConstants.isOffline()) break
                val result = hireAnimal(hireCandidates, visitedHireFriends)
                hireResult = result
                progressed = progressed || result.progressChanged
                if (!result.success && result.deferredReason == null) Log.error("AntFarm", "资源流程雇佣失败:$result")
            }
        }
        if (ApplicationHookConstants.isOffline()) break
        val aiKitchenShare = if (aiKitchenShareFailure == null) runFarmAiKitchenShare() else AntFarm.FarmResourceResult(0)
        aiKitchenShareFailure = aiKitchenShareFailure ?: aiKitchenShare.failure
        progressed = progressed || aiKitchenShare.progressed
        if (ApplicationHookConstants.isOffline()) break
        val oldKitchen = runFarmKitchen()
        if (ApplicationHookConstants.isOffline()) break
        // 睡前资源准备由原睡前预算安排照料，厨神制作留到正常资源流程。
        val aiKitchen = if (feedAfterAwards) runFarmAiKitchen() else AntFarm.FarmResourceResult(0)
        val cooked = oldKitchen.progressed || aiKitchen.progressed
        progressed = progressed || cooked || AntFarm.foodStock != roundStock
        if (ApplicationHookConstants.isOffline()) break
        aiKitchen.failure?.let { Log.error("AntFarm", "小鸡大厨动作失败:$it") }
        if (cooked) useDailySpecialFoodIfNeeded()
        if (!aiKitchenShare.progressed && !cooked && AntFarm.foodStock == roundStock) break
    } while (!ApplicationHookConstants.isOffline())
    return progressed || AntFarm.foodStock != stockBefore
}

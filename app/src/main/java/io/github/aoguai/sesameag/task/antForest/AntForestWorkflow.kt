package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeCounter
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject

private val FOREST_TAG: String = AntForest::class.java.simpleName

internal suspend fun AntForest.runForestPreparationAndCollectionWorkflow(tc: TimeCounter): JSONObject? {
    updateSelfHomePage()
    tc.countDebug("查询道具状态")

    usePropBeforeCollectEnergy(UserMap.currentUid)
    tc.countDebug("使用自己道具卡")

    val collectEnergyEnabled = isCollectEnergyEnabled()

    Log.forest("🌳 【正常流程】查询自己的森林主页...")
    var selfHomeObj = querySelfHome()
    tc.countDebug("获取自己主页对象信息")
    if (selfHomeObj != null) {
        if (collectEnergyEnabled) {
            collectEnergy(UserMap.currentUid, selfHomeObj, "self")
            Log.forest("✅ 【正常流程】收取自己的能量完成")
            tc.countDebug("收取自己的能量")
        } else {
            Log.forest("收集能量开关关闭，跳过自己的能量收取")
            tc.countDebug("跳过自己的能量收取（未开启）")
        }
    } else {
        Log.error(FOREST_TAG, "❌ 【正常流程】获取自己主页信息失败，跳过本次自己能量收取")
        tc.countDebug("跳过自己的能量收取（主页获取失败）")
    }

    if (isTakeLookEnergyEnabled()) {
        Log.forest("🚀 执行找能量接口")
        collectEnergyByTakeLook()
        tc.countDebug("找能量接口")
    } else {
        Log.forest("收集能量开关关闭，跳过找能量接口")
        tc.countDebug("跳过找能量接口（收集能量未开启）")
    }

    if (collectEnergyEnabled && pkEnergy?.value == true) {
        Log.forest("🚀 执行PK排行榜补全（协程）")
        collectPKEnergyCoroutine()
        tc.countDebug("PK排行榜补全（同步）")
    } else if (pkEnergy?.value == true) {
        Log.forest("收集能量开关关闭，跳过PK排行榜补全")
        tc.countDebug("跳过PK排行榜补全（收集能量未开启）")
    } else {
        tc.countDebug("跳过PK排行榜补全（未开启）")
    }

    if (hasFriendRankingWorkEnabled()) {
        Log.forest("🚀 执行好友排行榜补全（协程）")
        collectFriendEnergyCoroutine()
        tc.countDebug("好友排行榜补全（同步）")
    } else {
        Log.forest("收集能量、领取礼盒和复活能量均未开启，跳过好友排行榜扫描")
        tc.countDebug("跳过好友排行榜扫描（无收取/礼盒/复活需求）")
    }

    Log.forest("🌳 【正常流程】补充检查自己的森林主页...")
    val finalSelfHomeObj = querySelfHome()
    if (finalSelfHomeObj != null) {
        selfHomeObj = finalSelfHomeObj
        if (collectEnergyEnabled) {
            collectEnergy(UserMap.currentUid, finalSelfHomeObj, "self")
            Log.forest("✅ 【正常流程】补充检查自己能量完成")
            tc.countDebug("补充检查自己的能量")
        } else {
            tc.countDebug("跳过补充检查自己的能量（未开启）")
        }
    } else {
        Log.error(FOREST_TAG, "❌ 【正常流程】补充检查自己主页失败")
        tc.countDebug("跳过补充检查自己的能量（主页获取失败）")
    }
    return selfHomeObj
}

internal fun AntForest.runEnergyOnlyCollectionWorkflow(tc: TimeCounter): JSONObject? {
    val collectEnergyEnabled = isCollectEnergyEnabled()

    Log.forest("🌳 【只收能量】查询自己的森林主页...")
    val selfHomeObj = querySelfHome()
    if (selfHomeObj != null) {
        if (collectEnergyEnabled) {
            collectEnergy(UserMap.currentUid, selfHomeObj, "self")
            Log.forest("✅ 【只收能量】收取自己的能量完成")
            tc.countDebug("只收能量-收取自己的能量")
        } else {
            Log.forest("收集能量开关关闭，跳过自己的能量收取")
            tc.countDebug("只收能量-跳过自己的能量收取（未开启）")
        }
    } else {
        Log.error(FOREST_TAG, "❌ 【只收能量】获取自己主页信息失败，跳过本次自己能量收取")
        tc.countDebug("只收能量-跳过自己的能量收取（主页获取失败）")
    }

    if (isTakeLookEnergyEnabled()) {
        Log.forest("🚀 【只收能量】执行找能量接口")
        collectEnergyByTakeLook()
        tc.countDebug("只收能量-找能量接口")
    } else {
        Log.forest("收集能量开关关闭，跳过找能量接口")
        tc.countDebug("只收能量-跳过找能量接口（收集能量未开启）")
    }

    return selfHomeObj
}

internal suspend fun AntForest.runForestHomeFollowUpWorkflow(selfHomeObj: JSONObject?, tc: TimeCounter) {
    if (selfHomeObj == null) {
        return
    }
    var shouldRefreshForestHomeAfterEnergyRain = false

    checkAndHandleWhackMole()
    tc.countDebug("拼手速")

    val processObj = if (isTeam(selfHomeObj)) {
        selfHomeObj.optJSONObject("teamHomeResult")
            ?.optJSONObject("mainMember")
    } else {
        selfHomeObj
    }

    if (collectWateringBubble?.value == true) {
        wateringBubbles(processObj)
        tc.countDebug("收取浇水金球")
    }
    if (collectProp?.value == true) {
        givenProps(processObj)
        tc.countDebug("收取道具")
    }
    if (userPatrol?.value == true) {
        queryUserPatrol()
        tc.countDebug("动物巡护任务")
    }

    handleUserProps(selfHomeObj)
    tc.countDebug("收取动物派遣能量")

    handleEnergyPvpChallenge()
    tc.countDebug("1V1能量挑战赛")

    collectEnergyBomb(selfHomeObj)
    tc.countDebug("收取炸弹卡能量")

    if (canRunConsumeAnimalPropWorkflow()) {
        queryAndConsumeAnimal()
        tc.countDebug("森林巡护")
    } else {
        Log.forest("已经有动物伙伴在巡护森林~")
    }

    if (combineAnimalPiece?.value == true) {
        queryAnimalAndPiece()
        tc.countDebug("合成动物碎片")
    }

    if (receiveForestTaskAward?.value == true) {
        receiveTaskAward()
        tc.countDebug("森林任务")
        handleGift7thSign(selfHomeObj)
        tc.countDebug("森林七日礼包")
    }
    if (ecoLife?.value == true) {
        val ecoLifeTimeAllowed = ecoLifeTime?.let { it.isDisabled() || it.isReachedToday() } ?: true
        if (ecoLifeTimeAllowed) {
            EcoLife.ecoLife()
            tc.countDebug("绿色行动")
        } else {
            Log.forest("绿色行动未到执行时间，跳过")
        }
    }

    waterFriends()
    tc.countDebug("给好友浇水")

    if (giveProp?.value == true) {
        giveProp()
        tc.countDebug("赠送道具")
    }

    if (vitalityExchange?.value == true) {
        handleVitalityExchange()
        tc.countDebug("活力值兑换")
    }

    if (energyRain?.value == true) {
        val energyRainTimeAllowed = energyRainTime?.let { it.isDisabled() || it.isReachedToday() } ?: true
        if (energyRainTimeAllowed) {
            if (energyRainChance?.value == true) {
                useEnergyRainChanceCard()
                tc.countDebug("使用能量雨卡")
            }
            if (EnergyRainCoroutine.execEnergyRain()) {
                shouldRefreshForestHomeAfterEnergyRain = true
                handleEnergyRainPostFlow()
            }
            tc.countDebug("能量雨")
        } else {
            Log.forest("能量雨未到执行时间，跳过")
        }
    }

    if (forestMarket?.value == true) {
        GreenLife.ForestMarket("GREEN_LIFE", "ANTFOREST")
        tc.countDebug("森林集市")
    }

    if (medicalHealth?.value == true) {
        if (AntForest.medicalHealthOption?.value?.contains("FEEDS") == true) {
            Healthcare.queryForestEnergy("FEEDS")
            tc.countDebug("绿色医疗")
        }
        if (AntForest.medicalHealthOption?.value?.contains("BILL") == true) {
            Healthcare.queryForestEnergy("BILL")
            tc.countDebug("电子小票")
        }
    }

    if (youthPrivilege?.value == true) {
        Privilege.youthPrivilege()
    }

    if (dailyCheckIn?.value == true) {
        Privilege.studentSignInRedEnvelope()
    }

    if (forestChouChouLe?.value == true) {
        ForestChouChouLe().chouChouLe()
        tc.countDebug("抽抽乐")
    }

    doforestgame()

    updateSelfHomePage(
        collectRobMultiplierEnergy = true,
        homePageSource = if (shouldRefreshForestHomeAfterEnergyRain) {
            AntForestRpcCall.BACK_FROM_ENERGY_RAIN_SOURCE
        } else {
            null
        }
    )
    tc.countDebug("领取N倍卡能量")

    logForestEnergyInfo()
    tc.stop()
}

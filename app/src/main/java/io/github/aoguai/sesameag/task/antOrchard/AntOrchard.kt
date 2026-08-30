package io.github.aoguai.sesameag.task.antOrchard

import android.util.Base64
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.internal.SecurityBodyHelper
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.GameCenterPlayRpcCall
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectNeed
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenishResult
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenisher
import io.github.aoguai.sesameag.task.goldenBean.GoldenBeanRpcCall
import io.github.aoguai.sesameag.task.goldenBean.GoldenBeanTreasure
import io.github.aoguai.sesameag.task.goldenBean.GoldenBeanTreasureSupport
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.RandomUtil
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class AntOrchard : ModelTask() {
    companion object {
        private val TAG = AntOrchard::class.java.simpleName
        private const val ENTRY_SOURCE = "ch_appcenter__chsub_9patch"
        private const val ACTION_SOURCE = "gonggexiguan"
        private const val YEB_SOURCE = "yaoqianshu_qiehuan"
        private const val XLIGHT_PAGE_FROM = "ch_url-https://render.alipay.com/p/yuyan/180020010001263018/game.html"
        private const val ORCHARD_TASK_BLACKLIST_MODULE = "芭芭农场"
        private const val LEYUAN_DAILY_TASK_SCENE_CODE = "ANTORCHARD_LEYUAN_DAILY_TASK"
        private const val TASK_JINDOU_TREASURE = "ORCHARD_NORMAL_JINDOUDUOBAO"
        private const val TASK_FORTUNE_TREE = "ORCHARD_NORMAL_FACAISHU_NEW"
        private const val RECEIVE_SPREAD_MANURE_ACTIVITY_AWARD_ACTION = "RECEIVE_SPREAD_MANURE_ACTIVITY_AWARD"
        private val ORCHARD_BUSINESS_LIMIT_CODES =
            setOf(
                "CAMP_TRIGGER_ERROR",
                "PROMISE_TODAY_FINISH_TIMES_LIMIT",
            )
        private val ORCHARD_UNSUPPORTED_RPC_CODES =
            setOf(
                "400000040",
            )
        private val ORCHARD_NON_RETRYABLE_INVALID_CODES =
            setOf(
                "20020012",
                "400000001",
                "TASK_ID_INVALID",
                "ILLEGAL_ARGUMENT",
            )
        private val ORCHARD_RETRYABLE_RPC_CODES =
            setOf(
                "3000",
                "REMOTE_INVOKE_EXCEPTION",
                "OP_REPEAT_CHECK",
            )
    }

    internal var userId: String? = UserMap.currentUid
    internal var treeLevel: String? = null
    internal var currentPlantScene: String = "main"
    internal var executeIntervalInt: Int = 0
    internal var skipManurePotCollectThisRound: Boolean = false

    private lateinit var executeInterval: IntegerModelField
    internal lateinit var receiveSevenDayGift: BooleanModelField
    internal lateinit var receiveOrchardTaskAward: BooleanModelField
    internal lateinit var orchardSpreadManureCountMain: IntegerModelField
    internal lateinit var orchardSpreadManureCountYeb: IntegerModelField

    private lateinit var assistFriendList: FriendSelectionModelField

    // 模式选择
    private lateinit var plantModeField: ChoiceModelField

    override fun getName(): String = "农场"

    override fun getGroup(): ModelGroup = ModelGroup.ORCHARD

    override fun getIcon(): String = "AntOrchard.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()

        modelFields.addField(
            ChoiceModelField(
                "plantMode",
                "种植模式 | 芭芭农场",
                PlantModeType.MAIN,
                PlantModeType.nickNames,
                "选择优先推进果树、摇钱树或先摇钱树后果树的混合策略。",
            ).also { plantModeField = it },
        )

        modelFields.addField(
            IntegerModelField("executeInterval", "操作间隔(毫秒)", 500, 500, null)
                .withDesc(
                    "单次农场操作之间的等待时间，过小可能增加风控。",
                ).also { executeInterval = it },
        )
        modelFields.addField(
            BooleanModelField("receiveSevenDayGift", "七日礼包 | 领取", false)
                .withDesc(
                    "自动领取芭芭农场七日礼包奖励。",
                ).also { receiveSevenDayGift = it },
        )
        modelFields.addField(
            BooleanModelField("receiveOrchardTaskAward", "农场任务 | 领奖", false)
                .withDesc(
                    "自动领取芭芭农场任务奖励，包括肥料等常规收益。",
                ).also { receiveOrchardTaskAward = it },
        )
        modelFields.addField(
            IntegerModelField("orchardSpreadManureCount", "果树 | 每日施肥次数", 0, -1, null)
                .withDesc(
                    "每日给果树施肥的次数；施肥可推进成熟并产出庄园食材。-1 表示施肥到当日上限。",
                ).also { orchardSpreadManureCountMain = it },
        )
        modelFields.addField(
            IntegerModelField("orchardSpreadManureCountYeb", "摇钱树 | 每日施肥次数", 0, -1, null)
                .withDesc(
                    "每日给摇钱树施肥的次数；0 表示不处理摇钱树，-1 表示施肥到当日上限。",
                ).also { orchardSpreadManureCountYeb = it },
        )

        modelFields.addField(
            FriendSelectionModelField("assistFriendList", "助力 | 好友列表")
                .withDesc(
                    "仅对选中的好友执行助力流程。",
                ).also { assistFriendList = it },
        )

        return modelFields
    }

    override suspend fun runSuspend() {
        try {
            Log.orchard("执行开始-${getName()}")
            skipManurePotCollectThisRound = false
            executeIntervalInt = maxOf(executeInterval.value ?: 0, 500)

            val indexResponse = AntOrchardRpcCall.orchardIndex()
            val indexJson = JSONObject(indexResponse)

            if (indexJson.optString("resultCode") != "100") {
                Log.orchard(indexJson.optString("resultDesc", "orchardIndex 调用失败"))
                return
            }

            if (!indexJson.optBoolean("userOpenOrchard", false)) {
                Log.orchard("芭芭农场🍎[未开通，本轮跳过]")
                return
            }

            val taobaoDataStr = indexJson.optString("taobaoData")
            if (taobaoDataStr.isNotEmpty()) {
                val taobaoData = JSONObject(taobaoDataStr)
                treeLevel =
                    taobaoData
                        .optJSONObject("gameInfo")
                        ?.optJSONObject("plantInfo")
                        ?.optJSONObject("seedStage")
                        ?.optInt("stageLevel")
                        ?.toString()
            }
            currentPlantScene = indexJson.optString("currentPlantScene", currentPlantScene)

            if (userId == null) {
                userId = UserMap.currentUid
            }

            runOrchardRewardWorkflow(indexJson, userId!!)
            runOrchardCultivationWorkflow()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "start.run err:", t)
        } finally {
            Log.orchard("执行结束-${getName()}")
        }
    }

    internal fun orchardSpreadManure() {
        try {
            val modeSet = plantModeField.value
            val targetLimitMain = orchardSpreadManureCountMain.value ?: 0
            val targetLimitYeb = orchardSpreadManureCountYeb.value ?: 0
            val goldenBeanReserve = goldenBeanManureReserveAmount()

            // 1. 如果是 摇钱树模式(YEB) 或者 混合模式(HYBRID)
            if (modeSet == PlantModeType.YEB || modeSet == PlantModeType.HYBRID) {
                if (targetLimitYeb != 0) {
                    waterTree("yeb", targetLimitYeb, goldenBeanReserve)
                }
            }

            // 2. 如果是 果树模式(MAIN) 或者 混合模式(HYBRID)
            if (modeSet == PlantModeType.MAIN || modeSet == PlantModeType.HYBRID) {
                if (targetLimitMain != 0) {
                    waterTree("main", targetLimitMain, goldenBeanReserve)
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "orchardSpreadManure err:", t)
        }
    }

    private fun goldenBeanManureReserveAmount(): Int {
        val goldenBean = Model.getModel(GoldenBeanTreasure::class.java) ?: return 0
        if (!goldenBean.isEnable()) return 0

        val configuredAmount = goldenBean.goldenBeanManureExchangeDailyReserveAmount.value ?: 0
        if (configuredAmount == 0) return 0
        val exchangedToday =
            if (configuredAmount > 0) {
                Status.getIntFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_MANURE_EXCHANGE_AMOUNT) ?: 0
            } else {
                0
            }
        val configuredRemaining =
            if (configuredAmount > 0) {
                (configuredAmount - exchangedToday).coerceAtLeast(0)
            } else {
                0
            }
        if (configuredAmount < -1 ||
            (configuredAmount > 0 && configuredRemaining <= 0)
        ) return 0

        val indexResponse =
            try {
                GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index())
            } catch (error: Exception) {
                Log.printStackTrace("GoldenBeanTreasure", "农场读取金豆预留额度异常:", error)
                return 0
            }
        if (indexResponse == null || !GoldenBeanTreasureSupport.isSuccess(indexResponse)) {
            Log.goldenBean("金豆夺宝预留额度查询失败，农场本轮不设置预留")
            return 0
        }

        val exchangeInfo = indexResponse.optJSONObject("manureExchangeInfo") ?: run {
            Log.goldenBean("金豆夺宝预留额度缺少manureExchangeInfo，农场本轮不设置预留")
            return 0
        }
        if (!exchangeInfo.optBoolean("farmOpened") ||
            !exchangeInfo.optBoolean("pageOpened") ||
            !exchangeInfo.optBoolean("taobaoBinding")
        ) {
            Log.goldenBean("金豆夺宝预留额度资格未满足，农场本轮不设置预留")
            return 0
        }

        val currentManure = exchangeInfo.optInt("currentManure", 0)
        val effectiveExchangeManure = exchangeInfo.optInt("effectiveExchangeManure", 0)
        val remainQuota = exchangeInfo.optInt("remainQuota", 0)
        val minExchangeAmount = exchangeInfo.optInt("minExchangeAmount", 0)
        val reserveAmount =
            if (configuredAmount == -1) {
                minOf(effectiveExchangeManure, remainQuota)
            } else {
                configuredRemaining
            }
        if (minExchangeAmount <= 0 || reserveAmount < minExchangeAmount ||
            currentManure < reserveAmount ||
            effectiveExchangeManure < reserveAmount ||
            remainQuota < reserveAmount
        ) {
            Log.goldenBean("金豆夺宝预留额度或服务端兑换条件不足，农场本轮不设置预留")
            return 0
        }
        Log.orchard("农场为金豆夺宝预留肥料额度: $reserveAmount")
        return reserveAmount
    }

    private fun waterTree(
        targetScene: String,
        targetLimit: Int,
        goldenBeanReserve: Int,
    ) {
        val isMain = targetScene == "main"
        val waterToLimit = targetLimit == -1
        val sceneName = if (isMain) "种果树" else "种摇钱树"
        // 独立计数：果树使用原Flag，摇钱树使用新Key
        val statusKey = if (isMain) StatusFlags.FLAG_ANTORCHARD_SPREAD_MANURE_COUNT else StatusFlags.FLAG_ANTORCHARD_SPREAD_MANURE_COUNT_YEB

        var totalWatered = Status.getIntFlagToday(statusKey) ?: 0
        var fertilizerReplenishTried = false

        Log.orchard(
            if (waterToLimit) {
                "开始 $sceneName 任务，当前进度: $totalWatered，目标: 施肥到当日上限"
            } else {
                "开始 $sceneName 任务，当前进度: $totalWatered"
            },
        )

        // 切换场景
        try {
            AntOrchardRpcCall.switchPlantScene(targetScene, getSceneSource(targetScene))
            currentPlantScene = targetScene
            CoroutineUtils.sleepCompat(500)
        } catch (ignore: Throwable) {
        }

        do {
            try {
                // 检查肥料余额
                val orchardIndexData =
                    JSONObject(
                        AntOrchardRpcCall.orchardIndex(
                            source = getSceneSource(targetScene),
                            wua = buildIndexWua(targetScene),
                        ),
                    )
                if (orchardIndexData.optString("resultCode") != "100") break

                val taobaoDataStr = orchardIndexData.optString("taobaoData")
                if (taobaoDataStr.isEmpty()) break
                val batchSpreadInfo = orchardIndexData.optJSONObject("batchSpreadInfo")

                val taobaoData = JSONObject(taobaoDataStr)
                if (isPlantSelectionExplicitlyMissing(taobaoData, targetScene)) {
                    Log.orchard("$sceneName 前置状态不足: 服务端未返回已选种状态，本轮跳过施肥")
                    return
                }
                val accountInfo = taobaoData.optJSONObject("gameInfo")?.optJSONObject("accountInfo")
                if (isMain) {
                    taobaoData.optJSONObject("statistics")?.let { statistics ->
                        if (statistics.has("dailyAppWateringCount")) {
                            totalWatered = statistics.optInt("dailyAppWateringCount", 0)
                            Status.setIntFlagToday(statusKey, totalWatered)
                        }
                    }
                }
                if (!waterToLimit && totalWatered >= targetLimit) {
                    Log.orchard("$sceneName: 服务端已完成施肥目标 $totalWatered/$targetLimit")
                    return
                }

                val singleWateringCost = accountInfo?.optInt("wateringCost", 600)?.takeIf { it > 0 } ?: 600
                val happyPoint = accountInfo?.takeIf { it.has("happyPoint") }?.optInt("happyPoint")
                val effectiveGoldenBeanReserve =
                    if (happyPoint != null && happyPoint >= goldenBeanReserve) goldenBeanReserve else 0
                val availableManureForWatering =
                    happyPoint?.minus(effectiveGoldenBeanReserve)?.coerceAtLeast(0)
                val batchSpreadTimes = batchSpreadInfo?.optInt("batchSpreadTimes", 1)?.takeIf { it > 1 } ?: 1
                val batchSpreadValid = batchSpreadInfo?.optBoolean("batchSpreadValid", false) == true

                if (accountInfo != null) {
                    if (goldenBeanReserve > 0 && happyPoint != null && happyPoint < goldenBeanReserve) {
                        Log.orchard(
                            "$sceneName 当前肥料 $happyPoint 不足以满足金豆夺宝预留 $goldenBeanReserve，" +
                                "本轮取消预留并继续使用完整余额",
                        )
                    }
                    if (happyPoint == null || happyPoint < singleWateringCost) {
                        if (!fertilizerReplenishTried) {
                            fertilizerReplenishTried = true
                            val replenishResult =
                                ExchangeReplenisher.replenish(
                                    need = ExchangeEffectNeed.ORCHARD_FERTILIZER,
                                    reason = "$sceneName 肥料不足",
                                    maxCount = 1,
                                ) {
                                    AntOrchardRpcCall.orchardIndex(
                                        source = getSceneSource(targetScene),
                                        wua = buildIndexWua(targetScene),
                                    )
                                }
                            if (replenishResult == ExchangeReplenishResult.EXCHANGED) {
                                Log.orchard("$sceneName 肥料不足已触发会员积分补兑，重新查询库存")
                                continue
                            }
                            if (replenishResult == ExchangeReplenishResult.RETRY_LATER) {
                                Log.orchard("$sceneName 肥料补兑暂不可用，保留后续调度重试")
                            }
                        }
                        Log.orchard("$sceneName 肥料不足: 当前 ${happyPoint ?: 0} < 消耗 $singleWateringCost")
                        return
                    }
                    if (availableManureForWatering == null || availableManureForWatering < singleWateringCost) {
                        Log.orchard(
                            "$sceneName 保留金豆夺宝肥料额度 $effectiveGoldenBeanReserve 后可用 " +
                                "${availableManureForWatering ?: 0} < 消耗 $singleWateringCost，停止施肥",
                        )
                        return
                    }
                }

                var useBatchSpread = false
                var actualWaterTimes = 1

                val remainingTarget = if (waterToLimit) Int.MAX_VALUE else (targetLimit - totalWatered).coerceAtLeast(0)
                val canBatchByTarget = waterToLimit || remainingTarget >= batchSpreadTimes
                val batchWateringCost = singleWateringCost * batchSpreadTimes

                if (batchSpreadValid && batchSpreadTimes > 1 && canBatchByTarget &&
                    availableManureForWatering != null && availableManureForWatering >= batchWateringCost
                ) {
                    useBatchSpread = true
                    actualWaterTimes = batchSpreadTimes
                }

                val wua = SecurityBodyHelper.getSecurityBodyData(4).toString()

                // 执行施肥请求
                val spreadResponse =
                    AntOrchardRpcCall.orchardSpreadManure(
                        wua,
                        ACTION_SOURCE,
                        useBatchSpread,
                        targetScene,
                    )
                val spreadJson = JSONObject(spreadResponse)
                val resultCode = spreadJson.optString("resultCode")

                if (resultCode == "P14" || resultCode == "P13") {
                    Log.orchard("$sceneName 服务端已达当日次数或余额终态，停止施肥")
                    return
                }

                if (resultCode != "100") {
                    Log.error(TAG, "$sceneName 施肥失败[resultCode=$resultCode] raw=$spreadJson")
                    return
                }

                actualWaterTimes = spreadJson
                    .optJSONObject("batchSpreadInfo")
                    ?.takeIf { it.optBoolean("useBatchSpread", false) }
                    ?.optInt("batchSpreadTimes", actualWaterTimes)
                    ?.coerceAtLeast(1)
                    ?: actualWaterTimes

                // 更新计数
                val spreadTaobaoDataStr = spreadJson.optString("taobaoData")
                if (spreadTaobaoDataStr.isNotEmpty()) {
                    val spreadTaobaoData = JSONObject(spreadTaobaoDataStr)

                    val statistics = spreadTaobaoData.optJSONObject("statistics")
                    val hasServerDailyCount =
                        isMain && statistics != null && statistics.has("dailyAppWateringCount")

                    totalWatered =
                        if (hasServerDailyCount) {
                            statistics?.optInt("dailyAppWateringCount", 0) ?: 0
                        } else {
                            totalWatered + actualWaterTimes
                        }

                    Status.setIntFlagToday(statusKey, totalWatered)

                    var stageText = ""
                    if (isMain) {
                        stageText = spreadTaobaoData.optJSONObject("currentStage")?.optString("stageText") ?: ""
                    } else {
                        // 尝试从 yebScenePlantInfo 提取进度
                        val yebInfo = spreadTaobaoData.optJSONObject("yebScenePlantInfo")?.optJSONObject("plantProgressInfo")
                        if (yebInfo != null) {
                            val levelProgress = yebInfo.optString("levelProgress", "")
                            if (levelProgress.isNotEmpty()) {
                                stageText = "当前进度:$levelProgress%"
                            }
                        }
                    }

                    Log.orchard("施肥💩[$sceneName] $stageText|累计:$totalWatered")
                } else {
                    // 兜底逻辑
                    totalWatered += actualWaterTimes
                    Status.setIntFlagToday(statusKey, totalWatered)
                    Log.orchard("施肥💩[$sceneName] 成功|累计:$totalWatered")
                }

                CoroutineUtils.sleepCompat(500)
                // 检查施肥后礼盒
                extraInfoGet(from = "water")
                tryReceiveSpreadManureActivityAwardByQueryIndex()
            } finally {
                CoroutineUtils.sleepCompat(executeIntervalInt.toLong())
            }
        } while (waterToLimit || totalWatered < targetLimit)

        Log.orchard(
            if (waterToLimit) {
                "$sceneName 施肥结束，已按当日上限模式停止，最终累计: $totalWatered"
            } else {
                "$sceneName 施肥结束，最终累计: $totalWatered"
            },
        )
    }

    private fun isPlantSelectionExplicitlyMissing(
        taobaoData: JSONObject,
        targetScene: String,
    ): Boolean =
        if (targetScene == "main") {
            taobaoData.optJSONObject("gameInfo")?.optJSONObject("plantInfo")?.has("seedStage") == false
        } else {
            taobaoData.optJSONObject("yebScenePlantInfo")?.has("plantProgressInfo") == false
        }

    // ... 其余方法保持不变 ...
    internal fun receiveMoneyTreeReward() {
        try {
            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            // 每天7点后尝试领取
            if (hour >= 7 && !Status.hasFlagToday(StatusFlags.FLAG_ANTORCHARD_MONEY_TREE_COLLECTED)) {
                Log.orchard("检测到7点已过，尝试领取摇钱树余额奖励...")
                val res = AntOrchardRpcCall.moneyTreeTrigger()
                val json = JSONObject(res)
                if (json.optBoolean("success")) {
                    val result = json.optJSONObject("result")
                    val awardInfo = result?.optJSONObject("awardInfo")
                    val amount = awardInfo?.optString("totalAmount", "0") ?: "0"

                    if (amount != "0") {
                        Log.orchard("摇钱树💰[获得余额]#$amount 元")
                    } else {
                        Log.orchard("摇钱树暂无奖励可领")
                    }
                    Status.setFlagToday(StatusFlags.FLAG_ANTORCHARD_MONEY_TREE_COLLECTED)
                } else {
                    Log.orchard("摇钱树奖励领取失败: $json")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveMoneyTreeReward err:", t)
        }
    }

    /**
     * 获取额外信息（包含每日肥料、施肥礼盒）
     * @param from "entry" 或 "water"
     */
    internal fun extraInfoGet(from: String = "entry") {
        try {
            val source = if (from == "entry") ENTRY_SOURCE else ACTION_SOURCE
            val response = AntOrchardRpcCall.extraInfoGet(from, source)
            val jo = JSONObject(response)

            if (jo.getString("resultCode") == "100") {
                val data = jo.optJSONObject("data") ?: return
                val extraData = data.optJSONObject("extraData") ?: return
                val fertilizerPacket = extraData.optJSONObject("fertilizerPacket") ?: return

                // 状态为 waitTake 时领取
                if (fertilizerPacket.optString("status") == "todayFertilizerWaitTake") {
                    val num = fertilizerPacket.optInt("todayFertilizerNum")
                    val setResponse = JSONObject(AntOrchardRpcCall.extraInfoSet(source))
                    if (setResponse.getString("resultCode") == "100") {
                        val typeName = if (from == "water") "礼盒" else "每日"
                        Log.orchard("领取${typeName}肥料💩[${num}g]")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "extraInfoGet err:", t)
        }
    }

    internal fun checkLotteryPlus() {
        try {
            if (treeLevel == null) return
            val response = AntOrchardRpcCall.querySubplotsActivity(treeLevel!!)
            val json = JSONObject(response)
            if (!ResChecker.checkRes(TAG, json)) return

            val subplots = json.optJSONArray("subplotsActivityList") ?: return
            for (i in 0 until subplots.length()) {
                val activity = subplots.getJSONObject(i)
                if (activity.optString("activityType") == "LOTTERY_PLUS") {
                    val extendStr = activity.optString("extend")
                    if (extendStr.isNotEmpty()) {
                        val lotteryPlusInfo = JSONObject(extendStr)
                        drawLotteryPlus(lotteryPlusInfo)
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "checkLotteryPlus err", t)
        }
    }

    internal fun drawLotteryPlus(lotteryPlusInfo: JSONObject) {
        try {
            if (!lotteryPlusInfo.has("userSevenDaysGiftsItem")) return

            val itemId = lotteryPlusInfo.getString("itemId")
            val jo = lotteryPlusInfo.getJSONObject("userSevenDaysGiftsItem")
            val ja = jo.getJSONArray("userEverydayGiftItems")

            for (i in 0 until ja.length()) {
                val jo2 = ja.getJSONObject(i)
                if (jo2.getString("itemId") == itemId) {
                    if (!jo2.getBoolean("received")) {
                        Log.orchard("七日礼包: 发现未领取奖励 (itemId=$itemId)")
                        val jo3 = JSONObject(AntOrchardRpcCall.drawLottery())
                        if (jo3.getString("resultCode") == "100") {
                            val userEverydayGiftItems =
                                jo3
                                    .getJSONObject("lotteryPlusInfo")
                                    .getJSONObject("userSevenDaysGiftsItem")
                                    .getJSONArray("userEverydayGiftItems")

                            for (j in 0 until userEverydayGiftItems.length()) {
                                val jo4 = userEverydayGiftItems.getJSONObject(j)
                                if (jo4.getString("itemId") == itemId) {
                                    val awardCount = jo4.optInt("awardCount", 1)
                                    Log.orchard("七日礼包🎁[获得肥料]#${awardCount}g")
                                    break
                                }
                            }
                        } else {
                            Log.orchard(jo3.toString())
                        }
                    } else {
                        Log.orchard("七日礼包: 今日已领取")
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "drawLotteryPlus err:", t)
        }
    }

    internal fun doOrchardDailyTask(userId: String) {
        try {
            if (userId.isNotBlank()) {
                this.userId = userId
            }
            TaskFlowEngine(
                OrchardDailyTaskFlowAdapter(),
                roundSleepMs = executeIntervalInt.toLong(),
            ).run()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "doOrchardDailyTask err:", t)
        }
    }

    private inner class OrchardDailyTaskFlowAdapter : TaskFlowAdapter {
        private val loggedSkipKeys = mutableSetOf<String>()
        private var latestListTaskResponse = JSONObject()
        private var listModeLogged = false
        private var signHandled = false
        private var linkedHintsLogged = false

        override val moduleName: String = ORCHARD_TASK_BLACKLIST_MODULE
        override val flowName: String = "农场任务"
        override val continueCurrentRoundOnRetryableFailure: Boolean = true

        override fun query(): JSONObject {
            val response = AntOrchardRpcCall.orchardListTask()
            if (response.isBlank()) {
                return JSONObject()
                    .put("resultCode", "")
                    .put("resultDesc", "orchardListTask返回空")
            }
            return JSONObject(response)
        }

        override fun isQuerySuccess(response: JSONObject): Boolean = response.optString("resultCode") == "100"

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            latestListTaskResponse = response
            handleListMetadata(response)
            val taskList = response.optJSONArray("taskList") ?: return emptyList()
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                val title = resolveOrchardTaskTitle(task)
                val taskId = task.optString("taskId").trim()
                val groupId = task.optString("groupId").trim()
                val rightsTimesLimit = task.optInt("rightsTimesLimit", 0)
                val rightsTimes = task.optInt("rightsTimes", 0)
                val taskRequire = task.optInt("taskRequire", 0)
                val taskProgress = task.optInt("taskProgress", 0)
                val currentProgress: Int? =
                    when {
                        rightsTimesLimit > 0 -> rightsTimes
                        taskRequire > 0 -> taskProgress
                        else -> null
                    }
                val progressLimit: Int? =
                    when {
                        rightsTimesLimit > 0 -> rightsTimesLimit
                        taskRequire > 0 -> taskRequire
                        else -> null
                    }

                items.add(
                    TaskFlowItem(
                        id = groupId.ifBlank { taskId.ifBlank { title } },
                        title = title,
                        status = task.optString("taskStatus").trim(),
                        type = taskId,
                        sceneCode = task.optString("sceneCode").trim(),
                        actionType = task.optString("actionType").trim(),
                        blacklistKeys = listOf(groupId, taskId).filter { it.isNotBlank() },
                        raw = task,
                        progress = buildOrchardTaskProgress(task),
                        current = currentProgress,
                        limit = progressLimit,
                    ),
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
            when (item.status.uppercase()) {
                "FINISHED",
                "COMPLETE",
                "WAIT_RECEIVE",
                "TO_RECEIVE",
                "UNLOCKED",
                -> {
                    TaskFlowPhase.REWARD_READY
                }

                "TODO",
                "WAIT_COMPLETE",
                -> {
                    when {
                        item.actionType == "ANTFOREST_DEFOLIATION" -> TaskFlowPhase.BUSINESS_ACTION

                        item.raw?.let(::isSupportedTaobaoVisitTask) == true -> TaskFlowPhase.READY_TO_COMPLETE

                        item.actionType == "SYSTEM_SWITCH" -> TaskFlowPhase.BUSINESS_ACTION

                        item.actionType == "ANTFARM_COLLECT_MANURE" ||
                            item.actionType == "XLIGHT" ||
                            item.raw?.let(::orchardGamePlayContract) != null ||
                            hasOrdinaryFinishTaskContract(item) -> TaskFlowPhase.READY_TO_COMPLETE

                        else -> TaskFlowPhase.UNKNOWN
                    }
                }

                "RECEIVED",
                "HAS_RECEIVED",
                "DONE",
                "COMPLETED",
                "SUCCESS",
                -> {
                    TaskFlowPhase.TERMINAL
                }

                else -> {
                    TaskFlowPhase.UNKNOWN
                }
            }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            val phase = mapPhase(item)
            if (shouldSkipOrchardManurePot(item)) {
                return true
            }
            when (phase) {
                TaskFlowPhase.BUSINESS_ACTION -> {
                    logTaskSkipOnce(item, "action=${item.actionType} 依赖业务动作或其他模块完成，跳过")
                    return true
                }

                else -> {
                    Unit
                }
            }

            return false
        }

        override fun isBlacklisted(item: TaskFlowItem): Boolean {
            val blacklisted = super<TaskFlowAdapter>.isBlacklisted(item)
            if (blacklisted) {
                logTaskSkipOnce(item, "已在黑名单中，跳过处理")
            }
            return blacklisted
        }

        override fun isUnresolvedWhenSkipped(item: TaskFlowItem): Boolean =
            !isBlacklisted(item) && mapPhase(item) != TaskFlowPhase.TERMINAL

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            val task = item.raw ?: return missingOrchardRawResult(item, "triggerTbTask")
            val taskId = task.optString("taskId")
            val taskPlantType = task.optString("taskPlantType")
            if (taskId.isBlank() || taskPlantType.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    message = "缺少 taskId/taskPlantType",
                    rpc = "AntOrchardRpcCall.triggerTbTask",
                    detail = orchardActionDetail(item, "receive"),
                )
            }

            val response = claimTaskReward(taskId, taskPlantType, task)
            if (isOrchardRpcSuccessResponse(response.first)) {
                val refreshedTask = queryOrchardTaskById(taskId, response.second)
                if (refreshedTask?.optString("taskStatus") != "RECEIVED") {
                    return TaskFlowActionResult.defer(
                        deferredReason = DeferredReason.STATE_CONFIRMATION,
                        message = "triggerTbTask已返回成功，等待同源任务状态确认RECEIVED",
                        rpc = "AntOrchardRpcCall.triggerTbTask",
                        raw = refreshedTask?.toString() ?: response.first.toString(),
                        detail = orchardActionDetail(item, "receive"),
                        refreshAfterAction = true,
                    )
                }
                val awardCount = task.optInt("awardCount", task.optInt("confAwardCount", 0))
                val awardSuffix = if (awardCount > 0) "#${awardCount}g肥料" else ""
                Log.orchard("领取奖励🎖️[${item.title}]$awardSuffix")
                return TaskFlowActionResult.success()
            }
            return buildOrchardTaskFailureResult(
                response = response.first,
                taskId = item.id,
                title = item.title,
                action = "receive",
                rpc = "AntOrchardRpcCall.triggerTbTask",
                item = item,
            )
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val task = item.raw ?: return missingOrchardRawResult(item, "complete")
            orchardGamePlayContract(task)?.let { contract ->
                val ack = GameCenterPlayRpcCall.submitForAck(contract)
                val response = ack.response
                    ?: return TaskFlowActionResult.failure(
                        failureType = ack.failureType,
                        message = "时长动作响应无法解析",
                        rpc = "GameCenterPlayRpcCall.submit",
                        raw = ack.raw,
                        detail = orchardActionDetail(item, "playDuration"),
                        continueCurrentRoundOnFailure =
                            ack.failureType == TaskRpcFailureType.RETRYABLE_RPC ||
                                ack.failureType == TaskRpcFailureType.BUSINESS_LIMIT,
                    )
                if (!ack.accepted) {
                    return TaskFlowActionResult.failure(
                        failureType = ack.failureType,
                        code = response.optString("resultCode"),
                        message = response.optString("resultDesc", response.optString("desc", ack.raw)),
                        rpc = "GameCenterPlayRpcCall.submit",
                        raw = ack.raw,
                        detail = orchardActionDetail(item, "playDuration"),
                        continueCurrentRoundOnFailure =
                            ack.failureType == TaskRpcFailureType.RETRYABLE_RPC ||
                                ack.failureType == TaskRpcFailureType.BUSINESS_LIMIT,
                    )
                }
                Log.orchard("农场乐园任务[${item.title}]时长上报已接受，继续任务完成闭环")
                return executeOrchardFinishTask(
                    action = "GAME_PLAY",
                    sceneCode = item.sceneCode,
                    taskId = task.optString("taskId"),
                    groupId = task.optString("groupId"),
                    title = item.title,
                    task = task,
                )
            }
            if (isSupportedTaobaoVisitTask(task)) {
                return executeTaobaoVisitTask(task, item)
            }
            return when (item.actionType) {
                "XLIGHT" -> {
                    completeOrchardXLightTask(item, task)
                }

                "VISIT" -> {
                    executeOrchardFinishTask(
                        action = "VISIT",
                        sceneCode = item.sceneCode,
                        taskId = task.optString("taskId"),
                        groupId = task.optString("groupId"),
                        title = item.title,
                        task = task,
                    )
                }

                "ANTFARM_COLLECT_MANURE" -> {
                    collectOrchardManurePotIfNeeded(item, latestListTaskResponse)
                }

                "SYSTEM_SWITCH" -> {
                    executeTaobaoVisitTask(task, item)
                }

                else -> {
                    executeOrchardFinishTask(
                        action = item.actionType,
                        sceneCode = item.sceneCode,
                        taskId = task.optString("taskId"),
                        groupId = task.optString("groupId"),
                        title = item.title,
                        task = task,
                    )
                }
            }
        }

        override fun actionKey(
            item: TaskFlowItem,
            action: TaskFlowAction,
        ): String = "${action.logName}:${item.id}:${item.actionType}:${item.status}:${item.progress.ifBlank { "NO_PROGRESS" }}"

        override fun onAllTasksDone(snapshot: io.github.aoguai.sesameag.task.common.TaskFlowSnapshot) {
            Log.orchard("农场任务列表已无待处理任务")
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.error(TAG, "农场任务列表查询失败 raw=$response")
        }

        override fun logInfo(message: String) {
            Log.orchard(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun handleListMetadata(response: JSONObject) {
            if (!listModeLogged) {
                val inTeam = response.optBoolean("inTeam", false)
                Log.orchard(if (inTeam) "当前为农场 team 模式（合种/帮帮种已开启）" else "当前为普通单人农场模式")
                listModeLogged = true
            }
            if (!signHandled && response.has("signTaskInfo")) {
                signHandled = true
                orchardSign(response.getJSONObject("signTaskInfo"))
            }
            if (!linkedHintsLogged) {
                linkedHintsLogged = true
                logLinkedTaskHints(response)
            }
        }

        private fun hasOrdinaryFinishTaskContract(item: TaskFlowItem): Boolean =
            !userId.isNullOrBlank() && item.sceneCode.isNotBlank() && item.type.isNotBlank()

        private fun orchardGamePlayContract(task: JSONObject): GameCenterPlayRpcCall.Contract? {
            val display = task.optJSONObject("taskDisplayConfig") ?: return null
            if (display.optString("type").trim() != "nongchangleyuan") return null
            val duration = display.optJSONObject("floatBallConfig")?.optInt("floatBallDuration", 0) ?: 0
            if (duration <= 0) return null
            val contract = GameCenterPlayRpcCall.resolveContract(task, display) ?: return null
            return contract.copy(playTime = duration.coerceAtMost(Int.MAX_VALUE - 1) + 1)
        }

        private fun completeOrchardXLightTask(
            item: TaskFlowItem,
            task: JSONObject,
        ): TaskFlowActionResult = executeXLightTask(task, item)

        private fun missingOrchardRawResult(
            item: TaskFlowItem,
            action: String,
        ): TaskFlowActionResult =
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "缺少原始任务数据",
                rpc = "OrchardDailyTaskFlowAdapter.$action",
                detail = orchardActionDetail(item, action),
            )

        private fun logTaskSkipOnce(
            item: TaskFlowItem,
            reason: String,
        ) {
            val key = "${item.id}:${item.actionType}:$reason"
            if (loggedSkipKeys.add(key)) {
                Log.orchard("农场任务⏭️[${item.title}] $reason")
            }
        }

        private fun shouldSkipOrchardManurePot(item: TaskFlowItem): Boolean {
            if (item.actionType != "ANTFARM_COLLECT_MANURE") {
                return false
            }
            if (skipManurePotCollectThisRound) {
                logTaskSkipOnce(item, "本轮已触发肥料太少保护，跳过重复收取")
                return true
            }
            val manureFactory = latestListTaskResponse.optJSONObject("manureFactory") ?: return false
            if (!manureFactory.optBoolean("canCollect", false)) {
                // orchardListTask can keep this task as TODO after the pot has already been collected.
                logTaskSkipOnce(item, "肥料池前置条件未满足，本轮明确延后")
                return true
            }
            return false
        }
    }

    internal fun receiveLeyuanDailyTaskAwards() {
        try {
            val attemptedTaskKeys = mutableSetOf<String>()
            var remainingRounds = 0
            while (remainingRounds > 0 || attemptedTaskKeys.isEmpty()) {
                val response = JSONObject(AntOrchardRpcCall.queryOptionalPlay())
                if (!ResChecker.checkRes(TAG, response)) {
                    Log.orchard("农场乐园奖励查询失败: $response")
                    return
                }

                val taskList = response.optJSONObject("taskTriggerPlayInfo")?.optJSONArray("taskList") ?: return
                if (remainingRounds == 0) {
                    remainingRounds = taskList.length()
                }
                if (remainingRounds <= 0) {
                    return
                }

                val targetTask = findNextLeyuanAwardTask(taskList, attemptedTaskKeys) ?: return
                val sceneCode = targetTask.optString("sceneCode")
                val taskType = targetTask.optString("taskType")
                val taskKey = buildLeyuanTaskKey(sceneCode, taskType)
                attemptedTaskKeys.add(taskKey)
                remainingRounds--

                val awardCount = resolveLeyuanAwardCount(targetTask)
                val title = resolveLeyuanTaskTitle(targetTask)
                if (awardCount == null) {
                    Log.orchard("农场乐园奖励待复核[$title] 缺少有效 awardCount | raw=$targetTask")
                    continue
                }

                val awardResp =
                    JSONObject(
                        AntOrchardRpcCall.receiveTaskAwardAntOrchard(sceneCode, taskType, awardCount),
                    )
                if (!isOrchardRpcSuccessResponse(awardResp)) {
                    Log.orchard(
                        "农场乐园奖励领取失败[$title] " +
                            "classification=${classifyOrchardTaskFailure(awardResp)} raw=$awardResp",
                    )
                    continue
                }

                CoroutineUtils.sleepCompat(executeIntervalInt.toLong())
                val refreshedResponse = JSONObject(AntOrchardRpcCall.queryOptionalPlay())
                if (!ResChecker.checkRes(TAG, refreshedResponse)) {
                    Log.orchard("农场乐园奖励回查失败[$title] $refreshedResponse")
                    return
                }
                val refreshedTaskList =
                    refreshedResponse.optJSONObject("taskTriggerPlayInfo")?.optJSONArray("taskList")
                val refreshedTask = findLeyuanTask(refreshedTaskList, sceneCode, taskType)
                when {
                    refreshedTask == null -> {
                        Log.orchard("农场乐园奖励待复核[$title] 回查未返回任务")
                    }

                    hasLeyuanAwardStateProgressed(targetTask, refreshedTask) -> {
                        Log.orchard("农场乐园🎮[$title]#${awardCount}g肥料")
                    }

                    else -> {
                        Log.orchard("农场乐园奖励待复核[$title] RPC成功但回查未推进 raw=$refreshedTask")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveLeyuanDailyTaskAwards err:", t)
        }
    }

    private fun findNextLeyuanAwardTask(
        taskList: JSONArray,
        attemptedTaskKeys: Set<String>,
    ): JSONObject? {
        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            val sceneCode = task.optString("sceneCode")
            val taskType = task.optString("taskType")
            if (sceneCode != LEYUAN_DAILY_TASK_SCENE_CODE) continue
            if (taskType.isBlank() || task.optString("taskStatus") != "FINISHED") continue
            if (buildLeyuanTaskKey(sceneCode, taskType) in attemptedTaskKeys) continue
            return task
        }
        return null
    }

    private fun findLeyuanTask(
        taskList: JSONArray?,
        sceneCode: String,
        taskType: String,
    ): JSONObject? {
        if (taskList == null) return null
        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            if (task.optString("sceneCode") == sceneCode && task.optString("taskType") == taskType) {
                return task
            }
        }
        return null
    }

    private fun buildLeyuanTaskKey(
        sceneCode: String,
        taskType: String,
    ): String = "$sceneCode:$taskType"

    private fun resolveLeyuanAwardCount(task: JSONObject): Int? =
        sequenceOf("awardCount", "totalAwardCount", "nextStageAwardCount")
            .map { task.optInt(it) }
            .firstOrNull { it > 0 }

    private fun resolveLeyuanTaskTitle(task: JSONObject): String =
        task.optJSONObject("bizInfo")?.optString("title")?.takeIf { it.isNotBlank() }
            ?: task.optString("taskType")

    private fun hasLeyuanAwardStateProgressed(
        previousTask: JSONObject,
        refreshedTask: JSONObject,
    ): Boolean {
        if (refreshedTask.optString("taskStatus") in setOf("RECEIVED", "HAS_RECEIVED", "DONE", "COMPLETED")) {
            return true
        }
        return sequenceOf("rightsTimes", "taskProgress", "receiveTimes", "rewardTimes")
            .any { field -> refreshedTask.optInt(field) > previousTask.optInt(field) }
    }

    private fun extractManurePotNos(potList: JSONArray?): LinkedHashSet<String> {
        val potNos = LinkedHashSet<String>()
        if (potList == null) {
            return potNos
        }
        for (i in 0 until potList.length()) {
            potList
                .optJSONObject(i)
                ?.optString("manurePotNO")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { potNos.add(it) }
        }
        return potNos
    }

    private fun collectOrchardManurePotIfNeeded(
        item: TaskFlowItem,
        listTaskJson: JSONObject,
    ): TaskFlowActionResult {
        try {
            if (skipManurePotCollectThisRound) {
                return TaskFlowActionResult.defer(
                    deferredReason = DeferredReason.PREREQUISITE_PENDING,
                    message = "本轮已触发肥料太少保护，等待后续前置动作推进",
                    rpc = "AntOrchardRpcCall.collectManurePot",
                    detail = orchardActionDetail(item, "collectManurePot"),
                )
            }
            val manureFactory =
                listTaskJson.optJSONObject("manureFactory") ?: run {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = "缺少 manureFactory 字段",
                        rpc = "AntOrchardRpcCall.collectManurePot",
                        raw = listTaskJson.toString(),
                        detail = orchardActionDetail(item, "collectManurePot"),
                    )
                }
            if (!manureFactory.optBoolean("canCollect", false)) {
                return TaskFlowActionResult.defer(
                    deferredReason = DeferredReason.PREREQUISITE_PENDING,
                    message = "当前不可收取（canCollect=false），等待后续前置动作推进",
                    rpc = "AntOrchardRpcCall.collectManurePot",
                    detail = orchardActionDetail(item, "collectManurePot"),
                )
            }

            val collectTargets = LinkedHashSet<String>()
            val currentUserId = userId ?: UserMap.currentUid
            if (!currentUserId.isNullOrBlank()) {
                val latestAnimalShow = JSONObject(AntOrchardRpcCall.queryAnimalShowInfo(currentUserId))
                if (ResChecker.checkRes(TAG, latestAnimalShow)) {
                    collectTargets.addAll(extractManurePotNos(latestAnimalShow.optJSONArray("manurePotList")))
                }
            }
            if (collectTargets.isEmpty()) {
                val fallbackPotList = manureFactory.optJSONObject("manure")?.optJSONArray("manurePotList")
                collectTargets.addAll(extractManurePotNos(fallbackPotList))
            }
            if (collectTargets.isEmpty()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    message = "缺少可收取的 manurePotNO",
                    rpc = "AntOrchardRpcCall.collectManurePot",
                    raw = listTaskJson.toString(),
                    detail = orchardActionDetail(item, "collectManurePot"),
                )
            }

            val collectResp =
                JSONObject(
                    AntOrchardRpcCall.collectManurePot(collectTargets.joinToString(","), ACTION_SOURCE),
                )
            if (ResChecker.checkRes(TAG, collectResp)) {
                val collected = collectResp.optInt("collectManurePotNum", 0)
                if (collected > 0) {
                    Log.orchard("庄园鸡屎💩[收取肥料]#${collected}g")
                } else {
                    Log.orchard("庄园鸡屎💩任务：已触发收取，但本次为0g")
                }
                return TaskFlowActionResult.success()
            } else {
                val resultCode = collectResp.optString("resultCode").ifBlank { collectResp.optString("code") }
                val desc =
                    collectResp
                        .optString("memo")
                        .ifBlank { collectResp.optString("desc", collectResp.optString("resultDesc")) }
                if (resultCode == "G03" || desc.contains("肥料太少")) {
                    skipManurePotCollectThisRound = true
                    return TaskFlowActionResult.defer(
                        deferredReason = DeferredReason.PREREQUISITE_PENDING,
                        message = desc.ifBlank { "肥料太少，等待后续前置动作推进" },
                        rpc = "AntOrchardRpcCall.collectManurePot",
                        raw = collectResp.toString(),
                        detail = orchardActionDetail(item, "collectManurePot"),
                    )
                }
                return buildOrchardTaskFailureResult(
                    response = collectResp,
                    taskId = item.id,
                    title = item.title,
                    action = "collectManurePot",
                    rpc = "AntOrchardRpcCall.collectManurePot",
                    item = item,
                )
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "collectOrchardManurePotIfNeeded err:", t)
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = t.message.orEmpty(),
                rpc = "AntOrchardRpcCall.collectManurePot",
                raw = t.toString(),
                detail = orchardActionDetail(item, "collectManurePot"),
            )
        }
    }

    private fun executeXLightTask(
        task: JSONObject,
        item: TaskFlowItem,
    ): TaskFlowActionResult {
        try {
            val title = item.title
            val browseConfig =
                buildOrchardBrowseTaskConfig(task, title)
                    ?: return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = "无法构建XLight浏览配置",
                        rpc = "XLightRpcCall.xlightPlugin",
                        raw = task.toString(),
                        detail = orchardActionDetail(item, "xlight"),
                    )
            var finishedCount = 0
            var remainingRounds = browseConfig.rounds
            var round = 1
            var lastFailure: TaskFlowActionResult? = null

            while (remainingRounds > 0) {
                val roundResult = executeOrchardBrowseRound(browseConfig, item, round)
                if (roundResult.finishedCount <= 0) {
                    lastFailure = roundResult.failure
                    break
                }
                finishedCount += roundResult.finishedCount
                remainingRounds = (remainingRounds - roundResult.finishedCount).coerceAtLeast(0)
                if (roundResult.failure != null) {
                    lastFailure = roundResult.failure
                    break
                }
                if (remainingRounds > 0) {
                    round++
                    CoroutineUtils.sleepCompat(executeIntervalInt.toLong())
                }
            }

            if (lastFailure?.failureType == TaskRpcFailureType.BUSINESS_LIMIT) {
                return lastFailure
            }
            if (finishedCount > 0) {
                Log.orchard("农场浏览任务📺[$title] 完成${finishedCount}次浏览奖励")
                return TaskFlowActionResult.success()
            }
            return lastFailure ?: TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "XLight浏览任务未完成",
                rpc = "XLightRpcCall.finishTask",
                raw = task.toString(),
                detail = orchardActionDetail(item, "xlight"),
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "executeXLightTask err:", t)
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = t.message.orEmpty(),
                rpc = "XLightRpcCall.finishTask",
                raw = t.toString(),
                detail = orchardActionDetail(item, "xlight"),
            )
        }
    }

    private fun executeOrchardFinishTask(
        action: String,
        sceneCode: String,
        taskId: String,
        groupId: String,
        title: String,
        task: JSONObject,
    ): TaskFlowActionResult {
        val currentUserId = userId
        if (currentUserId.isNullOrBlank()) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "缺少 userId",
                rpc = "AntOrchardRpcCall.finishTask",
                detail = "taskId=${groupId.ifBlank { taskId }} taskName=$title action=$action sceneCode=$sceneCode",
            )
        }
        if (sceneCode.isBlank() || taskId.isBlank()) {
            if (action == "VISIT") {
                logUnsupportedVisitTask(task, title)
            }
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "缺少 sceneCode/taskId",
                rpc = "AntOrchardRpcCall.finishTask",
                raw = task.toString(),
                detail = "taskId=${groupId.ifBlank { taskId }} taskName=$title action=$action sceneCode=$sceneCode",
            )
        }
        val source = resolveTaskActionSource(task) ?: ACTION_SOURCE
        val responseText = AntOrchardRpcCall.finishTask(currentUserId, sceneCode, taskId, source)
        if (responseText.isBlank()) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.RETRYABLE_RPC,
                message = "finishTask返回空",
                rpc = "AntOrchardRpcCall.finishTask",
                detail = "taskId=${groupId.ifBlank { taskId }} taskName=$title action=$action sceneCode=$sceneCode",
                continueCurrentRoundOnFailure = true,
            )
        }
        val finishResponse = JSONObject(responseText)
        if (isOrchardRpcSuccessResponse(finishResponse)) {
            val refreshedTask = queryOrchardTaskById(taskId, source)
            when {
                refreshedTask == null -> {
                    return TaskFlowActionResult.defer(
                        deferredReason = DeferredReason.STATE_CONFIRMATION,
                        message = "finishTask已返回成功，等待任务列表回查",
                        rpc = "AntOrchardRpcCall.finishTask",
                        raw = finishResponse.toString(),
                        detail = "taskId=${groupId.ifBlank { taskId }} taskName=$title action=$action sceneCode=$sceneCode",
                    )
                }

                hasOrchardTaskStateProgressed(task, refreshedTask) -> {
                    Log.orchard("农场任务🧾[$title]")
                    return TaskFlowActionResult.success(refreshAfterAction = true)
                }

                else -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = "finishTask成功但任务状态未推进",
                        rpc = "AntOrchardRpcCall.finishTask",
                        raw = "finish=$finishResponse refreshed=$refreshedTask",
                        detail = "taskId=${groupId.ifBlank { taskId }} taskName=$title action=$action sceneCode=$sceneCode",
                    )
                }
            }
        }
        return buildOrchardTaskFailureResult(
            response = finishResponse,
            taskId = groupId.ifBlank { taskId },
            title = title,
            action = action,
            rpc = "AntOrchardRpcCall.finishTask",
            task = task,
        )
    }

    private fun hasOrchardTaskStateProgressed(
        previousTask: JSONObject,
        refreshedTask: JSONObject,
    ): Boolean {
        if (previousTask.optString("taskStatus") != refreshedTask.optString("taskStatus")) {
            return true
        }
        return sequenceOf("rightsTimes", "taskProgress", "receiveTimes", "rewardTimes")
            .any { field -> refreshedTask.optInt(field) > previousTask.optInt(field) }
    }

    private fun isOrchardRpcSuccessResponse(response: JSONObject): Boolean {
        if (response.optBoolean("success") || response.optBoolean("isSuccess")) {
            return true
        }

        when (val resultCode = response.opt("resultCode")) {
            is Number -> {
                if (resultCode.toInt() == 100 || resultCode.toInt() == 200) {
                    return true
                }
            }

            is String -> {
                if (resultCode.equals("SUCCESS", ignoreCase = true) ||
                    resultCode == "100" ||
                    resultCode == "200"
                ) {
                    return true
                }
            }
        }

        return response.optString("memo").equals("SUCCESS", ignoreCase = true)
    }

    private fun classifyOrchardTaskFailure(response: JSONObject): TaskRpcFailureType {
        val errorCode = extractOrchardRpcErrorCode(response)
        return when {
            errorCode in ORCHARD_BUSINESS_LIMIT_CODES -> TaskRpcFailureType.BUSINESS_LIMIT
            errorCode in ORCHARD_UNSUPPORTED_RPC_CODES -> TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE
            errorCode in ORCHARD_NON_RETRYABLE_INVALID_CODES -> TaskRpcFailureType.NON_RETRYABLE_INVALID
            errorCode in ORCHARD_RETRYABLE_RPC_CODES || isMarkedRetryable(response) -> TaskRpcFailureType.RETRYABLE_RPC
            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun resolveOrchardTaskTitle(task: JSONObject): String {
        val displayConfig = task.optJSONObject("taskDisplayConfig")
        return displayConfig
            ?.optString("title")
            .orEmpty()
            .trim()
            .ifBlank { task.optString("title").trim() }
            .ifBlank { task.optString("taskName").trim() }
            .ifBlank { task.optString("taskId").trim() }
            .ifBlank { task.optString("groupId").trim() }
            .ifBlank { "未知任务" }
    }

    private fun buildOrchardTaskProgress(task: JSONObject): String {
        val parts = mutableListOf<String>()
        val rightsTimesLimit = task.optInt("rightsTimesLimit", 0)
        if (rightsTimesLimit > 0) {
            parts.add("rights=${task.optInt("rightsTimes", 0)}/$rightsTimesLimit")
        }
        val taskRequire = task.optInt("taskRequire", 0)
        if (taskRequire > 0) {
            parts.add("task=${task.optInt("taskProgress", 0)}/$taskRequire")
        }
        val awardCount = task.optInt("awardCount", task.optInt("confAwardCount", 0))
        if (awardCount > 0) {
            parts.add("award=$awardCount")
        }
        return parts.joinToString(" ")
    }

    private fun orchardActionDetail(
        item: TaskFlowItem,
        action: String,
    ): String {
        val task = item.raw
        return buildString {
            append("taskId=")
            append(item.id.ifBlank { item.type })
            append(" taskName=")
            append(item.title)
            append(" action=")
            append(action)
            append(" actionType=")
            append(item.actionType.ifBlank { "UNKNOWN" })
            append(" sceneCode=")
            append(item.sceneCode.ifBlank { "UNKNOWN" })
            if (item.progress.isNotBlank()) {
                append(" progress=")
                append(item.progress)
            }
            task
                ?.optString("taskPlantType")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    append(" taskPlantType=")
                    append(it)
                }
            task
                ?.optString("groupId")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    append(" groupId=")
                    append(it)
                }
        }
    }

    private fun buildOrchardTaskFailureResult(
        response: JSONObject,
        taskId: String,
        title: String,
        action: String,
        rpc: String,
        item: TaskFlowItem? = null,
        task: JSONObject? = null,
    ): TaskFlowActionResult {
        val failureType = classifyOrchardTaskFailure(response)
        val rawTask = item?.raw ?: task
        val detail =
            buildString {
                append("taskId=")
                append(taskId.ifBlank { rawTask?.optString("taskId").orEmpty().ifBlank { "UNKNOWN" } })
                append(" taskName=")
                append(title.ifBlank { "UNKNOWN" })
                append(" action=")
                append(action)
                rawTask
                    ?.optString("actionType")
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append(" actionType=")
                        append(it)
                    }
                rawTask
                    ?.optString("sceneCode")
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append(" sceneCode=")
                        append(it)
                    }
                rawTask
                    ?.optString("taskPlantType")
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append(" taskPlantType=")
                        append(it)
                    }
                rawTask
                    ?.optString("groupId")
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append(" groupId=")
                        append(it)
                    }
                item
                    ?.progress
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append(" progress=")
                        append(it)
                    }
            }
        return TaskFlowActionResult.failure(
            failureType = failureType,
            code = extractOrchardFailureCode(response),
            message = extractOrchardRpcMessage(response),
            rpc = rpc,
            raw = response.toString(),
            detail = detail,
            continueCurrentRoundOnFailure =
                failureType == TaskRpcFailureType.RETRYABLE_RPC ||
                    failureType == TaskRpcFailureType.BUSINESS_LIMIT,
        )
    }

    private fun buildOrchardBrowseFailureResult(
        item: TaskFlowItem,
        message: String,
        rpc: String,
        raw: String = "",
        failureType: TaskRpcFailureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
    ): TaskFlowActionResult =
        TaskFlowActionResult.failure(
            failureType = failureType,
            message = message,
            rpc = rpc,
            raw = raw,
            detail = orchardActionDetail(item, "xlight"),
            continueCurrentRoundOnFailure =
                failureType == TaskRpcFailureType.RETRYABLE_RPC ||
                    failureType == TaskRpcFailureType.BUSINESS_LIMIT,
        )

    private fun buildOrchardXLightRestriction(
        item: TaskFlowItem,
        response: JSONObject,
    ): TaskFlowActionResult? {
        if (!RpcOfflineRisk.isAdTrafficRisk(response)) {
            return null
        }
        return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.BUSINESS_LIMIT,
            code = extractOrchardFailureCode(response).ifBlank { "AD_TRAFFIC_RISK" },
            message = extractOrchardRpcMessage(response),
            rpc = "XLightRpcCall.xlightPlugin",
            raw = response.toString(),
            detail = orchardActionDetail(item, "xlight"),
            continueCurrentRoundOnFailure = true,
        )
    }

    private fun extractOrchardFailureCode(response: JSONObject): String {
        val code = extractOrchardRpcErrorCode(response)
        return if (code.isBlank() && RpcOfflineRisk.isAdTrafficRisk(response)) {
            "AD_TRAFFIC_RISK"
        } else {
            code
        }
    }

    private fun extractOrchardRpcErrorCode(response: JSONObject): String =
        response
            .optString("resultCode")
            .ifBlank { response.optString("errorCode") }
            .ifBlank { response.optString("code") }
            .ifBlank { response.optString("resultStatus") }
            .ifBlank { response.optString("retCode") }
            .ifBlank { response.optString("sspErrorCode") }
            .ifBlank { response.optString("errCode") }

    private fun extractOrchardRpcMessage(response: JSONObject): String =
        response
            .optString("memo")
            .ifBlank { response.optString("desc") }
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("sspErrorMsg") }
            .ifBlank { response.optString("resultMsg") }
            .ifBlank { response.optString("errorMessage") }
            .ifBlank { response.toString() }

    private fun isMarkedRetryable(response: JSONObject): Boolean =
        listOf("retryable", "retriable", "canRetry").any { key ->
            response.has(key) && response.optBoolean(key, false)
        }

    private fun isOrchardTaskBlacklisted(
        taskId: String,
        title: String = "",
    ): Boolean {
        if (TaskBlacklist.isTaskInBlacklist(ORCHARD_TASK_BLACKLIST_MODULE, taskId)) {
            return true
        }
        if (title.isNotBlank() && TaskBlacklist.isTaskInBlacklist(ORCHARD_TASK_BLACKLIST_MODULE, title)) {
            return true
        }
        return title.isNotBlank() &&
            TaskBlacklist.isTaskInBlacklist(ORCHARD_TASK_BLACKLIST_MODULE, "$taskId|$title")
    }

    private fun isTaobaoVisitTask(task: JSONObject): Boolean =
        task.optString("taskPlantType") == "TAOBAO" &&
            task.optString("actionType") in setOf("VISIT", "SYSTEM_SWITCH")

    private fun resolveTaskActionSource(task: JSONObject): String? {
        if (task.optString("taskId") in setOf(TASK_JINDOU_TREASURE, TASK_FORTUNE_TREE)) {
            return ENTRY_SOURCE
        }
        if (isTaobaoVisitTask(task)) {
            return resolveTaobaoVisitSource(task)
        }
        return resolveTaskUrlSource(task, listOf("source", "chInfo", "alipayFarmSource"))
    }

    private fun resolveTaobaoVisitSource(task: JSONObject): String? =
        resolveTaskUrlSource(task, listOf("alipayFarmSource", "source", "chInfo"))

    private fun resolveTaskUrlSource(
        task: JSONObject,
        parameterNames: List<String>,
    ): String? {
        val targetUrl = task.optJSONObject("taskDisplayConfig")?.optString("targetUrl").orEmpty()
        if (targetUrl.isBlank()) {
            return null
        }
        val carriers = linkedSetOf(targetUrl)
        val pendingCarriers = mutableListOf(targetUrl)
        var carrierIndex = 0
        while (carrierIndex < pendingCarriers.size) {
            val carrier = pendingCarriers[carrierIndex++]
            listOf("url", "sourceUrl", "schema").forEach { key ->
                UrlUtil.getFullNestedUrl(carrier, key)
                    ?.takeIf { it.isNotBlank() && carriers.add(it) }
                    ?.let(pendingCarriers::add)
            }
        }
        return parameterNames.asSequence()
            .flatMap { parameter -> carriers.asSequence().map { UrlUtil.getParamValue(it, parameter) } }
            .firstOrNull { !it.isNullOrBlank() }
    }

    private fun isSupportedTaobaoVisitTask(task: JSONObject): Boolean {
        if (!isTaobaoVisitTask(task) || resolveTaobaoVisitSource(task).isNullOrBlank()) {
            return false
        }
        return task.optJSONObject("taobaoTaskParams") != null
    }

    private fun executeTaobaoVisitTask(
        task: JSONObject,
        item: TaskFlowItem,
    ): TaskFlowActionResult {
        val title = item.title
        val taskId = task.optString("taskId")
        val actualSource = resolveTaobaoVisitSource(task)
        if (actualSource == null || !isSupportedTaobaoVisitTask(task)) {
            Log.orchard("农场任务⏭️[$title] TAOBAO浏览任务缺少已验证的任务结构，保留后续重试机会")
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "TAOBAO浏览任务未匹配已验证结构",
                rpc = "AntOrchardRpcCall.orchardSimple",
                raw = task.toString(),
                detail = orchardActionDetail(item, "taobaoVisit"),
            )
        }
        if (taskId.isBlank()) {
            Log.orchard("农场任务⏭️[$title] TAOBAO浏览任务缺少 taskId，跳过")
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "TAOBAO浏览任务缺少 taskId",
                rpc = "AntOrchardRpcCall.orchardSimple",
                raw = task.toString(),
                detail = orchardActionDetail(item, "taobaoVisit"),
            )
        }

        val simpleResp = JSONObject(AntOrchardRpcCall.orchardSimple(actualSource))
        val simpleResult = simpleResp.optJSONObject("resData") ?: simpleResp
        if (!ResChecker.checkRes(TAG, simpleResult)) {
            Log.orchard("农场任务⏭️[$title] TAOBAO浏览触发失败: $simpleResp")
            return buildOrchardTaskFailureResult(
                response = simpleResult,
                taskId = item.id,
                title = item.title,
                action = "taobaoVisit",
                rpc = "AntOrchardRpcCall.orchardSimple",
                item = item,
            )
        }

        val refreshedTask = queryOrchardTaskById(taskId, actualSource)
        val taskStatus = refreshedTask?.optString("taskStatus").orEmpty()
        if (taskStatus == "FINISHED" || taskStatus == "RECEIVED") {
            val awardCount =
                refreshedTask
                    ?.optInt("awardCount")
                    ?.takeIf { it > 0 }
                    ?: refreshedTask
                        ?.optInt("confAwardCount", 0)
                        ?.takeIf { it > 0 }
            val awardSuffix = awardCount?.let { "#${it}g肥料" }.orEmpty()
            Log.orchard("农场任务🧾[$title]$awardSuffix")
            return TaskFlowActionResult.success()
        }

        if (refreshedTask != null) {
            Log.orchard("农场任务⏭️[$title] TAOBAO浏览已触发，当前列表状态=$taskStatus，等待服务端状态确认")
        } else {
            Log.orchard("农场任务⏭️[$title] TAOBAO浏览已触发，当前未能立即查询任务状态，等待后续统一刷新")
        }
        return TaskFlowActionResult.defer(
            deferredReason = DeferredReason.STATE_CONFIRMATION,
            message = "TAOBAO浏览已触发，等待服务端状态确认",
            rpc = "AntOrchardRpcCall.orchardSimple",
            raw = refreshedTask?.toString() ?: task.toString(),
            detail = orchardActionDetail(item, "taobaoVisit"),
            refreshAfterAction = true,
        )
    }

    private fun queryOrchardTaskById(
        taskId: String,
        source: String = ENTRY_SOURCE,
    ): JSONObject? {
        val response = JSONObject(AntOrchardRpcCall.orchardListTask(source))
        if (response.optString("resultCode") != "100") {
            Log.orchard("农场任务状态查询失败[$taskId]: $response")
            return null
        }
        val taskList = response.optJSONArray("taskList") ?: return null
        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            if (task.optString("taskId") == taskId) {
                return task
            }
        }
        return null
    }

    private fun executeOrchardBrowseRound(
        config: OrchardBrowseTaskConfig,
        item: TaskFlowItem,
        round: Int,
    ): OrchardBrowseRoundResult {
        val title = item.title
        val session = buildXLightSession()
        val processedEventKeys = mutableSetOf<String>()
        var playingPageInfo: String? = null
        var pageNo = 1
        var finishedCount = 0

        while (pageNo <= 5) {
            val response =
                XLightRpcCall.xlightPlugin(
                    pageUrl = config.pageUrl,
                    pageFrom = XLIGHT_PAGE_FROM,
                    session = session,
                    spaceCode = config.spaceCode,
                    referToken = config.referToken,
                    searchInfo = if (config.usePagedSearchInfo) buildOrchardBrowseSearchInfo(pageNo) else null,
                    playingPageInfo = playingPageInfo,
                    pageNo = pageNo,
                    positionExtMap = config.positionExtMap,
                )
            if (response.isBlank()) {
                Log.orchard("农场浏览任务⏭️[$title] 第${round}轮第${pageNo}页 xlightPlugin 无响应")
                return OrchardBrowseRoundResult(
                    finishedCount,
                    buildOrchardBrowseFailureResult(
                        item = item,
                        message = "xlightPlugin无响应",
                        rpc = "XLightRpcCall.xlightPlugin",
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    ),
                )
            }

            val responseJson = JSONObject(response)
            val xlightRestriction = buildOrchardXLightRestriction(item, responseJson)
            if (xlightRestriction != null) {
                Log.orchard(
                    "农场浏览任务⏭️[$title] 第${round}轮第${pageNo}页广告业务受限: " +
                        xlightRestriction.message.ifBlank { xlightRestriction.code },
                )
                return OrchardBrowseRoundResult(finishedCount, xlightRestriction)
            }
            val playingResult =
                responseJson.optJSONObject("resData")?.optJSONObject("playingResult")
                    ?: responseJson.optJSONObject("playingResult")
            if (playingResult == null) {
                Log.orchard("农场浏览任务⏭️[$title] 第${round}轮第${pageNo}页未返回 playingResult")
                return OrchardBrowseRoundResult(
                    finishedCount,
                    buildOrchardTaskFailureResult(
                        response = responseJson,
                        taskId = item.id,
                        title = item.title,
                        action = "xlightPlugin",
                        rpc = "XLightRpcCall.xlightPlugin",
                        item = item,
                    ),
                )
            }

            val playingBizId = playingResult.optString("playingBizId")
            val nextPlayingPageInfo = playingResult.optString("playingPageInfo").takeIf { it.isNotBlank() }
            val eventRewardInfoList =
                playingResult
                    .optJSONObject("eventRewardDetail")
                    ?.optJSONArray("eventRewardInfoList")
            if (playingBizId.isBlank() || eventRewardInfoList == null || eventRewardInfoList.length() == 0) {
                if (pageNo == 1 && finishedCount == 0) {
                    Log.orchard("农场浏览任务⏭️[$title] 第${round}轮未返回可完成事件")
                }
                if (nextPlayingPageInfo.isNullOrBlank()) {
                    return OrchardBrowseRoundResult(
                        finishedCount,
                        buildOrchardBrowseFailureResult(
                            item = item,
                            message = "未返回可完成浏览事件",
                            rpc = "XLightRpcCall.xlightPlugin",
                            raw = responseJson.toString(),
                        ),
                    )
                }
                playingPageInfo = nextPlayingPageInfo
                pageNo++
                continue
            }

            val browseEvents = mutableListOf<Pair<String, JSONObject>>()
            val queuedEventKeys = mutableSetOf<String>()
            for (i in 0 until eventRewardInfoList.length()) {
                val playEventInfo = eventRewardInfoList.optJSONObject(i) ?: continue
                if (playEventInfo.optString("playingEventType") != "BROWSE") {
                    continue
                }
                val eventKey = buildBrowseEventKey(playingBizId, playEventInfo)
                if (!processedEventKeys.contains(eventKey) && queuedEventKeys.add(eventKey)) {
                    browseEvents.add(eventKey to playEventInfo)
                }
            }
            if (browseEvents.isEmpty()) {
                if (nextPlayingPageInfo.isNullOrBlank()) {
                    return OrchardBrowseRoundResult(
                        finishedCount,
                        buildOrchardBrowseFailureResult(
                            item = item,
                            message = "未返回BROWSE事件",
                            rpc = "XLightRpcCall.xlightPlugin",
                            raw = responseJson.toString(),
                        ),
                    )
                }
                playingPageInfo = nextPlayingPageInfo
                pageNo++
                continue
            }

            browseEvents.sortBy { it.second.optInt("order", Int.MAX_VALUE) }
            var advancedToNextPage = false
            for ((eventKey, browseEvent) in browseEvents) {
                val eventStep = browseEvent.optInt("eventStep", 15).coerceAtLeast(1)
                Log.orchard(
                    "农场浏览任务▶[$title] 第$round/${config.rounds}轮 order=${browseEvent.optInt("order", 0)} 直接提交完成RPC(eventStep=${eventStep}s)",
                )

                val finishResponse =
                    XLightRpcCall.finishTask(
                        playBizId = playingBizId,
                        playEventInfo = browseEvent,
                        iepTaskSceneCode = config.iepTaskSceneCode,
                        iepTaskType = config.iepTaskType,
                    )
                if (finishResponse.isBlank()) {
                    Log.orchard("农场浏览任务❌[$title] 第$round/${config.rounds}轮完成失败: finishTask 无响应")
                    return OrchardBrowseRoundResult(
                        finishedCount,
                        buildOrchardBrowseFailureResult(
                            item = item,
                            message = "finishTask无响应",
                            rpc = "XLightRpcCall.finishTask",
                            failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        ),
                    )
                }
                val finishResult = JSONObject(finishResponse)
                if (!ResChecker.checkRes(TAG, finishResult)) {
                    val errMsg =
                        finishResult
                            .optString("desc")
                            .ifBlank { finishResult.optString("errMsg") }
                            .ifBlank { finishResult.optString("resultDesc") }
                    Log.orchard("农场浏览任务❌[$title] 第$round/${config.rounds}轮完成失败: $errMsg")
                    return OrchardBrowseRoundResult(
                        finishedCount,
                        buildOrchardTaskFailureResult(
                            response = finishResult,
                            taskId = item.id,
                            title = item.title,
                            action = "xlightFinishTask",
                            rpc = "XLightRpcCall.finishTask",
                            item = item,
                        ),
                    )
                }
                processedEventKeys.add(eventKey)
                finishedCount++
                CoroutineUtils.sleepCompat(executeIntervalInt.toLong())
                if (config.stopAfterFirstRewardInRound) {
                    return OrchardBrowseRoundResult(finishedCount)
                }
                if (!nextPlayingPageInfo.isNullOrBlank()) {
                    // 抓包显示分页浏览链路是一页一奖，完成当前奖励后需继续翻页刷新状态。
                    playingPageInfo = nextPlayingPageInfo
                    pageNo++
                    advancedToNextPage = true
                    break
                }
            }

            if (advancedToNextPage) {
                continue
            }
            if (nextPlayingPageInfo.isNullOrBlank()) {
                break
            }
            playingPageInfo = nextPlayingPageInfo
            pageNo++
        }

        return OrchardBrowseRoundResult(finishedCount)
    }

    private fun buildOrchardBrowseTaskConfig(
        task: JSONObject,
        title: String,
    ): OrchardBrowseTaskConfig? {
        val taskDisplayConfig = task.optJSONObject("taskDisplayConfig") ?: return null
        val targetUrl = taskDisplayConfig.optString("targetUrl")
        if (targetUrl.isEmpty()) {
            Log.orchard("农场浏览任务⏭️[$title] 缺少 targetUrl")
            return null
        }

        val pageUrl =
            UrlUtil.getFullNestedUrl(targetUrl, "url")
                ?: UrlUtil.getParamValue(targetUrl, "url")
                ?: targetUrl.takeIf { it.startsWith("http") }
        if (pageUrl.isNullOrEmpty()) {
            Log.orchard("农场浏览任务⏭️[$title] 无法解析 pageUrl")
            return null
        }

        val spaceCode =
            UrlUtil.extractParamFromUrl(pageUrl, "spaceCodeFeeds")
                ?: UrlUtil.getParamValue(targetUrl, "spaceCodeFeeds")
        if (spaceCode.isNullOrEmpty()) {
            Log.orchard("农场浏览任务⏭️[$title] 无法解析 spaceCodeFeeds")
            return null
        }

        val referToken =
            UrlUtil.extractParamFromUrl(pageUrl, "tokenFeeds")
                ?: UrlUtil.getParamValue(targetUrl, "tokenFeeds")
        val actionType = task.optString("actionType")
        val iepTaskSceneCode =
            UrlUtil.getParamValue(targetUrl, "iepTaskSceneCode")
                ?: task.optString("sceneCode").takeIf { actionType == "VISIT" && it.isNotBlank() }
        val iepTaskType =
            UrlUtil.getParamValue(targetUrl, "iepTaskType")
                ?: task.optString("taskId").takeIf { actionType == "VISIT" && it.isNotBlank() }
        val rightsTimes = task.optInt("rightsTimes", 0)
        val canDoTaskTimesLimit =
            UrlUtil.getParamValue(targetUrl, "canDoTaskTimesLimit")?.toIntOrNull()
                ?: task.optInt("rightsTimesLimit", 0).takeIf { it > 0 && actionType == "VISIT" }
                ?: task.optInt("rightsTimesLimit", 0).takeIf { it > 0 }
        val rounds = canDoTaskTimesLimit?.let { (it - rightsTimes).coerceAtLeast(0) } ?: 1
        if (canDoTaskTimesLimit != null && rounds <= 0) {
            Log.orchard("农场浏览任务⏭️[$title] 剩余次数不足 rightsTimes=$rightsTimes rightsTimesLimit=$canDoTaskTimesLimit")
            return null
        }
        val positionExtMap = JSONObject()
        if (canDoTaskTimesLimit != null && referToken.isNullOrBlank()) {
            positionExtMap.put("canDoTaskTimesLimit", canDoTaskTimesLimit.toString())
        }

        return OrchardBrowseTaskConfig(
            pageUrl = pageUrl,
            spaceCode = spaceCode,
            referToken = referToken,
            iepTaskSceneCode = iepTaskSceneCode,
            iepTaskType = iepTaskType,
            rounds = rounds,
            positionExtMap = positionExtMap,
            usePagedSearchInfo =
                referToken.isNullOrBlank() &&
                    pageUrl.contains("multi-stage-task.html") &&
                    iepTaskSceneCode != null &&
                    iepTaskType != null,
            stopAfterFirstRewardInRound =
                rounds == 1 &&
                    referToken.isNullOrBlank() &&
                    pageUrl.contains("multi-stage-task.html"),
        )
    }

    private fun buildBrowseEventKey(
        playBizId: String,
        playEventInfo: JSONObject,
    ): String =
        "$playBizId#${playEventInfo.optInt("order", -1)}#${playEventInfo.optInt("rewardId", -1)}#${playEventInfo.optInt("eventStep", 0)}"

    private fun buildOrchardBrowseSearchInfo(pageNo: Int): JSONObject? {
        if (pageNo <= 1) {
            return null
        }
        return JSONObject().apply {
            put("rangeFilter", "goodsPrice:-")
            put("tabKey", "all")
        }
    }

    private fun isXLightTask(task: JSONObject): Boolean {
        val taskDisplayConfig = task.optJSONObject("taskDisplayConfig") ?: return false
        val targetUrl = taskDisplayConfig.optString("targetUrl")
        if (targetUrl.isBlank()) {
            return false
        }
        val pageUrl =
            UrlUtil.getFullNestedUrl(targetUrl, "url")
                ?: UrlUtil.getParamValue(targetUrl, "url")
                ?: targetUrl
        val hasSpaceCode = pageUrl.contains("spaceCodeFeeds=") || targetUrl.contains("spaceCodeFeeds=")
        if (!hasSpaceCode) {
            return false
        }
        if (pageUrl.contains("tokenFeeds=") || targetUrl.contains("tokenFeeds=")) {
            return true
        }
        if (!pageUrl.contains("multi-stage-task.html")) {
            return false
        }
        val actionType = task.optString("actionType")
        val hasSceneCode =
            !UrlUtil.getParamValue(targetUrl, "iepTaskSceneCode").isNullOrBlank() ||
                (actionType == "VISIT" && task.optString("sceneCode").isNotBlank())
        val hasTaskType =
            !UrlUtil.getParamValue(targetUrl, "iepTaskType").isNullOrBlank() ||
                (actionType == "VISIT" && task.optString("taskId").isNotBlank())
        val hasTaskLimit =
            !UrlUtil.getParamValue(targetUrl, "canDoTaskTimesLimit").isNullOrBlank() ||
                task.optInt("rightsTimesLimit", 0) > 0
        return hasSceneCode && hasTaskType && hasTaskLimit
    }

    private fun logUnsupportedVisitTask(
        task: JSONObject,
        title: String,
    ) {
        val taskDisplayConfig = task.optJSONObject("taskDisplayConfig")
        val targetUrl = taskDisplayConfig?.optString("targetUrl").orEmpty()
        val pageUrl =
            UrlUtil.getFullNestedUrl(targetUrl, "url")
                ?: UrlUtil.getParamValue(targetUrl, "url")
                ?: targetUrl.takeIf { it.startsWith("http") }
        val appId = UrlUtil.getParamValue(targetUrl, "appId")
        val route =
            when {
                pageUrl?.contains("multi-stage-task.html") == true -> "multi-stage-task"

                targetUrl.startsWith(
                    "alipays://platformapi/startapp",
                ) && targetUrl.contains("jumpAction=userGrowth") -> "startapp-userGrowth"

                targetUrl.startsWith("alipays://platformapi/startapp") -> "startapp"

                targetUrl.startsWith("http") -> "direct-h5"

                targetUrl.isBlank() -> "missing-targetUrl"

                else -> "unknown"
            }
        val detailParts =
            mutableListOf(
                "taskId=${task.optString("taskId")}",
                "groupId=${task.optString("groupId")}",
                "sceneCode=${task.optString("sceneCode")}",
                "taskStatus=${task.optString("taskStatus")}",
                "taskPlantType=${task.optString("taskPlantType")}",
                "rightsTimes=${task.optInt("rightsTimes", 0)}",
                "rightsTimesLimit=${task.optInt("rightsTimesLimit", 0)}",
                "route=$route",
            )
        taskDisplayConfig
            ?.optString("type")
            ?.takeIf { it.isNotBlank() }
            ?.let { detailParts.add("type=$it") }
        appId?.takeIf { it.isNotBlank() }?.let { detailParts.add("appId=$it") }
        UrlUtil
            .extractParamFromUrl(pageUrl.orEmpty(), "spaceCodeFeeds")
            ?.takeIf { it.isNotBlank() }
            ?.let { detailParts.add("spaceCodeFeeds=$it") }
        UrlUtil
            .extractParamFromUrl(pageUrl.orEmpty(), "tokenFeeds")
            ?.takeIf { it.isNotBlank() }
            ?.let { detailParts.add("tokenFeeds=$it") }
        UrlUtil
            .getParamValue(targetUrl, "iepTaskSceneCode")
            ?.takeIf { it.isNotBlank() }
            ?.let { detailParts.add("iepTaskSceneCode=$it") }
        UrlUtil
            .getParamValue(targetUrl, "iepTaskType")
            ?.takeIf { it.isNotBlank() }
            ?.let { detailParts.add("iepTaskType=$it") }
        UrlUtil
            .getParamValue(targetUrl, "canDoTaskTimesLimit")
            ?.takeIf { it.isNotBlank() }
            ?.let { detailParts.add("canDoTaskTimesLimit=$it") }
        UrlUtil
            .getParamValue(targetUrl, "sceneCode")
            ?.takeIf { it.isNotBlank() }
            ?.let { detailParts.add("targetSceneCode=$it") }
        pageUrl?.takeIf { it.isNotBlank() }?.let { detailParts.add("pageUrl=$it") }
        if (targetUrl.isNotBlank()) {
            detailParts.add("targetUrl=$targetUrl")
        }
        Log.orchard("农场任务⏭️[$title] action=VISIT 未发现已验证完成RPC，未自动处理 | ${detailParts.joinToString(" | ")}")
    }

    private data class OrchardBrowseTaskConfig(
        val pageUrl: String,
        val spaceCode: String,
        val referToken: String?,
        val iepTaskSceneCode: String?,
        val iepTaskType: String?,
        val rounds: Int,
        val positionExtMap: JSONObject,
        val usePagedSearchInfo: Boolean,
        val stopAfterFirstRewardInRound: Boolean,
    )

    private data class OrchardBrowseRoundResult(
        val finishedCount: Int,
        val failure: TaskFlowActionResult? = null,
    )

    private fun logLinkedTaskHints(responseJson: JSONObject) {
        val convertToManureTask = responseJson.optJSONObject("convertToManureTask")
        if (convertToManureTask != null && convertToManureTask.optBoolean("showTask", false)) {
            val taskStatus = convertToManureTask.optString("taskStatus")
            if (taskStatus == "TODO") {
                Log.orchard("农场联动任务⏭️[${convertToManureTask.optString("title")}] 需由森林模块完成")
            }
        }
    }

    private fun getSceneSource(scene: String = currentPlantScene): String = if (scene == "yeb") YEB_SOURCE else ENTRY_SOURCE

    private fun buildIndexWua(scene: String = currentPlantScene): String =
        if (scene == "yeb") SecurityBodyHelper.getSecurityBodyData(4).toString() else ""

    internal fun tryReceiveSpreadManureActivityAward(indexJson: JSONObject) {
        try {
            // 领奖状态由阶段终态决定；缺失 manureTaskAwardReceive 时不能推定已经领取。
            val alreadyReceived =
                indexJson.has("manureTaskAwardReceive") && indexJson.optBoolean("manureTaskAwardReceive", false)
            val stage =
                indexJson
                    .optJSONObject("spreadManureActivity")
                    ?.optJSONObject("spreadManureStage")
                    ?: return
            val status = stage.optString("status")
            if (alreadyReceived || status != "FINISHED") {
                return
            }
            val sceneCode = stage.optString("sceneCode")
            val taskType = stage.optString("taskType")
            if (sceneCode.isBlank() || taskType.isBlank()) {
                Log.orchard("丰收奖励🎁字段缺失: sceneCode=$sceneCode taskType=$taskType")
                return
            }
            val awardCount = stage.optInt("awardCount", 0)
            val awardResp =
                JSONObject(
                    AntOrchardRpcCall.receiveTaskAward(sceneCode, taskType, ACTION_SOURCE),
                )
            if (isOrchardRpcSuccessResponse(awardResp)) {
                Log.orchard("丰收奖励🎁[领取成功]#${awardCount}g肥料")
                completeSpreadManureActivityTaskIfPresent()
            } else {
                Log.orchard("丰收奖励🎁领取失败[classification=${classifyOrchardTaskFailure(awardResp)}] raw=$awardResp")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "tryReceiveSpreadManureActivityAward err:", t)
        }
    }

    private fun completeSpreadManureActivityTaskIfPresent() {
        val response = JSONObject(AntOrchardRpcCall.refinedOperation(RECEIVE_SPREAD_MANURE_ACTIVITY_AWARD_ACTION))
        if (!ResChecker.checkRes(TAG, response)) {
            Log.orchard("丰收奖励任务刷新失败: $response")
            return
        }

        val taskInfo =
            response.optJSONObject("taskInfo")
                ?: response.optJSONObject("spreadManureActivity")?.optJSONObject("taskInfo")
                ?: return
        val task = taskInfo.optJSONObject("taskBaseInfo") ?: taskInfo
        if (task.optString("taskStatus") !in setOf("TODO", "WAIT_COMPLETE")) {
            return
        }

        val sceneCode = task.optString("sceneCode")
        val taskType = task.optString("taskType")
        if (sceneCode.isBlank() || taskType.isBlank() || userId.isNullOrBlank()) {
            Log.orchard("丰收奖励任务待复核: 缺少 finishTask 契约 raw=$taskInfo")
            return
        }

        val title = resolveSpreadManureActivityTaskTitle(task)
        val action = task.optString("finishTaskActionType").ifBlank { task.optString("actionType") }
        val result =
            executeOrchardFinishTask(
                action = action,
                sceneCode = sceneCode,
                taskId = taskType,
                groupId = task.optString("groupId"),
                title = title,
                task = task,
            )
        when {
            result.success -> Log.orchard("丰收奖励任务[$title] 已推进")
            result.deferredReason != null -> Log.orchard("丰收奖励任务[$title] 等待状态确认")
            else -> Log.orchard("丰收奖励任务待复核[$title] ${result.message} raw=${result.raw}")
        }
    }

    private fun resolveSpreadManureActivityTaskTitle(task: JSONObject): String {
        val bizInfo = task.opt("bizInfo")
        val biz =
            when (bizInfo) {
                is JSONObject -> bizInfo
                is String -> runCatching { JSONObject(bizInfo) }.getOrNull()
                else -> null
            }
        return sequenceOf(
            biz?.optString("taskTitle"),
            biz?.optString("title"),
            task.optString("taskType"),
        ).firstOrNull { !it.isNullOrBlank() } ?: "UNKNOWN"
    }

    internal fun tryReceiveSpreadManureActivityAwardByQueryIndex() {
        try {
            val refreshed =
                JSONObject(
                    AntOrchardRpcCall.orchardSyncIndex(
                        wua = SecurityBodyHelper.getSecurityBodyData(4).toString(),
                        syncIndexTypes = "QUERY_SPREAD_MANURE_ACTIVITY",
                        source = ACTION_SOURCE,
                    ),
                )
            if (!ResChecker.checkRes(TAG, refreshed)) {
                return
            }
            tryReceiveSpreadManureActivityAward(refreshed)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "tryReceiveSpreadManureActivityAwardByQueryIndex err:", t)
        }
    }

    private fun buildXLightSession(): String = "u_${RandomUtil.getRandomString(5)}_${RandomUtil.getRandomString(5)}"

    private fun orchardSign(signTaskInfo: JSONObject) {
        try {
            val currentSignItem = signTaskInfo.getJSONObject("currentSignItem")
            if (!currentSignItem.getBoolean("signed")) {
                val joSign = JSONObject(AntOrchardRpcCall.orchardSign())
                if (joSign.getString("resultCode") == "100") {
                    val awardCount =
                        joSign
                            .getJSONObject("signTaskInfo")
                            .getJSONObject("currentSignItem")
                            .getInt("awardCount")
                    Log.orchard("农场签到📅[获得肥料]#${awardCount}g")
                } else {
                    Log.orchard(joSign.toString())
                }
            } else {
                Log.orchard("农场今日已签到")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "orchardSign err:", t)
        }
    }

    internal fun smashedGoldenEgg(count: Int) {
        try {
            var remaining = count.coerceAtLeast(0)
            while (remaining > 0) {
                val batchCount = remaining.coerceAtMost(10)
                val response = AntOrchardRpcCall.smashedGoldenEgg(batchCount)
                val jo = JSONObject(response)

                if (ResChecker.checkRes(TAG, jo)) {
                    val batchSmashedList = jo.optJSONArray("batchSmashedList") ?: JSONArray()
                    for (i in 0 until batchSmashedList.length()) {
                        val smashedItem = batchSmashedList.getJSONObject(i)
                        val manureCount = smashedItem.optInt("manureCount", 0)
                        val jackpot = smashedItem.optBoolean("jackpot", false)
                        Log.orchard("砸出肥料 🎖️: $manureCount g" + if (jackpot) "（触发大奖）" else "")
                    }
                } else {
                    Log.orchard(jo.optString("resultDesc", "未知错误"))
                    return
                }

                val confirmedRemaining = queryUnsmashedGoldenEggs() ?: return
                if (confirmedRemaining >= remaining) {
                    Log.orchard("金蛋砸取后首页状态未推进，停止当前链路")
                    return
                }
                remaining = confirmedRemaining
                if (remaining > 0) {
                    CoroutineUtils.sleepCompat(executeIntervalInt.toLong())
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "smashedGoldenEgg err:", t)
        }
    }

    private fun queryUnsmashedGoldenEggs(): Int? {
        val index = JSONObject(AntOrchardRpcCall.orchardIndex())
        if (index.optString("resultCode") != "100") {
            Log.orchard("金蛋状态回查失败: ${index.optString("resultDesc", index.toString())}")
            return null
        }
        val goldenEggInfo = index.optJSONObject("goldenEggInfo")
        if (goldenEggInfo == null || !goldenEggInfo.has("unsmashedGoldenEggs")) {
            Log.orchard("金蛋状态回查缺少goldenEggInfo.unsmashedGoldenEggs")
            return null
        }
        return goldenEggInfo.optInt("unsmashedGoldenEggs").coerceAtLeast(0)
    }

    private fun claimTaskReward(
        taskId: String,
        taskPlantType: String,
        task: JSONObject? = null,
    ): Pair<JSONObject, String> {
        val source =
            task?.let(::resolveTaskActionSource)
                ?: if (taskId in setOf(TASK_JINDOU_TREASURE, TASK_FORTUNE_TREE)) ENTRY_SOURCE else ACTION_SOURCE
        val response = JSONObject(AntOrchardRpcCall.triggerTbTask(taskId, taskPlantType, source))
        return response to source
    }

    internal fun syncTaobaoLimitBalloon() {
        try {
            val lazyIndexResp =
                JSONObject(
                    AntOrchardRpcCall.orchardLazyIndex(currentPlantScene.ifBlank { "main" }, ENTRY_SOURCE),
                )
            if (!ResChecker.checkRes(TAG, lazyIndexResp)) {
                Log.orchard("农场限时福利🎈查询失败: $lazyIndexResp")
                return
            }

            val balloonCooper = lazyIndexResp.optJSONObject("balloonCooper") ?: return
            val activityId = balloonCooper.optString("activityId")
            val activityType = balloonCooper.optString("activityType")
            val status = balloonCooper.optString("status")
            val extendJson =
                balloonCooper
                    .optString("extend")
                    .takeIf { it.isNotBlank() }
                    ?.let { JSONObject(it) }
            val balloonScene = extendJson?.optString("balloonScene").orEmpty()
            if (activityType != "BALLOON") return
            if (status != "INIT" && status != "TODO") return

            val startResp =
                JSONObject(
                    AntOrchardRpcCall.triggerSubplotsActivity(activityId, activityType, "START"),
                )
            if (!ResChecker.checkRes(TAG, startResp)) {
                Log.orchard("农场限时福利🎈触发失败: $startResp")
                return
            }

            val wua = SecurityBodyHelper.getSecurityBodyData(4).toString()
            val syncBalloonScene = balloonScene.ifBlank { activityId }
            val syncResp =
                JSONObject(
                    AntOrchardRpcCall.orchardSyncIndex(
                        wua = wua,
                        syncIndexTypes = "QUERY_BALLOON_COOPER",
                        balloonScene = syncBalloonScene,
                        source = ACTION_SOURCE,
                    ),
                )
            if (!ResChecker.checkRes(TAG, syncResp)) {
                Log.orchard("农场限时福利🎈状态同步失败: $syncResp")
                return
            }

            val syncedStatus = syncResp.optJSONObject("balloonCooper")?.optString("status")
            if (syncedStatus.isNullOrBlank()) {
                Log.orchard("农场限时福利🎈已触发并同步 QUERY_BALLOON_COOPER")
            } else {
                Log.orchard("农场限时福利🎈状态同步: $status -> $syncedStatus")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "syncTaobaoLimitBalloon err:", t)
        }
    }

    internal fun receiveOrchardVisitAward() {
        try {
            val initialResponse =
                JSONObject(
                    AntOrchardRpcCall.receiveOrchardVisitAward(
                        diversionSource = "patch9Visit",
                        source = ENTRY_SOURCE,
                    ),
                )
            if (!isOrchardRpcSuccessResponse(initialResponse)) {
                Log.orchard("回访奖励查询失败 raw=$initialResponse")
                return
            }

            val awardResponse =
                if (initialResponse.optBoolean("needManualReceive", false)) {
                    JSONObject(
                        AntOrchardRpcCall.receiveOrchardVisitAward(
                            diversionSource = "patch9Visit",
                            source = ENTRY_SOURCE,
                            manualReceive = true,
                        ),
                    )
                } else {
                    initialResponse
                }
            if (!isOrchardRpcSuccessResponse(awardResponse)) {
                Log.orchard("回访奖励领取失败 raw=$awardResponse")
                return
            }

            val awardList = awardResponse.optJSONArray("orchardVisitAwardList") ?: JSONArray()
            var hasAwardReceived = false
            for (i in 0 until awardList.length()) {
                val award = awardList.optJSONObject(i) ?: continue
                val awardCount = award.optInt("awardCount", 0)
                val awardDesc = award.optString("awardDesc")
                Log.orchard("回访奖励[$awardDesc] $awardCount g肥料")
                hasAwardReceived = true
            }
            if (hasAwardReceived) {
                Status.setFlagToday(StatusFlags.FLAG_ANTORCHARD_WIDGET_DAILY_AWARD)
                Log.orchard("回访奖励领取完成")
            } else {
                Log.orchard("回访奖励已全部领取或无可领取奖励")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveOrchardVisitAward err:", t)
        }
    }

    private fun resolveLimitedChallengeGameAppId(child: JSONObject): String {
        val taskDisplayConfig = child.optJSONObject("taskDisplayConfig")
        val targetUrl = taskDisplayConfig?.optString("targetUrl").orEmpty()
        if (targetUrl.isNotBlank()) {
            UrlUtil
                .getParamValue(targetUrl, "appId")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }

            val nestedUrl =
                UrlUtil.getFullNestedUrl(targetUrl, "url")
                    ?: UrlUtil.getParamValue(targetUrl, "url")
            if (!nestedUrl.isNullOrBlank()) {
                UrlUtil
                    .extractParamFromUrl(nestedUrl, "appId")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                UrlUtil
                    .getParamValue(nestedUrl, "appId")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
        return listOf(
            child.optString("appId"),
            child.optString("contentId"),
            taskDisplayConfig?.optString("appId").orEmpty(),
            taskDisplayConfig?.optString("contentId").orEmpty(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    internal fun limitedTimeChallenge() {
        try {
            val wua = SecurityBodyHelper.getSecurityBodyData(4).toString()
            val response =
                AntOrchardRpcCall.orchardSyncIndex(
                    wua = wua,
                    source = ACTION_SOURCE,
                )
            val root = JSONObject(response)
            if (!ResChecker.checkRes(TAG, root)) return

            val challenge = root.optJSONObject("limitedTimeChallenge") ?: return
            val currentRound = challenge.optInt("currentRound", 0)
            if (currentRound <= 0) return

            val taskArray = challenge.optJSONArray("limitedTimeChallengeTasks") ?: return
            val targetIdx = currentRound - 1
            if (targetIdx !in 0 until taskArray.length()) return

            val roundTask = taskArray.optJSONObject(targetIdx) ?: return
            val ongoing = roundTask.optBoolean("ongoing", false)
            val MtaskStatus = roundTask.optString("taskStatus")
            val MtaskId = roundTask.optString("taskId")
            val MawardCount = roundTask.optInt("awardCount", 0)

            if (MtaskStatus == "FINISHED" && ongoing) {
                val awardResp = AntOrchardRpcCall.receiveTaskAward("ORCHARD_LIMITED_TIME_CHALLENGE", MtaskId)
                val joo = JSONObject(awardResp)
                if (ResChecker.checkRes(TAG, joo)) {
                    Log.orchard("第 $currentRound 轮 限时任务🎁[肥料 * $MawardCount]")
                }
                return
            }

            if (roundTask.optString("taskStatus") != "TODO") return
            val childTasks = roundTask.optJSONArray("childTaskList") ?: return

            for (i in 0 until childTasks.length()) {
                val child = childTasks.optJSONObject(i) ?: continue
                val childTaskId = child.optString("taskId", "未知ID")
                val actionType = child.optString("actionType")
                val groupId = child.optString("groupId")
                val taskStatus = child.optString("taskStatus")
                val sceneCode = child.optString("sceneCode")
                val taskRequire = child.optInt("taskRequire", 0)
                val taskProgress = child.optInt("taskProgress", 0)

                if (taskStatus != "TODO") continue
                if (groupId == "GROUP_1_STEP_3_GAME_WZZT_30s") continue
                if (groupId == "GROUP_1_STEP_2_GAME_WZZT_30s") continue

                when (actionType) {
                    "SPREAD_MANURE" -> {
                        val need = taskRequire - taskProgress
                        if (need > 0) {
                            repeat(need) {
                                val w = SecurityBodyHelper.getSecurityBodyData(4).toString()
                                val r = AntOrchardRpcCall.orchardSpreadManure(w, ACTION_SOURCE)
                                if (JSONObject(r).optString("resultCode") != "100") return
                            }
                        }
                    }

                    "GAME_CENTER" -> {
                        val appId = resolveLimitedChallengeGameAppId(child)
                        if (appId.isBlank()) {
                            Log.error(
                                TAG,
                                "农场限时福利[$childTaskId] classification=UNSUPPORTED_NO_CLOSURE " +
                                    "decision=SKIP_CURRENT_TASK action=noticeGame " +
                                    "rpc=AntOrchardRpcCall.noticeGame msg=child payload 缺少稳定 appId，禁止使用历史硬编码兜底 " +
                                    "sceneCode=$sceneCode groupId=$groupId raw=$child",
                            )
                            continue
                        }
                        val r = AntOrchardRpcCall.noticeGame(appId)
                        if (ResChecker.checkRes(TAG, JSONObject(r))) {
                            Log.orchard("游戏任务触发成功")
                        }
                    }

                    "VISIT" -> {
                        val displayCfg = child.optJSONObject("taskDisplayConfig") ?: continue
                        val childTitle =
                            displayCfg
                                .optString("title")
                                .ifBlank { child.optString("taskName") }
                                .ifBlank { childTaskId }
                        val targetUrl = displayCfg.optString("targetUrl", "")
                        if (targetUrl.isEmpty()) continue

                        val finalUrl = UrlUtil.getFullNestedUrl(targetUrl, "url") ?: ""
                        val spaceCodeFeeds = if (finalUrl.isNotEmpty()) UrlUtil.extractParamFromUrl(finalUrl, "spaceCodeFeeds") else null
                        val finalSpaceCode = spaceCodeFeeds ?: UrlUtil.getParamValue(targetUrl, "spaceCodeFeeds") ?: ""
                        if (finalSpaceCode.isEmpty()) continue

                        if (isOrchardTaskBlacklisted(childTaskId, childTitle) ||
                            isOrchardTaskBlacklisted(groupId, childTitle)
                        ) {
                            Log.orchard("农场限时福利⏭️[$childTitle]#任务在自动跳过列表(黑名单)中，跳过")
                            continue
                        }

                        Log.error(
                            TAG,
                            "农场限时福利[$childTitle] classification=UNKNOWN_NEEDS_REVIEW " +
                                "decision=SKIP_CURRENT_TASK taskId=$childTaskId action=xlightPlugin " +
                                "rpc=<none> code=UNVERIFIED_XLIGHT_LIMITED_CHALLENGE " +
                                "msg=任务载荷包含浏览参数，但当前没有抓包确认的完成 RPC，保留后续回查 " +
                                "sceneCode=$sceneCode groupId=$groupId spaceCode=$finalSpaceCode",
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "limitedTimeChallenge err:", t)
        }
    }

    internal fun querySubplotsActivity(taskRequire: Int) {
        try {
            val level = treeLevel
            if (level.isNullOrEmpty() || level == "0") return

            val response = AntOrchardRpcCall.querySubplotsActivity(level)
            val jo = JSONObject(response)

            if (jo.getString("resultCode") == "100") {
                val subplotsActivityList = jo.getJSONArray("subplotsActivityList")
                for (i in 0 until subplotsActivityList.length()) {
                    val jo2 = subplotsActivityList.getJSONObject(i)
                    if (jo2.getString("activityType") != "WISH") continue

                    val activityId = jo2.getString("activityId")
                    when (jo2.getString("status")) {
                        "NOT_STARTED" -> {
                            val extend = jo2.getString("extend")
                            val jo3 = JSONObject(extend)
                            val wishActivityOptionList = jo3.getJSONArray("wishActivityOptionList")
                            var optionKey: String? = null

                            for (j in 0 until wishActivityOptionList.length()) {
                                val jo4 = wishActivityOptionList.getJSONObject(j)
                                if (taskRequire == jo4.getInt("taskRequire")) {
                                    optionKey = jo4.getString("optionKey")
                                    break
                                }
                            }

                            if (optionKey != null) {
                                val jo5 = JSONObject(AntOrchardRpcCall.triggerSubplotsActivity(activityId, "WISH", optionKey))
                                if (jo5.getString("resultCode") == "100") {
                                    Log.orchard("农场许愿✨[每日施肥$taskRequire 次]")
                                } else {
                                    Log.orchard(jo5.getString("resultDesc"))
                                }
                            }
                        }

                        "FINISHED" -> {
                            val jo3 = JSONObject(AntOrchardRpcCall.receiveOrchardRights(activityId, "WISH"))
                            if (jo3.getString("resultCode") == "100") {
                                Log.orchard("许愿奖励✨[肥料${jo3.getInt("amount")}g]")
                                querySubplotsActivity(taskRequire)
                                return
                            } else {
                                Log.orchard(jo3.getString("resultDesc"))
                            }
                        }
                    }
                }
            } else {
                Log.orchard(jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "querySubplotsActivity err:", t)
        }
    }

    internal fun orchardAssistFriend() {
        try {
            if (!Status.canAntOrchardAssistFriendToday()) {
                Log.orchard("今日已助力，跳过农场助力")
                return
            }

            val friendSet = assistFriendList.resolvedIds()
            if (friendSet.isEmpty()) {
                Log.orchard("未设置农场助力好友列表，跳过农场助力")
                return
            }
            for (uid in friendSet) {
                if (FriendGuard.shouldSkipFriend(uid, TAG, "农场助力")) {
                    continue
                }
                if (Status.hasFlagToday(StatusFlags.FLAG_ANTORCHARD_ASSIST_RELATION_INVALID_PREFIX + uid)) {
                    Log.orchard("农场助力⏭️[${UserMap.getMaskName(uid)}]今日关系已判定无效，跳过")
                    continue
                }
                val shareId =
                    Base64.encodeToString(
                        ("$uid-${RandomUtil.getRandomInt(5)}ANTFARM_ORCHARD_SHARE_P2P").toByteArray(),
                        Base64.NO_WRAP,
                    )
                val str = AntOrchardRpcCall.achieveBeShareP2P(shareId)
                val jsonObject = JSONObject(str)
                CoroutineUtils.sleepCompat(800)
                val name = UserMap.getMaskName(uid)

                if (!ResChecker.checkRes(TAG, str)) {
                    val code = jsonObject.optString("code")
                    if (code == "600000027") {
                        Log.orchard("农场助力💪今日助力他人次数上限")
                        Status.antOrchardAssistFriendToday()
                        return
                    }
                    if (code == "600000031") {
                        Log.orchard("农场助力💪邀请过于频繁，停止今日助力以避免风控")
                        Status.antOrchardAssistFriendToday()
                        return
                    }
                    if (code == "600000010") {
                        Status.setFlagToday(StatusFlags.FLAG_ANTORCHARD_ASSIST_RELATION_INVALID_PREFIX + uid)
                        Log.orchard("农场助力⏭️[$name]人传人邀请关系不存在，已记录为今日跳过")
                        continue
                    }
                    Log.error(TAG, "农场助力😔失败[$name]${jsonObject.optString("desc")}")
                    continue
                }
                Log.orchard("农场助力💪[助力:$name]")
            }
            Status.antOrchardAssistFriendToday()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "orchardAssistFriend err:", t)
        }
    }

    object PlantModeType {
        const val MAIN = 0
        const val YEB = 1
        const val HYBRID = 2

        @JvmField
        val nickNames =
            arrayOf(
                "种果树(Main)",
                "种摇钱树(Yeb)",
                "混合模式(先摇钱树后果树)",
            )
    }
}

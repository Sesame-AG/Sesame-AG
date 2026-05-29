@file:Suppress("ClassName")

package io.github.aoguai.sesameag.task.antFarm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.aoguai.sesameag.entity.AntFarmIPChouChouLeBenefit
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.entity.OtherEntityProvider.farmFamilyOption
import io.github.aoguai.sesameag.entity.ParadiseCoinBenefit
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.hook.HookReadyChecker
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.hook.rpc.intervallimit.RpcIntervalLimit.addIntervalLimit
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionCountModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectAndCountModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.model.modelFieldExt.StringModelField
import io.github.aoguai.sesameag.model.modelFieldExt.TimePointModelField
import io.github.aoguai.sesameag.model.modelFieldExt.TimeTriggerModelField
import io.github.aoguai.sesameag.task.AnswerAI.AnswerAI
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.antFarm.AntFarmFamily.familyClaimRewardList
import io.github.aoguai.sesameag.task.antFarm.AntFarmFamily.familySign
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowDecision
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.task.exchange.ExchangeCost
import io.github.aoguai.sesameag.task.exchange.ExchangeItem
import io.github.aoguai.sesameag.task.exchange.ExchangeLimit
import io.github.aoguai.sesameag.task.exchange.ExchangeSafety
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.LogChannel
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.RandomUtil
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.TimeCounter
import io.github.aoguai.sesameag.util.TimeTriggerEvaluator
import io.github.aoguai.sesameag.util.TimeTriggerParseOptions
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.friend.FriendCapabilityRecorder
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.ParadiseCoinBenefitIdMap
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.util.maps.VipDataIdMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.Calendar
import java.util.Locale
import java.util.Objects
import java.util.Random
import kotlin.math.min

@Suppress("unused", "EnumEntryName", "EnumEntryName", "EnumEntryName", "EnumEntryName")
class AntFarm : ModelTask() {
    internal var ownerFarmId: String? = null
    private val farmTaskBlacklistModule = "蚂蚁庄园"

    private var animals: Array<Animal>? = null
    private var ownerAnimal = Animal()
    private var rewardProductNum: String? = null
    private var rewardList: Array<RewardFriend>? = null
    private var countdown: Long? = null
    /**
     * 慈善评分
     */
    internal var benevolenceScore = 0.0
    internal var harvestBenevolenceScore = 0.0

    /**
     * 未领取的饲料奖励
     */
    private var unreceiveTaskAward = 0

    /**
     * 小鸡心情值
     */
    private var finalScore = 0.0
    private var familyGroupId: String? = null
    private var farmTools: Array<FarmTool> = emptyArray()

    // 服务端返回的“是否已使用加饭卡”状态（从 subFarmVO.useBigEaterTool 解析）
    private var serverUseBigEaterTool: Boolean = false

    // 当前食槽上限（从 subFarmVO.foodInTroughLimit 解析，默认 180；使用加饭卡后为 360）
    private var foodInTroughLimitCurrent: Int = 180
    private val invalidToolTypesThisRound: MutableSet<ToolType> = linkedSetOf()
    private var manurePotCollectionBlockedThisRound: Boolean = false
    internal var lastDonationActivityIds: Set<String> = emptySet()
        private set
    internal var lastDonationNoMoreActivities: Boolean = false
        private set

    /**
     * 标记农场是否已满（用于雇佣小鸡逻辑）
     */
    private var isFarmFull: Boolean = false

    /**
     * 将服务端的饲喂状态代码转换为可读中文
     */
    private fun toFeedStatusName(status: String?): String {
        return when (status) {
            AnimalFeedStatus.HUNGRY.name -> "饥饿"
            AnimalFeedStatus.EATING.name -> "进食中"
            AnimalFeedStatus.SLEEPY.name -> "睡觉中"
            else -> status ?: "未知"
        }
    }

    override fun getName(): String {
        return "蚂蚁庄园"
    }

    override fun getGroup(): ModelGroup {
        return ModelGroup.FARM
    }

    override fun getIcon(): String {
        return "AntFarm.png"
    }

    /**
     * 小鸡睡觉时间
     */
    private var sleepTime: TimePointModelField? = null

    // 起床时间
    private var wakeUpTime: TimePointModelField? = null

    /**
     * 小鸡睡觉时长
     */
    private var sleepMinutes: IntegerModelField? = null

    /**
     * 自动喂鸡
     */
    private var feedAnimal: BooleanModelField? = null

    /**
     * 打赏好友
     */
    internal var rewardFriend: BooleanModelField? = null

    /**
     * 遣返小鸡
     */
    internal var sendBackAnimal: BooleanModelField? = null
    private var timeSendBack: IntegerModelField? = null

    /**
     * 遣返方式
     */
    private var sendBackAnimalWay: ChoiceModelField? = null

    /**
     * 遣返动作
     */
    private var sendBackAnimalType: ChoiceModelField? = null

    /**
     * 遣返好友列表
     */
    private var sendBackAnimalList: FriendSelectionModelField? = null

    /**
     * 召回小鸡
     */
    private var recallAnimalType: ChoiceModelField? = null

    /**
     * s收取道具奖励
     */
    internal var receiveFarmToolReward: BooleanModelField? = null

    /**
     * 游戏改分
     */
    internal var recordFarmGame: BooleanModelField? = null
    internal var gameRewardMax: IntegerModelField? = null

    /**
     * 小鸡游戏时间
     */
    internal var farmGameTrigger: TimeTriggerModelField? = null

    /**
     * 小鸡厨房
     */
    internal var kitchen: BooleanModelField? = null

    /**
     * 使用特殊食品
     */
    private var useSpecialFood: BooleanModelField? = null
    private var useSpecialFoodCount: IntegerModelField? = null
    internal var useNewEggCard: BooleanModelField? = null
    internal var harvestProduce: BooleanModelField? = null
    internal var donation: BooleanModelField? = null
    internal var donationMode: ChoiceModelField? = null
    internal var donationAmount: IntegerModelField? = null

    internal var donationCompetition: BooleanModelField? = null
    internal var donationCompetitionMode: ChoiceModelField? = null
    internal var receiveDonationCompetitionAward: BooleanModelField? = null
    internal var donationCompetitionTrySpecialFood: BooleanModelField? = null
    internal var donationCompetitionSpecialFoodCount: IntegerModelField? = null
    internal var stableDonationCompetitionAnytimeCheck: BooleanModelField? = null
    internal var donationCompetitionTime: StringModelField? = null
    internal var donationCompetitionOvertakeAmount: IntegerModelField? = null
    internal var watchDonationRank: BooleanModelField? = null
    internal var watchDonationAdvanceTime: IntegerModelField? = null
    internal var watchDonationRefreshInterval: IntegerModelField? = null
    internal var watchDonationLastRefreshSecondsBeforeEnd: IntegerModelField? = null
    internal var maxDailyDonationCompetitionCount: IntegerModelField? = null

    /**
     * 饲料任务
     */
    internal var doFarmTask: BooleanModelField? = null // 做饲料任务
    private var farmTaskTrigger: TimeTriggerModelField? = null // 饲料任务触发时间

    // 签到
    private var signRegardless: BooleanModelField? =null

    /**
     * 收取饲料奖励（无时间限制）
     */
    internal var receiveFarmTaskAward: BooleanModelField? = null
    internal var useAccelerateTool: BooleanModelField? = null
    internal var ignoreAcceLimit: BooleanModelField? = null
    private var useBigEaterTool: BooleanModelField? = null // ✅ 新增加饭卡

    /**
     * 喂鸡列表
     */
    private var feedFriendAnimalList: FriendSelectionCountModelField? = null
    internal var notifyFriend: BooleanModelField? = null
    private var notifyFriendType: ChoiceModelField? = null
    private var notifyFriendList: FriendSelectionModelField? = null
    private var acceptGift: BooleanModelField? = null
    private var visitFriendList: FriendSelectionCountModelField? = null
    internal var chickenDiary: BooleanModelField? = null
    private var diaryTietie: BooleanModelField? = null
    private var collectChickenDiary: ChoiceModelField? = null
    private lateinit var remainingTime: IntegerModelField
    private lateinit var accelerateToolDailyLimit: IntegerModelField
    internal var enableChouchoule: BooleanModelField? = null
    internal var chouChouLeTrigger: TimeTriggerModelField? = null // 抽抽乐触发时间
    var autoExchange: BooleanModelField? = null
    internal var exchangeDaysBeforeEndIp: IntegerModelField? = null  // IP 抽抽乐活动结束前兑换天数
    internal var autoExchangeList: SelectAndCountModelField? = null  // IP 抽抽乐自定义兑换列表
    private var listOrnaments: BooleanModelField? = null
    internal var hireAnimal: BooleanModelField? = null
    private var hireAnimalType: ChoiceModelField? = null
    private var hireAnimalList: FriendSelectionModelField? = null
    internal var enableDdrawGameCenterAward: BooleanModelField? = null
    internal var getFeed: BooleanModelField? = null
    private var getFeedlList: FriendSelectionModelField? = null
    private var getFeedType: ChoiceModelField? = null
    internal var family: BooleanModelField? = null
    internal var familyOptions: SelectModelField? = null
    internal var familyAssignStrategy: ChoiceModelField? = null
    internal var notInviteList: FriendSelectionModelField? = null
    private val giftFamilyDrawFragment: StringModelField? = null
    internal var paradiseCoinExchangeBenefit: BooleanModelField? = null
    private var paradiseCoinExchangeBenefitList: SelectModelField? = null

    internal var queryOrnamentMall: BooleanModelField? = null // 查询装扮商城开关
    internal var autoExchangeOrnamentLevel: ChoiceModelField? = null // 自动兑换装扮等级
    internal var onlyQueryNewOrnaments: BooleanModelField? = null // 仅查询未兑换装扮

    internal var visitAnimal: BooleanModelField? = null
    private var hasFence: Boolean = false       // 是否正在使用篱笆
    private var fenceCountDown: Int = 0
    // 雇佣NPC
    internal var npcAnimalType: ChoiceModelField? = null
    // NPC配置定义
    private enum class NpcConfig(val animalId: String, val source: String, val nickName: String) {
        NONE("", "", "关闭"),
        ZHIMA_PIGEON("20250901105101013088000000000006", "zhimaxiaoji_lianjin", "芝麻大表鸽"),
        GOLD_CHICKEN("20250725105101013088000000000004", "licaixiaoji_2025_1", "黄金鸡"),
        FARM_CHICKEN("20250613105101013088000000000002", "feiliaoji_202507", "农场小鸡");

        companion object {
            val nickNames: Array<String> by lazy {
                entries.map { it.nickName }.toTypedArray()
            }

            fun getByIndex(index: Int): NpcConfig {
                return entries.toTypedArray().getOrElse(index) { NONE }
            }
        }
    }

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            ChoiceModelField(
                "recallAnimalType",
                "召回小鸡 | 方式",
                RecallAnimalType.NEVER,
                RecallAnimalType.nickNames
            ).withDesc("控制遇到小鸡外出、偷吃或饥饿时是否主动召回。").also { recallAnimalType = it })
        modelFields.addField(
            BooleanModelField(
                "feedAnimal",
                "喂小鸡 | 开启",
                false
            ).withDesc("自动给自家小鸡喂食。").also { feedAnimal = it })
        modelFields.addField(
            BooleanModelField(
                "doFarmTask",
                "饲料任务 | 开启",
                false
            ).withDesc("执行庄园每日任务获取饲料、道具和抽奖机会。").also { doFarmTask = it })
        modelFields.addField(
            TimeTriggerModelField(
                "farmTaskTrigger",
                "饲料任务 | 触发时间",
                "-1",
                TimeTriggerParseOptions(
                    allowCheckpoints = true,
                    allowWindows = false,
                    allowBlockedWindows = false,
                    tag = TAG
                )
            ).withDesc("按检查点槽位尝试执行饲料任务；格式 HHmm 或 HHmmss，多个时间点用逗号分隔，填 -1 关闭。").also {
                farmTaskTrigger = it
            })
        modelFields.addField(
            BooleanModelField(
                "receiveFarmTaskAward",
                "饲料任务 | 领奖",
                false
            ).withDesc("自动领取已完成饲料任务的奖励。").also { receiveFarmTaskAward = it })
        modelFields.addField(
            BooleanModelField(
                "useBigEaterTool",
                "加饭卡 | 使用",
                false
            ).withDesc("自动使用加饭卡，延长单次进食时长。").also { useBigEaterTool = it })
        modelFields.addField(
            BooleanModelField(
                "useAccelerateTool",
                "加速卡 | 使用",
                false
            ).withDesc("自动使用加速卡缩短进食时间。").also { useAccelerateTool = it })
        modelFields.addField(
            IntegerModelField("remainingTime", "加速卡 | 防浪费阈值(分钟)(-1按60分钟)", 40, -1, null).withDesc(
                "剩余时间大于等于该值时才使用加速卡；-1 表示关闭自定义阈值并按默认60分钟无损模式处理，0 表示只要还有剩余时间就允许加速。"
            ).also { remainingTime = it }
        )
        modelFields.addField(
            IntegerModelField(
                "accelerateToolDailyLimit",
                "加速卡 | 每日最多使用张数(-1为不限)",
                1,
                -1,
                null
            ).withDesc("每日最多使用多少张加速卡；-1 不限，0 表示当日不使用。").also {
                accelerateToolDailyLimit = it
            }
        )
        modelFields.addField(
            BooleanModelField(
                "ignoreAcceLimit",
                "游戏改分/抽抽乐 | 仅按时间执行",
                false
            ).withDesc("开启后，游戏改分和抽抽乐只按设定时间执行，不再等待加速卡或游戏改分前置流程。").also {
                ignoreAcceLimit = it
            })
        modelFields.addField(
            BooleanModelField(
                "enableChouchoule",
                "装扮抽抽乐 | 开启",
                false
            ).withDesc("开启后执行庄园装扮抽抽乐，领取抽奖机会并参与抽奖。").also { enableChouchoule = it })
        modelFields.addField(
            BooleanModelField(
                "autoExchange",
                "装扮抽抽乐 | 最优兑换",
                false
            ).withDesc("开启后按奖励价值从高到低自动兑换装扮抽抽乐活动商店。需开启“装扮抽抽乐 | 开启”。").also { autoExchange = it })
        modelFields.addField(
            IntegerModelField("exchangeDaysBeforeEndIp", "装扮抽抽乐 | 活动结束前兑换天数(0每天)", 0, 0, 30).withDesc(
                "设置活动结束前多少天开始兑换；填 0 表示每天都按配置尝试兑换。需开启“装扮抽抽乐 | 最优兑换”。"
            ).also { exchangeDaysBeforeEndIp = it }
        )
        modelFields.addField(
            SelectAndCountModelField(
                "autoExchangeList",
                "装扮抽抽乐 | 自定义兑换列表",
                LinkedHashMap()
            ) { AntFarmIPChouChouLeBenefit.getList() }.withDesc(
                "只兑换列表中配置的活动奖励；不配置时按最优兑换策略处理。需开启“装扮抽抽乐 | 最优兑换”。"
            ).also {
                autoExchangeList = it
            })
        modelFields.addField(
            TimeTriggerModelField(
                "chouChouLeTrigger",
                "装扮抽抽乐 | 触发时间",
                "-1",
                TimeTriggerParseOptions(
                    allowCheckpoints = true,
                    allowWindows = true,
                    allowBlockedWindows = false,
                    tag = TAG
                )
            ).withDesc("控制抽抽乐尝试时机；支持时间点或允许时间段，格式 HHmm、HHmm-HHmm，填 -1 关闭。").also {
                chouChouLeTrigger = it
            })
        modelFields.addField(
            BooleanModelField(
                "recordFarmGame",
                "庄园小游戏 | 改分",
                false
            ).withDesc("执行星星球、登山赛、飞行赛、揍小鸡等庄园小游戏改分流程，按预估上限获取饲料。").also { recordFarmGame = it })
        modelFields.addField(
            IntegerModelField("gameRewardMax", "庄园小游戏 | 预计最大饲料(g)", 180, 0, null).withDesc(
                "游戏改分期望产出的最大饲料值，用于提前停止。"
            ).also { gameRewardMax = it }
        )
        modelFields.addField(
            TimeTriggerModelField(
                "farmGameTrigger",
                "庄园小游戏 | 执行时段",
                "-1",
                TimeTriggerParseOptions(
                    allowCheckpoints = false,
                    allowWindows = true,
                    allowBlockedWindows = false,
                    tag = TAG
                )
            ).withDesc("仅在这些允许时间段内执行游戏改分；支持多个 HHmm-HHmm，填 -1 关闭。").also {
                farmGameTrigger = it
            })
        modelFields.addField(
            BooleanModelField(
                "enableDdrawGameCenterAward",
                "小鸡乐园 | 开宝箱",
                false
            ).withDesc("自动领取小鸡乐园可开启的宝箱奖励。").also { enableDdrawGameCenterAward = it })
        modelFields.addField(
            TimePointModelField(
                "sleepTime",
                "小鸡作息 | 睡觉时间",
                "-1",
                true
            ).withDesc("设置自动让小鸡睡觉的时间。").also { sleepTime = it })
        modelFields.addField(
            TimePointModelField(
                "wakeupTime",
                "小鸡作息 | 起床时间",
                "-1",
                true
            ).withDesc("设置自动让小鸡起床的时间。").also { wakeUpTime = it })
        modelFields.addField(
            FriendSelectionCountModelField(
                "feedFriendAnimalList",
                "帮喂小鸡 | 好友列表"
            ).withDesc("配置帮喂好友及每日次数；列表中的数量表示可帮喂次数。").also {
                feedFriendAnimalList = it
            })
        modelFields.addField(
            BooleanModelField(
                "rewardFriend",
                "帮喂小鸡 | 打赏好友",
                false
            ).withDesc("自动处理可打赏的好友奖励。").also { rewardFriend = it })
        modelFields.addField(BooleanModelField("getFeed", "一起拿饲料 | 开启", false).withDesc(
            "处理“一起拿饲料”互动，可送给好友或随机送出。"
        ).also {
            getFeed = it
        })
        modelFields.addField(
            ChoiceModelField(
                "getFeedType",
                "一起拿饲料 | 动作",
                GetFeedType.GIVE,
                GetFeedType.nickNames
            ).withDesc("选择一起拿饲料的赠送策略。").also { getFeedType = it })
        modelFields.addField(
            FriendSelectionModelField(
                "getFeedlList",
                "一起拿饲料 | 好友列表"
            ).withDesc("仅对选中的好友执行一起拿饲料。").also {
                getFeedlList = it
            })
        modelFields.addField(BooleanModelField("acceptGift", "好友麦子 | 收取", false).withDesc(
            "自动收取好友赠送的麦子。"
        ).also {
            acceptGift = it
        })
        modelFields.addField(
            BooleanModelField(
                "visitAnimal",
                "到访小鸡送礼 | 开启",
                false
            ).withDesc("处理到访小鸡送礼，并按“到访小鸡送礼 | 好友与次数”配置给好友送麦子。").also { visitAnimal = it })
        modelFields.addField(
            FriendSelectionCountModelField(
                "visitFriendList",
                "到访小鸡送礼 | 好友与次数"
            ).withDesc("配置送麦子好友及每日赠送次数。需开启“到访小鸡送礼 | 开启”。").also {
                visitFriendList = it
            })
        modelFields.addField(
            BooleanModelField(
                "hireAnimal",
                "雇佣小鸡 | 开启",
                false
            ).withDesc("自动雇佣好友小鸡来打工赚取麦子。").also { hireAnimal = it })
        modelFields.addField(
            ChoiceModelField(
                "hireAnimalType",
                "雇佣小鸡 | 动作",
                HireAnimalType.DONT_HIRE,
                HireAnimalType.nickNames
            ).withDesc("选择名单模式：仅雇佣选中好友，或排除选中好友。需开启“雇佣小鸡 | 开启”。").also {
                hireAnimalType = it
            })
        modelFields.addField(
            FriendSelectionModelField(
                "hireAnimalList",
                "雇佣小鸡 | 好友列表"
            ).withDesc("仅在选中的好友列表内尝试雇佣小鸡。").also {
                hireAnimalList = it
            })
        modelFields.addField(
            ChoiceModelField(
                "npcAnimalType",
                "雇佣NPC小鸡(满产自动重雇)",
                NpcConfig.NONE.ordinal,
                NpcConfig.nickNames
            ).withDesc("选择自动雇佣并在满产后重雇的 NPC 小鸡；选“关闭”则不处理。").also {
                npcAnimalType = it
            })
        modelFields.addField(
            BooleanModelField(
                "sendBackAnimal",
                "遣返 | 开启",
                false
            ).withDesc("自动遣返来偷吃或做客的小鸡。").also { sendBackAnimal = it })
        modelFields.addField(
            IntegerModelField("timeSendBack", "遣返 | 投喂后等待(分钟,<10关闭)", 0, 0, 12 * 60).withDesc(
                "投喂后等待多少分钟再赶鸡，避免刚投喂就遣返；小于 10 分钟视为关闭。需开启“遣返 | 开启”。"
            ).also { timeSendBack = it }
        )
        modelFields.addField(
            ChoiceModelField(
                "sendBackAnimalWay",
                "遣返 | 方式",
                SendBackAnimalWay.NORMAL,
                SendBackAnimalWay.nickNames
            ).withDesc("选择遣返方式：攻击或常规赶回。需开启“遣返 | 开启”。").also {
                sendBackAnimalWay = it
            })
        modelFields.addField(
            ChoiceModelField(
                "sendBackAnimalType",
                "遣返 | 动作",
                SendBackAnimalType.NOT_BACK,
                SendBackAnimalType.nickNames
            ).withDesc("选择名单模式：仅遣返选中好友，或遣返未选中的好友。需开启“遣返 | 开启”。").also {
                sendBackAnimalType = it
            })
        modelFields.addField(
            FriendSelectionModelField(
                "dontSendFriendList",
                "遣返 | 好友列表"
            ).withDesc("设置遣返规则作用的好友名单。").also {
                sendBackAnimalList = it
            })
        modelFields.addField(
            BooleanModelField(
                "notifyFriend",
                "通知赶鸡 | 开启",
                false
            ).withDesc("自动通知好友赶回来偷吃的小鸡。").also { notifyFriend = it })
        modelFields.addField(
            ChoiceModelField(
                "notifyFriendType",
                "通知赶鸡 | 动作",
                NotifyFriendType.NOTIFY,
                NotifyFriendType.nickNames
            ).withDesc("选择通知名单模式：仅通知选中好友，或排除选中好友。需开启“通知赶鸡 | 开启”。").also {
                notifyFriendType = it
            })
        modelFields.addField(
            FriendSelectionModelField(
                "notifyFriendList",
                "通知赶鸡 | 好友列表"
            ).withDesc("设置通知规则作用的好友名单。需开启“通知赶鸡 | 开启”。").also {
                notifyFriendList = it
            })
        modelFields.addField(
            BooleanModelField(
                "donation",
                "每日捐蛋 | 开启",
                false
            ).withDesc("自动捐赠爱心鸡蛋到公益项目。").also { donation = it })
        modelFields.addField(
            ChoiceModelField(
                "donationMode",
                "每日捐蛋 | 模式",
                DonationMode.ONE_AVAILABLE_PROJECT,
                DonationMode.nickNames
            ).withDesc("控制普通每日公益捐蛋选择哪些项目。").also { donationMode = it })
        modelFields.addField(
            IntegerModelField(
                "donationAmount",
                "每日捐蛋 | 单次数量",
                1,
                1,
                20000
            ).withDesc("每一次公益捐蛋捐出的爱心蛋数量。").also { donationAmount = it })
        modelFields.addField(
            IntegerModelField(
                "maxDailyDonationCompetitionCount",
                "每日捐蛋上限",
                10,
                -1,
                20000
            ).withDesc("控制今日最多允许捐出的爱心蛋总量；普通每日公益捐蛋与排位赛补捐共享该上限，-1 表示不限制。").also {
                maxDailyDonationCompetitionCount = it
            })
        modelFields.addField(
            BooleanModelField(
                "donationCompetition",
                "捐蛋排位赛 | 开启",
                false
            ).withDesc("执行庄园捐蛋排位赛，自动加入并按配置执行卡点反超逻辑。").also { donationCompetition = it })
        modelFields.addField(
            ChoiceModelField(
                "donationCompetitionMode",
                "捐蛋排位赛 | 模式",
                DonationCompetitionMode.AGGRESSIVE,
                DonationCompetitionMode.nickNames
            ).withDesc("激进模式将尽量争取第一名排名；稳定模式按赛季进度只争取当天所需最低星星，必要时自动回退激进逻辑。").also {
                donationCompetitionMode = it
            })
        modelFields.addField(
            BooleanModelField(
                "stableDonationCompetitionAnytimeCheck",
                "捐蛋排位赛 | 稳定模式非蹲点评估",
                false
            ).withDesc("仅稳定模式生效；开启后，在每日结算前的每轮庄园流程中按稳定目标判断是否补捐。").also {
                stableDonationCompetitionAnytimeCheck = it
            })
        modelFields.addField(
            BooleanModelField(
                "receiveDonationCompetitionAward",
                "捐蛋排位赛 | 领取我的奖励",
                true
            ).withDesc("每轮结算后自动领取【我的奖励】中的普通美食、装扮币和段位装扮等奖励。需开启“捐蛋排位赛 | 开启”。").also {
                receiveDonationCompetitionAward = it
            })
        modelFields.addField(
            BooleanModelField(
                "donationCompetitionTrySpecialFood",
                "捐蛋排位赛 | 蛋不足使用特殊食品",
                false
            ).withDesc("仅在排位赛补捐时生效：鸡蛋不足会尝试使用特殊食品补充产蛋进度。需开启“使用特殊食品 | 开启”。").also {
                donationCompetitionTrySpecialFood = it
            })
        modelFields.addField(
            IntegerModelField(
                "donationCompetitionSpecialFoodCount",
                "捐蛋排位赛 | 特殊食品每日上限",
                1,
                -1,
                20000
            ).withDesc("仅用于排位赛补捐阶段自动使用特殊食品的次数上限；与日常“使用特殊食品 | 每日次数限制”独立计数，-1 表示不限制。").also {
                donationCompetitionSpecialFoodCount = it
            })
        modelFields.addField(
            StringModelField(
                "donationCompetitionTime",
                "捐蛋排位赛 | 单次蹲点时间",
                "1958"
            ).withDesc("设置执行卡点捐赠的时间：可以填具体时间如“1958”，或者填提前量如“2”（表示结束前2分钟）。").also {
                donationCompetitionTime = it
            })
        modelFields.addField(
            IntegerModelField(
                "donationCompetitionOvertakeAmount",
                "捐蛋排位赛 | 反超目标额外捐蛋数",
                1,
                1,
                10000
            ).withDesc("计算反超目标时，比目标排名当前捐蛋数多捐的爱心蛋数量；激进和稳定模式均生效。").also {
                donationCompetitionOvertakeAmount = it
            })
        modelFields.addField(
            BooleanModelField(
                "watchDonationRank",
                "捐蛋排位赛 | 轮询蹲点",
                false
            ).withDesc("在排位赛结束前开启高频轮询。激进模式将争取第一名排名；稳定模式将守住今日所需最低星数所在排名。").also { watchDonationRank = it })
        modelFields.addField(
            IntegerModelField(
                "watchDonationAdvanceTime",
                "捐蛋排位赛 | 提前蹲点时间(分钟)",
                2,
                1,
                10
            ).withDesc("设置提前多久开始进入高频轮询状态。").also { watchDonationAdvanceTime = it })
        modelFields.addField(
            IntegerModelField(
                "watchDonationRefreshInterval",
                "捐蛋排位赛 | 蹲点刷新间隔(秒)",
                10,
                1,
                60
            ).withDesc("高频轮询期间刷新排行榜的间隔时间。").also { watchDonationRefreshInterval = it })
        modelFields.addField(
            IntegerModelField(
                "watchDonationLastRefreshSecondsBeforeEnd",
                "捐蛋排位赛 | 轮询最后刷新距结束(秒)",
                2,
                0,
                10
            ).withDesc("控制轮询蹲点在结束前多少秒执行最后一次强制刷新，小于2秒意义不大，因为网络请求慢。").also {
                watchDonationLastRefreshSecondsBeforeEnd = it
            })
        modelFields.addField(
            BooleanModelField(
                "useSpecialFood",
                "使用特殊食品 | 开启",
                false
            ).withDesc("自动使用特殊食物，加快爱心鸡蛋进度。").also { useSpecialFood = it })
        modelFields.addField(
            IntegerModelField(
                "useSpecialFoodCount",
                "使用特殊食品 | 每日次数限制(-1为无限制)",
                1,
                -1,
                null
            ).withDesc("控制今日最多自动使用多少个特殊食品；-1 表示不限制。数量达到 10 个及以上时会优先按连续投喂批次处理。").also {
                useSpecialFoodCount = it
            })
        modelFields.addField(
            BooleanModelField(
                "useNewEggCard",
                "新蛋卡 | 使用",
                false
            ).withDesc("自动使用新蛋卡，切换到新的产蛋进度。").also { useNewEggCard = it })
        modelFields.addField(
            BooleanModelField(
                "signRegardless",
                "庄园签到忽略饲料余量",
                false
            ).withDesc("开启后签到时不再严格检查饲料槽空余，直接尝试领取签到饲料。").also {
                signRegardless = it
            })
        modelFields.addField(
            BooleanModelField(
                "receiveFarmToolReward",
                "收取道具奖励",
                false
            ).withDesc("自动领取庄园任务或活动中的道具类奖励。").also { receiveFarmToolReward = it })
        modelFields.addField(
            BooleanModelField(
                "harvestProduce",
                "收获爱心鸡蛋",
                false
            ).withDesc("有可收取的爱心鸡蛋时自动收取。").also { harvestProduce = it })
        modelFields.addField(BooleanModelField("kitchen", "小鸡厨房", false).withDesc(
            "执行小鸡厨房相关任务和做美食流程。"
        ).also { kitchen = it })
        modelFields.addField(
            BooleanModelField(
                "chickenDiary",
                "小鸡日记 | 开启",
                false
            ).withDesc("执行小鸡日记相关流程。开启后“小鸡日记 | 贴贴”和“小鸡日记 | 点赞”才会生效。").also { chickenDiary = it })
        modelFields.addField(
            BooleanModelField(
                "diaryTietze",
                "小鸡日记 | 贴贴",
                false
            ).withDesc("进入小鸡日记后自动执行贴贴操作。需开启“小鸡日记 | 开启”。").also { diaryTietie = it })
        modelFields.addField(
            ChoiceModelField(
                "collectChickenDiary",
                "小鸡日记 | 点赞",
                collectChickenDiaryType.CLOSE,
                collectChickenDiaryType.nickNames
            ).withDesc("设置小鸡日记点赞范围：不开启、一次、当月或所有。需开启“小鸡日记 | 开启”。").also {
                collectChickenDiary = it
            })
        modelFields.addField(
            BooleanModelField(
                "listOrnaments",
                "小鸡每日换装",
                false
            ).withDesc("每天随机切换一套已拥有的小鸡装扮。").also { listOrnaments = it })
        modelFields.addField(BooleanModelField("family", "家庭 | 开启", false).withDesc(
            "执行庄园家庭相关任务。"
        ).also { family = it })
        modelFields.addField(
            SelectModelField(
                "familyOptions",
                "家庭 | 选项",
                LinkedHashSet<String?>(),
                farmFamilyOption()
            ).withDesc("勾选允许自动执行的家庭任务类型。").also { familyOptions = it })
        modelFields.addField(
            ChoiceModelField(
                "familyAssignStrategy",
                "家庭 | 顶梁柱安排策略",
                FamilyAssignStrategy.RANDOM,
                FamilyAssignStrategy.nickNames
            ).withDesc("顶梁柱特权安排成员的策略；默认随机安排，低贡献策略会优先安排今日亲密值最低的家庭成员。").also {
                familyAssignStrategy = it
            })
        modelFields.addField(
            FriendSelectionModelField(
                "notInviteList",
                "家庭 | 好友分享排除列表"
            ).withDesc("家庭分享或邀请时排除这些好友。").also {
                notInviteList = it
            })
        //        modelFields.addField(giftFamilyDrawFragment = new StringModelField("giftFamilyDrawFragment", "家庭 | 扭蛋碎片赠送用户ID(配置目录查看)", ""));
        modelFields.addField(
            BooleanModelField(
                "paradiseCoinExchangeBenefit",
                "小鸡乐园 | 兑换权益",
                false
            ).withDesc("自动使用小鸡乐园币兑换选中的权益。").also { paradiseCoinExchangeBenefit = it })
        modelFields.addField(
            SelectModelField(
                "paradiseCoinExchangeBenefitList",
                "小鸡乐园 | 权益列表",
                LinkedHashSet<String?>()
            ) {
                refreshParadiseCoinExchangeOptionsForSettings()
                ParadiseCoinBenefit.getList()
            }.withDesc("仅兑换列表中的小鸡乐园权益。需开启“小鸡乐园 | 兑换权益”。").also {
                paradiseCoinExchangeBenefitList = it
            })
        modelFields.addField(
            BooleanModelField(
                "queryOrnamentMall",
                "装扮商城 | 开启",
                false
            ).withDesc("自动查询装扮币商城并根据配置执行兑换。").also { queryOrnamentMall = it })
        modelFields.addField(
            ChoiceModelField(
                "autoExchangeOrnamentLevel",
                "装扮商城 | 自动兑换等级",
                OrnamentLevel.NONE,
                OrnamentLevel.nickNames
            ).withDesc("选择自动兑换的装扮等级。需开启“装扮商城 | 开启”。").also { autoExchangeOrnamentLevel = it })
        modelFields.addField(
            BooleanModelField(
                "onlyQueryNewOrnaments",
                "装扮商城 | 只查询新装扮",
                false
            ).withDesc("开启后不执行兑换，仅查询并提示商城中未拥有的装扮。需开启“装扮商城 | 开启”。").also {
                onlyQueryNewOrnaments = it
            })
        return modelFields
    }

    override fun boot(classLoader: ClassLoader?) {
        super.boot(classLoader)
        instance = this
        addIntervalLimit("com.alipay.antfarm.enterFarm", 2000)
    }

    override suspend fun runSuspend() {
        try {
            val tc = TimeCounter(TAG)
            val userId = UserMap.currentUid
            Log.farm("执行开始-${getName()}")
            invalidToolTypesThisRound.clear()
            manurePotCollectionBlockedThisRound = false

            if (!runFarmLifecycleWorkflow(tc)) {
                return
            }
            val pendingFarmTaskFinalization = runFarmTaskWorkflow(tc, userId)
            runFarmSocialWorkflow(tc, pendingFarmTaskFinalization)
            runFarmFinalizeWorkflow(tc)
        } catch (e: CancellationException) {
            // 协程取消是正常现象，不记录为错误
             Log.farm("AntFarm 协程被取消")
            throw e  // 必须重新抛出以保证取消机制正常工作
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "AntFarm.start.run err:",t)
        } finally {
            Log.farm("执行结束-${getName()}")
        }
    }

    internal fun shouldHireAnimalNow(): Boolean {
        return hireAnimal?.value == true && AnimalFeedStatus.SLEEPY.name != ownerAnimal.animalFeedStatus
    }

    internal fun shouldRunNpcAnimalLogic(): Boolean {
        return npcAnimalType?.value != NpcConfig.NONE.ordinal
    }

    internal fun isOwnerAnimalSleeping(): Boolean {
        return AnimalFeedStatus.SLEEPY.name == ownerAnimal.animalFeedStatus
    }

    internal fun isOwnerAnimalAtHome(): Boolean {
        return AnimalInteractStatus.HOME.name == ownerAnimal.animalInteractStatus
    }

    internal fun ensureOwnerAnimalAtHome(actionName: String): Boolean {
        if (AnimalInteractStatus.HOME.name == ownerAnimal.animalInteractStatus) {
            return true
        }

        Log.farm("$actionName 前检测到小鸡不在庄园，尝试召回")
        recallAnimal()
        if (!ownerFarmId.isNullOrBlank()) {
            syncAnimalStatus(ownerFarmId)
        }
        if (AnimalInteractStatus.HOME.name == ownerAnimal.animalInteractStatus) {
            return true
        }

        Log.farm("$actionName 跳过：小鸡仍不在庄园[互动状态=${ownerAnimal.animalInteractStatus ?: "未知"}]")
        return false
    }

    internal fun shouldHarvestProduceNow(): Boolean {
        return harvestProduce?.value == true && benevolenceScore >= 1
    }

    internal fun shouldDonateEggNow(userId: String?): Boolean {
        if (donation?.value != true || userId.isNullOrBlank()) {
            return false
        }
        val amount = donationAmount?.value ?: 1
        val dailyLimit = maxDailyDonationCompetitionCount?.value ?: -1
        if (dailyLimit >= 0) {
            val remainingQuota = dailyLimit - Status.getDailyDonationTotal(userId)
            if (remainingQuota < amount) {
                return false
            }
        }
        if (Status.hasFlagToday(StatusFlags.FLAG_FARM_DAILY_DONATION_DONE_PREFIX + userId)) {
            return false
        }
        return harvestBenevolenceScore >= amount
    }

    internal fun isAutoUseSpecialFoodEnabled(): Boolean {
        return useSpecialFood?.value == true
    }

    internal fun preloadFarmTools() {
        listFarmTool()
    }

    /**
     * 召回小鸡
     */
    internal fun recallAnimal() {
        try {
            //召回小鸡相关操作
            if (AnimalInteractStatus.HOME.name != ownerAnimal.animalInteractStatus) { //如果小鸡不在家
                if ("ORCHARD" == ownerAnimal.locationType) {
                    Log.farm("庄园通知📣[你家的小鸡给拉去除草了！]")
                    val joRecallAnimal = JSONObject(
                        AntFarmRpcCall.orchardRecallAnimal(
                            ownerAnimal.animalId,
                            ownerAnimal.currentFarmMasterUserId
                        )
                    )
                    val manureCount = joRecallAnimal.getInt("manureCount")
                    Log.farm("召回小鸡📣[收获:肥料" + manureCount + "g]")
                } else {
                    Log.farm("DEBUG:$ownerAnimal")

                    syncAnimalStatus(ownerFarmId)
                    var guest = false
                    when (SubAnimalType.valueOf(ownerAnimal.subAnimalType!!)) {
                        SubAnimalType.GUEST -> {
                            guest = true
                            Log.farm("小鸡到好友家去做客了")
                        }

                        SubAnimalType.NORMAL -> Log.farm("小鸡太饿，离家出走了")
                        SubAnimalType.PIRATE -> Log.farm("小鸡外出探险了")
                        SubAnimalType.WORK -> Log.farm("小鸡出去工作啦")
                    }
                    var hungry = false
                    val userName =
                        UserMap.getMaskName(AntFarmRpcCall.farmId2UserId(ownerAnimal.currentFarmId))
                    when (AnimalFeedStatus.valueOf(ownerAnimal.animalFeedStatus!!)) {
                        AnimalFeedStatus.HUNGRY -> {
                            hungry = true
                            Log.farm("小鸡在[$userName]的庄园里挨饿")
                        }

                        AnimalFeedStatus.EATING -> Log.farm("小鸡在[$userName]的庄园里吃得津津有味"
                        )
                        AnimalFeedStatus.SLEEPY -> Log.farm("小鸡在[$userName]的庄园里睡觉")
                        AnimalFeedStatus.NONE -> Log.farm("小鸡在[$userName]的庄园里状态未知")
                    }
                    val recall = when (recallAnimalType!!.value) {
                        RecallAnimalType.ALWAYS -> true
                        RecallAnimalType.WHEN_THIEF -> !guest
                        RecallAnimalType.WHEN_HUNGRY -> hungry
                        else -> false
                    }
                    if (recall) {
                        recallAnimal(
                            ownerAnimal.animalId,
                            ownerAnimal.currentFarmId,
                            ownerFarmId,
                            userName
                        )
                        syncAnimalStatus(ownerFarmId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "recallAnimal err:", e)
        }
    }

    /**
     * 处理装扮币商城逻辑
     */
    internal suspend fun handleOrnamentMall() {
        try {
            AntFarmRpcCall.syncOrnamentCoin()

            val response = AntFarmRpcCall.getOrnamentItemList(10, 0)
            val jo = JSONObject(response)
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.farm("装扮商城💸[获取列表失败: ${jo.optString("desc")}]")
                return
            }

            val accountInfo = jo.optJSONObject("mallAccountInfoVO")
            val holdingCount = accountInfo?.optJSONObject("holdingCount")
            var balance = holdingCount?.optDouble("amount", 0.0) ?: 0.0

            val itemInfoVOList = jo.optJSONArray("itemInfoVOList") ?: return
            val configLevelIdx = autoExchangeOrnamentLevel?.value ?: OrnamentLevel.NONE

            val configLevelStr = OrnamentLevel.levels[configLevelIdx]
            val isQueryOnly =
                onlyQueryNewOrnaments?.value == true || configLevelIdx == OrnamentLevel.NONE

            Log.farm("装扮商城💸[当前余额: $balance 装扮币 | 设定等级: ${OrnamentLevel.nickNames[configLevelIdx]}${if (configLevelIdx == OrnamentLevel.NONE) " (仅查询模式)" else ""}]")

            var foundMatch = false
            for (i in 0 until itemInfoVOList.length()) {
                val itemJo = itemInfoVOList.getJSONObject(i)
                val spuName = itemJo.optString("spuName")
                val spuId = itemJo.optString("spuId")
                val itemStatus = itemJo.optString("itemStatus")
                val minPrice = itemJo.optJSONObject("minPrice")?.optDouble("amount", 0.0) ?: 0.0
                val offlineTime = itemJo.optLong("offlineTime", 0L)

                val spuExtendInfoStr = itemJo.optString("spuExtendInfo")
                val spuExtendInfo =
                    if (spuExtendInfoStr.isNotEmpty()) JSONObject(spuExtendInfoStr) else JSONObject()
                val dressUpLevel = spuExtendInfo.optString("dressUpLevel", "UNKNOWN")

                val isOwned = itemStatus == "REACH_USER_HOLD_LIMIT"

                if (isQueryOnly) {
                    if (!isOwned) {
                        val expireStr = if (offlineTime > 0) TimeUtil.getFormatTime(
                            offlineTime,
                            "yyyy-MM-dd HH:mm:ss"
                        ) else "无"
                        Log.farm("装扮商城🔍[发现未拥有: $spuName | 等级: $dressUpLevel | 价格: $minPrice | 过期时间: $expireStr]")
                    }
                    continue
                }

                if (isOwned) continue

                if (configLevelStr != "ALL" && configLevelStr != dressUpLevel) continue

                foundMatch = true
                if (balance < minPrice) {
                    Log.farm("装扮商城💸[$spuName] 余额不足 (需要: $minPrice, 当前: $balance)"                    )
                    continue
                }

                // 执行兑换
                Log.farm("装扮商城💸[准备兑换 $spuName ($dressUpLevel), 价格: $minPrice]")

                val skuModelList = itemJo.optJSONArray("skuModelList")
                if (skuModelList == null || skuModelList.length() == 0) continue
                val skuId = skuModelList.getJSONObject(0).optString("skuId")

                AntFarmRpcCall.getOrnamentItemDetail(spuId)
                delay(1000)

                val exchangeRes = AntFarmRpcCall.exchangeOrnamentBenefit(spuId, skuId)
                val resJo = JSONObject(exchangeRes)
                if (resJo.optBoolean("success")) {
                    Log.farm("装扮商城💸[兑换成功: $spuName]")
                    balance -= minPrice
                    delay(2000)
                } else {
                    Log.farm("装扮商城💸[兑换失败: $spuName, 原因: ${resJo.optString("resultDesc")}]")
                }
            }

            if (onlyQueryNewOrnaments?.value != true && configLevelIdx != OrnamentLevel.NONE && !foundMatch) {
                Log.farm("装扮商城💸[当前选择等级(${OrnamentLevel.nickNames[configLevelIdx]})中没有发现未兑换的装扮]")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "handleOrnamentMall err:", t)
        }
    }


    private fun refreshParadiseCoinExchangeOptionsForSettings() {
        if (!HookReadyChecker.isCurrentProcessReadyForRpc(UserMap.currentUid)) {
            if (!HookReadyChecker.isTargetAppReadyForRpc(UserMap.currentUid) ||
                !ExchangeOptionsRefreshBridge.requestRefresh(
                    ExchangeOptionsRefreshBridge.TARGET_FARM_PARADISE,
                    UserMap.currentUid
                )
            ) {
                Log.farm("小鸡乐园币💸目标应用未就绪，设置页使用缓存列表")
                return
            }
            val benefitMap = IdMapManager.getInstance(ParadiseCoinBenefitIdMap::class.java)
            benefitMap.load(UserMap.currentUid)
            Log.farm("小鸡乐园币💸设置页加载目标应用刷新列表#${benefitMap.map.size}")
            return
        }
        try {
            val jo = JSONObject(AntFarmRpcCall.getMallHome())
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error(TAG, "小鸡乐园币💸[设置页刷新权益列表失败]")
                return
            }
            val mallItemSimpleList = jo.optJSONArray("mallItemSimpleList") ?: return
            val benefitMap = IdMapManager.getInstance(ParadiseCoinBenefitIdMap::class.java)
            for (i in 0..<mallItemSimpleList.length()) {
                val mallItemInfo = mallItemSimpleList.optJSONObject(i) ?: continue
                val spuName = mallItemInfo.optString("spuName")
                val minPrice = mallItemInfo.optInt("minPrice")
                val controlTag = mallItemInfo.optString("controlTag")
                val spuId = mallItemInfo.optString("spuId")
                if (spuId.isBlank()) {
                    continue
                }
                val itemStatusList = mallItemInfo.optJSONArray("itemStatusList")
                val statusText = formatFarmPropStatusList(itemStatusList)
                val exchangeItem = ExchangeItem(
                    id = spuId,
                    name = spuName.ifBlank { spuId },
                    cost = ExchangeCost(pointText = "${minPrice}乐园币"),
                    limit = ExchangeLimit(statusText = listOf(controlTag, statusText).filter { it.isNotBlank() }.joinToString("、")),
                    safety = if (hasBlockingFarmPropStatus(itemStatusList)) ExchangeSafety.UNAVAILABLE else ExchangeSafety.AUTO,
                    safetyReason = if (hasBlockingFarmPropStatus(itemStatusList)) statusText else ""
                )
                benefitMap.add(spuId, exchangeItem.displayName())
            }
            benefitMap.save(UserMap.currentUid)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "refreshParadiseCoinExchangeOptionsForSettings err:", t)
        }
    }

    internal fun refreshParadiseCoinExchangeOptionsForRemote() {
        refreshParadiseCoinExchangeOptionsForSettings()
    }

    internal suspend fun paradiseCoinExchangeBenefit() {
        try {
            val jo = JSONObject(AntFarmRpcCall.getMallHome())

            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error(TAG, "小鸡乐园币💸[未获取到可兑换权益]")
                return
            }
            val mallItemSimpleList = jo.getJSONArray("mallItemSimpleList")
            for (i in 0..<mallItemSimpleList.length()) {
                val mallItemInfo = mallItemSimpleList.getJSONObject(i)
                val oderInfo: String?
                val spuName = mallItemInfo.getString("spuName")
                val minPrice = mallItemInfo.getInt("minPrice")
                val controlTag = mallItemInfo.getString("controlTag")
                val spuId = mallItemInfo.getString("spuId")
                val itemStatusList = mallItemInfo.optJSONArray("itemStatusList")
                val statusText = formatFarmPropStatusList(itemStatusList)
                val exchangeItem = ExchangeItem(
                    id = spuId,
                    name = spuName,
                    cost = ExchangeCost(pointText = "${minPrice}乐园币"),
                    limit = ExchangeLimit(statusText = listOf(controlTag, statusText).filter { it.isNotBlank() }.joinToString("、")),
                    safety = if (hasBlockingFarmPropStatus(itemStatusList)) ExchangeSafety.UNAVAILABLE else ExchangeSafety.AUTO,
                    safetyReason = if (hasBlockingFarmPropStatus(itemStatusList)) statusText else ""
                )
                oderInfo = exchangeItem.displayName()
                IdMapManager.getInstance(ParadiseCoinBenefitIdMap::class.java)
                    .add(spuId, oderInfo)
                if (!Status.canParadiseCoinExchangeBenefitToday(spuId) ||
                    paradiseCoinExchangeBenefitList?.value?.contains(spuId) != true ||
                    isExchange(itemStatusList ?: JSONArray(), spuId, spuName)
                ) {
                    continue
                }
                var exchangedCount = 0
                while (exchangeBenefit(spuId)) {
                    exchangedCount += 1
                    Log.farm("乐园币兑换💸#花费[" + minPrice + "乐园币]" + "#第" + exchangedCount + "次兑换" + "[" + spuName + "]")
                }
            }
            IdMapManager.getInstance(ParadiseCoinBenefitIdMap::class.java)
                .save(UserMap.currentUid)
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("paradiseCoinExchangeBenefit 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "paradiseCoinExchangeBenefit err:",t)
        }
    }

    private fun exchangeBenefit(spuId: String?): Boolean {
        try {
            val jo = JSONObject(AntFarmRpcCall.getMallItemDetail(spuId))
            if (!ResChecker.checkRes(TAG, jo)) {
                return false
            }
            val mallItemDetail = jo.getJSONObject("mallItemDetail")
            val mallSubItemDetailList = mallItemDetail.getJSONArray("mallSubItemDetailList")
            for (i in 0..<mallSubItemDetailList.length()) {
                val mallSubItemDetail = mallSubItemDetailList.getJSONObject(i)
                val skuId = mallSubItemDetail.getString("skuId")
                val skuName = mallSubItemDetail.getString("skuName")
                val itemStatusList = mallSubItemDetail.getJSONArray("itemStatusList")

                if (isExchange(itemStatusList, spuId, skuName)) {
                    return false
                }

                if (exchangeBenefit(spuId, skuId)) {
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "exchangeBenefit err:",t)
        }
        return false
    }

    private fun exchangeBenefit(spuId: String?, skuId: String?): Boolean {
        try {
            val jo = JSONObject(AntFarmRpcCall.exchangeBenefit(spuId, skuId))
            return ResChecker.checkRes(TAG, jo)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "exchangeBenefit err:",t)
        }
        return false
    }

    private fun formatFarmPropStatusList(itemStatusList: JSONArray?): String {
        if (itemStatusList == null || itemStatusList.length() == 0) {
            return ""
        }
        val statuses = mutableListOf<String>()
        for (j in 0..<itemStatusList.length()) {
            val itemStatus = itemStatusList.optString(j)
            if (itemStatus.isBlank()) {
                continue
            }
            val statusName = runCatching { PropStatus.valueOf(itemStatus).nickName()?.toString() }
                .getOrNull()
                ?: itemStatus
            statuses.add(statusName)
        }
        return statuses.joinToString("、")
    }

    private fun hasBlockingFarmPropStatus(itemStatusList: JSONArray?): Boolean {
        if (itemStatusList == null || itemStatusList.length() == 0) {
            return false
        }
        for (j in 0..<itemStatusList.length()) {
            when (itemStatusList.optString(j)) {
                PropStatus.REACH_LIMIT.name,
                PropStatus.REACH_USER_HOLD_LIMIT.name,
                PropStatus.NO_ENOUGH_POINT.name -> return true
            }
        }
        return false
    }

    private fun isExchange(itemStatusList: JSONArray, spuId: String?, spuName: String?): Boolean {
        try {
            for (j in 0..<itemStatusList.length()) {
                val itemStatus = itemStatusList.getString(j)
                if (PropStatus.REACH_LIMIT.name == itemStatus
                    || PropStatus.REACH_USER_HOLD_LIMIT.name == itemStatus
                    || PropStatus.NO_ENOUGH_POINT.name == itemStatus
                ) {
                    Log.farm("乐园兑换💸[$spuName]停止:" + PropStatus.valueOf(itemStatus)
                            .nickName()
                    )
                    if (PropStatus.REACH_LIMIT.name == itemStatus) {
                        Status.setFlagToday(StatusFlags.FLAG_FARM_PARADISE_COIN_EXCHANGE_LIMIT_PREFIX + spuId)
                    }
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "isItemExchange err:",t)
        }
        return false
    }

    internal fun animalSleepAndWake() {
        try {
            val now = TimeUtil.getNow()
            val animalSleepTime = when {
                sleepTime?.isDisabled() == true -> {
                    Log.farm("当前已关闭小鸡睡觉")
                    null
                }
                else -> sleepTime?.getTodayPointAt(now.timeInMillis)
            }
            if (sleepTime?.isDisabled() != true && animalSleepTime == null) {
                Log.farm("小鸡睡觉时间解析失败，请重新设置")
            }

            var animalWakeUpTime = when {
                wakeUpTime?.isDisabled() == true -> {
                    Log.farm("当前已关闭小鸡起床")
                    null
                }
                else -> wakeUpTime?.getTodayPointAt(now.timeInMillis)
            }
            if (wakeUpTime?.isDisabled() != true && animalWakeUpTime == null) {
                Log.farm("小鸡起床时间解析失败，请重新设置，否则默认06:00")
                animalWakeUpTime = TimePointModelField("defaultWakeupTime", "默认起床时间", "0600").getTodayPointAt(now.timeInMillis)
            }
            if (animalSleepTime == null && animalWakeUpTime == null) {
                return
            }
            val sixAmToday = TimeUtil.getTodayCalendarByTimeStr("0600") ?: return
            if (now.after(sixAmToday)) {
                animalWakeUpTime = animalWakeUpTime?.plus(24 * 60 * 60 * 1000L)
            }

            val animalSleepTimeCalendar = animalSleepTime?.let {
                Calendar.getInstance().apply { timeInMillis = it }
            }
            val animalWakeUpTimeCalendar = animalWakeUpTime?.let {
                Calendar.getInstance().apply { timeInMillis = it }
            }
            val afterSleepTime = animalSleepTimeCalendar?.let { now > it } ?: false
            val afterWakeUpTime = animalWakeUpTimeCalendar?.let { now > it } ?: false
            val afterSixAm = now >= sixAmToday

            if (animalSleepTimeCalendar != null && animalWakeUpTimeCalendar != null && afterSleepTime && afterWakeUpTime) {
                if (!Status.canAnimalSleep()) {
                    return
                }
                Log.farm("已错过小鸡今日睡觉时间")
                return
            }
            val sleepTaskId = animalSleepTime?.let { "AS|$it" }
            val wakeUpTaskId = animalWakeUpTime?.let { "AW|$it" }
            if (animalSleepTime != null && sleepTaskId != null && !hasChildTask(sleepTaskId) && !afterSleepTime) {
                addChildTask(
                    ChildModelTask(
                        sleepTaskId,
                        "AS",
                        suspendRunnable = {
                            this.animalSleepNow()
                            syncAnimalStatus(ownerFarmId)
                            receiveFarmAwards()
                        },
                        animalSleepTime
                    )
                )
                Log.farm("添加定时睡觉🛌[" + UserMap.getCurrentMaskName() + "]在[" + TimeUtil.getCommonDate(
                        animalSleepTime
                    ) + "]执行"
                )
            }
            if (animalWakeUpTime != null && wakeUpTaskId != null && !hasChildTask(wakeUpTaskId) && !afterWakeUpTime) {
                addChildTask(
                    ChildModelTask(
                        wakeUpTaskId,
                        "AW",
                        suspendRunnable = { this.animalWakeUpNow() },
                        animalWakeUpTime
                    )
                )
                Log.farm("添加定时起床🛌[" + UserMap.getCurrentMaskName() + "]在[" + TimeUtil.getCommonDate(
                        animalWakeUpTime
                    ) + "]执行"
                )
            }
            if (animalSleepTimeCalendar != null && afterSleepTime) {
                if (Status.canAnimalSleep()) {
                    animalSleepNow()
                }
            }
            if (animalWakeUpTimeCalendar != null && afterWakeUpTime && !afterSixAm) {
                if (Status.canAnimalSleep()) {
                    animalWakeUpNow()
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG,"animalSleepAndWake err:",e)
        }
    }

    /**
     * 初始化庄园
     *
     * @return 庄园信息
     */
    internal fun enterFarm(): JSONObject? {
        try {
            val userId = UserMap.currentUid
            val jo = JSONObject(AntFarmRpcCall.enterFarm(userId, userId))
            if (ResChecker.checkRes(TAG, jo)) {
                rewardProductNum =
                    jo.getJSONObject("dynamicGlobalConfig").getString("rewardProductNum")
                val joFarmVO = jo.getJSONObject("farmVO")
                val subFarmVO = joFarmVO.getJSONObject("subFarmVO")
                val familyInfoVO = jo.getJSONObject("familyInfoVO")
                foodStock = joFarmVO.getInt("foodStock")
                foodStockLimit = joFarmVO.getInt("foodStockLimit")
                harvestBenevolenceScore = joFarmVO.getDouble("harvestBenevolenceScore")

                parseSyncAnimalStatusResponse(joFarmVO)

                joFarmVO.getJSONObject("masterUserInfoVO").getString("userId")
                familyGroupId = familyInfoVO.optString("groupId", "")
                // 领取活动食物
                val activityData = jo.optJSONObject("activityData")
                if (activityData != null) {
                    val it = activityData.keys()
                    while (it.hasNext()) {
                        val key = it.next()
                        if (key.contains("Gifts")) {
                            val gifts = activityData.optJSONArray(key) ?: continue
                            for (i in 0..<gifts.length()) {
                                val gift = gifts.optJSONObject(i)
                                clickForGiftV2(gift)
                            }
                        }
                    }
                }
                if (useSpecialFood?.value == true) { //使用特殊食品
                    val cuisineList = jo.optJSONArray("cuisineList")
                    if (cuisineList != null &&
                        AnimalInteractStatus.HOME.name != ownerAnimal.animalInteractStatus
                    ) {
                        Log.farm("小鸡当前不在庄园，暂不使用特殊食品，等待召回后再试")
                    } else if (cuisineList != null &&
                        AnimalFeedStatus.SLEEPY.name != ownerAnimal.animalFeedStatus
                    ) {
                        val dailyLimit = useSpecialFoodCount?.value ?: -1
                        val usedToday =
                            Status.getIntFlagToday(StatusFlags.FLAG_FARM_SPECIAL_FOOD_DAILY_COUNT) ?: 0
                        if (dailyLimit > 0 &&
                            (Status.hasFlagToday(StatusFlags.FLAG_FARM_SPECIAL_FOOD_LIMIT) || usedToday >= dailyLimit)
                        ) {
                            Status.setFlagToday(StatusFlags.FLAG_FARM_SPECIAL_FOOD_LIMIT)
                            Log.farm("特殊食品今日已使用${usedToday}个，达到每日上限${dailyLimit}个，跳过")
                        } else {
                            val remainingDailyQuota = if (dailyLimit > 0) dailyLimit - usedToday else -1
                            useSpecialFood(cuisineList, remainingDailyQuota)
                        }
                    }
                }

                if (jo.has("lotteryPlusInfo")) { //彩票附加信息
                    drawLotteryPlus(jo.getJSONObject("lotteryPlusInfo"))
                }

                if (acceptGift?.value == true &&
                    foodStockLimit - foodStock >= 10 &&
                    shouldAcceptGift(subFarmVO)
                ) {
                    acceptGift()
                }
                return jo
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
        return null
    }

    /**
     * 自动喂鸡
     */
    internal suspend fun handleAutoFeedAnimal(isChildTask: Boolean = false) {
        if (!ownerFarmId.isNullOrBlank()) {
            syncAnimalStatus(ownerFarmId)
        }

//        val sleepTimeStr = sleepTime!!.value
//        if (sleepTimeStr != "-1") {
//            val now = TimeUtil.getNow()
//            val sleepCal = TimeUtil.getTodayCalendarByTimeStr(sleepTimeStr)
//            // 如果当前时间在睡觉时间之前，且差距小于 30 分钟
//            if (now.before(sleepCal) && (sleepCal.timeInMillis - now.timeInMillis) < 30 * 60 * 1000) {
//                Log.farm("马上要睡觉了，暂不投喂，让它饿着吧")
//                return
//            }
//            // 如果已经过了睡觉时间，理论上也不应该喂，但原逻辑会在后面 animalSleepAndWake 处理睡觉
//            if (now.after(sleepCal)) {
//                Log.farm("已过睡觉时间，暂不投喂")
//                return
//            }
//        }

        if (!ensureOwnerAnimalAtHome("喂食")) {
            return
        }

        if (AnimalFeedStatus.SLEEPY.name == ownerAnimal.animalFeedStatus) {
            Log.farm("投喂小鸡🥣[小鸡正在睡觉中，暂停投喂]")
            return
        }

        // 1. 如果不够一次喂食180g时尝试领取奖励，首次运行时unreceiveTaskAward=0
        if (receiveFarmTaskAward?.value == true && foodStock <180) {
            Log.farm("饲料小于180g，尝试领取饲料奖励")
            receiveFarmAwards() // 该步骤会自动计算饲料数量，不需要重复刷新状态
        }

        // 2. 判断是否需要喂食
        if (AnimalFeedStatus.HUNGRY.name == ownerAnimal.animalFeedStatus) {
            if (feedAnimal?.value == true) {
                Log.farm("小鸡在挨饿, 尝试为你自动喂食")
                if (feedAnimal(ownerFarmId)) {
                    // 刷新状态
                    syncAnimalStatus(ownerFarmId)
                }
            }
        }

        // 3. 使用加饭卡（仅当正在吃饭且开启配置）
        if (useBigEaterTool?.value == true && AnimalFeedStatus.EATING.name == ownerAnimal.animalFeedStatus) {
            // 若服务端已标记今日使用过（或当前有效），本地直接跳过
            if (serverUseBigEaterTool) {
                Log.farm("服务端标记已使用加饭卡，跳过使用")
                // 这里可选：尝试与本地计数对齐（仅在计数为0时+1，避免重复累加）
                val today = LocalDate.now().toString()
                val uid = UserMap.currentUid
                val usedKey = "AF_BIG_EATER_USED_COUNT|$uid|$today"
                val usedCount = DataStore.get(usedKey, Int::class.java) ?: 0
                if (usedCount == 0) {
                    DataStore.put(usedKey, 1)
                }
            } else {
                // 使用 DataStore 记录“当日已用次数”，每日上限为 2 次（按账号维度）
                val today = LocalDate.now().toString()
                val uid = UserMap.currentUid
                val usedKey = "AF_BIG_EATER_USED_COUNT|$uid|$today"
                val usedCount = DataStore.get(usedKey, Int::class.java) ?: 0

                if (usedCount >= 2) {
                    Log.farm("今日加饭卡已使用${usedCount}/2，跳过使用")
                } else {
                    val bigEaterCount = getFarmToolCount(ToolType.BIG_EATER_TOOL, forceRefresh = true)
                    if (bigEaterCount <= 0) {
                        Log.farm("背包中无加饭卡，跳过使用")
                    } else {
                        val result = useFarmTool(ownerFarmId, ToolType.BIG_EATER_TOOL)
                        if (result) {
                            Log.farm("使用道具🎭[加饭卡]！")
                            DataStore.put(usedKey, usedCount + 1)
                            // 刷新状态
                            syncAnimalStatus(ownerFarmId)
                        } else {
                            Log.farm("⚠️使用道具🎭[加饭卡]失败，可能卡片不足或状态异常~")
                        }
                    }
                }
            }
        }

        // 4. 判断是否需要使用加速道具（仅在正在吃饭时尝试）
        if (useAccelerateTool?.value == true && AnimalFeedStatus.EATING.name == ownerAnimal.animalFeedStatus) {
            // 记录调试日志：加速卡判定前的关键状态
            Log.farm("加速卡判断⏩[动物状态=" + toFeedStatusName(ownerAnimal.animalFeedStatus) +
                        ", " + getAccelerateToolUsageSummary() +
                        ", 今日封顶=" + (detectAccelerateToolLimit(syncFlag = false) != null) + "]"
            )
            val accelerated = useAccelerateTool()
            if (accelerated) {
                Log.farm("使用道具🎭[加速卡]⏩成功")
                // 刷新状态
                syncAnimalStatus(ownerFarmId)
            }
        }

        // 在蹲点喂食逻辑中判断是否需要执行游戏改分及抽抽乐
        if (isChildTask) {
            if (recordFarmGame?.value == true) {
                FarmGame.run(this@AntFarm)
            }
            if (enableChouchoule?.value == true) {
                ChouChouLe().run(this@AntFarm)
                handleMultiStageTasksLoop()
            }
        }

        // 5. 计算并安排下一次自动喂食任务（仅当小鸡不在睡觉时）
        if (AnimalFeedStatus.SLEEPY.name != ownerAnimal.animalFeedStatus) {
            try {
                /* 创建蹲点任务时间点前先同步countdown，因为可能因为好友小鸡在两次执行间隔间偷吃而引起蹲点时间变动。
                    比如投喂后程序第一次计算了剩余时间是4小时40分钟，那中间有小鸡偷吃，时间就少于4：40分钟了。再用原来
                    的时间显然有误,除非其他逻辑同步了小鸡状态才会修正，这里直接同步+修正
                 */
                syncAnimalStatus(ownerFarmId)
                // 直接使用服务器计算的权威倒计时（单位：秒）
                val remainingSec = countdown?.toDouble()?.coerceAtLeast(0.0)
                // 如果倒计时为0，跳过任务创建
                remainingSec?.let {
                    if (it > 0) {
                        // 计算下次执行时间（毫秒）
                        val nextFeedTime = System.currentTimeMillis() + (remainingSec * 1000).toLong()
                        // 调试日志：显示服务器倒计时详情
                        Log.farm("服务器倒计时🕐[小鸡状态=" + toFeedStatusName(ownerAnimal.animalFeedStatus) +
                                    ", 剩余=${remainingSec.toInt()}秒" +
                                    ", 执行时间=" + TimeUtil.getCommonDate(nextFeedTime) + "]"
                        )
                        val taskId = "FA|$ownerFarmId"
                        addChildTask(
                            ChildModelTask(
                                id = taskId,
                                group = "FA",
                                suspendRunnable = {
                                    try {
                                        Log.farm("🔔 蹲点投喂任务触发")
                                        // 重新进入庄园，获取最新状态
                                        enterFarm()
                                        // 同步最新状态
                                        syncAnimalStatus(ownerFarmId)
                                        // 遣返
                                        if (sendBackAnimal?.value == true) {
                                            sendBackAnimal()
                                        }
                                        // 先召回自己的小鸡，避免因外出导致本轮雇佣/喂食不执行
                                        recallAnimal()
                                        // 雇佣小鸡
                                        if (hireAnimal?.value == true) {
                                            hireAnimal()
                                        }
                                        // 喂鸡
                                        handleAutoFeedAnimal(true)
                                        Log.farm("🔄 下一次蹲点任务已创建")
                                    } catch (e: Exception) {
                                        Log.printStackTrace(TAG,"蹲点投喂任务执行失败", e)
                                    }
                                },
                                execTime = nextFeedTime
                            )
                        )
                        Log.farm(UserMap.getCurrentMaskName() + "小鸡的蹲点投喂时间[" + TimeUtil.getCommonDate(nextFeedTime)+"]")
                    } else {
                        Log.farm("蹲点投喂🥣[倒计时为0，开始投喂]")
                        if (feedAnimal(ownerFarmId)) {
                            // 刷新状态
                            syncAnimalStatus(ownerFarmId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "创建蹲点任务失败: ${e.message}",e)
            }
        } else {
            // 小鸡在睡觉，跳过创建蹲点投喂任务
            // 注意：已存在的任务会在小鸡醒来时被新任务自动替换
            Log.farm("蹲点投喂🥣[小鸡正在睡觉，暂不安排投喂任务]")
        }

        // 6. 其他功能（换装、领取饲料）
        // 小鸡换装
        if (listOrnaments?.value == true && Status.canOrnamentToday()) {
            listOrnaments()
        }
    }
    private fun animalSleepNow() {
        try {
            var s = AntFarmRpcCall.queryLoveCabin(UserMap.currentUid)
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val sleepNotifyInfo = jo.getJSONObject("sleepNotifyInfo")
                if (sleepNotifyInfo.optBoolean("canSleep", false)) {
                    val groupId = jo.optString("groupId")
                    s = if (groupId.isNotEmpty()) {
                        AntFarmRpcCall.sleep(groupId)
                    } else {
                        AntFarmRpcCall.sleep()
                    }
                    jo = JSONObject(s)
                    if (ResChecker.checkRes(TAG, jo)) {
                        if (groupId.isNotEmpty()) {
                            Log.farm("家庭🏡小鸡睡觉🛌")
                        } else {
                            Log.farm("小鸡睡觉🛌")
                        }
                        Status.animalSleep()
                    }
                } else {
                    Log.farm("小鸡无需睡觉🛌")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "animalSleepNow err:",t)
        }
    }

    private fun animalWakeUpNow() {
        try {
            var s = AntFarmRpcCall.queryLoveCabin(UserMap.currentUid)
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val sleepNotifyInfo = jo.getJSONObject("sleepNotifyInfo")
                if (!sleepNotifyInfo.optBoolean("canSleep", true)) {
                    s = AntFarmRpcCall.wakeUp()
                    jo = JSONObject(s)
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.farm("小鸡起床 🛏")
                    }
                } else {
                    Log.farm("小鸡无需起床 🛏")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "animalWakeUpNow err:",t)
        }
    }

    /**
     * 同步小鸡状态通用方法
     *
     * @param farmId 庄园id
     */
    private fun syncAnimalStatus(
        farmId: String?,
        operTag: String?,
        operateType: String?
    ): JSONObject? {
        try {
            return JSONObject(AntFarmRpcCall.syncAnimalStatus(farmId, operTag, operateType))
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            return null
        }
    }

    internal fun syncAnimalStatus(farmId: String?) {
        try {
            val jo = syncAnimalStatus(farmId, "SYNC_RESUME", "QUERY_ALL")
            parseSyncAnimalStatusResponse(jo!!)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "syncAnimalStatus err:", t)
        }
    }

    internal fun refreshFarmStatus(reason: String) {
        if (ownerFarmId.isNullOrBlank()) {
            return
        }
        Log.farm("刷新庄园状态[$reason]")
        syncAnimalStatus(ownerFarmId)
    }

    private fun syncAnimalStatusAfterFeedAnimal(farmId: String?): JSONObject? {
        try {
            return syncAnimalStatus(
                farmId,
                "SYNC_AFTER_FEED_ANIMAL",
                "QUERY_EMOTION_INFO|QUERY_ORCHARD_RIGHTS"
            )
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
        return null
    }

    private fun syncAnimalStatusQueryFamilyAnimals(farmId: String?): JSONObject? {
        try {
            return syncAnimalStatus(farmId, "SYNC_RESUME_FAMILY", "QUERY_ALL|QUERY_FAMILY_ANIMAL")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
        return null
    }


    private fun syncAnimalStatusAtOtherFarm(userId: String?, friendUserId: String?) {
        try {
            val s = AntFarmRpcCall.enterFarm(userId, friendUserId)
            var jo = JSONObject(s)
            Log.farm("DEBUG$jo")
            jo = jo.getJSONObject("farmVO").getJSONObject("subFarmVO")
            val jaAnimals = jo.getJSONArray("animals")
            for (i in 0..<jaAnimals.length()) {
                val jaAnimaJson = jaAnimals.getJSONObject(i)
                if (jaAnimaJson.getString("masterFarmId") == ownerFarmId) { // 过滤出当前用户的小鸡
                    val animal = jaAnimals.getJSONObject(i)
                    ownerAnimal =
                        objectMapper.readValue(animal.toString(), Animal::class.java)
                    break
                }
            }
        } catch (j: JSONException) {
            Log.printStackTrace(TAG, "syncAnimalStatusAtOtherFarm err:", j)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "syncAnimalStatusAtOtherFarm err:", t)
        }
    }

    internal fun rewardFriend() {
        try {
            if (rewardList != null) {
                for (rewardFriend in rewardList) {
                    val s = AntFarmRpcCall.rewardFriend(
                        rewardFriend.consistencyKey, rewardFriend.friendId,
                        rewardProductNum, rewardFriend.time
                    )
                    val jo = JSONObject(s)
                    val memo = jo.getString("memo")
                    if (ResChecker.checkRes(TAG, jo)) {
                        val rewardCount = benevolenceScore - jo.getDouble("farmProduct")
                        benevolenceScore -= rewardCount
                        Log.farm(
                            String.format(
                                Locale.CHINA,
                                "打赏好友💰[%s]# 得%.2f颗爱心鸡蛋",
                                UserMap.getMaskName(rewardFriend.friendId),
                                rewardCount
                            )
                        )
                    } else {
                        Log.farm(memo)
                        Log.farm(s)
                    }
                }
                rewardList = null
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG,"rewardFriend err:", t)
        }
    }

    private fun recallAnimal(
        animalId: String?,
        currentFarmId: String?,
        masterFarmId: String?,
        user: String?
    ) {
        try {
            val s = AntFarmRpcCall.recallAnimal(animalId, currentFarmId, masterFarmId)
            val jo = JSONObject(s)
            val memo = jo.getString("memo")
            if (ResChecker.checkRes(TAG, jo)) {
                val foodHaveStolen = jo.getDouble("foodHaveStolen")
                Log.farm("召回小鸡📣，偷吃[" + user + "]#" + foodHaveStolen + "g")
                // 这里不需要加
                // add2FoodStock((int)foodHaveStolen);
            } else {
                Log.farm(memo)
                Log.farm(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "recallAnimal err:",t)
        }
    }

    internal fun sendBackAnimal() {
        if (animals == null) {
            return
        }
        try {
            for (animal in animals) {
                if (AnimalInteractStatus.STEALING.name == animal.animalInteractStatus && (SubAnimalType.GUEST.name != animal.subAnimalType) && (SubAnimalType.WORK.name != animal.subAnimalType)) {
                    // 赶鸡
                    val userId = AntFarmRpcCall.farmId2UserId(animal.masterFarmId)
                    if (FriendGuard.shouldSkipFriend(userId, TAG, "庄园遣返")) {
                        continue
                    }
                    var isSendBackAnimal = sendBackAnimalList?.contains(userId) == true
                    if (sendBackAnimalType?.value == SendBackAnimalType.BACK) {
                        isSendBackAnimal = !isSendBackAnimal
                    }
                    if (isSendBackAnimal) {
                        continue
                    }
                    val sendTypeInt = (sendBackAnimalWay?.value ?: SendBackAnimalWay.NORMAL)
                        .coerceIn(0, SendBackAnimalWay.nickNames.size - 1)
                    val user = UserMap.getMaskName(userId) ?: userId
                    val s = AntFarmRpcCall.sendBackAnimal(
                        SendBackAnimalWay.nickNames[sendTypeInt],
                        animal.animalId,
                        animal.currentFarmId,
                        animal.masterFarmId
                    )
                    val jo = JSONObject(s)
                    val memo = jo.getString("memo")
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.farm("${UserMap.getCurrentMaskName()} 驱赶小鸡🧶[$user]")
                    } else {
                        Log.farm(memo)
                        Log.farm(s)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "sendBackAnimal err:",t)
        }
    }

    internal fun receiveToolTaskReward() {
        try {
            var s = AntFarmRpcCall.listToolTaskDetails()
            var jo = JSONObject(s)
            var memo = jo.getString("memo")
            if (ResChecker.checkRes(TAG, jo)) {
                val jaList = jo.getJSONArray("list")
                for (i in 0..<jaList.length()) {
                    val joItem = jaList.getJSONObject(i)
                    if (joItem.has("taskStatus")
                        && TaskStatus.FINISHED.name == joItem.getString("taskStatus")
                    ) {
                        val bizInfo = JSONObject(joItem.getString("bizInfo"))
                        val awardType = bizInfo.getString("awardType")
                        val taskTitle = bizInfo.optString("taskTitle", joItem.optString("taskType", "未知道具任务"))
                        val toolType = try {
                            ToolType.valueOf(awardType)
                        } catch (_: IllegalArgumentException) {
                            Log.farm("发现暂未支持的庄园道具类型[$awardType]，跳过任务[$taskTitle]")
                            continue
                        }
                        var isFull = false
                        for (farmTool in farmTools) {
                            if (farmTool.toolType == toolType) {
                                if (farmTool.toolCount == farmTool.toolHoldLimit) {
                                    isFull = true
                                }
                                break
                            }
                        }
                        if (isFull) {
                            Log.farm("领取道具[" + toolType.nickName() + "]#已满，暂不领取")
                            continue
                        }
                        val awardCount = bizInfo.optInt("awardCount", 0)
                        val taskType = joItem.getString("taskType")
                        s = AntFarmRpcCall.receiveToolTaskReward(awardType, awardCount, taskType)
                        jo = JSONObject(s)
                        memo = jo.getString("memo")
                        if (ResChecker.checkRes(TAG, jo)) {
                            Log.farm("领取道具🎖️[" + taskTitle + "-" + toolType.nickName() + "]#" + awardCount + "张")
                        } else {
                            memo = memo.replace("道具", toolType.nickName().toString())
                            Log.farm(memo)
                            Log.farm(s)
                        }
                    }
                }
            } else {
                Log.farm(memo)
                Log.farm(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveToolTaskReward err:",t)
        }
    }

    internal fun harvestProduce(farmId: String?) {
        try {
            val s = AntFarmRpcCall.harvestProduce(farmId)
            val jo = JSONObject(s)
            val memo = jo.getString("memo")
            if (ResChecker.checkRes(TAG, jo)) {
                val harvest = jo.getDouble("harvestBenevolenceScore")
                harvestBenevolenceScore = jo.getDouble("finalBenevolenceScore")
                Log.farm("收取鸡蛋🥚[" + harvest + "颗]#剩余" + harvestBenevolenceScore + "颗")
            } else {
                Log.farm(memo)
                Log.farm(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "harvestProduce err:",t)
        }
    }

    /* 捐赠爱心鸡蛋 */
    internal fun handleDonation(): Boolean {
        try {
            val uid = UserMap.currentUid
            if (uid.isNullOrBlank()) {
                Log.farm("公益捐蛋跳过：当前用户ID为空")
                return false
            }
            val dailyLimit = maxDailyDonationCompetitionCount?.value ?: -1
            if (dailyLimit >= 0) {
                val currentDailyTotal = Status.getDailyDonationTotal(uid)
                if (currentDailyTotal >= dailyLimit) {
                    Log.farm("今日已捐蛋总数($currentDailyTotal)已达每日捐蛋上限($dailyLimit)，跳过普通每日捐蛋")
                    return false
                }
            }

            val amount = donationAmount?.value ?: 1
            if (harvestBenevolenceScore < amount) {
                Log.farm("可用爱心蛋不足，跳过普通每日捐蛋：当前${harvestBenevolenceScore}颗，需要${amount}颗")
                return false
            }

            val donatedActivityIds = linkedSetOf<String>()
            lastDonationActivityIds = emptySet()
            lastDonationNoMoreActivities = false

            val s = AntFarmRpcCall.listActivityInfo()
            val jo = JSONObject(s)
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.farm("查询公益捐蛋项目失败: ${formatDonationFailure(jo)}")
                return false
            }

            val activityInfos = jo.optJSONArray("activityInfos") ?: run {
                Log.farm("查询公益捐蛋项目失败：activityInfos 为空")
                return false
            }
            val mode = donationMode?.value ?: DonationMode.ONE_AVAILABLE_PROJECT
            var hasAvailableProject = false
            var hasDonationSuccess = false
            var donationFailed = false
            var stoppedForInsufficientEggs = false
            var stoppedForDailyLimit = false
            var hasUnconfirmedUndonatedProject = false
            var hasInvalidActivityInfo = false

            for (i in 0 until activityInfos.length()) {
                val activity = activityInfos.optJSONObject(i) ?: continue
                val activityId = activity.optString("activityId")
                if (activityId.isBlank()) {
                    hasInvalidActivityInfo = true
                    Log.farm("公益捐蛋项目缺少 activityId，跳过")
                    continue
                }
                if (!activity.has("donationTotal") || !activity.has("donationLimit")) {
                    hasInvalidActivityInfo = true
                    Log.farm("公益捐蛋项目[$activityId]缺少 donationTotal/donationLimit，跳过")
                    continue
                }

                val activityName = activity.optString("projectName", activityId)
                val donationTotal = activity.optDouble("donationTotal", 0.0)
                val donationLimit = activity.optDouble("donationLimit", 0.0)
                if (donationTotal >= donationLimit) {
                    continue
                }
                hasAvailableProject = true

                if (mode == DonationMode.ALL_UNDONATED_PROJECTS) {
                    when (isUndonatedByCurrentUser(activity, uid)) {
                        true -> Unit
                        false -> {
                            Log.farm("公益捐蛋活动❤️[$activityName]#当前账号已捐过，跳过")
                            continue
                        }

                        null -> {
                            hasUnconfirmedUndonatedProject = true
                            Log.farm("公益捐蛋活动❤️[$activityName]#无法确认当前账号是否未捐，跳过")
                            continue
                        }
                    }
                }

                if (dailyLimit >= 0) {
                    val remainingQuota = dailyLimit - Status.getDailyDonationTotal(uid)
                    if (remainingQuota < amount) {
                        stoppedForDailyLimit = true
                        if (remainingQuota <= 0) {
                            Log.farm("今日已捐蛋总数已达每日捐蛋上限($dailyLimit)，停止本轮普通每日捐蛋")
                        } else {
                            Log.farm("今日捐蛋剩余额度不足单次捐蛋量，停止本轮普通每日捐蛋：剩余${remainingQuota}颗，单次需要${amount}颗")
                        }
                        break
                    }
                }

                if (harvestBenevolenceScore < amount) {
                    stoppedForInsufficientEggs = true
                    Log.farm("可用爱心蛋不足，停止本轮普通每日捐蛋：当前${harvestBenevolenceScore}颗，需要${amount}颗")
                    break
                }

                val result = performDonationDetailed(activityId, activityName, amount)
                if (!result.success) {
                    donationFailed = true
                    break
                }

                hasDonationSuccess = true
                donatedActivityIds.add(activityId)
                Status.updateDailyDonationTotal(uid, result.actualAmount, incremental = true)

                if (mode == DonationMode.ONE_AVAILABLE_PROJECT) {
                    break
                }
            }

            lastDonationActivityIds = donatedActivityIds
            lastDonationNoMoreActivities = !hasAvailableProject && !hasInvalidActivityInfo
            if (lastDonationNoMoreActivities) {
                Log.farm("今日已无可捐赠的活动")
            }

            val shouldMarkDone = when (mode) {
                DonationMode.ONE_AVAILABLE_PROJECT -> hasDonationSuccess
                DonationMode.ALL_AVAILABLE_PROJECTS ->
                    !donationFailed && !stoppedForInsufficientEggs && !stoppedForDailyLimit && !hasInvalidActivityInfo &&
                        (hasDonationSuccess || !hasAvailableProject)

                DonationMode.ALL_UNDONATED_PROJECTS ->
                    !donationFailed && !stoppedForInsufficientEggs && !stoppedForDailyLimit &&
                        !hasUnconfirmedUndonatedProject &&
                        !hasInvalidActivityInfo

                else -> hasDonationSuccess
            }
            if (shouldMarkDone) {
                Status.setFlagToday(StatusFlags.FLAG_FARM_DAILY_DONATION_DONE_PREFIX + uid)
            }
            return hasDonationSuccess
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "donation err:", t)
        }
        return false
    }

    private data class DonationPerformResult(
        val success: Boolean,
        val actualAmount: Int = 0
    )

    private fun isUndonatedByCurrentUser(activity: JSONObject, uid: String): Boolean? {
        val activityRecords = activity.optJSONArray("activityRecords") ?: return null
        for (index in 0 until activityRecords.length()) {
            val record = activityRecords.optJSONObject(index) ?: return null
            val userInfo = record.optJSONObject("userInfo") ?: return null
            val recordUserId = userInfo.optString("userId")
            if (recordUserId.isBlank()) {
                return null
            }
            if (recordUserId == uid) {
                return false
            }
        }
        return true
    }

    private fun performDonationDetailed(
        activityId: String?,
        activityName: String?,
        count: Int,
        historyCount: Int = 0
    ): DonationPerformResult {
        try {
            val s = AntFarmRpcCall.donation(activityId, count)
            val donationResponse = JSONObject(s)
            if (ResChecker.checkRes(TAG, donationResponse)) {
                val donationDetails = donationResponse.optJSONObject("donation")
                val responseAmount = donationDetails?.optInt("donationAmount", count) ?: count
                val actualAmount = if (responseAmount > 0) responseAmount else count
                syncHarvestBenevolenceScoreAfterDonation(donationDetails, actualAmount)

                if (historyCount == 0) {
                    Log.farm("捐赠活动❤️[$activityName]#捐赠了${actualAmount}颗蛋，首次捐赠该项目")
                } else {
                    Log.farm("捐赠活动❤️[$activityName]#捐赠了${actualAmount}颗蛋，累计捐赠${historyCount + 1}次")
                }
                return DonationPerformResult(true, actualAmount)
            }
            Log.farm("捐赠失败: ${formatDonationFailure(donationResponse)}")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "performDonation err:", t)
        }
        return DonationPerformResult(false)
    }

    private fun syncHarvestBenevolenceScoreAfterDonation(donationDetails: JSONObject?, actualAmount: Int) {
        val localRemaining = (harvestBenevolenceScore - actualAmount).coerceAtLeast(0.0)
        if (donationDetails == null || !donationDetails.has("harvestBenevolenceScore")) {
            harvestBenevolenceScore = localRemaining
            return
        }

        val responseRemaining = donationDetails.optDouble("harvestBenevolenceScore", Double.NaN)
        harvestBenevolenceScore = if (!responseRemaining.isNaN() && responseRemaining >= 0.0) {
            responseRemaining
        } else {
            localRemaining
        }
    }

    private fun formatDonationFailure(jo: JSONObject): String {
        val resultDesc = jo.optString("resultDesc")
        val memo = jo.optString("memo")
        val resultCode = jo.optString("resultCode")
        return "resultDesc=${resultDesc.ifBlank { "<blank>" }}, " +
            "memo=${memo.ifBlank { "<blank>" }}, " +
            "resultCode=${resultCode.ifBlank { "<blank>" }}, response=$jo"
    }

    internal fun AntFarm.performDonation(
        activityId: String?,
        activityName: String?,
        count: Int = 1,
        historyCount: Int = 0
    ): Boolean {
        return performDonationDetailed(activityId, activityName, count, historyCount).success
    }

    @Suppress("SameParameterValue")
    private fun answerQuestion(activityId: String?) {
        try {
            val today = TimeUtil.getDateStr2()
            val tomorrow = TimeUtil.getDateStr2(1)
            val farmAnswerCache = DataStore.getOrCreate<MutableMap<String, String>>(FARM_ANSWER_CACHE_KEY) as MutableMap<String, String>
            cleanOldAnswers(farmAnswerCache, today)
            // 检查是否今天已经答过题
            if (Status.hasFlagToday(StatusFlags.FLAG_FARM_QUESTION_ANSWERED)) {
                if (!Status.hasFlagToday(StatusFlags.FLAG_FARM_QUESTION_CACHE)) {
                    val jo = JSONObject(DadaDailyRpcCall.home(activityId))
                    if (ResChecker.checkRes(TAG, "查询答题活动失败:", jo)) {
                        val operationConfigList = jo.getJSONArray("operationConfigList")
                        updateTomorrowAnswerCache(operationConfigList, tomorrow)
                        Status.setFlagToday(StatusFlags.FLAG_FARM_QUESTION_CACHE)
                    }
                }
                return
            }

            // 获取题目信息
            val jo = JSONObject(DadaDailyRpcCall.home(activityId))
            if (!ResChecker.checkRes(TAG, "获取答题题目失败:", jo)) return

            val question = jo.getJSONObject("question")
            val questionId = question.getLong("questionId")
            val labels = question.getJSONArray("label")
            val answerList = JsonUtil.jsonArrayToList(labels)
            val title = question.getString("title")

            var answer: String? = null
            var farmAnswerMatched = false
            val cacheKey = "$title|$today"

            // 答题来源顺序：目标端预告答案缓存 -> AnswerAI 已验证正确缓存 -> AI 请求。
            if (farmAnswerCache.containsKey(cacheKey)) {
                val cachedAnswer = farmAnswerCache[cacheKey]
                Log.farm("🎉 目标端答案缓存[$cachedAnswer] 🎯 题目：$cacheKey")

                // 1. 首先尝试精确匹配
                for (i in 0..<labels.length()) {
                    val option = labels.getString(i)
                    if (option == cachedAnswer) {
                        answer = option
                        farmAnswerMatched = true
                        break
                    }
                }

                // 2. 如果精确匹配失败，尝试模糊匹配
                if (!farmAnswerMatched && cachedAnswer != null) {
                    for (i in 0..<labels.length()) {
                        val option = labels.getString(i)
                        if (option.contains(cachedAnswer) || cachedAnswer.contains(option)) {
                            answer = option
                            farmAnswerMatched = true
                            Log.farm("⚠️ 目标端答案缓存模糊匹配成功：$cachedAnswer → $option")
                            break
                        }
                    }
                }
            }

            // 目标端缓存未命中后，AnswerAI 内部会先查已验证正确缓存，再请求 AI。
            if (!farmAnswerMatched) {
                Log.farm("目标端答案缓存未命中，进入AI答题链路：$title")
                answer = AnswerAI.getAnswer(title, answerList, LogChannel.FARM.loggerName)
                if (answer.isNullOrEmpty()) {
                    answer = labels.getString(0) // 默认选择第一个选项
                }
            }

            // 提交答案
            val joDailySubmit = JSONObject(DadaDailyRpcCall.submit(activityId, answer, questionId))
            Status.setFlagToday(StatusFlags.FLAG_FARM_QUESTION_ANSWERED)
            if (ResChecker.checkRes(TAG, "提交答题答案失败:", joDailySubmit)) {
                val extInfo = joDailySubmit.getJSONObject("extInfo")
                val correct = joDailySubmit.getBoolean("correct")
                if (correct) {
                    AnswerAI.rememberAnswer(title, answerList, answer, LogChannel.FARM.loggerName)
                } else {
                    AnswerAI.removeCachedAnswer(title, LogChannel.FARM.loggerName)
                    if (farmAnswerCache.remove(cacheKey) != null) {
                        DataStore.put(FARM_ANSWER_CACHE_KEY, farmAnswerCache)
                    }
                }
                Log.farm("饲料任务答题：" + (if (correct) "正确" else "错误") + "领取饲料［" + extInfo.getString("award") + "g］")
                val operationConfigList = joDailySubmit.getJSONArray("operationConfigList")
                updateTomorrowAnswerCache(operationConfigList, tomorrow)
                Status.setFlagToday(StatusFlags.FLAG_FARM_QUESTION_CACHE)
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "答题出错", e)
        }
    }

    /**
     * 更新明日答案缓存
     *
     * @param operationConfigList 操作配置列表
     * @param date                日期字符串，格式 "yyyy-MM-dd"
     */
    private fun updateTomorrowAnswerCache(operationConfigList: JSONArray, date: String?) {
        try {
            Log.farm("updateTomorrowAnswerCache 开始更新缓存")
            val farmAnswerCache = DataStore.getOrCreate<MutableMap<String, String>>(FARM_ANSWER_CACHE_KEY)
            for (j in 0..<operationConfigList.length()) {
                val operationConfig = operationConfigList.getJSONObject(j)
                val type = operationConfig.getString("type")
                if ("PREVIEW_QUESTION" == type) {
                    val previewTitle = operationConfig.getString("title") + "|" + date
                    val actionTitle = JSONArray(operationConfig.getString("actionTitle"))
                    for (k in 0..<actionTitle.length()) {
                        val joActionTitle = actionTitle.getJSONObject(k)
                        val isCorrect = joActionTitle.getBoolean("correct")
                        if (isCorrect) {
                            val nextAnswer = joActionTitle.getString("title")
                            farmAnswerCache[previewTitle] = nextAnswer // 缓存下一个问题的答案
                        }
                    }
                }
            }
            DataStore.put(FARM_ANSWER_CACHE_KEY, farmAnswerCache)
            Log.farm("updateTomorrowAnswerCache 缓存更新完毕")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "updateTomorrowAnswerCache 错误:", e)
        }
    }


    /**
     * 清理缓存超过7天的B答案
     */
    private fun cleanOldAnswers(farmAnswerCache: MutableMap<String, String>?, today: String?) {
        try {
            Log.farm("cleanOldAnswers 开始清理缓存")
            if (farmAnswerCache == null || farmAnswerCache.isEmpty()) return
            // 将今天日期转为数字格式：20250405
            val todayInt = convertDateToInt(today) // 如 "2025-04-05" → 20250405
            // 设置保留天数（例如7天）
            val daysToKeep = 7
            val cleanedMap: MutableMap<String?, String?> = HashMap()
            for (entry in farmAnswerCache.entries) {
                val key: String = entry.key
                if (key.contains("|")) {
                    val parts: Array<String?> = key.split("\\|".toRegex(), limit = 2).toTypedArray()
                    if (parts.size == 2) {
                        val dateStr = parts[1] //获取日期部分 20
                        val dateInt = convertDateToInt(dateStr)
                        if (dateInt == -1) continue
                        if (todayInt - dateInt <= daysToKeep) {
                            cleanedMap[entry.key] = entry.value //保存7天内的答案
                            Log.farm("保留 日期：" + todayInt + "缓存日期：" + dateInt + " 题目：" + parts[0])
                        }
                    }
                }
            }
            DataStore.put(FARM_ANSWER_CACHE_KEY, cleanedMap)
            Log.farm("cleanOldAnswers 清理缓存完毕")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "cleanOldAnswers error:", e)
        }
    }


    /**
     * 将日期字符串转为数字格式
     *
     * @param dateStr 日期字符串，格式 "yyyy-MM-dd"
     * @return 日期数字格式，如 "2025-04-05" → 20250405
     */
    private fun convertDateToInt(dateStr: String?): Int {
        Log.farm("convertDateToInt 开始转换日期：$dateStr")
        if (dateStr == null || dateStr.length != 10 || dateStr[4] != '-' || dateStr[7] != '-') {
            Log.error("日期格式错误：$dateStr")
            return -1 // 格式错误
        }
        try {
            val year = dateStr.take(4).toInt()
            val month = dateStr.substring(5, 7).toInt()
            val day = dateStr.substring(8, 10).toInt()
            if (month !in 1..12 || day < 1 || day > 31) {
                Log.error("日期无效：$dateStr")
                return -1 // 日期无效
            }
            return year * 10000 + month * 100 + day
        } catch (e: NumberFormatException) {
            Log.error(TAG, "日期转换失败：" + dateStr + e.message)
            return -1
        }
    }

    /**
     * 庄园任务，目前支持i
     * 视频，杂货铺，抽抽乐，家庭，618会场，芭芭农场，小鸡厨房
     * 添加组件，雇佣，会员签到，逛咸鱼，今日头条极速版，UC浏览器
     * 一起拿饲料，到店付款，线上支付，鲸探
     */
    private suspend fun doFarmTasks(): Status.TodayFlagState {
        try {
            TaskFlowEngine(FarmDailyTaskFlowAdapter(), roundSleepMs = 800L).run()
            syncAnimalStatus(ownerFarmId)
            return resolveFarmTaskFlagState()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "doFarmTasks 错误:", t)
            return Status.TodayFlagState.RETRY_LATER
        }
    }

    private inner class FarmDailyTaskFlowAdapter : TaskFlowAdapter {
        private val loggedSkipKeys = mutableSetOf<String>()
        private val handledCompleteKeys = mutableSetOf<String>()

        override val moduleName: String = farmTaskBlacklistModule
        override val flowName: String = "庄园饲料任务"

        override fun query(): JSONObject {
            val response = AntFarmRpcCall.listFarmTask()
            if (response.isEmpty()) {
                return JSONObject()
                    .put("success", false)
                    .put("resultDesc", "listFarmTask返回空")
            }
            return JSONObject(response)
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return ResChecker.checkRes(TAG, "查询庄园任务失败:", response)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val farmTaskList = response.optJSONArray("farmTaskList") ?: return emptyList()
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until farmTaskList.length()) {
                val task = farmTaskList.optJSONObject(i) ?: continue
                val bizKey = task.optString("bizKey").trim()
                val taskId = task.optString("taskId").trim()
                val title = task.optString("title", bizKey.ifBlank { taskId }).trim()
                    .ifBlank { bizKey.ifBlank { taskId.ifBlank { "未知任务" } } }
                val status = task.optString("taskStatus").trim()
                syncFarmAnswerTaskState(bizKey, status)

                val rightsTimes = task.optInt("rightsTimes", 0)
                val rightsTimesLimit = task.optInt("rightsTimesLimit", rightsTimes + 1)
                items.add(
                    TaskFlowItem(
                        id = bizKey.ifBlank { taskId },
                        title = title,
                        status = status,
                        type = bizKey,
                        sceneCode = task.optString("sceneCode"),
                        actionType = task.optString("actionType"),
                        blacklistKeys = listOf(bizKey, taskId, title).filter { it.isNotBlank() },
                        raw = task,
                        progress = "rights=$rightsTimes/$rightsTimesLimit award=${task.optInt("awardCount", 0)}",
                        current = rightsTimes,
                        limit = rightsTimesLimit
                    )
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            return when (item.status) {
                TaskStatus.TODO.name,
                "WAIT_COMPLETE" -> when {
                    item.type == "tab3_gyg" -> TaskFlowPhase.BUSINESS_ACTION
                    item.type.isBlank() -> TaskFlowPhase.UNKNOWN
                    else -> TaskFlowPhase.READY_TO_COMPLETE
                }

                TaskStatus.FINISHED.name,
                TaskStatus.RECEIVED.name,
                "COMPLETE",
                "HAS_RECEIVED",
                "DONE",
                "COMPLETED" -> TaskFlowPhase.TERMINAL

                else -> TaskFlowPhase.UNKNOWN
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            if (Thread.currentThread().isInterrupted) {
                return true
            }
            if (item.type == "tab3_gyg" && enableChouchoule?.value != true) {
                logFarmTaskSkipOnce(item, "抽抽乐未开启，跳过饲料任务收敛检查")
                return true
            }
            if (Status.hasFlagToday(StatusFlags.FLAG_FARM_TASK_LIMIT_PREFIX + item.type)) {
                logFarmTaskSkipOnce(item, "今日已达该任务上限，跳过")
                return true
            }
            if (mapPhase(item) == TaskFlowPhase.READY_TO_COMPLETE &&
                actionKey(item, TaskFlowAction.COMPLETE) in handledCompleteKeys
            ) {
                logFarmTaskSkipOnce(item, "本轮已推进，等待刷新后再处理")
                return true
            }
            return false
        }

        override fun isBlacklisted(item: TaskFlowItem): Boolean {
            val blacklisted = super<TaskFlowAdapter>.isBlacklisted(item)
            if (blacklisted) {
                logFarmTaskSkipOnce(item, "已在黑名单中，跳过处理")
            }
            return blacklisted
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            return when (item.type) {
                "VIDEO_TASK" -> handleVideoTask(item.type, item.title)
                "ANSWER" -> completeFarmAnswerTask(item.title)
                else -> handleGeneralTask(item.type, item.title, blacklistOnTerminalFailure = false)
            }
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            return "${action.logName}:${item.type.ifBlank { item.id }}:${item.progress.ifBlank { "NO_PROGRESS" }}"
        }

        override fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
            if (action == TaskFlowAction.COMPLETE) {
                handledCompleteKeys.add(actionKey(item, action))
            }
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision
        ) {
            if (action == TaskFlowAction.COMPLETE && decision == TaskFlowDecision.MARK_HANDLED) {
                handledCompleteKeys.add(actionKey(item, action))
            }
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.error(TAG, "庄园饲料任务查询失败 raw=$response")
        }

        override fun logInfo(message: String) {
            Log.farm(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun logFarmTaskSkipOnce(item: TaskFlowItem, reason: String) {
            val key = "${item.type.ifBlank { item.id }}:$reason"
            if (loggedSkipKeys.add(key)) {
                Log.farm("庄园饲料任务[${item.title}]$reason")
            }
        }
    }

    internal fun finalizeFarmTaskAfterMultiStage(source: String): Boolean {
        val finalState = resolveFarmTaskFlagState()
        Status.setFlagToday(StatusFlags.FLAG_FARM_TASK_FINISHED, finalState)
        if (finalState == Status.TodayFlagState.RETRY_LATER) {
            Log.farm("饲料任务在${source}后仍未收敛，保留后续重试机会")
            return true
        }
        Log.farm("饲料任务在${source}后已完成最终状态确认: $finalState")
        return false
    }

    internal suspend fun triggerFarmTaskIfNeeded(tc: TimeCounter): Boolean {
        val spec = farmTaskTrigger?.getTriggerSpec() ?: return false
        if (spec.disabled) {
            Log.farm("饲料任务触发已关闭，跳过")
            return false
        }

        val consumedIndex = getFarmTaskTriggerIndex()
        val decision = TimeTriggerEvaluator.evaluateNow(spec, consumedIndex = consumedIndex)
        if (!decision.allowNow) {
            when {
                decision.blockedNow && decision.nextTriggerAt != null -> {
                    Log.farm("饲料任务当前槽位命中禁止窗口，等待${TimeUtil.getCommonDate(decision.nextTriggerAt)}后再尝试")
                }
                decision.nextTriggerAt != null -> {
                    Log.farm("饲料任务未到触发时机，下一次可尝试时间=${TimeUtil.getCommonDate(decision.nextTriggerAt)}")
                }
                else -> {
                    Log.farm("饲料任务今日已无可用触发槽位，跳过")
                }
            }
            return false
        }

        advanceFarmTaskTriggerIndex(decision.matchedSlotIndex)
        val slotLabel = if (decision.matchedSlotIndex >= 0) {
            "槽位#${decision.matchedSlotIndex + 1}"
        } else {
            "当前窗口"
        }
        Log.farm("命中饲料任务$slotLabel，开始尝试补全饲料任务")

        val state = doFarmTasks()
        Status.setFlagToday(StatusFlags.FLAG_FARM_TASK_FINISHED, state)
        tc.countDebug("饲料任务")
        return state == Status.TodayFlagState.RETRY_LATER
    }

    private fun getFarmTaskTriggerIndex(): Int {
        return Status.getIntFlagToday(StatusFlags.FLAG_FARM_TASK_TRIGGER_INDEX) ?: 0
    }

    private fun advanceFarmTaskTriggerIndex(matchedSlotIndex: Int) {
        if (matchedSlotIndex < 0) {
            return
        }
        val nextIndex = matchedSlotIndex + 1
        val currentIndex = getFarmTaskTriggerIndex()
        if (nextIndex > currentIndex) {
            Status.setIntFlagToday(StatusFlags.FLAG_FARM_TASK_TRIGGER_INDEX, nextIndex)
        }
    }

    /**
     * 多阶段任务专项循环处理器。
     * 策略：使用公共任务流单次推进后刷新；领奖保留饲料容量和游戏改分预留 guard。
     */
    internal suspend fun handleMultiStageTasksLoop(isManual: Boolean = false) {
        if (!isManual && Status.hasFlagToday(StatusFlags.FLAG_FARM_MULTI_STAGE_TASK_FINISHED)) {
            return
        }
        try {
            triggerMultiStageSeedTask()
            Log.record(TAG, "${if (isManual) "手动" else "自动"}多阶段任务补全循环开始...")
            TaskFlowEngine(FarmMultiStageTaskFlowAdapter(isManual), roundSleepMs = 800L).run()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "handleMultiStageTasksLoop 异常:", t)
        }
    }

    private fun triggerMultiStageSeedTask() {
        syncAnimalStatus(ownerFarmId)
        val startStock = foodStock
        val response = AntFarmRpcCall.listFarmTask()
        if (response.isEmpty()) {
            return
        }
        JSONObject(response).optJSONArray("farmTaskList")?.let { taskList ->
            for (i in 0 until taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                val bizKey = task.optString("bizKey")
                if (bizKey == "tab3_gyg" && task.optString("taskStatus") == TaskStatus.TODO.name) {
                    val title = task.optString("title", "未知任务")
                    Log.farm("开始处理任务: $title ($bizKey)")
                    handleGeneralTask(bizKey, title)
                    break
                }
            }
        }
        syncAnimalStatus(ownerFarmId)
        val silentGained = foodStock - startStock
        if (silentGained > 0) {
            Log.farm("庄园任务处理完毕，静默获得饲料(直接领取了奖励): ${silentGained}g")
        }
    }

    private inner class FarmMultiStageTaskFlowAdapter(
        private val isManual: Boolean
    ) : TaskFlowAdapter {
        private val handledActionKeys = mutableSetOf<String>()
        private var gameFinished = false
        private var shouldReceiveAwards = false
        private var hasIncompleteMultiStage = false

        override val moduleName: String = farmTaskBlacklistModule
        override val flowName: String = "庄园多阶段任务"

        override fun query(): JSONObject {
            val response = AntFarmRpcCall.listFarmTask()
            if (response.isEmpty()) {
                return JSONObject()
                    .put("success", false)
                    .put("resultDesc", "listFarmTask返回空")
            }
            return JSONObject(response)
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return ResChecker.checkRes(TAG, "查询庄园多阶段任务失败:", response)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val farmTaskList = response.optJSONArray("farmTaskList") ?: return emptyList()
            gameFinished = Status.hasFlagToday(StatusFlags.FLAG_FARM_GAME_FINISHED)
            syncAnimalStatus(ownerFarmId)

            val items = mutableListOf<TaskFlowItem>()
            var totalAvailableAwards = 0
            var anyTaskFullyDone = false
            hasIncompleteMultiStage = false

            for (i in 0 until farmTaskList.length()) {
                val task = farmTaskList.optJSONObject(i) ?: continue
                val limit = task.optInt("rightsTimesLimit", 1)
                if (limit <= 1) {
                    continue
                }

                val bizKey = task.optString("bizKey").trim()
                val taskId = task.optString("taskId").trim()
                val title = task.optString("title", bizKey.ifBlank { taskId }).trim()
                    .ifBlank { bizKey.ifBlank { taskId.ifBlank { "未知任务" } } }
                val status = task.optString("taskStatus")
                val rightsTimes = task.optInt("rightsTimes", 0)
                val accumulatedAward = getMultiStageAccumulatedAward(task)
                val hasAward = status == TaskStatus.FINISHED.name || accumulatedAward > 0
                val isBlacklisted = TaskBlacklist.isTaskInBlacklist(farmTaskBlacklistModule, title) ||
                    TaskBlacklist.isTaskInBlacklist(farmTaskBlacklistModule, bizKey)
                val limitReached = Status.hasFlagToday(StatusFlags.FLAG_FARM_TASK_LIMIT_PREFIX + bizKey)

                if ((isBlacklisted || limitReached) && !hasAward) {
                    continue
                }

                if (!isBlacklisted && !limitReached && rightsTimes < limit) {
                    hasIncompleteMultiStage = true
                }
                if (hasAward) {
                    totalAvailableAwards += accumulatedAward
                    if (rightsTimes >= limit) {
                        anyTaskFullyDone = true
                    }
                }
                if (rightsTimes < limit || accumulatedAward > 0) {
                    val awardInfo = if (accumulatedAward > 0) ", 待领奖励: ${accumulatedAward}g" else ""
                    Log.record(TAG, "任务[$title] 进度: $rightsTimes/$limit$awardInfo")
                }

                items.add(
                    TaskFlowItem(
                        id = bizKey.ifBlank { taskId },
                        title = title,
                        status = status,
                        type = bizKey,
                        blacklistKeys = listOf(bizKey, taskId, title).filter { it.isNotBlank() },
                        raw = task,
                        progress = "rights=$rightsTimes/$limit award=$accumulatedAward",
                        current = rightsTimes,
                        limit = limit
                    )
                )
            }

            val foodSpace = foodStockLimit - foodStock
            shouldReceiveAwards = if (!gameFinished) {
                foodStock < 180
            } else {
                (foodSpace > 0 && totalAvailableAwards >= foodSpace) || anyTaskFullyDone
            }
            if (!hasIncompleteMultiStage && !isManual) {
                Status.setFlagToday(StatusFlags.FLAG_FARM_MULTI_STAGE_TASK_FINISHED)
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            val task = item.raw ?: return TaskFlowPhase.UNKNOWN
            val rightsTimes = task.optInt("rightsTimes", item.current ?: 0)
            val limit = task.optInt("rightsTimesLimit", item.limit ?: 1)
            val hasAward = item.status == TaskStatus.FINISHED.name || getMultiStageAccumulatedAward(task) > 0
            return when {
                hasAward && canReceiveMultiStageAward(task) -> TaskFlowPhase.REWARD_READY
                rightsTimes < limit -> TaskFlowPhase.READY_TO_COMPLETE
                hasAward -> TaskFlowPhase.BUSINESS_ACTION
                else -> TaskFlowPhase.TERMINAL
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            if (Thread.currentThread().isInterrupted) {
                return true
            }
            val phase = mapPhase(item)
            return when {
                phase == TaskFlowPhase.REWARD_READY &&
                    actionKey(item, TaskFlowAction.RECEIVE) in handledActionKeys -> true
                phase == TaskFlowPhase.READY_TO_COMPLETE &&
                    actionKey(item, TaskFlowAction.COMPLETE) in handledActionKeys -> true
                else -> false
            }
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            val task = item.raw ?: return missingMultiStageRawResult(item, "receive")
            if (!canReceiveMultiStageAward(task)) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                    message = "容量策略暂不领取多阶段奖励",
                    rpc = "AntFarmRpcCall.receiveFarmTaskAward",
                    detail = "taskId=${item.id} taskName=${item.title}"
                )
            }

            val taskId = task.optString("taskId")
            if (taskId.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.NON_RETRYABLE_INVALID,
                    message = "多阶段任务缺少taskId",
                    rpc = "AntFarmRpcCall.receiveFarmTaskAward",
                    detail = "taskName=${item.title}"
                )
            }
            val accumulatedAward = getMultiStageAccumulatedAward(task)
            val receiveRes = JSONObject(AntFarmRpcCall.receiveFarmTaskAward(taskId))
            return if (ResChecker.checkRes(TAG, receiveRes)) {
                add2FoodStock(accumulatedAward)
                Log.farm("领取多阶段奖励[${item.title}] 🍪${accumulatedAward}g (当前饲料: ${foodStock}g)")
                TaskFlowActionResult.success()
            } else {
                buildFarmTaskFailureResult(
                    receiveRes,
                    taskId,
                    item.title,
                    "receiveMultiStageAward",
                    "AntFarmRpcCall.receiveFarmTaskAward"
                )
            }
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            return handleGeneralTask(item.type, item.title, silent = true, blacklistOnTerminalFailure = false)
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            return "${action.logName}:${item.type.ifBlank { item.id }}:${item.progress.ifBlank { "NO_PROGRESS" }}"
        }

        override fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
            if (action == TaskFlowAction.RECEIVE || action == TaskFlowAction.COMPLETE) {
                handledActionKeys.add(actionKey(item, action))
            }
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision
        ) {
            if (decision == TaskFlowDecision.MARK_HANDLED) {
                handledActionKeys.add(actionKey(item, action))
            }
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            if (!isManual) {
                Status.setFlagToday(StatusFlags.FLAG_FARM_MULTI_STAGE_TASK_FINISHED)
            }
        }

        override fun logInfo(message: String) {
            Log.farm(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun canReceiveMultiStageAward(task: JSONObject): Boolean {
            if (!shouldReceiveAwards) {
                return false
            }
            if (foodStock >= foodStockLimit) {
                return false
            }
            if (!gameFinished && foodStock >= 180) {
                return false
            }
            return task.optString("taskStatus") == TaskStatus.FINISHED.name ||
                getMultiStageAccumulatedAward(task) > 0
        }

        private fun missingMultiStageRawResult(item: TaskFlowItem, action: String): TaskFlowActionResult {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "缺少多阶段任务原始数据",
                rpc = "FarmMultiStageTaskFlowAdapter.$action",
                detail = "taskId=${item.id} taskName=${item.title} status=${item.status}"
            )
        }
    }

    private fun getMultiStageAccumulatedAward(task: JSONObject): Int {
        val currentTotalAward = task.optInt("awardCount", 0)
        val alreadyReceived = task.optInt("alreadyReceiveStageAwardCount", 0)
        return (currentTotalAward - alreadyReceived).coerceAtLeast(0)
    }

    private fun resolveFarmTaskFlagState(): Status.TodayFlagState {
        return try {
            val verifyJo = JSONObject(AntFarmRpcCall.listFarmTask())
            if (!ResChecker.checkRes(TAG, verifyJo)) {
                return Status.TodayFlagState.RETRY_LATER
            }
            val verifyTaskList = verifyJo.optJSONArray("farmTaskList") ?: return Status.TodayFlagState.RETRY_LATER
            for (i in 0 until verifyTaskList.length()) {
                val task = verifyTaskList.optJSONObject(i) ?: continue
                val title = task.optString("title", "未知任务")
                val bizKey = task.optString("bizKey")
                val taskStatus = task.optString("taskStatus")

                if (bizKey == "tab3_gyg" && enableChouchoule?.value != true) {
                    Log.farm("抽抽乐任务[$title]已关闭，跳过饲料任务收敛检查")
                    continue
                }
                if (Status.hasFlagToday(StatusFlags.FLAG_FARM_TASK_LIMIT_PREFIX + bizKey)) {
                    continue
                }
                if (TaskBlacklist.isTaskInBlacklist(farmTaskBlacklistModule, title) ||
                    TaskBlacklist.isTaskInBlacklist(farmTaskBlacklistModule, bizKey)
                ) {
                    continue
                }
                if (taskStatus == TaskStatus.FINISHED.name || taskStatus == TaskStatus.RECEIVED.name) {
                    continue
                }

                Log.farm("庄园任务[$title] 当前状态=$taskStatus，保留后续重试机会")
                return Status.TodayFlagState.RETRY_LATER
            }
            Status.TodayFlagState.NO_MORE_ACTION_TODAY
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "resolveFarmTaskFlagState err:", t)
            Status.TodayFlagState.RETRY_LATER
        }
    }

    private fun syncFarmAnswerTaskState(bizKey: String, taskStatus: String) {
        if (bizKey != "ANSWER") {
            return
        }
        if (taskStatus != TaskStatus.FINISHED.name && taskStatus != TaskStatus.RECEIVED.name) {
            return
        }
        if (!Status.hasFlagToday(StatusFlags.FLAG_FARM_QUESTION_ANSWERED)) {
            Status.setFlagToday(StatusFlags.FLAG_FARM_QUESTION_ANSWERED)
        }
        if (!Status.hasFlagToday(StatusFlags.FLAG_FARM_QUESTION_CACHE)) {
            Log.farm("答题已完成，尝试预取明日答案...")
            answerQuestion("100")
        }
    }

    private fun completeFarmAnswerTask(title: String): TaskFlowActionResult {
        if (!Status.hasFlagToday(StatusFlags.FLAG_FARM_QUESTION_CACHE)) {
            answerQuestion("100")
        }
        return if (Status.hasFlagToday(StatusFlags.FLAG_FARM_QUESTION_ANSWERED)) {
            TaskFlowActionResult.success()
        } else {
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "答题任务未确认完成",
                rpc = "DadaDailyRpcCall.home/submit",
                detail = "taskName=$title"
            )
        }
    }

    // 抽取视频处理逻辑，避免嵌套过深
    private fun handleVideoTask(bizKey: String, title: String): TaskFlowActionResult {
        val res = AntFarmRpcCall.queryTabVideoUrl()
        if (res.isEmpty()) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.RETRYABLE_RPC,
                message = "queryTabVideoUrl返回空",
                rpc = "AntFarmRpcCall.queryTabVideoUrl",
                detail = "taskId=$bizKey taskName=$title",
                stopCurrentRound = true
            )
        }
        val jo = JSONObject(res)
        if (!ResChecker.checkRes(TAG, jo)) {
            return buildFarmTaskFailureResult(jo, bizKey, title, "queryVideo", "AntFarmRpcCall.queryTabVideoUrl")
        }

        val videoUrl = jo.optString("videoUrl")
        val contentIdStart = videoUrl.indexOf("&contentId=")
        val referStart = videoUrl.indexOf("&refer", startIndex = (contentIdStart + 1).coerceAtLeast(0))
        if (contentIdStart < 0 || referStart <= contentIdStart) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "解析视频ID失败",
                rpc = "AntFarmRpcCall.queryTabVideoUrl",
                raw = jo.toString(),
                detail = "taskId=$bizKey taskName=$title"
            )
        }

        val contentId = videoUrl.substring(contentIdStart + 11, referStart)
        val deliverJo = JSONObject(AntFarmRpcCall.videoDeliverModule(contentId))
        if (!ResChecker.checkRes(TAG, deliverJo)) {
            return buildFarmTaskFailureResult(deliverJo, bizKey, title, "videoDeliver", "AntFarmRpcCall.videoDeliverModule")
        }

        val triggerJo = JSONObject(AntFarmRpcCall.videoTrigger(contentId))
        if (!ResChecker.checkRes(TAG, triggerJo)) {
            return buildFarmTaskFailureResult(triggerJo, bizKey, title, "videoTrigger", "AntFarmRpcCall.videoTrigger")
        }

        Log.farm("庄园视频任务确认成功🧾[$title]")
        return TaskFlowActionResult.success()
    }

    private fun isFarmTaskQuotaReachedResponse(jo: JSONObject): Boolean {
        val resultCode = jo.optString("resultCode").ifBlank { jo.optString("code") }
        if (resultCode == "309") return true

        val message = jo.optString("memo")
            .ifBlank { jo.optString("resultDesc") }
            .ifBlank { jo.optString("desc") }
        return message.contains("任务数达到当日上限") ||
            message.contains("权益获取次数超过上限") ||
            message.contains("当日达到上限") ||
            message.contains("当日上限")
    }

    private fun extractFarmRpcErrorCode(jo: JSONObject): String {
        return jo.optString("resultCode")
            .ifBlank { jo.optString("errorCode") }
            .ifBlank { jo.optString("code") }
    }

    private fun extractFarmRpcMessage(jo: JSONObject): String {
        return jo.optString("memo")
            .ifBlank { jo.optString("resultDesc") }
            .ifBlank { jo.optString("desc") }
            .ifBlank { jo.optString("errorMsg") }
            .ifBlank { jo.optString("resultMsg") }
            .ifBlank { jo.toString() }
    }

    private fun classifyFarmRpcFailure(jo: JSONObject): TaskRpcFailureType {
        val code = extractFarmRpcErrorCode(jo)
        val message = extractFarmRpcMessage(jo)
        return when {
            containsAny(message, "已领取", "已经领取", "重复领取", "重复领奖", "重复完成", "已完成", "任务已完结", "任务已结束") ->
                TaskRpcFailureType.TERMINAL_DONE

            code == "331" ||
                isFarmTaskQuotaReachedResponse(jo) ||
                code == "CAMP_TRIGGER_ERROR" ||
                code.contains("LIMIT", ignoreCase = true) ||
                containsAny(message, "上限", "限制", "受限", "不可领取", "资格不足", "饲料槽已满", "兑完", "风控", "风险") ->
                TaskRpcFailureType.BUSINESS_LIMIT

            code == "400000040" ||
                containsAny(message, "不支持rpc调用", "不支持RPC完成") ->
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                containsAny(message, "参数错误", "任务ID非法", "模板不存在") ->
                TaskRpcFailureType.NON_RETRYABLE_INVALID

            code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK") ||
                containsAny(message, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试") ||
                isFarmMarkedRetryable(jo) ->
                TaskRpcFailureType.RETRYABLE_RPC

            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun isFarmMarkedRetryable(jo: JSONObject): Boolean {
        return listOf("retryable", "retriable", "canRetry").any { key ->
            jo.has(key) && jo.optBoolean(key, false)
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private fun buildFarmTaskFailureResult(
        jo: JSONObject,
        taskId: String,
        title: String,
        action: String,
        rpc: String
    ): TaskFlowActionResult {
        val failureType = classifyFarmRpcFailure(jo)
        return TaskFlowActionResult.failure(
            failureType = failureType,
            code = extractFarmRpcErrorCode(jo),
            message = extractFarmRpcMessage(jo),
            rpc = rpc,
            raw = jo.toString(),
            detail = "taskId=$taskId taskName=$title action=$action",
            stopCurrentRound = failureType == TaskRpcFailureType.RETRYABLE_RPC
        )
    }

    // 抽取通用任务处理逻辑
    private fun handleGeneralTask(
        bizKey: String,
        title: String,
        silent: Boolean = false,
        blacklistOnTerminalFailure: Boolean = true
    ): TaskFlowActionResult {
        val result = AntFarmRpcCall.doFarmTask(bizKey)
        if (result.isEmpty()) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.RETRYABLE_RPC,
                message = "doFarmTask返回空",
                rpc = "AntFarmRpcCall.doFarmTask",
                detail = "taskId=$bizKey taskName=$title",
                stopCurrentRound = true
            )
        }

        val jo = JSONObject(result)
        if (isFarmTaskQuotaReachedResponse(jo)) {
            Status.setFlagToday(StatusFlags.FLAG_FARM_TASK_LIMIT_PREFIX + bizKey)
            Log.farm("庄园任务[$title]已达上限")
            return buildFarmTaskFailureResult(jo, bizKey, title, "doFarmTask", "AntFarmRpcCall.doFarmTask")
        }

        if (ResChecker.checkRes(TAG, jo)) {
            if (!silent) Log.farm("庄园任务完成🧾[$title]")
            return TaskFlowActionResult.success()
        } else {
            val resultCode = extractFarmRpcErrorCode(jo)
            val message = extractFarmRpcMessage(jo)
            val detail = "module=$farmTaskBlacklistModule taskId=$bizKey taskName=$title " +
                "action=doFarmTask rpc=AntFarmRpcCall.doFarmTask code=${resultCode.ifBlank { "UNKNOWN" }} msg=$message raw=$jo"
            when (classifyFarmRpcFailure(jo)) {
                TaskRpcFailureType.TERMINAL_DONE -> {
                    if (blacklistOnTerminalFailure) Log.farm("庄园任务[$title] classification=TERMINAL_DONE decision=MARK_HANDLED $detail")
                }
                TaskRpcFailureType.BUSINESS_LIMIT -> {
                    if (blacklistOnTerminalFailure) Log.farm("庄园任务[$title] classification=BUSINESS_LIMIT decision=STOP_TODAY_OR_CURRENT_CHAIN $detail")
                }
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE -> {
                    if (blacklistOnTerminalFailure) {
                        blacklistClassifiedFarmTask(bizKey, title, resultCode)
                        Log.error(TAG, "庄园任务[$title] classification=UNSUPPORTED_NO_CLOSURE decision=BLACKLIST reason=未抓到稳定完成RPC $detail")
                    }
                }
                TaskRpcFailureType.NON_RETRYABLE_INVALID -> {
                    if (blacklistOnTerminalFailure) {
                        blacklistClassifiedFarmTask(bizKey, title, resultCode)
                        Log.error(TAG, "庄园任务[$title] classification=NON_RETRYABLE_INVALID decision=BLACKLIST $detail")
                    }
                }
                TaskRpcFailureType.RETRYABLE_RPC -> {
                    if (blacklistOnTerminalFailure) Log.error(TAG, "庄园任务[$title] classification=RETRYABLE_RPC decision=RETRY_LATER $detail")
                }
                TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW -> {
                    if (blacklistOnTerminalFailure) Log.error(TAG, "庄园任务[$title] classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY $detail")
                }
            }
            return buildFarmTaskFailureResult(jo, bizKey, title, "doFarmTask", "AntFarmRpcCall.doFarmTask")
        }
    }

    private fun blacklistClassifiedFarmTask(taskId: String, title: String, errorCode: String) {
        if (errorCode.isNotBlank()) {
            TaskBlacklist.autoAddToBlacklist(farmTaskBlacklistModule, taskId, title, errorCode)
        }
        TaskBlacklist.addToBlacklist(farmTaskBlacklistModule, taskId, title)
    }

    internal suspend fun receiveFarmAwards() {
        try {
            var doubleCheck: Boolean
            var isFeedFull = false // 添加饲料槽已满的标志
            do {
                doubleCheck = false
                val response = AntFarmRpcCall.listFarmTask()
                // 检查空响应
                if (response.isEmpty()) {
                    Log.farm("receiveFarmAwards: 收到空响应，跳过本次执行")
                    return
                }
                val jo = JSONObject(response)
                if (ResChecker.checkRes(TAG, "查询庄园任务失败:", jo)) {
                    val farmTaskList = jo.getJSONArray("farmTaskList")
                    val signList = jo.getJSONObject("signList")
                    val needFarmGame = recordFarmGame!!.value == true && !Status.hasFlagToday(StatusFlags.FLAG_FARM_GAME_FINISHED)

                    // 庄园签到逻辑
                    if (!Status.hasFlagToday(StatusFlags.FLAG_FARM_SIGNED)) {
                        syncAnimalStatus(ownerFarmId)
                        val timeReached = TimeUtil.isNowAfterOrCompareTimeStr("1400")
                        val foodSpace = foodStockLimit - foodStock
                        var awardCount = 180
                        try {
                            val jaFarmSignList = signList.optJSONArray("signList")
                            val currentSignKey = signList.optString("currentSignKey")
                            if (jaFarmSignList != null && !currentSignKey.isNullOrEmpty()) {
                                for (j in 0 until jaFarmSignList.length()) {
                                    val joSign = jaFarmSignList.getJSONObject(j)
                                    if (joSign.optString("signKey") == currentSignKey) {
                                        awardCount = joSign.optString("awardCount", "180").toIntOrNull() ?: 180
                                        break
                                    }
                                }
                            }
                        } catch (_: Exception) { }

                        val haveEnoughSpace = if (needFarmGame) foodSpace > gameRewardMax!!.value!! else foodSpace >= awardCount
                        val shouldSign = signRegardless!!.value == true || timeReached || haveEnoughSpace

                        if (shouldSign) {
                            if (farmSign(signList) && foodSpace < awardCount) {
                                Log.farm("签到实际获得饲料\uD83C\uDF6A: ${foodSpace}g (因饲料空间不足)")
                            }
                        }  else {
                            val msg = if (needFarmGame) "预留游戏改分的饲料空间，庄园暂不执行签到" else "饲料空间不足${awardCount}g，庄园暂不签到"
                            Log.farm("${msg}。14点后会强制签到；如已签到请忽略")
                        }
                    }

                    val unreceivedTasks = mutableListOf<JSONObject>()
                    for (i in 0..<farmTaskList.length()) {
                        // 如果饲料槽已满，跳过后续任务的领取
                        val task = farmTaskList.getJSONObject(i)
                        val taskStatus = task.getString("taskStatus")
                        if (TaskStatus.FINISHED.name == taskStatus) {
                            if ("ALLPURPOSE" == task.optString("awardType")) {
                                unreceivedTasks.add(task)
                            }
                        }
                    }

                    // 领取前先同步一次食槽状态，避免边界误差
                    syncAnimalStatus(ownerFarmId)
                    val currentFoodStockLeft = foodStockLimit - foodStock
                    val isAscending = currentFoodStockLeft < 90
                    if (isAscending) {
                        unreceivedTasks.sortBy { it.optInt("awardCount", 0) }
                    } else {
                        unreceivedTasks.sortByDescending { it.optInt("awardCount", 0) }
                    }

                    var lastSkippedAwardCount = -1
                    awardLoop@ for (i in unreceivedTasks.indices) {
                        val task = unreceivedTasks[i]
                        val awardCount = task.optInt("awardCount", 0)
                        val taskTitle = task.optString("title", "未知任务")
                        val taskId = task.optString("taskId")

                        val isNight = TimeUtil.isNowAfterOrCompareTimeStr("2000")
                        val foodStockLeft = foodStockLimit - foodStock
                        if (foodStock >= foodStockLimit) {
                            Log.farm("饲料[已满],暂不领取")
                            unreceiveTaskAward += (unreceivedTasks.size - i)
                            isFeedFull = true
                            break
                        }

                        if (!ignoreAcceLimit!!.value!! && (needFarmGame && foodStock >= (foodStockLimit - gameRewardMax!!.value!!))) {
                            Log.farm("当日游戏改分未完成，预留最多${gameRewardMax!!.value}饲料空间，现有饲料${foodStock}g，需再消耗${gameRewardMax!!.value!! -(foodStockLimit-foodStock)}g")
                            unreceiveTaskAward += (unreceivedTasks.size - i)
                            isFeedFull = true
                            break
                        }

                        if (awardCount > foodStockLeft) {
                            if (awardCount < 90) {
                                // A: 奖励较小(<90g)，允许溢出领取，确保不漏掉小额饲料
                                Log.farm("任务[$taskTitle]奖励 ${awardCount}g 虽超出上限，但奖励较小(<90g)，直接领取")
                            } else if (!isNight) {
                                // B: 20点前，大额奖励(>=90g)若超出会造成较大浪费
                                if (awardCount != lastSkippedAwardCount) {
                                    Log.farm("任务[$taskTitle]奖励 ${awardCount}g 会超出，跳过以寻找后续更小奖励...")
                                    lastSkippedAwardCount = awardCount
                                }
                                unreceiveTaskAward++
                                if (isAscending) {
                                    Log.farm("已按从小到大排序，后续奖励均不满足，停止寻找。")
                                    unreceiveTaskAward += (unreceivedTasks.size - i - 1)
                                    break
                                }
                                continue
                            } else {
                                // C: 20点后，为了保底，除非空间极小且后面有小任务，否则直接溢出领取
                                val hasSmallerTask = if (isAscending) false else unreceivedTasks.any {
                                    it.optInt("awardCount", 0) <= 90 && unreceivedTasks.indexOf(it) > i
                                }
                                if (awardCount > 90 && foodStockLeft <= 90 && hasSmallerTask) {
                                    if (awardCount != lastSkippedAwardCount) {
                                        Log.farm("20点后任务[$taskTitle]奖励 ${awardCount}g 会超出且有更小任务，尝试先领小的...")
                                        lastSkippedAwardCount = awardCount
                                    }
                                    unreceiveTaskAward++
                                    continue
                                }
                                Log.farm("20点后领取任务：${taskTitle} 的奖励 ${awardCount}g，溢出 ${awardCount - foodStockLeft}g")
                            }
                        }


                        val receiveTaskAwardjo = JSONObject(AntFarmRpcCall.receiveFarmTaskAward(taskId))
                        if (ResChecker.checkRes(TAG, "领取庄园任务奖励失败:", receiveTaskAwardjo)) {
                            add2FoodStock(awardCount)
                            Log.farm("收取庄园任务奖励[$taskTitle]🍪${awardCount}g (剩余容量: ${foodStockLimit - foodStock}g)")
                            val nextFoodStockLeft = foodStockLimit - foodStock
                            if (nextFoodStockLeft <= 0) {
                                Log.farm("领取饲料后饲料[已满]$foodStock g，停止后续领取")
                                unreceiveTaskAward += (unreceivedTasks.size - i - 1)
                                isFeedFull = true
                                break
                            }
                            if (!isAscending && nextFoodStockLeft < 90) {
                                Log.farm("剩余空间跌至 ${nextFoodStockLeft}g，切换为从小到大领取策略")
                                doubleCheck = true
                                break
                            }
                            doubleCheck = true
                            if (unreceiveTaskAward > 0) unreceiveTaskAward--
                        }
                        else {
                            // 捕获饲料槽已满（331），设置满槽标记并停止后续领取
                            val resultCode = extractFarmRpcErrorCode(receiveTaskAwardjo)
                            val memo = extractFarmRpcMessage(receiveTaskAwardjo)
                            val detail = "module=$farmTaskBlacklistModule taskId=$taskId taskName=$taskTitle " +
                                "action=receiveAward rpc=AntFarmRpcCall.receiveFarmTaskAward " +
                                "code=${resultCode.ifBlank { "UNKNOWN" }} msg=$memo raw=$receiveTaskAwardjo"
                            when (classifyFarmRpcFailure(receiveTaskAwardjo)) {
                                TaskRpcFailureType.TERMINAL_DONE -> {
                                    Log.farm("庄园任务[$taskTitle] classification=TERMINAL_DONE decision=MARK_HANDLED $detail")
                                    doubleCheck = true
                                    if (unreceiveTaskAward > 0) unreceiveTaskAward--
                                }
                                TaskRpcFailureType.BUSINESS_LIMIT -> {
                                    Log.farm("庄园任务[$taskTitle] classification=BUSINESS_LIMIT decision=STOP_TODAY_OR_CURRENT_CHAIN $detail")
                                    unreceiveTaskAward += (unreceivedTasks.size - i)
                                    isFeedFull = true
                                    break@awardLoop
                                }
                                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE -> {
                                    blacklistClassifiedFarmTask(taskId, taskTitle, resultCode)
                                    Log.error(TAG, "庄园任务[$taskTitle] classification=UNSUPPORTED_NO_CLOSURE decision=BLACKLIST reason=未抓到稳定领奖闭环 $detail")
                                    unreceiveTaskAward += (unreceivedTasks.size - i)
                                    break@awardLoop
                                }
                                TaskRpcFailureType.NON_RETRYABLE_INVALID -> {
                                    blacklistClassifiedFarmTask(taskId, taskTitle, resultCode)
                                    Log.error(TAG, "庄园任务[$taskTitle] classification=NON_RETRYABLE_INVALID decision=BLACKLIST $detail")
                                    unreceiveTaskAward += (unreceivedTasks.size - i)
                                    break@awardLoop
                                }
                                TaskRpcFailureType.RETRYABLE_RPC -> {
                                    Log.error(TAG, "庄园任务[$taskTitle] classification=RETRYABLE_RPC decision=RETRY_LATER $detail")
                                    unreceiveTaskAward += (unreceivedTasks.size - i)
                                    break@awardLoop
                                }
                                TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW -> {
                                    Log.error(TAG, "庄园任务[$taskTitle] classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY $detail")
                                    unreceiveTaskAward += (unreceivedTasks.size - i)
                                    break@awardLoop
                                }
                            }
                        }
                    }
                }
            } while (doubleCheck && !isFeedFull) // 如果饲料槽已满，不再进行双重检查
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
            Log.farm("receiveFarmAwards 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveFarmAwards 错误:", t)
        }
    }

    private fun farmSign(signList: JSONObject): Boolean {
        try {
            if (Status.hasFlagToday(StatusFlags.FLAG_FARM_SIGNED)) return false
            val jaFarmSignList = signList.getJSONArray("signList")?: return false
            val currentSignKey = signList.getString("currentSignKey")
            for (i in 0..<jaFarmSignList.length()) {
                val jo = jaFarmSignList.getJSONObject(i)
                val signKey = jo.getString("signKey")
                val signed = jo.getBoolean("signed")
                val awardCount = jo.getString("awardCount")
                val currentContinuousCount = jo.getInt("currentContinuousCount")
                if (currentSignKey == signKey) {
                    if (!signed) {
                        val signResponse = AntFarmRpcCall.sign()
                        if (ResChecker.checkRes(TAG, signResponse)) {
                            Log.farm("庄园签到📅获得饲料${awardCount}g,签到天数${currentContinuousCount}")
                            Status.setFlagToday(StatusFlags.FLAG_FARM_SIGNED)
                            return true
                        } else {
                            Log.farm("签到失败")
                            return false
                        }
                    } else {
                        Log.farm("今日已经签到了")
                        Status.setFlagToday(StatusFlags.FLAG_FARM_SIGNED)
                        return false
                    }
                }
            }
        } catch (e: JSONException) {
            Log.printStackTrace(TAG, "庄园签到 JSON解析错误:", e)
        }
        return false
    }

    /**
     * 喂鸡
     *
     * @param farmId 庄园ID
     * @return true: 喂鸡成功，false: 喂鸡失败
     */
    private fun feedAnimal(farmId: String?): Boolean {
        try {
            if (!ensureOwnerAnimalAtHome("投喂小鸡")) {
                return false
            }

            // 检查小鸡是否在睡觉，如果在睡觉则直接返回
            if (AnimalFeedStatus.SLEEPY.name == ownerAnimal.animalFeedStatus) {
                Log.farm("投喂小鸡🥣[小鸡正在睡觉中，跳过投喂]")
                return false
            }


            // 检查小鸡是否正在吃饭，如果在吃饭则直接返回
            // EATING: 小鸡正在进食状态，此时不能重复投喂，会返回"不要着急，还没吃完呢"错误
            if (AnimalFeedStatus.EATING.name == ownerAnimal.animalFeedStatus) {
                Log.farm("投喂小鸡🥣[小鸡正在吃饭中，跳过投喂]")
                return false
            }

            if (foodStock < 180) {
                Log.farm("喂鸡饲料不足，停止本次投喂尝试")
                return false // 明确返回 false
            } else {
                val jo = JSONObject(AntFarmRpcCall.feedAnimal(farmId))
                if (ResChecker.checkRes(TAG, jo)) {
                    // 安全获取foodStock字段，如果不存在则显示未知
                    val remainingFood = jo.optInt("foodStock", 0).coerceAtLeast(0)
                    Log.farm("${UserMap.getCurrentMaskName()}投喂小鸡🥣[180g]#剩余饲料${remainingFood}g")

                    val interval = BaseModel.checkInterval.getConfigValue()?.toIntOrNull() ?: 0
                    val timeSendBackValue = timeSendBack?.value ?: 0
                    var timeSendBackAnimal = 0
                    if (timeSendBackValue in 10..interval){
                        timeSendBackAnimal = timeSendBackValue
                    } else if(timeSendBackValue > interval){
                        Log.farm("设置个合理的喂食后赶鸡时间，建议 30 分钟")
                    }
                    if (sendBackAnimal?.value == true && timeSendBackAnimal > 0) {
                        try {
                            val taskId = "KC|$ownerFarmId"
                            val kcTime =
                                TimeUtil.getCommonDate(System.currentTimeMillis() + timeSendBackAnimal * 60 * 1000L)
                            val task = ChildModelTask(
                                id = taskId,
                                group = "KC",
                                suspendRunnable = {
                                    try {
                                        Log.farm("🔔 蹲点赶鸡任务触发")
                                        enterFarm()
                                        syncAnimalStatus(ownerFarmId)
                                        sendBackAnimal()
                                    } catch (e: Exception) {
                                        Log.error(TAG, "蹲点赶鸡任务执行失败: ${e.message}")
                                        Log.printStackTrace(TAG, e)
                                    }
                                },
                                execTime = System.currentTimeMillis() + timeSendBackAnimal * 60 * 1000L
                            )
                            addChildTask(task)
                            Log.farm(UserMap.getCurrentMaskName() + "${timeSendBackAnimal}分钟后${kcTime}蹲点赶小鸡")

                        } catch (e: Exception) {
                            Log.printStackTrace(TAG, "创建蹲点赶鸡失败: ${e.message}", e)
                        }
                    }
                    return true
                } else {
                    // 检查特定的错误码
                    val resultCode = jo.optString("resultCode", "")
                    val memo = jo.optString("memo", "")
                    if ("311" == resultCode) {
                        Log.farm("投喂小鸡🥣[$memo]")
                    } else {
                        Log.farm("投喂小鸡失败: $jo")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "feedAnimal err:", t)
        }
        return false
    }

    /**
     * 加载持有道具信息
     */
    private fun listFarmTool(): List<FarmTool>? {
        try {
            var jo = JSONObject(AntFarmRpcCall.listFarmTool())
            if (ResChecker.checkRes(TAG, jo)) {
                val jaToolList = jo.getJSONArray("toolList")
                val tempList = mutableListOf<FarmTool>()
                for (i in 0..<jaToolList.length()) {
                    jo = jaToolList.getJSONObject(i)
                    val tool = FarmTool()
                    tool.toolId = jo.optString("toolId", "")
                    tool.toolType = ToolType.valueOf(jo.getString("toolType"))
                    tool.toolCount = jo.getInt("toolCount")
                    tool.toolHoldLimit = jo.optInt("toolHoldLimit", 20)
                    tempList.add(tool)
                }
                farmTools = tempList.toTypedArray()
                return tempList
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "listFarmTool err:", t)
        }
        return null
    }

    private fun findFarmTool(toolType: ToolType, forceRefresh: Boolean = false): FarmTool? {
        if (forceRefresh || farmTools.isEmpty()) {
            listFarmTool()
        }
        return farmTools.find { it.toolType == toolType }
    }

    private fun getFarmToolCount(toolType: ToolType, forceRefresh: Boolean = false): Int {
        return findFarmTool(toolType, forceRefresh)?.toolCount ?: 0
    }

    private fun applyFarmToolUseResult(tool: FarmTool, response: JSONObject): Int {
        val fallbackCount = (tool.toolCount - 1).coerceAtLeast(0)
        val toolCountAfter = if (response.has("toolCount")) {
            response.optInt("toolCount", fallbackCount).coerceAtLeast(0)
        } else {
            fallbackCount
        }
        tool.toolCount = toolCountAfter

        val nextToolId = response.optString("lastToolId", "")
        if (nextToolId.isNotBlank()) {
            tool.toolId = nextToolId
        }
        return toolCountAfter
    }

    private enum class AccelerateToolLimitReason {
        FLAGGED,
        SYSTEM_LIMIT,
        USER_LIMIT
    }

    internal val accelerateToolCount: Int
        get() = farmTools.find { it.toolType == ToolType.ACCELERATETOOL }?.toolCount ?: 0

    private fun getAccelerateToolDailyLimitValue(): Int {
        return accelerateToolDailyLimit.value ?: accelerateToolDailyLimit.defaultValue ?: -1
    }

    private fun getAccelerateToolRemainingTimeValue(): Int {
        val configuredValue = remainingTime.value ?: remainingTime.defaultValue ?: 40
        return if (configuredValue < 0) 60 else configuredValue
    }

    internal fun getAccelerateToolUsageSummary(): String {
        val dailyLimitValue = getAccelerateToolDailyLimitValue()
        return if (dailyLimitValue >= 0) {
            "已使用${Status.INSTANCE.useAccelerateToolCount}张，设定上限${dailyLimitValue}张"
        } else {
            "已使用${Status.INSTANCE.useAccelerateToolCount}张，设定上限不限"
        }
    }

    private fun hasReachedConfiguredAccelerateToolLimit(): Boolean {
        val dailyLimitValue = getAccelerateToolDailyLimitValue()
        return dailyLimitValue >= 0 && Status.INSTANCE.useAccelerateToolCount >= dailyLimitValue
    }

    /**
     * 检测加速卡限制原因
     * @param syncFlag 是否同步持久化标记。
     */
    private fun detectAccelerateToolLimit(syncFlag: Boolean = false): AccelerateToolLimitReason? {
        if (Status.hasFlagToday(StatusFlags.FLAG_FARM_ACCELERATE_LIMIT)) {
            return AccelerateToolLimitReason.FLAGGED
        }

        if (!Status.canUseAccelerateTool()) {
            if (syncFlag) {
                Status.setFlagToday(StatusFlags.FLAG_FARM_ACCELERATE_LIMIT)
            }
            return AccelerateToolLimitReason.SYSTEM_LIMIT
        }

        if (hasReachedConfiguredAccelerateToolLimit()) {
            return AccelerateToolLimitReason.USER_LIMIT
        }

        return null
    }

    internal fun hasReachedAccelerateToolLimit(): Boolean {
        return detectAccelerateToolLimit(syncFlag = true) != null
    }

    private fun canAccelerateByRemainingTime(
        remainingFood: Double,
        thresholdMinutes: Int,
        foodConsumePerHour: Double
    ): Boolean {
        return when {
            thresholdMinutes < 0 -> false
            thresholdMinutes == 0 -> remainingFood > 0.0
            else -> remainingFood >= thresholdMinutes / 60.0 * foodConsumePerHour
        }
    }

    /**
     * 使用加速卡
     *
     * @return true: 使用成功，false: 使用失败
     */
    private suspend fun useAccelerateTool(): Boolean {
        val remainingTimeValue = getAccelerateToolRemainingTimeValue()
        // 1) 基础开关：命中统一停止标记、系统硬上限或用户软上限时直接返回
        when (detectAccelerateToolLimit(syncFlag = true)) {
            AccelerateToolLimitReason.SYSTEM_LIMIT -> {
                Log.farm("加速卡已达到系统使用上限(8次)，本轮跳过")
                return false
            }

            AccelerateToolLimitReason.USER_LIMIT -> {
                Log.farm("加速卡已达到设定的每日上限(${getAccelerateToolDailyLimitValue()}张)，本轮跳过")
                return false
            }

            AccelerateToolLimitReason.FLAGGED -> {
                Log.farm("今日加速卡已达设定/系统上限，本轮跳过")
                return false
            }

            null -> Unit
        }
        // 2) 同步最新状态，确保消耗速度、已吃量、食槽上限为最新
        syncAnimalStatus(ownerFarmId)
        listFarmTool()
        if (AnimalBuff.ACCELERATING.name == ownerAnimal.animalBuff) {
            Log.farm("加速卡效果在本轮开始前已生效，继续按剩余时间和上限判断是否追加使用")
        }

        // 当前小鸡剩余多长时间吃完饲料
        val currentCountdown = countdown?.toDouble() ?: 0.0
        if (currentCountdown <= 0) return false

        var totalFoodHaveEatten = 0.0
        var totalConsumeSpeed = 0.0
        /* 小鸡自己已经吃的食物参数是foodHaveStolen，而不是foodHaveEatten,这是非常关键的问题！
            实际情况是使用加速卡后所吃的饲料才算在foodHaveEatten里，foodHaveEatten即使不使用加速卡也会有个随机？的1以内的值，通常0.1左右，也就是非0
            startEatTime通常是投喂小鸡饲料的时间，但
            小鸡起床后startEatTime（含日期参数的时间）会重新变更为起床的时间，比如6：00起床，而喂食时间实际是昨晚的20：00,startEatTime=20：00,然后小鸡睡觉
            6：00起床，再获取startEatTime则为6：00
            因此剩余饲料量应该使用countdown来进行计算，这是准确的。
         */
        for (animal in animals!!) {
            totalFoodHaveEatten += animal.foodHaveStolen!!
            totalFoodHaveEatten += animal.foodHaveEatten!!
            totalConsumeSpeed += animal.consumeSpeed!!
        }
        // 自己的小鸡每小时消耗的饲料g数
        val  foodConsumePerHour = ownerAnimal.consumeSpeed!! * 60 * 60
        Log.farm("加速卡内部计算⏩[totalConsumeSpeed=$totalConsumeSpeed, totalFoodHaveEatten=$totalFoodHaveEatten, limit=$foodInTroughLimitCurrent]"
        )
        if (totalConsumeSpeed <= 0) return false
        var isUseAccelerateTool = false
        // 剩余饲料量应该根据当前吃饲料的总速度 * 剩余时间原计算逻辑是错误的，总速度就是自己的鸡+偷吃的鸡
        var remainingFood = currentCountdown * totalConsumeSpeed
        /* 加速卡逻辑应该是消耗自己小鸡1个小时的食物消耗量，这个量只取决于自己小鸡的食物消耗速度，大约38g左右；
            计算：foodConsumeSpeed（g/s） * 3600 (g)
            因此对于不足一个小时/指定剩余时间阈值的加速应该理解为剩余饲料大于等于这个指定时间的自己小鸡的食物消耗量，
            这种情况下即使有多只偷吃小鸡时也可以按照设置的剩余时间（remainingTime）正确判断是否继续使用加速卡。
            也就是说，即使有多只鸡在偷吃/工作，界面上显示还有remainingTime分钟吃完，那使用加速卡也可以加速掉
            剩余食物，然后再次投喂
         */
        /* 1. 定义一个用于记录退出原因的变量，是为了在exitReason == "CONDITION_NOT_MET"，在小鸡饲料剩余时间不足设置
            的remainingTime时进行日志打印，如设置的是40分钟，但是饲料剩余只有30分钟，那打印一下为什么没有把加速卡用完。
         */

        var exitReason = "CONDITION_NOT_MET"
        while (canAccelerateByRemainingTime(remainingFood, remainingTimeValue, foodConsumePerHour)) {
            when (detectAccelerateToolLimit(syncFlag = true)) {
                AccelerateToolLimitReason.SYSTEM_LIMIT -> {
                    Log.farm("加速卡内部⏩已达到系统使用上限(8次)，停止使用")
                    exitReason = "SYSTEM_LIMIT"
                    break
                }

                AccelerateToolLimitReason.USER_LIMIT -> {
                    Log.farm("加速卡内部⏩已达到设定的每日上限(${getAccelerateToolDailyLimitValue()}张)，停止使用"
                    )
                    exitReason = "USER_LIMIT"
                    break
                }

                AccelerateToolLimitReason.FLAGGED -> {
                    exitReason = "FLAGGED_LIMIT"
                    break
                }

                null -> Unit
            }
            if (accelerateToolCount <= 0) {
                exitReason = "NO_TOOL_LEFT"
                break
            }
            if (useFarmTool(ownerFarmId, ToolType.ACCELERATETOOL)) {
                // 用了一张加速卡，那剩余饲料减少自己小鸡1个小时的饲料消耗量，如前述38g左右
                remainingFood -= foodConsumePerHour
                isUseAccelerateTool = true
                Status.useAccelerateTool()
                val timeLeft = remainingFood / totalConsumeSpeed
                if (timeLeft >= 0.0){
                    Log.farm("使用了1张加速卡⏩ 预估剩余时间: ${(timeLeft/60).toInt()} 分钟")
                    // 打印用了几张加速卡
                    Log.farm("今日已使用${Status.INSTANCE.useAccelerateToolCount}张加速卡")
                    syncAnimalStatus(ownerFarmId)
                } else{
                    /* timeLeft也就是饲料剩余时间，小于0则说明饲料吃完了，直接进行投喂，这样可以在一次任务里完成加速
                        卡的使用。如果加速后吃完了，尝试补喂并刷新倒计时。等待8秒是为了防止计算结果的细微差异引起投喂失败
                     */
                    Log.farm("使用加速卡后小鸡饲料吃完，等待8秒后尝试喂鸡")
                    delay(8000)
                    // 等8秒刷新一下小鸡状态，确认是真的处于饥饿状态
                    syncAnimalStatus(ownerFarmId)
                    if (AnimalFeedStatus.HUNGRY.name == ownerAnimal.animalFeedStatus) {
                        if (feedAnimal(ownerFarmId)) {
                            // 这里似乎不用在刷新了
                            syncAnimalStatus(ownerFarmId)
                            // 投喂成功后剩余食物变成了180g
                            remainingFood = 180.0
                            Log.farm("加速卡后投喂小鸡成功！")
                            /* 使用加速卡后尝试领取饲料，因为连续加速会导致饲料缺口，单轮最多可能
                                能投喂两次，饲料减少360g,这显然会导致游戏改分的判断条件失败，这样就不能在一次软件运行
                                过程中完成所有任务，所以需要根据条件领取饲料。领取逻辑是，游戏改分飞行赛2次可以通常
                                得到180g饲料，我测试没有低于180g的时候，因此可以留180g不领，用飞行赛填补。打小鸡
                                没有饲料奖励
                             */
                            // 判断游戏改分还没完成。按照我的设计，其实这里不用判断，因为任务顺序就是先加速->游戏改分
                            if (!Status.hasFlagToday(StatusFlags.FLAG_FARM_GAME_FINISHED)) {
                                val gameRewardMaxValue = gameRewardMax?.value ?: gameRewardMax?.defaultValue ?: 0
                                if (foodStock < foodStockLimit - gameRewardMaxValue) {
                                    Log.farm("加速后已喂食，领取饲料奖励")
                                    receiveFarmAwards()
                                } else {
                                    Log.farm("今天游戏改分还没有完成，预留${gameRewardMaxValue}g的饲料剩余空间，目前饲料${foodStock}g，差${foodStockLimit - foodStock}g满饲料")
                                }
                            } else {
                                Log.farm("加速后已喂食，领取饲料奖励")
                                receiveFarmAwards()
                            }
                        } else {
                            remainingFood = (countdown?.toDouble() ?: 0.0) * totalConsumeSpeed
                            Log.farm("使用加速卡使饲料吃完，投喂小鸡失败！")
                        }
                    } else {
                        // 如果再次同步发现小鸡不是饥饿状态，重新开始计算remainingFood
                        remainingFood = (countdown?.toDouble() ?: 0.0) * totalConsumeSpeed
                    }
                }
            } else {
                if (Status.hasFlagToday(StatusFlags.FLAG_FARM_ACCELERATE_LIMIT)) {
                    Log.farm("加速卡内部⏩useFarmTool 返回失败，且已触发系统上限标记，停止使用")
                    exitReason = "SYSTEM_LIMIT"
                } else {
                    Log.farm("加速卡内部⏩useFarmTool 返回失败，终止循环")
                    exitReason = "TOOL_USE_FAILED"
                }
                break
            }
        }
        // 这里打印本轮停止继续使用加速卡的原因
        when(exitReason){
            "CONDITION_NOT_MET" -> {
                if (remainingTimeValue == 0) {
                    Log.farm("当前已无剩余时间可继续加速，将在下次喂食后再次使用加速卡")
                } else {
                    Log.farm("剩余可加速的时间小于设置的${remainingTimeValue}分钟，将在下次喂食后再次使用加速卡")
                }
            }
            "SYSTEM_LIMIT" -> Log.farm("今日加速卡已达到系统上限，本轮不再继续使用")
            "USER_LIMIT" -> Log.farm("今日加速卡已达到设定上限，本轮不再继续使用")
            "FLAGGED_LIMIT" -> Log.farm("今日加速卡已达设定/系统上限，本轮不再继续使用")
            "NO_TOOL_LEFT" -> Log.farm("背包中已无可用加速卡，本轮停止继续使用")
        }
        Log.farm("加速卡内部⏩最终 isUseAccelerateTool=$isUseAccelerateTool")
        return isUseAccelerateTool
    }

    private fun confirmFarmToolResultAfterInvalid(
        targetFarmId: String?,
        toolType: ToolType,
        toolCountBefore: Int,
        wasBigEaterActive: Boolean,
        wasAcceleratingActive: Boolean
    ): Boolean {
        try {
            Log.farm("道具🎭[${toolType.nickName()}]返回“道具使用无效”，开始刷新状态复核")
            syncAnimalStatus(targetFarmId)
            listFarmTool()
            val toolCountAfter = getFarmToolCount(toolType, forceRefresh = false)
            if (toolCountAfter in 0 until toolCountBefore) {
                Log.farm("道具🎭[${toolType.nickName()}]复核后确认已生效/已消耗（${toolCountBefore}→${toolCountAfter}），按成功处理"
                )
                return true
            }
            if (toolType == ToolType.ACCELERATETOOL &&
                wasAcceleratingActive &&
                AnimalBuff.ACCELERATING.name == ownerAnimal.animalBuff
            ) {
                invalidToolTypesThisRound.add(toolType)
                Log.farm("道具🎭[${toolType.nickName()}]加速效果仍在生效，本轮停止继续尝试")
                return false
            }
            if (toolType == ToolType.BIG_EATER_TOOL && !wasBigEaterActive && serverUseBigEaterTool) {
                Log.farm("道具🎭[${toolType.nickName()}]复核后确认已处于生效状态，按成功处理")
                return true
            }
            if (toolType == ToolType.ACCELERATETOOL && AnimalBuff.ACCELERATING.name == ownerAnimal.animalBuff) {
                invalidToolTypesThisRound.add(toolType)
                Log.farm("道具🎭[${toolType.nickName()}]当前已处于加速状态，本轮不再重复尝试")
                return false
            }
            invalidToolTypesThisRound.add(toolType)
            Log.farm("道具🎭[${toolType.nickName()}]复核后仍无效，已在本轮停止继续尝试")
        } catch (t: Throwable) {
            invalidToolTypesThisRound.add(toolType)
            Log.printStackTrace(TAG, "confirmFarmToolResultAfterInvalid err:", t)
        }
        return false
    }

    internal fun useFarmTool(targetFarmId: String?, toolType: ToolType): Boolean {
        try {
            if (invalidToolTypesThisRound.contains(toolType)) {
                Log.farm("道具🎭[${toolType.nickName()}]本轮已被判定为无效，跳过继续尝试")
                return false
            }
            val tool = findFarmTool(toolType, forceRefresh = toolType != ToolType.ACCELERATETOOL)
            if (tool == null) {
                Log.farm("背包中未找到道具🎭[${toolType.nickName()}]，跳过使用")
                return false
            }
            if (tool.toolCount <= 0) {
                Log.farm("背包中道具🎭[${toolType.nickName()}]数量为0，跳过使用")
                return false
            }
            if (toolType == ToolType.FENCETOOL && hasFence) {
                Log.farm("🛡️ 篱笆效果尚在（剩余${fenceCountDown / 60}分钟），跳过重复使用")
                return false
            }

            val toolCountBefore = tool.toolCount
            val wasBigEaterActive = serverUseBigEaterTool
            val wasAcceleratingActive =
                toolType == ToolType.ACCELERATETOOL && AnimalBuff.ACCELERATING.name == ownerAnimal.animalBuff
            var s = AntFarmRpcCall.useFarmTool(targetFarmId, tool.toolId.orEmpty(), toolType.name)
            var jo = JSONObject(s)
            val memo = jo.optString("memo")
            val resultCode = jo.optString("resultCode")
            if (resultCode == "348" || memo.contains("道具使用无效")) {
                return confirmFarmToolResultAfterInvalid(
                    targetFarmId,
                    toolType,
                    toolCountBefore,
                    wasBigEaterActive,
                    wasAcceleratingActive
                )
            }
            if (ResChecker.checkRes(TAG, jo)) {
                val hasNextToolId = jo.optString("lastToolId", "").isNotBlank()
                val remainingToolCount = applyFarmToolUseResult(tool, jo)
                Log.farm("使用了道具🎭[" + toolType.nickName() + "]#剩余" + remainingToolCount + "张")
                if (toolType == ToolType.FENCETOOL) {
                    hasFence = true
                    fenceCountDown = 86400
                }
                if (toolType != ToolType.ACCELERATETOOL || !hasNextToolId) {
                    listFarmTool()
                }
                return true
            } else {
                // 针对加速卡：当日达到上限(resultCode=3D16)后，设置当日标记，避免后续重复尝试
                if (toolType == ToolType.ACCELERATETOOL && resultCode == "3D16") {
                    Status.setFlagToday(StatusFlags.FLAG_FARM_ACCELERATE_LIMIT)
                    Log.farm("加速卡触发系统上限(resultCode=3D16)，已记录为当日限制")
                }
                Log.farm(memo.ifBlank { "使用道具🎭[${toolType.nickName()}]失败" })
                Log.farm(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "useFarmTool err:",t)
        }
        return false
    }

    internal suspend fun feedFriend() {
        val pendingInvalidUserIds = linkedSetOf<String>()
        var lastInsufficientFriendFeedStock: Int? = null
        try {
            suspend fun ensureFriendFeedStock(user: String? = null): Boolean {
                if (foodStock >= 180) {
                    lastInsufficientFriendFeedStock = null
                    return true
                }

                if (receiveFarmTaskAward?.value == true && lastInsufficientFriendFeedStock != foodStock) {
                    Log.farm("帮喂前饲料不足180g，尝试领取饲料奖励")
                    val previousFoodStock = foodStock
                    receiveFarmAwards()
                    if (foodStock > previousFoodStock) {
                        lastInsufficientFriendFeedStock = null
                    }
                }

                if (foodStock >= 180) {
                    lastInsufficientFriendFeedStock = null
                    return true
                }

                lastInsufficientFriendFeedStock = foodStock
                if (user.isNullOrBlank()) {
                    Log.farm("😞当前饲料不足180g，停止本轮帮喂")
                } else {
                    Log.farm("😞喂鸡[$user]饲料不足，停止本轮帮喂")
                }
                return false
            }

            val feedFriendAnimalMap = feedFriendAnimalList?.resolvedCountMap() ?: emptyMap()
            val useFamilyFeedForMembers =
                family?.value == true && familyOptions?.value?.contains("feedFamilyAnimal") == true
            val feedFriendEntries = if (useFamilyFeedForMembers) {
                feedFriendAnimalMap.entries.toList()
            } else {
                feedFriendAnimalMap.entries
                    .toList()
                    .sortedByDescending { AntFarmFamily.isFamilyMember(it.key) }
            }
            for (entry in feedFriendEntries) {
                val userId = entry.key.trim()
                val maxDailyCount = entry.value
                if (userId.isBlank() || maxDailyCount <= 0) {
                    continue
                }

                // 自己不应进入实际执行结果；这里仅跳过本轮，不再运行时修改持久配置。
                if (userId == UserMap.currentUid) {
                    Log.farm("检测到“帮喂小鸡 | 好友列表”包含自己，已跳过")
                    continue
                }

                // 家庭成员优先走家庭接口，普通帮喂仅处理非家庭好友
                if (useFamilyFeedForMembers && AntFarmFamily.isFamilyMember(userId)) {
                    continue
                }

                if (!Status.canFeedFriendToday(userId, maxDailyCount)) continue
                if (!ensureFriendFeedStock()) {
                    return
                }
                val jo = enterFriendFarmIfAvailable(userId, "帮好友喂鸡", pendingInvalidUserIds)
                if (jo != null) {
                    val subFarmVOjo = jo.getJSONObject("farmVO").getJSONObject("subFarmVO")
                    val friendFarmId = subFarmVOjo.getString("farmId")
                    val jaAnimals = subFarmVOjo.getJSONArray("animals")
                    for (j in 0..<jaAnimals.length()) {
                        val animalsjo = jaAnimals.getJSONObject(j)

                        val masterFarmId = animalsjo.getString("masterFarmId")
                        if (masterFarmId == friendFarmId) { //遍历到的鸡 如果在自己的庄园
                            if (animalsjo.optBoolean("littleChick", false)) {
                                Log.farm("跳过帮喂好友🥣[${UserMap.getMaskName(userId)}]：好友的小鸡太小，暂不能投喂")
                                break
                            }
                            val animalStatusVO = animalsjo.getJSONObject("animalStatusVO")
                            val animalInteractStatus =
                                animalStatusVO.getString("animalInteractStatus") //动物互动状态
                            val animalFeedStatus =
                                animalStatusVO.getString("animalFeedStatus") //动物饲料状态
                            if (AnimalInteractStatus.HOME.name == animalInteractStatus && AnimalFeedStatus.HUNGRY.name == animalFeedStatus) { //状态是饥饿 并且在庄园
                                val user = UserMap.getMaskName(userId) //喂 给我喂
                                if (!ensureFriendFeedStock(user)) {
                                    return
                                }
                                if (Status.hasFlagToday(StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT)) {
                                    return
                                }
                                val feedFriendAnimaljo =
                                    JSONObject(AntFarmRpcCall.feedFriendAnimal(friendFarmId))
                                val resultCode = feedFriendAnimaljo.optString("resultCode", "")
                                val memo = feedFriendAnimaljo.optString("memo", "")
                                if ("388" == resultCode || memo.contains("小鸡太小")) {
                                    Log.farm("跳过帮喂好友🥣[$user]：好友的小鸡太小，暂不能投喂")
                                    continue
                                }
                                if (ResChecker.checkRes(TAG, feedFriendAnimaljo)) {
                                    foodStock = feedFriendAnimaljo.getInt("foodStock")
                                    lastInsufficientFriendFeedStock = null
                                    Log.farm("帮喂好友🥣[" + user + "]的小鸡[180g]#剩余" + foodStock + "g")
                                    Status.feedFriendToday(userId)
                                } else {
                                    if ("391" == resultCode || memo.contains("今日帮喂次数已达上限")) {
                                        Status.setFlagToday(StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT)
                                        Log.farm("😞喂[$user]的鸡失败：今日帮喂次数已达上限，已记录为当日限制")
                                        return
                                    }
                                    Log.error(
                                        TAG,
                                        "😞喂[$user]的鸡失败$feedFriendAnimaljo"
                                    )
                                    continue
                                }
                            }
                            break
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("feedFriend 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "feedFriendAnimal err:", t)
        } finally {
            flushInvalidFriendSelections(pendingInvalidUserIds, "帮好友喂鸡")
        }
    }


    internal fun notifyFriend() {
        if (foodStock >= foodStockLimit) return
        try {
            var hasNext = false
            var pageStartSum = 0
            var s: String?
            var jo: JSONObject
            do {
                s = AntFarmRpcCall.rankingList(pageStartSum)
                // 检查空响应
                if (s.isNullOrEmpty()) {
                    Log.farm("notifyFriend.rankingList: 收到空响应，终止通知")
                    break // 跳出do-while循环
                }
                jo = JSONObject(s)
                var memo = jo.getString("memo")
                if (ResChecker.checkRes(TAG, jo)) {
                    hasNext = jo.getBoolean("hasNext")
                    val jaRankingList = jo.getJSONArray("rankingList")
                    if (jaRankingList.length() == 0) {
                        Log.farm("notifyFriend.rankingList: 好友排行返回空页，终止通知")
                        break
                    }
                    pageStartSum += jaRankingList.length()
                    for (i in 0..<jaRankingList.length()) {
                        jo = jaRankingList.getJSONObject(i)
                        val userId = jo.getString("userId")
                        val userName = UserMap.getMaskName(userId)
                        var isNotifyFriend = notifyFriendList?.contains(userId) == true
                        if (notifyFriendType?.value == NotifyFriendType.DONT_NOTIFY) {
                            isNotifyFriend = !isNotifyFriend
                        }
                        if (!isNotifyFriend || userId == UserMap.currentUid) {
                            continue
                        }
                        val starve =
                            jo.has("actionType") && "starve_action" == jo.getString("actionType")
                        if (jo.getBoolean("stealingAnimal") && !starve) {
                            val friendFarmJo = enterFriendFarmIfAvailable(userId, "通知赶鸡")
                            if (friendFarmJo == null) {
                                continue // 跳过当前好友，处理下一个
                            }
                            jo = friendFarmJo
                            memo = jo.getString("memo")
                            if (ResChecker.checkRes(TAG, jo)) {
                                jo = jo.getJSONObject("farmVO").getJSONObject("subFarmVO")
                                val friendFarmId = jo.getString("farmId")
                                val jaAnimals = jo.getJSONArray("animals")
                                var notified = notifyFriend?.value == true
                                for (j in 0..<jaAnimals.length()) {
                                    jo = jaAnimals.getJSONObject(j)
                                    val animalId = jo.getString("animalId")
                                    val masterFarmId = jo.getString("masterFarmId")
                                    if (masterFarmId != friendFarmId && masterFarmId != ownerFarmId) {
                                        if (notified) continue
                                        jo = jo.getJSONObject("animalStatusVO")
                                        notified =
                                            notifyFriend(jo, friendFarmId, animalId, userName)
                                    }
                                }
                            } else {
                                Log.farm(memo)
                                Log.farm(s)
                            }
                        }
                    }
                } else {
                    Log.farm(memo)
                    Log.farm(s)
                }
            } while (hasNext)
            Log.farm("饲料剩余[" + foodStock + "g]")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "notifyFriend err:",t)
        }
    }

    private fun notifyFriend(
        joAnimalStatusVO: JSONObject,
        friendFarmId: String?,
        animalId: String?,
        user: String?
    ): Boolean {
        try {
            if (AnimalInteractStatus.STEALING.name == joAnimalStatusVO.getString("animalInteractStatus") && AnimalFeedStatus.EATING.name == joAnimalStatusVO.getString(
                    "animalFeedStatus"
                )
            ) {
                val jo = JSONObject(AntFarmRpcCall.notifyFriend(animalId, friendFarmId))
                if (ResChecker.checkRes(TAG, jo)) {
                    val rewardCount = jo.getDouble("rewardCount")
                    if (jo.getBoolean("refreshFoodStock")) foodStock =
                        jo.getDouble("finalFoodStock").toInt()
                    else add2FoodStock(rewardCount.toInt())
                    Log.farm("通知好友📧[" + user + "]被偷吃#奖励" + rewardCount + "g")
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "notifyFriend err:", t)
        }
        return false
    }

    /**
     * 解析同步响应状态
     *
     * @param jo 同步响应状态
     */
    private fun parseSyncAnimalStatusResponse(jo: JSONObject) {
        try {
            if (!jo.has("subFarmVO")) {
                return
            }
            if (jo.has("emotionInfo")) { //小鸡心情
                finalScore = jo.getJSONObject("emotionInfo").getDouble("finalScore")
            }
            val subFarmVO = jo.getJSONObject("subFarmVO")
            // 解析服务端返回的“是否已使用加饭卡”状态
            serverUseBigEaterTool = subFarmVO.optBoolean("useBigEaterTool", false)
            if (subFarmVO.has("foodStock")) {
                foodStock = subFarmVO.getInt("foodStock")
            }
            // 同步当前食槽上限（子字段 foodInTroughLimit 优先，其次 foodStockLimit）
            foodInTroughLimitCurrent = when {
                subFarmVO.has("foodInTroughLimit") -> subFarmVO.getInt("foodInTroughLimit")
                subFarmVO.has("foodStockLimit") -> subFarmVO.getInt("foodStockLimit")
                jo.has("foodStockLimit") -> jo.getInt("foodStockLimit")
                else -> 180
            }
            // 同步当前仓库上限，防止后续判断出现上限为0的情况（提取失败则默认 1800）
            foodStockLimit = if (subFarmVO.has("foodStockLimit")) {
                subFarmVO.getInt("foodStockLimit")
            } else if (jo.has("foodStockLimit")) {
                // enterFarm 的 farmVO 层也可能携带该字段
                jo.getInt("foodStockLimit")
            } else {
                1800
            }
            if (subFarmVO.has("manureVO")) { //粪肥 鸡屎
                val manurePotList =
                    subFarmVO.getJSONObject("manureVO").getJSONArray("manurePotList")
                for (i in 0..<manurePotList.length()) {
                    if (manurePotCollectionBlockedThisRound) {
                        break
                    }
                    val manurePot = manurePotList.getJSONObject(i)
                    // 兼容：manurePotNum 既可能是整数(直接为数量)，也可能是 0~1 的比例值
                    val manurePotNumRaw = manurePot.optDouble("manurePotNum", 0.0)
                    val manurePotLimit = manurePot.optDouble("manurePotLimit", 0.0)
                    val manurePotNum = when {
                        manurePotNumRaw <= 0.0 -> 0.0
                        manurePotNumRaw <= 1.0 && manurePotLimit > 0.0 -> manurePotNumRaw * manurePotLimit
                        else -> manurePotNumRaw
                    }

                    if (manurePotNum >= 3.0) {
                        val manurePotNO = manurePot.optString("manurePotNO")
                        if (manurePotNO.isBlank()) {
                            continue
                        }
                        val joManurePot =
                            JSONObject(AntFarmRpcCall.collectManurePot(manurePotNO))
                        if (ResChecker.checkRes(TAG, joManurePot)) {
                            val collectManurePotNum = joManurePot.optInt("collectManurePotNum", 0)
                            Log.farm("打扫鸡屎🧹[" + collectManurePotNum + "g]" + (i + 1) + "次")
                        } else {
                            val resultCode = joManurePot.optString("resultCode")
                            val memo = joManurePot.optString("memo")
                            if (resultCode == "G03" || memo.contains("肥料太少啦，等一会再收吧")) {
                                manurePotCollectionBlockedThisRound = true
                                Log.farm("打扫鸡屎🧹失败：肥料太少啦，等一会再收吧；本轮不再继续尝试")
                                break
                            }
                            Log.farm("打扫鸡屎失败: 第" + (i + 1) + "次" + joManurePot)
                        }
                    } else if (manurePotNum > 0.0) {
                        Log.farm(String.format(Locale.US, "打扫鸡屎🧹池[%d]当前%.2fg，未达到>1g门槛，跳过", i + 1, manurePotNum)
                        )
                    }
                }
            }


            ownerFarmId = subFarmVO.getString("farmId")
            //倒计时
            countdown = subFarmVO.getLong("countdown")
            val farmProduce = subFarmVO.getJSONObject("farmProduce") //产物 -🥚
            benevolenceScore = farmProduce.getDouble("benevolenceScore") //慈善评分

            if (subFarmVO.has("rewardList")) {
                val jaRewardList = subFarmVO.getJSONArray("rewardList")
                if (jaRewardList.length() > 0) {
                    val tempList = mutableListOf<RewardFriend>()
                    for (i in 0..<jaRewardList.length()) {
                        val joRewardList = jaRewardList.getJSONObject(i)
                        val reward = RewardFriend()
                        reward.consistencyKey = joRewardList.getString("consistencyKey")
                        reward.friendId = joRewardList.getString("friendId")
                        reward.time = joRewardList.getString("time")
                        tempList.add(reward)
                    }
                    rewardList = tempList.toTypedArray()
                }
            }

            if (jo.has("buffInfoVO")) {
                val buffInfo = jo.getJSONObject("buffInfoVO")
                val buffType = buffInfo.optString("buffType")
                if (buffType == "FENCE") {
                    hasFence = buffInfo.optBoolean("hasBuffEffect", false)
                    fenceCountDown = buffInfo.optInt("buffCountDown", 0)
                    if (hasFence) {
                        Log.farm("🛡️ 篱笆生效中，剩余时间: ${fenceCountDown / 3600}小时${(fenceCountDown % 3600) / 60}分")
                    }
                }
            } else {
                hasFence = false
                fenceCountDown = 0
            }

            val jaAnimals = subFarmVO.getJSONArray("animals") //小鸡们
            val animalList: MutableList<Animal> = ArrayList()
            for (i in 0..<jaAnimals.length()) {
                val animalJson = jaAnimals.getJSONObject(i)
                val animal: Animal =
                    objectMapper.readValue(animalJson.toString(), Animal::class.java)
                animalList.add(animal)
                if (animal.masterFarmId == ownerFarmId) {
                    ownerAnimal = animal
                }
                //                Log.farm("当前动物：" + animal.toString());
            }
            animals = animalList.toTypedArray()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "parseSyncAnimalStatusResponse err:",t)
        }
    }

    private fun add2FoodStock(i: Int) {
        foodStock += i
        if (foodStock > foodStockLimit) {
            foodStock = foodStockLimit
        }
        if (foodStock < 0) {
            foodStock = 0
        }
    }


    /**
     * 收集每日食材
     */
    internal fun collectDailyFoodMaterial() {
        try {
            val userId = UserMap.currentUid
            var jo = JSONObject(AntFarmRpcCall.enterKitchen(userId))
            if (ResChecker.checkRes(TAG, jo)) {
                val canCollectDailyFoodMaterial = jo.optBoolean("canCollectDailyFoodMaterial", false)
                val dailyFoodMaterialAmount = jo.optInt("dailyFoodMaterialAmount", 0)
                val garbageAmount = listOf(
                    jo.optInt("garbageAmount", -1),
                    jo.optInt("kitchenGarbageAmount", -1)
                ).firstOrNull { it >= 0 } ?: 0
                if (jo.has("orchardFoodMaterialStatus")) {
                    val orchardFoodMaterialStatus = jo.getJSONObject("orchardFoodMaterialStatus")
                    if (shouldCollectOrchardFoodMaterial(orchardFoodMaterialStatus)) {
                        jo = JSONObject(AntFarmRpcCall.farmFoodMaterialCollect())
                        if (ResChecker.checkRes(TAG, jo)) {
                            val collectAmount = jo.optInt("foodMaterialAddCount", jo.optInt("receiveFoodMaterialCount", 0))
                            Log.farm("小鸡厨房👨🏻‍🍳[领取农场食材]#" + collectAmount + "g")
                        }
                    }
                }
                if (canCollectDailyFoodMaterial && dailyFoodMaterialAmount > 0) {
                    jo =
                        JSONObject(AntFarmRpcCall.collectDailyFoodMaterial(dailyFoodMaterialAmount))
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.farm("小鸡厨房👨🏻‍🍳[领取今日食材]#" + dailyFoodMaterialAmount + "g")
                    }
                }
                if (garbageAmount > 0) {
                    jo = JSONObject(AntFarmRpcCall.collectKitchenGarbage())
                    if (ResChecker.checkRes(TAG, jo)) {
                        val receivedGarbageAmount = listOf(
                            jo.optInt("recievedKitchenGarbageAmount", -1),
                            jo.optInt("receivedKitchenGarbageAmount", -1),
                            jo.optInt("collectKitchenGarbageAmount", -1)
                        ).firstOrNull { it >= 0 } ?: garbageAmount
                        Log.farm("小鸡厨房👨🏻‍🍳[领取肥料]#" + receivedGarbageAmount + "g")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "收集每日食材", t)
        }
    }

    private fun shouldCollectOrchardFoodMaterial(status: JSONObject?): Boolean {
        if (status == null) {
            return false
        }
        if (!status.optBoolean("orchardExist", true)) {
            return false
        }
        if (status.optBoolean("canCollect", false)) {
            return true
        }
        val collectableAmount = listOf(
            status.optInt("collectableFoodMaterialAmount", -1),
            status.optInt("canCollectFoodMaterialAmount", -1),
            status.optInt("pendingCollectFoodMaterialAmount", -1),
            status.optInt("foodMaterialAmount", -1)
        ).firstOrNull { it > 0 } ?: 0
        if (collectableAmount > 0) {
            return true
        }
        val foodStatus = status.optString("foodStatus").trim().uppercase(Locale.ROOT)
        if (foodStatus.isBlank()) {
            return false
        }
        if (foodStatus in setOf("RECIVIED", "RECEIVED", "DONE", "COLLECTED", "EMPTY", "NONE")) {
            return false
        }
        return foodStatus in setOf("FINISHED", "TODO", "WAITING_RECEIVE", "UNRECEIVED", "CAN_COLLECT", "AVAILABLE")
    }

    /**
     * 领取爱心食材店食材
     */
    internal fun collectDailyLimitedFoodMaterial() {
        try {
            var jo = JSONObject(AntFarmRpcCall.queryFoodMaterialPack())
            if (ResChecker.checkRes(TAG, jo)) {
                val canCollectDailyLimitedFoodMaterial =
                    jo.getBoolean("canCollectDailyLimitedFoodMaterial")
                if (canCollectDailyLimitedFoodMaterial) {
                    val dailyLimitedFoodMaterialAmount = jo.getInt("dailyLimitedFoodMaterialAmount")
                    jo = JSONObject(
                        AntFarmRpcCall.collectDailyLimitedFoodMaterial(
                            dailyLimitedFoodMaterialAmount
                        )
                    )
                    val resultCode = jo.optString("resultCode")
                    val memo = jo.optString("memo")
                    if (resultCode == "U15" || memo.contains("食材槽剩余空间不足")) {
                        Log.farm("小鸡厨房👨🏻‍🍳[爱心食材店食材槽空间不足，跳过领取]")
                        return
                    }
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.farm("小鸡厨房👨🏻‍🍳[领取爱心食材店食材]#" + dailyLimitedFoodMaterialAmount + "g")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "领取爱心食材店食材", t)
        }
    }

    internal suspend fun cook() {
        try {
            val userId = UserMap.currentUid
            var jo = JSONObject(AntFarmRpcCall.enterKitchen(userId))
            Log.farm("cook userid :$userId")
            if (ResChecker.checkRes(TAG, jo)) {
                val cookTimesAllowed = jo.getInt("cookTimesAllowed")
                if (cookTimesAllowed > 0) {
                    for (i in 0..<cookTimesAllowed) {
                        jo = JSONObject(AntFarmRpcCall.cook(userId, "VILLA"))
                        if (ResChecker.checkRes(TAG, jo)) {
                            val cuisineVO = jo.getJSONObject("cuisineVO")
                            Log.farm("小鸡厨房👨🏻‍🍳[" + cuisineVO.getString("name") + "]制作成功")
                        } else {
                            Log.farm("小鸡厨房制作$jo")
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("cook 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "cook err:",t)
        }
    }

    /**
     * 使用特殊美食 - 批量模式（支持连吃10个）
     * @param cuisineList 待使用的美食列表
     * @param maxUsage 本次运行总计使用的美食数量。-1 为尝试吃完传入列表中的指定数量。
     */
    internal fun useSpecialFood(
        cuisineList: JSONArray,
        maxUsage: Int = -1,
        usageCountFlag: String = StatusFlags.FLAG_FARM_SPECIAL_FOOD_DAILY_COUNT,
        usageLimitFlag: String = StatusFlags.FLAG_FARM_SPECIAL_FOOD_LIMIT,
        usageDailyLimit: Int = useSpecialFoodCount?.value ?: -1,
        usageLabel: String = "特殊食品"
    ): Int {
        var usedCount = 0
        try {
            val foodList = mutableListOf<JSONObject>()
            var totalInventory = 0 // 统计所有美食库存总和
            var totalToEat = 0     // 本次任务待消耗的总量

            for (i in 0 until cuisineList.length()) {
                val item = cuisineList.getJSONObject(i)

                val stock = if (item.has("stock")) item.getInt("stock") else item.optInt("count", 0)
                totalInventory += stock

                val count = item.optInt("count", 0)
                if (count > 0) {
                    foodList.add(item)
                    totalToEat += count
                }
            }

            Log.farm("美食处理：统计到美食库共有美食 $totalInventory 个")

            // 2. 确定本次实际消耗量
            var remainingToEat = if (maxUsage == -1) totalToEat else min(maxUsage, totalToEat)
            if (remainingToEat <= 0) return 0

            Log.farm("美食处理：待消耗总量 $remainingToEat")

            while (remainingToEat > 0 && foodList.isNotEmpty()) {
                val batchTarget = min(remainingToEat, 10) // 每次最多吃10个
                val currentBatchArray = JSONArray()
                val usedNames = StringBuilder()
                var currentBatchCount = 0

                // 2. 策略判断：优先查找是否有单种食物满足本次 Batch 数量
                val singleFood = foodList.find { it.optInt("count", 0) >= batchTarget }

                if (singleFood != null) {
                    // 情况 A: 单种食物充足
                    val countToUse = batchTarget
                    val snack = JSONObject()
                    snack.put("cookbookId", singleFood.getString("cookbookId"))
                    snack.put("cuisineId", singleFood.getString("cuisineId"))
                    snack.put("count", countToUse)
                    snack.put("useCuisine", true)
                    currentBatchArray.put(snack)

                    usedNames.append(singleFood.getString("name")).append("x").append(countToUse)
                    currentBatchCount = countToUse

                    // 更新状态
                    val newCount = singleFood.getInt("count") - countToUse
                    if (newCount <= 0) foodList.remove(singleFood) else singleFood.put("count", newCount)
                    remainingToEat -= countToUse
                } else {
                    // 情况 B: 单种不足，进行多种混搭凑够 batchTarget
                    var currentBatchSum = 0
                    val iterator = foodList.iterator()
                    while (iterator.hasNext() && currentBatchSum < batchTarget) {
                        val food = iterator.next()
                        val canTake = min(batchTarget - currentBatchSum, food.getInt("count"))

                        val snack = JSONObject()
                        snack.put("cookbookId", food.getString("cookbookId"))
                        snack.put("cuisineId", food.getString("cuisineId"))
                        snack.put("count", canTake)
                        snack.put("useCuisine", true)
                        currentBatchArray.put(snack)

                        if (usedNames.isNotEmpty()) usedNames.append(" + ")
                        usedNames.append(food.getString("name")).append("x").append(canTake)

                        currentBatchSum += canTake
                        val left = food.getInt("count") - canTake
                        if (left <= 0) iterator.remove() else food.put("count", left)
                    }
                    currentBatchCount = currentBatchSum
                    remainingToEat -= currentBatchSum
                }

                // 3. 发送网络请求
                if (currentBatchArray.length() > 0) {
                    val res = AntFarmRpcCall.useFarmFood(currentBatchArray)
                    val joRes = JSONObject(res)
                    if (ResChecker.checkRes(TAG, joRes)) {
                        val delta = joRes.optJSONObject("foodEffect")?.optDouble("deltaProduce", 0.0) ?: 0.0
                        val formattedDelta = "%.2f".format(java.util.Locale.US, delta)
                        Log.farm("批量使用美食🍱[$usedNames]#加速${formattedDelta}颗爱心鸡蛋")
                        usedCount += currentBatchCount
                    } else {
                        Log.farm("美食使用失败，停止后续操作: ${joRes.optString("memo")}")
                        break
                    }
                    CoroutineUtils.sleepCompat(RandomUtil.nextInt(1000, 2000).toLong())
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "useSpecialFood 批量模式 err:", t)
        }
        if (usedCount > 0) {
            val usedToday = Status.getIntFlagToday(usageCountFlag) ?: 0
            val newUsedToday = usedToday + usedCount
            Status.setIntFlagToday(usageCountFlag, newUsedToday)

            if (usageDailyLimit > 0 && newUsedToday >= usageDailyLimit) {
                Status.setFlagToday(usageLimitFlag)
            }
            Log.farm("${usageLabel}今日已累计使用${newUsedToday}个")
        }
        return usedCount
    }

    private fun drawLotteryPlus(lotteryPlusInfo: JSONObject) {
        try {
            if (!lotteryPlusInfo.has("userSevenDaysGiftsItem")) return
            val itemId = lotteryPlusInfo.getString("itemId")
            var userSevenDaysGiftsItem = lotteryPlusInfo.getJSONObject("userSevenDaysGiftsItem")
            val userEverydayGiftItems = userSevenDaysGiftsItem.getJSONArray("userEverydayGiftItems")
            for (i in 0..<userEverydayGiftItems.length()) {
                userSevenDaysGiftsItem = userEverydayGiftItems.getJSONObject(i)
                if (userSevenDaysGiftsItem.getString("itemId") == itemId) {
                    if (!userSevenDaysGiftsItem.getBoolean("received")) {
                        val singleDesc = userSevenDaysGiftsItem.getString("singleDesc")
                        val awardCount = userSevenDaysGiftsItem.getInt("awardCount")
                        if (singleDesc.contains("饲料") && awardCount + foodStock > foodStockLimit) {
                            Log.farm("暂停领取[$awardCount]g饲料，上限为[$foodStockLimit]g"
                            )
                            break
                        }
                        userSevenDaysGiftsItem = JSONObject(AntFarmRpcCall.drawLotteryPlus())
                        if ("SUCCESS" == userSevenDaysGiftsItem.getString("memo")) {
                            Log.farm("惊喜礼包🎁[$singleDesc*$awardCount]")
                        }
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "drawLotteryPlus err:",t)
        }
    }

    /**
     * 送麦子
     */
    internal suspend fun visit() {
        val pendingInvalidUserIds = linkedSetOf<String>()
        try {
            val map = visitFriendList?.resolvedCountMap() ?: emptyMap()
            if (map.isEmpty()) return
            val currentUid = UserMap.currentUid
            for (entry in map.entries.toList()) {
                val userId = entry.key.trim()
                val count = entry.value
                // 跳过自己和非法数量
                if (userId.isBlank() || userId == currentUid || count <= 0) continue
                // 限制最大访问次数
                val visitCount = min(count, 3)
                // 如果今天还可以访问
                if (Status.canVisitFriendToday(userId, visitCount)) {
                    val remaining = visitFriend(userId, visitCount, pendingInvalidUserIds)
                    if (remaining > 0) {
                        Status.visitFriendToday(userId, remaining)
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("visit 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "visit err:",t)
        } finally {
            flushInvalidFriendSelections(pendingInvalidUserIds, "送麦子")
        }
    }

    private fun enterFriendFarmIfAvailable(
        userId: String?,
        sceneName: String,
        pendingInvalidUserIds: MutableSet<String>? = null
    ): JSONObject? {
        val safeUserId = FriendGuard.normalizeUserId(userId) ?: return null
        if (FriendGuard.shouldSkipFriend(safeUserId, TAG, sceneName)) {
            return null
        }
        val jo = JSONObject(AntFarmRpcCall.enterFarm(safeUserId, safeUserId))
        val memo = jo.optString("memo")
        if (jo.optString("resultCode") == "304" || memo.contains("查询庄园不存在")) {
            FriendCapabilityRecorder.record(
                safeUserId,
                "FARM",
                FriendCapabilityState.NOT_OPEN,
                "AntFarm.enterFarm",
                memo.ifBlank { "查询庄园不存在" }
            )
            Log.farm("$sceneName 跳过[${UserMap.getMaskName(safeUserId) ?: safeUserId}]：对方未开通蚂蚁庄园")
            return null
        }
        if (pendingInvalidUserIds != null && queueInvalidFriendSelection(safeUserId, jo, sceneName, pendingInvalidUserIds)) {
            return null
        }
        if (ResChecker.checkRes(TAG, jo)) {
            FriendCapabilityRecorder.record(safeUserId, "FARM", FriendCapabilityState.OPEN, "AntFarm.enterFarm")
            return jo
        }
        Log.error(TAG, "$sceneName 进入好友庄园失败[$safeUserId]> $jo")
        return null
    }


    private suspend fun visitFriend(
        userId: String?,
        count: Int,
        pendingInvalidUserIds: MutableSet<String>
    ): Int {
        var visitedTimes = 0
        try {
            var jo = enterFriendFarmIfAvailable(userId, "送麦子", pendingInvalidUserIds)
            if (jo != null) {
                val farmVO = jo.getJSONObject("farmVO")
                foodStock = farmVO.getInt("foodStock")
                val subFarmVO = farmVO.getJSONObject("subFarmVO")
                if (subFarmVO.optBoolean("visitedToday", true)) return 3
                val farmId = subFarmVO.getString("farmId")
                for (i in 0..<count) {
                    if (foodStock < 10) break
                    jo = JSONObject(AntFarmRpcCall.visitFriend(farmId))
                    if (ResChecker.checkRes(TAG, jo)) {
                        foodStock = jo.getInt("foodStock")
                        Log.farm("赠送麦子🌾[" + UserMap.getMaskName(userId) + "]#" + jo.getInt("giveFoodNum") + "g")
                        visitedTimes++
                        if (jo.optBoolean("isReachLimit")) {
                            Log.farm("今日给[" + UserMap.getMaskName(userId) + "]送麦子已达上限"
                            )
                            visitedTimes = 3
                            break
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("visitFriend 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "visitFriend err:",t)
        }
        return visitedTimes
    }

    private fun queueInvalidFriendSelection(
        userId: String?,
        response: JSONObject?,
        sceneName: String,
        pendingInvalidUserIds: MutableSet<String>
    ): Boolean {
        if (userId.isNullOrEmpty() || response == null || userId == UserMap.currentUid) {
            return false
        }
        val resultCode = response.optString("resultCode")
        val memo = response.optString("memo")
        if (resultCode != "302" && !memo.contains("非好友")) {
            return false
        }
        if (pendingInvalidUserIds.add(userId)) {
            FriendRepository.markRemoved(UserMap.currentUid, userId)
            Log.farm("$sceneName 检测到[$userId]已非好友，已标记为失效好友")
        }
        return true
    }

    private fun flushInvalidFriendSelections(invalidUserIds: Set<String>, sceneName: String) {
        if (invalidUserIds.isEmpty()) {
            return
        }
        Log.farm("$sceneName 已标记 ${invalidUserIds.size} 个失效好友，后续好友选择会自动过滤")
    }

    private fun shouldAcceptGift(subFarmVO: JSONObject): Boolean {
        if (subFarmVO.has("giftRecord")) {
            return true
        }
        val giveFoodInfo = subFarmVO.optJSONObject("giveFoodInfo")
        if (giveFoodInfo == null) {
            Log.farm("庄园收礼跳过：未找到 giftRecord/giveFoodInfo，当前接口结构未命中")
            return false
        }
        val giveFoodSum = giveFoodInfo.optInt("giveFoodSum", 0)
        val lastAcceptFoodNum = giveFoodInfo.optInt("lastAcceptFoodNum", 0)
        val pendingFoodNum = giveFoodSum - lastAcceptFoodNum
        if (pendingFoodNum <= 0) {
            Log.farm("庄园收礼跳过：giveFoodInfo 显示当前无可领取麦子/稻子")
            return false
        }
        return true
    }

    private fun acceptGift() {
        try {
            val jo = JSONObject(AntFarmRpcCall.acceptGift())
            if (ResChecker.checkRes(TAG, jo)) {
                val receiveFoodNum = jo.getInt("receiveFoodNum")
                Log.farm("收取麦子🌾[" + receiveFoodNum + "g]")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "acceptGift err:",t)
        }
    }

    /**
     * 贴贴小鸡
     *
     * @param queryDayStr 日期，格式：yyyy-MM-dd
     */
    private fun diaryTietze(@Suppress("SameParameterValue") queryDayStr: String?) {
        val diaryDateStr: String?
        try {
            var jo = JSONObject(AntFarmRpcCall.queryChickenDiary(queryDayStr))
            if (ResChecker.checkRes(TAG, jo)) {
                val data = jo.getJSONObject("data")
                val chickenDiary = data.getJSONObject("chickenDiary")
                diaryDateStr = chickenDiary.getString("diaryDateStr")
                if (data.has("hasTietie")) {
                    if (!data.optBoolean("hasTietie", true)) {
                        jo = JSONObject(AntFarmRpcCall.diaryTietie(diaryDateStr, "NEW"))
                        if (ResChecker.checkRes(TAG, jo)) {
                            val prizeType = jo.getString("prizeType")
                            val prizeNum = jo.optInt("prizeNum", 0)
                            Log.farm("[$diaryDateStr]贴贴小鸡💞[$prizeType*$prizeNum]")
                        } else {
                            Log.farm("贴贴小鸡失败:")
                            Log.farm("[${jo.getString("memo")}]: $jo")
                        }
                        if (!chickenDiary.has("statisticsList")) return
                        val statisticsList = chickenDiary.getJSONArray("statisticsList")
                        if (statisticsList.length() > 0) {
                            for (i in 0..<statisticsList.length()) {
                                val tietieStatus = statisticsList.getJSONObject(i)
                                val tietieRoleId = tietieStatus.getString("tietieRoleId")
                                jo = JSONObject(
                                    AntFarmRpcCall.diaryTietie(
                                        diaryDateStr,
                                        tietieRoleId
                                    )
                                )
                                if (ResChecker.checkRes(TAG, jo)) {
                                    val prizeType = jo.getString("prizeType")
                                    val prizeNum = jo.optInt("prizeNum", 0)
                                    Log.farm("[$diaryDateStr]贴贴小鸡💞[$prizeType*$prizeNum]")
                                } else {
                                    Log.farm("贴贴小鸡失败:")
                                    Log.farm("[${jo.getString("memo")}]: $jo")
                                }
                            }
                        }
                    }
                }
            } else {
                Log.farm("贴贴小鸡-获取小鸡日记详情 err:")
                Log.farm("[${jo.getString("resultDesc")}]: $jo")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryChickenDiary err:",t)
        }
    }

    /**
     * 点赞小鸡日记
     *
     */
    private fun collectChickenDiary(queryDayStr: String?): String? {
        var diaryDateStr: String? = null
        try {
            var jo = JSONObject(AntFarmRpcCall.queryChickenDiary(queryDayStr))
            if (ResChecker.checkRes(TAG, jo)) {
                val data = jo.getJSONObject("data")
                val chickenDiary = data.getJSONObject("chickenDiary")
                diaryDateStr = chickenDiary.getString("diaryDateStr")
                // 点赞小鸡日记
                if (!chickenDiary.optBoolean("collectStatus", true)) {
                    val diaryId = chickenDiary.getString("diaryId")
                    jo = JSONObject(AntFarmRpcCall.collectChickenDiary(diaryId))
                    if (jo.optBoolean("success", true)) {
                        Log.farm("[$diaryDateStr]点赞小鸡日记💞成功")
                    }
                }
            } else {
                Log.farm("日记点赞-获取小鸡日记详情 err:")
                Log.farm("[${jo.getString("resultDesc")}]: $jo")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryChickenDiary err:",t)
        }
        return diaryDateStr
    }

    private suspend fun queryChickenDiaryList(
        queryMonthStr: String?,
        `fun`: (String?) -> String?
    ): Boolean {
        var hasPreviousMore = false
        try {
            var jo: JSONObject?
            jo = if (queryMonthStr.isNullOrEmpty()) {
                JSONObject(AntFarmRpcCall.queryChickenDiaryList())
            } else {
                JSONObject(AntFarmRpcCall.queryChickenDiaryList(queryMonthStr))
            }
            if (ResChecker.checkRes(TAG, jo)) {
                jo = jo.getJSONObject("data")
                hasPreviousMore = jo.optBoolean("hasPreviousMore", false)
                val chickenDiaryBriefList = jo.optJSONArray("chickenDiaryBriefList")
                if (chickenDiaryBriefList != null && chickenDiaryBriefList.length() > 0) {
                    for (i in chickenDiaryBriefList.length() - 1 downTo 0) {
                        jo = chickenDiaryBriefList.getJSONObject(i)
                        if (!jo.optBoolean("read", true) ||
                            !jo.optBoolean("collectStatus")
                        ) {
                            val dateStr = jo.getString("dateStr")
                            `fun`(dateStr)
                        }
                    }
                }
            } else {
                Log.farm("[${jo.getString("resultDesc")}]: $jo")
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("queryChickenDiaryList 协程被取消")
            throw e
        } catch (t: Throwable) {
            hasPreviousMore = false
            Log.printStackTrace(TAG, "queryChickenDiaryList err:",t)
        }
        return hasPreviousMore
    }

    internal suspend fun doChickenDiary() {
        if (diaryTietie?.value == true) { // 贴贴小鸡
            diaryTietze("")
        }

        // 小鸡日记点赞
        var dateStr: String? = null
        var yearMonth = YearMonth.now()
        var previous = false
        try {
            val collectType =
                collectChickenDiary?.value ?: collectChickenDiary?.defaultValue ?: collectChickenDiaryType.CLOSE
            if (collectType >= collectChickenDiaryType.ONCE) {
                dateStr = collectChickenDiary("")
            }
            if (collectType >= collectChickenDiaryType.MONTH) {
                if (dateStr == null) {
                    Log.error(TAG, "小鸡日记点赞-dateStr为空，使用当前日期")
                } else {
                    yearMonth = YearMonth.from(LocalDate.parse(dateStr))
                }
                previous = queryChickenDiaryList(
                    yearMonth.toString()
                ) { queryDayStr ->
                    this.collectChickenDiary(queryDayStr)
                }
            }
            if (collectType >= collectChickenDiaryType.ALL) {
                while (previous) {
                    yearMonth = yearMonth.minusMonths(1)
                    previous = queryChickenDiaryList(
                        yearMonth.toString()
                    ) { queryDayStr ->
                        this.collectChickenDiary(queryDayStr)
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("doChickenDiary 协程被取消")
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "doChickenDiary err:",e)
        }
    }

    internal fun visitAnimal() {
        try {
            val response = AntFarmRpcCall.visitAnimal()
            if (response.isNullOrEmpty()) {
                Log.farm("visitAnimal: 收到空响应")
                return
            }
            var jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                if (!jo.has("talkConfigs")) return
                val talkConfigs = jo.getJSONArray("talkConfigs")
                val talkNodes = jo.getJSONArray("talkNodes")
                val data = talkConfigs.getJSONObject(0)
                val farmId = data.getString("farmId")

                val response2 = AntFarmRpcCall.feedFriendAnimalVisit(farmId)
                if (response2.isNullOrEmpty()) {
                    Log.farm("feedFriendAnimalVisit: 收到空响应")
                    return
                }
                jo = JSONObject(response2)
                if (ResChecker.checkRes(TAG, jo)) {
                    for (i in 0..<talkNodes.length()) {
                        jo = talkNodes.getJSONObject(i)
                        if ("FEED" != jo.getString("type")) continue
                        val consistencyKey = jo.getString("consistencyKey")

                        val response3 = AntFarmRpcCall.visitAnimalSendPrize(consistencyKey)
                        if (response3.isNullOrEmpty()) continue // 静默跳过，继续处理下一个
                        jo = JSONObject(response3)
                        if (ResChecker.checkRes(TAG, jo)) {
                            val prizeName = jo.getString("prizeName")
                            Log.farm("小鸡到访💞[$prizeName]")
                        } else {
                            Log.farm("[${jo.getString("memo")}]: $jo")
                        }
                    }
                } else {
                    Log.farm("[${jo.getString("memo")}]: $jo")
                }
            } else {
                Log.farm("[${jo.getString("resultDesc")}]: $jo")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "visitAnimal err:",t)
        }
    }

    /* 雇佣好友小鸡 */
    internal fun hireAnimal() {
        // 重置农场已满标志
        isFarmFull = false
        var animals: JSONArray? = null
        try {
            val jsonObject = enterFarm() ?: return
            if ("SUCCESS" == jsonObject.getString("memo")) {
                val farmVO = jsonObject.getJSONObject("farmVO")
                val subFarmVO = farmVO.getJSONObject("subFarmVO")
                animals = subFarmVO.getJSONArray("animals")
            } else {
                Log.farm(jsonObject.getString("memo"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "getAnimalCount err:",t)
            return
        }
        if (animals == null) {
            return
        }
        try {
            var i = 0
            val len = animals.length()
            while (i < len) {
                val joo = animals.getJSONObject(i)
                if (joo.getString("subAnimalType") == "WORK") {
                    val taskId = "HIRE|" + joo.getString("animalId")
                    val beHiredEndTime = joo.getLong("beHiredEndTime")
                    if (!hasChildTask(taskId)) {
                        addChildTask(
                            ChildModelTask(
                                taskId,
                                "HIRE",
                                suspendRunnable = { this.hireAnimal() },
                                beHiredEndTime
                            )
                        )
                        Log.farm("添加蹲点雇佣👷在[" + TimeUtil.getCommonDate(beHiredEndTime) + "]执行"
                        )
                    } else {
                        addChildTask(
                            ChildModelTask(
                                taskId,
                                "HIRE",
                                suspendRunnable = { this.hireAnimal() },
                                beHiredEndTime
                            )
                        )
                    }
                }
                i++
            }
            var animalCount = animals.length()
            if (animalCount >= 3) {
                return
            }
            val needHireCount = 3 - animalCount
            Log.farm("雇佣小鸡👷[当前可雇佣小鸡数量:${needHireCount}只]")

            // 前置检查：饲料是否足够
            if (foodStock < 50) {
                Log.farm("❌ 雇佣失败：饲料不足（当前${foodStock}g，至少需要50g）")
                return
            }

            // 前置检查：是否配置了雇佣好友列表
            val hireAnimalSet = hireAnimalList?.resolvedIds() ?: emptySet()
            if (hireAnimalSet.isEmpty()) {
                if (hireAnimalType!!.value == HireAnimalType.HIRE) {
                    Log.farm("❌ 雇佣失败：未配置雇佣好友列表")
                    Toast.show(
                        "⚠️ 雇佣小鸡配置错误\n" +
                                "已开启「雇佣小鸡」但未配置好友列表\n" +
                                "请在「雇佣小鸡 | 好友列表」中勾选好友"
                    )
                    return
                } else {
                    // 选中不雇佣：空列表表示“不排除任何好友”，即默认雇佣全部好友
                    Log.farm("雇佣小鸡👷[好友列表未勾选任何人，按「选中不雇佣」模式将默认尝试雇佣全部好友]")
                }
            }

            var hasNext: Boolean
            var pageStartSum = 0
            var s: String?
            var jo: JSONObject?
            var checkedCount = 0  // 检查过的好友数量
            var availableCount = 0  // 可雇佣状态的好友数量
            val initialAnimalCount = animalCount  // 记录初始数量

            do {
                s = AntFarmRpcCall.rankingList(pageStartSum)
                jo = JSONObject(s)
                val memo = jo.getString("memo")
                if (ResChecker.checkRes(TAG, jo)) {
                    hasNext = jo.getBoolean("hasNext")
                    val jaRankingList = jo.getJSONArray("rankingList")
                    if (jaRankingList.length() == 0) {
                        Log.farm("雇佣小鸡：好友排行返回空页，终止翻页")
                        break
                    }
                    pageStartSum += jaRankingList.length()
                    for (i in 0..<jaRankingList.length()) {
                        val joo = jaRankingList.getJSONObject(i)
                        val userId = joo.getString("userId")
                        if (FriendGuard.shouldSkipFriend(userId, TAG, "雇佣小鸡")) {
                            continue
                        }
                        var isHireAnimal = hireAnimalSet.contains(userId)
                        if (hireAnimalType!!.value == HireAnimalType.DONT_HIRE) {
                            isHireAnimal = !isHireAnimal
                        }
                        if (!isHireAnimal || userId == UserMap.currentUid) {
                            continue
                        }

                        checkedCount++
                        val actionTypeListStr = joo.optJSONArray("actionTypeList")?.toString().orEmpty()
                        val canHire = actionTypeListStr.contains("can_hire_action") ||
                            joo.optString("actionType") == "can_hire_action" ||
                            joo.optBoolean("canGrabHire", false)
                        if (canHire) {
                            availableCount++
                            if (hireAnimalAction(userId)) {
                                animalCount++
                                if (animalCount >= 3) {
                                    break
                                }
                                continue
                            }
                            // 检查农场是否已满
                            if (isFarmFull) {
                                animalCount = 3  // 标记庄园已满，避免下次循环继续尝试
                                break  // 跳出for循环
                            }
                        }
                    }
                } else {
                    Log.farm(memo)
                    Log.farm(s)
                    break
                }
            } while (hasNext && animalCount < 3)

            // 详细的结果报告
            val hiredCount = animalCount - initialAnimalCount
            if (animalCount < 3) {
                val stillNeed = 3 - animalCount
                Log.farm("雇佣小鸡结果统计：")
                Log.farm("  • 成功雇佣：${hiredCount}只")
                Log.farm("  • 还需雇佣：${stillNeed}只")
                Log.farm("  • 已检查好友：${checkedCount}人")
                Log.farm("  • 可雇佣状态：${availableCount}人")

                if (availableCount == 0) {
                    Log.farm("❌ 失败原因：好友列表中没有可雇佣的小鸡")
                    Log.farm("   建议：等待好友的小鸡回家或添加更多好友")
                } else if (hiredCount < availableCount) {
                    Log.farm("⚠️ 部分雇佣失败：好友的小鸡可能不在家")
                } else {
                    Log.farm("❌ 失败原因：可雇佣的小鸡数量不足")
                }
            } else if (hiredCount > 0) {
                Log.farm("✅ 雇佣成功：共雇佣${hiredCount}只小鸡")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "hireAnimal err:",t)
        }
    }

    private fun hireAnimalAction(userId: String?): Boolean {
        try {
            var jo = enterFriendFarmIfAvailable(userId, "雇佣小鸡") ?: return false
            if (ResChecker.checkRes(TAG, jo)) {
                val farmVO = jo.getJSONObject("farmVO")
                val subFarmVO = farmVO.getJSONObject("subFarmVO")
                val farmId = subFarmVO.getString("farmId")
                val animals = subFarmVO.getJSONArray("animals")
                var candidate: JSONObject? = null
                var fallbackCandidate: JSONObject? = null
                var sawWorkAnimal = false
                for (i in 0 until animals.length()) {
                    val animal = animals.getJSONObject(i)
                    if (animal.optString("subAnimalType") == "WORK") {
                        sawWorkAnimal = true
                        continue
                    }
                    val animalStatusVo = animal.optJSONObject("animalStatusVO") ?: continue
                    if (AnimalInteractStatus.HOME.name != animalStatusVo.optString("animalInteractStatus")) {
                        continue
                    }
                    fallbackCandidate = fallbackCandidate ?: animal
                    val masterUserId = animal.optJSONObject("masterUserInfoVO")
                        ?.optString("userId")
                        .orEmpty()
                    if (masterUserId.isBlank() || masterUserId == userId) {
                        candidate = animal
                        break
                    }
                }

                val animal = candidate ?: fallbackCandidate
                if (animal == null) {
                    if (sawWorkAnimal) {
                        Log.farm(UserMap.getMaskName(userId) + "的小鸡可雇佣数量不足，已跳过外出工作的小鸡")
                    } else {
                        Log.farm(UserMap.getMaskName(userId) + "的小鸡不在家")
                    }
                    return false
                }

                val animalId = animal.optString("animalId")
                if (animalId.isBlank()) {
                    return false
                }

                jo = JSONObject(AntFarmRpcCall.hireAnimal(farmId, animalId))
                val resultCode = jo.optString("resultCode", "")
                val memo = jo.optString("memo", "")
                if (resultCode == "I05" || memo.contains("篱笆卡")) {
                    Log.farm("雇佣小鸡👷[${UserMap.getMaskName(userId)}] 跳过：好友使用了篱笆卡")
                    return false
                }
                if (ResChecker.checkRes(TAG, jo)) {
                    Log.farm("雇佣小鸡👷[" + UserMap.getMaskName(userId) + "] 成功")
                    val newAnimals = jo.getJSONArray("animals")
                    var ii = 0
                    val newLen = newAnimals.length()
                    while (ii < newLen) {
                        val joo = newAnimals.getJSONObject(ii)
                        if (joo.getString("animalId") == animalId) {
                            val beHiredEndTime = joo.getLong("beHiredEndTime")
                            addChildTask(
                                ChildModelTask(
                                    "HIRE|$animalId",
                                    "HIRE",
                                    suspendRunnable = { this.hireAnimal() },
                                    beHiredEndTime
                                )
                            )
                            Log.farm("添加蹲点雇佣👷在[" + TimeUtil.getCommonDate(beHiredEndTime) + "]执行"
                            )
                            break
                        }
                        ii++
                    }
                    return true
                } else {
                    if (resultCode == "I07" || memo.contains("庄园的小鸡太多了")) {
                        isFarmFull = true
                        Log.farm("庄园小鸡已满，停止雇佣")
                        return false
                    }
                    Log.farm(memo)
                    Log.farm(jo.toString())
                }
            } else {
                Log.farm(jo.getString("memo"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "hireAnimal err:",t)
        }
        return false
    }

    /**
     * 统一处理NPC小鸡的雇佣、切换、领奖与任务
     */
    internal suspend fun handleNpcAnimalLogic() {
        try {
            val selectedIndex = npcAnimalType?.value ?: 0
            val targetConfig = NpcConfig.getByIndex(selectedIndex)
            if (targetConfig == NpcConfig.NONE) return

            // 1. 查找当前已雇佣的NPC动物
            var currentNpcAnimal: Animal? = null
            var currentNpcJson: JSONObject? = null // 用于获取 Animal 类未映射的字段

            // 为了获取准确的 npcBizReward 等字段，建议解析 syncAnimalStatus 的原始响应
            // 这里我们先从 enterFarm 缓存的 animals 中找，如果找不到或需要精确状态，可能需要重新 sync
            if (animals != null) {
                for (animal in animals!!) {
                    if ("NPC" == animal.subAnimalType) {
                        currentNpcAnimal = animal
                        break
                    }
                }
            }

            // 如果内存中状态可能不准，或者需要详细字段，重新同步一次
            val syncRes = AntFarmRpcCall.syncAnimalStatus(ownerFarmId, "SYNC_NPC", "QUERY_FARM_INFO")
            val joSync = JSONObject(syncRes)
            if (!ResChecker.checkRes(TAG, joSync)) return

            val animalsJa = joSync.optJSONObject("subFarmVO")?.optJSONArray("animals")
            if (animalsJa != null) {
                for (i in 0 until animalsJa.length()) {
                    val a = animalsJa.getJSONObject(i)
                    if ("NPC" == a.optString("subAnimalType")) {
                        currentNpcJson = a
                        // 更新内存对象
                        currentNpcAnimal = objectMapper.readValue(a.toString(), Animal::class.java)
                        break
                    }
                }
            }

            // 2. 决策逻辑
            if (currentNpcAnimal == null) {
                // 场景A: 当前没有NPC -> 直接雇佣目标NPC
                Log.farm("NPC小鸡🤖[当前未雇佣，准备雇佣${targetConfig.nickName}]")
                hireNpc(targetConfig)
            } else {
                // 场景B: 当前有NPC
                val currentId = currentNpcAnimal.animalId

                if (currentId == targetConfig.animalId) {
                    // B1: 正是选中的这只 -> 检查奖励是否已满
                    checkRewardAndTask(currentNpcAnimal, currentNpcJson, targetConfig)
                } else {
                    // B2: 是其他类型的NPC -> 遣返旧的，雇佣新的
                    val currentName = currentNpcAnimal.masterUserInfoVO?.get("nickName") as? String ?: "未知NPC"
                    Log.farm("NPC小鸡🤖[检测到${currentName}，目标是${targetConfig.nickName}，执行切换]")

                    // 遣返当前 (领取奖励)
                    val sendBackRes = AntFarmRpcCall.sendBackNpcAnimal(
                        currentNpcAnimal.animalId,
                        currentNpcAnimal.currentFarmId,
                        currentNpcAnimal.masterFarmId
                    )
                    if (ResChecker.checkRes(TAG, JSONObject(sendBackRes))) {
                        Log.farm("NPC小鸡🤖[已遣返${currentName}]")
                        // 雇佣新的
                        hireNpc(targetConfig)
                    } else {
                        Log.farm("NPC小鸡🤖[遣返失败，暂停切换]")
                    }
                }
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "handleNpcAnimalLogic err:", t)
        }
    }

    private fun hireNpc(config: NpcConfig): Boolean {
        try {
            val s = AntFarmRpcCall.hireNpcAnimal(config.animalId, config.source)
            val jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("NPC小鸡🤖[成功雇佣${config.nickName}]")
                syncAnimalStatus(ownerFarmId) // 刷新状态
                return true
            } else {
                Log.farm("NPC小鸡🤖[雇佣${config.nickName}失败: ${jo.optString("memo")}]")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "hireNpc err", e)
        }
        return false
    }

    private suspend fun checkRewardAndTask(animal: Animal, animalJson: JSONObject?, config: NpcConfig) {
        // 1. 检查奖励是否达标
        val currentReward = animalJson?.optDouble("npcBizReward", 0.0) ?: 0.0
        // 部分NPC可能用 reachNpcBizRewardLimit 标识满额，部分可能用阈值
        // 芝麻粒通常是 88，其他可能是 100%
        val isLimit = animalJson?.optBoolean("reachNpcBizRewardLimit", false) ?: false

        // 判定满额逻辑：如果是芝麻鸽且>=88，或者是通用Limit标记
        val isFull = isLimit || (config == NpcConfig.ZHIMA_PIGEON && currentReward >= 88.0)

        if (isFull) {
            Log.farm("NPC小鸡🤖[${config.nickName}产出已满($currentReward)，领取并重雇]")
            val sendBackRes = AntFarmRpcCall.sendBackNpcAnimal(
                animal.animalId,
                animal.currentFarmId,
                animal.masterFarmId
            )
            if (ResChecker.checkRes(TAG, JSONObject(sendBackRes))) {
                Log.farm("NPC小鸡🤖[奖励领取成功]")
                hireNpc(config)
            }
        } else {
            Log.farm("NPC小鸡🤖[${config.nickName}工作中... 当前产出:$currentReward]")

            // 2. 仅芝麻大表鸽支持做任务加速 (目前已知)
            if (config == NpcConfig.ZHIMA_PIGEON) {
                handleZhimaPigeonTasks()
            }
        }
    }

    /**
     * 处理芝麻大表鸽的加速任务
     */
    private fun handleZhimaPigeonTasks() {
        try {
            val s = AntFarmRpcCall.listZhimaNpcFarmTask()
            val jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val taskList = jo.optJSONArray("farmTaskList") ?: return
                for (i in 0 until taskList.length()) {
                    val task = taskList.getJSONObject(i)
                    val taskId = task.optString("taskId")
                    val title = task.optString("title")
                    val taskStatus = task.optString("taskStatus")

                    // 如果任务已完成但未领取
                    if (TaskStatus.FINISHED.name == taskStatus) {
                        val awardRes = AntFarmRpcCall.receiveZhimaNpcFarmTaskAward(taskId)
                        val awardJo = JSONObject(awardRes)
                        if (ResChecker.checkRes(TAG, awardJo)) {
                            val awardCount = task.optInt("awardCount", 0)
                            Log.farm("NPC任务🤖[完成: $title, 奖励: $awardCount 芝麻粒]")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "handleZhimaPigeonTasks err", e)
        }
    }
    // 小鸡换装
    private fun listOrnaments() {
        try {
            val s = AntFarmRpcCall.queryLoveCabin(UserMap.currentUid)
            val jsonObject = JSONObject(s)
            if ("SUCCESS" == jsonObject.getString("memo")) {
                val ownAnimal = jsonObject.getJSONObject("ownAnimal")
                val animalId = ownAnimal.getString("animalId")
                val farmId = ownAnimal.getString("farmId")
                val listResult = AntFarmRpcCall.listOrnaments()
                val jolistOrnaments = JSONObject(listResult)
                // 检查是否有 achievementOrnaments 数组
                if (!jolistOrnaments.has("achievementOrnaments")) {
                    return  // 数组为空，直接返回
                }
                val achievementOrnaments = jolistOrnaments.getJSONArray("achievementOrnaments")
                val random = Random()
                val possibleOrnaments: MutableList<String> = ArrayList() // 收集所有可保存的套装组合
                for (i in 0..<achievementOrnaments.length()) {
                    val ornament = achievementOrnaments.getJSONObject(i)
                    if (ornament.getBoolean("acquired")) {
                        val sets = ornament.getJSONArray("sets")
                        val availableSets: MutableList<JSONObject> = ArrayList()
                        // 收集所有带有 cap 和 coat 的套装组合
                        for (j in 0..<sets.length()) {
                            val set = sets.getJSONObject(j)
                            if ("cap" == set.getString("subType") || "coat" == set.getString("subType")) {
                                availableSets.add(set)
                            }
                        }
                        // 如果有可用的帽子和外套套装组合
                        if (availableSets.size >= 2) {
                            // 将所有可保存的套装组合添加到 possibleOrnaments 列表中
                            for (j in 0..<availableSets.size - 1) {
                                val selectedCoat = availableSets[j]
                                val selectedCap = availableSets[j + 1]
                                val id1 = selectedCoat.getString("id") // 外套 ID
                                val id2 = selectedCap.getString("id") // 帽子 ID
                                val ornaments = "$id1,$id2"
                                possibleOrnaments.add(ornaments)
                            }
                        }
                    }
                }
                // 如果有可保存的套装组合，则随机选择一个进行保存
                if (!possibleOrnaments.isEmpty()) {
                    val ornamentsToSave =
                        possibleOrnaments[random.nextInt(possibleOrnaments.size)]
                    val saveResult = AntFarmRpcCall.saveOrnaments(animalId, farmId, ornamentsToSave)
                    val saveResultJson = JSONObject(saveResult)
                    // 判断保存是否成功并输出日志
                    if (saveResultJson.optBoolean("success")) {
                        // 获取保存的整套服装名称
                        val ornamentIds: Array<String?> =
                            ornamentsToSave.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()
                        var wholeSetName = "" // 整套服装名称
                        // 遍历 achievementOrnaments 查找对应的套装名称
                        for (i in 0..<achievementOrnaments.length()) {
                            val ornament = achievementOrnaments.getJSONObject(i)
                            val sets = ornament.getJSONArray("sets")
                            // 找到对应的整套服装名称
                            if (sets.length() == 2 && sets.getJSONObject(0)
                                    .getString("id") == ornamentIds[0]
                                && sets.getJSONObject(1).getString("id") == ornamentIds[1]
                            ) {
                                wholeSetName = ornament.getString("name")
                                break
                            }
                        }
                        // 输出日志
                        Log.farm("庄园小鸡💞[换装:$wholeSetName]")
                        Status.setOrnamentToday()
                    } else {
                        Log.farm("保存时装失败，错误码： $saveResultJson")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "listOrnaments err: " + t.message,t)
        }
    }

    // 一起拿小鸡饲料
    internal fun letsGetChickenFeedTogether() {
        try {
            var jo = JSONObject(AntFarmRpcCall.letsGetChickenFeedTogether())
            if (jo.optBoolean("success")) {
                val bizTraceId = jo.getString("bizTraceId")
                val p2pCanInvitePersonDetailList = jo.getJSONArray("p2pCanInvitePersonDetailList")
                var canInviteCount = 0
                var hasInvitedCount = 0
                val userIdList: MutableList<String?> = ArrayList() // 保存 userId
                for (i in 0..<p2pCanInvitePersonDetailList.length()) {
                    val personDetail = p2pCanInvitePersonDetailList.getJSONObject(i)
                    val inviteStatus = personDetail.getString("inviteStatus")
                    val userId = personDetail.getString("userId")
                    if (inviteStatus == "CAN_INVITE" && !FriendGuard.shouldSkipFriend(userId, TAG, "一起拿饲料")) {
                        userIdList.add(userId)
                        canInviteCount++
                    } else if (inviteStatus == "HAS_INVITED") {
                        hasInvitedCount++
                    }
                }
                val invitedToday = hasInvitedCount
                val remainingInvites = 5 - invitedToday
                var invitesToSend = min(canInviteCount, remainingInvites)
                if (invitesToSend == 0) {
                    return
                }
                val getFeedSet = getFeedlList?.resolvedIds() ?: emptySet()
                if (getFeedType!!.value == GetFeedType.GIVE) {
                    for (userId in userIdList) {
                        if (invitesToSend <= 0) {
//                            Log.farm("已达到最大邀请次数限制，停止发送邀请。");
                            break
                        }
                        if (getFeedSet.contains(userId)) {
                            jo = JSONObject(AntFarmRpcCall.giftOfFeed(bizTraceId, userId))
                            if (jo.optBoolean("success")) {
                                Log.farm("一起拿小鸡饲料🥡 [送饲料：" + UserMap.getMaskName(userId) + "]")
                                invitesToSend-- // 每成功发送一次邀请，减少一次邀请次数
                            } else {
                                Log.farm("邀请失败：$jo")
                                break
                            }
                        }
                    }
                } else {
                    val random = Random()
                    for (j in 0..<invitesToSend) {
                        val randomIndex = random.nextInt(userIdList.size)
                        val userId = userIdList[randomIndex]
                        jo = JSONObject(AntFarmRpcCall.giftOfFeed(bizTraceId, userId))
                        if (jo.optBoolean("success")) {
                            Log.farm("一起拿小鸡饲料🥡 [送饲料：" + UserMap.getMaskName(userId) + "]")
                        } else {
                            Log.farm("邀请失败：$jo")
                            break
                        }
                        userIdList.removeAt(randomIndex)
                    }
                }
            }
        } catch (e: JSONException) {
            Log.printStackTrace(TAG, "letsGetChickenFeedTogether err:",e)
        }
    }

    interface DonationMode {
        companion object {
            const val ONE_AVAILABLE_PROJECT: Int = 0
            const val ALL_AVAILABLE_PROJECTS: Int = 1
            const val ALL_UNDONATED_PROJECTS: Int = 2
            val nickNames: Array<String?> = arrayOf<String?>(
                "当日列表中的一个项目",
                "当日列表中全部可捐项目",
                "当日列表中所有未捐项目"
            )
        }
    }

    interface DonationCompetitionMode {
        companion object {
            const val AGGRESSIVE: Int = 0
            const val STABLE: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("激进模式", "稳定模式")
        }
    }

    interface RecallAnimalType {
        companion object {
            const val ALWAYS: Int = 0
            const val WHEN_THIEF: Int = 1
            const val WHEN_HUNGRY: Int = 2
            const val NEVER: Int = 3
            val nickNames: Array<String?> =
                arrayOf<String?>("始终召回", "偷吃召回", "饥饿召回", "暂不召回")
        }
    }

    interface SendBackAnimalWay {
        companion object {
            const val HIT: Int = 0
            const val NORMAL: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("攻击", "常规")
        }
    }

    interface OrnamentLevel {
        companion object {
            const val NONE: Int = 0
            const val DIANCANG: Int = 1
            const val XIYOU: Int = 2
            const val GAOJI: Int = 3
            const val PUTONG: Int = 4
            const val ALL: Int = 5
            val nickNames: Array<String?> = arrayOf("不兑换", "典藏", "稀有", "高级", "普通", "全部")
            val levels: Array<String> = arrayOf("NONE", "DIANCANG", "XIYOU", "GAOJI", "PUTONG", "ALL")
        }
    }

    interface SendBackAnimalType {
        companion object {
            const val BACK: Int = 0
            const val NOT_BACK: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("选中遣返", "选中不遣返")
        }
    }

    @Suppress("ClassName")
    interface collectChickenDiaryType {
        companion object {
            const val CLOSE: Int = 0
            const val ONCE: Int = 1
            const val MONTH: Int = 2
            const val ALL: Int = 3
            val nickNames: Array<String?> = arrayOf<String?>("不开启", "一次", "当月", "所有")
        }
    }

    enum class AnimalBuff {
        //小鸡buff
        ACCELERATING, INJURED, NONE
    }

    /**
     * 小鸡喂食状态枚举
     */
    enum class AnimalFeedStatus {
        HUNGRY,  // 饥饿状态：小鸡需要投喂，可以正常喂食
        EATING,  // 进食状态：小鸡正在吃饭，此时不能重复投喂，会返回"不要着急，还没吃完呢"
        SLEEPY,  // 睡觉状态：小鸡正在睡觉，不能投喂，需要等待醒来
        NONE // 无状态：未知或其他状态
    }

    /**
     * 小鸡互动状态枚举
     */
    enum class AnimalInteractStatus {
        HOME,  // 在家：小鸡在自己的庄园里，正常状态
        GOTOSTEAL,  // 去偷吃：小鸡离开庄园，准备去别的庄园偷吃
        STEALING // 偷吃中：小鸡正在别人的庄园里偷吃饲料
    }

    /**
     * 小鸡子类型枚举
     */
    enum class SubAnimalType {
        NORMAL,  // 普通：正常的小鸡状态
        GUEST,  // 客人：小鸡去好友家做客
        PIRATE,  // 海盗：小鸡外出探险
        WORK // 工作：小鸡被雇佣去工作
    }

    /**
     * 道具类型枚举
     * STEALTOOL：蹭饭卡
     * ACCELERATETOOL：加速卡
     * SHARETOOL：救济卡
     * FENCETOOL：篱笆卡
     * NEWEGGTOOL：新蛋卡
     * DOLLTOOL：公仔补签卡
     * ORDINARY_ORNAMENT_TOOL：普通装扮补签卡
     * ADVANCE_ORNAMENT_TOOL：高级装扮补签卡
     * BIG_EATER_TOOL：加饭卡
     * RARE_ORNAMENT_TOOL：稀有装扮补签卡
     */
    enum class ToolType {
        STEALTOOL,  // 蹭饭卡
        ACCELERATETOOL,  // 加速卡
        SHARETOOL,  // 救济卡
        FENCETOOL,  // 篱笆卡
        NEWEGGTOOL,  // 新蛋卡
        DOLLTOOL,  // 公仔补签卡
        ORDINARY_ORNAMENT_TOOL,  // 普通装扮补签卡
        ADVANCE_ORNAMENT_TOOL,  // 高级装扮补签卡
        BIG_EATER_TOOL,  // 加饭卡
        RARE_ORNAMENT_TOOL; // 稀有装扮补签卡

        /**
         * 获取道具类型的中文名称
         * @return 对应的中文名称
         */
        fun nickName(): CharSequence? {
            return nickNames[ordinal]
        }

        companion object {
            // 道具类型对应的中文名称
            val nickNames: Array<CharSequence?> = arrayOf<CharSequence?>(
                "蹭饭卡",
                "加速卡",
                "救济卡",
                "篱笆卡",
                "新蛋卡",
                "公仔补签卡",
                "普通装扮补签卡",
                "高级装扮补签卡",
                "加饭卡",
                "稀有装扮补签卡"
            )
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private class Animal {
        @JsonProperty("animalId")
        var animalId: String? = null

        @JsonProperty("currentFarmId")
        var currentFarmId: String? = null

        @JsonProperty("masterFarmId")
        var masterFarmId: String? = null

        @JsonProperty("animalBuff")
        var animalBuff: String? = null

        @JsonProperty("subAnimalType")
        var subAnimalType: String? = null

        @JsonProperty("currentFarmMasterUserId")
        var currentFarmMasterUserId: String? = null

        var animalFeedStatus: String? = null

        var animalInteractStatus: String? = null

        @JsonProperty("locationType")
        var locationType: String? = null

        @JsonProperty("startEatTime")
        var startEatTime: Long? = null

        @JsonProperty("consumeSpeed")
        var consumeSpeed: Double? = null

        @JsonProperty("foodHaveEatten")
        var foodHaveEatten: Double? = null

        @JsonProperty("foodHaveStolen")
        var foodHaveStolen: Double? = null

        @JsonProperty("animalStatusVO")
        fun unmarshalAnimalStatusVO(map: MutableMap<String?, Any?>?) {
            if (map != null) {
                this.animalFeedStatus = map["animalFeedStatus"] as String?
                this.animalInteractStatus = map["animalInteractStatus"] as String?
            }
        }
        @JsonProperty("masterUserInfoVO")
        var masterUserInfoVO: Map<String, Any>? = null
    }

    private class RewardFriend {
        var consistencyKey: String? = null
        var friendId: String? = null
        var time: String? = null
    }

    private class FarmTool {
        var toolType: ToolType? = null
        var toolId: String? = null
        var toolCount: Int = 0
        var toolHoldLimit: Int = 0
    }

    @Suppress("unused")
    interface HireAnimalType {
        companion object {
            const val HIRE: Int = 0
            const val DONT_HIRE: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("选中雇佣", "选中不雇佣")
        }
    }

    @Suppress("unused")
    interface GetFeedType {
        companion object {
            const val GIVE: Int = 0
            const val RANDOM: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("选中赠送", "随机赠送")
        }
    }

    interface FamilyAssignStrategy {
        companion object {
            const val RANDOM: Int = 0
            const val LOWEST_TODAY_INTIMACY: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("随机安排", "优先今日亲密值最低")
        }
    }

    interface NotifyFriendType {
        companion object {
            const val NOTIFY: Int = 0
            const val DONT_NOTIFY: Int = 1
            val nickNames: Array<String?> = arrayOf<String?>("选中通知", "选中不通知")
        }
    }

    enum class PropStatus {
        REACH_USER_HOLD_LIMIT, NO_ENOUGH_POINT, REACH_LIMIT;

        fun nickName(): CharSequence? {
            return nickNames[ordinal]
        }

        companion object {
            val nickNames: Array<CharSequence?> =
                arrayOf<CharSequence?>("达到用户持有上限", "乐园币不足", "兑换达到上限")
        }
    }

    suspend fun family() {
        if (familyGroupId.isNullOrEmpty()) {
            return
        }
        try {
            var jo = JSONObject(AntFarmRpcCall.enterFamily())
            if (!ResChecker.checkRes(TAG, jo)) return
            familyGroupId = jo.getString("groupId")
            val familySignTips = jo.getBoolean("familySignTips")
            //顶梁柱
            jo.getJSONObject("assignFamilyMemberInfo")
            //美食配置
            val eatTogetherConfig = jo.getJSONObject("eatTogetherConfig")
            //扭蛋
            val familyDrawInfo = jo.getJSONObject("familyDrawInfo")
            val familyInteractActions = jo.getJSONArray("familyInteractActions")
            val animals = jo.getJSONArray("animals")
            val familyOptionSet = familyOptions?.value ?: emptySet()
            val familyUserIds: MutableList<String?> = ArrayList()

            for (i in 0..<animals.length()) {
                jo = animals.getJSONObject(i)
                val userId = jo.getString("userId")
                familyUserIds.add(userId)
            }
            if (familySignTips && familyOptionSet.contains("familySign")) {
                familySign()
            }
            if (familyOptionSet.contains("familyClaimReward")) {
                familyClaimRewardList()
            }

            //帮喂成员
            if (familyOptionSet.contains("feedFriendAnimal")) {
                familyFeedFriendAnimal(animals)
            }
            //请吃美食
            if (familyOptionSet.contains("eatTogetherConfig")) {
                familyEatTogether(eatTogetherConfig, familyInteractActions, familyUserIds)
            }

            //好友分享
            if (familyOptionSet.contains("inviteFriendVisitFamily")) {
                inviteFriendVisitFamily(familyUserIds)
            }
            val drawActivitySwitch = familyDrawInfo.getBoolean("drawActivitySwitch")
            //扭蛋
            if (drawActivitySwitch && familyOptionSet.contains("familyDrawInfo")) {
                familyDrawTask(familyUserIds, familyDrawInfo)
            }


        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "family err:",t)
        }
    }

    /**
     * 同步家庭亲密度状态
     * @param groupId 家庭组ID
     */
    private fun syncFamilyStatusIntimacy(groupId: String?) {
        try {
            val userId = UserMap.currentUid
            val jo = JSONObject(AntFarmRpcCall.syncFamilyStatus(groupId, "INTIMACY_VALUE", userId))
            ResChecker.checkRes(TAG, jo)
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("syncFamilyStatusIntimacy 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "syncFamilyStatus err:",t)
        }
    }

    /**
     * 邀请好友访问家庭
     * @param friendUserIds 好友用户ID列表
     */
    private suspend fun inviteFriendVisitFamily(friendUserIds: MutableList<String?>) {
        try {
            if (Status.hasFlagToday(StatusFlags.FLAG_FARM_INVITE_FRIEND_VISIT_FAMILY)) {
                return
            }
            val familyValue = notInviteList?.resolvedIds() ?: emptySet()
            if (familyValue.isEmpty()) {
                return
            }
            if (Objects.isNull(friendUserIds) || friendUserIds.isEmpty()) {
                return
            }
            val userIdArray = JSONArray()
            for (u in familyValue) {
                if (!friendUserIds.contains(u) && userIdArray.length() < 6) {
                    userIdArray.put(u)
                }
                if (userIdArray.length() >= 6) {
                    break
                }
            }
            val jo = JSONObject(AntFarmRpcCall.inviteFriendVisitFamily(userIdArray))
            if ("SUCCESS" == jo.getString("memo")) {
                Log.farm("亲密家庭🏠提交任务[分享好友]")
                Status.setFlagToday(StatusFlags.FLAG_FARM_INVITE_FRIEND_VISIT_FAMILY)
                syncFamilyStatusIntimacy(familyGroupId)
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("inviteFriendVisitFamily 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "inviteFriendVisitFamily err:",t)
        }
    }

    /**
     * 家庭批量邀请P2P任务
     * @param friendUserIds 好友用户ID列表
     * @param familyDrawInfo 家庭扭蛋信息
     */
    private suspend fun familyBatchInviteP2PTask(
        friendUserIds: MutableList<String?>,
        familyDrawInfo: JSONObject
    ) {
        try {
            if (Status.hasFlagToday(StatusFlags.FLAG_FARM_FAMILY_BATCH_INVITE_P2P)) {
                return
            }
            if (Objects.isNull(friendUserIds) || friendUserIds.isEmpty()) {
                return
            }
            val activityId = familyDrawInfo.optString("activityId")
            val sceneCode = "ANTFARM_FD_VISIT_$activityId"
            var jo = JSONObject(AntFarmRpcCall.familyShareP2PPanelInfo(sceneCode))
            if (ResChecker.checkRes(TAG, jo)) {
                val p2PFriendVOList = jo.getJSONArray("p2PFriendVOList")
                if (Objects.isNull(p2PFriendVOList) || p2PFriendVOList.length() <= 0) {
                    return
                }
                val inviteP2PVOList = JSONArray()
                for (i in 0..<p2PFriendVOList.length()) {
                    if (inviteP2PVOList.length() < 6) {
                        val `object` = JSONObject()
                        `object`.put(
                            "beInvitedUserId",
                            p2PFriendVOList.getJSONObject(i).getString("userId")
                        )
                        `object`.put("bizTraceId", "")
                        inviteP2PVOList.put(`object`)
                    }
                    if (inviteP2PVOList.length() >= 6) {
                        break
                    }
                }
                jo = JSONObject(AntFarmRpcCall.familyBatchInviteP2P(inviteP2PVOList, sceneCode))
                if (ResChecker.checkRes(TAG, jo)) {
                    Log.farm("亲密家庭🏠提交任务[好友串门送扭蛋]")
                    Status.setFlagToday(StatusFlags.FLAG_FARM_FAMILY_BATCH_INVITE_P2P)
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("familyBatchInviteP2PTask 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyBatchInviteP2PTask err:",t)
        }
    }

    /**
     * 家庭扭蛋任务
     * @param friendUserIds 好友用户ID列表
     * @param familyDrawInfo 家庭扭蛋信息
     */
    private suspend fun familyDrawTask(friendUserIds: MutableList<String?>, familyDrawInfo: JSONObject) {
        try {
            val listFarmTask = familyDrawListFarmTask() ?: return
            for (i in 0..<listFarmTask.length()) {
                val jo = listFarmTask.getJSONObject(i)
                val taskStatus = TaskStatus.valueOf(jo.getString("taskStatus"))
                val taskId = jo.optString("taskId")
                val title = jo.optString("title")
                if (taskStatus == TaskStatus.RECEIVED) {
                    continue
                }
                if (taskStatus == TaskStatus.TODO && taskId == "FAMILY_DRAW_VISIT_TASK"
                    && familyOptions?.value?.contains("batchInviteP2P") == true
                ) {
                    //分享
                    familyBatchInviteP2PTask(friendUserIds, familyDrawInfo)
                    continue
                }
                if (taskStatus == TaskStatus.FINISHED && taskId == "FAMILY_DRAW_FREE_TASK") {
                    //签到
                    familyDrawSignReceiveFarmTaskAward(taskId, title)
                    continue
                }
            }
            val jo = JSONObject(AntFarmRpcCall.queryFamilyDrawActivity())
            if (ResChecker.checkRes(TAG, jo)) {
                val drawTimes = jo.optInt("familyDrawTimes")
                //碎片个数
                val giftNum = jo.optInt("mengliFragmentCount")
                if (giftNum >= 20 && !Objects.isNull(giftFamilyDrawFragment!!.value)) {
                    giftFamilyDrawFragment(giftFamilyDrawFragment.value, giftNum)
                }
                for (i in 0..<drawTimes) {
                    if (!familyDraw()) {
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("familyDrawTask 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyDrawTask err:",t)
        }
    }

    private fun giftFamilyDrawFragment(giftUserId: String?, giftNum: Int) {
        try {
            val jo = JSONObject(AntFarmRpcCall.giftFamilyDrawFragment(giftUserId, giftNum))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("亲密家庭🏠赠送扭蛋碎片#" + giftNum + "个#" + giftUserId)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "giftFamilyDrawFragment err:",t)
        }
    }

    private fun familyDrawListFarmTask(): JSONArray? {
        try {
            val jo = JSONObject(AntFarmRpcCall.familyDrawListFarmTask())
            if (ResChecker.checkRes(TAG, jo)) {
                return jo.getJSONArray("farmTaskList")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyDrawListFarmTask err:",t)
        }
        return null
    }

    /**
     * 家庭扭蛋抽奖
     * @return 是否还有剩余抽奖次数
     */
    private fun familyDraw(): Boolean {
        try {
            val jo = JSONObject(AntFarmRpcCall.familyDraw())
            if (ResChecker.checkRes(TAG, jo)) {
                val familyDrawPrize = jo.getJSONObject("familyDrawPrize")
                val title = familyDrawPrize.optString("title")
                val awardCount = familyDrawPrize.getString("awardCount")
                val familyDrawTimes = jo.optInt("familyDrawTimes")
                Log.farm("开扭蛋🎟️抽中[$title]#[$awardCount]")
                return familyDrawTimes != 0
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("familyDraw 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyDraw err:",t)
        }
        return false
    }

    private suspend fun familyEatTogether(
        eatTogetherConfig: JSONObject,
        familyInteractActions: JSONArray,
        friendUserIds: MutableList<String?>
    ) {
        try {
            var isEat = false
            val periodItemList = eatTogetherConfig.getJSONArray("periodItemList")
            if (Objects.isNull(periodItemList) || periodItemList.length() <= 0) {
                return
            }
            if (!Objects.isNull(familyInteractActions) && familyInteractActions.length() > 0) {
                for (i in 0..<familyInteractActions.length()) {
                    val familyInteractAction = familyInteractActions.getJSONObject(i)
                    if ("EatTogether" == familyInteractAction.optString("familyInteractType")) {
                        return
                    }
                }
            }
            var periodName = ""
            val currentTime = Calendar.getInstance()
            for (i in 0..<periodItemList.length()) {
                val periodItem = periodItemList.getJSONObject(i)
                val startHour = periodItem.optInt("startHour")
                val startMinute = periodItem.optInt("startMinute")
                val endHour = periodItem.optInt("endHour")
                val endMinute = periodItem.optInt("endMinute")
                val startTime = Calendar.getInstance()
                startTime.set(Calendar.HOUR_OF_DAY, startHour)
                startTime.set(Calendar.MINUTE, startMinute)
                val endTime = Calendar.getInstance()
                endTime.set(Calendar.HOUR_OF_DAY, endHour)
                endTime.set(Calendar.MINUTE, endMinute)
                if (currentTime.after(startTime) && currentTime.before(endTime)) {
                    periodName = periodItem.optString("periodName")
                    isEat = true
                    break
                }
            }
            if (!isEat) {
                return
            }
            if (Objects.isNull(friendUserIds) || friendUserIds.isEmpty()) {
                return
            }
            val array = queryRecentFarmFood(friendUserIds.size) ?: return
            val friendUserIdList = JSONArray()
            for (userId in friendUserIds) {
                friendUserIdList.put(userId)
            }
            val jo =
                JSONObject(AntFarmRpcCall.familyEatTogether(familyGroupId, friendUserIdList, array))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("庄园家庭🏠" + periodName + "请客#消耗美食" + friendUserIdList.length() + "份")
                syncFamilyStatusIntimacy(familyGroupId)
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("familyEatTogether 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyEatTogether err:",t)
        }
    }

    private fun familyDrawSignReceiveFarmTaskAward(taskId: String?, title: String?) {
        try {
            val jo = JSONObject(AntFarmRpcCall.familyDrawSignReceiveFarmTaskAward(taskId))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("亲密家庭🏠扭蛋任务#$title#奖励领取成功")
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("familyDrawSignReceiveFarmTaskAward 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyDrawSignReceiveFarmTaskAward err:",t)
        }
    }

    private fun queryRecentFarmFood(queryNum: Int): JSONArray? {
        try {
            val jo = JSONObject(AntFarmRpcCall.queryRecentFarmFood(queryNum))
            if (!ResChecker.checkRes(TAG, jo)) {
                return null
            }
            val cuisines = jo.getJSONArray("cuisines")
            if (Objects.isNull(cuisines) || cuisines.length() == 0) {
                return null
            }
            var count = 0
            for (i in 0..<cuisines.length()) {
                count += cuisines.getJSONObject(i).optInt("count")
            }
            if (count >= queryNum) {
                return cuisines
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("queryRecentFarmFood 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryRecentFarmFood err:",t)
        }
        return null
    }

    private fun familyFeedFriendAnimal(animals: JSONArray) {
        try {
            for (i in 0..<animals.length()) {
                val animal = animals.getJSONObject(i)
                val animalStatusVo = animal.getJSONObject("animalStatusVO")
                if (AnimalInteractStatus.HOME.name == animalStatusVo.getString("animalInteractStatus") && AnimalFeedStatus.HUNGRY.name == animalStatusVo.getString(
                        "animalFeedStatus"
                    )
                ) {
                    val groupId = animal.getString("groupId")
                    val farmId = animal.getString("farmId")
                    val userId = animal.getString("userId")
                    if (FriendGuard.shouldSkipFriend(userId, TAG, "庄园家庭帮喂")) {
                        continue
                    }
                    if (Status.hasFlagToday(StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT)) {
                        Log.farm("今日喂鸡次数已达上限🥣")
                        return
                    }
                    val jo = JSONObject(AntFarmRpcCall.feedFriendAnimal(farmId, groupId))
                    val resultCode = jo.optString("resultCode")
                    val memo = jo.optString("memo")
                    if ("388" == resultCode || memo.contains("小鸡太小")) {
                        Log.farm("庄园家庭🏠帮喂好友🥣[${UserMap.getMaskName(userId)}]跳过：小鸡太小，暂不能投喂")
                        continue
                    }
                    if (ResChecker.checkRes(TAG, jo)) {
                        val feedFood: Int = foodStock - jo.getInt("foodStock")
                        if (feedFood > 0) {
                            add2FoodStock(-feedFood)
                        }
                        Log.farm("庄园家庭🏠帮喂好友🥣[" + UserMap.getMaskName(userId) + "]的小鸡[" + feedFood + "g]#剩余" + foodStock + "g")
                    } else {
                        if ("391" == resultCode || memo.contains("今日帮喂次数已达上限")) {
                            Status.setFlagToday(StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT)
                            Log.farm("庄园家庭🏠帮喂好友🥣今日次数已达上限，已记录为当日限制")
                            return
                        }
                        Log.farm("庄园家庭🏠帮喂好友失败: $jo")
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出，不能吞掉
             Log.farm("familyFeedFriendAnimal 协程被取消")
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyFeedFriendAnimal err:",t)
        }
    }

    /**
     * 点击领取活动食物
     * @param gift 礼物信息对象
     */
    private  fun clickForGiftV2(gift: JSONObject?) {
        if (gift == null) return
        try {
            val resultJson = JSONObject(
                AntFarmRpcCall.clickForGiftV2(
                    gift.getString("foodType"),
                    gift.getInt("giftIndex")
                )
            )
            if (ResChecker.checkRes(TAG, resultJson)) {
                Log.farm("领取活动食物成功," + "已领取" + resultJson.optInt("foodCount"))
            }
        }  catch (e: Exception) {
            Log.printStackTrace(TAG, "clickForGiftV2 err:",e)
        }
    }

    internal class AntFarmFamilyOption(i: String, n: String) : MapperEntity() {
        init {
            id = i
            name = n
        }

        companion object {
            val antFarmFamilyOptions: MutableList<AntFarmFamilyOption?>
                get() {
                    val list: MutableList<AntFarmFamilyOption?> =
                        ArrayList()
                    list.add(AntFarmFamilyOption("familySign", "每日签到"))
                    list.add(AntFarmFamilyOption("eatTogetherConfig", "请吃美食"))
                    list.add(AntFarmFamilyOption("feedFamilyAnimal", "帮喂小鸡"))
                    list.add(AntFarmFamilyOption("deliverMsgSend", "道早安"))
                    list.add(AntFarmFamilyOption("familyClaimReward", "领取奖励"))
                    list.add(AntFarmFamilyOption("familyDonateStep", "运动公益捐步"))
                    list.add(AntFarmFamilyOption("shareToFriends", "好友分享"))
                    list.add(AntFarmFamilyOption("sleepTogether", "一起睡觉"))
                    list.add(AntFarmFamilyOption("assignRights", "使用顶梁柱特权"))
                    list.add(AntFarmFamilyOption("familyDrawInfo", "开扭蛋"))
                    list.add(AntFarmFamilyOption("batchInviteP2P", "串门送扭蛋"))
                    list.add(AntFarmFamilyOption("ExchangeFamilyDecoration", "兑换装修物品"))
                    return list
                }
        }
    }

    companion object {
        internal val TAG: String = AntFarm::class.java.getSimpleName()
        private val objectMapper = ObjectMapper()

        @JvmField
        var instance: AntFarm? = null

        /**
         * 小鸡饲料g
         */
        @JvmField
        internal var foodStock: Int = 0

        @JvmField
        var foodStockLimit: Int = 0

        // 抽抽乐 / 广告任务使用的 referToken（从 VipDataIdMap 读取并缓存）
        private var antFarmReferToken: String? = null

        /**
         * 加载农场抽抽乐广告 referToken
         *
         * AntFarmReferToken：
         *  - 如果本地已有缓存，直接返回
         *  - 否则从 VipDataIdMap 加载当前账号下保存的 AntFarmReferToken
         */
        @JvmStatic
        fun loadAntFarmReferToken(): String? {
            if (!antFarmReferToken.isNullOrEmpty()) return antFarmReferToken
            val uid = UserMap.currentUid
            val vipData = IdMapManager.getInstance(VipDataIdMap::class.java)
            vipData.load(uid)
            antFarmReferToken = vipData.get("AntFarmReferToken")
            return antFarmReferToken
        }

        init {
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        private const val RPC_LIST_FARM_TOOL = "com.alipay.antfarm.listFarmTool"

        private const val FARM_ANSWER_CACHE_KEY = "farmAnswerQuestionCache"
    }

    /**
     * 手动触发遣返小鸡
     */
    fun manualSendBackAnimal() {
        try {
            Log.farm("🚀 开始执行手动遣返小鸡任务...")
            // 必须先进入农场获取最新 animal 数据
            if (enterFarm() != null) {
                sendBackAnimal()
                Log.farm("✅ 手动遣返指令执行完毕")
            } else {
                Log.farm("❌ 进入农场失败，无法执行遣返")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "manualSendBackAnimal 异常:", t)
        }
    }
    /**
     * 手动执行庄园游戏改分逻辑（供 ManualTask 调用）
     */
    suspend fun manualFarmGameLogic() {
        try {
            Log.farm("开始执行手动游戏改分任务...")
            if (enterFarm() != null) {
                // 同步最新状态后执行原有逻辑
                syncAnimalStatus(ownerFarmId)
                val foodStockThreshold = foodStockLimit - (gameRewardMax?.value ?: 0)
                if (foodStock < foodStockThreshold) {
                    receiveFarmAwards()
                }
                FarmGame.playAllFarmGames()
                Log.farm("手动游戏改分任务处理完毕")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "manualFarmGameLogic err:", t)
        }
    }
    /**
     * 手动执行庄园抽抽乐逻辑（供 ManualTask 调用）
     */
    fun manualChouChouLeLogic() {
        try {
            Log.farm("🚀 开始执行手动抽抽乐任务...")
            if (enterFarm() != null) {
                ChouChouLe().chouchoule()
                Log.farm("✅ 手动抽抽乐任务处理完毕")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "manualChouChouLeLogic 异常:", t)
        }
    }

    fun manualUseSpecialFood(count: Int) {
        try {
            if (count <= 0) {
                Log.farm("⚠️ 手动使用特殊美食已拦截：必须指定大于0的使用次数")
                return
            }

            Log.farm("🚀 开始执行手动使用特殊美食任务，目标数量: $count")
            val jo = enterFarm()
            if (jo != null) {
                val cuisineList = jo.getJSONArray("cuisineList")
                AntFarmRpcCall.queryLoveCabin(UserMap.currentUid)
                syncAnimalStatus(ownerFarmId)

                if (AnimalFeedStatus.SLEEPY.name == ownerAnimal.animalFeedStatus) {
                    Log.farm("❌ 小鸡正在睡觉，无法使用美食")
                } else {
                    useSpecialFood(cuisineList, count)
                    Log.farm("✅ 手动使用特殊美食任务处理完毕")
                }
            } else {
                Log.farm("❌ 进入庄园失败，无法执行任务")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "manualUseSpecialFood 异常:", t)
        }
    }

    /**
     * 手动使用庄园道具
     * @param toolType 道具类型：BIG_EATER_TOOL, NEWEGGTOOL, FENCETOOL
     * @param toolCount 使用数量（仅 NEWEGGTOOL 有效）
     */
    fun manualUseFarmTool(toolType: String, toolCount: Int) {
        try {
            if (enterFarm() != null) {
                syncAnimalStatus(ownerFarmId)
                Log.farm("开始执行手动使用道具: $toolType, 计划数量: $toolCount")
                val farmTools = listFarmTool()
                if (farmTools == null || farmTools.isEmpty()) {
                    Log.farm("❌ 获取道具列表失败或道具库为空")
                    return
                }

                val tool = farmTools.find { it.toolType?.name == toolType }
                if (tool == null) {
                    Log.farm("❌ 道具库中没有道具: $toolType")
                    return
                }
                if (toolType == "FENCETOOL" && hasFence) {
                    Log.farm("❌ 手动执行拦截：篱笆卡效果正在生效中")
                    return
                }

                Log.farm("当前道具 [${tool.toolType?.nickName()}] 余量: ${tool.toolCount}")

                val actualCount = if (toolType == "NEWEGGTOOL") {
                    if (tool.toolCount < toolCount) {
                        Log.farm("⚠️ 道具余量不足，将用完剩余的 ${tool.toolCount} 个")
                        tool.toolCount
                    } else {
                        toolCount
                    }
                } else {
                    1 // 其他道具默认使用1次
                }

                if (actualCount <= 0) {
                    Log.farm("❌ 可用数量为0，终止操作")
                    return
                }

                for (index in 0 until actualCount) {
                    if (Thread.currentThread().isInterrupted) break

                    val res = AntFarmRpcCall.useFarmTool(ownerFarmId, tool.toolId, tool.toolType?.name)
                    val jo = JSONObject(res)
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.farm("手动使用道具 [${tool.toolType?.nickName()}] 成功 (${index + 1}/$actualCount)")
                    } else {
                        val msg = jo.optString("memo", "未知错误")
                        Log.farm("❌ 使用道具失败: $msg")
                        break
                    }
                    // 使用多个时稍微延迟，避免过快
                    if (actualCount > 1 && index < actualCount - 1) {
                        CoroutineUtils.sleepCompat(1000)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.farm("❌ manualUseFarmTool 出错: ${t.message}")
            Log.printStackTrace(t)
        }
    }
}

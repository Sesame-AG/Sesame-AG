package io.github.aoguai.sesameag.task.antForest

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.data.RuntimeInfo
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.data.Statistics
import io.github.aoguai.sesameag.entity.CollectEnergyEntity
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.entity.KVMap
import io.github.aoguai.sesameag.entity.OtherEntityProvider.listEcoLifeOptions
import io.github.aoguai.sesameag.entity.OtherEntityProvider.listHealthcareOptions
import io.github.aoguai.sesameag.entity.VitalityStore
import io.github.aoguai.sesameag.entity.VitalityStore.Companion.getNameById
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.hook.HookReadyChecker
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.hook.RequestManager.requestString
import io.github.aoguai.sesameag.hook.Toast
import io.github.aoguai.sesameag.hook.rpc.intervallimit.FixedOrRangeIntervalLimit
import io.github.aoguai.sesameag.hook.rpc.intervallimit.IntervalLimit
import io.github.aoguai.sesameag.hook.rpc.intervallimit.MinIntervalLimit
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
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowDecision
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.task.antFarm.AntFarmRpcCall
import io.github.aoguai.sesameag.task.antFarm.FarmGame
import io.github.aoguai.sesameag.task.exchange.ExchangeCost
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectCatalog
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectNeed
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectTag
import io.github.aoguai.sesameag.task.exchange.ExchangeItem
import io.github.aoguai.sesameag.task.exchange.ExchangeLimit
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionRow
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsCache
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenishResult
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenisher
import io.github.aoguai.sesameag.task.exchange.ExchangeSafety
import io.github.aoguai.sesameag.task.antForest.ForestUtil.hasBombCard
import io.github.aoguai.sesameag.task.antForest.ForestUtil.hasShield
import io.github.aoguai.sesameag.task.antForest.Privilege.studentSignInRedEnvelope
import io.github.aoguai.sesameag.task.antForest.Privilege.youthPrivilege
import io.github.aoguai.sesameag.ui.ObjReference
import io.github.aoguai.sesameag.util.Average
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Notify.updateRunningLastExec
import io.github.aoguai.sesameag.util.Notify.updateRunningStatus
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.TimeCounter
import io.github.aoguai.sesameag.util.TimeFormatter
import io.github.aoguai.sesameag.util.TimeTriggerDecision
import io.github.aoguai.sesameag.util.TimeTriggerEvaluator
import io.github.aoguai.sesameag.util.TimeTriggerParseOptions
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.friend.FriendCapabilityRecorder
import io.github.aoguai.sesameag.util.friend.FriendRepository
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.VitalityRewardsMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.Objects
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.min

/**
 * 蚂蚁森林V2
 */
class AntForest : ModelTask(), EnergyCollectCallback {
    private val taskCount = AtomicInteger(0)
    private val forestTaskBlacklistModule = "蚂蚁森林"

    private var selfId: String? = null

    @Volatile
    private var rebornWeeklyCompleted: Boolean = false

    @Volatile
    private var rebornConfigSignature: String = ""

    private val rebornScanFoundProtectable = AtomicBoolean(false)
    private val rebornScanLimitReached = AtomicBoolean(false)
    private val rebornScanCheckedAnyCandidate = AtomicBoolean(false)
    private var tryCountInt: Int? = null
    private var retryIntervalInt: Int? = null
    private var collectIntervalEntity: IntervalLimit? = null
    private var doubleCollectIntervalEntity: IntervalLimit? = null

    /**
     * 双击卡结束时间
     */
    @Volatile
    private var doubleEndTime: Long = 0

    /**
     * 隐身卡结束时间
     */
    @Volatile
    private var stealthEndTime: Long = 0

    /**
     * 保护罩结束时间
     */
    @Volatile
    private var shieldEndTime: Long = 0

    /**
     * 炸弹卡结束时间
     */
    @Volatile
    private var energyBombCardEndTime: Long = 0

    /**
     * 收好友N倍卡结束时间
     */
    @Volatile
    private var robMultiplierCardEndTime: Long = 0

    @Volatile
    private var robMultiplierCardFactor: Double = 0.0

    @Volatile
    private var robMultiplierCardPropType: String = ""

    @Volatile
    private var robMultiplierCardPropName: String = ""

    private val delayTimeMath = Average(5)
    private val collectEnergyLockLimit = ObjReference(0L)
    private val doubleCardLockObj = Any()

    // 并发控制信号量，限制同时处理的好友数量，避免过多并发导致性能问题
    private var concurrencyLimiter = Semaphore(FRIEND_PROCESS_CONCURRENCY)
    private var friendProcessConcurrencyInt: Int = FRIEND_PROCESS_CONCURRENCY

    private var collectEnergy: BooleanModelField? = null // 收集能量开关
    internal var pkEnergy: BooleanModelField? = null // PK能量开关
    internal var energyPvpChallenge: BooleanModelField? = null // 1V1能量挑战赛开关
    internal var energyRain: BooleanModelField? = null // 能量雨开关
    private var tryCount: IntegerModelField? = null // 尝试收取次数
    private var retryInterval: IntegerModelField? = null // 重试间隔（毫秒）
    private var dontCollectList: FriendSelectionModelField? = null // 不收取能量的用户列表
    internal var collectWateringBubble: BooleanModelField? = null // 收取浇水金球开关
    private var batchRobEnergy: BooleanModelField? = null // 批量收取能量开关
    private var takeLookEnergy: BooleanModelField? = null // 找能量开关
    private var collectSelfEnergyType: ChoiceModelField? = null // 收自己能量方式
    private var collectSelfEnergyThreshold: IntegerModelField? = null // 收自己能量阈值
    private var robMultiplierCollectLimit: IntegerModelField? = null // 领取N倍卡能量阈值
    private var collectBombEnergyLimit: IntegerModelField? = null // 炸弹能量收取阈值
    private var balanceNetworkDelay: BooleanModelField? = null // 平衡网络延迟开关
    var whackMoleMode: ChoiceModelField? = null // 6秒拼手速开关
    var whackMoleTime: TimePointModelField? = null // 6秒拼手速执行时间

    val whackMoleModeNames = arrayOf("关闭", "开启")
    internal var collectProp: BooleanModelField? = null // 收集道具开关
    private var queryInterval: StringModelField? = null // 查询间隔时间
    private var collectInterval: StringModelField? = null // 收取间隔时间
    private var doubleCollectInterval: StringModelField? = null // 双击间隔时间
    private var friendProcessConcurrency: IntegerModelField? = null // 好友处理并发数
    private var doubleCard: ChoiceModelField? = null // 双击卡类型选择
    private var doubleCardTime: TimeTriggerModelField? = null // 双击卡使用时间列表
    var doubleCountLimit: IntegerModelField? = null // 双击卡使用次数限制
    private var doubleCardConstant: BooleanModelField? = null // 双击卡永动机开关

    private var stealthCard: ChoiceModelField? = null // 隐身卡
    private var stealthCardConstant: BooleanModelField? = null // 隐身卡永动机开关
    private var shieldCard: ChoiceModelField? = null // 保护罩
    private var shieldCardConstant: BooleanModelField? = null // 保护罩永动机开关
    private var helpFriendCollectType: ChoiceModelField? = null
    private var helpFriendCollectList: FriendSelectionModelField? = null
    private var helpFriendCollectListLimit: IntegerModelField? = null

    // 显示背包内容
    private var showBagList: BooleanModelField? = null

    private var vitalityExchangeList: SelectAndCountModelField? = null
    private var returnWater33: IntegerModelField? = null
    private var returnWater18: IntegerModelField? = null
    private var returnWater10: IntegerModelField? = null
    internal var receiveForestTaskAward: BooleanModelField? = null
    private var waterFriendList: FriendSelectionCountModelField? = null
    private var waterFriendCount: IntegerModelField? = null
    private var notifyFriend: BooleanModelField? = null
    internal var vitalityExchange: BooleanModelField? = null
    internal var userPatrol: BooleanModelField? = null
    private var collectGiftBox: BooleanModelField? = null
    internal var medicalHealth: BooleanModelField? = null //医疗健康开关
    internal var forestMarket: BooleanModelField? = null
    internal var combineAnimalPiece: BooleanModelField? = null
    private var consumeAnimalProp: BooleanModelField? = null
    private var whoYouWantToGiveTo: FriendSelectionModelField? = null
    internal var dailyCheckIn: BooleanModelField? = null //青春特权签到
    private var bubbleBoostCard: ChoiceModelField? = null //加速卡
    internal var youthPrivilege: BooleanModelField? = null //青春特权 森林道具
    internal var ecoLife: BooleanModelField? = null
    internal var ecoLifeTime: TimePointModelField? = null // 绿色行动执行时间
    internal var giveProp: BooleanModelField? = null

    private var robMultiplierCard: ChoiceModelField? = null // 收好友N倍卡
    private var robMultiplierCardTime: TimeTriggerModelField? = null // 收好友N倍卡时间

    private var cycleinterval: IntegerModelField? = null
    internal var energyRainChance: BooleanModelField? = null
    internal var energyRainTime: TimePointModelField? = null // 能量雨执行时间

    /**
     * 能量炸弹卡
     */
    private var energyBombCardType: ChoiceModelField? = null

    /**
     * 用户名缓存：userId -> userName 的映射
     */
    private val userNameCache: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    /**
     * 已处理用户缓存：记录本轮已处理过的用户ID，避免重复处理
     */
    private val processedUsersCache: ConcurrentHashMap.KeySetView<String, Boolean> = ConcurrentHashMap.newKeySet()

    /**
     * 空森林缓存，用于记录在本轮任务中已经确认没有能量的好友。
     * 在每轮蚂蚁森林任务开始时清空（见run方法finally块）。
     * “一轮任务”通常指由"执行间隔"触发的一次完整的好友遍历。
     */
    private val emptyForestCache: ConcurrentHashMap<String, Long> = ConcurrentHashMap<String, Long>()

    /**
     * 跳过用户缓存，用于记录有保护罩或其他需要跳过的用户
     * Key: 用户ID，Value: 跳过原因（如"baohuzhao"表示有保护罩）
     */
    private val skipUsersCache: ConcurrentHashMap<String, String> = ConcurrentHashMap<String, String>()

    /**
     * 好友主页缓存：记录本轮已查询到的好友主页，减少 takeLook / 排行榜 / 蹲点之间的重复请求
     */
    private val friendHomeCache: ConcurrentHashMap<String, JSONObject> = ConcurrentHashMap<String, JSONObject>()

    /**
     * 本轮已处理附加收益的好友集合，避免礼盒/复活能量重复执行
     */
    private val handledGiftBoxUsers: ConcurrentHashMap.KeySetView<String, Boolean> = ConcurrentHashMap.newKeySet()
    private val handledProtectUsers: ConcurrentHashMap.KeySetView<String, Boolean> = ConcurrentHashMap.newKeySet()

    internal var forestChouChouLe: BooleanModelField? = null //森林抽抽乐

    /**
     * 加速器定时
     */
    private var bubbleBoostTime: TimeTriggerModelField? = null

    private val forestTaskTryCount: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap<String, AtomicInteger>()
    private var lastPatrolId: Int = 0

    private var jsonCollectMap: Set<String> = emptySet()

    var emojiList: ArrayList<String> = ArrayList(
        listOf(
            "🍅", "🍓", "🥓", "🍂", "🍚", "🌰", "🟢", "🌴",
            "🥗", "🧀", "🥩", "🍍", "🌶️", "🍲", "🍆", "🥕",
            "✨", "🍑", "🍘", "🍀", "🥞", "🍈", "🥝", "🧅",
            "🌵", "🌾", "🥜", "🍇", "🌭", "🥑", "🥐", "🥖",
            "🍊", "🌽", "🍉", "🍖", "🍄", "🥚", "🥙", "🥦",
            "🍌", "🍱", "🍏", "🍎", "🌲", "🌿", "🍁", "🍒",
            "🥔", "🌯", "🌱", "🍐", "🍞", "🍳", "🍙", "🍋",
            "🍗", "🌮", "🍃", "🥘", "🥒", "🧄", "🍠", "🥥"
        )
    )
    private val random = Random()

    private var cachedBagObject: JSONObject? = null
    private var lastQueryPropListTime: Long = 0
    private var lastUsePropCheckTime: Long = 0
    private var roundPropCheckState: PropCheckRoundState? = null
    private val forestGameCenterRecentAppRecords = linkedMapOf<String, Long>()

    private data class PropCheckRoundState(
        val latestHome: JSONObject?
    )

    private data class RobMultiplierCardState(
        val propName: String,
        val propType: String,
        val factor: Double,
        val endTime: Long
    )

    private data class RobMultiplierCandidate(
        val prop: JSONObject,
        val propName: String,
        val propType: String,
        val factor: Double,
        val durationMs: Long,
        val expireTime: Long
    )

    // {{ 新增接口定义：收自己能量的方式 }}
    interface CollectSelfType {
        companion object {
            const val ALL = 0
            const val OVER_THRESHOLD = 1
            const val BELOW_THRESHOLD = 2
            val nickNames = arrayOf("所有", "大于阈值", "小于阈值")
        }
    }

    override fun getName(): String {
        return "蚂蚁森林"
    }

    override fun getGroup(): ModelGroup {
        return ModelGroup.FOREST
    }

    override fun getIcon(): String {
        return "AntForest.png"
    }

    interface ApplyPropType {
        companion object {
            const val CLOSE: Int = 0
            const val ALL: Int = 1
            const val ONLY_LIMIT_TIME: Int = 2
            val nickNames: Array<String?> = arrayOf<String?>("关闭", "所有道具", "限时道具")
        }
    }

    interface HelpFriendCollectType {
        companion object {
            const val NONE: Int = 0
            const val HELP: Int = 1
            const val EXCLUDE: Int = 2
            val nickNames: Array<String?> = arrayOf("关闭", "选中复活", "选中不复活")
        }
    }

    internal fun isCollectEnergyEnabled(): Boolean {
        return collectEnergy?.value == true
    }

    internal fun isTakeLookEnergyEnabled(): Boolean {
        return isCollectEnergyEnabled() && takeLookEnergy?.value != false
    }

    private fun hasRebornProtectWorkEnabled(): Boolean {
        val type = helpFriendCollectType?.value ?: HelpFriendCollectType.NONE
        if (type == HelpFriendCollectType.NONE || rebornWeeklyCompleted) {
            return false
        }
        if (type == HelpFriendCollectType.HELP && helpFriendCollectList?.isEmpty() != false) {
            return false
        }
        return true
    }

    internal fun hasFriendRankingWorkEnabled(): Boolean {
        return isCollectEnergyEnabled()
    }

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            BooleanModelField(
                "collectEnergy",
                "收集能量 | 开启",
                false
            ).withDesc("开启后收取自己、好友和可访问 PK 森友的可见绿色能量。").also { collectEnergy = it })
        modelFields.addField(
            BooleanModelField(
                "batchRobEnergy",
                "收集能量 | 一键收取",
                false
            ).withDesc("开启后在好友、PK好友页面收取多个成熟能量球时优先使用一键收取 RPC。").also { batchRobEnergy = it })
        modelFields.addField(
            BooleanModelField(
                "takeLookEnergy",
                "收集能量 | 是否启用找能量",
                true
            ).withDesc("关闭后跳过找能量接口，直接按现有流程继续好友与PK好友主页遍历。").also { takeLookEnergy = it })
        modelFields.addField(
            BooleanModelField(
                "pkEnergy",
                "能量PK榜 | 开启",
                false
            ).withDesc("开启后在赛季期间补充访问能量 PK 榜森友并收取可见能量。").also { pkEnergy = it })
        modelFields.addField(
            BooleanModelField(
                "energyPvpChallenge",
                "能量挑战赛 | 开启",
                false
            ).withDesc("自动查询 1V1 能量挑战赛状态，并领取已结算的能量和勋章奖励。").also { energyPvpChallenge = it })
        modelFields.addField(
            ChoiceModelField(
                "whackMoleMode",
                "6秒拼手速 | 开启",
                0, // 默认值为 0 (关闭)
                whackMoleModeNames
            ).withDesc("开启后按服务端返回的地鼠列表执行一次 6 秒拼手速。").also { whackMoleMode = it }
        )
        modelFields.addField(
            TimePointModelField(
                "whackMoleTime",
                "6秒拼手速 | 执行时间",
                "-1",
                allowDisable = true
            ).withDesc("限制 6 秒拼手速开始执行的时间；填 -1 关闭时间触发限制。").also { whackMoleTime = it }
        )
        modelFields.addField(
            BooleanModelField(
                "energyRain",
                "能量雨 | 开启",
                false
            ).withDesc("开启后自动进入能量雨并收集当天可用机会。").also { energyRain = it })
        modelFields.addField(
            TimePointModelField(
                "energyRainTime",
                "能量雨 | 执行时间",
                "-1",
                allowDisable = true
            ).withDesc("限制能量雨开始时间；填 -1 关闭时间触发限制。").also { energyRainTime = it })
        modelFields.addField(
            ChoiceModelField(
                "CollectSelfEnergyType",
                "收集能量 | 自家能量策略",
                CollectSelfType.ALL,
                CollectSelfType.nickNames
            ).withDesc("选择自家单个能量球的收取策略。需开启“收集能量 | 开启”。").also { collectSelfEnergyType = it }
        )
        modelFields.addField(
            IntegerModelField(
                "CollectSelfEnergyThreshold",
                "收集能量 | 自家能量阈值(g)",
                0,
                0,
                10000
            ).withDesc("自家能量策略选择阈值模式时使用；达到该克数才收取。").also { collectSelfEnergyThreshold = it }
        )
        modelFields.addField(
            IntegerModelField(
                "robExpandCardLimt",
                "收好友N倍卡 | 额外能量领取阈值(g)",
                1,
                1,
                20000
            ).withDesc("当 N 倍卡产生的可领取额外能量达到该克数时才自动领取，避免零碎收益。").also { robMultiplierCollectLimit = it }
        )

        modelFields.addField(
            IntegerModelField(
                "CollectBombEnergyLimit",
                "能量炸弹卡 | 收取阈值(g)",
                0,
                0,
                100000
            ).withDesc("好友挂炸弹卡时，单个能量球达到该值才尝试收取；0 表示不额外放宽。").also { collectBombEnergyLimit = it }
        )
        modelFields.addField(
            FriendSelectionModelField(
                "dontCollectList",
                "收集能量 | 不收好友列表"
            ).withDesc("这些好友不参与自动收取能量。").also {
                dontCollectList = it
            })
        modelFields.addField(
            FriendSelectionModelField(
                "giveEnergyRainList",
                "能量雨 | 赠送好友"
            ).withDesc("把当天可赠送的能量雨机会优先赠送给这些好友。").also {
                giveEnergyRainList = it
            })
        modelFields.addField(
            BooleanModelField(
                "energyRainChance",
                "能量雨 | 使用机会道具",
                false
            ).withDesc("开启后自动使用能量雨机会道具，机会当天有效且不跨天累计。").also { energyRainChance = it })
        modelFields.addField(
            BooleanModelField(
                "collectWateringBubble",
                "浇水金球 | 收取",
                false
            ).withDesc("自动领取浇水金球、复活金球和保护回赠能量。").also { collectWateringBubble = it })
        modelFields.addField(
            ChoiceModelField(
                "doubleCard",
                "双击卡 | 消耗类型",
                ApplyPropType.CLOSE,
                ApplyPropType.nickNames
            ).withDesc("配置双击卡的自动使用策略。").also { doubleCard = it })
        modelFields.addField(
            IntegerModelField(
                "doubleCountLimit",
                "双击卡 | 使用次数",
                6
            ).withDesc("每天最多使用双击卡的次数。").also { doubleCountLimit = it })
        modelFields.addField(
            TimeTriggerModelField(
                "doubleCardTime",
                "双击卡 | 使用时间/范围",
                "-1",
                TimeTriggerParseOptions(
                    allowCheckpoints = true,
                    allowWindows = true,
                    allowBlockedWindows = false,
                    tag = TAG
                )
            ).withDesc("仅在这些时间点或允许时间段内尝试使用双击卡；支持 HHmm、HHmm-HHmm，填 -1 关闭。").also {
                doubleCardTime = it
            })
        modelFields.addField(
            BooleanModelField(
                "DoubleCardConstant", "双击卡 | 自动兑换限时卡", false
            ).withDesc("背包没有双击卡时，允许按“活力值 | 兑换列表”中已勾选且名称命中的项自动补货。需同时开启“活力值 | 开启兑换”。").also {
                doubleCardConstant = it
            })
        modelFields.addField(
            ChoiceModelField(
                "bubbleBoostCard",
                "时光加速器 | 消耗类型",
                ApplyPropType.CLOSE,
                ApplyPropType.nickNames
            ).withDesc("配置时光加速器的自动使用策略。").also { bubbleBoostCard = it })
        modelFields.addField(
            TimeTriggerModelField(
                "bubbleBoostTime",
                "时光加速器 | 使用时间/禁用时段",
                "-1",
                TimeTriggerParseOptions(
                    allowCheckpoints = true,
                    allowWindows = false,
                    allowBlockedWindows = true,
                    tag = TAG
                )
            ).withDesc("按这些时间点尝试使用加速卡，并可用 !HHmm-HHmm 配置禁止窗口；未到时间会挂定时任务。").also {
                bubbleBoostTime = it
            })
        modelFields.addField(
            ChoiceModelField(
                "shieldCard",
                "能量保护罩 | 消耗类型",
                ApplyPropType.CLOSE,
                ApplyPropType.nickNames
            ).withDesc("配置能量保护罩的自动使用策略。").also { shieldCard = it })
        modelFields.addField(
            BooleanModelField(
                "shieldCardConstant",
                "能量保护罩 | 自动兑换限时卡",
                false
            ).withDesc("背包没有保护罩时，允许按“活力值 | 兑换列表”中已勾选且名称命中的项自动补货。需同时开启“活力值 | 开启兑换”。").also {
                shieldCardConstant = it
            })

        modelFields.addField(
            ChoiceModelField(
                "energyBombCardType", "能量炸弹卡 | 消耗类型", ApplyPropType.CLOSE,
                ApplyPropType.nickNames, "若开启了保护罩，则不会使用炸弹卡"
            ).withDesc("配置能量炸弹卡的自动使用策略；开启保护罩时不会使用炸弹卡。").also { energyBombCardType = it })
        modelFields.addField(
            ChoiceModelField(
                "robExpandCard",
                "收好友N倍卡 | 消耗类型",
                ApplyPropType.CLOSE,
                ApplyPropType.nickNames
            ).withDesc("配置收好友 N 倍卡的自动使用策略。").also { robMultiplierCard = it })
        // 收好友N倍卡时间
        modelFields.addField(
            TimeTriggerModelField(
                "robExpandCardTime",
                "收好友N倍卡 | 使用时间/范围",
                "-1",
                TimeTriggerParseOptions(
                    allowCheckpoints = true,
                    allowWindows = true,
                    allowBlockedWindows = false,
                    tag = TAG
                )
            ).withDesc("仅在这些时间点或允许时间段内尝试使用非限时收好友 N 倍卡；支持 HHmm、HHmm-HHmm，填 -1 关闭。").also {
                robMultiplierCardTime = it
            }
        )
        modelFields.addField(
            ChoiceModelField(
                "stealthCard",
                "隐身卡 | 消耗类型",
                ApplyPropType.CLOSE,
                ApplyPropType.nickNames
            ).withDesc("配置隐身卡的自动使用策略。").also { stealthCard = it })
        modelFields.addField(
            BooleanModelField(
                "stealthCardConstant",
                "隐身卡 | 自动兑换限时卡",
                false
            ).withDesc("背包没有隐身卡时，允许按“活力值 | 兑换列表”中已勾选且名称命中的项自动补货。需同时开启“活力值 | 开启兑换”。").also {
                stealthCardConstant = it
            })
        modelFields.addField(
            IntegerModelField(
                "returnWater10",
                "回浇 | 10g触发阈值(0关闭)",
                0
            ).withDesc("对同一好友当日收能量达到该值后，才自动回浇 10 克；0 为关闭。").also { returnWater10 = it })
        modelFields.addField(
            IntegerModelField(
                "returnWater18",
                "回浇 | 18g触发阈值(0关闭)",
                0
            ).withDesc("对同一好友当日收能量达到该值后，才自动回浇 18 克；0 为关闭。").also { returnWater18 = it })
        modelFields.addField(
            IntegerModelField(
                "returnWater33",
                "回浇 | 33g触发阈值(0关闭)",
                0
            ).withDesc("对同一好友当日收能量达到该值后，才自动回浇 33 克；0 为关闭。").also { returnWater33 = it })
        modelFields.addField(
            FriendSelectionCountModelField(
                "waterFriendList",
                "浇水 | 好友与次数"
            ).withDesc("配置需要浇水的好友及每日浇水次数。").also { waterFriendList = it })
        modelFields.addField(
            IntegerModelField(
                "waterFriendCount",
                "浇水 | 单次克数(10/18/33/66)",
                66
            ).withDesc("每次给好友浇水的克数。").also { waterFriendCount = it })
        modelFields.addField(
            BooleanModelField(
                "notifyFriend",
                "浇水 | 通知好友",
                false
            ).withDesc("给好友浇水时同时发送通知消息。").also { notifyFriend = it })
        modelFields.addField(
            BooleanModelField(
                "giveProp",
                "森林道具 | 赠送",
                false
            ).withDesc("自动把可赠送的森林道具送给好友。").also { giveProp = it })
        modelFields.addField(
            FriendSelectionModelField(
                "whoYouWantToGiveTo",
                "森林道具 | 赠送好友"
            ).withDesc("选择允许接收森林道具的好友。需开启“森林道具 | 赠送”。").also { whoYouWantToGiveTo = it })
        modelFields.addField(
            BooleanModelField(
                "collectProp",
                "森林道具 | 领取",
                false
            ).withDesc("自动领取森林背包或活动发放的道具。").also { collectProp = it })
        modelFields.addField(
            ChoiceModelField(
                "helpFriendCollectType",
                "复活能量 | 方式",
                HelpFriendCollectType.NONE,
                HelpFriendCollectType.nickNames
            ).withDesc("控制是否帮好友复活过期能量以及名单的解释方式。").also { helpFriendCollectType = it })
        modelFields.addField(
            FriendSelectionModelField(
                "helpFriendCollectList",
                "复活能量 | 好友列表"
            ).withDesc("配置复活能量选项对应好友名单。").also {
                helpFriendCollectList = it
            })
        modelFields.addField(
            IntegerModelField(
                "helpFriendCollectListLimit",
                "复活能量 | 好友能量下限(g)",
                0,
                0,
                100000
            ).withDesc("仅当好友可复活能量大于等于该值时才帮其复活。").also { helpFriendCollectListLimit = it }
        )
        modelFields.addField(BooleanModelField("vitalityExchange", "活力值 | 开启兑换", false).withDesc(
            "自动用活力值兑换已配置的道具或皮肤。"
        ).also { vitalityExchange = it })
        modelFields.addField(
            SelectAndCountModelField(
                "vitalityExchangeList", "活力值 | 兑换列表", LinkedHashMap<String?, Int?>(),
                this::refreshVitalityExchangeOptionsForSettings,
                "记得填兑换次数"
            ).withDesc("配置活力值商店兑换项及每日兑换次数。").also { vitalityExchangeList = it })
        modelFields.addField(BooleanModelField("userPatrol", "保护地巡护 | 开启", false).withDesc(
            "执行保护地巡护，消耗步数机会获取动物碎片。"
        ).also { userPatrol = it })
        modelFields.addField(BooleanModelField("combineAnimalPiece", "保护地巡护 | 合成动物碎片", false).withDesc(
            "自动合成保护地动物碎片。"
        ).also { combineAnimalPiece = it })
        modelFields.addField(BooleanModelField("consumeAnimalProp", "保护地巡护 | 派遣动物伙伴", false).withDesc(
            "派遣动物伙伴出战，领取额外能量收益。"
        ).also { consumeAnimalProp = it })
        modelFields.addField(BooleanModelField("receiveForestTaskAward", "森林任务 | 开启", false).withDesc(
            "执行并领取森林每日任务奖励。"
        ).also { receiveForestTaskAward = it })

        modelFields.addField(BooleanModelField("forestChouChouLe", "森林寻宝 | 开启", false).withDesc(
            "执行森林寻宝活动任务，领取抽奖机会和可领取奖励。"
        ).also { forestChouChouLe = it })

        modelFields.addField(BooleanModelField("collectGiftBox", "好友礼盒 | 领取", false).withDesc(
            "自动领取好友种树后的礼盒奖励。"
        ).also { collectGiftBox = it })

        modelFields.addField(BooleanModelField("medicalHealth", "健康医疗 | 开启", false).withDesc(
            "按已选项目领取健康医疗相关的森林能量奖励。"
        ).also { medicalHealth = it })
        modelFields.addField(
            SelectModelField(
                "medicalHealthOption", "健康医疗 | 项目", LinkedHashSet<String?>(), listHealthcareOptions(),
                "医疗健康需要先完成一次医疗打卡"
            ).also { medicalHealthOption = it })

        modelFields.addField(BooleanModelField("forestMarket", "森林集市", false).withDesc(
            "执行森林集市任务并领取奖励。"
        ).also { forestMarket = it })
        modelFields.addField(BooleanModelField("youthPrivilege", "青春特权 | 森林道具", false).withDesc(
            "领取青春特权中的森林道具奖励。"
        ).also { youthPrivilege = it })
        modelFields.addField(BooleanModelField("studentCheckIn", "青春特权 | 签到红包", false).withDesc(
            "执行青春特权签到红包。"
        ).also { dailyCheckIn = it })
        modelFields.addField(BooleanModelField("ecoLife", "绿色行动 | 开启", false).withDesc(
            "执行绿色行动任务。"
        ).also { ecoLife = it })
        modelFields.addField(
            TimePointModelField(
                "ecoLifeTime",
                "绿色行动 | 执行时间",
                "-1",
                allowDisable = true
            ).withDesc("限制绿色行动开始时间；填 -1 关闭时间触发限制。").also { ecoLifeTime = it }
        )
        modelFields.addField(BooleanModelField("ecoLifeOpen", "绿色行动 | 自动开通", false).withDesc(
            "绿色行动未开通时自动尝试开通后再执行任务。"
        ).also { ecoLifeOpen = it })
        modelFields.addField(
            SelectModelField(
                "ecoLifeOption", "绿色行动 | 选项", LinkedHashSet<String?>(), listEcoLifeOptions(), "光盘行动需要先完成一次光盘打卡"
            ).also { ecoLifeOption = it })

        modelFields.addField(StringModelField.IntervalStringModelField("queryInterval", "查询间隔(毫秒或毫秒范围)", "1000-2000", 10, 10000).withDesc(
            "控制查询主页、排行榜等请求间隔，可填固定值或范围。"
        ).also { queryInterval = it })
        modelFields.addField(StringModelField.IntervalStringModelField("collectInterval", "收取间隔(毫秒或毫秒范围)", "1000-1500", 50, 10000).withDesc(
            "控制实际收取能量请求的间隔，可填固定值或范围。"
        ).also { collectInterval = it })
        modelFields.addField(StringModelField.IntervalStringModelField("doubleCollectInterval", "双击间隔(毫秒或毫秒范围)", "800-2400", 10, 5000).withDesc(
            "控制双击补收时两次收取之间的间隔，可填固定值或范围。"
        ).also { doubleCollectInterval = it })
        modelFields.addField(IntegerModelField("friendProcessConcurrency", "好友处理并发数", FRIEND_PROCESS_CONCURRENCY, 1, 20).withDesc(
            "控制好友森林并发处理数量，范围 1-20。"
        ).also { friendProcessConcurrency = it })
        modelFields.addField(BooleanModelField("balanceNetworkDelay", "平衡网络延迟", false).withDesc(
            "根据实际网络耗时动态平衡收取节奏，减少过快触发风控。"
        ).also { balanceNetworkDelay = it })
        modelFields.addField(IntegerModelField("tryCount", "尝试收取(次数)", 1, 0, 5).withDesc(
            "单次收取失败后的最大重试次数。"
        ).also { tryCount = it })
        modelFields.addField(IntegerModelField("retryInterval", "重试间隔(毫秒)", 1200, 0, 10000).withDesc(
            "单次收取失败后再次尝试的等待时间。"
        ).also { retryInterval = it })
        modelFields.addField(IntegerModelField("cycleinterval", "循环间隔(毫秒)", 5000, 0, 10000).withDesc(
            "只收能量时间段内，每轮循环查找与收取的间隔。"
        ).also { cycleinterval = it })
        modelFields.addField(BooleanModelField("showBagList", "显示背包内容", false).withDesc(
            "任务开始时输出当前森林背包道具清单。"
        ).also { showBagList = it })
        return modelFields
    }

    override fun check(): Boolean {
        if (!super.check()) return false
        val currentTime = System.currentTimeMillis()
        val forestPauseTime = RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime)
        if (forestPauseTime > currentTime) {
            Log.forest(getName() + "任务-异常等待中，暂不执行检测！")
            return false
        }
        return true
    }

    /**
     * 创建区间限制对象
     *
     * @param intervalStr 区间字符串，如 "1000-2000"
     * @param defaultMin 默认最小值
     * @param defaultMax 默认最大值
     * @param description 描述，用于日志
     * @return 区间限制对象
     */
    private fun createSafeIntervalLimit(
        intervalStr: String?,
        defaultMin: Int,
        defaultMax: Int,
        description: String?
    ): FixedOrRangeIntervalLimit {
        // 记录原始输入值
        Log.forest(description + "原始设置值: [" + intervalStr + "]")

        // 使用自定义区间限制类，处理所有边界情况
        val limit = FixedOrRangeIntervalLimit(intervalStr, defaultMin, defaultMax)
        Log.forest(description + "成功创建区间限制")
        return limit
    }

    override fun boot(classLoader: ClassLoader?) {
        super.boot(classLoader)
        instance = this


        // 安全创建各种区间限制
        val queryHomeIntervalLimit = createSafeIntervalLimit(
            queryInterval!!.value, 10, 10000, "查询间隔"
        )
        val friendHomeIntervalLimit = MinIntervalLimit(
            createSafeIntervalLimit(queryInterval!!.value, 10, 10000, "好友主页查询间隔"),
            FRIEND_HOME_MIN_INTERVAL_MS
        )

        // 添加RPC间隔限制
        addIntervalLimit("alipay.antforest.forest.h5.queryHomePage", queryHomeIntervalLimit)
        addIntervalLimit("alipay.antforest.forest.h5.queryFriendHomePage", friendHomeIntervalLimit)
        addIntervalLimit("alipay.antmember.forest.h5.collectEnergy", 300)
        addIntervalLimit("alipay.antmember.forest.h5.queryEnergyRanking", 300)
        addIntervalLimit("alipay.antforest.forest.h5.fillUserRobFlag", 500)

        // 设置其他参数
        tryCountInt = tryCount!!.value
        retryIntervalInt = retryInterval!!.value
        friendProcessConcurrencyInt = (friendProcessConcurrency?.value ?: FRIEND_PROCESS_CONCURRENCY).coerceIn(1, 20)
        concurrencyLimiter = Semaphore(friendProcessConcurrencyInt)

        jsonCollectMap = dontCollectList?.resolvedIds() ?: emptySet()

        // 创建收取间隔实体
        collectIntervalEntity = createSafeIntervalLimit(
            collectInterval!!.value, 50, 10000, "收取间隔"
        )

        // 创建双击收取间隔实体
        doubleCollectIntervalEntity = createSafeIntervalLimit(
            doubleCollectInterval!!.value, 10, 5000, "双击间隔"
        )
        delayTimeMath.clear()


        AntForestRpcCall.init()

        // 设置蹲点管理器的回调
        EnergyWaitingManager.setEnergyCollectCallback(this)
    }

    private fun computeRebornConfigSignature(): String {
        val type = helpFriendCollectType?.value ?: HelpFriendCollectType.NONE
        val limit = helpFriendCollectListLimit?.value ?: 0
        val ids = helpFriendCollectList?.resolvedIds()?.sorted() ?: emptyList()
        var hash = 1
        for (id in ids) {
            hash = 31 * hash + id.hashCode()
        }
        return "t=$type,l=$limit,s=${ids.size},h=$hash"
    }

    private fun initRebornWeeklyState() {
        if (selfId.isNullOrEmpty()) {
            rebornWeeklyCompleted = false
            rebornConfigSignature = ""
            return
        }
        val type = helpFriendCollectType?.value ?: HelpFriendCollectType.NONE
        if (type == HelpFriendCollectType.NONE) {
            rebornWeeklyCompleted = false
            rebornConfigSignature = ""
            return
        }

        rebornConfigSignature = computeRebornConfigSignature()
        var state = RebornEnergyWeeklyPersistence.loadOrInit(rebornConfigSignature)
        if (state.completed && !state.lastScanFoundProtectable && !state.lastScanLimitReached) {
            state = RebornEnergyWeeklyPersistence.updateAfterScan(
                configSignature = rebornConfigSignature,
                scanAt = state.lastScanAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                foundProtectable = state.lastScanFoundProtectable,
                limitReached = state.lastScanLimitReached,
                completed = false
            )
            Log.forest("复活能量：已清理旧版周轮封存状态，恢复本周复活检查")
        }
        rebornWeeklyCompleted = state.completed
        if (rebornWeeklyCompleted) {
            Log.forest("⏭️ 复活能量：本周周轮已完成，跳过复活检查")
        }
    }

    private fun resetRebornScanStateForFriendRanking() {
        rebornScanFoundProtectable.set(false)
        rebornScanCheckedAnyCandidate.set(false)

        val uid = selfId
        val alreadyLimitReached = uid.isNullOrEmpty() || !Status.canProtectBubbleToday(uid)
        rebornScanLimitReached.set(alreadyLimitReached)
    }

    private fun finalizeRebornWeeklyStateAfterFriendRanking() {
        val type = helpFriendCollectType?.value ?: HelpFriendCollectType.NONE
        if (type == HelpFriendCollectType.NONE) return
        if (rebornWeeklyCompleted) return
        if (selfId.isNullOrEmpty()) return

        if (rebornConfigSignature.isBlank()) {
            rebornConfigSignature = computeRebornConfigSignature()
        }

        val scanAt = System.currentTimeMillis()
        val foundProtectable = rebornScanFoundProtectable.get()
        val limitReached = rebornScanLimitReached.get()
        val checkedAnyCandidate = rebornScanCheckedAnyCandidate.get()
        val state = RebornEnergyWeeklyPersistence.updateAfterScan(
            configSignature = rebornConfigSignature,
            scanAt = scanAt,
            foundProtectable = foundProtectable,
            limitReached = limitReached,
            completed = false
        )
        rebornWeeklyCompleted = state.completed
        if (checkedAnyCandidate && !limitReached && !foundProtectable) {
            Log.forest("复活能量：本次未发现可复活好友，保留后续重试机会")
        }
    }

    private fun markRebornLimitReachedToday() {
        val uid = selfId ?: return
        if (rebornScanLimitReached.compareAndSet(false, true)) {
            Status.protectBubbleToday(uid)
        }
    }

    private fun canTryRebornProtectNow(userId: String?): Boolean {
        val uid = selfId
        if (uid.isNullOrEmpty()) return false
        if (rebornWeeklyCompleted) return false
        if (rebornScanLimitReached.get() || !Status.canProtectBubbleToday(uid)) return false
        if (!isIsProtected(userId)) return false
        rebornScanCheckedAnyCandidate.set(true)
        return true
    }

    override suspend fun runSuspend() {
        val runStartTime = System.currentTimeMillis()
        Log.forest("🌲🌲🌲 森林主任务开始执行 🌲🌲🌲")
        try {
            TaskCommon.update()
            val energyOnlyModeAtStart = TaskCommon.IS_ENERGY_TIME
            // 每次运行时检查并更新计数器
            checkAndUpdateCounters()
            // 正常流程会自动处理所有收取任务，无需特殊处理
            errorWait = false
            // 计数器和时间记录
            val tc = TimeCounter(TAG)

            Log.forest("执行开始-蚂蚁${getName()}")
            taskCount.set(0)
            selfId = UserMap.currentUid
            lastUsePropCheckTime = 0
            invalidatePropBagCache()
            forestGameCenterRecentAppRecords.clear()
            GreenLife.resetForestMarketRound()
            if (showBagList?.value == true) showBag()
            initRebornWeeklyState()
            // 加载“今日统计”（按账号维度持久化），用于跨重启/多次运行累计
            selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                Statistics.load(uid)
                totalCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
                totalHelpCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.HELPED)
                totalWatered = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.WATERED)
            }

            if (energyOnlyModeAtStart) {
                TaskCommon.update()
                if (TaskCommon.IS_ENERGY_TIME) {
                    Log.forest("⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，执行本次只收能量链路")
                    runEnergyOnlyCollectionWorkflow(tc)
                    clearRoundCaches()
                    Log.forest("✨ 本次只收能量链路完成，交回统一调度")
                } else {
                    Log.forest("当前不在只收能量时间段，跳过只收能量链路并交回统一调度")
                }
                tc.stop()
                return
            }
            val selfHomeObj = runForestPreparationAndCollectionWorkflow(tc)
            runForestHomeFollowUpWorkflow(selfHomeObj, tc)
        } catch (e: CancellationException) {
            // 协程被取消是正常行为，不记录错误日志
            Log.forest("蚂蚁森林任务协程被取消")
            throw e // 重新抛出，让协程系统处理
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "执行蚂蚁森林任务时发生错误: ", t)
        } finally {
            // 计算总耗时
            val totalTime = System.currentTimeMillis() - runStartTime
            val timeInSeconds = totalTime / 1000

            // 优化：不再等待蹲点任务完成，让主任务立即结束
            // 蹲点任务会在后台独立协程中继续运行，不影响其他模块
            val waitingTaskCount = EnergyWaitingManager.getWaitingTaskCount()

            Log.forest("=".repeat(50))
            Log.forest("🌲🌲🌲 森林主任务执行完毕 🌲🌲🌲")
            Log.forest("⏱️ 主任务耗时: ${timeInSeconds}秒 (${totalTime}ms)")
            // 保存统计文件（按账号维度）
            selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                Statistics.save(uid)
                // 保存后再刷新一次本地展示值（避免跨天重置导致展示不一致）
                totalCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
                totalHelpCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.HELPED)
                totalWatered = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.WATERED)
            }

            Log.forest("📊 今日统计: 收${totalCollected}g 帮${totalHelpCollected}g 浇${totalWatered}g")
            if (waitingTaskCount > 0) {
                Log.forest("⏰ 后台蹲点任务: $waitingTaskCount 个 (将在指定时间自动收取)")
                // 输出详细的蹲点任务状态，帮助调试
                val taskStatus = EnergyWaitingManager.getWaitingTasksStatus()
                Log.forest("📋 $taskStatus")
            } else {
                Log.forest("✅ 无后台蹲点任务")
            }
            Log.forest("=".repeat(50))

            clearRoundCaches()
            val strTotalCollected =
                "今日总 收:" + totalCollected + "g 帮:" + totalHelpCollected + "g 浇:" + totalWatered + "g"
            updateRunningLastExec(strTotalCollected)
        }
    }

    private fun clearRoundCaches() {
        userNameCache.clear()
        processedUsersCache.clear()
        // 清空本轮的空森林缓存，以便下一轮（如下次"执行间隔"到达）重新检查所有好友
        emptyForestCache.clear()
        // 清空跳过用户缓存，下一轮重新检测保护罩状态
        skipUsersCache.clear()
        // 清空好友主页缓存
        friendHomeCache.clear()
        handledGiftBoxUsers.clear()
        handledProtectUsers.clear()
        roundPropCheckState = null
        lastUsePropCheckTime = 0L
        forestGameCenterRecentAppRecords.clear()
        GreenLife.resetForestMarketRound()
    }

    internal fun canRunConsumeAnimalPropWorkflow(): Boolean {
        return canConsumeAnimalProp && consumeAnimalProp?.value == true
    }

    internal fun hasPendingRobMultiplierEnergy(): Boolean {
        return robMultiplierCardEndTime > System.currentTimeMillis()
    }

    internal fun logForestEnergyInfo() {
        try {
            val uid = UserMap.currentUid
            if (uid.isNullOrBlank()) return

            val homeStr = AntForestRpcCall.queryHomePage()
            if (homeStr.isBlank()) return
            val homeJo = JSONObject(homeStr)
            if (!ResChecker.checkRes(TAG, "queryHomePage:", homeJo)) return
            updateSelfHomePage(homeJo)

            val currentEnergy = homeJo.optJSONObject("userBaseInfo")?.optInt("currentEnergy", 0) ?: 0

            val dynamicStr = AntForestRpcCall.queryDynamicsIndex()
            if (dynamicStr.isBlank()) return
            val dynamicJo = JSONObject(dynamicStr)
            if (!ResChecker.checkRes(TAG, "queryDynamicsIndex:", dynamicJo)) return

            val summary = dynamicJo.optJSONObject("todayEnergySummary") ?: return
            val obtainTotal = summary.optInt("obtainTotal", 0)
            val robbedTotal = summary.optInt("robbedTotal", 0)

            val selfName = UserMap.get(uid)?.showName ?: UserMap.getMaskName(uid) ?: uid
            Log.forest("森林能量🌳[$selfName]收取${obtainTotal}g;被收${robbedTotal}g;当前${currentEnergy}g")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "logForestEnergyInfo err", t)
        }
    }

    /**
     * 每日重置
     */
    // 上次检查的日期（用于判断是否跨天）
    private var lastCheckDate: String? = null

    private fun checkAndUpdateCounters() {
        val today = TimeUtil.getDateStr() // 获取当前日期，如 "2025-10-07"
        // 只在日期变化时重置计数器（跨天）
        if (lastCheckDate != today) {
            resetTaskCounters()
            lastCheckDate = today
            Log.forest("✅ 检测到新的一天[$today]，重置计数器")
        }
    }

    // 重置任务计数器（你需要根据具体任务的计数器来调整）
    private fun resetTaskCounters() {
        taskCount.set(0) // 重置任务计数
        // 每日重置时清空频率限制记录，让所有好友都有新的机会
        ForestUtil.clearAllFrequencyLimits()
        Log.forest("任务计数器已重置")
    }

    /**
     * 定义一个 处理器接口
     */
    private fun interface JsonArrayHandler {
        fun handle(array: JSONArray?)
    }

    private fun processJsonArray(
        initialObj: JSONObject?,
        arrayKey: String?,
        handler: JsonArrayHandler
    ) {
        var hasMore: Boolean
        var currentObj = initialObj
        do {
            val jsonArray = currentObj?.optJSONArray(arrayKey)
            if (jsonArray != null && jsonArray.length() > 0) {
                handler.handle(jsonArray)
                // 判断是否还有更多数据（比如返回满20个）
                hasMore = jsonArray.length() >= 20
            } else {
                hasMore = false
            }
            if (hasMore) {
                GlobalThreadPools.sleepCompat(2000L) // 防止请求过快被限制
                currentObj = querySelfHome() // 获取下一页数据
            }
        } while (hasMore)
    }

    internal fun wateringBubbles(selfHomeObj: JSONObject?) {
        processJsonArray(
            selfHomeObj,
            "wateringBubbles"
        ) { wateringBubbles: JSONArray? ->
            this.collectWateringBubbles(
                wateringBubbles!!
            )
        }
    }

    internal fun givenProps(selfHomeObj: JSONObject?) {
        processJsonArray(selfHomeObj, "givenProps") { givenProps: JSONArray? ->
            this.collectGivenProps(
                givenProps!!
            )
        }
    }

    /**
     * 收取回赠能量，好友浇水金秋，好友复活能量
     *
     * @param wateringBubbles 包含不同类型金球的对象数组
     */
    private fun collectWateringBubbles(wateringBubbles: JSONArray) {
        for (i in 0..<wateringBubbles.length()) {
            try {
                val wateringBubble = wateringBubbles.getJSONObject(i)
                when (val bizType = wateringBubble.getString("bizType")) {
                    "jiaoshui" -> collectWater(wateringBubble)
                    "fuhuo" -> collectRebornEnergy()
                    "baohuhuizeng" -> collectReturnEnergy(wateringBubble)
                    else -> {
                        Log.forest("未知bizType: $bizType")
                        continue
                    }
                }
                GlobalThreadPools.sleepCompat(500L)
            } catch (e: JSONException) {
                Log.forest("浇水金球JSON解析错误: " + e.message)
            } catch (e: RuntimeException) {
                Log.forest("浇水金球处理异常: " + e.message)
            }
        }
    }

    private fun collectWater(wateringBubble: JSONObject) {
        try {
            val friendId = wateringBubble.optString("userId")
            val id = wateringBubble.getLong("id")
            val uid = selfId ?: return
            val response = AntForestRpcCall.collectEnergy("jiaoshui", uid, id)
            runCatching {
                val bubbles = JSONObject(response).optJSONArray("bubbles") ?: return@runCatching
                val collected = bubbles.optJSONObject(0)?.optInt("collectedEnergy", 0) ?: 0
                if (collected > 0 && friendId.isNotEmpty()) {
                    Status.wateredFriendToday(friendId)
                }
            }
            val friendName = getAndCacheUserName(friendId)
            val msg = if (!friendName.isNullOrEmpty()) "收取[$friendName]的金球🍯浇水" else "收取金球🍯浇水"
            processCollectResult(response, msg)
        } catch (e: JSONException) {
            Log.forest("收取浇水JSON解析错误: " + e.message)
        }
    }

    private fun collectRebornEnergy() {
        try {
            val response = AntForestRpcCall.collectRebornEnergy()
            processCollectResult(response, "收取金球🍯复活")
        } catch (e: RuntimeException) {
            Log.forest("收取金球运行时异常: " + e.message)
        }
    }

    private fun collectReturnEnergy(wateringBubble: JSONObject) {
        try {
            val friendId = wateringBubble.getString("userId")
            val id = wateringBubble.getLong("id")
            val uid = selfId ?: return
            val response = AntForestRpcCall.collectEnergy("jiaoshui", uid, id)
            val friendName = getAndCacheUserName(friendId)
            val displayName = friendName ?: UserMap.getMaskName(friendId) ?: friendId
            processCollectResult(
                response,
                "收取金球🍯[$displayName]复活回赠"
            )
        } catch (e: JSONException) {
            Log.forest("收取金球回赠JSON解析错误: " + e.message)
        }
    }

    /**
     * 处理金球-浇水、收取结果
     *
     * @param response       收取结果
     * @param successMessage 成功提示信息
     */
    private fun processCollectResult(response: String, successMessage: String?) {
        try {
            val joEnergy = JSONObject(response)
            if (ResChecker.checkRes(TAG, "收集能量失败:", joEnergy)) {
                val bubbles = joEnergy.getJSONArray("bubbles")
                if (bubbles.length() > 0) {
                    val collected = bubbles.getJSONObject(0).getInt("collectedEnergy")
                    if (collected > 0) {
                        selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                            Statistics.addData(uid, Statistics.DataType.COLLECTED, collected)
                            totalCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
                        } ?: run {
                            totalCollected += collected
                        }
                        val msg = successMessage + "[" + collected + "g]"
                        Log.forest(msg)
                        Toast.show(msg)
                    } else {
                        Log.forest(successMessage + "失败")
                    }
                } else {
                    Log.forest(successMessage + "失败: 未找到金球信息")
                }
            } else {
                Log.forest(successMessage + "失败:" + joEnergy.getString("resultDesc"))
                Log.forest(response)
            }
        } catch (e: JSONException) {
            Log.forest("JSON解析错误: " + e.message)
        } catch (e: Exception) {
            Log.forest("处理收能量结果错误: " + e.message)
        }
    }

    /**
     * 领取道具
     *
     * @param givenProps 给的道具
     */
    private fun collectGivenProps(givenProps: JSONArray) {
        try {
            for (i in 0..<givenProps.length()) {
                val jo = givenProps.getJSONObject(i)
                val giveConfigId = jo.getString("giveConfigId")
                val giveId = jo.getString("giveId")
                val propConfig = jo.getJSONObject("propConfig")
                val propName = propConfig.getString("propName")
                try {
                    val response = AntForestRpcCall.collectProp(giveConfigId, giveId)
                    val responseObj = JSONObject(response)
                    if (ResChecker.checkRes(TAG, "领取道具失败:", responseObj)) {
                        val str = "领取道具🎭[$propName]"
                        Log.forest(str)
                        Toast.show(str)
                    } else {
                        Log.forest("领取道具🎭[" + propName + "]失败:" + responseObj.getString("resultDesc")
                        )
                        Log.forest(response)
                    }
                } catch (e: Exception) {
                    Log.printStackTrace(TAG, "领取道具时发生错误: " + e.message, e)
                }
                GlobalThreadPools.sleepCompat(1000L)
            }
        } catch (e: JSONException) {
            Log.printStackTrace(TAG, "givenProps JSON解析错误: " + e.message, e)
        }
    }

    /**
     * 处理用户派遣道具, 如果用户有派遣道具，则收取派遣动物滴能量
     *
     * @param selfHomeObj 用户主页信息的JSON对象
     */
    internal fun handleUserProps(selfHomeObj: JSONObject) {
        try {
            val usingUserProps = if (isTeam(selfHomeObj)) {
                selfHomeObj.optJSONObject("teamHomeResult")
                    ?.optJSONObject("mainMember")
                    ?.optJSONArray("usingUserProps")
                    ?: JSONArray()  // 提供默认值
            } else {
                selfHomeObj.optJSONArray("usingUserPropsNew") ?: JSONArray()
            }
            canConsumeAnimalProp = true
            if (usingUserProps.length() == 0) {
                return  // 如果没有使用中的用户道具，直接返回
            }
            //            Log.runtime(TAG, "尝试遍历使用中的道具:" + usingUserProps);
            for (i in 0..<usingUserProps.length()) {
                val jo = usingUserProps.getJSONObject(i)
                if ("animal" != jo.getString("propGroup")) {
                    continue  // 如果当前道具不是动物类型，跳过
                }
                canConsumeAnimalProp = false // 设置标志位，表示不可再使用动物道具
                val extInfo = JSONObject(jo.getString("extInfo"))
                if (extInfo.optBoolean("isCollected")) {
                    Log.forest("动物派遣能量已被收取")
                    continue  // 如果动物能量已经被收取，跳过
                }
                val propId = jo.getString("propId")
                val propType = jo.getString("propType")
                val shortDay = extInfo.getString("shortDay")
                val animalName = extInfo.getJSONObject("animal").getString("name")
                val response = AntForestRpcCall.collectAnimalRobEnergy(propId, propType, shortDay)
                val responseObj = JSONObject(response)
                if (ResChecker.checkRes(TAG, "收取动物派遣能量失败:", responseObj)) {
                    val energy = extInfo.optInt("energy", 0)
                    if (energy > 0) {
                        selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                            Statistics.addData(uid, Statistics.DataType.COLLECTED, energy)
                            totalCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
                        } ?: run {
                            totalCollected += energy
                        }
                    }
                    val str = "收取[" + animalName + "]派遣能量🦩[" + energy + "g]"
                    Toast.show(str)
                    Log.forest(str)
                } else {
                    Log.forest("收取动物能量失败: " + responseObj.getString("resultDesc"))
                    Log.forest(response)
                }
                break // 收取到一个动物能量后跳出循环
            }
        } catch (e: JSONException) {
            Log.printStackTrace(e)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "handleUserProps err", e)
        }
    }

    /**
     * 收取能量炸弹卡炸落的能量
     * 基于抓包数据：alipay.antforest.forest.h5.collectBombCardEnergy
     *
     * @param selfHomeObj 用户主页信息的JSON对象
     */
    internal fun collectEnergyBomb(selfHomeObj: JSONObject) {
        try {
            val usingUserProps = if (isTeam(selfHomeObj)) {
                selfHomeObj.optJSONObject("teamHomeResult")
                    ?.optJSONObject("mainMember")
                    ?.optJSONArray("usingUserProps")
                    ?: JSONArray()
            } else {
                selfHomeObj.optJSONArray("usingUserPropsNew") ?: JSONArray()
            }

            if (usingUserProps.length() == 0) return

            for (i in 0..<usingUserProps.length()) {
                val jo = usingUserProps.getJSONObject(i)
                // 筛选能量炸弹卡
                if ("energyBombCard" != jo.getString("propGroup")) {
                    continue
                }

                // 检查是否有可收取的剩余能量
                val extInfoStr = jo.optString("extInfo")
                if (extInfoStr.isEmpty()) continue

                val extInfo = JSONObject(extInfoStr)
                val remainEnergy = extInfo.optInt("remainEnergy", 0)

                if (remainEnergy > 0) {
                    val propId = jo.getString("propId")
                    val propName = jo.getString("propName")

                    Log.forest("发现[$propName]有 $remainEnergy g能量待收取，尝试收取...")

                    // 调用 AntForestRpcCall 中的静态方法
                    val response = AntForestRpcCall.collectBombCardEnergy(propId)

                    val responseObj = JSONObject(response)
                    if (ResChecker.checkRes(TAG, "收取炸弹卡能量失败:", responseObj)) {
                        val collected = responseObj.optInt("collectEnergy", 0)
                        if (collected > 0) {
                            selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                                Statistics.addData(uid, Statistics.DataType.COLLECTED, collected)
                                totalCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
                            } ?: run {
                                totalCollected += collected
                            }
                        }
                        val str = "收取炸弹卡能量💥[$collected g]"
                        Toast.show(str)
                        Log.forest(str)

                        // 收取成功后更新主页数据，避免重复显示
                        updateSelfHomePage()
                    } else {
                        Log.forest("收取炸弹卡失败: " + responseObj.getString("resultDesc"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "collectEnergyBomb err", e)
        }
    }

    /**
     * 给好友浇水
     */
    internal fun waterFriends() {
        try {
            val taskUid = UserMap.currentUid
            if (taskUid.isNullOrBlank()) {
                Log.forest("waterFriends: 当前用户为空，跳过浇水")
                return
            }
            val friendMap = waterFriendList?.resolvedCountMap() ?: emptyMap()
            val notify = notifyFriend?.value == true // 获取通知开关状态
            val maxFriendWaterCount = waterFriendCount?.value ?: waterFriendCount?.defaultValue ?: 0

            for (friendEntry in friendMap.entries) {
                // 避免切号后仍继续为旧账号执行浇水与标记
                if (taskUid != UserMap.currentUid) {
                    Log.forest("waterFriends: 检测到切号，终止浇水流程")
                    break
                }
                val uid = friendEntry.key
                if (selfId == uid) {
                    continue
                }
                var waterCount = friendEntry.value
                if (waterCount <= 0) {
                    continue
                }
                waterCount = min(waterCount, 3)

                if (Status.canWaterFriendToday(uid, waterCount, taskUid)) {
                    try {
                        val jo = queryFriendHome(uid, "waterFriend") ?: continue
                        if (ResChecker.checkRes(TAG, jo)) {
                            val bizNo = jo.optString("bizNo")
                            if (bizNo.isBlank()) {
                                continue
                            }

                            // ✅ 关键改动：传入通知开关
                            val waterCountKVNode = returnFriendWater(
                                uid, bizNo, waterCount, maxFriendWaterCount, notify, taskUid
                            )

                            val actualWaterCount: Int = waterCountKVNode.key!!
                            if (actualWaterCount > 0) {
                                Status.waterFriendToday(uid, actualWaterCount, taskUid)
                            }
                            if (java.lang.Boolean.FALSE == waterCountKVNode.value) {
                                break
                            }
                        } else {
                            Log.forest(jo.getString("resultDesc"))
                        }
                    } catch (e: JSONException) {
                        Log.forest("waterFriends JSON解析错误: " + e.message)
                    } catch (t: Throwable) {
                        Log.printStackTrace(TAG, t)
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "未知错误: " + e.message, e)
        }
    }

    private data class EnergyPvpInfoSnapshot(
        val hasEntry: Boolean,
        val hasReward: Boolean,
        val battleStatus: String
    )

    internal fun handleEnergyPvpChallenge() {
        if (energyPvpChallenge?.value != true) {
            return
        }
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTFOREST_ENERGY_PVP_CHALLENGE_DONE)) {
            Log.forest("1V1能量挑战赛：今日已处理，跳过重复查询")
            return
        }

        try {
            val pvpInfo = queryEnergyPvpInfoSnapshot()
            val homeResponse = AntForestRpcCall.queryPvpHomeInfo(queryWaitToReceive = true)
            if (homeResponse.isBlank()) {
                Log.forest("1V1能量挑战赛：查询主页响应为空")
                if (pvpInfo?.hasReward == true && receiveEnergyPvpRewards()) {
                    Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_ENERGY_PVP_CHALLENGE_DONE)
                }
                return
            }
            val homeObj = JSONObject(homeResponse)
            if (!ResChecker.checkRes(TAG, "查询1V1能量挑战赛失败:", homeObj)) {
                Log.forest("1V1能量挑战赛查询失败: ${homeObj.optString("resultDesc", homeObj.optString("resultCode"))}")
                if (pvpInfo?.hasReward == true) {
                    Log.forest("1V1能量挑战赛：入口提示有待领奖励，主页查询失败后尝试直接领取")
                    if (receiveEnergyPvpRewards()) {
                        Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_ENERGY_PVP_CHALLENGE_DONE)
                    }
                }
                return
            }

            val currentRecord = homeObj.optJSONObject("currentEnergyPvpBattleRecord")
            val previousRecord = homeObj.optJSONObject("previousEnergyPvpBattleRecord")
            val waitRecordCount = homeObj.optInt("waitToReceiveRecordCount", 0)
            val waitRewardCount = homeObj.optInt("waitToReceiveRewardCount", 0)
            val hasUnreceivedReward = hasUnreceivedPvpReward(currentRecord) || hasUnreceivedPvpReward(previousRecord)

            logEnergyPvpRecord("当前场次", currentRecord)
            logEnergyPvpRecord("上一场次", previousRecord)

            if (waitRewardCount > 0 || hasUnreceivedReward || pvpInfo?.hasReward == true) {
                Log.forest("1V1能量挑战赛：发现待领取奖励 record=$waitRecordCount reward=$waitRewardCount，开始领取")
                if (receiveEnergyPvpRewards()) {
                    Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_ENERGY_PVP_CHALLENGE_DONE)
                }
                return
            }

            if (isPvpBattleActive(currentRecord) ||
                isPvpBattleActive(previousRecord) ||
                isPvpBattleStatusActive(pvpInfo?.battleStatus.orEmpty())
            ) {
                Log.forest("1V1能量挑战赛：仍在匹配/进行/结算中，保留后续重试")
                return
            }

            Log.forest("1V1能量挑战赛：暂无待领取奖励，今日无需继续处理")
            Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_ENERGY_PVP_CHALLENGE_DONE)
        } catch (t: Throwable) {
            handleException("handleEnergyPvpChallenge", t)
        }
    }

    private fun queryEnergyPvpInfoSnapshot(): EnergyPvpInfoSnapshot? {
        val response = AntForestRpcCall.queryEnergyPvpInfo(checkReward = true)
        if (response.isBlank()) {
            Log.forest("1V1能量挑战赛：查询入口响应为空")
            return null
        }
        val obj = JSONObject(response)
        if (!ResChecker.checkRes(TAG, "查询1V1能量挑战赛入口失败:", obj)) {
            Log.forest("1V1能量挑战赛入口查询失败: ${obj.optString("resultDesc", obj.optString("resultCode"))}")
            return null
        }
        val pvpInfo = obj.optJSONObject("combineHandlerVOMap")
            ?.optJSONObject("energyPvpInfo")
        if (pvpInfo == null) {
            Log.forest("1V1能量挑战赛：入口未返回能量挑战信息")
            return null
        }

        val battleStatus = pvpInfo.optString("battleStatus")
        val hasEntry = pvpInfo.optBoolean("hasEntry", false)
        val hasReward = pvpInfo.optBoolean("hasReward", false)
        val attackerEnergy = pvpInfo.optInt("attackerEnergy", 0)
        val defenderEnergy = pvpInfo.optInt("defenderEnergy", 0)
        Log.forest(
            "1V1能量挑战赛：入口 status[$battleStatus] entry[$hasEntry] reward[$hasReward] " +
                    "energy[$attackerEnergy:$defenderEnergy]"
        )
        return EnergyPvpInfoSnapshot(hasEntry, hasReward, battleStatus)
    }

    private fun receiveEnergyPvpRewards(): Boolean {
        val receiveResponse = AntForestRpcCall.receivePvpRewards()
        if (receiveResponse.isBlank()) {
            Log.forest("1V1能量挑战赛：领取奖励响应为空")
            return false
        }
        val receiveObj = JSONObject(receiveResponse)
        if (!ResChecker.checkRes(TAG, "领取1V1能量挑战赛奖励失败:", receiveObj)) {
            val resultCode = receiveObj.optString("resultCode")
            val resultDesc = receiveObj.optString("resultDesc", resultCode)
            if (isPvpRewardTerminalResult(resultCode, resultDesc)) {
                Log.forest("1V1能量挑战赛领取终态: $resultDesc")
                return true
            }
            Log.forest("1V1能量挑战赛领取失败: $resultDesc")
            return false
        }

        val rewards = receiveObj.optJSONArray("receivedRewards")
        Log.forest("1V1能量挑战赛奖励领取成功：${summarizePvpRewards(rewards)}")
        queryEnergyPvpRecordsAfterReceive()
        return true
    }

    private fun queryEnergyPvpRecordsAfterReceive() {
        try {
            val recordsResponse = AntForestRpcCall.queryPvpBattleRecords(pageSize = 5)
            if (recordsResponse.isBlank()) {
                return
            }
            val recordsObj = JSONObject(recordsResponse)
            if (ResChecker.checkRes(TAG, "复查1V1能量挑战赛记录失败:", recordsObj)) {
                Log.forest("1V1能量挑战赛奖励复查：hasRewards=${recordsObj.optBoolean("hasRewards", false)}")
            }
        } catch (t: Throwable) {
            Log.error(TAG, "1V1能量挑战赛奖励复查失败: ${t.message}")
        }
    }

    private fun hasUnreceivedPvpReward(record: JSONObject?): Boolean {
        val rewards = record?.optJSONArray("rewardDetailList") ?: return false
        for (i in 0 until rewards.length()) {
            val reward = rewards.optJSONObject(i) ?: continue
            val status = reward.optString("rewardStatus").uppercase(Locale.ROOT)
            if (status.equals("UNRECEIVED", ignoreCase = true) ||
                status.equals("WAIT_RECEIVE", ignoreCase = true) ||
                status.contains("WAIT") && status.contains("RECEIV")
            ) {
                return true
            }
        }
        return false
    }

    private fun isPvpBattleActive(record: JSONObject?): Boolean {
        return isPvpBattleStatusActive(record?.optString("battleStatus").orEmpty())
    }

    private fun isPvpBattleStatusActive(status: String): Boolean {
        return status.equals("MATCHING", ignoreCase = true) ||
                status.equals("PROGRESSING", ignoreCase = true) ||
                status.equals("SETTLING", ignoreCase = true)
    }

    private fun isPvpRewardTerminalResult(resultCode: String, resultDesc: String): Boolean {
        val text = "$resultCode $resultDesc"
        return text.contains("已领取") ||
                text.contains("已发放") ||
                text.contains("无可领取") ||
                text.contains("没有可领取") ||
                text.contains("重复领取")
    }

    private fun logEnergyPvpRecord(label: String, record: JSONObject?) {
        if (record == null) {
            Log.forest("1V1能量挑战赛：$label 无记录")
            return
        }
        val battleDay = record.optString("battleDay")
        val battleStatus = record.optString("battleStatus")
        val battleResult = record.optString("battleResult")
        val attackerName = record.optString("attackerDisplayName").ifBlank { "我方" }
        val defenderName = record.optString("defenderDisplayName").ifBlank { "对手" }
        val attackerEnergy = record.optInt("attackerEnergy", 0)
        val defenderEnergy = record.optInt("defenderEnergy", 0)
        val rewards = summarizePvpRewards(record.optJSONArray("rewardDetailList"))
        Log.forest(
            "1V1能量挑战赛：$label day[$battleDay] status[$battleStatus] result[$battleResult] " +
                    "$attackerName ${attackerEnergy}g : ${defenderEnergy}g $defenderName，奖励[$rewards]"
        )
    }

    private fun summarizePvpRewards(rewards: JSONArray?): String {
        if (rewards == null || rewards.length() == 0) {
            return "无"
        }
        val parts = ArrayList<String>()
        for (i in 0 until rewards.length()) {
            val reward = rewards.optJSONObject(i) ?: continue
            parts.add(describePvpReward(reward))
        }
        return if (parts.isEmpty()) "无" else parts.joinToString("；")
    }

    private fun describePvpReward(reward: JSONObject): String {
        val name = reward.optString("rewardName")
            .ifBlank { reward.optString("rewardId") }
            .ifBlank { "未知奖励" }
        val status = reward.optString("rewardStatus")
        val type = reward.optString("rewardType")
        val energy = reward.optInt("energy", 0)
        val extra = when {
            energy > 0 -> "${energy}g"
            type.isNotBlank() -> type
            else -> ""
        }
        return buildString {
            append(name)
            if (extra.isNotBlank()) append("($extra)")
            if (status.isNotBlank()) append("[$status]")
        }
    }

    private fun refreshVitalityExchangeOptionsForSettings(): List<MapperEntity> {
        if (!HookReadyChecker.isCurrentProcessReadyForRpc(UserMap.currentUid)) {
            if (!HookReadyChecker.isTargetAppReadyForRpc(UserMap.currentUid)) {
                val cachedRows = ExchangeOptionsCache.loadForSettingsCache(
                    UserMap.currentUid,
                    ExchangeOptionsRefreshBridge.TARGET_FOREST_VITALITY
                )
                Log.forest("活力值兑换🎁目标应用未启动，设置页先展示上次缓存列表；请打开目标应用后再刷新#${cachedRows.size}")
                return cachedRows
            }
            val refreshResult = ExchangeOptionsRefreshBridge.requestRefreshOptions(
                ExchangeOptionsRefreshBridge.TARGET_FOREST_VITALITY,
                UserMap.currentUid
            )
            if (refreshResult.success) {
                Log.forest("活力值兑换🎁设置页使用目标应用刷新列表#${refreshResult.options.size}")
                return refreshResult.options
            }
            Log.forest("活力值兑换🎁远程刷新失败，不使用旧缓存#${refreshResult.message}")
            return emptyList()
        }
        val rows = refreshVitalityExchangeOptionsFromRpc()
        Log.forest("活力值兑换🎁设置页刷新结构化列表#${rows.size}")
        return rows
    }

    internal fun refreshVitalityExchangeOptionsForRemote(): List<ExchangeOptionRow> =
        refreshVitalityExchangeOptionsFromRpc()

    private fun refreshVitalityExchangeOptionsFromRpc(): List<ExchangeOptionRow> {
        return runCatching {
            Vitality.initVitality("")
            val rows = buildVitalityExchangeOptionRows()
            ExchangeOptionsCache.save(UserMap.currentUid, ExchangeOptionsRefreshBridge.TARGET_FOREST_VITALITY, rows)
            rows
        }.onFailure {
            Log.printStackTrace(TAG, "refreshVitalityExchangeOptionsFromRpc err:", it)
        }.getOrElse {
            emptyList()
        }
    }

    private fun buildVitalityExchangeOptionRows(): List<ExchangeOptionRow> {
        return Vitality.skuInfo.entries
            .mapNotNull { (skuId, skuModel) -> buildVitalityExchangeItem(skuId, skuModel).toOptionRow() }
    }

    private fun buildVitalityExchangeItem(skuId: String, skuModel: JSONObject): ExchangeItem {
        val skuName = skuModel.optString("skuName").ifBlank { skuId }
        val price = skuModel.optJSONObject("price")?.optString("amount").orEmpty()
            .ifBlank { skuModel.optString("price") }
        val statusText = formatVitalityStatusList(skuModel.optJSONArray("itemStatusList"))
        val unsafeByName = listOf("皮肤", "装扮", "主题", "挂件", "背景", "红包", "优惠券", "券")
            .any { skuName.contains(it) }
        val unavailable = statusText.isNotBlank()
        val safety = when {
            unavailable -> ExchangeSafety.UNAVAILABLE
            unsafeByName -> ExchangeSafety.LOG_ONLY
            else -> ExchangeSafety.AUTO
        }
        val safetyReason = when {
            unavailable -> statusText
            unsafeByName -> "展示/券类权益不参与自动补兑"
            else -> ""
        }
        val effectTags = buildVitalityEffectTags(skuModel, skuName)
        return ExchangeItem(
            id = skuId,
            name = skuName,
            cost = ExchangeCost(pointText = price.takeIf { it.isNotBlank() }?.let { "${it}活力值" }.orEmpty()),
            limit = ExchangeLimit(statusText = statusText),
            safety = safety,
            safetyReason = safetyReason,
            effectTags = effectTags,
            displayMeta = ExchangeEffectCatalog.displayMeta(
                ExchangeEffectCatalog.SOURCE_FOREST_VITALITY,
                skuName,
                safety,
                safetyReason,
                effectTags
            )
        )
    }

    private fun buildVitalityEffectTags(skuModel: JSONObject, skuName: String): List<ExchangeEffectTag> {
        val tags = ExchangeEffectCatalog.tagsFor(ExchangeEffectCatalog.SOURCE_FOREST_VITALITY, skuName).toMutableList()
        if (isVitalityEnergyRainChanceSku(skuModel) && tags.none { it.need == ExchangeEffectNeed.FOREST_ENERGY_RAIN }) {
            tags.add(
                ExchangeEffectTag(
                    need = ExchangeEffectNeed.FOREST_ENERGY_RAIN,
                    targetModule = "蚂蚁森林",
                    priority = 5,
                    reason = "能量雨机会缺货补兑",
                    triggerText = "能量雨开启但当前无可玩机会时补兑"
                )
            )
        }
        return tags
    }

    private fun isVitalityEnergyRainChanceSku(skuModel: JSONObject): Boolean {
        if (skuModel.optString("rightsConfigId") == VITALITY_ENERGY_RAIN_RIGHTS_ID) {
            return true
        }
        val rightsBatch = skuModel.optJSONObject("rightsBatchConfigVO")
        if (rightsBatch?.optString("provideBatchId") == VITALITY_ENERGY_RAIN_RIGHTS_ID) {
            return true
        }
        val rightsList = rightsBatch?.optJSONArray("rightsConfigVOList")
        if (rightsList != null) {
            for (i in 0 until rightsList.length()) {
                val extend = rightsList.optJSONObject(i)?.opt("extend")
                val extendJson = when (extend) {
                    is JSONObject -> extend
                    is String -> runCatching { JSONObject(extend) }.getOrNull()
                    else -> null
                }
                if (extendJson?.optString("antiepScAssetsType") == LIMIT_TIME_ENERGY_RAIN_CHANCE_PROP_TYPE) {
                    return true
                }
            }
        }
        return listOf(
            skuModel.optString("skuName"),
            skuModel.optString("spuName"),
            skuModel.optString("subTitle")
        ).any { it.contains("能量雨") }
    }

    private fun formatVitalityStatusList(itemStatusList: JSONArray?): String {
        if (itemStatusList == null || itemStatusList.length() == 0) {
            return ""
        }
        val statuses = mutableListOf<String>()
        for (i in 0 until itemStatusList.length()) {
            val rawStatus = itemStatusList.optString(i)
            val status = runCatching { VitalityStore.ExchangeStatus.valueOf(rawStatus) }.getOrNull()
            statuses.add(status?.nickName ?: rawStatus)
        }
        return statuses.filter { it.isNotBlank() }.joinToString("、")
    }

    internal fun handleVitalityExchange(itemFilter: ((String, String) -> Boolean)? = null): Boolean {
        var exchangedAny = false
        try {
//            JSONObject bag = getBag();

            Vitality.initVitality("")
            val exchangeList = vitalityExchangeList?.value ?: emptyMap()
            //            Map<String, Integer> maxLimitList = vitalityExchangeMaxList.value;
            for (entry in exchangeList.entries) {
                val skuId = entry.key ?: continue
                val count = entry.value
                if (count == null || count <= 0) {
                    Log.forest("无效的count值: skuId=$skuId, count=$count")
                    continue
                }
                val skuName = Vitality.skuInfo[skuId]?.optString("skuName").orEmpty()
                if (itemFilter != null && !itemFilter(skuId, skuName)) {
                    continue
                }
                val exchangeItem = Vitality.skuInfo[skuId]?.let { buildVitalityExchangeItem(skuId, it) }
                if (exchangeItem != null && exchangeItem.safety != ExchangeSafety.AUTO) {
                    Log.forest("活力值兑换🍃跳过[${exchangeItem.displayName()}]#${exchangeItem.safetyReason}")
                    continue
                }
                // 处理活力值兑换
                while (Status.canVitalityExchangeToday(skuId, count)) {
                    if (!Vitality.handleVitalityExchange(skuId)) {
                        Log.forest("活力值兑换失败: " + getNameById(skuId))
                        break
                    }
                    exchangedAny = true
                    GlobalThreadPools.sleepCompat(1000L)
                }
            }
        } catch (t: Throwable) {
            handleException("handleVitalityExchange", t)
        }
        return exchangedAny
    }

    internal fun replenishExchangeByNeed(
        need: ExchangeEffectNeed,
        reason: String,
        maxCount: Int
    ): ExchangeReplenishResult {
        if (vitalityExchange?.value != true) {
            return ExchangeReplenishResult.NOT_SELECTED
        }
        val exchangeList = vitalityExchangeList?.value ?: emptyMap()
        if (exchangeList.isEmpty()) {
            return ExchangeReplenishResult.NOT_SELECTED
        }
        return runCatching {
            Vitality.initVitality("")
            val safeMaxCount = maxCount.coerceAtLeast(1)
            var matchedSelected = false
            var attempted = false
            var exchangedCount = 0
            val sortedEntries = exchangeList.entries.sortedBy { entry ->
                val skuId = entry.key
                val skuModel = if (skuId == null) null else Vitality.skuInfo[skuId]
                if (skuId == null || skuModel == null) {
                    Int.MAX_VALUE
                } else {
                    ExchangeEffectCatalog.priorityFor(buildVitalityExchangeItem(skuId, skuModel), need)
                }
            }
            for (entry in sortedEntries) {
                val skuId = entry.key ?: continue
                val count = entry.value?.takeIf { it > 0 } ?: continue
                val skuName = Vitality.skuInfo[skuId]?.optString("skuName").orEmpty()
                if (skuName.isBlank()) {
                    continue
                }
                val exchangeItem = Vitality.skuInfo[skuId]?.let { buildVitalityExchangeItem(skuId, it) } ?: continue
                if (exchangeItem.effectTags.none { it.need == need }) {
                    continue
                }
                matchedSelected = true
                if (exchangeItem.safety != ExchangeSafety.AUTO) {
                    continue
                }
                while (exchangedCount < safeMaxCount && Status.canVitalityExchangeToday(skuId, count)) {
                    attempted = true
                    if (!Vitality.handleVitalityExchange(skuId)) {
                        break
                    }
                    exchangedCount += 1
                    Log.forest("缺货补兑🍃[$skuName]#${reason.ifBlank { need.name }}")
                    if (exchangedCount >= safeMaxCount) {
                        break
                    }
                    GlobalThreadPools.sleepCompat(1000L)
                }
            }
            when {
                exchangedCount > 0 -> ExchangeReplenishResult.EXCHANGED
                matchedSelected && attempted -> ExchangeReplenishResult.BUSINESS_LIMIT
                matchedSelected -> ExchangeReplenishResult.NOT_AVAILABLE
                else -> ExchangeReplenishResult.NOT_SELECTED
            }
        }.onFailure {
            Log.printStackTrace(TAG, "replenishVitalityExchangeByNeed err:", it)
        }.getOrDefault(ExchangeReplenishResult.RETRY_LATER)
    }

    private fun replenishSelectedRewardsForMissingProp(
        propName: String,
        allowPerpetualExchange: Boolean,
        need: ExchangeEffectNeed
    ): JSONObject? {
        if (!allowPerpetualExchange) {
            return null
        }
        Log.forest("背包中没有$propName，尝试按已勾选兑换列表补兑...")
        val result = ExchangeReplenisher.replenish(
            need = need,
            reason = "森林缺少$propName",
            maxCount = 1
        ) {
            queryPropList(true)
        }
        if (result != ExchangeReplenishResult.EXCHANGED) {
            Log.forest("缺货补兑[$propName]未完成#$result")
            return null
        }
        return queryPropList(true)
    }

    private fun notifyMain() {
        if (taskCount.decrementAndGet() < 1) {
            synchronized(this@AntForest) {
                (this@AntForest as Object).notifyAll()
            }
        }
    }

    /**
     * 获取自己主页对象信息
     *
     * @return 用户的主页信息，如果发生错误则返回null。
     */
    internal fun querySelfHome(): JSONObject? {
        var userHomeObj: JSONObject? = null
        try {
            val start = System.currentTimeMillis()
            val response = AntForestRpcCall.queryHomePage()
            if (response.trim { it <= ' ' }.isEmpty()) {
                //               Log.error(TAG, "获取自己主页信息失败：响应为空$response")
                return null
            }
            userHomeObj = JSONObject(response)
            // 检查响应是否成功
            if (!ResChecker.checkRes(TAG, "查询自己主页失败:", userHomeObj)) {
                Log.error(TAG, "查询自己主页失败: " + userHomeObj.optString("resultDesc", "未知错误"))
                return null
            }

            updateSelfHomePage(userHomeObj)
            val end = System.currentTimeMillis()
            // 安全获取服务器时间，如果没有则使用当前时间
            val serverTime = userHomeObj.optLong("now", System.currentTimeMillis())
            val offsetTime = offsetTimeMath.nextInteger(((start + end) / 2 - serverTime).toInt())
            // Log.forest("服务器时间：$serverTime，本地与服务器时间差：$offsetTime")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "查询自己主页异常", t)
        }
        return userHomeObj
    }

    /**
     * 更新好友主页信息
     *
     * @param userId 好友ID
     * @return 更新后的好友主页信息，如果发生错误则返回null。
     */
    private fun queryFriendHome(
        userId: String?,
        fromAct: String?,
        forceRefresh: Boolean = false,
        source: String? = null,
        fallbackFromAct: String? = null
    ): JSONObject? {
        val safeUserId = FriendGuard.normalizeUserId(userId) ?: return null
        val actualFromAct = fromAct ?: "TAKE_LOOK_FRIEND"
        val allowPkRelation = actualFromAct == "PKContest" || fallbackFromAct == "PKContest"
        if (allowPkRelation) {
            if (FriendGuard.isSelf(safeUserId)) {
                Log.record(TAG, "查询好友森林主页[$actualFromAct] 跳过自己账号[$safeUserId]")
                return null
            }
            if (FriendRepository.isGlobalBlocked(safeUserId)) {
                val maskName = UserMap.getMaskName(safeUserId) ?: safeUserId
                Log.record(TAG, "查询好友森林主页[$actualFromAct] 跳过[$maskName]：好友中心全局黑名单")
                return null
            }
        } else {
            if (FriendGuard.shouldSkipFriend(safeUserId, TAG, "查询好友森林主页[$actualFromAct]")) {
                return null
            }
        }
        val cacheKey = "$safeUserId#$actualFromAct#${source ?: "default"}"
        if (!forceRefresh) {
            friendHomeCache[cacheKey]?.let { return it }
        }
        var friendHomeObj: JSONObject? = null
        try {
            val start = System.currentTimeMillis()
            val response = AntForestRpcCall.queryFriendHomePage(safeUserId, actualFromAct, source)
            if (response.trim { it <= ' ' }.isEmpty()) {
                //               Log.error( TAG, "获取好友主页信息失败：响应为空, userId: " + UserMap.getMaskName(userId) + response)
                return null
            }
            friendHomeObj = JSONObject(response)
            val resultCode = friendHomeObj.optString("resultCode")
            val resultDesc = friendHomeObj.optString("resultDesc")
            if (resultDesc.contains("好友信息不存在") &&
                !fallbackFromAct.isNullOrBlank() &&
                fallbackFromAct != actualFromAct
            ) {
                Log.forest("查询好友森林主页[$actualFromAct]返回好友信息不存在，改用[$fallbackFromAct]补查[$safeUserId]")
                return queryFriendHome(safeUserId, fallbackFromAct, forceRefresh = true, source = source)
            }
            if (resultCode == "FRIEND_NOT_FOREST_USER" || resultDesc.contains("未开通")) {
                if (!allowPkRelation) {
                    FriendCapabilityRecorder.record(
                        safeUserId,
                        "FOREST",
                        FriendCapabilityState.NOT_OPEN,
                        "AntForest.queryFriendHomePage",
                        resultDesc.ifBlank { resultCode }
                    )
                }
                Log.forest("蚂蚁森林好友流程跳过[${UserMap.getMaskName(safeUserId) ?: safeUserId}]：对方未开通蚂蚁森林")
                return null
            }
            // 检查响应是否成功
            if (!ResChecker.checkRes(TAG, "查询好友主页失败:", friendHomeObj)) {
                // 检测并记录"手速太快"错误，避免日志刷屏
                ForestUtil.checkAndRecordFrequencyError(safeUserId, friendHomeObj)
                return null
            }
            if (!allowPkRelation) {
                FriendCapabilityRecorder.record(safeUserId, "FOREST", FriendCapabilityState.OPEN, "AntForest.queryFriendHomePage")
            }
            friendHomeCache[cacheKey] = friendHomeObj
            val end = System.currentTimeMillis()
            // 安全获取服务器时间，如果没有则使用当前时间
            val serverTime = friendHomeObj.optLong("now", System.currentTimeMillis())
            val offsetTime = offsetTimeMath.nextInteger(((start + end) / 2 - serverTime).toInt())
            //  Log.forest("服务器时间：$serverTime，本地与服务器时间差：$offsetTime")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "查询好友主页异常, userId: " + UserMap.getMaskName(userId), t)
        }
        return friendHomeObj // 返回用户主页对象
    }

    /**
     * 格式化时间差为人性化的字符串（保持向后兼容）
     * @param milliseconds 时差毫秒
     */
    private fun formatTimeDifference(milliseconds: Long): String {
        return TimeFormatter.formatTimeDifference(milliseconds)
    }

    /**
     * 检查并处理6秒拼手速逻辑（每天主动执行一次）
     */
    internal fun checkAndHandleWhackMole() {
        try {
            val modeIndex = whackMoleMode?.value ?: 0

            if (modeIndex == 0) {
                Log.forest("🎮 拼手速未开启，跳过")
                return
            }

            val whackMoleTimeAllowed = whackMoleTime?.let { it.isDisabled() || it.isReachedToday() } ?: true
            if (!whackMoleTimeAllowed) {
                Log.forest("🎮 拼手速未到执行时间，跳过")
                return
            }

            val whackMoleFlag = StatusFlags.FLAG_ANTFOREST_WHACK_MOLE_EXECUTED
            if (Status.hasFlagToday(whackMoleFlag)) {
                Log.forest("🎮 拼手速今日已执行，跳过")
                return
            }

            Log.forest("🎮 触发拼手速任务")
            WhackMole.start()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 收取用户的蚂蚁森林能量。
     *
     * @param userId      用户ID
     * @param userHomeObj 用户主页的JSON对象，包含用户的蚂蚁森林信息
     * @return 更新后的用户主页JSON对象，如果发生异常返回null
     */
    internal fun collectEnergy(
        userId: String?,
        userHomeObj: JSONObject?,
        fromTag: String?,
        rpcSource: String? = null
    ): JSONObject? {
        try {
            if (userHomeObj == null) {
                return null
            }
            // 1. 检查接口返回是否成功
            if (!ResChecker.checkRes(TAG, "载入用户主页失败:", userHomeObj)) {
                Log.forest("载入失败: " + userHomeObj.optString("resultDesc", "未知错误"))
                return userHomeObj
            }
            val serverTime = userHomeObj.optLong("now", System.currentTimeMillis())
            val isSelf = userId == UserMap.currentUid

            // 2. 自己的能量不受缓存限制，好友的能量检查缓存避免重复处理
            if (!isSelf && !userId.isNullOrEmpty() && processedUsersCache.contains(userId)) {
                return userHomeObj
            }

            // 标记用户为已处理（无论是否成功收取能量）
            if (!isSelf && !userId.isNullOrEmpty()) {
                processedUsersCache.add(userId)
            }
            val userName = getAndCacheUserName(userId, userHomeObj, fromTag)

            // 3. 判断是否允许收取能量 (开关关闭 或 在黑名单中)
            if (collectEnergy?.value != true || jsonCollectMap.contains(userId)) {
                Log.forest("[$userName] 不允许收取能量，跳过")
                return userHomeObj
            }

            // 4. 获取所有可收集的能量球 (extractBubbleInfo 内部已包含"收自己阈值"的逻辑)
            val availableBubbles: MutableList<Long> = ArrayList()
            extractBubbleInfo(userHomeObj, serverTime, availableBubbles, userId)

            if (availableBubbles.isEmpty()) {
                // 记录空森林的时间戳，避免本轮重复检查
                if (!userId.isNullOrEmpty()) {
                    emptyForestCache[userId] = System.currentTimeMillis()
                }
                return userHomeObj
            }

            // 5. 检查是否有能量罩或炸弹卡保护
            var hasProtection = false
            if (!isSelf) {
                // 检查保护罩
                if (hasShield(userHomeObj, serverTime)) {
                    hasProtection = true
                    Log.forest("[$userName]被能量罩❤️保护着哟，跳过收取")
                }

                // 🆕【核心修改】检查炸弹卡 及 阈值判断逻辑
                if (!hasProtection && hasBombCard(userHomeObj, serverTime)) {
                    var bypassBomb = false
                    val bombLimit = collectBombEnergyLimit?.value ?: 0

                    // 如果设定了阈值(>0)，检查是否有大额能量球值得冒险
                    if (bombLimit > 0) {
                        val bubbles = userHomeObj.optJSONArray("bubbles")
                        if (bubbles != null) {
                            for (i in 0 until bubbles.length()) {
                                val bubble = bubbles.getJSONObject(i)
                                // 获取能量值 (fullEnergy通常是当前可收取的能量)
                                val energy = bubble.optInt("fullEnergy", 0)
                                if (energy >= bombLimit) {
                                    bypassBomb = true
                                    Log.forest("[$userName] 发现大能量球($energy g) >= 炸弹阈值($bombLimit g)，无视炸弹卡强行收取！💥")
                                    break
                                }
                            }
                        }
                    }

                    if (!bypassBomb) {
                        hasProtection = true
                        Log.forest("[$userName]开着炸弹卡💣，跳过收取")
                    }
                }
            }

            // 6. 只有没有保护(或无视保护)时才收集当前可用能量
            if (!hasProtection) {
                collectVivaEnergy(userId, userHomeObj, availableBubbles, fromTag, rpcSource = rpcSource)
            }

            return userHomeObj
        } catch (e: JSONException) {
            Log.printStackTrace(TAG, "collectUserEnergy JSON解析错误", e)
            return null
        } catch (e: NullPointerException) {
            Log.printStackTrace(TAG, "collectUserEnergy 空指针异常", e)
            return null
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "collectUserEnergy 出现异常", t)
            return null
        }
    }

    /**
     * 检查保护罩是否覆盖能量成熟期
     *
     * @param userHomeObj 用户主页对象
     * @param produceTime 能量成熟时间
     * @param serverTime 服务器时间
     * @return true表示应该跳过蹲点（保护罩覆盖），false表示可以蹲点
     */
    private fun shouldSkipWaitingTaskDueToProtection(
        userHomeObj: JSONObject,
        produceTime: Long,
        serverTime: Long
    ): Boolean {
        val shieldEndTime = ForestUtil.getShieldEndTime(userHomeObj)
        val bombEndTime = ForestUtil.getBombCardEndTime(userHomeObj)
        val protectionEndTime = maxOf(shieldEndTime, bombEndTime)
        return protectionEndTime > produceTime
    }

    /**
     * {{ 新增辅助方法：统一判断是否满足收自己能量的阈值条件 }}
     * @param bubbleCount 能量球数值
     * @param canBeRobbedAgain 是否可被再次偷取（保底状态为false）
     */
    private fun shouldCollectSelfBubble(bubbleCount: Int, canBeRobbedAgain: Boolean): Boolean {
        val type = collectSelfEnergyType?.value ?: CollectSelfType.ALL
        val threshold = collectSelfEnergyThreshold?.value ?: 0

        return when (type) {
            CollectSelfType.OVER_THRESHOLD -> {
                // 模式：大于阈值才收
                // 逻辑：只有当 [小于阈值] 且 [还能被偷] 时才跳过 (不收)
                // 如果已经到底了(!canBeRobbedAgain)，即使小于阈值也应该收回来，防止浪费
                if (bubbleCount < threshold && canBeRobbedAgain) {
                    false
                } else {
                    // 满足阈值 OR 触发保底收取 (能量很少了，朋友偷不走，必须自己收，不然就浪费了)
                    if (bubbleCount < threshold && !canBeRobbedAgain) {
                        Log.forest("触发保底收取：能量[$bubbleCount g] < 阈值[$threshold g]，但已无法被偷，强制收取")
                    }
                    true
                }
            }
            CollectSelfType.BELOW_THRESHOLD -> {
                // 模式：小于阈值才收
                bubbleCount < threshold
            }
            // CollectSelfType.ALL -> 默认 true
            else -> true
        }
    }

    /**
     * 提取能量球状态
     *
     * @param userHomeObj      用户主页的JSON对象
     * @param serverTime       服务器时间
     * @param availableBubbles 可收集的能量球ID列表
     * @param userId           用户ID
     * @throws JSONException JSON解析异常
     */
    @Throws(JSONException::class)
    private fun extractBubbleInfo(
        userHomeObj: JSONObject,
        serverTime: Long,
        availableBubbles: MutableList<Long>,
        userId: String?,
        collectWaitingTasks: Boolean = true,
        logSummary: Boolean = true
    ) {
        // 1. 获取能量球数组（兼容组队模式）
        val jaBubbles = if (isTeam(userHomeObj)) {
            userHomeObj.optJSONObject("teamHomeResult")
                ?.optJSONObject("mainMember")
                ?.optJSONArray("bubbles")
        } else {
            userHomeObj.optJSONArray("bubbles")
        } ?: JSONArray()

        if (jaBubbles.length() == 0) return

        // 2. 获取用户名（用于日志）
        val userName = getAndCacheUserName(userId, userHomeObj, null)
        var waitingBubblesCount = 0

        // 3. 保护罩/炸弹卡日志记录（仅针对好友，仅做显示，实际拦截在collectEnergy）
        val isSelf = selfId == userId
        var protectionLog = ""
        if (!isSelf) {
            val shieldEndTime = ForestUtil.getShieldEndTime(userHomeObj)
            val bombEndTime = ForestUtil.getBombCardEndTime(userHomeObj)
            val hasShield = shieldEndTime > serverTime
            val hasBomb = bombEndTime > serverTime
            if (hasShield || hasBomb) {
                if (hasShield) {
                    val remainingTime = formatTimeDifference(shieldEndTime - serverTime)
                    protectionLog += " 保护罩剩余: $remainingTime. "
                }
                if (hasBomb) {
                    val remainingTime = formatTimeDifference(bombEndTime - serverTime)
                    protectionLog += " 炸弹卡剩余: $remainingTime."
                }
            }
        }

        // 4. 遍历能量球
        for (i in 0..<jaBubbles.length()) {
            val bubble = jaBubbles.getJSONObject(i)
            val bubbleId = bubble.getLong("id")
            val statusStr = bubble.getString("collectStatus")
            val status = CollectStatus.valueOf(statusStr)
            val bubbleCount = bubble.getInt("fullEnergy")

            when (status) {
                CollectStatus.AVAILABLE -> {
                    // 🆕【修改点1】：可收取状态，统一调用阈值判断
                    if (isSelf) {
                        // 获取是否还能被偷取的标记 (保底状态下该值为 false)
                        val canBeRobbedAgain = bubble.optBoolean("canBeRobbedAgain", false)

                        if (shouldCollectSelfBubble(bubbleCount, canBeRobbedAgain)) {
                            availableBubbles.add(bubbleId)
                        }
                    } else {
                        // 好友的能量直接添加，不进行阈值判断
                        availableBubbles.add(bubbleId)
                    }
                }

                CollectStatus.WAITING -> {
                    if (!collectWaitingTasks) {
                        continue
                    }
                    if (bubbleCount <= 0) {
                        Log.forest("跳过数量为[$bubbleId]的等待能量球的蹲点任务")
                        continue
                    }

                    // 🆕【修改点2】：蹲点任务也必须严格遵循收自己能量的阈值配置
                    if (isSelf) {
                        // 对于等待中的球，我们暂时假设它是可被偷的(canBeRobbedAgain=true)以进行严格检查
                        // 逻辑：如果只收>20g，现在有个5g的在等待，应该跳过，不加入蹲点队列
                        // 如果有明确的canBeRobbedAgain字段则使用，否则默认为true
                        val canBeRobbed = bubble.optBoolean("canBeRobbedAgain", true)
                        if (!shouldCollectSelfBubble(bubbleCount, canBeRobbed)) {
                            // 可选：Log.forest("跳过等待能量[$bubbleCount g] (不满足阈值配置)")
                            continue
                        }
                    }

                    // 等待成熟的能量球，添加到蹲点队列
                    val produceTime = bubble.optLong("produceTime", 0L)
                    if (produceTime > 0 && produceTime > serverTime) {
                        // 检查保护罩时间（仅好友）：如果保护罩覆盖整个成熟期，跳过蹲点
                        // 自己的账号：无论是否有保护罩都要添加蹲点（到时间后直接收取）
                        if (!isSelf && shouldSkipWaitingTaskDueToProtection(userHomeObj, produceTime, serverTime)) {
                            val shieldEndTime = ForestUtil.getShieldEndTime(userHomeObj)
                            val bombEndTime = ForestUtil.getBombCardEndTime(userHomeObj)
                            val protectionEndTime = maxOf(shieldEndTime, bombEndTime)
                            val remainingHours = (protectionEndTime - serverTime) / (1000 * 60 * 60)
                            Log.forest("⏭️ 跳过好友蹲点[$userName]球[$bubbleId]：保护罩覆盖整个成熟期(保护还剩${remainingHours}h，能量${TimeUtil.getCommonDate(produceTime)}成熟)"
                            )
                            continue
                        }

                        val safeUserId = userId ?: ""
                        if (EnergyWaitingManager.hasWaitingTask(safeUserId, bubbleId, produceTime)) {
                            continue
                        }

                        waitingBubblesCount++
                        // 添加蹲点任务
                        EnergyWaitingManager.addWaitingTask(
                            userId = safeUserId,
                            userName = userName ?: "未知用户",
                            bubbleId = bubbleId,
                            produceTime = produceTime,
                            fromTag = "蹲点收取"
                        )
                        Log.forest("添加蹲点: [$userName] 能量球[$bubbleId] 将在[${TimeUtil.getCommonDate(produceTime)}]成熟$protectionLog"
                        )
                    }
                }

                else -> {
                    // 其他状态（INSUFFICIENT, ROBBED等）跳过
                    continue
                }
            }
        }

        // 5. 打印调试信息
        // 只有当有可收取的球，或者有等待的球时才打印，避免刷屏
        if (logSummary && (availableBubbles.isNotEmpty() || waitingBubblesCount > 0)) {
            Log.forest("[$userName] 可收集能量球: ${availableBubbles.size}个")
            if (waitingBubblesCount > 0) {
                Log.forest("[$userName] 等待成熟能量球: ${waitingBubblesCount}个")
            }
        }
    }

    /**
     * 批量或逐一收取能量
     *
     * @param userId      用户ID
     * @param userHomeObj 用户主页的JSON对象
     * @param bubbleIds   能量球ID列表
     * @param fromTag     收取来源标识
     */
    @Throws(JSONException::class)
    /**
     * 收取活力能量
     * @param userId 用户ID
     * @param userHomeObj 用户主页对象
     * @param bubbleIds 能量球ID列表
     * @param fromTag 来源标识
     * @param skipPropCheck 是否跳过道具检查（用于蹲点收取快速通道）
     */
    private fun collectVivaEnergy(
        userId: String?,
        userHomeObj: JSONObject?,
        bubbleIds: MutableList<Long>,
        fromTag: String?,
        skipPropCheck: Boolean = false,
        rpcSource: String? = null
    ): Set<Long> {
        val bizType = "GREEN"
        val failedBubbleIds = linkedSetOf<Long>()
        val safeUserId = userId ?: return failedBubbleIds
        if (bubbleIds.isEmpty()) return failedBubbleIds
        val isBatchCollect = shouldUseBatchRobEnergyOnForestHomePage(fromTag, bubbleIds.size)
        if (isBatchCollect) {
            var i = 0
            while (i < bubbleIds.size) {
                val subList: MutableList<Long> =
                    bubbleIds.subList(i, min(i + MAX_BATCH_SIZE, bubbleIds.size))
                val collectEnergyEntity = CollectEnergyEntity(
                    userId = safeUserId,
                    userHome = userHomeObj,
                    rpcEntity = AntForestRpcCall.batchEnergyRpcEntity(bizType, safeUserId, subList, rpcSource),
                    fromTag = fromTag,
                    skipPropCheck = skipPropCheck,
                    bizType = bizType,
                    rpcSource = rpcSource
                )
                collectEnergy(collectEnergyEntity)
                failedBubbleIds.addAll(collectEnergyEntity.failedBubbleIds)
                i += MAX_BATCH_SIZE
            }
        } else {
            for (id in bubbleIds) {
                val collectEnergyEntity = CollectEnergyEntity(
                    userId = safeUserId,
                    userHome = userHomeObj,
                    rpcEntity = AntForestRpcCall.energyRpcEntity(bizType, safeUserId, id, rpcSource),
                    fromTag = fromTag,
                    skipPropCheck = skipPropCheck,
                    bizType = bizType,
                    rpcSource = rpcSource
                )
                collectEnergy(collectEnergyEntity)
                failedBubbleIds.addAll(collectEnergyEntity.failedBubbleIds)
            }
        }
        return failedBubbleIds
    }

    // “一键收取”配置只对应好友/PK 好友主页的批量按钮语义，不能外溢到找能量或蹲点链路。
    private fun shouldUseBatchRobEnergyOnForestHomePage(fromTag: String?, bubbleCount: Int): Boolean {
        if (batchRobEnergy?.value != true || bubbleCount <= 1) {
            return false
        }
        return when (fromTag) {
            "friend", "pk" -> true
            else -> false
        }
    }

    /**
     * 函数式接口，用于提供RPC调用
     */
    private fun interface RpcSupplier<T> {
        @Throws(Exception::class)
        fun get(): T?
    }

    /**
     * 函数式接口，用于对JSON对象进行断言
     */
    private fun interface JsonPredicate<T> {
        @Throws(Exception::class)
        fun test(t: T?): Boolean
    }

    /**
     * 协程版本的排行榜收取方法
     */

    private suspend fun collectRankingsCoroutine(
        rankingName: String?,
        rpcCall: RpcSupplier<String?>,
        jsonArrayKey: String?,
        flag: String,
        preCondition: JsonPredicate<JSONObject?>?
    ) = withContext(Dispatchers.Default) {
        try {
            Log.forest("开始处理$rankingName...")
            val tc = TimeCounter(TAG)
            var rankingObject: JSONObject? = null
            for (i in 0..2) {
                var response: String? = null
                try {
                    response = rpcCall.get()
                    if (response != null && !response.isEmpty()) {
                        rankingObject = JSONObject(response)
                        break
                    }
                } catch (e: Exception) {
                    Log.printStackTrace(
                        TAG,
                        "collectRankings $rankingName, response: $response",
                        e
                    )
                }
                if (i < 2) {
                    Log.forest("获取" + rankingName + "失败，" + (5 * (i + 1)) + "秒后重试")
                    GlobalThreadPools.sleepCompat(5000L * (i + 1))
                }
            }

            if (rankingObject == null) {
                Log.error(TAG, "获取" + rankingName + "失败")
                return@withContext
            }
            if (!ResChecker.checkRes(TAG, "获取" + rankingName + "失败:", rankingObject)) {
                Log.error(
                    TAG,
                    "获取" + rankingName + "失败: " + rankingObject.optString("resultDesc")
                )
                return@withContext
            }
            val totalDatas = rankingObject.optJSONArray(jsonArrayKey)
            if (totalDatas == null) {
                Log.forest(rankingName + "数据为空，跳过处理。")
                return@withContext
            }
            Log.forest("成功获取" + rankingName + "数据，共发现" + totalDatas.length() + "位好友。"
            )
            tc.countDebug("获取$rankingName")
            if (preCondition != null && !preCondition.test(rankingObject)) {
                return@withContext
            }
            // 处理前20个  超过会报错
            Log.forest("开始处理" + rankingName + "前20位好友...")
            val friendRanking = rankingObject.optJSONArray("friendRanking")
            if (friendRanking != null) {
                if (flag == "pk") {
                    val frontUserIds = mutableListOf<String?>()
                    for (index in 0 until friendRanking.length()) {
                        frontUserIds.add(friendRanking.optJSONObject(index)?.optString("userId"))
                    }
                    processFriendsEnergyCoroutine(frontUserIds, flag, "${rankingName}前20位")
                } else {
                    processFriendsEnergyCoroutine(friendRanking, flag, "${rankingName}前20位")
                }
            }
            tc.countDebug("处理" + rankingName + "靠前的好友")
            // 分批并行处理后续的（协程版本）
            if (totalDatas.length() <= 20) {
                Log.forest(rankingName + "没有更多的好友需要处理，跳过")
                return@withContext
            }

            // 处理所有好友（无限制模式）
            val remainingToProcess = totalDatas.length() - 20

            if (remainingToProcess <= 0) {
                Log.forest(rankingName + "已处理前20位好友，跳过后续处理")
                return@withContext
            }

            val idList: MutableList<String?> = ArrayList()
            val batchSize = 20
            val batches = (remainingToProcess + batchSize - 1) / batchSize
            Log.forest("🌟 处理所有好友：" + rankingName + "共${totalDatas.length()}位好友，需处理后续${remainingToProcess}位，共${batches}批"
            )

            // 串行处理批次，避免总并发数过高
            var batchCount = 0

            for (pos in 20..<totalDatas.length()) {
                // 检查协程是否被取消
                if (!isActive) {
                    Log.forest("协程被取消，停止处理${rankingName}批次")
                    return@withContext
                }

                val friend = totalDatas.getJSONObject(pos)
                val userId = friend.getString("userId")
                if (userId == selfId) continue
                idList.add(userId)

                if (idList.size == batchSize) {
                    val batch: MutableList<String?> = ArrayList(idList)
                    val currentBatchNum = ++batchCount

                    // 串行执行：等待当前批次完成再处理下一批次
                    Log.forest("[批次$currentBatchNum/$batches] 开始处理...")
                    try {
                        processFriendsEnergyCoroutine(batch, flag, "批次$currentBatchNum")
                        Log.forest("[批次$currentBatchNum/$batches] 处理完成")
                    } catch (e: CancellationException) {
                        Log.forest("[批次$currentBatchNum/$batches] 被取消")
                        throw e
                    }

                    idList.clear()
                }
            }

            // 处理剩余的用户
            if (idList.isNotEmpty()) {
                // 检查协程是否被取消
                if (!isActive) {
                    Log.forest("协程被取消，跳过${rankingName}剩余用户处理")
                    return@withContext
                }

                val currentBatchNum = ++batchCount
                Log.forest("[批次$currentBatchNum/$batches] 开始处理...")
                try {
                    processFriendsEnergyCoroutine(idList, flag, "批次$currentBatchNum")
                    Log.forest("[批次$currentBatchNum/$batches] 处理完成")
                } catch (e: CancellationException) {
                    Log.forest("[批次$currentBatchNum/$batches] 被取消")
                    throw e
                }
            }
            tc.countDebug("分批处理" + rankingName + "其他好友")
            Log.forest("收取" + rankingName + "能量完成！")
        } catch (e: CancellationException) {
            // 协程被取消是正常行为，不记录错误日志
            Log.forest("处理" + rankingName + "时协程被取消")
            throw e // 重新抛出，让协程系统处理
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "collectRankings 异常", e)
        }
    }

    /**
     * 协程版本：收取PK好友能量
     */
    internal suspend fun collectPKEnergyCoroutine() {
        if (!isCollectEnergyEnabled() || pkEnergy?.value != true) {
            Log.forest("收集能量或PK榜收取未开启，跳过PK排行榜扫描")
            return
        }

        collectRankingsCoroutine(
            "PK排行榜",
            { AntForestRpcCall.queryTopEnergyChallengeRanking() },
            "totalData",
            "pk",
            JsonPredicate { pkObject: JSONObject? ->
                val memberStatus = pkObject?.optString("rankMemberStatus").orEmpty()
                if (memberStatus == "JOIN") {
                    if (Status.hasFlagToday(StatusFlags.FLAG_ANTFOREST_PK_SKIP_TODAY)) {
                        Log.forest("PK排行榜：复核到赛季状态为JOIN，清除今日跳过标记并执行补全")
                        Status.removeFlag(StatusFlags.FLAG_ANTFOREST_PK_SKIP_TODAY)
                    }
                    return@JsonPredicate true
                }
                if (memberStatus.isBlank()) {
                    Log.forest("PK排行榜状态为空，暂不写入今日跳过标记")
                    return@JsonPredicate false
                }
                if (Status.hasFlagToday(StatusFlags.FLAG_ANTFOREST_PK_SKIP_TODAY)) {
                    Log.forest("PK排行榜：今日已判定无需处理，复核状态[$memberStatus]仍不可补全，跳过")
                } else {
                    Log.forest("未加入PK排行榜/赛季未开启[$memberStatus]，今日跳过PK补全以避免风控")
                    Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_PK_SKIP_TODAY)
                }
                false
            }
        )
    }

    /**
     * 使用找能量功能收取好友能量（协程版本）
     * 这是一个更高效的收取方式，可以直接找到有能量的好友
     */
    /**
     * 使用找能量功能收取好友能量（协程版本 - 修正版）
     * 逻辑：服务器自动轮询，返回空 friendId 代表无更多目标
     */
    internal fun collectEnergyByTakeLook(source: String? = null): JSONObject? {
        if (!isCollectEnergyEnabled()) {
            Log.forest("收集能量开关关闭，跳过找能量接口")
            return null
        }

        // 1. 冷却检查
        val currentTime = System.currentTimeMillis()
        if (currentTime < nextTakeLookTime) {
            val remaining = (nextTakeLookTime - currentTime) / 1000
            Log.forest("找能量冷却中，等待 ${remaining / 60}分${remaining % 60}秒")
            return null
        }

        val tc = TimeCounter(TAG)
        var foundCount = 0
        val maxAttempts = TAKE_LOOK_MAX_ATTEMPTS
        var consecutiveEmpty = 0
        var shouldCooldown = false
        var firstTakeLook = true
        var firstTakeLookExposedUserId = ""
        val actualSource = source?.takeIf { it.isNotBlank() }
        var takeLookSessionStarted = false
        var takeLookEndRequested = false
        var takeLookEndPayload: JSONObject? = null

        // 本地去重集合：防止单次运行中服务器重复返回同一个有保护罩的人
        val visitedInSession = mutableSetOf<String>()

        Log.forest("开始找能量 (服务器自动轮询)")

        fun requestTakeLookEndIfNeeded(): JSONObject? {
            if (takeLookEndRequested) {
                return takeLookEndPayload
            }
            takeLookEndRequested = true
            takeLookEndPayload = queryTakeLookEndPayload(actualSource)
            return takeLookEndPayload
        }

        fun extractTakeLookExposeFriendId(combineBizObj: JSONObject): String? {
            val rootFriendId = combineBizObj
                .optJSONObject("combineHandlerVOMap")
                ?.optJSONObject("takeLookExpose")
                ?.optString("friendUserId")
                ?.takeIf { it.isNotBlank() }
            if (!rootFriendId.isNullOrBlank()) return rootFriendId

            return combineBizObj
                .optJSONObject("resData")
                ?.optJSONObject("combineHandlerVOMap")
                ?.optJSONObject("takeLookExpose")
                ?.optString("friendUserId")
                ?.takeIf { it.isNotBlank() }
        }

        try {
            try {
                val combineBizResult = AntForestRpcCall.queryTakeLookCombineBiz(
                    buildTakeLookSkipUsers(),
                    source,
                    takeLookExposedTimes = 0,
                    takeLookExposed = false
                )
                if (combineBizResult.isNotBlank()) {
                    val combineBizObj = JSONObject(combineBizResult)
                    val exposedFriendId = extractTakeLookExposeFriendId(combineBizObj)
                    if (!exposedFriendId.isNullOrBlank()) {
                        firstTakeLookExposedUserId = exposedFriendId
                        Log.forest("找能量预曝光目标已更新")
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "找能量预曝光接口异常", e)
            }

            loop@ for (attempt in 1..maxAttempts) {
                // A. 调用接口
                val takeLookStartedThisRound = firstTakeLook
                val takeLookResult = try {
                    takeLookSessionStarted = true
                    val resStr = AntForestRpcCall.takeLook(
                        buildTakeLookSkipUsers(),
                        actualSource,
                        exposedUserId = if (takeLookStartedThisRound) firstTakeLookExposedUserId else "",
                        takeLookStart = takeLookStartedThisRound
                    )
                    firstTakeLook = false
                    JSONObject(resStr)
                } catch (e: Exception) {
                    Log.printStackTrace(TAG, "找能量接口异常", e)
                    shouldCooldown = true
                    break@loop
                }

                // B. 检查接口返回是否成功
                if (!ResChecker.checkRes("$TAG 接口业务失败:", takeLookResult)) {
                    break@loop
                }

                val takeLookEnded = takeLookResult.optBoolean("takeLookEnd", false)
                val friendId = takeLookResult.optString("friendId")
                val actionType = takeLookResult.optString("actionType")
                if (actionType.isNotBlank() && actionType != "FRIEND") {
                    Log.forest("找能量返回非好友动作[$actionType]，结束")
                    if (!takeLookEnded) {
                        requestTakeLookEndIfNeeded()
                    }
                    break@loop
                }
                if (friendId.isBlank()) {
                    val actionSuffix = if (actionType.isBlank()) "" else "[$actionType]"
                    Log.forest("找能量返回${actionSuffix}无有效好友，结束")
                    if (!takeLookEnded) {
                        requestTakeLookEndIfNeeded()
                    }
                    break@loop
                }

                // D. 排除自己
                if (friendId == selfId) {
                    Log.forest("发现自己，跳过")
                    if (takeLookEnded) {
                        Log.forest("找能量已达到官方结束状态，结束")
                        break@loop
                    }
                    consecutiveEmpty++ // 某种意义上也是无效结果
                    continue@loop
                }

                // E. 本地重复检查 (防止死循环刷同一个有盾的人)
                val fromAct = if (takeLookStartedThisRound) "TAKE_LOOK" else "TAKE_LOOK_FRIEND"
                if (visitedInSession.contains(friendId)) {
                    Log.forest("本次已检查过用户($friendId)，跳过")
                    if (takeLookEnded) {
                        Log.forest("找能量已达到官方结束状态，结束")
                        break@loop
                    }
                    consecutiveEmpty++
                    if (consecutiveEmpty >= 3) break@loop // 如果一直重复返回已访问的人，也没必要继续了
                    continue@loop
                }

                // 标记已访问
                visitedInSession.add(friendId)

                if (processedUsersCache.contains(friendId)) {
                    consecutiveEmpty++
                    Log.forest("本轮已处理用户($friendId)，跳过")
                    if (takeLookEnded) {
                        Log.forest("找能量已达到官方结束状态，结束")
                        break@loop
                    }
                    continue@loop
                }

                // F. 检查全局黑名单 (如之前炸弹被记录的人)
                if (skipUsersCache.containsKey(friendId)) {
                    processedUsersCache.add(friendId)
                    consecutiveEmpty++
                    Log.forest("找能量返回已跳过用户($friendId)，跳过")
                    if (takeLookEnded) {
                        Log.forest("找能量已达到官方结束状态，结束")
                        break@loop
                    }
                    continue@loop
                }
                // G. 查询主页详情
                val friendHomeObj = queryTakeLookFriendHome(friendId, actualSource, fromAct)
                if (friendHomeObj == null) {
                    if (takeLookEnded) {
                        Log.forest("找能量已达到官方结束状态，结束")
                        break@loop
                    }
                    continue@loop
                }

                // H. 检查保护罩/炸弹
                val now = System.currentTimeMillis()
                val hasShield = hasShield(friendHomeObj, now)
                val hasBomb = hasBombCard(friendHomeObj, now)

                if (hasShield || hasBomb) {
                    val friendName = UserMap.getMaskName(friendId) ?: "未知好友"
                    val type = if (hasShield) "保护罩" else "炸弹卡"
                    Log.forest("发现[$friendName]有$type，跳过")
                    // 记录到全局缓存，防止下次运行再次浪费时间查询
                    addToSkipUsers(friendId)
                    processedUsersCache.add(friendId)
                    // 后续 takeLook 请求会带上 skipUsers，排行榜补齐也会通过 processedUsersCache 跳过本轮已确认保护的好友
                } else {
                    // I. 收取能量
                    collectEnergy(friendId, friendHomeObj, "takeLook", rpcSource = actualSource)
                    handleFriendExtraBenefits(friendId, friendHomeObj)
                    foundCount++
                    consecutiveEmpty = 0 // 重置空计数

                }
                if (hasShield || hasBomb) {
                    handleFriendExtraBenefits(friendId, friendHomeObj)
                }
                if (takeLookEnded) {
                    Log.forest("找能量已处理官方结束前最后一个目标，结束")
                    break@loop
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "找能量流程异常", e)
        } finally {
            if (takeLookSessionStarted && !takeLookEndRequested) {
                takeLookEndPayload = requestTakeLookEndIfNeeded()
            }
            if (takeLookEndPayload != null) {
                processTakeLookEndTaskList(actualSource, takeLookEndPayload)
            }
            // 逻辑结束后的状态处理
            if (shouldCooldown) {
                nextTakeLookTime = System.currentTimeMillis() + TAKE_LOOK_COOLDOWN_MS
            } else {
                // 正常结束，下次可立即执行（或者根据需求设置一个小间隔）
                nextTakeLookTime = 0
            }
            val msg = "找能量结束，本次收取: $foundCount 个"
            Log.forest(msg)
            tc.countDebug(msg)
        }
        return takeLookEndPayload
    }

    /**
     * 将用户添加到跳过列表（内存缓存）
     *
     * @param userId 用户ID
     */
    private fun addToSkipUsers(userId: String?) {
        try {
            if (!userId.isNullOrEmpty()) {
                skipUsersCache[userId] = "baohuzhao"
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "添加跳过用户失败", e)
        }
    }

    private fun buildTakeLookSkipUsers(): JSONObject {
        val skipUsers = JSONObject()
        try {
            skipUsersCache.forEach { (userId, reason) ->
                if (userId.isNotBlank()) {
                    skipUsers.put(userId, reason)
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "构建找能量跳过用户失败", e)
        }
        return skipUsers
    }

    private fun queryTakeLookFriendHome(
        userId: String,
        source: String? = null,
        fromAct: String = "TAKE_LOOK_FRIEND"
    ): JSONObject? {
        return queryFriendHome(userId, fromAct, source = source, fallbackFromAct = "PKContest")
    }

    /**
     * 协程版本：收取好友能量
     */
    internal suspend fun collectFriendEnergyCoroutine() {
        if (!hasFriendRankingWorkEnabled()) {
            Log.forest("收能量未开启，跳过好友主页扫描；好友礼盒/复活能量仅随已获取好友主页处理")
            return
        }
        resetRebornScanStateForFriendRanking()
        var cancelled = false
        try {
            collectRankingsCoroutine(
                "好友排行榜",
                { AntForestRpcCall.queryFriendsEnergyRanking() },
                "totalDatas",
                "普通好友",
                null
            )
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            if (!cancelled) {
                finalizeRebornWeeklyStateAfterFriendRanking()
            }
        }
    }

    /**
     * 统一的协程批量好友处理方法
     *
     * @param friendSource 好友数据源，可以是：
     *   - JSONArray: 直接的好友列表
     *   - MutableList<String?>: 用户ID列表，需要通过API获取
     * @param flag 标记（空字符串=普通好友，"pk"=PK好友）
     * @param sourceName 数据源名称（用于日志）
     */
    private suspend fun processFriendsEnergyCoroutine(
        friendSource: Any,
        flag: String,
        sourceName: String = "好友"
    ) = withContext(Dispatchers.Default) {
        try {
            if (errorWait) return@withContext

            val friendList: JSONArray? = when (friendSource) {
                is JSONArray -> {
                    // 直接的好友列表
                    friendSource
                }

                is MutableList<*> -> {
                    // 用户ID列表，需要通过API获取详细信息
                    @Suppress("UNCHECKED_CAST")
                    val userIds = friendSource as MutableList<String?>
                    val jsonStr = if (flag == "pk") {
                        AntForestRpcCall.fillUserRobFlag(JSONArray(userIds), true)
                    } else {
                        AntForestRpcCall.fillUserRobFlag(JSONArray(userIds))
                    }
                    val batchObj = JSONObject(jsonStr)
                    batchObj.optJSONArray("friendRanking")
                }

                else -> {
                    Log.error(TAG, "不支持的好友数据源类型: ${friendSource.javaClass.simpleName}")
                    return@withContext
                }
            }

            if (friendList == null) {
                Log.forest("${sourceName}数据为空，跳过处理")
                return@withContext
            }

            if (friendList.length() == 0) {
                Log.forest("${sourceName}列表为空，跳过处理")
                return@withContext
            }

            // 先收集并显示所有好友名单
            val friendNames = mutableListOf<String>()
            for (i in 0..<friendList.length()) {
                val friendObj = friendList.getJSONObject(i)
                val userId = friendObj.optString("userId", "")
                val displayName = friendObj.optString("displayName", UserMap.getMaskName(userId) ?: userId)
                friendNames.add(displayName)
            }

            Log.forest("📋 开始处理${friendList.length()}个${sourceName}（并发数:$friendProcessConcurrencyInt）")
            Log.forest("👥 ${friendNames.joinToString(" | ")}")
            val startTime = System.currentTimeMillis()

            // 使用协程并发处理每个好友（带并发控制）
            val friendJobs = mutableListOf<Deferred<Unit>>()
            for (i in 0..<friendList.length()) {
                val friendObj = friendList.getJSONObject(i)
                val job = async {
                    concurrencyLimiter.acquire()
                    try {
                        // 直接调用内部方法，减少一层包装以提高性能
                        processEnergyInternal(friendObj, flag)
                    } catch (e: Exception) {
                        Log.printStackTrace(TAG, "处理好友异常", e)
                    } finally {
                        concurrencyLimiter.release()
                    }
                }
                friendJobs.add(job)
            }

            // 等待所有好友处理完成
            friendJobs.awaitAll()
            val elapsed = System.currentTimeMillis() - startTime
            Log.forest("✅ ${sourceName}处理完成，耗时${elapsed}ms，平均${elapsed / friendList.length()}ms/人")

        } catch (e: CancellationException) {
            // 协程被取消是正常行为，不记录错误日志
            Log.forest("处理${sourceName}时协程被取消")
            throw e // 重新抛出，让协程系统处理
        } catch (e: JSONException) {
            Log.printStackTrace(TAG, "解析${sourceName}数据失败", e)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "处理${sourceName}出错", e)
        }
    }

    /**
     * 处理单个好友的核心逻辑（无锁）
     *
     * @param obj  好友/PK好友 的JSON对象
     * @param flag 标记是普通好友还是PK好友
     */
    @Throws(Exception::class)
    private fun processEnergyInternal(obj: JSONObject, flag: String?) {
        if (errorWait) return
        val userId = FriendGuard.normalizeUserId(obj.optString("userId"))
        val isPk = "pk" == flag
        val sceneName = if (isPk) "PK好友收能量" else "好友收能量"
        if (userId == null) {
            Log.record(TAG, "$sceneName 跳过：userId为空")
            return
        }
        if (isPk) {
            if (FriendGuard.isSelf(userId)) {
                Log.record(TAG, "$sceneName 跳过自己账号[$userId]")
                return
            }
        } else if (FriendGuard.shouldSkipFriend(userId, TAG, sceneName)) {
            return
        }
        // 检查是否在"手速太快"冷却期
        if (ForestUtil.isUserInFrequencyCooldown(userId)) {
            return  // 跳过处理
        }
        var userName = obj.optString("displayName", UserMap.getMaskName(userId) ?: userId)
        if (emptyForestCache.containsKey(userId)) { //本轮已知为空的树林
            return
        }

        if (isPk) {
            userName = "PK榜好友|$userName"
        }
        //  Log.forest("  processEnergy 开始处理用户: [" + userName + "], 类型: " + (isPk ? "PK" : "普通"));
        if (isPk) {
            val needCollectEnergy = collectEnergy?.value == true && pkEnergy?.value == true
            if (!needCollectEnergy) {
                Log.forest("    PK好友: [$userName$userId], 不满足收取条件，跳过")
                return
            }
            if (processedUsersCache.contains(userId)) {
                Log.forest("    PK好友: [$userName$userId], 本轮已处理，跳过主页查询")
                return
            }
            Log.forest("  正在查询PK好友 [$userName$userId] 的主页...")
            collectEnergy(userId, queryFriendHome(userId, "PKContest"), "pk")
        } else { // 普通好友
            val needCollectEnergy = collectEnergy?.value == true && !jsonCollectMap.contains(userId)
            val energyAlreadyProcessed = processedUsersCache.contains(userId)
            val shouldQueryForEnergy = needCollectEnergy && !energyAlreadyProcessed
            if (!shouldQueryForEnergy) {
                //   Log.forest("    普通好友: [$userName$userId], 所有条件不满足，跳过")
                return
            }
            Log.forest("  正在查询好友 [$userName$userId] 的主页...")
            val userHomeObj = collectEnergy(userId, queryFriendHome(userId, null), "friend")
            handleFriendExtraBenefits(userId, userHomeObj)
        }
    }

    private fun isIsProtected(userId: String?): Boolean {
        if (userId.isNullOrEmpty()) return false
        val type = helpFriendCollectType?.value ?: HelpFriendCollectType.NONE
        if (type == HelpFriendCollectType.NONE) return false

        val selected = helpFriendCollectList?.contains(userId) == true
        return when (type) {
            HelpFriendCollectType.HELP -> selected
            HelpFriendCollectType.EXCLUDE -> !selected
            else -> !selected
        }
    }

    /** lzw add end */
    /**
     * 协程版本：收取排名靠前好友能量
     */
    private fun collectGiftBox(userHomeObj: JSONObject) {
        try {
            val giftBoxInfo = userHomeObj.optJSONObject("giftBoxInfo")
            val userEnergy = userHomeObj.optJSONObject("userEnergy")
            val userId =
                if (userEnergy == null) UserMap.currentUid else userEnergy.optString("userId")
            val safeUserId = userId ?: return
            if (giftBoxInfo != null) {
                val giftBoxList = giftBoxInfo.optJSONArray("giftBoxList")
                if (giftBoxList != null && giftBoxList.length() > 0) {
                    for (ii in 0..<giftBoxList.length()) {
                        try {
                            val giftBox = giftBoxList.getJSONObject(ii)
                            val giftBoxId = giftBox.getString("giftBoxId")
                            val title = giftBox.getString("title")
                            val giftBoxResult =
                                JSONObject(AntForestRpcCall.collectFriendGiftBox(giftBoxId, safeUserId))
                            if (!ResChecker.checkRes(TAG, "领取好友礼盒失败:", giftBoxResult)) {
                                Log.forest(giftBoxResult.getString("resultDesc"))
                                Log.forest(giftBoxResult.toString())
                                continue
                            }
                            val energy = giftBoxResult.optInt("energy", 0)
                            if (energy > 0) {
                                selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                                    Statistics.addData(uid, Statistics.DataType.COLLECTED, energy)
                                    totalCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
                                } ?: run {
                                    totalCollected += energy
                                }
                            }
                            Log.forest("礼盒能量🎁[" + UserMap.getMaskName(safeUserId) + "-" + title + "]#" + energy + "g")
                        } catch (t: Throwable) {
                            Log.printStackTrace(t)
                            break
                        } finally {
                            GlobalThreadPools.sleepCompat(500L)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    private fun protectFriendEnergy(userHomeObj: JSONObject) {
        try {
            val wateringBubbles = userHomeObj.optJSONArray("wateringBubbles")
            val userEnergy = userHomeObj.optJSONObject("userEnergy")
            val userId =
                if (userEnergy == null) UserMap.currentUid else userEnergy.optString("userId")
            val safeUserId = userId ?: return
            if (!canTryRebornProtectNow(safeUserId)) return
            if (wateringBubbles != null && wateringBubbles.length() > 0) {
                for (j in 0..<wateringBubbles.length()) {
                    try {
                        val wateringBubble = wateringBubbles.getJSONObject(j)
                        if ("fuhuo" != wateringBubble.getString("bizType")) {
                            continue
                        }
                        if (wateringBubble.getJSONObject("extInfo").optInt("restTimes", 0) == 0) {
                            markRebornLimitReachedToday()
                            break
                        }
                        if (!wateringBubble.getBoolean("canProtect")) {
                            continue
                        }
                        val fullEnergy = wateringBubble.optInt("fullEnergy", 0)
                        val limit = helpFriendCollectListLimit?.value ?: 0
                        if (fullEnergy < limit) continue
                        rebornScanFoundProtectable.set(true)
                        val joProtect = JSONObject(AntForestRpcCall.protectBubble(safeUserId))
                        val protectResultCode = joProtect.optString("resultCode")
                        val protectResultDesc = joProtect.optString("resultDesc")
                            .ifBlank { joProtect.optString("memo") }
                        if (protectResultCode == "PROTECT_REBORN_TIRED" ||
                            protectResultDesc.contains("复活能量次数已用完")
                        ) {
                            markRebornLimitReachedToday()
                            Log.forest("复活能量今日次数已用完，已记录为当日限制")
                            break
                        }
                        if (!ResChecker.checkRes(TAG, "复活能量失败:", joProtect)) {
                            //Log.forest(joProtect.getString("resultDesc"))
                            //Log.runtime(joProtect.toString())
                            continue
                        }
                        val vitalityAmount = joProtect.optInt("vitalityAmount", 0)
                        val str =
                            "复活能量🚑[" + UserMap.getMaskName(safeUserId) + "-" + fullEnergy + "g]" + (if (vitalityAmount > 0) "#活力值+$vitalityAmount" else "")
                        Log.forest(str)
                        if (fullEnergy > 0) {
                            selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                                Statistics.addData(uid, Statistics.DataType.HELPED, fullEnergy)
                                totalHelpCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.HELPED)
                            } ?: run {
                                totalHelpCollected += fullEnergy
                            }
                        }
                        break
                    } catch (t: Throwable) {
                        Log.printStackTrace(t)
                        break
                    } finally {
                        GlobalThreadPools.sleepCompat(500)
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
    }

    private fun shouldCollectGiftBoxFromHome(userHomeObj: JSONObject): Boolean {
        if (collectGiftBox?.value != true) {
            return false
        }
        val userEnergy = userHomeObj.optJSONObject("userEnergy")
        if (userEnergy?.optBoolean("canCollectGiftBox") == true) {
            return true
        }
        val giftBoxList = userHomeObj.optJSONObject("giftBoxInfo")?.optJSONArray("giftBoxList")
        return giftBoxList != null && giftBoxList.length() > 0
    }

    private fun shouldProtectFriendEnergyFromHome(userId: String?, userHomeObj: JSONObject): Boolean {
        if (!canTryRebornProtectNow(userId)) return false

        val limit = helpFriendCollectListLimit?.value ?: 0
        val wateringBubbles = userHomeObj.optJSONArray("wateringBubbles") ?: return false
        for (i in 0 until wateringBubbles.length()) {
            val wateringBubble = wateringBubbles.optJSONObject(i) ?: continue
            if ("fuhuo" != wateringBubble.optString("bizType")) continue

            val restTimes = wateringBubble.optJSONObject("extInfo")?.optInt("restTimes", -1) ?: -1
            if (restTimes == 0) {
                markRebornLimitReachedToday()
                return false
            }

            if (!wateringBubble.optBoolean("canProtect")) continue
            val fullEnergy = wateringBubble.optInt("fullEnergy", 0)
            if (fullEnergy < limit) continue

            rebornScanFoundProtectable.set(true)
            return true
        }
        return false
    }

    private fun handleFriendExtraBenefits(userId: String?, userHomeObj: JSONObject?) {
        val safeUserId = userId ?: return
        val safeUserHomeObj = userHomeObj ?: return
        if (shouldCollectGiftBoxFromHome(safeUserHomeObj) && handledGiftBoxUsers.add(safeUserId)) {
            collectGiftBox(safeUserHomeObj)
        }
        if (shouldProtectFriendEnergyFromHome(safeUserId, safeUserHomeObj) && handledProtectUsers.add(safeUserId)) {
            protectFriendEnergy(safeUserHomeObj)
        }
    }

    private fun collectEnergy(collectEnergyEntity: CollectEnergyEntity) {
        if (errorWait) {
            Log.forest("异常⌛等待中...不收取能量")
            return
        }
        val runnable = Runnable {
            try {
                val userId = collectEnergyEntity.userId
                // 从 CollectEnergyEntity 中读取是否跳过道具检查的标记
                val skipPropCheck = collectEnergyEntity.skipPropCheck
                usePropBeforeCollectEnergy(userId, skipPropCheck)
                val rpcEntity = collectEnergyEntity.rpcEntity ?: run {
                    Log.error(TAG, "collectEnergy: rpcEntity is null for userId=$userId")
                    return@Runnable
                }
                val needDouble = collectEnergyEntity.needDouble
                val needRetry = collectEnergyEntity.needRetry
                val tryCount = collectEnergyEntity.addTryCount()
                var collected = 0
                val startTime: Long

                synchronized(collectEnergyLockLimit) {
                    val sleep: Long
                    if (needDouble) {
                        collectEnergyEntity.unsetNeedDouble()
                        val interval = doubleCollectIntervalEntity!!.interval
                        sleep =
                            (interval ?: 1000) - System.currentTimeMillis() + collectEnergyLockLimit.get()!!
                    } else if (needRetry) {
                        collectEnergyEntity.unsetNeedRetry()
                        sleep =
                            retryIntervalInt!! - System.currentTimeMillis() + collectEnergyLockLimit.get()!!
                    } else {
                        val interval = collectIntervalEntity!!.interval
                        sleep =
                            (interval ?: 1000) - System.currentTimeMillis() + collectEnergyLockLimit.get()!!
                    }
                    if (sleep > 0) {
                        GlobalThreadPools.sleepCompat(sleep)
                    }
                    startTime = System.currentTimeMillis()
                    collectEnergyLockLimit.setForce(startTime)
                }

                requestString(rpcEntity, 0, 0)
                val spendTime = System.currentTimeMillis() - startTime
                if (balanceNetworkDelay?.value == true) {
                    delayTimeMath.nextInteger((spendTime / 3).toInt())
                }

                if (rpcEntity.hasError) {
                    val errorCode = runCatching {
                        val responseObject = rpcEntity.responseObject ?: return@runCatching null
                        responseObject.javaClass.getMethod("getString", String::class.java)
                            .invoke(responseObject, "error") as? String
                    }.getOrNull()
                    if ("1004" == errorCode) {
                        val waitWhenExceptionMs = (BaseModel.waitWhenException.value ?: 0).toLong()
                        if (waitWhenExceptionMs > 0) {
                            val waitTime =
                                System.currentTimeMillis() + waitWhenExceptionMs
                            RuntimeInfo.getInstance()
                                .put(RuntimeInfo.RuntimeInfoKey.ForestPauseTime, waitTime)
                            updateRunningStatus("异常")
                            Log.forest("触发异常,等待至" + TimeUtil.getCommonDate(waitTime))
                            errorWait = true
                            return@Runnable
                        }
                    }
                    if (tryCount < tryCountInt!!) {
                        collectEnergyEntity.setNeedRetry()
                        collectEnergy(collectEnergyEntity)
                    }
                    return@Runnable
                }

                val responseString: String = rpcEntity.responseString ?: ""
                val jo = JSONObject(responseString)
                val resultCode = jo.optString("resultCode")
                if (!jo.optBoolean("success") && !"SUCCESS".equals(resultCode, ignoreCase = true)) {
                    if ("PARAM_ILLEGAL2" == resultCode) {
                        Log.forest("[" + getAndCacheUserName(userId) + "]" + "能量已被收取,取消重试 错误:" + jo.getString("resultDesc"))
                        return@Runnable
                    }

                    // 检测并记录"手速太快"错误
                    if (ForestUtil.checkAndRecordFrequencyError(userId, jo)) {
                        return@Runnable
                    }

                    Log.forest("[" + getAndCacheUserName(userId) + "]" + jo.optString("resultDesc", ""))
                    if (tryCount < tryCountInt!!) {
                        collectEnergyEntity.setNeedRetry()
                        collectEnergy(collectEnergyEntity)
                    }
                    return@Runnable
                }
                if ("PARTIAL_SUCCESS".equals(resultCode, ignoreCase = true)) {
                    val failedBubbleIds = jo.optJSONArray("failedBubbleIds")
                    if (failedBubbleIds != null && failedBubbleIds.length() > 0) {
                        collectEnergyEntity.recordFailedBubbleIds(failedBubbleIds)
                        Log.runtime(TAG, "[" + getAndCacheUserName(userId) + "]收取能量部分成功: " + jo.optString("resultDesc") + ", failedBubbleIds=" + failedBubbleIds)
                    }
                }

                // 炸弹卡效果：记录“被炸”掉的能量
                val explodeEnergy = jo.optJSONObject("bombCardEffect")?.optInt("explodeEnergy", 0) ?: 0
                val bombSuffix = if (explodeEnergy > 0) "被炸${explodeEnergy}g" else ""

                // --- 收能量逻辑保持原样 ---
                val jaBubbles = jo.getJSONArray("bubbles")
                val jaBubbleLength = jaBubbles.length()
                if (jaBubbleLength > 1) {
                    val newBubbleIdList = ArrayList<Long>()
                    for (i in 0..<jaBubbleLength) {
                        val bubble = jaBubbles.getJSONObject(i)
                        if (bubble.getBoolean("canBeRobbedAgain")) {
                            newBubbleIdList.add(bubble.getLong("id"))
                        }
                        collected += bubble.getInt("collectedEnergy")
                    }
                    if (collected > 0) {
                        val randomIndex = random.nextInt(emojiList.size)
                        val randomEmoji = emojiList[randomIndex]
                        val collectType = "一键收取️"
                        val str =
                            collectType + randomEmoji + collected + "g[" + getAndCacheUserName(
                                userId
                            ) + "]#" + bombSuffix
                        selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                            Statistics.addData(uid, Statistics.DataType.COLLECTED, collected)
                            totalCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
                        } ?: run {
                            totalCollected += collected
                        }
                        if (needDouble) {
                            Log.forest(str + "耗时[" + spendTime + "]ms[双击]")
                            Toast.show("$str[双击]")
                        } else {
                            Log.forest(str + "耗时[" + spendTime + "]ms")
                            Toast.show(str)
                        }
                    }
                    if (!newBubbleIdList.isEmpty()) {
                        collectEnergyEntity.rpcEntity = AntForestRpcCall.batchEnergyRpcEntity(
                            collectEnergyEntity.bizType,
                            userId,
                            newBubbleIdList,
                            collectEnergyEntity.rpcSource
                        )
                        collectEnergyEntity.setNeedDouble()
                        collectEnergyEntity.resetTryCount()
                        collectEnergy(collectEnergyEntity)
                    }
                } else if (jaBubbleLength == 1) {
                    val bubble = jaBubbles.getJSONObject(0)
                    collected += bubble.getInt("collectedEnergy")
                    if (collected > 0) {
                        val randomIndex = random.nextInt(emojiList.size)
                        val randomEmoji = emojiList[randomIndex]
                        val collectType = when (collectEnergyEntity.fromTag) {
                            "takeLook" -> "找能量收取"
                            "蹲点收取" -> "蹲点收取"
                            else -> "普通收取"
                        }
                        val str =
                            collectType + randomEmoji + collected + "g[" + getAndCacheUserName(
                                userId
                            ) + "]" + if (bombSuffix.isNotEmpty()) "#$bombSuffix" else ""
                        selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                            Statistics.addData(uid, Statistics.DataType.COLLECTED, collected)
                            totalCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
                        } ?: run {
                            totalCollected += collected
                        }
                        if (needDouble) {
                            Log.forest(str + "耗时[" + spendTime + "]ms[双击]")
                            Toast.show("$str[双击]")
                        } else {
                            Log.forest(str + "耗时[" + spendTime + "]ms")
                            Toast.show(str)
                        }
                    }
                    if (bubble.getBoolean("canBeRobbedAgain")) {
                        collectEnergyEntity.setNeedDouble()
                        collectEnergyEntity.resetTryCount()
                        collectEnergy(collectEnergyEntity)
                        return@Runnable
                    }

                    val userHome = collectEnergyEntity.userHome
                    if (userHome != null) {
                        val bizNo = userHome.optString("bizNo")
                        if (bizNo.isNotEmpty()) {
                            val returnCount = getReturnCount(collected)
                            if (returnCount > 0) {
                                // ✅ 调用 returnFriendWater 增加通知好友开关
                                val shouldNotifyFriend = notifyFriend?.value == true
                                returnFriendWater(userId, bizNo, 1, returnCount, shouldNotifyFriend, selfId)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "collectEnergy err", e)
            } finally {
                val strTotalCollected =
                    "今日总 收:" + totalCollected + "g 帮:" + totalHelpCollected + "g 浇:" + totalWatered + "g"
                updateRunningLastExec(strTotalCollected)
                notifyMain()
            }
        }
        taskCount.incrementAndGet()
        runnable.run()
    }

    private fun getReturnCount(collected: Int): Int {
        var returnCount = 0
        val return33 = returnWater33?.value ?: 0
        val return18 = returnWater18?.value ?: 0
        val return10 = returnWater10?.value ?: 0
        if (return33 in 1..collected) {
            returnCount = 33
        } else if (return18 in 1..collected) {
            returnCount = 18
        } else if (return10 in 1..collected) {
            returnCount = 10
        }
        return returnCount
    }

    /**
     * 更新使用中的的道具剩余时间
     *
     * @param joHomePage 首页 JSON 对象，如果为 null 则发起网络请求获取
     * @return 最新的主页对象
     */
    internal fun updateSelfHomePage(
        joHomePage: JSONObject? = null,
        collectRobMultiplierEnergy: Boolean = false,
        robMultiplierEnergySource: String? = null,
        silent: Boolean = false,
        homePageSource: String? = null
    ): JSONObject? {
        val homeObj = joHomePage ?: run {
            val s = AntForestRpcCall.queryHomePage(homePageSource)
            GlobalThreadPools.sleepCompat(100)
            if (s.isBlank()) return null
            JSONObject(s)
        }
        try {
            val teamUsingUserProps = if (isTeam(homeObj)) {
                homeObj.optJSONObject("teamHomeResult")
                    ?.optJSONObject("mainMember")
                    ?.optJSONArray("usingUserProps")
            } else {
                null
            }
            val usingUserProps = JSONArray()
            val seenPropKeys = mutableSetOf<String>()
            fun appendUsingProps(props: JSONArray?) {
                if (props == null) return
                for (index in 0 until props.length()) {
                    val prop = props.optJSONObject(index) ?: continue
                    val key = prop.optString("propId")
                        .ifBlank { "${prop.optString("propGroup")}:${prop.optString("propType")}" }
                    if (seenPropKeys.add(key)) {
                        usingUserProps.put(prop)
                    }
                }
            }
            appendUsingProps(teamUsingUserProps)
            appendUsingProps(homeObj.optJSONArray("usingUserPropsNew"))
            appendUsingProps(homeObj.optJSONArray("loginUserUsingPropNew"))
            for (i in 0 until usingUserProps.length()) {
                val userUsingProp = usingUserProps.getJSONObject(i)
                val propGroup = userUsingProp.getString("propGroup")
                val propName = userUsingProp.optString("propName")
                when (propGroup) {
                    "doubleClick" -> {
                        doubleEndTime = userUsingProp.getLong("endTime")
                        if (!silent) Log.forest("$propName 剩余时间⏰：" + formatTimeDifference(doubleEndTime - System.currentTimeMillis()))
                    }

                    "stealthCard" -> {
                        stealthEndTime = userUsingProp.getLong("endTime")
                        if (!silent) Log.forest("$propName 剩余时间⏰️：" + formatTimeDifference(stealthEndTime - System.currentTimeMillis()))
                    }

                    "shield" -> {
                        shieldEndTime = userUsingProp.getLong("endTime")
                        if (!silent) Log.forest("$propName 剩余时间⏰：" + formatTimeDifference(shieldEndTime - System.currentTimeMillis()))
                    }

                    "energyBombCard" -> {
                        energyBombCardEndTime = userUsingProp.getLong("endTime")
                        if (!silent) Log.forest("$propName 剩余时间⏰：" + formatTimeDifference(energyBombCardEndTime - System.currentTimeMillis()))
                    }

                    "robExpandCard" -> {
                        val extInfo = userUsingProp.optString("extInfo")
                        robMultiplierCardEndTime = userUsingProp.getLong("endTime")
                        robMultiplierCardPropName = propName
                        robMultiplierCardPropType = userUsingProp.optString("propType")
                        robMultiplierCardFactor = parseRobMultiplierFactor(
                            robMultiplierCardPropType,
                            propName,
                            userUsingProp.optJSONObject("detail"),
                            userUsingProp.optString("description")
                        )
                        val factorSuffix = if (robMultiplierCardFactor > 0.0) {
                            "，倍率${formatRobMultiplierFactor(robMultiplierCardFactor)}倍"
                        } else {
                            ""
                        }
                        if (!silent) Log.forest("$propName 剩余时间⏰：" + formatTimeDifference(robMultiplierCardEndTime - System.currentTimeMillis()) + factorSuffix)
                        if (!extInfo.isEmpty()) {
                            val extInfoObj = runCatching { JSONObject(extInfo) }.getOrNull()
                            if (extInfoObj == null) {
                                Log.forest("$propName 附加信息解析失败，跳过N倍卡能量领取")
                                continue
                            }
                            val leftEnergy = extInfoObj.optString("leftEnergy", "0").toDoubleOrNull()
                                ?: extInfoObj.optDouble("leftEnergy", 0.0)
                            val collectableEnergy = extInfoObj.optString("collectableEnergy")
                                .toIntOrNull()
                                ?: leftEnergy.toInt()
                            val overLimitToday = extInfoObj.optBoolean("overLimitToday", false) ||
                                extInfoObj.optString("overLimitToday", "false").equals("true", true)
                            val robMultiplierLimit = (robMultiplierCollectLimit?.value ?: 0).coerceAtLeast(1).toDouble()
                            val shouldCollectRobMultiplierEnergy = collectableEnergy.toDouble() >= robMultiplierLimit ||
                                leftEnergy >= robMultiplierLimit
                            if (!collectRobMultiplierEnergy) {
                                continue
                            }
                            if (shouldCollectRobMultiplierEnergy) {
                                val propId = userUsingProp.optString("propId")
                                val propType = userUsingProp.optString("propType")
                                if (propId.isBlank() || propType.isBlank()) {
                                    Log.forest("$propName 缺少 propId/propType，跳过N倍卡能量领取")
                                    continue
                                }
                                val collectRobExpandEnergyResponse =
                                    if (robMultiplierEnergySource.isNullOrBlank()) {
                                        AntForestRpcCall.collectRobExpandEnergy(propId, propType)
                                    } else {
                                        AntForestRpcCall.collectRobExpandEnergy(
                                            propId,
                                            propType,
                                            robMultiplierEnergySource
                                        )
                                    }
                                val jo = JSONObject(collectRobExpandEnergyResponse)
                                if (ResChecker.checkRes(TAG, jo)) {
                                    val collectEnergy = jo.optInt("collectEnergy")
                                    if (collectEnergy > 0) {
                                        selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                                            Statistics.addData(uid, Statistics.DataType.COLLECTED, collectEnergy)
                                            totalCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
                                        } ?: run {
                                            totalCollected += collectEnergy
                                        }
                                    }
                                    val remainEnergy = jo.optString("leftEnergy")
                                    val remainSuffix = if (remainEnergy.isNotEmpty()) "#剩余${remainEnergy}g" else ""
                                    Log.forest("N倍卡能量🌳[" + collectEnergy + "g][$propName]$remainSuffix")
                                } else if (jo.optString("resultCode") == "COLLECT_EXPAND_ENERGY_NOT_ENOUGH") {
                                    Log.forest("$propName 剩余${jo.optString("leftEnergy", leftEnergy.toString())}g，不足1g无法领取")
                                }
                            } else {
                                if (overLimitToday) {
                                    Log.forest("$propName 今日翻倍能量领取已达上限(20000g)")
                                } else if (leftEnergy > 0.0) {
                                    if (leftEnergy >= 1.0) {
                                        Log.forest("$propName 剩余${leftEnergy}g，未达到领取阈值(${robMultiplierLimit.toInt()}g)，跳过领取")
                                    } else {
                                        Log.forest("$propName 剩余${leftEnergy}g，不足1g无法领取")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (th: Throwable) {
            Log.printStackTrace(TAG, "updateDoubleTime err", th)
        }
        return homeObj
    }

    /**
     * 为好友浇水并返回浇水次数和是否可以继续浇水的状态。
     *
     * @param userId       好友的用户ID
     * @param bizNo        业务编号
     * @param count        需要浇水的次数
     * @param waterEnergy  每次浇水的能量值
     * @param notifyFriend 是否通知好友
     * @return KVMap 包含浇水次数和是否可以继续浇水的状态
     */
    private fun returnFriendWater(
        userId: String?,
        bizNo: String?,
        count: Int,
        waterEnergy: Int,
        notifyFriend: Boolean,
        taskUid: String?
    ): KVMap<Int?, Boolean?> {
        // bizNo为空直接返回默认
        if (bizNo == null || bizNo.isEmpty()) {
            return KVMap(0, true)
        }
        val safeUserId = userId ?: return KVMap(0, true)

        var wateredTimes = 0 // 已浇水次数
        var successTimes = 0 // SUCCESS 次数（用于统计）
        var isContinue = true // 是否可以继续浇水

        try {
            val energyId = getEnergyId(waterEnergy)

            var waterCount = 1
            label@ while (waterCount <= count) {
                // 调用RPC进行浇水，并传入是否通知好友
                val rpcResponse =
                    AntForestRpcCall.transferEnergy(safeUserId, bizNo, energyId, notifyFriend)

                if (rpcResponse.isEmpty()) {
                    Log.forest("好友浇水返回空: " + UserMap.getMaskName(safeUserId))
                    isContinue = false
                    break
                }

                val jo = JSONObject(rpcResponse)

                // 先处理可能的错误码
                val errorCode = jo.optString("error")
                if ("1009" == errorCode) { // 访问被拒绝
                    Log.forest("好友浇水🚿访问被拒绝: " + UserMap.getMaskName(userId))
                    isContinue = false
                    break
                } else if ("3000" == errorCode) { // 系统错误
                    Log.forest("好友浇水🚿系统错误，稍后重试: " + UserMap.getMaskName(userId))
                    GlobalThreadPools.sleepCompat(500)
                    waterCount-- // 重试当前次数
                    waterCount++
                    continue
                }

                // 处理正常返回
                val resultCode = jo.optString("resultCode")
                when (resultCode) {
                    "SUCCESS" -> {
                        val userBaseInfo = jo.optJSONObject("userBaseInfo")
                        val currentEnergy = userBaseInfo?.optInt(
                            "currentEnergy",
                            0
                        ) ?: "未知"
                        val totalEnergy = userBaseInfo?.optInt(
                            "totalEnergy",
                            0
                        ) ?: "未知"
                        Log.forest("好友浇水🚿[${UserMap.getMaskName(userId)}]#$waterEnergy g，当前能量状态 [$currentEnergy/$totalEnergy g]")
                        wateredTimes++
                        successTimes++
                    }

                    "WATERING_TIMES_LIMIT" -> {
                        Log.forest("好友浇水🚿今日已达上限: " + UserMap.getMaskName(userId))
                        wateredTimes = 3 // 上限假设3次
                        break@label
                    }

                    // 该用户今日已被很多人浇水：直接标记为“已浇水”，避免重复尝试卡住流程
                    "WATERING_USER_LIMIT" -> {
                        Log.forest("好友浇水🚿" + jo.optString("resultDesc"))
                        wateredTimes = count // 本次配置的浇水次数(通常≤3)，用于跳过后续重复尝试
                        break@label
                    }

                    "ENERGY_INSUFFICIENT" -> {
                        Log.forest("好友浇水🚿" + jo.optString("resultDesc"))
                        isContinue = false
                        break@label
                    }

                    else -> {
                        Log.forest("好友浇水🚿" + jo.optString("resultDesc"))
                        Log.forest(jo.toString())
                    }
                }
                waterCount++
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "returnFriendWater err", t)
        }

        if (successTimes > 0 && !userId.isNullOrBlank()) {
            Status.wateringFriendToday(userId, successTimes, taskUid)
        }

        if (successTimes > 0 && waterEnergy > 0) {
            val wateredEnergy = successTimes * waterEnergy
            selfId?.takeIf { it.isNotBlank() }?.let { uid ->
                Statistics.addData(uid, Statistics.DataType.WATERED, wateredEnergy)
                totalWatered = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.WATERED)
            } ?: run {
                totalWatered += wateredEnergy
            }
        }

        return KVMap(wateredTimes, isContinue)
    }

    /**
     * 获取能量ID
     */
    private fun getEnergyId(waterEnergy: Int): Int {
        if (waterEnergy <= 0) return 0
        if (waterEnergy >= 66) return 42
        if (waterEnergy >= 33) return 41
        if (waterEnergy >= 18) return 40
        return 39
    }

    private fun isForestSignAlreadyHandled(response: JSONObject): Boolean {
        val code = response.optString("code")
        val desc = response.optString("desc")
        return code == "1400000001" || desc.contains("重复签到")
    }

    private fun isAntiepSuccess(response: JSONObject): Boolean {
        return response.optBoolean("success") ||
            response.optString("code") == "100000000" ||
            response.optString("resultCode").equals("SUCCESS", true)
    }

    private fun unwrapGift7thResponse(response: JSONObject): JSONObject {
        return response.optJSONObject("resData") ?: response
    }

    private fun extractGift7thAwardName(signResponse: JSONObject, signRecord: JSONObject?): String {
        val signAwardName = signResponse.optJSONObject("signModel")
            ?.optJSONObject("signAward")
            ?.optJSONObject("bizInfo")
            ?.optString("awardName")
            .orEmpty()
        if (signAwardName.isNotBlank()) {
            return signAwardName
        }
        return signRecord?.optJSONObject("extInfo")
            ?.optString("awardName")
            .orEmpty()
    }

    private fun hasGift7thActivity(homePage: JSONObject?): Boolean {
        val ext = homePage?.optJSONObject("properties")
            ?.optString("ext")
            .orEmpty()
        if (ext.isBlank()) {
            return false
        }
        val extObject = runCatching { JSONObject(ext) }.getOrNull() ?: return false
        return extObject.optString("sigh7thBizType").isNotBlank()
    }

    internal fun handleGift7thSign(
        homePage: JSONObject?,
        source: String = GIFT7TH_SIGN_SOURCE
    ) {
        try {
            if (!hasGift7thActivity(homePage)) {
                return
            }

            val currentUid = UserMap.currentUid
            if (currentUid.isNullOrBlank()) {
                Log.forest("森林七日礼包缺少用户ID，跳过自动领取")
                return
            }

            val entranceResponse = unwrapGift7thResponse(
                JSONObject(
                    AntForestRpcCall.signEntranceAccess(
                        GIFT7TH_SIGN_SCENE_CODE,
                        source,
                        source
                    )
                )
            )
            if (!isAntiepSuccess(entranceResponse)) {
                Log.forest("森林七日礼包入口查询失败：${entranceResponse.optString("desc")}")
                return
            }
            if (!entranceResponse.optBoolean("showEntrance", false)) {
                return
            }

            val commonSignResponse = unwrapGift7thResponse(
                JSONObject(
                    AntForestRpcCall.queryCommonSign(
                        GIFT7TH_SIGN_SCENE_CODE,
                        source,
                        true
                    )
                )
            )
            if (!ResChecker.checkRes(TAG, "森林七日礼包查询失败:", commonSignResponse)) {
                return
            }

            val forestSignVO = commonSignResponse.optJSONObject("forestSignVO") ?: return
            val currentSignKey = forestSignVO.optString("currentSignKey")
            val signRecords = forestSignVO.optJSONArray("signRecords") ?: return
            if (currentSignKey.isBlank()) {
                Log.forest("森林七日礼包缺少当前签到标识，跳过自动领取")
                return
            }

            var currentSignRecord: JSONObject? = null
            for (i in 0..<signRecords.length()) {
                val signRecord = signRecords.optJSONObject(i) ?: continue
                if (signRecord.optString("signKey") == currentSignKey) {
                    currentSignRecord = signRecord
                    break
                }
            }

            val todaySignRecord = currentSignRecord ?: run {
                Log.forest("森林七日礼包未找到今日签到记录，跳过自动领取")
                return
            }

            if (todaySignRecord.optBoolean("signed", false)) {
                return
            }

            val signResponse = unwrapGift7thResponse(
                JSONObject(
                    AntForestRpcCall.signCommon(
                        forestSignVO.optString("sceneCode", GIFT7TH_SIGN_SCENE_CODE),
                        currentUid
                    )
                )
            )
            GlobalThreadPools.sleepCompat(300)

            if (isForestSignAlreadyHandled(signResponse)) {
                Log.forest("森林七日礼包已完成，跳过重复领取")
                return
            }
            if (!isAntiepSuccess(signResponse)) {
                Log.forest("森林七日礼包领取失败：${signResponse.optString("desc")}")
                return
            }

            val awardName = extractGift7thAwardName(signResponse, todaySignRecord)
            val awardSuffix = if (awardName.isNotBlank()) "[$awardName]" else ""
            val continuousCount = signResponse.optInt("continuousCount", 0)
            val continuousSuffix = if (continuousCount > 0) "#连签${continuousCount}天" else ""
            Log.forest("森林七日礼包🎁$awardSuffix$continuousSuffix")
        } catch (t: Throwable) {
            handleException("handleGift7thSign", t)
        }
    }

    private fun isForestTaskAlreadyHandled(response: JSONObject): Boolean {
        val code = response.optString("code")
        val desc = response.optString("desc")
        return code == "400000030" ||
            code == "B000000008" ||
            desc.contains("任务已完结") ||
            desc.contains("无状态转换处理")
    }

    private fun handleForestTaskRpcFailure(
        action: String,
        sceneCode: String,
        taskType: String,
        taskTitle: String,
        response: JSONObject,
        tryKey: String? = null,
        terminalResult: Boolean = true
    ): Boolean {
        val code = extractForestTaskFailureCode(response)
        val message = extractForestTaskFailureMessage(response)
        val rpc = when {
            action.contains("opengreen", ignoreCase = true) -> "AntForestRpcCall.receiveTaskAwardopengreen"
            action.contains("receive", ignoreCase = true) -> "AntForestRpcCall.receiveTaskAward"
            else -> "AntForestRpcCall.finishTask"
        }
        val detail = "module=$forestTaskBlacklistModule taskId=$taskType taskType=$taskType taskName=$taskTitle " +
            "sceneCode=$sceneCode action=$action rpc=$rpc code=${code.ifBlank { "UNKNOWN" }} msg=$message raw=$response"
        return when (classifyForestTaskFailure(response)) {
            TaskRpcFailureType.TERMINAL_DONE -> {
                tryKey?.let(forestTaskTryCount::remove)
                Log.forest("森林任务[$taskTitle] classification=TERMINAL_DONE decision=MARK_HANDLED $detail")
                terminalResult
            }

            TaskRpcFailureType.BUSINESS_LIMIT -> {
                Log.forest("森林任务[$taskTitle] classification=BUSINESS_LIMIT decision=STOP_TODAY_OR_CURRENT_CHAIN $detail")
                false
            }

            TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE -> {
                blacklistClassifiedForestTask(taskType, taskTitle, code)
                tryKey?.let(forestTaskTryCount::remove)
                Log.error(TAG, "森林任务[$taskTitle] classification=UNSUPPORTED_NO_CLOSURE decision=BLACKLIST reason=未抓到稳定完成RPC $detail")
                false
            }

            TaskRpcFailureType.NON_RETRYABLE_INVALID -> {
                blacklistClassifiedForestTask(taskType, taskTitle, code)
                tryKey?.let(forestTaskTryCount::remove)
                Log.error(TAG, "森林任务[$taskTitle] classification=NON_RETRYABLE_INVALID decision=BLACKLIST $detail")
                false
            }

            TaskRpcFailureType.RETRYABLE_RPC -> {
                Log.error(TAG, "森林任务[$taskTitle] classification=RETRYABLE_RPC decision=RETRY_LATER $detail")
                false
            }

            TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW -> {
                Log.error(TAG, "森林任务[$taskTitle] classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY $detail")
                false
            }
        }
    }

    private fun blacklistClassifiedForestTask(taskType: String, taskTitle: String, code: String) {
        if (code.isNotBlank()) {
            TaskBlacklist.autoAddToBlacklist(forestTaskBlacklistModule, taskType, taskTitle, code)
        }
        TaskBlacklist.addToBlacklist(forestTaskBlacklistModule, taskType, taskTitle)
    }

    private fun classifyForestTaskFailure(response: JSONObject): TaskRpcFailureType {
        val code = extractForestTaskFailureCode(response)
        val message = extractForestTaskFailureMessage(response)
        return when {
            isForestTaskAlreadyHandled(response) ||
                containsAnyForest(message, "已领取", "已经领取", "重复领取", "重复领奖", "重复完成", "已完成", "任务已完结", "任务已结束") ->
                TaskRpcFailureType.TERMINAL_DONE

            code == "CAMP_TRIGGER_ERROR" ||
                code.contains("LIMIT", ignoreCase = true) ||
                containsAnyForest(message, "上限", "限制", "受限", "不可领取", "资格不足", "兑完", "风控", "风险") ->
                TaskRpcFailureType.BUSINESS_LIMIT

            code == "400000040" ||
                containsAnyForest(message, "不支持rpc调用", "不支持RPC完成") ->
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                containsAnyForest(message, "参数错误", "任务ID非法", "模板不存在") ->
                TaskRpcFailureType.NON_RETRYABLE_INVALID

            code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK") ||
                containsAnyForest(message, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试") ||
                isForestFailureMarkedRetryable(response) ->
                TaskRpcFailureType.RETRYABLE_RPC

            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun extractForestTaskFailureCode(response: JSONObject): String {
        return response.optString("code")
            .ifBlank { response.optString("resultCode") }
            .ifBlank { response.optString("errorCode") }
    }

    private fun extractForestTaskFailureMessage(response: JSONObject): String {
        return response.optString("desc")
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("resultView") }
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("errorMessage") }
            .ifBlank { response.optString("memo") }
            .ifBlank { response.toString() }
    }

    private fun isForestFailureMarkedRetryable(response: JSONObject): Boolean {
        return listOf("retryable", "retriable", "canRetry").any { key ->
            response.has(key) && response.optBoolean(key, false)
        }
    }

    private fun containsAnyForest(text: String, vararg keywords: String): Boolean {
        return keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private data class DeferredForestRightsTask(
        val sceneCode: String,
        val taskType: String,
        val touchId: String,
        val awardCount: Int,
        val taskTitle: String,
        val fallbackTaskBaseInfo: JSONObject
    )

    private data class ForestTaskCandidate(
        val item: TaskFlowItem,
        val sourceName: String
    )

    private fun isDeferredForestRightsTaskType(taskType: String): Boolean {
        return taskType.startsWith("acc_task_energy_")
    }

    private fun isEnergyRainTaskCenterTaskType(taskType: String): Boolean {
        return taskType == "ENERGYRAIN"
    }

    private fun canReceiveDeferredForestRightsAward(taskStatus: String): Boolean {
        return taskStatus == TaskStatus.FINISHED.name ||
            taskStatus == "COMPLETE" ||
            taskStatus == "WAIT_RECEIVE" ||
            taskStatus == "TO_RECEIVE" ||
            taskStatus == TaskStatus.RECEIVED.name
    }

    private fun parseTaskBizInfo(taskBaseInfo: JSONObject): JSONObject {
        val rawBizInfo = taskBaseInfo.optString("bizInfo")
        if (rawBizInfo.isBlank()) {
            return JSONObject()
        }
        return runCatching { JSONObject(rawBizInfo) }.getOrElse { JSONObject() }
    }

    private fun parseTaskRights(taskInfo: JSONObject): JSONObject {
        return when (val rawTaskRights = taskInfo.opt("taskRights")) {
            is JSONObject -> rawTaskRights
            is String -> runCatching { JSONObject(rawTaskRights) }.getOrElse { JSONObject() }
            else -> JSONObject()
        }
    }

    private fun getForestTaskTitle(taskBaseInfo: JSONObject, taskType: String): String {
        val bizInfo = parseTaskBizInfo(taskBaseInfo)
        return sequenceOf(
            bizInfo.optString("taskTitle"),
            bizInfo.optString("title"),
            bizInfo.optString("taskDesc"),
            bizInfo.optString("taskContent"),
            taskType
        ).firstOrNull { it.isNotBlank() } ?: taskType
    }

    private fun isGreenPracticeParentTask(taskBaseInfo: JSONObject, taskType: String): Boolean {
        return taskType == "ENERGY_XUANJIAO" &&
            taskBaseInfo.optString("taskNode").equals("PARENT", true)
    }

    private fun isGreenPracticeChildTask(taskBaseInfo: JSONObject, taskType: String): Boolean {
        return taskType.startsWith("ENERGY_XUANJIAO_") &&
            taskBaseInfo.optString("taskNode").equals("CHILD", true)
    }

    private fun isGreenPracticeChildBlacklisted(taskType: String, taskTitle: String): Boolean {
        return TaskBlacklist.isTaskInBlacklist(forestTaskBlacklistModule, taskType) ||
            TaskBlacklist.isTaskInBlacklist(forestTaskBlacklistModule, taskTitle)
    }

    private fun hasActionableGreenPracticeChild(taskInfo: JSONObject): Boolean {
        val taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo") ?: return false
        val parentSceneCode = taskBaseInfo.optString("sceneCode")
        val childTaskTypeList = taskInfo.optJSONArray("childTaskTypeList") ?: return false
        for (i in 0 until childTaskTypeList.length()) {
            val childTask = childTaskTypeList.optJSONObject(i) ?: continue
            val childBaseInfo = childTask.optJSONObject("taskBaseInfo") ?: continue
            val childTaskType = childBaseInfo.optString("taskType")
            val childSceneCode = childBaseInfo.optString("sceneCode").ifBlank { parentSceneCode }
            val childStatus = childBaseInfo.optString("taskStatus")
            if (childTaskType.isBlank() ||
                childSceneCode.isBlank() ||
                childStatus != TaskStatus.TODO.name ||
                !isGreenPracticeChildTask(childBaseInfo, childTaskType)
            ) {
                continue
            }

            val childTaskTitle = getForestTaskTitle(childBaseInfo, childTaskType)
            if (!isGreenPracticeChildBlacklisted(childTaskType, childTaskTitle)) {
                return true
            }
        }
        return false
    }

    private fun handleGreenPracticeTask(taskInfo: JSONObject): Boolean {
        val taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo") ?: return false
        val taskType = taskBaseInfo.optString("taskType")
        val sceneCode = taskBaseInfo.optString("sceneCode")
        val taskStatus = taskBaseInfo.optString("taskStatus")
        if (taskType != "ENERGY_XUANJIAO" || sceneCode.isBlank()) {
            return false
        }

        if (taskStatus == TaskStatus.FINISHED.name || taskStatus == "COMPLETE") {
            return handleForestTaskNodeRewardOnly(taskInfo, mutableSetOf())
        }
        if (taskStatus != TaskStatus.TODO.name) {
            return false
        }

        val taskProgress = taskBaseInfo.optInt("taskProgress", 0)
        val taskRequire = taskBaseInfo.optInt("taskRequire", 5).takeIf { it > 0 } ?: 5
        val remainingCount = if (taskRequire > taskProgress) taskRequire - taskProgress else 0
        if (remainingCount <= 0) {
            return true
        }

        val childTaskTypeList = taskInfo.optJSONArray("childTaskTypeList")
        if (childTaskTypeList == null || childTaskTypeList.length() == 0) {
            Log.forest("绿色践行任务缺少子任务列表，等待下次刷新")
            return false
        }

        var changed = false
        var finishedCount = 0
        for (i in 0 until childTaskTypeList.length()) {
            if (finishedCount >= remainingCount || Thread.currentThread().isInterrupted) {
                break
            }
            val childTask = childTaskTypeList.optJSONObject(i) ?: continue
            val childBaseInfo = childTask.optJSONObject("taskBaseInfo") ?: continue
            val childTaskType = childBaseInfo.optString("taskType")
            val childSceneCode = childBaseInfo.optString("sceneCode").ifBlank { sceneCode }
            val childStatus = childBaseInfo.optString("taskStatus")
            if (childTaskType.isBlank() ||
                childSceneCode.isBlank() ||
                childStatus != TaskStatus.TODO.name ||
                !isGreenPracticeChildTask(childBaseInfo, childTaskType)
            ) {
                continue
            }

            val childTaskTitle = getForestTaskTitle(childBaseInfo, childTaskType)
            if (isGreenPracticeChildBlacklisted(childTaskType, childTaskTitle)) {
                continue
            }

            val bizKey = "${childSceneCode}_$childTaskType"
            forestTaskTryCount.computeIfAbsent(bizKey) { AtomicInteger(0) }.incrementAndGet()
            val finishTaskResponse = JSONObject(AntForestRpcCall.finishTask(childSceneCode, childTaskType))
            when {
                isForestTaskAlreadyHandled(finishTaskResponse) -> {
                    forestTaskTryCount.remove(bizKey)
                    Log.forest("绿色践行子任务已完结: $childTaskTitle")
                    changed = true
                    finishedCount++
                }

                ResChecker.checkRes(TAG, "完成绿色践行子任务失败:", finishTaskResponse) -> {
                    forestTaskTryCount.remove(bizKey)
                    Log.forest("森林任务🧾️[绿色践行-$childTaskTitle]")
                    changed = true
                    finishedCount++
                }

                else -> {
                    if (handleForestTaskRpcFailure(
                            action = "finishGreenPracticeChildTask",
                            sceneCode = childSceneCode,
                            taskType = childTaskType,
                            taskTitle = childTaskTitle,
                            response = finishTaskResponse,
                            tryKey = bizKey
                        )
                    ) {
                        changed = true
                        finishedCount++
                    }
                }
            }
        }
        return changed
    }

    private fun appendDeferredForestRightsTask(
        taskInfo: JSONObject,
        deferredTasks: MutableMap<String, DeferredForestRightsTask>
    ) {
        val taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo") ?: return
        val taskType = taskBaseInfo.optString("taskType")
        val sceneCode = taskBaseInfo.optString("sceneCode")
        if (sceneCode.isBlank() || taskType.isBlank()) {
            return
        }
        if (!isDeferredForestRightsTaskType(taskType)) {
            return
        }
        val taskRights = parseTaskRights(taskInfo)
        val touchId = taskRights.optString("rightsTouchId").ifBlank { "$sceneCode#$taskType" }
        if (touchId.isBlank()) {
            return
        }
        val bizInfo = parseTaskBizInfo(taskBaseInfo)
        val awardCount = taskRights.optInt("awardCount", 0)
        val awardName = bizInfo.optString("awardName")
        val taskTitle = when {
            awardName.isNotBlank() -> "累计任务奖励$awardName"
            else -> bizInfo.optString("taskTitle").ifBlank { taskType }
        }
        deferredTasks.putIfAbsent(
            touchId,
            DeferredForestRightsTask(sceneCode, taskType, touchId, awardCount, taskTitle, taskBaseInfo)
        )
    }

    private fun appendSignInfo(signInfo: JSONObject?, uniqueSigns: MutableMap<String, JSONObject>) {
        val safeSignInfo = signInfo ?: return
        val signId = safeSignInfo.optString("signId")
        val currentSignKey = safeSignInfo.optString("currentSignKey")
        val uniqueKey = "$signId#$currentSignKey"
        if (signId.isNotBlank() && currentSignKey.isNotBlank() && !uniqueSigns.containsKey(uniqueKey)) {
            uniqueSigns[uniqueKey] = safeSignInfo
        }
    }

    private fun appendSignInfo(signInfoList: JSONArray?, uniqueSigns: MutableMap<String, JSONObject>) {
        if (signInfoList == null) {
            return
        }
        for (i in 0 until signInfoList.length()) {
            appendSignInfo(signInfoList.optJSONObject(i), uniqueSigns)
        }
    }

    private fun collectTaskNodesRecursively(taskInfo: JSONObject, out: MutableList<JSONObject>) {
        val childTaskTypeList = taskInfo.optJSONArray("childTaskTypeList")
        if (childTaskTypeList != null && childTaskTypeList.length() > 0) {
            for (i in 0 until childTaskTypeList.length()) {
                val childTask = childTaskTypeList.optJSONObject(i) ?: continue
                collectTaskNodesRecursively(childTask, out)
            }
        }
        out.add(taskInfo)
    }

    private fun collectForestTaskNodes(taskResponse: JSONObject): MutableList<JSONObject> {
        val taskNodes = mutableListOf<JSONObject>()
        fun appendTaskInfoList(taskInfoList: JSONArray?) {
            if (taskInfoList == null) return
            for (j in 0 until taskInfoList.length()) {
                val taskInfo = taskInfoList.optJSONObject(j) ?: continue
                collectTaskNodesRecursively(taskInfo, taskNodes)
            }
        }
        val forestTasksNew = taskResponse.optJSONArray("forestTasksNew")
        if (forestTasksNew != null) {
            for (i in 0 until forestTasksNew.length()) {
                val forestTask = forestTasksNew.optJSONObject(i) ?: continue
                appendTaskInfoList(forestTask.optJSONArray("taskInfoList"))
            }
        }
        val taskGroupInfoList = taskResponse.optJSONArray("taskGroupInfoList")
        if (taskGroupInfoList != null) {
            for (i in 0 until taskGroupInfoList.length()) {
                val taskGroup = taskGroupInfoList.optJSONObject(i) ?: continue
                appendTaskInfoList(taskGroup.optJSONArray("taskInfoList"))
            }
        }
        appendTaskInfoList(taskResponse.optJSONArray("taskInfoList"))
        return taskNodes
    }

    private fun requestDeferredForestRights(
        sceneCode: String,
        touchIds: Collection<String>,
        source: String? = null
    ): JSONObject? {
        if (touchIds.isEmpty()) {
            return null
        }
        val response = if (source.isNullOrBlank()) {
            AntForestRpcCall.batchQueryAndTouchOpenGreen(sceneCode, touchIds)
        } else {
            AntForestRpcCall.batchQueryAndTouchOpenGreen(sceneCode, touchIds, source)
        }
        if (response.isBlank()) {
            return null
        }
        val responseObj = JSONObject(response)
        return responseObj.optJSONObject("resData") ?: responseObj
    }

    private fun hasTouchedDeferredForestRights(
        response: JSONObject,
        touchIds: Collection<String>
    ): Boolean {
        val touchMap = response.optJSONObject("batchQueryAndTouchVOMap") ?: return false
        return touchIds.any { touchId ->
            val touchArray = touchMap.optJSONArray(touchId) ?: return@any false
            (0 until touchArray.length()).any { index ->
                touchArray.optJSONObject(index)?.optString("touchStatus").equals("TOUCHED", true)
            }
        }
    }

    private fun collectDeferredForestRights(
        tasks: Collection<DeferredForestRightsTask>,
        preferredSource: String? = null
    ) {
        if (tasks.isEmpty() || Thread.currentThread().isInterrupted) {
            return
        }
        val tasksByScene = tasks.groupBy { it.sceneCode }
        for ((sceneCode, sceneTasks) in tasksByScene) {
            if (Thread.currentThread().isInterrupted) {
                return
            }
            val touchIds = sceneTasks.map { it.touchId }
            val sourceCandidates = linkedSetOf<String?>().apply {
                if (!preferredSource.isNullOrBlank()) {
                    add(preferredSource)
                }
                add(AntForestRpcCall.OPEN_GREEN_RIGHTS_SOURCE)
                add(AntForestRpcCall.BACK_FROM_ENERGY_RAIN_SOURCE)
                add(null)
            }
            var response: JSONObject? = null
            var responseSource: String? = null
            for (candidate in sourceCandidates) {
                val candidateResponse = requestDeferredForestRights(sceneCode, touchIds, candidate)
                if (candidateResponse == null || !ResChecker.checkRes(TAG, "领取森林累计奖励失败:", candidateResponse)) {
                    if (response == null) {
                        response = candidateResponse
                    }
                    continue
                }
                response = candidateResponse
                responseSource = candidate
                if (hasTouchedDeferredForestRights(candidateResponse, touchIds) || candidate == null) {
                    break
                }
            }
            if (response == null || !ResChecker.checkRes(TAG, "领取森林累计奖励失败:", response)) {
                continue
            }
            val touchMap = response.optJSONObject("batchQueryAndTouchVOMap") ?: continue
            for (task in sceneTasks) {
                val touchArray = touchMap.optJSONArray(task.touchId) ?: continue
                var touched = false
                var provideRightsNum = 0
                for (index in 0 until touchArray.length()) {
                    val touchObj = touchArray.optJSONObject(index) ?: continue
                    if (touchObj.optString("touchStatus").equals("TOUCHED", true)) {
                        touched = true
                    }
                    val rightsTouchVOList = touchObj.optJSONArray("rightsTouchVOList") ?: continue
                    for (rightsIndex in 0 until rightsTouchVOList.length()) {
                        provideRightsNum += rightsTouchVOList.optJSONObject(rightsIndex)?.optInt("provideRightsNum", 0) ?: 0
                    }
                }
                if (touched) {
                    val displayAwardCount = if (provideRightsNum > 0) provideRightsNum else task.awardCount
                    Log.forest("森林累计奖励🏆[${task.taskTitle}]# $displayAwardCount")
                    receiveDeferredForestRightsAward(task, responseSource)
                }
            }
        }
    }

    private fun receiveDeferredForestRightsAward(
        task: DeferredForestRightsTask,
        preferredSource: String? = null
    ) {
        val fallbackTaskBaseInfo = task.fallbackTaskBaseInfo
        val taskBaseInfo = if (canReceiveDeferredForestRightsAward(fallbackTaskBaseInfo.optString("taskStatus"))) {
            fallbackTaskBaseInfo
        } else {
            findDeferredForestRightsTaskBaseInfo(task, preferredSource) ?: fallbackTaskBaseInfo
        }
        val taskStatus = taskBaseInfo.optString("taskStatus")
        if (!canReceiveDeferredForestRightsAward(taskStatus)) {
            Log.forest("森林累计奖励[${task.taskTitle}] 已触达，刷新后状态[$taskStatus]暂不可领奖")
            return
        }

        val rawTask = JSONObject(taskBaseInfo.toString()).apply {
            if (!has("sceneCode") || optString("sceneCode").isBlank()) put("sceneCode", task.sceneCode)
            if (!has("source") || optString("source").isBlank()) {
                put("source", preferredSource ?: AntForestRpcCall.OPEN_GREEN_RIGHTS_SOURCE)
            }
            if (!has("taskType") || optString("taskType").isBlank()) put("taskType", task.taskType)
        }
        val response = AntForestRpcCall.receiveTaskAwardopengreen(rawTask)
        if (response.isBlank()) {
            Log.error(TAG, "森林累计奖励[${task.taskTitle}]领取失败: receiveTaskAwardopengreen返回空")
            return
        }
        val responseObj = JSONObject(response)
        val awardResponse = responseObj.optJSONObject("resData") ?: responseObj
        when {
            isForestTaskAlreadyHandled(awardResponse) -> {
                Log.forest("森林累计奖励[${task.taskTitle}]已领取")
            }

            isAntiepSuccess(awardResponse) -> {
                Log.forest("森林累计奖励🎖️[${task.taskTitle}]领取成功")
            }

            else -> {
                handleForestTaskRpcFailure(
                    action = "receiveTaskAwardopengreen",
                    sceneCode = task.sceneCode,
                    taskType = task.taskType,
                    taskTitle = task.taskTitle,
                    response = awardResponse,
                    terminalResult = false
                )
            }
        }
    }

    private fun findDeferredForestRightsTaskBaseInfo(
        task: DeferredForestRightsTask,
        preferredSource: String? = null
    ): JSONObject? {
        val sources = linkedMapOf<String, () -> String>().apply {
            if (!preferredSource.isNullOrBlank()) {
                put("take_look_end_task_list($preferredSource)") {
                    AntForestRpcCall.queryTakeLookEndTaskList(preferredSource)
                }
            }
            put("take_look_end_task_list") { AntForestRpcCall.queryTakeLookEndTaskList() }
            put("home_task_list") { AntForestRpcCall.queryTaskList() }
        }
        for ((sourceName, request) in sources) {
            val payload = queryForestTaskSource(sourceName, request) ?: continue
            val taskNode = collectForestTaskNodes(payload).firstOrNull { taskInfo ->
                val baseInfo = taskInfo.optJSONObject("taskBaseInfo") ?: return@firstOrNull false
                baseInfo.optString("sceneCode") == task.sceneCode &&
                    baseInfo.optString("taskType") == task.taskType
            } ?: continue
            return taskNode.optJSONObject("taskBaseInfo")
        }
        return null
    }

    private fun queryForestTaskSource(sourceName: String, query: () -> String): JSONObject? {
        return try {
            val response = query()
            if (response.isBlank()) {
                return null
            }
            val responseObj = JSONObject(response)
            // 兼容不同 RPC Bridge 的返回结构：有的直接返回业务字段，有的包一层 resData
            val payload = responseObj.optJSONObject("resData") ?: responseObj
            if (!ResChecker.checkRes(TAG, "查询森林任务[$sourceName]失败:", payload)) {
                Log.forest("森林任务[$sourceName]返回异常: " + payload.optString(
                        "resultDesc",
                        payload.optString("desc", "未知错误")
                    )
                )
                return null
            }
            payload
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "查询森林任务[$sourceName]异常", t)
            null
        }
    }

    private fun handleForestTaskNodeRewardOnly(
        taskInfo: JSONObject,
        seenTaskKeys: MutableSet<String>
    ): Boolean {
        val taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo") ?: return false
        val taskType = taskBaseInfo.optString("taskType")
        val sceneCode = taskBaseInfo.optString("sceneCode")
        val taskStatus = taskBaseInfo.optString("taskStatus")
        if (taskType.isBlank() || sceneCode.isBlank()) {
            return false
        }

        val uniqueTaskKey = "$sceneCode#$taskType"
        if (!seenTaskKeys.add(uniqueTaskKey)) {
            return false
        }

        if (taskStatus != TaskStatus.FINISHED.name && taskStatus != "COMPLETE") {
            return false
        }

        val bizInfo = parseTaskBizInfo(taskBaseInfo)
        val taskRights = parseTaskRights(taskInfo)
        val awardCount = taskRights.optInt("awardCount", 0)
        val taskTitle = sequenceOf(
            bizInfo.optString("taskTitle"),
            bizInfo.optString("title"),
            bizInfo.optString("taskDesc"),
            bizInfo.optString("taskContent"),
            taskType
        ).firstOrNull { it.isNotBlank() } ?: taskType

        val awardResponse = JSONObject(AntForestRpcCall.receiveTaskAward(sceneCode, taskType))
        return when {
            isForestTaskAlreadyHandled(awardResponse) -> {
                Log.forest("奖励已领取: $taskTitle")
                false
            }

            ResChecker.checkRes(TAG, "领取森林任务奖励失败:", awardResponse) -> {
                val incAwardCount = awardResponse.optInt("incAwardCount", awardCount)
                val displayAwardCount = if (incAwardCount > 0) incAwardCount else awardCount
                Log.forest("森林奖励🎖️[$taskTitle]# $displayAwardCount 活力值")
                GlobalThreadPools.sleepCompat(500)
                true
            }

            else -> {
                handleForestTaskRpcFailure(
                    action = "receiveTaskAward",
                    sceneCode = sceneCode,
                    taskType = taskType,
                    taskTitle = taskTitle,
                    response = awardResponse,
                    terminalResult = false
                )
            }
        }
    }

    private fun completeForestSignTasks(signArray: JSONArray): TaskFlowActionResult {
        var pendingCount = 0
        var handledCount = 0
        var lastFailure: JSONObject? = null
        for (signIndex in 0 until signArray.length()) {
            val forestSignVO = signArray.optJSONObject(signIndex) ?: continue
            val currentSignKey = forestSignVO.optString("currentSignKey")
            val signId = forestSignVO.optString("signId")
            val sceneCode = forestSignVO.optString("sceneCode")
            val signRecords = forestSignVO.optJSONArray("signRecords") ?: continue
            if (currentSignKey.isBlank() || signId.isBlank() || sceneCode.isBlank()) {
                continue
            }
            for (recordIndex in 0 until signRecords.length()) {
                val signRecord = signRecords.optJSONObject(recordIndex) ?: continue
                if (signRecord.optString("signKey") != currentSignKey || signRecord.optBoolean("signed", true)) {
                    continue
                }
                pendingCount++
                val currentUid = UserMap.currentUid
                if (currentUid.isNullOrBlank()) {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        code = "MISSING_USER_ID",
                        message = "森林签到缺少用户ID",
                        rpc = "AntForestRpcCall.antiepSign",
                        detail = "action=forestSign signId=$signId sceneCode=$sceneCode"
                    )
                }
                val signRawResponse = AntForestRpcCall.antiepSign(signId, currentUid, sceneCode)
                if (signRawResponse.isBlank()) {
                    lastFailure = JSONObject()
                        .put("code", "EMPTY_RESPONSE")
                        .put("desc", "森林签到返回空")
                    break
                }
                val signResponse = JSONObject(signRawResponse)
                GlobalThreadPools.sleepCompat(300)
                when {
                    isForestSignAlreadyHandled(signResponse) -> {
                        Log.forest("森林签到已完成，跳过重复签到")
                        handledCount++
                    }

                    ResChecker.checkRes(TAG, "森林签到失败:", signResponse) -> {
                        val awardCount = signRecord.optInt("awardCount", 0)
                        val suffix = if (awardCount > 0) "# $awardCount" else ""
                        Log.forest("森林签到📆成功$suffix")
                        handledCount++
                    }

                    else -> {
                        lastFailure = signResponse
                    }
                }
                break
            }
        }

        if (handledCount > 0) {
            return TaskFlowActionResult.success()
        }
        if (pendingCount == 0) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.TERMINAL_DONE,
                code = "NO_PENDING_SIGN",
                message = "森林签到无待处理记录",
                rpc = "AntForestRpcCall.antiepSign",
                detail = "action=forestSign signCount=${signArray.length()}"
            )
        }

        val response = lastFailure ?: JSONObject()
            .put("code", "UNKNOWN_SIGN_FAILURE")
            .put("desc", "森林签到未完成")
        return forestTaskActionFailureResult(
            response = response,
            rpc = "AntForestRpcCall.antiepSign",
            detail = "action=forestSign pendingCount=$pendingCount"
        )
    }

    private fun receiveForestTaskReward(item: TaskFlowItem): TaskFlowActionResult {
        val awardText = item.raw?.optInt("awardCount", 0) ?: 0
        val response = AntForestRpcCall.receiveTaskAward(item.sceneCode, item.type)
        if (response.isBlank()) {
            return emptyForestTaskActionResponse("AntForestRpcCall.receiveTaskAward", item, "receiveTaskAward")
        }
        val awardResponse = JSONObject(response)
        return when {
            isForestTaskAlreadyHandled(awardResponse) -> TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.TERMINAL_DONE,
                code = extractForestTaskFailureCode(awardResponse),
                message = extractForestTaskFailureMessage(awardResponse),
                rpc = "AntForestRpcCall.receiveTaskAward",
                raw = awardResponse.toString(),
                detail = forestTaskActionDetail(item, "receiveTaskAward")
            )

            ResChecker.checkRes(TAG, "领取森林任务奖励失败:", awardResponse) -> {
                val incAwardCount = awardResponse.optInt("incAwardCount", awardText)
                val displayAwardCount = if (incAwardCount > 0) incAwardCount else awardText
                Log.forest("森林奖励🎖️[${item.title}]# $displayAwardCount 活力值")
                GlobalThreadPools.sleepCompat(500)
                TaskFlowActionResult.success()
            }

            else -> forestTaskActionFailureResult(
                response = awardResponse,
                rpc = "AntForestRpcCall.receiveTaskAward",
                detail = forestTaskActionDetail(item, "receiveTaskAward")
            )
        }
    }

    private fun completeLegacyForestGameTaskResult(item: TaskFlowItem): TaskFlowActionResult {
        val bizInfo = item.raw?.optJSONObject("bizInfo") ?: JSONObject()
        val awardCount = item.raw?.optInt("awardCount", 0) ?: 0
        val gameUrl = bizInfo.optString("taskJumpUrl")
        if (gameUrl.isNotBlank()) {
            Log.forest("跳转到游戏: $gameUrl")
        }
        Log.forest("森林任务🧾️[${item.title}] 直接提交完成RPC")
        val response = AntForestRpcCall.finishTask(item.sceneCode, item.type)
        if (response.isBlank()) {
            return emptyForestTaskActionResponse("AntForestRpcCall.finishTask", item, "finishLegacyGameTask")
        }
        val finishTaskResponse = JSONObject(response)
        return if (ResChecker.checkRes(TAG, "完成游戏任务失败:", finishTaskResponse)) {
            Log.forest("游戏任务完成 🎮️[${item.title}]# $awardCount 活力值")
            TaskFlowActionResult.success()
        } else {
            forestTaskActionFailureResult(
                response = finishTaskResponse,
                rpc = "AntForestRpcCall.finishTask",
                detail = forestTaskActionDetail(item, "finishLegacyGameTask")
            )
        }
    }

    private fun extractForestTaskAppId(url: String): String? {
        return APP_ID_QUERY_REGEX.find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun completeOrdinaryForestTask(item: TaskFlowItem): TaskFlowActionResult {
        val bizKey = buildForestTaskKey(item.sceneCode, item.type)
        forestTaskTryCount.computeIfAbsent(bizKey) { AtomicInteger(0) }.incrementAndGet()
        val response = AntForestRpcCall.finishTask(item.sceneCode, item.type)
        if (response.isBlank()) {
            return emptyForestTaskActionResponse("AntForestRpcCall.finishTask", item, "finishTask")
        }
        val finishTaskResponse = JSONObject(response)
        return when {
            isForestTaskAlreadyHandled(finishTaskResponse) -> TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.TERMINAL_DONE,
                code = extractForestTaskFailureCode(finishTaskResponse),
                message = extractForestTaskFailureMessage(finishTaskResponse),
                rpc = "AntForestRpcCall.finishTask",
                raw = finishTaskResponse.toString(),
                detail = forestTaskActionDetail(item, "finishTask")
            )

            ResChecker.checkRes(TAG, "完成森林任务失败:", finishTaskResponse) -> {
                forestTaskTryCount.remove(bizKey)
                Log.forest("森林任务🧾️[${item.title}]")
                TaskFlowActionResult.success()
            }

            else -> forestTaskActionFailureResult(
                response = finishTaskResponse,
                rpc = "AntForestRpcCall.finishTask",
                detail = forestTaskActionDetail(item, "finishTask")
            )
        }
    }

    private data class OneClickWateringTargetsResult(
        val targetUserIds: List<String> = emptyList(),
        val failure: TaskFlowActionResult? = null
    )

    private fun queryOneClickWateringTargets(item: TaskFlowItem): OneClickWateringTargetsResult {
        val response = AntForestRpcCall.queryRecommendFriendListByScene()
        if (response.isBlank()) {
            return OneClickWateringTargetsResult(
                failure = TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    message = "推荐好友查询返回空",
                    rpc = "AntForestRpcCall.queryRecommendFriendListByScene",
                    detail = forestTaskActionDetail(item, "queryRecommendFriendListByScene"),
                    stopCurrentRound = true
                )
            )
        }
        val payload = unwrapResData(JSONObject(response))
        if (!ResChecker.checkRes(TAG, "查询随机浇水好友失败:", payload)) {
            return OneClickWateringTargetsResult(
                failure = forestTaskActionFailureResult(
                    response = payload,
                    rpc = "AntForestRpcCall.queryRecommendFriendListByScene",
                    detail = forestTaskActionDetail(item, "queryRecommendFriendListByScene")
                )
            )
        }
        val targetUserIds = linkedSetOf<String>()
        val friendInfoList = payload.optJSONArray("recommendFriendInfoVOList") ?: JSONArray()
        for (i in 0 until friendInfoList.length()) {
            val friendInfo = friendInfoList.optJSONObject(i) ?: continue
            val userId = friendInfo.optString("userId")
            if (userId.isNotBlank()) {
                targetUserIds.add(userId)
            }
        }
        if (targetUserIds.isEmpty()) {
            return OneClickWateringTargetsResult(
                failure = TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                    code = "NO_RECOMMEND_FRIEND",
                    message = "随机浇水任务无可用好友",
                    rpc = "AntForestRpcCall.queryRecommendFriendListByScene",
                    raw = payload.toString(),
                    detail = forestTaskActionDetail(item, "queryRecommendFriendListByScene")
                )
            )
        }
        return OneClickWateringTargetsResult(targetUserIds = targetUserIds.toList())
    }

    private fun completeOneClickWateringTask(item: TaskFlowItem): TaskFlowActionResult {
        val targetResult = queryOneClickWateringTargets(item)
        targetResult.failure?.let { return it }
        var wateredCount = 0
        var totalCountByDay: Int? = null
        var firstFailure: TaskFlowActionResult? = null
        val partialFailureMessages = mutableListOf<String>()
        targetResult.targetUserIds.forEachIndexed { index, targetUserId ->
            val maskedName = UserMap.getMaskName(targetUserId)
            val response = AntForestRpcCall.transferEnergyForOneClickWatering(
                targetUser = targetUserId,
                notifyFriend = true,
                orderIndex = index
            )
            if (response.isBlank()) {
                if (firstFailure == null) {
                    firstFailure = TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        message = "随机浇水返回空",
                        rpc = "AntForestRpcCall.transferEnergyForOneClickWatering",
                        detail = forestTaskActionDetail(item, "transferEnergyForOneClickWatering"),
                        stopCurrentRound = true
                    )
                }
                partialFailureMessages.add("$maskedName: 返回空")
                Log.error(TAG, "森林任务🚿[${item.title}]推荐好友浇水失败[$maskedName] 返回空")
                return@forEachIndexed
            }
            val payload = unwrapResData(JSONObject(response))
            if (ResChecker.checkRes(TAG, "随机浇水失败:", payload)) {
                wateredCount++
                if (payload.has("totalCountByDay")) {
                    totalCountByDay = payload.optInt("totalCountByDay")
                }
                val daySuffix = totalCountByDay?.let { " 今日总浇水${it}次" }.orEmpty()
                Log.forest("森林任务🚿[${item.title}]推荐好友浇水成功[$maskedName]$daySuffix")
            } else if (firstFailure == null) {
                val failure = forestTaskActionFailureResult(
                    response = payload,
                    rpc = "AntForestRpcCall.transferEnergyForOneClickWatering",
                    detail = forestTaskActionDetail(item, "transferEnergyForOneClickWatering")
                )
                firstFailure = failure
                partialFailureMessages.add("$maskedName: ${failure.message}")
                Log.error(TAG, "森林任务🚿[${item.title}]推荐好友浇水失败[$maskedName] ${failure.message}")
            } else {
                val failureMessage = extractForestTaskFailureMessage(payload)
                partialFailureMessages.add("$maskedName: $failureMessage")
                Log.error(TAG, "森林任务🚿[${item.title}]推荐好友浇水失败[$maskedName] $failureMessage")
            }
        }
        if (wateredCount <= 0) {
            return firstFailure ?: TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                code = "ONE_CLICK_WATERING_NO_PROGRESS",
                message = "随机浇水任务未形成有效浇水",
                rpc = "AntForestRpcCall.transferEnergyForOneClickWatering",
                detail = forestTaskActionDetail(item, "transferEnergyForOneClickWatering")
            )
        }
        if (partialFailureMessages.isNotEmpty()) {
            Log.error(TAG, "森林任务🚿[${item.title}]部分好友浇水失败: ${partialFailureMessages.joinToString(" | ")}")
        }
        val response = AntForestRpcCall.finishTask(
            item.sceneCode,
            item.type,
            AntForestRpcCall.OPEN_GREEN_RIGHTS_SOURCE
        )
        if (response.isBlank()) {
            return emptyForestTaskActionResponse("AntForestRpcCall.finishTask", item, "oneClickWateringFinishTask")
        }
        val finishTaskResponse = JSONObject(response)
        return when {
            isForestTaskAlreadyHandled(finishTaskResponse) -> TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.TERMINAL_DONE,
                code = extractForestTaskFailureCode(finishTaskResponse),
                message = extractForestTaskFailureMessage(finishTaskResponse),
                rpc = "AntForestRpcCall.finishTask",
                raw = finishTaskResponse.toString(),
                detail = forestTaskActionDetail(item, "oneClickWateringFinishTask")
            )

            ResChecker.checkRes(TAG, "完成随机浇水任务失败:", finishTaskResponse) -> {
                val daySuffix = totalCountByDay?.let { " 今日总浇水${it}次" }.orEmpty()
                Log.forest("森林任务🧾️[${item.title}]随机浇水完成#好友${wateredCount}位$daySuffix")
                TaskFlowActionResult.success(refreshAfterAction = true)
            }

            else -> forestTaskActionFailureResult(
                response = finishTaskResponse,
                rpc = "AntForestRpcCall.finishTask",
                detail = forestTaskActionDetail(item, "oneClickWateringFinishTask")
            )
        }
    }

    private fun emptyForestTaskActionResponse(
        rpc: String,
        item: TaskFlowItem,
        action: String
    ): TaskFlowActionResult {
        return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.RETRYABLE_RPC,
            message = "${action}返回空",
            rpc = rpc,
            detail = forestTaskActionDetail(item, action),
            stopCurrentRound = true
        )
    }

    private fun missingForestTaskRawResult(item: TaskFlowItem, action: String): TaskFlowActionResult {
        return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            message = "缺少森林任务原始数据",
            rpc = "ForestTaskFlowAdapter.$action",
            detail = forestTaskActionDetail(item, action)
        )
    }

    private fun forestTaskActionFailureResult(
        response: JSONObject,
        rpc: String,
        detail: String
    ): TaskFlowActionResult {
        val code = extractForestTaskFailureCode(response)
        val message = extractForestTaskFailureMessage(response)
        return TaskFlowActionResult.failure(
            failureType = classifyForestTaskFailure(response),
            code = code,
            message = message,
            rpc = rpc,
            raw = response.toString(),
            detail = detail
        )
    }

    private fun forestTaskActionDetail(item: TaskFlowItem, action: String): String {
        return "taskType=${item.type} sceneCode=${item.sceneCode} action=$action"
    }

    private fun buildForestTaskKey(sceneCode: String, taskType: String): String {
        return "$sceneCode#$taskType"
    }

    private fun buildForestTaskCanonicalKey(
        taskInfo: JSONObject,
        taskBaseInfo: JSONObject,
        taskTitle: String
    ): String {
        val sceneCode = taskBaseInfo.optString("sceneCode")
        val taskType = taskBaseInfo.optString("taskType")
        if (sceneCode.isNotBlank() && taskType.isNotBlank()) {
            return buildForestTaskKey(sceneCode, taskType)
        }

        val bizInfo = parseTaskBizInfo(taskBaseInfo)
        val groupId = sequenceOf(
            taskBaseInfo.optString("groupId"),
            taskInfo.optString("groupId"),
            bizInfo.optString("groupId")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        return listOf(groupId, taskType, taskTitle)
            .filter { it.isNotBlank() }
            .joinToString("#")
            .ifBlank { taskInfo.toString().hashCode().toString() }
    }

    private fun forestTaskItemKey(item: TaskFlowItem): String {
        return item.raw?.optString("taskKey")
            ?.takeIf { it.isNotBlank() }
            ?: buildForestTaskKey(item.sceneCode, item.type)
    }

    private fun hasForestTaskChildren(item: TaskFlowItem): Boolean {
        return item.raw?.optBoolean("hasChildren", false) == true
    }

    private fun hasEnergyRainCollectHint(takeLookEndPayload: JSONObject): Boolean {
        val extInfo = takeLookEndPayload.optJSONObject("extInfoInTakeLookEnd") ?: return false
        val hintTitles = listOf("energyGrant", "energyPlay").mapNotNull { key ->
            extInfo.optJSONObject(key)?.optString("title")?.takeIf { it.isNotBlank() }
        }
        return hintTitles.any { title ->
            title.contains("还能收取") || title.contains("还可收取")
        }
    }

    private fun queryTakeLookEndPayload(source: String? = null): JSONObject? {
        val actualSource = source?.takeIf { it.isNotBlank() }
        val sourceName = if (actualSource.isNullOrBlank()) {
            "takeLookEnd"
        } else {
            "takeLookEnd($actualSource)"
        }
        return queryForestTaskSource(sourceName) {
            if (actualSource.isNullOrBlank()) {
                AntForestRpcCall.takeLookEnd()
            } else {
                AntForestRpcCall.takeLookEnd(actualSource)
            }
        }
    }

    private fun processTakeLookEndTaskList(source: String? = null, takeLookEndPayload: JSONObject? = null) {
        try {
            if (receiveForestTaskAward?.value != true) {
                return
            }
            if (takeLookEndPayload?.optBoolean("showTaskList", false) != true) {
                return
            }
            val actualSource = source?.takeIf { it.isNotBlank() }
            val sourceName = if (actualSource.isNullOrBlank()) {
                "take_look_end_task_list"
            } else {
                "take_look_end_task_list($actualSource)"
            }
            val taskResponse = queryForestTaskSource(sourceName) {
                if (actualSource.isNullOrBlank()) {
                    AntForestRpcCall.queryTakeLookEndTaskList()
                } else {
                    AntForestRpcCall.queryTakeLookEndTaskList(actualSource)
                }
            } ?: return

            val deferredForestRightsTasks = linkedMapOf<String, DeferredForestRightsTask>()
            val seenTaskKeys = mutableSetOf<String>()
            val taskNodes = collectForestTaskNodes(taskResponse)
            for (taskNode in taskNodes) {
                appendDeferredForestRightsTask(taskNode, deferredForestRightsTasks)
                handleForestTaskNodeRewardOnly(taskNode, seenTaskKeys)
            }
            collectDeferredForestRights(
                deferredForestRightsTasks.values,
                actualSource
            )
        } catch (t: Throwable) {
            handleException("processTakeLookEndTaskList", t)
        }
    }

    internal suspend fun handleEnergyRainPostFlow() {
        try {
            val takeLookEndPayload = queryTakeLookEndPayload(AntForestRpcCall.BACK_FROM_ENERGY_RAIN_SOURCE)
            if (takeLookEndPayload != null && hasEnergyRainCollectHint(takeLookEndPayload)) {
                Log.forest("能量雨收尾页提示仍有可收取机会，补做一次显式检查")
                val refreshedTakeLookEndPayload = collectEnergyByTakeLook(AntForestRpcCall.BACK_FROM_ENERGY_RAIN_SOURCE)
                if (refreshedTakeLookEndPayload == null) {
                    processTakeLookEndTaskList(AntForestRpcCall.BACK_FROM_ENERGY_RAIN_SOURCE, takeLookEndPayload)
                }
                return
            }
            processTakeLookEndTaskList(AntForestRpcCall.BACK_FROM_ENERGY_RAIN_SOURCE, takeLookEndPayload)
        } catch (t: Throwable) {
            handleException("handleEnergyRainPostFlow", t)
        }
    }

    private inner class ForestTaskFlowAdapter(
        private val taskSources: List<Pair<String, () -> String>>,
        private val deferredForestRightsTasks: MutableMap<String, DeferredForestRightsTask>,
        private val targetTaskTypes: Set<String> = emptySet()
    ) : TaskFlowAdapter {
        override val moduleName: String = forestTaskBlacklistModule
        override val flowName: String = "森林任务"
        private val greenPracticeManualOnlyLogged = mutableSetOf<String>()
        private val roundCompletedTaskKeys = mutableSetOf<String>()
        private val roundReceivedTaskKeys = mutableSetOf<String>()
        private val duplicateSkipLogged = mutableSetOf<String>()

        override fun query(): JSONObject {
            val responseArray = JSONArray()
            val uniqueSigns = linkedMapOf<String, JSONObject>()
            for ((sourceName, request) in taskSourcesForCurrentQuery()) {
                val taskResponse = queryForestTaskSource(sourceName, request) ?: continue
                responseArray.put(
                    JSONObject()
                        .put("sourceName", sourceName)
                        .put("payload", taskResponse)
                )
                if ("popupTask" == sourceName) {
                    appendSignInfo(taskResponse.optJSONObject("energySignVO"), uniqueSigns)
                }
                appendSignInfo(taskResponse.optJSONArray("forestSignVOList"), uniqueSigns)
            }

            val signArray = JSONArray()
            uniqueSigns.values.forEach { signArray.put(it) }
            return JSONObject()
                .put("success", true)
                .put("responses", responseArray)
                .put("signs", signArray)
        }

        private fun taskSourcesForCurrentQuery(): List<Pair<String, () -> String>> {
            if (targetTaskTypes.isNotEmpty() ||
                (roundCompletedTaskKeys.isEmpty() && roundReceivedTaskKeys.isEmpty())
            ) {
                return taskSources
            }
            val followUpSources = taskSources.filter { (sourceName, _) ->
                sourceName == "popupTask" || sourceName == "take_look_end_task_list"
            }
            return followUpSources.ifEmpty { taskSources }
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return response.optBoolean("success", true)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val items = mutableListOf<TaskFlowItem>()
            val signs = response.optJSONArray("signs")
            if (targetTaskTypes.isEmpty() && signs != null && hasPendingForestSign(signs)) {
                items.add(
                    TaskFlowItem(
                        id = FOREST_SIGN_TASK_TYPE,
                        title = "森林签到",
                        status = TaskStatus.TODO.name,
                        type = FOREST_SIGN_TASK_TYPE,
                        actionType = "SIGN",
                        blacklistKeys = emptyList(),
                        raw = JSONObject().put("signs", signs),
                        progress = "signCount=${signs.length()}"
                    )
                )
            }

            val uniqueTasks = linkedMapOf<String, ForestTaskCandidate>()
            val duplicateSourceSkipCounts = linkedMapOf<String, Int>()
            fun recordDuplicateSource(taskKey: String, sourceName: String, item: TaskFlowItem) {
                duplicateSourceSkipCounts[sourceName] = (duplicateSourceSkipCounts[sourceName] ?: 0) + 1
                if (duplicateSkipLogged.add("$taskKey|$sourceName")) {
                    Log.debug(TAG, "森林任务[${item.title}] 已从更优来源收敛，跳过重复来源[$sourceName]")
                }
            }
            val responses = response.optJSONArray("responses") ?: return items
            for (i in 0 until responses.length()) {
                val responseItem = responses.optJSONObject(i) ?: continue
                val sourceName = responseItem.optString("sourceName")
                val payload = responseItem.optJSONObject("payload") ?: continue
                val taskNodes = collectForestTaskNodes(payload)
                for (taskInfo in taskNodes) {
                    val taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo") ?: continue
                    val taskType = taskBaseInfo.optString("taskType")
                    val sceneCode = taskBaseInfo.optString("sceneCode")
                    if (taskType.isBlank() || sceneCode.isBlank()) {
                        continue
                    }
                    if (targetTaskTypes.isNotEmpty() && taskType !in targetTaskTypes) {
                        continue
                    }
                    appendDeferredForestRightsTask(taskInfo, deferredForestRightsTasks)

                    val bizInfo = parseTaskBizInfo(taskBaseInfo)
                    val taskRights = parseTaskRights(taskInfo)
                    val awardCount = taskRights.optInt("awardCount", 0)
                    val taskStatus = taskBaseInfo.optString("taskStatus")
                    val taskProgress = taskBaseInfo.optInt("taskProgress", 0)
                    val taskRequire = taskBaseInfo.optInt("taskRequire", 0).takeIf { it > 0 }
                    val hasChildren = taskInfo.optJSONArray("childTaskTypeList")?.length()?.let { it > 0 } ?: false
                    val taskTitle = getForestTaskTitle(taskBaseInfo, taskType)
                    val taskKey = buildForestTaskCanonicalKey(taskInfo, taskBaseInfo, taskTitle)
                    val raw = JSONObject()
                        .put("taskInfo", taskInfo)
                        .put("taskBaseInfo", taskBaseInfo)
                        .put("bizInfo", bizInfo)
                        .put("taskRights", taskRights)
                        .put("taskKey", taskKey)
                        .put("sourceName", sourceName)
                        .put("awardCount", awardCount)
                        .put("hasChildren", hasChildren)

                    val item = TaskFlowItem(
                        id = taskType,
                        title = taskTitle,
                        status = taskStatus,
                        type = taskType,
                        sceneCode = sceneCode,
                        actionType = taskBaseInfo.optString("actionType")
                            .ifBlank { bizInfo.optString("actionType") },
                        blacklistKeys = listOf(taskType, taskTitle).filter { it.isNotBlank() },
                        raw = raw,
                        progress = "award=$awardCount progress=$taskProgress/${taskRequire ?: 0}",
                        current = taskProgress,
                        limit = taskRequire
                    )
                    val existing = uniqueTasks[taskKey]
                    if (existing == null) {
                        uniqueTasks[taskKey] = ForestTaskCandidate(item, sourceName)
                    } else if (shouldPreferForestTaskCandidate(item, sourceName, existing)) {
                        uniqueTasks[taskKey] = ForestTaskCandidate(item, sourceName)
                        recordDuplicateSource(taskKey, existing.sourceName, existing.item)
                    } else {
                        recordDuplicateSource(taskKey, sourceName, item)
                    }
                }
            }
            items.addAll(uniqueTasks.values.map { it.item })
            logDuplicateSourceSummary(duplicateSourceSkipCounts)
            return items
        }

        private fun logDuplicateSourceSummary(duplicateSourceSkipCounts: Map<String, Int>) {
            if (duplicateSourceSkipCounts.isEmpty()) {
                return
            }
            val total = duplicateSourceSkipCounts.values.sum()
            val sourceSummary = duplicateSourceSkipCounts.entries.joinToString(", ") { (source, count) ->
                "$source=$count"
            }
            Log.forest("森林任务来源收敛: 跳过重复来源${total}项（$sourceSummary）")
        }

        private fun shouldPreferForestTaskCandidate(
            candidate: TaskFlowItem,
            sourceName: String,
            existing: ForestTaskCandidate
        ): Boolean {
            val candidatePhasePriority = forestTaskPhasePriority(mapPhase(candidate))
            val existingPhasePriority = forestTaskPhasePriority(mapPhase(existing.item))
            if (candidatePhasePriority != existingPhasePriority) {
                return candidatePhasePriority < existingPhasePriority
            }
            return forestTaskSourcePriority(sourceName) < forestTaskSourcePriority(existing.sourceName)
        }

        private fun forestTaskPhasePriority(phase: TaskFlowPhase): Int {
            return when (phase) {
                TaskFlowPhase.REWARD_READY -> 0
                TaskFlowPhase.READY_TO_COMPLETE -> 1
                TaskFlowPhase.BUSINESS_ACTION -> 2
                TaskFlowPhase.UNSUPPORTED -> 3
                TaskFlowPhase.TERMINAL -> 4
                else -> 5
            }
        }

        private fun forestTaskSourcePriority(sourceName: String): Int {
            return when (sourceName) {
                "popupTask" -> 0
                "home_leaves_task_list" -> 1
                "take_look_end_task_list" -> 2
                "home_task_list" -> 3
                else -> 4
            }
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            if (item.type == FOREST_SIGN_TASK_TYPE) {
                return TaskFlowPhase.READY_TO_COMPLETE
            }
            if (isDeferredForestRightsTaskType(item.type) || isEnergyRainTaskCenterTaskType(item.type)) {
                return TaskFlowPhase.TERMINAL
            }
            return when (item.status) {
                TaskStatus.FINISHED.name,
                "COMPLETE",
                "WAIT_RECEIVE",
                "TO_RECEIVE" -> TaskFlowPhase.REWARD_READY

                TaskStatus.TODO.name,
                "WAIT_COMPLETE" -> when {
                    isGreenPracticeChildItem(item) -> TaskFlowPhase.BUSINESS_ACTION
                    isGreenPracticeParentItem(item) -> {
                        if (isGreenPracticeParentWithoutAutoAction(item)) {
                            TaskFlowPhase.UNSUPPORTED
                        } else {
                            TaskFlowPhase.READY_TO_COMPLETE
                        }
                    }
                    hasForestTaskChildren(item) -> TaskFlowPhase.BUSINESS_ACTION
                    else -> TaskFlowPhase.READY_TO_COMPLETE
                }

                TaskStatus.RECEIVED.name,
                "HAS_RECEIVED",
                "DONE",
                "COMPLETED" -> TaskFlowPhase.TERMINAL

                else -> TaskFlowPhase.UNKNOWN
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            if (Thread.currentThread().isInterrupted || isGreenPracticeChildItem(item)) {
                return true
            }
            val taskKey = forestTaskItemKey(item)
            val phase = mapPhase(item)
            if (roundReceivedTaskKeys.contains(taskKey)) {
                if (duplicateSkipLogged.add("$taskKey|received")) {
                    Log.debug(TAG, "森林任务[${item.title}] 本轮已领奖，跳过重复检查")
                }
                return true
            }
            if (phase != TaskFlowPhase.REWARD_READY && roundCompletedTaskKeys.contains(taskKey)) {
                if (duplicateSkipLogged.add("$taskKey|completed")) {
                    Log.debug(TAG, "森林任务[${item.title}] 本轮已推进，跳过重复完成探测")
                }
                return true
            }
            if (isGreenPracticeParentWithoutAutoAction(item)) {
                val logKey = item.id.ifBlank { item.title }
                if (greenPracticeManualOnlyLogged.add(logKey)) {
                    Log.forest("森林任务[${item.title}] 绿色践行剩余任务需真实行为或已在黑名单，保留待手动完成后领奖")
                }
                return true
            }
            return false
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            return receiveForestTaskReward(item)
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            if (item.type == FOREST_SIGN_TASK_TYPE) {
                val signs = item.raw?.optJSONArray("signs") ?: JSONArray()
                return completeForestSignTasks(signs)
            }
            if (item.type == ONE_CLICK_WATERING_TASK_TYPE) {
                return completeOneClickWateringTask(item)
            }
            if (isGreenPracticeParentItem(item)) {
                val taskInfo = item.raw?.optJSONObject("taskInfo")
                    ?: return missingForestTaskRawResult(item, "finishGreenPracticeTask")
                return if (handleGreenPracticeTask(taskInfo)) {
                    TaskFlowActionResult.success()
                } else {
                    TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = "绿色践行任务本轮未推进",
                        rpc = "AntForestRpcCall.finishTask",
                        detail = forestTaskActionDetail(item, "finishGreenPracticeTask")
                    )
                }
            }
            if (item.type == LEGACY_FOREST_GAME_TASK_TYPE) {
                return completeLegacyForestGameTaskResult(item)
            }
            return completeOrdinaryForestTask(item)
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            if (item.type == FOREST_SIGN_TASK_TYPE) {
                return "${action.logName}:$FOREST_SIGN_TASK_TYPE"
            }
            val taskKey = forestTaskItemKey(item)
            return when (action) {
                TaskFlowAction.RECEIVE -> "receive:$taskKey"
                TaskFlowAction.COMPLETE -> "complete:$taskKey"
                else -> super<TaskFlowAdapter>.actionKey(item, action)
            }
        }

        override fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
            if (item.type == FOREST_SIGN_TASK_TYPE) {
                return
            }
            val taskKey = forestTaskItemKey(item)
            when (action) {
                TaskFlowAction.RECEIVE -> roundReceivedTaskKeys.add(taskKey)
                TaskFlowAction.COMPLETE -> {
                    roundCompletedTaskKeys.add(taskKey)
                    forestTaskTryCount.remove(buildForestTaskKey(item.sceneCode, item.type))
                }
                else -> Unit
            }
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision
        ) {
            if (item.type == FOREST_SIGN_TASK_TYPE) {
                return
            }
            val taskKey = forestTaskItemKey(item)
            when {
                action == TaskFlowAction.RECEIVE &&
                    (decision == TaskFlowDecision.MARK_HANDLED ||
                        decision == TaskFlowDecision.BLACKLIST ||
                        decision == TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN) -> {
                    roundReceivedTaskKeys.add(taskKey)
                }

                action == TaskFlowAction.COMPLETE &&
                    (decision == TaskFlowDecision.MARK_HANDLED ||
                        decision == TaskFlowDecision.BLACKLIST ||
                        decision == TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN) -> {
                    roundCompletedTaskKeys.add(taskKey)
                    forestTaskTryCount.remove(buildForestTaskKey(item.sceneCode, item.type))
                }
            }
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.error(TAG, "森林任务列表查询失败 raw=$response")
        }

        override fun logInfo(message: String) {
            Log.forest(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun hasPendingForestSign(signs: JSONArray): Boolean {
            for (signIndex in 0 until signs.length()) {
                val forestSignVO = signs.optJSONObject(signIndex) ?: continue
                val currentSignKey = forestSignVO.optString("currentSignKey")
                val signRecords = forestSignVO.optJSONArray("signRecords") ?: continue
                for (recordIndex in 0 until signRecords.length()) {
                    val signRecord = signRecords.optJSONObject(recordIndex) ?: continue
                    if (signRecord.optString("signKey") == currentSignKey &&
                        !signRecord.optBoolean("signed", true)
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        private fun isGreenPracticeParentItem(item: TaskFlowItem): Boolean {
            val taskBaseInfo = item.raw?.optJSONObject("taskBaseInfo") ?: return false
            return isGreenPracticeParentTask(taskBaseInfo, item.type)
        }

        private fun isGreenPracticeChildItem(item: TaskFlowItem): Boolean {
            val taskBaseInfo = item.raw?.optJSONObject("taskBaseInfo") ?: return false
            return isGreenPracticeChildTask(taskBaseInfo, item.type)
        }

        private fun isGreenPracticeParentWithoutAutoAction(item: TaskFlowItem): Boolean {
            val raw = item.raw ?: return false
            val taskBaseInfo = raw.optJSONObject("taskBaseInfo") ?: return false
            if (!isGreenPracticeParentTask(taskBaseInfo, item.type)) {
                return false
            }
            if (item.status != TaskStatus.TODO.name && item.status != "WAIT_COMPLETE") {
                return false
            }
            val taskInfo = raw.optJSONObject("taskInfo") ?: return false
            return !hasActionableGreenPracticeChild(taskInfo)
        }
    }

    /**
     * 森林任务:
     * 逛会员,去森林寻宝抽1t能量
     * 防治荒漠化和干旱日,给随机好友一键浇水
     * 开通高德活动领,去吉祥林许个愿
     * 逛森林集市得能量,逛一逛618会场
     * 逛一逛点淘得红包,去一淘签到领红包
     */
    internal fun receiveTaskAward() {
        try {
            val taskSources = listOf(
                "popupTask" to { AntForestRpcCall.popupTask() },
                "home_leaves_task_list" to { AntForestRpcCall.queryLeafTaskList() },
                "take_look_end_task_list" to { AntForestRpcCall.queryTakeLookEndTaskList() },
                "home_task_list" to { AntForestRpcCall.queryTaskList() }
            )
            val deferredForestRightsTasks = linkedMapOf<String, DeferredForestRightsTask>()
            TaskFlowEngine(
                ForestTaskFlowAdapter(taskSources, deferredForestRightsTasks),
                roundSleepMs = 1500L
            ).run()
            collectDeferredForestRights(deferredForestRightsTasks.values)
        } catch (t: Throwable) {
            handleException("receiveTaskAward", t)
        }
    }

    internal fun closeEnergyRainGameDriveTask(
        request: EnergyRainCoroutine.EnergyRainGameDriveRequest
    ): EnergyRainCoroutine.EnergyRainGameDriveResult {
        val driveTaskType = request.driveTaskType.takeIf { it.isNotBlank() }
            ?: return EnergyRainCoroutine.EnergyRainGameDriveResult(
                EnergyRainCoroutine.EnergyRainGameDriveStatus.NOT_FOUND,
                "缺少普通森林驱动任务类型"
            )
        if (TaskBlacklist.isTaskInBlacklist(forestTaskBlacklistModule, driveTaskType)) {
            return EnergyRainCoroutine.EnergyRainGameDriveResult(
                EnergyRainCoroutine.EnergyRainGameDriveStatus.SKIPPED_BLACKLISTED,
                "普通森林驱动任务已在黑名单: $driveTaskType"
            )
        }

        return try {
            val taskSources = listOf(
                "energy_rain_drive_listTaskByIds" to {
                    AntForestRpcCall.listTaskByIdsopengreen(
                        "ANTFOREST_VITALITY_TASK",
                        "antforest",
                        listOf(driveTaskType)
                    )
                },
                "energy_rain_drive_take_look_end" to {
                    AntForestRpcCall.queryTakeLookEndTaskList(AntForestRpcCall.BACK_FROM_ENERGY_RAIN_SOURCE)
                },
                "energy_rain_drive_home_task_list" to { AntForestRpcCall.queryTaskList() }
            )
            val deferredForestRightsTasks = linkedMapOf<String, DeferredForestRightsTask>()
            val runResult = TaskFlowEngine(
                ForestTaskFlowAdapter(
                    taskSources = taskSources,
                    deferredForestRightsTasks = deferredForestRightsTasks,
                    targetTaskTypes = setOf(driveTaskType)
                ),
                roundSleepMs = 500L
            ).run()
            collectDeferredForestRights(
                deferredForestRightsTasks.values,
                AntForestRpcCall.BACK_FROM_ENERGY_RAIN_SOURCE
            )

            val message = "gameTask=${request.gameTaskType}(${request.gameTaskStatus}) driveTask=$driveTaskType"
            val status = when {
                TaskBlacklist.isTaskInBlacklist(forestTaskBlacklistModule, driveTaskType) ->
                    EnergyRainCoroutine.EnergyRainGameDriveStatus.SKIPPED_BLACKLISTED

                runResult.completed -> EnergyRainCoroutine.EnergyRainGameDriveStatus.CONFIRMED_DONE
                runResult.progressed -> EnergyRainCoroutine.EnergyRainGameDriveStatus.PROGRESSED
                runResult.stopped || runResult.interrupted -> EnergyRainCoroutine.EnergyRainGameDriveStatus.RETRYABLE_FAILED
                runResult.actionAttempted -> EnergyRainCoroutine.EnergyRainGameDriveStatus.NO_PROGRESS
                else -> EnergyRainCoroutine.EnergyRainGameDriveStatus.NOT_FOUND
            }
            EnergyRainCoroutine.EnergyRainGameDriveResult(status, message)
        } catch (t: Throwable) {
            handleException("closeEnergyRainGameDriveTask", t)
            EnergyRainCoroutine.EnergyRainGameDriveResult(
                EnergyRainCoroutine.EnergyRainGameDriveStatus.RETRYABLE_FAILED,
                "普通森林驱动任务闭环异常: ${t.message.orEmpty()}"
            )
        }
    }

    /**
     * 在收集能量之前使用道具。
     * 这个方法检查是否需要使用增益卡
     * 并在需要时使用相应的道具。
     */
    /**
     * 在收集能量之前决定是否使用增益类道具卡
     * @param userId 用户ID
     * @param skipPropCheck 是否跳过道具检查（快速收取通道）
     * @return 最新的主页对象
     */
    internal fun usePropBeforeCollectEnergy(userId: String?, skipPropCheck: Boolean = false): JSONObject? {
        var latestHome: JSONObject? = null
        var checkedThisCall = false
        try {
            // 🚀 快速收取通道：跳过道具检查，直接返回
            if (skipPropCheck) {
                Log.forest("⚡ 快速收取通道：跳过道具检查，加速蹲点收取")
                return null
            }
            roundPropCheckState?.let { cachedState ->
                Log.debug(TAG, "本轮道具检查已完成，复用当前状态，跳过重复请求")
                return cachedState.latestHome
            }
            val now = System.currentTimeMillis()
            if (now - lastUsePropCheckTime < 5000) {
                Log.debug(TAG, "道具检查刚在5秒内完成，复用当前状态，跳过重复请求")
                return null
            }
            lastUsePropCheckTime = now
            checkedThisCall = true

            /*
             * 在收集能量之前决定是否使用增益类道具卡。
             *
             * 主要逻辑:
             * 1. 定义时间常量，用于判断道具剩余有效期。
             * 2. 获取当前时间及各类道具的到期时间，计算剩余时间。
             * 3. 根据以下条件判断是否需要使用特定道具:
             *    - needDouble: 双击卡开关已打开，且当前没有生效的双击卡。
             *    - needRobMultiplierCard: 收好友N倍卡开关已打开，且当前目标是好友能量；具体是否使用由倍率/延期策略决定。
             *    - needStealth: 隐身卡开关已打开，且当前没有生效的隐身卡。
             *    - needShield: 保护罩开关已打开，炸弹卡开关已关闭，且保护罩剩余时间不足一天。
             *    - needEnergyBombCard: 炸弹卡开关已打开，保护罩开关已关闭，且炸弹卡剩余时间不足三天。
             *    - needBubbleBoostCard: 加速卡开关已打开。
             * 4. 如果有任何一个道具需要使用，则同步查询背包信息，并调用相应的使用道具方法。
             */

            // 双击卡判断
            val needDouble =
                doubleCard!!.value != ApplyPropType.CLOSE && shouldRenewDoubleCard(
                    doubleEndTime,
                    now
                )

            val isFriendCollectTarget = !userId.isNullOrBlank() && userId != UserMap.currentUid
            val needRobMultiplierCard =
                isFriendCollectTarget && robMultiplierCard!!.value != ApplyPropType.CLOSE
            val needStealth =
                stealthCard!!.value != ApplyPropType.CLOSE && stealthEndTime < now

            // 保护罩判断
            val needShield =
                (shieldCard!!.value != ApplyPropType.CLOSE) && energyBombCardType!!.value == ApplyPropType.CLOSE
                        && shouldRenewShield(shieldEndTime, now)
            // 炸弹卡判断
            val needEnergyBombCard =
                (energyBombCardType!!.value != ApplyPropType.CLOSE) && shieldCard!!.value == ApplyPropType.CLOSE
                        && shouldRenewEnergyBomb(energyBombCardEndTime, now)

            val needBubbleBoostCard = bubbleBoostCard!!.value != ApplyPropType.CLOSE

            Log.forest("道具使用检查: needDouble=" + needDouble + ", needRobMultiplierCard=" + needRobMultiplierCard +
                        ", needStealth=" + needStealth + ", needShield=" + needShield +
                        ", needEnergyBombCard=" + needEnergyBombCard + ", needBubbleBoostCard=" + needBubbleBoostCard
            )
            if (needDouble || needStealth || needShield || needEnergyBombCard || needRobMultiplierCard || needBubbleBoostCard) {
                synchronized(doubleCardLockObj) {
                    val bagObject = queryPropList()
                    if (bagObject == null) {
                        Log.forest("背包查询为空，跳过本轮道具检查")
                        return null
                    }
                    // Log.runtime(TAG, "bagObject=" + (bagObject == null ? "null" : bagObject.toString()));
                    if (needDouble) useDoubleCard(bagObject) // 使用双击卡

                    if (needRobMultiplierCard) useRobMultiplierCard(bagObject) // 使用收好友N倍卡

                    if (needStealth) useStealthCard(bagObject) // 使用隐身卡

                    if (needBubbleBoostCard) {
                        latestHome = handleBubbleBoostTrigger(bagObject)
                    }
                    if (needShield) {
                        Log.forest("尝试使用保护罩罩")
                        useShieldCard(bagObject)
                    } else if (needEnergyBombCard) {
                        Log.forest("准备使用能量炸弹卡")
                        useEnergyBombCard(bagObject)
                    }
                }
            } else {
                Log.forest("没有需要使用的道具")
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
        if (checkedThisCall) {
            roundPropCheckState = PropCheckRoundState(latestHome)
        }
        return latestHome
    }

    private fun handleBubbleBoostTrigger(bagObject: JSONObject?): JSONObject? {
        val spec = bubbleBoostTime?.getTriggerSpec() ?: return null
        if (spec.disabled) {
            Log.forest("加速器时间触发已关闭，跳过")
            return null
        }

        val consumedIndex = getTriggerIndex(StatusFlags.FLAG_ANTFOREST_BUBBLE_BOOST_TRIGGER_INDEX)
        val decision = TimeTriggerEvaluator.evaluateNow(spec, consumedIndex = consumedIndex)
        if (decision.allowNow) {
            consumeTriggerSlot(StatusFlags.FLAG_ANTFOREST_BUBBLE_BOOST_TRIGGER_INDEX, decision.matchedSlotIndex)
            return useBubbleBoostCard(bagObject)
        }

        if (!TimeTriggerEvaluator.scheduleNext(
                this,
                "bubbleBoost",
                spec,
                consumedIndex,
                Runnable { executeBubbleBoostTriggerFromSchedule() }
            )
        ) {
            logTriggerWaiting("加速器", decision)
        }
        return null
    }

    private fun executeBubbleBoostTriggerFromSchedule() {
        synchronized(doubleCardLockObj) {
            val bagObject = queryPropList()
            if (bagObject == null) {
                Log.forest("背包查询为空，跳过定时加速卡触发")
                return
            }

            val spec = bubbleBoostTime?.getTriggerSpec() ?: return
            val consumedIndex = getTriggerIndex(StatusFlags.FLAG_ANTFOREST_BUBBLE_BOOST_TRIGGER_INDEX)
            val decision = TimeTriggerEvaluator.evaluateNow(spec, consumedIndex = consumedIndex)
            if (!decision.allowNow) {
                logTriggerWaiting("加速器", decision)
                return
            }

            consumeTriggerSlot(StatusFlags.FLAG_ANTFOREST_BUBBLE_BOOST_TRIGGER_INDEX, decision.matchedSlotIndex)
            useBubbleBoostCard(bagObject)
        }
    }

    private fun evaluateTriggerDecision(
        field: TimeTriggerModelField?,
        statusKey: String
    ): TimeTriggerDecision? {
        val spec = field?.getTriggerSpec() ?: return null
        if (spec.disabled) {
            return null
        }
        return TimeTriggerEvaluator.evaluateNow(spec, consumedIndex = getTriggerIndex(statusKey))
    }

    private fun getTriggerIndex(statusKey: String): Int {
        return Status.getIntFlagToday(statusKey) ?: 0
    }

    private fun consumeTriggerSlot(statusKey: String, matchedSlotIndex: Int) {
        if (matchedSlotIndex < 0) {
            return
        }
        val nextIndex = matchedSlotIndex + 1
        val currentIndex = getTriggerIndex(statusKey)
        if (nextIndex > currentIndex) {
            Status.setIntFlagToday(statusKey, nextIndex)
        }
    }

    private fun logTriggerWaiting(label: String, decision: TimeTriggerDecision?) {
        when {
            decision == null -> Log.forest("$label 时间触发已关闭，跳过")
            decision.blockedNow && decision.nextTriggerAt != null ->
                Log.forest("$label 当前命中禁止窗口，等待${TimeUtil.getCommonDate(decision.nextTriggerAt)}后再尝试")

            decision.nextTriggerAt != null ->
                Log.forest("$label 未到触发时机，下一次可尝试时间=${TimeUtil.getCommonDate(decision.nextTriggerAt)}")

            else -> Log.forest("$label 今日已无可用触发槽位，跳过")
        }
    }

    /**
     * 保护罩剩余时间判断
     * 新规则允许保护罩剩余有效期低于7天时延长使用。
     */
    @SuppressLint("DefaultLocale")
    private fun shouldRenewShield(shieldEnd: Long, nowMillis: Long): Boolean {
        // 检测异常数据
        if (shieldEnd > 0 && shieldEnd < nowMillis - 365 * TimeFormatter.ONE_DAY_MS) {
            Log.forest("[保护罩] ⚠️ 检测到异常时间数据(${TimeUtil.getCommonDate(shieldEnd)})，跳过检查")
            return false
        }

        if (shieldEnd in 1..nowMillis) { // 已过期
            Log.forest("[保护罩] 已过期，立即续写；end=" + TimeUtil.getCommonDate(shieldEnd) + ", now=" + TimeUtil.getCommonDate(
                    nowMillis
                )
            )
            return true
        }

        if (shieldEnd == 0L) { // 未生效
            Log.forest("[保护罩] 未生效，尝试使用")
            return true
        }
        val remain = shieldEnd - nowMillis
        val needRenew = remain < SHIELD_RENEW_THRESHOLD_MS
        // 格式化剩余时间和阈值时间为更直观的显示
        val remainTimeStr = TimeFormatter.formatRemainingTime(remain)
        val thresholdTimeStr = TimeFormatter.formatRemainingTime(SHIELD_RENEW_THRESHOLD_MS)
        if (needRenew) {
            Log.forest(String.format(
                    "[保护罩] 🔄 需要续写 - 剩余时间[%s] < 续写阈值[%s]",
                    remainTimeStr, thresholdTimeStr
                )
            )
        } else {
            Log.forest(String.format(
                    "[保护罩] ✅ 无需续写 - 剩余时间[%s] ≥ 续写阈值[%s]",
                    remainTimeStr, thresholdTimeStr
                )
            )
        }
        return needRenew
    }

    /**
     * 炸弹卡剩余时间判断
     * 当炸弹卡剩余时间低于3天时，需要续用
     * 最多可续用到4天
     */
    @SuppressLint("DefaultLocale")
    private fun shouldRenewEnergyBomb(bombEnd: Long, nowMillis: Long): Boolean {
        // 炸弹卡最长有效期为4天
        val maxBombDuration = 4 * TimeFormatter.ONE_DAY_MS
        // 炸弹卡续用阈值为3天
        val bombRenewThreshold = 3 * TimeFormatter.ONE_DAY_MS
        // 检测异常数据
        if (bombEnd > 0 && bombEnd < nowMillis - 365 * TimeFormatter.ONE_DAY_MS) {
            Log.forest("[炸弹卡] ⚠️ 检测到异常时间数据(${TimeUtil.getCommonDate(bombEnd)})，跳过检查")
            return false
        }

        if (bombEnd in 1..nowMillis) { // 已过期
            Log.forest("[炸弹卡] 已过期，立即续写；end=" + TimeUtil.getCommonDate(bombEnd) + ", now=" + TimeUtil.getCommonDate(
                    nowMillis
                )
            )
            return true
        }

        if (bombEnd == 0L) { // 未生效
            Log.forest("[炸弹卡] 未生效，尝试使用")
            return true
        }
        val remain = bombEnd - nowMillis
        // 如果剩余时间小于阈值且续写后总时长未超过最大有效期，则需要续用
        // 续写后结束时间 = bombEnd + 1天，续写后总时长 = 续写后结束时间 - 现在时间
        val renewDuration = TimeFormatter.ONE_DAY_MS // 每次续写增加1天
        val afterRenewRemain = remain + renewDuration // 续写后的剩余时间
        val needRenew =
            remain <= bombRenewThreshold && afterRenewRemain <= maxBombDuration

        val remainTimeStr = TimeFormatter.formatRemainingTime(remain)
        val thresholdTimeStr = TimeFormatter.formatRemainingTime(bombRenewThreshold)

        if (needRenew) {
            Log.forest(String.format(
                    "[炸弹卡] 🔄 需要续写 - 剩余时间[%s] ≤ 续写阈值[%s]",
                    remainTimeStr, thresholdTimeStr
                )
            )
        } else {
            Log.forest(String.format(
                    "[炸弹卡] ✅ 无需续写 - 剩余时间[%s] > 续写阈值[%s]",
                    remainTimeStr, thresholdTimeStr
                )
            )
        }
        return needRenew
    }

    /**
     * 双击卡剩余时间判断
     * 当双击卡剩余时间低于31天时，需要续用
     * 最多可续用到31+31天，但不建议，因为平时有5分钟、3天、7天等短期双击卡
     */
    @SuppressLint("DefaultLocale")
    private fun shouldRenewDoubleCard(doubleEnd: Long, nowMillis: Long): Boolean {
        // 双击卡最长有效期为62天（31+31）
        // 双击卡续用阈值为31天
        val doubleRenewThreshold = 31 * TimeFormatter.ONE_DAY_MS  // 改为小写开头

        // 如果doubleEnd为0或很久以前的时间（超过1年），说明数据未初始化或有问题
        if (doubleEnd > 0 && doubleEnd < nowMillis - 365 * TimeFormatter.ONE_DAY_MS) {
            Log.forest("[双击卡] ⚠️ 检测到异常时间数据(${TimeUtil.getCommonDate(doubleEnd)})，跳过检查")
            return false // 数据异常，不续用
        }

        if (doubleEnd in 1..nowMillis) { // 已过期
            Log.forest("[双击卡] 已过期，立即续写；end=" + TimeUtil.getCommonDate(doubleEnd) + ", now=" + TimeUtil.getCommonDate(
                    nowMillis
                )
            )
            return true
        }

        if (doubleEnd == 0L) { // 未生效（初始值）
            Log.forest("[双击卡] 未生效，尝试使用")
            return true
        }

        val remain = doubleEnd - nowMillis
        // 如果剩余时间小于阈值，则需要续用
        val needRenew = remain <= doubleRenewThreshold  // 使用修正后的变量名
        val remainTimeStr = TimeFormatter.formatRemainingTime(remain)
        val thresholdTimeStr = TimeFormatter.formatRemainingTime(doubleRenewThreshold)  // 使用修正后的变量名

        if (needRenew) {
            Log.forest(String.format(
                    "[双击卡] 🔄 需要续写 - 剩余时间[%s] ≤ 续写阈值[%s]",
                    remainTimeStr, thresholdTimeStr
                )
            )
        } else {
            Log.forest(String.format(
                    "[双击卡] ✅ 无需续写 - 剩余时间[%s] > 续写阈值[%s]",
                    remainTimeStr, thresholdTimeStr
                )
            )
        }
        return needRenew
    }

    internal fun giveProp() {
        val set = whoYouWantToGiveTo?.resolvedIds() ?: emptySet()
        if (set.isNotEmpty()) {
            for (userId in set) {
                if (FriendGuard.shouldSkipFriend(userId, TAG, "赠送森林道具")) continue
                if (queryFriendHome(userId, "giveProp") == null) continue
                giveProp(userId)
                break
            }
        }
    }

    /**
     * 向指定用户赠送道具。 这个方法首先查询可用的道具列表，然后选择一个道具赠送给目标用户。 如果有多个道具可用，会尝试继续赠送，直到所有道具都赠送完毕。
     *
     * @param targetUserId 目标用户的ID。
     */
    private fun giveProp(targetUserId: String?) {
        val safeTargetUserId = targetUserId ?: return
        try {
            do {
                // 查询道具列表
                val propListJo = JSONObject(AntForestRpcCall.queryPropList(true))
                if (ResChecker.checkRes(TAG, "查询道具列表失败:", propListJo)) {
                    val forestPropVOList = propListJo.optJSONArray("forestPropVOList")
                    if (forestPropVOList != null && forestPropVOList.length() > 0) {
                        val propJo = forestPropVOList.getJSONObject(0)
                        val giveConfigId =
                            propJo.getJSONObject("giveConfigVO").getString("giveConfigId")
                        val holdsNum = propJo.optInt("holdsNum", 0)
                        val propName = propJo.getJSONObject("propConfigVO").getString("propName")
                        val propId = propJo.getJSONArray("propIdList").getString(0)
                        val giveResultJo = JSONObject(
                            AntForestRpcCall.giveProp(
                                giveConfigId,
                                propId,
                                safeTargetUserId
                            )
                        )
                        if (ResChecker.checkRes(TAG, "赠送道具失败:", giveResultJo)) {
                            Log.forest("赠送道具🎭[" + UserMap.getMaskName(safeTargetUserId) + "]#" + propName)
                        } else {
                            val rt = giveResultJo.getString("resultDesc")
                            Log.forest(rt)
                            Log.forest(giveResultJo.toString())
                            if (rt.contains("异常")) {
                                return
                            }
                        }
                        // 如果持有数量大于1或道具列表中有多于一个道具，则继续赠送
                        if (holdsNum <= 1 && forestPropVOList.length() == 1) {
                            break
                        }
                    }
                } else {
                    // 如果查询道具列表失败，则记录失败的日志
                    Log.forest("赠送道具查询结果" + propListJo.getString("resultDesc"))
                }
            } while (!Thread.currentThread().isInterrupted)
        } catch (th: Throwable) {
            // 打印异常信息
            Log.printStackTrace(TAG, "giveProp err", th)
        }
    }

    private data class PatrolRecordInfo(
        val patrolId: Int,
        val startDate: Long,
        val reserveName: String,
        val patrolConfig: JSONObject,
        val userPatrol: JSONObject
    )

    private data class PatrolTargetRecord(
        val patrolId: Int,
        val reserveName: String,
        val reason: String
    )

    private fun collectPatrolRecordInfo(records: JSONArray): List<PatrolRecordInfo> {
        val recordInfos = mutableListOf<PatrolRecordInfo>()
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            val patrolConfig = record.optJSONObject("patrolConfig") ?: continue
            val userPatrol = record.optJSONObject("userPatrol") ?: JSONObject()
            val patrolId = patrolConfig.optInt("patrolId", userPatrol.optInt("patrolId", 0))
            if (patrolId <= 0) {
                continue
            }
            recordInfos.add(
                PatrolRecordInfo(
                    patrolId = patrolId,
                    startDate = patrolConfig.optLong("startDate", userPatrol.optLong("startDate", 0L)),
                    reserveName = patrolConfig.optString("reserveName").ifBlank { "保护地$patrolId" },
                    patrolConfig = patrolConfig,
                    userPatrol = userPatrol
                )
            )
        }
        return recordInfos
    }

    private fun hasUnreachedPatrolNode(userPatrol: JSONObject): Boolean {
        val unreachedNodeCount = userPatrol.optInt("unreachedNodeCount", -1)
        if (unreachedNodeCount >= 0) {
            return unreachedNodeCount > 0
        }
        val unreachedNodes = userPatrol.optJSONArray("unreachedNodes")
        return unreachedNodes != null && unreachedNodes.length() > 0
    }

    private fun isNormalPatrolAnimal(animal: JSONObject): Boolean {
        val status = animal.optString("status", "ONLINE")
        if (status.isNotBlank() && !status.equals("ONLINE", true)) {
            return false
        }
        if (animal.optBoolean("limited", false) ||
            animal.optBoolean("limit", false) ||
            animal.optBoolean("special", false)
        ) {
            return false
        }
        val extInfo = animal.optJSONObject("extInfo")
        if (extInfo != null &&
            (extInfo.optBoolean("limited", false) ||
                extInfo.optBoolean("limit", false) ||
                extInfo.optBoolean("special", false))
        ) {
            return false
        }
        val markerText = listOf(
            animal.optString("type"),
            animal.optString("name"),
            animal.optString("propType"),
            extInfo?.toString().orEmpty()
        ).joinToString("|").lowercase(Locale.ROOT)
        return listOf("limited", "limit", "special", "activity", "限定", "限时", "活動", "活动", "特殊")
            .none { markerText.contains(it) }
    }

    private fun normalPatrolAnimalIds(patrolConfig: JSONObject): Set<Int> {
        val animals = patrolConfig.optJSONArray("animals")
        if (animals == null || animals.length() == 0) {
            Log.forest("巡护地图缺少动物列表[patrolId=${patrolConfig.optInt("patrolId", 0)}]，跳过图鉴优先判断")
            return emptySet()
        }
        val animalIds = mutableSetOf<Int>()
        for (i in 0 until animals.length()) {
            val animal = animals.optJSONObject(i) ?: continue
            if (!isNormalPatrolAnimal(animal)) {
                continue
            }
            val animalId = animal.optInt("id", -1)
            if (animalId > 0) {
                animalIds.add(animalId)
            }
        }
        if (animalIds.isEmpty()) {
            Log.forest("巡护地图无普通在线动物[patrolId=${patrolConfig.optInt("patrolId", 0)}]，跳过图鉴优先判断")
        }
        return animalIds
    }

    private fun hasMissingAnimalPieces(record: PatrolRecordInfo): Boolean {
        val normalAnimalIds = normalPatrolAnimalIds(record.patrolConfig)
        if (normalAnimalIds.isEmpty()) {
            return false
        }
        return try {
            val response = unwrapResData(JSONObject(AntForestRpcCall.queryAnimalAndPiece(0, record.patrolId)))
            if (!ResChecker.checkRes(TAG, "查询巡护图鉴失败:", response)) {
                Log.forest("巡护图鉴检查失败[${record.reserveName}/${record.patrolId}]: ${response.optString("resultDesc", response.optString("desc"))}")
                return false
            }
            val animalProps = response.optJSONArray("animalProps")
            if (animalProps == null || animalProps.length() == 0) {
                Log.forest("巡护图鉴缺少动物碎片列表[${record.reserveName}/${record.patrolId}]")
                return false
            }

            var matched = false
            for (i in 0 until animalProps.length()) {
                val animalProp = animalProps.optJSONObject(i) ?: continue
                val animal = animalProp.optJSONObject("animal") ?: continue
                val animalId = animal.optInt("id", -1)
                if (!normalAnimalIds.contains(animalId)) {
                    continue
                }
                matched = true
                val pieces = animalProp.optJSONArray("pieces")
                if (pieces == null || pieces.length() == 0) {
                    Log.forest("巡护图鉴动物缺少碎片字段[${record.reserveName}/${animal.optString("name", animalId.toString())}]")
                    continue
                }
                for (pieceIndex in 0 until pieces.length()) {
                    val piece = pieces.optJSONObject(pieceIndex) ?: continue
                    if (piece.optInt("holdsNum", 0) <= 0) {
                        return true
                    }
                }
            }
            if (!matched) {
                Log.forest("巡护图鉴未返回当前保护地普通动物碎片[${record.reserveName}/${record.patrolId}]")
            }
            false
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "hasMissingAnimalPieces err", t)
            false
        }
    }

    private fun selectPatrolTargetRecord(records: JSONArray): PatrolTargetRecord? {
        val sortedRecords = collectPatrolRecordInfo(records)
            .sortedWith(compareBy<PatrolRecordInfo> { it.startDate }.thenBy { it.patrolId })
        if (sortedRecords.isEmpty()) {
            Log.forest("巡护记录为空，保留当前保护地")
            return null
        }

        sortedRecords.firstOrNull { hasUnreachedPatrolNode(it.userPatrol) }?.let {
            return PatrolTargetRecord(it.patrolId, it.reserveName, "旧到新未走完")
        }

        for (record in sortedRecords) {
            if (hasMissingAnimalPieces(record)) {
                return PatrolTargetRecord(record.patrolId, record.reserveName, "普通动物碎片未齐")
            }
        }

        val latestRecord = sortedRecords.maxWithOrNull(compareBy<PatrolRecordInfo> { it.startDate }.thenBy { it.patrolId })
            ?: return null
        return PatrolTargetRecord(latestRecord.patrolId, latestRecord.reserveName, "全部完成后最新循环")
    }

    private fun switchUserPatrolIfNeeded(currentPatrolId: Int, recordPayload: JSONObject): Boolean {
        if (!recordPayload.optBoolean("canSwitch", false)) {
            return false
        }
        val records = recordPayload.optJSONArray("records")
        if (records == null || records.length() == 0) {
            Log.forest("巡护记录缺少records，保留当前保护地")
            return false
        }
        val target = selectPatrolTargetRecord(records) ?: return false
        if (target.patrolId <= 0 || target.patrolId == currentPatrolId) {
            Log.forest("巡护⚖️-当前地图保持[${target.reserveName}/${target.patrolId}](${target.reason})")
            return false
        }
        val switchResponse = unwrapResData(JSONObject(AntForestRpcCall.switchUserPatrol(target.patrolId.toString())))
        return if (ResChecker.checkRes(TAG, "切换巡护地图失败:", switchResponse)) {
            Log.forest("巡护⚖️-切换地图至[${target.reserveName}/${target.patrolId}](${target.reason})")
            true
        } else {
            Log.forest("巡护地图切换失败[${target.reserveName}/${target.patrolId}]: ${switchResponse.optString("resultDesc", switchResponse.optString("desc"))}")
            false
        }
    }

    /**
     * 查询并管理用户巡护任务
     */
    internal fun queryUserPatrol() {
        val patrolChanceLimitFlag = StatusFlags.FLAG_ANTFOREST_PATROL_CHANCE_EXCHANGE_LIMIT
        var patrolChanceReplenishTried = false
        fun replenishPatrolChanceIfNeeded(reason: String): Boolean {
            if (patrolChanceReplenishTried) {
                return false
            }
            patrolChanceReplenishTried = true
            val replenishResult = ExchangeReplenisher.replenish(
                need = ExchangeEffectNeed.FOREST_PATROL_CHANCE,
                reason = reason,
                maxCount = 1
            ) {
                AntForestRpcCall.queryUserPatrol()
            }
            return if (replenishResult == ExchangeReplenishResult.EXCHANGED) {
                Log.forest("保护地巡护机会已触发缺货补兑，重新查询巡护状态")
                true
            } else {
                Log.forest("保护地巡护机会补兑未完成#$replenishResult")
                false
            }
        }
        try {
            do {
                // 查询当前巡护任务
                var jo = unwrapResData(JSONObject(AntForestRpcCall.queryUserPatrol()))
                // 如果查询成功
                if (ResChecker.checkRes(TAG, "查询巡护任务失败:", jo)) {
                    // 查询我的巡护记录
                    val currentPatrolId = jo.optJSONObject("userPatrol")?.optInt("patrolId", 0) ?: 0
                    val recordPayload = unwrapResData(JSONObject(AntForestRpcCall.queryMyPatrolRecord()))
                    if (ResChecker.checkRes(TAG, "查询巡护记录失败:", recordPayload) &&
                        switchUserPatrolIfNeeded(currentPatrolId, recordPayload)
                    ) {
                        jo = unwrapResData(JSONObject(AntForestRpcCall.queryUserPatrol()))
                        if (!ResChecker.checkRes(TAG, "查询巡护任务失败:", jo)) {
                            Log.forest(jo.optString("resultDesc", jo.optString("desc", "查询巡护任务失败")))
                            break
                        }
                    }
                    // 获取用户当前巡护状态信息
                    val userPatrol = jo.optJSONObject("userPatrol")
                    if (userPatrol == null) {
                        Log.forest("巡护任务缺少userPatrol字段，跳过本轮")
                        break
                    }
                    val currentNode = userPatrol.getInt("currentNode")
                    val currentStatus = userPatrol.getString("currentStatus")
                    val patrolId = userPatrol.getInt("patrolId")
                    lastPatrolId = patrolId
                    val chance = userPatrol.getJSONObject("chance")
                    val leftChance = chance.getInt("leftChance")
                    val leftStep = chance.getInt("leftStep")
                    val usedStep = chance.getInt("usedStep")
                    val chanceFromStepUpperLimit = jo.optInt("chanceFromStepUpperLimit", 5)
                    val chanceStepUnit = jo.optInt("chanceStepUnit", 2000)
                    val maxExchangeStep = if (chanceFromStepUpperLimit > 0 && chanceStepUnit > 0) {
                        chanceFromStepUpperLimit * chanceStepUnit
                    } else {
                        10000
                    }
                    if (usedStep >= maxExchangeStep && !Status.hasFlagToday(patrolChanceLimitFlag)) {
                        Status.setFlagToday(patrolChanceLimitFlag)
                        Log.forest("今日保护地巡护兑换次数已达上限(${chanceFromStepUpperLimit}次)，后续不再重复尝试")
                    }
                    if ("STANDING" == currentStatus) { // 当前巡护状态为"STANDING"
                        if (leftChance > 0) { // 如果还有剩余的巡护次数，则开始巡护
                            jo = JSONObject(AntForestRpcCall.patrolGo(currentNode, patrolId))
                            patrolKeepGoing(jo.toString(), currentNode, patrolId) // 继续巡护
                            continue  // 跳过当前循环
                        } else if (!Status.hasFlagToday(patrolChanceLimitFlag) &&
                            leftStep >= chanceStepUnit &&
                            usedStep < maxExchangeStep
                        ) { // 如果没有剩余的巡护次数但步数足够，则兑换巡护次数
                            jo = JSONObject(AntForestRpcCall.exchangePatrolChance(leftStep))
                            if (ResChecker.checkRes(TAG, "兑换巡护次数失败:", jo)) { // 兑换成功，增加巡护次数
                                val addedChance = jo.optInt("addedChance", 0)
                                Log.forest("步数兑换⚖️[巡护次数*$addedChance]")
                                val consumedStep = if (addedChance > 0) addedChance * chanceStepUnit else chanceStepUnit
                                if (usedStep + consumedStep >= maxExchangeStep) {
                                    Status.setFlagToday(patrolChanceLimitFlag)
                                    Log.forest("今日保护地巡护兑换次数已达上限(${chanceFromStepUpperLimit}次)，后续不再重复尝试")
                                }
                                continue  // 跳过当前循环
                            } else {
                                val resultDesc = jo.optString("resultDesc")
                                if (resultDesc.contains("上限") || resultDesc.contains("已达") || resultDesc.contains("最多")) {
                                    Status.setFlagToday(patrolChanceLimitFlag)
                                    Log.forest("今日保护地巡护兑换次数已达上限(${chanceFromStepUpperLimit}次)，后续不再重复尝试")
                                } else {
                                    Log.forest(resultDesc)
                                }
                            }
                        }
                    } else if ("GOING" == currentStatus) {
                        patrolKeepGoing(null, currentNode, patrolId)
                    }
                    if ("STANDING" == currentStatus && leftChance <= 0 &&
                        replenishPatrolChanceIfNeeded("森林保护地巡护机会不足")
                    ) {
                        continue
                    }
                } else {
                    Log.forest(jo.optString("resultDesc", jo.optString("desc", "查询巡护任务失败")))
                }
                break // 完成一次巡护任务后退出循环
            } while (!Thread.currentThread().isInterrupted)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryUserPatrol err", t) // 打印异常堆栈
        }
    }

    /**
     * 持续巡护森林，直到巡护状态不再是"进行中"
     *
     * @param s         巡护请求的响应字符串，若为null将重新请求
     * @param nodeIndex 当前节点索引
     * @param patrolId  巡护任务ID
     */
    private fun patrolKeepGoing(s: String?, nodeIndex: Int, patrolId: Int) {
        var s = s
        try {
            do {
                if (s == null) {
                    s = AntForestRpcCall.patrolKeepGoing(nodeIndex, patrolId, "image")
                }
                val jo: JSONObject?
                try {
                    jo = JSONObject(s)
                } catch (e: JSONException) {
                    Log.printStackTrace(TAG, "JSON解析错误: " + e.message, e)
                    return  // 解析失败，退出循环
                }
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.forest(jo.getString("resultDesc"))
                    break
                }
                val events = jo.optJSONArray("events")
                if (events == null || events.length() == 0) {
                    return  // 无事件，退出循环
                }
                val event = events.getJSONObject(0)
                val userPatrol = jo.getJSONObject("userPatrol")
                val currentNode = userPatrol.getInt("currentNode")
                // 获取奖励信息，并处理动物碎片奖励
                val rewardInfo = event.optJSONObject("rewardInfo")
                if (rewardInfo != null) {
                    val animalProp = rewardInfo.optJSONObject("animalProp")
                    if (animalProp != null) {
                        val animal = animalProp.optJSONObject("animal")
                        if (animal != null) {
                            Log.forest("巡护森林🏇🏻[" + animal.getString("name") + "碎片]")
                        }
                    }
                }
                // 如果巡护状态不是"进行中"，则退出循环
                if ("GOING" != jo.getString("currentStatus")) {
                    return
                }
                // 请求继续巡护
                val materialInfo = event.getJSONObject("materialInfo")
                val materialType = materialInfo.optString("materialType", "image")
                s = AntForestRpcCall.patrolKeepGoing(currentNode, patrolId, materialType)
            } while (!Thread.currentThread().isInterrupted)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "patrolKeepGoing err", t)
        }
    }

    /**
     * 查询并派遣伙伴
     */
    internal fun queryAndConsumeAnimal() {
        try {
            // 查询动物属性列表
            var jo = JSONObject(AntForestRpcCall.queryAnimalPropList())
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.forest(jo.getString("resultDesc"))
                return
            }
            // 获取所有动物属性并选择可以派遣的伙伴
            val animalProps = jo.getJSONArray("animalProps")
            val bestAnimalProp = selectBestAnimalProp(animalProps)
            // 派遣伙伴
            consumeAnimalProp(bestAnimalProp)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryAnimalPropList err", t)
        }
    }

    private fun selectBestAnimalProp(animalProps: JSONArray): JSONObject? {
        var bestAnimalProp: JSONObject? = null
        var bestHoldsNum = 0
        var bestEstimatedEnergy = 0
        for (i in 0 until animalProps.length()) {
            val animalProp = animalProps.optJSONObject(i) ?: continue
            val holdsNum = getAnimalPropHoldsNum(animalProp)
            if (holdsNum <= 0) {
                continue
            }
            val estimatedEnergy = estimateAnimalPropRobEnergy(animalProp)
            if (bestAnimalProp == null ||
                holdsNum > bestHoldsNum ||
                holdsNum == bestHoldsNum && estimatedEnergy > bestEstimatedEnergy
            ) {
                bestAnimalProp = animalProp
                bestHoldsNum = holdsNum
                bestEstimatedEnergy = estimatedEnergy
            }
        }
        return bestAnimalProp
    }

    private fun getAnimalPropHoldsNum(animalProp: JSONObject): Int {
        return animalProp.optJSONObject("main")?.optInt("holdsNum", 0) ?: 0
    }

    private fun estimateAnimalPropRobEnergy(animalProp: JSONObject): Int {
        val partner = animalProp.optJSONObject("partner")
        val main = animalProp.optJSONObject("main")
        return maxOf(
            extractAnimalRobAbilityEnergy(partner),
            extractAnimalRobAbilityEnergy(main),
            extractAnimalRobAbilityEnergy(parseAnimalPropExtInfo(partner)),
            extractAnimalRobAbilityEnergy(parseAnimalPropExtInfo(main))
        )
    }

    private fun extractAnimalRobAbilityEnergy(container: JSONObject?): Int {
        if (container == null) {
            return 0
        }
        val robAbility = container.optJSONObject("robAbility")
            ?: container.optJSONObject("animal")?.optJSONObject("robAbility")
            ?: return 0
        return maxOf(
            robAbility.optInt("robEnergyInDaily", 0),
            robAbility.optInt("robEnergyInRound", 0)
        )
    }

    private fun parseAnimalPropExtInfo(container: JSONObject?): JSONObject? {
        if (container == null || !container.has("extInfo")) {
            return null
        }
        val extInfo = container.opt("extInfo")
        return when (extInfo) {
            is JSONObject -> extInfo
            is String -> try {
                if (extInfo.trim().startsWith("{")) JSONObject(extInfo) else null
            } catch (_: JSONException) {
                null
            }

            else -> null
        }
    }

    /**
     * 派遣伙伴进行巡护
     *
     * @param animalProp 选择的动物属性
     */
    private fun consumeAnimalProp(animalProp: JSONObject?) {
        if (animalProp == null) return  // 如果没有可派遣的伙伴，则返回

        try {
            // 获取伙伴的属性信息
            val propGroup = animalProp.getJSONObject("main").getString("propGroup")
            val propType = animalProp.getJSONObject("main").getString("propType")
            val name = animalProp.getJSONObject("partner").getString("name")
            val holdsNum = getAnimalPropHoldsNum(animalProp)
            val estimatedEnergy = estimateAnimalPropRobEnergy(animalProp)
            // 调用API进行伙伴派遣
            val jo = JSONObject(
                AntForestRpcCall.consumeProp(
                    propGroup,
                    "",
                    propType,
                    false,
                    AntForestRpcCall.patrolPropConsumeContext(propGroup)
                )
            )
            if (ResChecker.checkRes(TAG, "巡护派遣失败:", jo)) {
                Log.forest("巡护派遣🐆[$name]#持有${holdsNum}个，预计能量${estimatedEnergy}g")
            } else {
                Log.forest(jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "consumeAnimalProp err", t)
        }
    }

    /**
     * 查询动物及碎片信息，并尝试合成可合成的动物碎片。
     */
    internal fun queryAnimalAndPiece() {
        try {
            // 调用远程接口查询动物及碎片信息
            val response = JSONObject(AntForestRpcCall.queryAnimalAndPiece(0, lastPatrolId))
            val resultCode = response.optString("resultCode")
            // 检查接口调用是否成功
            if ("SUCCESS" != resultCode) {
                Log.forest("查询失败: " + response.optString("resultDesc"))
                return
            }
            // 获取动物属性列表
            val animalProps = response.optJSONArray("animalProps")
            if (animalProps == null || animalProps.length() == 0) {
                Log.forest("动物属性列表为空")
                return
            }
            // 遍历动物属性
            for (i in 0..<animalProps.length()) {
                val animalObject = animalProps.optJSONObject(i) ?: continue
                val pieces = animalObject.optJSONArray("pieces")
                if (pieces == null || pieces.length() == 0) {
                    Log.forest("动物碎片列表为空")
                    continue
                }
                val animalId =
                    animalObject.optJSONObject("animal")?.optInt("id", -1) ?: -1
                if (animalId == -1) {
                    Log.forest("动物ID缺失")
                    continue
                }
                // 检查碎片是否满足合成条件
                if (canCombinePieces(pieces)) {
                    combineAnimalPiece(animalId)
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "queryAnimalAndPiece err", e)
        }
    }

    /**
     * 检查碎片是否满足合成条件。
     *
     * @param pieces 动物碎片数组
     * @return 如果所有碎片满足合成条件，返回 true；否则返回 false
     */
    private fun canCombinePieces(pieces: JSONArray): Boolean {
        for (j in 0..<pieces.length()) {
            val pieceObject = pieces.optJSONObject(j)
            if (pieceObject == null || pieceObject.optInt("holdsNum", 0) <= 0) {
                return false
            }
        }
        return true
    }

    /**
     * 合成动物碎片。
     *
     * @param animalId 动物ID
     */
    private fun combineAnimalPiece(animalId: Int) {
        var animalId = animalId
        try {
            while (!Thread.currentThread().isInterrupted) {
                // 查询动物及碎片信息
                val response = JSONObject(AntForestRpcCall.queryAnimalAndPiece(animalId))
                var resultCode = response.optString("resultCode")
                if ("SUCCESS" != resultCode) {
                    Log.forest("查询失败: " + response.optString("resultDesc"))
                    break
                }
                val animalProps = response.optJSONArray("animalProps")
                if (animalProps == null || animalProps.length() == 0) {
                    Log.forest("动物属性数据为空")
                    break
                }
                // 获取第一个动物的属性
                val animalProp = animalProps.getJSONObject(0)
                val animal: JSONObject = checkNotNull(animalProp.optJSONObject("animal"))
                val id = animal.optInt("id", -1)
                val name = animal.optString("name", "未知动物")
                // 获取碎片信息
                val pieces = animalProp.optJSONArray("pieces")
                if (pieces == null || pieces.length() == 0) {
                    Log.forest("碎片数据为空")
                    break
                }
                var canCombineAnimalPiece = true
                val piecePropIds = JSONArray()
                // 检查所有碎片是否可用
                for (j in 0..<pieces.length()) {
                    val piece = pieces.optJSONObject(j)
                    if (piece == null || piece.optInt("holdsNum", 0) <= 0) {
                        canCombineAnimalPiece = false
                        Log.forest("碎片不足，无法合成动物")
                        break
                    }
                    // 添加第一个道具ID
                    piece.optJSONArray("propIdList")?.optString(0, "")?.let { propId ->
                        piecePropIds.put(propId)
                    }
                }
                // 如果所有碎片可用，则尝试合成
                if (canCombineAnimalPiece) {
                    val combineResponse =
                        JSONObject(AntForestRpcCall.combineAnimalPiece(id, piecePropIds.toString()))
                    resultCode = combineResponse.optString("resultCode")
                    if ("SUCCESS" == resultCode) {
                        Log.forest("成功合成动物💡[$name]")
                        animalId = id
                        GlobalThreadPools.sleepCompat(100) // 等待一段时间再查询
                        continue
                    } else {
                        Log.forest("合成失败: " + combineResponse.optString("resultDesc"))
                    }
                }
                break // 如果不能合成或合成失败，跳出循环
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "combineAnimalPiece err", e)
        }
    }

    /**
     * 获取背包信息
     */
    private fun queryPropList(): JSONObject? {
        return queryPropList(false)
    }

    internal fun invalidatePropBagCache() {
        cachedBagObject = null
        lastQueryPropListTime = 0L
        roundPropCheckState = null
        lastUsePropCheckTime = 0L
    }

    @Synchronized
    private fun queryPropList(forceRefresh: Boolean): JSONObject? {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedBagObject != null && now - lastQueryPropListTime < 5000) {
            return cachedBagObject
        }
        try {
            Log.forest("刷新背包...")
            val response = AntForestRpcCall.queryPropList(false)
            // 检查响应是否为空，避免解析空字符串导致异常
            if (response.isNullOrBlank()) {
                Log.forest("刷新背包失败: 响应为空")
                return null
            }
            val bagObject = JSONObject(response)
            if (bagObject.optBoolean("success")) {
                cachedBagObject = bagObject
                lastQueryPropListTime = now
                return bagObject
            } else {
                Log.forest("刷新背包失败: " + bagObject.optString("resultDesc"))
            }
        } catch (th: Throwable) {
            handleException("queryPropList", th)
        }
        return null
    }

    private fun getPropType(propObject: JSONObject?): String {
        if (propObject == null) {
            return ""
        }
        val configType = propObject.optJSONObject("propConfigVO")?.optString("propType")
        return configType?.takeIf { it.isNotBlank() } ?: propObject.optString("propType")
    }

    private fun getPropName(propObject: JSONObject?): String {
        if (propObject == null) {
            return ""
        }
        val configName = propObject.optJSONObject("propConfigVO")?.optString("propName")
        return configName?.takeIf { it.isNotBlank() } ?: getPropType(propObject)
    }

    private fun parseRobMultiplierFactor(
        propType: String?,
        propName: String? = null,
        detail: JSONObject? = null,
        desc: String? = null
    ): Double {
        detail?.optString("factor")
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?.let { return it }

        val candidates = listOf(propType, propName, desc).filterNot { it.isNullOrBlank() }
        val patterns = arrayOf(
            Regex("""ROB_EXPAND_CARD_(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+(?:\.\d+)?)\s*倍""")
        )
        for (text in candidates) {
            for (pattern in patterns) {
                val factor = pattern.find(text.orEmpty())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()
                if (factor != null && factor > 0.0) {
                    return factor
                }
            }
        }
        return 0.0
    }

    private fun getRobMultiplierFactor(propObject: JSONObject?): Double {
        val propConfig = propObject?.optJSONObject("propConfigVO")
        return parseRobMultiplierFactor(
            getPropType(propObject),
            getPropName(propObject),
            propConfig?.optJSONObject("detail"),
            propConfig?.optString("desc")
        )
    }

    private fun getRobMultiplierDurationMs(propObject: JSONObject?): Long {
        val durationSeconds = propObject?.optJSONObject("propConfigVO")?.optLong("durationTime", 0L) ?: 0L
        return if (durationSeconds > 0L) durationSeconds * 1000L else 0L
    }

    private fun isRobMultiplierProp(propType: String): Boolean {
        return propType.contains("ROB_EXPAND", ignoreCase = true)
    }

    private fun isLimitedRobMultiplierProp(propType: String): Boolean {
        return propType.contains("LIMIT_TIME", ignoreCase = true) ||
                propType.contains("DAY", ignoreCase = true)
    }

    private fun isSameRobMultiplierFactor(a: Double, b: Double): Boolean {
        return abs(a - b) < ROB_MULTIPLIER_FACTOR_EPS
    }

    private fun formatRobMultiplierFactor(factor: Double): String {
        if (factor <= 0.0) {
            return "未知"
        }
        return String.format(Locale.US, "%.2f", factor).trimEnd('0').trimEnd('.')
    }

    private fun currentRobMultiplierState(now: Long = System.currentTimeMillis()): RobMultiplierCardState? {
        if (robMultiplierCardEndTime <= now) {
            return null
        }
        return RobMultiplierCardState(
            robMultiplierCardPropName.ifBlank { "生效中N倍卡" },
            robMultiplierCardPropType,
            robMultiplierCardFactor,
            robMultiplierCardEndTime
        )
    }

    private fun hasPropStock(propObject: JSONObject?): Boolean {
        if (propObject == null) {
            return false
        }
        val holdsNum = propObject.optInt("holdsNum", 0)
        val propIdCount = propObject.optJSONArray("propIdList")?.length() ?: 0
        return holdsNum > 0 && propIdCount > 0
    }

    private fun collectAvailablePropsByGroup(bagObject: JSONObject?, propGroup: String): MutableList<JSONObject> {
        val props: MutableList<JSONObject> = ArrayList()
        val forestPropVOList = bagObject?.optJSONArray("forestPropVOList") ?: return props
        for (i in 0..<forestPropVOList.length()) {
            val prop = forestPropVOList.optJSONObject(i) ?: continue
            if (prop.optString("propGroup") == propGroup && hasPropStock(prop)) {
                props.add(prop)
            }
        }
        return props
    }

    private fun selectPreferredBoostProp(bagObject: JSONObject?): JSONObject? {
        val boostProps = collectAvailablePropsByGroup(bagObject, "boost")
        if (boostProps.isEmpty()) {
            return null
        }
        Collections.sort(
            boostProps,
            Comparator { p1: JSONObject?, p2: JSONObject? ->
                val typePriority1 = when (getPropType(p1)) {
                    "LIMIT_TIME_ENERGY_BUBBLE_BOOST" -> 0
                    "BUBBLE_BOOST" -> 1
                    else -> 2
                }
                val typePriority2 = when (getPropType(p2)) {
                    "LIMIT_TIME_ENERGY_BUBBLE_BOOST" -> 0
                    "BUBBLE_BOOST" -> 1
                    else -> 2
                }
                if (typePriority1 != typePriority2) {
                    typePriority1.compareTo(typePriority2)
                } else {
                    p1!!.optLong("recentExpireTime", Long.MAX_VALUE)
                        .compareTo(p2!!.optLong("recentExpireTime", Long.MAX_VALUE))
                }
            }
        )
        return boostProps.firstOrNull()
    }

    private fun collectRobMultiplierCandidates(bagObject: JSONObject?): MutableList<RobMultiplierCandidate> {
        val candidates: MutableList<RobMultiplierCandidate> = ArrayList()
        val choice = robMultiplierCard?.value ?: ApplyPropType.CLOSE
        for (prop in collectAvailablePropsByGroup(bagObject, "robExpandCard")) {
            val propType = getPropType(prop)
            if (choice == ApplyPropType.ONLY_LIMIT_TIME && !isLimitedRobMultiplierProp(propType)) {
                continue
            }
            val factor = getRobMultiplierFactor(prop)
            if (factor <= 0.0) {
                Log.forest("跳过收好友N倍卡[${getPropName(prop)}]，无法识别倍率，避免误用")
                continue
            }
            candidates.add(
                RobMultiplierCandidate(
                    prop = prop,
                    propName = getPropName(prop),
                    propType = propType,
                    factor = factor,
                    durationMs = getRobMultiplierDurationMs(prop),
                    expireTime = prop.optLong("recentExpireTime", Long.MAX_VALUE)
                )
            )
        }
        return candidates
    }

    private fun sortRobMultiplierCandidates(candidates: MutableList<RobMultiplierCandidate>) {
        Collections.sort(
            candidates,
            Comparator { c1: RobMultiplierCandidate, c2: RobMultiplierCandidate ->
                when {
                    !isSameRobMultiplierFactor(c1.factor, c2.factor) -> c2.factor.compareTo(c1.factor)
                    c1.expireTime != c2.expireTime -> c1.expireTime.compareTo(c2.expireTime)
                    else -> c1.durationMs.compareTo(c2.durationMs)
                }
            }
        )
    }

    private fun selectPreferredRobMultiplierProp(
        bagObject: JSONObject?,
        now: Long = System.currentTimeMillis()
    ): RobMultiplierCandidate? {
        val candidates = collectRobMultiplierCandidates(bagObject)
        if (candidates.isEmpty()) {
            return null
        }
        val active = currentRobMultiplierState(now)
        if (active == null) {
            sortRobMultiplierCandidates(candidates)
            val selected = candidates.firstOrNull()
            if (selected != null) {
                Log.forest("当前无生效收好友N倍卡，选择${formatRobMultiplierFactor(selected.factor)}倍卡[${selected.propName}]")
            }
            return selected
        }

        val activeRemainingMs = active.endTime - now
        if (active.factor <= 0.0) {
            Log.forest("当前收好友N倍卡[${active.propName}]倍率未知，跳过自动替换/延期，避免误用")
            return null
        }

        val higherCandidates = candidates.filter { it.factor > active.factor + ROB_MULTIPLIER_FACTOR_EPS }
            .toMutableList()
        val safeHigherCandidates = higherCandidates.filter { it.durationMs >= activeRemainingMs }
            .toMutableList()
        if (safeHigherCandidates.isNotEmpty()) {
            sortRobMultiplierCandidates(safeHigherCandidates)
            val selected = safeHigherCandidates.first()
            Log.forest(
                "准备用更高倍率${formatRobMultiplierFactor(selected.factor)}倍卡[${selected.propName}]" +
                        "替换当前${formatRobMultiplierFactor(active.factor)}倍卡，候选时长不短于当前剩余时间"
            )
            return selected
        } else if (higherCandidates.isNotEmpty()) {
            sortRobMultiplierCandidates(higherCandidates)
            val bestHigher = higherCandidates.first()
            Log.forest(
                "跳过更高倍率${formatRobMultiplierFactor(bestHigher.factor)}倍卡[${bestHigher.propName}]，" +
                        "候选时长${formatTimeDifference(bestHigher.durationMs)}短于当前剩余${formatTimeDifference(activeRemainingMs)}，避免替换浪费"
            )
        }

        val sameFactorCandidates = candidates.filter {
            isSameRobMultiplierFactor(it.factor, active.factor)
        }.toMutableList()
        if (sameFactorCandidates.isNotEmpty()) {
            if (activeRemainingMs < ROB_MULTIPLIER_PROLONG_THRESHOLD_MS) {
                sortRobMultiplierCandidates(sameFactorCandidates)
                val selected = sameFactorCandidates.first()
                Log.forest(
                    "当前${formatRobMultiplierFactor(active.factor)}倍卡剩余${formatTimeDifference(activeRemainingMs)}，" +
                            "满足少于30天延期条件，选择[${selected.propName}]"
                )
                return selected
            }
            Log.forest(
                "当前${formatRobMultiplierFactor(active.factor)}倍卡剩余${formatTimeDifference(activeRemainingMs)}，" +
                        "不少于30天，跳过同倍率延期"
            )
        }

        if (candidates.any { it.factor + ROB_MULTIPLIER_FACTOR_EPS < active.factor }) {
            Log.forest("背包中仅有低倍率收好友N倍卡或不满足安全替换条件，跳过使用，避免降低当前收益")
        }
        return null
    }

    /**
     * 查找背包道具
     *
     * @param bagObject 背包对象
     * @param propType  道具类型 LIMIT_TIME_ENERGY_SHIELD_TREE,...
     */
    private fun findPropBag(bagObject: JSONObject?, propType: String): JSONObject? {
        if (Objects.isNull(bagObject)) {
            return null
        }
        try {
            val forestPropVOList = bagObject!!.getJSONArray("forestPropVOList")
            for (i in 0..<forestPropVOList.length()) {
                val forestPropVO = forestPropVOList.getJSONObject(i)
                val currentPropType = getPropType(forestPropVO)
                if (propType == currentPropType && hasPropStock(forestPropVO)) {
                    return forestPropVO // 找到后直接返回
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "findPropBag err", e)
        }

        return null // 未找到或出错时返回 null
    }

    /**
     * 返回背包道具信息
     */
    private fun showBag() {
        val bagObject = queryPropList()
        if (Objects.isNull(bagObject)) {
            return
        }
        try {
            val forestPropVOList = bagObject?.optJSONArray("forestPropVOList") ?: return

            val logBuilder = StringBuilder("\n======= 背包道具列表 =======\n")
            val availableProps = mutableListOf<String>()
            for (i in 0..<forestPropVOList.length()) {
                val prop = forestPropVOList.optJSONObject(i) ?: continue

                val propConfig = prop.optJSONObject("propConfigVO") ?: continue

                val propName = propConfig.optString("propName")
                val propType = prop.optString("propType")
                val holdsNum = prop.optInt("holdsNum")
                val expireTime = prop.optLong("recentExpireTime", 0)
                if (holdsNum > 0) {
                    availableProps.add("$propName*$holdsNum")
                }
                logBuilder.append("道具: ").append(propName)
                    .append(" | 数量: ").append(holdsNum)
                    .append(" | 类型: ").append(propType)
                if (expireTime > 0) {
                    val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(expireTime))
                    logBuilder.append(" | 过期时间: ").append(formattedDate)
                }
                logBuilder.append("\n")
            }
            logBuilder.append("==========================")
            val preview = availableProps.take(8).joinToString("，")
            val more = if (availableProps.size > 8) " 等${availableProps.size}类" else ""
            val summary = if (availableProps.isEmpty()) {
                "背包道具: 当前无可用道具"
            } else {
                "背包道具: 可用${availableProps.size}类：$preview$more"
            }
            Log.forest(summary)
            Log.debug(TAG, logBuilder.toString())
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "showBag err", e)
        }
    }

    /**
     * 使用背包道具
     *
     * @param propJsonObj 道具对象
     * @param needRefreshHome 是否需要刷新主页（默认true。加速卡等紧接着会查询主页的场景可设为false以优化延迟）
     */
    private fun unwrapResData(response: JSONObject): JSONObject {
        return response.optJSONObject("resData") ?: response
    }

    private fun usePropBag(
        propJsonObj: JSONObject?,
        needRefreshHome: Boolean = true,
        consumeContext: AntForestRpcCall.PropConsumeContext? = null
    ): Boolean {
        if (propJsonObj == null) {
            Log.forest("要使用的道具不存在！")
            return false
        }
        try {
            if (!hasPropStock(propJsonObj)) {
                Log.forest("道具[${getPropName(propJsonObj)}]数量不足，跳过使用")
                return false
            }
            val propId = propJsonObj.getJSONArray("propIdList").getString(0)
            val propConfigVO = propJsonObj.getJSONObject("propConfigVO")
            val propType = propConfigVO.getString("propType")
            val holdsNum = propJsonObj.optInt("holdsNum") // 当前持有数量
            val propName = propConfigVO.getString("propName")
            propEmoji(propName)
            val jo: JSONObject?
            val isRenewable = isRenewableProp(propType)
            Log.forest("道具 $propName (类型: $propType), 是否可续用: $isRenewable, 当前持有数量: $holdsNum"
            )
            val propGroup = AntForestRpcCall.getPropGroup(propType)
            val actualConsumeContext = consumeContext ?: when (propType) {
                "LIMIT_TIME_ENERGY_RAIN_CHANCE" -> AntForestRpcCall.vitalityEnergyRainPropConsumeContext()
                else -> null
            }
            if (isRenewable) {
                // 第一步：发送检查/尝试使用请求 (secondConfirm=false)
                val checkResponseStr = AntForestRpcCall.consumeProp(propGroup, propId, propType, false, actualConsumeContext)
                val checkResponse = JSONObject(checkResponseStr)
                // Log.forest("发送检查请求: " + checkResponse);
                var resData = checkResponse.optJSONObject("resData")
                if (resData == null) {
                    resData = checkResponse
                }
                val status = resData.optString("usePropStatus")
                if (status.startsWith("NEED_CONFIRM") || "REPLACE" == status) {
                    // 情况1: 需要二次确认 (真正地续写)
                    Log.forest(propName + "需要二次确认，发送确认请求...")
                    val confirmResponseStr =
                        AntForestRpcCall.consumeProp(propGroup, propId, propType, true, actualConsumeContext)
                    jo = JSONObject(confirmResponseStr)
                    // 提取道具名称用于日志显示
                    val userPropVO = unwrapResData(jo).optJSONObject("userPropVO")
                    val usedPropName = userPropVO?.optString("propName") ?: propName
                    Log.forest("已使用$usedPropName")

                } else {
                    // 其他所有情况都视为最终结果，通常是失败
                    // Log.forest("道具状态异常或使用失败12:"+ status)
                    jo = checkResponse
                }
            } else {
                // 非续用类道具，直接使用
                val consumeResponse = AntForestRpcCall.consumeProp2(propGroup, propId, propType, actualConsumeContext)
                jo = JSONObject(consumeResponse)
                // 提取道具名称用于日志显示
                val userPropVO = unwrapResData(jo).optJSONObject("userPropVO")
                val usedPropName = userPropVO?.optString("propName") ?: propName
                Log.forest("已使用$usedPropName")
            }

            // 统一结果处理
            val resultData = unwrapResData(jo)
            if (ResChecker.checkRes(TAG, "使用道具失败:", resultData)) {
                invalidatePropBagCache()
                // ⚡ 优化点：根据参数决定是否执行耗时的刷新操作
                if (needRefreshHome) {
                    updateSelfHomePage()
                }
                return true
            } else {
                val errorData = resultData
                val resultCode = errorData.optString("resultCode")
                    .ifBlank { jo.optString("resultCode") }
                val resultDesc = errorData.optString("resultDesc")
                    .ifBlank { errorData.optString("memo") }
                    .ifBlank { jo.optString("resultDesc") }
                    .ifBlank { jo.optString("memo") }
                    .ifBlank { "未知错误" }
                if (handleAlreadyActivePropUseFailure(propType, propName, resultCode, resultDesc, needRefreshHome)) {
                    invalidatePropBagCache()
                    return true
                }
                Log.forest("使用道具失败: $resultDesc")
                Toast.show(resultDesc)
                return false
            }
        } catch (th: Throwable) {
            Log.printStackTrace(TAG, "usePropBag err", th)
            return false
        }
    }

    private fun handleAlreadyActivePropUseFailure(
        propType: String,
        propName: String,
        resultCode: String,
        resultDesc: String,
        needRefreshHome: Boolean
    ): Boolean {
        val supportsActiveState = isRenewableProp(propType) || propType.contains("EXPAND_CARD")
        if (!supportsActiveState) {
            return false
        }
        val alreadyActive = when {
            resultCode.contains("IN_USE") -> true
            resultDesc.contains("已经在用") -> true
            resultDesc.contains("已经在使用") -> true
            resultDesc.contains("当前已生效") -> true
            resultDesc.contains("已在生效") -> true
            else -> false
        }
        if (!alreadyActive) {
            return false
        }
        val now = System.currentTimeMillis()
        when {
            propType.contains("DOUBLE_CLICK") -> {
                doubleEndTime = maxOf(doubleEndTime, now + 5 * TimeFormatter.ONE_MINUTE_MS)
            }

            propType.contains("STEALTH") -> {
                stealthEndTime = maxOf(stealthEndTime, now + TimeFormatter.ONE_DAY_MS)
            }

            propType.contains("SHIELD") -> {
                shieldEndTime = maxOf(shieldEndTime, now + TimeFormatter.ONE_DAY_MS)
            }

            propType.contains("BOMB_CARD") -> {
                energyBombCardEndTime = maxOf(energyBombCardEndTime, now + TimeFormatter.ONE_DAY_MS)
            }

            propType.contains("EXPAND_CARD") -> {
                robMultiplierCardEndTime = maxOf(robMultiplierCardEndTime, now + 5 * TimeFormatter.ONE_MINUTE_MS)
            }
        }
        Log.forest("道具[$propName]已在生效中，跳过重复使用并同步状态")
        if (needRefreshHome) {
            runCatching { updateSelfHomePage() }
                .onFailure { Log.printStackTrace(TAG, "refresh prop state after in-use", it) }
        }
        return true
    }

    /**
     * 判断是否是可续用类道具
     */
    private fun isRenewableProp(propType: String): Boolean {
        return propType.contains("SHIELD") // 保护罩
                || propType.contains("BOMB_CARD") // 炸弹卡
                || propType.contains("DOUBLE_CLICK") // 双击卡
                || isRobMultiplierProp(propType) // 收好友N倍卡，支持延期/替换二次确认
    }

    /**
     * 使用双击卡道具
     * 功能：在指定时间内，使好友的一个能量球可以收取两次
     *
     * @param bagObject 背包的JSON对象
     */
    private fun useDoubleCard(bagObject: JSONObject, allowVitalityExchangeFallback: Boolean = true) {
        try {
            if (doubleEndTime > System.currentTimeMillis()) {
                Log.forest("双击卡已生效，剩余${formatTimeDifference(doubleEndTime - System.currentTimeMillis())}，跳过重复使用")
                return
            }
            // 前置检查1: 检查今日使用次数是否已达上限
            if (!Status.canDoubleToday()) {
                Log.forest("双击卡使用条件检查: 今日次数已达上限")
                return
            }
            // 前置检查2: 校验背包数据是否有效
            if (!bagObject.optBoolean("success")) {
                Log.forest("背包数据异常，无法使用双击卡$bagObject")
                return
            }

            val forestPropVOList = bagObject.optJSONArray("forestPropVOList") ?: return

            // 步骤1: 根据用户UI设置，筛选出需要使用的双击卡
            val doubleClickProps: MutableList<JSONObject> = ArrayList()
            val choice = doubleCard!!.value
            for (i in 0..<forestPropVOList.length()) {
                val prop = forestPropVOList.optJSONObject(i)
                if (prop != null && "doubleClick" == prop.optString("propGroup") && hasPropStock(prop)) {
                    if (choice == ApplyPropType.ALL) {
                        // 设置为"所有道具": 添加所有双击卡
                        doubleClickProps.add(prop)
                    } else if (choice == ApplyPropType.ONLY_LIMIT_TIME) {
                        // 设置为"限时道具": 只添加用于续期的卡 (名字含LIMIT_TIME或DAYS)
                        val propType = prop.optString("propType")
                        if (propType.contains("LIMIT_TIME") || propType.contains("DAYS")) {
                            doubleClickProps.add(prop)
                        }
                    }
                }
            }
            if (doubleClickProps.isEmpty()) {
                if (allowVitalityExchangeFallback) {
                    val refreshedBag = replenishSelectedRewardsForMissingProp(
                        "双击卡",
                        doubleCardConstant?.value == true,
                        ExchangeEffectNeed.FOREST_DOUBLE_CLICK
                    )
                    if (refreshedBag != null) {
                        useDoubleCard(refreshedBag, false)
                        return
                    }
                }
                Log.forest("根据设置，背包中没有需要使用的双击卡")
                return
            }

            // 步骤2: 按过期时间升序排序，，避免浪费
            Collections.sort(
                doubleClickProps,
                Comparator { p1: JSONObject?, p2: JSONObject? ->
                    val expireTime1 = p1!!.optLong("recentExpireTime", Long.MAX_VALUE)
                    val expireTime2 = p2!!.optLong("recentExpireTime", Long.MAX_VALUE)
                    expireTime1.compareTo(expireTime2)
                })

            Log.forest("扫描到" + doubleClickProps.size + "种双击卡，将按过期顺序尝试使用...")

            // 步骤3: 遍历筛选并排序后的列表，逐个尝试使用
            val normalDoubleDecision = evaluateTriggerDecision(doubleCardTime, StatusFlags.FLAG_ANTFOREST_DOUBLE_CARD_TRIGGER_INDEX)
            var loggedNormalDoubleSkip = false
            var normalDoubleTriggerConsumed = false
            var success = false
            for (propObj in doubleClickProps) {
                val propType = propObj.optString("propType")
                val propName =
                    propObj.optJSONObject("propConfigVO")?.optString("propName") ?: ""

                // 特定条件检查1: 如果是普通的5分钟卡，需要检查是否在指定时间段内
                if ("ENERGY_DOUBLE_CLICK" == propType) {
                    if (normalDoubleDecision?.allowNow != true) {
                        if (!loggedNormalDoubleSkip) {
                            logTriggerWaiting("双击卡", normalDoubleDecision)
                            loggedNormalDoubleSkip = true
                        }
                        continue
                    }
                    if (!normalDoubleTriggerConsumed) {
                        consumeTriggerSlot(StatusFlags.FLAG_ANTFOREST_DOUBLE_CARD_TRIGGER_INDEX, normalDoubleDecision.matchedSlotIndex)
                        normalDoubleTriggerConsumed = true
                    }
                }

                if ("LIMIT_TIME_ENERGY_DOUBLE_CLICK" == propType && choice == ApplyPropType.ONLY_LIMIT_TIME) {
                    val expireTime = propObj.optLong("recentExpireTime", 0)
                    // 修改：24 改为 48 小时，日志信息同步更新
                    if (expireTime > 0 && (expireTime - System.currentTimeMillis() > 2 * 24 * 60 * 60 * 1000L)) {
                        Log.forest("跳过[$propName]，该卡有效期剩余超过2天 (仅限时模式)")
                        continue  // 跳过，尝试下一张
                    }
                }

                // 尝试使用道具
                Log.forest("尝试使用卡: $propName")
                if (usePropBag(propObj)) {
                    // 使用成功，更新状态并结束循环
                    doubleEndTime = System.currentTimeMillis() + 5 * TimeFormatter.ONE_MINUTE_MS
                    Status.doubleToday()
                    success = true
                    break
                }
            }

            if (!success) {
                Log.forest("所有可用的双击卡均不满足使用条件")
            }
        } catch (th: Throwable) {
            handleException("useDoubleCard", th)
        }
    }

    /**
     * 使用隐身卡道具
     * 功能：隐藏收取行为，避免被好友发现偷取能量
     *
     * @param bagObject 背包的JSON对象
     */
    private fun useStealthCard(bagObject: JSONObject?) {
        val config = PropConfig(
            "隐身卡",
            arrayOf<String>("LIMIT_TIME_STEALTH_CARD", "STEALTH_CARD"),
            null,  // 无特殊条件
            { replenishSelectedRewardsForMissingProp("隐身卡", stealthCardConstant?.value == true, ExchangeEffectNeed.FOREST_STEALTH) != null },
            { time: Long? -> stealthEndTime = time!! + TimeFormatter.ONE_DAY_MS }
        )
        usePropTemplate(bagObject, config, stealthCardConstant?.value == true)
    }


    /**
     * 使用保护罩道具
     * 功能：保护自己的能量不被好友偷取，防止能量被收走。
     * 优先使用即将过期的限时保护罩，避免浪费。
     * 支持来源：
     *   - 背包中已有的多种类型保护罩
     *   - 青春特权自动领取（若开启）
     *   - 活力值兑换列表统一处理，不在道具使用路径旁路兑换
     *
     * @param bagObject 当前背包的 JSON 对象（可能为 null）
     */
    private fun useShieldCard(bagObject: JSONObject?) {
        try {
            Log.forest("尝试使用保护罩...")

            // 说明：
            // 过去保护罩 propType 以 LIMIT_TIME_ENERGY_SHIELD / ENERGY_SHIELD 为主，
            // 但现在活动/节日保护罩会出现更多 *_ENERGY_SHIELD（如 DFYC_ENERGY_SHIELD / FMQK_ENERGY_SHIELD 等）。
            // 因此这里不再维护硬编码列表，改为依据 propGroup=shield（优先）或 propType 包含 ENERGY_SHIELD 判断。
            fun collectShieldsFromBag(bag: JSONObject?, out: MutableList<JSONObject>) {
                val forestPropVOList = bag?.optJSONArray("forestPropVOList") ?: return
                for (i in 0..<forestPropVOList.length()) {
                    val prop = forestPropVOList.optJSONObject(i) ?: continue
                    if (!hasPropStock(prop)) {
                        continue
                    }
                    val propGroup = prop.optString("propGroup")
                    val propType = prop.optJSONObject("propConfigVO")?.optString("propType")
                        ?.takeIf { it.isNotBlank() }
                        ?: prop.optString("propType")

                    val isShield = propGroup.equals("shield", ignoreCase = true)
                            || propType.contains("ENERGY_SHIELD", ignoreCase = true)
                    if (isShield) {
                        out.add(prop)
                    }
                }
            }

            // 步骤1: 从背包中收集所有可用的保护罩
            val availableShields: MutableList<JSONObject> = ArrayList()
            collectShieldsFromBag(bagObject, availableShields)

            // 步骤2: 如果没有找到保护罩，尝试获取
            if (availableShields.isEmpty()) {
                // 2.1 若青春特权开启 → 尝试领取并重新查找
                if (youthPrivilege?.value == true) {
                    Log.forest("尝试通过青春特权获取保护罩...")
                    if (youthPrivilege()) {
                        collectShieldsFromBag(queryPropList(true), availableShields)
                    }
                }

                // 2.2 使用统一活力值兑换列表补货，不再维护保护罩专用兑换开关或硬编码 SKU。
                if (availableShields.isEmpty()) {
                    val refreshedBag = replenishSelectedRewardsForMissingProp(
                        "保护罩",
                        shieldCardConstant?.value == true,
                        ExchangeEffectNeed.FOREST_SHIELD
                    )
                    collectShieldsFromBag(refreshedBag, availableShields)
                }
            }

            // 步骤3: 按过期时间升序排序，优先使用即将过期的保护罩
            if (availableShields.isNotEmpty()) {
                Collections.sort(
                    availableShields,
                    Comparator { p1: JSONObject?, p2: JSONObject? ->
                        val expireTime1 = p1!!.optLong("recentExpireTime", Long.MAX_VALUE)
                        val expireTime2 = p2!!.optLong("recentExpireTime", Long.MAX_VALUE)
                        expireTime1.compareTo(expireTime2)
                    })

                // 步骤4: 逐个尝试使用保护罩
                for (shieldObj in availableShields) {
                    val propType = shieldObj.optJSONObject("propConfigVO")?.optString("propType") ?: ""
                    val propName = shieldObj.optJSONObject("propConfigVO")?.optString("propName") ?: propType
                    Log.forest("尝试使用保护罩: $propName")
                    if (usePropBag(shieldObj)) {
                        Log.forest("保护罩使用成功: $propName")
                        return // 使用成功，直接退出
                    } else {
                        Log.forest("保护罩使用失败: $propName，尝试下一个...")
                    }
                }
            }
            // 步骤5: 未使用成功（无论是否找到）
            Log.forest("背包中未找到或无法使用任何可用保护罩")

        } catch (th: Throwable) {
            Log.printStackTrace(TAG, "useShieldCard err", th)
        }
    }

    /**
     * 使用加速卡道具
     * 功能：加速能量球成熟时间，让等待中的能量球提前成熟，并立即收取自己的能量
     */
    private fun useBubbleBoostCard(bag: JSONObject? = queryPropList()): JSONObject? {
        var latestHome: JSONObject? = null
        try {
            // 先检查自己是否有未成熟的能量球
            val selfHomeObj = querySelfHome()
            if (selfHomeObj == null) {
                Log.forest("无法获取自己主页信息，跳过使用加速卡")
                return null
            }
            // 检查是否有未来才会成熟的能量球（bubbleCount > 0且produceTime > serverTime）
            val serverTime = selfHomeObj.optLong("now", System.currentTimeMillis())
            val bubbles = selfHomeObj.optJSONArray("bubbles")
            var hasWaitingBubbles = false
            if (bubbles != null && bubbles.length() > 0) {
                for (i in 0..<bubbles.length()) {
                    val bubble = bubbles.getJSONObject(i)
                    val bubbleCount = bubble.getInt("fullEnergy")
                    if (bubbleCount <= 0) {
                        continue // 跳过能量为0的能量球
                    }
                    val produceTime = bubble.optLong("produceTime", 0L)
                    // 判断是否有未来才会成熟的能量球（produceTime > 0 且 > serverTime）
                    if (produceTime > 0 && produceTime > serverTime) {
                        hasWaitingBubbles = true
                        break
                    }
                }
            }
            if (!hasWaitingBubbles) {
                Log.forest("自己当前没有未来才会成熟的能量球，不使用加速卡")
                return selfHomeObj
            }

            var jo = selectPreferredBoostProp(bag)
            if (jo == null) {
                youthPrivilege()
                jo = selectPreferredBoostProp(queryPropList(true))
            }
            if (jo != null) {
                val propName = getPropName(jo)
                // ⚡ 优化点：传入 needRefreshHome = false，避免重复请求和等待
                // 因为紧接着调用的 collectSelfEnergyImmediately 会再次查询主页，那次查询会包含最新的道具状态和能量球状态
                if (usePropBag(jo, needRefreshHome = false)) {
                    Log.forest("使用加速卡🌪[$propName]")
                    latestHome = collectSelfEnergyImmediately("加速卡")
                }
            } else {
                val refreshedBag = replenishSelectedRewardsForMissingProp(
                    "时光加速器",
                    bubbleBoostCard?.value != ApplyPropType.CLOSE,
                    ExchangeEffectNeed.FOREST_BUBBLE_BOOST
                )
                val exchangedProp = selectPreferredBoostProp(refreshedBag)
                if (exchangedProp != null && usePropBag(exchangedProp, needRefreshHome = false)) {
                    Log.forest("使用补兑加速卡🌪[${getPropName(exchangedProp)}]")
                    latestHome = collectSelfEnergyImmediately("补兑加速卡")
                } else {
                    Log.forest("背包中无可用加速卡")
                    latestHome = selfHomeObj
                }
            }
        } catch (th: Throwable) {
            Log.printStackTrace(TAG, "useBubbleBoostCard err", th)
        }
        return latestHome
    }

    /**
     * 立即收取自己能量（专用方法）
     */
    private fun collectSelfEnergyImmediately(tag: String = "立即收取"): JSONObject? {
        var refreshedHome: JSONObject? = null
        try {
            // querySelfHome 内部会处理 updateSelfHomePage 逻辑，确保道具倒计时等状态同步
            val selfHomeObj = querySelfHome()
            refreshedHome = selfHomeObj
            if (selfHomeObj != null) {
                Log.forest("🎯 $tag：开始收取自己能量...")
                val availableBubbles: MutableList<Long> = ArrayList()
                val serverTime = selfHomeObj.optLong("now", System.currentTimeMillis())

                // ✅ 核心逻辑点：
                // 调用 extractBubbleInfo，该方法内部调用了 shouldCollectSelfBubble(bubbleCount, canBeRobbedAgain)
                // 从而严格执行了【收自己单个能量球方式】和【阈值】的判断逻辑。
                // 只有符合条件的 bubbleId 才会加入 availableBubbles
                extractBubbleInfo(selfHomeObj, serverTime, availableBubbles, UserMap.currentUid)

                if (availableBubbles.isNotEmpty()) {
                    Log.forest("🎯 $tag：找到${availableBubbles.size}个符合阈值条件的可收能量球")
                    // 即使 batchRobEnergy 为 true，collectVivaEnergy 也是对传入的 list 进行操作
                    // 因此【一键收取】、【找能量】、【普通收取】都复用了这个逻辑，保证了统一性
                    collectVivaEnergy(UserMap.currentUid, selfHomeObj, availableBubbles, "加速卡$tag", skipPropCheck = true)
                } else {
                    Log.forest("🎯 $tag：未找到满足条件的能量球 (可能是被阈值过滤或无能量)")
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "collectSelfEnergyImmediately err", e)
        }
        return refreshedHome
    }

    /**
     * 使用收好友N倍卡
     * 功能：增加收好友能量倍率，适配 1.1 / 1.3 / 1.5 / 1.8 等倍率卡
     */
    private fun useRobMultiplierCard(bag: JSONObject? = queryPropList()) {
        try {
            val now = System.currentTimeMillis()
            var selected = selectPreferredRobMultiplierProp(bag, now)
            if (selected == null) {
                val refreshedBag = replenishSelectedRewardsForMissingProp(
                    "收好友N倍卡",
                    robMultiplierCard?.value != ApplyPropType.CLOSE,
                    ExchangeEffectNeed.FOREST_ROB_MULTIPLIER
                )
                selected = selectPreferredRobMultiplierProp(refreshedBag, now)
                if (selected == null) {
                    Log.forest("背包中无可用或无需使用的收好友N倍卡")
                    return
                }
            }
            if (!isLimitedRobMultiplierProp(selected.propType)) {
                val decision = evaluateTriggerDecision(robMultiplierCardTime, StatusFlags.FLAG_ANTFOREST_ROB_MULTIPLIER_CARD_TRIGGER_INDEX)
                if (decision?.allowNow != true) {
                    logTriggerWaiting("收好友N倍卡", decision)
                    return
                }
                consumeTriggerSlot(StatusFlags.FLAG_ANTFOREST_ROB_MULTIPLIER_CARD_TRIGGER_INDEX, decision.matchedSlotIndex)
            }
            if (usePropBag(selected.prop)) {
                val fallbackDuration = selected.durationMs.takeIf { it > 0L } ?: (5 * TimeFormatter.ONE_MINUTE_MS)
                robMultiplierCardEndTime = maxOf(robMultiplierCardEndTime, now + fallbackDuration)
                robMultiplierCardFactor = selected.factor
                robMultiplierCardPropType = selected.propType
                robMultiplierCardPropName = selected.propName
            } else {
                Log.forest("收好友N倍卡[${selected.propName}]使用失败")
            }
        } catch (th: Throwable) {
            Log.printStackTrace(TAG, "useRobMultiplierCard err", th)
        }
    }

    private fun queryVitalityEnergyRainPropList(): JSONObject? {
        return try {
            val response = AntForestRpcCall.queryVitalityEnergyRainPropList()
            if (response.isBlank()) {
                return null
            }
            val jo = JSONObject(response)
            val data = unwrapResData(jo)
            if (ResChecker.checkRes(TAG, "查询活力来源能量雨道具失败:", data)) {
                data
            } else {
                null
            }
        } catch (th: Throwable) {
            Log.printStackTrace(TAG, "queryVitalityEnergyRainPropList err", th)
            null
        }
    }

    private fun queryEnergyRainChanceBag(forceRefresh: Boolean = true): JSONObject? {
        return queryVitalityEnergyRainPropList() ?: queryPropList(forceRefresh)
    }

    internal suspend fun useEnergyRainChanceCard() {
        try {
            suspend fun hasPlayableEnergyRainChance(): Boolean {
                return try {
                    val jo = JSONObject(AntForestRpcCall.queryEnergyRainHome())
                    ResChecker.checkRes(TAG, jo) && jo.optBoolean("canPlayToday", false)
                } catch (t: Throwable) {
                    Log.printStackTrace(TAG, "queryEnergyRainHome err", t)
                    false
                }
            }

            fun getLimitTimeEnergyRainFlag(propJsonObj: JSONObject): String? {
                val propId = propJsonObj.optJSONArray("propIdList")?.optString(0).orEmpty()
                if (propId.isEmpty()) {
                    return null
                }
                return StatusFlags.FLAG_FOREST_RAIN_LIMIT_TIME_CHANCE_PREFIX + propId
            }

            if (hasPlayableEnergyRainChance()) {
                Log.forest("当前已有可执行的能量雨机会，跳过重复激活")
                return
            }

            var usedAny = false
            val propTypes = arrayOf("LIMIT_TIME_ENERGY_RAIN_CHANCE", "ENERGY_RAIN_CHANCE")
            for (propType in propTypes) {
                while (true) {
                    val bag = if (propType == LIMIT_TIME_ENERGY_RAIN_CHANCE_PROP_TYPE) {
                        queryEnergyRainChanceBag(true)
                    } else {
                        queryPropList(true)
                    }
                    val jo = findPropBag(bag, propType) ?: break
                    if (propType == "LIMIT_TIME_ENERGY_RAIN_CHANCE") {
                        val todayFlag = getLimitTimeEnergyRainFlag(jo)
                        if (todayFlag != null && Status.hasFlagToday(todayFlag)) {
                            Log.forest("限时能量雨机会今日已激活，跳过重复使用")
                            break
                        }
                    }
                    if (usePropBag(jo)) {
                        Log.forest("成功使用一个能量雨道具: $propType")
                        usedAny = true
                        if (propType == "LIMIT_TIME_ENERGY_RAIN_CHANCE") {
                            getLimitTimeEnergyRainFlag(jo)?.let { Status.setFlagToday(it) }
                        }
                        delay(2000)
                        if (propType == "LIMIT_TIME_ENERGY_RAIN_CHANCE") {
                            break
                        }
                    } else {
                        break
                    }
                }

                if (propType == "LIMIT_TIME_ENERGY_RAIN_CHANCE" && !hasPlayableEnergyRainChance()) {
                    val replenishResult = ExchangeReplenisher.replenish(
                        need = ExchangeEffectNeed.FOREST_ENERGY_RAIN,
                        reason = "森林能量雨机会不足",
                        maxCount = 1
                    ) {
                        queryPropList(true)
                    }
                    if (replenishResult == ExchangeReplenishResult.EXCHANGED) {
                        delay(1000)
                        val joExchanged = findPropBag(queryEnergyRainChanceBag(true), propType)
                        if (joExchanged != null && usePropBag(joExchanged)) {
                            getLimitTimeEnergyRainFlag(joExchanged)?.let { Status.setFlagToday(it) }
                            usedAny = true
                            delay(1000)
                        }
                    } else if (replenishResult == ExchangeReplenishResult.BUSINESS_LIMIT) {
                        Log.forest("能量雨次卡今日已达兑换上限，跳过继续补兑")
                    }
                }
            }
            if (!usedAny) {
                Log.forest("当前无可激活的能量雨道具")
            }
            Log.forest("所有能量雨卡已处理完毕")
        } catch (e: CancellationException) {
            throw e
        } catch (th: Throwable) {
            Log.printStackTrace(TAG, "useEnergyRainChanceCard err", th)
        }
    }

    /**
     * 使用炸弹卡道具
     * 功能：对有保护罩的好友使用，可以破坏其保护罩并收取能量
     * 注意：与保护罩功能冲突，通常二选一使用
     *
     * @param bagObject 背包的JSON对象
     */
    private fun useEnergyBombCard(bagObject: JSONObject?) {
        try {
            Log.forest("尝试使用炸弹卡...")
            var jo = findPropBag(bagObject, "ENERGY_BOMB_CARD")
            if (jo == null) {
                Log.forest("背包中没有炸弹卡，尝试兑换...")
                val replenishResult = ExchangeReplenisher.replenish(
                    need = ExchangeEffectNeed.FOREST_ENERGY_BOMB,
                    reason = "森林能量炸弹卡缺货",
                    maxCount = 1
                ) {
                    queryPropList(true)
                }
                if (replenishResult == ExchangeReplenishResult.EXCHANGED) {
                    jo = findPropBag(queryPropList(true), "ENERGY_BOMB_CARD")
                } else {
                    Log.forest("能量炸弹卡补兑未完成#$replenishResult")
                }
            }

            if (jo != null) {
                Log.forest("找到炸弹卡，准备使用: $jo")
                if (usePropBag(jo)) {
                    // 使用成功后刷新真实结束时间
                    updateSelfHomePage()
                    Log.forest("能量炸弹卡使用成功，已刷新结束时间")
                }
            } else {
                Log.forest("背包中未找到任何可用炸弹卡。")
                updateSelfHomePage()
            }
        } catch (th: Throwable) {
            Log.printStackTrace(TAG, "useEnergyBombCard err", th)
        }
    }


    private fun currentForestGameCenterRecentAppRecords(): List<AntForestRpcCall.ForestGameCenterRecentAppRecord> {
        return synchronized(forestGameCenterRecentAppRecords) {
            forestGameCenterRecentAppRecords.entries
                .sortedByDescending { it.value }
                .take(60)
                .map { AntForestRpcCall.ForestGameCenterRecentAppRecord(it.key, it.value) }
        }
    }

    private fun rememberForestGameCenterApp(appId: String?) {
        val normalizedAppId = appId?.takeIf { it.isNotBlank() } ?: return
        synchronized(forestGameCenterRecentAppRecords) {
            forestGameCenterRecentAppRecords[normalizedAppId] = System.currentTimeMillis() / 1000
        }
    }

    private fun rememberForestGameCenterAppIds(source: Any?) {
        when (source) {
            is JSONObject -> {
                rememberForestGameCenterApp(source.optString("appId"))
                rememberForestGameCenterApp(source.optString("game_id"))
                val keys = source.keys()
                while (keys.hasNext()) {
                    rememberForestGameCenterAppIds(source.opt(keys.next()))
                }
            }

            is JSONArray -> {
                for (i in 0 until source.length()) {
                    rememberForestGameCenterAppIds(source.opt(i))
                }
            }

            is String -> rememberForestGameCenterApp(extractForestTaskAppId(source))
        }
    }

    suspend fun doforestgame() {
        try {
            var refreshRound = 0
            var hasAdvancedTaskThisRun = false
            while (refreshRound < 3) {
                refreshRound++

                val response = AntForestRpcCall.queryGameList(currentForestGameCenterRecentAppRecords())
                val jo = JSONObject(response)
                rememberForestGameCenterAppIds(jo)
                val querySuccess = ResChecker.checkRes(TAG, jo)
                if (!querySuccess) {
                    val msg = jo.optString("desc").ifBlank { jo.optString("resultDesc").ifBlank { jo.optString("memo") } }
                    Log.error(TAG, "queryGameList 失败: $msg")
                }

                val drawRightsSource = resolveForestDrawRights(jo, querySuccess) ?: return
                val drawRights = drawRightsSource.drawRights
                var optionalPlayTasks = queryForestLeyuanOptionalTasks()
                val rewardedTaskCount = optionalPlayTasks?.let {
                    receiveForestLeyuanOptionalRewards(it, drawRightsSource)
                } ?: 0
                if (rewardedTaskCount > 0) {
                    optionalPlayTasks = queryForestLeyuanOptionalTasks()
                }

                // 换算实际宝箱次数
                val rawCanUseCount = getForestDrawQuotaCanUse(drawRights)
                val canUseCount = rawCanUseCount.coerceAtMost(10)
                val limitCount = getForestDrawQuotaLimit(drawRights)
                val usedCount = getForestDrawUsedCount(drawRights)
                var openedCount = 0

                // 1. 处理待开启奖励 (批量开启)
                if (canUseCount > 0) {
                    Log.forest("正在批量开启 $canUseCount 个宝箱...")
                    if (rawCanUseCount > canUseCount) {
                        Log.forest("森林乐园可开宝箱 $rawCanUseCount 个，本轮按上限只开 $canUseCount 个")
                    }

                    var remain = canUseCount
                    var totalEnergy = 0
                    val otherAwards = mutableListOf<String>()

                    // 保险：服务端常见单次上限为 10，分批开箱避免一次性请求过大
                    while (remain > 0) {
                        val batch = minOf(remain, 10)
                        val drawResStr = AntForestRpcCall.drawGameCenterAward(batch)
                        val drawJo = JSONObject(drawResStr)
                        if (!ResChecker.checkRes(TAG, drawJo)) {
                            Log.error(TAG, "开启宝箱失败: ${drawJo.optString("resultDesc").ifBlank { drawJo.optString("desc") }}")
                            return
                        }

                        val drawResData = drawJo.optJSONObject("resData") ?: drawJo
                        val awardList = findForestDrawAwardList(drawResData)
                        if (awardList != null) {
                            for (i in 0 until awardList.length()) {
                                val award = awardList.getJSONObject(i)
                                val type = award.optString("awardType")
                                val name = award.optString("awardName")
                                val count = award.optInt("awardCount")

                                if (type == "ENERGY") {
                                    totalEnergy += count
                                } else {
                                    otherAwards.add("${name}x${count}")
                                }
                            }
                        }

                        openedCount += batch
                        remain -= batch
                    }

                    val logMsg = StringBuilder("[开宝箱] ")
                    if (totalEnergy > 0) logMsg.append("获得能量: ${totalEnergy}g")
                    if (otherAwards.isNotEmpty()) {
                        if (totalEnergy > 0) logMsg.append(", ")
                        logMsg.append("其他: ${otherAwards.joinToString("/")}")
                    }
                    Log.forest(logMsg.toString())
                } else if (drawRightsSource.sourceName == "FOREST_PLAY_GROUND" && limitCount <= 0 && usedCount <= 0) {
                    Log.forest("FOREST_PLAY_GROUND 已返回新入口权益对象，但当前未返回可用抽奖配额")
                } else {
                    Log.forest("森林乐园当前无待开启宝箱，已用配额 $usedCount/$limitCount")
                }

                // 2. 判断是否需要刷任务
                val remainToTask = (limitCount - usedCount - openedCount).coerceAtLeast(0)
                if (remainToTask <= 0) {
                    if (limitCount > 0) {
                        Log.forest("森林乐园今日宝箱任务已满额")
                    }
                    break
                }

                if (hasAdvancedTaskThisRun) {
                    Log.forest("森林乐园已补任务但额度仍未刷新，本轮停止重复上报")
                    break
                }

                val progressCandidates = buildForestGameCenterProgressCandidates(optionalPlayTasks.orEmpty(), jo)
                rememberForestGameCenterAppIdsFromCandidates(progressCandidates)
                rememberForestGameCenterApp(GameTask.Forest_slxcc.appId)
                hasAdvancedTaskThisRun = true
                val reported = GameTask.Forest_slxcc.report(remainToTask)
                if (!reported) {
                    Log.forest("森林乐园补任务未形成有效进展，本轮停止继续回查")
                    break
                }

                if (refreshRound < 3) {
                    Log.forest("森林乐园任务补齐后重新检查宝箱额度")
                    continue
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "doforestgame 流程异常", t)
        }
    }

    private data class ForestDrawRightsSource(
        val drawRights: JSONObject,
        val sourceName: String
    )

    private data class ForestLeyuanOptionalTask(
        val rawTask: JSONObject,
        val sceneCode: String,
        val taskType: String,
        val status: String,
        val title: String,
        val appId: String?,
        val awardCount: Int
    )

    private data class ForestGameCenterProgressCandidate(
        val key: String,
        val sourceName: String,
        val taskType: String,
        val title: String,
        val status: String,
        val appId: String?
    )

    private fun queryForestLeyuanOptionalTasks(): List<ForestLeyuanOptionalTask>? {
        return try {
            val response = AntForestRpcCall.queryOptionalPlay(currentForestGameCenterRecentAppRecords())
            if (response.isBlank()) {
                Log.error(TAG, "森林乐园 queryOptionalPlay 返回空")
                return null
            }
            val payload = JSONObject(response)
            rememberForestGameCenterAppIds(payload)
            if (!ResChecker.checkRes(TAG, payload)) {
                val msg = payload.optString("desc").ifBlank { payload.optString("resultDesc").ifBlank { payload.optString("memo") } }
                Log.error(TAG, "森林乐园 queryOptionalPlay 失败: $msg")
                return null
            }
            val taskList = payload.optJSONObject("taskTriggerPlayInfo")?.optJSONArray("taskList") ?: return emptyList()
            buildList {
                for (i in 0 until taskList.length()) {
                    val rawTask = taskList.optJSONObject(i) ?: continue
                    val sceneCode = rawTask.optString("sceneCode")
                    val taskType = rawTask.optString("taskType")
                    if (sceneCode.isBlank() || taskType.isBlank()) {
                        continue
                    }
                    val bizInfo = rawTask.optJSONObject("bizInfo") ?: JSONObject()
                    val title = sequenceOf(
                        bizInfo.optString("title"),
                        bizInfo.optString("taskTitle"),
                        bizInfo.optString("desc"),
                        rawTask.optString("taskType")
                    ).firstOrNull { it.isNotBlank() } ?: taskType
                    val targetUrl = bizInfo.optString("targetUrl")
                    val appId = extractForestTaskAppId(targetUrl)
                    val awardCount = rawTask.optInt("awardCount")
                        .takeIf { it > 0 }
                        ?: rawTask.optInt("nextStageAwardCount").takeIf { it > 0 }
                        ?: rawTask.optInt("totalAwardCount")
                    add(
                        ForestLeyuanOptionalTask(
                            rawTask = rawTask,
                            sceneCode = sceneCode,
                            taskType = taskType,
                            status = rawTask.optString("taskStatus"),
                            title = title,
                            appId = appId,
                            awardCount = awardCount
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryForestLeyuanOptionalTasks 异常", t)
            null
        }
    }

    private fun receiveForestLeyuanOptionalRewards(
        tasks: List<ForestLeyuanOptionalTask>,
        drawRightsSource: ForestDrawRightsSource
    ): Int {
        var rewardedCount = 0
        for (task in tasks) {
            if (task.sceneCode != FOREST_LEYUAN_DAILY_TASK_SCENE_CODE ||
                task.status !in FOREST_LEYUAN_REWARD_READY_STATUSES
            ) {
                continue
            }
            if (task.taskType == FOREST_LEYUAN_OPEN_BOX_TASK_TYPE &&
                !hasOpenedEnoughForestGameCenterBoxes(drawRightsSource)
            ) {
                Log.forest("森林乐园任务[${task.title}]已完成，但当前未确认达到${FOREST_LEYUAN_OPEN_BOX_TARGET_COUNT}个开箱数，暂不领奖")
                continue
            }
            val requestTask = JSONObject(task.rawTask.toString()).apply {
                put("source", AntForestRpcCall.OPEN_GREEN_RIGHTS_SOURCE)
            }
            val response = AntForestRpcCall.receiveTaskAwardopengreen(requestTask)
            if (response.isBlank()) {
                Log.error(TAG, "森林乐园任务[${task.title}]领奖失败: receiveTaskAwardopengreen 返回空")
                continue
            }
            val awardResponse = unwrapResData(JSONObject(response))
            when {
                isForestTaskAlreadyHandled(awardResponse) -> {
                    Log.forest("森林乐园任务[${task.title}]已领取")
                }

                isAntiepSuccess(awardResponse) -> {
                    val awardSuffix = task.awardCount.takeIf { it > 0 }?.let { "#${it}" }.orEmpty()
                    Log.forest("森林乐园奖励🎁[${task.title}]$awardSuffix")
                    rewardedCount++
                }

                else -> {
                    handleForestTaskRpcFailure(
                        action = "receiveTaskAwardopengreen",
                        sceneCode = task.sceneCode,
                        taskType = task.taskType,
                        taskTitle = task.title,
                        response = awardResponse,
                        terminalResult = false
                    )
                }
            }
        }
        return rewardedCount
    }

    private fun hasOpenedEnoughForestGameCenterBoxes(drawRightsSource: ForestDrawRightsSource): Boolean {
        return getForestDrawUsedCount(drawRightsSource.drawRights) >= FOREST_LEYUAN_OPEN_BOX_TARGET_COUNT
    }

    private fun buildForestGameCenterProgressCandidates(
        optionalPlayTasks: List<ForestLeyuanOptionalTask>,
        queryGameListResponse: JSONObject
    ): List<ForestGameCenterProgressCandidate> {
        val candidateMap = linkedMapOf<String, ForestGameCenterProgressCandidate>()
        optionalPlayTasks.forEach { task ->
            if (task.sceneCode != FOREST_LEYUAN_DAILY_TASK_SCENE_CODE ||
                task.status !in FOREST_LEYUAN_PROGRESS_PENDING_STATUSES
            ) {
                return@forEach
            }
            val key = "optionalPlay#${task.sceneCode}#${task.taskType}"
            candidateMap.putIfAbsent(
                key,
                ForestGameCenterProgressCandidate(
                    key = key,
                    sourceName = "queryOptionalPlay",
                    taskType = task.taskType,
                    title = task.title,
                    status = task.status,
                    appId = task.appId
                )
            )
        }
        appendForestGameCenterDeliveryCandidates(
            queryGameListResponse.optJSONObject("resData") ?: queryGameListResponse,
            candidateMap
        )
        return candidateMap.values.toList()
    }

    private fun rememberForestGameCenterAppIdsFromCandidates(candidates: List<ForestGameCenterProgressCandidate>) {
        candidates.forEach { candidate ->
            rememberForestGameCenterApp(candidate.appId)
        }
    }

    private fun appendForestGameCenterDeliveryCandidates(
        source: Any?,
        candidateMap: MutableMap<String, ForestGameCenterProgressCandidate>
    ) {
        when (source) {
            is JSONObject -> {
                val appId = source.optString("appId")
                val title = source.optString("title")
                val deliveryBenefitList = source.optJSONArray("deliveryBenefitList")
                if (appId.isNotBlank() && title.isNotBlank() && deliveryBenefitList != null) {
                    for (i in 0 until deliveryBenefitList.length()) {
                        val deliveryBenefit = deliveryBenefitList.optJSONObject(i) ?: continue
                        if (!deliveryBenefit.optString("benefitType").equals("IEP_REQUEST", true)) {
                            continue
                        }
                        val tracer = deliveryBenefit.optString("iepTaskTracer")
                        val taskType = deliveryBenefit.optString("iepTaskId")
                            .ifBlank { extractForestGameCenterTracerField(tracer, "taskType") }
                        if (taskType.isBlank()) {
                            continue
                        }
                        val taskStatus = extractForestGameCenterTracerField(tracer, "taskStatus")
                        val rightTimes = deliveryBenefit.optInt("rightTimes", 0)
                        val rightTimesLimit = deliveryBenefit.optInt("rightTimesLimit", 0)
                        if (taskStatus.equals("RECEIVED", true) ||
                            (rightTimesLimit > 0 && rightTimes >= rightTimesLimit)
                        ) {
                            continue
                        }
                        val key = "queryGameList#$taskType#$appId"
                        candidateMap.putIfAbsent(
                            key,
                            ForestGameCenterProgressCandidate(
                                key = key,
                                sourceName = "queryGameList",
                                taskType = taskType,
                                title = title,
                                status = taskStatus,
                                appId = appId
                            )
                        )
                    }
                }
                val keys = source.keys()
                while (keys.hasNext()) {
                    appendForestGameCenterDeliveryCandidates(source.opt(keys.next()), candidateMap)
                }
            }

            is JSONArray -> {
                for (i in 0 until source.length()) {
                    appendForestGameCenterDeliveryCandidates(source.opt(i), candidateMap)
                }
            }
        }
    }

    private fun extractForestGameCenterTracerField(tracer: String, field: String): String {
        if (tracer.isBlank()) {
            return ""
        }
        return tracer.split('~')
            .firstOrNull { it.startsWith("$field:") }
            ?.substringAfter(':')
            .orEmpty()
    }

    private fun resolveForestDrawRights(queryResponse: JSONObject, querySucceeded: Boolean): ForestDrawRightsSource? {
        if (querySucceeded) {
            val queryResData = queryResponse.optJSONObject("resData") ?: queryResponse
            findForestGameCenterRights(queryResData)?.let {
                return ForestDrawRightsSource(it, "queryGameList")
            }
        }
        return probeForestPlaygroundDrawRights(querySucceeded)
    }

    private fun findForestGameCenterRights(source: Any?): JSONObject? {
        return findForestObjectByKey(source, "gameCenterDrawRights", ::isUsableForestDrawRights)
            ?: findForestObjectByKey(source, "gameDrawAwardActivity", ::isUsableForestDrawRights)
            ?: findForestObjectByKey(source, "gameEntryInfo", ::isUsableForestDrawRights)
    }

    private fun findForestDrawAwardList(source: Any?): JSONArray? {
        return FarmGame.findFirstArrayByKey(source, "gameCenterDrawAwardList")
            ?: FarmGame.findFirstArrayByKey(source, "drawAwardList")
    }

    private fun findForestObjectByKey(
        source: Any?,
        targetKey: String,
        predicate: (JSONObject) -> Boolean
    ): JSONObject? {
        return when (source) {
            is JSONObject -> {
                source.optJSONObject(targetKey)?.takeIf(predicate)?.let { return it }
                val keys = source.keys()
                while (keys.hasNext()) {
                    val child = source.opt(keys.next())
                    findForestObjectByKey(child, targetKey, predicate)?.let { return it }
                }
                null
            }

            is JSONArray -> {
                for (index in 0 until source.length()) {
                    findForestObjectByKey(source.opt(index), targetKey, predicate)?.let { return it }
                }
                null
            }

            else -> null
        }
    }

    private fun isUsableForestDrawRights(candidate: JSONObject): Boolean {
        if (findForestDrawAwardList(candidate) != null) {
            return true
        }
        return listOf(
            "quotaCanUse",
            "quotaLimit",
            "usedQuota",
            "canUseTimes",
            "drawRightsTimes",
            "limit",
            "usedTimes"
        ).any(candidate::has)
    }

    private fun getForestDrawQuotaCanUse(drawRights: JSONObject): Int {
        if (drawRights.has("quotaCanUse")) {
            return normalizeForestDrawQuotaCount(
                drawRights.optInt("quotaCanUse"),
                drawRights.optInt("quotaPerTime")
            )
        }
        return drawRights.optInt("canUseTimes", drawRights.optInt("drawRightsTimes", 0)).coerceAtLeast(0)
    }

    private fun getForestDrawQuotaLimit(drawRights: JSONObject): Int {
        if (drawRights.has("quotaLimit")) {
            return normalizeForestDrawQuotaCount(
                drawRights.optInt("quotaLimit"),
                drawRights.optInt("quotaPerTime")
            )
        }
        return drawRights.optInt("limit", 0).coerceAtLeast(0)
    }

    private fun getForestDrawUsedCount(drawRights: JSONObject): Int {
        if (drawRights.has("usedQuota")) {
            return normalizeForestDrawQuotaCount(
                drawRights.optInt("usedQuota"),
                drawRights.optInt("quotaPerTime")
            )
        }
        return drawRights.optInt("usedTimes", 0).coerceAtLeast(0)
    }

    private fun normalizeForestDrawQuotaCount(rawQuota: Int, quotaPerTime: Int): Int {
        if (rawQuota <= 0) {
            return 0
        }
        if (quotaPerTime <= 1) {
            return rawQuota
        }
        return rawQuota / quotaPerTime
    }

    private fun probeForestPlaygroundDrawRights(querySucceeded: Boolean): ForestDrawRightsSource? {
        val flowHubResponse = runCatching {
            JSONObject(AntForestRpcCall.flowHubEntrance("FOREST_PLAY_GROUND"))
        }.getOrNull()

        if (flowHubResponse == null) {
            Log.forest("森林乐园未找到开宝箱权益，且 FOREST_PLAY_GROUND 探测返回为空")
            return null
        }

        if (!ResChecker.checkRes(TAG, flowHubResponse)) {
            val msg = flowHubResponse.optString("desc")
                .ifBlank { flowHubResponse.optString("resultDesc").ifBlank { flowHubResponse.optString("memo") } }
            Log.forest("森林乐园未找到开宝箱权益，FOREST_PLAY_GROUND 探测失败: $msg")
            return null
        }

        val resData = flowHubResponse.optJSONObject("resData") ?: flowHubResponse
        val drawRights = findForestGameCenterRights(resData)
        if (drawRights != null) {
            Log.forest("森林乐园权益已从 FOREST_PLAY_GROUND 分流返回，按新入口继续处理")
            return ForestDrawRightsSource(drawRights, "FOREST_PLAY_GROUND")
        }

        val unitList = resData.optJSONArray("unitList")
        val unitCount = unitList?.length() ?: 0
        val creativeCount = countForestPlaygroundCreatives(unitList)
        val queryPrefix = if (querySucceeded) "queryGameList 未返回权益" else "queryGameList 调用失败"
        if (creativeCount > 0) {
            Log.forest("$queryPrefix；FOREST_PLAY_GROUND 仅返回 $unitCount 组导航入口/$creativeCount 个 creative，当前无可开奖权益对象")
        } else {
            Log.forest("$queryPrefix；FOREST_PLAY_GROUND 也未返回可用权益或导航入口")
        }
        return null
    }

    private fun countForestPlaygroundCreatives(unitList: JSONArray?): Int {
        if (unitList == null) {
            return 0
        }
        var creativeCount = 0
        for (i in 0 until unitList.length()) {
            creativeCount += unitList.optJSONObject(i)?.optJSONArray("creativeList")?.length() ?: 0
        }
        return creativeCount
    }
    /**
     * 收取状态的枚举类型
     */
    enum class CollectStatus {
        AVAILABLE, WAITING, INSUFFICIENT, ROBBED
    }

    /**
     * 统一获取和缓存用户名的方法
     * @param userId 用户ID
     * @param userHomeObj 用户主页对象（可选）
     * @param fromTag 来源标记（可选）
     * @return 用户名
     */
    private fun getAndCacheUserName(userId: String?, userHomeObj: JSONObject?, fromTag: String?): String? {
        // 输入验证：userId为空时直接返回
        if (userId.isNullOrEmpty()) {
            return null
        }

        // 1. 尝试从缓存获取
        val cachedUserName = userNameCache.get(userId)
        if (!cachedUserName.isNullOrEmpty() && cachedUserName != userId) {
            // 如果缓存的不是userId本身，且不为空，则返回缓存值
            return cachedUserName
        }

        // 2. 根据上下文解析用户名
        var userName = resolveUserNameFromContext(userId, userHomeObj, fromTag)

        // 3. Fallback处理：如果解析失败，使用userId作为显示名
        if (userName.isNullOrEmpty()) {
            userName = userId
        }

        // 4. 存入缓存（只缓存有效的用户名）
        if (userName.isNotEmpty()) {
            userNameCache[userId] = userName
        }

        return userName
    }

    /**
     * 统一获取用户名的简化方法（无上下文）
     */
    private fun getAndCacheUserName(userId: String?): String? {
        return getAndCacheUserName(userId, null, null)
    }

    /**
     * 通用错误处理器
     * @param operation 操作名称
     * @param throwable 异常对象
     */
    private fun handleException(operation: String?, throwable: Throwable) {
        if (throwable is JSONException) {
            // JSON解析错误通常是网络响应问题，只记录错误信息不打印堆栈，避免刷屏
            Log.error(TAG, operation + " JSON解析错误: " + throwable.message)
        } else {
            Log.error(TAG, operation + " 错误: " + throwable.message)
            Log.printStackTrace(TAG, throwable)
        }
    }

    /**
     * 道具使用配置类
     */
    @JvmRecord
    private data class PropConfig(
        val propName: String?, val propTypes: Array<String>?,
        val condition: Supplier<Boolean?>?,
        val exchangeFunction: Supplier<Boolean?>?,
        val endTimeUpdater: Consumer<Long?>?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PropConfig
            if (propName != other.propName) return false
            if (!propTypes.contentEquals(other.propTypes)) return false
            if (condition != other.condition) return false
            if (exchangeFunction != other.exchangeFunction) return false
            if (endTimeUpdater != other.endTimeUpdater) return false
            return true
        }

        override fun hashCode(): Int {
            var result = propName?.hashCode() ?: 0
            result = 31 * result + (propTypes?.contentHashCode() ?: 0)
            result = 31 * result + (condition?.hashCode() ?: 0)
            result = 31 * result + (exchangeFunction?.hashCode() ?: 0)
            result = 31 * result + (endTimeUpdater?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * 通用道具使用模板方法
     *
     * @param bagObject    背包对象
     * @param config       道具配置
     * @param constantMode 是否开启永动机模式
     */
    private fun usePropTemplate(bagObject: JSONObject?, config: PropConfig, constantMode: Boolean) {
        try {
            if (config.condition != null && !config.condition.get()!!) {
                Log.forest("不满足使用" + config.propName + "的条件")
                return
            }
            Log.forest("尝试使用" + config.propName + "...")
            // 按优先级查找道具
            var propObj: JSONObject? = null
            for (propType in config.propTypes!!) {
                propObj = findPropBag(bagObject, propType)
                if (propObj != null) break
            }
            // 如果背包中没有道具且开启永动机，尝试兑换
            if (propObj == null && constantMode && config.exchangeFunction != null) {
                Log.forest("背包中没有" + config.propName + "，尝试兑换...")
                if (config.exchangeFunction.get() == true) {
                    // 重新查找兑换后的道具
                    for (propType in config.propTypes) {
                        propObj = findPropBag(queryPropList(), propType)
                        if (propObj != null) break
                    }
                }
            }
            if (propObj != null) {
                // 针对限时双击卡的时间检查
                if ("双击卡" == config.propName) {
                    val propType = propObj.optString("propType")
                    if ("ENERGY_DOUBLE_CLICK" == propType) {
                        val decision = evaluateTriggerDecision(doubleCardTime, StatusFlags.FLAG_ANTFOREST_DOUBLE_CARD_TRIGGER_INDEX)
                        if (decision?.allowNow != true) {
                            logTriggerWaiting("双击卡", decision)
                            return
                        }
                        consumeTriggerSlot(StatusFlags.FLAG_ANTFOREST_DOUBLE_CARD_TRIGGER_INDEX, decision.matchedSlotIndex)
                    }
                }
                Log.forest("找到" + config.propName + "，准备使用: " + propObj)
                if (usePropBag(propObj)) {
                    config.endTimeUpdater?.accept(System.currentTimeMillis())
                }
            } else {
                Log.forest("背包中未找到任何可用的" + config.propName + "，本次不额外刷新主页")
            }
        } catch (th: Throwable) {
            handleException("use" + config.propName, th)
        }
    }

    /**
     * 从上下文中解析用户名
     */
    private fun resolveUserNameFromContext(
        userId: String?,
        userHomeObj: JSONObject?,
        fromTag: String?
    ): String? {
        var userName: String? = null

        if ("pk" == fromTag && userHomeObj != null) {
            val userEnergy = userHomeObj.optJSONObject("userEnergy")
            if (userEnergy != null) {
                userName = "PK榜好友|" + userEnergy.optString("displayName")
            }
        } else {
            userName = UserMap.getMaskName(userId)
            if ((userName == null || userName == userId) && userHomeObj != null) {
                val userEnergy = userHomeObj.optJSONObject("userEnergy")
                if (userEnergy != null) {
                    val displayName = userEnergy.optString("displayName")
                    if (!displayName.isEmpty()) {
                        userName = displayName
                    }
                }
            }
        }
        return userName
    }

    companion object {
        val TAG: String = AntForest::class.java.getSimpleName()

        // 访问好友主页过快容易触发风控；这里在并发和最小间隔上做保守兜底（仍可通过配置调大查询间隔）。
        private const val FRIEND_PROCESS_CONCURRENCY = 8
        private const val FRIEND_HOME_MIN_INTERVAL_MS = 2000
        private const val GIFT7TH_SIGN_SCENE_CODE = "ANTFOREST_GIFT7TH_SIGN_202506"
        private const val GIFT7TH_SIGN_SOURCE = "chInfo_ch_appcenter__chsub_9patch"
        private const val FOREST_SIGN_TASK_TYPE = "__FOREST_SIGN__"
        private const val FOREST_LEYUAN_DAILY_TASK_SCENE_CODE = "ANTFOREST_LEYUAN_DAILY_TASK"
        private const val FOREST_LEYUAN_OPEN_BOX_TASK_TYPE = "OPEN_BOX_FIN"
        private const val FOREST_LEYUAN_OPEN_BOX_TARGET_COUNT = 10
        private const val ONE_CLICK_WATERING_TASK_TYPE = "ONE_CLICK_WATERING_V1"
        private const val LEGACY_FOREST_GAME_TASK_TYPE = "mokuai_senlin_hlz"
        private const val VITALITY_ENERGY_RAIN_RIGHTS_ID = "VITALITY_ENERGYRAIN_3DAYS"
        private const val LIMIT_TIME_ENERGY_RAIN_CHANCE_PROP_TYPE = "LIMIT_TIME_ENERGY_RAIN_CHANCE"
        private const val ROB_MULTIPLIER_PROLONG_THRESHOLD_MS = 30 * TimeFormatter.ONE_DAY_MS
        private const val ROB_MULTIPLIER_FACTOR_EPS = 0.0001
        private val FOREST_LEYUAN_REWARD_READY_STATUSES = setOf("FINISHED", "WAIT_RECEIVE", "TO_RECEIVE")
        private val FOREST_LEYUAN_PROGRESS_PENDING_STATUSES = setOf("TODO", "WAIT_COMPLETE", "NOT_TRIGGER")
        private val APP_ID_QUERY_REGEX = Regex("""(?:^|[?&])appId=([0-9]+)""")

        @JvmField
        var instance: AntForest? = null


        private val offsetTimeMath = Average(5)

        /** 保护罩剩余有效期低于7天时可延长使用。 */
        private const val SHIELD_RENEW_THRESHOLD_MS = 7 * TimeFormatter.ONE_DAY_MS
        var giveEnergyRainList: FriendSelectionModelField? = null //能量雨赠送列表
        var medicalHealthOption: SelectModelField? = null //医疗健康选项
        var ecoLifeOption: SelectModelField? = null

        /**
         * 异常返回检测开关
         */
        private var errorWait = false
        var ecoLifeOpen: BooleanModelField? = null
        private var canConsumeAnimalProp = false
        private var totalCollected = 0
        private var totalHelpCollected = 0
        private var totalWatered = 0
        private const val MAX_BATCH_SIZE = 6

        // 找能量功能的冷却时间（毫秒），15分钟
        private const val TAKE_LOOK_COOLDOWN_MS = 15 * 60 * 1000L
        private const val TAKE_LOOK_MAX_ATTEMPTS = 80

        /**
         * 下次可以执行找能量的时间戳
         * 使用 @Volatile 确保多线程环境下的可见性
         */
        @Volatile
        private var nextTakeLookTime: Long = 0

        private fun propEmoji(propName: String): String {
            val tag: String = if (propName.contains("保")) {
                "🛡️"
            } else if (propName.contains("双")) {
                "👥"
            } else if (propName.contains("加")) {
                "🌪"
            } else if (propName.contains("雨")) {
                "🌧️"
            } else if (propName.contains("炸")) {
                "💥"
            } else {
                "🥳"
            }
            return tag
        }
    }

    /**
     * 检查用户是否有保护罩或炸弹（按照原有逻辑）
     */
    private fun checkUserShieldAndBomb(userHomeObj: JSONObject, userName: String?, userId: String, serverTime: Long): Boolean {
        var hasProtection = false
        val isSelf = userId == UserMap.currentUid

        if (!isSelf) {
            val shieldEndTime = ForestUtil.getShieldEndTime(userHomeObj)
            val bombEndTime = ForestUtil.getBombCardEndTime(userHomeObj)
            maxOf(shieldEndTime, bombEndTime)

            if (shieldEndTime > serverTime) {
                hasProtection = true
                val remainingHours = (shieldEndTime - serverTime) / (1000 * 60 * 60)
                Log.forest("[$userName]被能量罩❤️保护着哟(还剩${remainingHours}h)，跳过收取")
            }
            if (bombEndTime > serverTime) {
                hasProtection = true
                val remainingHours = (bombEndTime - serverTime) / (1000 * 60 * 60)
                Log.forest("[$userName]开着炸弹卡💣(还剩${remainingHours}h)，跳过收取")
            }
        }

        return hasProtection
    }

    /**
     * 专门用于蹲点的能量收取方法
     */
    @SuppressLint("SimpleDateFormat")
    private fun collectEnergyForWaiting(
        userId: String,
        userHomeObj: JSONObject,
        fromTag: String?,
        userName: String?
    ): CollectResult {
        try {
            Log.forest("蹲点收取开始：用户[${userName}] userId[${userId}] fromTag[${fromTag}]")
            // 获取服务器时间
            val serverTime = userHomeObj.optLong("now", System.currentTimeMillis())
            // 判断是否是自己的账号
            val isSelf = userId == UserMap.currentUid

            // 先检查保护罩和炸弹（仅对好友检查）
            val shieldEndTime = ForestUtil.getShieldEndTime(userHomeObj)
            val bombEndTime = ForestUtil.getBombCardEndTime(userHomeObj)
            val hasShield = shieldEndTime > serverTime
            val hasBomb = bombEndTime > serverTime
            val hasProtection = hasShield || hasBomb

            Log.forest("蹲点收取保护检查详情：")
            Log.forest("  是否是主号: $isSelf")
            Log.forest("  服务器时间: $serverTime (${
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
                        Date(serverTime)
                    )
                })"
            )
            Log.forest("  保护罩结束时间: $shieldEndTime (${
                    if (shieldEndTime > 0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
                        Date(shieldEndTime)
                    ) else "无保护罩"
                })"
            )
            Log.forest("  炸弹卡结束时间: $bombEndTime (${
                    if (bombEndTime > 0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
                        Date(bombEndTime)
                    ) else "无炸弹卡"
                })"
            )
            Log.forest("  是否有保护罩: $hasShield")
            Log.forest("  是否有炸弹卡: $hasBomb")
            Log.forest("  总体保护状态: $hasProtection")

            // 只对好友账号进行保护检查，主号无视保护罩
            if (!isSelf && hasProtection) {
                // 调用原有的日志输出方法
                checkUserShieldAndBomb(userHomeObj, userName, userId, serverTime)
                return CollectResult(
                    success = false,
                    userName = userName,
                    message = "有保护，已跳过",
                    hasShield = hasShield,
                    hasBomb = hasBomb
                )
            }

            // 主号的保护罩不影响收取自己的能量
            if (isSelf && hasProtection) {
                Log.forest("  ⭐ 主号有保护罩，但可以收取自己的能量")
            }

            // 先查询用户能量状态
            val queryResult = collectEnergy(userId, userHomeObj, fromTag) ?: return CollectResult(
                success = false,
                userName = userName,
                message = "无法查询用户能量信息"
            )

            // 提取可收取的能量球ID
            val availableBubbles: MutableList<Long> = ArrayList()
            val queryServerTime = queryResult.optLong("now", System.currentTimeMillis())
            extractBubbleInfo(
                queryResult,
                queryServerTime,
                availableBubbles,
                userId,
                collectWaitingTasks = false,
                logSummary = false
            )

            if (availableBubbles.isEmpty()) {
                return CollectResult(
                    success = false,
                    userName = userName,
                    message = "用户无可收取的能量球"
                )
            }

            Log.forest("蹲点收取找到${availableBubbles.size}个可收取能量球: $availableBubbles")

            // 记录收取前的总能量
            val beforeTotal = totalCollected

            // 🚀 启用快速收取通道：跳过道具检查，加速蹲点收取
            val failedBubbleIds = collectVivaEnergy(userId, queryResult, availableBubbles, fromTag, skipPropCheck = true)

            // 计算收取的能量数量
            val collectedEnergy = totalCollected - beforeTotal
            val requestedBubbleIds = availableBubbles.toSet()
            val allRequestedFailed = failedBubbleIds.isNotEmpty() && failedBubbleIds.containsAll(requestedBubbleIds)

            return if (collectedEnergy > 0) {
                CollectResult(
                    success = true,
                    userName = userName,
                    energyCount = collectedEnergy,
                    totalCollected = totalCollected,
                    message = "收取成功，共收取${availableBubbles.size}个能量球，${collectedEnergy}g能量",
                    failedBubbleIds = failedBubbleIds
                )
            } else if (allRequestedFailed) {
                CollectResult(
                    success = false,
                    userName = userName,
                    message = "服务端标记目标能量球收取失败",
                    failedBubbleIds = failedBubbleIds,
                    allRequestedBubblesFailed = true
                )
            } else {
                CollectResult(
                    success = false,
                    userName = userName,
                    message = "未收取到任何能量"
                )
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "collectEnergyForWaiting err", e)
            return CollectResult(
                success = false,
                userName = userName,
                message = "收取异常：${e.message}"
            )
        }
    }

    /**
     * 实现EnergyCollectCallback接口
     * 为蹲点管理器提供能量收取功能（增强版）
     */
    override fun addToTotalCollected(energyCount: Int) {
        if (energyCount <= 0) return
        selfId?.takeIf { it.isNotBlank() }?.let { uid ->
            Statistics.addData(uid, Statistics.DataType.COLLECTED, energyCount)
            totalCollected = Statistics.getData(uid, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
        } ?: run {
            totalCollected += energyCount
        }
    }

    override fun getWaitingCollectDelay(): Long {
        return 0L // 立即收取，无延迟
    }

    override fun canRunWaitingCollection(): Boolean {
        return isCollectEnergyEnabled()
    }

    override suspend fun collectUserEnergyForWaiting(task: EnergyWaitingManager.WaitingTask): CollectResult {
        if (!isCollectEnergyEnabled()) {
            Log.forest("收集能量开关关闭，暂停蹲点收取")
            return CollectResult(
                success = false,
                userName = task.userName,
                message = EnergyWaitingManager.WAITING_PAUSED_COLLECT_DISABLED,
                paused = true
            )
        }
        return try {
            withContext(Dispatchers.Default) {
                // 主号蹲点查询自己的主页，好友蹲点走好友主页守卫。
                val fromAct = if (task.isPkContest()) "PKContest" else "TAKE_LOOK_FRIEND"
                val friendHomeObj = if (task.isSelf()) {
                    querySelfHome()
                } else {
                    queryFriendHome(task.userId, fromAct)
                }
                if (friendHomeObj != null) {
                    // 获取真实用户名
                    val realUserName = getAndCacheUserName(task.userId, friendHomeObj, task.fromTag)
                    val isSelf = task.userId == UserMap.currentUid
                    Log.forest("蹲点收取：用户[${realUserName}] userId=${task.userId} currentUid=${UserMap.currentUid} isSelf=${isSelf}")
                    // 直接执行能量收取，让原有的collectEnergy方法处理保护罩和炸弹检查
                    val result = collectEnergyForWaiting(task.userId, friendHomeObj, task.fromTag, realUserName)
                    if (result.success && result.energyCount > 0) {
                        runCatching {
                            updateSelfHomePage(collectRobMultiplierEnergy = true)
                        }.onFailure {
                            Log.printStackTrace(TAG, "蹲点后领取N倍卡能量失败", it)
                        }
                    }
                    result.copy(userName = realUserName)
                } else {
                    CollectResult(
                        success = false,
                        userName = task.userName,
                        message = "无法获取用户主页信息"
                    )
                }
            }
        } catch (e: CancellationException) {
            // 协程取消是正常现象，不记录为错误
            Log.forest("collectUserEnergyForWaiting 协程被取消")
            throw e  // 必须重新抛出以保证取消机制正常工作
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "collectUserEnergyForWaiting err", e)
            CollectResult(
                success = false,
                userName = task.userName,
                message = "异常：${e.message}"
            )
        }
    }

    /**
     * 判断是否为团队
     *
     * @param homeObj 用户主页的JSON对象
     * @return 是否为团队
     */
    internal fun isTeam(homeObj: JSONObject): Boolean {
        return homeObj.optString("nextAction", "") == "Team"
    }

    /**
     * 手动触发森林打地鼠
     */
    suspend fun manualWhackMole() {
        try {
            val obj = querySelfHome()
            if (obj != null) {
                Log.forest("🎮 手动触发拼手速任务")
                WhackMole.startSuspend()
            } else {
                Log.forest("无法获取自己主页信息")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 手动运行能量雨逻辑
     * @param exchange 是否先尝试兑换并使用能量雨卡
     */
    suspend fun manualUseEnergyRain(exchange: Boolean) {
        try {
            Log.forest("🚀 开始执行手动能量雨任务...")
            val obj = querySelfHome()
            if (obj != null) {

                if (exchange) {
                    Log.forest("尝试兑换并激活能量雨卡...")
                    useEnergyRainChanceCard()
                }

                if (EnergyRainCoroutine.execEnergyRain(
                        isManual = true,
                        gameTaskCloser = EnergyRainCoroutine.EnergyRainGameDriveCloser { request ->
                            closeEnergyRainGameDriveTask(request)
                        }
                    )
                ) {
                    handleEnergyRainPostFlow()
                    if (!hasPendingRobMultiplierEnergy()) {
                        updateSelfHomePage(homePageSource = AntForestRpcCall.BACK_FROM_ENERGY_RAIN_SOURCE)
                    }
                    if (hasPendingRobMultiplierEnergy()) {
                        updateSelfHomePage(
                            collectRobMultiplierEnergy = true
                        )
                    }
                }
                Log.forest("✅ 手动能量雨任务处理完毕")
            } else {
                Log.forest("无法获取自己主页信息")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "manualUseEnergyRain 异常:", t)
        }
    }
}

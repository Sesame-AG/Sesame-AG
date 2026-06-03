package io.github.aoguai.sesameag.task.antSports

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.SportsEnergyExchange
import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.hook.HookReadyChecker
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleDefaults
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleKind
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.model.modelFieldExt.HourOfDayModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.model.modelFieldExt.StringModelField
import io.github.aoguai.sesameag.task.AnswerAI.AnswerAI
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
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
import io.github.aoguai.sesameag.util.*
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.friend.FriendCapabilityRecorder
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.SportsEnergyExchangeMap
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*

import kotlin.math.max
import kotlin.math.min

/**
 * @file AntSports.kt
 * @brief 支付宝蚂蚁运动主任务逻辑（Kotlin 重构版）。
 *
 * @details
 * 负责统一调度蚂蚁运动相关的所有自动化逻辑，包括：
 * - 步数同步与行走路线（旧版 & 新版路线）
 * - 运动任务面板任务、首页能量球任务
 * - 首页金币收集、慈善捐步
 * - 文体中心任务 / 行走路线
 * - 抢好友大战（训练好友 + 抢购好友）
 * - 健康岛（Neverland）任务、泡泡、走路建造
 *
 * 所有 RPC 调用均通过 {@link AntSportsRpcCall} 与 {@link AntSportsRpcCall.NeverlandRpcCall} 完成。
 */
@SuppressLint("DefaultLocale")
class AntSports : ModelTask() {

    companion object {
        /** @brief 日志 TAG */
        private val TAG: String = AntSports::class.java.simpleName

        /** @brief 运动任务完成日期缓存键 */
        private const val SPORTS_TASKS_COMPLETED_DATE = "SPORTS_TASKS_COMPLETED_DATE"

        /** @brief 训练好友 0 金币达上限日期缓存键 */
        private const val TRAIN_FRIEND_ZERO_COIN_DATE = "TRAIN_FRIEND_ZERO_COIN_DATE"

        /** @brief 运动任务黑名单模块名 */
        private const val SPORTS_TASK_BLACKLIST_MODULE = "运动"
        private const val SPORTS_CHECK_IN_TITLE = "运动签到"
        private const val SPORTS_DOLPHIN_ACTIVITY_TITLE = "海豚活动"
        private const val SPORTS_WALK_CHALLENGE_TITLE = "走路挑战赛"

        /** @brief 训练好友目标变更重试上限 */
        private const val MAX_TRAIN_MEMBER_CHANGED_RETRIES = 5

        /** @brief 运动首页任务最大补拉轮次 */
        private const val MAX_SPORTS_HOME_BUBBLE_ROUNDS = 10

        /** @brief 步数同步子任务 ID，用于避免同一天重复排队 */
        private const val SYNC_STEP_CHILD_TASK_ID = "syncStep"
        const val PERSISTENT_CHILD_KIND = "sports_child_task"
        private const val MOTION_DAILY_QUIZ_REWARD_TASK_ID = "QUIZ_ANSWER_ENERGY_BALL_TASK"

        private const val RPC_WALK_QUERY_PATH = "com.alipay.sportsplay.biz.rpc.walk.queryPath"
        private const val RPC_WALK_QUERY_USER = "com.alipay.sportsplay.biz.rpc.walk.queryUser"
        private const val RPC_WALK_QUERY_WORLD_MAP = "com.alipay.sportsplay.biz.rpc.walk.queryWorldMap"
        private const val RPC_WALK_QUERY_CITY_PATH = "com.alipay.sportsplay.biz.rpc.walk.queryCityPath"
        private const val RPC_WALK_QUERY_CITY_KNOWLEDGE_SUMMARY =
            "com.alipay.sportsplay.biz.rpc.walk.queryCityKnowledgeSummary"
        private const val RPC_WALK_QUERY_MEDAL_DETAIL =
            "com.alipay.sportsplay.biz.rpc.walk.queryMedalDetail"
        private const val RPC_WALK_QUERY_RECOMMEND_PATH_LIST =
            "com.alipay.sportsplay.biz.rpc.walk.queryRecommendPathList"
        private const val RPC_WALK_REVIVE_QUERY_DETAIL =
            "com.alipay.sportsplay.biz.rpc.walk.steprevive.queryUserReviveStepT2"
        private const val RPC_WALK_REVIVE_QUERY_TASK_LIST =
            "com.alipay.sportsplay.biz.rpc.walk.steprevive.queryTaskList"
        private const val RPC_WALK_REVIVE_QUERY_TASK_FINISH_STATUS =
            "com.alipay.sportsplay.biz.rpc.walk.steprevive.queryTaskFinishStatus"
        private const val RPC_TIYUBIZ_PATH_FEATURE_QUERY = "alipay.tiyubiz.path.feature.query"
        private const val RPC_TIYUBIZ_PATH_MAP_HOMEPAGE = "alipay.tiyubiz.path.map.homepage"
        private const val RPC_TIYUBIZ_PATH_MAP_STEP_QUERY = "alipay.tiyubiz.path.map.step.query"
        private const val RPC_USER_ONLINE_GAME_LIST_QUERY = "alipay.tiyubiz.userOnlineGame.listquery"
        private const val RPC_ONLINE_GAME_SPORTS_LIST_QUERY = "alipay.tiyubiz.onlineGame.sports.listquery"
        private const val RPC_ONLINE_GAME_EVENT_QUERY = "alipay.tiyubiz.onlineGame.eventQuery"
        private const val RPC_USER_ONLINE_GAME_DETAIL_QUERY = "alipay.tiyubiz.userOnlineGame.detailQuery.forwenti"
        private const val RPC_USER_ONLINE_GAME_DATA_QUERY = "alipay.tiyubiz.userOnlineGame.dataQuery"

        private const val WALK_CHALLENGE_SPORTS_TYPE = "walk"
        private const val WALK_CHALLENGE_MIN_STEP_COUNT = 150
        private const val WALK_CHALLENGE_STEP_LENGTH_METER = 0.75
        private const val WALK_CHALLENGE_MIN_DISTANCE_METER = 100.0
        private const val WALK_CHALLENGE_WALK_CALORIE_FACTOR = 0.8214
        private const val NEVERLAND_SOURCE_SPORT_HOME = "ch_toufang__yundongshouye"
        private const val NEVERLAND_SOURCE_LEGACY = "jkdsportcard"
        private val NEVERLAND_SOURCE_CANDIDATES = listOf(NEVERLAND_SOURCE_SPORT_HOME, NEVERLAND_SOURCE_LEGACY)
        private const val SPORT_ITEM_MALL_CITY_CODE = "440100"

    }

    private data class SportsHomeRewardCandidate(
        val recordId: String,
        val sourceName: String,
        val taskId: String,
        val coinAmount: Int
    )

    private data class SportsHomeRewardScanResult(
        val candidates: LinkedHashMap<String, SportsHomeRewardCandidate> = LinkedHashMap(),
        var missingRecordIdCount: Int = 0
    )

    private data class SportsHomeTaskCompletionResult(
        val response: JSONObject,
        val action: String,
        val rpc: String,
        val source: String
    )

    private data class MotionQuizRewardCandidate(
        val creativityId: String,
        val objectId: String,
        val title: String
    )

    private data class WalkChallengeGame(
        val gameId: String,
        val name: String,
        val userGameId: String = "",
        val totalProgressValue: Double = 0.0,
        val userProgressGameValue: Double = 0.0,
        val progressUnit: String = "",
        val sportsDataType: String = ""
    )

    private data class WalkChallengeEvent(
        val gameEventId: String,
        val rightsPackageId: String,
        val title: String,
        val progressValue: Double,
        val progressUnit: String,
        val defaultSelected: Boolean
    )

    private data class WalkChallengeJoinQuery(
        val success: Boolean,
        val game: WalkChallengeGame? = null
    )

    private data class WalkChallengeSportRecord(
        val recordId: String,
        val sportsType: String,
        val stepCount: Int,
        val distance: Double,
        val durationSeconds: Int,
        val calories: Double,
        val averageSpeed: Double,
        val startTime: Long,
        val finishTime: Long,
        val geoPoints: String,
        val goalValue: String
    )

    private data class SportsTrainTarget(
        val memberId: String,
        val originBossId: String,
        val userName: String
    )

    private data class SportsTrainItemSelection(
        val bizId: String,
        val itemType: String,
        val itemName: String
    )

    private data class ClubMemberCandidate(
        val currentBossId: String,
        val memberId: String,
        val originBossId: String,
        val price: Int
    )

    private data class NeverlandRewardCandidate(
        val rewardId: String,
        val name: String,
        val status: String,
        val prizeStatus: String
    )

    private data class SportsEnergyExchangeCandidate(
        val item: ExchangeItem,
        val benefitId: String,
        val itemId: String,
        val materialType: String,
        val cityCode: String
    )

    private data class RouteConfig(
        val themeIds: List<String>,
        val pathIds: List<String>,
        val themeLoop: Boolean,
        val routeLoop: Boolean,
        val reviveSteps: Boolean,
        val reviveTask: Boolean
    )

    private data class RouteCandidate(
        val pathId: String,
        val name: String = "",
        val themeId: String? = null,
        val cityId: String? = null,
        val status: String = ""
    )

    private data class RouteKnowledgeCity(
        val cityId: String,
        val cityName: String,
        val total: Int,
        val received: Int,
        val status: String = "",
        val themeId: String? = null
    )

    private data class RouteDecision(
        val candidate: RouteCandidate,
        val reason: String
    )

    private data class ReviveCandidate(
        val date: String,
        val count: Int
    )

    private enum class RouteWalkOutcome {
        MOVED,
        COMPLETED,
        NO_STEPS,
        STOP
    }

    /** @brief 临时步数缓存（-1 表示未初始化） */
    private var tmpStepCount: Int = -1
    private var cachedOriginDailyStep: Int = -1
    private var cachedTargetDailyStep: Int = -1
    private var syncStepHookLogged: Boolean = false
    private val syncStepLock = Any()

    @Volatile
    private var syncStepInProgress: Boolean = false

    // 配置字段
    internal lateinit var walk: BooleanModelField
    private lateinit var walkPathTheme: ChoiceModelField
    private var walkPathThemeId: String? = null
    private lateinit var walkCustomPath: BooleanModelField
    private lateinit var walkCustomPathId: StringModelField
    private lateinit var walkThemeIds: StringModelField
    private lateinit var walkPathIds: StringModelField
    private lateinit var walkThemeLoop: BooleanModelField
    private lateinit var walkRouteLoop: BooleanModelField
    private lateinit var walkReviveSteps: BooleanModelField
    private lateinit var walkReviveTask: BooleanModelField
    internal lateinit var openTreasureBox: BooleanModelField
    private lateinit var receiveCoinAssetField: BooleanModelField
    internal lateinit var donateCharityCoin: BooleanModelField
    private lateinit var donateCharityCoinType: ChoiceModelField
    private lateinit var donateCharityCoinAmount: IntegerModelField
    internal lateinit var minExchangeCount: IntegerModelField
    internal lateinit var earliestSyncStepTime: HourOfDayModelField
    private lateinit var latestExchangeTime: HourOfDayModelField
    private lateinit var syncStepCount: IntegerModelField
    internal lateinit var tiyubiz: BooleanModelField
    internal lateinit var battleForFriends: BooleanModelField
    private lateinit var battleAutoUnlockRoom: BooleanModelField
    private lateinit var battleForFriendType: ChoiceModelField
    private lateinit var originBossIdList: FriendSelectionModelField
    private lateinit var sportsTasksField: BooleanModelField
    private lateinit var sportsEnergyBubble: BooleanModelField
    private lateinit var sportsEnergyExchange: BooleanModelField
    private lateinit var sportsEnergyExchangeList: SelectModelField

    // 训练好友相关配置
    internal lateinit var trainFriend: BooleanModelField
    private lateinit var zeroCoinLimit: IntegerModelField

    /** @brief 记录训练好友连续获得 0 金币的次数 */
    private var zeroTrainCoinCount: Int = 0
    private val skippedTrainBubbleRecordIds = mutableSetOf<String>()

    // 健康岛任务
    internal lateinit var neverlandTask: BooleanModelField
    internal lateinit var neverlandGrid: BooleanModelField
    private lateinit var neverlandAutoReward: BooleanModelField
    private lateinit var neverlandPreferMedal: BooleanModelField
    private lateinit var neverlandGridStepCount: IntegerModelField


    /**
     * @brief 任务名称
     */
    override fun getName(): String = "运动"

    /**
     * @brief 所属任务分组
     */
    override fun getGroup(): ModelGroup = ModelGroup.SPORTS

    /**
     * @brief 图标文件名
     */
    override fun getIcon(): String = "AntSports.png"

    /**
     * @brief 定义本任务所需的所有配置字段
     */
    override fun getFields(): ModelFields {
        val modelFields = ModelFields()

        // 行走路线
        modelFields.addField(BooleanModelField("walk", "行走路线 | 开启", false).withDesc(
            "开启新版运动路线自动行走，按配置主题或自定义路线推进路线并领取奖励。"
        ).also { walk = it })
        modelFields.addField(
            ChoiceModelField(
                "walkPathTheme",
                "行走路线 | 主题",
                WalkPathTheme.DA_MEI_ZHONG_GUO,
                WalkPathTheme.nickNames
            ).withDesc("选择新版行走路线主题。需开启“行走路线 | 开启”，且未开启“行走路线 | 开启自定义路线”。").also { walkPathTheme = it }
        )
        modelFields.addField(
            BooleanModelField("walkCustomPath", "行走路线 | 开启自定义路线", false).withDesc(
                "改为使用“行走路线 | 自定义路线代码”，不再按主题自动选线。需开启“行走路线 | 开启”。"
            ).also { walkCustomPath = it }
        )
        modelFields.addField(
            StringModelField(
                "walkCustomPathId",
                "行走路线 | 自定义路线代码",
                "p0002023122214520001"
            ).withDesc("自定义路线代码。需开启“行走路线 | 开启”和“行走路线 | 开启自定义路线”。").also { walkCustomPathId = it }
        )
        modelFields.addField(
            StringModelField(
                "walkThemeIds",
                "行走路线 | 自定义主题ID列表",
                ""
            ).withDesc("新版路线主题 ID 列表，支持逗号、空格或换行分隔；为空时兼容使用旧主题下拉配置。").also { walkThemeIds = it }
        )
        modelFields.addField(
            StringModelField(
                "walkPathIds",
                "行走路线 | 自定义路线ID列表",
                ""
            ).withDesc("新版路线 ID 列表，支持逗号、空格或换行分隔；非空时优先按路线列表选择。").also { walkPathIds = it }
        )
        modelFields.addField(
            BooleanModelField("walkThemeLoop", "行走路线 | 主题循环", false).withDesc(
                "开启后固定在当前主题或配置首个主题内循环所有路线；路线循环开启时优先路线循环。"
            ).also { walkThemeLoop = it }
        )
        modelFields.addField(
            BooleanModelField("walkRouteLoop", "行走路线 | 路线循环", false).withDesc(
                "开启后只循环当前路线；没有当前路线时使用自定义路线或主题中的第一条路线。"
            ).also { walkRouteLoop = it }
        )
        modelFields.addField(
            BooleanModelField("walkReviveSteps", "行走路线 | 复活步数", false).withDesc(
                "路线当日无可用步数时，尝试复活历史步数；耗尽后当天不再重复尝试。"
            ).also { walkReviveSteps = it }
        )
        modelFields.addField(
            BooleanModelField("walkReviveTask", "行走路线 | 复活任务", false).withDesc(
                "复活次数不足时尝试完成复活任务。"
            ).also { walkReviveTask = it }
        )

        // 旧版路线相关
        modelFields.addField(
            BooleanModelField("openTreasureBox", "旧版路线 | 开启", false).withDesc(
                "兼容旧版路线入口：在未开启新版行走路线时，自动处理旧版路线的加入、前进和宝箱领取。"
            ).also { openTreasureBox = it }
        )

        // 运动任务 & 能量球
        modelFields.addField(
            BooleanModelField("sportsTasks", "运动任务 | 开启", false).withDesc(
                "执行运动任务面板中的签到、任务完成与奖励领取。"
            ).also { sportsTasksField = it }
        )
        modelFields.addField(
            BooleanModelField(
                "sportsEnergyBubble",
                "运动球 | 开启",
                false
            ).withDesc("处理首页推荐的运动球任务，可能需要滑块验证，不包含任务面板任务。").also { sportsEnergyBubble = it }
        )
        modelFields.addField(
            BooleanModelField(
                "sportsEnergyExchange",
                "运动 | 能量兑换",
                false
            ).withDesc("刷新“运动 | 能量兑换列表”；仅纯能量虚拟权益自动兑换，实付/商品/下单链路只提醒。").also { sportsEnergyExchange = it }
        )
        modelFields.addField(
            SelectModelField(
                "sportsEnergyExchangeList",
                "运动 | 能量兑换列表",
                LinkedHashSet<String?>()
            ) {
                refreshSportsEnergyExchangeOptionsForSettings()
                SportsEnergyExchange.getList()
            }.withDesc("勾选允许处理的运动能量兑换项，需开启“运动 | 能量兑换”。").also { sportsEnergyExchangeList = it }
        )

        // 首页金币 & 捐步
        modelFields.addField(
            BooleanModelField("receiveCoinAsset", "能量气球 | 收取", false).withDesc(
                "收取首页可领取的能量气球或运动币资源；关闭后不会在运动任务完成后顺带收取。"
            ).also { receiveCoinAssetField = it }
        )
        modelFields.addField(
            BooleanModelField("donateCharityCoin", "能量气球 | 捐赠", false).withDesc(
                "自动把能量气球捐给公益项目。"
            ).also { donateCharityCoin = it }
        )
        modelFields.addField(
            ChoiceModelField(
                "donateCharityCoinType",
                "能量气球 | 捐赠方式",
                DonateCharityCoinType.ONE,
                DonateCharityCoinType.nickNames
            ).withDesc("控制只捐一个项目还是继续处理更多项目。需开启“能量气球 | 捐赠”。").also { donateCharityCoinType = it }
        )
        modelFields.addField(
            IntegerModelField("donateCharityCoinAmount", "能量气球 | 单次捐赠数量", 100, 1, 100000).withDesc(
                "每次捐赠的能量气球数量。需开启“能量气球 | 捐赠”。"
            )
                .also { donateCharityCoinAmount = it }
        )

        // 健康岛任务
        modelFields.addField(
            BooleanModelField("neverlandTask", "健康岛 | 任务", false).withDesc(
                "执行健康岛签到、任务大厅、浏览任务和泡泡领取。"
            ).also { neverlandTask = it }
        )
        modelFields.addField(
            BooleanModelField("neverlandGrid", "健康岛 | 自动走路建造", false).withDesc(
                "自动在健康岛走路建造，优先消耗可用能量并按最大可用倍数建造。"
            ).also { neverlandGrid = it }
        )
        modelFields.addField(
            BooleanModelField("neverlandAutoReward", "健康岛 | 自动领奖", true).withDesc(
                "新游戏模式岛屿完成后自动选择奖励；明确非重试错误会记录并尝试下一个奖励。"
            ).also { neverlandAutoReward = it }
        )
        modelFields.addField(
            BooleanModelField("neverlandPreferMedal", "健康岛 | 优先奖牌", true).withDesc(
                "健康岛自动领奖时优先选择名称包含“奖牌”的奖励，否则选择服务端返回的首个奖励。"
            ).also { neverlandPreferMedal = it }
        )
        modelFields.addField(
            IntegerModelField("neverlandGridStepCount", "健康岛 | 今日建造安全上限", 20).withDesc(
                "健康岛当天最多执行的建造次数；0 表示不按次数限制，改由能量不足或服务端限制停止。"
            )
                .also { neverlandGridStepCount = it }
        )

        // 抢好友相关
        modelFields.addField(
            BooleanModelField("battleForFriends", "抢好友 | 开启", false).withDesc(
                "执行抢好友大战主页收益收取与抢购好友逻辑。"
            ).also { battleForFriends = it }
        )
        modelFields.addField(
            BooleanModelField("battleAutoUnlockRoom", "抢好友 | 自动解锁场地", false).withDesc(
                "抢购好友前，若可购买新训练室且能量足够，则自动解锁并刷新主页状态。"
            ).also { battleAutoUnlockRoom = it }
        )
        modelFields.addField(
            ChoiceModelField(
                "battleForFriendType",
                "抢好友 | 动作",
                BattleForFriendType.ROB,
                BattleForFriendType.nickNames
            ).withDesc("决定好友列表是“选中抢”还是“选中不抢”。需开启“抢好友 | 开启”。").also { battleForFriendType = it }
        )
        modelFields.addField(
            FriendSelectionModelField(
                "originBossIdList",
                "抢好友 | 好友列表"
            ).withDesc("配置抢好友规则作用的好友名单，名单解释方式由“抢好友 | 动作”决定。").also { originBossIdList = it }
        )

        // 训练好友相关
        modelFields.addField(
            BooleanModelField("trainFriend", "抢好友 | 训练好友", false).withDesc(
                "在抢好友大战中自动训练空闲好友。需开启“抢好友 | 开启”。"
            ).also { trainFriend = it }
        )
        modelFields.addField(
            IntegerModelField("zeroCoinLimit", "抢好友 | 训练0金币止损次数", 5, 0, 100).withDesc(
                "连续收取 0 金币达到次数后，当天停止继续训练好友，设为 0 表示不限制。需开启“抢好友 | 训练好友”。"
            )
                .also { zeroCoinLimit = it }
        )

        // 文体中心 & 捐步 & 步数同步
        modelFields.addField(BooleanModelField("tiyubiz", "文体中心 | 开启", false).withDesc(
            "执行文体中心签到、任务、线路推进和走路挑战赛线上赛。"
        ).also { tiyubiz = it })
        modelFields.addField(
            IntegerModelField("minExchangeCount", "旧版捐步 | 最小步数", 0, 0, 100000).withDesc(
                "旧版捐步兑换的触发阈值；设为 0 关闭该流程，最晚时间前不足阈值会继续累积。"
            ).also { minExchangeCount = it }
        )
        modelFields.addField(
            HourOfDayModelField("earliestSyncStepTime", "同步步数 | 最早同步时间", "-1", allowDisable = true).withDesc(
                "允许开始同步自定义步数的最早时间，仅在自定义同步步数大于 0 时生效；填 -1 关闭时间触发。"
            )
                .also { earliestSyncStepTime = it }
        )
        modelFields.addField(
            HourOfDayModelField("latestExchangeTime", "旧版捐步 | 最晚时间", "-1", allowDisable = true, allowDayEnd = true).withDesc(
                "旧版捐步兑换的最晚等待时间；支持 24:00 作为日终截止，填 -1 后不足最小捐步时不会按截止时间强制处理。"
            )
                .also { latestExchangeTime = it }
        )
        modelFields.addField(
            IntegerModelField("syncStepCount", "同步步数 | 自定义步数", 0, 0, 100000).withDesc(
                "在当前真实步数基础上额外增加的步数基数，运行时会随机上浮 0~1999，设为 0 关闭自定义同步。"
            ).also { syncStepCount = it }
        )

        return modelFields
    }

    /**
     * @brief Xposed 启动时 hook 步数读取逻辑，实现自定义步数同步
     */
    override fun boot(classLoader: ClassLoader?) {
        if (classLoader == null) {
            Log.error(TAG, "ClassLoader is null, skip hook readDailyStep")
            return
        }
        try {
            val pedometerAgentClass = Class.forName(
                "com.alibaba.health.pedometer.core.datasource.PedometerAgent",
                false,
                classLoader
            )
            val readDailyStepMethod = pedometerAgentClass.getDeclaredMethod("readDailyStep").apply {
                isAccessible = true
            }
            ApplicationHook.requireXposedInterface().hook(readDailyStepMethod).intercept { chain ->
                val originStep = chain.proceed() as Int
                rememberCurrentDailyStep(originStep)
                val targetStep = resolveTargetDailyStep(originStep)
                if (shouldOverrideDailyStep(originStep, targetStep) && canOverrideDailyStepByHook()) {
                    if (!syncStepHookLogged) {
                        syncStepHookLogged = true
                        Log.sports(
                            "同步步数🏃🏻‍♂️[Hook][原始${originStep}步 + 自定义${targetStep - originStep}步 = ${targetStep}步]"
                        )
                    }
                    targetStep
                } else {
                    originStep
                }
            }
            Log.sports("hook readDailyStep successfully")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "hook readDailyStep err:", t)
        }
    }

    /**
     * @brief 任务主入口
     */
    override fun runJava() {
        Log.sports("执行开始-${getName()}")

        try {
            if (isEnergyOnlyModeNow()) {
                Log.sports("⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，跳过运动任务")
                return
            }

            val loader = ApplicationHook.classLoader
            if (loader == null) {
                Log.error(TAG, "ClassLoader is null, 跳过运动任务")
                return
            }

            runNeverlandWorkflow()
            registerPersistentSyncStepTask()
            runStepSyncWorkflow()

            // 运动任务
            if (sportsTasksField.value == true) {
                if (!Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_DAILY_TASKS_DONE) ||
                    !Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_MOTION_DAILY_QUIZ_DONE)
                ) {
                    sportsTasks()
                } else {
                    Log.sports("运动任务[今日任务面板与问答完成标记已存在，跳过任务入口]")
                }
            }

            // 运动球任务
            if (sportsEnergyBubble.value == true) {
                sportsEnergyBubbleTask()
            }

            if (sportsEnergyExchange.value == true) {
                sportsEnergyExchange()
            }

            runRouteWorkflow(loader)

            // 文体中心
            runSportsCenterWorkflow()

            // 抢好友大战
            runBattleForFriendsWorkflow()

            // 首页金币
            if (receiveCoinAssetField.value == true) {
                receiveCoinAsset()
            }

        } catch (t: Throwable) {
            Log.sports("runJava error:")
            Log.printStackTrace(TAG, t)
        } finally {
            Log.sports("执行结束-${getName()}")
        }
    }

    private fun refreshSportsEnergyExchangeOptionsForSettings(): List<SportsEnergyExchangeCandidate> {
        if (!HookReadyChecker.isCurrentProcessReadyForRpc(UserMap.currentUid)) {
            if (!HookReadyChecker.isTargetAppReadyForRpc(UserMap.currentUid) ||
                !ExchangeOptionsRefreshBridge.requestRefresh(
                    ExchangeOptionsRefreshBridge.TARGET_SPORTS_ENERGY,
                    UserMap.currentUid
                )
            ) {
                Log.sports("运动能量兑换🎁目标应用未就绪，设置页使用缓存列表")
                return emptyList()
            }
            val exchangeMap = IdMapManager.getInstance(SportsEnergyExchangeMap::class.java)
            exchangeMap.load(UserMap.currentUid)
            Log.sports("运动能量兑换🎁设置页加载目标应用刷新列表#${exchangeMap.map.size}")
            return emptyList()
        }
        try {
            val categoryTypes = linkedSetOf("")
            runCatching {
                AntSportsRpcCall.NeverlandRpcCall.queryCoinCenterPage()
            }.onFailure {
                Log.printStackTrace(TAG, "refreshSportsEnergyExchangeOptionsForSettings.queryCoinCenterPage err:", it)
            }
            runCatching {
                AntSportsRpcCall.NeverlandRpcCall.deliverSportsItemMallPage()
            }.onFailure {
                Log.printStackTrace(TAG, "refreshSportsEnergyExchangeOptionsForSettings.deliverByPageId err:", it)
            }
            runCatching {
                val categoryResp = JSONObject(AntSportsRpcCall.NeverlandRpcCall.queryItemCategoryList())
                if (ResChecker.checkRes(TAG, "运动能量兑换分类查询失败:", categoryResp)) {
                    val categoryList = extractSportsItemMallData(categoryResp).optJSONArray("itemCategoryVOList")
                    if (categoryList != null) {
                        for (i in 0 until categoryList.length()) {
                            val categoryCode = categoryList.optJSONObject(i)?.optString("categoryCode").orEmpty()
                            if (categoryCode.isNotBlank()) {
                                categoryTypes.add(categoryCode)
                            }
                        }
                    }
                }
            }.onFailure {
                Log.printStackTrace(TAG, "refreshSportsEnergyExchangeOptionsForSettings.queryItemCategoryList err:", it)
            }

            val exchangeMap = IdMapManager.getInstance(SportsEnergyExchangeMap::class.java)
            val candidateMap = LinkedHashMap<String, SportsEnergyExchangeCandidate>()
            categoryTypes.forEach { categoryType ->
                var pageNum = 1
                var adSession = ""
                while (pageNum <= 3) {
                    val response = JSONObject(
                        AntSportsRpcCall.NeverlandRpcCall.queryItemList(
                            categoryType = categoryType,
                            pageNum = pageNum,
                            cityCode = SPORT_ITEM_MALL_CITY_CODE,
                            adSession = adSession
                        )
                    )
                    if (!ResChecker.checkRes(TAG, "运动能量兑换列表查询失败:", response)) {
                        break
                    }
                    val data = extractSportsItemMallData(response)
                    val itemList = data.optJSONArray("itemVOList") ?: break
                    for (i in 0 until itemList.length()) {
                        val candidate = buildSportsEnergyExchangeCandidate(itemList.optJSONObject(i) ?: continue) ?: continue
                        candidateMap.putIfAbsent(candidate.item.id, candidate)
                    }
                    if (!data.optBoolean("hasMore", false)) {
                        break
                    }
                    adSession = data.optString("adSession", adSession)
                    pageNum++
                }
            }
            if (candidateMap.isEmpty()) {
                exchangeMap.save(UserMap.currentUid)
                Log.sports("运动能量兑换🎁未获取到候选列表")
                return emptyList()
            }
            val candidates = candidateMap.values.toList()
            candidates.forEach { candidate ->
                exchangeMap.add(candidate.item.id, candidate.item.displayName())
            }
            exchangeMap.save(UserMap.currentUid)
            Log.sports("运动能量兑换🎁设置页刷新列表#${candidates.size}")
            return candidates
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "refreshSportsEnergyExchangeOptionsForSettings err:", t)
            return emptyList()
        }
    }

    private fun sportsEnergyExchange() {
        try {
            val selectedIds: Set<String> = sportsEnergyExchangeList.value
                ?.filterNotNull()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
            val candidates = refreshSportsEnergyExchangeOptionsForSettings()
            if (candidates.isEmpty()) {
                return
            }
            val remainingSelectedIds: MutableSet<String>? = if (selectedIds.isNotEmpty()) selectedIds.toMutableSet() else null
            for (candidate in candidates) {
                if (!selectedIds.contains(candidate.item.id)) {
                    continue
                }
                remainingSelectedIds?.remove(candidate.item.id)
                if (candidate.item.safety == ExchangeSafety.UNAVAILABLE) {
                    Log.sports("运动能量兑换🎁跳过[${candidate.item.displayName()}]#${candidate.item.safetyReason}")
                } else {
                    if (candidate.item.safety == ExchangeSafety.LOG_ONLY) {
                        Log.sports("运动能量兑换🎁已勾选[${candidate.item.displayName()}]#仅提醒，不自动兑换")
                    } else {
                        exchangeSportsEnergyCandidate(candidate)
                    }
                }
            }
            remainingSelectedIds
                ?.forEach { Log.sports("运动能量兑换🎁已勾选[$it]#本次列表未返回，保留配置不删除") }
            Log.sports("运动能量兑换🎁列表刷新完成#${candidates.size}")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "sportsEnergyExchange err:", t)
        }
    }

    internal fun refreshSportsEnergyExchangeOptionsForRemote() {
        refreshSportsEnergyExchangeOptionsForSettings()
    }

    private fun exchangeSportsEnergyCandidate(candidate: SportsEnergyExchangeCandidate) {
        runCatching {
            AntSportsRpcCall.NeverlandRpcCall.deliverSportsItemMallPage("@alipay/health-island/goodsDetail")
        }.onFailure {
            Log.printStackTrace(TAG, "exchangeSportsEnergyCandidate.deliverGoodsDetail err:", it)
        }
        val detailResp = JSONObject(
            AntSportsRpcCall.NeverlandRpcCall.queryItemDetail(
                benefitId = candidate.benefitId,
                itemId = candidate.itemId,
                materialType = candidate.materialType,
                cityCode = candidate.cityCode
            )
        )
        if (!ResChecker.checkRes(TAG, "运动能量兑换详情查询失败:", detailResp)) {
            Log.sports("运动能量兑换🎁兑换前详情校验失败[${candidate.item.name}]")
            return
        }
        val detail = extractSportsItemMallData(detailResp).optJSONObject("itemDetailVO")
        val verifiedCandidate = detail?.let { buildSportsEnergyExchangeCandidate(it) } ?: candidate
        if (verifiedCandidate.item.safety != ExchangeSafety.AUTO) {
            Log.sports("运动能量兑换🎁跳过[${verifiedCandidate.item.displayName()}]#${verifiedCandidate.item.safetyReason}")
            return
        }
        val orderResp = JSONObject(
            AntSportsRpcCall.NeverlandRpcCall.createOrder(
                benefitId = verifiedCandidate.benefitId,
                itemId = verifiedCandidate.itemId,
                cityCode = verifiedCandidate.cityCode
            )
        )
        if (ResChecker.checkRes(TAG, "运动能量兑换下单失败:", orderResp)) {
            val purchaseType = extractSportsItemMallData(orderResp).optString("purchaseType").ifBlank { "purePoint" }
            Log.sports("运动能量兑换🎁兑换[${verifiedCandidate.item.name}]#$purchaseType")
            runCatching {
                AntSportsRpcCall.NeverlandRpcCall.collectExchangeData(cityCode = verifiedCandidate.cityCode)
            }.onFailure {
                Log.printStackTrace(TAG, "exchangeSportsEnergyCandidate.collectData err:", it)
            }
        } else {
            Log.sports("运动能量兑换🎁兑换失败[${verifiedCandidate.item.name}]#$orderResp")
        }
    }

    private fun querySportsExchangeNeedEnergyValue(source: String): String {
        return try {
            val response = JSONObject(AntSportsRpcCall.NeverlandRpcCall.queryExchangeCondition(source))
            if (!ResChecker.checkRes(TAG, response)) {
                return "1"
            }
            val data = response.optJSONObject("data") ?: response.optJSONObject("result") ?: response
            sequenceOf(
                data.optString("needEnergyValue"),
                data.optString("minEnergyValue"),
                data.optString("assetAmount")
            ).firstOrNull { it.isNotBlank() } ?: "1"
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "querySportsExchangeNeedEnergyValue err:", t)
            "1"
        }
    }

    private fun buildSportsEnergyExchangeCandidate(raw: JSONObject): SportsEnergyExchangeCandidate? {
        val benefitId = raw.optString("benefitId").trim()
        val itemId = raw.optString("itemId").trim()
        if (benefitId.isEmpty() && itemId.isEmpty()) {
            return null
        }
        val stableId = listOf(benefitId, itemId).filter { it.isNotBlank() }.joinToString("|")
        val name = raw.optString("itemName", raw.optString("benefitName", stableId))
        val status = raw.optString("status")
        val remainCount = raw.optInt("remainCount", -1)
        val tagText = collectSportsEnergyExchangeTags(raw.optJSONArray("tagValues"))
        val salePoint = raw.optString("salePoint")
        val displayPrice = raw.optString("displayPrice")
        val displaySalePrice = raw.optString("displaySalePrice")
        val materialType = raw.optString("materialType")
        val itemSpecialType = raw.optString("itemSpecialType")
        val unavailable = status.isNotBlank() && !status.equals("ITEM_SALE", true) ||
            remainCount == 0
        val hasCashPrice = hasPositiveSportsCash(displaySalePrice, raw.optString("salePrice")) ||
            displayPrice.contains("元")
        val manualMaterial = materialType.isBlank() || !materialType.equals("benefitItem", true)
        val manualSpecialType = itemSpecialType.equals("goods", true) ||
            itemSpecialType.equals("physical", true) ||
            itemSpecialType.equals("platformPhysicalItem", true)
        val orderLike = containsSportsOrderKeyword(
            name,
            materialType,
            itemSpecialType,
            raw.optString("displayDesc"),
            raw.optString("lMItemVO"),
            raw.optString("externJumpUrl")
        )
        val safety = when {
            unavailable -> ExchangeSafety.UNAVAILABLE
            hasCashPrice || manualMaterial || manualSpecialType || orderLike -> ExchangeSafety.LOG_ONLY
            else -> ExchangeSafety.AUTO
        }
        val safetyReason = when {
            unavailable -> when {
                remainCount == 0 -> "库存不足"
                status.isNotBlank() -> status
                else -> "服务端状态不可兑换"
            }
            hasCashPrice -> "涉及实付金额"
            manualMaterial || manualSpecialType || orderLike -> "商品/下单链路需手动处理"
            else -> ""
        }
        return SportsEnergyExchangeCandidate(
            ExchangeItem(
                id = stableId,
                name = name,
                cost = ExchangeCost(
                    pointText = displayPrice.ifBlank { salePoint.takeIf { it.isNotBlank() }?.let { "${it}能量" }.orEmpty() },
                    cashText = displaySalePrice.takeIf { hasPositiveSportsCash(it) }?.let { "${it}元" }.orEmpty()
                ),
                limit = ExchangeLimit(
                    stockText = remainCount.takeIf { it >= 0 }?.let { "库存$it" }.orEmpty(),
                    validText = raw.optString("strInvalidDate"),
                    statusText = listOf(status, tagText).filter { it.isNotBlank() }.joinToString("、")
                ),
                safety = safety,
                safetyReason = safetyReason
            ),
            benefitId = benefitId,
            itemId = itemId,
            materialType = materialType,
            cityCode = SPORT_ITEM_MALL_CITY_CODE
        )
    }

    private fun extractSportsItemMallData(response: JSONObject): JSONObject {
        return response.optJSONObject("data")
            ?: response.optJSONObject("result")
            ?: response
    }

    private fun hasPositiveSportsCash(vararg rawValues: String?): Boolean {
        return rawValues.any { value ->
            value?.trim().orEmpty().toBigDecimalOrNull()?.signum() == 1
        }
    }

    private fun containsSportsOrderKeyword(vararg textValues: String?): Boolean {
        val text = textValues.joinToString(" ").lowercase(Locale.getDefault())
        return listOf("收货", "发货", "下单", "商品详情", "邮寄", "快递", "付邮", "邮费", "订单", "goods")
            .any { text.contains(it.lowercase(Locale.getDefault())) }
    }

    private fun collectSportsEnergyExchangeTags(tags: JSONArray?): String {
        if (tags == null || tags.length() == 0) {
            return ""
        }
        val values = mutableListOf<String>()
        for (i in 0 until tags.length()) {
            val value = tags.optJSONObject(i)?.optString("value").orEmpty()
            if (value.isNotBlank()) {
                values.add(value)
            }
        }
        return values.joinToString("、")
    }

    private fun tryBeginSyncStepTask(): Boolean {
        synchronized(syncStepLock) {
            if (syncStepInProgress ||
                hasChildTask(SYNC_STEP_CHILD_TASK_ID) ||
                Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_SYNC_STEP_DONE)
            ) {
                return false
            }
            syncStepInProgress = true
            return true
        }
    }

    private fun finishSyncStepTask() {
        synchronized(syncStepLock) {
            syncStepInProgress = false
        }
    }

    private fun markSyncStepDone() {
        synchronized(syncStepLock) {
            Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_SYNC_STEP_DONE)
        }
    }

    private fun canOverrideDailyStepByHook(): Boolean {
        synchronized(syncStepLock) {
            return !Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_SYNC_STEP_DONE)
        }
    }

    internal fun isEnergyOnlyModeNow(): Boolean {
        TaskCommon.update()
        return TaskCommon.IS_ENERGY_TIME
    }

    private fun persistentSportsDedupeKey(childId: String): String {
        val owner = UserMap.currentUid?.takeIf { it.isNotBlank() } ?: "default"
        return "sports_child_${owner}::$childId"
    }

    internal fun registerPersistentChildTask(
        childId: String,
        group: String,
        triggerAtMs: Long,
        extraPayload: JSONObject = JSONObject()
    ) {
        val context = ApplicationHook.appContext ?: return
        if (triggerAtMs <= System.currentTimeMillis()) return
        try {
            val payload = JSONObject(extraPayload.toString())
                .put("child_kind", PERSISTENT_CHILD_KIND)
                .put("child_id", childId)
                .put("group", group)
                .put("launch_target", true)
            UserMap.currentUid?.takeIf { it.isNotBlank() }?.let { payload.put("owner_user_id", it) }

            UnifiedScheduler.schedulePersistentTrigger(
                context = context,
                name = "运动子任务:$group",
                kind = PersistentScheduleKind.MODULE_CHILD,
                triggerAtMs = triggerAtMs,
                dedupeKey = persistentSportsDedupeKey(childId),
                payloadJson = payload.toString(),
                toleranceMs = PersistentScheduleDefaults.DEFAULT_TOLERANCE_MS,
                ownerUserId = UserMap.currentUid
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "注册运动持久子任务失败[$group][$childId]", t)
        }
    }

    internal fun cancelPersistentChildTask(childId: String) {
        UnifiedScheduler.cancelPersistentByDedupeKey(ApplicationHook.appContext, persistentSportsDedupeKey(childId))
    }

    internal fun triggerPersistentChildTask(childId: String, group: String, payloadJson: String, source: String): Boolean {
        val payload = runCatching { JSONObject(payloadJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val ownerUserId = payload.optString("owner_user_id").trim()
        if (ownerUserId.isNotBlank() && ownerUserId != UserMap.currentUid) {
            Log.sports("运动持久子任务[$group][$childId]账号不匹配，跳过: owner=$ownerUserId current=${UserMap.currentUid}")
            return true
        }
        if (!isEnable()) {
            Log.sports("运动持久子任务[$group][$childId]触发时模块已关闭，跳过")
            return true
        }
        GlobalThreadPools.execute {
            runPersistentChildTask(childId, group, payload, source)
        }
        return true
    }

    private fun runPersistentChildTask(childId: String, group: String, payload: JSONObject, source: String) {
        try {
            Log.sports("运动持久子任务触发[$group][$childId] source=$source")
            cancelPersistentChildTask(childId)
            when (group) {
                "syncStep" -> runPersistentSyncStepTask(childId)
                "BX" -> runTreasureBoxPersistentTask(childId, payload)
                else -> Log.sports("未知运动持久子任务[$group][$childId]，跳过")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "运动持久子任务执行失败[$group][$childId]", t)
        }
    }

    /**
     * 步数同步任务
     */
    internal fun syncStepTask() {
        if (isEnergyOnlyModeNow()) {
            return
        }

        if (!tryBeginSyncStepTask()) {
            return
        }

        addChildTask(
            ChildModelTask(
                SYNC_STEP_CHILD_TASK_ID,
                Runnable {
                    try {
                        cancelPersistentChildTask(SYNC_STEP_CHILD_TASK_ID)
                        val customStep = tmpStepCount()
                        if (customStep <= 0) {
                            Log.sports("同步步数已关闭，跳过主动同步")
                            markSyncStepDone()
                            return@Runnable
                        }

                        val loader = ApplicationHook.classLoader
                        if (loader == null) {
                            Log.error(TAG, "ClassLoader is null, 跳过同步步数")
                            return@Runnable
                        }

                        val originStep = queryCurrentWalkStepCount()
                            ?: cachedOriginDailyStep.takeIf { it >= 0 }
                            ?: 0
                        val targetStep = resolveTargetDailyStep(originStep)
                        if (targetStep <= originStep) {
                            Log.sports("同步步数无需处理[原始=${originStep}步, 自定义=${customStep}步, 目标=${targetStep}步]"
                            )
                            markSyncStepDone()
                            return@Runnable
                        }

                        if (Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_SYNC_STEP_DONE)) {
                            Log.sports("今日步数已由其他入口同步，跳过主动提交")
                            return@Runnable
                        }

                        if (syncStepByRpcManager(loader, targetStep)) {
                            val confirmedStep = queryCurrentWalkStepCount()
                            if (confirmedStep != null && confirmedStep >= targetStep) {
                                Log.sports("同步步数🏃🏻‍♂️[原始${originStep}步 + 自定义${targetStep - originStep}步 = ${targetStep}步]")
                                markSyncStepDone()
                            } else {
                                Log.sports("同步步数提交返回成功但查询未确认[当前=${confirmedStep ?: "未知"}步, 目标=${targetStep}步]，暂不标记今日已同步"
                                )
                            }
                        } else {
                            Log.sports("主动同步入口未匹配，保留 readDailyStep Hook 等待运动页读取[原始=${originStep}步, 目标=${targetStep}步]"
                            )
                        }
                    } catch (t: Throwable) {
                        Log.printStackTrace(TAG, t)
                    } finally {
                        finishSyncStepTask()
                    }
                }
            )
        )
    }

    private fun runPersistentSyncStepTask(childId: String) {
        if (isEnergyOnlyModeNow()) {
            if (!isSyncStepEnabled() || Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_SYNC_STEP_DONE)) {
                return
            }
            val token = if (::earliestSyncStepTime.isInitialized) {
                earliestSyncStepTime.getHourToken() ?: "delayed"
            } else {
                "delayed"
            }
            registerPersistentSyncStepTask(
                System.currentTimeMillis() + PersistentScheduleDefaults.DEFAULT_TOLERANCE_MS,
                token
            )
            Log.sports("⏸ 当前为只收能量时间，延后运动持久同步步数[$childId]")
            return
        }
        syncStepTask()
    }

    /**
     * @brief 计算今日用于同步的随机步数
     *
     * @return 步数值（最大 100000）
     */
    fun tmpStepCount(): Int {
        if (tmpStepCount >= 0) {
            return tmpStepCount
        }
        tmpStepCount = (syncStepCount.value ?: 0).coerceIn(0, 100_000)
        if (tmpStepCount > 0) {
            tmpStepCount = RandomUtil.nextInt(tmpStepCount, tmpStepCount + 2000)
            if (tmpStepCount > 100_000) {
                tmpStepCount = 100_000
            }
        }
        return tmpStepCount
    }

    private fun rememberCurrentDailyStep(originStep: Int) {
        if (originStep < 0) {
            return
        }
        val safeOriginStep = originStep.coerceAtLeast(0)
        if (cachedTargetDailyStep > 0 && safeOriginStep >= cachedTargetDailyStep) {
            return
        }
        if (safeOriginStep > cachedOriginDailyStep) {
            cachedOriginDailyStep = safeOriginStep
            cachedTargetDailyStep = -1
        }
    }

    private fun resolveTargetDailyStep(originStep: Int): Int {
        val customStep = tmpStepCount()
        val safeOriginStep = originStep.coerceAtLeast(0)
        if (customStep <= 0) {
            return safeOriginStep
        }
        if (cachedTargetDailyStep > 0 && safeOriginStep >= cachedTargetDailyStep) {
            return safeOriginStep
        }
        if (cachedOriginDailyStep < 0 || safeOriginStep > cachedOriginDailyStep) {
            cachedOriginDailyStep = safeOriginStep
            cachedTargetDailyStep = -1
        }
        if (cachedTargetDailyStep < 0) {
            cachedTargetDailyStep = (cachedOriginDailyStep + customStep).coerceAtMost(100_000)
        }
        return cachedTargetDailyStep.coerceAtLeast(safeOriginStep)
    }

    internal fun isSyncStepEnabled(): Boolean {
        return (syncStepCount.value ?: 0) > 0
    }

    internal fun registerPersistentSyncStepTask() {
        if (!isSyncStepEnabled() ||
            Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_SYNC_STEP_DONE) ||
            !::earliestSyncStepTime.isInitialized
        ) {
            cancelPersistentChildTask(SYNC_STEP_CHILD_TASK_ID)
            return
        }
        val token = earliestSyncStepTime.getHourToken() ?: run {
            cancelPersistentChildTask(SYNC_STEP_CHILD_TASK_ID)
            return
        }
        if (token == "2400") {
            cancelPersistentChildTask(SYNC_STEP_CHILD_TASK_ID)
            return
        }
        val triggerAtMs = TimeUtil.getTodayCalendarByTimeStr(token)?.timeInMillis ?: return
        if (triggerAtMs <= System.currentTimeMillis()) {
            return
        }
        registerPersistentSyncStepTask(triggerAtMs, token)
    }

    private fun registerPersistentSyncStepTask(triggerAtMs: Long, token: String) {
        registerPersistentChildTask(
            SYNC_STEP_CHILD_TASK_ID,
            "syncStep",
            triggerAtMs,
            JSONObject().put("sync_step_time", token)
        )
    }

    private fun getTrainFriendZeroCoinLimit(): Int? {
        if (trainFriend.value != true) {
            return null
        }
        val maxCount = zeroCoinLimit.value ?: return null
        return maxCount.takeIf { it > 0 }
    }

    private fun hasReachedTrainFriendZeroCoinLimit(): Boolean {
        val maxCount = getTrainFriendZeroCoinLimit() ?: return false
        return zeroTrainCoinCount >= maxCount
    }

    private fun shouldOverrideDailyStep(originStep: Int, targetStep: Int): Boolean {
        if (targetStep <= 0 ||
            originStep >= targetStep
        ) {
            return false
        }
        if (isEnergyOnlyModeNow()) {
            return false
        }

        return if (::earliestSyncStepTime.isInitialized) {
            earliestSyncStepTime.hasReachedToday()
        } else {
            HourOfDayModelField("defaultEarliestSyncStepTime", "默认最早同步时间", "-1", allowDisable = true).hasReachedToday()
        }
    }

    private fun queryCurrentWalkStepCount(): Int? {
        return try {
            val response = JSONObject(AntSportsRpcCall.queryWalkStep())
            if (!ResChecker.checkRes(TAG, response)) {
                Log.sports("查询当前步数失败，回退到 Hook 实时步数")
                null
            } else {
                val currentStep = AntSportsRpcCall.extractWalkStepCount(response).coerceAtLeast(0)
                rememberCurrentDailyStep(currentStep)
                currentStep
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryCurrentWalkStepCount err:", t)
            null
        }
    }

    private fun syncStepByRpcManager(loader: ClassLoader, step: Int): Boolean {
        val candidateClassNames = listOf(
            "com.alibaba.health.pedometer.intergation.rpc.RpcManager",
            "com.alibaba.health.pedometer.integration.rpc.RpcManager"
        )

        for (className in candidateClassNames) {
            val rpcManagerClass = runCatching { loader.loadClass(className) }.getOrNull() ?: continue
            val syncMethods = findSyncStepMethods(rpcManagerClass)
            if (syncMethods.isEmpty()) {
                continue
            }

            for (method in syncMethods) {
                val targets = if (Modifier.isStatic(method.modifiers)) {
                    listOf<Any?>(null)
                } else {
                    collectRpcManagerInstances(rpcManagerClass)
                }

                for (target in targets) {
                    if (invokeSyncStepMethod(method, target, step)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun findSyncStepMethods(clazz: Class<*>): List<Method> {
        return (clazz.declaredMethods.toList() + clazz.methods.toList())
            .distinctBy { method ->
                buildString {
                    append(method.name)
                    append('#')
                    append(method.parameterTypes.joinToString(",") { it.name })
                }
            }
            .filter { method ->
                val parameterTypes = method.parameterTypes
                if (parameterTypes.size != 3) {
                    return@filter false
                }

                val firstArgMatches = parameterTypes[0] == Int::class.javaPrimitiveType ||
                    parameterTypes[0] == Int::class.javaObjectType ||
                    parameterTypes[0] == Long::class.javaPrimitiveType ||
                    parameterTypes[0] == Long::class.javaObjectType
                val secondArgMatches = parameterTypes[1] == Boolean::class.javaPrimitiveType ||
                    parameterTypes[1] == Boolean::class.javaObjectType
                val thirdArgMatches = parameterTypes[2] == String::class.java ||
                    CharSequence::class.java.isAssignableFrom(parameterTypes[2]) ||
                    parameterTypes[2] == Any::class.java

                firstArgMatches && secondArgMatches && thirdArgMatches
            }
            .sortedByDescending(::scoreSyncStepMethod)
    }

    private fun scoreSyncStepMethod(method: Method): Int {
        var score = 0
        if (method.returnType == Boolean::class.javaPrimitiveType ||
            method.returnType == Boolean::class.javaObjectType
        ) {
            score += 4
        }
        if (method.name == "a") {
            score += 2
        }
        if (Modifier.isPublic(method.modifiers)) {
            score += 1
        }
        return score
    }

    private fun collectRpcManagerInstances(clazz: Class<*>): List<Any?> {
        val instances = LinkedHashSet<Any?>()

        runCatching {
            val instanceField = clazz.getDeclaredField("INSTANCE")
            if (Modifier.isStatic(instanceField.modifiers)) {
                instanceField.isAccessible = true
                instances.add(instanceField.get(null))
            }
        }

        val singletonMethodNames = setOf("a", "getInstance", "instance")
        (clazz.declaredMethods.toList() + clazz.methods.toList())
            .filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    method.returnType != Void.TYPE &&
                    (method.name in singletonMethodNames || clazz.isAssignableFrom(method.returnType))
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    instances.add(method.invoke(null))
                }
            }

        runCatching {
            val constructor = clazz.getDeclaredConstructor()
            constructor.isAccessible = true
            instances.add(constructor.newInstance())
        }

        return instances.filterNotNull()
    }

    private fun invokeSyncStepMethod(method: Method, target: Any?, step: Int): Boolean {
        return runCatching {
            method.isAccessible = true
            val stepArg: Any = when (method.parameterTypes[0]) {
                Long::class.javaPrimitiveType, Long::class.javaObjectType -> step.toLong()
                else -> step
            }
            val result = method.invoke(target, stepArg, false, "system")
            when (result) {
                is Boolean -> result
                null -> method.returnType == Void.TYPE
                else -> true
            }
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------------
    // 运动任务面板
    // ---------------------------------------------------------------------

    /**
     * @brief 处理运动任务面板中的任务（含签到、完成、领奖）
     */
    private fun sportsTasks() {
        try {
            if (!Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_DAILY_TASKS_DONE)) {
                sportsTaskPanel()
            } else {
                Log.sports("运动任务面板[今日完成标记已存在，跳过面板闭环]")
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
        }
        motionDailyQuiz()
    }

    private fun sportsTaskPanel() {
        sportsCheckIn()
        TaskFlowEngine(SportsPanelTaskFlowAdapter()).run()
    }

    private inner class SportsPanelTaskFlowAdapter : TaskFlowAdapter {
        override val moduleName: String = SPORTS_TASK_BLACKLIST_MODULE
        override val flowName: String = "运动任务面板"

        override fun query(): JSONObject {
            return JSONObject(AntSportsRpcCall.queryCoinTaskPanel())
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return ResChecker.checkRes(TAG, response)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val taskList = response.optJSONObject("data")?.optJSONArray("taskList") ?: return emptyList()
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until taskList.length()) {
                val taskDetail = taskList.optJSONObject(i) ?: continue
                val taskId = taskDetail.optString("taskId", "").trim()
                val taskName = taskDetail.optString("taskName", taskId).trim()
                val currentNum = taskDetail.optInt("currentNum", 0)
                val limitConfigNum = taskDetail.optInt("limitConfigNum", currentNum + 1)
                items.add(
                    TaskFlowItem(
                        id = taskId,
                        title = taskName.ifBlank { taskId },
                        status = taskDetail.optString("taskStatus", ""),
                        type = taskDetail.optString("taskType", ""),
                        sceneCode = taskDetail.optString("sceneCode", ""),
                        actionType = taskDetail.optString("taskAction", ""),
                        blacklistKeys = listOf(taskId, taskName).filter { it.isNotBlank() },
                        raw = taskDetail,
                        progress = "$currentNum/$limitConfigNum",
                        current = currentNum,
                        limit = limitConfigNum
                    )
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            return when (item.status) {
                "HAS_RECEIVED" -> TaskFlowPhase.TERMINAL
                "WAIT_RECEIVE" -> TaskFlowPhase.REWARD_READY
                "WAIT_COMPLETE" -> TaskFlowPhase.READY_TO_COMPLETE
                else -> TaskFlowPhase.UNKNOWN
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            return item.type == "SETTLEMENT"
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            val taskDetail = item.raw ?: return missingRawResult(item, "receive")
            return receiveTaskReward(taskDetail, item.title)
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val taskDetail = item.raw ?: return missingRawResult(item, "complete")
            return completeTask(taskDetail, item.title)
        }

        override fun estimateRoundLimit(items: List<TaskFlowItem>): Int {
            var visibleTaskCount = 0
            var pendingTransitions = 0
            for (item in items) {
                if (isBlacklisted(item) || shouldSkip(item)) continue
                visibleTaskCount++
                when (item.status) {
                    "WAIT_RECEIVE" -> pendingTransitions += 1
                    "WAIT_COMPLETE" -> {
                        val currentNum = item.current ?: 0
                        val limitConfigNum = item.limit ?: (currentNum + 1)
                        val remainingNum = max(1, limitConfigNum - currentNum)
                        pendingTransitions += remainingNum * 2
                    }
                }
            }
            return max(1, pendingTransitions + visibleTaskCount)
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            val taskDetail = item.raw ?: return super<TaskFlowAdapter>.actionKey(item, action)
            return when (action) {
                TaskFlowAction.RECEIVE -> "receive:${buildSportsPanelReceiveKey(taskDetail, item.id)}"
                TaskFlowAction.COMPLETE -> "complete:${buildSportsPanelCompleteKey(taskDetail, item.id)}"
                else -> super<TaskFlowAdapter>.actionKey(item, action)
            }
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            if (ApplicationHookConstants.isOffline()) {
                Log.sports("⏸ 检测到离线模式，不设置运动任务今日完成标识")
                return
            }
            val verifyResponse = try {
                query()
            } catch (t: Throwable) {
                Log.error(TAG, "运动任务面板完成复核查询异常：${t.message}")
                return
            }
            if (!isQuerySuccess(verifyResponse)) {
                Log.error(TAG, "运动任务面板完成复核查询失败 raw=$verifyResponse")
                return
            }
            val pendingTasks = extractItems(verifyResponse)
                .filter { item ->
                    !isBlacklisted(item) &&
                        !shouldSkip(item) &&
                        item.status in setOf("WAIT_COMPLETE", "WAIT_RECEIVE")
                }
            if (pendingTasks.isNotEmpty()) {
                val pendingText = pendingTasks
                    .take(6)
                    .joinToString("；") {
                        "${it.title}(taskId=${it.id.ifBlank { "UNKNOWN" }}, status=${it.status})"
                    }
                val suffix = if (pendingTasks.size > 6) "；... 共${pendingTasks.size}个" else ""
                Log.sports("运动任务面板[完成复核仍有待处理，暂不设置今日完成标记] $pendingText$suffix")
                return
            }
            val today = TimeUtil.getDateStr2()
            DataStore.put(SPORTS_TASKS_COMPLETED_DATE, today)
            Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_DAILY_TASKS_DONE)
            Log.sports("✅ 所有运动任务已完成，今日不再执行")
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.error(TAG, "运动任务面板查询失败 raw=$response")
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision
        ) {
            if (result.stopCurrentRound) {
                Log.sports("运动任务面板[本轮止损：检测到离线/验证类错误，停止继续执行剩余浏览任务]")
            }
        }

        override fun logInfo(message: String) {
            Log.sports(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun missingRawResult(item: TaskFlowItem, action: String): TaskFlowActionResult {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "缺少任务原始数据",
                rpc = "SportsPanelTaskFlowAdapter.$action",
                detail = "taskId=${item.id} taskName=${item.title} status=${item.status}"
            )
        }
    }

    private fun motionDailyQuiz() {
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_MOTION_DAILY_QUIZ_DONE)) {
            return
        }
        try {
            val gmtStartAnswer = System.currentTimeMillis()
            val queryResponse = JSONObject(AntSportsRpcCall.queryMotionQuizBlockDetail(gmtStartAnswer))
            if (!ResChecker.checkRes(TAG, queryResponse)) {
                Log.error(TAG, "运动问答查询失败 raw=$queryResponse")
                return
            }

            val quizActivityList = findFirstJsonArrayByKey(queryResponse, "quizActivityList")
            if (quizActivityList == null) {
                Log.error(TAG, "运动问答查询响应缺少 quizActivityList raw=$queryResponse")
                return
            }
            if (quizActivityList.length() == 0) {
                markMotionDailyQuizDone("无可用题目")
                return
            }

            val quiz = quizActivityList.optJSONObject(0)
            if (quiz == null) {
                Log.error(TAG, "运动问答题目结构异常 raw=${quizActivityList.opt(0)}")
                return
            }

            val quizId = extractMotionQuizId(quiz)
            val title = quiz.optString("title", "").trim()
            val answerList = parseMotionQuizAnswerList(quiz.optJSONArray("answerList"))
            if (quizId.isBlank() || title.isBlank() || answerList.isEmpty()) {
                Log.error(
                    TAG,
                    "运动问答题目解析失败[quizId=$quizId][title=$title][answers=${answerList.size}] raw=$quiz"
                )
                return
            }

            when (extractMotionQuizUseRecordBizResult(quiz)) {
                true -> {
                    Log.sports("运动问答已答对[$title]，继续确认奖励")
                    handleMotionQuizAward(quizId)
                    return
                }

                false -> {
                    markMotionDailyQuizDone("已答过但结果非正确/未命中奖励")
                    return
                }

                null -> Unit
            }

            val answer = chooseMotionQuizAnswer(title, answerList, quiz.optString("answerResult", ""))
            if (answer.isBlank()) {
                Log.error(TAG, "运动问答无可提交答案[quizId=$quizId][title=$title]")
                return
            }

            val answerResponse = JSONObject(AntSportsRpcCall.answerMotionQuiz(quizId, answer, gmtStartAnswer))
            if (!ResChecker.checkRes(TAG, answerResponse)) {
                Log.error(TAG, "运动问答答题失败[quizId=$quizId][answer=$answer] raw=$answerResponse")
                return
            }

            when (extractMotionQuizUseRecordBizResult(answerResponse)) {
                true -> {
                    AnswerAI.rememberAnswer(title, answerList, answer, LogChannel.SPORTS.loggerName)
                    Log.sports("运动问答答题成功[$title][$answer]")
                    handleMotionQuizAward(quizId)
                }

                false -> {
                    AnswerAI.removeCachedAnswer(title, LogChannel.SPORTS.loggerName)
                    markMotionDailyQuizDone("答题结果非正确/未命中奖励")
                }

                null -> {
                    Log.error(TAG, "运动问答答题响应缺少 useRecord.bizResult raw=$answerResponse")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "motionDailyQuiz err:", t)
        }
    }

    private fun markMotionDailyQuizDone(reason: String) {
        Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_MOTION_DAILY_QUIZ_DONE)
        Log.sports("运动问答[$reason]，今日不再重复处理")
    }

    private fun extractMotionQuizId(quiz: JSONObject): String {
        return quiz.optString("quizId", "").trim().ifBlank {
            quiz.optString("id", "").trim()
        }
    }

    private fun parseMotionQuizAnswerList(answerArray: JSONArray?): List<String> {
        if (answerArray == null) {
            return emptyList()
        }
        val answers = mutableListOf<String>()
        for (i in 0 until answerArray.length()) {
            val answerText = when (val item = answerArray.opt(i)) {
                is JSONObject -> extractMotionQuizAnswerText(item)
                null -> ""
                else -> item.toString()
            }.trim()
            if (answerText.isNotBlank()) {
                answers.add(answerText)
            }
        }
        return answers.distinct()
    }

    private fun extractMotionQuizAnswerText(answer: JSONObject): String {
        return sequenceOf("answer", "content", "title", "name", "label", "value", "text")
            .map { answer.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun chooseMotionQuizAnswer(title: String, answerList: List<String>, answerResult: String): String {
        val serverAnswer = matchMotionQuizAnswer(answerResult, answerList)
        if (serverAnswer.isNotBlank()) {
            Log.sports("运动问答使用服务端答案[$title][$serverAnswer]")
            return serverAnswer
        }
        if (answerResult.isNotBlank()) {
            Log.error(TAG, "运动问答服务端答案未匹配选项[$title][answerResult=$answerResult][answerList=$answerList]")
        }

        val aiAnswer = AnswerAI.getAnswer(title, answerList, LogChannel.SPORTS.loggerName)
        return matchMotionQuizAnswer(aiAnswer, answerList).ifBlank {
            answerList.firstOrNull().orEmpty()
        }
    }

    private fun matchMotionQuizAnswer(answer: String, answerList: List<String>): String {
        val normalizedAnswer = normalizeMotionQuizAnswer(answer)
        if (normalizedAnswer.isBlank()) {
            return ""
        }
        return answerList.firstOrNull {
            normalizeMotionQuizAnswer(it).equals(normalizedAnswer, ignoreCase = true)
        }.orEmpty()
    }

    private fun normalizeMotionQuizAnswer(answer: String): String {
        return answer.trim().replace(Regex("\\s+"), "")
    }

    private fun handleMotionQuizAward(quizId: String) {
        try {
            val awardResponse = JSONObject(AntSportsRpcCall.queryMotionQuizAward(quizId))
            if (!ResChecker.checkRes(TAG, awardResponse)) {
                Log.error(TAG, "运动问答奖励查询失败[quizId=$quizId] raw=$awardResponse")
                return
            }

            val reward = findMotionQuizEnergyReward(awardResponse)
            if (reward == null) {
                markMotionDailyQuizDone("已答题但未找到可领取健康能量奖励")
                return
            }

            val receiveResponse = JSONObject(
                AntSportsRpcCall.clickMotionQuizReceiveSort(quizId, reward.creativityId)
            )
            if (ResChecker.checkRes(TAG, receiveResponse)) {
                markMotionDailyQuizDone("奖励领取成功:${reward.title.ifBlank { reward.creativityId }}")
                return
            }

            val errorCode = extractSportsRpcErrorCode(receiveResponse)
            val errorMsg = extractSportsRpcErrorMessage(receiveResponse)
            when {
                isMotionQuizDuplicateReceive(errorCode, errorMsg) -> {
                    markMotionDailyQuizDone("奖励已领取或重复领取")
                }

                !isSportsRpcRetryable(receiveResponse) -> {
                    markMotionDailyQuizDone(
                        "奖励领取非重试业务终态:${errorCode.ifEmpty { "UNKNOWN" }}-$errorMsg"
                    )
                }

                else -> {
                    Log.error(
                        TAG,
                        "运动问答奖励领取失败[quizId=$quizId][creativityId=${reward.creativityId}]" +
                            "[code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$receiveResponse"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "handleMotionQuizAward err:", t)
        }
    }

    private fun findMotionQuizEnergyReward(response: JSONObject): MotionQuizRewardCandidate? {
        val medDistributeQueryRes = findFirstJsonObjectByKey(response, "medDistributeQueryRes") ?: return null
        val spaceInfoList = medDistributeQueryRes.optJSONArray("spaceInfoList") ?: return null
        for (i in 0 until spaceInfoList.length()) {
            val spaceInfo = spaceInfoList.optJSONObject(i) ?: continue
            val spaceObjectList = spaceInfo.optJSONArray("spaceObjectList") ?: continue
            for (j in 0 until spaceObjectList.length()) {
                val spaceObject = spaceObjectList.optJSONObject(j) ?: continue
                val bizInfo = parseMotionQuizBizInfo(spaceObject.opt("bizInfo")) ?: continue
                if (bizInfo.optString("taskId", "") != MOTION_DAILY_QUIZ_REWARD_TASK_ID) {
                    continue
                }

                val objectId = spaceObject.optString("objectId", "").trim().ifBlank {
                    spaceObject.optString("id", "").trim()
                }
                val creativityId = bizInfo.optString("creativeCode", "").trim().ifBlank { objectId }
                if (creativityId.isBlank()) {
                    Log.error(TAG, "运动问答奖励缺少 creativityId raw=$spaceObject")
                    continue
                }
                val title = sequenceOf(
                    spaceObject.optString("title", ""),
                    spaceObject.optString("name", ""),
                    spaceObject.optString("objectName", ""),
                    bizInfo.optString("title", ""),
                    bizInfo.optString("name", "")
                ).firstOrNull { it.isNotBlank() }.orEmpty()
                return MotionQuizRewardCandidate(creativityId, objectId, title)
            }
        }
        return null
    }

    private fun parseMotionQuizBizInfo(rawBizInfo: Any?): JSONObject? {
        return when (rawBizInfo) {
            is JSONObject -> rawBizInfo
            is String -> runCatching { JSONObject(rawBizInfo) }.getOrNull()
            else -> null
        }
    }

    private fun extractMotionQuizUseRecordBizResult(root: JSONObject): Boolean? {
        root.optJSONObject("useRecord")?.let { useRecord ->
            if (useRecord.has("bizResult")) {
                return useRecord.optBoolean("bizResult", false)
            }
        }
        if (root.has("bizResult")) {
            return root.optBoolean("bizResult", false)
        }
        findFirstJsonObjectByKey(root, "useRecord")?.let { useRecord ->
            if (useRecord.has("bizResult")) {
                return useRecord.optBoolean("bizResult", false)
            }
        }
        return null
    }

    private fun isMotionQuizDuplicateReceive(errorCode: String, errorMsg: String): Boolean {
        val text = "$errorCode $errorMsg"
        return text.contains("已领取") ||
            text.contains("已经领取") ||
            text.contains("重复") ||
            text.contains("already", ignoreCase = true)
    }

    private fun findFirstJsonObjectByKey(root: JSONObject, key: String): JSONObject? {
        val direct = root.opt(key)
        if (direct is JSONObject) {
            return direct
        }
        val keys = root.keys()
        while (keys.hasNext()) {
            when (val value = root.opt(keys.next())) {
                is JSONObject -> findFirstJsonObjectByKey(value, key)?.let { return it }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        value.optJSONObject(i)?.let { child ->
                            findFirstJsonObjectByKey(child, key)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun findFirstJsonArrayByKey(root: JSONObject, key: String): JSONArray? {
        val direct = root.opt(key)
        if (direct is JSONArray) {
            return direct
        }
        val keys = root.keys()
        while (keys.hasNext()) {
            when (val value = root.opt(keys.next())) {
                is JSONObject -> findFirstJsonArrayByKey(value, key)?.let { return it }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        value.optJSONObject(i)?.let { child ->
                            findFirstJsonArrayByKey(child, key)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun buildSportsPanelReceiveKey(taskDetail: JSONObject, taskId: String): String {
        val assetId = taskDetail.optString("assetId", "").trim()
        return "$taskId:${assetId.ifBlank { "NO_ASSET" }}"
    }

    private fun buildSportsPanelCompleteKey(taskDetail: JSONObject, taskId: String): String {
        val currentNum = taskDetail.optInt("currentNum", 0)
        val bizId = resolveSportsPanelAdTaskBizId(taskDetail).ifBlank { taskDetail.optString("taskAction", "") }
        return "$taskId:$currentNum:$bizId"
    }

    /**
     * @brief 领取单个任务奖励
     *
     * @param taskDetail 任务详情 JSON
     * @param taskName   任务名称
     * @return 任务流动作结果
     */
    private fun receiveTaskReward(taskDetail: JSONObject, taskName: String): TaskFlowActionResult {
        return try {
            val assetId = taskDetail.getString("assetId")
            val prizeAmount = taskDetail.getInt("prizeAmount").toString()

            val result = AntSportsRpcCall.pickBubbleTaskEnergy(assetId)
            val resultData = JSONObject(result)

            if (isSportsRpcSuccess(resultData)) {
                Log.sports("做任务得能量🎈[$taskName] +$prizeAmount 能量")
                TaskFlowActionResult.success()
            } else {
                val errorMsg = extractSportsRpcErrorMessage(resultData)
                val errorCode = extractSportsRpcErrorCode(resultData)
                if (errorCode == "RECEIVE_REWARD_REPEATED") {
                    Log.sports("做任务得能量🎈[奖励已领取：$taskName，按完成处理：${errorCode.ifEmpty { "UNKNOWN" }} - $errorMsg}]"
                    )
                    TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.TERMINAL_DONE,
                        code = errorCode,
                        message = errorMsg,
                        rpc = "AntSportsRpcCall.pickBubbleTaskEnergy",
                        raw = resultData.toString()
                    )
                } else if (errorCode == "CAMP_TRIGGER_ERROR") {
                    TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                        code = errorCode,
                        message = errorMsg,
                        rpc = "AntSportsRpcCall.pickBubbleTaskEnergy",
                        raw = resultData.toString()
                    )
                } else if (!isSportsRpcRetryable(resultData)) {
                    TaskFlowActionResult.failure(
                        failureType = classifySportsTaskFailure(resultData),
                        code = errorCode,
                        message = errorMsg,
                        rpc = "AntSportsRpcCall.pickBubbleTaskEnergy",
                        raw = resultData.toString()
                    )
                } else {
                    TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        code = errorCode,
                        message = errorMsg,
                        rpc = "AntSportsRpcCall.pickBubbleTaskEnergy",
                        raw = resultData.toString()
                    )
                }
            }
        } catch (e: Exception) {
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = e.message.orEmpty(),
                rpc = "AntSportsRpcCall.pickBubbleTaskEnergy",
                detail = "taskName=$taskName"
            )
        }
    }

    /**
     * @brief 推进一次任务完成状态
     */
    private fun completeTask(taskDetail: JSONObject, taskName: String): TaskFlowActionResult {
        return try {
            val taskId = taskDetail.getString("taskId")
            val prizeAmount = taskDetail.getString("prizeAmount")
            val currentNum = taskDetail.getInt("currentNum")
            val limitConfigNum = taskDetail.getInt("limitConfigNum")
            val remainingNum = limitConfigNum - currentNum
            val needSignUp = taskDetail.optBoolean("needSignUp", false)
            val taskAction = taskDetail.optString("taskAction", "JUMP").ifBlank { "JUMP" }
            val requestTaskType = resolveSportsPanelCompleteRequestTaskType(taskDetail, taskAction)
            val adTaskBizId = resolveSportsPanelAdTaskBizId(taskDetail)
            val adTaskFinishPayload = resolveSportsPanelAdTaskFinishPayload(taskDetail, adTaskBizId)
            val adTaskPayloadBizId = adTaskFinishPayload.optString("bizId", "").trim()

            if (remainingNum <= 0) {
                return TaskFlowActionResult.success()
            }

            // 需要先签到
            if (needSignUp) {
                if (!signUpForTask(taskId, taskName)) {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = "任务报名失败",
                        rpc = "AntSportsRpcCall.signUpTask",
                        detail = "taskId=$taskId taskName=$taskName"
                    )
                }
                GlobalThreadPools.sleepCompat(2000)
            }

            val useVerifiedNewCompleteRpc =
                taskAction.equals("SHOW_AD", ignoreCase = true) &&
                    !requestTaskType.isNullOrBlank()
            val useAdTaskFinishRpc =
                taskAction.equals("JUMP", ignoreCase = true) &&
                    taskDetail.optBoolean("adTask", false) &&
                    adTaskPayloadBizId.isNotBlank()
            val result = when {
                useVerifiedNewCompleteRpc -> JSONObject(
                    AntSportsRpcCall.completeTask(
                        taskId = taskId,
                        taskAction = taskAction,
                        taskType = requestTaskType
                    )
                )
                useAdTaskFinishRpc -> JSONObject(AntSportsRpcCall.finishAdTask(adTaskFinishPayload))
                else -> JSONObject(AntSportsRpcCall.completeExerciseTasks(taskId))
            }

            val progressText = "#(${min(currentNum + 1, limitConfigNum)}/$limitConfigNum)"
            if (isSportsRpcSuccess(result)) {
                val completeSource = when {
                    useVerifiedNewCompleteRpc -> "completeTask"
                    useAdTaskFinishRpc -> "adtask.finish"
                    else -> "completeTask(JUMP)"
                }
                Log.sports("做任务得能量🎈[完成任务：$taskName，得$prizeAmount💰，方式：$completeSource]$progressText"
                )
                TaskFlowActionResult.success()
            } else {
                val errorCode = extractSportsRpcErrorCode(result)
                val errorMsg = extractSportsRpcErrorMessage(result)
                val completeSource = when {
                    useVerifiedNewCompleteRpc -> "AntSportsRpcCall.completeTask"
                    useAdTaskFinishRpc -> "AntSportsRpcCall.finishAdTask"
                    else -> "AntSportsRpcCall.completeExerciseTasks"
                }
                if (shouldTemporarilyStopSportsTask(errorCode, errorMsg)) {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        code = errorCode,
                        message = errorMsg,
                        rpc = completeSource,
                        raw = result.toString(),
                        detail = "progress=$progressText",
                        stopCurrentRound = true
                    )
                }

                return when (classifySportsTaskFailure(result)) {
                    TaskRpcFailureType.TERMINAL_DONE -> TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.TERMINAL_DONE,
                        code = errorCode,
                        message = errorMsg,
                        rpc = completeSource,
                        raw = result.toString(),
                        detail = "progress=$progressText"
                    )
                    TaskRpcFailureType.BUSINESS_LIMIT -> TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                        code = errorCode,
                        message = errorMsg,
                        rpc = completeSource,
                        raw = result.toString(),
                        detail = "progress=$progressText"
                    )
                    TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE -> TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
                        code = errorCode,
                        message = errorMsg,
                        rpc = completeSource,
                        raw = result.toString(),
                        detail = "reason=未抓到稳定完成RPC progress=$progressText"
                    )
                    TaskRpcFailureType.NON_RETRYABLE_INVALID -> TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.NON_RETRYABLE_INVALID,
                        code = errorCode,
                        message = errorMsg,
                        rpc = completeSource,
                        raw = result.toString(),
                        detail = "progress=$progressText"
                    )
                    TaskRpcFailureType.RETRYABLE_RPC -> TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        code = errorCode,
                        message = errorMsg,
                        rpc = completeSource,
                        raw = result.toString(),
                        detail = "progress=$progressText"
                    )
                    TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW -> TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        code = errorCode,
                        message = errorMsg,
                        rpc = completeSource,
                        raw = result.toString(),
                        detail = "progress=$progressText"
                    )
                }
            }
        } catch (e: Exception) {
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = e.message.orEmpty(),
                rpc = "AntSportsRpcCall.completeExerciseTasks",
                detail = "taskName=$taskName"
            )
        }
    }

    private fun resolveSportsPanelCompleteRequestTaskType(taskDetail: JSONObject, taskAction: String): String? {
        if (taskAction.equals("SHOW_AD", ignoreCase = true) && taskDetail.optBoolean("adTask", false)) {
            return "AD_TASK"
        }
        return null
    }

    private fun resolveSportsPanelAdTaskBizId(taskDetail: JSONObject): String {
        val bizIdFromExt = taskDetail.optJSONObject("bizExtMap")
            ?.optString("bizId", "")
            ?.trim()
            .orEmpty()
        if (bizIdFromExt.isNotBlank()) {
            return bizIdFromExt
        }
        return extractDecodedUrlParam(taskDetail.optString("taskUrl", ""), "bizId").trim()
    }

    private fun resolveSportsPanelAdTaskFinishPayload(taskDetail: JSONObject, adTaskBizId: String): JSONObject {
        val payloadFromUrl = extractSportsPanelAdTaskOpParam(taskDetail.optString("taskUrl", ""))
        if (payloadFromUrl != null) {
            if (payloadFromUrl.optString("bizId", "").isBlank() && adTaskBizId.isNotBlank()) {
                payloadFromUrl.put("bizId", adTaskBizId)
            }
            return payloadFromUrl
        }

        return JSONObject().apply {
            put("bizId", adTaskBizId)
        }
    }

    private fun extractSportsPanelAdTaskOpParam(taskUrl: String): JSONObject? {
        if (taskUrl.isBlank()) return null
        val opType = extractDecodedUrlParam(taskUrl, "opType")
        if (opType != "com.alipay.adtask.biz.mobilegw.service.task.finish") {
            return null
        }
        val opParam = extractDecodedUrlParam(taskUrl, "opParam")
        if (opParam.isBlank()) return null
        return runCatching { JSONObject(opParam) }.getOrNull()
    }

    private fun extractDecodedUrlParam(rawUrl: String, paramName: String): String {
        for (candidate in listOf(rawUrl, decodeUrlComponentRepeated(rawUrl))) {
            val pattern = Regex("""(?:[?&])${Regex.escape(paramName)}=([^&#]*)""")
            val match = pattern.find(candidate) ?: continue
            return decodeUrlComponentRepeated(match.groupValues[1])
        }
        return ""
    }

    private fun decodeUrlComponentRepeated(value: String, maxRounds: Int = 3): String {
        var current = value
        repeat(maxRounds) {
            val decoded = runCatching { URLDecoder.decode(current, "UTF-8") }.getOrDefault(current)
            if (decoded == current) {
                return current
            }
            current = decoded
        }
        return current
    }

    /**
     * @brief 为任务执行报名
     */
    private fun signUpForTask(taskId: String, taskName: String): Boolean {
        return try {
            val result = AntSportsRpcCall.signUpTask(taskId)
            val resultData = JSONObject(result)

            if (ResChecker.checkRes(TAG, resultData)) {
                val data = resultData.optJSONObject("data")
                val taskOrderId = data?.optString("taskOrderId", "") ?: ""
                Log.sports("做任务得能量🎈[签到成功：$taskName，订单：$taskOrderId]")
                true
            } else {
                val errorMsg = resultData.optString("errorMsg", "未知错误")
                Log.error(TAG, "做任务得能量🎈[签到失败：$taskName，错误：$errorMsg]")
                false
            }
        } catch (e: Exception) {
            Log.error(TAG, "做任务得能量🎈[签到异常：$taskName，错误：${e.message}]")
            false
        }
    }

    /**
     * @brief 运动首页推荐能量球任务
     *
     * @details
     * - 使用 {@link AntSportsRpcCall#queryEnergyBubbleModule} 获取首页 recBubbleList
     * - 区分待完成 task_bubble 与待领取 receive_coin_bubble
     * - 浏览类任务统一直接提交完成/领取 RPC，待领取气泡统一走 pickBubbleTaskEnergy 收取
     */
    private fun buildSportsHomeBubbleCooldownFlag(taskId: String): String {
        return StatusFlags.FLAG_ANTSPORTS_HOME_BUBBLE_COOLDOWN_PREFIX + taskId
    }

    private fun completeSportsHomeBubbleTask(
        task: JSONObject,
        taskId: String
    ): SportsHomeTaskCompletionResult {
        val taskType = task.optString("taskType", "")
        val isAdTask = taskType.equals("AD_TASK", ignoreCase = true) || task.optBoolean("adTask", false)
        if (isAdTask) {
            val adTaskBizId = resolveSportsPanelAdTaskBizId(task)
            val adTaskFinishPayload = resolveSportsPanelAdTaskFinishPayload(task, adTaskBizId)
            if (adTaskFinishPayload.optString("bizId", "").trim().isNotBlank()) {
                return SportsHomeTaskCompletionResult(
                    response = JSONObject(AntSportsRpcCall.finishAdTask(adTaskFinishPayload)),
                    action = "finishAdTask",
                    rpc = "AntSportsRpcCall.finishAdTask",
                    source = "adtask.finish"
                )
            }
            Log.sports("运动首页任务[广告闭环参数缺失，回退直完成：taskId=$taskId，taskType=$taskType]")
        }
        return SportsHomeTaskCompletionResult(
            response = JSONObject(AntSportsRpcCall.completeHomeBubbleTask(taskId)),
            action = "completeHomeBubbleTask",
            rpc = "AntSportsRpcCall.completeHomeBubbleTask",
            source = "completeHomeBubbleTask"
        )
    }

    private fun isSportsRpcSuccess(result: JSONObject): Boolean {
        result.optJSONObject("resData")?.let {
            return isSportsRpcSuccess(it)
        }
        if (result.has("success")) {
            return result.optBoolean("success", false)
        }
        if (result.has("isSuccess")) {
            return result.optBoolean("isSuccess", false)
        }

        val resultCode = result.opt("resultCode")
        when (resultCode) {
            is Number -> {
                val code = resultCode.toInt()
                if (code == 200 || code == 100) {
                    return true
                }
                return false
            }

            is String -> {
                if (
                    resultCode.equals("SUCCESS", ignoreCase = true) ||
                    resultCode == "100" ||
                    resultCode == "200"
                ) {
                    return true
                }
                if (resultCode.isNotBlank()) {
                    return false
                }
            }
        }

        val directErrorCode = result.optString("errorCode", "").trim()
        if (directErrorCode.isNotEmpty()) {
            return false
        }
        val errorValue = result.opt("error")?.toString()?.trim().orEmpty()
        if (errorValue.isNotEmpty() && errorValue != "0") {
            return false
        }

        val resultDesc = result.optString("resultDesc", "").trim()
        if (resultDesc == "成功" || resultDesc == "处理成功") {
            return true
        }
        val resultView = result.optString("resultView", "").trim()
        if (resultView == "成功" || resultView == "处理成功") {
            return true
        }

        return result.optString("memo", "").equals("SUCCESS", ignoreCase = true)
    }

    private fun unwrapSportsRpcPayload(result: JSONObject): JSONObject {
        return result.optJSONObject("resData") ?: result
    }

    private fun isSportsRpcRetryable(result: JSONObject): Boolean {
        result.optJSONObject("resData")?.let {
            return isSportsRpcRetryable(it)
        }
        if (result.has("retryable")) {
            return result.optBoolean("retryable", true)
        }
        if (result.has("retriable")) {
            return result.optBoolean("retriable", true)
        }
        return true
    }

    private fun extractSportsRpcErrorCode(result: JSONObject): String {
        result.optJSONObject("resData")?.let {
            return extractSportsRpcErrorCode(it)
        }
        val directErrorCode = result.optString("errorCode", "").trim()
        if (directErrorCode.isNotEmpty()) {
            return directErrorCode
        }

        val resultCode = result.optString("resultCode", "").trim()
        if (resultCode.isNotEmpty() && !"SUCCESS".equals(resultCode, ignoreCase = true)) {
            return resultCode
        }

        val errorValue = result.opt("error")?.toString()?.trim().orEmpty()
        if (errorValue.isNotEmpty() && errorValue != "0") {
            return errorValue
        }

        val errorTip = result.optString("errorTip", "").trim()
        if (errorTip.isNotEmpty()) {
            return errorTip
        }

        val errorNo = result.opt("errorNo")?.toString()?.trim().orEmpty()
        if (errorNo.isNotEmpty() && errorNo != "0") {
            return errorNo
        }

        return ""
    }

    private fun extractSportsRpcErrorMessage(result: JSONObject): String {
        result.optJSONObject("resData")?.let {
            return extractSportsRpcErrorMessage(it)
        }
        return sequenceOf(
            result.optString("errorMsg", "").trim(),
            result.optString("errorDesc", "").trim(),
            result.optString("resultDesc", "").trim(),
            result.optString("resultMessage", "").trim(),
            result.optString("resultMsg", "").trim(),
            result.optString("errorMessage", "").trim(),
            result.optString("desc", "").trim(),
            result.optString("errorTip", "").trim()
        ).firstOrNull { it.isNotEmpty() } ?: "未知错误"
    }

    private fun classifySportsTaskFailure(result: JSONObject): TaskRpcFailureType {
        val code = extractSportsRpcErrorCode(result)
        val message = extractSportsRpcErrorMessage(result)
        return when {
            code in setOf("RECEIVE_REWARD_REPEATED", "TASK_ALREADY_FINISHED", "TASK_HAS_FINISHED", "REPEAT_FINISH", "REPEAT_REWARD") ||
                containsAnySports(message, "已领取", "已经领取", "重复领取", "重复领奖", "重复完成", "已完成", "任务已完结", "任务已结束") ->
                TaskRpcFailureType.TERMINAL_DONE

            code == "CAMP_TRIGGER_ERROR" ||
                code.contains("LIMIT", ignoreCase = true) ||
                containsAnySports(message, "上限", "限制", "受限", "不可领取", "资格不足", "兑完", "能量不足", "风控", "风险") ->
                TaskRpcFailureType.BUSINESS_LIMIT

            code == "400000040" ||
                containsAnySports(message, "不支持rpc调用", "不支持RPC完成") ->
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                containsAnySports(message, "参数错误", "任务ID非法", "模板不存在") ->
                TaskRpcFailureType.NON_RETRYABLE_INVALID

            code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK", "SYSTEM_BUSY", "NETWORK_ERROR", "1009", "I07", "USER_FREQUENTLY_LOCK") ||
                containsAnySports(message, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试", "需要验证", "访问被拒绝") ||
                isSportsFailureMarkedRetryable(result) ->
                TaskRpcFailureType.RETRYABLE_RPC

            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun blacklistClassifiedSportsTask(taskId: String, taskName: String, errorCode: String) {
        if (errorCode.isNotBlank()) {
            TaskBlacklist.autoAddToBlacklist(SPORTS_TASK_BLACKLIST_MODULE, taskId, taskName, errorCode)
        }
        TaskBlacklist.addToBlacklist(SPORTS_TASK_BLACKLIST_MODULE, taskId, taskName)
    }

    private fun isSportsFailureMarkedRetryable(result: JSONObject): Boolean {
        result.optJSONObject("resData")?.let {
            return isSportsFailureMarkedRetryable(it)
        }
        return listOf("retryable", "retriable", "canRetry").any { key ->
            result.has(key) && result.optBoolean(key, false)
        }
    }

    private fun containsAnySports(text: String, vararg keywords: String): Boolean {
        return keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private fun extractSportsHomeBubbleErrorCode(result: JSONObject): String {
        return extractSportsRpcErrorCode(result)
    }

    private fun extractSportsHomeBubbleErrorMessage(result: JSONObject): String {
        return extractSportsRpcErrorMessage(result)
    }

    private fun shouldTemporarilyStopSportsTask(errorCode: String, errorMsg: String): Boolean {
        if (errorCode == "1009" || errorCode == "I07" || errorCode == "USER_FREQUENTLY_LOCK") {
            return true
        }

        val errorText = "$errorCode $errorMsg"
        return errorText.contains("离线模式") ||
            errorText.contains("離線模式") ||
            errorText.contains("需要验证") ||
            errorText.contains("需要驗證") ||
            errorText.contains("访问被拒绝") ||
            errorText.contains("訪問被拒絕") ||
            errorText.contains("手速太快") ||
            errorText.contains("频繁") ||
            errorText.contains("頻繁")
    }

    private fun shouldCooldownSportsHomeBubbleTask(result: JSONObject): Boolean {
        val errorCode = extractSportsHomeBubbleErrorCode(result)
        val errorMsg = extractSportsHomeBubbleErrorMessage(result)
        if (errorCode == "CAMP_TRIGGER_ERROR" || errorCode == "1009") {
            return true
        }
        if (errorCode != "I07") {
            return false
        }
        if (ApplicationHookConstants.isOffline() && ApplicationHookConstants.offlineReason == "auth_like") {
            return true
        }

        val errorText = "$errorCode $errorMsg"
        return errorText.contains("离线模式") ||
            errorText.contains("離線模式") ||
            errorText.contains("需要验证") ||
            errorText.contains("需要驗證") ||
            errorText.contains("访问被拒绝") ||
            errorText.contains("訪問被拒絕")
    }

    private fun buildSportsHomeBubbleCandidates(data: JSONObject): JSONArray {
        val combined = JSONArray()
        val seenTaskIds = LinkedHashSet<String>()
        val recBubbleList = data.optJSONArray("recBubbleList") ?: JSONArray()
        for (i in 0 until recBubbleList.length()) {
            val bubble = recBubbleList.optJSONObject(i) ?: continue
            combined.put(bubble)
            val taskId = bubble.optJSONObject("task")?.optString("taskId").orEmpty()
                .ifBlank { bubble.optString("channel") }
            if (taskId.isNotBlank()) {
                seenTaskIds.add(taskId)
            }
        }

        val bigBubbleTask = data.optJSONObject("bigBubbleTaskVo")
        if (bigBubbleTask != null) {
            val taskId = bigBubbleTask.optString("taskId")
            if (taskId.isNotBlank() && seenTaskIds.add(taskId)) {
                combined.put(
                    JSONObject()
                        .put("bubbleType", "task_bubble")
                        .put("simpleSourceName", bigBubbleTask.optString("taskName", "运动首页"))
                        .put("channel", taskId)
                        .put("coinAmount", bigBubbleTask.optInt("prizeAmount", 0))
                        .put("assetId", bigBubbleTask.optString("assetId"))
                        .put("medEnergyBallInfoRecordId", bigBubbleTask.optString("medEnergyBallInfoRecordId"))
                        .put("task", bigBubbleTask)
                )
            }
        }
        return combined
    }

    private fun collectSportsHomeRewardScanResult(recBubbleList: JSONArray): SportsHomeRewardScanResult {
        val scanResult = SportsHomeRewardScanResult()
        for (i in 0 until recBubbleList.length()) {
            val bubble = recBubbleList.optJSONObject(i) ?: continue
            val task = bubble.optJSONObject("task")
            val bubbleType = bubble.optString("bubbleType", "")
            val taskStatus = task?.optString("taskStatus", "").orEmpty()
            if (bubbleType != "receive_coin_bubble" && taskStatus != "WAIT_RECEIVE") {
                continue
            }

            val recordId = sequenceOf(
                bubble.optString("assetId", ""),
                task?.optString("assetId", "").orEmpty(),
                bubble.optString("medEnergyBallInfoRecordId", ""),
                task?.optString("medEnergyBallInfoRecordId", "").orEmpty()
            ).map { it.trim() }.firstOrNull { it.isNotEmpty() }

            if (recordId == null) {
                scanResult.missingRecordIdCount++
                continue
            }

            val sourceName = task?.optString(
                "taskName",
                bubble.optString("simpleSourceName", "运动首页")
            ) ?: bubble.optString("simpleSourceName", "运动首页")
            val taskId = task?.optString("taskId", bubble.optString("channel", "")) ?: bubble.optString("channel", "")
            val coinAmount = if (bubble.optInt("coinAmount", 0) > 0) {
                bubble.optInt("coinAmount", 0)
            } else {
                task?.optInt("prizeAmount", 0) ?: 0
            }

            if (!scanResult.candidates.containsKey(recordId)) {
                scanResult.candidates[recordId] = SportsHomeRewardCandidate(
                    recordId = recordId,
                    sourceName = sourceName,
                    taskId = taskId,
                    coinAmount = coinAmount
                )
            }
        }
        return scanResult
    }

    private fun mergeSportsHomeRewardScanResults(
        base: SportsHomeRewardScanResult,
        extra: SportsHomeRewardScanResult
    ): SportsHomeRewardScanResult {
        val merged = SportsHomeRewardScanResult(
            candidates = LinkedHashMap(base.candidates),
            missingRecordIdCount = base.missingRecordIdCount + extra.missingRecordIdCount
        )
        for ((recordId, candidate) in extra.candidates) {
            if (!merged.candidates.containsKey(recordId)) {
                merged.candidates[recordId] = candidate
            }
        }
        return merged
    }

    private fun querySportsHomeRewardScanResult(): SportsHomeRewardScanResult? {
        val response = JSONObject(AntSportsRpcCall.queryEnergyBubbleModule())
        if (!ResChecker.checkRes(TAG, response)) {
            Log.error(TAG, "运动首页任务[刷新奖励气泡失败] raw=$response")
            return null
        }
        val data = response.optJSONObject("data") ?: return SportsHomeRewardScanResult()
        return collectSportsHomeRewardScanResult(buildSportsHomeBubbleCandidates(data))
    }

    private fun isManualSportsHomeBubbleTask(task: JSONObject, taskName: String): Boolean {
        val taskText = listOf(
            taskName,
            task.optString("taskType"),
            task.optString("taskBizType"),
            task.optString("operationType"),
            task.optString("actionType")
        ).joinToString(" ")
        return containsAnySports(
            taskText,
            "下单",
            "购买",
            "买入",
            "支付",
            "开通",
            "订阅",
            "ORDER",
            "PURCHASE",
            "BUY",
            "SUBSCRIBE"
        )
    }

    private fun receiveSportsHomeRewardCandidates(
        scanResult: SportsHomeRewardScanResult,
        handledRecordIds: MutableSet<String>
    ): Boolean {
        var receivedAny = false
        for (candidate in scanResult.candidates.values) {
            if (candidate.recordId in handledRecordIds) {
                Log.sports("运动首页任务[跳过本轮已处理奖励：${candidate.sourceName}，taskId=${candidate.taskId}，recordId=${candidate.recordId}]"
                )
                continue
            }
            val response = JSONObject(AntSportsRpcCall.pickBubbleTaskEnergy(candidate.recordId, false))
            if (isSportsRpcSuccess(response)) {
                receivedAny = true
                handledRecordIds.add(candidate.recordId)
                val dataObj = response.optJSONObject("data")
                val changeAmount =
                    dataObj?.optString("changeAmount", candidate.coinAmount.toString()) ?: candidate.coinAmount.toString()
                val balance = dataObj?.optString("balance", "") ?: ""
                Log.sports("运动首页任务[领取奖励：${candidate.sourceName}，taskId=${candidate.taskId}，recordId=${candidate.recordId}，coin=$changeAmount，balance=${balance.ifBlank { "unknown" }}]"
                )
            } else {
                val errorCode = extractSportsRpcErrorCode(response)
                val errorMsg = extractSportsRpcErrorMessage(response)
                Log.error(
                    TAG,
                    "运动首页任务[领取奖励失败：${candidate.sourceName}，taskId=${candidate.taskId}，recordId=${candidate.recordId}，code=${errorCode.ifEmpty { "UNKNOWN" }}，msg=$errorMsg] raw=$response"
                )
                if (!isSportsRpcRetryable(response)) {
                    handledRecordIds.add(candidate.recordId)
                }
            }
        }
        return receivedAny
    }

    private fun processClubRoomBubbleRewards(clubHomeData: JSONObject) {
        processBubbleList(clubHomeData.optJSONObject("mainRoom"))
        if (hasReachedTrainFriendZeroCoinLimit()) {
            return
        }
        val roomList = clubHomeData.optJSONArray("roomList") ?: return
        for (i in 0 until roomList.length()) {
            val room = roomList.optJSONObject(i)
            processBubbleList(room)
            if (hasReachedTrainFriendZeroCoinLimit()) {
                return
            }
        }
    }

    private fun queryClubHomeForTraining(): JSONObject? {
        val clubHomeData = JSONObject(AntSportsRpcCall.queryClubHome())
        if (!isSportsRpcSuccess(clubHomeData)) {
            val errorCode = extractSportsRpcErrorCode(clubHomeData)
            val errorMsg = extractSportsRpcErrorMessage(clubHomeData)
            Log.error(
                TAG,
                "训练好友[queryClubHome失败][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$clubHomeData"
            )
            return null
        }
        return clubHomeData
    }

    private fun findNextTrainTarget(
        clubHomeData: JSONObject,
        skippedOriginBossIds: Set<String> = emptySet()
    ): SportsTrainTarget? {
        val mainRoomTarget = findTrainTargetInRoom(
            clubHomeData.optJSONObject("mainRoom"),
            skippedOriginBossIds
        )
        if (mainRoomTarget != null) {
            return mainRoomTarget
        }

        val roomList = clubHomeData.optJSONArray("roomList") ?: return null
        for (i in 0 until roomList.length()) {
            val room = roomList.optJSONObject(i) ?: continue
            val target = findTrainTargetInRoom(room, skippedOriginBossIds)
            if (target != null) {
                return target
            }
        }
        return null
    }

    private fun findTrainTargetInRoom(
        room: JSONObject?,
        skippedOriginBossIds: Set<String>
    ): SportsTrainTarget? {
        val memberList = room?.optJSONArray("memberList") ?: return null
        for (j in 0 until memberList.length()) {
            val member = memberList.optJSONObject(j) ?: continue
            val trainInfo = member.optJSONObject("trainInfo") ?: continue
            if (trainInfo.optBoolean("training", false)) {
                continue
            }

            val memberId = member.optString("memberId", "")
            val originBossId = member.optString("originBossId", "")
            if (memberId.isBlank() || originBossId.isBlank()) {
                continue
            }
            if (originBossId in skippedOriginBossIds) {
                continue
            }
            if (FriendGuard.shouldSkipFriend(originBossId, TAG, "训练好友")) {
                continue
            }
            val userName = UserMap.getMaskName(originBossId) ?: originBossId
            return SportsTrainTarget(
                memberId = memberId,
                originBossId = originBossId,
                userName = userName
            )
        }
        return null
    }

    private fun queryBestTrainItemSelection(): SportsTrainItemSelection? {
        val responseJson = JSONObject(AntSportsRpcCall.queryTrainItem())
        if (!isSportsRpcSuccess(responseJson)) {
            val errorCode = extractSportsRpcErrorCode(responseJson)
            val errorMsg = extractSportsRpcErrorMessage(responseJson)
            Log.error(
                TAG,
                "训练好友[queryTrainItem失败][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$responseJson"
            )
            return null
        }

        var bizId = responseJson.optString("bizId", "")
        if (bizId.isBlank() && responseJson.has("taskDetail")) {
            bizId = responseJson.optJSONObject("taskDetail")?.optString("taskId", "").orEmpty()
        }

        val trainItemList = responseJson.optJSONArray("trainItemList")
        if (bizId.isBlank() || trainItemList == null || trainItemList.length() == 0) {
            Log.error(TAG, "训练好友[queryTrainItem缺少bizId或trainItemList] raw=$responseJson")
            return null
        }

        var bestItem: JSONObject? = null
        var bestProduction = -1
        for (i in 0 until trainItemList.length()) {
            val item = trainItemList.optJSONObject(i) ?: continue
            val production = item.optInt("production", 0)
            if (production > bestProduction) {
                bestProduction = production
                bestItem = item
            }
        }

        val selected = bestItem ?: return null
        return SportsTrainItemSelection(
            bizId = bizId,
            itemType = selected.optString("itemType", ""),
            itemName = selected.optString("name", "")
        ).takeIf { it.itemType.isNotBlank() && it.itemName.isNotBlank() }
    }

    private fun shouldSkipTrainTargetAfterFailure(result: JSONObject, errorCode: String, errorMsg: String): Boolean {
        return classifySportsTaskFailure(result) in setOf(
            TaskRpcFailureType.TERMINAL_DONE,
            TaskRpcFailureType.BUSINESS_LIMIT,
            TaskRpcFailureType.NON_RETRYABLE_INVALID
        ) || containsAnySports(
            "$errorCode $errorMsg",
            "状态不错",
            "暂时不需要提醒",
            "不需要训练",
            "没有在训练",
            "目标已变化",
            "成员已变化",
            "STEP_ENOUGH",
            "NOT_TRAINING",
            "NO_NEED_TRAIN"
        )
    }

    private fun isSportsRouteBusinessTerminal(errorCode: String, errorMsg: String): Boolean {
        return errorCode == "AE950002" ||
            errorCode == "AE960231" ||
            errorMsg.contains("已参加路线") ||
            errorMsg.contains("路线已完成")
    }

    private fun sportsEnergyBubbleTask() {
        try {
            var round = 1
            val handledSportsHomeRewardRecordIds = mutableSetOf<String>()
            while (round <= MAX_SPORTS_HOME_BUBBLE_ROUNDS) {
                val jo = JSONObject(AntSportsRpcCall.queryEnergyBubbleModule())
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.error(TAG, "queryEnergyBubbleModule fail: $jo")
                    return
                }

                val data = jo.optJSONObject("data") ?: return
                val recBubbleList = buildSportsHomeBubbleCandidates(data)
                if (recBubbleList.length() == 0) return

                var hasCompletedTask = false
                var hasPendingRewardBubble = false
                var receivedRewardThisRound = false
                var rewardScanResult = collectSportsHomeRewardScanResult(recBubbleList)

                for (i in 0 until recBubbleList.length()) {
                    val bubble = recBubbleList.optJSONObject(i) ?: continue

                    val bubbleType = bubble.optString("bubbleType", "")
                    val sourceName = bubble.optString("simpleSourceName", "运动首页")

                    if (bubbleType == "receive_coin_bubble" || bubble.optString("assetId", "").isNotBlank()) {
                        hasPendingRewardBubble = true
                        val pendingTaskId = bubble.optString("channel", "")
                        val pendingRecordId = bubble.optString("assetId", "")
                        val coinAmount = bubble.optInt("coinAmount", 0)
                        Log.sports("运动首页任务[待领取气泡：$sourceName，taskId=$pendingTaskId，recordId=${pendingRecordId.ifBlank { "unknown" }}，coin=$coinAmount]"
                        )
                        continue
                    }

                    if (bubbleType != "task_bubble") {
                        continue
                    }

                    val task = bubble.optJSONObject("task")
                    if (task == null) {
                        Log.sports("运动首页任务[跳过：$sourceName，无task载荷]")
                        continue
                    }

                    val taskId = task.optString("taskId", "")
                    val taskName = task.optString("taskName", sourceName.ifBlank { taskId })
                    if (taskId.isBlank()) {
                        Log.sports("运动首页任务[跳过：$taskName，taskId为空]")
                        continue
                    }

                    val taskStatus = task.optString("taskStatus", "")
                    val isBlacklisted =
                        TaskBlacklist.isTaskInBlacklist(SPORTS_TASK_BLACKLIST_MODULE, taskId) ||
                            TaskBlacklist.isTaskInBlacklist(SPORTS_TASK_BLACKLIST_MODULE, taskName)
                    if (isBlacklisted && taskStatus != "WAIT_RECEIVE") {
                        Log.sports("运动首页任务[黑名单跳过：$taskName，taskId=$taskId]")
                        continue
                    }

                    val cooldownFlag = buildSportsHomeBubbleCooldownFlag(taskId)
                    if (Status.hasFlagToday(cooldownFlag) && taskStatus != "WAIT_RECEIVE") {
                        Log.sports("运动首页任务[今日冷却跳过：$taskName，taskId=$taskId]")
                        continue
                    }

                    if (taskStatus == "WAIT_RECEIVE") {
                        hasPendingRewardBubble = true
                        val rewardRecordId = task.optString("assetId", "")
                        Log.sports("运动首页任务[待领取奖励：$taskName，taskId=$taskId，recordId=${rewardRecordId.ifBlank { "unknown" }}]"
                        )
                        continue
                    }
                    if (taskStatus != "WAIT_COMPLETE") {
                        Log.sports("运动首页任务[状态跳过：$taskName，taskId=$taskId，status=$taskStatus]")
                        continue
                    }
                    if (isManualSportsHomeBubbleTask(task, taskName)) {
                        Log.sports("运动首页任务[人工动作跳过：$taskName，taskId=$taskId，taskType=${task.optString("taskType", "")}]")
                        continue
                    }

                    Log.sports("运动首页任务[直完成开始：$taskName，taskId=$taskId，taskType=${task.optString("taskType", "")}]"
                    )
                    val completion = completeSportsHomeBubbleTask(task, taskId)
                    val completeRes = completion.response

                    if (ResChecker.checkRes(TAG, completeRes)) {
                        hasCompletedTask = true
                        hasPendingRewardBubble = true
                        val dataObj = completeRes.optJSONObject("data")
                        val assetCoinAmount =
                            dataObj?.optInt("assetCoinAmount", task.optInt("prizeAmount", 0))
                                ?: task.optInt("prizeAmount", 0)
                        Log.sports("运动球任务✅[$taskName]#奖励$assetCoinAmount💰，方式=${completion.source}")
                        continue
                    }

                    val errorCode = extractSportsHomeBubbleErrorCode(completeRes)
                    val errorMsg = extractSportsHomeBubbleErrorMessage(completeRes)
                    val detail = "module=$SPORTS_TASK_BLACKLIST_MODULE taskId=$taskId taskName=$taskName " +
                        "action=${completion.action} rpc=${completion.rpc} " +
                        "code=${errorCode.ifEmpty { "UNKNOWN" }} msg=$errorMsg raw=$completeRes"
                    if (shouldCooldownSportsHomeBubbleTask(completeRes)) {
                        Status.setFlagToday(cooldownFlag)
                        Log.error(
                            TAG,
                            "运动首页任务[$taskName] classification=${classifySportsTaskFailure(completeRes)} decision=STOP_TODAY_OR_CURRENT_CHAIN $detail"
                        )
                    } else {
                        when (classifySportsTaskFailure(completeRes)) {
                            TaskRpcFailureType.TERMINAL_DONE -> {
                                hasCompletedTask = true
                                hasPendingRewardBubble = true
                                Log.sports("运动首页任务[$taskName] classification=TERMINAL_DONE decision=MARK_HANDLED $detail")
                            }
                            TaskRpcFailureType.BUSINESS_LIMIT -> {
                                Log.error(TAG, "运动首页任务[$taskName] classification=BUSINESS_LIMIT decision=STOP_TODAY_OR_CURRENT_CHAIN $detail")
                            }
                            TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE -> {
                                blacklistClassifiedSportsTask(taskId, taskName, errorCode)
                                Log.error(TAG, "运动首页任务[$taskName] classification=UNSUPPORTED_NO_CLOSURE decision=BLACKLIST reason=未抓到稳定完成RPC $detail")
                            }
                            TaskRpcFailureType.NON_RETRYABLE_INVALID -> {
                                blacklistClassifiedSportsTask(taskId, taskName, errorCode)
                                Log.error(TAG, "运动首页任务[$taskName] classification=NON_RETRYABLE_INVALID decision=BLACKLIST $detail")
                            }
                            TaskRpcFailureType.RETRYABLE_RPC -> {
                                Log.error(TAG, "运动首页任务[$taskName] classification=RETRYABLE_RPC decision=RETRY_LATER $detail")
                            }
                            TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW -> {
                                Log.error(TAG, "运动首页任务[$taskName] classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY $detail")
                            }
                        }
                    }
                }

                if (hasCompletedTask || hasPendingRewardBubble) {
                    if (hasCompletedTask) {
                        val refreshedRewardScanResult = querySportsHomeRewardScanResult()
                        if (refreshedRewardScanResult != null) {
                            rewardScanResult = mergeSportsHomeRewardScanResults(rewardScanResult, refreshedRewardScanResult)
                        } else {
                            rewardScanResult.missingRecordIdCount++
                        }
                    }

                    val receivedByRecordId = receiveSportsHomeRewardCandidates(
                        rewardScanResult,
                        handledSportsHomeRewardRecordIds
                    )
                    if (receivedByRecordId) {
                        receivedRewardThisRound = true
                    }
                    val shouldFallbackPickAll =
                        rewardScanResult.missingRecordIdCount > 0 ||
                            (hasCompletedTask && rewardScanResult.candidates.isEmpty())
                    if (shouldFallbackPickAll) {
                        Log.sports("运动首页任务[奖励兜底领取：pickAll，knownRecordIds=${rewardScanResult.candidates.size}，missingRecordIds=${rewardScanResult.missingRecordIdCount}]"
                        )
                        val resultJson = JSONObject(AntSportsRpcCall.pickBubbleTaskEnergy())
                        if (ResChecker.checkRes(TAG, resultJson)) {
                            val dataObj = resultJson.optJSONObject("data")
                            val balance = dataObj?.optString("balance", "0") ?: "0"
                            receivedRewardThisRound = true
                            Log.sports("拾取能量球成功  当前余额: $balance💰")
                        } else {
                            Log.error(TAG, "领取能量球任务失败: ${extractSportsRpcErrorMessage(resultJson)} raw=$resultJson")
                        }
                    } else if (!receivedByRecordId) {
                        Log.sports("运动首页任务[无可领取奖励记录，跳过pickAll兜底]")
                    }
                } else if (round == 1) {
                    Log.sports("未完成任何任务，跳过领取能量球")
                }

                if (!hasCompletedTask && !receivedRewardThisRound) {
                    break
                }
                round++
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "sportsEnergyBubbleTask err:", t)
        }
    }

    /**
     * @brief 运动签到：先 query 再 signIn
     */
    private fun sportsCheckIn() {
        if (
            TaskBlacklist.isTaskInBlacklist(SPORTS_TASK_BLACKLIST_MODULE, SPORTS_CHECK_IN_TITLE) ||
            TaskBlacklist.isTaskInBlacklist(SPORTS_TASK_BLACKLIST_MODULE, SPORTS_DOLPHIN_ACTIVITY_TITLE)
        ) {
            Log.sports("运动签到[黑名单跳过]")
            return
        }
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_CHECK_IN_HANDLED_TODAY)) {
            Log.sports("运动签到今日已处理，跳过重复触发")
            return
        }

        try {
            val queryJo = JSONObject(AntSportsRpcCall.signInCoinTask("query"))
            if (!isSportsRpcSuccess(queryJo)) {
                handleSportsCheckInFailure("查询签到状态", queryJo)
                return
            }

            val data = queryJo.optJSONObject("data")
            if (data == null) {
                Log.error(TAG, "运动签到查询返回缺少 data：$queryJo")
                return
            }

            val isSigned = data.optBoolean("signed", false)
            if (isSigned) {
                Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_CHECK_IN_HANDLED_TODAY)
                Log.sports("运动签到今日已签到")
                return
            }

            val signConfigList = data.optJSONArray("signConfigList")
            if (signConfigList == null) {
                Log.error(TAG, "运动签到查询返回缺少 signConfigList：$queryJo")
                return
            }

            var foundToday = false
            for (i in 0 until signConfigList.length()) {
                val configItem = signConfigList.optJSONObject(i) ?: continue
                val toDay = configItem.optBoolean("toDay", false)
                val itemSigned = configItem.optBoolean("signed", false)

                if (toDay) {
                    foundToday = true
                    if (itemSigned) {
                        Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_CHECK_IN_HANDLED_TODAY)
                        Log.sports("运动签到今日已签到")
                        return
                    }

                    val coinAmount = configItem.optInt("coinAmount", 0)
                    val signJo = JSONObject(AntSportsRpcCall.signInCoinTask("signIn"))
                    if (isSportsRpcSuccess(signJo)) {
                        Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_CHECK_IN_HANDLED_TODAY)
                        val signData = signJo.optJSONObject("data") ?: JSONObject()
                        val subscribeConfig = signData.optJSONObject("subscribeConfig") ?: JSONObject()
                        val expireDays = subscribeConfig.optString("subscribeExpireDays", "未知")
                        val toast = signData.optString("toast", "")

                        Log.sports(
                            "做任务得能量🎈[签到${expireDays}天|" +
                                coinAmount + "能量，" + toast + "💰]"
                        )
                    } else {
                        handleSportsCheckInFailure("执行签到", signJo)
                    }
                    return
                }
            }

            if (!foundToday) {
                Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_CHECK_IN_HANDLED_TODAY)
                Log.sports("运动签到未找到今日签到配置，今日不再重复触发")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "sportsCheck_in err", e)
        }
    }

    private fun handleSportsCheckInFailure(stage: String, result: JSONObject) {
        val errorCode = extractSportsRpcErrorCode(result)
        val errorMsg = extractSportsRpcErrorMessage(result)
        val nonRetryable = !isSportsRpcRetryable(result)
        val terminalBusinessError = errorCode == "CAMP_TRIGGER_ERROR" || nonRetryable
        val codeText = errorCode.ifEmpty { "UNKNOWN" }

        if (terminalBusinessError) {
            Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_CHECK_IN_HANDLED_TODAY)
            Log.sports(
                "运动签到[$stage 业务受限：$codeText - $errorMsg]，今日不再重复触发 raw=$result"
            )
            return
        }

        Log.error(TAG, "运动签到[$stage 失败：$codeText - $errorMsg] raw=$result")
    }

    /**
     * @brief 首页金币收集逻辑
     */
    private fun receiveCoinAsset() {
        if (receiveCoinAssetField.value != true) {
            return
        }
        try {
            val s = AntSportsRpcCall.queryCoinBubbleModule()
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val data = jo.getJSONObject("data")
                if (!data.has("receiveCoinBubbleList")) return

                val ja = data.getJSONArray("receiveCoinBubbleList")
                for (i in 0 until ja.length()) {
                    jo = ja.getJSONObject(i)
                    val assetId = jo.getString("assetId")
                    val coinAmount = jo.getInt("coinAmount")
                    val res = JSONObject(AntSportsRpcCall.receiveCoinAsset(assetId, coinAmount))
                    if (ResChecker.checkRes(TAG, res)) {
                        Log.sports("收集金币💰[$coinAmount 个]")
                    } else {
                        Log.sports("首页收集金币 $res")
                    }
                }
            } else {
                Log.sports(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveCoinAsset err:", t)
        }
    }

    // ---------------------------------------------------------------------
    // 新版行走路线（SportsPlay）
    // ---------------------------------------------------------------------

    /**
     * @brief 新版行走路线主流程 主入口
     */
    internal fun walk() {
        try {
            val config = buildRouteConfig()
            var lastTerminalJoinRouteKey: String? = null
            var repeatedTerminalJoinCount = 0

            while (true) {
                val userData = queryRouteUserData() ?: return
                val joinedPathId = resolveCurrentJoinedPathId(userData)
                val currentPathData = joinedPathId.takeIf { it.isNotBlank() }?.let { queryPath(it) }
                val currentStep = currentPathData?.optJSONObject("userPathStep")
                val currentStatus = currentStep?.optString("pathCompleteStatus", "").orEmpty()
                val currentCandidate = buildRouteCandidateFromPathData(currentPathData)
                    ?: joinedPathId.takeIf { it.isNotBlank() }?.let { RouteCandidate(pathId = it) }
                val currentRouteTerminal = isRouteTerminal(currentPathData, currentStatus)

                if (currentPathData != null && isActiveRouteStatus(currentStatus)) {
                    when (executeCurrentRouteWalk(currentPathData)) {
                        RouteWalkOutcome.MOVED,
                        RouteWalkOutcome.COMPLETED -> {
                            GlobalThreadPools.sleepCompat(500)
                            continue
                        }

                        RouteWalkOutcome.NO_STEPS -> {
                            if (tryReviveRouteSteps(config, currentPathData)) {
                                GlobalThreadPools.sleepCompat(500)
                                continue
                            }
                            Log.sports("行走路线🚶🏻‍♂️今日无可用步数，结束本轮路线流程")
                            return
                        }

                        RouteWalkOutcome.STOP -> return
                    }
                }

                val decision = chooseNextRoute(config, currentPathData, joinedPathId)
                if (decision == null) {
                    Log.error(TAG, "行走路线🚶🏻‍♂️未找到可加入路线")
                    return
                }

                Log.sports("行走路线🚶🏻‍♂️选择路线[${decision.candidate.name.ifBlank { decision.candidate.pathId }}]#${decision.reason}"
                )
                val shouldGuardTerminalLoop =
                    currentRouteTerminal || !isActiveRouteStatus(currentStatus)
                if (shouldGuardTerminalLoop) {
                    val currentRouteKey = decision.candidate.name.trim()
                        .takeIf { it.isNotEmpty() }
                        ?: decision.candidate.pathId.trim().takeIf { it.isNotEmpty() }
                        ?: currentCandidate?.name?.trim()?.takeIf { it.isNotEmpty() }
                        ?: currentCandidate?.pathId?.trim()?.takeIf { it.isNotEmpty() }
                        ?: joinedPathId.trim().takeIf { it.isNotEmpty() }
                        ?: decision.candidate.pathId
                    repeatedTerminalJoinCount = if (lastTerminalJoinRouteKey == currentRouteKey) {
                        repeatedTerminalJoinCount + 1
                    } else {
                        lastTerminalJoinRouteKey = currentRouteKey
                        1
                    }
                    if (repeatedTerminalJoinCount >= 2) {
                        val shouldTryReviveAfterRepeat =
                            isRouteStepUnavailable(currentPathData, currentStep)
                        if (shouldTryReviveAfterRepeat && tryReviveRouteSteps(config, currentPathData)) {
                            repeatedTerminalJoinCount = 0
                            lastTerminalJoinRouteKey = null
                            GlobalThreadPools.sleepCompat(500)
                            continue
                        }
                        Log.sports("行走路线🚶🏻‍♂️终态路线重复加入仍未切换[${decision.candidate.name.ifBlank { decision.candidate.pathId }}]，结束本轮以避免无效循环"
                        )
                        return
                    }
                } else {
                    repeatedTerminalJoinCount = 0
                    lastTerminalJoinRouteKey = null
                }
                joinPath(decision.candidate.pathId)
                GlobalThreadPools.sleepCompat(500)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "walk err:", t)
        }
    }

    /**
     * @brief 新版路线行走一步
     */
    private fun walkGo(pathId: String, useStepCount: Int, pathName: String): RouteWalkOutcome {
        return try {
            val date = Date()
            @SuppressLint("SimpleDateFormat") val sdf = SimpleDateFormat("yyyy-MM-dd")
            val jo = JSONObject(AntSportsRpcCall.walkGo(sdf.format(date), pathId, useStepCount))
            if (isSportsRpcSuccess(jo)) {
                Log.sports("行走路线🚶🏻‍♂️路线[$pathName]#前进了${useStepCount}步")
                processRouteEvents(jo.optJSONObject("data"))
                val latestPath = queryPath(pathId)
                if (latestPath?.optJSONObject("userPathStep")?.optString("pathCompleteStatus", "") == "COMPLETED") {
                    RouteWalkOutcome.COMPLETED
                } else {
                    RouteWalkOutcome.MOVED
                }
            } else {
                val errorCode = extractSportsRpcErrorCode(jo)
                val errorMsg = extractSportsRpcErrorMessage(jo)
                Log.error(
                    TAG,
                    "walkGo失败[pathId=$pathId][useStepCount=$useStepCount][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$jo"
                )
                if (isRouteStepLimit(errorCode, errorMsg)) RouteWalkOutcome.NO_STEPS else RouteWalkOutcome.STOP
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "walkGo err:", t)
            RouteWalkOutcome.STOP
        }
    }

    private fun buildRouteConfig(): RouteConfig {
        val configuredThemeIds = parseRouteIds(if (::walkThemeIds.isInitialized) walkThemeIds.value else null)
        val fallbackThemeId = walkPathThemeId?.takeIf { it.isNotBlank() }
            ?: WalkPathTheme.themeIds[WalkPathTheme.DA_MEI_ZHONG_GUO]
        val themeIds = configuredThemeIds.ifEmpty { listOf(fallbackThemeId) }

        val configuredPathIds = parseRouteIds(if (::walkPathIds.isInitialized) walkPathIds.value else null)
        val legacyPathIds = if (::walkCustomPath.isInitialized && walkCustomPath.value == true) {
            parseRouteIds(walkCustomPathId.value)
        } else {
            emptyList()
        }

        return RouteConfig(
            themeIds = themeIds,
            pathIds = configuredPathIds.ifEmpty { legacyPathIds },
            themeLoop = ::walkThemeLoop.isInitialized && walkThemeLoop.value == true,
            routeLoop = ::walkRouteLoop.isInitialized && walkRouteLoop.value == true,
            reviveSteps = !::walkReviveSteps.isInitialized || walkReviveSteps.value == true,
            reviveTask = !::walkReviveTask.isInitialized || walkReviveTask.value == true
        )
    }

    private fun parseRouteIds(rawValue: String?): List<String> {
        return rawValue.orEmpty()
            .split(Regex("[,，;；\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun queryRouteUserData(): JSONObject? {
        val user = JSONObject(AntSportsRpcCall.queryUser())
        if (!ResChecker.checkRes(TAG, user)) {
            Log.error(TAG, "查询路线用户失败: $user")
            return null
        }
        return user.optJSONObject("data") ?: run {
            Log.error(TAG, "查询路线用户成功但 data 为空: $user")
            null
        }
    }

    private fun resolveCurrentJoinedPathId(userData: JSONObject): String {
        return sequenceOf(
            userData.optString("joinedPathId", "").trim(),
            userData.optString("goingPathId", "").trim(),
            userData.optJSONObject("userPathStep")?.optString("pathId", "")?.trim().orEmpty(),
            userData.optJSONObject("path")?.optString("pathId", "")?.trim().orEmpty()
        ).firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun isActiveRouteStatus(status: String): Boolean {
        return status == "JOIN" || status == "NOT_COMPLETED"
    }

    private fun isRouteCompleted(status: String): Boolean {
        return status.equals("COMPLETED", ignoreCase = true)
    }

    private fun isRouteTerminal(pathData: JSONObject?, status: String): Boolean {
        if (isRouteCompleted(status)) {
            return true
        }
        val userPathStep = pathData?.optJSONObject("userPathStep") ?: return false
        if (userPathStep.optInt("pathProgress", 0) >= 100) {
            return true
        }
        val pathStepCount = pathData.optJSONObject("path")?.optInt("pathStepCount", 0) ?: 0
        val forwardStepCount = userPathStep.optInt("forwardStepCount", 0)
        val remainStepCount = userPathStep.optInt("remainStepCount", -1)
        return pathStepCount > 0 &&
            forwardStepCount >= pathStepCount &&
            (userPathStep.optBoolean("dayLimit", false) || remainStepCount <= 0)
    }

    private fun isRouteStepUnavailable(pathData: JSONObject?, userPathStep: JSONObject?): Boolean {
        if (userPathStep == null) {
            return false
        }
        val minGoStepCount =
            pathData?.optJSONObject("path")?.optInt("minGoStepCount", 1)?.coerceAtLeast(1) ?: 1
        val remainStepCount = userPathStep.optInt("remainStepCount", 0)
        return userPathStep.optBoolean("dayLimit", false) || remainStepCount < minGoStepCount
    }

    private fun executeCurrentRouteWalk(pathData: JSONObject): RouteWalkOutcome {
        val userPathStep = pathData.optJSONObject("userPathStep")
        val pathObj = pathData.optJSONObject("path")
        if (userPathStep == null || pathObj == null) {
            Log.error(TAG, "行走路线🚶🏻‍♂️路线详情缺少 userPathStep/path: $pathData")
            return RouteWalkOutcome.STOP
        }

        val pathId = userPathStep.optString("pathId", pathObj.optString("pathId", ""))
        val pathName = userPathStep.optString("pathName", pathObj.optString("name", pathId))
        val status = userPathStep.optString("pathCompleteStatus", "")
        if (isRouteCompleted(status)) {
            Log.sports("行走路线🚶🏻‍♂️路线[$pathName]已完成")
            return RouteWalkOutcome.COMPLETED
        }

        val minGoStepCount = pathObj.optInt("minGoStepCount", 1).coerceAtLeast(1)
        val remainStepCount = userPathStep.optInt("remainStepCount", 0)
        if (userPathStep.optBoolean("dayLimit", false) || remainStepCount < minGoStepCount) {
            Log.sports("行走路线🚶🏻‍♂️路线[$pathName]今日步数不足[remain=$remainStepCount,min=$minGoStepCount,dayLimit=${userPathStep.optBoolean("dayLimit", false)}]"
            )
            return RouteWalkOutcome.NO_STEPS
        }

        val pathStepCount = pathObj.optInt("pathStepCount", 0)
        val forwardStepCount = userPathStep.optInt("forwardStepCount", 0)
        val needStepCount = if (pathStepCount > 0) {
            val remainToFinish = pathStepCount - (forwardStepCount % pathStepCount)
            remainToFinish.takeIf { it > 0 } ?: pathStepCount
        } else {
            minGoStepCount
        }
        val useStepCount = min(remainStepCount, max(needStepCount, minGoStepCount))
        if (useStepCount < minGoStepCount) {
            return RouteWalkOutcome.NO_STEPS
        }
        return walkGo(pathId, useStepCount, pathName)
    }

    private fun chooseNextRoute(config: RouteConfig, currentPathData: JSONObject?, joinedPathId: String): RouteDecision? {
        val currentCandidate = buildRouteCandidateFromPathData(currentPathData)
            ?: joinedPathId.takeIf { it.isNotBlank() }?.let { RouteCandidate(pathId = it) }
        val currentPathId = currentCandidate?.pathId
        val currentStatus = currentPathData?.optJSONObject("userPathStep")?.optString("pathCompleteStatus", "").orEmpty()
        val currentRouteTerminal = isRouteTerminal(currentPathData, currentStatus)

        if (config.routeLoop) {
            currentCandidate?.let {
                return RouteDecision(it, "路线循环")
            }
            return firstConfiguredRoute(config)?.let { RouteDecision(it, "路线循环默认首条") }
        }

        if (config.themeLoop) {
            val currentThemeId = currentCandidate?.themeId?.takeIf { it in config.themeIds }
            val themeId = currentThemeId ?: config.themeIds.firstOrNull()
            if (!themeId.isNullOrBlank()) {
                val themeRoutes = collectRouteCandidatesForThemes(listOf(themeId))
                val candidate = themeRoutes.firstOrNull { !isRouteCompleted(it.status) }
                    ?: themeRoutes.firstOrNull()
                if (candidate != null) {
                    return RouteDecision(candidate, "主题循环[$themeId]")
                }
            }
        }

        val configuredPathRoutes = if (config.pathIds.isNotEmpty()) {
            config.pathIds.mapNotNull { queryRouteCandidateByPathId(it) }
        } else {
            emptyList()
        }
        configuredPathRoutes.firstOrNull {
            !isRouteCompleted(it.status) && !(currentRouteTerminal && it.pathId == currentPathId)
        }?.let {
            return RouteDecision(it, "自定义路线未完成优先")
        }

        val themeRoutes = if (config.pathIds.isEmpty()) {
            collectRouteCandidatesForThemes(config.themeIds)
        } else {
            emptyList()
        }
        if (config.pathIds.isEmpty()) {
            themeRoutes.firstOrNull {
                !isRouteCompleted(it.status) && !(currentRouteTerminal && it.pathId == currentPathId)
            }?.let {
                return RouteDecision(it, "主题路线未完成优先")
            }
        }

        findMissingKnowledgeRoute(
            excludePathId = currentPathId.takeIf { currentRouteTerminal },
            excludePathName = currentCandidate?.name?.takeIf { currentRouteTerminal && it.isNotBlank() }
        )?.let { return it }

        firstConfiguredRoute(config)?.let { return RouteDecision(it, "默认回到首条路线") }

        currentPathId?.let { pathId ->
            collectRecommendedRouteCandidates(pathId)
                .firstOrNull {
                    !isRouteCompleted(it.status) &&
                        !(currentRouteTerminal && it.pathId == pathId)
                }
                ?.let { return RouteDecision(it, "服务端推荐路线未完成优先") }
        }

        return null
    }

    private fun firstConfiguredRoute(config: RouteConfig): RouteCandidate? {
        if (config.pathIds.isNotEmpty()) {
            return queryRouteCandidateByPathId(config.pathIds.first()) ?: RouteCandidate(pathId = config.pathIds.first())
        }
        return collectRouteCandidatesForThemes(config.themeIds).firstOrNull()
    }

    private fun queryRouteCandidateByPathId(pathId: String): RouteCandidate? {
        return buildRouteCandidateFromPathData(queryPath(pathId)) ?: RouteCandidate(pathId = pathId)
    }

    private fun buildRouteCandidateFromPathData(pathData: JSONObject?): RouteCandidate? {
        if (pathData == null) return null
        val pathObj = pathData.optJSONObject("path")
        val userPathStep = pathData.optJSONObject("userPathStep")
        val pathId = userPathStep?.optString("pathId", "")
            ?.takeIf { it.isNotBlank() }
            ?: pathObj?.optString("pathId", "").orEmpty()
        if (pathId.isBlank()) return null
        return RouteCandidate(
            pathId = pathId,
            name = userPathStep?.optString("pathName", "")
                ?.takeIf { it.isNotBlank() }
                ?: pathObj?.optString("name", "").orEmpty(),
            themeId = pathObj?.optString("themeId", "")?.takeIf { it.isNotBlank() },
            cityId = pathObj?.optString("cityId", "")?.takeIf { it.isNotBlank() },
            status = userPathStep?.optString("pathCompleteStatus", "").orEmpty()
        )
    }

    private fun collectRouteCandidatesForThemes(themeIds: List<String>): List<RouteCandidate> {
        val candidates = LinkedHashMap<String, RouteCandidate>()
        for (themeId in themeIds) {
            val theme = queryWorldMap(themeId) ?: continue
            val cityList = theme.optJSONArray("cityList") ?: continue
            for (i in 0 until cityList.length()) {
                val cityId = cityList.optJSONObject(i)?.optString("cityId", "").orEmpty()
                if (cityId.isBlank()) continue
                val city = queryCityPath(cityId) ?: continue
                val cityPathList = city.optJSONArray("cityPathList") ?: continue
                for (j in 0 until cityPathList.length()) {
                    val cityPath = cityPathList.optJSONObject(j) ?: continue
                    val pathId = cityPath.optString("pathId", "")
                    if (pathId.isBlank() || candidates.containsKey(pathId)) continue
                    candidates[pathId] = RouteCandidate(
                        pathId = pathId,
                        name = cityPath.optString("name", cityPath.optString("pathName", pathId)),
                        themeId = cityPath.optString("themeId", themeId).ifBlank { themeId },
                        cityId = cityPath.optString("cityId", cityId).ifBlank { cityId },
                        status = cityPath.optString("pathCompleteStatus", "")
                    )
                }
            }
        }
        return candidates.values.toList()
    }

    private fun collectRecommendedRouteCandidates(pathId: String): List<RouteCandidate> {
        val response = JSONObject(AntSportsRpcCall.queryRecommendPathList(pathId))
        if (!ResChecker.checkRes(TAG, response)) {
            Log.error(
                TAG,
                "行走路线🚶🏻‍♂️查询推荐路线失败[pathId=$pathId][code=${extractSportsRpcErrorCode(response).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(response)}] raw=$response"
            )
            return emptyList()
        }
        val recommendList = response.optJSONObject("data")?.optJSONArray("recommendPathList") ?: return emptyList()
        val candidates = mutableListOf<RouteCandidate>()
        for (i in 0 until recommendList.length()) {
            val item = recommendList.optJSONObject(i) ?: continue
            val recommendPathId = item.optString("pathId", "")
            if (recommendPathId.isBlank()) continue
            candidates.add(
                RouteCandidate(
                    pathId = recommendPathId,
                    name = item.optString("name", recommendPathId),
                    themeId = item.optString("themeId", "").takeIf { it.isNotBlank() },
                    cityId = item.optString("cityId", "").takeIf { it.isNotBlank() },
                    status = item.optString("pathCompleteStatus", "")
                )
            )
        }
        return candidates
    }

    private fun findMissingKnowledgeRoute(
        excludePathId: String? = null,
        excludePathName: String? = null
    ): RouteDecision? {
        val cityList = queryRouteKnowledgeCities()
        for (city in cityList) {
            if (city.cityId.isBlank() || city.total <= 0 || city.received >= city.total) {
                continue
            }
            val cityPathData = queryCityPath(city.cityId) ?: continue
            val pathList = cityPathData.optJSONArray("cityPathList") ?: continue
            var hasRouteInCity = false
            var loopCandidate: RouteCandidate? = null
            var excludedLoopCandidate: RouteCandidate? = null
            for (j in 0 until pathList.length()) {
                val cityPath = pathList.optJSONObject(j) ?: continue
                val candidate = RouteCandidate(
                    pathId = cityPath.optString("pathId", ""),
                    name = cityPath.optString("name", cityPath.optString("pathName", "")),
                    themeId = cityPath.optString("themeId", "")
                        .ifBlank { city.themeId.orEmpty() }
                        .takeIf { it.isNotBlank() },
                    cityId = city.cityId,
                    status = cityPath.optString("pathCompleteStatus", "")
                )
                if (candidate.pathId.isBlank()) {
                    continue
                }
                hasRouteInCity = true
                val isExcluded = (!excludePathId.isNullOrBlank() && candidate.pathId == excludePathId) ||
                    (!excludePathName.isNullOrBlank() &&
                        candidate.name.isNotBlank() &&
                        candidate.name == excludePathName)
                if (!isExcluded && !isRouteCompleted(candidate.status)) {
                    Log.sports(
                        "行走路线📍城市见闻优先[${city.cityName.ifBlank { city.cityId }}:${city.received}/${city.total} status=${city.status.ifBlank { "UNKNOWN" }}]" +
                            " -> [${candidate.name.ifBlank { candidate.pathId }}]"
                    )
                    return RouteDecision(
                        candidate,
                        "城市见闻未完成[${city.cityName.ifBlank { city.cityId }}:${city.received}/${city.total}]"
                    )
                }
                if (!isExcluded && loopCandidate == null) {
                    loopCandidate = candidate
                }
                if (isExcluded && excludedLoopCandidate == null) {
                    excludedLoopCandidate = candidate
                }
            }
            if (loopCandidate != null) {
                Log.sports(
                    "行走路线📍城市见闻优先[${city.cityName.ifBlank { city.cityId }}:${city.received}/${city.total} status=${city.status.ifBlank { "UNKNOWN" }}]" +
                        " -> [${loopCandidate.name.ifBlank { loopCandidate.pathId }}]#循环"
                )
                return RouteDecision(
                    loopCandidate,
                    "城市见闻未完成[${city.cityName.ifBlank { city.cityId }}:${city.received}/${city.total}]#优先循环路线"
                )
            }
            if (excludedLoopCandidate != null) {
                Log.sports(
                    "行走路线📍城市见闻优先[${city.cityName.ifBlank { city.cityId }}:${city.received}/${city.total} status=${city.status.ifBlank { "UNKNOWN" }}]" +
                        " -> [${excludedLoopCandidate.name.ifBlank { excludedLoopCandidate.pathId }}]#当前路线"
                )
                return RouteDecision(
                    excludedLoopCandidate,
                    "城市见闻未完成[${city.cityName.ifBlank { city.cityId }}:${city.received}/${city.total}]#仅当前路线可循环"
                )
            }
            if (hasRouteInCity) {
                Log.sports("行走路线🚶🏻‍♂️城市见闻未完成但无未完成路线可切换[${city.cityName.ifBlank { city.cityId }}:${city.received}/${city.total}]"
                )
            }
        }
        return null
    }

    private fun queryRouteKnowledgeCities(): List<RouteKnowledgeCity> {
        queryRouteMedalKnowledgeCities()?.let { return it }
        queryRouteLegacyKnowledgeCities()?.let { return it }
        return emptyList()
    }

    private fun queryRouteMedalKnowledgeCities(): List<RouteKnowledgeCity>? {
        return try {
            val response = JSONObject(AntSportsRpcCall.queryMedalDetail())
            if (!isSportsRpcSuccess(response)) {
                Log.error(
                    TAG,
                    "行走路线🚶🏻‍♂️查询城市勋章详情失败[code=${extractSportsRpcErrorCode(response).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(response)}] raw=$response"
                )
                null
            } else {
                val medals = unwrapSportsRpcPayload(response)
                    .optJSONObject("data")
                    ?.optJSONArray("medals") ?: return null
                val result = mutableListOf<RouteKnowledgeCity>()
                for (i in 0 until medals.length()) {
                    val medal = medals.optJSONObject(i) ?: continue
                    val cityId = medal.optString("cityId", "")
                    if (cityId.isBlank()) continue
                    val lockNum = medal.optInt("lockNum", 0).coerceAtLeast(0)
                    val unlockNum = medal.optInt("unlockNum", 0).coerceAtLeast(0)
                    val total = lockNum + unlockNum
                    result.add(
                        RouteKnowledgeCity(
                            cityId = cityId,
                            cityName = medal.optString("cityName", cityId),
                            total = total,
                            received = unlockNum.coerceAtMost(total),
                            status = medal.optString("status", ""),
                            themeId = medal.optString("themeId", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
                result
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryRouteMedalKnowledgeCities err", t)
            null
        }
    }

    private fun queryRouteLegacyKnowledgeCities(): List<RouteKnowledgeCity>? {
        return try {
            val summary = JSONObject(AntSportsRpcCall.queryCityKnowledgeSummary())
            if (!isSportsRpcSuccess(summary)) {
                Log.error(
                    TAG,
                    "行走路线🚶🏻‍♂️查询城市见闻汇总失败[code=${extractSportsRpcErrorCode(summary).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(summary)}] raw=$summary"
                )
                null
            } else {
                val cityList = unwrapSportsRpcPayload(summary)
                    .optJSONObject("data")
                    ?.optJSONArray("cityKnowledgeSummaryList") ?: return null
                val result = mutableListOf<RouteKnowledgeCity>()
                for (i in 0 until cityList.length()) {
                    val city = cityList.optJSONObject(i) ?: continue
                    val cityId = city.optString("cityId", "")
                    if (cityId.isBlank()) continue
                    result.add(
                        RouteKnowledgeCity(
                            cityId = cityId,
                            cityName = city.optString("cityName", cityId),
                            total = city.optInt("totalKnowledgeCount", 0).coerceAtLeast(0),
                            received = city.optInt("receiveKnowledgeCount", 0).coerceAtLeast(0),
                            status = city.optString("status", "")
                        )
                    )
                }
                result
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryRouteLegacyKnowledgeCities err", t)
            null
        }
    }

    private fun isRouteStepLimit(errorCode: String, errorMsg: String): Boolean {
        val text = "$errorCode $errorMsg"
        return text.contains("步数") ||
            text.contains("步數") ||
            text.contains("上限") ||
            text.contains("不足") ||
            text.contains("dayLimit", ignoreCase = true)
    }

    private fun tryReviveRouteSteps(config: RouteConfig, currentPathData: JSONObject? = null): Boolean {
        if (!config.reviveSteps) {
            Log.sports("行走路线复活步数已关闭")
            return false
        }
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_ROUTE_REVIVE_TRIED)) {
            Log.sports("行走路线复活步数今日已尝试且不可继续，跳过")
            return false
        }

        if (config.reviveTask) {
            completeRouteReviveTasks()
        }

        val reviveData = queryRouteReviveData()
        if (reviveData == null) {
            return false
        }

        if (hasUsableRouteReviveSteps(currentPathData, reviveData)) {
            return true
        }

        val remainTimes = reviveData.optInt("remainReviveTimes", 0)
        if (remainTimes <= 0) {
            Log.sports("行走路线复活次数不足，今日不再重复尝试")
            Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_ROUTE_REVIVE_TRIED)
            return false
        }

        val candidates = collectReviveCandidates(reviveData)
        if (candidates.isEmpty()) {
            Log.sports("行走路线没有可复活历史步数，今日不再重复尝试")
            Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_ROUTE_REVIVE_TRIED)
            return false
        }

        var attempted = 0
        for (candidate in candidates) {
            if (attempted >= remainTimes) break
            attempted++
            val resp = JSONObject(AntSportsRpcCall.reviveSteps(candidate.date))
            if (isSportsRpcSuccess(resp)) {
                val reviveCount = resp.optJSONObject("data")?.optInt("reviveCount", candidate.count) ?: candidate.count
                Log.sports("行走路线复活步数成功[date=${candidate.date}, count=$reviveCount]")
                return true
            }

            val errorCode = extractSportsRpcErrorCode(resp)
            val errorMsg = extractSportsRpcErrorMessage(resp)
            Log.error(
                TAG,
                "行走路线复活步数失败[date=${candidate.date}, count=${candidate.count}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$resp"
            )
        }

        Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_ROUTE_REVIVE_TRIED)
        return false
    }

    private fun hasUsableRouteReviveSteps(currentPathData: JSONObject?, reviveData: JSONObject): Boolean {
        val reviveRemainStepCount = reviveData.optInt("remainStepCount", 0)
        if (reviveRemainStepCount <= 0 || currentPathData == null) {
            return false
        }

        val currentStep = currentPathData.optJSONObject("userPathStep")
        val pathObj = currentPathData.optJSONObject("path")
        val pathId = currentStep?.optString("pathId", pathObj?.optString("pathId", "").orEmpty())
            ?.takeIf { it.isNotBlank() }
            ?: return false
        val minGoStepCount = pathObj?.optInt("minGoStepCount", 1)?.coerceAtLeast(1) ?: 1
        if (reviveRemainStepCount < minGoStepCount) {
            return false
        }

        val refreshedPathData = queryPath(pathId) ?: return false
        val refreshedStep = refreshedPathData.optJSONObject("userPathStep") ?: return false
        val refreshedRemainStepCount = refreshedStep.optInt("remainStepCount", 0)
        val dayLimit = refreshedStep.optBoolean("dayLimit", false)
        if (!dayLimit && refreshedRemainStepCount >= minGoStepCount) {
            Log.sports("行走路线检测到已复活步数[count=$reviveRemainStepCount, routeRemain=$refreshedRemainStepCount]，继续行走"
            )
            return true
        }

        Log.sports("行走路线复活明细存在可用步数但当前路线仍不可行走[reviveRemain=$reviveRemainStepCount, routeRemain=$refreshedRemainStepCount, min=$minGoStepCount, dayLimit=$dayLimit]"
        )
        return false
    }

    private fun completeRouteReviveTasks() {
        runCatching {
            val finishStatus = JSONObject(AntSportsRpcCall.queryReviveTaskFinishStatus())
            if (!isSportsRpcSuccess(finishStatus)) {
                Log.error(
                    TAG,
                    "行走路线复活任务状态查询失败[code=${extractSportsRpcErrorCode(finishStatus).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(finishStatus)}] raw=$finishStatus"
                )
            }
        }.onFailure {
            Log.printStackTrace(TAG, "queryReviveTaskFinishStatus err", it)
        }

        val taskListResp = JSONObject(AntSportsRpcCall.queryReviveTaskList())
        if (!isSportsRpcSuccess(taskListResp)) {
            Log.error(
                TAG,
                "行走路线复活任务列表查询失败[code=${extractSportsRpcErrorCode(taskListResp).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(taskListResp)}] raw=$taskListResp"
            )
            return
        }

        val tasks = taskListResp.optJSONObject("data")?.optJSONArray("reviveTaskLists") ?: return
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            val taskId = task.optString("taskId", "")
            val taskName = task.optString("taskName", taskId)
            val taskStatus = task.optString("taskStatus", "")
            val taskType = task.optString("taskType", "")
            val taskBizId = task.optJSONObject("bizExtMap")?.optString("bizId", "").orEmpty()
            val isAdTask = task.optBoolean("adTask", false) || taskType == "AD_TASK"
            if (taskId.isBlank() || taskStatus != "WAIT_COMPLETE") {
                continue
            }
            if (task.optBoolean("needSignUp", false)) {
                Log.sports("行走路线复活任务[需要报名，跳过：$taskName，taskId=$taskId]")
                continue
            }
            if (!isAdTask && taskType != "BROWSER") {
                Log.sports("行走路线复活任务[非浏览任务跳过：$taskName，taskId=$taskId，type=$taskType]")
                continue
            }
            if (isAdTask && taskBizId.isBlank()) {
                Log.error(TAG, "行走路线复活任务[广告任务缺少bizId，跳过：$taskName，taskId=$taskId]")
                continue
            }

            val triggerResp = JSONObject(
                AntSportsRpcCall.triggerReviveTask(
                    taskId,
                    taskBizId.takeIf { it.isNotBlank() }
                )
            )
            if (!isSportsRpcSuccess(triggerResp)) {
                Log.error(
                    TAG,
                    "行走路线复活任务触发失败[$taskName][taskId=$taskId][code=${extractSportsRpcErrorCode(triggerResp).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(triggerResp)}] raw=$triggerResp"
                )
                continue
            }
            GlobalThreadPools.sleepCompat(500)
            if (isAdTask) {
                val adFinishResp = JSONObject(AntSportsRpcCall.finishAdTask(taskBizId))
                if (isSportsRpcSuccess(adFinishResp)) {
                    Log.sports("行走路线复活广告任务完成[$taskName][taskId=$taskId][bizId=$taskBizId]")
                    GlobalThreadPools.sleepCompat(500)
                    refreshRouteReviveTaskFinishStatus(taskName, taskId)
                } else {
                    Log.error(
                        TAG,
                        "行走路线复活广告任务完成失败[$taskName][taskId=$taskId][bizId=$taskBizId][code=${extractSportsRpcErrorCode(adFinishResp).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(adFinishResp)}] raw=$adFinishResp"
                    )
                    GlobalThreadPools.sleepCompat(500)
                }
                continue
            }
            val completeResp = JSONObject(AntSportsRpcCall.completeReviveTask(taskId))
            if (isSportsRpcSuccess(completeResp)) {
                Log.sports("行走路线复活任务完成[$taskName][taskId=$taskId]")
                GlobalThreadPools.sleepCompat(500)
                refreshRouteReviveTaskFinishStatus(taskName, taskId)
            } else {
                Log.error(
                    TAG,
                    "行走路线复活任务完成失败[$taskName][taskId=$taskId][code=${extractSportsRpcErrorCode(completeResp).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(completeResp)}] raw=$completeResp"
                )
                GlobalThreadPools.sleepCompat(500)
            }
        }
    }

    private fun refreshRouteReviveTaskFinishStatus(taskName: String, taskId: String) {
        runCatching {
            val finishStatus = JSONObject(AntSportsRpcCall.queryReviveTaskFinishStatus())
            if (!isSportsRpcSuccess(finishStatus)) {
                Log.error(
                    TAG,
                    "行走路线复活任务完成状态刷新失败[$taskName][taskId=$taskId][code=${extractSportsRpcErrorCode(finishStatus).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(finishStatus)}] raw=$finishStatus"
                )
                return
            }
            val data = finishStatus.optJSONObject("data")
            if (data?.optBoolean("complete", false) == true) {
                val confirmedTaskId = data.optString("taskId", taskId).ifBlank { taskId }
                Log.sports("行走路线复活任务完成状态已确认[$taskName][taskId=$confirmedTaskId]")
            } else {
                Log.sports("行走路线复活任务完成状态未确认[$taskName][taskId=$taskId]")
            }
        }.onFailure {
            Log.printStackTrace(TAG, "queryReviveTaskFinishStatus err", it)
        }
    }

    private fun queryRouteReviveData(): JSONObject? {
        val resp = JSONObject(AntSportsRpcCall.queryUserReviveStepT2())
        if (!isSportsRpcSuccess(resp)) {
            Log.error(
                TAG,
                "行走路线复活步数明细查询失败[code=${extractSportsRpcErrorCode(resp).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(resp)}] raw=$resp"
            )
            return null
        }
        return resp.optJSONObject("data")
    }

    private fun collectReviveCandidates(reviveData: JSONObject): List<ReviveCandidate> {
        val result = mutableListOf<ReviveCandidate>()
        val stepRevives = reviveData.optJSONArray("stepRevives") ?: return result
        for (i in 0 until stepRevives.length()) {
            val item = stepRevives.optJSONObject(i) ?: continue
            val date = item.optString("date", "")
            val count = item.optInt("count", 0)
            if (date.isNotBlank() && count > 0) {
                result.add(ReviveCandidate(date, count))
            }
        }
        return result.sortedByDescending { it.count }
    }

    /**
     * @brief 查询世界地图
     */
    private fun queryWorldMap(themeId: String?): JSONObject? {
        var theme: JSONObject? = null
        if (themeId.isNullOrEmpty()) return null
        try {
            val jo = JSONObject(AntSportsRpcCall.queryWorldMap(themeId))
            if (ResChecker.checkRes(TAG, "queryWorldMap失败： [ThemeID: $themeId]: ", jo)) {
                theme = jo.getJSONObject("data")
            } else {
                Log.error(TAG, "queryWorldMap失败： [ThemeID: $themeId]: $jo")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryWorldMap err:", t)
        }
        return theme
    }

    /**
     * @brief 查询指定城市的路线详情
     * @param cityId 城市 ID
     */
    private fun queryCityPath(cityId: String): JSONObject? {
        var city: JSONObject? = null
        try {
            val jo = JSONObject(AntSportsRpcCall.queryCityPath(cityId))
            if (ResChecker.checkRes(TAG, jo)) {
                city = jo.getJSONObject("data")
            } else {
                Log.error(TAG, "queryCityPath失败： [CityID: $cityId]$jo")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryCityPath err:", t)
        }
        return city
    }

    /**
     * @brief 查询路线详情（同时触发宝箱领取）
     */
    /*
    private fun queryPath(pathId: String): JSONObject? {
        var path: JSONObject? = null
        try {
            val date = Date()
            @SuppressLint("SimpleDateFormat") val sdf = SimpleDateFormat("yyyy-MM-dd")
            val jo = JSONObject(AntSportsRpcCall.queryPath(sdf.format(date), pathId))
            if (ResChecker.checkRes(TAG, jo)) {
                path = jo.getJSONObject("data")
                val ja = jo.getJSONObject("data").getJSONArray("treasureBoxList")
                for (i in 0 until ja.length()) {
                    val treasureBox = ja.getJSONObject(i)
                    receiveEvent(treasureBox.getString("boxNo"))
                }
            } else {
                Log.error(TAG, "queryPath失败： $jo")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryPath err:", t)
        }
        return path
    }*/


    //这里会返回路线详情
    private fun queryPath(pathId: String): JSONObject? {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val response = AntSportsRpcCall.queryPath(dateStr, pathId)
            val jo = JSONObject(response)

            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error(TAG, "queryPath 请求失败: $response")
                return null
            }

            // 2. 检查数据节点是否存在
            val data = jo.optJSONObject("data")
            if (data == null) {
                Log.error(TAG, "queryPath 响应成功但 data 节点为空: $response")
                return null
            }

            // --- 逻辑处理 ---
            val userPath = data.optJSONObject("userPathStep")
            Log.sports("路线: ${userPath?.optString("pathName")}, 进度: ${userPath?.optInt("pathProgress")}%")

            processRouteEvents(data)

            return data
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryPath 过程中发生崩溃", t)
        }
        return null
    }

    private fun processRouteEvents(data: JSONObject?) {
        if (data == null) return

        val boxList = data.optJSONArray("treasureBoxList")
        if (boxList != null && boxList.length() > 0) {
            for (i in 0 until boxList.length()) {
                val box = boxList.optJSONObject(i) ?: continue
                val eventBillNo = box.optString("eventBillNo", "")
                    .ifBlank { box.optString("boxNo", "") }
                if (eventBillNo.isNotBlank()) {
                    receiveEvent(eventBillNo)
                }
            }
        }

        val cityKnowledgeList = data.optJSONArray("cityKnowledgeList")
        if (cityKnowledgeList != null && cityKnowledgeList.length() > 0) {
            for (i in 0 until cityKnowledgeList.length()) {
                val knowledge = cityKnowledgeList.optJSONObject(i) ?: continue
                val eventBillNo = knowledge.optString("eventBillNo", "")
                if (eventBillNo.isNotBlank()) {
                    Log.sports("行走路线📍领取城市见闻[${knowledge.optString("name", knowledge.optString("knowledgeId", eventBillNo))}]"
                    )
                    receiveEvent(eventBillNo)
                }
            }
        }
    }

    /**
     * @brief 新版路线开启宝箱并打印奖励
     */
    private fun receiveEvent(eventBillNo: String) {
        try {
            val jo = JSONObject(AntSportsRpcCall.receiveEvent(eventBillNo))
            if (!isSportsRpcSuccess(jo)) {
                val errorCode = extractSportsRpcErrorCode(jo)
                val errorMsg = extractSportsRpcErrorMessage(jo)
                if (isRouteEventDuplicate(errorCode, errorMsg)) {
                    Log.sports("行走路线🎁事件已领取，跳过重复领取[eventBillNo=$eventBillNo][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]")
                    return
                }
                Log.error(
                    TAG,
                    "行走路线🎁领取事件失败[eventBillNo=$eventBillNo][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$jo"
                )
                return
            }

            val ja = jo.optJSONObject("data")?.optJSONArray("rewards")
            if (ja == null || ja.length() == 0) {
                Log.sports("行走路线🎁事件领取成功[eventBillNo=$eventBillNo]")
                return
            }
            for (i in 0 until ja.length()) {
                val reward = ja.getJSONObject(i)
                Log.sports("行走路线🎁领取奖励[${reward.optString("rewardName", reward.optString("name", "未知奖励"))}]*${reward.optInt("count", 1)}"
                )
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "receiveEvent err:", t)
        }
    }

    private fun isRouteEventDuplicate(errorCode: String, errorMsg: String): Boolean {
        return errorCode == "RECEIVE_REWARD_REPEATED" ||
            errorMsg.contains("已领取") ||
            errorMsg.contains("已经领取") ||
            errorMsg.contains("重复")
    }

    /**
     * @brief 根据主题 ID 挑选可加入的 pathId
     */
    private fun queryJoinPath(themeId: String?): String? {
        if (walkCustomPath.value == true) {
            walkCustomPathId.value?.takeIf { it.isNotBlank() }?.let { return it }
        }
        var pathId: String? = null
        try {
            val theme = queryWorldMap(themeId)
            if (theme == null) {
                Log.error(TAG, "queryJoinPath-> theme 失败：$theme")
                return null
            }
            val cityList = theme.getJSONArray("cityList")
            for (i in 0 until cityList.length()) {
                val cityId = cityList.getJSONObject(i).getString("cityId")
                val city = queryCityPath(cityId) ?: continue
                val cityPathList = city.getJSONArray("cityPathList")
                for (j in 0 until cityPathList.length()) {
                    val cityPath = cityPathList.getJSONObject(j)
                    pathId = cityPath.getString("pathId")
                    if ("COMPLETED" != cityPath.getString("pathCompleteStatus")) {
                        return pathId
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryJoinPath err:", t)
        }
        return pathId
    }

    /**
     * @brief 加入新版路线
     */
    private fun joinPath(pathId: String?) {
        var realPathId = pathId
        if (realPathId == null) {
            // 默认龙年祈福线
            realPathId = "p0002023122214520001"
        }
        try {
            val jo = JSONObject(AntSportsRpcCall.joinPath(realPathId))
            if (isSportsRpcSuccess(jo)) {
                val path = queryPath(realPathId)
                Log.sports("行走路线🚶🏻‍♂️路线[${path?.optJSONObject("path")?.optString("name", realPathId)}]已加入")
            } else {
                val errorCode = extractSportsRpcErrorCode(jo)
                val errorMsg = extractSportsRpcErrorMessage(jo)
                if (isSportsRouteBusinessTerminal(errorCode, errorMsg)) {
                    Log.sports("行走路线🚶🏻‍♂️加入路线业务终态[pathId=$realPathId][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                    )
                    return
                }
                Log.error(
                    TAG,
                    "行走路线🚶🏻‍♂️加入路线失败[pathId=$realPathId][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$jo"
                )
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "joinPath err:", t)
        }
    }

    /**
     * @brief 根据配置索引同步更新路线主题 ID
     */
    internal fun getWalkPathThemeIdOnConfig() {
        val index = walkPathTheme.value ?: WalkPathTheme.DA_MEI_ZHONG_GUO
        if (index in 0 until WalkPathTheme.themeIds.size) {
            walkPathThemeId = WalkPathTheme.themeIds[index]
        } else {
            Log.error(TAG, "非法的路线主题索引: $index，已回退至默认主题")
            walkPathThemeId = WalkPathTheme.themeIds[WalkPathTheme.DA_MEI_ZHONG_GUO]
        }
    }

    // ---------------------------------------------------------------------
    // 旧版行走路线（保留兼容）
    // ---------------------------------------------------------------------

    /**
     * @brief 旧版行走路线首页逻辑（开宝箱 + 行走 + 加入路线）
     */
    internal fun queryMyHomePage(loader: ClassLoader) {
        try {
            var s = AntSportsRpcCall.queryMyHomePage()
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val pathJoinStatus = jo.getString("pathJoinStatus")
                if ("GOING" == pathJoinStatus) {
                    if (jo.has("pathCompleteStatus")) {
                        if ("COMPLETED" == jo.getString("pathCompleteStatus")) {
                            jo = JSONObject(AntSportsRpcCall.queryBaseList())
                            if (ResChecker.checkRes(TAG, jo)) {
                                val allPathBaseInfoList = jo.getJSONArray("allPathBaseInfoList")
                                val otherAllPathBaseInfoList = jo.getJSONArray("otherAllPathBaseInfoList")
                                    .getJSONObject(0)
                                    .getJSONArray("allPathBaseInfoList")
                                join(loader, allPathBaseInfoList, otherAllPathBaseInfoList, "")
                            } else {
                                Log.sports(jo.getString("resultDesc"))
                            }
                        }
                    } else {
                        val rankCacheKey = jo.getString("rankCacheKey")
                        val ja = jo.getJSONArray("treasureBoxModelList")
                        for (i in 0 until ja.length()) {
                            parseTreasureBoxModel(loader, ja.getJSONObject(i), rankCacheKey)
                        }
                        val joPathRender = jo.getJSONObject("pathRenderModel")
                        val title = joPathRender.getString("title")
                        val minGoStepCount = joPathRender.getInt("minGoStepCount")
                        jo = jo.getJSONObject("dailyStepModel")
                        val consumeQuantity = jo.getInt("consumeQuantity")
                        val produceQuantity = jo.getInt("produceQuantity")
                        val day = jo.getString("day")
                        val canMoveStepCount = produceQuantity - consumeQuantity
                        if (canMoveStepCount >= minGoStepCount) {
                            go(loader, day, rankCacheKey, canMoveStepCount, title)
                        }
                    }
                } else if ("NOT_JOIN" == pathJoinStatus) {
                    val firstJoinPathTitle = jo.getString("firstJoinPathTitle")
                    val allPathBaseInfoList = jo.getJSONArray("allPathBaseInfoList")
                    val otherAllPathBaseInfoList = jo.getJSONArray("otherAllPathBaseInfoList")
                        .getJSONObject(0)
                        .getJSONArray("allPathBaseInfoList")
                    join(loader, allPathBaseInfoList, otherAllPathBaseInfoList, firstJoinPathTitle)
                }
            } else {
                Log.sports(jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryMyHomePage err:", t)
        }
    }

    /**
     * @brief 旧版路线加入逻辑（根据可解锁路径列表）
     */
    private fun join(
        loader: ClassLoader,
        allPathBaseInfoList: JSONArray,
        otherAllPathBaseInfoList: JSONArray,
        firstJoinPathTitle: String
    ) {
        try {
            var index = -1
            var title: String? = null
            var pathId: String? = null
            var jo: JSONObject

            for (i in allPathBaseInfoList.length() - 1 downTo 0) {
                jo = allPathBaseInfoList.getJSONObject(i)
                if (jo.getBoolean("unlocked")) {
                    title = jo.getString("title")
                    pathId = jo.getString("pathId")
                    index = i
                    break
                }
            }
            if (index < 0 || index == allPathBaseInfoList.length() - 1) {
                for (j in otherAllPathBaseInfoList.length() - 1 downTo 0) {
                    jo = otherAllPathBaseInfoList.getJSONObject(j)
                    if (jo.getBoolean("unlocked")) {
                        if (j != otherAllPathBaseInfoList.length() - 1 || index != allPathBaseInfoList.length() - 1) {
                            title = jo.getString("title")
                            pathId = jo.getString("pathId")
                            index = j
                        }
                        break
                    }
                }
            }
            if (index >= 0) {
                val s = if (title == firstJoinPathTitle) {
                    AntSportsRpcCall.openAndJoinFirst()
                } else {
                    AntSportsRpcCall.join(pathId ?: "")
                }
                jo = JSONObject(s)
                if (ResChecker.checkRes(TAG, jo)) {
                    Log.sports("加入线路🚶🏻‍♂️[$title]")
                    queryMyHomePage(loader)
                } else {
                    Log.sports(jo.getString("resultDesc"))
                }
            } else {
                Log.sports("好像没有可走的线路了！")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "join err:", t)
        }
    }

    /**
     * @brief 旧版路线行走逻辑
     */
    private fun go(loader: ClassLoader, day: String, rankCacheKey: String, stepCount: Int, title: String) {
        try {
            val s = AntSportsRpcCall.go(day, rankCacheKey, stepCount)
            val jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                Log.sports("行走线路🚶🏻‍♂️[$title]#前进了${jo.getInt("goStepCount")}步")
                val completed = "COMPLETED" == jo.getString("completeStatus")
                val ja = jo.getJSONArray("allTreasureBoxModelList")
                for (i in 0 until ja.length()) {
                    parseTreasureBoxModel(loader, ja.getJSONObject(i), rankCacheKey)
                }
                if (completed) {
                    Log.sports("完成线路🚶🏻‍♂️[$title]")
                    queryMyHomePage(loader)
                }
            } else {
                Log.sports(jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "go err:", t)
        }
    }

    /**
     * @brief 解析旧版宝箱模型并按时间安排子任务开箱
     */
    private fun parseTreasureBoxModel(loader: ClassLoader, jo: JSONObject, rankCacheKey: String) {
        try {
            val canOpenTime = jo.getString("canOpenTime")
            val issueTime = jo.getString("issueTime")
            val boxNo = jo.getString("boxNo")
            val userId = jo.getString("userId")
            if (canOpenTime == issueTime) {
                openTreasureBox(boxNo, userId)
            } else {
                val cot = canOpenTime.toLong()
                val now = rankCacheKey.toLong()
                val delay = cot - now
                if (delay <= 0) {
                    openTreasureBox(boxNo, userId)
                    return
                }
                val checkIntervalMs = BaseModel.checkInterval.value?.toLong() ?: 0L
                if (delay < checkIntervalMs) {
                    val taskId = "BX|$boxNo"
                    if (hasChildTask(taskId)) return
                    val triggerAtMs = System.currentTimeMillis() + delay
                    Log.sports("还有 $delay ms 开运动宝箱")
                    addChildTask(
                        ChildModelTask(
                            taskId,
                            "BX",
                            Runnable {
                                cancelPersistentChildTask(taskId)
                                Log.sports("蹲点开箱开始")
                                val startTime = System.currentTimeMillis()
                                while (System.currentTimeMillis() - startTime < 5_000) {
                                    if (openTreasureBox(boxNo, userId) > 0) {
                                        break
                                    }
                                    GlobalThreadPools.sleepCompat(200)
                                }
                            },
                            triggerAtMs
                        )
                    )
                    registerPersistentChildTask(
                        taskId,
                        "BX",
                        triggerAtMs,
                        JSONObject()
                            .put("box_no", boxNo)
                            .put("user_id", userId)
                            .put("can_open_time", cot)
                    )
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "parseTreasureBoxModel err:", t)
        }
    }

    private fun runTreasureBoxPersistentTask(childId: String, payload: JSONObject) {
        val boxNo = payload.optString("box_no").trim().ifBlank {
            childId.substringAfter("BX|", "")
        }
        val userId = payload.optString("user_id").trim()
        if (boxNo.isBlank() || userId.isBlank()) {
            Log.sports("运动持久宝箱[$childId]缺少 box_no/user_id，跳过")
            return
        }
        Log.sports("蹲点开箱开始")
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 5_000) {
            if (openTreasureBox(boxNo, userId) > 0) {
                break
            }
            GlobalThreadPools.sleepCompat(200)
        }
    }

    /**
     * @brief 旧版宝箱开启
     * @return 获得的奖励数量
     */
    private fun openTreasureBox(boxNo: String, userId: String): Int {
        try {
            val s = AntSportsRpcCall.openTreasureBox(boxNo, userId)
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val ja = jo.getJSONArray("treasureBoxAwards")
                var num = 0
                for (i in 0 until ja.length()) {
                    jo = ja.getJSONObject(i)
                    num += jo.getInt("num")
                    Log.sports("运动宝箱🎁[$num${jo.getString("name")}]")
                }
                return num
            } else if ("TREASUREBOX_NOT_EXIST" == jo.getString("resultCode")) {
                Log.sports(jo.getString("resultDesc"))
                return 1
            } else {
                Log.sports(jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "openTreasureBox err:", t)
        }
        return 0
    }

    // ---------------------------------------------------------------------
    // 旧版捐步 & 慈善
    // ---------------------------------------------------------------------

    /**
     * @brief 查询慈善项目列表并执行捐赠
     */
    internal fun queryProjectList() {
        try {
            var jo = JSONObject(AntSportsRpcCall.queryProjectList(0))
            if (ResChecker.checkRes(TAG, jo)) {
                val donateAmount = (donateCharityCoinAmount.value ?: return).coerceAtLeast(1)
                if (donateAmount <= 0) return
                var charityCoinCount = jo.getInt("charityCoinCount")
                if (charityCoinCount < donateAmount) return

                val ja = jo.getJSONObject("projectPage").getJSONArray("data")
                for (i in 0 until ja.length()) {
                    if (charityCoinCount < donateAmount) break
                    val basicModel = ja.getJSONObject(i).getJSONObject("basicModel")
                    if ("DONATE_COMPLETED" == basicModel.getString("footballFieldStatus")) break
                    donate(donateAmount, basicModel.getString("projectId"), basicModel.getString("title"))
                    Status.donateCharityCoin()
                    charityCoinCount -= donateAmount
                    if (donateCharityCoinType.value == DonateCharityCoinType.ONE) break
                }
            } else {
                Log.sports(jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryProjectList err:", t)
        }
    }

    /**
     * @brief 执行一次慈善捐赠
     */
    private fun donate(donateCharityCoin: Int, projectId: String, title: String) {
        try {
            val s = AntSportsRpcCall.donate(donateCharityCoin, projectId)
            val jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                Log.sports("捐赠活动❤️[$title][$donateCharityCoin 能量🎈]")
            } else {
                Log.sports(jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "donate err:", t)
        }
    }

    /**
     * @brief 查询行走步数，并根据条件自动捐步
     */
    internal fun queryWalkStep() {
        try {
            var s = AntSportsRpcCall.queryWalkStep()
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val produceQuantity = AntSportsRpcCall.extractWalkStepCount(jo)
                val minExchange = minExchangeCount.value ?: 0
                if (produceQuantity <= 0) {
                    Log.sports("当前暂无可捐步数")
                    return
                }
                if (produceQuantity < minExchange && (latestExchangeTime.isDisabled() || latestExchangeTime.isBeforeCutoff())) {
                    return
                }

                AntSportsRpcCall.walkDonateSignInfo(produceQuantity)
                s = AntSportsRpcCall.donateWalkHome(produceQuantity)
                jo = JSONObject(s)
                if (!jo.optBoolean("isSuccess", false)) {
                    if (s.contains("已捐步") || jo.optString("resultDesc").contains("已捐步")) {
                        Status.exchangeToday(UserMap.currentUid ?: return)
                    }
                    return
                }

                val walkDonateHomeModel = jo.optJSONObject("walkDonateHomeModel") ?: return
                val walkUserInfoModel = walkDonateHomeModel.optJSONObject("walkUserInfoModel")
                if (walkUserInfoModel == null || !walkUserInfoModel.has("exchangeFlag")) {
                    Status.exchangeToday(UserMap.currentUid ?: return)
                    return
                }

                val donateToken = walkDonateHomeModel.optString("donateToken")
                val activityId = walkDonateHomeModel.optJSONObject("walkCharityActivityModel")
                    ?.optString("activityId")
                    .orEmpty()
                if (donateToken.isBlank() || activityId.isBlank()) {
                    Log.sports("捐步兑换缺少 donateToken 或 activityId，跳过")
                    return
                }

                s = AntSportsRpcCall.exchange(activityId, produceQuantity, donateToken)
                jo = JSONObject(s)
                if (jo.optBoolean("isSuccess", false)) {
                    val donateExchangeResultModel = jo.optJSONObject("donateExchangeResultModel")
                    val userCount = donateExchangeResultModel?.optInt("userCount", produceQuantity) ?: produceQuantity
                    val amount = donateExchangeResultModel?.optJSONObject("userAmount")?.optDouble("amount", 0.0) ?: 0.0
                    Log.sports("捐出活动❤️[$userCount 步]#兑换$amount 元公益金")
                    Status.exchangeToday(UserMap.currentUid ?: return)
                } else if (s.contains("已捐步") || jo.optString("resultDesc").contains("已捐步")) {
                    Status.exchangeToday(UserMap.currentUid ?: return)
                } else {
                    Log.sports(jo.optString("resultDesc", s))
                }
            } else {
                Log.sports(jo.optString("resultDesc", s))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryWalkStep err:", t)
        }
    }

    // ---------------------------------------------------------------------
    // 文体中心
    // ---------------------------------------------------------------------

    /**
     * @brief 文体中心任务组查询并自动完成 TODO 状态任务
     */
    internal fun userTaskGroupQuery(groupId: String) {
        try {
            val s = AntSportsRpcCall.userTaskGroupQuery(groupId)
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                jo = jo.getJSONObject("group")
                val userTaskList = jo.getJSONArray("userTaskList")
                for (i in 0 until userTaskList.length()) {
                    jo = userTaskList.getJSONObject(i)
                    if ("TODO" != jo.getString("status")) continue
                    val taskInfo = jo.getJSONObject("taskInfo")
                    val bizType = taskInfo.getString("bizType")
                    val taskId = taskInfo.getString("taskId")
                    val res = JSONObject(AntSportsRpcCall.userTaskComplete(bizType, taskId))
                    if (ResChecker.checkRes(TAG, res)) {
                        val taskName = taskInfo.optString("taskName", taskId)
                        Log.sports("完成任务🧾[$taskName]")
                    } else {
                        Log.sports("文体每日任务 $res")
                    }
                }
            } else {
                Log.sports("文体每日任务 $s")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "userTaskGroupQuery err:", t)
        }
    }

    /**
     * @brief 文体中心走路挑战报名
     */
    internal fun participate() {
        try {
            if (TaskBlacklist.isTaskInBlacklist(SPORTS_TASK_BLACKLIST_MODULE, SPORTS_WALK_CHALLENGE_TITLE)) {
                Log.sports("走路挑战赛线上赛[黑名单跳过]")
                return
            }
            if (Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_WALK_CHALLENGE_UNAVAILABLE_TODAY)) {
                Log.sports("走路挑战赛线上赛[今日已停用，跳过重复报名]")
                return
            }

            val joinedQuery = queryJoinedWalkChallenge()
            if (!joinedQuery.success) {
                return
            }
            var joinedGame = joinedQuery.game

            if (joinedGame == null) {
                val game = findAvailableWalkChallengeGame()
                if (game == null) {
                    Log.sports("走路挑战赛线上赛[未找到可报名比赛]")
                    return
                }

                val event = selectWalkChallengeEvent(game)
                if (event == null) {
                    Log.sports("走路挑战赛线上赛[未找到可报名目标][${game.name}]")
                    return
                }

                val res = JSONObject(
                    AntSportsRpcCall.userOnlineGameSignup(
                        game.gameId,
                        event.gameEventId,
                        event.rightsPackageId
                    )
                )
                if (isSportsRpcSuccess(res)) {
                    val data = unwrapSportsRpcPayload(res).optJSONObject("data")
                    val targetValue = data?.optDouble("totalProgressValue", event.progressValue) ?: event.progressValue
                    val targetUnit = data?.optString("userProgressGameUnit", event.progressUnit) ?: event.progressUnit
                    val target = formatWalkChallengeTarget(targetValue, targetUnit)
                    Log.sports("走路挑战赛线上赛🚶🏻‍♂️报名[${game.name}][${event.title}]#$target")
                    joinedGame = parseWalkChallengeGameFromUserOnlineGame(
                        onlineGame = null,
                        userOnlineGame = data,
                        fallbackName = game.name
                    ) ?: queryJoinedWalkChallenge().game
                } else {
                    val errorCode = extractSportsRpcErrorCode(res)
                    val errorMsg = extractSportsRpcErrorMessage(res)
                    if (isWalkChallengeAlreadyJoinedError(errorCode, errorMsg)) {
                        Log.sports("走路挑战赛线上赛[已报名或不可重复][${game.name}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                        )
                        joinedGame = queryJoinedWalkChallenge().game
                    } else if (
                        errorCode == "3000" ||
                        errorMsg.contains("系统出错") ||
                        errorMsg.contains("系統出錯")
                    ) {
                        Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_WALK_CHALLENGE_UNAVAILABLE_TODAY)
                        Log.sports("走路挑战赛线上赛[暂不可用][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$res"
                        )
                        return
                    } else {
                        Log.error(
                            TAG,
                            "走路挑战赛线上赛报名失败[${game.name}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$res"
                        )
                        return
                    }
                }
            }

            if (joinedGame == null) {
                Log.sports("走路挑战赛线上赛[报名状态未确认，跳过今日运动提交]")
                return
            }

            submitWalkChallengeDailyProgress(joinedGame)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "participate err:", t)
        }
    }

    private fun queryJoinedWalkChallenge(): WalkChallengeJoinQuery {
        val jo = JSONObject(AntSportsRpcCall.userOnlineGameListQuery("JOIN"))
        if (!isSportsRpcSuccess(jo)) {
            val errorCode = extractSportsRpcErrorCode(jo)
            val errorMsg = extractSportsRpcErrorMessage(jo)
            Log.sports("走路挑战赛线上赛[查询已报名失败][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$jo"
            )
            return WalkChallengeJoinQuery(success = false)
        }

        val userDetailList = jo.optJSONArray("userDetailList") ?: return WalkChallengeJoinQuery(success = true)
        for (i in 0 until userDetailList.length()) {
            val detail = userDetailList.optJSONObject(i) ?: continue
            val onlineGame = detail.optJSONObject("onlineGame") ?: continue
            if (!isWalkChallengeOnlineGame(onlineGame)) continue
            val userOnlineGame = detail.optJSONObject("userOnlineGame") ?: continue
            val status = userOnlineGame.optString("status", "")
            if (!status.equals("JOIN", ignoreCase = true)) continue
            val game = parseWalkChallengeGameFromUserOnlineGame(onlineGame, userOnlineGame) ?: continue
            Log.sports(
                "走路挑战赛线上赛🚶🏻‍♂️[已报名][${game.name}]#" +
                    formatWalkChallengeTarget(game.totalProgressValue, game.progressUnit)
            )
            return WalkChallengeJoinQuery(success = true, game = game)
        }
        return WalkChallengeJoinQuery(success = true)
    }

    private fun parseWalkChallengeGameFromUserOnlineGame(
        onlineGame: JSONObject?,
        userOnlineGame: JSONObject?,
        fallbackName: String = ""
    ): WalkChallengeGame? {
        if (userOnlineGame == null) {
            return null
        }
        val gameId = userOnlineGame.optString("gameId", onlineGame?.optString("gameId", "").orEmpty())
        if (gameId.isBlank()) {
            return null
        }
        return WalkChallengeGame(
            gameId = gameId,
            name = onlineGame?.optString("name", fallbackName)?.ifBlank { gameId } ?: fallbackName.ifBlank { gameId },
            userGameId = userOnlineGame.optString("userGameId", userOnlineGame.optString("outUserGameNo", "")),
            totalProgressValue = userOnlineGame.optDouble("totalProgressValue", 0.0),
            userProgressGameValue = userOnlineGame.optDouble("userProgressGameValue", 0.0),
            progressUnit = userOnlineGame.optString("userProgressGameUnit", ""),
            sportsDataType = onlineGame?.optString("sportsDataType", "").orEmpty()
        )
    }

    private fun queryWalkChallengeDetail(game: WalkChallengeGame): WalkChallengeGame? {
        val jo = JSONObject(AntSportsRpcCall.userOnlineGameDetailQuery(game.gameId))
        if (!isSportsRpcSuccess(jo)) {
            val errorCode = extractSportsRpcErrorCode(jo)
            val errorMsg = extractSportsRpcErrorMessage(jo)
            Log.sports("走路挑战赛线上赛[详情查询失败][${game.name}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$jo"
            )
            return null
        }
        val payload = unwrapSportsRpcPayload(jo)
        return parseWalkChallengeGameFromUserOnlineGame(
            onlineGame = payload.optJSONObject("onlineGame"),
            userOnlineGame = payload.optJSONObject("userOnlineGame"),
            fallbackName = game.name
        )
    }

    private fun submitWalkChallengeDailyProgress(game: WalkChallengeGame) {
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_WALK_CHALLENGE_PROGRESS_DONE)) {
            Log.sports("走路挑战赛线上赛[今日已提交运动，跳过]")
            return
        }

        val latestGame = queryWalkChallengeDetail(game) ?: game
        if (isWalkChallengeProgressCompleted(latestGame)) {
            Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_WALK_CHALLENGE_PROGRESS_DONE)
            Log.sports(
                "走路挑战赛线上赛🚶🏻‍♂️[已达成][${latestGame.name}]#" +
                    "${formatWalkChallengeTarget(latestGame.userProgressGameValue, latestGame.progressUnit)}/" +
                    formatWalkChallengeTarget(latestGame.totalProgressValue, latestGame.progressUnit)
            )
            return
        }

        if (hasWalkChallengeSubmittedToday(latestGame)) {
            Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_WALK_CHALLENGE_PROGRESS_DONE)
            return
        }

        if (syncStepInProgress) {
            Log.sports("走路挑战赛线上赛[等待同步步数完成，跳过本轮]")
            return
        }

        val stepCount = resolveWalkChallengeDailyStepCount()
        if (stepCount < WALK_CHALLENGE_MIN_STEP_COUNT) {
            Log.sports("走路挑战赛线上赛[今日步数不足，跳过提交][当前=${stepCount}步][最低=${WALK_CHALLENGE_MIN_STEP_COUNT}步]"
            )
            return
        }

        val sportsType = selectWalkChallengeSportsType(latestGame)
        val toolPageRes = JSONObject(AntSportsRpcCall.querySportsToolPage(latestGame.gameId, sportsType))
        if (!isSportsRpcSuccess(toolPageRes)) {
            val errorCode = extractSportsRpcErrorCode(toolPageRes)
            val errorMsg = extractSportsRpcErrorMessage(toolPageRes)
            Log.error(
                TAG,
                "走路挑战赛线上赛运动工具页查询失败[${latestGame.name}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$toolPageRes"
            )
            return
        }

        runCatching { AntSportsRpcCall.querySportsToolConfig() }
        runCatching { AntSportsRpcCall.queryUserMovingRecord() }
        runCatching { AntSportsRpcCall.queryAudioConfig() }
        runCatching { AntSportsRpcCall.syncSportsDeviceAuthInfo() }

        val startRes = JSONObject(AntSportsRpcCall.startSports(latestGame.gameId, sportsType))
        if (!isSportsRpcSuccess(startRes)) {
            val errorCode = extractSportsRpcErrorCode(startRes)
            val errorMsg = extractSportsRpcErrorMessage(startRes)
            Log.error(
                TAG,
                "走路挑战赛线上赛开始运动失败[${latestGame.name}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$startRes"
            )
            return
        }
        val recordId = unwrapSportsRpcPayload(startRes).optJSONObject("data")?.optString("recordId", "").orEmpty()
        if (recordId.isBlank()) {
            Log.error(TAG, "走路挑战赛线上赛开始运动成功但缺少 recordId raw=$startRes")
            return
        }

        val record = buildWalkChallengeSportRecord(recordId, sportsType, stepCount, latestGame)
        val finishRes = JSONObject(AntSportsRpcCall.finishSports(buildWalkChallengeFinishRecord(record), true))
        if (!isSportsRpcSuccess(finishRes)) {
            val errorCode = extractSportsRpcErrorCode(finishRes)
            val errorMsg = extractSportsRpcErrorMessage(finishRes)
            Log.error(
                TAG,
                "走路挑战赛线上赛结束运动失败[${latestGame.name}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$finishRes"
            )
            return
        }

        runCatching { AntSportsRpcCall.querySportsRecordDetail(latestGame.gameId, record.recordId) }
        val particleRes = runCatching {
            JSONObject(
                AntSportsRpcCall.finishSyncParticles(
                    recordId = record.recordId,
                    sportsType = record.sportsType,
                    stepCount = record.stepCount,
                    distance = record.distance,
                    durationMillis = record.durationSeconds * 1000L,
                    startTime = record.startTime,
                    endTime = record.finishTime,
                    index = (record.durationSeconds / 3).coerceAtLeast(1)
                )
            )
        }.getOrNull()
        if (particleRes != null && !isSportsRpcSuccess(particleRes)) {
            Log.sports("走路挑战赛线上赛粒子统计同步失败[code=${extractSportsRpcErrorCode(particleRes).ifEmpty { "UNKNOWN" }}]" +
                    "[msg=${extractSportsRpcErrorMessage(particleRes)}] raw=$particleRes"
            )
        }

        val confirmed = confirmWalkChallengeProgressRecorded(latestGame, record)
        Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_WALK_CHALLENGE_PROGRESS_DONE)
        val confirmSuffix = if (confirmed) "已确认" else "已提交待回查"
        Log.sports(
            "走路挑战赛线上赛🚶🏻‍♂️运动提交[$confirmSuffix][${latestGame.name}]" +
                "[${record.stepCount}步][${formatWalkChallengeNumber(record.distance)}m]"
        )
    }

    private fun isWalkChallengeProgressCompleted(game: WalkChallengeGame): Boolean {
        return game.totalProgressValue > 0.0 && game.userProgressGameValue >= game.totalProgressValue
    }

    private fun hasWalkChallengeSubmittedToday(game: WalkChallengeGame): Boolean {
        if (game.userGameId.isBlank()) {
            return false
        }
        val jo = JSONObject(AntSportsRpcCall.userOnlineGameDataQuery(game.gameId, game.userGameId))
        if (!isSportsRpcSuccess(jo)) {
            val errorCode = extractSportsRpcErrorCode(jo)
            val errorMsg = extractSportsRpcErrorMessage(jo)
            Log.sports("走路挑战赛线上赛[运动数据查询失败][${game.name}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
            )
            return false
        }
        val list = unwrapSportsRpcPayload(jo).optJSONArray("userGameDataList") ?: return false
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val source = item.optString("userDataOriginSource", "")
            if (!source.equals("SPORT_UTILS", ignoreCase = true)) continue
            val end = item.optLong("gmtEnd", item.optLong("gmtStart", 0L))
            if (end > 0L && !TimeUtil.isToday(end)) continue
            val count = item.optDouble("userGameDataCount", 0.0)
            if (count <= 0.0) continue
            val unit = item.optString("userGameDataUnit", game.progressUnit)
            Log.sports(
                "走路挑战赛线上赛🚶🏻‍♂️[今日已提交][${game.name}]#" +
                    formatWalkChallengeTarget(count, unit)
            )
            return true
        }
        return false
    }

    private fun confirmWalkChallengeProgressRecorded(
        game: WalkChallengeGame,
        record: WalkChallengeSportRecord
    ): Boolean {
        if (hasWalkChallengeSubmittedToday(game)) {
            return true
        }
        val latest = queryWalkChallengeDetail(game) ?: return false
        return latest.userProgressGameValue >= game.userProgressGameValue + min(record.distance, 1.0)
    }

    private fun resolveWalkChallengeDailyStepCount(): Int {
        val currentStep = queryCurrentWalkStepCount()
        if (currentStep != null && currentStep > 0) {
            return currentStep
        }
        if (cachedTargetDailyStep > 0) {
            return cachedTargetDailyStep
        }
        if (cachedOriginDailyStep > 0) {
            return cachedOriginDailyStep
        }
        return 0
    }

    private fun selectWalkChallengeSportsType(game: WalkChallengeGame): String {
        val sportsDataType = game.sportsDataType.lowercase(Locale.ROOT)
        return when {
            sportsDataType.contains("walk") -> WALK_CHALLENGE_SPORTS_TYPE
            sportsDataType.contains("run") -> "run"
            else -> WALK_CHALLENGE_SPORTS_TYPE
        }
    }

    private fun buildWalkChallengeSportRecord(
        recordId: String,
        sportsType: String,
        stepCount: Int,
        game: WalkChallengeGame
    ): WalkChallengeSportRecord {
        val distance = max(
            WALK_CHALLENGE_MIN_DISTANCE_METER,
            stepCount * WALK_CHALLENGE_STEP_LENGTH_METER
        )
        val durationSeconds = max(90, (stepCount * 60 / 55).coerceAtLeast(90))
        val finishTime = System.currentTimeMillis()
        val startTime = finishTime - durationSeconds * 1000L
        val averageSpeed = if (distance > 0.0) {
            durationSeconds / (distance / 1000.0) / 60.0
        } else {
            0.0
        }
        val calories = 60.0 * distance * WALK_CHALLENGE_WALK_CALORIE_FACTOR / 1000.0
        val goalValue = formatWalkChallengeGoalValue(game.totalProgressValue, game.progressUnit)
        return WalkChallengeSportRecord(
            recordId = recordId,
            sportsType = sportsType,
            stepCount = stepCount,
            distance = distance,
            durationSeconds = durationSeconds,
            calories = calories,
            averageSpeed = averageSpeed,
            startTime = startTime,
            finishTime = finishTime,
            geoPoints = buildWalkChallengeGeoPoints(distance),
            goalValue = goalValue
        )
    }

    private fun buildWalkChallengeFinishRecord(record: WalkChallengeSportRecord): JSONObject {
        return JSONObject().apply {
            put("averageSpeed", record.averageSpeed)
            put("calories", record.calories)
            put("distance", record.distance)
            put("duration", record.durationSeconds)
            put("extraInfo", JSONObject().apply {
                put("goalType", "DISTANCE")
                put("value", record.goalValue)
            })
            put("finishTime", record.finishTime)
            put("geoPoints", record.geoPoints)
            put("maxSpeed", 0)
            put("minSpeed", 0)
            put("recordId", record.recordId)
            put("sportStatus", "FINISH")
            put("sportsType", record.sportsType)
            put("startTime", record.startTime)
        }
    }

    private fun formatWalkChallengeGoalValue(totalProgressValue: Double, progressUnit: String): String {
        if (totalProgressValue <= 0.0) {
            return ""
        }
        return when (progressUnit.uppercase(Locale.ROOT)) {
            "M", "METER", "METERS" -> formatWalkChallengeNumber(totalProgressValue / 1000.0)
            else -> formatWalkChallengeNumber(totalProgressValue)
        }
    }

    private fun buildWalkChallengeGeoPoints(distance: Double): String {
        val dayOffset = (System.currentTimeMillis() / 86_400_000L % 1000).toDouble()
        val baseLat = 30.274150 + dayOffset * 0.000001
        val baseLng = 120.155150 + dayOffset * 0.000001
        val segmentCount = (distance / 100.0).toInt().coerceIn(2, 16)
        val lngDelta = distance / (111_320.0 * 0.86)
        val points = mutableListOf<Pair<Double, Double>>()
        for (i in 0..segmentCount) {
            val ratio = i.toDouble() / segmentCount
            val lat = baseLat + if (i % 2 == 0) 0.0 else 0.00002
            val lng = baseLng + lngDelta * ratio
            points.add(lat to lng)
        }
        return encodePolyline(points)
    }

    private fun encodePolyline(points: List<Pair<Double, Double>>): String {
        val result = StringBuilder()
        var lastLat = 0
        var lastLng = 0
        for ((lat, lng) in points) {
            val latValue = Math.round(lat * 100000).toInt()
            val lngValue = Math.round(lng * 100000).toInt()
            appendEncodedPolylineValue(result, latValue - lastLat)
            appendEncodedPolylineValue(result, lngValue - lastLng)
            lastLat = latValue
            lastLng = lngValue
        }
        return result.toString()
    }

    private fun appendEncodedPolylineValue(builder: StringBuilder, diff: Int) {
        var value = diff shl 1
        if (diff < 0) {
            value = value.inv()
        }
        while (value >= 0x20) {
            builder.append(((0x20 or (value and 0x1f)) + 63).toChar())
            value = value shr 5
        }
        builder.append((value + 63).toChar())
    }

    private fun findAvailableWalkChallengeGame(): WalkChallengeGame? {
        val bizTypes = listOf("RECOMMEND_GAME", "NEW_ONLINE_GAME", "STEP_GAME", "ONLINE_GAME")
        for (bizType in bizTypes) {
            val jo = JSONObject(
                AntSportsRpcCall.onlineGameSportsListQuery(
                    bizType = bizType,
                    notInWufu = bizType == "RECOMMEND_GAME"
                )
            )
            if (!isSportsRpcSuccess(jo)) {
                val errorCode = extractSportsRpcErrorCode(jo)
                val errorMsg = extractSportsRpcErrorMessage(jo)
                Log.sports("走路挑战赛线上赛[列表查询失败][$bizType][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                )
                continue
            }
            val userDetailList = jo.optJSONArray("userDetailList") ?: continue
            for (i in 0 until userDetailList.length()) {
                val detail = userDetailList.optJSONObject(i) ?: continue
                val onlineGame = detail.optJSONObject("onlineGame") ?: continue
                if (!isWalkChallengeOnlineGame(onlineGame)) continue
                if (!isWalkChallengeJoinWindowOpen(onlineGame)) continue
                val gameId = onlineGame.optString("gameId", "")
                if (gameId.isBlank()) continue
                return WalkChallengeGame(
                    gameId = gameId,
                    name = onlineGame.optString("name", gameId)
                )
            }
        }
        return null
    }

    private fun selectWalkChallengeEvent(game: WalkChallengeGame): WalkChallengeEvent? {
        val jo = JSONObject(AntSportsRpcCall.onlineGameEventQuery(game.gameId))
        if (!isSportsRpcSuccess(jo)) {
            val errorCode = extractSportsRpcErrorCode(jo)
            val errorMsg = extractSportsRpcErrorMessage(jo)
            Log.sports("走路挑战赛线上赛[目标查询失败][${game.name}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$jo"
            )
            return null
        }
        val gameEventList = jo.optJSONArray("gameEventList") ?: return null
        val candidates = mutableListOf<WalkChallengeEvent>()
        for (i in 0 until gameEventList.length()) {
            val event = gameEventList.optJSONObject(i) ?: continue
            if (!event.optString("status", "").equals("ONLINE", ignoreCase = true)) continue
            val gameEventId = event.optString("gameEventId", "")
            val rightsPackageId = firstRightsPackageId(event.opt("rightPackageIdList"))
                .ifBlank { firstRightsPackageId(event.opt("rightsPackageId")) }
                .ifBlank { firstRightsPackageId(event.opt("rightPackageId")) }
            if (gameEventId.isBlank() || rightsPackageId.isBlank()) continue
            val candidate = WalkChallengeEvent(
                gameEventId = gameEventId,
                rightsPackageId = rightsPackageId,
                title = event.optString("title", gameEventId),
                progressValue = event.optDouble("progressValue", Double.MAX_VALUE),
                progressUnit = event.optString("progressUnit", ""),
                defaultSelected = event.optBoolean("defaultEventSelectFlag", false)
            )
            candidates.add(candidate)
        }
        if (candidates.isEmpty()) return null

        val budgetMeters = resolveWalkChallengeMonthlyBudgetMeters()
        if (budgetMeters != null) {
            val parsedCandidates = candidates.mapNotNull { candidate ->
                normalizeWalkChallengeTargetMeters(candidate)?.let { meters -> candidate to meters }
            }
            if (parsedCandidates.isNotEmpty()) {
                val selected = parsedCandidates
                    .filter { it.second <= budgetMeters }
                    .maxByOrNull { it.second }
                    ?: parsedCandidates.minByOrNull { it.second }
                    ?: return null
                val downgraded = selected.second > budgetMeters
                Log.sports(
                    "走路挑战赛线上赛目标选择" +
                        "[月预算=${formatWalkChallengeNumber(budgetMeters)}m]" +
                        "[选中=${selected.first.title}#${formatWalkChallengeTarget(selected.first.progressValue, selected.first.progressUnit)}]" +
                        if (downgraded) "[预算不足降级]" else ""
                )
                return selected.first
            }
            Log.sports("走路挑战赛线上赛目标单位无法解析，回退旧策略")
        }

        return selectWalkChallengeEventByLegacyStrategy(candidates)
    }

    private fun resolveWalkChallengeMonthlyBudgetMeters(): Double? {
        if (!::syncStepCount.isInitialized) return null
        val configuredDailyStep = (syncStepCount.value ?: 0).takeIf { it > 0 } ?: return null
        return configuredDailyStep * 30.0 * WALK_CHALLENGE_STEP_LENGTH_METER
    }

    private fun selectWalkChallengeEventByLegacyStrategy(candidates: List<WalkChallengeEvent>): WalkChallengeEvent? {
        candidates.firstOrNull { it.defaultSelected }?.let { return it }
        return candidates.minByOrNull { it.progressValue }
    }

    private fun normalizeWalkChallengeTargetMeters(event: WalkChallengeEvent): Double? {
        if (event.progressValue <= 0.0 || event.progressValue == Double.MAX_VALUE) {
            return null
        }
        val unit = event.progressUnit.uppercase(Locale.ROOT).trim()
        return when (unit) {
            "M", "METER", "METERS" -> event.progressValue
            "KM", "KILOMETER", "KILOMETERS" -> event.progressValue * 1000.0
            "STEP", "STEPS" -> event.progressValue * WALK_CHALLENGE_STEP_LENGTH_METER
            else -> parseWalkChallengeTargetMetersFromTitle(event.title)
                ?: inferWalkChallengeTargetMeters(event.progressValue, event.progressUnit, event.title)
        }
    }

    private fun parseWalkChallengeTargetMetersFromTitle(title: String): Double? {
        val match = Regex("""(\d+(?:\.\d+)?)\s*(公里|千米|km|KM|m|M|米|步)""").find(title) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "公里", "千米", "km" -> value * 1000.0
            "m", "米" -> value
            "步" -> value * WALK_CHALLENGE_STEP_LENGTH_METER
            else -> null
        }
    }

    private fun inferWalkChallengeTargetMeters(progressValue: Double, progressUnit: String, title: String): Double? {
        val text = (progressUnit + title).lowercase(Locale.ROOT)
        return when {
            text.contains("公里") || text.contains("千米") || text.contains("km") -> progressValue * 1000.0
            text.contains("米") || Regex("""(^|[^a-z])m([^a-z]|$)""").containsMatchIn(text) -> progressValue
            text.contains("步") || text.contains("step") -> progressValue * WALK_CHALLENGE_STEP_LENGTH_METER
            else -> null
        }
    }

    private fun isWalkChallengeOnlineGame(onlineGame: JSONObject): Boolean {
        if (!onlineGame.optString("bizType", "").equals("REGULAR_CHALLENGE", ignoreCase = true)) {
            return false
        }
        val sportsDataType = onlineGame.optString("sportsDataType", "").lowercase(Locale.ROOT)
        if (
            sportsDataType.contains("walk") ||
            sportsDataType.contains("step") ||
            sportsDataType.contains("run")
        ) {
            return true
        }

        val text = onlineGame.optString("name", "") +
            onlineGame.optString("gameDesc", "") +
            onlineGame.optString("content", "")
        return text.contains("走") ||
            text.contains("步") ||
            text.contains("跑") ||
            text.contains("挑战")
    }

    private fun isWalkChallengeJoinWindowOpen(onlineGame: JSONObject): Boolean {
        val now = System.currentTimeMillis()
        val start = onlineGame.optLong("userJoinStartTime", 0L)
        val end = onlineGame.optLong("userJoinEndTime", 0L)
        return (start <= 0L || now >= start) && (end <= 0L || now <= end)
    }

    private fun firstRightsPackageId(value: Any?): String {
        return when (value) {
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val item = value.optString(i, "").trim()
                    if (item.isNotEmpty()) return item
                }
                ""
            }
            is String -> value.split(',', ';').firstOrNull { it.trim().isNotEmpty() }?.trim().orEmpty()
            else -> value?.toString()?.trim().orEmpty()
        }
    }

    private fun isWalkChallengeAlreadyJoinedError(errorCode: String, errorMsg: String): Boolean {
        val text = "$errorCode $errorMsg"
        return text.contains("已报名") ||
            text.contains("已参加") ||
            text.contains("不可重复") ||
            text.contains("重复报名") ||
            text.contains("REPEAT", ignoreCase = true)
    }

    private fun formatWalkChallengeTarget(progressValue: Double, progressUnit: String): String {
        if (progressValue <= 0.0 || progressValue == Double.MAX_VALUE) {
            return "未知目标"
        }
        val unit = progressUnit.uppercase(Locale.ROOT)
        return when (unit) {
            "M", "METER", "METERS" -> {
                if (progressValue >= 1000.0) {
                    "${formatWalkChallengeNumber(progressValue / 1000)}km"
                } else {
                    "${formatWalkChallengeNumber(progressValue)}m"
                }
            }
            "KM", "KILOMETER", "KILOMETERS" -> "${formatWalkChallengeNumber(progressValue)}km"
            "STEP", "STEPS" -> "${formatWalkChallengeNumber(progressValue)}步"
            else -> "${formatWalkChallengeNumber(progressValue)}${progressUnit.ifBlank { "" }}"
        }
    }

    private fun formatWalkChallengeNumber(value: Double): String {
        val formatted = String.format(Locale.US, "%.2f", value)
        return formatted.trimEnd('0').trimEnd('.')
    }

    /**
     * @brief 文体中心奖励领取
     */
    internal fun userTaskRightsReceive() {
        try {
            val s = AntSportsRpcCall.userTaskGroupQuery("SPORTS_DAILY_GROUP")
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                jo = jo.getJSONObject("group")
                val userTaskList = jo.getJSONArray("userTaskList")
                for (i in 0 until userTaskList.length()) {
                    jo = userTaskList.getJSONObject(i)
                    if ("COMPLETED" != jo.getString("status")) continue
                    val userTaskId = jo.getString("userTaskId")
                    val taskInfo = jo.getJSONObject("taskInfo")
                    val taskId = taskInfo.getString("taskId")
                    val res = JSONObject(AntSportsRpcCall.userTaskRightsReceive(taskId, userTaskId))
                    if (ResChecker.checkRes(TAG, res)) {
                        val taskName = taskInfo.optString("taskName", taskId)
                        val rightsRuleList = taskInfo.getJSONArray("rightsRuleList")
                        val award = StringBuilder()
                        for (j in 0 until rightsRuleList.length()) {
                            val r = rightsRuleList.getJSONObject(j)
                            award.append(r.getString("rightsName"))
                                .append("*")
                                .append(r.getInt("baseAwardCount"))
                        }
                        Log.sports("领取奖励🎖️[$taskName]#$award")
                    } else {
                        Log.sports("文体中心领取奖励")
                        Log.sports(res.toString())
                    }
                }
            } else {
                Log.sports("文体中心领取奖励")
                Log.sports(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "userTaskRightsReceive err:", t)
        }
    }

    /**
     * @brief 文体中心路径特性查询 + 行走任务/加入路径
     */
    internal fun pathFeatureQuery() {
        try {
            val s = AntSportsRpcCall.pathFeatureQuery()
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val path = jo.getJSONObject("path")
                val pathId = path.getString("pathId")
                val title = path.getString("title")
                val minGoStepCount = path.getInt("minGoStepCount")
                if (jo.has("userPath")) {
                    val userPath = jo.getJSONObject("userPath")
                    val userPathRecordStatus = userPath.getString("userPathRecordStatus")
                    if ("COMPLETED" == userPathRecordStatus) {
                        pathMapHomepage(pathId)
                        pathMapJoin(title, pathId)
                    } else if ("GOING" == userPathRecordStatus) {
                        pathMapHomepage(pathId)
                        val countDate = TimeUtil.getFormatDate()
                        jo = JSONObject(AntSportsRpcCall.stepQuery(countDate, pathId))
                        if (ResChecker.checkRes(TAG, jo)) {
                            val canGoStepCount = jo.getInt("canGoStepCount")
                            if (canGoStepCount >= minGoStepCount) {
                                val userPathRecordId = userPath.getString("userPathRecordId")
                                tiyubizGo(countDate, title, canGoStepCount, pathId, userPathRecordId)
                            }
                        }
                    }
                } else {
                    pathMapJoin(title, pathId)
                }
            } else {
                Log.sports(jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "pathFeatureQuery err:", t)
        }
    }

    /**
     * @brief 文体中心地图首页 & 奖励领取
     */
    private fun pathMapHomepage(pathId: String) {
        try {
            val s = AntSportsRpcCall.pathMapHomepage(pathId)
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                if (!jo.has("userPathGoRewardList")) return
                val userPathGoRewardList = jo.getJSONArray("userPathGoRewardList")
                for (i in 0 until userPathGoRewardList.length()) {
                    jo = userPathGoRewardList.getJSONObject(i)
                    if ("UNRECEIVED" != jo.getString("status")) continue
                    val userPathRewardId = jo.getString("userPathRewardId")
                    val res = JSONObject(AntSportsRpcCall.rewardReceive(pathId, userPathRewardId))
                    if (ResChecker.checkRes(TAG, res)) {
                        val detail = res.getJSONObject("userPathRewardDetail")
                        val rightsRuleList = detail.getJSONArray("userPathRewardRightsList")
                        val award = StringBuilder()
                        for (j in 0 until rightsRuleList.length()) {
                            val right = rightsRuleList.getJSONObject(j).getJSONObject("rightsContent")
                            award.append(right.getString("name"))
                                .append("*")
                                .append(right.getInt("count"))
                        }
                        Log.sports("文体宝箱🎁[$award]")
                    } else {
                        Log.sports("文体中心开宝箱")
                        Log.sports(res.toString())
                    }
                }
            } else {
                Log.sports("文体中心开宝箱")
                Log.sports(s)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "pathMapHomepage err:", t)
        }
    }

    /**
     * @brief 文体中心加入路线
     */
    private fun pathMapJoin(title: String, pathId: String) {
        try {
            val jo = JSONObject(AntSportsRpcCall.pathMapJoin(pathId))
            if (isSportsRpcSuccess(jo)) {
                Log.sports("加入线路🚶🏻‍♂️[$title]")
                pathFeatureQuery()
            } else if (isSportsRouteBusinessTerminal(extractSportsRpcErrorCode(jo), extractSportsRpcErrorMessage(jo))) {
                val errorCode = extractSportsRpcErrorCode(jo)
                val errorMsg = extractSportsRpcErrorMessage(jo)
                Log.sports("文体中心路线[业务终态：已参加][$title][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                )
            } else {
                Log.error(TAG, "文体中心路线[加入失败][$title] raw=$jo")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "pathMapJoin err:", t)
        }
    }

    /**
     * @brief 文体中心行走逻辑
     */
    private fun tiyubizGo(
        countDate: String,
        title: String,
        goStepCount: Int,
        pathId: String,
        userPathRecordId: String
    ) {
        try {
            val s = AntSportsRpcCall.tiyubizGo(countDate, goStepCount, pathId, userPathRecordId)
            var jo = JSONObject(s)
            if (isSportsRpcSuccess(jo)) {
                jo = jo.getJSONObject("userPath")
                Log.sports(
                    "行走线路🚶🏻‍♂️[$title]#前进了" +
                        jo.getInt("userPathRecordForwardStepCount") + "步"
                )
                pathMapHomepage(pathId)
                val completed = "COMPLETED" == jo.getString("userPathRecordStatus")
                if (completed) {
                    Log.sports("完成线路🚶🏻‍♂️[$title]")
                    pathFeatureQuery()
                }
            } else if (isSportsRouteBusinessTerminal(extractSportsRpcErrorCode(jo), extractSportsRpcErrorMessage(jo))) {
                val errorCode = extractSportsRpcErrorCode(jo)
                val errorMsg = extractSportsRpcErrorMessage(jo)
                Log.sports("文体中心路线[业务终态：已完成][$title][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                )
                pathMapHomepage(pathId)
            } else {
                Log.error(TAG, "文体中心路线[前进失败][$title] raw=$s")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "tiyubizGo err:", t)
        }
    }

    // ---------------------------------------------------------------------
    // 抢好友大战
    // ---------------------------------------------------------------------

    /**
     * @brief 抢好友主页查询 + 训练好友收益泡泡收集
     */
    internal fun queryClubHome() {
        try {
            val maxCount = getTrainFriendZeroCoinLimit()
            if (maxCount != null && hasReachedTrainFriendZeroCoinLimit()) {
                val today = TimeUtil.getDateStr2()
                DataStore.put(TRAIN_FRIEND_ZERO_COIN_DATE, today)
                Log.sports("✅ 训练好友获得0金币已达${maxCount}次上限，今日不再执行")
                return
            }
            val clubHomeData = JSONObject(AntSportsRpcCall.queryClubHome())
            processBubbleList(clubHomeData.optJSONObject("mainRoom"))
            val roomList = clubHomeData.optJSONArray("roomList")
            if (roomList != null) {
                for (i in 0 until roomList.length()) {
                    val room = roomList.optJSONObject(i)
                    processBubbleList(room)
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryClubHome err:", t)
        }
    }

    /**
     * @brief 训练好友收益泡泡收集逻辑
     */
    private fun processBubbleList(obj: JSONObject?) {
        if (obj == null || !obj.has("bubbleList")) return
        try {
            val bubbleList = obj.optJSONArray("bubbleList") ?: return
            for (j in 0 until bubbleList.length()) {
                val bubble = bubbleList.optJSONObject(j) ?: continue
                val recordId = resolveTrainFriendBubbleRecordId(bubble)
                if (recordId.isBlank()) {
                    Log.error(TAG, "训练好友收益泡泡缺少可领取ID raw=$bubble")
                    continue
                }
                if (recordId in skippedTrainBubbleRecordIds) {
                    continue
                }

                val responseStr = AntSportsRpcCall.pickBubbleTaskEnergy(recordId, false)
                val responseJson = JSONObject(responseStr)

                if (!isSportsRpcSuccess(responseJson)) {
                    val errorCode = extractSportsRpcErrorCode(responseJson)
                    val errorMsg = extractSportsRpcErrorMessage(responseJson)
                    if (isTrainFriendBubbleAlreadyHandled(errorCode, errorMsg)) {
                        skippedTrainBubbleRecordIds.add(recordId)
                        Log.sports("训练好友收益泡泡已处理[recordId=$recordId][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                        )
                        continue
                    }
                    if (isTrainFriendBubbleInvalid(errorCode, errorMsg)) {
                        skippedTrainBubbleRecordIds.add(recordId)
                        Log.sports("训练好友收益泡泡已失效[recordId=$recordId][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                        )
                        continue
                    }
                    if (errorCode == "CAMP_TRIGGER_ERROR") {
                        Log.error(
                            TAG,
                            "收取训练好友失败-业务RPC受限[recordId=$recordId][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$responseStr"
                        )
                        continue
                    }
                    if (!isSportsRpcRetryable(responseJson)) {
                        Log.error(
                            TAG,
                            "收取训练好友失败-非重试RPC[recordId=$recordId][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$responseStr"
                        )
                        continue
                    }
                    Log.error(
                        TAG,
                        "收取训练好友失败[recordId=$recordId][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$responseStr"
                    )
                    continue
                }

                var amount = 0
                val data = responseJson.optJSONObject("data")
                if (data != null) {
                    val changeAmountStr = data.optString("changeAmount", "0")
                    amount = changeAmountStr.toIntOrNull() ?: 0
                }

                Log.sports("训练好友💰️ [获得:$amount 金币]")

                if (amount <= 0) {
                    zeroTrainCoinCount++
                    val maxCount = getTrainFriendZeroCoinLimit()
                    if (maxCount != null && hasReachedTrainFriendZeroCoinLimit()) {
                        val today = TimeUtil.getDateStr2()
                        DataStore.put(TRAIN_FRIEND_ZERO_COIN_DATE, today)
                        Log.sports("✅ 连续获得0金币已达${maxCount}次，今日停止执行")
                        return
                    } else if (maxCount != null) {
                        Log.sports("训练好友0金币计数: $zeroTrainCoinCount/$maxCount")
                    }
                }

                GlobalThreadPools.sleepCompat(1000)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "processBubbleList 异常:", t)
        }
    }

    private fun resolveTrainFriendBubbleRecordId(bubble: JSONObject): String {
        return sequenceOf(
            bubble.optString("medEnergyBallInfoRecordId", ""),
            bubble.optString("recordId", ""),
            bubble.optString("assetId", ""),
            bubble.optString("bubbleId", "")
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun isTrainFriendBubbleAlreadyHandled(errorCode: String, errorMsg: String): Boolean {
        val errorText = "$errorCode $errorMsg"
        return errorCode == "RECEIVE_REWARD_REPEATED" ||
            errorText.contains("已领取") ||
            errorText.contains("已经领取") ||
            errorText.contains("重复领取")
    }

    private fun isTrainFriendBubbleInvalid(errorCode: String, errorMsg: String): Boolean {
        val errorText = "$errorCode $errorMsg"
        return errorCode == "ENERGY_BALL_STATUS_IS_INVALID_ERROR" ||
            errorText.contains("能量球数据状态校验错误") ||
            errorText.contains("已失效") ||
            errorText.contains("已过期")
    }

    /**
     * @brief 训练好友：选取可训练好友并执行一次训练
     */
    internal fun queryTrainItem() {
        if (trainFriend.value != true) {
            return
        }
        val maxCount = getTrainFriendZeroCoinLimit()
        if (maxCount != null && hasReachedTrainFriendZeroCoinLimit()) {
            Log.sports("训练好友🥋0金币次数已达上限，跳过继续训练")
            return
        }
        try {
            var trainedAny = false
            val skippedOriginBossIds = mutableSetOf<String>()
            var memberChangedRetryCount = 0
            while (true) {
                if (maxCount != null && hasReachedTrainFriendZeroCoinLimit()) {
                    Log.sports("训练好友🥋0金币次数已达上限，停止继续训练")
                    return
                }

                val clubHomeData = queryClubHomeForTraining() ?: return
                processClubRoomBubbleRewards(clubHomeData)
                if (maxCount != null && hasReachedTrainFriendZeroCoinLimit()) {
                    Log.sports("训练好友🥋0金币次数已达上限，停止继续训练")
                    return
                }

                val trainTarget = findNextTrainTarget(clubHomeData, skippedOriginBossIds)
                if (trainTarget == null) {
                    if (!trainedAny) {
                        Log.sports("训练好友🥋当前没有可训练好友")
                    }
                    return
                }

                val trainItemSelection = queryBestTrainItemSelection()
                if (trainItemSelection == null) {
                    Log.sports("训练好友🥋无可用训练道具或训练道具查询失败，停止本轮训练")
                    return
                }
                val trainMemberJson = JSONObject(
                    AntSportsRpcCall.trainMember(
                        trainItemSelection.bizId,
                        trainItemSelection.itemType,
                        trainTarget.memberId,
                        trainTarget.originBossId
                    )
                )
                if (!isSportsRpcSuccess(trainMemberJson)) {
                    val errorCode = extractSportsRpcErrorCode(trainMemberJson)
                    val errorMsg = extractSportsRpcErrorMessage(trainMemberJson)
                    if (errorCode == "CLUB_MEMBER_CHANGED") {
                        skippedOriginBossIds.add(trainTarget.originBossId)
                        memberChangedRetryCount++
                        Log.sports("训练好友[目标已变化，跳过本轮目标并重试][friend=${trainTarget.userName}][retry=$memberChangedRetryCount/$MAX_TRAIN_MEMBER_CHANGED_RETRIES]"
                        )
                        if (memberChangedRetryCount >= MAX_TRAIN_MEMBER_CHANGED_RETRIES) {
                            Log.sports("训练好友[CLUB_MEMBER_CHANGED重试已达上限，结束本轮训练]")
                            return
                        }
                        GlobalThreadPools.sleepCompat(500)
                        continue
                    }
                    if (shouldSkipTrainTargetAfterFailure(trainMemberJson, errorCode, errorMsg)) {
                        skippedOriginBossIds.add(trainTarget.originBossId)
                        Log.sports(
                            "训练好友[当前目标不可训练，跳过继续寻找][friend=${trainTarget.userName}]" +
                                "[code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                        )
                        GlobalThreadPools.sleepCompat(500)
                        continue
                    }
                    Log.error(
                        TAG,
                        "训练好友[trainMember失败][friend=${trainTarget.userName}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$trainMemberJson"
                    )
                    return
                }

                trainedAny = true
                Log.sports("训练好友🥋[训练:${trainTarget.userName} ${trainItemSelection.itemName}]")
                GlobalThreadPools.sleepCompat(1000)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryTrainItem err:", t)
        }
    }

    /**
     * @brief 抢好友大战：抢购好友逻辑
     */
    internal fun buyMember() {
        try {
            var clubHomeJson = queryClubHomeForBattle() ?: return
            if (!isClubHomeEnabled(clubHomeJson)) return
            clubHomeJson = unlockNextRoomsIfPossible(clubHomeJson)

            var boughtAny = false
            roomLoop@ while (true) {
                if (!isClubHomeEnabled(clubHomeJson)) return

                val coinBalance = clubHomeJson.optJSONObject("assetsInfo")?.optInt("energyBalance", 0) ?: 0
                if (coinBalance <= 0) {
                    Log.sports("抢好友大战🧑‍🤝‍🧑当前能量为0，跳过抢好友")
                    return
                }

                val room = findEmptyClubRoom(clubHomeJson)
                if (room == null) {
                    Log.sports(if (boughtAny) "抢好友大战🧑‍🤝‍🧑空场地已处理完" else "抢好友大战🧑‍🤝‍🧑暂无空场地")
                    return
                }
                val roomId = room.optString("roomId", "")
                if (roomId.isBlank()) {
                    Log.error(TAG, "抢好友大战🧑‍🤝‍🧑空场地缺少roomId raw=$room")
                    return
                }

                val memberPriceJson = queryMemberPriceRankingForBattle(coinBalance) ?: return
                val candidates = collectClubMemberCandidates(memberPriceJson)
                if (candidates.isEmpty()) {
                    Log.sports("抢好友大战🧑‍🤝‍🧑暂无可抢好友")
                    return
                }

                var matchedTarget = false
                for (candidate in candidates) {
                    if (!shouldRobClubMember(candidate.originBossId)) {
                        continue
                    }
                    matchedTarget = true
                    if (candidate.price > coinBalance) {
                        continue
                    }
                    if (FriendGuard.shouldSkipFriend(candidate.originBossId, TAG, "抢好友")) {
                        continue
                    }

                    val memberObj = queryClubMemberForBattle(candidate) ?: continue
                    val currentBossId = memberObj.optString("currentBossId", candidate.currentBossId)
                    val memberId = memberObj.optString("memberId", candidate.memberId)
                    val priceInfo = memberObj.optJSONObject("priceInfo")
                    if (currentBossId.isBlank() || memberId.isBlank() || priceInfo == null) {
                        Log.error(TAG, "抢好友大战🧑‍🤝‍🧑成员详情缺少必要字段[originBossId=${candidate.originBossId}] raw=$memberObj")
                        continue
                    }
                    val realPrice = priceInfo.optInt("price", candidate.price)
                    if (realPrice > coinBalance) {
                        continue
                    }

                    val buyMemberResponse = JSONObject(
                        AntSportsRpcCall.buyMember(
                            currentBossId,
                            memberId,
                            candidate.originBossId,
                            priceInfo.toString(),
                            roomId
                        )
                    )
                    GlobalThreadPools.sleepCompat(500)

                    if (isSportsRpcSuccess(buyMemberResponse)) {
                        boughtAny = true
                        val userName = UserMap.getMaskName(candidate.originBossId) ?: candidate.originBossId
                        Log.sports("抢购好友🥋[成功:将 $userName 抢回来，消耗${realPrice}能量]")
                        clubHomeJson = queryClubHomeForBattle() ?: return
                        if (trainFriend.value == true) {
                            queryTrainItem()
                            clubHomeJson = queryClubHomeForBattle() ?: return
                        }
                        clubHomeJson = unlockNextRoomsIfPossible(clubHomeJson)
                        continue@roomLoop
                    }

                    val errorCode = extractSportsRpcErrorCode(buyMemberResponse)
                    val errorMsg = extractSportsRpcErrorMessage(buyMemberResponse)
                    when {
                        errorCode == "CLUB_AMOUNT_NOT_ENOUGH" -> {
                            Log.sports("抢好友大战🧑‍🤝‍🧑能量不足，停止抢购[code=$errorCode][msg=$errorMsg]")
                            return
                        }

                        isClubRetryableConflict(errorCode, errorMsg) -> {
                            Log.sports("抢好友大战🧑‍🤝‍🧑成员或房间状态变化，刷新主页后重试[code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                            )
                            clubHomeJson = queryClubHomeForBattle() ?: return
                            continue@roomLoop
                        }

                        else -> {
                            Log.error(
                                TAG,
                                "抢好友大战🧑‍🤝‍🧑抢购失败[originBossId=${candidate.originBossId}][roomId=$roomId][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$buyMemberResponse"
                            )
                            return
                        }
                    }
                }

                Log.sports(if (matchedTarget) "抢好友大战🧑‍🤝‍🧑可抢目标能量不足或不可交易" else "抢好友大战🧑‍🤝‍🧑未匹配到配置中的可抢好友"
                )
                return
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "buyMember err:", t)
        }
    }

    private fun queryClubHomeForBattle(): JSONObject? {
        val clubHomeJson = JSONObject(AntSportsRpcCall.queryClubHome())
        GlobalThreadPools.sleepCompat(500)
        if (!isSportsRpcSuccess(clubHomeJson)) {
            Log.error(
                TAG,
                "抢好友大战🧑‍🤝‍🧑queryClubHome失败[code=${extractSportsRpcErrorCode(clubHomeJson).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(clubHomeJson)}] raw=$clubHomeJson"
            )
            return null
        }
        return clubHomeJson
    }

    private fun isClubHomeEnabled(clubHomeJson: JSONObject): Boolean {
        if (clubHomeJson.optString("clubAuth", "") == "ENABLE") {
            return true
        }
        Log.sports("抢好友大战🧑‍🤝‍🧑未授权开启[clubAuth=${clubHomeJson.optString("clubAuth", "UNKNOWN")}]")
        return false
    }

    private fun unlockNextRoomsIfPossible(clubHomeJson: JSONObject): JSONObject {
        if (!::battleAutoUnlockRoom.isInitialized || battleAutoUnlockRoom.value != true) {
            return clubHomeJson
        }

        var latestHome = clubHomeJson
        var unlockCount = 0
        while (unlockCount < 10) {
            val unlockPrice = latestHome.optInt("nextRoomUnlockPrice", 0)
            if (unlockPrice <= 0) {
                return latestHome
            }
            val energyBalance = latestHome.optJSONObject("assetsInfo")?.optInt("energyBalance", 0) ?: 0
            if (energyBalance < unlockPrice) {
                Log.sports("抢好友大战🧑‍🤝‍🧑解锁新场地能量不足[balance=$energyBalance, price=$unlockPrice]")
                return latestHome
            }

            val unlockResp = JSONObject(AntSportsRpcCall.unlockNextRoom(energyBalance))
            GlobalThreadPools.sleepCompat(500)
            if (isSportsRpcSuccess(unlockResp)) {
                unlockCount++
                Log.sports("抢好友大战🧑‍🤝‍🧑解锁新场地成功[price=$unlockPrice,balanceBefore=$energyBalance,nextPrice=${unlockResp.optInt("nextRoomUnlockPrice", 0)}]"
                )
                latestHome = queryClubHomeForBattle() ?: return latestHome
                continue
            }

            val errorCode = extractSportsRpcErrorCode(unlockResp)
            val errorMsg = extractSportsRpcErrorMessage(unlockResp)
            if (errorCode == "CLUB_AMOUNT_NOT_ENOUGH") {
                Log.sports("抢好友大战🧑‍🤝‍🧑解锁新场地能量不足[code=$errorCode][msg=$errorMsg]")
                return latestHome
            }
            if (isClubRetryableConflict(errorCode, errorMsg)) {
                Log.sports("抢好友大战🧑‍🤝‍🧑解锁场地遇到状态变化，刷新主页[code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                )
                return queryClubHomeForBattle() ?: latestHome
            }

            Log.error(
                TAG,
                "抢好友大战🧑‍🤝‍🧑解锁新场地失败[price=$unlockPrice,balance=$energyBalance][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$unlockResp"
            )
            return latestHome
        }
        Log.error(TAG, "抢好友大战🧑‍🤝‍🧑解锁新场地达到保护上限，停止解锁")
        return latestHome
    }

    private fun findEmptyClubRoom(clubHomeJson: JSONObject): JSONObject? {
        val roomList = clubHomeJson.optJSONArray("roomList") ?: return null
        for (i in 0 until roomList.length()) {
            val room = roomList.optJSONObject(i) ?: continue
            val memberList = room.optJSONArray("memberList")
            if (memberList == null || memberList.length() == 0) {
                return room
            }
        }
        return null
    }

    private fun queryMemberPriceRankingForBattle(coinBalance: Int): JSONObject? {
        val memberPriceJson = JSONObject(AntSportsRpcCall.queryMemberPriceRanking(coinBalance))
        GlobalThreadPools.sleepCompat(500)
        if (!isSportsRpcSuccess(memberPriceJson)) {
            Log.error(
                TAG,
                "抢好友大战🧑‍🤝‍🧑queryMemberPriceRanking失败[coinBalance=$coinBalance][code=${extractSportsRpcErrorCode(memberPriceJson).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(memberPriceJson)}] raw=$memberPriceJson"
            )
            return null
        }
        return memberPriceJson
    }

    private fun collectClubMemberCandidates(memberPriceJson: JSONObject): List<ClubMemberCandidate> {
        val candidates = mutableListOf<ClubMemberCandidate>()

        val memberDetailList = memberPriceJson.optJSONArray("memberDetailList")
        if (memberDetailList != null) {
            for (i in 0 until memberDetailList.length()) {
                val memberModel = memberDetailList.optJSONObject(i)?.optJSONObject("memberModel") ?: continue
                val memberId = memberModel.optString("memberId", "")
                val originBossId = memberModel.optString("originBossId", "")
                if (memberId.isBlank() || originBossId.isBlank()) continue
                candidates.add(
                    ClubMemberCandidate(
                        currentBossId = memberModel.optString("currentBossId", ""),
                        memberId = memberId,
                        originBossId = originBossId,
                        price = memberModel.optJSONObject("priceInfo")?.optInt("price", Int.MAX_VALUE)
                            ?: memberModel.optInt("price", Int.MAX_VALUE)
                    )
                )
            }
        }

        val rankData = memberPriceJson.optJSONObject("rank")?.optJSONArray("data")
        if (rankData != null) {
            for (i in 0 until rankData.length()) {
                val item = rankData.optJSONObject(i) ?: continue
                val memberId = item.optString("memberId", "")
                val originBossId = item.optString("originBossId", "")
                if (memberId.isBlank() || originBossId.isBlank()) continue
                candidates.add(
                    ClubMemberCandidate(
                        currentBossId = item.optString("currentBossId", ""),
                        memberId = memberId,
                        originBossId = originBossId,
                        price = item.optInt("price", Int.MAX_VALUE)
                    )
                )
            }
        }

        return candidates.distinctBy { "${it.memberId}#${it.originBossId}" }
    }

    private fun shouldRobClubMember(originBossId: String): Boolean {
        var isTarget = originBossIdList.contains(originBossId)
        if (battleForFriendType.value == BattleForFriendType.DONT_ROB) {
            isTarget = !isTarget
        }
        return isTarget
    }

    private fun queryClubMemberForBattle(candidate: ClubMemberCandidate): JSONObject? {
        val clubMemberDetailJson = JSONObject(
            AntSportsRpcCall.queryClubMember(candidate.memberId, candidate.originBossId)
        )
        GlobalThreadPools.sleepCompat(500)
        if (!isSportsRpcSuccess(clubMemberDetailJson) || !clubMemberDetailJson.has("member")) {
            Log.error(
                TAG,
                "抢好友大战🧑‍🤝‍🧑queryClubMember失败[memberId=${candidate.memberId}][originBossId=${candidate.originBossId}][code=${extractSportsRpcErrorCode(clubMemberDetailJson).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(clubMemberDetailJson)}] raw=$clubMemberDetailJson"
            )
            return null
        }
        FriendCapabilityRecorder.record(
            candidate.originBossId,
            "SPORTS",
            FriendCapabilityState.OPEN,
            "AntSports.queryClubMember"
        )
        return clubMemberDetailJson.optJSONObject("member")
    }

    private fun isClubRetryableConflict(errorCode: String, errorMsg: String): Boolean {
        val text = "$errorCode $errorMsg"
        return errorCode == "CLUB_MEMBER_TRADE_PROTECT" ||
            errorCode == "CLUB_MEMBER_CHANGED" ||
            text.contains("独处") ||
            text.contains("保护") ||
            text.contains("变化") ||
            text.contains("已被抢")
    }

    // ---------------------------------------------------------------------
    // 健康岛任务处理器（内部类）
    // ---------------------------------------------------------------------

    /**
     * @brief 健康岛任务处理器
     *
     * <p>整体流程：</p>
     * <ol>
     *   <li>签到（querySign + takeSign）</li>
     *   <li>任务大厅循环处理（queryTaskCenter + taskSend / adtask.finish）</li>
     *   <li>健康岛浏览任务（queryTaskInfo + energyReceive）</li>
     *   <li>捡泡泡（queryBubbleTask + pickBubbleTaskEnergy）</li>
     *   <li>走路建造 / 旧版行走（queryBaseinfo + queryMapInfo/Build/WalkGrid 等）</li>
     * </ol>
     */
    @Suppress("GrazieInspection")
    inner class NeverlandTaskHandler {

        private val TAG = "Neverland"

        /** @brief 最大失败次数（优先使用 BaseModel 配置，默认 5 次） */
        private val MAX_ERROR_COUNT: Int = run {
            val v = BaseModel.setMaxErrorCount.value ?: 0
            if (v > 0) v else 5
        }

        /** @brief 任务循环间隔（毫秒） */
        private val TASK_LOOP_DELAY: Long = 1000
        private val pickedNeverlandBubbleRecordIds = mutableSetOf<String>()
        private val handledNeverlandBubbleEncryptValues = mutableSetOf<String>()
        private val handledNeverlandBubbleTaskIds = mutableSetOf<String>()
        private var activeNeverlandSource: String = NEVERLAND_SOURCE_SPORT_HOME

        private fun neverlandRewardUnavailableFlag(mapId: String): String {
            return StatusFlags.FLAG_NEVERLAND_REWARD_UNAVAILABLE_PREFIX + mapId
        }

        private fun isNeverlandRewardUnavailableToday(mapId: String): Boolean {
            return mapId.isNotBlank() && Status.hasFlagToday(neverlandRewardUnavailableFlag(mapId))
        }

        private fun markNeverlandRewardUnavailableToday(mapId: String) {
            if (mapId.isNotBlank()) {
                Status.setFlagToday(neverlandRewardUnavailableFlag(mapId))
            }
        }

        private fun candidateNeverlandSources(): List<String> {
            return (listOf(activeNeverlandSource) + NEVERLAND_SOURCE_CANDIDATES).distinct()
        }

        private fun rememberNeverlandSource(source: String) {
            activeNeverlandSource = source
        }

        private fun extractNeverlandViewSeconds(task: JSONObject, defaultSeconds: Int = 15): Int {
            val direct = task.optInt("viewSec", 0)
            val floating = task.optJSONObject("floatingBallVO")?.optInt("timeSeconds", 0) ?: 0
            val nestedFloating = task.optJSONObject("taskCenterTaskVO")
                ?.optJSONObject("floatingBallVO")
                ?.optInt("timeSeconds", 0) ?: 0
            return max(max(direct, floating), nestedFloating).takeIf { it > 0 } ?: defaultSeconds
        }

        /**
         * @brief 健康岛任务入口
         */
        fun runNeverland() {
            try {
                Log.sports("开始执行健康岛任务")
                if (neverlandTask.value == true) {
                    // 1. 签到
                    neverlandDoSign()
                    // 2. 任务大厅循环处理
                    loopHandleTaskCenter()
                    // 3. 浏览任务
                    handleHealthIslandTask()
                    // 4. 捡泡泡
                    neverlandPickAllBubble()
                }

                if (neverlandGrid.value == true) {
                    // 5. 自动走路建造
                    neverlandAutoTask()
                }

                Log.sports("健康岛任务结束")
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "runNeverland err:", t)
            }
        }

        // ---------------------------------------------------------------
        // 1. 健康岛签到
        // ---------------------------------------------------------------

        /**
         * @brief 健康岛签到流程
         */
        private fun neverlandDoSign() {
            try {
                if (Status.hasFlagToday(StatusFlags.FLAG_NEVERLAND_SIGN_DONE)) return

                Log.sports("健康岛 · 检查签到状态")
                val jo = JSONObject(AntSportsRpcCall.NeverlandRpcCall.querySign(3, "jkdsportcard"))

                if (!ResChecker.checkRes(TAG, "查询签到失败:", jo) ||
                    !ResChecker.checkRes(TAG, jo) ||
                    jo.optJSONObject("data") == null
                ) {
                    val errorCode = jo.optString("errorCode", "")
                    if ("ALREADY_SIGN_IN" == errorCode ||
                        "已签到" == jo.optString("errorMsg", "")
                    ) {
                        Status.setFlagToday(StatusFlags.FLAG_NEVERLAND_SIGN_DONE)
                    }
                    return
                }

                val data = jo.getJSONObject("data")
                val signInfo = data.optJSONObject("continuousSignInfo")
                if (signInfo != null && signInfo.optBoolean("signedToday", false)) {
                    Log.sports("今日已签到 ✔ 连续：${signInfo.optInt("continuitySignedDayCount")} 天"
                    )
                    Status.setFlagToday(StatusFlags.FLAG_NEVERLAND_SIGN_DONE)
                    return
                }

                Log.sports("健康岛 · 正在签到…")
                val signRes = JSONObject(AntSportsRpcCall.NeverlandRpcCall.takeSign(3, "jkdsportcard"))

                if (!ResChecker.checkRes(TAG, "签到失败:", signRes) ||
                    !ResChecker.checkRes(TAG, signRes) ||
                    signRes.optJSONObject("data") == null
                ) {
                    val errorCode = signRes.optString("errorCode", "")
                    val errorMsg = signRes.optString("errorMsg", "")
                    if ("ALREADY_SIGN_IN" == errorCode || "已签到" == errorMsg) {
                        Status.setFlagToday(StatusFlags.FLAG_NEVERLAND_SIGN_DONE)
                    }
                    Log.error(TAG, "takeSign raw=$signRes")
                    return
                }

                val signData = signRes.getJSONObject("data")
                val reward = signData.optJSONObject("continuousDoSignInVO")
                val rewardAmount = reward?.optInt("rewardAmount", 0) ?: 0
                val rewardType = reward?.optString("rewardType", "") ?: ""
                val signInfoAfter = signData.optJSONObject("continuousSignInfo")
                val newContinuity = signInfoAfter?.optInt("continuitySignedDayCount", -1) ?: -1

                Log.sports(
                    "健康岛签到成功 🎉 +" + rewardAmount + rewardType +
                        " 连续：" + newContinuity + " 天"
                )
                Status.setFlagToday(StatusFlags.FLAG_NEVERLAND_SIGN_DONE)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "neverlandDoSign err:$t", t)
            }
        }

        // ---------------------------------------------------------------
        // 2. 任务大厅循环处理
        // ---------------------------------------------------------------

        /**
         * @brief 循环处理健康岛任务大厅中的 PROMOKERNEL_TASK & LIGHT_TASK
         */
        private fun loopHandleTaskCenter() {
            if (Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_TASK_CENTER_DONE)) {
                Log.sports("健康岛任务大厅今日已处理，跳过")
                return
            }

            var errorCount = 0
            Log.sports("开始循环处理任务大厅（失败限制：$MAX_ERROR_COUNT 次）")

            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (errorCount >= MAX_ERROR_COUNT) {
                        Log.error(TAG, "任务处理失败次数达到上限，停止循环")
                        Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_TASK_CENTER_DONE)
                        break
                    }

                    val taskCenterResp = JSONObject(AntSportsRpcCall.NeverlandRpcCall.queryTaskCenter())
                    if (!ResChecker.checkRes(TAG, taskCenterResp) ||
                        taskCenterResp.optJSONObject("data") == null
                    ) {
                        errorCount++
                        GlobalThreadPools.sleepCompat(TASK_LOOP_DELAY)
                        continue
                    }

                    val taskList = taskCenterResp.getJSONObject("data").optJSONArray("taskCenterTaskVOS")
                    if (taskList == null || taskList.length() == 0) {
                        Log.sports("任务中心为空，无任务可处理")
                        Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_TASK_CENTER_DONE)
                        break
                    }

                    val pendingTasks = mutableListOf<JSONObject>()
                    for (i in 0 until taskList.length()) {
                        val task = taskList.optJSONObject(i) ?: continue

                        val title = task.optString("title", task.optString("taskName", "未知任务"))
                        val type = task.optString("taskType", "")
                        val status = task.optString("taskStatus", "")
                        val taskId = task.optString("id", task.optString("taskId", ""))

                        if ("NOT_SIGNUP" == status) {
                            Log.sports(
                                "任务[$title] classification=UNSUPPORTED_NO_CLOSURE decision=BLACKLIST " +
                                    "module=$SPORTS_TASK_BLACKLIST_MODULE taskId=$taskId taskName=$title " +
                                    "action=signup rpc=<none> reason=需要手动报名 status=$status raw=$task"
                            )
                            if (taskId.isNotEmpty()) {
                                TaskBlacklist.addToBlacklist(SPORTS_TASK_BLACKLIST_MODULE, taskId, title)
                            }
                            continue
                        }

                        if (TaskBlacklist.isTaskInBlacklist(SPORTS_TASK_BLACKLIST_MODULE, taskId) ||
                            TaskBlacklist.isTaskInBlacklist(SPORTS_TASK_BLACKLIST_MODULE, title)
                        ) {
                            continue
                        }

                        if (("PROMOKERNEL_TASK" == type || "LIGHT_TASK" == type) &&
                            "FINISHED" != status
                        ) {
                            pendingTasks.add(task)
                        }
                    }

                    if (pendingTasks.isEmpty()) {
                        Log.sports("没有可处理或领取的任务，退出循环")
                        Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_TASK_CENTER_DONE)
                        break
                    }

                    Log.sports("本次发现 ${pendingTasks.size} 个可处理任务（含待领取）")

                    var currentBatchError = 0
                    for (task in pendingTasks) {
                        val ok = handleSingleTask(task)
                        if (!ok) currentBatchError++
                        GlobalThreadPools.sleepCompat(3000)
                    }

                    errorCount += currentBatchError
                    Log.sports("当前批次执行完毕，准备下一次刷新检查")
                    GlobalThreadPools.sleepCompat(TASK_LOOP_DELAY)
                } catch (t: Throwable) {
                    errorCount++
                    Log.printStackTrace(TAG, "循环异常", t)
                }
            }
        }

        /**
         * @brief 处理单个大厅任务
         */
        private fun handleSingleTask(task: JSONObject): Boolean {
            return try {
                val title = task.optString("title", "未知任务")
                val type = task.optString("taskType", "")
                val status = task.optString("taskStatus", "")
                val jumpLink = task.optString("jumpLink", "")

                Log.sports("任务：[$title] 状态：$status 类型：$type")

                if ("TO_RECEIVE" == status) {
                    try {
                        task.put("scene", "MED_TASK_HALL")
                        if (!task.has("source")) {
                            task.put("source", "jkdsportcard")
                        }

                        val res = JSONObject(AntSportsRpcCall.NeverlandRpcCall.taskReceive(task))
                        if (res.optBoolean("success", false)) {
                            val data = res.optJSONObject("data")
                            var rewardDetail = ""
                            if (data != null && data.has("userItems")) {
                                val items = data.getJSONArray("userItems")
                                val sb = StringBuilder()
                                for (i in 0 until items.length()) {
                                    val item = items.getJSONObject(i)
                                    val name = item.optString("name", "未知奖励")
                                    val amount = item.optInt("modifyCount", 0)
                                    val total = item.optInt("count", 0)
                                    sb.append("[").append(name)
                                        .append(" +").append(amount)
                                        .append(" (余:").append(total).append(")] ")
                                }
                                rewardDetail = sb.toString()
                            }
                            Log.sports("完成[$title]✔$rewardDetail")
                            return true
                        } else {
                            val errorMsg = res.optString("errorMsg", "未知错误")
                            val errorCode = res.optString("errorCode", "UNKNOWN")
                            Log.error(TAG, "❌ 奖励领取失败 [$errorCode]: $errorMsg")
                            return false
                        }
                    } catch (e: Exception) {
                        Log.error(TAG, "领取流程异常: ${e.message}")
                        return false
                    }
                }

                if ("SIGNUP_COMPLETE" == status || "INIT" == status) {
                    return when (type) {
                        "PROMOKERNEL_TASK" -> handlePromoKernelTask(task, title)
                        "LIGHT_TASK" -> handleLightTask(task, title, jumpLink)
                        else -> {
                            Log.error(TAG, "未处理的任务类型：$type")
                            false
                        }
                    }
                }

                Log.sports("任务状态为 $status，跳过执行")
                true
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "handleSingleTask 异常", e)
                false
            }
        }

        // ---------------------------------------------------------------
        // 3. 健康岛浏览任务
        // ---------------------------------------------------------------

        /**
         * @brief 处理健康岛浏览任务（LIGHT_FEEDS_TASK）
         */
        private fun handleHealthIslandTask() {
            try {
                if (Status.hasFlagToday(StatusFlags.FLAG_NEVERLAND_LIGHT_FEEDS_DONE)) {
                    Log.sports("健康岛浏览任务今日已处理，跳过")
                    return
                }

                Log.sports("开始检查健康岛浏览任务")
                var hasTask = true
                val completedEncryptValues = mutableSetOf<String>()
                val nonRetryableEncryptValues = mutableSetOf<String>()
                while (hasTask) {
                    val taskInfoResp = JSONObject(
                        AntSportsRpcCall.NeverlandRpcCall.queryTaskInfo(
                            "health-island",
                            "LIGHT_FEEDS_TASK"
                        )
                    )

                    if (!ResChecker.checkRes(TAG, "查询健康岛浏览任务失败:", taskInfoResp) ||
                        taskInfoResp.optJSONObject("data") == null
                    ) {
                        Log.error(TAG, "健康岛浏览任务查询失败 [$taskInfoResp] 请关闭此功能")
                        return
                    }

                    val taskInfos = taskInfoResp.getJSONObject("data").optJSONArray("taskInfos")
                    if (taskInfos == null || taskInfos.length() == 0) {
                        Log.sports("健康岛浏览任务列表为空")
                        Status.setFlagToday(StatusFlags.FLAG_NEVERLAND_LIGHT_FEEDS_DONE)
                        hasTask = false
                        continue
                    }

                    val pendingTaskInfos = mutableListOf<JSONObject>()
                    val roundEncryptValues = mutableSetOf<String>()
                    var repeatedTaskCount = 0
                    var invalidTaskCount = 0

                    for (i in 0 until taskInfos.length()) {
                        val taskInfo = taskInfos.getJSONObject(i)
                        val taskTitle = taskInfo.optString("title", taskInfo.optString("taskName", "未知任务"))
                        val encryptValue = taskInfo.optString("encryptValue")
                        val viewSec = extractNeverlandViewSeconds(taskInfo)
                        val energyNum = taskInfo.optInt("energyNum", 0)

                        if (encryptValue.isEmpty()) {
                            invalidTaskCount++
                            Log.error(
                                TAG,
                                "健康岛任务[$taskTitle] encryptValue 为空，跳过 [viewSec=$viewSec][energyNum=$energyNum]"
                            )
                            continue
                        }
                        if (encryptValue in completedEncryptValues ||
                            encryptValue in nonRetryableEncryptValues ||
                            !roundEncryptValues.add(encryptValue)
                        ) {
                            repeatedTaskCount++
                            continue
                        }
                        pendingTaskInfos.add(taskInfo)
                    }

                    if (pendingTaskInfos.isEmpty()) {
                        if (repeatedTaskCount > 0) {
                            Log.sports("健康岛浏览任务本轮仅返回已处理任务，停止循环以避免重复完成[count=$repeatedTaskCount]"
                            )
                        }
                        if (invalidTaskCount == 0) {
                            Status.setFlagToday(StatusFlags.FLAG_NEVERLAND_LIGHT_FEEDS_DONE)
                        }
                        hasTask = false
                        continue
                    }

                    for (taskInfo in pendingTaskInfos) {
                        val taskTitle = taskInfo.optString("title", taskInfo.optString("taskName", "未知任务"))
                        val encryptValue = taskInfo.optString("encryptValue")
                        val energyNum = taskInfo.optInt("energyNum", 0)
                        val viewSec = extractNeverlandViewSeconds(taskInfo)

                        Log.sports("健康岛浏览任务[$taskTitle]：能量+$energyNum，直接提交领取RPC(viewSec=${viewSec}s)"
                        )

                        val receiveResp = JSONObject(
                            AntSportsRpcCall.NeverlandRpcCall.energyReceive(
                                encryptValue,
                                energyNum,
                                "LIGHT_FEEDS_TASK",
                                null
                            )
                        )
                        if (ResChecker.checkRes(TAG, "领取健康岛任务奖励:", receiveResp) &&
                            ResChecker.checkRes(TAG, receiveResp)
                        ) {
                            completedEncryptValues.add(encryptValue)
                            Log.sports("✅ 健康岛浏览任务[$taskTitle]完成，获得能量+$energyNum")
                        } else {
                            if (!isSportsRpcRetryable(receiveResp)) {
                                nonRetryableEncryptValues.add(encryptValue)
                                Log.sports("健康岛任务[$taskTitle]非重试失败，加入本轮跳过列表[encryptValue=$encryptValue]"
                                )
                            }
                            Log.error(
                                TAG,
                                "健康岛任务领取失败[$taskTitle][viewSec=$viewSec][energyNum=$energyNum][encryptValue=$encryptValue]: $receiveResp"
                            )
                        }

                        GlobalThreadPools.sleepCompat(1000)
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "handleHealthIslandTask err", t)
            }
        }

        // ---------------------------------------------------------------
        // 4. PROMOKERNEL_TASK / LIGHT_TASK 处理
        // ---------------------------------------------------------------

        /**
         * @brief 处理 PROMOKERNEL_TASK（活动类任务）
         */
        private fun handlePromoKernelTask(task: JSONObject, title: String): Boolean {
            return try {
                task.put("scene", "MED_TASK_HALL")
                val res = JSONObject(AntSportsRpcCall.NeverlandRpcCall.taskSend(task))
                val errorCode = res.optString("errorCode").ifBlank { res.optString("code") }
                if (errorCode == "TASK_TRIGGER_ERROR") {
                    val taskId = task.optString("id", task.optString("taskId", ""))
                    val errorMsg = res.optString("errorMsg").ifBlank { res.optString("desc", "任务推进失败") }
                    Log.sports("活动任务推进待确认：$title[taskId=$taskId] $errorMsg，保留下一轮刷新领奖")
                    false
                } else if (ResChecker.checkRes(TAG, res)) {
                    Log.sports("✔ 活动任务完成：$title")
                    true
                } else {
                    Log.error(TAG, "taskSend 失败: $task 响应：$res")
                    false
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "handlePromoKernelTask 处理 PROMOKERNEL_TASK 异常（$title）", e)
                false
            }
        }

        /**
         * @brief 处理 LIGHT_TASK（浏览类任务）
         */
        private fun handleLightTask(task: JSONObject, title: String, jumpLink: String): Boolean {
            return try {
                var bizId = task.optString("bizId", "")
                if (bizId.isEmpty()) {
                    val logExtMap = task.optJSONObject("logExtMap")
                    if (logExtMap != null) {
                        bizId = logExtMap.optString("bizId", "")
                    }
                }

                if (bizId.isEmpty()) {
                    Log.error(TAG, "LIGHT_TASK 未找到 bizId：$title jumpLink=$jumpLink")
                    return false
                }

                val res = JSONObject(AntSportsRpcCall.NeverlandRpcCall.finish(bizId))
                if (res.optBoolean("success", false) ||
                    "0" == res.optString("errCode", "")
                ) {
                    var rewardMsg = ""
                    val extendInfo = res.optJSONObject("extendInfo")
                    if (extendInfo != null) {
                        val rewardInfo = extendInfo.optJSONObject("rewardInfo")
                        if (rewardInfo != null) {
                            val amount = rewardInfo.optString("rewardAmount", "0")
                            rewardMsg = " (获得奖励: $amount 能量)"
                        }
                    }
                    Log.sports("✔ 浏览任务完成：$title$rewardMsg")
                    true
                } else {
                    Log.error(TAG, "完成 LIGHT_TASK 失败: $title 返回: $res")
                    false
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "handleLightTask 处理 LIGHT_TASK 异常（$title）", e)
                false
            }
        }

        // ---------------------------------------------------------------
        // 5. 捡泡泡
        // ---------------------------------------------------------------

        private fun isNeverlandBubbleReceivable(status: String): Boolean {
            return status.equals("TO_RECEIVE", ignoreCase = true)
        }

        private fun isNeverlandBubbleTerminal(status: String): Boolean {
            return when (status.uppercase(Locale.ROOT)) {
                "RECEIVE_SUCCESS", "RECEIVED", "FINISHED" -> true
                else -> false
            }
        }

        /**
         * @brief 健康岛捡泡泡 + 浏览类泡泡任务
         */
        private fun neverlandPickAllBubble(onlySource: String? = null) {
            try {
                val sources = onlySource?.let { listOf(it) } ?: candidateNeverlandSources()
                var queriedAny = false
                var foundActionable = false

                for (source in sources) {
                    Log.sports("健康岛 · 检查可领取泡泡[source=$source]")
                    val jo = JSONObject(AntSportsRpcCall.NeverlandRpcCall.queryBubbleTask(source))

                    if (!ResChecker.checkRes(TAG, "查询泡泡失败:", jo) ||
                        jo.optJSONObject("data") == null
                    ) {
                        Log.error(TAG, "queryBubbleTask source=$source raw=$jo")
                        continue
                    }
                    rememberNeverlandSource(source)
                    queriedAny = true

                    val arr = jo.getJSONObject("data").optJSONArray("bubbleTaskVOS")
                    if (arr == null || arr.length() == 0) {
                        continue
                    }

                    val ids = mutableListOf<String>()
                    val encryptTasks = mutableListOf<JSONObject>()
                    val centerTaskItems = mutableListOf<JSONObject>()

                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val bubbleTaskStatus = item.optString("bubbleTaskStatus")
                        val encryptValue = item.optString("encryptValue")
                        val energyNum = item.optInt("energyNum", 0)
                        val viewSec = extractNeverlandViewSeconds(item)
                        val title = item.optString("title", item.optString("taskId", "未知泡泡任务"))
                        val centerTask = item.optJSONObject("taskCenterTaskVO")

                        if (centerTask != null) {
                            val taskId = centerTask.optString("taskId", item.optString("taskId", ""))
                            if (taskId.isNotBlank() && taskId !in handledNeverlandBubbleTaskIds &&
                                centerTask.optString("taskStatus", "") != "FINISHED"
                            ) {
                                centerTaskItems.add(item)
                                Log.sports("找到健康岛泡泡任务中心任务：$title(viewSec=${viewSec}s)")
                            }
                            continue
                        }

                        if ("INIT" == bubbleTaskStatus && encryptValue.isNotEmpty()) {
                            if (encryptValue in handledNeverlandBubbleEncryptValues) {
                                Log.sports("浏览任务已处理，跳过重复提交：$title")
                                continue
                            }
                            encryptTasks.add(item)
                            Log.sports("找到可浏览任务：$title，能量+$energyNum，直接提交领取RPC(viewSec=${viewSec}s)")
                        } else if (!item.optBoolean("initState") &&
                            item.optString("medEnergyBallInfoRecordId").isNotEmpty()
                        ) {
                            val recordId = item.getString("medEnergyBallInfoRecordId")
                            if (recordId in pickedNeverlandBubbleRecordIds) {
                                Log.sports("泡泡奖励已处理，跳过重复领取[recordId=$recordId]")
                                continue
                            }
                            if (!isNeverlandBubbleReceivable(bubbleTaskStatus)) {
                                if (isNeverlandBubbleTerminal(bubbleTaskStatus)) {
                                    pickedNeverlandBubbleRecordIds.add(recordId)
                                }
                                Log.sports(
                                    "健康岛泡泡状态不可领取，跳过[$title]" +
                                        "[status=${bubbleTaskStatus.ifBlank { "UNKNOWN" }}][recordId=$recordId]"
                                )
                                continue
                            }
                            ids.add(recordId)
                        }
                    }

                    if (ids.isEmpty() && encryptTasks.isEmpty() && centerTaskItems.isEmpty()) {
                        continue
                    }
                    foundActionable = true

                    if (ids.isNotEmpty()) {
                        Log.sports("健康岛 · 正在领取 ${ids.size} 个泡泡[source=$source]…")
                        val pick = JSONObject(
                            AntSportsRpcCall.NeverlandRpcCall.pickBubbleTaskEnergy(
                                ids,
                                source,
                                pickAllEnergyBall = true
                            )
                        )

                        if (!ResChecker.checkRes(TAG, "领取泡泡失败:", pick) ||
                            pick.optJSONObject("data") == null
                        ) {
                            Log.error(TAG, "pickBubbleTaskEnergy source=$source raw=$pick")
                        } else {
                            val data = pick.getJSONObject("data")
                            val changeAmount = data.optString("changeAmount", "0")
                            val balance = data.optString("balance", "0")
                            if (changeAmount == "0") {
                                Log.sports("健康岛 · 本次未获得任何能量")
                            } else {
                                Log.sports("捡泡泡成功 🎈 +$changeAmount 余额：$balance")
                            }
                            pickedNeverlandBubbleRecordIds.addAll(ids)
                        }
                    }

                    for (item in centerTaskItems) {
                        val task = item.optJSONObject("taskCenterTaskVO") ?: continue
                        val taskId = task.optString("taskId", item.optString("taskId", ""))
                        if (handleSingleTask(task)) {
                            if (taskId.isNotBlank()) handledNeverlandBubbleTaskIds.add(taskId)
                        }
                    }

                    for (item in encryptTasks) {
                        val encryptValue = item.optString("encryptValue")
                        val energyNum = item.optInt("energyNum", 0)
                        val title = item.optString("title", encryptValue)
                        val viewSec = extractNeverlandViewSeconds(item)

                        Log.sports("开始浏览任务[$title]，任务 encryptValue: $encryptValue")
                        val receiveResp = JSONObject(
                            AntSportsRpcCall.NeverlandRpcCall.energyReceive(
                                encryptValue,
                                energyNum,
                                "LIGHT_FEEDS_TASK",
                                "adBubble",
                                source
                            )
                        )

                        if (ResChecker.checkRes(TAG, "领取泡泡任务奖励:", receiveResp)) {
                            handledNeverlandBubbleEncryptValues.add(encryptValue)
                            Log.sports("✅ 浏览任务[$title]完成，获得能量+$energyNum")
                        } else {
                            if (!isSportsRpcRetryable(receiveResp)) {
                                handledNeverlandBubbleEncryptValues.add(encryptValue)
                            }
                            Log.error(TAG, "浏览任务领取失败[source=$source]: $receiveResp")
                        }
                    }
                }

                if (!queriedAny) {
                    Log.error(TAG, "健康岛泡泡所有 source 查询失败")
                } else if (!foundActionable) {
                    Log.sports("没有可领取的泡泡任务")
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "neverlandPickAllBubble err:", t)
            }
        }

        // ---------------------------------------------------------------
        // 6. 自动走路建造（步数限制 + 能量限制）
        // ---------------------------------------------------------------

        /**
         * @brief 检查今日建造次数是否达到本地安全上限
         * @return 剩余可建造次数（<=0 表示已达上限；null 表示不按次数限制）
         */
        private fun checkDailyStepLimit(): Int? {
            val buildCount = Status.getIntFlagToday(StatusFlags.FLAG_NEVERLAND_STEP_COUNT) ?: 0
            val maxBuildLimit = configuredNeverlandBuildLimit()
            if (maxBuildLimit <= 0) {
                Log.sports("今日建造次数统计: 已执行 $buildCount 次，本地上限 不限制")
                return null
            }

            val remainBuildCount = maxBuildLimit - buildCount
            Log.sports(
                String.format(
                    "今日建造次数统计: 已执行 %d/%d 次, 剩余 %d 次",
                    buildCount,
                    maxBuildLimit,
                    max(0, remainBuildCount)
                )
            )
            return remainBuildCount
        }

        /**
         * @brief 记录建造次数增加
         * @param addedSteps 本次增加的建造次数
         * @return 更新后的总建造次数
         */
        private fun recordStepIncrease(addedSteps: Int): Int {
            if (addedSteps <= 0) {
                return Status.getIntFlagToday(StatusFlags.FLAG_NEVERLAND_STEP_COUNT) ?: 0
            }
            var currentSteps = Status.getIntFlagToday(StatusFlags.FLAG_NEVERLAND_STEP_COUNT) ?: 0
            val newSteps = currentSteps + addedSteps
            Status.setIntFlagToday(StatusFlags.FLAG_NEVERLAND_STEP_COUNT, newSteps)
            Log.sports(
                String.format(
                    "建造次数增加: +%d 次, 当前总计 %d/%s 次",
                    addedSteps,
                    newSteps,
                    formatNeverlandBuildLimit()
                )
            )
            return newSteps
        }

        private fun configuredNeverlandBuildLimit(): Int {
            return (neverlandGridStepCount.value ?: 0).coerceAtLeast(0)
        }

        private fun formatNeverlandBuildLimit(): String {
            val limit = configuredNeverlandBuildLimit()
            return if (limit > 0) limit.toString() else "不限"
        }

        /**
         * @brief 健康岛走路建造任务入口
         */
        private fun warmNeverlandQuickGameList(source: String) {
            if (source != NEVERLAND_SOURCE_SPORT_HOME) return
            runCatching {
                val quickGame = JSONObject(AntSportsRpcCall.NeverlandRpcCall.queryQuickGameList(source))
                if (!ResChecker.checkRes(TAG, " 查询健康岛快捷入口失败:", quickGame)) {
                    Log.error(
                        TAG,
                        "queryQuickGameList 失败[source=$source][code=${extractSportsRpcErrorCode(quickGame).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(quickGame)}] raw=$quickGame"
                    )
                }
            }.onFailure {
                Log.printStackTrace(TAG, "queryQuickGameList err", it)
            }
        }

        private fun queryNeverlandBaseInfo(): Pair<String, JSONObject>? {
            for (source in candidateNeverlandSources()) {
                warmNeverlandQuickGameList(source)
                val baseInfo = JSONObject(AntSportsRpcCall.NeverlandRpcCall.queryBaseinfo(source))
                val baseData = baseInfo.optJSONObject("data")
                if (ResChecker.checkRes(TAG, " 查询基础信息失败:", baseInfo) && baseData != null &&
                    (!baseData.optBoolean("newGame", false) || baseData.optString("mapId", "").isNotBlank())
                ) {
                    rememberNeverlandSource(source)
                    return source to baseInfo
                }
                Log.error(TAG, "queryBaseinfo 失败[source=$source], 响应数据: $baseInfo")

                val mapListBaseInfo = queryNeverlandBaseInfoFromMapList(source)
                if (mapListBaseInfo != null) {
                    rememberNeverlandSource(source)
                    return source to mapListBaseInfo
                }
            }
            return null
        }

        private fun queryNeverlandBaseInfoFromMapList(source: String): JSONObject? {
            val mapList = queryNeverlandMapList(source) ?: return null
            val selected = selectNeverlandMap(mapList) ?: return null
            val data = JSONObject().apply {
                put("newGame", selected.optBoolean("newIsLandFlg", true))
                put("branchId", selected.optString("branchId", "MASTER").ifBlank { "MASTER" })
                put("mapId", selected.optString("mapId", ""))
                put("mapName", selected.optString("mapName", selected.optString("mapId", "")))
            }
            return JSONObject().put("data", data)
        }

        private fun neverlandAutoTask() {
            try {
                Log.sports("健康岛 · 启动走路建造任务")

                val baseInfoResult = queryNeverlandBaseInfo() ?: return
                val source = baseInfoResult.first
                val baseInfo = baseInfoResult.second

                val baseData = baseInfo.getJSONObject("data")
                val isNewGame = baseData.optBoolean("newGame", false)
                var branchId = baseData.optString("branchId", "MASTER")
                var mapId = baseData.optString("mapId", "")
                var mapName = baseData.optString("mapName", "未知地图")
                if (isNewGame) {
                    handlePendingMapRewards(source)
                    val selectedMap = queryNeverlandMapList(source)?.let { selectNeverlandMap(it) }
                    val activeMap = if (selectedMap != null &&
                        selectedMap.optString("mapId", "") != mapId
                    ) {
                        chooseMap(selectedMap, source, mapId)
                    } else {
                        selectedMap
                    }
                    if (activeMap != null && activeMap.optBoolean("newIsLandFlg", true)) {
                        branchId = activeMap.optString("branchId", branchId).ifBlank { branchId }
                        mapId = activeMap.optString("mapId", mapId).ifBlank { mapId }
                        mapName = activeMap.optString("mapName", mapName).ifBlank { mapName }
                    }
                }
                if (isNewGame && Status.hasFlagToday(StatusFlags.FLAG_ANTSPORTS_NEVERLAND_ENERGY_LIMIT)) {
                    Log.sports("健康岛 · 今日已判定能量不足以单倍建造，跳过自动建造")
                    return
                }

                Log.sports(String.format(
                        "当前地图: [%s](%s) | 模式: %s",
                        mapName,
                        mapId,
                        if (isNewGame) "新游戏建造" else "旧版行走"
                    )
                )

                val remainSteps = checkDailyStepLimit()
                if (remainSteps != null && remainSteps <= 0) {
                    Log.sports("今日健康岛建造次数已达本地上限, 任务结束")
                    return
                }

                var leftEnergy = queryUserEnergy(source)
                if (leftEnergy < 0) {
                    Log.error(TAG, "健康岛 · 查询用户能量失败，停止本轮自动建造")
                    return
                }
                if (isNewGame) {
                    neverlandPickAllBubble(source)
                    val refreshedEnergy = queryUserEnergy(source)
                    if (refreshedEnergy >= 0) {
                        leftEnergy = refreshedEnergy
                    } else {
                        Log.error(TAG, "健康岛 · 领取泡泡后刷新能量失败，停止本轮自动建造")
                        return
                    }
                }
                if (!isNewGame && leftEnergy < 5) {
                    Log.sports("剩余能量不足(< 5), 无法执行任务")
                    return
                }

                if (isNewGame) {
                    executeAutoBuild(branchId, mapId, remainSteps, leftEnergy, mapName, source)
                    neverlandPickAllBubble(source)
                } else {
                    executeAutoWalk(branchId, mapId, remainSteps, leftEnergy, mapName, source)
                }

                Log.sports("健康岛自动走路建造执行完成 ✓")
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "neverlandAutoTask 发生异常$t", t)
            }
        }

        /**
         * @brief 查询用户剩余能量
         */
        private fun queryUserEnergy(source: String = activeNeverlandSource): Int {
            return try {
                val energyResp = JSONObject(AntSportsRpcCall.NeverlandRpcCall.queryUserEnergy(source))
                if (!ResChecker.checkRes(TAG, " 查询用户能量失败:", energyResp) ||
                    energyResp.optJSONObject("data") == null
                ) {
                    Log.error(TAG, "queryUserEnergy 失败[source=$source], 响应数据: $energyResp")
                    -1
                } else {
                    val balance = energyResp.getJSONObject("data").optInt("balance", 0)
                    Log.sports("当前剩余能量: $balance")
                    balance
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "queryUserEnergy err", t)
                -1
            }
        }

        /**
         * @brief 执行旧版行走任务（能量泵走路模式）
         */
        private fun executeAutoWalk(
            branchId: String,
            mapId: String,
            remainSteps: Int?,
            leftEnergyInit: Int,
            mapName: String,
            source: String
        ) {
            var leftEnergy = leftEnergyInit
            try {
                Log.sports("开始执行旧版行走任务")
                val mapInfoResp = JSONObject(
                    AntSportsRpcCall.NeverlandRpcCall.queryMapInfo(mapId, branchId, source)
                )

                if (!ResChecker.checkRes(TAG, " queryMapInfo 失败:", mapInfoResp) ||
                    mapInfoResp.optJSONObject("data") == null
                ) {
                    Log.error(TAG, "queryMapInfo 失败，终止走路任务")
                    return
                }

                val mapInfo = mapInfoResp.getJSONObject("data")
                if (!mapInfo.optBoolean("canWalk", false)) {
                    Log.sports("当前地图不可走(canWalk=false)，跳过走路任务")
                    return
                }

                val mapStarData = mapInfo.optJSONObject("starData")
                var lastCurrStar = mapStarData?.optInt("curr", 0) ?: 0

                var remainingWalkCount = remainSteps
                while (remainingWalkCount == null || remainingWalkCount > 0) {
                    if (leftEnergy < 5) {
                        Log.sports("[$mapName] 能量不足(< 5), 停止走路任务")
                        break
                    }

                    val walkResp = JSONObject(
                        AntSportsRpcCall.NeverlandRpcCall.walkGrid(branchId, mapId, false, source)
                    )

                    if (!ResChecker.checkRes(TAG, " walkGrid 失败:", walkResp) ||
                        walkResp.optJSONObject("data") == null
                    ) {
                        val errorCode = walkResp.optString("errorCode", "")
                        Log.error(
                            TAG,
                            String.format(
                                "walkGrid 失败, 错误码: %s, 响应数据: %s",
                                errorCode,
                                walkResp
                            )
                        )
                        break
                    }

                    val walkData = walkResp.getJSONObject("data")
                    leftEnergy = walkData.optInt("leftCount", leftEnergy)

                    recordStepIncrease(1)
                    remainingWalkCount = remainingWalkCount?.minus(1)
                    val stepThisTime = extractStepIncrease(walkData)

                    val starData = walkData.optJSONObject("starData")
                    val currStar = starData?.optInt("curr", lastCurrStar) ?: lastCurrStar
                    val maxStar = starData?.optInt("count", 0) ?: Int.MAX_VALUE
                    val starIncreased = currStar > lastCurrStar
                    lastCurrStar = currStar

                    var redPocketAdd = 0
                    val userItems = walkData.optJSONArray("userItems")
                    if (userItems != null && userItems.length() > 0) {
                        val item = userItems.optJSONObject(0)
                        if (item != null) {
                            redPocketAdd = item.optInt("modifyCount", item.optInt("count", 0))
                        }
                    }

                    val sb = StringBuilder()
                    sb.append("[").append(mapName).append("] 前进 ")
                        .append(stepThisTime).append(" 步，")

                    if (starIncreased) {
                        sb.append("获得 🌟")
                    } else if (redPocketAdd > 0) {
                        sb.append("获得 🧧 +").append(redPocketAdd)
                    } else {
                        sb.append("啥也没有")
                    }

                    Log.sports(sb.toString())

                    tryReceiveStageReward(branchId, mapId, starData, source)

                    if (currStar >= maxStar) {
                        Log.sports("[$mapName] 当前地图已完成星星，准备切换地图")
                        chooseAvailableMap(source = source)
                        break
                    }
                    GlobalThreadPools.sleepCompat(888)
                }
                Log.sports("自动走路任务完成 ✓")
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "executeAutoWalk err", t)
            }
        }

        /**
         * @brief 若有未领取的关卡奖励则尝试领取
         */
        private fun tryReceiveStageReward(
            branchId: String,
            mapId: String,
            starData: JSONObject?,
            source: String = activeNeverlandSource
        ) {
            if (starData == null) return

            val rewardLevel = starData.optInt("rewardLevel", -1)
            if (rewardLevel <= 0) return

            val recordArr = starData.optJSONArray("stageRewardRecord")
            if (recordArr != null) {
                for (i in 0 until recordArr.length()) {
                    if (recordArr.optInt(i, -1) == rewardLevel) return
                }
            }

            Log.sports(String.format("检测到未领取关卡奖励 🎁 map=%s 等级: %d，尝试领取…", mapId, rewardLevel))

            val rewardStr = try {
                AntSportsRpcCall.NeverlandRpcCall.mapStageReward(branchId, rewardLevel, mapId, source)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "mapStageReward RPC 调用异常", t)
                return
            }.trim()

            if (rewardStr.isEmpty()) {
                Log.error(TAG, "mapStageReward 返回空字符串")
                return
            }
            if (!rewardStr.startsWith("{")) {
                Log.error(TAG, "mapStageReward 返回非 JSON: $rewardStr")
                return
            }

            val rewardResp = try {
                JSONObject(rewardStr)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "mapStageReward JSON 解析失败", t)
                return
            }

            if (!ResChecker.checkRes(TAG, rewardResp)) {
                val errCode = rewardResp.optString("errorCode", "")
                if ("ASSET_ITEM_NOT_EXISTED" == errCode) {
                    Log.sports("关卡奖励已被领取或不存在（可忽略）")
                } else {
                    Log.error(TAG, "领取关卡奖励失败: $rewardResp")
                }
                return
            }

            val data = rewardResp.optJSONObject("data")
            val receiveResult = data?.optJSONObject("receiveResult")
            if (receiveResult == null) {
                Log.sports("关卡奖励领取成功 🎉（无奖励详情）")
                return
            }

            val prizes = receiveResult.optJSONArray("prizes")
            val balance = receiveResult.optString("balance", "")

            if (prizes != null && prizes.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until prizes.length()) {
                    val p = prizes.optJSONObject(i) ?: continue
                    sb.append(p.optString("title", "未知奖励"))
                        .append(" x")
                        .append(p.optString("modifyCount", "1"))
                    if (i != prizes.length() - 1) sb.append("，")
                }
                Log.sports(
                    String.format(
                        "Lv.%s 奖励领取成功 🎉 %s | 当前余额: %s",
                        rewardLevel,
                        sb.toString(),
                        balance
                    )
                )
            } else {
                Log.sports("关卡奖励领取成功 🎉（无可展示奖励）")
            }
        }

        /**
         * @brief 查询地图列表，按服务端顺序选择可处理的新游戏岛屿
         */
        private fun queryNeverlandMapList(source: String = activeNeverlandSource): JSONArray? {
            return try {
                val mapResp = JSONObject(AntSportsRpcCall.NeverlandRpcCall.queryMapList(source))
                if (!ResChecker.checkRes(TAG, " 查询地图失败:", mapResp)) {
                    Log.error(
                        TAG,
                        "queryMapList 失败[code=${extractSportsRpcErrorCode(mapResp).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(mapResp)}] raw=$mapResp"
                    )
                    return null
                }

                val mapList = mapResp.optJSONObject("data")?.optJSONArray("mapList")
                if (mapList == null || mapList.length() == 0) {
                    Log.error(TAG, "地图列表为空")
                    return null
                }
                mapList
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "queryNeverlandMapList err", t)
                null
            }
        }

        private fun selectNeverlandMap(
            mapList: JSONArray,
            skipMapId: String? = null
        ): JSONObject? {
            var finishNotRewardMap: JSONObject? = null
            var doingMap: JSONObject? = null
            var firstUnfinishedMap: JSONObject? = null
            var firstUnfinishedOrder = Long.MAX_VALUE
            for (i in 0 until mapList.length()) {
                val map = mapList.optJSONObject(i) ?: continue
                val mapId = map.optString("mapId", "")
                val order = map.optLong("order", Long.MIN_VALUE)
                if (skipMapId != null && mapId == skipMapId) {
                    continue
                }
                val status = map.optString("status")
                if ("FINISH_NOT_REWARD" == status && finishNotRewardMap == null &&
                    !isNeverlandRewardUnavailableToday(mapId)
                ) {
                    finishNotRewardMap = map
                }
                if ("DOING" == status && doingMap == null) {
                    doingMap = map
                }
                val currentPercent = map.optInt("currentPercent", 0)
                if (map.optBoolean("newIsLandFlg", true) &&
                    status != "FINISH" &&
                    status != "FINISH_NOT_REWARD" &&
                    currentPercent < 100
                ) {
                    val shouldUse = firstUnfinishedMap == null ||
                        (order != Long.MIN_VALUE &&
                            (firstUnfinishedOrder == Long.MIN_VALUE || order < firstUnfinishedOrder))
                    if (shouldUse) {
                        firstUnfinishedMap = map
                        firstUnfinishedOrder = order
                    }
                }
            }
            return if (skipMapId == null) {
                doingMap ?: firstUnfinishedMap ?: finishNotRewardMap
            } else {
                firstUnfinishedMap ?: finishNotRewardMap
            }
        }

        private fun handlePendingMapRewards(source: String = activeNeverlandSource) {
            val mapList = queryNeverlandMapList(source) ?: return
            for (i in 0 until mapList.length()) {
                val map = mapList.optJSONObject(i) ?: continue
                if (map.optString("status", "") != "FINISH_NOT_REWARD") {
                    continue
                }
                val mapId = map.optString("mapId", "")
                if (mapId.isBlank()) continue
                if (isNeverlandRewardUnavailableToday(mapId)) {
                    Log.sports("健康岛[${map.optString("mapName", mapId)}]今日已确认奖励不可领取，跳过重复领奖")
                    continue
                }
                val branchId = map.optString("branchId", "MASTER").ifBlank { "MASTER" }
                val mapName = map.optString("mapName", mapId)
                if (!::neverlandAutoReward.isInitialized || neverlandAutoReward.value == true) {
                    Log.sports("健康岛[$mapName]已完成待领奖，尝试优先领取奖励")
                    tryChooseMapReward(branchId, mapId, source)
                } else {
                    Log.sports("健康岛自动领奖已关闭，跳过待领奖岛屿[$mapName]")
                }
            }
        }

        private fun chooseAvailableMap(
            skipMapId: String? = null,
            source: String = activeNeverlandSource
        ): JSONObject? {
            return try {
                val mapList = queryNeverlandMapList(source) ?: return null
                val selected = selectNeverlandMap(mapList, skipMapId)
                if (selected == null) {
                    Log.sports("健康岛没有可切换的未完成岛屿")
                    return null
                }
                Log.sports("健康岛选择岛屿[${selected.optString("mapName", selected.optString("mapId"))}][status=${selected.optString("status", "")}]"
                )
                chooseMap(selected, source, skipMapId)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "chooseAvailableMap err", t)
                null
            }
        }

        /**
         * @brief 切换当前地图
         */
        private fun chooseMap(
            map: JSONObject,
            source: String = activeNeverlandSource,
            fromMapId: String? = null
        ): JSONObject? {
            return try {
                val mapId = map.optString("mapId")
                val branchId = map.optString("branchId", "MASTER").ifBlank { "MASTER" }
                val resp = JSONObject(
                    AntSportsRpcCall.NeverlandRpcCall.chooseMap(branchId, mapId, source)
                )
                if (ResChecker.checkRes(TAG, resp)) {
                    syncChosenMapBaseInfo(branchId, mapId, fromMapId, source)
                    Log.sports("切换地图成功: $mapId")
                    map
                } else {
                    Log.error(
                        TAG,
                        "切换地图失败[mapId=$mapId][branchId=$branchId][code=${extractSportsRpcErrorCode(resp).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(resp)}] raw=$resp"
                    )
                    null
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "chooseMap err", t)
                null
            }
        }

        private fun syncChosenMapBaseInfo(
            branchId: String,
            mapId: String,
            fromMapId: String?,
            source: String = activeNeverlandSource
        ) {
            try {
                val resp = JSONObject(
                    AntSportsRpcCall.NeverlandRpcCall.queryBaseinfo(branchId, mapId, fromMapId, source)
                )
                if (!ResChecker.checkRes(TAG, " 切岛后同步基础信息失败:", resp)) {
                    Log.error(
                        TAG,
                        "切岛后同步基础信息失败[mapId=$mapId][fromMapId=${fromMapId.orEmpty()}]" +
                            "[code=${extractSportsRpcErrorCode(resp).ifEmpty { "UNKNOWN" }}]" +
                            "[msg=${extractSportsRpcErrorMessage(resp)}] raw=$resp"
                    )
                    return
                }
                val syncedMapId = resp.optJSONObject("data")?.optString("mapId", "").orEmpty()
                if (syncedMapId.isNotBlank() && syncedMapId != mapId) {
                    Log.error(TAG, "切岛后基础信息仍指向[$syncedMapId]，目标地图[$mapId]")
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "syncChosenMapBaseInfo err", t)
            }
        }

        /**
         * @brief 从 walkData 中提取步数增量
         */
        private fun extractStepIncrease(walkData: JSONObject): Int {
            return try {
                val mapAwards = walkData.optJSONArray("mapAwards")
                if (mapAwards != null && mapAwards.length() > 0) {
                    mapAwards.getJSONObject(0).optInt("step", 0)
                } else {
                    0
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
                0
            }
        }

        /**
         * @brief 执行自动建造任务（新游戏模式）
         */
        private fun executeAutoBuild(
            branchIdInit: String,
            mapIdInit: String,
            remainStepsInit: Int?,
            leftEnergyInit: Int,
            mapName: String,
            source: String
        ) {
            var branchId = branchIdInit.ifBlank { "MASTER" }
            var mapId = mapIdInit
            var remainSteps = remainStepsInit
            var leftEnergy = leftEnergyInit
            var currentMapName = mapName
            val blockedMultis = mutableSetOf<Int>()
            try {
                Log.sports(String.format("开始执行建造任务, 地图: %s", mapId))

                while (remainSteps == null || remainSteps > 0) {
                    neverlandPickAllBubble(source)
                    val refreshedEnergy = queryUserEnergy(source)
                    if (refreshedEnergy >= 0) {
                        leftEnergy = refreshedEnergy
                    } else if (leftEnergy <= 0) {
                        Log.error(TAG, "健康岛[$currentMapName]刷新能量失败，停止本轮建造")
                        return
                    }

                    val mapInfo = JSONObject(
                        AntSportsRpcCall.NeverlandRpcCall.queryMapInfoNew(mapId, branchId, source)
                    )
                    if (!ResChecker.checkRes(TAG, " 查询建造地图失败", mapInfo)) {
                        Log.error(
                            TAG,
                            "查询建造地图失败[mapId=$mapId][branchId=$branchId][code=${extractSportsRpcErrorCode(mapInfo).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(mapInfo)}] raw=$mapInfo"
                        )
                        return
                    }
                    val data = mapInfo.optJSONObject("data")
                    if (data == null) {
                        Log.error(TAG, "地图Data 为空，无法解析 raw=$mapInfo")
                        return
                    }

                    branchId = data.optString("branchId", branchId).ifBlank { branchId }
                    mapId = data.optString("mapId", mapId).ifBlank { mapId }
                    currentMapName = data.optString("mapName", currentMapName).ifBlank { currentMapName }
                    val mapEnergyFinal = data.optInt("mapEnergyFinal", 0)
                    val mapEnergyProcess = data.optInt("mapEnergyProcess", 0)
                    val mapStatus = data.optString("mapStatus", "")
                    if (isNeverlandMapFinished(mapStatus, mapEnergyProcess, mapEnergyFinal)) {
                        Log.sports("健康岛[$currentMapName]已完成，准备领奖与切换岛屿")
                        neverlandPickAllBubble(source)
                        if (!::neverlandAutoReward.isInitialized || neverlandAutoReward.value == true) {
                            tryChooseMapReward(branchId, mapId, source)
                        } else {
                            Log.sports("健康岛自动领奖已关闭，跳过[$currentMapName]")
                        }
                        val nextMap = chooseAvailableMap(mapId, source) ?: return
                        if (!nextMap.optBoolean("newIsLandFlg", true)) {
                            Log.sports("已切换至旧版走路地图[${nextMap.optString("mapId")}], 本轮新游戏建造结束")
                            return
                        }
                        branchId = nextMap.optString("branchId", "MASTER").ifBlank { "MASTER" }
                        mapId = nextMap.optString("mapId", "")
                        currentMapName = nextMap.optString("mapName", mapId)
                        blockedMultis.clear()
                        if (mapId.isBlank()) {
                            Log.error(TAG, "健康岛切换后 mapId 为空 raw=$nextMap")
                            return
                        }
                        continue
                    }

                    val mapConfig = data.optJSONObject("mapConfig")
                    val oneBuildEnergy = mapConfig?.optInt("oneBuildEnergy", 5)?.takeIf { it > 0 } ?: 5
                    val buildMultis = extractBuildMultis(mapConfig)
                    val multiNum = buildMultis.firstOrNull { multi ->
                        val withinLocalLimit = remainSteps?.let { multi <= it } ?: true
                        multi !in blockedMultis && withinLocalLimit && leftEnergy >= oneBuildEnergy * multi
                    }

                    if (multiNum == null) {
                        if (leftEnergy < oneBuildEnergy) {
                            Log.sports("健康岛[$currentMapName]单倍能量不足[balance=$leftEnergy, oneBuildEnergy=$oneBuildEnergy]，今日停止建造"
                            )
                            Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_NEVERLAND_ENERGY_LIMIT)
                        } else if (remainSteps == null) {
                            Log.sports("健康岛[$currentMapName]无可用建造倍数，停止本轮建造")
                        } else {
                            Log.sports("健康岛[$currentMapName]今日建造次数不足以执行最小倍数[remainSteps=${remainSteps ?: "不限"}]")
                        }
                        return
                    }

                    val buildResp = JSONObject(
                        AntSportsRpcCall.NeverlandRpcCall.build(mapId, multiNum, branchId, source)
                    )
                    if (!ResChecker.checkRes(TAG, " build 失败:", buildResp)) {
                        val errorCode = extractSportsRpcErrorCode(buildResp)
                        val errorMsg = extractSportsRpcErrorMessage(buildResp)
                        if (isNeverlandEnergyLimit(errorCode, errorMsg)) {
                            if (multiNum > 1) {
                                blockedMultis.add(multiNum)
                                leftEnergy = queryUserEnergy(source)
                                if (leftEnergy < 0) {
                                    Log.error(TAG, "健康岛[$currentMapName]刷新能量失败，停止降级重试")
                                    return
                                }
                                Log.sports("健康岛[$currentMapName]x$multiNum 能量不足，刷新能量后降级重试[balance=$leftEnergy]")
                                continue
                            }
                            Log.sports("健康岛[$currentMapName]单倍建造受限[code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]")
                            Status.setFlagToday(StatusFlags.FLAG_ANTSPORTS_NEVERLAND_ENERGY_LIMIT)
                            return
                        }
                        if (isNeverlandBuildTimesLimit(errorCode, errorMsg)) {
                            Log.sports("健康岛[$currentMapName]建造受限[code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]，停止当前岛屿建造"
                            )
                            return
                        }
                        Log.error(
                            TAG,
                            "build 失败[mapId=$mapId][branchId=$branchId][multiNum=$multiNum][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$buildResp"
                        )
                        return
                    }

                    val buildData = buildResp.optJSONObject("data")
                    if (buildData == null || buildData.length() == 0) {
                        Log.sports("build响应数据为空，刷新地图状态后继续判断")
                        GlobalThreadPools.sleepCompat(500)
                        continue
                    }

                    val stepIncrease = calculateBuildSteps(buildData, multiNum)
                    val totalSteps = recordStepIncrease(stepIncrease)
                    remainSteps = remainSteps?.minus(stepIncrease)
                    leftEnergy = (leftEnergy - oneBuildEnergy * stepIncrease).coerceAtLeast(0)

                    val awardInfo = extractAwardInfo(buildData)
                    Log.sports(
                        String.format(
                            "建造进度 🏗️ [%s] 倍数: x%d | 能量: %d | 本次: +%d | 今日: %d/%s%s",
                            currentMapName,
                            multiNum,
                            leftEnergy,
                            stepIncrease,
                            totalSteps,
                            formatNeverlandBuildLimit(),
                            awardInfo
                        )
                    )
                    if (isBuildDataMapFinished(buildData)) {
                        neverlandPickAllBubble(source)
                    }
                    GlobalThreadPools.sleepCompat(1000)
                }
                handleFinishedMapAfterBuildLimit(branchId, mapId, currentMapName, source)
                Log.sports("自动建造任务完成 ✓")
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "executeAutoBuild err", t)
            }
        }

        private fun handleFinishedMapAfterBuildLimit(
            branchId: String,
            mapId: String,
            mapName: String,
            source: String = activeNeverlandSource
        ) {
            try {
                val mapInfo = JSONObject(
                    AntSportsRpcCall.NeverlandRpcCall.queryMapInfoNew(mapId, branchId, source)
                )
                if (!ResChecker.checkRes(TAG, " 查询建造地图失败", mapInfo)) {
                    Log.error(
                        TAG,
                        "健康岛结束前查询地图失败[mapId=$mapId][branchId=$branchId][code=${extractSportsRpcErrorCode(mapInfo).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(mapInfo)}] raw=$mapInfo"
                    )
                    return
                }
                val data = mapInfo.optJSONObject("data") ?: return
                val latestMapId = data.optString("mapId", mapId).ifBlank { mapId }
                val latestBranchId = data.optString("branchId", branchId).ifBlank { branchId }
                val latestMapName = data.optString("mapName", mapName).ifBlank { mapName }
                if (!isNeverlandMapFinished(
                        data.optString("mapStatus", ""),
                        data.optInt("mapEnergyProcess", 0),
                        data.optInt("mapEnergyFinal", 0)
                    )
                ) {
                    return
                }
                Log.sports("健康岛[$latestMapName]建造已完成，结束前尝试领奖与切换岛屿")
                neverlandPickAllBubble(source)
                if (!::neverlandAutoReward.isInitialized || neverlandAutoReward.value == true) {
                    tryChooseMapReward(latestBranchId, latestMapId, source)
                } else {
                    Log.sports("健康岛自动领奖已关闭，跳过[$latestMapName]")
                }
                chooseAvailableMap(latestMapId, source)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "handleFinishedMapAfterBuildLimit err", t)
            }
        }

        private fun isNeverlandMapFinished(mapStatus: String, process: Int, final: Int): Boolean {
            return mapStatus == "FINISH_NOT_REWARD" ||
                mapStatus == "FINISH" ||
                (final > 0 && process >= final)
        }

        private fun isBuildDataMapFinished(buildData: JSONObject): Boolean {
            val end = buildData.optJSONObject("endStageInfo") ?: return false
            val final = end.optInt("buildingEnergyFinal", 0)
            val process = end.optInt("buildingEnergyProcess", 0)
            val rewardCount = buildData.optJSONArray("rewards")?.length() ?: 0
            return final > 0 && process >= final && rewardCount > 0
        }

        private fun extractBuildMultis(mapConfig: JSONObject?): List<Int> {
            val arr = mapConfig?.optJSONArray("buildMulti")
            val result = mutableListOf<Int>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val multi = arr.optInt(i, 0)
                    if (multi > 0) result.add(multi)
                }
            }
            return result.ifEmpty { listOf(1) }.distinct().sortedDescending()
        }

        private fun tryChooseMapReward(
            branchId: String,
            mapId: String,
            source: String = activeNeverlandSource
        ): Boolean {
            if (isNeverlandRewardUnavailableToday(mapId)) {
                Log.sports("健康岛[$mapId]今日已确认奖励不可领取，跳过重复领奖")
                return false
            }
            val detailResp = JSONObject(AntSportsRpcCall.NeverlandRpcCall.queryMapDetail(mapId, source))
            if (!ResChecker.checkRes(TAG, " queryMapDetail 失败:", detailResp)) {
                Log.error(
                    TAG,
                    "健康岛查询领奖详情失败[mapId=$mapId][code=${extractSportsRpcErrorCode(detailResp).ifEmpty { "UNKNOWN" }}][msg=${extractSportsRpcErrorMessage(detailResp)}] raw=$detailResp"
                )
                return false
            }
            val baseMapInfo = detailResp.optJSONObject("data")?.optJSONObject("baseMapInfo")
            val rewards = collectNeverlandRewardCandidates(baseMapInfo)
            if (rewards.isEmpty()) {
                Log.sports("健康岛[$mapId]没有可选奖励")
                return false
            }
            val orderedRewards = orderNeverlandRewards(rewards)
            var detailUnavailableCount = 0
            var rpcUnavailableCount = 0
            for (reward in orderedRewards) {
                if (isNeverlandRewardUnavailableByDetail(reward)) {
                    detailUnavailableCount++
                    Log.sports(
                        "健康岛奖励详情显示不可领取，跳过[reward=${reward.name}][rewardId=${reward.rewardId}]" +
                            "[status=${reward.status.ifEmpty { "UNKNOWN" }}][prizeStatus=${reward.prizeStatus}]"
                    )
                    continue
                }
                val chooseResp = JSONObject(
                    AntSportsRpcCall.NeverlandRpcCall.chooseReward(branchId, mapId, reward.rewardId, source)
                )
                if (isSportsRpcSuccess(chooseResp)) {
                    Log.sports("健康岛领奖成功[mapId=$mapId][reward=${reward.name}][rewardId=${reward.rewardId}]")
                    return true
                }
                val errorCode = extractSportsRpcErrorCode(chooseResp)
                val errorMsg = extractSportsRpcErrorMessage(chooseResp)
                if (isNeverlandRewardNonRetryable(errorCode, errorMsg)) {
                    rpcUnavailableCount++
                    Log.sports("健康岛奖励不可领取，尝试下一个[reward=${reward.name}][rewardId=${reward.rewardId}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                    )
                    continue
                }
                if (isNeverlandDuplicateReward(errorCode, errorMsg)) {
                    Log.sports("健康岛奖励已处理[reward=${reward.name}][rewardId=${reward.rewardId}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg]"
                    )
                    return true
                }
                Log.error(
                    TAG,
                    "健康岛领奖失败[mapId=$mapId][reward=${reward.name}][rewardId=${reward.rewardId}][code=${errorCode.ifEmpty { "UNKNOWN" }}][msg=$errorMsg] raw=$chooseResp"
                )
                return false
            }
            val unavailableCount = detailUnavailableCount + rpcUnavailableCount
            if (unavailableCount == orderedRewards.size) {
                markNeverlandRewardUnavailableToday(mapId)
                Log.sports(
                    "健康岛[$mapId]所有奖励均为明确业务不可领取，今日跳过后续领奖" +
                        "[detail=$detailUnavailableCount][rpc=$rpcUnavailableCount]"
                )
            } else {
                Log.error(TAG, "健康岛[$mapId]所有奖励均领取失败或不可领取")
            }
            return false
        }

        private fun collectNeverlandRewardCandidates(baseMapInfo: JSONObject?): List<NeverlandRewardCandidate> {
            if (baseMapInfo == null) return emptyList()
            val rewardArray = baseMapInfo.optJSONArray("rewards") ?: baseMapInfo.optJSONArray("reward") ?: return emptyList()
            val candidates = mutableListOf<NeverlandRewardCandidate>()
            for (i in 0 until rewardArray.length()) {
                val reward = rewardArray.optJSONObject(i) ?: continue
                val rewardId = reward.optString("itemId", reward.optString("rewardId", ""))
                if (rewardId.isBlank()) continue
                candidates.add(
                    NeverlandRewardCandidate(
                        rewardId = rewardId,
                        name = reward.optString("name", rewardId),
                        status = reward.opt("status")?.toString().orEmpty(),
                        prizeStatus = reward.optString("prizeStatus", "")
                    )
                )
            }
            return candidates
        }

        private fun orderNeverlandRewards(rewards: List<NeverlandRewardCandidate>): List<NeverlandRewardCandidate> {
            if (!::neverlandPreferMedal.isInitialized || neverlandPreferMedal.value == true) {
                val medal = rewards.firstOrNull { it.name.contains("奖牌") }
                if (medal != null) {
                    return listOf(medal) + rewards.filterNot { it.rewardId == medal.rewardId }
                }
            }
            return rewards
        }

        private fun isNeverlandRewardUnavailableByDetail(reward: NeverlandRewardCandidate): Boolean {
            val prizeStatus = reward.prizeStatus
            return prizeStatus.isNotBlank() && isNeverlandRewardNonRetryable("", prizeStatus)
        }

        private fun isNeverlandEnergyLimit(errorCode: String, errorMsg: String): Boolean {
            val text = "$errorCode $errorMsg"
            return text.contains("能量") ||
                text.contains("体力") ||
                text.contains("體力") ||
                text.contains("ENERGY", ignoreCase = true) ||
                text.contains("NOT_ENOUGH", ignoreCase = true)
        }

        private fun isNeverlandBuildTimesLimit(errorCode: String, errorMsg: String): Boolean {
            val text = "$errorCode $errorMsg"
            return text.contains("次数") ||
                text.contains("上限") ||
                text.contains("今日")
        }

        private fun isNeverlandRewardNonRetryable(errorCode: String, errorMsg: String): Boolean {
            val text = "$errorCode $errorMsg"
            return errorCode == "PROMO_PRIZE_NOT_RETRY_ERROR" ||
                text.contains("奖品已抢完") ||
                text.contains("不可重试") ||
                text.contains("今日已兑完") ||
                text.contains("已兑完")
        }

        private fun isNeverlandDuplicateReward(errorCode: String, errorMsg: String): Boolean {
            val text = "$errorCode $errorMsg"
            return text.contains("已领取") || text.contains("重复领取")
        }

        /**
         * @brief 计算建造实际产生的步数
         */
        private fun calculateBuildSteps(buildData: JSONObject?, defaultMulti: Int): Int {
            return try {
                val buildResults = buildData?.optJSONArray("buildResults")
                if (buildResults != null && buildResults.length() > 0) {
                    buildResults.length()
                } else {
                    defaultMulti
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
                defaultMulti
            }
        }

        /**
         * @brief 从建造数据中提取奖励信息
         */
        private fun extractAwardInfo(buildData: JSONObject?): String {
            return try {
                val awards = buildData?.optJSONArray("awards") ?: buildData?.optJSONArray("rewards")
                if (awards != null && awards.length() > 0) {
                    String.format(" | 获得奖励: %d 项", awards.length())
                } else {
                    ""
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
                ""
            }
        }
    }

    // ---------------------------------------------------------------------
    // 配置用枚举/常量
    // ---------------------------------------------------------------------

    /**
     * @brief 蚂蚁运动路线主题常量与映射表
     */
    interface WalkPathTheme {
        companion object {
            const val DA_MEI_ZHONG_GUO = 0  ///< 大美中国 (默认)
            const val GONG_YI_YI_XIAO_BU = 1  ///< 公益一小步
            const val DENG_DING_ZHI_MA_SHAN = 2  ///< 登顶芝麻山
            const val WEI_C_DA_TIAO_ZHAN = 3  ///< 维C大挑战
            const val LONG_NIAN_QI_FU = 4  ///< 龙年祈福
            const val SHOU_HU_TI_YU_MENG = 5  ///< 守护体育梦

            /** @brief 界面显示的名称列表 */
            val nickNames = arrayOf(
                "大美中国",
                "公益一小步",
                "登顶芝麻山",
                "维C大挑战",
                "龙年祈福",
                "守护体育梦"
            )

            /**
             * @brief 对应目标应用接口的 ThemeID 映射表
             * @note 数组顺序必须与上方常量定义保持严格一致
             */
            val themeIds = arrayOf(
                "M202308082226",  ///< [0] 大美中国
                "M202401042147",  ///< [1] 公益一小步
                "V202405271625",  ///< [2] 登顶芝麻山
                "202404221422",   ///< [3] 维C大挑战
                "WF202312050200", ///< [4] 龙年祈福
                "V202409061650"   ///< [5] 守护体育梦
            )
        }
    }

    /**
     * @brief 慈善捐能量模式
     */
    interface DonateCharityCoinType {
        companion object {
            const val ONE = 0
            // 保留原 ALL 选项的文案，方便以后扩充
            val nickNames = arrayOf("捐赠一个项目", "捐赠所有项目")
        }
    }

    /**
     * @brief 抢好友模式
     */
    interface BattleForFriendType {
        companion object {
            const val ROB = 0
            const val DONT_ROB = 1
            val nickNames = arrayOf("选中抢", "选中不抢")
        }
    }
}


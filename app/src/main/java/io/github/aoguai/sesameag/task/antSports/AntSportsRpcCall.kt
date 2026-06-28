package io.github.aoguai.sesameag.task.antSports

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import io.github.aoguai.sesameag.entity.RpcEntity
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.RequestManager
import java.util.UUID

/**
 * @file AntSportsRpcCall.kt
 * @brief 支付宝蚂蚁运动（AntSports）RPC 接口调用集合
 * 
 * @details
 * 本模块提供了支付宝蚂蚁运动所有功能的 RPC 接口的 Kotlin 封装版本。
 * 包括：
 * - 运动任务查询与完成
 * - 能量球（金币）收集与捐赠
 * - 行走路线管理与进度查询
 * - 运动币兑换与礼品领取
 * - 健康岛（Neverland）任务系统
 * - 抢好友大战功能
 * 
 * @author [Original Java Author]
 * @since 2025.01.20
 * @version 1.0.0
 */
object AntSportsRpcCall {

    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 常量定义
    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @brief 应用渠道信息 - 应用中心子频道9patch
     */
    private const val CH_INFO = "ch_appcenter__chsub_9patch"

    /**
     * @brief 运动首页渠道信息
     */
    private const val HOME_CH_INFO = "ch_shouquan_shouye"

    /**
     * @brief 时区信息 - 亚洲/上海
     */
    private const val TIME_ZONE = "Asia\\/Shanghai"

    /**
     * @brief 版本号 - 蚂蚁运动版本
     */
    private const val VERSION = "3.0.1.2"

    /**
     * @brief 支付宝应用版本 - 动态获取
     */
    private val ALIPAY_APP_VERSION =ApplicationHook.alipayVersion

    /**
     * @brief 城市代码 - 杭州
     */
    private const val CITY_CODE = "330100"

    /**
     * @brief 应用ID - 蚂蚁运动小程序ID
     */
    private const val APP_ID = "2021002116659397"

    /**
     * @brief 功能特性列表 - JSON 格式字符串
     * 
     * 包含运动各项功能的支持标识符，用于API请求。
     */
    private const val FEATURES = """["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_AI","SUPPORT_TAB3","SUPPORT_FLYRABBIT","SUPPORT_NEW_MATCH","EXTERNAL_ADVERTISEMENT_TASK","PROP","PROPV2","ASIAN_GAMES"]"""

    private const val QUERY_COIN_TASK_PANEL_RPC =
        "com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.queryCoinTaskPanel"
    private const val QUERY_ENERGY_BUBBLE_MODULE_RPC =
        "com.alipay.sportshealth.biz.rpc.sportsHealthHomeRpc.queryEnergyBubbleModule"
    private const val NEVERLAND_QUERY_TASK_CENTER_RPC =
        "com.alipay.neverland.biz.rpc.queryTaskCenter"
    private const val NEVERLAND_QUERY_TASK_INFO_RPC =
        "com.alipay.neverland.biz.rpc.queryTaskInfo"
    private const val NEVERLAND_QUERY_BUBBLE_TASK_RPC =
        "com.alipay.neverland.biz.rpc.queryBubbleTask"
    private const val NEVERLAND_QUERY_MAP_LIST_RPC =
        "com.alipay.neverland.biz.rpc.queryMapList"
    private const val NEVERLAND_QUERY_MAP_INFO_NEW_RPC =
        "com.alipay.neverland.biz.rpc.queryMapInfoNew"
    private const val NEVERLAND_QUERY_BASEINFO_RPC =
        "com.alipay.neverland.biz.rpc.queryBaseinfo"
    private const val NEVERLAND_QUERY_MAP_DETAIL_RPC =
        "com.alipay.neverland.biz.rpc.queryMapDetail"
    internal const val QUERY_WALK_STEP_RPC =
        "alipay.antsports.steps.query"
    private const val MOTION_QUIZ_BIZ_TYPE = "MOTION_DAILY_QUIZ"
    private const val MOTION_QUIZ_CHANNEL_RPC = "alipay.iblib.channel.data"

    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 运动任务面板接口
    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @brief 查询运动币任务面板
     * 
     * @details 获取首页运动任务列表，包括能量获取任务和活动任务。
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.queryCoinTaskPanel
     */
    fun queryCoinTaskPanel(): String {
        val args1 = """[{"canAddHome":false,"chInfo":"medical_health","clientAuthStatus":"not_support","clientOS":"android","features":$FEATURES,"topTaskId":""}]"""
        return RequestManager.requestString(QUERY_COIN_TASK_PANEL_RPC, args1)
    }

    /**
     * @brief 运动任务签到
     * 
     * @param taskId 任务ID - 特定任务的唯一标识
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.signUpTask
     */
    fun signUpTask(taskId: String): String {
        val args = """[{"apiVersion":"energy","chInfo":"medical_health","clientOS":"android","features":$FEATURES,"taskCenId":"","taskId":"$taskId"}]"""
        return RequestManager.requestString("com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.signUpTask", args)
    }

    /**
     * @brief 完成运动锻炼任务（旧版接口）
     * 
     * @param taskId 任务ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该接口为旧版，建议使用 #completeTask
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.completeTask
     */
    fun completeExerciseTasks(taskId: String): String {
        val args1 = """[{"chInfo":"ch_appcenter__chsub_9patch","clientOS":"android","features":$FEATURES,"taskAction":"JUMP","taskId":"$taskId"}]"""
        return RequestManager.requestString("com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.completeTask", args1)
    }

    /**
     * @brief 完成运动任务（新版接口）
     * 
     * @param taskId 任务ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.completeTask
     */
    fun completeTask(
        taskId: String,
        taskAction: String = "JUMP",
        taskType: String? = null,
        bizNo: String = buildSportsTaskBizNo(),
        chInfo: String = "medical_health"
    ): String {
        val args = JSONArray().apply {
            put(JSONObject().apply {
                put("apiVersion", "energy")
                put("bizNo", bizNo)
                put("chInfo", chInfo)
                put("clientOS", "android")
                put("features", JSONArray(FEATURES))
                put("taskAction", taskAction)
                put("taskId", taskId)
                if (!taskType.isNullOrBlank()) {
                    put("taskType", taskType)
                }
            })
        }
        return RequestManager.requestString("com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.completeTask", args.toString())
    }

    /**
     * @brief 完成运动任务面板中的广告浏览任务
     *
     * @param bizId queryCoinTaskPanel.taskList[].bizExtMap.bizId
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应抓包：com.alipay.adtask.biz.mobilegw.service.task.finish
     */
    fun finishAdTask(bizId: String): String {
        val params = JSONObject().apply {
            put("bizId", bizId)
        }
        return finishAdTask(params)
    }

    fun finishAdTask(params: JSONObject): String {
        val args = JSONArray().put(params)
        return RequestManager.requestString(
            "com.alipay.adtask.biz.mobilegw.service.task.finish",
            args.toString()
        )
    }

    private fun buildSportsTaskBizNo(): String {
        return "${System.currentTimeMillis()}-${UUID.randomUUID()}"
    }

    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 运动问答接口
    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun requestMotionQuizChannelData(activityCode: String, activityId: String, body: JSONObject): String {
        val args = JSONArray().put(JSONObject().apply {
            put("activityCode", activityCode)
            put("activityId", activityId)
            put("body", body)
            put("version", "2.0")
        })
        return RequestManager.requestString(MOTION_QUIZ_CHANNEL_RPC, args.toString())
    }

    fun queryMotionQuizBlockDetail(queryDate: Long): String {
        val body = JSONObject().apply {
            put("bizType", MOTION_QUIZ_BIZ_TYPE)
            put("cityCode", CITY_CODE)
            put("queryDate", queryDate)
            put("queryVersion", 2)
            put("scene", "single_day")
            put("schemeParams", JSONObject().apply {
                put("queryPrizeParams", true)
            })
        }
        return requestMotionQuizChannelData(
            "query_quiz_block_detail",
            "2023041700010001",
            body
        )
    }

    fun answerMotionQuiz(quizId: String, answer: String, gmtStartAnswer: Long): String {
        val body = JSONObject().apply {
            val now = System.currentTimeMillis()
            put("answer", answer)
            put("bizType", MOTION_QUIZ_BIZ_TYPE)
            put("gmtAnswer", now)
            put("gmtStartAnswer", gmtStartAnswer)
            put("quizId", quizId)
        }
        return requestMotionQuizChannelData(
            "answer_quiz",
            "2023041700030001",
            body
        )
    }

    fun queryMotionQuizAward(quizId: String): String {
        val body = JSONObject().apply {
            put("bizType", MOTION_QUIZ_BIZ_TYPE)
            put("quizId", quizId)
        }
        return requestMotionQuizChannelData(
            "answer_quiz_award",
            "2025081410521001",
            body
        )
    }

    fun clickMotionQuizReceiveSort(quizId: String, creativityId: String): String {
        val body = JSONObject().apply {
            put("bizType", MOTION_QUIZ_BIZ_TYPE)
            put("creativityId", creativityId)
            put("quizId", quizId)
        }
        return requestMotionQuizChannelData(
            "click_receive_sort",
            "2025102200108020",
            body
        )
    }

    /**
     * @brief 完成运动首页气泡任务
     *
     * @param taskId 首页气泡任务ID
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应抓包：运动首页 recBubbleList 中的 task.taskId
     */
    fun completeHomeBubbleTask(taskId: String): String {
        val args1 = """[{"apiVersion":"energy","chInfo":"$HOME_CH_INFO","clientOS":"android","features":$FEATURES,"taskAction":"JUMP","taskId":"$taskId"}]"""
        return RequestManager.requestString("com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.completeTask", args1)
    }

    /**
     * @brief 查询运动主页信息
     * 
     * @details 获取运动首页的数据，包括个人信息、排行榜等。
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.queryMainPage
     */
    fun queryMainPage(): String {
        val args = """{"apiVersion":"energy","chInfo":"ch_shouquan_shouye","cityCode":"$CITY_CODE","clientOS":"android","features":$FEATURES,"timezone":"Asia/Shanghai"}"""
        return RequestManager.requestString("com.alipay.sportshealth.biz.rpc.queryMainPage", args)
    }

    /**
     * @brief 运动健康签到/查询接口
     * 
     * @param operatorType 操作类型
     *   - "signIn" - 执行签到
     *   - "query" - 查询签到状态
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.signInCoinTask
     */
    fun signInCoinTask(operatorType: String): String {
        val args1 = """[{"apiVersion":"energy","chInfo":"medical_health","clientOS":"android","features":$FEATURES,"operatorType":"$operatorType"}]"""
        return RequestManager.requestString("com.alipay.sportshealth.biz.rpc.SportsHealthCoinTaskRpc.signInCoinTask", args1)
    }

    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 能量球模块接口
    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @brief 查询能量球泡泡模块
     * 
     * @details 获取首页可领取的能量球列表。
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.sportsHealthHomeRpc.queryCoinBubbleModule
     */
    fun queryCoinBubbleModule(): String {
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.sportsHealthHomeRpc.queryCoinBubbleModule",
            """[{"bubbleId":"","canAddHome":false,"chInfo":"$CH_INFO","clientAuthStatus":"not_support","clientOS":"android","distributionChannel":"","features":["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_AI","SUPPORT_TAB3","SUPPORT_FLYRABBIT","PROP","PROPV2","ASIAN_GAMES"]}]"""
        )
    }

    /**
     * @brief 领取能量球任务能量（无指定方式，默认不抢好友能量球）
     * 
     * @param medEnergyBallInfoRecordId 能量球记录ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.neverland.biz.rpc.pickBubbleTaskEnergy
     */
    fun pickBubbleTaskEnergy(medEnergyBallInfoRecordId: String): String {
        return pickBubbleTaskEnergy(medEnergyBallInfoRecordId, false)
    }

    /**
     * @brief 领取能量球任务能量（可指定是否抢好友能量球）
     * 
     * @param medEnergyBallInfoRecordId 能量球记录ID
     * @param pickAllEnergyBall 是否领取所有能量球（包括好友的）
     *   - true - 领取所有能量球，包括好友的
     *   - false - 仅领取自己的
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.neverland.biz.rpc.pickBubbleTaskEnergy
     */
    fun pickBubbleTaskEnergy(medEnergyBallInfoRecordId: String, pickAllEnergyBall: Boolean): String {
        val args1 = """[{"apiVersion":"energy","chInfo":"medical_health","clientOS":"android","features":$FEATURES,"medEnergyBallInfoRecordIds":["$medEnergyBallInfoRecordId"],"pickAllEnergyBall":$pickAllEnergyBall,"source":"SPORT"}]"""
        return RequestManager.requestString("com.alipay.neverland.biz.rpc.pickBubbleTaskEnergy", args1)
    }

    /**
     * @brief 查询能量球模块 - 推荐列表
     * 
     * @details 获取首页能量球推荐列表，包含广告和活动任务。
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.sportsHealthHomeRpc.queryEnergyBubbleModule
     */
    fun queryEnergyBubbleModule(): String {
        val args1 = """[{"apiVersion":"MERGE_SERVICE","bubbleId":"","canAddHome":false,"chInfo":"$HOME_CH_INFO","clientAuthStatus":"not_support","clientOS":"android","distributionChannel":"","features":$FEATURES,"outBizNo":""}]"""
        return RequestManager.requestString(QUERY_ENERGY_BUBBLE_MODULE_RPC, args1)
    }

    /**
     * @brief 拾取能量球（无参数版本）
     * 
     * @details 领取所有待领取的能量球，传递空数组。
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.neverland.biz.rpc.pickBubbleTaskEnergy
     */
    fun pickBubbleTaskEnergy(): String {
        val args1 = """[{"apiVersion":"energy","chInfo":"$HOME_CH_INFO","clientOS":"android","features":$FEATURES,"medEnergyBallInfoRecordIds":[],"pickAllEnergyBall":true,"source":"SPORT"}]"""
        return RequestManager.requestString("com.alipay.neverland.biz.rpc.pickBubbleTaskEnergy", args1)
    }

    /**
     * @brief 收集金币资产
     * 
     * @param assetId 资产ID - 金币资产唯一标识
     * @param coinAmount 金币数量 - 收集的金币数量
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthCoinCenterRpc.receiveCoinAsset
     */
    fun receiveCoinAsset(assetId: String, coinAmount: Int): String {
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.SportsHealthCoinCenterRpc.receiveCoinAsset",
            """[{"assetId":"$assetId","chInfo":"$CH_INFO","clientOS":"android","coinAmount":$coinAmount,"features":["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_TAB3","SUPPORT_FLYRABBIT","PROP","PROPV2","ASIAN_GAMES"],"tracertPos":"首页金币收集"}]"""
        )
    }

    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 运动币兑换模块
    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @brief 查询礼品详情
     * 
     * @param itemId 礼品ID - 要查询的礼品唯一标识
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthItemCenterRpc.queryItemDetail
     */
    fun queryItemDetail(itemId: String): String {
        val arg = """[{"itemId":"$itemId"}]"""
        return RequestManager.requestString("com.alipay.sportshealth.biz.rpc.SportsHealthItemCenterRpc.queryItemDetail", arg)
    }

    /**
     * @brief 兑换礼品
     * 
     * @param itemId 礼品ID
     * @param coinAmount 消耗运动币数量
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthItemCenterRpc.exchangeItem
     */
    fun exchangeItem(itemId: String, coinAmount: Int): String {
        val arg = """[{"coinAmount":$coinAmount,"itemId":"$itemId"}]"""
        return RequestManager.requestString("com.alipay.sportshealth.biz.rpc.SportsHealthItemCenterRpc.exchangeItem", arg)
    }

    /**
     * @brief 查询兑换记录详情
     * 
     * @param exchangeRecordId 兑换记录ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthItemCenterRpc.queryExchangeRecordPage
     */
    fun queryExchangeRecordPage(exchangeRecordId: String): String {
        val arg = """[{"exchangeRecordId":"$exchangeRecordId"}]"""
        return RequestManager.requestString("com.alipay.sportshealth.biz.rpc.SportsHealthItemCenterRpc.queryExchangeRecordPage", arg)
    }

    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 旧版行走路线模块（已过时，保留兼容性）
    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @brief 查询个人首页 - 旧版运动路线
     * 
     * @details 获取用户在旧版蚂蚁行走中的个人主页信息。
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口，新版应使用新API
     * @remark 对应API：alipay.antsports.walk.map.queryMyHomePage
     */
    fun queryMyHomePage(): String {
        return RequestManager.requestString(
            "alipay.antsports.walk.map.queryMyHomePage",
            """[{"alipayAppVersion":"$ALIPAY_APP_VERSION","chInfo":"$CH_INFO","clientOS":"android","features":["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_TAB3","SUPPORT_FLYRABBIT","PROP","PROPV2","ASIAN_GAMES"],"pathListUsePage":true,"timeZone":"$TIME_ZONE"}]"""
        )
    }

    /**
     * @brief 加入旧版运动路线
     * 
     * @param pathId 路线ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口
     * @remark 对应API：alipay.antsports.walk.map.join
     */
    fun join(pathId: String): String {
        return RequestManager.requestString(
            "alipay.antsports.walk.map.join",
            """[{"chInfo":"$CH_INFO","clientOS":"android","features":["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_TAB3","SUPPORT_FLYRABBIT","PROP","PROPV2","ASIAN_GAMES"],"pathId":"$pathId"}]"""
        )
    }

    /**
     * @brief 首次开启并加入旧版运动路线
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口
     * @remark 对应API：alipay.antsports.walk.user.openAndJoinFirst
     */
    fun openAndJoinFirst(): String {
        return RequestManager.requestString(
            "alipay.antsports.walk.user.openAndJoinFirst",
            """[{"chInfo":"$CH_INFO","clientOS":"android","features":["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_TAB3","SUPPORT_FLYRABBIT","PROP","PROPV2","ASIAN_GAMES"]}]"""
        )
    }

    /**
     * @brief 行走旧版运动路线
     * 
     * @param day 日期字符串，格式如 "yyyy-MM-dd"
     * @param rankCacheKey 排行榜缓存键
     * @param stepCount 使用的步数
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口
     * @remark 对应API：alipay.antsports.walk.map.go
     */
    fun go(day: String, rankCacheKey: String, stepCount: Int): String {
        return RequestManager.requestString(
            "alipay.antsports.walk.map.go",
            """[{"chInfo":"$CH_INFO","clientOS":"android","day":"$day","features":["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_TAB3","SUPPORT_FLYRABBIT","PROP","PROPV2","ASIAN_GAMES"],"needAllBox":true,"rankCacheKey":"$rankCacheKey","timeZone":"$TIME_ZONE","useStepCount":$stepCount}]"""
        )
    }

    /**
     * @brief 开启旧版运动宝箱
     * 
     * @param boxNo 宝箱编号
     * @param userId 用户ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口
     * @remark 对应API：alipay.antsports.walk.treasureBox.openTreasureBox
     */
    fun openTreasureBox(boxNo: String, userId: String): String {
        return RequestManager.requestString(
            "alipay.antsports.walk.treasureBox.openTreasureBox",
            """[{"boxNo":"$boxNo","chInfo":"$CH_INFO","clientOS":"android","features":["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_TAB3","SUPPORT_FLYRABBIT","PROP","PROPV2","ASIAN_GAMES"],"userId":"$userId"}]"""
        )
    }

    /**
     * @brief 查询路线基础列表
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口
     * @remark 对应API：alipay.antsports.walk.path.queryBaseList
     */
    fun queryBaseList(): String {
        return RequestManager.requestString(
            "alipay.antsports.walk.path.queryBaseList",
            """[{"chInfo":"$CH_INFO","clientOS":"android","features":["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_TAB3","SUPPORT_FLYRABBIT","PROP","PROPV2","ASIAN_GAMES"]}]"""
        )
    }

    /**
     * @brief 查询项目列表
     * 
     * @param index 页码索引，从0开始
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口
     * @remark 对应API：alipay.antsports.walk.charity.queryProjectList
     */
    fun queryProjectList(index: Int): String {
        return RequestManager.requestString(
            "alipay.antsports.walk.charity.queryProjectList",
            """[{"chInfo":"$CH_INFO","clientOS":"android","features":["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_TAB3","SUPPORT_FLYRABBIT","PROP","PROPV2","ASIAN_GAMES"],"index":$index,"projectListUseVertical":true}]"""
        )
    }

    /**
     * @brief 捐赠慈善能量
     * 
     * @param donateCharityCoin 捐赠慈善能量数量
     * @param projectId 项目ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口
     * @remark 对应API：alipay.antsports.walk.charity.donate
     */
    fun donate(donateCharityCoin: Int, projectId: String): String {
        return RequestManager.requestString(
            "alipay.antsports.walk.charity.donate",
            """[{"chInfo":"$CH_INFO","clientOS":"android","donateCharityCoin":$donateCharityCoin,"features":["DAILY_STEPS_RANK_V2","STEP_BATTLE","CLUB_HOME_CARD","NEW_HOME_PAGE_STATIC","CLOUD_SDK_AUTH","STAY_ON_COMPLETE","EXTRA_TREASURE_BOX","NEW_HOME_PAGE_STATIC","SUPPORT_TAB3","SUPPORT_FLYRABBIT","PROP","PROPV2","ASIAN_GAMES"],"projectId":"$projectId"}]"""
        )
    }

    /**
     * @brief 查询当前可用步数
     *
     * @details
     * 2026-03 抓包显示捐步查询已切换为 `alipay.antsports.steps.query`，
     * 返回结构以顶层 `stepCount` 为主；旧版 `dailyStepModel.produceQuantity`
     * 仍保留兼容解析，见 [extractWalkStepCount]。
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：alipay.antsports.steps.query
     */
    fun queryWalkStep(): String {
        return RequestManager.requestString(
            QUERY_WALK_STEP_RPC,
            """[{"appId":"healthstep","bizId":"donation","chInfo":"h5_donation_healthstep","timeZone":"$TIME_ZONE"}]"""
        )
    }

    /**
     * @brief 从捐步查询响应中提取可用步数
     *
     * @param response `queryWalkStep()` 返回的 JSON 对象
     * @return 可用步数，解析失败时返回 0
     */
    fun extractWalkStepCount(response: JSONObject?): Int {
        if (response == null) {
            return 0
        }

        if (response.has("stepCount")) {
            return response.optInt("stepCount", 0).coerceAtLeast(0)
        }

        val dailyStepModel = response.optJSONObject("dailyStepModel")
        if (dailyStepModel != null) {
            if (dailyStepModel.has("produceQuantity")) {
                return dailyStepModel.optInt("produceQuantity", 0).coerceAtLeast(0)
            }
            if (dailyStepModel.has("stepCount")) {
                return dailyStepModel.optInt("stepCount", 0).coerceAtLeast(0)
            }
        }

        return 0
    }

    /**
     * @brief 行走捐赠签到信息
     * 
     * @param count 步数
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口
     * @remark 对应API：alipay.charity.mobile.donate.walk.walkDonateSignInfo
     */
    fun walkDonateSignInfo(count: Int): String {
        return RequestManager.requestString(
            "alipay.charity.mobile.donate.walk.walkDonateSignInfo",
            """[{"needDonateAction":false,"source":"walkDonateHome","steps":$count,"timezoneId":"$TIME_ZONE"}]"""
        )
    }

    /**
     * @brief 行走捐赠首页
     * 
     * @param count 步数
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口
     * @remark 对应API：alipay.charity.mobile.donate.walk.home
     */
    fun donateWalkHome(count: Int): String {
        return RequestManager.requestString(
            "alipay.charity.mobile.donate.walk.home",
            """[{"module":"3","steps":$count,"timezoneId":"$TIME_ZONE"}]"""
        )
    }

    /**
     * @brief 兑换捐赠步数
     * 
     * @param actId 活动ID
     * @param count 步数
     * @param donateToken 捐赠令牌
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该为旧版接口
     * @remark 对应API：alipay.charity.mobile.donate.walk.exchange
     */
    fun exchange(actId: String, count: Int, donateToken: String): String {
        return RequestManager.requestString(
            "alipay.charity.mobile.donate.walk.exchange",
            """[{"actId":"$actId","count":$count,"donateToken":"$donateToken","timezoneId":"$TIME_ZONE","ver":0}]"""
        )
    }

    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 新版行走路线模块（推荐使用）
    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @brief 查询用户信息 - 新版
     * 
     * @details 获取新版蚂蚁运动中用户的基本信息。
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportsplay.biz.rpc.walk.queryUser
     */

    fun queryUser(): String {
        // 补齐 mainPage 和 timeZone，直接一行搞定
        val data = """[{"apiVersion":"energy","chInfo":"ch_othertinyapp","clientOS":"android","features":$FEATURES,"mainPage":true,"timeZone":"$TIME_ZONE"}]"""

        return RequestManager.requestString("com.alipay.sportsplay.biz.rpc.walk.queryUser", data)
    }

    /**
     * @brief 查询主题列表 - 新版
     * 
     * @details 获取可用的运动路线主题列表。
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportsplay.biz.rpc.walk.theme.queryThemeList
     */
    fun queryThemeList(): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.theme.queryThemeList",
            """[{"apiVersion":"energy","chInfo":"ch_othertinyapp","clientOS":"android","features":$FEATURES}]"""
        )
    }

    /**
     * @brief 查询世界地图 - 新版
     * 
     * @param themeId 主题ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportsplay.biz.rpc.walk.queryWorldMap
     */
    fun queryWorldMap(themeId: String): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.queryWorldMap",
            """[{"apiVersion":"energy","chInfo":"ch_othertinyapp","clientOS":"android","features":$FEATURES,"themeId":"$themeId"}]"""
        )
    }

    /**
     * @brief 查询城市路线 - 新版
     * 
     * @param cityId 城市ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportsplay.biz.rpc.walk.queryCityPath
     */
    fun queryCityPath(cityId: String): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.queryCityPath",
            """[{"apiVersion":"energy","chInfo":"ch_othertinyapp","cityId":"$cityId","clientOS":"android","features":$FEATURES}]"""
        )
    }

    /**
     * @brief 查询城市见闻领取汇总
     *
     * @remark 对应抓包：com.alipay.sportsplay.biz.rpc.walk.queryCityKnowledgeSummary
     */
    fun queryCityKnowledgeSummary(): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.queryCityKnowledgeSummary",
            """[{"apiVersion":"energy","source":"ch_othertinyapp"}]"""
        )
    }

    /**
     * @brief 查询城市见闻勋章详情
     *
     * @remark 对应抓包：com.alipay.sportsplay.biz.rpc.walk.queryMedalDetail
     */
    fun queryMedalDetail(): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.queryMedalDetail",
            """[{"chInfo":"medical_health","clientOS":"android","features":$FEATURES}]"""
        )
    }

    /**
     * @brief 查询服务端推荐的下一批路线
     *
     * @param pathId 当前路线 ID
     *
     * @remark 对应抓包：com.alipay.sportsplay.biz.rpc.walk.queryRecommendPathList
     */
    fun queryRecommendPathList(pathId: String): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.queryRecommendPathList",
            """[{"apiVersion":"energy","pathId":"$pathId","source":"ch_othertinyapp"}]"""
        )
    }

    /**
     * @brief 查询路线详情 - 新版
     * 
     * @param date 日期字符串，格式如 "yyyy-MM-dd"
     * @param pathId 路线ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportsplay.biz.rpc.walk.queryPath
     */
    fun queryPath(date: String, pathId: String): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.queryPath",
            """[{"apiVersion":"energy","chInfo":"ch_othertinyapp","clientOS":"android","date":"$date","enableNewVersion":true,"features":$FEATURES,"pathId":"$pathId","timeZone":"$TIME_ZONE"}]"""
        )
    }

    /**
     * @brief 加入运动路线 - 新版
     * 
     * @param pathId 路线ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportsplay.biz.rpc.walk.joinPath
     */
    fun joinPath(pathId: String): String {
        val requestBody = """[{"apiVersion":"energy","chInfo":"ch_othertinyapp","clientOS":"android","features":$FEATURES,"pathId":"$pathId"}]"""
        return RequestManager.requestString("com.alipay.sportsplay.biz.rpc.walk.joinPath", requestBody)
    }

    /**
     * @brief 行走运动路线 - 新版
     * 
     * @param date 日期字符串，格式如 "yyyy-MM-dd"
     * @param pathId 路线ID
     * @param useStepCount 使用的步数
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportsplay.biz.rpc.walk.go
     */
    fun walkGo(date: String, pathId: String, useStepCount: Int): String {
        val requestBody = """[{"apiVersion":"energy","chInfo":"ch_othertinyapp","clientOS":"android","date":"$date","features":$FEATURES,"pathId":"$pathId","source":"ch_othertinyapp","timeZone":"$TIME_ZONE","useStepCount":$useStepCount}]"""
        return RequestManager.requestString("com.alipay.sportsplay.biz.rpc.walk.go", requestBody)
    }

    /**
     * @brief 领取路线事件奖励（如宝箱）- 新版
     * 
     * @param eventBillNo 事件账单号（宝箱号）
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportsplay.biz.rpc.walk.receiveEvent
     */
    fun receiveEvent(eventBillNo: String): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.receiveEvent",
            """[{"eventBillNo":"$eventBillNo"}]"""
        )
    }

    /**
     * @brief 查询路线复活任务完成状态
     */
    fun queryReviveTaskFinishStatus(): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.steprevive.queryTaskFinishStatus",
            "[{}]"
        )
    }

    /**
     * @brief 查询可复活步数明细
     */
    fun queryUserReviveStepT2(): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.steprevive.queryUserReviveStepT2",
            """[{"timeZone":"$TIME_ZONE"}]"""
        )
    }

    /**
     * @brief 查询路线复活任务列表
     */
    fun queryReviveTaskList(): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.steprevive.queryTaskList",
            "[{}]"
        )
    }

    /**
     * @brief 触发路线复活任务
     */
    fun triggerReviveTask(taskId: String, taskBizId: String? = null): String {
        val obj = JSONObject().apply {
            put("taskId", taskId)
            if (!taskBizId.isNullOrBlank()) {
                put("taskBizId", taskBizId)
            }
        }
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.steprevive.triggerTask",
            JSONArray().put(obj).toString()
        )
    }

    /**
     * @brief 完成路线复活任务
     */
    fun completeReviveTask(taskId: String): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.steprevive.completeTask",
            """[{"taskId":"$taskId"}]"""
        )
    }

    /**
     * @brief 复活当前路线可复活步数
     */
    fun reviveSteps(): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.steprevive.reviveSteps",
            """[{"chInfo":"ch_othertinyapp","clientOS":"android","features":$FEATURES,"timeZone":"$TIME_ZONE"}]"""
        )
    }

    /**
     * @brief 按指定日期复活步数
     */
    fun reviveSteps(stepDate: String): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.steprevive.reviveSteps",
            """[{"stepDate":"$stepDate","timeZone":"$TIME_ZONE"}]"""
        )
    }

    /**
     * @brief 查询路线奖励 - 新版
     * 
     * @param appId 应用ID
     * @param pathId 路线ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：com.alipay.sportsplay.biz.rpc.walk.queryPathReward
     */
    fun queryPathReward(appId: String, pathId: String): String {
        return RequestManager.requestString(
            "com.alipay.sportsplay.biz.rpc.walk.queryPathReward",
            """[{"appId":"$appId","pathId":"$pathId","source":"ch_appcenter__chsub_9patch"}]"""
        )
    }

    /**
     * @brief 兑换成功回调 - 旧版（已过时）
     * 
     * @param exchangeId 兑换ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @deprecated 该接口已过时
     * @remark 对应API：alipay.charity.mobile.donate.exchange.success
     */
    fun exchangeSuccess(exchangeId: String): String {
        val args1 = """[{"exchangeId":"$exchangeId","timezone":"GMT+08:00","version":"$VERSION"}]"""
        return RequestManager.requestString("alipay.charity.mobile.donate.exchange.success", args1)
    }

    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 文体中心模块
    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @brief 查询用户任务组
     * 
     * @param groupId 任务组ID
     *   - "SPORTS_DAILY_SIGN_GROUP" - 日常签到组
     *   - "SPORTS_DAILY_GROUP" - 日常任务组
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.tiyubiz.sports.userTaskGroup.query
     */
    fun userTaskGroupQuery(groupId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.sports.userTaskGroup.query",
            """[{"cityCode":"$CITY_CODE","groupId":"$groupId"}]"""
        )
    }

    /**
     * @brief 完成用户任务
     * 
     * @param bizType 业务类型
     * @param taskId 任务ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.tiyubiz.sports.userTask.complete
     */
    fun userTaskComplete(bizType: String, taskId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.sports.userTask.complete",
            """[{"bizType":"$bizType","cityCode":"$CITY_CODE","completedTime":${System.currentTimeMillis()},"taskId":"$taskId"}]"""
        )
    }

    /**
     * @brief 领取用户任务权益
     * 
     * @param taskId 任务ID
     * @param userTaskId 用户任务ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.tiyubiz.sports.userTaskRights.receive
     */
    fun userTaskRightsReceive(taskId: String, userTaskId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.sports.userTaskRights.receive",
            """[{"taskId":"$taskId","userTaskId":"$userTaskId"}]"""
        )
    }

    /**
     * @brief 查询已报名线上赛列表
     *
     * @param statusList 报名状态，抓包中进行中比赛为 "JOIN"
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：alipay.tiyubiz.userOnlineGame.listquery
     */
    fun userOnlineGameListQuery(statusList: String = "JOIN"): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.userOnlineGame.listquery",
            """[{"chInfo":"medical_health","clientOS":"android","features":$FEATURES,"index":1,"isInProgress":true,"size":10,"statusList":"$statusList"}]"""
        )
    }

    /**
     * @brief 查询线上赛列表
     *
     * @param bizType 线上赛分组，如 RECOMMEND_GAME、NEW_ONLINE_GAME、STEP_GAME
     * @param notInWufu 是否带上抓包中的 notinwufu 标记
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：alipay.tiyubiz.onlineGame.sports.listquery
     */
    fun onlineGameSportsListQuery(
        bizType: String,
        index: Int = 1,
        size: Int = 10,
        notInWufu: Boolean = false
    ): String {
        val notInWufuField = if (notInWufu) ",\"notinwufu\":\"true\"" else ""
        return RequestManager.requestString(
            "alipay.tiyubiz.onlineGame.sports.listquery",
            """[{"bizType":"$bizType","chInfo":"medical_health","clientOS":"android","features":$FEATURES,"index":$index$notInWufuField,"size":$size}]"""
        )
    }

    /**
     * @brief 查询线上赛目标档位
     *
     * @param gameId 比赛 ID
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：alipay.tiyubiz.onlineGame.eventQuery
     */
    fun onlineGameEventQuery(gameId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.onlineGame.eventQuery",
            """[{"chInfo":"medical_health","clientOS":"android","features":$FEATURES,"gameId":"$gameId"}]"""
        )
    }

    /**
     * @brief 报名线上赛
     *
     * @param gameId 比赛 ID
     * @param gameEventId 目标档位 ID
     * @param rightsPackageId 权益包 ID
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：alipay.tiyubiz.userOnlineGame.signup
     */
    fun userOnlineGameSignup(gameId: String, gameEventId: String, rightsPackageId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.userOnlineGame.signup",
            """[{"chInfo":"medical_health","clientOS":"android","features":$FEATURES,"gameEventId":"$gameEventId","gameId":"$gameId","rightsPackageId":"$rightsPackageId"}]"""
        )
    }

    /**
     * @brief 查询已报名线上赛详情
     *
     * @param gameId 比赛 ID
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：alipay.tiyubiz.userOnlineGame.detailQuery.forwenti
     */
    fun userOnlineGameDetailQuery(gameId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.userOnlineGame.detailQuery.forwenti",
            """[{"chInfo":"medical_health","clientOS":"android","features":$FEATURES,"gameId":"$gameId"}]"""
        )
    }

    /**
     * @brief 查询线上赛运动数据列表
     *
     * @param gameId 比赛 ID
     * @param userGameId 用户参赛 ID
     * @param sync 是否先同步服务端记录
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：alipay.tiyubiz.userOnlineGame.dataQuery
     */
    fun userOnlineGameDataQuery(
        gameId: String,
        userGameId: String,
        sync: Boolean = true,
        pageIndex: Int = 1,
        pageSize: Int = 3
    ): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.userOnlineGame.dataQuery",
            """[{"chInfo":"medical_health","clientOS":"android","features":$FEATURES,"gameId":"$gameId","pageIndex":$pageIndex,"pageSize":$pageSize,"sync":$sync,"userGameId":"$userGameId"}]"""
        )
    }

    /**
     * @brief 查询运动工具配置
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.querySportsToolConfig
     */
    fun querySportsToolConfig(): String {
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.querySportsToolConfig",
            """[{"chInfo":"medical_health","clientOS":"android","features":$FEATURES}]"""
        )
    }

    /**
     * @brief 查询运动工具页面
     *
     * @param outBizCode 线上赛 gameId
     * @param sportsType 运动类型，如 walk/run
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.querySportsToolPage
     */
    fun querySportsToolPage(outBizCode: String, sportsType: String): String {
        val args = JSONArray().put(JSONObject().apply {
            put("chInfo", "medical_health")
            put("clientOS", "android")
            put("extraInfo", JSONObject().put("bizDesc", ""))
            put("features", JSONArray(FEATURES))
            put("fromAppId", "sports_online_game")
            put("outBizCode", outBizCode)
            put("sportsType", sportsType)
        })
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.querySportsToolPage",
            args.toString()
        )
    }

    /**
     * @brief 查询用户进行中的运动记录
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.queryUserMovingRecord
     */
    fun queryUserMovingRecord(): String {
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.queryUserMovingRecord",
            """[{"chInfo":"medical_health","clientOS":"android","features":$FEATURES}]"""
        )
    }

    /**
     * @brief 查询运动工具音频配置
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.queryAudioConfig
     */
    fun queryAudioConfig(): String {
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.queryAudioConfig",
            """[{"chInfo":"medical_health","clientOS":"android","features":$FEATURES,"supportPlayBgm":true}]"""
        )
    }

    /**
     * @brief 同步运动工具传感器授权状态
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.sportsHealthHomeRpc.syncDeviceAuthInfo
     */
    fun syncSportsDeviceAuthInfo(): String {
        val authInfo = JSONObject().apply {
            put("authCanOpen", false)
            put("authOpen", true)
            put("authState", "authed")
            put("authType", "sensor")
            put("dataSource", "")
            put("queryError", "NO_ERROR_CODE")
        }
        val args = JSONArray().put(JSONObject().apply {
            put("authInfos", JSONArray().put(authInfo))
            put("chInfo", "default")
            put("clientOS", "android")
        })
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.sportsHealthHomeRpc.syncDeviceAuthInfo",
            args.toString()
        )
    }

    /**
     * @brief 开始运动工具记录
     *
     * @param outBizCode 线上赛 gameId
     * @param sportsType 运动类型，如 walk/run
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.startSports
     */
    fun startSports(outBizCode: String, sportsType: String): String {
        val args = JSONArray().put(JSONObject().apply {
            put("chInfo", "medical_health")
            put("clientOS", "android")
            put("extraInfo", JSONObject().apply {
                put("bizDesc", "")
                put("planId", "")
            })
            put("features", JSONArray(FEATURES))
            put("fromAppId", "sports_online_game")
            put("outBizCode", outBizCode)
            put("sportsType", sportsType)
        })
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.startSports",
            args.toString()
        )
    }

    /**
     * @brief 结束运动工具记录
     *
     * @param record 运动记录体，对应抓包 requestData[].record
     * @param isNormalFinish 是否正常结束
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.finishSports
     */
    fun finishSports(record: JSONObject, isNormalFinish: Boolean = true): String {
        val args = JSONArray().put(JSONObject().apply {
            put("chInfo", "medical_health")
            put("clientOS", "android")
            put("features", JSONArray(FEATURES))
            put("isAutoStop", false)
            put("isNormalFinish", isNormalFinish)
            put("record", record)
        })
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.finishSports",
            args.toString()
        )
    }

    /**
     * @brief 查询运动工具记录详情
     *
     * @param outBizCode 线上赛 gameId
     * @param recordId 运动记录 ID
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.querySportsRecordDetail
     */
    fun querySportsRecordDetail(outBizCode: String, recordId: String): String {
        val args = JSONArray().put(JSONObject().apply {
            put("chInfo", "medical_health")
            put("clientOS", "android")
            put("features", JSONArray(FEATURES))
            put("fromAppId", "sports_online_game")
            put("outBizCode", outBizCode)
            put("recordId", recordId)
            put("source", "SPORTS_TOOL")
        })
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.querySportsRecordDetail",
            args.toString()
        )
    }

    /**
     * @brief 同步运动工具结束粒子统计
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应API：com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.finishSyncParticles
     */
    fun finishSyncParticles(
        recordId: String,
        sportsType: String,
        stepCount: Int,
        distance: Double,
        durationMillis: Long,
        startTime: Long,
        endTime: Long,
        index: Int
    ): String {
        val particle = JSONObject().apply {
            put("accelStep", stepCount)
            put("distance", 0)
            put("duration", 0)
            put("endTime", endTime)
            put("gpsScore", 0)
            put("identifier", recordId)
            put("invalidStatistics", JSONObject().apply {
                put("last", 0)
                put("accuracy", 6)
                put("speedOrAcc", 3)
            }.toString())
            put("lbsStatistics", JSONObject().apply {
                put("gps", "80")
                put("wifi", "1")
            }.toString())
            put("sportDistance", distance)
            put("sportDuration", durationMillis)
            put("startTime", startTime)
            put("status", "stop")
            put("step", stepCount)
            put("type", sportsType)
        }
        val args = JSONArray().put(JSONObject().apply {
            put("chInfo", "medical_health")
            put("clientOS", "android")
            put("features", JSONArray(FEATURES))
            put("index", index)
            put("particleDetailInfos", JSONArray().put(particle))
            put("recordId", recordId)
        })
        return RequestManager.requestString(
            "com.alipay.sportshealth.biz.rpc.SportsHealthToolRpc.finishSyncParticles",
            args.toString()
        )
    }

    /**
     * @brief 查询路线功能特性
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.tiyubiz.path.feature.query
     */
    fun pathFeatureQuery(): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.path.feature.query",
            """[{"appId":"$APP_ID","features":["USER_CURRENT_PATH_SIMPLE"],"sceneCode":"wenti_shijiebei"}]"""
        )
    }

    /**
     * @brief 加入路线地图
     * 
     * @param pathId 路线ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.tiyubiz.path.map.join
     */
    fun pathMapJoin(pathId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.path.map.join",
            """[{"appId":"$APP_ID","pathId":"$pathId"}]"""
        )
    }

    /**
     * @brief 查询路线地图首页
     * 
     * @param pathId 路线ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.tiyubiz.path.map.homepage
     */
    fun pathMapHomepage(pathId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.path.map.homepage",
            """[{"appId":"$APP_ID","pathId":"$pathId"}]"""
        )
    }

    /**
     * @brief 查询步数
     * 
     * @param countDate 统计日期
     * @param pathId 路线ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.tiyubiz.path.map.step.query
     */
    fun stepQuery(countDate: String, pathId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.path.map.step.query",
            """[{"appId":"$APP_ID","countDate":"$countDate","pathId":"$pathId","timeZone":"$TIME_ZONE"}]"""
        )
    }

    /**
     * @brief 行走路线（文体中心版本）
     * 
     * @param countDate 统计日期
     * @param goStepCount 行走步数
     * @param pathId 路线ID
     * @param userPathRecordId 用户路线记录ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.tiyubiz.path.map.go
     */
    fun tiyubizGo(countDate: String, goStepCount: Int, pathId: String, userPathRecordId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.path.map.go",
            """[{"appId":"$APP_ID","countDate":"$countDate","goStepCount":$goStepCount,"pathId":"$pathId","timeZone":"$TIME_ZONE","userPathRecordId":"$userPathRecordId"}]"""
        )
    }

    /**
     * @brief 领取路线奖励（文体中心版本）
     * 
     * @param pathId 路线ID
     * @param userPathRewardId 用户路线奖励ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.tiyubiz.path.map.reward.receive
     */
    fun rewardReceive(pathId: String, userPathRewardId: String): String {
        return RequestManager.requestString(
            "alipay.tiyubiz.path.map.reward.receive",
            """[{"appId":"$APP_ID","pathId":"$pathId","userPathRewardId":"$userPathRewardId"}]"""
        )
    }

    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 抢好友大战模块
    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @brief 查询抢好友俱乐部首页
     * 
     * @details 获取抢好友大战中的俱乐部首页数据。
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.antsports.club.home.queryClubHome
     */
    fun queryClubHome(): String {
        val args = """[{"apiVersion":"energy","chInfo":"healthstep","timeZone":"$TIME_ZONE"}]"""
        return RequestManager.requestString("alipay.antsports.club.home.queryClubHome", args)
    }

    /**
     * @brief 查询训练项目
     * 
     * @details 获取可用的训练项目列表。
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.antsports.club.train.queryTrainItem
     */
    fun queryTrainItem(): String {
        val args = """[{"apiVersion":"energy","chInfo":"healthstep"}]"""
        return RequestManager.requestString("alipay.antsports.club.train.queryTrainItem", args)
    }

    /**
     * @brief 训练好友
     * 
     * @param bizId 业务ID
     * @param itemType 训练项目类型（如 "skate"）
     * @param memberId 成员ID
     * @param originBossId 原老板ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.antsports.club.train.trainMember
     */
    fun trainMember(bizId: String, itemType: String, memberId: String, originBossId: String): String {
        val args = """[{"apiVersion":"energy","bizId":"$bizId","chInfo":"healthstep","itemType":"$itemType","memberId":"$memberId","originBossId":"$originBossId"}]"""
        return RequestManager.requestString("alipay.antsports.club.train.trainMember", args)
    }

    /**
     * @brief 查询成员价格排行
     * 
     * @param coinBalance 当前能量余额
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.antsports.club.ranking.queryMemberPriceRanking
     */
    fun queryMemberPriceRanking(coinBalance: Int): String {
        val args = """[{"apiVersion":"energy","buyMember":true,"chInfo":"healthstep","coinBalance":$coinBalance}]"""
        return RequestManager.requestString("alipay.antsports.club.ranking.queryMemberPriceRanking", args)
    }

    /**
     * @brief 查询俱乐部成员详情
     * 
     * @param memberId 成员ID
     * @param originBossId 原老板ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.antsports.club.trade.queryClubMember
     */
    fun queryClubMember(memberId: String, originBossId: String): String {
        val args = """[{"apiVersion":"energy","chInfo":"healthstep","memberId":"$memberId","originBossId":"$originBossId"}]"""
        return RequestManager.requestString("alipay.antsports.club.trade.queryClubMember", args)
    }

    /**
     * @brief 抢购好友
     * 
     * @param currentBossId 当前老板ID
     * @param memberId 成员ID
     * @param originBossId 原老板ID
     * @param priceInfo 价格信息（JSON字符串）
     * @param roomId 房间ID
     * 
     * @return RPC调用结果的 JSON 字符串
     * 
     * @remark 对应API：alipay.antsports.club.trade.buyMember
     */
    fun buyMember(currentBossId: String, memberId: String, originBossId: String, priceInfo: String, roomId: String): String {
        val requestData = """[{"apiVersion":"energy","chInfo":"healthstep","currentBossId":"$currentBossId","memberId":"$memberId","originBossId":"$originBossId","priceInfo":$priceInfo,"roomId":"$roomId"}]"""
        return RequestManager.requestString("alipay.antsports.club.trade.buyMember", requestData)
    }

    /**
     * @brief 解锁下一个抢好友房间
     *
     * @param price 抓包中请求 price 使用当前能量余额，服务端自行计算实际解锁价格
     *
     * @return RPC调用结果的 JSON 字符串
     *
     * @remark 对应抓包：alipay.antsports.club.trade.unlockNextRoom
     */
    fun unlockNextRoom(price: Int): String {
        val args = """[{"apiVersion":"energy","chInfo":"healthstep","price":$price}]"""
        return RequestManager.requestString("alipay.antsports.club.trade.unlockNextRoom", args)
    }

    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 健康岛（Neverland）内部类
    //━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @brief 健康岛（Neverland）RPC 接口集合
     * 
     * @details 专门用于健康岛任务系统的 RPC 接口组合。
     * 包括签到、任务、泡泡、建造、走路等功能。
     */
    object NeverlandRpcCall {
        private const val DEFAULT_SOURCE = "jkdsportcard"
        private const val QUICK_GAME_CITY_CODE = "440100"

        /**
         * @brief 查询签到状态
         * 
         * @param signType 签到类型（健康岛固定为 3）
         * @param source 来源标识（当前运动首页抓包稳定使用 "ch_toufang__yundongshouye"）
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.querySign
         */
        fun querySign(signType: Int, source: String): String {
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.querySign",
                """[{"signType":$signType,"source":"$source"}]"""
            )
        }

        /**
         * @brief 执行签到
         * 
         * @param signType 签到类型（健康岛固定为 3）
         * @param source 来源标识（当前运动首页抓包稳定使用 "ch_toufang__yundongshouye"）
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.takeSign
         */
        fun takeSign(signType: Int, source: String): String {
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.takeSign",
                """[{"signType":$signType,"source":"$source"}]"""
            )
        }

        /**
         * @brief 查询泡泡任务列表
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.queryBubbleTask
         */
        fun queryBubbleTask(source: String = DEFAULT_SOURCE): String {
            return RequestManager.requestString(
                NEVERLAND_QUERY_BUBBLE_TASK_RPC,
                """[{"source":"$source","sportsAuthed":true}]"""
            )
        }

        fun queryExchangeCondition(source: String): String {
            val args = JSONArray().put(JSONObject().apply {
                put("assetType", "RED_PACKAGE_PIECE")
                put("source", source)
            }).toString()
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.queryExchangeCondition",
                args
            )
        }

        fun autoRecommendItem(source: String, needEnergyValue: String = "1"): String {
            val args = JSONArray().put(JSONObject().apply {
                put("extInfo", JSONObject().put("needEnergyValue", needEnergyValue))
                put("filterItemSalePrice", true)
                put("filterItemStatus", true)
                put("source", source)
            }).toString()
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.autoRecommendItem",
                args
            )
        }

        private fun sportsItemMallFeatures(): JSONArray {
            return JSONArray().apply {
                listOf(
                    "DAILY_STEPS_RANK_V2",
                    "STEP_BATTLE",
                    "CLUB_HOME_CARD",
                    "NEW_HOME_PAGE_STATIC",
                    "CLOUD_SDK_AUTH",
                    "STAY_ON_COMPLETE",
                    "EXTRA_TREASURE_BOX",
                    "SUPPORT_AI",
                    "SUPPORT_TAB3",
                    "SUPPORT_FLYRABBIT",
                    "SUPPORT_NEW_MATCH",
                    "EXTERNAL_ADVERTISEMENT_TASK",
                    "PROP",
                    "PROPV2",
                    "ASIAN_GAMES"
                ).forEach { put(it) }
            }
        }

        fun queryCoinCenterPage(): String {
            val args = JSONArray().put(JSONObject().apply {
                put("canAddHome", false)
                put("chInfo", "xzyd1031")
                put("clientAuthStatus", "not_support")
                put("clientOS", "android")
                put("features", sportsItemMallFeatures())
                put("topTaskId", "")
            }).toString()
            return RequestManager.requestString(
                "com.alipay.sportshealth.biz.rpc.SportsHealthCoinCenterRpc.queryCoinCenterPage",
                args
            )
        }

        fun deliverSportsItemMallPage(pageCode: String = "@alipay/alipaysports/property"): String {
            val args = JSONArray().put(JSONObject().apply {
                put("extInfo", JSONObject())
                put("pageCode", pageCode)
            }).toString()
            return RequestManager.requestString(
                "com.alipay.promofrontcenter.deliver.deliverByPageId",
                args
            )
        }

        fun queryItemCategoryList(): String {
            val args = JSONArray().put(JSONObject().apply {
                put("chInfo", "xzyd1031")
                put("clientOS", "android")
                put("features", sportsItemMallFeatures())
                put("sceneId", "SPORT_ITEM_MALL")
                put("source", "SPORT")
            }).toString()
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.queryItemCategoryList",
                args
            )
        }

        fun queryItemList(
            categoryType: String = "",
            pageNum: Int = 1,
            pageSize: Int = 10,
            cityCode: String = "440100",
            adSession: String = ""
        ): String {
            val args = JSONArray().put(JSONObject().apply {
                put("adSession", adSession)
                put("categoryType", categoryType)
                put("chInfo", "xzyd1031")
                put("cityCode", cityCode)
                put("clientOS", "android")
                put("features", sportsItemMallFeatures())
                put("pageNum", pageNum)
                put("pageSize", pageSize)
                put("recommendItemIdList", JSONArray())
                put("sceneId", "SPORT_ITEM_MALL")
                put("source", "SPORT")
            }).toString()
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.queryItemList",
                args
            )
        }

        fun queryItemDetail(
            benefitId: String,
            itemId: String,
            materialType: String,
            cityCode: String = "440100",
            apDid: String = ""
        ): String {
            val args = JSONArray().put(JSONObject().apply {
                if (apDid.isNotBlank()) {
                    put("apDid", apDid)
                }
                put("benefitId", benefitId)
                put("cityCode", cityCode)
                put("itemId", itemId)
                put("materialType", materialType)
            }).toString()
            return RequestManager.requestString(RpcEntity(
                requestMethod = "com.alipay.neverland.biz.rpc.queryItemDetail",
                requestData = args,
                appName = "neverland",
                methodName = "queryItemDetail",
                facadeName = "NeverlandBenefitCenterRpc"
            ))
        }

        fun createOrder(
            benefitId: String,
            itemId: String,
            cityCode: String = "440100",
            apDid: String = ""
        ): String {
            val args = JSONArray().put(JSONObject().apply {
                if (apDid.isNotBlank()) {
                    put("apDid", apDid)
                }
                put("benefitId", benefitId)
                put("chInfo", "xzyd1031")
                put("cityCode", cityCode)
                put("itemId", itemId)
                put("source", "SPORT")
            }).toString()
            return RequestManager.requestString(RpcEntity(
                requestMethod = "com.alipay.neverland.biz.rpc.createOrder",
                requestData = args,
                appName = "neverland",
                methodName = "createOrder",
                facadeName = "NeverlandBenefitCenterRpc"
            ))
        }

        fun collectExchangeData(cityCode: String = "440100", apDid: String = ""): String {
            val args = JSONArray().put(JSONObject().apply {
                if (apDid.isNotBlank()) {
                    put("apDid", apDid)
                }
                put("cityCode", cityCode)
                put("collectType", "LOG_REPORT")
                put("scene", "BENEFIT_CENTER")
                put("subScene", "exchange")
            }).toString()
            return RequestManager.requestString(RpcEntity(
                requestMethod = "com.alipay.neverland.biz.rpc.collectData",
                requestData = args,
                appName = "neverland",
                methodName = "collectData",
                facadeName = "NeverlandDataCollectRpc"
            ))
        }

        /**
         * @brief 领取泡泡能量
         *
         * @param ids 泡泡记录ID列表
         *
         * @return RPC调用结果的 JSON 字符串
         *
         * @remark 对应API：com.alipay.neverland.biz.rpc.pickBubbleTaskEnergy
         */
        fun pickBubbleTaskEnergy(
            ids: List<String>,
            source: String = DEFAULT_SOURCE,
            pickAllEnergyBall: Boolean = true
        ): String {
            val obj = JSONObject().apply {
                put("medEnergyBallInfoRecordIds", JSONArray().apply {
                    ids.forEach { put(it) }
                })
                put("pickAllEnergyBall", pickAllEnergyBall)
                put("source", source)
            }
            val arr = JSONArray().put(obj)
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.pickBubbleTaskEnergy",
                arr.toString()
            )
        }

        /**
         * @brief 查询健康岛快捷游戏入口
         *
         * @remark 对应抓包：com.alipay.neverland.biz.rpc.queryQuickGameList
         */
        fun queryQuickGameList(source: String = DEFAULT_SOURCE): String {
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.queryQuickGameList",
                """[{"chInfo":"$source","cityCode":"$QUICK_GAME_CITY_CODE","source":"$source"}]"""
            )
        }

        /**
         * @brief 查询任务中心
         *
         * @details 获取健康岛任务大厅的任务列表，请求体按当前运动首页抓包同步。
         *
         * @param source 来源标识
         * @param cityCode 城市编码
         * @param apDid 可选设备标识；当前模块内没有可靠运行时来源时不传固定值
         *
         * @return RPC调用结果的 JSON 字符串
         *
         * @remark 对应API：com.alipay.neverland.biz.rpc.queryTaskCenter
         */
        fun queryTaskCenter(
            source: String,
            cityCode: String = QUICK_GAME_CITY_CODE,
            apDid: String = ""
        ): String {
            val args = JSONArray().put(JSONObject().apply {
                if (apDid.isNotBlank()) {
                    put("apDid", apDid)
                }
                put("cityCode", cityCode)
                put("deviceLevel", "high")
                put("newGame", 1)
                put("source", source)
            }).toString()
            return RequestManager.requestString(
                NEVERLAND_QUERY_TASK_CENTER_RPC,
                args
            )
        }

        /**
         * @brief 查询指定类型的任务信息
         * 
         * @param source 来源标识（如 "health-island"）
         * @param type 任务类型（如 "LIGHT_FEEDS_TASK"）
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.queryTaskInfo
         */
        fun queryTaskInfo(source: String, type: String): String {
            return RequestManager.requestString(
                NEVERLAND_QUERY_TASK_INFO_RPC,
                """[{"source":"$source","type":"$type"}]"""
            )
        }

        /**
         * @brief 领取能量任务奖励
         *
         * @param encryptValue 任务加密值
         * @param energyNum 能量数量
         * @param type 任务类型
         * @param lightTaskId 轻任务ID（可选）
         *
         * @return RPC调用结果的 JSON 字符串
         *
         * @remark 对应API：com.alipay.neverland.biz.rpc.energyReceive
         */
        fun energyReceive(
            encryptValue: String,
            energyNum: Int,
            type: String,
            lightTaskId: String?,
            source: String = DEFAULT_SOURCE
        ): String {
            val obj = JSONObject().apply {
                put("encryptValue", encryptValue)
                put("energyNum", energyNum.toString())
                put("source", source)
                put("type", type)
                if (!lightTaskId.isNullOrEmpty()) {
                    put("lightTaskId", lightTaskId)
                }
            }
            val arr = JSONArray().put(obj)
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.energyReceive",
                arr.toString()
            )
        }

        /**
         * @brief 提交任务
         * 
         * @param taskObj 任务对象（JSONObject）
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.taskSend
         */
        fun taskSend(taskObj: JSONObject): String {
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.taskSend",
                """[${taskObj}]"""
            )
        }

        /**
         * @brief 领取任务
         * 
         * @param taskObj 任务对象（JSONObject）
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.taskReceive
         */
        fun taskReceive(taskObj: JSONObject): String {
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.taskReceive",
                """[${taskObj}]"""
            )
        }

        /**
         * @brief 完成广告任务
         * 
         * @param bizId 业务ID
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.adtask.biz.mobilegw.service.task.finish
         */
        fun finish(bizId: String): String {
            return RequestManager.requestString(
                "com.alipay.adtask.biz.mobilegw.service.task.finish",
                """[{"bizId":"$bizId"}]"""
            )
        }

        /**
         * @brief 查询地图列表
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.queryMapList
         */
        fun queryMapList(source: String = DEFAULT_SOURCE): String {
            return RequestManager.requestString(
                NEVERLAND_QUERY_MAP_LIST_RPC,
                """[{"source":"$source"}]"""
            )
        }

        /**
         * @brief 查询地图信息（旧版）
         * 
         * @param mapId 地图ID
         * @param branchId 分支ID
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @throws JSONException JSON解析异常
         * @remark 对应API：com.alipay.neverland.biz.rpc.queryMapInfo
         */
        @Throws(JSONException::class)
        fun queryMapInfo(mapId: String, branchId: String, source: String = DEFAULT_SOURCE): String {
            val obj = JSONObject()
            obj.put("branchId", branchId)
            obj.put("drilling", false)
            obj.put("mapId", mapId)
            obj.put("source", source)

            val arr = JSONArray()
            arr.put(obj)
            return RequestManager.requestString("com.alipay.neverland.biz.rpc.queryMapInfo", arr.toString())
        }

        /**
         * @brief 查询地图信息（新版）
         * 
         * @param mapId 地图ID
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.queryMapInfoNew
         */
        fun queryMapInfoNew(
            mapId: String,
            branchId: String = "MASTER",
            source: String = DEFAULT_SOURCE
        ): String {
            return RequestManager.requestString(
                NEVERLAND_QUERY_MAP_INFO_NEW_RPC,
                """[{"branchId":"$branchId","mapId":"$mapId","source":"$source"}]"""
            )
        }

        /**
         * @brief 查询基础信息
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.queryBaseinfo
         */
        fun queryBaseinfo(source: String = DEFAULT_SOURCE): String {
            return RequestManager.requestString(
                NEVERLAND_QUERY_BASEINFO_RPC,
                """[{"source":"$source"}]"""
            )
        }

        fun queryBaseinfo(
            branchId: String,
            mapId: String,
            fromMapId: String?,
            source: String = DEFAULT_SOURCE
        ): String {
            val obj = JSONObject().apply {
                put("branchId", branchId)
                if (!fromMapId.isNullOrBlank()) {
                    put("fromMapId", fromMapId)
                }
                put("mapId", mapId)
                put("source", source)
            }
            return RequestManager.requestString(
                NEVERLAND_QUERY_BASEINFO_RPC,
                JSONArray().put(obj).toString()
            )
        }

        /**
         * @brief 建造建筑
         * 
         * @param branchId 分支ID
         * @param mapId 地图ID
         * @param multiNum 建造倍数（1-10）
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.build
         */
        fun build(branchId: String, mapId: String, multiNum: Int, source: String = DEFAULT_SOURCE): String {
            val result = RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.build",
                """[{"branchId":"$branchId","mapId":"$mapId","multiNum":$multiNum,"source":"$source"}]"""
            )
            return result
        }

        /**
         * @brief 建造建筑（新版参数顺序，兼容旧 build(branchId, mapId, multiNum)）
         */
        fun build(
            mapId: String,
            multiNum: Int,
            branchId: String = "MASTER",
            source: String = DEFAULT_SOURCE
        ): String {
            return build(branchId, mapId, multiNum, source)
        }

        /**
         * @brief 查询地图详情
         * 
         * @param mapId 地图ID
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.queryMapDetail
         */
        fun queryMapDetail(mapId: String, source: String = DEFAULT_SOURCE): String {
            return RequestManager.requestString(
                NEVERLAND_QUERY_MAP_DETAIL_RPC,
                """[{"mapId":"$mapId","source":"$source"}]"""
            )
        }

        /**
         * @brief 领取地图关卡奖励
         * 
         * @param branchId 分支ID
         * @param level 关卡等级
         * @param mapId 地图ID
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.mapStageReward
         */
        fun mapStageReward(branchId: String, level: Int, mapId: String, source: String = DEFAULT_SOURCE): String {
            val result = RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.mapStageReward",
                """[{"branchId":"$branchId","level":$level,"mapId":"$mapId","source":"$source"}]"""
            )
            return result
        }

        /**
         * @brief 选择奖励
         * 
         * @param branchId 分支ID
         * @param mapId 地图ID
         * @param rewardId 奖励ID
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.mapChooseReward
         */
        fun chooseReward(
            branchId: String,
            mapId: String,
            rewardId: String,
            source: String = DEFAULT_SOURCE
        ): String {
            val result = RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.mapChooseReward",
                """[{"branchId":"$branchId","channel":"$source","mapId":"$mapId","rewardId":"$rewardId","source":"$source"}]"""
            )
            return result
        }

        /**
         * @brief 选择地图
         * 
         * @param branchId 分支ID
         * @param mapId 地图ID
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.mapChooseFree
         */
        fun chooseMap(branchId: String, mapId: String, source: String = DEFAULT_SOURCE): String {
            val result = RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.mapChooseFree",
                """[{"branchId":"$branchId","mapId":"$mapId","source":"$source"}]"""
            )
            return result
        }

        /**
         * @brief 行走地格
         * 
         * @param branchId 分支ID
         * @param mapId 地图ID
         * @param drilling 是否钻探（通常为 false）
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.walkGrid
         */
        fun walkGrid(branchId: String, mapId: String, drilling: Boolean, source: String = DEFAULT_SOURCE): String {
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.walkGrid",
                """[{"branchId":"$branchId","mapId":"$mapId","drilling":$drilling,"source":"$source"}]"""
            )
        }

        /**
         * @brief 查询用户能量
         * 
         * @return RPC调用结果的 JSON 字符串
         * 
         * @remark 对应API：com.alipay.neverland.biz.rpc.queryUserAccount
         */
        fun queryUserEnergy(source: String = DEFAULT_SOURCE): String {
            return RequestManager.requestString(
                "com.alipay.neverland.biz.rpc.queryUserAccount",
                """[{"source":"$source"}]"""
            )
        }
    }
}


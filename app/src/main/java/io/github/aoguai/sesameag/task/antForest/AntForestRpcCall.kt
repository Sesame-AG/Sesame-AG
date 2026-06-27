package io.github.aoguai.sesameag.task.antForest

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import io.github.aoguai.sesameag.entity.AlipayVersion
import io.github.aoguai.sesameag.entity.RpcEntity
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.RandomUtil
import java.util.UUID

/**
 * 森林 RPC 调用类
 */
object AntForestRpcCall {
    private const val DEFAULT_SOURCE = "chInfo_ch_appcenter__chsub_9patch"
    private const val HOME_TASK_SOURCE = "chInfo_ch_appid-60000002"
    private const val FOREST_GAME_CENTER_SOURCE = "chInfo_ch_appid-60000002"
    private const val FOREST_LEYUAN_DAILY_AWARD_SOURCE = "ch_appid-60000002"
    private const val FOREST_LEYUAN_DAILY_TASK_SCENE_CODE = "ANTFOREST_LEYUAN_DAILY_TASK"
    private const val PROTECT_BUBBLE_SOURCE = HOME_TASK_SOURCE
    private const val PROTECT_BUBBLE_VERSION = "20230501"
    private const val PATROL_SOURCE = "huolizhirenwu_xunhu"
    private const val PATROL_TIMEZONE = "Asia/Shanghai"
    private const val VITALITY_PROP_SOURCE = "vitality"
    private const val VITALITY_PROP_VERSION = "20250813"
    private const val ONE_CLICK_WATERING_SCENE_CODE = "ONE_CLICK_WATERING_V1"
    private const val ONE_CLICK_WATERING_VERSION = "20230501"
    private const val ENERGY_RAIN_SOURCE = "forest"
    internal const val ENERGY_RAIN_GAME_ENTRY_SOURCE = "senlinguangchuangrukou"
    private const val ENERGY_RAIN_VERSION = "20230501"
    private const val PROP_LIST_VERSION = "20250108"
    private const val WHACK_MOLE_VERSION = "20230824"
    const val OPEN_GREEN_RIGHTS_SOURCE = HOME_TASK_SOURCE
    internal const val BACK_FROM_ENERGY_RAIN_SOURCE = "backFromEnergyRain"
    private var VERSION = "20250818"
    private var HOME_PAGE_VERSION = "20250818"
    private var TASK_LIST_VERSION = "20250821"
    private var TASK_LIST_EXT_VERSION = "20260109"
    private var TAKE_LOOK_VERSION = "20260107"
    private const val COLLECT_ENERGY_VERSION = "20250326"
    private const val TAKE_LOOK_COMBINE_BIZ_VERSION = "20250108"

    private enum class ForestRpcScene {
        HOME_TASK_LIST,
        TAKE_LOOK_END_TASK_LIST,
        PATROL,
        VITALITY_PROP_CONSUME,
        ONE_CLICK_WATERING
    }

    private data class ForestRpcSceneContext(
        val source: String,
        val version: String? = null,
        val extend: JSONObject? = null,
        val headers: Map<String, String>? = null
    )

    data class ForestGameCenterRecentAppRecord(
        val appId: String,
        val visitTime: Long
    )

    internal data class PropConsumeContext(
        val source: String,
        val version: String,
        val headers: Map<String, String>? = forestHeaders(source),
        val propGroup: String? = null
    )

    @JvmStatic
    fun init() {
        val alipayVersion = io.github.aoguai.sesameag.hook.ApplicationHook.alipayVersion
        Log.forest("当前支付宝版本: $alipayVersion")
        try {
            when (alipayVersion.versionString) {
                "10.8.20.8000" -> {
                    VERSION = "20250818"
                    HOME_PAGE_VERSION = "20250818"
                    TASK_LIST_VERSION = "20250821"
                    TASK_LIST_EXT_VERSION = "20260109"
                    TAKE_LOOK_VERSION = "20260107"
                }

                "10.7.30.8000" -> {
                    VERSION = "20250813"
                    HOME_PAGE_VERSION = "20250813"
                    TASK_LIST_VERSION = "20250813"
                    TASK_LIST_EXT_VERSION = "20250813"
                    TAKE_LOOK_VERSION = "20250813"
                }

                "10.5.88.8000" -> {
                    VERSION = "20240403"
                    HOME_PAGE_VERSION = "20240403"
                    TASK_LIST_VERSION = "20240403"
                    TASK_LIST_EXT_VERSION = "20240403"
                    TAKE_LOOK_VERSION = "20240403"
                }

                "10.3.96.8100" -> {
                    VERSION = "20230501"
                    HOME_PAGE_VERSION = "20230501"
                    TASK_LIST_VERSION = "20230501"
                    TASK_LIST_EXT_VERSION = "20230501"
                    TAKE_LOOK_VERSION = "20230501"
                }

                else -> {
                    VERSION = "20250818"
                    HOME_PAGE_VERSION = "20250818"
                    TASK_LIST_VERSION = "20250821"
                    TASK_LIST_EXT_VERSION = "20260109"
                    TAKE_LOOK_VERSION = "20260107"
                }
            }
            Log.forest("使用API版本: $VERSION, 首页版本: $HOME_PAGE_VERSION, 任务版本: $TASK_LIST_VERSION"
            )
        } catch (e: Exception) {
            Log.error("AntForestRpcCall", "版本初始化异常，使用默认版本: $VERSION")
            Log.printStackTrace(e)
        }
    }

    private fun createTaskListExtend(extra: JSONObject? = null): JSONObject {
        val extend = JSONObject().apply {
            put("osType", "android")
            put("version", TASK_LIST_EXT_VERSION)
        }
        if (extra != null) {
            val keys = extra.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                extend.put(key, extra.opt(key))
            }
        }
        return extend
    }

    private fun forestHeaders(source: String): Map<String, String> {
        return mapOf("source" to source, "ags-source" to source)
    }

    private fun currentNativeVersion(): String {
        return ApplicationHook.alipayVersion.versionString
    }

    private fun resolveSceneContext(scene: ForestRpcScene, sourceOverride: String? = null): ForestRpcSceneContext {
        return when (scene) {
            ForestRpcScene.HOME_TASK_LIST -> ForestRpcSceneContext(
                source = HOME_TASK_SOURCE,
                version = TASK_LIST_VERSION,
                extend = createTaskListExtend(
                    JSONObject().apply {
                        put("appMode", "normal")
                        put("nativeVersion", currentNativeVersion())
                    }
                ),
                headers = forestHeaders(HOME_TASK_SOURCE)
            )

            ForestRpcScene.TAKE_LOOK_END_TASK_LIST -> {
                val actualSource = sourceOverride?.takeIf { it.isNotBlank() } ?: DEFAULT_SOURCE
                val extend = if (actualSource == BACK_FROM_ENERGY_RAIN_SOURCE) {
                    createTaskListExtend(
                        JSONObject().apply {
                            put("appMode", "normal")
                            put("nativeVersion", currentNativeVersion())
                        }
                    )
                } else {
                    createTaskListExtend()
                }
                ForestRpcSceneContext(
                    source = actualSource,
                    version = TASK_LIST_VERSION,
                    extend = extend,
                    headers = forestHeaders(actualSource)
                )
            }

            ForestRpcScene.PATROL -> ForestRpcSceneContext(
                source = PATROL_SOURCE,
                headers = forestHeaders(PATROL_SOURCE)
            )

            ForestRpcScene.VITALITY_PROP_CONSUME -> ForestRpcSceneContext(
                source = VITALITY_PROP_SOURCE,
                version = VITALITY_PROP_VERSION,
                headers = forestHeaders(VITALITY_PROP_SOURCE)
            )

            ForestRpcScene.ONE_CLICK_WATERING -> ForestRpcSceneContext(
                source = HOME_TASK_SOURCE,
                version = ONE_CLICK_WATERING_VERSION,
                headers = forestHeaders(HOME_TASK_SOURCE)
            )
        }
    }

    private fun buildPatrolPayload(block: JSONObject.() -> Unit = {}): JSONObject {
        return JSONObject().apply {
            put("source", PATROL_SOURCE)
            put("timezoneId", PATROL_TIMEZONE)
            block()
        }
    }

    private fun requestPatrol(method: String, payload: JSONObject): String {
        val context = resolveSceneContext(ForestRpcScene.PATROL)
        return RequestManager.requestString(
            RpcEntity(
                method,
                JSONArray().put(payload).toString(),
                headers = context.headers
            )
        )
    }

    private fun queryTaskListRequest(
        fromAct: String,
        source: String,
        extend: JSONObject,
        version: String,
        headers: Map<String, String>? = forestHeaders(source)
    ): String {
        val jo = JSONObject().apply {
            put("extend", extend)
            put("fromAct", fromAct)
            put("source", source)
            put("version", version)
        }
        return RequestManager.requestString(
            RpcEntity(
                "alipay.antforest.forest.h5.queryTaskList",
                JSONArray().put(jo).toString(),
                headers = headers
            )
        )
    }

    internal fun patrolPropConsumeContext(propGroup: String = ""): PropConsumeContext {
        val sceneContext = resolveSceneContext(ForestRpcScene.PATROL)
        return PropConsumeContext(
            source = sceneContext.source,
            version = VERSION,
            headers = sceneContext.headers,
            propGroup = propGroup.takeIf { it.isNotBlank() }
        )
    }

    internal fun vitalityEnergyRainPropConsumeContext(): PropConsumeContext {
        val sceneContext = resolveSceneContext(ForestRpcScene.VITALITY_PROP_CONSUME)
        return PropConsumeContext(
            source = sceneContext.source,
            version = sceneContext.version ?: VITALITY_PROP_VERSION,
            headers = sceneContext.headers,
            propGroup = "energyRain"
        )
    }

    private fun buildForestGameCenterHeaders(source: String = HOME_TASK_SOURCE): Map<String, String> {
        return forestHeaders(source)
    }

    private fun requestForestGameCenter(
        method: String,
        requestData: JSONObject,
        headerSource: String = HOME_TASK_SOURCE
    ): String {
        return RequestManager.requestString(
            RpcEntity(
                method,
                JSONArray().put(requestData).toString(),
                headers = buildForestGameCenterHeaders(headerSource)
            )
        )
    }

    private fun buildForestGameCenterFilter(appMode: Boolean = false): JSONObject {
        return JSONObject().apply {
            if (appMode) {
                put("appMode", "normal")
            }
            put("deviceLevel", "high")
            put("platform", "Android")
            put("unityDeviceLevel", "high")
        }
    }

    private fun JSONObject.putRecentAppRecordList(records: List<ForestGameCenterRecentAppRecord>) {
        val recentRecords = records
            .filter { it.appId.isNotBlank() && it.visitTime > 0 }
            .distinctBy { it.appId }
        if (recentRecords.isEmpty()) {
            return
        }
        put(
            "recentAppRecordList",
            JSONArray().apply {
                recentRecords.forEach { record ->
                    put(
                        JSONObject().apply {
                            put("appId", record.appId)
                            put("visitTime", record.visitTime)
                        }
                    )
                }
            }
        )
    }

    /**
     * 森林乐园 - 查询游戏中心列表（包含宝箱开箱权益信息）
     *
     */
    @JvmStatic
    fun queryGameList(
        recentAppRecords: List<ForestGameCenterRecentAppRecord> = emptyList()
    ): String {
        return try {
            val arg = JSONObject().apply {
                put("bizType", "ANTFOREST")
                put("commonDegradeFilterRequest", buildForestGameCenterFilter())
                putRecentAppRecordList(recentAppRecords)
                put("requestType", "RPC")
                put("sceneCode", "ANTFOREST")
                put("source", FOREST_GAME_CENTER_SOURCE)
                put("version", currentNativeVersion())
            }
            requestForestGameCenter("com.alipay.charitygamecenter.queryGameList", arg, FOREST_GAME_CENTER_SOURCE)
        } catch (e: Exception) {
            Log.printStackTrace("AntForestRpcCall", "queryGameList 构建请求参数失败", e)
            ""
        }
    }

    @JvmStatic
    fun queryOptionalPlay(
        recentAppRecords: List<ForestGameCenterRecentAppRecord> = emptyList(),
        source: String = FOREST_GAME_CENTER_SOURCE
    ): String {
        return try {
            val arg = JSONObject().apply {
                put("bizType", "ANTFOREST")
                put("commonDegradeFilterRequest", buildForestGameCenterFilter(appMode = true))
                put("playTypeList", JSONArray().put("TASK_TRIGGER").put("TOP_UP_COUPON"))
                putRecentAppRecordList(recentAppRecords)
                put("requestType", "RPC")
                put("sceneCode", "ANTFOREST_COMMON")
                put("source", source)
                put("version", currentNativeVersion())
            }
            requestForestGameCenter("com.alipay.charitygamecenter.queryOptionalPlay", arg, source)
        } catch (e: Exception) {
            Log.printStackTrace("AntForestRpcCall", "queryOptionalPlay 构建请求参数失败", e)
            ""
        }
    }

    @JvmStatic
    fun queryGameInfo(source: String = HOME_TASK_SOURCE): String {
        return try {
            val arg = JSONObject().apply {
                put("bizType", "ANTFOREST")
                put("commonDegradeFilterRequest", buildForestGameCenterFilter())
                put("requestType", "RPC")
                put("sceneCode", "ANTFOREST_RECENT_PLAY")
                put("source", source)
                put("version", currentNativeVersion())
            }
            requestForestGameCenter("com.alipay.charitygamecenter.queryGameInfo", arg, source)
        } catch (e: Exception) {
            Log.printStackTrace("AntForestRpcCall", "queryGameInfo 构建请求参数失败", e)
            ""
        }
    }

    @JvmStatic
    fun queryPreloadGame(source: String = HOME_TASK_SOURCE): String {
        return try {
            val arg = JSONObject().apply {
                put("bizType", "ANTFOREST")
                put(
                    "commonDegradeFilterRequest",
                    JSONObject().apply {
                        put("deviceLevel", "high")
                        put("productVersion", currentNativeVersion())
                        put("systemType", "Android")
                        put("unityDeviceLevel", "high")
                    }
                )
                put("requestType", "RPC")
                put("sceneCode", "find_energy_interframe")
                put("source", source)
                put("version", currentNativeVersion())
            }
            requestForestGameCenter("com.alipay.charitygamecenter.queryPreloadGame", arg, source)
        } catch (e: Exception) {
            Log.printStackTrace("AntForestRpcCall", "queryPreloadGame 构建请求参数失败", e)
            ""
        }
    }

    /**
     * 森林乐园 - 批量开宝箱
     *
     * @param batchDrawCount 批量领取次数（通常 1~10）
     */
    @JvmStatic
    fun drawGameCenterAward(batchDrawCount: Int): String {
        return try {
            val count = batchDrawCount.coerceAtLeast(1)
            val arg = JSONObject().apply {
                put("batchDrawCount", count)
                put("bizType", "ANTFOREST")
                put("requestType", "RPC")
                put("sceneCode", "ANTFOREST")
                put("source", FOREST_GAME_CENTER_SOURCE)
                put("version", currentNativeVersion())
            }
            requestForestGameCenter("com.alipay.charitygamecenter.drawGameCenterAward", arg, FOREST_GAME_CENTER_SOURCE)
        } catch (e: Exception) {
            Log.printStackTrace("AntForestRpcCall", "drawGameCenterAward 构建请求参数失败", e)
            ""
        }
    }

    @JvmStatic
    fun flowHubEntrance(flowEntranceId: String): String {
        return try {
            val arg = JSONObject().apply {
                put("bizType", "ANTFOREST")
                put("flowEntranceId", flowEntranceId)
                put("source", "ANTFOREST")
            }
            RequestManager.requestString(
                "com.alipay.antpwgrowth.flowHubEntrance",
                JSONArray().put(arg).toString()
            )
        } catch (e: Exception) {
            Log.printStackTrace("AntForestRpcCall", "flowHubEntrance 构建请求参数失败", e)
            ""
        }
    }

    @JvmStatic
    fun queryFriendsEnergyRanking(): String {
        return try {
            val arg = JSONObject().apply {
                put("source", "chInfo_ch_appcenter__chsub_9patch")
                put("periodType", "total")
                put("rankType", "energyRank")
                put("version", VERSION)
            }
            val correlationLocal = JSONObject().apply {
                put("pathList", JSONArray().put("friendRanking").put("myself").put("totalDatas"))
            }
            RequestManager.requestString(
                "alipay.antmember.forest.h5.queryEnergyRanking",
                "[$arg]",
                "[$correlationLocal]"
            )
        } catch (e: Exception) {
            ""
        }
    }

    @JvmStatic
    fun queryTopEnergyChallengeRanking(): String {
        return try {
            val arg = JSONObject().apply {
                put("source", "chInfo_ch_appcenter__chsub_9patch")
            }
            RequestManager.requestString("alipay.antforest.forest.h5.queryTopEnergyChallengeRanking", "[$arg]")
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    @JvmStatic
    fun queryPvpHomeInfo(queryWaitToReceive: Boolean = true, source: String = DEFAULT_SOURCE): String {
        return try {
            val arg = JSONObject().apply {
                put("queryWaitToReceive", queryWaitToReceive)
                put("source", source)
            }
            RequestManager.requestString("alipay.antforest.forest.h5.queryPvpHomeInfo", JSONArray().put(arg).toString())
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    @JvmStatic
    fun receivePvpRewards(source: String = DEFAULT_SOURCE): String {
        return try {
            val arg = JSONObject().apply {
                put("source", source)
            }
            RequestManager.requestString("alipay.antforest.forest.h5.receivePvpRewards", JSONArray().put(arg).toString())
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    @JvmStatic
    fun queryPvpBattleRecords(pageSize: Int = 5, source: String = DEFAULT_SOURCE): String {
        return try {
            val arg = JSONObject().apply {
                put("pageSize", pageSize.coerceAtLeast(1))
                put("source", source)
            }
            RequestManager.requestString("alipay.antforest.forest.h5.queryPvpBattleRecords", JSONArray().put(arg).toString())
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    @JvmStatic
    fun queryEnergyPvpInfo(checkReward: Boolean = true, source: String = DEFAULT_SOURCE): String {
        return try {
            val arg = JSONObject().apply {
                put("extInfo", JSONObject().put("checkReward", checkReward).toString())
                put("queryBizType", "energyPvpInfo")
                put("source", source)
            }
            RequestManager.requestString("alipay.antforest.forest.h5.queryMiscInfo", JSONArray().put(arg).toString())
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    /**
     * 批量获取好友能量信息（标准版）
     */
    @JvmStatic
    fun fillUserRobFlag(userIdList: JSONArray): String {
        return try {
            val arg = JSONObject().apply {
                put("source", "chInfo_ch_appcenter__chsub_9patch")
                put("userIdList", userIdList)
            }
            val joRelationLocal = JSONObject().apply {
                put("pathList", JSONArray().put("friendRanking"))
            }
            RequestManager.requestString(
                "alipay.antforest.forest.h5.fillUserRobFlag",
                "[$arg]",
                "[$joRelationLocal]"
            )
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 批量获取好友能量信息（增强版 - PK排行榜专用）
     */
    @JvmStatic
    fun fillUserRobFlag(userIdList: JSONArray, needFillUserInfo: Boolean): String {
        return try {
            val arg = JSONObject().apply {
                put("source", "chInfo_ch_appcenter__chsub_9patch")
                put("userIdList", userIdList)
                put("needFillUserInfo", needFillUserInfo)
            }
            RequestManager.requestString("alipay.antforest.forest.h5.fillUserRobFlag", "[$arg]")
        } catch (e: Exception) {
            ""
        }
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryHomePage(source: String? = null): String {
        val actualSource = source ?: DEFAULT_SOURCE
        val requestObject = JSONObject().apply {
            put("activityParam", JSONObject())
            put("configVersionMap", JSONObject().put("wateringBubbleConfig", "0"))
            put("skipWhackMole", false)
            put("source", actualSource)
            put("version", HOME_PAGE_VERSION)
        }
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.queryHomePage",
            JSONArray().put(requestObject).toString(),
            3,
            1000
        )
    }

    @JvmStatic
    fun queryDynamicsIndex(): String {
        return try {
            val arg = JSONObject().apply {
                put("autoRefresh", false)
                put("source", "chInfo_ch_appcenter__chsub_9patch")
                put("version", VERSION)
            }
            RequestManager.requestString(
                "alipay.antforest.forest.h5.queryDynamicsIndex",
                JSONArray().put(arg).toString()
            )
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    @JvmStatic
    fun queryFriendHomePage(userId: String, fromAct: String?, source: String? = null): String {
        return try {
            val actualFromAct = fromAct ?: "TAKE_LOOK_FRIEND"
            val actualSource = source ?: DEFAULT_SOURCE
            val arg = JSONObject().apply {
                put("activityParam", JSONObject())
                if (actualFromAct != "TAKE_LOOK" && actualFromAct != "TAKE_LOOK_FRIEND") {
                    put("canRobFlags", "T,F,F,F,F")
                }
                put("configVersionMap", JSONObject().put("wateringBubbleConfig", "0"))
                put("currentEnergy", 0)
                put("currentVitalityAmount", 0)
                put("skipWhackMole", false)
                put("source", actualSource)
                put("userId", userId)
                put("fromAct", actualFromAct)
                put("version", HOME_PAGE_VERSION)
            }
            RequestManager.requestString("alipay.antforest.forest.h5.queryFriendHomePage", "[$arg]", 3, 1000)
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    @JvmStatic
    fun queryTakeLookCombineBiz(
        skipUsers: JSONObject,
        source: String? = null,
        takeLookExposedTimes: Int = 0,
        takeLookExposed: Boolean = false
    ): String {
        return try {
            val actualSource = source ?: DEFAULT_SOURCE
            val extInfo = JSONObject().apply {
                if (skipUsers.length() > 0) {
                    put("skipUsers", skipUsers.toString())
                }
                if (takeLookExposed) {
                    put("takeLookExposed", "Y")
                }
                put("takeLookExposedTimes", takeLookExposedTimes)
            }
            val requestData = JSONObject().apply {
                put("extInfo", extInfo)
                put("source", actualSource)
                put("version", TAKE_LOOK_COMBINE_BIZ_VERSION)
            }
            RequestManager.requestString(
                RpcEntity(
                    "alipay.antforest.forest.h5.queryCombineBiz",
                    "[$requestData]",
                    headers = forestHeaders(actualSource)
                )
            )
        } catch (e: JSONException) {
            Log.printStackTrace("AntForestRpcCall", "queryTakeLookCombineBiz构建请求参数失败", e)
            ""
        }
    }

    /**
     * 找能量方法 - 查找可收取能量的好友（带跳过用户列表）
     */
    @JvmStatic
    fun takeLook(
        skipUsers: JSONObject,
        source: String? = null,
        exposedUserId: String = "",
        takeLookStart: Boolean = true
    ): String {
        return try {
            val actualSource = source ?: DEFAULT_SOURCE
            val requestData = JSONObject().apply {
                put("contactsStatus", "N")
                put("exposedUserId", exposedUserId)
                put("skipUsers", skipUsers)
                put("source", actualSource)
                put("takeLookEnd", false)
                put("takeLookStart", takeLookStart)
                put("version", TAKE_LOOK_VERSION)
            }
            RequestManager.requestString(
                RpcEntity(
                    "alipay.antforest.forest.h5.takeLook",
                    "[$requestData]",
                    headers = forestHeaders(actualSource)
                )
            )
        } catch (e: JSONException) {
            Log.printStackTrace("AntForestRpcCall", "takeLook构建请求参数失败", e)
            ""
        }
    }

    @JvmStatic
    fun energyRpcEntity(
        bizType: String,
        userId: String,
        bubbleId: Long,
        source: String? = null
    ): RpcEntity? {
        return try {
            val actualSource = source ?: DEFAULT_SOURCE
            val args = JSONObject().apply {
                put("bizType", bizType)
                put("bubbleIds", JSONArray().put(bubbleId))
                put("source", actualSource)
                put("userId", userId)
                put("version", COLLECT_ENERGY_VERSION)
            }
            RpcEntity(
                "alipay.antmember.forest.h5.collectEnergy",
                "[$args]",
                headers = forestHeaders(actualSource)
            )
        } catch (e: Exception) {
            Log.printStackTrace(e)
            null
        }
    }

    @JvmStatic
    fun collectEnergy(bizType: String, userId: String, bubbleId: Long, source: String? = null): String {
        val r = energyRpcEntity(bizType, userId, bubbleId, source) ?: return ""
        return RequestManager.requestString(r)
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun batchEnergyRpcEntity(
        bizType: String,
        userId: String,
        bubbleIds: List<Long>,
        source: String? = null
    ): RpcEntity {
        val actualSource = source ?: DEFAULT_SOURCE
        val arg = JSONObject().apply {
            put("bizType", bizType)
            put("bubbleIds", JSONArray(bubbleIds))
            put("fromAct", "BATCH_ROB_ENERGY")
            put("source", actualSource)
            put("userId", userId)
            put("version", COLLECT_ENERGY_VERSION)
        }
        return RpcEntity(
            "alipay.antmember.forest.h5.collectEnergy",
            "[$arg]",
            headers = forestHeaders(actualSource)
        )
    }

    @JvmStatic
    fun collectRebornEnergy(): String {
        return try {
            val arg = JSONObject().apply {
                put("source", "chInfo_ch_appcenter__chsub_9patch")
            }
            RequestManager.requestString("alipay.antforest.forest.h5.collectRebornEnergy", "[$arg]")
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    @JvmStatic
    fun transferEnergy(targetUser: String, bizNo: String, energyId: Int, notifyFriend: Boolean): String {
        return try {
            val arg = JSONObject().apply {
                put("bizNo", bizNo + UUID.randomUUID().toString())
                put("energyId", energyId)
                put("extendInfo", JSONObject().put("sendChat", if (notifyFriend) "Y" else "N"))
                put("from", "friendIndex")
                put("source", "chInfo_ch_appcenter__chsub_9patch")
                put("targetUser", targetUser)
                put("transferType", "WATERING")
                put("version", VERSION)
            }
            RequestManager.requestString("alipay.antmember.forest.h5.transferEnergy", "[$arg]")
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    @JvmStatic
    fun queryRecommendFriendListByScene(sceneCode: String = ONE_CLICK_WATERING_SCENE_CODE): String {
        return try {
            val context = resolveSceneContext(ForestRpcScene.ONE_CLICK_WATERING)
            val arg = JSONObject().apply {
                put("sceneCode", sceneCode)
                put("source", context.source)
            }
            RequestManager.requestString(
                RpcEntity(
                    "alipay.antforest.forest.h5.queryRecommendFriendListByScene",
                    JSONArray().put(arg).toString(),
                    headers = context.headers
                )
            )
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    @JvmStatic
    fun transferEnergyForOneClickWatering(
        targetUser: String,
        notifyFriend: Boolean = true,
        orderIndex: Int = 0
    ): String {
        return try {
            val context = resolveSceneContext(ForestRpcScene.ONE_CLICK_WATERING)
            val arg = JSONObject().apply {
                put("bizNo", "${System.currentTimeMillis()}_${RandomUtil.getRandomInt(16)}_${orderIndex.coerceAtLeast(0)}")
                put("energyId", "39")
                put("extInfo", JSONObject().put("sendChat", if (notifyFriend) "Y" else "N"))
                put("source", context.source)
                put("targetUser", targetUser)
                put("transferType", "WATERING")
                put("version", context.version ?: ONE_CLICK_WATERING_VERSION)
            }
            RequestManager.requestString(
                RpcEntity(
                    "alipay.antmember.forest.h5.transferEnergy",
                    JSONArray().put(arg).toString(),
                    headers = context.headers
                )
            )
        } catch (e: Exception) {
            Log.printStackTrace(e)
            ""
        }
    }

    @JvmStatic
    fun forFriendCollectEnergy(targetUserId: String, bubbleId: Long): String {
        val args1 = "[{\"bubbleIds\":[$bubbleId],\"targetUserId\":\"$targetUserId\"}]"
        return RequestManager.requestString("alipay.antmember.forest.h5.forFriendCollectEnergy", args1)
    }

    @JvmStatic
    fun vitalitySign(): String {
        return RequestManager.requestString("alipay.antforest.forest.h5.vitalitySign", "[{\"source\":\"chInfo_ch_appcenter__chsub_9patch\"}]")
    }

    @JvmStatic
    fun queryEnergyRainHome(source: String = ENERGY_RAIN_SOURCE): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.queryEnergyRainHome",
            "[{\"source\":\"$source\",\"version\":\"$ENERGY_RAIN_VERSION\"}]"
        )
    }

    @JvmStatic
    fun queryEnergyRainCanGrantList(): String {
        return RequestManager.requestString("alipay.antforest.forest.h5.queryEnergyRainCanGrantList", "[{}]")
    }

    @JvmStatic
    fun grantEnergyRainChance(targetUserId: String): String {
        return RequestManager.requestString("alipay.antforest.forest.h5.grantEnergyRainChance", "[{\"targetUserId\":$targetUserId}]")
    }

    @JvmStatic
    fun startEnergyRain(): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.startEnergyRain",
            "[{\"version\":\"$ENERGY_RAIN_VERSION\"}]"
        )
    }

    @JvmStatic
    fun energyRainSettlement(saveEnergy: Int, token: String): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.energyRainSettlement",
            "[{\"activityPropNums\":0,\"saveEnergy\":$saveEnergy,\"token\":\"$token\",\"version\":\"$ENERGY_RAIN_VERSION\"}]"
        )
    }

    /**
     * 查询能量雨/游戏结束列表奖励
     */
    @JvmStatic
    fun queryEnergyRainEndGameList(): String {
        return RequestManager.requestString("alipay.antforest.forest.h5.queryEnergyRainEndGameList", "[{}]")
    }

    /**
     * 初始化/上报游戏任务
     */
    @JvmStatic
    fun initTask(taskType: String): String {
        val timestamp = System.currentTimeMillis().toString()
        val randomSuffix = UUID.randomUUID().toString().substring(0, 8)
        val outBizNo = "${taskType}_${timestamp}_${randomSuffix}"

        val args = "[{\"outBizNo\":\"$outBizNo\",\"requestType\":\"H5\",\"sceneCode\":\"ANTFOREST_ENERGY_RAIN_TASK\",\"source\":\"ANTFOREST\",\"taskType\":\"$taskType\"}]"
        return RequestManager.requestString("com.alipay.antiep.initTask", args)
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryTaskList(): String {
        val context = resolveSceneContext(ForestRpcScene.HOME_TASK_LIST)
        return queryTaskListRequest(
            "home_task_list",
            context.source,
            context.extend ?: createTaskListExtend(),
            context.version ?: TASK_LIST_VERSION,
            context.headers
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryTaskList(
        fromAct: String,
        source: String = DEFAULT_SOURCE,
        extend: JSONObject = createTaskListExtend(),
        version: String = TASK_LIST_VERSION
    ): String {
        return queryTaskListRequest(fromAct, source, extend, version)
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryLeafTaskList(): String {
        return queryTaskList("home_leaves_task_list")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryTakeLookEndTaskList(source: String = DEFAULT_SOURCE): String {
        val context = resolveSceneContext(ForestRpcScene.TAKE_LOOK_END_TASK_LIST, source)
        return queryTaskListRequest(
            "take_look_end_task_list",
            context.source,
            context.extend ?: createTaskListExtend(),
            context.version ?: TASK_LIST_VERSION,
            context.headers
        )
    }

    @JvmStatic
    fun takeLookEnd(source: String = DEFAULT_SOURCE): String {
        return try {
            val requestData = JSONObject().apply {
                put("contactsStatus", "N")
                put("source", source)
                put("version", TASK_LIST_VERSION)
            }
            RequestManager.requestString(
                RpcEntity(
                    "alipay.antforest.forest.h5.takeLookEnd",
                    JSONArray().put(requestData).toString(),
                    headers = forestHeaders(source)
                )
            )
        } catch (e: JSONException) {
            Log.printStackTrace("AntForestRpcCall", "takeLookEnd构建请求参数失败", e)
            ""
        }
    }

    @JvmStatic
    fun queryGameAggCard(): String {
        return RequestManager.requestString(
            "com.alipay.gamecenterhome.biz.rpc.queryGameAggCard",
            "[{\"appearedCardIds\":[],\"deviceLevel\":\"high\",\"pageSize\":6,\"pageStart\":1," +
                    "\"source\":\"mokuai_senlin_hlz\",\"trafficDriverId\":\"mokuai_senlin_hlz\",\"unityDeviceLevel\":\"high\"}]"
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryTaskListV2(firstTaskType: String): String {
        val jo = JSONObject().apply {
            val extend = JSONObject().apply {
                put("firstTaskType", firstTaskType)
            }
            put("extend", extend)
            put("fromAct", "home_task_list")
            when (firstTaskType) {
                "DNHZ_SL_college" -> put("source", firstTaskType)
                "DXS_BHZ", "DXS_JSQ" -> put("source", "202212TJBRW")
            }
            put("version", VERSION)
        }
        return RequestManager.requestString("alipay.antforest.forest.h5.queryTaskList", JSONArray().put(jo).toString())
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun receiveTaskAward(sceneCode: String, taskType: String): String {
        val jo = JSONObject().apply {
            put("ignoreLimit", false)
            put("requestType", "H5")
            put("sceneCode", sceneCode)
            put("source", "ANTFOREST")
            put("taskType", taskType)
        }
        return RequestManager.requestString("com.alipay.antiep.receiveTaskAward", JSONArray().put(jo).toString())
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun receiveTaskAwardV2(taskType: String): String {
        val jo = JSONObject().apply {
            put("ignoreLimit", false)
            put("requestType", "H5")
            put("sceneCode", "ANTFOREST_VITALITY_TASK")
            put("source", "ANTFOREST")
            put("taskType", taskType)
        }
        return RequestManager.requestString("com.alipay.antiep.receiveTaskAward", JSONArray().put(jo).toString())
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun finishTask(sceneCode: String, taskType: String): String {
        return finishTask(sceneCode, taskType, null)
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun finishTask(sceneCode: String, taskType: String, headerSource: String?): String {
        val outBizNo = "${taskType}_${RandomUtil.nextDouble()}"
        val jo = JSONObject().apply {
            put("outBizNo", outBizNo)
            put("requestType", "H5")
            put("sceneCode", sceneCode)
            put("source", "ANTFOREST")
            put("taskType", taskType)
        }
        val headers = headerSource?.takeIf { it.isNotBlank() }?.let(::forestHeaders)
        return if (headers == null) {
            RequestManager.requestString("com.alipay.antiep.finishTask", "[$jo]")
        } else {
            RequestManager.requestString(
                RpcEntity(
                    "com.alipay.antiep.finishTask",
                    "[$jo]",
                    headers = headers
                )
            )
        }
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun popupTask(): String {
        val context = resolveSceneContext(ForestRpcScene.HOME_TASK_LIST)
        val jo = JSONObject().apply {
            put(
                "extend",
                JSONObject().apply {
                    put("appMode", "normal")
                    put("nativeVersion", currentNativeVersion())
                    put("osType", "android")
                }
            )
            put("fromAct", "pop_task")
            put("needInitSign", false)
            put("needTeamPlantRewardInfo", false)
            put("source", context.source)
            put("statusList", JSONArray().put("TODO").put("FINISHED"))
            put("version", HOME_PAGE_VERSION)
        }
        return RequestManager.requestString(
            RpcEntity(
                "alipay.antforest.forest.h5.popupTask",
                JSONArray().put(jo).toString(),
                headers = context.headers
            )
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun antiepSign(entityId: String, userId: String, sceneCode: String): String {
        val jo = JSONObject().apply {
            put("entityId", entityId)
            put("requestType", "rpc")
            put("sceneCode", sceneCode)
            put("source", "ANTFOREST")
            put("userId", userId)
        }
        return RequestManager.requestString("com.alipay.antiep.sign", "[$jo]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun signEntranceAccess(
        sceneCode: String,
        source: String = DEFAULT_SOURCE,
        externalSource: String = source
    ): String {
        val jo = JSONObject().apply {
            put("externalSource", externalSource)
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", source)
        }
        return RequestManager.requestString("com.alipay.antiep.signEntranceAccess", "[$jo]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryCommonSign(
        bizType: String,
        source: String = DEFAULT_SOURCE,
        withEntity: Boolean = true
    ): String {
        val jo = JSONObject().apply {
            put("bizType", bizType)
            put("source", source)
            put("withEntity", withEntity)
        }
        return RequestManager.requestString("alipay.antforest.forest.h5.queryCommonSign", "[$jo]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun signCommon(
        sceneCode: String,
        userId: String,
        source: String = "ANTFOREST"
    ): String {
        val jo = JSONObject().apply {
            put("requestType", "rpc")
            put("sceneCode", sceneCode)
            put("source", source)
            put("userId", userId)
        }
        return RequestManager.requestString("com.alipay.antiep.sign", "[$jo]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryPropList(
        onlyGive: Boolean,
        source: String = HOME_TASK_SOURCE,
        version: String = PROP_LIST_VERSION,
        propType: String = "",
        pageQuery: Boolean = true
    ): String {
        val jo = JSONObject().apply {
            put("onlyGive", if (onlyGive) "Y" else "")
            put("pageQuery", pageQuery)
            if (propType.isNotBlank()) put("propType", propType)
            put("source", source)
            put("version", version)
        }
        return RequestManager.requestString(
            RpcEntity(
                "alipay.antforest.forest.h5.queryPropList",
                JSONArray().put(jo).toString(),
                headers = forestHeaders(source)
            )
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryVitalityEnergyRainPropList(): String {
        return queryPropList(
            onlyGive = false,
            source = VITALITY_PROP_SOURCE,
            version = PROP_LIST_VERSION,
            propType = "LIMIT_TIME_ENERGY_RAIN_CHANCE",
            pageQuery = false
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryAnimalPropList(): String {
        val jo = JSONObject().apply {
            put("source", "chInfo_ch_appcenter__chsub_9patch")
        }
        return RequestManager.requestString("alipay.antforest.forest.h5.queryAnimalPropList", JSONArray().put(jo).toString())
    }

    @JvmStatic
    @Throws(JSONException::class)
    internal fun consumeProp(
        propGroup: String,
        propId: String,
        propType: String,
        secondConfirm: Boolean,
        context: PropConsumeContext? = null
    ): String {
        val actualContext = context ?: PropConsumeContext(
            source = DEFAULT_SOURCE,
            version = VERSION,
            headers = forestHeaders(DEFAULT_SOURCE)
        )
        val actualPropGroup = context?.propGroup?.takeIf { it.isNotBlank() } ?: propGroup
        val jo = JSONObject().apply {
            if (actualPropGroup.isNotEmpty()) put("propGroup", actualPropGroup)
            put("propId", propId)
            put("propType", propType)
            put("sToken", "${System.currentTimeMillis()}_${RandomUtil.getRandomString(8)}")
            put("secondConfirm", secondConfirm)
            put("source", actualContext.source)
            put("timezoneId", PATROL_TIMEZONE)
            put("version", actualContext.version)
        }
        return RequestManager.requestString(
            RpcEntity(
                "alipay.antforest.forest.h5.consumeProp",
                "[$jo]",
                headers = actualContext.headers
            )
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun consumeProp(propId: String, propType: String, secondConfirm: Boolean): String {
        return consumeProp("", propId, propType, secondConfirm)
    }

    @JvmStatic
    @Throws(JSONException::class)
    internal fun consumeProp2(
        propGroup: String,
        propId: String,
        propType: String,
        context: PropConsumeContext? = null
    ): String {
        val actualContext = context ?: PropConsumeContext(
            source = DEFAULT_SOURCE,
            version = VERSION,
            headers = forestHeaders(DEFAULT_SOURCE)
        )
        val actualPropGroup = context?.propGroup?.takeIf { it.isNotBlank() } ?: propGroup
        val jo = JSONObject().apply {
            if (actualPropGroup.isNotEmpty()) put("propGroup", actualPropGroup)
            put("propId", propId)
            put("propType", propType)
            put("sToken", "${System.currentTimeMillis()}_${RandomUtil.getRandomString(8)}")
            put("source", actualContext.source)
            put("timezoneId", PATROL_TIMEZONE)
            put("version", actualContext.version)
        }
        return RequestManager.requestString(
            RpcEntity(
                "alipay.antforest.forest.h5.consumeProp",
                "[$jo]",
                headers = actualContext.headers
            )
        )
    }

    @JvmStatic
    fun consumeProp(propId: String, propType: String): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.consumeProp",
            "[{\"propId\":\"$propId\",\"propType\":\"$propType\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"timezoneId\":\"Asia/Shanghai\",\"version\":\"$VERSION\"}]"
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryUserPatrol(): String {
        return requestPatrol("alipay.antforest.forest.h5.queryUserPatrol", buildPatrolPayload())
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun queryMyPatrolRecord(): String {
        return requestPatrol("alipay.antforest.forest.h5.queryMyPatrolRecord", buildPatrolPayload())
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun switchUserPatrol(targetPatrolId: String): String {
        val jo = buildPatrolPayload {
            put("targetPatrolId", targetPatrolId)
        }
        return requestPatrol("alipay.antforest.forest.h5.switchUserPatrol", jo)
    }

    @JvmStatic
    fun patrolGo(nodeIndex: Int, patrolId: Int): String {
        return requestPatrol(
            "alipay.antforest.forest.h5.patrolGo",
            buildPatrolPayload {
                put("nodeIndex", nodeIndex)
                put("patrolId", patrolId)
            }
        )
    }

    @JvmStatic
    fun patrolKeepGoing(nodeIndex: Int, patrolId: Int, eventType: String): String {
        val reactParam = when (eventType) {
            "video" -> JSONObject().put("viewed", "Y")
            "chase" -> JSONObject().put("sendChat", "Y")
            "quiz" -> JSONObject().put("answer", "correct")
            else -> JSONObject()
        }
        return requestPatrol(
            "alipay.antforest.forest.h5.patrolKeepGoing",
            buildPatrolPayload {
                put("nodeIndex", nodeIndex)
                put("patrolId", patrolId)
                put("reactParam", reactParam)
            }
        )
    }

    @JvmStatic
    fun exchangePatrolChance(costStep: Int): String {
        return requestPatrol(
            "alipay.antforest.forest.h5.exchangePatrolChance",
            buildPatrolPayload {
                put("costStep", costStep)
            }
        )
    }

    @JvmStatic
    fun queryAnimalAndPiece(animalId: Int, patrolId: Int = 0): String {
        val jo = buildPatrolPayload {
            when {
                patrolId > 0 -> {
                    put("patrolId", patrolId)
                    put("withDetail", "N")
                }

                animalId != 0 -> put("animalId", animalId)
                else -> {
                    put("withDetail", "N")
                    put("withGift", true)
                }
            }
        }
        return requestPatrol("alipay.antforest.forest.h5.queryAnimalAndPiece", jo)
    }

    @JvmStatic
    fun combineAnimalPiece(animalId: Int, piecePropIds: String): String {
        return requestPatrol(
            "alipay.antforest.forest.h5.combineAnimalPiece",
            buildPatrolPayload {
                put("animalId", animalId)
                put("piecePropIds", JSONArray(piecePropIds))
            }
        )
    }

    @JvmStatic
    fun protectBubble(targetUserId: String): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.protectBubble",
            "[{\"source\":\"$PROTECT_BUBBLE_SOURCE\",\"targetUserId\":\"$targetUserId\",\"version\":\"$PROTECT_BUBBLE_VERSION\"}]"
        )
    }

    @JvmStatic
    fun collectFriendGiftBox(targetId: String, targetUserId: String): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.collectFriendGiftBox",
            "[{\"source\":\"chInfo_ch_appid-60000002\",\"targetId\":\"$targetId\",\"targetUserId\":\"$targetUserId\"}]"
        )
    }

    @JvmStatic
    fun startWhackMole(source: String): String {
        return RequestManager.requestString("alipay.antforest.forest.h5.startWhackMole", "[{\"source\":\"$source\"}]")
    }

    @JvmStatic
    fun settlementWhackMole(token: String, moleIdList: List<String>, source: String): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.settlementWhackMole",
            "[{\"moleIdList\":[${moleIdList.joinToString(",")}],\"settlementScene\":\"NORMAL\",\"source\":\"$source\",\"token\":\"$token\",\"version\":\"$WHACK_MOLE_VERSION\"}]"
        )
    }

    @JvmStatic
    fun whackMole(moleId: Long, token: String, source: String): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.whackMole",
            "[{\"moleId\":$moleId,\"source\":\"$source\",\"token\":\"$token\",\"version\":\"$WHACK_MOLE_VERSION\"}]"
        )
    }

    @JvmStatic
    fun closeWhackMole(source: String): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.updateUserConfig",
            "[{\"configMap\":{\"whackMole\":\"N\"},\"source\":\"$source\"}]"
        )
    }

    @JvmStatic
    fun getPropGroup(propType: String): String {
        return when {
            propType.contains("SHIELD") -> "shield"
            propType.contains("DOUBLE_CLICK") -> "doubleClick"
            propType.contains("STEALTH") -> "stealthCard"
            propType.contains("BOMB_CARD") || propType.contains("NO_EXPIRE") -> "energyBombCard"
            propType.contains("ROB_EXPAND") -> "robExpandCard"
            propType.contains("BUBBLE_BOOST") -> "boost"
            else -> ""
        }
    }

    @JvmStatic
    fun itemList(labelType: String, startIndex: Int = 0, pageSize: Int = 10): String {
        val requestData = JSONObject().apply {
            put("extendInfo", "{}")
            put("fromSpuId", "")
            put("labelType", labelType)
            put("pageSize", pageSize)
            put("requestType", "rpc")
            put("sceneCode", "ANTFOREST_VITALITY")
            put("source", "afEntry")
            put("startIndex", startIndex)
        }
        return RequestManager.requestString(
            RpcEntity(
                "com.alipay.antiep.itemList",
                JSONArray().put(requestData).toString(),
                headers = forestHeaders("afEntry")
            )
        )
    }

    @JvmStatic
    fun itemDetail(spuId: String): String {
        val requestData = JSONObject().apply {
            put("requestType", "rpc")
            put("sceneCode", "ANTFOREST_VITALITY")
            put("source", "afEntry")
            put("spuId", spuId)
        }
        return RequestManager.requestString(
            RpcEntity(
                "com.alipay.antiep.itemDetail",
                JSONArray().put(requestData).toString(),
                headers = forestHeaders("afEntry")
            )
        )
    }

    @JvmStatic
    fun queryVitalityStoreIndex(): String {
        val requestData = JSONObject().apply {
            put("source", "afEntry")
        }
        return RequestManager.requestString(
            RpcEntity(
                "alipay.antforest.forest.h5.queryVitalityStoreIndex",
                JSONArray().put(requestData).toString(),
                headers = forestHeaders("afEntry")
            )
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun exchangeBenefit(spuId: String, skuId: String): String {
        val jo = JSONObject().apply {
            put("sceneCode", "ANTFOREST_VITALITY")
            put("requestId", "${System.currentTimeMillis()}_${RandomUtil.getRandomInt(17)}")
            put("spuId", spuId)
            put("skuId", skuId)
            put("source", "GOOD_DETAIL")
        }
        return RequestManager.requestString(
            RpcEntity(
                "com.alipay.antcommonweal.exchange.h5.exchangeBenefit",
                JSONArray().put(jo).toString(),
                headers = forestHeaders("afEntry")
            )
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun studentQqueryCheckInModel(): String {
        val jo = JSONObject().apply {
            put("chInfo", "ch_appcollect__chsub_my-recentlyUsed")
            put("skipTaskModule", false)
        }
        return RequestManager.requestString("alipay.membertangram.biz.rpc.student.queryCheckInModel", JSONArray().put(jo).toString())
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun studentCheckin(): String {
        val jo = JSONObject().apply {
            put("source", "chInfo_ch_appcenter__chsub_9patch")
        }
        return RequestManager.requestString("alipay.membertangram.biz.rpc.student.checkIn", JSONArray().put(jo).toString())
    }

    @JvmStatic
    fun queryForestEnergy(scene: String): String {
        val args = "[{\"activityCode\":\"query_forest_energy\",\"activityId\":\"2024052300762675\",\"body\":{\"scene\":\"$scene\"},\"version\":\"2.0\"}]"
        return RequestManager.requestString("alipay.iblib.channel.data", args)
    }

    @JvmStatic
    fun produceForestEnergy(scene: String): String {
        val uniqueId = System.currentTimeMillis()
        val args = "[{\"activityCode\":\"produce_forest_energy\",\"activityId\":\"2024052300762674\",\"body\":{\"scene\":\"$scene\",\"uniqueId\":\"$uniqueId\"},\"version\":\"2.0\"}]"
        return RequestManager.requestString("alipay.iblib.channel.data", args)
    }

    @JvmStatic
    fun harvestForestEnergy(scene: String, bubbles: JSONArray): String {
        val args = "[{\"activityCode\":\"harvest_forest_energy\",\"activityId\":\"2024052300762676\",\"body\":{\"bubbles\":$bubbles,\"scene\":\"$scene\"},\"version\":\"2.0\"}]"
        return RequestManager.requestString("alipay.iblib.channel.data", args)
    }

    @JvmStatic
    fun medical_health_feeds_query(): String {
        return RequestManager.requestString(
            "alipay.iblib.channel.build.query",
            "[{\"activityCode\":\"medical_health_feeds_query\",\"activityId\":\"2023072600001207\",\"body\":{\"apiVersion\":\"3.1.0\",\"bizId\":\"B213\"," +
                    "\"businessCode\":\"JKhealth\",\"businessId\":\"O2023071900061804\",\"cityCode\":\"330100\",\"cityName\":\"杭州\"," +
                    "\"exclContentIds\":[],\"filterItems\":[]," +
                    "\"latitude\":\"\",\"longitude\":\"\",\"moduleParam\":{\"COMMON_FEEDS_BLOCK_2024041200243259\":{}}," +
                    "\"pageCode\":\"YM2024041200137150\",\"pageNo\":1,\"pageSize\":10,\"pid\":\"BC_PD_20230713000008526\",\"queryQuizActivityFeed\":1," +
                    "\"scenceCode\":\"HEALTH_CHANNEL\",\"schemeParams\":{}," +
                    "\"scope\":\"PARTIAL\",\"selectedTabCode\":\"\",\"sourceType\":\"miniApp\",\"specialItemId\":\"\",\"specialItemType\":\"\"," +
                    "\"tenantCode\":\"2021003141652419\",\"underTakeContentId\":\"\"},\"version\":\"2.0\"}]"
        )
    }

    @JvmStatic
    fun query_forest_energy(): String {
        return RequestManager.requestString(
            "alipay.iblib.channel.data",
            "[{\"activityCode\":\"query_forest_energy\",\"activityId\":\"2024052300762675\",\"appId\":\"2021003141652419\"," +
                    "\"body\":{\"scene\":\"FEEDS\"},\"version\":\"2.0\"}]"
        )
    }

    @JvmStatic
    fun produce_forest_energy(uniqueId: String): String {
        return RequestManager.requestString(
            "alipay.iblib.channel.data",
            "[{\"activityCode\":\"produce_forest_energy\",\"activityId\":\"2024052300762674\",\"appId\":\"2021003141652419\"," +
                    "\"body\":{\"scene\":\"FEEDS\",\"uniqueId\":\"$uniqueId\"},\"version\":\"2.0\"}]"
        )
    }

    @JvmStatic
    fun harvest_forest_energy(energy: Int, id: String): String {
        return RequestManager.requestString(
            "alipay.iblib.channel.data",
            "[{\"activityCode\":\"harvest_forest_energy\",\"activityId\":\"2024052300762676\",\"appId\":\"2021003141652419\"," +
                    "\"body\":{\"bubbles\":[{\"energy\":$energy,\"id\":\"$id\"}],\"scene\":\"FEEDS\"},\"version\":\"2.0\"}]"
        )
    }

    @JvmStatic
    fun ecolifeQueryHomePage(): String {
        return RequestManager.requestString("alipay.ecolife.rpc.h5.queryHomePage", "[{\"channel\":\"ALIPAY\",\"source\":\"search_brandbox\"}]")
    }

    @JvmStatic
    fun ecolifeOpenEcolife(): String {
        return RequestManager.requestString("alipay.ecolife.rpc.h5.openEcolife", "[{\"channel\":\"ALIPAY\",\"source\":\"renwuGD\"}]")
    }

    @JvmStatic
    fun ecolifeTick(actionId: String, dayPoint: String, source: String): String {
        val args1 = "[{\"actionId\":\"$actionId\",\"channel\":\"ALIPAY\",\"dayPoint\":\"$dayPoint\",\"generateEnergy\":false,\"source\":\"$source\"}]"
        return RequestManager.requestString("alipay.ecolife.rpc.h5.tick", args1)
    }

    @JvmStatic
    fun ecolifeQueryDish(source: String, dayPoint: String): String {
        return RequestManager.requestString("alipay.ecolife.rpc.h5.queryDish", "[{\"channel\":\"ALIPAY\",\"dayPoint\":\"$dayPoint\",\"source\":\"$source\"}]")
    }

    @JvmStatic
    fun testH5Rpc(operationType: String, requestDate: String): String {
        return RequestManager.requestString(operationType, requestDate)
    }

    @JvmStatic
    fun consultForSendEnergyByAction(sourceType: String): String {
        return RequestManager.requestString("alipay.bizfmcg.greenlife.consultForSendEnergyByAction", "[{\"sourceType\":\"$sourceType\"}]")
    }

    @JvmStatic
    fun sendEnergyByAction(sourceType: String): String {
        return RequestManager.requestString(
            "alipay.bizfmcg.greenlife.sendEnergyByAction",
            "[{\"actionType\":\"GOODS_BROWSE\",\"requestId\":\"${RandomUtil.getRandomString(8)}\",\"sourceType\":\"$sourceType\"}]"
        )
    }

    @JvmStatic
    fun collectRobExpandEnergy(
        propId: String,
        propType: String,
        source: String = DEFAULT_SOURCE
    ): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.collectRobExpandEnergy",
            "[{\"propId\":\"$propId\",\"propType\":\"$propType\",\"source\":\"$source\"}]"
        )
    }

    @JvmStatic
    fun AnimalConsumeProp(propGroup: String, propId: String, propType: String): String {
        return consumeProp(propGroup, propId, propType, false, patrolPropConsumeContext(propGroup))
    }

    @JvmStatic
    fun collectAnimalRobEnergy(propId: String, propType: String, shortDay: String): String {
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.collectAnimalRobEnergy",
            "[{\"propId\":\"$propId\",\"propType\":\"$propType\",\"shortDay\":\"$shortDay\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun enterDrawActivityopengreen(activityId: String?, sceneCode: String, source: String): String {
        val requestData = JSONObject().apply {
            put("activityId", activityId ?: "")
            put("context", JSONObject().apply {
                put("appMode", "normal")
                put("layerTipDisplayInfos", "[]")
            })
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", source)
        }
        Log.forest("enterDrawActivityopengreen - 活动: $activityId, 场景: $sceneCode, source: $source")
        return RequestManager.requestString("com.alipay.antiepdrawprod.enterDrawActivityopengreen", "[$requestData]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun listTaskopengreen(sceneCode: String, source: String): String {
        val requestData = JSONObject().apply {
            put("extend", JSONObject().put("appMode", "normal"))
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", source)
        }
        Log.forest("listTaskopengreen - 场景: $sceneCode, source: $source")
        return RequestManager.requestString("com.alipay.antieptask.listTaskopengreen", "[$requestData]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun listTaskByIdsopengreen(sceneCode: String, source: String, taskTypeList: Collection<String>): String {
        val requestData = JSONObject().apply {
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", source)
            put("taskTypeList", JSONArray().apply {
                taskTypeList.filter { it.isNotBlank() }.forEach { put(it) }
            })
        }
        Log.forest("listTaskByIdsopengreen - 场景: $sceneCode, source: $source, taskTypes: ${taskTypeList.joinToString()}")
        return RequestManager.requestString("com.alipay.antieptask.listTaskByIdsopengreen", "[$requestData]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun drawopengreen(activityId: String, sceneCode: String, source: String, userId: String): String {
        val requestData = JSONObject().apply {
            put("activityId", activityId)
            put("context", JSONObject().put("appMode", "normal"))
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", source)
            put("userId", userId)
        }
        Log.forest("drawopengreen - 活动: $activityId, 场景: $sceneCode, source: $source")
        return RequestManager.requestString("com.alipay.antiepdrawprod.drawopengreen", "[$requestData]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun drawSyncopengreen(activityId: String, sceneCode: String, source: String): String {
        val requestData = JSONObject().apply {
            put("activityId", activityId)
            put("context", JSONObject().put("appMode", "normal"))
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", source)
        }
        Log.forest("drawSyncopengreen - 活动: $activityId, 场景: $sceneCode, source: $source")
        return RequestManager.requestString("com.alipay.antiepdrawprod.drawSyncopengreen", "[$requestData]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun receiveTaskAwardopengreen(source: String, sceneCode: String, taskType: String): String {
        val actualSource = if (sceneCode == FOREST_LEYUAN_DAILY_TASK_SCENE_CODE) {
            FOREST_LEYUAN_DAILY_AWARD_SOURCE
        } else {
            source
        }
        val requestData = JSONObject().apply {
            put("ignoreLimit", true)
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", actualSource)
            put("taskType", taskType)
        }
        Log.forest("receiveTaskAwardopengreen - 任务: $taskType, source: $actualSource")
        return RequestManager.requestString("com.alipay.antieptask.receiveTaskAwardopengreen", "[$requestData]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun receiveTaskAwardopengreen(task: JSONObject): String {
        val requestData = JSONObject(task.toString()).apply {
            val bizInfoValue = opt("bizInfo")
            if (bizInfoValue is String && bizInfoValue.isNotBlank()) {
                runCatching { put("bizInfo", JSONObject(bizInfoValue)) }
            }
            if (!has("ignoreLimit")) put("ignoreLimit", true)
            if (!has("requestType")) put("requestType", "RPC")
            if (!has("source") || optString("source").isBlank()) put("source", OPEN_GREEN_RIGHTS_SOURCE)
            if (optString("sceneCode") == FOREST_LEYUAN_DAILY_TASK_SCENE_CODE) {
                put("source", FOREST_LEYUAN_DAILY_AWARD_SOURCE)
            }
        }
        val sceneCode = requestData.optString("sceneCode")
        val source = requestData.optString("source")
        val taskType = requestData.optString("taskType")
        Log.forest("receiveTaskAwardopengreen(raw) - 任务: $taskType, sceneCode: $sceneCode, source: $source")
        return RequestManager.requestString("com.alipay.antieptask.receiveTaskAwardopengreen", "[$requestData]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun batchQueryAndTouchOpenGreen(
        sceneCode: String,
        touchIds: Collection<String>,
        source: String = DEFAULT_SOURCE
    ): String {
        if (touchIds.isEmpty()) {
            return ""
        }
        val paramMap = JSONObject().apply {
            touchIds.filter { it.isNotBlank() }.forEach { touchId ->
                put(touchId, JSONObject())
            }
        }
        val requestData = JSONObject().apply {
            put("paramMap", paramMap)
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", source)
        }
        Log.forest("batchQueryAndTouchOpenGreen - sceneCode: $sceneCode, source: $source, touchIds: ${touchIds.joinToString()}"
        )
        return RequestManager.requestString("com.alipay.antieprights.batchQueryAndTouchopengreen", "[$requestData]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun exchangeTimesFromTaskopengreen(activityId: String, sceneCode: String, source: String, taskSceneCode: String, taskType: String): String {
        val requestData = JSONObject().apply {
            put("activityId", activityId)
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", source)
            put("taskSceneCode", taskSceneCode)
            put("taskType", taskType)
        }
        Log.forest("exchangeTimesFromTaskopengreen - 活动: $activityId, 任务: $taskType, source: $source")
        return RequestManager.requestString("com.alipay.antiepdrawprod.exchangeTimesFromTaskopengreen", "[$requestData]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun finishTask4Chouchoule(taskType: String, sceneCode: String): String {
        val params = JSONObject().apply {
            put("outBizNo", taskType + RandomUtil.getRandomTag())
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            when {
                taskType.contains("XLIGHT") -> put("source", "ADBASICLIB")
                taskType.startsWith("FOREST_ACTIVITY_DRAW") -> put("source", "task_entry")
                else -> put("source", "task_entry")
            }
            put("taskType", taskType)
        }
        Log.forest("finishTask4Chouchoule - 任务: $taskType")
        return RequestManager.requestString("com.alipay.antiep.finishTask", "[$params]")
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun finishTaskopengreen(taskType: String, sceneCode: String): String {
        val params = JSONObject().apply {
            put("outBizNo", taskType + RandomUtil.getRandomTag())
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", "task_entry")
            put("taskType", taskType)
        }
        Log.forest("finishTaskopengreen - 任务: $taskType")
        return RequestManager.requestString("com.alipay.antieptask.finishTaskopengreen", "[$params]")
    }

    @JvmStatic
    fun ecolifeUploadDishImage(operateType: String, imageId: String, conf1: Double, conf2: Double, conf3: Double, dayPoint: String): String {
        return RequestManager.requestString(
            "alipay.ecolife.rpc.h5.uploadDishImage",
            "[{\"channel\":\"ALIPAY\",\"dayPoint\":\"$dayPoint\"," +
                    "\"source\":\"photo-comparison\",\"uploadParamMap\":{\"AIResult\":[{\"conf\":$conf1,\"kvPair\":false," +
                    "\"label\":\"other\",\"pos\":[1.0002995,0.22104378,0.0011976048,0.77727276],\"value\":\"\"}," +
                    "{\"conf\":$conf2,\"kvPair\":false,\"label\":\"guangpan\",\"pos\":[1.0002995,0.22104378,0.0011976048,0.77727276]," +
                    "\"value\":\"\"},{\"conf\":$conf3,\"kvPair\":false,\"label\":\"feiguangpan\"," +
                    "\"pos\":[1.0002995,0.22104378,0.0011976048,0.77727276],\"value\":\"\"}],\"existAIResult\":true,\"imageId\":\"$imageId\"," +
                    "\"imageUrl\":\"https://mdn.alipayobjects.com/afts/img/$imageId/original?bz=APM_20000067\",\"operateType\":\"$operateType\"}}]"
        )
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun giveProp(giveConfigId: String, propId: String, targetUserId: String): String {
        val jo = JSONObject().apply {
            put("giveConfigId", giveConfigId)
            put("propId", propId)
            put("source", "self_corner")
            put("targetUserId", targetUserId)
        }
        return RequestManager.requestString("alipay.antforest.forest.h5.giveProp", JSONArray().put(jo).toString())
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun collectProp(giveConfigId: String, giveId: String): String {
        val jo = JSONObject().apply {
            put("giveConfigId", giveConfigId)
            put("giveId", giveId)
            put("source", "chInfo_ch_appcenter__chsub_9patch")
        }
        return RequestManager.requestString("alipay.antforest.forest.h5.collectProp", JSONArray().put(jo).toString())
    }

    /** 收取能量炸弹卡 */
    @JvmStatic
    @Throws(JSONException::class)
    fun collectBombCardEnergy(propId: String): String {
        val jo = JSONObject().apply {
            put("propId", propId)
            put("source", "chInfo_ch_appcenter__chsub_9patch")
        }
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.collectBombCardEnergy",
            JSONArray().put(jo).toString()
        )
    }

    /** 能量雨机会任务点击游戏，保持 ANTFOREST 点击上下文。 */
    @JvmStatic
    fun clickEnergyRainGame(appId: String): String {
        val requestData = JSONObject().apply {
            put("appId", appId)
            put("bizType", "ANTFOREST")
            put("requestType", "RPC")
            put("sceneCode", "ANTFOREST")
            put("source", "ANTFOREST")
        }
        return RequestManager.requestString(
            "com.alipay.charitygamecenter.clickGame",
            JSONArray().put(requestData).toString()
        )
    }

    /** 模拟点击进入游戏 */
    @JvmStatic
    fun clickGame(appId: String, source: String = FOREST_GAME_CENTER_SOURCE): String {
        val requestData = JSONObject().apply {
            put("appId", appId)
            put("bizType", "ANTFOREST")
            put("requestType", "RPC")
            put("sceneCode", "ANTFOREST")
            put("source", source)
            put("version", currentNativeVersion())
        }
        return requestForestGameCenter("com.alipay.charitygamecenter.clickGame", requestData, source)
    }
}


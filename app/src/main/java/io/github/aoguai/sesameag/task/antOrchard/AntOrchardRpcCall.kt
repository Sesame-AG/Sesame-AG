package io.github.aoguai.sesameag.task.antOrchard

import io.github.aoguai.sesameag.hook.RequestManager
import org.json.JSONArray
import org.json.JSONObject

object AntOrchardRpcCall {
    private const val VERSION = "20260529.01"
    private const val CHARITY_GAME_CENTER_VERSION = "10.8.20"
    private const val ENTRY_SOURCE = "ch_appcenter__chsub_9patch"
    private const val ACTION_SOURCE = "gonggexiguan"
    private const val YEB_SOURCE = "yaoqianshu_qiehuan"
    private const val ANIMAL_SHOW_SOURCE = "ANTORCHARD"

    fun orchardIndex(source: String = ENTRY_SOURCE, wua: String = ""): String {
        val requestData = JSONObject().apply {
            put("appMode", "normal")
            put(
                "commonDegradeResult",
                JSONObject().apply {
                    put("deviceLevel", "high")
                    put("resultReason", 0)
                    put("resultType", 0)
                }
            )
            put(
                "darwinSceneList",
                JSONArray()
                    .put("gameListTwoOptimize")
                    .put("hd_mode")
                    .put("yebTreeTalk")
                    .put("transferPopupYebSwitchMainTree")
                    .put("yebLotteryPlus")
                    .put("teamPlantNewStyle")
                    .put("taskDarwGroup2")
                    .put("awardPreviewExp")
                    .put("unifiedVoucher")
                    .put("indexFeedsAB")
                    .put("goldenBeanSwiper")
                    .put("quickWaterOptimize")
                    .put("hideLeafCollectAniAB")
                    .put("farmButlerRouteBubble")
            )
            put("growthExtInfo", "")
            put("growthTask", "")
            put("inHomepage", true)
            put("requestType", "NORMAL")
            put("sceneCode", "ORCHARD")
            put("source", source)
            put("version", VERSION)
            if (source == YEB_SOURCE && wua.isNotBlank()) {
                put("useWua", true)
                put("wua", wua)
            } else {
                put("useWua", "")
            }
        }
        return RequestManager.requestString(
            "com.alipay.antfarm.orchardIndex",
            JSONArray().put(requestData).toString()
        )
    }

    /**
     * 获取额外信息（包含每日肥料、施肥礼盒）
     * @param from 来源：entry(首页), water(施肥后)
     */
    fun extraInfoGet(from: String = "entry", source: String = ENTRY_SOURCE): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.extraInfoGet",
            "[{\"from\":\"$from\",\"requestType\":\"NORMAL\",\"sceneCode\":\"FUGUO\",\"source\":\"$source\",\"version\":\"$VERSION\"}]"
        )
    }

    fun refinedOperation(actionId: String, source: String = ACTION_SOURCE): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.refinedOperation",
            "[{\"actionId\":\"$actionId\",\"appMode\":\"normal\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$source\",\"version\":\"$VERSION\"}]"
        )
    }

    fun extraInfoSet(source: String = ENTRY_SOURCE): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.extraInfoSet",
            "[{\"bizCode\":\"fertilizerPacket\",\"bizParam\":{\"action\":\"queryCollectFertilizerPacket\"},\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$source\",\"version\":\"$VERSION\"}]"
        )
    }

    // 修改：增加 LIMITED_TIME_CHALLENGE 和 LOTTERY_PLUS 类型
    fun querySubplotsActivity(treeLevel: String): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.querySubplotsActivity",
            "[{\"activityType\":[\"WISH\",\"BATTLE\",\"HELP_FARMER\",\"DEFOLIATION\",\"CAMP_TAKEOVER\",\"LIMITED_TIME_CHALLENGE\",\"LOTTERY_PLUS\"],\"appMode\":\"normal\",\"inHomepage\":true,\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$ENTRY_SOURCE\",\"treeLevel\":\"$treeLevel\",\"version\":\"$VERSION\"}]"
        )
    }

    fun triggerSubplotsActivity(activityId: String, activityType: String, optionKey: String): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.triggerSubplotsActivity",
            "[{\"activityId\":\"$activityId\",\"activityType\":\"$activityType\",\"optionKey\":\"$optionKey\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$ENTRY_SOURCE\",\"version\":\"$VERSION\"}]"
        )
    }

    fun receiveOrchardRights(activityId: String, activityType: String): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.receiveOrchardRights",
            "[{\"activityId\":\"$activityId\",\"activityType\":\"$activityType\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$ENTRY_SOURCE\",\"version\":\"$VERSION\"}]"
        )
    }

    /* 七日礼包 */
    fun drawLottery(): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.drawLottery",
            "[{\"lotteryScene\":\"receiveLotteryPlus\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$ENTRY_SOURCE\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * 切换种植场景
     * @param plantScene main(果树) 或 yeb(摇钱树)
     */
    fun switchPlantScene(plantScene: String, source: String = ENTRY_SOURCE): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.switchPlantScene",
            "[{\"plantScene\":\"$plantScene\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$source\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * 施肥
     * @param wua 用户标识
     * @param source 来源标识，可自定义
     * @param useBatchSpread 一键5次
     * @param plantScene 场景：main 或 yeb
     */
    fun orchardSpreadManure(wua: String, source: String, useBatchSpread: Boolean = false, plantScene: String = "main"): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.orchardSpreadManure",
            "[{\"plantScene\":\"$plantScene\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$source\",\"useBatchSpread\":$useBatchSpread,\"version\":\"$VERSION\",\"wua\":\"$wua\"}]"
        )
    }

    fun receiveTaskAward(
        sceneCode: String,
        taskType: String,
        source: String = ACTION_SOURCE,
        ignoreLimit: Boolean = false
    ): String {
        return RequestManager.requestString(
            "com.alipay.antiep.receiveTaskAward",
            "[{\"ignoreLimit\":$ignoreLimit,\"requestType\":\"NORMAL\",\"sceneCode\":\"$sceneCode\",\"source\":\"$source\",\"taskType\":\"$taskType\",\"version\":\"$VERSION\"}]"
        )
    }

    fun queryOptionalPlay(): String {
        val requestData = JSONObject().apply {
            put("bizType", "ANTORCHARD")
            put(
                "commonDegradeFilterRequest",
                JSONObject().apply {
                    put("appMode", "normal")
                    put("deviceLevel", "high")
                    put("h5Version", VERSION)
                    put("unityDeviceLevel", "high")
                }
            )
            put("playTypeList", JSONArray().put("TOP_UP_COUPON").put("TASK_TRIGGER"))
            put("requestType", "RPC")
            put("sceneCode", "ORCHARD")
            put("source", ACTION_SOURCE)
            put("version", CHARITY_GAME_CENTER_VERSION)
        }
        return RequestManager.requestString(
            "com.alipay.charitygamecenter.queryOptionalPlay",
            JSONArray().put(requestData).toString()
        )
    }

    fun receiveTaskAwardAntOrchard(
        sceneCode: String,
        taskType: String,
        awardCountForReceive: Int,
        source: String = "antorchard"
    ): String {
        val requestData = JSONObject().apply {
            put("awardCountForReceive", awardCountForReceive)
            put("ignoreLimit", true)
            put("requestType", "RPC")
            put("sceneCode", sceneCode)
            put("source", source)
            put("taskType", taskType)
            put("version", VERSION)
        }
        return RequestManager.requestString(
            "com.alipay.antieptask.receiveTaskAwardantorchard",
            JSONArray().put(requestData).toString()
        )
    }

    fun orchardListTask(source: String = ENTRY_SOURCE): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.orchardListTask",
            "[{\"addWidget\":false,\"appMode\":\"normal\",\"enableSwitchSceneList\":[\"main\",\"yeb\"],\"enableTeamType\":[\"help\",\"team\"],\"hasYebActivityEntrance\":true,\"plantHiddenMMC\":\"false\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$source\",\"version\":\"$VERSION\"}]"
        )
    }

    fun orchardSign(): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.orchardSign",
            "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"signScene\":\"ANTFARM_ORCHARD_SIGN_V2\",\"source\":\"$ENTRY_SOURCE\",\"version\":\"$VERSION\"}]"
        )
    }

    fun queryAnimalShowInfo(userId: String): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.queryAnimalShowInfo",
            "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$ANIMAL_SHOW_SOURCE\",\"userId\":\"$userId\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * 收取「庄园鸡屎/肥料」(芭芭农场里的肥料池)。
     */
    fun collectManurePot(
        manurePotNOs: String,
        source: String = ACTION_SOURCE
    ): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.collectManurePot",
            "[{\"manurePotNOs\":\"$manurePotNOs\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$source\",\"version\":\"$VERSION\"}]"
        )
    }

    fun finishTask(
        userId: String,
        sceneCode: String,
        taskType: String,
        source: String = ENTRY_SOURCE
    ): String {
        return RequestManager.requestString(
            "com.alipay.antiep.finishTask",
            "[{\"outBizNo\":\"${userId}${System.currentTimeMillis()}\",\"requestType\":\"NORMAL\",\"sceneCode\":\"$sceneCode\",\"source\":\"$source\",\"taskType\":\"$taskType\",\"userId\":\"$userId\",\"version\":\"$VERSION\"}]"
        )
    }

    fun triggerTbTask(
        taskId: String,
        taskPlantType: String,
        source: String = ENTRY_SOURCE
    ): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.triggerTbTask",
            "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$source\",\"taskId\":\"$taskId\",\"taskPlantType\":\"$taskPlantType\",\"version\":\"$VERSION\"}]"
        )
    }

    fun orchardLazyIndex(
        currentPlantScene: String = "main",
        source: String = ENTRY_SOURCE
    ): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.orchardLazyIndex",
            "[{\"appMode\":\"normal\",\"currentPlantScene\":\"$currentPlantScene\",\"hasWaitExchange\":false,\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"$source\",\"version\":\"$VERSION\"}]"
        )
    }

    fun orchardSimple(source: String, version: String = VERSION): String {
        val requestData = JSONObject().apply {
            put("requestType", "NORMAL")
            put("sceneCode", "ORCHARD")
            put("source", source)
            put("version", version)
        }
        return RequestManager.requestString(
            "com.alipay.antorchard.orchardSimple",
            JSONArray().put(requestData).toString()
        )
    }

    //砸蛋
    fun smashedGoldenEgg(count: Int): String {
        val jsonArgs = """
        [
            {
                "batchSmashCount": $count,
                "requestType": "NORMAL",
                "sceneCode": "ORCHARD",
                "source": "$ENTRY_SOURCE",
                "version": "$VERSION"
            }
        ]
    """.trimIndent()

        return RequestManager.requestString(
            "com.alipay.antorchard.smashedGoldenEgg",
            jsonArgs
        )
    }

    /**
     * 收取果园回访奖励
     * @param diversionSource 引流来源（如：widget、tmall）
     * @param source 具体来源（如：widget_shoufei、upgrade_tmall_exchange_task）
     * @return 请求结果字符串
     */
    fun receiveOrchardVisitAward(
        diversionSource: String,
        source: String
    ): String {
        val requestParams = """
        [{"diversionSource":"$diversionSource",
          "requestType":"NORMAL",
          "sceneCode":"ORCHARD",
          "source":"$source",
          "version":"$VERSION"}]
    """.trimIndent()

        return RequestManager.requestString(
            "com.alipay.antorchard.receiveOrchardVisitAward",
            requestParams
        )
    }

    fun orchardSyncIndex(
        wua: String,
        syncIndexTypes: String = "LIMITED_TIME_CHALLENGE",
        balloonScene: String? = null,
        source: String = ACTION_SOURCE
    ): String {
        val requestData = JSONObject().apply {
            put("appMode", "normal")
            put("requestType", "NORMAL")
            put("sceneCode", "ORCHARD")
            put("source", source)
            put("syncIndexTypes", syncIndexTypes)
            put("useWua", true)
            put("version", VERSION)
            put("wua", wua)
            if (!balloonScene.isNullOrBlank()) {
                put("balloonScene", balloonScene)
            }
        }

        return RequestManager.requestString(
            "com.alipay.antorchard.orchardSyncIndex",
            JSONArray().put(requestData).toString()
        )
    }

    fun noticeGame(appId: String): String {
        val jsonArgs = """
          [{
             "appId": "$appId",
             "requestType": "NORMAL",
             "sceneCode": "ORCHARD",
             "source": "$ACTION_SOURCE",
             "version": "$VERSION"
         }]
    """.trimIndent()

        return RequestManager.requestString(
            "com.alipay.antorchard.noticeGame",
            jsonArgs
        )
    }

    fun achieveBeShareP2P(shareId: String): String {
        return RequestManager.requestString(
            "com.alipay.antiep.achieveBeShareP2P",
            "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ANTFARM_ORCHARD_SHARE_P2P\",\"shareId\":\"$shareId\",\"source\":\"share\",\"version\":\"$VERSION\"}]"
        )
    }

    /* 摇钱树收余额奖励 */
    fun moneyTreeTrigger(): String {
        return RequestManager.requestString(
            "com.alipay.yebbffweb.needle.yebHome.moneyTree.trigger",
            "[{\"sceneType\":\"default\",\"type\":\"trigger\"}]"
        )
    }
}


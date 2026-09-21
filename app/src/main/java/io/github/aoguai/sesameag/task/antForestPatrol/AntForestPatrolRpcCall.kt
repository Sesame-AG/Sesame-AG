package io.github.aoguai.sesameag.task.antForestPatrol

import io.github.aoguai.sesameag.entity.RpcEntity
import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.task.antForest.AntForestRpcCall
import io.github.aoguai.sesameag.util.RandomUtil
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object AntForestPatrolRpcCall {
    private const val PATROL_SOURCE = "ant_forest"
    private const val PATROL_TIMEZONE = "Asia/Shanghai"
    private const val PATROL_GO_VERSION = "20231123"

    private fun buildPatrolPayload(block: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().apply {
            put("source", PATROL_SOURCE)
            put("timezoneId", PATROL_TIMEZONE)
            block()
        }

    private fun requestPatrol(
        method: String,
        payload: JSONObject,
    ): String {
        return RequestManager.requestString(
            RpcEntity(
                method,
                JSONArray().put(payload).toString(),
                headers = mapOf("source" to PATROL_SOURCE, "ags-source" to PATROL_SOURCE),
            ),
        )
    }

    fun queryMonopolyEntryInfo(): String = RequestManager.requestString(
        "alipay.antisle.monopoly.h5.queryMonopolyEntryInfo",
        JSONArray().put(JSONObject()
            .put("source", "monopoly_home_popup")
            .put("uniqueId", RandomUtil.getRandomTag())).toString(),
    )

    fun triggerMonopolyHomeProps(): String = RequestManager.requestString(
        "alipay.antisle.monopoly.h5.triggerHomePageProps",
        JSONArray().put(JSONObject()
            .put("source", "monopoly_home_popup")
            .put("uniqueId", RandomUtil.getRandomTag())).toString(),
    )

    fun rollMonopolyDice(guideRoll: Boolean): String = RequestManager.requestString(
        "alipay.antisle.monopoly.h5.rollDice",
        JSONArray().put(JSONObject()
            .put("source", "monopoly_home_popup")
            .put("uniqueId", RandomUtil.getRandomTag())
            .apply {
                if (guideRoll) put("extParams", JSONObject()
                    .put("guideRoll", true).put("source", "newUserGuide").toString())
            }).toString(),
    )

    fun confirmMonopolyEvent(eventId: String, actionKey: String): String = RequestManager.requestString(
        "alipay.antisle.monopoly.h5.eventConfirm",
        JSONArray().put(JSONObject()
            .put("source", "monopoly_home_popup")
            .put("uniqueId", RandomUtil.getRandomTag())
            .put("eventId", eventId)
            .put("status", "CONFIRMED")
            .put("decisionData", JSONObject().put("actionKey", actionKey).put("source", "ROLL_DICE_STEP"))).toString(),
    )

    @JvmStatic
    @Throws(JSONException::class)
    fun queryUserPatrol(): String = requestPatrol("alipay.antforest.forest.h5.queryUserPatrol", buildPatrolPayload())

    @JvmStatic
    @Throws(JSONException::class)
    fun queryMyPatrolRecord(): String = requestPatrol("alipay.antforest.forest.h5.queryMyPatrolRecord", buildPatrolPayload())

    @JvmStatic
    @Throws(JSONException::class)
    fun switchUserPatrol(targetPatrolId: String): String {
        val jo =
            buildPatrolPayload {
                put("targetPatrolId", targetPatrolId)
            }
        return requestPatrol("alipay.antforest.forest.h5.switchUserPatrol", jo)
    }

    @JvmStatic
    fun patrolGo(
        nodeIndex: Int,
        patrolId: Int,
    ): String =
        requestPatrol(
            "alipay.antforest.forest.h5.patrolGo",
            buildPatrolPayload {
                put("nodeIndex", nodeIndex)
                put("patrolId", patrolId)
                put("version", PATROL_GO_VERSION)
            },
        )

    @JvmStatic
    fun patrolKeepGoing(
        nodeIndex: Int,
        patrolId: Int,
        eventType: String,
    ): String {
        val reactParam =
            when (eventType) {
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
                put("version", PATROL_GO_VERSION)
            },
        )
    }

    @JvmStatic
    fun exchangePatrolChance(costStep: Int): String =
        requestPatrol(
            "alipay.antforest.forest.h5.exchangePatrolChance",
            buildPatrolPayload {
                put("costStep", costStep)
            },
        )

    @JvmStatic
    fun queryAnimalAndPiece(
        animalId: Int,
        patrolId: Int = 0,
    ): String {
        val jo =
            buildPatrolPayload {
                when {
                    patrolId > 0 -> {
                        put("patrolId", patrolId)
                        put("withDetail", "N")
                    }

                    animalId != 0 -> {
                        put("animalId", animalId)
                        // 最新巡护合成链路要求按动物定向查询时省略 withDetail，
                        // 服务端才会返回稳定的 propIdList。
                    }

                    else -> {
                        put("withDetail", "N")
                        put("withGift", true)
                    }
                }
            }
        return requestPatrol("alipay.antforest.forest.h5.queryAnimalAndPiece", jo)
    }

    @JvmStatic
    fun combineAnimalPiece(
        animalId: Int,
        piecePropIds: String,
    ): String =
        requestPatrol(
            "alipay.antforest.forest.h5.combineAnimalPiece",
            buildPatrolPayload {
                put("animalId", animalId)
                put("piecePropIds", JSONArray(piecePropIds))
            },
        )

    @JvmStatic
    @Throws(JSONException::class)
    fun queryAnimalPropList(): String {
        val jo =
            JSONObject().apply {
                put("source", "chInfo_ch_appcenter__chsub_9patch")
            }
        return RequestManager.requestString("alipay.antforest.forest.h5.queryAnimalPropList", JSONArray().put(jo).toString())
    }

    fun consumeAnimalProp(propGroup: String, propType: String): String = AntForestRpcCall.consumeProp(
        propGroup, "", propType, false,
        AntForestRpcCall.PropConsumeContext(source = PATROL_SOURCE, propGroup = propGroup),
    )

    fun collectAnimalRobEnergy(propId: String, propType: String, shortDay: String): String {
        val context = AntForestRpcCall.PropConsumeContext(source = "chInfo_ch_appcenter__chsub_9patch")
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.collectAnimalRobEnergy",
            JSONArray().put(JSONObject().put("propId", propId).put("propType", propType)
                .put("shortDay", shortDay).put("source", context.source).put("version", context.version)).toString(),
        )
    }

    fun queryUsingCreatureInfo(uid: String): String = RequestManager.requestString(
        "alipay.antisle.monopoly.h5.queryUsingCreatureInfo",
        JSONArray().put(JSONObject().put("source", "chInfo_ch_appcenter__chsub_9patch")
            .put("targetUserId", uid).put("uniqueId", RandomUtil.getRandomTag()).put("version", "20260623")).toString(),
    )

    fun collectMonopolyCreatureEnergy(creatureCode: String, shortDay: String): String = RequestManager.requestString(
        "alipay.antisle.monopoly.h5.collectMonopolyCreatureEnergy",
        JSONArray().put(JSONObject().put("creatureCode", creatureCode).put("shortDay", shortDay)
            .put("source", "chInfo_ch_appcenter__chsub_9patch").put("uniqueId", RandomUtil.getRandomTag())).toString(),
    )

    fun assignMonopolyCreature(creatureCode: String): String = RequestManager.requestString(
        "alipay.antisle.monopoly.h5.assignMonopolyCreature",
        JSONArray().put(JSONObject().put("creatureCode", creatureCode).put("secondConfirm", false)
            .put("source", "monopoly_home_popup").put("uniqueId", RandomUtil.getRandomTag())).toString(),
    )

    fun listMonopolyTasks(regionCode: String, sceneCode: String): String = RequestManager.requestString(
        "com.alipay.antieptask.listTaskopengreen",
        JSONArray().put(JSONObject().put("regionCode", regionCode).put("sceneCode", sceneCode)
            .put("source", "ANTFOREST").put("requestType", "RPC").put("zoneId", PATROL_TIMEZONE)
            .put("uniqueId", RandomUtil.getRandomTag())).toString(),
    )

    fun finishMonopolyTask(taskType: String, sceneCode: String): String = RequestManager.requestString(
        "com.alipay.antieptask.finishTaskopengreen",
        JSONArray().put(JSONObject().put("taskType", taskType).put("sceneCode", sceneCode)
            .put("source", "ANTFOREST").put("requestType", "H5")
            .put("outBizNo", "${taskType}_${System.currentTimeMillis()}_${RandomUtil.getRandomTag()}")).toString(),
    )

    fun receiveMonopolyTask(taskType: String, sceneCode: String): String = RequestManager.requestString(
        "com.alipay.antieptask.receiveTaskAwardopengreen",
        JSONArray().put(JSONObject().put("taskType", taskType).put("sceneCode", sceneCode)
            .put("source", "ANTFOREST").put("requestType", "RPC").put("ignoreLimit", false)).toString(),
    )

    fun queryCertificate(projectId: String): String = RequestManager.requestString(
        "alipay.antforest.forest.h5.queryTreeForExchange",
        JSONArray().put(JSONObject().put("projectId", projectId).put("source", "monopoly_auto_exchange")
            .put("version", "20240704")).toString(),
    )

    fun exchangeCertificate(projectId: Long): String = RequestManager.requestString(
        "alipay.antmember.forest.h5.exchangeTree",
        JSONArray().put(JSONObject().put("projectId", projectId).put("source", "monopoly_auto_exchange")
            .put("sToken", System.currentTimeMillis().toString()).put("userManualSelect", false)
            .put("version", "20230501")).toString(),
    )
}

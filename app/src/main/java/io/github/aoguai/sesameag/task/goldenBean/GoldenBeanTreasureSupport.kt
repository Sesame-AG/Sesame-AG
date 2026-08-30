package io.github.aoguai.sesameag.task.goldenBean

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TaskBlacklist
import org.json.JSONArray
import org.json.JSONObject

internal data class GoldenBeanManureExchangePlan(
    val reservedManure: Int,
    val exchangedToday: Int,
)

internal data class GoldenBeanSesameExchangePlan(
    val exchangeBeanAmount: Int,
    val exchangedToday: Int,
)

private data class GoldenBeanGameCandidate(
    val appId: String,
    val taskId: String,
    val rightTimes: Int,
    val rightTimesLimit: Int,
)

private data class GoldenBeanGameSnapshot(
    val drawRights: JSONObject,
    val candidates: List<GoldenBeanGameCandidate>,
)

internal data class GoldenBeanGameFlowResult(
    val completed: Boolean,
    val progressed: Boolean,
    val blocked: Boolean = false,
)

internal object GoldenBeanTreasureSupport {
    private val unsupportedCodes = setOf("400000040")
    private val invalidCodes = setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT")
    private val retryableCodes = setOf("3000", "REMOTE_INVOKE_EXCEPTION")

    internal fun parseResponse(response: String): JSONObject? =
        response.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { JSONObject(raw) }
                .onFailure { Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝响应解析失败 raw=$raw") }
                .getOrNull()
        }

    internal fun isSuccess(response: JSONObject): Boolean {
        if (response.optBoolean("success", false)) {
            return true
        }
        return response.optString("resultCode") in setOf("100", "SUCCESS") ||
            response.optString("code") == "100000000"
    }

    internal fun planSesameExchange(
        indexResponse: JSONObject,
        configuredDailyBeanAmount: Int,
    ): GoldenBeanSesameExchangePlan? {
        if (configuredDailyBeanAmount == 0) {
            return null
        }

        val exchangeInfo = requireSesameExchangeInfo(indexResponse, "计划") ?: return null
        val beanReward = exchangeInfo.optInt("beanReward", 0)
        val currentManure = exchangeInfo.optInt("currentManure", 0)
        val effectiveExchangeManure = exchangeInfo.optInt("effectiveExchangeManure", 0)
        val minExchangeAmount = exchangeInfo.optInt("minExchangeAmount", 0)
        val remainQuota = exchangeInfo.optInt("remainQuota", 0)
        val exchangedToday =
            Status.getIntFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_ZHIMA_EXCHANGE_BEAN_AMOUNT) ?: 0
        val configuredRemaining =
            if (configuredDailyBeanAmount > 0) configuredDailyBeanAmount - exchangedToday else Int.MAX_VALUE
        val availableBeans = minOf(
            remainQuota.toLong(),
            currentManure.toLong() * beanReward.toLong(),
            effectiveExchangeManure.toLong() * beanReward.toLong(),
            configuredRemaining.toLong(),
        ).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        Log.goldenBean(
            "金豆夺宝芝麻粒换豆资格 pageOpened=${exchangeInfo.optBoolean("pageOpened")} " +
                "currentSesameGrain=$currentManure effectiveSesameGrain=$effectiveExchangeManure " +
                "beanReward=$beanReward minExchangeAmount=$minExchangeAmount remainQuota=$remainQuota " +
                "configuredDailyBeanAmount=$configuredDailyBeanAmount exchangedToday=$exchangedToday " +
                "availableBeans=$availableBeans",
        )
        if (!exchangeInfo.optBoolean("pageOpened") || beanReward <= 0 || minExchangeAmount <= 0) {
            Log.goldenBean("金豆夺宝芝麻粒换豆[BUSINESS_LIMIT] 服务端资格未满足")
            return null
        }
        if (availableBeans < minExchangeAmount) {
            Log.goldenBean("金豆夺宝芝麻粒换豆[BUSINESS_LIMIT] 可兑换金豆不足最低兑换量")
            return null
        }
        return GoldenBeanSesameExchangePlan(availableBeans, exchangedToday)
    }

    internal fun exchangePlannedSesame(
        indexResponse: JSONObject,
        plan: GoldenBeanSesameExchangePlan,
    ): Boolean {
        requireSesameExchangeInfo(indexResponse, "执行") ?: return false
        val exchangeResponse =
            parseResponse(GoldenBeanRpcCall.manureExchange(plan.exchangeBeanAmount, GoldenBeanRpcCall.ZHIMA_ENTRY))
        if (exchangeResponse == null || !isSuccess(exchangeResponse)) {
            logManureExchangeFailure("zhimaManureExchange", exchangeResponse)
            return false
        }
        val beanDelta = exchangeResponse.optInt("beanDelta", 0)
        if (beanDelta <= 0) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝芝麻粒换豆 classification=UNKNOWN_NEEDS_REVIEW 响应缺少有效beanDelta raw=$exchangeResponse",
            )
            return false
        }

        val syncResponse =
            parseResponse(
                GoldenBeanRpcCall.sync(
                    listOf("JAR_INFO", "EXCHANGE_MANURE", "TASK_LIST"),
                    GoldenBeanRpcCall.ZHIMA_ENTRY,
                ),
            )
        if (syncResponse == null || !isSuccess(syncResponse)) {
            logManureExchangeFailure("zhimaSync", syncResponse)
            return false
        }
        if (!hasSesameExchangeProgress(indexResponse, syncResponse)) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝芝麻粒换豆 classification=UNKNOWN_NEEDS_REVIEW " +
                    "请求成功但同步未确认芝麻粒、配额、金豆罐或任务状态推进 raw=$syncResponse",
            )
            return false
        }

        Status.setIntFlagToday(
            StatusFlags.FLAG_GOLDEN_BEAN_ZHIMA_EXCHANGE_BEAN_AMOUNT,
            plan.exchangedToday + beanDelta,
        )
        val afterInfo = syncResponse.optJSONObject("manureExchangeInfo")
        Log.goldenBean(
            "金豆夺宝芝麻粒兑换成功并完成服务端回查 requested=${plan.exchangeBeanAmount} " +
                "beanDelta=$beanDelta sesameCost=${exchangeResponse.optInt("manureCost", -1)} " +
                "remainSesameGrain=${afterInfo?.optInt("currentManure", -1)} " +
                "remainQuota=${afterInfo?.optInt("remainQuota", -1)}",
        )
        return true
    }

    internal fun planManureExchange(
        indexResponse: JSONObject,
        configuredDailyReserveAmount: Int,
    ): GoldenBeanManureExchangePlan? {
        if (configuredDailyReserveAmount == 0) {
            return null
        }

        val exchangeInfo = requireManureExchangeInfo(indexResponse, "预留")
            ?: return null
        val farmOpened = exchangeInfo.optBoolean("farmOpened")
        val pageOpened = exchangeInfo.optBoolean("pageOpened")
        val taobaoBinding = exchangeInfo.optBoolean("taobaoBinding")
        val currentManure = exchangeInfo.optInt("currentManure", 0)
        val effectiveExchangeManure = exchangeInfo.optInt("effectiveExchangeManure", 0)
        val minExchangeAmount = exchangeInfo.optInt("minExchangeAmount", 0)
        val remainQuota = exchangeInfo.optInt("remainQuota", 0)
        val exchangedToday =
            Status.getIntFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_MANURE_EXCHANGE_AMOUNT) ?: 0
        val reservedManure =
            when {
                configuredDailyReserveAmount == -1 -> minOf(effectiveExchangeManure, remainQuota)
                configuredDailyReserveAmount > 0 -> configuredDailyReserveAmount - exchangedToday
                else -> {
                    Log.error(
                        GOLDEN_BEAN_BLACKLIST_MODULE,
                        "金豆夺宝肥料换豆 classification=UNKNOWN_NEEDS_REVIEW " +
                            "配置额度=$configuredDailyReserveAmount 不受支持",
                    )
                    return null
                }
            }

        Log.goldenBean(
            "金豆夺宝肥料换豆预留资格 farmOpened=$farmOpened " +
                "pageOpened=$pageOpened " +
                "taobaoBinding=$taobaoBinding " +
                "currentManure=$currentManure " +
                "effectiveExchangeManure=$effectiveExchangeManure " +
                "minExchangeAmount=$minExchangeAmount " +
                "remainQuota=$remainQuota " +
                "configuredDailyReserveAmount=$configuredDailyReserveAmount " +
                "exchangedToday=$exchangedToday reservedManure=$reservedManure",
        )

        if (!farmOpened || !pageOpened || !taobaoBinding) {
            Log.goldenBean("金豆夺宝肥料换豆[BUSINESS_LIMIT] 服务端资格未满足")
            return null
        }
        if (minExchangeAmount <= 0) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝肥料换豆 classification=UNKNOWN_NEEDS_REVIEW 服务端最低兑换量无效=$minExchangeAmount",
            )
            return null
        }
        if (reservedManure <= 0) {
            Log.goldenBean("金豆夺宝肥料换豆[USER_LIMIT] 今日配置额度已用完")
            return null
        }
        if (reservedManure < minExchangeAmount) {
            val classification =
                if (configuredDailyReserveAmount > 0) "USER_CONFIGURATION" else "BUSINESS_LIMIT"
            Log.goldenBean(
                "金豆夺宝肥料换豆[$classification] 预留${reservedManure}低于服务端最小兑换量$minExchangeAmount，本轮不预留也不换豆",
            )
            return null
        }
        if (currentManure < reservedManure ||
            effectiveExchangeManure < reservedManure ||
            remainQuota < reservedManure
        ) {
            Log.goldenBean(
                "金豆夺宝肥料换豆[USER_RESERVE_UNMET] 当前肥料或服务端可兑换额度不足预留$reservedManure，" +
                    "本轮不预留也不换豆",
            )
            return null
        }

        return GoldenBeanManureExchangePlan(
            reservedManure = reservedManure,
            exchangedToday = exchangedToday,
        )
    }

    internal fun exchangePlannedManure(
        indexResponse: JSONObject,
        plan: GoldenBeanManureExchangePlan,
    ): Boolean {
        val exchangeInfo = requireManureExchangeInfo(indexResponse, "最终兑换")
            ?: return false
        val farmOpened = exchangeInfo.optBoolean("farmOpened")
        val pageOpened = exchangeInfo.optBoolean("pageOpened")
        val taobaoBinding = exchangeInfo.optBoolean("taobaoBinding")
        val currentManure = exchangeInfo.optInt("currentManure", 0)
        val effectiveExchangeManure = exchangeInfo.optInt("effectiveExchangeManure", 0)
        val minExchangeAmount = exchangeInfo.optInt("minExchangeAmount", 0)
        val remainQuota = exchangeInfo.optInt("remainQuota", 0)

        if (!farmOpened || !pageOpened || !taobaoBinding) {
            Log.goldenBean("金豆夺宝肥料换豆[BUSINESS_LIMIT] 最终回查资格未满足")
            return false
        }
        if (minExchangeAmount <= 0) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝肥料换豆 classification=UNKNOWN_NEEDS_REVIEW 最终回查最低兑换量无效=$minExchangeAmount",
            )
            return false
        }
        if (plan.reservedManure < minExchangeAmount ||
            currentManure < plan.reservedManure ||
            effectiveExchangeManure < plan.reservedManure ||
            remainQuota < plan.reservedManure
        ) {
            Log.goldenBean(
                "金豆夺宝肥料换豆[BUSINESS_LIMIT] 最终回查无法满足预留${plan.reservedManure}，不发送兑换请求",
            )
            return false
        }

        val exchangeResponse = parseResponse(GoldenBeanRpcCall.manureExchange(plan.reservedManure))
        if (exchangeResponse == null || !isSuccess(exchangeResponse)) {
            logManureExchangeFailure("manureExchange", exchangeResponse)
            return false
        }

        val syncResponse =
            parseResponse(
                GoldenBeanRpcCall.sync(
                    listOf("JAR_INFO", "EXCHANGE_MANURE", "TASK_LIST"),
                    GoldenBeanRpcCall.MASTER_ENTRY,
                ),
            )
        if (syncResponse == null || !isSuccess(syncResponse)) {
            logManureExchangeFailure("sync", syncResponse)
            return false
        }

        if (hasManureExchangeProgress(indexResponse, syncResponse)) {
            val afterInfo = syncResponse.optJSONObject("manureExchangeInfo")
            val confirmedManureCost = resolveConfirmedManureCost(exchangeResponse, indexResponse, syncResponse)
            if (confirmedManureCost != null) {
                Status.setIntFlagToday(
                    StatusFlags.FLAG_GOLDEN_BEAN_MANURE_EXCHANGE_AMOUNT,
                    plan.exchangedToday + confirmedManureCost,
                )
            } else {
                Log.error(
                    GOLDEN_BEAN_BLACKLIST_MODULE,
                    "金豆夺宝肥料兑换 classification=UNKNOWN_NEEDS_REVIEW " +
                        "服务端已确认状态推进但缺少可计量肥料消耗 raw=$syncResponse",
                )
                return false
            }
            Log.goldenBean(
                "金豆夺宝肥料兑换成功并完成服务端回查 amount=${plan.reservedManure} " +
                    "manureCost=${confirmedManureCost ?: "UNKNOWN"} " +
                    "remainManure=${afterInfo?.optInt("currentManure", -1)} " +
                    "remainQuota=${afterInfo?.optInt("remainQuota", -1)} " +
                    "taskStatus=${findManureExchangeTaskStatus(syncResponse) ?: "UNKNOWN"}",
            )
            return true
        } else {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝肥料兑换 classification=UNKNOWN_NEEDS_REVIEW " +
                    "请求成功但同步未确认肥料、配额、金豆罐或任务状态推进 " +
                    "amount=${plan.reservedManure} raw=$syncResponse",
            )
        }
        return false
    }

    private fun requireSesameExchangeInfo(
        indexResponse: JSONObject,
        phase: String,
    ): JSONObject? {
        val exchangeInfo = indexResponse.optJSONObject("manureExchangeInfo")
        if (exchangeInfo == null) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝芝麻粒换豆 classification=UNKNOWN_NEEDS_REVIEW $phase 缺少manureExchangeInfo raw=$indexResponse",
            )
            return null
        }
        val requiredFields = listOf(
            "pageOpened",
            "currentManure",
            "effectiveExchangeManure",
            "beanReward",
            "minExchangeAmount",
            "remainQuota",
        )
        val missingField = requiredFields.firstOrNull { !exchangeInfo.has(it) }
        if (missingField != null) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝芝麻粒换豆 classification=UNKNOWN_NEEDS_REVIEW $phase 缺少字段=$missingField raw=$exchangeInfo",
            )
            return null
        }
        return exchangeInfo
    }

    private fun hasSesameExchangeProgress(
        beforeResponse: JSONObject,
        afterResponse: JSONObject,
    ): Boolean {
        val beforeInfo = beforeResponse.optJSONObject("manureExchangeInfo") ?: return false
        val afterInfo = afterResponse.optJSONObject("manureExchangeInfo") ?: return false
        val grainProgressed = listOf("currentManure", "effectiveExchangeManure", "remainQuota").any { field ->
            afterInfo.optInt(field, Int.MAX_VALUE) < beforeInfo.optInt(field, Int.MIN_VALUE)
        }
        if (grainProgressed) {
            return true
        }
        val beforeJar = beforeResponse.optJSONObject("jarInfo")?.optInt("currentProgress", -1) ?: -1
        val afterJar = afterResponse.optJSONObject("jarInfo")?.optInt("currentProgress", -1) ?: -1
        if (beforeJar >= 0 && afterJar > beforeJar) {
            return true
        }
        val beforeStatus = findManureExchangeTaskStatus(beforeResponse)
        val afterStatus = findManureExchangeTaskStatus(afterResponse)
        return beforeStatus != null && afterStatus != null && beforeStatus != afterStatus &&
            afterStatus in setOf("FINISHED", "TO_RECEIVE", "RECEIVED", "DONE")
    }

    private fun requireManureExchangeInfo(
        indexResponse: JSONObject,
        phase: String,
    ): JSONObject? {
        val exchangeInfo = indexResponse.optJSONObject("manureExchangeInfo")
        if (exchangeInfo == null) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝肥料兑换 classification=UNKNOWN_NEEDS_REVIEW $phase 缺少manureExchangeInfo raw=$indexResponse",
            )
            return null
        }

        val requiredFields =
            listOf(
                "farmOpened",
                "pageOpened",
                "taobaoBinding",
                "currentManure",
                "effectiveExchangeManure",
                "minExchangeAmount",
                "remainQuota",
            )
        val missingField = requiredFields.firstOrNull { !exchangeInfo.has(it) }
        if (missingField != null) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝肥料兑换 classification=UNKNOWN_NEEDS_REVIEW $phase 缺少字段=$missingField raw=$exchangeInfo",
            )
            return null
        }
        return exchangeInfo
    }

    private fun resolveConfirmedManureCost(
        exchangeResponse: JSONObject,
        beforeResponse: JSONObject,
        afterResponse: JSONObject,
    ): Int? {
        val responseCost = exchangeResponse.optInt("manureCost", 0)
        if (responseCost > 0) {
            return responseCost
        }

        val beforeInfo = beforeResponse.optJSONObject("manureExchangeInfo") ?: return null
        val afterInfo = afterResponse.optJSONObject("manureExchangeInfo") ?: return null
        for (field in listOf("currentManure", "effectiveExchangeManure")) {
            if (!beforeInfo.has(field) || !afterInfo.has(field)) {
                continue
            }
            val decrease = beforeInfo.optInt(field) - afterInfo.optInt(field)
            if (decrease > 0) {
                return decrease
            }
        }
        return null
    }

    private fun hasManureExchangeProgress(
        beforeResponse: JSONObject,
        afterResponse: JSONObject,
    ): Boolean {
        val beforeInfo = beforeResponse.optJSONObject("manureExchangeInfo")
        val afterInfo = afterResponse.optJSONObject("manureExchangeInfo")
        if (beforeInfo != null && afterInfo != null) {
            val manureDecreased =
                afterInfo.optInt("currentManure", Int.MAX_VALUE) <
                    beforeInfo.optInt("currentManure", Int.MIN_VALUE)
            val effectiveAmountDecreased =
                afterInfo.optInt("effectiveExchangeManure", Int.MAX_VALUE) <
                    beforeInfo.optInt("effectiveExchangeManure", Int.MIN_VALUE)
            val quotaDecreased =
                afterInfo.optInt("remainQuota", Int.MAX_VALUE) <
                    beforeInfo.optInt("remainQuota", Int.MIN_VALUE)
            if (manureDecreased || effectiveAmountDecreased || quotaDecreased) {
                return true
            }
        }

        val beforeProgress = beforeResponse.optJSONObject("jarInfo")?.optInt("currentProgress", -1) ?: -1
        val afterProgress = afterResponse.optJSONObject("jarInfo")?.optInt("currentProgress", -1) ?: -1
        if (beforeProgress >= 0 && afterProgress > beforeProgress) {
            return true
        }

        val beforeStatus = findManureExchangeTaskStatus(beforeResponse)
        val afterStatus = findManureExchangeTaskStatus(afterResponse)
        return beforeStatus != null &&
            afterStatus != null &&
            beforeStatus != afterStatus &&
            afterStatus in setOf("FINISHED", "TO_RECEIVE", "RECEIVED", "DONE")
    }

    private fun findManureExchangeTaskStatus(response: JSONObject): String? {
        val taskList = response.optJSONArray("taskList") ?: return null
        for (index in 0 until taskList.length()) {
            val task = taskList.optJSONObject(index) ?: continue
            if (task.optString("sceneCode") == GoldenBeanRpcCall.TASK_SCENE_CODE &&
                task.optString("taskId") == GoldenBeanRpcCall.MANURE_EXCHANGE_TASK_TYPE
            ) {
                return task.optString("taskStatus").trim().ifBlank { null }
            }
        }
        return null
    }

    private fun logManureExchangeFailure(
        action: String,
        response: JSONObject?,
    ) {
        val classification =
            if (response == null) {
                TaskRpcFailureType.RETRYABLE_RPC
            } else if (response.optBoolean("retryable", false) || response.optBoolean("retriable", false)) {
                TaskRpcFailureType.RETRYABLE_RPC
            } else {
                TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            }
        Log.error(
            GOLDEN_BEAN_BLACKLIST_MODULE,
            "金豆夺宝肥料兑换 action=$action classification=$classification " +
                "code=${response?.optString("code").orEmpty()} " +
                "resultCode=${response?.optString("resultCode").orEmpty()} " +
                "desc=${response?.let(::extractFailureMessage).orEmpty()} raw=${response ?: "EMPTY"}",
        )
    }

    internal fun handleDailySign(
        indexResponse: JSONObject,
        entry: GoldenBeanEntry = GoldenBeanRpcCall.MASTER_ENTRY,
    ): JSONObject? {
        val signList = indexResponse.optJSONObject("signInfo")?.optJSONArray("signList") ?: return null
        for (index in 0 until signList.length()) {
            val sign = signList.optJSONObject(index) ?: continue
            if (!sign.optBoolean("today", false) || sign.optBoolean("signed", false)) {
                continue
            }
            val signKey = sign.optString("signKey").trim()
            if (signKey.isBlank()) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝签到缺少服务端signKey")
                return null
            }

            val signResponse = parseResponse(GoldenBeanRpcCall.sign(signKey, entry))
            if (signResponse == null || !isSuccess(signResponse)) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝签到失败 raw=${signResponse ?: "EMPTY"}")
                return null
            }
            val syncResponse = parseResponse(
                GoldenBeanRpcCall.sync(
                    listOf("JAR_INFO", "SIGN", "MARKETING_POPUP", "TASK_LIST"),
                    entry,
                ),
            )
            if (isTodaySigned(syncResponse)) {
                Log.goldenBean("金豆夺宝签到成功 signKey=$signKey")
            } else {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝签到未通过服务端状态确认 raw=${syncResponse ?: "EMPTY"}")
            }
            return syncResponse
        }
        return null
    }

    internal fun handleMarketingPopup(
        indexResponse: JSONObject,
        entry: GoldenBeanEntry = GoldenBeanRpcCall.MASTER_ENTRY,
    ) {
        val marketingTask = indexResponse.optJSONObject("marketingPopupTask") ?: return
        val taskId = marketingTask.optString("taskId").trim()
        val triggerType = marketingTask.optString("triggerType").trim().ifBlank { MARKETING_POPUP_CLICKED }
        if (taskId.isBlank()) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝营销弹窗缺少服务端taskId raw=$marketingTask",
            )
            return
        }

        val triggerResponse = parseResponse(GoldenBeanRpcCall.trigger(taskId, triggerType, entry))
        if (triggerResponse == null || !isSuccess(triggerResponse)) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝营销弹窗触发失败 taskId=$taskId raw=${triggerResponse ?: "EMPTY"}")
            return
        }
        val syncResponse = parseResponse(GoldenBeanRpcCall.sync(listOf("MARKETING_POPUP"), entry))
        if (syncResponse == null || !isSuccess(syncResponse)) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝营销弹窗回查失败 taskId=$taskId raw=${syncResponse ?: "EMPTY"}")
        }
    }

    internal fun queryMallItems() {
        val topResponse = parseResponse(GoldenBeanRpcCall.listTopItemsByScene())
        if (topResponse == null || !isSuccess(topResponse)) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝商城运营位查询失败 raw=${topResponse ?: "EMPTY"}")
        } else {
            logMallItems(
                topResponse.optJSONObject("itemsSceneMap")?.optJSONArray("OPERATION_STRATEGY") ?: JSONArray(),
                "运营位",
            )
        }

        var startIndex = 0
        while (true) {
            val itemResponse = parseResponse(GoldenBeanRpcCall.itemList(startIndex))
            if (itemResponse == null || !isSuccess(itemResponse)) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝商城列表查询失败 startIndex=$startIndex raw=${itemResponse ?: "EMPTY"}")
                return
            }

            val items = itemResponse.optJSONArray("itemInfoVOList") ?: JSONArray()
            logMallItems(items, "商品")

            if (!itemResponse.optBoolean("hasMore", false)) {
                return
            }
            val nextStartIndex = itemResponse.optInt("nextStartIndex", startIndex)
            if (nextStartIndex <= startIndex || items.length() == 0) {
                Log.error(
                    GOLDEN_BEAN_BLACKLIST_MODULE,
                    "金豆夺宝商城分页未推进 startIndex=$startIndex nextStartIndex=$nextStartIndex",
                )
                return
            }
            startIndex = nextStartIndex
        }
    }

    internal suspend fun runGameCenterOpportunityFlow(): GoldenBeanGameFlowResult {
        val attemptedSnapshots = mutableSetOf<String>()
        var progressed = false
        var hasUnconfirmedGameAction = false
        var round = 1
        while (round <= GOLDEN_BEAN_CONVERGENCE_LIMIT) {
            val before =
                queryGameCenterSnapshot()
                    ?: return GoldenBeanGameFlowResult(false, progressed, blocked = true)
            val beforeRights = before.drawRights
            val beforeQuota = beforeRights.optInt("quotaCanUse", 0).coerceAtLeast(0)
            val beforeUsed = beforeRights.optInt("usedQuota", 0).coerceAtLeast(0)
            val quotaLimit = beforeRights.optInt("quotaLimit", 0).coerceAtLeast(0)

            if (beforeQuota > 0) {
                val drawResponse = parseResponse(GoldenBeanRpcCall.drawGameCenterAward())
                if (drawResponse == null || !isSuccess(drawResponse)) {
                    Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆乐园抽奖失败 raw=${drawResponse ?: "EMPTY"}")
                    return GoldenBeanGameFlowResult(false, progressed, blocked = true)
                }
                val after =
                    queryGameCenterSnapshot()
                        ?: return GoldenBeanGameFlowResult(false, progressed, blocked = true)
                val afterQuota = after.drawRights.optInt("quotaCanUse", beforeQuota).coerceAtLeast(0)
                val afterUsed = after.drawRights.optInt("usedQuota", beforeUsed).coerceAtLeast(0)
                if (afterQuota >= beforeQuota && afterUsed <= beforeUsed) {
                    Log.error(
                        GOLDEN_BEAN_BLACKLIST_MODULE,
                        "金豆乐园抽奖回查未确认配额推进 quotaCanUse=$beforeQuota->$afterQuota " +
                            "usedQuota=$beforeUsed->$afterUsed",
                    )
                    return GoldenBeanGameFlowResult(false, progressed, blocked = true)
                }
                progressed = true
                Log.goldenBean("金豆乐园抽奖回查 quotaCanUse=$beforeQuota->$afterQuota usedQuota=$beforeUsed->$afterUsed")
                round++
                continue
            }

            if (beforeUsed >= quotaLimit) {
                Log.goldenBean("金豆乐园抽奖[服务端确认已达上限$beforeUsed/$quotaLimit]")
                return GoldenBeanGameFlowResult(true, progressed)
            }

            val candidate =
                before.candidates.firstOrNull { candidate ->
                    candidate.rightTimes < candidate.rightTimesLimit &&
                        resolveGoldenBeanGameTask(candidate.appId) != null &&
                        "${candidate.appId}:${candidate.taskId}:${candidate.rightTimes}:${candidate.rightTimesLimit}" !in attemptedSnapshots
                }
            if (candidate == null) {
                val hasPendingSupportedGame =
                    before.candidates.any {
                        it.rightTimes < it.rightTimesLimit && resolveGoldenBeanGameTask(it.appId) != null
                    }
                val remainingQuota = (quotaLimit - beforeUsed).coerceAtLeast(0)
                Log.goldenBean(
                    "金豆乐园[当前无可执行游戏且无可用抽奖次数] usedQuota=$beforeUsed/$quotaLimit " +
                        "remainingQuota=$remainingQuota pendingSupportedGame=$hasPendingSupportedGame",
                )
                val blocked = hasPendingSupportedGame || hasUnconfirmedGameAction
                return GoldenBeanGameFlowResult(!blocked, progressed, blocked)
            }

            val attemptKey =
                "${candidate.appId}:${candidate.taskId}:${candidate.rightTimes}:${candidate.rightTimesLimit}"
            attemptedSnapshots.add(attemptKey)
            val gameTask = resolveGoldenBeanGameTask(candidate.appId) ?: continue
            val remaining =
                minOf(
                    candidate.rightTimesLimit - candidate.rightTimes,
                    quotaLimit - beforeUsed,
                ).coerceAtLeast(0)
            val reportResult =
                gameTask.reportDetailed(
                    remaining,
                    GOLDEN_BEAN_GAME_CHANNEL,
                    includeSafetyReport = false,
                ) { message ->
                    Log.goldenBean("[金豆乐园:${gameTask.title}] $message")
                }
            val after =
                queryGameCenterSnapshot()
                    ?: return GoldenBeanGameFlowResult(false, progressed, blocked = true)
            val afterCandidate =
                after.candidates.firstOrNull {
                    it.appId == candidate.appId && it.taskId == candidate.taskId
                }
            val afterQuota = after.drawRights.optInt("quotaCanUse", beforeQuota).coerceAtLeast(0)
            val afterUsed = after.drawRights.optInt("usedQuota", beforeUsed).coerceAtLeast(0)
            val candidateProgressed =
                afterCandidate?.rightTimes?.let { it > candidate.rightTimes } == true
            val rightsProgressed = afterQuota > beforeQuota || afterUsed > beforeUsed
            if (candidateProgressed || rightsProgressed) {
                progressed = true
                Log.goldenBean(
                    "金豆乐园游戏[appId=${candidate.appId} taskId=${candidate.taskId}] " +
                        "rightTimes=${candidate.rightTimes}->${afterCandidate?.rightTimes ?: candidate.rightTimesLimit} " +
                        "quotaCanUse=$beforeQuota->$afterQuota",
                )
            } else {
                hasUnconfirmedGameAction = true
                Log.error(
                    GOLDEN_BEAN_BLACKLIST_MODULE,
                    "金豆乐园游戏上报未确认进展 appId=${candidate.appId} taskId=${candidate.taskId} " +
                        "rightTimes=${candidate.rightTimes}/${candidate.rightTimesLimit} " +
                        "reports=${reportResult.successfulReports}/${reportResult.requiredSuccesses} " +
                        "msg=${reportResult.failureMessage.ifBlank { "服务端状态未推进" }}",
                )
            }
            round++
        }

        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆乐园达到收敛轮次上限$GOLDEN_BEAN_CONVERGENCE_LIMIT")
        return GoldenBeanGameFlowResult(false, progressed, blocked = true)
    }

    private fun queryGameCenterSnapshot(): GoldenBeanGameSnapshot? {
        val response = parseResponse(GoldenBeanRpcCall.queryGameList())
        if (response == null || !isSuccess(response)) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆乐园游戏列表查询失败 raw=${response ?: "EMPTY"}")
            return null
        }
        val drawRights = findObjectByKey(response, "gameCenterDrawRights")
        if (drawRights == null ||
            !drawRights.has("quotaCanUse") ||
            !drawRights.has("usedQuota") ||
            !drawRights.has("quotaLimit") ||
            drawRights.optInt("quotaLimit", 0) <= 0
        ) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆乐园抽奖资格缺少有效额度字段 raw=$response")
            return null
        }
        val candidates = linkedMapOf<String, GoldenBeanGameCandidate>()
        collectGameCenterCandidates(response, candidates)
        return GoldenBeanGameSnapshot(drawRights, candidates.values.toList())
    }

    private fun collectGameCenterCandidates(
        source: Any?,
        candidates: MutableMap<String, GoldenBeanGameCandidate>,
    ) {
        when (source) {
            is JSONObject -> {
                val appId = source.optString("appId")
                val deliveryBenefitList = source.optJSONArray("deliveryBenefitList")
                if (appId.isNotBlank() && deliveryBenefitList != null) {
                    for (index in 0 until deliveryBenefitList.length()) {
                        val benefit = deliveryBenefitList.optJSONObject(index) ?: continue
                        if (!benefit.optString("benefitType").equals("IEP_REQUEST", ignoreCase = true)) {
                            continue
                        }
                        val taskId = benefit.optString("iepTaskId")
                        val rightTimesLimit = benefit.optInt("rightTimesLimit", 0)
                        if (taskId.isBlank() || rightTimesLimit <= 0) {
                            continue
                        }
                        val candidate =
                            GoldenBeanGameCandidate(
                                appId = appId,
                                taskId = taskId,
                                rightTimes = benefit.optInt("rightTimes", 0).coerceAtLeast(0),
                                rightTimesLimit = rightTimesLimit,
                            )
                        candidates.putIfAbsent("$appId:$taskId", candidate)
                    }
                }
                val keys = source.keys()
                while (keys.hasNext()) {
                    collectGameCenterCandidates(source.opt(keys.next()), candidates)
                }
            }

            is JSONArray -> {
                for (index in 0 until source.length()) {
                    collectGameCenterCandidates(source.opt(index), candidates)
                }
            }
        }
    }

    private fun findObjectByKey(
        source: Any?,
        targetKey: String,
    ): JSONObject? =
        when (source) {
            is JSONObject -> {
                source.optJSONObject(targetKey) ?: run {
                    val keys = source.keys()
                    var result: JSONObject? = null
                    while (keys.hasNext() && result == null) {
                        result = findObjectByKey(source.opt(keys.next()), targetKey)
                    }
                    result
                }
            }

            is JSONArray -> {
                var result: JSONObject? = null
                var index = 0
                while (index < source.length() && result == null) {
                    result = findObjectByKey(source.opt(index), targetKey)
                    index++
                }
                result
            }

            else -> null
        }

    private fun resolveGoldenBeanGameTask(appId: String): GameTask? =
        when (appId) {
            GameTask.Orchard_ncscc.appId -> GameTask.Orchard_ncscc
            GameTask.Farm_ddply.appId -> GameTask.Farm_ddply
            else -> null
        }

    internal fun runMiner() {
        val indexResponse = parseResponse(GoldenBeanRpcCall.minerIndex())
        if (indexResponse == null || !isSuccess(indexResponse)) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工首页查询失败 raw=${indexResponse ?: "EMPTY"}")
            return
        }

        if (!indexResponse.has("enabled")) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工首页缺少enabled raw=$indexResponse")
            return
        }
        if (!indexResponse.optBoolean("enabled", false)) {
            Log.goldenBean("金猫矿工[服务端未启用]")
            return
        }

        val minerInfo = indexResponse.optJSONObject("minerInfo") ?: run {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工首页缺少minerInfo raw=$indexResponse")
            return
        }
        val taskProgress = minerInfo.optJSONObject("taskProgress") ?: run {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工首页缺少taskProgress raw=$indexResponse")
            return
        }
        if (!taskProgress.has("canGrab") || !taskProgress.has("remainingTimes")) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工首页缺少可抓取状态 raw=$indexResponse")
            return
        }
        if (!taskProgress.optBoolean("canGrab", false)) {
            Log.goldenBean("金猫矿工[服务端无可抓取次数]")
            return
        }

        val grabbedItemIds = mutableSetOf<String>()
        val progress = minerInfo.optJSONObject("progress")
        val alreadyGrabbed = progress?.optJSONArray("grabbedItemIds") ?: JSONArray()
        for (index in 0 until alreadyGrabbed.length()) {
            alreadyGrabbed.optString(index).takeIf { it.isNotBlank() }?.let(grabbedItemIds::add)
        }
        val beanItemIds = mutableListOf<String>()
        val items = minerInfo.optJSONObject("currentLevel")?.optJSONArray("items") ?: run {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工首页缺少items raw=$indexResponse")
            return
        }
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val itemId = item.optString("itemId").trim()
            if (item.optString("type") == "BEAN" && itemId.isNotBlank() && itemId !in grabbedItemIds) {
                beanItemIds.add(itemId)
            }
        }

        var candidateIndex = 0
        var remainingTimes = taskProgress.optInt("remainingTimes", 0)
        var canGrab = taskProgress.optBoolean("canGrab", false)
        while (canGrab && remainingTimes > 0) {
            val itemId = beanItemIds.getOrNull(candidateIndex)
            val expectedResult = if (itemId.isNullOrBlank()) "EMPTY" else "BEAN"
            val grabResponse = parseResponse(GoldenBeanRpcCall.minerGrab(expectedResult, itemId.orEmpty()))
            if (grabResponse == null || !isSuccess(grabResponse)) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工抓取失败 raw=${grabResponse ?: "EMPTY"}")
                return
            }

            val syncResponse = parseResponse(GoldenBeanRpcCall.sync(
                    listOf("JAR_INFO"),
                    sourceOverride = GoldenBeanRpcCall.MINER_SOURCE,
                ))
            if (syncResponse == null || !isSuccess(syncResponse)) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工抓取后金豆罐回查失败 raw=${syncResponse ?: "EMPTY"}")
                return
            }
            if (grabResponse.optBoolean("needAd", false)) {
                Log.goldenBean("金猫矿工[服务端要求广告，保留待人工处理]")
                return
            }

            if (expectedResult == "BEAN") {
                candidateIndex++
            }
            val updatedProgress = grabResponse.optJSONObject("taskProgress") ?: run {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工抓取响应缺少taskProgress raw=$grabResponse")
                return
            }
            if (!updatedProgress.has("canGrab") || !updatedProgress.has("remainingTimes")) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工抓取响应缺少可抓取状态 raw=$grabResponse")
                return
            }
            val updatedRemainingTimes = updatedProgress.optInt("remainingTimes", remainingTimes)
            if (updatedRemainingTimes >= remainingTimes) {
                Log.error(
                    GOLDEN_BEAN_BLACKLIST_MODULE,
                    "金猫矿工抓取后次数未推进 remainingTimes=$remainingTimes->$updatedRemainingTimes raw=$grabResponse",
                )
                return
            }
            remainingTimes = updatedRemainingTimes
            canGrab = updatedProgress.optBoolean("canGrab", false)
        }

        val finalResponse = parseResponse(GoldenBeanRpcCall.minerIndex())
        if (finalResponse == null || !isSuccess(finalResponse)) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工最终回查失败 raw=${finalResponse ?: "EMPTY"}")
            return
        }
        val finalProgress = finalResponse.optJSONObject("minerInfo")?.optJSONObject("taskProgress")
        if (finalProgress == null ||
            !finalProgress.has("canGrab") ||
            !finalProgress.has("remainingTimes")
        ) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工最终回查缺少可抓取状态 raw=$finalResponse")
            return
        }
        Log.goldenBean(
            "金猫矿工最终回查 canGrab=${finalProgress.optBoolean("canGrab", false)} " +
                "remainingTimes=${finalProgress.optInt("remainingTimes", -1)}",
        )
    }

    private fun logMallItems(
        items: JSONArray,
        listType: String,
    ) {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val itemId =
                item.optString("spuId")
                    .ifBlank { item.optString("itemId") }
                    .ifBlank { item.optString("id") }
            val firstSku = item.optJSONArray("skuModelList")?.optJSONObject(0)
            val minPrice = item.optJSONObject("minPrice")
            Log.goldenBean(
                "金豆夺宝商城[${listType}只读] itemId=${itemId.ifBlank { "UNKNOWN" }} " +
                    "itemStatus=${item.optString("itemStatus").ifBlank { "UNKNOWN" }} " +
                    "skuId=${firstSku?.optString("skuId").orEmpty().ifBlank { "UNKNOWN" }} " +
                    "skuStatus=${firstSku?.optString("skuRuleResult").orEmpty().ifBlank { "UNKNOWN" }} " +
                    "stock=${item.optInt("remainStockCounts", -1)} " +
                    "priceCent=${minPrice?.optInt("cent", -1)}",
            )
        }
    }

    internal fun actionResult(
        item: TaskFlowItem,
        response: JSONObject,
        action: String,
    ): TaskFlowActionResult {
        if (isSuccess(response)) {
            return TaskFlowActionResult.success(
                refreshAfterAction = true,
                progressChanged = false,
            )
        }
        val code = extractFailureCode(response)
        val failureType =
            when {
                code in unsupportedCodes -> TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE
                code in invalidCodes -> TaskRpcFailureType.NON_RETRYABLE_INVALID
                code in retryableCodes || response.optBoolean("retryable", false) || response.optBoolean("retriable", false) -> {
                    TaskRpcFailureType.RETRYABLE_RPC
                }

                else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            }
        return TaskFlowActionResult.failure(
            failureType = failureType,
            code = code,
            message = extractFailureMessage(response),
            rpc = "GoldenBeanRpcCall.$action",
            raw = response.toString(),
            detail = "taskId=${item.type.ifBlank { item.id }} actionType=${item.actionType.ifBlank { "UNKNOWN" }} " +
                "sceneCode=${item.sceneCode.ifBlank { "UNKNOWN" }}",
            continueCurrentRoundOnFailure = failureType == TaskRpcFailureType.RETRYABLE_RPC,
        )
    }

    internal fun emptyResponseFailure(
        item: TaskFlowItem,
        action: String,
    ): TaskFlowActionResult =
        TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.RETRYABLE_RPC,
            message = "RPC返回空",
            rpc = "GoldenBeanRpcCall.$action",
            detail = "taskId=${item.type.ifBlank { item.id }} actionType=${item.actionType.ifBlank { "UNKNOWN" }} " +
                "sceneCode=${item.sceneCode.ifBlank { "UNKNOWN" }}",
            continueCurrentRoundOnFailure = true,
        )

    internal fun missingTaskTypeFailure(
        item: TaskFlowItem,
        action: String,
    ): TaskFlowActionResult =
        TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            message = "缺少服务端taskId",
            rpc = "GoldenBeanRpcCall.$action",
            raw = item.raw?.toString().orEmpty(),
            detail = "taskId=${item.id.ifBlank { "UNKNOWN" }} actionType=${item.actionType.ifBlank { "UNKNOWN" }} " +
                "sceneCode=${item.sceneCode.ifBlank { "UNKNOWN" }}",
        )

    private fun extractFailureCode(response: JSONObject): String =
        response.optString("code")
            .ifBlank { response.optString("resultCode") }
            .ifBlank { response.optString("errorCode") }

    private fun extractFailureMessage(response: JSONObject): String =
        response.optString("desc")
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("memo") }

    private fun isTodaySigned(response: JSONObject?): Boolean {
        val signInfo = response?.optJSONObject("signInfo") ?: return false
        if (signInfo.optBoolean("todaySigned", false)) {
            return true
        }
        val signList = signInfo.optJSONArray("signList") ?: return false
        for (index in 0 until signList.length()) {
            val sign = signList.optJSONObject(index) ?: continue
            if (sign.optBoolean("today", false) && sign.optBoolean("signed", false)) {
                return true
            }
        }
        return false
    }
}

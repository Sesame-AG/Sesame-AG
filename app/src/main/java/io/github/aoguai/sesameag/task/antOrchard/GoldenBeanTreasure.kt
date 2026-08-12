package io.github.aoguai.sesameag.task.antOrchard

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TaskBlacklist
import org.json.JSONArray
import org.json.JSONObject

private const val GOLDEN_BEAN_BLACKLIST_MODULE = "金豆夺宝"
private const val MARKETING_POPUP_CLICKED = "MARKETING_POPUP_CLICKED"

/** 本轮由金豆首页服务端状态确认的肥料预留。 */
internal data class GoldenBeanManureExchangePlan(
    val reservedManure: Int,
    val exchangedToday: Int,
)

/**
 * Module-local orchestration for the Golden Bean Treasure domain. Task-list
 * transitions reuse TaskFlowEngine; mining and the game-centre draw use their
 * own server state because neither belongs to the task-list lifecycle.
 */
internal fun AntOrchard.runGoldenBeanTreasure() {
    if (Status.hasFlagToday(StatusFlags.FLAG_ANTORCHARD_GOLDEN_BEAN_TASKS_DONE)) {
        Log.orchard("金豆夺宝[今日已处理，跳过]")
        return
    }

    val indexResponse = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index())
    if (indexResponse == null || !GoldenBeanTreasureSupport.isSuccess(indexResponse)) {
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝首页查询失败 raw=${indexResponse ?: "EMPTY"}")
        return
    }

    val signSyncResponse = GoldenBeanTreasureSupport.handleDailySign(indexResponse)
    val marketingSource =
        signSyncResponse?.takeIf { it.optJSONObject("marketingPopupTask") != null } ?: indexResponse
    GoldenBeanTreasureSupport.handleMarketingPopup(marketingSource)
    GoldenBeanTreasureSupport.queryMallItems()

    val taskFlowAdapter = GoldenBeanTaskFlowAdapter()
    val taskResult = TaskFlowEngine(
        taskFlowAdapter,
        roundSleepMs = executeIntervalInt.toLong().coerceAtLeast(500L),
    ).run()

    val drawComplete = GoldenBeanTreasureSupport.drawGameCenterAwardIfAvailable()
    if (taskResult.completed && !taskResult.stopped && drawComplete) {
        Status.setFlagToday(StatusFlags.FLAG_ANTORCHARD_GOLDEN_BEAN_TASKS_DONE)
    }

    if (taskFlowAdapter.isMinerEntryReceived()) {
        GoldenBeanTreasureSupport.runMiner()
    }
}

internal fun AntOrchard.prepareGoldenBeanManureExchangePlan() {
    if (goldenBeanTreasure.value != true) {
        return
    }
    val indexResponse =
        try {
            GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index())
        } catch (error: Exception) {
            Log.printStackTrace(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝肥料换豆预留查询异常:", error)
            return
        }
    if (indexResponse == null || !GoldenBeanTreasureSupport.isSuccess(indexResponse)) {
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝肥料换豆预留查询失败 raw=${indexResponse ?: "EMPTY"}")
        return
    }
    goldenBeanManureExchangePlan =
        GoldenBeanTreasureSupport.planManureExchange(
            indexResponse,
            goldenBeanManureExchangeDailyReserveAmount.value ?: 0,
        )
}

internal fun AntOrchard.runGoldenBeanManureExchangeIfPlanned() {
    val plan = goldenBeanManureExchangePlan ?: return
    val indexResponse = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index())
    if (indexResponse == null || !GoldenBeanTreasureSupport.isSuccess(indexResponse)) {
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝肥料兑换最终查询失败 raw=${indexResponse ?: "EMPTY"}")
        return
    }
    GoldenBeanTreasureSupport.exchangePlannedManure(indexResponse, plan)
}

private class GoldenBeanTaskFlowAdapter : TaskFlowAdapter {
    private val loggedDeferredTaskIds = mutableSetOf<String>()
    private var latestTaskResponse = JSONObject()
    private var nextQuerySyncTypes = TASK_STATUS_SYNC_TYPES

    override val moduleName: String = GOLDEN_BEAN_BLACKLIST_MODULE
    override val flowName: String = "金豆夺宝任务"

    override fun query(): JSONObject {
        val syncTypes = nextQuerySyncTypes
        nextQuerySyncTypes = TASK_STATUS_SYNC_TYPES
        return GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.sync(syncTypes))
            ?: JSONObject().put("resultCode", "").put("resultDesc", "goldenbean.sync返回空")
    }

    override fun isQuerySuccess(response: JSONObject): Boolean = GoldenBeanTreasureSupport.isSuccess(response)

    override fun extractItems(response: JSONObject): List<TaskFlowItem> {
        latestTaskResponse = response
        val taskList = response.optJSONArray("taskList") ?: return emptyList()
        val items = mutableListOf<TaskFlowItem>()
        for (index in 0 until taskList.length()) {
            val task = taskList.optJSONObject(index) ?: continue
            val sceneCode = task.optString("sceneCode").trim()
            if (sceneCode != GoldenBeanRpcCall.TASK_SCENE_CODE) {
                if (sceneCode.isBlank()) {
                    Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝任务缺少sceneCode raw=$task")
                }
                continue
            }

            val taskId = task.optString("taskId").trim()
            val groupId = task.optString("groupId").trim()
            val stableId = taskId.ifBlank { groupId }.ifBlank { "UNKNOWN_$index" }
            val rightsTimesLimit = task.optInt("rightsTimesLimit", 0)
            val rightsTimes = task.optInt("rightsTimes", 0)

            items.add(
                TaskFlowItem(
                    id = stableId,
                    // Display copy is deliberately not used by the execution or blacklist path.
                    title = stableId,
                    status = task.optString("taskStatus").trim(),
                    type = taskId,
                    sceneCode = sceneCode,
                    actionType = task.optString("actionType").trim(),
                    blacklistKeys =
                        listOf(taskId, groupId)
                            .filter { it.isNotBlank() }
                            .map { stableBlacklistKey(sceneCode, it) },
                    raw = task,
                    progress = buildTaskProgress(task),
                    current = rightsTimes.takeIf { rightsTimesLimit > 0 },
                    limit = rightsTimesLimit.takeIf { it > 0 },
                ),
            )
        }
        return items
    }

    override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
        when (item.status.uppercase()) {
            "FINISHED", "TO_RECEIVE" -> TaskFlowPhase.REWARD_READY
            "RECEIVED", "DONE" -> TaskFlowPhase.TERMINAL
            "TODO" -> {
                if (hasCapturedFinishContract(item)) {
                    TaskFlowPhase.READY_TO_COMPLETE
                } else {
                    TaskFlowPhase.UNKNOWN
                }
            }

            else -> TaskFlowPhase.UNKNOWN
        }

    override fun isFlowHandledToday(): Boolean =
        Status.hasFlagToday(StatusFlags.FLAG_ANTORCHARD_GOLDEN_BEAN_TASKS_DONE)

    override fun shouldSkip(item: TaskFlowItem): Boolean {
        if (item.status.uppercase() != "TODO") {
            return false
        }
        if (hasCapturedFinishContract(item)) {
            return false
        }
        if (loggedDeferredTaskIds.add(item.id)) {
            Log.orchard(
                "金豆夺宝任务⏭️[taskId=${item.id} actionType=${item.actionType.ifBlank { "UNKNOWN" }} " +
                    "sceneCode=${item.sceneCode.ifBlank { "UNKNOWN" }}] 未捕获主动动作，仅保留服务端状态",
            )
        }
        return true
    }

    override fun receive(item: TaskFlowItem): TaskFlowActionResult {
        if (item.type.isBlank()) {
            return GoldenBeanTreasureSupport.missingTaskTypeFailure(item, "receive")
        }
        val response = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.receiveTaskAward(item.type))
            ?: return GoldenBeanTreasureSupport.emptyResponseFailure(item, "receive")
        if (GoldenBeanTreasureSupport.isSuccess(response)) {
            nextQuerySyncTypes = RECEIVE_TASK_SYNC_TYPES
        }
        return GoldenBeanTreasureSupport.actionResult(item, response, "receive")
    }

    override fun complete(item: TaskFlowItem): TaskFlowActionResult {
        if (item.type.isBlank()) {
            return GoldenBeanTreasureSupport.missingTaskTypeFailure(item, "complete")
        }
        val response = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.finishTask(item.type))
            ?: return GoldenBeanTreasureSupport.emptyResponseFailure(item, "complete")
        if (GoldenBeanTreasureSupport.isSuccess(response)) {
            nextQuerySyncTypes = TASK_STATUS_SYNC_TYPES
        }
        return GoldenBeanTreasureSupport.actionResult(item, response, "complete")
    }

    override fun actionKey(
        item: TaskFlowItem,
        action: TaskFlowAction,
    ): String = "${action.logName}:${item.id}:${item.sceneCode}:${item.actionType}:${item.status}"

    override fun blacklist(
        item: TaskFlowItem,
        result: TaskFlowActionResult,
    ) {
        val taskId = item.type.ifBlank { item.id }
        if (taskId.isBlank() || taskId.startsWith("UNKNOWN_")) {
            return
        }
        val stableId = stableBlacklistKey(item.sceneCode, taskId)
        if (result.code.isNotBlank()) {
            TaskBlacklist.autoAddToBlacklist(moduleName, stableId, errorCode = result.code)
        }
        TaskBlacklist.addToBlacklist(moduleName, stableId)
    }

    override fun onUnknownPhase(
        item: TaskFlowItem,
        phase: TaskFlowPhase,
    ) {
        Log.error(
            GOLDEN_BEAN_BLACKLIST_MODULE,
            "金豆夺宝任务未知状态 taskId=${item.id} status=${item.status} " +
                "actionType=${item.actionType.ifBlank { "UNKNOWN" }} phase=$phase raw=${item.raw ?: "EMPTY"}",
        )
    }

    override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
        Log.orchard("金豆夺宝任务列表已无可自动推进或待领取任务")
    }

    override fun onQueryFailed(response: JSONObject) {
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝任务查询失败 raw=$response")
    }

    override fun logInfo(message: String) {
        Log.orchard(message)
    }

    override fun logError(message: String) {
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, message)
    }

    fun isMinerEntryReceived(): Boolean {
        val taskList = latestTaskResponse.optJSONArray("taskList") ?: return false
        for (index in 0 until taskList.length()) {
            val task = taskList.optJSONObject(index) ?: continue
            if (task.optString("sceneCode") == GoldenBeanRpcCall.TASK_SCENE_CODE &&
                task.optString("taskId") == GoldenBeanRpcCall.WAKUANG_TASK_TYPE
            ) {
                return task.optString("taskStatus") in setOf("RECEIVED", "DONE")
            }
        }
        return false
    }

    private fun hasCapturedFinishContract(item: TaskFlowItem): Boolean {
        if (item.sceneCode != GoldenBeanRpcCall.TASK_SCENE_CODE) {
            return false
        }
        return when (item.type) {
            GoldenBeanRpcCall.WAKUANG_TASK_TYPE -> item.actionType == GoldenBeanRpcCall.WAKUANG_ACTION_TYPE
            GoldenBeanRpcCall.JINDOULEYUAN_TASK_TYPE -> item.actionType == GoldenBeanRpcCall.JINDOULEYUAN_ACTION_TYPE
            else -> false
        }
    }

    private fun buildTaskProgress(task: JSONObject): String {
        val parts = mutableListOf<String>()
        val rightsTimesLimit = task.optInt("rightsTimesLimit", 0)
        if (rightsTimesLimit > 0) {
            parts.add("rights=${task.optInt("rightsTimes", 0)}/$rightsTimesLimit")
        }
        val taskRequire = task.optInt("taskRequire", 0)
        if (taskRequire > 0) {
            parts.add("task=${task.optInt("taskProgress", 0)}/$taskRequire")
        }
        return parts.joinToString(" ")
    }

    private fun stableBlacklistKey(
        sceneCode: String,
        identifier: String,
    ): String = "$sceneCode:$identifier"

    private companion object {
        val TASK_STATUS_SYNC_TYPES = listOf("FARM_TASK", "TASK_LIST")
        val RECEIVE_TASK_SYNC_TYPES = listOf("JAR_INFO", "TASK_LIST")
    }
}

private object GoldenBeanTreasureSupport {
    private val unsupportedCodes = setOf("400000040")
    private val invalidCodes = setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT")
    private val retryableCodes = setOf("3000", "REMOTE_INVOKE_EXCEPTION")

    fun parseResponse(response: String): JSONObject? =
        response.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { JSONObject(raw) }
                .onFailure { Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝响应解析失败 raw=$raw") }
                .getOrNull()
        }

    fun isSuccess(response: JSONObject): Boolean {
        if (response.optBoolean("success", false)) {
            return true
        }
        return response.optString("resultCode") in setOf("100", "SUCCESS") ||
            response.optString("code") == "100000000"
    }

    fun planManureExchange(
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
            Status.getIntFlagToday(StatusFlags.FLAG_ANTORCHARD_GOLDEN_BEAN_MANURE_EXCHANGE_AMOUNT) ?: 0
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

        Log.orchard(
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
            Log.orchard("金豆夺宝肥料换豆[BUSINESS_LIMIT] 服务端资格未满足")
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
            Log.orchard("金豆夺宝肥料换豆[USER_LIMIT] 今日配置额度已用完")
            return null
        }
        if (reservedManure < minExchangeAmount) {
            val classification =
                if (configuredDailyReserveAmount > 0) "USER_CONFIGURATION" else "BUSINESS_LIMIT"
            Log.orchard(
                "金豆夺宝肥料换豆[$classification] 预留${reservedManure}低于服务端最小兑换量$minExchangeAmount，本轮不预留也不换豆",
            )
            return null
        }
        if (currentManure < reservedManure ||
            effectiveExchangeManure < reservedManure ||
            remainQuota < reservedManure
        ) {
            Log.orchard(
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

    fun exchangePlannedManure(
        indexResponse: JSONObject,
        plan: GoldenBeanManureExchangePlan,
    ) {
        val exchangeInfo = requireManureExchangeInfo(indexResponse, "最终兑换")
            ?: return
        val farmOpened = exchangeInfo.optBoolean("farmOpened")
        val pageOpened = exchangeInfo.optBoolean("pageOpened")
        val taobaoBinding = exchangeInfo.optBoolean("taobaoBinding")
        val currentManure = exchangeInfo.optInt("currentManure", 0)
        val effectiveExchangeManure = exchangeInfo.optInt("effectiveExchangeManure", 0)
        val minExchangeAmount = exchangeInfo.optInt("minExchangeAmount", 0)
        val remainQuota = exchangeInfo.optInt("remainQuota", 0)

        if (!farmOpened || !pageOpened || !taobaoBinding) {
            Log.orchard("金豆夺宝肥料换豆[BUSINESS_LIMIT] 最终回查资格未满足")
            return
        }
        if (minExchangeAmount <= 0) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝肥料换豆 classification=UNKNOWN_NEEDS_REVIEW 最终回查最低兑换量无效=$minExchangeAmount",
            )
            return
        }
        if (plan.reservedManure < minExchangeAmount ||
            currentManure < plan.reservedManure ||
            effectiveExchangeManure < plan.reservedManure ||
            remainQuota < plan.reservedManure
        ) {
            Log.orchard(
                "金豆夺宝肥料换豆[BUSINESS_LIMIT] 最终回查无法满足预留${plan.reservedManure}，不发送兑换请求",
            )
            return
        }

        val exchangeResponse = parseResponse(GoldenBeanRpcCall.manureExchange(plan.reservedManure))
        if (exchangeResponse == null || !isSuccess(exchangeResponse)) {
            logManureExchangeFailure("manureExchange", exchangeResponse)
            return
        }

        val syncResponse =
            parseResponse(
                GoldenBeanRpcCall.sync(
                    listOf("JAR_INFO", "EXCHANGE_MANURE", "TASK_LIST"),
                ),
            )
        if (syncResponse == null || !isSuccess(syncResponse)) {
            logManureExchangeFailure("sync", syncResponse)
            return
        }

        if (hasManureExchangeProgress(indexResponse, syncResponse)) {
            val afterInfo = syncResponse.optJSONObject("manureExchangeInfo")
            val confirmedManureCost = resolveConfirmedManureCost(exchangeResponse, indexResponse, syncResponse)
            if (confirmedManureCost != null) {
                Status.setIntFlagToday(
                    StatusFlags.FLAG_ANTORCHARD_GOLDEN_BEAN_MANURE_EXCHANGE_AMOUNT,
                    plan.exchangedToday + confirmedManureCost,
                )
            } else {
                Log.error(
                    GOLDEN_BEAN_BLACKLIST_MODULE,
                    "金豆夺宝肥料兑换 classification=UNKNOWN_NEEDS_REVIEW " +
                        "服务端已确认状态推进但缺少可计量肥料消耗 raw=$syncResponse",
                )
            }
            Log.orchard(
                "金豆夺宝肥料兑换成功并完成服务端回查 amount=${plan.reservedManure} " +
                    "manureCost=${confirmedManureCost ?: "UNKNOWN"} " +
                    "remainManure=${afterInfo?.optInt("currentManure", -1)} " +
                    "remainQuota=${afterInfo?.optInt("remainQuota", -1)} " +
                    "taskStatus=${findManureExchangeTaskStatus(syncResponse) ?: "UNKNOWN"}",
            )
        } else {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝肥料兑换 classification=UNKNOWN_NEEDS_REVIEW " +
                    "请求成功但同步未确认肥料、配额、金豆罐或任务状态推进 " +
                    "amount=${plan.reservedManure} raw=$syncResponse",
            )
        }
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

    fun handleDailySign(indexResponse: JSONObject): JSONObject? {
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

            val signResponse = parseResponse(GoldenBeanRpcCall.sign(signKey))
            if (signResponse == null || !isSuccess(signResponse)) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝签到失败 raw=${signResponse ?: "EMPTY"}")
                return null
            }
            val syncResponse = parseResponse(
                GoldenBeanRpcCall.sync(listOf("JAR_INFO", "SIGN", "MARKETING_POPUP", "TASK_LIST")),
            )
            if (isTodaySigned(syncResponse)) {
                Log.orchard("金豆夺宝签到成功 signKey=$signKey")
            } else {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝签到未通过服务端状态确认 raw=${syncResponse ?: "EMPTY"}")
            }
            return syncResponse
        }
        return null
    }

    fun handleMarketingPopup(indexResponse: JSONObject) {
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

        val triggerResponse = parseResponse(GoldenBeanRpcCall.trigger(taskId, triggerType))
        if (triggerResponse == null || !isSuccess(triggerResponse)) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝营销弹窗触发失败 taskId=$taskId raw=${triggerResponse ?: "EMPTY"}")
            return
        }
        val syncResponse = parseResponse(GoldenBeanRpcCall.sync(listOf("MARKETING_POPUP")))
        if (syncResponse == null || !isSuccess(syncResponse)) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝营销弹窗回查失败 taskId=$taskId raw=${syncResponse ?: "EMPTY"}")
        }
    }

    fun queryMallItems() {
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

    fun drawGameCenterAwardIfAvailable(): Boolean {
        while (true) {
            val beforeResponse = parseResponse(GoldenBeanRpcCall.queryGameList())
            if (beforeResponse == null || !isSuccess(beforeResponse)) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆乐园抽奖资格查询失败 raw=${beforeResponse ?: "EMPTY"}")
                return false
            }
            val beforeRights = beforeResponse.optJSONObject("gameCenterDrawRights")
            if (beforeRights == null || !beforeRights.has("quotaCanUse")) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆乐园抽奖资格缺少quotaCanUse raw=$beforeResponse")
                return false
            }
            val beforeQuota = beforeRights.optInt("quotaCanUse", 0)
            if (beforeQuota <= 0) {
                Log.orchard("金豆乐园抽奖[服务端无可用次数]")
                return true
            }

            val drawResponse = parseResponse(GoldenBeanRpcCall.drawGameCenterAward())
            if (drawResponse == null || !isSuccess(drawResponse)) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆乐园抽奖失败 raw=${drawResponse ?: "EMPTY"}")
                return false
            }

            val afterResponse = parseResponse(GoldenBeanRpcCall.queryGameList())
            if (afterResponse == null || !isSuccess(afterResponse)) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆乐园抽奖回查失败 raw=${afterResponse ?: "EMPTY"}")
                return false
            }
            val afterRights = afterResponse.optJSONObject("gameCenterDrawRights")
            if (afterRights == null || !afterRights.has("quotaCanUse")) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆乐园抽奖回查缺少quotaCanUse raw=$afterResponse")
                return false
            }
            val afterQuota = afterRights.optInt("quotaCanUse", beforeQuota)
            val usedQuota = afterRights.optInt("usedQuota", -1)
            if (afterQuota >= beforeQuota) {
                Log.error(
                    GOLDEN_BEAN_BLACKLIST_MODULE,
                    "金豆乐园抽奖回查未确认配额推进 quotaCanUse=$beforeQuota->$afterQuota raw=$afterResponse",
                )
                return false
            }
            Log.orchard("金豆乐园抽奖回查 quotaCanUse=$beforeQuota->$afterQuota usedQuota=$usedQuota")
            if (afterQuota <= 0) {
                return true
            }
        }
    }

    fun runMiner() {
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
            Log.orchard("金猫矿工[服务端未启用]")
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
            Log.orchard("金猫矿工[服务端无可抓取次数]")
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

            val syncResponse = parseResponse(GoldenBeanRpcCall.sync(listOf("JAR_INFO"), GoldenBeanRpcCall.MINER_SOURCE))
            if (syncResponse == null || !isSuccess(syncResponse)) {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金猫矿工抓取后金豆罐回查失败 raw=${syncResponse ?: "EMPTY"}")
                return
            }
            if (grabResponse.optBoolean("needAd", false)) {
                Log.orchard("金猫矿工[服务端要求广告，保留待人工处理]")
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
        Log.orchard(
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
            Log.orchard(
                "金豆夺宝商城[${listType}只读] itemId=${itemId.ifBlank { "UNKNOWN" }} " +
                    "itemStatus=${item.optString("itemStatus").ifBlank { "UNKNOWN" }} " +
                    "skuId=${firstSku?.optString("skuId").orEmpty().ifBlank { "UNKNOWN" }} " +
                    "skuStatus=${firstSku?.optString("skuRuleResult").orEmpty().ifBlank { "UNKNOWN" }} " +
                    "stock=${item.optInt("remainStockCounts", -1)} " +
                    "priceCent=${minPrice?.optInt("cent", -1)}",
            )
        }
    }

    fun actionResult(
        item: TaskFlowItem,
        response: JSONObject,
        action: String,
    ): TaskFlowActionResult {
        if (isSuccess(response)) {
            return TaskFlowActionResult.success(refreshAfterAction = true, progressChanged = false)
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
            stopCurrentRound = failureType == TaskRpcFailureType.RETRYABLE_RPC,
        )
    }

    fun emptyResponseFailure(
        item: TaskFlowItem,
        action: String,
    ): TaskFlowActionResult =
        TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.RETRYABLE_RPC,
            message = "RPC返回空",
            rpc = "GoldenBeanRpcCall.$action",
            detail = "taskId=${item.type.ifBlank { item.id }} actionType=${item.actionType.ifBlank { "UNKNOWN" }} " +
                "sceneCode=${item.sceneCode.ifBlank { "UNKNOWN" }}",
            stopCurrentRound = true,
        )

    fun missingTaskTypeFailure(
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

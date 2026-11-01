package io.github.aoguai.sesameag.task.antMember

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import kotlinx.coroutines.CancellationException
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TaskBlacklist
import org.json.JSONArray
import org.json.JSONObject

private const val YEB_TASK_BLACKLIST_MODULE = "余额宝"
private const val YEB_TASK_SOURCE_KEY = "_taskSource"

private enum class YebExpGoldTaskSource {
    PROMO_TASK_LIST,
    MAIN_QUERY
}

private data class YebExpGoldTaskRegistry(
    val groups: LinkedHashMap<String, YebExpGoldTaskGroup> = LinkedHashMap(),
    val taskIdToFingerprint: LinkedHashMap<String, String> = LinkedHashMap()
) {
    fun findGroupByTaskId(taskId: String): YebExpGoldTaskGroup? {
        val fingerprint = taskIdToFingerprint[taskId] ?: return null
        return groups[fingerprint]
    }
}

private data class YebExpGoldTaskGroup(
    val fingerprint: String,
    val aliasTaskIds: LinkedHashSet<String> = LinkedHashSet(),
    val taskBySource: LinkedHashMap<YebExpGoldTaskSource, JSONObject> = LinkedHashMap()
) {
    fun putTask(source: YebExpGoldTaskSource, taskId: String, task: JSONObject) {
        if (taskId.isNotBlank()) {
            aliasTaskIds.add(taskId)
        }
        val existingTask = taskBySource[source]
        if (existingTask == null || shouldReplaceYebExpGoldTask(existingTask, task)) {
            taskBySource[source] = task
        }
    }

    fun getTaskForSource(source: YebExpGoldTaskSource): JSONObject? = taskBySource[source]

    fun getPreferredActionSource(): YebExpGoldTaskSource {
        return if (taskBySource.containsKey(YebExpGoldTaskSource.PROMO_TASK_LIST)) {
            YebExpGoldTaskSource.PROMO_TASK_LIST
        } else {
            YebExpGoldTaskSource.MAIN_QUERY
        }
    }

    fun getActionTask(): JSONObject? {
        return getTaskForSource(getPreferredActionSource()) ?: getDisplayTask()
    }

    fun getActionTaskId(): String? {
        return getActionTask()
            ?.optString("taskId")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun getDisplayTask(): JSONObject? {
        return taskBySource.values.maxByOrNull(::getYebExpGoldTaskDisplayScore)
    }

    fun getRunStatus(): String {
        return getActionTask()?.let(::getYebExpGoldTaskRunStatus).orEmpty()
    }

    fun getTitle(): String {
        return getDisplayTask()
            ?.let { getYebExpGoldTaskTitle(it, aliasTaskIds.firstOrNull().orEmpty()) }
            .orEmpty()
            .ifBlank { aliasTaskIds.firstOrNull().orEmpty() }
    }

    fun hasAutoAction(): Boolean = getActionTaskId() != null
}

internal fun AntMember.handleYebExpGoldTasks() {
    try {
        var queryResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain())
        if (!isYebExpGoldSuccess(queryResponse)) {
            Log.error("AntMemberYebExpGold", "任务查询失败 rpc=com.alipay.yebscenebff.needle.yebExpGold.queryMain raw=$queryResponse")
            if (!RpcDailyCircuit.isStopResponse(queryResponse) && !RpcOfflineRisk.isHardBlocked(queryResponse) &&
                !ApplicationHookConstants.isOffline()
            ) handleYebExpGoldCertVouchers()
            return
        }

        val manualTaskTitles = LinkedHashSet<String>()
        var handledTask = trySignInYebExpGold(queryResponse, manualTaskTitles)
        if (ApplicationHookConstants.isOffline()) return
        val adapter = YebExpGoldTaskFlowAdapter(this)
        val runResult = TaskFlowEngine(adapter, roundSleepMs = 425L).run()
        handledTask = runResult.progressChanged || handledTask
        if (runResult.interrupted || ApplicationHookConstants.isOffline()) return

        queryResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain())
        if (isYebExpGoldSuccess(queryResponse)) {
            val vouchersConverted = handleYebExpGoldCertVouchers()
            handledTask = vouchersConverted || handledTask
            if (ApplicationHookConstants.isOffline()) return
            if (vouchersConverted) {
                queryResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain())
            }
            if (isYebExpGoldSuccess(queryResponse)) {
                handledTask = handleYebExpGoldExchange(queryResponse) || handledTask
            } else {
                Log.error("AntMemberYebExpGold", "券转换后余额查询失败 rpc=com.alipay.yebscenebff.needle.yebExpGold.queryMain raw=$queryResponse")
            }
        } else {
            Log.error("AntMemberYebExpGold", "任务刷新失败 rpc=com.alipay.yebscenebff.needle.yebExpGold.queryMain raw=$queryResponse")
        }
        if (!handledTask) Log.member("余额宝体验金任务: 本轮未确认任务或兑换进展")
        if (manualTaskTitles.isNotEmpty()) {
            Log.member("余额宝体验金任务待确认: "+manualTaskTitles.joinToString("、"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.printStackTrace("AntMemberYebExpGold", "handleYebExpGoldTasks err:", t)
    }
}

private class YebExpGoldTaskFlowAdapter(private val member: AntMember) : TaskFlowAdapter {
    override val moduleName = YEB_TASK_BLACKLIST_MODULE
    override val flowName = "余额宝体验金任务"
    override val continueCurrentRoundOnRetryableFailure = true
    private var registry = YebExpGoldTaskRegistry()
    private var querySucceeded = false
    private var queryRpc = ""

    override fun query(): JSONObject {
        querySucceeded = false
        queryRpc = "com.alipay.yebscenebff.needle.yebExpGold.queryMain"
        val mainResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain())
        if (!isYebExpGoldSuccess(mainResponse) || RpcDailyCircuit.isStopResponse(mainResponse) ||
            RpcOfflineRisk.isHardBlocked(mainResponse) || ApplicationHookConstants.isOffline()
        ) return mainResponse
        queryRpc = "com.alipay.yebpromobff.promosdk2024.task.query"
        val taskResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldTaskList())
        if (!isYebExpGoldSuccess(taskResponse) || RpcDailyCircuit.isStopResponse(taskResponse) ||
            RpcOfflineRisk.isHardBlocked(taskResponse) || ApplicationHookConstants.isOffline()
        ) return taskResponse
        if (mainResponse.optJSONObject("resultData")?.optJSONObject("taskData") == null ||
            taskResponse.optJSONObject("result")?.optJSONArray("taskDetailList") == null
        ) {
            logError("双来源任务快照缺少任务列表 main=$mainResponse promo=$taskResponse")
            return taskResponse
        }
        registry = queryYebExpGoldTaskRegistry(mainResponse, taskResponse)
        querySucceeded = true
        return mainResponse
    }

    override fun isQuerySuccess(response: JSONObject): Boolean = querySucceeded

    override fun extractItems(response: JSONObject): List<TaskFlowItem> {
        val items = LinkedHashMap<String, TaskFlowItem>()
        for (group in registry.groups.values) {
            val task = group.getActionTask()
            if (task != null && isYebExpGoldTaskReceived(task)) markYebExpGoldTaskGroupHandledToday(group)
            items[group.fingerprint] = TaskFlowItem(
                id = group.fingerprint,
                title = group.getTitle(),
                status = group.getRunStatus(),
                type = group.getPreferredActionSource().name,
                blacklistKeys = group.aliasTaskIds.toList() + group.getTitle(),
                raw = task,
            )
        }
        val completeList = getYebExpGoldCompleteList(response)
        for (index in 0 until completeList.length()) {
            val reward = completeList.optJSONObject(index) ?: continue
            val taskId = reward.optString("taskId").trim()
            val group = findYebExpGoldTaskGroupForRewardItem(reward, registry)
                ?: createFallbackYebExpGoldTaskGroup(taskId, reward, registry)
            registry.groups[group.fingerprint] = group
            items[group.fingerprint] = TaskFlowItem(
                id = group.fingerprint,
                title = getYebExpGoldCompletedTitle(reward, group.getDisplayTask(), taskId),
                status = group.getRunStatus(),
                type = group.getPreferredActionSource().name,
                actionType = TaskFlowAction.RECEIVE.logName,
                blacklistKeys = group.aliasTaskIds.toList() + group.getTitle(),
                raw = reward,
            )
        }
        return items.values.toList()
    }

    override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
        val group = registry.groups[item.id] ?: return TaskFlowPhase.UNKNOWN
        if (!group.hasAutoAction()) return TaskFlowPhase.UNKNOWN
        if (item.actionType == TaskFlowAction.RECEIVE.logName) return TaskFlowPhase.REWARD_READY
        return when (group.getRunStatus()) {
            "complete" -> TaskFlowPhase.TERMINAL
            "not_sign", "sign" -> TaskFlowPhase.SIGNUP_REQUIRED
            "not_done" -> TaskFlowPhase.READY_TO_COMPLETE
            else -> TaskFlowPhase.UNKNOWN
        }
    }

    override fun shouldSkipByTodayState(item: TaskFlowItem): Boolean =
        item.actionType != TaskFlowAction.RECEIVE.logName &&
            registry.groups[item.id]?.let(::hasHandledYebExpGoldTaskGroupToday) == true

    override fun signup(item: TaskFlowItem): TaskFlowActionResult = complete(item)

    override fun complete(item: TaskFlowItem): TaskFlowActionResult =
        tryHandleYebExpGoldTaskGroup(member, registry.groups.getValue(item.id), registry)

    override fun receive(item: TaskFlowItem): TaskFlowActionResult {
        val group = registry.groups.getValue(item.id)
        val source = group.getPreferredActionSource()
        val taskId = group.getActionTaskId().orEmpty()
        val response = if (source == YebExpGoldTaskSource.MAIN_QUERY) {
            triggerYebExpGoldTaskBySource(taskId, source)
        } else {
            completeYebExpGoldTaskBySource(taskId, source)
        }
        return handleYebExpGoldTaskActionResult(member, group, item.title, source, taskId, registry, response)
    }

    override fun onQueryFailed(response: JSONObject) {
        logError("$flowName[任务查询失败] rpc=$queryRpc raw=$response")
    }

    override fun logInfo(message: String) = Log.member(message)

    override fun logError(message: String) = Log.error("AntMemberYebExpGold", message)
}

private fun handleYebExpGoldCertVouchers(): Boolean {
    var queryResponse = runCatching {
        JSONObject(AntMemberYebExpGoldRpcCall.queryYebTrialCertVoucher())
    }.onFailure {
        if (it is CancellationException) throw it
        Log.printStackTrace("AntMemberYebExpGold", "queryYebTrialCertVoucher err:", it)
    }.getOrNull() ?: return false

    if (!isYebExpGoldSuccess(queryResponse)) {
        Log.error("AntMemberYebExpGold", "余额宝体验金券查询失败 rpc=alipay.yebprod.query.queryYebTrialCertVoucher raw=$queryResponse")
        return false
    }

    var pendingCount =
        getYebExpGoldVoucherCount(queryResponse) ?: run {
            Log.error("AntMemberYebExpGold", "余额宝体验金券查询缺少totalCount/certVoucherInfoList rpc=alipay.yebprod.query.queryYebTrialCertVoucher raw=$queryResponse")
            return false
        }
    if (pendingCount == 0) return false

    var handled = false
    while (pendingCount > 0) {
        val convertResponse = runCatching {
            JSONObject(AntMemberYebExpGoldRpcCall.convertYebExpGoldVoucher())
        }.onFailure {
            if (it is CancellationException) throw it
            Log.printStackTrace("AntMemberYebExpGold", "convertYebExpGoldVoucher err:", it)
        }.getOrNull() ?: return handled

        if (!isYebExpGoldVoucherConvertSuccess(convertResponse)) {
            Log.error("AntMemberYebExpGold", "余额宝体验金券使用失败 rpc=com.alipay.yebscenebff.needle.yebExpGoldVoucherConvert raw=$convertResponse")
            return handled
        }

        handled = true
        val rewardText = getYebExpGoldVoucherConvertText(convertResponse)
        Log.member("余额宝体验金💰[券自动使用]#${rewardText.ifBlank { "成功" }}")

        val delayRefreshTime = convertResponse.optLong("delayRefreshTime", 0L)
        if (delayRefreshTime > 0L) {
            // 券使用接口明确返回 delayRefreshTime，按该时长等待后再回查券状态，避免读到更新前的数据。
            CoroutineUtils.sleepCompat(delayRefreshTime)
        }

        queryResponse = runCatching {
            JSONObject(AntMemberYebExpGoldRpcCall.queryYebTrialCertVoucher())
        }.onFailure {
            if (it is CancellationException) throw it
            Log.printStackTrace("AntMemberYebExpGold", "queryYebTrialCertVoucher after convert err:", it)
        }.getOrNull() ?: return handled
        if (!isYebExpGoldSuccess(queryResponse)) {
            Log.error("AntMemberYebExpGold", "余额宝体验金券使用后回查失败 rpc=alipay.yebprod.query.queryYebTrialCertVoucher raw=$queryResponse")
            return handled
        }
        val remainingCount = getYebExpGoldVoucherCount(queryResponse)
        if (remainingCount == null) {
            Log.error("AntMemberYebExpGold", "余额宝体验金券使用后回查缺少totalCount/certVoucherInfoList rpc=alipay.yebprod.query.queryYebTrialCertVoucher raw=$queryResponse")
            return handled
        }
        if (remainingCount == 0) {
            Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_VOUCHER_CONVERT_DONE)
            return handled
        }
        if (remainingCount >= pendingCount) {
            Log.member("余额宝体验金券使用后库存未减少: $pendingCount->$remainingCount，停止当前链路")
            return handled
        }
        Log.member("余额宝体验金券使用后仍有待使用券: $remainingCount")
        pendingCount = remainingCount
    }
    return handled
}

private fun getYebExpGoldVoucherCount(response: JSONObject): Int? {
    if (response.has("totalCount") && !response.isNull("totalCount")) {
        return response.optInt("totalCount").coerceAtLeast(0)
    }
    return response.optJSONArray("certVoucherInfoList")?.length()
}

private fun isYebExpGoldVoucherConvertSuccess(response: JSONObject): Boolean {
    if (!isYebExpGoldSuccess(response)) {
        return false
    }
    val convertResults = response.optJSONArray("convertResults") ?: return false
    for (index in 0 until convertResults.length()) {
        val result = convertResults.optJSONObject(index) ?: continue
        val value = result.optJSONObject("value")
        if (result.optString("status").equals("fulfilled", ignoreCase = true) &&
            value?.optBoolean("success") == true
        ) {
            return true
        }
    }
    return false
}

private fun getYebExpGoldVoucherConvertText(response: JSONObject): String {
    val convertResults = response.optJSONArray("convertResults") ?: return ""
    val texts = ArrayList<String>()
    for (index in 0 until convertResults.length()) {
        val result = convertResults.optJSONObject(index) ?: continue
        val value = result.optJSONObject("value") ?: continue
        val amount = value.opt("amount")?.toString().orEmpty()
        val toastView = value.optString("toastView")
        when {
            amount.isNotBlank() -> texts.add("${amount}元")
            toastView.isNotBlank() -> texts.add(toastView)
        }
    }
    if (texts.isNotEmpty()) {
        return texts.joinToString("、")
    }
    return response.optJSONObject("firstVoucher")
        ?.optString("amount")
        .orEmpty()
}

private fun trySignInYebExpGold(
    queryResponse: JSONObject,
    manualTaskTitles: MutableSet<String>
): Boolean {
    if (Status.hasFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_SIGN_DONE)) {
        return false
    }

    val todaySign = getYebExpGoldTodaySignItem(queryResponse) ?: return false
    val signStatus = todaySign.optJSONObject("signInfo")
        ?.optString("signStatus")
        .orEmpty()
        .uppercase()
    if (signStatus == "SIGNED") {
        Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_SIGN_DONE)
        return false
    }
    if (signStatus != "TO_SIGNED" && signStatus != "UNSIGNED") {
        Log.error("AntMemberYebExpGold", "余额宝体验金签到状态未确认 status=$signStatus raw=$todaySign")
        return false
    }

    val amount = todaySign.optJSONObject("prizeInfo")
        ?.opt("prizeAmount")
        ?.toString()
        .orEmpty()
    val title = if (amount.isBlank()) "余额宝体验金签到" else "余额宝体验金签到(${amount}元)"
    if (TaskBlacklist.isTaskInBlacklist(YEB_TASK_BLACKLIST_MODULE, title)) {
        Log.member("任务在自动跳过列表(黑名单)中，跳过[$title]")
        return false
    }

    val signResponse = JSONObject(AntMemberYebExpGoldRpcCall.signInYebExpGold())
    if (!isYebExpGoldSuccess(signResponse)) {
        Log.error("AntMemberYebExpGold", "余额宝体验金签到失败 rpc=com.alipay.yebscenebff.needle.yebExpGold.signIn raw=$signResponse")
    }
    if (RpcDailyCircuit.isStopResponse(signResponse) || RpcOfflineRisk.isHardBlocked(signResponse) ||
        ApplicationHookConstants.isOffline()
    ) {
        manualTaskTitles.add(title)
        return false
    }
    val prizeOrders = signResponse.optJSONObject("resultData")
        ?.optJSONObject("resultData")?.optJSONArray("prizeOrderDTOList")
    val rewardSent = prizeOrders != null && (0 until prizeOrders.length()).any { index ->
        prizeOrders.optJSONObject(index)?.optString("sendStatus") == "SUCCESS"
    }
    if (!rewardSent) {
        val refreshed = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain())
        if (!isYebExpGoldSuccess(refreshed)) {
            Log.error("AntMemberYebExpGold", "签到后状态回查失败 rpc=com.alipay.yebscenebff.needle.yebExpGold.queryMain raw=$refreshed")
            manualTaskTitles.add(title)
            return false
        }
        val refreshedStatus = getYebExpGoldTodaySignItem(refreshed)
            ?.optJSONObject("signInfo")?.optString("signStatus")
        if (refreshedStatus != "SIGNED") {
            manualTaskTitles.add(title)
            return false
        }
    }
    logYebExpGoldSignInRewards(amount, signResponse)
    Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_SIGN_DONE)
    return true
}

private fun logYebExpGoldSignInRewards(
    fallbackAmount: String,
    response: JSONObject
) {
    val prizeOrderList = response.optJSONObject("resultData")
        ?.optJSONObject("resultData")
        ?.optJSONArray("prizeOrderDTOList")
    if (prizeOrderList != null && prizeOrderList.length() > 0) {
        for (index in 0 until prizeOrderList.length()) {
            val order = prizeOrderList.optJSONObject(index) ?: continue
            val memo = order.optJSONObject("customMemo")
            val amount = memo?.optString("PRIZE_AMOUNT").orEmpty()
            val unit = memo?.optString("PRIZE_UNIT").orEmpty()
            val prizeName = order.optString("prizeName")
            val rewardText = when {
                amount.isNotBlank() -> amount + unit.ifBlank { "元" }
                prizeName.isNotBlank() -> prizeName
                fallbackAmount.isNotBlank() -> fallbackAmount + "元"
                else -> "成功"
            }
            Log.member("余额宝体验金💰[签到成功]#$rewardText")
        }
        return
    }

    val rewardText = if (fallbackAmount.isNotBlank()) "${fallbackAmount}元" else "成功"
    Log.member("余额宝体验金💰[签到成功]#$rewardText")
}

private fun getYebExpGoldErrorDesc(response: JSONObject): String {
    return response.optString("resultDesc")
        .ifBlank { response.optString("resultView") }
        .ifBlank { response.optString("errorMessage") }
        .ifBlank { response.optString("memo") }
        .ifBlank { response.optString("desc") }
        .ifBlank { response.optString("message") }
        .ifBlank { response.toString() }
}

private fun getYebExpGoldTodaySignItem(queryResponse: JSONObject): JSONObject? {
    val signList = queryResponse.optJSONObject("resultData")
        ?.optJSONObject("signInData")
        ?.optJSONArray("list")
        ?: return null
    for (index in 0 until signList.length()) {
        val signItem = signList.optJSONObject(index) ?: continue
        val signInfo = signItem.optJSONObject("signInfo")
        val signDateDesc = signInfo?.optString("signDateDesc").orEmpty()
        val displayDate = signItem.optString("displayDate")
        if (signDateDesc == "TODAY" || displayDate.contains("今天")) {
            return signItem
        }
    }
    return null
}

private fun tryHandleYebExpGoldTaskGroup(
    member: AntMember,
    group: YebExpGoldTaskGroup,
    registry: YebExpGoldTaskRegistry
): TaskFlowActionResult {
    val title = group.getTitle()
    val actionTaskId = group.getActionTaskId().orEmpty()
    if (actionTaskId.isBlank()) {
        return TaskFlowActionResult.failure(
            TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            message = "任务缺少执行标识",
            detail = "taskName=$title source=${group.getPreferredActionSource()}",
        )
    }
    val source = group.getPreferredActionSource()
    val actionResponse = when {
        source == YebExpGoldTaskSource.PROMO_TASK_LIST ->
            completeYebExpGoldTaskBySource(actionTaskId, source)
        group.getRunStatus() == "not_sign" || group.getRunStatus() == "sign" ->
            triggerYebExpGoldTaskBySource(actionTaskId, source)
        else -> completeYebExpGoldTaskBySource(actionTaskId, source)
    }
    return handleYebExpGoldTaskActionResult(member, group, title, source, actionTaskId, registry, actionResponse)
}

private fun handleYebExpGoldTaskActionResult(
    member: AntMember,
    group: YebExpGoldTaskGroup,
    title: String,
    source: YebExpGoldTaskSource,
    actionTaskId: String,
    registry: YebExpGoldTaskRegistry,
    actionResponse: JSONObject
): TaskFlowActionResult {
    val rpc = when (source) {
        YebExpGoldTaskSource.PROMO_TASK_LIST -> "com.alipay.yebpromobff.promosdk2024.task.complete"
        YebExpGoldTaskSource.MAIN_QUERY -> "com.alipay.yebscenebff.promosdk.index.forward"
    }
    val detail = "taskId=$actionTaskId taskName=$title source=$source"
    val code = actionResponse.optString("resultCode")
        .ifBlank { actionResponse.optString("code") }
        .ifBlank { actionResponse.optString("errorCode") }
        .ifBlank { actionResponse.optString("errCode") }
    val message = getYebExpGoldErrorDesc(actionResponse)
    if (RpcDailyCircuit.isStopResponse(actionResponse) || RpcOfflineRisk.isHardBlocked(actionResponse) ||
        ApplicationHookConstants.isOffline()
    ) {
        Log.error("AntMemberYebExpGold", "任务接口停止 rpc=$rpc $detail raw=$actionResponse")
        return TaskFlowActionResult.failure(
            TaskRpcFailureType.RETRYABLE_RPC, code = code, message = message, rpc = rpc,
            raw = actionResponse.toString(), detail = detail, stopCurrentRound = true,
        )
    }
    if (hasYebExpGoldSendSuccess(actionResponse)) {
        logYebExpGoldRewards(title, actionResponse)
        markYebExpGoldTaskGroupHandledToday(group)
        return TaskFlowActionResult.success(refreshAfterAction = true)
    }

    val accepted = isYebExpGoldActionSuccess(actionResponse)
    val failureType = if (accepted) null else member.classifyMemberDomainTaskFailure(code, message, actionResponse)
    if (!accepted && failureType != TaskRpcFailureType.TERMINAL_DONE) {
        return TaskFlowActionResult.failure(
            failureType ?: TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            code = code, message = message, rpc = rpc, raw = actionResponse.toString(), detail = detail,
            continueCurrentRoundOnFailure = true,
        ).copy(refreshAfterAction = true)
    }

    val previousStatus = group.getRunStatus()
    val verifiedResponse = queryYebExpGoldTaskById(actionTaskId, source)
    val queryRpc = when (source) {
        YebExpGoldTaskSource.PROMO_TASK_LIST -> "com.alipay.yebpromobff.promosdk2024.task.queryTaskByTaskId"
        YebExpGoldTaskSource.MAIN_QUERY -> "com.alipay.yebscenebff.needle.yebExpGold.queryMain"
    }
    val queryStopped = RpcDailyCircuit.isStopResponse(verifiedResponse) ||
        RpcOfflineRisk.isHardBlocked(verifiedResponse) || ApplicationHookConstants.isOffline()
    if (queryStopped || !isYebExpGoldSuccess(verifiedResponse)) {
        Log.error("AntMemberYebExpGold", "任务状态回查失败 rpc=$queryRpc $detail raw=$verifiedResponse")
        val queryCode = verifiedResponse.optString("resultCode")
            .ifBlank { verifiedResponse.optString("code") }
            .ifBlank { verifiedResponse.optString("errorCode") }
        val queryMessage = getYebExpGoldErrorDesc(verifiedResponse)
        val queryFailure = if (queryStopped) TaskRpcFailureType.RETRYABLE_RPC else
            member.classifyMemberDomainTaskFailure(queryCode, queryMessage, verifiedResponse)
        return TaskFlowActionResult.failure(
            if (queryFailure == TaskRpcFailureType.TERMINAL_DONE) TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW else queryFailure,
            code = queryCode, message = queryMessage, rpc = queryRpc, raw = verifiedResponse.toString(),
            detail = detail, stopCurrentRound = queryStopped, continueCurrentRoundOnFailure = !queryStopped,
        ).copy(refreshAfterAction = !queryStopped)
    }
    val verifiedTask = findYebExpGoldTaskByIdInNode(verifiedResponse, actionTaskId, source)
    if (verifiedTask != null) {
        val verifiedGroup = mergeYebExpGoldTaskIntoRegistry(verifiedTask, source, registry) ?: group
        if (isYebExpGoldTaskReceived(verifiedTask)) {
            markYebExpGoldTaskGroupHandledToday(group)
            markYebExpGoldTaskGroupHandledToday(verifiedGroup)
            Log.member("余额宝体验金任务[$title]#回查确认完成")
            return TaskFlowActionResult.success(refreshAfterAction = true)
        }
    }
    if (!accepted) {
        return TaskFlowActionResult.failure(
            TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, code = code, message = message, rpc = rpc,
            raw = actionResponse.toString(), detail = "$detail 回查未确认终态",
            continueCurrentRoundOnFailure = true,
        ).copy(refreshAfterAction = true)
    }
    return TaskFlowActionResult.success(
        refreshAfterAction = true,
        progressChanged = verifiedTask != null && getYebExpGoldTaskRunStatus(verifiedTask) != previousStatus,
    )
}

private fun getYebExpGoldCompleteList(queryResponse: JSONObject): JSONArray {
    return queryResponse.optJSONObject("resultData")
        ?.optJSONObject("taskData")
        ?.optJSONArray("completeList")
        ?: JSONArray()
}

private fun getYebExpGoldCompletedTitle(
    rewardItem: JSONObject,
    task: JSONObject?,
    defaultTitle: String
): String {
    return rewardItem.optJSONObject("ext")
        ?.optJSONObject("TASK_MORPHO_DETAIL")
        ?.optString("title")
        .orEmpty()
        .ifBlank {
            rewardItem.optJSONObject("ext")
                ?.optJSONObject("TASK_MORPHO_DETAIL")
                ?.optString("taskMainTitle")
                .orEmpty()
        }
        .ifBlank { task?.let { getYebExpGoldTaskTitle(it, defaultTitle) }.orEmpty() }
        .ifBlank { defaultTitle }
}

private fun handleYebExpGoldExchange(queryResponse: JSONObject): Boolean {
    if (Status.hasFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_EXCHANGE_DONE)) {
        return false
    }

    val resultData = queryResponse.optJSONObject("resultData") ?: return false
    val balanceText = resultData.optString("balance")
    val balance = balanceText.toDoubleOrNull() ?: return false
    val threshold = when (val thresholdValue = resultData.opt("subThreshold")) {
        is Number -> thresholdValue.toDouble()
        is String -> thresholdValue.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    val thresholdText = resultData.opt("subThreshold")?.toString().orEmpty()

    if (balance <= 0.0) return false

    if (threshold > 0.0 && balance < threshold) {
        Log.member("余额宝体验金未达兑换门槛: 当前$balanceText，最低需${thresholdText.ifBlank { threshold.toString() }}")
        return false
    }

    // 当前兑换活动不依赖已有的已激活资产，活动参数以成功兑换抓包的固定参数为准，否则会出现失败。
    val exchangeResponse = JSONObject(
        AntMemberYebExpGoldRpcCall.exchangeYebExpGold(
            campId = "CP152735172",
            prizeId = "PZ1144215101",
            exchangeAmount = balanceText
        )
    )
    if (!isYebExpGoldSuccess(exchangeResponse)) {
        Log.error("AntMemberYebExpGold", "余额宝体验金兑换失败: ${getYebExpGoldErrorDesc(exchangeResponse)} rpc=com.alipay.yebscenebff.expgold.index.exchange raw=$exchangeResponse")
        return false
    }

    val couponId = exchangeResponse.optJSONObject("result")
        ?.optString("equityNo")
        .orEmpty()
    if (couponId.isBlank()) {
        Log.error("AntMemberYebExpGold", "余额宝体验金兑换成功但缺少激活凭证 rpc=com.alipay.yebscenebff.expgold.index.exchange raw=$exchangeResponse")
        return false
    }

    val activeResponse = JSONObject(AntMemberYebExpGoldRpcCall.activeYebTrial(couponId))
    if (!isYebExpGoldSuccess(activeResponse)) {
        Log.error("AntMemberYebExpGold", "余额宝体验金激活失败: ${getYebExpGoldErrorDesc(activeResponse)} rpc=alipay.yebprod.promo.yebTrial.active raw=$activeResponse")
        return false
    }

    val amountText = activeResponse.optJSONObject("amount")
        ?.opt("amount")
        ?.toString()
        .orEmpty()
        .ifBlank { balanceText }
    val confirmDate = activeResponse.optString("confirmDate")
    val profitDate = activeResponse.optString("profitDate")
    val extraInfo = buildString {
        if (confirmDate.isNotBlank()) {
            append("[确认:$confirmDate]")
        }
        if (profitDate.isNotBlank()) {
            append("[收益:$profitDate]")
        }
    }
    Log.member("余额宝体验金💰[兑换激活]#${amountText}元$extraInfo")
    Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_EXCHANGE_DONE)
    val assetResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebTrialAsset())
    if (!isYebExpGoldSuccess(assetResponse)) {
        Log.error("AntMemberYebExpGold", "体验金激活成功后资产回查失败: ${getYebExpGoldErrorDesc(assetResponse)} raw=$assetResponse")
    }
    return true
}

private fun queryYebExpGoldTaskRegistry(
    mainResponse: JSONObject,
    taskListResponse: JSONObject
): YebExpGoldTaskRegistry {
    val registry = YebExpGoldTaskRegistry()
    val taskDetailList = taskListResponse.getJSONObject("result").getJSONArray("taskDetailList")
    for (index in 0 until taskDetailList.length()) {
        val task = taskDetailList.optJSONObject(index) ?: continue
        mergeYebExpGoldTaskIntoRegistry(task, YebExpGoldTaskSource.PROMO_TASK_LIST, registry)
    }
    collectYebExpGoldTasks(mainResponse, registry, YebExpGoldTaskSource.MAIN_QUERY)
    return registry
}

private fun queryYebExpGoldTaskById(taskId: String, source: YebExpGoldTaskSource): JSONObject {
    val response = when (source) {
        YebExpGoldTaskSource.PROMO_TASK_LIST -> AntMemberYebExpGoldRpcCall.queryYebExpGoldTaskById(taskId)
        YebExpGoldTaskSource.MAIN_QUERY -> AntMemberYebExpGoldRpcCall.queryYebExpGoldMain(true, taskId)
    }
    return JSONObject(response)
}

private fun collectYebExpGoldTasks(
    node: Any?,
    registry: YebExpGoldTaskRegistry,
    source: YebExpGoldTaskSource
) {
    when (node) {
        is JSONObject -> {
            mergeYebExpGoldTaskIntoRegistry(node, source, registry)
            val keys = node.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                collectYebExpGoldTasks(node.opt(key), registry, source)
            }
        }

        is JSONArray -> {
            for (index in 0 until node.length()) {
                collectYebExpGoldTasks(node.opt(index), registry, source)
            }
        }
    }
}

private fun mergeYebExpGoldTaskIntoRegistry(
    task: JSONObject,
    source: YebExpGoldTaskSource,
    registry: YebExpGoldTaskRegistry
): YebExpGoldTaskGroup? {
    val taskId = task.optString("taskId").trim()
    if (taskId.isBlank() || !hasTrackableYebExpGoldTaskStatus(task)) {
        return null
    }
    markYebExpGoldTaskSource(task, source)
    val fingerprint = buildYebExpGoldTaskFingerprint(task, taskId)
    val group = registry.groups.getOrPut(fingerprint) { YebExpGoldTaskGroup(fingerprint = fingerprint) }
    group.putTask(source, taskId, task)
    registry.taskIdToFingerprint[taskId] = fingerprint
    return group
}

private fun findYebExpGoldTaskByIdInNode(
    node: Any?,
    taskId: String,
    source: YebExpGoldTaskSource
): JSONObject? {
    when (node) {
        is JSONObject -> {
            if (taskId == node.optString("taskId").trim() && hasTrackableYebExpGoldTaskStatus(node)) {
                markYebExpGoldTaskSource(node, source)
                return node
            }
            val keys = node.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val matched = findYebExpGoldTaskByIdInNode(node.opt(key), taskId, source)
                if (matched != null) {
                    return matched
                }
            }
        }

        is JSONArray -> {
            for (index in 0 until node.length()) {
                val matched = findYebExpGoldTaskByIdInNode(node.opt(index), taskId, source)
                if (matched != null) {
                    return matched
                }
            }
        }
    }
    return null
}

private fun markYebExpGoldTaskSource(task: JSONObject, source: YebExpGoldTaskSource) {
    task.put(YEB_TASK_SOURCE_KEY, source.name)
}

private fun completeYebExpGoldTaskBySource(
    taskId: String,
    source: YebExpGoldTaskSource
): JSONObject {
    val response = when (source) {
        YebExpGoldTaskSource.PROMO_TASK_LIST -> AntMemberYebExpGoldRpcCall.completeYebExpGoldTask(taskId)
        YebExpGoldTaskSource.MAIN_QUERY -> AntMemberYebExpGoldRpcCall.completeYebExpGoldMainQueryTask(taskId)
    }
    return JSONObject(response)
}

private fun triggerYebExpGoldTaskBySource(
    taskId: String,
    source: YebExpGoldTaskSource
): JSONObject {
    val response = when (source) {
        YebExpGoldTaskSource.PROMO_TASK_LIST -> AntMemberYebExpGoldRpcCall.completeYebExpGoldTask(taskId)
        YebExpGoldTaskSource.MAIN_QUERY -> AntMemberYebExpGoldRpcCall.triggerYebExpGoldMainQueryTask(taskId)
    }
    return JSONObject(response)
}

private fun hasTrackableYebExpGoldTaskStatus(task: JSONObject): Boolean {
    return task.has("simplifiedStatus") ||
        task.optString("taskProcessStatus").isNotBlank()
}

private fun getYebExpGoldTaskRunStatus(task: JSONObject): String {
    val simplifiedStatus = task.optString("simplifiedStatus").trim().lowercase()
    if (simplifiedStatus.isNotBlank()) {
        return simplifiedStatus
    }
    return when (task.optString("taskProcessStatus").trim().uppercase()) {
        "RECEIVE_SUCCESS",
        "HAS_RECEIVED",
        "RECEIVED",
        "DONE",
        "COMPLETE",
        "COMPLETED",
        "SUCCESS" -> "complete"

        "NOT_DONE",
        "WAIT_COMPLETE",
        "SIGNUP_COMPLETE",
        "SIGNUP_COMPLETED",
        "PROCESSING" -> "not_done"

        "NONE_SIGNUP",
        "UN_SIGNUP",
        "SIGNUP_EXPIRED" -> "not_sign"

        else -> ""
    }
}

private fun isYebExpGoldSuccess(jo: JSONObject): Boolean {
    return jo.optBoolean("success") ||
        jo.optString("resultCode") == "100" ||
        jo.optString("code") == "100000000"
}

private fun isYebExpGoldActionSuccess(response: JSONObject): Boolean {
    if (isYebExpGoldSuccess(response)) {
        return true
    }
    if (response.optInt("resultStatus", Int.MIN_VALUE) == 1) {
        return true
    }
    return hasYebExpGoldSendSuccess(response)
}

private fun hasYebExpGoldSendSuccess(response: JSONObject): Boolean {
    val resultObjList = response.optJSONArray("resultObj")
    if (resultObjList != null) {
        for (i in 0 until resultObjList.length()) {
            val resultItem = resultObjList.optJSONObject(i) ?: continue
            val prizeSendDetails = resultItem.optJSONArray("prizeSendDetails") ?: continue
            for (j in 0 until prizeSendDetails.length()) {
                val detail = prizeSendDetails.optJSONObject(j) ?: continue
                if (detail.optString("sendStatus").equals("SUCCESS", ignoreCase = true)) {
                    return true
                }
            }
        }
    }

    val prizeSendOrderList = response.optJSONObject("result")
        ?.optJSONArray("prizeSendOrderList")
    if (prizeSendOrderList != null) {
        for (i in 0 until prizeSendOrderList.length()) {
            val prizeOrder = prizeSendOrderList.optJSONObject(i) ?: continue
            if (prizeOrder.optString("sendStatus").equals("SUCCESS", ignoreCase = true)) {
                return true
            }
        }
    }
    return false
}

private fun isYebExpGoldTaskReceived(task: JSONObject): Boolean {
    val taskProcessStatus = task.optString("taskProcessStatus").uppercase()
    return getYebExpGoldTaskRunStatus(task) == "complete" || taskProcessStatus == "RECEIVE_SUCCESS"
}

private fun getYebExpGoldTaskTitle(
    task: JSONObject,
    defaultTitle: String
): String {
    return task.optString("title")
        .ifBlank { task.optString("taskMainTitle") }
        .ifBlank { task.optJSONObject("taskExtProps")?.optString("title").orEmpty() }
        .ifBlank { defaultTitle }
}

private fun getYebExpGoldTaskButtonText(task: JSONObject): String {
    return task.optString("buttonText")
        .ifBlank { task.optJSONObject("taskExtProps")?.optString("buttonText").orEmpty() }
}

private fun getYebExpGoldTaskLink(task: JSONObject): String {
    return task.optString("link")
        .ifBlank { task.optString("taskGotoUrl") }
        .ifBlank { task.optJSONObject("taskExtProps")?.optString("link").orEmpty() }
        .trim()
}

private fun getYebExpGoldTaskAppletId(task: JSONObject): String {
    return task.optString("appletId")
        .ifBlank { task.optJSONObject("taskExtProps")?.optString("appletId").orEmpty() }
        .trim()
}

private fun getYebExpGoldTaskPrizeIds(task: JSONObject): Set<String> {
    val prizeIds = LinkedHashSet<String>()
    task.optJSONObject("prizeData")
        ?.optJSONObject("prizeBaseInfoDTO")
        ?.optString("prizeId")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(prizeIds::add)
    val validPrizeIdList = task.optJSONArray("validPrizeIdList")
    if (validPrizeIdList != null) {
        for (i in 0 until validPrizeIdList.length()) {
            validPrizeIdList.optString(i).trim().takeIf { it.isNotBlank() }?.let(prizeIds::add)
        }
    }
    val prizeList = task.optJSONArray("prizeList")
    if (prizeList != null) {
        for (i in 0 until prizeList.length()) {
            prizeList.optJSONObject(i)
                ?.optString("prizeId")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(prizeIds::add)
        }
    }
    return prizeIds
}

private fun buildYebExpGoldTaskFingerprint(task: JSONObject, defaultTitle: String): String {
    val titleKey = normalizeYebExpGoldFingerprintPart(getYebExpGoldTaskTitle(task, defaultTitle))
    val linkKey = normalizeYebExpGoldFingerprintPart(getYebExpGoldTaskLink(task))
    val prizeKey = getYebExpGoldTaskPrizeIds(task).sorted().joinToString(",")
    return if (linkKey.isNotBlank() || prizeKey.isNotBlank()) {
        "$titleKey|$linkKey|$prizeKey"
    } else {
        "$titleKey|${normalizeYebExpGoldFingerprintPart(getYebExpGoldTaskAppletId(task))}"
    }
}

private fun normalizeYebExpGoldFingerprintPart(value: String): String {
    return value.trim()
        .replace(Regex("\\s+"), " ")
}

private fun shouldReplaceYebExpGoldTask(existingTask: JSONObject, candidateTask: JSONObject): Boolean {
    val existingScore = getYebExpGoldTaskDisplayScore(existingTask)
    val candidateScore = getYebExpGoldTaskDisplayScore(candidateTask)
    return candidateScore >= existingScore
}

private fun getYebExpGoldTaskDisplayScore(task: JSONObject): Int {
    var score = 0
    if (getYebExpGoldTaskTitle(task, "").isNotBlank()) {
        score += 4
    }
    if (getYebExpGoldTaskLink(task).isNotBlank()) {
        score += 3
    }
    if (getYebExpGoldTaskButtonText(task).isNotBlank()) {
        score += 2
    }
    if (getYebExpGoldTaskRunStatus(task).isNotBlank()) {
        score += 2
    }
    if (getYebExpGoldTaskPrizeIds(task).isNotEmpty()) {
        score += 2
    }
    if (task.optJSONObject("taskExtProps") != null || task.optJSONObject("prizeData") != null) {
        score += 1
    }
    return score
}

private fun hasHandledYebExpGoldTaskGroupToday(group: YebExpGoldTaskGroup): Boolean {
    if (Status.hasFlagToday(buildYebExpGoldTaskGroupSuccessFlag(group.fingerprint))) {
        return true
    }
    return group.aliasTaskIds.any { taskId ->
        Status.hasFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_TASK_PREFIX + taskId)
    }
}

private fun markYebExpGoldTaskGroupHandledToday(group: YebExpGoldTaskGroup) {
    Status.setFlagToday(buildYebExpGoldTaskGroupSuccessFlag(group.fingerprint))
    for (taskId in group.aliasTaskIds) {
        Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_TASK_PREFIX + taskId)
    }
}

private fun buildYebExpGoldTaskGroupSuccessFlag(fingerprint: String): String {
    return StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_TASK_PREFIX + "group:" + fingerprint
}

private fun findYebExpGoldTaskGroupForRewardItem(
    rewardItem: JSONObject,
    registry: YebExpGoldTaskRegistry
): YebExpGoldTaskGroup? {
    val taskId = rewardItem.optString("taskId").trim()
    registry.findGroupByTaskId(taskId)?.let { return it }
    val title = getYebExpGoldCompletedTitle(rewardItem, null, taskId)
    if (title.isBlank()) {
        return null
    }
    val normalizedTitle = normalizeYebExpGoldFingerprintPart(title)
    return registry.groups.values.firstOrNull { group ->
        normalizeYebExpGoldFingerprintPart(group.getTitle()) == normalizedTitle
    }
}

private fun createFallbackYebExpGoldTaskGroup(
    taskId: String,
    rewardItem: JSONObject,
    registry: YebExpGoldTaskRegistry
): YebExpGoldTaskGroup {
    val fallbackTask = JSONObject().apply {
        put("taskId", taskId)
        put("title", getYebExpGoldCompletedTitle(rewardItem, null, taskId))
    }
    return mergeYebExpGoldTaskIntoRegistry(fallbackTask, YebExpGoldTaskSource.MAIN_QUERY, registry)
        ?: YebExpGoldTaskGroup(fingerprint = buildYebExpGoldTaskFingerprint(fallbackTask, taskId)).also { group ->
            group.putTask(YebExpGoldTaskSource.MAIN_QUERY, taskId, fallbackTask)
        }
}

private fun logYebExpGoldRewards(
    title: String,
    response: JSONObject
) {
    val promoSdkResultList = response.optJSONArray("resultObj")
    if (promoSdkResultList != null && promoSdkResultList.length() > 0) {
        val rewardNames = ArrayList<String>()
        for (resultIndex in 0 until promoSdkResultList.length()) {
            val resultItem = promoSdkResultList.optJSONObject(resultIndex) ?: continue
            val prizeSendDetails = resultItem.optJSONArray("prizeSendDetails") ?: continue
            for (detailIndex in 0 until prizeSendDetails.length()) {
                val detail = prizeSendDetails.optJSONObject(detailIndex) ?: continue
                val prizeName = detail.optJSONObject("prizeBaseInfo")
                    ?.optString("prizeName")
                    .orEmpty()
                    .ifBlank {
                        detail.optJSONObject("extInfo")
                            ?.optString("promoPrizeName")
                            .orEmpty()
                    }
                    .ifBlank {
                        detail.optJSONObject("extInfo")
                            ?.optString("title")
                            .orEmpty()
                    }
                if (prizeName.isNotBlank()) {
                    rewardNames.add(prizeName)
                }
            }
        }
        if (rewardNames.isNotEmpty()) {
            rewardNames.forEach { prizeName ->
                Log.member("余额宝体验金💰[$title]#$prizeName")
            }
            return
        }
    }

    val prizeSendOrderList = response.optJSONObject("result")
        ?.optJSONArray("prizeSendOrderList")
    if (prizeSendOrderList != null && prizeSendOrderList.length() > 0) {
        for (index in 0 until prizeSendOrderList.length()) {
            val prizeOrder = prizeSendOrderList.optJSONObject(index) ?: continue
            val prizeName = prizeOrder.optString("prizeName")
            if (prizeName.isNotBlank()) {
                Log.member("余额宝体验金💰[$title]#$prizeName")
            } else {
                Log.member("余额宝体验金💰[$title]")
            }
        }
        return
    }
    Log.member("余额宝体验金💰[$title]")
}

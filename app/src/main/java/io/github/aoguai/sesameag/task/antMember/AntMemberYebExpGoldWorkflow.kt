package io.github.aoguai.sesameag.task.antMember

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
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
        var handledTask = false
        var queryResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain())
        if (!isYebExpGoldSuccess(queryResponse)) {
            Log.member("余额宝体验金任务查询失败: ${getYebExpGoldErrorDesc(queryResponse)}")
            handleYebExpGoldCertVouchers()
            return
        }

        val manualTaskTitles = LinkedHashSet<String>()
        if (trySignInYebExpGold(queryResponse, manualTaskTitles)) {
            handledTask = true
            JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain()).also { refreshed ->
                if (isYebExpGoldSuccess(refreshed)) {
                    queryResponse = refreshed
                }
            }
        }

        val registry = queryYebExpGoldTaskRegistry(queryResponse)
        collectYebExpGoldManualTasks(registry, manualTaskTitles)
        handledTask = claimPendingYebExpGoldRewards(queryResponse, registry) || handledTask

        for (group in registry.groups.values.toList()) {
            val title = group.getTitle()
            if (title.isBlank()) {
                continue
            }
            if (isYebExpGoldTaskBlacklisted(group)) {
                Log.member("任务在自动跳过列表(黑名单)中，跳过[$title]")
                continue
            }
            if (hasHandledYebExpGoldTaskGroupToday(group)) {
                continue
            }
            when (group.getRunStatus()) {
                "not_done",
                "not_sign",
                "sign" -> {
                    if (tryHandleYebExpGoldTaskGroup(group, registry)) {
                        handledTask = true
                    } else {
                        manualTaskTitles.add(title)
                    }
                    CoroutineUtils.sleepCompat(500L)
                }
            }
        }

        queryResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain())
        if (isYebExpGoldSuccess(queryResponse)) {
            collectYebExpGoldTasks(queryResponse, registry, YebExpGoldTaskSource.MAIN_QUERY)
            handledTask = claimPendingYebExpGoldRewards(queryResponse, registry) || handledTask
            handledTask = handleYebExpGoldExchange(queryResponse) || handledTask
            handledTask = handleYebExpGoldCertVouchers() || handledTask
        } else {
            Log.member("余额宝体验金任务刷新失败: ${getYebExpGoldErrorDesc(queryResponse)}")
        }

        if (!handledTask && manualTaskTitles.isEmpty()) {
            Log.member("余额宝体验金任务: 未发现可自动处理项目")
        }
        if (manualTaskTitles.isNotEmpty()) {
            Log.member("余额宝体验金任务待手动完成: ${manualTaskTitles.joinToString("、")}")
        }
    } catch (t: Throwable) {
        Log.printStackTrace("AntMemberYebExpGold", "handleYebExpGoldTasks err:", t)
    }
}

private fun handleYebExpGoldCertVouchers(): Boolean {
    if (Status.hasFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_VOUCHER_CONVERT_DONE)) {
        return false
    }

    var queryResponse = runCatching {
        JSONObject(AntMemberYebExpGoldRpcCall.queryYebTrialCertVoucher())
    }.onFailure {
        Log.printStackTrace("AntMemberYebExpGold", "queryYebTrialCertVoucher err:", it)
    }.getOrNull() ?: return false

    if (!isYebExpGoldSuccess(queryResponse)) {
        Log.member("余额宝体验金券查询失败: ${getYebExpGoldErrorDesc(queryResponse)}")
        return false
    }

    var pendingCount =
        getYebExpGoldVoucherCount(queryResponse) ?: run {
            Log.member("余额宝体验金券查询缺少totalCount/certVoucherInfoList，停止当前链路")
            return false
        }
    if (pendingCount == 0) {
        Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_VOUCHER_CONVERT_DONE)
        return false
    }

    var handled = false
    while (pendingCount > 0) {
        val convertResponse = runCatching {
            JSONObject(AntMemberYebExpGoldRpcCall.convertYebExpGoldVoucher())
        }.onFailure {
            Log.printStackTrace("AntMemberYebExpGold", "convertYebExpGoldVoucher err:", it)
        }.getOrNull() ?: return handled

        if (!isYebExpGoldVoucherConvertSuccess(convertResponse)) {
            Log.member("余额宝体验金券使用失败: ${getYebExpGoldErrorDesc(convertResponse)}")
            return handled
        }

        handled = true
        val rewardText = getYebExpGoldVoucherConvertText(convertResponse)
        Log.member("余额宝体验金💰[券自动使用]#${rewardText.ifBlank { "成功" }}")

        val delayRefreshTime = convertResponse.optLong("delayRefreshTime", 0L)
        if (delayRefreshTime > 0L) {
            CoroutineUtils.sleepCompat(delayRefreshTime)
        }

        queryResponse = runCatching {
            JSONObject(AntMemberYebExpGoldRpcCall.queryYebTrialCertVoucher())
        }.getOrNull() ?: run {
            Log.member("余额宝体验金券使用后回查响应解析失败")
            return handled
        }
        if (!isYebExpGoldSuccess(queryResponse)) {
            Log.member("余额宝体验金券使用后回查失败: ${getYebExpGoldErrorDesc(queryResponse)}")
            return handled
        }
        val remainingCount = getYebExpGoldVoucherCount(queryResponse)
        if (remainingCount == null) {
            Log.member("余额宝体验金券使用后回查缺少totalCount/certVoucherInfoList")
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

private fun collectYebExpGoldManualTasks(
    registry: YebExpGoldTaskRegistry,
    manualTaskTitles: MutableSet<String>
) {
    for (group in registry.groups.values) {
        val title = group.getTitle()
        if (title.isBlank() || isYebExpGoldTaskBlacklisted(group) || hasHandledYebExpGoldTaskGroupToday(group)) {
            continue
        }
        when (group.getRunStatus()) {
            "not_done",
            "not_sign",
            "sign" -> if (!group.hasAutoAction()) {
                manualTaskTitles.add(title)
            }
        }
    }
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
    if (signStatus != "TO_SIGNED" && signStatus != "UNSIGNED") {
        Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_SIGN_DONE)
        return false
    }

    val amount = todaySign.optJSONObject("prizeInfo")
        ?.opt("prizeAmount")
        ?.toString()
        .orEmpty()
    val title = if (amount.isBlank()) "余额宝体验金签到" else "余额宝体验金签到(${amount}元)"
    if (TaskBlacklist.isTaskInBlacklist(YEB_TASK_BLACKLIST_MODULE, title)) {
        Log.member("任务在自动跳过列表(黑名单)中，跳过[$title]")
        Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_SIGN_DONE)
        return false
    }

    val signResponse = JSONObject(AntMemberYebExpGoldRpcCall.signInYebExpGold())
    if (!isYebExpGoldSuccess(signResponse)) {
        Log.member("余额宝体验金签到失败: ${getYebExpGoldErrorDesc(signResponse)}")
        manualTaskTitles.add(title)
        return false
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
    group: YebExpGoldTaskGroup,
    registry: YebExpGoldTaskRegistry
): Boolean {
    val title = group.getTitle()
    val actionTask = group.getActionTask() ?: return false
    val actionTaskId = actionTask.optString("taskId").trim()
    if (actionTaskId.isBlank()) {
        return false
    }
    val source = group.getPreferredActionSource()
    val actionResponse = when {
        source == YebExpGoldTaskSource.PROMO_TASK_LIST ->
            completeYebExpGoldTaskBySource(actionTaskId, source)
        group.getRunStatus() == "not_sign" || group.getRunStatus() == "sign" ->
            triggerYebExpGoldTaskBySource(actionTaskId, source)
        else -> completeYebExpGoldTaskBySource(actionTaskId, source)
    }
    return handleYebExpGoldTaskActionResult(group, title, source, actionTaskId, registry, actionResponse)
}

private fun claimPendingYebExpGoldRewards(
    queryResponse: JSONObject,
    registry: YebExpGoldTaskRegistry
): Boolean {
    val completeList = getYebExpGoldCompleteList(queryResponse)
    var claimed = false
    for (index in 0 until completeList.length()) {
        val rewardItem = completeList.optJSONObject(index) ?: continue
        val rawTaskId = rewardItem.optString("taskId").trim()
        val group = findYebExpGoldTaskGroupForRewardItem(rewardItem, registry)
            ?: createFallbackYebExpGoldTaskGroup(rawTaskId, rewardItem, registry)
        val title = getYebExpGoldCompletedTitle(rewardItem, group.getDisplayTask(), rawTaskId)
        if (hasHandledYebExpGoldTaskGroupToday(group)) {
            continue
        }
        if (isYebExpGoldTaskBlacklisted(group)) {
            Log.member("任务在自动跳过列表(黑名单)中，跳过[$title]")
            continue
        }

        val source = group.getPreferredActionSource()
        val actionTaskId = group.getActionTaskId()?.ifBlank { rawTaskId }.orEmpty()
        if (actionTaskId.isBlank()) {
            continue
        }
        val completeResponse = if (source == YebExpGoldTaskSource.MAIN_QUERY) {
            triggerYebExpGoldTaskBySource(actionTaskId, source)
        } else {
            completeYebExpGoldTaskBySource(actionTaskId, source)
        }
        if (handleYebExpGoldTaskActionResult(group, title, source, actionTaskId, registry, completeResponse)) {
            claimed = true
        }
        CoroutineUtils.sleepCompat(500L)
    }
    return claimed
}

private fun handleYebExpGoldTaskActionResult(
    group: YebExpGoldTaskGroup,
    title: String,
    source: YebExpGoldTaskSource,
    actionTaskId: String,
    registry: YebExpGoldTaskRegistry,
    actionResponse: JSONObject
): Boolean {
    if (isYebExpGoldActionSuccess(actionResponse)) {
        logYebExpGoldRewards(title, actionResponse)
        markYebExpGoldTaskGroupHandledToday(group)
        queryYebExpGoldTaskById(actionTaskId, source)?.let { verifiedTask ->
            mergeYebExpGoldTaskIntoRegistry(verifiedTask, source, registry)?.let(::markYebExpGoldTaskGroupHandledToday)
        }
        return true
    }

    queryYebExpGoldTaskById(actionTaskId, source)?.let { verifiedTask ->
        val verifiedGroup = mergeYebExpGoldTaskIntoRegistry(verifiedTask, source, registry) ?: group
        if (isYebExpGoldTaskReceived(verifiedTask)) {
            markYebExpGoldTaskGroupHandledToday(verifiedGroup)
            return true
        }
    }

    Log.member("余额宝体验金任务领取失败[$title]: ${getYebExpGoldErrorDesc(actionResponse)}")
    return false
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

    if (balance <= 0.0) {
        Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_EXCHANGE_DONE)
        return false
    }

    if (threshold > 0.0 && balance < threshold) {
        Log.member("余额宝体验金未达兑换门槛: 当前$balanceText，最低需${thresholdText.ifBlank { threshold.toString() }}")
        Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_EXCHANGE_DONE)
        return false
    }

    val trialAssetResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebTrialAsset())
    if (!isYebExpGoldSuccess(trialAssetResponse)) {
        Log.member("余额宝体验金资产查询失败: ${getYebExpGoldErrorDesc(trialAssetResponse)}")
        return false
    }

    val trialInfo = getYebTrialInfo(trialAssetResponse)
    if (trialInfo == null) {
        Log.member("余额宝体验金兑换缺少试用资产信息")
        return false
    }

    val campId = trialInfo.optString("promoCampId")
    val prizeId = trialInfo.optString("promoPrizeId")
    if (campId.isBlank() || prizeId.isBlank()) {
        Log.member("余额宝体验金兑换缺少活动参数")
        return false
    }

    val exchangeResponse = JSONObject(
        AntMemberYebExpGoldRpcCall.exchangeYebExpGold(
            campId = campId,
            prizeId = prizeId,
            exchangeAmount = balanceText
        )
    )
    if (!isYebExpGoldSuccess(exchangeResponse)) {
        Log.member("余额宝体验金兑换失败: ${getYebExpGoldErrorDesc(exchangeResponse)}")
        return false
    }

    val couponId = exchangeResponse.optJSONObject("result")
        ?.optString("equityNo")
        .orEmpty()
    if (couponId.isBlank()) {
        Log.member("余额宝体验金兑换成功但缺少激活凭证")
        return false
    }

    val activeResponse = JSONObject(AntMemberYebExpGoldRpcCall.activeYebTrial(couponId))
    if (!isYebExpGoldSuccess(activeResponse)) {
        Log.member("余额宝体验金激活失败: ${getYebExpGoldErrorDesc(activeResponse)}")
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
    return true
}

private fun getYebTrialInfo(trialAssetResponse: JSONObject): JSONObject? {
    val trialInfoList = trialAssetResponse.optJSONArray("trialInfoList") ?: return null
    for (index in 0 until trialInfoList.length()) {
        val trialInfo = trialInfoList.optJSONObject(index) ?: continue
        if (trialInfo.optString("promoCampId").isNotBlank() &&
            trialInfo.optString("promoPrizeId").isNotBlank()
        ) {
            return trialInfo
        }
    }
    return null
}

private fun queryYebExpGoldTaskRegistry(
    fallbackQueryResponse: JSONObject
): YebExpGoldTaskRegistry {
    val registry = YebExpGoldTaskRegistry()
    val taskListResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldTaskList())
    if (isYebExpGoldSuccess(taskListResponse)) {
        val taskDetailList = taskListResponse.optJSONObject("result")
            ?.optJSONArray("taskDetailList")
        if (taskDetailList != null) {
            for (index in 0 until taskDetailList.length()) {
                val task = taskDetailList.optJSONObject(index) ?: continue
                mergeYebExpGoldTaskIntoRegistry(task, YebExpGoldTaskSource.PROMO_TASK_LIST, registry)
            }
        }
    } else {
        Log.member("余额宝体验金任务列表查询失败: ${getYebExpGoldErrorDesc(taskListResponse)}")
    }

    collectYebExpGoldTasks(fallbackQueryResponse, registry, YebExpGoldTaskSource.MAIN_QUERY)
    return registry
}

private fun queryYebExpGoldTaskById(taskId: String, source: YebExpGoldTaskSource): JSONObject? {
    if (taskId.isBlank()) {
        return null
    }

    return try {
        when (source) {
            YebExpGoldTaskSource.PROMO_TASK_LIST -> {
                val queryResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldTaskById(taskId))
                if (!isYebExpGoldSuccess(queryResponse)) {
                    return null
                }

                val taskDetailList = queryResponse.optJSONObject("result")
                    ?.optJSONArray("taskDetailList")
                    ?: return null
                for (index in 0 until taskDetailList.length()) {
                    val task = taskDetailList.optJSONObject(index) ?: continue
                    if (taskId == task.optString("taskId")) {
                        markYebExpGoldTaskSource(task, YebExpGoldTaskSource.PROMO_TASK_LIST)
                        return task
                    }
                }
            }

            YebExpGoldTaskSource.MAIN_QUERY -> {
                val queryResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain(true, taskId))
                if (!isYebExpGoldSuccess(queryResponse)) {
                    return null
                }
                return findYebExpGoldTaskByIdInNode(queryResponse, taskId, YebExpGoldTaskSource.MAIN_QUERY)
            }
        }
        null
    } catch (t: Throwable) {
        Log.printStackTrace("AntMemberYebExpGold", "queryYebExpGoldTaskById err:", t)
        null
    }
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

private fun isYebExpGoldTaskBlacklisted(group: YebExpGoldTaskGroup): Boolean {
    val title = group.getTitle()
    if (title.isNotBlank() && TaskBlacklist.isTaskInBlacklist(YEB_TASK_BLACKLIST_MODULE, title)) {
        return true
    }
    return group.aliasTaskIds.any { taskId ->
        TaskBlacklist.isTaskInBlacklist(YEB_TASK_BLACKLIST_MODULE, taskId)
    }
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

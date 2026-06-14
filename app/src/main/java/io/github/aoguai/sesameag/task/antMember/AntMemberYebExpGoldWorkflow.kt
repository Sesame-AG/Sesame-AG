package io.github.aoguai.sesameag.task.antMember

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TaskBlacklist
import org.json.JSONArray
import org.json.JSONObject

private const val YEB_TASK_BLACKLIST_MODULE = "余额宝"

internal fun AntMember.handleYebExpGoldTasks() {
    try {
        var handledTask = handleYebExpGoldCertVouchers()
        var queryResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain())
        if (!isYebExpGoldSuccess(queryResponse)) {
            Log.member("余额宝体验金任务查询失败: ${getYebExpGoldErrorDesc(queryResponse)}")
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

        val taskMap = queryYebExpGoldTaskMap(queryResponse)
        collectYebExpGoldManualTasks(taskMap, manualTaskTitles)
        handledTask = claimPendingYebExpGoldRewards(queryResponse, taskMap) || handledTask

        for ((taskId, task) in taskMap.entries.toList()) {
            if (taskId.isBlank()) {
                continue
            }

            val title = getYebExpGoldTaskTitle(task, taskId)
            if (isYebExpGoldTaskBlacklisted(title, taskId)) {
                Log.member("跳过黑名单任务[$title]")
                continue
            }
            val unsupportedReason = getUnsupportedYebExpGoldTaskReason(task, title)
            if (unsupportedReason != null) {
                blacklistUnsupportedYebExpGoldTask(title, taskId, unsupportedReason)
                continue
            }
            val successFlag = StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_TASK_PREFIX + taskId
            if (Status.hasFlagToday(successFlag)) {
                continue
            }
            when (task.optString("simplifiedStatus").lowercase()) {
                "not_done" -> {
                    if (tryCompleteYebExpGoldTask(taskId, task, taskMap)) {
                        handledTask = true
                    } else {
                        manualTaskTitles.add(title)
                    }
                    CoroutineUtils.sleepCompat(500L)
                }

                "not_sign" -> {
                    if (shouldAutoReceiveYebExpGoldTask(task) &&
                        tryCompleteYebExpGoldTask(taskId, task, taskMap)
                    ) {
                        handledTask = true
                        CoroutineUtils.sleepCompat(500L)
                    } else {
                        manualTaskTitles.add(title)
                    }
                }
            }
        }

        queryResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain())
        if (isYebExpGoldSuccess(queryResponse)) {
            handledTask = claimPendingYebExpGoldRewards(queryResponse, taskMap) || handledTask
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

    val queryResponse = runCatching {
        JSONObject(AntMemberYebExpGoldRpcCall.queryYebTrialCertVoucher())
    }.onFailure {
        Log.printStackTrace("AntMemberYebExpGold", "queryYebTrialCertVoucher err:", it)
    }.getOrNull() ?: return false

    if (!isYebExpGoldSuccess(queryResponse)) {
        Log.member("余额宝体验金券查询失败: ${getYebExpGoldErrorDesc(queryResponse)}")
        return false
    }

    val pendingCount = countCanUseYebExpGoldVouchers(queryResponse)
    if (pendingCount <= 0) {
        return false
    }

    val convertResponse = runCatching {
        JSONObject(AntMemberYebExpGoldRpcCall.convertYebExpGoldVoucher())
    }.onFailure {
        Log.printStackTrace("AntMemberYebExpGold", "convertYebExpGoldVoucher err:", it)
    }.getOrNull() ?: return false

    if (!isYebExpGoldVoucherConvertSuccess(convertResponse)) {
        Log.member("余额宝体验金券使用失败: ${getYebExpGoldErrorDesc(convertResponse)}")
        return false
    }

    val rewardText = getYebExpGoldVoucherConvertText(convertResponse)
    Log.member("余额宝体验金💰[券自动使用]#${rewardText.ifBlank { "成功" }}")
    Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_VOUCHER_CONVERT_DONE)

    val delayRefreshTime = convertResponse.optLong("delayRefreshTime", 0L)
    if (delayRefreshTime > 0L) {
        CoroutineUtils.sleepCompat(delayRefreshTime)
    }

    val refreshedVoucherResponse = runCatching {
        JSONObject(AntMemberYebExpGoldRpcCall.queryYebTrialCertVoucher())
    }.getOrNull()
    if (refreshedVoucherResponse != null && isYebExpGoldSuccess(refreshedVoucherResponse)) {
        val remainingCount = countCanUseYebExpGoldVouchers(refreshedVoucherResponse)
        if (remainingCount > 0) {
            Log.member("余额宝体验金券使用后仍有待使用券: $remainingCount")
        }
    }

    val refreshedMain = runCatching {
        JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain())
    }.getOrNull()
    if (refreshedMain != null && !isYebExpGoldSuccess(refreshedMain)) {
        Log.member("余额宝体验金券使用后主页回查失败: ${getYebExpGoldErrorDesc(refreshedMain)}")
    }
    return true
}

private fun countCanUseYebExpGoldVouchers(response: JSONObject): Int {
    val equityList = response.optJSONObject("result")
        ?.optJSONArray("equityList")
        ?: return 0
    var count = 0
    for (index in 0 until equityList.length()) {
        val voucher = equityList.optJSONObject(index) ?: continue
        if (isCanUseYebExpGoldVoucher(voucher)) {
            count++
        }
    }
    return count
}

private fun isCanUseYebExpGoldVoucher(voucher: JSONObject): Boolean {
    return listOf(
        voucher.optString("equityStatus"),
        voucher.optString("equityVoucherStatus"),
        voucher.optString("finEquityStatus")
    ).any { it.equals("CAN_USE", ignoreCase = true) }
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
    taskMap: Map<String, JSONObject>,
    manualTaskTitles: MutableSet<String>
) {
    for ((taskId, task) in taskMap) {
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_TASK_PREFIX + taskId)) {
            continue
        }
        val title = getYebExpGoldTaskTitle(task, taskId)
        if (isYebExpGoldTaskBlacklisted(title, taskId)) {
            continue
        }
        val unsupportedReason = getUnsupportedYebExpGoldTaskReason(task, title)
        if (unsupportedReason != null) {
            blacklistUnsupportedYebExpGoldTask(title, taskId, unsupportedReason)
            continue
        }
        if (task.optString("simplifiedStatus").lowercase() == "not_sign" &&
            !shouldAutoReceiveYebExpGoldTask(task)
        ) {
            manualTaskTitles.add(title)
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
        Log.member("跳过黑名单任务[$title]")
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

private fun getUnsupportedYebExpGoldTaskReason(task: JSONObject, title: String): String? {
    val morphoDetail = getYebExpGoldMorphoDetail(task)
    val link = task.optString("link")
        .ifBlank { morphoDetail?.optString("link").orEmpty() }
        .ifBlank { morphoDetail?.optString("taskGotoUrl").orEmpty() }
    val buttonText = task.optString("buttonText")
        .ifBlank { morphoDetail?.optString("buttonText").orEmpty() }
    val taskMainTitle = morphoDetail?.optString("taskMainTitle").orEmpty()
    val riskText = "$title $taskMainTitle $buttonText $link"
    return when {
        containsAnyYebExpGold(riskText, "widget", "小组件", "组件") ->
            "小组件任务无自动闭环"
        containsAnyYebExpGold(riskText, "存入", "攒入", "攒一笔", "开户", "开通", "下1单") ->
            "存入/开户/下单类任务无自动闭环"
        containsAnyYebExpGold(riskText, "app-download", "download", "下载", "外部app") ->
            "外部App/下载任务无自动闭环"
        containsAnyYebExpGold(riskText, "理财", "基金", "证券", "股票", "银行卡") ->
            "理财/证券/绑卡引导无自动闭环"
        else -> null
    }
}

private fun getYebExpGoldMorphoDetail(task: JSONObject): JSONObject? {
    val taskExtProps = task.optJSONObject("taskExtProps") ?: return null
    return when (val detail = taskExtProps.opt("TASK_MORPHO_DETAIL")) {
        is JSONObject -> detail
        is String -> runCatching { JSONObject(detail) }.getOrNull()
        else -> null
    }
}

private fun containsAnyYebExpGold(value: String, vararg keywords: String): Boolean {
    return keywords.any { value.contains(it, ignoreCase = true) }
}

private fun blacklistUnsupportedYebExpGoldTask(title: String, taskId: String, reason: String) {
    TaskBlacklist.addToBlacklist(YEB_TASK_BLACKLIST_MODULE, taskId, title)
    Log.member("余额宝体验金💰[无稳定闭环，已加入黑名单]#$title(taskId=$taskId, reason=$reason)")
}

private fun shouldAutoReceiveYebExpGoldTask(task: JSONObject): Boolean {
    val buttonText = task.optString("buttonText")
    return buttonText.contains("领取") || buttonText.contains("领奖") || buttonText.contains("领")
}

private fun tryCompleteYebExpGoldTask(
    taskId: String,
    task: JSONObject,
    taskMap: MutableMap<String, JSONObject>
): Boolean {
    val title = getYebExpGoldTaskTitle(task, taskId)
    if (taskId.isBlank()) {
        return false
    }
    val unsupportedReason = getUnsupportedYebExpGoldTaskReason(task, title)
    if (unsupportedReason != null) {
        blacklistUnsupportedYebExpGoldTask(title, taskId, unsupportedReason)
        return false
    }

    val prepareResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldMain(true, taskId))
    if (!isYebExpGoldSuccess(prepareResponse)) {
        Log.member("余额宝体验金任务预处理失败[$title]: ${getYebExpGoldErrorDesc(prepareResponse)}")
        return false
    }

    collectYebExpGoldTasks(prepareResponse, taskMap)
    val claimedByCompleteList = claimPendingYebExpGoldRewards(prepareResponse, taskMap)
    if (claimedByCompleteList) {
        return true
    }

    val completeResponse = JSONObject(AntMemberYebExpGoldRpcCall.completeYebExpGoldTask(taskId))
    if (!isYebExpGoldSuccess(completeResponse)) {
        queryYebExpGoldTaskById(taskId)?.let { verifiedTask ->
            taskMap[taskId] = verifiedTask
            if (isYebExpGoldTaskReceived(verifiedTask)) {
                Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_TASK_PREFIX + taskId)
                return true
            }
        }
        Log.member("余额宝体验金任务领取失败[$title]: ${getYebExpGoldErrorDesc(completeResponse)}")
        return false
    }

    logYebExpGoldRewards(title, completeResponse)
    Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_TASK_PREFIX + taskId)
    queryYebExpGoldTaskById(taskId)?.let { verifiedTask ->
        taskMap[taskId] = verifiedTask
    }
    return true
}

private fun claimPendingYebExpGoldRewards(
    queryResponse: JSONObject,
    taskMap: Map<String, JSONObject>
): Boolean {
    val completeList = getYebExpGoldCompleteList(queryResponse)
    var claimed = false
    for (index in 0 until completeList.length()) {
        val rewardItem = completeList.optJSONObject(index) ?: continue
        val taskId = rewardItem.optString("taskId")
        if (taskId.isBlank()) {
            continue
        }
        val successFlag = StatusFlags.FLAG_ANTMEMBER_YEB_EXP_GOLD_TASK_PREFIX + taskId
        if (Status.hasFlagToday(successFlag)) {
            continue
        }

        val title = getYebExpGoldCompletedTitle(rewardItem, taskMap[taskId], taskId)
        if (isYebExpGoldTaskBlacklisted(title, taskId)) {
            Log.member("跳过黑名单任务[$title]")
            continue
        }
        val completeResponse = JSONObject(AntMemberYebExpGoldRpcCall.completeYebExpGoldTask(taskId))
        if (isYebExpGoldSuccess(completeResponse)) {
            logYebExpGoldRewards(title, completeResponse)
            Status.setFlagToday(successFlag)
            claimed = true
        } else {
            val verifiedTask = queryYebExpGoldTaskById(taskId)
            if (verifiedTask != null && isYebExpGoldTaskReceived(verifiedTask)) {
                Status.setFlagToday(successFlag)
                claimed = true
                continue
            }
            Log.member("余额宝体验金任务领取失败[$title]: ${getYebExpGoldErrorDesc(completeResponse)}")
        }
        CoroutineUtils.sleepCompat(500L)
    }
    return claimed
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

private fun queryYebExpGoldTaskMap(
    fallbackQueryResponse: JSONObject
): LinkedHashMap<String, JSONObject> {
    val taskMap = LinkedHashMap<String, JSONObject>()
    val taskListResponse = JSONObject(AntMemberYebExpGoldRpcCall.queryYebExpGoldTaskList())
    if (isYebExpGoldSuccess(taskListResponse)) {
        val taskDetailList = taskListResponse.optJSONObject("result")
            ?.optJSONArray("taskDetailList")
        if (taskDetailList != null) {
            for (index in 0 until taskDetailList.length()) {
                val task = taskDetailList.optJSONObject(index) ?: continue
                val taskId = task.optString("taskId")
                if (taskId.isBlank() || !task.has("simplifiedStatus")) {
                    continue
                }
                taskMap[taskId] = task
            }
        }
    } else {
        Log.member("余额宝体验金任务列表查询失败: ${getYebExpGoldErrorDesc(taskListResponse)}")
    }

    collectYebExpGoldTasks(fallbackQueryResponse, taskMap)
    return taskMap
}

private fun queryYebExpGoldTaskById(taskId: String): JSONObject? {
    if (taskId.isBlank()) {
        return null
    }

    return try {
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
                return task
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
    taskMap: MutableMap<String, JSONObject>
) {
    when (node) {
        is JSONObject -> {
            val taskId = node.optString("taskId")
            if (taskId.isNotBlank() && node.has("simplifiedStatus")) {
                taskMap.putIfAbsent(taskId, node)
            }
            val keys = node.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                collectYebExpGoldTasks(node.opt(key), taskMap)
            }
        }

        is JSONArray -> {
            for (index in 0 until node.length()) {
                collectYebExpGoldTasks(node.opt(index), taskMap)
            }
        }
    }
}

private fun isYebExpGoldSuccess(jo: JSONObject): Boolean {
    return jo.optBoolean("success") ||
        jo.optString("resultCode") == "100" ||
        jo.optString("code") == "100000000"
}

private fun isYebExpGoldTaskReceived(task: JSONObject): Boolean {
    val simplifiedStatus = task.optString("simplifiedStatus").lowercase()
    val taskProcessStatus = task.optString("taskProcessStatus").uppercase()
    return simplifiedStatus == "complete" || taskProcessStatus == "RECEIVE_SUCCESS"
}

private fun isYebExpGoldTaskBlacklisted(
    title: String,
    taskId: String
): Boolean {
    return TaskBlacklist.isTaskInBlacklist(YEB_TASK_BLACKLIST_MODULE, title) ||
        (taskId.isNotBlank() && TaskBlacklist.isTaskInBlacklist(YEB_TASK_BLACKLIST_MODULE, taskId))
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

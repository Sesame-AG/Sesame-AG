package io.github.aoguai.sesameag.task.myBankWelfare

import io.github.aoguai.sesameag.data.Status.Companion.canMyBankWelfareExchangeToday
import io.github.aoguai.sesameag.data.Status.Companion.hasFlagToday
import io.github.aoguai.sesameag.data.Status.Companion.myBankWelfareExchangeToday
import io.github.aoguai.sesameag.data.Status.Companion.setFlagToday
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.hook.HookReadyChecker
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.antMember.AntMemberRpcCall
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
import io.github.aoguai.sesameag.task.exchange.ExchangeDisplayMeta
import io.github.aoguai.sesameag.task.exchange.ExchangeItem
import io.github.aoguai.sesameag.task.exchange.ExchangeLimit
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionRow
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsCache
import io.github.aoguai.sesameag.task.exchange.ExchangeSafety
import io.github.aoguai.sesameag.task.exchange.ExchangeSafetyRules
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.MyBankWelfareBenefitMap
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.LinkedHashMap
import java.util.Locale

class MyBankWelfare : ModelTask() {

    private companion object {
        const val TAG = "MyBankWelfare"
        const val DISPLAY_NAME = "网商银行"
        const val BUSINESS_NAME = "网商银行福利金"
        const val FIELD_PREFIX = "网商银行 | 福利金"
        const val TASK_CENTER_ID = "AP1269301"
        const val WELFARE_PURCHASE_TYPE = "MYBK_FULIJIN_POINT_PAY"
        val SUPPORTED_TRIGGER_TYPES = setOf("USER_TRIGGER", "EVENT_TRIGGER")
    }

    private data class MyBankWelfareExchangeCandidate(
        val item: ExchangeItem,
        val benefitId: String,
        val itemId: String,
        val pointNeeded: String,
        val purchaseType: String,
        val needPay: Boolean,
        val snapshotId: String,
        val consultCode: String,
        val itemSource: String = "PROMO",
        val requestSourceInfo: String = "",
        val sourcePassMap: JSONObject? = null
    )

    private data class MyBankWelfareExchangeData(
        val rows: List<ExchangeOptionRow>,
        val candidates: LinkedHashMap<String, MyBankWelfareExchangeCandidate>
    )

    private data class ExchangeAttemptResult(
        val success: Boolean = false,
        val markHandled: Boolean = false
    )

    private data class StorageReview(
        val outOfStock: Boolean = false,
        val stockText: String = "",
        val reviewOnlyReason: String = ""
    )

    private var myBankWelfareTask: BooleanModelField? = null
    private var myBankWelfareSign: BooleanModelField? = null
    private var myBankWelfareExchange: BooleanModelField? = null
    private var myBankWelfareExchangeList: SelectModelField? = null

    private val loggedUnsupportedTaskKeys = LinkedHashSet<String>()

    override fun getName(): String = DISPLAY_NAME

    override fun getGroup(): ModelGroup = ModelGroup.MYBANK

    override fun getIcon(): String = "AntMember.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(BooleanModelField("myBankWelfareTask", "${FIELD_PREFIX}任务", false).withDesc(
            "执行网商银行福利金任务中心的报名与发奖闭环，并在任务后回查服务端状态。"
        ).also { myBankWelfareTask = it })
        modelFields.addField(BooleanModelField("myBankWelfareSign", "${FIELD_PREFIX}签到", false).withDesc(
            "执行网商银行福利金签到咨询，成功或已处理后落今日状态。"
        ).also { myBankWelfareSign = it })
        modelFields.addField(BooleanModelField("myBankWelfareExchange", "${FIELD_PREFIX}兑换权益", false).withDesc(
            "按“${FIELD_PREFIX}兑换列表”处理已勾选项；纯福利金点付红包权益自动兑换。"
        ).also { myBankWelfareExchange = it })
        modelFields.addField(
            SelectModelField(
                "myBankWelfareExchangeList",
                "${FIELD_PREFIX}兑换列表",
                LinkedHashSet<String?>()
            ) {
                refreshMyBankWelfareExchangeOptionsForSettings()
            }.withDesc("勾选需要处理的网商银行福利金权益，需开启“${FIELD_PREFIX}兑换权益”。").also {
                myBankWelfareExchangeList = it
            }
        )
        return modelFields
    }

    override suspend fun runSuspend() {
        try {
            Log.mybank("执行开始-${getName()}")
            logPointBalance()
            logVirtualProfits()
            if (myBankWelfareSign?.value == true && !hasFlagToday(StatusFlags.FLAG_MYBANK_WELFARE_SIGN_DONE)) {
                handleSign()
            }
            if (myBankWelfareTask?.value == true) {
                TaskFlowEngine(MyBankWelfareTaskFlowAdapter(), roundSleepMs = 800L).run()
            }
            if (myBankWelfareExchange?.value == true) {
                myBankWelfareExchange()
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        } finally {
            Log.mybank("执行结束-${getName()}")
        }
    }

    private fun refreshMyBankWelfareExchangeOptionsForSettings(): List<MapperEntity> {
        if (!HookReadyChecker.isCurrentProcessReadyForRpc(UserMap.currentUid)) {
            val cachedRows = ExchangeOptionsCache.loadForSettingsCache(
                UserMap.currentUid,
                ExchangeOptionsRefreshBridge.TARGET_MYBANK_WELFARE
            )
            if (!HookReadyChecker.isTargetAppReadyForRpc(UserMap.currentUid)) {
                Log.mybank("${BUSINESS_NAME}🎐目标应用未就绪，设置页先展示上次缓存列表；请打开目标应用后再刷新#${cachedRows.size}")
                return cachedRows
            }
            val refreshResult = ExchangeOptionsRefreshBridge.requestRefreshOptions(
                ExchangeOptionsRefreshBridge.TARGET_MYBANK_WELFARE,
                UserMap.currentUid
            )
            if (refreshResult.success) {
                Log.mybank("${BUSINESS_NAME}🎐设置页使用目标应用刷新列表#${refreshResult.options.size}")
                return refreshResult.options
            }
            if (cachedRows.isNotEmpty()) {
                Log.mybank("${BUSINESS_NAME}🎐远程刷新失败，设置页回退上次缓存快照#${cachedRows.size}#${refreshResult.message}")
                return cachedRows
            }
            Log.mybank("${BUSINESS_NAME}🎐远程刷新失败，且无可用缓存快照#${refreshResult.message}")
            return emptyList()
        }
        val rowsResult = runCatching {
            refreshMyBankWelfareExchangeOptionsFromRpc()
        }.onFailure {
            Log.printStackTrace(TAG, "refreshMyBankWelfareExchangeOptionsForSettings.currentRpc err:", it)
        }
        val rows = rowsResult.getOrElse { throwable ->
            val cachedRows = ExchangeOptionsCache.loadForSettingsCache(
                UserMap.currentUid,
                ExchangeOptionsRefreshBridge.TARGET_MYBANK_WELFARE
            )
            if (cachedRows.isNotEmpty()) {
                Log.mybank("${BUSINESS_NAME}🎐当前进程刷新失败，设置页回退上次缓存快照#${cachedRows.size}#${throwable.message}")
                cachedRows
            } else {
                Log.mybank("${BUSINESS_NAME}🎐当前进程刷新失败，且无可用缓存快照#${throwable.message}")
                emptyList()
            }
        }
        Log.mybank("${BUSINESS_NAME}🎐设置页刷新结构化列表#${rows.size}")
        return rows
    }

    internal fun refreshMyBankWelfareExchangeOptionsForRemote(): List<ExchangeOptionRow> =
        refreshMyBankWelfareExchangeOptionsFromRpc()

    private fun refreshMyBankWelfareExchangeOptionsFromRpc(): List<ExchangeOptionRow> {
        try {
            val userId = UserMap.currentUid
            val exchangeData = queryMyBankWelfareExchangeData()
            val benefitMap = IdMapManager.getInstance(MyBankWelfareBenefitMap::class.java)
            exchangeData.rows.forEach { row ->
                benefitMap.add(row.id, row.name)
            }
            benefitMap.save(userId)
            ExchangeOptionsCache.save(userId, ExchangeOptionsRefreshBridge.TARGET_MYBANK_WELFARE, exchangeData.rows)
            Log.mybank("${BUSINESS_NAME}🎐刷新兑换列表#${exchangeData.rows.size}")
            return exchangeData.rows
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "refreshMyBankWelfareExchangeOptionsFromRpc err:", t)
            throw t
        }
    }

    private fun queryMyBankWelfareExchangeData(): MyBankWelfareExchangeData {
        val candidateMap = LinkedHashMap<String, MyBankWelfareExchangeCandidate>()
        queryMyBankRightsCandidates(candidateMap)
        val rows = candidateMap.values.map { it.item.toOptionRow() }
        return MyBankWelfareExchangeData(rows, candidateMap)
    }

    private fun queryMyBankRightsCandidates(
        candidateMap: LinkedHashMap<String, MyBankWelfareExchangeCandidate>
    ) {
        var pageNum = 1
        var totalCount = Int.MAX_VALUE
        val pageSize = 20
        while ((pageNum - 1) * pageSize < totalCount) {
            val response = JSONObject(MyBankWelfareRpcCall.queryItemsInMemberV2(pageNum = pageNum, perPageSize = pageSize))
            if (!ResChecker.checkRes(TAG, "${BUSINESS_NAME}权益列表查询失败:", response)) {
                break
            }
            val pageItems = response.optJSONObject("result")
                ?.optJSONObject("pageItems")
                ?: break
            val dataList = pageItems.optJSONArray("dataList") ?: break
            if (dataList.length() == 0) {
                break
            }
            for (i in 0 until dataList.length()) {
                val candidate = buildMyBankExchangeCandidate(dataList.optJSONObject(i) ?: continue) ?: continue
                candidateMap.putIfAbsent(candidate.item.id, candidate)
            }
            totalCount = pageItems.optInt("totalCount", totalCount)
            if (dataList.length() < pageSize) {
                break
            }
            pageNum++
        }
    }

    private fun buildMyBankExchangeCandidate(
        rawItem: JSONObject,
        requestSourceInfo: String = "",
        sourcePassMap: JSONObject? = null
    ): MyBankWelfareExchangeCandidate? {
        val nestedItemInfo = parseNestedItemInfo(rawItem)
        val itemId = rawItem.optString("itemId").trim()
            .ifBlank { rawItem.optJSONObject("itemConfigDTO")?.optString("itemId").orEmpty().trim() }
            .ifBlank { nestedItemInfo?.optString("itemId").orEmpty().trim() }
        if (itemId.isBlank()) {
            Log.mybank("${BUSINESS_NAME}🎐权益列表项缺少itemId，跳过:$rawItem")
            return null
        }
        val itemInfoDTO = rawItem.optJSONObject("itemInfoDTO")
            ?: nestedItemInfo?.optJSONObject("itemInfoDTO")
            ?: rawItem.optJSONObject("itemConfigDTO")?.optJSONObject("pkgBenefitConfig")
        val itemName = itemInfoDTO?.optString("itemName").orEmpty()
            .ifBlank { rawItem.optString("itemName") }
            .ifBlank { rawItem.optJSONObject("itemConfigDTO")?.optString("itemMainTitle").orEmpty() }
            .ifBlank { itemId }
        val purchaseInfoList = rawItem.optJSONArray("itemPurchaseInfoList")
            ?: nestedItemInfo?.optJSONArray("itemPurchaseInfoList")
            ?: JSONArray()
        val purchaseInfo = findPreferredPurchaseInfo(purchaseInfoList)
        val purchaseType = purchaseInfo?.optString("purchaseType").orEmpty().trim()
        val needPay = purchaseInfo?.optBoolean("needPay", false) == true
        val pointNeeded = extractAmountText(purchaseInfo?.opt("pointAmount"))
            .ifBlank { formatPointAmountFromMap(purchaseInfo?.optJSONObject("pointAmountMap")) }
        val cashAmount = extractAmountText(purchaseInfo?.opt("cashAmount"))
        val itemType = rawItem.optString("itemType").trim()
            .ifBlank { itemInfoDTO?.optJSONArray("itemType")?.optString(0).orEmpty().trim() }
        val jumpLink = rawItem.optString("itemJumpLink").trim()
        val consult = rawItem.optJSONObject("itemConsultDTO")
        val consultCode = consult?.optString("resultCode").orEmpty().trim()
        val consultSuccess = consult?.optBoolean("success", consultCode.isEmpty()) ?: true
        val benefitId = rawItem.optString("benefitId").trim()
            .ifBlank { nestedItemInfo?.optJSONArray("itemBenefitList")?.optJSONObject(0)?.opt("benefitId")?.toString().orEmpty().trim() }
        val snapshotId = rawItem.opt("snapshotId")?.toString().orEmpty().trim()
            .ifBlank { nestedItemInfo?.opt("snapshotId")?.toString().orEmpty().trim() }
        val storageList = rawItem.optJSONArray("itemStorageList")
            ?: nestedItemInfo?.optJSONArray("itemStorageList")
        val storageReview = reviewStorage(storageList)
        val itemSource = rawItem.optString("itemSource").trim().ifBlank { "PROMO" }
        val safetyReason = when {
            consultCode == "FAIL_ACCOUNT_AMOUNT" -> "福利金不足"
            storageReview.outOfStock -> "库存不足"
            benefitId.isBlank() -> "缺少benefitId"
            purchaseType.isBlank() -> "缺少purchaseType"
            purchaseType != "MYBK_FULIJIN_POINT_PAY" -> "非福利金点付"
            needPay -> "需支付现金"
            hasPositiveAmount(cashAmount) -> "存在现金补差"
            jumpLink.isNotBlank() -> "存在外部跳转"
            !consultSuccess && consultCode.isNotBlank() -> "咨询结果:$consultCode"
            storageReview.reviewOnlyReason.isNotBlank() -> storageReview.reviewOnlyReason
            itemType.isNotBlank() && itemType != "BENEFIT_ITEM" -> "非权益红包项"
            else -> ""
        }
        val safety = when {
            consultCode == "FAIL_ACCOUNT_AMOUNT" || storageReview.outOfStock -> ExchangeSafety.UNAVAILABLE
            safetyReason.isNotBlank() -> ExchangeSafety.LOG_ONLY
            else -> ExchangeSafety.AUTO
        }
        val statusParts = mutableListOf<String>()
        if (consultCode.isNotBlank()) {
            statusParts.add("咨询:$consultCode")
        }
        val stockText = storageReview.stockText
        return MyBankWelfareExchangeCandidate(
            item = ExchangeItem(
                id = itemId,
                name = itemName,
                cost = ExchangeCost(
                    pointText = pointNeeded.takeIf { it.isNotBlank() }?.let { "${it}福利金" }.orEmpty(),
                    cashText = cashAmount.takeIf { hasPositiveAmount(it) }?.let { "${it}元" }.orEmpty()
                ),
                limit = ExchangeLimit(
                    statusText = statusParts.joinToString(" | "),
                    stockText = stockText
                ),
                safety = safety,
                safetyReason = safetyReason,
                displayMeta = ExchangeDisplayMeta(
                    sourceModule = getName(),
                    excludeReason = if (safety == ExchangeSafety.AUTO) "" else safetyReason
                )
            ),
            benefitId = benefitId,
            itemId = itemId,
            pointNeeded = pointNeeded,
            purchaseType = purchaseType,
            needPay = needPay,
            snapshotId = snapshotId,
            consultCode = consultCode,
            itemSource = itemSource,
            requestSourceInfo = requestSourceInfo,
            sourcePassMap = sourcePassMap
        )
    }

    private fun parseNestedItemInfo(rawItem: JSONObject): JSONObject? {
        val itemInfo = rawItem.optJSONObject("itemConfigDTO")
            ?.optJSONObject("pkgBenefitConfig")
            ?.optJSONObject("extInfo")
            ?.optString("itemInfo")
            .orEmpty()
        return JsonUtil.parseJSONObjectOrNull(itemInfo)
    }

    private fun findPreferredPurchaseInfo(purchaseInfoList: JSONArray?): JSONObject? {
        if (purchaseInfoList == null || purchaseInfoList.length() == 0) {
            return null
        }
        var fallback: JSONObject? = null
        for (i in 0 until purchaseInfoList.length()) {
            val purchaseInfo = purchaseInfoList.optJSONObject(i) ?: continue
            if (fallback == null) {
                fallback = purchaseInfo
            }
            if (purchaseInfo.optString("purchaseType").trim() == WELFARE_PURCHASE_TYPE) {
                return purchaseInfo
            }
        }
        return fallback
    }

    private fun formatPointAmountFromMap(pointAmountMap: JSONObject?): String {
        if (pointAmountMap == null) {
            return ""
        }
        val directValue = pointAmountMap.opt(WELFARE_PURCHASE_TYPE)
        if (directValue != null) {
            return formatDecimalAmount(directValue.toString())
        }
        val keys = pointAmountMap.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = pointAmountMap.opt(key) ?: continue
            val text = formatDecimalAmount(value.toString())
            if (text.isNotBlank()) {
                return text
            }
        }
        return ""
    }

    private fun hasPositiveAmount(rawAmount: String?): Boolean {
        return rawAmount?.trim()?.toBigDecimalOrNull()?.signum() == 1
    }

    private fun reviewStorage(storageList: JSONArray?): StorageReview {
        if (storageList == null || storageList.length() == 0) {
            return StorageReview()
        }
        var maxRemainInventory = -1L
        var hasExplicitInventory = false
        for (i in 0 until storageList.length()) {
            val storage = storageList.optJSONObject(i) ?: continue
            val remainInventory = storage.optLong("remainInventory", Long.MIN_VALUE)
            if (remainInventory != Long.MIN_VALUE) {
                hasExplicitInventory = true
                if (remainInventory > maxRemainInventory) {
                    maxRemainInventory = remainInventory
                }
            }
        }
        return when {
            hasExplicitInventory && maxRemainInventory <= 0L -> StorageReview(
                outOfStock = true,
                stockText = "库存0"
            )

            maxRemainInventory > 0L -> StorageReview(
                stockText = "库存${maxRemainInventory}"
            )

            else -> StorageReview()
        }
    }

    private fun extractReviewCandidate(
        response: JSONObject,
        fallbackCandidate: MyBankWelfareExchangeCandidate
    ): MyBankWelfareExchangeCandidate {
        val resultObject = response.optJSONObject("result")
        val payloadCandidates = listOfNotNull(
            response.optJSONObject("benefitDetail"),
            response.optJSONObject("promoBenefitOrderConfirmInfo"),
            resultObject?.optJSONObject("benefitDetail"),
            resultObject?.optJSONObject("promoBenefitOrderConfirmInfo"),
            resultObject,
            response
        )
        payloadCandidates.forEach { payload ->
            if (!looksLikeMyBankItemPayload(payload)) {
                return@forEach
            }
            buildMyBankExchangeCandidate(
                payload,
                requestSourceInfo = fallbackCandidate.requestSourceInfo,
                sourcePassMap = fallbackCandidate.sourcePassMap
            )?.let { parsedCandidate ->
                if (shouldKeepFallbackAutoCandidate(parsedCandidate, fallbackCandidate)) {
                    return fallbackCandidate
                }
                return parsedCandidate
            }
        }
        return fallbackCandidate
    }

    private fun looksLikeMyBankItemPayload(payload: JSONObject?): Boolean {
        if (payload == null) {
            return false
        }
        return payload.has("itemId") ||
            payload.has("itemPurchaseInfoList") ||
            payload.has("itemConsultDTO") ||
            payload.has("itemConfigDTO")
    }

    private fun classifyMyBankExchangeFailure(response: JSONObject): TaskRpcFailureType {
        val code = extractResponseCode(response)
        val message = extractResponseMessage(response)
        return when {
            code in setOf("400000030", "400000012") ||
                message in setOf("已领取", "重复领取", "重复兑换", "已兑换", "已处理", "已完成", "已经处理") ->
                TaskRpcFailureType.TERMINAL_DONE

            code == "FAIL_ACCOUNT_AMOUNT" ||
                message in setOf("余额不足", "福利金不足", "资格不足", "业务受限", "奖品已发完", "库存不足", "访问被拒绝") ->
                TaskRpcFailureType.BUSINESS_LIMIT

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                message in setOf("参数错误", "模板不存在", "任务ID非法") ->
                TaskRpcFailureType.NON_RETRYABLE_INVALID

            code in setOf("3000", "1009", "I07", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK", "SYSTEM_BUSY", "NETWORK_ERROR") ||
                message in setOf("系统繁忙", "请稍后再试", "操作频繁", "请重试") ||
                isRetryableMarked(response) ->
                TaskRpcFailureType.RETRYABLE_RPC

            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun shouldKeepFallbackAutoCandidate(
        parsedCandidate: MyBankWelfareExchangeCandidate,
        fallbackCandidate: MyBankWelfareExchangeCandidate
    ): Boolean {
        if (fallbackCandidate.item.safety != ExchangeSafety.AUTO) {
            return false
        }
        if (parsedCandidate.item.safety == ExchangeSafety.AUTO ||
            parsedCandidate.item.safety == ExchangeSafety.UNAVAILABLE
        ) {
            return false
        }
        return parsedCandidate.item.safetyReason in setOf(
            "缺少benefitId",
            "缺少purchaseType",
            "非权益红包项"
        )
    }

    private fun isRetryableMarked(response: JSONObject): Boolean {
        return listOf("retryable", "retriable", "canRetry").any { key ->
            response.has(key) && response.optBoolean(key, false)
        }
    }

    private fun myBankWelfareExchange() {
        if (hasFlagToday(StatusFlags.FLAG_MYBANK_WELFARE_EXCHANGE_REFRESH_DONE)) {
            return
        }
        val selectedIds = myBankWelfareExchangeList?.value
            ?.filterNotNull()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
        if (selectedIds.isNotEmpty() && selectedIds.all { !canMyBankWelfareExchangeToday(it) }) {
            Log.mybank("${BUSINESS_NAME}🎐兑换列表今日已全部处理，跳过执行")
            setFlagToday(StatusFlags.FLAG_MYBANK_WELFARE_EXCHANGE_REFRESH_DONE)
            return
        }
        try {
            val userId = UserMap.currentUid
            val exchangeData = queryMyBankWelfareExchangeData()
            val benefitMap = IdMapManager.getInstance(MyBankWelfareBenefitMap::class.java)
            exchangeData.rows.forEach { row ->
                benefitMap.add(row.id, row.name)
            }
            benefitMap.save(userId)
            if (exchangeData.rows.isEmpty()) {
                Log.mybank("${BUSINESS_NAME}🎐未获取到可兑换列表")
            } else {
                Log.mybank("${BUSINESS_NAME}🎐兑换列表刷新完成#${exchangeData.rows.size}")
            }
            val remainingSelectedIds = if (selectedIds.isNotEmpty()) {
                selectedIds.toMutableSet()
            } else {
                null
            }
            exchangeData.rows.forEach { row ->
                if (!selectedIds.contains(row.id)) {
                    return@forEach
                }
                remainingSelectedIds?.remove(row.id)
                if (!canMyBankWelfareExchangeToday(row.id)) {
                    Log.mybank("${BUSINESS_NAME}🎐跳过[${row.rawName.ifBlank { row.id }}]#今日已处理")
                    return@forEach
                }
                val candidate = exchangeData.candidates[row.id]
                when (row.safety) {
                    ExchangeSafety.UNAVAILABLE.name -> {
                        Log.mybank("${BUSINESS_NAME}🎐跳过[${row.rawName.ifBlank { row.id }}]#${row.safetyReason.ifBlank { "服务端不可兑" }}")
                    }

                    ExchangeSafety.LOG_ONLY.name -> {
                        Log.mybank("${BUSINESS_NAME}🎐已勾选[${row.rawName.ifBlank { row.id }}]#仅提醒，不自动兑换")
                        myBankWelfareExchangeToday(row.id)
                    }

                    else -> {
                        if (candidate == null) {
                            Log.mybank("${BUSINESS_NAME}🎐跳过[${row.rawName.ifBlank { row.id }}]#未找到自动兑换候选")
                            return@forEach
                        }
                        val attemptResult = exchangeMyBankWelfareBenefit(candidate)
                        if (attemptResult.success || attemptResult.markHandled) {
                            myBankWelfareExchangeToday(row.id)
                        }
                    }
                }
            }
            remainingSelectedIds
                ?.filter { canMyBankWelfareExchangeToday(it) }
                ?.forEach { Log.mybank("${BUSINESS_NAME}🎐已勾选[$it]#本次列表未返回，保留配置不删除") }
            setFlagToday(StatusFlags.FLAG_MYBANK_WELFARE_EXCHANGE_REFRESH_DONE)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "myBankWelfareExchange err:", t)
        }
    }

    private fun exchangeMyBankWelfareBenefit(candidate: MyBankWelfareExchangeCandidate): ExchangeAttemptResult {
        try {
            if (candidate.benefitId.isBlank()) {
                Log.mybank("${BUSINESS_NAME}🎐跳过[${candidate.item.name}]#缺少benefitId")
                return ExchangeAttemptResult()
            }
            val detailResp = JSONObject(
                AntMemberRpcCall.querySingleBenefitDetail(
                    benefitId = candidate.benefitId,
                    requestSourceInfo = candidate.requestSourceInfo,
                    sourcePassMap = candidate.sourcePassMap
                )
            )
            if (!ExchangeSafetyRules.isSuccessResponse(detailResp) &&
                !ResChecker.checkRes(TAG, "${BUSINESS_NAME}权益详情查询失败:", detailResp)
            ) {
                val failureType = classifyMyBankExchangeFailure(detailResp)
                val detail = extractResponseCode(detailResp).ifBlank { extractResponseMessage(detailResp) }
                Log.mybank("${BUSINESS_NAME}🎐详情查询失败[${candidate.item.name}]#$detail")
                return ExchangeAttemptResult(
                    markHandled = failureType in setOf(
                        TaskRpcFailureType.TERMINAL_DONE,
                        TaskRpcFailureType.BUSINESS_LIMIT,
                        TaskRpcFailureType.NON_RETRYABLE_INVALID
                    )
                )
            }
            val detailCandidate = extractReviewCandidate(detailResp, candidate)
            if (detailCandidate.item.safety != ExchangeSafety.AUTO) {
                Log.mybank("${BUSINESS_NAME}🎐详情复核跳过[${detailCandidate.item.name}]#${detailCandidate.item.safetyReason}")
                return ExchangeAttemptResult(markHandled = detailCandidate.item.safety == ExchangeSafety.UNAVAILABLE)
            }

            val confirmResp = JSONObject(
                AntMemberRpcCall.queryPromoBenefitOrderConfirmInfo(
                    benefitId = detailCandidate.benefitId,
                    requestSourceInfo = detailCandidate.requestSourceInfo,
                    sourcePassMap = detailCandidate.sourcePassMap
                )
            )
            if (!ExchangeSafetyRules.isSuccessResponse(confirmResp) &&
                !ResChecker.checkRes(TAG, "${BUSINESS_NAME}兑换确认失败:", confirmResp)
            ) {
                val failureType = classifyMyBankExchangeFailure(confirmResp)
                val detail = extractResponseCode(confirmResp).ifBlank { extractResponseMessage(confirmResp) }
                Log.mybank("${BUSINESS_NAME}🎐确认失败[${detailCandidate.item.name}]#$detail")
                return ExchangeAttemptResult(
                    markHandled = failureType in setOf(
                        TaskRpcFailureType.TERMINAL_DONE,
                        TaskRpcFailureType.BUSINESS_LIMIT,
                        TaskRpcFailureType.NON_RETRYABLE_INVALID
                    )
                )
            }
            val confirmedCandidate = extractReviewCandidate(confirmResp, detailCandidate)
            if (confirmedCandidate.item.safety != ExchangeSafety.AUTO) {
                Log.mybank("${BUSINESS_NAME}🎐确认页复核跳过[${confirmedCandidate.item.name}]#${confirmedCandidate.item.safetyReason}")
                return ExchangeAttemptResult(markHandled = confirmedCandidate.item.safety == ExchangeSafety.UNAVAILABLE)
            }
            if (confirmedCandidate.itemId.isBlank()) {
                Log.mybank("${BUSINESS_NAME}🎐跳过[${confirmedCandidate.item.name}]#exchangeBenefit缺少itemId")
                return ExchangeAttemptResult()
            }

            val exchangeResp = JSONObject(
                AntMemberRpcCall.exchangeMemberBenefit(
                    benefitId = confirmedCandidate.benefitId,
                    itemId = confirmedCandidate.itemId,
                    requestSourceInfo = confirmedCandidate.requestSourceInfo,
                    sourcePassMap = confirmedCandidate.sourcePassMap
                )
            )
            if (!ExchangeSafetyRules.isSuccessResponse(exchangeResp) &&
                !ResChecker.checkRes(TAG, "${BUSINESS_NAME}兑换失败:", exchangeResp)
            ) {
                val failureType = classifyMyBankExchangeFailure(exchangeResp)
                val detail = extractResponseCode(exchangeResp).ifBlank { extractResponseMessage(exchangeResp) }
                Log.mybank("${BUSINESS_NAME}🎐兑换失败[${confirmedCandidate.item.name}]#$detail")
                return ExchangeAttemptResult(
                    markHandled = failureType in setOf(
                        TaskRpcFailureType.TERMINAL_DONE,
                        TaskRpcFailureType.BUSINESS_LIMIT,
                        TaskRpcFailureType.NON_RETRYABLE_INVALID
                    )
                )
            }
            val orderId = exchangeResp.optString("orderId").trim()
                .ifBlank { JsonUtil.getValueByPath(exchangeResp, "result.orderId") }
                .ifBlank { JsonUtil.getValueByPath(exchangeResp, "result.outBizNo") }
            Log.mybank("${BUSINESS_NAME}🎐兑换[${confirmedCandidate.item.name}]#消耗${confirmedCandidate.pointNeeded}福利金")
            if (orderId.isNotBlank()) {
                runCatching {
                    val orderResp = JSONObject(
                        AntMemberRpcCall.querySingleExchangeOrderDetail(
                            benefitId = confirmedCandidate.benefitId,
                            bizType = confirmedCandidate.itemSource.ifBlank { "PROMO" },
                            outBizNo = orderId,
                            sourcePassMap = confirmedCandidate.sourcePassMap
                        )
                    )
                    if (ExchangeSafetyRules.isSuccessResponse(orderResp) ||
                        ResChecker.checkRes(TAG, "${BUSINESS_NAME}兑换结果查询失败:", orderResp)
                    ) {
                        val detail = orderResp.optJSONObject("exchangeOrderDetailConfigInfo")
                            ?: orderResp.optJSONObject("result")?.optJSONObject("exchangeOrderDetailConfigInfo")
                        val status = detail?.optString("orderStatus").orEmpty().ifBlank {
                            detail?.optString("status").orEmpty()
                        }
                        Log.mybank("${BUSINESS_NAME}🎐兑换结果[${confirmedCandidate.item.name}]#${status.ifBlank { "已提交" }}")
                    }
                }.onFailure {
                    Log.printStackTrace(TAG, "exchangeMyBankWelfareBenefit.queryOrderDetail err:", it)
                }
            }
            return ExchangeAttemptResult(success = true, markHandled = true)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "exchangeMyBankWelfareBenefit err:", t)
        }
        return ExchangeAttemptResult()
    }

    private fun handleSign() {
        try {
            val response = JSONObject(MyBankWelfareRpcCall.signinPlay())
            if (!response.optBoolean("success")) {
                Log.mybank(
                    "${BUSINESS_NAME}📅签到咨询失败#${
                        extractResponseCode(response).ifBlank { extractResponseMessage(response) }
                    }"
                )
                return
            }
            val result = response.optJSONObject("result")
            val signNotAdmit = result?.optBoolean("signNotAdmit", false) == true
            val canRetry = result?.optBoolean("canRetry", false) == true
            if (signNotAdmit && !canRetry) {
                Log.mybank("${BUSINESS_NAME}📅今日签到已处理")
            } else {
                Log.mybank("${BUSINESS_NAME}📅签到咨询成功")
            }
            setFlagToday(StatusFlags.FLAG_MYBANK_WELFARE_SIGN_DONE)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "handleSign err:", t)
        }
    }

    private fun logPointBalance() {
        try {
            val response = JSONObject(MyBankWelfareRpcCall.queryPointBalance())
            if (!ResChecker.checkRes(TAG, "${BUSINESS_NAME}余额查询失败:", response)) {
                return
            }
            val pointBalance = response.optJSONObject("result")?.opt("pointBalance")?.toString().orEmpty()
                .ifBlank { response.opt("pointBalance")?.toString().orEmpty() }
            if (pointBalance.isNotBlank()) {
                Log.mybank("${BUSINESS_NAME}💰当前可用福利金${formatDecimalAmount(pointBalance)}")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "logPointBalance err:", t)
        }
    }

    private fun logVirtualProfits() {
        try {
            val response = JSONObject(MyBankWelfareRpcCall.queryEnableVirtualProfitV2())
            if (!ResChecker.checkRes(TAG, "${BUSINESS_NAME}奖励查询失败:", response)) {
                return
            }
            val profitList = response.optJSONObject("result")?.optJSONArray("virtualProfitList") ?: return
            if (profitList.length() == 0) {
                return
            }
            val logs = mutableListOf<String>()
            for (i in 0 until profitList.length()) {
                val profit = profitList.optJSONObject(i) ?: continue
                val sceneDesc = profit.optString("sceneDesc").ifBlank {
                    profit.optJSONObject("sceneDTO")?.optString("sceneDesc").orEmpty()
                }
                val reward = profit.optString("reward").ifBlank {
                    profit.optString("pointShowValue")
                }.ifBlank {
                    extractAmountText(profit.opt("point"))
                }
                logs.add(
                    listOf(
                        sceneDesc.ifBlank { "奖励场景" },
                        reward.takeIf { it.isNotBlank() }?.let { "${formatDecimalAmount(it)}福利金" }.orEmpty()
                    ).filter { it.isNotBlank() }.joinToString("#")
                )
            }
            logs.filter { it.isNotBlank() }.forEach { Log.mybank("${BUSINESS_NAME}🎁$it") }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "logVirtualProfits err:", t)
        }
    }

    private inner class MyBankWelfareTaskFlowAdapter : TaskFlowAdapter {
        override val moduleName: String = DISPLAY_NAME
        override val flowName: String = "${BUSINESS_NAME}任务"

        private val signedUpTaskKeys = LinkedHashSet<String>()
        private val sentTaskKeys = LinkedHashSet<String>()

        override fun query(): JSONObject {
            val response = MyBankWelfareRpcCall.taskQuery(TASK_CENTER_ID)
            if (response.isBlank()) {
                return JSONObject()
                    .put("success", false)
                    .put("resultDesc", "taskQuery返回空")
            }
            return JsonUtil.parseJSONObject(response)
        }

        override fun isQuerySuccess(response: JSONObject): Boolean = response.optBoolean("success")

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val taskDetailList = response.optJSONObject("result")
                ?.optJSONArray("taskDetailList")
                ?: return emptyList()
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until taskDetailList.length()) {
                val taskDetail = taskDetailList.optJSONObject(i) ?: continue
                val taskId = taskDetail.optString("taskId").trim()
                if (taskId.isBlank()) {
                    continue
                }
                val morphoDetail = JsonUtil.parseJSONObjectOrNull(
                    taskDetail.optJSONObject("taskExtProps")?.optString("TASK_MORPHO_DETAIL")
                ) ?: JSONObject()
                val title = morphoDetail.optString("title").trim()
                    .ifBlank { morphoDetail.optString("taskMainTitle").trim() }
                    .ifBlank { taskDetail.optString("taskTitle").trim() }
                    .ifBlank { taskDetail.optString("title").trim() }
                    .ifBlank { taskId }
                val current = when {
                    taskDetail.has("periodCurrentCompleteNum") -> taskDetail.optInt("periodCurrentCompleteNum")
                    taskDetail.has("taskCompleteTimes") -> taskDetail.optInt("taskCompleteTimes")
                    else -> null
                }
                val limit = when {
                    taskDetail.has("periodicTotalNum") -> taskDetail.optInt("periodicTotalNum")
                    taskDetail.has("accessLimitCount") && taskDetail.optInt("accessLimitCount") > 0 ->
                        taskDetail.optInt("accessLimitCount")
                    else -> null
                }
                items.add(
                    TaskFlowItem(
                        id = taskId,
                        title = title,
                        status = taskDetail.optString("taskProcessStatus").trim(),
                        type = taskDetail.optString("sendCampTriggerType").trim(),
                        sceneCode = TASK_CENTER_ID,
                        actionType = morphoDetail.optString("tagType").trim(),
                        blacklistKeys = listOf(taskId, title).filter { it.isNotBlank() },
                        raw = taskDetail,
                        progress = buildTaskProgress(current, limit),
                        current = current,
                        limit = limit
                    )
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            if (item.type !in SUPPORTED_TRIGGER_TYPES) {
                return TaskFlowPhase.UNSUPPORTED
            }
            val taskKey = buildTaskKey(item)
            return when (item.status.uppercase(Locale.ROOT)) {
                "NONE_SIGNUP" -> if (taskKey in signedUpTaskKeys) {
                    TaskFlowPhase.SIGNUP_COMPLETE
                } else {
                    TaskFlowPhase.SIGNUP_REQUIRED
                }

                "SIGNUP_COMPLETE" -> TaskFlowPhase.SIGNUP_COMPLETE
                "RECEIVE_SUCCESS" -> TaskFlowPhase.TERMINAL
                else -> TaskFlowPhase.UNKNOWN
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            if (item.type !in SUPPORTED_TRIGGER_TYPES) {
                val taskKey = buildTaskKey(item)
                if (loggedUnsupportedTaskKeys.add(taskKey)) {
                    logInfo(
                        "$flowName[跳过未支持触发类型：${item.title}] " +
                            "taskId=${item.id} sendCampTriggerType=${item.type.ifBlank { "UNKNOWN" }} " +
                            "status=${item.status.ifBlank { "UNKNOWN" }}"
                    )
                }
                return true
            }
            return when (mapPhase(item)) {
                TaskFlowPhase.SIGNUP_COMPLETE -> buildTaskKey(item) in sentTaskKeys
                else -> false
            }
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult =
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
                rpc = "MyBankWelfare.receive",
                detail = taskActionDetail(item, "receive"),
                message = "当前任务链路无receive动作"
            )

        override fun complete(item: TaskFlowItem): TaskFlowActionResult = triggerTaskStage(item, "send")

        override fun signup(item: TaskFlowItem): TaskFlowActionResult = triggerTaskStage(item, "signup")

        override fun send(item: TaskFlowItem): TaskFlowActionResult = triggerTaskStage(item, "send")

        override fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
            if (result.progressChanged) {
                rememberSuccessfulStage(item, action)
            }
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision
        ) {
            if (decision == TaskFlowDecision.MARK_HANDLED) {
                rememberSuccessfulStage(item, action)
            }
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            logInfo("$flowName[任务列表已处理完成：${snapshot.completedTasks}/${snapshot.totalTasks}]")
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.mybank(
                "$flowName[任务查询失败]#${extractResponseCode(response).ifBlank { extractResponseMessage(response) }}"
            )
        }

        override fun logInfo(message: String) {
            Log.mybank(message)
        }

        override fun logError(message: String) {
            Log.mybank(message)
        }

        private fun triggerTaskStage(item: TaskFlowItem, stageCode: String): TaskFlowActionResult {
            val response = MyBankWelfareRpcCall.taskTrigger(item.id, stageCode, TASK_CENTER_ID)
            if (response.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    message = "taskTrigger返回空",
                    rpc = "MyBankWelfareRpcCall.taskTrigger/$stageCode",
                    detail = taskActionDetail(item, stageCode),
                    stopCurrentRound = true
                )
            }
            val result = JsonUtil.parseJSONObject(response)
            val successResult = buildTaskTriggerSuccessResult(result, stageCode)
            if (successResult != null) {
                logTaskStageSuccess(item, stageCode, successResult.progressChanged)
                return successResult
            }
            val failureType = classifyTaskFailure(result)
            return TaskFlowActionResult.failure(
                failureType = failureType,
                code = extractResponseCode(result),
                message = extractResponseMessage(result),
                rpc = "MyBankWelfareRpcCall.taskTrigger/$stageCode",
                raw = result.toString(),
                detail = taskActionDetail(item, stageCode),
                stopCurrentRound = failureType == TaskRpcFailureType.RETRYABLE_RPC
            )
        }

        private fun rememberSuccessfulStage(item: TaskFlowItem, action: TaskFlowAction) {
            val taskKey = buildTaskKey(item)
            when (action) {
                TaskFlowAction.SIGNUP -> signedUpTaskKeys.add(taskKey)
                TaskFlowAction.SEND,
                TaskFlowAction.COMPLETE -> sentTaskKeys.add(taskKey)
                TaskFlowAction.RECEIVE -> Unit
            }
        }

        private fun buildTaskKey(item: TaskFlowItem): String = item.id.ifBlank { item.title }

        private fun buildTaskProgress(current: Int?, limit: Int?): String {
            return when {
                current != null && limit != null -> "$current/$limit"
                current != null -> current.toString()
                else -> ""
            }
        }

        private fun taskActionDetail(item: TaskFlowItem, stageCode: String): String {
            return "taskId=${item.id} taskName=${item.title} status=${item.status} " +
                "sendCampTriggerType=${item.type.ifBlank { "UNKNOWN" }} stageCode=$stageCode progress=${item.progress}"
        }

        private fun logTaskStageSuccess(
            item: TaskFlowItem,
            stageCode: String,
            progressChanged: Boolean
        ) {
            if (!progressChanged) {
                Log.mybank("${BUSINESS_NAME}🎯[${item.title}]处理成功，等待任务状态回查确认")
                return
            }
            val actionText = when (stageCode) {
                "signup" -> "报名完成"
                "send" -> "奖励发放完成"
                else -> "处理成功"
            }
            Log.mybank("${BUSINESS_NAME}🎯[${item.title}]$actionText")
        }

        private fun buildTaskTriggerSuccessResult(
            response: JSONObject,
            stageCode: String
        ): TaskFlowActionResult? {
            val baseSuccess = response.optBoolean("success") ||
                response.optString("resultCode").equals("SUCCESS", ignoreCase = true) ||
                response.optString("code") == "100000000"
            val prizeSendOrderList = response.optJSONObject("result")?.optJSONArray("prizeSendOrderList")
            var hasSendSuccess = false
            var hasBenefitPointPrize = false
            if (prizeSendOrderList != null) {
                for (i in 0 until prizeSendOrderList.length()) {
                    val prizeOrder = prizeSendOrderList.optJSONObject(i) ?: continue
                    if (prizeOrder.optString("sendStatus") == "SUCCESS") {
                        hasSendSuccess = true
                    }
                    val prizeType = prizeOrder.optString("prizeType")
                    val prizeSubType = prizeOrder.optString("prizeSubType")
                    if (prizeType == "MYBK_BENEFIT_POINT" || prizeSubType == "MYBK_BENEFIT_POINT") {
                        hasBenefitPointPrize = true
                    }
                }
            }
            return when {
                stageCode == "send" && (hasBenefitPointPrize || hasSendSuccess) -> TaskFlowActionResult.success()
                stageCode == "send" && baseSuccess ->
                    TaskFlowActionResult.success(refreshAfterAction = true, progressChanged = false)
                hasSendSuccess || baseSuccess -> TaskFlowActionResult.success()
                else -> null
            }
        }

        private fun classifyTaskFailure(response: JSONObject): TaskRpcFailureType {
            val code = extractResponseCode(response)
            val message = extractResponseMessage(response)
            return when {
                code in setOf("400000030", "400000012") ||
                    message in setOf("已领取", "重复领取", "重复领奖", "已完成", "已报名", "已经报名", "已处理") ->
                    TaskRpcFailureType.TERMINAL_DONE

                code in setOf(
                    "104",
                    "CAMP_TRIGGER_ERROR",
                    "10001013",
                    "10001033",
                    "10001034",
                    "10001043",
                    "10001044",
                    "NOT_PROMO_RULE_QUALIFIED"
                ) ||
                    message in setOf("访问被拒绝", "资格不足", "奖品已发完", "业务受限") ->
                    TaskRpcFailureType.BUSINESS_LIMIT

                code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                    code == "10000005" ||
                    message in setOf(
                        "参数错误",
                        "模板不存在",
                        "任务ID非法",
                        "[10000005]参数错误, loanpromoweb不允许完成事件规则任务"
                    ) ->
                    TaskRpcFailureType.NON_RETRYABLE_INVALID

                code in setOf("3000", "1009", "I07", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK", "SYSTEM_BUSY", "NETWORK_ERROR") ||
                    message in setOf("系统繁忙", "请稍后再试", "操作频繁", "请重试") ||
                    isRetryableMarked(response) ->
                    TaskRpcFailureType.RETRYABLE_RPC

                else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            }
        }
    }

    private fun extractResponseMessage(response: JSONObject): String {
        return response.optString("resultDesc")
            .ifBlank { response.optString("memo") }
            .ifBlank { response.optString("desc") }
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("errorMessage") }
            .ifBlank { response.optString("message") }
            .ifBlank { response.optString("resultView") }
            .ifBlank { JsonUtil.getValueByPath(response, "result.resultDesc") }
            .ifBlank { JsonUtil.getValueByPath(response, "result.errorMsg") }
            .ifBlank { JsonUtil.getValueByPath(response, "result.message") }
            .ifBlank { response.toString() }
    }

    private fun extractResponseCode(response: JSONObject): String {
        return response.optString("resultCode")
            .ifBlank { response.optString("errorCode") }
            .ifBlank { response.optString("code") }
            .ifBlank { response.optString("errCode") }
            .ifBlank { JsonUtil.getValueByPath(response, "result.resultCode") }
            .ifBlank { JsonUtil.getValueByPath(response, "result.errorCode") }
            .ifBlank { JsonUtil.getValueByPath(response, "result.code") }
    }

    private fun formatDecimalAmount(rawAmount: String?): String {
        val normalized = rawAmount?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return ""
        }
        return runCatching {
            BigDecimal(normalized).stripTrailingZeros().toPlainString()
        }.getOrDefault(normalized)
    }

    private fun extractAmountText(value: Any?): String {
        return when (value) {
            is JSONObject -> formatDecimalAmount(value.optString("amount"))
            else -> formatDecimalAmount(value?.toString())
        }
    }
}

package io.github.aoguai.sesameag.task.goldenBean

import io.github.aoguai.sesameag.util.RandomUtil

import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.antOrchard.AntOrchard
import io.github.aoguai.sesameag.task.antStall.AntStall

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import io.github.aoguai.sesameag.task.exchange.ExchangeSafety
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.UserDataStore
import io.github.aoguai.sesameag.util.UserDataStoreManager
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.task.common.GameCenterPlayRpcCall
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.util.CoroutineUtils
import kotlinx.coroutines.runBlocking
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.GameTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TaskBlacklist
import org.json.JSONArray
import org.json.JSONObject

internal const val GOLDEN_BEAN_BLACKLIST_MODULE = "金豆夺宝"
internal const val MARKETING_POPUP_CLICKED = "MARKETING_POPUP_CLICKED"
internal const val GOLDEN_BEAN_GAME_CHANNEL = "goldenbean"
internal const val GOLDEN_BEAN_CONVERGENCE_LIMIT = 64

/**
 * Module-local orchestration for the Golden Bean Treasure domain. Task-list
 * transitions reuse TaskFlowEngine; mining and the game-centre draw use their
 * own server state because neither belongs to the task-list lifecycle.
 */
internal suspend fun GoldenBeanTreasure.runGoldenBeanTreasure() {
    val initialFloatingConfirmed = GoldenBeanTreasureSupport.collectFloatingBeans()
    if (Status.hasFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_TASKS_DONE)) {
        if (!Status.hasFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_FORTUNE_DRAWN)) {
            val indexResponse = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index())
            if (indexResponse != null && GoldenBeanTreasureSupport.isSuccess(indexResponse)) {
                GoldenBeanTreasureSupport.handleFortuneDraw(indexResponse)
            } else {
                Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆求签入口查询失败 raw=${indexResponse ?: "EMPTY"}")
            }
        }
        // 任务列表完成不代表当天后续到账的肥料、芝麻粒已兑换。
        val entryRuns = GoldenBeanRpcCall.ENTRIES.map { entry ->
            GoldenBeanEntryRun(entry, GoldenBeanTaskFlowAdapter(this, entry))
        }
        val exchangedEntries = runGoldenBeanExchanges(entryRuns)
        if (exchangedEntries.isNotEmpty()) {
            exchangedEntries.forEach { it.runTaskFlow(executeIntervalInt) }
            GoldenBeanTreasureSupport.collectFloatingBeans()
            Model.getModel(AntOrchard::class.java)?.resumeTasksAfterGoldenBeanProgress()
            Model.getModel(AntStall::class.java)?.resumeTasksAfterGoldenBeanProgress()
        }
        Log.goldenBean("金豆夺宝[今日任务已处理，保留资源兑换与求签检查]")
        return
    }

    val entryRuns = GoldenBeanRpcCall.ENTRIES.mapNotNull { entry ->
        val indexResponse = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index(entry))
        if (indexResponse == null || !GoldenBeanTreasureSupport.isSuccess(indexResponse)) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝入口首页查询失败 entry=${entry.bizType}/${entry.source} raw=${indexResponse ?: "EMPTY"}",
            )
            return@mapNotNull null
        }
        if (entry == GoldenBeanRpcCall.MASTER_ENTRY) {
            GoldenBeanTreasureSupport.handleFortuneDraw(indexResponse)
        }
        val signSyncResponse = GoldenBeanTreasureSupport.handleDailySign(indexResponse, entry)
        val marketingSource =
            signSyncResponse?.takeIf { it.optJSONObject("marketingPopupTask") != null } ?: indexResponse
        GoldenBeanTreasureSupport.handleMarketingPopup(marketingSource, entry)
        GoldenBeanEntryRun(entry, GoldenBeanTaskFlowAdapter(this, entry))
    }
    val masterRun = entryRuns.firstOrNull { it.entry == GoldenBeanRpcCall.MASTER_ENTRY }
    masterRun?.let {
        try {
            GoldenBeanTreasureSupport.queryMallItems()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException || t is InterruptedException) throw t
            Log.printStackTrace(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆商城目录暂未完成，保留已有目录与查询进度", t)
        }
    }
    val attemptedGameSnapshots = mutableSetOf<String>()
    var convergenceRound = 1
    var taskResults = entryRuns.associateWith { it.runTaskFlow(executeIntervalInt) }
    var confirmedTaskProgress = taskResults.values.any { it.progressed }
    var reachedConvergenceLimit = false
    var gameResult =
        if (masterRun == null) {
            GoldenBeanGameFlowResult(completed = false, progressed = false, blocked = true)
        } else {
            GoldenBeanTreasureSupport.runGameCenterOpportunityFlow(attemptedGameSnapshots)
        }
    var needsTaskRefresh =
        taskResults.values.any { it.progressed } || gameResult.progressed || gameResult.taskRefreshRequested
    while (!gameResult.blocked && needsTaskRefresh && convergenceRound < GOLDEN_BEAN_CONVERGENCE_LIMIT) {
        convergenceRound++
        taskResults = entryRuns.associateWith { it.runTaskFlow(executeIntervalInt) }
        confirmedTaskProgress = confirmedTaskProgress || taskResults.values.any { it.progressed }
        val shouldRunGameCenter = taskResults.values.any { it.progressed } || gameResult.progressed
        gameResult =
            if (masterRun != null && shouldRunGameCenter) {
                GoldenBeanTreasureSupport.runGameCenterOpportunityFlow(attemptedGameSnapshots)
            } else {
                gameResult.copy(taskRefreshRequested = false)
            }
        needsTaskRefresh =
            taskResults.values.any { it.progressed } || gameResult.progressed || gameResult.taskRefreshRequested
    }
    if (convergenceRound >= GOLDEN_BEAN_CONVERGENCE_LIMIT && needsTaskRefresh) {
        reachedConvergenceLimit = true
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝达到收敛轮次上限$GOLDEN_BEAN_CONVERGENCE_LIMIT")
    }

    val exchangedEntries = runGoldenBeanExchanges(entryRuns)
    if (exchangedEntries.isNotEmpty()) {
        val refreshedResults = exchangedEntries.associateWith { it.runTaskFlow(executeIntervalInt) }
        taskResults = taskResults + refreshedResults
        confirmedTaskProgress = confirmedTaskProgress || refreshedResults.values.any { it.progressed }
    }
    val minerConfirmed = masterRun?.adapter?.isMinerEntryReceived() != true ||
        GoldenBeanTreasureSupport.runMiner()

    val floatingConfirmed = GoldenBeanTreasureSupport.collectFloatingBeans() && initialFloatingConfirmed
    val allEntrancesQueried = entryRuns.size == GoldenBeanRpcCall.ENTRIES.size
    val allTaskFlowsConfirmed = taskResults.values.all { it.completed && !it.stopped }
    val hasUnresolvedTasks = entryRuns.any { it.adapter.hasUnresolvedTasks() }
    if (allEntrancesQueried && floatingConfirmed && minerConfirmed && !reachedConvergenceLimit && allTaskFlowsConfirmed && gameResult.completed && !hasUnresolvedTasks) {
        Status.setFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_TASKS_DONE)
    } else if (!hasUnresolvedTasks) {
        Log.goldenBean("金豆夺宝任务未写入当日完成标记：任务流、矿工或金豆乐园状态尚未完成确认")
    } else {
        Log.goldenBean("金豆夺宝仍有非黑名单待完成或待领奖任务，不写入今日完成标记")
    }
    // 先保存金豆最终确认结果，再续接依赖任务，避免收尾读到旧的依赖状态。
    if (confirmedTaskProgress || exchangedEntries.isNotEmpty() || Status.hasFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_TASKS_DONE)) {
        Model.getModel(AntOrchard::class.java)?.resumeTasksAfterGoldenBeanProgress()
        Model.getModel(AntStall::class.java)?.resumeTasksAfterGoldenBeanProgress()
    }
}

private const val GOLDEN_BEAN_MALL_PENDING = "goldenBeanMallPending"
private val goldenBeanMallQueues = java.util.concurrent.ConcurrentHashMap<String, io.github.aoguai.sesameag.util.SerialTaskQueue>()

internal fun GoldenBeanTreasure.runGoldenBeanMall() {
    val store = UserDataStoreManager.getCurrentInstance() ?: return
    val identity = RpcDailyCircuit.captureIdentity()
    val startedAt = System.currentTimeMillis()
    val checkCurrent = {
        check(identity.userId == store.uid && UserMap.currentUid == store.uid && RpcDailyCircuit.isCurrent(identity)) {
            "金豆商城账号会话已变化"
        }
        check(!ApplicationHookConstants.isOffline() && !Thread.currentThread().isInterrupted &&
            TimeUtil.isSameDay(startedAt, System.currentTimeMillis())) { "金豆商城本轮已中断或跨天" }
    }
    goldenBeanMallQueues.computeIfAbsent(store.uid) { io.github.aoguai.sesameag.util.SerialTaskQueue() }.execute {
        checkCurrent()
        val saved = store.get(GOLDEN_BEAN_MALL_PENDING, String::class.java)
        val pending = saved?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
        if (pending.length() > 0 && !confirmGoldenBeanMallExchange(store, pending, checkCurrent)) return@execute
        if (goldenBeanMallExchange.value != true) return@execute
        GoldenBeanTreasureSupport.queryMallItems()
        val selected = goldenBeanMallExchangeList.value?.toMap().orEmpty()
        if (selected.isEmpty()) {
            Log.goldenBean("金豆商城未选择商品，仅更新兑换候选")
            return@execute
        }
        var confirmed = 0
        for ((selectedId, configuredCount) in selected) {
            if (selectedId.isNullOrBlank() || configuredCount == null || configuredCount < 0) continue
            val countFlag = StatusFlags.FLAG_GOLDEN_BEAN_MALL_EXCHANGE_COUNT_PREFIX + selectedId
            while (confirmed < GOLDEN_BEAN_CONVERGENCE_LIMIT) {
                checkCurrent()
                val done = Status.getIntFlagToday(countFlag) ?: 0
                if (configuredCount > 0 && done >= configuredCount) break
                val snapshot = GoldenBeanTreasureSupport.queryLiveMallItems()
                val products = snapshot.payload.getJSONArray("items")
                var selectedProduct: Pair<JSONObject, JSONObject>? = null
                for (i in 0 until products.length()) {
                    val spu = products.getJSONObject(i)
                    val skus = spu.optJSONArray("skuModelList") ?: continue
                    for (j in 0 until skus.length()) {
                        val sku = skus.getJSONObject(j)
                        if ("${spu.optString("spuId")}:${sku.optString("skuId")}" == selectedId) {
                            selectedProduct = spu to sku
                            break
                        }
                    }
                    if (selectedProduct != null) break
                }
                if (selectedProduct == null) {
                    Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆商城所选规格已不在当前目录:$selectedId")
                    break
                }
                val (spu, sku) = selectedProduct
                val item = GoldenBeanTreasureSupport.mallExchangeItem(spu, sku)
                if (item.safety != ExchangeSafety.AUTO) {
                    Log.goldenBean("金豆商城[${item.name}]本轮不兑换：${item.safetyReason}")
                    break
                }
                val index = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.index())
                    ?: error("金豆商城余额查询返回空")
                check(GoldenBeanTreasureSupport.isSuccess(index)) { "金豆商城余额查询失败:$index" }
                val balance = index.optJSONObject("jarInfo")?.optString("jarCount")?.toBigDecimalOrNull()
                    ?: error("金豆商城未取得有效金豆罐余额")
                val cost = java.math.BigDecimal.valueOf(sku.getJSONObject("price").getLong("cent"), 2)
                check(balance.signum() >= 0) { "金豆罐余额无效:$balance" }
                if (balance < cost) {
                    Log.goldenBean("金豆商城[${item.name}]金豆罐不足，余额$balance，需$cost")
                    break
                }
                val beforeOrders = GoldenBeanTreasureSupport.queryMallOrders(checkCurrent).map { it.getString("orderNo") }
                val request = JSONObject()
                    .put("itemId", selectedId).put("spuId", spu.getString("spuId")).put("skuId", sku.getString("skuId"))
                    .put("name", item.name).put("requestId", java.util.UUID.randomUUID().toString())
                    .put("requestedAt", System.currentTimeMillis()).put("beforeOrderNos", JSONArray(beforeOrders))
                checkCurrent()
                store.put(GOLDEN_BEAN_MALL_PENDING, request.toString(), requirePersistence = true)
                checkCurrent()
                val response = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.exchangeBenefit(
                    request.getString("spuId"), request.getString("skuId"), request.getString("requestId"),
                ))
                checkCurrent()
                if (response == null) {
                    Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆商城兑换响应为空，保留待确认:$selectedId")
                    return@execute
                }
                val acknowledged = GoldenBeanTreasureSupport.isSuccess(response)
                val failure = if (acknowledged) null else GoldenBeanTreasureSupport.actionResult(
                    TaskFlowItem(selectedId, item.name, "TODO", type = sku.getString("skuId"), sceneCode = "ANTORCHARD_JINDOU_MALL"),
                    response, "exchangeBenefit",
                )
                if (response.opt("canBuy") == false || failure?.failureType in setOf(
                        TaskRpcFailureType.NON_RETRYABLE_INVALID, TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
                    )) {
                    Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆商城未受理兑换[${item.name}]:$response")
                    store.put(GOLDEN_BEAN_MALL_PENDING, "{}", requirePersistence = true)
                    break
                }
                request.put("acknowledged", acknowledged).put("orderNo", response.optString("orderNo"))
                store.put(GOLDEN_BEAN_MALL_PENDING, request.toString(), requirePersistence = true)
                if (!acknowledged) Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆商城兑换未确认成功，保留订单回查:$response")
                if (!confirmGoldenBeanMallExchange(store, request, checkCurrent)) return@execute
                confirmed++
                // 保留金豆 executeInterval 配置的连续兑换间隔，并沿用原有 425–500ms 随机下限。
                CoroutineUtils.sleepCompat(executeIntervalInt.toLong().coerceAtLeast(RandomUtil.nextLong(425, 501)))
            }
            if (confirmed >= GOLDEN_BEAN_CONVERGENCE_LIMIT) {
                Log.goldenBean("金豆商城本轮已确认${confirmed}笔，剩余配置留待后续调度")
                return@execute
            }
        }
    }
}

private fun confirmGoldenBeanMallExchange(store: UserDataStore, pending: JSONObject, checkCurrent: () -> Unit): Boolean {
    checkCurrent()
    val itemId = pending.getString("itemId")
    val orderNo = pending.optString("orderNo")
    if (!pending.has("confirmedAt")) {
        val orders = GoldenBeanTreasureSupport.queryMallOrders(checkCurrent)
        checkCurrent()
        val before = pending.getJSONArray("beforeOrderNos")
        val existed = (0 until before.length()).any { before.optString(it) == orderNo }
        if (!pending.optBoolean("acknowledged") || orderNo.isBlank() || existed ||
            orders.none { it.optString("orderNo") == orderNo }
        ) {
            Log.error(GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆商城待确认[${pending.optString("name")}] requestId=${pending.optString("requestId")} " +
                    "orderNo=$orderNo，未确认对应新增订单，不重复提交")
            return false
        }
        pending.put("confirmedAt", System.currentTimeMillis())
        pending.put("confirmedCount", (Status.getIntFlagToday(StatusFlags.FLAG_GOLDEN_BEAN_MALL_EXCHANGE_COUNT_PREFIX + itemId) ?: 0) + 1)
        store.put(GOLDEN_BEAN_MALL_PENDING, pending.toString(), requirePersistence = true)
    }
    checkCurrent()
    if (TimeUtil.isSameDay(pending.getLong("confirmedAt"), System.currentTimeMillis())) {
        val flag = StatusFlags.FLAG_GOLDEN_BEAN_MALL_EXCHANGE_COUNT_PREFIX + itemId
        val expected = pending.getInt("confirmedCount")
        if ((Status.getIntFlagToday(flag) ?: 0) < expected) Status.setIntFlagToday(flag, expected)
        else Status.save()
    }
    checkCurrent()
    store.put(GOLDEN_BEAN_MALL_PENDING, "{}", requirePersistence = true)
    Log.goldenBean("金豆商城已确认兑换[${pending.optString("name")}][订单$orderNo]")
    return true
}

private data class GoldenBeanEntryRun(
    val entry: GoldenBeanEntry,
    val adapter: GoldenBeanTaskFlowAdapter,
) {
    private val executionState = TaskFlowExecutionState()

    fun runTaskFlow(executeInterval: Int) =
        TaskFlowEngine(
            adapter,
            roundSleepMs = executeInterval.toLong().coerceAtLeast(425L),
            executionState = executionState,
        ).run()
}

private fun GoldenBeanTreasure.runGoldenBeanExchanges(entryRuns: List<GoldenBeanEntryRun>): List<GoldenBeanEntryRun> =
    entryRuns.filter { run ->
        when (run.entry) {
            GoldenBeanRpcCall.MASTER_ENTRY -> runGoldenBeanManureExchangeIfNeeded()
            GoldenBeanRpcCall.ZHIMA_ENTRY -> runGoldenBeanSesameExchangeIfNeeded()
            else -> false
        }
    }

internal fun GoldenBeanTreasure.resumeGoldenBeanTasks(): io.github.aoguai.sesameag.task.TaskExecutionOutcome {
    val results = GoldenBeanRpcCall.ENTRIES.map { entry ->
        TaskFlowEngine(GoldenBeanTaskFlowAdapter(this, entry), roundSleepMs = executeIntervalInt.toLong()).run()
    }
    return when {
        results.any { it.interrupted } -> io.github.aoguai.sesameag.task.TaskExecutionOutcome.CANCELLED
        results.any { it.failureCount > 0 } -> io.github.aoguai.sesameag.task.TaskExecutionOutcome.FAILED
        results.all { it.completed } -> io.github.aoguai.sesameag.task.TaskExecutionOutcome.SUCCESS
        else -> io.github.aoguai.sesameag.task.TaskExecutionOutcome.DEFERRED
    }
}

private class GoldenBeanTaskFlowAdapter(
    private val owner: GoldenBeanTreasure,
    private val entry: GoldenBeanEntry,
) : TaskFlowAdapter {
    private val loggedDeferredTaskIds = mutableSetOf<String>()
    private var latestTaskResponse = JSONObject()
    private var nextQuerySyncTypes = TASK_STATUS_SYNC_TYPES

    override val moduleName: String = GOLDEN_BEAN_BLACKLIST_MODULE
    override val flowName: String = "金豆夺宝任务"
    override val stateScope: String = "$flowName:${entry.taskSceneCode}"
    override val continueCurrentRoundOnRetryableFailure: Boolean = true

    override fun query(): JSONObject {
        val syncTypes = nextQuerySyncTypes
        nextQuerySyncTypes = TASK_STATUS_SYNC_TYPES
        return GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.sync(syncTypes, entry))
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
            if (sceneCode != entry.taskSceneCode) {
                if (sceneCode.isBlank()) {
                    Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, "金豆夺宝任务缺少sceneCode raw=$task")
                }
                continue
            }

            val taskId = task.optString("taskId").trim()
            val groupId = task.optString("groupId").trim()
            val stableIdentifier = taskId.ifBlank { groupId }.ifBlank { "UNKNOWN_$index" }
            val stableId = stableBlacklistKey(sceneCode, stableIdentifier)
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
                if (hasTaskAction(item)) {
                    TaskFlowPhase.READY_TO_COMPLETE
                } else {
                    TaskFlowPhase.UNKNOWN
                }
            }

            else -> TaskFlowPhase.UNKNOWN
        }

    override fun isFlowHandledToday(): Boolean = false

    override fun shouldSkip(item: TaskFlowItem): Boolean {
        if (item.status.uppercase() != "TODO") {
            return false
        }
        if (hasTaskAction(item)) {
            return false
        }
        if (loggedDeferredTaskIds.add(item.id)) {
            Log.goldenBean(
                "金豆夺宝任务⏭️[taskId=${item.id} actionType=${item.actionType.ifBlank { "UNKNOWN" }} " +
                    "sceneCode=${item.sceneCode.ifBlank { "UNKNOWN" }}] 等待对应业务动作完成后回查任务",
            )
        }
        return true
    }

    override fun isUnresolvedWhenSkipped(item: TaskFlowItem): Boolean =
        item.status.uppercase() !in setOf("RECEIVED", "DONE") && !isBlacklisted(item)

    override fun receive(item: TaskFlowItem): TaskFlowActionResult {
        if (item.type.isBlank()) {
            return GoldenBeanTreasureSupport.missingTaskTypeFailure(item, "receive")
        }
        val response = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.receiveTaskAward(item.type, entry))
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
        if (item.actionType == "COMMON_USED_VISIT") {
            val response = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.indexFromHome())
                ?: return GoldenBeanTreasureSupport.emptyResponseFailure(item, "indexFromHome")
            if (GoldenBeanTreasureSupport.isSuccess(response)) {
                nextQuerySyncTypes = RECEIVE_TASK_SYNC_TYPES
                val awardCount = response.optInt("receptionAwardCount")
                if (awardCount > 0) Log.goldenBean("金豆常用来访获得${awardCount}金豆")
            }
            return GoldenBeanTreasureSupport.actionResult(item, response, "indexFromHome")
        }
        if (item.actionType != GoldenBeanRpcCall.WAKUANG_ACTION_TYPE &&
            !(entry == GoldenBeanRpcCall.MASTER_ENTRY && item.type == GoldenBeanRpcCall.JINDOULEYUAN_TASK_TYPE &&
                item.actionType == GoldenBeanRpcCall.JINDOULEYUAN_ACTION_TYPE)
        ) {
            val descriptor = GameCenterPlayRpcCall.describeTask(item.raw)
            val decision = GameCenterPlayRpcCall.resolveTaskAction(
                item.raw, directFinishSupported = item.actionType == "MULTI_STAGE" ||
                    (item.actionType == "VISIT" && !descriptor.isGameTask),
            )
            when (decision.action) {
                GameCenterPlayRpcCall.TaskAction.DIRECT_FINISH -> Unit
                GameCenterPlayRpcCall.TaskAction.DURATION_ONLY -> {
                    val result = GameCenterPlayRpcCall.advanceDuration(
                        "golden_game:${item.sceneCode}:${item.id}:${item.progress}", requireNotNull(decision.contract),
                    )
                    if (!result.success || result.deferredReason != null) return result
                }
                GameCenterPlayRpcCall.TaskAction.LEGACY_EXTERNAL_REPORT -> {
                    val report = runBlocking {
                        requireNotNull(decision.mappedTask).reportDetailed(1, GOLDEN_BEAN_GAME_CHANNEL,
                            includeSafetyReport = false) { Log.goldenBean(it) }
                    }
                    if (!report.completed) return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = report.failureMessage, rpc = "GameTask.reportDetailed",
                        detail = "taskId=${item.type}", continueCurrentRoundOnFailure = true,
                    )
                }
                else -> return TaskFlowActionResult.defer(
                    DeferredReason.PREREQUISITE_PENDING, "等待对应游戏业务条件", refreshAfterAction = true,
                )
            }
            nextQuerySyncTypes = TASK_STATUS_SYNC_TYPES
        }
        val response = GoldenBeanTreasureSupport.parseResponse(GoldenBeanRpcCall.finishTask(item.type, entry))
            ?: return GoldenBeanTreasureSupport.emptyResponseFailure(item, "complete")
        if (GoldenBeanTreasureSupport.isSuccess(response)) nextQuerySyncTypes = TASK_STATUS_SYNC_TYPES
        return GoldenBeanTreasureSupport.actionResult(item, response, "complete").copy(progressChanged = false)
    }

    override fun afterDeferred(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
        result.deferredUntil?.let { owner.scheduleGoldenBeanTasks("${item.id}:${item.progress}", it) }
    }

    override fun actionKey(
        item: TaskFlowItem,
        action: TaskFlowAction,
    ): String = "${action.logName}:${item.id}:${item.sceneCode}:${item.actionType}:${item.status}:${item.progress}"

    override fun blacklist(
        item: TaskFlowItem,
        result: TaskFlowActionResult,
    ) {
        val taskId = item.type
        if (taskId.isBlank()) {
            return
        }
        val stableId = stableBlacklistKey(item.sceneCode, taskId)
        if (result.failureType == TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW) {
            Log.error(
                GOLDEN_BEAN_BLACKLIST_MODULE,
                "金豆夺宝任务保留UNKNOWN_NEEDS_REVIEW，不自动加入黑名单 taskId=$stableId " +
                    "code=${result.code} raw=${result.raw ?: "EMPTY"}",
            )
            return
        }
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
        Log.goldenBean("金豆夺宝任务列表已无可自动推进或待领取任务")
    }

    override fun logInfo(message: String) {
        Log.goldenBean(message)
    }

    override fun logError(message: String) {
        Log.error(GOLDEN_BEAN_BLACKLIST_MODULE, message)
    }

    fun hasUnresolvedTasks(): Boolean {
        val taskList = latestTaskResponse.optJSONArray("taskList") ?: return false
        for (index in 0 until taskList.length()) {
            val task = taskList.optJSONObject(index) ?: continue
            if (task.optString("sceneCode") != entry.taskSceneCode) {
                continue
            }
            if (task.optString("taskStatus").uppercase() !in setOf("RECEIVED", "DONE") &&
                !isTaskBlacklisted(task)
            ) {
                return true
            }
        }
        return false
    }

    fun isMinerEntryReceived(): Boolean {
        val taskList = latestTaskResponse.optJSONArray("taskList") ?: return false
        for (index in 0 until taskList.length()) {
            val task = taskList.optJSONObject(index) ?: continue
            if (entry == GoldenBeanRpcCall.MASTER_ENTRY &&
                task.optString("sceneCode") == entry.taskSceneCode &&
                task.optString("taskId") == GoldenBeanRpcCall.WAKUANG_TASK_TYPE
            ) {
                return task.optString("taskStatus") in setOf("RECEIVED", "DONE")
            }
        }
        return false
    }

    private fun isTaskBlacklisted(task: JSONObject): Boolean {
        val sceneCode = task.optString("sceneCode")
        return listOf(task.optString("taskId"), task.optString("groupId"))
            .filter { it.isNotBlank() }
            .map { stableBlacklistKey(sceneCode, it) }
            .any { TaskBlacklist.isTaskInBlacklist(moduleName, it) }
    }

    private fun hasTaskAction(item: TaskFlowItem): Boolean {
        if (item.sceneCode != entry.taskSceneCode || item.type.isBlank()) return false
        if (item.actionType == GoldenBeanRpcCall.WAKUANG_ACTION_TYPE ||
            item.actionType == "COMMON_USED_VISIT" ||
            (entry == GoldenBeanRpcCall.MASTER_ENTRY && item.type == GoldenBeanRpcCall.JINDOULEYUAN_TASK_TYPE &&
                item.actionType == GoldenBeanRpcCall.JINDOULEYUAN_ACTION_TYPE)) return true
        val descriptor = GameCenterPlayRpcCall.describeTask(item.raw)
        val decision = GameCenterPlayRpcCall.resolveTaskAction(
            item.raw, directFinishSupported = item.actionType == "MULTI_STAGE" ||
                (item.actionType == "VISIT" && !descriptor.isGameTask),
        )
        return decision.action in setOf(
            GameCenterPlayRpcCall.TaskAction.DIRECT_FINISH,
            GameCenterPlayRpcCall.TaskAction.DURATION_ONLY,
            GameCenterPlayRpcCall.TaskAction.LEGACY_EXTERNAL_REPORT,
        )
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
        task.optJSONArray("multiStageDisplayList")?.let { stages ->
            for (index in 0 until stages.length()) {
                val stage = stages.getJSONObject(index)
                parts.add("stage=$index:${stage.optInt("timeCount")}:${stage.optString("status")}")
            }
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

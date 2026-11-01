package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.task.antFarm.AntFarm.Companion.TAG
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.UserDataStoreManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

private const val LOVE_CHICKEN_TASK_SCENE = "ANTFARM_PK_COMPETITION"
// 普通活动状态按三十分钟低频回查；蹲点配置和活动边界会提前下一次唤醒。
private const val LOVE_CHICKEN_POLL_MS = 30 * 60 * 1000L
// 查询失败或状态待确认时保留十分钟本地退避，避免连续重试同一活动。
private const val LOVE_CHICKEN_RETRY_MS = 10 * 60 * 1000L
private const val LOVE_CHICKEN_FINISHED_PREFIX = "antFarmLoveChickenGathering::finished::"

private data class LoveChickenActivitySnapshot(
    val activityId: String,
    val projectId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val rankRoundId: String,
    val participated: Boolean,
    val matching: Boolean,
    val settleAtMs: Long,
    val settleStartTime: LocalTime,
    val settleEndTime: LocalTime,
    val directContributionEndMs: Long,
    val finalSettlementMs: Long,
)

private data class LoveChickenTaskPage(val tasks: List<JSONObject>, val paramMap: JSONObject)

private data class LoveChickenRankMember(
    val userId: String,
    val rankOrder: Int,
    val donationNum: Int,
    val rewardContributionNum: Int?,
)

private data class LoveChickenRankSnapshot(
    val self: LoveChickenRankMember,
    val members: List<LoveChickenRankMember>,
)

private data class LoveChickenLevelReward(val rightsId: String, val threshold: Int, val status: String)
private data class LoveChickenGlobalAward(val order: Int, val contribution: Int, val rankTime: Long)
private data class LoveChickenRewardSnapshot(
    val contribution: Int,
    val rewards: List<LoveChickenLevelReward>,
    val globalAwards: List<LoveChickenGlobalAward>,
)
private data class LoveChickenDonationPlan(
    val amount: Int,
    val targetTotal: Int,
    val expectedContribution: Int?,
    val reason: String,
    val rankTargetMet: Boolean = false,
)

private class LoveChickenRpcException(val result: TaskFlowActionResult) : RuntimeException(result.message)

private fun AntFarm.requireLoveChickenResponse(rpc: String, response: JSONObject): JSONObject {
    if (!ResChecker.checkRes(TAG, response)) {
        val result = farmResourceFailure(rpc, response)
        Log.error(TAG, formatFarmHighRiskFailure(rpc, response, result.failureType!!))
        throw LoveChickenRpcException(result)
    }
    return response
}

private fun parseLoveChickenActivity(response: JSONObject): LoveChickenActivitySnapshot {
    val config = response.getJSONObject("donationCompetitionActivityConf")
    val rank = response.optJSONObject("donationRankHomeInfo")
    val settleStart = LocalTime.parse(config.getString("settleStartTime"))
    val settleEnd = LocalTime.parse(config.getString("settleEndTime"))
    val now = ZonedDateTime.now(FARM_ZONE)
    // 倒计时到零后不能把本轮截止滚动到下一周；周日结算期间仍使用本周边界。
    val calendarSettle = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).toLocalDate()
        .atTime(settleStart).atZone(FARM_ZONE).toInstant().toEpochMilli()
    val countdown = rank?.optLong("settleCountDown", -1L) ?: -1L
    val settleAt = if (countdown > 0 && calendarSettle > System.currentTimeMillis())
        System.currentTimeMillis() + countdown else calendarSettle
    val endTime = config.getLong("endTime")
    val end = Instant.ofEpochMilli(endTime).atZone(FARM_ZONE)
    var finalSettlement = end.toLocalDate().atTime(settleEnd).atZone(FARM_ZONE)
    if (finalSettlement.toInstant().toEpochMilli() < endTime) finalSettlement = finalSettlement.plusDays(1)
    return LoveChickenActivitySnapshot(
        activityId = config.getString("activityId"), projectId = config.getString("projectId"),
        startTimeMs = config.getLong("startTime"), endTimeMs = endTime,
        rankRoundId = rank?.optString("rankRoundId").orEmpty(),
        participated = response.optBoolean("isParticipateCompetition"),
        matching = rank == null || rank.optString("status") == "MATCHING",
        settleAtMs = settleAt, settleStartTime = settleStart, settleEndTime = settleEnd,
        directContributionEndMs = minOf(endTime, end.toLocalDate().atTime(20, 0)
            .atZone(FARM_ZONE).toInstant().toEpochMilli()),
        finalSettlementMs = maxOf(endTime, finalSettlement.toInstant().toEpochMilli()),
    )
}

private fun AntFarm.queryLoveChickenActivity(): JSONObject? {
    val owner = AccountSessionCoordinator.currentUserId().orEmpty()
    val epoch = AccountSessionCoordinator.currentSessionEpoch()
    val response = JSONObject(AntFarmRpcCall.enterDonationCompetitionRank())
    if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
    when (response.optString("resultCode")) {
        "DONATION_COMPETITION01" -> {
            Log.error(TAG, "爱心鸡结号当前入口无活动，结束本次续调 rpc=enterDonationCompetitionRank classification=TERMINAL_DONE raw=$response")
            return null
        }
        "DONATION_COMPETITION02" -> {
            Log.error(TAG, "爱心鸡结号功能未开启，结束本次续调 rpc=enterDonationCompetitionRank classification=BUSINESS_LIMIT raw=$response")
            return null
        }
    }
    requireLoveChickenResponse("enterDonationCompetitionRank", response)
    if (response.optJSONObject("donationCompetitionActivityConf")?.has("projectId") != true) {
        Log.farm("当前入口不是爱心鸡结号活动，由对应玩法处理")
        return null
    }
    val snapshot = parseLoveChickenActivity(response)
    require(snapshot.activityId.isNotBlank() && snapshot.projectId.isNotBlank() && snapshot.endTimeMs > 0)
    return response
}

/** 保存待确认动作、延期失败和稳定目标所需的最近两次排名样本。 */
private class LoveChickenExecution(private val snapshot: LoveChickenActivitySnapshot) {
    private val owner = AccountSessionCoordinator.currentUserId().orEmpty()
    private val epoch = AccountSessionCoordinator.currentSessionEpoch()
    private val store = UserDataStoreManager.getInstance(owner)
    private val key = "antFarmLoveChickenGathering::pending::${snapshot.activityId}::${snapshot.endTimeMs}"
    val data = store?.get(key, String::class.java)?.let(::JSONObject) ?: JSONObject()
    val tasks = data.optJSONObject("tasks") ?: JSONObject().also { data.put("tasks", it) }
    val rewards = data.optJSONObject("rewards") ?: JSONObject().also { data.put("rewards", it) }
    val failures = data.optJSONObject("failures") ?: JSONObject().also { data.put("failures", it) }
    val flowState = TaskFlowExecutionState()
    val attemptedTasks = mutableSetOf<String>()
    val attemptedRewards = mutableSetOf<String>()
    var retryAt: Long? = null
    var stopped = false
    val hasPending: Boolean get() = tasks.length() > 0 || rewards.length() > 0 || data.has("annGift")

    fun save() {
        if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
        store?.put(key, data.toString())
    }

    fun canRetryPending(pending: JSONObject, attempted: Boolean): Boolean {
        val now = System.currentTimeMillis()
        var submittedAt = pending.optLong("submittedAt")
        if (submittedAt <= 0L) {
            // 旧记录先经过本次有效查询，再从此时起等待，不直接重放历史请求。
            submittedAt = now
            pending.put("submittedAt", submittedAt)
            save()
        }
        val until = submittedAt + LOVE_CHICKEN_RETRY_MS
        if (!attempted && now >= until) return true
        val next = if (until > now) until else now + LOVE_CHICKEN_RETRY_MS
        retryAt = minOf(retryAt ?: next, next)
        return false
    }

    fun isBlocked(action: String): Boolean {
        val failure = failures.optJSONObject(action) ?: return false
        if (System.currentTimeMillis() >= snapshot.finalSettlementMs &&
            ((action == "listCompetitionTask" && failure.optString("code") == "DONATION_COMPETITION04") ||
                (action == "enterCompetitionAwardPage" && failure.optString("code") == "DONATION_COMPETITION01"))) {
            failures.remove(action)
            save()
            return false
        }
        val until = failure.optLong("until")
        if (until > System.currentTimeMillis()) {
            if (until != Long.MAX_VALUE) retryAt = minOf(retryAt ?: until, until)
            return true
        }
        failures.remove(action)
        save()
        return false
    }

    fun recordFailure(action: String, result: TaskFlowActionResult, stopTarget: Boolean = false) {
        val now = System.currentTimeMillis()
        val nextDay = ZonedDateTime.now(FARM_ZONE).toLocalDate().plusDays(1)
            .atStartOfDay(FARM_ZONE).toInstant().toEpochMilli()
        val until = result.deferredUntil ?: when (result.failureType) {
            TaskRpcFailureType.BUSINESS_LIMIT -> nextDay
            TaskRpcFailureType.NON_RETRYABLE_INVALID, TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE -> Long.MAX_VALUE
            TaskRpcFailureType.TERMINAL_DONE -> nextDay
            else -> now + LOVE_CHICKEN_RETRY_MS
        }
        failures.put(action, JSONObject().put("until", until).put("rpc", result.rpc)
            .put("classification", result.failureType?.name).put("code", result.code)
            .put("message", result.message).put("raw", result.raw))
        if (until != Long.MAX_VALUE) retryAt = minOf(retryAt ?: until, until)
        if (stopTarget) stopped = true
        Log.error(TAG, "爱心鸡结号动作停止[$action] classification=${result.failureType} rpc=${result.rpc} " +
            "code=${result.code} message=${result.message} next=$until raw=${result.raw}")
        save()
    }
}

private class LoveChickenTaskAdapter(
    private val farm: AntFarm,
    private val snapshot: LoveChickenActivitySnapshot,
    private val execution: LoveChickenExecution,
) : TaskFlowAdapter {
    override val moduleName = "蚂蚁庄园"
    override val flowName = "爱心鸡结号活动任务"
    private val owner = AccountSessionCoordinator.currentUserId().orEmpty()
    private val epoch = AccountSessionCoordinator.currentSessionEpoch()
    var lastPage: LoveChickenTaskPage? = null
        private set
    var confirmedCount = 0
        private set

    override fun query(): JSONObject {
        lastPage = null
        if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
        return try {
            JSONObject(AntFarmRpcCall.listCompetitionTask())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            execution.recordFailure("listCompetitionTask", TaskFlowActionResult.failure(
                TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, rpc = "listCompetitionTask",
                message = t.message.orEmpty(), raw = t.toString()))
            throw t
        }
    }

    override fun isQuerySuccess(response: JSONObject): Boolean {
        if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) return false
        if (System.currentTimeMillis() >= snapshot.finalSettlementMs &&
            response.optString("resultCode") == "DONATION_COMPETITION04") return true
        return ResChecker.checkRes(TAG, response) &&
            response.optJSONArray("taskList") != null && response.optJSONObject("paramMap") != null
    }

    override fun isPendingActionResolved(
        item: TaskFlowItem, action: TaskFlowAction, response: JSONObject, items: List<TaskFlowItem>,
    ): Boolean {
        val expiresAt = item.raw?.optLong("taskEntityExpireTime") ?: 0L
        return (expiresAt > 0L && expiresAt <= System.currentTimeMillis()) ||
            (System.currentTimeMillis() >= snapshot.finalSettlementMs &&
                response.optString("resultCode") == "DONATION_COMPETITION04")
    }

    override fun isQueryComplete(response: JSONObject) = execution.tasks.length() == 0

    override fun onQueryFailed(response: JSONObject) {
        lastPage = null
        execution.recordFailure("listCompetitionTask", if (ResChecker.checkRes(TAG, response))
            TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, rpc = "listCompetitionTask",
                message = "任务列表或活动参数未返回", raw = response.toString())
            else farm.farmResourceFailure("listCompetitionTask", response))
    }

    override fun actionKey(item: TaskFlowItem, action: TaskFlowAction) =
        "${action.name}:${snapshot.rankRoundId}:${item.id}:${item.current}:${item.progress}"

    private fun pendingKey(item: TaskFlowItem) = "${snapshot.rankRoundId}:${item.id}:${item.raw!!.optLong("taskEntityExpireTime") }"

    override fun shouldSkip(item: TaskFlowItem) = mapPhase(item) == TaskFlowPhase.REWARD_READY &&
        (execution.tasks.has(pendingKey(item)) || actionKey(item, TaskFlowAction.RECEIVE) in execution.attemptedTasks ||
            execution.isBlocked(actionKey(item, TaskFlowAction.RECEIVE)))
    override fun isUnresolvedWhenSkipped(item: TaskFlowItem) = true

    override fun extractItems(response: JSONObject): List<TaskFlowItem> {
        if (System.currentTimeMillis() >= snapshot.finalSettlementMs &&
            response.optString("resultCode") == "DONATION_COMPETITION04") {
            Log.error(TAG, "爱心鸡结号旧活动任务已下线 activity=${snapshot.activityId} classification=TERMINAL_DONE raw=$response")
            execution.tasks.keys().asSequence().toList().forEach { execution.tasks.remove(it) }
            execution.save()
            lastPage = LoveChickenTaskPage(emptyList(), JSONObject())
            return emptyList()
        }
        val list = response.getJSONArray("taskList")
        val page = LoveChickenTaskPage((0 until list.length()).map { list.getJSONObject(it) }, response.getJSONObject("paramMap"))
        lastPage = page
        val scene = response.optString("taskSceneCode").ifBlank { LOVE_CHICKEN_TASK_SCENE }
        val items = page.tasks.map { task ->
            val id = task.getString("taskType")
            TaskFlowItem(
                id = id, title = task.optString("title", id), status = task.getString("taskStatus"),
                type = id, sceneCode = scene, raw = task,
                current = task.optInt("alreadyReceiveStageAwardCount"), limit = task.optInt("rightsTimesLimit", 1),
                progress = "${task.optInt("taskThreshold")}:${task.optLong("taskEntityExpireTime")}",
            )
        }
        val now = System.currentTimeMillis()
        for (key in execution.tasks.keys().asSequence().toList()) {
            val pending = execution.tasks.getJSONObject(key)
            val before = pending.getJSONObject("task")
            val id = before.getString("taskType")
            val expiresAt = before.optLong("taskEntityExpireTime")
            if (pending.getString("round") != snapshot.rankRoundId || (expiresAt > 0L && expiresAt <= now)) {
                execution.tasks.remove(key)
                Log.farm("爱心鸡结号任务过期未确认[taskType=$id round=${pending.optString("round")} expire=$expiresAt]")
                continue
            }
            val item = items.firstOrNull { it.id == id && it.raw!!.optLong("taskEntityExpireTime") == expiresAt }
            val after = item?.raw
            val threshold = before.optInt("taskThreshold", -1)
            val suffix = "_$threshold"
            val stageAdvanced = threshold > 0 && id.endsWith(suffix) && page.tasks.any {
                val nextThreshold = it.optInt("taskThreshold", -1)
                nextThreshold > threshold && it.getString("taskType") == id.removeSuffix(suffix) + "_$nextThreshold" &&
                    it.optLong("taskEntityExpireTime") == expiresAt
            }
            if (after?.optString("taskStatus") == "RECEIVED" ||
                (after != null && after.optInt("alreadyReceiveStageAwardCount") > before.optInt("alreadyReceiveStageAwardCount")) || stageAdvanced
            ) {
                execution.tasks.remove(key)
                confirmedCount++
                Log.farm("爱心鸡结号任务奖励已回查确认[$id]")
            } else if (item != null && after != null && mapPhase(item) == TaskFlowPhase.REWARD_READY &&
                after.optInt("alreadyReceiveStageAwardCount") == before.optInt("alreadyReceiveStageAwardCount") &&
                after.optInt("taskThreshold", -1) == threshold
            ) {
                val action = actionKey(item, TaskFlowAction.RECEIVE)
                if (!execution.isBlocked(action) && execution.canRetryPending(pending, action in execution.attemptedTasks)) {
                    execution.tasks.remove(key)
                    Log.farm("爱心鸡结号任务回查仍可领取，等待期已过[$id]")
                }
            }
        }
        execution.save()
        return items
    }

    fun logUnresolved(reason: String) {
        val tasks = lastPage?.tasks ?: execution.tasks.keys().asSequence().map {
            execution.tasks.getJSONObject(it).getJSONObject("task")
        }.toList()
        Log.farm("爱心鸡结号停止新增耗蛋[$reason] pendingTasks=${execution.tasks.length()} pendingRewards=${execution.rewards.length()}")
        for (task in tasks) {
            Log.farm("爱心鸡结号任务快照[taskType=${task.optString("taskType")} status=${task.optString("taskStatus")} " +
                "stage=${task.optInt("taskThreshold", -1)} canReceive=${task.optInt("canReceiveAwardCount")} " +
                "received=${task.optInt("alreadyReceiveStageAwardCount")} expire=${task.optLong("taskEntityExpireTime")}]")
        }
    }

    override fun mapPhase(item: TaskFlowItem) = when (item.status) {
        "FINISHED", "ENDED" -> if (item.raw!!.optInt("canReceiveAwardCount") > 0) TaskFlowPhase.REWARD_READY else TaskFlowPhase.TERMINAL
        "TODO", "RECEIVED" -> TaskFlowPhase.TERMINAL
        else -> TaskFlowPhase.UNKNOWN
    }

    override fun receive(item: TaskFlowItem): TaskFlowActionResult {
        if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
        val key = pendingKey(item)
        execution.attemptedTasks += actionKey(item, TaskFlowAction.RECEIVE)
        execution.tasks.put(key, JSONObject().put("round", snapshot.rankRoundId).put("task", item.raw)
            .put("submittedAt", System.currentTimeMillis()))
        execution.save()
        val response = try {
            JSONObject(AntFarmRpcCall.receiveTaskAwardAntFarm(item.sceneCode, item.type, item.raw!!.getInt("canReceiveAwardCount")))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val failure = TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                rpc = "receiveTaskAwardantfarm", message = t.message.orEmpty(), raw = t.toString(),
                continueCurrentRoundOnFailure = true).copy(refreshAfterAction = true)
            execution.recordFailure(actionKey(item, TaskFlowAction.RECEIVE), failure)
            return failure
        }
        if (ResChecker.checkRes(TAG, response)) return TaskFlowActionResult.success()
        execution.tasks.remove(key)
        val failure = farm.farmResourceFailure("receiveTaskAwardantfarm", response)
        execution.recordFailure(actionKey(item, TaskFlowAction.RECEIVE), failure)
        return failure.copy(continueCurrentRoundOnFailure = true, refreshAfterAction = true)
    }

    override fun logInfo(message: String) = Log.farm(message)
    override fun logError(message: String) = Log.error(TAG, message)
}

private fun AntFarm.receiveLoveChickenRewards(snapshot: LoveChickenActivitySnapshot, execution: LoveChickenExecution): LoveChickenRewardSnapshot? {
    if (execution.isBlocked("enterCompetitionAwardPage")) return null
    val owner = AccountSessionCoordinator.currentUserId().orEmpty()
    val epoch = AccountSessionCoordinator.currentSessionEpoch()
    while (!ApplicationHookConstants.isOffline()) {
        if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
        val response = JSONObject(AntFarmRpcCall.enterCompetitionAwardPage())
        if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
        if (System.currentTimeMillis() >= snapshot.finalSettlementMs &&
            response.optString("resultCode") == "DONATION_COMPETITION01") {
            Log.error(TAG, "爱心鸡结号旧活动奖励入口已下线 activity=${snapshot.activityId} classification=TERMINAL_DONE raw=$response")
            execution.rewards.keys().asSequence().toList().forEach { execution.rewards.remove(it) }
            execution.data.remove("annGift")
            execution.save()
            return LoveChickenRewardSnapshot(0, emptyList(), emptyList())
        }
        requireLoveChickenResponse("enterCompetitionAwardPage", response)
        if (response.getJSONObject("donationCompetitionActivityConf").getString("activityId") != snapshot.activityId) return null
        val contribution = response.getJSONObject("userDonationLevelInfo").getInt("userContributionNum")
        val list = response.getJSONArray("levelAwardInfoList")
        val rewards = (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            LoveChickenLevelReward(item.getString("rightsId"), item.getInt("levelContributionTotalNum"), item.getString("status"))
        }
        require(rewards.isNotEmpty() && rewards.all { it.status in setOf("received", "unclaimed", "unattained") }) {
            "爱心鸡结号奖励列表未确认:$response"
        }
        rewards.filter { it.status == "received" }.forEach { execution.rewards.remove(it.rightsId) }
        execution.save()
        val candidates = rewards.filter { reward ->
            if (reward.status != "unclaimed" || reward.rightsId in execution.attemptedRewards ||
                execution.isBlocked("reward:${reward.rightsId}")) false
            else {
                val pending = execution.rewards.optJSONObject(reward.rightsId)
                pending == null || (pending.optInt("threshold") == reward.threshold &&
                    execution.canRetryPending(pending, attempted = false))
            }
        }
        if (candidates.isEmpty()) {
            val globalRanks = response.optJSONObject("globalRankAwardInfo")?.optJSONArray("contributionRankInfoList")
            val globalAwards = (0 until (globalRanks?.length() ?: 0)).mapNotNull { index ->
                val rank = globalRanks?.optJSONObject(index) ?: return@mapNotNull null
                val order = rank.optInt("awardRankOrder", -1)
                val minimum = rank.optInt("awardContributionNum", -1)
                if (order <= 0 || minimum < 0) return@mapNotNull null
                LoveChickenGlobalAward(order, minimum, rank.optLong("rankTime"))
            }
            return LoveChickenRewardSnapshot(contribution, rewards, globalAwards)
        }
        for (reward in candidates) {
            if (ApplicationHookConstants.isOffline()) return null
            if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
            execution.attemptedRewards += reward.rightsId
            execution.rewards.put(reward.rightsId, JSONObject().put("threshold", reward.threshold)
                .put("submittedAt", System.currentTimeMillis()))
            execution.save()
            try {
                val receive = JSONObject(AntFarmRpcCall.receiveDonationLevelReward(reward.rightsId))
                if (!ResChecker.checkRes(TAG, receive)) {
                    execution.rewards.remove(reward.rightsId)
                    execution.recordFailure("reward:${reward.rightsId}", farmResourceFailure("receiveDonationLevelReward", receive))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                execution.recordFailure("reward:${reward.rightsId}", TaskFlowActionResult.failure(
                    TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, rpc = "receiveDonationLevelReward",
                    message = t.message.orEmpty(), raw = t.toString()))
            }
        }
    }
    return null
}

private fun AntFarm.receiveLoveChickenAnnGift(response: JSONObject, execution: LoveChickenExecution): Boolean {
    if (ApplicationHookConstants.isOffline()) return false
    val owner = AccountSessionCoordinator.currentUserId().orEmpty()
    val epoch = AccountSessionCoordinator.currentSessionEpoch()
    val info = response.optJSONObject("ann9thCompetitionInfo") ?: return false
    val pending = execution.data.optJSONObject("annGift")
    if (info.optString("ann9thCakeStatus") == "RECEIVED" && pending != null) {
        if (execution.isBlocked("annGiftResources")) return false
        var failure: TaskFlowActionResult? = null
        syncAnimalStatus(ownerFarmId) { failure = it }
        if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
        if (failure != null) {
            execution.recordFailure("annGiftResources", failure!!)
            return false
        }
        execution.data.remove("annGift")
        execution.save()
        Log.farm("爱心鸡结号周年食品已回查确认，庄园资源已刷新")
        return true
    }
    if (!info.optBoolean("ann9thActivitySwitch") || info.optString("ann9thCakeStatus") != "PENDING" ||
        "getAnnGift" in execution.attemptedRewards || execution.isBlocked("getAnnGift")) return false
    if (pending != null && !execution.canRetryPending(pending, attempted = false)) return false
    execution.attemptedRewards += "getAnnGift"
    execution.data.put("annGift", JSONObject().put("submittedAt", System.currentTimeMillis()))
    execution.save()
    try {
        val receive = JSONObject(AntFarmRpcCall.getAnnGift())
        if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
        if (!ResChecker.checkRes(TAG, receive)) {
            execution.data.remove("annGift")
            execution.recordFailure("getAnnGift", farmResourceFailure("getAnnGift", receive))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        execution.recordFailure("getAnnGift", TaskFlowActionResult.failure(
            TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, rpc = "getAnnGift",
            message = t.message.orEmpty(), raw = t.toString()))
    }
    return false
}

private fun AntFarm.queryLoveChickenFullRank(snapshot: LoveChickenActivitySnapshot): LoveChickenRankSnapshot? {
    val response = requireLoveChickenResponse("queryAllMemberRankInfo", JSONObject(AntFarmRpcCall.queryAllMemberRankInfo()))
    if (response.getJSONObject("donationCompetitionActivityConf").getString("activityId") != snapshot.activityId) return null
    var home = response.getJSONObject("donationRankHomeInfo")
    if (home.getString("rankRoundId") != snapshot.rankRoundId || home.optString("status") == "MATCHING") return null
    fun member(item: JSONObject) = LoveChickenRankMember(
        item.getString("userId"), item.getInt("rankOrder"), item.getInt("donationNum"),
        if (item.has("rewardContributionNum") && !item.isNull("rewardContributionNum")) item.getInt("rewardContributionNum") else null,
    )
    val self = member(home.getJSONObject("selfDonationRank"))
    val members = linkedMapOf<String, LoveChickenRankMember>()
    val pages = mutableSetOf<String>()
    var pageNo = 1
    while (!ApplicationHookConstants.isOffline()) {
        val list = home.getJSONArray("userDonationRankList")
        require(pages.add(list.toString())) { "爱心鸡结号榜单分页重复:$home" }
        for (index in 0 until list.length()) {
            val item = member(list.getJSONObject(index))
            val previous = members[item.userId]
            members[item.userId] = if (item.rewardContributionNum == null && previous != null && previous.rankOrder == item.rankOrder)
                item.copy(rewardContributionNum = previous.rewardContributionNum) else item
        }
        if (!home.optBoolean("hasMore")) return LoveChickenRankSnapshot(self, members.values.toList())
        val page = requireLoveChickenResponse("queryPageRankInfo", JSONObject(AntFarmRpcCall.queryPageRankInfo(snapshot.rankRoundId, pageNo++)))
        home = page.getJSONObject("donationRankHomeInfo")
        if (home.getString("rankRoundId") != snapshot.rankRoundId) return null
    }
    return null
}

private fun AntFarm.loveChickenWatchStart(snapshot: LoveChickenActivitySnapshot): Long {
    val value = loveChickenTime?.value.orEmpty().ifBlank { "1958" }
    val number = value.toIntOrNull()
    if (number != null && value.length < 4 && number > 0) return snapshot.settleAtMs - number * 60_000L
    val time = if (value.length == 4 && number != null && number / 100 in 0..23 && number % 100 in 0..59)
        LocalTime.of(number / 100, number % 100) else LocalTime.of(19, 58)
    return minOf(snapshot.settleAtMs - 1000L, Instant.ofEpochMilli(snapshot.settleAtMs)
        .atZone(FARM_ZONE).toLocalDate().atTime(time).atZone(FARM_ZONE).toInstant().toEpochMilli())
}

private fun AntFarm.selectLoveChickenDonation(
    snapshot: LoveChickenActivitySnapshot, page: LoveChickenTaskPage, rank: LoveChickenRankSnapshot?,
    rewards: LoveChickenRewardSnapshot, remainingQuota: Int, execution: LoveChickenExecution,
): LoveChickenDonationPlan {
    val now = System.currentTimeMillis()
    val stable = loveChickenMode?.value == 1
    val selfTotal = rank?.self?.donationNum ?: 0
    val highestLevel = rewards.rewards.maxOf { it.threshold }
    val globalAward = rewards.globalAwards.minByOrNull { it.order }
    val contributionMargin = (loveChickenContributionMargin?.value ?: 10).coerceAtLeast(1)
    var rankSamples = execution.data.optJSONObject("globalRankSamples")
        ?.takeIf { stable && globalAward != null && it.optInt("order") == globalAward.order }
    if (stable && globalAward != null && globalAward.rankTime in 1..now) {
        val latest = rankSamples?.optJSONObject("latest")
        if (latest == null || globalAward.rankTime > latest.optLong("rankTime")) {
            rankSamples = JSONObject().put("order", globalAward.order)
                .put("latest", JSONObject().put("rankTime", globalAward.rankTime)
                    .put("contribution", globalAward.contribution))
            if (latest != null) rankSamples.put("previous", latest)
            execution.data.put("globalRankSamples", rankSamples)
            execution.save()
        }
    }
    val latestRank = rankSamples?.optJSONObject("latest")
    val previousRank = rankSamples?.optJSONObject("previous")
    val observedThreshold = latestRank?.optInt("contribution") ?: globalAward?.contribution ?: 0
    val rankTime = latestRank?.optLong("rankTime") ?: globalAward?.rankTime ?: 0L
    val previousTime = previousRank?.optLong("rankTime") ?: 0L
    val growthPerMs = if (stable && previousTime > 0L && rankTime > previousTime) {
        (observedThreshold.toLong() - previousRank!!.getInt("contribution")).coerceAtLeast(0).toDouble() /
            (rankTime - previousTime)
    } else 0.0
    val currentIncomeAt = minOf(now + LOVE_CHICKEN_POLL_MS, snapshot.endTimeMs)
    var settlementEnd = Instant.ofEpochMilli(snapshot.settleAtMs).atZone(FARM_ZONE).toLocalDate()
        .atTime(snapshot.settleEndTime).atZone(FARM_ZONE)
    if (settlementEnd.toInstant().toEpochMilli() <= snapshot.settleAtMs) settlementEnd = settlementEnd.plusDays(1)
    if (settlementEnd.toInstant().toEpochMilli() <= now) settlementEnd = settlementEnd.plusWeeks(1)
    val nextIncomeAt = minOf(settlementEnd.toInstant().toEpochMilli(), snapshot.finalSettlementMs)
        .coerceAtLeast(currentIncomeAt)
    val currentGrowth = ceil(growthPerMs * (currentIncomeAt - rankTime).coerceAtLeast(0)).toLong()
        .coerceAtMost(Int.MAX_VALUE.toLong())
    val futureGrowth = ceil(growthPerMs * (nextIncomeAt - rankTime).coerceAtLeast(0)).toLong()
        .coerceAtMost(Int.MAX_VALUE.toLong())
    val currentTarget = maxOf(highestLevel, if (globalAward == null) highestLevel else
        (observedThreshold.toLong() + contributionMargin + currentGrowth).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    val target = maxOf(currentTarget, if (globalAward == null) highestLevel else
        (observedThreshold.toLong() + contributionMargin + futureGrowth).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    val immediateGap = (currentTarget - rewards.contribution).coerceAtLeast(0)
    val gap = (target - rewards.contribution).coerceAtLeast(0)
    val targetDescription = if (globalAward == null) "全球末位待查询，成就目标$highestLevel"
        else "当前目标$currentTarget，结算目标$target，末位$observedThreshold+余量$contributionMargin，" +
            "排名时间$rankTime，时效增量$currentGrowth/$futureGrowth" +
            if (previousTime <= 0L) "，排名样本不足，未估算增长" else "，下次结算到账${TimeUtil.getCommonDate(nextIncomeAt)}"
    fun none(reason: String, met: Boolean = false) = LoveChickenDonationPlan(0, selfTotal, rank?.self?.rewardContributionNum, "$reason；$targetDescription", met)
    if (now >= snapshot.endTimeMs) return none("活动结束")
    if (remainingQuota <= 0) return none("今日捐蛋额度用尽，当前仍差$immediateGap，结算目标仍差$gap")
    if (stable && gap == 0) return none(if (globalAward == null) "成就达成，等待全球排名" else "稳定目标已达成，继续定期回查")
    var rounds = 0
    var settlement = ZonedDateTime.now(FARM_ZONE).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        .toLocalDate().atTime(snapshot.settleStartTime).atZone(FARM_ZONE)
    while (settlement.toInstant().toEpochMilli() <= snapshot.endTimeMs) {
        if (settlement.toInstant().toEpochMilli() > now) rounds++
        settlement = settlement.plusWeeks(1)
    }
    val weeklyNeed = gap
    val activeTasks = page.tasks.filter { it.optString("taskStatus") == "TODO" &&
        it.optLong("taskEntityExpireTime", Long.MAX_VALUE) > now }
    val personal = if (now < snapshot.directContributionEndMs && activeTasks.any {
        it.optString("taskType") == "USER_ACCUMLATED_DONATION_TASK"
    }) (page.paramMap.getInt("userActivityDonateMax") - page.paramMap.getInt("userActivityDonateTotal")).coerceAtLeast(0) else 0
    val firstTask = activeTasks.firstOrNull { it.optString("taskType") == "USER_FIRST_DONATION_TASK" }
    val firstReward = firstTask?.optInt("canReceiveAwardCount", 0) ?: 0
    val teamThreshold = page.paramMap.getInt("teamTaskThreshold")
    val teamTask = if (!snapshot.matching && now < snapshot.settleAtMs) activeTasks.firstOrNull {
        it.optInt("taskThreshold", -1) == teamThreshold && teamThreshold >= 100
    } else null
    val teamNeed = if (teamTask != null) (teamThreshold - page.paramMap.getInt("teamDonateTotal")).coerceAtLeast(0) else 0
    val teamReward = teamTask?.optInt("canReceiveAwardCount", 0) ?: 0
    val inWatch = now >= loveChickenWatchStart(snapshot) && now < snapshot.settleAtMs
    val singleDone = execution.data.optString("watchRound") == snapshot.rankRoundId
    val rankAllowed = rank != null && !snapshot.matching && now < snapshot.settleAtMs &&
        ((inWatch && (loveChickenWatchMode?.value == 1 || !singleDone)) || (stable && loveChickenAnytimeCheck?.value == true))
    val opponents = rank?.members.orEmpty().filter { it.userId != rank?.self?.userId }
    val margin = (loveChickenOvertakeAmount?.value ?: 1).coerceAtLeast(1)
    fun needToPass(member: LoveChickenRankMember) = (member.donationNum.toLong() + margin - selfTotal)
        .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    if (!stable) {
        val rankNeed = if (rankAllowed) maxOf(if (selfTotal == 0) 1 else 0,
            opponents.maxOfOrNull(::needToPass) ?: 0) else 0
        val taskNeed = listOf(personal, if (firstTask != null) 1 else 0, teamNeed).filter { it > 0 }.minOrNull() ?: 0
        val need = if (taskNeed > 0) taskNeed else rankNeed
        return LoveChickenDonationPlan(minOf(need, remainingQuota),
            (selfTotal.toLong() + need).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), null,
            (if (taskNeed > 0) "日常爱心值任务/直接贡献" else "激进周榜第一，余量$margin") +
                "；$targetDescription，已到账${rewards.contribution}，差额$gap",
            rankTargetMet = rankAllowed && rankNeed == 0)
    }
    val currentRankReward = if (rounds > 0 && now < snapshot.settleAtMs && selfTotal > 0) rank?.self?.rewardContributionNum else 0
    val candidates = sortedSetOf(0)
    if (firstTask != null) candidates += 1
    if (teamNeed > 0) candidates += teamNeed
    if (personal > 0) candidates += personal
    if (rankAllowed) {
        if (selfTotal == 0) candidates += 1
        opponents.forEach { candidates += needToPass(it) }
    }
    fun rankReward(eggs: Int): Int? {
        if (rank == null || rounds == 0 || now >= snapshot.settleAtMs) return 0
        if (eggs == 0 && selfTotal > 0) return rank.self.rewardContributionNum
        if (selfTotal + eggs <= 0) return 0
        val position = opponents.count { it.donationNum >= selfTotal.toLong() + eggs } + 1
        return (rank.members + rank.self).firstOrNull { it.rankOrder == position && it.rewardContributionNum != null }?.rewardContributionNum
    }
    fun immediateGain(eggs: Int): Int = minOf(eggs, personal) + (if (eggs > 0) firstReward else 0) +
        (if (teamNeed > 0 && eggs >= teamNeed) teamReward else 0)
    fun gain(eggs: Int): Int = immediateGain(eggs) + (rankReward(eggs) ?: 0)
    for (base in candidates.toList()) {
        val required = maxOf(immediateGap - immediateGain(base), weeklyNeed - gain(base), 0)
        if (base < personal) candidates += base + minOf(required, personal - base)
    }
    // 未到账周奖只覆盖结算目标，不能抵消当前缺口；非蹲点仍只使用确定的日常收益。
    val available = candidates.filter { it == 0 || rankAllowed || it <= maxOf(personal, if (firstTask != null) 1 else 0, teamNeed) }
    val sufficient = available.filter { immediateGain(it) >= immediateGap && gain(it) >= weeklyNeed }
    val chosen = if (sufficient.isNotEmpty()) sufficient.minWithOrNull(compareBy<Int> { it }.thenByDescending { gain(it) })!!
        else available.minWithOrNull(compareBy<Int> { (immediateGap - immediateGain(it)).coerceAtLeast(0) }
            .thenBy { (weeklyNeed - gain(it)).coerceAtLeast(0) }.thenBy { it }) ?: 0
    if (immediateGap == 0 && currentRankReward != null && currentRankReward >= weeklyNeed && firstTask == null)
        return none("当前目标已达成，预计周奖励${currentRankReward}覆盖结算差额$weeklyNeed，等待到账回查", rankAllowed)
    val amount = if (firstTask != null && chosen == 0) 1 else chosen
    val planned = minOf(amount, remainingQuota)
    val remainingImmediate = (immediateGap - immediateGain(planned)).coerceAtLeast(0)
    val remainingFuture = (weeklyNeed - gain(planned)).coerceAtLeast(0)
    return LoveChickenDonationPlan(planned, (selfTotal.toLong() + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        gain(planned), "当前缺口$immediateGap，结算差额$weeklyNeed，$targetDescription，已到账${rewards.contribution}，" +
            "剩余${rounds}次周结算，预计周奖励${currentRankReward ?: "未知"}，" +
            "本次后当前缺口$remainingImmediate、结算差额$remainingFuture" +
            if (planned < amount) "，受今日剩余额度${remainingQuota}限制，尚未达到稳定目标" else "",
        rankTargetMet = rankAllowed && chosen == 0 && sufficient.isNotEmpty())
}

private fun AntFarm.scheduleLoveChickenTask(snapshot: LoveChickenActivitySnapshot?, execution: LoveChickenExecution? = null, retryAt: Long? = null) {
    val now = System.currentTimeMillis()
    val nextDay = ZonedDateTime.now(FARM_ZONE).toLocalDate().plusDays(1).atStartOfDay(FARM_ZONE).toInstant().toEpochMilli()
    // 已结束活动改为六小时低频回查，减少无进展查询；下方仍优先对齐明确的最终结算时间。
    var triggerAt = retryAt ?: execution?.retryAt ?: if (snapshot != null && now >= snapshot.endTimeMs)
        now + 6 * 60 * 60 * 1000L else now + LOVE_CHICKEN_POLL_MS
    if (snapshot != null) {
        if (snapshot.startTimeMs > now) triggerAt = snapshot.startTimeMs
        else if (now < snapshot.endTimeMs) {
            val watchStart = loveChickenWatchStart(snapshot)
            val inWatch = now >= watchStart && now < snapshot.settleAtMs
            // 蹲点窗口内改用用户配置的刷新间隔；显式失败退避优先，防止把失败重试变为高频轮询。
            if (inWatch && loveChickenWatchMode?.value == 1 && execution?.stopped != true &&
                execution?.isBlocked("donation") != true && retryAt == null && execution?.retryAt == null
            ) triggerAt = minOf(triggerAt, now + (loveChickenRefreshInterval?.value ?: 10).coerceAtLeast(1) * 1000L)
            val boundary = listOf(watchStart, snapshot.settleAtMs, snapshot.directContributionEndMs,
                snapshot.endTimeMs, nextDay - 60_000L, nextDay).filter { it > now }.minOrNull()
            if (boundary != null) triggerAt = minOf(triggerAt, boundary)
        } else if (snapshot.finalSettlementMs > now) triggerAt = minOf(triggerAt, snapshot.finalSettlementMs)
    }
    val payload = JSONObject().put("activity_id", snapshot?.activityId).put("rank_round_id", snapshot?.rankRoundId)
        .put("end_time_ms", snapshot?.endTimeMs ?: 0L).put("trigger_at", triggerAt)
    replaceLoveChickenSchedule(triggerAt, payload)
    Log.farm("爱心鸡结号下次查询[${TimeUtil.getCommonDate(triggerAt)}]")
}

internal suspend fun AntFarm.runLoveChickenGatheringWorkflow(initialResponse: JSONObject? = null): Boolean {
    if (loveChickenGathering?.value != true || !check() || ApplicationHookConstants.isOffline()) return false
    val owner = AccountSessionCoordinator.currentUserId().orEmpty()
    val epoch = AccountSessionCoordinator.currentSessionEpoch()
    val store = UserDataStoreManager.getInstance(owner)
    var snapshot: LoveChickenActivitySnapshot? = null
    var execution: LoveChickenExecution? = null
    var progressed = false
    var cancelled = false
    var finished = false
    var schedule = true
    var retryAt: Long? = null
    try {
        var response = initialResponse ?: queryLoveChickenActivity()
        if (response == null) { schedule = false; return false }
        snapshot = parseLoveChickenActivity(response)
        val finishedKey = "$LOVE_CHICKEN_FINISHED_PREFIX${snapshot.activityId}::${snapshot.endTimeMs}"
        if (store?.get(finishedKey, Boolean::class.javaObjectType) == true) { finished = true; return false }
        if (snapshot.startTimeMs > System.currentTimeMillis()) return false
        execution = LoveChickenExecution(snapshot)
        if (!snapshot.participated && System.currentTimeMillis() < snapshot.endTimeMs) {
            if (execution.isBlocked("participateCompetition")) return false
            requireLoveChickenResponse("participateCompetition", JSONObject(AntFarmRpcCall.participateCompetition()))
            response = queryLoveChickenActivity()
            if (response == null) { schedule = false; return false }
            snapshot = parseLoveChickenActivity(response)
            progressed = snapshot.participated
            if (!snapshot.participated) { retryAt = System.currentTimeMillis() + LOVE_CHICKEN_RETRY_MS; return false }
        }
        var adapter = LoveChickenTaskAdapter(this, snapshot, execution)
        var homeHintRechecked = false
        var refreshHomeBeforeTasks = false
        while (!ApplicationHookConstants.isOffline() && AccountSessionCoordinator.isCurrentSession(owner, epoch)) {
            currentCoroutineContext().ensureActive()
            if (refreshHomeBeforeTasks) {
                response = queryLoveChickenActivity()
                if (response == null) { schedule = false; break }
            }
            refreshHomeBeforeTasks = false
            val previous = snapshot ?: break
            val current = parseLoveChickenActivity(response ?: break)
            snapshot = current
            if (current.activityId != previous.activityId || current.rankRoundId != previous.rankRoundId ||
                current.projectId != previous.projectId || current.endTimeMs != previous.endTimeMs
            ) {
                if (current.activityId != previous.activityId || current.endTimeMs != previous.endTimeMs)
                    execution = LoveChickenExecution(current)
                adapter = LoveChickenTaskAdapter(this, current, execution ?: break)
                homeHintRechecked = false
            }
            val state = execution ?: break
            val confirmedBefore = adapter.confirmedCount
            val taskResult = if (state.isBlocked("listCompetitionTask"))
                TaskFlowRunResult(completed = false, progressed = false, stopped = true, rounds = 0)
                else TaskFlowEngine(adapter, roundSleepMs = 255L, executionState = state.flowState).run()
            val taskProgressed = taskResult.progressChanged || adapter.confirmedCount > confirmedBefore
            progressed = progressed || taskProgressed
            if (taskResult.interrupted || ApplicationHookConstants.isOffline()) break
            val rewards = receiveLoveChickenRewards(current, state)
            if (ApplicationHookConstants.isOffline()) break
            val giftConfirmed = if (rewards == null || rewards.rewards.isNotEmpty())
                receiveLoveChickenAnnGift(response ?: break, state) else false
            progressed = progressed || giftConfirmed
            if (ApplicationHookConstants.isOffline()) break
            val page = adapter.lastPage
            response = queryLoveChickenActivity()
            if (response == null) { schedule = false; break }
            val refreshed = parseLoveChickenActivity(response)
            if (refreshed.activityId != current.activityId || refreshed.rankRoundId != current.rankRoundId ||
                refreshed.projectId != current.projectId || refreshed.endTimeMs != current.endTimeMs
            ) continue
            snapshot = refreshed
            val giftAttempted = "getAnnGift" in state.attemptedRewards
            val giftConfirmedAfterRefresh = if (rewards == null || rewards.rewards.isNotEmpty())
                receiveLoveChickenAnnGift(response, state) else false
            progressed = progressed || giftConfirmedAfterRefresh
            if (ApplicationHookConstants.isOffline()) break
            if (!giftAttempted && "getAnnGift" in state.attemptedRewards) {
                refreshHomeBeforeTasks = true
                continue
            }
            val gift = response.optJSONObject("ann9thCompetitionInfo")
            val giftUnclaimed = (rewards == null || rewards.rewards.isNotEmpty()) && gift != null &&
                gift.optBoolean("ann9thActivitySwitch") && gift.optString("ann9thCakeStatus") == "PENDING"
            val now = System.currentTimeMillis()
            if (rewards == null || page == null || !taskResult.completed || state.hasPending || giftUnclaimed ||
                rewards.rewards.any { it.status == "unclaimed" }
            ) {
                adapter.logUnresolved("任务或奖励查询未完成、仍可领取或待确认")
                if (state.retryAt == null) state.retryAt = now + LOVE_CHICKEN_RETRY_MS
                break
            }
            if (now < current.endTimeMs &&
                response.optJSONObject("competitionTaskInfo")?.optBoolean("hasNewAwardReceive") == true) {
                if (!homeHintRechecked || taskProgressed) {
                    homeHintRechecked = true
                    continue
                }
                adapter.logUnresolved("首页提示新奖励，但补查任务列表无新增领取或阶段进展")
                state.retryAt = minOf(state.retryAt ?: Long.MAX_VALUE, now + LOVE_CHICKEN_RETRY_MS)
                break
            }
            homeHintRechecked = false
            if (now >= snapshot.endTimeMs) {
                finished = now >= snapshot.finalSettlementMs
                if (finished) {
                    Log.farm("爱心鸡结号活动结束，最终任务及奖励已处理")
                }
                break
            }
            if (!snapshot.participated || state.stopped || state.isBlocked("donation")) break
            var rank: LoveChickenRankSnapshot? = null
            if (!snapshot.matching && now < snapshot.settleAtMs && snapshot.rankRoundId.isNotBlank() &&
                !state.isBlocked("queryAllMemberRankInfo")) {
                try {
                    rank = queryLoveChickenFullRank(snapshot)
                } catch (e: LoveChickenRpcException) {
                    state.recordFailure("queryAllMemberRankInfo", e.result)
                }
            }
            val limit = maxDailyDonationCompetitionCount?.value ?: -1
            val remaining = if (limit < 0) Int.MAX_VALUE else (limit - Status.getDailyDonationTotal(owner)).coerceAtLeast(0)
            val plan = selectLoveChickenDonation(snapshot, page, rank, rewards, remaining, state)
            Log.farm("爱心鸡结号[${plan.reason}]目标总量${plan.targetTotal}，本次${plan.amount}，预计贡献${plan.expectedContribution ?: "未知"}")
            if (plan.rankTargetMet && loveChickenWatchMode?.value != 1) {
                state.data.put("watchRound", snapshot.rankRoundId)
                state.save()
            }
            if (plan.amount <= 0) break
            currentCoroutineContext().ensureActive()
            if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
            val resource = replenishEggsForDonation(plan.amount, forLoveChicken = true)
            if (resource.failure != null) {
                state.recordFailure("donation", resource.failure, stopTarget = true)
                break
            }
            currentCoroutineContext().ensureActive()
            if (!AccountSessionCoordinator.isCurrentSession(owner, epoch) || ApplicationHookConstants.isOffline()) break
            if (resource.progressed) {
                // 新库存也先让高优先级玩法处理；随后刷新贡献与共享额度再选量。
                runFarmPriorityDonations(owner)
                refreshHomeBeforeTasks = true
                continue
            }
            // 补蛋可能跨过结算或直接贡献截止，重新按各收益的有效期选量。
            val allowed = selectLoveChickenDonation(snapshot, page, rank, rewards, remaining, state).amount
            val actual = minOf(allowed, resource.availableEggs)
            if (actual <= 0) { Log.farm("爱心鸡结号待资源产出，当前蛋数${resource.availableEggs}"); break }
            val donation = performDonationDetailed(activityId = null, activityName = "爱心鸡结号", count = actual,
                competitionProjectId = snapshot.projectId)
            if (!donation.success) {
                state.recordFailure("donation", TaskFlowActionResult.failure(
                    donation.classification ?: TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    rpc = "donation", raw = donation.raw, code = donation.code, message = donation.message,
                ), stopTarget = true)
                break
            }
            if (!AccountSessionCoordinator.isCurrentSession(owner, epoch)) throw CancellationException("账号会话已变化")
            Status.updateDailyDonationTotal(owner, donation.actualAmount, incremental = true)
            progressed = true
            var refreshFailure: TaskFlowActionResult? = null
            syncAnimalStatus(ownerFarmId) { refreshFailure = it }
            refreshFailure?.let { state.recordFailure("donation", it, stopTarget = true) }
            if (refreshFailure != null) break
            refreshHomeBeforeTasks = true
        }
    } catch (e: CancellationException) {
        cancelled = true
        throw e
    } catch (e: LoveChickenRpcException) {
        if (execution != null) execution.recordFailure(e.result.rpc, e.result)
        else if (e.result.failureType in setOf(TaskRpcFailureType.BUSINESS_LIMIT, TaskRpcFailureType.NON_RETRYABLE_INVALID,
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE, TaskRpcFailureType.TERMINAL_DONE)) schedule = false
        else retryAt = System.currentTimeMillis() + LOVE_CHICKEN_RETRY_MS
    } catch (t: Throwable) {
        retryAt = System.currentTimeMillis() + LOVE_CHICKEN_RETRY_MS
        Log.printStackTrace(TAG, "爱心鸡结号执行状态未确认，保留查询", t)
    } finally {
        if (!cancelled && currentCoroutineContext().isActive &&
            AccountSessionCoordinator.isCurrentSession(owner, epoch) && !ApplicationHookConstants.isOffline()) {
            if (finished) {
                snapshot?.let {
                    store?.put("$LOVE_CHICKEN_FINISHED_PREFIX${it.activityId}::${it.endTimeMs}", true)
                    store?.remove("antFarmLoveChickenGathering::pending::${it.activityId}::${it.endTimeMs}")
                }
            }
            if (finished || !schedule) cancelLoveChickenSchedule()
            else if (loveChickenGathering?.value == true) scheduleLoveChickenTask(snapshot, execution, retryAt)
        }
    }
    return progressed
}

internal suspend fun AntFarm.runLoveChickenGatheringPersistentTask(payload: JSONObject) {
    if (loveChickenGathering?.value != true || !check() || ApplicationHookConstants.isOffline()) return
    if (payload.optString("farm_id") != ownerFarmId) return
    val response = try {
        queryLoveChickenActivity()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LoveChickenRpcException) {
        currentCoroutineContext().ensureActive()
        if (e.result.failureType == TaskRpcFailureType.RETRYABLE_RPC || e.result.failureType == TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW)
            scheduleLoveChickenTask(null, retryAt = System.currentTimeMillis() + LOVE_CHICKEN_RETRY_MS)
        return
    } catch (t: Throwable) {
        currentCoroutineContext().ensureActive()
        Log.printStackTrace(TAG, "爱心鸡结号持久查询未确认，保留回查", t)
        scheduleLoveChickenTask(null, retryAt = System.currentTimeMillis() + LOVE_CHICKEN_RETRY_MS)
        return
    }
    if (response == null) { cancelLoveChickenSchedule(); return }
    val snapshot = parseLoveChickenActivity(response)
    if (payload.optString("activity_id") != snapshot.activityId || payload.optString("rank_round_id") != snapshot.rankRoundId ||
        payload.optLong("end_time_ms") != snapshot.endTimeMs
    ) {
        Log.farm("爱心鸡结号旧载荷已失效，替换为当前轮次查询计划")
        scheduleLoveChickenTask(snapshot, retryAt = System.currentTimeMillis())
        return
    }
    runFarmPriorityDonations()
    if (!ApplicationHookConstants.isOffline()) runLoveChickenGatheringWorkflow()
}

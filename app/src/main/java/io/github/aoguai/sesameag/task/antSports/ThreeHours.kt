package io.github.aoguai.sesameag.task.antSports

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
import io.github.aoguai.sesameag.hook.internal.ThreeHoursSession
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.TaskExecutionOutcome
import io.github.aoguai.sesameag.task.TaskExecutionReport
import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.UserDataStoreManager
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

internal object ThreeHours {
    private const val STORE_KEY = "threeHoursActions"
    private const val CHILD_ID = "threeHours"
    private val running = AtomicBoolean()
    private val scheduledCredentials = ConcurrentHashMap<AccountSessionIdentity, Long>()

    private fun enabled(sports: AntSports): Boolean = sports.isEnable() &&
        (sports.threeHoursDonate.value == true || sports.threeHoursVote.value == true ||
            sports.threeHoursWish.value == true || sports.threeHoursSteps.value == true)

    fun onSessionChanged() {
        val session = AccountSessionCoordinator.currentSession() ?: return
        if (!session.workflowAllowed || running.get()) return
        val sports = Model.getModel(AntSports::class.java) ?: return
        if (!runCatching { enabled(sports) }.getOrDefault(false)) return
        val identity = AccountSessionIdentity(session.userId, session.sessionEpoch)
        val credential = ThreeHoursSession.load(identity) ?: return
        val observedAt = credential.data.optLong("observedAt")
        synchronized(scheduledCredentials) {
            if (scheduledCredentials[identity] == observedAt) return
            if (resumeLater(sports, "收到官方页面凭据，等待验证", System.currentTimeMillis() + 5_000L)) {
                scheduledCredentials[identity] = observedAt
            }
        }
    }

    fun resumeLater(sports: AntSports, reason: String, triggerAtMs: Long = System.currentTimeMillis() + 300_000L): Boolean {
        val registered = sports.registerPersistentChildTask(CHILD_ID, CHILD_ID, triggerAtMs)
        if (registered) Log.record("ThreeHours", "$reason；已登记公益持久续接")
        else Log.error("ThreeHours", "$reason；公益续接未登记，保留状态等待下次运动任务")
        return registered
    }

    fun run(sports: AntSports): TaskExecutionOutcome {
        if (!enabled(sports)) {
            sports.cancelPersistentChildTask(CHILD_ID)
            return TaskExecutionOutcome.SKIPPED
        }
        var outcome = TaskExecutionOutcome.DEFERRED
        val acquired = running.compareAndSet(false, true)
        try {
            if (!acquired) {
                resumeLater(sports, "公益流程正在执行，等待后续回查")
                return outcome
            }
            val session = AccountSessionCoordinator.currentSession() ?: throw CancellationException("公益账号会话不可用")
            val identity = AccountSessionIdentity(session.userId, session.sessionEpoch)
            val credential = ThreeHoursSession.load(identity)
            if (credential == null) {
                sports.cancelPersistentChildTask(CHILD_ID)
                ThreeHoursSession.prompt(identity)
                return outcome
            }
            scheduledCredentials[identity] = credential.data.optLong("observedAt")
            val rpc = ThreeHoursRpcCall(credential)
            try {
                val account = rpc.account()
                if (!ThreeHoursSession.verified(credential)) {
                    resumeLater(sports, "公益凭据已更新，等待重新验证", System.currentTimeMillis() + 5_000L)
                    return outcome
                }
                Log.record("ThreeHours", "公益凭据已通过账户只读验证")
                outcome = Workflow(sports, credential, rpc, account).run()
                return outcome
            } catch (e: ThreeHoursRpcCall.Expired) {
                if (ThreeHoursSession.load(identity) != null) {
                    resumeLater(sports, "公益旧会话失效，已有新观察凭据等待验证", System.currentTimeMillis() + 5_000L)
                } else {
                    sports.cancelPersistentChildTask(CHILD_ID)
                    ThreeHoursSession.prompt(identity)
                }
                Log.error("ThreeHours", "公益会话失效 path=${e.endpoint} code=${e.code}")
                return outcome
            }
        } catch (e: CancellationException) {
            outcome = TaskExecutionOutcome.CANCELLED
            throw e
        } catch (e: InterruptedException) {
            outcome = TaskExecutionOutcome.CANCELLED
            Thread.currentThread().interrupt()
            throw e
        } catch (t: Throwable) {
            outcome = TaskExecutionOutcome.FAILED
            Log.error("ThreeHours", "公益查询或确认失败: ${t.message}")
            resumeLater(sports, "公益查询或确认失败，等待重试")
            return outcome
        } finally {
            TaskExecutionReport.current()?.recordFlow(TaskFlowExecutionState(), "AntSports", "人人3小时",
                TaskFlowRunResult(completed = outcome == TaskExecutionOutcome.SUCCESS, progressed = false,
                    stopped = outcome != TaskExecutionOutcome.SUCCESS, rounds = 1,
                    failureCount = if (outcome == TaskExecutionOutcome.FAILED) 1 else 0,
                    deferredCount = if (outcome == TaskExecutionOutcome.DEFERRED) 1 else 0,
                    interrupted = outcome == TaskExecutionOutcome.CANCELLED))
            if (acquired) {
                running.set(false)
                runCatching { onSessionChanged() }.onFailure { Log.error("ThreeHours", "公益凭据续接登记失败: ${it.javaClass.simpleName}") }
            }
        }
    }

    private class Workflow(
        private val sports: AntSports,
        private val credential: ThreeHoursSession.Credential,
        private val rpc: ThreeHoursRpcCall,
        private var account: JSONObject,
    ) {
        private val identity = credential.identity
        private val store = requireNotNull(UserDataStoreManager.getInstance(identity.userId))
        private val state = AccountSessionCoordinator.withCurrentSession(identity) {
            store.get(STORE_KEY, String::class.java)?.let(::JSONObject) ?: JSONObject()
        }
        private val pending = state.optJSONObject("pending") ?: JSONObject().also { state.put("pending", it) }
        private val completed = state.optJSONObject("completed") ?: JSONObject().also { state.put("completed", it) }
        private var retry = false
        private var waiting = false

        private fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())

        private fun save() = AccountSessionCoordinator.withCurrentSession(identity) {
            store.put(STORE_KEY, state.toString())
        }

        private fun allowed(kind: String): Boolean = sports.isEnable() && when (kind) {
            "donate" -> sports.threeHoursDonate.value == true
            "vote" -> sports.threeHoursVote.value == true
            "wish" -> sports.threeHoursWish.value == true
            "steps" -> sports.threeHoursSteps.value == true
            else -> false
        }

        private fun pendingKeys(kind: String): List<String> = pending.keys().asSequence()
            .filter { pending.getJSONObject(it).optString("kind") == kind }.toList()

        fun run(): TaskExecutionOutcome {
            val initialHours = hours(account)
            var failed = false
            for (kind in listOf("donate", "vote", "wish", "steps")) {
                val keys = if (kind == "donate") emptyList() else pendingKeys(kind)
                if (!allowed(kind) && keys.isEmpty()) continue
                ThreeHoursSession.ensureCurrent(credential)
                try {
                    for (key in keys) {
                        val record = pending.getJSONObject(key)
                        if (record.optBoolean("confirmed")) confirm(key, record)
                    }
                    when (kind) {
                        "donate" -> donate()
                        "vote" -> vote()
                        "wish" -> wish()
                        "steps" -> steps()
                    }
                } catch (e: ThreeHoursRpcCall.Expired) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    throw e
                } catch (t: Throwable) {
                    failed = true
                    if (kind != "donate") retry = true
                    logError("$kind 执行失败：${t.message}")
                }
            }
            account = rpc.account()
            logInfo("公益账户：${hours(account)}小时，爱能待领取${account.optLong("unCollectLoveBeanNum")}；本轮公益时变化" +
                String.format(Locale.ROOT, "%.2f", hours(account) - initialHours))
            if (retry) resumeLater(sports, "公益查询或动作未完成，等待回查")
            else sports.cancelPersistentChildTask(CHILD_ID)
            return when {
                failed -> TaskExecutionOutcome.FAILED
                retry || waiting -> TaskExecutionOutcome.DEFERRED
                else -> TaskExecutionOutcome.SUCCESS
            }
        }

        private fun donate() {
            while (allowed("donate")) {
                val configuredCount = sports.threeHoursDonateCount.value ?: 1
                val count = Status.getIntFlagToday(StatusFlags.FLAG_THREE_HOURS_DONATE_COUNT) ?: 0
                if (configuredCount == -1) {
                    if (Status.hasFlagToday(StatusFlags.FLAG_THREE_HOURS_DONATE_NO_REWARD)) {
                        logInfo("今日捐赠已无公益时奖励，停止自动捐赠")
                        return
                    }
                } else if (count >= configuredCount) return
                if (!credential.data.has("csrf")) {
                    waiting = true
                    ThreeHoursSession.prompt(identity, donationParametersMissing = true)
                    return
                }
                val tier = sports.threeHoursDonateTier.value ?: 0
                require(tier in 0..2) { "健康能量捐赠档位无效: $tier" }
                val labels = rpc.projects()
                var selected: JSONObject? = null
                for (i in 0 until labels.length()) {
                    val projects = labels.getJSONObject(i).optJSONArray("projectList") ?: continue
                    for (j in 0 until projects.length()) {
                        val project = projects.getJSONObject(j)
                        val projectId = project.getLong("id")
                        val projectCount = Status.getIntFlagToday("${StatusFlags.FLAG_THREE_HOURS_DONATE_COUNT}::$projectId") ?: 0
                        if (project.optString("donateTimesCycle") == "DAY" && !project.isNull("userMaxCanDonateTimes") &&
                            projectCount >= project.getLong("userMaxCanDonateTimes")) continue
                        val rawTiers = project.optJSONObject("extResources")?.opt("donatePopChooseList") ?: continue
                        val tiers = if (rawTiers is JSONArray) rawTiers else JSONArray(rawTiers.toString())
                        if (tier >= tiers.length()) continue
                        val copies = tiers.getLong(tier)
                        val unitCost = project.getLong("costPoints")
                        if (copies <= 0L || unitCost <= 0L) continue
                        val cost = Math.multiplyExact(copies, unitCost)
                        if (project.getLong("remainPoints") < cost) continue
                        if (!project.isNull("userCanDonateTimes") && project.getLong("userCanDonateTimes") <= 0L) continue
                        if (!project.isNull("userMaxCanDonateTimes") && project.getLong("userMaxCanDonateTimes") <= 0L) continue
                        if (!project.isNull("donateMaxPoints") && cost > project.getLong("donateMaxPoints")) continue
                        if (project.getLong("donationLimit") - project.getLong("donationProgress") < copies) continue
                        selected = JSONObject().put("projectId", projectId).put("copies", copies)
                        break
                    }
                    if (selected != null) break
                }
                val project = selected ?: run {
                    logInfo("健康能量捐赠：当前项目不满足所选第${tier + 1}档、余额或额度条件")
                    return
                }
                val projectId = project.getLong("projectId")
                val submittedDay = day()
                val receipt = rpc.donate(projectId, project.getLong("copies")).getJSONObject("data")
                if (submittedDay == day()) AccountSessionCoordinator.withCurrentSession(identity) {
                    val projectCountKey = "${StatusFlags.FLAG_THREE_HOURS_DONATE_COUNT}::$projectId"
                    Status.setIntFlagToday(projectCountKey, (Status.getIntFlagToday(projectCountKey) ?: 0) + 1)
                    Status.setIntFlagToday(StatusFlags.FLAG_THREE_HOURS_DONATE_COUNT, count + 1)
                }
                logInfo("健康能量捐赠[$projectId]成功：${project.getLong("copies")}份")
                val rewards = receipt.optJSONArray("rewards")
                var rewardGranted: Boolean? = null
                if (rewards != null) {
                    var complete = true
                    var granted = false
                    for (i in 0 until rewards.length()) {
                        val reward = rewards.optJSONObject(i)
                        val code = reward?.opt("rewardCode") as? String
                        val issued = reward?.opt("granted") as? Boolean
                        if (code.isNullOrBlank() || issued == null) complete = false
                        if (code == "WELFARE_TIME" && issued == true) {
                            val quantity = reward?.optDouble("quantity") ?: Double.NaN
                            if (!quantity.isFinite()) complete = false
                            else if (quantity > 0.0) granted = true
                        }
                    }
                    rewardGranted = if (granted) true else if (complete) false else null
                }
                if (rewardGranted == false && submittedDay == day()) AccountSessionCoordinator.withCurrentSession(identity) {
                    Status.setFlagToday(StatusFlags.FLAG_THREE_HOURS_DONATE_NO_REWARD, expectedUserId = identity.userId)
                }
                logReward("donate", receipt)
                rpc.projects()
                account = rpc.account()
                if (rewardGranted == null) {
                    waiting = true
                    logError("捐赠[$projectId]已计入成功次数，响应未明确奖励结果，结束本轮捐赠")
                    return
                }
                if (!rewardGranted && configuredCount == -1) {
                    logInfo("本次捐赠未获得公益时奖励，停止当天自动捐赠")
                    return
                }
            }
        }

        private fun vote() {
            val vote = rpc.voteToday()
            val voteId = vote.getLong("voteId")
            val option = vote.optInt("userOption")
            for (key in pendingKeys("vote")) {
                val record = pending.getJSONObject(key)
                if (record.optJSONObject("receipt") != null || record.optLong("voteId") == voteId && option in 1..2) {
                    confirm(key, record)
                }
            }
            val key = "vote:$voteId"
            if (option in 1..2) {
                completed.put(key, true)
                save()
                AccountSessionCoordinator.withCurrentSession(identity) {
                    Status.setFlagToday(StatusFlags.FLAG_THREE_HOURS_VOTE_DONE, expectedUserId = identity.userId)
                }
                logInfo("今日热议[$voteId]已投第${option}项，无需重复投票")
                return
            }
            if (pending.has(key)) { retry = true; return }
            if (!allowed("vote") || completed.optBoolean(key) || vote.optInt("status") != 1) return
            val strategy = sports.threeHoursVoteStrategy.value ?: 0
            val red = vote.optLong("redCount", -1L)
            val blue = vote.optLong("blueCount", -1L)
            val chosen = when (strategy) {
                0 -> Random.nextInt(1, 3)
                1 -> if (red >= 0 && blue >= 0 && red != blue) { if (red > blue) 1 else 2 } else Random.nextInt(1, 3)
                2 -> 1
                3 -> 2
                else -> error("投票策略无效: $strategy")
            }
            logInfo("投票[$voteId]选择第${chosen}项，策略=" +
                if (strategy == 1 && (red < 0 || blue < 0 || red == blue)) "多数无有效差异，随机" else strategy.toString())
            val record = JSONObject().put("kind", "vote").put("voteId", voteId).put("option", chosen)
            submit(key, record)
            rpc.voteToday()
            confirm(key, record)
        }

        private fun wish() {
            val tasks = rpc.tasks()
            var wish: JSONObject? = null
            for (listKey in listOf("kingkongTaskList", "secondKingkongTaskList", "thirdKingkongTaskList", "opportunityTaskList")) {
                val list = tasks.optJSONArray(listKey) ?: continue
                for (i in 0 until list.length()) {
                    val task = list.getJSONObject(i)
                    if (task.optString("taskCode") == "like_ttznl_wish" && task.optJSONObject("feature")?.isNull("wishId") == false) {
                        wish = task
                        break
                    }
                }
                if (wish != null) break
            }
            for (key in pendingKeys("wish")) {
                val record = pending.getJSONObject(key)
                val sameWish = wish?.optJSONObject("feature")?.optString("wishId") == record.optString("wishId")
                if (record.optJSONObject("receipt")?.optBoolean("todayLiked") == true ||
                    sameWish && record.optString("day") == day() && wish?.optString("taskStatus") == "COMPLETED") {
                    confirm(key, record)
                }
            }
            val task = wish ?: return
            val feature = task.getJSONObject("feature")
            val wishId = feature.getString("wishId")
            val daily = feature.optString("wishType") == "DAILY"
            val period = if (daily) day() else feature.optString("wishType")
            val key = "wish:$period:$wishId"
            val done = if (daily) Status.hasFlagToday(StatusFlags.FLAG_THREE_HOURS_WISH_DONE) else completed.optBoolean(key)
            if (task.optString("taskStatus") == "COMPLETED" || done) {
                if (!daily) {
                    completed.put(key, true)
                    save()
                }
                AccountSessionCoordinator.withCurrentSession(identity) {
                    Status.setFlagToday(StatusFlags.FLAG_THREE_HOURS_WISH_DONE, expectedUserId = identity.userId)
                }
                logInfo("心愿[$wishId]本周期已确认助力，无需重复提交")
                return
            }
            if (pending.has(key)) { retry = true; return }
            if (!allowed("wish") || task.optString("taskStatus") != "RUNNING" || feature.optString("status") != "RUNNING") return
            val record = JSONObject().put("kind", "wish").put("wishId", wishId).put("wishType", feature.optString("wishType"))
            val receipt = submit(key, record)
            if (receipt.optBoolean("todayLiked")) confirm(key, record) else retry = true
        }

        private fun steps() {
            var steps = rpc.steps()
            check(steps.getString("alipayUserId") == identity.userId) { "公益捐步返回的账号与当前账号不一致" }
            for (key in pendingKeys("steps")) {
                val record = pending.getJSONObject(key)
                if (record.optString("day") == day() &&
                    (steps.optBoolean("allDonated") || steps.getLong("donatedSteps") >= record.getLong("todaySteps"))) {
                    confirm(key, record)
                }
            }
            val key = "steps:${day()}"
            if (steps.optBoolean("allDonated") || steps.optBoolean("reachLimit") ||
                Status.hasFlagToday(StatusFlags.FLAG_THREE_HOURS_STEPS_DONE)) {
                AccountSessionCoordinator.withCurrentSession(identity) {
                    Status.setFlagToday(StatusFlags.FLAG_THREE_HOURS_STEPS_DONE, expectedUserId = identity.userId)
                }
                logInfo("今日捐步已完成或达到上限：已捐${steps.getLong("donatedSteps")}步，剩余${steps.getLong("remainSteps")}步")
                return
            }
            if (pending.has(key)) { retry = true; return }
            if (!allowed("steps") || steps.getLong("remainSteps") <= 0) return
            val today = steps.getLong("todaySteps")
            if (today <= steps.getLong("donatedSteps")) return
            val record = JSONObject().put("kind", "steps").put("todaySteps", today)
                .put("beforeDonated", steps.getLong("donatedSteps"))
            submit(key, record)
            steps = rpc.steps()
            check(steps.getString("alipayUserId") == identity.userId) { "公益捐步返回的账号与当前账号不一致" }
            if (steps.optBoolean("allDonated") || steps.getLong("donatedSteps") >= today) confirm(key, record) else retry = true
        }

        private fun submit(key: String, record: JSONObject): JSONObject {
            ThreeHoursSession.ensureCurrent(credential)
            record.put("day", day()).put("submittedAt", System.currentTimeMillis())
            pending.put(key, record)
            save()
            resumeLater(sports, "$key 提交后回查")
            try {
                val result = when (record.getString("kind")) {
                    "vote" -> rpc.vote(record.getLong("voteId"), record.getInt("option"))
                    "wish" -> rpc.like(record.getString("wishId"))
                    "steps" -> rpc.donateSteps(record.getLong("todaySteps"))
                    else -> error("未知公益动作 $key")
                }
                val receipt = result.getJSONObject("data")
                if (record.getString("kind") == "vote") check(
                    receipt.optLong("voteId") == record.getLong("voteId") && receipt.optInt("userOption") in 1..2
                ) { "投票响应缺少本人选择 raw=${rpc.safeResponse(result)}" }
                record.put("receipt", receipt)
                save()
                logInfo("${record.getString("kind")}[$key]提交成功")
                logReward(record.getString("kind"), receipt)
                return receipt
            } catch (e: ThreeHoursRpcCall.Rejected) {
                pending.remove(key)
                save()
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (t: Throwable) {
                record.put("error", t.message.orEmpty().take(2048))
                save()
                throw t
            }
        }

        private fun confirm(key: String, record: JSONObject) {
            record.put("confirmed", true)
            save()
            if (record.optString("day") == day()) AccountSessionCoordinator.withCurrentSession(identity) {
                when (record.optString("kind")) {
                    "vote" -> Status.setFlagToday(StatusFlags.FLAG_THREE_HOURS_VOTE_DONE, expectedUserId = identity.userId)
                    "wish" -> Status.setFlagToday(StatusFlags.FLAG_THREE_HOURS_WISH_DONE, expectedUserId = identity.userId)
                    "steps" -> Status.setFlagToday(StatusFlags.FLAG_THREE_HOURS_STEPS_DONE, expectedUserId = identity.userId)
                }
            }
            when (record.getString("kind")) {
                "vote" -> completed.put(key, true)
                "wish" -> if (record.getString("wishType") != "DAILY") completed.put(key, true)
            }
            pending.remove(key)
            save()
            logInfo("已确认 ${record.optString("kind")}[$key]")
        }

        private fun logReward(kind: String, receipt: JSONObject) {
            if (kind == "wish") receipt.optJSONObject("reward")?.let { reward ->
                logInfo("心愿助力：本人今日已助力=${receipt.optBoolean("todayLiked")}，奖励状态=${reward.optString("state")}，" +
                    "本次公益时=${reward.optString("charityHours", "未知")}，本次爱能=${reward.optString("loveEnergy", "未知")}")
                if (reward.optString("state") == "ALREADY_GRANTED") logInfo("心愿奖励已发过，本次没有新增奖励")
                if (reward.optBoolean("needCollectLoveEnergy")) logInfo("心愿助力附送爱能待领取")
            }
            val award = receipt.optJSONObject("sendAwardResponse")
            if (award?.optString("sendCharityTimeAwardResult") == "SENT") {
                logInfo("$kind 公益时已发放：${award.optDouble("charityTimeHour")}")
            }
            if (award?.optBoolean("needCollectLoveEnergy") == true) {
                logInfo("$kind 附送爱能${award.optLong("loveBeanNum")}待领取")
            }
            val rewards = receipt.optJSONArray("rewards") ?: return
            for (i in 0 until rewards.length()) {
                val reward = rewards.optJSONObject(i) ?: continue
                if (reward.optString("rewardCode") == "WELFARE_TIME" && reward.optBoolean("granted") &&
                    reward.optDouble("quantity") > 0.0) {
                    logInfo("健康能量捐赠公益时已发放，服务端数量${reward.optString("quantity")}")
                }
            }
        }

        private fun hours(value: JSONObject): Double = value.getJSONObject("publicWelfareLevel").getDouble("currentCharityTime")
        private fun logInfo(message: String) = Log.sports("人人3小时[$message]")
        private fun logError(message: String) = Log.error("ThreeHours", message)
    }
}

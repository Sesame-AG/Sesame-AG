package io.github.aoguai.sesameag.task.youthPrivilege

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.antForest.AntForestRpcCall
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.Log
import org.json.JSONArray
import org.json.JSONObject

class YouthPrivilege : ModelTask() {
    companion object {
        const val TAG = "YouthPrivilege"
        const val SUCCESS = "SUCCESS"
        const val CHECK_IN_ACTION = "CHECK_IN"
        const val CHECKED_IN_ACTION = "DO_TASK"
        const val STATUS_FINISHED = "FINISHED"
        const val STATUS_RECEIVED = "RECEIVED"
        const val STATUS_COMPLETE = "COMPLETE"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_TO_APPLY = "TO_APPLY"
        const val ACTION_SIGNUP = "SIGNUP"
        const val ACTION_DO_NOTHING = "DO_NOTHING"
        const val ACTION_COMPLETE = "COMPLETE"
        const val TASK_TYPE_BROWSER = "BROWSER"

        private val LEGACY_FOREST_ROUTES = listOf(
            ForestRewardRoute("DNHZ_SL_college", "DNHZ_SL_college", "DAXUESHENG_SJK", "双击卡"),
            ForestRewardRoute("DXS_BHZ", "202212TJBRW", "NENGLIANGZHAO_20230807", "保护罩"),
            ForestRewardRoute("DXS_JSQ", "202212TJBRW", "JIASUQI_20230808", "加速器"),
        )

        internal fun claimForestPropsFromForest(): Boolean =
            Model.getModel(YouthPrivilege::class.java)?.claimForestPropsForForest() ?: false
    }

    private data class ForestRewardRoute(
        val firstTaskType: String,
        val source: String,
        val awardTaskType: String,
        val displayName: String,
    )

    private var checkIn: BooleanModelField? = null
    private var forestProps: BooleanModelField? = null
    private var youthTasks: BooleanModelField? = null
    private var multiDraws: BooleanModelField? = null

    override fun getName(): String = "青春特权"

    override fun getGroup(): ModelGroup = ModelGroup.MEMBER

    override fun getIcon(): String = "AntMember.png"

    override fun getFields(): ModelFields =
        ModelFields().apply {
            addField(
                BooleanModelField("youthPrivilegeCheckIn", "青春特权 | 签到青春豆", false)
                    .withDesc("依据服务端签到状态执行青春豆签到并回查确认。")
                    .also { checkIn = it },
            )
            addField(
                BooleanModelField("youthPrivilegeForestProps", "青春特权 | 森林道具", false)
                    .withDesc("领取青春特权中已验证闭环的双击卡、保护罩和加速器。")
                    .also { forestProps = it },
            )
            addField(
                BooleanModelField("youthPrivilegeTasks", "青春特权 | 青春任务", false)
                    .withDesc("按服务端下发状态完成青春特权任务并在每步后回查。")
                    .also { youthTasks = it },
            )
            addField(
                BooleanModelField("youthPrivilegeMultiDraws", "青春特权 | 青春豆十连抽", false)
                    .withDesc("消耗服务端显示的青春豆成本执行十连抽，受余额和每周额度限制。")
                    .also { multiDraws = it },
            )
        }

    override suspend fun runSuspend() {
        try {
            Log.youthPrivilege("青春特权执行开始")
            if (checkIn?.value == true) {
                handleCheckIn()
            }
            if (forestProps?.value == true) {
                claimForestProps()
            }
            if (youthTasks?.value == true) {
                TaskFlowEngine(YouthTaskFlowAdapter(), roundSleepMs = 800L).run()
                claimTrialPrize()
                TaskFlowEngine(MonthlyPrivilegeAdapter(), roundSleepMs = 0L).run()
            }
            if (multiDraws?.value == true) {
                handleMultiDraws()
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "青春特权执行异常", t)
        } finally {
            Log.youthPrivilege("青春特权执行结束")
        }
    }

    private fun handleMultiDraws() {
        val store = io.github.aoguai.sesameag.util.UserDataStoreManager.getCurrentInstance() ?: return
        val pendingKey = "youthMultiDraws:2026-09-30:pending"
        if (io.github.aoguai.sesameag.hook.ApplicationHookConstants.isOffline()) return
        val index = JSONObject(YouthPrivilegeRpcCall.queryLotteryIndex())
        if (!isYouthSuccess(index)) {
            Log.error(TAG, "青春豆十连抽首页查询失败:$index")
            return
        }
        val status = index.optString("multiDrawsLotteryStatus")
        if (status == "LIMIT") {
            store.removePersistentFlag(pendingKey)
            Log.youthPrivilege("青春豆十连抽额度已用完，当前余额#${index.optString("totalAmount")}")
            return
        }
        if (status != "DRAW") {
            Log.youthPrivilege("青春豆十连抽当前不可执行#status=$status raw=$index")
            return
        }
        if (store.hasPersistentFlag(pendingKey)) {
            Log.youthPrivilege("青春豆十连抽已有请求待确认，仅回查额度，暂不再次消费")
            return
        }
        val cost = index.optString("multiDrawsCost").toBigDecimalOrNull()
        val balance = index.optString("totalAmount").toBigDecimalOrNull()
        if (cost == null || balance == null || cost.signum() <= 0 || balance.signum() < 0) {
            Log.error(TAG, "青春豆十连抽缺少有效成本或余额:$index")
            return
        }
        if (balance < cost) {
            Log.youthPrivilege("青春豆十连抽余额不足#余额=$balance 成本=$cost")
            return
        }
        if (io.github.aoguai.sesameag.hook.ApplicationHookConstants.isOffline()) return
        // 付费动作没有抓到幂等键；请求结果不确定时保留待确认，避免再次扣除青春豆。
        store.setPersistentFlag(pendingKey, -1L)
        val result = try {
            JSONObject(YouthPrivilegeRpcCall.multiDrawsLottery())
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "青春豆十连抽响应异常，保留回查", t)
            null
        }
        if (result != null && isYouthSuccess(result)) {
            val prizes = result.optJSONArray("lotteryPrizeInfoList")
            if (prizes == null) Log.error(TAG, "青春豆十连抽响应缺少实际奖励列表:$result")
            for (indexInPrizes in 0 until (prizes?.length() ?: 0)) {
                val prize = prizes?.optJSONObject(indexInPrizes) ?: continue
                Log.youthPrivilege("青春豆十连抽🎁[${prize.optString("title")}] ${prize.optString("subTitle")}")
            }
        } else if (result != null) {
            Log.error(TAG, "青春豆十连抽失败，不立即重试:$result")
        }
        val confirmed = JSONObject(YouthPrivilegeRpcCall.queryLotteryIndex())
        if (!isYouthSuccess(confirmed)) {
            Log.error(TAG, "青春豆十连抽回查失败，保留待确认:$confirmed")
            return
        }
        if (confirmed.optString("multiDrawsLotteryStatus") == "LIMIT") {
            store.removePersistentFlag(pendingKey)
            Log.youthPrivilege("青春豆十连抽额度已确认消耗，剩余余额#${confirmed.optString("totalAmount")}")
        } else {
            Log.error(TAG, "青春豆十连抽额度尚未确认变化，不重复抽取:$confirmed")
        }
    }

    internal fun claimForestPropsForForest(): Boolean {
        if (!isEnable() || forestProps?.value != true) {
            return false
        }
        return claimForestProps()
    }

    private fun handleCheckIn() {
        val previouslyConfirmed = Status.hasFlagToday(StatusFlags.FLAG_YOUTH_PRIVILEGE_CHECK_IN_DONE)
        val model = JSONObject(YouthPrivilegeRpcCall.queryCheckInModel())
        if (!isYouthSuccess(model)) {
            Log.error(TAG, "青春特权签到模型查询失败:$model")
            return
        }
        val checkInInfo = model.optJSONObject("studentCheckInInfo")
        val action = checkInInfo?.optString("action").orEmpty()
        when (action) {
            CHECK_IN_ACTION -> {
                val result = JSONObject(YouthPrivilegeRpcCall.checkIn())
                if (!isYouthSuccess(result)) {
                    Log.error(TAG, "青春特权签到执行失败:$result")
                    return
                }
                confirmCheckInAfterAction()
            }

            CHECKED_IN_ACTION -> {
                Status.setFlagToday(StatusFlags.FLAG_YOUTH_PRIVILEGE_CHECK_IN_DONE)
                Log.youthPrivilege(
                    if (previouslyConfirmed) "青春特权签到服务端仍确认完成" else "青春特权签到已完成#action=$action",
                )
            }

            else -> Log.youthPrivilege("青春特权签到暂不处理#action=${action.ifBlank { "UNKNOWN" }} raw=$model")
        }
    }

    private fun confirmCheckInAfterAction() {
        val confirmation = JSONObject(YouthPrivilegeRpcCall.queryCheckInModel())
        if (!isYouthSuccess(confirmation)) {
            Log.error(TAG, "青春特权签到回查失败:$confirmation")
            return
        }
        val action = confirmation.optJSONObject("studentCheckInInfo")?.optString("action").orEmpty()
        if (action == CHECKED_IN_ACTION) {
            Status.setFlagToday(StatusFlags.FLAG_YOUTH_PRIVILEGE_CHECK_IN_DONE)
            Log.youthPrivilege("青春特权签到回查确认完成")
        } else {
            Log.error(TAG, "青春特权签到执行成功但未确认进展#action=${action.ifBlank { "UNKNOWN" }} raw=$confirmation")
        }
    }

    private fun claimForestProps(): Boolean {
        if (Status.hasFlagToday(StatusFlags.FLAG_YOUTH_PRIVILEGE_FOREST_PROPS_DONE)) {
            return true
        }
        var allConfirmed = true
        for (route in LEGACY_FOREST_ROUTES) {
            val initialStatus = queryForestTaskStatus(route)
            when (initialStatus) {
                STATUS_RECEIVED -> Log.youthPrivilege("青春特权森林道具[${route.displayName}]已领取")
                STATUS_FINISHED -> {
                    val award = JSONObject(
                        AntForestRpcCall.receiveYouthPrivilegeTaskAward(route.source, route.awardTaskType),
                    )
                    if (!isAntiepSuccess(award)) {
                        allConfirmed = false
                        Log.error(TAG, "青春特权森林道具[${route.displayName}]领奖失败:$award")
                        continue
                    }
                    val confirmedStatus = queryForestTaskStatus(route)
                    if (confirmedStatus == STATUS_RECEIVED) {
                        Log.youthPrivilege("青春特权森林道具[${route.displayName}]回查确认领取")
                    } else {
                        allConfirmed = false
                        Log.error(
                            TAG,
                            "青春特权森林道具[${route.displayName}]处理成功但未确认进展#status=${confirmedStatus.ifBlank { "UNKNOWN" }}",
                        )
                    }
                }

                else -> {
                    allConfirmed = false
                    Log.youthPrivilege(
                        "青春特权森林道具[${route.displayName}]暂不处理#status=${initialStatus.ifBlank { "NOT_FOUND" }}",
                    )
                }
            }
        }
        if (allConfirmed) {
            Status.setFlagToday(StatusFlags.FLAG_YOUTH_PRIVILEGE_FOREST_PROPS_DONE)
        }
        return allConfirmed
    }

    private fun queryForestTaskStatus(route: ForestRewardRoute): String {
        val response = JSONObject(AntForestRpcCall.queryYouthPrivilegeTaskList(route.firstTaskType, route.source))
        val payload = response.optJSONObject("resData") ?: response
        if (!isAntiepSuccess(payload)) {
            Log.error(TAG, "青春特权森林道具[${route.displayName}]状态查询失败:$payload")
            return ""
        }
        val task = collectOpenGreenTaskInfos(payload).firstOrNull { taskInfo ->
            taskInfo.optJSONObject("taskBaseInfo")?.optString("taskType") == route.awardTaskType
        } ?: return ""
        return task.optJSONObject("taskBaseInfo")?.optString("taskStatus").orEmpty()
    }

    private fun collectOpenGreenTaskInfos(payload: JSONObject): List<JSONObject> {
        val taskInfos = mutableListOf<JSONObject>()
        fun appendTaskInfoList(taskInfoList: JSONArray?) {
            if (taskInfoList == null) return
            for (index in 0 until taskInfoList.length()) {
                taskInfoList.optJSONObject(index)?.let(taskInfos::add)
            }
        }
        fun appendGroups(groups: JSONArray?) {
            if (groups == null) return
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index) ?: continue
                appendTaskInfoList(group.optJSONArray("taskInfoList"))
            }
        }
        appendTaskInfoList(payload.optJSONArray("taskInfoList"))
        appendGroups(payload.optJSONArray("taskGroupList"))
        appendGroups(payload.optJSONArray("forestTasksNew"))
        payload.optJSONObject("result")?.let { result ->
            appendTaskInfoList(result.optJSONArray("taskInfoList"))
            appendGroups(result.optJSONArray("taskGroupList"))
        }
        return taskInfos
    }

    private fun isYouthSuccess(response: JSONObject): Boolean =
        response.optBoolean("success") && response.optString("resultCode") == SUCCESS

    private fun isAntiepSuccess(response: JSONObject): Boolean =
        response.optBoolean("success") ||
            response.optString("resultCode") == SUCCESS ||
            response.optString("code") == "100000000"

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private inner class YouthTaskFlowAdapter : TaskFlowAdapter {
        private val loggedUnsupportedTaskCodes = mutableSetOf<String>()

        override val continueCurrentRoundOnRetryableFailure: Boolean = true
        override val moduleName: String = getName()
        override val flowName: String = "青春特权任务"

        override fun query(): JSONObject = JSONObject(YouthPrivilegeRpcCall.queryTaskModel())

        override fun isQuerySuccess(response: JSONObject): Boolean = isYouthSuccess(response)

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val module = response.optJSONObject("studentTaskModule") ?: JSONObject()
            val rawTasks = linkedMapOf<String, JSONObject>()
            fun appendTask(task: JSONObject?) {
                val safeTask = task ?: return
                val taskCode = safeTask.optString("taskCode")
                val taskSource = safeTask.optString("taskSource")
                val taskType = safeTask.optString("taskType")
                if (taskCode.isBlank() || taskSource.isBlank() || taskType.isBlank()) {
                    Log.error(TAG, "青春特权任务缺少服务端执行参数:$safeTask")
                    return
                }
                rawTasks.putIfAbsent(taskCode, safeTask)
            }
            val groups = module.optJSONArray("taskGroupList")
            if (groups != null) {
                for (groupIndex in 0 until groups.length()) {
                    val group = groups.optJSONObject(groupIndex) ?: continue
                    val taskList = group.optJSONArray("taskList") ?: continue
                    for (taskIndex in 0 until taskList.length()) {
                        val task = taskList.optJSONObject(taskIndex) ?: continue
                        for (key in listOf("currentCount", "totalCount")) {
                            if (!task.has(key) && group.has(key)) task.put(key, group.get(key))
                        }
                        appendTask(task)
                    }
                }
            }
            appendTask(module.optJSONObject("checkInRecommendTask"))

            val items = rawTasks.values.map { task ->
                TaskFlowItem(
                    id = task.optString("taskCode"),
                    title = task.optString("taskName").ifBlank { task.optString("taskCode") },
                    status = task.optString("taskStatus"),
                    type = task.optString("taskType"),
                    actionType = task.optString("taskAction"),
                    blacklistKeys = listOf(task.optString("taskCode")),
                    raw = task,
                    progress = task.optString("currentCount"),
                    current = task.optIntOrNull("currentCount"),
                    limit = task.optIntOrNull("totalCount"),
                )
            }.toMutableList()
            response.optJSONObject("feedsTaskVO")?.let { feeds ->
                items.add(TaskFlowItem(
                    id = "DO_FEEDS_TASK", title = "滑动浏览15秒", status = feeds.optString("feedsTaskStatus"),
                    type = "FEEDS", raw = feeds,
                    progress = "${feeds.optString("feedsTaskStatus")}:${response.optString("taskFlowInAmountInfo")}",
                ))
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
            when {
                item.type == "FEEDS" && item.status == "FINISH" -> TaskFlowPhase.TERMINAL
                item.type == "FEEDS" && item.status == STATUS_PROCESSING -> TaskFlowPhase.SIGNUP_COMPLETE
                item.status == STATUS_COMPLETE || item.actionType == ACTION_DO_NOTHING -> TaskFlowPhase.TERMINAL
                item.type != TASK_TYPE_BROWSER -> TaskFlowPhase.UNKNOWN
                item.status == STATUS_PROCESSING || item.actionType == ACTION_COMPLETE -> TaskFlowPhase.SIGNUP_COMPLETE
                item.status == STATUS_TO_APPLY || item.actionType == ACTION_SIGNUP -> TaskFlowPhase.SIGNUP_REQUIRED
                else -> TaskFlowPhase.UNKNOWN
            }

        override fun isFlowHandledToday(): Boolean = false

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            if (item.type.isBlank() || item.type == TASK_TYPE_BROWSER || item.type == "FEEDS") {
                return false
            }
            if (loggedUnsupportedTaskCodes.add(item.id)) {
                Log.youthPrivilege(
                    "青春特权任务[跳过非浏览任务] taskCode=${item.id} " +
                        "taskType=${item.type} status=${item.status.ifBlank { "UNKNOWN" }}",
                )
            }
            return true
        }

        override fun isUnresolvedWhenSkipped(item: TaskFlowItem): Boolean =
            !isBlacklisted(item) &&
                item.status != STATUS_COMPLETE && item.actionType != ACTION_DO_NOTHING

        override fun signup(item: TaskFlowItem): TaskFlowActionResult =
            executeTaskAction(item, "taskSignUp") { taskCode, taskSource, taskType ->
                YouthPrivilegeRpcCall.taskSignUp(taskCode, taskSource, taskType)
            }

        override fun send(item: TaskFlowItem): TaskFlowActionResult {
            if (item.type == "FEEDS") {
                val response = JSONObject(YouthPrivilegeRpcCall.triggerFeedsPrize())
                if (!isYouthSuccess(response)) return TaskFlowActionResult.failure(
                    TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, code = response.optString("resultCode"),
                    message = response.optString("resultDesc"), rpc = "student.triggerPointPrize", raw = response.toString(),
                )
                return TaskFlowActionResult.success(refreshAfterAction = true, progressChanged = false)
            }
            return executeTaskAction(item, "taskComplete") { taskCode, taskSource, taskType ->
                YouthPrivilegeRpcCall.taskComplete(taskCode, taskSource, taskType)
            }
        }

        private fun executeTaskAction(
            item: TaskFlowItem,
            rpc: String,
            request: (taskCode: String, taskSource: String, taskType: String) -> String,
        ): TaskFlowActionResult {
            val rawTask =
                item.raw
                    ?: return unsupported(
                        item,
                        if (rpc == "taskSignUp") TaskFlowAction.SIGNUP else TaskFlowAction.SEND,
                    )
            val taskCode = rawTask.optString("taskCode")
            val taskSource = rawTask.optString("taskSource")
            val taskType = rawTask.optString("taskType")
            if (taskCode.isBlank() || taskSource.isBlank() || taskType.isBlank()) {
                return unsupported(item, if (rpc == "taskSignUp") TaskFlowAction.SIGNUP else TaskFlowAction.SEND)
            }
            val response = JSONObject(request(taskCode, taskSource, taskType))
            if (isYouthSuccess(response)) {
                // 动作成功仅说明服务端接受请求，TaskFlow 必须回查服务端状态。
                return TaskFlowActionResult.success(
                    refreshAfterAction = true,
                    progressChanged = false,
                )
            }
            val hasRetryable = response.has("retryable") && !response.isNull("retryable")
            val failureType =
                when {
                    hasRetryable && response.optBoolean("retryable") -> TaskRpcFailureType.RETRYABLE_RPC
                    response.optBoolean("success", true) == false &&
                        response.optString("resultCode") == "TASK_OPERATE_ERROR" &&
                        hasRetryable &&
                        !response.optBoolean("retryable") -> {
                        TaskRpcFailureType.NON_RETRYABLE_INVALID
                    }

                    else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
                }
            return TaskFlowActionResult.failure(
                failureType = failureType,
                code = response.optString("resultCode"),
                message = response.optString("resultMessage"),
                rpc = "YouthPrivilegeRpcCall.$rpc",
                raw = response.toString(),
                detail = "taskCode=$taskCode taskSource=$taskSource taskType=$taskType",
            )
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            Status.setFlagToday(StatusFlags.FLAG_YOUTH_PRIVILEGE_TASKS_DONE)
            Log.youthPrivilege("青春特权任务服务端已无待处理项#${snapshot.totalTasks}")
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.error(TAG, "青春特权任务查询失败:$response")
        }

        override fun logInfo(message: String) {
            Log.youthPrivilege(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }
    }

    private fun claimTrialPrize() {
        try {
            val store = io.github.aoguai.sesameag.util.UserDataStoreManager.getCurrentInstance() ?: return
            val pending = store.getOrCreate<MutableMap<String, String>>("youthTrialPrizePending")
            val period = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
            fun queryAwards(month: Boolean): List<JSONObject>? {
                val awards = mutableListOf<JSONObject>()
                val response = JSONObject(YouthPrivilegeRpcCall.queryTrialPrizes(month))
                val list = response.optJSONArray("result")
                if (!response.optBoolean("success") || list == null) {
                    Log.error(TAG, "青春体验金查询失败 month=$month raw=$response")
                    return null
                }
                for (index in 0 until list.length()) list.optJSONObject(index)?.let(awards::add)
                return awards.distinctBy { it.optString("sendOrderId") }
            }
            fun confirmed(award: JSONObject): Boolean =
                award.optString("sendStatus") == "SUCCESS" &&
                    award.optString("sendOrderId").isNotBlank() &&
                    award.optJSONArray("voucherInfo")?.let { vouchers ->
                        (0 until vouchers.length()).any { vouchers.optJSONObject(it)?.optString("voucher_id")?.isNotBlank() == true }
                    } == true
            val beforeDaily = queryAwards(month = false) ?: return
            val beforeMonthly = queryAwards(month = true)
            val expected = pending[period]?.let { JSONArray(it) }
            val expectedIds = (0 until (expected?.length() ?: 0))
                .map { expected!!.getString(it) }.filter { it.isNotBlank() }.distinct()
            if (expectedIds.isNotEmpty()) {
                val confirmedOrders = (beforeDaily + beforeMonthly.orEmpty()).filter(::confirmed)
                val unresolvedIds = expectedIds.filterNot { id -> confirmedOrders.any { it.optString("sendOrderId") == id } }
                if (unresolvedIds.isNotEmpty()) {
                    pending[period] = JSONArray(unresolvedIds).toString()
                    store.put("youthTrialPrizePending", pending)
                    Log.youthPrivilege("青春体验金已有绑定订单待确认，继续回查同订单与券状态#${unresolvedIds.joinToString()}")
                    return
                }
                pending.remove(period)
                store.put("youthTrialPrizePending", pending)
            }
            val received = beforeDaily.filter(::confirmed)
            if (received.isNotEmpty()) {
                pending.remove(period)
                store.put("youthTrialPrizePending", pending)
                Log.youthPrivilege("青春体验金当日发放已确认#${received.joinToString { "订单=${it.optString("sendOrderId")} 券状态=${it.optString("finEquityStatus")}" }}")
                return
            }
            if (beforeDaily.isNotEmpty()) {
                Log.youthPrivilege("青春体验金当日已有订单但券状态未确认，保留后续查询")
                return
            }
            pending[period] = "[]"
            store.put("youthTrialPrizePending", pending)
            val response = JSONObject(YouthPrivilegeRpcCall.triggerTrialPrize())
            val results = response.optJSONArray("result")
            val ids = JSONArray()
            for (index in 0 until (results?.length() ?: 0)) {
                results?.optJSONObject(index)?.optString("sendOrderId")?.takeIf { it.isNotBlank() }?.let(ids::put)
            }
            pending[period] = ids.toString()
            store.put("youthTrialPrizePending", pending)
            if (!response.optBoolean("success")) {
                val retryMessage = if (response.has("needRetry") && !response.isNull("needRetry") && !response.optBoolean("needRetry")) {
                    "服务端标记不重试，本轮仅回查"
                } else {
                    "本轮不重复触发，保留后续查询"
                }
                Log.error(TAG, "青春体验金领取失败，$retryMessage code=${response.optString("resultCode")} needRetry=${response.opt("needRetry")} raw=$response")
            }
            val afterDaily = queryAwards(month = false)
            val afterMonthly = queryAwards(month = true)
            val confirmedDaily = afterDaily?.filter(::confirmed).orEmpty()
            val confirmedOrders = confirmedDaily + afterMonthly?.filter(::confirmed).orEmpty()
            val orderIds = (0 until ids.length()).map { ids.getString(it) }.distinct()
            val unresolvedIds = orderIds.filterNot { id -> confirmedOrders.any { it.optString("sendOrderId") == id } }
            if ((orderIds.isNotEmpty() && unresolvedIds.isEmpty()) ||
                (orderIds.isEmpty() && confirmedDaily.isNotEmpty())
            ) {
                pending.remove(period)
                store.put("youthTrialPrizePending", pending)
            } else if (unresolvedIds.isNotEmpty()) {
                pending[period] = JSONArray(unresolvedIds).toString()
                store.put("youthTrialPrizePending", pending)
                Log.youthPrivilege("青春体验金绑定订单尚未全部确认#${unresolvedIds.joinToString()}")
            }
            if (confirmedDaily.isNotEmpty()) {
                Log.youthPrivilege("青春体验金当日发放确认#${confirmedDaily.joinToString { "订单=${it.optString("sendOrderId")} 金额=${it.optString("amount")} 券状态=${it.optString("finEquityStatus")}" }}")
            } else {
                Log.youthPrivilege("青春体验金当日到账未确认，月奖励不代替日奖励，保留后续查询")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "青春体验金处理异常", t)
        }
    }

    private inner class MonthlyPrivilegeAdapter : TaskFlowAdapter {
        override val moduleName: String = getName()
        override val flowName: String = "青春每月理财福利"
        override fun query(): JSONObject = JSONObject(YouthPrivilegeRpcCall.queryYouth100())
        override fun isQuerySuccess(response: JSONObject): Boolean = isYouthSuccess(response)
        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val items = mutableListOf<TaskFlowItem>()
            val feeds = response.optJSONArray("feeds") ?: return items
            for (feedIndex in 0 until feeds.length()) {
                val modules = feeds.optJSONObject(feedIndex)?.optJSONArray("modules") ?: continue
                for (moduleIndex in 0 until modules.length()) {
                    val module = modules.optJSONObject(moduleIndex) ?: continue
                    val moduleId = module.optString("moduleId")
                    if (moduleId != "FIN_MONTHLY") continue
                    val entries = module.optJSONArray("items") ?: continue
                    for (index in 0 until entries.length()) {
                        val entry = entries.optJSONObject(index) ?: continue
                        val id = entry.optString("privilegeId")
                        if (id.isBlank()) continue
                        val status = entry.optString("cardStatus")
                        items.add(TaskFlowItem(id = id, title = entry.optString("title", id), status = status,
                            sceneCode = moduleId, actionType = entry.optJSONObject("actionButton")?.optString("actionType").orEmpty(), raw = entry))
                    }
                }
            }
            return items
        }
        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase = when {
            item.status == "COOLDOWN" || item.status == STATUS_RECEIVED -> TaskFlowPhase.TERMINAL
            item.status == "AVAILABLE" && item.actionType == "CLAIM" -> TaskFlowPhase.REWARD_READY
            else -> TaskFlowPhase.UNKNOWN
        }
        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            val response = JSONObject(YouthPrivilegeRpcCall.receiveMonthlyPrivilege(item.id, item.sceneCode))
            if (!isYouthSuccess(response)) return TaskFlowActionResult.failure(
                TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, code = response.optString("resultCode"),
                message = response.optString("resultMessage"), rpc = "youth100.privilege.receive", raw = response.toString(),
            )
            return TaskFlowActionResult.success(refreshAfterAction = true, progressChanged = false)
        }
        override fun onQueryFailed(response: JSONObject) { Log.error(TAG, "青春每月权益查询失败 raw=$response") }
        override fun logInfo(message: String) { Log.youthPrivilege(message) }
        override fun logError(message: String) { Log.error(TAG, message) }
    }

    private fun unsupported(
        item: TaskFlowItem,
        action: TaskFlowAction,
    ): TaskFlowActionResult =
        TaskFlowActionResult.failure(
            TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            message = "缺少${action.logName}任务参数",
            rpc = "YouthPrivilegeRpcCall.${action.logName}",
            detail = "taskCode=${item.id} status=${item.status}",
        )

}

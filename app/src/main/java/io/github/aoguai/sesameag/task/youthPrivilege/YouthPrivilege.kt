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
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "青春特权执行异常", t)
        } finally {
            Log.youthPrivilege("青春特权执行结束")
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

        override val moduleName: String = getName()
        override val flowName: String = "青春特权任务"

        override fun query(): JSONObject = JSONObject(YouthPrivilegeRpcCall.queryTaskModel())

        override fun isQuerySuccess(response: JSONObject): Boolean = isYouthSuccess(response)

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val module = response.optJSONObject("studentTaskModule") ?: return emptyList()
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
                    val taskList = groups.optJSONObject(groupIndex)?.optJSONArray("taskList") ?: continue
                    for (taskIndex in 0 until taskList.length()) {
                        appendTask(taskList.optJSONObject(taskIndex))
                    }
                }
            }
            appendTask(module.optJSONObject("checkInRecommendTask"))

            return rawTasks.values.map { task ->
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
            }
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
            when {
                item.status == STATUS_COMPLETE || item.actionType == ACTION_DO_NOTHING -> TaskFlowPhase.TERMINAL
                item.type != TASK_TYPE_BROWSER -> TaskFlowPhase.UNKNOWN
                item.status == STATUS_PROCESSING || item.actionType == ACTION_COMPLETE -> TaskFlowPhase.SIGNUP_COMPLETE
                item.status == STATUS_TO_APPLY || item.actionType == ACTION_SIGNUP -> TaskFlowPhase.SIGNUP_REQUIRED
                else -> TaskFlowPhase.UNKNOWN
            }

        override fun isFlowHandledToday(): Boolean = false

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            if (item.type.isBlank() || item.type == TASK_TYPE_BROWSER) {
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

        override fun send(item: TaskFlowItem): TaskFlowActionResult =
            executeTaskAction(item, "taskComplete") { taskCode, taskSource, taskType ->
                YouthPrivilegeRpcCall.taskComplete(taskCode, taskSource, taskType)
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

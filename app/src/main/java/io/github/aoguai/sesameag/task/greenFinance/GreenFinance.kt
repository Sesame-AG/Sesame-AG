package io.github.aoguai.sesameag.task.greenFinance

import io.github.aoguai.sesameag.task.greenFinance.GreenFinanceRpcCall.taskQuery
import io.github.aoguai.sesameag.task.greenFinance.GreenFinanceRpcCall.taskTrigger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowDecision
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.util.TimeUtil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

class GreenFinance : ModelTask() {

    private var greenFinanceLsxd: BooleanModelField? = null
    private var greenFinanceLsbg: BooleanModelField? = null
    private var greenFinanceLscg: BooleanModelField? = null
    private var greenFinanceLswl: BooleanModelField? = null
    private var greenFinanceWdxd: BooleanModelField? = null
    private var greenFinanceDonation: BooleanModelField? = null
    private var greenFinancePointFriend: BooleanModelField? = null

    override fun getName(): String = "绿色经营"

    override fun getGroup(): ModelGroup = ModelGroup.OTHER

    override fun getIcon(): String = "GreenFinance.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(BooleanModelField("greenFinanceLsxd", "绿色行动 | 打卡", false).withDesc(
            "执行绿色经营中绿色行动分类的待打卡项。"
        ).also { greenFinanceLsxd = it })
        modelFields.addField(BooleanModelField("greenFinanceLscg", "绿色采购 | 打卡", false).withDesc(
            "执行绿色经营中绿色采购分类的待打卡项。"
        ).also { greenFinanceLscg = it })
        modelFields.addField(BooleanModelField("greenFinanceLsbg", "绿色办公 | 打卡", false).withDesc(
            "执行绿色经营中绿色办公分类的待打卡项。"
        ).also { greenFinanceLsbg = it })
        modelFields.addField(BooleanModelField("greenFinanceWdxd", "绿色销售 | 打卡", false).withDesc(
            "执行绿色经营中绿色销售分类的待打卡项。"
        ).also { greenFinanceWdxd = it })
        modelFields.addField(BooleanModelField("greenFinanceLswl", "绿色物流 | 打卡", false).withDesc(
            "执行绿色经营中绿色物流分类的待打卡项。"
        ).also { greenFinanceLswl = it })
        modelFields.addField(BooleanModelField("greenFinancePointFriend", "好友金币 | 收取", false).withDesc(
            "巡查好友排行榜并收取可领取的好友金币，每日仅处理一次。"
        ).also { greenFinancePointFriend = it })
        modelFields.addField(BooleanModelField("greenFinanceDonation", "经营金币 | 捐助快过期金币", false).withDesc(
            "检测 1 天内将过期的经营金币并自动分批捐助，避免过期失效。"
        ).also { greenFinanceDonation = it })
        return modelFields
    }

    override fun check(): Boolean {
        return when {
            TaskCommon.IS_ENERGY_TIME -> {
                Log.greenFinance("⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，停止执行${getName()}任务！")
                false
            }
            TaskCommon.IS_MODULE_SLEEP_TIME -> {
                Log.greenFinance("💤 模块休眠时间【${BaseModel.modelSleepTime.value}】停止执行${getName()}任务！")
                false
            }
            else -> true
        }
    }

    override fun runJava() {
        GlobalThreadPools.execute {
            runSuspend()
        }
    }

    @Suppress("ReturnCount")
    override suspend fun runSuspend() {
        try {
            Log.greenFinance("执行开始-${getName()}")
            val s = GreenFinanceRpcCall.greenFinanceIndex()
            var jo = JsonUtil.parseJSONObject(s)
            if (!jo.optBoolean("success")) {
                Log.runtime(TAG, jo.optString("resultDesc"))
                return
            }

            val result = jo.optJSONObject("result") ?: return
            if (!result.optBoolean("greenFinanceSigned")) {
                Log.greenFinance("绿色经营📊未开通")
                return
            }

            val mcaGreenLeafResult = result.optJSONObject("mcaGreenLeafResult")
            val greenLeafList = mcaGreenLeafResult?.optJSONArray("greenLeafList")
            if (greenLeafList != null) {
                var currentCode: String? = null
                var bsnIds = JSONArray()

                for (i in 0 until greenLeafList.length()) {
                    val greenLeaf = greenLeafList.optJSONObject(i) ?: continue
                    val code = greenLeaf.optString("code")
                    val bsnId = greenLeaf.optString("bsnId")
                    if (code.isEmpty() || bsnId.isEmpty()) continue

                    if (currentCode == null) {
                        currentCode = code
                    }

                    if (code != currentCode && bsnIds.length() > 0) {
                        batchSelfCollect(bsnIds)
                        bsnIds = JSONArray()
                        currentCode = code
                    }

                    bsnIds.put(bsnId)
                }

                if (bsnIds.length() > 0) {
                    batchSelfCollect(bsnIds)
                }
            }

            signIn("PLAY102632271")
            signIn("PLAY102232206")
            behaviorTick()
            donation()
            batchStealFriend()
            prizes()
            doTask("AP13159535", TAG, "绿色经营📊")
        } catch (th: Throwable) {
            Log.runtime(TAG, "index err:")
            Log.printStackTrace(TAG, th)
        } finally {
            Log.greenFinance("执行结束-${getName()}")
        }
    }

    private fun batchSelfCollect(bsnIds: JSONArray) {
        val s = GreenFinanceRpcCall.batchSelfCollect(bsnIds)
        try {
            val joSelfCollect = JsonUtil.parseJSONObject(s)
            if (joSelfCollect.optBoolean("success")) {
                val totalCollectPoint = joSelfCollect.optJSONObject("result")?.optInt("totalCollectPoint") ?: 0
                Log.greenFinance("绿色经营📊收集获得$totalCollectPoint")
            } else {
                Log.runtime("$TAG.batchSelfCollect", joSelfCollect.optString("resultDesc"))
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "batchSelfCollect err:")
            Log.printStackTrace(TAG, th)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun signIn(sceneId: String) {
        try {
            var s = GreenFinanceRpcCall.signInQuery(sceneId)
            var jo = JsonUtil.parseJSONObject(s)
            if (!jo.optBoolean("success")) {
                Log.runtime("$TAG.signIn.signInQuery", jo.optString("resultDesc"))
                return
            }
            val result = jo.optJSONObject("result") ?: return
            if (result.optBoolean("isTodaySignin")) {
                return
            }
            s = GreenFinanceRpcCall.signInTrigger(sceneId)
            jo = JsonUtil.parseJSONObject(s)
            if (jo.optBoolean("success")) {
                Log.greenFinance("绿色经营📊签到成功")
            } else {
                Log.runtime("$TAG.signIn.signInTrigger", jo.optString("resultDesc"))
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "signIn err:")
            Log.printStackTrace(TAG, th)
        }
    }

    private suspend fun behaviorTick() {
        if (greenFinanceLsxd?.value == true) doTick("lsxd")
        if (greenFinanceLscg?.value == true) doTick("lscg")
        if (greenFinanceLswl?.value == true) doTick("lswl")
        if (greenFinanceLsbg?.value == true) doTick("lsbg")
        if (greenFinanceWdxd?.value == true) doTick("wdxd")
    }

    private suspend fun doTick(type: String) {
        try {
            var str = GreenFinanceRpcCall.queryUserTickItem(type)
            var jsonObject = JsonUtil.parseJSONObject(str)
            if (!jsonObject.optBoolean("success")) {
                Log.runtime("$TAG.doTick.queryUserTickItem", jsonObject.optString("resultDesc"))
                return
            }
            val jsonArray = jsonObject.optJSONArray("result") ?: return
            for (i in 0 until jsonArray.length()) {
                jsonObject = jsonArray.optJSONObject(i) ?: continue
                if ("Y" == jsonObject.optString("status")) {
                    continue
                }
                val behaviorCode = jsonObject.optString("behaviorCode")
                if (behaviorCode.isEmpty()) continue
                str = GreenFinanceRpcCall.submitTick(type, behaviorCode)
                val obj = JsonUtil.parseJSONObject(str)
                if (!obj.optBoolean("success") || 
                    JsonUtil.getValueByPath(obj, "result.result") != "true") {
                    Log.greenFinance("绿色经营📊[${jsonObject.optString("title")}]打卡失败")
                    break
                }
                Log.greenFinance("绿色经营📊[${jsonObject.optString("title")}]打卡成功")
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "doTick err:")
            Log.printStackTrace(TAG, th)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun donation() {
        if (greenFinanceDonation?.value != true) {
            return
        }
        try {
            var str = GreenFinanceRpcCall.queryExpireMcaPoint(1)
            var jsonObject = JsonUtil.parseJSONObject(str)
            if (!jsonObject.optBoolean("success")) {
                Log.runtime("$TAG.donation.queryExpireMcaPoint", jsonObject.optString("resultDesc"))
                return
            }
            val strAmount = JsonUtil.getValueByPath(jsonObject, "result.expirePoint.amount")
            if (strAmount.isEmpty() || !strAmount.matches(Regex("-?\\d+(\\.\\d+)?"))) {
                return
            }
            val amount = strAmount.toDouble()
            if (amount <= 0) {
                return
            }
            Log.greenFinance("绿色经营📊1天内过期的金币[$amount]")
            str = GreenFinanceRpcCall.queryAllDonationProjectNew()
            jsonObject = JsonUtil.parseJSONObject(str)
            if (!jsonObject.optBoolean("success")) {
                Log.runtime("$TAG.donation.queryAllDonationProjectNew", jsonObject.optString("resultDesc"))
                return
            }
            val result = jsonObject.optJSONArray("result") ?: return
            val dicId = TreeMap<String, String>()
            for (i in 0 until result.length()) {
                val obj = JsonUtil.getValueByPathObject(
                    result.getJSONObject(i),
                    "mcaDonationProjectResult.[0]"
                ) as? JSONObject ?: continue
                val pId = obj.optString("projectId")
                if (pId.isEmpty()) {
                    continue
                }
                dicId[pId] = obj.optString("projectName")
            }
            val r = calculateDeductions(amount.toInt(), dicId.size)
            var am = "200"
            for (i in 0 until r[0]) {
                val id = dicId.keys.elementAt(i)
                val name = dicId[id]
                if (i == r[0] - 1) {
                    am = r[1].toString()
                }
                str = GreenFinanceRpcCall.donation(id, am)
                jsonObject = JsonUtil.parseJSONObject(str)
                if (!jsonObject.optBoolean("success")) {
                    Log.runtime("$TAG.donation.$id", jsonObject.optString("resultDesc"))
                    return
                }
                Log.greenFinance("绿色经营📊成功捐助[$name]${am}金币")
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "donation err:")
            Log.printStackTrace(TAG, th)
        }
    }

    private fun prizes() {
        try {
            if (Status.canGreenFinancePrizesMap()) {
                return
            }
            val campId = "CP14664674"
            var str = GreenFinanceRpcCall.queryPrizes(campId)
            var jsonObject = JsonUtil.parseJSONObject(str)
            if (!jsonObject.optBoolean("success")) {
                Log.runtime("$TAG.prizes.queryPrizes", jsonObject.optString("resultDesc"))
                return
            }
            val prizes = JsonUtil.getValueByPathObject(jsonObject, "result.prizes") as? JSONArray
            if (prizes != null) {
                for (i in 0 until prizes.length()) {
                    jsonObject = prizes.getJSONObject(i)
                    val bizTime = jsonObject.getString("bizTime")
                    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                    val dateTime = formatter.parse(bizTime)
                    if (dateTime != null && TimeUtil.getWeekNumber(dateTime) == TimeUtil.getWeekNumber(Date())) {
                        Status.greenFinancePrizesMap()
                        return
                    }
                }
            }
            str = GreenFinanceRpcCall.campTrigger(campId)
            jsonObject = JsonUtil.parseJSONObject(str)
            if (!jsonObject.optBoolean("success")) {
                Log.runtime("$TAG.prizes.campTrigger", jsonObject.optString("resultDesc"))
                return
            }
            val obj = JsonUtil.getValueByPathObject(jsonObject, "result.prizes.[0]") as? JSONObject ?: return
            Log.greenFinance("绿色经营🍬评级奖品[${obj.getString("prizeName")}]${obj.getString("price")}")
        } catch (th: Throwable) {
            Log.runtime(TAG, "prizes err:")
            Log.printStackTrace(TAG, th)
        }
    }

    private suspend fun batchStealFriend() {
        if (Status.canGreenFinancePointFriend() || greenFinancePointFriend?.value != true) {
            return
        }
        try {
            var startIndex = 0
            while (currentCoroutineContext().isActive) {
                val pageJo = queryRankingPage(startIndex) ?: break
                val result = pageJo.optJSONObject("result") ?: break
                val rankingList = result.optJSONArray("rankingList")
                if (rankingList != null && rankingList.length() > 0) {
                    processRankingList(rankingList)
                }
                if (result.optBoolean("lastPage")) {
                    Log.greenFinance("绿色经营🙋，好友金币巡查完成")
                    Status.greenFinancePointFriend()
                    break
                }
                val nextStartIndex = result.optInt("nextStartIndex", startIndex)
                if (nextStartIndex <= startIndex) {
                    Log.runtime(TAG, "绿色经营好友排行分页停止：nextStartIndex无进展[$startIndex->$nextStartIndex]")
                    break
                }
                startIndex = nextStartIndex
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "batchStealFriend err:")
            Log.printStackTrace(TAG, th)
        }
    }

    private suspend fun queryRankingPage(startIndex: Int): JSONObject? {
        val str = GreenFinanceRpcCall.queryRankingList(startIndex)
        val jo = JsonUtil.parseJSONObject(str)
        if (!jo.optBoolean("success")) {
            Log.greenFinance("绿色经营🙋，好友金币巡查失败")
            return null
        }
        return jo
    }

    private suspend fun processRankingList(list: JSONArray) {
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            if (!obj.optBoolean("collectFlag")) {
                continue
            }
            val friendId = obj.optString("uid")
            if (friendId.isEmpty()) {
                continue
            }
            if (FriendGuard.shouldSkipFriend(friendId, TAG, "绿色经营好友金币")) {
                continue
            }
            collectFromFriend(friendId, obj.optString("nickName"))
        }
    }

    @Suppress("ReturnCount")
    private suspend fun collectFromFriend(friendId: String, nickname: String) {
        var str = GreenFinanceRpcCall.queryGuestIndexPoints(friendId)
        var jsonObject = JsonUtil.parseJSONObject(str)
        if (!jsonObject.optBoolean("success")) {
            Log.runtime("$TAG.batchStealFriend.queryGuestIndexPoints", jsonObject.optString("resultDesc"))
            return
        }
        val points = JsonUtil.getValueByPathObject(jsonObject, "result.pointDetailList") as? JSONArray ?: return
        val bsnIds = extractStealableBsnIds(points)
        if (bsnIds.length() == 0) {
            return
        }

        str = GreenFinanceRpcCall.batchSteal(bsnIds, friendId)
        jsonObject = JsonUtil.parseJSONObject(str)
        if (!jsonObject.optBoolean("success")) {
            Log.runtime("$TAG.batchStealFriend.batchSteal", jsonObject.optString("resultDesc"))
            return
        }
        Log.greenFinance("绿色经营🤩收[$nickname]${JsonUtil.getValueByPath(jsonObject, "result.totalCollectPoint")}金币")
    }

    private fun extractStealableBsnIds(points: JSONArray): JSONArray {
        val bsnIds = JSONArray()
        for (j in 0 until points.length()) {
            val point = points.optJSONObject(j) ?: continue
            if (point.optBoolean("collectFlag")) {
                continue
            }
            val bsnId = point.optString("bsnId")
            if (bsnId.isNotEmpty()) {
                bsnIds.put(bsnId)
            }
        }
        return bsnIds
    }

    private fun calculateDeductions(amount: Int, maxDeductions: Int): IntArray {
        if (amount < 200) {
            return intArrayOf(1, 200)
        }
        var actualDeductions = minOf(maxDeductions, ((amount.toDouble() / 200).let { if (it > it.toInt()) it.toInt() + 1 else it.toInt() }))
        var remainingAmount = amount - actualDeductions * 200
        if (remainingAmount % 100 != 0) {
            remainingAmount = ((remainingAmount + 99) / 100) * 100
        }
        if (remainingAmount < 200) {
            remainingAmount = 200
        }
        if (remainingAmount < amount - actualDeductions * 200) {
            actualDeductions = (amount - remainingAmount) / 200
        }
        return intArrayOf(actualDeductions, remainingAmount)
    }

    companion object {
        private val TAG = GreenFinance::class.java.simpleName

        @JvmStatic
        fun doTask(appletId: String, tag: String, name: String) {
            try {
                TaskFlowEngine(
                    GreenFinanceTaskFlowAdapter(appletId, tag, name),
                    roundSleepMs = 500L
                ).run()
            } catch (th: Throwable) {
                Log.runtime(tag, "doTask err:")
                Log.printStackTrace(tag, th)
            }
        }

        private class GreenFinanceTaskFlowAdapter(
            private val appletId: String,
            private val tag: String,
            private val name: String
        ) : TaskFlowAdapter {
            override val moduleName: String = TASK_BLACKLIST_MODULE
            override val flowName: String = "${name}任务"

            private val signedUpTaskKeys = LinkedHashSet<String>()
            private val sentTaskKeys = LinkedHashSet<String>()
            private val receivedTaskKeys = LinkedHashSet<String>()
            private val loggedUnsupportedTaskKeys = LinkedHashSet<String>()

            override fun query(): JSONObject {
                val response = taskQuery(appletId)
                if (response.isBlank()) {
                    return JSONObject()
                        .put("success", false)
                        .put("resultDesc", "taskQuery返回空")
                }
                return JsonUtil.parseJSONObject(response)
            }

            override fun isQuerySuccess(response: JSONObject): Boolean {
                return response.optBoolean("success")
            }

            override fun extractItems(response: JSONObject): List<TaskFlowItem> {
                val result = response.optJSONObject("result") ?: return emptyList()
                val taskDetailList = result.optJSONArray("taskDetailList")
                    ?: result.optJSONObject("taskDetailList")?.optJSONArray("taskDetailList")
                    ?: return emptyList()
                val items = mutableListOf<TaskFlowItem>()
                for (i in 0 until taskDetailList.length()) {
                    val taskDetail = taskDetailList.optJSONObject(i) ?: continue
                    val taskId = taskDetail.optString("taskId")
                        .ifBlank { taskDetail.optString("taskCenterId") }
                        .ifBlank { taskDetail.optJSONObject("taskConfig")?.optString("appletId").orEmpty() }
                        .trim()
                    if (taskId.isBlank()) {
                        continue
                    }

                    val taskExtProps = taskDetail.optJSONObject("taskExtProps")
                    val morphoDetail = parseGreenFinanceObject(taskExtProps?.opt("TASK_MORPHO_DETAIL"))
                    val taskMaterial = taskDetail.optJSONObject("taskMaterial")
                    val title = extractGreenFinanceTaskTitle(taskDetail, taskMaterial, morphoDetail, taskId)
                    val actionType = taskDetail.optString("taskType")
                        .ifBlank { taskMaterial?.optString("taskType").orEmpty() }
                        .ifBlank { morphoDetail.optString("taskType") }
                        .ifBlank { taskExtProps?.optString("TASK_TYPE").orEmpty() }
                        .trim()
                    val current = extractGreenFinanceTaskCurrent(taskDetail)
                    val limit = extractGreenFinanceTaskLimit(taskDetail)
                    items.add(
                        TaskFlowItem(
                            id = taskId,
                            title = title,
                            status = taskDetail.optString("taskProcessStatus")
                                .ifBlank { taskDetail.optString("taskStatus") }
                                .trim(),
                            type = taskDetail.optString("sendCampTriggerType").trim(),
                            sceneCode = appletId,
                            actionType = actionType,
                            blacklistKeys = listOf(taskId, title).filter { it.isNotBlank() },
                            raw = taskDetail,
                            progress = buildGreenFinanceTaskProgress(current, limit),
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
                val taskKey = buildGreenFinanceTaskKey(item)
                return when (item.status.uppercase(Locale.ROOT)) {
                    "TO_RECEIVE",
                    "WAIT_RECEIVE",
                    "FINISHED",
                    "COMPLETE" -> TaskFlowPhase.REWARD_READY

                    "NONE_SIGNUP" -> if (taskKey in signedUpTaskKeys) {
                        TaskFlowPhase.SIGNUP_COMPLETE
                    } else {
                        TaskFlowPhase.SIGNUP_REQUIRED
                    }

                    "SIGNUP_COMPLETE" -> TaskFlowPhase.SIGNUP_COMPLETE

                    "NOT_DONE",
                    "TODO",
                    "WAIT_COMPLETE" -> TaskFlowPhase.READY_TO_COMPLETE

                    "SEND_SUCCESS" -> if (item.raw?.optBoolean("needManuallyReceiveAward", false) == true) {
                        TaskFlowPhase.REWARD_READY
                    } else {
                        TaskFlowPhase.TERMINAL
                    }

                    "RECEIVE_SUCCESS",
                    "RECEIVED",
                    "HAS_RECEIVED",
                    "DONE",
                    "COMPLETED",
                    "COMPLETE_SUCCESS" -> TaskFlowPhase.TERMINAL

                    else -> TaskFlowPhase.UNKNOWN
                }
            }

            override fun shouldSkip(item: TaskFlowItem): Boolean {
                if (item.type !in SUPPORTED_TRIGGER_TYPES) {
                    val taskKey = buildGreenFinanceTaskKey(item)
                    if (loggedUnsupportedTaskKeys.add(taskKey)) {
                        logInfo(
                            "$flowName[跳过未支持触发类型：${item.title}]" +
                                " taskId=${item.id} sendCampTriggerType=${item.type.ifBlank { "UNKNOWN" }}" +
                                " status=${item.status.ifBlank { "UNKNOWN" }} actionType=${item.actionType.ifBlank { "UNKNOWN" }}"
                        )
                    }
                    return true
                }

                val taskKey = buildGreenFinanceTaskKey(item)
                return when (mapPhase(item)) {
                    TaskFlowPhase.REWARD_READY -> taskKey in receivedTaskKeys
                    TaskFlowPhase.SIGNUP_COMPLETE,
                    TaskFlowPhase.READY_TO_COMPLETE -> taskKey in sentTaskKeys
                    else -> false
                }
            }

            override fun isUnresolvedWhenSkipped(item: TaskFlowItem): Boolean =
                mapPhase(item) != TaskFlowPhase.TERMINAL && !super<TaskFlowAdapter>.isBlacklisted(item)

            override fun receive(item: TaskFlowItem): TaskFlowActionResult {
                return triggerTaskStage(item, "receive", TaskFlowAction.RECEIVE)
            }

            override fun complete(item: TaskFlowItem): TaskFlowActionResult {
                return triggerTaskStage(item, "send", TaskFlowAction.COMPLETE)
            }

            override fun signup(item: TaskFlowItem): TaskFlowActionResult {
                return triggerTaskStage(item, "signup", TaskFlowAction.SIGNUP)
            }

            override fun send(item: TaskFlowItem): TaskFlowActionResult {
                return triggerTaskStage(item, "send", TaskFlowAction.SEND)
            }

            override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
                return "${action.logName}:${buildGreenFinanceTaskKey(item)}:${item.status}:${item.actionType}"
            }

            override fun afterSuccess(
                item: TaskFlowItem,
                action: TaskFlowAction,
                result: TaskFlowActionResult
            ) {
                rememberSuccessfulStage(item, action)
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
                Log.runtime(
                    "$tag.doTask.taskQuery",
                    response.optString("resultDesc").ifBlank { response.toString() }
                )
            }

            override fun logInfo(message: String) {
                Log.greenFinance(message)
            }

            override fun logError(message: String) {
                Log.error(tag, message)
            }

            private fun triggerTaskStage(
                item: TaskFlowItem,
                stageCode: String,
                action: TaskFlowAction
            ): TaskFlowActionResult {
                val response = taskTrigger(item.id, stageCode, appletId)
                if (response.isBlank()) {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        message = "taskTrigger返回空",
                        rpc = "GreenFinanceRpcCall.taskTrigger/$stageCode",
                        detail = greenFinanceActionDetail(item, stageCode),
                        stopCurrentRound = true
                    )
                }

                val result = JsonUtil.parseJSONObject(response)
                if (isGreenFinanceTaskRpcSuccess(result)) {
                    logGreenFinanceStageSuccess(item, action)
                    return TaskFlowActionResult.success()
                }

                return greenFinanceActionFailureResult(
                    response = result,
                    rpc = "GreenFinanceRpcCall.taskTrigger/$stageCode",
                    detail = greenFinanceActionDetail(item, stageCode)
                )
            }

            private fun rememberSuccessfulStage(item: TaskFlowItem, action: TaskFlowAction) {
                val taskKey = buildGreenFinanceTaskKey(item)
                when (action) {
                    TaskFlowAction.SIGNUP -> signedUpTaskKeys.add(taskKey)
                    TaskFlowAction.SEND,
                    TaskFlowAction.COMPLETE -> sentTaskKeys.add(taskKey)
                    TaskFlowAction.RECEIVE -> receivedTaskKeys.add(taskKey)
                }
            }

            private fun logGreenFinanceStageSuccess(item: TaskFlowItem, action: TaskFlowAction) {
                val actionText = when (action) {
                    TaskFlowAction.SIGNUP -> "报名完成"
                    TaskFlowAction.SEND,
                    TaskFlowAction.COMPLETE -> "任务完成"
                    TaskFlowAction.RECEIVE -> "奖励领取"
                }
                Log.greenFinance("$name[${item.title}]$actionText")
            }
        }

        private fun greenFinanceActionFailureResult(
            response: JSONObject,
            rpc: String,
            detail: String
        ): TaskFlowActionResult {
            val code = extractGreenFinanceTaskFailureCode(response)
            val message = extractGreenFinanceTaskFailureMessage(response)
            return TaskFlowActionResult.failure(
                failureType = classifyGreenFinanceTaskFailure(response),
                code = code,
                message = message,
                rpc = rpc,
                raw = response.toString(),
                detail = detail
            )
        }

        private fun greenFinanceActionDetail(item: TaskFlowItem, stageCode: String): String {
            return "sendCampTriggerType=${item.type} actionType=${item.actionType} " +
                "sceneCode=${item.sceneCode} stageCode=$stageCode progress=${item.progress}"
        }

        private fun isGreenFinanceTaskRpcSuccess(response: JSONObject): Boolean {
            return response.optBoolean("success") ||
                response.optBoolean("isSuccess") ||
                response.optString("code") == "100000000" ||
                response.optString("resultCode").equals("SUCCESS", ignoreCase = true)
        }

        private fun classifyGreenFinanceTaskFailure(response: JSONObject): TaskRpcFailureType {
            val code = extractGreenFinanceTaskFailureCode(response)
            val message = extractGreenFinanceTaskFailureMessage(response)
            return when {
                code in setOf("400000030", "400000012") ||
                    containsAny(
                        message,
                        "已领取",
                        "已经领取",
                        "重复领取",
                        "重复领奖",
                        "重复完成",
                        "已完成",
                        "已报名",
                        "已经报名",
                        "重复报名",
                        "任务已完结",
                        "任务已结束"
                    ) -> TaskRpcFailureType.TERMINAL_DONE

                code in setOf("104", "CAMP_TRIGGER_ERROR") ||
                    code.startsWith("100010") ||
                    code.contains("LIMIT", ignoreCase = true) ||
                    containsAny(
                        message,
                        "上限",
                        "限制",
                        "受限",
                        "不可领取",
                        "资格不足",
                        "次数超过限制",
                        "兑完",
                        "风控",
                        "风险",
                        "模板处理中",
                        "奖品已发完",
                        "名额"
                    ) -> TaskRpcFailureType.BUSINESS_LIMIT

                code == "400000040" ||
                    containsAny(message, "不支持rpc调用", "不支持RPC完成") ->
                    TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

                code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                    containsAny(message, "参数错误", "任务ID非法", "模板不存在", "生活记录模板不存在") ->
                    TaskRpcFailureType.NON_RETRYABLE_INVALID

                code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK", "SYSTEM_BUSY", "NETWORK_ERROR") ||
                    containsAny(message, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试") ||
                    isGreenFinanceFailureMarkedRetryable(response) ->
                    TaskRpcFailureType.RETRYABLE_RPC

                else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            }
        }

        private fun extractGreenFinanceTaskFailureCode(response: JSONObject): String {
            return response.optString("resultCode")
                .ifBlank { response.optString("errorCode") }
                .ifBlank { response.optString("code") }
                .ifBlank { response.optString("errCode") }
                .ifBlank { JsonUtil.getValueByPath(response, "result.resultCode") }
                .ifBlank { JsonUtil.getValueByPath(response, "result.errorCode") }
                .ifBlank { JsonUtil.getValueByPath(response, "result.code") }
        }

        private fun extractGreenFinanceTaskFailureMessage(response: JSONObject): String {
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

        private fun isGreenFinanceFailureMarkedRetryable(response: JSONObject): Boolean {
            return listOf("retryable", "retriable", "canRetry").any { key ->
                response.has(key) && response.optBoolean(key, false)
            }
        }

        private fun parseGreenFinanceObject(raw: Any?): JSONObject {
            return when (raw) {
                is JSONObject -> raw
                is String -> JsonUtil.parseJSONObjectOrNull(raw) ?: JSONObject()
                else -> JSONObject()
            }
        }

        private fun extractGreenFinanceTaskTitle(
            taskDetail: JSONObject,
            taskMaterial: JSONObject?,
            morphoDetail: JSONObject,
            taskId: String
        ): String {
            return taskMaterial?.optString("title").orEmpty()
                .ifBlank { morphoDetail.optString("title") }
                .ifBlank { taskDetail.optJSONObject("taskDisplayInfo")?.optString("taskMainTitle").orEmpty() }
                .ifBlank {
                    taskDetail.optJSONObject("taskDisplayInfo")
                        ?.optJSONObject("customInfo")
                        ?.optString("taskMainTitle")
                        .orEmpty()
                }
                .ifBlank { taskDetail.optString("taskTitle") }
                .ifBlank { taskDetail.optString("title") }
                .ifBlank { taskId }
                .trim()
        }

        private fun extractGreenFinanceTaskCurrent(taskDetail: JSONObject): Int? {
            return when {
                taskDetail.has("periodCurrentCompleteNum") -> taskDetail.optInt("periodCurrentCompleteNum")
                taskDetail.has("taskCompleteTimes") -> taskDetail.optInt("taskCompleteTimes")
                else -> null
            }
        }

        private fun extractGreenFinanceTaskLimit(taskDetail: JSONObject): Int? {
            val taskExtProps = taskDetail.optJSONObject("taskExtProps")
            return when {
                taskDetail.has("periodTotalCompleteNum") -> taskDetail.optInt("periodTotalCompleteNum")
                taskDetail.has("periodicTotalNum") -> taskDetail.optInt("periodicTotalNum")
                taskExtProps?.has("taskTotal") == true -> taskExtProps.optString("taskTotal").toIntOrNull()
                else -> null
            }
        }

        private fun buildGreenFinanceTaskProgress(current: Int?, limit: Int?): String {
            return when {
                current != null && limit != null -> "$current/$limit"
                current != null -> current.toString()
                else -> ""
            }
        }

        private fun buildGreenFinanceTaskKey(item: TaskFlowItem): String {
            return item.id.ifBlank { item.title }
        }

        private fun containsAny(text: String, vararg keywords: String): Boolean {
            return keywords.any { text.contains(it, ignoreCase = true) }
        }

        private const val TASK_BLACKLIST_MODULE = "绿色经营"
        private val SUPPORTED_TRIGGER_TYPES = setOf("USER_TRIGGER", "EVENT_TRIGGER")
    }
}


package io.github.aoguai.sesameag.task.antDodo

import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowDecision
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectNeed
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenishResult
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenisher
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.friend.FriendCapabilityRecorder

class AntDodo : ModelTask() {

    private var collectToFriend: BooleanModelField? = null
    private var collectToFriendType: ChoiceModelField? = null
    private var collectToFriendList: FriendSelectionModelField? = null
    private var sendFriendCard: FriendSelectionModelField? = null
    private var useProp: BooleanModelField? = null
    private var usePropCollectTimes7Days: BooleanModelField? = null
    private var usePropCollectHistoryAnimal7Days: BooleanModelField? = null
    private var usePropCollectToFriendTimes7Days: BooleanModelField? = null
    private var autoGenerateBook: BooleanModelField? = null
    private val handledTaskFinishes = LinkedHashSet<String>()
    private val handledTaskAwards = LinkedHashSet<String>()
    private val loggedTaskProgressHints = LinkedHashSet<String>()

    override fun getName(): String = "神奇物种"

    override fun getGroup(): ModelGroup = ModelGroup.DODO

    override fun getIcon(): String = "AntDodo.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            BooleanModelField("collectToFriend", "帮抽卡 | 开启", false).withDesc(
                "开启后按“帮抽卡 | 动作”和“帮抽卡 | 好友列表”帮好友抽神奇物种卡片。"
            ).also { collectToFriend = it }
        )
        modelFields.addField(
            ChoiceModelField(
                "collectToFriendType",
                "帮抽卡 | 动作",
                CollectToFriendType.COLLECT,
                CollectToFriendType.nickNames
            ).withDesc("选择名单解释方式：仅帮选中好友抽卡，或跳过选中好友。需开启“帮抽卡 | 开启”。").also {
                collectToFriendType = it
            }
        )
        modelFields.addField(
            FriendSelectionModelField(
                "collectToFriendList",
                "帮抽卡 | 好友列表"
            ).withDesc("设置帮抽卡规则作用的好友名单。").also { collectToFriendList = it }
        )
        modelFields.addField(
            FriendSelectionModelField(
                "sendFriendCard",
                "赠送卡片 | 好友列表"
            ).withDesc("列表不为空时，会把当前图鉴可赠送的冗余卡片送给列表中的首个有效好友，并始终保留每张卡至少1张。").also {
                sendFriendCard = it
            }
        )
        modelFields.addField(
            BooleanModelField("useProp", "道具 | 使用全部", false).withDesc(
                "自动使用当前可消费的神奇物种道具，对所有支持的道具类型生效。"
            ).also { useProp = it }
        )
        modelFields.addField(
            BooleanModelField("usePropCollectTimes7Days", "道具 | 抽卡道具", false).withDesc(
                "单独开启后仅使用抽卡类道具；开启“道具 | 使用全部”时也会一起生效。"
            ).also { usePropCollectTimes7Days = it }
        )
        modelFields.addField(
            BooleanModelField("usePropCollectHistoryAnimal7Days", "道具 | 抽历史卡道具", false).withDesc(
                "单独开启后仅使用历史卡抽卡道具；开启“道具 | 使用全部”时也会一起生效。"
            ).also { usePropCollectHistoryAnimal7Days = it }
        )
        modelFields.addField(
            BooleanModelField("usePropCollectToFriendTimes7Days", "道具 | 抽好友卡道具", false).withDesc(
                "单独开启后仅使用好友卡抽卡道具；开启“道具 | 使用全部”时也会一起生效。"
            ).also { usePropCollectToFriendTimes7Days = it }
        )
        modelFields.addField(
            BooleanModelField("autoGenerateBook", "图鉴勋章 | 自动合成", false).withDesc(
                "图鉴显示“已集齐”时自动合成对应勋章。"
            ).also { autoGenerateBook = it }
        )
        return modelFields
    }

    override fun check(): Boolean {
        return when {
            TaskCommon.IS_ENERGY_TIME -> {
                Log.dodo("⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，停止执行${getName()}任务！")
                false
            }
            TaskCommon.IS_MODULE_SLEEP_TIME -> {
                Log.dodo("💤 模块休眠时间【${BaseModel.modelSleepTime.value}】停止执行${getName()}任务！")
                false
            }
            else -> true
        }
    }

    override fun runJava() {
        try {
            Log.dodo("执行开始-${getName()}")
            handledTaskFinishes.clear()
            handledTaskAwards.clear()
            loggedTaskProgressHints.clear()
            receiveTaskAward()
            collect()
            if (collectToFriend?.value == true) {
                var friendCollectPasses = 0
                while (friendCollectPasses < 2) {
                    if (!collectToFriend()) {
                        break
                    }
                    friendCollectPasses++
                }
            }
            sendAntDodoCard()
            receiveTaskAward()
            propList()
            if (autoGenerateBook?.value == true) {
                autoGenerateBook()
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "start.run err:")
            Log.printStackTrace(TAG, t)
        } finally {
            Log.dodo("执行结束-${getName()}")
        }
    }

    private fun collect() {
        try {
            val response = AntDodoRpcCall.queryAnimalStatus()
            if (response.isNullOrEmpty()) {
                Log.runtime(TAG, "queryAnimalStatus返回空")
                return
            }
            val jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                val data = jo.getJSONObject("data")
                if (data.getBoolean("collect")) {
                    Log.dodo("神奇物种卡片今日收集完成！")
                } else {
                    collectAnimalCard()
                }
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "AntDodo Collect err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun collectAnimalCard() {
        try {
            val homeResponse = AntDodoRpcCall.homePage()
            if (homeResponse.isNullOrEmpty()) {
                Log.runtime(TAG, "homePage返回空")
                return
            }
            var jo = JSONObject(homeResponse)
            if (ResChecker.checkRes(TAG, jo)) {
                var data = jo.getJSONObject("data")
                val ja = data.getJSONArray("limit")
                var index = -1
                for (i in 0 until ja.length()) {
                    jo = ja.getJSONObject(i)
                    if ("DAILY_COLLECT" == jo.getString("actionCode")) {
                        index = i
                        break
                    }
                }
                if (index >= 0) {
                    val leftFreeQuota = jo.getInt("leftFreeQuota")
                    for (j in 0 until leftFreeQuota) {
                        val collectResponse = AntDodoRpcCall.collect()
                        if (collectResponse.isNullOrEmpty()) {
                            Log.runtime(TAG, "collect返回空")
                            continue
                        }
                        jo = JSONObject(collectResponse)
                        if (ResChecker.checkRes(TAG, jo)) {
                            data = jo.getJSONObject("data")
                            val animal = data.getJSONObject("animal")
                            val ecosystem = animal.getString("ecosystem")
                            val name = animal.getString("name")
                            Log.dodo("神奇物种🦕[$ecosystem]#$name")
                        } else {
                            Log.runtime(TAG, jo.getString("resultDesc"))
                        }
                    }
                }
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "AntDodo CollectAnimalCard err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun receiveTaskAward() {
        try {
            TaskFlowEngine(DodoTaskFlowAdapter(), roundSleepMs = 500L).run()
        } catch (e: JSONException) {
            Log.error(TAG, "JSON解析错误: ${e.message}")
            Log.printStackTrace(TAG, e)
        } catch (t: Throwable) {
            Log.runtime(TAG, "AntDodo ReceiveTaskAward 错误:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun buildTaskKey(sceneCode: String, taskType: String): String {
        return "$sceneCode|$taskType"
    }

    private inner class DodoTaskFlowAdapter : TaskFlowAdapter {
        override val moduleName: String = TASK_BLACKLIST_MODULE
        override val flowName: String = "神奇物种任务"

        override fun query(): JSONObject {
            val response = AntDodoRpcCall.taskList()
            if (response.isNullOrEmpty()) {
                return JSONObject()
                    .put("success", false)
                    .put("resultDesc", "taskList返回空")
            }
            return JSONObject(response)
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return ResChecker.checkRes(TAG, response)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val taskGroupInfoList = response.optJSONObject("data")?.optJSONArray("taskGroupInfoList")
                ?: return emptyList()
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until taskGroupInfoList.length()) {
                val taskGroup = taskGroupInfoList.optJSONObject(i) ?: continue
                val taskInfoList = taskGroup.optJSONArray("taskInfoList") ?: continue
                for (j in 0 until taskInfoList.length()) {
                    val taskInfo = taskInfoList.optJSONObject(j) ?: continue
                    val taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo") ?: continue
                    val bizInfo = parseDodoBizInfo(taskBaseInfo.opt("bizInfo"))
                    val taskType = taskBaseInfo.optString("taskType").trim()
                    if (taskType.isBlank()) {
                        continue
                    }
                    val taskTitle = bizInfo.optString("taskTitle", taskType).trim().ifBlank { taskType }
                    val sceneCode = taskBaseInfo.optString("sceneCode").trim()
                    val taskStatus = taskBaseInfo.optString("taskStatus").trim()
                    val awardCount = bizInfo.optString("awardCount", "1").trim().ifBlank { "1" }
                    val taskProgress = parseDodoTaskProgressInt(taskBaseInfo, "taskProgress")
                    val taskRequire = parseDodoTaskProgressInt(taskBaseInfo, "taskRequire")?.takeIf { it > 0 }
                    val raw = JSONObject()
                        .put("taskGroupId", taskGroup.optString("taskGroupId"))
                        .put("taskInfo", taskInfo)
                        .put("taskBaseInfo", taskBaseInfo)
                        .put("bizInfo", bizInfo)
                        .put("taskKey", buildTaskKey(sceneCode, taskType))
                        .put("awardCount", awardCount)

                    items.add(
                        TaskFlowItem(
                            id = taskType,
                            title = taskTitle,
                            status = taskStatus,
                            type = taskType,
                            sceneCode = sceneCode,
                            actionType = taskBaseInfo.optString("actionType")
                                .ifBlank { bizInfo.optString("actionType") },
                            blacklistKeys = listOf(taskType, taskTitle).filter { it.isNotBlank() },
                            raw = raw,
                            progress = "award=$awardCount progress=${taskProgress ?: 0}/${taskRequire ?: 0}",
                            current = taskProgress,
                            limit = taskRequire
                        )
                    )
                }
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            if (isConsecutiveCollectTask(item.type, item.title) &&
                item.status in setOf(TaskStatus.TODO.name, "WAIT_COMPLETE")
            ) {
                return mapConsecutiveCollectPhase(item)
            }
            return when (item.status) {
                TaskStatus.FINISHED.name,
                "COMPLETE",
                "WAIT_RECEIVE",
                "TO_RECEIVE" -> TaskFlowPhase.REWARD_READY

                TaskStatus.TODO.name,
                "WAIT_COMPLETE" -> if (item.type in BUSINESS_DRIVEN_TASK_TYPES) {
                    TaskFlowPhase.BUSINESS_ACTION
                } else {
                    TaskFlowPhase.READY_TO_COMPLETE
                }

                TaskStatus.RECEIVED.name,
                "HAS_RECEIVED",
                "DONE",
                "COMPLETED" -> TaskFlowPhase.TERMINAL

                else -> TaskFlowPhase.UNKNOWN
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            val taskKey = buildTaskKey(item.sceneCode, item.type)
            return when {
                handledTaskAwards.contains(taskKey) && mapPhase(item) == TaskFlowPhase.REWARD_READY -> true
                handledTaskFinishes.contains(taskKey) && mapPhase(item) == TaskFlowPhase.READY_TO_COMPLETE -> true
                else -> false
            }
        }

        override fun isBlacklisted(item: TaskFlowItem): Boolean {
            if (isConsecutiveCollectTask(item.type, item.title)) {
                return false
            }
            return super<TaskFlowAdapter>.isBlacklisted(item)
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            val response = AntDodoRpcCall.receiveTaskAward(item.sceneCode, item.type)
            if (response.isNullOrEmpty()) {
                return emptyActionResponse("AntDodoRpcCall.receiveTaskAward", item, "receiveTaskAward")
            }
            val result = JSONObject(response)
            if (isDodoTaskRpcSuccess(result)) {
                val awardCount = item.raw?.optString("awardCount", "1") ?: "1"
                Log.dodo("任务奖励🎖️[${item.title}]#${awardCount}个")
                return TaskFlowActionResult.success()
            }
            return dodoActionFailureResult(
                response = result,
                rpc = "AntDodoRpcCall.receiveTaskAward",
                detail = dodoActionDetail(item, "receiveTaskAward")
            )
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val raw = item.raw ?: return missingRawResult(item, "finishTask")
            val taskBaseInfo = raw.optJSONObject("taskBaseInfo") ?: return missingRawResult(item, "finishTask")
            if (shouldMaterializeManualTask(raw, taskBaseInfo)) {
                val groupId = raw.optString("taskGroupId").trim()
                if (groupId.isBlank()) {
                    return missingRawResult(item, "reDecisionTaskOpenGreen")
                }
                val response = AntDodoRpcCall.reDecisionTaskOpenGreen(groupId, item.type)
                if (response.isNullOrEmpty()) {
                    return emptyActionResponse(
                        "AntDodoRpcCall.reDecisionTaskOpenGreen",
                        item,
                        "reDecisionTaskOpenGreen"
                    )
                }
                val result = JSONObject(response)
                if (isDodoTaskRpcSuccess(result)) {
                    Log.dodo("物种任务显化🧾️[${item.title}]")
                    return TaskFlowActionResult.success(refreshAfterAction = true)
                }
                return dodoActionFailureResult(
                    response = result,
                    rpc = "AntDodoRpcCall.reDecisionTaskOpenGreen",
                    detail = dodoActionDetail(item, "reDecisionTaskOpenGreen")
                )
            }
            val bizInfo = raw.optJSONObject("bizInfo") ?: JSONObject()
            val response = finishTodoTask(taskBaseInfo, bizInfo, item.sceneCode, item.type)
            if (response.isNullOrEmpty()) {
                return emptyActionResponse("AntDodoRpcCall.finishTask", item, "finishTask")
            }
            val result = JSONObject(response)
            if (isDodoTaskRpcSuccess(result)) {
                Log.dodo("物种任务🧾️[${item.title}]")
                return TaskFlowActionResult.success()
            }
            return dodoActionFailureResult(
                response = result,
                rpc = "AntDodoRpcCall.finishTask",
                detail = dodoActionDetail(item, "finishTask")
            )
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            val taskKey = buildTaskKey(item.sceneCode, item.type)
            return when (action) {
                TaskFlowAction.RECEIVE -> "receive:$taskKey"
                TaskFlowAction.COMPLETE -> "complete:$taskKey"
                else -> super<TaskFlowAdapter>.actionKey(item, action)
            }
        }

        override fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
            rememberHandledTask(item, action)
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision
        ) {
            if (decision == TaskFlowDecision.MARK_HANDLED ||
                decision == TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN ||
                decision == TaskFlowDecision.BLACKLIST
            ) {
                rememberHandledTask(item, action)
            }
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.error(TAG, "神奇物种任务列表查询失败 raw=$response")
        }

        override fun logInfo(message: String) {
            Log.dodo(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun rememberHandledTask(item: TaskFlowItem, action: TaskFlowAction) {
            val taskKey = buildTaskKey(item.sceneCode, item.type)
            when (action) {
                TaskFlowAction.RECEIVE -> handledTaskAwards.add(taskKey)
                TaskFlowAction.COMPLETE -> handledTaskFinishes.add(taskKey)
                else -> Unit
            }
        }

        private fun emptyActionResponse(
            rpc: String,
            item: TaskFlowItem,
            phase: String
        ): TaskFlowActionResult {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.RETRYABLE_RPC,
                message = "${phase}返回空",
                rpc = rpc,
                detail = dodoActionDetail(item, phase),
                stopCurrentRound = true
            )
        }

        private fun missingRawResult(item: TaskFlowItem, phase: String): TaskFlowActionResult {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "缺少任务原始数据",
                rpc = "DodoTaskFlowAdapter.$phase",
                detail = dodoActionDetail(item, phase)
            )
        }
    }

    private fun dodoActionFailureResult(
        response: JSONObject,
        rpc: String,
        detail: String
    ): TaskFlowActionResult {
        val code = extractDodoTaskFailureCode(response)
        val message = extractDodoTaskFailureMessage(response)
        return TaskFlowActionResult.failure(
            failureType = classifyDodoTaskFailure(response),
            code = code,
            message = message,
            rpc = rpc,
            raw = response.toString(),
            detail = detail
        )
    }

    private fun dodoActionDetail(item: TaskFlowItem, phase: String): String {
        return "taskType=${item.type} sceneCode=${item.sceneCode} action=$phase"
    }

    private fun parseDodoBizInfo(rawBizInfo: Any?): JSONObject {
        return when (rawBizInfo) {
            is JSONObject -> rawBizInfo
            is String -> rawBizInfo.takeIf { it.isNotBlank() }?.let {
                runCatching { JSONObject(it) }.getOrElse { JSONObject() }
            } ?: JSONObject()
            else -> JSONObject()
        }
    }

    private fun isDodoTaskRpcSuccess(response: JSONObject): Boolean {
        return response.optBoolean("success") ||
            response.optBoolean("isSuccess") ||
            response.optString("code") == "100000000" ||
            response.optString("resultCode").equals("SUCCESS", ignoreCase = true)
    }

    private fun classifyDodoTaskFailure(response: JSONObject): TaskRpcFailureType {
        val code = extractDodoTaskFailureCode(response)
        val desc = extractDodoTaskFailureMessage(response)
        return when {
            code in setOf("400000030", "400000012") ||
                containsAny(desc, "已领取", "已经领取", "重复领取", "重复领奖", "重复完成", "已完成", "任务已完结", "任务已结束") ->
                TaskRpcFailureType.TERMINAL_DONE

            containsAny(desc, "权益获取次数超过上限", "上限", "不可领取", "资格不足", "风控", "风险") ||
                code == "CAMP_TRIGGER_ERROR" ||
                code.contains("LIMIT", ignoreCase = true) ->
                TaskRpcFailureType.BUSINESS_LIMIT

            code == "400000040" ||
                containsAny(desc, "不支持rpc调用", "不支持RPC完成") ->
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                containsAny(desc, "参数错误", "任务ID非法", "模板不存在") ->
                TaskRpcFailureType.NON_RETRYABLE_INVALID

            code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK") ||
                containsAny(desc, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试") ||
                isDodoFailureMarkedRetryable(response) ->
                TaskRpcFailureType.RETRYABLE_RPC

            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun extractDodoTaskFailureCode(response: JSONObject): String {
        val code = response.optString("code")
            .ifBlank { response.optString("errorCode") }
            .ifBlank { response.optString("resultCode") }
        return code
    }

    private fun extractDodoTaskFailureMessage(response: JSONObject): String {
        return response.optString("desc")
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("memo") }
            .ifBlank { response.toString() }
    }

    private fun isDodoFailureMarkedRetryable(response: JSONObject): Boolean {
        return listOf("retryable", "retriable", "canRetry").any { key ->
            response.has(key) && response.optBoolean(key, false)
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private fun parseDodoTaskProgressInt(source: JSONObject, key: String): Int? {
        val value = source.opt(key)
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun isConsecutiveCollectTask(taskType: String, taskTitle: String): Boolean {
        return taskType == "CONTINUOUS_COLLECT_TIMES_7" ||
            (taskTitle.contains("连续") && taskTitle.contains("抽卡7天"))
    }

    private fun mapConsecutiveCollectPhase(item: TaskFlowItem): TaskFlowPhase {
        val current = item.current ?: 0
        val limit = item.limit
        val curCycleFinished = item.raw?.optJSONObject("taskInfo")?.optBoolean("curCycleFinished") == true
        val progressMessage = when {
            limit == null -> "缺少有效进度[${item.progress}]，等待服务端刷新"
            current < limit && curCycleFinished -> "今日已完成，连续进度[$current/$limit]，等待后续天数推进"
            current < limit -> "今日未完成，连续进度[$current/$limit]，等待主动抽卡推进"
            else -> "进度已满[$current/$limit]，等待服务端刷新为可领奖状态"
        }
        logDodoTaskProgressOnce("物种任务🧾️[${item.title}]$progressMessage，不调用finishTask")
        return TaskFlowPhase.BUSINESS_ACTION
    }

    private fun logDodoTaskProgressOnce(message: String) {
        if (loggedTaskProgressHints.add(message)) {
            Log.dodo(message)
        }
    }

    private fun shouldMaterializeManualTask(raw: JSONObject, taskBaseInfo: JSONObject): Boolean {
        if (!taskBaseInfo.optBoolean("reDecision")) {
            return false
        }
        if (!parseStringSet(taskBaseInfo.optJSONArray("reDecisionModeList")).contains("MANUAL")) {
            return false
        }
        if (taskBaseInfo.optLong("entityGenerateTime", 0L) > 0L ||
            taskBaseInfo.optLong("entityExpireTime", 0L) > 0L ||
            taskBaseInfo.optString("taskMode").isNotBlank()
        ) {
            return false
        }
        return raw.optString("taskGroupId").isNotBlank()
    }

    private fun parseStringSet(values: JSONArray?): Set<String> {
        if (values == null || values.length() == 0) {
            return emptySet()
        }
        val result = LinkedHashSet<String>()
        for (i in 0 until values.length()) {
            val value = values.optString(i).trim()
            if (value.isNotBlank()) {
                result.add(value)
            }
        }
        return result
    }

    private fun finishTodoTask(
        taskBaseInfo: JSONObject,
        bizInfo: JSONObject,
        sceneCode: String,
        taskType: String
    ): String {
        if (taskType.startsWith("GAME_") || taskType.contains("WZDAOLIU")) {
            extractDodoGameAppId(taskBaseInfo, bizInfo)?.let { appId ->
                AntDodoRpcCall.clickGame(appId)
            }
        }
        return AntDodoRpcCall.finishTask(sceneCode, taskType)
    }

    private fun extractDodoGameAppId(taskBaseInfo: JSONObject, bizInfo: JSONObject): String? {
        val taskJumpUrl = bizInfo.optString("taskJumpUrl")
        val appIdFromUrl = Regex("appId=(\\d+)").find(taskJumpUrl)?.groupValues?.getOrNull(1)
        if (!appIdFromUrl.isNullOrBlank()) {
            return appIdFromUrl
        }
        return JSONObject(taskBaseInfo.optString("prodPlayParam", "{}"))
            .optJSONObject("taskCategorization")
            ?.optJSONObject("categorizationParamModel")
            ?.optString("game_id")
            ?.takeIf { it.isNotBlank() }
    }

    private fun propList(allowReplenish: Boolean = true) {
        try {
            th@ while (!Thread.currentThread().isInterrupted) {
                val response = AntDodoRpcCall.propList()
                if (response.isNullOrEmpty()) {
                    Log.runtime(TAG, "propList返回空")
                    return
                }
                val jo = JSONObject(response)
                if (ResChecker.checkRes(TAG, jo)) {
                    val propList = jo.getJSONObject("data").optJSONArray("propList")
                    if (propList == null || propList.length() == 0) {
                        if (replenishDodoPropIfMissing(
                                allowReplenish = allowReplenish,
                                historyMissing = true,
                                friendMissing = true
                            )
                        ) {
                            return
                        }
                        Log.dodo("神奇物种道具跳过：未找到可使用的道具")
                        return
                    }
                    var hasHistoryProp = false
                    var hasFriendProp = false
                    var usedEnabledProp = false
                    for (i in 0 until propList.length()) {
                        val prop = propList.getJSONObject(i)
                        val propType = prop.optString("propType")
                        val propName = prop.optJSONObject("propConfig")?.optString("propName")
                            ?.takeIf { it.isNotBlank() } ?: propType
                        val propKind = resolvePropKind(propType)
                        val propIdList = prop.optJSONArray("propIdList")
                        val holdsNum = prop.optInt("holdsNum", propIdList?.length() ?: 0)
                        val hasUsablePropId = propIdList != null && propIdList.length() > 0
                        if (propKind == DodoPropKind.HISTORY_CARD && holdsNum > 0 && hasUsablePropId) {
                            hasHistoryProp = true
                        }
                        if (propKind == DodoPropKind.FRIEND_CARD && holdsNum > 0 && hasUsablePropId) {
                            hasFriendProp = true
                        }
                        if (!isUsePropType(propType)) {
                            Log.dodo("神奇物种道具跳过[$propName]：配置未开启")
                            continue
                        }
                        if (propIdList == null || propIdList.length() == 0) {
                            Log.runtime(TAG, "神奇物种道具[$propName]缺少propIdList")
                            continue
                        }
                        val propId = propIdList.getString(0)
                        val consumeTargetResult = resolvePropConsumeTarget(propType)
                        val consumeTarget = consumeTargetResult?.target
                        if (isUniversalCardProp(propType)) {
                            if (consumeTargetResult == null || !consumeTargetResult.querySucceeded) {
                                Log.dodo("神奇物种道具跳过[$propName]：目标卡查询失败")
                                continue
                            }
                            if (consumeTarget == null) {
                                Log.dodo("神奇物种道具跳过[$propName]：未找到可兑换的目标卡片")
                                continue
                            }
                        }
                        val consumeResponse = AntDodoRpcCall.consumeProp(propId, propType, consumeTarget?.animalId)
                        if (consumeResponse.isNullOrEmpty()) {
                            Log.runtime(TAG, "consumeProp返回空")
                            continue
                        }
                        val joConsume = JSONObject(consumeResponse)
                        if (!ResChecker.checkRes(TAG, joConsume)) {
                            Log.dodo("神奇物种道具使用失败[$propName]：${joConsume.optString("resultDesc", "未知错误")}")
                            Log.runtime(joConsume.toString())
                            continue
                        }
                        val useResult = joConsume.optJSONObject("data")?.optJSONObject("useResult")
                        val animal = useResult?.optJSONObject("animal")
                        if (animal != null) {
                            val ecosystem = animal.optString("ecosystem")
                            val name = animal.optString("name")
                            Log.dodo("使用道具🎭[$propName]#${formatAnimalDisplayName(ecosystem, name)}")
                        } else {
                            Log.dodo("使用道具🎭[$propName]")
                        }
                        usedEnabledProp = true
                        logPropRefreshState(propType, propName, consumeTarget, animal)
                        GlobalThreadPools.sleepCompat(300)
                        if (holdsNum > 1) {
                            continue@th
                        }
                    }
                    if (!usedEnabledProp && replenishDodoPropIfMissing(
                            allowReplenish = allowReplenish,
                            historyMissing = !hasHistoryProp,
                            friendMissing = !hasFriendProp
                        )
                    ) {
                        return
                    }
                }
                break
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "AntDodo PropList err:")
            Log.printStackTrace(TAG, th)
        }
    }

    private fun replenishDodoPropIfMissing(
        allowReplenish: Boolean,
        historyMissing: Boolean,
        friendMissing: Boolean
    ): Boolean {
        if (!allowReplenish) {
            return false
        }
        var exchanged = false
        if (historyMissing && isUsePropType("COLLECT_HISTORY_ANIMAL_7_DAYS")) {
            val result = ExchangeReplenisher.replenish(
                need = ExchangeEffectNeed.DODO_HISTORY_CARD,
                reason = "神奇物种抽历史卡机会不足",
                maxCount = 1
            ) {
                AntDodoRpcCall.propList()
            }
            exchanged = exchanged || result == ExchangeReplenishResult.EXCHANGED
        }
        if (friendMissing && isUsePropType("COLLECT_TO_FRIEND_TIMES_7_DAYS")) {
            val result = ExchangeReplenisher.replenish(
                need = ExchangeEffectNeed.DODO_FRIEND_CARD,
                reason = "神奇物种抽好友卡机会不足",
                maxCount = 1
            ) {
                AntDodoRpcCall.propList()
            }
            exchanged = exchanged || result == ExchangeReplenishResult.EXCHANGED
        }
        if (exchanged) {
            Log.dodo("神奇物种道具已触发缺货补兑，重新查询道具列表")
            propList(allowReplenish = false)
        }
        return exchanged
    }

    private fun isUsePropType(propType: String): Boolean {
        var usePropType = useProp?.value ?: false
        usePropType = when (resolvePropKind(propType)) {
            DodoPropKind.CURRENT_BOOK_DRAW,
            DodoPropKind.UNIVERSAL_CARD -> usePropType || (usePropCollectTimes7Days?.value ?: false)
            DodoPropKind.HISTORY_CARD -> usePropType || (usePropCollectHistoryAnimal7Days?.value ?: false)
            DodoPropKind.FRIEND_CARD -> usePropType || (usePropCollectToFriendTimes7Days?.value ?: false)
            DodoPropKind.OTHER -> usePropType
        }
        return usePropType
    }

    private fun isUniversalCardProp(propType: String): Boolean {
        return resolvePropKind(propType) == DodoPropKind.UNIVERSAL_CARD
    }

    private fun resolvePropKind(propType: String): DodoPropKind {
        return when (propType) {
            "COLLECT_TIMES_7_DAYS" -> DodoPropKind.CURRENT_BOOK_DRAW
            "UNIVERSAL_CARD_7_DAYS" -> DodoPropKind.UNIVERSAL_CARD
            "COLLECT_HISTORY_ANIMAL_7_DAYS" -> DodoPropKind.HISTORY_CARD
            "COLLECT_TO_FRIEND_TIMES_7_DAYS" -> DodoPropKind.FRIEND_CARD
            else -> DodoPropKind.OTHER
        }
    }

    private fun resolvePropConsumeTarget(propType: String): PropTargetQueryResult? {
        return when (resolvePropKind(propType)) {
            DodoPropKind.UNIVERSAL_CARD -> resolveUniversalCardTarget()
            else -> null
        }
    }

    private fun resolveUniversalCardTarget(): PropTargetQueryResult {
        val currentBookTarget = queryCurrentBookUniversalCardTarget()
        if (!currentBookTarget.querySucceeded) {
            return currentBookTarget
        }
        currentBookTarget.target?.let { return PropTargetQueryResult(it, true) }
        return queryHistoricalBookUniversalCardTarget()
    }

    private fun queryCurrentBookUniversalCardTarget(): PropTargetQueryResult {
        return try {
            val response = AntDodoRpcCall.homePage()
            if (response.isNullOrEmpty()) {
                Log.runtime(TAG, "万能卡目标查询homePage返回空")
                return PropTargetQueryResult(null, false)
            }
            val jo = JSONObject(response)
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.runtime(TAG, "万能卡目标查询homePage失败：${jo.optString("resultDesc")}")
                return PropTargetQueryResult(null, false)
            }
            val data = jo.getJSONObject("data")
            val animalBook = data.optJSONObject("animalBook")
            if (animalBook?.optBoolean("stopAnimalCirculation") == true) {
                return PropTargetQueryResult(null, true)
            }
            val currentBookId = animalBook?.optString("bookId").orEmpty()
            PropTargetQueryResult(
                findMissingAnimalTarget(data.optJSONArray("curCollection"), currentBookId),
                true
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryCurrentBookUniversalCardTarget err:", t)
            PropTargetQueryResult(null, false)
        }
    }

    private fun queryHistoricalBookUniversalCardTarget(): PropTargetQueryResult {
        return try {
            var hasMore: Boolean
            var pageStart = 0
            var queryFailed = false
            do {
                val response = AntDodoRpcCall.queryBookList(9, pageStart)
                if (response.isNullOrEmpty()) {
                    Log.runtime(TAG, "万能卡目标查询queryBookList返回空")
                    return PropTargetQueryResult(null, false)
                }
                val jo = JSONObject(response)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.runtime(TAG, "万能卡目标查询queryBookList失败：${jo.optString("resultDesc")}")
                    return PropTargetQueryResult(null, false)
                }
                val data = jo.getJSONObject("data")
                hasMore = data.optBoolean("hasMore")
                pageStart += 9
                val bookForUserList = data.optJSONArray("bookForUserList") ?: break
                for (i in 0 until bookForUserList.length()) {
                    val bookForUser = bookForUserList.optJSONObject(i) ?: continue
                    val animalBookResult = bookForUser.optJSONObject("animalBookResult")
                    if (animalBookResult?.optBoolean("stopAnimalCirculation") == true) {
                        continue
                    }
                    val bookId = animalBookResult?.optString("bookId").orEmpty()
                    if (bookId.isBlank()) {
                        continue
                    }
                    val targetResult = queryBookUniversalCardTarget(bookId)
                    if (!targetResult.querySucceeded) {
                        queryFailed = true
                        continue
                    }
                    targetResult.target?.let { return PropTargetQueryResult(it, true) }
                }
            } while (hasMore && !Thread.currentThread().isInterrupted)
            if (queryFailed) {
                PropTargetQueryResult(null, false)
            } else {
                PropTargetQueryResult(null, true)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryHistoricalBookUniversalCardTarget err:", t)
            PropTargetQueryResult(null, false)
        }
    }

    private fun queryBookUniversalCardTarget(bookId: String): PropTargetQueryResult {
        val response = AntDodoRpcCall.queryBookInfo(bookId)
        if (response.isNullOrEmpty()) {
            Log.runtime(TAG, "万能卡目标查询queryBookInfo返回空，bookId=$bookId")
            return PropTargetQueryResult(null, false)
        }
        val jo = JSONObject(response)
        if (!ResChecker.checkRes(TAG, jo)) {
            Log.runtime(TAG, "万能卡目标查询queryBookInfo失败，bookId=$bookId：${jo.optString("resultDesc")}")
            return PropTargetQueryResult(null, false)
        }
        val data = jo.getJSONObject("data")
        if (data.optJSONObject("animalBookResult")?.optBoolean("stopAnimalCirculation") == true) {
            return PropTargetQueryResult(null, true)
        }
        return PropTargetQueryResult(
            findMissingAnimalTarget(data.optJSONArray("animalForUserList"), bookId),
            true
        )
    }

    private fun findMissingAnimalTarget(collection: JSONArray?, fallbackBookId: String): PropConsumeTarget? {
        if (collection == null) {
            return null
        }
        for (i in 0 until collection.length()) {
            val item = collection.optJSONObject(i) ?: continue
            val collectDetail = item.optJSONObject("collectDetail")
            if (!isMissingCurrentAnimalCard(collectDetail)) {
                continue
            }
            val animal = item.optJSONObject("animal") ?: continue
            val animalId = animal.optString("animalId")
            val bookId = animal.optString("bookId").ifBlank { fallbackBookId }
            if (animalId.isBlank() || bookId.isBlank()) {
                continue
            }
            return PropConsumeTarget(
                bookId = bookId,
                animalId = animalId,
                ecosystem = animal.optString("ecosystem"),
                name = animal.optString("name")
            )
        }
        return null
    }

    private fun hasCurrentAnimalCard(collectDetail: JSONObject?): Boolean {
        if (collectDetail == null) {
            return false
        }
        return collectDetail.optBoolean("collect") ||
            collectDetail.optInt("count", 0) > 0
    }

    private fun isMissingCurrentAnimalCard(collectDetail: JSONObject?): Boolean {
        return !hasCurrentAnimalCard(collectDetail) &&
            collectDetail?.optBoolean("hasGeneratedBookMedal") != true
    }

    private fun hasAnimalCardForBookMedal(collectDetail: JSONObject?): Boolean {
        return hasCurrentAnimalCard(collectDetail)
    }

    private fun logPropRefreshState(
        propType: String,
        propName: String,
        consumeTarget: PropConsumeTarget?,
        usedAnimal: JSONObject?
    ) {
        val refreshParts = mutableListOf<String>()
        queryPropHoldNum(propType)?.let {
            refreshParts.add("剩余${it}张")
        }
        val verifyBookId = usedAnimal?.optString("bookId").orEmpty().ifBlank { consumeTarget?.bookId.orEmpty() }
        val verifyAnimalId = usedAnimal?.optString("animalId").orEmpty().ifBlank { consumeTarget?.animalId.orEmpty() }
        if (verifyBookId.isNotBlank() && verifyAnimalId.isNotBlank()) {
            when (queryAnimalCollectedState(verifyBookId, verifyAnimalId)) {
                true -> refreshParts.add("目标卡已入库")
                false -> refreshParts.add("目标卡状态未确认")
                null -> Log.runtime(TAG, "神奇物种道具刷新[$propName]：目标卡查询失败")
            }
        }
        if (refreshParts.isNotEmpty()) {
            Log.dodo("神奇物种道具刷新[$propName]：${refreshParts.joinToString("，")}")
        }
    }

    private fun queryPropHoldNum(propType: String): Int? {
        return try {
            val response = AntDodoRpcCall.propList()
            if (response.isNullOrEmpty()) {
                Log.runtime(TAG, "道具刷新propList返回空")
                return null
            }
            val jo = JSONObject(response)
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.runtime(TAG, "道具刷新propList失败：${jo.optString("resultDesc")}")
                return null
            }
            val propList = jo.getJSONObject("data").optJSONArray("propList") ?: return 0
            var holdsNum = 0
            val targetKind = resolvePropKind(propType)
            for (i in 0 until propList.length()) {
                val prop = propList.optJSONObject(i) ?: continue
                val currentPropType = prop.optString("propType")
                when (targetKind) {
                    DodoPropKind.OTHER -> if (currentPropType != propType) {
                        continue
                    }
                    else -> if (resolvePropKind(currentPropType) != targetKind) {
                        continue
                    }
                }
                val propIdList = prop.optJSONArray("propIdList")
                holdsNum += prop.optInt("holdsNum", propIdList?.length() ?: 0)
            }
            holdsNum
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryPropHoldNum err:", t)
            null
        }
    }

    private fun queryAnimalCollectedState(bookId: String, animalId: String): Boolean? {
        return try {
            val response = AntDodoRpcCall.queryBookInfo(bookId)
            if (response.isNullOrEmpty()) {
                Log.runtime(TAG, "目标卡刷新queryBookInfo返回空，bookId=$bookId")
                return null
            }
            val jo = JSONObject(response)
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.runtime(TAG, "目标卡刷新queryBookInfo失败，bookId=$bookId：${jo.optString("resultDesc")}")
                return null
            }
            val animalForUserList = jo.getJSONObject("data").optJSONArray("animalForUserList") ?: return null
            for (i in 0 until animalForUserList.length()) {
                val item = animalForUserList.optJSONObject(i) ?: continue
                val currentAnimalId = item.optJSONObject("animal")?.optString("animalId").orEmpty()
                if (currentAnimalId == animalId) {
                    return hasCurrentAnimalCard(item.optJSONObject("collectDetail"))
                }
            }
            false
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryAnimalCollectedState err:", t)
            null
        }
    }

    private fun formatAnimalDisplayName(ecosystem: String, name: String): String {
        return when {
            ecosystem.isBlank() && name.isBlank() -> "未知物种"
            ecosystem.isBlank() -> name
            name.isBlank() -> ecosystem
            else -> "$ecosystem-$name"
        }
    }

    private fun resolveSendFriendCardTarget(): String? {
        val configuredUsers = sendFriendCard?.resolvedIds() ?: emptySet()
        if (configuredUsers.isEmpty()) {
            return null
        }
        val availableFriends = queryDodoAvailableFriendIds()
        if (availableFriends.isEmpty()) {
            return null
        }
        for (safeUserId in configuredUsers) {
            if (FriendGuard.shouldSkipFriend(safeUserId, TAG, "神奇物种送卡")) {
                continue
            }
            if (availableFriends.contains(safeUserId)) {
                return safeUserId
            }
            Log.dodo("神奇物种送卡跳过[${UserMap.getMaskName(safeUserId) ?: safeUserId}]：不在当前可赠送好友列表")
        }
        return null
    }

    private fun queryDodoAvailableFriendIds(): Set<String> {
        return try {
            val response = AntDodoRpcCall.queryFriend()
            if (response.isNullOrEmpty()) {
                emptySet()
            } else {
                val jo = JSONObject(response)
                if (!ResChecker.checkRes(TAG, jo)) {
                    emptySet()
                } else {
                    val friendList = jo.getJSONObject("data").optJSONArray("friends") ?: return emptySet()
                    val availableFriends = LinkedHashSet<String>()
                    for (i in 0 until friendList.length()) {
                        val userId = friendList.optJSONObject(i)?.optString("userId").orEmpty()
                        if (userId.isNotBlank() && !FriendGuard.shouldSkipFriend(userId, TAG, "神奇物种好友校验")) {
                            availableFriends.add(userId)
                            FriendCapabilityRecorder.record(userId, "DODO", FriendCapabilityState.OPEN, "AntDodo.queryFriend")
                        }
                    }
                    availableFriends
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryDodoAvailableFriendIds err:", t)
            emptySet()
        }
    }

    private fun sendAntDodoCard() {
        val targetUser = resolveSendFriendCardTarget() ?: return
        try {
            val homeResponse = AntDodoRpcCall.homePage()
            if (homeResponse.isNullOrEmpty()) {
                Log.runtime(TAG, "送卡homePage返回空")
                return
            }
            val home = JSONObject(homeResponse)
            if (!ResChecker.checkRes(TAG, home)) {
                Log.runtime(TAG, home.optString("resultDesc"))
                return
            }
            val animalBook = home.optJSONObject("data")?.optJSONObject("animalBook") ?: return
            if (animalBook.optBoolean("stopAnimalCirculation")) {
                return
            }
            val bookId = animalBook.optString("bookId")
            if (bookId.isBlank()) {
                return
            }
            val response = AntDodoRpcCall.queryBookInfo(bookId)
            if (response.isNullOrEmpty()) {
                Log.runtime(TAG, "queryBookInfo返回空")
                return
            }
            val jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                val animalForUserList = jo.getJSONObject("data").optJSONArray("animalForUserList")
                for (i in 0 until (animalForUserList?.length() ?: 0)) {
                    val animalForUser = animalForUserList!!.getJSONObject(i)
                    val count = animalForUser.optJSONObject("collectDetail")?.optInt("count", 0) ?: 0
                    if (count <= 1) {
                        continue
                    }
                    val animal = animalForUser.getJSONObject("animal")
                    for (j in 1 until count) {
                        sendCard(animal, targetUser)
                        GlobalThreadPools.sleepCompat(500L)
                    }
                }
            } else {
                Log.runtime(TAG, jo.optString("resultDesc"))
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "AntDodo SendAntDodoCard err:")
            Log.printStackTrace(TAG, th)
        }
    }

    private fun sendCard(animal: JSONObject, targetUser: String) {
        try {
            if (FriendGuard.shouldSkipFriend(targetUser, TAG, "神奇物种送卡")) {
                return
            }
            val animalId = animal.getString("animalId")
            val ecosystem = animal.getString("ecosystem")
            val name = animal.getString("name")
            val socialResponse = AntDodoRpcCall.social(animalId, targetUser)
            if (socialResponse.isNullOrEmpty()) {
                Log.runtime(TAG, "social返回空")
                return
            }
            val jo = JSONObject(socialResponse)
            if (ResChecker.checkRes(TAG, jo)) {
                Log.dodo("赠送卡片🦕[${UserMap.getMaskName(targetUser) ?: targetUser}]#$ecosystem-$name")
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "AntDodo SendCard err:")
            Log.printStackTrace(TAG, th)
        }
    }

    private fun collectToFriend(): Boolean {
        try {
            val queryResponse = AntDodoRpcCall.queryFriend()
            if (queryResponse.isNullOrEmpty()) {
                Log.runtime(TAG, "queryFriend返回空")
                return false
            }
            var jo = JSONObject(queryResponse)
            if (ResChecker.checkRes(TAG, jo)) {
                var handled = false
                var count = 0
                val limitList = jo.getJSONObject("data").getJSONObject("extend").getJSONArray("limit")
                for (i in 0 until limitList.length()) {
                    val limit = limitList.getJSONObject(i)
                    if (limit.getString("actionCode") == "COLLECT_TO_FRIEND") {
                        if (limit.getLong("startTime") > System.currentTimeMillis()) {
                            return false
                        }
                        count = limit.getInt("leftLimit")
                        break
                    }
                }
                val friendList = jo.getJSONObject("data").getJSONArray("friends")
                for (i in 0 until friendList.length()) {
                    if (count <= 0) break
                    val friend = friendList.getJSONObject(i)
                    if (friend.getBoolean("dailyCollect")) {
                        continue
                    }
                    val useId = friend.getString("userId")
                    if (FriendGuard.shouldSkipFriend(useId, TAG, "神奇物种帮抽卡")) {
                        continue
                    }
                    var isCollectToFriend = collectToFriendList?.contains(useId) == true
                    if (collectToFriendType?.value == CollectToFriendType.DONT_COLLECT) {
                        isCollectToFriend = !isCollectToFriend
                    }
                    if (!isCollectToFriend) {
                        continue
                    }
                    val collectFriendResponse = AntDodoRpcCall.collect(useId)
                    if (collectFriendResponse.isNullOrEmpty()) {
                        Log.runtime(TAG, "collect(friend)返回空")
                        continue
                    }
                    jo = JSONObject(collectFriendResponse)
                    if (ResChecker.checkRes(TAG, jo)) {
                        val collectData = jo.getJSONObject("data")
                        val animal = collectData.getJSONObject("animal")
                        val ecosystem = animal.getString("ecosystem")
                        val name = animal.getString("name")
                        val userName = UserMap.getMaskName(useId) ?: useId
                        Log.dodo("神奇物种🦕帮好友[$userName]抽卡[$ecosystem]#$name")
                        handleFriendExchange(useId, collectData)
                        count--
                        handled = true
                    } else if (!ResChecker.isSilentFailure(jo)) {
                        val message = jo.optString("resultDesc").ifBlank {
                            jo.optString("desc", "帮好友抽卡失败")
                        }
                        Log.runtime(TAG, message)
                    }
                }
                return handled
            } else if (!ResChecker.isSilentFailure(jo)) {
                val message = jo.optString("resultDesc").ifBlank {
                    jo.optString("desc", "查询神奇物种好友列表失败")
                }
                Log.runtime(TAG, message)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "AntDodo CollectHelpFriend err:")
            Log.printStackTrace(TAG, t)
        }
        return false
    }

    private fun handleFriendExchange(targetUserId: String, collectData: JSONObject) {
        val animal = collectData.optJSONObject("animal") ?: return
        val targetAnimalId = animal.optString("animalId")
        if (targetAnimalId.isBlank()) {
            return
        }
        val collectDetail = collectData.optJSONObject("collectDetail")
        if (!isMissingCurrentAnimalCard(collectDetail)) {
            giveUpFriendExchange(targetUserId, "已有目标卡或当前勋章已完成，保留重复卡")
            return
        }
        val notifyResponse = AntDodoRpcCall.socialExchangeMsg(targetAnimalId, targetUserId)
        if (notifyResponse.isNullOrEmpty()) {
            Log.runtime(TAG, "social(EXCHANGE_MSG)返回空")
            return
        }
        val notifyResult = JSONObject(notifyResponse)
        if (!ResChecker.checkRes(TAG, notifyResult)) {
            logDodoFriendBusinessFailure("通知交换", targetUserId, notifyResult)
            return
        }
        val candidateResult = queryExchangeCardCandidate(targetAnimalId)
        if (!candidateResult.querySucceeded) {
            Log.runtime(TAG, "神奇物种好友换卡[${UserMap.getMaskName(targetUserId) ?: targetUserId}]：查询可交换卡片失败")
            return
        }
        val candidate = candidateResult.candidate
        if (candidate == null) {
            giveUpFriendExchange(targetUserId, "没有可交换的冗余卡")
            return
        }
        val exchangeResponse = AntDodoRpcCall.exchange(candidate.animalId, targetAnimalId, targetUserId)
        if (exchangeResponse.isNullOrEmpty()) {
            Log.runtime(TAG, "exchange返回空")
            return
        }
        val exchangeResult = JSONObject(exchangeResponse)
        if (ResChecker.checkRes(TAG, exchangeResult)) {
            val targetAnimal = exchangeResult.optJSONObject("data")?.optJSONObject("animal") ?: animal
            Log.dodo(
                "神奇物种🦕帮好友换卡[${UserMap.getMaskName(targetUserId) ?: targetUserId}]#${candidate.displayName}→${
                    formatAnimalDisplayName(
                        targetAnimal.optString("ecosystem"),
                        targetAnimal.optString("name")
                    )
                }"
            )
            return
        }
        logDodoFriendBusinessFailure("交换卡片", targetUserId, exchangeResult)
    }

    private fun queryExchangeCardCandidate(targetAnimalId: String): ExchangeCardCandidateQueryResult {
        return try {
            var pageStart: Any = 0
            do {
                val response = AntDodoRpcCall.queryMyCollection(targetAnimalId, 18, pageStart)
                if (response.isNullOrEmpty()) {
                    Log.runtime(TAG, "queryMyCollection返回空")
                    return ExchangeCardCandidateQueryResult(null, false)
                }
                val jo = JSONObject(response)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.runtime(TAG, "queryMyCollection失败：${jo.optString("resultDesc")}")
                    return ExchangeCardCandidateQueryResult(null, false)
                }
                val data = jo.optJSONObject("data") ?: return ExchangeCardCandidateQueryResult(null, true)
                val animalCollectionInfoList = data.optJSONArray("animalCollectionInfoList")
                for (i in 0 until (animalCollectionInfoList?.length() ?: 0)) {
                    val animalCollectionInfo = animalCollectionInfoList!!.optJSONObject(i) ?: continue
                    val animal = animalCollectionInfo.optJSONObject("animal") ?: continue
                    val animalId = animal.optString("animalId")
                    val count = animalCollectionInfo.optJSONObject("collectDetail")?.optInt("count", 0) ?: 0
                    if (animalId.isBlank() || animalId == targetAnimalId || count <= 1) {
                        continue
                    }
                    return ExchangeCardCandidateQueryResult(
                        ExchangeCardCandidate(
                            animalId = animalId,
                            displayName = formatAnimalDisplayName(
                                animal.optString("ecosystem"),
                                animal.optString("name")
                            )
                        ),
                        true
                    )
                }
                if (!data.optBoolean("hasMore")) {
                    break
                }
                val nextPageStart = data.opt("nextPageStart")
                if (nextPageStart == null || nextPageStart == JSONObject.NULL) {
                    break
                }
                pageStart = nextPageStart
            } while (!Thread.currentThread().isInterrupted)
            ExchangeCardCandidateQueryResult(null, true)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryExchangeCardCandidate err:", t)
            ExchangeCardCandidateQueryResult(null, false)
        }
    }

    private fun giveUpFriendExchange(targetUserId: String, reason: String) {
        val response = AntDodoRpcCall.socialGiveUpExchange(targetUserId)
        if (response.isNullOrEmpty()) {
            Log.runtime(TAG, "social(GIVE_UP_EXCHANGE)返回空")
            return
        }
        val jo = JSONObject(response)
        if (ResChecker.checkRes(TAG, jo)) {
            Log.dodo("神奇物种好友换卡放弃[${UserMap.getMaskName(targetUserId) ?: targetUserId}]：$reason")
            return
        }
        logDodoFriendBusinessFailure("放弃交换", targetUserId, jo)
    }

    private fun logDodoFriendBusinessFailure(action: String, targetUserId: String, response: JSONObject) {
        val code = extractDodoTaskFailureCode(response)
        val message = extractDodoTaskFailureMessage(response)
        val userName = UserMap.getMaskName(targetUserId) ?: targetUserId
        when (classifyDodoFriendBusinessFailure(response)) {
            DodoBusinessFailureKind.TERMINAL -> {
                Log.dodo(
                    "神奇物种好友换卡[$userName]${action}跳过：$message${
                        if (code.isBlank()) "" else "($code)"
                    }"
                )
            }

            DodoBusinessFailureKind.RETRYABLE -> {
                Log.runtime(TAG, "神奇物种好友换卡[$userName]${action}待重试：$message${if (code.isBlank()) "" else "($code)"}")
            }

            DodoBusinessFailureKind.UNKNOWN -> {
                Log.runtime(TAG, "神奇物种好友换卡[$userName]${action}异常：$message${if (code.isBlank()) "" else "($code)"}")
            }
        }
    }

    private fun classifyDodoFriendBusinessFailure(response: JSONObject): DodoBusinessFailureKind {
        val code = extractDodoTaskFailureCode(response)
        val desc = extractDodoTaskFailureMessage(response)
        return when {
            code in setOf(
                "EXCHANGED",
                "NO_OWNER_ANIMAL",
                "NO_TARGET_OWNER_ANIMAL",
                "EXCHANGE_ANIMAL_EQUAL",
                "EXCHANGE_NOT_YOU_COLLECT",
                "TIME_LIMIT_EXCEED_7D",
                "EXCHANGE_NOT_START",
                "NOT_FRIEND",
                "FRIEND_COLLECTED_LIMIT",
                "COLLECT_TO_FRIEND_LIMIT",
                "COLLECT_LIMIT_TO_OTHER",
                "NOT_START",
                "TARGET_USER_NOT_V2",
                "PRODUCT_NOT_OPEN",
                "DISABLE_ANIMAL_CIRCULATION",
                "NO_ANIMAL_BOOK_CAN_COLLECT",
                "OTHER_RECEIVE",
                "YOURSELF_RECEIVE"
            ) ||
                containsAny(
                    desc,
                    "已经交换",
                    "你还没有这张物种卡片",
                    "对方不拥有该物种卡",
                    "交换的物种卡不能相同",
                    "交换时间",
                    "链接超过7天",
                    "功能暂未开放",
                    "版权限制",
                    "该物种卡已被其他人领取",
                    "该物种卡已被你领取"
                ) -> DodoBusinessFailureKind.TERMINAL

            code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "SYSTEM_ERROR") ||
                containsAny(desc, "系统出错", "系统繁忙", "稍后", "重试", "人数过多") ||
                isDodoFailureMarkedRetryable(response) -> DodoBusinessFailureKind.RETRYABLE

            else -> DodoBusinessFailureKind.UNKNOWN
        }
    }

    private fun autoGenerateBook() {
        try {
            val generatedBookIds = LinkedHashSet<String>()
            generateCurrentBookIfReminded()?.let { generatedBookIds.add(it) }
            var hasMore: Boolean
            var pageStart = 0
            do {
                if (Thread.currentThread().isInterrupted) {
                    break
                }
                val bookListResponse = AntDodoRpcCall.queryBookList(9, pageStart)
                if (bookListResponse.isNullOrEmpty()) {
                    Log.runtime(TAG, "queryBookList返回空")
                    break
                }
                var jo = JSONObject(bookListResponse)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.runtime(TAG, "queryBookList失败：${jo.optString("resultDesc", jo.toString())}")
                    break
                }
                jo = jo.getJSONObject("data")
                hasMore = jo.optBoolean("hasMore")
                pageStart += 9
                val bookForUserList = jo.optJSONArray("bookForUserList") ?: break
                for (i in 0 until bookForUserList.length()) {
                    val bookForUser = bookForUserList.optJSONObject(i) ?: continue
                    val animalBookResult = bookForUser.optJSONObject("animalBookResult") ?: continue
                    if (animalBookResult.optBoolean("stopAnimalCirculation")) {
                        continue
                    }
                    val bookId = animalBookResult.optString("bookId")
                    if (bookId.isBlank() || generatedBookIds.contains(bookId)) {
                        continue
                    }
                    if (!isBookReadyForMedal(bookForUser, bookId)) {
                        continue
                    }
                    val ecosystem = animalBookResult.optString("ecosystem")
                    if (generateBookMedal(bookId, ecosystem)) {
                        generatedBookIds.add(bookId)
                    }
                }
            } while (hasMore && !Thread.currentThread().isInterrupted)
        } catch (t: Throwable) {
            Log.runtime(TAG, "generateBookMedal err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun generateCurrentBookIfReminded(): String? {
        val homeResponse = AntDodoRpcCall.homePage()
        if (homeResponse.isNullOrEmpty()) {
            Log.runtime(TAG, "自动合成图鉴homePage返回空")
            return null
        }
        val jo = JSONObject(homeResponse)
        if (!ResChecker.checkRes(TAG, jo)) {
            Log.runtime(TAG, "自动合成图鉴homePage失败：${jo.optString("resultDesc", jo.toString())}")
            return null
        }
        val data = jo.getJSONObject("data")
        if (!data.optBoolean("showBookMedalGenerationRemind") && !data.optBoolean("showMedalRedDot")) {
            return null
        }
        val animalBook = data.optJSONObject("animalBook") ?: return null
        if (animalBook.optBoolean("stopAnimalCirculation")) {
            return null
        }
        val bookId = animalBook.optString("bookId")
        if (bookId.isBlank()) {
            return null
        }
        val ecosystem = animalBook.optString("ecosystem")
        return if (isBookCollectedButMedalMissing(bookId) == true && generateBookMedal(bookId, ecosystem)) {
            bookId
        } else {
            null
        }
    }

    private fun isBookReadyForMedal(bookForUser: JSONObject, bookId: String): Boolean {
        val status = bookForUser.optString("medalGenerationStatus")
        if (isGeneratedBookStatus(status) || isNotReadyBookStatus(status)) {
            return false
        }
        if (isReadyBookStatus(status)) {
            return true
        }
        return isBookCollectedButMedalMissing(bookId) == true
    }

    private fun isGeneratedBookStatus(status: String): Boolean {
        return status == "已合成" ||
            status == "已生成" ||
            status.uppercase() in setOf(
                "GENERATED",
                "HAS_GENERATED",
                "GENERATED_BOOK_MEDAL"
            )
    }

    private fun isReadyBookStatus(status: String): Boolean {
        return status == "已集齐" ||
            status.uppercase() in setOf(
                "CAN_GENERATE",
                "CAN_GENERATE_MEDAL",
                "CAN_GENERATE_BOOK_MEDAL",
                "READY_TO_GENERATE",
                "TO_GENERATE"
            )
    }

    private fun isNotReadyBookStatus(status: String): Boolean {
        return status == "未集齐" ||
            status == "不可合成" ||
            status.uppercase() in setOf(
                "CAN_NOT_GENERATE",
                "NOT_COLLECTED",
                "UNFINISHED"
            )
    }

    private fun isBookCollectedButMedalMissing(bookId: String): Boolean? {
        return try {
            val response = AntDodoRpcCall.queryBookInfo(bookId)
            if (response.isNullOrEmpty()) {
                Log.runtime(TAG, "自动合成图鉴queryBookInfo返回空，bookId=$bookId")
                return null
            }
            val jo = JSONObject(response)
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.runtime(TAG, "自动合成图鉴queryBookInfo失败，bookId=$bookId：${jo.optString("resultDesc")}")
                return null
            }
            val data = jo.getJSONObject("data")
            if (data.optJSONObject("animalBookResult")?.optBoolean("stopAnimalCirculation") == true) {
                return false
            }
            val animalForUserList = data.optJSONArray("animalForUserList") ?: return null
            if (animalForUserList.length() == 0) {
                return false
            }
            var hasMedalPending = false
            for (i in 0 until animalForUserList.length()) {
                val collectDetail = animalForUserList.optJSONObject(i)?.optJSONObject("collectDetail")
                if (!hasAnimalCardForBookMedal(collectDetail)) {
                    return false
                }
                if (collectDetail?.optBoolean("hasGeneratedBookMedal") != true) {
                    hasMedalPending = true
                }
            }
            hasMedalPending
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "isBookCollectedButMedalMissing err:", t)
            null
        }
    }

    private fun generateBookMedal(bookId: String, ecosystem: String): Boolean {
        val medalResponse = AntDodoRpcCall.generateBookMedal(bookId)
        if (medalResponse.isNullOrEmpty()) {
            Log.runtime(TAG, "generateBookMedal返回空，bookId=$bookId")
            return false
        }
        val jo = JSONObject(medalResponse)
        if (!ResChecker.checkRes(TAG, jo)) {
            Log.dodo("合成图鉴失败[${ecosystem.ifBlank { bookId }}]：${jo.optString("resultDesc", jo.toString())}")
            return false
        }
        Log.dodo("神奇物种🦕合成勋章[${ecosystem.ifBlank { bookId }}]")
        return true
    }

    interface CollectToFriendType {
        companion object {
            const val COLLECT = 0
            const val DONT_COLLECT = 1
            val nickNames = arrayOf("选中帮抽卡", "选中不帮抽卡")
        }
    }

    private enum class DodoPropKind {
        CURRENT_BOOK_DRAW,
        UNIVERSAL_CARD,
        HISTORY_CARD,
        FRIEND_CARD,
        OTHER
    }

    private enum class DodoBusinessFailureKind {
        TERMINAL,
        RETRYABLE,
        UNKNOWN
    }

    private data class PropConsumeTarget(
        val bookId: String,
        val animalId: String,
        val ecosystem: String,
        val name: String
    )

    private data class ExchangeCardCandidate(
        val animalId: String,
        val displayName: String
    )

    private data class PropTargetQueryResult(
        val target: PropConsumeTarget?,
        val querySucceeded: Boolean
    )

    private data class ExchangeCardCandidateQueryResult(
        val candidate: ExchangeCardCandidate?,
        val querySucceeded: Boolean
    )

    companion object {
        private val TAG = AntDodo::class.java.simpleName
        private const val TASK_BLACKLIST_MODULE = "神奇物种"
        private val BUSINESS_DRIVEN_TASK_TYPES = setOf("HELP_FRIEND_COLLECT", "SEND_FRIEND_CARD")
    }
}


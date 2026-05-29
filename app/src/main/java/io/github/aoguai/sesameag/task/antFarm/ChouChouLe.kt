package io.github.aoguai.sesameag.task.antFarm


import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.TimeTriggerEvaluator
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * 小鸡抽抽乐功能类
 */
class ChouChouLe {

    companion object {
        private val TAG = ChouChouLe::class.java.simpleName
        private const val FARM_BLACKLIST_MODULE = "蚂蚁庄园"
        private const val DATA_FILE_NAME = "farmIPChouChouLeShop.json"

        /**
         * 供外部（如实体类）加载数据使用
         */
        @JvmStatic
        fun loadData(userId: String?): IpChouChouLeData {
            try {
                val file = Files.getTargetFileofUser(userId, DATA_FILE_NAME)
                if (file != null && file.exists()) {
                    val body = Files.readFromFile(file)
                    if (body.isNotEmpty()) {
                        return JsonUtil.parseObject(body, IpChouChouLeData::class.java)
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "加载IP抽抽乐数据失败", e)
            }
            return IpChouChouLeData()
        }

        @JvmStatic
        fun saveData(userId: String?, data: IpChouChouLeData) {
            try {
                val json = JsonUtil.formatJson(data)
                if (json.isEmpty()) return
                val file = Files.getTargetFileofUser(userId, DATA_FILE_NAME)
                if (file != null) {
                    Files.write2File(json, file)
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "保存IP抽抽乐数据失败", e)
            }
        }
    }

    /**
     * 合并后的数据结构
     */
    class IpChouChouLeData {
        var activityId: String = ""
        var shopItems: MutableMap<String, String> = mutableMapOf() // skuId -> "名称|限购|价格"
        var exchangedCounts: MutableMap<String, Int> = mutableMapOf() // skuId -> 累计兑换次数
    }

    /**
     * 任务信息结构体
     */
    private data class TaskInfo(
        var taskStatus: String = "",
        var title: String = "",
        var taskId: String = "",
        var innerAction: String = "",
        var rightsTimes: Int = 0,
        var rightsTimesLimit: Int = 0,
        var awardType: String = "",
        var awardCount: Int = 0,
        var targetUrl: String = "",
        var desc: String = "",
        var categorizationSecondLevel: String = "",
        var categorizationThirdLevel: String = ""
    ) {
        /**
         * 获取剩余次数
         */
        fun getRemainingTimes(): Int {
            return max(0, rightsTimesLimit - rightsTimes)
        }

        fun isLimitedTask(): Boolean {
            return title.contains("【限时】")
        }
    }

    fun run(antFarm: AntFarm) {
        if (Status.hasFlagToday(StatusFlags.FLAG_FARM_CHOUCHOULE_FINISHED)) {
            return
        }

        val isGameFinished = Status.hasFlagToday(StatusFlags.FLAG_FARM_GAME_FINISHED)
        val isGameEnabled = antFarm.recordFarmGame?.value == true
        val isTimeReached = antFarm.chouChouLeTrigger?.getTriggerSpec()?.let {
            TimeTriggerEvaluator.evaluateNow(it).allowNow
        } == true
        val ignoreAcceLimitMode = !isGameEnabled || antFarm.ignoreAcceLimit?.value == true

        when {
            ignoreAcceLimitMode -> {
                if (isTimeReached) {
                    executeAndSync(antFarm)
                } else {
                    Log.farm("当前处于按时抽抽乐模式，未到设定时间，跳过")
                }
            }
            isGameFinished -> {
                executeAndSync(antFarm)
            }
            !isGameFinished -> {
                Log.farm("游戏改分还没有完成，暂不执行抽抽乐")
            }
        }
    }

    private fun executeAndSync(antFarm: AntFarm) {
        if (this.chouchoule()) {
            Status.setFlagToday(StatusFlags.FLAG_FARM_CHOUCHOULE_FINISHED)
            antFarm.syncAnimalStatus(antFarm.ownerFarmId)
            Log.farm("今日抽抽乐已完成")
        } else {
            antFarm.syncAnimalStatus(antFarm.ownerFarmId)
            Log.farm("抽抽乐尚有未完成项（请检查是否需要验证）")
        }
    }

    /**
     * 抽抽乐主入口
     * 返回值判断是否真的完成任务，是否全部执行完毕且无剩余（任务已做、奖励已领、抽奖已完）
     */
    fun chouchoule(): Boolean {
        var allFinished = true
        try {
            val response = AntFarmRpcCall.queryLoveCabin(UserMap.currentUid)
            val jo = JSONObject(response)
            if (!ResChecker.checkRes(TAG, jo)) {
                return false
            }

            val drawMachineInfo = jo.optJSONObject("drawMachineInfo")
            val hasIpDraw = drawMachineInfo?.has("ipDrawMachineActivityId") == true ||
                jo.has("ipDrawMachineActivityId") ||
                jo.has("ipDrawMachine")
            val hasDailyDraw = drawMachineInfo?.has("dailyDrawMachineActivityId") == true ||
                jo.has("dailyDrawMachineActivityId") ||
                jo.has("dailyDrawMachine")
            if (!hasIpDraw && !hasDailyDraw) {
                Log.error(TAG, "抽抽乐🎁[获取抽抽乐活动信息失败]")
                return false
            }

            // 执行IP抽抽乐
            if (hasIpDraw) {
                allFinished = doChouchoule("ipDraw")
            }

            // 执行普通抽抽乐
            if (hasDailyDraw) {
                allFinished = allFinished and doChouchoule("dailyDraw")
            }

            return allFinished
        } catch (t: Throwable) {
            Log.printStackTrace("chouchoule err:", t)
            return false
        }
    }

    /**
     * 执行抽抽乐
     *
     * @param drawType "dailyDraw" 或 "ipDraw"
     * 返回是否该类型已全部完成
     */
    private fun doChouchoule(drawType: String): Boolean {
        var doubleCheck: Boolean
        try {
            runCatching {
                AntFarmRpcCall.refinedOperation("DRAW_MACHINE", "antfarm_villa", "RPC")
            }
            do {
                doubleCheck = false
                val jo = JSONObject(AntFarmRpcCall.chouchouleListFarmTask(drawType))
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.error(TAG, if (drawType == "ipDraw") "IP抽抽乐任务列表获取失败" else "抽抽乐任务列表获取失败")
                    return false
                }

                val farmTaskList = jo.getJSONArray("farmTaskList")
                val tasks = parseTasks(farmTaskList)

                for (task in tasks) {
                    if (TaskStatus.FINISHED.name == task.taskStatus) {
                        if (receiveTaskAward(drawType, task)) {
                            GlobalThreadPools.sleepCompat(300L)
                            doubleCheck = true
                        }
                    } else if (TaskStatus.TODO.name == task.taskStatus) {
                        if (shouldSkipLimitedTaskToday(task)) {
                            continue
                        }
                        if (task.getRemainingTimes() > 0 && !isBlacklistedTask(task)) {
                            if (doChouTask(drawType, task)) {
                                doubleCheck = true
                            }
                        }
                    }
                }
            } while (doubleCheck)
        } catch (t: Throwable) {
            Log.printStackTrace("doChouchoule err:", t)
            return false
        }

        // 执行抽奖
        val drawSuccess = if ("ipDraw" == drawType) {
            handleIpDraw()
        } else {
            handleDailyDraw()
        }

        if (!drawSuccess) return false

        // 最后校验是否真的全部完成
        return verifyFinished(drawType)
    }

    /*
     校验是否还有未完成的任务或抽奖
     */
    private fun verifyFinished(drawType: String): Boolean {
        return try {
            // 校验任务
            val jo = JSONObject(AntFarmRpcCall.chouchouleListFarmTask(drawType))
            if (!ResChecker.checkRes(TAG, jo)) return false

            val farmTaskList = jo.getJSONArray("farmTaskList")
            val tasks = parseTasks(farmTaskList)
            for (task in tasks) {
                if (TaskStatus.FINISHED.name == task.taskStatus) {
                    return false
                } else if (TaskStatus.TODO.name == task.taskStatus) {
                    if (shouldSkipLimitedTaskToday(task)) {
                        continue
                    }
                    if (task.getRemainingTimes() > 0 && !isBlacklistedTask(task)) {
                        return false
                    }
                }
            }

            // 校验抽奖次数
            val drawJo = if ("ipDraw" == drawType) {
                JSONObject(AntFarmRpcCall.queryDrawMachineActivity_New("ipDrawMachine", "dailyDrawMachine"))
            } else {
                JSONObject(AntFarmRpcCall.queryDrawMachineActivity_New("dailyDrawMachine", "ipDrawMachine"))
            }
            if (!ResChecker.checkRes(TAG, drawJo)) return false
            val drawTimes = extractDrawTimes(drawJo)
            if (drawTimes > 0) return false

            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun limitedTaskFlag(taskId: String): String {
        return StatusFlags.FLAG_FARM_CHOUCHOULE_LIMITED_ENDED_PREFIX + taskId
    }

    private fun shouldSkipLimitedTaskToday(task: TaskInfo): Boolean {
        return task.isLimitedTask() && Status.hasFlagToday(limitedTaskFlag(task.taskId))
    }

    private fun isBlacklistedTask(task: TaskInfo): Boolean {
        return listOf(
            task.innerAction.takeIf { it.isNotBlank() }?.let { "innerAction:$it" }.orEmpty(),
            task.innerAction,
            task.taskId,
            task.title,
            if (task.targetUrl.contains("donationSubject")) "targetUrl:donationSubject" else "",
            task.desc.takeIf { it.isNotBlank() }?.let { "desc:$it" }.orEmpty(),
            task.desc,
            task.categorizationSecondLevel.takeIf { it.isNotBlank() }?.let { "categorizationSecondLevel:$it" }.orEmpty(),
            task.categorizationThirdLevel.takeIf { it.isNotBlank() }?.let { "categorizationThirdLevel:$it" }.orEmpty()
        )
            .filter { it.isNotBlank() }
            .any { TaskBlacklist.isTaskInBlacklist(FARM_BLACKLIST_MODULE, it) }
    }

    private fun markLimitedTaskEndedToday(task: TaskInfo, reason: String) {
        if (!task.isLimitedTask()) {
            return
        }
        Status.setFlagToday(limitedTaskFlag(task.taskId))
        val detail = reason.ifBlank { "服务端返回活动已结束" }
        Log.farm("限时抽抽乐任务[${task.title}]已结束，今日不再尝试：$detail")
    }

    private fun getResponseMessage(jo: JSONObject): String {
        val resData = jo.optJSONObject("resData")
        return listOf(
            jo.optString("resultDesc"),
            jo.optString("desc"),
            jo.optString("memo"),
            resData?.optString("resultDesc").orEmpty(),
            resData?.optString("desc").orEmpty(),
            resData?.optString("memo").orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun extractDrawTimes(jo: JSONObject): Int {
        val userInfo = jo.optJSONObject("userInfo")
        val drawMachineActivity = jo.optJSONObject("drawMachineActivity")
        return listOf(
            jo.optInt("drawTimes", -1),
            jo.optInt("leftDrawTimes", -1),
            jo.optInt("quotaCanUse", -1),
            jo.optInt("canUseTimes", -1),
            jo.optInt("drawRightsTimes", -1),
            userInfo?.optInt("leftDrawTimes", -1) ?: -1,
            userInfo?.optInt("drawTimes", -1) ?: -1,
            drawMachineActivity?.optInt("quotaCanUse", -1) ?: -1,
            drawMachineActivity?.optInt("canUseTimes", -1) ?: -1,
            drawMachineActivity?.optInt("drawRightsTimes", -1) ?: -1
        ).firstOrNull { it >= 0 } ?: 0
    }

    private fun isLimitedTaskEndedResponse(jo: JSONObject): Boolean {
        val resultCode = jo.optString("resultCode")
        if (resultCode == "DRAW_MACHINE07") {
            return false
        }
        val message = getResponseMessage(jo)
        if (message.isBlank()) {
            return false
        }
        return listOf("活动已结束", "活动结束", "已下线", "已失效", "不存在", "未开始", "已结束")
            .any { message.contains(it) }
    }

    private fun isTaskQuotaReachedResponse(jo: JSONObject): Boolean {
        val resultCode = jo.optString("resultCode").ifBlank { jo.optString("code") }
        if (resultCode == "309") {
            return true
        }
        val message = getResponseMessage(jo)
        return message.contains("任务数达到当日上限") ||
            message.contains("权益获取次数超过上限") ||
            message.contains("当日上限")
    }

    /**
     * 解析任务列表
     */
    @Throws(Exception::class)
    private fun parseTasks(array: JSONArray): List<TaskInfo> {
        val list = ArrayList<TaskInfo>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val info = TaskInfo(
                taskStatus = item.getString("taskStatus"),
                title = item.getString("title"),
                taskId = item.optString("bizKey").ifBlank { item.optString("taskId") },
                innerAction = item.optString("innerAction"),
                rightsTimes = listOf(
                    item.optInt("rightsTimes", -1),
                    item.optInt("receivedTimes", -1)
                ).firstOrNull { it >= 0 } ?: 0,
                rightsTimesLimit = listOf(
                    item.optInt("rightsTimesLimit", -1),
                    item.optInt("drawRightsTimes", -1),
                    item.optInt("canReceiveAwardCount", -1)
                ).firstOrNull { it >= 0 } ?: 0,
                awardType = item.optString("awardType").ifBlank { item.optString("taskAwardType") },
                awardCount = listOf(
                    item.optInt("awardCount", -1),
                    item.optInt("canReceiveAwardCount", -1)
                ).firstOrNull { it >= 0 } ?: 0,
                targetUrl = item.optString("targetUrl").ifBlank { item.optString("finishedUrl") },
                desc = item.optString("desc"),
                categorizationSecondLevel = item.optString("categorizationSecondLevel"),
                categorizationThirdLevel = item.optString("categorizationThirdLevel")
            )
            list.add(info)
        }
        return list
    }

    private fun isAdBrowseTask(task: TaskInfo): Boolean {
        val merged = listOf(task.taskId, task.title, task.targetUrl)
            .joinToString("|")
            .lowercase()
        return merged.contains("shangyehua") ||
            merged.contains("30s") ||
            merged.contains("15s") ||
            merged.contains("browse") ||
            merged.contains("杂货铺") ||
            merged.contains("逛一逛")
    }

    private fun resolveAdTaskAttemptCount(task: TaskInfo): Int {
        val remainingTimes = task.getRemainingTimes()
        if (remainingTimes > 0) {
            return remainingTimes
        }
        if (task.rightsTimesLimit <= 0) {
            Log.farm("广告任务[${task.title}]剩余次数字段异常，按默认3次兜底")
            return 3
        }
        return 0
    }

    /**
     * 执行任务
     */
    private fun doChouTask(drawType: String, task: TaskInfo): Boolean {
        try {
            if (shouldSkipLimitedTaskToday(task)) {
                return false
            }
            if (task.taskId.isBlank()) {
                Log.farm("抽抽乐任务[${task.title}]缺少 taskId，跳过")
                return false
            }
            val taskName = if (drawType == "ipDraw") "IP抽抽乐" else "抽抽乐"

            // 特殊任务：浏览广告
            if (isAdBrowseTask(task)) {
                return handleAdTask(drawType, task)
            }

            // 普通任务
            if (task.title == "消耗饲料换机会") {
                if (AntFarm.foodStock < 90) {
                    Log.farm("饲料余量(${AntFarm.foodStock}g)少于90g，跳过任务: ${task.title}")
                    return false // 返回 false 避免 doubleCheck，且不执行后续 RPC
                }
            }
            val s = AntFarmRpcCall.chouchouleDoFarmTask(drawType, task.taskId)
            val jo = JSONObject(s)
            val resultCode = jo.optString("resultCode")
            if ("DRAW_MACHINE07" == resultCode) {
                Log.farm("${taskName}任务[${task.title}]失败: 饲料不足，停止后续尝试")
                return false
            }
            if (isTaskQuotaReachedResponse(jo)) {
                Log.farm("${taskName}任务[${task.title}]今日次数已达上限，停止继续尝试")
                return false
            }
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("$taskName🧾️[任务: ${task.title}]")
                if (task.title == "消耗饲料换机会") {
                    GlobalThreadPools.sleepCompat(300L)
                } else {
                    GlobalThreadPools.sleepCompat(1000L)
                }
                return true
            } else {
                if (isLimitedTaskEndedResponse(jo)) {
                    markLimitedTaskEndedToday(task, getResponseMessage(jo))
                    return true
                }
            }
            return false
        } catch (t: Throwable) {
            Log.printStackTrace("执行抽抽乐任务 err:", t)
            return false
        }
    }

    private fun finishAdTaskDirectly(
        drawType: String,
        task: TaskInfo,
        taskSceneCode: String,
        maxRetry: Int? = null
    ): Int {
        val taskName = if (drawType == "ipDraw") "IP抽抽乐" else "抽抽乐"
        val attemptCount = resolveAdTaskAttemptCount(task)
        if (attemptCount <= 0) {
            return 0
        }
        val maxTimes = maxRetry?.let { attemptCount.coerceAtMost(it) } ?: attemptCount
        var successCount = 0
        for (index in 0 until maxTimes) {
            val outBizNo = buildString {
                append(task.taskId)
                append("_")
                append(System.currentTimeMillis())
                append("_")
                append(index)
                append("_")
                append(Integer.toHexString((Math.random() * 0xFFFFFF).toInt()))
            }
            val response = AntFarmRpcCall.finishTask(task.taskId, taskSceneCode, outBizNo)
            val jo = JSONObject(response)
            if (isTaskQuotaReachedResponse(jo)) {
                Log.farm("广告任务[${task.title}]今日权益已达上限，停止继续尝试")
                return -1
            }
            if (ResChecker.checkRes(TAG, jo)) {
                successCount++
                Log.farm("$taskName🧾️[任务: ${task.title}]#第${task.rightsTimes + successCount}次")
                continue
            }
            if (isLimitedTaskEndedResponse(jo)) {
                markLimitedTaskEndedToday(task, getResponseMessage(jo))
                return successCount
            }
            val message = getResponseMessage(jo)
            if (message.contains("任务已完成") || message.contains("已完成")) {
                return max(1, successCount)
            }
            if (successCount == 0) {
                Log.farm("广告任务直连完成失败[${task.title}]: ${message.ifBlank { jo.toString() }}")
            }
            break
        }
        return successCount
    }

    /**
     * 处理广告任务
     */
    private fun handleAdTask(drawType: String, task: TaskInfo): Boolean {
        try {
            if (shouldSkipLimitedTaskToday(task)) {
                return false
            }
            val taskSceneCode = if (drawType == "ipDraw") "ANTFARM_IP_DRAW_TASK" else "ANTFARM_DAILY_DRAW_TASK"
            val directSuccessCount = finishAdTaskDirectly(drawType, task, taskSceneCode)
            if (directSuccessCount != 0) {
                return directSuccessCount > 0
            }

            val referToken = AntFarm.loadAntFarmReferToken()
            if (!referToken.isNullOrEmpty()) {
                val response = AntFarmRpcCall.xlightPlugin(referToken, "HDWFCJGXNZW_CUSTOM_20250826173111")
                val jo = JSONObject(response)
                if (isTaskQuotaReachedResponse(jo)) {
                    Log.farm("浏览广告任务[${task.title}]今日权益已达上限，跳过插件流程")
                    return false
                }

                if (jo.optString("retCode") == "0") {
                    val resData = jo.optJSONObject("resData")
                    if (resData != null) {
                        val adList = resData.optJSONArray("adList")

                        if (adList != null && adList.length() > 0) {
                            // 检查是否有猜一猜任务
                            val playingResult = resData.optJSONObject("playingResult")
                            if (playingResult != null &&
                                "XLIGHT_GUESS_PRICE_FEEDS" == playingResult.optString("playingStyleType")
                            ) {
                                return handleGuessTask(drawType, task, adList, playingResult)
                            }
                        }
                    } else {
                        Log.farm("浏览广告任务[广告插件未返回resData，回退普通完成方式]")
                    }
                }
                Log.farm("浏览广告任务[没有可用广告或不支持，使用普通完成方式]")
            } else {
                Log.farm("浏览广告任务[没有可用Token，请手动看一起广告]")
            }

            return finishAdTaskDirectly(drawType, task, taskSceneCode, 1) > 0
        } catch (t: Throwable) {
            Log.printStackTrace("处理广告任务 err:", t)
            return false
        }
    }

    /**
     * 处理猜一猜任务
     */
    private fun handleGuessTask(
        drawType: String, task: TaskInfo,
        adList: JSONArray, playingResult: JSONObject
    ): Boolean {
        try {
            // 找到正确价格
            var correctPrice = -1
            var targetAdId = ""

            for (i in 0 until adList.length()) {
                val ad = adList.getJSONObject(i)
                val schemaJson = ad.optString("schemaJson", "")
                if (schemaJson.isNotEmpty()) {
                    val schema = JSONObject(schemaJson)
                    val price = schema.optInt("price", -1)
                    if (price > 0) {
                        if (correctPrice == -1 || abs(price - 11888) < abs(correctPrice - 11888)) {
                            correctPrice = price
                            targetAdId = ad.optString("adId", "")
                        }
                    }
                }
            }

            if (correctPrice > 0 && targetAdId.isNotEmpty()) {
                // 提交猜价格结果
                val playBizId = playingResult.optString("playingBizId", "")
                val eventRewardDetail = playingResult.optJSONObject("eventRewardDetail")
                if (eventRewardDetail != null) {
                    val eventRewardInfoList = eventRewardDetail.optJSONArray("eventRewardInfoList")
                    if (eventRewardInfoList != null && eventRewardInfoList.length() > 0) {
                        val playEventInfo = eventRewardInfoList.getJSONObject(0)

                        val taskSceneCode =
                            if (drawType == "ipDraw") "ANTFARM_IP_DRAW_TASK" else "ANTFARM_DAILY_DRAW_TASK"

                        val response = AntFarmRpcCall.finishAdTask(
                            playBizId, playEventInfo, task.taskId, taskSceneCode
                        )
                        val jo = JSONObject(response)

                        if (jo.optJSONObject("resData") != null &&
                            jo.getJSONObject("resData").optBoolean("success", false)
                        ) {
                            Log.farm(
                                (if (drawType == "ipDraw") "IP抽抽乐" else "抽抽乐") +
                                        "🧾️[猜价格任务完成: ${task.title}, 猜中价格: $correctPrice]"
                            )
                            GlobalThreadPools.sleepCompat(300L)
                            return true
                        }
                    }
                }
            }

            Log.farm("猜价格任务[未找到合适价格，使用普通完成方式]")
            return false
        } catch (t: Throwable) {
            Log.printStackTrace("处理猜价格任务 err:", t)
            return false
        }
    }

    /**
     * 领取任务奖励
     */
    private fun receiveTaskAward(drawType: String, task: TaskInfo): Boolean {
        try {
            if (task.taskId.isBlank()) {
                Log.farm("抽抽乐奖励[${task.title}]缺少 taskId，跳过领取")
                return false
            }
            val s = AntFarmRpcCall.chouchouleReceiveFarmTaskAward(
                drawType,
                task.taskId,
                task.awardType
            )
            val jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                return true
            }
        } catch (t: Throwable) {
            Log.printStackTrace("receiveFarmTaskAward err:", t)
        }
        return false
    }

    /**
     * 执行IP抽抽乐抽奖
     */
    private fun handleIpDraw(): Boolean {
        try {
            val jo = JSONObject(
                AntFarmRpcCall.queryDrawMachineActivity_New(
                    "ipDrawMachine", "dailyDrawMachine"
                )
            )
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.farm("IP抽抽乐新版活动查询失败，切换旧版接口重试")
                return handleIpDrawLegacy()
            }

            val activity = jo.optJSONObject("drawMachineActivity") ?: return handleIpDrawLegacy()
            val activityId = activity.optString("activityId")
            val endTime = activity.optLong("endTime", 0)
            if (endTime > 0 && System.currentTimeMillis() > endTime) {
                Log.farm("该[${activity.optString("activityId")}]抽奖活动已结束")
                return true
            }

            var remainingTimes = extractDrawTimes(jo)
            if (remainingTimes <= 0) {
                Log.farm("IP抽抽乐当前无可用次数，跳过旧版兜底接口")
                return true
            }
            var allSuccess = true
            Log.farm("IP抽抽乐剩余次数: $remainingTimes")

            while (remainingTimes > 0) {
                val batchCount = remainingTimes.coerceAtMost(10)
                Log.farm("执行 IP 抽抽乐 $batchCount 连抽...")

                val response = AntFarmRpcCall.drawMachineIP(batchCount)
                val batchSuccess = drawPrize("IP抽抽乐", response)
                if (!batchSuccess) {
                    Log.farm("IP抽抽乐连抽失败，切换旧版单抽流程")
                    return handleIpDrawLegacy()
                }
                allSuccess = allSuccess and batchSuccess

                remainingTimes -= batchCount
                if (remainingTimes > 0) {
                    GlobalThreadPools.sleepCompat(1500L)
                }
            }
            if (activityId.isNotEmpty() && AntFarm.instance?.autoExchange?.value == true) {
                batchExchangeRewards(activityId, endTime)
            }
            return allSuccess
        } catch (t: Throwable) {
            Log.printStackTrace("handleIpDraw err:", t)
            return false
        }
    }

    /**
     * 执行普通抽抽乐抽奖
     */
    private fun handleDailyDraw(): Boolean {
        try {
            val jo = JSONObject(
                AntFarmRpcCall.queryDrawMachineActivity_New(
                    "dailyDrawMachine", "ipDrawMachine"
                )
            )
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.farm("日常抽抽乐新版活动查询失败，切换旧版接口重试")
                return handleDailyDrawLegacy()
            }

            val activity = jo.optJSONObject("drawMachineActivity") ?: return handleDailyDrawLegacy()
            val endTime = activity.optLong("endTime", 0)
            if (endTime > 0 && System.currentTimeMillis() > endTime) {
                Log.farm("该[${activity.optString("activityId")}]抽奖活动已结束")
                return true
            }

            var remainingTimes = extractDrawTimes(jo)
            if (remainingTimes <= 0) {
                Log.farm("日常抽抽乐当前无可用次数，跳过旧版兜底接口")
                return true
            }
            var allSuccess = true

            Log.farm("日常抽抽乐剩余次数: $remainingTimes")

            while (remainingTimes > 0) {
                val batchCount = remainingTimes.coerceAtMost(10)
                Log.farm("执行日常抽抽乐 $batchCount 连抽...")

                val response = AntFarmRpcCall.drawMachineDaily(batchCount)
                val batchSuccess = drawPrize("日常抽抽乐", response)
                if (!batchSuccess) {
                    Log.farm("日常抽抽乐连抽失败，切换旧版单抽流程")
                    return handleDailyDrawLegacy()
                }
                allSuccess = allSuccess and batchSuccess

                remainingTimes -= batchCount
                if (remainingTimes > 0) {
                    GlobalThreadPools.sleepCompat(1500L)
                }
            }
            return allSuccess
        } catch (t: Throwable) {
            Log.printStackTrace("handleDailyDraw err:", t)
            return false
        }
    }

    private fun handleIpDrawLegacy(): Boolean {
        return try {
            val jo = JSONObject(AntFarmRpcCall.queryDrawMachineActivity())
            if (!ResChecker.checkRes(TAG, jo)) {
                false
            } else {
                val activity = jo.optJSONObject("drawMachineActivity") ?: return true
                val endTime = activity.optLong("endTime", 0)
                if (endTime > 0 && System.currentTimeMillis() > endTime) {
                    Log.farm("该[${activity.optString("activityId")}]抽奖活动已结束")
                    return true
                }

                var remainingTimes = jo.optInt("drawTimes", 0)
                var allSuccess = true
                while (remainingTimes > 0) {
                    val drawSuccess = drawPrize("IP抽抽乐", AntFarmRpcCall.drawMachine())
                    allSuccess = allSuccess and drawSuccess
                    if (!drawSuccess) {
                        break
                    }
                    remainingTimes--
                    if (remainingTimes > 0) {
                        GlobalThreadPools.sleepCompat(1500L)
                    }
                }
                allSuccess
            }
        } catch (t: Throwable) {
            Log.printStackTrace("handleIpDrawLegacy err:", t)
            false
        }
    }

    private fun handleDailyDrawLegacy(): Boolean {
        return try {
            val jo = JSONObject(AntFarmRpcCall.enterDrawMachine())
            if (!ResChecker.checkRes(TAG, jo)) {
                false
            } else {
                val userInfo = jo.optJSONObject("userInfo") ?: return true
                val drawActivityInfo = jo.optJSONObject("drawActivityInfo") ?: return true
                val endTime = drawActivityInfo.optLong("endTime", 0)
                if (endTime > 0 && System.currentTimeMillis() > endTime) {
                    Log.farm("该[${drawActivityInfo.optString("activityId")}]抽奖活动已结束")
                    return true
                }

                var remainingTimes = userInfo.optInt("leftDrawTimes", 0)
                val activityId = drawActivityInfo.optString("activityId")
                var allSuccess = true
                while (remainingTimes > 0) {
                    val response = if (activityId.isBlank() || activityId == "null") {
                        AntFarmRpcCall.DrawPrize()
                    } else {
                        AntFarmRpcCall.DrawPrize(activityId)
                    }
                    val drawSuccess = drawPrize("日常抽抽乐", response)
                    allSuccess = allSuccess and drawSuccess
                    if (!drawSuccess) {
                        break
                    }
                    remainingTimes--
                    if (remainingTimes > 0) {
                        GlobalThreadPools.sleepCompat(1500L)
                    }
                }
                allSuccess
            }
        } catch (t: Throwable) {
            Log.printStackTrace("handleDailyDrawLegacy err:", t)
            false
        }
    }

    /**
     * 领取抽抽乐奖品
     *
     * @param prefix   抽奖类型前缀
     * @param response 服务器返回的结果
     * 返回是否领取成功
     */
    private fun drawPrize(prefix: String, response: String): Boolean {
        try {
            val jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                val prizeList = jo.optJSONArray("drawMachinePrizeList")
                if (prizeList != null && prizeList.length() > 0) {
                    for (i in 0 until prizeList.length()) {
                        val prize = prizeList.getJSONObject(i)
                        val title = prize.optString("title", prize.optString("prizeName", "未知奖品"))
                        Log.farm("$prefix🎁[领取: $title]")
                    }
                } else {
                    val prize = jo.optJSONObject("drawMachinePrize")
                    if (prize != null) {
                        val title = prize.optString("title", prize.optString("prizeName", "未知奖品"))
                        Log.farm("$prefix🎁[领取: $title]")
                    } else {
                        Log.farm("$prefix🎁[抽奖成功，但未解析到具体奖品名称]")
                    }
                }
                return true
            }
        } catch (t: Throwable) {
            Log.printStackTrace("drawPrize err:", t)
        }
        return false
    }

    /**
     * 批量兑换奖励
     */
    fun batchExchangeRewards(activityId: String, endTime: Long) {
        try {
            val daysBefore = AntFarm.instance?.exchangeDaysBeforeEndIp?.value ?: 0
            if (daysBefore > 0 && endTime > 0) {
                val now = System.currentTimeMillis()
                val remainingMs = endTime - now
                val limitMs = daysBefore * 24 * 60 * 60 * 1000L

                if (remainingMs > limitMs) {
                    val remainingDays = remainingMs / (24 * 60 * 60 * 1000L)
                    Log.farm("[自动兑换]: 未到设定兑换时间：活动尚余 $remainingDays 天结束，设定为提前 $daysBefore 天兑换，跳过。")
                    return
                }
            }
            val response = AntFarmRpcCall.getItemList(activityId, 10, 0)
            val respJson = JSONObject(response)

            if (respJson.optBoolean("success", false) || respJson.optString("code") == "100000000") {
                var totalCent = 0
                val mallAccount = respJson.optJSONObject("mallAccountInfoVO")
                if (mallAccount != null) {
                    val holdingCount = mallAccount.optJSONObject("holdingCount")
                    if (holdingCount != null) {
                        totalCent = holdingCount.optInt("cent", 0)
                    }
                }
                Log.farm("[自动兑换]: 当前持有总碎片: " + (totalCent / 100))
                val itemVOList = respJson.optJSONArray("itemInfoVOList") ?: return

                val userId = UserMap.currentUid
                val data = loadData(userId)

                // 1. 同步物品列表到本地文件，常规情况下只做增量更新
                var changed = false
                if (data.activityId != activityId) {
                    Log.farm("[自动兑换]: 检测到活动变更 ($activityId)，重置本地兑换记录并更新商店列表")
                    data.activityId = activityId
                    data.exchangedCounts.clear()
                    data.shopItems.clear()
                    changed = true
                }

                val latestShopItems = LinkedHashMap<String, String>()
                for (i in 0 until itemVOList.length()) {
                    val item = itemVOList.optJSONObject(i) ?: continue
                    val skuList = item.optJSONArray("skuModelList") ?: continue
                    for (j in 0 until skuList.length()) {
                        val sku = skuList.optJSONObject(j) ?: continue
                        val skuId = sku.optString("skuId")
                        val spu = item.optString("spuName").trim()
                        val skuN = sku.optString("skuName").trim()

                        // 解析限购次数
                        var limitCount = 0
                        val extendInfo = sku.optString("skuExtendInfo")
                        try {
                            val regex = "(\\d+)次".toRegex()
                            val matchResult = regex.find(extendInfo)
                            if (matchResult != null) {
                                limitCount = matchResult.groupValues[1].toInt()
                            }
                        } catch (_: Exception) {}

                        // 获取碎片价格
                        val minPriceObj = item.optJSONObject("minPrice")
                        val cent = minPriceObj?.optInt("cent", 0) ?: 0

                        // 存储格式: "名字|限制次数|所需碎片"
                        // 优化重复名称显示：如果 skuName 和 spuName 相同，则只显示一个
                        val displayName = when {
                            skuN.isEmpty() || spu == skuN -> spu
                            skuN.contains(spu) -> skuN
                            spu.contains(skuN) -> spu
                            else -> spu + skuN
                        }
                        val valueStr = "$displayName|$limitCount|$cent"
                        latestShopItems[skuId] = valueStr
                        if (data.shopItems[skuId] != valueStr) {
                            data.shopItems[skuId] = valueStr
                            changed = true
                        }
                    }
                }

                if (changed) {
                    saveData(userId, data)
                }

                // 3. 收集所有可兑换物品
                val allSkus = ArrayList<JSONObject>()
                for (i in 0 until itemVOList.length()) {
                    val item = itemVOList.optJSONObject(i) ?: continue
                    val itemReachedLimit = isReachedLimit(item)
                    val minPriceObj = item.optJSONObject("minPrice")
                    val cent = minPriceObj?.optInt("cent", 0) ?: 0

                    val skuList = item.optJSONArray("skuModelList") ?: continue
                    for (j in 0 until skuList.length()) {
                        val sku = skuList.optJSONObject(j) ?: continue
                        sku.put("_spuId", item.optString("spuId"))
                        sku.put("_spuName", item.optString("spuName"))
                        sku.put("_isReachLimit", itemReachedLimit || isReachedLimit(sku))
                        sku.put("_cent", cent)

                        // 识别 IP 限定装扮
                        val extendInfo = sku.optString("skuExtendInfo")
                        sku.put("_isIp", extendInfo.contains("\"controlTag\":\"IP限定装扮\""))

                        allSkus.add(sku)
                    }
                }

                // 4. 确定兑换序列
                val customMap = AntFarm.instance?.autoExchangeList?.value
                val isCustom = !customMap.isNullOrEmpty()

                val finalSequence = ArrayList<JSONObject>()
                if (isCustom) {
                    val missingCustomSkuIds = LinkedHashSet<String>()
                    // 完全按照用户设置的顺序执行
                    customMap.entries.forEach { entry ->
                        val skuId = entry.key
                        val targetCount = entry.value
                        if (skuId != null && targetCount != null && targetCount > 0) {
                            val sku = allSkus.find { it.optString("skuId") == skuId }
                            if (sku != null) {
                                val alreadyExchanged = data.exchangedCounts[skuId] ?: 0
                                val needToExchange = targetCount - alreadyExchanged
                                if (needToExchange > 0) {
                                    sku.put("_needToExchange", needToExchange)
                                    finalSequence.add(sku)
                                } else {
                                    Log.farm("[自动兑换]: [${sku.optString("_spuName") + sku.optString("skuName")}] 已达到自定义兑换数量($targetCount)，跳过")
                                }
                            } else {
                                missingCustomSkuIds.add(skuId)
                            }
                        }
                    }
                    if (missingCustomSkuIds.isNotEmpty()) {
                        if (data.shopItems != latestShopItems) {
                            data.shopItems.clear()
                            data.shopItems.putAll(latestShopItems)
                            saveData(userId, data)
                            Log.farm("[自动兑换]: 检测到自定义商品缺失，已按当前商店补做一次全量快照同步")
                        }
                        missingCustomSkuIds.forEach { skuId ->
                            Log.farm("[自动兑换]: 自定义商品[$skuId] 当前商店未找到，已跳过")
                        }
                    }
                } else {
                    // 默认排序逻辑
                    allSkus.sortWith { a, b ->
                        val isIpA = a.optBoolean("_isIp")
                        val isIpB = b.optBoolean("_isIp")
                        val nameA = a.optString("_spuName") + a.optString("skuName")
                        val nameB = b.optString("_spuName") + b.optString("skuName")
                        val isNewEggA = nameA.contains("新蛋卡")
                        val isNewEggB = nameB.contains("新蛋卡")

                        if (isIpA != isIpB) {
                            if (isIpA) -1 else 1
                        } else if (isNewEggA != isNewEggB) {
                            if (isNewEggA) 1 else -1
                        } else {
                            b.optInt("_cent", 0).compareTo(a.optInt("_cent", 0))
                        }
                    }

                    // 默认逻辑下的预检查
                    for (sku in allSkus) {
                        if (sku.optBoolean("_isReachLimit")) continue
                        val fullName = sku.optString("_spuName") + sku.optString("skuName")
                        if (fullName.contains("新蛋卡")) continue
                        val cent = sku.optInt("_cent", 0)
                        if (isNoEnoughPoint(sku) || (cent > 0 && totalCent < cent)) {
                            Log.farm("[自动兑换]: 最高价值项 [$fullName] 碎片不足，等攒够再换，终止本次兑换")
                            return
                        }
                        break
                    }
                    finalSequence.addAll(allSkus)
                }

                // 5. 执行兑换
                var stoppedByPoints = false
                for (sku in finalSequence) {
                    if (sku.optBoolean("_isReachLimit")) continue

                    val skuId = sku.optString("skuId")
                    val fullName = sku.optString("_spuName") + sku.optString("skuName")
                    val cent = sku.optInt("_cent", 0)

//                    if (!isCustom) {
//                        if (fullName.contains("新蛋卡")) {
//                            var hasOtherItems = false
//                            for (other in allSkus) {
//                                if (!other.optString("_spuName").contains("新蛋卡") && !other.optBoolean("_isReachLimit")) {
//                                    hasOtherItems = true
//                                    break
//                                }
//                            }
//                            if (hasOtherItems) continue
//                        }
//                    }

                    // 确定本次需要兑换的次数
                    var limitCount = 1
                    if (isCustom) {
                        limitCount = sku.optInt("_needToExchange", 1)
                    } else {
                        val extendInfo = sku.optString("skuExtendInfo")
                        try {
                            val regex = "(\\d+)次".toRegex()
                            val matchResult = regex.find(extendInfo)
                            if (matchResult != null) {
                                limitCount = matchResult.groupValues[1].toInt()
                            }
                        } catch (_: Exception) {}
                    }

                    if (isNoEnoughPoint(sku) || (cent > 0 && totalCent < cent)) {
                        if (!isCustom && fullName.contains("新蛋卡")) {
                            Log.farm("[自动兑换]: 新蛋卡碎片不足(需 ${cent/100})，等攒够再换")
                        } else {
                            Log.farm("[自动兑换]: 剩余碎片不足以兑换优先级项 [$fullName] (需 ${cent/100})，停止后续兑换任务")
                        }
                        return
                    }

                    var sessionExchangedCount = 0
                    while (sessionExchangedCount < limitCount) {
                        if (cent > 0 && totalCent < cent) {
                            Log.farm("[自动兑换]: 剩余碎片[${totalCent / 100}]，不足以兑换[$fullName]，兑换终止")
                            stoppedByPoints = true
                            break
                        }

                        val result = AntFarmRpcCall.exchangeBenefit(
                            sku.optString("_spuId"), sku.optString("skuId"),
                            activityId, "ANTFARM_IP_DRAW_MALL", "antfarm_villa"
                        )

                        val resObj = JSONObject(result)
                        val resultCode = resObj.optString("resultCode")

                        if ("SUCCESS" == resultCode) {
                            sessionExchangedCount++
                            totalCent -= cent
                            // 更新并保存记录
                            val currentExchanged = data.exchangedCounts[skuId] ?: 0
                            data.exchangedCounts[skuId] = currentExchanged + 1
                            saveData(UserMap.currentUid, data)

                            Log.farm("IP抽抽乐商店兑换: $fullName (本地累计已换 ${data.exchangedCounts[skuId]} 次，剩余碎片: ${totalCent/100})")
                            GlobalThreadPools.sleepCompat(800L)
                        } else if ("NO_ENOUGH_POINT" == resultCode) {
                            Log.farm("[自动兑换]: 兑换过程中碎片不足，停止兑换")
                            return
                        } else if (resultCode.contains("LIMIT") || resultCode.contains("MAX")) {
                            Log.farm("[自动兑换]: [$fullName] 达到服务器上限，尝试兑换下一个物品...")
                            break
                        } else {
                            Log.farm("[自动兑换]: 跳过 [$fullName]: " + resObj.optString("resultDesc"))
                            break
                        }
                    }
                }
                if (!stoppedByPoints) {
                    Log.farm("IP抽抽乐商店任务已处理完毕")
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG,"自动兑换异常", e)
        }
    }

    private fun isReachedLimit(jo: JSONObject?): Boolean {
        if (jo == null) return false
        if ("REACH_LIMIT" == jo.optString("itemStatus")) return true
        val list = jo.optJSONArray("itemStatusList")
        if (list != null) {
            for (i in 0 until list.length()) {
                val status = list.optString(i)
                if ("REACH_LIMIT" == status || status.contains("LIMIT")) return true
            }
        }
        return false
    }

    private fun isNoEnoughPoint(jo: JSONObject?): Boolean {
        if (jo == null) return false
        if ("NO_ENOUGH_POINT" == jo.optString("itemStatus")) return true
        val list = jo.optJSONArray("itemStatusList")
        if (list != null) {
            for (i in 0 until list.length()) {
                if ("NO_ENOUGH_POINT" == list.optString(i)) return true
            }
        }
        return false
    }
}

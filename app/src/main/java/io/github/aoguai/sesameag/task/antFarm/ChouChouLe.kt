package io.github.aoguai.sesameag.task.antFarm


import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.exchange.ExchangeCost
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectCatalog
import io.github.aoguai.sesameag.task.exchange.ExchangeItem
import io.github.aoguai.sesameag.task.exchange.ExchangeLimit
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionRow
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsCache
import io.github.aoguai.sesameag.task.exchange.ExchangeSafety
import io.github.aoguai.sesameag.task.exchange.ExchangeSafetyRules
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

    private data class IpDrawActivity(
        val activityId: String,
        val endTime: Long
    )

    private data class IpDrawMallSnapshot(
        val activityId: String,
        val endTime: Long,
        val balanceCent: Int,
        val items: List<IpDrawMallItem>
    )

    private data class IpDrawMallItem(
        val spuId: String,
        val skuId: String,
        val name: String,
        val priceCent: Int,
        val limitCount: Int,
        val itemStatus: String,
        val itemStatusList: JSONArray?,
        val skuStatus: String,
        val skuStatusList: JSONArray?,
        val skuExtendInfo: String,
        val userTotalLeftAmount: Int,
        val isIp: Boolean,
        val isNewEgg: Boolean,
        val safety: ExchangeSafety,
        val safetyReason: String
    )

    private data class IpDrawMallItemDetail(
        val item: IpDrawMallItem,
        val balanceCent: Int?
    )

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
            var allSuccess = true
            if (remainingTimes <= 0) {
                Log.farm("IP抽抽乐当前无可用次数，跳过抽奖")
            } else {
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

            var snapshot = queryIpDrawMallSnapshot(IpDrawActivity(activityId, endTime)) ?: return
            val userId = UserMap.currentUid
            val data = syncIpDrawShopSnapshot(snapshot)
            Log.farm("[自动兑换]: 当前持有总碎片: ${snapshot.balanceCent / 100}")

            val customMap = AntFarm.instance?.autoExchangeList?.value
            val isCustom = !customMap.isNullOrEmpty()
            val sequence = if (isCustom) {
                buildCustomExchangeSequence(customMap, snapshot, data)
            } else {
                buildDefaultExchangeSequence(snapshot)
            }
            if (sequence.isEmpty()) {
                Log.farm("IP抽抽乐商店当前没有需要兑换的奖励")
                return
            }

            for ((item, targetCount) in sequence) {
                var currentItem = snapshot.items.firstOrNull { it.skuId == item.skuId } ?: item
                val blockReason = blockedIpDrawMallItemReason(currentItem)
                if (blockReason.isNotBlank()) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：$blockReason")
                    if (!isCustom && shouldStopDefaultExchangeForNoEnoughPoint(currentItem)) {
                        return
                    }
                    continue
                }
                if (currentItem.safety != ExchangeSafety.AUTO) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：${currentItem.safetyReason.ifBlank { currentItem.safety.name }}")
                    continue
                }
                if (currentItem.spuId.isBlank() || currentItem.skuId.isBlank()) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：缺少 spuId/skuId")
                    continue
                }
                val detail = queryIpDrawMallItemDetail(currentItem)
                if (detail == null) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：缺少详情复核闭环")
                    continue
                }
                currentItem = detail.item
                detail.balanceCent?.let { snapshot = snapshot.copy(balanceCent = it) }
                val detailBlockReason = blockedIpDrawMallItemReason(currentItem)
                if (detailBlockReason.isNotBlank()) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：$detailBlockReason")
                    if (!isCustom && shouldStopDefaultExchangeForNoEnoughPoint(currentItem)) {
                        return
                    }
                    continue
                }
                if (currentItem.safety != ExchangeSafety.AUTO) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：${currentItem.safetyReason.ifBlank { currentItem.safety.name }}")
                    continue
                }
                if (currentItem.userTotalLeftAmount == 0) {
                    Log.farm("[自动兑换]: [${currentItem.name}] 跳过：已达兑换限制")
                    continue
                }
                if (currentItem.priceCent > 0 && snapshot.balanceCent < currentItem.priceCent) {
                    if (!isCustom) {
                        Log.farm("[自动兑换]: 最高价值项 [${currentItem.name}] 碎片不足(需 ${currentItem.priceCent / 100})，等攒够再换，终止本次兑换")
                        return
                    }
                    Log.farm("[自动兑换]: [${currentItem.name}] 碎片不足(需 ${currentItem.priceCent / 100}, 当前 ${snapshot.balanceCent / 100})，跳过")
                    continue
                }

                var confirmedCount = 0
                while (confirmedCount < targetCount) {
                    if (currentItem.priceCent > 0 && snapshot.balanceCent < currentItem.priceCent) {
                        Log.farm("[自动兑换]: 剩余碎片[${snapshot.balanceCent / 100}]，不足以兑换[${currentItem.name}]，兑换终止")
                        if (!isCustom) {
                            return
                        }
                        break
                    }

                    val exchangeJo = runCatching {
                        JSONObject(
                            AntFarmRpcCall.exchangeBenefit(
                                currentItem.spuId,
                                currentItem.skuId,
                                activityId,
                                "ANTFARM_IP_DRAW_MALL",
                                "antfarm_villa"
                            )
                        )
                    }.onFailure {
                        Log.printStackTrace(TAG, "IP抽抽乐兑换异常", it)
                    }.getOrNull() ?: break

                    if (!isIpDrawExchangeSuccess(exchangeJo)) {
                        val resultCode = exchangeJo.optString("resultCode").ifBlank { exchangeJo.optString("code") }
                        if (resultCode == "NO_ENOUGH_POINT") {
                            Log.farm("[自动兑换]: [${currentItem.name}] 兑换过程中碎片不足，停止兑换 | ${formatIpDrawRpcResult(exchangeJo)}")
                            if (!isCustom) {
                                return
                            }
                            break
                        }
                        if (resultCode.contains("LIMIT") || resultCode.contains("MAX")) {
                            Log.farm("[自动兑换]: [${currentItem.name}] 达到服务器上限，尝试兑换下一个物品 | ${formatIpDrawRpcResult(exchangeJo)}")
                            break
                        }
                        Log.farm("[自动兑换]: 跳过 [${currentItem.name}]: ${formatIpDrawRpcResult(exchangeJo)}")
                        break
                    }

                    GlobalThreadPools.sleepCompat(800L)
                    val beforeExchangeItem = currentItem
                    val refreshedSnapshot = queryIpDrawMallSnapshot(IpDrawActivity(activityId, endTime))
                    if (refreshedSnapshot == null) {
                        Log.farm("[自动兑换]: 已调用兑换但未回查确认 [${currentItem.name}] | ${formatIpDrawRpcResult(exchangeJo)}")
                        break
                    }
                    val refreshedItem = refreshedSnapshot.items.firstOrNull { it.skuId == currentItem.skuId }
                    if (!isIpDrawExchangeConfirmed(snapshot, refreshedSnapshot, beforeExchangeItem, refreshedItem)) {
                        val statusText = refreshedItem?.let { formatIpDrawStatus(it).ifBlank { it.itemStatus.ifBlank { it.skuStatus } } }.orEmpty()
                        Log.farm("[自动兑换]: 已调用兑换但未回查确认 [${currentItem.name}] | 回查状态: ${statusText.ifBlank { "UNKNOWN" }}")
                        snapshot = refreshedSnapshot
                        break
                    }

                    confirmedCount++
                    val currentExchanged = data.exchangedCounts[currentItem.skuId] ?: 0
                    data.exchangedCounts[currentItem.skuId] = currentExchanged + 1
                    syncIpDrawShopSnapshot(refreshedSnapshot, data)
                    saveData(userId, data)
                    snapshot = refreshedSnapshot
                    Log.farm("IP抽抽乐商店兑换: ${currentItem.name} (本地累计已换 ${data.exchangedCounts[currentItem.skuId]} 次，剩余碎片: ${snapshot.balanceCent / 100})")
                    if (refreshedItem == null || isIpDrawTerminalExchangeStatus(refreshedItem)) {
                        break
                    }
                    currentItem = refreshedItem
                }
            }
            Log.farm("IP抽抽乐商店任务已处理完毕")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "自动兑换异常", e)
        }
    }

    fun refreshIpChouChouLeExchangeOptionsFromRpc(): List<ExchangeOptionRow> {
        val activity = queryActiveIpDrawActivity() ?: return emptyList()
        val snapshot = queryIpDrawMallSnapshot(activity) ?: return emptyList()
        syncIpDrawShopSnapshot(snapshot)
        val rows = snapshot.items.map { buildIpChouChouLeExchangeOption(it).toOptionRow() }
        ExchangeOptionsCache.save(UserMap.currentUid, ExchangeOptionsRefreshBridge.TARGET_FARM_IP_CHOUCHOULE, rows)
        return rows
    }

    private fun queryActiveIpDrawActivity(): IpDrawActivity? {
        val latest = runCatching {
            JSONObject(AntFarmRpcCall.queryDrawMachineActivity_New("ipDrawMachine", "dailyDrawMachine"))
        }.getOrNull()
        if (latest != null && ResChecker.checkRes(TAG, latest)) {
            latest.optJSONObject("drawMachineActivity")?.let { activity ->
                val activityId = activity.optString("activityId").trim()
                if (activityId.isNotEmpty()) {
                    val activeActivity = IpDrawActivity(activityId, activity.optLong("endTime", 0L))
                    if (isIpDrawActivityActive(activeActivity)) {
                        return activeActivity
                    }
                }
            }
        }

        val legacy = runCatching { JSONObject(AntFarmRpcCall.queryDrawMachineActivity()) }.getOrNull() ?: return null
        if (!ResChecker.checkRes(TAG, legacy)) {
            Log.farm("IP抽抽乐商店💸[获取活动失败: ${formatIpDrawRpcResult(legacy)}]")
            return null
        }
        val activity = legacy.optJSONObject("drawMachineActivity") ?: return null
        val activityId = activity.optString("activityId").trim()
        if (activityId.isBlank()) {
            return null
        }
        val activeActivity = IpDrawActivity(activityId, activity.optLong("endTime", 0L))
        return activeActivity.takeIf { isIpDrawActivityActive(it) }
    }

    private fun queryIpDrawMallSnapshot(activity: IpDrawActivity, pageSize: Int = 10, maxPages: Int = 20): IpDrawMallSnapshot? {
        if (activity.activityId.isBlank()) {
            Log.farm("IP抽抽乐商店💸[缺少 activityId，无法查询]")
            return null
        }
        if (!isIpDrawActivityActive(activity)) {
            return null
        }
        val items = mutableListOf<IpDrawMallItem>()
        val seenSkuIds = linkedSetOf<String>()
        val seenStartIndexes = linkedSetOf<Int>()
        var balanceCent = 0
        var startIndex = 0
        var pageCount = 0

        while (pageCount < maxPages && seenStartIndexes.add(startIndex)) {
            val jo = runCatching {
                JSONObject(AntFarmRpcCall.getItemList(activity.activityId, pageSize, startIndex))
            }.onFailure {
                Log.printStackTrace(TAG, "queryIpDrawMallSnapshot err:", it)
            }.getOrNull() ?: return null

            if (!isIpDrawExchangeSuccess(jo)) {
                Log.farm("IP抽抽乐商店💸[获取列表失败: startIndex=$startIndex | ${formatIpDrawRpcResult(jo)}]")
                return null
            }

            jo.optJSONObject("mallAccountInfoVO")
                ?.optJSONObject("holdingCount")
                ?.takeIf { it.has("cent") }
                ?.let { balanceCent = it.optInt("cent", balanceCent) }

            val itemInfoVOList = jo.optJSONArray("itemInfoVOList")
            if (itemInfoVOList == null || itemInfoVOList.length() == 0) {
                break
            }

            var newItemCount = 0
            for (i in 0 until itemInfoVOList.length()) {
                val itemJo = itemInfoVOList.optJSONObject(i) ?: continue
                parseIpDrawMallItems(itemJo).forEach { item ->
                    if (seenSkuIds.add(item.skuId)) {
                        items.add(item)
                        newItemCount++
                    }
                }
            }

            pageCount++
            if (newItemCount == 0) {
                Log.farm("IP抽抽乐商店💸[分页未发现新商品，停止继续查询: startIndex=$startIndex]")
                break
            }

            val responseNextIndex = if (jo.has("nextStartIndex")) jo.optInt("nextStartIndex", -1) else -1
            val nextStartIndex = if (responseNextIndex > startIndex) responseNextIndex else startIndex + itemInfoVOList.length()
            val hasMore = if (jo.has("hasMore")) jo.optBoolean("hasMore", false) else itemInfoVOList.length() >= pageSize
            if (!hasMore || nextStartIndex <= startIndex) {
                if (hasMore && nextStartIndex <= startIndex) {
                    Log.farm("IP抽抽乐商店💸[分页 nextStartIndex 未前进，停止继续查询: startIndex=$startIndex]")
                }
                break
            }
            startIndex = nextStartIndex
        }

        if (pageCount >= maxPages) {
            Log.farm("IP抽抽乐商店💸[分页达到上限${maxPages}页，停止继续查询]")
        }
        return IpDrawMallSnapshot(activity.activityId, activity.endTime, balanceCent, items)
    }

    private fun queryIpDrawMallItemDetail(item: IpDrawMallItem): IpDrawMallItemDetail? {
        if (item.spuId.isBlank()) {
            return null
        }
        val jo = runCatching {
            JSONObject(AntFarmRpcCall.getIpDrawMallItemDetail(item.spuId))
        }.onFailure {
            Log.printStackTrace(TAG, "queryIpDrawMallItemDetail err:", it)
        }.getOrNull() ?: return null

        if (!isIpDrawExchangeSuccess(jo)) {
            Log.farm("IP抽抽乐商店💸[获取详情失败: ${item.name} | ${formatIpDrawRpcResult(jo)}]")
            return null
        }

        val detailItemJo = jo.optJSONObject("spuItemInfoVO")
        if (detailItemJo == null) {
            Log.farm("IP抽抽乐商店💸[获取详情失败: ${item.name} | 缺少商品详情]")
            return null
        }

        val detailItem = parseIpDrawMallItems(detailItemJo).firstOrNull { it.skuId == item.skuId }
        if (detailItem == null) {
            Log.farm("IP抽抽乐商店💸[获取详情失败: ${item.name} | 未匹配 skuId=${item.skuId}]")
            return null
        }

        val balanceCent = jo.optJSONObject("mallAccountInfoVO")
            ?.optJSONObject("holdingCount")
            ?.takeIf { it.has("cent") }
            ?.optInt("cent")
        return IpDrawMallItemDetail(detailItem, balanceCent)
    }

    private fun isIpDrawActivityActive(activity: IpDrawActivity): Boolean {
        if (activity.endTime > 0 && System.currentTimeMillis() > activity.endTime) {
            Log.farm("IP抽抽乐商店💸[活动已结束: ${activity.activityId}]")
            return false
        }
        return true
    }

    private fun parseIpDrawMallItems(itemJo: JSONObject): List<IpDrawMallItem> {
        val spuId = itemJo.optString("spuId").trim()
        if (spuId.isBlank()) {
            return emptyList()
        }
        val spuName = itemJo.optString("spuName").trim().ifBlank { spuId }
        val itemStatus = itemJo.optString("itemStatus").trim()
        val itemStatusList = itemJo.optJSONArray("itemStatusList")
        val priceCent = itemJo.optJSONObject("minPrice")?.optInt("cent", 0) ?: 0
        val skuList = itemJo.optJSONArray("skuModelList") ?: return emptyList()
        val items = mutableListOf<IpDrawMallItem>()
        for (j in 0 until skuList.length()) {
            val sku = skuList.optJSONObject(j) ?: continue
            val skuId = sku.optString("skuId").trim()
            if (skuId.isBlank()) {
                continue
            }
            val skuName = sku.optString("skuName").trim()
            val displayName = mergeIpDrawItemName(spuName, skuName)
            val skuExtendInfo = sku.optString("skuExtendInfo")
            val limitCount = parseIpDrawLimitCount(skuExtendInfo)
            val skuStatus = sku.optString("itemStatus").trim().ifBlank { sku.optString("skuStatus").trim() }
            val skuStatusList = sku.optJSONArray("itemStatusList") ?: sku.optJSONArray("skuStatusList")
            val userTotalLeftAmount = if (sku.has("userTotalLeftAmount")) sku.optInt("userTotalLeftAmount", -1) else -1
            val statusText = formatIpDrawStatus(itemStatus, itemStatusList, skuStatus, skuStatusList)
            val blockedReason = if (userTotalLeftAmount == 0) {
                "已达兑换限制"
            } else {
                blockedIpDrawMallItemReason(itemStatus, itemStatusList, skuStatus, skuStatusList)
            }
            val safetyDecision = if (blockedReason.isNotBlank()) {
                ExchangeSafety.UNAVAILABLE to blockedReason
            } else {
                ExchangeSafetyRules.classify(
                    textValues = listOf(displayName, skuExtendInfo),
                    defaultReason = "涉及实付或下单链路"
                )
            }
            items.add(
                IpDrawMallItem(
                    spuId = spuId,
                    skuId = skuId,
                    name = displayName,
                    priceCent = priceCent,
                    limitCount = limitCount,
                    itemStatus = itemStatus,
                    itemStatusList = itemStatusList,
                    skuStatus = skuStatus,
                    skuStatusList = skuStatusList,
                    skuExtendInfo = skuExtendInfo,
                    userTotalLeftAmount = userTotalLeftAmount,
                    isIp = skuExtendInfo.contains("\"controlTag\":\"IP限定装扮\"") || skuExtendInfo.contains("IP限定装扮"),
                    isNewEgg = displayName.contains("新蛋卡"),
                    safety = safetyDecision.first,
                    safetyReason = safetyDecision.second
                )
            )
        }
        return items
    }

    private fun mergeIpDrawItemName(spuName: String, skuName: String): String = when {
        skuName.isBlank() || spuName == skuName -> spuName
        skuName.contains(spuName) -> skuName
        spuName.contains(skuName) -> spuName
        else -> spuName + skuName
    }

    private fun parseIpDrawLimitCount(extendInfo: String): Int {
        return runCatching {
            "(\\d+)次".toRegex().find(extendInfo)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        }.getOrDefault(0)
    }

    private fun syncIpDrawShopSnapshot(snapshot: IpDrawMallSnapshot, existingData: IpChouChouLeData? = null): IpChouChouLeData {
        val userId = UserMap.currentUid
        val data = existingData ?: loadData(userId)
        var changed = false
        if (data.activityId != snapshot.activityId) {
            Log.farm("[自动兑换]: 检测到活动变更 (${snapshot.activityId})，重置本地兑换记录并更新商店列表")
            data.activityId = snapshot.activityId
            data.exchangedCounts.clear()
            data.shopItems.clear()
            changed = true
        }
        val latestShopItems = LinkedHashMap<String, String>()
        snapshot.items.forEach { item ->
            latestShopItems[item.skuId] = "${item.name}|${item.limitCount}|${item.priceCent}"
        }
        if (data.shopItems != latestShopItems) {
            data.shopItems.clear()
            data.shopItems.putAll(latestShopItems)
            changed = true
        }
        if (changed || existingData == null) {
            saveData(userId, data)
        }
        return data
    }

    private fun buildCustomExchangeSequence(
        customMap: MutableMap<String?, Int?>?,
        snapshot: IpDrawMallSnapshot,
        data: IpChouChouLeData
    ): List<Pair<IpDrawMallItem, Int>> {
        if (customMap.isNullOrEmpty()) {
            return emptyList()
        }
        val result = mutableListOf<Pair<IpDrawMallItem, Int>>()
        val missingCustomSkuIds = linkedSetOf<String>()
        customMap.entries.forEach { entry ->
            val skuId = entry.key?.trim().orEmpty()
            val targetCount = entry.value ?: 0
            if (skuId.isBlank() || targetCount <= 0) {
                return@forEach
            }
            val item = snapshot.items.firstOrNull { it.skuId == skuId }
            if (item == null) {
                missingCustomSkuIds.add(skuId)
                return@forEach
            }
            val alreadyExchanged = data.exchangedCounts[skuId] ?: 0
            val needToExchange = targetCount - alreadyExchanged
            if (needToExchange > 0) {
                result.add(item to needToExchange)
            } else {
                Log.farm("[自动兑换]: [${item.name}] 已达到自定义兑换数量($targetCount)，跳过")
            }
        }
        missingCustomSkuIds.forEach { skuId ->
            Log.farm("[自动兑换]: 自定义商品[$skuId] 当前商店未找到，已跳过")
        }
        return result
    }

    private fun buildDefaultExchangeSequence(snapshot: IpDrawMallSnapshot): List<Pair<IpDrawMallItem, Int>> {
        val sortedItems = snapshot.items.sortedWith { a, b ->
            when {
                a.isIp != b.isIp -> if (a.isIp) -1 else 1
                a.isNewEgg != b.isNewEgg -> if (a.isNewEgg) 1 else -1
                else -> b.priceCent.compareTo(a.priceCent)
            }
        }

        for (item in sortedItems) {
            if (item.safety != ExchangeSafety.AUTO) continue
            val blockReason = blockedIpDrawMallItemReason(item)
            if (blockReason.isNotBlank()) {
                if (shouldStopDefaultExchangeForNoEnoughPoint(item)) {
                    Log.farm("[自动兑换]: 最高价值项 [${item.name}] 碎片不足，等攒够再换，终止本次兑换")
                    return emptyList()
                }
                continue
            }
            if (item.isNewEgg) continue
            if (item.priceCent > 0 && snapshot.balanceCent < item.priceCent) {
                Log.farm("[自动兑换]: 最高价值项 [${item.name}] 碎片不足，等攒够再换，终止本次兑换")
                return emptyList()
            }
            break
        }
        return sortedItems.map { it to (if (it.limitCount > 0) it.limitCount else 1) }
    }

    private fun buildIpChouChouLeExchangeOption(item: IpDrawMallItem): ExchangeItem {
        val effectTags = ExchangeEffectCatalog.tagsFor(ExchangeEffectCatalog.SOURCE_FARM_PARADISE, item.name)
        val statusText = formatIpDrawStatus(item)
        return ExchangeItem(
            id = item.skuId,
            name = item.name,
            cost = ExchangeCost(pointText = "${item.priceCent / 100}碎片"),
            limit = ExchangeLimit(statusText = listOf(
                item.limitCount.takeIf { it > 0 }?.let { "限购${it}次" }.orEmpty(),
                statusText
            ).filter { it.isNotBlank() }.joinToString("、")),
            safety = item.safety,
            safetyReason = item.safetyReason,
            effectTags = effectTags,
            displayMeta = ExchangeEffectCatalog.displayMeta(
                ExchangeEffectCatalog.SOURCE_FARM_PARADISE,
                item.name,
                item.safety,
                item.safetyReason,
                effectTags
            ).copy(sourceModule = "庄园装扮抽抽乐")
        )
    }

    private fun isIpDrawExchangeConfirmed(
        beforeSnapshot: IpDrawMallSnapshot,
        afterSnapshot: IpDrawMallSnapshot,
        beforeItem: IpDrawMallItem,
        refreshedItem: IpDrawMallItem?
    ): Boolean {
        if (afterSnapshot.balanceCent < beforeSnapshot.balanceCent) {
            return true
        }
        if (refreshedItem != null &&
            beforeItem.userTotalLeftAmount > 0 &&
            refreshedItem.userTotalLeftAmount in 0 until beforeItem.userTotalLeftAmount
        ) {
            return true
        }
        if (refreshedItem == null) {
            return true
        }
        return isIpDrawTerminalExchangeStatus(refreshedItem)
    }

    private fun isIpDrawTerminalExchangeStatus(item: IpDrawMallItem): Boolean {
        val statuses = collectIpDrawStatuses(item.itemStatus, item.itemStatusList, item.skuStatus, item.skuStatusList)
        return statuses.any { status ->
            status == "REACH_LIMIT" ||
                status == "REACH_USER_HOLD_LIMIT" ||
                status.contains("LIMIT")
        }
    }

    private fun isIpDrawNoEnoughPointStatus(item: IpDrawMallItem): Boolean {
        val statuses = collectIpDrawStatuses(item.itemStatus, item.itemStatusList, item.skuStatus, item.skuStatusList)
        return statuses.any { it == "NO_ENOUGH_POINT" }
    }

    private fun shouldStopDefaultExchangeForNoEnoughPoint(item: IpDrawMallItem): Boolean {
        return isIpDrawNoEnoughPointStatus(item) && !isIpDrawTerminalExchangeStatus(item)
    }

    private fun blockedIpDrawMallItemReason(item: IpDrawMallItem): String {
        if (item.userTotalLeftAmount == 0) {
            return "已达兑换限制"
        }
        return blockedIpDrawMallItemReason(item.itemStatus, item.itemStatusList, item.skuStatus, item.skuStatusList)
    }

    private fun blockedIpDrawMallItemReason(
        itemStatus: String,
        itemStatusList: JSONArray?,
        skuStatus: String,
        skuStatusList: JSONArray?
    ): String {
        val statuses = collectIpDrawStatuses(itemStatus, itemStatusList, skuStatus, skuStatusList)
        val blocked = statuses.firstOrNull { status ->
            status == "REACH_LIMIT" ||
                status == "REACH_USER_HOLD_LIMIT" ||
                status == "NO_ENOUGH_POINT" ||
                status == "SOLD_OUT" ||
                status == "EXCHANGE_END" ||
                status == "END" ||
                status.contains("LIMIT") ||
                status.contains("SOLD_OUT")
        } ?: return ""
        return ipDrawStatusName(blocked)
    }

    private fun formatIpDrawStatus(item: IpDrawMallItem): String =
        formatIpDrawStatus(item.itemStatus, item.itemStatusList, item.skuStatus, item.skuStatusList)

    private fun formatIpDrawStatus(
        itemStatus: String,
        itemStatusList: JSONArray?,
        skuStatus: String,
        skuStatusList: JSONArray?
    ): String {
        return collectIpDrawStatuses(itemStatus, itemStatusList, skuStatus, skuStatusList)
            .map { ipDrawStatusName(it) }
            .joinToString("、")
    }

    private fun collectIpDrawStatuses(
        itemStatus: String,
        itemStatusList: JSONArray?,
        skuStatus: String,
        skuStatusList: JSONArray?
    ): LinkedHashSet<String> {
        val statuses = linkedSetOf<String>()
        itemStatus.takeIf { it.isNotBlank() }?.let { statuses.add(it) }
        skuStatus.takeIf { it.isNotBlank() }?.let { statuses.add(it) }
        listOf(itemStatusList, skuStatusList).forEach { list ->
            if (list != null) {
                for (i in 0 until list.length()) {
                    list.optString(i).takeIf { it.isNotBlank() }?.let { statuses.add(it) }
                }
            }
        }
        return statuses
    }

    private fun ipDrawStatusName(status: String): String {
        return when (status) {
            "REACH_USER_HOLD_LIMIT" -> "已拥有/已达持有限制"
            "REACH_LIMIT" -> "已达兑换限制"
            "NO_ENOUGH_POINT" -> "碎片不足"
            "SOLD_OUT" -> "已售罄"
            "EXCHANGE_END" -> "兑换已结束"
            else -> status
        }
    }

    private fun isIpDrawExchangeSuccess(jo: JSONObject): Boolean {
        return ExchangeSafetyRules.isSuccessResponse(jo) || ResChecker.checkRes(TAG, jo)
    }

    private fun formatIpDrawRpcResult(jo: JSONObject): String {
        val parts = mutableListOf<String>()
        if (jo.has("success")) {
            parts.add("success=${jo.optBoolean("success")}")
        }
        listOf("resultCode", "code", "memo", "resultDesc", "desc").forEach { key ->
            jo.optString(key).takeIf { it.isNotBlank() }?.let { parts.add("$key=$it") }
        }
        return parts.joinToString(" | ").ifBlank { jo.toString() }
    }
}

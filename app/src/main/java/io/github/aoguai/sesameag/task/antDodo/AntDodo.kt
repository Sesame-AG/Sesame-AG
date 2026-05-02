package io.github.aoguai.sesameag.task.antDodo

import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import io.github.aoguai.sesameag.entity.AlipayUser
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.util.FriendGuard
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.TimeUtil

class AntDodo : ModelTask() {

    private var collectToFriend: BooleanModelField? = null
    private var collectToFriendType: ChoiceModelField? = null
    private var collectToFriendList: SelectModelField? = null
    private var sendFriendCard: SelectModelField? = null
    private var useProp: BooleanModelField? = null
    private var usePropCollectTimes7Days: BooleanModelField? = null
    private var usePropCollectHistoryAnimal7Days: BooleanModelField? = null
    private var usePropCollectToFriendTimes7Days: BooleanModelField? = null
    private var autoGenerateBook: BooleanModelField? = null
    private val handledTaskFinishes = LinkedHashSet<String>()
    private val handledTaskAwards = LinkedHashSet<String>()

    override fun getName(): String = "神奇物种"

    override fun getGroup(): ModelGroup = ModelGroup.FOREST

    override fun getIcon(): String = "AntDodo.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            BooleanModelField("collectToFriend", "帮抽卡 | 开启", false).withDesc(
                "开启后按下面的名单规则帮好友抽神奇物种卡片。"
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
            SelectModelField(
                "collectToFriendList",
                "帮抽卡 | 好友列表",
                LinkedHashSet<String?>(),
                AlipayUser::getFriendListAsMapperEntity
            ).withDesc("设置帮抽卡规则作用的好友名单。").also { collectToFriendList = it }
        )
        modelFields.addField(
            SelectModelField(
                "sendFriendCard",
                "送卡片好友列表(当前图鉴所有卡片)",
                LinkedHashSet<String?>(),
                AlipayUser::getFriendListAsMapperEntity
            ).withDesc("列表不为空时，会把当前图鉴可赠送的卡片和新抽到的三星卡送给列表中的首个有效好友。").also {
                sendFriendCard = it
            }
        )
        modelFields.addField(
            BooleanModelField("useProp", "使用道具 | 所有", false).withDesc(
                "自动使用当前可消费的神奇物种道具，对所有支持的道具类型生效。"
            ).also { useProp = it }
        )
        modelFields.addField(
            BooleanModelField("usePropCollectTimes7Days", "使用道具 | 抽卡道具", false).withDesc(
                "单独开启后仅使用抽卡类道具；开启“使用道具 | 所有”时也会一起生效。"
            ).also { usePropCollectTimes7Days = it }
        )
        modelFields.addField(
            BooleanModelField("usePropCollectHistoryAnimal7Days", "使用道具 | 抽历史卡道具", false).withDesc(
                "单独开启后仅使用历史卡抽卡道具；开启“使用道具 | 所有”时也会一起生效。"
            ).also { usePropCollectHistoryAnimal7Days = it }
        )
        modelFields.addField(
            BooleanModelField("usePropCollectToFriendTimes7Days", "使用道具 | 抽好友卡道具", false).withDesc(
                "单独开启后仅使用好友卡抽卡道具；开启“使用道具 | 所有”时也会一起生效。"
            ).also { usePropCollectToFriendTimes7Days = it }
        )
        modelFields.addField(
            BooleanModelField("autoGenerateBook", "自动合成图鉴", false).withDesc(
                "图鉴显示“已集齐”时自动合成对应勋章。"
            ).also { autoGenerateBook = it }
        )
        return modelFields
    }

    override fun check(): Boolean {
        return when {
            TaskCommon.IS_ENERGY_TIME -> {
                Log.forest(TAG, "⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，停止执行${getName()}任务！")
                false
            }
            TaskCommon.IS_MODULE_SLEEP_TIME -> {
                Log.forest(TAG, "💤 模块休眠时间【${BaseModel.modelSleepTime.value}】停止执行${getName()}任务！")
                false
            }
            else -> true
        }
    }

    override fun runJava() {
        try {
            Log.forest(TAG, "执行开始-${getName()}")
            handledTaskFinishes.clear()
            handledTaskAwards.clear()
            receiveTaskAward()
            propList()
            collect()
            if (collectToFriend?.value == true) {
                var friendCollectPasses = 0
                while (friendCollectPasses < 2 && collectToFriend()) {
                    friendCollectPasses++
                    receiveTaskAward()
                    propList()
                }
            }
            if (autoGenerateBook?.value == true) {
                autoGenerateBook()
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "start.run err:")
            Log.printStackTrace(TAG, t)
        } finally {
            Log.forest(TAG, "执行结束-${getName()}")
        }
    }

    private fun lastDay(endDate: String): Boolean {
        val timeStep = System.currentTimeMillis()
        val endTimeStep = TimeUtil.timeToStamp(endDate)
        return timeStep < endTimeStep && (endTimeStep - timeStep) < 86400000L
    }

    private fun in8Days(endDate: String): Boolean {
        val timeStep = System.currentTimeMillis()
        val endTimeStep = TimeUtil.timeToStamp(endDate)
        return timeStep < endTimeStep && (endTimeStep - timeStep) < 691200000L
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
                    Log.forest(TAG, "神奇物种卡片今日收集完成！")
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
                val animalBook = data.getJSONObject("animalBook")
                val bookId = animalBook.getString("bookId")
                val endDate = "${animalBook.getString("endDate")} 23:59:59"
                receiveTaskAward()
                if (!in8Days(endDate) || lastDay(endDate))
                    propList()
                val ja = data.getJSONArray("limit")
                var index = -1
                for (i in 0 until ja.length()) {
                    jo = ja.getJSONObject(i)
                    if ("DAILY_COLLECT" == jo.getString("actionCode")) {
                        index = i
                        break
                    }
                }
                val giftTargetUserId = resolveSendFriendCardTarget()
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
                            Log.forest("神奇物种🦕[$ecosystem]#$name")
                            if (giftTargetUserId != null) {
                                val fantasticStarQuantity = animal.optInt("fantasticStarQuantity", 0)
                                if (fantasticStarQuantity == 3) {
                                    sendCard(animal, giftTargetUserId)
                                }
                            }
                        } else {
                            Log.runtime(TAG, jo.getString("resultDesc"))
                        }
                    }
                }
                if (giftTargetUserId != null) {
                    sendAntDodoCard(bookId, giftTargetUserId)
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
            val businessDrivenTasks = LinkedHashSet(listOf("HELP_FRIEND_COLLECT"))
            while (!Thread.currentThread().isInterrupted) {
                var doubleCheck = false
                val response = AntDodoRpcCall.taskList()
                if (response.isNullOrEmpty()) {
                    Log.runtime(TAG, "taskList返回空")
                    break
                }
                val jsonResponse = JSONObject(response)
                if (!ResChecker.checkRes(TAG, jsonResponse)) {
                    Log.forest(TAG, "查询任务列表失败：${jsonResponse.optString("resultDesc")}")
                    break
                }
                val taskGroupInfoList = jsonResponse.optJSONObject("data")?.optJSONArray("taskGroupInfoList") ?: break
                for (i in 0 until taskGroupInfoList.length()) {
                    val antDodoTask = taskGroupInfoList.getJSONObject(i)
                    val taskInfoList = antDodoTask.getJSONArray("taskInfoList")
                    for (j in 0 until taskInfoList.length()) {
                        val taskInfo = taskInfoList.getJSONObject(j)
                        val taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo")
                        val bizInfo = JSONObject(taskBaseInfo.optString("bizInfo", "{}"))
                        val taskType = taskBaseInfo.getString("taskType")
                        val taskTitle = bizInfo.optString("taskTitle", taskType)
                        val awardCount = bizInfo.optString("awardCount", "1")
                        val sceneCode = taskBaseInfo.getString("sceneCode")
                        val taskStatus = taskBaseInfo.getString("taskStatus")
                        val taskKey = buildTaskKey(sceneCode, taskType)
                        when {
                            TaskStatus.FINISHED.name == taskStatus -> {
                                if (handledTaskAwards.contains(taskKey)) {
                                    continue
                                }
                                val awardResponse = AntDodoRpcCall.receiveTaskAward(sceneCode, taskType)
                                if (awardResponse.isNullOrEmpty()) {
                                    Log.runtime(TAG, "receiveTaskAward返回空")
                                    continue
                                }
                                val joAward = JSONObject(awardResponse)
                                if (isDodoTaskRpcSuccess(joAward)) {
                                    handledTaskAwards.add(taskKey)
                                    doubleCheck = true
                                    Log.forest("任务奖励🎖️[$taskTitle]#${awardCount}个")
                                } else {
                                    Log.forest(TAG, "领取失败[$taskTitle]：${joAward.optString("resultDesc", joAward.toString())}")
                                    if (isTaskTerminalFailure(joAward)) {
                                        handledTaskAwards.add(taskKey)
                                    }
                                }
                            }
                            TaskStatus.TODO.name == taskStatus -> {
                                if (businessDrivenTasks.contains(taskType)) {
                                    Log.runtime(TAG, "任务等待业务动作完成[$taskTitle]")
                                    continue
                                }
                                if (TaskBlacklist.isTaskInBlacklist(TASK_BLACKLIST_MODULE, taskType) ||
                                    TaskBlacklist.isTaskInBlacklist(TASK_BLACKLIST_MODULE, taskTitle)
                                ) {
                                    continue
                                }
                                if (handledTaskFinishes.contains(taskKey)) {
                                    continue
                                }

                                val finishResponse = finishTodoTask(taskBaseInfo, bizInfo, sceneCode, taskType)
                                if (finishResponse.isNullOrEmpty()) {
                                    Log.runtime(TAG, "finishTask返回空")
                                    continue
                                }
                                val joFinishTask = JSONObject(finishResponse)
                                if (isDodoTaskRpcSuccess(joFinishTask)) {
                                    handledTaskFinishes.add(taskKey)
                                    Log.forest("物种任务🧾️[$taskTitle]")
                                    doubleCheck = true
                                } else {
                                    val errorCode = joFinishTask.optString("code")
                                        .ifBlank { joFinishTask.optString("resultCode") }
                                    val resultDesc = joFinishTask.optString("desc")
                                        .ifBlank { joFinishTask.optString("resultDesc") }
                                    Log.forest(
                                        TAG,
                                        "完成任务失败[$taskTitle] code=${errorCode.ifBlank { "UNKNOWN" }} msg=$resultDesc"
                                    )
                                    if (isTaskTerminalFailure(joFinishTask)) {
                                        handledTaskFinishes.add(taskKey)
                                    }
                                    val blacklistReason = errorCode.ifBlank { resultDesc }
                                    if (blacklistReason.isNotBlank()) {
                                        TaskBlacklist.autoAddToBlacklist(
                                            TASK_BLACKLIST_MODULE,
                                            taskType,
                                            taskTitle,
                                            blacklistReason
                                        )
                                    }
                                }
                            }
                        }
                        GlobalThreadPools.sleepCompat(500)
                    }
                }
                if (!doubleCheck) break
            }
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

    private fun isDodoTaskRpcSuccess(response: JSONObject): Boolean {
        return response.optBoolean("success") ||
            response.optString("code") == "100000000" ||
            response.optString("resultCode").equals("SUCCESS", ignoreCase = true)
    }

    private fun isTaskTerminalFailure(response: JSONObject): Boolean {
        val code = response.optString("code")
            .ifBlank { response.optString("errorCode") }
            .ifBlank { response.optString("resultCode") }
        val desc = response.optString("desc")
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("resultDesc") }
        return code == "400000030" ||
            code == "400000012" ||
            desc.contains("任务已完结") ||
            desc.contains("权益获取次数超过上限")
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

    private fun propList() {
        try {
            val giftTargetUserId = resolveSendFriendCardTarget()
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
                        Log.forest(TAG, "神奇物种道具跳过：未找到可使用的道具")
                        return
                    }
                    for (i in 0 until propList.length()) {
                        val prop = propList.getJSONObject(i)
                        val propType = prop.optString("propType")
                        val propName = prop.optJSONObject("propConfig")?.optString("propName")
                            ?.takeIf { it.isNotBlank() } ?: propType
                        if (!isUsePropType(propType)) {
                            Log.forest(TAG, "神奇物种道具跳过[$propName]：配置未开启")
                            continue
                        }
                        val propIdList = prop.optJSONArray("propIdList")
                        if (propIdList == null || propIdList.length() == 0) {
                            Log.runtime(TAG, "神奇物种道具[$propName]缺少propIdList")
                            continue
                        }
                        val propId = propIdList.getString(0)
                        val holdsNum = prop.optInt("holdsNum", propIdList.length())
                        val consumeTargetResult = resolvePropConsumeTarget(propType)
                        val consumeTarget = consumeTargetResult?.target
                        if (isUniversalCardProp(propType)) {
                            if (consumeTargetResult == null || !consumeTargetResult.querySucceeded) {
                                Log.forest(TAG, "神奇物种道具跳过[$propName]：目标卡查询失败")
                                continue
                            }
                            if (consumeTarget == null) {
                                Log.forest(TAG, "神奇物种道具跳过[$propName]：未找到可兑换的目标卡片")
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
                            Log.forest(TAG, "神奇物种道具使用失败[$propName]：${joConsume.optString("resultDesc", "未知错误")}")
                            Log.runtime(joConsume.toString())
                            continue
                        }
                        val useResult = joConsume.optJSONObject("data")?.optJSONObject("useResult")
                        val animal = useResult?.optJSONObject("animal")
                        if (animal != null) {
                            val ecosystem = animal.optString("ecosystem")
                            val name = animal.optString("name")
                            Log.forest("使用道具🎭[$propName]#${formatAnimalDisplayName(ecosystem, name)}")
                            if (giftTargetUserId != null && isUniversalCardProp(propType)) {
                                val fantasticStarQuantity = animal.optInt("fantasticStarQuantity", 0)
                                if (fantasticStarQuantity == 3) {
                                    sendCard(animal, giftTargetUserId)
                                }
                            }
                        } else {
                            Log.forest("使用道具🎭[$propName]")
                        }
                        logPropRefreshState(propType, propName, consumeTarget, animal)
                        GlobalThreadPools.sleepCompat(300)
                        if (holdsNum > 1) {
                            continue@th
                        }
                    }
                }
                break
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "AntDodo PropList err:")
            Log.printStackTrace(TAG, th)
        }
    }

    private fun isUsePropType(propType: String): Boolean {
        var usePropType = useProp?.value ?: false
        usePropType = when (resolvePropKind(propType)) {
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
            "COLLECT_TIMES_7_DAYS",
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
            if (isCollectedAnimal(collectDetail)) {
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

    private fun isCollectedAnimal(collectDetail: JSONObject?): Boolean {
        if (collectDetail == null) {
            return false
        }
        return collectDetail.optBoolean("collect") ||
            collectDetail.optBoolean("hasCollected") ||
            collectDetail.optInt("count", 0) > 0
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
            Log.forest(TAG, "神奇物种道具刷新[$propName]：${refreshParts.joinToString("，")}")
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
                    return isCollectedAnimal(item.optJSONObject("collectDetail"))
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
        val configuredUsers = sendFriendCard?.value ?: emptySet()
        if (configuredUsers.isEmpty()) {
            return null
        }
        val availableFriends = queryDodoAvailableFriendIds()
        if (availableFriends.isEmpty()) {
            return null
        }
        for (userId in configuredUsers) {
            val safeUserId = FriendGuard.normalizeUserId(userId) ?: continue
            if (FriendGuard.shouldSkipFriend(safeUserId, TAG, "神奇物种送卡")) {
                continue
            }
            if (availableFriends.contains(safeUserId)) {
                return safeUserId
            }
            Log.forest(TAG, "神奇物种送卡跳过[${UserMap.getMaskName(safeUserId) ?: safeUserId}]：对方未开通神奇物种")
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

    private fun sendAntDodoCard(bookId: String, targetUser: String) {
        try {
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
                    val count = animalForUser.getJSONObject("collectDetail").optInt("count")
                    if (count <= 0)
                        continue
                    val animal = animalForUser.getJSONObject("animal")
                    for (j in 0 until count) {
                        sendCard(animal, targetUser)
                        GlobalThreadPools.sleepCompat(500L)
                    }
                }
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
                Log.forest("赠送卡片🦕[${UserMap.getMaskName(targetUser)}]#$ecosystem-$name")
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
                    var isCollectToFriend = collectToFriendList?.value?.contains(useId) ?: false
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
                        val ecosystem = jo.getJSONObject("data").getJSONObject("animal").getString("ecosystem")
                        val name = jo.getJSONObject("data").getJSONObject("animal").getString("name")
                        val userName = UserMap.getMaskName(useId)
                        Log.forest("神奇物种🦕帮好友[$userName]抽卡[$ecosystem]#$name")
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

    private fun autoGenerateBook() {
        try {
            AntDodoRpcCall.invalidateBookCache()
            val generatedBookIds = LinkedHashSet<String>()
            generateCurrentBookIfReminded()?.let { generatedBookIds.add(it) }
            AntDodoRpcCall.invalidateBookCache()
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
                if (!isCollectedAnimal(collectDetail)) {
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
            Log.forest(TAG, "合成图鉴失败[${ecosystem.ifBlank { bookId }}]：${jo.optString("resultDesc", jo.toString())}")
            return false
        }
        Log.forest("神奇物种🦕合成勋章[${ecosystem.ifBlank { bookId }}]")
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
        UNIVERSAL_CARD,
        HISTORY_CARD,
        FRIEND_CARD,
        OTHER
    }

    private data class PropConsumeTarget(
        val bookId: String,
        val animalId: String,
        val ecosystem: String,
        val name: String
    )

    private data class PropTargetQueryResult(
        val target: PropConsumeTarget?,
        val querySucceeded: Boolean
    )

    companion object {
        private val TAG = AntDodo::class.java.simpleName
        private const val TASK_BLACKLIST_MODULE = "神奇物种"
    }
}


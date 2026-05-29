package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.extensions.JSONExtensions.toJSONArray
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.task.antFarm.AntFarm.AnimalFeedStatus
import io.github.aoguai.sesameag.task.antFarm.AntFarm.AnimalInteractStatus
import io.github.aoguai.sesameag.task.antFarm.AntFarm.FamilyAssignStrategy
import io.github.aoguai.sesameag.task.antSports.AntSportsRpcCall
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.RandomUtil
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.friend.FriendSelectionResolver
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Objects
import kotlin.math.abs

data object AntFarmFamily {
    private const val TAG = "AntFarmFamily"
    private const val DAILY_DONATE_TASK_ID = "DAILY_DONATE"

    /**
     * 家庭ID
     */
    private var groupId: String = ""

    /**
     * 家庭名称
     */
    private var groupName: String = ""

    /**
     * 家庭成员对象
     */
    private var familyAnimals: JSONArray = JSONArray()

    /**
     * 家庭成员列表
     */
    private var familyUserIds: MutableList<String> = mutableListOf()

    /**
     * 互动功能列表
     */
    private var familyInteractActions: JSONArray = JSONArray()

    /**
     * 美食配置对象
     */
    private var eatTogetherConfig: JSONObject = JSONObject()

    private data class FamilyAssignCandidate(
        val userId: String,
        val userName: String,
        val todayIntimateNum: Int,
        val totalIntimateNum: Int,
        val userDonateCount: Int
    )

    private fun hasFamilyOption(familyOptions: SelectModelField, vararg optionKeys: String): Boolean {
        val values = familyOptions.value ?: return false
        return optionKeys.any { values.contains(it) }
    }

    private fun extractGreetingContent(response: JSONObject?): String {
        if (response == null) {
            return ""
        }
        val directKeys = listOf("content", "expandContent", "deliverContent", "msgContent", "text")
        directKeys.forEach { key ->
            val value = response.optString(key).trim()
            if (value.isNotEmpty()) {
                return value
            }
        }
        val data = response.optJSONObject("data")
        directKeys.forEach { key ->
            val value = data?.optString(key).orEmpty().trim()
            if (value.isNotEmpty()) {
                return value
            }
        }
        return ""
    }

    private fun buildFamilyUserIds(memberList: JSONArray?): MutableList<String> {
        if (memberList == null) {
            return mutableListOf()
        }
        return mutableListOf<String>().apply {
            for (index in 0 until memberList.length()) {
                val userId = memberList.optJSONObject(index)?.optString("userId").orEmpty()
                if (userId.isNotBlank()) {
                    add(userId)
                }
            }
        }
    }

    fun isFamilyMember(userId: String?): Boolean {
        val normalizedUserId = userId?.trim().orEmpty()
        return normalizedUserId.isNotBlank() && familyUserIds.contains(normalizedUserId)
    }

    private fun refreshFamilyBaseInfoFromQueryFamilyInfo() {
        try {
            val queryRes = JSONObject(AntFarmRpcCall.queryFamilyInfo())
            if (!ResChecker.checkRes(TAG, queryRes)) {
                return
            }
            if (groupId.isBlank()) {
                groupId = queryRes.optString("groupId")
            }
            if (groupName.isBlank()) {
                groupName = queryRes.optString("familyName", queryRes.optString("groupName"))
            }
            if (familyUserIds.isEmpty()) {
                familyUserIds = buildFamilyUserIds(queryRes.optJSONArray("familyMemberInfoList"))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "refreshFamilyBaseInfoFromQueryFamilyInfo err:", t)
        }
    }

    private fun queryFamilyTreadMillState(): JSONObject? {
        return try {
            val treadMillRes = JSONObject(AntFarmRpcCall.familyTreadMill())
            if (!ResChecker.checkRes(TAG, treadMillRes)) {
                null
            } else {
                treadMillRes
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryFamilyTreadMillState err:", t)
            null
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) {
            return null
        }
        return optInt(key)
    }

    private fun normalizeAssignableUserIds(userIds: Collection<String>): List<String> {
        val currentUid = UserMap.currentUid
        return userIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != currentUid }
            .distinct()
            .toList()
    }

    private fun selectRandomAssignableUser(userIds: Collection<String>): String? {
        val candidates = normalizeAssignableUserIds(userIds)
        if (candidates.isEmpty()) {
            return null
        }
        return candidates[RandomUtil.nextInt(0, candidates.size - 1)]
    }

    private fun selectLowestTodayIntimacyUser(userIds: Collection<String>): String? {
        val candidateIds = normalizeAssignableUserIds(userIds)
        if (candidateIds.isEmpty()) {
            return null
        }
        val candidateIdSet = candidateIds.toSet()
        val treadMillJo = queryFamilyTreadMillState() ?: run {
            Log.farm("家庭任务🏡[使用顶梁柱特权] 获取家庭贡献信息失败，回退随机安排")
            return null
        }
        val memberList = treadMillJo.optJSONArray("familyMemberInfoList") ?: run {
            Log.farm("家庭任务🏡[使用顶梁柱特权] familyTreadMill 缺少 familyMemberInfoList，回退随机安排")
            return null
        }
        val candidates = mutableListOf<FamilyAssignCandidate>()
        for (index in 0 until memberList.length()) {
            val member = memberList.optJSONObject(index) ?: continue
            val userId = member.optString("userId").trim()
            if (userId.isBlank() || userId == UserMap.currentUid || member.optBoolean("currentUser", false)) {
                continue
            }
            if (!candidateIdSet.contains(userId)) {
                continue
            }
            val todayIntimateNum = member.optIntOrNull("todayIntimateNum")
            val totalIntimateNum = member.optIntOrNull("totalIntimateNum")
            val userDonateCount = member.optIntOrNull("userDonateCount")
            if (todayIntimateNum == null || totalIntimateNum == null || userDonateCount == null) {
                Log.farm("家庭任务🏡[使用顶梁柱特权] 成员贡献字段不完整，回退随机安排")
                return null
            }
            candidates.add(
                FamilyAssignCandidate(
                    userId = userId,
                    userName = member.optString("userName").trim(),
                    todayIntimateNum = todayIntimateNum,
                    totalIntimateNum = totalIntimateNum,
                    userDonateCount = userDonateCount
                )
            )
        }
        if (candidates.size < candidateIds.size) {
            Log.farm("家庭任务🏡[使用顶梁柱特权] familyTreadMill 未返回完整家庭成员贡献信息，回退随机安排")
            return null
        }
        val selected = candidates.sortedWith(
            compareBy<FamilyAssignCandidate> { it.todayIntimateNum }
                .thenBy { it.totalIntimateNum }
                .thenBy { it.userDonateCount }
                .thenBy { it.userId }
        ).firstOrNull() ?: return null
        val displayName = UserMap.getMaskName(selected.userId)
            ?: selected.userName.ifBlank { selected.userId }
        Log.farm(
            "家庭任务🏡[使用顶梁柱特权] 优先安排今日亲密值最低成员[$displayName] " +
                "today=${selected.todayIntimateNum}, total=${selected.totalIntimateNum}, donate=${selected.userDonateCount}"
        )
        return selected.userId
    }

    private fun queryDailyDonateTaskAfterPublicDonation(): JSONObject? {
        val taskLogName = "家庭任务🏠每日捐蛋"
        val taskJo = JSONObject(AntFarmRpcCall.listFamilyTask())
        if (!ResChecker.checkRes(TAG, taskJo)) {
            Log.farm("$taskLogName#listFamilyTask 调用失败: ${formatFamilyTaskFailure(taskJo)}")
            return null
        }
        val familyTasks = taskJo.optJSONArray("familyTasks") ?: run {
            Log.farm("$taskLogName#familyTasks 为空")
            return null
        }
        for (index in 0 until familyTasks.length()) {
            val task = familyTasks.optJSONObject(index) ?: continue
            val bizKey = task.optString("bizKey")
            val taskId = task.optString("taskId")
            if (bizKey == DAILY_DONATE_TASK_ID || taskId == DAILY_DONATE_TASK_ID) {
                return task
            }
        }
        Log.farm("$taskLogName#未找到 DAILY_DONATE")
        return null
    }

    private fun extractFamilyTaskAwardCount(task: JSONObject?): Int {
        if (task == null) {
            return 0
        }
        val awardCount = task.optInt("awardCount", 0)
        val canReceiveAwardCount = task.optInt("canReceiveAwardCount", 0)
        val alreadyReceiveStageAwardCount = task.optInt("alreadyReceiveStageAwardCount", 0)
        return maxOf(awardCount, canReceiveAwardCount, alreadyReceiveStageAwardCount)
    }

    private fun formatFamilyTaskFailure(jo: JSONObject): String {
        val resultDesc = jo.optString("resultDesc")
        val memo = jo.optString("memo")
        val resultCode = jo.optString("resultCode")
        return "resultDesc=${resultDesc.ifBlank { "<blank>" }}, " +
            "memo=${memo.ifBlank { "<blank>" }}, " +
            "resultCode=${resultCode.ifBlank { "<blank>" }}, response=$jo"
    }

    private fun logDailyDonateTaskAward(taskTitle: String, task: JSONObject?) {
        val awardCount = extractFamilyTaskAwardCount(task)
        if (awardCount > 0) {
            Log.farm("家庭任务🏠$taskTitle🥚#亲密度+$awardCount")
        } else {
            Log.farm("家庭任务🏠$taskTitle🥚")
        }
    }

    private fun receiveDailyDonateTaskAward(taskTitle: String, task: JSONObject?): Boolean {
        val taskStatus = task?.optString("taskStatus").orEmpty()
        if (taskStatus == "RECEIVED") {
            logDailyDonateTaskAward(taskTitle, task)
            return true
        }
        if (taskStatus != "FINISHED") {
            return false
        }
        val taskId = task?.optString("taskId").orEmpty().ifBlank { DAILY_DONATE_TASK_ID }
        val receiveRes = JSONObject(AntFarmRpcCall.familyReceiveFarmTaskAward(taskId))
        if (!ResChecker.checkRes(TAG, receiveRes)) {
            val failMsg = formatFamilyTaskFailure(receiveRes)
            Log.farm("家庭任务🏠$taskTitle#任务已完成，但领取亲密度失败: $failMsg")
            return false
        }
        logDailyDonateTaskAward(taskTitle, task)
        return true
    }

    internal fun confirmDailyDonateTaskAfterPublicDonation() {
        try {
            val donateTask = queryDailyDonateTaskAfterPublicDonation() ?: return
            val taskStatus = donateTask.optString("taskStatus")
            val taskTitle = donateTask.optString("title", "每日捐蛋做好事")
            when (taskStatus) {
                "RECEIVED" -> {
                    logDailyDonateTaskAward(taskTitle, donateTask)
                }

                "FINISHED" -> {
                    if (!receiveDailyDonateTaskAward(taskTitle, donateTask)) {
                        Log.farm("家庭任务🏠$taskTitle#今日已完成，但亲密度领取失败，保留后续重试")
                    }
                }

                "TODO" -> {
                    Log.farm("家庭任务🏠$taskTitle#普通捐蛋已成功，但家庭任务仍未刷新为完成")
                }

                else -> {
                    Log.farm("家庭任务🏠$taskTitle#普通捐蛋已成功，家庭任务当前状态[$taskStatus]")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "confirmDailyDonateTaskAfterPublicDonation err:", t)
        }
    }


    fun run(
        familyOptions: SelectModelField,
        notInviteList: FriendSelectionModelField,
        familyAssignStrategy: Int = FamilyAssignStrategy.RANDOM
    ) {
        try {
            enterFamily(familyOptions, notInviteList, familyAssignStrategy)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 进入家庭
     */
    fun enterFamily(
        familyOptions: SelectModelField,
        notInviteList: FriendSelectionModelField,
        familyAssignStrategy: Int = FamilyAssignStrategy.RANDOM
    ) {
        try {
            groupId = ""
            groupName = ""
            familyAnimals = JSONArray()
            familyUserIds = mutableListOf()
            familyInteractActions = JSONArray()
            eatTogetherConfig = JSONObject()
            runCatching {
                AntFarmRpcCall.refinedOperation("ENTERFAMILY")
            }
            val enterRes = JSONObject(AntFarmRpcCall.enterFamily());
            if (ResChecker.checkRes(TAG, enterRes)) {
                groupId = enterRes.optString("groupId")
                groupName = enterRes.optString("groupName", enterRes.optString("familyName"))
                val familySignTips: Boolean = enterRes.optBoolean("familySignTips", false)//签到
                val assignFamilyMemberInfo: JSONObject? = enterRes.optJSONObject("assignFamilyMemberInfo")//分配成员信息-顶梁柱
                familyAnimals = enterRes.optJSONArray("animals") ?: JSONArray()//家庭动物列表
                familyUserIds = buildFamilyUserIds(familyAnimals)
                if (familyUserIds.isEmpty()) {
                    familyUserIds = buildFamilyUserIds(enterRes.optJSONArray("familyMemberInfoList"))
                }
                familyInteractActions = enterRes.optJSONArray("familyInteractActions") ?: JSONArray()//互动功能列表
                eatTogetherConfig = enterRes.optJSONObject("eatTogetherConfig") ?: JSONObject()//美食配置对象

                if (groupId.isBlank() || groupName.isBlank() || familyUserIds.isEmpty()) {
                    refreshFamilyBaseInfoFromQueryFamilyInfo()
                }
                if (groupId.isBlank()) {
                    Log.farm("请先开通小鸡家庭");
                    return;
                }

                if (hasFamilyOption(familyOptions, "familySign") && familySignTips) {
                    familySign()
                }

                if (assignFamilyMemberInfo != null
                    && hasFamilyOption(familyOptions, "assignRights")
                ) {
                    val assignRights = assignFamilyMemberInfo.optJSONObject("assignRights")
                    val assignConfigList = assignFamilyMemberInfo.optJSONArray("assignConfigList")
                    if (assignRights == null) {
                        Log.farm("家庭任务🏡[使用顶梁柱特权] 缺少 assignRights 信息，跳过")
                    } else if (assignConfigList == null || assignConfigList.length() == 0) {
                        Log.farm("家庭任务[使用顶梁柱特权] 缺少可分配配置，跳过")
                    } else if (assignRights.optString("status") == "USED") {
                        Log.farm("家庭任务[使用顶梁柱特权] 今日已使用，跳过")
                    } else if (assignRights.optString("assignRightsOwner") == UserMap.currentUid) {
                        assignFamilyMember(assignFamilyMemberInfo, familyUserIds, familyAssignStrategy)
                    } else {
                        Log.farm("家庭任务[使用顶梁柱特权] 当前账号不是顶梁柱，跳过")
                    }
                }

                if (hasFamilyOption(familyOptions, "familyClaimReward")) {
                    familyClaimRewardList()
                }

                if (hasFamilyOption(familyOptions, "feedFamilyAnimal") && familyAnimals.length() > 0) {
                    familyFeedFriendAnimal(familyAnimals)
                }

                if (hasFamilyOption(familyOptions, "sleepTogether") && familyAnimals.length() > 0) {
                    familySleepTogether(enterRes)
                }

                if (hasFamilyOption(familyOptions, "eatTogetherConfig") && eatTogetherConfig.length() > 0) {
                    familyEatTogether(eatTogetherConfig, familyInteractActions, familyUserIds.toMutableList())
                }

                if (hasFamilyOption(familyOptions, "familyDonateStep")) {
                    familyDonateStep()
                }

                if (hasFamilyOption(familyOptions, "deliverMsgSend") && familyUserIds.isNotEmpty()) {
                    deliverMsgSend(familyUserIds.toMutableList())
                }

                if (hasFamilyOption(familyOptions, "shareToFriends", "inviteFriendVisitFamily")) {
                    familyShareToFriends(familyUserIds.toMutableList(), notInviteList)
                }
                if (hasFamilyOption(familyOptions, "ExchangeFamilyDecoration")) {
                    autoExchangeFamilyDecoration()
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG,  e)
        }
    }

    /**
     * 家庭签到
     */
    fun familySign() {
        try {
            if (Status.hasFlagToday(StatusFlags.FLAG_FARM_FAMILY_SIGNED)) return
            val res = JSONObject(AntFarmRpcCall.familyReceiveFarmTaskAward("FAMILY_SIGN_TASK"))
            if (ResChecker.checkRes(TAG, res)) {
                Status.setFlagToday(StatusFlags.FLAG_FARM_FAMILY_SIGNED)
                Log.farm("家庭任务🏡每日签到")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG,  e)
        }
    }

    /**
     * 领取家庭奖励
     */
    fun familyClaimRewardList() {
        try {
            var jo = JSONObject(AntFarmRpcCall.familyAwardList())
            if (ResChecker.checkRes(TAG, jo)) {
                val ja = jo.getJSONArray("familyAwardRecordList")
                for (i in 0..<ja.length()) {
                    jo = ja.getJSONObject(i)
                    if (jo.optBoolean("expired")
                        || jo.optBoolean("received", true)
                        || jo.has("linkUrl")
                        || (jo.has("operability") && !jo.getBoolean("operability"))
                    ) {
                        continue
                    }
                    val rightId = jo.getString("rightId")
                    val awardName = jo.getString("awardName")
                    val count = jo.optInt("count", 1)
                    val receveRes = JSONObject(AntFarmRpcCall.receiveFamilyAward(rightId))
                    if (ResChecker.checkRes(TAG, receveRes)) {
                        Log.farm("家庭奖励🏆: $awardName x $count")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "家庭领取奖励", t)
        }
    }

    /**
     * 顶梁柱
     */
    fun assignFamilyMember(
        jsonObject: JSONObject,
        userIds: MutableList<String>,
        familyAssignStrategy: Int = FamilyAssignStrategy.RANDOM
    ) {
        try {
            val beAssignUser = if (familyAssignStrategy == FamilyAssignStrategy.LOWEST_TODAY_INTIMACY) {
                selectLowestTodayIntimacyUser(userIds) ?: selectRandomAssignableUser(userIds)
            } else {
                selectRandomAssignableUser(userIds)
            }
            if (beAssignUser.isNullOrBlank()) {
                Log.farm("家庭任务🏡[使用顶梁柱特权] 无可安排家庭成员，跳过")
                return
            }
            //随机获取一个任务类型
            val assignConfigList = jsonObject.optJSONArray("assignConfigList")
            if (assignConfigList == null || assignConfigList.length() == 0) {
                Log.farm("家庭任务[使用顶梁柱特权] assignConfigList 为空，跳过")
                return
            }
            val assignConfig = assignConfigList.getJSONObject(RandomUtil.nextInt(0, assignConfigList.length() - 1))
            val assignAction = assignConfig.optString("assignAction")
            val assignDesc = assignConfig.optString("assignDesc", assignAction)
            if (assignAction.isBlank()) {
                Log.farm("家庭任务[使用顶梁柱特权] assignAction 为空，跳过")
                return
            }
            val jo = JSONObject(AntFarmRpcCall.assignFamilyMember(assignAction, beAssignUser))
            if (ResChecker.checkRes(TAG, jo)) {
                val displayName = UserMap.getMaskName(beAssignUser) ?: beAssignUser
                Log.farm("家庭任务🏡[使用顶梁柱特权] $assignDesc -> [$displayName]")
//                val sendRes = JSONObject(AntFarmRpcCall.sendChat(assignConfig.getString("chatCardType"), beAssignUser))
            } else {
                val failMsg = jo.optString("resultDesc")
                    .ifBlank { jo.optString("memo") }
                    .ifBlank { jo.optString("errorMsg") }
                    .ifBlank { jo.toString() }
                Log.farm("家庭任务🏡[使用顶梁柱特权] $assignDesc 失败: $failMsg")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 帮好友喂小鸡
     * @param animals 家庭动物列表
     */
    fun familyFeedFriendAnimal(animals: JSONArray) {
        try {
            if (Status.hasFlagToday(StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT)) {
                Log.farm("家庭任务帮喂今日次数已达上限，跳过")
                return
            }
            for (i in 0 until animals.length()) {
                if (Status.hasFlagToday(StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT)) {
                    return
                }
                val animal = animals.getJSONObject(i)
                val status = animal.getJSONObject("animalStatusVO")

                val interactStatus = status.getString("animalInteractStatus")
                val feedStatus = status.getString("animalFeedStatus")

                // 过滤非 HOME / HUNGRY 的
                if (interactStatus != AnimalInteractStatus.HOME.name ||
                    feedStatus != AnimalFeedStatus.HUNGRY.name) continue

                val groupId = animal.getString("groupId")
                val farmId = animal.getString("farmId")
                val userId = animal.getString("userId")

                // 自己 → 跳过
                if (userId == UserMap.currentUid) {
                    continue
                }

                val flagKey = StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT_PREFIX + userId

                // 如果该用户已经记录今日上限 → 跳过
                if (Status.hasFlagToday(flagKey)) {
                    Log.farm("[$userId] 今日喂鸡次数已达上限（已记录）🥣，跳过")
                    continue
                }

                // 调用 RPC
                val jo = JSONObject(AntFarmRpcCall.feedFriendAnimal(farmId, groupId))

                // 统一错误码检查
                if (!jo.optBoolean("success", false)) {
                    val code = jo.optString("resultCode")
                    val memo = jo.optString("memo")

                    if (code == "391" || memo.contains("今日帮喂次数已达上限")) {
                        // 记录该用户今日不能再喂
                        Status.setFlagToday(flagKey)
                        Status.setFlagToday(StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT)
                        Log.farm("[$userId] 今日帮喂次数已达上限🥣，已记录为当日限制")
                    } else if (code == "388" || memo.contains("小鸡太小")) {
                        val maskName = UserMap.getMaskName(userId) ?: userId
                        Log.farm("家庭任务🏠帮喂小鸡🥣[$maskName]跳过：小鸡太小，暂不能投喂")
                    } else {
                        Log.error(TAG, "喂食失败 user=$userId code=$code msg=${jo.optString("memo")}")
                    }
                    if (Status.hasFlagToday(StatusFlags.FLAG_FARM_FEED_FRIEND_LIMIT)) {
                        return
                    }
                    continue
                }

                // 正常成功
                val foodStock = jo.optInt("foodStock")
                val maskName = UserMap.getMaskName(userId) ?: userId
                Log.farm("家庭任务🏠帮喂小鸡🥣[$maskName]180g #剩余${foodStock}g")
                continue
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyFeedFriendAnimal err:",t)
        }
    }

    /**
     * 家庭任务：去睡觉（SLEEP_TOGETHER）
     *
     */
    private fun familySleepTogether(enterRes: JSONObject) {
        try {
            if (groupId.isEmpty()) return
            if (Status.hasFlagToday(StatusFlags.FLAG_FARM_FAMILY_SLEEP_TOGETHER)) return

            // 远端任务状态校验：只在 SLEEP_TOGETHER=TODO 时触发，避免误刷
            val taskTipsRes = JSONObject(AntFarmRpcCall.familyTaskTips(familyAnimals))
            if (!ResChecker.checkRes(TAG, taskTipsRes)) {
                Log.error(TAG, "家庭任务🏠去睡觉#familyTaskTips 调用失败，跳过")
                return
            }

            val taskTips = taskTipsRes.optJSONArray("familyTaskTips")
            if (taskTips == null || taskTips.length() == 0) {
                Status.setFlagToday(StatusFlags.FLAG_FARM_FAMILY_SLEEP_TOGETHER)
                return
            }

            var hasSleepTodo = false
            for (i in 0 until taskTips.length()) {
                val item = taskTips.getJSONObject(i)
                val bizKey = item.optString("bizKey")
                val taskId = item.optString("taskId")
                val taskStatus = item.optString("taskStatus")
                if ((bizKey == "SLEEP_TOGETHER" || taskId == "SLEEP_TOGETHER") && taskStatus == "TODO") {
                    hasSleepTodo = true
                    break
                }
            }

            if (!hasSleepTodo) {
                Status.setFlagToday(StatusFlags.FLAG_FARM_FAMILY_SLEEP_TOGETHER)
                return
            }

            // 部分版本 enterFamily 可能缺少 sleepNotifyInfo，这里默认允许尝试（由服务端返回结果兜底）
            val canSleep = enterRes.optJSONObject("sleepNotifyInfo")?.optBoolean("canSleep", true) ?: true
            if (!canSleep) {
                Log.farm("家庭任务🏠去睡觉#当前无需睡觉或不在可睡时间段，跳过")
                return
            }

            val sleepRes = JSONObject(AntFarmRpcCall.sleep(groupId))
            if (ResChecker.checkRes(TAG, sleepRes)) {
                Log.farm("家庭任务🏠去睡觉🛌")
                Status.animalSleep()
                Status.setFlagToday(StatusFlags.FLAG_FARM_FAMILY_SLEEP_TOGETHER)
                return
            }

            // 某些“已在睡觉”等状态属于静默失败，也视为完成，避免反复触发
            val memo = sleepRes.optString("memo")
            val resultDesc = sleepRes.optString("resultDesc")
            if (memo.contains("睡觉") || resultDesc.contains("睡觉")) {
                Log.farm("家庭任务🏠去睡觉#可能已在睡觉：${resultDesc.ifBlank { memo }}")
                Status.animalSleep()
                Status.setFlagToday(StatusFlags.FLAG_FARM_FAMILY_SLEEP_TOGETHER)
                return
            }

            Log.error(TAG, "家庭任务🏠去睡觉失败: ${resultDesc.ifBlank { memo.ifBlank { sleepRes.toString() } }}")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familySleepTogether err:", t)
        }
    }

    /**
     * 请客吃美食
     * @param eatTogetherConfig 美食配置对象
     * @param familyInteractActions 互动功能列表
     * @param familyUserIds 家庭成员列表
     */
    private fun familyEatTogether(eatTogetherConfig: JSONObject, familyInteractActions: JSONArray, familyUserIds: MutableList<String>) {
        try {
            var isEat = false
            val periodItemList = eatTogetherConfig.optJSONArray("periodItemList")
            if (periodItemList == null || periodItemList.length() == 0) {
                Log.error(TAG, "美食不足,无法请客,请检查小鸡厨房")
                return
            }
            if (familyInteractActions.length() > 0) {
                for (i in 0..<familyInteractActions.length()) {
                    val familyInteractAction = familyInteractActions.getJSONObject(i)
                    if ("EatTogether" == familyInteractAction.optString("familyInteractType")) {
                        val endTime = familyInteractAction.optLong("interactEndTime", 0)
                        val gaptime = endTime - System.currentTimeMillis()
                        Log.farm("正在吃..${formatDuration(gaptime)} 吃完")
                        return
                    }
                }
            }
            var periodName = ""
            val currentTime = Calendar.getInstance()
            for (i in 0..<periodItemList.length()) {
                val periodItem = periodItemList.getJSONObject(i)
                val startHour = periodItem.optInt("startHour")
                val startMinute = periodItem.optInt("startMinute")
                val endHour = periodItem.optInt("endHour")
                val endMinute = periodItem.optInt("endMinute")
                val startTime = Calendar.getInstance()
                startTime.set(Calendar.HOUR_OF_DAY, startHour)
                startTime.set(Calendar.MINUTE, startMinute)
                val endTime = Calendar.getInstance()
                endTime.set(Calendar.HOUR_OF_DAY, endHour)
                endTime.set(Calendar.MINUTE, endMinute)
                if (currentTime.after(startTime) && currentTime.before(endTime)) {
                    periodName = periodItem.optString("periodName")
                    isEat = true
                    break
                }
            }
            if (!isEat) {
                Log.farm("家庭任务🏠请客吃美食#当前时间不在美食时间段")
                return
            }
            if (Objects.isNull(familyUserIds) || familyUserIds.isEmpty()) {
                Log.farm("家庭成员列表为空,无法请客")
                return
            }
            val array: JSONArray? = queryRecentFarmFood(familyUserIds.size)
            if (array == null) {
                Log.farm("查询最近的几份美食为空,无法请客")
                return
            }
            val jo = JSONObject(AntFarmRpcCall.familyEatTogether(groupId, familyUserIds.toJSONArray(), array))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("家庭任务🏠请客" + periodName + "#消耗美食" + familyUserIds.size + "份")
                GlobalThreadPools.sleepCompat(500L)
                syncFamilyStatusIntimacy(groupId)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyEatTogether err:",t)
        }
    }

    /**
     * 家庭任务：运动公益捐步（FAMILY_TREAD_MALL）
     */
    private fun familyDonateStep() {
        try {
            val currentUid = UserMap.currentUid
            if (currentUid.isNullOrBlank()) {
                return
            }

            val taskJo = JSONObject(AntFarmRpcCall.listFamilyTask())
            if (!ResChecker.checkRes(TAG, taskJo)) {
                Log.farm("家庭任务🏠捐步做公益#listFamilyTask 调用失败，跳过")
                return
            }

            val familyTasks = taskJo.optJSONArray("familyTasks") ?: return
            var needDonateStep = false
            for (index in 0 until familyTasks.length()) {
                val task = familyTasks.optJSONObject(index) ?: continue
                val bizKey = task.optString("bizKey")
                val taskId = task.optString("taskId")
                if (bizKey != "FAMILY_TREAD_MALL" && taskId != "FAMILY_TREAD_MALL") {
                    continue
                }

                when (task.optString("taskStatus")) {
                    "RECEIVED", "FINISHED" -> {
                        Status.exchangeToday(currentUid)
                        return
                    }

                    "TODO" -> {
                        needDonateStep = true
                    }
                }
                break
            }

            if (!needDonateStep) {
                return
            }

            val treadMillJo = queryFamilyTreadMillState()
            val currentMemberState = treadMillJo?.optJSONArray("familyMemberInfoList")
                ?.let { memberList ->
                    (0 until memberList.length())
                        .mapNotNull { memberList.optJSONObject(it) }
                        .firstOrNull {
                            it.optBoolean("currentUser", false) || it.optString("userId") == currentUid
                        }
                }
            if (currentMemberState?.optBoolean("alreadyDonate", false) == true) {
                Status.exchangeToday(currentUid)
                Log.farm("家庭任务🏠捐步做公益#家庭页显示今日已捐步，跳过")
                return
            }
            if (currentMemberState != null) {
                if (!currentMemberState.optBoolean("openSportsPolicy", true)) {
                    Log.farm("家庭任务🏠捐步做公益#当前账号未开启运动步数授权，跳过")
                    return
                }
                if (!currentMemberState.optBoolean("treadMillDataShare", true)) {
                    Log.farm("家庭任务🏠捐步做公益#当前账号未开启家庭步数共享，跳过")
                    return
                }
            }

            if (!Status.canExchangeToday(currentUid)) {
                Log.farm("家庭任务🏠捐步做公益#今日已完成，跳过")
                return
            }

            val stepJo = JSONObject(AntSportsRpcCall.queryWalkStep())
            if (!ResChecker.checkRes(TAG, stepJo)) {
                Log.farm("家庭任务🏠捐步做公益#queryWalkStep 调用失败，跳过")
                return
            }

            val produceQuantity = AntSportsRpcCall.extractWalkStepCount(stepJo)
            if (produceQuantity <= 0) {
                Log.farm("家庭任务🏠捐步做公益#当前暂无可捐步数")
                return
            }

            AntSportsRpcCall.walkDonateSignInfo(produceQuantity)
            val donateHomeResponse = AntSportsRpcCall.donateWalkHome(produceQuantity)
            val donateHomeJo = JSONObject(donateHomeResponse)
            if (!donateHomeJo.optBoolean("isSuccess", false)) {
                if (donateHomeResponse.contains("已捐步")) {
                    Status.exchangeToday(currentUid)
                    Log.farm("家庭任务🏠捐步做公益#今日已捐步，跳过")
                } else {
                    Log.farm("家庭任务🏠捐步做公益失败: ${donateHomeJo.optString("resultDesc", donateHomeResponse)}")
                }
                return
            }

            val walkDonateHomeModel = donateHomeJo.optJSONObject("walkDonateHomeModel") ?: return
            val walkUserInfoModel = walkDonateHomeModel.optJSONObject("walkUserInfoModel")
            if (walkUserInfoModel == null || !walkUserInfoModel.has("exchangeFlag")) {
                Status.exchangeToday(currentUid)
                return
            }

            val donateToken = walkDonateHomeModel.optString("donateToken")
            val activityId = walkDonateHomeModel.optJSONObject("walkCharityActivityModel")
                ?.optString("activityId")
                .orEmpty()
            if (donateToken.isBlank() || activityId.isBlank()) {
                Log.farm("家庭任务🏠捐步做公益#缺少 donateToken 或 activityId，跳过")
                return
            }

            val exchangeResponse = AntSportsRpcCall.exchange(activityId, produceQuantity, donateToken)
            val exchangeJo = JSONObject(exchangeResponse)
            if (exchangeJo.optBoolean("isSuccess", false)) {
                val donateExchangeResultModel = exchangeJo.optJSONObject("donateExchangeResultModel")
                val userCount = donateExchangeResultModel?.optInt("userCount", produceQuantity) ?: produceQuantity
                val amount = donateExchangeResultModel?.optJSONObject("userAmount")?.optDouble("amount", 0.0) ?: 0.0
                Log.farm("家庭任务🏠捐步做公益🚶[${userCount}步]#兑换${amount}元公益金")
                Status.exchangeToday(currentUid)
                syncFamilyStatusIntimacy(groupId)
                return
            }

            if (exchangeResponse.contains("已捐步") || exchangeJo.optString("resultDesc").contains("已捐步")) {
                Status.exchangeToday(currentUid)
                Log.farm("家庭任务🏠捐步做公益#今日已捐步")
                return
            }

            Log.farm("家庭任务🏠捐步做公益失败: ${exchangeJo.optString("resultDesc", exchangeResponse)}")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyDonateStep err:", t)
        }
    }

    /**
     * 同步家庭亲密度状态
     */
    private fun syncFamilyStatusIntimacy(groupId: String) {
        try {
            val currentUserId = UserMap.currentUid
            if (currentUserId.isNullOrBlank()) {
                return
            }
            val jo = JSONObject(AntFarmRpcCall.syncFamilyStatus(groupId, "INTIMACY_VALUE", currentUserId))
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.farm("家庭任务🏠同步亲密度状态失败")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "syncFamilyStatusIntimacy err:", t)
        }
    }

    /**
     * 查询最近的几份美食
     * @param queryNum 查询数量
     */
    fun queryRecentFarmFood(queryNum: Int): JSONArray? {
        try {
            val jo = JSONObject(AntFarmRpcCall.queryRecentFarmFood(queryNum))
            if (!ResChecker.checkRes(TAG, jo)) {
                return null
            }
            val cuisines = jo.getJSONArray("cuisines")
            var count = 0
            for (i in 0..<cuisines.length()) {
                val cuisine = cuisines.getJSONObject(i)
                count += cuisine.optInt("count")
            }
            if (cuisines != null && queryNum <= count) {
                return cuisines
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryRecentFarmFood err:", t)
        }
        return null
    }

    /**
     * 家庭「道早安」任务
     *
     *
     *
     * 1）先通过 familyTaskTips 判断今日是否还有「道早安」任务：
     *    - 请求方法：com.alipay.antfarm.familyTaskTips
     *    - 请求体关键字段：
     *        animals      -> 直接复用 enterFamily 返回的家庭 animals 列表
     *        taskSceneCode-> "ANTFARM_FAMILY_TASK"
     *        sceneCode    -> "ANTFARM"
     *        source       -> "H5"
     *        requestType  -> "NORMAL"
     *        timeZoneId   -> "Asia/Shanghai"
     *    - 响应 familyTaskTips 数组中存在 bizKey="GREETING" 且 taskStatus="TODO" 时，说明可以道早安
     *
     * 2）未完成早安任务时，按顺序调用以下 RPC 获取 AI 文案并发送：
     *    a. com.alipay.antfarm.deliverSubjectRecommend
     *       -> 入参：friendUserIds（家庭其他成员 userId 列表），sceneCode="ChickFamily"，source="H5"
     *       -> 取出：ariverRpcTraceId、eventId、eventName、sceneId、sceneName 等上下文
     *    b. com.alipay.antfarm.DeliverContentExpand
     *       -> 入参：上一步取到的 ariverRpcTraceId / eventId / eventName / sceneId / sceneName 等 + friendUserIds
     *       -> 返回：AI 生成的 content 以及 deliverId
     *    c. com.alipay.antfarm.QueryExpandContent
     *       -> 入参：deliverId
     *       -> 用于再次确认 content 与场景（可选安全校验）
     *    d. com.alipay.antfarm.DeliverMsgSend
     *       -> 入参：content、deliverId、friendUserIds、groupId（家庭 groupId）、sceneCode="ANTFARM"、spaceType="ChickFamily" 等
     *
     *   额外增加保护：
     *  - 仅在每天 06:00~10:00 之间执行
     *  - 每日仅发送一次（本地 Status 标记 + 远端 familyTaskTips 双重判断）
     *  - 自动从家庭成员列表中移除自己，避免接口报参数错误
     *
     * @param familyUserIds 家庭成员 userId 列表（包含自己，方法内部会移除当前账号）
     */
    fun deliverMsgSend(familyUserIds: MutableList<String>) {
        try {
            // 1. 时间窗口控制：仅允许在「早安时间段」内自动发送（06:00 ~ 10:00）
            val now = Calendar.getInstance()
            val startTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 6)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val endTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 10)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (now.before(startTime) || now.after(endTime)) {
                Log.farm("家庭任务🏠道早安#当前时间不在 06:00-10:00，跳过")
                return
            }

            // groupId 是 enterFamily 返回的家庭 ID，如果为空说明当前账号未开通家庭
            if (groupId.isEmpty()) {
                Log.farm("家庭任务🏠道早安#未检测到家庭 groupId，可能尚未加入家庭，跳过")
                return
            }

            // 本地去重：一天只发送一次，避免重复打扰
            if (Status.hasFlagToday(StatusFlags.FLAG_FARM_FAMILY_DELIVER_MSG_SEND)) {
                Log.farm("家庭任务🏠道早安#今日已在本地发送过，跳过")
                return
            }

            // 2. 远端任务状态校验：确认「道早安」任务是否仍为 TODO
            try {
                val taskTipsRes = JSONObject(AntFarmRpcCall.familyTaskTips(familyAnimals))
                if (!ResChecker.checkRes(TAG, taskTipsRes)) {
                    Log.error(TAG, "家庭任务🏠道早安#familyTaskTips 调用失败，跳过")
                    return
                }

                val taskTips = taskTipsRes.optJSONArray("familyTaskTips")
                if (taskTips == null || taskTips.length() == 0) {
                    // familyTaskTips 为空：要么今天已经完成，要么当前无早安任务
                    Log.farm("家庭任务🏠道早安#远端无 GREETING 任务，可能今日已完成，跳过")
                    Status.setFlagToday(StatusFlags.FLAG_FARM_FAMILY_DELIVER_MSG_SEND)
                    return
                }

                var hasGreetingTodo = false
                for (i in 0 until taskTips.length()) {
                    val item = taskTips.getJSONObject(i)
                    val bizKey = item.optString("bizKey")
                    val taskStatus = item.optString("taskStatus")
                    if ("GREETING" == bizKey && "TODO" == taskStatus) {
                        hasGreetingTodo = true
                        break
                    }
                }

                if (!hasGreetingTodo) {
                    Log.farm("家庭任务🏠道早安#GREETING 任务非 TODO 状态，跳过")
                    Status.setFlagToday(StatusFlags.FLAG_FARM_FAMILY_DELIVER_MSG_SEND)
                    return
                }
            } catch (e: Throwable) {
                // safety：远端任务判断异常时，为了避免误刷，多数情况下选择跳过
                Log.printStackTrace(TAG, "familyTaskTips 解析失败，出于安全考虑跳过道早安：", e)
                return
            }

            // 3. 构建好友 userId 列表（去掉自己）
            // 先移除当前用户自己的 ID，否则 DeliverMsgSend 等接口会因为参数不合法而报错
            familyUserIds.remove(UserMap.currentUid)
            if (familyUserIds.isEmpty()) {
                Log.farm("家庭任务🏠道早安#家庭成员仅自己一人，跳过")
                return
            }

            val userIds = JSONArray().apply {
                for (userId in familyUserIds) {
                    put(userId)
                }
            }

            // 4. 确认 AI 隐私协议（OpenAIPrivatePolicy 抓包见看我.txt 中 deliverChickInfoVO.privatePolicyId）
            val resp0 = JSONObject(AntFarmRpcCall.OpenAIPrivatePolicy())
            if (!ResChecker.checkRes(TAG, resp0)) {
                Log.error(TAG, "家庭任务🏠道早安#OpenAIPrivatePolicy 调用失败")
                return
            }

            // 5. 请求推荐早安场景（deliverSubjectRecommend）以获取事件上下文
            val resp1 = JSONObject(AntFarmRpcCall.deliverSubjectRecommend(userIds))
            if (!ResChecker.checkRes(TAG, resp1)) {
                Log.error(TAG, "家庭任务🏠道早安#deliverSubjectRecommend 调用失败")
                return
            }

            // 提取后续调用所需的关键字段（均为动态值，绝不可写死）
            val ariverRpcTraceId = resp1.getString("ariverRpcTraceId")
            val eventId = resp1.getString("eventId")
            val eventName = resp1.getString("eventName")
            val memo = resp1.optString("memo")
            val resultCode = resp1.optString("resultCode")
            val sceneId = resp1.getString("sceneId")
            val sceneName = resp1.getString("sceneName")
            val success = resp1.optBoolean("success", true)

            // 6. 调用 DeliverContentExpand，实际向 AI 请求生成完整早安文案
            val resp2 = JSONObject(
                AntFarmRpcCall.deliverContentExpand(
                    ariverRpcTraceId,
                    eventId,
                    eventName,
                    memo,
                    resultCode,
                    sceneId,
                    sceneName,
                    success,
                    userIds
                )
            )
            if (!ResChecker.checkRes(TAG, resp2)) {
                Log.error(TAG, "家庭任务🏠道早安#DeliverContentExpand 调用失败")
                return
            }

            val deliverId = resp2.getString("deliverId")

            // 7. 使用 deliverId 再次确认扩展内容，得到最终的早安文案
            val resp3 = JSONObject(AntFarmRpcCall.QueryExpandContent(deliverId))
            val content = if (ResChecker.checkRes(TAG, resp3)) {
                extractGreetingContent(resp3)
            } else {
                val fallbackContent = extractGreetingContent(resp2)
                if (fallbackContent.isBlank()) {
                    Log.error(TAG, "家庭任务🏠道早安#QueryExpandContent 调用失败")
                    return
                }
                Log.farm("家庭任务🏠道早安#QueryExpandContent 调用失败，已回退到 DeliverContentExpand 文案")
                fallbackContent
            }
            if (content.isBlank()) {
                Log.error(TAG, "家庭任务🏠道早安#未获取到可发送文案，跳过")
                return
            }

            // 8. 最终发送早安消息：DeliverMsgSend
            val resp4 = JSONObject(AntFarmRpcCall.deliverMsgSend(groupId, userIds, content, deliverId))
            if (ResChecker.checkRes(TAG, resp4)) {
                Log.farm("家庭任务🏠道早安: $content 🌈")
                Status.setFlagToday(StatusFlags.FLAG_FARM_FAMILY_DELIVER_MSG_SEND)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "deliverMsgSend err:", t)
        }
    }

    /**
     * 好友分享家庭
     * @param familyUserIds 好友列表
     * @param notInviteList 不邀请列表
     */
    private fun familyShareToFriends(familyUserIds: MutableList<String>, notInviteList: FriendSelectionModelField) {
        try {
            if (Status.hasFlagToday(StatusFlags.FLAG_FARM_FAMILY_SHARE_TO_FRIENDS)) {
                return
            }

            val familyValue = notInviteList.resolvedIds()
            val allUser = FriendSelectionResolver.availableFriendOptions()

            if (allUser.isEmpty()) {
                Log.error(TAG, "allUser is empty")
                return
            }

            // 打乱顺序，实现随机选取
            val shuffledUsers = allUser.shuffled()

            val inviteList = JSONArray()
            for (u in shuffledUsers) {
                if (!familyUserIds.contains(u.id) && !familyValue.contains(u.id)) {
                    inviteList.put(u.id)
                    if (inviteList.length() >= 6) {
                        break
                    }
                }
            }

            if (inviteList.length() == 0) {
                Log.error(TAG, "没有符合分享条件的好友")
                return
            }

            Log.farm("inviteList: $inviteList")

            val jo = JSONObject(AntFarmRpcCall.inviteFriendVisitFamily(inviteList))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("家庭任务🏠分享好友")
                Status.setFlagToday(StatusFlags.FLAG_FARM_FAMILY_SHARE_TO_FRIENDS)
                syncFamilyStatusIntimacy(groupId)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyShareToFriends err:", t)
        }
    }


    /**
     * 自动购买家具
     */
    fun autoExchangeFamilyDecoration() {
        Log.farm("[家庭装扮] 启动分类购买任务...")
        try {
            // 获取活动 ID
            val familyRes = AntFarmRpcCall.enterFamily()
            val familyJo = JSONObject(familyRes)
            if (!ResChecker.checkRes(TAG, familyJo)) return

            val activityId = familyJo.optString("decorationCoinActivityId", "20250808")
            Log.farm("[家庭装扮] 当前活动 ID: $activityId")

            // 分类列表
            val labelTypes = listOf(
                "", "recentlyAdded", "sofa", "seat2", "seat4", "seat5", "seat3",
                "curtain", "table", "carpet", "mattress", "bed3", "bed4",
                "bed5", "ceiling", "windowView", "firstFloor", "firstWall", "secondFloor",
                "secondWall", "leftWallDecoration", "rightWallDecoration", "treadmill", "slide"
            )

            var currentBalance = 0
            val purchasedDecorationKeys = LinkedHashSet<String>()

            for (label in labelTypes) {
                var startIndex = 0
                var hasMore = true
                Log.farm("[家庭装扮] 正在检查分类: ${if (label.isEmpty()) "新品" else label}")

                while (hasMore) {
                    val itemListRes = AntFarmRpcCall.getFitmentItemList(activityId, 10, label, startIndex)
                    val itemJo = JSONObject(itemListRes)
                    if (!ResChecker.checkRes(TAG, itemJo)) break

                    // 解析实时装修金余额
                    val accountInfo = itemJo.optJSONObject("mallAccountInfoVO")
                    currentBalance = accountInfo?.optJSONObject("holdingCount")?.optInt("cent") ?: 0

                    val items = itemJo.optJSONArray("itemInfoVOList")
                    if (items == null || items.length() == 0) break

                    for (j in 0 until items.length()) {
                        val item = items.getJSONObject(j)
                        val spuId = item.getString("spuId")
                        val spuName = item.getString("spuName")
                        val price = item.optJSONObject("minPrice")?.optInt("cent") ?: 9999999

                        val itemStatusList = item.optJSONArray("itemStatusList")
                        val canBuy = itemStatusList == null || itemStatusList.length() == 0

                        if (canBuy && currentBalance >= price) {
                            val skuList = item.optJSONArray("skuModelList")
                            if (skuList != null && skuList.length() > 0) {
                                val skuId = skuList.getJSONObject(0).getString("skuId")
                                val exchangeKey = "$spuId|$skuId|$activityId"
                                if (purchasedDecorationKeys.contains(exchangeKey)) {
                                    Log.farm("[家庭装扮] 跳过已购买家具: $spuName")
                                    continue
                                }
                                Log.farm("[家庭装扮] 发现未拥有家具: $spuName")

                                val exchangeRes = AntFarmRpcCall.exchangeBenefit(spuId, skuId, activityId)
                                val exchangeJo = JSONObject(exchangeRes)

                                if (ResChecker.checkRes(TAG, exchangeJo)) {
                                    Log.farm("家庭装扮💸#成功购买[$spuName]#消耗[${price/100}装修金]")
                                    purchasedDecorationKeys.add(exchangeKey)
                                    currentBalance -= price
                                }
                                GlobalThreadPools.sleepCompat(2000)
                            }
                        }
                    }

                    val nextIndex = itemJo.optInt("nextStartIndex", 0)
                    val hasMoreField = itemJo.optBoolean("hasMore", false)
                    if (hasMoreField && nextIndex > startIndex) {
                        startIndex = nextIndex
                    } else {
                        hasMore = false
                    }
                }

                // 当处理完 seat3 分类后，如果装修金 < 49，终止后续更贵的分类的遍历
                if (currentBalance < 4900 && label == "seat3") {
                    Log.farm("[家庭装扮] 装修金不足 49 且已完成 seat3 遍历，终止任务")
                    break
                }
            }
            Log.farm("[家庭装扮] 全量检查任务执行完毕")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "autoExchangeFamilyDecoration 失败", t)
        }
    }


    /**
     * 通用时间差格式化（自动区分过去/未来）
     * @param diffMillis 任意时间戳（毫秒）
     * @return 易读字符串，如 "刚刚", "5分钟后", "3天前"
     */
    fun formatDuration(diffMillis: Long): String {
        val absSeconds = abs(diffMillis) / 1000

        val (value, unit) = when {
            absSeconds < 60 -> Pair(absSeconds, "秒")
            absSeconds < 3600 -> Pair(absSeconds / 60, "分钟")
            absSeconds < 86400 -> Pair(absSeconds / 3600, "小时")
            absSeconds < 2592000 -> Pair(absSeconds / 86400, "天")
            absSeconds < 31536000 -> Pair(absSeconds / 2592000, "个月")
            else -> Pair(absSeconds / 31536000, "年")
        }

        return when {
            absSeconds < 1 -> "刚刚"
            diffMillis > 0 -> "$value$unit 后"
            else -> "$value$unit 前"
        }
    }
}

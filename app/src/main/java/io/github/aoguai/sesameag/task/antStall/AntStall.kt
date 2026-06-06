package io.github.aoguai.sesameag.task.antStall

import android.util.Base64
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.friend.FriendCapabilityState
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleDefaults
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleKind
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.FriendSelectionModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskStatus
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
import io.github.aoguai.sesameag.util.RandomUtil
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TimeCounter
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.friend.FriendCapabilityRecorder
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedList
import java.util.Queue

/**
 * @file AntStall.kt
 * @brief 蚂蚁新村任务模块
 * @author Constanline
 * @since 2023/08/22
 */
class AntStall : ModelTask() {

    /**
     * @brief 摊位数据类
     * @property userId 用户ID
     * @property hot 热度值
     */
    private data class Seat(val userId: String, val hot: Int)

    // 配置字段
    private lateinit var stallAutoOpen: BooleanModelField
    private lateinit var stallOpenType: ChoiceModelField
    private lateinit var stallOpenList: FriendSelectionModelField
    private lateinit var stallAutoClose: BooleanModelField
    private lateinit var stallAutoTicket: BooleanModelField
    private lateinit var stallTicketType: ChoiceModelField
    private lateinit var stallTicketList: FriendSelectionModelField
    private lateinit var stallAutoTask: BooleanModelField
    private lateinit var stallReceiveAward: BooleanModelField
    private lateinit var stallWhiteList: FriendSelectionModelField
    private lateinit var stallBlackList: FriendSelectionModelField
    private lateinit var stallAllowOpenReject: BooleanModelField
    private lateinit var stallAllowOpenTime: IntegerModelField
    private lateinit var stallSelfOpenTime: IntegerModelField
    private lateinit var stallDonate: BooleanModelField
    private lateinit var stallInviteRegister: BooleanModelField
    private lateinit var stallThrowManure: BooleanModelField
    private lateinit var stallThrowManureType: ChoiceModelField
    private lateinit var stallThrowManureList: FriendSelectionModelField
    private lateinit var stallInviteShop: BooleanModelField
    private lateinit var stallInviteShopType: ChoiceModelField
    private lateinit var stallInviteShopList: FriendSelectionModelField
    private lateinit var roadmap: BooleanModelField
    private lateinit var stallInviteRegisterList: FriendSelectionModelField
    private lateinit var stallAssistFriend: BooleanModelField
    private lateinit var assistFriendList: FriendSelectionModelField
    private val handledTaskFinishes = LinkedHashSet<String>()
    private val handledTaskAwards = LinkedHashSet<String>()
    private val loggedTaskMessages = LinkedHashSet<String>()

    override fun getName(): String = "新村"

    override fun getGroup(): ModelGroup = ModelGroup.STALL

    override fun getIcon(): String = "AntStall.png"

    private fun persistentStallDedupeKey(childId: String): String {
        val owner = AccountSessionCoordinator.currentUserId()?.takeIf { it.isNotBlank() }
            ?: UserMap.currentUid?.takeIf { it.isNotBlank() }
            ?: "default"
        return "stall_child_${owner}::$childId"
    }

    internal fun registerPersistentChildTask(
        childId: String,
        group: String,
        triggerAtMs: Long,
        extraPayload: JSONObject = JSONObject()
    ) {
        val context = ApplicationHook.appContext ?: return
        if (triggerAtMs <= System.currentTimeMillis()) return
        try {
            val ownerUserId = AccountSessionCoordinator.currentUserId()?.takeIf { it.isNotBlank() }
                ?: UserMap.currentUid?.takeIf { it.isNotBlank() }
            val payload = JSONObject(extraPayload.toString())
                .put("child_kind", PERSISTENT_CHILD_KIND)
                .put("child_id", childId)
                .put("group", group)
                .put("launch_target", true)
            ownerUserId?.let { payload.put("owner_user_id", it) }
            payload.put("session_epoch", AccountSessionCoordinator.currentSessionEpoch())

            UnifiedScheduler.schedulePersistentTrigger(
                context = context,
                name = "新村子任务:$group",
                kind = PersistentScheduleKind.MODULE_CHILD,
                triggerAtMs = triggerAtMs,
                dedupeKey = persistentStallDedupeKey(childId),
                payloadJson = payload.toString(),
                toleranceMs = PersistentScheduleDefaults.DEFAULT_TOLERANCE_MS,
                ownerUserId = ownerUserId,
                sessionEpoch = AccountSessionCoordinator.currentSessionEpoch()
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "注册新村持久子任务失败[$group][$childId]", t)
        }
    }

    internal fun cancelPersistentChildTask(childId: String) {
        UnifiedScheduler.cancelPersistentByDedupeKey(ApplicationHook.appContext, persistentStallDedupeKey(childId))
    }

    internal fun triggerPersistentChildTask(childId: String, group: String, payloadJson: String, source: String): Boolean {
        val payload = runCatching { JSONObject(payloadJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val ownerUserId = payload.optString("owner_user_id").trim()
        val payloadSessionEpoch = payload.optLong("session_epoch", 0L)
        val currentOwnerUserId = (AccountSessionCoordinator.currentUserId() ?: UserMap.currentUid).orEmpty()
        if (ownerUserId.isNotBlank() && ownerUserId != currentOwnerUserId) {
            Log.stall("新村持久子任务[$group][$childId]账号不匹配，跳过: owner=$ownerUserId current=$currentOwnerUserId")
            return true
        }
        if (!isPersistentChildSessionCurrent(currentOwnerUserId, payloadSessionEpoch)) {
            Log.stall("新村持久子任务[$group][$childId]会话无效，跳过触发: owner=$currentOwnerUserId session=$payloadSessionEpoch")
            return true
        }
        if (!isEnable()) {
            Log.stall("新村持久子任务[$group][$childId]触发时模块已关闭，跳过")
            return true
        }
        GlobalThreadPools.execute {
            runPersistentChildTask(childId, group, payload, source, currentOwnerUserId.orEmpty(), payloadSessionEpoch)
        }
        return true
    }

    private fun isPersistentChildSessionCurrent(ownerUserId: String, sessionEpoch: Long): Boolean {
        return ownerUserId.isNotBlank() &&
            sessionEpoch > 0L &&
            AccountSessionCoordinator.isCurrentSession(ownerUserId, sessionEpoch)
    }

    private fun runPersistentChildTask(
        childId: String,
        group: String,
        payload: JSONObject,
        source: String,
        ownerUserId: String,
        sessionEpoch: Long
    ) {
        try {
            if (!isPersistentChildSessionCurrent(ownerUserId, sessionEpoch)) {
                Log.stall("新村持久子任务[$group][$childId]会话已切换，取消执行: owner=$ownerUserId session=$sessionEpoch")
                return
            }
            Log.stall("新村持久子任务触发[$group][$childId] source=$source")
            cancelPersistentChildTask(childId)
            when (group) {
                "SB" -> runSendBackPersistentTask(childId, payload)
                "SH" -> runCloseShopPersistentTask(childId, payload)
                else -> Log.stall("未知新村持久子任务[$group][$childId]，跳过")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "新村持久子任务执行失败[$group][$childId]", t)
        }
    }

    override fun getFields(): ModelFields {
        return ModelFields().apply {
            addField(BooleanModelField("stallAutoOpen", "摆摊 | 开启", false).withDesc(
                "自动把空闲小摊派到好友村庄摆摊；关闭后不会执行摆摊流程。"
            ).also { stallAutoOpen = it })
            addField(
                ChoiceModelField(
                    "stallOpenType",
                    "摆摊 | 动作",
                    StallOpenType.OPEN,
                    StallOpenType.nickNames
                ).withDesc("决定“摆摊 | 好友列表”是作为允许名单还是排除名单。需开启“摆摊 | 开启”。")
                    .also { stallOpenType = it })
            addField(
                FriendSelectionModelField(
                    "stallOpenList",
                    "摆摊 | 好友列表"
                ).withDesc("配置摆摊规则作用的好友村庄名单。需开启“摆摊 | 开启”。")
                    .also { stallOpenList = it })
            addField(
                BooleanModelField(
                    "stallAutoClose",
                    "收摊 | 开启",
                    false
                ).withDesc("按设定摆摊时长自动收回自己的小摊，并可衔接再次摆摊。")
                    .also { stallAutoClose = it })
            addField(
                IntegerModelField(
                    "stallSelfOpenTime",
                    "收摊 | 摆摊时长(分钟)",
                    120,
                    0,
                    null
                ).withDesc("单个摊位摆出多久后自动收摊；需开启“收摊 | 开启”。")
                    .also { stallSelfOpenTime = it })
            addField(
                BooleanModelField(
                    "stallAutoTicket",
                    "贴罚单 | 开启",
                    false
                ).withDesc("自动巡逻并给符合条件的好友小摊贴罚单。")
                    .also { stallAutoTicket = it })
            addField(
                ChoiceModelField(
                    "stallTicketType",
                    "贴罚单 | 动作",
                    StallTicketType.DONT_TICKET,
                    StallTicketType.nickNames
                ).withDesc("决定“贴罚单 | 好友列表”是贴还是跳过。需开启“贴罚单 | 开启”。")
                    .also { stallTicketType = it })
            addField(
                FriendSelectionModelField(
                    "stallTicketList",
                    "贴罚单 | 好友列表"
                ).withDesc("配置贴罚单规则作用的好友名单。需开启“贴罚单 | 开启”。")
                    .also { stallTicketList = it })
            addField(
                BooleanModelField(
                    "stallThrowManure",
                    "丢肥料 | 开启",
                    false
                ).withDesc("按名单规则自动给动态中的好友丢肥料。")
                    .also { stallThrowManure = it })
            addField(
                ChoiceModelField(
                    "stallThrowManureType",
                    "丢肥料 | 动作",
                    StallThrowManureType.DONT_THROW,
                    StallThrowManureType.nickNames
                ).withDesc("决定“丢肥料 | 好友列表”是丢还是跳过。需开启“丢肥料 | 开启”。")
                    .also { stallThrowManureType = it })
            addField(
                FriendSelectionModelField(
                    "stallThrowManureList",
                    "丢肥料 | 好友列表"
                ).withDesc("配置丢肥料规则作用的好友名单。需开启“丢肥料 | 开启”。")
                    .also { stallThrowManureList = it })
            addField(
                BooleanModelField(
                    "stallInviteShop",
                    "邀请摆摊 | 开启",
                    false
                ).withDesc("自家有空位或请走别人后，自动邀请好友来你家摆摊。")
                    .also { stallInviteShop = it })
            addField(
                ChoiceModelField(
                    "stallInviteShopType",
                    "邀请摆摊 | 动作",
                    StallInviteShopType.INVITE,
                    StallInviteShopType.nickNames
                ).withDesc("决定“邀请摆摊 | 好友列表”是邀请还是排除。需开启“邀请摆摊 | 开启”。")
                    .also { stallInviteShopType = it })
            addField(
                FriendSelectionModelField(
                    "stallInviteShopList",
                    "邀请摆摊 | 好友列表"
                ).withDesc("配置允许自动邀请来摆摊的好友名单。需开启“邀请摆摊 | 开启”。")
                    .also { stallInviteShopList = it })
            addField(
                BooleanModelField(
                    "stallAllowOpenReject",
                    "请走小摊 | 开启",
                    false
                ).withDesc("按时长、黑白名单规则自动请走占位小摊。")
                    .also { stallAllowOpenReject = it })
            addField(
                IntegerModelField(
                    "stallAllowOpenTime",
                    "请走小摊 | 允许摆摊时长(分钟)",
                    121,
                    0,
                    null
                ).withDesc("好友在你家摆摊超过该时长后会被自动请走；白名单除外。需开启“请走小摊 | 开启”。")
                    .also { stallAllowOpenTime = it })
            addField(
                FriendSelectionModelField(
                    "stallWhiteList",
                    "请走小摊 | 白名单(超时也不赶)"
                ).withDesc("这些好友即使超时也不会被请走。需开启“请走小摊 | 开启”。")
                    .also { stallWhiteList = it })
            addField(
                FriendSelectionModelField(
                    "stallBlackList",
                    "请走小摊 | 黑名单(不超时也赶)"
                ).withDesc("这些好友即使未超时也会被立即请走。需开启“请走小摊 | 开启”。")
                    .also { stallBlackList = it })
            addField(BooleanModelField("stallAutoTask", "新村任务 | 开启", false).withDesc(
                "执行新村签到、可自动完成的每日任务及部分特殊任务。"
            ).also { stallAutoTask = it })
            addField(
                BooleanModelField(
                    "stallReceiveAward",
                    "新村任务 | 领奖",
                    false
                ).withDesc("自动领取已完成新村任务的奖励。建议配合“新村任务 | 开启”一起开启。")
                    .also { stallReceiveAward = it })
            addField(
                BooleanModelField("stallDonate", "公益捐赠 | 开启", false).withDesc(
                    "金币满足条件时自动向新村公益项目捐赠。"
                ).also { stallDonate = it }
            )
            addField(
                BooleanModelField("roadmap", "新村进度 | 进入下一村", false).withDesc(
                    "发现可解锁的新村时自动进入打卡，每个村每天只处理一次。"
                ).also { roadmap = it }
            )
            addField(
                BooleanModelField(
                    "stallInviteRegister",
                    "新村任务 | 邀请好友开通",
                    false
                ).withDesc("在自动任务中尝试邀请指定好友开通蚂蚁新村。需开启“新村任务 | 开启”。")
                    .also { stallInviteRegister = it })
            addField(
                FriendSelectionModelField(
                    "stallInviteRegisterList",
                    "新村任务 | 邀请好友列表"
                ).withDesc("仅邀请列表中的好友开通新村。需开启“新村任务 | 邀请好友开通”。")
                    .also { stallInviteRegisterList = it })
            addField(
                BooleanModelField("stallAssistFriend", "好友助力 | 开启", false).withDesc(
                    "按好友助力列表自动执行新村助力；关闭后不会发起助力。"
                ).also { stallAssistFriend = it }
            )
            addField(
                FriendSelectionModelField(
                    "assistFriendList",
                    "好友助力 | 好友列表"
                ).withDesc("配置允许自动新村助力的好友列表。需开启“好友助力 | 开启”。")
                    .also { assistFriendList = it })
        }
    }

    override fun runJava() {
        try {
            val tc = TimeCounter(TAG)
            Log.stall("执行开始-${getName()}")

            val homeResponse = AntStallRpcCall.home()
            val homeJson = JSONObject(homeResponse)

            if (!ResChecker.checkRes(TAG, homeJson)) {
                Log.stall("home err: $homeResponse")
                return
            }

            // 检查是否已注册
            if (!homeJson.getBoolean("hasRegister") || homeJson.getBoolean("hasQuit")) {
                Log.stall("蚂蚁新村⛪请先开启蚂蚁新村")
                return
            }

            // 收取应收金币
            val astReceivableCoinVO = homeJson.getJSONObject("astReceivableCoinVO")
            if (astReceivableCoinVO.optBoolean("hasCoin")) {
                settleReceivable()
                tc.countDebug("收金币")
            }

            // 丢肥料
            if (stallThrowManure.value == true) {
                throwManure()
                tc.countDebug("丢肥料")
            }

            val seatsMap = homeJson.getJSONObject("seatsMap")

            // 收取金币
            settle(seatsMap)
            tc.countDebug("收取金币")

            // 收肥料
            collectManure()
            tc.countDebug("收肥料")

            // 请走操作
            sendBack(seatsMap)
            tc.countDebug("请走")

            // 收摊
            if (stallAutoClose.value == true) {
                closeShop()
                tc.countDebug("收摊")
            }

            // 摆摊
            if (stallAutoOpen.value == true) {
                openShop()
                tc.countDebug("摆摊")
            }

            // 自动任务
            if (stallAutoTask.value == true) {
                handledTaskFinishes.clear()
                handledTaskAwards.clear()
                loggedTaskMessages.clear()
                taskList()
                tc.countDebug("自动任务第一次")
                GlobalThreadPools.sleepCompat(500)
                taskList()
                tc.countDebug("自动任务第二次")
            }

            // 新村助力
            if (stallAssistFriend.value == true) {
                assistFriend()
                tc.countDebug("新村助力")
            }

            // 自动捐赠
            if (stallDonate.value == true && Status.canStallDonateToday()) {
                donate()
                tc.countDebug("自动捐赠")
            }

            // 进入下一村
            if (roadmap.value == true) {
                roadmap()
                tc.countDebug("自动进入下一村")
            }

            // 贴罚单
            if (stallAutoTicket.value == true) {
                pasteTicket()
                tc.countDebug("贴罚单")
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "home err:", t)
        } finally {
            Log.stall("执行结束-${getName()}")
        }
    }

    /**
     * @brief 请走小摊
     */
    private fun sendBack(
        billNo: String,
        seatId: String,
        shopId: String,
        shopUserId: String,
        sentUserId: MutableSet<String>
    ) {
        try {
            val preResponse = AntStallRpcCall.shopSendBackPre(billNo, seatId, shopId, shopUserId)
            val preJson = JSONObject(preResponse)

            if (!ResChecker.checkRes(TAG, preJson)) {
                Log.error(TAG, "sendBackPre err: $preResponse")
                return
            }

            val income = preJson.getJSONObject("astPreviewShopSettleVO").getJSONObject("income")
            val amount = income.getDouble("amount").toInt()

            val sendBackResponse = AntStallRpcCall.shopSendBack(seatId)
            val sendBackJson = JSONObject(sendBackResponse)

            if (ResChecker.checkRes(TAG, sendBackJson)) {
                val amountText = if (amount > 0) "获得金币$amount" else ""
                Log.stall("蚂蚁新村⛪请走[${UserMap.getMaskName(shopUserId)}]的小摊$amountText")
            } else {
                Log.error(TAG, "sendBack err: $sendBackResponse")
            }

            if (stallInviteShop.value == true) {
                inviteOpen(seatId, sentUserId)
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "sendBack err:", t)
        }
    }

    /**
     * @brief 邀请开店
     */
    private fun inviteOpen(seatId: String, sentUserId: MutableSet<String>) {
        try {
            val response = AntStallRpcCall.rankInviteOpen()
            val json = JSONObject(response)

            if (!ResChecker.checkRes(TAG, json)) {
                Log.error(TAG, "inviteOpen err: $response")
                return
            }

            val friendRankList = json.getJSONArray("friendRankList")
            for (i in 0 until friendRankList.length()) {
                val friend = friendRankList.getJSONObject(i)
                val friendUserId = friend.getString("userId")

                var isInviteShop = stallInviteShopList.contains(friendUserId)
                if (stallInviteShopType.value == StallInviteShopType.DONT_INVITE) {
                    isInviteShop = !isInviteShop
                }

                if (!isInviteShop || sentUserId.contains(friendUserId)) {
                    continue
                }
                if (FriendGuard.shouldSkipFriend(friendUserId, TAG, "邀请摆摊")) {
                    continue
                }

                if (friend.getBoolean("canInviteOpenShop")) {
                    val inviteResponse = AntStallRpcCall.oneKeyInviteOpenShop(friendUserId, seatId)
                    if (inviteResponse.isEmpty()) {
                        Log.stall("邀请[${UserMap.getMaskName(friendUserId)}]开店返回空,跳过")
                        continue
                    }

                    val inviteJson = JSONObject(inviteResponse)
                    if (ResChecker.checkRes(TAG, inviteJson)) {
                        Log.stall("蚂蚁新村⛪邀请[${UserMap.getMaskName(friendUserId)}]开店成功")
                        sentUserId.add(friendUserId)
                        return
                    } else {
                        Log.stall("邀请[${UserMap.getMaskName(friendUserId)}]开店失败: ${
                                inviteJson.optString("errorMessage")
                            }"
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "inviteOpen err:", t)
        }
    }

    /**
     * @brief 处理摊位请走逻辑
     */
    private fun sendBack(seatsMap: JSONObject) {
        try {
            val sentUserId = mutableSetOf<String>()

            // 记录已占用的用户
            for (i in 1..2) {
                val seat = seatsMap.getJSONObject("GUEST_0$i")
                if (seat.getString("status") == "BUSY") {
                    val rentLastUser = seat.optString("rentLastUser")
                    if (rentLastUser.isNotEmpty()) {
                        sentUserId.add(rentLastUser)
                    }
                }
            }

            // 处理每个摊位
            for (i in 1..2) {
                val seat = seatsMap.getJSONObject("GUEST_0$i")
                val seatId = seat.getString("seatId")

                // 摊位空闲时尝试邀请
                if (seat.getString("status") == "FREE") {
                    if (stallInviteShop.value == true) {
                        Log.stall("摊位[$i]空闲,尝试邀请好友...")
                        inviteOpen(seatId, sentUserId)
                    }
                    continue
                }

                if (stallAllowOpenReject.value != true) {
                    continue
                }

                val rentLastUser = seat.optString("rentLastUser")
                if (rentLastUser.isEmpty()) {
                    continue
                }
                if (FriendGuard.shouldSkipFriend(rentLastUser, TAG, "请走小摊")) {
                    continue
                }

                // 白名单跳过
                if (stallWhiteList.containsConfigured(rentLastUser)) {
                    Log.stall("好友[${UserMap.getMaskName(rentLastUser)}]在白名单中,跳过请走。"
                    )
                    continue
                }

                val rentLastBill = seat.getString("rentLastBill")
                val rentLastShop = seat.getString("rentLastShop")

                // 黑名单直接赶走
                if (stallBlackList.containsConfigured(rentLastUser)) {
                    Log.stall("好友[${UserMap.getMaskName(rentLastUser)}]在黑名单中,立即请走。"
                    )
                    sendBack(rentLastBill, seatId, rentLastShop, rentLastUser, sentUserId)
                    continue
                }

                // 超时判断
                val bizStartTime = seat.getLong("bizStartTime")
                val allowMinutes = stallAllowOpenTime.value ?: 0
                val endTime = bizStartTime + allowMinutes * 60 * 1000L

                if (System.currentTimeMillis() > endTime) {
                    Log.stall("好友[${UserMap.getMaskName(rentLastUser)}]摆摊超时,立即请走。")
                    sendBack(rentLastBill, seatId, rentLastShop, rentLastUser, sentUserId)
                } else {
                    val taskId = "SB|$seatId"
                    if (!hasChildTask(taskId)) {
                        addChildTask(ChildModelTask(taskId, "SB", {
                            cancelPersistentChildTask(taskId)
                            if (stallAllowOpenReject.value == true) {
                                if (!FriendGuard.shouldSkipFriend(rentLastUser, TAG, "请走小摊")) {
                                    sendBack(
                                        rentLastBill,
                                        seatId,
                                        rentLastShop,
                                        rentLastUser,
                                        sentUserId
                                    )
                                }
                            }
                        }, endTime))
                        registerPersistentChildTask(
                            taskId,
                            "SB",
                            endTime,
                            JSONObject()
                                .put("seat_id", seatId)
                                .put("rent_last_bill", rentLastBill)
                                .put("rent_last_shop", rentLastShop)
                                .put("rent_last_user", rentLastUser)
                                .put("biz_start_time", bizStartTime)
                        )
                        Log.stall("添加蹲点请走⛪在[${TimeUtil.getCommonDate(endTime)}]执行")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "sendBack err:", t)
        }
    }

    private fun runSendBackPersistentTask(childId: String, payload: JSONObject) {
        if (stallAllowOpenReject.value != true) {
            Log.stall("新村持久请走[$childId]触发时功能已关闭，跳过")
            return
        }
        val targetSeatId = payload.optString("seat_id").trim().ifBlank {
            childId.substringAfter("SB|", "")
        }
        val expectedUserId = payload.optString("rent_last_user").trim()
        if (targetSeatId.isBlank()) {
            Log.stall("新村持久请走[$childId]缺少 seat_id，跳过")
            return
        }

        val homeResponse = AntStallRpcCall.home()
        val homeJson = JSONObject(homeResponse)
        if (!ResChecker.checkRes(TAG, homeJson)) {
            Log.error(TAG, "persistent sendBack home err: $homeResponse")
            return
        }
        val seatsMap = homeJson.optJSONObject("seatsMap") ?: run {
            Log.stall("新村持久请走[$childId]未查询到摊位信息，跳过")
            return
        }
        val seat = findSeatById(seatsMap, targetSeatId) ?: run {
            Log.stall("新村持久请走[$childId]未找到摊位[$targetSeatId]，跳过")
            return
        }
        if (seat.optString("status") != "BUSY") {
            Log.stall("新村持久请走[$childId]摊位已非占用状态，跳过")
            return
        }
        val currentUserId = seat.optString("rentLastUser").trim()
        if (currentUserId.isBlank() || (expectedUserId.isNotBlank() && currentUserId != expectedUserId)) {
            Log.stall("新村持久请走[$childId]摊位用户已变化，跳过")
            return
        }
        if (FriendGuard.shouldSkipFriend(currentUserId, TAG, "请走小摊")) {
            return
        }

        val bizStartTime = seat.optLong("bizStartTime", 0L)
        val allowMinutes = stallAllowOpenTime.value ?: 0
        val endTime = bizStartTime + allowMinutes * 60 * 1000L
        if (endTime > System.currentTimeMillis()) {
            registerPersistentChildTask(
                childId,
                "SB",
                endTime,
                JSONObject()
                    .put("seat_id", targetSeatId)
                    .put("rent_last_bill", seat.optString("rentLastBill"))
                    .put("rent_last_shop", seat.optString("rentLastShop"))
                    .put("rent_last_user", currentUserId)
                    .put("biz_start_time", bizStartTime)
            )
            Log.stall("新村持久请走[$childId]尚未到点，重排到[${TimeUtil.getCommonDate(endTime)}]")
            return
        }

        sendBack(
            seat.optString("rentLastBill"),
            targetSeatId,
            seat.optString("rentLastShop"),
            currentUserId,
            collectOccupiedStallUsers(seatsMap)
        )
    }

    private fun findSeatById(seatsMap: JSONObject, seatId: String): JSONObject? {
        for (i in 1..2) {
            val seat = seatsMap.optJSONObject("GUEST_0$i") ?: continue
            if (seat.optString("seatId") == seatId) {
                return seat
            }
        }
        return null
    }

    private fun collectOccupiedStallUsers(seatsMap: JSONObject): MutableSet<String> {
        val sentUserId = mutableSetOf<String>()
        for (i in 1..2) {
            val seat = seatsMap.optJSONObject("GUEST_0$i") ?: continue
            if (seat.optString("status") == "BUSY") {
                seat.optString("rentLastUser").takeIf { it.isNotBlank() }?.let { sentUserId.add(it) }
            }
        }
        return sentUserId
    }

    /**
     * @brief 结算金币
     */
    private fun settle(seatsMap: JSONObject) {
        try {
            val seat = seatsMap.getJSONObject("MASTER")
            if (!seat.has("coinsMap")) return

            val coinsMap = seat.getJSONObject("coinsMap")
            val master = coinsMap.getJSONObject("MASTER")
            val assetId = master.getString("assetId")
            val settleCoin = master.getJSONObject("money").getDouble("amount").toInt()
            val fullShow = master.getBoolean("fullShow")

            if (fullShow || settleCoin > 100) {
                val response = AntStallRpcCall.settle(assetId, settleCoin)
                val json = JSONObject(response)
                if (ResChecker.checkRes(TAG, json)) {
                    Log.stall("蚂蚁新村⛪[收取金币]#$settleCoin")
                } else {
                    Log.error(TAG, "settle err: $response")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "settle err:", t)
        }
    }

    /**
     * @brief 收摊
     */
    private fun closeShop() {
        try {
            val response = AntStallRpcCall.shopList()
            val json = JSONObject(response)

            if (!ResChecker.checkRes(TAG, json)) {
                Log.error(TAG, "closeShop err: $response")
                return
            }

            val astUserShopList = json.getJSONArray("astUserShopList")
            if (astUserShopList.length() == 0) {
                Log.stall("没有正在摆摊的小摊可收。")
                return
            }

            Log.stall("检查 ${astUserShopList.length()} 个小摊的收摊时间...")

            for (i in 0 until astUserShopList.length()) {
                val shop = astUserShopList.getJSONObject(i)
                if (shop.getString("status") != "OPEN") continue

                val rentLastEnv = shop.getJSONObject("rentLastEnv")
                val gmtLastRent = rentLastEnv.getLong("gmtLastRent")
                val selfOpenMinutes = stallSelfOpenTime.value ?: 0
                val shopTime = gmtLastRent + selfOpenMinutes * 60 * 1000L
                val shopId = shop.getString("shopId")
                val rentLastBill = shop.getString("rentLastBill")
                val rentLastUser = shop.getString("rentLastUser")

                if (System.currentTimeMillis() > shopTime) {
                    Log.stall("小摊[$shopId]摆摊时间已到,执行收摊。")
                    shopClose(shopId, rentLastBill, rentLastUser)
                } else {
                    val taskId = "SH|$shopId"
                    if (!hasChildTask(taskId)) {
                        addChildTask(ChildModelTask(taskId, "SH", {
                            cancelPersistentChildTask(taskId)
                            if (stallAutoClose.value == true) {
                                shopClose(shopId, rentLastBill, rentLastUser)
                            }
                            GlobalThreadPools.sleepCompat(300L)
                            if (stallAutoOpen.value == true) {
                                openShop()
                            }
                        }, shopTime))
                        registerPersistentChildTask(
                            taskId,
                            "SH",
                            shopTime,
                            JSONObject()
                                .put("shop_id", shopId)
                                .put("rent_last_bill", rentLastBill)
                                .put("rent_last_user", rentLastUser)
                                .put("gmt_last_rent", gmtLastRent)
                        )
                        Log.stall("添加蹲点收摊⛪在[${TimeUtil.getCommonDate(shopTime)}]执行")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "closeShop err:", t)
        }
    }

    private fun runCloseShopPersistentTask(childId: String, payload: JSONObject) {
        val targetShopId = payload.optString("shop_id").trim().ifBlank {
            childId.substringAfter("SH|", "")
        }
        val expectedBillNo = payload.optString("rent_last_bill").trim()
        if (targetShopId.isBlank()) {
            Log.stall("新村持久收摊[$childId]缺少 shop_id，跳过")
            return
        }

        if (stallAutoClose.value == true) {
            val response = AntStallRpcCall.shopList()
            val json = JSONObject(response)
            if (!ResChecker.checkRes(TAG, json)) {
                Log.error(TAG, "persistent closeShop err: $response")
            } else {
                val shop = findOpenShopById(json.optJSONArray("astUserShopList"), targetShopId)
                if (shop == null) {
                    Log.stall("新村持久收摊[$childId]小摊已非开启状态，跳过收摊")
                } else {
                    val rentLastBill = shop.optString("rentLastBill").trim()
                    if (expectedBillNo.isNotBlank() && rentLastBill != expectedBillNo) {
                        Log.stall("新村持久收摊[$childId]账单已变化，跳过收摊")
                    } else {
                        val gmtLastRent = shop.optJSONObject("rentLastEnv")?.optLong("gmtLastRent", 0L) ?: 0L
                        val selfOpenMinutes = stallSelfOpenTime.value ?: 0
                        val shopTime = gmtLastRent + selfOpenMinutes * 60 * 1000L
                        if (shopTime > System.currentTimeMillis()) {
                            registerPersistentChildTask(
                                childId,
                                "SH",
                                shopTime,
                                JSONObject()
                                    .put("shop_id", targetShopId)
                                    .put("rent_last_bill", rentLastBill)
                                    .put("rent_last_user", shop.optString("rentLastUser"))
                                    .put("gmt_last_rent", gmtLastRent)
                            )
                            Log.stall("新村持久收摊[$childId]尚未到点，重排到[${TimeUtil.getCommonDate(shopTime)}]")
                            return
                        }
                        shopClose(targetShopId, rentLastBill, shop.optString("rentLastUser"))
                    }
                }
            }
        } else {
            Log.stall("新村持久收摊[$childId]触发时收摊功能已关闭，跳过收摊")
        }

        GlobalThreadPools.sleepCompat(300L)
        if (stallAutoOpen.value == true) {
            openShop()
        }
    }

    private fun findOpenShopById(shopList: JSONArray?, shopId: String): JSONObject? {
        if (shopList == null) return null
        for (i in 0 until shopList.length()) {
            val shop = shopList.optJSONObject(i) ?: continue
            if (shop.optString("shopId") == shopId && shop.optString("status") == "OPEN") {
                return shop
            }
        }
        return null
    }

    /**
     * @brief 摆摊
     */
    private fun openShop() {
        try {
            val response = AntStallRpcCall.shopList()
            val json = JSONObject(response)

            if (!ResChecker.checkRes(TAG, json)) {
                Log.error(TAG, "openShop err: $response")
                return
            }

            val astUserShopList = json.getJSONArray("astUserShopList")
            val shopIds: Queue<String> = LinkedList()

            for (i in 0 until astUserShopList.length()) {
                val shop = astUserShopList.getJSONObject(i)
                if (shop.getString("status") == "FREE") {
                    shopIds.add(shop.getString("shopId"))
                }
            }

            if (shopIds.isEmpty()) {
                Log.stall("没有空闲的小摊可用于摆摊。")
                return
            }

            Log.stall("找到 ${shopIds.size} 个空闲小摊,开始寻找好友村庄...")
            rankCoinDonate(shopIds)

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "openShop err:", t)
        }
    }

    /**
     * @brief 获取好友排行榜
     */
    private fun rankCoinDonate(shopIds: Queue<String>) {
        try {
            val response = AntStallRpcCall.rankCoinDonate()
            val json = JSONObject(response)

            if (!ResChecker.checkRes(TAG, json)) {
                Log.error(TAG, "rankCoinDonate err: $response")
                return
            }

            val friendRankList = json.getJSONArray("friendRankList")
            val seats = mutableListOf<Seat>()

            for (i in 0 until friendRankList.length()) {
                val friendRank = friendRankList.getJSONObject(i)
                if (!friendRank.getBoolean("canOpenShop")) continue

                val userId = friendRank.getString("userId")
                var isStallOpen = stallOpenList.contains(userId)
                if (stallOpenType.value == StallOpenType.CLOSE) {
                    isStallOpen = !isStallOpen
                }

                if (isStallOpen) {
                    val hot = friendRank.getInt("hot")
                    seats.add(Seat(userId, hot))
                }
            }

            friendHomeOpen(seats, shopIds)

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "rankCoinDonate err:", t)
        }
    }

    /**
     * @brief 在好友村庄开店
     */
    private fun openShop(seatId: String, userId: String, shopId: String) {
        try {
            val response = AntStallRpcCall.shopOpen(seatId, userId, shopId)
            val json = JSONObject(response)

            if (json.optString("resultCode") == "SUCCESS") {
                Log.stall("蚂蚁新村⛪在[${UserMap.getMaskName(userId)}]家摆摊")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "openShop err:", t)
        }
    }

    /**
     * @brief 访问好友主页并开店
     */
    private fun friendHomeOpen(seats: List<Seat>, shopIds: Queue<String>) {
        val sortedSeats = seats.sortedByDescending { it.hot }
        val currentUid = UserMap.currentUid

        for (seat in sortedSeats) {
            if (shopIds.isEmpty()) {
                return
            }
            val userId = seat.userId

            try {
                val json = queryFriendHomeIfAvailable(userId, "新村摆摊") ?: continue

                val seatsMap = json.getJSONObject("seatsMap")

                // 检查是否已占用摊位
                val guest1 = seatsMap.getJSONObject("GUEST_01")
                val rentUser1 = guest1.optString("rentLastUser")
                val guest2 = seatsMap.getJSONObject("GUEST_02")
                val rentUser2 = guest2.optString("rentLastUser")

                if (currentUid == rentUser1 || currentUid == rentUser2) {
                    Log.stall("已在[${UserMap.getMaskName(userId)}]家摆摊,跳过")
                    continue
                }

                val targetSeatId = when {
                    guest1.getBoolean("canOpenShop") -> guest1.getString("seatId")
                    guest2.getBoolean("canOpenShop") -> guest2.getString("seatId")
                    else -> null
                }
                if (targetSeatId == null) {
                    continue
                }
                val shopId = shopIds.poll() ?: return
                openShop(targetSeatId, userId, shopId)

            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
            }
        }
    }

    private fun queryFriendHomeIfAvailable(userId: String, sceneName: String): JSONObject? {
        if (FriendGuard.shouldSkipFriend(userId, TAG, sceneName)) {
            return null
        }
        val response = AntStallRpcCall.friendHome(userId)
        val json = JSONObject(response)
        val reason = json.optString("resultDesc")
            .ifEmpty { json.optString("errorMessage") }
            .ifEmpty { json.optString("memo") }
        if (
            json.optString("resultCode") == "NEED_UPGRADE_VILLAGE" ||
            reason.contains("未开通") ||
            reason.contains("未注册")
        ) {
            FriendCapabilityRecorder.record(
                userId,
                "STALL",
                FriendCapabilityState.NOT_OPEN,
                "AntStall.friendHome",
                reason.ifEmpty { json.optString("resultCode") }
            )
            Log.stall("$sceneName 跳过[${UserMap.getMaskName(userId) ?: userId}]：${reason.ifEmpty { "对方未开通蚂蚁新村或当前不可访问" }}")
            return null
        }
        if (!ResChecker.checkRes(TAG, json)) {
            Log.stall("$sceneName 跳过[${UserMap.getMaskName(userId) ?: userId}]：${reason.ifEmpty { "对方未开通蚂蚁新村或当前不可访问" }}")
            return null
        }
        FriendCapabilityRecorder.record(userId, "STALL", FriendCapabilityState.OPEN, "AntStall.friendHome")
        return json
    }

    /**
     * @brief 关闭商店
     */
    private fun shopClose(shopId: String, billNo: String, userId: String) {
        try {
            val preResponse = AntStallRpcCall.preShopClose(shopId, billNo)
            val preJson = JSONObject(preResponse)

            if (!ResChecker.checkRes(TAG, preJson)) {
                Log.error(TAG, "shopClose err: $preResponse")
                return
            }

            val income = preJson.getJSONObject("astPreviewShopSettleVO").getJSONObject("income")
            val closeResponse = AntStallRpcCall.shopClose(shopId)
            val closeJson = JSONObject(closeResponse)

            if (ResChecker.checkRes(TAG, closeJson)) {
                Log.stall(
                    "蚂蚁新村⛪收取在[${UserMap.getMaskName(userId)}]的摊位获得${
                        income.getString(
                            "amount"
                        )
                    }"
                )
            } else {
                Log.error(TAG, "shopClose err: $closeResponse")
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "shopClose err:", t)
        }
    }

    /**
     * @brief 处理任务列表
     */
    private fun taskList() {
        try {
            TaskFlowEngine(StallTaskFlowAdapter(), roundSleepMs = 500L).run()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "taskList err:", t)
        }
    }

    private inner class StallTaskFlowAdapter : TaskFlowAdapter {
        override val moduleName: String = STALL_TASK_BLACKLIST_MODULE
        override val flowName: String = "新村任务"

        override fun query(): JSONObject {
            val response = AntStallRpcCall.taskList()
            return JsonUtil.parseJSONObjectOrNull(response) ?: JSONObject()
                .put("success", false)
                .put("resultDesc", "taskList返回空或无法解析")
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return ResChecker.checkRes(TAG, response)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val items = mutableListOf<TaskFlowItem>()
            val signListModel = response.optJSONObject("signListModel")
            if (signListModel != null && !signListModel.optBoolean("currentKeySigned", true)) {
                items.add(
                    TaskFlowItem(
                        id = STALL_SIGN_TASK_TYPE,
                        title = "新村签到",
                        status = TaskStatus.TODO.name,
                        type = STALL_SIGN_TASK_TYPE,
                        actionType = "SIGN",
                        blacklistKeys = emptyList(),
                        raw = JSONObject().put("signListModel", signListModel)
                    )
                )
            }

            val taskModels = response.optJSONArray("taskModels") ?: return items
            for (i in 0 until taskModels.length()) {
                val task = taskModels.optJSONObject(i) ?: continue
                val taskType = task.optString("taskType").trim()
                if (taskType.isBlank()) {
                    continue
                }
                val bizInfo = parseStallBizInfo(task.opt("bizInfo"))
                val taskTitle = getStallTaskTitle(task, bizInfo, taskType)
                val taskStatus = task.optString("taskStatus").trim()
                val actionType = bizInfo.optString("actionType").trim().ifBlank {
                    task.optString("actionType").trim()
                }
                val awardCount = bizInfo.optString("awardCount").trim().ifBlank {
                    task.optString("awardCount").trim()
                }
                val raw = JSONObject()
                    .put("task", task)
                    .put("bizInfo", bizInfo)
                    .put("taskKey", buildStallTaskKey(taskType))
                    .put("awardCount", awardCount)

                items.add(
                    TaskFlowItem(
                        id = taskType,
                        title = taskTitle,
                        status = taskStatus,
                        type = taskType,
                        actionType = actionType,
                        blacklistKeys = listOf(taskType, taskTitle).filter { it.isNotBlank() },
                        raw = raw,
                        progress = "actionType=${actionType.ifBlank { "UNKNOWN" }} award=$awardCount"
                    )
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            if (item.type == STALL_SIGN_TASK_TYPE) {
                return TaskFlowPhase.READY_TO_COMPLETE
            }
            return when {
                isStallRewardReadyStatus(item.status) -> TaskFlowPhase.REWARD_READY
                isStallTerminalStatus(item.status) -> TaskFlowPhase.TERMINAL
                isStallTodoStatus(item.status) && isCompletableStallTask(item) -> TaskFlowPhase.READY_TO_COMPLETE
                isStallTodoStatus(item.status) -> TaskFlowPhase.UNSUPPORTED
                else -> TaskFlowPhase.UNKNOWN
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            if (Thread.currentThread().isInterrupted) {
                return true
            }

            val phase = mapPhase(item)
            val taskKey = buildStallTaskKey(item.type)
            if (handledTaskAwards.contains(taskKey) && phase == TaskFlowPhase.REWARD_READY) {
                return true
            }
            if (handledTaskFinishes.contains(taskKey) && phase == TaskFlowPhase.READY_TO_COMPLETE) {
                return true
            }
            if (phase == TaskFlowPhase.REWARD_READY && stallReceiveAward.value != true) {
                logStallTaskOnce("新村任务⛪[${item.title}]已完成，未开启领奖，跳过领取")
                return true
            }
            if (item.type == STALL_INVITE_REGISTER_TASK_TYPE &&
                phase == TaskFlowPhase.READY_TO_COMPLETE &&
                stallInviteRegister.value != true
            ) {
                logStallTaskOnce("新村任务⛪[${item.title}]未开启邀请好友开通，跳过")
                return true
            }
            if (phase == TaskFlowPhase.UNSUPPORTED) {
                logStallTaskOnce(
                    "新村任务⛪[${item.title}]暂不支持自动闭环，跳过 " +
                        "taskType=${item.type} actionType=${item.actionType.ifBlank { "UNKNOWN" }} status=${item.status}"
                )
                return true
            }
            return false
        }

        override fun isBlacklisted(item: TaskFlowItem): Boolean {
            val blacklisted = super<TaskFlowAdapter>.isBlacklisted(item)
            if (blacklisted) {
                logStallTaskOnce("新村任务⛪[${item.title}]已在黑名单中，跳过处理")
            }
            return blacklisted
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            return receiveTaskAward(item)
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            return when (item.type) {
                STALL_SIGN_TASK_TYPE -> signToday()
                STALL_DAILY_QA_TASK_TYPE -> completeDailyQuestionTask(item)
                STALL_INVITE_REGISTER_TASK_TYPE -> completeInviteRegisterTask(item)
                STALL_XLIGHT_TASK_TYPE -> handleXlightTask(item)
                else -> finishTask(item)
            }
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            val taskKey = buildStallTaskKey(item.type)
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

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            Log.stall("新村任务⛪全部处理完成[${snapshot.completedTasks}/${snapshot.totalTasks}]")
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.error(TAG, "新村任务列表查询失败 raw=$response")
        }

        override fun logInfo(message: String) {
            Log.stall(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun rememberHandledTask(item: TaskFlowItem, action: TaskFlowAction) {
            val taskKey = buildStallTaskKey(item.type)
            when (action) {
                TaskFlowAction.RECEIVE -> handledTaskAwards.add(taskKey)
                TaskFlowAction.COMPLETE -> handledTaskFinishes.add(taskKey)
                else -> Unit
            }
        }
    }

    /**
     * @brief 处理X-light任务
     */
    private fun handleXlightTask(item: TaskFlowItem): TaskFlowActionResult {
        val response = AntStallRpcCall.xlightPlugin()
        val json = JsonUtil.parseJSONObjectOrNull(response) ?: return emptyStallActionResponse(
            rpc = "AntStallRpcCall.xlightPlugin",
            item = item,
            action = "xlightPlugin",
            raw = response
        )

        val playingResult = json.optJSONObject("playingResult") ?: return stallTaskActionFailureResult(
            response = json,
            rpc = "AntStallRpcCall.xlightPlugin",
            detail = stallTaskActionDetail(item, "xlightPlugin")
        )
        val pid = playingResult.optString("playingBizId").trim()
        if (pid.isBlank()) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "XLight缺少playingBizId",
                rpc = "AntStallRpcCall.xlightPlugin",
                raw = json.toString(),
                detail = stallTaskActionDetail(item, "xlightPlugin")
            )
        }

        val eventList = JsonUtil.getValueByPathObject(
            playingResult,
            "eventRewardDetail.eventRewardInfoList"
        ) as? JSONArray ?: return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            message = "XLight缺少奖励事件",
            rpc = "AntStallRpcCall.xlightPlugin",
            raw = json.toString(),
            detail = stallTaskActionDetail(item, "xlightPlugin")
        )

        if (eventList.length() == 0) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "XLight奖励事件为空",
                rpc = "AntStallRpcCall.xlightPlugin",
                raw = json.toString(),
                detail = stallTaskActionDetail(item, "xlightPlugin")
            )
        }

        var successCount = 0
        var firstFailure: TaskFlowActionResult? = null
        for (j in 0 until eventList.length()) {
            val eventInfo = eventList.optJSONObject(j) ?: continue
            val finishResponse = AntStallRpcCall.finish(pid, eventInfo)
            Log.stall("延时5S 木兰市集")
            GlobalThreadPools.sleepCompat(5000)

            val finishJson = JsonUtil.parseJSONObjectOrNull(finishResponse)
            if (finishJson == null) {
                if (firstFailure == null) {
                    firstFailure = emptyStallActionResponse(
                        rpc = "AntStallRpcCall.finish",
                        item = item,
                        action = "xlightFinish",
                        raw = finishResponse
                    )
                }
                continue
            }

            if (isStallTaskRpcSuccess(finishJson)) {
                successCount++
            } else if (firstFailure == null) {
                firstFailure = stallTaskActionFailureResult(
                    response = finishJson,
                    rpc = "AntStallRpcCall.finish",
                    detail = stallTaskActionDetail(item, "xlightFinish")
                )
            }
        }

        return if (successCount > 0) {
            Log.stall("蚂蚁新村💣任务[${item.title}]完成")
            TaskFlowActionResult.success()
        } else {
            firstFailure ?: TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "XLight未推进任何事件",
                rpc = "AntStallRpcCall.finish",
                raw = json.toString(),
                detail = stallTaskActionDetail(item, "xlightFinish")
            )
        }
    }

    /**
     * @brief 今日签到
     */
    private fun signToday(): TaskFlowActionResult {
        val response = AntStallRpcCall.signToday()
        val json = JsonUtil.parseJSONObjectOrNull(response) ?: return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.RETRYABLE_RPC,
            message = "signToday返回空或无法解析",
            rpc = "AntStallRpcCall.signToday",
            raw = response,
            stopCurrentRound = true
        )

        return if (isStallTaskRpcSuccess(json)) {
            Log.stall("蚂蚁新村⛪[签到成功]")
            TaskFlowActionResult.success()
        } else {
            stallTaskActionFailureResult(
                response = json,
                rpc = "AntStallRpcCall.signToday",
                detail = "taskType=$STALL_SIGN_TASK_TYPE action=signToday"
            )
        }
    }

    /**
     * @brief 领取任务奖励
     */
    private fun receiveTaskAward(item: TaskFlowItem): TaskFlowActionResult {
        val response = AntStallRpcCall.receiveTaskAward(item.type)
        val json = JsonUtil.parseJSONObjectOrNull(response) ?: return emptyStallActionResponse(
            rpc = "AntStallRpcCall.receiveTaskAward",
            item = item,
            action = "receiveTaskAward",
            raw = response
        )

        return if (isStallTaskRpcSuccess(json)) {
            Log.stall("蚂蚁新村⛪[领取奖励:${item.title}]")
            TaskFlowActionResult.success()
        } else {
            stallTaskActionFailureResult(
                response = json,
                rpc = "AntStallRpcCall.receiveTaskAward",
                detail = stallTaskActionDetail(item, "receiveTaskAward")
            )
        }
    }

    /**
     * @brief 完成任务
     */
    private fun finishTask(item: TaskFlowItem): TaskFlowActionResult {
        val response = AntStallRpcCall.finishTask(
            "${item.type}_${System.currentTimeMillis()}",
            item.type
        )
        val json = JsonUtil.parseJSONObjectOrNull(response) ?: return emptyStallActionResponse(
            rpc = "AntStallRpcCall.finishTask",
            item = item,
            action = "finishTask",
            raw = response
        )

        return if (isStallTaskRpcSuccess(json)) {
            Log.stall("蚂蚁新村💣任务[${item.title}]完成")
            TaskFlowActionResult.success()
        } else {
            stallTaskActionFailureResult(
                response = json,
                rpc = "AntStallRpcCall.finishTask",
                detail = stallTaskActionDetail(item, "finishTask")
            )
        }
    }

    private fun completeDailyQuestionTask(item: TaskFlowItem): TaskFlowActionResult {
        val bizInfo = item.raw?.optJSONObject("bizInfo") ?: return missingStallRawResult(item, "dailyQuestion")
        return if (ReadingDada.answerQuestion(bizInfo)) {
            TaskFlowActionResult.success()
        } else {
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "答题任务未完成",
                rpc = "ReadingDada.answerQuestion",
                detail = stallTaskActionDetail(item, "dailyQuestion")
            )
        }
    }

    private fun completeInviteRegisterTask(item: TaskFlowItem): TaskFlowActionResult {
        return if (inviteRegister()) {
            TaskFlowActionResult.success()
        } else {
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                message = "未找到可邀请好友或邀请未成功",
                rpc = "AntStall.inviteRegister",
                detail = stallTaskActionDetail(item, "inviteRegister")
            )
        }
    }

    private fun emptyStallActionResponse(
        rpc: String,
        item: TaskFlowItem,
        action: String,
        raw: String = ""
    ): TaskFlowActionResult {
        return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.RETRYABLE_RPC,
            message = "${action}返回空或无法解析",
            rpc = rpc,
            raw = raw,
            detail = stallTaskActionDetail(item, action),
            stopCurrentRound = true
        )
    }

    private fun missingStallRawResult(item: TaskFlowItem, action: String): TaskFlowActionResult {
        return TaskFlowActionResult.failure(
            failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            message = "缺少任务原始数据",
            rpc = "StallTaskFlowAdapter.$action",
            detail = stallTaskActionDetail(item, action)
        )
    }

    private fun stallTaskActionFailureResult(
        response: JSONObject,
        rpc: String,
        detail: String
    ): TaskFlowActionResult {
        val code = extractStallTaskFailureCode(response)
        val message = extractStallTaskFailureMessage(response)
        val failureType = classifyStallTaskFailure(response)
        return TaskFlowActionResult.failure(
            failureType = failureType,
            code = code,
            message = message,
            rpc = rpc,
            raw = response.toString(),
            detail = detail,
            stopCurrentRound = failureType == TaskRpcFailureType.RETRYABLE_RPC
        )
    }

    private fun stallTaskActionDetail(item: TaskFlowItem, action: String): String {
        return "taskType=${item.type} taskName=${item.title} actionType=${item.actionType} action=$action"
    }

    private fun buildStallTaskKey(taskType: String): String {
        return taskType.ifBlank { "UNKNOWN_TASK" }
    }

    private fun parseStallBizInfo(rawBizInfo: Any?): JSONObject {
        return when (rawBizInfo) {
            is JSONObject -> rawBizInfo
            is String -> rawBizInfo.takeIf { it.isNotBlank() }?.let {
                JsonUtil.parseJSONObjectOrNull(it)
            } ?: JSONObject()
            else -> JSONObject()
        }
    }

    private fun getStallTaskTitle(task: JSONObject, bizInfo: JSONObject, taskType: String): String {
        return sequenceOf(
            bizInfo.optString("taskTitle"),
            bizInfo.optString("title"),
            bizInfo.optString("taskName"),
            bizInfo.optString("taskDesc"),
            task.optString("taskTitle"),
            task.optString("title"),
            taskType
        ).firstOrNull { it.isNotBlank() } ?: taskType
    }

    private fun isCompletableStallTask(item: TaskFlowItem): Boolean {
        return item.actionType == "VISIT_AUTO_FINISH" ||
            item.type in TASK_TYPE_LIST ||
            item.type == STALL_DAILY_QA_TASK_TYPE ||
            item.type == STALL_INVITE_REGISTER_TASK_TYPE ||
            item.type == STALL_XLIGHT_TASK_TYPE
    }

    private fun isStallRewardReadyStatus(taskStatus: String): Boolean {
        return taskStatus == TaskStatus.FINISHED.name ||
            taskStatus == "COMPLETE" ||
            taskStatus == "WAIT_RECEIVE" ||
            taskStatus == "TO_RECEIVE" ||
            taskStatus == "UNLOCKED"
    }

    private fun isStallTerminalStatus(taskStatus: String): Boolean {
        return taskStatus == TaskStatus.RECEIVED.name ||
            taskStatus == "HAS_RECEIVED" ||
            taskStatus == "DONE" ||
            taskStatus == "COMPLETED"
    }

    private fun isStallTodoStatus(taskStatus: String): Boolean {
        return taskStatus == TaskStatus.TODO.name ||
            taskStatus == "WAIT_COMPLETE"
    }

    private fun isStallTaskRpcSuccess(response: JSONObject): Boolean {
        response.optJSONObject("resData")?.let {
            return isStallTaskRpcSuccess(it)
        }
        if (response.has("success")) {
            return response.optBoolean("success", false)
        }
        if (response.has("isSuccess")) {
            return response.optBoolean("isSuccess", false)
        }

        val resultCode = response.opt("resultCode")
        when (resultCode) {
            is Number -> if (resultCode.toInt() == 100 || resultCode.toInt() == 200) return true
            is String -> {
                if (resultCode.equals("SUCCESS", ignoreCase = true) ||
                    resultCode == "100" ||
                    resultCode == "200"
                ) {
                    return true
                }
                if (resultCode.isNotBlank()) {
                    return false
                }
            }
        }

        if ("SUCCESS".equals(response.optString("memo"), ignoreCase = true)) {
            return true
        }
        val resultDesc = response.optString("resultDesc").trim()
        if (resultDesc == "成功" || resultDesc == "处理成功") {
            return true
        }
        val resultView = response.optString("resultView").trim()
        return resultView == "成功" || resultView == "处理成功"
    }

    private fun classifyStallTaskFailure(response: JSONObject): TaskRpcFailureType {
        response.optJSONObject("resData")?.let {
            return classifyStallTaskFailure(it)
        }
        val code = extractStallTaskFailureCode(response)
        val message = extractStallTaskFailureMessage(response)
        return when {
            isStallTaskAlreadyHandled(response) ||
                containsAnyStall(message, "已领取", "已经领取", "重复领取", "重复领奖", "重复完成", "已完成", "任务已完结", "任务已结束", "无状态转换处理") ->
                TaskRpcFailureType.TERMINAL_DONE

            ResChecker.isSilentFailure(response) ||
                code == "CAMP_TRIGGER_ERROR" ||
                code.contains("LIMIT", ignoreCase = true) ||
                containsAnyStall(message, "上限", "限制", "受限", "不可领取", "资格不足", "兑完", "风控", "风险") ->
                TaskRpcFailureType.BUSINESS_LIMIT

            code == "400000040" ||
                containsAnyStall(message, "不支持rpc调用", "不支持RPC完成") ->
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                containsAnyStall(message, "参数错误", "任务ID非法", "模板不存在") ->
                TaskRpcFailureType.NON_RETRYABLE_INVALID

            code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK", "SYSTEM_BUSY", "NETWORK_ERROR", "I07", "USER_FREQUENTLY_LOCK") ||
                containsAnyStall(message, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试", "需要验证", "访问被拒绝") ||
                isStallFailureMarkedRetryable(response) ->
                TaskRpcFailureType.RETRYABLE_RPC

            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun isStallTaskAlreadyHandled(response: JSONObject): Boolean {
        val code = extractStallTaskFailureCode(response)
        val message = extractStallTaskFailureMessage(response)
        return code == "400000030" ||
            code == "B000000008" ||
            containsAnyStall(message, "任务已完结", "无状态转换处理")
    }

    private fun extractStallTaskFailureCode(response: JSONObject): String {
        response.optJSONObject("resData")?.let {
            return extractStallTaskFailureCode(it)
        }
        return response.optString("code").trim()
            .ifBlank { response.optString("errorCode").trim() }
            .ifBlank { response.optString("resultCode").trim() }
            .ifBlank { response.opt("error")?.toString()?.trim().orEmpty() }
    }

    private fun extractStallTaskFailureMessage(response: JSONObject): String {
        response.optJSONObject("resData")?.let {
            return extractStallTaskFailureMessage(it)
        }
        return sequenceOf(
            response.optString("desc").trim(),
            response.optString("errorMsg").trim(),
            response.optString("resultDesc").trim(),
            response.optString("resultMessage").trim(),
            response.optString("resultMsg").trim(),
            response.optString("errorMessage").trim(),
            response.optString("memo").trim(),
            response.optString("message").trim(),
            response.optString("resultView").trim(),
            response.optString("errorTip").trim()
        ).firstOrNull { it.isNotBlank() } ?: response.toString()
    }

    private fun isStallFailureMarkedRetryable(response: JSONObject): Boolean {
        response.optJSONObject("resData")?.let {
            return isStallFailureMarkedRetryable(it)
        }
        return listOf("retryable", "retriable", "canRetry").any { key ->
            response.has(key) && response.optBoolean(key, false)
        }
    }

    private fun containsAnyStall(text: String, vararg keywords: String): Boolean {
        return keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private fun logStallTaskOnce(message: String) {
        if (loggedTaskMessages.add(message)) {
            Log.stall(message)
        }
    }

    /**
     * @brief 邀请好友注册
     */
    private fun inviteRegister(): Boolean {
        if (stallInviteRegister.value != true) return false

        try {
            val response = AntStallRpcCall.rankInviteRegister()
            val json = JSONObject(response)

            if (!ResChecker.checkRes(TAG, json)) {
                Log.error(TAG, "rankInviteRegister err: $response")
                return false
            }

            val friendRankList = json.optJSONArray("friendRankList") ?: return false
            if (friendRankList.length() == 0) return false

            for (i in 0 until friendRankList.length()) {
                val friend = friendRankList.getJSONObject(i)

                if (!friend.optBoolean("canInviteRegister", false) ||
                    friend.getString("userStatus") != "UNREGISTER"
                ) {
                    continue
                }

                val userId = friend.getString("userId")
                if (FriendGuard.shouldSkipFriend(userId, TAG, "邀请开通新村")) {
                    continue
                }
                if (!stallInviteRegisterList.contains(userId)) {
                    continue
                }

                val inviteResponse = AntStallRpcCall.friendInviteRegister(userId)
                val inviteJson = JSONObject(inviteResponse)

                if (ResChecker.checkRes(TAG, inviteJson)) {
                    Log.stall("蚂蚁新村⛪邀请好友[${UserMap.getMaskName(userId)}]#开通新村")
                    return true
                } else {
                    Log.error(TAG, "friendInviteRegister err: $inviteJson")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "InviteRegister err:", t)
        }
        return false
    }

    /**
     * @brief 分享助力
     */
    private fun shareP2P(): String? {
        try {
            val response = AntStallRpcCall.shareP2P()
            val json = JSONObject(response)

            if (json.optBoolean("success")) {
                val shareId = json.getString("shareId")
                Log.stall("蚂蚁新村⛪[分享助力]")
                return shareId
            } else {
                Log.error(TAG, "shareP2P err: $response")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "shareP2P err:", t)
        }
        return null
    }

    /**
     * @brief 助力好友
     */
    private fun assistFriend() {
        try {
            if (stallAssistFriend.value != true) return

            if (!Status.canAntStallAssistFriendToday()) {
                Log.stall("今日新村助力次数已用完。")
                return
            }

            val friendSet = assistFriendList.resolvedIds()
            if (friendSet.isEmpty()) {
                Log.stall("未设置新村助力好友列表。")
                return
            }

            Log.stall("开始为 ${friendSet.size} 位好友进行新村助力...")

            for (safeUid in friendSet) {
                if (FriendGuard.shouldSkipFriend(safeUid, TAG, "新村助力")) {
                    continue
                }
                val shareId = Base64.encodeToString(
                    "$safeUid-${RandomUtil.getRandomInt(5)}ANUTSALTML_2PA_SHARE".toByteArray(),
                    Base64.NO_WRAP
                )

                val response = AntStallRpcCall.achieveBeShareP2P(shareId)
                val json = JSONObject(response)
                val name = UserMap.getMaskName(safeUid)

                if (!json.optBoolean("success")) {
                    when (json.getString("code")) {
                        "600000028" -> {
                            Log.stall("新村助力🮐被助力次数上限[$name]")
                            continue
                        }

                        "600000027" -> {
                            Log.stall("新村助力💪今日助力他人次数上限")
                            Status.antStallAssistFriendToday()
                            return
                        }

                        else -> {
                            Log.error(TAG, "新村助力😔失败[$name]${json.optString("desc")}")
                            continue
                        }
                    }
                }

                Log.stall("新村助力🎉成功[$name]")
                GlobalThreadPools.sleepCompat(5000)
            }

            Status.antStallAssistFriendToday()

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "assistFriend err:", t)
        }
    }

    /**
     * @brief 捐赠项目
     */
    private fun donate() {
        try {
            val response = AntStallRpcCall.projectList()
            val json = JSONObject(response)

            if (json.optString("resultCode", "") != "SUCCESS") return

            // 检查余额
            val userInfo = json.optJSONObject("astUserInfoVO")
            if (userInfo != null) {
                val currentCoinAmount = userInfo.optJSONObject("currentCoin")
                    ?.optDouble("amount", 0.0) ?: 0.0

                if (currentCoinAmount < 15000) {
                    return
                }
            }

            // 查找在线项目
            val projects = json.optJSONArray("astProjectVOS") ?: return

            for (i in 0 until projects.length()) {
                val project = projects.optJSONObject(i) ?: continue

                if (project.optString("status", "") == "ONLINE") {
                    val projectId = project.optString("projectId", "")

                    // 获取项目详情
                    val detailResponse = AntStallRpcCall.projectDetail(projectId)
                    val detailJson = JSONObject(detailResponse)

                    if (detailJson.optString("resultCode", "") == "SUCCESS") {
                        // 执行捐赠
                        val donateResponse = AntStallRpcCall.projectDonate(projectId)
                        val donateJson = JSONObject(donateResponse)

                        val astProjectVO = donateJson.optJSONObject("astProjectVO")
                        if (astProjectVO != null) {
                            val title = astProjectVO.optString("title", "未知项目")

                            if (donateJson.optString("resultCode", "") == "SUCCESS") {
                                Log.stall("蚂蚁新村⛪[捐赠:$title]")
                                Status.setStallDonateToday()
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "donate err:", t)
        }
    }

    /**
     * @brief 进入下一村
     */
    private fun roadmap() {
        try {
            val response = AntStallRpcCall.roadmap()
            val json = JSONObject(response)

            if (!ResChecker.checkRes(TAG, json)) return

            val roadList = json.getJSONArray("roadList")
            var hasNewVillage = false

            for (i in 0 until roadList.length()) {
                val road = roadList.getJSONObject(i)

                if (road.getString("status") != "NEW") continue

                hasNewVillage = true
                val villageName = road.getString("villageName")
                val flagKey = StatusFlags.FLAG_ANTSTALL_ROADMAP_VISITED_PREFIX + villageName

                if (Status.hasFlagToday(flagKey)) {
                    Log.stall("今日已进入[$villageName],跳过重复打卡。")
                    continue
                }

                Log.stall("蚂蚁新村⛪[进入:$villageName]成功")
                Status.setFlagToday(flagKey)
                break
            }

            if (!hasNewVillage) {
                Log.stall("所有村庄都已解锁,无需进入下一村。")
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "roadmap err:", t)
        }
    }

    /**
     * @brief 收集肥料
     */
    private fun collectManure() {
        try {
            val response = AntStallRpcCall.queryManureInfo()
            val json = JSONObject(response)

            if (!json.optBoolean("success")) {
                Log.error(TAG, "collectManure err: $response")
                return
            }

            val astManureInfoVO = json.getJSONObject("astManureInfoVO")
            if (astManureInfoVO.optBoolean("hasManure")) {
                val manure = astManureInfoVO.getInt("manure")
                val collectResponse = AntStallRpcCall.collectManure()
                val collectJson = JSONObject(collectResponse)

                if (ResChecker.checkRes(TAG, collectJson)) {
                    Log.stall("蚂蚁新村⛪获得肥料${manure}g")
                }
            } else {
                Log.stall("没有可收取的肥料。")
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "collectManure err:", t)
        }
    }

    /**
     * @brief 丢肥料批量处理
     */
    private fun throwManure(dynamicList: JSONArray) {
        // 前置检查:如果今日已达上限,直接跳过
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTSTALL_THROW_MANURE_LIMIT)) {
            return
        }

        try {
            val response = AntStallRpcCall.throwManure(dynamicList)
            val json = JSONObject(response)

            // 先于ResChecker判断特定业务错误码
            val resultCode = json.optString("resultCode")
            if (resultCode == "B_OVER_LIMIT_COUNT_OF_THROW_TO_FRIEND") {
                Log.stall("检测到今日丢肥料次数已达上限,停止后续尝试")
                Status.setFlagToday(StatusFlags.FLAG_ANTSTALL_THROW_MANURE_LIMIT)
                return
            }

            // 正常的响应检查
            if (ResChecker.checkRes(TAG, json)) {
                Log.stall("蚂蚁新村⛪打肥料成功")
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "throwManure err:", t)
        } finally {
            GlobalThreadPools.sleepCompat(1000)
        }
    }

    /**
     * @brief 丢肥料主流程
     */
    private fun throwManure() {
        try {
            val response = AntStallRpcCall.dynamicLoss()
            val json = JSONObject(response)

            if (!ResChecker.checkRes(TAG, json)) {
                Log.error(TAG, "throwManure err: $response")
                return
            }

            val astLossDynamicVOS = json.getJSONArray("astLossDynamicVOS")
            var dynamicList = JSONArray()

            for (i in 0 until astLossDynamicVOS.length()) {
                val lossDynamic = astLossDynamicVOS.getJSONObject(i)

                if (lossDynamic.has("specialEmojiVO")) continue

                val objectId = lossDynamic.getString("objectId")
                if (FriendGuard.shouldSkipFriend(objectId, TAG, "新村丢肥料")) {
                    continue
                }
                var isThrowManure = stallThrowManureList.contains(objectId)

                if (stallThrowManureType.value == StallThrowManureType.DONT_THROW) {
                    isThrowManure = !isThrowManure
                }

                if (!isThrowManure) continue

                val dynamic = JSONObject().apply {
                    put("bizId", lossDynamic.getString("bizId"))
                    put("bizType", lossDynamic.getString("bizType"))
                }
                dynamicList.put(dynamic)

                if (dynamicList.length() == 5) {
                    throwManure(dynamicList)
                    dynamicList = JSONArray()
                }
            }

            if (dynamicList.length() > 0) {
                throwManure(dynamicList)
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "throwManure err:", t)
        }
    }

    /**
     * @brief 结算应收金币
     */
    private fun settleReceivable() {
        try {
            val response = AntStallRpcCall.settleReceivable()
            val json = JSONObject(response)

            if (ResChecker.checkRes(TAG, json)) {
                Log.stall("蚂蚁新村⛪收取应收金币")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "settleReceivable err:", t)
        }
    }

    /**
     * @brief 贴罚单
     */
    private fun pasteTicket() {
        try {
            if (!Status.canPasteTicketTime()) {
                Log.stall("未到贴罚单时间或今日已贴完。")
                return
            }

            Log.stall("开始巡逻,寻找可贴罚单的好友...")

            while (!Thread.currentThread().isInterrupted) {
                try {
                    val response = AntStallRpcCall.nextTicketFriend()
                    val json = JSONObject(response)

                    if (!json.optBoolean("success")) {
                        Log.error(
                            TAG,
                            "pasteTicket.nextTicketFriend err: ${json.optString("resultDesc")}"
                        )
                        return
                    }

                    if (json.getInt("canPasteTicketCount") == 0) {
                        Log.stall("蚂蚁新村👍[今日罚单已贴完]")
                        Status.pasteTicketTime()
                        return
                    }

                    val friendId = json.optString("friendUserId")
                    if (friendId.isEmpty()) {
                        Log.stall("没有更多可贴罚单的好友了。")
                        return
                    }

                    var isStallTicket = stallTicketList.contains(friendId)
                    if (stallTicketType.value == StallTicketType.DONT_TICKET) {
                        isStallTicket = !isStallTicket
                    }

                    if (!isStallTicket) continue

                    // 访问好友主页
                    val homeJson = queryFriendHomeIfAvailable(friendId, "新村贴罚单") ?: continue

                    val seatsMap = homeJson.getJSONObject("seatsMap")
                    val keys = seatsMap.keys()

                    while (keys.hasNext()) {
                        if (Thread.currentThread().isInterrupted) return
                        try {
                            val key = keys.next()
                            val propertyValue = seatsMap.get(key)

                            if (propertyValue !is JSONObject || propertyValue.length() == 0) {
                                continue
                            }

                            if (propertyValue.getBoolean("canOpenShop") ||
                                propertyValue.getString("status") != "BUSY" ||
                                !propertyValue.getBoolean("overTicketProtection")
                            ) {
                                continue
                            }

                            val rentLastUser = propertyValue.getString("rentLastUser")
                            val ticketResponse = AntStallRpcCall.ticket(
                                propertyValue.getString("rentLastBill"),
                                propertyValue.getString("seatId"),
                                propertyValue.getString("rentLastShop"),
                                rentLastUser,
                                propertyValue.getString("userId")
                            )

                            val ticketJson = JSONObject(ticketResponse)
                            if (!ticketJson.optBoolean("success")) {
                                val failureText = pasteTicketFailureText(ticketJson)
                                if (isPasteTicketLimitReached(failureText)) {
                                    Log.stall("蚂蚁新村👍[今日罚单已贴完]")
                                    Status.pasteTicketTime()
                                    return
                                }
                                Log.error(
                                    TAG,
                                    "pasteTicket.ticket err: $failureText"
                                )
                                return
                            }

                            Log.stall("蚂蚁新村🚫在[${UserMap.getMaskName(friendId)}]贴罚单")

                        } finally {
                            GlobalThreadPools.sleepCompat(1000)
                        }
                    }

                } finally {
                    GlobalThreadPools.sleepCompat(1500)
                }
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "pasteTicket err:", t)
        }
    }

    private fun pasteTicketFailureText(ticketJson: JSONObject): String {
        return sequenceOf(
            ticketJson.optString("resultDesc"),
            ticketJson.optString("resultMessage"),
            ticketJson.optString("resultMsg"),
            ticketJson.optString("desc"),
            ticketJson.optString("memo")
        ).firstOrNull { it.isNotBlank() } ?: ticketJson.toString()
    }

    private fun isPasteTicketLimitReached(resultDesc: String): Boolean {
        return resultDesc.contains("贴罚单次数已用完") ||
            resultDesc.contains("明天再来") ||
            (resultDesc.contains("罚单") && resultDesc.contains("次数") && resultDesc.contains("用完"))
    }

    /**
     * @brief 摆摊操作类型
     */
    interface StallOpenType {
        companion object {
            const val OPEN = 0
            const val CLOSE = 1
            val nickNames = arrayOf("选中摆摊", "选中不摆摊")
        }
    }

    /**
     * @brief 贴罚单操作类型
     */
    interface StallTicketType {
        companion object {
            const val TICKET = 0
            const val DONT_TICKET = 1
            val nickNames = arrayOf("选中贴罚单", "选中不贴罚单")
        }
    }

    /**
     * @brief 丢肥料操作类型
     */
    interface StallThrowManureType {
        companion object {
            const val THROW = 0
            const val DONT_THROW = 1
            val nickNames = arrayOf("选中丢肥料", "选中不丢肥料")
        }
    }

    /**
     * @brief 邀请摆摊操作类型
     */
    interface StallInviteShopType {
        companion object {
            const val INVITE = 0
            const val DONT_INVITE = 1
            val nickNames = arrayOf("选中邀请", "选中不邀请")
        }
    }

    companion object {
        private const val TAG = "AntStall"
        private const val STALL_TASK_BLACKLIST_MODULE = "蚂蚁新村"
        private const val STALL_SIGN_TASK_TYPE = "ANTSTALL_SIGN_TODAY"
        private const val STALL_DAILY_QA_TASK_TYPE = "ANTSTALL_NORMAL_DAILY_QA"
        private const val STALL_INVITE_REGISTER_TASK_TYPE = "ANTSTALL_NORMAL_INVITE_REGISTER"
        private const val STALL_XLIGHT_TASK_TYPE = "ANTSTALL_XLIGHT_VARIABLE_AWARD"
        const val PERSISTENT_CHILD_KIND = "stall_child_task"

        /**
         * @brief 任务类型列表
         */
        private val TASK_TYPE_LIST = listOf(
            "ANTSTALL_NORMAL_OPEN_NOTICE",  // 开启摊新村收益提醒
            "tianjiashouye",                 // 添加首页
            "ANTSTALL_ELEME_VISIT",          // 去饿了么果园逛一逛
            "ANTSTALL_TASK_diantao202311",   // 去点淘赚元宝提现
            "ANTSTALL_TASK_nongchangleyuan"  // 农场乐园
        )
    }
}


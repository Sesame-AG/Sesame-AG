package io.github.aoguai.sesameag.task.antMember

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.Status.Companion.canMemberPointExchangeBenefitToday
import io.github.aoguai.sesameag.data.Status.Companion.canMemberSignInToday
import io.github.aoguai.sesameag.data.Status.Companion.hasFlagToday
import io.github.aoguai.sesameag.data.Status.Companion.memberPointExchangeBenefitToday
import io.github.aoguai.sesameag.data.Status.Companion.memberSignInToday
import io.github.aoguai.sesameag.data.Status.Companion.setFlagToday
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.BeanExchangeRight
import io.github.aoguai.sesameag.entity.MemberBenefit
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.hook.HookReadyChecker
import io.github.aoguai.sesameag.hook.internal.LocationHelper.requestLocationSuspend
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.antOrchard.UrlUtil
import io.github.aoguai.sesameag.task.exchange.ExchangeCost
import io.github.aoguai.sesameag.task.exchange.ExchangeItem
import io.github.aoguai.sesameag.task.exchange.ExchangeLimit
import io.github.aoguai.sesameag.task.exchange.ExchangeSafety
import io.github.aoguai.sesameag.task.exchange.ExchangeSafetyRules
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowDecision
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Log.record
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.BeanExchangeRightMap
import io.github.aoguai.sesameag.util.maps.MemberBenefitsMap
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max

class AntMember : ModelTask() {
    override fun getName(): String {
        return "会员"
    }

    override fun getGroup(): ModelGroup {
        return ModelGroup.MEMBER
    }

    override fun getIcon(): String {
        return "AntMember.png"
    }

    internal var memberSign: BooleanModelField? = null
    internal var memberTask: BooleanModelField? = null
    internal var memberPointExchangeBenefit: BooleanModelField? = null
    private var memberPointExchangeBenefitList: SelectModelField? = null
    private var collectInsuredGold: BooleanModelField? = null
    private var enableGameCenter: BooleanModelField? = null
    internal var merchantSign: BooleanModelField? = null
    internal var merchantKmdk: BooleanModelField? = null
    internal var merchantMoreTask: BooleanModelField? = null
    internal var beanSignIn: BooleanModelField? = null
    internal var beanExchangeRight: BooleanModelField? = null
    private var beanExchangeRightList: SelectModelField? = null
    private val loggedUnsupportedMemberTaskIds = LinkedHashSet<String>()
    private var unsupportedMemberTaskOverflowLogged = false


    /*//年度回顾
    private var annualReview: BooleanModelField? = null*/

    // 黄金票配置 - 签到
    internal var enableGoldTicket: BooleanModelField? = null

    // 黄金票配置 - 提取/兑换
    internal var enableGoldTicketConsume: BooleanModelField? = null

    /** 账单 贴纸 功能开关 */
    private var collectStickers: BooleanModelField? = null

    private val goldTicketTaskBlacklistModule = "黄金票"


    private enum class MemberTaskTargetBusinessType {
        BROWSE,
        CALL_APP,
        UNSUPPORTED
    }

    private data class ResolvedMemberTaskTargetBusiness(
        val raw: String = "",
        val type: MemberTaskTargetBusinessType = MemberTaskTargetBusinessType.UNSUPPORTED
    )

    private data class CurrentMemberTask(
        val taskConfigId: String,
        val taskProcessId: String,
        val title: String,
        val awardPoint: String,
        val targetBusiness: String,
        val targetBusinessType: MemberTaskTargetBusinessType = MemberTaskTargetBusinessType.UNSUPPORTED,
        val simpleTaskConfig: JSONObject,
        val adBizId: String,
        val status: String = "",
        val current: Int? = null,
        val limit: Int? = null
    )

    private data class MemberTaskProcessAward(
        val taskProcessId: String,
        val awardRelatedOutBizNo: String,
        val title: String,
        val awardPoint: Int,
        val stageIndex: Int
    )

    private enum class CurrentMemberTaskVerifyState {
        CONFIRMED,
        PARTIAL_REPEATABLE,
        UNCONFIRMED
    }

    private enum class CurrentMemberTaskListProcessState {
        COMPLETED,
        HANDLED_PENDING_CONFIRM,
        NO_ACTIONABLE_TASK,
        RETRY_LATER,
        NO_TASK,
        UNKNOWN
    }

    private data class MemberFloatingBallTaskRef(
        val bizNo: String,
        val taskType: String,
        val taskStatus: String,
        val endDt: Long,
        val executeTimeSeconds: Long
    )

    private enum class MemberFloatingBallTaskProcessState {
        PROCESSED,
        NO_TASK,
        RETRY_LATER,
        UNKNOWN
    }


    private data class StickerFollowUpResult(
        val success: Boolean = true,
        val handled: Boolean = false
    )

    private data class MemberExchangeCandidate(
        val item: ExchangeItem,
        val benefitId: String,
        val itemId: String,
        val pointNeeded: String,
        val benefitMark: String,
        val itemSource: String,
        val requestSourceInfo: String = "",
        val sourcePassMap: JSONObject? = null
    )

    private data class BeanExchangeCandidate(
        val item: ExchangeItem,
        val rightsId: String,
        val assetAmount: Int,
        val needOrder: Int
    )

    private enum class StickerRpcFailureType {
        BUSINESS_LIMIT,
        DUPLICATE_REWARD,
        NON_RETRYABLE
    }

    private enum class DailyTaskProcessResult {
        HANDLED,
        RETRYABLE_FAILURE,
        UNKNOWN_FAILURE
    }

    private data class InsuredTaskCenterConfig(
        val taskCenterId: String,
        val sceneCode: String,
        val controlSolutionSceneCode: String? = null
    )

    private data class InsuredGoldCollectionPassResult(
        val availableCount: Int,
        val result: DailyTaskProcessResult
    )

    private enum class InsuredGoldRpcFailureType {
        BUSINESS_LIMIT,
        DUPLICATE_REWARD,
        RETRYABLE,
        NON_RETRYABLE
    }

    private enum class GuardianBeanAwardRpcFailureType {
        BUSINESS_LIMIT,
        DUPLICATE_REWARD,
        RETRYABLE,
        NON_RETRYABLE
    }

    private data class GoldTicketTaskFlowHandleResult(
        val querySuccess: Boolean,
        val canMarkDone: Boolean
    )

    private val insuredTaskCenterConfigs = listOf(
        InsuredTaskCenterConfig("AP16236844", "TASK_LIST", "GIFT_GOLD_NORMAL_TASK_CONTROL"),
        InsuredTaskCenterConfig("AP19236833", "TOP_LIST", "GIFT_GOLD_TOP_TASK_CONTROL"),
        InsuredTaskCenterConfig("AP19301319", "BZJ_SWAP_TASK_CONSULT_CONTROL", "BZJ_SWAP_TASK_CONSULT_CONTROL"),
        InsuredTaskCenterConfig("AP12301346", "BZJ_SOFT_TASK_CONSULT_CONTROL", "BZJ_SOFT_TASK_CONSULT_CONTROL")
    )

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(BooleanModelField("memberSign", "会员积分 | 签到", false).withDesc(
            "执行会员中心每日签到并领取会员积分。"
        ).also {
            memberSign = it
        })
        modelFields.addField(BooleanModelField("memberTask", "会员积分 | 任务", false).withDesc(
            "执行会员中心每日任务，完成后统一领取会员积分。"
        ).also {
            memberTask = it
        })



        modelFields.addField(
            BooleanModelField(
                "memberPointExchangeBenefit", "会员积分 | 兑换权益", false
            ).withDesc("按“会员积分 | 兑换列表”处理已勾选权益；仅纯积分虚拟道具自动兑换，实付或下单权益只记录提醒。").also { memberPointExchangeBenefit = it })
        modelFields.addField(
            SelectModelField(
                "memberPointExchangeBenefitList",
                "会员积分 | 兑换列表",
                LinkedHashSet<String?>()
            ) {
                refreshMemberPointExchangeOptionsForSettings()
                MemberBenefit.getList()
            }.withDesc("勾选需要处理的会员权益。需开启“会员积分 | 兑换权益”。").also { memberPointExchangeBenefitList = it })




        modelFields.addField(
            BooleanModelField(
                "collectInsuredGold", "蚂蚁保 | 保障金领取", false
            ).withDesc("领取蚂蚁保页面可收取的签到保障金和活动保障金。").also { collectInsuredGold = it })

        // 黄金票配置
        modelFields.addField(
            BooleanModelField(
                "enableGoldTicket", "黄金票 | 签到与收取", false
            ).withDesc("执行黄金票首页签到与日常收取，持续累积黄金票。").also { enableGoldTicket = it })
        modelFields.addField(
            BooleanModelField(
                "enableGoldTicketConsume", "黄金票 | 提取/兑换黄金", false
            ).withDesc("黄金票达到提取条件后自动兑换或提取黄金。").also { enableGoldTicketConsume = it })
        modelFields.addField(BooleanModelField("enableGameCenter", "游戏中心 | 签到", false).withDesc(
            "执行游戏中心签到、平台任务，并领取可收取的玩乐豆奖励。"
        ).also {
            enableGameCenter = it
        })
        modelFields.addField(
            BooleanModelField(
                "merchantSign", "商家服务 | 签到", false
            ).withDesc("执行商家服务每日签到，包含可领取时会顺带处理招财金签到积分。").also { merchantSign = it })
        modelFields.addField(
            BooleanModelField(
                "merchantKmdk", "商家服务 | 开门打卡", false
            ).withDesc("执行商家服务开门打卡的报名与上午签到，需在可用时段内运行。").also { merchantKmdk = it })
        modelFields.addField(
            BooleanModelField(
                "merchantMoreTask", "商家服务 | 积分任务", false
            ).withDesc("执行商家服务积分任务，并顺带领取任务产出的积分球奖励。").also {
                merchantMoreTask = it
            })
        modelFields.addField(
            BooleanModelField(
                "beanSignIn", "安心豆 | 签到", false
            ).withDesc("执行安心豆每日签到，领取当天可得的安心豆奖励。").also { beanSignIn = it })
        modelFields.addField(
            BooleanModelField(
                "beanExchangeRight", "安心豆 | 兑换权益", false
            ).withDesc("按“安心豆 | 兑换列表”刷新并处理安心豆权益；涉及下单或外跳的权益只记录提醒。").also { beanExchangeRight = it })
        modelFields.addField(
            SelectModelField(
                "beanExchangeRightList",
                "安心豆 | 兑换列表",
                LinkedHashSet<String?>()
            ) {
                refreshBeanExchangeRightOptionsForSettings()
                BeanExchangeRight.getList()
            }.withDesc("勾选允许处理的安心豆权益，需开启“安心豆 | 兑换权益”。").also { beanExchangeRightList = it })
       /* modelFields.addField(
            BooleanModelField(
                "annualReview", "年度回顾", false
            ).also { annualReview = it })*/


        modelFields.addField(
            BooleanModelField("CollectStickers", "账单贴纸 | 领取", false).withDesc(
                "扫描并领取当前账单周期内可领取的贴纸奖励。"
            ).also { collectStickers = it }
        )



        return modelFields
    }

    override fun runJava() {
        runBlocking {
            try {
                Log.member("执行开始-${getName()}")
                requestLocationSuspend()

                val deferredTasks = mutableListOf<Deferred<Unit>>()
                val memberPointPlan = prepareMemberPointWorkflows(this, deferredTasks)

                if (collectInsuredGold?.value == true) {
                    deferredTasks.add(async(Dispatchers.IO) { collectInsuredGold() })
                }

                scheduleGoldTicketWorkflows(this, deferredTasks)

                if (enableGameCenter?.value == true) {
                    deferredTasks.add(async(Dispatchers.IO) { enableGameCenter() })
                }

               /* if (annualReview!!.value) {   //年度回顾已下线
                    deferredTasks.add(async(Dispatchers.IO) { doAnnualReview() })
                }*/

                scheduleBeanWorkflows(this, deferredTasks)
                scheduleMerchantWorkflows(this, deferredTasks)

                if (collectStickers?.value == true) {
                    queryAndCollectStickers()
                }

                deferredTasks.awaitAll()
                finishMemberPointWorkflows(memberPointPlan)

            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
            } finally {
                Log.member("执行结束-${getName()}")
            }
        }
    }

    internal suspend fun runMerchantWorkflow() {
        val needKmdkSignIn =
            merchantKmdk?.value == true &&
                !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNIN_DONE) &&
                TimeUtil.isNowAfterTimeStr("0600") &&
                TimeUtil.isNowBeforeTimeStr("1200")
        val needKmdkSignUp =
            merchantKmdk?.value == true &&
                !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNUP_DONE)
        val needMerchantSign =
            merchantSign?.value == true &&
                !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_SIGN_DONE)
        val needMerchantMoreTask =
            merchantMoreTask?.value == true &&
                !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_MORE_TASK_DONE)

        if (!(needKmdkSignIn || needKmdkSignUp || needMerchantSign || needMerchantMoreTask)) {
            Log.member("⏭️ 今天已处理过商家服务相关任务，跳过执行")
            return
        }

        if (!canRunMerchantService()) {
            return
        }

        if (needMerchantSign) {
            if (doMerchantSign()) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_SIGN_DONE)
                collectMerchantPointBalls()
            }
        }
        if (needMerchantMoreTask) {
            doMerchantMoreTask()
        }
        if (merchantKmdk?.value == true && (needKmdkSignIn || needKmdkSignUp)) {
            if (needKmdkSignIn) {
                if (kmdkSignIn()) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNIN_DONE)
                }
            } else if (TimeUtil.isNowAfterTimeStr("1200")) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNIN_DONE)
            }
            if (needKmdkSignUp) {
                if (kmdkSignUp()) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNUP_DONE)
                }
            }
        }
    }


    /*
     * 年度回顾已下线：相关 RPC/组件字段不再维护。
     * 为保证编译通过，暂时整体注释掉这一段实现（含 RPC/组件常量未补齐部分）。
     *
     * 如需恢复：请先补齐 AntMemberRpcCall.annualReview* 与组件常量后再启用。
     *
    /**
     * 年度回顾任务：通过 programInvoke 查询并自动完成任务
     *
     *
     * 1) alipay.imasp.program.programInvoke + ..._task_reward_query 查询 playTaskOrderInfoList
     * 2) 对于 taskStatus = "init" 的任务，使用 ..._task_reward_apply(code) 领取，得到 recordNo
     * 3) 使用 ..._task_reward_process(code, recordNo) 上报完成，服务端自动发放成长值奖励
     */
    private suspend fun doAnnualReview(): Unit = CoroutineUtils.run {
        try {
            Log.member("年度回顾🎞[开始执行]")

            val resp = AntMemberRpcCall.annualReviewQueryTasks()
            if (resp == null || resp.isEmpty()) {
                Log.member("年度回顾[查询返回空]")
                return
            }

            val root: JSONObject?
            try {
                root = JSONObject(resp)
            } catch (e: Throwable) {
                Log.printStackTrace("$TAG.doAnnualReview.parseRoot", e)
                return
            }

            if (!root.optBoolean("isSuccess", false)) {
                Log.member("年度回顾[查询失败]#$resp")
                return
            }

            val components = root.optJSONObject("components")
            if (components == null || components.length() == 0) {
                Log.member("年度回顾[components 为空]")
                return
            }

            var queryComp = components.optJSONObject(AntMemberRpcCall.ANNUAL_REVIEW_QUERY_COMPONENT)
            if (queryComp == null) {
                // 兜底：取第一个组件
                try {
                    val it = components.keys()
                    if (it.hasNext()) {
                        queryComp = components.optJSONObject(it.next())
                    }
                } catch (_: Throwable) {
                }
            }
            if (queryComp == null) {
                Log.member("年度回顾[未找到查询组件]")
                return
            }
            if (!queryComp.optBoolean("isSuccess", true)) {
                Log.member("年度回顾[查询组件返回失败]")
                return
            }

            val content = queryComp.optJSONObject("content")
            if (content == null) {
                Log.member("年度回顾[content 为空]")
                return
            }

            val taskList = content.optJSONArray("playTaskOrderInfoList")
            if (taskList == null || taskList.length() == 0) {
                Log.member("年度回顾[当前无可处理任务]")
                return
            }

            var candidate = 0
            var applied = 0
            var processed = 0
            var failed = 0

            for (i in 0..<taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue

                val taskStatus = task.optString("taskStatus", "")
                if ("init" != taskStatus) {
                    // 已完成/已领奖等状态直接跳过
                    continue
                }
                candidate++

                var code = task.optString("code", "")
                if (code.isEmpty()) {
                    val extInfo = task.optJSONObject("extInfo")
                    if (extInfo != null) {
                        code = extInfo.optString("taskId", "")
                    }
                }
                if (code.isEmpty()) {
                    failed++
                    continue
                }

                var taskName = code
                val displayInfo = task.optJSONObject("displayInfo")
                if (displayInfo != null) {
                    val name = displayInfo.optString(
                        "taskName", displayInfo.optString("activityName", code)
                    )
                    if (!name.isEmpty()) {
                        taskName = name
                    }
                }

                // ========== Step 1: 领取任务 (apply) ==========
                val applyResp = AntMemberRpcCall.annualReviewApplyTask(code)
                if (applyResp == null || applyResp.isEmpty()) {
                    Log.member("年度回顾[领任务失败]$taskName#响应为空")
                    failed++
                    continue
                }

                val applyRoot: JSONObject?
                try {
                    applyRoot = JSONObject(applyResp)
                } catch (e: Throwable) {
                    Log.printStackTrace("$TAG.doAnnualReview.parseApply", e)
                    failed++
                    continue
                }
                if (!applyRoot.optBoolean("isSuccess", false)) {
                    Log.member("年度回顾[领任务失败]$taskName#$applyResp")
                    failed++
                    continue
                }
                val applyComps = applyRoot.optJSONObject("components")
                if (applyComps == null) {
                    failed++
                    continue
                }
                var applyComp = applyComps.optJSONObject(AntMemberRpcCall.ANNUAL_REVIEW_APPLY_COMPONENT)
                if (applyComp == null) {
                    try {
                        val it2 = applyComps.keys()
                        if (it2.hasNext()) {
                            applyComp = applyComps.optJSONObject(it2.next())
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (applyComp == null || !applyComp.optBoolean("isSuccess", true)) {
                    failed++
                    continue
                }
                val applyContent = applyComp.optJSONObject("content")
                if (applyContent == null) {
                    failed++
                    continue
                }
                val claimedTask = applyContent.optJSONObject("claimedTask")
                if (claimedTask == null) {
                    failed++
                    continue
                }
                val recordNo = claimedTask.optString("recordNo", "")
                if (recordNo.isEmpty()) {
                    failed++
                    continue
                }
                applied++

                // ========== Step 2: 提交任务完成 (process) ==========
                val processResp = AntMemberRpcCall.annualReviewProcessTask(code, recordNo)
                if (processResp == null || processResp.isEmpty()) {
                    Log.member("年度回顾[提交任务失败]$taskName#响应为空")
                    failed++
                    continue
                }

                val processRoot: JSONObject?
                try {
                    processRoot = JSONObject(processResp)
                } catch (e: Throwable) {
                    Log.printStackTrace("$TAG.doAnnualReview.parseProcess", e)
                    failed++
                    continue
                }
                if (!processRoot.optBoolean("isSuccess", false)) {
                    Log.member("年度回顾[提交任务失败]$taskName#$processResp")
                    failed++
                    continue
                }
                val processComps = processRoot.optJSONObject("components")
                if (processComps == null) {
                    failed++
                    continue
                }
                var processComp = processComps.optJSONObject(AntMemberRpcCall.ANNUAL_REVIEW_PROCESS_COMPONENT)
                if (processComp == null) {
                    try {
                        val it3 = processComps.keys()
                        if (it3.hasNext()) {
                            processComp = processComps.optJSONObject(it3.next())
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (processComp == null || !processComp.optBoolean("isSuccess", true)) {
                    failed++
                    continue
                }
                val processContent = processComp.optJSONObject("content")
                if (processContent == null) {
                    failed++
                    continue
                }
                val processedTask = processContent.optJSONObject("processedTask")
                if (processedTask == null) {
                    failed++
                    continue
                }
                val newStatus = processedTask.optString("taskStatus", "")
                var rewardStatus = processedTask.optString("rewardStatus", "")

                // ========== Step 3: 如仍未发奖，则调用 get_reward 领取奖励 ==========
                if (!"success".equals(rewardStatus, ignoreCase = true)) {
                    try {
                        val rewardResp = AntMemberRpcCall.annualReviewGetReward(code, recordNo)
                        if (rewardResp != null && !rewardResp.isEmpty()) {
                            val rewardRoot = JSONObject(rewardResp)
                            if (rewardRoot.optBoolean("isSuccess", false)) {
                                val rewardComps = rewardRoot.optJSONObject("components")
                                if (rewardComps != null) {
                                    var rewardComp = rewardComps.optJSONObject(AntMemberRpcCall.ANNUAL_REVIEW_GET_REWARD_COMPONENT)
                                    if (rewardComp == null) {
                                        try {
                                            val it4 = rewardComps.keys()
                                            if (it4.hasNext()) {
                                                rewardComp = rewardComps.optJSONObject(it4.next())
                                            }
                                        } catch (_: Throwable) {
                                        }
                                    }
                                    if (rewardComp != null && rewardComp.optBoolean(
                                            "isSuccess", true
                                        )
                                    ) {
                                        val rewardContent = rewardComp.optJSONObject("content")
                                        if (rewardContent != null) {
                                            var rewardTask = rewardContent.optJSONObject("processedTask")
                                            if (rewardTask == null) {
                                                rewardTask = rewardContent.optJSONObject("claimedTask")
                                            }
                                            if (rewardTask != null) {
                                                val rs = rewardTask.optString("rewardStatus", "")
                                                if (!rs.isEmpty()) {
                                                    rewardStatus = rs
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.printStackTrace("$TAG.doAnnualReview.getReward", e)
                    }
                }

                processed++
                Log.member("年度回顾🎞[任务完成]$taskName#状态=$newStatus 奖励状态=$rewardStatus")
            }

            Log.member("年度回顾🎞[执行结束] 待处理=$candidate 已领取=$applied 已提交=$processed 失败=$failed"
            )
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.doAnnualReview", t)
        }
    }

    */

    /**
     * 会员积分0元兑，权益道具兑换
     */
    private fun refreshMemberPointExchangeOptionsForSettings() {
        if (!HookReadyChecker.isCurrentProcessReadyForRpc(UserMap.currentUid)) {
            if (!HookReadyChecker.isTargetAppReadyForRpc(UserMap.currentUid) ||
                !ExchangeOptionsRefreshBridge.requestRefresh(
                    ExchangeOptionsRefreshBridge.TARGET_MEMBER_POINT,
                    UserMap.currentUid
                )
            ) {
                Log.member("会员积分🎐目标应用未就绪，设置页使用缓存列表")
                return
            }
            val memberBenefitMap = IdMapManager.getInstance(MemberBenefitsMap::class.java)
            memberBenefitMap.load(UserMap.currentUid)
            Log.member("会员积分🎐设置页加载目标应用刷新列表#${memberBenefitMap.map.size}")
            return
        }
        try {
            val userId = UserMap.currentUid
            val memberInfo = JSONObject(AntMemberRpcCall.queryMemberInfo())
            if (!ResChecker.checkRes(TAG, "会员积分兑换列表刷新失败:", memberInfo)) {
                return
            }
            val pointBalance = memberInfo.optString("pointBalance")
                .ifEmpty { memberInfo.optInt("pointBalance", 0).toString() }
            val memberBenefitMap = IdMapManager.getInstance(MemberBenefitsMap::class.java)
            val candidateMap = queryMemberExchangeCandidates(userId, pointBalance)
            candidateMap.values.forEach { candidate ->
                memberBenefitMap.add(candidate.item.id, candidate.item.displayName())
            }
            memberBenefitMap.save(userId)
            Log.member("会员积分🎐设置页刷新兑换列表#${candidateMap.size}")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "refreshMemberPointExchangeOptionsForSettings err:", t)
        }
    }

    internal fun refreshMemberPointExchangeOptionsForRemote() {
        refreshMemberPointExchangeOptionsForSettings()
    }

    private fun queryMemberExchangeCandidates(
        userId: String?,
        pointBalance: String,
        sleepMillis: Long = 0L
    ): LinkedHashMap<String, MemberExchangeCandidate> {
        val candidateMap = LinkedHashMap<String, MemberExchangeCandidate>()
        var currentPage = 1
        var hasNextPage = true
        while (hasNextPage) {
            if (sleepMillis > 0L) {
                GlobalThreadPools.sleepCompat(sleepMillis)
            }
            val responseStr = AntMemberRpcCall.queryShandieEntityList(userId.orEmpty(), pointBalance, currentPage, 18)
            if (responseStr.isEmpty()) {
                break
            }
            val jo = JSONObject(responseStr)
            if (!ResChecker.checkRes(TAG, "会员积分闪兑列表查询失败:", jo)) {
                break
            }
            addMemberExchangeCandidates(jo.optJSONArray("benefits"), candidateMap)
            val nextPageNum = jo.optInt("nextPageNum", 0)
            hasNextPage = nextPageNum > currentPage
            currentPage = if (hasNextPage) nextPageNum else currentPage
        }

        val deliveryUniqueId = "${System.currentTimeMillis()}and99999999INTELLIGENT_SORT5000551494000SR2024110510425045,94000SR2025091714812006,94000SR2023102305988003"
        currentPage = 1
        hasNextPage = true
        while (hasNextPage && currentPage <= 10) {
            if (sleepMillis > 0L) {
                GlobalThreadPools.sleepCompat(sleepMillis)
            }
            val jo = JSONObject(
                AntMemberRpcCall.queryDeliveryZoneDetail(
                    pointBalance = pointBalance,
                    pageNum = currentPage,
                    pageSize = 18,
                    uniqueId = deliveryUniqueId
                )
            )
            if (!ResChecker.checkRes(TAG, "会员积分专区列表查询失败:", jo)) {
                break
            }
            val uniqueId = jo.optString("uniqueId", deliveryUniqueId)
            addMemberExchangeCandidates(jo.optJSONArray("briefConfigInfos"), candidateMap, currentPage, 18, uniqueId)
            addMemberExchangeEntityCandidates(jo.optJSONArray("entityInfoList"), candidateMap, currentPage, 18, uniqueId)
            val nextPageNum = jo.optInt("nextPageNum", 0)
            hasNextPage = nextPageNum > currentPage
            currentPage = if (hasNextPage) nextPageNum else currentPage
        }
        return candidateMap
    }

    private fun addMemberExchangeCandidates(
        benefits: JSONArray?,
        candidateMap: LinkedHashMap<String, MemberExchangeCandidate>,
        pageNum: Int = 1,
        pageSize: Int = 18,
        uniqueId: String = ""
    ) {
        if (benefits == null || benefits.length() == 0) {
            return
        }
        for (i in 0 until benefits.length()) {
            val globalIndex = (pageNum - 1) * pageSize + i + 1
            val sourcePassMap = if (uniqueId.isNotBlank()) buildMemberDeliveryZoneSourcePassMap(uniqueId, globalIndex) else null
            val requestSourceInfo = uniqueId.takeIf { it.isNotBlank() }?.let { "SID:$it|$globalIndex" }.orEmpty()
            val candidate = buildMemberExchangeCandidate(
                benefits.optJSONObject(i) ?: continue,
                requestSourceInfo = requestSourceInfo,
                sourcePassMap = sourcePassMap
            ) ?: continue
            candidateMap.putIfAbsent(candidate.item.id, candidate)
        }
    }

    private fun addMemberExchangeEntityCandidates(
        entities: JSONArray?,
        candidateMap: LinkedHashMap<String, MemberExchangeCandidate>,
        pageNum: Int,
        pageSize: Int,
        uniqueId: String
    ) {
        if (entities == null || entities.length() == 0) {
            return
        }
        for (i in 0 until entities.length()) {
            val benefit = entities.optJSONObject(i)?.optJSONObject("benefitInfo") ?: continue
            val globalIndex = (pageNum - 1) * pageSize + i + 1
            val sourcePassMap = buildMemberDeliveryZoneSourcePassMap(uniqueId, globalIndex)
            val candidate = buildMemberExchangeCandidate(
                benefit,
                requestSourceInfo = "SID:$uniqueId|$globalIndex",
                sourcePassMap = sourcePassMap
            ) ?: continue
            candidateMap.putIfAbsent(candidate.item.id, candidate)
        }
    }

    private fun buildMemberDeliveryZoneSourcePassMap(uniqueId: String, feedsIndex: Int): JSONObject {
        return JSONObject().apply {
            put("bid", "202412231259661040")
            put("feedsIndex", feedsIndex.toString())
            put("innerSource", "a159.b114660")
            put("isCpc", "")
            put("source", "")
            put("unid", "")
            put("uniqueId", uniqueId)
        }
    }

    private fun refreshBeanExchangeRightOptionsForSettings() {
        if (!HookReadyChecker.isCurrentProcessReadyForRpc(UserMap.currentUid)) {
            if (!HookReadyChecker.isTargetAppReadyForRpc(UserMap.currentUid) ||
                !ExchangeOptionsRefreshBridge.requestRefresh(
                    ExchangeOptionsRefreshBridge.TARGET_BEAN_RIGHT,
                    UserMap.currentUid
                )
            ) {
                Log.member("安心豆🫘目标应用未就绪，设置页使用缓存列表")
                return
            }
            val beanRightMap = IdMapManager.getInstance(BeanExchangeRightMap::class.java)
            beanRightMap.load(UserMap.currentUid)
            Log.member("安心豆🫘设置页加载目标应用刷新列表#${beanRightMap.map.size}")
            return
        }
        try {
            val userId = UserMap.currentUid
            val candidateMap = queryBeanExchangeCandidates(queryBlueBeanBalance())
            val beanRightMap = IdMapManager.getInstance(BeanExchangeRightMap::class.java)
            candidateMap.values.forEach { candidate ->
                beanRightMap.add(candidate.item.id, candidate.item.displayName())
            }
            beanRightMap.save(userId)
            Log.member("安心豆🫘设置页刷新兑换列表#${candidateMap.size}")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "refreshBeanExchangeRightOptionsForSettings err:", t)
        }
    }

    internal fun refreshBeanExchangeRightOptionsForRemote() {
        refreshBeanExchangeRightOptionsForSettings()
    }

    private fun queryBeanExchangeCandidates(beanBalance: Int?): LinkedHashMap<String, BeanExchangeCandidate> {
        val candidateMap = LinkedHashMap<String, BeanExchangeCandidate>()
        val bizProperties = listOf(
            "",
            "RED_PACKET_COUPON",
            "VOUCHER_CARD",
            "CASH_MEMBER",
            "HEALTH",
            "HOEM_DALIY",
            "MOTHER_BABY_TOYS"
        )
        for (bizProperty in bizProperties) {
            var pageStartIndex = 0
            var pageCount = 0
            while (pageCount < 10) {
                pageCount++
                val response = JSONObject(
                    AntMemberRpcCall.rightsRecommend(
                        pageStartIndex = pageStartIndex,
                        bizProperty = bizProperty
                    )
                )
                if (!ResChecker.checkRes(TAG, "安心豆权益推荐列表查询失败:", response)) {
                    break
                }
                addBeanExchangeCandidates(response, candidateMap, fromHistory = false, beanBalance = beanBalance)
                val result = extractBeanExchangeResult(response)
                if (!result.optBoolean("hasNext", false)) {
                    break
                }
                val nextStartIndex = result.optInt("pageEndIndex", pageStartIndex)
                if (nextStartIndex <= pageStartIndex) {
                    break
                }
                pageStartIndex = nextStartIndex
            }
        }
        runCatching {
            val response = JSONObject(AntMemberRpcCall.queryRightsPreExchangeFlows(pageStartIndex = 0, pageSize = 99))
            if (ResChecker.checkRes(TAG, "安心豆预兑换列表查询失败:", response)) {
                addBeanExchangeCandidates(response, candidateMap, fromHistory = false, beanBalance = beanBalance)
            }
        }.onFailure {
            Log.printStackTrace(TAG, "queryBeanExchangeCandidates.queryRightsPreExchangeFlows err:", it)
        }
        return candidateMap
    }

    internal fun memberPointExchangeBenefit() {
        if (hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_BENEFIT_REFRESH_DONE)) {
            return
        }
        val selectedIds: Set<String> = memberPointExchangeBenefitList?.value
            ?.filterNotNull()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
        if (selectedIds.isNotEmpty() && selectedIds.all { !canMemberPointExchangeBenefitToday(it) }) {
            Log.member("会员积分🎐兑换列表今日已全部处理，跳过执行")
            setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_BENEFIT_REFRESH_DONE)
            return
        }
        try {
            val userId = UserMap.currentUid
            val memberInfo = JSONObject(AntMemberRpcCall.queryMemberInfo())
            if (!ResChecker.checkRes(TAG, "会员积分信息查询失败:", memberInfo)) {
                return
            }
            val pointBalance = memberInfo.optString("pointBalance")
                .ifEmpty { memberInfo.optInt("pointBalance", 0).toString() }
            val remainingSelectedIds: MutableSet<String>? = if (selectedIds.isNotEmpty()) {
                selectedIds.toMutableSet()
            } else {
                null
            }
            val memberBenefitMap = IdMapManager.getInstance(MemberBenefitsMap::class.java)
            Log.member("会员积分🎐兑换列表刷新..")
            val candidateMap = queryMemberExchangeCandidates(userId, pointBalance, 1000L)
            candidateMap.values.forEach { candidate ->
                memberBenefitMap.add(candidate.item.id, candidate.item.displayName())
                if (!selectedIds.contains(candidate.item.id)) {
                    return@forEach
                }
                remainingSelectedIds?.remove(candidate.item.id)
                if (!canMemberPointExchangeBenefitToday(candidate.item.id)) {
                    Log.member("会员积分🎐跳过[${candidate.item.name}]#今日已处理")
                    return@forEach
                }
                when (candidate.item.safety) {
                    ExchangeSafety.UNAVAILABLE -> {
                        Log.member("会员积分🎐跳过[${candidate.item.displayName()}]#${candidate.item.safetyReason}")
                    }
                    ExchangeSafety.LOG_ONLY -> {
                        Log.member("会员积分🎐已勾选[${candidate.item.displayName()}]#仅提醒，不自动兑换")
                        memberPointExchangeBenefitToday(candidate.item.id)
                    }
                    ExchangeSafety.AUTO -> {
                        if (exchangeMemberPointBenefit(candidate)) {
                            memberPointExchangeBenefitToday(candidate.item.id)
                        }
                    }
                }
            }
            memberBenefitMap.save(userId)
            if (candidateMap.isEmpty()) {
                Log.member("会员积分🎐未获取到可兑换列表")
            } else {
                Log.member("会员积分🎐兑换列表刷新完成#${candidateMap.size}")
            }
            remainingSelectedIds
                ?.filter { canMemberPointExchangeBenefitToday(it) }
                ?.forEach { Log.member("会员积分🎐已勾选[$it]#本次列表未返回，保留配置不删除") }
            setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_BENEFIT_REFRESH_DONE)

        } catch (t: Throwable) {
            Log.member("memberPointExchangeBenefit 运行异常: ${t.message}")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun buildMemberExchangeCandidate(
        benefit: JSONObject,
        requestSourceInfo: String = "",
        sourcePassMap: JSONObject? = null
    ): MemberExchangeCandidate? {
        val benefitId = benefit.optString("benefitId").trim()
        val itemId = benefit.optString("itemId").trim()
        val name = benefit.optString("name", benefit.optString("shortTitle", "未知权益")).trim()
        if (benefitId.isEmpty()) {
            Log.member("会员积分🎐商品[$name]没有 benefitId，跳过")
            return null
        }
        val stableId = if (itemId.isEmpty()) benefitId else "$benefitId|$itemId"
        val pricePresentation = benefit.optJSONObject("pricePresentation")
        val pointNeeded = pricePresentation?.optString("point").orEmpty()
            .ifEmpty { benefit.optJSONObject("pointPriceForDisplay")?.optString("minPoint").orEmpty() }
        val yuan = pricePresentation?.optString("yuan").orEmpty()
        val channelPrice = benefit.optString("channelPrice")
        val nextQuantity = benefit.optInt("nextQuantity", -1)
        val reserve = benefit.optInt("reserve", -1)
        val reserveForDisplay = benefit.optString("reserveForDisplay")
        val stockText = when {
            nextQuantity > 0 -> "下轮库存$nextQuantity"
            reserveForDisplay.isNotEmpty() -> "库存$reserveForDisplay"
            reserve >= 0 -> "库存$reserve"
            else -> ""
        }
        val validText = formatExchangeWindow(
            benefit.optLong("nextExchangeStartTime", 0L).takeIf { it > 0L }
                ?: benefit.optLong("exchangeStartDt", 0L),
            benefit.optLong("nextExchangeEndTime", 0L).takeIf { it > 0L }
                ?: benefit.optLong("exchangeEndDt", 0L)
        )
        val benefitMark = benefit.optString("benefitMark")
        val itemSource = benefit.optString("itemSource")
        val actionUrl = benefit.optString("actionUrl")
        val extInfo = benefit.optJSONObject("extInfo")
        val linkInfo = benefit.optJSONObject("linkInfo")
        val statusParts = mutableListOf<String>()
        val serverUsable = when {
            benefit.has("usable") -> benefit.optBoolean("usable", false)
            benefit.has("exchangeable") -> benefit.optBoolean("exchangeable", false)
            else -> true
        }
        if (!serverUsable) {
            statusParts.add("服务端不可兑")
        }
        if (nextQuantity == 0 && reserve <= 0) {
            statusParts.add("库存不足")
        }
        if (benefit.optBoolean("needPartnerMember", false) || benefit.optBoolean("needCheckPartnerMember", false)) {
            statusParts.add("需合作会员")
        }
        extInfo?.optString("labelContent")
            ?.takeIf { it.isNotBlank() }
            ?.let { statusParts.add(it) }

        val autoBenefitMark = benefitMark.equals("ONE_PARTY_VIRTUAL_ITEM", ignoreCase = true) &&
            itemSource.equals("PROMO", ignoreCase = true)
        val unsafeByMark = !autoBenefitMark
        val (baseSafety, baseReason) = ExchangeSafetyRules.classify(
            cashValues = listOf(channelPrice, yuan),
            textValues = listOf(name, benefitMark, actionUrl, itemSource, extInfo?.toString(), linkInfo?.toString()),
            defaultReason = "涉及实付或下单链路"
        )
        val safety = when {
            statusParts.any { it == "服务端不可兑" || it == "库存不足" } -> ExchangeSafety.UNAVAILABLE
            unsafeByMark -> ExchangeSafety.LOG_ONLY
            baseSafety == ExchangeSafety.LOG_ONLY -> ExchangeSafety.LOG_ONLY
            else -> ExchangeSafety.AUTO
        }
        val safetyReason = when {
            safety == ExchangeSafety.UNAVAILABLE -> statusParts.firstOrNull { it == "服务端不可兑" || it == "库存不足" }.orEmpty()
            unsafeByMark -> "非纯积分虚拟道具"
            baseReason.isNotEmpty() -> baseReason
            else -> ""
        }
        return MemberExchangeCandidate(
            item = ExchangeItem(
                id = stableId,
                name = name.ifEmpty { stableId },
                cost = ExchangeCost(
                    pointText = pointNeeded.takeIf { it.isNotBlank() }?.let { "${it}积分" }.orEmpty(),
                    cashText = yuan.takeIf { it.isNotBlank() && it != "0" }?.let { "${it}元" }
                        ?: channelPrice.takeIf { it.isNotBlank() && it != "0" }?.let { "${it}元" }.orEmpty()
                ),
                limit = ExchangeLimit(
                    statusText = statusParts.joinToString("、"),
                    stockText = stockText,
                    validText = validText
                ),
                safety = safety,
                safetyReason = safetyReason
            ),
            benefitId = benefitId,
            itemId = itemId,
            pointNeeded = pointNeeded,
            benefitMark = benefitMark,
            itemSource = itemSource,
            requestSourceInfo = requestSourceInfo,
            sourcePassMap = sourcePassMap
        )
    }

    private fun exchangeMemberPointBenefit(candidate: MemberExchangeCandidate): Boolean {
        return try {
            val detailResp = JSONObject(
                AntMemberRpcCall.querySingleBenefitDetail(
                    benefitId = candidate.benefitId,
                    requestSourceInfo = candidate.requestSourceInfo,
                    sourcePassMap = candidate.sourcePassMap
                )
            )
            if (!ResChecker.checkRes(TAG, "会员积分权益详情查询失败:", detailResp)) {
                return false
            }
            val detailCandidate = detailResp.optJSONObject("benefitDetail")
                ?.let {
                    buildMemberExchangeCandidate(
                        it,
                        requestSourceInfo = candidate.requestSourceInfo,
                        sourcePassMap = candidate.sourcePassMap
                    )
                }
                ?: candidate
            if (detailCandidate.item.safety != ExchangeSafety.AUTO) {
                Log.member("会员积分🎐详情复核跳过[${detailCandidate.item.displayName()}]#${detailCandidate.item.safetyReason}")
                return false
            }

            val confirmResp = JSONObject(
                AntMemberRpcCall.queryPromoBenefitOrderConfirmInfo(
                    benefitId = detailCandidate.benefitId,
                    requestSourceInfo = detailCandidate.requestSourceInfo,
                    sourcePassMap = detailCandidate.sourcePassMap
                )
            )
            if (!ResChecker.checkRes(TAG, "会员积分兑换确认失败:", confirmResp)) {
                return false
            }
            val confirmedCandidate = confirmResp.optJSONObject("promoBenefitOrderConfirmInfo")
                ?.let {
                    buildMemberExchangeCandidate(
                        it,
                        requestSourceInfo = detailCandidate.requestSourceInfo,
                        sourcePassMap = detailCandidate.sourcePassMap
                    )
                }
                ?: detailCandidate
            if (confirmedCandidate.item.safety != ExchangeSafety.AUTO) {
                Log.member("会员积分🎐确认页复核跳过[${confirmedCandidate.item.displayName()}]#${confirmedCandidate.item.safetyReason}")
                return false
            }
            if (confirmedCandidate.itemId.isBlank()) {
                Log.member("会员积分🎐跳过[${confirmedCandidate.item.name}]#exchangeBenefit 缺少 itemId")
                return false
            }

            val exchangeResp = JSONObject(
                AntMemberRpcCall.exchangeMemberBenefit(
                    benefitId = confirmedCandidate.benefitId,
                    itemId = confirmedCandidate.itemId,
                    requestSourceInfo = confirmedCandidate.requestSourceInfo,
                    sourcePassMap = confirmedCandidate.sourcePassMap
                )
            )
            if (!ResChecker.checkRes(TAG, "会员积分兑换失败:", exchangeResp)) {
                Log.member("会员积分🎐兑换失败[${confirmedCandidate.item.name}]#$exchangeResp")
                return false
            }
            val orderId = exchangeResp.optString("orderId")
            Log.member("会员积分🎐兑换[${confirmedCandidate.item.name}]#消耗${confirmedCandidate.pointNeeded}积分")
            if (orderId.isNotBlank()) {
                runCatching {
                    val orderResp = JSONObject(
                        AntMemberRpcCall.querySingleExchangeOrderDetail(
                            benefitId = confirmedCandidate.benefitId,
                            bizType = confirmedCandidate.itemSource.ifBlank { "PROMO" },
                            outBizNo = orderId,
                            sourcePassMap = confirmedCandidate.sourcePassMap
                        )
                    )
                    if (ResChecker.checkRes(TAG, "会员积分兑换结果查询失败:", orderResp)) {
                        val detail = orderResp.optJSONObject("exchangeOrderDetailConfigInfo")
                        val status = detail?.optString("orderStatus").orEmpty().ifEmpty {
                            detail?.optString("status").orEmpty()
                        }
                        Log.member("会员积分🎐兑换结果[${confirmedCandidate.item.name}]#${status.ifBlank { "已提交" }}")
                    }
                }.onFailure {
                    Log.printStackTrace(TAG, "exchangeMemberPointBenefit.queryOrderDetail err:", it)
                }
            }
            true
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "exchangeMemberPointBenefit err:", t)
            false
        }
    }

    private fun formatExchangeWindow(startMillis: Long, endMillis: Long): String {
        if (startMillis <= 0L && endMillis <= 0L) {
            return ""
        }
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val startText = startMillis.takeIf { it > 0L }?.let { formatter.format(Date(it)) }.orEmpty()
        val endText = endMillis.takeIf { it > 0L }?.let { formatter.format(Date(it)) }.orEmpty()
        return when {
            startText.isNotEmpty() && endText.isNotEmpty() -> "${startText}至${endText}"
            startText.isNotEmpty() -> "${startText}起"
            else -> "${endText}止"
        }
    }

    /**
     * 会员签到
     */
    /**
     * 会员签到
     */
    internal suspend fun doMemberSign(): Unit = CoroutineUtils.run {
        var signDoneToday = hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_SIGN_DONE)
        try {
            val uid = UserMap.currentUid
            if (!signDoneToday) {
                if (!canMemberSignInToday(uid)) {
                    signDoneToday = true
                } else {
                    val s = AntMemberRpcCall.queryMemberSigninCalendar()
                    val jo = JSONObject(s)
                    if (ResChecker.checkRes(TAG, "会员签到失败:", jo)) {
                        val currentSigned = jo.optBoolean("currentSigninStatus") || jo.optBoolean("autoSignInSuccess")
                        if (currentSigned) {
                            val signPoint = jo.optString("signinPoint", "0")
                            val signDays = jo.optString("signinSumDay", "-")
                            val signStatus = if (jo.optBoolean("autoSignInSuccess")) "签到成功" else "已签到"
                            Log.member("会员签到📅[${signPoint}积分]#$signStatus${signDays}天")
                            memberSignInToday(uid)
                            signDoneToday = true
                        } else {
                            Log.member("会员签到📅[今日未自动签到]#$s")
                        }
                    } else {
                        val resultDesc = jo.optString("resultDesc", "")
                        if (resultDesc.contains("已签到") || resultDesc.contains("成功")) {
                            memberSignInToday(uid)
                            signDoneToday = true
                        }
                        Log.member("会员签到📅[$resultDesc]")
                        Log.member(s)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "doMemberSign err:", t)
        } finally {
            if (signDoneToday) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_SIGN_DONE)
            }
        }
    }

    internal suspend fun doAllMemberAvailableTaskCompat(): Unit = CoroutineUtils.run {
        try {
            val floatingBallState = processMemberFloatingBallTaskCompat()
            var processedAnyTask = floatingBallState == MemberFloatingBallTaskProcessState.PROCESSED
            if (ApplicationHookConstants.isOffline()) {
                Log.member("会员任务[浮球]#检测到离线模式，本轮中断")
                return@run
            }

            when (floatingBallState) {
                MemberFloatingBallTaskProcessState.PROCESSED -> Unit

                MemberFloatingBallTaskProcessState.RETRY_LATER -> {
                    Log.member("会员任务[浮球]#存在进行中任务，本轮结束，后续轮次继续查询")
                    return@run
                }

                MemberFloatingBallTaskProcessState.UNKNOWN -> {
                    if (!hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)) {
                        Log.member("会员任务[浮球]#当前链路状态未确认，本轮结束，后续轮次继续查询")
                    }
                    return@run
                }

                MemberFloatingBallTaskProcessState.NO_TASK -> {
                    Unit
                }
            }

            when (processCurrentMemberTaskListCompat()) {
                CurrentMemberTaskListProcessState.COMPLETED -> {
                    markMemberTaskDoneToday("会员任务#任务列表已处理完成，今日停止继续刷新")
                }

                CurrentMemberTaskListProcessState.HANDLED_PENDING_CONFIRM -> {
                    markMemberTaskDoneToday("会员任务#今日已尝试可闭环任务，等待后续调度/明日刷新确认")
                }

                CurrentMemberTaskListProcessState.NO_ACTIONABLE_TASK -> {
                    markMemberTaskDoneToday("会员任务#当前列表无可执行白名单任务，今日停止继续刷新")
                }

                CurrentMemberTaskListProcessState.NO_TASK -> {
                    if (!processedAnyTask) {
                        markMemberTaskDoneToday("会员任务#未发现可执行任务，今日停止继续刷新")
                    } else {
                        markMemberTaskDoneToday("会员任务#浮球任务已处理且未发现更多任务，今日停止继续刷新")
                    }
                }

                CurrentMemberTaskListProcessState.RETRY_LATER -> {
                    Log.member("会员任务#存在白名单任务但未确认完成，本轮结束，后续轮次继续查询")
                }

                CurrentMemberTaskListProcessState.UNKNOWN -> Unit
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "doAllMemberAvailableTaskCompat err:", t)
        }
    }

    private suspend fun processCurrentMemberTaskListCompat(): CurrentMemberTaskListProcessState = CoroutineUtils.run {
        try {
            val adapter = CurrentMemberTaskFlowAdapter()
            val runResult = TaskFlowEngine(adapter, roundSleepMs = 500L).run()
            return@run when {
                adapter.queryFailed -> CurrentMemberTaskListProcessState.UNKNOWN
                adapter.hasRetryableFailure || adapter.hasBlockingFailure || runResult.stopped ->
                    CurrentMemberTaskListProcessState.RETRY_LATER

                runResult.completed -> CurrentMemberTaskListProcessState.COMPLETED
                adapter.supportedTaskCount == 0 && adapter.hasTaskSnapshot ->
                    CurrentMemberTaskListProcessState.NO_ACTIONABLE_TASK

                adapter.supportedTaskCount == 0 -> CurrentMemberTaskListProcessState.NO_TASK
                runResult.progressChanged || runResult.noProgressSuccess || adapter.hasPendingConfirmation ->
                    CurrentMemberTaskListProcessState.HANDLED_PENDING_CONFIRM

                else -> CurrentMemberTaskListProcessState.RETRY_LATER
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "processCurrentMemberTaskListCompat err:", t)
            return@run CurrentMemberTaskListProcessState.UNKNOWN
        }
    }

    private inner class CurrentMemberTaskFlowAdapter : TaskFlowAdapter {
        override val moduleName: String = memberTaskBlacklistModule
        override val flowName: String = "会员任务"

        var queryFailed: Boolean = false
            private set
        var hasTaskSnapshot: Boolean = false
            private set
        var supportedTaskCount: Int = 0
            private set
        var hasBlockingFailure: Boolean = false
            private set
        var hasRetryableFailure: Boolean = false
            private set
        var hasPendingConfirmation: Boolean = false
            private set

        private val appliedMemberTasks = LinkedHashMap<String, CurrentMemberTask>()
        private val completedMemberTaskKeys = LinkedHashSet<String>()
        private val pendingConfirmationTaskKeys = LinkedHashSet<String>()
        private val loggedSkipKeys = LinkedHashSet<String>()

        override fun query(): JSONObject {
            val rawResponses = JSONArray()
            var querySuccess = true
            var stopReason = ""
            var stopScene = ""
            var stopObject: JSONObject? = null
            var rawResponse = ""

            fun appendTaskResponse(response: String, scene: String) {
                if (!querySuccess) {
                    return
                }
                rawResponse = response
                val taskObject = JSONObject(response)
                val sceneStopReason = resolveMemberTaskQueryStopReason(taskObject)
                if (sceneStopReason != null) {
                    querySuccess = false
                    stopReason = sceneStopReason
                    stopScene = scene
                    stopObject = taskObject
                    return
                }
                if (!ResChecker.checkRes(TAG, taskObject)) {
                    querySuccess = false
                    stopScene = scene
                    stopObject = taskObject
                    return
                }
                rawResponses.put(taskObject)
            }

            return try {
                appendTaskResponse(AntMemberRpcCall.queryMemberTaskList(), "signInAd")
                appendTaskResponse(AntMemberRpcCall.queryMemberTaskProcessList(), "memberPoint")
                JSONObject()
                    .put("_taskFlowQuerySuccess", querySuccess)
                    .put("_taskFlowStopReason", stopReason)
                    .put("_taskFlowStopScene", stopScene)
                    .put("_taskFlowStopObject", stopObject ?: JSONObject())
                    .put("_rawResponse", rawResponse)
                    .put("_rawTaskResponses", rawResponses)
                    .put("_hasTaskSnapshot", hasMemberTaskSnapshot(rawResponses))
            } catch (t: Throwable) {
                JSONObject()
                    .put("_taskFlowQuerySuccess", false)
                    .put("_taskFlowStopScene", "query")
                    .put("resultDesc", "查询异常:${t.message}")
            }
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return response.optBoolean("_taskFlowQuerySuccess", false)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val rawResponses = response.optJSONArray("_rawTaskResponses") ?: JSONArray()
            hasTaskSnapshot = hasTaskSnapshot || response.optBoolean("_hasTaskSnapshot", false)
            val tasks = mutableListOf<CurrentMemberTask>()
            for (i in 0 until rawResponses.length()) {
                val taskObject = rawResponses.optJSONObject(i) ?: continue
                tasks.addAll(buildCurrentMemberTasks(taskObject, skipBlacklisted = false))
            }
            val dedupedTasks = dedupeCurrentMemberTasks(tasks)
            supportedTaskCount = max(supportedTaskCount, dedupedTasks.size)
            return dedupedTasks.map(::currentMemberTaskToFlowItem)
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            if (item.id.isBlank()) {
                return TaskFlowPhase.UNKNOWN
            }
            val task = currentMemberTaskFromFlowItem(item)
            val taskKey = buildCurrentMemberTaskFlowKey(task)
            if (taskKey in completedMemberTaskKeys) {
                return TaskFlowPhase.TERMINAL
            }
            if (task.adBizId.isNotBlank()) {
                return TaskFlowPhase.READY_TO_COMPLETE
            }
            return if (task.taskProcessId.isBlank() && taskKey !in appliedMemberTasks) {
                TaskFlowPhase.SIGNUP_REQUIRED
            } else {
                TaskFlowPhase.READY_TO_COMPLETE
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            if (Thread.currentThread().isInterrupted) {
                return true
            }
            return buildCurrentMemberTaskFlowKey(currentMemberTaskFromFlowItem(item)) in completedMemberTaskKeys
        }

        override fun isBlacklisted(item: TaskFlowItem): Boolean {
            val blacklisted = super<TaskFlowAdapter>.isBlacklisted(item)
            if (blacklisted) {
                logMemberTaskSkipOnce(item, "黑名单任务，跳过")
            }
            return blacklisted
        }

        override fun signup(item: TaskFlowItem): TaskFlowActionResult {
            val task = currentMemberTaskFromFlowItem(item)
            val applyResponse = AntMemberRpcCall.applyMemberTask(task.taskConfigId)
            val applyObject = JSONObject(applyResponse)
            if (isSkippableMemberTaskRejection(applyObject)) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                    code = extractMemberDomainRpcCode(applyObject).ifBlank { "NOT_PROMO_RULE_QUALIFIED" },
                    message = extractMemberDomainRpcMessage(applyObject).ifBlank { "不满足任务的营销规则条件" },
                    rpc = "AntMemberRpcCall.applyMemberTask",
                    raw = applyResponse,
                    detail = currentMemberTaskActionDetail(task, "apply")
                )
            }
            if (!ResChecker.checkRes(TAG, "领取会员任务失败:", applyObject)) {
                return memberDomainTaskFailureResult(
                    item = item,
                    responseObject = applyObject,
                    rawResponse = applyResponse,
                    rpc = "AntMemberRpcCall.applyMemberTask",
                    detail = currentMemberTaskActionDetail(task, "apply")
                )
            }
            val appliedTask = buildCurrentMemberTaskFromApplyResponse(task, applyObject)
                ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "TASK_PROCESS_MISSING",
                    message = "领取成功但缺少processId或可闭环目标业务字段",
                    rpc = "AntMemberRpcCall.applyMemberTask",
                    raw = applyResponse,
                    detail = currentMemberTaskActionDetail(task, "apply")
                )
            appliedMemberTasks[buildCurrentMemberTaskFlowKey(task)] = appliedTask
            Log.member("会员任务[${appliedTask.title}]#领取任务")
            return TaskFlowActionResult.success()
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val task = currentMemberTaskFromFlowItem(item)
            val taskKey = buildCurrentMemberTaskFlowKey(task)
            val executableTask = if (task.taskProcessId.isBlank()) {
                appliedMemberTasks[taskKey] ?: task
            } else {
                task
            }
            return completeCurrentMemberTask(executableTask, item)
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            val task = currentMemberTaskFromFlowItem(item)
            return "${action.logName}:${buildCurrentMemberTaskFlowKey(task)}:${item.status}:${item.progress}"
        }

        override fun estimateRoundLimit(items: List<TaskFlowItem>): Int {
            val visibleTaskCount = items.count { !isBlacklisted(it) && !shouldSkip(it) }
            return max(1, visibleTaskCount * MEMBER_TASK_REPEAT_LIMIT * 2 + visibleTaskCount)
        }

        override fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
            val task = currentMemberTaskFromFlowItem(item)
            val taskKey = buildCurrentMemberTaskFlowKey(task)
            if (action == TaskFlowAction.COMPLETE && result.code == "CONFIRMED") {
                completedMemberTaskKeys.add(taskKey)
                pendingConfirmationTaskKeys.remove(taskKey)
            } else if (action == TaskFlowAction.COMPLETE &&
                (result.code == "VERIFY_PARTIAL" || result.code == "VERIFY_PENDING")
            ) {
                hasPendingConfirmation = true
                pendingConfirmationTaskKeys.add(taskKey)
            }
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision
        ) {
            when (decision) {
                TaskFlowDecision.RETRY_LATER -> hasRetryableFailure = true
                TaskFlowDecision.LOG_ONLY -> hasBlockingFailure = true
                TaskFlowDecision.MARK_HANDLED -> completedMemberTaskKeys.add(
                    buildCurrentMemberTaskFlowKey(currentMemberTaskFromFlowItem(item))
                )
                TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN,
                TaskFlowDecision.BLACKLIST -> Unit
            }
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            logInfo("会员任务[任务列表已处理完成：${snapshot.completedTasks}/${snapshot.totalTasks}]")
        }

        override fun onQueryFailed(response: JSONObject) {
            queryFailed = true
            val stopReason = response.optString("_taskFlowStopReason")
            val stopScene = response.optString("_taskFlowStopScene").ifBlank { "unknown" }
            val stopObject = response.optJSONObject("_taskFlowStopObject") ?: response
            if (stopReason.isNotBlank()) {
                if (!isOfflineMemberTaskStopReason(stopReason)) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)
                }
                Log.member("会员任务[$stopScene]#${buildMemberTaskQueryStopMessage(stopReason, stopObject)}")
                return
            }
            Log.error(
                "$TAG.processCurrentMemberTaskListCompat",
                "会员任务[$stopScene]响应失败: " +
                    stopObject.optString("resultDesc").ifBlank {
                        stopObject.optString("errorMsg").ifBlank { response.optString("_rawResponse", response.toString()) }
                    }
            )
        }

        override fun onUnknownPhase(item: TaskFlowItem, phase: TaskFlowPhase) {
            hasBlockingFailure = true
            Log.error(
                TAG,
                "会员任务[${item.title}]#未知状态 module=$moduleName taskId=${item.id.ifBlank { "UNKNOWN" }} " +
                    "status=${item.status.ifBlank { "UNKNOWN" }} progress=${item.progress.ifBlank { "UNKNOWN" }} raw=${item.raw}"
            )
        }

        override fun onRoundLimit(roundLimit: Int) {
            hasRetryableFailure = true
            Log.error(TAG, "会员任务[达到动态轮次上限$roundLimit，停止以避免重复循环]")
        }

        override fun logInfo(message: String) {
            Log.member(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun hasMemberTaskSnapshot(rawResponses: JSONArray): Boolean {
            for (i in 0 until rawResponses.length()) {
                val taskObject = rawResponses.optJSONObject(i) ?: continue
                if (hasCurrentMemberTaskSnapshot(taskObject)) {
                    return true
                }
            }
            return false
        }

        private fun logMemberTaskSkipOnce(item: TaskFlowItem, reason: String) {
            val key = "$reason|${item.id}|${item.title}"
            if (loggedSkipKeys.add(key)) {
                Log.member("会员任务[${item.title}]#$reason")
            }
        }
    }

    private fun buildMemberTaskProcessAwards(jsonObject: JSONObject): List<MemberTaskProcessAward> {
        val taskProcessList = jsonObject.optJSONArray("availableTaskProcessList") ?: return emptyList()
        val awardList = mutableListOf<MemberTaskProcessAward>()
        val dedupKeys = LinkedHashSet<String>()
        for (i in 0 until taskProcessList.length()) {
            val taskProcess = taskProcessList.optJSONObject(i) ?: continue
            val taskProcessId = taskProcess.optString("taskProcessId")
            if (taskProcessId.isEmpty()) {
                continue
            }
            val taskConfig = taskProcess.optJSONObject("taskConfig")
            val taskTitle = taskConfig?.optString("title").orEmpty().ifEmpty {
                taskProcess.optString("title", "会员任务")
            }
            val stageProcessList = taskProcess.optJSONArray("stageProcessList") ?: continue
            for (stageIndexInList in 0 until stageProcessList.length()) {
                val stageProcess = stageProcessList.optJSONObject(stageIndexInList) ?: continue
                val stageStatus = stageProcess.optString("stageStatus")
                val awardRelatedOutBizNo = stageProcess.optString("awardRelatedOutBizNo")
                if (!stageStatus.equals("COMPLETE", true) || awardRelatedOutBizNo.isEmpty()) {
                    continue
                }
                val stageIndex = stageProcess.optInt("stageIndex", stageIndexInList + 1)
                val dedupKey = "$taskProcessId#$awardRelatedOutBizNo"
                if (!dedupKeys.add(dedupKey)) {
                    continue
                }
                awardList.add(
                    MemberTaskProcessAward(
                        taskProcessId = taskProcessId,
                        awardRelatedOutBizNo = awardRelatedOutBizNo,
                        title = taskTitle,
                        awardPoint = stageProcess.optInt("awardPoint", 0),
                        stageIndex = stageIndex
                    )
                )
            }
        }
        return awardList
    }

    internal suspend fun collectMemberTaskProcessAwards(): Int = CoroutineUtils.run {
        try {
            val response = AntMemberRpcCall.queryMemberTaskProcessList()
            val taskListObject = JSONObject(response)
            if (!ResChecker.checkRes(TAG, "查询会员阶段奖励失败:", taskListObject)) {
                Log.member("会员任务[阶段奖励]#查询失败:" + taskListObject.optString("resultDesc", response)
                )
                return@run 0
            }

            val awardList = buildMemberTaskProcessAwards(taskListObject)
            var claimedCount = 0
            for (award in awardList) {
                val awardResponse = AntMemberRpcCall.awardMemberTaskProcess(
                    award.awardRelatedOutBizNo,
                    award.taskProcessId
                )
                val awardObject = JSONObject(awardResponse)
                if (!ResChecker.checkRes(TAG, "领取会员阶段奖励失败:", awardObject)) {
                    Log.member("会员任务[${award.title}]#阶段奖励领取失败:" + awardObject.optString("resultDesc", awardResponse)
                    )
                    continue
                }
                val stageSuffix = if (award.stageIndex > 0) "-阶段${award.stageIndex}" else ""
                if (award.awardPoint > 0) {
                    Log.member("会员任务[${award.title}$stageSuffix]#获得积分${award.awardPoint}")
                } else {
                    Log.member("会员任务[${award.title}$stageSuffix]#领取阶段奖励")
                }
                claimedCount++
                delay(300)
            }
            return@run claimedCount
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "collectMemberTaskProcessAwards err:", t)
            return@run 0
        }
    }

    private fun resolveMemberTaskQueryStopReason(jsonObject: JSONObject): String? {
        if (ApplicationHookConstants.isOffline()) {
            return "OFFLINE_MODE"
        }
        val code = RpcOfflineRisk.extractCode(jsonObject)
        val desc = RpcOfflineRisk.extractMessage(jsonObject)
        if (RpcOfflineRisk.isOfflineRisk(code, desc)) {
            RpcOfflineRisk.enterOfflineIfNeeded("AntMember.memberTask", code, desc)
            return "AUTH_LIKE"
        }
        if (code == "I07" || desc.contains("离线模式")) {
            return "OFFLINE_MODE"
        }
        return null
    }

    private fun isOfflineMemberTaskStopReason(stopReason: String): Boolean {
        return stopReason == "OFFLINE_MODE" || ApplicationHookConstants.isOffline()
    }

    private fun buildMemberTaskQueryStopMessage(stopReason: String, jsonObject: JSONObject): String {
        val detail = sequenceOf(
            jsonObject.optString("resultDesc"),
            jsonObject.optString("memo"),
            jsonObject.optString("desc"),
            jsonObject.optString("errorMessage"),
            jsonObject.optString("errorMsg")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        return when (stopReason) {
            "AUTH_LIKE" -> "检测到验证/访问异常($detail)，停止今日继续刷新"
            "OFFLINE_MODE" -> "检测到离线模式($detail)，停止今日继续刷新"
            else -> "检测到异常($detail)，停止今日继续刷新"
        }
    }

    private fun buildCurrentMemberTasks(
        jsonObject: JSONObject,
        skipBlacklisted: Boolean = true
    ): List<CurrentMemberTask> {
        val taskProcessObjects = collectCurrentMemberTaskProcessObjects(jsonObject)
        if (taskProcessObjects.isEmpty()) {
            return emptyList()
        }
        val taskList = mutableListOf<CurrentMemberTask>()
        val dedupKeys = LinkedHashSet<String>()
        for (taskProcessObject in taskProcessObjects) {
            if (isMemberTaskProcessFinished(taskProcessObject)) {
                continue
            }
            val simpleTaskConfig = resolveCurrentMemberTaskConfigObject(taskProcessObject) ?: continue
            val title = simpleTaskConfig.optString("title").ifEmpty {
                simpleTaskConfig.optString("name").ifEmpty { "会员任务" }
            }
            val unsupportedAdTaskReason = resolveUnsupportedMemberAdTaskReason(taskProcessObject, simpleTaskConfig)
            if (unsupportedAdTaskReason != null) {
                logSkippedMemberAdTask(title, unsupportedAdTaskReason)
                continue
            }
            val adBizId = resolveMemberAdTaskBizId(taskProcessObject, simpleTaskConfig)
            val taskConfigId = resolveCurrentMemberTaskConfigId(taskProcessObject) ?: continue
            if (skipBlacklisted && isMemberTaskInBlacklist(taskConfigId, title)) {
                Log.member("会员任务[$title]#黑名单任务，跳过")
                continue
            }
            if (!isWhitelistedMemberTaskConfigId(taskConfigId, adBizId.isNotEmpty())) {
                logSkippedUnsupportedMemberTask(title, taskConfigId, taskProcessObject)
                continue
            }
            val targetBusiness = resolveSupportedMemberTaskTargetBusiness(
                taskProcessObject.optJSONArray("targetBusiness") ?: simpleTaskConfig.optJSONArray("targetBusiness")
            )
            if (targetBusiness.raw.isEmpty() && adBizId.isEmpty()) {
                Log.member("会员任务[$title]#缺少可闭环目标业务字段，跳过")
                continue
            }
            val taskProcessId = taskProcessObject.optString("processId").ifEmpty {
                taskProcessObject.optString("taskProcessId")
            }
            val dedupKey = when {
                taskProcessId.isNotEmpty() -> taskProcessId
                adBizId.isNotEmpty() -> "$taskConfigId#$adBizId"
                else -> taskConfigId
            }
            if (!dedupKeys.add(dedupKey)) {
                continue
            }
            taskList.add(
                CurrentMemberTask(
                    taskConfigId = taskConfigId,
                    taskProcessId = taskProcessId,
                    title = title.ifEmpty { "任务$taskConfigId" },
                    awardPoint = extractMemberTaskAwardPoint(simpleTaskConfig),
                    targetBusiness = targetBusiness.raw,
                    targetBusinessType = targetBusiness.type,
                    simpleTaskConfig = simpleTaskConfig,
                    adBizId = adBizId,
                    status = taskProcessObject.optString("status").ifEmpty {
                        taskProcessObject.optString("subStatus")
                    },
                    current = extractCurrentMemberTaskCurrent(taskProcessObject),
                    limit = extractCurrentMemberTaskLimit(taskProcessObject)
                )
            )
        }
        return taskList
    }

    private fun currentMemberTaskToFlowItem(task: CurrentMemberTask): TaskFlowItem {
        val raw = JSONObject()
            .put("taskConfigId", task.taskConfigId)
            .put("taskProcessId", task.taskProcessId)
            .put("title", task.title)
            .put("awardPoint", task.awardPoint)
            .put("targetBusiness", task.targetBusiness)
            .put("targetBusinessType", task.targetBusinessType.name)
            .put("adBizId", task.adBizId)
            .put("simpleTaskConfig", task.simpleTaskConfig)
        return TaskFlowItem(
            id = task.taskConfigId,
            title = task.title,
            status = task.status,
            type = when {
                task.adBizId.isNotBlank() -> "AD_TASK"
                task.targetBusinessType == MemberTaskTargetBusinessType.CALL_APP -> "CALL_APP"
                else -> "BROWSE"
            },
            actionType = when {
                task.adBizId.isNotBlank() -> "taskFinish"
                task.targetBusinessType == MemberTaskTargetBusinessType.CALL_APP -> "verifyCallApp"
                else -> "executeTask"
            },
            blacklistKeys = listOf(task.taskConfigId, task.title).filter { it.isNotBlank() },
            raw = raw,
            progress = buildCurrentMemberTaskProgress(task.current, task.limit),
            current = task.current,
            limit = task.limit
        )
    }

    private fun currentMemberTaskFromFlowItem(item: TaskFlowItem): CurrentMemberTask {
        val raw = item.raw ?: JSONObject()
        val targetBusiness = raw.optString("targetBusiness")
        val targetBusinessType = raw.optString("targetBusinessType").ifBlank {
            resolveSupportedMemberTaskTargetBusinessType(targetBusiness).name
        }
        return CurrentMemberTask(
            taskConfigId = raw.optString("taskConfigId").ifBlank { item.id },
            taskProcessId = raw.optString("taskProcessId"),
            title = raw.optString("title").ifBlank { item.title },
            awardPoint = raw.optString("awardPoint"),
            targetBusiness = targetBusiness,
            targetBusinessType = resolveMemberTaskTargetBusinessType(targetBusinessType),
            simpleTaskConfig = raw.optJSONObject("simpleTaskConfig") ?: JSONObject(),
            adBizId = raw.optString("adBizId"),
            status = item.status,
            current = item.current,
            limit = item.limit
        )
    }

    private fun buildCurrentMemberTaskFlowKey(task: CurrentMemberTask): String {
        return when {
            task.taskProcessId.isNotBlank() -> task.taskProcessId
            task.adBizId.isNotBlank() -> "${task.taskConfigId}#${task.adBizId}"
            else -> task.taskConfigId.ifBlank { task.title }
        }
    }

    private fun buildCurrentMemberTaskProgress(current: Int?, limit: Int?): String {
        return if (current != null && limit != null && limit > 0) {
            "$current/$limit"
        } else {
            ""
        }
    }

    private fun extractCurrentMemberTaskCurrent(taskProcessObject: JSONObject): Int? {
        val directCurrent = taskProcessObject.optInt("currentCount", Int.MIN_VALUE)
        if (directCurrent != Int.MIN_VALUE) {
            return directCurrent
        }
        return taskProcessObject.optJSONObject("extInfo")
            ?.optString("PERIOD_CURRENT_COUNT")
            ?.toIntOrNull()
    }

    private fun extractCurrentMemberTaskLimit(taskProcessObject: JSONObject): Int? {
        val directLimit = taskProcessObject.optInt("targetCount", Int.MIN_VALUE)
        if (directLimit != Int.MIN_VALUE) {
            return directLimit
        }
        return taskProcessObject.optJSONObject("extInfo")
            ?.optString("PERIOD_TARGET_COUNT")
            ?.toIntOrNull()
    }

    private fun collectCurrentMemberTaskProcessObjects(responseObject: JSONObject): List<JSONObject> {
        val taskProcessObjects = mutableListOf<JSONObject>()
        appendCurrentMemberTaskProcessObjects(taskProcessObjects, responseObject.optJSONArray("availableTaskProcessList"))
        appendCurrentMemberTaskProcessObjects(taskProcessObjects, responseObject.optJSONArray("availableTaskConfigList"))

        val resultData = responseObject.optJSONObject("resultData") ?: responseObject
        appendCurrentMemberTaskProcessObjects(taskProcessObjects, resultData.optJSONArray("taskProcessVOList"))
        appendCurrentMemberTaskProcessObjects(taskProcessObjects, resultData.optJSONArray("taskHistoryVOList"))
        appendCurrentMemberTaskProcessObjects(taskProcessObjects, resultData.optJSONArray("pureTaskList"))
        appendCurrentMemberTaskProcessObjects(taskProcessObjects, resultData.optJSONArray("adTaskList"))
        appendCurrentMemberTaskProcessObjects(taskProcessObjects, resultData.optJSONArray("alipayGrowthTaskList"))
        appendCurrentMemberCategoryTaskProcessObjects(taskProcessObjects, resultData.optJSONArray("categoryTaskList"))
        appendCurrentMemberCategoryTaskProcessObjects(taskProcessObjects, resultData.optJSONArray("categoryTaskVOList"))
        return taskProcessObjects
    }

    private fun appendCurrentMemberTaskProcessObjects(target: MutableList<JSONObject>, taskArray: JSONArray?) {
        if (taskArray == null) {
            return
        }
        for (i in 0 until taskArray.length()) {
            taskArray.optJSONObject(i)?.let(target::add)
        }
    }

    private fun appendCurrentMemberCategoryTaskProcessObjects(target: MutableList<JSONObject>, categoryArray: JSONArray?) {
        if (categoryArray == null) {
            return
        }
        for (i in 0 until categoryArray.length()) {
            val categoryObject = categoryArray.optJSONObject(i) ?: continue
            appendCurrentMemberTaskProcessObjects(target, categoryObject.optJSONArray("taskProcessVOList"))
        }
    }

    private fun resolveCurrentMemberTaskConfigObject(taskProcessObject: JSONObject): JSONObject? {
        return taskProcessObject.optJSONObject("simpleTaskConfig")
            ?: taskProcessObject.optJSONObject("taskConfigInfo")
            ?: taskProcessObject.optJSONObject("taskConfig")
    }

    private fun hasCurrentMemberTaskSnapshot(jsonObject: JSONObject): Boolean {
        if (jsonObject.has("availableTaskProcessList") || jsonObject.has("availableTaskConfigList")) {
            return true
        }
        val resultData = jsonObject.optJSONObject("resultData") ?: return false
        return resultData.has("taskProcessVOList") ||
            resultData.has("taskHistoryVOList") ||
            resultData.has("categoryTaskList") ||
            resultData.has("categoryTaskVOList") ||
            resultData.has("pureTaskList") ||
            resultData.has("adTaskList") ||
            resultData.optString("playInstanceId").isNotBlank()
    }

    private fun dedupeCurrentMemberTasks(tasks: List<CurrentMemberTask>): List<CurrentMemberTask> {
        val dedupKeys = LinkedHashSet<String>()
        return tasks.filter { task ->
            val dedupKey = when {
                task.taskProcessId.isNotEmpty() -> task.taskProcessId
                task.adBizId.isNotEmpty() -> "${task.taskConfigId}#${task.adBizId}"
                else -> task.taskConfigId
            }
            dedupKeys.add(dedupKey)
        }
    }

    private fun markMemberTaskDoneToday(message: String) {
        setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_EMPTY_TODAY)
        Log.member(message)
    }

    private suspend fun processMemberFloatingBallTaskCompat(): MemberFloatingBallTaskProcessState = CoroutineUtils.run {
        try {
            val floatingBallResponse = AntMemberRpcCall.querySignFloatingBall()
            val floatingBallObject = JSONObject(floatingBallResponse)
            val stopReason = resolveMemberTaskQueryStopReason(floatingBallObject)
            if (stopReason != null) {
                if (!isOfflineMemberTaskStopReason(stopReason)) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)
                }
                Log.member("会员任务[浮球]#${buildMemberTaskQueryStopMessage(stopReason, floatingBallObject)}"
                )
                return@run MemberFloatingBallTaskProcessState.UNKNOWN
            }
            if (!ResChecker.checkRes(TAG, floatingBallObject)) {
                Log.error(
                    "$TAG.processMemberFloatingBallTaskCompat",
                    "会员浮球查询失败: " + floatingBallObject.optString("resultDesc", floatingBallResponse)
                )
                return@run MemberFloatingBallTaskProcessState.UNKNOWN
            }
            if (floatingBallObject.optBoolean("allTaskCompleted")) {
                Log.member("会员任务[浮球]#今日浮球任务已全部完成")
                return@run MemberFloatingBallTaskProcessState.NO_TASK
            }
            val taskRef = buildMemberFloatingBallTaskRef(floatingBallObject)
                ?: return@run MemberFloatingBallTaskProcessState.NO_TASK
            if (isMemberTaskProcessFinishedStatus(taskRef.taskStatus)) {
                Log.member("会员任务[浮球]#当前浮球任务已完成，停止本轮继续刷新")
                return@run MemberFloatingBallTaskProcessState.NO_TASK
            }
            if (!taskRef.taskType.equals("MULTIPLE_TIMER_TASK", true)) {
                Log.member("会员任务[浮球]#未适配任务类型${taskRef.taskType}，停止本轮继续刷新")
                return@run MemberFloatingBallTaskProcessState.UNKNOWN
            }

            val remainingMillis = when {
                taskRef.endDt > 0L -> taskRef.endDt - System.currentTimeMillis()
                taskRef.executeTimeSeconds > 0L -> taskRef.executeTimeSeconds * 1000L
                else -> 0L
            }
            if (remainingMillis > 20_000L) {
                val remainingSeconds = ((remainingMillis + 999L) / 1000L).coerceAtLeast(1L)
                Log.member("会员任务[浮球]#倒计时任务进行中，剩余${remainingSeconds}秒，停止本轮继续刷新"
                )
                return@run MemberFloatingBallTaskProcessState.RETRY_LATER
            }
            val triggerResponse = AntMemberRpcCall.triggerSignFloatingBall(taskRef.bizNo, taskRef.taskType)
            val triggerObject = JSONObject(triggerResponse)
            val triggerStopReason = resolveMemberTaskQueryStopReason(triggerObject)
            if (triggerStopReason != null) {
                if (!isOfflineMemberTaskStopReason(triggerStopReason)) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)
                }
                Log.member("会员任务[浮球]#${buildMemberTaskQueryStopMessage(triggerStopReason, triggerObject)}"
                )
                return@run MemberFloatingBallTaskProcessState.UNKNOWN
            }
            if (isMemberFloatingBallTaskNotEnded(triggerObject)) {
                Log.member("会员任务[浮球]#倒计时任务未结束，本轮结束，后续轮次继续查询")
                return@run MemberFloatingBallTaskProcessState.RETRY_LATER
            }
            if (!ResChecker.checkRes(TAG, triggerObject)) {
                Log.error(
                    "$TAG.processMemberFloatingBallTaskCompat",
                    "会员浮球触发失败: " + triggerObject.optString("resultDesc", triggerResponse)
                )
                return@run MemberFloatingBallTaskProcessState.UNKNOWN
            }

            val triggerStatus = triggerObject.optJSONObject("currentTaskInfo")?.optString("taskStatus").orEmpty()
            if (!isMemberTaskProcessFinishedStatus(triggerStatus)) {
                Log.member("会员任务[浮球]#触发完成后状态未终态，停止本轮继续刷新")
                return@run MemberFloatingBallTaskProcessState.RETRY_LATER
            }

            Log.member("会员任务[浮球]#完成倒计时浮球任务")
            if (!tryProcessMemberFloatingBallAdTask(taskRef)) {
                Log.member("会员任务[浮球]#后续广告任务未返回可直接上报字段，停止本轮继续刷新")
            }
            return@run MemberFloatingBallTaskProcessState.PROCESSED
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "processMemberFloatingBallTaskCompat err:", t)
            return@run MemberFloatingBallTaskProcessState.UNKNOWN
        }
    }

    private fun isMemberFloatingBallTaskNotEnded(jsonObject: JSONObject): Boolean {
        return jsonObject.optString("resultCode") == "SIGN_FLOATING_BALL_TASK_NOT_END" ||
            jsonObject.optString("resultDesc").contains("任务未结束")
    }

    private fun buildMemberFloatingBallTaskRef(jsonObject: JSONObject): MemberFloatingBallTaskRef? {
        val currentTaskInfo = jsonObject.optJSONObject("currentTaskInfo")
        val nextTaskInfo = jsonObject.optJSONObject("nextTaskInfo")
        val activeTaskInfo = when {
            currentTaskInfo == null -> nextTaskInfo
            isMemberTaskProcessFinishedStatus(currentTaskInfo.optString("taskStatus")) &&
                nextTaskInfo != null &&
                !isMemberTaskProcessFinishedStatus(nextTaskInfo.optString("taskStatus")) -> nextTaskInfo

            else -> currentTaskInfo
        } ?: return null
        val bizNo = activeTaskInfo.optString("bizNo").ifEmpty { jsonObject.optString("bizNo") }
        val taskType = jsonObject.optString("taskType")
        if (bizNo.isBlank() || taskType.isBlank()) {
            return null
        }
        return MemberFloatingBallTaskRef(
            bizNo = bizNo,
            taskType = taskType,
            taskStatus = activeTaskInfo.optString("taskStatus"),
            endDt = activeTaskInfo.optLong("endDt", 0L),
            executeTimeSeconds = activeTaskInfo.optLong("executeTime", 0L)
        )
    }

    private suspend fun tryProcessMemberFloatingBallAdTask(taskRef: MemberFloatingBallTaskRef): Boolean = CoroutineUtils.run {
        try {
            if (TaskBlacklist.isTaskInBlacklist(memberTaskBlacklistModule, memberFloatingBallAdTaskTitle)) {
                Log.member("会员任务[浮球]#$memberFloatingBallAdTaskTitle 已在黑名单，跳过后续广告任务")
                return@run true
            }
            val adTaskResponse = AntMemberRpcCall.querySignFloatingBallAdTask(taskRef.bizNo)
            val adTaskObject = JSONObject(adTaskResponse)
            if (!ResChecker.checkRes(TAG, adTaskObject)) {
                Log.error(
                    "$TAG.tryProcessMemberFloatingBallAdTask",
                    "会员浮球广告任务查询失败: " + adTaskObject.optString("resultDesc", adTaskResponse)
                )
                return@run false
            }
            val floatingBallAdTask = buildCurrentMemberTaskFromFloatingBallAdResponse(adTaskObject)
            if (floatingBallAdTask == null) {
                val videoTaskInfo = adTaskObject.optJSONObject("videoTaskInfo")
                if (videoTaskInfo != null) {
                    Log.member("会员任务[浮球]#已识别后续广告任务，但当前响应缺少adBizId/configId，保留后续刷新"
                    )
                }
                return@run false
            }
            return@run finishMemberAdTask(
                floatingBallAdTask.taskConfigId,
                floatingBallAdTask.title,
                floatingBallAdTask.awardPoint,
                floatingBallAdTask.adBizId
            ).success
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "tryProcessMemberFloatingBallAdTask err:", t)
            return@run false
        }
    }

    private fun buildCurrentMemberTaskFromFloatingBallAdResponse(responseObject: JSONObject): CurrentMemberTask? {
        val taskConfigObject = responseObject.optJSONObject("taskInfo")
            ?: responseObject.optJSONObject("currentTaskInfo")
            ?: responseObject.optJSONObject("nextTaskInfo")
            ?: responseObject.optJSONObject("videoTaskInfo")
            ?: responseObject
        val adBizId = resolveMemberAdTaskBizId(responseObject, taskConfigObject)
            .ifEmpty { resolveMemberAdTaskBizId(taskConfigObject, taskConfigObject) }
        if (adBizId.isBlank()) {
            return null
        }
        val taskConfigId = resolveCurrentMemberTaskConfigId(taskConfigObject)
            ?: resolveFallbackMemberTaskConfigId(responseObject, taskConfigObject)
            ?: return null
        val title = sequenceOf(
            taskConfigObject.optString("title"),
            taskConfigObject.optString("name"),
            responseObject.optJSONObject("extendInfo")?.optJSONObject("taskInfo")?.optString("taskTitle")
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty().ifEmpty { "会员任务$taskConfigId" }
        val awardPoint = sequenceOf(
            taskConfigObject.optString("awardNum"),
            responseObject.optJSONObject("extendInfo")?.optJSONObject("rewardInfo")?.optString("rewardAmount")
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        return CurrentMemberTask(
            taskConfigId = taskConfigId,
            taskProcessId = "",
            title = title,
            awardPoint = awardPoint,
            targetBusiness = "",
            simpleTaskConfig = taskConfigObject,
            adBizId = adBizId
        )
    }

    private fun resolveFallbackMemberTaskConfigId(responseObject: JSONObject, taskConfigObject: JSONObject): String? {
        val directCandidate = sequenceOf(
            responseObject.optString("configId"),
            taskConfigObject.optString("configId"),
            responseObject.optString("taskConfigId"),
            taskConfigObject.optString("taskConfigId")
        ).firstOrNull { it.isNotBlank() }
        if (!directCandidate.isNullOrBlank()) {
            return directCandidate
        }
        val taskId = taskConfigObject.optLong("id", 0L)
        return if (taskId > 0) taskId.toString() else null
    }

    private fun completeCurrentMemberTask(
        task: CurrentMemberTask,
        item: TaskFlowItem
    ): TaskFlowActionResult {
        if (task.adBizId.isNotEmpty()) {
            return finishMemberAdTask(task.taskConfigId, task.title, task.awardPoint, task.adBizId, item)
        }

        if (task.taskProcessId.isBlank()) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = "TASK_PROCESS_ID_MISSING",
                message = "缺少taskProcessId，无法执行任务",
                rpc = "AntMemberRpcCall.executeMemberTask",
                detail = currentMemberTaskActionDetail(task, "execute")
            )
        }

        if (task.targetBusinessType == MemberTaskTargetBusinessType.CALL_APP) {
            return when (checkCurrentMemberTaskFinishedWithRetry(task)) {
                CurrentMemberTaskVerifyState.CONFIRMED -> {
                    if (task.awardPoint.isNotEmpty()) {
                        Log.member("会员任务[${task.title}]#CALL_APP任务已完成，获得积分${task.awardPoint}")
                    } else {
                        Log.member("会员任务[${task.title}]#CALL_APP任务已完成")
                    }
                    TaskFlowActionResult(success = true, code = "CONFIRMED")
                }

                CurrentMemberTaskVerifyState.PARTIAL_REPEATABLE -> {
                    Log.member("会员任务[${task.title}]#CALL_APP任务本次完成但周期进度未满，等待后续调度确认")
                    TaskFlowActionResult(
                        success = true,
                        code = "VERIFY_PARTIAL",
                        progressChanged = false
                    )
                }

                CurrentMemberTaskVerifyState.UNCONFIRMED -> {
                    TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        code = "VERIFY_PENDING",
                        message = "CALL_APP任务领取后详情未确认完成",
                        rpc = "AntMemberRpcCall.querySingleTaskProcessDetail",
                        detail = currentMemberTaskActionDetail(task, "verifyCallApp"),
                        stopCurrentRound = true
                    )
                }
            }
        }

        if (task.targetBusinessType != MemberTaskTargetBusinessType.BROWSE) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
                code = "TARGET_BUSINESS_UNSUPPORTED",
                message = "缺少可闭环目标业务字段",
                rpc = "AntMemberRpcCall.executeMemberTask",
                detail = currentMemberTaskActionDetail(task, "execute")
            )
        }

        val targetBusinessArray = task.targetBusiness.split("#".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (targetBusinessArray.size < 3) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = "TARGET_BUSINESS_INVALID",
                message = "缺少可闭环BROWSE字段",
                rpc = "AntMemberRpcCall.executeMemberTask",
                detail = currentMemberTaskActionDetail(task, "execute")
            )
        }

        val bizType = targetBusinessArray[0]
        val bizSubType = targetBusinessArray[1]
        val bizParam = targetBusinessArray[2]
        val executeResponse = AntMemberRpcCall.executeMemberTask(bizParam, bizSubType, bizType)
        val executeObject = JSONObject(executeResponse)
        if (isSkippableMemberTaskRejection(executeObject)) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                code = extractMemberDomainRpcCode(executeObject).ifBlank { "NOT_PROMO_RULE_QUALIFIED" },
                message = extractMemberDomainRpcMessage(executeObject).ifBlank { "不满足任务的营销规则条件" },
                rpc = "AntMemberRpcCall.executeMemberTask",
                raw = executeResponse,
                detail = currentMemberTaskActionDetail(task, "execute")
            )
        }
        if (!ResChecker.checkRes(TAG, "执行会员任务失败:", executeObject)) {
            return memberDomainTaskFailureResult(
                item = item,
                responseObject = executeObject,
                rawResponse = executeResponse,
                rpc = "AntMemberRpcCall.executeMemberTask",
                detail = currentMemberTaskActionDetail(task, "execute")
            )
        }

        return when (checkCurrentMemberTaskFinished(task)) {
            CurrentMemberTaskVerifyState.CONFIRMED -> {
                if (task.awardPoint.isNotEmpty()) {
                    Log.member("会员任务[${task.title}]#获得积分${task.awardPoint}")
                } else {
                    Log.member("会员任务[${task.title}]#任务完成")
                }
                TaskFlowActionResult(success = true, code = "CONFIRMED")
            }

            CurrentMemberTaskVerifyState.PARTIAL_REPEATABLE -> {
                Log.member("会员任务[${task.title}]#本次完成但周期进度未满，等待后续调度确认")
                TaskFlowActionResult(
                    success = true,
                    code = "VERIFY_PARTIAL",
                    progressChanged = false
                )
            }

            CurrentMemberTaskVerifyState.UNCONFIRMED -> {
                TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    code = "VERIFY_PENDING",
                    message = "执行成功但详情未确认完成",
                    rpc = "AntMemberRpcCall.querySingleTaskProcessDetail",
                    detail = currentMemberTaskActionDetail(task, "verify"),
                    stopCurrentRound = true
                )
            }
        }
    }

    private fun prepareCurrentMemberTaskForExecution(task: CurrentMemberTask): CurrentMemberTask? {
        if (task.taskProcessId.isNotEmpty()) {
            return task
        }
        val applyResponse = AntMemberRpcCall.applyMemberTask(task.taskConfigId)
        val applyObject = JSONObject(applyResponse)
        if (isSkippableMemberTaskRejection(applyObject)) {
            Log.member("会员任务[${task.title}]#不满足营销规则，跳过领取")
            return null
        }
        if (!ResChecker.checkRes(TAG, "领取会员任务失败:", applyObject)) {
            Log.error(TAG, "领取会员任务失败:" + applyObject.optString("resultDesc", applyResponse))
            return null
        }
        val appliedTask = buildCurrentMemberTaskFromApplyResponse(task, applyObject)
        if (appliedTask == null) {
            Log.member("会员任务[${task.title}]#领取成功但缺少processId或可闭环目标业务字段，跳过执行")
        }
        return appliedTask
    }

    private fun buildCurrentMemberTaskFromApplyResponse(
        original: CurrentMemberTask,
        applyObject: JSONObject
    ): CurrentMemberTask? {
        val taskProcessObject = applyObject.optJSONObject("resultData")?.optJSONObject("taskProcessVO")
            ?: applyObject.optJSONObject("taskProcessVO")
            ?: return null
        val simpleTaskConfig = resolveCurrentMemberTaskConfigObject(taskProcessObject) ?: original.simpleTaskConfig
        val taskConfigId = resolveCurrentMemberTaskConfigId(taskProcessObject) ?: original.taskConfigId
        if (!isWhitelistedMemberTaskConfigId(taskConfigId, original.adBizId.isNotBlank())) {
            val taskTitle = simpleTaskConfig.optString("title").ifEmpty { original.title }
            logSkippedUnsupportedMemberTask(taskTitle, taskConfigId, taskProcessObject)
            return null
        }
        val processId = taskProcessObject.optString("processId").ifEmpty {
            taskProcessObject.optString("taskProcessId")
        }
        val targetBusiness = resolveSupportedMemberTaskTargetBusiness(
            taskProcessObject.optJSONArray("targetBusiness") ?: simpleTaskConfig.optJSONArray("targetBusiness")
        )
        if (processId.isBlank() || targetBusiness.raw.isBlank()) {
            return null
        }
        return original.copy(
            taskConfigId = taskConfigId,
            taskProcessId = processId,
            title = simpleTaskConfig.optString("title").ifEmpty { original.title },
            awardPoint = extractMemberTaskAwardPoint(simpleTaskConfig).ifEmpty { original.awardPoint },
            targetBusiness = targetBusiness.raw,
            targetBusinessType = targetBusiness.type,
            simpleTaskConfig = simpleTaskConfig
        )
    }

    private fun checkCurrentMemberTaskFinished(task: CurrentMemberTask): CurrentMemberTaskVerifyState {
        return try {
            if (task.taskProcessId.isEmpty()) {
                return CurrentMemberTaskVerifyState.UNCONFIRMED
            }

            val detailResponse = AntMemberRpcCall.querySingleTaskProcessDetail(task.taskProcessId)
            val detailObject = JSONObject(detailResponse)
            if (!ResChecker.checkRes(TAG, "查询会员任务详情失败:", detailObject)) {
                Log.error(
                    "$TAG.checkCurrentMemberTaskFinished",
                    "会员任务详情响应失败: " + detailObject.optString("resultDesc", detailResponse)
                )
                return CurrentMemberTaskVerifyState.UNCONFIRMED
            }

            val taskProcessObject = detailObject.optJSONObject("resultData")?.optJSONObject("taskProcessVO")
                ?: detailObject.optJSONObject("taskProcessVO")
            when {
                isMemberTaskProcessFinished(taskProcessObject) -> CurrentMemberTaskVerifyState.CONFIRMED
                isRepeatableMemberTaskProgressIncomplete(taskProcessObject) -> CurrentMemberTaskVerifyState.PARTIAL_REPEATABLE
                else -> CurrentMemberTaskVerifyState.UNCONFIRMED
            }
        } catch (_: JSONException) {
            CurrentMemberTaskVerifyState.UNCONFIRMED
        }
    }

    private fun checkCurrentMemberTaskFinishedWithRetry(task: CurrentMemberTask): CurrentMemberTaskVerifyState {
        var lastState = CurrentMemberTaskVerifyState.UNCONFIRMED
        for (i in 0 until MEMBER_CALL_APP_VERIFY_RETRY_LIMIT) {
            lastState = checkCurrentMemberTaskFinished(task)
            if (lastState != CurrentMemberTaskVerifyState.UNCONFIRMED) {
                return lastState
            }
            if (i < MEMBER_CALL_APP_VERIFY_RETRY_LIMIT - 1) {
                GlobalThreadPools.sleepCompat(MEMBER_CALL_APP_VERIFY_SLEEP_MS)
            }
        }
        return lastState
    }

    private fun isRepeatableMemberTaskProgressIncomplete(taskProcessObject: JSONObject?): Boolean {
        val extInfo = taskProcessObject?.optJSONObject("extInfo") ?: return false
        val currentCount = extInfo.optString("PERIOD_CURRENT_COUNT").toIntOrNull() ?: return false
        val targetCount = extInfo.optString("PERIOD_TARGET_COUNT").toIntOrNull() ?: return false
        return targetCount > 0 && currentCount in 0 until targetCount
    }

    private fun resolveCurrentMemberTaskConfigId(taskObject: JSONObject): String? {
        val directValue = taskObject.optString("taskConfigId")
        if (directValue.isNotEmpty()) {
            return directValue
        }
        val simpleTaskConfig = taskObject.optJSONObject("simpleTaskConfig")
            ?: taskObject.optJSONObject("taskConfigInfo")
            ?: taskObject.optJSONObject("taskConfig")
        if (simpleTaskConfig != null) {
            val configId = simpleTaskConfig.optString("configId")
            if (configId.isNotEmpty()) {
                return configId
            }
            val taskConfigId = simpleTaskConfig.optString("taskConfigId")
            if (taskConfigId.isNotEmpty()) {
                return taskConfigId
            }
            val id = simpleTaskConfig.optLong("id", 0L)
            if (id > 0) {
                return id.toString()
            }
        }
        val id = taskObject.optLong("id", 0L)
        return if (id > 0) id.toString() else null
    }

    private fun isMemberTaskProcessFinished(taskProcessObject: JSONObject?): Boolean {
        if (taskProcessObject == null) {
            return false
        }
        val status = taskProcessObject.optString("status")
        if (isMemberTaskProcessFinishedStatus(status)) {
            return true
        }
        val subStatus = taskProcessObject.optString("subStatus")
        if (isMemberTaskProcessFinishedStatus(subStatus)) {
            return true
        }
        val currentCount = taskProcessObject.optLong("currentCount", -1L)
        val targetCount = taskProcessObject.optLong("targetCount", -1L)
        if (targetCount > 0 && currentCount >= targetCount) {
            return true
        }
        val extInfo = taskProcessObject.optJSONObject("extInfo")
        if (extInfo != null) {
            if (extInfo.optString("awardCurrentPoint").isNotEmpty() || extInfo.optString("awardSuccessTime").isNotEmpty()) {
                return true
            }
        }
        return false
    }

    private fun isMemberTaskProcessFinishedStatus(status: String): Boolean {
        return status.equals("AWARDED", true) ||
            status.equals("SUCCESS", true) ||
            status.equals("COMPLETE", true) ||
            status.equals("DONE", true) ||
            status.equals("FINISHED", true) ||
            status.equals("EXPIRED", true)
    }

    private fun extractMemberTaskAwardPoint(simpleTaskConfig: JSONObject): String {
        val stageVOList = simpleTaskConfig.optJSONArray("stageVOList")
        if (stageVOList != null && stageVOList.length() > 0) {
            val stageObject = stageVOList.optJSONObject(0)
            val awardParam = stageObject?.optJSONObject("awardParam")
            val awardPoint = awardParam?.optString("awardParamPoint").orEmpty()
            if (awardPoint.isNotEmpty()) {
                return awardPoint
            }
        }
        return simpleTaskConfig.optJSONObject("awardParam")?.optString("awardParamPoint").orEmpty()
    }

    private fun isSkippableMemberTaskRejection(response: JSONObject): Boolean {
        val resultCode = response.optString("resultCode").ifEmpty {
            response.optString("errorCode")
        }
        val resultDesc = response.optString("resultDesc").ifEmpty {
            response.optString("errorMsg")
        }
        return resultCode == "NOT_PROMO_RULE_QUALIFIED" ||
            resultDesc.contains("不满足任务的营销规则条件")
    }

    private fun memberDomainTaskFailureResult(
        item: TaskFlowItem?,
        responseObject: JSONObject,
        rawResponse: String,
        rpc: String,
        detail: String
    ): TaskFlowActionResult {
        val code = extractMemberDomainRpcCode(responseObject)
        val message = extractMemberDomainRpcMessage(responseObject)
        val failureType = classifyMemberDomainTaskFailure(code, message, responseObject)
        return TaskFlowActionResult.failure(
            failureType = failureType,
            code = code,
            message = message,
            rpc = rpc,
            raw = rawResponse,
            detail = detail.ifBlank {
                item?.let {
                    "taskId=${it.id} taskName=${it.title} status=${it.status} actionType=${it.actionType}"
                }.orEmpty()
            },
            stopCurrentRound = failureType == TaskRpcFailureType.RETRYABLE_RPC
        )
    }

    private fun extractMemberDomainRpcCode(responseObject: JSONObject): String {
        val data = responseObject.optJSONObject("data")
        return sequenceOf(
            responseObject.optString("resultCode"),
            responseObject.optString("code"),
            responseObject.optString("errorCode"),
            responseObject.optString("errCode"),
            responseObject.opt("error")?.toString().orEmpty(),
            responseObject.optString("errorTip"),
            data?.optString("resultCode").orEmpty(),
            data?.optString("errorCode").orEmpty()
        ).firstOrNull { it.isNotBlank() && it != "0" }.orEmpty()
    }

    private fun extractMemberDomainRpcMessage(responseObject: JSONObject): String {
        val data = responseObject.optJSONObject("data")
        return sequenceOf(
            responseObject.optString("resultDesc"),
            responseObject.optString("resultMsg"),
            responseObject.optString("memo"),
            responseObject.optString("errorMessage"),
            responseObject.optString("errorMsg"),
            responseObject.optString("errMsg"),
            responseObject.optString("resultView"),
            responseObject.optString("desc"),
            data?.optString("resultDesc").orEmpty(),
            data?.optString("errorMsg").orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty().ifBlank {
            responseObject.toString()
        }
    }

    private fun classifyMemberDomainTaskFailure(
        code: String,
        message: String,
        responseObject: JSONObject
    ): TaskRpcFailureType {
        return when {
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
                "今日已签到",
                "任务已完结",
                "任务已结束"
            ) -> TaskRpcFailureType.TERMINAL_DONE

            code == "400000040" ||
                containsAny(message, "不支持rpc调用", "不支持RPC完成") ->
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                containsAny(message, "参数错误", "参数非法", "任务ID非法", "模板不存在", "生活记录模板不存在") ->
                TaskRpcFailureType.NON_RETRYABLE_INVALID

            isMemberDomainMarkedRetryable(responseObject) ||
                code in setOf("3000", "1009", "I07", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK", "SYSTEM_BUSY", "NETWORK_ERROR") ||
                containsAny(message, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试", "需要验证", "访问异常") ->
                TaskRpcFailureType.RETRYABLE_RPC

            code.startsWith("100010") ||
                code.contains("LIMIT", ignoreCase = true) ||
                code == "NOT_PROMO_RULE_QUALIFIED" ||
                containsAny(message, "上限", "限制", "受限", "不可领取", "资格不足", "风控", "风险", "不满足任务的营销规则条件", "访问被拒绝") ->
                TaskRpcFailureType.BUSINESS_LIMIT

            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun isMemberDomainMarkedRetryable(responseObject: JSONObject): Boolean {
        return listOf("retryable", "retriable", "canRetry").any { key ->
            responseObject.has(key) && responseObject.optBoolean(key, false)
        }
    }

    private fun currentMemberTaskActionDetail(task: CurrentMemberTask, action: String): String {
        return "taskConfigId=${task.taskConfigId.ifBlank { "UNKNOWN" }} " +
            "taskProcessId=${task.taskProcessId.ifBlank { "UNKNOWN" }} " +
            "taskName=${task.title.ifBlank { "UNKNOWN" }} status=${task.status.ifBlank { "UNKNOWN" }} " +
            "action=$action targetBusiness=${task.targetBusiness.ifBlank { "UNKNOWN" }} " +
            "adBizId=${task.adBizId.ifBlank { "NONE" }} progress=${buildCurrentMemberTaskProgress(task.current, task.limit).ifBlank { "UNKNOWN" }}"
    }

    /**
     * 保障金领取
     */
    private suspend fun collectInsuredGold(): Unit = CoroutineUtils.run {
        try {
            if (hasFlagToday(StatusFlags.FLAG_ANTMEMBER_INSURED_GOLD_DONE)) {
                Log.member("保障金🏥[今日已处理，跳过]")
                return@run
            }

            var allHandled = warmUpInsuredGoldEntrance()

            val handledInsuredGoldFlowNos = mutableSetOf<String>()
            var insuredGoldQueryRound = 0
            var shouldRecheckInsuredGold: Boolean
            do {
                insuredGoldQueryRound++
                val passResult = collectAvailableInsuredGoldOnce(handledInsuredGoldFlowNos)
                if (passResult.result != DailyTaskProcessResult.HANDLED) {
                    allHandled = false
                }
                shouldRecheckInsuredGold = passResult.availableCount > 0 &&
                    insuredGoldQueryRound < INSURED_GOLD_WAIT_LIST_QUERY_LIMIT
                if (shouldRecheckInsuredGold) {
                    Log.member("保障金🏥[待领取气泡]#本轮处理${passResult.availableCount}项，复查是否还有新奖励")
                }
            } while (shouldRecheckInsuredGold)

            val taskCenterResult = collectInsuredTaskCenterRewards()
            if (allHandled && taskCenterResult == DailyTaskProcessResult.HANDLED) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_INSURED_GOLD_DONE)
            }
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.collectInsuredGold", t)
        }
    }

    private fun collectAvailableInsuredGoldOnce(
        handledFlowNos: MutableSet<String>
    ): InsuredGoldCollectionPassResult {
        var availableCount = 0
        var passResult = DailyTaskProcessResult.HANDLED
        val response = AntMemberRpcCall.queryAvailableCollectInsuredGold()
        val responseObject = JSONObject(response)
        if (!ResChecker.checkRes(TAG, responseObject)) {
            Log.error("$TAG.collectInsuredGold.queryInsuredHome", "保障金🏥[响应失败]#$response")
            return InsuredGoldCollectionPassResult(availableCount, DailyTaskProcessResult.UNKNOWN_FAILURE)
        }
        val data = responseObject.optJSONObject("data")
        if (data == null) {
            Log.error("$TAG.collectInsuredGold.queryInsuredHome", "保障金🏥[响应缺少data]#$response")
            return InsuredGoldCollectionPassResult(availableCount, DailyTaskProcessResult.UNKNOWN_FAILURE)
        }

        val signInBall = data.optJSONObject("signInDTO")
        if (signInBall != null &&
            signInBall.optInt("sendFlowStatus") == 1 &&
            signInBall.optInt("sendType") == 1
        ) {
            val sendFlowNo = signInBall.optString("sendFlowNo")
            if (sendFlowNo.isBlank() || handledFlowNos.add(sendFlowNo)) {
                availableCount++
                passResult = mergeDailyTaskProcessResult(passResult, collectSingleInsuredGold(signInBall, true))
            }
        }

        val otherBallList = data.optJSONArray("eventToWaitDTOList") ?: JSONArray()
        for (i in 0 until otherBallList.length()) {
            val anotherBall = otherBallList.optJSONObject(i) ?: continue
            if (anotherBall.optInt("sendType") != 1) {
                continue
            }
            val sendFlowNo = anotherBall.optString("sendFlowNo")
            if (sendFlowNo.isNotBlank() && !handledFlowNos.add(sendFlowNo)) {
                continue
            }
            availableCount++
            passResult = mergeDailyTaskProcessResult(passResult, collectSingleInsuredGold(anotherBall, false))
        }

        return InsuredGoldCollectionPassResult(availableCount, passResult)
    }

    private fun warmUpInsuredGoldEntrance(): Boolean {
        return try {
            val response = AntMemberRpcCall.queryInsuredOpenAndAllowAndUpgrade()
            val responseObject = JSONObject(response)
            var success = if (ResChecker.checkRes("$TAG.collectInsuredGold.queryOpenAndAllowAndUpgrade", responseObject)) {
                true
            } else {
                Log.member("保障金🏥[访问预热]#响应失败，继续查询待领取奖励:$response")
                false
            }

            val homeRenderResponse = AntMemberRpcCall.queryInsuredGiftHomeRender()
            val homeRenderObject = JSONObject(homeRenderResponse)
            if (!ResChecker.checkRes("$TAG.collectInsuredGold.giftHomeRender", homeRenderObject)) {
                Log.member("保障金🏥[访问预热]#页面渲染失败，继续查询待领取奖励:$homeRenderResponse")
                success = false
            }
            success
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.collectInsuredGold.queryOpenAndAllowAndUpgrade", t)
            false
        }
    }

    private fun collectSingleInsuredGold(goldBall: JSONObject, isSignIn: Boolean): DailyTaskProcessResult {
        val title = resolveInsuredGoldTitle(goldBall, isSignIn)
        if (goldBall.optString("sendFlowNo").isBlank()) {
            Log.member("保障金🏥[$title]#缺少sendFlowNo，跳过")
            return DailyTaskProcessResult.UNKNOWN_FAILURE
        }
        val requestObject = buildInsuredGoldGainRequest(goldBall, isSignIn)
        val response = AntMemberRpcCall.collectInsuredGold(requestObject)
        val responseObject = JSONObject(response)
        if (!ResChecker.checkRes(TAG, responseObject)) {
            return logInsuredGoldFailure(title, responseObject, response)
        }
        val gainGold = extractInsuredGoldGainYuan(responseObject)
        if (gainGold.isBlank()) {
            Log.member("保障金🏥[$title]#领取成功，返回未包含金额")
        } else {
            Log.member("保障金🏥[$title]#+" + gainGold + "元")
        }
        return DailyTaskProcessResult.HANDLED
    }

    private suspend fun collectInsuredTaskCenterRewards(): DailyTaskProcessResult {
        var overallResult = DailyTaskProcessResult.HANDLED
        var taskCount = 0
        for (config in insuredTaskCenterConfigs) {
            val adapter = InsuredTaskCenterFlowAdapter(config)
            val runResult = TaskFlowEngine(adapter, roundSleepMs = 500L).run()
            taskCount += adapter.taskCount
            val configResult = when {
                adapter.queryFailed || adapter.hasBlockingFailure -> DailyTaskProcessResult.UNKNOWN_FAILURE
                adapter.hasRetryableFailure || runResult.stopped -> DailyTaskProcessResult.RETRYABLE_FAILURE
                else -> DailyTaskProcessResult.HANDLED
            }
            overallResult = mergeDailyTaskProcessResult(overallResult, configResult)
        }
        if (taskCount == 0 && overallResult == DailyTaskProcessResult.HANDLED) {
            Log.member("保障金🏥[任务中心]#无可处理任务")
        }
        return overallResult
    }

    private inner class InsuredTaskCenterFlowAdapter(
        private val config: InsuredTaskCenterConfig
    ) : TaskFlowAdapter {
        override val moduleName: String = insuredTaskBlacklistModule
        override val flowName: String = "保障金🏥任务中心(${config.sceneCode})"

        var queryFailed: Boolean = false
            private set
        var hasBlockingFailure: Boolean = false
            private set
        var hasRetryableFailure: Boolean = false
            private set
        var taskCount: Int = 0
            private set

        private val signedUpTaskKeys = LinkedHashSet<String>()
        private val completedTaskKeys = LinkedHashSet<String>()
        private val loggedSkipTaskKeys = LinkedHashSet<String>()

        override fun query(): JSONObject {
            return try {
                val response = AntMemberRpcCall.queryInsuredTaskListV2(
                    config.taskCenterId,
                    config.sceneCode,
                    "cfsy",
                    config.controlSolutionSceneCode
                )
                val responseObject = JSONObject(response)
                val hasData = responseObject.optJSONObject("data") != null
                val success = ResChecker.checkRes(TAG, responseObject) && hasData
                if (!hasData) {
                    responseObject.put("_taskFlowResultDesc", "响应缺少data")
                }
                responseObject
                    .put("_taskFlowQuerySuccess", success)
                    .put("_rawResponse", response)
                    .put("_taskCenterId", config.taskCenterId)
                    .put("_sceneCode", config.sceneCode)
            } catch (t: Throwable) {
                JSONObject()
                    .put("_taskFlowQuerySuccess", false)
                    .put("resultDesc", "查询异常:${t.message}")
                    .put("_taskCenterId", config.taskCenterId)
                    .put("_sceneCode", config.sceneCode)
            }
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return response.optBoolean("_taskFlowQuerySuccess", false)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val taskList = response.optJSONObject("data")?.optJSONArray("taskDetailList") ?: JSONArray()
            taskCount = max(taskCount, taskList.length())
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                val taskId = resolveInsuredTaskId(task)
                val title = resolveInsuredTaskTitle(task, taskId)
                val customInfo = resolveInsuredTaskCustomInfo(task)
                val taskMainType = task.optString("taskMainType")
                val taskType = customInfo.optString("taskType").ifBlank { taskMainType }
                val operationType = customInfo.optString("taskOperationType")
                val rawTask = JSONObject(task.toString())
                    .put("_taskCenterId", config.taskCenterId)
                    .put("_sceneCode", config.sceneCode)
                    .put("_controlSolutionSceneCode", config.controlSolutionSceneCode.orEmpty())
                items.add(
                    TaskFlowItem(
                        id = taskId,
                        title = title,
                        status = task.optString("taskProcessStatus").trim(),
                        type = taskType,
                        sceneCode = config.sceneCode,
                        actionType = operationType,
                        blacklistKeys = listOf(taskId, title).filter { it.isNotBlank() },
                        raw = rawTask,
                        progress = resolveInsuredTaskPrizeText(task)
                    )
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            if (item.id.isBlank()) {
                return TaskFlowPhase.UNKNOWN
            }
            val taskKey = buildInsuredTaskFlowKey(item)
            if (taskKey in completedTaskKeys || hasInsuredTaskSendOrder(item.raw ?: JSONObject())) {
                return TaskFlowPhase.TERMINAL
            }

            return when (item.status.uppercase(Locale.ROOT)) {
                "TO_RECEIVE",
                "WAIT_RECEIVE",
                "FINISHED",
                "COMPLETE" -> TaskFlowPhase.REWARD_READY

                "",
                "NONE",
                "NONE_SIGNUP",
                "SIGNUP_EXPIRED" -> if (taskKey in signedUpTaskKeys) {
                    TaskFlowPhase.SIGNUP_COMPLETE
                } else {
                    TaskFlowPhase.SIGNUP_REQUIRED
                }

                "SIGNUP_COMPLETE" -> TaskFlowPhase.SIGNUP_COMPLETE

                "TODO",
                "NOT_DONE",
                "WAIT_COMPLETE" -> TaskFlowPhase.READY_TO_COMPLETE

                "SEND_SUCCESS",
                "RECEIVE_SUCCESS",
                "HAS_RECEIVED",
                "RECEIVED",
                "DONE",
                "COMPLETED",
                "COMPLETE_SUCCESS",
                "SUCCESS" -> TaskFlowPhase.TERMINAL

                else -> TaskFlowPhase.UNKNOWN
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            val task = item.raw ?: return false
            if (item.id.isBlank()) {
                return false
            }
            if (buildInsuredTaskFlowKey(item) in completedTaskKeys ||
                hasInsuredTaskSendOrder(task) ||
                isInsuredTaskRewardReadyStatus(item.status)
            ) {
                return false
            }
            if (isSupportedInsuredBrowseTask(task)) {
                return false
            }

            if (isBlacklisted(item)) {
                logInsuredTaskSkipOnce(item, "黑名单任务，跳过")
                return true
            }

            logUnsupportedInsuredTask(task, item.id, item.title)
            return true
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            return verifyInsuredTaskRewardAction(item, "receive")
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            return sendInsuredTaskAndVerify(item, TaskFlowAction.COMPLETE)
        }

        override fun signup(item: TaskFlowItem): TaskFlowActionResult {
            return triggerInsuredTaskStageAction(item, "signup")
        }

        override fun send(item: TaskFlowItem): TaskFlowActionResult {
            return sendInsuredTaskAndVerify(item, TaskFlowAction.SEND)
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            return "${action.logName}:${buildInsuredTaskFlowKey(item)}:${item.status}:${item.actionType}"
        }

        override fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
            rememberSuccessfulInsuredTaskStage(item, action)
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision
        ) {
            when (decision) {
                TaskFlowDecision.MARK_HANDLED -> rememberSuccessfulInsuredTaskStage(item, action)
                TaskFlowDecision.RETRY_LATER -> hasRetryableFailure = true
                TaskFlowDecision.LOG_ONLY -> hasBlockingFailure = true
                TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN,
                TaskFlowDecision.BLACKLIST -> Unit
            }
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            logInfo("$flowName[任务列表已处理完成：${snapshot.completedTasks}/${snapshot.totalTasks}]")
        }

        override fun onQueryFailed(response: JSONObject) {
            queryFailed = true
            val reason = response.optString("_taskFlowResultDesc")
                .ifBlank { response.optString("resultDesc") }
                .ifBlank { response.optString("memo") }
                .ifBlank { response.optString("errorMsg") }
                .ifBlank { response.toString() }
            Log.error(
                "$TAG.collectInsuredTaskCenterRewards.queryTaskListV2",
                "保障金🏥[任务中心]#查询失败:${config.taskCenterId}/${config.sceneCode}#$reason raw=${response.optString("_rawResponse")}"
            )
        }

        override fun onUnknownPhase(item: TaskFlowItem, phase: TaskFlowPhase) {
            hasBlockingFailure = true
            Log.error(
                TAG,
                "保障金🏥[任务中心-${item.title}]#未知状态 module=$moduleName taskId=${item.id.ifBlank { "UNKNOWN" }} " +
                    "status=${item.status.ifBlank { "UNKNOWN" }} actionType=${item.actionType.ifBlank { "UNKNOWN" }} raw=${item.raw}"
            )
        }

        override fun onRoundLimit(roundLimit: Int) {
            hasRetryableFailure = true
            Log.error(TAG, "$flowName[达到动态轮次上限$roundLimit，停止以避免重复循环]")
        }

        override fun logInfo(message: String) {
            Log.member(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun sendInsuredTaskAndVerify(
            item: TaskFlowItem,
            action: TaskFlowAction
        ): TaskFlowActionResult {
            val sendResult = triggerInsuredTaskStageAction(item, "send")
            if (!sendResult.success) {
                return sendResult
            }
            return verifyInsuredTaskRewardAction(item, action.logName)
        }

        private fun triggerInsuredTaskStageAction(
            item: TaskFlowItem,
            stageCode: String
        ): TaskFlowActionResult {
            if (item.id.isBlank()) {
                return invalidInsuredTaskActionResult(item, "taskTriggerv2/$stageCode")
            }
            val response = AntMemberRpcCall.triggerInsuredTaskV2(
                item.id,
                config.taskCenterId,
                config.sceneCode,
                stageCode
            )
            if (response.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    message = "taskTriggerv2/$stageCode 返回空",
                    rpc = "AntMemberRpcCall.triggerInsuredTaskV2/$stageCode",
                    detail = insuredTaskActionDetail(item, stageCode),
                    stopCurrentRound = true
                )
            }
            val responseObject = JSONObject(response)
            if (!ResChecker.checkRes(TAG, responseObject)) {
                return insuredTaskActionFailureResult(
                    item = item,
                    stage = "taskTriggerv2/$stageCode",
                    responseObject = responseObject,
                    rawResponse = response,
                    rpc = "AntMemberRpcCall.triggerInsuredTaskV2/$stageCode"
                )
            }
            Log.member("保障金🏥[任务中心-${item.title}]#$stageCode 成功")
            return TaskFlowActionResult.success()
        }

        private fun verifyInsuredTaskRewardAction(
            item: TaskFlowItem,
            action: String
        ): TaskFlowActionResult {
            if (item.id.isBlank()) {
                return invalidInsuredTaskActionResult(item, "taskCenterConsultById")
            }
            val response = AntMemberRpcCall.consultInsuredTaskCenterById(config.taskCenterId, item.id)
            if (response.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    message = "taskCenterConsultById返回空",
                    rpc = "AntMemberRpcCall.consultInsuredTaskCenterById",
                    detail = insuredTaskActionDetail(item, action),
                    stopCurrentRound = true
                )
            }
            val responseObject = JSONObject(response)
            if (!ResChecker.checkRes(TAG, responseObject)) {
                return insuredTaskActionFailureResult(
                    item = item,
                    stage = "taskCenterConsultById",
                    responseObject = responseObject,
                    rawResponse = response,
                    rpc = "AntMemberRpcCall.consultInsuredTaskCenterById"
                )
            }
            val taskDetail = responseObject.optJSONObject("data")?.optJSONObject("taskDetailWithFilterDTO")
            if (taskDetail == null) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "TASK_DETAIL_MISSING",
                    message = "回查缺少taskDetailWithFilterDTO",
                    rpc = "AntMemberRpcCall.consultInsuredTaskCenterById",
                    raw = response,
                    detail = insuredTaskActionDetail(item, action)
                )
            }
            if (isInsuredTaskRewardConfirmed(taskDetail)) {
                val prizeText = resolveInsuredTaskPrizeText(taskDetail)
                val status = taskDetail.optString("taskProcessStatus")
                if (prizeText.isBlank()) {
                    Log.member("保障金🏥[任务中心-${item.title}]#领取完成:$status")
                } else {
                    Log.member("保障金🏥[任务中心-${item.title}]#$prizeText:$status")
                }
                return TaskFlowActionResult.success()
            }

            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.RETRYABLE_RPC,
                code = "VERIFY_PENDING",
                message = "回查未确认完成",
                rpc = "AntMemberRpcCall.consultInsuredTaskCenterById",
                raw = response,
                detail = insuredTaskActionDetail(item, action) +
                    " verifyStatus=${taskDetail.optString("taskProcessStatus").ifBlank { "UNKNOWN" }}",
                stopCurrentRound = true
            )
        }

        private fun rememberSuccessfulInsuredTaskStage(item: TaskFlowItem, action: TaskFlowAction) {
            val taskKey = buildInsuredTaskFlowKey(item)
            when (action) {
                TaskFlowAction.SIGNUP -> signedUpTaskKeys.add(taskKey)
                TaskFlowAction.RECEIVE,
                TaskFlowAction.COMPLETE,
                TaskFlowAction.SEND -> completedTaskKeys.add(taskKey)
            }
        }

        private fun buildInsuredTaskFlowKey(item: TaskFlowItem): String {
            return "${config.taskCenterId}|${item.id.ifBlank { item.title }}|${item.title}"
        }

        private fun logInsuredTaskSkipOnce(item: TaskFlowItem, reason: String) {
            val key = "$reason|${buildInsuredTaskFlowKey(item)}"
            if (loggedSkipTaskKeys.add(key)) {
                Log.member("保障金🏥[任务中心-${item.title}]#$reason:${item.id}")
            }
        }

        private fun invalidInsuredTaskActionResult(item: TaskFlowItem, stage: String): TaskFlowActionResult {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.NON_RETRYABLE_INVALID,
                code = "TASK_ID_INVALID",
                message = "任务ID为空",
                rpc = "AntMemberRpcCall.$stage",
                detail = insuredTaskActionDetail(item, stage)
            )
        }

        private fun insuredTaskActionFailureResult(
            item: TaskFlowItem,
            stage: String,
            responseObject: JSONObject,
            rawResponse: String,
            rpc: String
        ): TaskFlowActionResult {
            val code = extractInsuredTaskRpcCode(responseObject)
            val message = extractInsuredTaskRpcMessage(responseObject)
            val failureType = classifyInsuredTaskFailure(code, message, responseObject)
            return TaskFlowActionResult.failure(
                failureType = failureType,
                code = code,
                message = message,
                rpc = rpc,
                raw = rawResponse,
                detail = insuredTaskActionDetail(item, stage),
                stopCurrentRound = failureType == TaskRpcFailureType.RETRYABLE_RPC
            )
        }

        private fun insuredTaskActionDetail(item: TaskFlowItem, action: String): String {
            val raw = item.raw
            val customInfo = raw?.let { resolveInsuredTaskCustomInfo(it) } ?: JSONObject()
            val taskCategory = raw?.optString("taskCategory").orEmpty().ifBlank {
                customInfo.optString("taskCategorize")
            }
            return "taskCenterId=${config.taskCenterId} sceneCode=${config.sceneCode} " +
                "taskId=${item.id.ifBlank { "UNKNOWN" }} taskName=${item.title.ifBlank { "UNKNOWN" }} " +
                "status=${item.status.ifBlank { "UNKNOWN" }} action=$action " +
                "taskMainType=${raw?.optString("taskMainType").orEmpty().ifBlank { "UNKNOWN" }} " +
                "taskType=${item.type.ifBlank { "UNKNOWN" }} " +
                "operationType=${item.actionType.ifBlank { "UNKNOWN" }} " +
                "category=${taskCategory.ifBlank { "UNKNOWN" }}"
        }
    }

    private fun mergeDailyTaskProcessResult(
        current: DailyTaskProcessResult,
        next: DailyTaskProcessResult
    ): DailyTaskProcessResult {
        return when {
            current == DailyTaskProcessResult.UNKNOWN_FAILURE ||
                next == DailyTaskProcessResult.UNKNOWN_FAILURE -> DailyTaskProcessResult.UNKNOWN_FAILURE

            current == DailyTaskProcessResult.RETRYABLE_FAILURE ||
                next == DailyTaskProcessResult.RETRYABLE_FAILURE -> DailyTaskProcessResult.RETRYABLE_FAILURE

            else -> DailyTaskProcessResult.HANDLED
        }
    }

    private fun resolveInsuredTaskId(task: JSONObject): String {
        return task.optString("taskId").ifBlank {
            task.optJSONObject("taskConfig")?.optString("appletId").orEmpty()
        }
    }

    private fun resolveInsuredTaskTitle(task: JSONObject, fallback: String): String {
        val customInfo = resolveInsuredTaskCustomInfo(task)
        return customInfo.optString("taskMainTitle").ifBlank {
            task.optJSONObject("taskDisplayInfo")?.optString("taskMainTitle").orEmpty()
        }.ifBlank {
            task.optJSONObject("taskConfig")?.optString("appletName").orEmpty()
        }.ifBlank {
            fallback.ifBlank { "蚂蚁保任务" }
        }
    }

    private fun resolveInsuredTaskCustomInfo(task: JSONObject): JSONObject {
        return task.optJSONObject("taskDisplayInfo")?.optJSONObject("customInfo") ?: JSONObject()
    }

    private fun isInsuredTaskRewardConfirmed(task: JSONObject): Boolean {
        val status = task.optString("taskProcessStatus").trim().uppercase(Locale.ROOT)
        return status in setOf(
            "TO_RECEIVE",
            "WAIT_RECEIVE",
            "FINISHED",
            "COMPLETE",
            "RECEIVE_SUCCESS",
            "HAS_RECEIVED",
            "RECEIVED",
            "DONE",
            "COMPLETED",
            "COMPLETE_SUCCESS",
            "SUCCESS"
        ) ||
            hasInsuredTaskSendOrder(task)
    }

    private fun hasInsuredTaskSendOrder(task: JSONObject): Boolean {
        val sendOrderList = task.optJSONArray("sendPrizeSendOrderList") ?: return false
        for (i in 0 until sendOrderList.length()) {
            val sendOrder = sendOrderList.optJSONObject(i) ?: continue
            val sendStatus = sendOrder.optString("sendStatus")
            if (sendStatus.isBlank() || sendStatus == "SUCCESS") {
                return true
            }
        }
        return false
    }

    private fun isSupportedInsuredBrowseTask(task: JSONObject): Boolean {
        val customInfo = resolveInsuredTaskCustomInfo(task)
        val taskMainType = task.optString("taskMainType")
        val taskType = customInfo.optString("taskType").ifBlank { taskMainType }
        val operationType = customInfo.optString("taskOperationType")
        val taskCategory = task.optString("taskCategory").ifBlank {
            customInfo.optString("taskCategorize")
        }
        if (taskMainType == "ISSUED_TASK" ||
            taskType == "ISSUED_TASK" ||
            taskMainType == "EXPLAIN_INTELLIGENCE" ||
            taskType == "EXPLAIN_INTELLIGENCE" ||
            taskMainType == "COMMON_TASK" ||
            taskType == "COMMON_TASK" ||
            operationType == "COMMON_TASK" ||
            taskCategory == "TRANSFER"
        ) {
            return false
        }

        return taskMainType == "BROWSE_PAGE" ||
            taskType == "BROWSE_PAGE" ||
            taskMainType == "BROWSE_TASK" ||
            taskType == "BROWSE_TASK" ||
            operationType == "BROWSE_TASK" ||
            operationType == "CLICK_TASK" ||
            operationType == "NORMAL_PENDANT_CLICK_TASK"
    }

    private fun logUnsupportedInsuredTask(task: JSONObject, taskId: String, title: String) {
        val customInfo = resolveInsuredTaskCustomInfo(task)
        val taskMainType = task.optString("taskMainType")
        val taskType = customInfo.optString("taskType").ifBlank { taskMainType }
        val operationType = customInfo.optString("taskOperationType")
        val taskCategory = task.optString("taskCategory").ifBlank {
            customInfo.optString("taskCategorize")
        }
        val status = task.optString("taskProcessStatus")
        val reason = resolveUnsupportedInsuredTaskReason(taskMainType, taskType, operationType, taskCategory)
        Log.member(
            "保障金🏥[任务中心-$title] classification=UNSUPPORTED_NO_CLOSURE decision=BLACKLIST reason=$reason " +
                "module=$insuredTaskBlacklistModule action=unsupportedInsuredTask rpc=<none> code=400000040 msg=未抓到稳定完成RPC " +
                "taskId=$taskId taskMainType=$taskMainType taskType=$taskType " +
                "operationType=$operationType category=$taskCategory status=$status"
        )
        TaskBlacklist.autoAddToBlacklist(insuredTaskBlacklistModule, taskId, title, "400000040")
    }

    private fun resolveUnsupportedInsuredTaskReason(
        taskMainType: String,
        taskType: String,
        operationType: String,
        taskCategory: String
    ): String {
        return when {
            taskMainType == "ISSUED_TASK" || taskType == "ISSUED_TASK" || taskCategory == "TRANSFER" ->
                "投保/转账类任务缺少可确认RPC闭环"

            taskMainType == "EXPLAIN_INTELLIGENCE" || taskType == "EXPLAIN_INTELLIGENCE" ->
                "讲解/视频类任务缺少播放完成RPC闭环"

            taskMainType == "COMMON_TASK" || taskType == "COMMON_TASK" || operationType == "COMMON_TASK" ->
                "COMMON_TASK仅抓到报名，缺少发奖闭环"

            else -> "任务类型暂未支持"
        }
    }

    private fun resolveInsuredTaskPrizeText(task: JSONObject): String {
        val customInfo = resolveInsuredTaskCustomInfo(task)
        val goldPrize = customInfo.optString("goldPrize")
        if (goldPrize.isNotBlank()) {
            return "+${goldPrize}元保障金"
        }
        return resolveInsuredTaskPrizeText(task.optJSONArray("validPrizeDetailList")).ifBlank {
            resolveInsuredTaskPrizeText(task.optJSONArray("taskPrizeDetailList"))
        }
    }

    private fun resolveInsuredTaskPrizeText(prizeList: JSONArray?): String {
        if (prizeList == null) {
            return ""
        }
        for (i in 0 until prizeList.length()) {
            val prize = prizeList.optJSONObject(i) ?: continue
            val priceYuan = prize.optJSONObject("priceStrategyDTO")
                ?.optJSONObject("maxPriceYuan")
                ?.optString("value")
                .orEmpty()
            if (priceYuan.isNotBlank()) {
                return "+${priceYuan}元"
            }
            val customMemo = prize.optJSONObject("extProperties")?.optString("CUSTOM_MEMO").orEmpty()
            if (customMemo.isNotBlank()) {
                return customMemo
            }
        }
        return ""
    }

    private fun isInsuredTaskRewardReadyStatus(status: String): Boolean {
        return status.trim().uppercase(Locale.ROOT) in setOf(
            "TO_RECEIVE",
            "WAIT_RECEIVE",
            "FINISHED",
            "COMPLETE"
        )
    }

    private fun extractInsuredTaskRpcCode(responseObject: JSONObject): String {
        val data = responseObject.optJSONObject("data")
        return sequenceOf(
            responseObject.optString("resultCode"),
            responseObject.optString("code"),
            responseObject.optString("errorCode"),
            responseObject.optString("errCode"),
            data?.optString("queryErrorCode").orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun extractInsuredTaskRpcMessage(responseObject: JSONObject): String {
        val data = responseObject.optJSONObject("data")
        return sequenceOf(
            responseObject.optString("resultDesc"),
            responseObject.optString("resultMsg"),
            responseObject.optString("memo"),
            responseObject.optString("errorMessage"),
            responseObject.optString("errorMsg"),
            responseObject.optString("desc"),
            data?.optString("queryErrorMsg").orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty().ifBlank {
            responseObject.toString()
        }
    }

    private fun classifyInsuredTaskFailure(
        code: String,
        message: String,
        responseObject: JSONObject
    ): TaskRpcFailureType {
        return when {
            containsAny(
                message,
                "已领取",
                "已经领取",
                "重复领取",
                "重复领奖",
                "重复完成",
                "重复",
                "已完成",
                "任务已完结",
                "任务已结束"
            ) -> TaskRpcFailureType.TERMINAL_DONE

            code == "400000040" ||
                containsAny(message, "不支持rpc调用", "不支持RPC完成") ->
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                containsAny(message, "参数错误", "任务ID非法", "模板不存在", "生活记录模板不存在") ->
                TaskRpcFailureType.NON_RETRYABLE_INVALID

            isInsuredTaskMarkedRetryable(responseObject) ||
                code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK", "SYSTEM_BUSY", "NETWORK_ERROR") ||
                containsAny(message, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试", "需要验证", "访问被拒绝") ->
                TaskRpcFailureType.RETRYABLE_RPC

            code.startsWith("100010") ||
                code.contains("LIMIT", ignoreCase = true) ||
                containsAny(message, "次数超过限制", "上限", "限制", "受限", "不可领取", "资格不足", "风控", "风险") ->
                TaskRpcFailureType.BUSINESS_LIMIT

            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun isInsuredTaskMarkedRetryable(responseObject: JSONObject): Boolean {
        return listOf("retryable", "retriable", "canRetry").any { key ->
            responseObject.has(key) && responseObject.optBoolean(key, false)
        }
    }

    private fun buildInsuredGoldGainRequest(goldBall: JSONObject, isSignIn: Boolean): JSONObject {
        val requestObject = JSONObject(goldBall.toString())
        if (!requestObject.has("bizData")) {
            requestObject.put("bizData", JSONObject())
        }
        requestObject.put("entrance", "cfsy")
        requestObject.put("helpGain", false)
        val showYuan = requestObject.optString("sendSumInsuredYuan").ifBlank {
            requestObject.optString("realSendSumInsuredYuan")
        }
        if (showYuan.isNotBlank()) {
            requestObject.put("showYuan", showYuan)
        }
        val title = resolveInsuredGoldTitle(requestObject, isSignIn)
        if (title.isNotBlank()) {
            requestObject.put("title", title)
        }
        if (isSignIn) {
            requestObject.put("disabled", false)
            requestObject.put("isSignIn", true)
            if (!requestObject.has("isTodayContinuousSignIn")) {
                requestObject.put("isTodayContinuousSignIn", false)
            }
        }
        return requestObject
    }

    private fun resolveInsuredGoldTitle(goldBall: JSONObject, isSignIn: Boolean): String {
        if (isSignIn || goldBall.optString("channel") == "DAILY_SIGN_IN") {
            return "签到"
        }
        return when (goldBall.optString("channel")) {
            "ALIPAY_LOGIN" -> "登录奖励"
            "ANT_COVERAGE_LOGIN" -> "访问蚂蚁保"
            else -> goldBall.optString("title").ifBlank { "领取保证金" }
        }
    }

    private fun extractInsuredGoldGainYuan(responseObject: JSONObject): String {
        val data = responseObject.optJSONObject("data") ?: return ""
        val gainDto = data.optJSONObject("gainSumInsuredDTO")
        return gainDto?.optString("gainSumInsuredYuan").orEmpty().ifBlank {
            data.optString("gainSumInsuredYuan").ifBlank {
                data.optString("sendSumInsuredYuan")
            }
        }
    }

    private fun logInsuredGoldFailure(
        title: String,
        responseObject: JSONObject,
        rawResponse: String
    ): DailyTaskProcessResult {
        val code = sequenceOf(
            responseObject.optString("resultCode"),
            responseObject.optString("code"),
            responseObject.optString("errorCode")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val message = sequenceOf(
            responseObject.optString("resultDesc"),
            responseObject.optString("resultMsg"),
            responseObject.optString("memo"),
            responseObject.optString("errorMessage"),
            responseObject.optString("errorMsg"),
            responseObject.optString("desc")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val detail = when {
            code.isNotBlank() && message.isNotBlank() -> "$code/$message"
            code.isNotBlank() -> code
            message.isNotBlank() -> message
            else -> rawResponse
        }
        return when (classifyInsuredGoldFailure(code, message)) {
            InsuredGoldRpcFailureType.DUPLICATE_REWARD -> {
                Log.member("保障金🏥[$title]#已领取或重复领取，跳过:$detail")
                DailyTaskProcessResult.HANDLED
            }

            InsuredGoldRpcFailureType.BUSINESS_LIMIT -> {
                Log.member("保障金🏥[$title]#业务受限，本轮跳过:$detail")
                DailyTaskProcessResult.HANDLED
            }

            InsuredGoldRpcFailureType.RETRYABLE -> {
                Log.member("保障金🏥[$title]#暂时不可领取，保留后续重试:$detail")
                DailyTaskProcessResult.RETRYABLE_FAILURE
            }

            InsuredGoldRpcFailureType.NON_RETRYABLE -> {
                Log.error("$TAG.collectInsuredGold.collectInsuredGold", "保障金🏥[$title]#响应失败:$detail")
                DailyTaskProcessResult.UNKNOWN_FAILURE
            }
        }
    }

    private fun classifyInsuredGoldFailure(code: String, message: String): InsuredGoldRpcFailureType {
        return when {
            code == "AE13116030004362" && message.contains("领金成功") ->
                InsuredGoldRpcFailureType.DUPLICATE_REWARD

            message.contains("已领取") ||
                message.contains("重复") ||
                message.contains("已经领取") -> InsuredGoldRpcFailureType.DUPLICATE_REWARD

            message.contains("稍后") ||
                message.contains("频繁") ||
                message.contains("繁忙") -> InsuredGoldRpcFailureType.RETRYABLE

            code.contains("LIMIT", ignoreCase = true) ||
                message.contains("上限") ||
                message.contains("限制") ||
                message.contains("受限") ||
                message.contains("不可领取") -> InsuredGoldRpcFailureType.BUSINESS_LIMIT

            else -> InsuredGoldRpcFailureType.NON_RETRYABLE
        }
    }

    private fun resolveSupportedMemberTaskTargetBusiness(targetBusinessArray: JSONArray?): ResolvedMemberTaskTargetBusiness {
        if (targetBusinessArray == null || targetBusinessArray.length() <= 0) {
            return ResolvedMemberTaskTargetBusiness()
        }
        for (i in 0 until targetBusinessArray.length()) {
            val targetBusiness = targetBusinessArray.optString(i)
            val targetBusinessType = resolveSupportedMemberTaskTargetBusinessType(targetBusiness)
            if (targetBusinessType != MemberTaskTargetBusinessType.UNSUPPORTED) {
                return ResolvedMemberTaskTargetBusiness(targetBusiness, targetBusinessType)
            }
        }
        return ResolvedMemberTaskTargetBusiness()
    }

    private fun resolveSupportedMemberTaskTargetBusinessType(targetBusiness: String): MemberTaskTargetBusinessType {
        if (targetBusiness.isBlank()) {
            return MemberTaskTargetBusinessType.UNSUPPORTED
        }
        val targetParts = targetBusiness.split("#")
        val bizType = targetParts.getOrNull(0).orEmpty()
        if (bizType.equals("CALL_APP", true)) {
            val appScene = targetParts.getOrNull(1).orEmpty()
            return if (appScene.isNotBlank()) {
                MemberTaskTargetBusinessType.CALL_APP
            } else {
                MemberTaskTargetBusinessType.UNSUPPORTED
            }
        }
        if (targetParts.size < 3) {
            return MemberTaskTargetBusinessType.UNSUPPORTED
        }
        val bizSubType = targetParts[1]
        val bizParam = targetParts[2]
        return if (bizType.equals("BROWSE", true) && bizSubType.isNotBlank() && bizParam.isNotBlank()) {
            MemberTaskTargetBusinessType.BROWSE
        } else {
            MemberTaskTargetBusinessType.UNSUPPORTED
        }
    }

    private fun resolveMemberTaskTargetBusinessType(value: String): MemberTaskTargetBusinessType {
        return try {
            MemberTaskTargetBusinessType.valueOf(value.ifBlank { MemberTaskTargetBusinessType.UNSUPPORTED.name })
        } catch (_: IllegalArgumentException) {
            MemberTaskTargetBusinessType.UNSUPPORTED
        }
    }

    private fun isWhitelistedMemberTaskConfigId(taskConfigId: String, isAdTask: Boolean): Boolean {
        return if (isAdTask) {
            memberAdTaskClosedLoopConfigIds.contains(taskConfigId)
        } else {
            memberTaskClosedLoopConfigIds.contains(taskConfigId)
        }
    }

    private fun logSkippedUnsupportedMemberTask(
        taskTitle: String,
        taskConfigId: String,
        taskProcessObject: JSONObject
    ) {
        if (isMemberTaskProcessFinished(taskProcessObject)) return
        val source = taskProcessObject.optString("source").ifEmpty {
            resolveCurrentMemberTaskConfigObject(taskProcessObject)?.optString("sourceBusiness").orEmpty()
        }
        val status = taskProcessObject.optString("status").ifEmpty {
            taskProcessObject.optString("subStatus")
        }
        TaskBlacklist.addToBlacklist(memberTaskBlacklistModule, taskConfigId, taskTitle)
        val detail = buildString {
            append("configId=").append(taskConfigId)
            append(", classification=UNSUPPORTED_NO_CLOSURE")
            append(", decision=BLACKLIST")
            if (source.isNotBlank()) {
                append(", source=").append(source)
            }
            if (status.isNotBlank()) {
                append(", status=").append(status)
            }
        }
        if (!loggedUnsupportedMemberTaskIds.add(taskConfigId)) {
            return
        }
        if (loggedUnsupportedMemberTaskIds.size > MEMBER_TASK_UNSUPPORTED_LOG_LIMIT) {
            if (!unsupportedMemberTaskOverflowLogged) {
                unsupportedMemberTaskOverflowLogged = true
                Log.member("会员任务#更多未纳入白名单闭环任务已加入黑名单并省略日志")
            }
            return
        }
        Log.member("会员任务[$taskTitle]#未纳入白名单闭环，已加入黑名单($detail)")
    }

    private fun isMemberTaskInBlacklist(taskConfigId: String, taskTitle: String): Boolean {
        return TaskBlacklist.isTaskInBlacklist(memberTaskBlacklistModule, taskTitle)
            || TaskBlacklist.isTaskInBlacklist(memberTaskBlacklistModule, taskConfigId)
    }

    private fun resolveMemberAdTaskBizId(
        taskObject: JSONObject?,
        taskConfigInfo: JSONObject? = null
    ): String {
        if (isUnsupportedMemberAdTaskType(taskObject, taskConfigInfo)) {
            return ""
        }

        val urlCandidates = listOfNotNull(
            taskObject?.optString("actionUrl"),
            taskObject?.optString("targetUrl"),
            taskObject?.optString("jumpUrl"),
            taskObject?.optString("pageUrl"),
            taskObject?.optString("clickThroughUrl"),
            taskObject?.optString("halfClickThroughUrl"),
            taskConfigInfo?.optString("actionUrl"),
            taskConfigInfo?.optString("targetUrl"),
            taskConfigInfo?.optString("jumpUrl"),
            taskConfigInfo?.optString("pageUrl"),
            taskConfigInfo?.optString("clickThroughUrl"),
            taskConfigInfo?.optString("halfClickThroughUrl"),
            taskConfigInfo?.optString("schemaJson")
        ).filter { it.isNotBlank() }
        val hasMemberAdUrlMarker = urlCandidates.any { looksLikeMemberAdTaskUrl(it) }

        if (hasExplicitMemberAdTaskMarker(taskObject, taskConfigInfo, hasMemberAdUrlMarker)) {
            val directBizId = sequenceOf(
                taskObject?.optString("adBizId"),
                taskObject?.optJSONObject("logExtMap")?.optString("bizId"),
                taskObject?.optJSONObject("extInfo")?.optString("adBizId"),
                taskObject?.optJSONObject("extInfo")?.optString("bizId"),
                taskConfigInfo?.optString("adBizId"),
                taskConfigInfo?.optJSONObject("logExtMap")?.optString("bizId"),
                taskConfigInfo?.optJSONObject("extInfo")?.optString("adBizId"),
                taskConfigInfo?.optJSONObject("extInfo")?.optString("bizId")
            ).filterNotNull().firstOrNull { it.isNotBlank() }
            if (!directBizId.isNullOrBlank()) {
                return directBizId
            }
        }

        for (urlCandidate in urlCandidates) {
            if (!hasMemberAdUrlMarker || !looksLikeMemberAdTaskUrl(urlCandidate)) {
                continue
            }
            extractMemberAdBizIdFromText(urlCandidate)?.let { return it }
            val nestedUrl = UrlUtil.getFullNestedUrl(urlCandidate, "url")
            if (!nestedUrl.isNullOrBlank()) {
                extractMemberAdBizIdFromText(nestedUrl)?.let { return it }
            }
        }
        return ""
    }

    private fun resolveUnsupportedMemberAdTaskReason(
        taskObject: JSONObject?,
        taskConfigInfo: JSONObject?
    ): String? {
        val taskTypeCandidates = sequenceOf(
            taskObject?.optString("taskType"),
            taskConfigInfo?.optString("taskType")
        ).filterNotNull()
        if (taskTypeCandidates.any { it.equals("MULTIPLE_TIMER_TASK", true) }) {
            return "MULTIPLE_TIMER_TASK"
        }
        if (taskObject?.has("videoTaskInfo") == true || taskConfigInfo?.has("videoTaskInfo") == true) {
            return "VIDEO_TASK"
        }
        if (taskObject?.optBoolean("adVideoTask") == true || taskConfigInfo?.optBoolean("adVideoTask") == true) {
            return "AD_VIDEO_TASK"
        }
        if (hasMemberAdVideoSchema(taskObject, taskConfigInfo)) {
            return "VIDEO_TASK"
        }
        return null
    }

    private fun isUnsupportedMemberAdTaskType(
        taskObject: JSONObject?,
        taskConfigInfo: JSONObject?
    ): Boolean {
        return resolveUnsupportedMemberAdTaskReason(taskObject, taskConfigInfo) != null
    }

    private fun logSkippedMemberAdTask(
        taskTitle: String,
        skipReason: String,
        logPrefix: String = "会员任务"
    ) {
        val detail = when (skipReason) {
            "MULTIPLE_TIMER_TASK" -> "多阶段倒计时任务"
            "VIDEO_TASK", "AD_VIDEO_TASK" -> "视频广告任务"
            else -> skipReason
        }
        Log.member("$logPrefix[$taskTitle]#识别到$detail，未纳入白名单闭环，跳过")
    }

    private fun hasExplicitMemberAdTaskMarker(
        taskObject: JSONObject?,
        taskConfigInfo: JSONObject?,
        hasMemberAdUrlMarker: Boolean
    ): Boolean {
        if (hasMemberAdUrlMarker) {
            return true
        }
        val configIds = linkedSetOf<String>().apply {
            taskObject?.let(::resolveCurrentMemberTaskConfigId)?.takeIf { it.isNotBlank() }?.let(::add)
            taskObject?.optString("configId")?.takeIf { it.isNotBlank() }?.let(::add)
            taskConfigInfo?.optString("configId")?.takeIf { it.isNotBlank() }?.let(::add)
            taskConfigInfo?.optLong("id", 0L)?.takeIf { it > 0 }?.toString()?.let(::add)
        }
        if (configIds.any { it.length == 8 && it.startsWith("3200") }) {
            return true
        }
        return taskObject?.optBoolean("adTaskFlag") == true ||
            taskConfigInfo?.optBoolean("adTaskFlag") == true ||
            taskObject?.optBoolean("adTask") == true ||
            taskConfigInfo?.optBoolean("adTask") == true
    }

    private fun hasMemberAdVideoSchema(
        taskObject: JSONObject?,
        taskConfigInfo: JSONObject?
    ): Boolean {
        return sequenceOf(
            taskObject?.optString("schemaJson"),
            taskConfigInfo?.optString("schemaJson")
        ).filterNotNull()
            .any { schemaJson ->
                if (schemaJson.isBlank()) {
                    false
                } else {
                    runCatching {
                        JSONObject(schemaJson).optString("videoUrl").isNotBlank()
                    }.getOrDefault(false)
                }
            }
    }

    private fun extractMemberAdBizIdFromText(text: String): String? {
        if (text.isBlank()) {
            return null
        }
        UrlUtil.getParamValue(text, "bizId")?.takeIf { it.isNotBlank() }?.let { return it }
        UrlUtil.getParamValue(text, "opParam")
            ?.takeIf { it.isNotBlank() }
            ?.let { opParam ->
                runCatching { JSONObject(opParam).optString("bizId") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        val jsonMatcher = Pattern.compile("\"bizId\"\\s*:\\s*\"([^\"]+)\"").matcher(text)
        if (jsonMatcher.find()) {
            return jsonMatcher.group(1)
        }
        val queryMatcher = Pattern.compile("bizId=([^&#\"]+)").matcher(text)
        if (queryMatcher.find()) {
            return queryMatcher.group(1)
        }
        return null
    }

    private fun looksLikeMemberAdTaskUrl(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        val normalized = text.lowercase()
        return normalized.contains("com.alipay.adtask.biz.mobilegw.service.task.finish") ||
            normalized.contains("spacecode#ant_member_xlight_task") ||
            normalized.contains("spacecode=ant_member_xlight_task") ||
            (normalized.contains("renderconfigkey=") && normalized.contains("ant_member_xlight_task"))
    }

    private fun finishMemberAdTask(
        taskConfigId: String,
        taskTitle: String,
        fallbackAwardPoint: String,
        bizId: String,
        item: TaskFlowItem? = null
    ): TaskFlowActionResult {
        if (!isWhitelistedMemberTaskConfigId(taskConfigId, true)) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = "CONFIG_NOT_WHITELISTED",
                message = "广告任务configId未纳入白名单闭环",
                rpc = "AntMemberRpcCall.taskFinish",
                detail = "taskConfigId=$taskConfigId taskName=$taskTitle bizId=$bizId"
            )
        }
        val response = AntMemberRpcCall.taskFinish(bizId)
        val responseObject = JSONObject(response)
        val success = responseObject.optBoolean("success") ||
            responseObject.optString("errCode") == "0" ||
            responseObject.optString("resultCode").equals("SUCCESS", true)
        if (!success) {
            return memberDomainTaskFailureResult(
                item = item,
                responseObject = responseObject,
                rawResponse = response,
                rpc = "AntMemberRpcCall.taskFinish",
                detail = "taskConfigId=$taskConfigId taskName=$taskTitle bizId=$bizId"
            )
        }
        val verifyState = checkMemberAdTaskFinished(taskConfigId, bizId)
        val rewardPoint = responseObject.optJSONObject("extendInfo")
            ?.optJSONObject("rewardInfo")
            ?.optString("rewardAmount")
            .orEmpty()
            .ifEmpty { fallbackAwardPoint }
        if (verifyState == CurrentMemberTaskVerifyState.CONFIRMED) {
            if (rewardPoint.isNotBlank()) {
                Log.member("会员任务[$taskTitle]#获得积分$rewardPoint")
            } else {
                Log.member("会员任务[$taskTitle]#广告任务完成")
            }
            return TaskFlowActionResult(success = true, code = "CONFIRMED")
        } else {
            Log.member("会员任务[$taskTitle]#广告任务上报成功，状态待后续页面确认")
            return TaskFlowActionResult(
                success = true,
                code = "VERIFY_PENDING",
                progressChanged = false
            )
        }
    }

    private fun checkMemberAdTaskFinished(
        taskConfigId: String,
        bizId: String
    ): CurrentMemberTaskVerifyState {
        if (taskConfigId.isBlank() || bizId.isBlank()) {
            return CurrentMemberTaskVerifyState.UNCONFIRMED
        }
        return try {
            val detailResponse = AntMemberRpcCall.querySingleAdTaskProcessDetail(taskConfigId, bizId)
            val detailObject = JSONObject(detailResponse)
            if (!ResChecker.checkRes(TAG, detailObject)) {
                return CurrentMemberTaskVerifyState.UNCONFIRMED
            }
            val taskProcessObject = detailObject.optJSONObject("resultData")?.optJSONObject("taskProcessVO")
            if (isMemberTaskProcessFinished(taskProcessObject)) {
                CurrentMemberTaskVerifyState.CONFIRMED
            } else {
                CurrentMemberTaskVerifyState.UNCONFIRMED
            }
        } catch (_: JSONException) {
            CurrentMemberTaskVerifyState.UNCONFIRMED
        }
    }

    /**
     * 黄金票任务入口（首页签到/收取/任务扫描 + 提取）
     * @param doSignIn 是否执行签到
     * @param doConsume 是否执行提取
     */
    internal fun doGoldTicketTask(doSignIn: Boolean, doConsume: Boolean) {
        val needSignIn = doSignIn && !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_SIGN_DONE)
        val needHomeCheck = doSignIn && !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_HOME_DONE)
        val needWelfareCheck = doSignIn && !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_WELFARE_DONE)
        val needConsume = doConsume && !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_CONSUME_DONE)

        if (!needSignIn && !needHomeCheck && !needWelfareCheck && !needConsume) {
            Log.member("黄金票🎫[今日已处理] 跳过执行")
            return
        }

        try {
            Log.member("开始执行黄金票...")

            var homeUpsertData: JSONObject? = null
            if (needSignIn || needHomeCheck) {
                homeUpsertData = queryGoldTicketHomeUpsert()
            }

            if (needSignIn) {
                if (homeUpsertData == null) {
                    Log.error("黄金票🎫[首页查询失败] 无法判断签到状态")
                } else if (doGoldTicketSignIn(homeUpsertData)) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_SIGN_DONE)
                    homeUpsertData = queryGoldTicketHomeUpsert() ?: homeUpsertData
                }
            }

            if (needHomeCheck) {
                if (homeUpsertData == null) {
                    Log.error("黄金票🎫[首页查询失败] 跳过收取与任务扫描")
                } else {
                    doGoldTicketCollect(homeUpsertData)
                    val homeTaskHandleResult = handleGoldTicketTasks(homeUpsertData)
                    if (!homeTaskHandleResult.querySuccess) {
                        Log.error("黄金票🎫[首页任务查询失败]")
                    } else if (homeTaskHandleResult.canMarkDone) {
                        setFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_HOME_DONE)
                    }
                }
            }

            if (needWelfareCheck) {
                val welfareHandleResult = handleGoldTicketWelfareTasks()
                if (!welfareHandleResult.querySuccess) {
                    Log.error("黄金票🎫[福利中心任务查询失败]")
                } else if (welfareHandleResult.canMarkDone) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_WELFARE_DONE)
                }
            }

            if (needConsume) {
                doGoldTicketConsume()
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 黄金票首页数据
     */
    private fun queryGoldTicketHomeUpsert(taskId: String = ""): JSONObject? {
        return try {
            val homeRes = AntMemberRpcCall.queryGoldTicketHome(taskId) ?: return null
            val homeJson = JSONObject(homeRes)
            if (!ResChecker.checkRes(TAG, homeJson)) {
                return null
            }
            homeJson.optJSONObject("result")?.optJSONObject("upsertData")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            null
        }
    }

    private fun isGoldTicketCanSign(homeUpsertData: JSONObject?): Boolean {
        return homeUpsertData?.optJSONObject("assetInfo")?.optBoolean("canSign", false) == true
    }

    private fun doGoldTicketIndexCollect(source: String): Int {
        val needleResponse = AntMemberRpcCall.goldTicketIndexCollect()
        if (!needleResponse.isNullOrBlank()) {
            return logGoldTicketCollectResponse(needleResponse, source)
        }
        return logGoldTicketCollectResponse(
            AntMemberRpcCall.goldBillCollect(),
            "$source-旧版兼容"
        )
    }

    /**
     * 黄金票签到逻辑
     *
     * 真实首页日志来自 `com.alipay.wealthgoldtwa.needle.v2.index`，
     * 抓包显示收取接口已切到 `com.alipay.wealthgoldtwa.needle.index.collect`，
     * 因此先用首页 `canSign` 判定，再尝试新版首页收取；
     * 若仍未落库，再回退到已有的 welfareCenter 触发链路。
     */
    private fun doGoldTicketSignIn(homeUpsertData: JSONObject): Boolean {
        return try {
            if (!isGoldTicketCanSign(homeUpsertData)) {
                Log.member("黄金票🎫[今日已签到]")
                return true
            }

            Log.member("黄金票🎫[准备签到]")

            var signSuccess = false
            val collectCount = doGoldTicketIndexCollect("签到尝试")
            var refreshedHome = queryGoldTicketHomeUpsert()
            if (refreshedHome != null && !isGoldTicketCanSign(refreshedHome)) {
                Log.member(
                    if (collectCount > 0) "黄金票🎫[签到成功]#通过首页收取完成签到"
                    else "黄金票🎫[签到成功]"
                )
                signSuccess = true
            }

            if (!signSuccess) {
                val signRes = AntMemberRpcCall.welfareCenterTrigger("SIGN")
                if (signRes.isNotBlank()) {
                    val signJson = JSONObject(signRes)
                    if (ResChecker.checkRes(TAG, signJson)) {
                        val signResult = signJson.optJSONObject("result")
                        val amount = signResult?.optJSONObject("prize")?.optString("amount").orEmpty()
                        refreshedHome = queryGoldTicketHomeUpsert()
                        signSuccess = refreshedHome != null && !isGoldTicketCanSign(refreshedHome)
                        if (signSuccess || amount.isNotBlank()) {
                            Log.member(
                                if (amount.isNotBlank()) "黄金票🎫[签到成功]#获得: $amount"
                                else "黄金票🎫[签到成功]"
                            )
                            signSuccess = true
                        }
                    }
                }
            }

            if (!signSuccess) {
                Log.error("黄金票🎫[签到失败] 未找到可用签到返回")
            }
            signSuccess
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            false
        }
    }

    /**
     * 黄金票首页场景收取
     */
    private fun doGoldTicketCollect(homeUpsertData: JSONObject) {
        try {
            val toBeCollectInfo = homeUpsertData.optJSONObject("assetInfo")?.optJSONObject("toBeCollectInfo")
            val totalProfitValue = toBeCollectInfo?.optInt("totalProfitValue", 0) ?: 0
            if (totalProfitValue <= 0) {
                return
            }

            val collectCount = doGoldTicketIndexCollect("场景收取")
            if (collectCount == 0) {
                Log.member("黄金票🎫[场景收取] 暂无可领取奖励")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    private fun logGoldTicketCollectResponse(response: String?, source: String): Int {
        if (response.isNullOrBlank()) {
            return 0
        }
        return try {
            val collectJson = JSONObject(response)
            if (!ResChecker.checkRes(TAG, collectJson)) {
                val message = collectJson.optString("resultDesc", collectJson.optString("memo"))
                if (message.isNotBlank()) {
                    Log.member("黄金票🎫[$source] $message")
                }
                return 0
            }

            val result = collectJson.optJSONObject("result") ?: return 0
            val collectedList = result.optJSONArray("collectedList") ?: return 0
            var count = 0
            for (i in 0 until collectedList.length()) {
                val item = collectedList.optString(i)
                if (item.isBlank()) {
                    continue
                }
                count++
                Log.member("黄金票🎫[$source]#$item")
            }

            if (count > 0) {
                val totalAmount = result.optJSONObject("collectedCamp")?.optString("amount").orEmpty()
                if (totalAmount.isNotBlank()) {
                    Log.member("黄金票🎫[$source]#本次共得${totalAmount}份")
                }
            }
            count
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            0
        }
    }

    private fun isGoldTicketEggSignTask(task: JSONObject): Boolean {
        val taskId = task.optString("taskId")
        if (taskId == "AP11249033") {
            return true
        }
        return task.optString("title").contains("蛋定生财")
    }

    private fun isGoldTicketKnownWelfareAutoTask(task: JSONObject): Boolean {
        return when (task.optString("taskId")) {
            "AP11249033", // 逛蛋定生财去签到
            "AP10247402", // 逛逛稳健理财领红包
            "AP13250426", // 逛定期市场领红包
            "AP15280470", // 逛蚂蚁投教基地
            "AP16338809"  // 去芝麻攒粒兑权益
            -> true

            else -> false
        }
    }

    private fun queryGoldTicketWelfareResult(): JSONObject? {
        return try {
            val welfareResponse = AntMemberRpcCall.queryWelfareHome() ?: return null
            val welfareJson = JSONObject(welfareResponse)
            if (!ResChecker.checkRes(TAG, welfareJson)) {
                return null
            }
            welfareJson.optJSONObject("result")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            null
        }
    }

    private fun queryGoldTicketWelfareTodoTasks(): JSONArray? {
        val welfareResult = queryGoldTicketWelfareResult() ?: return null
        return welfareResult.optJSONObject("goldbillTasks")
            ?.optJSONArray("todo")
            ?: JSONArray()
    }

    private fun extractGoldTicketHomeTodoTasks(homeUpsertData: JSONObject): JSONArray {
        return homeUpsertData.optJSONObject("task")
            ?.optJSONObject("tasks")
            ?.optJSONArray("todo")
            ?: JSONArray()
    }

    private fun isGoldTicketTaskRewardReady(task: JSONObject): Boolean {
        return task.optString("taskProcessStatus") in setOf(
            "TO_RECEIVE",
            "WAIT_RECEIVE",
            "FINISHED",
            "COMPLETE"
        )
    }

    private fun isGoldTicketTaskPendingAutoStatus(task: JSONObject): Boolean {
        return task.optString("taskProcessStatus") in setOf(
            "TO_RECEIVE",
            "WAIT_RECEIVE",
            "FINISHED",
            "COMPLETE",
            "NONE_SIGNUP",
            "SIGNUP_EXPIRED",
            "SIGNUP_COMPLETE"
        )
    }

    private fun isGoldTicketTaskBlacklisted(task: JSONObject): Boolean {
        val taskId = task.optString("taskId")
        val title = task.optString("title", taskId)
        return TaskBlacklist.isTaskInBlacklist(goldTicketTaskBlacklistModule, taskId) ||
            TaskBlacklist.isTaskInBlacklist(goldTicketTaskBlacklistModule, title)
    }

    private fun isGoldTicketManualHomeTask(task: JSONObject): Boolean {
        val status = task.optString("taskProcessStatus")
        val hasEntrance = task.optString("link").isNotBlank() ||
            task.optBoolean("canAccess", false)
        return when (status) {
            "NONE_SIGNUP",
            "SIGNUP_EXPIRED" -> hasEntrance
            "SIGNUP_COMPLETE" -> hasEntrance && !isGoldTicketEggSignTask(task)
            else -> false
        }
    }

    private fun countGoldTicketPendingWelfareAutoTasks(todoTasks: JSONArray?): Int {
        if (todoTasks == null || todoTasks.length() == 0) {
            return 0
        }
        var pendingCount = 0
        for (i in 0 until todoTasks.length()) {
            val task = todoTasks.optJSONObject(i) ?: continue
            if (isGoldTicketKnownWelfareAutoTask(task) &&
                isGoldTicketTaskPendingAutoStatus(task) &&
                (isGoldTicketTaskRewardReady(task) || !isGoldTicketTaskBlacklisted(task))
            ) {
                pendingCount++
            }
        }
        return pendingCount
    }

    /**
     * 黄金票首页任务扫描。
     *
     * 首页任务和福利中心任务共用 `taskProcessStatus` 阶段流。首页只自动推进已确认
     * 可闭环的任务和待领奖任务，其余带跳转入口的任务仍保守记录为手动处理。
     */
    private fun handleGoldTicketTasks(homeUpsertData: JSONObject): GoldTicketTaskFlowHandleResult {
        return try {
            val initialTodoTasks = extractGoldTicketHomeTodoTasks(homeUpsertData)
            val adapter = GoldTicketTaskFlowAdapter(
                source = "首页",
                firstTasks = initialTodoTasks,
                queryTasks = { queryGoldTicketHomeUpsert()?.let { extractGoldTicketHomeTodoTasks(it) } },
                autoTaskPredicate = ::isGoldTicketKnownWelfareAutoTask,
                recordUnsupportedTasks = true
            )
            val runResult = TaskFlowEngine(adapter, roundSleepMs = 500L).run()
            adapter.logSummary()
            GoldTicketTaskFlowHandleResult(
                querySuccess = !adapter.queryFailed,
                canMarkDone = !adapter.queryFailed &&
                    !adapter.hasBlockingFailure &&
                    !runResult.stopped
            )
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            GoldTicketTaskFlowHandleResult(querySuccess = false, canMarkDone = false)
        }
    }

    /**
     * 福利中心已确认的浏览类任务会走：
     * `goldbill.v4.task.trigger -> needle.taskQueryPush -> welfareCenter.index`
     * 这里仅放开抓包已确认的 taskId，避免把未知福利任务误判成可自动完成。
     */
    private fun handleGoldTicketWelfareTasks(): GoldTicketTaskFlowHandleResult {
        try {
            val todoTasks = queryGoldTicketWelfareTodoTasks()
                ?: return GoldTicketTaskFlowHandleResult(querySuccess = false, canMarkDone = false)

            val trackedAutoTaskCount = countGoldTicketPendingWelfareAutoTasks(todoTasks)
            if (trackedAutoTaskCount == 0) {
                return GoldTicketTaskFlowHandleResult(querySuccess = true, canMarkDone = true)
            }

            val adapter = GoldTicketTaskFlowAdapter(
                source = "福利中心",
                firstTasks = todoTasks,
                queryTasks = ::queryGoldTicketWelfareTodoTasks,
                autoTaskPredicate = ::isGoldTicketKnownWelfareAutoTask,
                recordUnsupportedTasks = false
            )
            TaskFlowEngine(adapter, roundSleepMs = 500L).run()

            val refreshedTodoTasks = queryGoldTicketWelfareTodoTasks()
            if (refreshedTodoTasks == null) {
                Log.member("黄金票🎫[福利中心任务复查失败] 暂不写入今日完成")
                return GoldTicketTaskFlowHandleResult(
                    querySuccess = true,
                    canMarkDone = false
                )
            }

            val pendingRetryCount = countGoldTicketPendingWelfareAutoTasks(refreshedTodoTasks)
            val confirmedAutoReceivedCount = (trackedAutoTaskCount - pendingRetryCount).coerceAtLeast(0)
            if (confirmedAutoReceivedCount > 0) {
                Log.member("黄金票🎫[福利中心任务自动领取] ${confirmedAutoReceivedCount}项")
            }
            if (pendingRetryCount > 0) {
                Log.member("黄金票🎫[福利中心任务保留下次重试] ${pendingRetryCount}项")
            }
            return GoldTicketTaskFlowHandleResult(
                querySuccess = true,
                canMarkDone = pendingRetryCount == 0 && !adapter.hasBlockingFailure
            )
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            return GoldTicketTaskFlowHandleResult(querySuccess = false, canMarkDone = false)
        }
    }

    private inner class GoldTicketTaskFlowAdapter(
        private val source: String,
        private val firstTasks: JSONArray,
        private val queryTasks: () -> JSONArray?,
        private val autoTaskPredicate: (JSONObject) -> Boolean,
        private val recordUnsupportedTasks: Boolean
    ) : TaskFlowAdapter {
        override val moduleName: String = goldTicketTaskBlacklistModule
        override val flowName: String = "黄金票🎫${source}任务"

        var queryFailed: Boolean = false
            private set
        var hasBlockingFailure: Boolean = false
            private set

        private var firstQueryConsumed = false
        private var autoReceivedCount = 0
        private val unsupportedTaskKeys = LinkedHashSet<String>()

        override fun query(): JSONObject {
            val todoTasks = if (!firstQueryConsumed) {
                firstQueryConsumed = true
                firstTasks
            } else {
                queryTasks()
            }
            if (todoTasks == null) {
                queryFailed = true
                return JSONObject()
                    .put("success", false)
                    .put("resultDesc", "${source}任务查询失败")
            }
            return JSONObject()
                .put("success", true)
                .put("tasks", todoTasks)
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return response.optBoolean("success", false)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val todoTasks = response.optJSONArray("tasks") ?: return emptyList()
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until todoTasks.length()) {
                val task = todoTasks.optJSONObject(i) ?: continue
                val taskId = task.optString("taskId").trim()
                if (taskId.isBlank()) {
                    continue
                }
                val title = task.optString("title", taskId).trim().ifBlank { taskId }
                val status = task.optString("taskProcessStatus").trim()
                val shouldHandle = isGoldTicketTaskRewardReady(task) || autoTaskPredicate(task)
                if (!shouldHandle) {
                    if (recordUnsupportedTasks && isGoldTicketManualHomeTask(task)) {
                        unsupportedTaskKeys.add("$taskId|$title")
                    }
                    continue
                }

                items.add(
                    TaskFlowItem(
                        id = taskId,
                        title = title,
                        status = status,
                        type = task.optString("taskType"),
                        sceneCode = task.optString("sceneCode"),
                        actionType = task.optString("actionType"),
                        blacklistKeys = listOf(taskId, title).filter { it.isNotBlank() },
                        raw = task,
                        progress = task.optString("subTitle")
                            .ifBlank { task.optString("amount") }
                    )
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            return when (item.status) {
                "TO_RECEIVE",
                "WAIT_RECEIVE",
                "FINISHED",
                "COMPLETE" -> TaskFlowPhase.REWARD_READY

                "NONE_SIGNUP",
                "SIGNUP_EXPIRED" -> TaskFlowPhase.SIGNUP_REQUIRED

                "SIGNUP_COMPLETE" -> TaskFlowPhase.SIGNUP_COMPLETE

                "RECEIVE_SUCCESS",
                "HAS_RECEIVED",
                "DONE",
                "COMPLETED" -> TaskFlowPhase.TERMINAL

                else -> TaskFlowPhase.UNKNOWN
            }
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            return pushGoldTicketTask(item, "receive")
        }

        override fun signup(item: TaskFlowItem): TaskFlowActionResult {
            val response = AntMemberRpcCall.goldBillTaskTrigger(item.id)
            if (response.isNullOrBlank()) {
                return emptyGoldTicketActionResponse(item, "AntMemberRpcCall.goldBillTaskTrigger", "signup")
            }
            val result = JSONObject(response)
            if (ResChecker.checkRes(TAG, result)) {
                Log.member("黄金票🎫[${source}任务报名成功]#${item.title}")
                return TaskFlowActionResult.success()
            }
            return goldTicketActionFailureResult(
                response = result,
                rpc = "AntMemberRpcCall.goldBillTaskTrigger",
                item = item,
                action = "signup",
                source = source
            )
        }

        override fun send(item: TaskFlowItem): TaskFlowActionResult {
            return pushGoldTicketTask(item, "send")
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            return "${action.logName}:$source:${item.id}:${item.status}"
        }

        override fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
            if (action == TaskFlowAction.RECEIVE || action == TaskFlowAction.SEND) {
                autoReceivedCount++
            }
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision
        ) {
            if (decision == TaskFlowDecision.RETRY_LATER ||
                decision == TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN ||
                result.failureType == TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            ) {
                hasBlockingFailure = true
            }
        }

        override fun onQueryFailed(response: JSONObject) {
            queryFailed = true
            Log.error(TAG, "黄金票🎫[${source}任务查询失败] raw=$response")
        }

        override fun logInfo(message: String) {
            Log.member(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        fun logSummary() {
            if (autoReceivedCount > 0) {
                Log.member("黄金票🎫[${source}任务自动领取] ${autoReceivedCount}项")
            }
            if (unsupportedTaskKeys.isNotEmpty()) {
                Log.member("黄金票🎫[${source}任务待手动处理] ${unsupportedTaskKeys.size}项")
            }
        }

        private fun pushGoldTicketTask(item: TaskFlowItem, action: String): TaskFlowActionResult {
            val response = AntMemberRpcCall.taskQueryPush(item.id)
            if (response.isNullOrBlank()) {
                return emptyGoldTicketActionResponse(item, "AntMemberRpcCall.taskQueryPush", action)
            }
            val result = JSONObject(response)
            if (!ResChecker.checkRes(TAG, result)) {
                return goldTicketActionFailureResult(
                    response = result,
                    rpc = "AntMemberRpcCall.taskQueryPush",
                    item = item,
                    action = action,
                    source = source
                )
            }
            val pushDone = result.optJSONObject("result")
                ?.optJSONObject("pushResult")
                ?.optBoolean("done", true)
            if (pushDone == false) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    message = "pushResult.done=false",
                    rpc = "AntMemberRpcCall.taskQueryPush",
                    detail = goldTicketActionDetail(item, action, source),
                    stopCurrentRound = true
                )
            }

            val amount = item.raw?.optString("amount").orEmpty()
            if (amount.isNotBlank()) {
                Log.member("黄金票🎫[${source}任务领取成功]#${item.title}#+${amount}份")
            } else {
                Log.member("黄金票🎫[${source}任务领取成功]#${item.title}")
            }
            return TaskFlowActionResult.success()
        }

        private fun emptyGoldTicketActionResponse(
            item: TaskFlowItem,
            rpc: String,
            action: String
        ): TaskFlowActionResult {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.RETRYABLE_RPC,
                message = "${action}返回空",
                rpc = rpc,
                detail = goldTicketActionDetail(item, action, source),
                stopCurrentRound = true
            )
        }
    }

    private fun goldTicketActionFailureResult(
        response: JSONObject,
        rpc: String,
        item: TaskFlowItem,
        action: String,
        source: String
    ): TaskFlowActionResult {
        return TaskFlowActionResult.failure(
            failureType = classifyGoldTicketRpcFailure(response),
            code = extractGoldTicketRpcCode(response),
            message = extractGoldTicketRpcMessage(response),
            rpc = rpc,
            raw = response.toString(),
            detail = goldTicketActionDetail(item, action, source)
        )
    }

    private fun goldTicketActionDetail(item: TaskFlowItem, action: String, source: String): String {
        return "source=$source taskId=${item.id} taskName=${item.title} " +
            "status=${item.status} action=$action actionType=${item.actionType}"
    }

    private fun classifyGoldTicketRpcFailure(response: JSONObject): TaskRpcFailureType {
        val code = extractGoldTicketRpcCode(response)
        val message = extractGoldTicketRpcMessage(response)
        return when {
            containsAny(message, "已领取", "已经领取", "重复领取", "重复领奖", "重复完成", "已完成", "任务已完结", "任务已结束") ->
                TaskRpcFailureType.TERMINAL_DONE

            code in setOf("104", "PROMISE_HAS_PROCESSING_TEMPLATE", "CAMP_TRIGGER_ERROR") ||
                code.contains("LIMIT", ignoreCase = true) ||
                containsAny(message, "上限", "限制", "受限", "不可领取", "资格不足", "兑完", "风控", "风险", "模板处理中") ->
                TaskRpcFailureType.BUSINESS_LIMIT

            code == "400000040" ||
                containsAny(message, "不支持rpc调用", "不支持RPC完成") ->
                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

            code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                containsAny(message, "参数错误", "任务ID非法", "模板不存在", "生活记录模板不存在") ->
                TaskRpcFailureType.NON_RETRYABLE_INVALID

            code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK", "SYSTEM_BUSY", "NETWORK_ERROR") ||
                containsAny(message, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试") ||
                isGoldTicketMarkedRetryable(response) ->
                TaskRpcFailureType.RETRYABLE_RPC

            else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
    }

    private fun extractGoldTicketRpcCode(response: JSONObject): String {
        return response.optString("resultCode")
            .ifBlank { response.optString("errorCode") }
            .ifBlank { response.optString("code") }
            .ifBlank { response.optString("errCode") }
    }

    private fun extractGoldTicketRpcMessage(response: JSONObject): String {
        return response.optString("resultDesc")
            .ifBlank { response.optString("memo") }
            .ifBlank { response.optString("desc") }
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("errorMessage") }
            .ifBlank { response.optString("resultView") }
            .ifBlank { response.toString() }
    }

    private fun isGoldTicketMarkedRetryable(response: JSONObject): Boolean {
        return listOf("retryable", "retriable", "canRetry").any { key ->
            response.has(key) && response.optBoolean(key, false)
        }
    }

    /**
     * 黄金票提取逻辑（`queryConsumeHome` + `submitConsume`）
     */
    private fun doGoldTicketConsume() {
        var consumeDone = false
        try {
            Log.member("黄金票🎫[准备检查余额及提取]")

            // 1. 调用新接口 queryConsumeHome 获取最新的资产信息
            val queryRes = AntMemberRpcCall.queryConsumeHome() ?: return
            val queryJson = JSONObject(queryRes)
            if (!ResChecker.checkRes(TAG, queryJson)) return

            val result = queryJson.optJSONObject("result") ?: return

            // 2. 获取余额
            val assetInfo = result.optJSONObject("assetInfo") ?: return

            val availableAmount = assetInfo.optInt("availableAmount", 0)
            val minExchangeAmount = assetInfo.optInt("minExchangeAmount", 100)
            val exchangeAmountUnit = assetInfo.optInt("exchangeAmountUnit", minExchangeAmount).coerceAtLeast(1)

            // 3. 按接口返回的门槛与步长计算提取数量
            val extractAmount = (availableAmount / exchangeAmountUnit) * exchangeAmountUnit

            if (extractAmount < minExchangeAmount) {
                Log.member("黄金票🎫[余额不足] 当前: $availableAmount，最低需$minExchangeAmount")
                consumeDone = true
                return
            }

            // 4. 获取必要参数 productId 和 bonusAmount
            var productId = ""
            val product = result.optJSONObject("product")
            if (product != null) {
                productId = product.optString("productId")
            } else if (result.has("productList") && result.optJSONArray("productList") != null && (result.optJSONArray("productList")?.length()
                    ?: 0) > 0
            ) {
                productId = result.optJSONArray("productList")?.optJSONObject(0)?.optString("productId") ?: ""
            } else if (assetInfo.optJSONArray("mainExchangePrizeList")?.length() ?: 0 > 0) {
                productId = assetInfo.optJSONArray("mainExchangePrizeList")?.optJSONObject(0)?.optString("bizNo") ?: ""
            } else if (assetInfo.optJSONArray("footerExchangePrizeList")?.length() ?: 0 > 0) {
                productId = assetInfo.optJSONArray("footerExchangePrizeList")?.optJSONObject(0)?.optString("bizNo") ?: ""
            } else {
                val backupPrize = assetInfo.optJSONObject("backupPrize")
                if (backupPrize != null && "GOLD".equals(backupPrize.optString("prizeType"), true)) {
                    productId = backupPrize.optString("bizNo")
                }
            }

            if (productId.isEmpty()) {
                Log.error("黄金票🎫[提取异常] 未找到有效的基金ID")
                return
            }

            var bonusAmount = 0
            val bonusInfo = result.optJSONObject("bonusInfo")
            if (bonusInfo != null) {
                bonusAmount = bonusInfo.optInt("bonusAmount", 0)
            }

            // 5. 提交提取
            val exchangeMoney = result.optJSONObject("calcInfo")?.optString("exchangeMoney")
                ?.takeIf { it.isNotBlank() } ?: String.format(Locale.US, "%.2f", extractAmount / 1000.0)
            Log.member("黄金票🎫[开始提取] 计划: $extractAmount 份 => $exchangeMoney 元 (持有: $availableAmount)")
            val submitRes = AntMemberRpcCall.submitConsume(extractAmount, productId, bonusAmount)

            if (submitRes.isNullOrBlank()) {
                Log.error("黄金票🎫[提取失败] 接口无返回")
                return
            }

            val submitJson = JSONObject(submitRes)
            if (!ResChecker.checkRes(TAG, submitJson)) {
                val submitDesc = submitJson.optString("resultDesc", submitJson.optString("memo"))
                if (submitDesc.isNotBlank()) {
                    Log.error("黄金票🎫[提取失败] $submitDesc")
                }
                return
            }

            val submitResult = submitJson.optJSONObject("result")
            val writeOffNo = submitResult?.optString("writeOffNo").orEmpty()
            val successTitle = submitResult?.optString("successTitle").orEmpty()
            if (writeOffNo.isNotBlank() || successTitle.contains("成功")) {
                Log.member("黄金票🎫[提取成功]#$exchangeMoney 元#$extractAmount 份")
                consumeDone = true
            } else {
                Log.error("黄金票🎫[提取失败] 未返回核销码")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        } finally {
            if (consumeDone) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_CONSUME_DONE)
            }
        }
    }

    private suspend fun enableGameCenter() {
        try {
            if (hasFlagToday(StatusFlags.FLAG_ANTMEMBER_GAME_CENTER_DONE)) {
                Log.member("游戏中心🎮[今日已处理，跳过]")
                return
            }

            var signInResult = DailyTaskProcessResult.UNKNOWN_FAILURE
            var platformTaskResult = DailyTaskProcessResult.UNKNOWN_FAILURE
            var pointBallResult = DailyTaskProcessResult.UNKNOWN_FAILURE
            var p2eSignInResult = DailyTaskProcessResult.UNKNOWN_FAILURE

            // 1. 查询签到状态并尝试签到
            try {
                val resp = AntMemberRpcCall.querySignInBall()
                val root = JSONObject(resp)
                if (!ResChecker.checkRes(TAG, root)) {
                    val msg = root.optString("errorMsg", root.optString("resultView", resp))
                    Log.error("$TAG.enableGameCenter.signIn", "游戏中心🎮[签到查询失败]#$msg")
                } else {
                    val data = root.optJSONObject("data")

                    if (data == null || data.length() == 0) {
                        Log.member("游戏中心🎮[签到状态为空，跳过签到]")
                        signInResult = DailyTaskProcessResult.HANDLED
                    } else {
                        val signModule = data.optJSONObject("signInBallModule")
                        if (signModule == null) {
                            Log.member("游戏中心🎮[暂无签到模块]")
                            signInResult = DailyTaskProcessResult.HANDLED
                        } else if (signModule.optBoolean("signInStatus", false)) {
                            Log.member("游戏中心🎮[今日已签到]")
                            signInResult = DailyTaskProcessResult.HANDLED
                        } else {
                            val signResp = AntMemberRpcCall.continueSignIn()
                            val signJo = JSONObject(signResp)
                            if (!ResChecker.checkRes(TAG, signJo)) {
                                val msg = signJo.optString(
                                    "errorMsg", signJo.optString("resultView", signResp)
                                )
                                Log.error("$TAG.enableGameCenter.signIn", "游戏中心🎮[签到失败]#$msg")
                            } else {
                                val signData = signJo.optJSONObject("data")
                                var title = ""
                                var desc = ""
                                var type = ""
                                if (signData != null) {
                                    val toast = signData.optJSONObject("autoSignInToastModule")
                                    if (toast != null) {
                                        title = toast.optString("title", "")
                                        desc = toast.optString("desc", "")
                                        type = toast.optString("type", "")
                                    }
                                }
                                val toastSuccess = "SUCCESS".equals(type, ignoreCase = true) && !title.contains("失败") && !desc.contains("失败")
                                if (toastSuccess) {
                                    val sb = StringBuilder()
                                    sb.append("游戏中心🎮[每日签到成功]")
                                    if (!title.isEmpty()) {
                                        sb.append("#").append(title)
                                    }
                                    if (!desc.isEmpty()) {
                                        sb.append("#").append(desc)
                                    }
                                    Log.member(sb.toString())
                                    signInResult = DailyTaskProcessResult.HANDLED
                                } else {
                                    val sb = StringBuilder()
                                    if (!title.isEmpty()) {
                                        sb.append(title)
                                    }
                                    if (!desc.isEmpty()) {
                                        if (sb.isNotEmpty()) sb.append(" ")
                                        sb.append(desc)
                                    }
                                    Log.error(
                                        "$TAG.enableGameCenter.signIn", "游戏中心🎮[签到失败]#" + (if (sb.isNotEmpty()) sb.toString() else signResp)
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "enableGameCenter.signIn err:", th)
            }

            // 2. 查询任务列表,完成平台任务
            try {
                platformTaskResult = runGameCenterPlatformTaskFlow()
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "enableGameCenter.tasks err:", th)
            }

            // 3. 查询待收乐豆并使用一键收取接口
            try {
                val resp = AntMemberRpcCall.queryPointBallList()
                val root = JSONObject(resp)
                if (!ResChecker.checkRes(TAG, root)) {
                    val msg = root.optString("errorMsg", root.optString("resultView", resp))
                    Log.error("$TAG.enableGameCenter.point", "游戏中心🎮[查询待收乐豆失败]#$msg")
                } else {
                    val data = root.optJSONObject("data")
                    val pointBallList = data?.optJSONArray("pointBallList")
                    if (pointBallList == null || pointBallList.length() == 0) {
                        Log.member("游戏中心🎮[暂无可领取乐豆]")
                        pointBallResult = DailyTaskProcessResult.HANDLED
                    } else {
                        val batchResp = AntMemberRpcCall.batchReceivePointBall()
                        val batchJo = JSONObject(batchResp)
                        if (ResChecker.checkRes(TAG, batchJo)) {
                            val batchData = batchJo.optJSONObject("data")
                            val receiveAmount = batchData?.optInt("receiveAmount", 0) ?: 0
                            val totalAmount = batchData?.optInt("totalAmount", receiveAmount) ?: receiveAmount
                            if (receiveAmount > 0) {
                                Log.member("游戏中心🎮[一键领取乐豆成功]#本次领取" + receiveAmount + " | 当前累计" + totalAmount + "玩乐豆")
                            } else {
                                Log.member("游戏中心🎮[暂无可领取乐豆]")
                            }
                            pointBallResult = DailyTaskProcessResult.HANDLED
                        } else {
                            val msg = batchJo.optString(
                                "errorMsg", batchJo.optString("resultView", batchResp)
                            )
                            Log.error(
                                "$TAG.enableGameCenter.point", "游戏中心🎮[一键领取乐豆失败]#$msg"
                            )
                        }
                    }
                }
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "enableGameCenter.point err:", th)
            }

            // 4. 游戏中心赚现金签到
            try {
                p2eSignInResult = doGameCenterP2eSignIn()
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "enableGameCenter.p2eSignIn err:", th)
            }

            if (listOf(
                    signInResult,
                    platformTaskResult,
                    pointBallResult,
                    p2eSignInResult
                ).all { it == DailyTaskProcessResult.HANDLED }
            ) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_GAME_CENTER_DONE)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        }
    }

    private fun runGameCenterPlatformTaskFlow(): DailyTaskProcessResult {
        val adapter = GameCenterPlatformTaskFlowAdapter()
        val runResult = TaskFlowEngine(adapter, roundSleepMs = 500L).run()
        if (adapter.taskCount == 0) {
            Log.member("游戏中心🎮[暂无平台任务模块]")
        } else if (adapter.availableTaskCount == 0) {
            Log.member("游戏中心🎮[无待处理的平台任务]")
        }
        return when {
            adapter.queryFailed || adapter.hasBlockingFailure -> DailyTaskProcessResult.UNKNOWN_FAILURE
            adapter.hasRetryableFailure || runResult.stopped -> DailyTaskProcessResult.RETRYABLE_FAILURE
            else -> DailyTaskProcessResult.HANDLED
        }
    }

    private inner class GameCenterPlatformTaskFlowAdapter : TaskFlowAdapter {
        override val moduleName: String = memberTaskBlacklistModule
        override val flowName: String = "游戏中心🎮平台任务"

        var queryFailed: Boolean = false
            private set
        var hasBlockingFailure: Boolean = false
            private set
        var hasRetryableFailure: Boolean = false
            private set
        var taskCount: Int = 0
            private set
        var availableTaskCount: Int = 0
            private set

        private val signedUpTaskIds = LinkedHashSet<String>()
        private val completedTaskIds = LinkedHashSet<String>()
        private val loggedSkipKeys = LinkedHashSet<String>()

        override fun query(): JSONObject {
            return try {
                val raw = AntMemberRpcCall.queryGameCenterTaskList()
                val response = JSONObject(raw)
                response
                    .put("_taskFlowQuerySuccess", ResChecker.checkRes(TAG, response))
                    .put("_rawResponse", raw)
            } catch (t: Throwable) {
                JSONObject()
                    .put("_taskFlowQuerySuccess", false)
                    .put("resultView", "查询异常:${t.message}")
            }
        }

        override fun isQuerySuccess(response: JSONObject): Boolean {
            return response.optBoolean("_taskFlowQuerySuccess", false)
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val data = response.optJSONObject("data") ?: return emptyList()
            val platformTaskModule = data.optJSONObject("gameTaskModule")
                ?: data.optJSONObject("platformTaskModule")
                ?: return emptyList()
            val platformTaskList = platformTaskModule.optJSONArray("gameTaskList")
                ?: platformTaskModule.optJSONArray("platformTaskList")
                ?: return emptyList()
            taskCount = max(taskCount, platformTaskList.length())
            availableTaskCount = 0
            val items = mutableListOf<TaskFlowItem>()
            for (i in 0 until platformTaskList.length()) {
                val task = platformTaskList.optJSONObject(i) ?: continue
                val taskId = task.optString("taskId")
                if (taskId.isBlank()) {
                    continue
                }
                val title = task.optString("title").ifBlank {
                    task.optString("subTitle").ifBlank { taskId }
                }
                val status = task.optString("taskStatus")
                if (status == "NOT_DONE" || status == "SIGNUP_COMPLETE") {
                    availableTaskCount++
                }
                items.add(
                    TaskFlowItem(
                        id = taskId,
                        title = title,
                        status = status,
                        type = if (task.optBoolean("needSignUp", false)) "SIGNUP_TASK" else "PLATFORM_TASK",
                        actionType = "doTaskSend",
                        blacklistKeys = listOf(taskId, title).filter { it.isNotBlank() },
                        raw = task,
                        progress = "pointAmount=${task.optInt("pointAmount", 0)}"
                    )
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            if (item.id.isBlank()) {
                return TaskFlowPhase.UNKNOWN
            }
            if (item.id in completedTaskIds) {
                return TaskFlowPhase.TERMINAL
            }
            return when (item.status.uppercase(Locale.ROOT)) {
                "NOT_DONE" -> if (item.raw?.optBoolean("needSignUp", false) == true && item.id !in signedUpTaskIds) {
                    TaskFlowPhase.SIGNUP_REQUIRED
                } else {
                    TaskFlowPhase.READY_TO_COMPLETE
                }
                "SIGNUP_COMPLETE" -> TaskFlowPhase.SIGNUP_COMPLETE
                "RECEIVED",
                "DONE",
                "FINISHED",
                "COMPLETE",
                "COMPLETED",
                "SUCCESS" -> TaskFlowPhase.TERMINAL
                else -> TaskFlowPhase.UNKNOWN
            }
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            return item.id in completedTaskIds
        }

        override fun isBlacklisted(item: TaskFlowItem): Boolean {
            val blacklisted = super<TaskFlowAdapter>.isBlacklisted(item)
            if (blacklisted) {
                logSkipOnce(item, "黑名单任务，跳过")
            }
            return blacklisted
        }

        override fun signup(item: TaskFlowItem): TaskFlowActionResult {
            val response = AntMemberRpcCall.doTaskSignup(item.id)
            val responseObject = JSONObject(response)
            if (!ResChecker.checkRes(TAG, responseObject)) {
                return gameCenterTaskFailureResult(
                    item = item,
                    responseObject = responseObject,
                    rawResponse = response,
                    rpc = "AntMemberRpcCall.doTaskSignup"
                )
            }
            Log.member("游戏中心🎮任务[${item.title}]#报名完成")
            return TaskFlowActionResult.success()
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            return sendGameCenterTask(item)
        }

        override fun send(item: TaskFlowItem): TaskFlowActionResult {
            return sendGameCenterTask(item)
        }

        override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
            return "${action.logName}:${item.id}:${item.status}"
        }

        override fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
            when (action) {
                TaskFlowAction.SIGNUP -> signedUpTaskIds.add(item.id)
                TaskFlowAction.COMPLETE,
                TaskFlowAction.SEND,
                TaskFlowAction.RECEIVE -> completedTaskIds.add(item.id)
            }
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision
        ) {
            when (decision) {
                TaskFlowDecision.RETRY_LATER -> hasRetryableFailure = true
                TaskFlowDecision.LOG_ONLY -> hasBlockingFailure = true
                TaskFlowDecision.MARK_HANDLED -> completedTaskIds.add(item.id)
                TaskFlowDecision.STOP_TODAY_OR_CURRENT_CHAIN,
                TaskFlowDecision.BLACKLIST -> Unit
            }
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            logInfo("游戏中心🎮[平台任务已处理完成：${snapshot.completedTasks}/${snapshot.totalTasks}]")
        }

        override fun onQueryFailed(response: JSONObject) {
            queryFailed = true
            val msg = response.optString("errorMsg")
                .ifBlank { response.optString("resultView") }
                .ifBlank { response.optString("resultDesc") }
                .ifBlank { response.optString("_rawResponse", response.toString()) }
            Log.error("$TAG.enableGameCenter.tasks", "游戏中心🎮[任务列表查询失败]#$msg")
        }

        override fun onUnknownPhase(item: TaskFlowItem, phase: TaskFlowPhase) {
            hasBlockingFailure = true
            Log.error(
                "$TAG.enableGameCenter.tasks",
                "游戏中心🎮任务[${item.title}]未知状态 taskId=${item.id} status=${item.status} raw=${item.raw}"
            )
        }

        override fun logInfo(message: String) {
            Log.member(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun sendGameCenterTask(item: TaskFlowItem): TaskFlowActionResult {
            val response = AntMemberRpcCall.doTaskSend(item.id)
            val responseObject = JSONObject(response)
            if (!ResChecker.checkRes(TAG, responseObject)) {
                return gameCenterTaskFailureResult(
                    item = item,
                    responseObject = responseObject,
                    rawResponse = response,
                    rpc = "AntMemberRpcCall.doTaskSend"
                )
            }
            val resultStatus = responseObject.optJSONObject("data")?.optString("taskStatus").orEmpty()
            if (resultStatus == "SIGNUP_COMPLETE" || resultStatus == "NOT_DONE") {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    code = "STATUS_UNCHANGED",
                    message = "任务状态未变更",
                    rpc = "AntMemberRpcCall.doTaskSend",
                    raw = response,
                    detail = gameCenterTaskActionDetail(item, "send"),
                    stopCurrentRound = true
                )
            }
            val task = item.raw ?: JSONObject()
            val title = task.optString("subTitle").ifBlank { item.title }
            val pointAmount = task.optInt("pointAmount", 0)
            Log.member(
                "游戏中心🎮任务[$title]#完成,奖励${pointAmount}玩乐豆" +
                    if (task.optBoolean("needSignUp", false)) "(签到任务)" else ""
            )
            return TaskFlowActionResult.success()
        }

        private fun logSkipOnce(item: TaskFlowItem, reason: String) {
            val key = "$reason|${item.id}"
            if (loggedSkipKeys.add(key)) {
                Log.member("游戏中心🎮任务[${item.title}]#$reason")
            }
        }
    }

    private fun gameCenterTaskFailureResult(
        item: TaskFlowItem,
        responseObject: JSONObject,
        rawResponse: String,
        rpc: String
    ): TaskFlowActionResult {
        return memberDomainTaskFailureResult(
            item = item,
            responseObject = responseObject,
            rawResponse = rawResponse,
            rpc = rpc,
            detail = gameCenterTaskActionDetail(item, rpc.substringAfterLast('.'))
        )
    }

    private fun gameCenterTaskActionDetail(item: TaskFlowItem, action: String): String {
        val task = item.raw ?: JSONObject()
        return "taskId=${item.id.ifBlank { "UNKNOWN" }} taskName=${item.title.ifBlank { "UNKNOWN" }} " +
            "status=${item.status.ifBlank { "UNKNOWN" }} action=$action " +
            "needSignUp=${task.optBoolean("needSignUp", false)} pointAmount=${task.optInt("pointAmount", 0)}"
    }

    private suspend fun doGameCenterP2eSignIn(): DailyTaskProcessResult {
        val resp = AntMemberRpcCall.queryGameCenterP2eHomePage()
        val root = JSONObject(resp)
        if (!ResChecker.checkRes(TAG, root)) {
            return logGameCenterP2eFailure("赚现金签到查询", root, resp)
        }

        val data = root.optJSONObject("data")
        val signUpModule = data?.optJSONObject("signUpModuleVO")
        if (signUpModule == null) {
            val riskMsg = data?.optString("hitRiskControlMsg").orEmpty()
            if (data?.optBoolean("hitRiskControl", false) == true || riskMsg.isNotBlank()) {
                Log.member("游戏中心🎮[赚现金签到业务受限，跳过]#$riskMsg")
            } else {
                Log.member("游戏中心🎮[赚现金暂无签到模块]")
            }
            return DailyTaskProcessResult.HANDLED
        }

        val todayRecord = findGameCenterP2eTodaySignRecord(signUpModule)
        val todayStatus = todayRecord?.optString("signUpStatus").orEmpty()
        if ("SIGNED".equals(todayStatus, ignoreCase = true)) {
            val amount = todayRecord?.optString("todayGoldCoinAmount").orEmpty()
            Log.member("游戏中心🎮[赚现金今日已签到]" + if (amount.isNotBlank()) "#金币+$amount" else "")
            return DailyTaskProcessResult.HANDLED
        }

        val date = signUpModule.optString("date")
        val index = signUpModule.optInt("index", 0)
        val signSequenceId = signUpModule.optString("signSequenceId")
        if (date.isBlank() || signSequenceId.isBlank()) {
            Log.error(
                "$TAG.enableGameCenter.p2eSignIn",
                "游戏中心🎮[赚现金签到配置缺失]#date=$date index=$index signSequenceId=$signSequenceId"
            )
            return DailyTaskProcessResult.UNKNOWN_FAILURE
        }

        val signResp = AntMemberRpcCall.gameCenterP2eSignIn(date, index, signSequenceId)
        val signObject = JSONObject(signResp)
        if (!ResChecker.checkRes(TAG, signObject)) {
            return logGameCenterP2eFailure("赚现金签到", signObject, signResp)
        }

        val signedRecord = findGameCenterP2eTodaySignRecord(
            signObject.optJSONObject("data")?.optJSONObject("signUpPopupModuleVO")
        )
        val signedStatus = signedRecord?.optString("signUpStatus").orEmpty()
        val amount = signedRecord?.optString("todayGoldCoinAmount").orEmpty()
        if ("SIGNED".equals(signedStatus, ignoreCase = true) || signObject.optBoolean("success", false)) {
            Log.member("游戏中心🎮[赚现金签到成功]" + if (amount.isNotBlank()) "#金币+$amount" else "")
            return DailyTaskProcessResult.HANDLED
        } else {
            Log.error(
                "$TAG.enableGameCenter.p2eSignIn",
                "游戏中心🎮[赚现金签到状态未确认]#" + buildGameCenterRpcMessage(signObject, signResp)
            )
            return DailyTaskProcessResult.UNKNOWN_FAILURE
        }
    }

    private fun findGameCenterP2eTodaySignRecord(signUpModule: JSONObject?): JSONObject? {
        if (signUpModule == null) {
            return null
        }
        val signDate = signUpModule.optString("date")
        val records = signUpModule.optJSONArray("signRecordVOList") ?: return null
        var dateMatchedRecord: JSONObject? = null
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            if (record.optBoolean("isToday", false)) {
                return record
            }
            if (signDate.isNotBlank() && signDate == record.optString("signDate")) {
                dateMatchedRecord = record
            }
        }
        return dateMatchedRecord
    }

    private fun logGameCenterP2eFailure(
        scene: String,
        response: JSONObject,
        rawResponse: String
    ): DailyTaskProcessResult {
        val message = buildGameCenterRpcMessage(response, rawResponse)
        return when {
            isGameCenterBusinessLimited(response, message) -> {
                Log.member("游戏中心🎮[$scene]#业务受限，本轮跳过:$message")
                DailyTaskProcessResult.HANDLED
            }

            isGameCenterDuplicateOrAlreadyDone(message) -> {
                Log.member("游戏中心🎮[$scene]#已处理过，跳过重复处理:$message")
                DailyTaskProcessResult.HANDLED
            }

            !response.optBoolean("retryable", true) -> {
                Log.error("$TAG.enableGameCenter.p2eSignIn", "游戏中心🎮[$scene]#非重试失败:$message")
                DailyTaskProcessResult.UNKNOWN_FAILURE
            }

            else -> {
                Log.error("$TAG.enableGameCenter.p2eSignIn", "游戏中心🎮[$scene]#失败:$message")
                DailyTaskProcessResult.RETRYABLE_FAILURE
            }
        }
    }

    private fun buildGameCenterRpcMessage(response: JSONObject, rawResponse: String): String {
        return sequenceOf(
            response.optString("errorMsg"),
            response.optString("errorMessage"),
            response.optString("resultView"),
            response.optString("resultDesc"),
            response.optString("memo"),
            response.optString("desc")
        ).firstOrNull { it.isNotBlank() } ?: rawResponse
    }

    private fun isGameCenterBusinessLimited(response: JSONObject, message: String): Boolean {
        val errorCode = response.optString("errorCode", response.optString("resultCode"))
        return errorCode.equals("PROMO_RISK_ERROR", ignoreCase = true) ||
            message.contains("不在活动邀请范围") ||
            message.contains("风险") ||
            message.contains("风控") ||
            message.contains("受限")
    }

    private fun isGameCenterDuplicateOrAlreadyDone(message: String): Boolean {
        return message.contains("已签到") ||
            message.contains("已领取") ||
            message.contains("重复") ||
            message.contains("already", ignoreCase = true)
    }

    internal fun beanSignIn() {
        try {
            if (hasFlagToday(StatusFlags.FLAG_ANTMEMBER_BEAN_SIGN_DONE)) {
                Log.member("安心豆🫘[今日已处理，跳过]")
                return
            }

            try {
                val signInProcessStr = AntMemberRpcCall.querySignInProcess("AP16242232", "INS_BLUE_BEAN_SIGN")

                var jo = JSONObject(signInProcessStr)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.member(jo.toString())
                    return
                }

                val signInResult = jo.optJSONObject("result")
                if (signInResult == null) {
                    Log.error(TAG, "安心豆🫘[签到查询缺少result]#$signInProcessStr")
                    return
                }
                var signInHandled = !signInResult.optBoolean("canPush", false)
                if (signInResult.optBoolean("canPush") == true) {
                    val signInTriggerStr = AntMemberRpcCall.signInTrigger("AP16242232", "INS_BLUE_BEAN_SIGN")

                    jo = JSONObject(signInTriggerStr)
                    if (ResChecker.checkRes(TAG, jo)) {
                        val prizeName = extractBeanSignInPrizeName(jo)
                        if (prizeName.isBlank()) {
                            Log.member("安心豆🫘[签到成功]")
                        } else {
                            Log.member("安心豆🫘[$prizeName]")
                        }
                        signInHandled = true
                    } else {
                        Log.member(jo.toString())
                    }
                }
                val guardianAwardResult = collectGuardianBeanAward()
                val taskCenterResult = processBeanTaskCenter()
                if (signInHandled &&
                    guardianAwardResult == DailyTaskProcessResult.HANDLED &&
                    taskCenterResult == DailyTaskProcessResult.HANDLED
                ) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_BEAN_SIGN_DONE)
                }
            } catch (e: NullPointerException) {
                Log.printStackTrace(TAG, "安心豆🫘[RPC桥接失败]#可能是RpcBridge未初始化", e)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "beanSignIn err:", t)
        }
    }

    private fun extractBeanSignInPrizeName(responseObject: JSONObject): String {
        val prizeList = responseObject.optJSONObject("result")?.optJSONArray("prizeSendOrderDTOList") ?: return ""
        for (i in 0 until prizeList.length()) {
            val prizeName = prizeList.optJSONObject(i)?.optString("prizeName").orEmpty()
            if (prizeName.isNotBlank()) {
                return prizeName
            }
        }
        return ""
    }

    private fun collectGuardianBeanAward(): DailyTaskProcessResult {
        try {
            val awardsResponse = AntMemberRpcCall.queryGuardianGradeAwards()
            val awardsObject = JSONObject(awardsResponse)
            if (!ResChecker.checkRes(TAG, awardsObject)) {
                Log.member("安心豆🫘[守护者奖励查询失败]#$awardsResponse")
                return DailyTaskProcessResult.UNKNOWN_FAILURE
            }
            if (awardsObject.optJSONObject("result") == null) {
                Log.error("$TAG.collectGuardianBeanAward", "安心豆🫘[守护者奖励查询缺少result]#$awardsResponse")
                return DailyTaskProcessResult.UNKNOWN_FAILURE
            }
            val award = findAvailableGuardianBeanAward(awardsObject)
            if (award == null) {
                logUnavailableGuardianBeanAward(awardsObject)
                return DailyTaskProcessResult.HANDLED
            }
            val skuId = award.optString("skuId")
            val beanQuantity = award.optInt("beanQuantity", 0)
            if (skuId.isBlank() || beanQuantity <= 0) {
                Log.error("$TAG.collectGuardianBeanAward", "安心豆🫘[守护者奖励配置异常]#$award")
                return DailyTaskProcessResult.UNKNOWN_FAILURE
            }
            val sendResponse = AntMemberRpcCall.guardianAwardSend(skuId)
            val sendObject = JSONObject(sendResponse)
            if (ResChecker.checkRes(TAG, sendObject)) {
                Log.member("安心豆🫘[守护者等级奖励]#${beanQuantity}豆")
                return DailyTaskProcessResult.HANDLED
            } else {
                return logGuardianBeanAwardSendFailure(sendObject, sendResponse)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "collectGuardianBeanAward err:", t)
            return DailyTaskProcessResult.UNKNOWN_FAILURE
        }
    }

    private fun findAvailableGuardianBeanAward(responseObject: JSONObject): JSONObject? {
        val gradeAwardsList = responseObject.optJSONObject("result")?.optJSONArray("gradeSkuAwardsList") ?: return null
        for (i in 0 until gradeAwardsList.length()) {
            val skuAwardList = gradeAwardsList.optJSONObject(i)?.optJSONArray("skuAwardList") ?: continue
            for (j in 0 until skuAwardList.length()) {
                val award = skuAwardList.optJSONObject(j) ?: continue
                if (award.optString("status") == "AVAILABLE" &&
                    award.optString("spuType") == "MARKETING_PRIZE" &&
                    award.optInt("beanQuantity", 0) > 0
                ) {
                    return award
                }
            }
        }
        return null
    }

    private fun logUnavailableGuardianBeanAward(responseObject: JSONObject) {
        val gradeAwardsList = responseObject.optJSONObject("result")?.optJSONArray("gradeSkuAwardsList") ?: return
        for (i in 0 until gradeAwardsList.length()) {
            val skuAwardList = gradeAwardsList.optJSONObject(i)?.optJSONArray("skuAwardList") ?: continue
            for (j in 0 until skuAwardList.length()) {
                val award = skuAwardList.optJSONObject(j) ?: continue
                val beanQuantity = award.optInt("beanQuantity", 0)
                val status = award.optString("status")
                if (award.optString("spuType") == "MARKETING_PRIZE" &&
                    beanQuantity > 0 &&
                    status == "MONTH_COUNT_LIMIT"
                ) {
                    Log.member("安心豆🫘[守护者等级奖励]#${beanQuantity}豆，业务受限($status)，跳过")
                    return
                }
            }
        }
    }

    private fun logGuardianBeanAwardSendFailure(
        responseObject: JSONObject,
        rawResponse: String
    ): DailyTaskProcessResult {
        val code = sequenceOf(
            responseObject.optString("resultCode"),
            responseObject.optString("code"),
            responseObject.optString("errorCode")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val message = sequenceOf(
            responseObject.optString("resultDesc"),
            responseObject.optString("resultMsg"),
            responseObject.optString("memo"),
            responseObject.optString("errorMessage"),
            responseObject.optString("errorMsg"),
            responseObject.optString("desc")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val detail = when {
            code.isNotBlank() && message.isNotBlank() -> "$code/$message"
            code.isNotBlank() -> code
            message.isNotBlank() -> message
            else -> rawResponse
        }
        return when (classifyGuardianBeanAwardFailure(code, message)) {
            GuardianBeanAwardRpcFailureType.DUPLICATE_REWARD -> {
                Log.member("安心豆🫘[守护者等级奖励]#已领取或重复领取，跳过:$detail")
                DailyTaskProcessResult.HANDLED
            }

            GuardianBeanAwardRpcFailureType.BUSINESS_LIMIT -> {
                Log.member("安心豆🫘[守护者等级奖励]#业务受限，本轮跳过:$detail")
                DailyTaskProcessResult.HANDLED
            }

            GuardianBeanAwardRpcFailureType.RETRYABLE -> {
                Log.member("安心豆🫘[守护者等级奖励]#暂时不可领取，保留后续重试:$detail")
                DailyTaskProcessResult.RETRYABLE_FAILURE
            }

            GuardianBeanAwardRpcFailureType.NON_RETRYABLE -> {
                Log.error("$TAG.collectGuardianBeanAward", "安心豆🫘[守护者奖励领取失败]#$detail")
                DailyTaskProcessResult.UNKNOWN_FAILURE
            }
        }
    }

    private fun classifyGuardianBeanAwardFailure(code: String, message: String): GuardianBeanAwardRpcFailureType {
        return when {
            message.contains("已领取") ||
                message.contains("重复") ||
                message.contains("已经领取") -> GuardianBeanAwardRpcFailureType.DUPLICATE_REWARD

            message.contains("稍后") ||
                message.contains("频繁") ||
                message.contains("繁忙") -> GuardianBeanAwardRpcFailureType.RETRYABLE

            code.contains("LIMIT", ignoreCase = true) ||
                message.contains("上限") ||
                message.contains("限制") ||
                message.contains("受限") ||
                message.contains("不可领取") -> GuardianBeanAwardRpcFailureType.BUSINESS_LIMIT

            else -> GuardianBeanAwardRpcFailureType.NON_RETRYABLE
        }
    }

    private fun processBeanTaskCenter(): DailyTaskProcessResult {
        var result = DailyTaskProcessResult.HANDLED
        result = mergeDailyTaskProcessResult(result, consultGuardianAnswerTask())
        result = mergeDailyTaskProcessResult(result, queryBeanTaskCenterStatus())
        return result
    }

    private fun consultGuardianAnswerTask(): DailyTaskProcessResult {
        return try {
            val response = AntMemberRpcCall.guardianAnswerConsult()
            val responseObject = JSONObject(response)
            if (!ResChecker.checkRes(TAG, responseObject)) {
                val detail = buildBeanTaskCenterFailureDetail(responseObject, response)
                val failureType = classifyBeanTaskCenterFailure(detail.first, detail.second)
                return when (failureType) {
                    GuardianBeanAwardRpcFailureType.BUSINESS_LIMIT -> {
                        Log.member("安心豆🫘[保险知识闯关]#业务受限，本轮跳过:${detailToString(detail, response)}")
                        DailyTaskProcessResult.HANDLED
                    }

                    GuardianBeanAwardRpcFailureType.DUPLICATE_REWARD -> {
                        Log.member("安心豆🫘[保险知识闯关]#已处理过，跳过:${detailToString(detail, response)}")
                        DailyTaskProcessResult.HANDLED
                    }

                    GuardianBeanAwardRpcFailureType.RETRYABLE -> {
                        Log.member("安心豆🫘[保险知识闯关]#暂时不可查询，保留后续重试:${detailToString(detail, response)}")
                        DailyTaskProcessResult.RETRYABLE_FAILURE
                    }

                    GuardianBeanAwardRpcFailureType.NON_RETRYABLE -> {
                        Log.error("$TAG.consultGuardianAnswerTask", "安心豆🫘[保险知识闯关]#查询失败:${detailToString(detail, response)}")
                        DailyTaskProcessResult.UNKNOWN_FAILURE
                    }
                }
            }

            val answerStatus = responseObject.optJSONObject("result")?.optString("answerStatus")
                ?: responseObject.optString("answerStatus")
            when (answerStatus) {
                "ANSWER_PENDING" -> Log.member("安心豆🫘[保险知识闯关]#发现待答题，缺少题目提交闭环RPC，暂不自动答题")
                "ANSWERED",
                "ANSWER_SUCCESS",
                "RECEIVE_SUCCESS" -> Log.member("安心豆🫘[保险知识闯关]#状态已完成:$answerStatus")
                "" -> Log.member("安心豆🫘[保险知识闯关]#未返回答题状态，保留后续日志复核")
                else -> Log.member("安心豆🫘[保险知识闯关]#当前状态:$answerStatus")
            }
            DailyTaskProcessResult.HANDLED
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "consultGuardianAnswerTask err:", t)
            DailyTaskProcessResult.UNKNOWN_FAILURE
        }
    }

    private fun queryBeanTaskCenterStatus(): DailyTaskProcessResult {
        return try {
            val response = AntMemberRpcCall.beanTaskCenterConsult(
                taskCenterId = "AP15241780",
                sceneCode = "AXD_TAK_LIST",
                entrance = "insplatform_mine_anxindou"
            )
            val responseObject = JSONObject(response)
            if (!ResChecker.checkRes(TAG, responseObject)) {
                val detail = buildBeanTaskCenterFailureDetail(responseObject, response)
                return when (classifyBeanTaskCenterFailure(detail.first, detail.second)) {
                    GuardianBeanAwardRpcFailureType.BUSINESS_LIMIT -> {
                        Log.member("安心豆🫘[任务中心]#业务受限，本轮跳过:${detailToString(detail, response)}")
                        DailyTaskProcessResult.HANDLED
                    }

                    GuardianBeanAwardRpcFailureType.DUPLICATE_REWARD -> {
                        Log.member("安心豆🫘[任务中心]#已领取或重复领取，跳过:${detailToString(detail, response)}")
                        DailyTaskProcessResult.HANDLED
                    }

                    GuardianBeanAwardRpcFailureType.RETRYABLE -> {
                        Log.member("安心豆🫘[任务中心]#暂时不可查询，保留后续重试:${detailToString(detail, response)}")
                        DailyTaskProcessResult.RETRYABLE_FAILURE
                    }

                    GuardianBeanAwardRpcFailureType.NON_RETRYABLE -> {
                        Log.error("$TAG.queryBeanTaskCenterStatus", "安心豆🫘[任务中心]#查询失败:${detailToString(detail, response)}")
                        DailyTaskProcessResult.UNKNOWN_FAILURE
                    }
                }
            }

            val resultObject = responseObject.optJSONObject("result") ?: responseObject.optJSONObject("data")
            if (resultObject == null) {
                Log.error("$TAG.queryBeanTaskCenterStatus", "安心豆🫘[任务中心]#响应缺少result/data:$response")
                return DailyTaskProcessResult.UNKNOWN_FAILURE
            }
            val taskCount = logBeanTaskCenterTaskList(resultObject.optJSONArray("taskDetailList"))
            val doneTaskCount = logBeanTaskCenterTaskList(resultObject.optJSONArray("doneTaskDetailList"))
            if (taskCount == 0 && doneTaskCount == 0) {
                Log.member("安心豆🫘[任务中心]#无可识别任务")
            }
            DailyTaskProcessResult.HANDLED
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryBeanTaskCenterStatus err:", t)
            DailyTaskProcessResult.UNKNOWN_FAILURE
        }
    }

    private fun logBeanTaskCenterTaskList(taskList: JSONArray?): Int {
        if (taskList == null) {
            return 0
        }
        var count = 0
        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            count++
            val taskId = resolveBeanTaskCenterTaskId(task)
            val title = resolveBeanTaskCenterTaskTitle(task, taskId)
            val status = task.optString("taskProcessStatus")
            val errorCode = task.optString("queryErrorCode")
            val errorMsg = task.optString("queryErrorMsg")
            when {
                status.equals("RECEIVE_SUCCESS", true) -> {
                    Log.member("安心豆🫘[任务中心]#$title($taskId) 已完成/已领取")
                }

                status.equals("WAIT_RECEIVE", true) ||
                    status.equals("TO_RECEIVE", true) ||
                    status.equals("FINISHED", true) -> {
                    Log.member("安心豆🫘[任务中心]#$title($taskId) 待领取，但当前未抓到明确领奖RPC，保留后续复核")
                }

                errorCode in setOf("10001013", "10001034") -> {
                    val detail = if (errorMsg.isNotBlank()) "$errorCode/$errorMsg" else errorCode
                    Log.member("安心豆🫘[任务中心]#$title($taskId) 业务受限，跳过:$detail")
                }

                status.isNotBlank() -> {
                    Log.member("安心豆🫘[任务中心]#$title($taskId) 当前状态:$status")
                }
            }
        }
        return count
    }

    private fun resolveBeanTaskCenterTaskId(task: JSONObject): String {
        return sequenceOf(
            task.optString("taskId"),
            task.optString("taskConfigId"),
            task.optString("id"),
            task.optJSONObject("taskBaseInfo")?.optString("taskId").orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun resolveBeanTaskCenterTaskTitle(task: JSONObject, taskId: String): String {
        val displayInfo = task.optJSONObject("taskDisplayInfo")
        return sequenceOf(
            displayInfo?.optString("taskMainTitle").orEmpty(),
            displayInfo?.optString("taskTitle").orEmpty(),
            task.optString("taskMainTitle"),
            task.optString("taskTitle"),
            task.optString("title")
        ).firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "任务$taskId" }
    }

    private fun buildBeanTaskCenterFailureDetail(responseObject: JSONObject, rawResponse: String): Pair<String, String> {
        val code = sequenceOf(
            responseObject.optString("resultCode"),
            responseObject.optString("code"),
            responseObject.optString("errorCode"),
            responseObject.optString("errCode")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val message = sequenceOf(
            responseObject.optString("resultDesc"),
            responseObject.optString("resultMsg"),
            responseObject.optString("memo"),
            responseObject.optString("errorMessage"),
            responseObject.optString("errorMsg"),
            responseObject.optString("desc")
        ).firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { rawResponse }
        return code to message
    }

    private fun classifyBeanTaskCenterFailure(code: String, message: String): GuardianBeanAwardRpcFailureType {
        return classifyGuardianBeanAwardFailure(code, message)
    }

    private fun detailToString(detail: Pair<String, String>, rawResponse: String): String {
        val (code, message) = detail
        return when {
            code.isNotBlank() && message.isNotBlank() -> "$code/$message"
            code.isNotBlank() -> code
            message.isNotBlank() -> message
            else -> rawResponse
        }
    }

    internal fun beanExchangeRight() {
        try {
            val selectedIds: Set<String> = beanExchangeRightList?.value
                ?.filterNotNull()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
            val userId = UserMap.currentUid
            val candidateMap = queryBeanExchangeCandidates(queryBlueBeanBalance())
            val beanRightMap = IdMapManager.getInstance(BeanExchangeRightMap::class.java)
            if (candidateMap.isEmpty()) {
                beanRightMap.save(userId)
                Log.member("安心豆🫘未获取到可兑换权益列表")
                return
            }
            val remainingSelectedIds: MutableSet<String>? = if (selectedIds.isNotEmpty()) selectedIds.toMutableSet() else null
            candidateMap.values.forEach { candidate ->
                beanRightMap.add(candidate.item.id, candidate.item.displayName())
                if (!selectedIds.contains(candidate.item.id)) {
                    return@forEach
                }
                remainingSelectedIds?.remove(candidate.item.id)
                when (candidate.item.safety) {
                    ExchangeSafety.UNAVAILABLE -> {
                        Log.member("安心豆🫘跳过[${candidate.item.displayName()}]#${candidate.item.safetyReason}")
                    }
                    ExchangeSafety.LOG_ONLY -> {
                        Log.member("安心豆🫘已勾选[${candidate.item.displayName()}]#仅提醒，不自动兑换")
                    }
                    ExchangeSafety.AUTO -> {
                        val detailResp = JSONObject(AntMemberRpcCall.queryRightsDetail(candidate.rightsId))
                        if (!ResChecker.checkRes(TAG, "安心豆权益详情查询失败:", detailResp)) {
                            Log.member("安心豆🫘兑换前详情校验失败[${candidate.item.name}]")
                            return@forEach
                        }
                        val exchangeResult = JSONObject(
                            AntMemberRpcCall.rightsExchange(candidate.rightsId, candidate.assetAmount, candidate.needOrder)
                        )
                        if (ResChecker.checkRes(TAG, "安心豆权益兑换失败:", exchangeResult)) {
                            Log.member("安心豆🫘兑换[${candidate.item.name}]#消耗${candidate.assetAmount}安心豆")
                        } else {
                            Log.member("安心豆🫘兑换失败[${candidate.item.name}]#$exchangeResult")
                        }
                    }
                }
            }
            beanRightMap.save(userId)
            remainingSelectedIds
                ?.forEach { Log.member("安心豆🫘已勾选[$it]#本次列表未返回，保留配置不删除") }
            Log.member("安心豆🫘兑换列表刷新完成#${candidateMap.size}")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "beanExchangeRight err:", t)
        }
    }

    private fun addBeanExchangeCandidates(
        response: JSONObject,
        candidateMap: MutableMap<String, BeanExchangeCandidate>,
        fromHistory: Boolean,
        beanBalance: Int?
    ) {
        val result = extractBeanExchangeResult(response)
        val arrays = listOfNotNull(
            result.optJSONArray("preExchangeDetailList"),
            result.optJSONArray("couponRightsDTOList"),
            result.optJSONArray("rightsList"),
            result.optJSONArray("flowList")
        )
        arrays.forEach { array ->
            for (i in 0 until array.length()) {
                val candidate = buildBeanExchangeCandidate(array.optJSONObject(i) ?: continue, fromHistory, beanBalance) ?: continue
                val existing = candidateMap[candidate.item.id]
                if (fromHistory && existing != null) {
                    continue
                }
                candidateMap[candidate.item.id] = candidate
            }
        }
    }

    private fun extractBeanExchangeResult(response: JSONObject): JSONObject {
        return response.optJSONObject("result")
            ?: response.optJSONObject("data")?.optJSONObject("result")
            ?: response.optJSONObject("data")
            ?: response
    }

    private fun queryBlueBeanBalance(): Int? {
        return runCatching {
            val accountInfo = JSONObject(AntMemberRpcCall.queryUserAccountInfo("INS_BLUE_BEAN"))
            if (!ResChecker.checkRes(TAG, accountInfo)) {
                return@runCatching null
            }
            val result = accountInfo.optJSONObject("result") ?: return@runCatching null
            result.optInt("userCurrentPoint", -1)
                .takeIf { it >= 0 }
                ?: result.optJSONObject("accountPoint")?.optInt("userCurrentPoint", -1)?.takeIf { it >= 0 }
        }.onFailure {
            Log.printStackTrace(TAG, "queryBlueBeanBalance err:", it)
        }.getOrNull()
    }

    private fun buildBeanExchangeCandidate(raw: JSONObject, fromHistory: Boolean, beanBalance: Int?): BeanExchangeCandidate? {
        val rightsId = sequenceOf(
            raw.optString("rightsId"),
            raw.optString("rightsCode"),
            raw.optString("itemId"),
            raw.optString("id")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        if (rightsId.isEmpty()) {
            return null
        }
        val name = sequenceOf(
            raw.optString("title"),
            raw.optString("simpleTitle"),
            raw.optString("rightsName"),
            raw.optString("itemName"),
            raw.optString("prizeName")
        ).firstOrNull { it.isNotBlank() } ?: rightsId
        val cash = raw.optString("cash")
        val pointNum = raw.optString("pointNum")
        val status = raw.optString("status")
        val rightsUseLink = raw.optString("rightsUseLink", raw.optString("jumpUrl"))
        val showType = raw.optString("showType")
        val needOrder = when {
            raw.has("needOrder") -> raw.optInt("needOrder", -1)
            raw.optJSONObject("exchangeRequest")?.has("needOrder") == true ->
                raw.optJSONObject("exchangeRequest")?.optInt("needOrder", -1) ?: -1
            else -> 0
        }
        val assetAmount = when {
            raw.has("assetAmount") -> raw.optInt("assetAmount", -1)
            raw.optJSONObject("exchangeRequest")?.has("assetAmount") == true ->
                raw.optJSONObject("exchangeRequest")?.optInt("assetAmount", -1) ?: -1
            pointNum.toIntOrNull() != null -> pointNum.toInt()
            else -> -1
        }
        val statusParts = mutableListOf<String>()
        if (fromHistory) {
            statusParts.add("已兑换记录")
        }
        if (status.isNotBlank()) {
            statusParts.add(status)
        }
        raw.optString("exchangeTotalNum")
            .takeIf { it.isNotBlank() }
            ?.let { statusParts.add("已兑$it") }
        raw.optInt("lackPointNum", 0)
            .takeIf { it > 0 }
            ?.let { statusParts.add("安心豆不足$it") }
        val balanceNotEnough = beanBalance != null && assetAmount > beanBalance
        if (balanceNotEnough) {
            statusParts.add("安心豆不足")
        }

        val unavailable = fromHistory ||
            status.equals("WAIT_EFFECTIVE", true) ||
            status.equals("INVALID", true) ||
            status.equals("USED", true) ||
            raw.optInt("lackPointNum", 0) > 0 ||
            balanceNotEnough
        val unsafeByType = showType.equals("GOODS", true) ||
            showType.equals("CASH", true) ||
            (showType.isNotBlank() && !showType.equals("OTHER", true))
        val (baseSafety, baseReason) = ExchangeSafetyRules.classify(
            cashValues = listOf(cash),
            textValues = listOf(name, showType, rightsUseLink, raw.optString("supplyType"), raw.toString()),
            defaultReason = "涉及实付、外跳或下单链路"
        )
        val paramsComplete = assetAmount > 0 && needOrder == 0
        val safety = when {
            unavailable -> ExchangeSafety.UNAVAILABLE
            unsafeByType -> ExchangeSafety.LOG_ONLY
            baseSafety == ExchangeSafety.LOG_ONLY -> ExchangeSafety.LOG_ONLY
            !paramsComplete -> ExchangeSafety.LOG_ONLY
            else -> ExchangeSafety.AUTO
        }
        val safetyReason = when {
            unavailable -> when {
                fromHistory -> "已兑换记录"
                balanceNotEnough || raw.optInt("lackPointNum", 0) > 0 -> "安心豆不足"
                else -> statusParts.firstOrNull().orEmpty().ifEmpty { "服务端状态不可兑换" }
            }
            unsafeByType -> "商品/现金/券类权益需手动处理"
            baseReason.isNotEmpty() -> baseReason
            !paramsComplete -> "rightsExchange 参数不完整"
            else -> ""
        }
        return BeanExchangeCandidate(
            item = ExchangeItem(
                id = rightsId,
                name = name,
                cost = ExchangeCost(
                    pointText = pointNum.takeIf { it.isNotBlank() }?.let { "${it}安心豆" }.orEmpty(),
                    cashText = cash.takeIf { it.isNotBlank() && it != "0" }?.let { "${it}元" }.orEmpty()
                ),
                limit = ExchangeLimit(
                    statusText = statusParts.joinToString("、")
                ),
                safety = safety,
                safetyReason = safetyReason
            ),
            rightsId = rightsId,
            assetAmount = assetAmount,
            needOrder = needOrder
        )
    }



    /**
     * 查询 + 自动领取贴纸
     */
    @SuppressLint("DefaultLocale")
    fun queryAndCollectStickers() {
        try {
            if (hasFlagToday(StatusFlags.FLAG_ANTMEMBER_STICKER)) {
                Log.member("今日已兑换贴纸，跳过")
                return
            }
            val now = Date()
            val year = SimpleDateFormat("yyyy", Locale.ENGLISH).format(now)
            val month = SimpleDateFormat("MM", Locale.ENGLISH).format(now)
            val day = SimpleDateFormat("dd", Locale.ENGLISH).format(now)

            val queryResp = AntMemberRpcCall.queryStickerCanReceive(year, month)

            val queryJson = JSONObject(queryResp)
            if (!ResChecker.checkRes(TAG, queryJson)) {
                logStickerRpcFailure("查询可领取列表", queryJson)
                return
            }

            val canReceivePageList = queryJson.optJSONArray("canReceivePageList") ?: JSONArray()

            // 用于存储 ID -> Name 的映射
            val stickerNameMap = mutableMapOf<String, String>()
            val allStickerIds = mutableListOf<String>()

            for (i in 0 until canReceivePageList.length()) {
                val page = canReceivePageList.optJSONObject(i)
                val stickerList = page?.optJSONArray("stickerCanReceiveList") ?: continue
                for (j in 0 until stickerList.length()) {
                    val stickerObj = stickerList.optJSONObject(j) ?: continue
                    val id = stickerObj.optString("id")
                    val name = stickerObj.optString("name")
                    if (id.isNotEmpty()) {
                        allStickerIds.add(id)
                        stickerNameMap[id] = name.ifEmpty { "未知贴纸" }
                    }
                }
            }

            if (allStickerIds.isEmpty()) {
                Log.member("贴纸扫描：暂无可领取的贴纸")
            } else {
                // 2. 领取阶段
                val collectResp = AntMemberRpcCall.receiveSticker(year, month, allStickerIds)

                val collectJson = JSONObject(collectResp)
                if (!ResChecker.checkRes(TAG, collectJson)) {
                    logStickerRpcFailure("领取贴纸", collectJson)
                    return
                }

                // 3. 结果解析与比对输出
                val specialList = collectJson.optJSONArray("specialStickerList")
                val obtainedIds = collectJson.optJSONArray("obtainedConfigId")

                Log.member("贴纸领取成功，总数：${obtainedIds?.length() ?: 0}")

                if (specialList != null && specialList.length() > 0) {
                    for (i in 0 until specialList.length()) {
                        val special = specialList.optJSONObject(i) ?: continue

                        // 获取领取结果中的 recordId
                        val recordId = special.optString("stickerRecordId")
                        // 从我们之前的 Map 中根据 ID 找到对应的 Name
                        val stickerName = stickerNameMap[recordId] ?: "特殊贴纸"

                        val ranking = special.optString("rankingText")

                        // 仅对特殊贴纸输出会员日志，显示真实的贴纸名称
                        Log.member("获得特殊贴纸 → $stickerName ($ranking)")
                    }
                }
            }

            val followUpResult = handleStickerFollowUps(year, month, day)
            if (!followUpResult.success) {
                Log.member("贴纸后续处理存在失败，保留后续重试机会")
                return
            }

            if (allStickerIds.isNotEmpty() || followUpResult.handled) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_STICKER)
            }

        } catch (e: Exception) {
            Log.printStackTrace("$TAG stickerAutoCollect err", e)
        }
    }

    private fun handleStickerFollowUps(year: String, month: String, day: String): StickerFollowUpResult {
        val upgradeResult = upgradeAndCollectStickerBenefits(year, month, day)
        val drawingResult = collectStickerDrawingPrizes()
        return StickerFollowUpResult(
            success = upgradeResult.success && drawingResult.success,
            handled = upgradeResult.handled || drawingResult.handled
        )
    }

    private fun upgradeAndCollectStickerBenefits(year: String, month: String, day: String): StickerFollowUpResult {
        var success = true
        var handled = false
        val benefitCandidates = linkedMapOf<String, String>()
        val upgradeReqList = JSONArray()

        try {
            val homeJson = JSONObject(AntMemberRpcCall.queryStickerHomePage(year, month, day))
            if (!ResChecker.checkRes(TAG, homeJson)) {
                logStickerRpcFailure("查询贴纸首页", homeJson)
                return StickerFollowUpResult(success = false)
            }

            val stickerList = homeJson.optJSONObject("commonStickerRes")
                ?.optJSONArray("stickerDetailList")
                ?: JSONArray()

            for (i in 0 until stickerList.length()) {
                val sticker = stickerList.optJSONObject(i) ?: continue
                val stickerConfigId = sticker.optString("stickerConfigId")
                if (stickerConfigId.isBlank()) continue

                val stickerName = sticker.optString("name", stickerConfigId)
                val status = sticker.optString("status")
                if (sticker.optBoolean("hasBenefit") && !"notReceived".equals(status, ignoreCase = true)) {
                    benefitCandidates[stickerConfigId] = stickerName
                }

                if ("upgradable".equals(status, ignoreCase = true)) {
                    val currentLevelCode = sticker.optJSONObject("currentLevel")
                        ?.optString("levelCode")
                        .orEmpty()
                    val upgradableLevelCode = sticker.optJSONObject("upgradableLevel")
                        ?.optString("levelCode")
                        .orEmpty()
                    if (currentLevelCode.isNotBlank() && upgradableLevelCode.isNotBlank()) {
                        upgradeReqList.put(JSONObject().apply {
                            put("currentLevelCode", currentLevelCode)
                            put("month", month)
                            put("stickerConfigId", stickerConfigId)
                            put("upgradableLevelCode", upgradableLevelCode)
                            put("year", year)
                        })
                    }
                }
            }

            if (upgradeReqList.length() > 0) {
                handled = true
                val upgradeJson = JSONObject(AntMemberRpcCall.upgradeStickerBatch(upgradeReqList))
                if (!ResChecker.checkRes(TAG, upgradeJson)) {
                    logStickerRpcFailure("贴纸升级", upgradeJson)
                    success = false
                } else {
                    val failedList = upgradeJson.optJSONArray("failStickerCfgIdList")
                    if (failedList != null && failedList.length() > 0) {
                        Log.error(TAG, "贴纸升级部分失败：$failedList")
                        success = false
                    } else {
                        Log.member("贴纸升级成功，数量：${upgradeReqList.length()}")
                    }
                }
            }

            for ((stickerConfigId, stickerName) in benefitCandidates) {
                val benefitResult = collectStickerUpgradeBenefit(year, month, stickerConfigId, stickerName)
                handled = handled || benefitResult.handled
                success = success && benefitResult.success
            }
        } catch (e: Exception) {
            Log.printStackTrace("$TAG stickerUpgradeAndBenefit err", e)
            return StickerFollowUpResult(success = false, handled = handled)
        }

        return StickerFollowUpResult(success = success, handled = handled)
    }

    private fun collectStickerUpgradeBenefit(
        year: String,
        month: String,
        stickerConfigId: String,
        stickerName: String
    ): StickerFollowUpResult {
        try {
            val detailJson = JSONObject(AntMemberRpcCall.queryStickerDetailPage(year, month, stickerConfigId))
            if (!ResChecker.checkRes(TAG, detailJson)) {
                logStickerRpcFailure("查询权益详情[$stickerName]", detailJson)
                return StickerFollowUpResult(success = false)
            }

            if (!hasReceivableStickerUpgradeBenefit(detailJson)) {
                return StickerFollowUpResult()
            }

            val triggerJson = JSONObject(AntMemberRpcCall.triggerStickerUpgradePrize(stickerConfigId))
            if (!ResChecker.checkRes(TAG, triggerJson)) {
                logStickerRpcFailure("领取升级权益[$stickerName]", triggerJson)
                return StickerFollowUpResult(success = false, handled = true)
            }

            logStickerPrizeResults("贴纸权益[$stickerName]", triggerJson)
            return StickerFollowUpResult(handled = true)
        } catch (e: Exception) {
            Log.printStackTrace("$TAG collectStickerUpgradeBenefit err", e)
            return StickerFollowUpResult(success = false)
        }
    }

    private fun hasReceivableStickerUpgradeBenefit(detailJson: JSONObject): Boolean {
        val detailList = detailJson.optJSONObject("stickerDetailRes")
            ?.optJSONArray("stickerDetailList")
            ?: return false
        for (i in 0 until detailList.length()) {
            val benefitStatus = detailList.optJSONObject(i)
                ?.optJSONObject("upgradeBenefitModel")
                ?.optString("status")
                .orEmpty()
            if ("can_receive".equals(benefitStatus, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun collectStickerDrawingPrizes(): StickerFollowUpResult {
        var success = true
        var handled = false
        try {
            val prizeHomeJson = JSONObject(AntMemberRpcCall.queryStickerPrizeHomePage())
            if (!ResChecker.checkRes(TAG, prizeHomeJson)) {
                logStickerRpcFailure("查询抽奖机会", prizeHomeJson)
                return StickerFollowUpResult(success = false)
            }

            val prizeConsumerIdList = prizeHomeJson.optJSONArray("prizeConsumerIdList") ?: return StickerFollowUpResult()
            if (prizeConsumerIdList.length() == 0) {
                return StickerFollowUpResult()
            }

            Log.member("贴纸抽奖机会：${prizeConsumerIdList.length()}次")
            for (i in 0 until prizeConsumerIdList.length()) {
                val prizeQuotaRecordId = prizeConsumerIdList.optString(i)
                if (prizeQuotaRecordId.isBlank()) continue

                handled = true
                val drawJson = JSONObject(AntMemberRpcCall.triggerStickerDrawing(prizeQuotaRecordId))
                if (!ResChecker.checkRes(TAG, drawJson)) {
                    logStickerRpcFailure("抽奖[$prizeQuotaRecordId]", drawJson)
                    success = false
                    continue
                }
                logStickerPrizeResults("贴纸抽奖", drawJson)
            }
        } catch (e: Exception) {
            Log.printStackTrace("$TAG collectStickerDrawingPrizes err", e)
            return StickerFollowUpResult(success = false, handled = handled)
        }

        return StickerFollowUpResult(success = success, handled = handled)
    }

    private fun logStickerRpcFailure(scene: String, response: JSONObject) {
        val code = response.optString("resultCode").ifBlank {
            response.optString("code").ifBlank {
                response.optString("errorCode")
            }
        }
        val message = response.optString("message").ifBlank {
            response.optString("resultDesc").ifBlank {
                response.optString("memo").ifBlank {
                    response.optString("errorMsg").ifBlank {
                        response.optString("resultView")
                    }
                }
            }
        }
        val combined = "$code $message ${response.optString("desc")}"
        val failureType = when {
            containsAny(combined, "已领取", "重复", "已兑换", "已经抽过") ->
                StickerRpcFailureType.DUPLICATE_REWARD

            containsAny(combined, "上限", "频繁", "手速", "稍后", "库存不足", "名额", "资格", "机会不足", "额度不足", "活动太火爆") ->
                StickerRpcFailureType.BUSINESS_LIMIT

            else -> StickerRpcFailureType.NON_RETRYABLE
        }
        val label = when (failureType) {
            StickerRpcFailureType.BUSINESS_LIMIT -> "业务受限"
            StickerRpcFailureType.DUPLICATE_REWARD -> "重复领取"
            StickerRpcFailureType.NON_RETRYABLE -> "接口失败"
        }
        val detail = when {
            code.isNotBlank() && message.isNotBlank() -> "$code/$message"
            code.isNotBlank() -> code
            message.isNotBlank() -> message
            else -> response.toString()
        }
        Log.error(TAG, "贴纸[$scene]#$label:$detail")
    }

    private fun containsAny(value: String, vararg keywords: String): Boolean {
        return keywords.any { value.contains(it, ignoreCase = true) }
    }

    private fun logStickerPrizeResults(scene: String, prizeJson: JSONObject) {
        val prizeResultList = prizeJson.optJSONArray("prizeResultList")
        if (prizeResultList != null && prizeResultList.length() > 0) {
            for (i in 0 until prizeResultList.length()) {
                val prize = prizeResultList.optJSONObject(i) ?: continue
                if (!ResChecker.checkRes(TAG, prize)) {
                    logStickerRpcFailure("$scene 部分奖励", prize)
                    continue
                }
                Log.member("$scene#${resolveStickerPrizeName(prize)}")
            }
            return
        }

        Log.member("$scene#${resolveStickerPrizeName(prizeJson)}")
    }

    private fun resolveStickerPrizeName(prize: JSONObject): String {
        val couponPrize = prize.optJSONObject("couponPrizeRes")
        if (couponPrize != null) {
            val price = couponPrize.optString("price")
            val unit = couponPrize.optString("unit")
            val name = couponPrize.optString("name", couponPrize.optString("title", "优惠券"))
            val condition = couponPrize.optString("condition")
            return buildString {
                if (price.isNotBlank() && unit.isNotBlank() && !name.contains(price)) {
                    append(price).append(unit)
                }
                append(name)
                if (condition.isNotBlank()) {
                    append(" ").append(condition)
                }
            }
        }

        val virtualPrize = prize.optJSONObject("virtualPrizeRes")
        if (virtualPrize != null) {
            val title = virtualPrize.optString("title", "虚拟奖励")
            val count = virtualPrize.optString("count")
            val unit = virtualPrize.optString("unit")
            return buildString {
                append(title)
                if (count.isNotBlank()) {
                    append("*").append(count)
                    if (unit.isNotBlank()) {
                        append(unit)
                    }
                }
            }
        }

        return prize.optString("prizeId", "未知奖励")
    }

    companion object {
        private val TAG: String = AntMember::class.java.getSimpleName()
        private const val memberTaskBlacklistModule = "支付宝会员"
        private const val insuredTaskBlacklistModule = "蚂蚁保"
        private const val memberFloatingBallAdTaskTitle = "会员浮球广告浏览任务"
        private const val MERCHANT_EXAM_TASK_CODE = "JYMWDDJF_TASK"
        private const val MERCHANT_EXAM_PRODUCE_CHANNEL = "GW_MRCHSERVEBASE_DEFAULT"
        private const val MERCHANT_UNCLOSED_AD_TASK_CODE = "SYH_RTB_SHOW_TASK_INDEX_1"
        private const val INSURED_GOLD_WAIT_LIST_QUERY_LIMIT = 3
        private val memberTaskClosedLoopConfigIds = setOf(
            "600202500151482",
            "600202400075770",
            "600202500163188",
            "600202400066231",
            "600202300028189",
            "600202300020561",
            "600202300002546",
            "600202400073337",
            "600202500136682",
            "600202300040463",
            "600202400104923",
            "600202400081445",
            "600202600208739",
            "600202500195828",
            "600202600200069",
            "600202400098334",
            "600202400102692",
            "600202500160908",
            "600202300043597",
            "600202500154335",
            "600202400066415",
            "600202400072292"
        )
        private val memberAdTaskClosedLoopConfigIds = setOf("32002001")
        private const val MEMBER_TASK_UNSUPPORTED_LOG_LIMIT = 8
        private const val MEMBER_TASK_REPEAT_LIMIT = 6
        private const val MEMBER_CALL_APP_VERIFY_RETRY_LIMIT = 5
        private const val MEMBER_CALL_APP_VERIFY_SLEEP_MS = 2000L


        /**
         * 会员积分收取
         * @param page 第几页
         * @param pageSize 每页数据条数
         */
        internal suspend fun queryPointCert(page: Int, pageSize: Int) {
            try {
                var s = AntMemberRpcCall.queryPointCertV2(page, pageSize)
                var jo = JSONObject(s)
                if (ResChecker.checkRes(TAG, "查询会员积分证书失败:", jo) && jo.has("pointToClaim")) {
                    val pointToClaim = jo.optInt("pointToClaim", 0)
                    if (pointToClaim > 0 && jo.optBoolean("showReceiveAllPointFunction")) {
                        s = AntMemberRpcCall.receiveAllPointByUser()
                        val receiveAllObject = JSONObject(s)
                        val receiveAllSuccess = ResChecker.checkRes(TAG, "会员积分一键领取失败:", receiveAllObject)
                        if (receiveAllSuccess) {
                            val receiveSumPoint = receiveAllObject.optInt("receiveSumPoint", 0)
                            val receiveStatus = receiveAllObject.optString("receiveStatus")
                            if ("SUCCESS" == receiveStatus || receiveSumPoint > 0) {
                                Log.member("会员积分🎖️[一键领取]#${receiveSumPoint}积分")
                                return
                            }
                            if ("DOING" == receiveStatus) {
                                Log.member("会员积分🎖️[一键领取处理中，不等待轮询，回退逐条领取]#receiveStatus=$receiveStatus")
                            } else {
                                Log.member("会员积分🎖️[一键领取未确认成功，回退逐条领取]#receiveStatus=$receiveStatus")
                            }
                        }
                        if (!receiveAllSuccess) {
                            Log.member("会员积分🎖️[一键领取失败，回退逐条领取]")
                        }
                    }
                    claimMemberPointCertList(jo, page, pageSize)
                    return
                }

                s = AntMemberRpcCall.queryPointCert(page, pageSize)
                jo = JSONObject(s)
                if (ResChecker.checkRes(TAG, "查询会员积分证书失败:", jo)) {
                    claimMemberPointCertList(jo, page, pageSize)
                } else {
                    Log.member(jo.getString("resultDesc"))
                    Log.member(s)
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "queryPointCert err:", t)
            }
        }

        private suspend fun claimMemberPointCertList(queryObject: JSONObject, page: Int, pageSize: Int) {
            val hasNextPage = queryObject.optBoolean("hasNextPage")
            val certList = queryObject.optJSONArray("certList") ?: JSONArray()
            for (i in 0 until certList.length()) {
                val certObject = certList.getJSONObject(i)
                val bizTitle = certObject.optString("bizTitle").ifEmpty {
                    certObject.optString("title", "会员积分")
                }
                val id = certObject.optString("id").ifEmpty { certObject.optString("certId") }
                if (id.isEmpty()) {
                    continue
                }
                val pointAmount = certObject.optInt("pointAmount", certObject.optInt("point", 0))
                val response = AntMemberRpcCall.receivePointByUser(id)
                val receiveObject = JSONObject(response)
                if (ResChecker.checkRes(TAG, "会员积分领取失败:", receiveObject)) {
                    Log.member("会员积分🎖️[领取$bizTitle]#${pointAmount}积分")
                } else {
                    Log.member(receiveObject.optString("resultDesc"))
                    Log.member(response)
                }
            }
            if (hasNextPage) {
                queryPointCert(page + 1, pageSize)
            }
        }


        /**
         * 商家开门打卡签到
         */
        private fun kmdkSignIn(): Boolean = CoroutineUtils.run {
            try {
                val s = AntMemberRpcCall.queryActivity()
                val jo = JSONObject(s)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.member("queryActivity $s")
                    return@run false
                }

                when (jo.optString("signInStatus")) {
                    "SIGN_IN_ENABLE" -> {
                        val activityNo = jo.optString("activityNo")
                        if (activityNo.isEmpty()) return@run false
                        val joSignIn = JSONObject(AntMemberRpcCall.signIn(activityNo))
                        if (ResChecker.checkRes(TAG, joSignIn)) {
                            Log.member("商家服务🏬[开门打卡签到成功]")
                            return@run true
                        }
                        Log.member(joSignIn.optString("errorMsg"))
                        Log.member(joSignIn.toString())
                        return@run false
                    }

                    "SIGN_IN_DISABLE" -> return@run true // 通常表示已签到
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "kmdkSignIn err:", t)
            }
            false
        }

        /**
         * 商家开门打卡报名
         */
        private suspend fun kmdkSignUp(): Boolean = CoroutineUtils.run {
            try {
                for (i in 0..4) {
                    val jo = JSONObject(AntMemberRpcCall.queryActivity())
                    if (ResChecker.checkRes(TAG, jo)) {
                        val activityNo = jo.optString("activityNo")
                        if (activityNo.isEmpty()) {
                            continue
                        }
                        if (TimeUtil.getFormatDate().replace("-", "") != activityNo.split("_".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[2]) {
                            break
                        }
                        if ("SIGN_UP" == jo.getString("signUpStatus")) {
                            return@run true
                        }
                        if ("UN_SIGN_UP" == jo.getString("signUpStatus")) {
                            val activityPeriodName = jo.getString("activityPeriodName")
                            val joSignUp = JSONObject(AntMemberRpcCall.signUp(activityNo))
                            if (ResChecker.checkRes(TAG, joSignUp)) {
                                Log.member("商家服务🏬[" + activityPeriodName + "开门打卡报名]")
                                return@run true
                            } else {
                                Log.member(joSignUp.getString("errorMsg"))
                                Log.member(joSignUp.toString())
                            }
                        }
                    } else {
                        Log.member("queryActivity")
                        Log.member(jo.toString())
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "kmdkSignUp err:", t)
            }
            false
        }

        /**
         * 商家积分签到
         */
        private fun doMerchantSign(): Boolean = CoroutineUtils.run {
            var handled = false
            try {
                if (doMerchantZcjSignIn()) {
                    handled = true
                }
                val s = AntMemberRpcCall.merchantSign()
                var jo = JSONObject(s)
                if (!ResChecker.checkRes(TAG, jo)) {
                    if (!handled) {
                        Log.member("doMerchantSign err:$s")
                    }
                    return@run handled
                }
                jo = jo.getJSONObject("data")
                val signResult = jo.optString("signInResult")
                val reward = jo.optString("todayReward")
                if ("SUCCESS" == signResult) {
                    Log.member("商家服务🏬[每日签到]#获得积分$reward")
                    return@run true
                } else {
                    // 对于「已签到 / 不可签到」等情况，直接视为今日已处理，避免反复请求触发风控
                    Log.member("商家服务🏬[每日签到]#未返回SUCCESS(signInResult=$signResult,todayReward=$reward)")
                    Log.member(s)
                    return@run true
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "kmdkSignIn err:", t)
            }
            handled
        }

        /**
         * 商家积分任务
         */
        private suspend fun doMerchantMoreTask(): Unit = CoroutineUtils.run {
            try {
                val adapter = MerchantMoreTaskFlowAdapter()
                val runResult = TaskFlowEngine(adapter, roundSleepMs = 500L).run()
                if (adapter.taskListObserved && adapter.taskCount == 0) {
                    Log.member("商家服务🏬[积分任务]#未查询到任务列表")
                }
                if (runResult.completed || (adapter.querySucceeded && adapter.taskListObserved && adapter.taskCount == 0)) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_MORE_TASK_DONE)
                }
                collectMerchantPointBalls()
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "taskListQuery err:", t)
            }
        }

        private class MerchantMoreTaskFlowAdapter : TaskFlowAdapter {
            override val moduleName: String = memberTaskBlacklistModule
            override val flowName: String = "商家服务🏬积分任务"

            var taskCount: Int = 0
                private set
            var querySucceeded: Boolean = false
                private set
            var taskListObserved: Boolean = false
                private set

            private val handledTaskKeys = LinkedHashSet<String>()
            private val receivedTaskKeys = LinkedHashSet<String>()
            private val signedUpTaskKeys = LinkedHashSet<String>()
            private val loggedSkipKeys = LinkedHashSet<String>()

            override fun query(): JSONObject {
                return try {
                    val raw = AntMemberRpcCall.taskListQuery()
                    val response = JSONObject(raw)
                    val evaluation = evaluateMerchantRpc(response)
                    response
                        .put("_taskFlowQuerySuccess", evaluation.success)
                        .put("_rawResponse", raw)
                } catch (t: Throwable) {
                    JSONObject()
                        .put("_taskFlowQuerySuccess", false)
                        .put("resultDesc", "查询异常:${t.message}")
                }
            }

            override fun isQuerySuccess(response: JSONObject): Boolean {
                querySucceeded = response.optBoolean("_taskFlowQuerySuccess", false)
                return querySucceeded
            }

            override fun extractItems(response: JSONObject): List<TaskFlowItem> {
                val data = response.optJSONObject("data") ?: return emptyList()
                val planCode = data.optString("planCode")
                if (planCode.isNotBlank() && !planCode.equals("MORE", true)) {
                    Log.member("商家服务🏬[积分任务列表]#返回计划$planCode，本轮跳过")
                    return emptyList()
                }
                val taskList = data.optJSONArray("taskList") ?: return emptyList()
                taskListObserved = true
                taskCount = max(taskCount, taskList.length())
                val items = mutableListOf<TaskFlowItem>()
                for (i in 0 until taskList.length()) {
                    val task = taskList.optJSONObject(i) ?: continue
                    val taskCode = task.optString("taskCode")
                    val title = task.optString("title", task.optString("taskName", "商家任务"))
                    val status = task.optString("status")
                    val current = extractMerchantTaskCurrent(task)
                    val limit = extractMerchantTaskLimit(task)
                    items.add(
                        TaskFlowItem(
                            id = taskCode.ifBlank { task.optString("pointBallId").ifBlank { title } },
                            title = title,
                            status = status,
                            type = taskCode,
                            actionType = resolveMerchantActionCodes(task).joinToString("|"),
                            blacklistKeys = listOf(taskCode, title).filter { it.isNotBlank() },
                            raw = task,
                            progress = buildMerchantTaskProgress(task, current, limit),
                            current = current,
                            limit = limit
                        )
                    )
                }
                return items
            }

            override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
                val taskKey = buildMerchantTaskFlowKey(item)
                if (taskKey in receivedTaskKeys) {
                    return TaskFlowPhase.TERMINAL
                }
                return when (item.status.uppercase(Locale.ROOT)) {
                    "NEED_RECEIVE" -> TaskFlowPhase.REWARD_READY
                    "PROCESSING" -> if (resolveMerchantBizId(item.raw ?: JSONObject()).isNotBlank()) {
                        TaskFlowPhase.REWARD_READY
                    } else if (taskKey in handledTaskKeys) {
                        TaskFlowPhase.TERMINAL
                    } else {
                        TaskFlowPhase.READY_TO_COMPLETE
                    }
                    "UNRECEIVED" -> if (taskKey in signedUpTaskKeys) {
                        TaskFlowPhase.READY_TO_COMPLETE
                    } else {
                        TaskFlowPhase.SIGNUP_REQUIRED
                    }
                    "RECEIVED",
                    "DONE",
                    "FINISHED",
                    "COMPLETE",
                    "SUCCESS" -> TaskFlowPhase.TERMINAL
                    else -> TaskFlowPhase.UNKNOWN
                }
            }

            override fun shouldSkip(item: TaskFlowItem): Boolean {
                val phase = mapPhase(item)
                val raw = item.raw ?: JSONObject()
                val taskKey = buildMerchantTaskFlowKey(item)
                if (taskKey in receivedTaskKeys ||
                    (taskKey in handledTaskKeys && phase != TaskFlowPhase.REWARD_READY)
                ) {
                    return true
                }
                if (item.type == MERCHANT_UNCLOSED_AD_TASK_CODE && isBlacklisted(item)) {
                    return true
                }
                if (phase == TaskFlowPhase.REWARD_READY &&
                    raw.optString("pointBallId").isBlank() &&
                    resolveMerchantBizId(raw).isBlank()
                ) {
                    logSkipOnce(item, "缺少可领取pointBallId/bizId，跳过")
                    return true
                }
                if ((phase == TaskFlowPhase.SIGNUP_REQUIRED || phase == TaskFlowPhase.READY_TO_COMPLETE) &&
                    item.type.isBlank()
                ) {
                    logSkipOnce(item, "缺少taskCode，跳过")
                    return true
                }
                if (phase == TaskFlowPhase.READY_TO_COMPLETE &&
                    item.actionType.isBlank() &&
                    item.type != MERCHANT_EXAM_TASK_CODE
                ) {
                    logSkipOnce(item, "缺少actionCode，跳过")
                    return true
                }
                return false
            }

            override fun isBlacklisted(item: TaskFlowItem): Boolean {
                val blacklisted = super<TaskFlowAdapter>.isBlacklisted(item)
                if (blacklisted) {
                    logSkipOnce(item, "黑名单任务，跳过")
                }
                return blacklisted
            }

            override fun receive(item: TaskFlowItem): TaskFlowActionResult {
                val task = item.raw ?: JSONObject()
                val pointBallId = task.optString("pointBallId")
                if (pointBallId.isNotBlank()) {
                    return receiveMerchantPointBallResult(pointBallId, item.title, task.optString("reward", task.optString("point")))
                }
                val bizId = resolveMerchantBizId(task)
                if (bizId.isBlank()) {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        code = "MERCHANT_RECEIVE_ID_MISSING",
                        message = "缺少pointBallId/bizId",
                        rpc = "AntMemberRpcCall.ballReceive/taskFinish",
                        detail = merchantTaskActionDetail(item, "receive")
                    )
                }
                val response = JSONObject(AntMemberRpcCall.taskFinish(bizId))
                val evaluation = evaluateMerchantRpc(response)
                if (!evaluation.success) {
                    return merchantTaskFailureResult(item, "AntMemberRpcCall.taskFinish", response, evaluation)
                }
                Log.member("商家服务🏬[${item.title}]#领取积分${task.optString("reward", task.optString("point"))}")
                return TaskFlowActionResult.success()
            }

            override fun signup(item: TaskFlowItem): TaskFlowActionResult {
                val response = JSONObject(AntMemberRpcCall.taskReceive(item.type))
                val evaluation = evaluateMerchantRpc(response)
                if (!evaluation.success) {
                    return merchantTaskFailureResult(item, "AntMemberRpcCall.taskReceive", response, evaluation)
                }
                Log.member("商家服务🏬[${item.title}]#领取任务")
                return TaskFlowActionResult.success()
            }

            override fun complete(item: TaskFlowItem): TaskFlowActionResult {
                if (item.type == MERCHANT_EXAM_TASK_CODE) {
                    return completeMerchantExamTask(item)
                }
                val actionCodes = item.actionType.split("|")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                var lastFailure: TaskFlowActionResult? = null
                val targetCount = resolveMerchantTaskTargetCount(item.raw ?: JSONObject())
                actionLoop@ for (actionCode in actionCodes) {
                    val queryActivity = JSONObject(AntMemberRpcCall.actioncode(actionCode))
                    val queryEvaluation = evaluateMerchantRpc(queryActivity)
                    if (!queryEvaluation.success) {
                        val failure = merchantTaskFailureResult(
                            item,
                            "AntMemberRpcCall.actioncode",
                            queryActivity,
                            queryEvaluation,
                            actionCode
                        )
                        lastFailure = failure
                        if (queryEvaluation.failureType == MerchantRpcFailureType.NO_ACTIVITY) {
                            logMerchantRpcFailure("查询任务活动[${item.title}/$actionCode]", queryActivity, queryEvaluation)
                            continue
                        }
                        if (failure.failureType == TaskRpcFailureType.RETRYABLE_RPC ||
                            failure.failureType == TaskRpcFailureType.BUSINESS_LIMIT
                        ) {
                            return failure
                        }
                        continue
                    }

                    var produceSuccess = false
                    var remainingCount = max(1, targetCount)
                    for (index in 0 until max(1, targetCount)) {
                        val produce = JSONObject(AntMemberRpcCall.produce(actionCode))
                        val produceEvaluation = evaluateMerchantRpc(produce)
                        if (!produceEvaluation.success) {
                            val failure = merchantTaskFailureResult(
                                item,
                                "AntMemberRpcCall.produce",
                                produce,
                                produceEvaluation,
                                actionCode
                            )
                            lastFailure = failure
                            if (produceEvaluation.failureType == MerchantRpcFailureType.NO_ACTIVITY) {
                                logMerchantRpcFailure("完成任务[${item.title}/$actionCode]", produce, produceEvaluation)
                                continue@actionLoop
                            }
                            if (failure.failureType == TaskRpcFailureType.RETRYABLE_RPC ||
                                failure.failureType == TaskRpcFailureType.BUSINESS_LIMIT
                            ) {
                                return failure
                            }
                            break
                        }
                        produceSuccess = true

                        val refreshedTask = queryMerchantTaskByCode(item.type) ?: break
                        val refreshedStatus = refreshedTask.optString("status")
                        if ("NEED_RECEIVE" == refreshedStatus ||
                            ("PROCESSING" != refreshedStatus && "UNRECEIVED" != refreshedStatus)
                        ) {
                            break
                        }

                        val refreshedRemainingCount = resolveMerchantTaskRemainingCount(refreshedTask) ?: break
                        if (refreshedRemainingCount <= 0 || refreshedRemainingCount >= remainingCount) {
                            break
                        }
                        remainingCount = refreshedRemainingCount
                    }
                    if (produceSuccess) {
                        Log.member("商家服务🏬[完成任务${item.title}]")
                        return TaskFlowActionResult.success()
                    }
                }
                return lastFailure ?: TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "MERCHANT_ACTION_CODE_MISSING",
                    message = "没有可执行actionCode",
                    rpc = "AntMemberRpcCall.actioncode",
                    detail = merchantTaskActionDetail(item, "complete")
                )
            }

            private fun completeMerchantExamTask(item: TaskFlowItem): TaskFlowActionResult {
                val examPage = JSONObject(AntMemberRpcCall.merchantExamPage(item.type))
                val examEvaluation = evaluateMerchantRpc(examPage)
                if (!examEvaluation.success) {
                    return merchantTaskFailureResult(
                        item,
                        "AntMemberRpcCall.merchantExamPage",
                        examPage,
                        examEvaluation,
                        item.type
                    )
                }

                val data = examPage.optJSONObject("data")
                if (data == null || !data.optBoolean("available", false)) {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                        code = "MERCHANT_EXAM_UNAVAILABLE",
                        message = "答题任务当前不可用",
                        rpc = "AntMemberRpcCall.merchantExamPage",
                        raw = examPage.toString(),
                        detail = merchantTaskActionDetail(item, "examPage")
                    )
                }

                val actionCode = data.optString("actionCode").trim()
                if (actionCode.isEmpty()) {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        code = "MERCHANT_EXAM_ACTION_MISSING",
                        message = "答题任务缺少actionCode",
                        rpc = "AntMemberRpcCall.merchantExamPage",
                        raw = examPage.toString(),
                        detail = merchantTaskActionDetail(item, "examPage")
                    )
                }

                val produce = JSONObject(AntMemberRpcCall.produce(actionCode, MERCHANT_EXAM_PRODUCE_CHANNEL))
                val produceEvaluation = evaluateMerchantRpc(produce)
                if (!produceEvaluation.success) {
                    return merchantTaskFailureResult(
                        item,
                        "AntMemberRpcCall.produce",
                        produce,
                        produceEvaluation,
                        actionCode
                    )
                }

                Log.member("商家服务🏬[${item.title}]#完成答题任务")
                return TaskFlowActionResult.success()
            }

            override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String {
                return "${action.logName}:${buildMerchantTaskFlowKey(item)}:${item.status}:${item.progress}"
            }

            override fun afterSuccess(item: TaskFlowItem, action: TaskFlowAction, result: TaskFlowActionResult) {
                when (action) {
                    TaskFlowAction.RECEIVE -> receivedTaskKeys.add(buildMerchantTaskFlowKey(item))
                    TaskFlowAction.COMPLETE,
                    TaskFlowAction.SEND -> handledTaskKeys.add(buildMerchantTaskFlowKey(item))
                    TaskFlowAction.SIGNUP -> signedUpTaskKeys.add(buildMerchantTaskFlowKey(item))
                }
            }

            override fun afterFailure(
                item: TaskFlowItem,
                action: TaskFlowAction,
                result: TaskFlowActionResult,
                decision: TaskFlowDecision
            ) {
                if (decision == TaskFlowDecision.MARK_HANDLED) {
                    when (action) {
                        TaskFlowAction.RECEIVE -> receivedTaskKeys.add(buildMerchantTaskFlowKey(item))
                        TaskFlowAction.SIGNUP -> signedUpTaskKeys.add(buildMerchantTaskFlowKey(item))
                        TaskFlowAction.COMPLETE,
                        TaskFlowAction.SEND -> handledTaskKeys.add(buildMerchantTaskFlowKey(item))
                    }
                }
            }

            override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
                logInfo("商家服务🏬[积分任务已处理完成：${snapshot.completedTasks}/${snapshot.totalTasks}]")
            }

            override fun onQueryFailed(response: JSONObject) {
                val evaluation = evaluateMerchantRpc(response)
                logMerchantRpcFailure("积分任务列表", response, evaluation)
            }

            override fun onUnknownPhase(item: TaskFlowItem, phase: TaskFlowPhase) {
                Log.member(
                    "商家服务🏬[${item.title}]#未知任务状态，跳过 " +
                        "taskCode=${item.type.ifBlank { "UNKNOWN" }} status=${item.status.ifBlank { "UNKNOWN" }} raw=${item.raw}"
                )
            }

            override fun logInfo(message: String) {
                Log.member(message)
            }

            override fun logError(message: String) {
                Log.error(TAG, message)
            }

            private fun logSkipOnce(item: TaskFlowItem, reason: String) {
                val key = "$reason|${buildMerchantTaskFlowKey(item)}"
                if (loggedSkipKeys.add(key)) {
                    Log.member("商家服务🏬[${item.title}]#$reason")
                }
            }
        }

        /**
         * 完成商家积分任务
         * @param taskCode 任务代码
         * @param actionCodes 行为代码候选
         * @param title 标题
         */
        private fun receiveMerchantTask(taskCode: String): Boolean = CoroutineUtils.run {
            try {
                val jo = JSONObject(AntMemberRpcCall.taskReceive(taskCode))
                val evaluation = evaluateMerchantRpc(jo)
                if (!evaluation.success) {
                    logMerchantRpcFailure("领取任务[$taskCode]", jo, evaluation)
                    return@run evaluation.failureType == MerchantRpcFailureType.DUPLICATE_REWARD
                }
                return@run true
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "receiveMerchantTask err:", t)
            }
            false
        }

        private suspend fun executeMerchantBrowseTask(
            taskCode: String,
            actionCodes: List<String>,
            title: String,
            taskStatus: String,
            targetCount: Int = 1
        ): Boolean = CoroutineUtils.run {
            try {
                if ("UNRECEIVED" == taskStatus && !receiveMerchantTask(taskCode)) {
                    return@run false
                }

                for (actionCode in actionCodes) {
                    var jo = JSONObject(AntMemberRpcCall.actioncode(actionCode))
                    var evaluation = evaluateMerchantRpc(jo)
                    if (!evaluation.success) {
                        logMerchantRpcFailure("查询任务活动[$title/$actionCode]", jo, evaluation)
                        if (evaluation.failureType == MerchantRpcFailureType.AUTH_LIMIT) {
                            return@run false
                        }
                        continue
                    }

                    var produceSuccess = false
                    var remainingCount = max(1, targetCount)
                    for (index in 0 until max(1, targetCount)) {
                        jo = JSONObject(AntMemberRpcCall.produce(actionCode))
                        evaluation = evaluateMerchantRpc(jo)
                        if (!evaluation.success) {
                            logMerchantRpcFailure("任务打点[$title/$actionCode]", jo, evaluation)
                            if (evaluation.failureType == MerchantRpcFailureType.AUTH_LIMIT) {
                                return@run false
                            }
                            break
                        }
                        produceSuccess = true

                        val refreshedTask = queryMerchantTaskByCode(taskCode) ?: break
                        val refreshedStatus = refreshedTask.optString("status")
                        if ("NEED_RECEIVE" == refreshedStatus || ("PROCESSING" != refreshedStatus && "UNRECEIVED" != refreshedStatus)) {
                            break
                        }

                        val refreshedRemainingCount = resolveMerchantTaskRemainingCount(refreshedTask) ?: break
                        if (refreshedRemainingCount <= 0 || refreshedRemainingCount >= remainingCount) {
                            break
                        }
                        remainingCount = refreshedRemainingCount
                    }

                    if (produceSuccess) {
                        Log.member("商家服务🏬[完成任务$title]")
                        return@run true
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "executeMerchantBrowseTask err:", t)
            }
            false
        }

        private fun queryMerchantTaskByCode(taskCode: String): JSONObject? {
            if (taskCode.isEmpty()) {
                return null
            }
            val taskGroups = queryMerchantTaskGroups()
            for (taskList in taskGroups) {
                for (i in 0..<taskList.length()) {
                    val task = taskList.optJSONObject(i) ?: continue
                    if (taskCode == task.optString("taskCode")) {
                        return task
                    }
                }
            }
            return null
        }

        private enum class MerchantRpcFailureType {
            AUTH_LIMIT,
            NO_ACTIVITY,
            DUPLICATE_REWARD,
            DEPRECATED_SOURCE,
            NON_RETRYABLE
        }

        private data class MerchantRpcEvaluation(
            val success: Boolean,
            val code: String,
            val message: String,
            val failureType: MerchantRpcFailureType? = null
        )

        private fun evaluateMerchantRpc(response: JSONObject): MerchantRpcEvaluation {
            val success = response.optBoolean("success") ||
                response.optString("resultCode").equals("SUCCESS", true) ||
                response.optString("errCode") == "0"
            val code = sequenceOf(
                response.optString("errorCode"),
                response.optString("resultCode"),
                response.opt("error")?.toString(),
                response.optString("errorTip"),
                response.optString("errCode"),
                response.opt("errorNo")?.toString()
            ).firstOrNull { !it.isNullOrBlank() && it != "0" }
                .orEmpty()
            val message = sequenceOf(
                response.optString("errorMsg"),
                response.optString("errorMessage"),
                response.optString("resultDesc"),
                response.optString("memo"),
                response.optString("desc")
            ).firstOrNull { it.isNotBlank() }
                .orEmpty()
            if (success) {
                return MerchantRpcEvaluation(
                    success = true,
                    code = code,
                    message = message
                )
            }
            val failureType = when {
                code == "1009" ||
                    message.contains("伺服器繁忙") ||
                    message.contains("服务器繁忙") ||
                    message.contains("請稍後再試") ||
                    message.contains("请稍后再试") ||
                    message.contains("訪問被拒絕") ||
                    message.contains("访问被拒绝") -> MerchantRpcFailureType.AUTH_LIMIT

                code.equals("RESULT_IS_NULL", true) ||
                    message.contains("通过actionCode查询的任务活动为空") -> MerchantRpcFailureType.NO_ACTIVITY

                code == "392" ||
                    message == "任务已领取,无法重复领取" ||
                    message == "宝箱奖励已领取" -> MerchantRpcFailureType.DUPLICATE_REWARD

                code == "3000" ||
                    message.contains("系統出錯，正在排查") ||
                    message.contains("系统出错，正在排查") -> MerchantRpcFailureType.DEPRECATED_SOURCE

                else -> MerchantRpcFailureType.NON_RETRYABLE
            }
            return MerchantRpcEvaluation(
                success = false,
                code = code,
                message = message,
                failureType = failureType
            )
        }

        private fun buildMerchantRpcFailureDetail(evaluation: MerchantRpcEvaluation, response: JSONObject): String {
            return when {
                evaluation.code.isNotBlank() && evaluation.message.isNotBlank() -> "${evaluation.code}/${evaluation.message}"
                evaluation.code.isNotBlank() -> evaluation.code
                evaluation.message.isNotBlank() -> evaluation.message
                else -> response.toString()
            }
        }

        private fun logMerchantRpcFailure(
            scene: String,
            response: JSONObject,
            evaluation: MerchantRpcEvaluation = evaluateMerchantRpc(response)
        ) {
            val detail = buildMerchantRpcFailureDetail(evaluation, response)
            when (evaluation.failureType) {
                MerchantRpcFailureType.AUTH_LIMIT ->
                    Log.member("商家服务🏬[$scene]#业务受限，本轮跳过:$detail")

                MerchantRpcFailureType.NO_ACTIVITY ->
                    Log.member("商家服务🏬[$scene]#当前无可执行活动，跳过:$detail")

                MerchantRpcFailureType.DUPLICATE_REWARD ->
                    Log.member("商家服务🏬[$scene]#奖励已领取，跳过重复领取:$detail")

                MerchantRpcFailureType.DEPRECATED_SOURCE ->
                    Log.member("商家服务🏬[$scene]#旧链路不可用，已停止使用:$detail")

                MerchantRpcFailureType.NON_RETRYABLE, null ->
                    Log.member("商家服务🏬[$scene]#接口失败:$detail")
            }
        }

        private fun merchantTaskFailureResult(
            item: TaskFlowItem,
            rpc: String,
            response: JSONObject,
            evaluation: MerchantRpcEvaluation = evaluateMerchantRpc(response),
            actionCode: String = ""
        ): TaskFlowActionResult {
            val failureType = when (evaluation.failureType) {
                MerchantRpcFailureType.DUPLICATE_REWARD -> TaskRpcFailureType.TERMINAL_DONE
                MerchantRpcFailureType.AUTH_LIMIT -> TaskRpcFailureType.RETRYABLE_RPC
                MerchantRpcFailureType.NO_ACTIVITY -> TaskRpcFailureType.BUSINESS_LIMIT
                MerchantRpcFailureType.DEPRECATED_SOURCE -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
                MerchantRpcFailureType.NON_RETRYABLE,
                null -> classifyMerchantTaskFailure(evaluation.code, evaluation.message, response)
            }
            return TaskFlowActionResult.failure(
                failureType = failureType,
                code = evaluation.code,
                message = evaluation.message.ifBlank { buildMerchantRpcFailureDetail(evaluation, response) },
                rpc = rpc,
                raw = response.toString(),
                detail = merchantTaskActionDetail(item, actionCode.ifBlank { rpc.substringAfterLast('.') }),
                stopCurrentRound = failureType == TaskRpcFailureType.RETRYABLE_RPC
            )
        }

        private fun receiveMerchantPointBallResult(
            pointBallId: String,
            title: String,
            reward: String
        ): TaskFlowActionResult {
            val response = JSONObject(AntMemberRpcCall.ballReceive(pointBallId))
            val evaluation = evaluateMerchantRpc(response)
            if (!evaluation.success) {
                return TaskFlowActionResult.failure(
                    failureType = if (evaluation.failureType == MerchantRpcFailureType.DUPLICATE_REWARD) {
                        TaskRpcFailureType.TERMINAL_DONE
                    } else {
                        classifyMerchantTaskFailure(evaluation.code, evaluation.message, response)
                    },
                    code = evaluation.code,
                    message = evaluation.message.ifBlank { buildMerchantRpcFailureDetail(evaluation, response) },
                    rpc = "AntMemberRpcCall.ballReceive",
                    raw = response.toString(),
                    detail = "pointBallId=$pointBallId taskName=$title reward=${reward.ifBlank { "UNKNOWN" }}"
                )
            }
            val pointReceived = response.optJSONObject("data")?.optString("pointReceived").orEmpty()
            if (pointReceived.isNotEmpty()) {
                Log.member("商家服务🏬[$title]#领取积分$pointReceived")
            } else if (reward.isNotEmpty()) {
                Log.member("商家服务🏬[$title]#领取积分$reward")
            } else {
                Log.member("商家服务🏬[$title]#领取积分")
            }
            return TaskFlowActionResult.success()
        }

        private fun classifyMerchantTaskFailure(
            code: String,
            message: String,
            responseObject: JSONObject
        ): TaskRpcFailureType {
            val combined = "$code $message ${responseObject.optString("desc")} ${responseObject.optString("resultView")}"
            return when {
                merchantContainsAny(
                    combined,
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

                code == "400000040" ||
                    merchantContainsAny(combined, "不支持rpc调用", "不支持RPC完成") ->
                    TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE

                code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                    merchantContainsAny(combined, "参数错误", "参数非法", "任务ID非法", "模板不存在", "生活记录模板不存在") ->
                    TaskRpcFailureType.NON_RETRYABLE_INVALID

                code in setOf("3000", "1009", "I07", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK", "SYSTEM_BUSY", "NETWORK_ERROR") ||
                    merchantContainsAny(combined, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试", "需要验证", "访问异常") ->
                    TaskRpcFailureType.RETRYABLE_RPC

                code.startsWith("100010") ||
                    code.contains("LIMIT", ignoreCase = true) ||
                    code == "NOT_PROMO_RULE_QUALIFIED" ||
                    merchantContainsAny(combined, "上限", "限制", "受限", "不可领取", "资格不足", "风控", "风险", "访问被拒绝") ->
                    TaskRpcFailureType.BUSINESS_LIMIT

                else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            }
        }

        private fun merchantContainsAny(value: String, vararg keywords: String): Boolean {
            return keywords.any { value.contains(it, ignoreCase = true) }
        }

        private fun buildMerchantTaskFlowKey(item: TaskFlowItem): String {
            return item.id.ifBlank {
                item.raw?.optString("pointBallId").orEmpty().ifBlank { item.title }
            }
        }

        private fun merchantTaskActionDetail(item: TaskFlowItem, action: String): String {
            return "taskCode=${item.type.ifBlank { "UNKNOWN" }} taskName=${item.title.ifBlank { "UNKNOWN" }} " +
                "status=${item.status.ifBlank { "UNKNOWN" }} action=$action " +
                "actionType=${item.actionType.ifBlank { "UNKNOWN" }} progress=${item.progress.ifBlank { "UNKNOWN" }}"
        }

        private fun extractMerchantTaskCurrent(task: JSONObject): Int? {
            val current = task.optInt("current", Int.MIN_VALUE)
            if (current != Int.MIN_VALUE) {
                return current
            }
            val currentCount = task.optInt("currentCount", Int.MIN_VALUE)
            return currentCount.takeIf { it != Int.MIN_VALUE }
        }

        private fun extractMerchantTaskLimit(task: JSONObject): Int? {
            val target = task.optInt("target", Int.MIN_VALUE)
            if (target != Int.MIN_VALUE) {
                return target
            }
            val targetCount = task.optInt("targetCount", Int.MIN_VALUE)
            return targetCount.takeIf { it != Int.MIN_VALUE }
        }

        private fun buildMerchantTaskProgress(task: JSONObject, current: Int?, limit: Int?): String {
            val reward = task.optString("reward", task.optString("point"))
            val progress = if (current != null && limit != null && limit > 0) {
                "$current/$limit"
            } else {
                ""
            }
            return listOf(
                progress.takeIf { it.isNotBlank() }?.let { "progress=$it" },
                reward.takeIf { it.isNotBlank() }?.let { "reward=$it" }
            ).filterNotNull().joinToString(" ")
        }

        private fun canRunMerchantService(): Boolean = CoroutineUtils.run {
            try {
                val jo = JSONObject(AntMemberRpcCall.transcodeCheck())
                val evaluation = evaluateMerchantRpc(jo)
                if (evaluation.success) {
                    val data = jo.optJSONObject("data")
                    if (data?.optBoolean("isOpened") == true) {
                        return@run true
                    }
                    Log.member("商家服务🏬[未开通，本轮跳过]")
                    return@run false
                }
                logMerchantRpcFailure("开通检查", jo, evaluation)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "canRunMerchantService err:", t)
            }
            false
        }

        private fun doMerchantZcjSignIn(): Boolean = CoroutineUtils.run {
            try {
                val queryResp = JSONObject(AntMemberRpcCall.zcjSignInQuery())
                if (!ResChecker.checkRes(TAG, queryResp)) {
                    return@run false
                }
                val button = queryResp.optJSONObject("data")?.optJSONObject("button") ?: return@run false
                when (button.optString("status")) {
                    "RECEIVED" -> return@run true
                    "UNRECEIVED" -> {
                        val executeResp = JSONObject(AntMemberRpcCall.zcjSignInExecute())
                        if (!ResChecker.checkRes(TAG, executeResp)) {
                            Log.member("doMerchantZcjSignIn err:$executeResp")
                            return@run false
                        }
                        val data = executeResp.optJSONObject("data")
                        val reward = data?.optString("todayReward").orEmpty()
                        val widgetName = data?.optString("widgetName").orEmpty().ifEmpty { "招财金签到" }
                        if (reward.isNotEmpty()) {
                            Log.member("商家服务🏬[$widgetName]#获得积分$reward")
                        } else {
                            Log.member("商家服务🏬[$widgetName]")
                        }
                        return@run true
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "doMerchantZcjSignIn err:", t)
            }
            false
        }

        private fun queryMerchantTaskGroups(): List<JSONArray> {
            try {
                val response = JSONObject(AntMemberRpcCall.taskListQuery())
                val evaluation = evaluateMerchantRpc(response)
                if (!evaluation.success) {
                    logMerchantRpcFailure("积分任务列表", response, evaluation)
                    return emptyList()
                }
                val data = response.optJSONObject("data")
                val planCode = data?.optString("planCode").orEmpty()
                if (planCode.isNotBlank() && !planCode.equals("MORE", true)) {
                    Log.member("商家服务🏬[积分任务列表]#返回计划$planCode，本轮跳过")
                    return emptyList()
                }
                val taskList = data?.optJSONArray("taskList") ?: return emptyList()
                if (taskList.length() <= 0) {
                    return emptyList()
                }
                return listOf(taskList)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "queryMerchantTaskGroups taskListQuery err:", t)
            }
            return emptyList()
        }

        private suspend fun processMerchantTask(task: JSONObject): Boolean = CoroutineUtils.run {
            val taskStatus = task.optString("status")
            if (taskStatus.isEmpty()) {
                return@run false
            }

            val title = task.optString("title", task.optString("taskName", "商家任务"))
            val reward = task.optString("reward", task.optString("point"))
            val taskCode = task.optString("taskCode")

            if (TaskBlacklist.isTaskInBlacklist(memberTaskBlacklistModule, title) ||
                TaskBlacklist.isTaskInBlacklist(memberTaskBlacklistModule, taskCode)
            ) {
                Log.member("商家服务🏬[$title]#黑名单任务，停止执行")
                return@run false
            }

            if ("NEED_RECEIVE" == taskStatus) {
                val pointBallId = task.optString("pointBallId")
                if (pointBallId.isNotEmpty()) {
                    return@run receiveMerchantPointBall(pointBallId, title, reward)
                }
                return@run false
            }

            if ("PROCESSING" != taskStatus && "UNRECEIVED" != taskStatus) {
                return@run false
            }

            val bizId = resolveMerchantBizId(task)
            if ("PROCESSING" == taskStatus && bizId.isNotEmpty()) {
                val jo = JSONObject(AntMemberRpcCall.taskFinish(bizId))
                val evaluation = evaluateMerchantRpc(jo)
                if (evaluation.success) {
                    Log.member("商家服务🏬[$title]#领取积分$reward")
                    return@run true
                }
                logMerchantRpcFailure("领取积分[$title]", jo, evaluation)
                return@run evaluation.failureType == MerchantRpcFailureType.DUPLICATE_REWARD
            }

            val actionCodes = resolveMerchantActionCodes(task)
            if (taskCode.isEmpty() || actionCodes.isEmpty()) {
                return@run false
            }

            return@run executeMerchantBrowseTask(taskCode, actionCodes, title, taskStatus, resolveMerchantTaskTargetCount(task))
        }

        private fun resolveMerchantBizId(task: JSONObject): String {
            return task.optJSONObject("extendLog")
                ?.optJSONObject("bizExtMap")
                ?.optString("bizId")
                .orEmpty()
        }

        private fun resolveMerchantActionCodes(task: JSONObject): List<String> {
            val candidates = LinkedHashSet<String>()
            val buttonActionCode = task.optJSONObject("button")
                ?.optJSONObject("extInfo")
                ?.optString("actionCode")
                .orEmpty()
            addMerchantActionCodeCandidates(candidates, buttonActionCode)

            val taskActionCode = task.optString("actionCode")
            addMerchantActionCodeCandidates(candidates, taskActionCode)

            val taskCode = task.optString("taskCode")
            if (task.has("sendPointImmediately") && taskCode.isNotEmpty()) {
                addMerchantActionCodeCandidate(candidates, "${taskCode}_VIEWED")
            }
            addMerchantActionCodeCandidate(candidates, when (taskCode) {
                "SYH_CPC_DYNAMIC" -> "SYH_CPC_DYNAMIC_VIEWED"
                "JFLLRW_TASK" -> "JFLL_VIEWED"
                "ZFBHYLLRW_TASK" -> "ZFBHYLL_VIEWED"
                "QQKLLRW_TASK" -> "QQKLL_VIEWED"
                "RCR_RWZX_LLRW_TASK" -> "rcr_llrw_VIEWED"
                "SSLLRW_TASK" -> "SSLL_VIEWED"
                "CYLLRW_TASK" -> "CYLLRW_VIEWED"
                "ELMGYLLRW2_TASK" -> "ELMGYLL_VIEWED"
                "ZMXYLLRW_TASK" -> "ZMXYLL_VIEWED"
                "GXYKPDDYH_TASK" -> "xykhkzd_VIEWED"
                "HHKLLRW_TASK" -> "HHKLLX_VIEWED"
                "TBNCLLRW_TASK" -> "TBNCLLRW_TASK_VIEWED"
                else -> null
            })
            return candidates.toList()
        }

        private fun addMerchantActionCodeCandidates(candidates: LinkedHashSet<String>, actionCode: String?) {
            val normalizedActionCode = actionCode.orEmpty().trim()
            if (normalizedActionCode.isEmpty()) {
                return
            }
            candidates.add(normalizedActionCode)
            if (!normalizedActionCode.endsWith("_VIEWED")) {
                candidates.add("${normalizedActionCode}_VIEWED")
            }
        }

        private fun addMerchantActionCodeCandidate(candidates: LinkedHashSet<String>, actionCode: String?) {
            val normalizedActionCode = actionCode.orEmpty().trim()
            if (normalizedActionCode.isNotEmpty()) {
                candidates.add(normalizedActionCode)
            }
        }

        private fun resolveMerchantTaskTargetCount(task: JSONObject): Int {
            return max(1, resolveMerchantTaskRemainingCount(task) ?: 1)
        }

        private fun resolveMerchantTaskRemainingCount(task: JSONObject): Int? {
            val target = task.optInt("target", Int.MIN_VALUE)
            val current = task.optInt("current", 0)
            if (target != Int.MIN_VALUE) {
                return (target - current).coerceAtLeast(0)
            }

            val targetCount = task.optInt("targetCount", Int.MIN_VALUE)
            val currentCount = task.optInt("currentCount", 0)
            if (targetCount != Int.MIN_VALUE) {
                return (targetCount - currentCount).coerceAtLeast(0)
            }

            return null
        }

        private suspend fun collectMerchantPointBalls(): Boolean = CoroutineUtils.run {
            try {
                val jo = JSONObject(AntMemberRpcCall.merchantBallQuery())
                val evaluation = evaluateMerchantRpc(jo)
                if (!evaluation.success) {
                    logMerchantRpcFailure("查询积分球", jo, evaluation)
                    return@run false
                }
                val pointBalls = jo.optJSONObject("data")?.optJSONArray("pointBalls") ?: return@run false
                var received = false
                for (i in 0..<pointBalls.length()) {
                    val pointBall = pointBalls.optJSONObject(i) ?: continue
                    val ballId = pointBall.optString("id")
                    if (ballId.isEmpty()) {
                        continue
                    }
                    val ballName = pointBall.optString("name", "积分球")
                    val receiveResp = JSONObject(AntMemberRpcCall.ballReceive(ballId))
                    val receiveEvaluation = evaluateMerchantRpc(receiveResp)
                    if (!receiveEvaluation.success) {
                        logMerchantRpcFailure("领取积分球[$ballName]", receiveResp, receiveEvaluation)
                        continue
                    }
                    val pointReceived = receiveResp.optJSONObject("data")?.optString("pointReceived").orEmpty()
                    if (pointReceived.isNotEmpty()) {
                        Log.member("商家服务🏬领取[$ballName]#获得积分$pointReceived")
                    } else {
                        Log.member("商家服务🏬领取[$ballName]")
                    }
                    received = true
                }
                return@run received
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "collectMerchantPointBalls err:", t)
            }
            false
        }

        private suspend fun receiveMerchantPointBall(
            pointBallId: String,
            title: String,
            reward: String
        ): Boolean = CoroutineUtils.run {
            try {
                val jo = JSONObject(AntMemberRpcCall.ballReceive(pointBallId))
                if (!ResChecker.checkRes(TAG, jo)) {
                    return@run false
                }
                val pointReceived = jo.optJSONObject("data")?.optString("pointReceived").orEmpty()
                if (pointReceived.isNotEmpty()) {
                    Log.member("商家服务🏬[$title]#领取积分$pointReceived")
                } else if (reward.isNotEmpty()) {
                    Log.member("商家服务🏬[$title]#领取积分$reward")
                } else {
                    Log.member("商家服务🏬[$title]#领取积分")
                }
                return@run true
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "receiveMerchantPointBall err:", t)
            }
            false
        }
    }

}

package io.github.aoguai.sesameag.task.antSesameCredit


import android.annotation.SuppressLint
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.Status.Companion.hasFlagToday
import io.github.aoguai.sesameag.data.Status.Companion.setFlagToday
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.entity.SesameGift
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionCheck
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleKind
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleState
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.hook.HookReadyChecker
import io.github.aoguai.sesameag.hook.internal.LocationHelper.requestLocationSuspend
import io.github.aoguai.sesameag.hook.internal.SecurityBodyHelper.getSecurityBodyData
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.model.ConfigPortScope
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.withPortScope
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskExecutionOutcome
import io.github.aoguai.sesameag.task.TaskExecutionReport
import kotlin.coroutines.EmptyCoroutineContext
import io.github.aoguai.sesameag.task.antFarm.AntFarm
import io.github.aoguai.sesameag.task.antOrchard.AntOrchard
import io.github.aoguai.sesameag.task.antOrchard.UrlUtil
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.GameCenterPlayRpcCall
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowDecision
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.task.exchange.ExchangeCost
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectCatalog
import io.github.aoguai.sesameag.task.exchange.ExchangeItem
import io.github.aoguai.sesameag.task.exchange.ExchangeLimit
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionRow
import io.github.aoguai.sesameag.task.exchange.ExchangeFetchPacing
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsCache
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsSnapshot
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenisher
import io.github.aoguai.sesameag.task.exchange.ExchangeSafety
import io.github.aoguai.sesameag.task.exchange.ExchangeSafetyRules
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.RpcOfflineRisk
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.TaskBlacklist.autoAddToBlacklist
import io.github.aoguai.sesameag.util.UserDataStoreManager
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.SesameGiftMap
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import io.github.aoguai.sesameag.util.SerialTaskQueue

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Objects
import java.util.regex.Pattern
import kotlin.math.max

class AntSesameCredit : ModelTask() {
    override fun getName(): String = "芝麻信用"

    override fun getGroup(): ModelGroup = ModelGroup.SESAME_CREDIT

    override fun getIcon(): String = "AntMember.png"

    internal var collectSesame: BooleanModelField? = null
    internal var collectSesameWithOneClick: BooleanModelField? = null
    internal var sesameTask: BooleanModelField? = null
    internal var sesameAchievements: BooleanModelField? = null
    internal var sesameAlchemy: BooleanModelField? = null
    private var sesameAlchemyConsume: BooleanModelField? = null
    internal var enableZhimaTree: BooleanModelField? = null
    internal var sesameGrainExchange: BooleanModelField? = null
    private var sesameGrainExchangeList: SelectModelField? = null

    private val sesameWorkflowQueue = SerialTaskQueue()
    private val sesameCreditTaskBlacklistModule = "芝麻信用"
    private val sesameAlchemyTaskBlacklistModule = "芝麻炼金"
    private val sesamePushModelTaskSnapshots = linkedMapOf<String, SesamePushModelTaskSnapshot>()
    private val sesameFloatingBallFeedbackPending = linkedSetOf<String>()
    private val sesameAlchemyBottleCompletionPending = mutableSetOf<String>()
    private val zhimaPigeonFeedbackCategory = "ZMZY#FEED_ZM_CHICKEN"
    private val sesameFloatingBallFeedbackPendingPrefix = "sesame_floating_ball_feedback_pending:"

    private data class SesameFeedbackItem(
        val cateId: String,
        val title: String,
        val creditFeedbackId: String,
        val potentialSize: String,
    )

    private data class SesameExchangeCandidate(
        val item: ExchangeItem,
        val templateId: String,
        val pointNeeded: String,
    )

    internal data class SesameTaskRunSummary(
        val finishedAllRounds: Boolean = false,
        val completedCount: Int = 0,
        val skippedCount: Int = 0,
        val interrupted: Boolean = false,
    )

    private data class SesameLifeRecordCompletionSpec(
        val moduleName: String,
        val logPrefix: String,
        val sceneCode: String,
        val version: String,
        val changeRewardType: String? = null,
        val joinedRecordIds: MutableMap<String, String>? = null,
    )

    private data class SesameFloatingBallContract(
        val sceneId: String,
        val taskId: String,
        val source: String,
        val moduleId: String,
        val guideType: String,
        val gameAppId: String? = null,
        val gameId: String? = null,
        val gameModuleId: String? = null,
        val oriChInfo: String? = null,
        val trafficDriverId: String? = null,
        val floatingBallTypeList: List<String> = emptyList(),
        val componentChannel: String? = null,
        val componentScene: String? = null,
        val gameVersion: String? = null,
        val pageRequests: GameCenterPlayRpcCall.P2ePageRequests? = null,
    )

    private fun currentUserDataStore() = UserDataStoreManager.getInstance(
        AccountSessionCoordinator.currentUserId() ?: UserMap.currentUid,
    )

    private fun sesameFloatingBallFeedbackPendingKey(
        task: JSONObject,
        floatingBall: SesameFloatingBallContract,
    ): String {
        val owner = AccountSessionCoordinator.currentUserId()
            ?: UserMap.currentUid
            ?: "UNKNOWN_USER"
        val taskIdentity = task.optString("templateId").trim().ifBlank { floatingBall.taskId }
        return "$sesameFloatingBallFeedbackPendingPrefix$owner:$taskIdentity"
    }

    private fun hasSesameFloatingBallFeedbackPending(key: String): Boolean =
        key in sesameFloatingBallFeedbackPending || currentUserDataStore()?.hasPersistentFlag(key) == true

    private fun markSesameFloatingBallFeedbackPending(key: String) {
        sesameFloatingBallFeedbackPending += key
        currentUserDataStore()?.setPersistentFlag(key, System.currentTimeMillis() + 48 * 60 * 60 * 1000L)
    }

    private fun clearSesameFloatingBallFeedbackPending(key: String) {
        sesameFloatingBallFeedbackPending -= key
        currentUserDataStore()?.removePersistentFlag(key)
    }

    private fun submitSesameFloatingBallFeedbackOnce(
        task: JSONObject,
        taskTitle: String,
        spec: SesameLifeRecordCompletionSpec,
        feedbackPendingKey: String,
        taskIdentity: String,
    ): TaskFlowActionResult? {
        if (hasSesameFloatingBallFeedbackPending(feedbackPendingKey)) {
            Log.sesame("${spec.logPrefix}[任务反馈已提交，继续未完成的游戏动作]#$taskTitle taskId=$taskIdentity")
            return null
        }
        val feedbackResult =
            reportSesameTaskFeedbackResult(
                task = task,
                taskTitle = taskTitle,
                logPrefix = spec.logPrefix,
                moduleName = spec.moduleName,
                version = spec.version,
                sceneCode = spec.sceneCode,
                changeRewardType = spec.changeRewardType,
            )
        if (!feedbackResult.success || feedbackResult.failureType == TaskRpcFailureType.TERMINAL_DONE) {
            return feedbackResult
        }
        markSesameFloatingBallFeedbackPending(feedbackPendingKey)
        Log.sesame("${spec.logPrefix}[P2E任务反馈已接受]#$taskTitle taskId=$taskIdentity confirmationState=PENDING")
        return null
    }

    private data class ZhimaTreeTaskRef(
        val title: String,
        val prizeName: String,
        val status: String,
        val taskId: String?,
        val taskIdCandidates: List<String>,
        val needSignUp: Boolean,
        val needManuallyReceiveAward: Boolean,
        val templateCode: String,
        val appletType: String,
        val taskType: String,
        val taskMaterialType: String,
        val taskChannel: String,
        val chInfo: String,
        val refer: String,
        val playInfo: String,
        val appId: String,
        val sourceName: String,
    ) {
        fun describeCandidates(): String {
            if (taskIdCandidates.isEmpty()) {
                return "<empty>"
            }
            return taskIdCandidates.joinToString(" | ") { it.ifBlank { "<blank>" } }
        }
    }

    private data class SesamePushModelTaskSnapshot(
        val recordId: String,
        val title: String,
        val templateId: String,
        val merchantName: String,
        val actionUrl: String,
        val appId: String,
        val sourceName: String,
        val jumpToPushModel: Boolean,
        val completed: Boolean,
    )

    private data class ZhimaTreeTaskContext(
        val appId: String,
        val chInfo: String,
        val refer: String,
        val urlCandidates: List<String>,
    )

    private data class SesameCheckInCandidate(
        val checkInDate: String,
        val broken: Boolean,
        val currentDay: Boolean,
        val doubleReward: Boolean,
        val status: String,
    )

    private enum class SesameCheckInExecutionStatus {
        COMPLETE,
        NO_MORE_DIRECT_ACTION,
        FAILED,
    }

    private data class SesameCheckInExecutionResult(
        val status: SesameCheckInExecutionStatus,
        val initialCandidateCount: Int,
        val completedCandidateCount: Int,
        val remainingCandidateCount: Int,
        val failureRawResponse: String? = null,
        val failedCandidate: SesameCheckInCandidate? = null,
    )

    private data class SesameCheckInSceneSpec(
        val completeTask: (String) -> String,
    )

    private data class ZhimaTreeAdTaskRef(
        val title: String,
        val rewardText: String,
        val bizId: String,
        val spaceCode: String?,
    )

    private data class ZhimaTreeActionResult(
        val success: Boolean,
        val response: JSONObject?,
        val rawResponse: String?,
    )

    private data class ZhimaTreeClearArea(
        val clearArea: String,
        val sort: Int,
    )

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()

        modelFields.addField(
            BooleanModelField(
                "sesameTask",
                "芝麻信用 | 信用任务",
                false,
            ).withDesc("执行芝麻信用的涨分进度与芝麻粒相关每日任务。").also { sesameTask = it },
        )
        modelFields.addField(
            BooleanModelField("sesameAchievements", "成就馆 | 自动领取", false)
                .withDesc("自动领取已达成的成就勋章及等级奖励。").also { sesameAchievements = it },
        )
        modelFields.addField(
            BooleanModelField("collectSesame", "芝麻粒 | 领取", false)
                .withDesc(
                    "领取芝麻粒、阶段奖励和其他可收取的芝麻相关奖励。",
                ).also {
                    collectSesame = it
                },
        )
        modelFields.addField(
            BooleanModelField(
                "collectSesameWithOneClick",
                "芝麻粒 | 一键收取",
                false,
            ).withDesc("开启后优先走一键收取接口领取芝麻粒。需开启“芝麻粒 | 领取”。").also { collectSesameWithOneClick = it },
        )
        modelFields.addField(
            BooleanModelField(
                "sesameGrainExchange",
                "芝麻粒 | 兑换道具",
                false,
            ).withDesc("使用芝麻粒兑换已勾选的道具，适合长期清理库存。").also { sesameGrainExchange = it },
        )

        // 使用 SesameGiftMap 来存储和回显商品名称
        modelFields.addField(
            SelectModelField(
                "sesameGrainExchangeList",
                "芝麻粒 | 兑换列表",
                LinkedHashSet<String?>(),
            ) {
                refreshSesameGrainExchangeOptionsForSettings()
            }.withDesc("勾选允许自动兑换的芝麻粒商品，需开启“芝麻粒 | 兑换道具”。").also { sesameGrainExchangeList = it },
        )
        // 芝麻炼金
        modelFields.addField(
            BooleanModelField(
                "sesameAlchemy",
                "芝麻炼金 | 开启",
                false,
            ).withDesc("执行芝麻粒炼金的签到、任务和时段奖励领取。").also { sesameAlchemy = it },
        )
        modelFields.addField(
            BooleanModelField("sesameAlchemyConsume", "芝麻炼金 | 消耗芝麻粒", false)
                 .withPortScope(ConfigPortScope.GENERIC)
                .withDesc("允许按首页实时成本消耗芝麻粒炼金；关闭时仅使用免费次数。")
                .also { sesameAlchemyConsume = it },
        )
        // 芝麻树
        modelFields.addField(
            BooleanModelField("enableZhimaTree", "芝麻树 | 开启", false)
                .withDesc(
                    "执行芝麻树相关签到、任务和奖励领取。",
                ).also {
                    enableZhimaTree = it
                },
        )
        return modelFields
    }

    override fun runJava() {
        runBlocking(TaskExecutionReport.current()?.context ?: EmptyCoroutineContext) {
            sesameWorkflowQueue.submit {
            try {
                Log.sesame("执行开始-${getName()}")
                requestLocationSuspend()

                val deferredTasks = mutableListOf<Deferred<Unit>>()
                val sesamePlan = prepareSesameWorkflows(this, deferredTasks)
                deferredTasks.awaitAll()
                finishSesameWorkflows(sesamePlan)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                TaskExecutionReport.current()?.recordException()
                Log.printStackTrace(TAG, t)
            } finally {
                Log.sesame("执行结束-${getName()}")
            }
            }
        }
    }

    private fun scheduleSesameConfirmation(payload: JSONObject) {
        val context = ApplicationHook.appContext ?: return
        val owner = AccountSessionCoordinator.currentUserId()?.takeIf { it.isNotBlank() } ?: return
        val epoch = AccountSessionCoordinator.currentSessionEpoch()
        val record = if (payload.optBoolean("pigeon")) {
            val farm = Model.getModel(AntFarm::class.java) ?: return
            if (!farm.hasPendingZhimaPigeonRewardReceipt()) return
            val round = farm.pendingZhimaPigeonRewardRound()
            if (payload.has("pigeon_round") && payload.optString("pigeon_round") != round) return
            payload.put("pigeon_round", round)
            "pigeon:$round"
        } else payload.optString("recordId")
        if (record.isBlank() || hasFlagToday("sesame_confirmation_checked:$record")) return
        val dedupe = "$PERSISTENT_CONFIRMATION_KIND:$owner:$record"
        if (PersistentScheduleRegistry.list().any {
                it.dedupeKey == dedupe && it.sessionEpoch == epoch &&
                    it.state in setOf(PersistentScheduleState.SCHEDULED, PersistentScheduleState.QUEUED, PersistentScheduleState.RUNNING)
            }) return
        val attempt = payload.optInt("attempt", 0)
        if (attempt >= 3) {
            setFlagToday("sesame_confirmation_checked:$record")
            Log.sesame("芝麻任务[回查仍待确认，保留业务状态，结束本次自动回查] record=$record")
            return
        }
        payload.put("child_kind", PERSISTENT_CONFIRMATION_KIND)
            .put("owner_user_id", owner).put("session_epoch", epoch).put("attempt", attempt + 1)
        val schedule = UnifiedScheduler.schedulePersistentTrigger(
            context = context, name = "芝麻任务与奖励回查", kind = PersistentScheduleKind.MODULE_CHILD,
            triggerAtMs = System.currentTimeMillis(),
            dedupeKey = dedupe, payloadJson = payload.toString(), ownerUserId = owner, sessionEpoch = epoch,
        )
        if (schedule.state == PersistentScheduleState.FAILED) {
            Log.error(TAG, "芝麻任务回查调度注册失败:${schedule.lastError}")
        }
    }

    internal fun triggerPersistentConfirmation(payloadJson: String, scheduleId: String, source: String): Boolean {
        val payload = JSONObject(payloadJson)
        var needsConfirmation = false
        return executePersistentChild(
            scheduleId = scheduleId,
            ownerUserId = payload.optString("owner_user_id"),
            sessionEpoch = payload.optLong("session_epoch"),
            source = "$PERSISTENT_CONFIRMATION_KIND:$source",
            workflowQueue = sesameWorkflowQueue,
            afterExecution = { if (needsConfirmation) scheduleSesameConfirmation(payload) },
        ) {
            val eligible = isEnable() &&
                if (payload.optBoolean("pigeon")) sesameAlchemy?.value == true || collectSesame?.value == true
                else if (payload.optString("version") == "alchemy") sesameAlchemy?.value == true else sesameTask?.value == true
            if (!eligible) return@executePersistentChild TaskExecutionOutcome.SKIPPED
            var confirmed = false
            var queryFailed = false
            if (payload.optBoolean("pigeon")) {
                Model.getModel(AntFarm::class.java)?.let { farm ->
                    if (payload.optString("pigeon_round") != farm.pendingZhimaPigeonRewardRound()) {
                        return@executePersistentChild TaskExecutionOutcome.SKIPPED
                    }
                    confirmed = !farm.hasPendingZhimaPigeonRewardReceipt() || collectPendingZhimaPigeonReward(farm)
                }
            } else {
                val result = confirmSesameLifeRecord(
                    version = payload.optString("version"),
                    templateId = payload.optString("templateId"),
                    recordId = payload.optString("recordId"),
                )
                confirmed = result.success
                queryFailed = result.failureType != null
                if (queryFailed) {
                    Log.error(TAG, "芝麻生活记录回查失败 record=${payload.optString("recordId")} rpc=${result.rpc} code=${result.code} message=${result.message} raw=${result.raw}")
                }
            }
            if (!queryFailed && (collectSesame?.value == true || sesameAlchemy?.value == true)) {
                collectSesame(collectSesameWithOneClick?.value == true || sesameAlchemy?.value == true)
                if (confirmed && !payload.optBoolean("pigeon")) {
                    val rewards = queryExplicitUnclaimedSesameFeedbackItems("芝麻生活记录[奖励回查]")
                    queryFailed = rewards == null
                    confirmed = rewards?.isEmpty() == true
                    if (rewards?.isNotEmpty() == true) {
                        Log.sesame("芝麻生活记录[任务已完成，奖励仍待确认] record=${payload.optString("recordId")}")
                    }
                }
            }
            if (confirmed && !payload.optBoolean("pigeon")) {
                UserDataStoreManager.getInstance(payload.optString("owner_user_id"))
                    ?.remove("sesame_user_growth_pending:${payload.optString("recordId")}")
                Log.sesame("芝麻生活记录[已回查确认完成] record=${payload.optString("recordId")}")
            }
            if (confirmed && !queryFailed && sesameAlchemy?.value == true) {
                val executionState = TaskFlowExecutionState()
                resetSesamePushModelTaskSnapshots()
                processAlchemyTaskListsUntilStable(executionState)
                finishSesameAlchemy(executionState)
                collectSesame(collectSesameWithOneClick?.value == true || sesameAlchemy?.value == true)
            }
            needsConfirmation = !confirmed
            when {
                queryFailed -> TaskExecutionOutcome.FAILED
                confirmed -> TaskExecutionOutcome.SUCCESS
                else -> TaskExecutionOutcome.DEFERRED
            }
        }
    }

    internal fun handleGrowthGuideTasks() {
        try {
            if (ApplicationHookConstants.isOffline()) {
                Log.sesame("成长锦囊因离线模式跳过，保留后续重试机会")
                return
            }
            Log.sesame("开始处理成长锦囊")
            var resp: String?
            try {
                resp = AntSesameCreditRpcCall.Zmxy.queryGrowthGuideToDoList()
            } catch (e: Throwable) {
                Log.printStackTrace("$TAG.handleGrowthGuideTasks.queryGrowthGuideToDoList", e)
                return
            }

            if (resp.isNullOrEmpty()) {
                Log.sesame("信誉任务列表返回空")
                return
            }

            var root: JSONObject
            try {
                root = JSONObject(resp)
            } catch (e: Throwable) {
                Log.printStackTrace("$TAG.handleGrowthGuideTasks.parseRootJson", e)
                return
            }

            if (!ResChecker.checkRes(TAG, root)) {
                Log.sesame("信誉任务列表获取失败: " + root.optString("resultView", resp))
                return
            }
            root = acceptGrowthGuideTasks(root)
            // 接取后的最新列表同时用于推进所属业务。
            val growthGuideList = root.optJSONArray("growthGuideList")
            growthGuideList?.length() ?: 0

            // 待处理任务列表
            val toDoList = root.optJSONArray("toDoList")
            val toDoCount = toDoList?.length() ?: 0
            if (toDoList == null || toDoCount == 0) {
                return
            }

            for (i in 0..<toDoList.length()) {
                var task: JSONObject? = null
                try {
                    task = toDoList.optJSONObject(i)
                } catch (_: Throwable) {
                }

                if (task == null) continue

                val behaviorId = task.optString("behaviorId", "")
                val title = task.optString("title", "")
                val status = task.optString("status", "")
                val subTitle = task.optString("subTitle", "")

                if ("wait_receive" == status) {
                    continue
                }

                // ===== 2.2 每日问答 =====
                if ("meiriwenda" == behaviorId && "wait_doing" == status) { // 如果等待去做才执行，一般不会进入下面的今日已参与判断
                    try {
                        // ① 查询题目
                        val quizResp = AntSesameCreditRpcCall.Zmxy.queryDailyQuiz(behaviorId)
                        val quizJo: JSONObject?
                        try {
                            quizJo = JSONObject(quizResp)
                        } catch (e: Throwable) {
                            Log.printStackTrace(
                                "$TAG.handleGrowthGuideTasks.parseDailyQuiz 每日问答[解析失败]$quizResp",
                                e,
                            )
                            continue
                        }

                        if (!ResChecker.checkRes(TAG, quizJo)) {
                            continue
                        }

                        val data = quizJo.optJSONObject("data")
                        if (data == null) {
                            Log.error("$TAG.handleGrowthGuideTasks", "每日问答[返回缺少data]")
                            continue
                        }

                        val qVo = data.optJSONObject("questionVo")
                        if (qVo == null) {
                            Log.error("$TAG.handleGrowthGuideTasks", "每日问答[缺少questionVo]")
                            continue
                        }

                        val rightAnswer = qVo.optJSONObject("rightAnswer")
                        if (rightAnswer == null) {
                            Log.error("$TAG.handleGrowthGuideTasks", "每日问答[缺少rightAnswer]")
                            continue
                        }

                        val bizDate = data.optLong("bizDate", 0L)
                        val questionId = qVo.optString("questionId", "")
                        val questionContent = qVo.optString("questionContent", "")
                        val answerId = rightAnswer.optString("answerId", "")
                        val answerContent = rightAnswer.optString("answerContent", "")

                        if (bizDate <= 0 || questionId.isEmpty() || answerId.isEmpty()) {
                            Log.error("$TAG.handleGrowthGuideTasks", "每日问答[关键字段缺失]")
                            continue
                        }

                        // ② 提交答案
                        val pushResp =
                            AntSesameCreditRpcCall.Zmxy.pushDailyTask(
                                behaviorId,
                                bizDate,
                                answerId,
                                questionId,
                                "RIGHT",
                            )

                        val pushJo: JSONObject?
                        try {
                            pushJo = JSONObject(pushResp)
                        } catch (e: Throwable) {
                            Log.printStackTrace(
                                "$TAG.handleGrowthGuideTasks.parsePushDailyTask 每日问答[提交解析失败]$quizResp",
                                e,
                            )
                            continue
                        }

                        if (ResChecker.checkRes(TAG, pushJo)) {
                            Log.sesame(
                                (
                                    "信誉任务[每日答题成功] " + questionContent + " | 答案=" + answerContent + "(" + answerId + ")" +
                                        (if (subTitle.isEmpty()) "" else " | $subTitle")
                                ),
                            )
                        } else {
                            Log.error(
                                "$TAG.handleGrowthGuideTasks",
                                "每日问答[提交失败] resp=$pushResp",
                            )
                        }
                    } catch (e: Throwable) {
                        Log.printStackTrace("$TAG.handleGrowthGuideTasks.meiriwenda", e)
                    }
                }

                // ===== 2.3 视频问答 =====
                if ("shipingwenda" == behaviorId && "wait_doing" == status) {
                    val bizDate = System.currentTimeMillis()
                    val questionId = "question3"
                    val answerId = "A"
                    val answerType = "RIGHT"

                    val pushResp =
                        AntSesameCreditRpcCall.Zmxy.pushDailyTask(
                            behaviorId,
                            bizDate,
                            answerId,
                            questionId,
                            answerType,
                        )

                    val jo: JSONObject?
                    try {
                        jo = JSONObject(pushResp)
                    } catch (e: Throwable) {
                        Log.printStackTrace(
                            "$TAG.handleGrowthGuideTasks.parsePushDailyTask 视频问答[提交解析失败]$pushResp",
                            e,
                        )
                        continue // 改为continue，避免return影响循环
                    }

                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.sesame("信誉任务[视频问答提交成功] → ")
                    } else {
                        Log.error("$TAG.handleGrowthGuideTasks", "视频问答[提交失败] → $pushResp")
                    }
                }

                // ===== 2.4 芭芭农场施肥 =====
                if ("babanongchang_7d" == behaviorId && "wait_doing" == status) {
                    try {
                        val orchard = Model.getModel(AntOrchard::class.java)
                        if (orchard?.spreadManureForSesame() == true) {
                            Log.sesame("信誉任务[芭芭农场施肥成功] $title")
                        } else {
                            Log.sesame("信誉任务[芭芭农场施肥] 本轮未推进，保留后续任务回查")
                        }
                    } catch (e: Throwable) {
                        Log.printStackTrace("$TAG.handleGrowthGuideTasks.babanongchang", e)
                    }
                }

                if ("wait_doing" == status &&
                    behaviorId !in setOf("meiriwenda", "shipingwenda", "babanongchang_7d")
                ) {
                    Log.sesame(
                        "成长锦囊[等待所属业务进度] " +
                            "title=$title behaviorId=$behaviorId subTitle=$subTitle",
                    )
                }
            }
        } catch (e: Throwable) {
            Log.printStackTrace("$TAG.handleGrowthGuideTasks.Fatal", e)
        }
    }

    private fun acceptGrowthGuideTasks(initialPage: JSONObject): JSONObject {
        val flag = "${StatusFlags.FLAG_SESAME_DO_ALL_AVAILABLE_TASK}::growthGuideAccept"
        if (hasFlagToday(flag)) return initialPage
        var firstPage: JSONObject? = initialPage
        var latestPage = initialPage
        val pending = mutableSetOf<String>()
        TaskFlowEngine(object : TaskFlowAdapter {
            override val moduleName = "芝麻信用"
            override val flowName = "成长锦囊接取"
            override fun query(): JSONObject =
                (firstPage ?: JSONObject(AntSesameCreditRpcCall.Zmxy.queryGrowthGuideToDoList())).also {
                    firstPage = null
                    latestPage = it
                }
            override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
                if (pending.isEmpty()) setFlagToday(flag)
            }
            override fun isQuerySuccess(response: JSONObject): Boolean = ResChecker.checkRes(TAG, response)
            override fun isQueryComplete(response: JSONObject): Boolean = response.optJSONArray("toDoList") != null
            override fun extractItems(response: JSONObject): List<TaskFlowItem> {
                val tasks = response.optJSONArray("toDoList") ?: return emptyList()
                return (0 until tasks.length()).mapNotNull { index ->
                    val task = tasks.optJSONObject(index) ?: return@mapNotNull null
                    val id = task.optString("behaviorId")
                    val status = task.optString("status")
                    if (status in setOf("wait_doing", "limit", "done_today", "finished") && pending.remove(id)) {
                        Log.sesame("成长锦囊[接取已确认] ${task.optString("title", id)}")
                    }
                    TaskFlowItem(id = id, title = task.optString("title", id), status = status, raw = task)
                }
            }
            override fun mapPhase(item: TaskFlowItem): TaskFlowPhase = when {
                item.id.isBlank() || item.status.isBlank() -> TaskFlowPhase.UNKNOWN
                item.status == "wait_receive" -> TaskFlowPhase.SIGNUP_REQUIRED
                item.status in setOf("wait_doing", "limit", "done_today", "finished") -> TaskFlowPhase.TERMINAL
                else -> TaskFlowPhase.UNKNOWN
            }
            override fun signup(item: TaskFlowItem): TaskFlowActionResult {
                val response = JSONObject(AntSesameCreditRpcCall.Zmxy.openBehaviorCollect(item.id))
                if (!ResChecker.checkRes(TAG, response)) {
                    val code = response.optString("resultCode", response.optString("errorCode"))
                    return TaskFlowActionResult.failure(
                        failureType = classifySesameTaskFailure(code, response), code = code,
                        message = buildSesameRpcMessage(response, response.toString()),
                        rpc = "openBehaviorCollect", raw = response.toString(),
                        stopCurrentRound = isSesameTaskFlowInterrupted(response),
                        continueCurrentRoundOnFailure = true,
                    )
                }
                pending.add(item.id)
                return TaskFlowActionResult.defer(
                    deferredReason = DeferredReason.STATE_CONFIRMATION,
                    message = "锦囊接取已受理，等待进入进行中状态", refreshAfterAction = true,
                )
            }
            override fun logInfo(message: String) = Log.sesame(message)
            override fun logError(message: String) = Log.error(TAG, message)
        }).run()
        if (pending.isNotEmpty()) {
            Log.error(TAG, "成长锦囊接取状态待确认 behaviorIds=$pending，保留后续重试")
        }
        return latestPage
    }

    internal fun handleCreditAchievements() {
        if (ApplicationHookConstants.isOffline()) return
        try {
            val home = JSONObject(AntSesameCreditRpcCall.queryAccomplishmentHome())
            if (!ResChecker.checkRes(TAG, home)) {
                Log.error(TAG, "成就馆查询失败 raw=$home")
                return
            }
            val data = home.optJSONObject("data") ?: return
            val tabs = data.optJSONArray("tabs") ?: return
            var initialPage: JSONObject? = home
            for (index in 0 until tabs.length()) {
                if (ApplicationHookConstants.isOffline()) return
                val tabCode = tabs.optJSONObject(index)?.optString("tabCode").orEmpty()
                if (tabCode.isBlank()) continue
                TaskFlowEngine(object : TaskFlowAdapter {
                    override val moduleName = "芝麻信用"
                    override val flowName = "成就馆:$tabCode"
                    override fun query(): JSONObject = initialPage
                        ?.takeIf { it.optJSONObject("data")?.optString("currentTabCode") == tabCode }
                        .also { initialPage = null }
                        ?: JSONObject(AntSesameCreditRpcCall.queryAccomplishmentHome(tabCode))
                    override fun isQuerySuccess(response: JSONObject): Boolean =
                        ResChecker.checkRes(TAG, response) && isQueryComplete(response)
                    override fun isQueryComplete(response: JSONObject): Boolean {
                        val page = response.optJSONObject("data") ?: return false
                        return page.optString("currentTabCode") == tabCode &&
                            (page.optJSONArray("themeCategories") != null ||
                                page.optJSONArray("flatSeries") != null ||
                                (page.has("totalSeriesCount") && !page.isNull("totalSeriesCount") &&
                                    page.optInt("totalSeriesCount", -1) == 0))
                    }
                    override fun onQueryFailed(response: JSONObject) {
                        Log.error(TAG, "成就馆页签查询失败或结构不完整 tab=$tabCode raw=$response")
                    }
                    override fun extractItems(response: JSONObject): List<TaskFlowItem> {
                        val page = response.optJSONObject("data") ?: return emptyList()
                        val seriesLists = mutableListOf<JSONArray>()
                        page.optJSONArray("flatSeries")?.let { seriesLists.add(it) }
                        val categories = page.optJSONArray("themeCategories")
                        if (categories != null) {
                            for (categoryIndex in 0 until categories.length()) {
                                categories.optJSONObject(categoryIndex)?.optJSONArray("seriesList")
                                    ?.let { seriesLists.add(it) }
                            }
                        }
                        val items = mutableListOf<TaskFlowItem>()
                        for (series in seriesLists) {
                            for (seriesIndex in 0 until series.length()) {
                                val medal = series.optJSONObject(seriesIndex) ?: continue
                                val seriesCode = medal.optString("medalSeriesCode")
                                if (seriesCode.isBlank()) continue
                                var target = medal
                                // UPGRADEABLE 也用于已领取但下一级尚未达标的成就。
                                if (medal.optLong("claimedAt") > 0 && medal.optLong("nextThreshold") > 0 &&
                                    medal.optLong("currentValue") >= medal.optLong("nextThreshold")
                                ) {
                                    val detail = JSONObject(AntSesameCreditRpcCall.queryAccomplishmentDetail(seriesCode))
                                    val levels = detail.optJSONObject("data")?.optJSONArray("levels")
                                    if (!ResChecker.checkRes(TAG, detail) || levels == null) {
                                        Log.error(TAG, "成就等级查询失败 series=$seriesCode raw=$detail")
                                        items.add(TaskFlowItem(id = seriesCode, title = seriesCode, status = "UNKNOWN"))
                                        continue
                                    }
                                    target = (0 until levels.length()).mapNotNull { levels.optJSONObject(it) }
                                        .filter { it.optLong("claimedAt") == 0L && it.optLong("threshold") > 0 &&
                                            it.optLong("currentValue") >= it.optLong("threshold") }
                                        .maxByOrNull { it.optInt("levelNo") } ?: medal
                                }
                                val level = target.optInt("levelNo")
                                val claimed = target.optLong("claimedAt") > 0
                                val ready = !claimed && (target.optString("displayState") == "UNCLAIMED" ||
                                    (target.optLong("threshold") > 0 && target.optLong("currentValue") >= target.optLong("threshold")))
                                items.add(TaskFlowItem(
                                    id = "$seriesCode:$level", title = target.optString("medalName", seriesCode),
                                    type = seriesCode, raw = target,
                                    status = if (level <= 0) "UNKNOWN" else if (ready) "UNCLAIMED" else "VIEW",
                                    progress = "$level:${target.optLong("claimedAt")}",
                                ))
                            }
                        }
                        return items
                    }
                    override fun mapPhase(item: TaskFlowItem): TaskFlowPhase = when (item.status) {
                        "UNCLAIMED" -> TaskFlowPhase.REWARD_READY
                        "VIEW" -> TaskFlowPhase.TERMINAL
                        else -> TaskFlowPhase.UNKNOWN
                    }
                    override fun actionKey(item: TaskFlowItem, action: TaskFlowAction): String = "${action.logName}:${item.id}"
                    override fun receive(item: TaskFlowItem): TaskFlowActionResult {
                        val response = JSONObject(AntSesameCreditRpcCall.claimAccomplishment(item.type))
                        if (!ResChecker.checkRes(TAG, response)) {
                            val code = response.optString("resultCode", response.optString("errorCode"))
                            return TaskFlowActionResult.failure(
                                failureType = classifySesameTaskFailure(code, response), code = code,
                                message = buildSesameRpcMessage(response, response.toString()),
                                rpc = "claimAccomplishmentV2", raw = response.toString(),
                                stopCurrentRound = isSesameTaskFlowInterrupted(response), continueCurrentRoundOnFailure = true,
                            )
                        }
                        return TaskFlowActionResult.defer(
                            deferredReason = DeferredReason.STATE_CONFIRMATION,
                            message = "成就领取已受理，回查目标等级及领取时间", refreshAfterAction = true,
                        )
                    }
                    override fun logInfo(message: String) = Log.sesame(message)
                    override fun logError(message: String) = Log.error(TAG, message)
                }).run()
            }
        } catch (t: Throwable) {
            if (t is java.util.concurrent.CancellationException || t is InterruptedException) throw t
            Log.printStackTrace(TAG, "成就馆处理失败", t)
        }
    }

    internal fun handleCreditPassages() {
        if (ApplicationHookConstants.isOffline()) return
        try {
            TaskFlowEngine(object : TaskFlowAdapter {
                override val moduleName = "芝麻信用"
                override val flowName = "信用特权激活"
                override fun query(): JSONObject = JSONObject(AntSesameCreditRpcCall.queryPassageScenes())
                override fun isQuerySuccess(response: JSONObject): Boolean = ResChecker.checkRes(TAG, response)
                override fun isQueryComplete(response: JSONObject): Boolean =
                    response.optJSONObject("data")?.optJSONArray("scenes") != null
                override fun extractItems(response: JSONObject): List<TaskFlowItem> {
                    val scenes = response.optJSONObject("data")?.optJSONArray("scenes") ?: return emptyList()
                    return (0 until scenes.length()).mapNotNull { index ->
                        val scene = scenes.optJSONObject(index) ?: return@mapNotNull null
                        TaskFlowItem(
                            id = scene.optString("sceneCode"), title = scene.optString("sceneName", scene.optString("sceneCode")),
                            status = scene.optString("activationStatus"), raw = scene,
                        )
                    }
                }
                override fun mapPhase(item: TaskFlowItem): TaskFlowPhase = when {
                    item.id.isBlank() -> TaskFlowPhase.UNKNOWN
                    item.status == "ACTIVATED" -> TaskFlowPhase.TERMINAL
                    item.status == "INACTIVATED" && item.raw?.optString("qualificationStatus") == "ELIGIBLE" &&
                        item.raw.optString("verificationStatus") == "NOT_REQUIRED" -> TaskFlowPhase.READY_TO_COMPLETE
                    else -> TaskFlowPhase.BUSINESS_ACTION
                }
                override fun shouldSkip(item: TaskFlowItem): Boolean = mapPhase(item) == TaskFlowPhase.BUSINESS_ACTION
                override fun isUnresolvedWhenSkipped(item: TaskFlowItem): Boolean = true
                override fun complete(item: TaskFlowItem): TaskFlowActionResult {
                    val panel = JSONObject(AntSesameCreditRpcCall.queryPassageActivationPanel(item.id))
                    if (!ResChecker.checkRes(TAG, panel)) {
                        val code = panel.optString("resultCode", panel.optString("errorCode"))
                        return TaskFlowActionResult.failure(
                            failureType = classifySesameTaskFailure(code, panel), code = code,
                            message = buildSesameRpcMessage(panel, panel.toString()),
                            rpc = "queryPassageActivationPanel", raw = panel.toString(),
                            stopCurrentRound = isSesameTaskFlowInterrupted(panel), continueCurrentRoundOnFailure = true,
                        )
                    }
                    val scene = panel.optJSONObject("data")
                    if (scene?.optString("activationStatus") == "ACTIVATED") {
                        return TaskFlowActionResult.defer(
                            deferredReason = DeferredReason.STATE_CONFIRMATION, message = "特权已激活，回查场景列表", refreshAfterAction = true,
                        )
                    }
                    if (scene == null || scene.optString("sceneCode") != item.id ||
                        scene.optString("qualificationStatus") != "ELIGIBLE" || scene.optString("verificationStatus") != "NOT_REQUIRED"
                    ) {
                        return TaskFlowActionResult.defer(
                            deferredReason = DeferredReason.PREREQUISITE_PENDING, message = "特权资格或验证条件待满足", raw = panel.toString(),
                        )
                    }
                    val response = JSONObject(AntSesameCreditRpcCall.activatePassage(item.id))
                    if (!ResChecker.checkRes(TAG, response)) {
                        val code = response.optString("resultCode", response.optString("errorCode"))
                        return TaskFlowActionResult.failure(
                            failureType = classifySesameTaskFailure(code, response), code = code,
                            message = buildSesameRpcMessage(response, response.toString()), rpc = "activatePassage", raw = response.toString(),
                            stopCurrentRound = isSesameTaskFlowInterrupted(response), continueCurrentRoundOnFailure = true,
                        )
                    }
                    return TaskFlowActionResult.defer(
                        deferredReason = DeferredReason.STATE_CONFIRMATION, message = "特权激活已受理，回查激活状态", refreshAfterAction = true,
                    )
                }
                override fun logInfo(message: String) = Log.sesame(message)
                override fun logError(message: String) = Log.error(TAG, message)
            }).run()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "信用特权处理失败", t)
        }
    }

    internal fun handleNewTaskCenterTasks() {
        try {
            if (ApplicationHookConstants.isOffline()) {
                Log.sesame("成长锦囊新任务中心因离线模式跳过")
                return
            }

            val signFlag = "${StatusFlags.FLAG_SESAME_ZML_CHECKIN_DONE}::newTaskCenter"
            if (!hasFlagToday(signFlag)) {
                val signStatus = runCatching {
                    JSONObject(AntSesameCreditRpcCall.Zmxy.newTaskCenterSignStatusQuery())
                }.onFailure {
                    if (it is java.util.concurrent.CancellationException || it is InterruptedException) throw it
                    Log.printStackTrace(TAG, "handleNewTaskCenterTasks.signStatusQuery err:", it)
                }.getOrNull()
                if (signStatus != null && ResChecker.checkRes(TAG, signStatus)) {
                    if (handleNewTaskCenterSign(signStatus)) setFlagToday(signFlag)
                } else if (signStatus != null) {
                    Log.error(TAG, "成长锦囊新任务中心签到状态查询失败: ${newTaskCenterErrorDesc(signStatus)}")
                }
            }

            val taskList =
                runCatching {
                    JSONObject(AntSesameCreditRpcCall.Zmxy.newTaskCenterQueryTaskList())
                }.onFailure {
                    if (it is java.util.concurrent.CancellationException || it is InterruptedException) throw it
                    Log.printStackTrace(TAG, "handleNewTaskCenterTasks.queryTaskList err:", it)
                }.getOrNull()
            if (taskList != null && ResChecker.checkRes(TAG, taskList)) {
                inspectNewTaskCenterTaskList(taskList)
            } else if (taskList != null) {
                Log.error(TAG, "成长锦囊新任务中心任务列表查询失败: ${newTaskCenterErrorDesc(taskList)}")
            }
        } catch (t: Throwable) {
            if (t is java.util.concurrent.CancellationException || t is InterruptedException) throw t
            Log.printStackTrace(TAG, "handleNewTaskCenterTasks err:", t)
        }
    }

    private fun handleNewTaskCenterSign(signStatus: JSONObject): Boolean {
        val signInfo = signStatus.optJSONObject("signInfo") ?: return false
        val action = signInfo.optString("buttonActionEnum")
        val signed =
            signInfo.optBoolean("todaySignedFlag", false) ||
                signInfo.optString("signStatus").equals("SIGNED", ignoreCase = true)
        if (signed || !action.equals("DO_SIGN", ignoreCase = true)) {
            Log.sesame("成长锦囊新任务中心签到状态: ${signInfo.optString("buttonText").ifBlank { action.ifBlank { "已处理" } }}")
            return signed
        }

        Log.sesame("成长锦囊新任务中心[待签到]#doSign抓包包含动态bizNo/token，当前无稳定参数来源，暂不自动签到")
        return false
    }

    private fun inspectNewTaskCenterTaskList(taskListResponse: JSONObject) {
        val taskGroupList = taskListResponse.optJSONArray("taskGroupList") ?: return
        for (groupIndex in 0 until taskGroupList.length()) {
            val group = taskGroupList.optJSONObject(groupIndex) ?: continue
            val taskList = group.optJSONArray("taskList") ?: continue
            val taskSummaries = mutableListOf<String>()
            for (taskIndex in 0 until taskList.length()) {
                val task = taskList.optJSONObject(taskIndex) ?: continue
                val taskCode = task.optString("taskCode")
                val taskName =
                    task
                        .optString("taskName")
                        .ifBlank { task.optString("taskDesc").ifBlank { taskCode } }
                val taskType = task.optString("taskType")
                val taskScene = task.optString("taskScene")
                val taskAction = task.optString("taskAction")
                val taskSummary =
                    "$taskName(taskCode=$taskCode,type=$taskType," +
                        "action=$taskAction,scene=$taskScene)"
                taskSummaries.add(taskSummary)
            }
            if (taskSummaries.isNotEmpty()) {
                Log.sesame(
                    "成长锦囊新任务中心[暂未接入自动执行流程，保留后续抓包复核]#" +
                        "${taskSummaries.size}项: ${taskSummaries.joinToString("；")}",
                )
            }
        }
    }

    private fun newTaskCenterErrorDesc(response: JSONObject): String =
        response
            .optString("resultDesc")
            .ifBlank { response.optString("resultView") }
            .ifBlank { response.optString("errorMsg") }
            .ifBlank { response.optString("memo") }
            .ifBlank { response.toString() }

    /**
     * 芝麻信用任务
     */
    internal suspend fun doAllAvailableSesameTask(): SesameTaskRunSummary =
        CoroutineUtils.run {
            val adapter = SesameCreditTaskFlowAdapter()
            try {
                val result = TaskFlowEngine(adapter, roundSleepMs = 850L).run()
                val finishedAllRounds = result.completed && adapter.canMarkTodayDone()
                Log.sesame("芝麻信用💳[任务总计]#轮次:${result.rounds}, 报名:${adapter.joinedActionCount}个, 完成动作已提交:${adapter.completedActionCount}个, 跳过:${adapter.skippedTaskCount}个")

                if (adapter.interrupted || result.stopped || ApplicationHookConstants.isOffline()) {
                    return@run SesameTaskRunSummary(
                        completedCount = adapter.completedActionCount,
                        skippedCount = adapter.skippedTaskCount,
                        interrupted = true,
                    )
                }

                if (finishedAllRounds) {
                    setFlagToday(StatusFlags.FLAG_SESAME_DO_ALL_AVAILABLE_TASK)
                    Log.sesame(
                        if (adapter.completedActionCount > 0) {
                            "芝麻信用💳[当前可执行任务已处理完成，今日跳过]"
                        } else {
                            "芝麻信用💳[无新增可执行任务，今日跳过]"
                        },
                    )
                } else {
                    Log.sesame("芝麻信用💳[任务流未确认终态]#保留后续重试机会")
                }
                return@run SesameTaskRunSummary(
                    finishedAllRounds = finishedAllRounds,
                    completedCount = adapter.completedActionCount,
                    skippedCount = adapter.skippedTaskCount,
                )
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "doAllAvailableSesameTask err", t)
                return@run SesameTaskRunSummary(
                    completedCount = adapter.completedActionCount,
                    skippedCount = adapter.skippedTaskCount,
                    interrupted = true,
                )
            }
        }

    private inner class SesameCreditTaskFlowAdapter : TaskFlowAdapter {
        override val moduleName: String = sesameCreditTaskBlacklistModule
        override val flowName: String = "芝麻信用任务"
        override val continueCurrentRoundOnRetryableFailure: Boolean = true

        override fun isSameTask(previous: TaskFlowItem, current: TaskFlowItem): Boolean {
            if (!super<TaskFlowAdapter>.isSameTask(previous, current)) return false
            val recordId = previous.raw?.optString("recordId").orEmpty()
            return recordId.isBlank() || current.raw?.optString("recordId") == recordId
        }

        override fun isPendingActionResolved(
            item: TaskFlowItem, action: TaskFlowAction, response: JSONObject, items: List<TaskFlowItem>,
        ): Boolean {
            if (item.type == "AD_TASK") return false
            val recordId = item.raw?.optString("recordId").orEmpty()
            if (recordId.isBlank()) return false
            val matching = items.filter {
                it.id == item.id && it.type == item.type && it.raw?.optString("recordId") == recordId
            }
            if (matching.isNotEmpty()) {
                val confirmed = matching.any { it.raw?.optBoolean("finishFlag", false) == true }
                if (confirmed) {
                    UserDataStoreManager.getInstance(UserMap.currentUid)?.remove("sesame_user_growth_pending:$recordId")
                }
                return confirmed
            }
            // 列表不再呈现旧记录时，沿现有最近操作记录接口确认真实完成。
            if (action != TaskFlowAction.COMPLETE) return false
            val confirmation = confirmSesameLifeRecord("new", item.id, recordId)
            if (confirmation.failureType != null) {
                logError("生活记录回查失败 recordId=$recordId code=${confirmation.code} raw=${confirmation.raw}")
            }
            return confirmation.success
        }

        var completedActionCount: Int = 0
            private set
        var joinedActionCount: Int = 0
            private set
        var skippedTaskCount: Int = 0
            private set
        var interrupted: Boolean = false
            private set

        private var lastQuerySucceeded = false
        private var lastActionableCount = 0
        private var lastUnknownCount = 0
        private var joinLimitReached = hasFlagToday(StatusFlags.FLAG_SESAME_JOIN_LIMIT_REACHED)
        private var joinLimitLogged = false
        private val joinedRecordIds = mutableMapOf<String, String>()
        private val processingTemplateRefreshKeys = mutableSetOf<String>()
        private val loggedSkipKeys = mutableSetOf<String>()

        override fun query(): JSONObject {
            val response = AntSesameCreditRpcCall.queryAvailableSesameTask()
            var result = JSONObject(response)
            if (result.has("resData")) {
                result = result.getJSONObject("resData")
            }
            result.put("_raw", response)
            return result
        }

        override fun isQuerySuccess(response: JSONObject): Boolean =
            ResChecker.checkRes(TAG, response) && response.optJSONObject("data") != null

        override fun isQueryComplete(response: JSONObject): Boolean {
            val data = response.optJSONObject("data") ?: return false
            return data.optJSONObject("dailyTaskListVO") != null || data.optJSONArray("toCompleteVOS") != null
        }

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            lastQuerySucceeded = true
            val taskObj = response.optJSONObject("data")
            if (taskObj == null) {
                replaceSesamePushModelTaskSnapshots(emptyList())
                refreshSesameCreditSnapshot(emptyList())
                return emptyList()
            }

            replaceSesamePushModelTaskSnapshots(collectSesamePushModelTaskSnapshots(taskObj).values)
            val items = mutableListOf<TaskFlowItem>()
            val dailyTaskListVO = taskObj.optJSONObject("dailyTaskListVO")
            appendSesameCreditTaskItems(
                items,
                dailyTaskListVO?.optJSONArray("waitCompleteTaskVOS"),
                "daily.waitCompleteTaskVOS",
            )
            appendSesameCreditTaskItems(
                items,
                dailyTaskListVO?.optJSONArray("waitJoinTaskVOS"),
                "daily.waitJoinTaskVOS",
            )
            appendSesameCreditTaskItems(items, taskObj.optJSONArray("toCompleteVOS"), "toCompleteVOS")
            appendSesameCreditTaskItems(items, taskObj.optJSONArray("completeVOS"), "completeVOS")
            appendSesameCreditTaskItems(
                items,
                dailyTaskListVO?.optJSONArray("todayCompleteTaskVOS"),
                "daily.todayCompleteTaskVOS",
            )
            refreshSesameCreditSnapshot(items)
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            val phase = when (item.status) {
                "COMPLETED",
                "DONE",
                "HAS_RECEIVED",
                "RECEIVED",
                -> TaskFlowPhase.TERMINAL

                "WAIT_JOIN" -> TaskFlowPhase.SIGNUP_REQUIRED

                "WAIT_COMPLETE" -> TaskFlowPhase.READY_TO_COMPLETE

                else -> TaskFlowPhase.UNKNOWN
            }
            val raw = item.raw
            if ((phase == TaskFlowPhase.SIGNUP_REQUIRED || phase == TaskFlowPhase.READY_TO_COMPLETE) &&
                item.type != "AD_TASK" && raw != null &&
                raw.optString("bizType").equals("LIFE_RECORD", ignoreCase = true)
            ) {
                val decision = GameCenterPlayRpcCall.resolveTaskAction(raw, directFinishSupported = true)
                if (decision.action == GameCenterPlayRpcCall.TaskAction.DEFERRED &&
                    decision.missingFields.isEmpty() &&
                    !isSesameP2eFloatingBallTask(raw) && sesameFloatingBallContract(raw) == null
                ) {
                    return TaskFlowPhase.BUSINESS_ACTION
                }
            }
            return phase
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            val raw = item.raw ?: return false
            if (mapPhase(item) == TaskFlowPhase.TERMINAL) {
                return false
            }
            if (shouldSkipShareAssistSesameTask(raw)) {
                logSkipOnce(item, "跳过助力型任务")
                return true
            }
            if (item.id.isBlank()) {
                logSkipOnce(item, "跳过缺少稳定任务标识的任务")
                return true
            }
            if (joinLimitReached && item.status == "WAIT_JOIN") {
                if (!joinLimitLogged) {
                    Log.sesame("芝麻信用💳[领取任务已达当日上限] 今日不再领取新任务")
                    joinLimitLogged = true
                }
                logSkipOnce(item, "跳过今日领取上限任务")
                return true
            }
            return false
        }

        override fun isBlacklisted(item: TaskFlowItem): Boolean {
            val blacklisted =
                item.blacklistKeys.any { key ->
                    TaskBlacklist.isTaskInBlacklist(
                        moduleName = moduleName,
                        taskInfo = key,
                    )
                }
            if (blacklisted && mapPhase(item) != TaskFlowPhase.REWARD_READY) {
                logSkipOnce(item, "任务在自动跳过列表(黑名单)中，跳过")
            }
            return blacklisted
        }

        override fun signup(item: TaskFlowItem): TaskFlowActionResult {
            val raw = item.raw ?: return missingSesameCreditRawResult(item, "join")
            val taskTemplateId = raw.optString("templateId")
            if (taskTemplateId.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.NON_RETRYABLE_INVALID,
                    code = "TEMPLATE_ID_EMPTY",
                    message = "templateId为空",
                    rpc = "AntSesameCreditRpcCall.joinSesameTask",
                    detail = sesameCreditActionDetail(item, "join"),
                )
            }

            val (joinRes, responseObj) = joinSesameTask(taskTemplateId, "zml")
            val errorCode = responseObj.optString("resultCode", responseObj.optString("errorCode", ""))
            val resultView =
                responseObj.optString("resultView").ifEmpty {
                    responseObj.optString("errorMessage", joinRes)
                }
            val joinSuccess = AntSesameCreditRpcCall.isRpcSuccess(joinRes)
            if ("PROMISE_TODAY_FINISH_TIMES_LIMIT" == errorCode) {
                joinLimitReached = true
                setFlagToday(StatusFlags.FLAG_SESAME_JOIN_LIMIT_REACHED)
                Log.sesame("芝麻信用💳[领取任务已达当日上限] 今日不再领取新任务")
                joinLimitLogged = true
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                    code = errorCode,
                    message = resultView,
                    rpc = "AntSesameCreditRpcCall.joinSesameTask",
                    raw = joinRes,
                    detail = sesameCreditActionDetail(item, "join"),
                    continueCurrentRoundOnFailure = true,
                )
            }
            if (!joinSuccess && isSesameProcessingTemplate(errorCode)) {
                if (!processingTemplateRefreshKeys.add(taskTemplateId)) {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        code = errorCode,
                        message = "已有进行中生活记录但刷新后未返回recordId: $resultView",
                        rpc = "AntSesameCreditRpcCall.joinSesameTask",
                        raw = joinRes,
                        detail = sesameCreditActionDetail(item, "join"),
                    )
                }
                Log.sesame("芝麻信用💳[已有进行中生活记录，刷新任务列表后继续]#${item.title}")
                return TaskFlowActionResult(
                    success = true,
                    code = errorCode,
                    message = resultView,
                    rpc = "AntSesameCreditRpcCall.joinSesameTask",
                    raw = joinRes,
                    detail = sesameCreditActionDetail(item, "join") + " processingTemplateRefresh=true",
                    refreshAfterAction = true,
                )
            }
            if (!joinSuccess) {
                RpcOfflineRisk.enterOfflineIfNeeded(TAG, responseObj)
                val failureType = classifySesameTaskFailure(errorCode, responseObj)
                return TaskFlowActionResult.failure(
                    failureType = failureType,
                    code = errorCode,
                    message = resultView,
                    rpc = "AntSesameCreditRpcCall.joinSesameTask",
                    raw = joinRes,
                    detail = sesameCreditActionDetail(item, "join"),
                    stopCurrentRound = isSesameTaskFlowInterrupted(responseObj),
                    continueCurrentRoundOnFailure = failureType == TaskRpcFailureType.BUSINESS_LIMIT,
                )
            }
            val recordId = responseObj.optJSONObject("data")?.optString("recordId").orEmpty()
            if (recordId.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "RECORD_ID_EMPTY",
                    message = "领取成功但未返回recordId",
                    rpc = "AntSesameCreditRpcCall.joinSesameTask",
                    raw = joinRes,
                    detail = sesameCreditActionDetail(item, "join"),
                )
            }
            joinedRecordIds[taskTemplateId] = recordId
            return TaskFlowActionResult.success()
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val raw = item.raw ?: return missingSesameCreditRawResult(item, "finish")
            val task = JSONObject(raw.toString())
            if (item.type == "AD_TASK") {
                return handleSesameAdTaskResult(task, item.title, "芝麻信用💳", moduleName)
            }
            return completeSesameLifeRecord(
                task = task,
                taskTitle = item.title,
                spec = SesameLifeRecordCompletionSpec(
                    moduleName = moduleName,
                    logPrefix = "芝麻信用💳",
                    sceneCode = "zml",
                    version = "new",
                    joinedRecordIds = joinedRecordIds,
                ),
                actionDetail = sesameCreditActionDetail(item, "finish"),
            )
        }

        override fun afterSuccess(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
        ) {
            if (isSesameProcessingTemplateRefresh(result)) {
                return
            }
            if (action == TaskFlowAction.SIGNUP) {
                joinedActionCount++
                Log.sesame("芝麻信用💳[领取任务成功]#${item.title}")
            } else {
                completedActionCount++
            }
        }

        override fun afterDeferred(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
        ) {
            if (action == TaskFlowAction.COMPLETE && result.deferredReason == DeferredReason.STATE_CONFIRMATION) {
                completedActionCount++
            }
        }

        override fun actionKey(
            item: TaskFlowItem,
            action: TaskFlowAction,
        ): String {
            if (action == TaskFlowAction.COMPLETE && item.type != "AD_TASK") {
                val recordId = item.raw?.optString("recordId").orEmpty()
                if (recordId.isNotBlank()) {
                    return "${action.logName}:recordId:$recordId"
                }
            }
            val progressKey = item.current?.toString() ?: item.progress.ifBlank { "NO_PROGRESS" }
            val typeKey = item.actionType.ifBlank { item.type.ifBlank { "NO_TYPE" } }
            return "${action.logName}:${item.id.ifBlank { item.title }}:$progressKey:$typeKey"
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision,
        ) {
            if (result.stopCurrentRound) {
                interrupted = true
            }
        }

        override fun onQueryFailed(response: JSONObject) {
            interrupted = isSesameTaskFlowInterrupted(response)
            Log.error(
                "$TAG.doAllAvailableSesameTask.queryAvailableSesameTask",
                "$flowName[任务查询失败] raw=${response.optString("_raw", response.toString())}",
            )
        }

        override fun onUnknownPhase(
            item: TaskFlowItem,
            phase: TaskFlowPhase,
        ) {
            lastUnknownCount++
            Log.error(
                TAG,
                "芝麻信用💳[未知任务状态] module=$moduleName taskId=${item.id} taskName=${item.title} " +
                    "status=${item.status} actionType=${item.actionType} raw=${item.raw}",
            )
        }

        override fun logInfo(message: String) {
            Log.sesame(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        fun canMarkTodayDone(): Boolean = lastQuerySucceeded && lastUnknownCount == 0 && lastActionableCount == 0

        private fun appendSesameCreditTaskItems(
            target: MutableList<TaskFlowItem>,
            taskList: JSONArray?,
            sourceName: String,
        ) {
            if (taskList == null) return
            for (i in 0..<taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                target.add(buildSesameCreditTaskItem(task, sourceName))
            }
        }

        private fun buildSesameCreditTaskItem(
            task: JSONObject,
            sourceName: String,
        ): TaskFlowItem {
            val taskTitle = task.optString("title", "未知任务").ifBlank { "未知任务" }
            val bizType = task.optString("bizType", "")
            val templateId = task.optString("templateId")
            val recordId = task.optString("recordId").ifBlank { joinedRecordIds[templateId].orEmpty() }
            val completedNum = task.optInt("completedNum", 0)
            val needCompleteNum = task.optInt("needCompleteNum", 1).takeIf { it > 0 } ?: 1
            val terminal =
                task.optBoolean("finishFlag", false) ||
                    completedNum >= needCompleteNum
            val status =
                when {
                    terminal -> "COMPLETED"
                    bizType == "AD_TASK" -> "WAIT_COMPLETE"
                    recordId.isBlank() -> "WAIT_JOIN"
                    else -> "WAIT_COMPLETE"
                }
            val logExtMap = task.optJSONObject("logExtMap")
            val taskId =
                if (bizType == "AD_TASK") {
                    logExtMap
                        ?.optString("bizId")
                        .orEmpty()
                        .ifBlank { task.optString("adTaskBizId") }
                        .trim()
                } else {
                    templateId.trim()
                }
            val raw =
                JSONObject(task.toString())
                    .put("recordId", recordId)
                    .put("_sourceList", sourceName)
                    .put("_taskFlowId", taskId)
            return TaskFlowItem(
                id = taskId,
                title = taskTitle,
                status = status,
                type = bizType,
                sceneCode = task.optString("sceneCode"),
                actionType = task.optString("actionText").ifBlank { bizType },
                blacklistKeys = listOf(taskId, taskTitle).filter { it.isNotBlank() },
                raw = raw,
                progress = "$completedNum/$needCompleteNum",
                current = completedNum,
                limit = needCompleteNum,
            )
        }

        private fun refreshSesameCreditSnapshot(items: List<TaskFlowItem>) {
            lastUnknownCount = 0
            lastActionableCount = 0
            for (item in items) {
                val phase = mapPhase(item)
                if (phase == TaskFlowPhase.UNKNOWN) {
                    lastUnknownCount++
                    continue
                }
                if (phase != TaskFlowPhase.TERMINAL && phase != TaskFlowPhase.BUSINESS_ACTION && !shouldSkip(item) &&
                    !(phase != TaskFlowPhase.REWARD_READY && isBlacklisted(item))
                ) {
                    lastActionableCount++
                }
            }
        }

        private fun logSkipOnce(
            item: TaskFlowItem,
            reason: String,
        ) {
            val key = "$reason|${item.id}|${item.title}"
            if (loggedSkipKeys.add(key)) {
                skippedTaskCount++
                Log.sesame("芝麻信用💳[$reason]#${item.title}")
            }
        }

        private fun missingSesameCreditRawResult(
            item: TaskFlowItem,
            action: String,
        ): TaskFlowActionResult =
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = "RAW_EMPTY",
                message = "任务原始数据为空",
                rpc = "AntSesameCredit.$action",
                detail = sesameCreditActionDetail(item, action),
            )

        private fun sesameCreditActionDetail(
            item: TaskFlowItem,
            action: String,
        ): String {
            val raw = item.raw
            return "taskId=${item.id} taskName=${item.title} action=$action " +
                "templateId=${raw?.optString("templateId").orEmpty()} " +
                "recordId=${raw?.optString("recordId").orEmpty()} " +
                "bizType=${raw?.optString("bizType").orEmpty()} progress=${item.progress} " +
                "sourceList=${raw?.optString("_sourceList").orEmpty()} " +
                "finishFlag=${raw?.opt("finishFlag")} completedNum=${raw?.opt("completedNum")}"
        }
    }

    private fun executeSesameCheckInScene(
        sceneCode: String,
        logPrefix: String,
        queryCheckIn: () -> String,
    ): SesameCheckInExecutionResult {
        val sceneSpec = buildSesameCheckInSceneSpec(sceneCode)
        var lastQueryRawResponse = ""

        fun queryCandidates(
            phase: String,
            remainingCandidateCount: Int,
            failedCandidate: SesameCheckInCandidate?,
        ): Pair<JSONObject, List<SesameCheckInCandidate>>? {
            val rawResponse =
                try {
                    queryCheckIn()
                } catch (t: Throwable) {
                    Log.printStackTrace("$TAG.executeSesameCheckInScene.$sceneCode.$phase", t)
                    return null
                }
            lastQueryRawResponse = rawResponse
            val response = parseJSONObjectOrNull(rawResponse)
            if (response == null) {
                val detail = failedCandidate?.let { describeSesameCheckInCandidate(it) }.orEmpty()
                Log.error(
                    "$TAG.executeSesameCheckInScene",
                    "$logPrefix[$phase 解析失败] ${if (detail.isEmpty()) "" else "$detail "}remaining=$remainingCandidateCount raw=$rawResponse",
                )
                return null
            }
            if (!ResChecker.checkRes(TAG, response)) {
                val detail = failedCandidate?.let { describeSesameCheckInCandidate(it) }.orEmpty()
                Log.error(
                    "$TAG.executeSesameCheckInScene",
                    "$logPrefix[$phase 响应失败] ${if (detail.isEmpty()) "" else "$detail "}remaining=$remainingCandidateCount raw=$rawResponse",
                )
                return null
            }
            return response to extractSesameCheckInCandidates(response)
        }

        val initialState =
            queryCandidates("查询签到", 0, null)
                ?: return SesameCheckInExecutionResult(
                    status = SesameCheckInExecutionStatus.FAILED,
                    initialCandidateCount = 0,
                    completedCandidateCount = 0,
                    remainingCandidateCount = 0,
                    failureRawResponse = lastQueryRawResponse.ifBlank { "QUERY_FAILED" },
                )

        var response = initialState.first
        var visibleCandidates = initialState.second
        val initialCandidateCount = visibleCandidates.size
        var completedCandidateCount = 0
        val attemptedDates = linkedSetOf<String>()

        if (initialCandidateCount == 0) {
            return SesameCheckInExecutionResult(
                status = SesameCheckInExecutionStatus.COMPLETE,
                initialCandidateCount = 0,
                completedCandidateCount = 0,
                remainingCandidateCount = 0,
            )
        }

        while (visibleCandidates.isNotEmpty()) {
            val directCandidates =
                extractDirectCompletableSesameCheckInCandidates(sceneCode, visibleCandidates)
            if (directCandidates.isEmpty()) {
                val candidate = visibleCandidates.first()
                Log.sesame(
                    "$logPrefix[存在待完成项但无已验证自动闭环] " +
                        "${describeSesameCheckInCandidate(candidate)} remaining=${visibleCandidates.size} raw=$response",
                )
                return SesameCheckInExecutionResult(
                    status = SesameCheckInExecutionStatus.NO_MORE_DIRECT_ACTION,
                    initialCandidateCount = initialCandidateCount,
                    completedCandidateCount = completedCandidateCount,
                    remainingCandidateCount = visibleCandidates.size,
                    failedCandidate = candidate,
                )
            }

            val candidate = directCandidates.first()
            if (!attemptedDates.add(candidate.checkInDate)) {
                val rawResponse = response.toString()
                Log.error(
                    "$TAG.executeSesameCheckInScene",
                    "$logPrefix[刷新后仍停留在同一待完成项] ${describeSesameCheckInCandidate(
                        candidate,
                    )} remaining=${visibleCandidates.size} raw=$rawResponse",
                )
                return SesameCheckInExecutionResult(
                    status = SesameCheckInExecutionStatus.FAILED,
                    initialCandidateCount = initialCandidateCount,
                    completedCandidateCount = completedCandidateCount,
                    remainingCandidateCount = visibleCandidates.size,
                    failureRawResponse = rawResponse,
                    failedCandidate = candidate,
                )
            }

            val completeResponse = sceneSpec.completeTask(candidate.checkInDate)
            val completeJson = parseJSONObjectOrNull(completeResponse)
            if (completeJson == null) {
                Log.error(
                    "$TAG.executeSesameCheckInScene",
                    "$logPrefix[签到解析失败] ${describeSesameCheckInCandidate(
                        candidate,
                    )} remaining=${visibleCandidates.size} raw=$completeResponse",
                )
                return SesameCheckInExecutionResult(
                    status = SesameCheckInExecutionStatus.FAILED,
                    initialCandidateCount = initialCandidateCount,
                    completedCandidateCount = completedCandidateCount,
                    remainingCandidateCount = visibleCandidates.size,
                    failureRawResponse = completeResponse,
                    failedCandidate = candidate,
                )
            }

            if (!ResChecker.checkRes(TAG, completeJson)) {
                Log.error(
                    "$TAG.executeSesameCheckInScene",
                    "$logPrefix[签到失败] ${describeSesameCheckInCandidate(
                        candidate,
                    )} remaining=${visibleCandidates.size} raw=$completeResponse",
                )
                return SesameCheckInExecutionResult(
                    status = SesameCheckInExecutionStatus.FAILED,
                    initialCandidateCount = initialCandidateCount,
                    completedCandidateCount = completedCandidateCount,
                    remainingCandidateCount = visibleCandidates.size,
                    failureRawResponse = completeResponse,
                    failedCandidate = candidate,
                )
            }

            val rewardAmount = extractSesameCheckInRewardAmount(completeJson)
            Log.sesame("$logPrefix[签到成功] ${describeSesameCheckInCandidate(candidate)} #获得${rewardAmount}粒")
            completedCandidateCount++

            val knownRemainingCount = max(visibleCandidates.size - 1, 0)
            val refreshedState =
                queryCandidates("刷新签到", knownRemainingCount, candidate)
                    ?: return SesameCheckInExecutionResult(
                        status = SesameCheckInExecutionStatus.FAILED,
                        initialCandidateCount = initialCandidateCount,
                        completedCandidateCount = completedCandidateCount,
                        remainingCandidateCount = knownRemainingCount,
                        failureRawResponse = lastQueryRawResponse.ifBlank { "REFRESH_FAILED" },
                        failedCandidate = candidate,
                    )

            response = refreshedState.first
            visibleCandidates = refreshedState.second
        }

        return SesameCheckInExecutionResult(
            status = SesameCheckInExecutionStatus.COMPLETE,
            initialCandidateCount = initialCandidateCount,
            completedCandidateCount = completedCandidateCount,
            remainingCandidateCount = 0,
        )
    }

    private fun buildSesameCheckInSceneSpec(sceneCode: String): SesameCheckInSceneSpec =
        when (sceneCode) {
            "alchemy" -> {
                SesameCheckInSceneSpec { checkInDate ->
                    // Latest successful alchemy capture still carries this field even when it is empty.
                    AntSesameCreditRpcCall.alchemyCheckInCompleteTask(checkInDate)
                }
            }

            else -> {
                SesameCheckInSceneSpec { checkInDate ->
                    AntSesameCreditRpcCall.zmCheckInCompleteTask(checkInDate, sceneCode)
                }
            }
        }

    private fun extractSesameCheckInCandidates(response: JSONObject): List<SesameCheckInCandidate> {
        val data = response.optJSONObject("data") ?: return emptyList()
        val candidates = linkedMapOf<String, SesameCheckInCandidate>()
        val taskArray = data.optJSONArray("checkInTaskVOS")
        if (taskArray != null) {
            for (i in 0 until taskArray.length()) {
                val task = taskArray.optJSONObject(i) ?: continue
                val candidate = buildSesameCheckInCandidate(task) ?: continue
                candidates[candidate.checkInDate] = candidate
            }
        }
        if (candidates.isEmpty()) {
            val currentDay = data.optJSONObject("currentDateCheckInTaskVO")
            val candidate = buildSesameCheckInCandidate(currentDay)
            if (candidate != null) {
                candidates[candidate.checkInDate] = candidate
            }
        }
        return candidates.values
            .sortedWith(compareByDescending<SesameCheckInCandidate> { it.broken }.thenBy { it.checkInDate })
    }

    private fun extractDirectCompletableSesameCheckInCandidates(
        sceneCode: String,
        visibleCandidates: List<SesameCheckInCandidate>,
    ): List<SesameCheckInCandidate> =
        visibleCandidates.filter { candidate ->
            when (sceneCode) {
                "zml" -> candidate.currentDay && !candidate.broken
                else -> true
            }
        }

    private fun buildSesameCheckInCandidate(task: JSONObject?): SesameCheckInCandidate? {
        if (task == null) {
            return null
        }
        val status = task.optString("status").trim()
        val checkInDate = task.optString("checkInDate").trim()
        if (status != "CAN_COMPLETE" || checkInDate.isEmpty()) {
            return null
        }
        return SesameCheckInCandidate(
            checkInDate = checkInDate,
            broken = task.optBoolean("broken", false),
            currentDay = task.optBoolean("currentDay", false),
            doubleReward = task.optBoolean("doubleReward", false),
            status = status,
        )
    }

    private fun extractSesameCheckInRewardAmount(response: JSONObject): Int {
        val prize = response.optJSONObject("data") ?: return 0
        val prizeObject = prize.optJSONObject("prize")
        return prize.optInt("zmlNum", prizeObject?.optInt("num", 0) ?: 0)
    }

    private fun describeSesameCheckInCandidate(candidate: SesameCheckInCandidate): String =
        "date=${candidate.checkInDate} broken=${candidate.broken} " +
            "currentDay=${candidate.currentDay} doubleReward=${candidate.doubleReward} status=${candidate.status}"

    /**
     * 芝麻粒信用福利签到与芝麻炼金签到共用同一签到闭环。
     */
    internal fun doSesameZmlCheckIn() {
        var flagState = Status.TodayFlagState.RETRY_LATER
        try {
            if (ApplicationHookConstants.isOffline()) {
                return
            }
            val result =
                executeSesameCheckInScene(
                    sceneCode = "zml",
                    logPrefix = "芝麻信用💳[芝麻粒福利签到]",
                ) {
                    AntSesameCreditRpcCall.zmlCheckInQueryTaskLists()
                }
            when (result.status) {
                SesameCheckInExecutionStatus.COMPLETE -> {
                    flagState =
                        if (result.initialCandidateCount == 0) {
                            Status.TodayFlagState.NO_MORE_ACTION_TODAY
                        } else {
                            Status.TodayFlagState.DONE
                        }
                }

                SesameCheckInExecutionStatus.NO_MORE_DIRECT_ACTION -> {
                    flagState = Status.TodayFlagState.NO_MORE_ACTION_TODAY
                }

                SesameCheckInExecutionStatus.FAILED -> {
                    return
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.doSesameZmlCheckIn", t)
        } finally {
            setFlagToday(StatusFlags.FLAG_SESAME_ZML_CHECKIN_DONE, flagState)
        }
    }

    internal fun doSesameAlchemyNextDayAward() =
        CoroutineUtils.run {
            try {
                val nextDayAward = querySesameAlchemyNextDayAward("次日奖励入口查询") ?: return@run
                val awardAvailable = nextDayAward.opt("awardAvailable") as? Boolean
                val awardId = nextDayAward.optString("awardId")
                val pointValue = nextDayAward.optInt("pointValue", 0)
                if (awardAvailable == null) {
                    Log.sesame("芝麻炼金⚗️[次日奖励可领取状态未知] awardId=$awardId，保留后续重试机会")
                    return@run
                }
                if (!awardAvailable) {
                    Log.sesame("芝麻炼金⚗️[次日奖励暂无可领] 预计奖励=${pointValue}粒${if (awardId.isNotEmpty()) " awardId=$awardId" else ""}")
                    setFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_NEXT_DAY_AWARD)
                    return@run
                }
                val awardRes = AntSesameCreditRpcCall.Zmxy.Alchemy.claimAward(awardId)
                val jo = JSONObject(awardRes)

                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.error("芝麻炼金⚗️[次日奖励领取失败]：$awardRes")
                    return@run
                }

                val refreshedAward = querySesameAlchemyNextDayAward("次日奖励领取后回查")
                if (refreshedAward == null) {
                    Log.sesame("芝麻炼金⚗️[次日奖励领取待确认] awardId=$awardId，保留后续重试机会")
                    return@run
                }
                val refreshedAwardId = refreshedAward.optString("awardId")
                val refreshedAwardAvailable = refreshedAward.opt("awardAvailable") as? Boolean
                if (refreshedAwardId != awardId || refreshedAwardAvailable != false) {
                    val availability = refreshedAwardAvailable?.toString() ?: "未知"
                    Log.sesame(
                        "芝麻炼金⚗️[次日奖励领取待确认] awardId=$awardId " +
                            "recheckAwardId=$refreshedAwardId awardAvailable=$availability，保留后续重试机会",
                    )
                    return@run
                }

                Log.sesame("芝麻炼金⚗️[次日奖励领取已确认] awardId=$awardId")
                setFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_NEXT_DAY_AWARD)
            } catch (t: Throwable) {
                Log.printStackTrace("doSesameAlchemyNextDayAward", t)
            }
        }

    private fun querySesameAlchemyNextDayAward(action: String): JSONObject? {
        val entryRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryEntryList()
        val entryJo = parseSesameAlchemyRpcResponse(entryRes, action) ?: return null
        if (!ResChecker.checkRes(TAG, entryJo)) {
            Log.error("芝麻炼金⚗️[$action]失败：$entryRes")
            return null
        }

        val entryList = entryJo.optJSONObject("data")?.optJSONArray("entryList")
        if (entryList == null) {
            Log.sesame("芝麻炼金⚗️[$action]入口列表结构未知，保留后续重试机会")
            return null
        }
        for (index in 0 until entryList.length()) {
            val entry = entryList.optJSONObject(index) ?: continue
            if (entry.optString("entryCode") != "ALCHEMY_STAGE_REWARD") {
                continue
            }
            val nextDayAward = entry.optJSONObject("nextDayAwardDTO")
            if (nextDayAward == null) {
                Log.sesame("芝麻炼金⚗️[$action]阶段奖励入口缺少次日奖励对象，保留后续重试机会")
            }
            return nextDayAward
        }

        Log.sesame("芝麻炼金⚗️[$action]未返回阶段奖励入口，保留后续重试机会")
        return null
    }

    private fun extractSesameFeedbackArray(root: JSONObject): JSONArray? =
        root.optJSONArray("creditFeedbackVOS")
            ?: root.optJSONObject("data")?.optJSONArray("creditFeedbackVOS")
            ?: root.optJSONObject("resData")?.optJSONArray("creditFeedbackVOS")

    private fun buildUnclaimedSesameFeedbackItems(root: JSONObject): List<SesameFeedbackItem> {
        val feedbackArray = extractSesameFeedbackArray(root) ?: return emptyList()
        val result = mutableListOf<SesameFeedbackItem>()
        for (i in 0 until feedbackArray.length()) {
            val item = feedbackArray.optJSONObject(i) ?: continue
            if ("UNCLAIMED" != item.optString("status")) {
                continue
            }
            result.add(
                SesameFeedbackItem(
                    cateId = item.optString("cateId"),
                    title = item.optString("title", "未知奖励"),
                    creditFeedbackId = item.optString("creditFeedbackId"),
                    potentialSize = item.optString("potentialSize", "0"),
                ),
            )
        }
        return result
    }

    private suspend fun queryUnclaimedSesameFeedbackItems(logPrefix: String): List<SesameFeedbackItem>? {
        val resp = AntSesameCreditRpcCall.queryCreditFeedback()
        val jo = JSONObject(resp)
        if (!ResChecker.checkRes(TAG, jo)) {
            Log.error(
                "$TAG.queryUnclaimedSesameFeedbackItems",
                "$logPrefix[查询未领取芝麻粒响应失败]#$jo",
            )
            return null
        }
        return buildUnclaimedSesameFeedbackItems(jo)
    }

    private suspend fun queryExplicitUnclaimedSesameFeedbackItems(logPrefix: String): List<SesameFeedbackItem>? {
        val resp = AntSesameCreditRpcCall.queryCreditFeedback()
        val jo = JSONObject(resp)
        if (!ResChecker.checkRes(TAG, jo)) {
            Log.error(
                "$TAG.queryExplicitUnclaimedSesameFeedbackItems",
                "$logPrefix[查询未领取芝麻粒响应失败]#$jo",
            )
            return null
        }
        val feedbackArray = extractSesameFeedbackArray(jo)
        if (feedbackArray == null) {
            Log.error(
                "$TAG.queryExplicitUnclaimedSesameFeedbackItems",
                "$logPrefix[回查未返回creditFeedbackVOS] 保留后续重试机会 raw=$jo",
            )
            return null
        }
        for (index in 0 until feedbackArray.length()) {
            if (feedbackArray.optJSONObject(index) == null) {
                Log.error(
                    "$TAG.queryExplicitUnclaimedSesameFeedbackItems",
                    "$logPrefix[回查包含无法识别的反馈项] 保留后续重试机会 raw=$jo",
                )
                return null
            }
        }
        return buildUnclaimedSesameFeedbackItems(jo)
    }

    private suspend fun collectSesameFeedbackItems(
        items: List<SesameFeedbackItem>,
        preferOneClick: Boolean,
        logPrefix: String,
    ): Int {
        if (items.isEmpty()) {
            return 0
        }
        val antFarm = Model.getModel(AntFarm::class.java)
        if (antFarm != null && antFarm.pendingZhimaPigeonRewardFeedbackId()?.isBlank() == true) {
            val pigeonReward = items.firstOrNull { it.cateId == zhimaPigeonFeedbackCategory }
            if (pigeonReward != null && !antFarm.bindZhimaPigeonRewardFeedbackId(pigeonReward.creditFeedbackId)) {
                Log.error(TAG, "$logPrefix[无法绑定大表鸽待收反馈，保留该反馈并继续收取其他反馈]")
                return collectSesameFeedbackItems(
                    items.filter { it.cateId != zhimaPigeonFeedbackCategory },
                    preferOneClick = false,
                    logPrefix = logPrefix,
                )
            }
        }
        var collectedCount = 0

        if (preferOneClick) {
            val collectAllResp = AntSesameCreditRpcCall.collectAllCreditFeedback()
            val collectAllJo = JSONObject(collectAllResp)
            if (AntSesameCreditRpcCall.isRpcSuccess(collectAllResp)) {
                items.forEach { item ->
                    Log.sesame("$logPrefix[" + item.title + "]#" + item.potentialSize + "粒(一键收取)")
                    collectedCount++
                }
                return collectedCount
            } else {
                val errorCode =
                    collectAllJo.optString(
                        "errorCode",
                        collectAllJo.optString("resultCode", ""),
                    )
                val msg = buildSesameRpcMessage(collectAllJo, collectAllResp)
                if (isTransientSesameTaskError(errorCode)) {
                    Log.error(
                        TAG,
                        "$logPrefix[一键收取失败，回查后收取剩余反馈] classification=RETRYABLE_RPC decision=RETRY_LATER " +
                            "module=$logPrefix taskId=collectAllCreditFeedback taskName=一键收取芝麻粒 " +
                            "action=collectAllCreditFeedback rpc=AntSesameCreditRpcCall.collectAllCreditFeedback " +
                            "code=${errorCode.ifEmpty { "UNKNOWN" }} msg=$msg raw=$collectAllJo",
                    )
                } else {
                    Log.error(
                        "$TAG.collectSesameFeedbackItems",
                        "$logPrefix[一键收取响应失败，回查后收取剩余反馈] classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY " +
                            "module=$logPrefix taskId=collectAllCreditFeedback taskName=一键收取芝麻粒 " +
                            "action=collectAllCreditFeedback rpc=AntSesameCreditRpcCall.collectAllCreditFeedback " +
                            "code=${errorCode.ifEmpty { "UNKNOWN" }} msg=$msg raw=$collectAllJo",
                    )
                }
            }
            val remaining = queryExplicitUnclaimedSesameFeedbackItems("$logPrefix[一键领取后回查]") ?: return 0
            return collectSesameFeedbackItems(
                remaining.filter { remainingItem -> items.any { it.creditFeedbackId == remainingItem.creditFeedbackId } },
                preferOneClick = false,
                logPrefix = logPrefix,
            )
        }

        for (item in items) {
            if (item.creditFeedbackId.isEmpty()) {
                continue
            }
            val collectResp = AntSesameCreditRpcCall.collectCreditFeedback(item.creditFeedbackId)
            val collectJo = JSONObject(collectResp)
            if (!ResChecker.checkRes(TAG, collectJo)) {
                val itemErrorCode = collectJo.optString("errorCode", collectJo.optString("resultCode", ""))
                val itemMsg = buildSesameRpcMessage(collectJo, collectResp)
                val itemClassification =
                    if (isTransientSesameTaskError(itemErrorCode)) {
                        "RETRYABLE_RPC"
                    } else {
                        "UNKNOWN_NEEDS_REVIEW"
                    }
                val itemDecision = if (itemClassification == "RETRYABLE_RPC") "RETRY_LATER" else "LOG_ONLY"
                Log.error(
                    "$TAG.collectSesameFeedbackItems",
                    "$logPrefix[${item.title}] classification=$itemClassification decision=$itemDecision " +
                        "module=$logPrefix taskId=${item.creditFeedbackId} taskName=${item.title} " +
                        "action=collectCreditFeedback rpc=AntSesameCreditRpcCall.collectCreditFeedback " +
                        "code=${itemErrorCode.ifEmpty { "UNKNOWN" }} msg=$itemMsg raw=$collectJo",
                )
                continue
            }
            Log.sesame("$logPrefix[" + item.title + "]#" + item.potentialSize + "粒")
            collectedCount++
        }
        return collectedCount
    }

    /**
     * 领取已由庄园确认遣返的大表鸽奖励。
     * 该专项只处理账号私有待收记录绑定的反馈，不扩大为一键收取。
     */
    internal suspend fun collectPendingZhimaPigeonReward(antFarm: AntFarm): Boolean {
        if (!antFarm.hasPendingZhimaPigeonRewardReceipt() || ApplicationHookConstants.isOffline()) {
            return false
        }

        try {
            if (!antFarm.confirmZhimaPigeonDeparture()) return false
            val pendingFeedbackId = antFarm.pendingZhimaPigeonRewardFeedbackId() ?: return false
            val unclaimedItems = queryExplicitUnclaimedSesameFeedbackItems("芝麻大表鸽🤖") ?: return false
            val target = if (pendingFeedbackId.isBlank()) {
                unclaimedItems.firstOrNull { it.cateId == zhimaPigeonFeedbackCategory }
                    ?: run {
                        scheduleSesameConfirmation(JSONObject().put("pigeon", true))
                        Log.sesame("芝麻大表鸽🤖[奖励反馈尚未生成，保留待收状态并等待持久回查]")
                        return false
                    }
            } else {
                unclaimedItems.firstOrNull { it.creditFeedbackId == pendingFeedbackId }
                    ?: run {
                        val confirmed = antFarm.confirmZhimaPigeonRewardReceipt()
                        if (!confirmed) {
                            Log.error(TAG, "芝麻大表鸽🤖[反馈已不存在但完成态写入失败，保留后续复核]")
                        }
                        return confirmed
                    }
            }

            val creditFeedbackId = target.creditFeedbackId.trim()
            if (creditFeedbackId.isBlank()) {
                Log.error(TAG, "芝麻大表鸽🤖[待收反馈缺少creditFeedbackId，保留后续重试]")
                return false
            }
            if (pendingFeedbackId.isBlank() && !antFarm.bindZhimaPigeonRewardFeedbackId(creditFeedbackId)) {
                Log.error(TAG, "芝麻大表鸽🤖[无法绑定待收反馈，保留后续重试]")
                return false
            }

            val collectResponse = AntSesameCreditRpcCall.collectCreditFeedback(creditFeedbackId)
            val collectJo = JSONObject(collectResponse)
            val collectSucceeded = AntSesameCreditRpcCall.isRpcSuccess(collectResponse)
            if (!collectSucceeded) {
                val errorCode = collectJo.optString("errorCode", collectJo.optString("resultCode", ""))
                val message = buildSesameRpcMessage(collectJo, collectResponse)
                val classification = if (isTransientSesameTaskError(errorCode)) {
                    "RETRYABLE_RPC"
                } else {
                    "UNKNOWN_NEEDS_REVIEW"
                }
                val decision = if (classification == "RETRYABLE_RPC") "RETRY_LATER" else "LOG_ONLY"
                Log.error(
                    TAG,
                    "芝麻大表鸽🤖[领取反馈失败] classification=$classification decision=$decision " +
                        "taskId=$creditFeedbackId action=collectCreditFeedback " +
                        "rpc=AntSesameCreditRpcCall.collectCreditFeedback " +
                        "code=${errorCode.ifEmpty { "UNKNOWN" }} msg=$message raw=$collectJo",
                )
            }

            val remainingItems = queryExplicitUnclaimedSesameFeedbackItems("芝麻大表鸽🤖[复核]") ?: return false
            if (remainingItems.none { it.creditFeedbackId == creditFeedbackId }) {
                if (antFarm.confirmZhimaPigeonRewardReceipt()) {
                    Log.sesame("芝麻大表鸽🤖[${target.potentialSize}粒芝麻粒已收取]")
                    return true
                } else {
                    Log.error(TAG, "芝麻大表鸽🤖[反馈已收取但完成态写入失败，保留后续复核]")
                }
            } else {
                Log.error(TAG, "芝麻大表鸽🤖[领取后反馈仍未消失，保留后续自然重试]")
            }
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.collectPendingZhimaPigeonReward", t)
        }
        return false
    }

    /**
     * 芝麻粒收取
     * @param withOneClick 启用一键收取
     */
    internal suspend fun collectSesame(withOneClick: Boolean): Unit =
        CoroutineUtils.run {
            if (hasFlagToday(StatusFlags.FLAG_SESAME_COLLECT_DONE)) {
                Log.sesame("⏭️ 今天已处理过芝麻粒领取，跳过执行")
                return@run
            }
            var flagState = Status.TodayFlagState.RETRY_LATER
            if (ApplicationHookConstants.isOffline()) {
                return@run
            }
            try {
                var items = queryExplicitUnclaimedSesameFeedbackItems("芝麻信用💳") ?: return@run
                val attemptedIds = mutableSetOf<String>()
                var preferOneClick = withOneClick
                var treasureBoxConfirmed = true
                while (!ApplicationHookConstants.isOffline() && !Thread.currentThread().isInterrupted) {
                    val actionable = items.filter {
                        it.creditFeedbackId.isNotBlank() && it.creditFeedbackId !in attemptedIds
                    }
                    if (items.isNotEmpty() && actionable.isEmpty()) {
                        Log.sesame("芝麻信用💳[仍有${items.size}项未收取，暂无新进度] 保留待确认，不重复提交")
                        return@run
                    }
                    if (actionable.isNotEmpty()) {
                        attemptedIds.addAll(actionable.map { it.creditFeedbackId })
                        collectSesameFeedbackItems(actionable, preferOneClick, "芝麻信用💳")
                        preferOneClick = false
                    }
                    if (ApplicationHookConstants.isOffline()) return@run
                    // 宝箱可生成新反馈；未确认的开启请求本次不重复提交，但仍收取新出现的反馈。
                    if (treasureBoxConfirmed) {
                        val box = handlePointTreasureBox("芝麻信用💳")
                        treasureBoxConfirmed = box != null
                        if (box?.opt("hasBox") == false) {
                            flagState = Status.TodayFlagState.NO_MORE_ACTION_TODAY
                        } else {
                            // HANGING 是冷却中的下一次奖励，不能据此结束全天领取。
                            flagState = Status.TodayFlagState.RETRY_LATER
                        }
                    }
                    if (ApplicationHookConstants.isOffline()) return@run
                    val refreshedItems = queryExplicitUnclaimedSesameFeedbackItems("芝麻信用💳[复核]")
                    if (refreshedItems == null) {
                        flagState = Status.TodayFlagState.RETRY_LATER
                        return@run
                    }
                    items = refreshedItems
                    if (items.isEmpty()) {
                        if (treasureBoxConfirmed) {
                            if (flagState != Status.TodayFlagState.RETRY_LATER && attemptedIds.isNotEmpty()) {
                                flagState = Status.TodayFlagState.DONE
                            }
                        } else {
                            Log.sesame("芝麻信用💳[积分宝箱状态未确认] 保留后续重试机会")
                        }
                        return@run
                    }
                    flagState = Status.TodayFlagState.RETRY_LATER
                }
            } catch (t: Throwable) {
                flagState = Status.TodayFlagState.RETRY_LATER
                Log.printStackTrace("$TAG.collectSesame", t)
            } finally {
                // 连续产出和后续时段尚未结束时，当前反馈收空不代表全天领取完成。
                val farm = Model.getModel(AntFarm::class.java)
                if (ApplicationHookConstants.isOffline() || Thread.currentThread().isInterrupted ||
                    (sesameTask?.value == true &&
                        (!hasFlagToday(StatusFlags.FLAG_SESAME_DO_ALL_AVAILABLE_TASK) ||
                            !hasFlagToday("${StatusFlags.FLAG_SESAME_DO_ALL_AVAILABLE_TASK}::growthGuideAccept"))) ||
                    (sesameAlchemy?.value == true &&
                        (!hasFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_TASKS_DONE) ||
                            !hasFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_NEXT_DAY_AWARD))) ||
                    farm?.hasPendingZhimaPigeonRewardReceipt() == true || farm?.isZhimaPigeonConfigured() == true
                ) {
                    flagState = Status.TodayFlagState.RETRY_LATER
                }
                if (flagState != Status.TodayFlagState.RETRY_LATER && sesameAlchemy?.value == true) {
                    try {
                        val response = JSONObject(AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryTimeLimitedTask())
                        val data = response.optJSONObject("data")
                        if (!ResChecker.checkRes(TAG, response) || data == null) {
                            Log.error(TAG, "芝麻领取收尾时段回查失败 raw=$response")
                            flagState = Status.TodayFlagState.RETRY_LATER
                        } else if (data.optJSONObject("timeLimitedTaskVO")?.opt("tomorrow") != true) {
                            flagState = Status.TodayFlagState.RETRY_LATER
                        }
                        if (flagState != Status.TodayFlagState.RETRY_LATER) {
                            val home = querySesameAlchemyHomeData("领取收尾炼金额度回查")
                            if (home == null || home.optInt("freeAlchemyNum", -1) != 0 ||
                                (sesameAlchemyConsume?.value == true && !home.optBoolean("capReached", false))
                            ) flagState = Status.TodayFlagState.RETRY_LATER
                        }
                    } catch (t: Throwable) {
                        flagState = Status.TodayFlagState.RETRY_LATER
                        Log.printStackTrace("$TAG.collectSesame.timeLimited", t)
                    }
                }
                setFlagToday(StatusFlags.FLAG_SESAME_COLLECT_DONE, flagState)
            }
        }

    /** 返回已确认的宝箱状态；冷却仍会产奖，不能当作全天领取完成。 */
    private suspend fun handlePointTreasureBox(source: String): JSONObject? {
        if (ApplicationHookConstants.isOffline()) {
            return null
        }
        try {
            val queryJo = JSONObject(AntSesameCreditRpcCall.queryPointTreasureBox())
            if (!ResChecker.checkRes(TAG, queryJo)) {
                RpcOfflineRisk.enterOfflineIfNeeded("$TAG.treasureBox.query", queryJo)
                return null
            }
            val data = queryJo.optJSONObject("data") ?: return null
            if (!data.optBoolean("hasBox", false) || "WAIT_CLAIM" != data.optString("status")) {
                return data
            }
            val openJo = JSONObject(AntSesameCreditRpcCall.openPointTreasureBox())
            if (!ResChecker.checkRes(TAG, openJo)) {
                RpcOfflineRisk.enterOfflineIfNeeded("$TAG.treasureBox.open", openJo)
                Log.error("$TAG.handlePointTreasureBox", "开启积分宝箱失败:$openJo")
                return null
            }
            val reward = openJo.optJSONObject("data")?.optInt("rewardAmount", 0) ?: 0
            Log.sesame("$source[开启积分宝箱]#获得${reward}粒")

            val refreshedJo = JSONObject(AntSesameCreditRpcCall.queryPointTreasureBox())
            if (!ResChecker.checkRes(TAG, refreshedJo)) {
                RpcOfflineRisk.enterOfflineIfNeeded("$TAG.treasureBox.recheck", refreshedJo)
                return null
            }
            val refreshedData = refreshedJo.optJSONObject("data") ?: return null
            if (refreshedData.optBoolean("hasBox", false) &&
                refreshedData.optString("status") == "WAIT_CLAIM"
            ) {
                Log.error("$TAG.handlePointTreasureBox", "开启积分宝箱后仍为WAIT_CLAIM，停止重复提交:$refreshedJo")
                return null
            }
            return refreshedData
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.handlePointTreasureBox", t)
            return null
        }
    }

    /**
     * 芝麻炼金
     */
    internal suspend fun doSesameAlchemy(executionState: TaskFlowExecutionState): Unit =
        CoroutineUtils.run {
            try {
                Log.sesame("开始执行芝麻炼金⚗️")
                sesameAlchemyBottleCompletionPending.clear()

                // ================= Step 1: 自动签到 & 时段奖励 =================
                executeSesameCheckInScene(
                    sceneCode = "alchemy",
                    logPrefix = "芝麻炼金⚗️[每日签到]",
                ) {
                    AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryCheckIn("alchemy")
                }

                // 1. 查询时段任务
                val queryRespStr = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryTimeLimitedTask()
                Log.sesame("芝麻炼金⚗️[检查时段奖励]")

                val queryResp = JSONObject(queryRespStr)
                val queryData = queryResp.optJSONObject("data")
                if (!ResChecker.checkRes(TAG, "查询时段任务失败:", queryResp) ||
                    !ResChecker.checkRes(
                        TAG,
                        queryResp,
                    ) || queryData == null
                ) {
                    Log.error(
                        TAG,
                        "芝麻炼金⚗️[检查时段奖励错误] alchemyQueryTimeLimitedTask raw=$queryResp",
                    )
                } else {
                    val timeLimitedTaskVO = queryData.optJSONObject("timeLimitedTaskVO")
                    if (timeLimitedTaskVO == null) {
                        Log.sesame("芝麻炼金⚗️[当前没有时段奖励任务]")
                    } else {
                        // 2. 获取任务信息
                        val taskName = timeLimitedTaskVO.optString("longTitle", "未知任务")
                        val templateId = timeLimitedTaskVO.getString("templateId") // 动态获取
                        val state = timeLimitedTaskVO.optInt("state", 0) // 1: 可领取, 2: 未到时间
                        val tomorrow = timeLimitedTaskVO.optBoolean("tomorrow", false)
                        val rewardAmount = timeLimitedTaskVO.optInt("rewardAmount", 0)

                        Log.sesame("芝麻炼金⚗️[任务检查] 任务=$taskName 状态=$state 奖励=$rewardAmount 明天=$tomorrow")

                        // 3. 如果是明天任务，跳过时段奖励，但继续处理任务列表
                        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        val startHour = timeLimitedTaskVO.optInt("startHour", -1)
                        val endHour = timeLimitedTaskVO.optInt("endHour", -1)
                        val inWindow = startHour < 0 || endHour <= startHour || hour in startHour until endHour
                        if (tomorrow) {
                            Log.sesame("芝麻炼金⚗️[任务跳过] 任务=$taskName 是明天的奖励")
                        } else if (state == 1 && inWindow && templateId.isNotBlank()) {
                            Log.sesame("芝麻炼金⚗️[开始领取任务奖励] 任务=$taskName")

                            val collectRespStr = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyCompleteTimeLimitedTask(templateId)
                            val collectResp = JSONObject(collectRespStr)

                            if (!ResChecker.checkRes(
                                    TAG,
                                    collectResp,
                                ) || collectResp.optJSONObject("data") == null
                            ) {
                                Log.error(TAG, "领取任务奖励失败 raw=$collectResp")
                            } else {
                                val data = collectResp.getJSONObject("data")
                                val zmlNum = data.optInt("zmlNum", 0)
                                val toast = data.optString("toast", "")
                                val verifyRaw = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryTimeLimitedTask()
                                val verify = JSONObject(verifyRaw)
                                val nextTask = verify.optJSONObject("data")?.optJSONObject("timeLimitedTaskVO")
                                if (ResChecker.checkRes(TAG, verify) && verify.optJSONObject("data") != null &&
                                    (nextTask == null || nextTask.optString("templateId") != templateId || nextTask.optInt("state") != 1)
                                ) {
                                    Log.sesame("芝麻炼金⚗️[时段奖励已确认] 获得芝麻粒=$zmlNum 提示=$toast")
                                } else {
                                    Log.error(TAG, "芝麻炼金时段奖励回查未确认 raw=$verifyRaw")
                                }
                            }
                        } else { // 其他状态
                            Log.sesame("芝麻炼金⚗️[当前不可领取] 任务=$taskName")
                        }
                    }
                }

                // ================= Step 3: 自动做任务 =================
                processAlchemyTaskListsUntilStable(executionState)

                // 待收反馈在信用、炼金及关联任务汇合后统一领取，再进入消费阶段。
            } catch (t: Throwable) {
                Log.printStackTrace("$TAG.doSesameAlchemy", t)
            }
        }

    internal suspend fun finishSesameAlchemy(executionState: TaskFlowExecutionState) {
        if (ApplicationHookConstants.isOffline()) return
        var pending = queryExplicitUnclaimedSesameFeedbackItems("芝麻炼金[消费前反馈回查]") ?: return
        if (pending.isNotEmpty()) {
            collectSesame(collectSesameWithOneClick?.value == true || sesameAlchemy?.value == true)
            pending = queryExplicitUnclaimedSesameFeedbackItems("芝麻炼金[消费前领取确认]") ?: return
        }
        // 大表鸽反馈由独立待收链确认，不阻塞使用首页已确认的余额和次数。
        if (pending.any { it.cateId != zhimaPigeonFeedbackCategory }) {
            Log.sesame("芝麻炼金[仍有待收反馈，等待领取确认后继续]")
            return
        }
        val withdrawState = SesameAlchemyWithdrawState()
        runSesameAlchemyCycles(withdrawState, allowPaidAlchemy = sesameAlchemyConsume?.value == true)
        if (!withdrawState.confirmationPending && restoreSesameAlchemyStaminaIfNeeded(executionState)) {
            runSesameAlchemyCycles(withdrawState, allowPaidAlchemy = sesameAlchemyConsume?.value == true)
        }
        // 消费和精力瓶任务均可能推进任务状态，在最后一次完整回查后才确认日任务结束。
        if (!withdrawState.confirmationPending && !ApplicationHookConstants.isOffline() &&
            UserDataStoreManager.getCurrentInstance()?.get("sesame_alchemy_consumption_pending", String::class.java) == null &&
            processAlchemyTaskListsUntilStable(executionState)
        ) {
            setFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_TASKS_DONE)
        }
    }

    private suspend fun runSesameAlchemyCycles(
        withdrawState: SesameAlchemyWithdrawState,
        allowPaidAlchemy: Boolean,
    ) {
        val store = UserDataStoreManager.getCurrentInstance() ?: return
        val epoch = AccountSessionCoordinator.currentSessionEpoch()
        val pendingKey = "sesame_alchemy_consumption_pending"
        var data = querySesameAlchemyHomeData("炼金首页查询") ?: return
        if (AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current) return
        val previous = store.get(pendingKey, String::class.java)?.let(::JSONObject)
        if (previous != null) {
            val confirmed = listOf("freeAlchemyNum", "alchemyNum", "finishAlchemyCount", "goldBalance")
                .any { previous.has(it) && data.has(it) && previous.optLong(it) != data.optLong(it) }
            if (!confirmed) {
                withdrawState.confirmationPending = true
                Log.sesame("芝麻炼金[上次消费尚未确认，保留回查，不重复炼金]")
                return
            }
            store.remove(pendingKey)
        }
        if (data.optString("roundStatus") == "WAIT_WITHDRAW") {
            withdrawState.confirmationPending = true
            if (!claimSesameAlchemyFullLevelRedPacket(withdrawState)) {
                Log.sesame("芝麻炼金⚗️[首页要求提现但处理未确认] 停止本轮炼金，保留后续自然调度")
                return
            }

            data = querySesameAlchemyHomeData("满级红包领取后炼金首页回查") ?: return
            if (data.optString("roundStatus") == "WAIT_WITHDRAW") {
                Log.sesame("芝麻炼金⚗️[满级红包领取后仍等待提现] 停止本轮炼金，保留后续自然调度")
                return
            }
            withdrawState.confirmationPending = false
        }
        val maxAlchemyAttempts = (data.optInt("alchemyNum", 0) + data.optInt("freeAlchemyNum", 0))
            .coerceIn(1, 100)
        var alchemyAttempts = 0
        while (!ApplicationHookConstants.isOffline() && !Thread.currentThread().isInterrupted) {
            val freeAlchemyNum = data.optInt("freeAlchemyNum", 0)
            val cost = data.optInt("alchemyCostZml", -1)
            val zmlBalance = data.optLong("zmlBalance", -1L)
            val capReached = data.optBoolean("capReached", false)
            if (data.optBoolean("levelFull", false) || data.optBoolean("staminaExhausted", false) ||
                data.optString("staminaStatus").equals("EXHAUSTED", ignoreCase = true) ||
                data.optInt("staminaCurrent", -1) == 0 ||
                (freeAlchemyNum <= 0 && (!allowPaidAlchemy || capReached ||
                    data.optInt("alchemyNum", 0) <= 0 || cost <= 0 || zmlBalance < cost))
            ) break
            if (alchemyAttempts++ >= maxAlchemyAttempts) {
                Log.sesame("芝麻炼金[达到本轮次数上限]#$maxAlchemyAttempts")
                break
            }
            if (AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current) return
            store.put(pendingKey, data.toString(), requirePersistence = true)
            val alchemyRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyExecute()
            val alchemyJo = parseSesameAlchemyRpcResponse(alchemyRes, "炼金") ?: run {
                withdrawState.confirmationPending = true
                return
            }
            if (AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current) return
            if (isSesameAlchemyCapReached(alchemyJo) && freeAlchemyNum <= 0) {
                store.remove(pendingKey)
                break
            }
            val alData = alchemyJo.optJSONObject("data")
            if (!ResChecker.checkRes(TAG, alchemyJo) || alData?.opt("success") == false) {
                val rejected = !alchemyJo.has("rpcMethod") &&
                    (alchemyJo.opt("success") == false || alData?.opt("success") == false)
                if (rejected) store.remove(pendingKey)
                else withdrawState.confirmationPending = true
                Log.error(TAG, "芝麻炼金失败 failErrorCode=${alData?.optString("failErrorCode").orEmpty()} raw=$alchemyRes")
                return
            }
            if (alData == null) {
                withdrawState.confirmationPending = true
                return
            }
            val refreshed = querySesameAlchemyHomeData("炼金后首页回查") ?: run {
                withdrawState.confirmationPending = true
                return
            }
            if (AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current) return
            val progressed = listOf("freeAlchemyNum", "alchemyNum", "finishAlchemyCount", "goldBalance")
                .any { data.has(it) && refreshed.has(it) && data.optLong(it) != refreshed.optLong(it) }
            if (!progressed) {
                withdrawState.confirmationPending = true
                val stateFields = listOf(
                    "freeAlchemyNum", "alchemyNum", "finishAlchemyCount", "goldBalance", "zmlBalance",
                    "staminaCurrent", "staminaStatus", "staminaExhausted",
                )
                Log.error(
                    TAG,
                    "芝麻炼金请求已接受，但首页未确认推进 raw=$alchemyRes " +
                        "before=${stateFields.associateWith { data.opt(it) }} " +
                        "after=${stateFields.associateWith { refreshed.opt(it) }}",
                )
                return
            }
            store.remove(pendingKey)
            Log.sesame("芝麻炼金[炼金已确认]#获得${alData.optInt("goldNum")}金 | 芝麻粒余额${refreshed.optLong("zmlBalance", -1L)}")
            data = refreshed
            if (alData.optBoolean("levelFull", false) || data.optString("roundStatus") == "WAIT_WITHDRAW") break
        }
    }

    private suspend fun querySesameAlchemyHomeData(action: String): JSONObject? {
        val homeRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryHome()
        val homeJo = parseSesameAlchemyRpcResponse(homeRes, action) ?: return null
        if (!ResChecker.checkRes(TAG, homeJo)) {
            Log.error(TAG, "芝麻炼金⚗️[$action]失败 raw=$homeRes")
            return null
        }
        return homeJo.optJSONObject("data") ?: run {
            Log.error(TAG, "芝麻炼金⚗️[$action]缺少data raw=$homeRes")
            null
        }
    }

    private data class SesameAlchemyWithdrawState(
        var submitted: Boolean = false,
        var confirmationPending: Boolean = false,
    )

    private enum class SesameAlchemyBottleUseResult {
        USED,
        NOT_FOUND,
        FAILED,
    }

    private fun parseSesameAlchemyRpcResponse(
        rawResponse: String,
        action: String,
    ): JSONObject? =
        runCatching { JSONObject(rawResponse) }
            .onFailure { error -> Log.error(TAG, "芝麻炼金⚗️[$action]响应解析失败: ${error.message} raw=$rawResponse") }
            .getOrNull()

    private suspend fun claimSesameAlchemyFullLevelRedPacket(state: SesameAlchemyWithdrawState): Boolean {
        if (state.submitted) {
            return false
        }

        val preConsultRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyWithdrawPreConsult()
        val preConsultJo = parseSesameAlchemyRpcResponse(preConsultRes, "满级红包资格查询") ?: return false
        if (!ResChecker.checkRes(TAG, preConsultJo)) {
            Log.error(TAG, "芝麻炼金⚗️[满级红包资格查询失败] raw=$preConsultRes")
            return false
        }

        val preConsultData = preConsultJo.optJSONObject("data")
        if (preConsultData == null) {
            Log.error(TAG, "芝麻炼金⚗️[满级红包资格查询缺少data] raw=$preConsultRes")
            return false
        }
        if (!preConsultData.optBoolean("withdrawable", false)) {
            Log.sesame("芝麻炼金⚗️[满级红包当前不可领取]")
            return false
        }

        // 在提交前锁定本轮，避免成功响应的回查异常触发重复提现。
        state.submitted = true
        val withdrawRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyWithdraw()
        val withdrawJo = parseSesameAlchemyRpcResponse(withdrawRes, "满级红包领取") ?: return false
        if (!ResChecker.checkRes(TAG, withdrawJo)) {
            Log.error(TAG, "芝麻炼金⚗️[满级红包领取失败] raw=$withdrawRes")
            return false
        }

        val amount =
            withdrawJo
                .optJSONObject("data")
                ?.let { data -> data.optString("amount").ifBlank { data.optString("withdrawAmount") } }
                .orEmpty()
        Log.sesame("芝麻炼金⚗️[满级红包领取请求已接受]${amount.ifBlank { "服务端未返回金额" }}，等待资格回查")

        val verifyRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyWithdrawPreConsult()
        val verifyJo = parseSesameAlchemyRpcResponse(verifyRes, "满级红包领取后回查") ?: return false
        val verifyData = verifyJo.optJSONObject("data")
        return when {
            ResChecker.checkRes(TAG, verifyJo) && verifyData != null && !verifyData.optBoolean("withdrawable", false) -> {
                Log.sesame("芝麻炼金⚗️[满级红包领取已确认]")
                true
            }

            isSesameAlchemyWithdrawUnavailable(verifyJo) -> {
                Log.sesame("芝麻炼金⚗️[满级红包领取已确认]#资格已关闭")
                true
            }

            else -> {
                Log.error(TAG, "芝麻炼金⚗️[满级红包领取后回查未确认] raw=$verifyRes")
                false
            }
        }
    }

    private fun isSesameAlchemyWithdrawUnavailable(response: JSONObject): Boolean {
        val code = response.optString("resultCode", response.optString("errorCode", ""))
        return code in setOf("NOT_REACH_MAX_LEVEL", "NOT_WITHDRAWABLE")
    }

    private suspend fun restoreSesameAlchemyStaminaIfNeeded(executionState: TaskFlowExecutionState): Boolean {
        val homeRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryHome()
        val homeJo = parseSesameAlchemyRpcResponse(homeRes, "精力状态查询") ?: return false
        if (!ResChecker.checkRes(TAG, homeJo)) {
            Log.error(TAG, "芝麻炼金⚗️[精力状态查询失败] raw=$homeRes")
            return false
        }
        val homeData = homeJo.optJSONObject("data")
        if (homeData == null) {
            Log.error(TAG, "芝麻炼金⚗️[精力状态查询缺少data] raw=$homeRes")
            return false
        }

        val staminaDepleted =
            homeData.optBoolean("staminaExhausted", false) ||
                homeData.optString("staminaStatus").equals("EXHAUSTED", ignoreCase = true) ||
                homeData.optInt("staminaCurrent", -1) == 0
        if (!staminaDepleted) {
            return false
        }
        if (!homeData.optBoolean("hasBottleQuota", false)) {
            Log.sesame("芝麻炼金⚗️[精力已耗尽且无精力瓶领取配额]")
            return false
        }

        Log.sesame("芝麻炼金⚗️[精力已耗尽，检查精力瓶]")
        when (useAvailableSesameAlchemyBottle()) {
            SesameAlchemyBottleUseResult.USED -> return true
            SesameAlchemyBottleUseResult.FAILED -> return false
            SesameAlchemyBottleUseResult.NOT_FOUND -> Unit
        }

        if (!completeOneSesameAlchemyTaskForBottle(executionState)) {
            return false
        }

        return when (useAvailableSesameAlchemyBottle()) {
            SesameAlchemyBottleUseResult.USED -> true
            SesameAlchemyBottleUseResult.NOT_FOUND -> {
                Log.sesame("芝麻炼金⚗️[任务动作已提交，精力瓶可能异步到账，等待后续自然调度]")
                false
            }

            SesameAlchemyBottleUseResult.FAILED -> false
        }
    }

    private suspend fun useAvailableSesameAlchemyBottle(): SesameAlchemyBottleUseResult {
        val itemsRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryAvailableItems()
        val itemsJo = parseSesameAlchemyRpcResponse(itemsRes, "精力瓶库存查询") ?: return SesameAlchemyBottleUseResult.FAILED
        if (!ResChecker.checkRes(TAG, itemsJo)) {
            Log.error(TAG, "芝麻炼金⚗️[精力瓶库存查询失败] raw=$itemsRes")
            return SesameAlchemyBottleUseResult.FAILED
        }

        val items = itemsJo.optJSONObject("data")?.optJSONArray("items")
        if (items == null) {
            Log.error(TAG, "芝麻炼金⚗️[精力瓶库存查询缺少items] raw=$itemsRes")
            return SesameAlchemyBottleUseResult.FAILED
        }
        var bottleId = ""
        for (index in 0..<items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val expired = item.optLong("expireTime", 0L).let { it > 0L && it < System.currentTimeMillis() }
            if (!expired && item.optString("itemType") == "BOTTLE") {
                bottleId = item.optString("itemId")
                if (bottleId.isNotBlank()) {
                    break
                }
            }
        }
        if (bottleId.isBlank()) {
            Log.sesame("芝麻炼金⚗️[当前无可用精力瓶]")
            return SesameAlchemyBottleUseResult.NOT_FOUND
        }

        val useRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyUseItem(bottleId, "BOTTLE")
        val useJo = parseSesameAlchemyRpcResponse(useRes, "精力瓶使用") ?: return SesameAlchemyBottleUseResult.FAILED
        if (!ResChecker.checkRes(TAG, useJo)) {
            Log.error(TAG, "芝麻炼金⚗️[精力瓶使用失败] itemId=$bottleId raw=$useRes")
            return SesameAlchemyBottleUseResult.FAILED
        }

        val verifyItemsRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryAvailableItems()
        val verifyItemsJo =
            parseSesameAlchemyRpcResponse(verifyItemsRes, "精力瓶使用后库存回查")
                ?: return SesameAlchemyBottleUseResult.FAILED
        if (!ResChecker.checkRes(TAG, verifyItemsJo)) {
            Log.error(TAG, "芝麻炼金⚗️[精力瓶使用后库存回查失败] raw=$verifyItemsRes")
            return SesameAlchemyBottleUseResult.FAILED
        }
        val verifyItems = verifyItemsJo.optJSONObject("data")?.optJSONArray("items")
        if (verifyItems == null) {
            Log.error(TAG, "芝麻炼金⚗️[精力瓶使用后库存回查缺少items] raw=$verifyItemsRes")
            return SesameAlchemyBottleUseResult.FAILED
        }
        val hasConsumedBottle =
            (0..<verifyItems.length()).none { index ->
                verifyItems.optJSONObject(index)?.optString("itemId") == bottleId
            }
        if (!hasConsumedBottle) {
            Log.error(TAG, "芝麻炼金⚗️[精力瓶使用后仍在库存中] itemId=$bottleId")
            return SesameAlchemyBottleUseResult.FAILED
        }

        val homeRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryHome()
        val homeJo =
            parseSesameAlchemyRpcResponse(homeRes, "精力瓶使用后精力回查")
                ?: return SesameAlchemyBottleUseResult.FAILED
        val staminaCurrent = homeJo.optJSONObject("data")?.optInt("staminaCurrent", 0) ?: 0
        if (!ResChecker.checkRes(TAG, homeJo) || staminaCurrent <= 0) {
            Log.error(TAG, "芝麻炼金⚗️[精力瓶使用后精力回查未确认] raw=$homeRes")
            return SesameAlchemyBottleUseResult.FAILED
        }

        Log.sesame("芝麻炼金⚗️[精力瓶使用已确认]#当前精力=$staminaCurrent")
        return SesameAlchemyBottleUseResult.USED
    }

    private suspend fun completeOneSesameAlchemyTaskForBottle(executionState: TaskFlowExecutionState): Boolean {
        if (hasFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_TASKS_DONE)) return false
        val listRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryListV3()
        val listJo = parseSesameAlchemyRpcResponse(listRes, "精力瓶任务列表查询") ?: return false
        if (!ResChecker.checkRes(TAG, listJo)) {
            Log.error(TAG, "芝麻炼金⚗️[精力瓶任务列表查询失败] raw=$listRes")
            return false
        }

        val data = listJo.optJSONObject("data")
        if (data == null) {
            Log.sesame("芝麻炼金⚗️[精力瓶任务列表为空]")
            return false
        }
        val dailyTaskVO = data.optJSONObject("dailyTaskListVO")
        val taskLists =
            listOf(
                data.optJSONArray("toCompleteVOS"),
                dailyTaskVO?.optJSONArray("waitJoinTaskVOS"),
                dailyTaskVO?.optJSONArray("waitCompleteTaskVOS"),
            )
        for (taskList in taskLists) {
            if (taskList == null) {
                continue
            }
            for (index in 0..<taskList.length()) {
                val task = taskList.optJSONObject(index) ?: continue
                if (!isExecutableSesameAlchemyBottleTask(task)) {
                    continue
                }
                val snapshotKey = "bottle:${task.optString("templateId")}:${task.optInt("completedNum")}:${task.optInt("needCompleteNum", 1)}"
                if (executionState.deferredActionKeys.putIfAbsent(snapshotKey, 0L) != null) continue
                if (completeSesameAlchemyTaskForBottle(task)) return true
                if (ApplicationHookConstants.isOffline()) return false
            }
        }

        Log.sesame("芝麻炼金⚗️[本轮没有新的可执行精力瓶任务，保留未完成状态]")
        return false
    }

    private fun isExecutableSesameAlchemyBottleTask(task: JSONObject): Boolean {
        val title = task.optString("title")
        val templateId = task.optString("templateId").trim()
        if (task.optBoolean("finishFlag", false)) {
            return false
        }
        if (templateId.isBlank()) {
            Log.sesame("芝麻炼金精力瓶任务缺少稳定 templateId，保留后续服务端查询")
            return false
        }
        if (TaskBlacklist.isTaskInBlacklist(sesameAlchemyTaskBlacklistModule, templateId)) {
            Log.sesame("芝麻炼金精力瓶任务: 在自动跳过列表中，跳过 ${title.ifBlank { templateId }}")
            return false
        }
        if (shouldSkipShareAssistSesameTask(task)) {
            return false
        }
        if (task.optString("bizType") == "AD_TASK" ||
            templateId == AntFarm.ZHIMA_PIGEON_ALCHEMY_TEMPLATE_ID
        ) {
            return false
        }
        return true
    }

    private suspend fun completeSesameAlchemyTaskForBottle(task: JSONObject): Boolean {
        val title = task.optString("title")
        val templateId = task.optString("templateId").trim()
        if (templateId in sesameAlchemyBottleCompletionPending) {
            Log.sesame("芝麻炼金⚗️[精力瓶任务已提交待确认，跳过本轮重复提交]#$title")
            return false
        }
        val actionTask = JSONObject(task.toString())
        Log.sesame("芝麻炼金⚗️[精力瓶任务准备]#$title")

        if (actionTask.optString("recordId").isBlank()) {
            val (joinRes, joinJo) = joinSesameTask(templateId, "alchemy")
            if (!AntSesameCreditRpcCall.isRpcSuccess(joinRes)) {
                val errorCode = joinJo.optString("errorCode", joinJo.optString("resultCode", ""))
                val resultView = buildSesameRpcMessage(joinJo, joinRes)
                autoBlacklistSesameTaskIfNeeded(
                    moduleName = sesameAlchemyTaskBlacklistModule,
                    taskId = templateId,
                    taskTitle = title,
                    errorCode = errorCode,
                    resultView = resultView,
                    rawResponse = joinRes,
                    action = "join",
                )
                Log.error(TAG, "芝麻炼金⚗️[精力瓶任务领取失败]#$title raw=$joinRes")
                return false
            }
            val recordId = joinJo.optJSONObject("data")?.optString("recordId").orEmpty()
            if (recordId.isBlank()) {
                Log.error(TAG, "芝麻炼金⚗️[精力瓶任务领取未返回recordId，保留后续重试]#$title raw=$joinRes")
                return false
            }
            actionTask.put("recordId", recordId)
        }

        val result =
            completeSesameLifeRecord(
                task = actionTask,
                taskTitle = title,
                spec = SesameLifeRecordCompletionSpec(
                    moduleName = sesameAlchemyTaskBlacklistModule,
                    logPrefix = "芝麻炼金⚗️",
                    sceneCode = "alchemy",
                    version = "alchemy",
                    changeRewardType = "BOTTLE",
                ),
                actionDetail = "module=$sesameAlchemyTaskBlacklistModule taskId=$templateId taskName=$title action=bottleComplete",
            )
        if (result.success || result.failureType == TaskRpcFailureType.TERMINAL_DONE ||
            result.deferredReason == DeferredReason.STATE_CONFIRMATION
        ) {
            sesameAlchemyBottleCompletionPending += templateId
            Log.sesame("芝麻炼金⚗️[精力瓶任务完成动作已提交，等待后续物品回查]#$title")
            return true
        }
        if (result.deferredReason != null) {
            Log.sesame("芝麻炼金⚗️[精力瓶任务待处理]#$title ${result.message}")
            return false
        }
        autoBlacklistSesameTaskIfNeeded(
            moduleName = sesameAlchemyTaskBlacklistModule,
            taskId = templateId,
            taskTitle = title,
            errorCode = result.code,
            resultView = result.message,
            rawResponse = result.raw,
            action = "finish",
        )
        Log.error(TAG, "芝麻炼金⚗️[精力瓶任务完成动作失败]#$title classification=${result.failureType} code=${result.code} message=${result.message} raw=${result.raw}")
        return false
    }

    internal suspend fun processAlchemyTaskListsUntilStable(executionState: TaskFlowExecutionState): Boolean {
        val adapter = SesameAlchemyTaskFlowAdapter(executionState)
        val result = TaskFlowEngine(adapter, roundSleepMs = 850L, executionState = executionState).run()
        if (adapter.interrupted || result.stopped || ApplicationHookConstants.isOffline()) {
            Log.sesame("芝麻炼金⚗️[任务流中断]#轮次=${result.rounds}")
        }
        if (adapter.submittedActionCount > 0) {
            Log.sesame("芝麻炼金⚗️[任务动作已提交]#本次${adapter.submittedActionCount}项，完成状态以列表回查为准")
        }
        return result.completed && !result.stopped && !result.interrupted && !adapter.interrupted &&
            adapter.confirmedTaskCount > 0
    }

    private inner class SesameAlchemyTaskFlowAdapter(private val executionState: TaskFlowExecutionState) : TaskFlowAdapter {
        override val moduleName: String = sesameAlchemyTaskBlacklistModule
        override val flowName: String = "芝麻炼金任务"
        override val continueCurrentRoundOnRetryableFailure: Boolean = true

        override fun isSameTask(previous: TaskFlowItem, current: TaskFlowItem): Boolean {
            if (!super<TaskFlowAdapter>.isSameTask(previous, current)) return false
            val recordId = previous.raw?.optString("recordId").orEmpty()
            return recordId.isBlank() || current.raw?.optString("recordId") == recordId
        }

        override fun isPendingActionResolved(
            item: TaskFlowItem, action: TaskFlowAction, response: JSONObject, items: List<TaskFlowItem>,
        ): Boolean {
            if (item.type == "AD_TASK") return false
            val recordId = item.raw?.optString("recordId").orEmpty()
            if (recordId.isBlank()) return false
            val matching = items.filter { it.id == item.id && it.type == item.type }
            if (matching.any { it.raw?.optString("recordId") == recordId && mapPhase(it) == TaskFlowPhase.TERMINAL }) {
                UserDataStoreManager.getInstance(UserMap.currentUid)?.remove("sesame_user_growth_pending:$recordId")
                return true
            }
            if (matching.any { it.raw?.optString("recordId") == recordId }) return false
            // 列表不再呈现旧记录时，沿现有最近操作记录接口确认真实完成。
            if (action != TaskFlowAction.COMPLETE) return false
            val confirmation = confirmSesameLifeRecord("alchemy", item.id, recordId)
            if (confirmation.failureType != null) {
                logError("生活记录回查失败 recordId=$recordId code=${confirmation.code} raw=${confirmation.raw}")
            }
            return confirmation.success
        }

        override fun isFlowHandledToday(): Boolean =
            Status.hasFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_TASKS_DONE)

        var confirmedTaskCount: Int = 0
            private set

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            confirmedTaskCount = snapshot.completedTasks
        }

        override fun isQueryComplete(response: JSONObject): Boolean {
            val data = response.optJSONObject("data") ?: return false
            return isQuerySuccess(response) &&
                (data.optJSONArray("toCompleteVOS") != null || data.optJSONObject("dailyTaskListVO") != null)
        }

        var submittedActionCount: Int = 0
            private set
        var interrupted: Boolean = false
            private set

        private val joinedRecordIds = mutableMapOf<String, String>()
        private val processingTemplateRefreshKeys = mutableSetOf<String>()
        private val loggedSkipKeys = mutableSetOf<String>()

        override fun query(): JSONObject {
            val raw = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryListV3()
            val response = JSONObject(raw)
            val payload = response.optJSONObject("resData") ?: response
            return JSONObject(payload.toString()).put("_raw", raw)
        }

        override fun isQuerySuccess(response: JSONObject): Boolean =
            ResChecker.checkRes(TAG, response) && response.optJSONObject("data") != null

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val payload = response.optJSONObject("resData") ?: response
            val data = payload.optJSONObject("data")
            if (data == null) {
                return emptyList()
            }
            val items = mutableListOf<TaskFlowItem>()
            val dailyTaskVO = data.optJSONObject("dailyTaskListVO")
            appendAlchemyTaskItems(items, data.optJSONArray("toCompleteVOS"), "toCompleteVOS")
            appendAlchemyTaskItems(items, dailyTaskVO?.optJSONArray("waitJoinTaskVOS"), "daily.waitJoinTaskVOS")
            appendAlchemyTaskItems(items, dailyTaskVO?.optJSONArray("waitCompleteTaskVOS"), "daily.waitCompleteTaskVOS")
            appendAlchemyTaskItems(items, data.optJSONArray("completeVOS"), "completeVOS")
            appendAlchemyTaskItems(items, dailyTaskVO?.optJSONArray("todayCompleteTaskVOS"), "daily.todayCompleteTaskVOS")
            val pigeonTasks = items.filter { it.raw?.optString("templateId") == AntFarm.ZHIMA_PIGEON_ALCHEMY_TEMPLATE_ID }
            if (pigeonTasks.isNotEmpty() && pigeonTasks.all { mapPhase(it) == TaskFlowPhase.TERMINAL } && isQueryComplete(response) &&
                Model.getModel(AntFarm::class.java)?.hasPendingZhimaPigeonRewardReceipt() != true
            ) {
                if (!hasFlagToday(StatusFlags.FLAG_FARM_ZHIMA_PIGEON_HIRE_DONE)) {
                    Log.sesame("芝麻大表鸽🤖[炼金雇佣任务已确认完成；在场产出与奖励仍按实际状态处理]")
                }
                Status.setFlagToday(StatusFlags.FLAG_FARM_ZHIMA_PIGEON_HIRE_DONE)
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            val phase = when (item.status) {
                "COMPLETED", "DONE", "HAS_RECEIVED", "RECEIVED" -> TaskFlowPhase.TERMINAL
                "WAIT_JOIN" -> TaskFlowPhase.SIGNUP_REQUIRED
                "WAIT_COMPLETE" -> TaskFlowPhase.READY_TO_COMPLETE
                else -> TaskFlowPhase.UNKNOWN
            }
            val raw = item.raw
            if ((phase == TaskFlowPhase.SIGNUP_REQUIRED || phase == TaskFlowPhase.READY_TO_COMPLETE) &&
                item.type != "AD_TASK" && raw != null &&
                raw.optString("bizType").equals("LIFE_RECORD", ignoreCase = true) &&
                raw.optString("templateId") != AntFarm.ZHIMA_PIGEON_ALCHEMY_TEMPLATE_ID
            ) {
                val decision = GameCenterPlayRpcCall.resolveTaskAction(raw, directFinishSupported = true)
                if (decision.action == GameCenterPlayRpcCall.TaskAction.DEFERRED &&
                    decision.missingFields.isEmpty() &&
                    !isSesameP2eFloatingBallTask(raw) && sesameFloatingBallContract(raw) == null
                ) {
                    return TaskFlowPhase.BUSINESS_ACTION
                }
            }
            return phase
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            val raw = item.raw ?: return true
            if (mapPhase(item) == TaskFlowPhase.TERMINAL) {
                return false
            }
            val bottleSnapshot = "bottle:${item.id}:${item.current ?: 0}:${item.limit ?: 1}"
            if (bottleSnapshot in executionState.deferredActionKeys) return true
            if (raw.optString("templateId").trim() in sesameAlchemyBottleCompletionPending) {
                logSkipOnce(item, "精力瓶任务已有提交待确认，跳过本轮重复提交")
                return true
            }
            if (shouldSkipShareAssistSesameTask(raw)) {
                logSkipOnce(item, "跳过助力型任务")
                return true
            }
            if (item.id.isBlank()) {
                logSkipOnce(item, "跳过缺少稳定任务标识的任务")
                return true
            }
            if (raw.optString("templateId") == AntFarm.ZHIMA_PIGEON_ALCHEMY_TEMPLATE_ID &&
                Model.getModel(AntFarm::class.java)?.isZhimaPigeonConfigured() != true
            ) {
                logSkipOnce(item, "等待庄园配置芝麻大表鸽")
                return true
            }
            return false
        }

        override fun isUnresolvedWhenSkipped(item: TaskFlowItem): Boolean {
            if (isBlacklisted(item)) return false
            val raw = item.raw ?: return true
            return mapPhase(item) != TaskFlowPhase.TERMINAL &&
                ("bottle:${item.id}:${item.current ?: 0}:${item.limit ?: 1}" in executionState.deferredActionKeys ||
                raw.optString("templateId").trim() in sesameAlchemyBottleCompletionPending ||
                    item.id.isBlank() ||
                    shouldSkipShareAssistSesameTask(raw) ||
                    (raw.optString("templateId") == AntFarm.ZHIMA_PIGEON_ALCHEMY_TEMPLATE_ID &&
                        Model.getModel(AntFarm::class.java)?.isZhimaPigeonConfigured() != true))
        }

        override fun isBlacklisted(item: TaskFlowItem): Boolean {
            val blacklisted =
                item.blacklistKeys.any { key ->
                    TaskBlacklist.isTaskInBlacklist(
                        moduleName = moduleName,
                        taskInfo = key,
                    )
                }
            if (blacklisted && mapPhase(item) != TaskFlowPhase.TERMINAL) {
                logSkipOnce(item, "任务在自动跳过列表(黑名单)中，跳过")
            }
            return blacklisted
        }

        override fun signup(item: TaskFlowItem): TaskFlowActionResult {
            val raw = item.raw ?: return missingAlchemyRawResult(item, "join")
            val templateId = raw.optString("templateId").trim()
            if (templateId.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.NON_RETRYABLE_INVALID,
                    code = "TEMPLATE_ID_EMPTY",
                    message = "templateId为空",
                    rpc = "AntSesameCreditRpcCall.joinSesameTask",
                    detail = alchemyActionDetail(item, "join"),
                )
            }
            val (joinRes, joinJo) = joinSesameTask(templateId, "alchemy")
            val errorCode = joinJo.optString("resultCode", joinJo.optString("errorCode", ""))
            val resultView = buildSesameRpcMessage(joinJo, joinRes)
            if (!AntSesameCreditRpcCall.isRpcSuccess(joinRes) && isSesameProcessingTemplate(errorCode)) {
                if (!processingTemplateRefreshKeys.add(templateId)) {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        code = errorCode,
                        message = "已有进行中生活记录但刷新后未返回recordId: $resultView",
                        rpc = "AntSesameCreditRpcCall.joinSesameTask",
                        raw = joinRes,
                        detail = alchemyActionDetail(item, "join"),
                    )
                }
                Log.sesame("芝麻炼金⚗️[已有进行中生活记录，刷新任务列表后继续]#${item.title}")
                return TaskFlowActionResult.success(
                    refreshAfterAction = true,
                    progressChanged = false,
                )
            }
            if (!AntSesameCreditRpcCall.isRpcSuccess(joinRes)) {
                RpcOfflineRisk.enterOfflineIfNeeded(TAG, joinJo)
                return TaskFlowActionResult.failure(
                    failureType = classifySesameTaskFailure(errorCode, joinJo),
                    code = errorCode,
                    message = resultView,
                    rpc = "AntSesameCreditRpcCall.joinSesameTask",
                    raw = joinRes,
                    detail = alchemyActionDetail(item, "join"),
                    stopCurrentRound = isSesameTaskFlowInterrupted(joinJo),
                    continueCurrentRoundOnFailure = classifySesameTaskFailure(errorCode, joinJo) ==
                        TaskRpcFailureType.BUSINESS_LIMIT,
                )
            }
            val recordId = joinJo.optJSONObject("data")?.optString("recordId").orEmpty()
            if (recordId.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "RECORD_ID_EMPTY",
                    message = "领取成功但未返回recordId",
                    rpc = "AntSesameCreditRpcCall.joinSesameTask",
                    raw = joinRes,
                    detail = alchemyActionDetail(item, "join"),
                )
            }
            joinedRecordIds[templateId] = recordId
            Log.sesame("芝麻炼金⚗️[领取任务成功]#${item.title}")
            return TaskFlowActionResult.success(
                refreshAfterAction = true,
                progressChanged = false,
            )
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val raw = item.raw ?: return missingAlchemyRawResult(item, "finish")
            val task = JSONObject(raw.toString())
            if (item.type == "AD_TASK") {
                return handleSesameAdTaskResult(task, item.title, "芝麻炼金⚗️", moduleName)
            }
            if (task.optString("templateId") == AntFarm.ZHIMA_PIGEON_ALCHEMY_TEMPLATE_ID) {
                return completeAlchemyZhimaPigeonTask(item, task)
            }
            return completeSesameLifeRecord(
                task = task,
                taskTitle = item.title,
                spec = SesameLifeRecordCompletionSpec(
                    moduleName = moduleName,
                    logPrefix = "芝麻炼金⚗️",
                    sceneCode = "alchemy",
                    version = "alchemy",
                    joinedRecordIds = joinedRecordIds,
                ),
                actionDetail = alchemyActionDetail(item, "finish"),
            )
        }

        override fun afterSuccess(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
        ) {
            if (action == TaskFlowAction.COMPLETE) {
                submittedActionCount++
            }
        }

        override fun afterDeferred(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
        ) {
            if (action == TaskFlowAction.COMPLETE && result.deferredReason == DeferredReason.STATE_CONFIRMATION) {
                submittedActionCount++
            }
        }

        override fun afterFailure(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
            decision: TaskFlowDecision,
        ) {
            if (result.stopCurrentRound ||
                (decision == TaskFlowDecision.RETRY_LATER && !result.continueCurrentRoundOnFailure)
            ) {
                interrupted = true
            }
        }

        override fun onQueryFailed(response: JSONObject) {
            interrupted = isSesameTaskFlowInterrupted(response)
            Log.error(TAG, "$flowName[任务查询失败] raw=${response.optString("_raw", response.toString())}")
        }

        override fun onUnknownPhase(
            item: TaskFlowItem,
            phase: TaskFlowPhase,
        ) {
            Log.error(
                TAG,
                "芝麻炼金⚗️[未知任务状态] module=$moduleName taskId=${item.id} taskName=${item.title} " +
                    "status=${item.status} actionType=${item.actionType} raw=${item.raw}",
            )
        }

        override fun actionKey(
            item: TaskFlowItem,
            action: TaskFlowAction,
        ): String {
            if (action == TaskFlowAction.COMPLETE && item.type != "AD_TASK") {
                val recordId = item.raw?.optString("recordId").orEmpty()
                if (recordId.isNotBlank()) {
                    return "${action.logName}:recordId:$recordId"
                }
            }
            val progressKey = item.current?.toString() ?: item.progress.ifBlank { "NO_PROGRESS" }
            val typeKey = item.actionType.ifBlank { item.type.ifBlank { "NO_TYPE" } }
            return "${action.logName}:${item.id.ifBlank { item.title }}:$progressKey:$typeKey"
        }

        override fun logInfo(message: String) {
            Log.sesame(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun appendAlchemyTaskItems(
            target: MutableList<TaskFlowItem>,
            taskList: JSONArray?,
            sourceName: String,
        ) {
            if (taskList == null) {
                return
            }
            for (index in 0..<taskList.length()) {
                taskList.optJSONObject(index)?.let { task -> target.add(buildAlchemyTaskItem(task, sourceName)) }
            }
        }

        private fun buildAlchemyTaskItem(
            task: JSONObject,
            sourceName: String,
        ): TaskFlowItem {
            val title = task.optString("title", "未知任务").ifBlank { "未知任务" }
            val bizType = task.optString("bizType")
            val templateId = task.optString("templateId").trim()
            val recordId = task.optString("recordId").ifBlank { joinedRecordIds[templateId].orEmpty() }
            val completedNum = task.optInt("completedNum", 0)
            val needCompleteNum = task.optInt("needCompleteNum", 1).takeIf { it > 0 } ?: 1
            val terminal = task.optBoolean("finishFlag", false) || completedNum >= needCompleteNum
            val status =
                when {
                    terminal -> "COMPLETED"
                    bizType == "AD_TASK" -> "WAIT_COMPLETE"
                    recordId.isBlank() -> "WAIT_JOIN"
                    else -> "WAIT_COMPLETE"
                }
            val adTaskBizId =
                task
                    .optJSONObject("logExtMap")
                    ?.optString("bizId")
                    .orEmpty()
                    .ifBlank { task.optString("adTaskBizId") }
                    .trim()
            val taskId = if (bizType == "AD_TASK") adTaskBizId else templateId
            val raw =
                JSONObject(task.toString())
                    .put("recordId", recordId)
                    .put("_sourceList", sourceName)
                    .put("_taskFlowId", taskId)
            return TaskFlowItem(
                id = taskId,
                title = title,
                status = status,
                type = bizType,
                sceneCode = task.optString("sceneCode"),
                actionType = task.optString("actionText").ifBlank { bizType },
                blacklistKeys = listOf(taskId, title).filter { it.isNotBlank() },
                raw = raw,
                progress = "$completedNum/$needCompleteNum",
                current = completedNum,
                limit = needCompleteNum,
            )
        }

        private fun completeAlchemyZhimaPigeonTask(
            item: TaskFlowItem,
            task: JSONObject,
        ): TaskFlowActionResult {
            val antFarm = Model.getModel(AntFarm::class.java)
                ?: return TaskFlowActionResult.defer(
                    deferredReason = DeferredReason.CHILD_TASK_PENDING,
                    message = "庄园模块未加载，等待芝麻大表鸽委派",
                    rpc = "AntFarm.activateZhimaPigeonFromAlchemyTask",
                    detail = alchemyActionDetail(item, "delegatePigeon"),
                )
            if (!antFarm.isZhimaPigeonConfigured()) {
                return TaskFlowActionResult.defer(
                    deferredReason = DeferredReason.CHILD_TASK_PENDING,
                    message = "芝麻大表鸽未在庄园配置，保留后续重试",
                    rpc = "AntFarm.activateZhimaPigeonFromAlchemyTask",
                    detail = alchemyActionDetail(item, "delegatePigeon"),
                )
            }
            if (antFarm.hasPendingZhimaPigeonRewardReceipt()) {
                antFarm.runNpcTaskFlow()
                scheduleSesameConfirmation(JSONObject().put("pigeon", true))
                return TaskFlowActionResult.defer(
                    DeferredReason.STATE_CONFIRMATION,
                    message = "当前大表鸽奖励仍待收取，暂缓新的反馈与雇佣",
                    rpc = "AntFarm.activateZhimaPigeonFromAlchemyTask",
                    detail = alchemyActionDetail(item, "delegatePigeon"),
                )
            }
            val feedbackResult =
                reportSesameTaskFeedbackResult(
                    task = task,
                    taskTitle = item.title,
                    logPrefix = "芝麻炼金⚗️",
                    moduleName = moduleName,
                    version = "alchemy",
                    sceneCode = "alchemy",
                )
            if (!feedbackResult.success && feedbackResult.failureType != TaskRpcFailureType.TERMINAL_DONE) {
                return feedbackResult
            }
            val activated =
                try {
                    runBlocking { antFarm.activateZhimaPigeonFromAlchemyTask() }
                } catch (t: Throwable) {
                    Log.printStackTrace(TAG, "芝麻炼金⚗️[委派芝麻大表鸽异常]", t)
                    TaskFlowActionResult.failure(
                        TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        message = t.message.orEmpty(),
                        rpc = "AntFarm.activateZhimaPigeonFromAlchemyTask",
                    )
                }
            if (activated.success) {
                Log.sesame("芝麻炼金⚗️[已确认庄园芝麻大表鸽工作状态]#${item.title}")
            }
            return activated.copy(
                rpc = activated.rpc.ifBlank { "AntFarm.activateZhimaPigeonFromAlchemyTask" },
                detail = alchemyActionDetail(item, "delegatePigeon"),
            )
        }

        private fun missingAlchemyRawResult(
            item: TaskFlowItem,
            action: String,
        ): TaskFlowActionResult =
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = "RAW_EMPTY",
                message = "任务原始数据为空",
                rpc = "AntSesameCredit.$action",
                detail = alchemyActionDetail(item, action),
            )

        private fun alchemyActionDetail(
            item: TaskFlowItem,
            action: String,
        ): String {
            val raw = item.raw
            return "module=$moduleName taskId=${item.id} taskName=${item.title} action=$action " +
                "templateId=${raw?.optString("templateId").orEmpty()} " +
                "recordId=${raw?.optString("recordId").orEmpty()} " +
                "bizType=${raw?.optString("bizType").orEmpty()} progress=${item.progress}"
        }

        private fun logSkipOnce(
            item: TaskFlowItem,
            reason: String,
        ) {
            val key = "$reason|${item.id}|${item.title}"
            if (loggedSkipKeys.add(key)) {
                Log.sesame("芝麻炼金⚗️[$reason]#${item.title}")
            }
        }
    }

    internal suspend fun doZhimaTree(): Unit =
        CoroutineUtils.run {
            try {
                ensureSesamePushModelSnapshotsLoaded()
                // 1. 执行首页和赚净化值列表任务，统一走 send -> refresh -> receive 闭环
                if (hasFlagToday(StatusFlags.FLAG_SESAME_ZHIMA_TREE_TASK_HANDLED_TODAY)) {
                    Log.sesame("芝麻树🌳[今日任务奖励已处理，跳过任务闭环]")
                } else {
                    doZhimaTreeTasks()
                }

                // 2. 消耗净化值进行净化
                doPurification()
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
            }
        }

    private suspend fun doZhimaTreeTasks(): Unit =
        CoroutineUtils.run {
            val adapter = ZhimaTreeTaskFlowAdapter()
            val runResult = TaskFlowEngine(adapter, roundSleepMs = 680L).run()
            if (adapter.hasHandledReceiveTask && runResult.completed && adapter.canMarkTodayDone()) {
                setFlagToday(StatusFlags.FLAG_SESAME_ZHIMA_TREE_TASK_HANDLED_TODAY)
                Log.sesame("芝麻树🌳[今日任务奖励已确认处理完成]")
            }
        }

    internal fun resetSesamePushModelTaskSnapshots() {
        sesamePushModelTaskSnapshots.clear()
    }

    private fun ensureSesamePushModelSnapshotsLoaded() {
        if (sesamePushModelTaskSnapshots.isNotEmpty()) {
            return
        }
        try {
            val response = AntSesameCreditRpcCall.queryAvailableSesameTask()
            var result = JSONObject(response)
            if (result.has("resData")) {
                result = result.getJSONObject("resData")
            }
            replaceSesamePushModelTaskSnapshots(
                collectSesamePushModelTaskSnapshots(result.optJSONObject("data")).values,
            )
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.ensureSesamePushModelSnapshotsLoaded", t)
        }
    }

    private fun replaceSesamePushModelTaskSnapshots(snapshots: Collection<SesamePushModelTaskSnapshot>) {
        val completedSnapshots =
            sesamePushModelTaskSnapshots.values
                .filter { it.completed }
                .associateBy { it.recordId }
        sesamePushModelTaskSnapshots.clear()
        for (snapshot in snapshots) {
            sesamePushModelTaskSnapshots[snapshot.recordId] = snapshot
        }
        for ((recordId, snapshot) in completedSnapshots) {
            sesamePushModelTaskSnapshots.putIfAbsent(recordId, snapshot)
        }
    }

    private fun collectSesamePushModelTaskSnapshots(taskObj: JSONObject?): LinkedHashMap<String, SesamePushModelTaskSnapshot> {
        val snapshots = linkedMapOf<String, SesamePushModelTaskSnapshot>()
        if (taskObj == null) {
            return snapshots
        }
        val dailyTaskListVO = taskObj.optJSONObject("dailyTaskListVO")
        appendSesamePushModelTaskSnapshots(
            snapshots,
            dailyTaskListVO?.optJSONArray("waitCompleteTaskVOS"),
            "daily.waitCompleteTaskVOS",
        )
        appendSesamePushModelTaskSnapshots(
            snapshots,
            dailyTaskListVO?.optJSONArray("waitJoinTaskVOS"),
            "daily.waitJoinTaskVOS",
        )
        appendSesamePushModelTaskSnapshots(
            snapshots,
            taskObj.optJSONArray("toCompleteVOS"),
            "toCompleteVOS",
        )
        return snapshots
    }

    private fun appendSesamePushModelTaskSnapshots(
        target: MutableMap<String, SesamePushModelTaskSnapshot>,
        taskList: JSONArray?,
        sourceName: String,
    ) {
        if (taskList == null) {
            return
        }
        for (i in 0..<taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            val snapshot = buildSesamePushModelTaskSnapshot(task, sourceName) ?: continue
            target[snapshot.recordId] = snapshot
        }
    }

    private fun buildSesamePushModelTaskSnapshot(
        task: JSONObject,
        sourceName: String,
    ): SesamePushModelTaskSnapshot? {
        if (!task.optBoolean("jumpToPushModel", false)) {
            return null
        }
        val recordId = task.optString("recordId").trim()
        if (recordId.isBlank()) {
            return null
        }
        val actionUrl = task.optString("actionUrl").trim()
        if (actionUrl.isBlank()) {
            return null
        }
        val taskContext = resolveZhimaTreeTaskContext(task)
        val appId = taskContext.appId
        if (appId.isBlank()) {
            return null
        }
        val completedNum = task.optInt("completedNum", 0)
        val needCompleteNum = task.optInt("needCompleteNum", 1).takeIf { it > 0 } ?: 1
        val completed =
            task.optBoolean("finishFlag", false) ||
                task.optString("actionText") == "已完成" ||
                completedNum >= needCompleteNum
        return SesamePushModelTaskSnapshot(
            recordId = recordId,
            title = task.optString("title").trim(),
            templateId = task.optString("templateId").trim(),
            merchantName =
                task
                    .optJSONObject("strategyRule")
                    ?.optString("merchantName")
                    .orEmpty()
                    .trim(),
            actionUrl = actionUrl,
            appId = appId,
            sourceName = sourceName,
            jumpToPushModel = true,
            completed = completed,
        )
    }

    private fun markSesamePushModelTaskFinished(recordId: String) {
        val snapshot = sesamePushModelTaskSnapshots[recordId] ?: return
        if (!snapshot.completed) {
            sesamePushModelTaskSnapshots[recordId] = snapshot.copy(completed = true)
        }
    }

    private fun findZhimaTreePushModelDelegate(taskRef: ZhimaTreeTaskRef): SesamePushModelTaskSnapshot? {
        val rentSnapshots = findZhimaTreePushModelSnapshots(taskRef)
        if (rentSnapshots.isEmpty()) {
            return null
        }
        val expectedAppId = resolveZhimaTreePushModelExpectedAppId(taskRef, rentSnapshots)
        if (expectedAppId.isBlank()) {
            return rentSnapshots.singleOrNull()
        }
        return rentSnapshots.filter { it.appId == expectedAppId }.singleOrNull()
    }

    private fun findZhimaTreePushModelSnapshots(taskRef: ZhimaTreeTaskRef): List<SesamePushModelTaskSnapshot> {
        if (taskRef.sourceName != "rent.taskDetailList") {
            return emptyList()
        }
        if (!taskRef.taskChannel.equals("RENT", ignoreCase = true)) {
            return emptyList()
        }
        return sesamePushModelTaskSnapshots.values.filter { snapshot ->
            snapshot.jumpToPushModel &&
                snapshot.sourceName in RENT_PUSH_MODEL_SNAPSHOT_SOURCES &&
                snapshot.merchantName == "芝麻租赁" &&
                snapshot.appId.isNotBlank()
        }
    }

    private fun resolveZhimaTreePushModelExpectedAppId(
        taskRef: ZhimaTreeTaskRef,
        rentSnapshots: List<SesamePushModelTaskSnapshot>,
    ): String =
        taskRef.appId.ifBlank {
            rentSnapshots
                .map { it.appId }
                .distinct()
                .singleOrNull()
                .orEmpty()
        }

    private fun resolveZhimaTreeTaskAppletId(taskRef: ZhimaTreeTaskRef): String {
        if (taskRef.sourceName != "rent.taskDetailList" ||
            !taskRef.taskChannel.equals("RENT", ignoreCase = true)
        ) {
            return ""
        }
        if (taskRef.appId.isNotBlank()) {
            return taskRef.appId
        }
        val rentSnapshots = findZhimaTreePushModelSnapshots(taskRef)
        return resolveZhimaTreePushModelExpectedAppId(taskRef, rentSnapshots)
    }

    private fun extractSesameAppId(text: String): String {
        val rawText = text.trim()
        if (rawText.isBlank()) {
            return ""
        }
        if (rawText.all { it.isDigit() }) {
            return rawText
        }
        UrlUtil
            .getParamValue(rawText, "appId")
            ?.trim()
            ?.takeIf { candidate -> candidate.isNotBlank() && candidate.all(Char::isDigit) }
            ?.let { return it }
        val hostSuffix = ".hybrid.alipay-eco.com"
        val hostIndex = rawText.indexOf(hostSuffix)
        if (hostIndex <= 0) {
            return ""
        }
        val schemeIndex = rawText.lastIndexOf("://", hostIndex)
        val hostStart = if (schemeIndex >= 0) schemeIndex + 3 else 0
        val host = rawText.substring(hostStart, hostIndex).trim()
        return host.takeIf { candidate -> candidate.isNotBlank() && candidate.all(Char::isDigit) }.orEmpty()
    }

    private inner class ZhimaTreeTaskFlowAdapter : TaskFlowAdapter {
        override val moduleName: String = sesameCreditTaskBlacklistModule
        override val flowName: String = "芝麻树任务"
        override val nonRetryableActionFlagPrefix: String = StatusFlags.FLAG_SESAME_ZHIMA_TREE_ACTION_STOP_PREFIX

        private val sourceResults = JSONObject()
        private val handledAdBizIds = mutableSetOf<String>()
        private val pendingAdRewardScores = linkedMapOf<String, Long>()
        private val handledReceiveTaskKeys = mutableSetOf<String>()
        private val pendingSentTaskRefs = linkedMapOf<String, ZhimaTreeTaskRef>()
        private val loggedSkipKeys = mutableSetOf<String>()
        private var lastQuerySucceeded = false
        private var lastUnknownCount = 0
        private var lastActionableCount = 0
        val hasHandledReceiveTask: Boolean
            get() = handledReceiveTaskKeys.isNotEmpty()

        fun canMarkTodayDone(): Boolean =
            lastQuerySucceeded &&
                lastUnknownCount == 0 &&
                lastActionableCount == 0 &&
                pendingSentTaskRefs.isEmpty() &&
                pendingAdRewardScores.isEmpty()

        override fun query(): JSONObject {
            val result = sourceResults

            if (!result.has("homeRaw") || result.optBoolean("homeConfirmed")) try {
                result.put("homeRaw", "").put("homeConfirmed", false).remove("homeQueryResult")
                val homeRes = AntSesameCreditRpcCall.zhimaTreeHomePage()
                result.put("homeRaw", homeRes ?: "")
                if (!homeRes.isNullOrBlank()) {
                    val homeJson = JSONObject(homeRes)
                    val homeData = homeJson.optJSONObject("extInfo")?.optJSONObject("zhimaTreeHomePageQueryResult")
                    if (ResChecker.checkRes(TAG, homeJson) && homeData != null) {
                        result.put("homeConfirmed", true)
                        result.put("homeQueryResult", homeData)
                    } else {
                        result.put("homeError", homeJson)
                    }
                }
            } catch (t: Throwable) {
                result.put("homeException", t.message.orEmpty())
            }

            if (!result.has("rentRaw") || result.optBoolean("rentConfirmed")) try {
                result.put("rentRaw", "").put("rentConfirmed", false).remove("rentTaskDetailList")
                val rentRes = AntSesameCreditRpcCall.queryRentGreenTaskList()
                result.put("rentRaw", rentRes ?: "")
                if (!rentRes.isNullOrBlank()) {
                    val rentJson = JSONObject(rentRes)
                    val rentData = rentJson.optJSONObject("extInfo")?.optJSONObject("taskDetailList")
                    if (ResChecker.checkRes(TAG, rentJson) && rentData != null) {
                        result.put("rentConfirmed", true)
                        result.put("rentTaskDetailList", rentData)
                    } else {
                        result.put("rentError", rentJson)
                    }
                }
            } catch (t: Throwable) {
                result.put("rentException", t.message.orEmpty())
            }

            val homeConfirmed = result.optBoolean("homeConfirmed")
            val rentConfirmed = result.optBoolean("rentConfirmed")
            if (homeConfirmed) {
                val purificationScore = result.optJSONObject("homeQueryResult")
                    ?.optLong("purificationScore", -1L) ?: -1L
                val iterator = pendingAdRewardScores.entries.iterator()
                while (iterator.hasNext()) {
                    val pending = iterator.next()
                    if (purificationScore >= pending.value) {
                        Log.sesame("芝麻树🌳[净化值回查已达到预期] bizId=${pending.key} 当前=$purificationScore 目标=${pending.value}")
                        iterator.remove()
                    }
                }
            }
            result.put("success", homeConfirmed || rentConfirmed)
            lastQuerySucceeded = homeConfirmed && rentConfirmed
            if (!lastQuerySucceeded) {
                Log.error(TAG, "芝麻树🌳[部分任务来源未确认，保留有效来源执行] homeConfirmed=$homeConfirmed rentConfirmed=$rentConfirmed raw=$result")
            }
            return result
        }

        override fun isQuerySuccess(response: JSONObject): Boolean = response.optBoolean("success", false)

        override fun isQueryComplete(response: JSONObject): Boolean =
            response.optBoolean("homeConfirmed") && response.optBoolean("rentConfirmed")

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val items = mutableListOf<TaskFlowItem>()
            val currentTaskRefs = mutableListOf<ZhimaTreeTaskRef>()
            val seenTaskKeys = mutableSetOf<String>()

            val homeQueryResult = response.optJSONObject("homeQueryResult")
            if (homeQueryResult != null) {
                appendZhimaTreeAdItems(
                    items,
                    homeQueryResult.optJSONArray("homeSpaceResultList"),
                    "home.homeSpaceResultList",
                )
                appendZhimaTreeTaskItems(
                    items,
                    currentTaskRefs,
                    seenTaskKeys,
                    homeQueryResult.optJSONArray("browseTaskList"),
                    "home.browseTaskList",
                )
                appendZhimaTreeTaskItems(
                    items,
                    currentTaskRefs,
                    seenTaskKeys,
                    homeQueryResult.optJSONArray("taskStatusList"),
                    "home.taskStatusList",
                )
                appendZhimaTreeTaskItems(
                    items,
                    currentTaskRefs,
                    seenTaskKeys,
                    homeQueryResult.optJSONArray("staticSceneGuideTaskList"),
                    "home.staticSceneGuideTaskList",
                )
            }

            val rentTaskDetailList = response.optJSONObject("rentTaskDetailList")
            if (rentTaskDetailList != null) {
                appendZhimaTreeAdItems(
                    items,
                    rentTaskDetailList.optJSONArray("spaceResultList"),
                    "rent.spaceResultList",
                )
                appendZhimaTreeTaskItems(
                    items,
                    currentTaskRefs,
                    seenTaskKeys,
                    rentTaskDetailList.optJSONArray("taskDetailList"),
                    "rent.taskDetailList",
                )
            }

            removeConfirmedPendingTasks(currentTaskRefs)
            if (isQueryComplete(response)) {
                appendPendingReceiveFallbacks(items, currentTaskRefs, seenTaskKeys)
            }
            refreshZhimaTreeSnapshot(items)
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase {
            if (item.type == "AD_TASK") {
                return TaskFlowPhase.READY_TO_COMPLETE
            }
            val needManualReceive = item.raw?.optBoolean("needManuallyReceiveAward", true) ?: true
            val needSignUp = item.raw?.optBoolean("needSignUp", false) ?: false
            val phase = when (item.status) {
                "TO_RECEIVE" -> {
                    TaskFlowPhase.REWARD_READY
                }

                "RECEIVE_SUCCESS" -> {
                    if (needManualReceive) {
                        TaskFlowPhase.REWARD_READY
                    } else {
                        TaskFlowPhase.TERMINAL
                    }
                }

                "NONE_SIGNUP",
                "UN_SIGNUP",
                -> {
                    if (needSignUp) {
                        TaskFlowPhase.SIGNUP_REQUIRED
                    } else {
                        TaskFlowPhase.READY_TO_COMPLETE
                    }
                }

                "NOT_DONE",
                "SIGNUP_COMPLETE",
                "SIGNUP_COMPLETED",
                -> {
                    TaskFlowPhase.SIGNUP_COMPLETE
                }

                "WAIT_COMPLETE" -> {
                    TaskFlowPhase.READY_TO_COMPLETE
                }

                "DONE",
                "COMPLETE",
                "FINISHED",
                "RECEIVED",
                -> {
                    TaskFlowPhase.TERMINAL
                }

                else -> {
                    TaskFlowPhase.UNKNOWN
                }
            }
            if ((phase == TaskFlowPhase.SIGNUP_COMPLETE || phase == TaskFlowPhase.READY_TO_COMPLETE) &&
                item.raw?.optString("sendCampTriggerType") == "EVENT_TRIGGER" &&
                findZhimaTreePushModelDelegate(item.toZhimaTreeTaskRef()) == null
            ) {
                return TaskFlowPhase.BUSINESS_ACTION
            }
            return phase
        }

        override fun shouldSkip(item: TaskFlowItem): Boolean {
            if (item.type == "AD_TASK") {
                return item.id in handledAdBizIds
            }
            val phase = mapPhase(item)
            if ((
                    phase == TaskFlowPhase.SIGNUP_REQUIRED ||
                        phase == TaskFlowPhase.SIGNUP_COMPLETE ||
                        phase == TaskFlowPhase.REWARD_READY
                ) &&
                item.id.isBlank()
            ) {
                logZhimaTreeSkipOnce(item, "跳过无有效任务ID")
                return true
            }
            if (phase == TaskFlowPhase.SIGNUP_COMPLETE && zhimaTreeTaskKey(item) in pendingSentTaskRefs) {
                logZhimaTreeSkipOnce(item, "等待上次send回查")
                return true
            }
            if (phase == TaskFlowPhase.REWARD_READY && zhimaTreeTaskKey(item) in handledReceiveTaskKeys) {
                logZhimaTreeSkipOnce(item, "本轮已领取，等待刷新确认")
                return true
            }
            return false
        }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            if (item.type != "AD_TASK") {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    message = "芝麻树非广告任务不走complete",
                    rpc = "AntSesameCredit.zhimaTree.complete",
                    detail = zhimaTreeActionDetail(item, "complete"),
                )
            }
            val taskRef =
                ZhimaTreeAdTaskRef(
                    title = item.title,
                    rewardText = item.raw?.optString("rewardText").orEmpty(),
                    bizId = item.id,
                    spaceCode =
                        item.raw
                            ?.optString("spaceCode")
                            .orEmpty()
                            .takeIf { it.isNotBlank() },
                )
            if (item.raw?.optString("_sourceList") != "home.homeSpaceResultList") {
                return finishZhimaTreeAdTaskResult(taskRef)
            }
            val rewardAmount = extractAdRenderConfigValue(taskRef.spaceCode, "rewardNum")
                .toIntOrNull()?.takeIf { it > 0 }
                ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    message = "芝麻树主页广告缺少可确认的奖励数量",
                    rpc = "AntSesameCreditRpcCall.taskFinish",
                    raw = item.raw.toString(),
                )
            val before = query()
            val scoreBefore = if (before.optBoolean("homeConfirmed")) {
                before.optJSONObject("homeQueryResult")?.optLong("purificationScore", -1L) ?: -1L
            } else -1L
            if (scoreBefore < 0) {
                return TaskFlowActionResult.defer(
                    deferredReason = DeferredReason.STATE_CONFIRMATION,
                    message = "芝麻树主页净化值未确认，暂缓广告任务",
                    rpc = "AntSesameCreditRpcCall.zhimaTreeHomePage",
                )
            }
            val result = finishZhimaTreeAdTaskResult(taskRef)
            if (!result.success) return result
            handledAdBizIds.add(item.id)
            pendingAdRewardScores[item.id] =
                maxOf(scoreBefore, pendingAdRewardScores.values.maxOrNull() ?: scoreBefore) + rewardAmount
            query()
            return if (item.id !in pendingAdRewardScores) {
                TaskFlowActionResult.success()
            } else {
                TaskFlowActionResult.defer(
                    deferredReason = DeferredReason.STATE_CONFIRMATION,
                    message = "芝麻树主页广告已上报，净化值尚未确认到账",
                    rpc = "AntSesameCreditRpcCall.zhimaTreeHomePage",
                    detail = "bizId=${item.id} expectedScore=${pendingAdRewardScores[item.id]}",
                    refreshAfterAction = true,
                )
            }
        }

        override fun signup(item: TaskFlowItem): TaskFlowActionResult {
            val taskRef = item.toZhimaTreeTaskRef()
            val taskId = taskRef.taskId
            if (taskId.isNullOrBlank()) {
                return missingZhimaTreeTaskIdResult(item, "signup")
            }
            val signupResult = doTaskActionResult(taskRef, "signup")
            if (signupResult.success) {
                val rewardSuffix =
                    taskRef.prizeName
                        .takeIf { it.isNotBlank() }
                        ?.let { " #$it" }
                        .orEmpty()
                Log.sesame("芝麻树🌳[报名成功] ${taskRef.title}$rewardSuffix")
                return TaskFlowActionResult.success()
            }
            return zhimaTreeActionFailureResult(item, "signup", signupResult)
        }

        override fun send(item: TaskFlowItem): TaskFlowActionResult {
            val taskRef = item.toZhimaTreeTaskRef()
            val taskId = taskRef.taskId
            if (taskId.isNullOrBlank()) {
                return missingZhimaTreeTaskIdResult(item, "send")
            }
            Log.sesame(
                "芝麻树🌳[开始任务] " + taskRef.title +
                    (if (taskRef.prizeName.isEmpty()) "" else " (${taskRef.prizeName})"),
            )
            tryDelegateZhimaTreePushModelSend(item, taskRef)?.let { return it }
            val sendResult = doTaskActionResult(taskRef, "send")
            if (sendResult.success) {
                pendingSentTaskRefs[taskRef.key()] = taskRef
                return zhimaTreePendingSendResult(item, taskRef, "send")
            }
            return zhimaTreeActionFailureResult(item, "send", sendResult)
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            val taskRef = item.toZhimaTreeTaskRef()
            val taskId = taskRef.taskId
            if (taskId.isNullOrBlank()) {
                return missingZhimaTreeTaskIdResult(item, "receive")
            }
            val receiveResult = doTaskActionResult(taskRef, "receive")
            if (receiveResult.success) {
                handledReceiveTaskKeys.add(zhimaTreeTaskKey(item))
                removePendingTaskRef(taskRef)
                Log.sesame(buildZhimaTreeSuccessLog("领取奖励", taskRef))
                return TaskFlowActionResult.success()
            }
            return zhimaTreeActionFailureResult(item, "receive", receiveResult)
        }

        override fun afterSuccess(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
        ) {
            if (item.type == "AD_TASK" && action == TaskFlowAction.COMPLETE) {
                handledAdBizIds.add(item.id)
            }
        }

        override fun actionKey(
            item: TaskFlowItem,
            action: TaskFlowAction,
        ): String =
            if (item.type == "AD_TASK") {
                "${action.logName}:AD_TASK:${item.id}"
            } else {
                val taskRef = item.toZhimaTreeTaskRef()
                val delegateRecordId = if (action == TaskFlowAction.SEND) {
                    findZhimaTreePushModelDelegate(taskRef)?.recordId.orEmpty()
                } else ""
                "${action.logName}:${taskRef.key()}:${item.status}:${taskRef.appletType}:" +
                    "${taskRef.appId}:${taskRef.chInfo}:${taskRef.refer}:${taskRef.playInfo}:$delegateRecordId"
            }

        override fun onQueryFailed(response: JSONObject) {
            Log.error(
                TAG,
                "$flowName[任务查询失败] home=${response.optString("homeRaw")} rent=${response.optString("rentRaw")} " +
                    "homeError=${response.opt("homeError")} rentError=${response.opt("rentError")} " +
                    "homeException=${response.optString("homeException")} rentException=${response.optString("rentException")}",
            )
        }

        override fun onUnknownPhase(
            item: TaskFlowItem,
            phase: TaskFlowPhase,
        ) {
            Log.error(
                TAG,
                "芝麻树🌳[未知任务状态] module=$moduleName taskId=${item.id} taskName=${item.title} " +
                    "status=${item.status} actionType=${item.actionType} raw=${item.raw}",
            )
        }

        override fun logInfo(message: String) {
            Log.sesame(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun refreshZhimaTreeSnapshot(items: List<TaskFlowItem>) {
            lastUnknownCount = 0
            lastActionableCount = 0
            for (item in items) {
                val phase = mapPhase(item)
                if ((phase != TaskFlowPhase.REWARD_READY && isBlacklisted(item)) || shouldSkip(item)) {
                    continue
                }
                if (phase == TaskFlowPhase.UNKNOWN) {
                    lastUnknownCount++
                    continue
                }
                if (phase == TaskFlowPhase.REWARD_READY ||
                    phase == TaskFlowPhase.READY_TO_COMPLETE ||
                    phase == TaskFlowPhase.SIGNUP_REQUIRED ||
                    phase == TaskFlowPhase.SIGNUP_COMPLETE ||
                    phase == TaskFlowPhase.BUSINESS_ACTION
                ) {
                    lastActionableCount++
                }
            }
        }

        private fun appendZhimaTreeTaskItems(
            target: MutableList<TaskFlowItem>,
            currentTaskRefs: MutableList<ZhimaTreeTaskRef>,
            seenTaskKeys: MutableSet<String>,
            tasks: JSONArray?,
            sourceName: String,
        ) {
            if (tasks == null) return
            for (i in 0..<tasks.length()) {
                val task = tasks.optJSONObject(i) ?: continue
                val taskRef = buildZhimaTreeTaskRef(task, sourceName) ?: continue
                currentTaskRefs.add(taskRef)
                val key = taskRef.key()
                if (!seenTaskKeys.add(key)) {
                    continue
                }
                val item = taskRef.toTaskFlowItem(sourceName)
                item.raw?.put("sendCampTriggerType", task.optString("sendCampTriggerType"))
                target.add(item)
            }
        }

        private fun appendZhimaTreeAdItems(
            target: MutableList<TaskFlowItem>,
            spaceResultList: JSONArray?,
            sourceName: String,
        ) {
            if (spaceResultList == null) return
            for (i in 0..<spaceResultList.length()) {
                val spaceResult = spaceResultList.optJSONObject(i) ?: continue
                val listSpaceCode = spaceResult.optString("spaceCode")
                val spaceObjectList = spaceResult.optJSONArray("spaceObjectList") ?: continue
                for (j in 0..<spaceObjectList.length()) {
                    val spaceObject = spaceObjectList.optJSONObject(j) ?: continue
                    val adTask = extractZhimaTreeAdTaskContent(spaceObject) ?: continue
                    val adTaskRef = buildZhimaTreeAdTaskRef(adTask, listSpaceCode) ?: continue
                    if (adTaskRef.bizId in handledAdBizIds ||
                        target.any { it.type == "AD_TASK" && it.id == adTaskRef.bizId }
                    ) {
                        continue
                    }
                    target.add(adTaskRef.toTaskFlowItem(sourceName))
                }
            }
        }

        private fun removeConfirmedPendingTasks(currentTaskRefs: List<ZhimaTreeTaskRef>) {
            val iterator = pendingSentTaskRefs.entries.iterator()
            while (iterator.hasNext()) {
                val pendingTask = iterator.next().value
                val matched =
                    currentTaskRefs.firstOrNull { refreshedTask ->
                        isSameZhimaTreeTask(pendingTask, refreshedTask, requireSameTaskId = false)
                    } ?: continue
                if (matched.status in setOf("DONE", "COMPLETE", "FINISHED", "RECEIVED") ||
                    (matched.status == "RECEIVE_SUCCESS" && !matched.needManuallyReceiveAward)
                ) {
                    iterator.remove()
                }
            }
        }

        private fun removePendingTaskRef(receivedTask: ZhimaTreeTaskRef) {
            val iterator = pendingSentTaskRefs.entries.iterator()
            while (iterator.hasNext()) {
                val pendingTask = iterator.next().value
                if (isSameZhimaTreeTask(pendingTask, receivedTask, requireSameTaskId = false)) {
                    iterator.remove()
                }
            }
        }

        private fun appendPendingReceiveFallbacks(
            target: MutableList<TaskFlowItem>,
            currentTaskRefs: List<ZhimaTreeTaskRef>,
            seenTaskKeys: MutableSet<String>,
        ) {
            for (pendingTask in pendingSentTaskRefs.values) {
                val stillVisible =
                    currentTaskRefs.any { refreshedTask ->
                        isSameZhimaTreeTask(pendingTask, refreshedTask, requireSameTaskId = false)
                    }
                if (stillVisible || !pendingTask.needManuallyReceiveAward) {
                    continue
                }
                val receiveFallback = pendingTask.copy(status = "TO_RECEIVE")
                if (seenTaskKeys.add(receiveFallback.key())) {
                    Log.sesame("芝麻树🌳[回查未找到任务，尝试直接领取] ${receiveFallback.title} | candidates=${receiveFallback.describeCandidates()}")
                    target.add(receiveFallback.toTaskFlowItem("pending.sendFallback", syntheticReceive = true))
                }
            }
        }

        private fun ZhimaTreeTaskRef.toTaskFlowItem(
            sourceName: String,
            syntheticReceive: Boolean = false,
        ): TaskFlowItem {
            val raw =
                JSONObject()
                    .put("title", title)
                    .put("prizeName", prizeName)
                    .put("status", status)
                    .put("taskId", taskId ?: "")
                    .put("taskIdCandidates", JSONArray(taskIdCandidates))
                    .put("needSignUp", needSignUp)
                    .put("needManuallyReceiveAward", needManuallyReceiveAward)
                    .put("templateCode", templateCode)
                    .put("appletType", appletType)
                    .put("taskType", taskType)
                    .put("taskMaterialType", taskMaterialType)
                    .put("taskChannel", taskChannel)
                    .put("chInfo", chInfo)
                    .put("refer", refer)
                    .put("playInfo", playInfo)
                    .put("appId", appId)
                    .put("_sourceList", sourceName)
                    .put("_syntheticReceive", syntheticReceive)
            val safeTaskId = taskId.orEmpty()
            return TaskFlowItem(
                id = safeTaskId,
                title = title,
                status = status,
                type = "ZHIMA_TREE_TASK",
                actionType = "rentGreenTaskFinish",
                blacklistKeys = listOf(safeTaskId).filter { it.isNotBlank() },
                raw = raw,
                progress = prizeName,
            )
        }

        private fun ZhimaTreeAdTaskRef.toTaskFlowItem(sourceName: String): TaskFlowItem {
            val raw =
                JSONObject()
                    .put("rewardText", rewardText)
                    .put("bizId", bizId)
                    .put("spaceCode", spaceCode ?: "")
                    .put("_sourceList", sourceName)
            return TaskFlowItem(
                id = bizId,
                title = title,
                status = "WAIT_COMPLETE",
                type = "AD_TASK",
                actionType = "AD_TASK",
                blacklistKeys = listOf(bizId).filter { it.isNotBlank() },
                raw = raw,
                progress = rewardText,
            )
        }

        private fun TaskFlowItem.toZhimaTreeTaskRef(): ZhimaTreeTaskRef {
            val raw = raw ?: JSONObject()
            val candidatesJson = raw.optJSONArray("taskIdCandidates")
            val candidates = mutableListOf<String>()
            if (candidatesJson != null) {
                for (i in 0..<candidatesJson.length()) {
                    candidates.add(candidatesJson.optString(i))
                }
            }
            return ZhimaTreeTaskRef(
                title = title,
                prizeName = raw.optString("prizeName"),
                status = status,
                taskId = normalizeZhimaTreeTaskId(raw.optString("taskId").ifBlank { id }),
                taskIdCandidates = candidates.ifEmpty { listOf(id) },
                needSignUp = raw.optBoolean("needSignUp", false),
                needManuallyReceiveAward = raw.optBoolean("needManuallyReceiveAward", true),
                templateCode = raw.optString("templateCode"),
                appletType = raw.optString("appletType"),
                taskType = raw.optString("taskType"),
                taskMaterialType = raw.optString("taskMaterialType"),
                taskChannel = raw.optString("taskChannel"),
                chInfo = raw.optString("chInfo"),
                refer = raw.optString("refer"),
                playInfo = raw.optString("playInfo"),
                appId = raw.optString("appId"),
                sourceName = raw.optString("_sourceList"),
            )
        }

        private fun ZhimaTreeTaskRef.key(): String = (taskId ?: title) + "|" + title + "|" + prizeName

        private fun zhimaTreeTaskKey(item: TaskFlowItem): String = item.toZhimaTreeTaskRef().key()

        private fun missingZhimaTreeTaskIdResult(
            item: TaskFlowItem,
            action: String,
        ): TaskFlowActionResult =
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.NON_RETRYABLE_INVALID,
                code = "TASK_ID_EMPTY",
                message = "芝麻树任务ID为空",
                rpc = "AntSesameCreditRpcCall.rentGreenTaskFinish",
                detail = zhimaTreeActionDetail(item, action),
            )

        private fun zhimaTreeActionFailureResult(
            item: TaskFlowItem,
            stageCode: String,
            actionResult: ZhimaTreeActionResult,
        ): TaskFlowActionResult {
            val response = actionResult.response
            val code =
                response
                    ?.optString("errorCode")
                    .orEmpty()
                    .ifBlank { response?.optString("resultCode").orEmpty() }
                    .ifBlank { response?.optString("code").orEmpty() }
            val message =
                response
                    ?.let { extractZhimaTreeActionFailureMessage(it) }
                    .orEmpty()
                    .ifBlank { actionResult.rawResponse.orEmpty() }
            val failureType = classifyZhimaTreeTaskFailure(response)
            return TaskFlowActionResult.failure(
                failureType = failureType,
                code = code,
                message = message,
                rpc = "AntSesameCreditRpcCall.rentGreenTaskFinish",
                raw = actionResult.rawResponse.orEmpty(),
                detail = zhimaTreeActionDetail(item, stageCode),
            )
        }

        private fun zhimaTreePendingSendResult(
            item: TaskFlowItem,
            taskRef: ZhimaTreeTaskRef,
            stageCode: String,
        ): TaskFlowActionResult =
            TaskFlowActionResult.defer(
                deferredReason = DeferredReason.STATE_CONFIRMATION,
                message = "send已发起，等待服务端状态确认",
                rpc = "AntSesameCreditRpcCall.rentGreenTaskFinish",
                detail = zhimaTreeActionDetail(item, stageCode) + " pendingSend=${taskRef.key()}",
                refreshAfterAction = true,
                progressChanged = true,
            )

        private fun zhimaTreeActionDetail(
            item: TaskFlowItem,
            action: String,
        ): String {
            val raw = item.raw
            return "taskId=${item.id} taskName=${item.title} action=$action " +
                "prize=${raw?.optString("prizeName").orEmpty()} " +
                "templateCode=${raw?.optString("templateCode").orEmpty()} " +
                "appletType=${raw?.optString("appletType").orEmpty()} " +
                "taskType=${raw?.optString("taskType").orEmpty()} " +
                "taskMaterialType=${raw?.optString("taskMaterialType").orEmpty()} " +
                "taskChannel=${raw?.optString("taskChannel").orEmpty()} " +
                "chInfo=${raw?.optString("chInfo").orEmpty()} " +
                "refer=${raw?.optString("refer").orEmpty()} " +
                "playInfo=${raw?.optString("playInfo").orEmpty()} " +
                "appId=${raw?.optString("appId").orEmpty()} " +
                "candidates=${raw?.optJSONArray("taskIdCandidates") ?: JSONArray()} " +
                "source=${raw?.optString("_sourceList").orEmpty()}"
        }

        private fun logZhimaTreeSkipOnce(
            item: TaskFlowItem,
            reason: String,
        ) {
            val key = "$reason|${item.id}|${item.title}"
            if (loggedSkipKeys.add(key)) {
                Log.sesame("芝麻树🌳[$reason] ${item.title} | candidates=${item.raw?.optJSONArray("taskIdCandidates") ?: JSONArray()}")
            }
        }

        private fun tryDelegateZhimaTreePushModelSend(
            item: TaskFlowItem,
            taskRef: ZhimaTreeTaskRef,
        ): TaskFlowActionResult? {
            val snapshot = findZhimaTreePushModelDelegate(taskRef) ?: return null
            if (snapshot.completed) {
                pendingSentTaskRefs[taskRef.key()] = taskRef
                Log.sesame("芝麻树🌳[复用push闭环已完成] ${taskRef.title} -> recordId=${snapshot.recordId}")
                return zhimaTreePendingSendResult(item, taskRef, "delegatePushActivity")
            }
            val finishRes = AntSesameCreditRpcCall.finishSesameTask(snapshot.recordId)
            val responseObj =
                parseJSONObjectOrNull(finishRes)
                    ?: return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        message = "pushActivity返回空或无法解析",
                        rpc = "AntSesameCreditRpcCall.finishSesameTask",
                        raw = finishRes,
                        detail = zhimaTreeActionDetail(item, "delegatePushActivity"),
                    )
            if (ResChecker.checkRes(TAG, responseObj)) {
                markSesamePushModelTaskFinished(snapshot.recordId)
                pendingSentTaskRefs[taskRef.key()] = taskRef
                Log.sesame("芝麻树🌳[复用push闭环] ${taskRef.title} -> recordId=${snapshot.recordId}")
                return zhimaTreePendingSendResult(item, taskRef, "delegatePushActivity")
            }
            val code =
                responseObj
                    .optString("errorCode")
                    .ifBlank { responseObj.optString("resultCode") }
                    .ifBlank { responseObj.optString("code") }
            val message =
                responseObj
                    .optString("resultView")
                    .ifBlank { responseObj.optString("errorMsg") }
                    .ifBlank { responseObj.optString("errorMessage") }
                    .ifBlank { finishRes }
            val failureType =
                when {
                    code == "20020012" && explicitZhimaTreeRetryable(responseObj) == false -> {
                        TaskRpcFailureType.NON_RETRYABLE_INVALID
                    }

                    code in setOf("TASK_ID_INVALID", "PROMISE_TEMPLATE_NOT_EXIST") -> {
                        TaskRpcFailureType.NON_RETRYABLE_INVALID
                    }

                    else -> {
                        classifyZhimaTreeTaskFailure(responseObj)
                    }
                }
            return TaskFlowActionResult.failure(
                failureType = failureType,
                code = code,
                message = message,
                rpc = "AntSesameCreditRpcCall.finishSesameTask",
                raw = finishRes,
                detail = zhimaTreeActionDetail(item, "delegatePushActivity"),
            )
        }
    }

    private fun extractZhimaTreeAdTaskContent(spaceObject: JSONObject): JSONObject? =
        when (val content = spaceObject.opt("content")) {
            is JSONObject -> content
            is String -> parseJSONObjectOrNull(content) ?: spaceObject
            else -> spaceObject
        }

    private fun buildZhimaTreeAdTaskRef(
        adTask: JSONObject,
        listSpaceCode: String,
    ): ZhimaTreeAdTaskRef? {
        val logExtMap = adTask.optJSONObject("logExtMap")
        val schemaJson = parseJSONObjectOrNull(adTask.optString("schemaJson"))
        val clickThroughUrl =
            adTask
                .optString("clickThroughUrl")
                .ifBlank { schemaJson?.optString("url").orEmpty() }
        val rewardAmount =
            schemaJson
                ?.optString("taskRewardAmount")
                .orEmpty()
                .ifBlank { adTask.optString("rewardNum") }
                .ifBlank { logExtMap?.optString("rewardNum").orEmpty() }
        val spaceCode =
            resolveAdTaskSpaceCode(
                logExtMap,
                clickThroughUrl,
                fallbackSpaceCode = listSpaceCode,
                fallbackRewardNum = rewardAmount,
            )
        val bizId =
            logExtMap
                ?.optString("bizId")
                .orEmpty()
                .ifBlank { adTask.optString("xlightBizId") }
                .ifBlank { adTask.optString("bizId") }
                .ifBlank { schemaJson?.optString("adBizId").orEmpty() }
                .ifBlank { extractQueryParam(clickThroughUrl, "bizId").orEmpty() }
                .ifBlank { extractAdRenderConfigValue(spaceCode, "bizId") }
        if (bizId.isBlank()) {
            return null
        }
        val title =
            schemaJson
                ?.optString("taskMainTitle")
                .orEmpty()
                .ifBlank { schemaJson?.optString("title").orEmpty() }
                .ifBlank { adTask.optString("title") }
                .ifBlank { "芝麻树广告浏览任务" }
        val renderRewardAmount =
            rewardAmount.ifBlank {
                extractAdRenderConfigValue(spaceCode, "rewardNum")
            }
        val rewardText =
            if (renderRewardAmount.isBlank()) {
                "奖励已领取"
            } else if (renderRewardAmount.contains("净化") || renderRewardAmount.contains("能量")) {
                renderRewardAmount
            } else {
                renderRewardAmount + "净化值"
            }
        return ZhimaTreeAdTaskRef(
            title = title,
            rewardText = rewardText,
            bizId = bizId,
            spaceCode = spaceCode,
        )
    }

    private fun finishZhimaTreeAdTaskResult(taskRef: ZhimaTreeAdTaskRef): TaskFlowActionResult {
        val spaceCode = taskRef.spaceCode
        if (spaceCode.isNullOrBlank()) {
            Log.sesame("芝麻树🌳[广告任务缺少浏览配置] ${taskRef.title} | bizId=${taskRef.bizId}")
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = "SPACE_CODE_EMPTY",
                message = "广告任务缺少浏览配置",
                rpc = "AntSesameCreditRpcCall.adTaskApplayerQuery",
                detail = "module=$sesameCreditTaskBlacklistModule taskId=${taskRef.bizId} taskName=${taskRef.title} action=adLayer",
            )
        }
        return try {
            Log.sesame("芝麻树🌳[广告任务准备] ${taskRef.title}")
            val layerRes = AntSesameCreditRpcCall.adTaskApplayerQuery(spaceCode)
            val layerJo = JSONObject(layerRes)
            if (!ResChecker.checkRes(TAG, layerJo) && "0" != layerJo.optString("errCode")) {
                val layerMsg = buildSesameRpcMessage(layerJo, layerRes)
                if (isAdTaskRetryable(layerJo)) {
                    Log.sesame("芝麻树🌳[广告浏览配置暂时不可用] ${taskRef.title} - $layerMsg")
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        code = layerJo.optString("errorCode", layerJo.optString("resultCode", layerJo.optString("errCode", ""))),
                        message = layerMsg,
                        rpc = "AntSesameCreditRpcCall.adTaskApplayerQuery",
                        raw = layerRes,
                        detail = "module=$sesameCreditTaskBlacklistModule taskId=${taskRef.bizId} taskName=${taskRef.title} action=adLayer",
                    )
                } else {
                    Log.error(TAG, "芝麻树🌳[广告浏览配置失败] ${taskRef.title} - $layerMsg")
                    val layerCode = layerJo.optString("errorCode", layerJo.optString("resultCode", layerJo.optString("errCode", "")))
                    return TaskFlowActionResult.failure(
                        failureType = classifySesameTaskFailure(layerCode, layerJo),
                        code = layerCode,
                        message = layerMsg,
                        rpc = "AntSesameCreditRpcCall.adTaskApplayerQuery",
                        raw = layerRes,
                        detail = "module=$sesameCreditTaskBlacklistModule taskId=${taskRef.bizId} taskName=${taskRef.title} action=adLayer",
                    )
                }
            }
            val finishRes = AntSesameCreditRpcCall.taskFinish(taskRef.bizId, includeExtendInfo = false)
            val finishJo = JSONObject(finishRes)
            if (isAdTaskFinishSuccess(finishJo)) {
                Log.sesame("芝麻树🌳[广告完成请求已接受] ${taskRef.title}，等待奖励回查")
                return TaskFlowActionResult.success()
            }
            val finishMsg = buildSesameRpcMessage(finishJo, finishRes)
            val finishCode = finishJo.optString("errorCode", finishJo.optString("resultCode", finishJo.optString("errCode", "")))
            if (isSesameAdTaskAlreadyFinished(finishJo)) {
                Log.sesame("芝麻树🌳[广告任务已完成，跳过重复上报] ${taskRef.title} - $finishMsg")
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.TERMINAL_DONE,
                    code = finishCode,
                    message = finishMsg,
                    rpc = "AntSesameCreditRpcCall.taskFinish",
                    raw = finishRes,
                    detail = "module=$sesameCreditTaskBlacklistModule taskId=${taskRef.bizId} taskName=${taskRef.title} action=adFinish",
                )
            }
            if (isAdTaskRetryable(finishJo)) {
                Log.sesame("芝麻树🌳[广告任务暂时未完成] ${taskRef.title} - $finishMsg")
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    code = finishCode,
                    message = finishMsg,
                    rpc = "AntSesameCreditRpcCall.taskFinish",
                    raw = finishRes,
                    detail = "module=$sesameCreditTaskBlacklistModule taskId=${taskRef.bizId} taskName=${taskRef.title} action=adFinish",
                )
            } else {
                Log.error(TAG, "芝麻树🌳[广告任务上报失败] ${taskRef.title} - $finishMsg")
                return TaskFlowActionResult.failure(
                    failureType = classifySesameTaskFailure(finishCode, finishJo),
                    code = finishCode,
                    message = finishMsg,
                    rpc = "AntSesameCreditRpcCall.taskFinish",
                    raw = finishRes,
                    detail = "module=$sesameCreditTaskBlacklistModule taskId=${taskRef.bizId} taskName=${taskRef.title} action=adFinish",
                )
            }
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.finishZhimaTreeAdTask", t)
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = t.message.orEmpty(),
                rpc = "AntSesameCredit.finishZhimaTreeAdTask",
                raw = t.toString(),
                detail = "module=$sesameCreditTaskBlacklistModule taskId=${taskRef.bizId} taskName=${taskRef.title} action=adFinish",
            )
        }
    }

    private fun buildZhimaTreeTaskRef(
        task: JSONObject,
        sourceName: String,
    ): ZhimaTreeTaskRef? {
        val taskBaseInfo = task.optJSONObject("taskBaseInfo") ?: return null
        val taskMaterial = task.optJSONObject("taskMaterial")
        val morphoDetail =
            task
                .optJSONObject("taskExtProps")
                ?.opt("TASK_MORPHO_DETAIL")
                ?.let { detail ->
                    when (detail) {
                        is JSONObject -> detail
                        is String -> parseJSONObjectOrNull(detail)
                        else -> null
                    }
                }
        val taskIdCandidates = collectZhimaTreeTaskIdCandidates(task, taskBaseInfo)
        val taskId = taskIdCandidates.mapNotNull { normalizeZhimaTreeTaskId(it) }.firstOrNull()
        val title = resolveZhimaTreeTaskTitle(task, taskBaseInfo, taskId ?: "未知任务")
        val taskContext = resolveZhimaTreeTaskContext(task, taskBaseInfo, taskMaterial, morphoDetail)
        val playInfo = resolveZhimaTreeTaskPlayInfo(task, taskBaseInfo, taskMaterial, morphoDetail)
        return ZhimaTreeTaskRef(
            title = title,
            prizeName = getPrizeName(task),
            status = task.optString("taskProcessStatus"),
            taskId = taskId,
            taskIdCandidates = taskIdCandidates,
            needSignUp = resolveZhimaTreeNeedSignUp(task),
            needManuallyReceiveAward = task.optBoolean("needManuallyReceiveAward", true),
            templateCode = taskBaseInfo.optString("templateCode"),
            appletType = taskBaseInfo.optString("appletType"),
            taskType = task.optString("taskType"),
            taskMaterialType =
                taskMaterial
                    ?.optString("taskType")
                    .orEmpty()
                    .ifBlank { morphoDetail?.optString("taskType").orEmpty() },
            taskChannel = resolveZhimaTreeTaskChannel(task, taskBaseInfo, taskMaterial, morphoDetail),
            chInfo = taskContext.chInfo,
            refer = taskContext.refer,
            playInfo = playInfo,
            appId = taskContext.appId,
            sourceName = sourceName,
        )
    }

    private fun resolveZhimaTreeTaskTitle(
        task: JSONObject,
        taskBaseInfo: JSONObject,
        defaultTitle: String,
    ): String {
        val taskMaterial = task.optJSONObject("taskMaterial")
        val morphoDetail =
            task
                .optJSONObject("taskExtProps")
                ?.opt("TASK_MORPHO_DETAIL")
                ?.let { detail ->
                    when (detail) {
                        is JSONObject -> detail
                        is String -> parseJSONObjectOrNull(detail)
                        else -> null
                    }
                }
        return taskMaterial
            ?.optString("title")
            .orEmpty()
            .ifBlank { morphoDetail?.optString("title").orEmpty() }
            .ifBlank { taskBaseInfo.optString("appletName") }
            .ifBlank { taskBaseInfo.optString("title") }
            .ifBlank { defaultTitle }
    }

    private fun resolveZhimaTreeNeedSignUp(task: JSONObject): Boolean {
        if (task.optBoolean("needSignUp", false)) {
            return true
        }
        val taskExtProps = task.optJSONObject("taskExtProps") ?: return false
        return taskExtProps.optBoolean("needSignUp", false) ||
            taskExtProps.optString("needSignUp").equals("true", ignoreCase = true)
    }

    private fun resolveZhimaTreeTaskChannel(
        task: JSONObject,
        taskBaseInfo: JSONObject,
        taskMaterial: JSONObject?,
        morphoDetail: JSONObject?,
    ): String =
        task
            .optString("taskChannel")
            .trim()
            .ifBlank { taskBaseInfo.optString("taskChannel").trim() }
            .ifBlank { taskMaterial?.optString("taskChannel").orEmpty().trim() }
            .ifBlank { morphoDetail?.optString("taskChannel").orEmpty().trim() }

    private fun resolveZhimaTreeTaskContext(
        task: JSONObject,
        taskBaseInfo: JSONObject,
        taskMaterial: JSONObject?,
        morphoDetail: JSONObject?,
    ): ZhimaTreeTaskContext {
        val urlCandidates = collectZhimaTreeTaskUrlCandidates(task, taskBaseInfo, taskMaterial, morphoDetail)
        val chInfo = resolveZhimaTreeTaskChInfo(task, taskBaseInfo, taskMaterial, morphoDetail, urlCandidates)
        return ZhimaTreeTaskContext(
            appId = resolveZhimaTreeTaskAppId(task, taskBaseInfo, taskMaterial, morphoDetail, chInfo, urlCandidates),
            chInfo = chInfo,
            refer = resolveZhimaTreeTaskRefer(task, taskBaseInfo, taskMaterial, morphoDetail, chInfo),
            urlCandidates = urlCandidates,
        )
    }

    private fun resolveZhimaTreeTaskContext(task: JSONObject): ZhimaTreeTaskContext {
        val taskBaseInfo = task.optJSONObject("taskBaseInfo") ?: JSONObject()
        val taskMaterial = task.optJSONObject("taskMaterial")
        val morphoDetail =
            task
                .optJSONObject("taskExtProps")
                ?.opt("TASK_MORPHO_DETAIL")
                ?.let { detail ->
                    when (detail) {
                        is JSONObject -> detail
                        is String -> parseJSONObjectOrNull(detail)
                        else -> null
                    }
                }
        return resolveZhimaTreeTaskContext(task, taskBaseInfo, taskMaterial, morphoDetail)
    }

    private fun collectZhimaTreeTaskUrlCandidates(
        task: JSONObject,
        taskBaseInfo: JSONObject,
        taskMaterial: JSONObject?,
        morphoDetail: JSONObject?,
    ): List<String> {
        val urlCandidates = linkedSetOf<String>()
        val targetUrl = task.optString("targetUrl").ifBlank { taskMaterial?.optString("targetUrl").orEmpty() }
        val schema = task.optString("schema").ifBlank { taskMaterial?.optString("schema").orEmpty() }
        sequenceOf(
            taskMaterial?.optString("jumpUrl").orEmpty(),
            morphoDetail?.optString("jumpUrl").orEmpty(),
            task.optString("actionUrl"),
            targetUrl,
            schema,
            taskBaseInfo.optString("appletSchema"),
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach(urlCandidates::add)
        return urlCandidates.toList()
    }

    private fun resolveZhimaTreeTaskChInfo(
        task: JSONObject,
        taskBaseInfo: JSONObject,
        taskMaterial: JSONObject?,
        morphoDetail: JSONObject?,
        urlCandidates: List<String>,
    ): String {
        val explicitChInfo =
            sequenceOf(
                task.optString("chInfo"),
                taskBaseInfo.optString("chInfo"),
                taskMaterial?.optString("chInfo").orEmpty(),
                morphoDetail?.optString("chInfo").orEmpty(),
                task.optJSONObject("taskParticipateExtInfo")?.optString("chInfo").orEmpty(),
            ).map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        if (explicitChInfo.isNotBlank()) {
            return explicitChInfo
        }
        val derivedChInfo =
            urlCandidates
                .asSequence()
                .mapNotNull { extractQueryParam(it, "chInfo") }
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && (it.startsWith("ch_url-") || it.startsWith("ch_appid-")) }
                .orEmpty()
        return derivedChInfo.ifBlank { AntSesameCreditRpcCall.ZHIMATREE_CH_INFO }
    }

    private fun resolveZhimaTreeTaskAppId(
        task: JSONObject,
        taskBaseInfo: JSONObject,
        taskMaterial: JSONObject?,
        morphoDetail: JSONObject?,
        chInfo: String,
        urlCandidates: List<String>,
    ): String =
        sequenceOf(
            taskBaseInfo.optString("appId"),
            task.optString("appId"),
            taskMaterial?.optString("appId").orEmpty(),
            morphoDetail?.optString("appId").orEmpty(),
            extractSesameAppId(chInfo),
        ).map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .ifBlank {
                urlCandidates
                    .asSequence()
                    .map(::extractSesameAppId)
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }

    private fun resolveZhimaTreeTaskRefer(
        task: JSONObject,
        taskBaseInfo: JSONObject,
        taskMaterial: JSONObject?,
        morphoDetail: JSONObject?,
        chInfo: String,
    ): String =
        sequenceOf(
            task.optString("refer"),
            taskBaseInfo.optString("refer"),
            taskMaterial?.optString("refer").orEmpty(),
            morphoDetail?.optString("refer").orEmpty(),
            task.optJSONObject("taskParticipateExtInfo")?.optString("refer").orEmpty(),
        ).map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: buildZhimaTreeReferFromChInfo(chInfo)

    private fun buildZhimaTreeReferFromChInfo(chInfo: String): String {
        if (chInfo.isBlank() ||
            chInfo.contains("://") ||
            chInfo.contains("caprMode=") ||
            chInfo.startsWith("alipays://")
        ) {
            return AntSesameCreditRpcCall.ZHIMATREE_REFER
        }
        return "https://render.alipay.com/p/yuyan/180020010001288004/zmTree.html?caprMode=sync&chInfo=$chInfo"
    }

    private fun resolveZhimaTreeTaskPlayInfo(
        task: JSONObject,
        taskBaseInfo: JSONObject,
        taskMaterial: JSONObject?,
        morphoDetail: JSONObject?,
    ): String =
        sequenceOf(
            task.optString("playInfo"),
            taskBaseInfo.optString("playInfo"),
            taskMaterial?.optString("playInfo").orEmpty(),
            morphoDetail?.optString("playInfo").orEmpty(),
            task.optJSONObject("taskParticipateExtInfo")?.optString("playInfo").orEmpty(),
        ).map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .ifBlank { AntSesameCreditRpcCall.ZHIMATREE_PLAY_INFO }

    private fun normalizeZhimaTreeTaskId(rawTaskId: String?): String? {
        val normalized = rawTaskId?.trim().orEmpty()
        if (normalized.isBlank() || normalized.equals("null", ignoreCase = true)) {
            return null
        }
        if (normalized == "{}" || normalized == "[]") {
            return null
        }
        if ((normalized.startsWith("{") && normalized.endsWith("}")) ||
            (normalized.startsWith("[") && normalized.endsWith("]"))
        ) {
            return null
        }
        return normalized
    }

    private fun collectZhimaTreeTaskIdCandidates(
        task: JSONObject,
        taskBaseInfo: JSONObject,
    ): List<String> =
        sequenceOf(
            taskBaseInfo.opt("appletId"),
            taskBaseInfo.opt("taskId"),
            taskBaseInfo.opt("appId"),
            task.opt("taskId"),
            task.opt("appletId"),
            task.opt("appId"),
        ).filterNotNull()
            .map { candidate ->
                when (candidate) {
                    JSONObject.NULL -> ""
                    is String -> candidate
                    else -> candidate.toString()
                }
            }.toList()

    private fun isSameZhimaTreeTask(
        originalTask: ZhimaTreeTaskRef,
        refreshedTask: ZhimaTreeTaskRef,
        requireSameTaskId: Boolean,
    ): Boolean {
        if (refreshedTask.title != originalTask.title) {
            return false
        }
        val prizeMatched =
            originalTask.prizeName.isEmpty() ||
                refreshedTask.prizeName.isEmpty() ||
                refreshedTask.prizeName == originalTask.prizeName
        if (!prizeMatched) {
            return false
        }
        if (!requireSameTaskId || originalTask.taskId == null) {
            return true
        }
        return refreshedTask.taskIdCandidates
            .mapNotNull(::normalizeZhimaTreeTaskId)
            .any { it == originalTask.taskId }
    }

    private fun buildZhimaTreeSuccessLog(
        action: String,
        taskRef: ZhimaTreeTaskRef,
    ): String =
        "芝麻树🌳[$action] " + taskRef.title + " #" +
            taskRef.prizeName.ifEmpty { "奖励已领取" }

    private fun classifyZhimaTreeActionFailure(response: JSONObject?): String {
        val code =
            response
                ?.optString("errorCode")
                .orEmpty()
                .ifBlank { response?.optString("resultCode").orEmpty() }
                .ifBlank { response?.optString("code").orEmpty() }
        return when (code) {
            "TASK_ALREADY_FINISHED",
            "TASK_HAS_FINISHED",
            "REPEAT_FINISH",
            "REPEAT_REWARD",
            -> {
                "duplicate_or_already_done"
            }

            "20020012" -> {
                "rpc_failed"
            }

            "10001011" -> {
                "business_limited"
            }

            "10000702" -> {
                "business_restricted"
            }

            else -> {
                when {
                    explicitZhimaTreeRetryable(response) == false -> "non_retryable"

                    else -> "rpc_failed"
                }
            }
        }
    }

    private fun classifyZhimaTreeTaskFailure(response: JSONObject?): TaskRpcFailureType {
        if (response == null) {
            return TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
        }
        val code =
            response
                .optString("errorCode")
                .ifBlank { response.optString("resultCode") }
                .ifBlank { response.optString("code") }
        return when (classifyZhimaTreeActionFailure(response)) {
            "duplicate_or_already_done" -> {
                TaskRpcFailureType.TERMINAL_DONE
            }

            "business_limited",
            "business_restricted",
            "risk_limited",
            -> {
                TaskRpcFailureType.BUSINESS_LIMIT
            }

            "parameter_invalid",
            "non_retryable",
            -> {
                TaskRpcFailureType.NON_RETRYABLE_INVALID
            }

            else -> {
                when {
                    code == "20020012" && explicitZhimaTreeRetryable(response) == false -> {
                        TaskRpcFailureType.NON_RETRYABLE_INVALID
                    }

                    code == "400000040" -> {
                        TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE
                    }

                    code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "SYSTEM_BUSY", "NETWORK_ERROR") ||
                        explicitZhimaTreeRetryable(response) == true -> {
                        TaskRpcFailureType.RETRYABLE_RPC
                    }

                    else -> {
                        TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
                    }
                }
            }
        }
    }

    private fun explicitZhimaTreeRetryable(response: JSONObject?): Boolean? {
        val payload = response ?: return null
        payload.optJSONObject("resData")?.let { nested ->
            return explicitZhimaTreeRetryable(nested)
        }
        for (key in listOf("retryable", "retriable", "canRetry", "needRetry")) {
            if (payload.has(key) && !payload.isNull(key)) {
                return payload.optBoolean(key, false)
            }
        }
        return null
    }

    private fun extractZhimaTreeActionFailureMessage(response: JSONObject): String =
        response
            .optString("errorMsg")
            .ifBlank { response.optString("errorMessage") }
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("resultView") }
            .ifBlank { response.optString("desc") }
            .ifBlank { response.optString("memo") }

    /**
     * 获取任务奖励名称
     */
    private fun getPrizeName(task: JSONObject): String {
        var prizeName = ""
        try {
            var prizes = task.optJSONArray("validPrizeDetailDTO")
            if (prizes == null || prizes.length() == 0) {
                prizes = task.optJSONArray("prizeDetailDTOList")
            }

            if (prizes != null && prizes.length() > 0) {
                val prizeBase = prizes.getJSONObject(0).optJSONObject("prizeBaseInfoDTO")
                if (prizeBase != null) {
                    val rawName = prizeBase.optString("prizeName", "")

                    if (rawName.contains("能量")) {
                        val p = Pattern.compile("(森林)?能量(\\d+g?)")
                        val m = p.matcher(rawName)
                        if (m.find()) {
                            prizeName = m.group(0) ?: ""
                        } else {
                            prizeName = rawName
                        }
                    } else if (rawName.contains("净化值")) {
                        val p = Pattern.compile("(\\d+净化值|净化值\\d+)")
                        val m = p.matcher(rawName)
                        if (m.find()) {
                            prizeName = m.group(1) ?: ""
                        } else {
                            prizeName = rawName
                        }
                    } else {
                        prizeName = rawName
                    }
                }
            }

            // 如果没找到 PrizeDTO，尝试从 taskExtProps 解析
            if (prizeName.isEmpty()) {
                val taskExtProps = task.optJSONObject("taskExtProps")
                if (taskExtProps != null && taskExtProps.has("TASK_MORPHO_DETAIL")) {
                    val detail = JSONObject(taskExtProps.getString("TASK_MORPHO_DETAIL"))
                    val `val` = detail.optString("finishOneTaskGetPurificationValue", "")
                    if (!`val`.isEmpty() && "0" != `val`) {
                        prizeName = `val` + "净化值"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return prizeName
    }

    private fun doTaskActionResult(
        taskRef: ZhimaTreeTaskRef,
        stageCode: String?,
    ): ZhimaTreeActionResult {
        try {
            val safeTaskId =
                normalizeZhimaTreeTaskId(taskRef.taskId)
                    ?: return ZhimaTreeActionResult(false, null, null)
            val safeStageCode =
                stageCode?.takeIf { it.isNotBlank() }
                    ?: return ZhimaTreeActionResult(false, null, null)
            val carryRentContext =
                taskRef.sourceName == "rent.taskDetailList" &&
                    taskRef.taskChannel.equals("RENT", ignoreCase = true)
            val appletId = resolveZhimaTreeTaskAppletId(taskRef)
            val rawResponse =
                AntSesameCreditRpcCall.rentGreenTaskFinish(
                    taskId = safeTaskId,
                    stageCode = safeStageCode,
                    chInfo = taskRef.chInfo,
                    refer = taskRef.refer,
                    playInfo = taskRef.playInfo,
                    appletId = appletId,
                    userId = if (carryRentContext) UserMap.currentUid.orEmpty() else "",
                )
                    ?: return ZhimaTreeActionResult(false, null, null)
            val json = JSONObject(rawResponse)
            return ZhimaTreeActionResult(
                success = ResChecker.checkRes(TAG, json),
                response = json,
                rawResponse = rawResponse,
            )
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            return ZhimaTreeActionResult(false, null, null)
        }
    }

    /**
     * 净化逻辑
     */
    private suspend fun doPurification(): Unit =
        CoroutineUtils.run {
            try {
                val homeRes = AntSesameCreditRpcCall.zhimaTreeHomePage() ?: return@run

                val homeJson = JSONObject(homeRes)
                if (!ResChecker.checkRes(TAG, homeJson)) return@run

                val result = homeJson.optJSONObject("extInfo")?.optJSONObject("zhimaTreeHomePageQueryResult")
                if (result == null) return@run

                val score = result.optInt("purificationScore", result.optInt("currentCleanNum", 0))
                var treeCode = "ZHIMA_TREE"
                var clicks = score / 100
                val trees = result.optJSONArray("trees")
                if (trees != null && trees.length() > 0) {
                    val tree = trees.optJSONObject(0) ?: JSONObject()
                    treeCode = tree.optString("treeCode", "ZHIMA_TREE")
                    val topLevel = tree.optInt("topLevel", 0)
                    if (topLevel > 0 && tree.optInt("treeLevel", 0) >= topLevel) {
                        Log.sesame("芝麻树🌳[已达本轮最高等级，停止净化]")
                        return@run
                    }
                    if (tree.has("remainPurificationClickNum")) {
                        clicks = max(0, tree.optInt("remainPurificationClickNum", clicks))
                    }
                }

                if (clicks <= 0) {
                    Log.sesame("芝麻树🌳[无需净化] 净化值不足（当前: " + score + "g，可点击: " + clicks + "次）")
                    return@run
                }

                val clearAreas = extractZhimaTreeClearAreas(result)
                if (clearAreas.isEmpty()) {
                    Log.sesame("芝麻树🌳[净化区域缺失] 服务端返回可点击 $clicks 次，但未返回clearArea，保留后续重试机会")
                    return@run
                }

                val targetAreas = clearAreas.take(clicks)
                Log.sesame("芝麻树🌳[开始净化] 可点击 $clicks 次，待清理 ${targetAreas.size} 处")

                for ((index, area) in targetAreas.withIndex()) {
                    val res = AntSesameCreditRpcCall.zhimaTreeCleanAndPush(treeCode, area.clearArea) ?: break

                    val json = JSONObject(res)
                    if (!ResChecker.checkRes(TAG, json)) break

                    val ext = json.optJSONObject("extInfo") ?: continue
                    val cleanResult = ext.optJSONObject("zhimaTreeCleanAndPushResult")

                    var newScore = cleanResult?.optInt("purificationScore", -1) ?: -1
                    if (newScore == -1) {
                        newScore = ext.optInt("purificationScore", score - (index + 1) * 100)
                    }

                    val currentTreeInfo = cleanResult?.optJSONObject("currentTreeInfo")
                    val growth = currentTreeInfo?.optInt("scoreSummary", -1) ?: -1
                    val remainClicks = currentTreeInfo?.optInt("remainPurificationClickNum", -1) ?: -1

                    var log = "芝麻树🌳[净化]第" + (index + 1) + "次#" + area.clearArea + " | 剩:" + newScore + "g"
                    if (growth != -1) log += "|成长:$growth"
                    if (remainClicks != -1) log += "|剩余次数:$remainClicks"
                    Log.sesame("$log ✅")

                    val topLevel = currentTreeInfo?.optInt("topLevel", 0) ?: 0
                    if (remainClicks == 0 ||
                        (topLevel > 0 && (currentTreeInfo?.optInt("treeLevel", 0) ?: 0) >= topLevel)
                    ) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, e)
            }
        }

    private fun extractZhimaTreeClearAreas(homeResult: JSONObject): List<ZhimaTreeClearArea> {
        val garbageMap = homeResult.optJSONObject("garbageMap") ?: return emptyList()
        val areas = mutableListOf<ZhimaTreeClearArea>()
        val seenAreas = linkedSetOf<String>()
        val typeKeys = garbageMap.keys()
        while (typeKeys.hasNext()) {
            val typeList = garbageMap.optJSONArray(typeKeys.next()) ?: continue
            for (typeIndex in 0 until typeList.length()) {
                val group = typeList.optJSONObject(typeIndex) ?: continue
                val groupArea = group.optString("subType")
                val groupSort = group.optInt("subShowSort", Int.MAX_VALUE)
                val garbageSubList = group.optJSONArray("garbageSubList")
                if (garbageSubList == null || garbageSubList.length() == 0) {
                    addZhimaTreeClearArea(areas, seenAreas, groupArea, groupSort)
                    continue
                }
                for (subIndex in 0 until garbageSubList.length()) {
                    val garbage = garbageSubList.optJSONObject(subIndex) ?: continue
                    val clearArea = garbage.optString("trashSubType").ifBlank { groupArea }
                    val sort =
                        garbage.optInt(
                            "trashClearSort",
                            garbage.optInt("trashSubShowSort", groupSort),
                        )
                    addZhimaTreeClearArea(areas, seenAreas, clearArea, sort)
                }
            }
        }
        return areas.sortedWith(compareBy<ZhimaTreeClearArea> { it.sort }.thenBy { it.clearArea })
    }

    private fun addZhimaTreeClearArea(
        areas: MutableList<ZhimaTreeClearArea>,
        seenAreas: MutableSet<String>,
        clearArea: String,
        sort: Int,
    ) {
        if (clearArea.isBlank() || !seenAreas.add(clearArea)) {
            return
        }
        areas.add(ZhimaTreeClearArea(clearArea, sort))
    }

    companion object {
        const val PERSISTENT_CONFIRMATION_KIND = "sesame_confirmation"
        private val TAG: String = AntSesameCredit::class.java.simpleName
        private val RENT_PUSH_MODEL_SNAPSHOT_SOURCES =
            setOf(
                "daily.waitCompleteTaskVOS",
                "daily.waitJoinTaskVOS",
                "toCompleteVOS",
            )

        /**
         * 查询 + 自动领取可领取球（精简一行输出领取信息）
         */
        @SuppressLint("DefaultLocale")
        fun queryAndCollect() {
            try {
                var collectedRounds = 0
                var emptyRetryBeforeCollect = 0
                for (attempt in 0..2) {
                    val queryResp = AntSesameCreditRpcCall.Zmxy.queryScoreProgress()
                    if (queryResp.isEmpty()) {
                        return
                    }

                    val json = JSONObject(queryResp)
                    if (!ResChecker.checkRes(TAG, json)) {
                        if (attempt == 0) {
                            Log.sesame("攒芝麻分🎁[查询进度球失败，准备重试]")
                            continue
                        }
                        return
                    }

                    val newProgressBallIds = copyNewProgressBallIds(json)
                    if (newProgressBallIds.length() == 0) {
                        if (collectedRounds == 0 && emptyRetryBeforeCollect == 0) {
                            emptyRetryBeforeCollect++
                            continue
                        }
                        if (collectedRounds == 0) {
                            Log.sesame("攒芝麻分🎁[进度锦囊暂无可领取进度球]")
                        }
                        return
                    }

                    val collectResp = AntSesameCreditRpcCall.Zmxy.collectProgressBall(newProgressBallIds) ?: return
                    val collectJson = JSONObject(collectResp)
                    if (isSesameProgressBallEmpty(collectJson)) {
                        Log.sesame("攒芝麻分🎁[进度锦囊暂无可领取进度球]")
                        return
                    }
                    if (!ResChecker.checkRes(TAG, collectJson)) {
                        if (attempt == 0) {
                            Log.sesame("攒芝麻分🎁[领取进度球失败，准备重试]")
                            continue
                        }
                        Log.error(TAG, "攒芝麻分🎁[领取失败]#$collectResp")
                        return
                    }

                    Log.sesame(
                        String.format(
                            "领取完成 → 本次加速进度: %d, 当前加速倍率: %.2f",
                            collectJson.optInt("collectedAccelerateProgress", -1),
                            collectJson.optDouble("currentAccelerateValue", -1.0),
                        ),
                    )
                    collectedRounds++
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "queryAndCollect err", e)
            }
        }

        private fun copyNewProgressBallIds(json: JSONObject): JSONArray {
            val ids = JSONArray()
            val source = json.optJSONArray("newProgressBallIds") ?: return ids
            for (index in 0 until source.length()) {
                val id = source.optString(index)
                if (id.isNotBlank()) {
                    ids.put(id)
                }
            }
            return ids
        }

        /**
         * 检查是否满足运行芝麻信用任务的条件
         * @return bool
         */
        internal fun checkSesameCanRun(): Boolean {
            try {
                val s = AntSesameCreditRpcCall.queryHomeV8()
                val jo = JSONObject(s)
                if (ResChecker.checkRes(TAG, jo)) {
                    val entrance = jo.optJSONObject("entrance")
                    if (entrance != null && !entrance.optBoolean("openApp", true)) {
                        Log.sesame("芝麻信用💳[未开通，本轮跳过]")
                        return false
                    }
                    return true
                }
                Log.sesame("芝麻信用💳[V8首页探活失败，回退V7]")
            } catch (t: Throwable) {
                Log.sesame("芝麻信用💳[V8首页探活异常，回退V7]#${t.message}")
            }

            try {
                val s = AntSesameCreditRpcCall.queryHome()
                val jo = JSONObject(s)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.error("$TAG.checkSesameCanRun.queryHomeV7", "芝麻信用💳[首页响应失败]#$s")
                    return false
                }
                val entrance = jo.optJSONObject("entrance")
                if (entrance != null && !entrance.optBoolean("openApp", true)) {
                    Log.sesame("芝麻信用💳[未开通，本轮跳过]")
                    return false
                }
                return true
            } catch (t: Throwable) {
                Log.printStackTrace("$TAG.checkSesameCanRun", t)
                return false
            }
        }

        private fun shouldSkipShareAssistSesameTask(task: JSONObject): Boolean =
            task.optBoolean("shareAssist", false)

        private fun isTransientSesameTaskError(errorCode: String): Boolean =
            errorCode in
                setOf(
                    "OP_REPEAT_CHECK",
                    "SYSTEM_BUSY",
                    "NETWORK_ERROR",
                    "COLLECT_CREDIT_FEEDBACK_FAILED",
                )

        private fun isSesameProgressBallEmpty(response: JSONObject): Boolean {
            val resultCode = response.optString("resultCode", response.optString("errorCode", ""))
            return resultCode == "INIT_SCORE_BALL_EMPTY" ||
                resultCode == "无可领取的信用球"
        }

        private fun isSesameAlchemyCapReached(response: JSONObject): Boolean {
            val resultCode = response.optString("resultCode", response.optString("errorCode", ""))
            return resultCode == "CAP_REACHED"
        }

        private fun formatSesameAlchemyReward(task: JSONObject): String {
            val rewardAmount = task.optInt("rewardAmount", 0)
            return when (task.optString("rewardType", "ZML")) {
                "LJCS" -> {
                    rewardAmount.toString() + "次炼金次数"
                }

                "ZML" -> {
                    rewardAmount.toString() + "粒"
                }

                else -> {
                    val rewardType = task.optString("rewardType")
                    if (rewardType.isEmpty()) {
                        rewardAmount.toString() + "粒"
                    } else {
                        rewardAmount.toString() + rewardType
                    }
                }
            }
        }

        private fun buildSesameRpcMessage(
            response: JSONObject,
            rawResponse: String,
        ): String =
            sequenceOf(
                response.optString("resultView"),
                response.optString("resultDesc"),
                response.optString("errMsg"),
                response.optString("errorMessage"),
                response.optString("memo"),
                rawResponse,
            ).firstOrNull { it.isNotBlank() }.orEmpty()

        private fun parseJSONObjectOrNull(raw: String?): JSONObject? {
            val value = raw?.trim().orEmpty()
            if (value.isBlank() || !value.startsWith("{")) {
                return null
            }
            return try {
                JSONObject(value)
            } catch (_: Throwable) {
                null
            }
        }

        private fun decodeUrlComponentRepeated(
            value: String?,
            maxRounds: Int = 3,
        ): String {
            var current = value?.trim().orEmpty()
            if (current.isBlank()) {
                return ""
            }
            repeat(maxRounds) {
                val decoded =
                    try {
                        URLDecoder.decode(current, "UTF-8")
                    } catch (_: Throwable) {
                        return current
                    }
                if (decoded == current) {
                    return current
                }
                current = decoded
            }
            return current
        }

        private fun extractQueryParam(
            rawUrl: String?,
            name: String,
        ): String? {
            val url = rawUrl?.takeIf { it.isNotBlank() } ?: return null
            val marker = "$name="
            for (candidate in listOf(url, decodeUrlComponentRepeated(url))) {
                val startIndex = candidate.indexOf(marker)
                if (startIndex < 0) {
                    continue
                }
                val valueStart = startIndex + marker.length
                val valueEnd = candidate.indexOf('&', valueStart).takeIf { it >= 0 } ?: candidate.length
                val rawValue = candidate.substring(valueStart, valueEnd)
                val decodedValue = decodeUrlComponentRepeated(rawValue)
                if (decodedValue.isNotBlank()) {
                    return decodedValue
                }
            }
            return null
        }

        private fun buildAdTaskSpaceCodeFromRenderConfigKey(rawRenderConfigKey: String?): String? {
            val decoded = decodeUrlComponentRepeated(rawRenderConfigKey)
            if (decoded.isBlank()) {
                return null
            }
            return decoded.takeIf { it.contains("adPosId#") || it.contains("_duration=") }
        }

        private fun extractAdTaskSpaceCodeFromCdpQueryParams(rawUrl: String?): String? {
            val rawParams =
                extractQueryParam(rawUrl, "cdpQueryParams")
                    ?: extractQueryParam(rawUrl, "useCdpQueryParams")
                    ?: return null
            val params = parseJSONObjectOrNull(rawParams) ?: return null
            return buildAdTaskSpaceCodeFromRenderConfigKey(params.optString("spaceCode"))
                ?: buildAdTaskSpaceCodeFromRenderConfigKey(params.optString("renderConfigKey"))
        }

        private fun extractAdRenderConfigValue(
            rawRenderConfigKey: String?,
            key: String,
        ): String {
            val renderConfigKey =
                buildAdTaskSpaceCodeFromRenderConfigKey(rawRenderConfigKey)
                    ?: decodeUrlComponentRepeated(rawRenderConfigKey)
            if (renderConfigKey.isBlank()) {
                return ""
            }
            val prefix = "$key#"
            return renderConfigKey
                .split("##")
                .firstOrNull { it.startsWith(prefix) }
                ?.substring(prefix.length)
                .orEmpty()
        }

        private fun buildAdTaskSpaceCodeFromLogExtMap(
            logExtMap: JSONObject?,
            fallbackSpaceCode: String? = null,
            fallbackRewardNum: String? = null,
        ): String? {
            if (logExtMap == null) {
                return null
            }
            val adPositionId = logExtMap.optString("adPositionId")
            val taskType = logExtMap.optString("taskType")
            val mediaScene = logExtMap.optString("mediaScene").ifBlank { logExtMap.optString("ch") }
            val rewardNum = logExtMap.optString("rewardNum").ifBlank { fallbackRewardNum.orEmpty() }
            val spaceCode = logExtMap.optString("spaceCode").ifBlank { fallbackSpaceCode.orEmpty() }
            if (adPositionId.isBlank() || taskType.isBlank() || mediaScene.isBlank() ||
                rewardNum.isBlank() || spaceCode.isBlank()
            ) {
                return null
            }
            val sceneCode = logExtMap.optString("sceneCode")
            val expCode = logExtMap.optString("expCode").ifBlank { "null" }
            return "adPosId#$adPositionId##taskType#$taskType##sceneCode#$sceneCode" +
                "##mediaScene#$mediaScene##rewardNum#$rewardNum##spaceCode#$spaceCode##expCode#$expCode"
        }

        private fun resolveAdTaskSpaceCode(
            logExtMap: JSONObject?,
            actionUrl: String?,
            fallbackSpaceCode: String? = null,
            fallbackRewardNum: String? = null,
        ): String? {
            val candidates =
                listOf(
                    logExtMap?.optString("renderConfigKey"),
                    extractQueryParam(actionUrl, "renderConfigKey"),
                    extractAdTaskSpaceCodeFromCdpQueryParams(actionUrl),
                    logExtMap?.optString("spaceCode"),
                    fallbackSpaceCode,
                )
            for (candidate in candidates) {
                buildAdTaskSpaceCodeFromRenderConfigKey(candidate)?.let {
                    return it
                }
            }
            return buildAdTaskSpaceCodeFromLogExtMap(logExtMap, fallbackSpaceCode, fallbackRewardNum)
        }

        private fun resolveSesameAdTaskSpaceCode(
            task: JSONObject,
            logExtMap: JSONObject,
        ): String? {
            if ("LJCS" == task.optString("rewardType")) {
                val ch = logExtMap.optString("ch")
                val adPositionId = logExtMap.optString("adPositionId")
                if (ch.isNotBlank() && adPositionId.isNotBlank()) {
                    return "${ch}_${adPositionId}_duration=5"
                }
            }
            resolveAdTaskSpaceCode(
                logExtMap,
                task.optString("actionUrl"),
                fallbackRewardNum = task.optString("rewardAmount"),
            )?.let {
                return it
            }
            return null
        }

        private fun isAdTaskFinishSuccess(response: JSONObject): Boolean =
            ResChecker.checkRes(TAG, response) ||
                "0" == response.optString("errCode") ||
                "SUCCESS".equals(response.optString("resultCode"), ignoreCase = true) ||
                "SUCCESS".equals(response.optString("errorCode"), ignoreCase = true)

        private fun isAdTaskRetryable(response: JSONObject): Boolean {
            val code =
                response.optString(
                    "errorCode",
                    response.optString("resultCode", response.optString("errCode", "")),
                )
            return explicitSesameTaskRetryable(response) == true || isTransientSesameTaskError(code)
        }

        private fun confirmAlchemyAdTaskFinished(
            adTaskBizId: String,
            taskTitle: String,
            logPrefix: String,
        ): Boolean? {
            return try {
                val lastOperateRes = AntSesameCreditRpcCall.queryLastOperateTask("alchemy")
                val lastOperateJo = JSONObject(lastOperateRes)
                if (!ResChecker.checkRes(TAG, lastOperateJo)) {
                    Log.sesame("$logPrefix[炼金次数回查失败]#$taskTitle - $lastOperateRes")
                    return null
                }
                val lastTask =
                    lastOperateJo
                        .optJSONObject("data")
                        ?.optJSONObject("lastOperateTaskVO")
                val matched =
                    lastTask?.optBoolean("finishFlag", false) == true &&
                        "LJCS" == lastTask.optString("rewardType") &&
                        (adTaskBizId.isBlank() || adTaskBizId == lastTask.optString("adTaskBizId"))
                if (!matched) {
                    Log.sesame("$logPrefix[炼金次数回查未确认]#$taskTitle | adTaskBizId=$adTaskBizId | last=$lastTask")
                    return false
                }
                true
            } catch (t: Throwable) {
                Log.printStackTrace("$TAG.confirmAlchemyAdTaskFinished", t)
                null
            }
        }

        private fun isSesameAdTaskAlreadyFinished(response: JSONObject): Boolean {
            val resultCode =
                response.optString(
                    "resultCode",
                    response.optString("errorCode", response.optString("errCode", "")),
                )
            return resultCode in
                setOf(
                    "TASK_ALREADY_FINISHED",
                    "TASK_HAS_FINISHED",
                    "REPEAT_FINISH",
                    "REPEAT_REWARD",
                )
        }

        private fun autoBlacklistSesameTaskIfNeeded(
            moduleName: String,
            taskId: String,
            taskTitle: String,
            errorCode: String,
            resultView: String = "",
            rawResponse: String = "",
            action: String = "task",
        ) {
            val normalizedTaskId = taskId.trim()
            if (normalizedTaskId.isBlank() ||
                (errorCode.isBlank() && resultView.isBlank() && rawResponse.isBlank())
            ) {
                if (normalizedTaskId.isBlank()) {
                    Log.error(TAG, "$moduleName[$taskTitle] 缺少稳定 taskId，保留 UNKNOWN_NEEDS_REVIEW，不写自动跳过列表")
                }
                return
            }
            val code = errorCode.ifBlank { "UNKNOWN" }
            val message = resultView.ifBlank { "<empty>" }
            val raw = rawResponse.ifBlank { resultView }
            val rpc =
                when (action) {
                    "join" -> "AntSesameCreditRpcCall.joinSesameTask"
                    "feedback" -> "AntSesameCreditRpcCall.feedBackSesameTask"
                    "finish" -> "AntSesameCreditRpcCall.finishSesameTask"
                    "adFinish" -> "AntSesameCreditRpcCall.taskFinish"
                    else -> "AntSesameCreditRpcCall.$action"
                }
            val detail =
                "module=$moduleName taskId=$normalizedTaskId taskName=$taskTitle action=$action rpc=$rpc " +
                    "code=$code msg=$message raw=$raw"
            when (classifySesameTaskFailure(errorCode, parseJSONObjectOrNull(raw))) {
                TaskRpcFailureType.TERMINAL_DONE -> {
                    Log.sesame("$moduleName[$taskTitle] classification=TERMINAL_DONE decision=MARK_HANDLED $detail")
                }

                TaskRpcFailureType.BUSINESS_LIMIT -> {
                    Log.sesame("$moduleName[$taskTitle] classification=BUSINESS_LIMIT decision=STOP_TODAY_OR_CURRENT_CHAIN $detail")
                }

                TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE -> {
                    blacklistClassifiedSesameTask(moduleName, normalizedTaskId, errorCode)
                    Log.error(
                        TAG,
                        "$moduleName[$taskTitle] classification=UNSUPPORTED_NO_CLOSURE decision=BLACKLIST reason=未抓到稳定完成RPC $detail",
                    )
                }

                TaskRpcFailureType.NON_RETRYABLE_INVALID -> {
                    blacklistClassifiedSesameTask(moduleName, normalizedTaskId, errorCode)
                    Log.error(TAG, "$moduleName[$taskTitle] classification=NON_RETRYABLE_INVALID decision=BLACKLIST $detail")
                }

                TaskRpcFailureType.RETRYABLE_RPC -> {
                    Log.error(TAG, "$moduleName[$taskTitle] classification=RETRYABLE_RPC decision=RETRY_LATER $detail")
                }

                TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW -> {
                    Log.error(TAG, "$moduleName[$taskTitle] classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY $detail")
                }
            }
        }

        private fun blacklistClassifiedSesameTask(
            moduleName: String,
            taskId: String,
            errorCode: String,
        ) {
            if (taskId.isBlank()) {
                return
            }
            if (errorCode.isNotBlank()) {
                autoAddToBlacklist(moduleName, taskId, errorCode = errorCode)
            }
            TaskBlacklist.addToBlacklist(moduleName, taskId)
        }

        private fun classifySesameTaskFailure(
            errorCode: String,
            response: JSONObject? = null,
        ): TaskRpcFailureType {
            val code = errorCode.trim()
            return when {
                code in setOf("TASK_ALREADY_FINISHED", "TASK_HAS_FINISHED", "REPEAT_FINISH", "REPEAT_REWARD") -> {
                    TaskRpcFailureType.TERMINAL_DONE
                }

                code in setOf(
                    "CAMP_TRIGGER_ERROR",
                    "OP_REPEAT_CHECK",
                    "PROMISE_TODAY_FINISH_TIMES_LIMIT",
                    "PROMISE_HAS_PROCESSING_TEMPLATE",
                    "104",
                ) -> {
                    TaskRpcFailureType.BUSINESS_LIMIT
                }

                code == "400000040" -> {
                    TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE
                }

                code in setOf("TASK_ID_INVALID", "PROMISE_TEMPLATE_NOT_EXIST", "ILLEGAL_ARGUMENT") ||
                    (code == "20020012" && explicitSesameTaskRetryable(response) == false) ||
                    (isExplicitSesameTaskFailure(response) &&
                        explicitSesameTaskRetryable(response) == false) -> {
                    TaskRpcFailureType.NON_RETRYABLE_INVALID
                }

                code in
                    setOf(
                        "3000",
                        "REMOTE_INVOKE_EXCEPTION",
                        "SYSTEM_BUSY",
                        "NETWORK_ERROR",
                        "COLLECT_CREDIT_FEEDBACK_FAILED",
                    ) || explicitSesameTaskRetryable(response) == true -> {
                    TaskRpcFailureType.RETRYABLE_RPC
                }

                else -> {
                    TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
                }
            }
        }

        private fun explicitSesameTaskRetryable(response: JSONObject?): Boolean? {
            val payload = response ?: return null
            for (key in listOf("retryable", "retriable", "canRetry", "needRetry")) {
                if (payload.has(key) && !payload.isNull(key)) {
                    return payload.optBoolean(key, false)
                }
            }
            payload.optJSONObject("resData")?.let { nested ->
                return explicitSesameTaskRetryable(nested)
            }
            return null
        }

        private fun isExplicitSesameTaskFailure(response: JSONObject?): Boolean {
            val payload = response ?: return false
            if (payload.has("success") && !payload.isNull("success")) {
                return !payload.optBoolean("success", true)
            }
            if (payload.has("isSuccess") && !payload.isNull("isSuccess")) {
                return !payload.optBoolean("isSuccess", true)
            }
            return payload.optJSONObject("resData")?.let(::isExplicitSesameTaskFailure) ?: false
        }

        private fun isSesameProcessingTemplate(errorCode: String): Boolean =
            errorCode.trim() == "PROMISE_HAS_PROCESSING_TEMPLATE"

        private fun isSesameProcessingTemplateRefresh(result: TaskFlowActionResult): Boolean =
            result.detail.contains("processingTemplateRefresh=true")

        private fun joinSesameTask(
            taskTemplateId: String,
            sceneCode: String,
        ): Pair<String, JSONObject> {
            val joinRes = AntSesameCreditRpcCall.joinSesameTask(taskTemplateId, sceneCode)
            return joinRes to JSONObject(joinRes)
        }

        private fun reportSesameTaskFeedbackResult(
            task: JSONObject,
            taskTitle: String,
            logPrefix: String,
            moduleName: String,
            version: String,
            sceneCode: String,
            changeRewardType: String? = null,
        ): TaskFlowActionResult {
            val templateId = task.optString("templateId").trim()
            if (templateId.isBlank()) {
                Log.sesame("$logPrefix[任务回调缺少templateId]#$taskTitle")
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.NON_RETRYABLE_INVALID,
                    code = "TEMPLATE_ID_EMPTY",
                    message = "任务回调缺少templateId",
                    rpc = "AntSesameCreditRpcCall.feedBackSesameTask",
                    detail = "module=$moduleName taskId=$taskTitle taskName=$taskTitle action=feedback",
                )
            }

            val bizType = task.optString("bizType").trim()
            if (bizType.isBlank() || sceneCode.isBlank()) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "TASK_FEEDBACK_CONTRACT_EMPTY",
                    message = "任务回调缺少bizType或sceneCode",
                    rpc = "AntSesameCreditRpcCall.feedBackSesameTask",
                    detail = "module=$moduleName taskId=$templateId taskName=$taskTitle action=feedback",
                )
            }
            val feedbackRes = AntSesameCreditRpcCall.feedBackSesameTask(
                taskTemplateId = templateId,
                bizType = bizType,
                sceneCode = sceneCode,
                version = version,
                changeRewardType = changeRewardType,
            )
            val feedbackJo = parseJSONObjectOrNull(feedbackRes)
                ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "RESPONSE_PARSE_ERROR",
                    message = "任务回调响应无法解析",
                    rpc = "AntSesameCreditRpcCall.feedBackSesameTask",
                    raw = feedbackRes,
                    detail = "module=$moduleName taskId=$templateId taskName=$taskTitle action=feedback bizType=$bizType",
                )
            if (ResChecker.checkRes(TAG, feedbackJo)) {
                return TaskFlowActionResult(
                    success = true,
                    code = feedbackJo.optString("resultCode"),
                    rpc = "AntSesameCreditRpcCall.feedBackSesameTask",
                    raw = feedbackRes,
                    detail = "module=$moduleName taskId=$templateId taskName=$taskTitle action=feedback parameter=taskContract",
                )
            }

            val errorCode = feedbackJo.optString("errorCode", feedbackJo.optString("resultCode", ""))
            val resultView = buildSesameRpcMessage(feedbackJo, feedbackRes)
            Log.error(TAG, "$logPrefix[任务回调失败]#$taskTitle - $resultView")
            val failureType = classifySesameTaskFailure(errorCode, feedbackJo)
            return TaskFlowActionResult.failure(
                failureType = failureType,
                code = errorCode,
                message = resultView,
                rpc = "AntSesameCreditRpcCall.feedBackSesameTask",
                raw = feedbackRes,
                detail = "module=$moduleName taskId=$templateId taskName=$taskTitle action=feedback bizType=$bizType",
                stopCurrentRound = isSesameTaskFlowInterrupted(feedbackJo),
                continueCurrentRoundOnFailure = failureType == TaskRpcFailureType.RETRYABLE_RPC ||
                    failureType == TaskRpcFailureType.BUSINESS_LIMIT,
            )
        }

        private fun handleSesameAdTask(
            task: JSONObject,
            taskTitle: String,
            logPrefix: String,
            moduleName: String,
        ): Boolean {
            val result = handleSesameAdTaskResult(task, taskTitle, logPrefix, moduleName)
            if (!result.success && result.failureType != TaskRpcFailureType.TERMINAL_DONE) {
                autoBlacklistSesameTaskIfNeeded(
                    moduleName = moduleName,
                    taskId = task.optJSONObject("logExtMap")?.optString("bizId")
                        .orEmpty()
                        .ifBlank { task.optString("adTaskBizId") },
                    taskTitle = taskTitle,
                    errorCode = result.code,
                    resultView = result.message,
                    rawResponse = result.raw,
                    action = "adFinish",
                )
            }
            return result.success || result.failureType == TaskRpcFailureType.TERMINAL_DONE
        }

        private fun handleSesameAdTaskResult(
            task: JSONObject,
            taskTitle: String,
            logPrefix: String,
            moduleName: String,
        ): TaskFlowActionResult {
            val logExtMap = task.optJSONObject("logExtMap")
            if (logExtMap == null) {
                Log.sesame("$logPrefix[广告任务缺少logExtMap]#$taskTitle")
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "LOG_EXT_MAP_EMPTY",
                    message = "广告任务缺少logExtMap",
                    rpc = "AntSesameCreditRpcCall.taskFinish",
                    detail = "module=$moduleName taskId=$taskTitle taskName=$taskTitle action=adFinish",
                )
            }
            val bizId = logExtMap.optString("bizId")
            if (bizId.isEmpty()) {
                Log.sesame("$logPrefix[广告任务缺少bizId]#$taskTitle")
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "BIZ_ID_EMPTY",
                    message = "广告任务缺少bizId",
                    rpc = "AntSesameCreditRpcCall.taskFinish",
                    detail = "module=$moduleName taskId=$taskTitle taskName=$taskTitle action=adFinish",
                )
            }
            Log.sesame("$logPrefix[广告任务准备]#$taskTitle")
            val isAlchemyFreeCountTask = "LJCS" == task.optString("rewardType")
            val adTaskBizId = task.optString("adTaskBizId").ifEmpty { bizId }
            if (isAlchemyFreeCountTask) {
                val rewardRes = AntSesameCreditRpcCall.adRewardLjcs(adTaskBizId)
                val rewardJo = JSONObject(rewardRes)
                if (!ResChecker.checkRes(TAG, rewardJo)) {
                    val rewardMsg = buildSesameRpcMessage(rewardJo, rewardRes)
                    if (isSesameAdTaskAlreadyFinished(rewardJo)) {
                        Log.sesame("$logPrefix[炼金次数登记已完成，继续浏览上报]#$taskTitle - $rewardMsg")
                    } else if (isAdTaskRetryable(rewardJo)) {
                        Log.sesame("$logPrefix[炼金次数登记暂时不可用]#$taskTitle - $rewardMsg")
                        val rewardCode = rewardJo.optString("resultCode", rewardJo.optString("errorCode", ""))
                        return TaskFlowActionResult.failure(
                            failureType = TaskRpcFailureType.RETRYABLE_RPC,
                            code = rewardCode,
                            message = rewardMsg,
                            rpc = "AntSesameCreditRpcCall.adRewardLjcs",
                            raw = rewardRes,
                            detail = "module=$moduleName taskId=$bizId taskName=$taskTitle action=adRewardLjcs",
                        )
                    } else {
                        Log.error(TAG, "$logPrefix[炼金次数登记失败]#$taskTitle - $rewardMsg")
                        return TaskFlowActionResult.failure(
                            failureType =
                                classifySesameTaskFailure(
                                    rewardJo.optString("resultCode", rewardJo.optString("errorCode", "")),
                                    rewardJo,
                                ),
                            code = rewardJo.optString("resultCode", rewardJo.optString("errorCode", "")),
                            message = rewardMsg,
                            rpc = "AntSesameCreditRpcCall.adRewardLjcs",
                            raw = rewardRes,
                            detail = "module=$moduleName taskId=$bizId taskName=$taskTitle action=adRewardLjcs",
                        )
                    }
                }
            }
            val spaceCode = resolveSesameAdTaskSpaceCode(task, logExtMap)
            if (!spaceCode.isNullOrBlank()) {
                val layerRes = AntSesameCreditRpcCall.adTaskApplayerQuery(spaceCode)
                val layerResponse = JSONObject(layerRes)
                if (!ResChecker.checkRes(TAG, layerResponse) && "0" != layerResponse.optString("errCode")) {
                    val layerMsg = buildSesameRpcMessage(layerResponse, layerRes)
                    val layerCode =
                        layerResponse.optString(
                            "errorCode",
                            layerResponse.optString("resultCode", layerResponse.optString("errCode", "")),
                        )
                    if (isAdTaskRetryable(layerResponse)) {
                        Log.sesame("$logPrefix[广告浏览配置暂时不可用]#$taskTitle - $layerMsg")
                        return TaskFlowActionResult.failure(
                            failureType = TaskRpcFailureType.RETRYABLE_RPC,
                            code = layerCode,
                            message = layerMsg,
                            rpc = "AntSesameCreditRpcCall.adTaskApplayerQuery",
                            raw = layerRes,
                            detail = "module=$moduleName taskId=$bizId taskName=$taskTitle action=adLayer",
                        )
                    } else {
                        Log.error(TAG, "$logPrefix[广告浏览配置失败]#$taskTitle - code=$layerCode msg=$layerMsg")
                        return TaskFlowActionResult.failure(
                            failureType = classifySesameTaskFailure(layerCode, layerResponse),
                            code = layerCode,
                            message = layerMsg,
                            rpc = "AntSesameCreditRpcCall.adTaskApplayerQuery",
                            raw = layerRes,
                            detail = "module=$moduleName taskId=$bizId taskName=$taskTitle action=adLayer",
                        )
                    }
                }
            } else {
                Log.sesame("$logPrefix[广告浏览配置缺失，直接上报]#$taskTitle")
            }
            val adFinishRes = AntSesameCreditRpcCall.taskFinish(bizId, includeExtendInfo = true)
            val adFinishJo = JSONObject(adFinishRes)
            if (isAdTaskFinishSuccess(adFinishJo)) {
                if (isAlchemyFreeCountTask) {
                    confirmAlchemyAdTaskFinished(adTaskBizId, taskTitle, logPrefix)
                }
                Log.sesame("$logPrefix[广告任务完成: " + taskTitle + "]#获得" + formatSesameAlchemyReward(task))
                return TaskFlowActionResult.success()
            }
            val errorCode =
                adFinishJo.optString(
                    "errorCode",
                    adFinishJo.optString("resultCode", adFinishJo.optString("errCode", "")),
                )
            val resultView = buildSesameRpcMessage(adFinishJo, adFinishRes)
            if (isSesameAdTaskAlreadyFinished(adFinishJo)) {
                Log.sesame("$logPrefix[广告任务已完成，跳过重复上报]#$taskTitle - $resultView")
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.TERMINAL_DONE,
                    code = errorCode,
                    message = resultView,
                    rpc = "AntSesameCreditRpcCall.taskFinish",
                    raw = adFinishRes,
                    detail = "module=$moduleName taskId=$bizId taskName=$taskTitle action=adFinish",
                )
            }
            val failureType = classifySesameTaskFailure(errorCode, adFinishJo)
            return TaskFlowActionResult.failure(
                failureType = failureType,
                code = errorCode,
                message = resultView,
                rpc = "AntSesameCreditRpcCall.taskFinish",
                raw = adFinishRes,
                detail = "module=$moduleName taskId=$bizId taskName=$taskTitle action=adFinish",
            )
        }

        private fun isSesameTaskFlowInterrupted(response: JSONObject? = null): Boolean {
            if (ApplicationHookConstants.isOffline()) {
                return true
            }
            if (response == null) {
                return false
            }
            val resultCode =
                response.optString("resultCode").ifEmpty {
                    response.optString("errorCode").ifEmpty {
                        response.optString("code")
                    }
                }
            return resultCode == "I07"
        }
    }

    private fun sesameFloatingBallContract(task: JSONObject): SesameFloatingBallContract? {
        val parameters = sesameActionUrlParameters(task)
        if (!isSesameP2eFloatingBallTask(task) && parameters["taskType"] != "durationTask") return null
        val objects = sesameGameContractObjects(task)
        val sceneId = firstNonBlank(objects, parameters, "sceneId")
        val taskId = firstNonBlank(objects, parameters, "taskId")
        val moduleId = firstNonBlank(objects, parameters, "moduleId")
        val guideType = firstNonBlank(objects, parameters, "guideType")
        val source = firstNonBlank(objects, parameters, "source", "chInfo")
        if (sceneId.isBlank() || taskId.isBlank() || moduleId.isBlank() || guideType.isBlank() || source.isBlank()) {
            return null
        }
        return buildSesameFloatingBallContract(
            sceneId = sceneId,
            taskId = taskId,
            source = source,
            moduleId = moduleId,
            guideType = guideType,
            gameAppId = firstNonBlank(objects, parameters, "gameAppId", "appId").ifBlank { null },
            gameId = firstNonBlank(objects, parameters, "gameId").ifBlank { null },
            gameModuleId = firstNonBlank(objects, parameters, "gameModuleId").ifBlank { null },
            oriChInfo = firstNonBlank(objects, parameters, "oriChInfo", "chInfo").ifBlank { null },
            trafficDriverId = firstNonBlank(objects, parameters, "trafficDriverId").ifBlank { null },
            floatingBallTypeList = parseFloatingBallTypeList(objects, parameters),
            componentChannel = firstNonBlank(objects, parameters, "componentChannel", "p2eComponentChannel", "channel").ifBlank { null },
            componentScene = firstNonBlank(objects, parameters, "componentScene", "p2eComponentScene", "gameScene").ifBlank { null },
            gameVersion = firstNonBlank(objects, parameters, "gameVersion").ifBlank { null },
            pageRequests = sesameP2ePageRequests(objects),
        )
    }

    private fun firstNonBlank(
        objects: List<JSONObject>,
        parameters: Map<String, String>,
        vararg keys: String,
    ): String {
        return keys.asSequence()
            .flatMap { key -> objects.asSequence().map { it.optString(key).trim() } }
            .firstOrNull { it.isNotBlank() }
            ?: keys.asSequence()
                .map { key -> parameters[key].orEmpty().trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
    }

    private fun parseFloatingBallTypeList(
        objects: List<JSONObject>,
        parameters: Map<String, String>,
    ): List<String> {
        val direct = objects.asSequence().mapNotNull { it.optJSONArray("floatingBallTypeList") }.firstOrNull()
        if (direct != null) {
            val values = (0 until direct.length()).mapNotNull { direct.optString(it).trim().takeIf { it.isNotBlank() } }
            if (values.isNotEmpty()) return values
        }
        val raw = objects.asSequence()
            .map { it.optString("floatingBallTypeList") }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .ifBlank { parameters["floatingBallTypeList"].orEmpty() }
        return raw.split(',', '|').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun sesameP2ePageRequests(objects: List<JSONObject>): GameCenterPlayRpcCall.P2ePageRequests? {
        fun firstRequest(vararg keys: String): JSONObject? =
            objects.asSequence()
                .flatMap { value -> keys.asSequence().map { key -> value.opt(key) } }
                .mapNotNull { value ->
                    when (value) {
                        is JSONObject -> JSONObject(value.toString())
                        is String -> value.takeIf { it.isNotBlank() }?.let { raw ->
                            runCatching { JSONObject(raw) }.getOrNull()
                        }
                        else -> null
                    }
                }
                .firstOrNull()
        val homePage = firstRequest(
            "p2eHomePageRequest",
            "p2eQueryHomePageRequest",
            "queryHomePageRequest",
        ) ?: return null
        val taskList = firstRequest(
            "p2eTaskListRequest",
            "p2eQueryTaskListRequest",
            "queryTaskListRequest",
        ) ?: return null
        val feedsGameList = firstRequest(
            "p2eFeedsGameListRequest",
            "p2eQueryFeedsGameListRequest",
            "queryFeedsGameListRequest",
        ) ?: return null
        return GameCenterPlayRpcCall.P2ePageRequests(homePage, taskList, feedsGameList)
    }

    private fun buildSesameFloatingBallContract(
        sceneId: String,
        taskId: String,
        source: String,
        moduleId: String,
        guideType: String,
        gameAppId: String?,
        gameId: String?,
        gameModuleId: String?,
        oriChInfo: String?,
        trafficDriverId: String?,
        floatingBallTypeList: List<String>,
        componentChannel: String?,
        componentScene: String?,
        gameVersion: String?,
        pageRequests: GameCenterPlayRpcCall.P2ePageRequests?,
    ): SesameFloatingBallContract =
        SesameFloatingBallContract(
            sceneId = sceneId,
            taskId = taskId,
            source = source,
            moduleId = moduleId,
            guideType = guideType,
            gameAppId = gameAppId,
            gameId = gameId,
            gameModuleId = gameModuleId,
            oriChInfo = oriChInfo,
            trafficDriverId = trafficDriverId,
            floatingBallTypeList = floatingBallTypeList,
            componentChannel = componentChannel,
            componentScene = componentScene,
            gameVersion = gameVersion,
            pageRequests = pageRequests,
        )

    private fun sesameGameContractObjects(task: JSONObject): List<JSONObject> =
        GameCenterPlayRpcCall.describeTask(task).objects

    private fun sesameP2eFloatingBallEntranceMissingFields(task: JSONObject): List<String> {
        val objects = sesameGameContractObjects(task)
        val parameters = sesameActionUrlParameters(task)
        return listOf(
            "sceneId",
            "taskId",
            "moduleId",
            "guideType",
            "source",
        ).filter { key ->
            firstNonBlank(objects, parameters, key, if (key == "source") "chInfo" else key).isBlank()
        }
    }

    private fun isSesameP2eFloatingBallTask(task: JSONObject): Boolean {
        val objects = sesameGameContractObjects(task)
        val parameters = sesameActionUrlParameters(task)
        val floatingBallTypes = parseFloatingBallTypeList(objects, parameters)
        return floatingBallTypes.any {
            it.equals("P2E_GAME_BROWSE_TASK_FLOATING_BALL", ignoreCase = true)
        } || sesameP2ePageRequests(objects) != null
    }

    private fun sesameActionUrlParameters(task: JSONObject): Map<String, String> =
        GameCenterPlayRpcCall.describeTask(task).urlParameters

    private fun isGameCenterRecommendationPayload(response: JSONObject): Boolean {
        val data = response.optJSONObject("data") ?: return false
        if (data.optJSONArray("feedsList") == null) return false
        val completionObjects = listOf(response, data)
        val explicitCompletion = completionObjects.any { objectValue ->
            listOf("completed", "taskCompleted", "finishSuccess", "lifeRecordCompleted")
                .any { key -> objectValue.optBoolean(key, false) }
        }
        val terminalStatus = completionObjects.asSequence()
            .map { it.optString("taskStatus").trim().uppercase(Locale.ROOT) }
            .any { it in setOf("DONE", "COMPLETED", "FINISHED", "SUCCESS") }
        return !explicitCompletion && !terminalStatus
    }

    private fun confirmSesameLifeRecord(
        version: String,
        templateId: String,
        recordId: String,
    ): TaskFlowActionResult {
        val detail = "templateId=$templateId recordId=$recordId version=$version"
        for (queryLast in listOf(true, false)) {
            val rpc = when {
                queryLast -> "AntSesameCreditRpcCall.queryLastOperateTask"
                version == "alchemy" -> "AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryListV3"
                else -> "AntSesameCreditRpcCall.queryAvailableSesameTask"
            }
            val raw = when {
                queryLast -> AntSesameCreditRpcCall.queryLastOperateTask(version)
                version == "alchemy" -> AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryListV3()
                else -> AntSesameCreditRpcCall.queryAvailableSesameTask()
            }
            val root = parseJSONObjectOrNull(raw)
                ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "RESPONSE_PARSE_ERROR",
                    message = "生活记录回查响应无法解析",
                    rpc = rpc,
                    raw = raw,
                    detail = detail,
                )
            val response = root.optJSONObject("resData") ?: root
            if (!ResChecker.checkRes(TAG, response)) {
                val code = response.optString("errorCode", response.optString("resultCode", ""))
                val failureType = classifySesameTaskFailure(code, response)
                return TaskFlowActionResult.failure(
                    failureType = failureType,
                    code = code,
                    message = buildSesameRpcMessage(response, raw),
                    rpc = rpc,
                    raw = raw,
                    detail = detail,
                    stopCurrentRound = isSesameTaskFlowInterrupted(response),
                    continueCurrentRoundOnFailure = failureType == TaskRpcFailureType.RETRYABLE_RPC ||
                        failureType == TaskRpcFailureType.BUSINESS_LIMIT,
                )
            }
            val data = response.optJSONObject("data")
                ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "QUERY_DATA_EMPTY",
                    message = "生活记录回查缺少任务数据",
                    rpc = rpc,
                    raw = raw,
                    detail = detail,
                )
            val tasks = if (queryLast) {
                listOfNotNull(data.optJSONObject("lastOperateTaskVO"))
            } else {
                val dailyTasks = data.optJSONObject("dailyTaskListVO")
                listOfNotNull(
                    dailyTasks?.optJSONArray("waitCompleteTaskVOS"),
                    dailyTasks?.optJSONArray("waitJoinTaskVOS"),
                    data.optJSONArray("toCompleteVOS"),
                    data.optJSONArray("completeVOS"),
                    dailyTasks?.optJSONArray("todayCompleteTaskVOS"),
                ).flatMap { tasks ->
                    (0 until tasks.length()).mapNotNull { tasks.optJSONObject(it) }
                }
            }
            if (tasks.any {
                    it.optString("templateId") == templateId && it.optString("recordId") == recordId &&
                        it.optBoolean("finishFlag", false)
                }) {
                UserDataStoreManager.getInstance(UserMap.currentUid)?.remove("sesame_user_growth_pending:$recordId")
                return TaskFlowActionResult.success(refreshAfterAction = true)
            }
        }
        return TaskFlowActionResult.defer(
            deferredReason = DeferredReason.STATE_CONFIRMATION,
            message = "同一生活记录尚未确认完成",
            detail = detail,
        )
    }

    private fun completeSesameLifeRecord(
        task: JSONObject,
        taskTitle: String,
        spec: SesameLifeRecordCompletionSpec,
        actionDetail: String,
    ): TaskFlowActionResult {
        if (!task.optString("bizType").equals("LIFE_RECORD", ignoreCase = true)) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = "UNSUPPORTED_BIZ_TYPE",
                message = "非LIFE_RECORD任务不走生活记录完成动作",
                rpc = "AntSesameCredit.completeSesameLifeRecord",
                detail = actionDetail,
            )
        }
        val floatingBall = sesameFloatingBallContract(task)
        if (floatingBall != null) {
            return completeSesameFloatingBallTask(
                task = task,
                taskTitle = taskTitle,
                spec = spec,
                floatingBall = floatingBall,
                actionDetail = actionDetail,
            )
        }
        if (isSesameP2eFloatingBallTask(task)) {
            val missingFields = sesameP2eFloatingBallEntranceMissingFields(task).joinToString(",")
            Log.sesame("芝麻炼金[P2E前置条件待确认]#$taskTitle missingFields=$missingFields")
            return TaskFlowActionResult.defer(
                deferredReason = DeferredReason.PREREQUISITE_PENDING,
                message = "检测到P2E浮球任务但入口合同字段不完整，等待后续调度",
                rpc = "AntSesameCredit.completeSesameLifeRecord",
                detail = "$actionDetail completionMode=P2E_FLOATING_BALL missingFields=$missingFields confirmationState=NOT_SUBMITTED",
            )
        }

        val gameDecision = GameCenterPlayRpcCall.resolveTaskAction(task, directFinishSupported = true)
        if (gameDecision.action == GameCenterPlayRpcCall.TaskAction.DURATION_ONLY) {
            val directGameContract = gameDecision.contract ?: return TaskFlowActionResult.defer(
                deferredReason = DeferredReason.PREREQUISITE_PENDING,
                message = "游戏时长任务缺少完整上报合同",
                rpc = "GameCenterPlayRpcCall.resolveTaskAction",
                detail = "$actionDetail completionMode=DIRECT_GAME_DURATION missingFields=${gameDecision.missingFields.joinToString(",")}",
            )
            submitSesameGameDuration(directGameContract, taskTitle, spec, actionDetail)?.let { return it }
        }
        if (gameDecision.action == GameCenterPlayRpcCall.TaskAction.DEFERRED) {
            return TaskFlowActionResult.defer(
                deferredReason = DeferredReason.PREREQUISITE_PENDING,
                message = "游戏任务完成条件待确认，保留后续执行机会",
                rpc = "GameCenterPlayRpcCall.resolveTaskAction",
                raw = task.toString(),
                detail = "$actionDetail reason=${gameDecision.reason} missingFields=${gameDecision.missingFields.joinToString(",")}",
            )
        }
        gameDecision.mappedTask?.let { mappedTask ->
            if (!kotlinx.coroutines.runBlocking { mappedTask.report(1) }) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    message = "已有游戏业务动作未完成",
                    rpc = "GameTask.report",
                    detail = actionDetail,
                    continueCurrentRoundOnFailure = true,
                )
            }
        }
        val templateId = task.optString("templateId").trim()
        val recordId = task.optString("recordId").trim()
        if (recordId.isBlank()) {
            return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = "RECORD_ID_EMPTY",
                message = "推送型生活记录缺少recordId",
                rpc = "AntSesameCreditRpcCall.finishSesameTask",
                detail = "$actionDetail templateId=$templateId completionMode=PUSH_ACTIVITY confirmationState=NOT_SUBMITTED",
            )
        }

        val pendingStore = AccountSessionCoordinator.currentUserId()?.let { UserDataStoreManager.getInstance(it) }
        val pendingKey = "sesame_user_growth_pending:$recordId"
        val userGrowth = extractQueryParam(task.optString("actionUrl"), "jumpAction") == "userGrowth"
        val feedbackResult =
            if (pendingStore?.get(pendingKey, Boolean::class.java) == true) TaskFlowActionResult.success()
            else reportSesameTaskFeedbackResult(
                task = task,
                taskTitle = taskTitle,
                logPrefix = spec.logPrefix,
                moduleName = spec.moduleName,
                version = spec.version,
                sceneCode = spec.sceneCode,
                changeRewardType = spec.changeRewardType,
            )
        if (!feedbackResult.success && feedbackResult.failureType != TaskRpcFailureType.TERMINAL_DONE) {
            return feedbackResult
        }
        if (userGrowth) {
            pendingStore?.put(pendingKey, true)
            scheduleSesameConfirmation(
                JSONObject().put("templateId", templateId).put("recordId", recordId).put("version", spec.version),
            )
        }

        val confirmation = confirmSesameLifeRecord(spec.version, templateId, recordId)
        if (confirmation.failureType != null) {
            return confirmation.copy(detail = "$actionDetail ${confirmation.detail}")
        }
        if (confirmation.success) {
            if (!userGrowth) {
                pendingStore?.remove(pendingKey)
            }
            spec.joinedRecordIds?.remove(templateId)
            Log.sesame("${spec.logPrefix}[生活记录回查确认完成]#$taskTitle templateId=$templateId recordId=$recordId")
            return confirmation
        }
        // userGrowth 的业务完成由外部应用确认，普通生活记录推送不能替代。
        if (userGrowth) {
            Log.sesame("${spec.logPrefix}[外跳业务尚未确认完成，保留记录回查]#$taskTitle templateId=$templateId recordId=$recordId")
            return confirmation.copy(
                message = "外跳业务自动执行尚未接通，保留记录回查",
                detail = "$actionDetail ${confirmation.detail} jumpAction=userGrowth confirmationState=PENDING",
                refreshAfterAction = true,
            )
        }
        if (feedbackResult.failureType == TaskRpcFailureType.TERMINAL_DONE) {
            return feedbackResult.copy(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "任务回调返回终态，但同一生活记录尚未回查确认：${feedbackResult.message}",
                refreshAfterAction = true,
            )
        }

        val feedbackCode = feedbackResult.code.ifBlank { "SUCCESS" }
        if (templateId == "zml_zmzl_cyz_erfang") {
            val actionRes = AntSesameCreditRpcCall.submitSesameRentAction()
            val actionJo = parseJSONObjectOrNull(actionRes)
                ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "RESPONSE_PARSE_ERROR",
                    message = "租赁动作响应无法解析",
                    rpc = "AntSesameCreditRpcCall.submitSesameRentAction",
                    raw = actionRes,
                    detail = "$actionDetail templateId=$templateId recordId=$recordId feedbackCode=$feedbackCode",
                )
            if (!ResChecker.checkRes(TAG, actionJo)) {
                val actionCode = actionJo.optString("errorCode", actionJo.optString("resultCode", ""))
                return TaskFlowActionResult.failure(
                    failureType = classifySesameTaskFailure(actionCode, actionJo),
                    code = actionCode,
                    message = buildSesameRpcMessage(actionJo, actionRes),
                    rpc = "AntSesameCreditRpcCall.submitSesameRentAction",
                    raw = actionRes,
                    detail = "$actionDetail templateId=$templateId recordId=$recordId feedbackCode=$feedbackCode",
                    stopCurrentRound = isSesameTaskFlowInterrupted(actionJo),
                    continueCurrentRoundOnFailure = classifySesameTaskFailure(actionCode, actionJo) ==
                        TaskRpcFailureType.BUSINESS_LIMIT,
                )
            }
        }
        val finishRes = AntSesameCreditRpcCall.finishSesameTask(recordId)
        val finishJo = parseJSONObjectOrNull(finishRes)
            ?: return TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                code = "RESPONSE_PARSE_ERROR",
                message = "pushActivity响应无法解析",
                rpc = "AntSesameCreditRpcCall.finishSesameTask",
                raw = finishRes,
                detail = "$actionDetail templateId=$templateId recordId=$recordId completionMode=PUSH_ACTIVITY confirmationState=NOT_SUBMITTED",
            )
        if (ResChecker.checkRes(TAG, finishJo)) {
            if (isGameCenterRecommendationPayload(finishJo)) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    code = "GAME_CENTER_FEEDS_NOT_COMPLETION",
                    message = "finishSesameTask返回推荐游戏数据而非生活记录完成结果",
                    rpc = "AntSesameCreditRpcCall.finishSesameTask",
                    raw = finishRes,
                    detail = "$actionDetail templateId=$templateId recordId=$recordId completionMode=PUSH_ACTIVITY confirmationState=NOT_CONFIRMED",
                )
            }
            spec.joinedRecordIds?.remove(templateId)
            val finishCode = finishJo.optString("resultCode", "SUCCESS").ifBlank { "SUCCESS" }
            Log.sesame(
                "${spec.logPrefix}[生活记录推送已接受，等待列表确认]#$taskTitle " +
                    "templateId=$templateId recordId=$recordId completionMode=PUSH_ACTIVITY " +
                    "feedbackCode=$feedbackCode finishCode=$finishCode confirmationState=PENDING",
            )
            return TaskFlowActionResult.defer(
                deferredReason = DeferredReason.STATE_CONFIRMATION,
                message = "生活记录推送已提交，等待任务列表确认",
                rpc = "AntSesameCreditRpcCall.finishSesameTask",
                raw = finishRes,
                detail = "$actionDetail templateId=$templateId recordId=$recordId completionMode=PUSH_ACTIVITY feedbackCode=$feedbackCode finishCode=$finishCode confirmationState=PENDING",
                refreshAfterAction = true,
            )
        }
        val errorCode = finishJo.optString("errorCode", finishJo.optString("resultCode", ""))
        val resultView = buildSesameRpcMessage(finishJo, finishRes)
        val failureType = classifySesameTaskFailure(errorCode, finishJo)
        return TaskFlowActionResult.failure(
            failureType = failureType,
            code = errorCode,
            message = resultView,
            rpc = "AntSesameCreditRpcCall.finishSesameTask",
            raw = finishRes,
            detail = "$actionDetail templateId=$templateId recordId=$recordId completionMode=PUSH_ACTIVITY " +
                "feedbackCode=$feedbackCode feedbackRaw=${feedbackResult.raw} confirmationState=NOT_CONFIRMED",
            stopCurrentRound = isSesameTaskFlowInterrupted(finishJo),
            continueCurrentRoundOnFailure = failureType == TaskRpcFailureType.RETRYABLE_RPC ||
                failureType == TaskRpcFailureType.BUSINESS_LIMIT,
        ).copy(refreshAfterAction = true)
    }

    private fun submitSesameGameDuration(
        contract: GameCenterPlayRpcCall.Contract,
        taskTitle: String,
        spec: SesameLifeRecordCompletionSpec,
        actionDetail: String,
    ): TaskFlowActionResult? {
        val ack = GameCenterPlayRpcCall.submitForAck(contract)
        val response = ack.response
            ?: return TaskFlowActionResult.failure(
                failureType = ack.failureType,
                message = "时长动作响应无法解析",
                rpc = "GameCenterPlayRpcCall.submit",
                raw = ack.raw,
                detail = "$actionDetail completionMode=DIRECT_GAME_DURATION fieldSource=taskContract",
            )
        if (ack.accepted) {
            Log.sesame(
                "${spec.logPrefix}[时长上报已接受]#$taskTitle completionMode=DIRECT_GAME_DURATION " +
                    "fieldSource=taskContract gameAppId=${contract.gameAppId} playTime=${contract.playTime}",
            )
            return null
        }
        val failureType = sesameGameActionFailureType(response, ack.failureType)
        return TaskFlowActionResult.failure(
            failureType = failureType,
            code = response.optString("resultCode", response.optString("errorCode", "")),
            message = buildSesameRpcMessage(response, ack.raw),
            rpc = "GameCenterPlayRpcCall.submit",
            raw = ack.raw,
            detail = "$actionDetail completionMode=DIRECT_GAME_DURATION fieldSource=taskContract",
            stopCurrentRound = isSesameTaskFlowInterrupted(response),
            continueCurrentRoundOnFailure = failureType == TaskRpcFailureType.RETRYABLE_RPC ||
                failureType == TaskRpcFailureType.BUSINESS_LIMIT,
        )
    }

    private fun sesameGameActionFailureType(
        response: JSONObject,
        fallback: TaskRpcFailureType,
    ): TaskRpcFailureType {
        val errorCode = response.optString("resultCode", response.optString("errorCode", response.optString("code", "")))
        return classifySesameTaskFailure(errorCode, response).let { classified ->
            if (classified == TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW) fallback else classified
        }
    }

    private fun completeSesameP2eFloatingBallTask(
        taskTitle: String,
        spec: SesameLifeRecordCompletionSpec,
        p2eContract: GameCenterPlayRpcCall.P2eFloatingBallContract,
        feedbackPendingKey: String,
        taskIdentity: String,
        completionMode: String,
        actionDetail: String,
        identityRaw: String,
    ): TaskFlowActionResult {
        Log.sesame(
            "${spec.logPrefix}[P2E浮球合同]#$taskTitle taskId=$taskIdentity completionMode=$completionMode " +
                "fieldSource=taskContract+p2ePageSession gameAppId=${p2eContract.gameAppId} gameId=${p2eContract.gameId} " +
                "gameModuleId=${p2eContract.gameModuleId} source=${p2eContract.source}",
        )
        val initialize = GameCenterPlayRpcCall.initializeP2eFloatingBallGame(p2eContract)
        if (!initialize.accepted) {
            val response = initialize.response
            return TaskFlowActionResult.failure(
                failureType = response?.let { sesameGameActionFailureType(it, initialize.failureType) } ?: initialize.failureType,
                code = response?.let { it.optString("resultCode", it.optString("errorCode", "")) }.orEmpty(),
                message = response?.let { buildSesameRpcMessage(it, initialize.raw) } ?: "P2E游戏初始化响应无法解析",
                rpc = "GameCenterPlayRpcCall.initializeP2eFloatingBallGame",
                raw = initialize.raw,
                detail = "$actionDetail completionMode=$completionMode gameAppId=${p2eContract.gameAppId} gameId=${p2eContract.gameId} gameModuleId=${p2eContract.gameModuleId}",
            )
        }
        val consult = GameCenterPlayRpcCall.consultP2eFloatingBall(p2eContract)
        if (!consult.accepted || consult.timeSeconds == null) {
            val response = consult.response
            return TaskFlowActionResult.failure(
                failureType = response?.let { sesameGameActionFailureType(it, consult.failureType) } ?: consult.failureType,
                code = response?.let { it.optString("resultCode", it.optString("errorCode", "")) }.orEmpty(),
                message = response?.let { buildSesameRpcMessage(it, consult.raw) } ?: "P2E浮球consult响应无法解析",
                rpc = "GameCenterPlayRpcCall.consultP2eFloatingBall",
                raw = consult.raw,
                detail = "$actionDetail completionMode=$completionMode gameAppId=${p2eContract.gameAppId} gameId=${p2eContract.gameId} gameModuleId=${p2eContract.gameModuleId} gameEngineType=${consult.gameEngineType.orEmpty()}",
            )
        }
        val event = GameCenterPlayRpcCall.submitGameLoadingCompletedEvent(p2eContract)
        if (!event.accepted) {
            Log.sesame("${spec.logPrefix}[P2E loading_completed遥测失败]#$taskTitle raw=${event.raw}")
        }
        val duration = GameCenterPlayRpcCall.submitP2eDurationForAck(
            p2eContract.copy(durationSeconds = consult.timeSeconds),
        )
        if (!duration.accepted) {
            val failed = duration.acknowledgements.lastOrNull { !it.accepted }
            val response = failed?.response
            val failureType = response?.let { sesameGameActionFailureType(it, duration.failureType) } ?: duration.failureType
            return TaskFlowActionResult.failure(
                failureType = failureType,
                code = response?.optString("resultCode", response.optString("errorCode", "")).orEmpty(),
                message = response?.let { buildSesameRpcMessage(it, failed?.raw.orEmpty()) } ?: "P2E时长分片响应无法解析",
                rpc = "GameCenterPlayRpcCall.submitP2eDurationForAck",
                raw = duration.raw,
                detail = "$actionDetail completionMode=$completionMode gameAppId=${p2eContract.gameAppId} gameId=${p2eContract.gameId} gameModuleId=${p2eContract.gameModuleId} consultSeconds=${consult.timeSeconds} chunks=${duration.acceptedChunks}/${duration.totalChunks}",
            )
        }
        val typeList = consult.response?.optJSONObject("data")?.optJSONArray("floatingBallTypeList")
        val completeContract = p2eContract.copy(
            floatingBallTypeList = if (typeList != null) {
                (0 until typeList.length()).mapNotNull { typeList.optString(it).takeIf(String::isNotBlank) }
            } else p2eContract.floatingBallTypeList,
        )
        val complete = GameCenterPlayRpcCall.completeP2eFloatingBall(completeContract)
        if (!complete.accepted) {
            val response = complete.response
            return TaskFlowActionResult.failure(
                failureType = response?.let { sesameGameActionFailureType(it, complete.failureType) } ?: complete.failureType,
                code = response?.let { it.optString("resultCode", it.optString("errorCode", "")) }.orEmpty(),
                message = response?.let { buildSesameRpcMessage(it, complete.raw) } ?: "P2E浮球complete响应无法解析",
                rpc = "GameCenterPlayRpcCall.completeP2eFloatingBall",
                raw = complete.raw,
                detail = "$actionDetail completionMode=$completionMode gameAppId=${p2eContract.gameAppId} gameId=${p2eContract.gameId} gameModuleId=${p2eContract.gameModuleId} consultSeconds=${consult.timeSeconds}",
            )
        }
        clearSesameFloatingBallFeedbackPending(feedbackPendingKey)
        Log.sesame(
            "${spec.logPrefix}[P2E游戏中心浮球完成动作已接受]#$taskTitle taskId=$taskIdentity " +
                "completionMode=$completionMode consultSeconds=${consult.timeSeconds} " +
                "chunks=${duration.acceptedChunks}/${duration.totalChunks} confirmationState=PENDING",
        )
        return TaskFlowActionResult.defer(
            deferredReason = DeferredReason.STATE_CONFIRMATION,
            message = "P2E游戏中心浮球完成已提交，等待任务列表与芝麻粒回查",
            rpc = "GameCenterPlayRpcCall.completeP2eFloatingBall",
            raw = "identity=$identityRaw initialize=${initialize.raw} consult=${consult.raw} duration=${duration.raw} complete=${complete.raw}",
            detail = "$actionDetail completionMode=$completionMode gameAppId=${p2eContract.gameAppId} gameId=${p2eContract.gameId} gameModuleId=${p2eContract.gameModuleId} consultSeconds=${consult.timeSeconds} chunks=${duration.totalChunks} confirmationState=PENDING",
            refreshAfterAction = true,
        )
    }

    private fun completeSesameFloatingBallTask(
        task: JSONObject,
        taskTitle: String,
        spec: SesameLifeRecordCompletionSpec,
        floatingBall: SesameFloatingBallContract,
        actionDetail: String,
    ): TaskFlowActionResult {
        val feedbackPendingKey = sesameFloatingBallFeedbackPendingKey(task, floatingBall)
        submitSesameFloatingBallFeedbackOnce(
            task = task,
            taskTitle = taskTitle,
            spec = spec,
            feedbackPendingKey = feedbackPendingKey,
            taskIdentity = floatingBall.taskId,
        )?.let { return it }
        if (!isSesameP2eFloatingBallTask(task)) {
            val passThrough = JSONObject()
                .put("sceneId", floatingBall.sceneId)
                .put("taskId", floatingBall.taskId)
                .toString()
            val home = GameCenterPlayRpcCall.queryExternalGameCenter(
                floatingBall.sceneId, floatingBall.moduleId, floatingBall.guideType,
                floatingBall.source, passThrough,
            )
            if (!home.accepted) {
                return TaskFlowActionResult.failure(
                    failureType = home.failureType,
                    message = "频道游戏中心初始化失败",
                    rpc = "GameCenterPlayRpcCall.queryExternalGameCenter",
                    raw = home.raw,
                    detail = actionDetail,
                )
            }
            val games = GameCenterPlayRpcCall.queryExternalRecommendGames(floatingBall.sceneId, floatingBall.source)
            if (!games.accepted) {
                return TaskFlowActionResult.failure(
                    failureType = games.failureType,
                    message = "频道游戏列表查询失败",
                    rpc = "GameCenterPlayRpcCall.queryExternalRecommendGames",
                    raw = games.raw,
                    detail = actionDetail,
                )
            }
            val data = games.response?.optJSONObject("data")
            val gameAppId = sequenceOf("recentPlayGameVO", "todayRecommendVO")
                .mapNotNull { data?.optJSONObject(it)?.optJSONArray("gameList") }
                .flatMap { list -> (0 until list.length()).asSequence().mapNotNull { list.optJSONObject(it) } }
                .mapNotNull { it.opt("appId") as? String }
                .firstOrNull { it.isNotBlank() }
                ?: return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                    message = "频道游戏列表未返回可用的游戏appId",
                    rpc = "GameCenterPlayRpcCall.queryExternalRecommendGames",
                    raw = games.raw,
                    detail = actionDetail,
                )
            val consult = GameCenterPlayRpcCall.consultFloatingBall(passThrough, floatingBall.source)
            val seconds = consult.timeSeconds
            if (!consult.accepted || seconds == null) {
                return TaskFlowActionResult.failure(
                    failureType = consult.failureType,
                    message = "频道浮球时长查询失败",
                    rpc = "GameCenterPlayRpcCall.consultFloatingBall",
                    raw = consult.raw,
                    detail = actionDetail,
                )
            }
            val playTime = (seconds.toLong() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val durationResult = submitSesameGameDuration(
                GameCenterPlayRpcCall.Contract(gameAppId, playTime, floatingBall.source),
                taskTitle, spec, actionDetail,
            )
            if (durationResult != null) return durationResult
            val complete = GameCenterPlayRpcCall.completeFloatingBall(passThrough, floatingBall.sceneId)
            if (!complete.accepted) {
                return TaskFlowActionResult.failure(
                    failureType = complete.response?.let { sesameGameActionFailureType(it, complete.failureType) }
                        ?: complete.failureType,
                    message = "频道浮球完成请求失败",
                    rpc = "GameCenterPlayRpcCall.completeFloatingBall",
                    raw = complete.raw,
                    detail = actionDetail,
                )
            }
            clearSesameFloatingBallFeedbackPending(feedbackPendingKey)
            return TaskFlowActionResult.defer(
                deferredReason = DeferredReason.STATE_CONFIRMATION,
                message = "频道浮球完成已提交，等待生活记录和奖励确认",
                rpc = "GameCenterPlayRpcCall.completeFloatingBall",
                raw = complete.raw,
                detail = "$actionDetail completionMode=CHANNEL_FLOATING_BALL gameAppId=$gameAppId confirmationState=PENDING",
                refreshAfterAction = true,
            )
        }
        val entranceContract = GameCenterPlayRpcCall.P2eFloatingBallContract(
            sceneId = floatingBall.sceneId,
            taskId = floatingBall.taskId,
            moduleId = floatingBall.moduleId,
            guideType = floatingBall.guideType,
            gameAppId = floatingBall.gameAppId.orEmpty(),
            gameId = floatingBall.gameId.orEmpty(),
            gameModuleId = floatingBall.gameModuleId.orEmpty(),
            source = floatingBall.source,
            oriChInfo = floatingBall.oriChInfo.orEmpty(),
            trafficDriverId = floatingBall.trafficDriverId.orEmpty(),
            floatingBallTypeList = floatingBall.floatingBallTypeList,
            componentChannel = floatingBall.componentChannel.orEmpty(),
            componentScene = floatingBall.componentScene.orEmpty(),
            gameVersion = floatingBall.gameVersion.orEmpty(),
        )
        val requests = floatingBall.pageRequests
            ?: return deferSesameP2ePrerequisite(
                taskTitle = taskTitle,
                taskIdentity = floatingBall.taskId,
                actionDetail = actionDetail,
                missingFields = listOf("p2eHomePageRequest", "p2eTaskListRequest", "p2eFeedsGameListRequest"),
                raw = "",
            )
        val pageSession = GameCenterPlayRpcCall.resolveP2ePageSession(entranceContract, requests)
        if (!pageSession.requestsAccepted || pageSession.session == null) {
            return deferSesameP2ePrerequisite(
                taskTitle = taskTitle,
                taskIdentity = floatingBall.taskId,
                actionDetail = actionDetail,
                missingFields = pageSession.missingFields,
                raw = pageSession.raw,
            )
        }
        return completeSesameP2eFloatingBallTask(
            taskTitle = taskTitle,
            spec = spec,
            p2eContract = pageSession.session.contract,
            feedbackPendingKey = feedbackPendingKey,
            taskIdentity = floatingBall.taskId,
            completionMode = "P2E_FLOATING_BALL",
            actionDetail = actionDetail,
            identityRaw = pageSession.raw,
        )
    }

    private fun deferSesameP2ePrerequisite(
        taskTitle: String,
        taskIdentity: String,
        actionDetail: String,
        missingFields: List<String>,
        raw: String,
    ): TaskFlowActionResult {
        val fields = missingFields.ifEmpty { listOf("P2E_PAGE_SESSION") }.joinToString(",")
        Log.sesame("芝麻炼金[P2E前置条件待确认]#$taskTitle taskId=$taskIdentity missing=$fields")
        return TaskFlowActionResult.defer(
            deferredReason = DeferredReason.PREREQUISITE_PENDING,
            message = "P2E页面会话合同不完整，等待后续调度",
            rpc = "GameCenterPlayRpcCall.resolveP2ePageSession",
            raw = raw,
            detail = "$actionDetail completionMode=P2E_PREREQUISITE_PENDING fieldSource=taskContract+p2ePageSession taskId=$taskIdentity missingFields=$fields confirmationState=NOT_SUBMITTED",
        )
    }

    /**
     * 芝麻粒兑换道具
     * 仿照会员积分兑换逻辑：遍历列表更新Map，同时匹配用户设置进行兑换
     */
    private fun refreshSesameGrainExchangeOptionsForSettings(): List<MapperEntity> {
        val freshRows = ExchangeOptionsCache.loadTodaySnapshot(
            UserMap.currentUid, ExchangeOptionsRefreshBridge.TARGET_SESAME_GRAIN
        )?.rows
        if (freshRows != null) {
            Log.sesame("芝麻粒兑换🛒设置页使用新鲜缓存#${freshRows.size}")
            return freshRows
        }
        if (!HookReadyChecker.isCurrentProcessReadyForRpc(UserMap.currentUid)) {
            val cachedRows =
                ExchangeOptionsCache.loadForSettingsCache(
                    UserMap.currentUid,
                    ExchangeOptionsRefreshBridge.TARGET_SESAME_GRAIN,
                )
            if (!HookReadyChecker.isTargetAppReadyForRpc(UserMap.currentUid)) {
                Log.sesame("芝麻粒兑换🛒目标应用未启动，设置页先展示上次缓存列表；请打开目标应用后再刷新#${cachedRows.size}")
                return cachedRows
            }
            val refreshResult =
                ExchangeOptionsRefreshBridge.requestRefreshOptions(
                    ExchangeOptionsRefreshBridge.TARGET_SESAME_GRAIN,
                    UserMap.currentUid,
                )
            if (refreshResult.success) {
                Log.sesame("芝麻粒兑换🛒设置页使用目标应用刷新列表#${refreshResult.options.size}")
                return refreshResult.options
            }
            if (cachedRows.isNotEmpty()) {
                Log.sesame("芝麻粒兑换🛒远程刷新失败，设置页回退上次缓存快照#${cachedRows.size}#${refreshResult.message}")
                return cachedRows
            }
            Log.sesame("芝麻粒兑换🛒远程刷新失败，且无可用缓存快照#${refreshResult.message}")
            return emptyList()
        }
        val rowsResult =
            runCatching {
                refreshSesameGrainExchangeOptionsFromRpc()
            }.onFailure {
                Log.printStackTrace(TAG, "refreshSesameGrainExchangeOptionsForSettings.currentRpc err:", it)
            }
        val rows =
            rowsResult.getOrElse { throwable ->
                val cachedRows =
                    ExchangeOptionsCache.loadForSettingsCache(
                        UserMap.currentUid,
                        ExchangeOptionsRefreshBridge.TARGET_SESAME_GRAIN,
                    )
                if (cachedRows.isNotEmpty()) {
                    Log.sesame("芝麻粒兑换🛒当前进程刷新失败，设置页回退上次缓存快照#${cachedRows.size}#${throwable.message}")
                    cachedRows
                } else {
                    Log.sesame("芝麻粒兑换🛒当前进程刷新失败，且无可用缓存快照#${throwable.message}")
                    emptyList()
                }
            }
        Log.sesame("芝麻粒兑换🛒设置页刷新结构化列表#${rows.size}")
        return rows
    }

    private fun refreshSesameGrainExchangeOptionsFromRpc(forceRefresh: Boolean = false): List<ExchangeOptionRow> =
        querySesameGrainExchangeCandidates(forceRefresh).map { it.item.toOptionRow() }

    private fun querySesameGrainExchangeCandidates(forceRefresh: Boolean = false): List<SesameExchangeCandidate> {
        fun parse(payload: JSONObject): List<SesameExchangeCandidate> {
            val candidates = LinkedHashMap<String, SesameExchangeCandidate>()
            val items = payload.getJSONArray("items")
            for (i in 0 until items.length()) {
                val candidate = buildSesameExchangeCandidate(items.getJSONObject(i)) ?: continue
                candidates.putIfAbsent(candidate.item.id, candidate)
            }
            return candidates.values.toList()
        }
        val snapshot = ExchangeOptionsCache.getOrFetch(UserMap.currentUid, ExchangeOptionsRefreshBridge.TARGET_SESAME_GRAIN, forceRefresh) { session ->
            val pageSize = 20
            val pendingTabs = mutableListOf<String?>(null)
            val scannedTabs = LinkedHashSet<String>()
            val items = JSONArray()
            var tabIndex = 0
            while (tabIndex < pendingTabs.size) {
                val tab = pendingTabs[tabIndex++]
                val tabKey = tab ?: ""
                if (!scannedTabs.add(tabKey)) continue
                val seenItemIds = linkedSetOf<String>()
                var currentPage = 1
                while (true) {
                    val jo = session.read("items", JSONObject().put("tab", tabKey).put("pageNum", currentPage).put("pageSize", pageSize),
                        "${tabKey.ifBlank { "全部商品" }}第 $currentPage 页") {
                        JSONObject(AntSesameCreditRpcCall.queryExchangeList(currentPage, pageSize, tab))
                    }
                    check(ResChecker.checkRes(TAG, jo)) { "芝麻粒兑换列表查询失败" }
                    val data = jo.getJSONObject("data")
                    val tabList = data.optJSONArray("tabList")
                    if (tabList != null) {
                        for (i in 0 until tabList.length()) {
                            val discoveredTab = tabList.optJSONObject(i)?.optString("tab").orEmpty()
                                .ifEmpty { tabList.optString(i) }
                            if (discoveredTab.isNotBlank() && discoveredTab != "all" &&
                                !scannedTabs.contains(discoveredTab) && !pendingTabs.contains(discoveredTab)
                            ) {
                                pendingTabs.add(discoveredTab)
                            }
                        }
                    }
                    val list = data.getJSONArray("awardTemplateList")
                    val seenCount = seenItemIds.size
                    for (i in 0 until list.length()) {
                        val item = list.getJSONObject(i)
                        items.put(item)
                        item.optString("awardTemplateId").trim().takeIf { it.isNotBlank() }?.let { seenItemIds.add(it) }
                    }
                    val hasNext = data.optBoolean("hasNext", false)
                    if (hasNext) {
                        check(list.length() > 0 && seenItemIds.size > seenCount) {
                            "芝麻粒兑换分页未前进: tab=$tabKey currentPage=$currentPage count=${list.length()} hasNext=true"
                        }
                    }
                    session.commit(JSONObject().put("pendingTabs", JSONArray(pendingTabs.map { it ?: "" }))
                        .put("tabIndex", if (hasNext) tabIndex - 1 else tabIndex).put("pageNum", if (hasNext) currentPage + 1 else 1),
                        list.length(), if (hasNext) "${tabKey.ifBlank { "全部商品" }}第 ${currentPage + 1} 页" else "当前标签结束，继续后续标签或保存完整列表")
                    if (!hasNext) break
                    currentPage++
                    session.pageTurnDelay()
                }
            }
            val payload = JSONObject().put("items", items)
            ExchangeOptionsSnapshot(parse(payload).map { it.item.toOptionRow() }, payload)
        }
        val candidates = parse(snapshot.payload)
        val sesameGiftMap = IdMapManager.getInstance(SesameGiftMap::class.java)
        candidates.forEach { sesameGiftMap.add(it.item.id, it.item.displayName()) }
        sesameGiftMap.save(UserMap.currentUid)
        return candidates
    }

    internal fun refreshSesameGrainExchangeOptionsForRemote(forceRefresh: Boolean = false): List<ExchangeOptionRow> =
        refreshSesameGrainExchangeOptionsFromRpc(forceRefresh)

    internal suspend fun doSesameGrainExchange(): Unit =
        CoroutineUtils.run {
            try {
                // 保持原每日自动兑换额度，只记录已确认商品，不让其他商品失败导致重复兑换。
                val targetIds = ExchangeReplenisher.remainingSelectedIds(sesameGrainExchangeList?.value) {
                    !hasFlagToday(StatusFlags.FLAG_SESAME_GRAIN_EXCHANGE_DONE + "::" + it)
                }
                if (targetIds.isEmpty()) return@run
                val candidates = querySesameGrainExchangeCandidates()
                val remainingTargetIds = targetIds.toMutableSet()
                for (candidate in candidates) {
                    if (!targetIds.contains(candidate.item.id)) continue
                    remainingTargetIds.remove(candidate.item.id)
                    when (candidate.item.safety) {
                        ExchangeSafety.LOG_ONLY -> {
                            Log.sesame("芝麻粒兑换🛒已勾选[${candidate.item.displayName()}]#仅提醒，不自动兑换")
                        }
                        ExchangeSafety.AUTO, ExchangeSafety.UNAVAILABLE -> {
                            exchangeSesameGift(candidate.templateId, candidate.item.name)
                        }
                    }
                }

                val unresolvedTargetIds = remainingTargetIds
                unresolvedTargetIds
                    .forEach { Log.sesame("芝麻粒兑换🛒已勾选[$it]#本次列表未返回，保留配置不删除") }
                Log.sesame("芝麻粒兑换列表刷新完成#${candidates.size}")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.printStackTrace(TAG, "doSesameGrainExchange 运行异常:", t)
            }
        }

    private fun buildSesameExchangeCandidate(item: JSONObject): SesameExchangeCandidate? {
        val templateId = item.optString("awardTemplateId").trim()
        if (templateId.isEmpty()) {
            return null
        }
        val name = item.optString("awardName", "未知商品")
        val extInfo = item.optJSONObject("extInfo")
        val pointNeeded = item.optString("point", item.optString("rawPoint", "0"))
        val tabLabel = item.optString("awardTabLabel", extInfo?.optString("awardTabLabel").orEmpty())
        val remainingBudget = item.optInt("remainingBudget", 0)
        val validType = extInfo?.optString("validType").orEmpty()
        val sendStart = item.optLong("sendStartTime", 0L)
        val sendEnd = item.optLong("sendEndTime", 0L)
        val now = System.currentTimeMillis()
        val statusParts = mutableListOf<String>()
        if (item.optBoolean("hasTaken", false)) {
            statusParts.add("已领取")
        }
        if (item.optBoolean("hasFinished", false)) {
            statusParts.add("已完成")
        }
        if (remainingBudget <= 0) {
            statusParts.add("库存不足")
        }
        if (tabLabel.isNotBlank()) {
            statusParts.add(tabLabel)
        }
        if (validType.isNotBlank()) {
            statusParts.add("有效期:$validType")
        }
        val notStarted = sendStart > 0L && now < sendStart
        val ended = sendEnd > 0L && now > sendEnd
        if (notStarted) {
            statusParts.add("未到兑换时间")
        }
        if (ended) {
            statusParts.add("兑换已结束")
        }
        val awardShouldKnow = extInfo?.optString("awardShouldKnow").orEmpty()
        val toUseAddress = extInfo?.optString("toUseAddress").orEmpty()
        val awardTabLabel = extInfo?.optString("awardTabLabel", tabLabel).orEmpty()
        val unavailable =
            item.optBoolean("hasTaken", false) ||
                item.optBoolean("hasFinished", false) ||
                remainingBudget <= 0 ||
                notStarted ||
                ended
        val unsafeByTab =
            tabLabel.equals("ONLINE_SHOPPING", true) ||
                awardTabLabel.equals("ONLINE_SHOPPING", true)
        val (baseSafety, baseReason) =
            ExchangeSafetyRules.classify(
                textValues =
                    listOf(
                        name,
                        item.optString("awardProdType"),
                        tabLabel,
                        awardTabLabel,
                        toUseAddress,
                        awardShouldKnow,
                    ),
                defaultReason = "涉及实付、下单或收货链路",
            )
        val safety =
            when {
                unavailable -> ExchangeSafety.UNAVAILABLE
                unsafeByTab -> ExchangeSafety.LOG_ONLY
                baseSafety == ExchangeSafety.LOG_ONLY -> ExchangeSafety.LOG_ONLY
                else -> ExchangeSafety.AUTO
            }
        val safetyReason =
            when {
                unavailable -> statusParts.firstOrNull().orEmpty().ifEmpty { "服务端状态不可兑换" }
                unsafeByTab -> "网购权益需手动处理"
                baseReason.isNotEmpty() -> baseReason
                else -> ""
            }
        val effectTags = ExchangeEffectCatalog.tagsFor(ExchangeEffectCatalog.SOURCE_SESAME_GRAIN, name)
        return SesameExchangeCandidate(
            item =
                ExchangeItem(
                    id = templateId,
                    name = name,
                    cost = ExchangeCost(pointText = "${pointNeeded}芝麻粒"),
                    limit =
                        ExchangeLimit(
                            stockText = "库存$remainingBudget",
                            validText = formatSesameExchangeWindow(sendStart, sendEnd),
                            statusText = statusParts.joinToString("、"),
                        ),
                    safety = safety,
                    safetyReason = safetyReason,
                    effectTags = effectTags,
                    displayMeta =
                        ExchangeEffectCatalog.displayMeta(
                            ExchangeEffectCatalog.SOURCE_SESAME_GRAIN,
                            name,
                            safety,
                            safetyReason,
                            effectTags,
                        ),
                ),
            templateId = templateId,
            pointNeeded = pointNeeded,
        )
    }

    private fun formatSesameExchangeWindow(
        startMillis: Long,
        endMillis: Long,
    ): String {
        if (startMillis <= 0L && endMillis <= 0L) {
            return ""
        }
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val startText = startMillis.takeIf { it > 0L }?.let { formatter.format(Date(it)) }.orEmpty()
        val endText = endMillis.takeIf { it > 0L }?.let { formatter.format(Date(it)) }.orEmpty()
        return when {
            startText.isNotEmpty() && endText.isNotEmpty() -> "${startText}至$endText"
            startText.isNotEmpty() -> "${startText}起"
            else -> "${endText}止"
        }
    }

    /**
     * 执行具体的芝麻粒兑换请求
     */
    private fun exchangeSesameGift(
        templateId: String,
        name: String,
    ): Boolean {
        try {
            val detailResp = JSONObject(AntSesameCreditRpcCall.queryAwardDetail(templateId))
            if (!ExchangeSafetyRules.isSuccessResponse(detailResp) &&
                !ResChecker.checkRes(TAG, "芝麻粒兑换详情查询失败:", detailResp)
            ) {
                return false
            }
            val template = detailResp.optJSONObject("data")?.optJSONObject("awardTemplateVO")
            val detailCandidate = template?.let { buildSesameExchangeCandidate(it) }
            if (detailCandidate == null) {
                Log.error(TAG, "芝麻粒兑换🛒详情缺少有效 awardTemplateVO[$name] raw=$detailResp")
                return false
            }
            if (template?.optBoolean("hasTaken", false) == true || template?.optBoolean("hasFinished", false) == true) {
                setFlagToday(StatusFlags.FLAG_SESAME_GRAIN_EXCHANGE_DONE + "::" + templateId)
                Log.sesame("芝麻粒兑换🛒[$name]已确认领取或完成")
                return true
            }
            if (detailCandidate.item.safety != ExchangeSafety.AUTO) {
                Log.sesame("芝麻粒兑换🛒详情复核跳过[${detailCandidate.item.displayName()}]#${detailCandidate.item.safetyReason}")
                return false
            }

            val resString = AntSesameCreditRpcCall.obtainAward(templateId)
            val jo = JSONObject(resString)

            if (ExchangeSafetyRules.isSuccessResponse(jo) || ResChecker.checkRes(TAG, jo)) {
                val recordId = jo.optJSONObject("data")?.optString("awardRecordId", "").orEmpty()
                if (recordId.isBlank()) {
                    Log.sesame("芝麻粒兑换🛒[请求已接受但未确认] ${detailCandidate.item.name} 未返回awardRecordId，保留后续重试机会")
                    return false
                }

                val awardDetail = JSONObject(AntSesameCreditRpcCall.queryMyAwardDetail(recordId))
                if (!ExchangeSafetyRules.isSuccessResponse(awardDetail) &&
                    !ResChecker.checkRes(TAG, "芝麻粒兑换结果查询失败:", awardDetail)
                ) {
                    Log.error(TAG, "芝麻粒兑换🛒结果查询未确认[${detailCandidate.item.name}] recordId=$recordId raw=$awardDetail")
                    return false
                }
                if (awardDetail.optJSONObject("data") == null) {
                    Log.error(TAG, "芝麻粒兑换🛒结果查询缺少详情对象[${detailCandidate.item.name}] recordId=$recordId raw=$awardDetail")
                    return false
                }

                setFlagToday(StatusFlags.FLAG_SESAME_GRAIN_EXCHANGE_DONE + "::" + templateId)
                Log.sesame("芝麻粒兑换🛒[兑换已确认] ${detailCandidate.item.name} #消耗${detailCandidate.pointNeeded}粒")
                return true
            } else {
                val errorMsg = jo.optString("resultView", resString)
                Log.error(TAG, "兑换失败[$name]: $errorMsg")
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.printStackTrace(TAG, "exchangeSesameGift 错误:", t)
        }
        return false
    }
}

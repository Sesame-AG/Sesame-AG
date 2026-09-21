package io.github.aoguai.sesameag.task.common

import android.net.Uri
import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.util.GameTask
import org.json.JSONArray
import org.json.JSONObject

/** 游戏动作合同；任务所属模块负责完成、领奖和服务端终态回查。 */
object GameCenterPlayRpcCall {
    private const val METHOD_SUBMIT_PLAY_DURATION =
        "com.alipay.gamecenteruprod.biz.rpc.v3.submitUserPlayDurationAction"
    private const val METHOD_FLOATING_BALL_CONSULT =
        "com.alipay.gamecenteruprod.biz.rpc.channeltask.floatingball.consult"
    private const val METHOD_FLOATING_BALL_COMPLETE =
        "com.alipay.gamecenteruprod.biz.rpc.channeltask.floatingball.complete"
    private const val METHOD_P2E_FLOATING_BALL_CONSULT =
        "com.alipay.gamecenteruprod.biz.rpc.p2e.gameP2eFloatingBallConsult"
    private const val METHOD_P2E_FLOATING_BALL_COMPLETE =
        "com.alipay.gamecenteruprod.biz.rpc.p2e.gameP2eFloatingBallComplete"
    private const val METHOD_P2E_QUERY_HOME_PAGE =
        "com.alipay.gamecenteruprod.biz.rpc.p2e.queryHomePage"
    private const val METHOD_P2E_QUERY_TASK_LIST =
        "com.alipay.gamecenteruprod.biz.rpc.p2e.queryTaskList"
    private const val METHOD_P2E_QUERY_FEEDS_GAME_LIST =
        "com.alipay.gamecenteruprod.biz.rpc.p2e.queryFeedsGameList"
    private const val METHOD_GAME_ENGINE_TYPE =
        "com.alipay.gamecenteruprod.biz.rpc.queryGameEngineType"
    private const val METHOD_GAME_USER_ACTION =
        "com.alipay.gamecenteruprod.biz.rpc.v3.submitUserAction"
    private const val METHOD_GAME_ASSISTANT_CONSULT =
        "com.alipay.gamecenteruprod.biz.rpc.facade.assistant.GameAssistantRpc.consult"
    private const val METHOD_GAME_COMPONENT_CONSULT =
        "com.alipay.gamecenteruprod.biz.rpc.consultGameComponent"
    private const val METHOD_GAME_COMPONENT_QUERY =
        "com.alipay.gamecenteruprod.biz.rpc.queryGameComponent"
    private const val METHOD_GAME_EVENT = "com.alipay.gameevent.biz.rpc.submitEvent"
    private const val GAME_CENTER_GIT = "9e159d58cce04c13a"

    data class Contract(
        val gameAppId: String,
        val playTime: Int,
        val source: String,
    )

    /** A server-delivered game reward opportunity, independent from draw quota. */
    data class DeliveryBenefitCandidate(
        val appId: String,
        val taskId: String,
        val title: String,
        val taskStatus: String,
        val rightTimes: Int,
        val rightTimesLimit: Int,
        val rawGame: JSONObject,
        val rawBenefit: JSONObject,
    ) {
        val key: String
            get() = "$appId:$taskId"

        val snapshotKey: String
            get() = "$key:$taskStatus:$rightTimes:$rightTimesLimit"

        val hasPendingReward: Boolean
            get() = !taskStatus.equals("RECEIVED", ignoreCase = true) && rightTimes < rightTimesLimit

        val remainingRewards: Int
            get() = (rightTimesLimit - rightTimes).coerceAtLeast(0)
    }

    /**
     * A module-owned task decision. This object only exposes evidence from the server task
     * contract; modules retain ownership of click, finish, receive, and business RPCs.
     */
    enum class TaskAction {
        DIRECT_FINISH,
        CLICK_THEN_DURATION,
        DURATION_ONLY,
        LEGACY_EXTERNAL_REPORT,
        OWNER_BUSINESS,
        DEFERRED,
    }

    data class TaskActionDecision(
        val action: TaskAction,
        val contract: Contract? = null,
        val mappedTask: GameTask? = null,
        val reason: String,
        val missingFields: List<String> = emptyList(),
    )

    data class GameTaskDescriptor(
        val roots: List<JSONObject>,
        val objects: List<JSONObject>,
        val urlParameters: Map<String, String>,
        val gameAppId: String,
        val source: String,
        val gameTaskType: String,
        val contract: Contract?,
        val mappedTask: GameTask?,
        val isGameTask: Boolean,
    ) {
        val missingDurationFields: List<String>
            get() = buildList {
                if (gameAppId.isBlank()) add("gameAppId")
                if (contract == null || contract.playTime <= 0) add("playTime")
                if (source.isBlank()) add("source")
            }.distinct()
    }

    fun describeTask(vararg roots: JSONObject?): GameTaskDescriptor {
        val rootObjects = roots.filterNotNull()
        val objects = collectTaskObjects(rootObjects)
        val urlParameters = collectTaskUrlParameters(objects)
        val gameAppId = firstNonBlank(
            objects.map { it.optString("gameAppId") },
            objects.map { it.optString("appId") },
            objects.map { it.optString("game_id") },
            urlParameters.valuesFor("gameAppId", "appId", "game_id"),
        )
        val source = firstNonBlank(
            objects.map { it.optString("source") },
            objects.map { it.optString("chInfo") },
            objects.map { it.optString("oriChInfo") },
            objects.map { it.optString("alipayFarmSource") },
            urlParameters.valuesFor("source", "chInfo", "oriChInfo", "alipayFarmSource"),
        )
        val gameTaskType = firstNonBlank(
            objects.map { it.optString("gameTaskType") },
            urlParameters.valuesFor("gameTaskType"),
        )
        val mappedTask = GameTask.fromAppId(gameAppId)
        val isGameTask = mappedTask != null ||
            gameTaskType.isNotBlank() ||
            hasP2eFloatingBallProtocol(objects) ||
            urlParameters.valuesFor("gameAppId", "game_id").isNotEmpty() ||
            objects.any { objectValue ->
                objectValue.optString("gameAppId").isNotBlank() ||
                    objectValue.optString("game_id").isNotBlank() ||
                    objectValue.optString("categorizationSecondLevel")
                    .equals("Game", ignoreCase = true) ||
                    objectValue.optJSONObject("taskCategorization")
                        ?.optString("categorizationSecondLevel")
                        ?.equals("Game", ignoreCase = true) == true
            }
        val contract = if (isGameTask && (gameTaskType.isBlank() || gameTaskType.equals("shichang", ignoreCase = true))) {
            resolveContractFrom(objects, urlParameters, gameAppId, source)
        } else null
        return GameTaskDescriptor(
            roots = rootObjects,
            objects = objects,
            urlParameters = urlParameters,
            gameAppId = gameAppId,
            source = source,
            gameTaskType = gameTaskType,
            contract = contract,
            mappedTask = mappedTask,
            isGameTask = isGameTask,
        )
    }

    fun resolveTaskAction(
        vararg roots: JSONObject?,
        clickBeforeDuration: Boolean = false,
        directFinishSupported: Boolean = false,
    ): TaskActionDecision {
        val descriptor = describeTask(*roots)
        if (descriptor.contract == null && !hasP2eFloatingBallProtocol(descriptor.objects)) {
            descriptor.mappedTask?.let { mappedTask ->
                return TaskActionDecision(
                    action = TaskAction.LEGACY_EXTERNAL_REPORT,
                    mappedTask = mappedTask,
                    reason = "verified GameTask mapping",
                )
            }
        }
        if (!descriptor.isGameTask) {
            return TaskActionDecision(
                action = TaskAction.DIRECT_FINISH.takeIf { directFinishSupported } ?: TaskAction.DEFERRED,
                reason = if (directFinishSupported) "module-provided direct completion contract" else "not a structured game task",
            )
        }
        val objects = descriptor.objects
        if (hasP2eFloatingBallProtocol(objects)) {
            return TaskActionDecision(
                action = TaskAction.DEFERRED,
                contract = descriptor.contract,
                reason = "structured P2E/floating-ball contract is owned by its module adapter",
            )
        }
        descriptor.contract?.let { contract ->
            return TaskActionDecision(
                action = if (clickBeforeDuration) TaskAction.CLICK_THEN_DURATION else TaskAction.DURATION_ONLY,
                contract = contract,
                reason = "structured duration protocol",
            )
        }
        if (descriptor.gameTaskType.equals("shichang", ignoreCase = true)) {
            return TaskActionDecision(
                action = TaskAction.DEFERRED,
                reason = "duration protocol is present but its contract is incomplete",
                missingFields = descriptor.missingDurationFields,
            )
        }
        return TaskActionDecision(
            action = TaskAction.DEFERRED,
            reason = "game task has no direct, click, duration or supported business completion contract",
        )
    }

    fun decideDurationAction(
        clickBeforeDuration: Boolean = false,
        vararg roots: JSONObject?,
    ): TaskActionDecision = resolveTaskAction(*roots, clickBeforeDuration = clickBeforeDuration)

    fun directFinishDecision(reason: String): TaskActionDecision =
        TaskActionDecision(TaskAction.DIRECT_FINISH, reason = reason)

    fun legacyExternalReportDecision(reason: String): TaskActionDecision =
        TaskActionDecision(TaskAction.LEGACY_EXTERNAL_REPORT, reason = reason)

    fun ownerBusinessDecision(reason: String): TaskActionDecision =
        TaskActionDecision(TaskAction.OWNER_BUSINESS, reason = reason)

    fun auditDetail(
        decision: TaskActionDecision,
        taskId: String,
        appId: String,
        sceneCode: String,
        rightTimes: Int,
        rightTimesLimit: Int,
    ): String =
        "taskId=$taskId appId=$appId sceneCode=$sceneCode source=${decision.contract?.source.orEmpty()} " +
            "rightTimes=$rightTimes/$rightTimesLimit action=${decision.action} reason=${decision.reason}"

    /**
     * Recursively extracts IEP delivery benefits from a game-center response. The returned
     * candidate preserves the task payload so each module can select its own completion contract.
     */
    fun collectDeliveryBenefitCandidates(source: Any?): List<DeliveryBenefitCandidate> {
        val candidates = linkedMapOf<String, DeliveryBenefitCandidate>()
        appendDeliveryBenefitCandidates(source, candidates)
        return candidates.values.toList()
    }

    private fun appendDeliveryBenefitCandidates(
        source: Any?,
        candidates: MutableMap<String, DeliveryBenefitCandidate>,
    ) {
        when (source) {
            is JSONObject -> {
                val appId = source.optString("appId")
                val title = source.optString("title").ifBlank { appId }
                val benefits = source.optJSONArray("deliveryBenefitList")
                if (appId.isNotBlank() && benefits != null) {
                    for (index in 0 until benefits.length()) {
                        val benefit = benefits.optJSONObject(index) ?: continue
                        if (!benefit.optString("benefitType").equals("IEP_REQUEST", ignoreCase = true)) {
                            continue
                        }
                        val tracer = benefit.optString("iepTaskTracer")
                        val taskId = benefit.optString("iepTaskId")
                            .ifBlank { extractTracerField(tracer, "taskType") }
                        val rightTimesLimit = benefit.optInt("rightTimesLimit", 0)
                        if (taskId.isBlank() || rightTimesLimit <= 0) {
                            continue
                        }
                        val taskStatus = benefit.optString("taskStatus")
                            .ifBlank { extractTracerField(tracer, "taskStatus") }
                        val candidate =
                            DeliveryBenefitCandidate(
                                appId = appId,
                                taskId = taskId,
                                title = title,
                                taskStatus = taskStatus,
                                rightTimes = benefit.optInt("rightTimes", 0).coerceAtLeast(0),
                                rightTimesLimit = rightTimesLimit,
                                rawGame = source,
                                rawBenefit = benefit,
                            )
                        candidates.putIfAbsent(candidate.key, candidate)
                    }
                }
                val keys = source.keys()
                while (keys.hasNext()) {
                    appendDeliveryBenefitCandidates(source.opt(keys.next()), candidates)
                }
            }

            is JSONArray -> {
                for (index in 0 until source.length()) {
                    appendDeliveryBenefitCandidates(source.opt(index), candidates)
                }
            }
        }
    }

    private fun extractTracerField(tracer: String, field: String): String =
        tracer.split('~')
            .firstOrNull { it.startsWith("$field:") }
            ?.substringAfter(':')
            .orEmpty()

    data class P2eFloatingBallContract(
        val sceneId: String,
        val taskId: String,
        val moduleId: String,
        val guideType: String,
        val gameAppId: String,
        val gameId: String,
        val gameModuleId: String,
        val source: String,
        val oriChInfo: String,
        val trafficDriverId: String,
        val floatingBallTypeList: List<String>,
        val componentChannel: String,
        val componentScene: String,
        val durationSeconds: Int = 0,
        val gameVersion: String,
    )

    /**
     * The three request objects are captured from the current P2E task entry. They are replayed
     * unchanged so a task flow never fabricates page-session or identity parameters.
     */
    data class P2ePageRequests(
        val homePage: JSONObject,
        val taskList: JSONObject,
        val feedsGameList: JSONObject,
    )

    data class P2ePageSession(
        val contract: P2eFloatingBallContract,
        val homePageResponse: JSONObject,
        val taskListResponse: JSONObject,
        val feedsGameListResponse: JSONObject,
    )

    data class P2ePageSessionResolution(
        val raw: String,
        val response: JSONObject?,
        val session: P2ePageSession?,
        val missingFields: List<String>,
        val requestsAccepted: Boolean,
        val failureType: TaskRpcFailureType,
    )

    /** The duration endpoint only acknowledges the report; it never proves task completion. */
    data class DurationAck(
        val raw: String,
        val response: JSONObject?,
        val accepted: Boolean,
        val failureType: TaskRpcFailureType,
    )

    data class FloatingBallConsultAck(
        val raw: String,
        val response: JSONObject?,
        val timeSeconds: Int?,
        val accepted: Boolean,
        val failureType: TaskRpcFailureType,
    )

    data class P2eFloatingBallConsultAck(
        val raw: String,
        val response: JSONObject?,
        val timeSeconds: Int?,
        val gameEngineType: String?,
        val accepted: Boolean,
        val failureType: TaskRpcFailureType,
    )

    data class DurationBatchAck(
        val raw: String,
        val acknowledgements: List<DurationAck>,
        val totalChunks: Int,
        val acceptedChunks: Int,
        val accepted: Boolean,
        val failureType: TaskRpcFailureType,
    )

    data class FloatingBallAck(
        val raw: String,
        val response: JSONObject?,
        val accepted: Boolean,
        val failureType: TaskRpcFailureType,
    )

    /**
     * Resolves a duration-report contract from the structured task payload. The descriptor
     * intentionally keeps the protocol classification separate from module-owned completion.
     */
    fun resolveContract(vararg roots: JSONObject?): Contract? = describeTask(*roots).contract

    private fun collectTaskObjects(roots: List<JSONObject>): List<JSONObject> {
        val objects = mutableListOf<JSONObject>()
        fun addObject(value: JSONObject?) {
            if (value != null && objects.none { it === value }) objects.add(value)
        }
        fun parseObject(value: Any?): JSONObject? =
            when (value) {
                is JSONObject -> value
                is String -> value.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                else -> null
            }

        roots.forEach(::addObject)
        var index = 0
        while (index < objects.size) {
            val current = objects[index++]
            listOf(
                "extend",
                "prodPlayParam",
                "taskDisplayConfig",
                "floatBallConfig",
                "task",
                "bizInfo",
                "taskCategorization",
                "categorizationParamModel",
                "p2ePageRequests",
                "p2ePageSession",
                "p2eHomePageRequest",
                "p2eTaskListRequest",
                "p2eFeedsGameListRequest",
            ).forEach { key -> addObject(parseObject(current.opt(key))) }
        }
        return objects
    }

    private fun collectTaskUrlParameters(objects: List<JSONObject>): Map<String, String> {
        val parameters = linkedMapOf<String, String>()
        val queue = ArrayDeque<String>()
        objects.forEach { objectValue ->
            listOf("targetUrl", "actionUrl", "jumpUrl", "jumpLink", "pageUrl", "taskJumpUrl")
                .map(objectValue::optString)
                .filterTo(queue) { it.isNotBlank() }
        }
        val visited = linkedSetOf<String>()
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (!visited.add(url)) continue
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: continue
            uri.queryParameterNames.forEach { key ->
                uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let { parameters[key] = it }
            }
            listOf("url", "sourceUrl", "schema").forEach { key ->
                uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let(queue::add)
            }
        }
        return parameters
    }

    private fun hasP2eFloatingBallProtocol(objects: List<JSONObject>): Boolean =
        objects.any { value ->
            value.optString("floatingBallTypeList").contains("P2E_GAME_BROWSE_TASK_FLOATING_BALL", ignoreCase = true) ||
                value.optJSONArray("floatingBallTypeList")?.let { list ->
                    (0 until list.length()).any { index ->
                        list.optString(index).equals("P2E_GAME_BROWSE_TASK_FLOATING_BALL", ignoreCase = true)
                    }
                } == true ||
                value.has("p2ePageRequests") ||
                value.has("p2eHomePageRequest") ||
                value.has("p2eTaskListRequest") ||
                value.has("p2eFeedsGameListRequest")
        }

    private fun resolveContractFrom(
        objects: List<JSONObject>,
        urlParameters: Map<String, String>,
        gameAppId: String,
        source: String,
    ): Contract? {
        val seconds = objects.asSequence().flatMap { value ->
            sequenceOf(
                value.optInt("playTime", 0),
                value.optInt("timeCount", 0),
                value.optInt("floatBallDuration", 0),
                value.optInt("requiredDuration", 0),
                value.optInt("duration", 0),
            )
        }.firstOrNull { it > 0 }
        val playTime = seconds ?: objects.asSequence()
            .map { it.optLong("vstTime", 0L) }
            .firstOrNull { it > 0L }
            ?.let { durationMillis ->
                ((durationMillis + 999L) / 1000L + 1L)
                    .takeIf { it <= Int.MAX_VALUE.toLong() }
                    ?.toInt()
            }
            ?: urlParameters.valuesFor("playTime", "timeCount", "floatBallDuration", "requiredDuration", "duration")
                .mapNotNull { it.toIntOrNull()?.takeIf { seconds -> seconds > 0 } }
                .firstOrNull()
            ?: return null
        if (gameAppId.isBlank() || source.isBlank()) return null
        return Contract(gameAppId, playTime, source)
    }

    private fun Map<String, String>.valuesFor(vararg keys: String): List<String> =
        keys.mapNotNull { key -> get(key)?.trim()?.takeIf { it.isNotBlank() } }

    private fun firstNonBlank(vararg candidates: Iterable<String>): String =
        candidates.asSequence()
            .flatMap { values -> values.asSequence().map(String::trim) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    fun submit(contract: Contract): String =
        RequestManager.requestString(
            METHOD_SUBMIT_PLAY_DURATION,
            JSONArray().put(
                JSONObject()
                    .put("gameAppId", contract.gameAppId)
                    .put("playTime", contract.playTime)
                    .put("source", contract.source)
                    .put("statisticTag", ""),
            ).toString(),
        )

    fun submitForAck(contract: Contract): DurationAck {
        val raw = submit(contract)
        val response = runCatching { JSONObject(raw) }.getOrNull()
        return DurationAck(
            raw = raw,
            response = response,
            accepted = response?.let(::isAccepted) == true,
            failureType = classifyResponse(raw, response),
        )
    }

    fun submitP2eDurationForAck(
        contract: P2eFloatingBallContract,
        playSource: String = contract.source,
    ): DurationBatchAck {
        val totalSeconds = (contract.durationSeconds.toLong() + 1L)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
        val chunks = mutableListOf<Int>()
        var remaining = totalSeconds
        var firstChunk = true
        while (remaining > 0) {
            val chunk = minOf(if (firstChunk) 31 else 30, remaining)
            chunks += chunk
            remaining -= chunk
            firstChunk = false
        }

        val acknowledgements = mutableListOf<DurationAck>()
        for (chunk in chunks) {
            val acknowledgement = submitP2eChunkForAck(
                Contract(
                    gameAppId = contract.gameAppId,
                    playTime = chunk,
                    source = playSource,
                )
            )
            acknowledgements += acknowledgement
            if (!acknowledgement.accepted) {
                break
            }
        }
        val failedAcknowledgement = acknowledgements.firstOrNull { !it.accepted }
        return DurationBatchAck(
            raw = acknowledgements.joinToString(" | ") { it.raw },
            acknowledgements = acknowledgements,
            totalChunks = chunks.size,
            acceptedChunks = acknowledgements.count { it.accepted },
            accepted = acknowledgements.size == chunks.size && acknowledgements.all { it.accepted },
            failureType = failedAcknowledgement?.failureType ?: TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
        )
    }

    private fun submitP2eChunkForAck(contract: Contract): DurationAck {
        // Captured P2E duration responses require top-level success=true; generic ACK aliases stay for legacy callers.
        val raw = submit(contract)
        val response = runCatching { JSONObject(raw) }.getOrNull()
        return DurationAck(
            raw = raw,
            response = response,
            accepted = response?.optBoolean("success", false) == true,
            failureType = classifyResponse(raw, response),
        )
    }

    fun consultP2eFloatingBall(contract: P2eFloatingBallContract): P2eFloatingBallConsultAck {
        val raw = requestRaw(
            METHOD_P2E_FLOATING_BALL_CONSULT,
            JSONObject()
                .put("__git", GAME_CENTER_GIT)
                .put("gameId", contract.gameId)
                .put("gameModuleId", contract.gameModuleId)
                .put("source", contract.source)
                .put("trafficDriverId", contract.trafficDriverId),
        )
        val response = runCatching { JSONObject(raw) }.getOrNull()
        val timeSeconds = response?.let(::extractTimeSeconds)?.takeIf { it > 0 }
        val gameEngineType = response?.let(::extractGameEngineType)
        return P2eFloatingBallConsultAck(
            raw = raw,
            response = response,
            timeSeconds = timeSeconds,
            gameEngineType = gameEngineType,
            accepted = response?.optBoolean("success", false) == true && timeSeconds != null,
            failureType = classifyResponse(raw, response),
        )
    }

    fun completeP2eFloatingBall(contract: P2eFloatingBallContract): FloatingBallAck {
        val raw = requestRaw(
            METHOD_P2E_FLOATING_BALL_COMPLETE,
            JSONObject()
                .put("floatingBallTypeList", JSONArray().apply {
                    contract.floatingBallTypeList.filter { it.isNotBlank() }.forEach(::put)
                })
                .put("gameId", contract.gameId)
                .put("gameModuleId", contract.gameModuleId)
                .put("oriChInfo", contract.oriChInfo)
                .put("source", contract.source)
                .put("trafficDriverId", contract.trafficDriverId),
        )
        val response = runCatching { JSONObject(raw) }.getOrNull()
        return FloatingBallAck(
            raw = raw,
            response = response,
            accepted = response?.optBoolean("success", false) == true,
            failureType = classifyResponse(raw, response),
        )
    }

    fun initializeP2eFloatingBallGame(contract: P2eFloatingBallContract): FloatingBallAck {
        var ack = requestAck(
            METHOD_GAME_EVENT,
            JSONObject()
                .put("appId", contract.gameAppId)
                .put("eventAttrMap", JSONObject()
                    .put("ALIVE_ENTER", "0")
                    .put("CH_INFO", contract.source)
                    .put("CPS_ID", "unknown")
                    .put("GAME_VERSION", contract.gameVersion)
                    .put("PALADINX_VERSION", "2.2.5")
                    .put("PLAY_SCENE", "NORMAL")
                    .put("SCENE_ID", contract.componentChannel))
                .put("eventId", "GAME_FIRST_FRAME")
                .put("idempotentNo", "platform_${System.currentTimeMillis()}_${contract.gameAppId}_first_frame")
                .put("source", contract.source),
        )
        if (!ack.accepted) return ack
        ack = requestAck(
            METHOD_GAME_USER_ACTION,
            JSONObject()
                .put("actionCode", "enterGame")
                .put("gameId", contract.gameAppId)
                .put("paladinxVersion", "2.2.5")
                .put("source", "gameFramework"),
        )
        if (!ack.accepted) return ack
        ack = requestAck(
            METHOD_GAME_ASSISTANT_CONSULT,
            JSONObject()
                .put("appId", contract.gameAppId)
                .put("assistantVersion", "3.0.0")
                .put("deviceLevel", "high")
                .put("sceneCode", "GAME_MSG")
                .put("source", contract.source),
        )
        if (!ack.accepted) return ack
        ack = requestAck(
            METHOD_GAME_COMPONENT_CONSULT,
            JSONObject()
                .put("appId", contract.gameAppId)
                .put("channel", contract.componentChannel)
                .put("scene", contract.componentScene)
                .put("source", contract.source),
        )
        if (!ack.accepted) return ack
        ack = queryGameComponent(
            contract.gameAppId,
            contract.source,
            channel = contract.componentChannel,
            scene = contract.componentScene,
            setHead = false,
        )
        if (!ack.accepted) return ack
        return queryGameComponent(
            contract.gameAppId,
            contract.source,
            channel = contract.componentChannel,
            scene = contract.componentScene,
            setHead = true,
        )
    }

    fun submitGameLoadingCompletedEvent(contract: P2eFloatingBallContract): FloatingBallAck =
        requestAck(
            METHOD_GAME_EVENT,
            JSONObject()
                .put("appId", contract.gameAppId)
                .put("eventAttrMap", JSONObject()
                    .put("ALIVE_ENTER", "0")
                    .put("CH_INFO", contract.source)
                    .put("CPS_ID", "unknown")
                    .put("GAME_VERSION", contract.gameVersion)
                    .put("PALADINX_VERSION", "2.2.5")
                    .put("PLAY_SCENE", "NORMAL")
                    .put("SCENE_ID", contract.componentChannel))
                .put("eventId", "loading_completed")
                .put("idempotentNo", "platform_${System.currentTimeMillis()}_${contract.gameAppId}_1")
                .put("source", contract.source),
        )

    /**
     * Opens only the P2E page represented by the current task contract. The request maps are
     * supplied by the entry payload and replayed verbatim; no recommendation, feeds filter, or
     * fallback game identity is fabricated here.
     */
    fun resolveP2ePageSession(
        entrance: P2eFloatingBallContract,
        requests: P2ePageRequests,
    ): P2ePageSessionResolution {
        val requestFieldErrors = mutableListOf<String>()
        listOf(
            "homePage" to requests.homePage,
            "taskList" to requests.taskList,
            "feedsGameList" to requests.feedsGameList,
        ).forEach { (name, request) ->
            val requestSource = request.optString("source").trim()
            if (requestSource.isBlank()) {
                requestFieldErrors += "$name.source"
            } else if (requestSource != entrance.source) {
                requestFieldErrors += "$name.source_mismatch"
            }
        }
        if (requestFieldErrors.isNotEmpty()) {
            return P2ePageSessionResolution(
                raw = "",
                response = null,
                session = null,
                missingFields = requestFieldErrors,
                requestsAccepted = false,
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
            )
        }

        val acknowledgements = listOf(
            METHOD_P2E_QUERY_HOME_PAGE to requestAck(METHOD_P2E_QUERY_HOME_PAGE, JSONObject(requests.homePage.toString())),
            METHOD_P2E_QUERY_TASK_LIST to requestAck(METHOD_P2E_QUERY_TASK_LIST, JSONObject(requests.taskList.toString())),
            METHOD_P2E_QUERY_FEEDS_GAME_LIST to requestAck(METHOD_P2E_QUERY_FEEDS_GAME_LIST, JSONObject(requests.feedsGameList.toString())),
        )
        val raw = acknowledgements.joinToString(" | ") { (method, ack) -> "$method=${ack.raw}" }
        val failed = acknowledgements.firstOrNull { (_, ack) -> !ack.accepted }
        if (failed != null) {
            val (method, ack) = failed
            return P2ePageSessionResolution(
                raw = raw,
                response = ack.response,
                session = null,
                missingFields = listOf(method),
                requestsAccepted = false,
                failureType = ack.failureType,
            )
        }
        val responses = acknowledgements.mapNotNull { (_, ack) -> ack.response }
        val resolvedContract = resolveP2ePageContract(entrance, responses)
        val missingFields = missingP2eCompletionFields(resolvedContract)
        return P2ePageSessionResolution(
            raw = raw,
            response = responses.lastOrNull(),
            session = resolvedContract.takeIf { missingFields.isEmpty() }?.let { contract ->
                P2ePageSession(
                    contract = contract,
                    homePageResponse = responses[0],
                    taskListResponse = responses[1],
                    feedsGameListResponse = responses[2],
                )
            },
            missingFields = missingFields,
            requestsAccepted = true,
            failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
        )
    }

    private fun resolveP2ePageContract(
        entrance: P2eFloatingBallContract,
        responses: List<JSONObject>,
    ): P2eFloatingBallContract {
        val candidates = responses
            .flatMap(::extractP2ePageGameCandidates)
            .filter { candidate ->
                (entrance.gameAppId.isBlank() || candidate.gameAppId == entrance.gameAppId) &&
                    (entrance.gameId.isBlank() || candidate.gameId == entrance.gameId) &&
                    (entrance.gameModuleId.isBlank() || candidate.gameModuleId.isBlank() || candidate.gameModuleId == entrance.gameModuleId)
            }
            .distinctBy { candidate ->
                "${candidate.gameAppId}|${candidate.gameId}|${candidate.gameModuleId}|${candidate.gameVersion}"
            }
        val candidate = candidates.singleOrNull()
        return entrance.copy(
            gameAppId = entrance.gameAppId.ifBlank { candidate?.gameAppId.orEmpty() },
            gameId = entrance.gameId.ifBlank { candidate?.gameId.orEmpty() },
            gameModuleId = entrance.gameModuleId.ifBlank { candidate?.gameModuleId.orEmpty() },
            gameVersion = entrance.gameVersion.ifBlank { candidate?.gameVersion.orEmpty() },
        )
    }

    private fun missingP2eCompletionFields(contract: P2eFloatingBallContract): List<String> =
        buildList {
            if (contract.sceneId.isBlank()) add("sceneId")
            if (contract.taskId.isBlank()) add("taskId")
            if (contract.moduleId.isBlank()) add("moduleId")
            if (contract.guideType.isBlank()) add("guideType")
            if (contract.source.isBlank()) add("source")
            if (contract.gameAppId.isBlank()) add("gameAppId")
            if (contract.gameId.isBlank()) add("gameId")
            if (contract.gameModuleId.isBlank()) add("gameModuleId")
            if (contract.gameVersion.isBlank()) add("gameVersion")
            if (contract.componentChannel.isBlank()) add("componentChannel")
            if (contract.componentScene.isBlank()) add("componentScene")
            if (contract.oriChInfo.isBlank()) add("oriChInfo")
            if (contract.trafficDriverId.isBlank()) add("trafficDriverId")
            if (contract.floatingBallTypeList.none { it.isNotBlank() }) add("floatingBallTypeList")
        }

    fun initializeFloatingBallGame(
        sceneId: String,
        source: String,
        gameAppId: String,
    ): FloatingBallAck {
        var ack =
            requestAck(
                METHOD_GAME_ENGINE_TYPE,
                JSONObject()
                    .put("__git", GAME_CENTER_GIT)
                    .put("appId", gameAppId)
                    .put("sceneId", sceneId),
            )
        if (!ack.accepted) return ack
        ack =
            requestAck(
                METHOD_GAME_USER_ACTION,
                JSONObject()
                    .put("actionCode", "enterGame")
                    .put("gameId", gameAppId)
                    .put("paladinxVersion", "2.2.5")
                    .put("source", "gameFramework"),
            )
        if (!ack.accepted) return ack
        ack =
            requestAck(
                METHOD_GAME_ASSISTANT_CONSULT,
                JSONObject()
                    .put("appId", gameAppId)
                    .put("assistantVersion", "3.0.0")
                    .put("deviceLevel", "high")
                    .put("sceneCode", "GAME_MSG")
                    .put("source", source),
            )
        if (!ack.accepted) return ack
        ack =
            requestAck(
                METHOD_GAME_COMPONENT_CONSULT,
                JSONObject()
                    .put("appId", gameAppId)
                    .put("channel", sceneId)
                    .put("scene", sceneId)
                    .put("source", source),
            )
        if (!ack.accepted) return ack
        ack = queryGameComponent(
            gameAppId,
            source,
            channel = sceneId,
            scene = sceneId,
            setHead = false,
        )
        if (!ack.accepted) return ack
        return queryGameComponent(
            gameAppId,
            source,
            channel = sceneId,
            scene = sceneId,
            setHead = true,
        )
    }

    private data class P2ePageGameCandidate(
        val gameAppId: String,
        val gameId: String,
        val gameModuleId: String,
        val gameVersion: String,
    )

    private fun extractP2ePageGameCandidates(response: JSONObject): List<P2ePageGameCandidate> {
        val objects = mutableListOf<JSONObject>()
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> {
                    objects += value
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        collect(value.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        collect(value.opt(index))
                    }
                }
            }
        }
        collect(response)
        return objects.mapNotNull { value ->
            fun firstValue(vararg keys: String): String =
                keys.asSequence()
                    .map { key -> value.optString(key).trim() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            val gameAppId = firstValue("gameAppId", "appId", "game_id")
            val gameId = firstValue("gameId", "gid")
            if (gameAppId.isBlank() || gameId.isBlank()) {
                return@mapNotNull null
            }
            P2ePageGameCandidate(
                gameAppId = gameAppId,
                gameId = gameId,
                gameModuleId = firstValue("gameModuleId", "moduleId"),
                gameVersion = firstValue("gameVersion", "gameVer", "version"),
            )
        }
    }

    private fun queryGameComponent(
        gameAppId: String,
        source: String,
        channel: String,
        scene: String,
        setHead: Boolean,
    ): FloatingBallAck =
        requestAck(
            METHOD_GAME_COMPONENT_QUERY,
            JSONObject()
                .put("appId", gameAppId)
                .put("chInfo", source)
                .put("channel", channel)
                .put("closeAd", false)
                .put("closeCntMap", JSONObject())
                .put("dayAdViewCnt", 0)
                .put("deviceLevel", "high")
                .put("monthAdViewCnt", 0)
                .put("notUnityDeviceLevel", "high")
                .apply {
                    if (!setHead) {
                        put(
                            "panelLaunchableCheckMap",
                            JSONObject()
                                .put("SET_HEAD_TASK", true)
                                .put("SUBSCRIBE_TASK", false)
                                .put("THIRD_PARTY_GAME_ADD_DESKTOP", true),
                        )
                    }
                }
                .put("reqType", if (setHead) "" else "START_APP_FIRST_REQ")
                .put("scene", scene)
                .put("setHead", setHead)
                .put("sourceTab", "buoy")
                .put("unityDeviceLevel", "high")
                .put("virtualActivity", false),
        )

    fun queryExternalGameCenter(
        sceneId: String,
        moduleId: String,
        guideType: String,
        source: String,
        passThrough: String,
    ): FloatingBallAck = requestAck(
        "com.alipay.gamecenteruprod.biz.rpc.external.gamecenter.queryHomePage",
        JSONObject()
            .put("__git", GAME_CENTER_GIT)
            .put("channelTaskPassThrough", passThrough)
            .put("sceneId", sceneId)
            .put("moduleId", moduleId)
            .put("guideType", guideType)
            .put("source", source)
            .put("deviceLevel", "high")
            .put("unityDeviceLevel", "high"),
    )

    fun completeExternalBrowseTask(sceneId: String, sceneExtInfo: String): FloatingBallAck = requestAck(
        "com.alipay.gamecenteruprod.biz.rpc.external.gamecenter.completeBrowseTask",
        JSONObject().put("sceneId", sceneId).put("sceneExtInfo", sceneExtInfo),
    )

    fun queryExternalRecommendGames(sceneId: String, source: String): FloatingBallAck = requestAck(
        "com.alipay.gamecenteruprod.biz.rpc.external.gamecenter.queryRecommendGames",
        JSONObject()
            .put("__git", GAME_CENTER_GIT)
            .put("sceneId", sceneId)
            .put("source", source)
            .put("creativeId", "")
            .put("topGameId", "")
            .put("deviceLevel", "high")
            .put("unityDeviceLevel", "high"),
    )

    fun queryGameCenterHome(source: String, trafficDriverId: String): FloatingBallAck = requestAck(
        "com.alipay.gamecenterhome.biz.rpc.queryHomePage",
        JSONObject()
            .put("source", source)
            .put("sourceTab", "index")
            .put("trafficDriverId", trafficDriverId),
    )

    fun consultGameFloatingBall(
        gameId: String,
        gameModuleId: String,
        source: String,
        trafficDriverId: String,
    ): FloatingBallConsultAck {
        val raw = requestRaw(
            "com.alipay.gamecenteruprod.biz.rpc.floatingball.consult",
            JSONObject()
                .put("gameId", gameId)
                .put("gameModuleId", gameModuleId)
                .put("source", source)
                .put("trafficDriverId", trafficDriverId),
        )
        val response = runCatching { JSONObject(raw) }.getOrNull()
        return FloatingBallConsultAck(
            raw = raw,
            response = response,
            timeSeconds = response?.let(::extractTimeSeconds)?.takeIf { it > 0 },
            accepted = response?.let(::isAccepted) == true,
            failureType = classifyResponse(raw, response),
        )
    }

    fun completeGameFloatingBall(
        gameId: String,
        gameModuleId: String,
        source: String,
        trafficDriverId: String,
        floatingBallTypeList: JSONArray,
    ): FloatingBallAck = requestAck(
        "com.alipay.gamecenteruprod.biz.rpc.floatingball.complete",
        JSONObject()
            .put("gameId", gameId)
            .put("gameModuleId", gameModuleId)
            .put("source", source)
            .put("oriChInfo", source)
            .put("trafficDriverId", trafficDriverId)
            .put("floatingBallTypeList", floatingBallTypeList),
    )

    fun consultFloatingBall(
        passThrough: String,
        source: String,
    ): FloatingBallConsultAck {
        val raw =
            RequestManager.requestString(
                METHOD_FLOATING_BALL_CONSULT,
                JSONArray().put(
                    JSONObject()
                        .put("__git", GAME_CENTER_GIT)
                        .put("passThrough", passThrough)
                        .put("source", source),
                ).toString(),
            )
        val response = runCatching { JSONObject(raw) }.getOrNull()
        val timeSeconds = response?.let(::extractTimeSeconds)?.takeIf { it > 0 }
        return FloatingBallConsultAck(
            raw = raw,
            response = response,
            timeSeconds = timeSeconds,
            accepted = response?.let(::isAccepted) == true && timeSeconds != null,
            failureType = classifyResponse(raw, response),
        )
    }

    fun completeFloatingBall(
        passThrough: String,
        sceneId: String,
    ): FloatingBallAck {
        val raw =
            RequestManager.requestString(
                METHOD_FLOATING_BALL_COMPLETE,
                JSONArray().put(
                    JSONObject()
                        .put("passThrough", passThrough)
                        .put("sceneId", sceneId),
                ).toString(),
            )
        val response = runCatching { JSONObject(raw) }.getOrNull()
        return FloatingBallAck(
            raw = raw,
            response = response,
            accepted = response?.let(::isAccepted) == true,
            failureType = classifyResponse(raw, response),
        )
    }

    private fun extractTimeSeconds(response: JSONObject): Int =
        sequenceOf(
            response.optInt("timeSeconds", 0),
            response.optJSONObject("data")?.optInt("timeSeconds", 0) ?: 0,
            response.optJSONObject("data")?.optJSONObject("floatingBallVO")?.optInt("timeSeconds", 0) ?: 0,
            response.optJSONObject("resData")?.optInt("timeSeconds", 0) ?: 0,
            response.optJSONObject("resData")?.optJSONObject("data")?.optInt("timeSeconds", 0) ?: 0,
            response.optJSONObject("resData")?.optJSONObject("data")?.optJSONObject("floatingBallVO")?.optInt("timeSeconds", 0) ?: 0,
        ).firstOrNull { it > 0 } ?: 0

    private fun extractGameEngineType(response: JSONObject): String? =
        sequenceOf(
            response.optString("gameEngineType"),
            response.optJSONObject("data")?.optString("gameEngineType").orEmpty(),
            response.optJSONObject("data")?.optJSONObject("floatingBallVO")?.optString("gameEngineType").orEmpty(),
            response.optJSONObject("resData")?.optString("gameEngineType").orEmpty(),
            response.optJSONObject("resData")?.optJSONObject("data")?.optString("gameEngineType").orEmpty(),
            response.optJSONObject("resData")?.optJSONObject("data")?.optJSONObject("floatingBallVO")?.optString("gameEngineType").orEmpty(),
        ).firstOrNull { it.isNotBlank() }

    private fun requestRaw(
        method: String,
        request: JSONObject,
    ): String = RequestManager.requestString(method, JSONArray().put(request).toString())

    private fun requestAck(
        method: String,
        request: JSONObject,
    ): FloatingBallAck {
        val raw = requestRaw(method, request)
        val response = runCatching { JSONObject(raw) }.getOrNull()
        return FloatingBallAck(
            raw = raw,
            response = response,
            accepted = response?.let(::isAccepted) == true,
            failureType = classifyResponse(raw, response),
        )
    }

    private fun classifyResponse(
        raw: String,
        response: JSONObject?,
    ): TaskRpcFailureType =
        if (response == null) TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW else classify(raw)

    fun classify(raw: String): TaskRpcFailureType =
        runCatching {
            val response = JSONObject(raw)
            when {
                response.optBoolean("success") || response.optBoolean("isSuccess") -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
                response.optString("resultCode") == "400000040" -> TaskRpcFailureType.NON_RETRYABLE_INVALID
                response.optBoolean("retryable") || response.optBoolean("retriable") -> TaskRpcFailureType.RETRYABLE_RPC
                response.optString("resultCode") == "OP_REPEAT_CHECK" -> TaskRpcFailureType.RETRYABLE_RPC
                else -> TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            }
        }.getOrDefault(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW)

    fun isAccepted(response: JSONObject): Boolean =
        response.optBoolean("success") ||
            response.optBoolean("isSuccess") ||
            response.optString("resultCode").equals("100", ignoreCase = true) ||
            response.optString("resultCode").equals("200", ignoreCase = true) ||
            response.optString("resultCode").equals("SUCCESS", ignoreCase = true) ||
            response.optString("code").equals("100000000", ignoreCase = true)
}

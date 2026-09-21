package io.github.aoguai.sesameag.task.antForestPatrol

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.task.exchange.ExchangeEffectNeed
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenishResult
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenisher
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object LegacyPatrolWorkflow {
    private const val TAG = "AntForestPatrol"

    private fun unwrapResData(response: JSONObject): JSONObject = response.optJSONObject("resData") ?: response

    private data class PatrolRecordInfo(
        val patrolId: Int,
        val startDate: Long,
        val reserveName: String,
        val patrolConfig: JSONObject,
        val userPatrol: JSONObject
    )

    private data class PatrolTargetRecord(
        val patrolId: Int,
        val reserveName: String,
        val reason: String
    )

    private enum class PatrolPieceState {
        MISSING,
        COMPLETE,
        UNKNOWN,
    }

    private fun collectPatrolRecordInfo(records: JSONArray): List<PatrolRecordInfo> {
        val recordInfos = mutableListOf<PatrolRecordInfo>()
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            val patrolConfig = record.optJSONObject("patrolConfig") ?: continue
            val userPatrol = record.optJSONObject("userPatrol") ?: JSONObject()
            val patrolId = patrolConfig.optInt("patrolId", userPatrol.optInt("patrolId", 0))
            if (patrolId <= 0) {
                continue
            }
            recordInfos.add(
                PatrolRecordInfo(
                    patrolId = patrolId,
                    startDate = patrolConfig.optLong("startDate", userPatrol.optLong("startDate", 0L)),
                    reserveName = patrolConfig.optString("reserveName").ifBlank { "保护地$patrolId" },
                    patrolConfig = patrolConfig,
                    userPatrol = userPatrol
                )
            )
        }
        return recordInfos
    }

    private fun hasUnreachedPatrolNode(userPatrol: JSONObject): Boolean {
        val unreachedNodeCount = userPatrol.optInt("unreachedNodeCount", -1)
        if (unreachedNodeCount >= 0) {
            return unreachedNodeCount > 0
        }
        val unreachedNodes = userPatrol.optJSONArray("unreachedNodes")
        return unreachedNodes != null && unreachedNodes.length() > 0
    }

    private fun isNormalPatrolAnimal(animal: JSONObject): Boolean {
        if (animal.optInt("id", -1) <= 0 || !animal.optString("status").equals("ONLINE", true)) {
            return false
        }
        if (animal.optBoolean("limited", false) ||
            animal.optBoolean("limit", false) ||
            animal.optBoolean("special", false)
        ) {
            return false
        }
        val extInfo = animal.optJSONObject("extInfo")
        if (extInfo != null &&
            (extInfo.optBoolean("limited", false) ||
                extInfo.optBoolean("limit", false) ||
                extInfo.optBoolean("special", false) ||
                extInfo.optString("shortDesc").contains("限定"))
        ) {
            return false
        }
        return true
    }

    private fun normalPatrolAnimalIds(patrolConfig: JSONObject): Set<Int> {
        val patrolId = patrolConfig.optInt("patrolId", 0)
        val animals = patrolConfig.optJSONArray("animals")
        if (animals == null || animals.length() == 0) {
            Log.forestPatrol("巡护地图缺少动物列表[patrolId=$patrolId]，不以图鉴缺片优先")
            return emptySet()
        }
        val animalIds = mutableSetOf<Int>()
        for (i in 0 until animals.length()) {
            val animal = animals.optJSONObject(i) ?: continue
            if (!isNormalPatrolAnimal(animal)) {
                val shortDesc = animal.optJSONObject("extInfo")?.optString("shortDesc").orEmpty()
                if (shortDesc.contains("限定")) {
                    Log.forestPatrol(
                        "巡护图鉴排除活动动物[patrolId=$patrolId," +
                            "animalId=${animal.optInt("id", -1)}," +
                            "name=${animal.optString("name", "未知")},shortDesc=$shortDesc]"
                    )
                }
                continue
            }
            val animalId = animal.optInt("id", -1)
            if (animalId > 0) {
                animalIds.add(animalId)
            }
        }
        if (animalIds.isEmpty()) {
            Log.forestPatrol("巡护地图无可证明的常驻在线动物[patrolId=$patrolId]，不以图鉴缺片优先")
        }
        return animalIds
    }

    private fun getPatrolAnimalPieceState(record: PatrolRecordInfo): PatrolPieceState {
        val normalAnimalIds = normalPatrolAnimalIds(record.patrolConfig)
        if (normalAnimalIds.isEmpty()) {
            return PatrolPieceState.UNKNOWN
        }
        return try {
            val response = unwrapResData(JSONObject(AntForestPatrolRpcCall.queryAnimalAndPiece(0, record.patrolId)))
            if (!ResChecker.checkRes(TAG, "查询巡护图鉴失败:", response)) {
                Log.forestPatrol("巡护图鉴检查失败[${record.reserveName}/${record.patrolId}]: ${response.optString("resultDesc", response.optString("desc"))}")
                return PatrolPieceState.UNKNOWN
            }
            val animalProps = response.optJSONArray("animalProps")
            if (animalProps == null || animalProps.length() == 0) {
                Log.forestPatrol("巡护图鉴缺少动物碎片列表[${record.reserveName}/${record.patrolId}]")
                return PatrolPieceState.UNKNOWN
            }

            var matched = false
            for (i in 0 until animalProps.length()) {
                val animalProp = animalProps.optJSONObject(i) ?: continue
                val animal = animalProp.optJSONObject("animal") ?: continue
                val animalId = animal.optInt("id", -1)
                if (!normalAnimalIds.contains(animalId)) {
                    continue
                }
                matched = true
                if (!animalProp.has("main") || animalProp.isNull("main")) {
                    Log.forestPatrol(
                        "巡护图鉴确认可推进缺片[${record.reserveName}/${record.patrolId}/" +
                            "${animal.optString("name", animalId.toString())}]"
                    )
                    return PatrolPieceState.MISSING
                }
            }
            if (!matched) {
                Log.forestPatrol("巡护图鉴未返回当前保护地常驻在线动物碎片[${record.reserveName}/${record.patrolId}]，不以图鉴缺片优先")
                PatrolPieceState.UNKNOWN
            } else {
                Log.forestPatrol("巡护图鉴无可推进的常驻动物缺片[${record.reserveName}/${record.patrolId}]")
                PatrolPieceState.COMPLETE
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.printStackTrace(TAG, "getPatrolAnimalPieceState err", t)
            PatrolPieceState.UNKNOWN
        }
    }

    private fun selectPatrolTargetRecord(records: JSONArray): PatrolTargetRecord? {
        val sortedRecords = collectPatrolRecordInfo(records)
            .sortedWith(compareBy<PatrolRecordInfo> { it.startDate }.thenBy { it.patrolId })
        if (sortedRecords.isEmpty()) {
            Log.forestPatrol("巡护记录为空，保留当前保护地")
            return null
        }

        var hasUnknownPieceState = false
        for (record in sortedRecords) {
            when (getPatrolAnimalPieceState(record)) {
                PatrolPieceState.MISSING -> {
                    return PatrolTargetRecord(record.patrolId, record.reserveName, "普通动物碎片未齐")
                }

                PatrolPieceState.UNKNOWN -> hasUnknownPieceState = true

                PatrolPieceState.COMPLETE -> Unit
            }
        }

        if (!hasUnknownPieceState) {
            selectPatrolInventoryTargetRecord(sortedRecords)?.let { return it }
        } else {
            Log.forestPatrol("巡护图鉴状态不完整，保留原地图排序，不按背包动物切换")
        }

        sortedRecords.firstOrNull { hasUnreachedPatrolNode(it.userPatrol) }?.let {
            return PatrolTargetRecord(it.patrolId, it.reserveName, "旧到新未走完")
        }

        val latestRecord = sortedRecords.maxWithOrNull(compareBy<PatrolRecordInfo> { it.startDate }.thenBy { it.patrolId })
            ?: return null
        return PatrolTargetRecord(latestRecord.patrolId, latestRecord.reserveName, "全部完成后最新循环")
    }

    private fun selectPatrolInventoryTargetRecord(records: List<PatrolRecordInfo>): PatrolTargetRecord? {
        val response = try {
            JSONObject(AntForestPatrolRpcCall.queryAnimalPropList())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.printStackTrace(TAG, "queryAnimalPropList for patrol target err", t)
            return null
        }
        if (!ResChecker.checkRes(TAG, "查询巡护背包动物失败:", response)) {
            return null
        }
        val animalProps = response.optJSONArray("animalProps")
        if (animalProps == null || animalProps.length() == 0) {
            Log.forestPatrol("巡护背包动物为空，保留原地图排序")
            return null
        }

        var bestTarget: PatrolInventoryTarget? = null
        for (record in records) {
            val normalAnimalIds = normalPatrolAnimalIds(record.patrolConfig)
            if (normalAnimalIds.isEmpty()) continue
            for (index in 0 until animalProps.length()) {
                val animalProp = animalProps.optJSONObject(index) ?: continue
                val animalId = getPatrolAnimalId(animalProp)
                if (animalId !in normalAnimalIds) continue
                val main = animalProp.optJSONObject("main")
                if (main == null || !main.has("holdsNum") || main.isNull("holdsNum")) {
                    Log.forestPatrol("巡护背包动物缺少holdsNum字段[animalId=$animalId]，保留原地图排序")
                    return null
                }
                if (!hasAnimalPropRobEnergy(animalProp)) {
                    Log.forestPatrol("巡护背包动物缺少robEnergy字段[animalId=$animalId]，保留原地图排序")
                    return null
                }
                val holdsNum = main.optInt("holdsNum", 0)
                if (holdsNum <= 0) continue
                val estimatedEnergy = estimateAnimalPropRobEnergy(animalProp)
                val currentBest = bestTarget
                if (currentBest == null ||
                    holdsNum < currentBest.holdsNum ||
                    holdsNum == currentBest.holdsNum && estimatedEnergy > currentBest.estimatedEnergy
                ) {
                    bestTarget = PatrolInventoryTarget(record, holdsNum, estimatedEnergy)
                }
            }
        }
        val target = bestTarget ?: run {
            Log.forestPatrol("巡护背包动物未匹配普通地图动物，保留原地图排序")
            return null
        }
        return PatrolTargetRecord(
            target.record.patrolId,
            target.record.reserveName,
            "普通动物已合成，背包数量最少(${target.holdsNum})且能量最高(${target.estimatedEnergy}g)",
        )
    }

    private data class PatrolInventoryTarget(
        val record: PatrolRecordInfo,
        val holdsNum: Int,
        val estimatedEnergy: Int,
    )

    private fun getPatrolAnimalId(animalProp: JSONObject): Int {
        val animalId = animalProp.optJSONObject("animal")?.optInt("id", 0) ?: 0
        if (animalId > 0) {
            return animalId
        }
        return animalProp.optJSONObject("partner")
            ?.optString("animalId")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 0
    }

    private fun switchUserPatrolIfNeeded(currentPatrolId: Int, recordPayload: JSONObject): Boolean {
        if (!recordPayload.optBoolean("canSwitch", false)) {
            return false
        }
        val records = recordPayload.optJSONArray("records")
        if (records == null || records.length() == 0) {
            Log.forestPatrol("巡护记录缺少records，保留当前保护地")
            return false
        }
        val target = selectPatrolTargetRecord(records) ?: return false
        if (target.patrolId <= 0 || target.patrolId == currentPatrolId) {
            Log.forestPatrol("巡护⚖️-当前地图保持[${target.reserveName}/${target.patrolId}](${target.reason})")
            return false
        }
        val switchResponse = unwrapResData(JSONObject(AntForestPatrolRpcCall.switchUserPatrol(target.patrolId.toString())))
        return if (ResChecker.checkRes(TAG, "切换巡护地图失败:", switchResponse)) {
            Log.forestPatrol("巡护⚖️-切换地图至[${target.reserveName}/${target.patrolId}](${target.reason})")
            true
        } else {
            Log.forestPatrol("巡护地图切换失败[${target.reserveName}/${target.patrolId}]: ${switchResponse.optString("resultDesc", switchResponse.optString("desc"))}")
            false
        }
    }

    /**
     * 查询并管理用户巡护任务
     */
    internal fun queryUserPatrol() {
        val patrolChanceLimitFlag = StatusFlags.FLAG_ANTFOREST_PATROL_CHANCE_EXCHANGE_LIMIT
        var patrolChanceReplenishTried = false
        fun replenishPatrolChanceIfNeeded(reason: String): Boolean {
            if (patrolChanceReplenishTried) {
                return false
            }
            patrolChanceReplenishTried = true
            val replenishResult = ExchangeReplenisher.replenish(
                need = ExchangeEffectNeed.FOREST_PATROL_CHANCE,
                reason = reason,
                maxCount = 1
            ) {
                AntForestPatrolRpcCall.queryUserPatrol()
            }
            return if (replenishResult == ExchangeReplenishResult.EXCHANGED) {
                Log.forestPatrol("保护地巡护机会已触发缺货补兑，重新查询巡护状态")
                true
            } else {
                Log.forestPatrol("保护地巡护机会补兑未完成#$replenishResult")
                false
            }
        }
        try {
            do {
                // 查询当前巡护任务
                var jo = unwrapResData(JSONObject(AntForestPatrolRpcCall.queryUserPatrol()))
                // 如果查询成功
                if (ResChecker.checkRes(TAG, "查询巡护任务失败:", jo)) {
                    // 查询我的巡护记录
                    val currentPatrolId = jo.optJSONObject("userPatrol")?.optInt("patrolId", 0) ?: 0
                    val recordPayload = unwrapResData(JSONObject(AntForestPatrolRpcCall.queryMyPatrolRecord()))
                    if (ResChecker.checkRes(TAG, "查询巡护记录失败:", recordPayload) &&
                        switchUserPatrolIfNeeded(currentPatrolId, recordPayload)
                    ) {
                        jo = unwrapResData(JSONObject(AntForestPatrolRpcCall.queryUserPatrol()))
                        if (!ResChecker.checkRes(TAG, "查询巡护任务失败:", jo)) {
                            Log.forestPatrol(jo.optString("resultDesc", jo.optString("desc", "查询巡护任务失败")))
                            break
                        }
                    }
                    // 获取用户当前巡护状态信息
                    val userPatrol = jo.optJSONObject("userPatrol")
                    if (userPatrol == null) {
                        Log.forestPatrol("巡护任务缺少userPatrol字段，跳过本轮")
                        break
                    }
                    val currentNode = userPatrol.getInt("currentNode")
                    val currentStatus = userPatrol.getString("currentStatus")
                    val patrolId = userPatrol.getInt("patrolId")
                    val chance = userPatrol.getJSONObject("chance")
                    val leftChance = chance.getInt("leftChance")
                    val leftStep = chance.getInt("leftStep")
                    val usedStep = chance.getInt("usedStep")
                    val chanceFromStepUpperLimit = jo.optInt("chanceFromStepUpperLimit", 5)
                    val chanceStepUnit = jo.optInt("chanceStepUnit", 2000)
                    val maxExchangeStep = if (chanceFromStepUpperLimit > 0 && chanceStepUnit > 0) {
                        chanceFromStepUpperLimit * chanceStepUnit
                    } else {
                        10000
                    }
                    if (usedStep >= maxExchangeStep && !Status.hasFlagToday(patrolChanceLimitFlag)) {
                        Status.setFlagToday(patrolChanceLimitFlag)
                        Log.forestPatrol("今日保护地巡护兑换次数已达上限(${chanceFromStepUpperLimit}次)，后续不再重复尝试")
                    }
                    if ("STANDING" == currentStatus) { // 当前巡护状态为"STANDING"
                        if (leftChance > 0) { // 如果还有剩余的巡护次数，则开始巡护
                            jo = unwrapResData(JSONObject(AntForestPatrolRpcCall.patrolGo(currentNode, patrolId)))
                            patrolKeepGoing(jo, patrolId) // 继续巡护
                            continue  // 跳过当前循环
                        } else if (!Status.hasFlagToday(patrolChanceLimitFlag) &&
                            leftStep >= chanceStepUnit &&
                            usedStep < maxExchangeStep
                        ) { // 如果没有剩余的巡护次数但步数足够，则兑换巡护次数
                            jo = JSONObject(AntForestPatrolRpcCall.exchangePatrolChance(leftStep))
                            if (ResChecker.checkRes(TAG, "兑换巡护次数失败:", jo)) { // 兑换成功，增加巡护次数
                                val addedChance = jo.optInt("addedChance", 0)
                                Log.forestPatrol("步数兑换⚖️[巡护次数*$addedChance]")
                                val consumedStep = if (addedChance > 0) addedChance * chanceStepUnit else chanceStepUnit
                                if (usedStep + consumedStep >= maxExchangeStep) {
                                    Status.setFlagToday(patrolChanceLimitFlag)
                                    Log.forestPatrol("今日保护地巡护兑换次数已达上限(${chanceFromStepUpperLimit}次)，后续不再重复尝试")
                                }
                                continue  // 跳过当前循环
                            } else {
                                val resultDesc = jo.optString("resultDesc")
                                if (resultDesc.contains("上限") || resultDesc.contains("已达") || resultDesc.contains("最多")) {
                                    Status.setFlagToday(patrolChanceLimitFlag)
                                    Log.forestPatrol("今日保护地巡护兑换次数已达上限(${chanceFromStepUpperLimit}次)，后续不再重复尝试")
                                } else {
                                    Log.forestPatrol(resultDesc)
                                }
                            }
                        }
                    } else if ("GOING" == currentStatus) {
                        patrolKeepGoing(jo, patrolId)
                    }
                    if ("STANDING" == currentStatus && leftChance <= 0 &&
                        replenishPatrolChanceIfNeeded("森林保护地巡护机会不足")
                    ) {
                        continue
                    }
                } else {
                    Log.forestPatrol(jo.optString("resultDesc", jo.optString("desc", "查询巡护任务失败")))
                }
                break // 完成一次巡护任务后退出循环
            } while (!Thread.currentThread().isInterrupted && !ApplicationHookConstants.isOffline())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.printStackTrace(TAG, "queryUserPatrol err", t) // 打印异常堆栈
        }
    }

    /**
     * 持续巡护森林，直到巡护状态不再是"进行中"
     *
     * @param response  当前巡护响应
     * @param patrolId  巡护任务ID
     */
    private fun patrolKeepGoing(response: JSONObject, patrolId: Int) {
        var currentResponse = response
        try {
            do {
                val jo = currentResponse
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.forestPatrol(jo.optString("resultDesc", jo.optString("desc", "巡护失败")))
                    return
                }
                logPatrolRewardPiece(jo.optJSONArray("events")?.optJSONObject(0))
                currentResponse = buildNextPatrolKeepGoingResponse(jo, patrolId) ?: return
            } while (!Thread.currentThread().isInterrupted && !ApplicationHookConstants.isOffline())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.printStackTrace(TAG, "patrolKeepGoing err", t)
        }
    }

    private fun logPatrolRewardPiece(event: JSONObject?) {
        val animalName = event?.optJSONObject("rewardInfo")
            ?.optJSONObject("animalProp")
            ?.optJSONObject("animal")
            ?.optString("name")
            .orEmpty()
        if (animalName.isNotBlank()) {
            Log.forestPatrol("巡护森林🏇🏻[${animalName}碎片]")
        }
    }

    private fun buildNextPatrolKeepGoingResponse(response: JSONObject, patrolId: Int): JSONObject? {
        val currentStatus = response.optString("currentStatus")
        if ("GOING" != currentStatus) {
            return null
        }
        val events = response.optJSONArray("events")
        if (events == null || events.length() == 0) {
            logPatrolKeepGoingStop(currentStatus, "缺少事件载荷")
            return null
        }
        val event = events.optJSONObject(0)
        if (event == null) {
            logPatrolKeepGoingStop(currentStatus, "事件数据为空")
            return null
        }
        val userPatrol = response.optJSONObject("userPatrol")
        if (userPatrol == null) {
            logPatrolKeepGoingStop(currentStatus, "缺少userPatrol")
            return null
        }
        val currentNode = userPatrol.optInt("currentNode", -1)
        if (currentNode < 0) {
            logPatrolKeepGoingStop(currentStatus, "缺少当前节点")
            return null
        }
        val materialType = event.optJSONObject("materialInfo")
            ?.optString("materialType")
            .orEmpty()
        if (materialType.isBlank()) {
            logPatrolKeepGoingStop(currentStatus, "缺少事件类型")
            return null
        }
        return unwrapResData(
            JSONObject(AntForestPatrolRpcCall.patrolKeepGoing(currentNode, patrolId, materialType))
        )
    }

    private fun logPatrolKeepGoingStop(currentStatus: String, reason: String) {
        if ("GOING" == currentStatus) {
            Log.forestPatrol("巡护进行中但$reason，停止本轮巡护续跑")
        }
    }

    internal data class AnimalPropSelection(
        val animalProp: JSONObject,
        val holdsNum: Int,
        val estimatedEnergy: Int,
    )

    internal fun selectBestAnimalProp(animalProps: JSONArray): AnimalPropSelection? {
        var bestSelection: AnimalPropSelection? = null
        for (i in 0 until animalProps.length()) {
            val animalProp = animalProps.optJSONObject(i) ?: continue
            val holdsNum = getAnimalPropHoldsNum(animalProp)
            if (holdsNum <= 0) {
                continue
            }
            val estimatedEnergy = estimateAnimalPropRobEnergy(animalProp)
            val currentBest = bestSelection
            if (currentBest == null ||
                holdsNum > currentBest.holdsNum ||
                holdsNum == currentBest.holdsNum && estimatedEnergy > currentBest.estimatedEnergy
            ) {
                bestSelection = AnimalPropSelection(animalProp, holdsNum, estimatedEnergy)
            }
        }
        return bestSelection
    }

    private fun getAnimalPropHoldsNum(animalProp: JSONObject): Int {
        return animalProp.optJSONObject("main")?.optInt("holdsNum", 0) ?: 0
    }

    private fun estimateAnimalPropRobEnergy(animalProp: JSONObject): Int {
        val partner = animalProp.optJSONObject("partner")
        val main = animalProp.optJSONObject("main")
        return maxOf(
            extractAnimalRobAbilityEnergy(partner),
            extractAnimalRobAbilityEnergy(main),
            extractAnimalRobAbilityEnergy(parseAnimalPropExtInfo(partner)),
            extractAnimalRobAbilityEnergy(parseAnimalPropExtInfo(main))
        )
    }

    private fun hasAnimalPropRobEnergy(animalProp: JSONObject): Boolean {
        val partner = animalProp.optJSONObject("partner")
        val main = animalProp.optJSONObject("main")
        return listOf(
            partner,
            main,
            parseAnimalPropExtInfo(partner),
            parseAnimalPropExtInfo(main),
        ).any { container ->
            val robAbility = container?.optJSONObject("robAbility")
                ?: container?.optJSONObject("animal")?.optJSONObject("robAbility")
            robAbility != null &&
                (robAbility.has("robEnergyInDaily") || robAbility.has("robEnergyInRound"))
        }
    }

    private fun extractAnimalRobAbilityEnergy(container: JSONObject?): Int {
        if (container == null) {
            return 0
        }
        val robAbility = container.optJSONObject("robAbility")
            ?: container.optJSONObject("animal")?.optJSONObject("robAbility")
            ?: return 0
        return maxOf(
            robAbility.optInt("robEnergyInDaily", 0),
            robAbility.optInt("robEnergyInRound", 0)
        )
    }

    private fun parseAnimalPropExtInfo(container: JSONObject?): JSONObject? {
        if (container == null || !container.has("extInfo")) {
            return null
        }
        val extInfo = container.opt("extInfo")
        return when (extInfo) {
            is JSONObject -> extInfo
            is String -> try {
                if (extInfo.trim().startsWith("{")) JSONObject(extInfo) else null
            } catch (_: JSONException) {
                null
            }

            else -> null
        }
    }

    /**
     * 派遣伙伴进行巡护
     *
     * @param selection 按库存优先、同库存收益优先选出的动物属性。
     */
    internal fun consumeAnimalProp(selection: AnimalPropSelection?) {
        if (selection == null) return  // 如果没有可派遣的伙伴，则返回

        try {
            val animalProp = selection.animalProp
            // 获取伙伴的属性信息
            val propGroup = animalProp.getJSONObject("main").getString("propGroup")
            val propType = animalProp.getJSONObject("main").getString("propType")
            val name = animalProp.getJSONObject("partner").getString("name")
            // 调用API进行伙伴派遣
            val jo = JSONObject(
                AntForestPatrolRpcCall.consumeAnimalProp(propGroup, propType)
            )
            if (ResChecker.checkRes(TAG, "巡护派遣失败:", jo)) {
                Log.forestPatrol(
                    "巡护派遣🐆[$name]#持有${selection.holdsNum}个，" +
                        "预计能量${selection.estimatedEnergy}g。"
                )
            } else {
                Log.forestPatrol(jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.printStackTrace(TAG, "consumeAnimalProp err", t)
        }
    }

    /**
     * 查询动物及碎片信息，并尝试合成可合成的动物碎片。
     */
    internal fun queryAnimalAndPiece() {
        try {
            // 调用远程接口查询动物及碎片信息
            val response = unwrapResData(JSONObject(AntForestPatrolRpcCall.queryAnimalAndPiece(0)))
            val resultCode = response.optString("resultCode")
            // 检查接口调用是否成功
            if ("SUCCESS" != resultCode) {
                Log.forestPatrol("查询失败: " + response.optString("resultDesc"))
                return
            }
            // 获取动物属性列表
            val animalProps = response.optJSONArray("animalProps")
            if (animalProps == null || animalProps.length() == 0) {
                Log.forestPatrol("动物属性列表为空")
                return
            }
            for (animalId in collectCombinableAnimalIds(animalProps)) {
                combineAnimalPiece(animalId)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.printStackTrace(TAG, "queryAnimalAndPiece err", e)
        }
    }

    private fun collectCombinableAnimalIds(animalProps: JSONArray): List<Int> {
        val combinableAnimalIds = mutableListOf<Int>()
        for (i in 0..<animalProps.length()) {
            val animalObject = animalProps.optJSONObject(i) ?: continue
            val pieces = animalObject.optJSONArray("pieces") ?: continue
            val animalId = animalObject.optJSONObject("animal")?.optInt("id", -1) ?: continue
            if (animalId > 0 && canCombinePieces(pieces)) {
                combinableAnimalIds.add(animalId)
            }
        }
        return combinableAnimalIds
    }

    /**
     * 检查碎片是否满足合成条件。
     *
     * @param pieces 动物碎片数组
     * @return 如果所有碎片满足合成条件，返回 true；否则返回 false
     */
    private fun canCombinePieces(pieces: JSONArray): Boolean {
        for (j in 0..<pieces.length()) {
            val pieceObject = pieces.optJSONObject(j)
            if (pieceObject == null || pieceObject.optInt("holdsNum", 0) <= 0) {
                return false
            }
        }
        return true
    }

    /**
     * 合成动物碎片。
     *
     * @param animalId 动物ID
     */
    private fun combineAnimalPiece(animalId: Int) {
        var animalId = animalId
        try {
            while (!Thread.currentThread().isInterrupted && !ApplicationHookConstants.isOffline()) {
                // 查询动物及碎片信息
                val response = unwrapResData(JSONObject(AntForestPatrolRpcCall.queryAnimalAndPiece(animalId)))
                var resultCode = response.optString("resultCode")
                if ("SUCCESS" != resultCode) {
                    Log.forestPatrol(
                        "动物碎片合成查询失败[#${animalId}]: " +
                            response.optString("resultDesc", response.optString("desc"))
                    )
                    break
                }
                val animalProps = response.optJSONArray("animalProps")
                if (animalProps == null || animalProps.length() == 0) {
                    Log.forestPatrol("动物碎片合成查询返回空动物数据[#${animalId}]")
                    break
                }
                // 获取第一个动物的属性
                val animalProp = animalProps.getJSONObject(0)
                val animal: JSONObject = checkNotNull(animalProp.optJSONObject("animal"))
                val id = animal.optInt("id", -1)
                val name = animal.optString("name", "未知动物")
                // 获取碎片信息
                val pieces = animalProp.optJSONArray("pieces")
                if (pieces == null || pieces.length() == 0) {
                    Log.forestPatrol("动物碎片合成查询缺少碎片数据[$name]")
                    break
                }
                var canCombineAnimalPiece = true
                val piecePropIds = JSONArray()
                // 检查所有碎片是否可用
                for (j in 0..<pieces.length()) {
                    val piece = pieces.optJSONObject(j)
                    if (piece == null || piece.optInt("holdsNum", 0) <= 0) {
                        canCombineAnimalPiece = false
                        Log.forestPatrol("动物碎片不足[$name]：无法继续自动合成")
                        break
                    }
                    val propId = piece.optJSONArray("propIdList")?.optString(0)?.takeIf { it.isNotBlank() }
                    if (propId == null) {
                        canCombineAnimalPiece = false
                        Log.forestPatrol(
                            "动物碎片合成暂停[$name]：碎片[${piece.optString("propType")}]未返回稳定propIdList，跳过本轮合成"
                        )
                        break
                    }
                    piecePropIds.put(propId)
                }
                // 如果所有碎片可用，则尝试合成
                if (canCombineAnimalPiece) {
                    val combineResponse =
                        unwrapResData(JSONObject(AntForestPatrolRpcCall.combineAnimalPiece(id, piecePropIds.toString())))
                    resultCode = combineResponse.optString("resultCode")
                    if ("SUCCESS" == resultCode) {
                        Log.forestPatrol("成功合成动物💡[$name]")
                        animalId = id
                        GlobalThreadPools.sleepCompat(100) // 等待一段时间再查询
                        continue
                    } else {
                        Log.forestPatrol(
                            "动物碎片合成失败[$name]: " +
                                combineResponse.optString("resultDesc", combineResponse.optString("desc"))
                        )
                    }
                }
                break // 如果不能合成或合成失败，跳出循环
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.printStackTrace(TAG, "combineAnimalPiece err", e)
        }
    }

}

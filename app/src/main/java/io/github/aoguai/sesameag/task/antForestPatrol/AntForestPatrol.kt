package io.github.aoguai.sesameag.task.antForestPatrol

import io.github.aoguai.sesameag.data.Statistics
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.model.ConfigPortScope
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.withPortScope
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.antForest.AntForestRpcCall
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject

internal fun parsePatrolResponse(raw: String): JSONObject {
    val response = JSONObject(raw)
    return response.optJSONObject("resData") ?: response
}

class AntForestPatrol : ModelTask() {
    private lateinit var monopolyPatrol: BooleanModelField
    private lateinit var monopolyTasks: BooleanModelField
    private lateinit var legacyPatrol: BooleanModelField
    private lateinit var combineAnimalPiece: BooleanModelField
    private lateinit var collectAnimalEnergy: BooleanModelField
    private lateinit var dispatchAnimal: BooleanModelField
    private lateinit var dispatchPriority: ChoiceModelField
    private lateinit var dispatchStrategy: ChoiceModelField
    private lateinit var exchangeCertificate: BooleanModelField
    private lateinit var earlyExchangeCertificate: BooleanModelField
    private lateinit var autoSwitchMap: BooleanModelField
    private lateinit var certificateEnergyReserve: IntegerModelField

    override fun getName(): String = "保护地巡护"
    override fun getGroup(): ModelGroup = ModelGroup.FOREST_PATROL
    override fun getIcon(): String = "AntForest.png"

    override fun getFields(): ModelFields = ModelFields().apply {
        addField(BooleanModelField("monopolyPatrol", "新版巡护 | 开启", false)
            .withDesc("遍历开放区域，领取首页机会、掷骰并处理事件。可与旧版同时开启。")
            .also { monopolyPatrol = it })
        addField(BooleanModelField("monopolyTasks", "新版巡护 | 自动任务", false)
            .withDesc("独立完成各开放区域的可执行任务并领取骰子；开启新版巡护后继续掷骰。步数任务依赖实际步数。")
            .also { monopolyTasks = it })
        addField(BooleanModelField("legacyPatrol", "旧版巡护 | 开启", false)
            .withDesc("保留原路线选择、步数兑换与巡护机会补兑，可与新版同时开启。")
            .also { legacyPatrol = it })
        addField(BooleanModelField("combineAnimalPiece", "旧版巡护 | 合成动物碎片", false)
            .also { combineAnimalPiece = it })
        addField(BooleanModelField("collectAnimalEnergy", "动物伙伴 | 领取能量", false)
            .withDesc("领取新旧动物已产生的能量，不要求开启地图巡护。")
            .also { collectAnimalEnergy = it })
        addField(BooleanModelField("dispatchAnimal", "动物伙伴 | 自动派遣", false)
            .withDesc("按选择策略派遣伙伴；默认保留正在工作的动物。")
            .also { dispatchAnimal = it })
        addField(ChoiceModelField("dispatchPriority", "动物伙伴 | 派遣优先级", 0, arrayOf("新版优先", "旧版优先"))
            .withDesc("空闲派遣时优先尝试指定版本，没有可用伙伴时再尝试另一版本；各版本内部按选择策略挑选。")
            .also { dispatchPriority = it })
        addField(ChoiceModelField("dispatchStrategy", "动物伙伴 | 选择策略", 0, arrayOf("原策略", "服务端顺序", "能量优先", "新版续派", "新版跨地图择优", "新版续派并跨地图择优"))
            .withPortScope(ConfigPortScope.GENERIC)
            .withDesc(
                "原策略：新版优先初始产能，旧版优先库存、同库存再比较收益；" +
                    "服务端顺序：选择列表中首个可用伙伴；" +
                    "能量优先：新版按初始产能、旧版按每日产能。新版产能信息不全时按列表顺序；" +
                    "新版续派：工作期缩短后续派当前伙伴；新版跨地图择优：在开放地图中选择产能更高的伙伴，可替换在岗伙伴。新增策略的旧版选择沿用原策略。"
            )
            .also { dispatchStrategy = it })
        addField(BooleanModelField("exchangeCertificate", "新版巡护 | 自动兑换保护证书", false)
            .withDesc("按地图下发项目、实时资格和成本消耗森林能量，每张地图领取一份证书。")
            .also { exchangeCertificate = it })
        addField(BooleanModelField("earlyExchangeCertificate", "新版巡护 | 提前兑换保护证书", false)
            .withDesc("自动兑换开启时，允许兑换尚未巡护但已下发明确项目的地图证书。")
            .also { earlyExchangeCertificate = it })
        addField(IntegerModelField("certificateEnergyReserve", "新版巡护 | 兑换后至少保留能量(g)", 0, 0, Int.MAX_VALUE)
            .also { certificateEnergyReserve = it })
        addField(BooleanModelField("autoSwitchMap", "新版巡护 | 满星后切换最新开放地图", false)
            .also { autoSwitchMap = it })
    }

    override suspend fun runSuspend() {
        val uid = UserMap.currentUid?.takeIf { it.isNotBlank() } ?: return
        Log.forestPatrol("执行开始-${getName()}")
        val stages: List<Pair<String, () -> Unit>> = listOf(
            "旧版动物能量" to { if (collectAnimalEnergy.value == true) collectLegacyEnergy(uid) },
            "新版动物能量" to { if (collectAnimalEnergy.value == true) collectMonopolyEnergy(uid) },
            "旧版巡护" to { if (legacyPatrol.value == true) LegacyPatrolWorkflow.queryUserPatrol() },
            "动物碎片合成" to { if (combineAnimalPiece.value == true) LegacyPatrolWorkflow.queryAnimalAndPiece() },
            "巡护地图" to {
                if (monopolyPatrol.value == true || monopolyTasks.value == true || exchangeCertificate.value == true || autoSwitchMap.value == true) {
                    MonopolyPatrolWorkflow.updateMaps(
                        exchangeEnabled = exchangeCertificate.value == true,
                        earlyExchange = earlyExchangeCertificate.value == true,
                        switchEnabled = autoSwitchMap.value == true,
                        energyReserve = certificateEnergyReserve.value ?: 0,
                        showRanking = true,
                        tasksEnabled = monopolyTasks.value == true,
                        patrolEnabled = monopolyPatrol.value == true,
                    )
                }
            },
            "动物派遣" to { if (dispatchAnimal.value == true) dispatchAnimal(uid) },
        )
        try {
            for ((name, action) in stages) {
                currentCoroutineContext().ensureActive()
                if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return
                try {
                    runInterruptible(Dispatchers.IO) { action() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MonopolyPatrolWorkflow.RegionRestoreException) {
                    Log.printStackTrace(TAG, "巡护区域尚未恢复，结束本轮", e)
                    return
                } catch (e: Exception) {
                    Log.printStackTrace(TAG, "$name 失败", e)
                }
            }
        } finally {
            Log.forestPatrol("执行结束-${getName()}")
        }
    }

    private fun queryLegacyAnimals(): JSONArray? {
        val home = parsePatrolResponse(AntForestRpcCall.queryHomePage())
        if (!ResChecker.checkRes(TAG, "查询动物首页失败:", home)) return null
        val animals = if (home.optString("nextAction") == "Team") {
            home.optJSONObject("teamHomeResult")?.optJSONObject("mainMember")?.optJSONArray("usingUserProps")
        } else {
            home.optJSONArray("usingUserPropsNew")
        }
        if (animals == null) Log.error(TAG, "首页缺少正在使用的道具列表，未确认动物占用:$home")
        return animals
    }

    private fun collectLegacyEnergy(uid: String) {
        val animals = queryLegacyAnimals() ?: return
        for (index in 0 until animals.length()) {
            val animal = animals.getJSONObject(index)
            if (animal.optString("propGroup") != "animal") continue
            val ext = JSONObject(animal.getString("extInfo"))
            if (ext.optBoolean("isCollected")) continue
            var accepted = false
            try {
                val response = parsePatrolResponse(AntForestPatrolRpcCall.collectAnimalRobEnergy(
                    animal.getString("propId"), animal.getString("propType"), ext.getString("shortDay"),
                ))
                accepted = ResChecker.checkRes(TAG, "收取旧版动物能量失败:", response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "旧版动物领取结果不确定，先回查", e)
            }
            if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return
            val refreshed = queryLegacyAnimals() ?: return
            val confirmed = (0 until refreshed.length()).mapNotNull { refreshed.optJSONObject(it) }
                .firstOrNull { it.optString("propId") == animal.getString("propId") }
                ?.optString("extInfo")?.takeIf { it.isNotBlank() }?.let(::JSONObject)
            if (confirmed?.optString("shortDay") == ext.getString("shortDay") && confirmed.optBoolean("isCollected")) {
                val energy = ext.optInt("energy", 0)
                if (accepted && energy > 0) Statistics.addData(uid, Statistics.DataType.COLLECTED, energy)
                Log.forestPatrol("已确认收取[${ext.optJSONObject("animal")?.optString("name")}]派遣能量${if (accepted) "[$energy g]" else "，本次响应未确认到账量"}")
            } else {
                Log.forestPatrol("旧版动物[${animal.getString("propId")}]能量尚未确认领取，留待下次查询")
            }
            return
        }
    }

    private fun queryUsingCreature(uid: String): JSONObject? {
        val response = parsePatrolResponse(AntForestPatrolRpcCall.queryUsingCreatureInfo(uid))
        return response.takeIf { ResChecker.checkRes(TAG, "查询新版动物失败:", it) }
    }

    private fun collectMonopolyEnergy(uid: String): Boolean {
        val state = queryUsingCreature(uid) ?: return false
        if (state.opt("usingMonopolyCreature") == false) return true
        val creature = state.optJSONObject("userCreatureVO") ?: return false
        val energy = creature.optJSONObject("robEnergyVO") ?: run {
            Log.error(TAG, "新版动物缺少能量状态:$creature")
            return false
        }
        if (energy.opt("energyIsCollect") == true || energy.optInt("yesterdayRobEnergy", -1) == 0) return true
        val code = creature.optString("creatureCode")
        val day = energy.optString("yesterdayShortDay")
        if (energy.opt("energyIsCollect") != false || code.isBlank() || day.isBlank()) {
            Log.error(TAG, "新版动物能量缺少领取标识:$creature")
            return false
        }
        try {
            val response = parsePatrolResponse(AntForestPatrolRpcCall.collectMonopolyCreatureEnergy(code, day))
            if (ResChecker.checkRes(TAG, "收取新版动物能量失败:", response)) {
                val collected = response.optInt("collectedEnergy", -1)
                if (collected < 0) {
                    Log.error(TAG, "新版动物领取成功但缺少实际到账量:$response")
                } else {
                    if (collected > 0) Statistics.addData(uid, Statistics.DataType.COLLECTED, collected)
                    Log.forestPatrol("收取[${creature.optString("creatureName", code)}]派遣能量[$collected g]")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "新版动物领取结果不确定，先回查", e)
        }
        if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return false
        val refreshed = queryUsingCreature(uid)?.optJSONObject("userCreatureVO") ?: return false
        val confirmed = refreshed.optString("creatureCode") == code &&
            refreshed.optJSONObject("robEnergyVO")?.opt("energyIsCollect") == true
        if (!confirmed) Log.forestPatrol("新版动物能量尚未确认领取，留待下次调度查询")
        return confirmed
    }

    private fun dispatchAnimal(uid: String) {
        val oldAnimals = queryLegacyAnimals() ?: return
        for (index in 0 until oldAnimals.length()) {
            if (oldAnimals.optJSONObject(index)?.optString("propGroup") == "animal") {
                Log.forestPatrol("已有旧版动物工作，保留当前伙伴")
                return
            }
        }
        val using = try {
            queryUsingCreature(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "新版动物占用查询失败", e)
            null
        }
        when (using?.opt("usingMonopolyCreature")) {
            true -> {
                if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return
                val entry = queryMonopolyEntry() ?: return
                val usingAnimal = findUsingCreature(entry)
                if (usingAnimal == null) {
                    Log.error(TAG, "新版在岗动物详情缺失，保留当前伙伴:$entry")
                    return
                }
                if ((dispatchStrategy.value ?: 0) < 3 && !isCreatureExpired(usingAnimal)) {
                    Log.forestPatrol("已有新版动物工作，保留当前伙伴")
                    return
                }
                dispatchMonopolyAnimal(entry, replaceUsing = true)
                return
            }
            false -> Unit
            else -> {
                Log.forestPatrol("新版占用尚未确认，仅保留旧版非强制派遣请求")
                if (!ApplicationHookConstants.isOffline() && UserMap.currentUid == uid) dispatchLegacyAnimal()
                return
            }
        }
        val sources = if (dispatchPriority.value == 1) listOf(false, true) else listOf(true, false)
        for (newVersion in sources) {
            if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return
            val attempted = if (newVersion) dispatchMonopolyAnimal() else dispatchLegacyAnimal()
            // null 表示查询失败，不作为“该来源没有候选”切换来源。
            if (attempted != false) {
                if (!ApplicationHookConstants.isOffline() && UserMap.currentUid == uid) {
                    queryLegacyAnimals()
                    queryUsingCreature(uid)
                }
                return
            }
        }
        Log.forestPatrol("当前没有可派遣动物")
    }

    private fun queryMonopolyEntry(): JSONObject? =
        parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
            .takeIf { ResChecker.checkRes(TAG, "查询新版动物候选失败:", it) }

    private fun findUsingCreature(entry: JSONObject): JSONObject? = entry.optJSONObject("usingCreature")

    private fun isCreatureExpired(animal: JSONObject): Boolean {
        val energy = animal.optJSONObject("robEnergyVO") ?: return false
        if (energy.optInt("robRemainDays", -1) > 0 ||
            energy.optString("robEnergyStatus") == "WORKING" || energy.opt("robEnergyStopped") == false
        ) return false
        return energy.optInt("robRemainDays", -1) == 0 || energy.opt("robEnergyStopped") == true
    }

    private fun dispatchLegacyAnimal(): Boolean? {
        val response = parsePatrolResponse(AntForestPatrolRpcCall.queryAnimalPropList())
        if (!ResChecker.checkRes(TAG, "查询旧版动物候选失败:", response)) return null
        val animals = response.optJSONArray("animalProps") ?: run {
            Log.error(TAG, "旧版候选缺少 animalProps:$response")
            return null
        }
        val selected = LegacyPatrolWorkflow.selectBestAnimalProp(animals, (dispatchStrategy.value ?: 0).takeIf { it < 3 } ?: 0) ?: return false
        val uid = UserMap.currentUid.orEmpty()
        if (uid.isBlank() || ApplicationHookConstants.isOffline()) return null
        LegacyPatrolWorkflow.consumeAnimalProp(selected)
        if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return null
        val refreshed = queryLegacyAnimals() ?: return null
        val main = selected.animalProp.getJSONObject("main")
        val propId = main.optString("propId")
        val propType = main.getString("propType")
        val confirmed = (0 until refreshed.length()).mapNotNull { refreshed.optJSONObject(it) }
            .firstOrNull { it.optString("propGroup") == "animal" && it.optString("propType") == propType &&
                (propId.isBlank() || it.optString("propId") == propId) }
        if (confirmed == null) {
            Log.error(TAG, "旧版动物派遣回查未确认目标[$propType/$propId]，不改派其他伙伴")
            return null
        }
        Log.forestPatrol("已确认旧版动物派遣[${confirmed.getString("propId")}]，类型[$propType]")
        return true
    }

    private fun dispatchMonopolyAnimal(entryInfo: JSONObject? = null, replaceUsing: Boolean = false): Boolean? {
        val response = entryInfo ?: queryMonopolyEntry() ?: return null
        if (!replaceUsing && response.optBoolean("usingMonopolyCreature")) return true
        val using = findUsingCreature(response)
        if (replaceUsing && using == null) return null
        val strategy = dispatchStrategy.value ?: 0
        val continuous = strategy == 3 || strategy == 5
        val acrossMaps = strategy == 4 || strategy == 5
        val uid = UserMap.currentUid.orEmpty()
        if (uid.isBlank() || ApplicationHookConstants.isOffline()) return null
        val candidates = mutableListOf<Triple<String, String, JSONObject>>()
        var originalRegion = ""
        var originalMap = ""
        val scanned = MonopolyPatrolWorkflow.visitRegions(allRegions = acrossMaps || continuous && using != null) { page ->
            val regionCode = page.getString("currentRegionCode")
            if (originalRegion.isBlank()) {
                originalRegion = regionCode
                originalMap = page.getString("currentMapCode")
            }
            val maps = page.getJSONArray("currentRegionMapList")
            for (index in 0 until maps.length()) {
                val map = maps.getJSONObject(index)
                if (map.optString("mapAvailability") != "AVAILABLE") continue
                val mapCode = map.getString("mapCode")
                val animals = map.getJSONArray("creatureList")
                for (animalIndex in 0 until animals.length()) {
                    val animal = animals.getJSONObject(animalIndex)
                    val code = animal.optString("creatureCode")
                    if (code.isBlank() || animal.optInt("level", 0) <= 0) continue
                    if (animal.optString("status") != "unused" && code != using?.optString("creatureCode")) continue
                    if (acrossMaps || regionCode == originalRegion && mapCode == originalMap ||
                        code == using?.optString("creatureCode")
                    ) candidates.add(Triple(regionCode, mapCode, animal))
                }
            }
        }
        if (!scanned || ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return null
        val energyOf: (JSONObject) -> Int = { animal ->
            val levelEnergy = animal.optJSONObject("displayInfo")
                ?.optJSONObject("lv${animal.optInt("level")}")?.optInt("levelRobEnergy", 0) ?: 0
            if (levelEnergy > 0) levelEnergy else animal.optInt("initialRobEnergy", 0)
        }
        val available = candidates.filter { it.third.optString("status") == "unused" }
        val best = when {
            acrossMaps -> available.maxByOrNull { energyOf(it.third) }
            strategy != 1 && available.all { it.third.has("initialRobEnergy") && !it.third.isNull("initialRobEnergy") } ->
                available.maxByOrNull { it.third.optInt("initialRobEnergy") }
            else -> available.firstOrNull()
        }
        val selected = if (using != null) {
            val expired = isCreatureExpired(using)
            val energy = using.optJSONObject("robEnergyVO")
            val remain = energy?.optInt("robRemainDays", -1) ?: -1
            val maxDays = energy?.optInt("maxWorkDays", 0) ?: 0
            when {
                acrossMaps && best != null && (expired ||
                    energyOf(using) > 0 && energyOf(best.third) > energyOf(using)) -> best
                continuous && (expired || remain > 0 && maxDays > remain) ->
                    candidates.firstOrNull { it.third.optString("creatureCode") == using.optString("creatureCode") }
                expired -> best
                else -> {
                    Log.forestPatrol("当前伙伴无需续派或替换")
                    return true
                }
            }
        } else best
        if (selected == null) return if (using == null) false else true
        val code = selected.third.getString("creatureCode")
        if (using != null && !collectMonopolyEnergy(uid)) {
            Log.error(TAG, "动物能量领取尚未确认，保留当前伙伴")
            return null
        }
        var result: Boolean? = null
        val visited = MonopolyPatrolWorkflow.visitRegions(targetRegion = selected.first, restoreMap = true) { page ->
            if (page.optString("currentMapCode") != selected.second) {
                val switched = parsePatrolResponse(AntForestPatrolRpcCall.switchMonopolyMap(selected.second, selected.first))
                ResChecker.checkRes(TAG, "派遣前切换地图失败:", switched)
                if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return@visitRegions
                val confirmed = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyMapPage())
                if (!ResChecker.checkRes(TAG, confirmed) || confirmed.optString("currentRegionCode") != selected.first ||
                    confirmed.optString("currentMapCode") != selected.second
                ) {
                    Log.error(TAG, "派遣地图切换未确认:$confirmed")
                    return@visitRegions
                }
            }
            if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return@visitRegions
            val before = queryUsingCreature(uid) ?: return@visitRegions
            val beforeAnimal = before.optJSONObject("userCreatureVO")
            if (using == null && before.opt("usingMonopolyCreature") != false ||
                using != null && (before.opt("usingMonopolyCreature") != true ||
                    beforeAnimal?.optString("creatureCode") != using.optString("creatureCode"))
            ) {
                Log.error(TAG, "派遣前动物占用发生变化，等待下次调度:$before")
                return@visitRegions
            }
            try {
                assignMonopolyWithConfirm(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "新版动物派遣结果不确定[$code]，先回查", e)
            }
            if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return@visitRegions
            val refreshed = queryUsingCreature(uid) ?: return@visitRegions
            val animal = refreshed.optJSONObject("userCreatureVO")
            val renewed = beforeAnimal == null || beforeAnimal.optString("creatureCode") != code ||
                (animal?.optLong("assignTime", 0L) ?: 0L) > beforeAnimal.optLong("assignTime", 0L) ||
                (animal?.optJSONObject("robEnergyVO")?.optInt("robRemainDays", -1) ?: -1) >
                    (beforeAnimal.optJSONObject("robEnergyVO")?.optInt("robRemainDays", -1) ?: -1)
            if (refreshed.opt("usingMonopolyCreature") != true || animal?.optString("creatureCode") != code || !renewed) {
                Log.error(TAG, "新版动物派遣回查未确认目标或续派进度[$code]，不改派其他伙伴")
                return@visitRegions
            }
            Log.forestPatrol("已确认派遣新版动物[${selected.third.optString("creatureName", code)}]")
            result = true
        }
        return if (visited) result else null
    }

    /**
     * 派遣新版动物；服务端返回占用确认码时按 secondConfirm=true 重发完成替换。
     */
    private fun assignMonopolyWithConfirm(creatureCode: String): Boolean {
        val first = parsePatrolResponse(AntForestPatrolRpcCall.assignMonopolyCreature(creatureCode))
        val resultCode = first.optString("resultCode")
        if (resultCode == "SUCCESS") return true
        // 占用确认码属业务确认流而非失败：MONOPOLY_CREATURE_USE_CONFIRM(新版占用)/ANIMAL_PROP_IN_USE_CONFIRM(旧版占用)
        if (resultCode != "MONOPOLY_CREATURE_USE_CONFIRM" && resultCode != "ANIMAL_PROP_IN_USE_CONFIRM") {
            Log.error(TAG, "新版动物派遣失败:$first")
            return false
        }
        val second = parsePatrolResponse(
            AntForestPatrolRpcCall.assignMonopolyCreature(creatureCode, secondConfirm = true)
        )
        return ResChecker.checkRes(TAG, "新版动物替换失败:", second)
    }

    companion object {
        private const val TAG = "AntForestPatrol"
    }
}

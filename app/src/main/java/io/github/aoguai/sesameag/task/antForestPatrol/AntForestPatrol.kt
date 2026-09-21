package io.github.aoguai.sesameag.task.antForestPatrol

import io.github.aoguai.sesameag.data.Statistics
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.ChoiceModelField
import io.github.aoguai.sesameag.model.withDesc
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
    private lateinit var exchangeCertificate: BooleanModelField

    override fun getName(): String = "保护地巡护"
    override fun getGroup(): ModelGroup = ModelGroup.FOREST_PATROL
    override fun getIcon(): String = "AntForest.png"

    override fun getFields(): ModelFields = ModelFields().apply {
        addField(BooleanModelField("monopolyPatrol", "新版巡护 | 开启", false)
            .withDesc("推进当前新版地图，领取首页机会，掷骰并处理事件。可与旧版同时开启。")
            .also { monopolyPatrol = it })
        addField(BooleanModelField("monopolyTasks", "新版巡护 | 自动任务", false)
            .withDesc("新版巡护开启时完成可执行任务、领取骰子，并继续巡护；步数任务依赖实际步数。")
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
            .withDesc("空闲时选择伙伴，保留正在工作的动物；不强制替换。")
            .also { dispatchAnimal = it })
        addField(ChoiceModelField("dispatchPriority", "动物伙伴 | 派遣优先级", 0, arrayOf("新版优先", "旧版优先"))
            .also { dispatchPriority = it })
        addField(BooleanModelField("exchangeCertificate", "新版巡护 | 自动兑换保护证书", false)
            .withDesc("按当前地图下发项目、实时资格和成本消耗森林能量，兑换后回查；默认不消费。")
            .also { exchangeCertificate = it })
    }

    override suspend fun runSuspend() {
        val uid = UserMap.currentUid?.takeIf { it.isNotBlank() } ?: return
        Log.forestPatrol("执行开始-${getName()}")
        val stages: List<Pair<String, () -> Unit>> = listOf(
            "旧版动物能量" to { if (collectAnimalEnergy.value == true) collectLegacyEnergy(uid) },
            "新版动物能量" to { if (collectAnimalEnergy.value == true) collectMonopolyEnergy(uid) },
            "旧版巡护" to { if (legacyPatrol.value == true) LegacyPatrolWorkflow.queryUserPatrol() },
            "动物碎片合成" to { if (combineAnimalPiece.value == true) LegacyPatrolWorkflow.queryAnimalAndPiece() },
            "新版巡护" to { if (monopolyPatrol.value == true) MonopolyPatrolWorkflow.run(monopolyTasks.value == true) },
            "保护证书" to { if (exchangeCertificate.value == true) MonopolyPatrolWorkflow.exchangeCertificate() },
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
            val response = parsePatrolResponse(AntForestPatrolRpcCall.collectAnimalRobEnergy(
                animal.getString("propId"), animal.getString("propType"), ext.getString("shortDay"),
            ))
            if (ResChecker.checkRes(TAG, "收取旧版动物能量失败:", response)) {
                val energy = ext.optInt("energy", 0)
                if (energy > 0) Statistics.addData(uid, Statistics.DataType.COLLECTED, energy)
                Log.forestPatrol("收取[${ext.optJSONObject("animal")?.optString("name")}]派遣能量[$energy g]")
            }
            if (!ApplicationHookConstants.isOffline()) queryLegacyAnimals()
            return
        }
    }

    private fun queryUsingCreature(uid: String): JSONObject? {
        val response = parsePatrolResponse(AntForestPatrolRpcCall.queryUsingCreatureInfo(uid))
        return response.takeIf { ResChecker.checkRes(TAG, "查询新版动物失败:", it) }
    }

    private fun collectMonopolyEnergy(uid: String) {
        val state = queryUsingCreature(uid) ?: return
        val creature = state.optJSONObject("userCreatureVO") ?: return
        val energy = creature.optJSONObject("robEnergyVO") ?: run {
            Log.error(TAG, "新版动物缺少能量状态:$creature")
            return
        }
        if (energy.optBoolean("energyIsCollect") || energy.optInt("yesterdayRobEnergy", 0) == 0) return
        val code = creature.optString("creatureCode")
        val day = energy.optString("yesterdayShortDay")
        if (energy.opt("energyIsCollect") != false || code.isBlank() || day.isBlank()) {
            Log.error(TAG, "新版动物能量缺少领取标识:$creature")
            return
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
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "新版动物领取结果不确定，先回查", e)
        }
        if (ApplicationHookConstants.isOffline() || UserMap.currentUid != uid) return
        val refreshed = queryUsingCreature(uid) ?: return
        if (refreshed.optJSONObject("userCreatureVO")?.optJSONObject("robEnergyVO")?.optBoolean("energyIsCollect") != true) {
            Log.forestPatrol("新版动物能量尚未确认领取，留待下次调度查询")
        }
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
                Log.forestPatrol("已有新版动物工作，保留当前伙伴")
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

    private fun dispatchLegacyAnimal(): Boolean? {
        val response = parsePatrolResponse(AntForestPatrolRpcCall.queryAnimalPropList())
        if (!ResChecker.checkRes(TAG, "查询旧版动物候选失败:", response)) return null
        val animals = response.optJSONArray("animalProps") ?: run {
            Log.error(TAG, "旧版候选缺少 animalProps:$response")
            return null
        }
        val selected = LegacyPatrolWorkflow.selectBestAnimalProp(animals) ?: return false
        if (ApplicationHookConstants.isOffline()) return null
        LegacyPatrolWorkflow.consumeAnimalProp(selected)
        return true
    }

    private fun dispatchMonopolyAnimal(): Boolean? {
        val response = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo())
        if (!ResChecker.checkRes(TAG, "查询新版动物候选失败:", response)) return null
        if (response.optBoolean("usingMonopolyCreature")) return true
        val animals = response.optJSONArray("creatureList") ?: run {
            Log.error(TAG, "新版候选缺少 creatureList:$response")
            return null
        }
        val candidates = (0 until animals.length()).mapNotNull { animals.optJSONObject(it) }.filter { animal ->
            val energy = animal.optJSONObject("robEnergyVO")
            val maxDays = energy?.optInt("maxWorkDays", 0) ?: 0
            animal.optString("creatureCode").isNotBlank() && animal.optString("status") != "using" &&
                animal.optLong("assignTime", 0L) <= 0 &&
                (energy == null || !energy.has("robRemainDays") || energy.optInt("robRemainDays") > 0) &&
                (maxDays <= 0 || (energy?.optInt("alreadyWorkDays", 0) ?: 0) < maxDays)
        }
        val selected = if (candidates.all { it.has("initialRobEnergy") && !it.isNull("initialRobEnergy") }) {
            candidates.maxByOrNull { it.optInt("initialRobEnergy") }
        } else {
            candidates.firstOrNull()
        } ?: return false
        if (ApplicationHookConstants.isOffline()) return null
        val assigned = parsePatrolResponse(AntForestPatrolRpcCall.assignMonopolyCreature(selected.getString("creatureCode")))
        if (ResChecker.checkRes(TAG, "新版动物派遣失败:", assigned)) {
            Log.forestPatrol("派遣新版动物[${selected.optString("creatureName", selected.getString("creatureCode"))}]")
        }
        return true
    }

    companion object {
        private const val TAG = "AntForestPatrol"
    }
}

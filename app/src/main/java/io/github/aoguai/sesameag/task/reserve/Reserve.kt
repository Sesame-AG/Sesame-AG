package io.github.aoguai.sesameag.task.reserve

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.entity.ReserveEntity
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionCheck
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.SelectAndCountModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionRow
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsCache
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsSnapshot
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.SerialTaskQueue
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.UserDataStoreManager
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.ReserveaMap
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject

class Reserve : ModelTask() {
    private var reserveList: SelectAndCountModelField? = null
    private var certificateTargets: SelectAndCountModelField? = null
    private var reserveEnergy: IntegerModelField? = null

    override fun getName(): String = "保护地"

    override fun getGroup(): ModelGroup = ModelGroup.FOREST

    override fun getIcon(): String = "Reserve.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            SelectAndCountModelField(
                "reserveList",
                "保护项目 | 每日申请次数",
                LinkedHashMap(),
                ReserveEntity::getListAsMapperEntity,
            ).withDesc("选择保护地、树木或动物项目及每日申请上限；数量大于 0 才会执行。").also {
                reserveList = it
            },
        )
        modelFields.addField(
            SelectAndCountModelField("certificateTargets", "保护项目 | 证书持有目标", LinkedHashMap(), ReserveEntity::getListAsMapperEntity)
                .withDesc("按服务端持有数量补齐，0 不设置目标；未配置每日次数的项目每天最多申请 1 次。动物项目先集齐基础证书，再领取金色证书。")
                .also { certificateTargets = it },
        )
        modelFields.addField(
            IntegerModelField("reserveEnergy", "保护项目 | 保留能量（g）", 0)
                .withDesc("申请后保留的最低能量；成本或持有数量不明时不申请。")
                .also { reserveEnergy = it },
        )
        return modelFields
    }

    override fun check(): Boolean =
        when {
            TaskCommon.IS_ENERGY_TIME -> {
                Log.forest("⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，停止执行${getName()}任务！")
                false
            }

            TaskCommon.IS_MODULE_SLEEP_TIME -> {
                Log.forest("💤 模块休眠时间【${BaseModel.modelSleepTime.value}】停止执行${getName()}任务！")
                false
            }

            else -> {
                true
            }
        }

    override fun runJava() {
        GlobalThreadPools.execute {
            runSuspend()
        }
    }

    override suspend fun runSuspend() {
        try {
            Log.forest("开始保护地任务")
            initReserve()
            animalReserve()
        } catch (t: Throwable) {
            Log.runtime(TAG, "start.run err:")
            Log.printStackTrace(TAG, t)
        } finally {
            Log.forest("保护地任务")
        }
    }

    private suspend fun animalReserve() {
        try {
            Log.forest("开始执行-${getName()}")
            val configuredReserveMap = getConfiguredReserveMap()
            val targets = certificateTargets?.value.orEmpty().mapNotNull { (id, count) ->
                if (!id.isNullOrBlank() && count != null && count > 0) id to count else null
            }.toMap()
            if (configuredReserveMap.isEmpty() && targets.isEmpty()) {
                Log.forest("保护项目未配置有效申请项，跳过执行")
                return
            }
            val energyReserve = reserveEnergy?.value ?: 0
            if (energyReserve < 0) {
                Log.error(TAG, "保护项目保留能量不能小于 0")
                return
            }
            var s: String? = ReserveRpcCall.queryTreeItemsForExchange()
            if (s == null) {
                s = ReserveRpcCall.queryTreeItemsForExchange()
            }
            val jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val ja = jo.getJSONArray("treeItems")
                for (i in 0 until ja.length()) {
                    val item = ja.getJSONObject(i)
                    if (item.optString("projectType") !in setOf("RESERVE", "TREE", "ANIMAL")) continue
                    val projectId = item.getString("itemId")
                    val itemName = item.getString("itemName")
                    val target = targets[projectId]
                    val dailyLimit = configuredReserveMap[projectId]
                        ?: if (target != null && reserveList?.value?.containsKey(projectId) != true) 1 else continue
                    exchangeTree(projectId, itemName, dailyLimit, target, energyReserve)
                }
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "animalReserve err:")
            Log.printStackTrace(TAG, t)
        } finally {
            Log.forest("结束执行-${getName()}")
        }
    }

    private fun getConfiguredReserveMap(): Map<String, Int> {
        val configuredMap = reserveList?.value ?: return emptyMap()
        if (configuredMap.isEmpty()) {
            return emptyMap()
        }
        val enabledReserveMap = LinkedHashMap<String, Int>()
        configuredMap.forEach { (projectId, count) ->
            val enabledCount = count ?: return@forEach
            if (!projectId.isNullOrBlank() && enabledCount > 0) {
                enabledReserveMap[projectId] = enabledCount
            }
        }
        return enabledReserveMap
    }

    companion object {
        private val TAG = Reserve::class.java.simpleName
        private val exchangeQueue = SerialTaskQueue()

        internal fun exchangeTree(
            projectId: String,
            itemName: String,
            count: Int,
            targetCount: Int? = null,
            energyReserve: Int = 0,
            initialDetail: JSONObject? = null,
        ): Boolean = exchangeQueue.execute {
            if (count <= 0 || energyReserve < 0 || (targetCount != null && targetCount <= 0)) return@execute false
            val store = UserDataStoreManager.getCurrentInstance() ?: return@execute false
            val epoch = AccountSessionCoordinator.currentSessionEpoch()
            val pendingKey = "reserve_pending:$projectId"
            try {
                var pending = store.get(pendingKey, String::class.java)?.let(::JSONObject)
                var detail = if (pending == null && initialDetail != null) initialDetail else {
                    JSONObject(ReserveRpcCall.queryTreeForExchange(projectId)).let { it.optJSONObject("resData") ?: it }
                }
                while (!ApplicationHookConstants.isOffline() && !Thread.currentThread().isInterrupted) {
                    if (AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current) return@execute false
                    if (!ResChecker.checkRes(TAG, detail)) {
                        Log.error(TAG, "保护项目查询失败[$itemName] raw=$detail")
                        return@execute false
                    }
                    val tree = detail.getJSONObject("exchangeableTree")
                    val type = tree.getString("type")
                    val owned = tree.getInt("certCount")
                    if (owned < 0 || type !in setOf("RESERVE", "TREE", "ANIMAL", "MONOPOLY")) {
                        Log.error(TAG, "保护项目类型或证书数量无效[$itemName] raw=$detail")
                        return@execute false
                    }
                    val aliases = if (type == "ANIMAL") detail.getJSONArray("subTreeVOs") else null
                    val aliasCounts = aliases?.let { list -> (0 until list.length()).map { list.getJSONObject(it).getInt("certCountForAlias") } }
                    if (aliasCounts != null && (aliasCounts.isEmpty() || aliasCounts.any { it < 0 })) {
                        Log.error(TAG, "动物项目缺少有效基础证书进度[$itemName] raw=$detail")
                        return@execute false
                    }
                    pending?.let { previous ->
                        val confirmed = if (previous.getBoolean("golden") || !previous.getBoolean("animal")) {
                            owned > previous.getInt("certCount")
                        } else {
                            aliasCounts != null && aliasCounts.sum() > previous.getInt("aliasCount")
                        }
                        if (!confirmed) {
                            Log.forest("保护项目🏕️[$itemName]上次申请尚未确认，保留回查，不重复消耗")
                            return@execute false
                        }
                        if (!previous.getBoolean("golden") && TimeUtil.isSameDay(previous.getLong("submittedAt"), System.currentTimeMillis())) {
                            val recorded = Status.getReserveTimes(projectId)
                            if (recorded <= previous.getInt("recordedCount")) Status.reserveToday(projectId, 1)
                        }
                        store.remove(pendingKey)
                        pending = null
                        Log.forest("保护项目🏕️[$itemName]已回查确认上次申请")
                    }
                    if (targetCount != null && owned >= targetCount) return@execute true
                    if (type == "ANIMAL" && owned > 0) return@execute targetCount == null || owned >= targetCount
                    val golden = aliasCounts != null && aliasCounts.all { it > 0 }
                    if (!golden) {
                        if (!Status.canReserveToday(projectId, count)) return@execute targetCount == null
                        when (val action = detail.getString("applyAction")) {
                            "AVAILABLE" -> Unit
                            "LIMITED", "NO_STOCK", "ENERGY_LACK", "COMING" -> {
                                Log.forest("保护项目🏕️[$itemName] 当前不可申请[$action]")
                                return@execute false
                            }
                            else -> {
                                Log.error(TAG, "保护项目申请状态未知[$itemName] action=$action raw=$detail")
                                return@execute false
                            }
                        }
                        if (tree.optBoolean("overLimit") || !tree.optBoolean("hasBudget", true)) {
                            Log.forest("保护项目🏕️[$itemName]已达上限或项目预算不足，停止申请")
                            return@execute false
                        }
                        val cost = tree.getLong("energy")
                        val balance = detail.getLong("currentEnergy")
                        if (cost < 0L || balance < 0L) {
                            Log.error(TAG, "保护项目成本或余额无效[$itemName] raw=$detail")
                            return@execute false
                        }
                        if (balance - cost < energyReserve) {
                            Log.forest("保护项目🏕️[$itemName] 能量不足或需要保留${energyReserve}g，停止申请")
                            return@execute false
                        }
                    }
                    val submittedAt = System.currentTimeMillis()
                    val recordedCount = Status.getReserveTimes(projectId)
                    store.put(pendingKey, JSONObject().put("certCount", owned).put("aliasCount", aliasCounts?.sum() ?: 0)
                        .put("animal", aliasCounts != null).put("golden", golden).put("submittedAt", submittedAt)
                        .put("recordedCount", recordedCount).toString(), requirePersistence = true)
                    var response: JSONObject? = null
                    try {
                        response = JSONObject(
                            if (golden) ReserveRpcCall.applyGoldAnimalCert(projectId) else ReserveRpcCall.exchangeTree(projectId),
                        ).let { it.optJSONObject("resData") ?: it }
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException || t is InterruptedException) throw t
                        Log.printStackTrace(TAG, "保护项目申请结果不确定[$itemName]，先回查", t)
                    }
                    val accepted = response?.let { ResChecker.checkRes(TAG, it) } == true
                    if (response != null && !accepted) Log.error(TAG, "保护项目申请返回失败[$itemName] raw=$response")
                    if (ApplicationHookConstants.isOffline() ||
                        AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current
                    ) return@execute false
                    val refreshed = JSONObject(ReserveRpcCall.queryTreeForExchange(projectId)).let { it.optJSONObject("resData") ?: it }
                    if (AccountSessionCoordinator.checkCurrentSession(store.uid, epoch) !is AccountSessionCheck.Current) return@execute false
                    if (!ResChecker.checkRes(TAG, refreshed)) {
                        Log.error(TAG, "保护项目申请后回查失败[$itemName] raw=$refreshed")
                        return@execute false
                    }
                    val after = refreshed.getJSONObject("exchangeableTree").getInt("certCount")
                    val afterAliases = if (aliasCounts != null && !golden) {
                        val list = refreshed.getJSONArray("subTreeVOs")
                        (0 until list.length()).sumOf { list.getJSONObject(it).getInt("certCountForAlias") }
                    } else 0
                    val progressed = if (aliasCounts != null && !golden) afterAliases > aliasCounts.sum() else after > owned
                    if (!progressed) {
                        if (!accepted && response?.opt("success") == false && !response.has("rpcMethod")) {
                            store.remove(pendingKey)
                        }
                        Log.error(TAG, "保护项目申请后证书数量未确认[$itemName] raw=$refreshed")
                        return@execute false
                    }
                    if (!golden && TimeUtil.isSameDay(submittedAt, System.currentTimeMillis())) Status.reserveToday(projectId, 1)
                    store.remove(pendingKey)
                    val vitality = response?.optInt("vitalityAmount", 0) ?: 0
                    val progress = if (aliasCounts != null && !golden) "基础证书${aliasCounts.sum()}→$afterAliases" else "证书$owned→$after"
                    Log.forest("保护项目🏕️[$itemName]${if (golden) "金色证书" else "申请确认"}#$progress${if (vitality > 0) "，活力值+$vitality" else ""}")
                    if (!accepted) return@execute targetCount != null && after >= targetCount
                    detail = refreshed
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException || t is InterruptedException) throw t
                Log.printStackTrace(TAG, "保护项目申请异常[$itemName]", t)
            }
            false
        }

        @JvmStatic
        fun initReserve(forceRefresh: Boolean = false): List<ExchangeOptionRow> {
            val snapshot = ExchangeOptionsCache.getOrFetch(
                UserMap.currentUid, ExchangeOptionsRefreshBridge.TARGET_RESERVE, forceRefresh,
            ) { session ->
                val jsonResponse = session.read("treeItems", JSONObject(), "保护项目目录") {
                    JSONObject(ReserveRpcCall.queryTreeItemsForExchange())
                }
                check(ResChecker.checkRes(TAG, jsonResponse)) {
                    "刷新保护项目候选失败: ${jsonResponse.optString("resultDesc", "未知错误")}"
                }
                val treeItems = checkNotNull(jsonResponse.optJSONArray("treeItems")) {
                    "刷新保护项目候选失败: 缺少treeItems"
                }
                val rows = ArrayList<ExchangeOptionRow>()
                for (i in 0 until treeItems.length()) {
                    val item = treeItems.optJSONObject(i) ?: continue
                    if (item.optString("projectType") !in setOf("RESERVE", "TREE", "ANIMAL")) {
                        continue
                    }
                    val itemId = item.optString("itemId").trim()
                    if (itemId.isEmpty()) {
                        Log.runtime(TAG, "跳过缺少itemId的保护地候选")
                        continue
                    }
                    val itemName = item.optString("itemName", itemId)
                    val energy = if (item.has("energy")) "${item.getLong("energy")}g" else "成本待查询"
                    val typeName = when (item.optString("projectType")) {
                        "TREE" -> "树木"
                        "ANIMAL" -> "动物"
                        else -> "保护地"
                    }
                    rows.add(ExchangeOptionRow().apply {
                        id = itemId
                        name = "$typeName·$itemName($energy)"
                        rawName = itemName
                        sourceModule = "Reserve"
                        costText = energy
                    })
                }
                session.commit(JSONObject(), treeItems.length(), "整理保护项目")
                ExchangeOptionsSnapshot(rows, jsonResponse)
            }
            val reserveMap = IdMapManager.getInstance(ReserveaMap::class.java)
            reserveMap.clear()
            snapshot.rows.forEach { reserveMap.add(it.id, it.name) }
            check(reserveMap.save()) { "保存保护项目候选失败" }
            ReserveEntity.invalidate()
            Log.runtime(TAG, "刷新保护项目候选成功[${snapshot.rows.size}]")
            return snapshot.rows
        }
    }
}

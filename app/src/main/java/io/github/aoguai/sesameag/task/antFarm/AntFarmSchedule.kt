package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.UserDataStoreManager
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

private const val FARM_PENDING_WORK = "antFarmPendingWork"
internal val FARM_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

internal fun farmTimeToday(hour: Int, minute: Int): Long =
    LocalDate.now(FARM_ZONE).atTime(hour, minute).atZone(FARM_ZONE).toInstant().toEpochMilli()

internal fun AntFarm.scheduleFarmChildTask(
    childId: String,
    group: String,
    triggerAtMs: Long,
    extraPayload: JSONObject = JSONObject(),
) {
    val owner = AccountSessionCoordinator.currentUserId().orEmpty()
    val epoch = AccountSessionCoordinator.currentSessionEpoch()
    if (owner.isBlank() || epoch <= 0) return
    val farmId = ownerFarmId?.takeIf { it.isNotBlank() } ?: return
    val payload = JSONObject(extraPayload.toString()).put("farm_id", farmId)
    addChildTask(
        ModelTask.ChildModelTask(
            id = childId,
            group = group,
            suspendRunnable = {
                cancelPersistentChildTask(childId)
                runPersistentChildTask(childId, group, payload, "memory_timer", owner, epoch)
            },
            execTime = triggerAtMs,
        ),
    )
    registerPersistentChildTask(childId, group, triggerAtMs, payload)
}

internal fun AntFarm.cancelLoveChickenSchedule() {
    loveChickenChildTask?.cancel()
    loveChickenChildTask = null
    ownerFarmId?.takeIf { it.isNotBlank() }?.let { cancelPersistentChildTask("LC|$it") }
}

internal fun AntFarm.replaceLoveChickenSchedule(triggerAtMs: Long, extraPayload: JSONObject) {
    val owner = AccountSessionCoordinator.currentUserId().orEmpty()
    val epoch = AccountSessionCoordinator.currentSessionEpoch()
    val farmId = ownerFarmId?.takeIf { it.isNotBlank() } ?: return
    if (owner.isBlank() || epoch <= 0 || ApplicationHookConstants.isOffline()) return
    loveChickenChildTask?.cancel()
    loveChickenChildTask = null
    val childId = "LC|$farmId"
    val payload = JSONObject(extraPayload.toString()).put("farm_id", farmId)
    registerPersistentChildTask(childId, "LC", triggerAtMs, payload)
    // 长期等待交给持久唤醒；只有近一分钟的蹲点在内存等待。
    if (triggerAtMs - System.currentTimeMillis() > 60_000L) return
    lateinit var child: ModelTask.ChildModelTask
    child = ModelTask.ChildModelTask(
        id = childId, group = "LC", execTime = triggerAtMs,
        suspendRunnable = {
            if (loveChickenChildTask === child) {
                loveChickenChildTask = null
                detachChildTask(child)
                runPersistentChildTask(childId, "LC", payload, "memory_timer", owner, epoch)
            }
        },
    )
    loveChickenChildTask = child
    addChildTask(child)
}

internal fun AntFarm.deferFarmWork(reason: String, triggerAtMs: Long) {
    if (triggerAtMs <= System.currentTimeMillis()) return
    val store = UserDataStoreManager.getCurrentInstance() ?: return
    val pending = store.getOrCreate<MutableMap<String, Long>>(FARM_PENDING_WORK)
    if (pending[reason] == triggerAtMs) return
    pending[reason] = triggerAtMs
    store.put(FARM_PENDING_WORK, pending)
}

internal fun AntFarm.scheduleFarmPendingWork() {
    val store = UserDataStoreManager.getCurrentInstance() ?: return
    val pending = store.getOrCreate<MutableMap<String, Long>>(FARM_PENDING_WORK)
    val now = System.currentTimeMillis()
    val disabledReasons = pending.keys.filter { reason ->
        when (reason) {
            "meal" -> family?.value != true || familyOptions?.value?.contains("eatTogetherConfig") != true
            "draw" -> enableChouchoule?.value != true
            "exchange" -> enableChouchoule?.value != true || autoExchange?.value != true
            "rankAwards" -> donationCompetition?.value != true
            "loveChicken" -> loveChickenGathering?.value != true
            "zhimaPigeon" -> !isZhimaPigeonConfigured()
            "awards" -> false
            else -> true
        }
    }
    if (disabledReasons.isNotEmpty()) {
        disabledReasons.forEach { pending.remove(it) }
        store.put(FARM_PENDING_WORK, pending)
    }
    val next = pending.values.filter { it > now }.minOrNull() ?: return
    val farmId = ownerFarmId?.takeIf { it.isNotBlank() } ?: return
    scheduleFarmChildTask("FD|$farmId", "FD", next)
}

internal suspend fun AntFarm.runDueFarmWork() {
    if (ApplicationHookConstants.isOffline()) return
    val store = UserDataStoreManager.getCurrentInstance() ?: return
    val now = System.currentTimeMillis()
    val due = store.getOrCreate<MutableMap<String, Long>>(FARM_PENDING_WORK)
        .filterValues { it <= now }.keys.toList()
    for (reason in due) {
        if (ApplicationHookConstants.isOffline()) break
        when (reason) {
            "rankAwards" -> if (donationCompetition?.value == true) handleDonationCompetition()
            "loveChicken" -> if (loveChickenGathering?.value == true) {
                runFarmPriorityDonations()
                runLoveChickenGatheringWorkflow()
            }
            "zhimaPigeon" -> if (isZhimaPigeonConfigured()) runZhimaPigeonTaskFlow()
            "awards" -> receiveFarmAwards()
            "draw" -> if (enableChouchoule?.value == true) ChouChouLe().run(this)
            "exchange" -> if (enableChouchoule?.value == true && autoExchange?.value == true) ChouChouLe().run(this)
            "meal" -> if (family?.value == true && familyOptions?.value?.contains("eatTogetherConfig") == true) {
                AntFarmFamily.runMeal(this)
            }
        }
        // 到期待办只负责一次收尾；未确认的业务状态仍由下一轮自然调度读取。
        if (reason == "awards" || reason == "draw" || reason == "exchange" || reason == "loveChicken" || reason == "zhimaPigeon") {
            val pending = store.getOrCreate<MutableMap<String, Long>>(FARM_PENDING_WORK)
            if ((pending[reason] ?: Long.MAX_VALUE) <= now) {
                pending.remove(reason)
                store.put(FARM_PENDING_WORK, pending)
            }
        }
    }
    scheduleFarmPendingWork()
    if (due.isNotEmpty()) Log.farm("庄园到期待办已推进：${due.joinToString()}")
}

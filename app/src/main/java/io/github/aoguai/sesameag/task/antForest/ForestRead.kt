package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleKind
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleState
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.task.TaskExecutionReport
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowExecutionState
import io.github.aoguai.sesameag.task.common.TaskFlowRunResult
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.TimeTriggerEvaluator
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.TimeWindow
import io.github.aoguai.sesameag.util.UserDataStoreManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpRetryException
import java.util.Calendar
import java.util.concurrent.TimeUnit

internal const val FOREST_READ_CHILD_KIND = "forest_read"

internal suspend fun AntForest.runForestReadWorkflow() = forestReadQueue.submit {
    val result = ForestRead.run()
    if (result.deferredReason == DeferredReason.TIME_WINDOW) {
        result.deferredUntil?.let { scheduleForestRead(it) }
    }
    if (result.failureType != null) {
        TaskExecutionReport.current()?.recordFlow(
            TaskFlowExecutionState(), "蚂蚁森林", "无纸阅读", TaskFlowRunResult(false, false, false, 1, failureCount = 1),
        )
    }
}

internal fun scheduleForestRead(triggerAtMs: Long) {
    val context = requireNotNull(ApplicationHook.appContext)
    val owner = AccountSessionCoordinator.currentUserId().orEmpty()
    val epoch = AccountSessionCoordinator.currentSessionEpoch()
    require(owner.isNotBlank() && epoch > 0)
    val midnight = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    if (triggerAtMs >= midnight) return
    val key = "forest_read_$owner"
    if (PersistentScheduleRegistry.listFresh().any {
        it.dedupeKey == key && it.ownerUserId == owner && it.sessionEpoch == epoch &&
            it.state in setOf(PersistentScheduleState.SCHEDULED, PersistentScheduleState.QUEUED, PersistentScheduleState.RUNNING)
    }) return
    val payload = JSONObject().put("child_kind", FOREST_READ_CHILD_KIND).put("owner_user_id", owner)
        .put("session_epoch", epoch).put("day", System.currentTimeMillis())
    val schedule = UnifiedScheduler.schedulePersistentTrigger(
        context = context, name = "森林无纸阅读", kind = PersistentScheduleKind.MODULE_CHILD,
        triggerAtMs = triggerAtMs, dedupeKey = key, payloadJson = payload.toString(),
        toleranceMs = midnight - triggerAtMs, ownerUserId = owner, sessionEpoch = epoch,
    )
    check(schedule.state == PersistentScheduleState.SCHEDULED) { "森林阅读续接注册失败: ${schedule.lastError}" }
}

internal object ForestRead {
    fun run(): TaskFlowActionResult {
        val module = "蚂蚁森林"
        val taskId = "forestRead"
        if (taskId in TaskBlacklist.getBlacklist(module)) {
            Log.forest("无纸阅读[命中当日黑名单，本轮跳过]")
            return TaskFlowActionResult.success(progressChanged = false).copy(submissionStarted = false)
        }
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTFOREST_READ_DONE)) return TaskFlowActionResult.success(progressChanged = false)
        TaskCommon.update()
        if (TaskCommon.IS_ENERGY_TIME) {
            val spec = BaseModel.energyTime.getTriggerSpec()
            return TaskFlowActionResult.defer(
                DeferredReason.TIME_WINDOW,
                message = "只收能量时间内保留阅读进度，窗口结束后执行",
                deferredUntil = TimeTriggerEvaluator.nextTriggerAt(
                    spec.copy(allowWindows = listOf(TimeWindow(0, 86_400)), blockWindows = spec.allowWindows),
                ),
            )
        }
        val identity = RpcDailyCircuit.captureIdentity()
        val store = requireNotNull(UserDataStoreManager.getInstance(identity.userId))
        val key = "forest_read_state"
        val state = store.get(key, String::class.java)?.let(::JSONObject) ?: JSONObject()
        val finished = store.get("forest_read_finished_books", String::class.java)?.let(::JSONObject) ?: JSONObject()
        fun save() {
            check(RpcDailyCircuit.isCurrent(identity)) { "森林阅读账号会话已变化" }
            store.put(key, state.toString(), requirePersistence = true)
        }
        try {
            val now = System.currentTimeMillis()
            if (!TimeUtil.isSameDay(state.optLong("day"), now)) {
                state.remove("chapter")
                state.remove("catalog")
                state.put("day", now)
                save()
            }
            val client = ForestReadRpcCall(state.optString("token"))
            val cachedProgress = if (client.token.isNotBlank()) {
                try {
                    client.energy()
                } catch (e: HttpRetryException) {
                    if (e.responseCode() != 401) throw e
                    client.token = ""
                    state.remove("token")
                    save()
                    Log.error("ForestRead", "无纸阅读缓存登录态失效[401]，清理凭证并重新授权")
                    null
                }
            } else null
            var progress = cachedProgress ?: run {
                client.token = client.login()
                state.put("token", client.token)
                save()
                client.energy().also {
                    Log.forest("无纸阅读登录后收益查询成功，继续原进度")
                }
            }
            while (true) {
                var current = progress.getInt("current")
                var total = progress.getInt("total")
                require(current >= 0 && total > 0) { "森林阅读收益进度无效: $progress" }
                val reading = state.optJSONObject("chapter")
                if (current >= total && (reading == null || reading.optString("phase") == "READING")) {
                    check(RpcDailyCircuit.isCurrent(identity)) { "森林阅读账号会话已变化" }
                    Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_READ_DONE)
                    Log.forest("无纸阅读今日进度 $current/$total")
                    return TaskFlowActionResult.success()
                }
                if (reading == null) {
                    var catalog = state.optJSONArray("catalog")
                    var book: JSONObject?
                    while (true) {
                        val fromCache = catalog != null
                        val currentCatalog = catalog ?: client.index()
                        val freeBooks = linkedMapOf<String, JSONObject>()
                        for (i in 0 until currentCatalog.length()) {
                            val resources = currentCatalog.getJSONObject(i).optJSONArray("block_resource") ?: continue
                            for (j in 0 until resources.length()) {
                                val candidate = resources.getJSONObject(j)
                                val id = candidate.optString("book_id")
                                if (id.isNotBlank() && candidate.optString("price").toBigDecimalOrNull()?.signum() == 0 && !finished.has(id)) {
                                    freeBooks[id] = candidate
                                }
                            }
                        }
                        if (!fromCache) {
                            // index() 已校验 code=2000；只保存已解析的非空首页。
                            if (currentCatalog.length() > 0) state.put("catalog", currentCatalog) else state.remove("catalog")
                            save()
                        }
                        book = freeBooks[state.optString("bookId")] ?: freeBooks.values.firstOrNull()
                        if (book != null || !fromCache) break
                        catalog = null
                    }
                    if (book == null) {
                        Log.record("ForestRead", "无纸阅读当前没有未读免费书籍")
                        return TaskFlowActionResult.success(progressChanged = false)
                    }
                    val bookId = book.getString("book_id")
                    if (state.optString("bookId") != bookId) state.remove("nextChapterId")
                    state.put("bookId", bookId).put("bookName", book.optString("name", bookId))
                    val chapter = client.chapter(bookId, state.optString("nextChapterId"))
                    val chapterId = chapter.getString("id")
                    require(chapterId.isNotBlank() && chapter.has("next_id")) { "森林阅读章节结构不完整: $chapter" }
                    val started = System.currentTimeMillis()
                    // 保留六分钟本地章节阅读计时，后续按实际经过秒数上报，避免开始后立即提交零阅读时长。
                    val readyAt = started + TimeUnit.MINUTES.toMillis(6)
                    state.put("chapter", JSONObject().put("id", chapterId).put("name", chapter.optString("name"))
                        .put("nextId", if (chapter.isNull("next_id")) "" else chapter.getString("next_id"))
                        .put("before", current).put("started", started).put("readyAt", readyAt).put("phase", "READING"))
                    save()
                    return TaskFlowActionResult.defer(DeferredReason.TIME_WINDOW, "森林阅读等待时长", deferredUntil = readyAt)
                }
                val bookId = state.getString("bookId")
                val chapterId = reading.getString("id")
                if (reading.getString("phase") == "READING") {
                    val readyAt = reading.getLong("readyAt")
                    if (System.currentTimeMillis() < readyAt) {
                        return TaskFlowActionResult.defer(DeferredReason.TIME_WINDOW, "森林阅读等待时长", deferredUntil = readyAt)
                    }
                    reading.put("before", current).put("phase", "SUBMITTED")
                    save()
                    try {
                        client.report(bookId, chapterId,
                            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - reading.getLong("started")))
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException || t is InterruptedException ||
                            Thread.currentThread().isInterrupted || ApplicationHookConstants.isOffline() ||
                            !RpcDailyCircuit.isCurrent(identity)
                        ) throw t
                        if (t is ForestReadRpcCall.RejectedResponse ||
                            (t is HttpRetryException && t.responseCode() == 401)
                        ) {
                            reading.put("phase", "READING")
                            throw t
                        }
                        // 超时等异常可能出现在服务端记账之后，按该章节提交前的进度核对一次。
                        val recovered = try {
                            client.energy()
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
                            t.addSuppressed(e)
                            throw t
                        }
                        if (recovered.optInt("current", -1) <= current) throw t
                        Log.printStackTrace("ForestRead", "无纸阅读上报调用失败，收益回查已确认章节进度", t)
                    }
                    reading.put("phase", "REPORTED")
                    save()
                }
                if (reading.getString("phase") == "REPORTED") {
                    client.recent(bookId, chapterId, reading.optString("name"))
                    reading.put("phase", "RECENT")
                    save()
                }
                progress = client.energy()
                current = progress.getInt("current")
                total = progress.getInt("total")
                require(current >= 0 && total > 0) { "森林阅读收益进度无效: $progress" }
                val gain = current - reading.getInt("before")
                if (gain <= 0 && current < total) {
                    val message = "无纸阅读上报后本轮收益未推进，章节 $chapterId，当前 $current/$total"
                    save()
                    Log.error("ForestRead", "$message，保留章节进度")
                    AccountSessionCoordinator.withCurrentSession(identity) {
                        TaskBlacklist.addToBlacklist(module, taskId, "无纸阅读")
                    }
                    PersistentScheduleRegistry.removeByDedupeKey(ApplicationHook.appContext, "forest_read_${identity.userId}")
                    return TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, message = message)
                }
                if (gain > 0 || reading.getString("phase") == "RECENT") {
                    if (reading.getString("phase") == "SUBMITTED") {
                        client.recent(bookId, chapterId, reading.optString("name"))
                        reading.put("phase", "RECENT")
                        save()
                    }
                    if (gain > 0) {
                        Log.forest("无纸阅读[${state.optString("bookName")}] 获得${gain}g能量，进度 $current/$total")
                    }
                    val next = reading.getString("nextId")
                    require(next != chapterId) { "森林阅读章节游标未推进: $chapterId" }
                    if (next.isBlank() || next == "0" || next == "null") {
                        finished.put(bookId, state.optString("bookName"))
                        check(RpcDailyCircuit.isCurrent(identity)) { "森林阅读账号会话已变化" }
                        store.put("forest_read_finished_books", finished.toString(), requirePersistence = true)
                        state.remove("bookId")
                        state.remove("nextChapterId")
                    } else state.put("nextChapterId", next)
                    state.remove("chapter")
                    save()
                }
                if (current >= total) {
                    check(RpcDailyCircuit.isCurrent(identity)) { "森林阅读账号会话已变化" }
                    Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_READ_DONE)
                    return TaskFlowActionResult.success()
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException || t is InterruptedException ||
                Thread.currentThread().isInterrupted || ApplicationHookConstants.isOffline() || !RpcDailyCircuit.isCurrent(identity)) throw t
            if (t is HttpRetryException && t.responseCode() == 401) state.remove("token")
            save()
            Log.printStackTrace("ForestRead", "无纸阅读失败，保留章节进度", t)
            AccountSessionCoordinator.withCurrentSession(identity) {
                TaskBlacklist.addToBlacklist(module, taskId, "无纸阅读")
            }
            PersistentScheduleRegistry.removeByDedupeKey(ApplicationHook.appContext, "forest_read_${identity.userId}")
            val type = if (t is IOException && t !is HttpRetryException) TaskRpcFailureType.RETRYABLE_RPC
            else TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW
            return TaskFlowActionResult.failure(type, message = t.message.orEmpty())
        }
    }
}

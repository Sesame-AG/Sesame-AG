package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleKind
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleState
import io.github.aoguai.sesameag.hook.keepalive.UnifiedScheduler
import io.github.aoguai.sesameag.hook.rpc.RpcDailyCircuit
import io.github.aoguai.sesameag.task.common.DeferredReason
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.UserDataStoreManager
import org.json.JSONObject
import java.net.HttpRetryException
import java.net.SocketTimeoutException
import java.util.Calendar
import java.util.concurrent.TimeUnit

internal const val FOREST_READ_CHILD_KIND = "forest_read"

internal suspend fun AntForest.runForestReadWorkflow() = forestReadQueue.submit {
    val result = ForestRead.run()
    result.deferredUntil?.let { scheduleForestRead(it) }
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
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTFOREST_READ_DONE)) return TaskFlowActionResult.success(progressChanged = false)
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
                    val catalog = client.index()
                    val freeBooks = linkedMapOf<String, JSONObject>()
                    for (i in 0 until catalog.length()) {
                        val resources = catalog.getJSONObject(i).optJSONArray("block_resource") ?: continue
                        for (j in 0 until resources.length()) {
                            val book = resources.getJSONObject(j)
                            val id = book.optString("book_id")
                            if (id.isNotBlank() && book.optString("price").toBigDecimalOrNull()?.signum() == 0 && !finished.has(id)) {
                                freeBooks[id] = book
                            }
                        }
                    }
                    val book = freeBooks[state.optString("bookId")] ?: freeBooks.values.firstOrNull()
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
                        // 只有明确被拒的上报可重新提交，查询失败或结果未知时保留提交状态。
                        if (t is ForestReadRpcCall.RejectedResponse ||
                            (t is HttpRetryException && t.responseCode() == 401)
                        ) {
                            reading.put("phase", "READING")
                        }
                        throw t
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
                if (gain <= 0 && current < total && reading.getString("phase") == "SUBMITTED") {
                    Log.record("ForestRead", "无纸阅读上报后收益待确认，当前 $current/$total，保留章节 ${reading.getString("id")}")
                    return TaskFlowActionResult.defer(
                        DeferredReason.STATE_CONFIRMATION,
                        "森林阅读收益未推进",
                        // 结果未知时延后六分钟回查收益，保留 SUBMITTED 状态，避免重放可能已成功的上报。
                        deferredUntil = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(6),
                    )
                }
                if (gain > 0 || reading.getString("phase") == "RECENT") {
                    if (reading.getString("phase") == "SUBMITTED") {
                        client.recent(bookId, chapterId, reading.optString("name"))
                        reading.put("phase", "RECENT")
                        save()
                    }
                    if (gain > 0) {
                        Log.forest("无纸阅读[${state.optString("bookName")}] 获得${gain}g能量，进度 $current/$total")
                    } else {
                        Log.record("ForestRead", "无纸阅读章节上报及历史记录已确认，收益 $current/$total，继续下一章节")
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
            if (t is kotlinx.coroutines.CancellationException || t is InterruptedException || !RpcDailyCircuit.isCurrent(identity)) throw t
            if (t is HttpRetryException && t.responseCode() == 401) state.remove("token")
            save()
            Log.printStackTrace("ForestRead", "无纸阅读失败，保留进度:", t)
            if (t is SocketTimeoutException) {
                // 网络超时后按章节的六分钟本地间隔退避；SUBMITTED 只回查收益，不重发结果未知的上报。
                return TaskFlowActionResult.failure(TaskRpcFailureType.RETRYABLE_RPC, message = t.message.orEmpty())
                    .copy(deferredUntil = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(6))
            }
            return TaskFlowActionResult.failure(TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW, message = t.message.orEmpty())
        }
    }
}

package io.github.aoguai.sesameag.task.exchange

import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object ExchangeFetchProgress {
    data class Event(val message: String, val advances: Boolean)

    private val listeners = ConcurrentHashMap<Pair<String, String>, CopyOnWriteArraySet<(Event) -> Unit>>()

    fun subscribe(userId: String, target: String, listener: (Event) -> Unit): AutoCloseable {
        val key = userId to target
        listeners.compute(key) { _, current ->
            (current ?: CopyOnWriteArraySet()).also { it.add(listener) }
        }
        return AutoCloseable {
            listeners.computeIfPresent(key) { _, current ->
                current.remove(listener)
                current.takeIf { it.isNotEmpty() }
            }
        }
    }

    fun report(userId: String, target: String, message: String, advances: Boolean = true) {
        val name = when (target) {
            "sports_energy" -> "运动能量兑换"
            "member_point" -> "会员积分兑换"
            "bean_right" -> "安心豆兑换"
            "forest_vitality" -> "森林活力值兑换"
            "farm_paradise" -> "庄园乐园币兑换"
            "farm_ip_chouchoule" -> "IP抽抽乐商店"
            "mybank_welfare" -> "网商银行福利金兑换"
            "sesame_grain" -> "芝麻粒兑换"
            else -> target
        }
        val text = "$name：$message"
        when (target) {
            "sports_energy" -> Log.sports(text)
            "member_point", "bean_right" -> Log.member(text)
            "forest_vitality" -> Log.forest(text)
            "farm_paradise", "farm_ip_chouchoule" -> Log.farm(text)
            "mybank_welfare" -> Log.mybank(text)
            "sesame_grain" -> Log.sesame(text)
            else -> Log.record(text)
        }
        listeners[userId to target]?.forEach { listener ->
            runCatching { listener(Event(text, advances)) }
        }
    }
}

/** Replays committed reads locally; only the first uncommitted step makes an RPC. */
class ExchangeFetchSession private constructor(
    private val file: File,
    private val userId: String,
    private val target: String,
    val header: JSONObject,
    private val records: MutableList<JSONObject>,
    private val checkCurrent: () -> Unit
) {
    val runId: String = header.getString("runId")
    private var replayIndex = 0
    private var active: JSONObject? = null
    private var startedRequests = false
    private val startedAt = System.nanoTime()

    private val pageCount: Int get() = records.count { it.optInt("itemCount", -1) >= 0 }
    private val itemCount: Int get() = records.sumOf { it.optInt("itemCount", 0).coerceAtLeast(0) }

    fun read(stage: String, cursor: JSONObject, label: String, fetch: () -> JSONObject): JSONObject {
        checkCurrent()
        check(active == null) { "上一兑换列表步骤尚未保存" }
        val saved = records.getOrNull(replayIndex)
        if (saved != null) {
            check(saved.getString("stage") == stage && matchesContext(saved.getJSONObject("cursor"), cursor)) {
                "兑换列表断点与当前查询步骤不一致，请手动刷新"
            }
            active = saved
            return JSONObject(saved.getJSONObject("response").toString())
        }
        active = JSONObject().put("sequence", replayIndex + 1).put("stage", stage)
            .put("cursor", cursor).put("label", label)
        ExchangeFetchProgress.report(userId, target, "正在查询$label，已保存 $pageCount 页")
        if (!startedRequests) {
            ExchangeFetchPacing.domainStartDelay()
            startedRequests = true
        }
        checkCurrent()
        val response = fetch()
        active!!.put("response", response)
        return response
    }

    fun commit(nextState: JSONObject, itemCount: Int = -1, nextLabel: String = "最终处理") {
        checkCurrent()
        val current = checkNotNull(active) { "兑换列表没有待保存步骤" }
        if (replayIndex >= records.size) {
            check(current.has("response")) { "兑换列表请求尚未成功" }
            current.put("nextState", nextState).put("itemCount", itemCount).put("nextLabel", nextLabel)
            Files.appendSynced(file, current.toString() + "\n")
            records.add(current)
            checkCurrent()
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000L
            val count = if (itemCount >= 0) "本页 $itemCount 条，" else ""
            ExchangeFetchProgress.report(userId, target,
                "${current.getString("label")}已保存，${count}累计 $pageCount 页、${this.itemCount} 条原始记录，下一步：$nextLabel，用时 $elapsed 秒")
        }
        replayIndex++
        active = null
    }

    fun pageTurnDelay(baseMillis: Long = 850L) {
        if (replayIndex >= records.size) {
            checkCurrent()
            ExchangeFetchPacing.pageTurnDelay(baseMillis = baseMillis)
        }
    }

    fun finish() {
        checkCurrent()
        check(active == null && replayIndex == records.size) { "兑换列表仍有未完成步骤" }
    }

    fun reportFailure() {
        val position = active?.optString("label")
            ?: records.lastOrNull()?.optString("nextLabel") ?: "初始化"
        ExchangeFetchProgress.report(userId, target,
            "${position}失败或中断，已保存 $pageCount 页、$itemCount 条原始记录，下次从此处继续", advances = false)
    }

    companion object {
        internal fun matchesContext(stored: JSONObject, expected: JSONObject): Boolean =
            stored.length() == expected.length() && expected.keys().asSequence().all { key ->
                stored.opt(key)?.toString() == expected.opt(key)?.toString()
            }

        internal fun readHeader(file: File): JSONObject? = runCatching {
            if (!file.exists()) return null
            Files.withInputStream(file) { input -> input.bufferedReader(Charsets.UTF_8).use { reader -> reader.readLine()?.let { JSONObject(it) } } }
        }.getOrNull()

        internal fun isCurrentHeader(header: JSONObject, userId: String, target: String): Boolean =
            header.optString("userId") == userId && header.optString("target") == target &&
                header.optString("runId").isNotBlank() && header.optJSONObject("context") != null &&
                TimeUtil.isSameDay(header.optLong("createdAt", 0L), System.currentTimeMillis())

        internal fun open(
            file: File, userId: String, target: String, context: JSONObject,
            checkCurrent: () -> Unit
        ): ExchangeFetchSession {
            checkCurrent()
            var header = readHeader(file)
            val records = mutableListOf<JSONObject>()
            val validHeader = header != null && isCurrentHeader(header, userId, target) &&
                matchesContext(header.optJSONObject("context") ?: JSONObject(), context)
            if (validHeader) {
                val restored = runCatching {
                    io.github.aoguai.sesameag.storage.StoreAccess.transaction {
                        var validEnd = 0L
                        var interrupted = false
                        Files.withInputStream(file) { input ->
                            input.bufferedReader(Charsets.UTF_8).use { reader ->
                                fun line(): Pair<String, Boolean>? {
                                    val text = StringBuilder()
                                    while (true) {
                                        val char = reader.read()
                                        if (char == -1) return if (text.isEmpty()) null else text.toString() to false
                                        if (char == 10) return text.toString() to true
                                        text.append(char.toChar())
                                    }
                                }
                                val first = checkNotNull(line()) { "断点头部缺失" }
                                check(first.second) { "断点头部未完整保存" }
                                validEnd = first.first.toByteArray(Charsets.UTF_8).size + 1L
                                while (true) {
                                    val next = line() ?: break
                                    if (!next.second) { interrupted = true; break }
                                    val record = JSONObject(next.first)
                                    check(record.getInt("sequence") == records.size + 1) { "步骤序号不连续" }
                                    record.getString("stage")
                                    record.getString("label")
                                    record.getJSONObject("cursor")
                                    record.getJSONObject("response")
                                    record.getJSONObject("nextState")
                                    record.getInt("itemCount")
                                    record.getString("nextLabel")
                                    records.add(record)
                                    validEnd += next.first.toByteArray(Charsets.UTF_8).size + 1L
                                }
                            }
                        }
                        if (interrupted) {
                            checkCurrent()
                            Files.truncate(file, validEnd)
                            ExchangeFetchProgress.report(userId, target, "上次写入中断，保留完整步骤并重试未保存页")
                        }
                    }
                }
                if (restored.isFailure) {
                    ExchangeFetchProgress.report(userId, target, "断点记录损坏，重新建立本轮查询；旧完整缓存保留")
                    header = null
                    records.clear()
                }
            } else {
                if (file.exists() && file.length() > 0L) {
                    ExchangeFetchProgress.report(userId, target, "断点日期或查询条件已变化，重新查询")
                }
                header = null
            }
            if (header == null) {
                checkCurrent()
                header = JSONObject().put("userId", userId).put("target", target)
                    .put("createdAt", System.currentTimeMillis()).put("runId", UUID.randomUUID().toString())
                    .put("context", context)
                checkCurrent()
                check(Files.writeAtomically(header.toString() + "\n", file)) { "无法保存断点头部" }
                ExchangeFetchProgress.report(userId, target, "开始新一轮完整列表查询")
            }
            return ExchangeFetchSession(file, userId, target, header, records, checkCurrent).also { session ->
                if (records.isNotEmpty()) {
                    ExchangeFetchProgress.report(userId, target,
                        "恢复进度，已保存 ${session.pageCount} 页、${session.itemCount} 条原始记录，下一步：${records.last().getString("nextLabel")}")
                }
            }
        }
    }
}

package io.github.aoguai.sesameag.task.antMember

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

internal fun AntMember.runBillBlockWorld() {
    BillBlockWorldWorkflow().run()
}

private data class BlockWorldCanvas(
    val chapterId: String,
    val seasonId: String,
    val width: Int,
    val length: Int,
)

private data class BlockWorldBlock(
    val recordId: String,
    val configId: String,
    val level: Int,
    val width: Int,
    val length: Int,
    val posX: Int? = null,
    val posY: Int? = null,
)

private data class BlockWorldChapter(
    val id: String,
    val completed: Boolean,
    val status: String,
    val taskStatus: String,
    val targetType: String,
    val currentValue: Int,
)

private data class BlockWorldSnapshot(
    val canvas: BlockWorldCanvas,
    val chapters: List<BlockWorldChapter>,
    val pendingBlocks: List<BlockWorldBlock>,
    val placedBlocks: List<BlockWorldBlock>,
    val warehouseBlocks: List<BlockWorldBlock>?,
    val detailBlockConfigId: String,
    val dailyProductAmt: Int?,
    val coinBalance: Int?,
    val prosperityLevel: Int?,
    val prosperityProgress: Int?,
    val stickerCount: Int?,
) {
    fun currentChapter(): BlockWorldChapter? = chapters.firstOrNull {
        it.status != "REWARDED" && it.taskStatus != "REWARDED" &&
            (it.completed || it.status in setOf("IN_PROGRESS", "COMPLETED") || it.taskStatus == "COMPLETED")
    } ?: chapters.firstOrNull { it.id == canvas.chapterId }
}

private data class BlockWorldPosition(
    val x: Int,
    val y: Int,
)

private data class BlockWorldActionResult(
    val performed: Boolean,
    val responseConfirmedProgress: Boolean = false,
)

private class BillBlockWorldWorkflow {
    private val createdBlockIds = linkedSetOf<String>()
    private val syncedCreatedBlockIds = linkedSetOf<String>()
    private val reclaimedBlockIds = linkedSetOf<String>()

    fun run() {
        var snapshot = queryHome() ?: return
        while (true) {
            var canvasSyncOnly = false
            val pendingDailyProductAmt = snapshot.dailyProductAmt
            val chapter = snapshot.currentChapter()
            val (actionName, action) =
                when {
                    pendingDailyProductAmt != null && pendingDailyProductAmt > 0 -> {
                        "领取每日贴贴币" to collectDailyProductCoin()
                    }

                    createdBlockIds.any { it !in syncedCreatedBlockIds } -> {
                        canvasSyncOnly = true
                        "同步完整画布" to performCanvasSync(snapshot)
                    }

                    chapter != null && isCompleted(chapter) && !isRewarded(chapter) -> {
                        "推进章节" to BlockWorldActionResult(performed = advanceChapter(chapter.id))
                    }

                    snapshot.pendingBlocks.isNotEmpty() ||
                        snapshot.warehouseBlocks?.any { it.recordId !in reclaimedBlockIds } == true -> {
                        "放置免费贴纸或腾出空间" to performPlace(snapshot)
                    }

                    findSafeMergePair(snapshot) != null -> {
                        "合并画布贴纸" to performMerge(snapshot)
                    }

                    chapter != null && !isRewarded(chapter) -> {
                        chapter.targetType to performChapterAction(snapshot, chapter)
                    }

                    chapter == null -> {
                        Log.member("账单拼贴世界⏭️免费贴纸处理结束，未找到当前章节，不写入今日完成标识")
                        return
                    }

                    else -> {
                        if (snapshot.dailyProductAmt != 0 || snapshot.warehouseBlocks == null) {
                            Log.member("账单拼贴世界⏭️每日贴贴币或仓库状态未确认，当前不写入今日完成标识")
                            return
                        }
                        Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_BILL_BLOCK_WORLD_DONE)
                        Log.member("账单拼贴世界✅当前章节已领奖，免费贴纸及画布合并处理结束；腾位回收的贴纸保留在仓库")
                        return
                    }
                }
            val refreshed = queryHome() ?: return
            if (!action.performed) return
            val unconfirmedPlacedIds = createdBlockIds.filter { recordId ->
                refreshed.placedBlocks.none { it.recordId == recordId }
            }
            if (unconfirmedPlacedIds.isNotEmpty()) {
                Log.error("AntMemberBillBlockWorld", "贴纸放置响应与首页回查不一致，保留后续查询#${unconfirmedPlacedIds.joinToString()}")
                return
            }
            if (refreshed.prosperityLevel != snapshot.prosperityLevel ||
                refreshed.prosperityProgress != snapshot.prosperityProgress ||
                refreshed.stickerCount != snapshot.stickerCount
            ) {
                val levelIncreased = snapshot.prosperityLevel?.let { before ->
                    refreshed.prosperityLevel?.let { after -> after > before }
                } == true
                Log.member("账单拼贴世界${if (levelIncreased) "繁荣度升级确认" else "繁荣度状态回查"}#等级=${snapshot.prosperityLevel ?: "未知"}→${refreshed.prosperityLevel ?: "未知"} 进度=${snapshot.prosperityProgress ?: "未知"}→${refreshed.prosperityProgress ?: "未知"} 贴纸数=${snapshot.stickerCount ?: "未知"}→${refreshed.stickerCount ?: "未知"}")
            }
            if (actionName == "领取每日贴贴币") {
                val pendingCleared = refreshed.dailyProductAmt == 0
                val balanceIncreased =
                    snapshot.coinBalance?.let { before ->
                        refreshed.coinBalance?.let { after -> after > before }
                    } == true
                if (!pendingCleared && !balanceIncreased && !action.responseConfirmedProgress) {
                    Log.member("账单拼贴世界⏭️每日贴贴币领取后未获得可确认的数量或余额推进，停止当前链路")
                    return
                }
            }
            if (snapshotStateKey(refreshed) == snapshotStateKey(snapshot) &&
                (!canvasSyncOnly || createdBlockIds.any { it !in syncedCreatedBlockIds })
            ) {
                Log.member("账单拼贴世界⏭️[$actionName]回查未发现状态变化，停止当前链路")
                return
            }
            snapshot = refreshed
        }
    }

    private fun queryHome(): BlockWorldSnapshot? {
        val response =
            runCatching {
                JSONObject(AntMemberRpcCall.queryBillBlockWorldHome())
            }.getOrElse {
                Log.member("账单拼贴世界❌首页查询响应解析失败:${it.message}")
                return null
            }
        if (!isSuccess(response)) {
            Log.member("账单拼贴世界❌首页查询失败:$response")
            return null
        }
        val data = response.optJSONObject("data")
        val canvasJson = data?.optJSONObject("canvas")
        if (data == null || canvasJson == null) {
            Log.member("账单拼贴世界❌首页缺少data.canvas，停止当前链路")
            return null
        }
        val canvas =
            BlockWorldCanvas(
                chapterId = canvasJson.optString("currentChapterId"),
                seasonId = canvasJson.optString("seasonId"),
                width = canvasJson.optInt("canvasWidth", 0),
                length = canvasJson.optInt("canvasLength", 0),
            )
        if (canvas.seasonId.isBlank() || canvas.width <= 0 || canvas.length <= 0) {
            Log.member("账单拼贴世界❌画布状态不完整，停止当前链路")
            return null
        }
        val pendingBlockItems = data.optJSONArray("pendingBlocks")
        val placedBlockItems = data.optJSONArray("placedBlocks")
        if (pendingBlockItems == null || placedBlockItems == null) {
            Log.error("AntMemberBillBlockWorld", "首页缺少pendingBlocks或placedBlocks，不将未知画布视为空画布")
            return null
        }
        val pendingBlocks = parseBlocks(pendingBlockItems, includePosition = false)
        val placedBlocks = parseBlocks(placedBlockItems, includePosition = true)
        if (pendingBlocks.size != pendingBlockItems.length() || placedBlocks.size != placedBlockItems.length() ||
            placedBlocks.any {
                !isValidBlock(it) || it.posX == null || it.posY == null ||
                    it.posX < 0 || it.posY < 0 ||
                    it.posX + it.width > canvas.width || it.posY + it.length > canvas.length
            }
        ) {
            Log.error("AntMemberBillBlockWorld", "首页贴纸标识、尺寸或位置不完整，保留当前画布")
            return null
        }
        val dailyProductAmt = optionalInt(data, "dailyProductAmt")
        val coinBalance = optionalInt(data, "coinBalance")
        if (dailyProductAmt == null) {
            Log.member("账单拼贴世界⏭️首页缺少dailyProductAmt，当前无法确认每日贴贴币状态")
        }
        if (dailyProductAmt != null && dailyProductAmt > 0 && coinBalance == null) {
            Log.member("账单拼贴世界⏭️首页缺少coinBalance，将仅依据待领数量或动作响应确认领取结果")
        }
        val warehouse = callAction("查询贴纸仓库") {
            AntMemberRpcCall.queryBillBlockWorldWarehouse(canvas.seasonId)
        }
        val groups = warehouse?.optJSONArray("blocks")
        val warehouseBlocks = if (groups != null) {
            val blocks = JSONArray()
            var completeWarehouse = true
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index)
                val records = group?.optJSONArray("blocks")
                if (group == null || records == null) {
                    completeWarehouse = false
                    continue
                }
                for (recordIndex in 0 until records.length()) {
                    val record = records.optJSONObject(recordIndex)
                    if (record == null || record.optString("blockRecordId").isBlank()) {
                        completeWarehouse = false
                        continue
                    }
                    blocks.put(JSONObject(group.toString()).put("blockRecordId", record.optString("blockRecordId")))
                }
            }
            if (completeWarehouse) {
                parseBlocks(blocks, includePosition = false)
            } else {
                Log.error("AntMemberBillBlockWorld", "贴纸仓库分组或记录不完整，不确认仓库已处理完成 raw=$warehouse")
                null
            }
        } else {
            if (warehouse != null) Log.error("AntMemberBillBlockWorld", "贴纸仓库缺少blocks raw=$warehouse")
            null
        }
        val prosperityInfo = data.optJSONObject("prosperityInfo")
        return BlockWorldSnapshot(
            canvas = canvas,
            chapters = parseChapters(data.optJSONArray("chapterTasks")),
            pendingBlocks = pendingBlocks,
            placedBlocks = placedBlocks,
            warehouseBlocks = warehouseBlocks,
            detailBlockConfigId = data.optJSONObject("normalBlockRes")?.optJSONArray("blockDetailList")?.let { blocks ->
                (0 until blocks.length()).asSequence()
                    .mapNotNull { blocks.optJSONObject(it)?.optString("blockConfigId") }
                    .firstOrNull { it.isNotBlank() }
            }.orEmpty(),
            dailyProductAmt = dailyProductAmt,
            coinBalance = coinBalance,
            prosperityLevel = prosperityInfo?.let { optionalInt(it, "level") },
            prosperityProgress = prosperityInfo?.let { optionalInt(it, "progress") },
            stickerCount = prosperityInfo?.let { optionalInt(it, "stickerCount") },
        )
    }

    private fun optionalInt(
        data: JSONObject,
        key: String,
    ): Int? = data.takeIf { it.has(key) && !it.isNull(key) }?.optInt(key)

    private fun parseChapters(chapters: JSONArray?): List<BlockWorldChapter> {
        if (chapters == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until chapters.length()) {
                val chapter = chapters.optJSONObject(index) ?: continue
                val task = chapter.optJSONObject("task")
                val id = chapter.optString("chapterId")
                if (id.isBlank()) {
                    continue
                }
                add(
                    BlockWorldChapter(
                        id = id,
                        completed = chapter.optBoolean("completed", false),
                        status = chapter.optString("status").uppercase(),
                        taskStatus = task?.optString("status").orEmpty().uppercase(),
                        targetType = task?.optString("targetType").orEmpty(),
                        currentValue = task?.optInt("currentValue", 0) ?: 0,
                    ),
                )
            }
        }
    }

    private fun parseBlocks(
        blocks: JSONArray?,
        includePosition: Boolean,
    ): List<BlockWorldBlock> {
        if (blocks == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until blocks.length()) {
                val block = blocks.optJSONObject(index) ?: continue
                val recordId = block.optString("blockRecordId")
                if (recordId.isBlank()) {
                    continue
                }
                add(
                    BlockWorldBlock(
                        recordId = recordId,
                        configId = block.optString("blockConfigId"),
                        level = block.optInt("level", 0),
                        width = block.optInt("width", 0),
                        length = block.optInt("length", 0),
                        posX = if (includePosition) optionalInt(block, "posX") else null,
                        posY = if (includePosition) optionalInt(block, "posY") else null,
                    ),
                )
            }
        }
    }

    private fun performChapterAction(
        snapshot: BlockWorldSnapshot,
        chapter: BlockWorldChapter,
    ): BlockWorldActionResult =
        when (chapter.targetType) {
            "PLACE_BLOCK" -> {
                performPlace(snapshot, forChapter = true)
            }

            "MOVE_BLOCK" -> {
                performCanvasSync(snapshot, moveOne = true)
            }

            "MERGE_COUNT" -> {
                performMerge(snapshot)
            }

            "RECLAIM_BLOCK" -> {
                performReclaim(snapshot)
            }

            "VIEW_BLOCK_DETAIL" -> {
                performViewBlockDetail(snapshot)
            }

            else -> {
                Log.member("账单拼贴世界⏭️未支持章节任务类型=${chapter.targetType.ifBlank { "UNKNOWN" }}，停止当前链路")
                BlockWorldActionResult(performed = false)
            }
        }

    private fun isCompleted(chapter: BlockWorldChapter): Boolean =
        chapter.completed ||
            chapter.status in setOf("COMPLETED", "REWARDED") ||
            chapter.taskStatus in setOf("COMPLETED", "REWARDED")

    private fun isRewarded(chapter: BlockWorldChapter): Boolean = chapter.status == "REWARDED" || chapter.taskStatus == "REWARDED"

    private fun isSuccess(response: JSONObject): Boolean = response.optBoolean("success", false) && response.optInt("resultCode", -1) == 200

    private fun snapshotStateKey(snapshot: BlockWorldSnapshot): String =
        buildString {
            append(snapshot.canvas.chapterId)
            append('|')
            append(snapshot.canvas.seasonId)
            append('|')
            append(snapshot.canvas.width)
            append('x')
            append(snapshot.canvas.length)
            append("|dailyProduct:")
            append(snapshot.dailyProductAmt)
            append(':')
            append(snapshot.coinBalance)
            append("|prosperity:")
            append(snapshot.prosperityLevel)
            append(':')
            append(snapshot.prosperityProgress)
            append(':')
            append(snapshot.stickerCount)
            snapshot.chapters.sortedBy { it.id }.forEach { chapter ->
                append("|chapter:")
                append(chapter.id)
                append(':')
                append(chapter.completed)
                append(':')
                append(chapter.status)
                append(':')
                append(chapter.taskStatus)
                append(':')
                append(chapter.currentValue)
            }
            snapshot.pendingBlocks.sortedBy { it.recordId }.forEach { block ->
                append("|pending:")
                appendBlockState(block)
            }
            snapshot.placedBlocks.sortedBy { it.recordId }.forEach { block ->
                append("|placed:")
                appendBlockState(block)
            }
            append("|warehouse:")
            if (snapshot.warehouseBlocks == null) {
                append("UNKNOWN")
            } else {
                snapshot.warehouseBlocks.sortedBy { it.recordId }.forEach { block ->
                    append('|')
                    appendBlockState(block)
                }
            }
        }

    private fun StringBuilder.appendBlockState(block: BlockWorldBlock) {
        append(block.recordId)
        append(':')
        append(block.configId)
        append(':')
        append(block.level)
        append(':')
        append(block.width)
        append('x')
        append(block.length)
        append(':')
        append(block.posX)
        append(':')
        append(block.posY)
    }

    private fun performViewBlockDetail(snapshot: BlockWorldSnapshot): BlockWorldActionResult {
        val blockConfigId = snapshot.detailBlockConfigId
        if (blockConfigId.isBlank()) {
            Log.error("AntMemberBillBlockWorld", "账单拼贴世界查看详情缺少blockConfigId，保留当前章节")
            return BlockWorldActionResult(performed = false)
        }
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR).toString()
        val month = (calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val response = runCatching {
            JSONObject(AntMemberRpcCall.queryBillBlockWorldDetail(blockConfigId, year, month))
        }.getOrElse {
            Log.printStackTrace("AntMemberBillBlockWorld", "queryBlockDetail err:", it)
            return BlockWorldActionResult(performed = false)
        }
        if (!isSuccess(response)) {
            Log.error("AntMemberBillBlockWorld", "账单拼贴世界查看详情失败 blockConfigId=$blockConfigId raw=$response")
            return BlockWorldActionResult(performed = false)
        }
        callAction("上报查看贴纸") { AntMemberRpcCall.reportBillBlockViewed() }
            ?: return BlockWorldActionResult(performed = false)
        Log.member("账单拼贴世界[查看贴纸详情并上报]#$blockConfigId，等待章节回查")
        return BlockWorldActionResult(performed = true)
    }

    private fun performPlace(
        snapshot: BlockWorldSnapshot,
        forMerge: Boolean = false,
        forChapter: Boolean = false,
    ): BlockWorldActionResult {
        if (snapshot.placedBlocks.any { it.posX == null || it.posY == null || !isValidBlock(it) }) {
            Log.error("AntMemberBillBlockWorld", "画布贴纸结构不完整，无法计算不重叠的放置坐标")
            return BlockWorldActionResult(performed = false)
        }
        val pendingCandidates = if (forMerge) {
            listOfNotNull(findPendingMergeBlock(snapshot))
        } else snapshot.pendingBlocks
        val collected = collectPendingBlocks(snapshot, pendingCandidates)
        if (collected.performed) return collected

        val availableWarehouseBlocks = snapshot.warehouseBlocks.orEmpty().filter { block ->
            (forChapter || block.recordId !in reclaimedBlockIds) &&
                snapshot.placedBlocks.none { it.recordId == block.recordId }
        }
        val warehouseCandidates = if (forMerge) {
            availableWarehouseBlocks.filter { candidate ->
                (snapshot.placedBlocks + snapshot.pendingBlocks + availableWarehouseBlocks).any { other ->
                    candidate.recordId != other.recordId && canMerge(candidate, other)
                }
            }
        } else availableWarehouseBlocks
        val reclaimedBlock = findBestPlacement(snapshot, warehouseCandidates)
        if (reclaimedBlock == null) {
            val placementTarget = (pendingCandidates + warehouseCandidates).firstOrNull {
                isValidBlock(it) && it.width <= snapshot.canvas.width && it.length <= snapshot.canvas.length
            }
            if (placementTarget != null) {
                val moved = performCanvasSync(snapshot, placementTarget = placementTarget)
                if (moved.performed) return moved
                if (findSafeMergePair(snapshot) != null) return performMerge(snapshot)
                if (pendingCandidates.any { it.recordId == placementTarget.recordId }) {
                    return performReclaim(snapshot)
                }
            }
            if (!forChapter) {
                val chapter = snapshot.currentChapter()
                if (chapter != null && !isCompleted(chapter)) {
                    return performChapterAction(snapshot, chapter)
                }
            }
            Log.member(if (forMerge) "账单拼贴世界⏭️画布、待领取和仓库中没有可放置合成的同类贴纸，保留当前章节" else "账单拼贴世界⏭️没有可安全放置的贴纸块，停止当前链路")
            return BlockWorldActionResult(performed = false)
        }
        val (block, position) = reclaimedBlock
        val response =
            callAction("重新放置贴纸") {
                AntMemberRpcCall.placeBillBlockWorldBlock(block.recordId, position.x, position.y)
            } ?: return BlockWorldActionResult(performed = true)
        if (response.optString("status") != "PLACED") {
            Log.error("AntMemberBillBlockWorld", "重新放置贴纸未确认PLACED blockRecordId=${block.recordId} raw=$response")
            return BlockWorldActionResult(performed = true)
        }
        createdBlockIds.add(block.recordId)
        Log.member("账单拼贴世界🧩仓库贴纸放置已接受#${block.recordId}，等待首页回查")
        return BlockWorldActionResult(performed = true)
    }

    private fun collectDailyProductCoin(): BlockWorldActionResult {
        val rawResponse =
            runCatching { AntMemberRpcCall.collectBillBlockWorldDailyProductCoin() }.getOrElse {
                Log.member("账单拼贴世界❌领取每日贴贴币请求失败:${it.message}")
                return BlockWorldActionResult(performed = false)
            }
        val response =
            runCatching { JSONObject(rawResponse) }.getOrElse {
                Log.member("账单拼贴世界❌领取每日贴贴币响应解析失败:${it.message}")
                return BlockWorldActionResult(performed = true)
            }
        if (!isSuccess(response)) {
            val code = response.optString("resultCode").ifBlank { "UNKNOWN" }
            val message = response.optString("message").ifBlank { response.optString("resultDesc") }
            Log.member("账单拼贴世界❌领取每日贴贴币失败#code=$code message=${message.ifBlank { response.toString() }}")
            return BlockWorldActionResult(performed = true)
        }
        val gainedCoinAmt = response.optInt("gainedCoinAmt", 0)
        Log.member("账单拼贴世界💰领取每日贴贴币${if (gainedCoinAmt > 0) "#$gainedCoinAmt" else ""}")
        return BlockWorldActionResult(
            performed = true,
            responseConfirmedProgress = gainedCoinAmt > 0,
        )
    }

    private fun collectPendingBlocks(
        snapshot: BlockWorldSnapshot,
        candidates: List<BlockWorldBlock>,
    ): BlockWorldActionResult {
        val remaining = candidates.distinctBy { it.recordId }.toMutableList()
        val blockItems = JSONArray()
        var plannedSnapshot = snapshot
        while (remaining.isNotEmpty()) {
            val (block, position) = findBestPlacement(plannedSnapshot, remaining) ?: break
            blockItems.put(
                JSONObject()
                    .put("blockRecordId", block.recordId)
                    .put("posX", position.x)
                    .put("posY", position.y),
            )
            plannedSnapshot = plannedSnapshot.copy(
                placedBlocks = plannedSnapshot.placedBlocks + block.copy(posX = position.x, posY = position.y),
            )
            remaining.remove(block)
        }
        if (blockItems.length() == 0) return BlockWorldActionResult(performed = false)
        val response = runCatching {
            JSONObject(AntMemberRpcCall.batchCollectBillBlockWorldBlocks(snapshot.canvas.seasonId, blockItems))
        }.getOrElse {
            Log.printStackTrace("AntMemberBillBlockWorld", "批量领取贴纸响应异常，等待首页回查:", it)
            return BlockWorldActionResult(performed = true)
        }
        if (!isSuccess(response)) {
            Log.error("AntMemberBillBlockWorld", "批量领取贴纸失败 raw=$response")
        }
        val itemResults = response.optJSONArray("itemResults")
        if (itemResults == null) {
            Log.error("AntMemberBillBlockWorld", "批量领取贴纸缺少itemResults raw=$response")
            return BlockWorldActionResult(performed = true)
        }
        val results = (0 until itemResults.length()).mapNotNull { itemResults.optJSONObject(it) }
        for (index in 0 until blockItems.length()) {
            val recordId = blockItems.getJSONObject(index).getString("blockRecordId")
            val result = results.singleOrNull { it.optString("blockRecordId") == recordId }
            if (result != null && result.optString("outcome") == "SUCCESS" &&
                result.optString("status") == "PLACED" &&
                (!result.has("failCode") || result.optString("failCode") == "0")
            ) {
                createdBlockIds.add(recordId)
                Log.member("账单拼贴世界🧩批量贴纸放置已接受#$recordId，等待首页回查")
            } else {
                Log.error("AntMemberBillBlockWorld", "批量贴纸逐项放置未确认 blockRecordId=$recordId raw=${result ?: response}")
            }
        }
        return BlockWorldActionResult(performed = true)
    }

    private fun performCanvasSync(
        snapshot: BlockWorldSnapshot,
        moveOne: Boolean = false,
        placementTarget: BlockWorldBlock? = null,
    ): BlockWorldActionResult {
        if (snapshot.placedBlocks.any { it.posX == null || it.posY == null || !isValidBlock(it) }) {
            Log.member("账单拼贴世界⏭️画布缺少可同步的完整贴纸结构，停止当前链路")
            return BlockWorldActionResult(performed = false)
        }
        val plannedBlocks = if (moveOne) {
            snapshot.placedBlocks.sortedBy { it.recordId !in createdBlockIds }.firstNotNullOfOrNull { block ->
                val others = snapshot.placedBlocks.filterNot { it.recordId == block.recordId }
                findFreePosition(snapshot.copy(placedBlocks = others), block, excludeCurrentPosition = true)?.let { position ->
                    snapshot.placedBlocks.map {
                        if (it.recordId == block.recordId) it.copy(posX = position.x, posY = position.y) else it
                    }
                }
            }
        } else if (placementTarget != null) {
            planCanvasPlacement(snapshot, placementTarget)
        } else snapshot.placedBlocks
        if (plannedBlocks == null) return BlockWorldActionResult(performed = false)
        val positions =
            JSONArray().apply {
                plannedBlocks.forEach { block ->
                    put(
                        JSONObject()
                            .put("blockRecordId", block.recordId)
                            .put("posX", block.posX)
                            .put("posY", block.posY),
                    )
                }
            }
        callAction("优化同步画布") {
            AntMemberRpcCall.syncBillBlockWorldCanvas(snapshot.canvas.seasonId, positions)
        } ?: return BlockWorldActionResult(performed = true)
        syncedCreatedBlockIds.addAll(createdBlockIds)
        Log.member("账单拼贴世界🧩完整画布同步请求已接受，等待首页回查")
        return BlockWorldActionResult(performed = true)
    }

    private fun planCanvasPlacement(
        snapshot: BlockWorldSnapshot,
        placementTarget: BlockWorldBlock,
    ): List<BlockWorldBlock>? {
        val plannedBlocks = mutableListOf<BlockWorldBlock>()
        val movableBlocksInPlacementOrder =
            snapshot.placedBlocks.sortedWith(
                compareByDescending<BlockWorldBlock> { it.width * it.length }
                    .thenByDescending { it.length }
                    .thenByDescending { it.width }
                    .thenBy { it.recordId },
            )
        for (block in movableBlocksInPlacementOrder) {
            val position = findFreePosition(snapshot.copy(placedBlocks = plannedBlocks), block)
            if (position == null) return null
            plannedBlocks.add(block.copy(posX = position.x, posY = position.y))
        }
        if (findFreePosition(snapshot.copy(placedBlocks = plannedBlocks), placementTarget) == null) return null
        val plannedByRecordId = plannedBlocks.associateBy { it.recordId }
        return snapshot.placedBlocks.map { block -> plannedByRecordId.getValue(block.recordId) }
    }

    private fun findBestPlacement(
        snapshot: BlockWorldSnapshot,
        blocks: Collection<BlockWorldBlock>,
    ): Pair<BlockWorldBlock, BlockWorldPosition>? =
        blocks
            .mapNotNull { block -> findFreePosition(snapshot, block)?.let { block to it } }
            .minWithOrNull(
                compareBy<Pair<BlockWorldBlock, BlockWorldPosition>> { placementFootprint(snapshot, it.first, it.second) }
                    .thenByDescending { it.first.width * it.first.length }
                    .thenBy { it.second.y }
                    .thenBy { it.second.x }
                    .thenBy { it.first.recordId },
            )

    private fun placementFootprint(
        snapshot: BlockWorldSnapshot,
        candidate: BlockWorldBlock,
        position: BlockWorldPosition,
    ): Int {
        val occupiedRight =
            snapshot.placedBlocks.maxOfOrNull { block ->
                (block.posX ?: 0) + block.width
            } ?: 0
        val occupiedBottom =
            snapshot.placedBlocks.maxOfOrNull { block ->
                (block.posY ?: 0) + block.length
            } ?: 0
        return maxOf(occupiedRight, position.x + candidate.width) *
            maxOf(occupiedBottom, position.y + candidate.length)
    }

    private fun performMerge(snapshot: BlockWorldSnapshot): BlockWorldActionResult {
        val mergePair = findSafeMergePair(snapshot)
        if (mergePair == null) {
            return performPlace(snapshot, forMerge = true, forChapter = true)
        }

        val mainBlock = mergePair.first
        val mergedBlock = mergePair.second
        val position =
            BlockWorldPosition(
                mergedBlock.posX ?: return BlockWorldActionResult(false),
                mergedBlock.posY ?: return BlockWorldActionResult(false),
            )
        val mergeResponse =
            callAction("合成贴纸") {
                AntMemberRpcCall.mergeBillBlockWorldBlocks(
                    mainBlock.recordId,
                    listOf(mergedBlock.recordId),
                    position.x,
                    position.y,
                )
            }
        if (mergeResponse == null) {
            return BlockWorldActionResult(performed = true)
        }
        createdBlockIds.remove(mergedBlock.recordId)
        syncedCreatedBlockIds.remove(mergedBlock.recordId)
        Log.member("账单拼贴世界🧩合成贴纸#${mainBlock.configId}")
        return BlockWorldActionResult(performed = true)
    }

    private fun performReclaim(snapshot: BlockWorldSnapshot): BlockWorldActionResult {
        val block = snapshot.placedBlocks.firstOrNull { it.recordId in createdBlockIds && isValidBlock(it) }
            ?: snapshot.placedBlocks.firstOrNull { isValidBlock(it) }
        if (block == null) {
            Log.member("账单拼贴世界⏭️当前没有可回收贴纸")
            return BlockWorldActionResult(performed = false)
        }
        callAction("回收贴纸") {
            AntMemberRpcCall.reclaimBillBlockWorldBlock(block.recordId)
        } ?: return BlockWorldActionResult(performed = true)
        createdBlockIds.remove(block.recordId)
        syncedCreatedBlockIds.remove(block.recordId)
        reclaimedBlockIds.add(block.recordId)
        Log.member("账单拼贴世界🧩回收贴纸#${block.configId}")
        return BlockWorldActionResult(performed = true)
    }

    private fun advanceChapter(chapterId: String): Boolean {
        val response =
            callAction("推进章节") {
                AntMemberRpcCall.advanceBillBlockWorldChapter(chapterId)
            } ?: return false
        if (response.optBoolean("rewardPending", false)) {
            Log.member("账单拼贴世界⏭️章节奖励仍待处理，已回查确认状态")
        } else {
            Log.member("账单拼贴世界🏁推进章节成功")
        }
        return true
    }

    private fun callAction(
        actionName: String,
        request: () -> String,
    ): JSONObject? {
        val response =
            runCatching { JSONObject(request()) }.getOrElse {
                Log.printStackTrace("AntMemberBillBlockWorld", "$actionName 响应解析失败:", it)
                return null
            }
        if (!isSuccess(response)) {
            val message = response.optString("message").ifBlank { response.optString("resultDesc") }
            Log.error("AntMemberBillBlockWorld", "$actionName 失败#$message raw=$response")
            return null
        }
        return response
    }

    private fun findSafeMergePair(snapshot: BlockWorldSnapshot): Pair<BlockWorldBlock, BlockWorldBlock>? {
        val placedBlocks =
            snapshot.placedBlocks.filter { block ->
                isValidBlock(block) &&
                    block.posX != null &&
                    block.posY != null
            }
        for (index in placedBlocks.indices) {
            val mainBlock = placedBlocks[index]
            for (nextIndex in index + 1 until placedBlocks.size) {
                val mergedBlock = placedBlocks[nextIndex]
                if (canMerge(mainBlock, mergedBlock)) {
                    return mainBlock to mergedBlock
                }
            }
        }
        return null
    }

    private fun findPendingMergeBlock(snapshot: BlockWorldSnapshot): BlockWorldBlock? {
        val placedBlocks = snapshot.placedBlocks.filter(::isValidBlock)
        return snapshot.pendingBlocks.firstOrNull { candidate ->
            isValidBlock(candidate) &&
                (
                    placedBlocks.any { canMerge(it, candidate) } ||
                        snapshot.pendingBlocks.any { other ->
                            other.recordId != candidate.recordId && canMerge(other, candidate)
                        }
                )
        }
    }

    private fun canMerge(
        first: BlockWorldBlock,
        second: BlockWorldBlock,
    ): Boolean =
        first.configId.isNotBlank() &&
            first.configId == second.configId &&
            first.level > 0 &&
            first.level == second.level

    private fun findFreePosition(
        snapshot: BlockWorldSnapshot,
        candidate: BlockWorldBlock,
        excludeCurrentPosition: Boolean = false,
    ): BlockWorldPosition? {
        if (!isValidBlock(candidate)) {
            return null
        }
        val maxX = snapshot.canvas.width - candidate.width
        val maxY = snapshot.canvas.length - candidate.length
        if (maxX < 0 || maxY < 0) {
            return null
        }

        val occupiedCells = mutableSetOf<Pair<Int, Int>>()
        var occupiedRight = 0
        var occupiedBottom = 0
        snapshot.placedBlocks.forEach { block ->
            val posX = block.posX ?: return@forEach
            val posY = block.posY ?: return@forEach
            if (!isValidBlock(block)) {
                return@forEach
            }
            occupiedRight = maxOf(occupiedRight, posX + block.width)
            occupiedBottom = maxOf(occupiedBottom, posY + block.length)
            for (x in posX until posX + block.width) {
                for (y in posY until posY + block.length) {
                    occupiedCells.add(x to y)
                }
            }
        }

        var bestPosition: BlockWorldPosition? = null
        var bestArea = Int.MAX_VALUE
        var bestPerimeter = Int.MAX_VALUE
        var bestAdjacency = Int.MIN_VALUE
        for (x in 0..maxX) {
            for (y in 0..maxY) {
                if (excludeCurrentPosition && x == candidate.posX && y == candidate.posY) continue
                val position = BlockWorldPosition(x, y)
                if (overlapsOccupiedCells(position, candidate, occupiedCells)) {
                    continue
                }
                val right = maxOf(occupiedRight, x + candidate.width)
                val bottom = maxOf(occupiedBottom, y + candidate.length)
                val area = right * bottom
                val perimeter = right + bottom
                val adjacency = countAdjacentEdges(position, candidate, occupiedCells)
                if (area < bestArea ||
                    (area == bestArea && perimeter < bestPerimeter) ||
                    (area == bestArea && perimeter == bestPerimeter && adjacency > bestAdjacency) ||
                    (
                        area == bestArea && perimeter == bestPerimeter && adjacency == bestAdjacency &&
                            (bestPosition == null || y < bestPosition.y || (y == bestPosition.y && x < bestPosition.x))
                    )
                ) {
                    bestPosition = position
                    bestArea = area
                    bestPerimeter = perimeter
                    bestAdjacency = adjacency
                }
            }
        }
        return bestPosition
    }

    private fun overlapsOccupiedCells(
        position: BlockWorldPosition,
        candidate: BlockWorldBlock,
        occupiedCells: Set<Pair<Int, Int>>,
    ): Boolean {
        for (x in position.x until position.x + candidate.width) {
            for (y in position.y until position.y + candidate.length) {
                if (x to y in occupiedCells) {
                    return true
                }
            }
        }
        return false
    }

    private fun countAdjacentEdges(
        position: BlockWorldPosition,
        candidate: BlockWorldBlock,
        occupiedCells: Set<Pair<Int, Int>>,
    ): Int {
        var adjacency = 0
        for (x in position.x until position.x + candidate.width) {
            for (y in position.y until position.y + candidate.length) {
                adjacency +=
                    listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)
                        .count { it in occupiedCells }
            }
        }
        return adjacency
    }

    private fun isValidBlock(block: BlockWorldBlock): Boolean = block.width > 0 && block.length > 0
}

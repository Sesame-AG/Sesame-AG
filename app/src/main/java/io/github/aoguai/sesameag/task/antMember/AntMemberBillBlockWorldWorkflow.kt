package io.github.aoguai.sesameag.task.antMember

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.util.Log
import org.json.JSONArray
import org.json.JSONObject

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
    val dailyProductAmt: Int?,
    val coinBalance: Int?,
) {
    fun currentChapter(): BlockWorldChapter? = chapters.firstOrNull { it.id == canvas.chapterId }
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
    private val reclaimedBlocks = linkedMapOf<String, BlockWorldBlock>()

    fun run() {
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTMEMBER_BILL_BLOCK_WORLD_DONE)) {
            Log.member("账单拼贴世界今日已处理，跳过重复查询")
            return
        }
        var snapshot = queryHome() ?: return
        while (true) {
            var dailyProductResponseConfirmed = false
            val pendingDailyProductAmt = snapshot.dailyProductAmt
            val chapter = snapshot.currentChapter()
            if (chapter == null) {
                Log.member("账单拼贴世界⏭️未找到当前章节，停止处理")
                return
            }

            // 章节奖励和画布资源是独立状态：已领奖章节仍可能有待领取贴纸或可合成方块。
            val actionName =
                when {
                    pendingDailyProductAmt != null && pendingDailyProductAmt > 0 -> {
                        val result = collectDailyProductCoin()
                        if (!result.performed) return
                        dailyProductResponseConfirmed = result.responseConfirmedProgress
                        "领取每日贴贴币"
                    }

                    snapshot.pendingBlocks.isNotEmpty() -> {
                        if (!performPlace(snapshot).performed) return
                        "放置待领取贴纸"
                    }

                    createdBlockIds.any { it !in syncedCreatedBlockIds } -> {
                        if (!performCanvasSync(snapshot).performed) return
                        "同步完整画布"
                    }

                    findSafeMergePair(snapshot) != null -> {
                        if (!performMerge(snapshot).performed) return
                        "合成贴纸"
                    }

                    snapshot.dailyProductAmt == null -> {
                        Log.member("账单拼贴世界⏭️每日贴贴币状态未知，当前不确认画布资源已收敛")
                        return
                    }

                    isRewarded(chapter) -> {
                        if (snapshot.dailyProductAmt != 0) {
                            Log.member("账单拼贴世界⏭️每日贴贴币数量异常，当前不写入今日完成标识")
                            return
                        }
                        Status.setFlagToday(StatusFlags.FLAG_ANTMEMBER_BILL_BLOCK_WORLD_DONE)
                        Log.member("账单拼贴世界✅当前章节已领奖且画布资源已收敛")
                        return
                    }

                    isCompleted(chapter) -> {
                        if (!advanceChapter(chapter.id)) return
                        "推进章节"
                    }

                    else -> {
                        val action = performChapterAction(snapshot, chapter)
                        if (!action.performed) return
                        chapter.targetType
                    }
                }
            val refreshed = queryHome() ?: return
            if (actionName == "领取每日贴贴币") {
                val pendingCleared = refreshed.dailyProductAmt == 0
                val balanceIncreased =
                    snapshot.coinBalance?.let { before ->
                        refreshed.coinBalance?.let { after -> after > before }
                    } == true
                if (!pendingCleared && !balanceIncreased && !dailyProductResponseConfirmed) {
                    Log.member("账单拼贴世界⏭️每日贴贴币领取后未获得可确认的数量或余额推进，停止当前链路")
                    return
                }
            }
            if (snapshotStateKey(refreshed) == snapshotStateKey(snapshot)) {
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
        if (canvas.chapterId.isBlank() || canvas.seasonId.isBlank() || canvas.width <= 0 || canvas.length <= 0) {
            Log.member("账单拼贴世界❌画布状态不完整，停止当前链路")
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
        return BlockWorldSnapshot(
            canvas = canvas,
            chapters = parseChapters(data.optJSONArray("chapterTasks")),
            pendingBlocks = parseBlocks(data.optJSONArray("pendingBlocks"), includePosition = false),
            placedBlocks = parseBlocks(data.optJSONArray("placedBlocks"), includePosition = true),
            dailyProductAmt = dailyProductAmt,
            coinBalance = coinBalance,
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
                        posX = if (includePosition && block.has("posX")) block.optInt("posX") else null,
                        posY = if (includePosition && block.has("posY")) block.optInt("posY") else null,
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
                performPlace(snapshot)
            }

            "MOVE_BLOCK" -> {
                performCanvasSync(snapshot)
            }

            "MERGE_COUNT" -> {
                performMerge(snapshot)
            }

            "RECLAIM_BLOCK" -> {
                performReclaim(snapshot)
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

    private fun performPlace(snapshot: BlockWorldSnapshot): BlockWorldActionResult {
        val pendingBlock = findBestPlacement(snapshot, snapshot.pendingBlocks)
        if (pendingBlock != null) {
            return collectPendingBlock(pendingBlock.first, pendingBlock.second)
        }

        val reclaimedBlock = findBestPlacement(snapshot, reclaimedBlocks.values)
        if (reclaimedBlock == null) {
            Log.member("账单拼贴世界⏭️没有可安全放置的贴纸块，停止当前链路")
            return BlockWorldActionResult(performed = false)
        }
        val (block, position) = reclaimedBlock
        val response =
            callAction("重新放置贴纸") {
                AntMemberRpcCall.placeBillBlockWorldBlock(block.recordId, position.x, position.y)
            } ?: return BlockWorldActionResult(performed = false)
        if (response.optString("status") != "PLACED") {
            Log.member("账单拼贴世界❌重新放置贴纸未确认PLACED:$response")
            return BlockWorldActionResult(performed = false)
        }
        reclaimedBlocks.remove(block.recordId)
        createdBlockIds.add(block.recordId)
        Log.member("账单拼贴世界🧩重新放置贴纸")
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

    private fun collectPendingBlock(
        block: BlockWorldBlock,
        position: BlockWorldPosition,
    ): BlockWorldActionResult {
        val response =
            callAction("放置待领取贴纸") {
                AntMemberRpcCall.collectBillBlockWorldBlock(block.recordId, position.x, position.y)
            } ?: return BlockWorldActionResult(performed = false)
        if (response.optString("status") != "PLACED") {
            Log.member("账单拼贴世界❌待领取贴纸未确认PLACED:$response")
            return BlockWorldActionResult(performed = false)
        }
        createdBlockIds.add(block.recordId)
        Log.member("账单拼贴世界🧩放置贴纸#${block.configId}")
        return BlockWorldActionResult(performed = true)
    }

    private fun performCanvasSync(snapshot: BlockWorldSnapshot): BlockWorldActionResult {
        if (snapshot.placedBlocks.any { it.posX == null || it.posY == null || !isValidBlock(it) }) {
            Log.member("账单拼贴世界⏭️画布缺少可同步的完整贴纸结构，停止当前链路")
            return BlockWorldActionResult(performed = false)
        }
        val plannedBlocks = planCompactCreatedBlocks(snapshot) ?: return BlockWorldActionResult(performed = false)
        // collectBlock 成功后也提交一次服务端要求的全量布局；不能因本地位置恰好相同而跳过闭环。
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
        } ?: return BlockWorldActionResult(performed = false)
        syncedCreatedBlockIds.addAll(createdBlockIds)
        Log.member("账单拼贴世界🧩已同步完整贴纸画布")
        return BlockWorldActionResult(performed = true)
    }

    private fun planCompactCreatedBlocks(snapshot: BlockWorldSnapshot): List<BlockWorldBlock>? {
        val movableBlocks = snapshot.placedBlocks.filter { it.recordId in createdBlockIds }
        if (movableBlocks.isEmpty()) {
            Log.member("账单拼贴世界⏭️不移动用户原有贴纸，当前没有本轮可整理贴纸")
            return null
        }
        val fixedBlocks = snapshot.placedBlocks.filterNot { it.recordId in createdBlockIds }.toMutableList()
        val plannedBlocks = fixedBlocks.toMutableList()
        val movableBlocksInPlacementOrder =
            movableBlocks.sortedWith(
                compareByDescending<BlockWorldBlock> { it.width * it.length }
                    .thenByDescending { it.length }
                    .thenByDescending { it.width }
                    .thenBy { it.recordId },
            )
        for (block in movableBlocksInPlacementOrder) {
            val position = findFreePosition(snapshot.copy(placedBlocks = plannedBlocks), block)
            if (position == null) {
                Log.member("账单拼贴世界⏭️无法为本轮贴纸生成安全布局，停止当前链路")
                return null
            }
            plannedBlocks.add(block.copy(posX = position.x, posY = position.y))
        }
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
            val pendingBlock = findPendingMergeBlock(snapshot)
            if (pendingBlock == null) {
                Log.member("账单拼贴世界⏭️没有本轮可安全合成的同类贴纸，停止当前链路")
                return BlockWorldActionResult(performed = false)
            }
            val position = findFreePosition(snapshot, pendingBlock)
            if (position == null) {
                Log.member("账单拼贴世界⏭️没有空间放置合成所需贴纸，停止当前链路")
                return BlockWorldActionResult(performed = false)
            }
            return collectPendingBlock(pendingBlock, position)
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
            queryHome()
            Log.member("账单拼贴世界⏭️合成未获服务端确认，已回查画布后停止当前链路")
            return BlockWorldActionResult(performed = false)
        }
        createdBlockIds.remove(mergedBlock.recordId)
        Log.member("账单拼贴世界🧩合成贴纸#${mainBlock.configId}")
        return BlockWorldActionResult(performed = true)
    }

    private fun performReclaim(snapshot: BlockWorldSnapshot): BlockWorldActionResult {
        val block = snapshot.placedBlocks.firstOrNull { it.recordId in createdBlockIds }
        if (block == null) {
            Log.member("账单拼贴世界⏭️只保护用户原有布局，当前没有本轮可回收贴纸")
            return BlockWorldActionResult(performed = false)
        }
        callAction("回收贴纸") {
            AntMemberRpcCall.reclaimBillBlockWorldBlock(block.recordId)
        } ?: return BlockWorldActionResult(performed = false)
        createdBlockIds.remove(block.recordId)
        reclaimedBlocks[block.recordId] = block
        Log.member("账单拼贴世界🧩回收本轮放置贴纸#${block.configId}")
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
                Log.member("账单拼贴世界❌$actionName 响应解析失败:${it.message}")
                return null
            }
        if (!isSuccess(response)) {
            val message = response.optString("message").ifBlank { response.optString("resultDesc") }
            Log.member("账单拼贴世界❌$actionName 失败#${message.ifBlank { response.toString() }}")
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
                findFreePosition(snapshot, candidate) != null &&
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
                val position = BlockWorldPosition(x, y)
                if (overlapsOccupiedCells(position, candidate, occupiedCells)) {
                    continue
                }
                // 不重排用户已有画布；对本轮新块优先压缩外接矩形，再尽量贴合已有块。
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

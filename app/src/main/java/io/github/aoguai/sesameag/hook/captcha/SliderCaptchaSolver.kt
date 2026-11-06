package io.github.aoguai.sesameag.hook.captcha

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 滑块验证码求解器（纯计算，不依赖任何第三方图像库）。
 *
 * 两个能力：
 * 1. [findGapOffset]：从背景图中找缺口位置（列边缘能量法）；
 * 2. [buildTrack]：生成带加减速、过冲回拉和微抖动的人类化拖动轨迹。
 *
 * 注意：识别准确率强依赖验证码样式，不同版本的图（带阴影、带干扰线、双图叠加）需要调参；
 * 调用方必须对结果做校验（拖动失败要立即放弃，不要连续重试，否则会加重风控）。
 */
object SliderCaptchaSolver {
    private const val MAX_ANALYSIS_WIDTH = 320
    private const val MAX_MATCH_WIDTH = 200
    private const val MIN_OUTLINE_SCORE = 6f
    private const val MIN_GAP_OFFSET_RATIO = 0.08f
    private const val MAX_GAP_OFFSET_RATIO = 0.92f

    data class Solution(
        /** 缺口相对图片左边缘的像素偏移（已按原图宽度换算）。 */
        val offsetPx: Int,
        /** 轨迹点，元素为 [x, y]，坐标为起始点相对位移。 */
        val track: List<FloatArray>,
        val durationMs: Long,
    )

    /** 缺口匹配结果：偏移 + 置信度（边缘亮度 − 内部亮度）。 */
    data class GapMatch(
        val offsetPx: Int,
        val score: Float,
        val refined: Boolean,
    )

    /**
     * 用拼图块的 alpha 轮廓做模板，定位缺口。
     *
     * 实测（jshield / netflow_captcha）：缺口是"内部暗、边缘亮"的空洞，
     * 单纯找最强竖直边缘会被画面内容带偏（x=147 vs 真实 205）。
     *
     * 采用**粗到精**两级搜索：
     * 1. 缩到 200 宽快速扫全图（约 10ms）；
     * 2. 在粗结果附近用**全分辨率**再精修，消除缩放带来的 ±15px 误差
     *    （实测缩放到 200 宽时同一张图会给出 180 而真值是 164）。
     */
    fun findGapByOutline(
        background: Bitmap,
        piece: Bitmap,
    ): GapMatch? {
        if (background.width < 80 || background.height < 40) return null
        if (piece.width < 12 || piece.height < 12) return null

        val scale = min(1f, MAX_MATCH_WIDTH.toFloat() / background.width)
        val bgW = max(80, (background.width * scale).roundToInt())
        val bgH = max(40, (background.height * scale).roundToInt())
        val pw = max(8, (piece.width * scale).roundToInt())
        val ph = max(8, (piece.height * scale).roundToInt())
        if (pw >= bgW || ph >= bgH) return null

        val coarse = search(
            background = background,
            piece = piece,
            workWidth = bgW,
            workHeight = bgH,
            pieceWidth = pw,
            pieceHeight = ph,
        ) ?: return null

        // 粗结果映射回原图坐标，再做全分辨率局部精修
        val coarseOriginalX = (background.width * (coarse.first.toFloat() / bgW)).roundToInt()
        val coarseOriginalY = (background.height * (coarse.second.toFloat() / bgH)).roundToInt()
        val fine = refine(
            background = background,
            piece = piece,
            centerX = coarseOriginalX,
            centerY = coarseOriginalY,
        )
        return if (fine != null) {
            GapMatch(fine.first, fine.second, refined = true)
        } else {
            GapMatch(coarseOriginalX, coarse.third, refined = false)
        }
    }

    /** 全分辨率局部精修：在粗结果 ±8px 内逐像素搜索，取分数更高者。 */
    private fun refine(
        background: Bitmap,
        piece: Bitmap,
        centerX: Int,
        centerY: Int,
    ): Triple<Int, Float, Int>? {
        val gray = grayOf(background, background.width, background.height) ?: return null
        val mask = maskOf(piece, piece.width, piece.height) ?: return null
        val boundary = boundaryOf(mask, piece.width, piece.height) ?: return null
        var bestScore = 0f
        var bestX = -1
        var bestY = -1
        for (dy in -6..6) {
            for (dx in -8..8) {
                val x = centerX + dx
                val y = centerY + dy
                if (x < 0 || y < 0) continue
                if (x + piece.width > background.width || y + piece.height > background.height) continue
                val score = scoreAt(gray, background.width, mask, boundary, piece.width, piece.height, x, y)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
            }
        }
        if (bestX < 0) return null
        return Triple(bestX, bestScore, bestY)
    }

    /** 返回 (bestX, bestY, bestScore)。 */
    private fun search(
        background: Bitmap,
        piece: Bitmap,
        workWidth: Int,
        workHeight: Int,
        pieceWidth: Int,
        pieceHeight: Int,
    ): Triple<Int, Int, Float>? {
        val bgScaled = Bitmap.createScaledBitmap(background, workWidth, workHeight, false)
        val pieceScaled = Bitmap.createScaledBitmap(piece, pieceWidth, pieceHeight, false)
        val gray = grayOf(bgScaled, workWidth, workHeight)
        val mask = maskOf(pieceScaled, pieceWidth, pieceHeight)
        if (bgScaled !== background) runCatching { bgScaled.recycle() }
        if (pieceScaled !== piece) runCatching { pieceScaled.recycle() }
        if (gray == null || mask == null) return null
        val boundary = boundaryOf(mask, pieceWidth, pieceHeight) ?: return null

        var bestScore = 0f
        var bestX = -1
        var bestY = -1
        for (y in 0..(workHeight - pieceHeight)) {
            for (x in 0..(workWidth - pieceWidth)) {
                val score = scoreAt(gray, workWidth, mask, boundary, pieceWidth, pieceHeight, x, y)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
            }
        }
        if (bestX < 0 || bestScore < MIN_OUTLINE_SCORE) return null
        return Triple(bestX, bestY, bestScore)
    }

    private fun grayOf(
        bitmap: Bitmap,
        width: Int,
        height: Int,
    ): IntArray? {
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return IntArray(pixels.size) { index ->
            val c = pixels[index]
            (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 151 + (c and 0xFF) * 28) shr 8
        }
    }

    private fun maskOf(
        piece: Bitmap,
        width: Int,
        height: Int,
    ): BooleanArray? {
        val pixels = IntArray(width * height)
        piece.getPixels(pixels, 0, width, 0, 0, width, height)
        val mask = BooleanArray(pixels.size) { (pixels[it] ushr 24) > 128 }
        // 没有 alpha 通道（全不透明）时用整块，仍可匹配
        return if (mask.count { it } < 200) null else mask
    }

    private fun boundaryOf(
        mask: BooleanArray,
        width: Int,
        height: Int,
    ): BooleanArray? {
        val dilated = dilate(mask, width, height, 2)
        val boundary = BooleanArray(mask.size) { dilated[it] && !mask[it] }
        return if (boundary.none { it }) null else boundary
    }

    private fun scoreAt(
        gray: IntArray,
        grayWidth: Int,
        mask: BooleanArray,
        boundary: BooleanArray,
        pieceWidth: Int,
        pieceHeight: Int,
        originX: Int,
        originY: Int,
    ): Float {
        var insideSum = 0
        var insideCount = 0
        var edgeSum = 0
        var edgeCount = 0
        for (py in 0 until pieceHeight) {
            val rowBase = (originY + py) * grayWidth + originX
            val pieceRow = py * pieceWidth
            for (px in 0 until pieceWidth) {
                val index = pieceRow + px
                if (mask[index]) {
                    insideSum += gray[rowBase + px]
                    insideCount++
                } else if (boundary[index]) {
                    edgeSum += gray[rowBase + px]
                    edgeCount++
                }
            }
        }
        if (insideCount == 0 || edgeCount == 0) return 0f
        return (edgeSum.toFloat() / edgeCount) - (insideSum.toFloat() / insideCount)
    }

    private fun dilate(
        mask: BooleanArray,
        width: Int,
        height: Int,
        iterations: Int,
    ): BooleanArray {
        var current = mask
        repeat(iterations) {
            val next = BooleanArray(current.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    if (!current[index]) continue
                    next[index] = true
                    if (x > 0) next[index - 1] = true
                    if (x < width - 1) next[index + 1] = true
                    if (y > 0) next[index - width] = true
                    if (y < height - 1) next[index + width] = true
                }
            }
            current = next
        }
        return current
    }

    /**
     * 在背景图中定位缺口。
     *
     * 做法：按列统计竖直方向的灰度梯度能量 → 3 列平滑 → 用"约一个拼图宽度的滑动窗口"求能量峰值。
     * 这是拿不到拼图块时的降级方案，准确率明显低于 [findGapByOutline]。
     *
     * @return 原图坐标系下的偏移；识别失败返回 null。
     */
    fun findGapOffset(background: Bitmap): Int? {
        if (background.width < 80 || background.height < 40) return null
        val scale = min(1f, MAX_ANALYSIS_WIDTH.toFloat() / background.width)
        val width = max(40, (background.width * scale).roundToInt())
        val height = max(20, (background.height * scale).roundToInt())
        val scaled = runCatching {
            if (width == background.width && height == background.height) {
                background
            } else {
                Bitmap.createScaledBitmap(background, width, height, false)
            }
        }.getOrNull() ?: return null

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== background) runCatching { scaled.recycle() }

        val gray = IntArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            gray[index] = (r * 77 + g * 151 + b * 28) shr 8
        }

        val rawEnergy = FloatArray(width)
        for (x in 1 until width) {
            var sum = 0
            for (y in 0 until height) {
                val index = y * width + x
                sum += abs(gray[index] - gray[index - 1])
            }
            rawEnergy[x] = sum.toFloat() / height
        }
        // 3 列平滑，压掉单像素噪声
        val energy = FloatArray(width)
        for (x in 0 until width) {
            var acc = 0f
            var count = 0
            for (offset in -1..1) {
                val i = x + offset
                if (i in 0 until width) {
                    acc += rawEnergy[i]
                    count++
                }
            }
            energy[x] = if (count > 0) acc / count else 0f
        }

        val minX = max(2, (width * MIN_GAP_OFFSET_RATIO).roundToInt())
        val maxX = min(width - 2, (width * MAX_GAP_OFFSET_RATIO).roundToInt())
        if (maxX - minX < 12) return null

        val window = max(10, width / 8)
        val meanEnergy = energy.drop(1).average().toFloat()
        if (meanEnergy <= 0.5f) return null

        var bestCenter = -1
        var bestScore = 0f
        for (center in (minX + window)..(maxX - window)) {
            var sum = 0f
            for (x in (center - window)..(center + window)) {
                sum += energy[x]
            }
            val avg = sum / (2 * window + 1)
            if (avg > bestScore) {
                bestScore = avg
                bestCenter = center
            }
        }
        if (bestCenter < 0) return null
        // 显著性门槛：窗口均值得明显高于整图均值，否则说明没有缺口特征（多半是选错了图）
        if (bestScore < meanEnergy * 1.6f) return null

        val gapLeft = (bestCenter - window).coerceIn(2, width - 2)
        val ratio = gapLeft.toFloat() / width
        val original = (background.width * ratio).roundToInt()
        return original.coerceIn(4, background.width - 4)
    }

    /**
     * 一次拖动的完整"表演计划"：轨迹点 + 节奏参数，全部随机生成。
     *
     * 目的：每次滑块验证的轨迹、时长、停顿位置、抖动幅度都不一样，
     * 避免同一账号反复出现完全相同的运动学特征。
     */
    data class DragPlan(
        val points: List<FloatArray>,
        /** 起手按住时长（毫秒）。 */
        val startHoldMs: Long,
        /** 松手前终点停留时长（毫秒）。 */
        val endHoldMs: Long,
        /** 每帧真实间隔区间（毫秒）。 */
        val frameMsMin: Long,
        val frameMsMax: Long,
        /** 停顿帧的真实间隔区间（毫秒）。 */
        val pauseMsMin: Long,
        val pauseMsMax: Long,
        /** 缓动曲线名，仅用于日志。 */
        val profile: String,
        /** 总时长（毫秒），仅用于日志。 */
        val durationMs: Long,
    )

    /**
     * 生成随机化的人类拖动计划。
     *
     * 每次调用都会随机化：缓动曲线、总时长、单步位移上限、过冲幅度、回拉起点、
     * 停顿次数/位置/时长、纵向抖动幅度与频率、中途倒退次数。
     * 只有**终点**保持精确（必须严格落在 [distancePx]），否则验证必失败。
     */
    fun buildPlan(
        distancePx: Int,
        startY: Float = 0f,
        random: Random = Random.Default,
    ): DragPlan {
        if (distancePx <= 0) {
            return DragPlan(
                emptyList(), 0L, 0L, 14L, 19L, 45L, 90L, "empty", 0L,
            )
        }
        // 时长：按距离给一个基础值，再叠 ±45% 的随机波动
        val base = 900L + distancePx * 3L
        val durationMs = (base * (0.55 + random.nextDouble() * 0.9)).toLong().coerceIn(1_100L, 3_800L)
        val steps = max(28, (durationMs / 18L).toInt())

        // 缓动曲线四选一：真人不总是同一种加减速
        val profileIndex = random.nextInt(4)
        val profileName = when (profileIndex) {
            0 -> "easeOutQuint"
            1 -> "easeOutCubic"
            2 -> "easeOutQuad"
            else -> "easeInOutSine"
        }
        fun ease(p: Float): Float = when (profileIndex) {
            0 -> 1f - (1f - p).pow5()
            1 -> 1f - (1f - p) * (1f - p) * (1f - p)
            2 -> 1f - (1f - p) * (1f - p)
            else -> -0.5f * (kotlin.math.cos(Math.PI.toFloat() * p) - 1f)
        }

        val overshoot = (2 + random.nextInt(6)).toFloat().coerceAtMost(distancePx * 0.14f)
        val peak = distancePx.toFloat() + overshoot
        val maxStep = random.nextFloat() * 6f + 3f          // 3~9px
        val pullBackAt = 0.80f + random.nextFloat() * 0.12f // 回拉起点 0.80~0.92

        // 停顿：1~4 次，位置随机且互不重叠
        val pauseCount = 1 + random.nextInt(4)
        val pauseAt = generateSequence { 0.18f + random.nextFloat() * 0.66f }
            .distinct()
            .take(pauseCount)
            .sorted()
            .toList()

        // 50% 概率在中后段出现一次 1~3px 的"手滑倒退"
        val backtrackAt = if (random.nextBoolean()) 0.62f + random.nextFloat() * 0.18f else -1f
        val backtrackPx = 1f + random.nextFloat() * 2f

        // 纵向抖动：低频正弦 + 高频噪声，幅度随机
        val wobbleAmp = 0.6f + random.nextFloat() * 1.6f
        val wobbleFreq = 4f + random.nextFloat() * 9f
        val wobblePhase = random.nextFloat() * 6.28f

        val points = ArrayList<FloatArray>(steps + 16)
        var lastX = 0f
        var pauseBudget = pauseAt.toMutableList()
        var backtrackDone = false

        for (step in 0..steps) {
            val progress = step.toFloat() / steps
            var x = peak * ease(progress)
            if (progress > pullBackAt) {
                val tail = (progress - pullBackAt) / (1f - pullBackAt)
                x -= overshoot * tail
            }
            // 中途手滑倒退
            if (!backtrackDone && backtrackAt > 0 && progress >= backtrackAt) {
                x -= backtrackPx
                backtrackDone = true
            }
            x = x.coerceIn(lastX - backtrackPx, distancePx.toFloat() + overshoot)
            x = x.coerceAtMost(lastX + maxStep)
            lastX = x

            val wobble = kotlin.math.sin(progress * wobbleFreq + wobblePhase) * wobbleAmp +
                (random.nextFloat() * 1.2f - 0.6f)
            points.add(floatArrayOf(x, startY + wobble))

            // 停顿：同一坐标重复若干帧
            if (pauseBudget.isNotEmpty() && progress >= pauseBudget.first()) {
                pauseBudget.removeAt(0)
                repeat(1 + random.nextInt(5)) {
                    points.add(floatArrayOf(x, startY + wobble))
                }
            }
        }
        // 终点必须精确对齐缺口
        points[points.size - 1] = floatArrayOf(distancePx.toFloat(), startY)

        return DragPlan(
            points = points,
            startHoldMs = 90L + random.nextLong(0L, 170L),
            endHoldMs = 90L + random.nextLong(0L, 180L),
            frameMsMin = 12L,
            frameMsMax = 16L + random.nextLong(0L, 8L),
            pauseMsMin = 35L + random.nextLong(0L, 40L),
            pauseMsMax = 90L + random.nextLong(0L, 90L),
            profile = profileName,
            durationMs = durationMs,
        )
    }

    private fun Float.pow5(): Float {
        val a = this * this
        return a * a * this
    }

    /** 轨迹统计，写进日志便于复盘（不影响识别）。 */
    fun describePlan(plan: DragPlan): String {
        val points = plan.points
        if (points.isEmpty()) return "empty"
        val total = points.last()[0]
        var maxStep = 0f
        for (index in 1 until points.size) {
            maxStep = max(maxStep, points[index][0] - points[index - 1][0])
        }
        return "points=${points.size} distance=${total.roundToInt()} maxStep=${maxStep.roundToInt()} " +
            "profile=${plan.profile} targetMs=${plan.durationMs} " +
            "frame=${plan.frameMsMin}~${plan.frameMsMax}ms hold=${plan.startHoldMs}/${plan.endHoldMs}ms"
    }

    @Suppress("unused")
    private fun easeInOut(progress: Float): Float = exp(-6f * (1f - progress))
}

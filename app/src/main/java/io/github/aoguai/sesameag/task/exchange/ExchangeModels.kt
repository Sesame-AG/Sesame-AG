package io.github.aoguai.sesameag.task.exchange

import com.fasterxml.jackson.core.type.TypeReference
import io.github.aoguai.sesameag.entity.MapperEntity
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import org.json.JSONObject

enum class ExchangeSafety {
    AUTO,
    LOG_ONLY,
    UNAVAILABLE
}

enum class ExchangeEffectNeed {
    FOREST_DOUBLE_CLICK,
    FOREST_PATROL_CHANCE,
    FOREST_SHIELD,
    FOREST_STEALTH,
    FOREST_BUBBLE_BOOST,
    FOREST_ENERGY_RAIN,
    FOREST_ENERGY_BOMB,
    FOREST_ROB_MULTIPLIER,
    FARM_FEED,
    FARM_ACCELERATE_TOOL,
    FARM_BIG_EATER_TOOL,
    FARM_FENCE_TOOL,
    FARM_NEW_EGG_TOOL,
    ORCHARD_FERTILIZER,
    OCEAN_UNIVERSAL_PIECE,
    OCEAN_RANDOM_PIECE,
    DODO_HISTORY_CARD,
    DODO_FRIEND_CARD,
    MEMBER_GOLD_TICKET
}

enum class ExchangeReplenishResult {
    EXCHANGED,
    NOT_SELECTED,
    NOT_AVAILABLE,
    BUSINESS_LIMIT,
    RETRY_LATER,
    UNSUPPORTED
}

data class ExchangeCost(
    val pointText: String = "",
    val cashText: String = ""
)

data class ExchangeLimit(
    val statusText: String = "",
    val stockText: String = "",
    val validText: String = ""
)

data class ExchangeEffectTag(
    val need: ExchangeEffectNeed,
    val targetModule: String,
    val priority: Int = 100,
    val reason: String = "",
    val triggerText: String = ""
)

data class ExchangeDisplayMeta(
    val sourceModule: String = "",
    val effectSummary: String = "",
    val triggerText: String = "",
    val excludeReason: String = ""
)

object ExchangeEffectCatalog {
    const val SOURCE_SPORTS_ENERGY = "运动能量"
    const val SOURCE_FOREST_VITALITY = "森林活力值"
    const val SOURCE_FARM_PARADISE = "庄园乐园币"
    const val SOURCE_MEMBER_POINT = "会员积分"
    const val SOURCE_BEAN_RIGHT = "安心豆"
    const val SOURCE_SESAME_GRAIN = "芝麻粒"

    fun tagsFor(sourceModule: String, itemName: String): List<ExchangeEffectTag> {
        val name = itemName.trim()
        if (name.isEmpty()) {
            return emptyList()
        }
        return when (sourceModule) {
            SOURCE_SPORTS_ENERGY -> sportsTags(name)
            SOURCE_FOREST_VITALITY -> forestVitalityTags(name)
            SOURCE_FARM_PARADISE -> farmParadiseTags(name)
            SOURCE_MEMBER_POINT -> memberPointTags(name)
            SOURCE_BEAN_RIGHT -> beanTags(name)
            else -> emptyList()
        }
    }

    fun displayMeta(
        sourceModule: String,
        itemName: String,
        safety: ExchangeSafety,
        safetyReason: String,
        tags: List<ExchangeEffectTag>
    ): ExchangeDisplayMeta {
        val effectSummary = tags.joinToString("；") { tag ->
            "${sourceModule} -> ${tag.targetModule}: ${tag.reason.ifBlank { tag.need.name }}"
        }
        return ExchangeDisplayMeta(
            sourceModule = sourceModule,
            effectSummary = effectSummary,
            triggerText = tags.map { it.triggerText }.firstOrNull { it.isNotBlank() }.orEmpty(),
            excludeReason = when {
                tags.isNotEmpty() -> ""
                safety != ExchangeSafety.AUTO -> safetyReason
                sourceModule == SOURCE_SESAME_GRAIN -> "当前未发现可安全接入模块业务补货的稳定虚拟道具"
                else -> ""
            }
        )
    }

    fun matchesNeed(item: ExchangeItem, need: ExchangeEffectNeed): Boolean {
        return item.effectTags.any { it.need == need }
    }

    fun priorityFor(item: ExchangeItem, need: ExchangeEffectNeed): Int {
        var priority = Int.MAX_VALUE
        item.effectTags.forEach { tag ->
            if (tag.need == need && tag.priority < priority) {
                priority = tag.priority
            }
        }
        return priority
    }

    private fun tag(
        need: ExchangeEffectNeed,
        targetModule: String,
        reason: String,
        triggerText: String,
        priority: Int = 100
    ) = ExchangeEffectTag(
        need = need,
        targetModule = targetModule,
        priority = priority,
        reason = reason,
        triggerText = triggerText
    )

    private fun sportsTags(name: String): List<ExchangeEffectTag> = when {
        name.contains("森林") && name.contains("双击卡") -> listOf(
            tag(
                ExchangeEffectNeed.FOREST_DOUBLE_CLICK,
                "蚂蚁森林",
                "森林双击卡缺货补兑",
                "森林双击卡开启且背包无可用双击卡时补兑",
                40
            )
        )
        name.contains("保护地") && name.contains("巡护") -> listOf(
            tag(
                ExchangeEffectNeed.FOREST_PATROL_CHANCE,
                "蚂蚁森林",
                "保护地巡护机会补兑",
                "保护地巡护开启且机会不足或步数兑换受限时补兑",
                45
            )
        )
        else -> emptyList()
    }

    private fun forestVitalityTags(name: String): List<ExchangeEffectTag> {
        val tags = mutableListOf<ExchangeEffectTag>()
        if (name.contains("双击卡")) {
            tags.add(tag(ExchangeEffectNeed.FOREST_DOUBLE_CLICK, "蚂蚁森林", "双击卡缺货补兑", "背包无可用双击卡且永动配置允许时补兑", 10))
        }
        if (name.contains("保护罩")) {
            tags.add(tag(ExchangeEffectNeed.FOREST_SHIELD, "蚂蚁森林", "保护罩缺货补兑", "保护罩策略开启且背包无可用保护罩时补兑", 10))
        }
        if (name.contains("隐身")) {
            tags.add(tag(ExchangeEffectNeed.FOREST_STEALTH, "蚂蚁森林", "隐身卡缺货补兑", "隐身卡策略开启且背包无可用隐身卡时补兑", 10))
        }
        if (name.contains("时光加速器")) {
            tags.add(tag(ExchangeEffectNeed.FOREST_BUBBLE_BOOST, "蚂蚁森林", "时光加速器缺货补兑", "存在未来成熟能量球且背包无加速器时补兑", 10))
        }
        if (name.contains("能量雨")) {
            tags.add(tag(ExchangeEffectNeed.FOREST_ENERGY_RAIN, "蚂蚁森林", "能量雨机会缺货补兑", "能量雨开启但当前无可玩机会时补兑", 10))
        }
        if (name.contains("能量炸弹")) {
            tags.add(tag(ExchangeEffectNeed.FOREST_ENERGY_BOMB, "蚂蚁森林", "能量炸弹卡缺货补兑", "炸弹卡策略开启且背包无炸弹卡时补兑", 10))
        }
        if ((name.contains("收能量") || name.contains("收好友")) && name.contains("倍")) {
            tags.add(tag(ExchangeEffectNeed.FOREST_ROB_MULTIPLIER, "蚂蚁森林", "收好友能量倍率卡缺货补兑", "收好友N倍卡策略开启且背包无合适倍率卡时补兑", 10))
        }
        if (name.contains("神奇海洋") && name.contains("万能拼图")) {
            tags.add(tag(ExchangeEffectNeed.OCEAN_UNIVERSAL_PIECE, "神奇海洋", "万能拼图缺货补兑", "存在可合成目标但万能拼图不足时补兑", 30))
        }
        if (name.contains("神奇海洋") && name.contains("随机拼图")) {
            tags.add(tag(ExchangeEffectNeed.OCEAN_RANDOM_PIECE, "神奇海洋", "随机拼图推进", "海洋流程开启且用户已勾选时仅推进碎片进度", 80))
        }
        if (name.contains("神奇物种") && name.contains("历史卡")) {
            tags.add(tag(ExchangeEffectNeed.DODO_HISTORY_CARD, "神奇物种", "抽历史卡机会缺货补兑", "历史卡道具开关开启且背包无可用历史卡时补兑", 30))
        }
        if (name.contains("神奇物种") && name.contains("好友卡")) {
            tags.add(tag(ExchangeEffectNeed.DODO_FRIEND_CARD, "神奇物种", "抽好友卡机会缺货补兑", "好友卡道具开关开启且背包无可用好友卡时补兑", 30))
        }
        return tags
    }

    private fun farmParadiseTags(name: String): List<ExchangeEffectTag> = buildList {
        if (name.contains("饲料")) add(tag(ExchangeEffectNeed.FARM_FEED, "蚂蚁庄园", "饲料不足补兑", "领取普通饲料奖励后仍不足时补兑", 10))
        if (name.contains("加速卡")) add(tag(ExchangeEffectNeed.FARM_ACCELERATE_TOOL, "蚂蚁庄园", "加速卡缺货补兑", "小鸡吃饭中且加速卡开关开启、背包为0时补兑", 10))
        if (name.contains("加饭卡")) add(tag(ExchangeEffectNeed.FARM_BIG_EATER_TOOL, "蚂蚁庄园", "加饭卡缺货补兑", "小鸡吃饭中且加饭卡开关开启、背包为0时补兑", 10))
        if (name.contains("篱笆卡")) add(tag(ExchangeEffectNeed.FARM_FENCE_TOOL, "蚂蚁庄园", "篱笆卡缺货补兑", "篱笆未生效且背包为0时补兑", 10))
        if (name.contains("新蛋卡")) add(tag(ExchangeEffectNeed.FARM_NEW_EGG_TOOL, "蚂蚁庄园", "新蛋卡缺货补兑", "新蛋卡流程开启且背包为0时补兑", 10))
    }

    private fun memberPointTags(name: String): List<ExchangeEffectTag> = buildList {
        if (name.contains("芭芭农场") && name.contains("肥料")) add(tag(ExchangeEffectNeed.ORCHARD_FERTILIZER, "芭芭农场", "肥料不足兜底补兑", "农场施肥链路缺肥料且用户已勾选时补兑", 70))
        if (name.contains("森林") && name.contains("保护罩")) add(tag(ExchangeEffectNeed.FOREST_SHIELD, "蚂蚁森林", "保护罩兜底补兑", "森林活力值未补齐保护罩后兜底补兑", 70))
        if (name.contains("时光加速器")) add(tag(ExchangeEffectNeed.FOREST_BUBBLE_BOOST, "蚂蚁森林", "时光加速器兜底补兑", "森林活力值未补齐加速器后兜底补兑", 70))
        if (name.contains("庄园") && name.contains("饲料")) add(tag(ExchangeEffectNeed.FARM_FEED, "蚂蚁庄园", "庄园饲料兜底补兑", "乐园币未补齐饲料后兜底补兑", 70))
        if (name.contains("加速卡")) add(tag(ExchangeEffectNeed.FARM_ACCELERATE_TOOL, "蚂蚁庄园", "庄园加速卡兜底补兑", "乐园币未补齐加速卡后兜底补兑", 70))
        if (name.contains("篱笆卡")) add(tag(ExchangeEffectNeed.FARM_FENCE_TOOL, "蚂蚁庄园", "庄园篱笆卡兜底补兑", "乐园币未补齐篱笆卡后兜底补兑", 70))
        if (name.contains("神奇海洋") && name.contains("万能拼图")) add(tag(ExchangeEffectNeed.OCEAN_UNIVERSAL_PIECE, "神奇海洋", "万能拼图兜底补兑", "森林活力值未补齐万能拼图后兜底补兑", 70))
        if (name.contains("神奇物种") && name.contains("历史卡")) add(tag(ExchangeEffectNeed.DODO_HISTORY_CARD, "神奇物种", "抽历史卡机会兜底补兑", "森林活力值未补齐历史卡机会后兜底补兑", 70))
    }

    private fun beanTags(name: String): List<ExchangeEffectTag> = when {
        name.contains("黄金票") -> listOf(
            tag(
                ExchangeEffectNeed.MEMBER_GOLD_TICKET,
                "会员黄金票",
                "黄金票余额不足补兑",
                "黄金票提取/兑换开启且余额低于阈值时补兑",
                20
            )
        )
        else -> emptyList()
    }
}

data class ExchangeItem(
    val id: String,
    val name: String,
    val cost: ExchangeCost = ExchangeCost(),
    val limit: ExchangeLimit = ExchangeLimit(),
    val safety: ExchangeSafety = ExchangeSafety.AUTO,
    val safetyReason: String = "",
    val effectTags: List<ExchangeEffectTag> = emptyList(),
    val displayMeta: ExchangeDisplayMeta = ExchangeDisplayMeta()
) {
    fun displayName(): String {
        val parts = listOf(
            cost.pointText,
            cost.cashText,
            limit.stockText,
            limit.validText,
            limit.statusText,
            when (safety) {
                ExchangeSafety.AUTO -> ""
                ExchangeSafety.LOG_ONLY -> "仅提醒${safetyReason.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()}"
                ExchangeSafety.UNAVAILABLE -> "不可兑换${safetyReason.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()}"
            }
        ).filter { it.isNotBlank() }
        return if (parts.isEmpty()) name else "$name[${parts.joinToString(" | ")}]"
    }

    fun toOptionRow(): ExchangeOptionRow = ExchangeOptionRow(this)
}

class ExchangeOptionRow() : MapperEntity() {
    @JvmField
    var sourceModule: String = ""

    @JvmField
    var rawName: String = ""

    @JvmField
    var costText: String = ""

    @JvmField
    var statusText: String = ""

    @JvmField
    var safety: String = ""

    @JvmField
    var safetyReason: String = ""

    @JvmField
    var effectSummary: String = ""

    @JvmField
    var effectTargets: List<String> = emptyList()

    @JvmField
    var triggerText: String = ""

    @JvmField
    var excludeReason: String = ""

    constructor(item: ExchangeItem) : this() {
        id = item.id
        name = item.displayName()
        sourceModule = item.displayMeta.sourceModule
        rawName = item.name
        costText = listOf(item.cost.pointText, item.cost.cashText)
            .filter { it.isNotBlank() }
            .joinToString(" + ")
        statusText = listOf(item.limit.stockText, item.limit.validText, item.limit.statusText)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        safety = item.safety.name
        safetyReason = item.safetyReason
        effectSummary = item.displayMeta.effectSummary
            .ifBlank { item.effectTags.joinToString("；") { tag -> "${tag.targetModule}:${tag.reason.ifBlank { tag.need.name }}" } }
        effectTargets = item.effectTags.map { it.targetModule }.distinct()
        triggerText = item.displayMeta.triggerText
            .ifBlank { item.effectTags.map { it.triggerText }.firstOrNull { it.isNotBlank() }.orEmpty() }
        excludeReason = item.displayMeta.excludeReason
    }

    fun asLogOnlyCacheRow(reason: String): ExchangeOptionRow {
        return ExchangeOptionRow().also { row ->
            row.id = id
            row.name = name
            row.sourceModule = sourceModule
            row.rawName = rawName
            row.costText = costText
            row.statusText = statusText
            row.safety = ExchangeSafety.LOG_ONLY.name
            row.safetyReason = reason
            row.effectSummary = effectSummary
            row.effectTargets = effectTargets
            row.triggerText = triggerText
            row.excludeReason = excludeReason.ifBlank { reason }
        }
    }
}

object ExchangeOptionsCache {
    private const val TAG = "ExchangeOptionsCache"
    private const val FILE_PREFIX = "exchange_options_"
    private const val FILE_SUFFIX = ".json"
    private const val CACHE_REASON = "本地缓存，未经过本次目标应用刷新复核"

    fun save(userId: String?, target: String, rows: List<ExchangeOptionRow>): Boolean {
        val normalizedUserId = userId?.trim().orEmpty()
        if (normalizedUserId.isEmpty()) {
            return false
        }
        return runCatching {
            val file = Files.getTargetFileofUser(normalizedUserId, fileName(target)) ?: return false
            Files.write2File(JsonUtil.formatJson(rows, false), file)
        }.onFailure {
            Log.printStackTrace(TAG, "save err:", it)
        }.getOrDefault(false)
    }

    fun load(userId: String?, target: String): List<ExchangeOptionRow> {
        val normalizedUserId = userId?.trim().orEmpty()
        if (normalizedUserId.isEmpty()) {
            return emptyList()
        }
        return runCatching {
            val file = Files.getTargetFileofUser(normalizedUserId, fileName(target)) ?: return emptyList()
            val body = Files.readFromFile(file)
            if (body.isBlank()) {
                emptyList()
            } else {
                JsonUtil.parseObject(
                    body,
                    object : TypeReference<List<ExchangeOptionRow>>() {}
                ).filter { it.id.isNotBlank() }
            }
        }.onFailure {
            Log.printStackTrace(TAG, "load err:", it)
        }.getOrDefault(emptyList())
    }

    fun loadForSettingsCache(userId: String?, target: String): List<ExchangeOptionRow> {
        return load(userId, target).map { it.asLogOnlyCacheRow(CACHE_REASON) }
    }

    private fun fileName(target: String): String {
        val safeTarget = target
            .trim()
            .map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_' }
            .joinToString("")
            .ifBlank { "unknown" }
        return "$FILE_PREFIX$safeTarget$FILE_SUFFIX"
    }
}

object ExchangeSafetyRules {
    private val orderKeywords = listOf(
        "收货", "发货", "下单", "实付", "支付页", "支付链路", "支付金额", "支付成功", "支付时", "邮寄", "快递", "订单",
        "付邮", "邮费", "包邮", "商品详情", "商品", "实物", "优惠券", "红包", "话费", "券", "小程序",
        "goods", "goodsDetail", "platformPhysicalItem", "MINIAPP_ITEMBASE", "UNION_PRICE", "DEDUCT_CASH",
        "COUPON_PURCHASE", "needSendCoupon", "recruitPlatform"
    )

    fun hasPositiveCash(vararg rawValues: String?): Boolean {
        return rawValues.any { value ->
            val normalized = value?.trim().orEmpty()
            normalized.isNotEmpty() && normalized.toBigDecimalOrNull()?.signum() == 1
        }
    }

    fun hasOrderLikeText(vararg textValues: String?): Boolean {
        val text = textValues.joinToString(" ").lowercase()
        return orderKeywords.any { text.contains(it.lowercase()) }
    }

    fun classify(
        cashValues: List<String?> = emptyList(),
        textValues: List<String?> = emptyList(),
        defaultReason: String = "涉及实付或下单链路"
    ): Pair<ExchangeSafety, String> {
        return if (hasPositiveCash(*cashValues.toTypedArray()) || hasOrderLikeText(*textValues.toTypedArray())) {
            ExchangeSafety.LOG_ONLY to defaultReason
        } else {
            ExchangeSafety.AUTO to ""
        }
    }

    fun isSuccessResponse(response: JSONObject): Boolean {
        if (response.optBoolean("success", false)) {
            return true
        }
        val resultCode = response.optString("resultCode")
        if (resultCode.equals("SUCCESS", ignoreCase = true) || resultCode == "100") {
            return true
        }
        val code = response.optString("code")
        if (code == "100000000") {
            return true
        }
        return sequenceOf(
            response.optString("resultDesc"),
            response.optString("desc"),
            response.optString("resultView")
        ).any { it == "成功" || it == "处理成功" }
    }
}

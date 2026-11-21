package io.github.aoguai.sesameag.task.antForest

import org.json.JSONArray
import org.json.JSONObject
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.VitalityStore.ExchangeStatus
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.UserMap
import io.github.aoguai.sesameag.util.maps.VitalityRewardsMap
import io.github.aoguai.sesameag.task.exchange.ExchangeFetchPacing
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsCache
import io.github.aoguai.sesameag.task.exchange.ExchangeOptionsSnapshot
import io.github.aoguai.sesameag.task.exchange.ExchangeReplenishResult
import io.github.aoguai.sesameag.task.exchange.ExchangeSafety
import io.github.aoguai.sesameag.hook.ExchangeOptionsRefreshBridge
import kotlinx.coroutines.CancellationException
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TimeUtil

object Vitality {
    private val TAG = Vitality::class.java.simpleName
    private var skuInfoLoadedAt = 0L
    val skuInfo = HashMap<String, JSONObject>()

    @JvmStatic
    fun ItemListByType(labelType: String, startIndex: Int = 0, pageSize: Int = 10): JSONObject? {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(
                AntForestRpcCall.itemList(labelType, startIndex, pageSize)
            ) ?: return null
            if (ResChecker.checkRes("${TAG}查询森林活力值商品列表失败:", jo)) {
                return jo
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "ItemListByType err")
            Log.printStackTrace(TAG, th)
        }
        return null
    }

    private fun queryVitalitySkuDetail(spuId: String, skuId: String): JSONObject? {
        val response = JsonUtil.parseJSONObjectOrNull(AntForestRpcCall.itemDetail(spuId)) ?: return null
        if (!ResChecker.checkRes("${TAG}查询森林活力值商品详情失败:", response)) return null
        val detail = response.optJSONObject("spuItemInfoVO") ?: return null
        val skus = HashMap<String, JSONObject>()
        handleVitalityItem(detail, skus)
        skuInfo.putAll(skus)
        val rewardsMap = IdMapManager.getInstance(VitalityRewardsMap::class.java)
        skus.forEach { (id, sku) -> rewardsMap.add(id, sku.optString("skuName")) }
        UserMap.currentUid?.let { rewardsMap.save(it) }
        return skus[skuId]
    }

    @JvmStatic
    fun initVitality(labelType: String, forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh && skuInfo.isNotEmpty() && TimeUtil.isSameDay(skuInfoLoadedAt, System.currentTimeMillis())) return true
        try {
            val snapshot = ExchangeOptionsCache.getOrFetch(
                UserMap.currentUid, ExchangeOptionsRefreshBridge.TARGET_FOREST_VITALITY, forceRefresh,
                context = { JSONObject().put("labelType", labelType) }
            ) { session ->
                val index = session.read("index", JSONObject(), "商城首页") {
                    JSONObject(AntForestRpcCall.queryVitalityStoreIndex())
                }
                check(ResChecker.checkRes(TAG, index)) { "森林活力值商城首页查询失败" }
                session.commit(JSONObject().put("startIndex", 0), nextLabel = "商品列表偏移 0")
                val items = JSONArray()
                val seenItemIds = linkedSetOf<String>()
                val pageSize = 10
                var startIndex = 0
                while (true) {
                    val response = session.read("items", JSONObject().put("startIndex", startIndex).put("pageSize", pageSize),
                        "商品列表偏移 $startIndex") {
                        ItemListByType(labelType, startIndex, pageSize)
                            ?: error("森林活力值商品列表查询失败: startIndex=$startIndex")
                    }
                    val page = response.optJSONArray("itemInfoVOList")
                        ?: error("森林活力值商品列表缺少 itemInfoVOList: startIndex=$startIndex count=missing hasMore=${response.opt("hasMore")} nextStartIndex=${response.opt("nextStartIndex")}")
                    val seenCount = seenItemIds.size
                    for (i in 0 until page.length()) {
                        val item = page.getJSONObject(i)
                        items.put(item)
                        item.optString("spuId").takeIf { it.isNotBlank() }?.let { seenItemIds.add("spu:$it") }
                        val skus = item.optJSONArray("skuModelList") ?: continue
                        for (j in 0 until skus.length()) {
                            skus.optJSONObject(j)?.optString("skuId")?.takeIf { it.isNotBlank() }
                                ?.let { seenItemIds.add("sku:$it") }
                        }
                    }
                    val hasMore = if (response.has("hasMore")) response.getBoolean("hasMore") else page.length() >= pageSize
                    if (!hasMore) {
                        session.commit(JSONObject().put("done", true), page.length(), "分页结束，保存完整列表")
                        break
                    }
                    val nextStartIndex = if (response.has("nextStartIndex")) response.optInt("nextStartIndex", -1) else startIndex + page.length()
                    check(page.length() > 0 && seenItemIds.size > seenCount && nextStartIndex > startIndex) {
                        "森林活力值商品列表分页未前进: startIndex=$startIndex count=${page.length()} hasMore=$hasMore nextStartIndex=$nextStartIndex"
                    }
                    session.commit(JSONObject().put("startIndex", nextStartIndex), page.length(), "商品列表偏移 $nextStartIndex")
                    startIndex = nextStartIndex
                    session.pageTurnDelay()
                }
                val skus = HashMap<String, JSONObject>()
                for (i in 0 until items.length()) handleVitalityItem(items.getJSONObject(i), skus)
                val rows = AntForest.instance?.buildVitalityExchangeOptionRows(skus) ?: error("森林模块未初始化")
                ExchangeOptionsSnapshot(rows, JSONObject().put("items", items).put("labelType", labelType))
            }
            val skus = HashMap<String, JSONObject>()
            val items = snapshot.payload.getJSONArray("items")
            for (i in 0 until items.length()) handleVitalityItem(items.getJSONObject(i), skus)
            skuInfo.clear()
            skuInfo.putAll(skus)
            skuInfoLoadedAt = System.currentTimeMillis()
            val rewardsMap = IdMapManager.getInstance(VitalityRewardsMap::class.java)
            skus.forEach { (skuId, skuModel) ->
                val skuName = skuModel.optString("skuName")
                val price = skuModel.optJSONObject("price")?.optInt("amount") ?: 0
                var orderInfo = "$skuName\n价格${price}🍃活力值"
                if (skuName.contains("能量雨") || skuName.contains("敦煌") || skuName.contains("保护罩") ||
                    skuName.contains("海洋") || skuName.contains("物种") || skuName.contains("收能量") || skuName.contains("隐身")) {
                    orderInfo += "\n每日限时兑1个"
                } else if (skuName == "限时31天内使用31天长效双击卡") {
                    orderInfo += "\n每月限时兑1个"
                }
                rewardsMap.add(skuId, orderInfo)
            }
            rewardsMap.save(UserMap.currentUid)
            return true
        } catch (th: Throwable) {
            Log.runtime(TAG, "initVitality err")
            Log.printStackTrace(TAG, th)
            return false
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun handleVitalityItem(vitalityItem: JSONObject, skus: MutableMap<String, JSONObject>) {
        try {
            val spuId = vitalityItem.optString("spuId")
            val skuModelList = vitalityItem.getJSONArray("skuModelList")
            for (i in 0 until skuModelList.length()) {
                val skuModel = skuModelList.optJSONObject(i) ?: continue
                val skuId = skuModel.optString("skuId")
                if (skuId.isEmpty()) continue

                if (!skuModel.has("spuId")) {
                    skuModel.put("spuId", spuId)
                }
                copyParentItemFields(skuModel, vitalityItem)
                skus[skuId] = skuModel
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "handleVitalityItem err")
            Log.printStackTrace(TAG, th)
            throw th
        }
    }

    private fun copyParentItemFields(skuModel: JSONObject, parentItem: JSONObject) {
        listOf("spuName", "subTitle", "labelTypeList", "itemStatus", "spuExtendInfo").forEach { key ->
            if (!skuModel.has(key) && parentItem.has(key)) {
                skuModel.put(key, parentItem.opt(key))
            }
        }
    }

    internal fun handleVitalityExchange(skuId: String): ExchangeReplenishResult {
        val dailyLimit = StatusFlags.FLAG_ANTFOREST_VITALITY_EXCHANGE_LIMIT_PREFIX + skuId
        if (Status.hasFlagToday(dailyLimit)) return ExchangeReplenishResult.BUSINESS_LIMIT
        val userId = UserMap.currentUid ?: return ExchangeReplenishResult.RETRY_LATER
        val spuId = skuInfo[skuId]?.optString("spuId")?.takeIf { it.isNotBlank() }
            ?: return ExchangeReplenishResult.NOT_AVAILABLE
        val forest = AntForest.instance ?: return ExchangeReplenishResult.RETRY_LATER
        return try {
            val sku = queryVitalitySkuDetail(spuId, skuId) ?: run {
                Log.forest("活力兑换🍃[$skuId]未从最新详情确认要兑换的商品")
                return ExchangeReplenishResult.RETRY_LATER
            }
            val item = forest.buildVitalityExchangeItem(skuId, sku)
            if (sku.optInt("userDayLeftAmount", -1) == 0) {
                Status.setFlagToday(dailyLimit, expectedUserId = userId)
                Log.forest("活力兑换🍃[${item.name}]个人当日兑换额度已用尽")
                return ExchangeReplenishResult.BUSINESS_LIMIT
            }
            if (sku.optInt("userTotalLeftAmount", -1) == 0) {
                Log.forest("活力兑换🍃[${item.name}]个人总兑换额度已用尽")
                return ExchangeReplenishResult.BUSINESS_LIMIT
            }
            if (item.safety != ExchangeSafety.AUTO) {
                Log.forest("活力兑换🍃[${item.name}]停止:${item.safetyReason}")
                val statuses = sku.optJSONArray("itemStatusList")
                val reachLimit = (0 until (statuses?.length() ?: 0)).any {
                    statuses?.optString(it) == ExchangeStatus.REACH_LIMIT.name
                }
                return if (reachLimit) ExchangeReplenishResult.BUSINESS_LIMIT else ExchangeReplenishResult.NOT_AVAILABLE
            }

            val response = JsonUtil.parseJSONObjectOrNull(AntForestRpcCall.exchangeBenefit(spuId, skuId))
            val result = when {
                response == null -> {
                    Log.error(TAG, "森林活力值兑换结果未确认 skuId=$skuId")
                    ExchangeReplenishResult.RETRY_LATER
                }
                !response.optBoolean("success") && response.optString("resultCode") == "QUOTA_USER_NOT_ENOUGH" -> {
                    Log.forest("活力兑换🍃[${item.name}]${response.optString("resultDesc")}")
                    ExchangeReplenishResult.BUSINESS_LIMIT
                }
                ResChecker.checkRes("${TAG}森林活力值兑换失败:", response) -> {
                    Status.vitalityExchangeToday(skuId)
                    Log.forest("活力兑换🍃[${item.name}]#第${Status.getVitalityCount(skuId)}次")
                    ExchangeReplenishResult.EXCHANGED
                }
                else -> ExchangeReplenishResult.RETRY_LATER
            }
            try {
                val confirmed = queryVitalitySkuDetail(spuId, skuId)
                if (confirmed == null) {
                    Log.forest("活力兑换🍃[${item.name}]兑换后详情未确认")
                } else if (confirmed.optInt("userDayLeftAmount", -1) == 0) {
                    Status.setFlagToday(dailyLimit, expectedUserId = userId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "森林活力值兑换后详情复核失败 skuId=$skuId", e)
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "森林活力值兑换失败 skuId=$skuId", e)
            ExchangeReplenishResult.RETRY_LATER
        }
    }

    @JvmStatic
    fun findSkuInfoBySkuName(spuName: String): JSONObject? {
        try {
            if (skuInfo.isEmpty()) {
                initVitality("")
            }
            for ((_, sku) in skuInfo) {
                if (sku.optString("skuName").contains(spuName)) {
                    return sku
                }
            }
        } catch (e: Exception) {
            Log.runtime(TAG, "findSkuInfoBySkuName err:")
            Log.printStackTrace(TAG, e)
        }
        return null
    }
}


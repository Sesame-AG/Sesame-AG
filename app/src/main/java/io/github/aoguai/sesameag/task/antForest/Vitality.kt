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
import io.github.aoguai.sesameag.util.ResChecker

/**
 * @author Byseven
 * @apiNote
 * @see 2025/1/20
 */
object Vitality {
    private val TAG = Vitality::class.java.simpleName
    val skuInfo = HashMap<String, JSONObject>()

    @JvmStatic
    fun ItemListByType(labelType: String, startIndex: Int = 0, pageSize: Int = 10): JSONArray? {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(
                AntForestRpcCall.itemList(labelType, startIndex, pageSize)
            ) ?: return null
            if (ResChecker.checkRes("${TAG}查询森林活力值商品列表失败:", jo)) {
                return jo.optJSONArray("itemInfoVOList")
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "ItemListByType err")
            Log.printStackTrace(TAG, th)
        }
        return null
    }

    @JvmStatic
    fun ItemDetailBySpuId(spuId: String): Boolean {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntForestRpcCall.itemDetail(spuId)) ?: return false
            if (ResChecker.checkRes("${TAG}查询森林活力值商品详情失败:", jo)) {
                val itemDetail = jo.optJSONObject("spuItemInfoVO") ?: return false
                handleItemDetail(itemDetail)
                return true
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "ItemDetailBySpuId err")
            Log.printStackTrace(TAG, th)
        }
        return false
    }

    @JvmStatic
    fun initVitality(labelType: String) {
        try {
            skuInfo.clear()
            runCatching { AntForestRpcCall.queryVitalityStoreIndex() }
                .onFailure { Log.printStackTrace(TAG, "queryVitalityStoreIndex err:", it) }
            val pageSize = 10
            var startIndex = 0
            var loadedAny = false
            while (startIndex <= 100) {
                val itemInfoVOList = ItemListByType(labelType, startIndex, pageSize)
                if (itemInfoVOList == null || itemInfoVOList.length() == 0) {
                    break
                }
                loadedAny = true
                for (i in 0 until itemInfoVOList.length()) {
                    val itemInfoVO = itemInfoVOList.optJSONObject(i) ?: continue
                    handleVitalityItem(itemInfoVO)
                }
                if (itemInfoVOList.length() < pageSize) {
                    break
                }
                startIndex += pageSize
            }
            if (!loadedAny) {
                Log.error(TAG, "活力兑换🍃初始化失败！")
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "initVitality err")
            Log.printStackTrace(TAG, th)
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun handleVitalityItem(vitalityItem: JSONObject) {
        try {
            val spuId = vitalityItem.optString("spuId")
            val skuModelList = vitalityItem.optJSONArray("skuModelList") ?: return
            for (i in 0 until skuModelList.length()) {
                val skuModel = skuModelList.optJSONObject(i) ?: continue
                val skuId = skuModel.optString("skuId")
                if (skuId.isEmpty()) continue

                val skuName = skuModel.optString("skuName")
                val price = skuModel.optJSONObject("price")?.optInt("amount") ?: 0
                var oderInfo = "$skuName\n价格${price}🍃活力值"
                
                if (skuName.contains("能量雨") || skuName.contains("敦煌") || skuName.contains("保护罩") || 
                    skuName.contains("海洋") || skuName.contains("物种") || skuName.contains("收能量") || skuName.contains("隐身")) {
                    oderInfo = "$skuName\n价格${price}🍃活力值\n每日限时兑1个"
                } else if (skuName == "限时31天内使用31天长效双击卡") {
                    oderInfo = "$skuName\n价格${price}🍃活力值\n每月限时兑1个"
                }
                
                if (!skuModel.has("spuId")) {
                    skuModel.put("spuId", spuId)
                }
                copyParentItemFields(skuModel, vitalityItem)
                skuInfo[skuId] = skuModel
                IdMapManager.getInstance(VitalityRewardsMap::class.java).add(skuId, oderInfo)
            }
            UserMap.currentUid?.let { IdMapManager.getInstance(VitalityRewardsMap::class.java).save(it) }
        } catch (th: Throwable) {
            Log.runtime(TAG, "handleVitalityItem err")
            Log.printStackTrace(TAG, th)
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun handleItemDetail(ItemDetail: JSONObject) {
        try {
            val spuId = ItemDetail.optString("spuId")
            val skuModelList = ItemDetail.optJSONArray("skuModelList") ?: return
            for (i in 0 until skuModelList.length()) {
                val skuModel = skuModelList.optJSONObject(i) ?: continue
                val skuId = skuModel.optString("skuId")
                if (skuId.isEmpty()) continue

                val skuName = skuModel.optString("skuName")
                if (!skuModel.has("spuId")) {
                    skuModel.put("spuId", spuId)
                }
                copyParentItemFields(skuModel, ItemDetail)
                skuInfo[skuId] = skuModel
                IdMapManager.getInstance(VitalityRewardsMap::class.java).add(skuId, skuName)
            }
            UserMap.currentUid?.let { IdMapManager.getInstance(VitalityRewardsMap::class.java).save(it) }
        } catch (th: Throwable) {
            Log.runtime(TAG, "handleItemDetail err:")
            Log.printStackTrace(TAG, th)
        }
    }

    private fun copyParentItemFields(skuModel: JSONObject, parentItem: JSONObject) {
        listOf("spuName", "subTitle", "labelTypeList", "itemStatus", "spuExtendInfo").forEach { key ->
            if (!skuModel.has(key) && parentItem.has(key)) {
                skuModel.put(key, parentItem.opt(key))
            }
        }
    }

    @JvmStatic
    fun handleVitalityExchange(skuId: String): Boolean {
        if (Status.hasFlagToday(StatusFlags.FLAG_ANTFOREST_VITALITY_EXCHANGE_LIMIT_PREFIX + skuId)) {
            Log.forest("活力兑换🍃[$skuId]今日已达上限，跳过兑换")
            return false
        }

        if (skuInfo.isEmpty()) {
            initVitality("")
        }
        
        var sku = skuInfo[skuId] ?: run {
            Log.forest("活力兑换🍃找不到要兑换的权益！")
            return false
        }
        
        try {
            var skuName = sku.optString("skuName")
            val spuId = sku.optString("spuId")
            if (spuId.isEmpty()) return false
            if (!ItemDetailBySpuId(spuId)) {
                Log.forest("活力兑换🍃[$skuName]兑换前详情复核失败")
                return false
            }
            sku = skuInfo[skuId] ?: sku
            skuName = sku.optString("skuName").ifBlank { skuName }
            val itemStatusList = sku.optJSONArray("itemStatusList") ?: JSONArray()
            for (i in 0 until itemStatusList.length()) {
                val itemStatus = itemStatusList.optString(i)
                val status = runCatching { ExchangeStatus.valueOf(itemStatus) }.getOrNull() ?: continue
                if (status.name == itemStatus) {
                    Log.forest("活力兑换🍃[$skuName]停止:${status.nickName}")
                    if (ExchangeStatus.REACH_LIMIT.name == itemStatus) {
                        Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_VITALITY_EXCHANGE_LIMIT_PREFIX + skuId)
                        Log.forest("活力兑换🍃[$skuName]已达上限,停止兑换！")
                    }
                    return false
                }
            }
            
            if (VitalityExchange(spuId, skuId, skuName)) {
                if (skuName.contains("限时")) {
                    Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_VITALITY_EXCHANGE_LIMIT_PREFIX + skuId)
                }
                ItemDetailBySpuId(spuId)
                return true
            }
            ItemDetailBySpuId(spuId)
        } catch (th: Throwable) {
            Log.runtime(TAG, "VitalityExchange err")
            Log.printStackTrace(TAG, th)
        }
        return false
    }

    @JvmStatic
    fun VitalityExchange(spuId: String, skuId: String, skuName: String): Boolean {
        try {
            if (VitalityExchange(spuId, skuId)) {
                Status.vitalityExchangeToday(skuId)
                val exchangedCount = Status.getVitalityCount(skuId)
                Log.forest("活力兑换🍃[$skuName]#第${exchangedCount}次")
                return true
            }
        } catch (th: Throwable) {
            Log.runtime(TAG, "VitalityExchange err:$spuId,$skuId")
            Log.printStackTrace(TAG, th)
        }
        return false
    }

    private fun VitalityExchange(spuId: String, skuId: String): Boolean {
        try {
            val jo = JsonUtil.parseJSONObjectOrNull(AntForestRpcCall.exchangeBenefit(spuId, skuId)) ?: return false
            if (!jo.optBoolean("success")) {
                val resultCode = jo.optString("resultCode", "")
                if ("QUOTA_USER_NOT_ENOUGH" == resultCode) {
                    Log.forest("活力兑换🍃[兑换次数已达上限]#${jo.optString("resultDesc", "")}")
                    Status.setFlagToday(StatusFlags.FLAG_ANTFOREST_VITALITY_EXCHANGE_LIMIT_PREFIX + skuId)
                    return false
                }
            }
            return ResChecker.checkRes("${TAG}森林活力值兑换失败:", jo)
        } catch (th: Throwable) {
            Log.runtime(TAG, "VitalityExchange err:$spuId,$skuId")
            Log.printStackTrace(TAG, th)
        }
        return false
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


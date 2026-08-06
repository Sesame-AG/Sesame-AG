package io.github.aoguai.sesameag.hook.rpc.debug

import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.task.reserve.ReserveRpcCall
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import kotlinx.coroutines.Dispatchers
import org.json.JSONException
import org.json.JSONObject

/**
 * RPC调试工具类
 */
class DebugRpc {
    fun start(broadcastFun: String, broadcastData: String, testType: String) {
        GlobalThreadPools.execute(Dispatchers.IO) {
            when (testType) {
                "Rpc" -> {
                    val result = test(broadcastFun, broadcastData)
                    Log.debug("收到测试消息:\n方法:$broadcastFun\n数据:$broadcastData\n结果:$result")
                }
                "getNewTreeItems" -> getNewTreeItems() // 获取新树上苗🌱信息
                "getTreeItems" -> getTreeItems() // 🔍查询树苗余量
                "queryAreaTrees" -> queryAreaTrees()
                "getUnlockTreeItems" -> getUnlockTreeItems()
                "walkGrid" -> walkGrid() // 走格子
                else -> Log.debug("未知的测试类型: $testType")
            }
        }
    }

    private fun test(method: String, data: String): String? = RequestManager.requestString(method, data)

    private fun getNewTreeItems() {
        try {
            val s = ReserveRpcCall.queryTreeItemsForExchange() ?: return
            val jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val ja = jo.getJSONArray("treeItems")
                for (i in 0 until ja.length()) {
                    val item = ja.getJSONObject(i)
                    if (!item.has("projectType")) continue
                    if (item.optString("projectType") != "TREE") continue
                    if (item.optString("applyAction") != "COMING") continue
                    val projectId = item.optString("itemId")
                    queryTreeForExchange(projectId)
                }
            } else {
                Log.runtime(TAG, jo.optString("resultDesc", "Unknown error"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "getTreeItems err:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 查询特定项目下可交换树木的信息
     *
     * @param projectId 项目ID
     */
    private fun queryTreeForExchange(projectId: String) {
        try {
            val response = ReserveRpcCall.queryTreeForExchange(projectId) ?: return
            val jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                val exchangeableTree = jo.getJSONObject("exchangeableTree")
                val currentBudget = exchangeableTree.getInt("currentBudget")
                val region = exchangeableTree.optString("region", "")
                val treeName = exchangeableTree.optString("treeName", "")
                
                val tips = if (exchangeableTree.optBoolean("canCoexchange", false)) {
                    val coexchangeTypeIdList = exchangeableTree
                        .getJSONObject("extendInfo")
                        .optString("cooperate_template_id_list", "")
                    "可以合种-合种类型：$coexchangeTypeIdList"
                } else {
                    "不可合种"
                }
                
                Log.debug(TAG, "新树上苗🌱[$region-$treeName]#${currentBudget}株-$tips")
            } else {
                Log.debug("${jo.optString("resultDesc", "Error")} projectId: $projectId")
            }
        } catch (e: JSONException) {
            Log.runtime(TAG, "JSON解析错误:")
            Log.printStackTrace(TAG, e)
        } catch (t: Throwable) {
            Log.runtime(TAG, "查询树木交换信息过程中发生错误:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 获取可交换的树木项目列表，并对每个可用的项目查询当前预算
     */
    private fun getTreeItems() {
        try {
            val response = ReserveRpcCall.queryTreeItemsForExchange() ?: return
            val jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                val ja = jo.getJSONArray("treeItems")
                for (i in 0 until ja.length()) {
                    val item = ja.getJSONObject(i)
                    if (!item.has("projectType")) continue
                    if (item.optString("applyAction") != "AVAILABLE") continue
                    val projectId = item.optString("itemId")
                    val itemName = item.optString("itemName")
                    getTreeCurrentBudget(projectId, itemName)
                    GlobalThreadPools.sleepCompat(100)
                }
            } else {
                Log.runtime(TAG, jo.optString("resultDesc", "Unknown error"))
            }
        } catch (e: JSONException) {
            Log.runtime(TAG, "JSON解析错误:")
            Log.printStackTrace(TAG, e)
        } catch (t: Throwable) {
            Log.runtime(TAG, "获取树木项目列表过程中发生错误:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 树苗查询
     *
     * @param projectId 项目ID
     * @param treeName 树木名称
     */
    private fun getTreeCurrentBudget(projectId: String, treeName: String) {
        try {
            val response = ReserveRpcCall.queryTreeForExchange(projectId) ?: return
            val jo = JSONObject(response)
            if (ResChecker.checkRes(TAG, jo)) {
                val exchangeableTree = jo.getJSONObject("exchangeableTree")
                val currentBudget = exchangeableTree.getInt("currentBudget")
                val region = exchangeableTree.optString("region", "")
                Log.debug(TAG, "树苗查询🌱[$region-$treeName]#剩余:$currentBudget")
            } else {
                Log.debug("${jo.optString("resultDesc", "Error")} projectId: $projectId")
            }
        } catch (e: JSONException) {
            Log.runtime(TAG, "JSON解析错误:")
            Log.printStackTrace(TAG, e)
        } catch (t: Throwable) {
            Log.runtime(TAG, "查询树木交换信息过程中发生错误:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 模拟网格行走过程，处理行走中的事件，如完成迷你游戏和广告任务
     */
    private fun walkGrid() {
        try {
            val s = DebugRpcCall.walkGrid() ?: return
            val jo = JSONObject(s)
            if (jo.getBoolean("success")) {
                val data = jo.getJSONObject("data")
                if (!data.has("mapAwards")) return
                
                val mapAwards = data.getJSONArray("mapAwards")
                val mapAward = mapAwards.getJSONObject(0)
                
                if (mapAward.has("miniGameInfo")) {
                    val miniGameInfo = mapAward.getJSONObject("miniGameInfo")
                    val gameId = miniGameInfo.optString("gameId")
                    val key = miniGameInfo.optString("key")
                    
                    GlobalThreadPools.sleepCompat(4000L)
                    val gameResultStr = DebugRpcCall.miniGameFinish(gameId, key) ?: return
                    val gameResult = JSONObject(gameResultStr)
                    
                    if (gameResult.getBoolean("success")) {
                        val miniGamedata = gameResult.getJSONObject("data")
                        if (miniGamedata.has("adVO")) {
                            val adVO = miniGamedata.getJSONObject("adVO")
                            if (adVO.has("adBizNo")) {
                                val adBizNo = adVO.optString("adBizNo")
                                val taskResultStr = DebugRpcCall.taskFinish(adBizNo) ?: return
                                val taskResult = JSONObject(taskResultStr)
                                
                                if (taskResult.getBoolean("success")) {
                                    val queryResultStr = DebugRpcCall.queryAdFinished(adBizNo, "NEVERLAND_DOUBLE_AWARD_AD") ?: return
                                    val queryResult = JSONObject(queryResultStr)
                                    if (queryResult.getBoolean("success")) {
                                        Log.debug("完成双倍奖励🎁")
                                    }
                                }
                            }
                        }
                    }
                }
                
                val leftCount = data.getInt("leftCount")
                if (leftCount > 0) {
                    GlobalThreadPools.sleepCompat(3000L)
                    walkGrid() // 递归调用，继续行走
                }
            } else {
                Log.debug("${jo.optString("errorMsg", "Error")}$s")
            }
        } catch (e: JSONException) {
            Log.runtime(TAG, "JSON解析错误:")
            Log.printStackTrace(TAG, e)
        } catch (t: Throwable) {
            Log.runtime(TAG, "行走网格过程中发生错误:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun queryAreaTrees() {
        try {
            val resultStr = ReserveRpcCall.queryAreaTrees() ?: return
            val jo = JSONObject(resultStr)
            if (!ResChecker.checkRes(TAG, jo)) return
            
            val areaTrees = jo.getJSONObject("areaTrees")
            val regionConfig = jo.getJSONObject("regionConfig")
            val regionKeys = regionConfig.keys()
            
            while (regionKeys.hasNext()) {
                val regionKey = regionKeys.next()
                if (!areaTrees.has(regionKey)) {
                    val region = regionConfig.getJSONObject(regionKey)
                    val regionName = region.optString("regionName")
                    Log.debug(TAG, "未解锁地区🗺️[$regionName]")
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryAreaTrees err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun getUnlockTreeItems() {
        try {
            val resultStr = ReserveRpcCall.queryTreeItemsForExchange("", "project") ?: return
            val jo = JSONObject(resultStr)
            if (!ResChecker.checkRes(TAG, jo)) return
            
            val ja = jo.getJSONArray("treeItems")
            for (i in 0 until ja.length()) {
                val item = ja.getJSONObject(i)
                if (!item.has("projectType")) continue
                
                val certCountForAlias = item.optInt("certCountForAlias", -1)
                if (certCountForAlias == 0) {
                    val itemName = item.optString("itemName")
                    val region = item.optString("region")
                    val organization = item.optString("organization")
                    Log.debug(TAG, "未解锁项目🐘[$region-$itemName]#$organization")
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "getUnlockTreeItems err:")
            Log.printStackTrace(TAG, t)
        }
    }

    companion object {
        private const val TAG = "DebugRpc"
    }
}


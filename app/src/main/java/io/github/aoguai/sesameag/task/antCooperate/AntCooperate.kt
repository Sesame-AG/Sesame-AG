package io.github.aoguai.sesameag.task.antCooperate


import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.CooperateEntity.Companion.getList
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.ConfigPortScope
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.IntegerModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectAndCountModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.withPortScope
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.maps.CooperateMap
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

class AntCooperate : ModelTask() {
    /**
     * 获取任务名称
     *
     * @return 合种任务名称
     */
    override fun getName(): String {
        return "蚂蚁森林合种" // 保留这个全称
    }

    /**
     * 获取任务分组
     *
     * @return 森林分组
     */
    override fun getGroup(): ModelGroup = ModelGroup.FOREST

    /**
     * 获取任务图标
     *
     * @return 合种任务图标文件名
     */
    override fun getIcon(): String = "AntCooperate.png"

    private val cooperateWater =
        BooleanModelField("cooperateWater", "合种浇水 | 开启", false).withDesc(
            "按“合种浇水 | 列表与单次克数”自动浇水；未配置浇水克数的合种会跳过。",
        )
    private val cooperateWaterList =
        SelectAndCountModelField(
            "cooperateWaterList",
            "合种浇水 | 列表与单次克数",
            LinkedHashMap<String?, Int?>(),
            { getList() },
            "设置每个合种单次最多浇多少克；首次开启并执行一次后，返回设置页可刷新出合种列表。",
        ).withPortScope(ConfigPortScope.ACCOUNT_PRIVATE)
    private val cooperateWaterTotalLimitList =
        SelectAndCountModelField(
            "cooperateWaterTotalLimitList",
            "合种浇水 | 总量限制",
            LinkedHashMap<String?, Int?>(),
            { getList() },
            "限制每个合种的累计总浇水量；达到后即使当天还有额度也不再浇。",
        ).withPortScope(ConfigPortScope.ACCOUNT_PRIVATE)
    private val cooperateSendCooperateBeckon =
        BooleanModelField("cooperateSendCooperateBeckon", "合种 | 召唤队友浇水", false).withDesc(
            "仅队长可用，18:00 后自动召唤还能浇水的队友；可单独开启，不依赖“合种浇水 | 开启”。",
        )
    private val loveCooperateWater =
        BooleanModelField("loveCooperateWater", "真爱合种 | 开启浇水", false).withDesc(
            "按服务端今日浇水记录补足每日目标。",
        )
    private val loveCooperateWaterNum =
        IntegerModelField("loveCooperateWaterNum", "真爱合种 | 每日浇水总量", 20).withDesc(
            "包含当天已浇水量；剩余目标不足单次最小量20g时不再加量。",
        )
    private val teamCooperateWaterNum =
        IntegerModelField("teamCooperateWaterNum", "组队合种 | 每日浇水总量(0关闭)", 0, 0, 5000).withDesc(
            "组队合种每天目标浇水总量；填 0 关闭，实际会受官方剩余额度和当前能量限制。",
        )

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(cooperateWater)
        modelFields.addField(cooperateWaterList)
        modelFields.addField(cooperateWaterTotalLimitList)
        modelFields.addField(cooperateSendCooperateBeckon)
        // 真爱合种配置
        modelFields.addField(loveCooperateWater)
        modelFields.addField(loveCooperateWaterNum)
        // 组队合种配置
        modelFields.addField(teamCooperateWaterNum)
        return modelFields
    }

    /**
     * 执行合种任务的主要逻辑
     */
    override suspend fun runSuspend() {
        try {
            Log.forest("执行开始-${getName() ?: ""}")

            // 1. 真爱合种
            if (loveCooperateWater.value == true) {
                loveCooperateWater()
            }

            // 2. 组队合种
            if ((teamCooperateWaterNum.value ?: 0) > 0) {
                teamCooperateWater()
            }
            // 3. 普通合种/召唤队友浇水
            val enableCooperateWater = cooperateWater.value == true
            val enableCooperateBeckon = cooperateSendCooperateBeckon.value == true
            if (enableCooperateWater || enableCooperateBeckon) {
                val queryUserCooperatePlantList = JSONObject(AntCooperateRpcCall.queryUserCooperatePlantList())
                if (ResChecker.checkRes(TAG, queryUserCooperatePlantList)) {
                    // 1. 获取当前能量，设为 var，因为浇水后本地需要扣减，否则下一个合种会误判能量充足
                    var userCurrentEnergy = queryUserCooperatePlantList.getInt("userCurrentEnergy")
                    val cooperatePlants = queryUserCooperatePlantList.getJSONArray("cooperatePlants")
                    Log.forest("获取合种列表成功: ${cooperatePlants.length()} 颗合种")
                    for (i in 0 until cooperatePlants.length()) {
                        var plant = cooperatePlants.getJSONObject(i)
                        val cooperationId = plant.getString("cooperationId")
                        // 补全缺失的合种名称信息
                        if (!plant.has("name")) {
                            val detailResp = AntCooperateRpcCall.queryCooperatePlant(cooperationId)
                            plant = JSONObject(detailResp).getJSONObject("cooperatePlant")
                        }

                        val name = plant.getString("name")
                        val admin = plant.getString("admin")

                        // 2. 合种打招呼逻辑 (独立判断，不影响浇水主流程)
                        if (enableCooperateBeckon && UserMap.currentUid == admin) {
                            cooperateSendCooperateBeckon(cooperationId, name)
                        }

                        // 3. 记录合种信息到本地 Map
                        io.github.aoguai.sesameag.util.maps.IdMapManager
                            .getInstance(CooperateMap::class.java)
                            .add(cooperationId, name)

                        if (!enableCooperateWater) {
                            continue
                        }

                        // 4. 检查是否满足“今日是否可浇水”的本地状态缓存
                        if (!Status.canCooperateWaterToday(UserMap.currentUid, cooperationId)) {
                            // Log.runtime(TAG, "$name 今日已标记为不可浇水/已浇完")
                            continue
                        }

                        // 获取服务端限制
                        val waterDayLimit = plant.getInt("waterDayLimit") // 今日剩余可浇水量
                        val waterLimit = plant.getJSONObject("cooperateTemplate").getInt("waterLimit") // 每日总上限
                        // val watered = waterLimit - waterDayLimit
                        Log.forest("获取合种[$name] 浇水信息: 剩余可浇 $waterDayLimit g / 总限制 $waterLimit g")

                        // 5. 获取配置
                        val configPerRound = cooperateWaterList.value?.get(cooperationId) // 本轮配置浇水量
                        val configTotalLimit = cooperateWaterTotalLimitList.value?.get(cooperationId) // 配置的总浇水上限(累计)

                        if (configPerRound == null) {
                            Log.forest("浇水列表中没有为[$name]配置，跳过")
                            continue
                        }

                        // 6. 计算本轮目标浇水量 (Target Water)
                        var planToWater: Int

                        if (configTotalLimit == null) {
                            // 逻辑保持原意：如果没有配置总限制，则直接把今日剩余额度拉满
                            Log.forest("未配置 $name 限制总浇水，目标为填满今日可浇水量（服务端或本地限制）")
                            planToWater = waterDayLimit
                        } else {
                            Log.forest("载入配置 $name 限制总浇水[$configTotalLimit]g")
                            val totalWatered = getTotalWatering(cooperationId) // 获取已累计浇水

                            if (totalWatered < 0) {
                                Log.forest("无法获取用户[${UserMap.currentUid}]的累计浇水数据，跳过 $name")
                                continue
                            }

                            val remainingQuota = configTotalLimit - totalWatered
                            if (remainingQuota <= 0) {
                                Log.forest("$name 累计浇水已达标($totalWatered/$configTotalLimit)，跳过")
                                continue
                            }

                            planToWater = remainingQuota
                        }

                        val actualWater =
                            planToWater
                                .coerceAtMost(waterDayLimit)
                                .coerceAtMost(configPerRound)
                                .coerceAtMost(userCurrentEnergy)

                        Log.forest("[$name] 结算: 计划 $planToWater, 剩余限额 $waterDayLimit, 背包 $userCurrentEnergy -> 实际: $actualWater")

                        if (actualWater > 0) {
                            if (cooperateWater(cooperationId, actualWater, name)) {
                                // Only confirmed writes affect later plants in this same snapshot.
                                userCurrentEnergy -= actualWater
                            }
                        } else {
                            Log.forest("合种[$name]没有可执行的浇水额度")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        } finally {
            io.github.aoguai.sesameag.util.maps.IdMapManager
                .getInstance(CooperateMap::class.java)
                .save(UserMap.currentUid)
            Log.forest("执行结束-${getName() ?: ""}")
        }
    }

    private fun loveTeamWateredAmount(teamInfo: JSONObject): Int {
        val userId = UserMap.currentUid?.takeIf { it.isNotBlank() } ?: return -1
        val todayWaterMap = teamInfo.optJSONObject("waterInfo")?.optJSONObject("todayWaterMap") ?: return -1
        return if (todayWaterMap.has(userId)) todayWaterMap.optInt(userId, -1) else 0
    }

    // 真爱合种逻辑
    private fun loveCooperateWater() {
        try {
            val target = loveCooperateWaterNum.value ?: 0
            if (target < 20) {
                Log.forest("真爱合种目标不足单次最小浇水量20g，不执行")
                return
            }

            // 每次以服务端记录为准，允许修改目标后继续，也不依赖本地状态是否被清空。
            // 2. 查询首页数据
            val queryLoveHome =
                try {
                    JSONObject(AntCooperateRpcCall.queryLoveHome())
                } catch (e: Exception) {
                    Log.printStackTrace(TAG, "真爱合种响应JSON解析失败", e)
                    return
                }

            if (!ResChecker.checkRes(TAG, queryLoveHome)) {
                // ResChecker 内部通常已经打印了错误日志
                return
            }

            // 3. 解析队伍信息
            val teamInfo = queryLoveHome.optJSONObject("teamInfo")
            if (teamInfo == null) {
                Log.error(TAG, "未找到真爱合种队伍信息，可能是未开启或结构变更")
                // 如果确认是未开启，可以考虑自动关闭开关
                // loveCooperateWater.value = false
                return
            }

            val teamId = queryLoveHome.optJSONObject("userInfo")?.optString("teamId").orEmpty()
                .ifBlank { teamInfo.optString("teamId") }
            val teamStatus = teamInfo.optString("teamStatus")
            if (teamId.isBlank() || teamStatus.isBlank()) {
                Log.error(TAG, "真爱合种缺少队伍标识或状态：$queryLoveHome")
                return
            }
            if (teamStatus != "ACTIVATED") {
                Log.forest("真爱合种当前状态[$teamStatus]，尚未激活")
                return
            }
            val myWateredAmount = loveTeamWateredAmount(teamInfo)
            if (myWateredAmount < 0) {
                Log.error(TAG, "真爱合种缺少今日浇水记录：$teamInfo")
                return
            }
            val waterAmount = target - myWateredAmount
            if (waterAmount < 20) {
                Log.forest("真爱合种今日已浇${myWateredAmount}g / 目标${target}g，剩余额度不足20g")
                if (myWateredAmount >= target) Status.setFlagToday(StatusFlags.FLAG_ANTCOOPERATE_LOVE_TEAM_WATER)
                return
            }

            try {
                val waterJo = JSONObject(AntCooperateRpcCall.loveTeamWater(teamId, waterAmount))
                if (!ResChecker.checkRes(TAG, waterJo)) {
                    Log.error(TAG, "真爱合种浇水响应未确认，将查询实际进度：$waterJo")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "真爱合种浇水响应异常，将查询实际进度", e)
            }

            val confirmation = JSONObject(AntCooperateRpcCall.queryLoveHome())
            if (!ResChecker.checkRes(TAG, confirmation)) {
                Log.error(
                    TAG,
                    "module=合种 action=loveTeamWater rpc=AntCooperateRpcCall.queryLoveHome " +
                        "code=${confirmation.optString("resultCode")} msg=动作成功后无法确认状态 " +
                        "classification=UNKNOWN_NEEDS_REVIEW decision=RETRY_LATER",
                )
                return
            }
            val confirmedWateredAmount =
                confirmation
                    .optJSONObject("teamInfo")
                    ?.let(::loveTeamWateredAmount) ?: 0
            if (confirmedWateredAmount > myWateredAmount) {
                Log.forest("真爱合种💖今日已浇${confirmedWateredAmount}g / 目标${target}g，本轮增加${confirmedWateredAmount - myWateredAmount}g")
                if (confirmedWateredAmount >= target) Status.setFlagToday(StatusFlags.FLAG_ANTCOOPERATE_LOVE_TEAM_WATER)
            } else {
                Log.error(
                    TAG,
                    "module=合种 action=loveTeamWater rpc=AntCooperateRpcCall.queryLoveHome " +
                        "code=NO_PROGRESS msg=动作成功后未确认今日浇水记录 " +
                        "classification=UNKNOWN_NEEDS_REVIEW decision=RETRY_LATER",
                )
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "loveCooperateWater 异常:", t)
        }
    }

    private fun teamCooperateWater() {
        var switchedToTeamMode = false
        try {
            val userDailyTarget = teamCooperateWaterNum.value ?: 0
            if (userDailyTarget < 10) {
                Log.forest("组队合种目标不足单次最小浇水量10g，不执行")
                return
            }

            val homeJo = JSONObject(AntCooperateRpcCall.queryHomePage())
            if (!ResChecker.checkRes(TAG, homeJo)) {
                Log.forest("queryHomePage 返回异常")
                return
            }
            val teamId =
                homeJo
                    .optJSONObject("teamHomeResult")
                    ?.optJSONObject("teamBaseInfo")
                    ?.optString("teamId")
                    ?.takeIf { it.isNotBlank() }
            if (teamId == null) {
                Log.forest("未获取到组队合种 TeamID")
                return
            }

            val memberResponse = JSONObject(AntCooperateRpcCall.queryTeamMemberList(teamId))
            if (!ResChecker.checkRes(TAG, memberResponse)) return
            val members = memberResponse.optJSONArray("memberList")
            val self = members?.let { list ->
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .firstOrNull { it.optBoolean("self") || it.optString("userId") == UserMap.currentUid }
            }
            val todayUsed = self?.optInt("todayEnergy", -1) ?: -1
            if (todayUsed < 0) {
                Log.error(TAG, "组队合种缺少本人的今日浇水记录：$memberResponse")
                return
            }
            Status.setIntFlagToday(StatusFlags.FLAG_TEAM_WATER_DAILY_COUNT, todayUsed)
            val userRemainingQuota = userDailyTarget - todayUsed
            if (userRemainingQuota < 10) {
                Log.forest("组队合种今日已浇${todayUsed}g / 目标${userDailyTarget}g，剩余不足10g")
                return
            }
            if (homeJo.optString("nextAction") !in setOf("Team", "Cultivate")) {
                Log.error(TAG, "组队合种无法确认当前森林模式：${homeJo.optString("nextAction")}")
                return
            }
            val currentEnergy = homeJo.optJSONObject("userBaseInfo")?.optInt("currentEnergy", -1) ?: -1
            if (currentEnergy < 0) {
                Log.error(TAG, "组队合种缺少可用能量，未执行浇水")
                return
            }
            if (currentEnergy < 10) {
                Log.forest("当前能量不足10g (${currentEnergy}g)，无法浇水")
                return
            }

            if (!isTeam(homeJo)) {
                switchedToTeamMode = true
                val modeResponse = JSONObject(AntCooperateRpcCall.updateUserConfig(true))
                if (!ResChecker.checkRes(TAG, modeResponse)) {
                    Log.error(
                        TAG,
                        "module=合种 action=switchToTeamMode rpc=AntCooperateRpcCall.updateUserConfig " +
                            "code=${modeResponse.optString("resultCode")} msg=${modeResponse.optString("resultDesc")} " +
                            "classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY",
                    )
                    return
                }
                val switchedHome = JSONObject(AntCooperateRpcCall.queryHomePage())
                if (!ResChecker.checkRes(TAG, switchedHome) || !isTeam(switchedHome)) {
                    Log.error(TAG, "组队合种未确认切换到团队模式：$switchedHome")
                    return
                }
                Log.forest("已临时切换至组队浇水模式")
            }

            val miscJo = JSONObject(AntCooperateRpcCall.queryMiscInfo("teamCanWaterCount", teamId))
            if (!ResChecker.checkRes(TAG, miscJo)) {
                Log.forest("queryMiscInfo 查询失败")
                return
            }
            val serverRemaining =
                miscJo
                    .optJSONObject("combineHandlerVOMap")
                    ?.optJSONObject("teamCanWaterCount")
                    ?.optInt("waterCount", -1) ?: -1
            if (serverRemaining < 0) {
                Log.error(TAG, "组队合种查询未返回剩余浇水额度：$miscJo")
                return
            }
            Log.forest("组队状态检查: 目标剩余${userRemainingQuota}g | 官方剩余${serverRemaining}g | 背包能量${currentEnergy}g")
            if (serverRemaining < 10) {
                Log.forest("官方限制今日无可浇水额度，跳过")
                return
            }

            val finalWaterAmount =
                userRemainingQuota
                    .coerceAtMost(serverRemaining)
                    .coerceAtMost(currentEnergy)
            if (finalWaterAmount < 10) {
                Log.forest("计算后浇水量(${finalWaterAmount}g)低于最小限制10g，不执行")
                return
            }

            try {
                val waterJo = JSONObject(AntCooperateRpcCall.teamWater(teamId, finalWaterAmount))
                if (!ResChecker.checkRes(TAG, waterJo)) Log.error(TAG, "组队浇水响应未确认，将查询实际进度：$waterJo")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "组队浇水响应异常，将查询实际进度", e)
            }
            val confirmation = JSONObject(AntCooperateRpcCall.queryTeamMemberList(teamId))
            if (!ResChecker.checkRes(TAG, confirmation)) return
            val confirmedMembers = confirmation.optJSONArray("memberList")
            val confirmedSelf = confirmedMembers?.let { list ->
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .firstOrNull { it.optBoolean("self") || it.optString("userId") == UserMap.currentUid }
            }
            val confirmedTotal = confirmedSelf?.optInt("todayEnergy", -1) ?: -1
            if (confirmedTotal > todayUsed) {
                Status.setIntFlagToday(StatusFlags.FLAG_TEAM_WATER_DAILY_COUNT, confirmedTotal)
                Log.forest("组队合种🌲今日已浇${confirmedTotal}g / 目标${userDailyTarget}g，本轮增加${confirmedTotal - todayUsed}g")
            } else {
                Log.error(TAG, "组队浇水后未确认进度，未增加本地计数：$confirmation")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "teamCooperateWater 异常:", t)
        } finally {
            if (switchedToTeamMode) {
                restorePersonalCooperateMode()
            }
        }
    }

    private fun restorePersonalCooperateMode() {
        try {
            val response = JSONObject(AntCooperateRpcCall.updateUserConfig(false))
            if (ResChecker.checkRes(TAG, response)) {
                val home = JSONObject(AntCooperateRpcCall.queryHomePage())
                if (ResChecker.checkRes(TAG, home) && home.optString("nextAction") == "Cultivate") {
                    Log.forest("已恢复运行前的个人模式")
                } else {
                    Log.error(TAG, "未确认森林恢复个人模式：$home")
                }
            } else {
                Log.error(
                    TAG,
                    "module=合种 action=restorePersonalMode rpc=AntCooperateRpcCall.updateUserConfig " +
                        "code=${response.optString("resultCode")} msg=${response.optString("resultDesc")} " +
                        "classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY",
                )
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "restorePersonalCooperateMode 异常:", t)
        }
    }

    companion object {
        private val TAG: String = AntCooperate::class.java.getSimpleName()

        /**
         * 判断是否为团队
         *
         * @param homeObj 用户主页的JSON对象
         * @return 是否为团队
         */
        private fun isTeam(homeObj: JSONObject): Boolean {
            return "Team" == homeObj.optString("nextAction", "")
        }

        private fun cooperateWater(
            coopId: String,
            count: Int,
            name: String,
        ): Boolean {
            try {
                val jo = JSONObject(AntCooperateRpcCall.cooperateWater(UserMap.currentUid, coopId, count))
                if (ResChecker.checkRes(TAG, jo)) {
                    Log.forest("合种浇水🚿[$name]${jo.optString("barrageText")}")
                    Status.cooperateWaterToday(UserMap.currentUid, coopId)
                    return true
                }
                Log.error(
                    TAG,
                    "module=合种 action=cooperateWater rpc=AntCooperateRpcCall.cooperateWater " +
                        "code=${jo.optString("resultCode")} msg=${jo.optString("resultDesc")} " +
                        "classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY plant=$name",
                )
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "cooperateWater err:", t)
            }
            return false
        }

        /**
         * 计算合种需要浇水的克数
         */
        private fun getTotalWatering(coopId: String?): Int {
            try {
                val jo = JSONObject(AntCooperateRpcCall.queryCooperateRank("A", coopId))
                if (ResChecker.checkRes(TAG, jo)) {
                    val jaList = jo.getJSONArray("cooperateRankInfos")
                    for (i in 0..<jaList.length()) {
                        val joItem = jaList.getJSONObject(i)
                        val userId = joItem.getString("userId")
                        if (userId == UserMap.currentUid) {
                            // 未获取到累计浇水量 返回 -1 不执行浇水
                            val energySummation = joItem.optInt("energySummation", -1)
                            if (energySummation >= 0) {
                                Log.forest("当前用户[$userId]的累计浇水能量: $energySummation")
                            }
                            return energySummation
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "计算合种需要浇水的克数err", t)
            }
            Log.error(TAG, "合种获取累计浇水量失败")
            return -1 // 未获取到累计浇水量，停止浇水
        }

        /**
         * 召唤队友浇水（仅队长）
         */
        private fun cooperateSendCooperateBeckon(
            cooperationId: String,
            name: String,
        ) {
            try {
                if (TimeUtil.isNowBeforeTimeStr("1800")) {
                    return
                }
                var jo = JSONObject(AntCooperateRpcCall.queryCooperateRank("D", cooperationId))
                if (ResChecker.checkRes(TAG, jo)) {
                    val cooperateRankInfos = jo.getJSONArray("cooperateRankInfos")
                    for (i in 0..<cooperateRankInfos.length()) {
                        val rankInfo = cooperateRankInfos.getJSONObject(i)
                        if (rankInfo.getBoolean("canBeckon")) {
                            jo = JSONObject(AntCooperateRpcCall.sendCooperateBeckon(rankInfo.getString("userId"), cooperationId))
                            if (ResChecker.checkRes(TAG, jo)) {
                                Log.forest("合种🚿[" + name + "]#召唤队友[" + rankInfo.getString("displayName") + "]成功")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "召唤队友和种错误：", t)
            }
        }
    }
}

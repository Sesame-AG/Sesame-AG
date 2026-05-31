package io.github.aoguai.sesameag.data

import com.fasterxml.jackson.databind.JsonMappingException
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.antForest.AntForest
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.get
import kotlin.collections.set

class Status {

    /**
     * 当日 Flag 的统一落标状态：
     * - DONE: 任务已确认完成
     * - NO_MORE_ACTION_TODAY: 已确认今天无需再尝试（如已处理/无可执行项/时间窗已过）
     * - RETRY_LATER: 本次执行被中断或结论不确定，保留当天后续重试机会
     */
    enum class TodayFlagState {
        DONE,
        NO_MORE_ACTION_TODAY,
        RETRY_LATER
    }

    // =========================== forest
    var waterFriendLogList: MutableMap<String, Int> = HashMap()
    var wateredFriendLogList: MutableMap<String, Int> = HashMap() // 统计“被浇水”(好友->次数)
    var wateringFriendLogList: MutableMap<String, Int> = HashMap() // 统计“浇水”(好友->次数)
    var cooperateWaterList: MutableSet<String> = HashSet() // 合作浇水
    var reserveLogList: MutableMap<String, Int> = HashMap()
    var ancientTreeCityCodeList: MutableSet<String> = HashSet() // 古树
    var protectBubbleList: MutableSet<String> = HashSet()
    var doubleTimes: Int = 0
    var exchangeCollectHistoryAnimal7Days: Boolean = false
    var exchangeCollectToFriendTimes7Days: Boolean = false
    var youthPrivilege: Boolean = true
    var studentTask: Boolean = true
    var vitalityStoreList: MutableMap<String, Int> = HashMap() // 注意命名规范首字母小写

    // =========================== farm
    var answerQuestion: Boolean = false
    var feedFriendLogList: MutableMap<String, Int> = HashMap()
    var visitFriendLogList: MutableMap<String, Int> = HashMap()

    // 可以存各种今日计数（步数、次数等）
    // 2025/12/4 GSMT 用来存储int类型数据，无需再重复定义
    var intFlagMap: MutableMap<String, Int> = HashMap()

    var dailyAnswerList: MutableSet<String> = HashSet()
    var useAccelerateToolCount: Int = 0

    /** 小鸡换装 */
    var canOrnament: Boolean = true
    var animalSleep: Boolean = false

    // ============================= stall
    var stallHelpedCountLogList: MutableMap<String, Int> = HashMap()
    var spreadManureList: MutableSet<String> = HashSet()
    var stallP2PHelpedList: MutableSet<String> = HashSet()
    var canStallDonate: Boolean = true

    // ========================== sport
    var syncStepList: MutableSet<String> = HashSet()
    var exchangeList: MutableSet<String> = HashSet()

    /** 捐运动币 */
    var donateCharityCoin: Boolean = false

    // ======================= other
    var memberSignInList: MutableSet<String> = HashSet()

    /** 模块化标记与计数存储 (Key: 模块名, Value: Map<标记名, 次数>) */
    var moduleFlags: MutableMap<String, MutableMap<String, Int>> = HashMap()

    /** 口碑签到 */
    var kbSignIn: Long = 0

    /** 上次任务启动时间 */
    var lastTaskTime: Long = 0L

    /** 预定的下次任务执行时间 */
    var nextExecutionTime: Long = 0L

    /** 🚀 新增：上次高优任务（能量|小鸡任务）启动时间 */
    var lastCoreTaskTime: Long = 0L

    /** 持久化身份锚定：标记此内存状态属于哪个用户 */
    var currentUid: String? = null

    /** 保存时间：默认为创建时刻，确保新对象不会被判定为“跨天” */
    var saveTime: Long = System.currentTimeMillis()

    /** 是否正在运行主任务 (用于进程重启检测) */
    var isTaskRunning: Boolean = false

    /** 新村助力好友，已上限的用户 */
    var antStallAssistFriend: MutableSet<String> = HashSet()

    /** 新村-罚单已贴完的用户 */
    var canPasteTicketTime: MutableSet<String> = HashSet()

    /** 绿色经营，收取好友金币已完成用户 */
    var greenFinancePointFriend: MutableSet<String> = HashSet()

    /** 绿色经营，评级领奖已完成用户 */
    var greenFinancePrizesMap: MutableMap<String, Int> = HashMap()

    /** 农场助力 */
    var antOrchardAssistFriend: MutableSet<String> = HashSet()

    /** 会员权益 */
    var memberPointExchangeBenefitLogList: MutableSet<String> = HashSet()

    /**
     * 检查今日已完成状态（实例方法，供 ModelFieldTodayStateResolver 使用）
     */
    fun hasFlagTodayInstance(flag: String, retryLimit: Int = 0, retryTimes: String? = null): Boolean {
        val (module, name) = parseFlag(flag)
        val flags = moduleFlags[module] ?: return false
        val currentCount = flags[name] ?: return false
        if (retryLimit > 0 && currentCount < retryLimit) return false
        if (!retryTimes.isNullOrEmpty()) {
            val sdf = SimpleDateFormat("HHmm", Locale.getDefault())
            val nowTime = sdf.format(Date(System.currentTimeMillis()))
            val timeArray = retryTimes.split(",")
            for (t in timeArray) {
                val threshold = t.trim()
                if (nowTime >= threshold && !flags.containsKey("${name}_$threshold")) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * 获取今日整型标记（实例方法）
     */
    fun getIntFlagTodayInstance(flag: String): Int? {
        val (module, name) = parseFlag(flag)
        return moduleFlags[module]?.get(name)
    }

    companion object {
        private val TAG = Status::class.java.simpleName

        @JvmStatic
        val INSTANCE: Status = Status()

        private var lastModifiedTime: Long = 0L
        private var lastUid: String? = null
        private val offlineSkippedTodayFlags = ConcurrentHashMap.newKeySet<String>()

        @JvmStatic
        val currentDayTimestamp: Long
            get() {
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                return calendar.timeInMillis
            }

        private fun parseFlag(flag: String): Pair<String, String> {
            val index = flag.indexOf("::")
            return if (index > 0) {
                flag.substring(0, index) to flag.substring(index + 2)
            } else {
                "general" to flag
            }
        }

        /**
         * 🚀 核心新增：加载一个独立的状态实例而不影响 INSTANCE
         */
        @JvmStatic
        fun loadStandalone(userId: String): Status? {
            try {
                val statusFile = Files.getStatusFile(userId) ?: return null
                if (!statusFile.exists()) return null
                val json = Files.readFromFile(statusFile)
                if (json.isBlank()) return null
                val status = JsonUtil.parseObject(json, Status::class.java)
                return status
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load standalone status for $userId", e)
                return null
            }
        }

        @JvmStatic
        fun getVitalityCount(skuId: String): Int {
            return INSTANCE.vitalityStoreList[skuId] ?: 0
        }

        @JvmStatic
        fun canVitalityExchangeToday(skuId: String, count: Int): Boolean {
            return !hasFlagToday(StatusFlags.FLAG_ANTFOREST_VITALITY_EXCHANGE_LIMIT_PREFIX + skuId) && getVitalityCount(skuId) < count
        }

        @JvmStatic
        fun vitalityExchangeToday(skuId: String) {
            val count = getVitalityCount(skuId) + 1
            INSTANCE.vitalityStoreList[skuId] = count
            save()
        }

        @JvmStatic
        fun canAnimalSleep(): Boolean {
            return !INSTANCE.animalSleep
        }

        @JvmStatic
        fun animalSleep() {
            if (!INSTANCE.animalSleep) {
                INSTANCE.animalSleep = true
                save()
            }
        }

        @JvmStatic
        fun canWaterFriendToday(id: String, newCount: Int): Boolean {
            val key = "${UserMap.currentUid}-$id"
            val count = INSTANCE.waterFriendLogList[key] ?: return true
            return count < newCount
        }

        /**
         * 带 UID 保护的“浇水计数检查”。
         *
         * 用于规避：任务执行过程中切号导致 Status 标记写入到下一个账号的极少数情况。
         */
        @JvmStatic
        fun canWaterFriendToday(id: String, newCount: Int, taskUid: String?): Boolean {
            if (taskUid.isNullOrBlank()) return canWaterFriendToday(id, newCount)
            if (taskUid != UserMap.currentUid) return false
            val key = "$taskUid-$id"
            val count = INSTANCE.waterFriendLogList[key] ?: return true
            return count < newCount
        }

        @JvmStatic
        fun waterFriendToday(id: String, count: Int) {
            val key = "${UserMap.currentUid}-$id"
            INSTANCE.waterFriendLogList[key] = count
            save()
        }

        /**
         * 带 UID 保护的“浇水计数标记”。
         *
         * @param taskUid 任务启动时捕获的 UID（避免切号后写入到错误账号）
         */
        @JvmStatic
        fun waterFriendToday(id: String, count: Int, taskUid: String?) {
            if (taskUid.isNullOrBlank()) {
                waterFriendToday(id, count)
                return
            }
            if (taskUid != UserMap.currentUid) return
            val key = "$taskUid-$id"
            INSTANCE.waterFriendLogList[key] = count
            save()
        }

        /**
         * 记录“浇水”次数（给好友浇水 SUCCESS 时触发）。
         *
         * @param id 好友 UID
         * @param addTimes 本次新增次数（SUCCESS 次数）
         * @param taskUid 任务启动时捕获的 UID（避免切号后写入到错误账号）
         */
        @JvmStatic
        fun wateringFriendToday(id: String, addTimes: Int, taskUid: String?) {
            if (id.isBlank() || addTimes <= 0) return
            val uid = if (taskUid.isNullOrBlank()) UserMap.currentUid else taskUid
            if (uid.isNullOrBlank()) return
            if (!taskUid.isNullOrBlank() && taskUid != UserMap.currentUid) return

            val key = "$uid-$id"
            val count = INSTANCE.wateringFriendLogList[key] ?: 0
            INSTANCE.wateringFriendLogList[key] = count + addTimes
            save()
        }

        /**
         * 记录“被浇水”次数（收取浇水金球时触发）。
         *
         * @param id 给你浇水的好友 UID
         */
        @JvmStatic
        fun wateredFriendToday(id: String) {
            val uid = UserMap.currentUid
            if (uid.isNullOrBlank() || id.isBlank()) return
            val key = "$uid-$id"
            val count = INSTANCE.wateredFriendLogList[key] ?: 0
            INSTANCE.wateredFriendLogList[key] = count + 1
            save()
        }

        /**
         * 输出今日“被浇水”统计（明细 + 汇总），结果写入森林日志。
         */
        @JvmStatic
        fun getWateredFriendToday() {
            val uid = UserMap.currentUid
            if (uid.isNullOrBlank()) return

            val prefix = "$uid-"
            val entries = INSTANCE.wateredFriendLogList.entries.filter { it.key.startsWith(prefix) }

            var friendCount = 0
            var totalTimes = 0

            for ((key, times) in entries) {
                val friendId = key.removePrefix(prefix)
                val friendName = UserMap.get(friendId)?.showName ?: UserMap.getMaskName(friendId) ?: friendId
                val safeTimes = times.coerceAtLeast(0)
                Log.forest("统计被水🍯被[$friendName]浇水${safeTimes}次")
                friendCount += 1
                totalTimes += safeTimes
            }

            val selfName = UserMap.get(uid)?.showName ?: UserMap.getMaskName(uid) ?: uid
            Log.forest("统计被水🍯共计被${friendCount}个好友浇水${totalTimes}次#[$selfName]")
        }

        /**
         * 输出今日“浇水”统计（明细 + 汇总），结果写入森林日志。
         */
        @JvmStatic
        fun getWateringFriendToday() {
            val uid = UserMap.currentUid
            if (uid.isNullOrBlank()) return

            val prefix = "$uid-"
            val entries = INSTANCE.wateringFriendLogList.entries.filter { it.key.startsWith(prefix) }

            var friendCount = 0
            var totalTimes = 0

            for ((key, times) in entries) {
                val friendId = key.removePrefix(prefix)
                val friendName = UserMap.get(friendId)?.showName ?: UserMap.getMaskName(friendId) ?: friendId
                val safeTimes = times.coerceAtLeast(0)
                Log.forest("统计浇水🚿给[$friendName]浇水${safeTimes}次")
                friendCount += 1
                totalTimes += safeTimes
            }

            val selfName = UserMap.get(uid)?.showName ?: UserMap.getMaskName(uid) ?: uid
            Log.forest("统计浇水🚿共计给${friendCount}个好友浇水${totalTimes}次#[$selfName]")
        }

        @JvmStatic
        fun getReserveTimes(id: String): Int {
            return INSTANCE.reserveLogList[id] ?: 0
        }

        @JvmStatic
        fun canReserveToday(id: String, count: Int): Boolean {
            return getReserveTimes(id) < count
        }

        @JvmStatic
        fun reserveToday(id: String, newCount: Int) {
            val count = INSTANCE.reserveLogList[id] ?: 0
            INSTANCE.reserveLogList[id] = count + newCount
            save()
        }

        @JvmStatic
        fun canCooperateWaterToday(uid: String?, coopId: String): Boolean {
            return !INSTANCE.cooperateWaterList.contains("${uid}_$coopId")
        }

        @JvmStatic
        fun cooperateWaterToday(uid: String?, coopId: String?) {
            val v = "${uid}_$coopId"
            if (INSTANCE.cooperateWaterList.add(v)) {
                save()
            }
        }

        @JvmStatic
        fun canAncientTreeToday(cityCode: String): Boolean {
            return !INSTANCE.ancientTreeCityCodeList.contains(cityCode)
        }

        @JvmStatic
        fun ancientTreeToday(cityCode: String) {
            if (INSTANCE.ancientTreeCityCodeList.add(cityCode)) {
                save()
            }
        }

        @JvmStatic
        fun canAnswerQuestionToday(): Boolean {
            return !INSTANCE.answerQuestion
        }

        @JvmStatic
        fun answerQuestionToday() {
            if (!INSTANCE.answerQuestion) {
                INSTANCE.answerQuestion = true
                save()
            }
        }

        @JvmStatic
        fun canFeedFriendToday(id: String, newCount: Int): Boolean {
            val count = INSTANCE.feedFriendLogList[id] ?: return true
            return count < newCount
        }

        @JvmStatic
        fun feedFriendToday(id: String) {
            val count = INSTANCE.feedFriendLogList[id] ?: 0
            INSTANCE.feedFriendLogList[id] = count + 1
            save()
        }

        @JvmStatic
        fun canVisitFriendToday(id: String, newCount: Int): Boolean {
            val key = "${UserMap.currentUid}-$id"
            val count = INSTANCE.visitFriendLogList[key] ?: return true
            return count < newCount
        }

        @JvmStatic
        fun visitFriendToday(id: String, newCount: Int) {
            val key = "${UserMap.currentUid}-$id"
            INSTANCE.visitFriendLogList[key] = newCount
            save()
        }

        @JvmStatic
        fun canMemberSignInToday(uid: String?): Boolean {
            return !INSTANCE.memberSignInList.contains(uid)
        }

        @JvmStatic
        fun memberSignInToday(uid: String?) {
            if (uid != null) {
                if (INSTANCE.memberSignInList.add(uid)) {
                    save()
                }
            }
        }

        @JvmStatic
        fun canUseAccelerateTool(): Boolean {
            return INSTANCE.useAccelerateToolCount < 8
        }

        @JvmStatic
        fun useAccelerateTool() {
            INSTANCE.useAccelerateToolCount += 1
            save()
        }

        /**
         * 获取今日捐蛋总数
         */
        @JvmStatic
        fun getDailyDonationTotal(uid: String?): Int {
            if (uid.isNullOrEmpty()) return 0
            return getIntFlagToday(StatusFlags.FLAG_FARM_DONATION_COUNT + uid) ?: 0
        }

        /**
         * 更新今日捐蛋总数
         * @param incremental true: 累加原有数值, false: 强制覆盖为新数值(用于服务器同步)
         */
        @JvmStatic
        fun updateDailyDonationTotal(uid: String?, count: Int, incremental: Boolean = true) {
            if (uid.isNullOrEmpty()) return
            val finalCount = if (incremental) getDailyDonationTotal(uid) + count else count

            if (finalCount != getDailyDonationTotal(uid)) {
                setIntFlagToday(StatusFlags.FLAG_FARM_DONATION_COUNT + uid, finalCount)
            }
        }

        @JvmStatic
        fun canSpreadManureToday(uid: String): Boolean {
            return !INSTANCE.spreadManureList.contains(uid)
        }

        @JvmStatic
        fun spreadManureToday(uid: String) {
            if (INSTANCE.spreadManureList.add(uid)) {
                save()
            }
        }

        @JvmStatic
        fun canAntStallAssistFriendToday(): Boolean {
            return !INSTANCE.antStallAssistFriend.contains(UserMap.currentUid)
        }

        @JvmStatic
        fun antStallAssistFriendToday() {
            if (INSTANCE.antStallAssistFriend.add(UserMap.currentUid!!)) {
                save()
            }
        }

        @JvmStatic
        fun canAntOrchardAssistFriendToday(): Boolean {
            return !INSTANCE.antOrchardAssistFriend.contains(UserMap.currentUid)
        }

        @JvmStatic
        fun antOrchardAssistFriendToday() {
            if (INSTANCE.antOrchardAssistFriend.add(UserMap.currentUid!!)) {
                save()
            }
        }

        @JvmStatic
        fun canProtectBubbleToday(uid: String?): Boolean {
            return !INSTANCE.protectBubbleList.contains(uid)
        }

        @JvmStatic
        fun protectBubbleToday(uid: String?) {
            if (uid != null) {
                if (INSTANCE.protectBubbleList.add(uid)) {
                    save()
                }
            } else {
                Log.error("protectBubbleToday uid is null")
            }
        }

        @JvmStatic
        fun canPasteTicketTime(): Boolean {
            return !INSTANCE.canPasteTicketTime.contains(UserMap.currentUid)
        }

        @JvmStatic
        fun pasteTicketTime() {
            if (INSTANCE.canPasteTicketTime.add(UserMap.currentUid!!)) {
                save()
            }
        }

        @JvmStatic
        fun canDoubleToday(): Boolean {
            val task = Model.getModel(AntForest::class.java) ?: return false
            return INSTANCE.doubleTimes < (task.doubleCountLimit?.value ?: 0)
        }

        @JvmStatic
        fun doubleToday() {
            INSTANCE.doubleTimes += 1
            save()
        }

        @JvmStatic
        fun canKbSignInToday(): Boolean {
            return INSTANCE.kbSignIn < currentDayTimestamp
        }

        @JvmStatic
        fun KbSignInToday() {
            val todayZero = currentDayTimestamp
            if (INSTANCE.kbSignIn != todayZero) {
                INSTANCE.kbSignIn = todayZero
                save()
            }
        }

        @JvmStatic
        fun setDadaDailySet(dailyAnswerList: MutableSet<String>) {
            INSTANCE.dailyAnswerList = dailyAnswerList
            save()
        }

        @JvmStatic
        fun canDonateCharityCoin(): Boolean {
            return !INSTANCE.donateCharityCoin
        }

        @JvmStatic
        fun donateCharityCoin() {
            if (!INSTANCE.donateCharityCoin) {
                INSTANCE.donateCharityCoin = true
                save()
            }
        }

        @JvmStatic
        fun canExchangeToday(uid: String): Boolean {
            return !INSTANCE.exchangeList.contains(uid)
        }

        @JvmStatic
        fun exchangeToday(uid: String) {
            if (INSTANCE.exchangeList.add(uid)) {
                save()
            }
        }

        @JvmStatic
        fun canGreenFinancePointFriend(): Boolean {
            return INSTANCE.greenFinancePointFriend.contains(UserMap.currentUid)
        }

        @JvmStatic
        fun greenFinancePointFriend() {
            if (canGreenFinancePointFriend()) return
            INSTANCE.greenFinancePointFriend.add(UserMap.currentUid!!)
            save()
        }

        @JvmStatic
        fun canGreenFinancePrizesMap(): Boolean {
            val week = TimeUtil.getWeekNumber(Date())
            val currentUid = UserMap.currentUid
            if (INSTANCE.greenFinancePrizesMap.containsKey(currentUid)) {
                val storedWeek = INSTANCE.greenFinancePrizesMap[currentUid]
                return storedWeek == null || storedWeek != week
            }
            return true
        }

        @JvmStatic
        fun greenFinancePrizesMap() {
            if (!canGreenFinancePrizesMap()) return
            INSTANCE.greenFinancePrizesMap[UserMap.currentUid!!] = TimeUtil.getWeekNumber(Date())
            save()
        }

        @Synchronized
        @JvmStatic
        fun load(currentUid: String?): Status {
            return load(currentUid, true)
        }

        @Synchronized
        @JvmStatic
        fun load(currentUid: String?, showLog: Boolean): Status {
            if (currentUid.isNullOrEmpty()) {
                if (showLog) Log.record(TAG, "用户为空，状态加载失败")
                throw RuntimeException("用户为空，状态加载失败")
            }

            try {
                val statusFile = Files.getStatusFile(currentUid)
                if (statusFile!!.exists()) {
                    val fileTime = statusFile.lastModified()
                    val isUserChanged = currentUid != lastUid

                    // 缓存检查：如果 UID 和文件修改时间都没变，跳过 IO
                    if (!isUserChanged && fileTime != 0L && fileTime == lastModifiedTime) {
                        // 同一用户重复加载，不打印 record 日志
                    } else {
                        if (showLog) {
                            if (isUserChanged) Log.record(TAG, "加载用户[$currentUid]的 status.json")
                            else Log.record(TAG, "加载 status.json")
                        }

                        val json = Files.readFromFile(statusFile)
                        if (json.trim().isNotEmpty()) {
                            JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(json, Status::class.java)

                            val formatted = JsonUtil.formatJson(INSTANCE)
                            if (formatted != json) {
                                if (showLog) Log.record(TAG, "重新格式化 status.json")
                                Files.write2File(formatted, statusFile)
                            }
                            lastModifiedTime = statusFile.lastModified()
                            lastUid = currentUid
                            // 🚩 关键：修正加载后的身份
                            INSTANCE.currentUid = currentUid
                        } else {
                            if (showLog) Log.record(TAG, "配置文件为空，初始化默认配置")
                            initializeDefaultConfig(statusFile)
                        }
                    }
                } else {
                    if (showLog) Log.record(TAG, "配置文件不存在，初始化默认配置")
                    initializeDefaultConfig(statusFile)
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
                if (showLog) Log.record(TAG, "状态文件格式有误，已重置")
                resetAndSaveConfig()
            }

            // 无论加载还是初始化，确保身份锚定
            if (INSTANCE.currentUid == null) INSTANCE.currentUid = currentUid

            // 无论是否命中缓存，日期检查逻辑保持不变
            if (updateDay(Calendar.getInstance())) {
                if (showLog) Log.record(TAG, "发现日期更新，重置 status.json 状态")
                // 如果过期重置了，重新确保新日期的身份和时间有效
                INSTANCE.currentUid = currentUid
                INSTANCE.saveTime = System.currentTimeMillis()
            }

            if (INSTANCE.saveTime == 0L) {
                INSTANCE.saveTime = System.currentTimeMillis()
            }
            return INSTANCE
        }

        private fun initializeDefaultConfig(statusFile: java.io.File) {
            try {
                JsonUtil.copyMapper().updateValue(INSTANCE, Status())
                Log.record(TAG, "初始化 status.json")
                INSTANCE.currentUid = UserMap.currentUid
                INSTANCE.saveTime = System.currentTimeMillis()
                Files.write2File(JsonUtil.formatJson(INSTANCE), statusFile)
                // 同步缓存标记
                lastModifiedTime = statusFile.lastModified()
                lastUid = UserMap.currentUid
            } catch (e: JsonMappingException) {
                Log.printStackTrace(TAG, e)
                throw RuntimeException("初始化配置失败", e)
            }
        }

        private fun resetAndSaveConfig() {
            try {
                JsonUtil.copyMapper().updateValue(INSTANCE, Status())
                val statusFile = Files.getStatusFile(UserMap.currentUid)!!
                INSTANCE.currentUid = UserMap.currentUid
                INSTANCE.saveTime = System.currentTimeMillis()
                Files.write2File(JsonUtil.formatJson(INSTANCE), statusFile)
                // 同步缓存标记
                lastModifiedTime = statusFile.lastModified()
                lastUid = UserMap.currentUid
            } catch (e: JsonMappingException) {
                Log.printStackTrace(TAG, e)
                throw RuntimeException("重置配置失败", e)
            }
        }

        @Synchronized
        @JvmStatic
        fun unload() {
            try {
                // 创建新状态实例并确保清空所有每日标记
                val newStatus = Status()
                // 确保清空所有标记和计数
                INSTANCE.moduleFlags.clear()
                JsonUtil.copyMapper().updateValue(INSTANCE, newStatus)
                // 清除缓存标记，强制下次加载重新读取
                lastModifiedTime = 0L
                lastUid = null
            } catch (e: JsonMappingException) {
                Log.printStackTrace(TAG, e)
            }
        }

        @Synchronized
        @JvmStatic
        fun save(nowCalendar: Calendar = Calendar.getInstance()) {
            val targetUid = UserMap.currentUid
            if (targetUid.isNullOrEmpty()) {
                Log.record(TAG, "用户为空，状态保存失败")
                throw RuntimeException("用户为空，状态加载失败")
            }

            // 🚩 核心防御：如果内存里的 UID 与当前全局 UID 不符，说明处于切换中，严禁写入
            if (INSTANCE.currentUid != null && INSTANCE.currentUid != targetUid) {
                Log.record(TAG, "拒绝跨账号保存: Memory(${INSTANCE.currentUid}) -> Disk($targetUid)")
                return
            }

            if (updateDay(nowCalendar)) {
                Log.record(TAG, "重置 status.json")
            } else {
                Log.record(TAG, "保存 status.json")
            }
            val lastSaveTime = INSTANCE.saveTime
            try {
                INSTANCE.currentUid = targetUid
                INSTANCE.saveTime = System.currentTimeMillis()
                val statusFile = Files.getStatusFile(targetUid)!!
                Files.write2File(JsonUtil.formatJson(INSTANCE), statusFile)
                // 关键：更新标记，防止下一次 load 触发冗余读取
                lastModifiedTime = statusFile.lastModified()
                lastUid = targetUid
            } catch (e: Exception) {
                INSTANCE.saveTime = lastSaveTime
                throw e
            }
        }

        @JvmStatic
        fun updateDay(nowCalendar: Calendar): Boolean {
            if (TimeUtil.isLessThanSecondOfDays(INSTANCE.saveTime, nowCalendar.timeInMillis)) {
                unload()
                return true
            }
            return false
        }

        @JvmStatic
        fun canOrnamentToday(): Boolean {
            return INSTANCE.canOrnament
        }

        @JvmStatic
        fun setOrnamentToday() {
            if (INSTANCE.canOrnament) {
                INSTANCE.canOrnament = false
                save()
            }
        }

        @JvmStatic
        fun canStallDonateToday(): Boolean {
            return INSTANCE.canStallDonate
        }

        @JvmStatic
        fun setStallDonateToday() {
            if (INSTANCE.canStallDonate) {
                INSTANCE.canStallDonate = false
                save()
            }
        }

        /**
         * ## 检查今日已运行状态
         * @param flag 标记名，支持 "Module::Task"格式
         * @param retryLimit 允许运行的最大次数。0 表示不限制次数（只要 flag 存在即拦截）
         * @param retryTimes 重试时间点，格式 "0800,1200"。到达时间点后允许再次运行。
         * @return true 表示“已完成/需拦截”，false 表示“未完成/需运行”
         */
        @JvmStatic
        @JvmOverloads
        fun hasFlagToday(flag: String, retryLimit: Int = 0, retryTimes: String? = null): Boolean {
            return INSTANCE.hasFlagTodayInstance(flag, retryLimit, retryTimes)
        }

        @JvmStatic
        fun setFlagToday(flag: String, state: TodayFlagState) {
            if (state == TodayFlagState.RETRY_LATER) {
                return
            }
            setFlagToday(flag)
        }

        /**
         * ## 完善的标记设置逻辑 (单次或分时段)
         * 1. 自动过滤重复调用，仅在状态变更时保存，降低 I/O 损耗。
         * 2. 支持分时段逻辑，确保每个时间点仅触发一次更新。
         */
        @JvmStatic
        @JvmOverloads
        fun setFlagToday(flag: String, retryTimes: String? = null) {
            if (ApplicationHookConstants.isOffline()) {
                if (offlineSkippedTodayFlags.add(flag)) {
                    Log.record(TAG, "离线模式跳过今日标识: $flag")
                }
                return
            }

            val (module, name) = parseFlag(flag)
            val flags = INSTANCE.moduleFlags.getOrPut(module) { HashMap() }
            val oldCount = flags[name] ?: 0

            var changed = false
            val nowTime = SimpleDateFormat("HHmm", Locale.getDefault()).format(Date())

            if (oldCount == 0) {
                flags[name] = 1
                changed = true
            }

            if (!retryTimes.isNullOrEmpty()) {
                retryTimes.split(",").forEach {
                    val threshold = it.trim()
                    if (nowTime >= threshold) {
                        val slotKey = "${name}_$threshold"
                        if (flags[slotKey] != 1) {
                            flags[slotKey] = 1
                            changed = true
                            // 此时增加总计数，代表进入了新阶段
                            flags[name] = (flags[name] ?: 0) + 1
                        }
                    }
                }
            }

            if (changed) {
                save()
            }
        }

        /**
         * 🚀 完善的 RPC 结果自动补标记工具
         * 适配光盘打卡、健康岛签到等“远程已完成”场景。
         * @param jo RPC 返回的 JSONObject
         * @param extraDoneCodes 额外的“视为完成”的 resultCode 或 errorCode
         */
        @JvmStatic
        fun setFlagIfDone(flag: String, jo: JSONObject?, vararg extraDoneCodes: String) {
            if (jo == null) return
            val resultCode = jo.optString("resultCode")
            val errorCode = jo.optString("errorCode")
            val success = jo.optBoolean("success") || jo.optBoolean("isSuccess") ||
                    "SUCCESS" == jo.optString("memo")

            val isAlreadyDone = resultCode == "ACTION_ALREADY_TICKED" ||
                    resultCode == "ALREADY_SIGN_IN" ||
                    errorCode == "ALREADY_SIGN_IN" ||
                    extraDoneCodes.any { it == resultCode || it == errorCode }

            if (success || isAlreadyDone) {
                setFlagToday(flag)
            }
        }

        /**
         * 清除今日标记
         * 支持 "Module::Task" 格式
         */
        @JvmStatic
        fun removeFlag(flag: String) {
            var changed = false
            val (module, name) = parseFlag(flag)

            // 1. 从 moduleFlags 中移除
            INSTANCE.moduleFlags[module]?.let {
                if (it.remove(name) != null) {
                    changed = true
                }
                // 同时移除带时间点的子标记（如 Task_0800）
                val keysToRemove = it.keys.filter { key -> key.startsWith("${name}_") }
                if (keysToRemove.isNotEmpty()) {
                    keysToRemove.forEach { k -> it.remove(k) }
                    changed = true
                }
            }

            if (changed) {
                save()
            }
        }

        @JvmStatic
        fun getIntFlagToday(flag: String): Int? {
            return INSTANCE.getIntFlagTodayInstance(flag)
        }

        @JvmStatic
        fun setIntFlagToday(flag: String, value: Int) {
            val (module, name) = parseFlag(flag)
            val flags = INSTANCE.moduleFlags.getOrPut(module) { HashMap() }

            // 仅当数值确实不同时才保存，避免冗余 I/O
            if (flags[name] != value) {
                flags[name] = value
                save()
            }
        }

        @JvmStatic
        fun canMemberPointExchangeBenefitToday(benefitId: String): Boolean {
            return !INSTANCE.memberPointExchangeBenefitLogList.contains(benefitId)
        }

        @JvmStatic
        fun memberPointExchangeBenefitToday(benefitId: String) {
            if (canMemberPointExchangeBenefitToday(benefitId)) {
                INSTANCE.memberPointExchangeBenefitLogList.add(benefitId)
                save()
            }
        }

        /**
         * 乐园商城-是否可以兑换该商品
         *
         * @param spuId 商品spuId
         * @return true 可以兑换 false 兑换达到上限
         */
        @JvmStatic
        fun canParadiseCoinExchangeBenefitToday(spuId: String): Boolean {
            return !hasFlagToday(StatusFlags.FLAG_FARM_PARADISE_COIN_EXCHANGE_LIMIT_PREFIX + spuId)
        }

        @JvmStatic
        fun getFlagModuleNames(): List<String> {
            return INSTANCE.moduleFlags.keys.toList()
        }

        @JvmStatic
        fun getFlagsByModule(module: String): List<String> {
            return INSTANCE.moduleFlags[module]?.keys?.filter { !it.contains("_") }?.toList() ?: emptyList()
        }

        @JvmStatic
        fun clearModuleFlags(module: String) {
            if (INSTANCE.moduleFlags.remove(module) != null) {
                save()
            }
        }
    }
}

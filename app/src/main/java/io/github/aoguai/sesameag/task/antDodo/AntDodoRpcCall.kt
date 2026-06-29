package io.github.aoguai.sesameag.task.antDodo

import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.util.RandomUtil
import org.json.JSONObject

/**
 * 神奇物种RPC调用
 */
object AntDodoRpcCall {

    private const val METHOD_QUERY_ANIMAL_STATUS = "alipay.antdodo.rpc.h5.queryAnimalStatus"
    private const val METHOD_HOME_PAGE = "alipay.antdodo.rpc.h5.homePage"
    private const val METHOD_COLLECT = "alipay.antdodo.rpc.h5.collect"
    private const val METHOD_TASK_ENTRANCE = "alipay.antdodo.rpc.h5.taskEntrance"
    private const val METHOD_TASK_LIST = "alipay.antdodo.rpc.h5.taskList"
    private const val METHOD_PROP_LIST = "alipay.antdodo.rpc.h5.propList"
    private const val METHOD_CONSUME_PROP = "alipay.antdodo.rpc.h5.consumeProp"
    private const val METHOD_QUERY_BOOK_INFO = "alipay.antdodo.rpc.h5.queryBookInfo"
    private const val METHOD_QUERY_MY_COLLECTION = "alipay.antdodo.rpc.h5.queryMyCollection"
    private const val METHOD_SOCIAL = "alipay.antdodo.rpc.h5.social"
    private const val METHOD_EXCHANGE = "alipay.antdodo.rpc.h5.exchange"
    private const val METHOD_QUERY_FRIEND = "alipay.antdodo.rpc.h5.queryFriend"
    private const val METHOD_QUERY_BOOK_LIST = "alipay.antdodo.rpc.h5.queryBookList"
    private const val METHOD_GENERATE_BOOK_MEDAL = "alipay.antdodo.rpc.h5.generateBookMedal"
    private const val METHOD_REDECISION_TASK_OPEN_GREEN = "com.alipay.antieptask.reDecisionTaskopengreen"

    /**
     * 查询动物状态
     */
    @JvmStatic
    fun queryAnimalStatus(): String {
        return RequestManager.requestString(
            METHOD_QUERY_ANIMAL_STATUS,
            "[{\"source\":\"wuzhong_leyuan\",\"version\":\"20241203\"}]"
        )
    }

    /**
     * 神奇物种首页
     */
    @JvmStatic
    fun homePage(): String {
        return RequestManager.requestString(METHOD_HOME_PAGE, "[{}]")
    }

    /**
     * 任务入口
     */
    @JvmStatic
    fun taskEntrance(): String {
        return RequestManager.requestString(
            METHOD_TASK_ENTRANCE,
            "[{\"statusList\":[\"TODO\",\"FINISHED\"]}]"
        )
    }

    /**
     * 收集
     */
    @JvmStatic
    fun collect(): String {
        return RequestManager.requestString(METHOD_COLLECT, "[{}]")
    }

    /**
     * 任务列表
     */
    @JvmStatic
    fun taskList(): String {
        return RequestManager.requestString(METHOD_TASK_LIST, "[{\"version\":\"20241203\"}]")
    }

    /**
     * 完成任务
     *
     * @param sceneCode 场景代码
     * @param taskType 任务类型
     */
    @JvmStatic
    @JvmOverloads
    fun finishTask(sceneCode: String?, taskType: String?, outBizNo: String? = null): String {
        val uniqueId = outBizNo ?: getUniqueId()
        val response = RequestManager.requestString(
            "com.alipay.antiep.finishTask",
            ("[{\"outBizNo\":\"" + uniqueId + "\",\"requestType\":\"rpc\",\"sceneCode\":\""
                    + sceneCode + "\",\"source\":\"af-biodiversity\",\"taskType\":\""
                    + taskType + "\",\"uniqueId\":\"" + uniqueId + "\"}]")
        )
        return response
    }

    @JvmStatic
    fun clickGame(appId: String?): String {
        return RequestManager.requestString(
            "com.alipay.charitygamecenter.clickGame",
            ("[{\"appId\":\"" + appId + "\",\"bizType\":\"ANTFOREST\",\"requestType\":\"RPC\",\"sceneCode\":\"ANTFOREST\",\"source\":\"ANTFOREST\"}]")
        )
    }

    /**
     * 生成唯一ID
     */
    private fun getUniqueId(): String {
        return "${System.currentTimeMillis()}${RandomUtil.nextLong()}"
    }

    /**
     * 领取任务奖励
     *
     * @param sceneCode 场景代码
     * @param taskType 任务类型
     */
    @JvmStatic
    fun receiveTaskAward(sceneCode: String, taskType: String): String {
        val response = RequestManager.requestString(
            "com.alipay.antiep.receiveTaskAward",
            "[{\"ignoreLimit\":0,\"requestType\":\"rpc\",\"sceneCode\":\"$sceneCode\",\"source\":\"af-biodiversity\",\"taskType\":\"$taskType\"}]"
        )
        return response
    }

    /**
     * 道具列表
     */
    @JvmStatic
    fun propList(): String {
        return RequestManager.requestString(METHOD_PROP_LIST, "[{}]")
    }

    /**
     * 使用道具
     *
     * @param propId 道具ID
     * @param propType 道具类型
     */
    @JvmStatic
    fun consumeProp(propId: String, propType: String, animalId: String? = null): String {
        val args = JSONObject()
        args.put("propId", propId)
        args.put("propType", propType)
        if (!animalId.isNullOrBlank()) {
            args.put("extendInfo", JSONObject().put("animalId", animalId))
        }
        val response = RequestManager.requestString(
            METHOD_CONSUME_PROP,
            "[$args]"
        )
        return response
    }

    /**
     * 查询图鉴信息
     *
     * @param bookId 图鉴ID
     */
    @JvmStatic
    fun queryBookInfo(bookId: String): String {
        return RequestManager.requestString(
            METHOD_QUERY_BOOK_INFO,
            "[{\"bookId\":\"$bookId\"}]"
        )
    }

    /**
     * 查询我的卡片收藏
     *
     * @param extendAnimalId 目标动物ID
     * @param pageSize 每页数量
     * @param pageStart 起始页
     */
    @JvmStatic
    fun queryMyCollection(extendAnimalId: String, pageSize: Int, pageStart: Any? = 0): String {
        val args = JSONObject()
        args.put("extendAnimalId", extendAnimalId)
        args.put("pageSize", pageSize)
        args.put("pageStart", pageStart ?: 0)
        return RequestManager.requestString(METHOD_QUERY_MY_COLLECTION, "[$args]")
    }

    /**
     * 送卡片给好友
     *
     * @param targetAnimalId 目标动物ID
     * @param targetUserId 目标用户ID
     */
    @JvmStatic
    fun social(targetAnimalId: String, targetUserId: String): String {
        val response = RequestManager.requestString(
            METHOD_SOCIAL,
            "[{\"actionCode\":\"GIFT_TO_FRIEND\",\"source\":\"GIFT_TO_FRIEND_FROM_CC\",\"targetAnimalId\":\"$targetAnimalId\",\"targetUserId\":\"$targetUserId\",\"triggerTime\":\"${System.currentTimeMillis()}\"}]"
        )
        return response
    }

    /**
     * 发送换卡提醒
     *
     * @param targetAnimalId 目标动物ID
     * @param targetUserId 目标用户ID
     */
    @JvmStatic
    fun socialExchangeMsg(targetAnimalId: String, targetUserId: String): String {
        val args = JSONObject()
        args.put("actionCode", "EXCHANGE_MSG")
        args.put("targetAnimalId", targetAnimalId)
        args.put("targetUserId", targetUserId)
        return RequestManager.requestString(METHOD_SOCIAL, "[$args]")
    }

    /**
     * 放弃换卡
     *
     * @param targetUserId 目标用户ID
     */
    @JvmStatic
    fun socialGiveUpExchange(targetUserId: String): String {
        val args = JSONObject()
        args.put("actionCode", "GIVE_UP_EXCHANGE")
        args.put("targetUserId", targetUserId)
        return RequestManager.requestString(METHOD_SOCIAL, "[$args]")
    }

    /**
     * 查询好友
     */
    @JvmStatic
    fun queryFriend(): String {
        return RequestManager.requestString(
            METHOD_QUERY_FRIEND,
            "[{\"sceneCode\":\"EXCHANGE\"}]"
        )
    }

    /**
     * 收集（好友）
     *
     * @param targetUserId 目标用户ID
     */
    @JvmStatic
    fun collect(targetUserId: String): String {
        val args = JSONObject()
        args.put("targetUserId", targetUserId)
        val response = RequestManager.requestString(
            METHOD_COLLECT,
            "[$args]"
        )
        return response
    }

    /**
     * 交换卡片
     *
     * @param animalId 我方筹码卡ID
     * @param targetAnimalId 目标卡ID
     * @param targetUserId 目标用户ID
     */
    @JvmStatic
    fun exchange(animalId: String, targetAnimalId: String, targetUserId: String): String {
        val args = JSONObject()
        args.put("animalId", animalId)
        args.put("targetAnimalId", targetAnimalId)
        args.put("targetUserId", targetUserId)
        return RequestManager.requestString(METHOD_EXCHANGE, "[$args]")
    }

    /**
     * 查询图鉴列表
     *
     * @param pageSize 每页数量
     * @param pageStart 起始页
     */
    @JvmStatic
    fun queryBookList(pageSize: Int, pageStart: Int): String {
        val args = "[{\"pageSize\":$pageSize,\"pageStart\":\"$pageStart\",\"v2\":\"true\"}]"
        return RequestManager.requestString(METHOD_QUERY_BOOK_LIST, args)
    }

    /**
     * 生成图鉴勋章
     *
     * @param bookId 图鉴ID
     */
    @JvmStatic
    fun generateBookMedal(bookId: String): String {
        val args = "[{\"bookId\":\"$bookId\"}]"
        return RequestManager.requestString(METHOD_GENERATE_BOOK_MEDAL, args)
    }

    /**
     * 显化 reDecision 任务
     *
     * @param groupId 任务分组ID
     * @param taskType 任务类型
     */
    @JvmStatic
    fun reDecisionTaskOpenGreen(groupId: String, taskType: String): String {
        val args = JSONObject()
        args.put("extend", JSONObject().put("h5Version", "20241203").put("osType", "android"))
        args.put("reDecisionGroupId", groupId)
        args.put("reDecisionMode", "MANUAL")
        args.put("reDecisionTaskType", taskType)
        args.put("requestType", "rpc")
        args.put("sceneCode", "ANTDODO_TASK")
        args.put("source", "af-biodiversity")
        return RequestManager.requestString(METHOD_REDECISION_TASK_OPEN_GREEN, "[$args]")
    }
}


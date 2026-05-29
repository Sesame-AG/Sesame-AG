package io.github.aoguai.sesameag.task.antStall

import io.github.aoguai.sesameag.hook.RequestManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * @class AntStallRpcCall
 * @brief 蚂蚁小铺 (Ant Stall) RPC 调用类
 * @details 处理蚂蚁小铺相关的网络请求，包括店铺管理、任务、好友互动等
 * @author
 * @since 2023/08/22
 */
object AntStallRpcCall {

    /** 接口版本号 */
    private const val VERSION = "0.1.2601161444.47"
    private const val METHOD_TASK_LIST = "com.alipay.antstall.task.list"
    private const val METHOD_SIGN_TODAY = "com.alipay.antstall.sign.today"
    private const val METHOD_FINISH_TASK = "com.alipay.antiep.finishTask"
    private const val METHOD_RECEIVE_TASK_AWARD = "com.alipay.antiep.receiveTaskAward"
    private const val METHOD_TASK_FINISH = "com.alipay.antstall.task.finish"
    private const val METHOD_TASK_AWARD = "com.alipay.antstall.task.award"

    /**
     * @brief 获取个人主页数据
     * @return 响应字符串
     */
    fun home(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.self.home",
            "[{\"arouseAppParams\":{},\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 结算收益
     * @param assetId 资产ID
     * @param settleCoin 结算金币数量
     * @return 响应字符串
     */
    fun settle(assetId: String, settleCoin: Int): String {
        return RequestManager.requestString(
            "com.alipay.antstall.self.settle",
            "[{\"assetId\":\"$assetId\",\"coinType\":\"MASTER\",\"settleCoin\":$settleCoin,\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取商店列表
     * @return 响应字符串
     */
    fun shopList(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.shop.list",
            "[{\"freeTop\":false,\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 一键收摊前的预检查
     * @return 响应字符串
     */
    fun preOneKeyClose(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.shop.close.preOneKey",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 一键收摊
     * @return 响应字符串
     */
    fun oneKeyClose(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.shop.oneKeyClose",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 收摊前的预检查
     * @param shopId 商店ID
     * @param billNo 账单编号
     * @return 响应字符串
     */
    fun preShopClose(shopId: String, billNo: String): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.shop.close.pre",
            "[{\"billNo\":\"$billNo\",\"shopId\":\"$shopId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 收摊
     * @param shopId 商店ID
     * @return 响应字符串
     */
    fun shopClose(shopId: String): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.shop.close",
            "[{\"shopId\":\"$shopId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 一键开店
     * @return 响应字符串
     */
    fun oneKeyOpen(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.shop.oneKeyOpen",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 在好友位开店
     * @param friendSeatId 好友位置ID
     * @param friendUserId 好友用户ID
     * @param shopId 商店ID
     * @return 响应字符串
     */
    fun shopOpen(friendSeatId: String, friendUserId: String, shopId: String): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.shop.open",
            "[{\"friendSeatId\":\"$friendSeatId\",\"friendUserId\":\"$friendUserId\",\"shopId\":\"$shopId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 捐赠排名金币
     * @return 响应字符串
     */
    fun rankCoinDonate(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.rank.coin.donate",
            "[{\"source\":\"ANTFARM\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 进入好友的小铺首页
     * @param userId 好友用户ID
     * @return 响应字符串
     */
    fun friendHome(userId: String): String {
        return RequestManager.requestString(
            "com.alipay.antstall.friend.home",
            "[{\"arouseAppParams\":{},\"friendUserId\":\"$userId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取任务列表
     * @return 响应字符串
     */
    fun taskList(): String {
        return RequestManager.requestString(
            METHOD_TASK_LIST,
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 今日签到
     * @return 响应字符串
     */
    fun signToday(): String {
        val response = RequestManager.requestString(
            METHOD_SIGN_TODAY,
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
        return response
    }

    /**
     * @brief 完成通用任务
     * @param outBizNo 外部业务编号
     * @param taskType 任务类型
     * @return 响应字符串
     */
    fun finishTask(outBizNo: String, taskType: String): String {
        val response = RequestManager.requestString(
            METHOD_FINISH_TASK,
            "[{\"outBizNo\":\"$outBizNo\",\"requestType\":\"RPC\",\"sceneCode\":\"ANTSTALL_TASK\",\"source\":\"AST\",\"systemType\":\"android\",\"taskType\":\"$taskType\",\"version\":\"$VERSION\"}]"
        )
        return response
    }

    /**
     * @brief 调用广告/插件接口
     * @return 响应字符串
     */
    fun xlightPlugin(): String {
        return RequestManager.requestString(
            "com.alipay.adexchange.ad.facade.xlightPlugin",
            "[{\"positionRequest\":{\"extMap\":{\"xlightPlayInstanceId\":\"300004\"},\"referInfo\":{},\"spaceCode\":\"ANT_FARM_NEW_VILLAGE\"},\"sdkPageInfo\":{\"adComponentType\":\"FEEDS\",\"adComponentVersion\":\"4.11.13\",\"enableFusion\":true,\"networkType\":\"WIFI\",\"pageFrom\":\"ch_url-https://68687809.h5app.alipay.com/www/game.html\",\"pageNo\":1,\"pageUrl\":\"https://render.alipay.com/p/yuyan/180020010001256918/multi-stage-task.html?caprMode=sync&spaceCodeFeeds=ANT_FARM_NEW_VILLAGE&usePlayLink=true&xlightPlayInstanceId=300004\",\"session\":\"u_54b721d9fffd6_1904b8eba8f\",\"unionAppId\":\"2060090000304921\",\"usePlayLink\":\"true\",\"xlightSDKType\":\"h5\",\"xlightSDKVersion\":\"4.11.13\"}}]"
        )
    }

    /**
     * @brief 结束特定业务
     * @param playBizId 播放业务ID
     * @param jsonObject 事件信息
     * @return 响应字符串
     */
    fun finish(playBizId: String, jsonObject: JSONObject): String {
        return RequestManager.requestString(
            "com.alipay.adtask.biz.mobilegw.service.interaction.finish",
            "[{\"extendInfo\":{\"iepTaskSceneCode\":\"ANTSTALL_TASK\",\"iepTaskType\":\"ANTSTALL_XLIGHT_VARIABLE_AWARD\"},\"playBizId\":\"$playBizId\",\"playEventInfo\":$jsonObject,\"source\":\"adx\" }]"
        )
    }

    /**
     * @brief 查询应用跳转 Schema
     * @param sceneCode 场景代码
     * @return 响应字符串
     */
    fun queryCallAppSchema(sceneCode: String): String {
        return RequestManager.requestString(
            "alipay.antmember.callApp.queryCallAppSchema",
            "[{\"sceneCode\":\"$sceneCode\" }]"
        )
    }

    /**
     * @brief 领取任务奖励 (IEP 接口)
     * @param taskType 任务类型
     * @return 响应字符串
     */
    fun receiveTaskAward(taskType: String): String {
        val response = RequestManager.requestString(
            METHOD_RECEIVE_TASK_AWARD,
            "[{\"ignoreLimit\":true,\"requestType\":\"RPC\",\"sceneCode\":\"ANTSTALL_TASK\",\"source\":\"AST\",\"systemType\":\"android\",\"taskType\":\"$taskType\",\"version\":\"$VERSION\"}]"
        )
        return response
    }

    /**
     * @brief 完成小铺任务
     * @param taskType 任务类型
     * @return 响应字符串
     */
    fun taskFinish(taskType: String): String {
        val response = RequestManager.requestString(
            METHOD_TASK_FINISH,
            "[{\"source\":\"search\",\"systemType\":\"android\",\"taskType\":\"$taskType\",\"version\":\"$VERSION\"}]"
        )
        return response
    }

    /**
     * @brief 领取小铺任务奖励
     * @param amount 奖励数量
     * @param prizeId 奖品ID
     * @param taskType 任务类型
     * @return 响应字符串
     */
    fun taskAward(amount: String, prizeId: String, taskType: String): String {
        val response = RequestManager.requestString(
            METHOD_TASK_AWARD,
            "[{\"amount\":$amount,\"prizeId\":\"$prizeId\",\"source\":\"search\",\"systemType\":\"android\",\"taskType\":\"$taskType\",\"version\":\"$VERSION\"}]"
        )
        return response
    }

    /**
     * @brief 获取任务权益
     * @return 响应字符串
     */
    fun taskBenefit(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.task.benefit",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 收集肥料
     * @return 响应字符串
     */
    fun collectManure(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.manure.collectManure",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 查询肥料信息
     * @return 响应字符串
     */
    fun queryManureInfo(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.manure.queryManureInfo",
            "[{\"queryManureType\":\"ANTSTALL\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取项目列表
     * @return 响应字符串
     */
    fun projectList(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.project.list",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取项目详情
     * @param projectId 项目ID
     * @return 响应字符串
     */
    fun projectDetail(projectId: String): String {
        return RequestManager.requestString(
            "com.alipay.antstall.project.detail",
            "[{\"projectId\":\"$projectId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 捐赠项目
     * @param projectId 项目ID
     * @return 响应字符串
     */
    fun projectDonate(projectId: String): String {
        return RequestManager.requestString(
            "com.alipay.antstall.project.donate",
            "[{\"bizNo\":\"${UUID.randomUUID()}\",\"projectId\":\"$projectId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取路线图
     * @return 响应字符串
     */
    fun roadmap(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.village.roadmap",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 进入下一个村庄
     * @return 响应字符串
     */
    fun nextVillage(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.ast.next.village",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 注册排行榜邀请
     * @return 响应字符串
     */
    fun rankInviteRegister(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.rank.invite.register",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 注册好友邀请
     * @param friendUserId 好友用户ID
     * @return 响应字符串
     */
    fun friendInviteRegister(friendUserId: String): String {
        return RequestManager.requestString(
            "com.alipay.antstall.friend.invite.register",
            "[{\"friendUserId\":\"$friendUserId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 分享助力 (P2P)
     * @return 响应字符串
     */
    fun shareP2P(): String {
        return RequestManager.requestString(
            "com.alipay.antiep.shareP2P",
            "[{\"requestType\":\"RPC\",\"sceneCode\":\"ANTSTALL_P2P_SHARER\",\"source\":\"ANTSTALL\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 领取被分享的助力奖励
     * @param shareId 分享ID
     * @return 响应字符串
     */
    fun achieveBeShareP2P(shareId: String): String {
        return RequestManager.requestString(
            "com.alipay.antiep.achieveBeShareP2P",
            "[{\"requestType\":\"RPC\",\"sceneCode\":\"ANTSTALL_P2P_SHARER\",\"shareId\":\"$shareId\",\"source\":\"ANTSTALL\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 遣返好友店铺前的预检查
     * @param billNo 账单号
     * @param seatId 位置ID
     * @param shopId 商店ID
     * @param shopUserId 店主用户ID
     * @return 响应字符串
     */
    fun shopSendBackPre(
        billNo: String,
        seatId: String,
        shopId: String,
        shopUserId: String
    ): String {
        return RequestManager.requestString(
            "com.alipay.antstall.friend.shop.sendback.pre",
            "[{\"billNo\":\"$billNo\",\"seatId\":\"$seatId\",\"shopId\":\"$shopId\",\"shopUserId\":\"$shopUserId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 遣返好友店铺
     * @param seatId 位置ID
     * @return 响应字符串
     */
    fun shopSendBack(seatId: String): String {
        return RequestManager.requestString(
            "com.alipay.antstall.friend.shop.sendback",
            "[{\"seatId\":\"$seatId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 打开排行榜邀请
     * @return 响应字符串
     */
    fun rankInviteOpen(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.rank.invite.open",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 一键邀请好友开店
     * @param friendUserId 好友用户ID
     * @param mySeatId 我的位置ID
     * @return 响应字符串
     */
    fun oneKeyInviteOpenShop(friendUserId: String, mySeatId: String): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.shop.oneKeyInviteOpenShop",
            "[{\"friendUserId\":\"$friendUserId\",\"mySeatId\":\"$mySeatId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取动态损失（如被贴罚单记录）
     * @return 响应字符串
     */
    fun dynamicLoss(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.dynamic.loss",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 扔肥料（复仇等）
     * @param dynamicList 动态列表JSONArray
     * @return 响应字符串
     */
    fun throwManure(dynamicList: JSONArray): String {
        return RequestManager.requestString(
            "com.alipay.antstall.manure.throwManure",
            "[{\"dynamicList\":$dynamicList,\"sendMsg\":false,\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 结算待收收益
     * @return 响应字符串
     */
    fun settleReceivable(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.self.settle.receivable",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 查找下一个可以贴罚单的好友
     * @return 响应字符串
     */
    fun nextTicketFriend(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.friend.nextTicketFriend",
            "[{\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 给好友贴罚单
     * @param billNo 账单编号
     * @param seatId 位置ID
     * @param shopId 商店ID
     * @param shopUserId 商店所属用户ID
     * @param seatUserId 位置所属用户ID
     * @return 响应字符串
     */
    fun ticket(
        billNo: String,
        seatId: String,
        shopId: String,
        shopUserId: String,
        seatUserId: String
    ): String {
        return RequestManager.requestString(
            "com.alipay.antstall.friend.paste.ticket",
            "[{\"billNo\":\"$billNo\",\"seatId\":\"$seatId\",\"shopId\":\"$shopId\",\"shopUserId\":\"$shopUserId\",\"seatUserId\": \"$seatUserId\",\"source\":\"search\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }
}

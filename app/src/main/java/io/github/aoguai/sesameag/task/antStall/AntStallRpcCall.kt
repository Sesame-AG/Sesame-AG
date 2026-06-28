package io.github.aoguai.sesameag.task.antStall

import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.util.RandomUtil
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
    private const val VERSION = "0.1.2606011409.44"
    private const val BASE_SOURCE = "ch_appcenter__chsub_9patch"
    private const val IEP_SOURCE = "AST"
    private const val SHARE_SOURCE = "ANTSTALL"
    private const val RANK_SOURCE = "ANTFARM"
    private const val XLIGHT_AD_COMPONENT_TYPE = "FEEDS"
    private const val XLIGHT_AD_COMPONENT_VERSION = "4.30.62"
    private const val XLIGHT_ENABLE_FUSION = true
    private const val XLIGHT_NETWORK_TYPE = "WWAN"
    private const val XLIGHT_PAGE_NO = 1
    private const val XLIGHT_UNION_APP_ID = "2060090000304921"
    private const val XLIGHT_RUNTIME_SDK_VERSION = "4.30.62"
    private const val XLIGHT_SDK_TYPE = "h5"
    private const val XLIGHT_SDK_VERSION = "4.30.62"
    private const val METHOD_TASK_LIST = "com.alipay.antstall.task.list"
    private const val METHOD_SIGN_TODAY = "com.alipay.antstall.sign.today"
    private const val METHOD_FINISH_TASK = "com.alipay.antiep.finishTask"
    private const val METHOD_GENERATE_TOKEN = "com.alipay.antiep.generateToken"
    private const val METHOD_RECEIVE_TASK_AWARD = "com.alipay.antiep.receiveTaskAward"
    private const val METHOD_TASK_AWARD = "com.alipay.antstall.task.award"

    /**
     * @brief 获取个人主页数据
     * @return 响应字符串
     */
    fun home(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.self.home",
            "[{\"arouseAppParams\":{},\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 结算收益
     * @param assetId 资产ID
     * @param settleCoin 结算金币数量
     * @return 响应字符串
     */
    fun settle(assetId: String, settleCoin: String): String {
        return RequestManager.requestString(
            "com.alipay.antstall.self.settle",
            "[{\"assetId\":\"$assetId\",\"coinType\":\"MASTER\",\"settleCoin\":$settleCoin,\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取商店列表
     * @return 响应字符串
     */
    fun shopList(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.shop.list",
            "[{\"freeTop\":false,\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 一键收摊前的预检查
     * @return 响应字符串
     */
    fun preOneKeyClose(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.shop.close.preOneKey",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 一键收摊
     * @return 响应字符串
     */
    fun oneKeyClose(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.shop.oneKeyClose",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"billNo\":\"$billNo\",\"shopId\":\"$shopId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"shopId\":\"$shopId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 一键开店
     * @return 响应字符串
     */
    fun oneKeyOpen(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.shop.oneKeyOpen",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"friendSeatId\":\"$friendSeatId\",\"friendUserId\":\"$friendUserId\",\"shopId\":\"$shopId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 捐赠排名金币
     * @return 响应字符串
     */
    fun rankCoinDonate(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.rank.coin.donate",
            "[{\"source\":\"$RANK_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"arouseAppParams\":{},\"friendUserId\":\"$userId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取任务列表
     * @return 响应字符串
     */
    fun taskList(): String {
        return RequestManager.requestString(
            METHOD_TASK_LIST,
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 今日签到
     * @return 响应字符串
     */
    fun signToday(): String {
        val response = RequestManager.requestString(
            METHOD_SIGN_TODAY,
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"outBizNo\":\"$outBizNo\",\"requestType\":\"RPC\",\"sceneCode\":\"ANTSTALL_TASK\",\"source\":\"$IEP_SOURCE\",\"systemType\":\"android\",\"taskType\":\"$taskType\",\"version\":\"$VERSION\"}]"
        )
        return response
    }

    /**
     * @brief 生成外跳任务 token
     * @param taskType 任务类型
     * @return 响应字符串
     */
    fun generateToken(taskType: String): String {
        return RequestManager.requestString(
            METHOD_GENERATE_TOKEN,
            "[{\"requestType\":\"RPC\",\"sceneCode\":\"ANTSTALL_TASK\",\"source\":\"$IEP_SOURCE\",\"systemType\":\"android\",\"taskType\":\"$taskType\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 调用广告/插件接口
     * @return 响应字符串
     */
    fun xlightPlugin(
        pageUrl: String,
        pageFrom: String,
        spaceCode: String,
        session: String = "u_${RandomUtil.getRandomString(5)}_${RandomUtil.getRandomString(5)}",
        referToken: String? = null,
        searchInfo: JSONObject? = null,
        playingPageInfo: String? = null,
        positionExtMap: JSONObject? = null,
        pageNo: Int = XLIGHT_PAGE_NO,
        networkType: String = XLIGHT_NETWORK_TYPE
    ): String {
        val args = JSONArray().apply {
            put(
                JSONObject().apply {
                    put(
                        "positionRequest",
                        JSONObject().apply {
                            put("extMap", positionExtMap ?: JSONObject())
                            put(
                                "referInfo",
                                JSONObject().apply {
                                    if (!referToken.isNullOrBlank()) {
                                        put("referToken", referToken)
                                    }
                                }
                            )
                            put("searchInfo", searchInfo ?: JSONObject())
                            put("spaceCode", spaceCode)
                        }
                    )
                    put(
                        "sdkPageInfo",
                        JSONObject().apply {
                            put("adComponentType", XLIGHT_AD_COMPONENT_TYPE)
                            put("adComponentVersion", XLIGHT_AD_COMPONENT_VERSION)
                            put("enableFusion", XLIGHT_ENABLE_FUSION)
                            put("networkType", if (networkType.isBlank()) XLIGHT_NETWORK_TYPE else networkType)
                            put("pageFrom", pageFrom)
                            put("pageNo", if (pageNo > 0) pageNo else XLIGHT_PAGE_NO)
                            put("pageUrl", pageUrl)
                            if (!playingPageInfo.isNullOrBlank()) {
                                put("playingPageInfo", playingPageInfo)
                            }
                            put("session", session)
                            put("unionAppId", XLIGHT_UNION_APP_ID)
                            put("usePlayLink", "true")
                            put("xlightRuntimeSDKversion", XLIGHT_RUNTIME_SDK_VERSION)
                            put("xlightSDKType", XLIGHT_SDK_TYPE)
                            put("xlightSDKVersion", XLIGHT_SDK_VERSION)
                        }
                    )
                }
            )
        }
        return RequestManager.requestString("com.alipay.adexchange.ad.facade.xlightPlugin", args.toString())
    }

    /**
     * @brief 结束特定业务
     * @param playBizId 播放业务ID
     * @param jsonObject 事件信息
     * @return 响应字符串
     */
    fun finish(
        playBizId: String,
        jsonObject: JSONObject,
        iepTaskSceneCode: String? = null,
        iepTaskType: String? = null
    ): String {
        val extendInfo = JSONObject().apply {
            if (!iepTaskSceneCode.isNullOrBlank()) {
                put("iepTaskSceneCode", iepTaskSceneCode)
            }
            if (!iepTaskType.isNullOrBlank()) {
                put("iepTaskType", iepTaskType)
            }
        }
        return RequestManager.requestString(
            "com.alipay.adtask.biz.mobilegw.service.interaction.finish",
            "[{\"extendInfo\":$extendInfo,\"playBizId\":\"$playBizId\",\"playEventInfo\":$jsonObject,\"source\":\"adx\" }]"
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
            "[{\"ignoreLimit\":true,\"requestType\":\"RPC\",\"sceneCode\":\"ANTSTALL_TASK\",\"source\":\"$IEP_SOURCE\",\"systemType\":\"android\",\"taskType\":\"$taskType\",\"version\":\"$VERSION\"}]"
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
            "[{\"amount\":$amount,\"prizeId\":\"$prizeId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"taskType\":\"$taskType\",\"version\":\"$VERSION\"}]"
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
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 收集肥料
     * @return 响应字符串
     */
    fun collectManure(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.manure.collectManure",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 查询肥料信息
     * @return 响应字符串
     */
    fun queryManureInfo(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.manure.queryManureInfo",
            "[{\"queryManureType\":\"ANTSTALL\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取项目列表
     * @return 响应字符串
     */
    fun projectList(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.project.list",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"projectId\":\"$projectId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"bizNo\":\"${UUID.randomUUID()}\",\"projectId\":\"$projectId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取路线图
     * @return 响应字符串
     */
    fun roadmap(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.village.roadmap",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 进入下一个村庄
     * @return 响应字符串
     */
    fun nextVillage(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.user.ast.next.village",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 注册排行榜邀请
     * @return 响应字符串
     */
    fun rankInviteRegister(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.rank.invite.register",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"friendUserId\":\"$friendUserId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 分享助力 (P2P)
     * @return 响应字符串
     */
    fun shareP2P(): String {
        return RequestManager.requestString(
            "com.alipay.antiep.shareP2P",
            "[{\"requestType\":\"RPC\",\"sceneCode\":\"ANTSTALL_P2P_SHARER\",\"source\":\"$SHARE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"requestType\":\"RPC\",\"sceneCode\":\"ANTSTALL_P2P_SHARER\",\"shareId\":\"$shareId\",\"source\":\"$SHARE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"billNo\":\"$billNo\",\"seatId\":\"$seatId\",\"shopId\":\"$shopId\",\"shopUserId\":\"$shopUserId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"seatId\":\"$seatId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 打开排行榜邀请
     * @return 响应字符串
     */
    fun rankInviteOpen(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.rank.invite.open",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"friendUserId\":\"$friendUserId\",\"mySeatId\":\"$mySeatId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 获取动态损失（如被贴罚单记录）
     * @return 响应字符串
     */
    fun dynamicLoss(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.dynamic.loss",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"dynamicList\":$dynamicList,\"sendMsg\":false,\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 结算待收收益
     * @return 响应字符串
     */
    fun settleReceivable(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.self.settle.receivable",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * @brief 查找下一个可以贴罚单的好友
     * @return 响应字符串
     */
    fun nextTicketFriend(): String {
        return RequestManager.requestString(
            "com.alipay.antstall.friend.nextTicketFriend",
            "[{\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
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
            "[{\"billNo\":\"$billNo\",\"seatId\":\"$seatId\",\"shopId\":\"$shopId\",\"shopUserId\":\"$shopUserId\",\"seatUserId\": \"$seatUserId\",\"source\":\"$BASE_SOURCE\",\"systemType\":\"android\",\"version\":\"$VERSION\"}]"
        )
    }
}

package io.github.aoguai.sesameag.task.antSesameCredit

import io.github.aoguai.sesameag.hook.RequestManager
import org.json.JSONArray
import org.json.JSONObject

object AntSesameCreditRpcCall {
    private const val SESAME_CHECK_IN_VERSION = "2025-10-22"
    private const val SESAME_GROWTH_GUIDE_INVOKE_VERSION = "1.0.2025.10.27"
    private const val SESAME_TASK_VERSION = "new"
    private const val SESAME_TASK_SCENE_CODE = "DAILY_MUST_DO_CARD"
    private const val SESAME_TASK_CH_INFO = "ch_zhimahome__chsub_zml_doudi"
    private const val SESAME_TASK_JOIN_CH_INFO = "seasameList"
    private const val METHOD_CREDIT_ACCUMULATE_QUERY_LIST_V3 =
        "com.antgroup.zmxy.zmmemberop.biz.rpc.creditaccumulate.CreditAccumulateStrategyRpcManager.queryListV3"
    private const val METHOD_QUERY_CREDIT_FEEDBACK =
        "com.antgroup.zmxy.zmcustprod.biz.rpc.home.creditaccumulate.api.CreditAccumulateRpcManager.queryCreditFeedback"
    private const val METHOD_COLLECT_CREDIT_FEEDBACK =
        "com.antgroup.zmxy.zmcustprod.biz.rpc.home.creditaccumulate.api.CreditAccumulateRpcManager.collectCreditFeedback"
    private const val METHOD_QUERY_LAST_OPERATE_TASK =
        "com.antgroup.zmxy.zmmemberop.biz.rpc.creditaccumulate.CreditAccumulateStrategyRpcManager.queryLastOperateTask"
    private const val METHOD_ALCHEMY_QUERY_HOME =
        "com.antgroup.zmxy.zmmemberop.biz.rpc.AlchemyRpcManager.queryHome"

    internal fun isRpcSuccess(raw: String): Boolean {
        return try {
            val jo = JSONObject(raw)
            val resultCode = jo.opt("resultCode")
            val resultCodeSuccess = when (resultCode) {
                is Number -> resultCode.toInt() == 200
                is String -> resultCode.equals("SUCCESS", ignoreCase = true) ||
                    resultCode == "100" ||
                    resultCode == "200"
                else -> false
            }
            val errCodeSuccess = jo.optString("errCode") == "0"
            jo.optBoolean("success") ||
                jo.optBoolean("isSuccess") ||
                resultCodeSuccess ||
                errCodeSuccess ||
                jo.optString("memo").equals("SUCCESS", ignoreCase = true) ||
                jo.optString("resultView") == "成功" ||
                jo.optString("resultDesc") == "成功"
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun taskFinish(bizId: String, includeExtendInfo: Boolean = false): String {
        val args = JSONObject().apply {
            put("bizId", bizId)
            if (includeExtendInfo) {
                put("extendInfo", JSONObject())
            }
        }
        val resp = RequestManager.requestString(
            "com.alipay.adtask.biz.mobilegw.service.task.finish",
            JSONArray().put(args).toString()
        )
        return resp
    }

    @JvmStatic
    fun adTaskApplayerQuery(spaceCode: String): String {
        val args = JSONObject().apply {
            put("spaceCode", spaceCode)
        }
        return RequestManager.requestString(
            "com.alipay.adtask.biz.mobilegw.service.applayer.query",
            JSONArray().put(args).toString()
        )
    }


    /**
     * 芝麻信用首页
     */
    @JvmStatic
    fun queryHome(): String {
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmcustprod.biz.rpc.home.api.HomeV7RpcManager.queryHome",
            """[{"invokeSource":"zmHome","miniZmGrayInside":"","version":"week"}]"""
        )
    }

    /**
     * 芝麻信用首页（V8 兼容）
     */
    @JvmStatic
    fun queryHomeV8(): String {
        val args = JSONObject().apply {
            put("invokeSource", "zmHome")
            put("miniZmGrayInside", "")
            put("switchNavigation", true)
            put("switchNewPage", true)
            put("version", "week")
        }
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmcustprod.biz.rpc.home.api.HomeV8RpcManager.queryHome",
            JSONArray().put(args).toString()
        )
    }

    /**
     * 获取芝麻信用任务列表
     */
    @JvmStatic
    fun queryAvailableSesameTask(): String {
        return RequestManager.requestString(
            METHOD_CREDIT_ACCUMULATE_QUERY_LIST_V3,
            """[{"chInfo":"$SESAME_TASK_CH_INFO","deliverStatus":"","deliveryTemplateId":"","sceneCode":"$SESAME_TASK_SCENE_CODE","searchAddToHomeTask":true,"searchGuidePopFlag":true,"searchShareAssistTask":true,"searchSubscribeTask":true,"supportJumpAuth":true,"version":"$SESAME_TASK_VERSION"}]"""
        )
    }

    /**
     * 芝麻信用领取任务
     */
    @JvmStatic
    fun joinSesameTask(taskTemplateId: String, sceneCode: String? = null): String {
        val args = JSONObject().apply {
            put("chInfo", SESAME_TASK_JOIN_CH_INFO)
            put("joinFromOuter", false)
            if (!sceneCode.isNullOrBlank()) {
                put("sceneCode", sceneCode)
            }
            put("templateId", taskTemplateId)
        }
        val resp = RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.promise.PromiseRpcManager.joinActivity",
            JSONArray().put(args).toString()
        )
        return resp
    }

    /**
     * 芝麻信用获取任务回调
     */
    @JvmStatic
    fun feedBackSesameTask(
        taskTemplateId: String,
        bizType: String? = null,
        sceneCode: String? = null,
        version: String? = null
    ): String {
        val args = JSONObject().apply {
            put("actionType", "TO_COMPLETE")
            if (!bizType.isNullOrBlank()) {
                put("bizType", bizType)
            }
            if (!sceneCode.isNullOrBlank()) {
                put("sceneCode", sceneCode)
            }
            put("templateId", taskTemplateId)
            if (!version.isNullOrBlank()) {
                put("version", version)
            }
        }
        val resp = RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.creditaccumulate.CreditAccumulateStrategyRpcManager.taskFeedback",
            JSONArray().put(args).toString(),
            "zmmemberop", "taskFeedback", "CreditAccumulateStrategyRpcManager"
        )
        return resp
    }

    /**
     * 芝麻信用完成任务
     */
    @JvmStatic
    fun finishSesameTask(recordId: String): String {
        val resp = RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.promise.PromiseRpcManager.pushActivity",
            """[{"recordId":"$recordId"}]"""
        )
        return resp
    }

    @JvmStatic
    fun adRewardLjcs(adTaskBizId: String): String {
        val resp = RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.promise.PromiseRpcManager.adRewardLjcs",
            """[{"adTaskBizId":"$adTaskBizId"}]"""
        )
        return resp
    }

    /**
     * 查询可收取的芝麻粒
     */
    @JvmStatic
    fun queryCreditFeedback(): String {
        return RequestManager.requestString(
            METHOD_QUERY_CREDIT_FEEDBACK,
            """[{"queryPotential":false,"size":20,"status":"UNCLAIMED"}]"""
        )
    }

    /**
     * 一键收取芝麻粒
     */
    @JvmStatic
    fun collectAllCreditFeedback(): String {
        val resp = RequestManager.requestString(
            METHOD_COLLECT_CREDIT_FEEDBACK,
            """[{"collectAll":true,"status":"UNCLAIMED"}]"""
        )
        return resp
    }

    /**
     * 收取芝麻粒
     *
     * @param creditFeedbackId creditFeedbackId
     */
    @JvmStatic
    fun collectCreditFeedback(creditFeedbackId: String): String {
        val resp = RequestManager.requestString(
            METHOD_COLLECT_CREDIT_FEEDBACK,
            """[{"collectAll":false,"creditFeedbackId":"$creditFeedbackId","status":"UNCLAIMED"}]"""
        )
        return resp
    }

    /**
     * 芝麻粒首页
     */
    @JvmStatic
    fun queryPointHome(): String {
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.PointHomeRpcManager.queryHome",
            "[{}]"
        )
    }

    /**
     * 芝麻粒积分宝箱查询：data.hasBox=true 且 data.status=WAIT_CLAIM 时可开启
     */
    @JvmStatic
    fun queryPointTreasureBox(): String {
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.PointHomeRpcManager.queryTreasureBox",
            "[{}]"
        )
    }

    /**
     * 开启芝麻粒积分宝箱：成功返回 data.rewardAmount，开启后服务端将 status 置为 HANGING（进入冷却）
     */
    @JvmStatic
    fun openPointTreasureBox(): String {
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.PointHomeRpcManager.openTreasureBox",
            "[{}]"
        )
    }

    /**
     * 查询最近一次被操作的芝麻任务
     */
    @JvmStatic
    fun queryLastOperateTask(version: String = SESAME_TASK_VERSION): String {
        return RequestManager.requestString(
            METHOD_QUERY_LAST_OPERATE_TASK,
            """[{"version":"$version"}]"""
        )
    }

    /**
     * 芝麻粒福利签到列表
     */
    @JvmStatic
    fun zmlCheckInQueryTaskLists(): String {
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.pointtask.CheckInTaskRpcManager.queryTaskLists",
            """[{"supportMakeUp":true,"version":"$SESAME_CHECK_IN_VERSION"}]"""
        )
    }

    fun queryExchangeList(page: Int, pageSize: Int, tab: String? = null): String {
        val tabList = JSONArray()
        if (!tab.isNullOrBlank()) {
            tabList.put(tab)
        }
        val args = JSONArray().put(JSONObject().apply {
            put("currentPage", page)
            put("formDelivery", "false")
            put("pageSize", pageSize)
            put("privilegeSource", "")
            put("privilegeTab", "")
            put("tabList", tabList)
        }).toString()
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.award.AwardRpcManager.queryListV2",
            args
        )
    }

    @JvmStatic
    fun queryAwardDetail(templateId: String): String {
        val args = """[{"awardTemplateId":"$templateId"}]"""
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.award.AwardRpcManager.queryDetail",
            args
        )
    }

    @JvmStatic
    fun obtainAward(templateId: String): String {
        val args = """[{"awardTemplateId":"$templateId"}]"""
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.award.AwardRpcManager.obtainAward",
            args
        )
    }

    @JvmStatic
    fun queryMyAwardDetail(awardId: String): String {
        val args = """[{"awardId":"$awardId"}]"""
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.award.AwardRpcManager.queryMyAwardDetail",
            args
        )
    }

    private const val ZHIMATREE_PLAY_INFO = "SwbtxJSo8OOUrymAU%2FHnY2jyFRc%2BkCJ3"
    private const val ZHIMATREE_CH_INFO = "ch_url-https://2021002135657012.hybrid.alipay-eco.com/index.html"
    private const val ZHIMATREE_REFER =
        "https://render.alipay.com/p/yuyan/180020010001288004/zmTree.html?caprMode=sync&chInfo=ch_zmzlzms__chsub_zlsy_icon"

    @JvmStatic
    fun zhimaTreeHomePage(): String? {
        return try {
            val args = JSONObject().apply {
                put("operation", "ZHIMA_TREE_HOME_PAGE")
                put("playInfo", ZHIMATREE_PLAY_INFO)
                put("refer", ZHIMATREE_REFER)
                put("extInfo", JSONObject())
            }
            RequestManager.requestString(
                "alipay.promoprod.play.trigger",
                JSONArray().put(args).toString()
            )
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun zhimaTreeCleanAndPush(treeCode: String, clearArea: String): String? {
        return try {
            val extInfo = JSONObject().apply {
                put("clearArea", clearArea)
                put("clickNum", "1")
                put("treeCode", treeCode)
            }
            val args = JSONObject().apply {
                put("operation", "ZHIMA_TREE_CLEAN_AND_PUSH")
                put("playInfo", ZHIMATREE_PLAY_INFO)
                put("refer", ZHIMATREE_REFER)
                put("extInfo", extInfo)
            }
            RequestManager.requestString(
                "alipay.promoprod.play.trigger",
                JSONArray().put(args).toString()
            )
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun queryRentGreenTaskList(): String? {
        return try {
            val extInfo = JSONObject().apply {
                put("chInfo", ZHIMATREE_CH_INFO)
                put("batchId", "")
            }
            val args = JSONObject().apply {
                put("operation", "RENT_GREEN_TASK_LIST_QUERY")
                put("playInfo", ZHIMATREE_PLAY_INFO)
                put("refer", ZHIMATREE_REFER)
                put("extInfo", extInfo)
            }
            RequestManager.requestString(
                "alipay.promoprod.play.trigger",
                JSONArray().put(args).toString()
            )
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun rentGreenTaskFinish(taskId: String, stageCode: String): String? {
        return try {
            val extInfo = JSONObject().apply {
                put("chInfo", ZHIMATREE_CH_INFO)
                put("taskId", taskId)
                put("stageCode", stageCode)
            }
            val args = JSONObject().apply {
                put("operation", "RENT_GREEN_TASK_FINISH")
                put("playInfo", ZHIMATREE_PLAY_INFO)
                put("refer", ZHIMATREE_REFER)
                put("extInfo", extInfo)
            }
            RequestManager.requestString(
                "alipay.promoprod.play.trigger",
                JSONArray().put(args).toString()
            )
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun zmCheckInCompleteTask(checkInDate: String, sceneCode: String): String {
        val args = """[{"checkInDate":"$checkInDate","sceneCode":"$sceneCode"}]"""
        return RequestManager.requestString(
            "com.antgroup.zmxy.zmmemberop.biz.rpc.pointtask.CheckInTaskRpcManager.completeTask",
            args
        )
    }

    /**
     * 芝麻信用相关 RPC 归类到 Zmxy 命名空间下，供 AntSesameCredit 调用。
     */
    object Zmxy {

        /**
         * 查询“成长引导/信誉任务”待办列表
         */
        @JvmStatic
        fun queryGrowthGuideToDoList(
            guideBehaviorId: String = "",
            invokeVersion: String = SESAME_GROWTH_GUIDE_INVOKE_VERSION
        ): String {
            val args = JSONObject().apply {
                put("guideBehaviorId", guideBehaviorId)
                put("hitExperiment", true)
                put("invokeVersion", invokeVersion)
                put("newSpecialRecommend", true)
                put("switchNewPage", true)
            }
            val requestData = JSONArray().put(args).toString()
            return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.growthbehavior.apiGrowthBehaviorRpcManager.queryToDoList",
                requestData
            )
        }

        /**
         * 接受/开启一个行为任务
         */
        @JvmStatic
        fun openBehaviorCollect(behaviorId: String): String {
            val requestData = """[{"behaviorId":"$behaviorId"}]"""
            return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.growthbehavior.apiGrowthBehaviorRpcManager.openBehaviorCollect",
                requestData
            )
        }

        /**
         * 查询每日问答题目
         */
        @JvmStatic
        fun queryDailyQuiz(behaviorId: String): String {
            val requestData = """[{"behaviorId":"$behaviorId"}]"""
            return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.growthtask.api.GrowthTaskRpcManager.queryDailyQuiz",
                requestData
            )
        }

        /**
         * 提交每日问答/视频问答答案
         */
        @JvmStatic
        fun pushDailyTask(
            behaviorId: String,
            bizDate: Long,
            answerId: String,
            questionId: String,
            answerStatus: String
        ): String {
            val extInfo =
                """{"answerId":"$answerId","answerStatus":"$answerStatus","questionId":"$questionId"}"""
            val requestData = """[{"behaviorId":"$behaviorId","bizDate":$bizDate,"extInfo":$extInfo}]"""
            return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.growthtask.api.GrowthTaskRpcManager.pushDailyTask",
                requestData
            )
        }

        /**
         * 查询当前可领取的进度球
         */
        @JvmStatic
        fun queryScoreProgress(): String {
            val args = JSONObject().apply {
                put("hitExperiment", true)
                put("needTotalProcess", "TRUE")
                put("queryGuideInfo", true)
                put("switchNewPage", true)
            }
            val requestData = JSONArray().put(args).toString()
            return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.home.api.HomeV8RpcManager.queryScoreProgress",
                requestData
            )
        }

        /**
         * 收集一个或多个进度球
         */
        @JvmStatic
        fun collectProgressBall(newProgressBallIds: JSONArray?): String? {
            if (newProgressBallIds == null || newProgressBallIds.length() == 0) {
                return null
            }
            val args = JSONObject().apply {
                put("ballIdList", JSONArray())
                put("hitExperiment", true)
                put("newProgressBallIds", newProgressBallIds)
            }
            val requestData = JSONArray().put(args).toString()
            return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.growthbehavior.apiGrowthBehaviorRpcManager.collectProgressBall",
                requestData
            )
        }

        @JvmStatic
        fun queryNewTaskCenterModuleConfigs(): String {
            val args = JSONObject()
                .put("bizType", "NEW_TASK_CENTER")
                .put("chInfo", "zhimaxinyong")
            return RequestManager.requestString(
                "alipay.membertangram.biz.rpc.common.queryModuleConfigs",
                JSONArray().put(args).toString()
            )
        }

        @JvmStatic
        fun newTaskCenterSignStatusQuery(): String {
            val args = JSONObject()
                .put("activityId", "SIGN_TASK_CENTER")
                .put("source", "zhimaxinyong")
            return RequestManager.requestString(
                "alipay.membertangram.biz.rpc.newtaskcenter.signStatusQuery",
                JSONArray().put(args).toString()
            )
        }

        @JvmStatic
        fun newTaskCenterQueryTaskList(): String {
            val args = JSONObject()
                .put("activityId", "SIGN_TASK_CENTER")
                .put("source", "zhimaxinyong")
                .put("topTaskCodeList", JSONArray())
                .put("xlightTaskDegrade", false)
            return RequestManager.requestString(
                "alipay.membertangram.biz.rpc.newtaskcenter.queryTaskList",
                JSONArray().put(args).toString()
            )
        }

        @JvmStatic
        fun newTaskCenterQueryPop(): String {
            val args = JSONObject()
                .put("activityId", "SIGN_TASK_CENTER")
            return RequestManager.requestString(
                "alipay.membertangram.biz.rpc.newtaskcenter.queryPop",
                JSONArray().put(args).toString()
            )
        }

        object Alchemy {
            @JvmStatic
            fun alchemyQueryCheckIn(sceneCode: String): String {
                val requestData = """[{"sceneCode":"$sceneCode","version":"$SESAME_CHECK_IN_VERSION"}]"""
                return RequestManager.requestString(
                    "com.antgroup.zmxy.zmmemberop.biz.rpc.pointtask.CheckInTaskRpcManager.queryTaskLists",
                    requestData
                )
            }

            @JvmStatic
            fun alchemyQueryEntryList(): String {
                val requestData = """[{"version":"$SESAME_CHECK_IN_VERSION"}]"""
                return RequestManager.requestString(
                    "com.antgroup.zmxy.zmmemberop.biz.rpc.AlchemyRpcManager.queryEntryList",
                    requestData
                )
            }

            @JvmStatic
            fun claimAward(awardId: String = ""): String {
                val requestData = if (awardId.isBlank()) {
                    "[{}]"
                } else {
                    """[{"awardId":"$awardId"}]"""
                }
                return RequestManager.requestString(
                    "com.antgroup.zmxy.zmmemberop.biz.rpc.AlchemyRpcManager.claimAward",
                    requestData
                )
            }

            @JvmStatic
            fun alchemyQueryHome(): String {
                return RequestManager.requestString(
                    METHOD_ALCHEMY_QUERY_HOME,
                    "[{}]"
                )
            }

            @JvmStatic
            fun alchemyExecute(): String {
                val requestData = JSONArray().put(JSONObject().apply {
                    put("outerNewHand", false)
                }).toString()
                return RequestManager.requestString(
                    "com.antgroup.zmxy.zmmemberop.biz.rpc.AlchemyRpcManager.alchemy",
                    requestData
                )
            }

            @JvmStatic
            fun alchemyQueryTimeLimitedTask(): String {
                return RequestManager.requestString(
                    "com.antgroup.zmxy.zmmemberop.biz.rpc.pointtask.TimeLimitedTaskRpcManager.queryTask",
                    "[{}]"
                )
            }

            @JvmStatic
            fun alchemyCompleteTimeLimitedTask(templateId: String): String {
                val requestData = """[{"templateId":"$templateId"}]"""
                return RequestManager.requestString(
                    "com.antgroup.zmxy.zmmemberop.biz.rpc.pointtask.TimeLimitedTaskRpcManager.completeTask",
                    requestData
                )
            }

            @JvmStatic
            fun alchemyQueryListV3(): String {
                val requestData =
                    """[{"chInfo":"","deliverStatus":"","deliveryTemplateId":"","searchSubscribeTask":true,"supportRewardLJCS":true,"version":"alchemy"}]"""
                return RequestManager.requestString(
                    METHOD_CREDIT_ACCUMULATE_QUERY_LIST_V3,
                    requestData
                )
            }
        }
    }

}

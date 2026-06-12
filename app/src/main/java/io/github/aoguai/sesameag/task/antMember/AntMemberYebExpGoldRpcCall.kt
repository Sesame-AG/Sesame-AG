package io.github.aoguai.sesameag.task.antMember

import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject

object AntMemberYebExpGoldRpcCall {
    private const val YEB_EXP_GOLD_SIGN_IN_PLAY_ID = "PLAY102253251"
    private const val YEB_EXP_GOLD_CH_INFO = "ch_url-https://render.alipay.com/p/yuyan/180020010001282160/index.html"

    fun queryYebExpGoldMain(
        queryComplete: Boolean = false,
        taskId: String? = null
    ): String {
        val taskPayload = if (taskId.isNullOrBlank()) {
            """{"downgrade":false,"queryComplete":$queryComplete,"strategyCode":"YEB_TRIAL_ASSET_TASK_BLOCK_REC"}"""
        } else {
            """{"downgrade":false,"queryComplete":$queryComplete,"startTime":${System.currentTimeMillis()},"strategyCode":"YEB_TRIAL_ASSET_TASK_BLOCK_REC","taskId":"$taskId"}"""
        }
        return RequestManager.requestString(
            "com.alipay.yebscenebff.needle.yebExpGold.queryMain",
            """[{"chInfo":"$YEB_EXP_GOLD_CH_INFO","signIn":{"daysOfQuerySignInData":21,"displaySignInTextList":[{"value":"持"},{"value":"续"},{"value":"签"},{"value":"到"},{"value":"可"},{"value":"领"},{"value":""}],"downgrade":false,"todayRedDotText":"戳这里","tomorrowRedDotText":""},"task":$taskPayload}]"""
        )
    }

    fun signInYebExpGold(signInPlayId: String = YEB_EXP_GOLD_SIGN_IN_PLAY_ID): String {
        return RequestManager.requestString(
            "com.alipay.yebscenebff.needle.yebExpGold.signIn",
            """[{"signInPlayId":"$signInPlayId"}]"""
        )
    }

    fun queryYebExpGoldTaskList(): String {
        return RequestManager.requestString(
            "com.alipay.yebpromobff.promosdk2024.task.query",
            """[{"needTriggerPrize":false,"playActionCode":"TASK_LIST_CONSULT","playEntrance":"HYQ_TASK_LIST_ENTRANCE_2"}]"""
        )
    }

    fun queryYebExpGoldTaskById(taskId: String): String {
        return RequestManager.requestString(
            "com.alipay.yebpromobff.promosdk2024.task.queryTaskByTaskId",
            """[{"appName":"yebpromobff","playActionCode":"TASK_STATUS_QUERY","playEntrance":"HYQ_TASK_LIST_ENTRANCE_2","taskId":"$taskId"}]"""
        )
    }

    fun completeYebExpGoldTask(taskId: String): String {
        val outBizNo = "$taskId-${System.currentTimeMillis()}-"
        return RequestManager.requestString(
            "com.alipay.yebpromobff.promosdk2024.task.complete",
            """[{"appName":"yebpromobff","outBizNo":"$outBizNo","playActionCode":"TASK_COMPLETE","playEntrance":"HYQ_TASK_LIST_ENTRANCE_2","taskId":"$taskId"}]"""
        )
    }

    fun queryYebTrialAsset(): String {
        return RequestManager.requestString(
            "alipay.yebprod.promo.yebTrialAsset",
            "[{}]"
        )
    }

    fun exchangeYebExpGold(
        campId: String,
        prizeId: String,
        exchangeAmount: String
    ): String {
        val bizOrderNo = "${UserMap.currentUid.orEmpty()}${System.currentTimeMillis()}"
        return RequestManager.requestString(
            "com.alipay.yebscenebff.expgold.index.exchange",
            """[{"bizOrderNo":"$bizOrderNo","campId":"$campId","exchangeAmount":"$exchangeAmount","prizeId":"$prizeId"}]"""
        )
    }

    fun activeYebTrial(couponId: String): String {
        return RequestManager.requestString(
            "alipay.yebprod.promo.yebTrial.active",
            """[{"couponId":"$couponId","equityType":"voucher","type":"YEB_TRIAL"}]"""
        )
    }

    fun queryYebTrialCertVoucher(): String {
        val args = JSONObject().apply {
            put("component", "PROMO_ACTIVITY")
            put("sortType", "drawTime")
            put("source", "QIANAPP")
            put(
                "voucherTemplateIdList",
                JSONArray()
                    .put("202312260007300180780087H5IR")
                    .put("2026011300073001807800H1558H")
            )
        }
        return RequestManager.requestString(
            "alipay.yebprod.query.queryYebTrialCertVoucher",
            JSONArray().put(args).toString()
        )
    }

    fun convertYebExpGoldVoucher(): String {
        val args = JSONObject().apply {
            put("convertType", "all")
            put("isShowExchangeModal", true)
        }
        return RequestManager.requestString(
            "com.alipay.yebscenebff.needle.yebExpGoldVoucherConvert",
            JSONArray().put(args).toString()
        )
    }
}

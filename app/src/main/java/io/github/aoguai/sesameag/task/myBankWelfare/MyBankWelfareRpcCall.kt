package io.github.aoguai.sesameag.task.myBankWelfare

import io.github.aoguai.sesameag.hook.RequestManager
import org.json.JSONArray
import org.json.JSONObject

object MyBankWelfareRpcCall {

    private const val TASK_CENTER_APPLET_ID = "AP1269301"
    private const val SIGN_PLAY_ID = "PLAY100177545"
    private const val MEMBER_BENEFIT_CAMP_ID = "BSCP202209161036790608000055160000G"
    private const val MEMBER_BENEFIT_SCENE_CODE = "MYBK_SUPER_930"
    private const val MEMBER_BENEFIT_TAB_ID = "BSLB202209161036790633000055640000G"
    private const val WELFARE_POINT_SCENE_CODE = "SUPER930"
    private const val WELFARE_POINT_TYPE = "ANTBANK_WELFARE_POINT"

    private val VIRTUAL_PROFIT_SCENE_CODES = listOf(
        "FULICenter_SENLIN",
        "FULICenter_FiveOpenBC",
        "FULICenter_YYYYHNewUserGift",
        "FULICenter_UnredeemedUserMindActivityForV2Plus",
        "FULICenter_JKJML",
        "FULICenter_JZN",
        "BC3_BC3V1",
        "BC3_BC3V2",
        "BC3_BC3V3",
        "SQB_SQBV0",
        "SQB_SQBV1",
        "SQB_SQBV2",
        "SQB_SQBV3",
        "SQB_SQBV4",
        "SQB_SQBV5",
        "SQB_SQBV6",
        "SQB_SQBV7",
        "SQB_SQBV8",
        "SQB_SQBV9",
        "SQB_SQBV10",
        "SQB_SQBV11",
        "SQB_SQBSIGN",
        "FULICenter_JKJQW",
        "FULICenter_WSWF",
        "FULICenter_FLKZS",
        "FULICenter_KGJXBBF",
        "FULICenter_AXHZXB",
        "FULICenter_BBF",
        "FULICenter_V1",
        "FULICenter_V2",
        "FULICenter_V3",
        "FULICenter_V4",
        "FULICenter_V5",
        "FULICenter_V6",
        "FULICenter_V7",
        "FULICenter_YulibaoAUM",
        "FULICenter_PayByMybank",
        "FULICenter_DepositAUM",
        "FULICenter_YYYYH",
        "FULICenter_QYZ",
        "FULICenter_V7PLUS",
        "FULICenter_V6PLUS",
        "FULICenter_V5PLUS",
        "FULICenter_V8",
        "FULICenter_V9",
        "FULICenter_V10",
        "FULICenter_LCCZ",
        "FULICenter_LCTZ",
        "FULICenter_shequn",
        "FULICenter_shizhounian",
        "HarvestCard_HarvestCardGold1",
        "HarvestCard_HarvestCardGold2",
        "HarvestCard_HarvestCardGold3",
        "BC3_BC3SILVER",
        "HarvestCard_SILVER",
        "FULICenter_ylbxianshi",
        "FULICenter_FLR",
        "FULICenter_yiliaowenda",
        "FULICenter_HarvestCardNormal",
        "MybankMembe_MybankMemberSILVER",
        "MybankMembe_MybankMemberGOLD1",
        "MybankMembe_MybankMemberGOLD2",
        "MybankMembe_MybankMemberGOLD3",
        "LicaiKaimenhong_LicaiKaimenhongYueqianli",
        "Kaimenhong_MemberGOLD3",
        "Kaimenhong_MemberGOLD2",
        "Kaimenhong_MemberGOLD1",
        "Kaimenhong_MemberSILVER",
        "LEYEKA_LEYEKAGOLD3",
        "LEYEKA_LEYEKAGOLD2",
        "LEYEKA_LEYEKAGOLD1",
        "LEYEKA_LEYEKAYINKA",
        "BC3_DIAMOND",
        "LEYEKA_LEYEKADIAMOND",
        "HarvestCard_HarvestCardDiamond"
    )

    @JvmStatic
    fun taskQuery(appletId: String = TASK_CENTER_APPLET_ID): String {
        val args = JSONObject().apply {
            put("appletId", appletId)
        }
        return RequestManager.requestString(
            "com.alipay.loanpromoweb.promo.task.taskQuery",
            JSONArray().put(args).toString()
        )
    }

    @JvmStatic
    fun taskTrigger(
        appletId: String,
        stageCode: String,
        taskCenId: String = TASK_CENTER_APPLET_ID
    ): String {
        val args = JSONObject().apply {
            put("appletId", appletId)
            put("stageCode", stageCode)
            put("taskCenId", taskCenId)
        }
        return RequestManager.requestString(
            "com.alipay.loanpromoweb.promo.task.taskTrigger",
            JSONArray().put(args).toString()
        )
    }

    @JvmStatic
    fun signinPlay(
        playId: String = SIGN_PLAY_ID,
        operation: String = "signConsult"
    ): String {
        val args = JSONObject().apply {
            put("channel", "miniApp")
            put("needMultiple", false)
            put("operation", operation)
            put("playId", playId)
        }
        return RequestManager.requestString(
            "com.alipay.loanpromoweb.member.play.signinPlay",
            JSONArray().put(args).toString()
        )
    }

    @JvmStatic
    fun queryEnableVirtualProfitV2(): String {
        val args = JSONObject().apply {
            put("firstSceneCode", JSONArray())
            put("profitType", WELFARE_POINT_TYPE)
            put("sceneCode", JSONArray(VIRTUAL_PROFIT_SCENE_CODES))
            put("signInSceneId", "")
        }
        return RequestManager.requestString(
            "com.alipay.loanpromoweb.promo.virtualProfit.queryEnableVirtualProfitV2",
            JSONArray().put(args).toString()
        )
    }

    @JvmStatic
    fun queryPointBalance(): String {
        val args = JSONObject().apply {
            put("sceneCode", WELFARE_POINT_SCENE_CODE)
        }
        return RequestManager.requestString(
            "com.alipay.loanpromoweb.promo.group.point.pointBanlanceV2",
            JSONArray().put(args).toString()
        )
    }

    @JvmStatic
    fun queryItemsInMemberV2(pageNum: Int = 1, perPageSize: Int = 20): String {
        val args = JSONObject().apply {
            put("campId", MEMBER_BENEFIT_CAMP_ID)
            put("homeQuery", false)
            put("pageNum", pageNum)
            put("perPageSize", perPageSize.toString())
            put("sceneCode", MEMBER_BENEFIT_SCENE_CODE)
            put("tabId", MEMBER_BENEFIT_TAB_ID)
        }
        return RequestManager.requestString(
            "com.alipay.loanpromoweb.member.benefits.queryItemsInMemberV2",
            JSONArray().put(args).toString()
        )
    }
}

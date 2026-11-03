package io.github.aoguai.sesameag.task.EcoProtection

import io.github.aoguai.sesameag.hook.RequestManager.requestString
import org.json.JSONArray
import org.json.JSONObject

object EcoProtectionRpcCall {
    private const val VERSION = "20230522"
    fun homePage(selectCityCode: String?): String {
        return requestString(
            "alipay.greenmatrix.rpc.h5.ancienttree.homePage",
            ("[{\"cityCode\":\"330100\",\"selectCityCode\":\"" + selectCityCode
                    + "\",\"source\":\"antforesthome\"}]")
        )
    }

    fun queryTreeItemsForExchange(cityCode: String?): String {
        return requestString(
            "alipay.antforest.forest.h5.queryTreeItemsForExchange",
            ("[{\"cityCode\":\"" + cityCode
                    + "\",\"itemTypes\":\"\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"version\":\""
                    + VERSION + "\"}]")
        )
    }

    fun districtDetail(districtCode: String?): String {
        return requestString(
            "alipay.greenmatrix.rpc.h5.ancienttree.districtDetail",
            "[{\"districtCode\":\"" + districtCode + "\",\"source\":\"antforesthome\"}]"
        )
    }

    fun projectDetail(ancientTreeProjectId: String?, cityCode: String?): String {
        return requestString(
            "alipay.greenmatrix.rpc.h5.ancienttree.projectDetail",
            ("[{\"ancientTreeProjectId\":\"" + ancientTreeProjectId
                    + "\",\"channel\":\"ONLINE\",\"cityCode\":\"" + cityCode
                    + "\",\"source\":\"ancientreethome\"}]")
        )
    }

    fun doRubickActivity(actionCode: String, activityId: String, paramMap: JSONObject): String = requestString(
        "com.alipay.charityactivity.rubick.rpc.h5.doRubickActivity",
        JSONArray().put(JSONObject().put("actionCode", actionCode).put("activityId", activityId)
            .put("paramMap", paramMap).put("source", "forest")).toString(),
    )

    fun protect(activityId: String?, ancientTreeProjectId: String?, cityCode: String?): String {
        return requestString(
            "alipay.greenmatrix.rpc.h5.ancienttree.protect",
            ("[{\"ancientTreeActivityId\":\"" + activityId + "\",\"ancientTreeProjectId\":\""
                    + ancientTreeProjectId + "\",\"cityCode\":\"" + cityCode
                    + "\",\"source\":\"ancientreethome\"}]")
        )
    }
}

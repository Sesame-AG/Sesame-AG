package io.github.aoguai.sesameag.task.antOrchard

import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.util.Log
import org.json.JSONArray
import org.json.JSONObject

internal object XLightRpcCall {
    private const val TAG = "XLightRpcCall"

    // 固定 SDK 信息
    private const val AD_COMPONENT_TYPE = "FEEDS"
    private const val AD_COMPONENT_VERSION = "4.30.21"
    private const val ENABLE_FUSION = true
    private const val NETWORK_TYPE = "WWAN"
    private const val PAGE_NO = 2
    private const val UNION_APP_ID = "2060090000304921"
    private const val XLIGHT_RUNTIME_SDK_VERSION = "4.30.21"
    private const val XLIGHT_SDK_TYPE = "h5"
    private const val XLIGHT_SDK_VERSION = "4.30.21"

    /**
     * 调用 xlightPlugin
     * @param referToken referToken 字符串
     * @param pageUrl 当前页面 url
     * @param pageFrom 页面来源
     */
    fun xlightPlugin(
        pageUrl: String,
        pageFrom: String,
        session: String,
        spaceCode: String,
        referToken: String? = null,
        searchInfo: JSONObject? = null,
        playingPageInfo: String? = null,
        positionExtMap: JSONObject? = null,
        pageNo: Int = PAGE_NO,
        networkType: String = NETWORK_TYPE
    ): String {
        return try {

            // positionRequest
            val positionRequest = JSONObject().apply {
                put("extMap", positionExtMap ?: JSONObject())
                put("referInfo", JSONObject().apply {
                    if (!referToken.isNullOrBlank()) {
                        put("referToken", referToken)
                    }
                })
                put("searchInfo", searchInfo ?: JSONObject())
                put("spaceCode", spaceCode)
            }

            // sdkPageInfo
            val sdkPageInfo = JSONObject().apply {
                put("adComponentType", AD_COMPONENT_TYPE)
                put("adComponentVersion", AD_COMPONENT_VERSION)
                put("enableFusion", ENABLE_FUSION)
                put("networkType", if (networkType.isBlank()) NETWORK_TYPE else networkType)
                put("pageFrom", pageFrom)
                put("pageNo", if (pageNo > 0) pageNo else PAGE_NO)
                put("pageUrl", pageUrl)
                if (!playingPageInfo.isNullOrBlank()) {
                    put("playingPageInfo", playingPageInfo)
                }
                put("session", session)
                put("unionAppId", UNION_APP_ID)
                put("usePlayLink", "true")
                put("xlightRuntimeSDKversion", XLIGHT_RUNTIME_SDK_VERSION)
                put("xlightSDKType", XLIGHT_SDK_TYPE)
                put("xlightSDKVersion", XLIGHT_SDK_VERSION)
            }

            // 数组包装
            val args = JSONArray().apply {
                put(JSONObject().apply {
                    put("positionRequest", positionRequest)
                    put("sdkPageInfo", sdkPageInfo)
                })
            }

            // RPC 调用
            RequestManager.requestString(
                "com.alipay.adexchange.ad.facade.xlightPlugin",
                args.toString()
            )

        } catch (e: Exception) {
            Log.printStackTrace(TAG, "xlightPlugin failed", e)
            ""
        }
    }

    /**
     * 完成广告任务（新版，支持 extendInfo）
     * @param playBizId 广告任务业务 ID
     * @param playEventInfo 完整的 playEventInfo JSON
     * @param iepTaskSceneCode extendInfo.iepTaskSceneCode
     * @param iepTaskType extendInfo.iepTaskType
     */
    fun finishTask(
        playBizId: String,
        playEventInfo: JSONObject,
        iepTaskSceneCode: String? = null,
        iepTaskType: String? = null
    ): String {
        return try {

            // extendInfo
            val extendInfo = JSONObject().apply {
                if (!iepTaskSceneCode.isNullOrBlank()) {
                    put("iepTaskSceneCode", iepTaskSceneCode)
                }
                if (!iepTaskType.isNullOrBlank()) {
                    put("iepTaskType", iepTaskType)
                }
            }

            // 单条任务对象
            val args = JSONObject().apply {
                put("extendInfo", extendInfo)
                put("playBizId", playBizId)
                put("playEventInfo", playEventInfo)
                put("source", "adx")   // 固定
            }

            // 最外层数组
            val argsArray = JSONArray().apply {
                put(args)
            }

            RequestManager.requestString(
                "com.alipay.adtask.biz.mobilegw.service.interaction.finish",
                argsArray.toString()
            )

        } catch (e: Exception) {
            Log.printStackTrace(TAG, "finishTask failed", e)
            ""
        }
    }

}


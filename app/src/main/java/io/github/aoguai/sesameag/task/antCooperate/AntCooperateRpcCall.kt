package io.github.aoguai.sesameag.task.antCooperate

import io.github.aoguai.sesameag.hook.RequestManager
import io.github.aoguai.sesameag.util.RandomUtil
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object AntCooperateRpcCall {
    private const val VERSION = "20230501"

    @JvmStatic
    fun queryUserCooperatePlantList(): String {
        return RequestManager.requestString(
            "alipay.antmember.forest.h5.queryUserCooperatePlantList",
            "[{}]"
        )
    }

    @JvmStatic
    fun queryCooperatePlant(coopId: String): String {
        val args1 = "[{\"cooperationId\":\"" + coopId + "\"}]"
        return RequestManager.requestString(
            "alipay.antmember.forest.h5.queryCooperatePlant",
            args1
        )
    }

    @JvmStatic
    fun cooperateWater(uid: String?, coopId: String, count: Int): String {
        val args = "[{\"bizNo\":\"" + uid + "_" + coopId + "_" + System.currentTimeMillis() +
                "\",\"cooperationId\":\"" + coopId + "\",\"energyCount\":" + count +
                ",\"source\":\"\",\"version\":\"" + VERSION + "\"}]"
        return RequestManager.requestString(
            "alipay.antmember.forest.h5.cooperateWater",
            args
        )
    }

    /**
     * 获取合种浇水量排行
     *
     * @param bizType 参数：D/A,“D”为查询当天，“A”为查询所有
     * @param coopId  合种ID
     * @return x
     */
    fun queryCooperateRank(bizType: String?, coopId: String?): String {
        return RequestManager.requestString(
            "alipay.antmember.forest.h5.queryCooperateRank",
            "[{\"bizType\":\"$bizType\",\"cooperationId\":\"$coopId\",\"source\":\"ch_appcenter__chsub_9patch\"}]"
        )
    }

    /**
     * 更新用户配置（是否处于队伍中）
     *
     * 示例请求体：
     * [
     *   {
     *     "configMap": {
     *       "inTeam": "Y"
     *     },
     *     "source": "chInfo_ch_appcenter__chsub_9patch"
     *   }
     * ]
     *
     * 说明：
     * - inTeam = "Y" 表示用户在队伍中
     * - inTeam = "N" 表示用户不在队伍中
     *
     * @param inTeam 是否在队伍中（true = Y，false = N）
     * @return 返回 RPC 响应字符串
     */


    @JvmStatic
    fun updateUserConfig(inTeam: Boolean): String {
        val inTeamValue = if (inTeam) "Y" else "N"
        val args = "[{" +
                "\"configMap\":{\"inTeam\":\"$inTeamValue\"}," +
                "\"source\":\"chInfo_ch_appcenter__chsub_9patch\"" +
                "}]"

        return RequestManager.requestString(
            "alipay.antforest.forest.h5.updateUserConfig",
            args
        )
    }


    @JvmStatic
    @Throws(JSONException::class)
    fun sendCooperateBeckon(userId: String, cooperationId: String): String {
        val jo = JSONObject().apply {
            put("bizImage", "https://gw.alipayobjects.com/zos/rmsportal/gzYPfxdAxLrkzFUeVkiY.jpg")
            put(
                "link",
                "lipays://platformapi/startapp?appId=66666886&url=%2Fwww%2Fcooperation%2Findex.htm%3FcooperationId%3D" +
                        cooperationId + "%26sourceName%3Dcard"
            )
            put("midTitle", "快来给我们的树苗浇水，让它快快长大。")
            put(
                "noticeLink",
                "alipays://platformapi/startapp?appId=60000002&url=https%3A%2F%2Frender.alipay.com%2Fp%2Fc%2F17ussbd8vtfg%2Fmessage.html%3FsourceName%3Dcard&showOptionMenu=NO&transparentTitle=NO"
            )
            put("topTitle", "树苗需要你的呵护")
            put("source", "chInfo_ch_url-https://render.alipay.com/p/yuyan/180020010001247580/home.html")
            put("cooperationId", cooperationId)
            put("userId", userId)
        }
        val args = JSONArray().put(jo).toString()
        return RequestManager.requestString(
            "alipay.antmember.forest.h5.sendCooperateBeckon",
            args
        )
    }

    @JvmStatic
    fun queryLoveHome(): String {
        val start = "20251022"
        val end = "20251217"
        val args = "[{\"calenderEnd\":\"" + end +
                "\",\"calenderStart\":\"" + start +
                "\",\"source\":\"chInfo_ch_appcenter__chsub_9patch\"}]"
        return RequestManager.requestString(
            "alipay.greenmatrix.rpc.h5.love.loveHome",
            args
        )
    }

    @JvmStatic
    fun loveTeamWater(teamId: String, donateNum: Int): String {
        val args = "[{\"donateNum\":" + donateNum +
                ",\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"teamId\":\"" + teamId + "\"}]"
        return RequestManager.requestString(
            "alipay.greenmatrix.rpc.h5.love.teamWater",
            args
        )
    }

    @JvmStatic
    fun queryHomePage(): String {
        val args = "[{\"configVersionMap\":{\"wateringBubbleConfig\":\"0\"},\"skipWhackMole\":false,\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"version\":\"20250818\"}]"
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.queryHomePage",
            args
        )
    }

    /**
     * 组队版浇水
     * 修复了 sToken 生成逻辑，必须是 时间戳_8位
     * @param teamId 队伍ID
     * @param energyCount 浇水克数
     * @return 响应字符串，可能为 null
     */
    @JvmStatic
    fun teamWater(teamId: String, energyCount: Int): String {
        // 1. 生成毫秒级时间戳
        val ts = System.currentTimeMillis()

        // 2. 生成 8 位随机数字字符
        val rand = RandomUtil.getRandomString(8)

        // 3. 拼接 sToken：时间戳_8位数字字符
        val sToken = "${ts}_${rand}"

        // 4. 构造参数 JSON 字符串（与 queryLoveHome 一致的写法）
        val args = "[{" +
                "\"energyCount\":$energyCount," +
                "\"sToken\":\"$sToken\"," +
                "\"source\":\"chInfo_ch_appcenter__chsub_9patch\"," +
                "\"teamId\":\"$teamId\"" +
                "}]"

        // 5. RPC 调用
        return RequestManager.requestString(
            "alipay.antforest.forest.h5.teamWater",
            args
        )
    }

    /**
     * 查询 MiscInfo（teamFlagTreeCount 等）
     *
     * 示例请求体：
     * {
     *   "configVersionMap": {},
     *   "extInfo": {},
     *   "queryBizType": "teamFlagTreeCount",
     *   "source": "SELF_HOME",
     *   "version": "20240201"
     * }
     *
     * @param queryBizType 查询的业务类型，例如 "teamFlagTreeCount"
     * @return 返回 RPC 响应字符串
     */
    @JvmStatic
    fun queryMiscInfo(queryBizType: String, Teamid: String): String {
        // 构造 H5 RPC 参数（森林所有 H5 RPC 都要求外层包一层数组）
        val args = """
        [{
            "queryBizType":"$queryBizType",
            "source":"SELF_HOME",
            "targetUserId":"$Teamid",
            "version":"20240201"
        }]
    """.trimIndent() // trimIndent 去除换行和缩进，保证 JSON 格式正确

        return RequestManager.requestString(
            "alipay.antforest.forest.h5.queryMiscInfo",
            args
        )
    }
}

package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.internal.AlipayMiniMarkHelper
import io.github.aoguai.sesameag.hook.internal.AuthCodeHelper
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture
import io.github.aoguai.sesameag.task.TaskExecutionReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class GameTaskReportResult(
    val requestedRewards: Int,
    val requiredSuccesses: Int,
    val attemptedReports: Int,
    val successfulReports: Int,
    val failureMessage: String = "",
    val failureCode: String = "",
    val failureRaw: String = "",
    val stopCurrentRound: Boolean = false,
    val businessConfirmed: Boolean = false,
    val resultUnknown: Boolean = false,
) {
    val accepted: Boolean
        get() = failureMessage.isBlank() && (businessConfirmed || successfulReports >= requiredSuccesses)
}

private data class GameTaskSingleReportResult(
    val success: Boolean,
    val message: String = "",
    val code: String = "",
    val raw: String = "",
    val stopCurrentRound: Boolean = false,
    val token: String? = null,
    val writeStarted: Boolean = false,
    val resultUnknown: Boolean = false,
)

class GameTask private constructor(
    val title: String,
    val appId: String,
    val gid: String,
    val action: String,
    val channel: String,
    val version: String,
    val requestsPerEgg: Int,
    val sdk: String = "aslk2018",
    val protocolParameters: Map<String, String> = emptyMap(),
    val actions: List<String> = listOf(action).filter(String::isNotBlank),
) {
    companion object {
        val Orchard_ncscc = GameTask("农场乐园:农场上车车", "2060170000356601", "zfb_ncscc",
            "ncscc_game_kaiche_every_10", "nongchangleyuan", "1.0.2", 2)
        val Farm_ddply = GameTask("庄园乐园:对对碰乐园", "2021004149679303", "zfb_ddply",
            "ddply_game_xiaochu_every_5", "zhuangyuan", "1.0.14", 4)
        val Forest_slxcc = GameTask("森林乐园:森林小车车", "2060170000363691", "zfb_slxcc",
            "slxcc_game_kaiche_every_10", "lianyun_senlin_leyuan", "1.0.1", 3)
        val Forest_sljyd = GameTask("森林乐园:森林救援队(能量雨)", "2021005113684028", "zfb_sljydx",
            "sljyd_game_xiaochu_every_10", "lianyun_senlin_leyuan", "1.0.1", 3)

        // 动作使用服务端合同或该游戏已确认的厂商事件。
        private val games = listOf(
            Orchard_ncscc, Farm_ddply, Forest_slxcc, Forest_sljyd,
            GameTask("捞个菜", "2021000110660562", "zfb_lgc", "lgc_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2606221937.35", 1, "aslk2018", mapOf("gid" to "zfb_lgc")),
            GameTask("找不停", "2021000111632448", "zfb_zbt", "zbt_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2606181421.5", 1, "aslk2018", mapOf("gid" to "zfb_zbt", "v" to "1.0.0")),
            GameTask("超级解压馆", "2021004126650405", "zfb_cjjyg", "cjjyg_game_hecheng_every_5",
                "zmxyzms_game_task", "0.2.2507301514.41", 1, "aslk2018", mapOf("gid" to "zfb_cjjyg")),
            GameTask("玩梗高手", "2021004128666655", "zfb_wggs", "wggs_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2603171940.31", 1, "aslk2018", mapOf("gid" to "zfb_wggs", "v" to "1.0.4")),
            GameTask("水滴排排序", "2021004131628794", "zfb_sdppxx", "sdppx_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2512171908.31", 1, "aslk2018", mapOf("gid" to "zfb_sdppxx")),
            GameTask("火锅消不停", "2021004135688960", "zfb_hgxbt", "hgxbt_game_xiaochu_every_5",
                "zmxyzms_game_task", "0.2.2407091636.11", 1, "aslk2018", mapOf("gid" to "zfb_hgxbt")),
            GameTask("农场解压馆", "2021004138632167", "zfb_ncjyg", "ncjyg_game_hecheng_every_5",
                "zmxyzms_game_task", "0.2.2511281734.55", 1, "aslk2018", mapOf("gid" to "zfb_ncjyg")),
            GameTask("螺丝庄园", "2021004150621264", "zfb_lszy", "lszy_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2512051527.46", 1, "aslk2018", mapOf("gid" to "zfb_lszy")),
            GameTask("农场解螺丝", "2021004153698112", "zfb_ncjls", "ncjls_game_xiaochu_every_5",
                "zmxyzms_game_task", "0.2.2601231520.27", 1, "aslk2018", mapOf("gid" to "zfb_ncjls")),
            GameTask("会员连不停", "2021004158640815", "zfb_hylbt", "qsgj_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2509281117.51", 1, "aslk2018", mapOf("gid" to "zfb_hylbt")),
            GameTask("玩个儿毛线", "2021004159604141", "zfb_wgemx", "wgemx_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2512081218.33", 1, "aslk2018", mapOf("gid" to "zfb_wgemx")),
            GameTask("拿下100关", "2021004164603638", "zfb_nx100g", "",
                "zmxyzms_game_task", "0.2.2606112022.39", 1, "aslk2018", mapOf("gid" to "zfb_nx100g", "v" to "1.0.0")),
            GameTask("离谱的汉字", "2021004164632103", "zfb_lpdhz1", "",
                "zmxyzms_game_task", "0.2.2507181129.29", 1, "aslk2018", mapOf("gid" to "zfb_lpdhz1")),
            GameTask("农场文字秀", "2021004170628696", "zfb_ncwzx", "ncwzx_game_hecheng_every_10",
                "zmxyzms_game_task", "0.2.2601192247.15", 1, "aslk2018", mapOf("gid" to "zfb_ncwzx")),
            GameTask("农场大聪明", "2021004172624044", "zfb_ncdcm", "ncdcm_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2602101831.24", 1, "aslk2018", mapOf("gid" to "zfb_ncdcm")),
            GameTask("会员爱动脑", "2021004194616336", "zfb_hyadn", "hyadn_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2506261635.47", 1, "aslk2018", mapOf("gid" to "zfb_hyadn")),
            GameTask("森林萌萌消", "2021005112605978", "zfb_slmmxx", "slmmx_game_xiaochu_every_10",
                "zmxyzms_game_task", "0.2.2601281827.8", 1, "aslk2018", mapOf("gid" to "zfb_slmmxx")),
            GameTask("诗意绿洲", "2021005112647931", "zfb_sylz", "",
                "zmxyzms_game_task", "0.2.2606091857.22", 1, "aslk2018", mapOf("gid" to "zfb_sylz")),
            GameTask("森林解压馆", "2021005113622761", "zfb_sljyg", "sljyg_game_hecheng_every_1",
                "zmxyzms_game_task", "0.2.2509301530.27", 1, "aslk2018", mapOf("gid" to "zfb_sljyg")),
            GameTask("找个喵", "2021005172696439", "zfb_zgm", "zgm_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2601131551.50", 1, "aslk2018", mapOf("gid" to "zfb_zgm")),
            GameTask("挪了个箭", "2021006113622705", "zfb_nlgj", "nlgj_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2605252023.30", 1, "aslk2018", mapOf("gid" to "zfb_nlgj", "v" to "1.0.5")),
            GameTask("玩水大作战", "2021006117687842", "zfb_wsdzz", "wsdzz_game_hecheng_every_20",
                "zmxyzms_game_task", "0.2.2602241832.15", 1, "aslk2018", mapOf("gid" to "zfb_wsdzz")),
            GameTask("欢乐拼拼豆", "2021006148677438", "zfb_klppd", "klppd_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2607211740.19", 1, "aslk2018", mapOf("gid" to "zfb_klppd", "v" to "1.2.0")),
            GameTask("一找一个准", "2021006152654592", "zfb_yzygz", "yzygz_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2609141439.46", 1, "aslk2018", mapOf("gid" to "zfb_yzygz", "v" to "1.0.1")),
            GameTask("毛线消不停", "2060170000354005", "zfb_mxxbt", "mxxbt_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2601091752.55", 1, "aslk2018", mapOf("gid" to "zfb_mxxbt")),
            GameTask("农场拆毛线", "2060170000355266", "zfb_nccmx", "nccmx_game_xiaochu_every_20",
                "zmxyzms_game_task", "0.2.2602101754.41", 1, "aslk2018", mapOf("gid" to "zfb_nccmx")),
            GameTask("毛线解压馆", "2060170000366384", "zfb_mxjyg", "ylzt_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2601091624.55", 1, "aslk2018", mapOf("gid" to "zfb_mxjyg")),
            GameTask("小鸡拆毛线", "2060170000373873", "zfb_xjcmx", "xjcmx_game_xiaochu_every_10",
                "zmxyzms_game_task", "0.2.2601091612.54", 1, "aslk2018", mapOf("gid" to "zfb_xjcmx")),
            GameTask("解压放松器", "2060170000376051", "zfb_jyfsq", "jyfsq_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2601222010.25", 1, "aslk2018", mapOf("gid" to "zfb_jyfsq")),
            GameTask("全民打螺丝", "2060170000357462", "", "qmdls_game_pass_every_1",
                "lianyun_senlin_leyuan", "0.2.2602060943.11", 1, "cpchxapi", emptyMap()),
            GameTask("疯狂来解压", "2060170000357475", "", "fkljy_game_pass_every_1",
                "lianyun_senlin_leyuan", "0.2.2604020945.51", 1, "cpchxapi", emptyMap()),
            GameTask("森林解压王", "2060170000363288", "", "sljyw_game_xiaochu_every_10",
                "lianyun_senlin_leyuan", "0.2.2602060952.45", 1, "cpchxapi", emptyMap()),
            GameTask("螺丝消不停", "2060170000363314", "", "lsxbt_game_pass_every_1",
                "lianyun_senlin_leyuan", "0.2.2602060945.10", 1, "cpchxapi", emptyMap()),
            GameTask("倒水杯", "2021005187627410", "", "dsb_game_pass_every_1",
                "zhuangyuan", "0.2.2603161110.56", 1, "devourad", mapOf("appKey" to "740f34d825e4ba3bc80b856fb6a99b8b")),
            GameTask("智商乐消消", "2021006105651938", "", "",
                "zhuangyuan", "0.2.2606241540.10", 1, "devourad", mapOf("appKey" to "87e3d612cefbe59822d5c6cbde82a74f")),
            GameTask("农场对对碰", "2021004138613197", "", "ncddp_game_match_every_5",
                "zhuangyuan", "0.2.2601041522.28", 1, "dianli100", emptyMap()),
            GameTask("消费金对对碰", "2021004170626565", "", "",
                "zhuangyuan", "0.2.2504291637.15", 1, "dianli100", emptyMap()),
            GameTask("会员欢乐消", "2021005111689183", "", "ttddp_game_pass_every_1",
                "zhuangyuan", "0.2.2601041522.28", 1, "dianli100", emptyMap()),
            GameTask("汉字消消大师", "2021005132609824", "", "",
                "zmxyzms_game_task", "0.2.2512261652.33", 1, "dianwanshidai", mapOf("bgid" to "6501")),
            GameTask("这关我很行", "2021005132680209", "", "zgwhx_game_level_every_1",
                "zmxyzms_game_task", "0.2.2602130949.23", 1, "dianwanshidai", mapOf("bgid" to "6560")),
            GameTask("成语小福星", "2021005187620542", "", "",
                "zmxyzms_game_task", "0.2.2512301639.48", 1, "dianwanshidai", mapOf("bgid" to "6617")),
            GameTask("分离吧小鸡", "2021006101606146", "", "flbxj_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2601041709.52", 1, "dianwanshidai", mapOf("bgid" to "6661")),
            GameTask("套住那只羊", "2021006109602033", "", "",
                "zmxyzms_game_task", "0.2.2606301404.35", 1, "dianwanshidai", mapOf("bgid" to "6680")),
            GameTask("农场分色瓶", "2021006119624486", "", "ncfsp_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2602271145.38", 1, "dianwanshidai", mapOf("bgid" to "6705")),
            GameTask("玩个倒水", "2021006131625225", "", "wgds_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2606181714.32", 1, "dianwanshidai", mapOf("bgid" to "6798")),
            GameTask("谁来救救鹅", "2021006151633350", "", "sljje_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2607151542.41", 1, "dianwanshidai", mapOf("bgid" to "6914")),
            GameTask("你是真厉害", "2060170000378094", "", "",
                "zmxyzms_game_task", "0.2.2602031151.46", 1, "dianwanshidai", mapOf("bgid" to "6624")),
            GameTask("农场织织乐", "2060170000385587", "", "nczzl_game_xiaochu_every_20",
                "zmxyzms_game_task", "0.2.2602261702.58", 1, "dianwanshidai", mapOf("bgid" to "6704")),
            GameTask("挪了下车", "2060170000399014", "", "nlxc_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2608251627.21", 1, "dianwanshidai", mapOf("bgid" to "6969")),
            GameTask("眼力拼图", "2060170000399540", "", "ylpt_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2609161736.19", 1, "dianwanshidai", mapOf("bgid" to "6968")),
            GameTask("斗破苍穹", "2060170000393854", "", "",
                "lianyun_senlin_leyuan", "0.2.2607061716.45", 1, "flourish", mapOf("appKey" to "a307ac9468c154a3db020597397b12be")),
            GameTask("打个螺丝", "2021005139650112", "", "",
                "zmxyzms_game_task", "0.2.2602281817.3", 1, "haligame", mapOf("reportHost" to "zfbak.haligame.com"),
                listOf("dgls_game_xiaochu_every_5", "dgls_game_level_every_1")),
            GameTask("我的花园世界", "2021004163668677", "", "",
                "lianyun_senlin_leyuan", "0.2.2607091304.58", 1, "hnycgames", emptyMap(),
                listOf("wdhysj_game_richang_every_1", "wdhysj_game_gukedan_every_5")),
            GameTask("弓箭手对决", "2060170000373301", "", "gjsdj_game_win_every_1",
                "lianyun_senlin_leyuan", "0.2.2509301438.22", 1, "lumosfun", mapOf("loginHost" to "azory.lumosfun.com:8525", "reportHost" to "gjdz-zfb-def.gusspro.com:15087")),
            GameTask("浪漫餐厅", "2060170000365923", "", "",
                "nongchangleyuan", "0.2.2601281402.55", 1, "microfun", mapOf("path" to "61b45d"),
                listOf("lmct_game_merge_every_5", "lmct_game_order_every_1")),
            GameTask("一步两步", "2060170000359285", "", "",
                "lianyun_senlin_leyuan", "0.2.2607140922.20", 1, "nebula737", mapOf("nbAppKey" to "ac23221b56c6ab0d442094b67bf794c9", "nebulaAppId" to "30488", "companyId" to "69", "channelId" to "100", "sdkVersion" to "3.4.35", "gameVersion" to "1.6.13"),
                listOf("yblb_game_jineng_every_1", "yblb_game_pass_every_1")),
            GameTask("四季物语", "2021004173649916", "", "",
                "zhuangyuan", "0.2.2607172159.19", 1, "qmhd87", mapOf("reportHost" to "game2-sjwy.qmhd87.com", "channelCode" to "PA0306", "packageName" to "projectA", "sdkVersion" to "6.9.7")),
            GameTask("农场纸牌塔", "2021004130631819", "", "farmzpt_game_pass_1_misson",
                "lianyun_senlin_leyuan", "0.2.2606151503.30", 1, "qufang", mapOf("versionApp" to "4.4.5", "loginHost" to "login-01.qufang.top", "reportHost" to "game-factory-jail-001.qufang.top")),
            GameTask("天天怼方块", "2021004132661719", "", "zydfk_game_hecheng_every_5",
                "lianyun_senlin_leyuan", "0.2.2512101743.54", 1, "qufang", mapOf("versionApp" to "4.4.5", "loginHost" to "login-01.qufang.top", "reportHost" to "game-factory-jail-001.qufang.top")),
            GameTask("庄园怼方块", "2021004148695256", "", "zydfk_game_hecheng_every_5",
                "lianyun_senlin_leyuan", "0.2.2606041614.13", 1, "qufang", mapOf("versionApp" to "4.4.5", "loginHost" to "login-01.qufang.top", "reportHost" to "game-factory-jail-001.qufang.top")),
            GameTask("占城大师", "2060170000399302", "", "",
                "nongchangleyuan", "0.2.2609172112.5", 1, "raiyantway", mapOf("loginHost" to "cbqta.rayiantway.com:8525", "userType" to "31")),
            GameTask("时尚百货城", "2021006139626077", "", "",
                "lianyun_senlin_leyuan", "0.2.2607140329.43", 1, "tomato", mapOf("sdkVersion" to "1.0.0", "secret" to "24AB7463A3C613B430EDEBCEE1DFA190", "gameId" to "1100313", "platformKey" to "zfb_xyx_ssbhc", "sdkChannel" to "zfbxyx_and_ssbhcwxand_34", "cid" to "2200449"),
                listOf("ssbhc_game_dpsj_every_10", "ssbhc_game_gcyf_every_10", "ssbhc_game_hbts_every_10")),
            GameTask("青云诀之伏魔", "2021004122696780", "", "",
                "lianyun_senlin_leyuan", "0.2.2606261000.30", 1, "yyxxgame", mapOf("oid" to "rhkyx", "gcid" to "Y09020301"),
                listOf("qyj_game_pass_level_every_3", "qyj_game_jiuyoufuben_every_1")),
            GameTask("源星战域", "2021005120637256", "", "",
                "lianyun_senlin_leyuan", "0.2.2607101555.51", 1, "yyxxgame", mapOf("oid" to "zfb", "gcid" to "Y011020301", "reportHost" to "yxdasapi.yxzygame.com"),
                listOf("yxzy_game_pass_every_1", "yxzy_game_boss_every_1")),
        ).associateBy { it.appId }

        @JvmStatic
        fun fromAppId(appId: String): GameTask? = games[appId]
    }

    private fun logTask(message: String) {
        when (this) {
            Orchard_ncscc -> Log.orchard("[$title]: $message")
            Farm_ddply -> Log.farm("[$title]: $message")
            Forest_slxcc, Forest_sljyd -> Log.forest("[$title]: $message")
            else -> Log.record("[$title]: $message")
        }
    }

    private fun login(sessionCurrent: () -> Boolean): GameTaskSingleReportResult {
        if (!sessionCurrent()) return GameTaskSingleReportResult(false, "账号会话已变化或已离线", stopCurrentRound = true)
        var captureRequestId: String? = null
        var connection: HttpURLConnection? = null
        return try {
            val authCode = AuthCodeHelper.getAuthCode(appId)
            if (authCode.isNullOrBlank()) {
                return GameTaskSingleReportResult(false, "登录失败：授权码为空", stopCurrentRound = true)
            }
            check(sessionCurrent()) { "账号会话已变化或已离线" }
            val mark = AlipayMiniMarkHelper.getAlipayMiniMark(appId, version)
            val requestId = "${System.currentTimeMillis()}_${(1..350).random()}"
            val body =
                JSONObject()
                    .put("v", protocolParameters["v"] ?: version)
                    .put("code", authCode)
                    .put("pf", "zfb")
                    .put("reqId", requestId)
                    .put("gid", gid)
                    .put("version", version)
                    .toString()
            connection =
                (URL("https://gamesapi2.aslk2018.com/v2/game/login").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("alipayMiniMark", mark)
                    getDynamicUserAgent().takeIf(String::isNotBlank)?.let { setRequestProperty("User-Agent", it) }
                    setRequestProperty("x-release-type", "ONLINE")
                }

            captureRequestId = RpcTrafficCapture.recordModuleHttpRequest(connection, body)
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            RpcTrafficCapture.recordModuleHttpResponse(captureRequestId, connection, responseCode, responseText)
            captureRequestId = null
            val response = JSONObject(responseText)
            val token = if (responseCode in 200..299 && response.optInt("code") == 1) {
                response.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }
            } else null
            if (token == null) {
                GameTaskSingleReportResult(
                    false, "登录失败：HTTP=$responseCode", RpcOfflineRisk.extractCode(response),
                    responseText, stopCurrentRound = responseCode in setOf(401, 403) || RpcOfflineRisk.isOfflineRisk(response),
                )
            } else {
                check(sessionCurrent()) { "账号会话已变化或已离线" }
                GameTaskSingleReportResult(true, token = token)
            }
        } catch (error: Exception) {
            RpcTrafficCapture.recordModuleHttpError(captureRequestId, error)
            if (error is CancellationException || error is InterruptedException) throw error
            GameTaskSingleReportResult(false, "登录异常：${error.message.orEmpty()}")
        } finally {
            connection?.disconnect()
        }
    }

    internal suspend fun reportDetailed(
        eggCount: Int,
        actionFinishChannel: String = channel,
        logger: ((String) -> Unit)? = null,
        confirmProgress: (suspend () -> Boolean?)? = null,
        actionCode: String = action,
        reportCount: Int? = null,
    ): GameTaskReportResult {
        val emitLog = logger ?: ::logTask
        if (eggCount <= 0) {
            return GameTaskReportResult(eggCount, 0, 0, 0)
        }
        if (actionFinishChannel.isBlank() || actionCode.isBlank()) {
            return GameTaskReportResult(
                requestedRewards = eggCount,
                requiredSuccesses = eggCount * requestsPerEgg,
                attemptedReports = 0,
                successfulReports = 0,
                failureMessage = "action_finish_channel或结构化动作码为空",
            )
        }

        val batchSize = when {
            this == Farm_ddply && actionFinishChannel == "zhuangyuan" -> 2
            this == Forest_slxcc && actionFinishChannel == "goldenbean" -> 2
            else -> requestsPerEgg
        }
        val requiredSuccesses = reportCount ?: (eggCount * batchSize)
        if (requiredSuccesses <= 0) return GameTaskReportResult(eggCount, 0, 0, 0)
        val session = AccountSessionCoordinator.currentSession()
            ?: return GameTaskReportResult(eggCount, requiredSuccesses, 0, 0,
                failureMessage = "当前没有有效账号会话", stopCurrentRound = true)
        val executionReport = TaskExecutionReport.current()
        val coroutineContext = currentCoroutineContext()
        val sessionCurrent = {
            coroutineContext.ensureActive()
            executionReport?.ensureCurrent()
            !ApplicationHookConstants.isOffline() &&
                AccountSessionCoordinator.isCurrentSession(session.userId, session.sessionEpoch)
        }
        coroutineContext.ensureActive()
        val loginResult = if (sdk == "aslk2018") login(sessionCurrent)
            else GameTaskSingleReportResult(true)
        coroutineContext.ensureActive()
        executionReport?.ensureCurrent()
        executionReport?.recordActivity()
        val token = loginResult.token.orEmpty()
        if (!loginResult.success || !sessionCurrent()) {
            return GameTaskReportResult(
                requestedRewards = eggCount,
                requiredSuccesses = requiredSuccesses,
                attemptedReports = 0,
                successfulReports = 0,
                failureMessage = loginResult.message,
                failureCode = loginResult.code,
                failureRaw = loginResult.raw,
                stopCurrentRound = loginResult.stopCurrentRound || !sessionCurrent(),
            )
        }

        var attemptedReports = 0
        var successfulReports = 0
        var failure: GameTaskSingleReportResult? = null
        var businessConfirmed = false
        for (index in 1..requiredSuccesses) {
            currentCoroutineContext().ensureActive()
            if (!sessionCurrent()) {
                failure = GameTaskSingleReportResult(false, "账号会话已变化或已离线", stopCurrentRound = true)
                break
            }
            val result = if (sdk == "aslk2018") {
                executeSingleReport(index, requiredSuccesses, actionFinishChannel, token, actionCode, sessionCurrent)
            } else {
                val vendor = MiniGameVendorReporter.reportOnce(this, actionFinishChannel, actionCode) { sessionCurrent() }
                GameTaskSingleReportResult(vendor.success, vendor.message, vendor.code, vendor.raw, vendor.stopCurrentRound,
                    writeStarted = vendor.writeStarted, resultUnknown = vendor.resultUnknown)
            }
            if (result.writeStarted) attemptedReports++
            currentCoroutineContext().ensureActive()
            executionReport?.ensureCurrent()
            executionReport?.recordActivity()
            if (!result.success || !sessionCurrent()) {
                failure = if (sessionCurrent()) result
                    else GameTaskSingleReportResult(false, "账号会话已变化或已离线", stopCurrentRound = true)
                break
            }
            successfulReports++
            emitLog("上报已接受: $index/$requiredSuccesses，业务进度待回查")
            if (confirmProgress != null) {
                val confirmed = try {
                    confirmProgress()
                } catch (error: Exception) {
                    if (error is CancellationException || error is InterruptedException) throw error
                    failure = GameTaskSingleReportResult(false, "上报后业务查询异常：${error.message.orEmpty()}", resultUnknown = true)
                    null
                }
                currentCoroutineContext().ensureActive()
                if (confirmed == null) {
                    if (failure == null) failure = GameTaskSingleReportResult(false, "上报后业务状态未确认，停止本批上报", resultUnknown = true)
                    break
                }
                if (confirmed) {
                    businessConfirmed = true
                    emitLog("业务状态已确认达成，结束本批上报")
                    break
                }
            }
        }
        return GameTaskReportResult(
            requestedRewards = eggCount,
            requiredSuccesses = requiredSuccesses,
            attemptedReports = attemptedReports,
            successfulReports = successfulReports,
            failureMessage = failure?.message.orEmpty(),
            failureCode = failure?.code.orEmpty(),
            failureRaw = failure?.raw.orEmpty(),
            stopCurrentRound = failure?.stopCurrentRound == true,
            businessConfirmed = businessConfirmed,
            resultUnknown = failure?.resultUnknown == true,
        )
    }

    private fun executeSingleReport(
        current: Int,
        total: Int,
        actionFinishChannel: String,
        token: String,
        actionCode: String,
        sessionCurrent: () -> Boolean,
    ): GameTaskSingleReportResult {
        var captureRequestId: String? = null
        var connection: HttpURLConnection? = null
        var writeStarted = false
        return try {
            check(sessionCurrent()) { "账号会话已变化或已离线" }
            val mark = AlipayMiniMarkHelper.getAlipayMiniMark(appId, version)
            val body =
                JSONObject()
                    .put("v", protocolParameters["v"] ?: version)
                    .put("version", version)
                    .put("reqId", "${System.currentTimeMillis()}_${(10..99).random()}")
                    .put("gid", gid)
                    .put("action_code", actionCode)
                    .put("action_finish_channel", actionFinishChannel)
                    .toString()
            connection =
                (URL("https://gamesapi2.aslk2018.com/v2/zfb/taskReport").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("authorization", token)
                    setRequestProperty("alipayMiniMark", mark)
                    setRequestProperty("Content-Type", "application/json")
                    getDynamicUserAgent().takeIf(String::isNotBlank)?.let { setRequestProperty("User-Agent", it) }
                    setRequestProperty("x-release-type", "ONLINE")
                    setRequestProperty("referer", "https://$appId.hybrid.alipay-eco.com/$appId/$version/index.html")
                }

            captureRequestId = RpcTrafficCapture.recordModuleHttpRequest(connection, body)
            check(sessionCurrent()) { "账号会话已变化或已离线" }
            writeStarted = true
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            RpcTrafficCapture.recordModuleHttpResponse(captureRequestId, connection, responseCode, responseText)
            captureRequestId = null
            val response = JSONObject(responseText)
            if (responseCode in 200..299 && response.optInt("code") == 1) {
                GameTaskSingleReportResult(success = true, writeStarted = true)
            } else {
                GameTaskSingleReportResult(
                    success = false,
                    message = "上报失败：进度=$current/$total HTTP=$responseCode",
                    code = RpcOfflineRisk.extractCode(response),
                    raw = responseText,
                    stopCurrentRound = responseCode in setOf(401, 403) || RpcOfflineRisk.isOfflineRisk(response),
                    writeStarted = true,
                )
            }
        } catch (error: Exception) {
            RpcTrafficCapture.recordModuleHttpError(captureRequestId, error)
            if (error is CancellationException || error is InterruptedException) throw error
            GameTaskSingleReportResult(
                success = false,
                message = "上报异常：进度=$current/$total ${error.message.orEmpty()}",
                writeStarted = writeStarted, resultUnknown = writeStarted,
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun getDynamicUserAgent(): String {
        val systemUserAgent = System.getProperty("http.agent").orEmpty()
        val alipayVersion = ApplicationHook.alipayVersion.versionString
        return buildString {
            append(systemUserAgent)
            if (alipayVersion.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append("AliApp(AP/$alipayVersion) AlipayClient/$alipayVersion")
            }
        }
    }
}

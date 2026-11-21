package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.AccountSessionIdentity
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
    val reportLimit: Int,
    val attemptedReports: Int,
    val successfulReports: Int,
    val failureMessage: String = "",
    val failureCode: String = "",
    val failureRaw: String = "",
    val stopCurrentRound: Boolean = false,
    val businessConfirmed: Boolean = false,
) {
    val accepted: Boolean
        get() = failureMessage.isBlank() && (businessConfirmed || successfulReports >= reportLimit)
}

private data class GameTaskSingleReportResult(
    val success: Boolean,
    val message: String = "",
    val code: String = "",
    val raw: String = "",
    val stopCurrentRound: Boolean = false,
    val token: String? = null,
    val writeStarted: Boolean = false,
    val authenticationFailed: Boolean = false,
)

class GameTask private constructor(
    val title: String,
    val appId: String,
    val gid: String,
    val action: String,
    val channel: String,
    val version: String,
    val sdk: String = "aslk2018",
    val protocolParameters: Map<String, String> = emptyMap(),
    val actions: List<String> = listOf(action).filter(String::isNotBlank),
    private val reportHostAliases: Set<String> = emptySet(),
) {
    companion object {
        val Orchard_ncscc = GameTask("农场乐园:农场上车车", "2060170000356601", "zfb_ncscc", "ncscc_game_kaiche_every_10",
            "nongchangleyuan", "1.0.2", "aslk2018",
            mapOf("gid" to "zfb_ncscc", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
            listOf("ncscc_game_kaiche_every_10"))
        val Farm_ddply = GameTask("庄园乐园:对对碰乐园", "2021004149679303", "zfb_ddply", "ddply_game_xiaochu_every_5",
            "zhuangyuan", "1.0.14", "aslk2018",
            mapOf("gid" to "zfb_ddply", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
            listOf("ddply_game_xiaochu_every_5"))
        val Forest_slxcc = GameTask("森林乐园:森林小车车", "2060170000363691", "zfb_slxcc", "slxcc_game_kaiche_every_10",
            "lianyun_senlin_leyuan", "1.0.1", "aslk2018",
            mapOf("gid" to "zfb_slxcc", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
            listOf("slxcc_game_kaiche_every_10"))
        val Forest_sljyd = GameTask("森林乐园:森林救援队(能量雨)", "2021005113684028", "zfb_sljydx", "sljyd_game_xiaochu_every_10",
            "lianyun_senlin_leyuan", "1.0.1", "aslk2018",
            mapOf("gid" to "zfb_sljydx", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
            listOf("sljyd_game_xiaochu_every_10"))

        // 动作使用服务端合同或该游戏已确认的厂商事件。
        private val games = listOf(
            Orchard_ncscc, Farm_ddply, Forest_slxcc, Forest_sljyd,
            GameTask("捞个菜", "2021000110660562", "zfb_lgc", "lgc_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2606221937.35", "aslk2018",
                mapOf("gid" to "zfb_lgc", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("lgc_game_pass_every_1")),
            GameTask("找不停", "2021000111632448", "zfb_zbt", "zbt_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2606181421.5", "aslk2018",
                mapOf("gid" to "zfb_zbt", "v" to "1.0.0", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("zbt_game_pass_every_1")),
            GameTask("超级解压馆", "2021004126650405", "zfb_cjjyg", "cjjyg_game_hecheng_every_5",
                "zmxyzms_game_task", "0.2.2507301514.41", "aslk2018",
                mapOf("gid" to "zfb_cjjyg", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("cjjyg_game_hecheng_every_5")),
            GameTask("玩梗高手", "2021004128666655", "zfb_wggs", "wggs_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2603171940.31", "aslk2018",
                mapOf("gid" to "zfb_wggs", "v" to "1.0.4", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("wggs_game_pass_every_1")),
            GameTask("水滴排排序", "2021004131628794", "zfb_sdppxx", "sdppx_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2512171908.31", "aslk2018",
                mapOf("gid" to "zfb_sdppxx", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("sdppx_game_pass_every_1")),
            GameTask("火锅消不停", "2021004135688960", "zfb_hgxbt", "hgxbt_game_xiaochu_every_5",
                "zmxyzms_game_task", "0.2.2407091636.11", "aslk2018",
                mapOf("gid" to "zfb_hgxbt", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("hgxbt_game_xiaochu_every_5")),
            GameTask("农场解压馆", "2021004138632167", "zfb_ncjyg", "ncjyg_game_hecheng_every_5",
                "zmxyzms_game_task", "0.2.2511281734.55", "aslk2018",
                mapOf("gid" to "zfb_ncjyg", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("ncjyg_game_hecheng_every_5")),
            GameTask("螺丝庄园", "2021004150621264", "zfb_lszy", "lszy_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2512051527.46", "aslk2018",
                mapOf("gid" to "zfb_lszy", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("lszy_game_pass_every_1")),
            GameTask("农场解螺丝", "2021004153698112", "zfb_ncjls", "ncjls_game_xiaochu_every_5",
                "zmxyzms_game_task", "0.2.2601231520.27", "aslk2018",
                mapOf("gid" to "zfb_ncjls", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("ncjls_game_xiaochu_every_5")),
            GameTask("会员连不停", "2021004158640815", "zfb_hylbt", "qsgj_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2509281117.51", "aslk2018",
                mapOf("gid" to "zfb_hylbt", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("qsgj_game_pass_every_1")),
            GameTask("玩个儿毛线", "2021004159604141", "zfb_wgemx", "wgemx_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2512081218.33", "aslk2018",
                mapOf("gid" to "zfb_wgemx", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("wgemx_game_pass_every_1")),
            GameTask("拿下100关", "2021004164603638", "zfb_nx100g", "nxybg_game_yingzhang_every_1",
                "zmxyzms_game_task", "0.2.2606112022.39", "aslk2018",
                mapOf("gid" to "zfb_nx100g", "v" to "1.0.0", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("nxybg_game_yingzhang_every_1", "nxybg_game_pass_every_1")),
            GameTask("离谱的汉字", "2021004164632103", "zfb_lpdhz1", "",
                "zmxyzms_game_task", "0.2.2507181129.29", "aslk2018",
                mapOf("gid" to "zfb_lpdhz1", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("lpdhz_game_pass_every_1", "lpdhz_game_xiaochu_every_10")),
            GameTask("农场文字秀", "2021004170628696", "zfb_ncwzx", "ncwzx_game_hecheng_every_10",
                "zmxyzms_game_task", "0.2.2601192247.15", "aslk2018",
                mapOf("gid" to "zfb_ncwzx", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("ncwzx_game_hecheng_every_10")),
            GameTask("农场大聪明", "2021004172624044", "zfb_ncdcm", "ncdcm_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2602101831.24", "aslk2018",
                mapOf("gid" to "zfb_ncdcm", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("ncdcm_game_pass_every_1")),
            GameTask("会员爱动脑", "2021004194616336", "zfb_hyadn", "hyadn_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2506261635.47", "aslk2018",
                mapOf("gid" to "zfb_hyadn", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("hyadn_game_pass_every_1")),
            GameTask("森林萌萌消", "2021005112605978", "zfb_slmmxx", "slmmx_game_xiaochu_every_10",
                "zmxyzms_game_task", "0.2.2601281827.8", "aslk2018",
                mapOf("gid" to "zfb_slmmxx", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("slmmx_game_xiaochu_every_10")),
            GameTask("诗意绿洲", "2021005112647931", "zfb_sylz", "sylz_game_hecheng_every_10",
                "zmxyzms_game_task", "0.2.2606091857.22", "aslk2018",
                mapOf("gid" to "zfb_sylz", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("sylz_game_hecheng_every_10", "sylz_game_pass_every_1")),
            GameTask("森林解压馆", "2021005113622761", "zfb_sljyg", "sljyg_game_hecheng_every_1",
                "zmxyzms_game_task", "0.2.2509301530.27", "aslk2018",
                mapOf("gid" to "zfb_sljyg", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("sljyg_game_hecheng_every_1")),
            GameTask("找个喵", "2021005172696439", "zfb_zgm", "zgm_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2601131551.50", "aslk2018",
                mapOf("gid" to "zfb_zgm", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("zgm_game_pass_every_1")),
            GameTask("挪了个箭", "2021006113622705", "zfb_nlgj", "nlgj_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2605252023.30", "aslk2018",
                mapOf("gid" to "zfb_nlgj", "v" to "1.0.5", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("nlgj_game_pass_every_1", "nlgj_game_jiantou_every_50")),
            GameTask("玩水大作战", "2021006117687842", "zfb_wsdzz", "wsdzz_game_hecheng_every_20",
                "zmxyzms_game_task", "0.2.2602241832.15", "aslk2018",
                mapOf("gid" to "zfb_wsdzz", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("wsdzz_game_hecheng_every_20")),
            GameTask("欢乐拼拼豆", "2021006148677438", "zfb_klppd", "klppd_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2607211740.19", "aslk2018",
                mapOf("gid" to "zfb_klppd", "v" to "1.2.0", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("klppd_game_pass_every_1")),
            GameTask("一找一个准", "2021006152654592", "zfb_yzygz", "yzygz_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2609141439.46", "aslk2018",
                mapOf("gid" to "zfb_yzygz", "v" to "1.0.1", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("yzygz_game_pass_every_1")),
            GameTask("毛线消不停", "2060170000354005", "zfb_mxxbt", "mxxbt_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2601091752.55", "aslk2018",
                mapOf("gid" to "zfb_mxxbt", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("mxxbt_game_pass_every_1")),
            GameTask("农场拆毛线", "2060170000355266", "zfb_nccmx", "nccmx_game_xiaochu_every_20",
                "zmxyzms_game_task", "0.2.2602101754.41", "aslk2018",
                mapOf("gid" to "zfb_nccmx", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("nccmx_game_xiaochu_every_20")),
            GameTask("毛线解压馆", "2060170000366384", "zfb_mxjyg", "ylzt_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2601091624.55", "aslk2018",
                mapOf("gid" to "zfb_mxjyg", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("ylzt_game_pass_every_1")),
            GameTask("小鸡拆毛线", "2060170000373873", "zfb_xjcmx", "xjcmx_game_xiaochu_every_10",
                "zmxyzms_game_task", "0.2.2601091612.54", "aslk2018",
                mapOf("gid" to "zfb_xjcmx", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("xjcmx_game_xiaochu_every_10")),
            GameTask("解压放松器", "2060170000376051", "zfb_jyfsq", "jyfsq_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2601222010.25", "aslk2018",
                mapOf("gid" to "zfb_jyfsq", "loginUrl" to "https://gamesapi2.aslk2018.com/v2/game/login", "submitUrl" to "https://gamesapi2.aslk2018.com/v2/zfb/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("jyfsq_game_pass_every_1")),
            GameTask("全民打螺丝", "2060170000357462", "", "qmdls_game_pass_every_1",
                "lianyun_senlin_leyuan", "0.2.2602060943.11", "cpchxapi",
                mapOf("loginUrl" to "https://cpchxapi.dianwanshidai.com/app-api/game/auth/login-ali", "submitUrl" to "https://cpchxapi.dianwanshidai.com/app-api/game/analytics/ali-report", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("qmdls_game_pass_every_1")),
            GameTask("疯狂来解压", "2060170000357475", "", "fkljy_game_pass_every_1",
                "lianyun_senlin_leyuan", "0.2.2604020945.51", "cpchxapi",
                mapOf("loginUrl" to "https://cpchxapi.dianwanshidai.com/app-api/game/auth/login-ali", "submitUrl" to "https://cpchxapi.dianwanshidai.com/app-api/game/analytics/ali-report", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("fkljy_game_pass_every_1")),
            GameTask("森林解压王", "2060170000363288", "", "sljyw_game_xiaochu_every_10",
                "lianyun_senlin_leyuan", "0.2.2602060952.45", "cpchxapi",
                mapOf("loginUrl" to "https://cpchxapi.dianwanshidai.com/app-api/game/auth/login-ali", "submitUrl" to "https://cpchxapi.dianwanshidai.com/app-api/game/analytics/ali-report", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("sljyw_game_xiaochu_every_10")),
            GameTask("螺丝消不停", "2060170000363314", "", "lsxbt_game_pass_every_1",
                "lianyun_senlin_leyuan", "0.2.2602060945.10", "cpchxapi",
                mapOf("loginUrl" to "https://cpchxapi.dianwanshidai.com/app-api/game/auth/login-ali", "submitUrl" to "https://cpchxapi.dianwanshidai.com/app-api/game/analytics/ali-report", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("lsxbt_game_pass_every_1")),
            GameTask("倒水杯", "2021005187627410", "", "dsb_game_pass_every_1",
                "zhuangyuan", "0.2.2603161110.56", "devourad",
                mapOf("appKey" to "740f34d825e4ba3bc80b856fb6a99b8b", "loginUrl" to "https://api.devourad.com/alipay/gameLogin", "submitUrl" to "https://api.devourad.com/alipay/gameReport", "loginMethod" to "fn#327", "submitMethod" to "fn#330"),
                listOf("dsb_game_pass_every_1")),
            GameTask("智商乐消消", "2021006105651938", "", "zslxx_game_xiaochu_every_10",
                "zhuangyuan", "0.2.2606241540.10", "devourad",
                mapOf("appKey" to "87e3d612cefbe59822d5c6cbde82a74f", "loginUrl" to "https://api.devourad.com/alipay/gameLogin", "submitUrl" to "https://api.devourad.com/alipay/gameReport", "loginMethod" to "fn#327", "submitMethod" to "fn#330"),
                listOf("zslxx_game_xiaochu_every_10", "zslxx_game_pass_every_1")),
            GameTask("农场对对碰", "2021004138613197", "", "ncddp_game_match_every_5",
                "zhuangyuan", "0.2.2601041522.28", "dianli100",
                mapOf("loginUrl" to "https://alifunc.dianli100.com/majiang/alipay/", "submitUrl" to "https://alifunc.dianli100.com/majiang/alipay/", "loginMethod" to "POST", "submitMethod" to "POST", "gameId" to "farm"),
                listOf("ncddp_game_match_every_5")),
            GameTask("消费金对对碰", "2021004170626565", "", "",
                "zhuangyuan", "0.2.2504291637.15", "dianli100",
                mapOf("loginUrl" to "https://alifunc.dianli100.com/majiang/alipay/", "submitUrl" to "https://alifunc.dianli100.com/majiang/alipay/", "loginMethod" to "POST", "submitMethod" to "POST", "gameId" to "xiaofeijin"),
                listOf("xfjddp_game_pass_every_1", "xfjddp_game_hecheng_every_10")),
            GameTask("会员欢乐消", "2021005111689183", "", "ttddp_game_pass_every_1",
                "zhuangyuan", "0.2.2601041522.28", "dianli100",
                mapOf("loginUrl" to "https://alifunc.dianli100.com/majiang/alipay/", "submitUrl" to "https://alifunc.dianli100.com/majiang/alipay/", "loginMethod" to "POST", "submitMethod" to "POST", "gameId" to "huiyuanhuanleixiao"),
                listOf("ttddp_game_pass_every_1")),
            GameTask("汉字消消大师", "2021005132609824", "", "hzxxds_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2512261652.33", "dianwanshidai",
                mapOf("bgid" to "6501", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST", "channelContainer" to "query"),
                listOf("hzxxds_game_pass_every_1", "hzxxds_game_xiaochu_every_10")),
            GameTask("这关我很行", "2021005132680209", "", "zgwhx_game_level_every_1",
                "zmxyzms_game_task", "0.2.2602130949.23", "dianwanshidai",
                mapOf("bgid" to "6560", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("zgwhx_game_level_every_1")),
            GameTask("成语小福星", "2021005187620542", "", "cyxfx_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2512301639.48", "dianwanshidai",
                mapOf("bgid" to "6617", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST", "channelContainer" to "query"),
                listOf("cyxfx_game_pass_every_1", "cyxfx_game_xiaochu_every_10")),
            GameTask("分离吧小鸡", "2021006101606146", "", "flbxj_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2601041709.52", "dianwanshidai",
                mapOf("bgid" to "6661", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("flbxj_game_pass_every_1")),
            GameTask("套住那只羊", "2021006109602033", "", "tznzy_game_daoju_every_1",
                "zmxyzms_game_task", "0.2.2606301404.35", "dianwanshidai",
                mapOf("bgid" to "6680", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("tznzy_game_daoju_every_1", "tznzy_game_pass_every_1", "tznzy_game_sheep_every_10")),
            GameTask("农场分色瓶", "2021006119624486", "", "ncfsp_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2602271145.38", "dianwanshidai",
                mapOf("bgid" to "6705", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("ncfsp_game_pass_every_1")),
            GameTask("玩个倒水", "2021006131625225", "", "wgds_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2606181714.32", "dianwanshidai",
                mapOf("bgid" to "6798", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("wgds_game_pass_every_1")),
            GameTask("谁来救救鹅", "2021006151633350", "", "sljje_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2607151542.41", "dianwanshidai",
                mapOf("bgid" to "6914", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("sljje_game_pass_every_1")),
            GameTask("你是真厉害", "2060170000378094", "", "nszlh_game_xiaochu_every_20",
                "zmxyzms_game_task", "0.2.2602031151.46", "dianwanshidai",
                mapOf("bgid" to "6624", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("nszlh_game_xiaochu_every_20", "nszlh_game_pass_every_1", "nszlh_game_daoju_every_1")),
            GameTask("农场织织乐", "2060170000385587", "", "nczzl_game_xiaochu_every_20",
                "zmxyzms_game_task", "0.2.2602261702.58", "dianwanshidai",
                mapOf("bgid" to "6704", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("nczzl_game_xiaochu_every_20")),
            GameTask("挪了下车", "2060170000399014", "", "nlxc_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2608251627.21", "dianwanshidai",
                mapOf("bgid" to "6969", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("nlxc_game_pass_every_1")),
            GameTask("眼力拼图", "2060170000399540", "", "ylpt_game_pass_every_1",
                "zmxyzms_game_task", "0.2.2609161736.19", "dianwanshidai",
                mapOf("bgid" to "6968", "loginUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/user/login", "submitUrl" to "https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("ylpt_game_pass_every_1")),
            GameTask("斗破苍穹", "2060170000393854", "", "dpcq_game_pass_every_1",
                "lianyun_senlin_leyuan", "0.2.2607061716.45", "flourish",
                mapOf("appKey" to "a307ac9468c154a3db020597397b12be", "loginUrl" to "https://sdk.flourish-fs.com/QuickGame/login", "submitUrl" to "https://sdk.flourish-fs.com/QuickGame/callMethod", "loginMethod" to "POST", "submitMethod" to "POST", "gameId" to "192", "reportHost" to "sdk.flourish-fs.com"),
                listOf("dpcq_game_pass_every_1", "dpcq_game_shop_every_1", "dpcq_game_douji_every_1", "dpcq_game_take_every_1", "dpcq_game_zhaomu_every_1", "dpcq_game_huoyue_every_5", "dpcq_game_shengji_every_1")),
            GameTask("打个螺丝", "2021005139650112", "", "",
                "zmxyzms_game_task", "0.2.2602281817.3", "haligame",
                mapOf("loginUrl" to "https://zf.haligame.com/ajax/get_openid.php", "submitUrl" to "https://zf.haligame.com/ajax/user_action_submit.php", "loginMethod" to "GET", "submitMethod" to "POST_FORM", "loginHost" to "zf.haligame.com", "reportHost" to "zf.haligame.com"),
                listOf("dgls_game_xiaochu_every_5", "dgls_game_level_every_1", "dgls_game_daoju_every_1"), reportHostAliases = setOf("zfbak.haligame.com")),
            GameTask("我的花园世界", "2021004163668677", "", "",
                "lianyun_senlin_leyuan", "0.2.2607091304.58", "hnycgames",
                mapOf("loginUrl" to "https://h5sdk.hnycgames.cn/Channel/login/yxtGame/wdhysj/yxtChannel/myxyx/yxtSubChannel/myxyx", "submitUrl" to "https://h5sdk.hnycgames.cn/channel/index/yxtMethod/reportaction/yxtGame/wdhysj/yxtChannel/myxyx/yxtSubChannel/myxyx", "loginMethod" to "POST_FORM", "submitMethod" to "POST_FORM"),
                listOf("wdhysj_game_richang_every_1", "wdhysj_game_gukedan_every_5", "wdhysj_game_zhuxian_every_1", "wdhysj_game_dingdan_every_2")),
            GameTask("弓箭手对决", "2060170000373301", "", "gjsdj_game_win_every_1",
                "lianyun_senlin_leyuan", "0.2.2509301438.22", "lumosfun",
                mapOf("loginHost" to "azory.lumosfun.com:8525", "reportHost" to "gjdz-zfb-def.gusspro.com:15087", "loginUrl" to "https://azory.lumosfun.com:8525/MiniGameServer/minigame/login.do", "submitUrl" to "https://gjdz-zfb-def.gusspro.com:15087/mini-alipay/event/submit", "loginMethod" to "GET", "submitMethod" to "POST"),
                listOf("gjsdj_game_win_every_1")),
            GameTask("浪漫餐厅", "2060170000365923", "", "",
                "nongchangleyuan", "0.2.2601281402.55", "microfun",
                mapOf("path" to "61b45d", "loginUrl" to "https://xyx-txeo.cliapi.microfun.cn/zhifubao/getDeviceInfo/61b45d", "submitUrl" to "https://xyx-txeo.cliapi.microfun.cn/zhifubao/uploadActionNew/61b45d", "loginMethod" to "GET", "submitMethod" to "POST_FORM"),
                listOf("lmct_game_merge_every_5", "lmct_game_order_every_1")),
            GameTask("一步两步", "2060170000359285", "", "",
                "lianyun_senlin_leyuan", "0.2.2607140922.20", "nebula737",
                mapOf("nbAppKey" to "ac23221b56c6ab0d442094b67bf794c9", "nebulaAppId" to "30488", "companyId" to "69", "channelId" to "100", "sdkVersion" to "3.4.35", "gameVersion" to "1.6.13", "loginUrl" to "https://gate-nebula.737.com/account/user/detail", "submitUrl" to "https://api-combine.feiyu.com/v1/alipayMiniGame/behavior/report", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("yblb_game_jineng_every_1", "yblb_game_pass_every_1", "yblb_game_gaojixuyuan_every_1")),
            GameTask("四季物语", "2021004173649916", "", "sjwy_game_hecheng_every_20",
                "zhuangyuan", "0.2.2607172159.19", "qmhd87",
                mapOf("reportHost" to "game2-sjwy.qmhd87.com", "channelCode" to "PA0306", "packageName" to "projectA", "sdkVersion" to "6.9.7", "loginUrl" to "https://game2-sjwy.qmhd87.com/api/v1/ap/openid/", "submitUrl" to "https://game2-sjwy.qmhd87.com/api/v1/ap/report/", "loginMethod" to "POST", "submitMethod" to "POST", "action.goldenbean" to "sjwy_game_dingdan_every_1"),
                listOf("sjwy_game_hecheng_every_20", "sjwy_game_dingdan_every_1", "sjwy_game_jiaju_every_1")),
            GameTask("农场纸牌塔", "2021004130631819", "", "farmzpt_game_pass_1_misson",
                "lianyun_senlin_leyuan", "0.2.2606151503.30", "qufang",
                mapOf("versionApp" to "4.4.5", "loginHost" to "login-01.qufang.top", "reportHost" to "game-factory-jail-001.qufang.top", "loginUrl" to "https://login-01.qufang.top/account/login", "submitUrl" to "https://game-factory-jail-001.qufang.top/platform_service/increment/basic", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("farmzpt_game_pass_1_misson")),
            GameTask("天天怼方块", "2021004132661719", "", "zydfk_game_hecheng_every_5",
                "lianyun_senlin_leyuan", "0.2.2512101743.54", "qufang",
                mapOf("versionApp" to "4.4.5", "loginHost" to "login-01.qufang.top", "reportHost" to "game-factory-jail-001.qufang.top", "loginUrl" to "https://login-01.qufang.top/account/login", "submitUrl" to "https://game-factory-jail-001.qufang.top/platform_service/increment/basic", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("zydfk_game_hecheng_every_5")),
            GameTask("庄园怼方块", "2021004148695256", "", "zydfk_game_hecheng_every_5",
                "lianyun_senlin_leyuan", "0.2.2606041614.13", "qufang",
                mapOf("versionApp" to "4.4.5", "loginHost" to "login-01.qufang.top", "reportHost" to "game-factory-jail-001.qufang.top", "loginUrl" to "https://login-01.qufang.top/account/login", "submitUrl" to "https://game-factory-jail-001.qufang.top/platform_service/increment/basic", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("zydfk_game_hecheng_every_5")),
            GameTask("占城大师", "2060170000399302", "", "zcds_game_duiju_every_1",
                "nongchangleyuan", "0.2.2609172112.5", "raiyantway",
                mapOf("loginHost" to "cbqta.rayiantway.com:8525", "userType" to "31", "loginUrl" to "https://cbqta.rayiantway.com:8525/MiniGameServer/minigame/login.do", "submitUrl" to "https://cbqta.rayiantway.com:8525/MiniGameServer/alipay/reportGameAction", "loginMethod" to "GET", "submitMethod" to "POST_FORM"),
                listOf("zcds_game_duiju_every_1", "zcds_game_baoxiang_every_1", "zcds_game_kapai_every_1")),
            GameTask("时尚百货城", "2021006139626077", "", "",
                "lianyun_senlin_leyuan", "0.2.2607140329.43", "tomato",
                mapOf("sdkVersion" to "1.0.0", "secret" to "24AB7463A3C613B430EDEBCEE1DFA190", "gameId" to "1100313", "platformKey" to "zfb_xyx_ssbhc", "sdkChannel" to "zfbxyx_and_ssbhcwxand_34", "cid" to "2200449", "loginUrl" to "https://ssbhc-minigame-sdk.tomatogames.com/init/v2/sdk/initDevices", "submitUrl" to "https://ssbhc-minigame-sdk.tomatogames.com/init/platform/event/push", "loginMethod" to "POST_FORM", "submitMethod" to "POST"),
                listOf("ssbhc_game_dpsj_every_10", "ssbhc_game_gcyf_every_10", "ssbhc_game_hbts_every_10", "ssbhc_game_rw_every_1", "ssbhc_game_pass_every_1")),
            GameTask("青云诀之伏魔", "2021004122696780", "", "",
                "lianyun_senlin_leyuan", "0.2.2606261000.30", "yyxxgame",
                mapOf("apiKey" to "5t5s9a18bcfdfb8a4a4a10c546dg8r58", "oid" to "rhkyx", "gcid" to "Y09020301", "submitUrl" to "https://fumodasapi.yyxxgame.com/alipay/alipay_game_report", "submitMethod" to "POST"),
                listOf("qyj_game_pass_level_every_3", "qyj_game_jiuyoufuben_every_1", "qyj_game_mission_every_1", "qyj_game_level_every_1", "qyj_game_fengmoguta_every_1")),
            GameTask("源星战域", "2021005120637256", "", "",
                "lianyun_senlin_leyuan", "0.2.2607101555.51", "yyxxgame",
                mapOf("apiKey" to "8f882d04f3d064c06ef40d76b77143a1", "oid" to "zfb", "gcid" to "Y011020301", "reportHost" to "yxdasapi.yxzygame.com", "submitUrl" to "https://yxdasapi.yxzygame.com/alipay/alipay_game_report", "submitMethod" to "POST"),
                listOf("yxzy_game_pass_every_1", "yxzy_game_boss_every_1", "yxzy_game_mission_every_1")),
            GameTask("西游除妖", "2021004135635323", "", "",
                "", "0.2.2607020611.9", "",
                mapOf("sdkVersion" to "1.9.7", "gameVersion" to "1.0", "loginUrl" to "https://member.aidalan.com/v2/oauth/virtualCode;https://member.aidalan.com/v2/oauth/thirdOpenAuthorize", "loginMethod" to "POST", "submitUrl" to "https://game.aidalan.com/v1/miniGame/gameAction", "submitMethod" to "POST"),
                listOf("xycy_game_pass_every_1", "xycy_game_hulu_every_5")),
            GameTask("无尽探险队", "2021006101635241", "", "wjtxd_game_pass_every_1",
                "", "0.2.2607161416.35", "trueplay",
                mapOf("loginUrl" to "https://minigame-cli.youwongames.com/biu-ccb/", "submitUrl" to "https://center.trueplay.cn/mGame/userAction/", "loginMethod" to "POST", "submitMethod" to "GET", "site" to "wjtxdzfbxyx_android", "siteKey" to "f0d7bb250aa1d731a463ed50f61c6e3f", "aid" to "936299151563628366", "sdkAppId" to "dae9cea8-d53c-4655-a61b-8e46794b7b5c", "channelId" to "47"),
                listOf("wjtxd_game_pass_every_1")),
            GameTask("全境守卫", "2021005161697013", "", "",
                "", "0.2.2608171810.43", "",
                mapOf("gameVersion" to "1.0.73.0", "loginUrl" to "https://h5apiv2.hnlyplay.cn/login/login_alimg.php?appid=", "loginMethod" to "POST"),
                listOf("qjsw_game_pass_every_1", "qjsw_game_jianzhu_every_1", "qjsw_game_puzhaohuan_every_1", "qjsw_game_baoxiang_every_1")),
            GameTask("洪荒仙韵", "2060170000358842", "", "",
                "", "0.2.2605141416.45", "",
                emptyMap(),
                listOf("hhxy_game_guimenguan_every_1", "hhxy_game_shiwang_every_5", "hhxy_game_jingjichang_every_1", "hhxy_game_fuben_every_1")),
            GameTask("九梦仙域", "2060170000352323", "", "",
                "", "0.2.2606111657.38", "youyantech",
                mapOf("submitUrl" to "https://cl1-wc08.youyantech.com/hcz/incrementgameaction.php", "submitMethod" to "GET"),
                listOf("jmxy_game_tianguan_every_1", "jmxy_game_mowang_every_1")),
            GameTask("小鸡方块拼图", "2021005197696986", "", "",
                "", "0.2.2603171953.39", "jingyou",
                mapOf("appKey" to "ofHCFJVjdl7Wr169G5uSOmXjKaMEIniT", "loginUrl" to "https://pt-sdk-api.jingyougz.com/api/v1/app/init", "submitUrl" to "https://pt-sdk-api.jingyougz.com/api/v1/platform/action_report", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("xjfkpt_game_jingdian_every_500", "xjfkpt_game_pass_every_1")),
            GameTask("解压俄罗斯方块", "2021005176645204", "", "jyelsfk_game_pass_every_1",
                "", "0.2.2607241635.14", "jingyou",
                mapOf("appKey" to "ofHCFJVjdl7Wr169G5uSOmXjKaMEIniT", "loginUrl" to "https://pt-sdk-api.jingyougz.com/api/v1/app/init", "submitUrl" to "https://pt-sdk-api.jingyougz.com/api/v1/platform/action_report", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("jyelsfk_game_pass_every_1")),
            GameTask("超元火柴人", "2060170000358895", "", "cyqhcr_game_renyifuben_every_1",
                "", "0.2.2501072053.14", "soulgame",
                mapOf("appKey" to "sg1d1bf57c8def15ee", "loginUrl" to "https://api-alipay.soulgame.mobi/ThreeLogin/index", "submitUrl" to "https://api-alipay.soulgame.mobi/alipay_qg_report_game_action", "loginMethod" to "POST_FORM", "submitMethod" to "POST_FORM"),
                listOf("cyqhcr_game_renyifuben_every_1")),
            GameTask("指尖王国", "2060170000377298", "", "",
                "", "0.2.2609031704.54", "h5i13yx",
                mapOf("appKey" to "10002", "loginUrl" to "https://h5i.13yx.com/mini/user/login", "submitUrl" to "https://h5i.13yx.com/mini/data/taskReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("3", "2")),
            GameTask("货柜消消消", "2021004134649102", "", "hgxxx_game_game_pass_every_1",
                "", "0.2.2509031342.19", "igame58",
                mapOf("loginUrl" to "https://aam.igame58.com/mini/three/getOpenId", "submitUrl" to "https://aam.igame58.com/mini/three/ali/action/submit", "loginMethod" to "GET", "submitMethod" to "POST", "umid" to "64dca6225488fe7b3af4f865"),
                listOf("hgxxx_game_game_pass_every_1")),
            GameTask("小鸡货柜消除", "2021004199670767", "", "xjhgxc_game_game_pass_every_1",
                "", "0.2.2512171756.18", "igame58",
                mapOf("loginUrl" to "https://aam.igame58.com/mini/three/getOpenId", "submitUrl" to "https://aam.igame58.com/mini/three/ali/action/submit", "loginMethod" to "GET", "submitMethod" to "POST", "umid" to "qgame_60829c805844f15425e6ffe4"),
                listOf("xjhgxc_game_game_pass_every_1")),
            GameTask("货柜趣消消", "2021004145605636", "", "hgqxx_game_game_pass_every_1",
                "", "0.2.2510211823.14", "igame58",
                mapOf("loginUrl" to "https://aam.igame58.com/mini/three/getOpenId", "submitUrl" to "https://aam.igame58.com/mini/three/ali/action/submit", "loginMethod" to "GET", "submitMethod" to "POST", "umid" to "qgame_62a29dd705844627b5a932ef"),
                listOf("hgqxx_game_game_pass_every_1")),
            GameTask("闯关挪挪车", "2021004148696299", "", "cgnnc_game_pass_every_1",
                "", "0.2.2510311929.40", "igame58",
                mapOf("loginUrl" to "https://aam.igame58.com/mini/three/getOpenId", "submitUrl" to "https://aam.igame58.com/mini/three/ali/action/submit", "loginMethod" to "GET", "submitMethod" to "POST", "umid" to "62820a8a30a4f67780dc02f6"),
                listOf("cgnnc_game_pass_every_1")),
            GameTask("小鸡挪车", "2021004199674631", "", "xjnc_game_pass_every_1",
                "", "0.2.2512171758.11", "igame58",
                mapOf("loginUrl" to "https://aam.igame58.com/mini/three/getOpenId", "submitUrl" to "https://aam.igame58.com/mini/three/ali/action/submit", "loginMethod" to "GET", "submitMethod" to "POST", "umid" to "qgame_6007a121f1eb4f3f9b67a9a9"),
                listOf("xjnc_game_pass_every_1")),
            GameTask("会员挪小羊", "2021005161639016", "", "hynxy_game_pass_every_1",
                "", "0.2.2507211449.1", "igame58",
                mapOf("loginUrl" to "https://aam.igame58.com/mini/three/getOpenId", "submitUrl" to "https://aam.igame58.com/mini/three/ali/action/submit", "loginMethod" to "GET", "submitMethod" to "POST", "umid" to "68764064bc47b67d83b14460"),
                listOf("hynxy_game_pass_every_1")),
            GameTask("消消来解压", "2021005181698249", "", "xxljy_game_pass_every_1",
                "", "0.2.2606041504.28", "igame58",
                mapOf("loginUrl" to "https://aam.igame58.com/mini/three/getOpenId", "submitUrl" to "https://aam.igame58.com/mini/three/ali/action/submit", "loginMethod" to "GET", "submitMethod" to "POST", "umid" to "67ea379048ac1b4f8700008e"),
                listOf("xxljy_game_pass_every_1")),
            GameTask("玩不过我呀", "2060170000363633", "", "wbgwy_game_pass_every_1",
                "", "0.2.2605270925.9", "aaIgame58",
                mapOf("loginUrl" to "https://aa.igame58.com/mini/three/getOpenId", "submitUrl" to "https://aa.igame58.com/mini/three/ali/action/submit", "loginMethod" to "GET", "submitMethod" to "POST", "umid" to "qgame_64361083ba6a5259c4360029"),
                listOf("wbgwy_game_pass_every_1")),
            GameTask("点点消不停", "2060170000365575", "", "ddxbt_game_pass_every_1",
                "", "0.2.2604161533.29", "aaIgame58",
                mapOf("loginUrl" to "https://aa.igame58.com/mini/three/getOpenId", "submitUrl" to "https://aa.igame58.com/mini/three/ali/action/submit", "loginMethod" to "GET", "submitMethod" to "POST", "umid" to "qgaem_60de8c4e8a102159db85d599"),
                listOf("ddxbt_game_pass_every_1")),
            GameTask("疯狂旋转模拟器", "2021004129659235", "", "fkxzmnq_game_jibai_every_1",
                "", "0.2.2602031626.19", "qxygame",
                mapOf("loginUrl" to "https://qxygame.zhangle.fun/", "submitUrl" to "https://qxygame.zhangle.fun/", "loginMethod" to "POST", "submitMethod" to "POST", "gameId" to "fkxzmnq"),
                listOf("fkxzmnq_game_jibai_every_1")),
            GameTask("萌萌闯关达人", "2021004160635662", "", "mmcgdr_game_pass_every_1",
                "", "0.2.2512121514.7", "qxygame",
                mapOf("loginUrl" to "https://qxygame.zhangle.fun/", "submitUrl" to "https://qxygame.zhangle.fun/", "loginMethod" to "POST", "submitMethod" to "POST", "gameId" to "mmcgdr"),
                listOf("mmcgdr_game_pass_every_1")),
            GameTask("成语状元", "2021004182639175", "", "cyzzy_game_pass_every_1",
                "", "0.2.2512051703.23", "cdwaterbear",
                mapOf("loginUrl" to "https://idauth.cdwaterbear.cn/createaccount", "submitUrl" to "https://idauth.cdwaterbear.cn/alipayIncrementGameAction", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("cyzzy_game_pass_every_1")),
            GameTask("躺平植物战僵尸", "2021004157672803", "", "tpzwjs_game_shengji_every_1",
                "", "0.2.2512251750.58", "cdwaterbear",
                mapOf("loginUrl" to "https://idauth.cdwaterbear.cn/createaccount", "submitUrl" to "https://idauth.cdwaterbear.cn/alipayIncrementGameAction", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("tpzwjs_game_shengji_every_1")),
            GameTask("超凡打螺丝", "2021004159660680", "", "cfdls_game_pass_every_1",
                "", "0.2.2603150002.25", "cdwaterbear",
                mapOf("loginUrl" to "https://idauth.cdwaterbear.cn/createaccount", "submitUrl" to "https://idauth.cdwaterbear.cn/alipayIncrementGameAction", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("cfdls_game_pass_every_1")),
            GameTask("茶叶蛋大解密", "2021005107607717", "", "cyddjm_game_pass_every_1",
                "", "0.2.2602091812.10", "ultralisk",
                mapOf("loginUrl" to "https://common.ultralisk.cn/sdkserver/userlogin", "submitUrl" to "https://common.ultralisk.cn/sdkserver/h5alipayuploadgamebehaviour", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("cyddjm_game_pass_every_1")),
            GameTask("茶叶蛋大冒险", "2021003193629997", "", "cyddmx_game_pass_level_1",
                "", "0.2.2606121444.57", "ultralisk",
                mapOf("loginUrl" to "https://common.ultralisk.cn/sdkserver/userlogin", "submitUrl" to "https://common.ultralisk.cn/sdkserver/h5alipayuploadgamebehaviour", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("cyddmx_game_pass_level_1")),
            GameTask("废物幸存者", "2021005130646326", "", "fxxcz_game_pass_every_1",
                "", "0.2.2601071550.0", "ultralisk",
                mapOf("loginUrl" to "https://common.ultralisk.cn/sdkserver/userlogin", "submitUrl" to "https://common.ultralisk.cn/sdkserver/h5alipayuploadgamebehaviour", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("fxxcz_game_pass_every_1")),
            GameTask("农场水滴排序", "2021004167642405", "", "ncspx_game_pass_every_1",
                "", "0.2.2507111655.8", "tgpax",
                mapOf("loginUrl" to "https://zfbvip.tgpax.cn/game/v3/token_api/token/alipay", "submitUrl" to "https://zfbvip.tgpax.cn/game/v3/alipay_api/increment_game_action", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("ncspx_game_pass_every_1")),
            GameTask("疯狂爱消除", "2060170000366791", "", "fkaxc_game_hecheng_every_10",
                "", "0.2.2512011739.5", "landintheair",
                mapOf("loginUrl" to "https://service.landintheair.com/game/api/openid/zfb", "submitUrl" to "https://service.landintheair.com/game/api/alipay/gameaction", "loginMethod" to "GET", "submitMethod" to "POST"),
                listOf("fkaxc_game_hecheng_every_10")),
            GameTask("会员爱解压", "2060170000358569", "", "hyajy_game_pass_every_1",
                "", "0.2.2509081602.46", "landintheair",
                mapOf("loginUrl" to "https://service.landintheair.com/game/api/openid/zfb", "submitUrl" to "https://service.landintheair.com/game/api/alipay/gameaction", "loginMethod" to "GET", "submitMethod" to "POST"),
                listOf("hyajy_game_pass_every_1")),
            GameTask("农场萌小院", "2021004115691527", "", "",
                "", "0.2.2607301618.6", "beautifulgarden",
                mapOf("loginUrl" to "https://partition-zfb.moxigame.cn/api/role/login", "submitUrl" to "https://two-zfb.moxigame.cn/beautifulgarden-zfb-ncxy/api/api/redirectAlipay", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("ncmxy_game_dingdan_every_1", "ncmxy_game_synthetise_num_10")),
            GameTask("会员小庭院", "2021004143669557", "", "",
                "", "0.2.2607301647.41", "oneMoxigame",
                mapOf("loginUrl" to "https://pay.moxigame.cn/auth/login", "submitUrl" to "https://one-zfb-hy.moxigame.cn/xmty-hy-banhao-a/api/api/redirectAlipay", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("iaphyxty_game_dingdan_every_5", "iaphyxty_game_hecheng_every_10")),
            GameTask("躺平发育", "2021004123689742", "", "tpfy_game_shengji_every_1",
                "", "0.2.2602281055.3", "mihuangame",
                mapOf("loginUrl" to "https://tttp01.mihuangame.com/api/v2/sys/user/login", "submitUrl" to "https://tttp01.mihuangame.com/api/v2/sys/alipay/point", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("tpfy_game_shengji_every_1")),
            GameTask("猛鬼宿舍", "2021004122659378", "", "mgss_game_shengji_every_1",
                "", "0.2.2602281119.3", "mihuangame",
                mapOf("loginUrl" to "https://tttp01.mihuangame.com/api/v2/sys/user/login", "submitUrl" to "https://tttp01.mihuangame.com/api/v2/sys/alipay/point", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("mgss_game_shengji_every_1")),
            GameTask("农场火柴人", "2021004184670590", "", "nchcr_game_pass_every_1",
                "", "0.2.2504281703.57", "zuiqiangyingyu",
                mapOf("loginUrl" to "https://game.zuiqiangyingyu.net/common/alipay/login", "submitUrl" to "https://game.zuiqiangyingyu.net/common/alipay/gameActionSubmit", "loginMethod" to "GET", "submitMethod" to "GET"),
                listOf("nchcr_game_pass_every_1")),
            GameTask("打螺丝大师", "2021004148695291", "", "dlsds_game_pass_every_1",
                "", "0.2.2511181728.34", "flysheeep",
                mapOf("loginUrl" to "https://api.flysheeep.com/api/tapaway/get_ali_token", "submitUrl" to "https://api.flysheeep.com/api/ali/ali_submit_v3", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("dlsds_game_pass_every_1")),
            GameTask("猪了个猪", "2060170000375112", "", "zlgz_game_pass_every_1",
                "", "0.2.2511181728.34", "flysheeep",
                mapOf("loginUrl" to "https://api.flysheeep.com/api/tapaway/get_ali_token", "submitUrl" to "https://api.flysheeep.com/api/ali/ali_submit_v3", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("zlgz_game_pass_every_1")),
            GameTask("小鸡料理王", "2021004157677654", "", "xjllw_game_hecheng_every_10",
                "", "0.2.2608071935.8", "moxigame",
                mapOf("loginUrl" to "https://txy-xj.moxigame.cn/cookking-b/api/role/login", "submitUrl" to "https://txy-xj.moxigame.cn/cookking-b/api/alipay/redirect", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("xjllw_game_hecheng_every_10")),
            GameTask("女孩与海", "2021004171692313", "", "",
                "", "0.2.2507232209.2", "nhyhtaobao",
                mapOf("loginUrl" to "https://nhyhalipay-login.gameserver.iplay11g.com:8001/login", "submitUrl" to "https://nhyhtaobao-gm.gameserver.iplay11g.com/gmsys/alipay/gameactionsubmit.php", "loginMethod" to "GET", "submitMethod" to "GET"),
                listOf()),
            GameTask("我要下西洋农场版", "2060170000359949", "", "wyxxyncb_game_jianzao_every_1",
                "", "0.2.2501222004.56", "yoqu",
                mapOf("loginUrl" to "https://data.3yoqu.com/api/v1.index/get_openid", "submitUrl" to "https://data.3yoqu.com/api/v1.Game/ali_action_callback", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("wyxxyncb_game_jianzao_every_1")),
            GameTask("我要下西洋会员版", "2060170000359968", "", "wyxxyhyb_game_jianzao_every_1",
                "", "0.2.2501221829.7", "yoqu",
                mapOf("loginUrl" to "https://data.3yoqu.com/api/v1.index/get_openid", "submitUrl" to "https://data.3yoqu.com/api/v1.Game/ali_action_callback", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("wyxxyhyb_game_jianzao_every_1")),
            GameTask("我要下西洋", "2060170000357463", "", "wyxxy_game_jianzao_every_1",
                "", "0.2.2501211752.30", "yoqu",
                mapOf("loginUrl" to "https://data.3yoqu.com/api/v1.index/get_openid", "submitUrl" to "https://data.3yoqu.com/api/v1.Game/ali_action_callback", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("wyxxy_game_jianzao_every_1")),
            GameTask("果园消螺丝", "2021006118661999", "", "gyxls_game_xiaochu_every_5",
                "", "0.2.2603041819.11", "server8haligame",
                mapOf("loginUrl" to "https://server8.haligame.com:39100/zfb_getopenid", "submitUrl" to "https://server8.haligame.com:39101/zfb/sendevent", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("gyxls_game_xiaochu_every_5")),
            GameTask("动物叠高高", "2021005112624997", "", "dwdgg_game_hecheng_every_1",
                "", "0.2.2603122222.47", "modyoo",
                mapOf("loginUrl" to "https://ddl.topnod.modyoo.com/game/user/login", "submitUrl" to "https://ddl.topnod.modyoo.com/game/user/report", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("dwdgg_game_hecheng_every_1")),
            GameTask("森林改造计划", "2021005113663065", "", "slgzjh_game_hecheng_every_10",
                "", "0.2.2601051147.45", "moyangmoyang",
                mapOf("submitUrl" to "https://slgz-ali.moyangmoyang.com/alipay/reportData.php", "submitMethod" to "POST"),
                listOf("slgzjh_game_hecheng_every_10")),
            GameTask("消消小能手", "2021005100698187", "", "xxxns_game_pass_every_1",
                "", "0.2.2512241437.43", "yigomob",
                mapOf("loginUrl" to "https://bi-api.yigomob.com/api/v1/dygame/aliLogin", "submitUrl" to "https://bi-api.yigomob.com/api/v1/dygame/event", "loginMethod" to "POST_FORM", "submitMethod" to "POST_FORM"),
                listOf("xxxns_game_pass_every_1")),
            GameTask("合不过我吧", "2021005112626489", "", "",
                "", "0.2.2607011748.44", "mmhygame",
                mapOf("loginUrl" to "https://sgxc.xyx.mmhygame.com/sgxc/login", "submitUrl" to "https://sgxc.xyx.mmhygame.com/sgxc/api/reqZfbGameEvent", "loginMethod" to "POST", "submitMethod" to "POST", "gameName" to "sgxc"),
                listOf("hbgwb_game_pass_every_1", "hbgwb_game_xiaochu_every_10")),
            GameTask("简直是天才", "2021006137600649", "", "",
                "", "0.2.2608191623.26", "mmhygame",
                mapOf("loginUrl" to "https://lgsg.xyx.mmhygame.com/lgsg/login", "submitUrl" to "https://lgsg.xyx.mmhygame.com/lgsg/api/reqZfbGameEvent", "loginMethod" to "POST", "submitMethod" to "POST", "gameName" to "lgsg"),
                listOf("jzstc_game_pass_every_1", "jzstc_game_xiaochu_every_10")),
            GameTask("泡泡龙王国", "2021003184609526", "", "",
                "", "0.2.2507071728.25", "d3games",
                mapOf("appKey" to "86b5b3815ddfa448eec459718d4c2aa3", "loginMethod" to "POST", "submitMethod" to "POST", "loginUrl" to "https://api-zfb-ppl.d3games.com/login", "submitUrl" to "https://api-zfb-ppl-version.d3games.com/ali/incrementgameaction/submit"),
                listOf("pplwg_game_pass_level_every_1", "pplwg_game_daoju_every_1")),
            GameTask("农场泡泡龙", "2021004130619550", "", "farmppl_game_pass_1_misson",
                "", "0.2.2512101746.15", "d3games",
                mapOf("appKey" to "4a9c9ae228d57007eb3af2808142e242", "loginMethod" to "POST", "submitMethod" to "POST", "loginUrl" to "https://api-zfb-ncppl.d3games.com/login", "submitUrl" to "https://api-zfb-ncppl.d3games.com/ali/action/submit"),
                listOf("farmppl_game_pass_1_misson")),
            GameTask("泡泡龙大挑战", "2021004157647236", "", "ppldtz_game_pass_every_1",
                "", "0.2.2409041752.27", "d3games",
                mapOf("appKey" to "e6a0631f86467b7a4a880574bbb78d7f", "loginMethod" to "POST", "submitMethod" to "POST", "loginUrl" to "https://api-ppldtz.d3games.com/login", "submitUrl" to "https://api-ppldtz.d3games.com/ali/action/submit"),
                listOf("ppldtz_game_pass_every_1")),
            GameTask("小鸡向前冲", "2021004138641359", "", "xjxqc_game_piaoyi_every_1",
                "", "0.2.2602041526.56", "d3games",
                mapOf("appKey" to "ca08c5ba0b86372f1028375f91ecdbf6", "loginMethod" to "POST", "submitMethod" to "POST", "loginUrl" to "https://xjxqc.d3games.com/api/login", "submitUrl" to "https://xjxqc.d3games.com/api/ali/incrementgameaction/submit"),
                listOf("xjxqc_game_piaoyi_every_1")),
            GameTask("美食奇遇记", "2021005109675436", "", "",
                "", "0.2.2607092006.11", "d3games",
                mapOf("appKey" to "192398bf853974a546a8e6c522d04b8a", "gameId" to "10161", "reportHost" to "msqyj.d3games.com", "loginMethod" to "POST", "submitMethod" to "POST", "loginUrl" to "https://msqyj.d3games.com/api/login", "submitUrl" to "https://msqyj.d3games.com/api/ali/incrementgameaction/submit"),
                listOf("iapmsqyj_game_tili_every_10", "iapmsqyj_game_hecheng_every_10", "iapmsqyj_game_dingdan_every_1", "iapmsqyj_game_jianzao_every_1")),
            GameTask("森林环游记", "2021004184696771", "", "",
                "", "0.2.2607071824.23", "d3games",
                mapOf("appKey" to "465d2fe840abb7bfdc2d69cf1f39be28", "gameId" to "10182", "reportHost" to "api-slhyj.d3games.com", "loginMethod" to "POST", "submitMethod" to "POST", "loginUrl" to "https://api-slhyj.d3games.com/api/login", "submitUrl" to "https://api-slhyj.d3games.com/api/ali/incrementgameaction/submit"),
                listOf("slhyjiap_game_tili_every_10", "slhyjiap_game_hecheng_every_10", "slhyjiap_game_dingdan_every_1", "slhyjiap_game_jianzao_every_1")),
            GameTask("农场挪对对", "2021004148696219", "", "ncndd_game_pipei_every_10",
                "", "0.2.2607201557.47", "d3games",
                mapOf("appKey" to "05b83455558bcd98f7beab079498df27", "loginMethod" to "POST", "submitMethod" to "POST", "loginUrl" to "https://api-ncndd.d3games.com/login", "submitUrl" to "https://api-ncndd.d3games.com/ali/action/submit"),
                listOf("ncndd_game_pipei_every_10")),
            GameTask("农场叠叠叠", "2021004150609014", "", "ncddd_game_pipei_every_5",
                "", "0.2.2504071609.32", "vip888888",
                mapOf("sdkVersion" to "v1.0.1", "gameVersion" to "20250331V1.0.0", "loginUrl" to "https://api.vip888888.top/v1/login", "submitUrl" to "https://api.vip888888.top/v1/alipayIncReport", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("ncddd_game_pipei_every_5")),
            GameTask("羊了个羊农场版", "2021004138691383", "", "",
                "", "0.2.2607162135.58", "",
                mapOf("loginUrl" to "https://cat-match.easygame2021.com/sheep/v1/user/login_ali_farm?query_scene=", "loginMethod" to "POST", "submitUrl" to "https://cat-match.easygame2021.com/sheep/v1/game/ali_farm/upload_count", "submitMethod" to "POST"),
                listOf()),
            GameTask("密室出逃", "2021005190608132", "", "msct_game_pass_every_1",
                "", "0.2.2511141651.44", "maoyumeng",
                mapOf("loginUrl" to "https://www.maoyumeng.club/api/interface/open/getIdKey", "submitUrl" to "https://www.maoyumeng.club/api/interface/alipay/incrementGameAction", "loginMethod" to "GET", "submitMethod" to "POST"),
                listOf("msct_game_pass_every_1")),
            GameTask("抓大鹅", "2060170000400026", "", "zde_game_xiaochu_every_15",
                "", "0.2.2610231342.51", "",
                mapOf("loginUrl" to "https://prod-zhuadae.lanfeitech.com/api/user/auth", "loginMethod" to "POST", "submitUrl" to "https://prod-zhuadae.lanfeitech.com/api/api/alipay_action_report", "submitMethod" to "POST"),
                listOf("zde_game_xiaochu_every_15")),
            GameTask("城主别慌", "2021006152653505", "", "",
                "", "0.2.2610010110.0", "shryou",
                mapOf("game" to "chengzhubiehuangzhang-zfb", "reportHost" to "tf-api.shryou.com", "loginUrl" to "https://graph.llewan.com/v5/AliGame/UserReg;https://exc.llewan.com/v5/GameUser/Auth;https://tf-api.shryou.com/auth/token", "loginMethod" to "POST", "submitUrl" to "https://tf-api.shryou.com/docking/lewan/aliReport", "submitMethod" to "POST"),
                listOf("czbhz_game_pass_every_1", "czbhz_game_jineng_every_1", "czbhz_game_fangwen_every_1", "czbhz_game_gaojizhaomu_every_1")),
            GameTask("营救小鸡", "2021004138694188", "", "yjxj_game_dingzi_every_10",
                "", "0.2.2606051701.41", "manyoukeji2024",
                mapOf("loginUrl" to "https://jump.manyoukeji2024.com/auth/platformLogin", "submitUrl" to "https://s1jump.manyoukeji2024.com/alipay-report/report", "loginMethod" to "POST_FORM", "submitMethod" to "POST_FORM"),
                listOf("yjxj_game_dingzi_every_10")),
            GameTask("指尖幻影", "2021000100675812", "", "zjhy_game_boci_every_3",
                "", "0.2.2607102236.59", "",
                emptyMap(),
                listOf("zjhy_game_boci_every_3")),
            GameTask("守护世界", "2021004189686520", "", "",
                "", "0.2.2607131151.47", "",
                mapOf("channelId" to "hy1", "sdkVersion" to "1.0.7"),
                listOf("shsj_game_hecheng_every_10", "shsj_game_renwu_every_1")),
            GameTask("消消瓜果园", "2021005183651471", "", "",
                "", "", "",
                emptyMap(),
                listOf()),
            GameTask("三国冰河时代", "2021004173661702", "", "",
                "", "0.2.2606261000.30", "tuyoo",
                mapOf("cloud_id" to "138", "sdkAppId" to "20562", "loginUrl" to "https://138-sanguo2-sdk-online01.bjxuejing.cn/open/v6/user/LoginBySnsIdNoVerify", "clientId" to "H5_5.0_tyGuest,tyAccount.alipay.0-hall20562.aliminigame.sanguo2"),
                listOf("sgbhsd_game_caiji_every_1", "sgbhsd_game_xunlian_every_1", "sgbhsd_game_zhaomu_every_1")),
            GameTask("拳皇98", "2021005186664566", "", "kof_game_pass_every_1",
                "", "1.1.14.0", "bkxgame",
                mapOf("platform_game_id" to "kof-alipay", "platform_app_id" to "bbe32c018fa745968360c5dd52dda89e", "loginUrl" to "https://platform.bkxgame.com/admin-console/alipay/login", "submitUrl" to "https://platform.bkxgame.com/report-console/data_report", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("kof_game_pass_every_1")),
            GameTask("次神：光之觉醒", "2021004163645297", "", "csgzjs_game_wave_every_1",
                "", "", "tuyoo",
                mapOf("cloud_id" to "152", "sdkAppId" to "20586", "loginUrl" to "https://152-slmsq-sdk-online01.bjxuejing.cn/open/v6/user/LoginBySnsIdNoVerify", "loginMethod" to "POST_FORM"),
                listOf("csgzjs_game_wave_every_1")),
            GameTask("英雄没有闪", "2021005121619727", "", "yxmys_game_pass_every_1",
                "", "", "yxmys",
                mapOf("appid" to "37zfbxyx", "game_id" to "363", "cgame_id" to "android.game.yxmys_zfbxyxtg", "sdk" to "gzyxnetwork", "loginUrl" to "https://gapigameh5.gzyxnetwork.com/api/v1/quickAppEnter?pt_type=1&appid=37zfbxyx&c=enter&a=quickAppEnter&game_id=363", "loginMethod" to "POST"),
                listOf("yxmys_game_pass_every_1")),
            GameTask("赵云与阿斗", "2021006159622573", "", "zyyad_game_duiju_every_1",
                "", "", "mihuangame",
                mapOf("channelAppId" to "1229", "loginUrl" to "https://api01.mihuangame.com/api/v2/sys/user/login", "submitUrl" to "https://api01.mihuangame.com/api/v2/sys/alipay/submit", "loginMethod" to "POST", "submitMethod" to "POST"),
                listOf("zyyad_game_duiju_every_1")),
        ).associateBy { it.appId }

        @JvmStatic
        fun fromAppId(appId: String): GameTask? = games[appId]

        @JvmStatic
        fun fromReportHost(host: String): GameTask? =
            games.values.firstOrNull {
                it.protocolParameters["reportHost"] == host || host in it.reportHostAliases
            }
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
        actionFinishChannel: String = channel,
        logger: ((String) -> Unit)? = null,
        confirmProgress: (suspend () -> Boolean?)? = null,
        actionCode: String = action,
        reportCount: Int? = null,
    ): GameTaskReportResult {
        val emitLog = logger ?: ::logTask
        val loginOnly = sdk == "tuyoo" || sdk == "yxmys"
        // 未知次数仅执行一步，不把单步预算当作任务需求；后续由业务回查推进。
        val reportLimit = (reportCount ?: 1).let { if (loginOnly) minOf(it, 1) else it }
        if (reportLimit <= 0) return GameTaskReportResult(0, 0, 0)
        if (actionFinishChannel.isBlank() || actionCode.isBlank()) {
            return GameTaskReportResult(
                reportLimit = reportLimit,
                attemptedReports = 0,
                successfulReports = 0,
                failureMessage = "action_finish_channel或结构化动作码为空",
            )
        }

        val session = AccountSessionCoordinator.currentSession()
            ?: return GameTaskReportResult(reportLimit, 0, 0,
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
        val loginStartedAt = System.currentTimeMillis()
        val cachedSession = if (sdk == "aslk2018") GameSession.read(appId)?.takeIf { saved ->
            val credentials = saved.credentials
            credentials["token"].orEmpty().isNotBlank() && credentials["gid"] == gid &&
                credentials["version"] == version && credentials["sessionEpoch"] == session.sessionEpoch.toString() &&
                credentials["issuedAt"]?.toLongOrNull()?.let { loginStartedAt - it in 0 until 300_000L } == true
        } else null
        val loginResult = when {
            cachedSession != null -> GameTaskSingleReportResult(true, token = cachedSession.credentials["token"])
            sdk == "aslk2018" -> login(sessionCurrent)
            else -> GameTaskSingleReportResult(true)
        }
        coroutineContext.ensureActive()
        executionReport?.ensureCurrent()
        executionReport?.recordActivity()
        val token = loginResult.token.orEmpty()
        if (!loginResult.success || !sessionCurrent()) {
            return GameTaskReportResult(
                reportLimit = reportLimit,
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
        for (index in 1..reportLimit) {
            currentCoroutineContext().ensureActive()
            if (!sessionCurrent()) {
                failure = GameTaskSingleReportResult(false, "账号会话已变化或已离线", stopCurrentRound = true)
                break
            }
            val result = if (sdk == "aslk2018") {
                executeSingleReport(index, reportLimit, actionFinishChannel, token, actionCode, sessionCurrent)
            } else {
                val vendor = MiniGameVendorReporter.reportOnce(this, actionFinishChannel, actionCode) { sessionCurrent() }
                GameTaskSingleReportResult(vendor.success, vendor.message, vendor.code, vendor.raw, vendor.stopCurrentRound,
                    writeStarted = vendor.writeStarted)
            }
            if (result.authenticationFailed) cachedSession?.let(GameSession::invalidate)
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
            emitLog(if (loginOnly) "厂商登录已接受，业务进度待回查"
                else if (reportCount == null) "动作上报已接受，任务次数未知，回查业务进度"
                else "动作上报已接受: $index/$reportLimit，业务进度待回查")
            if (confirmProgress != null) {
                val confirmed = try {
                    confirmProgress()
                } catch (error: Exception) {
                    if (error is CancellationException || error is InterruptedException) throw error
                    failure = GameTaskSingleReportResult(false, "上报后业务查询异常：${error.message.orEmpty()}")
                    null
                }
                currentCoroutineContext().ensureActive()
                if (confirmed == null) {
                    if (failure == null) failure = GameTaskSingleReportResult(false, "上报后业务状态未确认，停止本批上报")
                    break
                }
                if (confirmed) {
                    businessConfirmed = true
                    emitLog("业务状态已确认达成，结束本批上报")
                    break
                }
            }
        }
        if (sdk == "aslk2018" && cachedSession == null && successfulReports > 0 &&
            failure?.authenticationFailed != true && sessionCurrent()
        ) {
            GameSession.save(AccountSessionIdentity(session.userId, session.sessionEpoch), appId,
                mapOf("token" to token, "gid" to gid, "version" to version,
                    "sessionEpoch" to session.sessionEpoch.toString(), "issuedAt" to loginStartedAt.toString()))
        }
        return GameTaskReportResult(
            reportLimit = reportLimit,
            attemptedReports = attemptedReports,
            successfulReports = successfulReports,
            failureMessage = failure?.message.orEmpty(),
            failureCode = failure?.code.orEmpty(),
            failureRaw = failure?.raw.orEmpty(),
            stopCurrentRound = failure?.stopCurrentRound == true,
            businessConfirmed = businessConfirmed,
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
        var authenticationFailed = false
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
            authenticationFailed = responseCode == 401
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
                    message = "上报失败：本批请求=$current/$total HTTP=$responseCode",
                    code = RpcOfflineRisk.extractCode(response),
                    raw = responseText,
                    stopCurrentRound = responseCode in setOf(401, 403) || RpcOfflineRisk.isOfflineRisk(response),
                    writeStarted = true,
                    authenticationFailed = authenticationFailed,
                )
            }
        } catch (error: Exception) {
            RpcTrafficCapture.recordModuleHttpError(captureRequestId, error)
            if (error is CancellationException || error is InterruptedException) throw error
            GameTaskSingleReportResult(
                success = false,
                message = "上报异常：本批请求=$current/$total ${error.message.orEmpty()}",
                writeStarted = writeStarted,
                authenticationFailed = authenticationFailed,
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

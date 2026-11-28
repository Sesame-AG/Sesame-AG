package io.github.aoguai.sesameag.util

import android.os.Build
import android.util.Base64
import io.github.aoguai.sesameag.hook.AccountSessionCoordinator
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.internal.AlipayMiniMarkHelper
import io.github.aoguai.sesameag.hook.internal.AuthCodeHelper
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.StringTokenizer
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 厂商单次登录及动作协议。批次、账号会话和业务回查由 GameTask 管理。 */
object MiniGameVendorReporter {
    const val ROLE_CONTEXT_MISSING = "ROLE_CONTEXT_MISSING"
    private val RANDOM = Random()
    private val REQUEST = ThreadLocal<RequestState>()

    private class Meta(game: GameTask, val channel: String) {
        val name = game.title
        val version = game.version
        val oid = param(game.appId, "oid")
        val gcid = param(game.appId, "gcid")
        val appKey = param(game.appId, "nbAppKey").ifEmpty { param(game.appId, "appKey") }
        val nebulaAppId = param(game.appId, "nebulaAppId")
        val companyId = param(game.appId, "bgid").ifEmpty { param(game.appId, "companyId") }
        val channelId = param(game.appId, "channelId")
    }

    class Result internal constructor(
        val success: Boolean,
        val message: String,
        val raw: String,
        val code: String,
        val stopCurrentRound: Boolean,
        val writeStarted: Boolean,
    )

    private class RequestState(val sessionCurrent: () -> Boolean, val sdk: String) {
        var response = ""
        var httpStatus = 0
        var stopped = false
        var reporting = false
        var writeStarted = false
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)

        fun requireCurrent() {
            if (Thread.currentThread().isInterrupted) throw CancellationException("游戏上报已取消")
            if (!sessionCurrent()) {
                stopped = true
                throw IllegalStateException("账号会话已变化或已离线，停止游戏上报")
            }
        }

        fun result(success: Boolean, message: String, localCode: String = ""): Result {
            var resultCode = ""
            try {
                val root = if (sdk == "qmhd87") qmhdJson(response) else JSONObject(response)
                val json = if (sdk == "tuyoo") root?.optJSONObject("result") ?: root else root
                if (json != null) {
                    for (key in arrayOf("code", "errorCode", "resultCode", "errcode", "status")) {
                        if (!json.isNull(key)) resultCode = json.optString(key)
                        if (resultCode.isNotEmpty()) break
                    }
                }
            } catch (_: Exception) {
            }
            if (localCode.isNotEmpty()) resultCode = localCode
            return Result(
                success = success,
                message = if (message.isEmpty()) "" else (if (reporting) "动作上报：" else "厂商登录：") + message,
                raw = response,
                code = if (resultCode.isEmpty() && httpStatus != 0) httpStatus.toString() else resultCode,
                stopCurrentRound = stopped || httpStatus == 401 || httpStatus == 403,
                writeStarted = writeStarted,
            )
        }
    }

    fun observeOfficialSession(loader: ClassLoader, rawUrl: String, body: Any?, entry: String) {
        val url = try {
            URL(rawUrl)
        } catch (_: MalformedURLException) {
            return
        }
        if (url.protocol != "https") return
        val game = GameTask.fromReportHost(url.host) ?: return
        when (game.sdk) {
            "haligame" -> {
                if (url.path != "/ajax/user_action_submit.php" || body == null) return
                val data = (if (body is ByteArray) String(body, StandardCharsets.UTF_8) else body.toString())
                    .trim { it <= ' ' }
                var openId = ""
                if (data.startsWith("{")) {
                    openId = JSONObject(data).optString("open_id")
                } else {
                    val fields = StringTokenizer(data, "&")
                    while (fields.hasMoreTokens()) {
                        val field = fields.nextToken()
                        val separator = field.indexOf('=')
                        if (separator < 0) continue
                        val name = URLDecoder.decode(field.substring(0, separator), "UTF-8")
                        if (name == "open_id") {
                            openId = URLDecoder.decode(field.substring(separator + 1), "UTF-8")
                            break
                        }
                    }
                }
                if (openId.isNotEmpty()) {
                    GameSession.save(loader, game.appId, linkedMapOf("open_id" to openId, "reportHost" to url.host))
                }
            }
            "flourish" -> {
                if (url.path != "/QuickGame/callMethod") return
                if (body == null) {
                    GameSession.logHandoff(game.appId, "接收", "$entry 缺少请求体")
                    return
                }
                val request = JSONObject(if (body is ByteArray) String(body, StandardCharsets.UTF_8) else body.toString())
                if (request.optString("method") != "incrementGameAction") return
                GameSession.logHandoff(game.appId, "接收", entry)
                if (param(game.appId, "gameId") != request.optString("game_id")) {
                    GameSession.logHandoff(game.appId, "识别", "游戏映射不一致")
                    return
                }
                var event = request.optJSONObject("channel_params")
                if (event == null) {
                    val channelParams = request.optString("channel_params")
                    if (channelParams.isNotEmpty()) event = JSONObject(channelParams)
                }
                if (event == null || event.optString("event") != "alipay_game_action" ||
                    event.optString("role_id").isEmpty() || event.optString("server_id").isEmpty()
                ) {
                    GameSession.logHandoff(game.appId, "识别", "缺少角色资料或事件类型不符")
                    return
                }
                GameSession.logHandoff(game.appId, "识别", "已识别角色资料")
                val role = JSONObject()
                for (key in arrayOf("role_id", "role_name", "server_id", "server_name", "level", "role_level", "power", "combat_power")) {
                    if (!event.isNull(key)) role.put(key, event.get(key))
                }
                GameSession.save(loader, game.appId, mapOf("channel_params" to role.toString()))
            }
            "shryou" -> {
                if (url.path != "/docking/lewan/aliReport" || body == null) return
                val shryou = JSONObject(if (body is ByteArray) String(body, StandardCharsets.UTF_8) else body.toString())
                if (param(game.appId, "game") != shryou.optString("game")) return
                val uid = shryou.optString("uid")
                if (uid.isNotEmpty()) GameSession.save(loader, game.appId, mapOf("uid" to uid))
            }
        }
    }

    fun reportOnce(game: GameTask, channel: String, action: String, sessionCurrent: () -> Boolean): Result {
        val state = RequestState(sessionCurrent, game.sdk)
        REQUEST.set(state)
        try {
            state.requireCurrent()
            val meta = Meta(game, channel)
            val appId = game.appId
            val gameSession = if (game.sdk == "haligame" || game.sdk == "flourish" || game.sdk == "shryou") {
                GameSession.read(appId)
            } else null
            val hasIdentity = game.sdk == "haligame" && !gameSession?.credentials?.get("open_id").isNullOrEmpty()
            if (game.sdk == "flourish" && gameSession?.credentials?.get("channel_params").isNullOrEmpty()) {
                return state.result(false, "当前账号的角色上下文尚未取得，请进入${meta.name}完成一次游戏动作后重试", ROLE_CONTEXT_MISSING)
            }
            val authCode = if (game.sdk == "yyxxgame" || game.sdk == "shryou" || hasIdentity) ""
                else AuthCodeHelper.getAuthCode(appId).orEmpty()
            state.requireCurrent()
            if (game.sdk != "yyxxgame" && game.sdk != "shryou" && !hasIdentity && authCode.isEmpty()) {
                state.stopped = true
                return state.result(false, "厂商登录失败：授权码为空")
            }
            val headers = baseHeaders(meta, appId)
            val success = when (game.sdk) {
                "haligame" -> reportHaligame(meta, appId, action, authCode, gameSession, headers)
                "dianwanshidai" -> reportDianwanshidai(meta, appId, action, authCode, headers)
                "yyxxgame" -> reportYyxx(meta, appId, action, headers)
                "nebula737" -> reportNebula(meta, action, authCode, headers)
                "cpchxapi" -> reportCpchx(meta, appId, action, authCode, headers)
                "hnycgames" -> reportHnyc(meta, action, authCode, headers)
                "dianli100" -> reportDianli100(meta, appId, action, authCode, headers)
                "tomato" -> reportTomato(meta, appId, action, authCode, headers)
                "raiyantway" -> reportRaiyantway(meta, appId, action, authCode, headers)
                "flourish" -> reportFlourish(meta, appId, action, authCode, gameSession!!, headers)
                "devourad" -> reportDevourad(meta, appId, action, authCode, headers)
                "microfun" -> reportMicrofun(meta, appId, action, authCode, headers)
                "qufang" -> reportQufang(meta, appId, action, authCode, headers)
                "qmhd87" -> reportQmhd87(meta, appId, action, authCode, headers)
                "lumosfun" -> reportLumosfun(meta, appId, action, authCode, headers)
                "mmhygame" -> reportMmhygame(meta, appId, action, authCode, headers)
                "trueplay" -> reportTrueplay(meta, appId, action, authCode, headers)
                "igame58", "aaIgame58" -> reportIgame58(meta, appId, action, authCode, headers)
                "soulgame" -> reportSoulgame(meta, appId, action, authCode, headers)
                "qxygame" -> reportQxygame(meta, appId, action, authCode, headers)
                "mihuangame" -> reportMihuangame(meta, appId, action, authCode, headers)
                "d3games" -> reportD3games(meta, appId, action, authCode, headers)
                "tuyoo" -> loginTuyoo(meta, appId, authCode, headers)
                "yxmys" -> loginYxmys(meta, appId, authCode, headers)
                "shryou" -> reportShryou(meta, appId, action, gameSession, headers)
                else -> return state.result(false, "厂商动作协议尚未实现：${game.sdk}")
            }
            state.requireCurrent()
            return state.result(success, if (success) "" else "厂商登录或上报失败")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return state.result(false, "请求异常：${e.message}")
        } finally {
            REQUEST.remove()
        }
    }

    private fun param(appId: String, key: String): String = GameTask.fromAppId(appId)?.protocolParameters?.get(key).orEmpty()

    private fun baseHeaders(meta: Meta, appId: String): MutableMap<String, String> {
        val headers = linkedMapOf(
            "alipayminimark" to AlipayMiniMarkHelper.getAlipayMiniMark(appId, meta.version),
            "x-release-type" to "ONLINE",
            "referer" to "https://$appId.hybrid.alipay-eco.com/$appId/${meta.version}/index.html",
        )
        val version = alipayVersion()
        headers["user-agent"] = System.getProperty("http.agent", "").orEmpty() + " AliApp(AP/$version) AlipayClient/$version"
        return headers
    }

    /** haligame：优先复用当前账号身份，缺失时通过授权码登录。 */
    private fun reportHaligame(
        meta: Meta, appId: String, action: String, authCode: String,
        gameSession: GameSession.Snapshot?, headers: Map<String, String>,
    ): Boolean {
        var openId = gameSession?.credentials?.get("open_id").orEmpty()
        val reportHost = gameSession?.credentials?.get("reportHost") ?: param(appId, "reportHost")
        val loginIdentity = if (openId.isEmpty()) AccountSessionCoordinator.currentOrPersistedSessionIdentity() else null
        if (openId.isEmpty()) {
            val response = request("GET", "https://${param(appId, "loginHost")}/ajax/get_openid.php?auth_code=" +
                URLEncoder.encode(authCode, "UTF-8"), "", headers)
            val login = JSONObject(response)
            if (login.optInt("code", -1) != 0) return false
            openId = login.getJSONObject("data").getString("open_id")
            if (openId.isEmpty()) throw IOException("登录响应缺少 open_id")
        }
        val submit = linkedMapOf("action_code" to action, "action_finish_channel" to meta.channel, "open_id" to openId)
        REQUEST.get()!!.reporting = true
        val resp = try {
            postForm("https://$reportHost/ajax/user_action_submit.php", submit, headers)
        } finally {
            val state = REQUEST.get()!!
            if (gameSession != null && (state.httpStatus == 401 || state.httpStatus == 403) && state.sessionCurrent()) {
                GameSession.invalidate(gameSession)
            }
        }
        // 成功格式 {"code":"10000","msg":"Success"}（code 是字符串）
        val ok = JSONObject(resp).optString("code") == "10000"
        if (ok && loginIdentity != null) {
            GameSession.save(loginIdentity, appId, linkedMapOf("open_id" to openId, "reportHost" to reportHost))
        }
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}")
        return ok
    }

    /** dianwanshidai：user/login → ZfbSop/behavior。 */
    private fun reportDianwanshidai(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val login = JSONObject().put("openid", "").put("code", authCode)
            .put("query", JSONObject().put("channel", meta.channel))
        val loginHeaders = LinkedHashMap(headers)
        loginHeaders["content-type"] = "application/json; charset=utf-8"
        loginHeaders["appid"] = appId
        loginHeaders["app-id"] = appId
        loginHeaders["bgid"] = meta.companyId
        val loginResp = post("https://platformv2.dianwanshidai.com/api_v2/v1/user/login", login.toString(), loginHeaders)
        val openId = findString(loginResp, "openid", "open_id", "openId")
        if (openId.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#dianwanshidai 未取到 openid，响应=$loginResp")
            return false
        }
        val behavior = JSONObject().put("openid", openId).put("action_code", action)
        val channelContainer = param(appId, "channelContainer")
        if (channelContainer.isEmpty()) {
            behavior.put("channel", meta.channel)
        } else {
            behavior.put(channelContainer, JSONObject().put("channel", meta.channel))
        }
        REQUEST.get()!!.reporting = true
        val resp = post("https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior", behavior.toString(), loginHeaders)
        // 成功格式 {"code":200,"msg":"上报成功"}
        val ok = JSONObject(resp).optString("code") == "200"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}")
        return ok
    }

    /** yyxxgame：无登录，直接带支付宝 UID + md5 签名上报，同族域名取自表中 reportHost。 */
    private fun reportYyxx(meta: Meta, appId: String, action: String, headers: Map<String, String>): Boolean {
        // JSONObject.put(key, null) 会移除该键，服务端随即报 field "user_id" is not set。
        var userId = UserMap.currentUid
        if (userId.isNullOrEmpty()) userId = UserMap.currentUid
        if (userId.isNullOrEmpty()) {
            Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action，跳过：未取到支付宝 UID")
            return false
        }
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#user_id=$userId（len=${userId.length}），oid=${meta.oid}，gcid=${meta.gcid}，channel=${meta.channel}")
        val time = System.currentTimeMillis()
        val signSrc = "action_code=$action&action_finish_channel=${meta.channel}&gcid=${meta.gcid}&oid=${meta.oid}&user_id=$userId&time=${time}undefined"
        // 服务端只按表单字段解析：发 JSON 会 400 field "user_id" is not set。
        val fields = linkedMapOf(
            "user_id" to userId, "action_code" to action, "action_finish_channel" to meta.channel,
            "oid" to meta.oid, "gcid" to meta.gcid, "sign" to md5(signSrc), "time" to time.toString(),
        )
        val host = param(appId, "reportHost").ifEmpty { "fumodasapi.yyxxgame.com" }
        val reportUrl = "https://$host/alipay/alipay_game_report"
        REQUEST.get()!!.reporting = true
        val resp = postForm(reportUrl, fields, headers)
        // 成功格式 {"msg":"success","code":1}
        val ok = JSONObject(resp).optString("code") == "1"
        if (ok) {
            Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=成功")
        } else {
            Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=失败\n请求: POST $reportUrl\n参数: $fields\n响应: $resp")
        }
        return ok
    }

    /** nebula737（一步两步）：account/user/detail 登录 → behavior/report。 */
    private fun reportNebula(meta: Meta, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val params = linkedMapOf(
            "app_id" to meta.nebulaAppId, "channel_id" to meta.channelId, "code" to authCode,
            "company_id" to meta.companyId, "data_version" to "1", "device_id" to randomHex(32),
            "device_type" to Build.MODEL, "extend" to "", "game_version" to "1.7.15", "is_debug" to "0",
            "network_mode" to "0", "open_id" to "", "os" to "1", "os_version" to Build.VERSION.RELEASE,
            "passport_code" to "alipayminigame", "payment_code" to "alipayminigame", "sdk_version" to "3.4.58",
            "sign_nonce" to randomHexUpper(8), "sign_type" to "md5", "sign_version" to "1.0",
            "startup_id" to randomHex(32), "timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "union_id" to "", "user_id" to "0",
        )
        val login = JSONObject()
        for ((key, value) in params) login.put(key, value)
        login.put("is_debug", params.getValue("is_debug").toInt())
        login.put("os", params.getValue("os").toInt())
        login.put("sign", md5(encodedSorted(params) + "&" + meta.appKey))
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        val loginResp = post("https://gate-nebula.737.com/account/user/detail", login.toString(), jsonHeaders)
        val openId = findString(loginResp, "open_id", "openId")
        val userId = findString(loginResp, "user_id", "userId")
        if (openId.isEmpty() || userId.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#nebula 登录未取到 open_id/user_id，响应=$loginResp")
            return false
        }
        val report = linkedMapOf(
            "action_code" to action, "action_finish_channel" to meta.channel,
            "action_finish_date" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()),
            "app_id" to meta.nebulaAppId, "channel_id" to meta.channelId, "open_id" to openId,
            "out_biz_no" to randomHex(32), "user_id" to userId,
        )
        val body = JSONObject()
        for ((key, value) in report) {
            body.put(key, value)
            if (key == "channel_id") body.put(key, value.toInt())
        }
        body.put("sign", md5(encodedSorted(report) + "&" + meta.appKey))
        REQUEST.get()!!.reporting = true
        val resp = post("https://api-combine.feiyu.com/v1/alipayMiniGame/behavior/report", body.toString(), jsonHeaders)
        val response = JSONObject(resp)
        val ok = if (response.has("status")) response.optInt("status", -1) == 0 else response.optString("code") == "2000"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}")
        return ok
    }

    /** cpchxapi：auth/login-ali 换 JWT → analytics/ali-report（Bearer 鉴权）。 */
    private fun reportCpchx(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val login = JSONObject().put("appId", appId).put("code", authCode).put("anonymousCode", JSONObject.NULL)
            .put("channel", 3).put("inviteId", "")
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        val loginResp = post("https://cpchxapi.dianwanshidai.com/app-api/game/auth/login-ali", login.toString(), jsonHeaders)
        val token = findString(loginResp, "token")
        if (token.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#cpchx 未取到 token，响应=$loginResp")
            return false
        }
        val report = JSONObject().put("eventName", action).put("channel", meta.channel)
        val reportHeaders = LinkedHashMap(jsonHeaders)
        reportHeaders["authorization"] = "Bearer $token"
        REQUEST.get()!!.reporting = true
        val resp = post("https://cpchxapi.dianwanshidai.com/app-api/game/analytics/ali-report", report.toString(), reportHeaders)
        // 成功格式 {"code":0,"msg":"","data":null}
        val ok = JSONObject(resp).optString("code") == "0"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}")
        return ok
    }

    /** hnycgames：Channel/login 换 yxtChannelUserId → reportaction，无签名与 clientSendBI。 */
    private fun reportHnyc(meta: Meta, action: String, authCode: String, headers: Map<String, String>): Boolean {
        // action 形如 wdhysj_game_dingdan_every_2，前缀即 yxtGame 目录名。
        val index = action.indexOf("_game_")
        val game = if (index > 0) action.substring(0, index) else "wdhysj"
        val segments = "yxtGame/$game/yxtChannel/myxyx/yxtSubChannel/myxyx"
        val hnycBase = "https://h5sdk.hnycgames.cn"
        val formHeaders = LinkedHashMap(headers)
        formHeaders["content-type"] = "application/x-www-form-urlencoded"
        val loginBody = "authCode=" + URLEncoder.encode(authCode, "UTF-8") + "&scene=" + URLEncoder.encode(meta.channel, "UTF-8")
        val loginResp = post("$hnycBase/Channel/login/$segments", loginBody, formHeaders)
        val yxtChannelUserId = findString(loginResp, "yxtChannelUserId")
        if (yxtChannelUserId.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#hnyc 未取到 yxtChannelUserId，响应=$loginResp")
            return false
        }
        val reportBody = "action_code=" + URLEncoder.encode(action, "UTF-8") + "&channel=" + URLEncoder.encode(meta.channel, "UTF-8") +
            "&yxtChannelUserId=" + URLEncoder.encode(yxtChannelUserId, "UTF-8")
        REQUEST.get()!!.reporting = true
        val resp = post("$hnycBase/channel/index/yxtMethod/reportaction/$segments", reportBody, formHeaders)
        val ok = JSONObject(resp).optString("errorCode") == "0"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}" + if (ok) "" else "，响应=$resp")
        return ok
    }

    /** dianli100（农场对对碰）：farm/openid 登录 → farm/action/report，上报重新取 authCode。 */
    private fun reportDianli100(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val base = "https://alifunc.dianli100.com/majiang/alipay/farm"
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        val login = JSONObject().put("authCode", authCode)
        val loginResp = post("$base/openid", login.toString(), jsonHeaders)
        val openId = findString(loginResp, "data")
        if (openId.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#dianli100 未取到 openid，响应=$loginResp")
            return false
        }
        // 每个动作上报都带一个刚生成的新 authCode，与登录时不同。
        val reportCode = AuthCodeHelper.getAuthCode(appId)
        if (reportCode.isNullOrEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#dianli100 上报取不到授权码")
            return false
        }
        val report = JSONObject().put("authCode", reportCode).put("channel", meta.channel).put("actionCode", action)
        REQUEST.get()!!.reporting = true
        val resp = post("$base/action/report", report.toString(), jsonHeaders)
        val ok = JSONObject(resp).optString("code") == "200"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"} openid=$openId")
        return ok
    }

    /**
     * tomato（时尚百货城）：initDevices → zfbMiniGame 换 channelUserId → event/push。
     * 签名为全部字段（除 sign）按 key 排序 k=v 用 & 连接（含空值，值不编码），末尾追加 secret。
     */
    private fun reportTomato(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val base = "https://ssbhc-minigame-sdk.tomatogames.com"
        val secret = param(appId, "secret")
        val gameId = param(appId, "gameId")
        val platformKey = param(appId, "platformKey")
        val sdkChannel = param(appId, "sdkChannel")
        val cid = param(appId, "cid")
        val uuid = deviceUuid()
        val gid = String.format(Locale.ROOT, "%010d-%d", (RANDOM.nextLong() and Long.MAX_VALUE) % 10000000000L, System.currentTimeMillis())
        val base2 = linkedMapOf(
            "advertisingId" to uuid, "androidId" to uuid, "channel" to sdkChannel, "cid" to cid,
            "device_no" to uuid, "gameId" to gameId, "gid" to gid, "idfa" to uuid, "idfv" to uuid,
            "imei" to uuid, "ip" to "", "mac" to "", "model" to Build.MODEL, "network_type" to "4g",
            "oaid" to uuid, "sdk_version" to "1.0.0", "type" to "0", "udid" to alipayVersion(), "version" to "1.0.171",
        )
        val initParams = LinkedHashMap(base2)
        initParams["afContent"] = ""
        initParams["channelType"] = "1"
        initParams["extension"] = JSONObject().put("miniprogram", JSONObject().put("zfb_uid", uuid)).toString()
        initParams["sign"] = tomatoSign(initParams, secret)
        val initResp = postForm("$base/init/v2/sdk/initDevices", initParams, headers)
        if (JSONObject(initResp).optString("code") != "1") {
            Log.debug("游戏任务⏭️[${meta.name}]#tomato initDevices 失败，响应=$initResp")
            return false
        }
        // zfbMiniGame 登录的 extension 中传入 authCode。
        val loginParams = LinkedHashMap(base2)
        loginParams["channelType"] = "1"
        loginParams["extSdkData"] = JSONObject().put("source", JSONObject().put("platform", "my").put("ext", JSONObject())).toString()
        loginParams["extension"] = JSONObject().put("loginMode", "99")
            .put("extension", JSONObject().put("miniprogram", JSONObject().put("zfb_uid", uuid)))
            .put("code", authCode).toString()
        loginParams["method"] = ""
        loginParams["sign"] = tomatoSign(loginParams, secret)
        val loginResp = postForm("$base/merge/v3/login/inland/zfbMiniGame", loginParams, headers)
        val channelUserId = findString(loginResp, "channelUserId")
        if (channelUserId.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#tomato 登录未取到 channelUserId，响应=$loginResp")
            return false
        }
        // event/push 使用 JSON 请求体；pushParams 是 JSON 字符串，openId=channelUserId。
        val pushParams = JSONObject().put("eventId", action).put("eventFinishChannel", meta.channel)
            .put("isCustom", true)
            .put("eventFinishDate", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
            .put("openId", channelUserId).toString()
        val pushSign = LinkedHashMap(base2)
        pushSign["platformKey"] = platformKey
        pushSign["platformType"] = "ali"
        pushSign["pushParams"] = pushParams
        val push = JSONObject()
        for ((key, value) in pushSign) push.put(key, value)
        push.put("sign", tomatoSign(pushSign, secret))
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        REQUEST.get()!!.reporting = true
        val resp = post("$base/init/platform/event/push", push.toString(), jsonHeaders)
        val ok = JSONObject(resp).optString("code") == "1"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}")
        return ok
    }

    /** flourish（斗破苍穹）：QuickGame/login 换 openid/token → callMethod(incrementGameAction)。 */
    private fun reportFlourish(
        meta: Meta, appId: String, action: String, authCode: String,
        gameSession: GameSession.Snapshot, headers: Map<String, String>,
    ): Boolean {
        val event = JSONObject(gameSession.credentials.getValue("channel_params"))
        if (event.optString("role_id").isEmpty() || event.optString("server_id").isEmpty()) {
            throw IOException("角色上下文缺少 role_id/server_id")
        }
        val base = "https://${param(appId, "reportHost")}/QuickGame"
        val appKey = param(appId, "appKey")
        if (appKey.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#flourish 缺 appKey（表 param），跳过")
            return false
        }
        var userId = UserMap.currentUid
        if (userId.isNullOrEmpty()) userId = UserMap.currentUid
        if (userId.isNullOrEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#flourish 未取到支付宝 UID，跳过")
            return false
        }
        val launchOptions = JSONObject().put("channel", meta.channel).toString()
        val loginParams = linkedMapOf(
            "c_id" to "1", "game_id" to param(appId, "gameId"), "game_version" to "1.0.0", "sdk_version" to "1.1.2",
            "agent_id" to "1001", "site_id" to "10001", "sdk_channel" to "qg_alipay", "brand" to "",
            "model" to (Build.MANUFACTURER + " " + Build.MODEL), "os_type" to "android", "is_quick_game" to "1",
            "device" to ("qg_" + md5(userId)), "timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "timezone" to "Asia/Shanghai", "utctime" to "UTC+8", "launch_options" to launchOptions,
            "channel_params" to JSONObject().put("code", authCode).toString(),
        )
        loginParams["sign"] = flourishSign(loginParams, appKey)
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        val loginResp = post("$base/login", flourishJson(loginParams).toString(), jsonHeaders)
        val openId = findString(loginResp, "openid")
        val accessToken = findString(loginResp, "access_token")
        val refreshToken = findString(loginResp, "refresh_token")
        val uid = findString(loginResp, "uid")
        val uname = findString(loginResp, "uname")
        if (JSONObject(loginResp).optString("code") != "0" || openId.isEmpty() || accessToken.isEmpty() || uid.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#flourish 登录未取到 openid/token，响应=$loginResp")
            return false
        }
        event.put("launch_options", JSONObject().put("channel", meta.channel))
        event.put("event", "alipay_game_action")
        event.put("action_code", action)
        val reportParams = LinkedHashMap(loginParams)
        reportParams.remove("sign")
        reportParams.remove("launch_options")
        reportParams["timestamp"] = (System.currentTimeMillis() / 1000).toString()
        reportParams["access_token"] = accessToken
        reportParams["refresh_token"] = refreshToken
        reportParams["openid"] = openId
        reportParams["uid"] = uid
        reportParams["uname"] = uname
        reportParams["method"] = "incrementGameAction"
        reportParams["channel_params"] = event.toString()
        reportParams["sign"] = flourishSign(reportParams, appKey)
        val reportHeaders = baseHeaders(meta, appId)
        reportHeaders["content-type"] = "application/json; charset=utf-8"
        REQUEST.get()!!.reporting = true
        val resp = post("$base/callMethod", flourishJson(reportParams).toString(), reportHeaders)
        val ok = JSONObject(resp).optString("code") == "0"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}" + if (ok) "" else "，响应=$resp")
        return ok
    }

    /** devourad：alipay/gameLogin → gameReport，MD5(appid + appSecret + 参数直拼)。 */
    private fun reportDevourad(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val secret = param(appId, "appKey")
        if (secret.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#devourad 缺 appKey（表 param），跳过")
            return false
        }
        val base = "https://api.devourad.com"
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        val login = JSONObject().put("appid", appId).put("code", authCode).put("sign", md5(appId + secret + authCode))
        val loginResp = post("$base/alipay/gameLogin", login.toString(), jsonHeaders)
        val openId = findString(loginResp, "open_id", "openid")
        if (openId.isEmpty() || JSONObject(loginResp).optString("code") != "1") {
            Log.debug("游戏任务⏭️[${meta.name}]#devourad 登录失败，响应=$loginResp")
            return false
        }
        val outBizNo = md5(UUID.randomUUID().toString())
        val report = JSONObject().put("appid", appId).put("action_code", action).put("action_finish_channel", meta.channel)
            .put("open_id", openId).put("out_biz_no", outBizNo)
            .put("sign", md5(appId + secret + action + meta.channel + outBizNo + openId))
        REQUEST.get()!!.reporting = true
        val resp = post("$base/alipay/gameReport", report.toString(), jsonHeaders)
        val ok = JSONObject(resp).optString("code") == "1"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}" + if (ok) "" else "，响应=$resp")
        return ok
    }

    /** microfun（浪漫餐厅）：getDeviceInfo 换 openid → uploadActionNew，无签名。 */
    private fun reportMicrofun(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val path = param(appId, "path")
        if (path.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#microfun 缺 path（表 param），跳过")
            return false
        }
        val base = "https://xyx-txeo.cliapi.microfun.cn/zhifubao"
        val loginResp = request("GET", "$base/getDeviceInfo/$path?code=" + URLEncoder.encode(authCode, "UTF-8"), "", headers)
        val openId = findString(loginResp, "openid", "open_id")
        if (openId.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#microfun 登录未取到 openid，响应=$loginResp")
            return false
        }
        val report = linkedMapOf(
            "action_finish_date" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()),
            "open_id" to openId, "action_code" to action, "action_finish_channel" to meta.channel,
        )
        REQUEST.get()!!.reporting = true
        val resp = postForm("https://xyx.cliapi.microfun.cn/zhifubao/uploadActionNew/$path", report, headers)
        val ok = JSONObject(resp).optString("code") == "10000"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}" + if (ok) "" else "，响应=$resp")
        return ok
    }

    /**
     * qufang（农场纸牌塔等）：account/login 换 uid/token → increment/basic。
     * signature = "v3_" + SHA1("qyqf-cocos" + "3" + appid + versionApp + uid + token + t + message)。
     */
    private fun reportQufang(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val loginHost = param(appId, "loginHost").ifEmpty { "login-01.qufang.top" }
        val reportHost = param(appId, "reportHost").ifEmpty { "game-factory-jail-001.qufang.top" }
        val versionApp = param(appId, "versionApp").ifEmpty { "4.4.5" }
        val loginHeaders = LinkedHashMap(headers)
        loginHeaders["content-type"] = "application/json; charset=utf-8"
        loginHeaders["qy-appid"] = appId
        loginHeaders["qy-rid"] = UUID.randomUUID().toString()
        val systemInfo = JSONObject().put("screenWidth", 1080).put("screenHeight", 2400)
            .put("windowWidth", 411).put("windowHeight", 914).put("pixelRatio", 2.625)
            .put("model", Build.MODEL).put("system", Build.VERSION.RELEASE).put("version", alipayVersion())
            .put("language", "zh-Hans").put("isIphoneXSeries", false)
        val launchOptions = JSONObject().put("path", "").put("query", JSONObject().put("channel", meta.channel))
            .put("referrerInfo", JSONObject())
        val hostName = JSONObject().put("login", loginHost).put("game_factory", reportHost)
            .put("event", "event-jail-001.qufang.top").put("more_game", "moregame.qufang.top")
            .put("static_resources_public", "static-resources-public.qufang.top")
        val message = JSONObject().put("version", 3).put("versionApp", versionApp).put("type", "alipay")
            .put("code", authCode).put("idx", 0).put("launchOptions", launchOptions).put("systemInfo", systemInfo)
            .put("stored_part", JSONObject().put("part", JSONObject().put("syncVersion", 0)))
            .put("hostName", hostName).put("res", JSONObject().put("code", authCode))
        val login = JSONObject().put("version", 3).put("versionApp", versionApp).put("platformType", "alipay")
            .put("appid", appId).put("t", System.currentTimeMillis()).put("message", message)
        val loginResp = post("https://$loginHost/account/login", login.toString(), loginHeaders)
        val uid = findString(loginResp, "uid")
        val token = findString(loginResp, "token")
        if (JSONObject(loginResp).optString("ok") != "1" || uid.isEmpty() || token.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#qufang 登录未取到 uid/token，响应=$loginResp")
            return false
        }
        val reportTs = System.currentTimeMillis()
        val actionMessage = JSONObject().put("action_code", action).put("action_finish_channel", meta.channel)
            .put("action_finish_time", reportTs)
        val signature = "v3_" + sha1("qyqf-cocos" + "3" + appId + versionApp + uid + token + reportTs + qufangMessage(actionMessage))
        val report = JSONObject().put("version", 3).put("versionApp", versionApp).put("platformType", "alipay")
            .put("appid", appId).put("uid", uid).put("token", token).put("t", reportTs)
            .put("message", actionMessage).put("signature", signature)
        val reportHeaders = LinkedHashMap(headers)
        reportHeaders["content-type"] = "application/json; charset=utf-8"
        reportHeaders["qy-appid"] = appId
        reportHeaders["qy-rid"] = UUID.randomUUID().toString()
        reportHeaders["qy-uid"] = uid
        REQUEST.get()!!.reporting = true
        val resp = post("https://$reportHost/platform_service/increment/basic", report.toString(), reportHeaders)
        val ok = JSONObject(resp).optString("ok") == "1"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}" + if (ok) "" else "，响应=$resp")
        return ok
    }

    /** qufang 的 message 签名串：key 字典序、过滤空值、值 URL 编码后用 & 连接。 */
    private fun qufangMessage(message: JSONObject): String {
        val names = message.names()
        val keys = mutableListOf<String>()
        if (names != null) {
            for (i in 0 until names.length()) keys.add(names.optString(i))
        }
        keys.sort()
        val sb = StringBuilder()
        for (key in keys) {
            val value = message.optString(key)
            if (value.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(key).append('=').append(URLEncoder.encode(value, "UTF-8").replace("+", "%20"))
        }
        return sb.toString()
    }

    /** qmhd87（四季物语）：openid → login → report，body 只有 Base64(DES-ECB(Base64(gzip(JSON)))) 的 eData。 */
    private fun reportQmhd87(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val host = param(appId, "reportHost").ifEmpty { "game2-sjwy.qmhd87.com" }
        val channelCode = param(appId, "channelCode").ifEmpty { "PA0306" }
        val packageName = param(appId, "packageName").ifEmpty { "projectA" }
        val sdkVersion = param(appId, "sdkVersion").ifEmpty { "6.9.7" }
        val base = "https://$host"
        val installId = System.currentTimeMillis()
        val did = "rc$installId"
        val noise = (100000000 + RANDOM.nextInt(900000000)).toString()
        val openidHeaders = qmhdHeaders(headers, did, installId, noise, channelCode, packageName, sdkVersion)
        val openidBody = JSONObject().put("eData", qmhdEncrypt(JSONObject().put("code", authCode).toString()))
        val openidResp = post("$base/api/v1/ap/openid/", openidBody.toString(), openidHeaders)
        val openidJson = qmhdJson(openidResp)
        val openId = if (openidJson == null) "" else findString(openidJson, "openid", "open_id")
        val token = if (openidJson == null) "" else findString(openidJson, "token", "authorization", "accessToken", "access_token")
        if (openId.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#qmhd87 未取到 openid，响应=$openidResp")
            return false
        }
        val loginHeaders = qmhdHeaders(headers, openId, installId, noise, channelCode, packageName, sdkVersion)
        if (token.isNotEmpty()) loginHeaders["authorization"] = "Bearer $token"
        val loginPlain = JSONObject().put("type", 6).put("account", openId).put("extData", JSONObject())
        val loginBody = JSONObject().put("eData", qmhdEncrypt(loginPlain.toString()))
        val loginResp = post("$base/api/v1/login/", loginBody.toString(), loginHeaders)
        val loginJson = qmhdJson(loginResp)
        val loginToken = if (loginJson == null) "" else findString(loginJson, "token", "authorization", "accessToken", "access_token")
        val reportHeaders = qmhdHeaders(headers, openId, installId, noise, channelCode, packageName, sdkVersion)
        val bearer = loginToken.ifEmpty { token }
        if (bearer.isNotEmpty()) reportHeaders["authorization"] = "Bearer $bearer"
        val reportPlain = JSONObject().put("actionCode", action).put("actionFinishAt", System.currentTimeMillis() / 1000)
            .put("actionFinishChannel", meta.channel)
        val reportBody = JSONObject().put("eData", qmhdEncrypt(reportPlain.toString()))
        REQUEST.get()!!.reporting = true
        val resp = post("$base/api/v1/ap/report/", reportBody.toString(), reportHeaders)
        val json = qmhdJson(resp)
        val ok = json != null && json.optInt("code", -1) == 0
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}" + if (ok) "" else "，响应=$resp")
        return ok
    }

    private fun qmhdHeaders(
        base: Map<String, String>, did: String, installId: Long, noise: String,
        channelCode: String, packageName: String, sdkVersion: String,
    ): MutableMap<String, String> {
        val timestamp = System.currentTimeMillis() / 1000
        val headers = LinkedHashMap(base)
        headers["content-type"] = "application/json; charset=utf-8"
        headers["channel"] = channelCode
        headers["did"] = did
        headers["install-id"] = installId.toString()
        headers["noise"] = noise
        headers["package-name"] = packageName
        headers["run-id"] = installId.toString()
        headers["timestamp"] = timestamp.toString()
        headers["version"] = sdkVersion
        headers["sign"] = md5(channelCode + did + installId + noise + packageName + installId + timestamp + sdkVersion + "6KscZatA")
        return headers
    }

    /** qmhd87 响应可能是明文 JSON，也可能是 eData 密文。 */
    private fun qmhdJson(response: String?): JSONObject? {
        if (response.isNullOrEmpty()) return null
        try {
            val jo = JSONObject(response)
            val encrypted = jo.optString("eData")
            return if (encrypted.isEmpty()) jo else JSONObject(qmhdDecrypt(encrypted))
        } catch (_: Exception) {
        }
        return try {
            JSONObject(qmhdDecrypt(response))
        } catch (_: Exception) {
            null
        }
    }

    private val QMHD_DES_KEY = "cTawb7/c".toByteArray(StandardCharsets.UTF_8)

    private fun qmhdEncrypt(plain: String): String {
        val gzipBase64 = Base64.encodeToString(gzip(plain.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(QMHD_DES_KEY, "DES"))
        return Base64.encodeToString(cipher.doFinal(gzipBase64.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun qmhdDecrypt(data: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(QMHD_DES_KEY, "DES"))
        val inner = cipher.doFinal(Base64.decode(data, Base64.DEFAULT))
        val gzipBase64 = String(inner, StandardCharsets.UTF_8).trim { it <= ' ' }
        return gunzip(Base64.decode(gzipBase64, Base64.DEFAULT))
    }

    /** lumosfun/gusspro（弓箭手对决）：MiniGameServer 登录 → mini-alipay event/submit。 */
    private fun reportLumosfun(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val loginHost = param(appId, "loginHost").ifEmpty { "azory.lumosfun.com:8525" }
        val reportHost = param(appId, "reportHost").ifEmpty { "gjdz-zfb-def.gusspro.com:15087" }
        val loginUrl = "https://$loginHost/MiniGameServer/minigame/login.do?userType=31&appId=" +
            URLEncoder.encode(appId, "UTF-8") + "&code=" + URLEncoder.encode(authCode, "UTF-8")
        val loginResp = request("GET", loginUrl, "", headers)
        val openId = findString(loginResp, "openId", "openid", "open_id")
        if (JSONObject(loginResp).optString("code") != "0" || openId.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#lumosfun 登录未取到 openId，响应=$loginResp")
            return false
        }
        val report = JSONObject().put("openId", openId).put("eventId", action).put("EventFinishChannel", meta.channel)
            .put("AlipayUserId", "")
            .put("EventFinishDate", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
            .put("OutBizNo", "SB_${System.currentTimeMillis() / 1000}")
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        val resp = post("https://$reportHost/mini-alipay/event/submit", report.toString(), jsonHeaders)
        val ok = JSONObject(resp).optString("code") == "0"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}" + if (ok) "" else "，响应=$resp")
        return ok
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): String = GZIPInputStream(ByteArrayInputStream(data)).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val len = input.read(buffer)
            if (len <= 0) break
            out.write(buffer, 0, len)
        }
        String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun sha1(text: String): String = hex(MessageDigest.getInstance("SHA-1").digest(text.toByteArray(StandardCharsets.UTF_8)))

    /** flourish 请求体：数字字段保持 JSON number，其余保持字符串。 */
    private fun flourishJson(params: Map<String, String>): JSONObject {
        val json = JSONObject()
        for ((key, value) in params) {
            when (key) {
                "c_id", "game_id", "agent_id", "site_id", "is_quick_game" -> json.put(key, value.toInt())
                "timestamp", "uid" -> json.put(key, value.toLong())
                else -> json.put(key, value)
            }
        }
        return json
    }

    /** flourish 变体 A：嵌套 JSON 字符串整体不参与签名，空值字段仍保留。 */
    private fun flourishSign(params: Map<String, String>, appKey: String): String {
        val sb = StringBuilder()
        for (key in params.keys.sorted()) {
            when (key) {
                "sign", "channel_params", "launch_options", "role_name", "server_name", "goods_name", "extension", "event_params" -> continue
            }
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(key).append('=').append(params[key])
        }
        sb.append(appKey)
        return md5(sb.toString())
    }

    /** raiyantway（占城大师）：login.do 换 openId → reportGameAction，使用 ENCODE_DATA 加密。 */
    private fun reportRaiyantway(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val host = param(appId, "loginHost")
        if (host.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#raiyantway 缺 loginHost（表 param），跳过")
            return false
        }
        val userType = param(appId, "userType").ifEmpty { "31" }
        val base = "https://$host/MiniGameServer"
        val loginResp = request("GET", "$base/minigame/login.do?appId=" + URLEncoder.encode(appId, "UTF-8") +
            "&userType=" + URLEncoder.encode(userType, "UTF-8") + "&code=" + URLEncoder.encode(authCode, "UTF-8"), "", headers)
        val openId = findString(loginResp, "openId")
        if (JSONObject(loginResp).optString("code") != "0" || openId.isEmpty()) {
            Log.debug("游戏任务⏭️[${meta.name}]#raiyantway 登录未取到 openId，响应=$loginResp")
            return false
        }
        val plain = JSONObject().put("appId", appId).put("openId", openId).put("actionCode", action)
            .put("actionFinishChannel", meta.channel).put("outBizNo", UUID.randomUUID().toString())
            .put("actionFinishDate", System.currentTimeMillis())
        val fields = linkedMapOf("ENCODE_DATA" to raiyantwayEncode(plain.toString()))
        REQUEST.get()!!.reporting = true
        val resp = postForm("$base/alipay/reportGameAction", fields, headers)
        val ok = JSONObject(resp).optString("code") == "0"
        Log.debug("游戏任务🎮厂商上报[${meta.name}]#$action 结果=${if (ok) "成功" else "失败"}" + if (ok) "" else "\n明文: $plain\n响应: $resp")
        return ok
    }

    /** UTF-8 字节补零至 4 的倍数，逐字节 XOR 随机 4 字节 key，密文拼接 key 后 base64。 */
    private fun raiyantwayEncode(plainJson: String): String {
        val data = plainJson.toByteArray(StandardCharsets.UTF_8)
        val pad = (4 - data.size % 4) % 4
        val key = ByteArray(4)
        RANDOM.nextBytes(key)
        val out = ByteArray(data.size + pad + 4)
        for (i in 0 until data.size + pad) {
            val b = if (i < data.size) data[i].toInt() else 0
            out[i] = (b xor key[i % 4].toInt()).toByte()
        }
        System.arraycopy(key, 0, out, data.size + pad, 4)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** mmhygame：login 换 openid → api/reqZfbGameEvent，登录 Cookie 随当前请求会话传递。 */
    private fun reportMmhygame(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val gameName = param(appId, "gameName")
        val loginUrl = if (gameName.isEmpty()) tableUrl(appId, "login", 0, "")
            else "https://$gameName.xyx.mmhygame.com/$gameName/login"
        val submitUrl = if (gameName.isEmpty()) tableUrl(appId, "submit", 0, "")
            else "https://$gameName.xyx.mmhygame.com/$gameName/api/reqZfbGameEvent"
        if (loginUrl.isEmpty() || submitUrl.isEmpty()) return false
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        val login = JSONObject().put("type", 7).put("code", authCode).put("version", meta.version).put("packageType", 2)
        val loginResp = post(loginUrl, login.toString(), jsonHeaders)
        val loginData = JSONObject(loginResp)
        val openId = loginData.optJSONObject("player")?.optString("openId").orEmpty()
        if (loginData.optString("code") != "0" || openId.isEmpty()) return false
        val report = JSONObject().put("eventId", action).put("eventChannel", meta.channel)
        REQUEST.get()!!.reporting = true
        val resp = post(submitUrl, report.toString(), jsonHeaders)
        return JSONObject(resp).optString("code") == "0" || JSONObject(resp).optBoolean("success")
    }

    /** trueplay（无尽探险队）：biu-ccb 换 token → mGame/login 换 uid → userAction(GET)。 */
    private fun reportTrueplay(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val site = param(appId, "site")
        val siteKey = param(appId, "siteKey")
        val aid = param(appId, "aid")
        val sdkAppId = param(appId, "sdkAppId")
        if (site.isEmpty() || siteKey.isEmpty()) return false
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["accept-charset"] = "UTF-8"
        jsonHeaders["content-type"] = "application/json"
        val channelId = param(appId, "channelId")
        val biuTarget = if (sdkAppId.isEmpty()) {
            tableUrl(appId, "login", 0, "https://minigame-cli.youwongames.com/biu-ccb/") + "alipay/login?biuopen=%7C%7C%7C%7C%7C"
        } else "https://minigame-cli.youwongames.com/biu-ccb/$sdkAppId/alipay/login?biuopen=%7C%7C%7C%7C%7C"
        val biu = JSONObject().put("auth_code", authCode)
        val biuResp = post(biuTarget, biu.toString(), jsonHeaders)
        val token = findString(biuResp, "token", "biuToken")
        if (token.isEmpty()) return false
        val login = linkedMapOf(
            "site" to site, "appid" to sdkAppId, "js_code" to token, "aid" to aid, "code" to "",
            "channel" to meta.channel, "channel_parame" to "{\"channel\":\"other\"}",
        )
        val seconds = System.currentTimeMillis() / 1000
        login["time"] = seconds.toString()
        login["sign"] = trueplaySign(login, site, siteKey)
        val loginUrl2 = if (channelId.isEmpty()) tableUrl(appId, "login", 1, "https://center.trueplay.cn/mGame/login/")
            else "https://center.trueplay.cn/mGame/login/$channelId"
        val loginResp = post(loginUrl2, JSONObject(login).toString(), jsonHeaders)
        val uid = findString(loginResp, "uid", "userId", "user_id")
        if (uid.isEmpty()) return false
        val query = linkedMapOf(
            "site" to site, "aid" to aid, "uid" to uid, "device" to UUID.randomUUID().toString().replace("-", ""),
            "action_code" to action, "action_finish_channel" to meta.channel,
            "time" to (System.currentTimeMillis() / 1000).toString(),
        )
        query["sign"] = trueplaySign(query, site, siteKey)
        val qs = StringBuilder()
        for ((key, value) in query) {
            if (qs.isNotEmpty()) qs.append('&')
            qs.append(URLEncoder.encode(key, "UTF-8")).append('=').append(URLEncoder.encode(value, "UTF-8"))
        }
        val submitUrl = if (channelId.isEmpty()) tableUrl(appId, "submit", 0, "https://center.trueplay.cn/mGame/userAction/")
            else "https://center.trueplay.cn/mGame/userAction/$channelId"
        REQUEST.get()!!.reporting = true
        val resp = request("GET", "$submitUrl?$qs", "", headers)
        return JSONObject(resp).optString("code") == "0" || JSONObject(resp).optBoolean("success")
    }

    /** trueplay 签名：排序 k=v& 拼接（排除 sign），md5(site + 串 + siteKey) 小写。 */
    private fun trueplaySign(body: Map<String, String>, site: String, siteKey: String): String {
        val sb = StringBuilder()
        for (key in body.keys.sorted()) {
            if (key == "sign") continue
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(key).append('=').append(body[key].orEmpty())
        }
        return md5(site + sb + siteKey).lowercase(Locale.ROOT)
    }

    /** igame58（货柜消消消等）：GET getOpenId 换 openid → POST ali/action/submit。 */
    private fun reportIgame58(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val loginUrl = tableUrl(appId, "login", 0, "")
        val submitUrl = tableUrl(appId, "submit", 0, "")
        if (loginUrl.isEmpty() || submitUrl.isEmpty()) return false
        val umid = param(appId, "umid")
        val query = StringBuilder("?code=").append(URLEncoder.encode(authCode, "UTF-8")).append("&channel=zhifubao&platform=0")
        if (umid.isNotEmpty()) query.append("&umid=").append(URLEncoder.encode(umid, "UTF-8"))
        val platformHeaders = LinkedHashMap(headers)
        platformHeaders["platform"] = "0"
        platformHeaders["channel"] = "zhifubao"
        if (umid.isNotEmpty()) platformHeaders["umid"] = umid
        val loginResp = request("GET", loginUrl + query, "", platformHeaders)
        val openId = findString(loginResp, "openId", "openid", "open_id", "data")
        if (openId.isEmpty()) return false
        val jsonHeaders = LinkedHashMap(platformHeaders)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        jsonHeaders["device"] = "{}"
        jsonHeaders["openid"] = openId
        jsonHeaders["usertag"] = ""
        jsonHeaders["uuid"] = UUID.randomUUID().toString().replace("-", "")
        jsonHeaders["versioncode"] = "0"
        val report = JSONObject().put("actionCode", action).put("actionFinishChannel", meta.channel)
            .put("channel", "zhifubao").put("platform", 0)
        if (umid.isNotEmpty()) report.put("umid", umid)
        REQUEST.get()!!.reporting = true
        val resp = post(submitUrl, report.toString(), jsonHeaders)
        return JSONObject(resp).optString("code") == "0" || JSONObject(resp).optBoolean("success")
    }

    /** soulgame（超元火柴人）：ThreeLogin 取 data.mid，沿现有 userId 参数上报动作。 */
    private fun reportSoulgame(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val loginUrl = tableUrl(appId, "login", 0, "")
        val submitUrl = tableUrl(appId, "submit", 0, "")
        if (loginUrl.isEmpty() || submitUrl.isEmpty()) return false
        val formHeaders = LinkedHashMap(headers)
        formHeaders["content-type"] = "application/x-www-form-urlencoded"
        val deviceId = md5(UUID.randomUUID().toString().replace("-", ""))
        val login = linkedMapOf(
            "appkey" to meta.appKey, "channel" to "zhifubao", "device_id" to deviceId, "distinct_id" to deviceId,
            "logintype" to "qg_alipay", "openid" to "", "token" to authCode, "ext" to "",
            "client_ver" to "1.0.0", "client_type" to "4", "sdkLogin" to "1",
        )
        login["sig"] = soulgameSig(login)
        val loginResp = postForm(loginUrl, login, formHeaders)
        val userId = findString(JSONObject(loginResp).optJSONObject("data"), "mid")
            .ifEmpty { findString(loginResp, "userId", "user_id", "uid") }
        if (userId.isEmpty()) return false
        val report = linkedMapOf(
            "userId" to userId, "openId" to "", "action_code" to action, "action_finish_channel" to meta.channel,
            "action_finish_date" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()), "out_biz_no" to "",
        )
        report["sig"] = soulgameSig(report)
        REQUEST.get()!!.reporting = true
        val resp = postForm(submitUrl, report, formHeaders)
        return JSONObject(resp).optString("code") == "0" || JSONObject(resp).optBoolean("success")
    }

    /** soulgame 签名：常量前缀 + 排序后各值（排除 sig/ext），md5 后转大写。 */
    private fun soulgameSig(body: Map<String, String>): String {
        val sb = StringBuilder("S1G20A4I")
        for (key in body.keys.sorted()) {
            if (key == "sig" || key == "ext") continue
            sb.append(body[key].orEmpty())
        }
        return md5(sb.toString()).uppercase(Locale.ROOT)
    }

    /** qxygame：基础 URL 与 gameId 取自表，<base><gameId>GetUserId.php → <base><gameId>UpTask.php。 */
    private fun reportQxygame(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val gameId = param(appId, "gameId")
        var base = tableUrl(appId, "login", 0, "https://qxygame.zhangle.fun/")
        var submitBase = tableUrl(appId, "submit", 0, "https://qxygame.zhangle.fun/")
        if (gameId.isEmpty() || base.isEmpty() || submitBase.isEmpty()) return false
        if (!base.endsWith("/")) base += "/"
        if (!submitBase.endsWith("/")) submitBase += "/"
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        val login = JSONObject().put("code", authCode)
        val loginResp = post("$base${gameId}GetUserId.php", login.toString(), jsonHeaders)
        val userId = loginResp.trim().toLongOrNull()?.takeIf { it > 0L }?.toString()
            ?: findString(loginResp, "userId", "uid", "user_id", "uuid", "openid", "open_id", "data")
        if (userId.isEmpty()) return false
        REQUEST.get()!!.requireCurrent()
        val alipayUserId = AccountSessionCoordinator.currentSession()!!.userId
        val report = JSONObject()
            .put("action_finish_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
            .put("uuid", userId).put("alipay_user_id", alipayUserId).put("action_code", action).put("channel", meta.channel)
        REQUEST.get()!!.reporting = true
        val resp = post("$submitBase${gameId}UpTask.php", report.toString(), jsonHeaders)
        val result = if (resp.trimStart().startsWith("{")) JSONObject(resp) else {
            val status = resp.substringAfterLast("HTTP response code:", "").substringBefore("^_^").trim()
            if (status != "200") return false
            val body = resp.substringAfterLast("HTTP response body:", "")
            org.json.JSONTokener(body).nextValue() as? JSONObject ?: return false
        }
        return result.optString("code") == "0" || result.optBoolean("success") ||
            result.length() == 0 && resp.contains("HTTP response code:200^_^HTTP response body:")
    }

    /** mihuangame：user/login 取 authentication → alipay/submit，上报 token 放 authentication 头。 */
    private fun reportMihuangame(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val loginUrl = param(appId, "loginUrl")
        val submitUrl = param(appId, "submitUrl")
        val channelAppId = param(appId, "channelAppId")
        if (loginUrl.isEmpty() || submitUrl.isEmpty()) return false
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        val login = JSONObject()
        if (channelAppId.isNotEmpty()) {
            try {
                login.put("channelAppId", channelAppId.toInt())
            } catch (_: NumberFormatException) {
                login.put("channelAppId", channelAppId)
            }
        }
        login.put("code", authCode)
        val loginResp = post(loginUrl, login.toString(), jsonHeaders)
        val token = findString(loginResp, "authentication")
        if (token.isEmpty()) return false
        val submitHeaders = LinkedHashMap(jsonHeaders)
        submitHeaders["authentication"] = token
        val submit = JSONObject().put("action_code", action).put("action_finish_date", System.currentTimeMillis())
            .put("action_finish_channel", meta.channel)
        REQUEST.get()!!.reporting = true
        val resp = post(submitUrl, submit.toString(), submitHeaders)
        val trimmed = resp.trim { it <= ' ' }
        return trimmed.isEmpty() || JSONObject(trimmed).length() == 0 || JSONObject(trimmed).optString("code") == "0"
    }

    /** tuyoo（三国冰河时代/次神）：只做厂商登录，游戏自身 biLog/WebSocket 的进度不在此上报。 */
    private fun loginTuyoo(meta: Meta, appId: String, authCode: String, headers: Map<String, String>): Boolean {
        val loginUrl = param(appId, "loginUrl")
        val sdkAppId = param(appId, "sdkAppId")
        if (loginUrl.isEmpty() || sdkAppId.isEmpty()) return false
        val clientId = param(appId, "clientId").ifEmpty { "H5_5.0_tyGuest,tyAccount.alipay.0-hall$sdkAppId.aliminigame" }
        val deviceId = randomHex(32).uppercase(Locale.ROOT)
        val sceneParam = JSONObject().put("channel", meta.channel)
        val params = linkedMapOf(
            "ali_appId" to appId, "ali_authcode" to authCode, "appId" to sdkAppId, "gameId" to sdkAppId,
            "clientId" to clientId, "snsId" to "ali.cn.minigame:$authCode", "uuid" to deviceId, "original_deviceid" to deviceId,
            "osName" to "Android", "os_version" to Build.VERSION.RELEASE, "phone_model" to Build.MODEL, "k1" to "v1",
            "sdk_device_brand" to Build.BRAND, "sdk_origin_device_level_info" to "high",
            "sdk_session_id" to randomHex(32).uppercase(Locale.ROOT), "sdk_login_track_id" to randomHex(32).uppercase(Locale.ROOT),
            "scene_param" to Base64.encodeToString(sceneParam.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP),
        )
        REQUEST.get()!!.reporting = true
        val resp = postForm(loginUrl, params, headers)
        return JSONObject(resp).optString("code") == "0" || findString(resp, "authorCode").isNotEmpty()
    }

    /** yxmys（英雄没有闪）：quickAppEnter 登录，进游上报走 game_login_report，无通关类上报接口。 */
    private fun loginYxmys(meta: Meta, appId: String, authCode: String, headers: Map<String, String>): Boolean {
        val loginUrl = param(appId, "loginUrl")
        val cgameId = param(appId, "cgame_id")
        if (loginUrl.isEmpty()) return false
        val wxParam = JSONObject().put("channel", meta.channel)
        val params = linkedMapOf<String, String>()
        if (cgameId.isNotEmpty()) params["c_game_id"] = cgameId
        params["guid"] = UUID.randomUUID().toString()
        params["js_code"] = authCode
        params["wx_param"] = wxParam.toString()
        REQUEST.get()!!.reporting = true
        val resp = postForm(loginUrl, params, headers)
        return JSONObject(resp).optString("code") == "1" || !JSONObject(resp).isNull("app_ext")
    }

    private fun reportD3games(meta: Meta, appId: String, action: String, authCode: String, headers: Map<String, String>): Boolean {
        val host = param(appId, "reportHost").ifEmpty { "api-slhyj.d3games.com" }
        val base = "https://$host/api"
        val loginUrl = tableUrl(appId, "login", 0, "$base/login")
        val submitUrl = tableUrl(appId, "submit", 0, "$base/ali/incrementgameaction/submit")
        val gameId = param(appId, "gameId")
        val formHeaders = LinkedHashMap(headers)
        formHeaders["content-type"] = "application/x-www-form-urlencoded"
        // 登录 body 是 JSON 字符串，但 content-type 为 form-urlencoded。
        val loginParams = linkedMapOf("ver" to "2.0.1")
        if (gameId.isNotEmpty()) loginParams["gameid"] = gameId
        loginParams["appid"] = appId
        loginParams["sign_type"] = "md5"
        loginParams["time_stamp"] = (System.currentTimeMillis() / 1000).toString()
        loginParams["channel"] = meta.channel
        loginParams["model"] = Build.MODEL
        loginParams["version"] = alipayVersion()
        loginParams["system"] = Build.VERSION.RELEASE
        loginParams["platform"] = "Android"
        loginParams["shareuid"] = "0"
        loginParams["scene"] = "0"
        loginParams["sharekey"] = ""
        loginParams["shareid"] = "0"
        loginParams["clue_token"] = ""
        loginParams["creative_id"] = ""
        loginParams["advertister_id"] = ""
        loginParams["request_id"] = ""
        loginParams["code"] = authCode
        val login = JSONObject()
        for ((key, value) in loginParams) {
            if (key == "shareuid") login.put(key, 0) else login.put(key, value)
        }
        login.put("sign", d3Sign(loginParams, meta.appKey))
        val loginResp = post(loginUrl, login.toString(), formHeaders)
        val uid = findString(loginResp, "uid")
        val openId = findString(loginResp, "openid")
        val token = findString(loginResp, "token")
        if (uid.isEmpty() || token.isEmpty()) return false
        // 上报 uid 是数字，openid 是支付宝 UID，time_stamp 单位秒。
        val reportParams = linkedMapOf("ver" to "2.0.1")
        if (gameId.isNotEmpty()) reportParams["gameid"] = gameId
        reportParams["appid"] = appId
        reportParams["sign_type"] = "md5"
        reportParams["time_stamp"] = (System.currentTimeMillis() / 1000).toString()
        reportParams["action_code"] = action
        reportParams["action_finish_channel"] = meta.channel
        reportParams["uid"] = uid
        reportParams["openid"] = openId
        reportParams["token"] = token
        val report = JSONObject()
        for ((key, value) in reportParams) {
            if (key == "uid") report.put(key, value.toLong()) else report.put(key, value)
        }
        report.put("sign", d3Sign(reportParams, meta.appKey))
        REQUEST.get()!!.reporting = true
        val resp = post(submitUrl, report.toString(), formHeaders)
        return JSONObject(resp).optString("code") == "0"
    }

    /** d3games：按 key 排序拼接 k=v（无分隔符，排除 ver/sign/空值/"0" 值），末尾追加密钥。 */
    private fun d3Sign(params: Map<String, String>, secret: String): String {
        val sb = StringBuilder()
        for (key in params.keys.sorted()) {
            if (key == "ver" || key == "sign") continue
            val value = params[key]
            if (value.isNullOrEmpty() || value == "0") continue
            sb.append(key).append('=').append(value)
        }
        sb.append(secret)
        return md5(sb.toString())
    }

    private fun reportShryou(meta: Meta, appId: String, action: String, session: GameSession.Snapshot?, headers: Map<String, String>): Boolean {
        val uid = session?.credentials?.get("uid").orEmpty()
        if (uid.isEmpty()) throw IllegalStateException("请进入${meta.name}完成一次动作，以取得当前账号的游戏会话")
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val body = JSONObject().put("game", param(appId, "game")).put("uid", uid).put("action_code", action)
            .put("action_finish_date", now).put("action_finish_channel", meta.channel)
            .put("out_biz_no", "${uid}_${action}_${now}_${System.nanoTime()}")
        val jsonHeaders = LinkedHashMap(headers)
        jsonHeaders["content-type"] = "application/json; charset=utf-8"
        REQUEST.get()!!.reporting = true
        try {
            val response = post(param(appId, "submitUrl"), body.toString(), jsonHeaders)
            return JSONObject(response).optString("code") == "0"
        } finally {
            val state = REQUEST.get()!!
            if (session != null && state.httpStatus == 401) GameSession.invalidate(session)
        }
    }

    private fun tableUrl(appId: String, phase: String, step: Int, fallback: String): String {
        val urls = StringTokenizer(param(appId, phase + "Url"), ";")
        var index = 0
        while (urls.hasMoreTokens()) {
            val url = urls.nextToken()
            if (index == step) return url
            index++
        }
        return fallback
    }

    /** tomato 设备标识：androidId/idfa/oaid 等字段同值，进程内生成一次复用。 */
    private fun deviceUuid(): String {
        if (DEVICE_UUID == null) DEVICE_UUID = UUID.randomUUID().toString()
        return DEVICE_UUID!!
    }

    @Volatile
    private var DEVICE_UUID: String? = null

    private fun alipayVersion(): String = try {
        ApplicationHook.alipayVersion.versionString
    } catch (_: Exception) {
        ""
    }

    /** tomato 签名：全部字段（除 sign）按 key 排序 k=v 用 & 连接（含空值），末尾追加密钥。 */
    private fun tomatoSign(params: Map<String, String>, secret: String): String {
        val sb = StringBuilder()
        for (key in params.keys.sorted()) {
            if (key == "sign") continue
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(key).append('=').append(params[key])
        }
        sb.append(secret)
        return md5(sb.toString())
    }

    /** 以 application/x-www-form-urlencoded 提交表单字段，值逐个 urlencode。 */
    private fun postForm(url: String, params: Map<String, String>, headers: Map<String, String>): String {
        val sb = StringBuilder()
        for ((key, value) in params) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(URLEncoder.encode(key, "UTF-8")).append('=').append(URLEncoder.encode(value, "UTF-8"))
        }
        val formHeaders = LinkedHashMap(headers)
        formHeaders["content-type"] = "application/x-www-form-urlencoded"
        return post(url, sb.toString(), formHeaders)
    }

    /** 按 key 排序后逐个 urlencode(key)=urlencode(value) 用 & 连接，供 nebula737 签名。 */
    private fun encodedSorted(params: Map<String, String>): String {
        val sb = StringBuilder()
        for (key in params.keys.sorted()) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(URLEncoder.encode(key, "UTF-8").replace("+", "%20")).append('=')
                .append(URLEncoder.encode(params[key], "UTF-8").replace("+", "%20"))
        }
        return sb.toString()
    }

    private fun md5(text: String): String = hex(MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8)))

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun randomHex(length: Int): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder()
        for (i in 0 until length) sb.append(hex[RANDOM.nextInt(hex.length)])
        return sb.toString()
    }

    private fun randomHexUpper(length: Int): String = randomHex(length).uppercase(Locale.ROOT)

    /** 在 JSON 里递归找第一个命中 key 的非空字符串，保留 data/result 等包装。 */
    private fun findString(json: String?, vararg keys: String): String {
        if (json.isNullOrEmpty()) return ""
        return try {
            findString(JSONObject(json), *keys)
        } catch (_: Exception) {
            ""
        }
    }

    private fun findString(node: Any?, vararg keys: String): String {
        when (node) {
            is JSONObject -> {
                for (key in keys) {
                    val scalar = node.opt(key)
                    val value = if (scalar is String || scalar is Number) scalar.toString() else ""
                    if (value.isNotEmpty()) return value
                }
                val names = node.names()
                if (names != null) {
                    for (i in 0 until names.length()) {
                        val found = findString(node.opt(names.optString(i)), *keys)
                        if (found.isNotEmpty()) return found
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findString(node.opt(i), *keys)
                    if (found.isNotEmpty()) return found
                }
            }
        }
        return ""
    }

    private fun post(url: String, body: String, headers: Map<String, String>): String = request("POST", url, body, headers)

    private fun request(method: String, url: String, body: String, headers: Map<String, String>): String {
        val state = REQUEST.get()!!
        state.requireCurrent()
        state.httpStatus = 0
        state.response = ""
        val connection = URL(url).openConnection() as HttpURLConnection
        var captureId: String? = null
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.doOutput = body.isNotEmpty()
            for ((key, value) in headers) {
                if (value.isNotEmpty()) connection.setRequestProperty(key, value)
            }
            if (state.sdk == "mmhygame") {
                for ((key, values) in state.cookies.get(URL(url).toURI(), emptyMap())) {
                    connection.setRequestProperty(key, values.joinToString("; "))
                }
            }
            captureId = RpcTrafficCapture.recordModuleHttpRequest(connection, body)
            state.requireCurrent()
            if (state.reporting) state.writeStarted = true
            if (body.isNotEmpty()) {
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            }
            state.httpStatus = connection.responseCode
            if (state.sdk == "mmhygame") state.cookies.put(URL(url).toURI(), connection.headerFields)
            val stream = if (state.httpStatus in 200 until 300) connection.inputStream else connection.errorStream
            val response = StringBuilder()
            if (stream != null) {
                stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        response.append(line)
                    }
                }
            }
            state.response = response.toString()
            RpcTrafficCapture.recordModuleHttpResponse(captureId, connection, state.httpStatus, state.response)
            captureId = null
            state.requireCurrent()
            if (state.httpStatus !in 200 until 300) throw IOException("厂商请求失败：HTTP=${state.httpStatus} url=$url")
            return state.response
        } catch (e: Exception) {
            RpcTrafficCapture.recordModuleHttpError(captureId, e)
            throw e
        } finally {
            connection.disconnect()
        }
    }
}

package io.github.aoguai.sesameag.util;

import android.os.Build;
import android.util.Base64;
import io.github.aoguai.sesameag.hook.ApplicationHook;
import io.github.aoguai.sesameag.hook.internal.AlipayMiniMarkHelper;
import io.github.aoguai.sesameag.hook.internal.AuthCodeHelper;
import io.github.aoguai.sesameag.hook.rpc.capture.RpcTrafficCapture;
import io.github.aoguai.sesameag.util.maps.UserMap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** 厂商单次登录及动作协议。批次、账号会话和业务回查由 GameTask 管理。 */
public final class MiniGameVendorReporter {
    private static final Random RANDOM = new Random();
    private static final ThreadLocal<RequestState> REQUEST = new ThreadLocal<>();

    private static final class Meta {
        final String name, version, channel, oid, gcid, appKey, nebulaAppId, companyId, channelId;
        Meta(GameTask game, String channel) {
            name = game.getTitle();
            version = game.getVersion();
            this.channel = channel;
            oid = param(game.getAppId(), "oid");
            gcid = param(game.getAppId(), "gcid");
            String nebulaKey = param(game.getAppId(), "nbAppKey");
            appKey = nebulaKey.isEmpty() ? param(game.getAppId(), "appKey") : nebulaKey;
            nebulaAppId = param(game.getAppId(), "nebulaAppId");
            String group = param(game.getAppId(), "bgid");
            companyId = group.isEmpty() ? param(game.getAppId(), "companyId") : group;
            channelId = param(game.getAppId(), "channelId");
        }
    }

    public static final class Result {
        public final boolean success;
        public final String message, raw, code;
        public final boolean stopCurrentRound, writeStarted, resultUnknown;
        Result(boolean success, String message, RequestState state) {
            this.success = success;
            this.message = message.isEmpty() ? "" : (state.reporting ? "动作上报：" : "厂商登录：") + message;
            writeStarted = state.writeStarted;
            resultUnknown = state.resultUnknown;
            raw = state.response;
            String resultCode = "";
            try {
                JSONObject json = state.sdk.equals("qmhd87") ? qmhdJson(raw) : new JSONObject(raw);
                if (json != null) {
                    for (String key : new String[]{"code", "errorCode", "resultCode", "errcode", "status"}) {
                        if (!json.isNull(key)) resultCode = json.optString(key);
                        if (!resultCode.isEmpty()) break;
                    }
                }
            } catch (Exception ignored) { }
            code = resultCode.isEmpty() && state.httpStatus != 0 ? String.valueOf(state.httpStatus) : resultCode;
            stopCurrentRound = state.stopped || state.httpStatus == 401 || state.httpStatus == 403;
        }
    }

    private static final class RequestState {
        final BooleanSupplier sessionCurrent;
        final String sdk;
        String response = "";
        int httpStatus;
        boolean stopped, reporting, writeStarted, resultUnknown;
        RequestState(BooleanSupplier sessionCurrent, String sdk) {
            this.sessionCurrent = sessionCurrent;
            this.sdk = sdk;
        }
        void requireCurrent() {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("游戏上报已取消");
            if (!sessionCurrent.getAsBoolean()) {
                stopped = true;
                throw new IllegalStateException("账号会话已变化或已离线，停止游戏上报");
            }
        }
    }

    public static Result reportOnce(GameTask game, String channel, String action,
                                    BooleanSupplier sessionCurrent) {
        RequestState state = new RequestState(sessionCurrent, game.getSdk());
        REQUEST.set(state);
        try {
            state.requireCurrent();
            Meta meta = new Meta(game, channel);
            String appId = game.getAppId();
            String authCode = game.getSdk().equals("yyxxgame") ? "" : AuthCodeHelper.INSTANCE.getAuthCode(appId);
            state.requireCurrent();
            if (!game.getSdk().equals("yyxxgame") && (authCode == null || authCode.isEmpty())) {
                state.stopped = true;
                return new Result(false, "厂商登录失败：授权码为空", state);
            }
            Map<String, String> headers = baseHeaders(meta, appId);
            boolean success;
            switch (game.getSdk()) {
                case "haligame": success = reportHaligame(meta, appId, action, authCode, headers); break;
                case "dianwanshidai": success = reportDianwanshidai(meta, appId, action, authCode, headers); break;
                case "yyxxgame": success = reportYyxx(meta, appId, action, headers); break;
                case "nebula737": success = reportNebula(meta, action, authCode, headers); break;
                case "cpchxapi": success = reportCpchx(meta, appId, action, authCode, headers); break;
                case "hnycgames": success = reportHnyc(meta, action, authCode, headers); break;
                case "dianli100": success = reportDianli100(meta, appId, action, authCode, headers); break;
                case "tomato": success = reportTomato(meta, appId, action, authCode, headers); break;
                case "raiyantway": success = reportRaiyantway(meta, appId, action, authCode, headers); break;
                case "flourish": success = reportFlourish(meta, appId, action, authCode, headers); break;
                case "devourad": success = reportDevourad(meta, appId, action, authCode, headers); break;
                case "microfun": success = reportMicrofun(meta, appId, action, authCode, headers); break;
                case "qufang": success = reportQufang(meta, appId, action, authCode, headers); break;
                case "qmhd87": success = reportQmhd87(meta, appId, action, authCode, headers); break;
                case "lumosfun": success = reportLumosfun(meta, appId, action, authCode, headers); break;
                default: return new Result(false, "未支持的厂商协议：" + game.getSdk(), state);
            }
            state.requireCurrent();
            return new Result(success, success ? "" : "厂商登录或上报失败", state);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            state.resultUnknown = state.writeStarted && (state.httpStatus == 0 ||
                    (state.httpStatus >= 200 && state.httpStatus < 300));
            return new Result(false, "请求异常：" + e.getMessage(), state);
        } finally {
            REQUEST.remove();
        }
    }

    private static String param(String appId, String key) {
        GameTask game = GameTask.fromAppId(appId);
        return game == null ? "" : game.getProtocolParameters().getOrDefault(key, "");
    }

    private static Map<String, String> baseHeaders(Meta meta, String appId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("alipayminimark", AlipayMiniMarkHelper.INSTANCE.getAlipayMiniMark(appId, meta.version));
        headers.put("x-release-type", "ONLINE");
        headers.put("referer", "https://" + appId + ".hybrid.alipay-eco.com/" + appId + "/" + meta.version + "/index.html");
        String version = alipayVersion();
        headers.put("user-agent", System.getProperty("http.agent", "") + " AliApp(AP/" + version + ") AlipayClient/" + version);
        return headers;
    }

    /** haligame：get_openid → user_action_submit。 */
    private static boolean reportHaligame(Meta meta, String appId, String action, String authCode,
                                          Map<String, String> headers) throws Exception {
        JSONObject auth = new JSONObject();
        auth.put("auth_code", authCode);
        Map<String, String> jsonHeaders = new LinkedHashMap<>(headers);
        jsonHeaders.put("content-type", "application/json; charset=utf-8");
        String openIdResp = post("https://zf.haligame.com/ajax/get_openid.php", auth.toString(), jsonHeaders);
        String openId = findString(openIdResp, "open_id", "openid", "openId");
        if (openId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#haligame 未取到 open_id，响应=" + openIdResp);
            return false;
        }
        Map<String, String> submit = new LinkedHashMap<>();
        submit.put("action_code", action);
        submit.put("action_finish_channel", meta.channel);
        submit.put("open_id", openId);
        String reportHost = param(appId, "reportHost");
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = postForm("https://" + reportHost + "/ajax/user_action_submit.php", submit, headers);
        // 成功格式 {"code":"10000","msg":"Success"}（code 是字符串）
        boolean ok = new JSONObject(resp).optString("code").equals("10000");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败"));
        return ok;
    }

    /** dianwanshidai：user/login → ZfbSop/behavior。 */
    private static boolean reportDianwanshidai(Meta meta, String appId, String action, String authCode,
                                               Map<String, String> headers) throws Exception {
        JSONObject login = new JSONObject();
        login.put("openid", "");
        login.put("code", authCode);
        JSONObject query = new JSONObject();
        query.put("channel", "");
        login.put("query", query);
        Map<String, String> loginHeaders = new LinkedHashMap<>(headers);
        loginHeaders.put("content-type", "application/json; charset=utf-8");
        loginHeaders.put("appid", appId);
        loginHeaders.put("app-id", appId);
        loginHeaders.put("bgid", meta.companyId);
        String loginResp = post("https://platformv2.dianwanshidai.com/api_v2/v1/user/login",
                login.toString(), loginHeaders);
        String openId = findString(loginResp, "openid", "open_id", "openId");
        if (openId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#dianwanshidai 未取到 openid，响应=" + loginResp);
            return false;
        }
        JSONObject behavior = new JSONObject();
        behavior.put("openid", openId);
        behavior.put("action_code", action);
        behavior.put("channel", meta.channel);
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = post("https://platformv2.dianwanshidai.com/api_v2/v1/ZfbSop/behavior",
                behavior.toString(), loginHeaders);
        // 成功格式 {"code":200,"msg":"上报成功"}
        boolean ok = new JSONObject(resp).optString("code").equals("200");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败"));
        return ok;
    }

    /** yyxxgame：无登录，直接带支付宝 UID + md5 签名上报。域名按表 param[reportHost] 取
     *  （源星战域实测走 yxdasapi.yxzygame.com，与 yyxxgame 同模板仅换域名），缺省回落 fumodasapi。 */
    private static boolean reportYyxx(Meta meta, String appId, String action,
                                      Map<String, String> headers) throws Exception {
        // JSONObject.put(key, null) 会移除该键，服务端随即报 field "user_id" is not set，
        // 故先取 hook 的 UID，取不到再回落到模块缓存的当前 UID。
        String userId = UserMap.INSTANCE.getCurrentUid();
        if (userId == null || userId.isEmpty()) {
            userId = UserMap.INSTANCE.getCurrentUid();
        }
        if (userId == null || userId.isEmpty()) {
            Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + "，跳过：未取到支付宝 UID");
            return false;
        }
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#user_id=" + userId
                + "（len=" + userId.length() + "），oid=" + meta.oid + "，gcid=" + meta.gcid
                + "，channel=" + meta.channel);
        long time = System.currentTimeMillis();
        String signSrc = "action_code=" + action
                + "&action_finish_channel=" + meta.channel
                + "&gcid=" + meta.gcid
                + "&oid=" + meta.oid
                + "&user_id=" + userId
                + "&time=" + time
                + "undefined";
        // 服务端只按表单字段解析：发 JSON 会 400 field "user_id" is not set
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("user_id", userId);
        fields.put("action_code", action);
        fields.put("action_finish_channel", meta.channel);
        fields.put("oid", meta.oid);
        fields.put("gcid", meta.gcid);
        fields.put("sign", md5(signSrc));
        fields.put("time", String.valueOf(time));
        // 同族换域名（yxzygame 与 yyxxgame 同模板），差异走表 param，不硬编码
        String host = param(appId, "reportHost");
        if (host.isEmpty()) {
            host = "fumodasapi.yyxxgame.com";
        }
        String reportUrl = "https://" + host + "/alipay/alipay_game_report";
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = postForm(reportUrl, fields, headers);
        // 成功格式 {"msg":"success","code":1}
        boolean ok = new JSONObject(resp).optString("code").equals("1");
        if (ok) {
            Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=成功");
        } else {
            Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=失败"
                    + "\n请求: POST " + reportUrl
                    + "\n参数: " + fields
                    + "\n响应: " + resp);
        }
        return ok;
    }

    /** nebula737（一步两步）：account/user/detail 登录 → behavior/report。 */
    private static boolean reportNebula(Meta meta, String action, String authCode,
                                        Map<String, String> headers) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", meta.nebulaAppId);
        params.put("channel_id", meta.channelId);
        params.put("code", authCode);
        params.put("company_id", meta.companyId);
        params.put("data_version", "1");
        params.put("device_id", randomHex(32));
        params.put("device_type", Build.MODEL);
        params.put("extend", "");
        params.put("game_version", "1.6.13");
        params.put("is_debug", "0");
        params.put("network_mode", "0");
        params.put("open_id", "");
        params.put("os", "1");
        params.put("os_version", Build.VERSION.RELEASE);
        params.put("passport_code", "alipayminigame");
        params.put("payment_code", "alipayminigame");
        params.put("sdk_version", "3.4.35");
        params.put("sign_nonce", randomHexUpper(8));
        params.put("sign_type", "md5");
        params.put("sign_version", "1.0");
        params.put("startup_id", randomHex(32));
        params.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        params.put("union_id", "");
        params.put("user_id", "0");
        JSONObject login = new JSONObject();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            login.put(entry.getKey(), entry.getValue());
        }
        login.put("sign", md5(encodedSorted(params) + "&" + meta.appKey));
        Map<String, String> jsonHeaders = new LinkedHashMap<>(headers);
        jsonHeaders.put("content-type", "application/json; charset=utf-8");
        String loginResp = post("https://gate-nebula.737.com/account/user/detail",
                login.toString(), jsonHeaders);
        String openId = findString(loginResp, "open_id", "openId");
        String userId = findString(loginResp, "user_id", "userId");
        if (openId.isEmpty() || userId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#nebula 登录未取到 open_id/user_id，响应=" + loginResp);
            return false;
        }
        Map<String, String> report = new LinkedHashMap<>();
        report.put("action_code", action);
        report.put("action_finish_channel", meta.channel);
        report.put("action_finish_date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date()));
        report.put("app_id", meta.nebulaAppId);
        report.put("channel_id", meta.channelId);
        report.put("open_id", openId);
        report.put("out_biz_no", randomHex(32));
        report.put("user_id", userId);
        JSONObject body = new JSONObject();
        for (Map.Entry<String, String> entry : report.entrySet()) {
            body.put(entry.getKey(), entry.getValue());
            if ("channel_id".equals(entry.getKey())) {
                body.put(entry.getKey(), Integer.parseInt(entry.getValue()));
            }
        }
        body.put("sign", md5(encodedSorted(report) + "&" + meta.appKey));
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = post("https://api-combine.feiyu.com/v1/alipayMiniGame/behavior/report",
                body.toString(), jsonHeaders);
        JSONObject response = new JSONObject(resp);
        boolean ok = response.has("status") ? response.optInt("status", -1) == 0
                : response.optString("code").equals("2000");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败"));
        return ok;
    }

    /** cpchxapi：auth/login-ali 换 JWT → analytics/ali-report（Bearer 鉴权）。 */
    private static boolean reportCpchx(Meta meta, String appId, String action, String authCode,
                                       Map<String, String> headers) throws Exception {
        JSONObject login = new JSONObject();
        login.put("appId", appId);
        login.put("code", authCode);
        login.put("anonymousCode", JSONObject.NULL);
        login.put("channel", 3);
        login.put("inviteId", "");
        Map<String, String> jsonHeaders = new LinkedHashMap<>(headers);
        jsonHeaders.put("content-type", "application/json; charset=utf-8");
        String loginResp = post("https://cpchxapi.dianwanshidai.com/app-api/game/auth/login-ali",
                login.toString(), jsonHeaders);
        String token = findString(loginResp, "token");
        if (token.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#cpchx 未取到 token，响应=" + loginResp);
            return false;
        }
        JSONObject report = new JSONObject();
        report.put("eventName", action);
        report.put("channel", meta.channel);
        Map<String, String> reportHeaders = new LinkedHashMap<>(jsonHeaders);
        reportHeaders.put("authorization", "Bearer " + token);
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = post("https://cpchxapi.dianwanshidai.com/app-api/game/analytics/ali-report",
                report.toString(), reportHeaders);
        // 成功格式 {"code":0,"msg":"","data":null}
        boolean ok = new JSONObject(resp).optString("code").equals("0");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败"));
        return ok;
    }

    /**
     * hnycgames（我的花园世界）：最简模板——Channel/login 换 yxtChannelUserId → reportaction。
     * 本协议：全程无 sign、无时间戳、无设备指纹、无 clientSendBI（clientSendBI 由
     * 游戏自身 JS 生成，不属于本类提报链路）。登录只换 authCode，上报只换 yxtChannelUserId。
     */
    private static boolean reportHnyc(Meta meta, String action, String authCode,
                                      Map<String, String> headers) throws Exception {
        // action 形如 wdhysj_game_dingdan_every_2，前缀即 yxtGame 目录名
        int index = action.indexOf("_game_");
        String game = index > 0 ? action.substring(0, index) : "wdhysj";
        String segments = "yxtGame/" + game + "/yxtChannel/myxyx/yxtSubChannel/myxyx";
        String hnycBase = "https://h5sdk.hnycgames.cn";
        Map<String, String> formHeaders = new LinkedHashMap<>(headers);
        formHeaders.put("content-type", "application/x-www-form-urlencoded");

        // 1. 渠道登录：body 只有 scene + authCode 两个字段
        String loginBody = "authCode=" + URLEncoder.encode(authCode, "UTF-8")
                + "&scene=" + URLEncoder.encode(meta.channel, "UTF-8");
        String loginResp = post(hnycBase + "/Channel/login/" + segments, loginBody, formHeaders);
        // 上报唯一要用的字段是 yxtChannelUserId（即支付宝 openId）
        String yxtChannelUserId = findString(loginResp, "yxtChannelUserId");
        if (yxtChannelUserId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#hnyc 未取到 yxtChannelUserId，响应=" + loginResp);
            return false;
        }

        // 2. 行为上报：body 只有 action_code + channel + yxtChannelUserId 三个字段
        String reportBody = "action_code=" + URLEncoder.encode(action, "UTF-8")
                + "&channel=" + URLEncoder.encode(meta.channel, "UTF-8")
                + "&yxtChannelUserId=" + URLEncoder.encode(yxtChannelUserId, "UTF-8");
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = post(hnycBase + "/channel/index/yxtMethod/reportaction/" + segments,
                reportBody, formHeaders);
        // 成功格式 {"errorCode":0,"errorMessage":"成功"}
        boolean ok = new JSONObject(resp).optString("errorCode").equals("0");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败")
                + (ok ? "" : "，响应=" + resp));
        return ok;
    }

    /** dianli100（农场对对碰）：farm/openid 登录 → farm/action/report，上报重新取 authCode。 */
    private static boolean reportDianli100(Meta meta, String appId, String action, String authCode,
                                           Map<String, String> headers) throws Exception {
        String base = "https://alifunc.dianli100.com/majiang/alipay/farm";
        Map<String, String> jsonHeaders = new LinkedHashMap<>(headers);
        jsonHeaders.put("content-type", "application/json; charset=utf-8");
        JSONObject login = new JSONObject();
        login.put("authCode", authCode);
        String loginResp = post(base + "/openid", login.toString(), jsonHeaders);
        String openId = findString(loginResp, "data");
        if (openId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#dianli100 未取到 openid，响应=" + loginResp);
            return false;
        }
        // 每个动作上报都带一个刚生成的新 authCode，与登录时不同
        String reportCode = AuthCodeHelper.INSTANCE.getAuthCode(appId);
        if (reportCode == null || reportCode.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#dianli100 上报取不到授权码");
            return false;
        }
        JSONObject report = new JSONObject();
        report.put("authCode", reportCode);
        report.put("channel", meta.channel);
        report.put("actionCode", action);
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = post(base + "/action/report", report.toString(), jsonHeaders);
        // 成功格式 {"code":200,"msg":"success","data":...}
        boolean ok = new JSONObject(resp).optString("code").equals("200");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败")
                + " openid=" + openId);
        return ok;
    }

    /**
     * tomato（时尚百货城）：initDevices → merge/v3/login/inland/zfbMiniGame 换 channelUserId
     * → init/platform/event/push。
     * 签名 md5(全部字段（除 sign）按 key 排序 k=v 用 & 连接（含空值，值不编码）+ secret)。
     */
    private static boolean reportTomato(Meta meta, String appId, String action, String authCode,
                                        Map<String, String> headers) throws Exception {
        String base = "https://ssbhc-minigame-sdk.tomatogames.com";
        String secret = param(appId, "secret");
        String gameId = param(appId, "gameId");
        String platformKey = param(appId, "platformKey");
        String sdkChannel = param(appId, "sdkChannel");
        String cid = param(appId, "cid");
        String uuid = deviceUuid();
        String gid = String.format(Locale.ROOT, "%010d-%d",
                (RANDOM.nextLong() & Long.MAX_VALUE) % 10000000000L, System.currentTimeMillis());
        // 设备/渠道基础字段（initDevices / login / push 共用）
        Map<String, String> base2 = new LinkedHashMap<>();
        base2.put("advertisingId", uuid);
        base2.put("androidId", uuid);
        base2.put("channel", sdkChannel);
        base2.put("cid", cid);
        base2.put("device_no", uuid);
        base2.put("gameId", gameId);
        base2.put("gid", gid);
        base2.put("idfa", uuid);
        base2.put("idfv", uuid);
        base2.put("imei", uuid);
        base2.put("ip", "");
        base2.put("mac", "");
        base2.put("model", Build.MODEL);
        base2.put("network_type", "4g");
        base2.put("oaid", uuid);
        base2.put("sdk_version", "1.0.0");
        base2.put("type", "0");
        base2.put("udid", alipayVersion());
        base2.put("version", "1.0.171");
        // 1) initDevices
        Map<String, String> initParams = new LinkedHashMap<>(base2);
        initParams.put("afContent", "");
        initParams.put("channelType", "1");
        initParams.put("extension", new JSONObject()
                .put("miniprogram", new JSONObject().put("zfb_uid", uuid)).toString());
        initParams.put("sign", tomatoSign(initParams, secret));
        String initResp = postForm(base + "/init/v2/sdk/initDevices", initParams, headers);
        if (!new JSONObject(initResp).optString("code").equals("1")) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#tomato initDevices 失败，响应=" + initResp);
            return false;
        }
        // 2) zfbMiniGame 登录（extension 里塞 authCode）
        Map<String, String> loginParams = new LinkedHashMap<>(base2);
        loginParams.put("channelType", "1");
        loginParams.put("extSdkData", new JSONObject().put("source", new JSONObject()
                .put("platform", "my").put("ext", new JSONObject())).toString());
        loginParams.put("extension", new JSONObject().put("loginMode", "99")
                .put("extension", new JSONObject()
                        .put("miniprogram", new JSONObject().put("zfb_uid", uuid)))
                .put("code", authCode).toString());
        loginParams.put("method", "");
        loginParams.put("sign", tomatoSign(loginParams, secret));
        String loginResp = postForm(base + "/merge/v3/login/inland/zfbMiniGame", loginParams, headers);
        String channelUserId = findString(loginResp, "channelUserId");
        if (channelUserId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#tomato 登录未取到 channelUserId，响应=" + loginResp);
            return false;
        }
        // 3) event/push（JSON 请求体；pushParams 是 JSON 字符串，openId=channelUserId）
        String pushParams = new JSONObject()
                .put("eventId", action)
                .put("eventFinishChannel", meta.channel)
                .put("isCustom", true)
                .put("eventFinishDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                        .format(new Date()))
                .put("openId", channelUserId)
                .toString();
        Map<String, String> pushSign = new LinkedHashMap<>(base2);
        pushSign.put("platformKey", platformKey);
        pushSign.put("platformType", "ali");
        pushSign.put("pushParams", pushParams);
        JSONObject push = new JSONObject();
        for (Map.Entry<String, String> entry : pushSign.entrySet()) {
            push.put(entry.getKey(), entry.getValue());
        }
        push.put("sign", tomatoSign(pushSign, secret));
        Map<String, String> jsonHeaders = new LinkedHashMap<>(headers);
        jsonHeaders.put("content-type", "application/json; charset=utf-8");
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = post(base + "/init/platform/event/push", push.toString(), jsonHeaders);
        // 成功格式 {"code":"1","msg":"成功","data":null}（code 是字符串）
        boolean ok = new JSONObject(resp).optString("code").equals("1");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败"));
        return ok;
    }

    /**
     * flourish（斗破苍穹）：QuickGame/login 换 openid/token → callMethod(incrementGameAction)。
     * 签名为变体 A：排除嵌套字段后按 key 排序，k=v 用 & 连接，末尾直拼 appKey 再 MD5。
     */
    private static boolean reportFlourish(Meta meta, String appId, String action, String authCode,
                                          Map<String, String> headers) throws Exception {
        String base = "https://sdk.flourish-fs.com/QuickGame";
        String appKey = param(appId, "appKey");
        if (appKey.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#flourish 缺 appKey（表 param），跳过");
            return false;
        }
        String userId = UserMap.INSTANCE.getCurrentUid();
        if (userId == null || userId.isEmpty()) {
            userId = UserMap.INSTANCE.getCurrentUid();
        }
        if (userId == null || userId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#flourish 未取到支付宝 UID，跳过");
            return false;
        }
        String launchOptions = new JSONObject().put("channel", meta.channel).toString();
        Map<String, String> loginParams = new LinkedHashMap<>();
        loginParams.put("c_id", "1");
        loginParams.put("game_id", "192");
        loginParams.put("game_version", "1.0.0");
        loginParams.put("sdk_version", "1.1.2");
        loginParams.put("agent_id", "1001");
        loginParams.put("site_id", "10001");
        loginParams.put("sdk_channel", "qg_alipay");
        loginParams.put("brand", "");
        loginParams.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        loginParams.put("os_type", "android");
        loginParams.put("is_quick_game", "1");
        loginParams.put("device", "qg_" + md5(userId));
        loginParams.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        loginParams.put("timezone", "Asia/Shanghai");
        loginParams.put("utctime", "UTC+8");
        loginParams.put("launch_options", launchOptions);
        loginParams.put("channel_params", new JSONObject().put("code", authCode).toString());
        loginParams.put("sign", flourishSign(loginParams, appKey));
        Map<String, String> jsonHeaders = new LinkedHashMap<>(headers);
        jsonHeaders.put("content-type", "application/json; charset=utf-8");
        String loginResp = post(base + "/login", flourishJson(loginParams).toString(), jsonHeaders);
        String openId = findString(loginResp, "openid");
        String accessToken = findString(loginResp, "access_token");
        String refreshToken = findString(loginResp, "refresh_token");
        String uid = findString(loginResp, "uid");
        String uname = findString(loginResp, "uname");
        if (!new JSONObject(loginResp).optString("code").equals("0") || openId.isEmpty() || accessToken.isEmpty() || uid.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#flourish 登录未取到 openid/token，响应=" + loginResp);
            return false;
        }

        JSONObject event = new JSONObject();
        event.put("launch_options", new JSONObject().put("channel", meta.channel));
        event.put("event", "alipay_game_action");
        event.put("action_code", action);

        Map<String, String> reportParams = new LinkedHashMap<>(loginParams);
        reportParams.remove("sign");
        reportParams.remove("launch_options");
        reportParams.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        reportParams.put("access_token", accessToken);
        reportParams.put("refresh_token", refreshToken);
        reportParams.put("openid", openId);
        reportParams.put("uid", uid);
        reportParams.put("uname", uname);
        reportParams.put("method", "incrementGameAction");
        reportParams.put("channel_params", event.toString());
        reportParams.put("sign", flourishSign(reportParams, appKey));
        Map<String, String> reportHeaders = baseHeaders(meta, appId);
        reportHeaders.put("content-type", "application/json; charset=utf-8");
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = post(base + "/callMethod", flourishJson(reportParams).toString(), reportHeaders);
        boolean ok = new JSONObject(resp).optString("code").equals("0");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败")
                + (ok ? "" : "，响应=" + resp));
        return ok;
    }

    /** devourad：alipay/gameLogin → gameReport，MD5(appid + appSecret + 参数直拼)。 */
    private static boolean reportDevourad(Meta meta, String appId, String action, String authCode,
                                          Map<String, String> headers) throws Exception {
        String secret = param(appId, "appKey");
        if (secret.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#devourad 缺 appKey（表 param），跳过");
            return false;
        }
        String base = "https://api.devourad.com";
        Map<String, String> jsonHeaders = new LinkedHashMap<>(headers);
        jsonHeaders.put("content-type", "application/json; charset=utf-8");
        JSONObject login = new JSONObject();
        login.put("appid", appId);
        login.put("code", authCode);
        login.put("sign", md5(appId + secret + authCode));
        String loginResp = post(base + "/alipay/gameLogin", login.toString(), jsonHeaders);
        String openId = findString(loginResp, "open_id", "openid");
        if (openId.isEmpty() || !new JSONObject(loginResp).optString("code").equals("1")) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#devourad 登录失败，响应=" + loginResp);
            return false;
        }
        String outBizNo = md5(UUID.randomUUID().toString());
        JSONObject report = new JSONObject();
        report.put("appid", appId);
        report.put("action_code", action);
        report.put("action_finish_channel", meta.channel);
        report.put("open_id", openId);
        report.put("out_biz_no", outBizNo);
        report.put("sign", md5(appId + secret + action + meta.channel + outBizNo + openId));
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = post(base + "/alipay/gameReport", report.toString(), jsonHeaders);
        boolean ok = new JSONObject(resp).optString("code").equals("1");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败")
                + (ok ? "" : "，响应=" + resp));
        return ok;
    }

    /** microfun（浪漫餐厅）：getDeviceInfo 换 openid → uploadActionNew，无签名。 */
    private static boolean reportMicrofun(Meta meta, String appId, String action, String authCode,
                                          Map<String, String> headers) throws Exception {
        String path = param(appId, "path");
        if (path.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#microfun 缺 path（表 param），跳过");
            return false;
        }
        String base = "https://xyx-txeo.cliapi.microfun.cn/zhifubao";
        String loginResp = request("GET", base + "/getDeviceInfo/" + path
                + "?code=" + URLEncoder.encode(authCode, "UTF-8"), "", headers);
        String openId = findString(loginResp, "openid", "open_id");
        if (openId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#microfun 登录未取到 openid，响应=" + loginResp);
            return false;
        }
        Map<String, String> report = new LinkedHashMap<>();
        report.put("action_finish_date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date()));
        report.put("open_id", openId);
        report.put("action_code", action);
        report.put("action_finish_channel", meta.channel);
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = postForm(base + "/uploadActionNew/" + path, report, headers);
        boolean ok = new JSONObject(resp).optString("code").equals("10000");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败")
                + (ok ? "" : "，响应=" + resp));
        return ok;
    }

    /**
     * qufang（农场纸牌塔等）：account/login 换 uid/token → increment/basic。
     * signature = "v3_" + SHA1("qyqf-cocos" + "3" + appid + versionApp + uid + token + t + message)。
     */
    private static boolean reportQufang(Meta meta, String appId, String action, String authCode,
                                        Map<String, String> headers) throws Exception {
        String loginHost = param(appId, "loginHost");
        if (loginHost.isEmpty()) {
            loginHost = "login-01.qufang.top";
        }
        String reportHost = param(appId, "reportHost");
        if (reportHost.isEmpty()) {
            reportHost = "game-factory-jail-001.qufang.top";
        }
        String versionApp = param(appId, "versionApp");
        if (versionApp.isEmpty()) {
            versionApp = "4.4.5";
        }
        Map<String, String> loginHeaders = new LinkedHashMap<>(headers);
        loginHeaders.put("content-type", "application/json; charset=utf-8");
        loginHeaders.put("qy-appid", appId);
        loginHeaders.put("qy-rid", UUID.randomUUID().toString());
        JSONObject systemInfo = new JSONObject();
        systemInfo.put("screenWidth", 1080);
        systemInfo.put("screenHeight", 2400);
        systemInfo.put("windowWidth", 411);
        systemInfo.put("windowHeight", 914);
        systemInfo.put("pixelRatio", 2.625);
        systemInfo.put("model", Build.MODEL);
        systemInfo.put("system", Build.VERSION.RELEASE);
        systemInfo.put("version", alipayVersion());
        systemInfo.put("language", "zh-Hans");
        systemInfo.put("isIphoneXSeries", false);
        JSONObject launchOptions = new JSONObject();
        launchOptions.put("path", "");
        launchOptions.put("query", new JSONObject().put("channel", meta.channel));
        launchOptions.put("referrerInfo", new JSONObject());
        JSONObject hostName = new JSONObject();
        hostName.put("login", loginHost);
        hostName.put("game_factory", reportHost);
        hostName.put("event", "event-jail-001.qufang.top");
        hostName.put("more_game", "moregame.qufang.top");
        hostName.put("static_resources_public", "static-resources-public.qufang.top");
        JSONObject message = new JSONObject();
        message.put("version", 3);
        message.put("versionApp", versionApp);
        message.put("type", "alipay");
        message.put("code", authCode);
        message.put("idx", 0);
        message.put("launchOptions", launchOptions);
        message.put("systemInfo", systemInfo);
        message.put("stored_part", new JSONObject().put("part", new JSONObject().put("syncVersion", 0)));
        message.put("hostName", hostName);
        message.put("res", new JSONObject().put("code", authCode));
        JSONObject login = new JSONObject();
        login.put("version", 3);
        login.put("versionApp", versionApp);
        login.put("platformType", "alipay");
        login.put("appid", appId);
        login.put("t", System.currentTimeMillis());
        login.put("message", message);
        String loginResp = post("https://" + loginHost + "/account/login", login.toString(), loginHeaders);
        String uid = findString(loginResp, "uid");
        String token = findString(loginResp, "token");
        if (!new JSONObject(loginResp).optString("ok").equals("1") || uid.isEmpty() || token.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#qufang 登录未取到 uid/token，响应=" + loginResp);
            return false;
        }
        long reportTs = System.currentTimeMillis();
        JSONObject actionMessage = new JSONObject();
        actionMessage.put("action_code", action);
        actionMessage.put("action_finish_channel", meta.channel);
        actionMessage.put("action_finish_time", reportTs);
        String signature = "v3_" + sha1("qyqf-cocos" + "3" + appId + versionApp
                + uid + token + reportTs + qufangMessage(actionMessage));
        JSONObject report = new JSONObject();
        report.put("version", 3);
        report.put("versionApp", versionApp);
        report.put("platformType", "alipay");
        report.put("appid", appId);
        report.put("uid", uid);
        report.put("token", token);
        report.put("t", reportTs);
        report.put("message", actionMessage);
        report.put("signature", signature);
        Map<String, String> reportHeaders = new LinkedHashMap<>(headers);
        reportHeaders.put("content-type", "application/json; charset=utf-8");
        reportHeaders.put("qy-appid", appId);
        reportHeaders.put("qy-rid", UUID.randomUUID().toString());
        reportHeaders.put("qy-uid", uid);
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = post("https://" + reportHost + "/platform_service/increment/basic",
                report.toString(), reportHeaders);
        boolean ok = new JSONObject(resp).optString("ok").equals("1");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败")
                + (ok ? "" : "，响应=" + resp));
        return ok;
    }

    /** qufang 的 message 签名串：key 字典序、过滤空值、值 URL 编码后用 & 连接。 */
    private static String qufangMessage(JSONObject message) throws Exception {
        JSONArray names = message.names();
        List<String> keys = new ArrayList<>();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                keys.add(names.optString(i));
            }
        }
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            String value = message.optString(key);
            if (value.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(key).append('=')
                    .append(URLEncoder.encode(value, "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    /**
     * qmhd87（四季物语）：openid → login → report，body 只有 eData。
     * eData = Base64(DES-ECB("cTawb7/c", PKCS#7(Base64(gzip(JSON)))))。
     */
    private static boolean reportQmhd87(Meta meta, String appId, String action, String authCode,
                                        Map<String, String> headers) throws Exception {
        String host = param(appId, "reportHost");
        if (host.isEmpty()) {
            host = "game2-sjwy.qmhd87.com";
        }
        String channelCode = param(appId, "channelCode");
        if (channelCode.isEmpty()) {
            channelCode = "PA0306";
        }
        String packageName = param(appId, "packageName");
        if (packageName.isEmpty()) {
            packageName = "projectA";
        }
        String sdkVersion = param(appId, "sdkVersion");
        if (sdkVersion.isEmpty()) {
            sdkVersion = "6.9.7";
        }
        String base = "https://" + host;
        long installId = System.currentTimeMillis();
        String did = "rc" + installId;
        String noise = String.valueOf(100000000 + RANDOM.nextInt(900000000));
        Map<String, String> openidHeaders = qmhdHeaders(headers, did, installId, noise,
                channelCode, packageName, sdkVersion);
        JSONObject openidBody = new JSONObject();
        openidBody.put("eData", qmhdEncrypt(new JSONObject().put("code", authCode).toString()));
        String openidResp = post(base + "/api/v1/ap/openid/", openidBody.toString(), openidHeaders);
        JSONObject openidJson = qmhdJson(openidResp);
        String openId = openidJson == null ? "" : findString(openidJson, "openid", "open_id");
        String token = openidJson == null ? ""
                : findString(openidJson, "token", "authorization", "accessToken", "access_token");
        if (openId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#qmhd87 未取到 openid，响应=" + openidResp);
            return false;
        }
        Map<String, String> loginHeaders = qmhdHeaders(headers, openId, installId, noise,
                channelCode, packageName, sdkVersion);
        if (!token.isEmpty()) {
            loginHeaders.put("authorization", "Bearer " + token);
        }
        JSONObject loginPlain = new JSONObject();
        loginPlain.put("type", 6);
        loginPlain.put("account", openId);
        loginPlain.put("extData", new JSONObject());
        JSONObject loginBody = new JSONObject();
        loginBody.put("eData", qmhdEncrypt(loginPlain.toString()));
        String loginResp = post(base + "/api/v1/login/", loginBody.toString(), loginHeaders);
        JSONObject loginJson = qmhdJson(loginResp);
        String loginToken = loginJson == null ? ""
                : findString(loginJson, "token", "authorization", "accessToken", "access_token");
        Map<String, String> reportHeaders = qmhdHeaders(headers, openId, installId, noise,
                channelCode, packageName, sdkVersion);
        String bearer = loginToken.isEmpty() ? token : loginToken;
        if (!bearer.isEmpty()) {
            reportHeaders.put("authorization", "Bearer " + bearer);
        }
        JSONObject reportPlain = new JSONObject();
        reportPlain.put("actionCode", action);
        reportPlain.put("actionFinishAt", System.currentTimeMillis() / 1000);
        reportPlain.put("actionFinishChannel", meta.channel);
        JSONObject reportBody = new JSONObject();
        reportBody.put("eData", qmhdEncrypt(reportPlain.toString()));
        Objects.requireNonNull(REQUEST.get()).reporting = true;
        String resp = post(base + "/api/v1/ap/report/", reportBody.toString(), reportHeaders);
        JSONObject json = qmhdJson(resp);
        boolean ok = json != null && json.optInt("code", -1) == 0;
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败")
                + (ok ? "" : "，响应=" + resp));
        return ok;
    }

    private static Map<String, String> qmhdHeaders(Map<String, String> base, String did, long installId,
                                                   String noise, String channelCode, String packageName,
                                                   String sdkVersion) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        Map<String, String> headers = new LinkedHashMap<>(base);
        headers.put("content-type", "application/json; charset=utf-8");
        headers.put("channel", channelCode);
        headers.put("did", did);
        headers.put("install-id", String.valueOf(installId));
        headers.put("noise", noise);
        headers.put("package-name", packageName);
        headers.put("run-id", String.valueOf(installId));
        headers.put("timestamp", String.valueOf(timestamp));
        headers.put("version", sdkVersion);
        headers.put("sign", md5(channelCode + did + installId + noise + packageName
                + installId + timestamp + sdkVersion + "6KscZatA"));
        return headers;
    }

    /** qmhd87 响应解析：可能是明文 JSON，也可能是 {@code eData} 密文。 */
    private static JSONObject qmhdJson(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        try {
            JSONObject jo = new JSONObject(response);
            String encrypted = jo.optString("eData");
            return encrypted.isEmpty() ? jo : new JSONObject(qmhdDecrypt(encrypted));
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject(qmhdDecrypt(response));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final byte[] QMHD_DES_KEY = "cTawb7/c".getBytes(StandardCharsets.UTF_8);

    private static String qmhdEncrypt(String plain) throws Exception {
        String gzipBase64 = Base64.encodeToString(gzip(plain.getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(QMHD_DES_KEY, "DES"));
        return Base64.encodeToString(cipher.doFinal(gzipBase64.getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
    }

    private static String qmhdDecrypt(String data) throws Exception {
        Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(QMHD_DES_KEY, "DES"));
        byte[] inner = cipher.doFinal(Base64.decode(data, Base64.DEFAULT));
        String gzipBase64 = new String(inner, StandardCharsets.UTF_8).trim();
        return gunzip(Base64.decode(gzipBase64, Base64.DEFAULT));
    }

    /** lumosfun/gusspro（弓箭手对决）：MiniGameServer 登录 → mini-alipay event/submit。 */
    private static boolean reportLumosfun(Meta meta, String appId, String action, String authCode,
                                          Map<String, String> headers) throws Exception {
        String loginHost = param(appId, "loginHost");
        if (loginHost.isEmpty()) {
            loginHost = "azory.lumosfun.com:8525";
        }
        String reportHost = param(appId, "reportHost");
        if (reportHost.isEmpty()) {
            reportHost = "gjdz-zfb-def.gusspro.com:15087";
        }
        String loginUrl = "https://" + loginHost + "/MiniGameServer/minigame/login.do?userType=31&appId="
                + URLEncoder.encode(appId, "UTF-8") + "&code=" + URLEncoder.encode(authCode, "UTF-8");
        String loginResp = request("GET", loginUrl, "", headers);
        String openId = findString(loginResp, "openId", "openid", "open_id");
        if (!new JSONObject(loginResp).optString("code").equals("0") || openId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#lumosfun 登录未取到 openId，响应=" + loginResp);
            return false;
        }
        JSONObject report = new JSONObject();
        report.put("openId", openId);
        report.put("eventId", action);
        report.put("EventFinishChannel", meta.channel);
        report.put("AlipayUserId", "");
        report.put("EventFinishDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date()));
        report.put("OutBizNo", "SB_" + (System.currentTimeMillis() / 1000));
        Map<String, String> jsonHeaders = new LinkedHashMap<>(headers);
        jsonHeaders.put("content-type", "application/json; charset=utf-8");
        String resp = post("https://" + reportHost + "/mini-alipay/event/submit",
                report.toString(), jsonHeaders);
        boolean ok = new JSONObject(resp).optString("code").equals("0");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败")
                + (ok ? "" : "，响应=" + resp));
        return ok;
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }

    private static String gunzip(byte[] data) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String sha1(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return hex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** flourish 请求体：数字字段保持 JSON number，其余保持字符串。 */
    private static JSONObject flourishJson(Map<String, String> params) throws Exception {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("c_id".equals(key) || "game_id".equals(key) || "agent_id".equals(key)
                    || "site_id".equals(key) || "is_quick_game".equals(key)) {
                json.put(key, Integer.parseInt(value));
            } else if ("timestamp".equals(key) || "uid".equals(key)) {
                json.put(key, Long.parseLong(value));
            } else {
                json.put(key, value);
            }
        }
        return json;
    }

    /** flourish 变体 A：嵌套 JSON 字符串整体不参与签名，空值字段仍保留。 */
    private static String flourishSign(Map<String, String> params, String appKey) throws Exception {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if ("sign".equals(key) || "channel_params".equals(key) || "launch_options".equals(key)
                    || "role_name".equals(key) || "server_name".equals(key) || "goods_name".equals(key)
                    || "extension".equals(key) || "event_params".equals(key)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(key).append('=').append(params.get(key));
        }
        sb.append(appKey);
        return md5(sb.toString());
    }

    /**
     * raiyantway（占城大师，MiniGameServer 框架）：login.do 换 openId → reportGameAction。
     * 上报体为 ENCODE_DATA 加密：明文 {appId,openId,actionCode,actionFinishChannel,outBizNo,
     * actionFinishDate}（算法来自 GameTask.js 的 Ya 函数）。
     * 域名/端口与 userType 走 {@link GameTask} param（loginHost/userType），不硬编码。
     */
    private static boolean reportRaiyantway(Meta meta, String appId, String action, String authCode,
                                            Map<String, String> headers) throws Exception {
        String host = param(appId, "loginHost");
        if (host.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#raiyantway 缺 loginHost（表 param），跳过");
            return false;
        }
        String userType = param(appId, "userType");
        if (userType.isEmpty()) {
            userType = "31";
        }
        String base = "https://" + host + "/MiniGameServer";
        // 实际为 GET + JSON body（HttpURLConnection 不支持 GET 带 body），按同 content-type 的 POST 发送
        JSONObject login = new JSONObject();
        login.put("userType", userType);
        login.put("appId", appId);
        login.put("code", authCode);
        Map<String, String> jsonHeaders = new LinkedHashMap<>(headers);
        jsonHeaders.put("content-type", "application/json; charset=utf-8");
        String loginResp = post(base + "/minigame/login.do", login.toString(), jsonHeaders);
        String openId = findString(loginResp, "openId");
        if (!new JSONObject(loginResp).optString("code").equals("0") || openId.isEmpty()) {
            Log.debug("游戏任务⏭️[" + meta.name + "]#raiyantway 登录未取到 openId，响应=" + loginResp);
            return false;
        }
        JSONObject plain = new JSONObject();
        plain.put("appId", appId);
        plain.put("openId", openId);
        plain.put("actionCode", action);
        plain.put("actionFinishChannel", meta.channel);
        plain.put("outBizNo", UUID.randomUUID().toString());
        plain.put("actionFinishDate", System.currentTimeMillis());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ENCODE_DATA", raiyantwayEncode(plain.toString()));
        String resp = postForm(base + "/alipay/reportGameAction", fields, headers);
        // 成功格式 {"code":"0","msg":"success","data":null}（code 是字符串）
        boolean ok = new JSONObject(resp).optString("code").equals("0");
        Log.debug("游戏任务🎮厂商上报[" + meta.name + "]#" + action + " 结果=" + (ok ? "成功" : "失败")
                + (ok ? "" : "\n明文: " + plain + "\n响应: " + resp));
        return ok;
    }

    /**
     * raiyantway ENCODE_DATA 加密（GameTask.js Ya 函数）：
     * UTF-8 字节 → 补零至 4 的倍数 → 逐字节 XOR 随机 4 字节 key → 密文拼接 key → base64。
     */
    private static String raiyantwayEncode(String plainJson) {
        byte[] data = plainJson.getBytes(StandardCharsets.UTF_8);
        int pad = (4 - data.length % 4) % 4;
        byte[] key = new byte[4];
        RANDOM.nextBytes(key);
        byte[] out = new byte[data.length + pad + 4];
        for (int i = 0; i < data.length + pad; i++) {
            // 补零段 0 ^ key = key，与显式补零后统一 XOR 等价
            byte b = i < data.length ? data[i] : 0;
            out[i] = (byte) (b ^ key[i % 4]);
        }
        System.arraycopy(key, 0, out, data.length + pad, 4);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    /** tomato 设备标识：androidId/idfa/oaid 等字段同值，进程内生成一次复用。 */
    private static String deviceUuid() {
        if (DEVICE_UUID == null) {
            DEVICE_UUID = java.util.UUID.randomUUID().toString();
        }
        return DEVICE_UUID;
    }

    private static volatile String DEVICE_UUID;

    private static String alipayVersion() {
        try {
            return ApplicationHook.getAlipayVersion().getVersionString();
        } catch (Exception t) {
            return "";
        }
    }

    /** tomato 签名：全部字段（除 sign）按 key 排序 k=v 用 & 连接（含空值），末尾追加密钥。 */
    private static String tomatoSign(Map<String, String> params, String secret) throws Exception {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if ("sign".equals(key)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(key).append('=').append(params.get(key));
        }
        sb.append(secret);
        return md5(sb.toString());
    }

    /** 以 application/x-www-form-urlencoded 提交表单字段（值逐个 urlencode）。 */
    private static String postForm(String url, Map<String, String> params,
                                   Map<String, String> headers) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), "UTF-8"));
        }
        Map<String, String> formHeaders = new LinkedHashMap<>(headers);
        formHeaders.put("content-type", "application/x-www-form-urlencoded");
        return post(url, sb.toString(), formHeaders);
    }

    /** 按 key 排序后逐个 urlencode(key)=urlencode(value) 用 & 连接（nebula737 的签名串）。 */
    private static String encodedSorted(Map<String, String> params) throws Exception {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(key, "UTF-8").replace("+", "%20"))
                    .append('=')
                    .append(URLEncoder.encode(params.get(key), "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String md5(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return hex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String randomHex(int length) {
        String hex = "0123456789abcdef";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(hex.charAt(RANDOM.nextInt(hex.length())));
        }
        return sb.toString();
    }

    private static String randomHexUpper(int length) {
        return randomHex(length).toUpperCase(Locale.ROOT);
    }

    /** 在 JSON 里递归找第一个命中 key 的非空字符串（兼容 data/result 包装）。 */
    private static String findString(String json, String... keys) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            return findString(new JSONObject(json), keys);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String findString(Object node, String... keys) {
        if (node instanceof JSONObject) {
            JSONObject jo = (JSONObject) node;
            for (String key : keys) {
                Object scalar = jo.opt(key);
                String value = scalar instanceof String || scalar instanceof Number ? scalar.toString() : "";
                if (!value.isEmpty()) {
                    return value;
                }
            }
            JSONArray names = jo.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String found = findString(jo.opt(names.optString(i)), keys);
                    if (!found.isEmpty()) {
                        return found;
                    }
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findString(array.opt(i), keys);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return "";
    }

    private static String post(String url, String body, Map<String, String> headers) throws Exception {
        return request("POST", url, body, headers);
    }

    private static String request(String method, String url, String body,
                                  Map<String, String> headers) throws Exception {
        RequestState state = Objects.requireNonNull(REQUEST.get());
        state.requireCurrent();
        state.httpStatus = 0;
        state.response = "";
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        String captureId = null;
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(!body.isEmpty());
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty())
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            captureId = RpcTrafficCapture.INSTANCE.recordModuleHttpRequest(connection, body);
            state.requireCurrent();
            if (state.reporting) state.writeStarted = true;
            if (!body.isEmpty()) {
                try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8)) {
                    writer.write(body);
                }
            }
            state.httpStatus = connection.getResponseCode();
            InputStream stream = state.httpStatus >= 200 && state.httpStatus < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder response = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }
            }
            state.response = response.toString();
            RpcTrafficCapture.INSTANCE.recordModuleHttpResponse(captureId, connection, state.httpStatus, state.response);
            captureId = null;
            state.requireCurrent();
            if (state.httpStatus < 200 || state.httpStatus >= 300)
                throw new IOException("厂商请求失败：HTTP=" + state.httpStatus + " url=" + url);
            return state.response;
        } catch (Exception e) {
            RpcTrafficCapture.INSTANCE.recordModuleHttpError(captureId, e);
            throw e;
        } finally {
            connection.disconnect();
        }
    }
}

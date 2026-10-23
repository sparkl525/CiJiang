package com.jianyi.dict;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 云端翻译：两家可切换的实现。
 *
 * 为什么要两家：
 *  · 百度通用翻译 —— 注册简单，只要 APP ID + 密钥，免费额度约 5 万字符/月
 *  · 腾讯云机器翻译（TMT）—— 免费额度更高（每月 500 万字符），国内直连稳定
 * 用户可以在设置里切换，默认仍是百度（老用户配置直接沿用，不用重填）。
 *
 * 两家的失败语义统一：
 *   返回 null      = 没配凭据（调用方静默走离线引擎）
 *   返回 "!ERR:xx" = 失败原因（服务报错、连不上、超时…）→ 调用方把原因显示给用户，同时回退离线
 * 这样调用方不用关心是哪家云，错误处理逻辑只有一套。
 */
public class CloudTrans {

    public static final String PREF_PROVIDER = "cloud_provider";
    public static final String PROVIDER_BAIDU = "baidu";
    public static final String PROVIDER_TENCENT = "tencent";

    private CloudTrans() {
    }

    // ================= 统一入口 =================

    /**
     * 按当前设置走对应云端翻译。
     *
     * @param text   待译文本
     * @param zh2en  true = 中译英；false = 英译中
     * @return 译文；null = 该厂商还没配凭据；"!ERR:原因" = 失败（含网络失败）
     */
    public static String translate(String text, boolean zh2en) {
        String provider = provider();
        if (PROVIDER_TENCENT.equals(provider)) {
            String id = secret("tencent_secret_id");
            String key = secret("tencent_secret_key");
            if (id.isEmpty() || key.isEmpty()) return null;
            return tencentTranslate(text, zh2en, id, key);
        }
        // 默认百度（老用户配置在这里）
        String appId = secret("baidu_appid");
        String appSecret = secret("baidu_secret");
        if (appId.isEmpty() || appSecret.isEmpty()) return null;
        return baiduTranslate(text, zh2en, appId, appSecret);
    }

    /** 当前是否已经配好了可用的云端凭据 */
    public static boolean isConfigured() {
        if (PROVIDER_TENCENT.equals(provider())) {
            return !secret("tencent_secret_id").isEmpty() && !secret("tencent_secret_key").isEmpty();
        }
        return !secret("baidu_appid").isEmpty() && !secret("baidu_secret").isEmpty();
    }

    /** 当前选中的厂商名字（显示用） */
    public static String providerLabel() {
        return PROVIDER_TENCENT.equals(provider()) ? "腾讯云" : "百度";
    }

    // ================= 百度通用翻译 =================

    /** 百度通用翻译 API；成功返回译文，失败返回 null 或 "!ERR:原因" */
    public static String baiduTranslate(String input, boolean zh2en, String appId, String secret) {
        if (input.length() > 3000) {
            return "!ERR:云端不支持超过 3000 字的段落，请分段翻译";
        }
        HttpURLConnection conn = null;
        try {
            String salt = String.valueOf(System.currentTimeMillis());
            String sign = md5(appId + input + salt + secret);
            String q = URLEncoder.encode(input, "UTF-8");
            URL url = new URL("https://fanyi-api.baidu.com/api/trans/vip/translate"
                    + "?q=" + q
                    + "&from=" + (zh2en ? "zh" : "en")
                    + "&to=" + (zh2en ? "en" : "zh")
                    + "&appid=" + appId + "&salt=" + salt + "&sign=" + sign);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            // 非 2xx 时正文在 errorStream 里；只看 getInputStream() 会把错误详情整个丢掉
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JSONObject o = new JSONObject(resp);
            if (o.has("error_code")) {
                return "!ERR:" + baiduErrMsg(o.optString("error_code"), o.optString("error_msg"));
            }
            JSONArray arr = o.getJSONArray("trans_result");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) out.append('\n');
                out.append(arr.getJSONObject(i).optString("dst"));
            }
            return out.toString();
        } catch (Throwable t) {
            // 原来一律 return null，等于把 DNS 失败、证书错误、HTTP 4xx、JSON 解析失败、
            // 甚至 OOM 全说成"网络不通"，用户报"云端没反应"时无从查起
            String reason = why(t);
            logFailure("CloudTrans.baiduTranslate", t, reason);
            return "!ERR:" + reason;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 异常 → 一句用户能看懂的原因（配合 "!ERR:" 通道显示到界面上）
     *
     * 这里刻意不直接用 t.getMessage()：百度侧的异常消息里常带完整请求地址
     *（appid 和 sign 都在里面），原样显示到界面 = 把凭据摊在用户眼前。
     * 所以尾巴上的消息一律先过 safeMsg() 过一遍筛。
     */
    private static String why(Throwable t) {
        if (t instanceof java.net.UnknownHostException) return "域名解析失败，请检查网络";
        if (t instanceof java.net.SocketTimeoutException) return "请求超时，请稍后再试";
        if (t instanceof javax.net.ssl.SSLException) return "安全连接建立失败";
        if (t instanceof java.io.IOException) return "网络中断" + safeMsg(t);
        if (t instanceof org.json.JSONException) return "云端返回的内容无法解析";
        return t.getClass().getSimpleName() + safeMsg(t);
    }

    /**
     * 异常消息尾巴：只在「短、且不含敏感标记」时才带出来。
     * 含 http / appid / sign= / secret / key= / token 的一律丢掉。
     */
    private static String safeMsg(Throwable t) {
        String m = (t == null) ? null : t.getMessage();
        if (m == null) return "";
        m = m.trim();
        if (m.isEmpty() || m.length() > 80) return "";
        if (isSensitiveMsg(m)) return "";
        return "：" + m;
    }

    /**
     * 消息里有没有不该出现的东西（请求地址、appid、sign…）。
     * 等号和冒号两种分隔都查：URL 查询串用 appid=123，HTTP 头是 Authorization: Bearer …
     * 2026-10-23 补了 authorization / credential / password —— 腾讯侧 TC3 的
     * Authorization 头值形如 "TC3-HMAC-SHA256 Credential=…/…, SignedHeaders=…, Signature=…"，
     * 光靠 sign= 只能拦住尾巴，Signature 和 Credential 都会漏出去。
     */
    private static boolean isSensitiveMsg(String m) {
        if (m == null) return false;
        String low = m.toLowerCase(Locale.US);
        return low.contains("http") || low.contains("appid")
                || low.contains("sign=") || low.contains("sign:")
                || low.contains("secret") || low.contains("signature")
                || low.contains("key=") || low.contains("key:")
                || low.contains("token") || low.contains("authorization")
                || low.contains("credential") || low.contains("password")
                || low.contains("pwd");
    }

    /**
     * 失败落日志：能安全记录时带完整栈（便于定位），否则只记干净的原因。
     * 原始异常消息里可能带着完整的百度请求地址，原样进 logcat 等于把凭据写进日志文件。
     */
    private static void logFailure(String where, Throwable t, String reason) {
        if (t != null && !isSensitiveMsg(t.getMessage())) {
            JLog.e(where, t);
        } else {
            JLog.w(where, reason);
        }
    }

    /** 百度错误码 → 人话 */
    public static String baiduErrMsg(String code, String raw) {
        if (code == null || code.isEmpty()) return "未知错误";
        switch (code) {
            case "52001": return "请求超时，请重试";
            case "52002": return "百度服务系统错误，请稍后再试";
            case "52003": return "APP ID 无效或未开通「通用翻译」服务";
            case "54000": return "请求参数为空";
            case "54001": return "签名错误：请检查 APP ID 和密钥是否填对（不要有多余空格）";
            case "54003": return "请求太频繁：免费版限速 1 次/秒，请稍后再试";
            case "54004": return "账户余额不足";
            case "58002": return "服务已被封禁";
            case "58003": return "本月免费额度已用完";
            case "90107": return "账号未实名认证：请到百度智能云完成实名认证";
            default: return "错误码 " + code + (raw == null || raw.isEmpty() ? "" : "（" + raw + "）");
        }
    }

    // ================= 腾讯云机器翻译 TMT =================

    private static final String TENCENT_HOST = "tmt.tencentcloudapi.com";
    private static final String TENCENT_SERVICE = "tmt";
    private static final String TENCENT_REGION = "ap-guangzhou";
    private static final String TENCENT_VERSION = "2018-03-21";
    private static final String TENCENT_ACTION = "TextTranslate";

    /**
     * 腾讯云机器翻译。签名用 TC3-HMAC-SHA256（腾讯云标准签名 v3）：
     *   1. 规范化请求 → 哈希
     *   2. 拼待签字符串 → 哈希
     *   3. 用 SecretKey 逐级派生 SigningKey 再签名
     * 详见腾讯云 API 3.0 签名方法 v3。
     */
    public static String tencentTranslate(String input, boolean zh2en,
                                         String secretId, String secretKey) {
        if (input.length() > 2000) {
            // 腾讯云文档：SourceText 单次请求最多 2000 字符
            return "!ERR:云端不支持超过 2000 字的段落，请分段翻译";
        }
        HttpURLConnection conn = null;
        try {
            String source = zh2en ? "zh" : "en";
            String target = zh2en ? "en" : "zh";
            JSONObject payload = new JSONObject();
            payload.put("SourceText", input);
            payload.put("Source", source);
            payload.put("Target", target);
            payload.put("ProjectId", 0);
            String body = payload.toString();

            long timestamp = System.currentTimeMillis() / 1000;
            // 腾讯云要求 UTC 的 yyyy-MM-dd，与请求时间戳同一天
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String date = sdf.format(new Date(timestamp * 1000));

            // content-type 不带 charset：Android 的 HttpURLConnection 偶尔会自动加/改 charset，
            // 一旦实际发出的头和签名用的不一致，服务端就报签名错误。
            // 腾讯云对 JSON 默认按 UTF-8 解析，不写 charset 更稳。
            String canonicalHeaders = "content-type:application/json\n"
                    + "host:" + TENCENT_HOST + "\n"
                    + "x-tc-action:" + TENCENT_ACTION.toLowerCase(Locale.US) + "\n";
            String signedHeaders = "content-type;host;x-tc-action";

            String canonicalRequest = "POST\n"
                    + "/\n"
                    + "\n"
                    + canonicalHeaders
                    + "\n"
                    + signedHeaders
                    + "\n"
                    + sha256Hex(body);

            // CredentialScope = Date/Service/tc3_request —— 注意这里【没有 Region】！
            // 腾讯云签名 v3 的 scope 只有 3 段；Region 只通过 X-TC-Region 请求头传递。
            // 写成 4 段（多带一个 Region）服务端会报 "Credential scope size not valid"。
            String credentialScope = date + "/" + TENCENT_SERVICE + "/tc3_request";
            String stringToSign = "TC3-HMAC-SHA256\n"
                    + timestamp + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest);

            // 派生密钥同样只有 3 步：Date → Service → tc3_request
            byte[] kDate = hmac("TC3" + secretKey, date);
            byte[] kService = hmac(kDate, TENCENT_SERVICE);
            byte[] kSigning = hmac(kService, "tc3_request");
            String signature = hex(hmac(kSigning, stringToSign));

            String authorization = "TC3-HMAC-SHA256 "
                    + "Credential=" + secretId + "/" + credentialScope + ", "
                    + "SignedHeaders=" + signedHeaders + ", "
                    + "Signature=" + signature;

            URL url = new URL("https://" + TENCENT_HOST);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Host", TENCENT_HOST);
            conn.setRequestProperty("X-TC-Action", TENCENT_ACTION);
            conn.setRequestProperty("X-TC-Version", TENCENT_VERSION);
            conn.setRequestProperty("X-TC-Region", TENCENT_REGION);
            conn.setRequestProperty("X-TC-Timestamp", String.valueOf(timestamp));
            conn.setRequestProperty("Authorization", authorization);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JSONObject o = new JSONObject(resp);
            JSONObject err = o.optJSONObject("Response") == null
                    ? null : o.getJSONObject("Response").optJSONObject("Error");
            if (err != null) {
                return "!ERR:" + tencentErrMsg(err.optString("Code"), err.optString("Message"));
            }
            JSONObject rsp = o.getJSONObject("Response");
            String targetText = rsp.optString("TargetText");
            if (targetText == null || targetText.isEmpty()) {
                return "!ERR:云端返回了空译文";
            }
            return targetText;
        } catch (Throwable t) {
            String reason = why(t);
            logFailure("CloudTrans.tencentTranslate", t, reason);
            return "!ERR:" + reason;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 腾讯云错误码 → 人话 */
    public static String tencentErrMsg(String code, String msg) {
        if (code == null || code.isEmpty()) return "未知错误";
        switch (code) {
            case "AuthFailure.SignatureFailure": return "签名失败：请检查 SecretId 和 SecretKey 是否填对";
            case "AuthFailure.SecretIdNotFound": return "SecretId 不存在或已禁用";
            case "AuthFailure.TokenFailure": return "临时密钥已过期，请重新填写";
            case "AuthFailure.SecretIdNotFoundOrDisabled": return "SecretId 不存在或已被禁用";
            case "FailedOperation.NoCertificate": return "没有开通机器翻译服务";
            case "FailedOperation.TextTooLong": return "文本太长，请分段翻译";
            case "FailedOperation.SourceTextIllegal": return "源文本包含不支持的内容";
            case "FailedOperation.UnSupportLanguage": return "暂不支持该语种";
            case "FailedOperation.ServiceIsolate": return "账户异常，服务已隔离";
            case "FailedOperation.AccountHasNotOpenTmtService": return "还没开通「机器翻译」服务，去腾讯云控制台开通";
            case "RequestLimitExceeded": return "请求太频繁，请稍后再试";
            case "InternalError": return "腾讯云内部错误，请稍后再试";
            default:
                return code + (msg == null || msg.isEmpty() ? "" : "（" + msg + "）");
        }
    }

    // ================= 底层工具 =================

    private static String readAll(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static byte[] hmac(String key, String data) throws Exception {
        return hmac(key.getBytes("UTF-8"), data);
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes("UTF-8"));
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(s.getBytes("UTF-8")));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ================= 偏好存储（静态注入，避免层层传 SharedPreferences）=====

    // init 在主线程、pref 在后台线程读，字段需 volatile 才能安全发布
    private static volatile android.content.SharedPreferences sPrefs;

    public static void init(android.content.SharedPreferences p) {
        sPrefs = p;
    }

    private static String pref(String key) {
        if (sPrefs == null) return "";
        String v = sPrefs.getString(key, "");
        return v == null ? "" : v.trim();
    }

    /**
     * 凭据从加密存储里取。
     * 以前这里是 prefs.getString("baidu_secret") —— 明文躺在磁盘上；
     * 现在走 SecretStore，磁盘上只剩 Keystore 密钥解不开的密文。
     * 解不开（换了设备 / 密钥丢了）就当没配：用户重填一次即可。
     */
    private static String secret(String name) {
        if (sPrefs == null) return "";
        String v = SecretStore.get(sPrefs, name);
        return v == null ? "" : v.trim();
    }

    private static String provider() {
        String p = pref(PREF_PROVIDER);
        return p.isEmpty() ? PROVIDER_BAIDU : p;
    }
}

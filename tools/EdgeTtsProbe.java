import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 简译 · 发音引擎协议探针
 * 纯 JDK 实现（不含任何 Android API），手写 WebSocket 客户端 + 微软 Edge 朗读协议。
 * 桌面跑通的这份逻辑，可直接搬进 Android 工程复用。
 */
public class EdgeTtsProbe {

    static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    static final String CHROMIUM_FULL = "143.0.3650.75";
    static final String CHROMIUM_MAJOR = "143";
    static final String HOST = "speech.platform.bing.com";
    static final String PATH = "/consumer/speech/synthesize/readaloud/edge/v1";
    static final String WIN_EPOCH = "11644473600";

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        new File(outDir).mkdirs();

        String[][] jobs = {
                {"en-GB-RyanNeural", "英式 · 男声", "schedule"},
                {"en-GB-SoniaNeural", "英式 · 女声", "schedule"},
                {"en-US-GuyNeural", "美式 · 男声", "schedule"},
                {"en-US-AriaNeural", "美式 · 女声", "schedule"},
        };

        int ok = 0;
        for (String[] job : jobs) {
            String voice = job[0], label = job[1], text = job[2];
            File out = new File(outDir, voice + ".mp3");
            System.out.println("\n=== " + label + "  [" + voice + "] ===");
            long t0 = System.currentTimeMillis();
            try {
                int n = synth(voice, text, out);
                long ms = System.currentTimeMillis() - t0;
                byte[] head = new byte[3];
                try (InputStream in = new java.io.FileInputStream(out)) {
                    int r = in.read(head);
                    if (r < 3) head = new byte[0];
                }
                String magic = bytesToHex(head);
                boolean mp3 = magic.startsWith("fff3") || magic.startsWith("fff2") || magic.startsWith("494433");
                System.out.println("  结果: " + (mp3 ? "OK" : "可疑") + "  " + n + " bytes"
                        + "  " + ms + " ms  头=" + magic);
                if (mp3) ok++;
            } catch (Exception e) {
                System.out.println("  失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        System.out.println("\n===== 汇总: " + ok + "/4 成功 =====");
        System.out.flush();
    }

    /** 合成一段音频，返回音频字节数 */
    static int synth(String voice, String text, File outFile) throws Exception {
        String connId = uuidHex();
        String url = PATH
                + "?TrustedClientToken=" + TRUSTED_TOKEN
                + "&ConnectionId=" + connId
                + "&Sec-MS-GEC=" + secMsGec()
                + "&Sec-MS-GEC-Version=1-" + CHROMIUM_FULL;

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sock = (SSLSocket) factory.createSocket();
        sock.connect(new InetSocketAddress(HOST, 443), 10000);
        sock.setSoTimeout(30000);
        sock.startHandshake();

        InputStream rawIn = new BufferedInputStream(sock.getInputStream(), 16384);
        OutputStream rawOut = sock.getOutputStream();

        // ---- WebSocket 握手 ----
        String key = wsKey();
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(url).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(HOST).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        req.append("Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n");
        req.append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/" + CHROMIUM_MAJOR + ".0.0.0 Safari/537.36"
                + " Edg/" + CHROMIUM_MAJOR + ".0.0.0\r\n");
        req.append("Accept-Language: en-US,en;q=0.9\r\n");
        req.append("Pragma: no-cache\r\n");
        req.append("Cache-Control: no-cache\r\n");
        req.append("Cookie: muid=").append(muid()).append(";\r\n");
        req.append("\r\n");
        rawOut.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        rawOut.flush();

        String statusLine = readLine(rawIn);
        if (statusLine == null || !statusLine.contains("101")) {
            throw new IllegalStateException("握手失败: " + statusLine);
        }
        // 吃掉剩余响应头
        String line;
        while ((line = readLine(rawIn)) != null && !line.isEmpty()) { /* skip */ }

        // ---- 1. speech.config ----
        String cfg = "X-Timestamp:" + dateStr() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\""
                + "},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
        sendText(rawOut, cfg);

        // ---- 2. SSML ----
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + voice + "'>"
                + "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>"
                + escapeXml(text)
                + "</prosody></voice></speak>";
        String ssmlMsg = "X-RequestId:" + uuidHex() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + dateStr() + "Z\r\n"
                + "Path:ssml\r\n\r\n"
                + ssml;
        sendText(rawOut, ssmlMsg);

        // ---- 收帧 ----
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        int metaCount = 0;
        String sawPaths = "";
        boolean done = false;
        int guard = 0;
        while (!done && guard++ < 2000) {
            Frame f;
            try {
                f = readFrame(rawIn);
            } catch (java.net.SocketTimeoutException te) {
                break;
            }
            if (f == null) break;

            if (f.opcode == 0x9) {           // ping -> pong
                sendFrame(rawOut, 0xA, f.payload);
                continue;
            }
            if (f.opcode == 0x8) break;      // close
            if (f.opcode == 0x1) {           // text
                String msg = new String(f.payload, StandardCharsets.UTF_8);
                int p = msg.indexOf("\r\n\r\n");
                String head = p >= 0 ? msg.substring(0, p) : msg;
                String body = p >= 0 ? msg.substring(p + 4) : "";
                if (head.contains("Path:audio.metadata")) {
                    metaCount++;
                } else if (head.contains("Path:turn.end")) {
                    done = true;
                } else {
                    for (String h : head.split("\r\n")) {
                        if (h.startsWith("Path:")) sawPaths += h.substring(5).trim() + " ";
                    }
                    if (body.length() > 0 && body.length() < 300) {
                        // 可能是错误信息
                        if (body.contains("Error") || body.contains("error")) {
                            System.out.println("  服务端消息: " + body.trim());
                        }
                    }
                }
            } else if (f.opcode == 0x2) {    // binary = audio
                int hlen = ((f.payload[0] & 0xFF) << 8) | (f.payload[1] & 0xFF);
                int start = 2 + hlen;
                if (start + 1 < f.payload.length
                        && f.payload[start] == 0x0D && f.payload[start + 1] == 0x0A) {
                    start += 2;
                }
                if (start < f.payload.length) {
                    audio.write(f.payload, start, f.payload.length - start);
                }
            }
        }
        sock.close();

        byte[] data = audio.toByteArray();
        if (data.length == 0) {
            throw new IllegalStateException("未收到音频 (meta=" + metaCount + " paths=" + sawPaths + ")");
        }
        try (FileOutputStream fo = new FileOutputStream(outFile)) {
            fo.write(data);
        }
        System.out.println("  元数据帧 " + metaCount + " 个" + (sawPaths.isEmpty() ? "" : "  其他路径: " + sawPaths));
        return data.length;
    }

    // ================= WebSocket 基础 =================

    static class Frame {
        int opcode;
        byte[] payload;
    }

    static void sendText(OutputStream out, String s) throws Exception {
        sendFrame(out, 0x1, s.getBytes(StandardCharsets.UTF_8));
    }

    static synchronized void sendFrame(OutputStream out, int opcode, byte[] payload) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x80 | opcode);
        int len = payload.length;
        if (len <= 125) {
            buf.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            buf.write(0x80 | 126);
            buf.write((len >>> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) buf.write((int) (((long) len >>> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        buf.write(mask);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        buf.write(masked);
        out.write(buf.toByteArray());
        out.flush();
    }

    static Frame readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        Frame f = new Frame();
        f.opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) readN(in, 1)[0] & 0xFF) << 8 | (readN(in, 1)[0] & 0xFF);
        } else if (len == 127) {
            byte[] lb = readN(in, 8);
            len = 0;
            for (byte x : lb) len = (len << 8) | (x & 0xFF);
        }
        byte[] mask = masked ? readN(in, 4) : null;
        f.payload = readN(in, (int) len);
        if (masked && mask != null) {
            for (int i = 0; i < f.payload.length; i++) f.payload[i] ^= mask[i & 3];
        }
        return f;
    }

    static byte[] readN(InputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new java.io.EOFException("连接中断");
            off += r;
        }
        return b;
    }

    static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
        }
        if (c < 0 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    // ================= 令牌与工具 =================

    /** Sec-MS-GEC：Windows 文件时间(100ns) 向下取整到 5 分钟 + 固定 token，SHA256 大写十六进制 */
    static String secMsGec() throws Exception {
        long unix = System.currentTimeMillis() / 1000L;
        long ticks = unix + Long.parseLong(WIN_EPOCH);
        ticks -= ticks % 300;
        ticks *= 10000000L;
        String s = ticks + TRUSTED_TOKEN;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return bytesToHex(md.digest(s.getBytes(StandardCharsets.US_ASCII))).toUpperCase(Locale.US);
    }

    static String dateStr() {
        SimpleDateFormat f = new SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    static String uuidHex() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    static String muid() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return bytesToHex(b).toUpperCase(Locale.US);
    }

    static String wsKey() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}

package com.jianyi.dict;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 Edge 发音客户端（纯 Java 实现，零第三方依赖）。
 *
 * 走微软 Edge「大声朗读」的 WebSocket 接口，拿到 24kHz/48kbps 的 MP3 音频。
 * 之所以自己手写 WebSocket 而不是引 OkHttp：Android SDK 里没有 java.net.http，
 * 而引库会额外增加体积和依赖；这里只需要"发两帧文本、收二进制音频"这点能力。
 *
 * 音色：英式男/女、美式男/女，均由神经语音合成，接近真人。
 * 注意：这是微软的消费级免费接口，不是正式商用 API，将来可能变动或不可用。
 */
public final class EdgeTts {

    /** 四种音色，顺序与界面选项一一对应 */
    public static final String[] VOICE_IDS = {
            "en-GB-RyanNeural",     // 英式 · 男
            "en-GB-SoniaNeural",    // 英式 · 女
            "en-US-GuyNeural",      // 美式 · 男
            "en-US-AriaNeural",     // 美式 · 女
    };
    public static final String[] VOICE_LABELS = {
            "英式 · 男声", "英式 · 女声", "美式 · 男声", "美式 · 女声",
    };

    private static final String CHROMIUM_MAJOR = "143";
    private static final String HOST = "speech.platform.bing.com";
    private static final String PATH = "/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String WS_ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 单次请求的总时长上限（含连接），超过就放弃，避免界面无限等待 */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    /**
     * 首帧超时：桌面实测服务正常时首帧中位 0.27 秒、最慢 0.50 秒，
     * 8 秒已经是十几倍余量。真等满 8 秒的只有两种情况——服务端拥塞或者连接已被掐断，
     * 两种都值得早点判死走重连，而不是干等 10 秒。
     */
    private static final int FIRST_FRAME_TIMEOUT_MS = 8000;
    /**
     * 帧间超时：一旦有帧进来就说明这条线是活的，实测流中最大间隔 1.01 秒，
     * 3 秒没下一帧就是流断了。总时长仍由 TOTAL_BUDGET_MS 兜着，
     * 所以"慢但一直在给数据"的请求能继续跑，不会因为一次间隔被砍。
     */
    private static final int STREAM_GAP_TIMEOUT_MS = 3000;
    private static final int TOTAL_BUDGET_MS = 30000;
    /** 单帧负载上限：远端报一个 2^31 的长度就分配一个数组，会直接把进程打爆 */
    private static final int MAX_FRAME_BYTES = 1 << 20;

    // ---- 连接复用：一条连接可以连续问多个词，省掉每词一次 TCP+TLS+WS ----
    // 实测桌面：连接成本约 0.9~1.7 秒，服务端合成快的时候只要 0.4~0.8 秒，
    // 后台预缓存 500 个词就是白扔十分钟。
    private static final Object poolLock = new Object();
    private static final java.util.concurrent.atomic.AtomicBoolean poolBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static Conn pooled;
    /**
     * 服务端会掐闲置连接（桌面实测：同一条连接空闲 30 秒后直接无响应）。
     * 撞上这种半死的连接要等满 10 秒读超时才知道，所以宁可提前作废重连：
     * 重连只要 1~2 秒，超时黑洞要 10 秒。
     */
    private static final long POOL_MAX_AGE_MS = 3 * 60 * 1000L;
    private static final long POOL_MAX_IDLE_MS = 20 * 1000L;
    private static final int POOL_MAX_USES = 60;

    private EdgeTts() {
    }

    /**
     * 合成一段英文语音。
     *
     * @param voice 音色 ID，取值见 {@link #VOICE_IDS}
     * @param text  要朗读的文本（单词或短句）
     * @return MP3 音频字节
     * @throws IOException 网络异常或服务端未返回音频
     */
    public static byte[] synth(String voice, String text) throws IOException {
        String clean = cleanText(text);
        if (clean.isEmpty()) throw new IOException("没有可朗读的内容");
        // voice 会被拼进 SSML 的单引号属性里，只允许内置音色 ID
        boolean known = false;
        for (String id : VOICE_IDS) if (id.equals(voice)) { known = true; break; }
        if (!known) throw new IOException("未知音色：" + voice);
        final long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;

        // 复用连接只有一条，被批处理占着时不排队 —— 用户点发音走一次性连接，
        // 免得等后台预缓存跑完（否则第一次点要等好几秒）。
        if (poolBusy.compareAndSet(false, true)) {
            try {
                return pooledSynth(voice, clean, deadline);
            } catch (IOException first) {
                closePooled();          // 复用车道出错说明这条连接已经不可信，直接丢弃
                JLog.w("EdgeTts.synth 复用车道失败，改用一次性连接", first);
                if (System.currentTimeMillis() >= deadline) throw first;
                try {
                    return oneShot(voice, clean, deadline);
                } catch (IOException second) {
                    second.addSuppressed(first);
                    throw second;
                }
            } catch (Throwable t) {
                // IOException 之外冒出来的（帧解析里的 RuntimeException、OOM/Error）
                // 也说明这条连接是读到一半被打断的，留着它下次接着问就会拿到上一条
                // 响应的残帧。关门，原样抛出去，不做二次兜底——那类问题重问一次也好的可能性极低。
                closePooled();
                JLog.w("EdgeTts.synth 复用车道异常，连接已作废", t);
                throw t;
            } finally {
                poolBusy.set(false);
            }
        }
        return oneShot(voice, clean, deadline);
    }

    // ================= 连接车道 =================

    /** 一次性的连接：问完一个词就关，不参与复用 */
    private static byte[] oneShot(String voice, String clean, long deadline) throws IOException {
        Conn c = new Conn();
        try {
            return c.request(voice, clean, deadline);
        } finally {
            closeQuietly(c);
        }
    }

    /** 复用同一条连接问下一个词，省掉 TCP+TLS+WS 三次握手 */
    private static byte[] pooledSynth(String voice, String clean, long deadline) throws IOException {
        Conn c;
        synchronized (poolLock) {
            c = pooled;
            if (c != null && !c.usable()) {
                closeQuietly(c);
                pooled = null;
                c = null;               // 别忘了把局部引用也清空，否则下面还会拿这条死连接去问
            }
            if (c == null) pooled = c = new Conn();
        }
        byte[] data = c.request(voice, clean, deadline);
        synchronized (poolLock) {
            c.uses++;
            c.lastUsedAt = System.currentTimeMillis();
            if (pooled == c && !c.usable()) {
                closeQuietly(c);
                pooled = null;
            }
        }
        return data;
    }

    /**
     * 不用发音了就把复用那条连接交出去（Pron.release 里调）。
     * pooled 是静态字段：一条 SSLSocket 挂着两套 16KB 缓冲和一个 TLS 会话，
     * 过期判断只在下一次请求时做，没人再发音的话它就一直占着内存和服务器那边的连接额度。
     */
    public static void release() {
        closePooled();
    }

    private static void closePooled() {
        synchronized (poolLock) {
            if (pooled != null) {
                closeQuietly(pooled);
                pooled = null;
            }
        }
    }

    private static void closeQuietly(Conn c) {
        try {
            c.sock.close();
        } catch (Throwable ignored) {
            JLog.w("EdgeTts.closeQuietly", ignored);
        }
    }

    private static String url() {
        return PATH
                + "?TrustedClientToken=" + Gec.TRUSTED_TOKEN
                + "&ConnectionId=" + uuidHex()
                + "&Sec-MS-GEC=" + Gec.current()
                + "&Sec-MS-GEC-Version=" + Gec.VERSION;
    }

    /**
     * 一条握好手的连接。speech.config 每连接发一次就够，音色写在各词自己的 SSML 里，
     * 所以复用连接时换音色照样生效。
     */
    private static final class Conn {
        SSLSocket sock;
        InputStream in;
        OutputStream out;
        final long bornAt = System.currentTimeMillis();
        long lastUsedAt = bornAt;
        int uses;

        Conn() throws IOException {
            SSLSocket s = null;
            boolean ok = false;
            try {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                s = (SSLSocket) factory.createSocket();
                s.connect(new InetSocketAddress(HOST, 443), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(FIRST_FRAME_TIMEOUT_MS);
                s.startHandshake();
                sock = s;
                in = new BufferedInputStream(s.getInputStream(), 16384);
                out = s.getOutputStream();
                handshake(in, out, url());
                // 合成参数：只要音频，不要逐词时间戳。
                // 必须写在 try 里面：握手成功但这一帧发不出去（刚连上就被掐、断网）时，
                // 下面的 finally 才有关门的机会；写在外面就是这条套接字悬在堆上等人回收。
                sendText(out, "X-Timestamp:" + dateStr() + "\r\n"
                        + "Content-Type:application/json; charset=utf-8\r\n"
                        + "Path:speech.config\r\n\r\n"
                        + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                        + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\""
                        + "},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n");
                ok = true;
            } finally {
                // 握手半路失败的话别把套接字留在外面等人回收
                if (!ok && s != null) {
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                        JLog.w("EdgeTts.Conn", ignored);
                    }
                }
            }
        }

        /** 服务端会掐闲置连接，用久了/放凉了主动作废，别等下一次请求撞读超时黑洞 */
        boolean usable() {
            long now = System.currentTimeMillis();
            return !sock.isClosed()
                    && uses < POOL_MAX_USES
                    && now - bornAt < POOL_MAX_AGE_MS
                    && now - lastUsedAt < POOL_MAX_IDLE_MS;
        }

        byte[] request(String voice, String clean, long deadline) throws IOException {
            // 待朗读内容（SSML）—— 音色就在这一帧里，跟着请求换
            sendText(out, "X-RequestId:" + uuidHex() + "\r\n"
                    + "Content-Type:application/ssml+xml\r\n"
                    + "X-Timestamp:" + dateStr() + "Z\r\n"
                    + "Path:ssml\r\n\r\n"
                    + "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                    + "<voice name='" + voice + "'>"
                    + "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>"
                    + escapeXml(clean)
                    + "</prosody></voice></speak>");

            // 收音频
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            String serverError = null;
            boolean sawFirstFrame = false;
            for (int guard = 0; guard < 3000; guard++) {
                // setSoTimeout 只限制单次 read，3000 帧拼起来仍可能耗上好几分钟
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("合成超时（" + (TOTAL_BUDGET_MS / 1000) + " 秒）");
                }
                // 每次读之前重新定时限：还没见过帧＝最多等 8 秒，已经在收＝只容忍 3 秒停顿
                sock.setSoTimeout(sawFirstFrame ? STREAM_GAP_TIMEOUT_MS : FIRST_FRAME_TIMEOUT_MS);
                Msg m = readMessage(in, out);
                sawFirstFrame = true;
                if (m == null) break;
                if (m.opcode == 1) {
                    String msg = new String(m.payload, "UTF-8");
                    int sep = msg.indexOf("\r\n\r\n");
                    String head = sep >= 0 ? msg.substring(0, sep) : msg;
                    String body = sep >= 0 ? msg.substring(sep + 4) : "";
                    if (head.contains("Path:turn.end")) break;
                    if (!head.contains("Path:audio.metadata") && body.length() > 0 && body.length() < 500) {
                        String low = body.toLowerCase(Locale.US);
                        if (low.contains("error") || low.contains("fail")) serverError = body.trim();
                    }
                } else if (m.opcode == 2) {
                    if (m.payload.length < 2) continue;      // 残缺帧丢掉，别让下面两个下标越界
                    int hlen = ((m.payload[0] & 0xFF) << 8) | (m.payload[1] & 0xFF);
                    int start = 2 + hlen;
                    if (start + 1 < m.payload.length
                            && m.payload[start] == 0x0D && m.payload[start + 1] == 0x0A) {
                        start += 2;
                    }
                    if (start < m.payload.length) {
                        audio.write(m.payload, start, m.payload.length - start);
                    }
                }
            }
            byte[] data = audio.toByteArray();
            if (data.length == 0) {
                throw new IOException(serverError != null ? "服务端返回：" + serverError : "未收到音频数据");
            }
            return data;
        }
    }

    // ================= WebSocket 握手 =================

    private static void handshake(InputStream in, OutputStream out, String url) throws IOException {
        String key = base64(randomBytes(16));
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(url).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(HOST).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        req.append("Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n");
        req.append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/").append(CHROMIUM_MAJOR)
                .append(".0.0.0 Safari/537.36 Edg/").append(CHROMIUM_MAJOR).append(".0.0.0\r\n");
        req.append("Accept-Language: en-US,en;q=0.9\r\n");
        req.append("Pragma: no-cache\r\n");
        req.append("Cache-Control: no-cache\r\n");
        req.append("Cookie: muid=").append(muid()).append(";\r\n\r\n");
        out.write(req.toString().getBytes(LATIN1));
        out.flush();

        String status = readLine(in);
        int code = 0;
        if (status != null) {
            String[] parts = status.split(" ");
            if (parts.length >= 2) {
                try {
                    code = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    code = 0;
                }
            }
        }
        // 只看响应串里有没有 "101" 不够：一份内容里带 101 的 200 错误页也会被判成握手成功，
        // 之后那页 HTML 会被当成音频存进缓存
        if (code != 101) {
            throw new IOException("连接被拒绝" + (status == null ? "" : "：" + status));
        }
        String accept = null;
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int c = line.indexOf(':');
            if (c > 0 && "Sec-WebSocket-Accept".equalsIgnoreCase(line.substring(0, c).trim())) {
                accept = line.substring(c + 1).trim();
            }
        }
        if (accept == null || !accept.equals(expectedAccept(key))) {
            throw new IOException("握手校验失败：Sec-WebSocket-Accept 不匹配");
        }
    }

    /** RFC 6455 的握手应答：base64(SHA1(clientKey + GUID)) */
    private static String expectedAccept(String key) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return base64(sha1.digest((key + WS_ACCEPT_GUID).getBytes(LATIN1)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("设备不支持 SHA-1", e);
        }
    }

    // ================= 帧收发 =================

    private static class Frame {
        int opcode;
        boolean fin;
        byte[] payload;
    }

    private static class Msg {
        int opcode;
        byte[] payload;
    }

    private static void sendText(OutputStream out, String s) throws IOException {
        sendFrame(out, 0x1, s.getBytes("UTF-8"));
    }

    /**
     * 这里不加类级别的锁：每条连接同一时刻只有一个写者（复用车道由 poolBusy 独占，
     * 一次性连接归调用线程私有）。加了反而会让用户点发音排在后台预缓存后面。
     */
    private static void sendFrame(OutputStream out, int opcode, byte[] payload)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(payload.length + 14);
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
            for (int i = 7; i >= 0; i--) {
                buf.write((int) (((long) len >>> (8 * i)) & 0xFF));
            }
        }
        byte[] mask = randomBytes(4);
        buf.write(mask, 0, 4);
        for (int i = 0; i < len; i++) {
            buf.write(payload[i] ^ mask[i & 3]);
        }
        // 锁每条连接自己的输出流：原来方法级 synchronized 会把并发连接的写全部串行化
        synchronized (out) {
            out.write(buf.toByteArray());
            out.flush();
        }
    }

    /** 读一条完整消息，内部处理 ping/pong 与分片 */
    private static Msg readMessage(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream acc = null;
        int msgOpcode = -1;
        while (true) {
            Frame f = readFrame(in);
            if (f == null) return null;
            if (f.opcode == 0x9) {              // ping → pong
                sendFrame(out, 0xA, f.payload);
                continue;
            }
            if (f.opcode == 0x8) {              // close
                throw new IOException("连接已关闭");
            }
            if (f.opcode == 0x0) {              // 续帧
                if (acc != null) acc.write(f.payload);
            } else if (f.fin) {
                Msg m = new Msg();
                m.opcode = f.opcode;
                m.payload = f.payload;
                return m;
            } else {
                msgOpcode = f.opcode;
                acc = new ByteArrayOutputStream();
                acc.write(f.payload);
            }
            if (f.fin && acc != null) {
                Msg m = new Msg();
                m.opcode = msgOpcode;
                m.payload = acc.toByteArray();
                return m;
            }
        }
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        Frame f = new Frame();
        f.fin = (b0 & 0x80) != 0;
        f.opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            byte[] lb = readN(in, 2);
            len = ((lb[0] & 0xFF) << 8) | (lb[1] & 0xFF);
        } else if (len == 127) {
            byte[] lb = readN(in, 8);
            len = 0;
            for (byte x : lb) len = (len << 8) | (x & 0xFF);
        }
        // 8 字节长度域可以报出远超内存的数值（还会溢出成负数），必须先拦下来
        if (len < 0 || len > MAX_FRAME_BYTES) {
            throw new IOException("帧长度不可信：" + len);
        }
        byte[] mask = masked ? readN(in, 4) : null;
        f.payload = readN(in, (int) len);
        if (mask != null) {
            for (int i = 0; i < f.payload.length; i++) {
                f.payload[i] ^= mask[i & 3];
            }
        }
        return f;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException("连接中断");
            off += r;
        }
        return b;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return new String(line.toByteArray(), LATIN1);
            if (c != '\r') line.write(c);
        }
        if (line.size() == 0) return null;
        return new String(line.toByteArray(), LATIN1);
    }

    // ================= 令牌与工具 =================

    private static String dateStr() {
        SimpleDateFormat f = new SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String uuidHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String muid() {
        return bytesToHex(randomBytes(16)).toUpperCase(Locale.US);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String escapeXml(String s) {
        // SSML 里属性用单引号包裹，引号不转义就能把标签结构顶掉
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** 服务端不接受垂直制表符等控制字符 */
    private static String cleanText(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0 && c <= 8) || (c >= 11 && c <= 12) || (c >= 14 && c <= 31)) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String bytesToHex(byte[] b) {
        char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = HEX[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }

    /** 手写 Base64，避免依赖 java.util.Base64（Android 8.0 以下没有） */
    private static String base64(byte[] data) {
        final char[] T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(T[(n >>> 18) & 63]).append(T[(n >>> 12) & 63])
                    .append(T[(n >>> 6) & 63]).append(T[n & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(T[(n >>> 18) & 63]).append(T[(n >>> 12) & 63]).append("==");
        } else if (rest == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(T[(n >>> 18) & 63]).append(T[(n >>> 12) & 63])
                    .append(T[(n >>> 6) & 63]).append('=');
        }
        return sb.toString();
    }
}

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

    private static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String CHROMIUM_FULL = "143.0.3650.75";
    private static final String CHROMIUM_MAJOR = "143";
    private static final String HOST = "speech.platform.bing.com";
    private static final String PATH = "/consumer/speech/synthesize/readaloud/edge/v1";
    private static final long WIN_EPOCH = 11644473600L;
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 单次请求的总时长上限（含连接），超过就放弃，避免界面无限等待 */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;

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

        String url = PATH
                + "?TrustedClientToken=" + TRUSTED_TOKEN
                + "&ConnectionId=" + uuidHex()
                + "&Sec-MS-GEC=" + secMsGec()
                + "&Sec-MS-GEC-Version=1-" + CHROMIUM_FULL;

        SSLSocket sock = null;
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            sock = (SSLSocket) factory.createSocket();
            sock.connect(new InetSocketAddress(HOST, 443), CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(READ_TIMEOUT_MS);
            sock.startHandshake();

            InputStream in = new BufferedInputStream(sock.getInputStream(), 16384);
            OutputStream out = sock.getOutputStream();

            handshake(in, out, url);

            // 1) 合成参数：只要音频，不要逐词时间戳
            sendText(out, "X-Timestamp:" + dateStr() + "\r\n"
                    + "Content-Type:application/json; charset=utf-8\r\n"
                    + "Path:speech.config\r\n\r\n"
                    + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                    + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\""
                    + "},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n");

            // 2) 待朗读内容（SSML）
            sendText(out, "X-RequestId:" + uuidHex() + "\r\n"
                    + "Content-Type:application/ssml+xml\r\n"
                    + "X-Timestamp:" + dateStr() + "Z\r\n"
                    + "Path:ssml\r\n\r\n"
                    + "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                    + "<voice name='" + voice + "'>"
                    + "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>"
                    + escapeXml(clean)
                    + "</prosody></voice></speak>");

            // 3) 收音频
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            String serverError = null;
            for (int guard = 0; guard < 3000; guard++) {
                Msg m = readMessage(in, out);
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
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (Throwable ignored) {
                }
            }
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
        if (status == null || status.indexOf("101") < 0) {
            throw new IOException("连接被拒绝" + (status == null ? "" : "：" + status));
        }
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            // 跳过剩余响应头
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

    private static synchronized void sendFrame(OutputStream out, int opcode, byte[] payload)
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
        out.write(buf.toByteArray());
        out.flush();
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

    /** Sec-MS-GEC：Windows 文件时间（100ns 单位）向下取整到 5 分钟，拼上固定令牌后 SHA-256 */
    private static String secMsGec() throws IOException {
        try {
            long ticks = System.currentTimeMillis() / 1000L + WIN_EPOCH;
            ticks -= ticks % 300;
            ticks *= 10000000L;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((ticks + TRUSTED_TOKEN).getBytes("US-ASCII"));
            return bytesToHex(d).toUpperCase(Locale.US);
        } catch (Exception e) {
            throw new IOException("令牌生成失败", e);
        }
    }

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
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

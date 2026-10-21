package com.jianyi.dict;

/**
 * 微软 Edge 朗读的 Sec-MS-GEC v2 令牌。
 *
 * v2 看着像 SHA-256，其实初值、K[14]、前 7 个字节的使用方式都被改过，
 * 所以不能用 MessageDigest 抄近路；这里是照 edge-tts 7.3.1 的 drm.py 逐行搬过来的，
 * 并且和 Python 做过逐字节对拍。改错一个常量只会变成服务端 403，本地不会报错。
 *
 * 微软换算法（上一版是 1-，标准 SHA-256）时这份会整体失效，
 * 表现是发音静默回落到系统语音 —— 排查先看握手响应码。
 */
final class Gec {

    /** Windows 文件时间基准：1601-01-01 到 1970-01-01 的秒数 */
    private static final long WIN_EPOCH = 11644473600L;
    /** 微软 Edge 朗读的固定客户端令牌，握手 URL 与令牌摘要共用这一份 */
    static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    /** 令牌里 Sec-MS-GEC-Version 用的号段，必须和这个算法版本配对 */
    static final String VERSION = "2-143.0.3650.75";

    private static final int[] H0 = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    };
    /** 非标准初值：前 7 个字节不参与压缩，而是喂进 initState 造出这份工作状态 */
    private static final int[] HS = {
            0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
            0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    };
    private static final int[] SCHEDULE = {
            0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA,
            0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6,
            0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
            0xD258B060, 0x72766564,
    };
    private static final int[] K = {
            0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5,
            0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
            0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3,
            0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
            0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC,
            0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
            0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7,
            0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
            0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
            0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
            0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3,
            0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
            0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
            0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208,
            0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    };
    private static final int BLOCK_ADJ = 0xC9DC06A7;
    private static final int SIGMA0_ADJ = 0x059C0B80;
    private static final int SIGMA1_ADJ = 0x000B92C0;

    private static final int[] CS64_C = {
            0xE82E91B1, 0x44DE24C6, 0x3BEB65BC, 0xC89C5933,
            0x8D1EC211, 0x314EF5D5, 0xE92491B1, 0xF0A5B9D5,
            0x06064CF3, 0xDA79FA60, 0xBED20560, 0x89F9C9F8,
            0x35FA38C6, 0xCB49E3C6,
    };
    private static final int[] CS64_O = {
            0xB32700D0, 0x88DFF51D, 0x29514038, 0xB328CBDF,
            0xD405B4D5, 0x26E55F3C, 0x607801C6, 0x82B2C1FB,
            0xFA51A3DA, 0x207CC464, 0x3C3C74F3, 0xD2D14B61,
            0xBB04A2C1, 0xC5626167,
    };

    private Gec() {
    }

    /** 当前时间窗口的令牌（5 分钟一档，同一档内可以一直复用） */
    static String current() {
        return token(inputFor(System.currentTimeMillis() / 1000L));
    }

    /** 秒级 Unix 时间 -> 待摘要字符串：对齐到 5 分钟窗口再换成 100ns 计数 */
    static String inputFor(long unixSeconds) {
        long ticks = unixSeconds + WIN_EPOCH;
        ticks -= ticks % 300;
        return (ticks * 10000000L) + TRUSTED_TOKEN;
    }

    /** 把待摘要的字符串算成 "<CS64>-<改版 SHA-256>"，单独拆出来便于和参考实现对拍 */
    static String token(String input) {
        byte[] data = input.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] digest = digest(data);
        int[] cs = cs64(data, digest);
        StringBuilder sb = new StringBuilder(16 + 1 + 64);
        appendLeHex(sb, cs[0]);
        appendLeHex(sb, cs[1]);
        sb.append('-');
        for (byte b : digest) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    // ================= 改版 SHA-256 =================

    /** 输入的前 7 个字节被拿去造初值，剩下的才按非标准填充压缩 */
    static byte[] digest(byte[] data) {
        int[] h = initState(data);
        int length = 57 + data.length;
        int zeros = Math.floorMod(55 - length, 64);
        byte[] padded = new byte[data.length - 7 + 1 + zeros + 8];
        System.arraycopy(data, 7, padded, 0, data.length - 7);
        padded[data.length - 7] = (byte) 0x80;
        long bits = length * 8L;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 8 + i] = (byte) (bits >>> (56 - 8 * i));
        }
        int[] w = new int[64];
        for (int off = 0; off < padded.length; off += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = (padded[off + i * 4] & 0xFF) << 24
                        | (padded[off + i * 4 + 1] & 0xFF) << 16
                        | (padded[off + i * 4 + 2] & 0xFF) << 8
                        | (padded[off + i * 4 + 3] & 0xFF);
            }
            expand(w);
            compress(h, w);
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (h[i] >>> 24);
            out[i * 4 + 1] = (byte) (h[i] >>> 16);
            out[i * 4 + 2] = (byte) (h[i] >>> 8);
            out[i * 4 + 3] = (byte) h[i];
        }
        return out;
    }

    /**
     * 初值不是标准 H0：先把 SCHEDULE 拼成一串"伪消息"，从 HS 状态的第 14 轮起压缩，
     * 最后再和 H0 相加。前 14 轮全零所以被跳过，这点必须照抄，不然状态对不上。
     */
    static int[] initState(byte[] data) {
        int[] w = new int[64];
        w[14] = (data[0] & 0xFF) << 16 | (data[1] & 0xFF) << 8 | (data[2] & 0xFF);
        w[15] = (data[3] & 0xFF) << 24 | (data[4] & 0xFF) << 16
                | (data[5] & 0xFF) << 8 | (data[6] & 0xFF);
        for (int i = 16; i < 30; i++) {
            int v = SCHEDULE[i - 16];
            int s1 = sigma1(w[i - 2]);
            v += i == 16 ? (s1 ^ SIGMA1_ADJ) : s1;      // 只有第 16 轮的 σ1 多异或一个调整量
            if (i - 7 >= 14) v += w[i - 7];
            if (i - 15 >= 14) {
                int s0 = sigma0(w[i - 15]);
                v += i == 29 ? (s0 ^ SIGMA0_ADJ) : s0;  // 同上，第 29 轮的 σ0
            }
            w[i] = v;
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + BLOCK_ADJ - K[14];
        expand(w, 31);

        int[] kb = K.clone();
        kb[14] = BLOCK_ADJ;
        // 注意：参考实现这里只要"工作寄存器"，把 HS 也加一遍就错了，
        // 最终状态是 H0 + 从第 14 轮跑出来的寄存器值。
        int[] working = rounds(HS, w, kb, 14);
        int[] h = new int[8];
        for (int i = 0; i < 8; i++) h[i] = H0[i] + working[i];
        return h;
    }

    private static void expand(int[] w) {
        expand(w, 16);
    }

    private static void expand(int[] w, int from) {
        for (int i = from; i < 64; i++) {
            w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16];
        }
    }

    /** 跑 64 轮压缩，只返回工作寄存器（参考实现的 _sha256_rounds） */
    private static int[] rounds(int[] st, int[] w, int[] k, int from) {
        int a = st[0], b = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], x = st[7];
        for (int i = from; i < 64; i++) {
            int t1 = x + bigSigma1(e) + (g ^ (e & (f ^ g))) + k[i] + w[i];
            int t2 = bigSigma0(a) + ((a & b) | (c & (a | b)));
            x = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        return new int[]{a, b, c, d, e, f, g, x};
    }

    /** 标准 Merkle-Damgård 收尾：状态 += 工作寄存器 */
    private static void compress(int[] h, int[] w) {
        int[] r = rounds(h, w, K, 0);
        for (int i = 0; i < 8; i++) h[i] += r[i];
    }

    private static int bigSigma0(int v) {
        return Integer.rotateRight(v, 2) ^ Integer.rotateRight(v, 13) ^ Integer.rotateRight(v, 22);
    }

    private static int bigSigma1(int v) {
        return Integer.rotateRight(v, 6) ^ Integer.rotateRight(v, 11) ^ Integer.rotateRight(v, 25);
    }

    private static int sigma0(int v) {
        return Integer.rotateRight(v, 7) ^ Integer.rotateRight(v, 18) ^ (v >>> 3);
    }

    private static int sigma1(int v) {
        return Integer.rotateRight(v, 17) ^ Integer.rotateRight(v, 19) ^ (v >>> 10);
    }

    // ================= CS64 =================

    /** 用摘要的前 8 字节当密钥，把整段输入再压成两个 32 位字 */
    static int[] cs64(byte[] data, byte[] digest) {
        int keyA = le32(digest, 0) | 1;
        int keyB = le32(digest, 4) | 1;
        int[] factors = {keyA * 0x82893933, keyB * 0x82893933};

        int[] words = leWords(data);
        int n = CS64_C.length + words.length;
        int[] terms = new int[n];
        int[] offsets = new int[n];
        for (int i = 0; i < CS64_C.length; i++) {
            terms[i] = CS64_C[i] * factors[i % 2];
            offsets[i] = CS64_O[i];
        }
        for (int i = 0; i < words.length; i++) {
            int word = words[i];
            int term = word * ((i & 1) == 0 ? keyA : keyB);
            int offset = rol16(word) * ((i & 1) == 0 ? 0xEF0569FB : 0xC31713DB);
            if (i == 0) {
                term += 0xF2F90B1A * factors[0];
                offset += 0x0B1A0000;
            }
            terms[CS64_C.length + i] = term;
            offsets[CS64_C.length + i] = offset;
        }

        int swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < n; i += 2) {
            int xv = terms[i], yv = terms[i + 1];
            int t = rol16(offsets[i] + xv);
            int q = t * 0x76F50F34;
            swapAcc += rol16(q) * 0xC3EFEA97 + q;
            t = rol16(offsets[i + 1] + yv);
            q = t * 0x1881CE3C;
            swapLast = rol16(q) * 0x35BD1EC9 + q;
            swapAcc += swapLast;

            state = state * keyA + xv;
            state = rol16(state) * 0xCF98B111;
            state = rol16(state) * 0x87085B9F;
            state = rol16(state) * 0x12CEB96D;
            state = rol16(state) * 0x257E1D83;
            acc += state;
            int t2 = state * keyB + yv;
            t2 = rol16(t2) * 0xA27416F5;
            t2 = rol16(t2) * 0xD38396FF;
            state = rol16(rol16(t2) * 0x7C932B89) * 0xBFA49F69;
            acc += state;
        }
        return new int[]{swapLast ^ state, swapAcc ^ acc};
    }

    /** 前面补一个 0 字节，再按小端 4 字节切词，词数凑成偶数 */
    private static int[] leWords(byte[] data) {
        int padded = (1 + data.length + 3) & ~3;
        int count = padded / 4;
        if ((count & 1) != 0) count++;
        int[] out = new int[count];
        for (int i = 0; i < data.length; i++) {
            int pos = i + 1;
            out[pos >> 2] |= (data[i] & 0xFF) << (8 * (pos & 3));
        }
        return out;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static int rol16(int v) {
        return Integer.rotateLeft(v, 16);
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** struct.pack('<II', ...) 的等价写法：字节序倒过来，每个字节内部仍是高四位在前 */
    private static void appendLeHex(StringBuilder sb, int v) {
        for (int i = 0; i < 4; i++) {
            int b = (v >>> (8 * i)) & 0xFF;
            sb.append(HEX[b >> 4]).append(HEX[b & 0xF]);
        }
    }
}

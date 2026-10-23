package com.jianyi.dict;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 云端凭据的落盘加密。
 *
 * 为什么要加密：
 * 百度 AppSecret、腾讯云 SecretKey 原来是明文躺在 SharedPreferences 里，
 * 只要拿到备份文件、或在 root 过的机器上读一下 prefs，凭据就等于交出去。
 * 现在改成用 Android Keystore 里的 AES-GCM 密钥加密后再落盘：
 *   · 备份文件 / 备份规则里只剩一段读不懂的密文；
 *   · 密钥本身在系统 Keystore 里，不随 App 数据走；
 *   · 把备份恢复到另一台设备也解不开（那台机器上没有这把密钥）——
 *     这正是我们要的：凭据不该跟着换机搬家。
 *
 * 老数据怎么办：
 * 老版本写的是明文，键名就是本名（"baidu_secret"…）。第一次读到明文就顺手
 * 加密存进 "名字.enc"，再把明文删掉。用户不用重新填一次。
 *
 * 兜底：
 * 极少数定制系统上 Keystore 不可用。这种情况下宁可明文（功能不能因为这个坏掉），
 * 但会在日志里留下一条明确的警告，而不是悄悄降级。
 */
final class SecretStore {

    /** Keystore 里的别名；升级不换名，这样老密文还能解开 */
    private static final String ALIAS = "jianyi.cloud.v1";
    private static final String SUF_ENC = ".enc";
    private static final String SUF_IV = ".iv";

    /** 同一次运行内反复读不必每次都问 Keystore 解密 */
    private static final Map<String, String> sCache = new HashMap<>();

    private SecretStore() {
    }

    /**
     * 取一个凭据。
     * @return 明文；没配 / 解不开时返回空串（调用方按「没配」处理）
     */
    static String get(SharedPreferences p, String name) {
        if (p == null || name == null || name.isEmpty()) return "";
        synchronized (sCache) {
            String cached = sCache.get(name);
            if (cached != null) return cached;
        }
        String enc = p.getString(name + SUF_ENC, null);
        String plain;
        if (enc != null && !enc.isEmpty()) {
            plain = decrypt(p, name, enc);
        } else {
            // 老版本留下的明文：读出来立刻加密，再把明文删掉
            String legacy = p.getString(name, "");
            plain = legacy == null ? "" : legacy;
            if (!plain.isEmpty()) {
                if (encrypt(p, name, plain)) {
                    p.edit().remove(name).apply();
                }
            }
        }
        synchronized (sCache) {
            sCache.put(name, plain);
        }
        return plain;
    }

    /** 存一个凭据；空串等于删除 */
    static void put(SharedPreferences p, String name, String value) {
        if (p == null || name == null || name.isEmpty()) return;
        if (value == null || value.isEmpty()) {
            remove(p, name);
            return;
        }
        if (encrypt(p, name, value)) {
            p.edit().remove(name).apply();      // 确认不留明文
        } else {
            // Keystore 用不了：明文兜底，功能优先；日志里留一条警告
            p.edit().putString(name, value).apply();
            JLog.w("SecretStore.put", "Keystore 不可用，凭据退回明文存储");
        }
        synchronized (sCache) {
            sCache.put(name, value);
        }
    }

    /** 删掉一个凭据（连密文带明文一起删） */
    static void remove(SharedPreferences p, String name) {
        if (p == null || name == null) return;
        p.edit().remove(name).remove(name + SUF_ENC).remove(name + SUF_IV).apply();
        synchronized (sCache) {
            sCache.remove(name);
        }
    }

    // ================= 内部实现 =================

    /** 加密后写进 "名字.enc" + "名字.iv" */
    private static boolean encrypt(SharedPreferences p, String name, String value) {
        try {
            SecretKey key = key(true);
            if (key == null) return false;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = c.getIV();                       // GCM 每次都换随机 IV
            byte[] data = c.doFinal(value.getBytes("UTF-8"));
            p.edit()
                    .putString(name + SUF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(name + SUF_ENC, Base64.encodeToString(data, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Throwable t) {
            JLog.w("SecretStore.encrypt", t);
            return false;
        }
    }

    /** 解密；解不开就把这段密文删掉（留着只会每次都失败） */
    private static String decrypt(SharedPreferences p, String name, String encB64) {
        try {
            String ivB64 = p.getString(name + SUF_IV, "");
            if (ivB64 == null || ivB64.isEmpty()) return "";
            SecretKey key = key(false);
            if (key == null) return "";
            byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);
            byte[] data = Base64.decode(encB64, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(c.doFinal(data), "UTF-8");
        } catch (Throwable t) {
            // 最常见的原因是这台设备上没有那把密钥（备份恢复到别的机器）。
            // 这时只能请用户重填一次，密文留着没有意义。
            JLog.w("SecretStore.decrypt", "凭据解不开（多半是换了设备），请重新填写");
            p.edit().remove(name + SUF_ENC).remove(name + SUF_IV).apply();
            return "";
        }
    }

    /**
     * 取（或建）Keystore 里的 AES 密钥。
     * @param create true = 没有就建一把；false = 只取，没有就返回 null
     */
    private static SecretKey key(boolean create) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            }
            if (!create) return null;
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return kg.generateKey();
        } catch (Throwable t) {
            JLog.w("SecretStore.key", t);
            return null;
        }
    }
}

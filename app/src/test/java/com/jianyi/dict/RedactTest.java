package com.jianyi.dict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 崩溃日志脱敏：凭据不能随日志出门。
 */
public class RedactTest {

    @Test
    public void 百度那种带签名的地址整条抹掉() {
        String line = "java.io.IOException: failed https://fanyi-api.baidu.com/api/trans/vip/translate"
                + "?q=hello&appid=12345&salt=99&sign=deadbeef";
        String out = Redact.crash(line);
        assertFalse(out.contains("fanyi-api.baidu.com"));
        assertFalse(out.contains("appid=12345"));
        assertFalse(out.contains("sign=deadbeef"));
        assertFalse(out.contains("hello"));
        assertTrue(out.contains("[URL 已隐去]"));
    }

    @Test
    public void 参数值单独出现也要抹掉() {
        String out = Redact.crash("appid=12345 sign=deadbeef token=abc");
        assertFalse(out.contains("12345"));
        assertFalse(out.contains("deadbeef"));
        assertFalse(out.contains("abc"));
        assertTrue(out.contains("appid=[已隐去]"));
        assertTrue(out.contains("sign=[已隐去]"));
    }

    @Test
    public void 冒号写法的头也要抹掉() {
        // 腾讯 TC3 的 Authorization 头值就长这样：算法名 + Credential= + Signature=
        String line = "javax.net.ssl.SSLHandshakeException: Authorization: TC3-HMAC-SHA256 "
                + "Credential=AKIDexample/20261023, SignedHeaders=host, Signature=deadbeefcafe";
        String out = Redact.crash(line);
        assertFalse(out.contains("TC3-HMAC-SHA256"));
        assertFalse(out.contains("AKIDexample"));
        assertFalse(out.contains("deadbeefcafe"));
        assertTrue(out.contains("Authorization=[已隐去]"));
        assertTrue(out.contains("Credential=[已隐去]"));
        assertTrue(out.contains("Signature=[已隐去]"));
    }

    @Test
    public void JSON片段里的键值也要抹掉() {
        String line = "com.google.gson.JsonSyntaxException: "
                + "{\"error\":\"invalid_sign\",\"secret\":\"abc123xyz\"}";
        String out = Redact.crash(line);
        assertFalse(out.contains("abc123xyz"));
        assertTrue(out.contains("secret=[已隐去]"));
        // 值叫 invalid_sign 但本身不是参数，不该被误伤
        assertTrue(out.contains("invalid_sign"));
    }

    @Test
    public void 普通栈帧原样保留() {
        String line = "at com.jianyi.dict.MainActivity.doTranslate(MainActivity.java:4001)";
        assertTrue(Redact.crash(line).contains("MainActivity.java:4001"));
    }

    @Test
    public void 多行一起处理() {
        String stack = "java.lang.NullPointerException: boom\n"
                + "\tat com.jianyi.dict.Foo.bar(Foo.java:1)\n"
                + "url=https://example.com/x?appid=9";
        String out = Redact.crash(stack);
        assertFalse(out.contains("example.com"));
        assertTrue(out.contains("NullPointerException"));
        assertTrue(out.contains("Foo.java:1"));
    }

    @Test
    public void 空输入不炸() {
        assertEquals("", Redact.crash(null));
        assertEquals("", Redact.crash(""));
    }
}

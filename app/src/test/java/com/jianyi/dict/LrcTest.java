package com.jianyi.dict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

/**
 * LRC 字幕解析（纯计算）。
 *
 * 用例里的字幕样貌取自 assets/listening 下真实文件（[ti:] / [by:] 元数据行 +
 * 每行 [mm:ss.xx]，全站都是两位小数），小数位的 1/3/4 位形式是 21voa 早期样本里
 * 出现过的写法，用来锁住「按位数折算」这条规则。
 */
public class LrcTest {

    @Test
    public void 解析真实字幕并保留文本() {
        String raw = "[ti:PISA 2022: Students' Historic Setbacks]\n"
                + "[by:VOA Learning English]\n"
                + "\n"
                + "[00:01.54]Students around the world suffered historic setbacks.\n"
                + "[00:12.04]That information comes from results of the latest PISA.\n"
                + "[01:02.30]The PISA is an international comparative study.\n";
        ArrayList<Lrc.Line> lines = Lrc.parse(raw);
        assertEquals(3, lines.size());
        assertEquals(1 * 1000 + 540, lines.get(0).startMs);
        assertEquals(12 * 1000 + 40, lines.get(1).startMs);
        assertEquals(62 * 1000 + 300, lines.get(2).startMs);
        assertTrue(lines.get(0).text.startsWith("Students around the world"));
    }

    @Test
    public void 元数据空行和广告行都不算歌词() {
        String raw = "[ti:标题]\n"
                + "[by:www.21voa.com]\n"
                + "[al:专辑]\n"
                + "纯文本没有方括号，丢掉\n"
                + "[00:00.00]21VOA.COM 广告前缀\n"
                + "[00:05.00]正文\n"
                + "[00:09.00]21voa.com 另一种写法\n"
                + "[00:99]\n";            // 时间戳有、正文为空 → 丢
        ArrayList<Lrc.Line> lines = Lrc.parse(raw);
        assertEquals(1, lines.size());
        assertEquals("正文", lines.get(0).text);
    }

    @Test
    public void 小数位按位数折算成毫秒() {
        // .5 是半秒不是 5 毫秒；.04 是 40 毫秒
        assertEquals(500, Lrc.parse("[00:00.5]x").get(0).startMs);
        assertEquals(40, Lrc.parse("[00:00.04]x").get(0).startMs);
        assertEquals(123, Lrc.parse("[00:00.123]x").get(0).startMs);
        // 超过 3 位是微秒噪声：只认前三位，不能整串乘出去
        assertEquals(40, Lrc.parse("[00:00.0400]x").get(0).startMs);
        assertEquals(500, Lrc.parse("[00:00.500000]x").get(0).startMs);
        assertEquals(0, Lrc.parse("[00:00.00]x").get(0).startMs);
        // 整分整秒
        assertEquals(60_000, Lrc.parse("[01:00]x").get(0).startMs);
        assertEquals(65_400, Lrc.parse("[01:05.40]x").get(0).startMs);
    }

    @Test
    public void 时间乱序也要按时间升序排() {
        String raw = "[00:30.00]第三句\n"
                + "[00:05.00]第一句\n"
                + "[00:15.00]第二句\n";
        ArrayList<Lrc.Line> lines = Lrc.parse(raw);
        assertEquals(5000, lines.get(0).startMs);
        assertEquals(15000, lines.get(1).startMs);
        assertEquals(30000, lines.get(2).startMs);
        // indexAt 的「提前 break」依赖这个顺序，顺序错了就会跳句
        assertEquals(0, Lrc.indexAt(lines, 6000));
        assertEquals(1, Lrc.indexAt(lines, 15000));
        assertEquals(2, Lrc.indexAt(lines, 99_000));
    }

    @Test
    public void 播放位置查句子() {
        ArrayList<Lrc.Line> lines = Lrc.parse("[00:01.00]一\n[00:02.00]二\n[00:03.00]三\n");
        assertEquals(-1, Lrc.indexAt(lines, 0));        // 还没开始
        assertEquals(-1, Lrc.indexAt(lines, 999));
        assertEquals(0, Lrc.indexAt(lines, 1000));
        assertEquals(1, Lrc.indexAt(lines, 2500));
        assertEquals(2, Lrc.indexAt(lines, 10_000));
        assertEquals(-1, Lrc.indexAt(new ArrayList<>(), 1000));
    }

    @Test
    public void 坏行不能把整个字幕带崩() {
        ArrayList<Lrc.Line> lines = Lrc.parse(
                "[00:0abc]坏时间戳\n"
                        + "[notatime]元数据\n"
                        + "[00:01.00]好的一行\n"
                        + "[]空标签\n"
                        + "[00:99999999999999:00]溢出\n");
        assertEquals(1, lines.size());
        assertEquals("好的一行", lines.get(0).text);
    }

    @Test
    public void 空输入返回空列表() {
        assertTrue(Lrc.parse(null).isEmpty());
        assertTrue(Lrc.parse("").isEmpty());
        assertTrue(Lrc.parse("   \n  \n").isEmpty());
    }
}

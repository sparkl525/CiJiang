package com.jianyi.dict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

/**
 * WordImport 的纯逻辑测试（v3.15.1）：神秘开关导进来的文本长什么样都有可能，
 * 解析这层一旦认错词和释义，牌面上就是莫名其妙的方块，所以每种粘贴格式都钉一条。
 */
public class WordImportTest {

    private static String[] line(String s) {
        return WordImport.parseLine(s);
    }

    @Test
    public void 空格分隔是最常见的格式() {
        String[] r = line("apple 苹果");
        assertEquals("apple", r[0]);
        assertEquals("苹果", r[1]);
    }

    @Test
    public void 等号分隔右边带逗号只留第一个义项() {
        // 方块上塞整条「放弃, 遗弃」读不完，而且两条长释义截断后容易撞车
        assertArrayEq(line("abandon = 放弃, 遗弃"), new String[]{"abandon", "放弃"});
        assertArrayEq(line("abandon = 放弃；遗弃"), new String[]{"abandon", "放弃"});
    }

    @Test
    public void 中文冒号和全角等号也认() {
        assertArrayEq(line("book：书"), new String[]{"book", "书"});
        assertArrayEq(line("book＝书"), new String[]{"book", "书"});
        assertArrayEq(line("book\t书"), new String[]{"book", "书"});
    }

    @Test
    public void 中英写反时把英文当词() {
        assertArrayEq(line("苹果 = apple"), new String[]{"apple", "苹果"});
        assertArrayEq(line("放弃 abandon"), new String[]{"abandon", "放弃"});
    }

    @Test
    public void 序号和词性都剥掉() {
        assertArrayEq(line("1. apple 苹果"), new String[]{"apple", "苹果"});
        assertArrayEq(line("12、banana 香蕉"), new String[]{"banana", "香蕉"});
        assertArrayEq(line("abandon v. 放弃"), new String[]{"abandon", "放弃"});
        assertArrayEq(line("brave adj. 勇敢的"), new String[]{"brave", "勇敢的"});
        assertArrayEq(line("cat n. 猫"), new String[]{"cat", "猫"});
    }

    @Test
    public void 只给英文词时释义留空交给词典() {
        assertArrayEq(line("banana"), new String[]{"banana", ""});
        assertArrayEq(line("BANANA"), new String[]{"banana", ""});
    }

    @Test
    public void 词组要收下单字母和纯中文不要() {
        assertArrayEq(line("take off 脱下"), new String[]{"take off", "脱下"});
        assertArrayEq(line("well-being 福祉"), new String[]{"well-being", "福祉"});
        assertNull(line("I 我"));
        assertNull(line("苹果"));
        assertNull(line("123 一二三"));
    }

    @Test
    public void 注释和音标行直接跳过() {
        assertNull(line("# 这是注释"));
        assertNull(line("// note"));
        assertNull(line("[ˈæpl]"));
        assertNull(line(""));
        assertNull(line("   "));
    }

    @Test
    public void 释义太长截到十六字() {
        String[] r = line("abandon 放弃遗弃抛弃舍弃放弃自己不要我了啊啊啊啊");
        assertEquals(16, r[1].length());
    }

    @Test
    public void 释义没到上限就原样留着() {
        String[] r = line("abandon 放弃遗弃抛弃舍弃放弃自己");
        assertEquals(12, r[1].length());
    }

    @Test
    public void 一长串义项只留第一个() {
        assertArrayEq(line("abandon 放弃, 遗弃, 抛弃, 舍弃, 五斗"), new String[]{"abandon", "放弃"});
        assertArrayEq(line("book 书、簿、册本"), new String[]{"book", "书"});
    }

    @Test
    public void 整份文本按词和释义双重去重() {
        ArrayList<String[]> rows = WordImport.parse(
                "apple 苹果\napple 果子\nbanana 香蕉\npear 苹果");
        assertEquals(2, rows.size());
        assertEquals("apple", rows.get(0)[0]);
        assertEquals("banana", rows.get(1)[0]);
    }

    @Test
    public void 多种换行都能切开() {
        assertEquals(2, WordImport.parse("a1 甲\r\na2 乙").size());
        assertEquals(2, WordImport.parse("a1 甲\ra2 乙").size());
        assertTrue(WordImport.parse(null).isEmpty());
        assertTrue(WordImport.parse("").isEmpty());
    }

    @Test
    public void 超过上限就截断() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < WordImport.MAX_WORDS + 50; i++) {
            sb.append("word").append(i).append(" 词").append(i).append("\n");
        }
        assertEquals(WordImport.MAX_WORDS, WordImport.parse(sb.toString()).size());
    }

    @Test
    public void 词典补的释义也走同一条只留第一个义项() {
        // GameWords.useImport 查到的义项很长，必须和贴进来的文本同一口径，
        // 否则两条长释义硬截断会撞成同一个前缀，屏幕上就是两块一样的牌
        assertEquals("放弃", WordImport.firstClause("放弃, 遗弃, 抛弃"));
        assertEquals("放弃", WordImport.firstClause("放弃；遗弃"));
        assertEquals("勇敢的", WordImport.firstClause("勇敢的"));
        assertEquals("a b", WordImport.firstClause("a b"));
        assertEquals("", WordImport.firstClause(""));
        assertEquals("", WordImport.firstClause(null));
    }

    private static void assertArrayEq(String[] got, String[] want) {
        assertEquals(2, got.length);
        assertEquals(want[0], got[0]);
        assertEquals(want[1], got[1]);
    }
}

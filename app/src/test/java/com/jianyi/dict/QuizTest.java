package com.jianyi.dict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * 出题逻辑（纯计算）。
 *
 * 下面这些 translation 字符串不是编的，是从随 APK 打包的词典里查出来的真实内容
 * （apple / make / strength / economy / data / quartz / umbrella，以及 phrases 表里
 * head=abandon 的那几条），所以这几个用例锁的是「用户实际会看到的题」。
 * 哪天换词典、改清洗规则，这里会立刻告诉你题目是不是变傻。
 */
public class QuizTest {

    /** 词典里的真实释义（保留原样的换行、词性前缀、全半角逗号） */
    private static final String T_APPLE = "n. 苹果, 家伙\n[医] 苹果";
    private static final String T_MAKE = "vt. 制造, 安排, 创造, 构成, 使得, 产生, 造成, 整理, 布置, 引起, 到达, 进行\n"
            + "vi. 开始, 前进, 增大, 被制造, 被处理\nn. 制造, 构造, 性情";
    private static final String T_STRENGTH = "n. 力量, 实力, 强度, 浓度, 人数, 抵抗力\n[化] 强度";
    private static final String T_ECONOMY = "n. 经济, 理财, 节约\n[医] 经济, 整体";
    private static final String T_DATA = "pl. 资料, 数据\n[计] 数据; DOS内部命令:用于显示或设定系统的日期";
    private static final String T_QUARTZ = "n. 石英\n[化] 石英";
    private static final String T_UMBRELLA = "n. 伞, 雨伞, 保护伞\na. 伞的, 包罗万象的\nvt. 用伞遮掩";

    @Test
    public void 真实释义能切成可用义项() {
        assertEquals(Arrays.asList("苹果", "家伙"), Quiz.meanings(T_APPLE, 10));
        assertEquals(Arrays.asList("制造", "安排", "创造"), Quiz.meanings(T_MAKE, 10));
        assertEquals(Arrays.asList("力量", "实力", "强度"), Quiz.meanings(T_STRENGTH, 10));
        assertEquals(Arrays.asList("经济", "理财", "节约"), Quiz.meanings(T_ECONOMY, 10));
        // pl. 这种复数前缀也在 POS 表里，漏了就会出题成 "pl. 资料"
        assertEquals(Arrays.asList("资料", "数据"), Quiz.meanings(T_DATA, 10));
    }

    @Test
    public void 只取首行不会被例句和补充行污染() {
        // quartz 的第二行是 [化] 石英；data 的第二行是一整句 DOS 命令说明
        assertEquals(Arrays.asList("石英"), Quiz.meanings(T_QUARTZ, 10));
        for (String s : Quiz.meanings(T_DATA, 10)) {
            assertFalse("首行之外的内容混进题目了：" + s, s.contains("DOS") || s.contains("内部命令"));
        }
    }

    @Test
    public void 单字和词根碎片不能成题() {
        // umbrella 的第一个义项「伞」只有一个字，直接当选项会歧义
        assertEquals(Arrays.asList("雨伞", "保护伞"), Quiz.meanings(T_UMBRELLA, 10));
        // 全是碎片的释义就该返回空，调用方据此跳过这个词
        assertTrue(Quiz.meanings("n. 孔，洞；场所", 10).isEmpty());
        assertTrue(Quiz.meanings(null, 10).isEmpty());
        assertTrue(Quiz.meanings("", 10).isEmpty());
    }

    @Test
    public void 方括号标签和分号后的补充要丢掉() {
        assertEquals(Arrays.asList("交流"), Quiz.meanings("[化] 交流; 交变电流", 10));
        assertEquals(Arrays.asList("拯救骤变的地球"), Quiz.meanings("[网络] 拯救骤变的地球；改变", 10));
    }

    @Test
    public void 义项过长就按上限截断() {
        for (String s : Quiz.meanings("n. 可持续发展, 环境保护", 3)) {
            assertTrue("截断后仍超长：" + s, s.length() <= 3);
        }
        assertEquals(2, Quiz.meanings("n. 可持续发展, 环境保护", 4).size());
    }

    @Test
    public void 重复义项只留一份() {
        // 词典里同一个词反复出现同一义项（chase 就是这样），清洗后不能出现两个"追逐"
        assertEquals(Arrays.asList("追逐", "猎取"), Quiz.meanings("n. 追逐, 追逐, 猎取", 10));
    }

    @Test
    public void usable_的边界() {
        assertFalse(Quiz.usable(null));
        assertFalse(Quiz.usable(""));
        assertFalse(Quiz.usable("伞"));                     // 单字
        assertFalse(Quiz.usable("性的"));                    // 词根碎片（尾字「的」）
        assertFalse(Quiz.usable("美化"));                    // 尾字「化」
        assertFalse(Quiz.usable("男性"));                    // 尾字「性」
        assertFalse(Quiz.usable("苹果，水果"));               // 带标点，没清洗过
        assertFalse(Quiz.usable("computer 硬件"));            // 混着没翻译的英文
        // 注：注释里举的「化了」其实没被拦住——尾字符名单里只有 的/地/性/儿/化，没有「了」。
        // 要不要把「了」也加进去属于出题口味，留给产品决定，这里先如实记下现在的行为。
        assertTrue(Quiz.usable("化了"));
        StringBuilder longOne = new StringBuilder();
        for (int i = 0; i < 11; i++) longOne.append('词');
        assertFalse(Quiz.usable(longOne.toString()));        // 超过 10 字
        assertTrue(Quiz.usable("苹果"));
        assertTrue(Quiz.usable("可持续发展"));
        assertTrue(Quiz.usable("A股"));                       // 字母数字 + 中文，允许
    }

    @Test
    public void 干扰项要够迷惑才收() {
        // 长度接近、互不包含 → 收
        assertTrue(Quiz.plausible("制造", "安排"));
        assertTrue(Quiz.plausible("经济", "社会学"));
        // 同一个词、空值 → 不收（否则选项里出现两个一样的）
        assertFalse(Quiz.plausible("制造", "制造"));
        assertFalse(Quiz.plausible("制造", null));
        assertFalse(Quiz.plausible(null, "制造"));
        // 互相包含 → 不收（"苹果"配"苹果树"等于送分）
        assertFalse(Quiz.plausible("苹果", "苹果树"));
        assertFalse(Quiz.plausible("苹果树", "苹果"));
        // 长度差超过 3 → 不收
        assertFalse(Quiz.plausible("苹果", "环境保护和可持续发展"));
    }

    @Test
    public void 选择题最多四个选项且答案位置正确() {
        Quiz q = Quiz.meaning("make", "制造",
                Arrays.asList("安排", "创造", "构成", "使得"), new Random(7));
        assertEquals(Quiz.TYPE_MEANING, q.type);
        assertEquals(4, q.options.size());
        assertEquals("制造", q.options.get(q.answerIndex));
        assertEquals(1, countEquals(q.options, "制造"));
        // 干扰项溢出后多余的那些被丢掉
        assertFalse(q.options.contains("使得"));
        // null / 空串 / 和答案重复的干扰项都不能进选项
        Quiz q2 = Quiz.meaning("apple", "苹果", Arrays.asList("苹果", null, "", "家伙"), new Random(1));
        assertEquals(Arrays.asList("家伙", "苹果"), sorted(q2.options));
        assertEquals("苹果", q2.options.get(q2.answerIndex));
    }

    @Test
    public void 拼写题判分与提示() {
        Quiz q = Quiz.spell("monkey", "猴子");
        assertEquals(Quiz.TYPE_SPELL, q.type);
        assertTrue(q.checkSpelling("monkey"));
        assertTrue("大小写和首尾空格不该算错", q.checkSpelling("  MonKey "));
        assertFalse(q.checkSpelling("monkes"));
        assertFalse(q.checkSpelling(null));
        assertFalse(q.checkSpelling(""));
        assertEquals("m _ _ _ _ _", q.spellHint());
        assertEquals("", Quiz.spell(null, "x").spellHint());
        assertEquals("", Quiz.spell("", "x").spellHint());
    }

    @Test
    public void 短语题两个方向都能出() {
        List<String> ds = Arrays.asList("abandon for", "abandon to", "give up", "多余的第四项");
        Quiz en = Quiz.phrase("abandon oneself to", "沉溺于", ds, true, new Random(3));
        assertEquals(Quiz.TYPE_PHRASE, en.type);
        assertTrue(en.phraseAskChinese);
        assertEquals(4, en.options.size());
        assertEquals("abandon oneself to", en.options.get(en.answerIndex));

        Quiz zh = Quiz.phrase("abandon oneself to", "沉溺于", Arrays.asList("放弃", "使全然陷入"), false, new Random(5));
        assertFalse(zh.phraseAskChinese);
        assertEquals("沉溺于", zh.options.get(zh.answerIndex));
        assertEquals(3, zh.options.size());
    }

    @Test
    public void 干扰项凑不满两个就不出题() {
        assertNull(Quiz.phrase("abandon oneself to", "沉溺于", Arrays.asList(), true, new Random(1)));
        assertNull(Quiz.phrase("abandon oneself to", "沉溺于", Arrays.asList(null, ""), true, new Random(1)));
        // 只有一个像样的干扰项时，退化成本来就该被拒的题：宁可不出也不能出成送分题
        assertNull(Quiz.phrase("x", "y", Arrays.asList("x"), true, new Random(1)));
    }

    private static int countEquals(List<String> l, String s) {
        int n = 0;
        for (String x : l) if (s.equals(x)) n++;
        return n;
    }

    private static List<String> sorted(ArrayList<String> in) {
        ArrayList<String> out = new ArrayList<>(in);
        out.sort(String::compareTo);
        return out;
    }

    /** 选项集合的去重检查（防止将来 shuffle 改动引入重复项） */
    @Test
    public void 选择题选项互不相同() {
        Quiz q = Quiz.meaning("chase", "追求", Arrays.asList("追求", "狩猎", "追逐", "追捕", "奔跑"), new Random(11));
        assertEquals(q.options.size(), new HashSet<>(q.options).size());
        assertEquals(4, q.options.size());
        assertEquals("追求", q.options.get(q.answerIndex));
    }
}

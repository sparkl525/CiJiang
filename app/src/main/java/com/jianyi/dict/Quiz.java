package com.jianyi.dict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一道记背题。
 *
 * 两种题型：
 *  - 看单词选释义（选择题）：干扰项从同一考试标签里取，且要求长度接近、互不包含，避免"一眼看出"
 *  - 看释义拼单词（拼写题）：给出首字母和字母数提示，校验时忽略大小写和首尾空格
 *
 * 答错记进陌生词本，按 Leitner 盒子安排下次出现——见 StudyDb。
 */
public class Quiz {

    public static final int TYPE_MEANING = 0;
    public static final int TYPE_SPELL = 1;

    public int type;
    public String word;          // 正确答案的单词
    public String meaning;       // 正确答案的中文释义（取首行第一个合格义项）
    public ArrayList<String> options;   // 选择题选项，已打乱
    public int answerIndex;      // 正确项在 options 里的位置

    /** 词性前缀，如 "n." "vt." "adj." —— 取释义时要剥掉 */
    private static final Pattern POS = Pattern.compile(
            "^(?:n|v|vt|vi|vn|adj|adv|prep|conj|pron|num|art|int|aux|abbr|pl|pref|suff)\\s*\\.",
            Pattern.CASE_INSENSITIVE);

    /** 看单词选释义 */
    public static Quiz meaning(String word, String meaning, List<String> distractors, Random r) {
        Quiz q = new Quiz();
        q.type = TYPE_MEANING;
        q.word = word;
        q.meaning = meaning;
        q.options = new ArrayList<>();
        q.options.add(meaning);
        for (String d : distractors) {
            if (q.options.size() >= 4) break;
            if (d == null || d.isEmpty() || q.options.contains(d)) continue;
            q.options.add(d);
        }
        Collections.shuffle(q.options, r);
        q.answerIndex = q.options.indexOf(meaning);
        return q;
    }

    /** 看释义拼单词 */
    public static Quiz spell(String word, String meaning) {
        Quiz q = new Quiz();
        q.type = TYPE_SPELL;
        q.word = word;
        q.meaning = meaning;
        return q;
    }

    /** 拼写题判分：忽略大小写和多余空格 */
    public boolean checkSpelling(String input) {
        if (input == null) return false;
        return input.trim().equalsIgnoreCase(word);
    }

    /** 拼写题提示：m _ _ _ _（首字母 + 字母个数） */
    public String spellHint() {
        if (word == null || word.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            if (i == 0) sb.append(word.charAt(i));
            else sb.append(" _");
        }
        return sb.toString();
    }

    /**
     * 把整条释义切成一组干净可用的短义项。
     * 只取首行（ECDICT 第二行起常是例句/补充），按逗号切成多个义项，
     * 逐个剥掉词性前缀和括号内容，留下能单独成题的那些。
     */
    public static ArrayList<String> meanings(String translation, int maxLen) {
        ArrayList<String> out = new ArrayList<>();
        if (translation == null) return out;
        String first = translation.split("\n")[0];
        for (String seg : first.split("[,，、]")) {
            String s = clean(seg);
            if (s.length() > maxLen) s = s.substring(0, maxLen).trim();
            if (usable(s) && !out.contains(s)) out.add(s);
            if (out.size() >= 3) break;
        }
        return out;
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("[（(【\\[].*?[)）\\]】]", "");   // 括号及其内容
        s = s.replaceAll("<[^>]*>", "");                   // <...> 标记
        s = s.replaceAll("[;；:：].*$", "");               // 分号/冒号后面整段丢掉
        s = s.trim();
        Matcher m = POS.matcher(s);
        if (m.find()) s = s.substring(m.end()).trim();
        return s.trim();
    }

    /**
     * 这个义项能不能单独出现在题目里。
     * 剔除：单字（"孔""热"）、词根碎片（"性的""化了"）、过长、含未翻译的英文串。
     */
    public static boolean usable(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.length() < 2 || s.length() > 10) return false;
        int cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else if (!(Character.isLetterOrDigit(c) || c == ' ' || c == '·' || c == '-')) return false;
        }
        if (cjk < 1) return false;                                  // 没有中文，多半是残条
        if (s.matches(".*[A-Za-z]{3,}.*")) return false;             // 混着长英文，没翻译干净
        if (s.length() <= 2) {
            char last = s.charAt(s.length() - 1);
            if (last == '的' || last == '地' || last == '性' || last == '儿' || last == '化') return false;
        }
        return true;
    }

    /**
     * 干扰项够不够迷惑：长度差不超过 3，且互不包含。
     * 否则会出现"制造"配"热"这种一眼就能排除的选项。
     */
    public static boolean plausible(String answer, String cand) {
        if (answer == null || cand == null || answer.equals(cand)) return false;
        if (Math.abs(answer.length() - cand.length()) > 3) return false;
        if (answer.contains(cand) || cand.contains(answer)) return false;
        return true;
    }
}

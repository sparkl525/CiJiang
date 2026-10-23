package com.jianyi.dict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 配对小游戏的出词器（v3.8）。
 *
 * 出词顺序：
 *   ① 错题集 / 单词本里的词 —— 不排队，优先插进来（这些是玩家自己的难点）
 *   ② 六级高频 → ③ 六级中频 → ④ 六级低频 → ⑤ 词典里剩下的常用词
 * 每个词只出一次，中文释义也全局去重（两个词都释义成「状态」的话，
 * 同屏出现会让玩家分不清该配哪个，等于送错）。
 *
 * 为什么单独抽一个类：词源从「背过的词」换成了整本词典，
 * 一万多词不可能一次性组好丢给界面——这里按 300 词一页懒加载，
 * 界面每屏来要 5 对，取完再取下一页。
 */
public class GameWords {

    private static final int STAGE_HIGH = 0;
    private static final int STAGE_MID = 1;
    private static final int STAGE_LOW = 2;
    private static final int STAGE_OTHER = 3;
    private static final int STAGE_DONE = 4;

    private static final int PAGE_SIZE = 300;      // 一次从库里取多少词
    private static final int PERSONAL_MAX = 300;   // 个人词最多预取这么多，别开屏卡住
    private static final int OTHER_SKIP = 300;     // 「其他」档跳过最常用的一批（the/and 这种没配对待的意义）
    /** 错词回炉：错完当屏不算，之后完整过三组（3 屏 = 15 对），下一屏才回来 */
    private static final int WRONG_BACK_WAVES = 3;
    private static final int WRONG_MAX = 8;        // 存档最多留这么多，别无限堆

    private final DictDb dict;
    private final StudyDb study;

    /** 错题集 + 单词本的词，优先出 */
    private final ArrayList<MatchGameView.Pair> personal = new ArrayList<>();
    /** 已组好、还没上场的词对 */
    private final ArrayList<MatchGameView.Pair> queue = new ArrayList<>();
    /** 上次退出时没消完的牌，接着用（恢复进度用） */
    private final ArrayList<MatchGameView.Pair> resume = new ArrayList<>();

    /**
     * 神秘开关：玩家自己导入的词（v3.15.1）。
     * 非空 = 只出这些词，词典那套词频顺序原样停在原地不动，关掉开关就接着往下走。
     */
    private final ArrayList<MatchGameView.Pair> custom = new ArrayList<>();
    /** 导入表的发牌堆：洗完一轮再洗下一轮，这样一屏之内不会重样 */
    private final ArrayList<MatchGameView.Pair> customDeck = new ArrayList<>();

    /**
     * isCustom() / customCount() 的无锁快照（v3.16.1 修）。
     * custom 是普通 ArrayList，界面线程直接读它，可能正好撞上 dbPool 上
     * useImport 的 addAll 进行到一半——读到半个数（比如 ✦ 后显示 137/300），
     * 虽不会崩但 UI 会抖一下。改成：锁内改完 custom，再把这两个 volatile
     * 翻过来；界面线程只读 volatile，既不等锁、也不会读到中间态。
     */
    private volatile boolean customOn;
    private volatile int customCountSnap;

    private final ArrayList<String> page = new ArrayList<>();
    private int pageIdx;

    private final HashSet<String> usedEn = new HashSet<>();
    private final HashSet<String> usedZh = new HashSet<>();

    private int[] bands = {0, 0, 0, 0};
    private int stage = STAGE_HIGH;
    private int lastStage = STAGE_HIGH;
    private int offset;

    private int highMidTotal;      // 高频 + 中频一共多少词（里程碑判定）
    private int highMidOut;        // 已经取出来出过的
    private boolean milestoneSent;

    public GameWords(DictDb d, StudyDb s) {
        dict = d;
        study = s;
    }

    /** 后台线程调用：算好分档边界，把个人词和上次的进度都捞出来 */
    public void prepare() {
        int n = dict == null ? 0 : dict.cet6Count();
        bands = new int[]{0, n / 3, n * 2 / 3, n};
        highMidTotal = bands[2];          // 高频 + 中频 = 前三分之二
        loadPersonal();
        restore();
    }

    /** 恢复上次的进度：词序游标 + 没消完的牌面 */
    private void restore() {
        if (study == null) return;
        try {
            stage = study.getGameState("stage", STAGE_HIGH);
            offset = study.getGameState("offset", 0);
            highMidOut = study.getGameState("highMidOut", 0);
            if (stage < STAGE_HIGH || stage > STAGE_OTHER) {
                stage = STAGE_HIGH;
                offset = 0;
                highMidOut = 0;
            }
            resume.clear();
            for (String[] row : study.loadGamePending()) {
                String w = row[0];
                String zh = row.length > 1 && row[1] != null ? row[1].trim() : "";
                if (w == null || w.isEmpty() || zh.isEmpty()) continue;
                int lv = 0;
                try {
                    if (row.length > 2) lv = Integer.parseInt(row[2]);
                } catch (NumberFormatException ignore) { JLog.w("GameWords.restore", ignore);
                 }
                MatchGameView.Pair p = new MatchGameView.Pair(w, zh, lv);
                markUsed(p);            // 没消完的牌也登记，见 takeDueWrong 同款说明
                resume.add(p);
            }
        } catch (Throwable ignored) { JLog.w("GameWords.restore", ignored);
         }
    }

    /** 退出游戏 / 切后台：把词序游标和没消完的牌存下来 */
    public synchronized void saveProgress(List<MatchGameView.Pair> onScreen) {
        if (study == null) return;
        if (!custom.isEmpty()) return;      // 自定义牌面不写存档，免得混进词典那一局
        try {
            study.setGameState("stage", stage);
            study.setGameState("offset", offset);
            study.setGameState("highMidOut", highMidOut);
            ArrayList<String[]> rows = new ArrayList<>();
            ArrayList<MatchGameView.Pair> all = new ArrayList<>(resume);   // 还没铺出来的
            if (onScreen != null) all.addAll(0, onScreen);                  // 屏幕上没消的排前面
            for (MatchGameView.Pair p : all) {
                if (p == null) continue;
                rows.add(new String[]{p.en, p.zh, String.valueOf(p.level)});
            }
            study.saveGamePending(rows);
        } catch (Throwable ignored) { JLog.w("GameWords.saveProgress", ignored);
         }
    }

    private void loadPersonal() {
        if (study == null) return;
        ArrayList<String> list = new ArrayList<>();
        try {
            list.addAll(study.wrongWords());
            for (String w : study.favoriteWords()) {
                if (!list.contains(w)) list.add(w);
            }
        } catch (Throwable ignored) { JLog.w("GameWords.loadPersonal", ignored);
         }
        for (String w : list) {
            if (personal.size() >= PERSONAL_MAX) break;
            MatchGameView.Pair p = makePair(w);
            if (p != null) personal.add(p);
        }
    }

    /**
     * 取一屏需要的词对。个人词一屏最多挤进一个——不限它的顺序，
     * 但也不能让整屏全是自己的生词，那样和刷单词本没区别。
     */
    public synchronized ArrayList<MatchGameView.Pair> take(int want) {
        ArrayList<MatchGameView.Pair> out = new ArrayList<>();
        if (want <= 0 || dict == null) return out;
        // 神秘开关开着：整条词典管线（回炉错词 / 单词本 / 词频档）都不掺进来
        if (!custom.isEmpty()) return takeCustom(want);
        // 先把上次没消完的牌接上，再插个人词，最后才按词频取新词
        while (!resume.isEmpty() && out.size() < want) out.add(resume.remove(0));
        if (!personal.isEmpty() && out.size() < want) out.add(personal.remove(0));
        int guard = 0;
        while (out.size() < want && guard++ < 800) {
            if (!queue.isEmpty()) {
                out.add(queue.remove(0));
                continue;
            }
            if (!fillOne()) break;
        }
        return out;
    }

    // ================= 神秘开关：导入词出题（v3.15.1）=================

    /**
     * 换成玩家自己导入的词表出题。
     * rows 是 WordImport 解析出来的 {词, 释义}，释义为空就用词典里的第一个义项；
     * 词典也查不到、又没给释义的词收不进来（配对游戏没中文可配）。
     *
     * @return 真正能上牌的词数
     */
    public synchronized int useImport(ArrayList<String[]> rows) {
        ArrayList<MatchGameView.Pair> built = new ArrayList<>();
        customDeck.clear();
        if (rows != null) {
            HashSet<String> seen = new HashSet<>();
            HashSet<String> seenZh = new HashSet<>();
            for (String[] r : rows) {
                if (built.size() >= WordImport.MAX_WORDS) break;
                if (r == null || r.length < 1) continue;
                String w = r[0] == null ? "" : r[0].trim().toLowerCase(Locale.US);
                if (w.isEmpty() || w.length() > 30 || !seen.add(w)) continue;
                String zh = r.length > 1 && r[1] != null ? r[1].trim() : "";
                DictDb.Entry e = lookupOrNull(w);
                if (zh.isEmpty()) {
                    zh = firstMeaning(e);
                    if (zh.isEmpty()) continue;         // 哪儿都查不到：这块牌出不了
                }
                // 释义只留第一个义项，和 WordImport 同一口径：整条「放弃, 遗弃, 抛弃」装不下，
                // 而下面那道硬截断更危险——两条长释义会截成同一个前缀，屏幕上就是两块一模一样的牌。
                zh = WordImport.firstClause(zh);
                if (zh.length() > 16) zh = zh.substring(0, 16);
                // 中文也要去重：WordImport 只管贴进来的那些字，词典补的释义不归它管，
                // 而 abandon（贴的「放弃」）和 desert（查词典也是「放弃」）撞在一起，
                // 同一屏就会出现两张「放弃」——玩家没法配，看着就像出题重复了。
                if (zh.isEmpty() || !seenZh.add(zh)) continue;
                built.add(new MatchGameView.Pair(w, zh, levelOf(e)));
            }
        }
        if (built.isEmpty()) return 0;      // 一个都用不上：原来的牌面、存档一概不动
        custom.clear();
        custom.addAll(built);
        customOn = true;                    // 锁内改完再翻快照，界面线程读到的永远是整数
        customCountSnap = custom.size();
        queue.clear();
        resume.clear();
        // 导入模式不写牌面存档（见 saveProgress）：现在把残留清掉，
        // 切回正常词库时才不会把自定义词当成"上次没消完的牌"再铺一次。
        if (study != null) {
            try {
                study.saveGamePending(new ArrayList<String[]>());
            } catch (Throwable ignored) { JLog.w("GameWords.useImport", ignored);
             }
        }
        return custom.size();
    }

    /** 关掉神秘开关：回到词典出词，词序游标还在原位，接着上次往下出 */
    public synchronized void useDictionary() {
        custom.clear();
        customDeck.clear();
        customOn = false;
        customCountSnap = 0;
        queue.clear();
        resume.clear();
        personal.clear();
        loadPersonal();         // 「已出过」的登记没清，所以这里不会把出过的词再塞回来
    }

    /**
     * 现在是不是自定义词表出题。
     * 读的是 volatile 快照，不加锁：界面线程只读一个 boolean 判断，别为了这行代码去等 dbPool 上的 useImport。
     */
    public boolean isCustom() {
        return customOn;
    }

    /** 导入了多少个词（顶部小字用；同上，读 volatile 快照，不抢锁） */
    public int customCount() {
        return customCountSnap;
    }

    /**
     * 导入词的发牌：洗完一轮再洗下一轮，所以自定义模式不会"出完"。
     * 词堆不够铺满一屏（导入不足 5 对）时会把刚洗完的一轮接着摊开，
     * 但同一屏里绝不重复——同一屏出现两张一模一样的中文，玩家根本没法配，
     * 看着就像"出题重复了两组"（v3.15.1 反馈的那个）。
     */
    private ArrayList<MatchGameView.Pair> takeCustom(int want) {
        ArrayList<MatchGameView.Pair> out = new ArrayList<>();
        HashSet<String> thisWaveEn = new HashSet<>();
        HashSet<String> thisWaveZh = new HashSet<>();
        for (int spin = 0; out.size() < want && spin < 8; spin++) {
            while (out.size() < want && !customDeck.isEmpty()) {
                MatchGameView.Pair p = customDeck.remove(customDeck.size() - 1);
                if (p == null || p.en == null) continue;
                String en = p.en.trim().toLowerCase(Locale.US);
                String zh = p.zh == null ? "" : p.zh.trim();
                if (!thisWaveEn.add(en)) continue;
                if (!zh.isEmpty() && !thisWaveZh.add(zh)) continue;
                out.add(p);
            }
            if (out.size() < want && !custom.isEmpty()) {
                customDeck.addAll(custom);
                Collections.shuffle(customDeck);
            } else if (custom.isEmpty()) {
                break;
            }
        }
        return out;
    }

    /** 词典里的第一个义项；和 makePair 同一套清洗，剥不干净就当没有 */
    private String firstMeaning(DictDb.Entry e) {
        if (e == null || e.translation == null) return "";
        ArrayList<String> ms = Quiz.meanings(e.translation, 10);
        if (ms.isEmpty()) return "";
        String zh = ms.get(0).replace("\n", " ").trim();
        return zh.matches(".*[A-Za-z].*") ? "" : zh;   // 半截英文的义项拿来配对等于出错题
    }

    /** 组一对进队列；取不到新词了返回 false */
    private boolean fillOne() {
        for (int t = 0; t < 150; t++) {
            String w = nextWord();
            if (w == null) return false;
            MatchGameView.Pair p = makePair(w);
            if (p == null) continue;
            if (lastStage <= STAGE_MID) highMidOut++;   // 只数中高频真正出出去的词
            queue.add(p);
            return true;
        }
        return false;
    }

    /** 按档顺序吐词；这一页用完就取下一页，一页用完就换下一档 */
    private String nextWord() {
        while (true) {
            if (pageIdx < page.size()) {
                lastStage = stage;
                return page.get(pageIdx++);
            }
            if (!loadMore()) return null;
        }
    }

    private boolean loadMore() {
        page.clear();
        pageIdx = 0;
        while (stage < STAGE_DONE) {
            ArrayList<String> got;
            if (stage == STAGE_OTHER) {
                if (offset == 0) offset = OTHER_SKIP;   // 从实义词开始，别一上来就是 the / and
                got = dict.otherPage(offset, PAGE_SIZE);
            } else {
                int start = bands[stage], end = bands[stage + 1];
                int left = end - start - offset;
                if (left <= 0) {
                    stage++;
                    offset = 0;
                    continue;
                }
                got = dict.cet6Page(start + offset, Math.min(PAGE_SIZE, left));
            }
            offset += got.size();
            if (!got.isEmpty()) {
                page.addAll(got);
                return true;
            }
            stage++;
            offset = 0;
        }
        return false;
    }

    /** 词典查一次，查不到 / 没就绪就返回 null（调用方都在 dbPool 上，不用另开线程） */
    private DictDb.Entry lookupOrNull(String w) {
        if (dict == null || w == null) return null;
        try {
            return dict.lookup(w.trim().toLowerCase(Locale.US));
        } catch (Throwable t) {
            JLog.w("GameWords.lookupOrNull", t);
            return null;
        }
    }

    /**
     * 登记"这组词已经在牌面上了"。
     * 回炉错词和上次没消完的牌都不走 makePair，不登记的话 usedEn/usedZh 不知道它们存在，
     * 同一屏里同一个词就会被再抽一次 —— 就是"两组一模一样的词"那个 bug（石英/quartz 重复）。
     */
    private void markUsed(MatchGameView.Pair p) {
        if (p == null) return;
        if (p.en != null) usedEn.add(p.en.trim().toLowerCase(Locale.US));
        if (p.zh != null) usedZh.add(p.zh.trim());
    }

    /** 单词 → 可出题的词对；中文太长/太碎/重复的都不要 */
    private MatchGameView.Pair makePair(String w) {
        if (w == null) return null;
        String word = w.trim().toLowerCase(Locale.US);
        if (word.length() < 2 || word.length() > 15) return null;
        if (!usedEn.add(word)) return null;
        DictDb.Entry e = dict.lookup(word);
        if (e == null || e.translation == null || e.translation.isEmpty()) return null;
        ArrayList<String> ms = Quiz.meanings(e.translation, 10);
        if (ms.isEmpty()) return null;
        String zh = ms.get(0).replace("\n", " ").trim();
        if (zh.length() < 2 || zh.length() > 9) return null;
        if (zh.matches(".*[A-Za-z].*")) return null;    // 没剥干净的词性前缀/半截英文，别拿来配对
        if (!usedZh.add(zh)) return null;
        return new MatchGameView.Pair(word, zh, levelOf(e));
    }

    /**
     * 词级：1 = 高频（最常用 500 词），2 = 六级重点，0 = 普通。
     * 和查词页的标记同一口径 —— 高频标琥珀，六级标砖红。
     * 界限放在 500 而不是 3000：六级词表按词频排，前面的词几乎全在牛津 3000 里，
     * 界限放太高就会整屏一个颜色，看不出层次。
     */
    private static int levelOf(DictDb.Entry e) {
        if (e == null) return 0;
        if (e.frq > 0 && e.frq <= 500) return 1;
        if (e.tag != null) {
            for (String p : e.tag.trim().split("\\s+")) {
                if ("cet6".equals(p)) return 2;
            }
        }
        return 0;
    }

    /**
     * 每消完一屏、要铺下一屏时调用：先把到期的错词捞出来，再给剩下的倒计时 -1。
     * 顺序很重要 —— 先取后减，remain=3 才是"还要空过三屏"：
     * 错在第 N 屏 → 第 N+1 / N+2 / N+3 屏都取不到 → 第 N+4 屏出现（中间正好 15 对）。
     */
    public synchronized ArrayList<MatchGameView.Pair> onWaveStart(int max) {
        if (!custom.isEmpty()) return new ArrayList<>();   // 自定义模式：不掺回炉词，也不扣倒计时
        ArrayList<MatchGameView.Pair> out = takeDueWrong(max);
        if (study != null) {
            try {
                study.tickGameWrong();
            } catch (Throwable ignored) { JLog.w("GameWords.onWaveStart", ignored);
             }
        }
        return out;
    }

    /** 只取到期该回炉的错词，不动倒计时（进游戏铺第一屏时用，把上次没销号的先捞出来） */
    public synchronized ArrayList<MatchGameView.Pair> takeDueWrong(int max) {
        ArrayList<MatchGameView.Pair> out = new ArrayList<>();
        if (study == null) return out;
        if (!custom.isEmpty()) return out;      // 自定义模式：牌面只放玩家导入的词
        try {
            for (String[] row : study.dueGameWrong(max)) {
                String w = row[0];
                String zh = row[1] == null ? "" : row[1].trim();
                study.removeGameWrong(w);
                if (w == null || w.isEmpty() || zh.isEmpty()) continue;
                MatchGameView.Pair p = new MatchGameView.Pair(w, zh, levelOf(lookupOrNull(w)));
                // 回炉词也要登记，否则同一屏里 makePair 会把这个词当新词再抽一次
                // —— 就是"两组一模一样的词"（截图里 quartz/石英 各两块，带红条的是新抽的，
                //    不带条的是回炉的：存档只存词形，词级得回查一次词典才补得回来）。
                markUsed(p);
                out.add(p);
            }
        } catch (Throwable ignored) { JLog.w("GameWords.takeDueWrong", ignored);
         }
        return out;
    }

    /** 存档里还压着多少个错词（到期没到期的都算） */
    public int pendingWrongCount() {
        if (study == null) return 0;
        try {
            return study.countGameWrong();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 记一个错词：游戏内过三组（15 对）之后回来一次 */
    public synchronized void rememberWrong(MatchGameView.Pair p) {
        if (study == null || p == null) return;
        try {
            if (!custom.isEmpty()) {
                // 自定义模式：只有词典里真有的词才进错题集。
                // 玩家手输的词（词典查不到）进了错题集就是「点了看不到释义」那个老 bug 的翻版，
                // 回炉存档也一律不写——那套倒计时是给词典那一局排的。
                if (lookupOrNull(p.en) != null) study.wrong(p.en);
                return;
            }
            // 先记进「错题集」再管游戏回炉存档（#4）：
            // study.wrong() 会把 fav.wrong 计数 +1，于是这个词会出现在查词页的「错题集」
            // 里、并按 Leitner 盒子参与日常复习。放在存档容量判断之外，
            // 这样即使游戏回炉队列(WRONG_MAX)已满，用户在游戏里答错的词也照样进错题集。
            study.wrong(p.en);
            // 游戏回炉存档：队列满了就不收新的，但同一个词重复错仍然刷新倒计时
            if (study.countGameWrong() >= WRONG_MAX && !study.hasGameWrong(p.en)) return;
            study.saveGameWrong(p.en, p.zh, WRONG_BACK_WAVES);
        } catch (Throwable ignored) { JLog.w("GameWords.rememberWrong", ignored);
         }
    }

    /** 六级高频 + 中频是否都出现过一遍了 */
    public boolean highMidDone() {
        return highMidTotal > 0 && highMidOut >= highMidTotal;
    }

    /** 里程碑只报一次 */
    public boolean consumeMilestone() {
        if (!milestoneSent && !isCustom() && highMidDone()) {
            milestoneSent = true;
            return true;
        }
        return false;
    }

    /** 重新开始：清空进度，从六级高频再来一遍 */
    public synchronized void reset() {
        personal.clear();
        queue.clear();
        resume.clear();
        page.clear();
        pageIdx = 0;
        usedEn.clear();
        usedZh.clear();
        stage = STAGE_HIGH;
        offset = 0;
        highMidOut = 0;
        milestoneSent = false;
        if (study != null) {
            try {
                study.clearGameState();     // 整局重来：进度和残留牌面都清掉
            } catch (Throwable ignored) { JLog.w("GameWords.reset", ignored);
             }
        }
        loadPersonal();
    }
}

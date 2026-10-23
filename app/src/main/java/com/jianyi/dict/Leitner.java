package com.jianyi.dict;

/**
 * Leitner 五格盒的复习调度算法（v3.15 从 StudyDb 里拆出来）。
 *
 * 拆出来的理由只有一个：这套算法原来夹在 SQLite 事务中间，没法单独测。
 * "答错掉回第一格""最高档不再往上加"这类规则一旦改错，用户不会看到报错，
 * 只会觉得复习安排莫名其妙 —— 正是最该被单测锁住的那类逻辑。
 *
 * 规则：
 *   答对 → 升一格，间隔按 {@link #GAPS} 变长（1/2/4/8/16 天），到顶就停在顶格；
 *   答错 → 直接掉回第一格（明天再见），wrong/right 计数由 StudyDb 那边记。
 *
 * 这里全是纯计算：不碰数据库、不碰 Android 类型，可以在 JVM 上直接跑单测。
 */
public final class Leitner {

    private Leitner() {
    }

    static final long HOUR = 60L * 60 * 1000;
    static final long DAY = 24 * HOUR;

    /** 每格的复习间隔：盒子序号 → 答对之后隔多久再问 */
    static final long[] GAPS = {DAY, 2 * DAY, 4 * DAY, 8 * DAY, 16 * DAY};

    /** 顶格序号（答对也越不过这一格） */
    static final int LAST_BOX = GAPS.length - 1;

    /** 格子总数，界面上画进度用 */
    public static int boxCount() {
        return GAPS.length;
    }

    /**
     * 答完一题之后该落在哪一格。
     * 传进来的 box 可能是老库里的脏数据（负数、比表还大的值），一律夹回合法区间，
     * 不能让它把 GAPS 数组戳越界。
     */
    static int nextBox(int box, boolean ok) {
        if (!ok) return 0;
        return Math.min(clamp(box) + 1, LAST_BOX);
    }

    /** 这一格的复习间隔（毫秒） */
    static long delayFor(int box) {
        return GAPS[clamp(box)];
    }

    /** 这一格的下次到期时间：now + 间隔 */
    static long dueAt(int box, long now) {
        return now + delayFor(box);
    }

    private static int clamp(int box) {
        if (box < 0) return 0;
        return Math.min(box, LAST_BOX);
    }
}

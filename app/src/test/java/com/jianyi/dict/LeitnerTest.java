package com.jianyi.dict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Leitner 调度算法（纯计算）。
 * 这几条锁的是用户能直接感觉到的行为：答错要不要掉回明天、连对会不会一直加间隔、
 * 以及老库里被写坏的 box 会不会把数组戳越界。
 */
public class LeitnerTest {

    private static final long DAY = Leitner.DAY;

    @Test
    public void 答对升一格且间隔按档位翻倍() {
        assertEquals(1, Leitner.nextBox(0, true));
        assertEquals(2, Leitner.nextBox(1, true));
        assertEquals(3, Leitner.nextBox(2, true));
        assertEquals(4, Leitner.nextBox(3, true));
    }

    @Test
    public void 顶格不再往上加() {
        assertEquals(Leitner.LAST_BOX, Leitner.nextBox(Leitner.LAST_BOX, true));
        // 顶格的间隔就是最长那一档，答得再多也不会把复习越推越远
        assertEquals(16 * DAY, Leitner.delayFor(Leitner.LAST_BOX));
        assertEquals(Leitner.delayFor(Leitner.LAST_BOX), Leitner.delayFor(Leitner.LAST_BOX + 1));
    }

    @Test
    public void 答错掉回第一格() {
        for (int box = 0; box <= 8; box++) {
            assertEquals("box=" + box, 0, Leitner.nextBox(box, false));
        }
        assertEquals(DAY, Leitner.delayFor(0));
    }

    @Test
    public void 脏数据不能越界() {
        // 老库 / 手工改库都可能留下非法 box：不能抛 ArrayIndexOutOfBounds，
        // 一抛就是记背页当场崩。
        assertEquals("非法 box 当第一格处理", 1, Leitner.nextBox(-5, true));
        assertEquals("非法 box 答错也要归位", 0, Leitner.nextBox(-5, false));
        assertEquals(Leitner.LAST_BOX, Leitner.nextBox(9999, true));
        assertEquals(DAY, Leitner.delayFor(-5));
        assertEquals(16 * DAY, Leitner.delayFor(9999));
    }

    @Test
    public void 间隔单调变长() {
        for (int b = 0; b < Leitner.LAST_BOX; b++) {
            assertTrue("第 " + b + " 格应比第 " + (b + 1) + " 格短",
                    Leitner.delayFor(b) < Leitner.delayFor(b + 1));
        }
        assertEquals(5, Leitner.boxCount());
    }

    @Test
    public void 到期时间等于当前时间加间隔() {
        long now = 1_700_000_000_000L;
        for (int b = 0; b < Leitner.boxCount(); b++) {
            assertEquals(now + Leitner.delayFor(b), Leitner.dueAt(b, now));
        }
    }
}

package com.jianyi.dict;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 无尽配对模式：屏幕上散着「已经背过的词」的方块——英文一块、中文一块，
 * 点两块配成一对就化成散沙。一屏全部消完，整屏换一批新词、换一个新图案。
 *
 * 为什么自己画，不用 ViewGroup + 一堆 TextView：
 *  ① 方块要散乱旋转、选中放大、闪白、抖动、入场回弹，这些都在一次 draw 里算，
 *     比同时驱动十几个 View 的属性动画省内存也省代码；
 *  ② 散沙是上百个粒子，只能逐帧重绘。
 * 这台 8GB 的机器经不起花架子：粒子总量封顶，画面静止时整个重绘循环会停掉。
 *
 * v3.7 两处体验修正：
 *  · 不再「消一对补一对」——那样位置永远是老的，一眼就能记住格子，玩两屏就腻；
 *    现在一整屏消干净才重新铺，中间留 0.45 秒空白，有"清台"的爽感。
 *  · 摆法从固定两列改成六种图案随机轮换（错行 / 波浪 / 双斜线 / 椭圆环 / 散点 / 三列），
 *    每次铺牌随机参数 + 随机歪角，再跑一遍互相推挤的松弛，保证不重叠点得中。
 */
public class MatchGameView extends View {

    /** 一对待配对的内容：英文词 + 中文首义项 + 词级（0 普通 / 1 高频 / 2 六级重点） */
    public static class Pair {
        public final String en;
        public final String zh;
        public final int level;

        public Pair(String en, String zh) {
            this(en, zh, 0);
        }

        public Pair(String en, String zh, int level) {
            this.en = en;
            this.zh = zh;
            this.level = level;
        }
    }

    /** 分数变化回调（分数 / 连击 / 历史最高） */
    public interface Listener {
        void onScore(int score, int combo, int best);
    }

    // ===== 时间常量（秒）=====
    private static final float T_APPEAR = 0.26f;    // 入场回弹
    private static final float T_FLASH = 0.10f;     // 配对成功的闪白
    private static final float T_SHAKE = 0.30f;     // 配错的抖动
    private static final float T_POP = 0.16f;       // 点下去的按压回弹
    private static final float T_REFILL = 0.45f;    // 清屏后停顿多久再铺新牌

    // ===== 图案 =====
    private static final int MODE_STAGGER = 0;
    private static final int MODE_WAVE = 1;
    private static final int MODE_ZIGZAG = 2;
    private static final int MODE_RING = 3;
    private static final int MODE_SCATTER = 4;
    private static final int MODE_COLUMNS = 5;

    private static final int PAIRS_PER_WAVE = 5;    // 一屏 5 对 = 10 块

    /** 一个落点位：由图案算出来，方块按它摆 */
    private static class Slot {
        float cx, cy, w, h, rot;

        Slot(float cx, float cy, float w, float h, float rot) {
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.rot = rot;
        }
    }

    /** 一个词方块 */
    private static class Tile {
        Pair pair;
        boolean english;
        String text;
        float cx, cy;          // 中心
        float w, h;
        float rot;             // 随机小角度，制造散乱感
        float textSize;
        float appear;          // <0 未出现；0→1 弹入；1 停稳
        float scale = 1f;
        float press;           // 按压回弹剩余时间
        float shake;           // 抖动剩余时间
        float flash;           // 闪白剩余时间（归零即移除）
        boolean selected;
        boolean dying;
    }

    /** 散沙粒子 */
    private static class Grain {
        float x, y, vx, vy;
        float size, rot, vr, life, maxLife;
        int color;
        boolean square;
    }

    /** 飘字（+30 / 连击 ×3 / 清屏 +20） */
    private static class Fly {
        String text;
        float x, y, life, maxLife;
        int color;
        float size;
    }

    /** 清屏涟漪：从中心荡开的一圈细线 */
    private static class Ring {
        float life, maxLife, maxR;
    }

    private GameWords source;                    // 出词器（词典分档 + 个人词 + 错词存档）
    private int waveNo;                          // 已经铺到第几屏
    private Runnable onMilestone;                // 六级中高频刷完
    private Runnable onExhausted;                // 词典里能出的都出完了
    private Runnable onWaveChanged;              // 换了一屏（外面可以刷新"待回炉"数字）

    private final ArrayList<Tile> tiles = new ArrayList<>();
    private final ArrayList<Grain> grains = new ArrayList<>();
    private final ArrayList<Fly> flies = new ArrayList<>();
    private final ArrayList<Ring> rings = new ArrayList<>();

    private final Random rnd = new Random();

    private Tile selected;
    private int score, combo, best;
    private Listener listener;

    private float refillIn = -1f;      // >=0 表示正在等下一批上场
    private boolean waveDealt;         // 这一屏是否真的铺过牌（防止空屏被当成"清屏"）
    private boolean exhausted;         // 词典里能出的词都出完了
    private int lastMode = -1;         // 上一屏的图案，避免连着两次一样

    // 重绘循环
    private boolean looping;
    private long lastFrameNs;
    private float timeSec;

    // 画笔
    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tabPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF bar = new RectF();

    // 颜色（跟随日夜主题，attach 时刷新）
    private int cSurface, cStroke, cText, cTextSec, cAccent, cAccentSoft, cPeach, cMark, cCet6;

    public MatchGameView(Context c) {
        this(c, null);
    }

    public MatchGameView(Context c, android.util.AttributeSet a) {
        super(c, a);
        setClickable(true);
        tilePaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1));
        shadowPaint.setStyle(Paint.Style.FILL);
        grainPaint.setStyle(Paint.Style.FILL);
        tabPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2));
        flyPaint.setTextAlign(Paint.Align.CENTER);
        flyPaint.setTypeface(Typeface.DEFAULT_BOLD);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextSize(sp(13));
        refreshColors();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshColors();      // 换肤色后重建视图，这里拿到的是新主题
    }

    private void refreshColors() {
        cSurface = getContext().getColor(R.color.surface);
        cStroke = getContext().getColor(R.color.btn_stroke);
        cText = getContext().getColor(R.color.text_primary);
        cTextSec = getContext().getColor(R.color.text_secondary);
        cAccent = getContext().getColor(R.color.accent);
        cAccentSoft = getContext().getColor(R.color.accent_soft);
        cPeach = getContext().getColor(R.color.peach);
        cMark = getContext().getColor(R.color.mark_high);
        cCet6 = getContext().getColor(R.color.mark_cet6);
        hintPaint.setColor(cTextSec);
        ringPaint.setColor(cAccent);
        shadowPaint.setColor(0x14000000);
        invalidate();
    }

    // ================= 对外接口 =================

    public void setListener(Listener l) {
        listener = l;
    }

    /** 接上出词器并重开一局 */
    public void setSource(GameWords src) {
        source = src;
        startGame();
    }

    /** 六级高频+中频都出现过一遍时回调（只报一次） */
    public void setOnMilestone(Runnable r) {
        onMilestone = r;
    }

    /** 词典里能出的词都出完了 */
    public void setOnExhausted(Runnable r) {
        onExhausted = r;
    }

    /** 每换一屏回调一次（用来刷新顶部"待回炉"数量） */
    public void setOnWaveChanged(Runnable r) {
        onWaveChanged = r;
    }

    public void setBest(int b) {
        best = Math.max(0, b);
    }

    /**
     * 退出游戏时把进度落盘：词序游标由 GameWords 存，
     * 这里把"屏幕上还没消掉的牌"一并交过去，下次进来先铺这几对。
     */
    public void persistProgress() {
        if (source == null) return;
        ArrayList<Pair> left = new ArrayList<>();
        for (Tile t : tiles) {
            if (t.dying) continue;
            if (!left.contains(t.pair)) left.add(t.pair);
        }
        source.saveProgress(left);
    }

    /** 离开这一页时停掉重绘，别在后台空转 */
    public void stop() {
        looping = false;
        removeCallbacks(ticker);
        selected = null;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) stop();
        else if (!tiles.isEmpty() || refillIn >= 0) ensureLoop();
    }

    /** 退到后台（窗口不可见）也停掉重绘；回来时接着跑 */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            stop();
        } else if (!tiles.isEmpty() || refillIn >= 0) {
            lastFrameNs = System.nanoTime();
            ensureLoop();
        }
    }

    public int getScore() {
        return score;
    }

    public int getBest() {
        return best;
    }

    // ================= 开局 / 铺牌 =================

    private void startGame() {
        tiles.clear();
        grains.clear();
        flies.clear();
        rings.clear();
        selected = null;
        score = 0;                   // 分数不存档，每局从头算
        combo = 0;                   // 连击是当场表现，断了就断了
        waveNo = 0;
        refillIn = -1f;
        waveDealt = false;
        exhausted = false;
        lastMode = -1;
        newWave(false);          // 首屏不算"过了一屏"，不扣错词倒计时
        notifyScore();
        ensureLoop();
    }

    /**
     * 铺一整屏新牌：先算图案，再一对一对填词。
     * 词从 GameWords 按"高频→中频→低频→其他"来拿，每个词全局只出一次；
     * 之前答错的词会在过完三屏（15 对）之后插回来，最多占两个位置。
     *
     * @param advance true = 这是消完上一屏后的新一屏，要给错词倒计时；
     *                首次进入 / 旋屏重排都是 false，免得进出游戏白扣倒计时
     */
    private void newWave(boolean advance) {
        tiles.clear();
        selected = null;
        if (getWidth() <= 0 || getHeight() <= 0 || source == null) return;

        ArrayList<Slot> slots = makeWaveSlots();
        if (slots.size() < 4) return;

        waveNo++;
        ArrayList<Pair> picked = new ArrayList<>();
        if (advance) picked.addAll(source.onWaveStart(2));   // 过完一屏：倒计时 -1，到期错词先占位
        else picked.addAll(source.takeDueWrong(2));          // 刚进游戏：把上次到期没销号的先捞出来
        picked.addAll(source.take(PAIRS_PER_WAVE - picked.size()));
        if (picked.isEmpty()) {           // 词典里能出的都出完了
            waveDealt = false;
            exhausted = true;
            if (onExhausted != null) post(onExhausted);
            return;
        }
        exhausted = false;

        Collections.shuffle(slots, rnd);         // 哪个坑放英文哪块放中文也随机
        int n = Math.min(slots.size(), picked.size() * 2);
        for (int i = 0; i < n; i += 2) {
            Pair p = picked.get(i / 2);
            boolean enFirst = rnd.nextBoolean();
            // 从中心往外依次弹入，比整齐划一地一起出现有意思
            float delay = -0.035f * i;
            addTile(p, enFirst, slots.get(i), delay);
            addTile(p, !enFirst, slots.get(i + 1), delay - 0.04f);
        }
        waveDealt = !tiles.isEmpty();

        if (source.consumeMilestone() && onMilestone != null) post(onMilestone);
        if (onWaveChanged != null) post(onWaveChanged);
    }

    private void addTile(Pair p, boolean english, Slot s, float appearStart) {
        Tile t = new Tile();
        t.pair = p;
        t.english = english;
        t.text = english ? p.en : p.zh;
        t.cx = s.cx;
        t.cy = s.cy;
        t.w = s.w;
        t.h = s.h;
        t.rot = s.rot;
        t.appear = appearStart;
        fitText(t);
        tiles.add(t);
    }

    /** 记一个错词（存档在 study.db 里，退出游戏也不会丢） */
    private void rememberWrong(Pair p) {
        if (source != null) source.rememberWrong(p);
    }

    // ================= 图案 =================

    /**
     * 随机挑一种图案摆 10 块牌。
     * 图案本身可能算出重叠（斜线交叉、椭圆两腰），所以统一跑一遍 relax 让方块互相推开；
     * 推开后仍然压着就换一种再来，几次都不成才退回最稳的错行。
     */
    private ArrayList<Slot> makeWaveSlots() {
        int w = getWidth(), h = getHeight();
        int mode = rnd.nextInt(6);
        if (mode == lastMode && rnd.nextBoolean()) mode = rnd.nextInt(6);   // 别连着两屏一个样
        lastMode = mode;

        for (int attempt = 0; attempt < 5; attempt++) {
            ArrayList<Slot> s = buildByMode(attempt == 0 ? mode : rnd.nextInt(6), w, h);
            relax(s, w, h);
            if (!hasOverlap(s)) return s;
        }
        ArrayList<Slot> s = buildStagger(w, h);
        relax(s, w, h);
        return s;
    }

    private ArrayList<Slot> buildByMode(int mode, float w, float h) {
        switch (mode) {
            case MODE_WAVE:
                return buildWave(w, h);
            case MODE_ZIGZAG:
                return buildZigzag(w, h);
            case MODE_RING:
                return buildRing(w, h);
            case MODE_SCATTER:
                return buildScatter(w, h);
            case MODE_COLUMNS:
                return buildColumns(w, h);
            default:
                return buildStagger(w, h);
        }
    }

    /** 方块基准尺寸：一屏两列时不至于挤成一团 */
    private float baseW(float w) {
        return Math.min(dp(126), w * 0.34f);
    }

    private float baseH(float h) {
        return Math.min(dp(56), Math.max(dp(44), h * 0.095f));
    }

    private float jitterRot() {
        return (rnd.nextFloat() - 0.5f) * 9f;      // ±4.5°
    }

    /** 图案一：双列错行。奇数行整体横移，两列不再对齐成表格 */
    private ArrayList<Slot> buildStagger(float w, float h) {
        ArrayList<Slot> out = new ArrayList<>();
        float bw = baseW(w), bh = baseH(h);
        float shift = w * (0.03f + rnd.nextFloat() * 0.05f);
        for (int r = 0; r < PAIRS_PER_WAVE; r++) {
            float y = h * (0.10f + 0.80f * r / (PAIRS_PER_WAVE - 1f));
            float dir = (r % 2 == 0) ? -1f : 1f;
            out.add(new Slot(w * 0.24f + dir * shift, y + jitter(h * 0.012f), bw, bh, jitterRot()));
            out.add(new Slot(w * 0.76f + dir * shift, y + jitter(h * 0.012f), bw, bh, jitterRot()));
        }
        return out;
    }

    /** 图案二：两条竖列各自随正弦起伏，像两条水波 */
    private ArrayList<Slot> buildWave(float w, float h) {
        ArrayList<Slot> out = new ArrayList<>();
        float bw = baseW(w), bh = baseH(h);
        float amp = h * (0.025f + rnd.nextFloat() * 0.04f);
        float phase = rnd.nextFloat() * 6.28f;
        for (int col = 0; col < 2; col++) {
            float x = col == 0 ? w * 0.24f : w * 0.76f;
            for (int k = 0; k < PAIRS_PER_WAVE; k++) {
                float y = h * (0.12f + 0.76f * k / (PAIRS_PER_WAVE - 1f))
                        + (float) Math.sin(phase + k * 1.15f) * amp;
                out.add(new Slot(x + jitter(w * 0.012f), y, bw, bh,
                        (float) Math.cos(phase + k * 1.15f) * 4f));
            }
        }
        return out;
    }

    /** 图案三：梅花桩。3 / 2 / 3 / 2 一高一低交错，方块窄一档才排得下三列 */
    private ArrayList<Slot> buildZigzag(float w, float h) {
        ArrayList<Slot> out = new ArrayList<>();
        float bw = Math.min(dp(104), w * 0.28f), bh = baseH(h);
        boolean flip = rnd.nextBoolean();
        int[] cnt = {3, 2, 3, 2};
        float[] ys = {0.10f, 0.36f, 0.62f, 0.90f};
        for (int r = 0; r < cnt.length; r++) {
            for (int k = 0; k < cnt[r]; k++) {
                float x = (k + 0.5f) / cnt[r];
                if (flip) x = 1f - x;
                out.add(new Slot(w * x, h * ys[r] + jitter(h * 0.02f), bw, bh, jitterRot()));
            }
        }
        return out;
    }

    /** 图案四：椭圆环上 8 块 + 中心 2 块，方块沿切线歪一点 */
    private ArrayList<Slot> buildRing(float w, float h) {
        ArrayList<Slot> out = new ArrayList<>();
        float bw = Math.min(dp(118), w * 0.31f), bh = baseH(h);
        float rx = w * (0.30f + rnd.nextFloat() * 0.04f);
        float ry = h * (0.33f + rnd.nextFloat() * 0.03f);
        float off = rnd.nextFloat() * 6.28f;
        for (int i = 0; i < 8; i++) {
            double ang = off + i * Math.PI / 4;
            float x = w / 2f + (float) (Math.cos(ang) * rx);
            float y = h / 2f + (float) (Math.sin(ang) * ry);
            out.add(new Slot(x, y, bw, bh, (float) Math.toDegrees(ang) + 90f));
        }
        out.add(new Slot(w / 2f + jitter(w * 0.02f), h / 2f - h * 0.09f, bw, bh, jitterRot()));
        out.add(new Slot(w / 2f + jitter(w * 0.02f), h / 2f + h * 0.09f, bw, bh, jitterRot()));
        return out;
    }

    /**
     * 图案五：乱撒。两列只是防止挤成一堆的骨架，每块自己再各处乱漂，
     * 漂到哪算哪——纯随机采样常常凑不够 10 块，这个写法稳出满屏。
     */
    private ArrayList<Slot> buildScatter(float w, float h) {
        ArrayList<Slot> out = new ArrayList<>();
        float bw = baseW(w), bh = baseH(h);
        float colX0 = w * (0.22f + rnd.nextFloat() * 0.05f);
        float colX1 = w * (0.72f + rnd.nextFloat() * 0.06f);
        float jx = Math.max(dp(6), (colX1 - colX0 - bw) * 0.45f);
        float jy = Math.min(dp(16), h * 0.026f);
        for (int r = 0; r < PAIRS_PER_WAVE; r++) {
            float y = h * (0.10f + 0.80f * r / (PAIRS_PER_WAVE - 1f));
            out.add(new Slot(colX0 + jitter(jx * 2f), y + jitter(jy), bw, bh, jitterRot()));
            out.add(new Slot(colX1 + jitter(jx * 2f), y + jitter(jy), bw, bh, jitterRot()));
        }
        return out;
    }

    /** 图案六：三列疏密错落，方块相应做窄一点 */
    private ArrayList<Slot> buildColumns(float w, float h) {
        ArrayList<Slot> out = new ArrayList<>();
        float bw = Math.min(dp(104), w * 0.28f), bh = baseH(h);
        float[] frac = rnd.nextBoolean() ? new float[]{0.17f, 0.5f, 0.83f}
                : new float[]{0.22f, 0.56f, 0.84f};
        int[] perCol = {3, 4, 3};
        for (int c = 0; c < 3; c++) {
            int cnt = perCol[c];
            for (int k = 0; k < cnt; k++) {
                float y = h * (0.12f + 0.76f * k / Math.max(1, cnt - 1));
                out.add(new Slot(w * frac[c] + jitter(w * 0.015f),
                        y + jitter(h * 0.01f), bw, bh, jitterRot()));
            }
        }
        return out;
    }

    private float jitter(float amp) {
        return (rnd.nextFloat() - 0.5f) * amp;
    }

    // ---- 防重叠 ----

    /** 旋转后的保守半宽/半高，比真实包围盒略大，宁可留缝也别压着 */
    private float halfW(Slot s) {
        double r = Math.toRadians(s.rot);
        return (float) (Math.abs(s.w * Math.cos(r)) + Math.abs(s.h * Math.sin(r))) / 2f;
    }

    private float halfH(Slot s) {
        double r = Math.toRadians(s.rot);
        return (float) (Math.abs(s.w * Math.sin(r)) + Math.abs(s.h * Math.cos(r))) / 2f;
    }

    /** 互相推挤的松弛：重叠的沿重叠较小的那个轴各退一步，再夹回屏幕内 */
    private void relax(List<Slot> s, float w, float h) {
        if (s.size() < 2) return;
        float pad = dp(6);
        for (int iter = 0; iter < 40; iter++) {
            boolean moved = false;
            for (int i = 0; i < s.size(); i++) {
                for (int j = i + 1; j < s.size(); j++) {
                    Slot a = s.get(i), b = s.get(j);
                    float dx = b.cx - a.cx;
                    float dy = b.cy - a.cy;
                    float ox = (halfW(a) + halfW(b) + pad) - Math.abs(dx);
                    float oy = (halfH(a) + halfH(b) + pad) - Math.abs(dy);
                    if (ox <= 0 || oy <= 0) continue;
                    moved = true;
                    if (ox < oy) {
                        float push = (dx >= 0 ? 1f : -1f) * ox / 2f;
                        a.cx -= push;
                        b.cx += push;
                    } else {
                        float push = (dy >= 0 ? 1f : -1f) * oy / 2f;
                        a.cy -= push;
                        b.cy += push;
                    }
                }
            }
            // 别被推出屏幕
            for (Slot t : s) {
                float hx = halfW(t), hy = halfH(t);
                t.cx = Math.max(pad + hx, Math.min(w - pad - hx, t.cx));
                t.cy = Math.max(pad + hy, Math.min(h - pad - hy, t.cy));
            }
            if (!moved) break;
        }
    }

    private boolean hasOverlap(List<Slot> s) {
        float pad = dp(2);
        for (int i = 0; i < s.size(); i++) {
            for (int j = i + 1; j < s.size(); j++) {
                Slot a = s.get(i), b = s.get(j);
                if (Math.abs(b.cx - a.cx) < halfW(a) + halfW(b) - pad
                        && Math.abs(b.cy - a.cy) < halfH(a) + halfH(b) - pad) {
                    return true;
                }
            }
        }
        return false;
    }

    // ================= 尺寸与文字 =================

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0 || source == null) return;
        // 旋屏/改分屏：位置全按旧尺寸算的，直接重铺一屏最省事
        if (ow != w || oh != h) {
            newWave(false);      // 只是重排，不算过了一屏
            ensureLoop();
        }
    }

    /** 文字自动缩到装得下；实在太长就截断加省略号 */
    private void fitText(Tile t) {
        float maxW = t.w - dp(16);
        float max = t.english ? sp(17) : sp(15);
        float min = sp(9);
        textPaint.setTextSize(max);
        while (textPaint.measureText(t.text) > maxW && textPaint.getTextSize() > min) {
            textPaint.setTextSize(textPaint.getTextSize() - 0.5f);
        }
        if (textPaint.measureText(t.text) > maxW) {
            String s = t.text;
            while (s.length() > 1 && textPaint.measureText(s + "…") > maxW) {
                s = s.substring(0, s.length() - 1);
            }
            t.text = s + "…";
        }
        t.textSize = textPaint.getTextSize();
    }

    // ================= 触摸 =================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            Tile hit = hitTest(e.getX(), e.getY());
            if (hit != null) onTap(hit);
        }
        return true;      // 全部吃掉，别让外层把事件当成滚动
    }

    private Tile hitTest(float x, float y) {
        for (int i = tiles.size() - 1; i >= 0; i--) {
            Tile t = tiles.get(i);
            if (t.dying || t.appear < 0.75f) continue;
            rect.set(t.cx - t.w / 2f, t.cy - t.h / 2f, t.cx + t.w / 2f, t.cy + t.h / 2f);
            rect.inset(-dp(3), -dp(3));
            if (rect.contains(x, y)) return t;
        }
        return null;
    }

    private void onTap(Tile hit) {
        if (hit.dying || refillIn >= 0) return;
        if (selected == null) {
            selected = hit;
            hit.selected = true;
            hit.press = T_POP;
            ensureLoop();
            return;
        }
        if (selected == hit) {
            hit.selected = false;
            selected = null;
            hit.press = T_POP;
            ensureLoop();
            return;
        }
        Tile a = selected;
        selected = null;
        a.selected = false;
        hit.press = T_POP;

        if (a.pair == hit.pair) {
            a.flash = T_FLASH;
            hit.flash = T_FLASH;
            a.dying = true;
            hit.dying = true;
            burst(a);
            burst(hit);
            combo++;
            // 连击倍数：连对第 1 把 +10，第 2 把 +20 …… 封顶 ×5（+50）
            int gain = 10 * Math.min(5, combo);
            score += gain;
            if (score > best) best = score;
            flies.add(makeFly(a, combo >= 2 ? "+" + gain + "  连击 ×" + combo : "+" + gain,
                    cAccent, sp(15)));
            notifyScore();
        } else {
            a.shake = T_SHAKE;
            hit.shake = T_SHAKE;
            combo = 0;
            // 错一次抵掉一次基础配对；连击清零是更重的代价
            score = Math.max(0, score - 10);
            // 这两块都算"没认出来"：三屏之后各回来一次，帮玩家再认一遍
            rememberWrong(a.pair);
            rememberWrong(hit.pair);
            if (onWaveChanged != null) post(onWaveChanged);   // 让顶部"待回炉"数字立刻变
            notifyScore();
        }
        ensureLoop();
    }

    private Fly makeFly(Tile t, String text, int color, float size) {
        Fly f = new Fly();
        f.text = text;
        f.x = t.cx;
        f.y = t.cy - t.h * 0.3f;
        f.maxLife = 0.75f;
        f.life = f.maxLife;
        f.color = color;
        f.size = size;
        return f;
    }

    private void notifyScore() {
        if (listener != null) listener.onScore(score, combo, best);
    }

    // ================= 散沙 =================

    private void burst(Tile t) {
        int n = 26;
        int[] palette = {cAccent, cPeach, cMark, cTextSec, cAccentSoft};
        for (int i = 0; i < n; i++) {
            Grain g = new Grain();
            double ang = rnd.nextDouble() * Math.PI * 2;
            float speed = dp(60) + rnd.nextFloat() * dp(230);
            g.x = t.cx + (rnd.nextFloat() - 0.5f) * t.w * 0.8f;
            g.y = t.cy + (rnd.nextFloat() - 0.5f) * t.h * 0.7f;
            g.vx = (float) Math.cos(ang) * speed;
            g.vy = (float) Math.sin(ang) * speed - dp(70);
            g.size = dp(1.6f) + rnd.nextFloat() * dp(2.4f);
            g.rot = rnd.nextFloat() * 90f;
            g.vr = (rnd.nextFloat() - 0.5f) * 900f;
            g.maxLife = 0.45f + rnd.nextFloat() * 0.45f;
            g.life = g.maxLife;
            g.color = palette[rnd.nextInt(palette.length)];
            g.square = rnd.nextBoolean();
            grains.add(g);
        }
    }

    /** 清屏：中间荡开一圈涟漪 + 清屏奖励，给"扫干净了"一点回报 */
    private void celebrateClear() {
        Ring r = new Ring();
        r.maxLife = 0.55f;
        r.life = r.maxLife;
        r.maxR = Math.max(getWidth(), getHeight()) * 0.62f;
        rings.add(r);

        score += 20;
        if (score > best) best = score;
        Fly f = new Fly();
        f.text = "清屏 +20";
        f.x = getWidth() / 2f;
        f.y = getHeight() / 2f;
        f.maxLife = 0.9f;
        f.life = f.maxLife;
        f.color = cAccent;
        f.size = sp(18);
        flies.add(f);
        notifyScore();
    }

    // ================= 逐帧驱动 =================

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            step();
        }
    };

    private void ensureLoop() {
        if (looping) return;
        looping = true;
        lastFrameNs = System.nanoTime();
        postOnAnimation(ticker);
    }

    private void step() {
        long now = System.nanoTime();
        float dt = (now - lastFrameNs) / 1e9f;
        lastFrameNs = now;
        if (dt > 0.05f) dt = 0.05f;      // 卡一下也别让画面瞬移
        timeSec += dt;

        boolean busy = false;

        for (int i = tiles.size() - 1; i >= 0; i--) {
            Tile t = tiles.get(i);
            if (t.flash > 0) {
                t.flash -= dt;
                if (t.flash <= 0) {       // 闪完就撤，散沙已经飞出去了
                    tiles.remove(i);
                    continue;
                }
                busy = true;
            }
            if (t.appear < 1f) {
                t.appear += dt / T_APPEAR;
                if (t.appear > 1f) t.appear = 1f;
                busy = true;
            }
            float target = t.selected ? 1.06f : 1f;
            if (t.press > 0) {
                t.press -= dt;
                target = 0.94f;
                busy = true;
            }
            if (Math.abs(t.scale - target) > 0.002f) {
                t.scale += (target - t.scale) * Math.min(1f, dt * 16f);
                busy = true;
            }
            if (t.shake > 0) {
                t.shake -= dt;
                busy = true;
            }
        }

        // 整屏消完 → 歇一下 → 换图案换词重铺
        if (waveDealt && tiles.isEmpty() && refillIn < 0 && source != null) {
            waveDealt = false;
            refillIn = T_REFILL;
            celebrateClear();
        }
        if (refillIn >= 0) {
            refillIn -= dt;
            if (refillIn <= 0) {
                refillIn = -1;
                newWave(true);       // 消完一整屏 → 算过了一组，错词倒计时 -1
            }
            busy = true;
        }

        for (int i = grains.size() - 1; i >= 0; i--) {
            Grain g = grains.get(i);
            g.life -= dt;
            if (g.life <= 0) {
                grains.remove(i);
                continue;
            }
            g.vy += dp(700) * dt;         // 重力
            g.vx *= 0.985f;               // 空气阻力
            g.x += g.vx * dt;
            g.y += g.vy * dt;
            g.rot += g.vr * dt;
            busy = true;
        }

        for (int i = flies.size() - 1; i >= 0; i--) {
            Fly f = flies.get(i);
            f.life -= dt;
            if (f.life <= 0) {
                flies.remove(i);
                continue;
            }
            f.y -= dp(38) * dt;
            busy = true;
        }

        for (int i = rings.size() - 1; i >= 0; i--) {
            Ring r = rings.get(i);
            r.life -= dt;
            if (r.life <= 0) {
                rings.remove(i);
                continue;
            }
            busy = true;
        }

        invalidate();
        if (busy) {
            postOnAnimation(ticker);
        } else {
            looping = false;
        }
    }

    // ================= 绘制 =================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (Ring r : rings) {
            float k = 1f - r.life / r.maxLife;
            ringPaint.setAlpha((int) ((1f - k) * 110));
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r.maxR * k, ringPaint);
        }

        for (Tile t : tiles) drawTile(canvas, t);

        for (Grain g : grains) {
            float a = Math.min(1f, g.life / g.maxLife * 1.6f);
            grainPaint.setColor(g.color);
            grainPaint.setAlpha((int) (a * 235));
            if (g.square) {
                canvas.save();
                canvas.rotate(g.rot, g.x, g.y);
                canvas.drawRect(g.x - g.size / 2f, g.y - g.size / 2f,
                        g.x + g.size / 2f, g.y + g.size / 2f, grainPaint);
                canvas.restore();
            } else {
                canvas.drawCircle(g.x, g.y, g.size * 0.45f, grainPaint);
            }
        }

        for (Fly f : flies) {
            float a = Math.min(1f, f.life / f.maxLife * 1.4f);
            flyPaint.setColor(f.color);
            flyPaint.setAlpha((int) (a * 255));
            flyPaint.setTextSize(f.size);
            canvas.drawText(f.text, f.x, f.y, flyPaint);
        }

        if (exhausted) {
            canvas.drawText("这本词典里能出的词都出完了", getWidth() / 2f,
                    getHeight() / 2f - dp(10), hintPaint);
            canvas.drawText("点左上角返回，再进来就是新的一轮", getWidth() / 2f,
                    getHeight() / 2f + dp(14), hintPaint);
        }
    }

    private void drawTile(Canvas canvas, Tile t) {
        if (t.appear <= 0f) return;                  // 还没到出场时间

        float a = Math.min(1f, t.appear);
        float pop = 0.62f + 0.38f * easeOutBack(a);  // 弹入：先冲过头一点再落回
        float s = t.scale * pop;

        float sx = 0f;
        if (t.shake > 0) {
            float k = t.shake / T_SHAKE;
            sx = (float) Math.sin(timeSec * 70) * dp(6) * k;
        }

        canvas.save();
        canvas.translate(t.cx + sx, t.cy);
        canvas.rotate(t.rot);
        canvas.scale(s, s);

        float hw = t.w / 2f, hh = t.h / 2f;
        float radius = dp(13);
        rect.set(-hw, -hh, hw, hh);

        // 底影：比卡片低 2dp，别用 blur，弱机上软阴影太贵
        rect.offset(0, dp(2));
        shadowPaint.setAlpha((int) (a * 26));
        canvas.drawRoundRect(rect, radius, radius, shadowPaint);
        rect.offset(0, -dp(2));

        // 卡面
        tilePaint.setColor(t.selected ? cAccentSoft : cSurface);
        tilePaint.setAlpha((int) (a * 255));
        canvas.drawRoundRect(rect, radius, radius, tilePaint);

        // 描边：选中时换成主题色并加粗
        strokePaint.setColor(t.shake > 0 ? cMark : (t.selected ? cAccent : cStroke));
        strokePaint.setStrokeWidth(t.selected ? dp(2) : dp(1));
        strokePaint.setAlpha((int) (a * 255));
        canvas.drawRoundRect(rect, radius, radius, strokePaint);

        // 词级色条：左边竖一小条，高频=琥珀、六级重点=砖红，和查词页的标记同色
        if (t.pair != null && t.pair.level > 0) {
            tabPaint.setColor(t.pair.level == 1 ? cMark : cCet6);
            tabPaint.setAlpha((int) (a * 235));
            float tw = dp(3.5f);
            float bh2 = t.h * 0.52f;
            bar.set(-hw + dp(8), -bh2 / 2f, -hw + dp(8) + tw, bh2 / 2f);
            canvas.drawRoundRect(bar, tw / 2f, tw / 2f, tabPaint);
        }

        // 文字
        textPaint.setTypeface(t.english ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        textPaint.setColor(cText);
        textPaint.setAlpha((int) (a * 255));
        textPaint.setTextSize(t.textSize);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float ty = -(fm.ascent + fm.descent) / 2f;
        canvas.drawText(t.text, -textPaint.measureText(t.text) / 2f, ty, textPaint);

        // 配对成功的闪白
        if (t.flash > 0) {
            tilePaint.setColor(Color.WHITE);
            tilePaint.setAlpha((int) (t.flash / T_FLASH * 210));
            canvas.drawRoundRect(rect, radius, radius, tilePaint);
        }

        canvas.restore();
    }

    /** 回弹缓动：末尾稍微冲过 1 再回来，方块才有"啪"地落下的手感 */
    private static float easeOutBack(float x) {
        if (x >= 1f) return 1f;
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float y = x - 1f;
        return 1f + c3 * y * y * y + c1 * y * y;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}

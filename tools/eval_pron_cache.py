# -*- coding: utf-8 -*-
"""评估「全部单词语音缓存」的占用：实测每个词的音频大小，再按词典真实词条量推算。"""
import asyncio
import os
import random
import sqlite3
import time
import tempfile

import edge_tts

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


DB = WS + r"\logs\dict_test.db"
VOICE = "en-US-AriaNeural"

# ---------- 1. 词典里到底有多少条 ----------
con = sqlite3.connect(DB)


def count(where):
    return con.execute("SELECT COUNT(*) FROM stardict WHERE " + where).fetchone()[0]


total = con.execute("SELECT COUNT(*) FROM stardict").fetchone()[0]
pure_word = count(
    "word NOT LIKE '% %' AND word NOT LIKE '%-%' AND word NOT LIKE \"%'%\" "
    "AND word NOT GLOB '*[^a-z]*'"
)
pure_with_frq = count(
    "word NOT LIKE '% %' AND word NOT LIKE '%-%' AND word NOT LIKE \"%'%\" "
    "AND word NOT GLOB '*[^a-z]*' AND frq > 0"
)
with_frq = count("frq > 0")

print("=== 一、词条规模 ===")
print("  词条总数            : %s" % f"{total:,}")
print("  其中「纯单词」(不含短语/连字符/撇号/数字): %s" % f"{pure_word:,}")
print("  有词频数据的词条    : %s" % f"{with_frq:,}")
print("  有词频的纯单词      : %s" % f"{pure_with_frq:,}")

# ---------- 2. 按词长分层抽样，实测音频大小 ----------
random.seed(7)
buckets = [(1, 4), (5, 7), (8, 10), (11, 99)]
sample = []
for lo, hi in buckets:
    rows = con.execute(
        "SELECT word FROM stardict WHERE frq > 0 "
        "AND word NOT LIKE '% %' AND word NOT LIKE '%-%' AND word NOT LIKE \"%'%\" "
        "AND word NOT GLOB '*[^a-z]*' AND length(word) BETWEEN ? AND ? "
        "ORDER BY frq LIMIT 400",
        (lo, hi),
    ).fetchall()
    pool = [r[0] for r in rows]
    sample += random.sample(pool, min(8, len(pool)))

print("\n=== 二、实测样本（%d 个词，音色 %s）===" % (len(sample), VOICE))
out_dir = tempfile.mkdtemp(prefix="pron_eval_")


async def synth_all():
    sizes = {}
    t0 = time.time()
    for w in sample:
        p = os.path.join(out_dir, w + ".mp3")
        c = edge_tts.Communicate(w, VOICE)
        await c.save(p)
        sizes[w] = os.path.getsize(p)
    return sizes, time.time() - t0


sizes, elapsed = asyncio.run(synth_all())
vals = list(sizes.values())
avg = sum(vals) / len(vals)
per_word_time = elapsed / len(sample)

for w in sorted(sizes, key=lambda x: len(x)):
    print("   %-16s %2d 字母  %6d 字节" % (w, len(w), sizes[w]))
print("  平均: %.0f 字节/词   实测单线程: %.2f 秒/词" % (avg, per_word_time))


# ---------- 3. 推算 ----------
def human(n):
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return "%.1f %s" % (n, unit)
        n /= 1024.0
    return "%.1f PB" % n


def human_h(sec):
    if sec < 60:
        return "%.0f 秒" % sec
    if sec < 3600:
        return "%.1f 分钟" % (sec / 60)
    if sec < 86400:
        return "%.1f 小时" % (sec / 3600)
    return "%.1f 天" % (sec / 86400)


print("\n=== 三、推算（按 %.0f 字节/词，单音色）===" % avg)
scales = [
    ("500 词（已实现的档位）", 500),
    ("2,000 词", 2000),
    ("10,000 词", 10000),
    ("50,000 词", 50000),
    ("有词频的纯单词", pure_with_frq),
    ("词典全部词条", total),
]
print("   %-22s %-12s %-12s %-14s %s" % ("规模", "单音色", "四音色", "单线程耗时", "4 并发耗时"))
for name, n in scales:
    one = avg * n
    four = one * 4
    t1 = per_word_time * n
    t4 = t1 / 4
    print("   %-22s %-12s %-12s %-14s %s"
          % (name, human(one), human(four), human_h(t1), human_h(t4)))

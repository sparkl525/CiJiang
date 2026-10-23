# -*- coding: utf-8 -*-
"""从 ECDICT 完整数据库生成精简版 dict.db（打进 APK 的内置词典）。

筛选原则：只保留「对英语学习者有用」的词条 ——
  1. 必须有中文释义
  2. 满足任一条件：有语料库词频(frq/bnc) / 柯林斯星级 / 牛津3000 / 考试标签 / 短语
  3. 排除人名地名等专有名词和乱码条目
"""
import os
import re
import sqlite3
import sys

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 词桨工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


SRC = WS + r"\ecdict\stardict.db"
DST = PROJ + r"\app\src\main\assets\db\dict.db"  # 构建后 gzip 为 dict.dbz（扩展名不能用 .gz，aapt2 会解压）

os.makedirs(os.path.dirname(DST), exist_ok=True)
if os.path.exists(DST):
    os.remove(DST)

src = sqlite3.connect(SRC)
dst = sqlite3.connect(DST)
dst.execute("PRAGMA journal_mode=OFF")
dst.execute("PRAGMA synchronous=OFF")
dst.execute("""
CREATE TABLE stardict(
    word TEXT PRIMARY KEY,
    phonetic TEXT,
    translation TEXT,
    definition TEXT,
    tag TEXT,
    collins INTEGER,
    oxford INTEGER,
    frq INTEGER,
    exchange TEXT
)""")

WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'\- ]*$")

# 分批处理，避免低配机器内存吃紧
total_in = total_out = 0
BATCH = 200000
cur = src.cursor()
cur.execute("SELECT COUNT(*) FROM stardict")
grand = cur.fetchone()[0]
print(f"source rows: {grand}", flush=True)

last = ""
while True:
    cur.execute(
        "SELECT word,phonetic,translation,definition,tag,collins,oxford,frq,bnc,exchange "
        "FROM stardict WHERE word > ? ORDER BY word LIMIT ?",
        (last, BATCH))
    rows = cur.fetchall()
    if not rows:
        break
    ins = []
    for (w, ph, tr, de, tag, col, oxf, frq, bnc, ex) in rows:
        last = w
        total_in += 1
        w = w.strip()
        if not w or not WORD_RE.match(w):
            continue
        tr = (tr or "").strip()
        if not tr:
            continue
        # 学习价值判断，三档准入：
        #  A. 有信号（词频/星级/标签）的核心词 —— 全收
        #  B. 带英文释义的正式词条 —— 全收
        #  C. 无释义但带词形变化的屈折形式（going/books/better）—— 方便变形查询
        signal = (frq and frq > 0) or (bnc and bnc > 0) or (col and col > 0) \
                 or (oxf and oxf > 0) or (tag and tag.strip())
        if " " in w:
            if not (signal and len(w) <= 50):
                continue
        else:
            has_def = bool(de and de.strip())
            has_ex = bool(ex and ex.strip())
            if signal:
                pass
            elif has_def and len(w) <= 15:
                pass
            elif (not has_def) and has_ex and len(w) <= 20:
                pass
            else:
                continue
        # 单字母或超长词条意义不大（短语除外）
        if " " not in w and (len(w) > 30):
            continue
        ins.append((w.lower(), ph, tr, de, (tag or "").strip(),
                    int(col or 0), int(oxf or 0), int(frq or 0), ex))
    dst.executemany(
        "INSERT OR IGNORE INTO stardict VALUES(?,?,?,?,?,?,?,?,?)", ins)
    total_out += len(ins)
    print(f"  processed {total_in}/{grand}, kept {total_out}", flush=True)

dst.commit()

# ─── 索引：App 侧 DictDb 的查询形状依赖这两条，别删 ───
# 建库时只声明了 word 主键，其余列全靠全表扫 + 临时 B 树排序。桌面实测（355,240 行）：
#   背词分页 cet6Page 151ms -> 0.5ms，topWords 41ms -> 1ms，中文反查 86ms -> 10ms。
# idx_cet6 是部分索引：只把六级词放进索引，普通查询用 idx_frq，两边都不必为对方付费。
# 部分索引的 WHERE 必须和 DictDb.CET6_WHERE 开头那段逐字一致，规划器证明不了等价就不会用它。
dst.execute("CREATE INDEX idx_frq ON stardict(frq)")
dst.execute("CREATE INDEX idx_cet6 ON stardict(frq, word) WHERE "
            "(' '||ifnull(tag,'')||' ') LIKE '% cet6 %'")
dst.commit()

dst.execute("VACUUM")
dst.commit()

n = dst.execute("SELECT COUNT(*) FROM stardict").fetchone()[0]
size_mb = os.path.getsize(DST) / 1048576
print(f"DONE: {n} entries, {size_mb:.1f} MB", flush=True)
src.close()
dst.close()

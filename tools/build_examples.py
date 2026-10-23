# -*- coding: utf-8 -*-
"""给词典高频词配中英对照例句（Tatoeba 语料，CC-BY 协议，可自由分发）。

流程：
  1. 解压现役 dict.dbz 得到 dict.db
  2. 取词频最高的 N 个纯单词作为目标词表，并用 exchange 字段建立「变形 → 原形」映射
  3. 读 Tatoeba 中英句对，过滤出短小干净的句子
  4. 句子分词 → 命中目标词（原形优先于变形）→ 每词留最短的 K 条
  5. 写 examples 表，重新 gzip 为 dict.dbz（原文件先备份）

用法：python build_examples.py [目标词数] [每词例句数]
"""
import bz2
import gzip
import os
import re
import sqlite3
import sys
import time

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 词桨工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


try:
    import zhconv as ZH2CN
except ImportError:                      # 没装繁转简库就原样输出，不阻断流程
    ZH2CN = None

ROOT = WS
TATOEBA = os.path.join(ROOT, "tatoeba")
DBZ = PROJ + r"\app\src\main\assets\db\dict.dbz"
WORK_DB = os.path.join(ROOT, "tatoeba", "dict_work.db")

TOP_N = int(sys.argv[1]) if len(sys.argv) > 1 else 20000
PER_WORD = int(sys.argv[2]) if len(sys.argv) > 2 else 2
LEN_MAX = int(sys.argv[3]) if len(sys.argv) > 3 else 15

ENG = os.path.join(TATOEBA, "eng_sentences.tsv.bz2")
CMN = os.path.join(TATOEBA, "cmn_sentences.tsv.bz2")
LINKS = os.path.join(TATOEBA, "cmn-eng_links.tsv.bz2")

WORD_RE = re.compile(r"[a-z][a-z']*")
BAD_EN = re.compile(r"(https?://|www\.|@|<|>|&|\\|\||\d{3,})")

t0 = time.time()


def log(msg):
    print("[%6.1fs] %s" % (time.time() - t0, msg), flush=True)


def mb(n):
    return n / (1024 * 1024)


# ---------- 1. 准备工作库 ----------
log("解压现役词典 %s (%.1f MB)..." % (os.path.basename(DBZ), mb(os.path.getsize(DBZ))))
if os.path.exists(WORK_DB):
    os.remove(WORK_DB)
with gzip.open(DBZ, "rb") as f, open(WORK_DB, "wb") as o:
    while True:
        b = f.read(1 << 20)
        if not b:
            break
        o.write(b)
log("工作库就绪 %.1f MB" % mb(os.path.getsize(WORK_DB)))

db = sqlite3.connect(WORK_DB)
db.execute("PRAGMA journal_mode=OFF")
db.execute("PRAGMA synchronous=OFF")

# ---------- 2. 目标词表 + 变形映射 ----------
log("读取目标词表（frq 最高的 %d 个纯单词）..." % TOP_N)
targets = {}
alias = {}          # 变形 -> 原形
for word, frq, exchange in db.execute(
        "SELECT word, frq, exchange FROM stardict WHERE frq > 0 "
        "AND word NOT LIKE '% %' AND word NOT LIKE '%-%' AND word NOT LIKE \"%'%\" "
        "AND word NOT GLOB '*[^a-z]*' ORDER BY frq LIMIT ?", (TOP_N,)):
    targets[word] = frq
    if exchange:
        for part in exchange.split("/"):
            i = part.find(":")
            if i > 0:
                form = part[i + 1:].strip().lower()
                if form and form != word:
                    alias.setdefault(form, word)
log("目标词 %d 个，变形映射 %d 条" % (len(targets), len(alias)))

# ---------- 3. 句对 ----------
log("读取中英对照索引...")
pairs = []
with bz2.open(LINKS, "rt", encoding="utf-8", errors="replace") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) >= 2:
            pairs.append((p[0], p[1]))          # (中文句 id, 英文句 id)
log("句对 %d 组" % len(pairs))
need_cmn = set(x for x, y in pairs)
need_en = set(y for x, y in pairs)

log("读取英文句（只取句对涉及的部分）...")
en_map = {}
with bz2.open(ENG, "rt", encoding="utf-8", errors="replace") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3 and p[0] in need_en:
            en_map[p[0]] = p[2]
log("英文句 %d 条" % len(en_map))

log("读取中文句...")
cmn_map = {}
with bz2.open(CMN, "rt", encoding="utf-8", errors="replace") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3 and p[0] in need_cmn:
            cmn_map[p[0]] = p[2]
log("中文句 %d 条" % len(cmn_map))

# ---------- 4. 过滤 + 匹配 ----------
KEEP = 20           # 每词先攒够这么多候选，最后按长度取前 K 条
cand = {}
used = 0
for cmn_id, en_id in pairs:
    en = en_map.get(en_id)
    zh = cmn_map.get(cmn_id)
    if not en or not zh:
        continue
    n = len(en.split())
    if n < 3 or n > LEN_MAX:
        continue
    if len(en) > 60 + LEN_MAX * 4 or len(zh) > 60:
        continue
    if BAD_EN.search(en):
        continue
    if not re.search(r"[一-鿿]", zh):
        continue
    if ZH2CN:
        zh = ZH2CN.convert(zh, "zh-cn")     # Tatoeba 里简繁混排，统一转简体
    used += 1
    toks = WORD_RE.findall(en.lower())
    for t in toks:
        w = t
        exact = w in targets
        if not exact:
            w = alias.get(t)
            if not w:
                continue
        lst = cand.get(w)
        if lst is None:
            cand[w] = [(0 if exact else 1, n, en, zh)]
            continue
        item = (0 if exact else 1, n, en, zh)
        if len(lst) < KEEP:
            lst.append(item)
        elif item < max(lst):
            lst[lst.index(max(lst))] = item
log("可用句对 %d 组，命中 %d 个词" % (used, len(cand)))

# ---------- 5. 写库 ----------
log("写入 examples 表...")
db.execute("DROP TABLE IF EXISTS examples")
db.execute("CREATE TABLE examples(word TEXT PRIMARY KEY, en1 TEXT, zh1 TEXT, en2 TEXT, zh2 TEXT)")
rows = []
for w, lst in cand.items():
    if w not in targets:
        continue
    lst.sort()
    picked = []
    sets = []            # 已选句子的词集合，用来拦同句式重复（如 He's my friend / He's your friend）
    seen = set()
    similar_pool = []    # 因雷同被拦下但可作为兜底的候选
    for _, _, en, zh in lst:
        if en in seen:
            continue
        seen.add(en)
        toks = set(WORD_RE.findall(en.lower()))
        similar = False
        for s in sets:
            inter = len(toks & s)
            if inter >= 2 and inter >= 0.6 * min(len(toks), len(s)):
                similar = True
                break
        if similar:
            if len(similar_pool) < PER_WORD:
                similar_pool.append((en, zh))
            continue
        sets.append(toks)
        picked.append((en, zh))
        if len(picked) == PER_WORD:
            break
    # 去重后不够条数时，用雷同句补齐（有例句总比没有强）
    for item in similar_pool:
        if len(picked) >= PER_WORD:
            break
        picked.append(item)
    if len(picked) < PER_WORD:
        picked += [("", "")] * (PER_WORD - len(picked))
    rows.append((w, picked[0][0], picked[0][1], picked[1][0], picked[1][1]))
db.executemany("INSERT INTO examples VALUES(?,?,?,?,?)", rows)
db.commit()
log("例句词条 %d 条" % len(rows))

db.execute("VACUUM")
db.close()
log("库大小 %.1f MB" % mb(os.path.getsize(WORK_DB)))

# ---------- 6. 回写 dict.dbz ----------
# 备份必须放在 assets 目录之外：assets 下的文件会被原样打进 APK，留一个 .bak 会让包体翻倍
BAK_DIR = os.path.join(ROOT, "backup")
os.makedirs(BAK_DIR, exist_ok=True)
bak = os.path.join(BAK_DIR, "dict.dbz-no-examples.bak")
if not os.path.exists(bak):
    import shutil
    shutil.copyfile(DBZ, bak)
with open(WORK_DB, "rb") as f, gzip.GzipFile(DBZ, "wb", compresslevel=9, mtime=0) as o:
    while True:
        b = f.read(1 << 20)
        if not b:
            break
        o.write(b)
log("新 dict.dbz %.2f MB（旧版已备份为 dict.dbz.bak）" % mb(os.path.getsize(DBZ)))
log("完成")

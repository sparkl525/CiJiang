# -*- coding: utf-8 -*-
"""给词典加「常用搭配」表（phrases），支撑词组训练。

背景：ECDICT 的 stardict.db 里，短语是独立词条（word 里带空格），
但没有专门的搭配字段（detail 字段基本是空的），也没有 frq 标记。
所以要自己筛出「学习者值得记的固定搭配」。

筛选思路（几轮实测调出来的，纯结构特征，不靠人工词表）：
  ① 结构：短语的第二个词是小品词/介词（up/out/off/on/into/over/back/with/for…）
     —— 这类「动词+小品词」和「动词+介词」是中式英语最容易踩坑的地方，
        记下来的收益远高于名词+名词的术语组合。
  ② headword 常用度：短语的第一个词必须是学习者认识的常用词
     （frq 靠前 或 柯林斯 3 星以上 或 牛津 3000），
     否则会出现 abacus computation（珠算）、short udder（短乳房）这种没人学的搭配。
  ③ 释义干净：剥掉括号里的专业标注和词性前缀，取首个义项，长度达标。
     只取首个义项很关键 —— ECDICT 的释义是十几个逗号堆在一起
     （give up = "放弃, 停止, 献出, 抛弃, 认输, 把...送交"），
     按整条长度筛会把 give up 这种核心搭配误杀。

产出：phrases(head, phrase, zh, score) 四列，约 1.3 万条、2000+ 个 headword。
score 是展示优先级（查词页只取前几条，按它倒序）。
用法：python build_phrases.py
"""
import gzip
import os
import re
import shutil
import sqlite3
import sys
import time

# 核心搭配的人工校订（最高频那一批，释义取最常用的那个义项）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    from phrase_overrides import PHRASE_OVERRIDES
except Exception:            # 找不到就用空表，不阻断流程
    PHRASE_OVERRIDES = {}

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
# 本脚本放在 D:\android-dev\tools（工作区侧），工程在 D:\android-dev\JianYi。
# 仓库内还有一份快照在 JianYi\tools\build_dict.py，那份的 PROJ 推导方式不同，两边别混。
WS = os.path.dirname(_TOOLS)                    # 开发工作区 D:\android-dev
PROJ = os.path.join(WS, "JianYi")               # 词桨工程根目录
BAK_DIR = os.path.join(WS, "backups")

DBZ = PROJ + r"\app\src\main\assets\db\dict.dbz"
WORK_DB = os.path.join(WS, "ecdict", "dict_phrases_work.db")
SRC = os.path.join(WS, "ecdict", "stardict.db")

t0 = time.time()


def log(msg):
    print("[%6.1fs] %s" % (time.time() - t0, msg), flush=True)


def mb(n):
    return n / (1024 * 1024)


# ---------- 1. 筛选参数 ----------
# ② 小品词/介词表：短语的第二个词落在这些里
PARTICLES = [
    "up", "out", "down", "off", "on", "in", "into", "over", "through",
    "back", "along", "around", "about", "after", "away", "apart", "aside",
    "forward", "together", "upon", "with", "for", "to",
]
# ③ 释义里不要出现的专业标注（这些多半是术语/网络用语，不适合当学习搭配）
BAD_TAG_PREFIX = ("[地名]", "[人名]", "[缩写]", "[网络]", "[计]", "[经]",
                   "[医]", "[法]", "[建]", "[化]", "[美]", "[英]", "[德]", "[日]")

MAX_ZH_LEN = 16       # 中文释义最长多少字（清洗后）
MAX_PHRASE_LEN = 18   # 短语最长多少字符
HEAD_FREQ_MAX = 4000  # headword 词频上限（越小越常用）

# 词性前缀（ECDICT 释义里常见），提取义项时剥掉
POS_RE = re.compile(r"^(?:n|v|vt|vi|vn|adj|adv|a|prep|conj|pron|num|art|int|aux|abbr|pl|pref|suff)\s*\.")
# 括号及其内容（含全角括号），专业标注一律剥掉
BRACKET_RE = re.compile(r"[（(【\[].*?[)）\]】]")


def extract_meaning(zh):
    """从 ECDICT 的整条释义里提取「首个义项」。

    ECDICT 的 translation 形如 "放弃, 停止, 献出, 抛弃, 认输, 把...送交"，
    十几个逗号分隔的义项堆在一起。这里只取第一个 —— 它是最主要的用法，
    短、准，而且正好绕开了"释义太长把好搭配误杀"的问题
    （give up 的释义有 21 字，砍长度会连它一起丢掉，那是核心搭配）。
    """
    if not zh:
        return ""
    s = zh.strip()
    s = s.split("\n")[0]              # 只取首行
    s = BRACKET_RE.sub("", s)        # 剥掉 (牛等) [法] 这类标注
    s = POS_RE.sub("", s).strip()    # 剥掉词性前缀
    for seg in re.split(r"[,，、;；]", s):
        t = seg.strip()
        if t:
            return t
    return ""


def zh_is_clean(zh):
    """释义是否干净：无专业标注、无换行、长度达标、至少一个汉字"""
    if not zh:
        return False
    z = zh.strip()
    if not z or len(z) > MAX_ZH_LEN:
        return False
    for p in BAD_TAG_PREFIX:
        if z.startswith(p) or p in z:
            return False
    if "\n" in z or ";" in z or "；" in z:
        return False
    if "[" in z or "]" in z:
        return False
    if not re.search(r"[一-鿿]", z):
        return False
    # 中文省略号 "..."（如"促进..."）是正常的，要留着；
    # 英文句点（缩写/小数点）说明这条释义没洗干净，丢掉。
    if re.search(r"[A-Za-z]\.[A-Za-z]", z):
        return False
    return True


def build_filter_sql():
    or_parts = " OR ".join("s.word LIKE '%% %s'" % p for p in PARTICLES)
    return """
        SELECT s.word, s.translation
        FROM stardict s
        WHERE s.word LIKE '%% %%'
          AND s.translation IS NOT NULL AND s.translation <> ''
          AND s.word NOT LIKE '%%,%%' AND s.word NOT LIKE '%%(%%'
          AND s.word NOT LIKE '%%/%%' AND s.word NOT LIKE '%%-%%'
          AND length(s.word) <= {maxlen}
          AND s.word GLOB '[a-z][a-z]* [a-z]*'
          AND ({ors})
          AND EXISTS (SELECT 1 FROM stardict h
                      WHERE h.word = substr(s.word, 1, instr(s.word, ' ') - 1)
                        AND ( (h.frq > 0 AND h.frq < {freqmax})
                              OR h.collins >= 3 OR h.oxford = 1 )
                        AND h.translation <> ''
                        AND h.word NOT LIKE '%% %%'
                        AND length(h.word) <= 10)
    """.format(maxlen=MAX_PHRASE_LEN, ors=or_parts, freqmax=HEAD_FREQ_MAX)


def score_of(phrase, head_frq=0, head_collins=0, head_oxford=0):
    """搭配的展示优先级：分越高越该出现在查词页前面。

    经验规则：
      · 单词数越少越优先（give up > give up on > give sb a line on）
      · 小品词分层：最高频那批（up/out/off/in/on/over）必须排最前，
        它们既是高频搭配，也是中式英语最容易错的地方
      · 带 sb/sth 之类占位词的往后排（学习价值低）
      · head 词本身越常用，它的搭配整体越该靠前
    """
    parts = phrase.split(" ")
    s = 100
    s -= (len(parts) - 2) * 30          # 三个词 -30，四个词 -60
    top_core = ("up", "out", "off", "in", "on", "over")
    mid_core = ("down", "back", "away", "into", "through", "after", "about")
    prep = ("to", "for", "with", "upon", "at", "by", "against", "from")
    if len(parts) == 2:
        if parts[1] in top_core:
            s += 50
        elif parts[1] in mid_core:
            s += 34
        elif parts[1] in prep:
            s += 20
    if "sb" in parts or "sth" in parts or "somebody" in parts or "something" in parts:
        s -= 25
    if head_frq > 0:
        s += max(0, 20 - head_frq // 300)
    if head_collins >= 4:
        s += 12
    if head_oxford:
        s += 8
    return s


# ---------- 2. 解压现役词典 ----------
if not os.path.exists(SRC):
    log("找不到 ECDICT 源库：%s" % SRC)
    sys.exit(1)
if not os.path.exists(DBZ):
    log("找不到现役词典：%s" % DBZ)
    sys.exit(1)

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

# ---------- 3. 从 ECDICT 源库筛搭配 ----------
log("从 ECDICT 源库筛选搭配 ...")
src = sqlite3.connect(SRC)
src.execute("PRAGMA journal_mode=OFF")
src.execute("PRAGMA synchronous=OFF")

rows = list(src.execute(build_filter_sql()))
log("结构筛选命中 %d 条，开始按释义清洗" % len(rows))

picked = {}
for w, tr in rows:
    zh = extract_meaning(tr)
    phrase = w.strip()
    # 人工校订优先：核心搭配的首义项常常不是最常用的那个义项
    # （look up 首义项是「向上看」，但用户要学的是「查阅」）
    if phrase in PHRASE_OVERRIDES:
        zh = PHRASE_OVERRIDES[phrase]
    else:
        if not zh_is_clean(zh):
            continue
    head = w.split(" ")[0]
    # 去重按「短语」而不是「释义」！
    # 按释义去重会把 give up（放弃）让给先遍历到的 give it away（放弃），
    # 核心搭配就这么被挤掉了，这个坑踩过。
    if phrase in picked:
        continue
    picked[phrase] = (head, phrase, zh)

log("清洗后剩 %d 条（其中人工校订 %d 条）"
    % (len(picked), len([p for p in picked if p in PHRASE_OVERRIDES])))

# 人工校订的搭配强制收录 —— 哪怕它没通过结构筛选（三词短语等），
# 既然我们手写了释义，说明它值得收录。
forced = 0
for phrase, zh in PHRASE_OVERRIDES.items():
    if phrase in picked:
        continue
    r = src.execute("SELECT translation FROM stardict WHERE word = ?", (phrase,)).fetchone()
    if not r:
        continue
    picked[phrase] = (phrase.split(" ")[0], phrase, zh)
    forced += 1
if forced:
    log("人工校订额外补录 %d 条（未通过结构筛选但值得收录的）" % forced)

# 调试：核心搭配必须还在（give up / take over / work out 这类是主角）
for probe in ("give up", "take over", "work out", "deal with"):
    hit = [v for v in picked.values() if v[1] == probe]
    log("  抽查 %-10s -> %s" % (probe, hit[0] if hit else "【丢失!】"))

# head 词的常用度信息：用来给它的搭配排优先级
log("读取 head 词常用度 ...")
head_info = {}
for r in src.execute("SELECT word, frq, collins, oxford FROM stardict "
                     "WHERE word NOT LIKE '% %' AND (frq>0 OR collins>0 OR oxford=1)"):
    head_info[r[0]] = (r[1] or 0, r[2] or 0, r[3] or 0)
log("head 元信息 %d 个" % len(head_info))

# ---------- 4. 写 phrases 表 ----------
db.execute("DROP TABLE IF EXISTS phrases")
db.execute("""CREATE TABLE phrases(
    head TEXT,        -- headword：短语的第一个词（查它时带出这个搭配）
    phrase TEXT,      -- 完整短语，如 give up
    zh TEXT,          -- 中文释义（已取首个义项）
    score INTEGER     -- 排序分：越大越优先展示（查词页只取前几条）
)""")
db.execute("CREATE INDEX idx_phrases_head ON phrases(head)")

ins = []
for (h, p, z) in picked.values():
    frq, col, oxf = head_info.get(h, (0, 0, 0))
    ins.append((h, p, z, score_of(p, frq, col, oxf)))
db.executemany("INSERT INTO phrases VALUES(?,?,?,?)", ins)
db.commit()

n = db.execute("SELECT COUNT(*) FROM phrases").fetchone()[0]
nh = db.execute("SELECT COUNT(DISTINCT head) FROM phrases").fetchone()[0]
log("phrases 表写入 %d 条，覆盖 %d 个 headword" % (n, nh))

log("抽样 12 条：")
for r in db.execute("SELECT phrase, zh FROM phrases ORDER BY RANDOM() LIMIT 12"):
    log("    %-24s %s" % (r[0], r[1]))

for head_probe in ("give", "take", "work"):
    log("%s 的前 8 条（按优先级）：" % head_probe)
    for r in db.execute("SELECT phrase, zh, score FROM phrases WHERE head=? "
                         "ORDER BY score DESC, length(zh), phrase LIMIT 8", (head_probe,)):
        log("    %-24s %-26s score=%d" % (r[0], r[1], r[2]))

db.close()
src.close()

# ---------- 5. 回写 dict.dbz ----------
os.makedirs(BAK_DIR, exist_ok=True)
bak = os.path.join(BAK_DIR, "dict.dbz-no-phrases.bak")
if not os.path.exists(bak):
    shutil.copy2(DBZ, bak)
    log("旧版已备份 -> %s" % bak)

log("回写 dict.dbz ...")
tmp = DBZ + ".tmp"
with open(WORK_DB, "rb") as f, gzip.open(tmp, "wb", 6) as o:
    while True:
        b = f.read(1 << 20)
        if not b:
            break
        o.write(b)
os.replace(tmp, DBZ)
log("新 dict.dbz %.2f MB" % mb(os.path.getsize(DBZ)))

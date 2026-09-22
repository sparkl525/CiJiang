# -*- coding: utf-8 -*-
"""在本机复刻 App 的出题逻辑，检查题目质量：释义是否可读、干扰项是否够迷惑。
规则和 Quiz.java / MainActivity.buildGroup() 保持一致，改了 Java 记得同步改这里。
"""
import gzip
import os
import random
import re
import shutil
import sqlite3

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


TMP = WS + r"\tatoeba\quiz_check.db"
if not os.path.exists(TMP):
    with gzip.open(PROJ + r"\app\src\main\assets\db\dict.dbz", "rb") as f, open(TMP, "wb") as o:
        shutil.copyfileobj(f, o, 1 << 20)
db = sqlite3.connect(TMP)

words = [r[0] for r in db.execute(
    "SELECT word FROM stardict WHERE tag LIKE '%cet6%' AND frq > 0 "
    "AND translation IS NOT NULL AND translation <> '' "
    "AND word NOT LIKE '% %' AND word NOT LIKE '%-%' AND word NOT LIKE \"%'%\" "
    "AND word NOT GLOB '*[^a-z]*' AND length(word) >= 3 AND length(word) <= 20 "
    "ORDER BY frq LIMIT 800")]
print("六级候选词：", len(words))

POS = re.compile(r"^(?:n|v|vt|vi|vn|adj|adv|prep|conj|pron|num|art|int|aux|abbr|pl|pref|suff)\s*\.",
                 re.IGNORECASE)
MAXLEN = 10


def clean(s):
    s = re.sub(r"[（(【\[].*?[)）\]】]", "", s)
    s = re.sub(r"<[^>]*>", "", s)
    s = re.sub(r"[;；:：].*$", "", s)
    s = s.strip()
    m = POS.match(s)
    if m:
        s = s[m.end():].strip()
    return s.strip()


def usable(s):
    s = s.strip()
    if len(s) < 2 or len(s) > MAXLEN:
        return False
    cjk = sum(1 for c in s if '\u4e00' <= c <= '\u9fff')
    for c in s:
        if '\u4e00' <= c <= '\u9fff':
            continue
        if not (c.isalnum() or c in ' ·-'):
            return False
    if cjk < 1:
        return False
    if re.search(r"[A-Za-z]{3,}", s):
        return False
    if len(s) <= 2 and s[-1] in '的地性儿化':
        return False
    return True


def meanings(tr):
    out = []
    if not tr:
        return out
    first = tr.split("\n")[0]
    for seg in re.split(r"[,，、]", first):
        s = clean(seg)
        if len(s) > MAXLEN:
            s = s[:MAXLEN].strip()
        if usable(s) and s not in out:
            out.append(s)
        if len(out) >= 3:
            break
    return out


def plausible(a, b):
    if not a or not b or a == b:
        return False
    if abs(len(a) - len(b)) > 3:
        return False
    return not (a in b or b in a)


def tr_of(w):
    r = db.execute("SELECT translation FROM stardict WHERE word=?", (w,)).fetchone()
    return r[0] if r else ""


rng = random.Random(7)
pool = list(words)
picked = []
while len(picked) < 16 and pool:
    w = pool.pop(rng.randrange(len(pool)))
    picked.append(w)

quiz = []
print("\n=== 模拟一组十题 ===")
for w in picked:
    if len(quiz) >= 10:
        break
    ms = meanings(tr_of(w))
    if not ms:
        print("  (跳过) %-14s 释义不合格：%s" % (w, tr_of(w).split("\n")[0][:40]))
        continue
    m = ms[0]
    if len(quiz) % 2 == 0:
        ds = []
        for _ in range(40):
            if len(ds) >= 3:
                break
            ow = words[rng.randrange(len(words))]
            if ow == w:
                continue
            oms = meanings(tr_of(ow))
            if not oms:
                continue
            om = oms[0]
            if plausible(m, om) and om not in ds:
                ds.append(om)
        if len(ds) < 3:
            quiz.append(("spell", w, m, None))
        else:
            opts = [m] + ds
            rng.shuffle(opts)
            quiz.append(("meaning", w, m, opts))
    else:
        quiz.append(("spell", w, m, None))

for i, (kind, w, m, opts) in enumerate(quiz):
    if kind == "meaning":
        print("\n[%d] 选择题：%s" % (i + 1, w))
        for o in opts:
            print("     %s %s" % ("●" if o == m else "○", o))
    else:
        hint = w[0] + " _" * (len(w) - 1)
        print("\n[%d] 拼写题：%s  →  拼 %s" % (i + 1, m, hint))

print("\n共出题：", len(quiz))

# -*- coding: utf-8 -*-
"""扫描工程里哪些文件是二进制/非 UTF-8，并导出词典内容的可读预览"""
import gzip
import html
import io
import os
import shutil
import sqlite3
import tempfile

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


ROOT = PROJ
SKIP_DIRS = {"build", ".gradle", ".idea", "intermediates"}

print("=" * 78)
print("一、工程文件编码普查")
print("=" * 78)
rows = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for fn in filenames:
        p = os.path.join(dirpath, fn)
        try:
            size = os.path.getsize(p)
        except OSError:
            continue
        with open(p, "rb") as f:
            head = f.read(8192)
        is_binary = b"\x00" in head
        enc = "二进制"
        if not is_binary:
            try:
                with open(p, "rb") as f:
                    f.read().decode("utf-8")
                enc = "UTF-8 文本"
            except UnicodeDecodeError:
                enc = "非 UTF-8 文本(可能是 GBK)"
        rows.append((enc, size, os.path.relpath(p, ROOT)))

for label in ("二进制", "非 UTF-8 文本(可能是 GBK)", "UTF-8 文本"):
    sub = sorted([r for r in rows if r[0] == label], key=lambda r: -r[1])
    if not sub:
        continue
    print("\n【%s】共 %d 个" % (label, len(sub)))
    for enc, size, rel in sub[:25]:
        print("   %10.1f KB  %s" % (size / 1024.0, rel))
    if len(sub) > 25:
        print("   …… 另有 %d 个" % (len(sub) - 25))

# ---------------------------------------------------------------- 词典预览
DICT = os.path.join(ROOT, r"app\src\main\assets\db\dict.dbz")
OUT = DESKTOP + r"\简译-词典内容预览.html"

print()
print("=" * 78)
print("二、词典内容导出")
print("=" * 78)

tmp = os.path.join(tempfile.gettempdir(), "dict_preview_tmp.db")
with gzip.open(DICT, "rb") as fin, open(tmp, "wb") as fout:
    shutil.copyfileobj(fin, fout, 1024 * 1024)
RAW = os.path.getsize(tmp) / 1048576.0
print("解压完成：%.1f MB" % RAW)

TBL = "stardict"
con = sqlite3.connect(tmp)
cur = con.cursor()
schema = cur.execute(
    "SELECT sql FROM sqlite_master WHERE name=?", (TBL,)).fetchall()
total = cur.execute("SELECT COUNT(*) FROM %s" % TBL).fetchone()[0]
cols = [d[1] for d in cur.execute("PRAGMA table_info(%s)" % TBL).fetchall()]
print("表结构：%s" % (schema[0][0] if schema else "?"))
print("字段：%s" % ", ".join(cols))
print("总词条：%s" % format(total, ","))

SAMPLE = ["abandon", "apple", "beautiful", "run", "hello", "the", "book",
          "computer", "study", "translation", "中文", "good"]
cards = []
for w in SAMPLE:
    r = cur.execute(
        "SELECT * FROM %s WHERE word = ? LIMIT 1" % TBL, (w,)).fetchone()
    if not r:
        r = cur.execute(
            "SELECT * FROM %s WHERE word LIKE ? ORDER BY LENGTH(word) LIMIT 1" % TBL,
            (w + "%",)).fetchone()
    if r:
        cards.append(dict(zip(cols, r)))

# 统计信息
stats = {}
stats["以 a-z 开头的词条"] = cur.execute(
    "SELECT COUNT(*) FROM %s WHERE word >= 'a' AND word < '{'" % TBL).fetchone()[0]
for tag in ("zk", "gk", "cet4", "cet6", "ky", "toefl", "ielts", "gre"):
    stats[tag.upper()] = cur.execute(
        "SELECT COUNT(*) FROM %s WHERE tag IS NOT NULL AND tag LIKE ?" % TBL,
        ("%" + tag + "%",)).fetchone()[0]
stats["含短语(带空格)"] = cur.execute(
    "SELECT COUNT(*) FROM %s WHERE word LIKE '%% %%'" % TBL).fetchone()[0]
stats["有音标"] = cur.execute(
    "SELECT COUNT(*) FROM %s WHERE phonetic IS NOT NULL AND phonetic <> ''" % TBL).fetchone()[0]
stats["柯林斯星级词"] = cur.execute(
    "SELECT COUNT(*) FROM %s WHERE collins > 0" % TBL).fetchone()[0]
con.close()
os.remove(tmp)


def esc(t):
    return html.escape("" if t is None else str(t))


def card_html(d):
    tr = d.get("translation") or ""
    lines = []
    for ln in tr.replace("\\n", "\n").split("\n"):
        ln = ln.strip()
        if ln:
            lines.append(ln)
    body = "".join("<div class='tr'>%s</div>" % esc(x) for x in lines[:8])
    defn = (d.get("definition") or "").replace("\\n", "\n").strip()
    if defn:
        body += "".join(
            "<div class='def'>%s</div>" % esc(x.strip())
            for x in defn.split("\n")[:3] if x.strip())
    phon = d.get("phonetic") or ""
    tag = d.get("tag") or ""
    meta = []
    if d.get("collins"):
        meta.append("柯林斯 %d 星" % d["collins"])
    if d.get("oxford"):
        meta.append("牛津核心词")
    if tag:
        meta.append("标签 " + tag)
    return ("<div class='card'><div class='w'>%s</div>"
            "<div class='ph'>%s</div>%s%s</div>"
            % (esc(d.get("word")),
               "[" + esc(phon) + "]" if phon else "",
               body,
               "<div class='tag'>%s</div>" % esc(" · ".join(meta)) if meta else ""))


stat_rows = "".join("<tr><td>%s</td><td class='n'>%s</td></tr>" % (esc(k), format(v, ","))
                   for k, v in stats.items())

doc = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>简译 · 词典内容预览</title>
<style>
 body{margin:0;background:#fff;color:#1f2328;
      font:15px/1.7 "Segoe UI","Microsoft YaHei",system-ui,sans-serif;}
 .wrap{max-width:860px;margin:0 auto;padding:36px 28px 70px;}
 h1{font-size:22px;margin:0 0 6px;}
 .sub{color:#6b7280;font-size:13px;margin-bottom:26px;}
 .panel{background:#f6f7f9;border:1px solid #e4e6eb;border-radius:10px;
        padding:16px 20px;margin-bottom:26px;font-size:14px;}
 .panel b{color:#111;}
 code{background:#eceef2;padding:1px 5px;border-radius:4px;
      font-family:"Cascadia Mono",Consolas,monospace;font-size:13px;}
 table{border-collapse:collapse;width:100%;max-width:520px;margin-top:8px;}
 td{padding:4px 8px;border-bottom:1px solid #eceef2;font-size:13.5px;}
 td.n{text-align:right;font-family:"Cascadia Mono",Consolas,monospace;color:#0550ae;}
 h2{font-size:16px;margin:30px 0 12px;padding-bottom:6px;border-bottom:1px solid #e4e6eb;}
 .card{border:1px solid #e4e6eb;border-radius:10px;padding:14px 18px;margin-bottom:12px;}
 .w{font-size:17px;font-weight:650;letter-spacing:.2px;}
 .ph{color:#8250df;font-size:13.5px;margin:1px 0 8px;}
 .tr{color:#3d444d;font-size:14px;}
 .def{color:#6b7280;font-size:13px;font-style:italic;margin-top:3px;}
 .tag{color:#6b7280;font-size:12px;margin-top:8px;}
 .foot{color:#6b7280;font-size:12.5px;margin-top:30px;
       border-top:1px solid #e4e6eb;padding-top:14px;}
</style></head><body><div class="wrap">
<h1>简译 · 内置词典里到底装了什么</h1>
<div class="sub">数据来源：<code>app\\src\\main\\assets\\db\\dict.dbz</code>
（gzip 压缩的 SQLite 数据库，这就是 VS Code 说"二进制文件"的那个文件）</div>

<div class="panel">
<b>为什么 VS Code 打不开它？</b><br>
因为它的内容是压缩过的数据库，字节层面就不是给人看的文字。
VS Code 检测到里面没有可读的文本结构，就不渲染了（这是保护，不是报错）。<br><br>
<b>解压后的真相：</b>一个标准 SQLite 数据库，
表结构 <code>__SCHEMA__</code>，共 <b>__TOTAL__ 条词条</b>，
解压后约 __RAW__ MB，压缩后 __PACK__ MB。
</div>

<h2>词库构成</h2>
<table>__STATS__</table>

<h2>随便抽几个词看看</h2>
__CARDS__

<div class="foot">
数据源自 ECDICT（skywind3000/ECDICT，MIT 协议），经 <code>tools\\build_dict.py</code>
按"常用度 + 释义完备度"筛出 35.5 万条后压缩打包。<br>
想换一批词看，跟我说词就行。
</div>
</div></body></html>"""

doc = (doc.replace("__SCHEMA__", esc(schema[0][0].replace("\n", " ").replace("  ", " ")) if schema else "?")
          .replace("__TOTAL__", format(total, ","))
          .replace("__RAW__", "%.0f" % RAW)
          .replace("__PACK__", "%.1f" % (os.path.getsize(DICT) / 1048576.0))
          .replace("__STATS__", stat_rows)
          .replace("__CARDS__", "".join(card_html(d) for d in cards)))

with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write(doc)
print("预览页已生成：%s" % OUT)

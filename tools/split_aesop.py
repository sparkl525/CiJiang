# -*- coding: utf-8 -*-
"""把古登堡 #21《伊索寓言全集》切成单篇，按词数筛出适合做成阅读素材的候选。"""
import re
import os

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


SRC = WS + r"\tatoeba\pg21.txt"
raw = open(SRC, encoding="utf-8", errors="ignore").read()
raw = raw.replace("\r", "")

raw_lines = raw.split("\n")

# 从 START 标记开始整段扫描：目录区标题后面没有正文，自然不会产出条目
start_marker = raw.find("*** START OF THE PROJECT GUTENBERG EBOOK")
end_marker = raw.find("*** END OF THE PROJECT GUTENBERG EBOOK")
body = raw[start_marker if start_marker > 0 else 0:
           end_marker if end_marker > 0 else len(raw)].split("\n")


def is_title(l):
    """寓言标题：行首无缩进、Title Case/全大写、短、不是句子"""
    if l != l.strip() or not l.strip():
        return False
    if len(l) > 60 or len(l.split()) > 9:
        return False
    if any(ch in l for ch in ".;:;?!—"):
        return False
    words = [w for w in l.split() if w[0].isalpha()]
    if not words:
        return False
    return all(w[0].isupper() for w in words)


fables = []          # (title, 正文, 词数)
cur_title = None
cur_para = []


def flush():
    global cur_title, cur_para
    if cur_title and cur_para:
        text = re.sub(r"\s+", " ", " ".join(cur_para)).strip()
        n = len(text.split())
        if 40 <= n <= 400:
            fables.append((cur_title, text, n))
    cur_title = None
    cur_para = []


for l in body:
    s = l.rstrip()
    if is_title(s):
        flush()
        cur_title = s.strip()
        continue
    if cur_title and s.strip():
        cur_para.append(s.strip())
flush()

pass
print("切出篇数：", len(fables))
print("\n=== 前 20 条（含词数）===")
for t, txt, n in fables[:20]:
    print("%3d  %-34s | %s" % (n, t[:34], txt[:60]))
print("\n=== 词数 90~220 的候选（前 40 条）===")
cand = [f for f in fables if 90 <= f[2] <= 220]
for t, txt, n in cand[:40]:
    print("%-38s %3d 词 | %s" % (t[:38], n, txt[:70]))
print("\n候选总数：", len(cand))

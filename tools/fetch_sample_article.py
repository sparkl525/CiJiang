# -*- coding: utf-8 -*-
"""抓公有领域英文短文作为素材下载链路的联调样本。
Aesop's Fables = Gutenberg #21（公有领域，可自由分发）。
网络不稳所以重试；抓到的全文会缓存到本地，避免反复下载。
"""
import os
import re
import ssl
import urllib.request

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}

DEST = WS + r"\repo-v2.0\articles"
CACHE = WS + r"\tatoeba\pg21.txt"
os.makedirs(DEST, exist_ok=True)

if os.path.exists(CACHE) and os.path.getsize(CACHE) > 200000:
    best = open(CACHE, encoding="utf-8", errors="replace").read()
    print("用本地缓存：", len(best), "字符")
else:
    best = ""
    for url in ["https://www.gutenberg.org/cache/epub/21/pg21.txt",
                "https://www.gutenberg.org/files/21/21-0.txt"]:
        for attempt in range(3):
            try:
                req = urllib.request.Request(url, headers=UA)
                with urllib.request.urlopen(req, timeout=45, context=CTX) as r:
                    data = b""
                    try:
                        while True:
                            c = r.read(65536)
                            if not c:
                                break
                            data += c
                    except Exception:
                        pass
                text = data.decode("utf-8", "replace")
                print("%s 第%d次：%d 字符" % (url.split('/')[-1], attempt + 1, len(text)))
                if len(text) > len(best):
                    best = text
            except Exception as e:
                print("  失败：", str(e)[:50])
        if len(best) > 200000:
            break
    if len(best) > 200000:
        with open(CACHE, "w", encoding="utf-8") as f:
            f.write(best)

lines = best.split("\n")
start = None
for i, l in enumerate(lines):
    s = l.strip()
    if s.upper().startswith("A FAMISHED FOX") and "grape" in s.lower():
        start = i
        break
print("正文起始行：", start)

paras = []
if start is not None:
    for l in lines[start: start + 15]:
        s = l.strip()
        if not s:
            if paras:
                break
            continue
        if s.isupper() or (s.upper() == s and len(s) < 40):
            break
        paras.append(s)
        if len(" ".join(paras)) > 320:
            break
body = re.sub(r"\s+", " ", " ".join(paras)).strip()
print("--- 正文 ---")
print(body)

if len(body) > 120:
    with open(os.path.join(DEST, "sample-001.txt"), "w", encoding="utf-8") as f:
        f.write(body + "\n")
    print("\n已写入 articles/sample-001.txt（%d 字符）" % len(body))
else:
    print("\n正文太短，未写入")

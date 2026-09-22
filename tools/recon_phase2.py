# -*- coding: utf-8 -*-
"""阶段二前置侦察：词典数据能力 + 发音来源连通性"""
import gzip
import os
import shutil
import socket
import sqlite3
import ssl
import tempfile
import urllib.request

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


DICT = PROJ + r"\app\src\main\assets\db\dict.dbz"

print("=" * 72)
print("一、词典数据能力（决定能不能做「六级词/高频词」标注）")
print("=" * 72)
tmp = os.path.join(tempfile.gettempdir(), "d2.db")
with gzip.open(DICT, "rb") as a, open(tmp, "wb") as b:
    shutil.copyfileobj(a, b, 1 << 20)
con = sqlite3.connect(tmp)
cur = con.cursor()
T = "stardict"


def one(sql, *args):
    return cur.execute(sql, args).fetchone()[0]


total = one("SELECT COUNT(*) FROM %s" % T)
print("总词条            %s" % format(total, ","))
print("有 frq 词频数据   %s" % format(one(
    "SELECT COUNT(*) FROM %s WHERE frq IS NOT NULL AND frq > 0" % T), ","))
print("有 collins 星级   %s" % format(one(
    "SELECT COUNT(*) FROM %s WHERE collins IS NOT NULL AND collins > 0" % T), ","))
print("牛津核心词(oxford)%s" % format(one(
    "SELECT COUNT(*) FROM %s WHERE oxford = 1" % T), ","))
print("tag 非空          %s" % format(one(
    "SELECT COUNT(*) FROM %s WHERE tag IS NOT NULL AND tag <> ''" % T), ","))
print()
print("-- 按 tag 分类 --")
for key, name in (("zk", "中考"), ("gk", "高考"), ("cet4", "四级"), ("cet6", "六级"),
                  ("ky", "考研"), ("ielts", "雅思"), ("toefl", "托福"), ("gre", "GRE")):
    print("   %-4s %-4s %s" % (key, name, format(one(
        "SELECT COUNT(*) FROM %s WHERE tag LIKE ?" % T, "%" + key + "%"), ",")))
print()
print("-- 六级词里同时有词频数据的 --")
print("   %s" % format(one(
    "SELECT COUNT(*) FROM %s WHERE tag LIKE '%%cet6%%' AND frq > 0" % T), ","))
print()
print("-- frq 数值样本（越小越高频）--")
for w in ("the", "abandon", "apple", "hello", "beautiful", "translation",
          "ubiquitous", "serendipity", "computer"):
    r = cur.execute("SELECT frq, collins, oxford, tag FROM %s WHERE word=?" % T, (w,)).fetchone()
    if r:
        print("   %-14s frq=%-6s collins=%-3s oxford=%-3s tag=%s" % (w, r[0], r[1], r[2], r[3]))
    else:
        print("   %-14s (未收录)" % w)
print()
print("-- frq 分档（判断「高频词」阈值放哪合适）--")
for lo, hi in ((1, 1000), (1, 2000), (1, 3000), (1, 5000), (1, 10000), (10001, 999999)):
    n = one("SELECT COUNT(*) FROM %s WHERE frq >= ? AND frq <= ?" % T, lo, hi)
    print("   frq %6d-%-8s %s" % (lo, hi, format(n, ",")))
con.close()
os.remove(tmp)


# ------------------------------------------------------------ 连通性
print()
print("=" * 72)
print("二、发音来源连通性（本机能否直连）")
print("=" * 72)

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"

TARGETS = [
    ("有道发音 英式", "https://dict.youdao.com/dictvoice?audio=hello&type=1"),
    ("有道发音 美式", "https://dict.youdao.com/dictvoice?audio=hello&type=2"),
    ("谷歌翻译TTS 英式", "https://translate.google.com/translate_tts?ie=UTF-8&q=hello&tl=en-GB&client=tw-ob"),
    ("微软Edge语音服务主机", "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"),
    ("百度翻译API(已有key)", "https://fanyi-api.baidu.com/api/trans/vip/translate"),
]
for name, url in TARGETS:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=12, context=ctx) as r:
            body = r.read(2048)
            ct = r.headers.get("Content-Type", "")
            print("   ✅ %-22s HTTP %s  %s  %d bytes" % (name, r.status, ct[:30], len(body)))
    except urllib.error.HTTPError as e:
        print("   ⚠️ %-22s HTTP %s（服务可达，参数被拒也正常）" % (name, e.code))
    except Exception as e:
        print("   ❌ %-22s %s: %s" % (name, type(e).__name__, str(e)[:70]))

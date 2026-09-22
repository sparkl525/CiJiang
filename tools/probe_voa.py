# -*- coding: utf-8 -*-
"""探测 21voa.com：能不能拿到文章列表、正文和音频直链。"""
import re
import sys
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/120 Safari/537.36"}


def get(url, timeout=25):
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=timeout).read()


def decode(b):
    for enc in ("utf-8", "gbk", "gb18030", "latin-1"):
        try:
            return b.decode(enc)
        except Exception:
            continue
    return b.decode("utf-8", "ignore")


url = sys.argv[1] if len(sys.argv) > 1 else "http://m.21voa.com/"
try:
    html = decode(get(url))
except Exception as e:
    print("抓取失败:", type(e).__name__, e)
    sys.exit(1)

print("URL :", url)
print("长度:", len(html))
print("=== 标题 ===")
m = re.search(r"<title>(.*?)</title>", html, re.S)
print(m.group(1).strip() if m else "(无)")

print("\n=== 链接（前 30 条）===")
for href, text in re.findall(r'<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>', html, re.S)[:30]:
    text = re.sub(r"<[^>]+>", "", text).strip()
    if text:
        print("  %-52s %s" % (href[:52], text[:40]))

print("\n=== 音频线索 ===")
for pat in (r'https?://[^"\']+\.mp3', r'https?://[^"\']+\.m4a', r'<audio[^>]+>', r'src="([^"]+\.mp3)"'):
    hits = re.findall(pat, html, re.I)
    if hits:
        print(pat, "->", len(hits))
        for h in hits[:5]:
            print("   ", h if isinstance(h, str) else h)

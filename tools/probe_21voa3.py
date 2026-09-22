# -*- coding: utf-8 -*-
"""进到 VOA 素材站的具体文章页，确认一篇文章里到底有什么：音频、英文全文、中文、日期。"""
import re
import ssl
import urllib.request

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                         "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"}


def get(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=30, context=CTX) as r:
        return r.read(600000).decode(r.headers.get_content_charset() or "utf-8", "replace")


BASE = "https://www.21voa.com"
html = get(BASE + "/special_english/")
arts = [l for l in re.findall(r'href="([^"]+)"', html) if l.endswith(".html") and "/20" not in l]
arts = [l for l in arts if not l.startswith("http")]
print("栏目页找到候选文章：", len(arts))
for a in arts[:5]:
    print("   ", a)

target = arts[0] if arts else None
if target:
    url = target if target.startswith("http") else BASE + target
    if not url.startswith("http"):
        url = BASE + "/" + target.lstrip("/")
    print("\n>>> 打开文章页：", url)
    h = get(url)
    a_links = re.findall(r'href="([^"]+\.mp3)"', h)
    src = re.findall(r'(?:src|data-url|url)\s*=\s*"([^"]+\.mp3)"', h)
    print("音频直链：", (a_links + src)[:3])
    print("页面含 '下载'：", len(re.findall("下载", h)), " 含 'mp3'：", len(re.findall("mp3", h, re.I)))
    body = re.sub(r"<script.*?</script>|<style.*?</style>", "", h, flags=re.S)
    body = re.sub(r"<[^>]+>", " ", body)
    body = re.sub(r"\s+", " ", body).strip()
    print("正文前 500 字：\n", body[:500])

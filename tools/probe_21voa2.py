# -*- coding: utf-8 -*-
"""看清 VOA 素材站首页的实际结构：标题、栏目、链接形态、有没有音频入口。"""
import re
import ssl
import urllib.request

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                         "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"}

for host in ["http://m.21voa.com/", "https://www.21voa.com/"]:
    try:
        req = urllib.request.Request(host, headers=HEADERS)
        with urllib.request.urlopen(req, timeout=30, context=CTX) as r:
            html = r.read(300000).decode(r.headers.get_content_charset() or "utf-8", "replace")
        title = re.search(r"<title>(.*?)</title>", html, re.S)
        print("=" * 60)
        print(host, "标题：", (title.group(1).strip() if title else "无")[:80])
        print("页面含 'mp3' 次数：", len(re.findall(r"mp3", html, re.I)),
              " 含 '听力'：", len(re.findall(r"听力", html)),
              " 含 '慢速'：", len(re.findall(r"慢速", html)))
        links = re.findall(r'href="([^"]+)"', html)
        uniq = []
        for l in links:
            if l not in uniq and not l.startswith(("#", "javascript")):
                uniq.append(l)
        print("链接样例（前 25 条）：")
        for l in uniq[:25]:
            print("   ", l[:95])
        text = re.sub(r"<script.*?</script>|<style.*?</style>", "", html, flags=re.S)
        text = re.sub(r"<[^>]+>", " ", text)
        text = re.sub(r"\s+", " ", text).strip()
        print("正文片段：", text[:400])
    except Exception as e:
        print(host, "不可达：", str(e)[:70])

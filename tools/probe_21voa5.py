# -*- coding: utf-8 -*-
"""打印栏目页全部链接，看清文章 URL 到底长什么样。"""
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
        return r.read(800000).decode(r.headers.get_content_charset() or "utf-8", "replace")


for page in ["http://m.21voa.com/voa_special_english", "http://m.21voa.com/"]:
    try:
        h = get(page)
    except Exception as e:
        print(page, "不可达", str(e)[:60])
        continue
    links = sorted(set(re.findall(r'href="([^"]+)"', h)))
    print("=" * 60)
    print(page, "链接总数", len(links))
    for l in links:
        print("   ", l[:100])

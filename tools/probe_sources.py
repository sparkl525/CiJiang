# -*- coding: utf-8 -*-
"""探查 v2.0 规划里可能用到的数据源是否可达、多大。只发 HEAD 请求，不下载正文。"""
import urllib.request

URLS = [
    ("OPUS-100 中英平行句(监督版)", "https://object.pouta.csc.fi/OPUS-100/v2.0/supervised/en-zh/zh-en.txt.zip"),
    ("OPUS-100 中英平行句(原始)", "https://object.pouta.csc.fi/OPUS-100/v2.0/raw/en-zh.zip"),
    ("Tatoeba 全量句对", "https://downloads.tatoeba.org/exports/links.tar.bz2"),
    ("简单英语维基（CC BY-SA）", "https://dumps.wikimedia.org/simplewiki/latest/simplewiki-latest-pages-articles.xml.bz2"),
]

for name, url in URLS:
    try:
        req = urllib.request.Request(url, method="HEAD")
        with urllib.request.urlopen(req, timeout=25) as r:
            size = r.headers.get("Content-Length")
            mb = int(size) / (1024 * 1024) if size else 0
            print("%-24s %s  %.1f MB" % (name, r.status, mb))
    except Exception as e:
        print("%-24s 不可达：%s" % (name, str(e)[:70]))

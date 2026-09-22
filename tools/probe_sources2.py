# -*- coding: utf-8 -*-
"""二次探查：带自定义 UA、忽略证书问题，确认几个关键素材源的连通性与体积。"""
import ssl
import urllib.request

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"

URLS = [
    ("OPUS-100 中英平行句", "https://object.pouta.csc.fi/OPUS-100/v2.0/supervised/en-zh/zh-en.txt.zip"),
    ("OPUS Tatoeba 子集", "https://object.pouta.csc.fi/OPUS-Tatoeba/v2020-08-06/moses/en-zh.txt.zip"),
    ("VOA 慢速英语首页", "https://learningenglish.voanews.com/"),
    ("VOA 音频接口示例", "https://learningenglish.voanews.com/Container_pmw/audios/1574932.mp3"),
    ("Breaking News English", "https://www.breakingnewsenglish.com/"),
    ("古登堡计划（公版书）", "https://www.gutenberg.org/ebooks/search/?sort_order=downloads"),
]

for name, url in URLS:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30, context=CTX) as r:
            size = r.headers.get("Content-Length")
            mb = int(size) / (1024 * 1024) if size else 0
            print("%-22s %s  %.1f MB" % (name, r.status, mb))
    except Exception as e:
        print("%-22s 不可达：%s" % (name, str(e)[:60]))

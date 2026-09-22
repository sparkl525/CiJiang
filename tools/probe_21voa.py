# -*- coding: utf-8 -*-
"""探查 VOA 素材站的可用性：首页能否打开、里面有没有 MP3 链接、有没有明确的更新节奏。
只做少量 GET，不抓全站。
"""
import re
import ssl
import urllib.request

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

HEADERS = {
    "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
                  "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1",
    "Accept": "text/html,application/xhtml+xml",
    "Accept-Language": "en-US,en;q=0.9",
}


def get(url, limit=200000):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=30, context=CTX) as r:
        raw = r.read(limit)
        enc = r.headers.get_content_charset() or "utf-8"
        return r.status, raw.decode(enc, errors="replace")


for host in ["http://m.21voa.com/", "https://www.21voa.com/", "http://www.51voa.com/"]:
    try:
        st, html = get(host)
        links = re.findall(r'href="([^"]+)"', html)
        mp3 = [l for l in links if l.lower().endswith(".mp3")]
        arts = [l for l in links if re.search(r"/(VOA|Special|standard|Article)\w*/\d", l)]
        print("%-28s 状态 %s  页面 %d 字符  文章链接 %d 个  mp3 直链 %d 个"
              % (host, st, len(html), len(arts), len(mp3)))
        for a in arts[:5]:
            print("      样例文章：", a)
        for m in mp3[:3]:
            print("      样例音频：", m)
    except Exception as e:
        print("%-28s 不可达：%s" % (host, str(e)[:70]))

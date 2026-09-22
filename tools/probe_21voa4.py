# -*- coding: utf-8 -*-
"""找准 VOA 素材站里"具体文章"的 URL 形态，并打开一篇确认内容构成。"""
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


for page in ["https://www.21voa.com/special_english/", "http://m.21voa.com/voa_special_english"]:
    try:
        h = get(page)
    except Exception as e:
        print(page, "不可达", str(e)[:50])
        continue
    links = sorted(set(re.findall(r'href="([^"]+)"', h)))
    cand = [l for l in links if re.search(r"_\d+\.html$", l) or re.search(r"/\d{5,}\.html", l)]
    print("=" * 60)
    print(page, "共链接", len(links), "疑似文章链接", len(cand))
    for l in cand[:8]:
        print("   ", l)
    if cand:
        one = cand[0]
        base = "https://www.21voa.com" if page.startswith("https") else "http://m.21voa.com"
        url = one if one.startswith("http") else base + "/" + one.lstrip("/")
        try:
            a = get(url)
        except Exception as e:
            print("文章页打不开：", str(e)[:60])
            break
        mp3 = re.findall(r'["\']([^"\']+\.mp3)["\']', a)
        print("\n>>> 文章页：", url)
        print("    mp3 链接：", mp3[:3])
        print("    含'下载'：", len(re.findall("下载", a)), " 含'MP3'：", len(re.findall("MP3", a)))
        body = re.sub(r"<script.*?</script>|<style.*?</style>", "", a, flags=re.S)
        body = re.sub(r"<[^>]+>", " ", body)
        body = re.sub(r"\s+", " ", body).strip()
        print("    正文片段：", body[:420])
        break

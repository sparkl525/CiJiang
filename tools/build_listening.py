# -*- coding: utf-8 -*-
"""抓取 21voa.com 的慢速英语存量：MP3 + LRC 字幕，做成听力素材。

站点已停更（首页最新停在 3-22），但这些存量文章依然可用。
音频直链在 files.21voa.com，需要带 Referer 才能下（否则 403）。
只有列表页带 lrc.png 图标的文章才有字幕，优先抓这些。

用法：
  python build_listening.py           # 只看有哪些可抓（不下载）
  python build_listening.py 6         # 抓前 6 篇，下载音频
"""
import os
import re
import sys
import urllib.request

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 词桨工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120",
    "Referer": "http://m.21voa.com/",
}
SITE = "http://m.21voa.com"
LIST_URL = SITE + "/voa_special_english"
OUT = WS + r"\repo-v2.0"
AUDIO_DIR = os.path.join(OUT, "audio")
ART_DIR = os.path.join(OUT, "articles")

# 素材在 GitHub 上的根地址，App 拼上 items 里的相对路径去下载
BASE_URL = "https://cdn.jsdelivr.net/gh/sparkl525/-v2.0@main"

# 只把第一篇打进 APK；其余靠「素材包」联网下载，否则音频会把 APK 撑爆
BUILTIN_COUNT = 1

# 中文标题：抓到哪篇就在这里补一条，没补就退回英文原标题
TITLE_ZH = {
    "l-001": "林肯的小屋",
    "l-002": "固特异飞艇飞了 100 年",
    "l-003": "金门大桥",
    "l-004": "秃鹰成为美国国鸟",
}


def get(url, timeout=40):
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=timeout).read()


def text_of(h):
    return h.decode("utf-8", "ignore")


def parse_list(html):
    """列表页的文章链接没有引号，得单独处理：<a href=/xxx-93397>标题</a>"""
    items = []
    # 先按 <li> 切开，这样能知道每条里有没有 lrc 图标
    for block in re.split(r"<li>", html):
        m = re.search(r'<h3><a\s+href=([^\s>]+)>(.*?)</a>', block, re.S)
        if not m:
            continue
        href = m.group(1).strip().strip('"\'')
        title = re.sub(r"<[^>]+>", "", m.group(2)).strip()
        if not href.startswith("/"):
            href = "/" + href
        items.append((href, title, "lrc.png" in block))
    return items


def parse_article(html):
    """返回 (mp3 直链, lrc 原文)"""
    mps = re.findall(r'https?://[^"\']+\.mp3', html)
    mp3 = mps[0] if mps else ""
    m = re.search(r'id="lrc_content"[^>]*>(.*?)</textarea>', html, re.S)
    if not m:
        m = re.search(r'id="lrc_content"[^>]*>(.*?)</', html, re.S)
    lrc = m.group(1).strip() if m else ""
    return mp3, lrc


def lrc_text(lrc):
    """只看字幕正文，去掉 [ti:] [by:] 之类的元数据行"""
    out = []
    for line in lrc.split("\n"):
        s = line.strip()
        if not s or not re.match(r"\[\d{2}:\d{2}", s):
            continue
        out.append(re.sub(r"^\[\d{2}:\d{2}[.\d]*\]", "", s))
    return out


def main():
    want = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    html = text_of(get(LIST_URL))
    items = parse_list(html)
    with_lrc = [it for it in items if it[2]]
    print("列表共 %d 条，其中有字幕的 %d 条" % (len(items), len(with_lrc)))
    for href, title, has in items[:15]:
        print("  %-8s %-58s %s" % ("[字幕]" if has else "      ", title[:58], href))

    if not want:
        print("\n（只探测，不下载。要抓取请带篇数参数）")
        return

    os.makedirs(AUDIO_DIR, exist_ok=True)
    os.makedirs(ART_DIR, exist_ok=True)
    picked = with_lrc[:want]
    print("\n=== 开始抓 %d 篇 ===" % len(picked))
    metas = []
    for i, (href, title, _) in enumerate(picked, 1):
        lid = "l-%03d" % i
        try:
            mp3_path = os.path.join(AUDIO_DIR, lid + ".mp3")
            lrc_path = os.path.join(ART_DIR, lid + ".lrc")
            if (os.path.exists(mp3_path) and os.path.getsize(mp3_path) > 1000
                    and os.path.exists(lrc_path)):
                # 抓过就复用，别每次重新下载十几 MB
                data = open(mp3_path, "rb").read()
                lrc = open(lrc_path, encoding="utf-8").read()
            else:
                ah = text_of(get(SITE + href))
                mp3, lrc = parse_article(ah)
                if not mp3 or not lrc:
                    print("%s 跳过：mp3=%s lrc=%d 字" % (lid, bool(mp3), len(lrc)))
                    continue
                data = get(mp3)
                open(mp3_path, "wb").write(data)
                open(lrc_path, "w", encoding="utf-8").write(lrc)
            lines = lrc_text(lrc)
            words = sum(len(x.split()) for x in lines)
            # 时长用字幕最后一句的时间戳近似
            stamps = [x for x in re.findall(r"\[(\d{2}):(\d{2})", lrc)]
            last = int(stamps[-1][0]) * 60 + int(stamps[-1][1]) if stamps else 0
            metas.append(dict(
                id=lid, title=title, titleZh=TITLE_ZH.get(lid, title),
                topic="VOA 慢速英语", level="easy", words=words,
                sentences=len(lines), durationSec=last + 5,
                builtin=(len(metas) < BUILTIN_COUNT),
                hasAudio=True, audioBytes=len(data),
                text="articles/%s.lrc" % lid, audio="audio/%s.mp3" % lid,
                source="21voa.com（VOA Learning English 存量，站点已停更）",
            ))
            print("%s  %-42s %5.1f MB  %2d 句  %4d 词  约 %d:%02d  %s" % (
                lid, title[:42], len(data) / 1048576.0, len(lines), words,
                (last + 5) // 60, (last + 5) % 60,
                "内置" if len(metas) <= BUILTIN_COUNT else "在线"))
        except Exception as e:
            print("%s 失败：%s %s" % (lid, type(e).__name__, e))

    if not metas:
        return
    import json
    import shutil
    manifest = dict(version=1, updated="2026-09-22", baseUrl=BASE_URL,
                    note="词桨 App 听力素材清单。builtin=true 的已内置在 APK，"
                         "其余由「素材包」开关联网下载到手机。",
                    items=metas)
    apk_dir = PROJ + r"\app\src\main\assets\listening"
    os.makedirs(apk_dir, exist_ok=True)
    for m in metas:
        if not m["builtin"]:
            continue
        shutil.copy(os.path.join(AUDIO_DIR, m["id"] + ".mp3"),
                    os.path.join(apk_dir, m["id"] + ".mp3"))
        shutil.copy(os.path.join(ART_DIR, m["id"] + ".lrc"),
                    os.path.join(apk_dir, m["id"] + ".lrc"))
    # APK 里叫 manifest.json（App 读这个）；仓库根用 listening.json
    open(os.path.join(apk_dir, "manifest.json"), "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=2))
    open(os.path.join(OUT, "listening.json"), "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=2))
    print("\n清单已写入 %d 条，音频已进 APK assets/listening" % len(metas))


if __name__ == "__main__":
    main()

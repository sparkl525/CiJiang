# -*- coding: utf-8 -*-
"""生成 v3.0 新增的 5 篇 NASA 阅读素材（r-005 ~ r-009）并追加进阅读 manifest。

文章源：science.nasa.gov（NASA 为美国政府机构，文字作品属公共域）。
源 HTML 缓存在 ../nasa-src/（2026-10-20 抓取）；刷新素材时需重新抓取该目录下的页面。
策展规则见下面 ARTICLES 的 splits / drops。

用法：python build_reading_nasa.py
"""
import html as H
import json
import os
import re

_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)
WS = os.path.dirname(PROJ)

SRC_DIR = os.path.join(WS, "nasa-src")
OUT_REPO = os.path.join(WS, "repo-v2.0", "articles")
OUT_APK = os.path.join(PROJ, "app", "src", "main", "assets", "reading")
MANIFEST_REPO = os.path.join(WS, "repo-v2.0", "reading.json")
MANIFEST_APK = os.path.join(OUT_APK, "manifest.json")

BASE_URL = "https://cdn.jsdelivr.net/gh/sparkl525/-v2.0@main"

# 命中这些串的段落 → 连同其后所有段落一起截断（图注、相关推荐、模板尾巴）
CUT_MARKERS = [
    "References & Resources", "Keep Exploring", "Discover More from NASA",
    "Subscribe to Earth", "Article 1 2 3", "View more Images of the Day",
    "Open access to NASA", "The following sections contain links",
    "To learn more about Webb", "The James Webb Space Telescope is the world's premier",
    "Webb is the premier observatory", "Related", "You May Also Like",
]

# ─── 5 篇精品（2026 年 9-10 月发布）───
# splits:  段落里出现这些串，就砍掉它之前的内容（去掉混排的图片说明）
# drops:   段落以这些串开头 → 整段丢弃（图注、署名、模板尾巴）
ARTICLES = [
    dict(
        id="r-005", src="perseverance.html",
        url="https://science.nasa.gov/blogs/perseverance-mars-rover/2026/10/12/nasas-perseverance-sets-off-world-record-for-total-distance-driven/",
        title="Perseverance Sets Off-World Driving Record",
        titleZh="毅力号火星车创下地外行驶纪录",
        topic="太空探索", level="medium",
        splits=[],
        drops=["One of Perseverance's navigation cameras captured",
               "This infographic illustrates",
               "NASA Science Editorial Team"],
        zh="《毅力号创地外行驶纪录》毅力号火星车总里程超过 45 公里，超越机遇号成为另一颗星球上开得最远的车；文章回顾三十年来四代火星车与自动驾驶技术的跨越。",
    ),
    dict(
        id="r-006", src="greenland.html",
        url="https://science.nasa.gov/earth/earth-observatory/uncovering-the-valleys-hidden-below-greenlands-ice/",
        title="Valleys Hidden Below Greenland's Ice",
        titleZh="格陵兰冰盖下的隐秘山谷",
        topic="地球科学", level="medium",
        splits=[],
        drops=["NASA Earth Observatory map by Lauren Dauphin"],
        zh="《格陵兰冰下山谷》NASA 团队用「冰流扰动分析」绘制出冰盖下 1943 条山谷的最精确地图，约三分之一是新发现的，有助预测冰盖未来的变化。",
    ),
    dict(
        id="r-007", src="arctic.html",
        url="https://science.nasa.gov/earth/earth-observatory/arctic-melt-season-length-levels-off/",
        title="Arctic Melt Season Length Levels Off",
        titleZh="北极融雪季的延长趋势放缓",
        topic="气候变化", level="medium",
        splits=["For decades, Arctic sea ice"],
        drops=["NASA Earth Observatory images by Michala Garrison"],
        zh="《北极融雪季趋稳》新研究显示北极海冰融化季比 1979 年长了约 40 天，但 2010 年后延长趋势意外放缓，天气的年际变化影响越来越大。",
    ),
    dict(
        id="r-008", src="amazon.html",
        url="https://science.nasa.gov/earth/earth-observatory/fires-arrive-in-the-amazon-rainforest/",
        title="Fires Arrive in the Amazon Rainforest",
        titleZh="火灾季降临亚马逊雨林",
        topic="生态环境", level="medium",
        splits=[],
        drops=["NASA Earth Observatory images by Lauren Dauphin"],
        zh="《亚马逊火灾季》厄尔尼诺干旱让亚马逊雨林进入严重火季，卫星监测到中东部火点骤增、马瑙斯被浓烟笼罩，科学家警告火势将向北、向南蔓延。",
    ),
    dict(
        id="r-009", src="frb.html",
        url="https://science.nasa.gov/missions/webb/webb-measures-distance-to-farthest-fast-radio-burst-suggesting-origin/",
        title="Webb Measures Distance to Farthest Fast Radio Burst",
        titleZh="韦布望远镜测得最远快速射电暴的距离",
        topic="天文", level="medium",
        splits=["Webb's NIRCam (Near-Infrared Camera) instrument detected",
                "In the future, the team"],
        drops=["The James Webb Space Telescope is the world's premier",
               "To learn more about Webb",
               "The following sections contain links",
               "Farthest Fast Radio Burst Host Galaxy Spectrum",
               "FRB 20240304B (NIRCam Compass Image)",
               "NASA's James Webb Space Telescope's NIRCam (Near-Infrared Camera) was able to detect"],
        zh="《最远快速射电暴》韦布望远镜确认迄今最远的快速射电暴来自大爆炸后仅 30 亿年的小矮星系，暗示它更可能源自年轻磁星，而非双中子星并合。",
    ),
]


def clean_text(t):
    t = re.sub(r"<[^>]+>", " ", t)
    t = H.unescape(t)
    t = t.replace("\u00a0", " ").replace("\u2019", "'").replace("\u2018", "'")
    t = t.replace("\u201c", '"').replace("\u201d", '"')
    t = t.replace("\u2013", "-").replace("\u2014", "-").replace("\u2026", "...")
    return re.sub(r"\s+", " ", t).strip()


def extract_paras(html_text, art):
    """定位正文容器 → 抽 <p> → 应用该篇的策展规则"""
    seg = ""
    for pat in [r'<div[^>]*class="[^"]*entry-content[^"]*"[^>]*>(.*?)</article>',
                r'<div[^>]*class="[^"]*single-blog-content[^"]*"[^>]*>(.*?)</article>',
                r'<div[^>]*class="[^"]*entry-content[^"]*"[^>]*>(.*?)<footer',
                r'<div[^>]*class="[^"]*single-blog-content[^"]*"[^>]*>(.*?)<footer']:
        m = re.search(pat, html_text, re.S)
        if m:
            seg = m.group(1)
            break
    seg = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", seg, flags=re.S)
    out = []
    cutting = False
    for i, p in enumerate(re.findall(r"<p[^>]*>(.*?)</p>", seg, re.S)):
        if cutting:
            break
        t = clean_text(p)
        if len(t.split()) < 4:
            continue
        # 首段若是面包屑导航块则丢
        if i == 0 and len(t.split()) > 40 and ("Image of the Day" in t or (t.startswith("Science") and "Observatory" in t)):
            continue
        # 命中截断标记 → 从此处往后全部丢弃
        if any(k in t for k in CUT_MARKERS):
            cutting = True
            continue
        # splits：段落中出现标记串 → 砍掉它之前的混排图注
        hit = False
        for s in art["splits"]:
            k = t.find(s)
            if k > 0:
                t = t[k:].strip()
                hit = True
        if hit:
            if len(t.split()) >= 4:
                out.append(t)
            continue
        # drops：图注 / 署名 / 模板尾巴
        if any(t.startswith(d) for d in art["drops"]):
            continue
        out.append(t)
    return out


def main():
    os.makedirs(OUT_REPO, exist_ok=True)
    os.makedirs(OUT_APK, exist_ok=True)

    manifest = json.load(open(MANIFEST_REPO, encoding="utf-8"))
    have = {it["id"] for it in manifest["items"]}
    added = 0
    for a in ARTICLES:
        html_text = open(os.path.join(SRC_DIR, a["src"]), encoding="utf-8", errors="ignore").read()
        paras = extract_paras(html_text, a)
        words = sum(len(p.split()) for p in paras)
        en = "\n\n".join(paras).strip() + "\n"
        zh = "《%s》%s\n" % (a["titleZh"], a["zh"].split("》", 1)[1] if "》" in a["zh"] else a["zh"])

        open(os.path.join(OUT_REPO, a["id"] + ".txt"), "w", encoding="utf-8").write(en)
        open(os.path.join(OUT_REPO, a["id"] + ".zh.txt"), "w", encoding="utf-8").write(zh)
        open(os.path.join(OUT_APK, a["id"] + ".txt"), "w", encoding="utf-8").write(en)
        open(os.path.join(OUT_APK, a["id"] + ".zh.txt"), "w", encoding="utf-8").write(zh)

        item = dict(
            id=a["id"], title=a["title"], titleZh=a["titleZh"], topic=a["topic"],
            level=a["level"], words=words, builtin=True,
            bytes=len(en.encode("utf-8")) + len(zh.encode("utf-8")),
            text="articles/%s.txt" % a["id"],
            translation="articles/%s.zh.txt" % a["id"],
            license="public-domain",
            source="NASA（美国政府作品·公共域）%s" % a["url"],
        )
        if a["id"] not in have:
            manifest["items"].append(item)
            added += 1
        print("%s  %-22s %2d 段 %4d 词  %s" % (a["id"], a["titleZh"], len(paras), words, "内置"))

    manifest["version"] = 2
    manifest["updated"] = "2026-10-20"
    manifest["note"] = ("词桨 App 阅读素材清单。builtin=true 的已内置在 APK，"
                        "其余由「素材包」开关联网下载到手机。NASA 文章为美国政府作品（公共域）。")
    open(MANIFEST_REPO, "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=2))
    open(MANIFEST_APK, "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=2))
    print("\n新增 %d 条；清单共 %d 条，版本 %d（%s）" % (
        added, len(manifest["items"]), manifest["version"], manifest["updated"]))


if __name__ == "__main__":
    main()

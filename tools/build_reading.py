# -*- coding: utf-8 -*-
"""生成阅读素材：把古登堡 #21 的伊索寓言按"期"打包成 App 可直接读的 txt。

产出（写到 工作区/repo-v2.0/articles/，同时镜像到 APK assets/reading/）：
  r-00N.txt     英文正文，篇名 + 空行 + 段落
  r-00N.zh.txt  每篇一句中文梗概（不是全文对照，正文翻译交给 App 内的划句翻译）
  manifest.json 索引

改期次/篇目只动下面的 GROUPS 和 SUMMARIES。
"""
import json
import os
import re

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


SRC = WS + r"\tatoeba\pg21.txt"
OUT_REPO = WS + r"\repo-v2.0\articles"
OUT_APK = PROJ + r"\app\src\main\assets\reading"

# ---- 期次配置：每期 3~4 篇，总词数控制在 350~600 ----
# builtin=True 的会打进 APK；其余只在清单里占位，由 App 的「素材包」开关联网下载
GROUPS = [
    dict(id="r-001", title="Aesop I: Strength and Weakness",
         titleZh="伊索寓言（一）· 强与弱", topic="伊索寓言", level="easy", builtin=True,
         fables=["The Lion And The Mouse", "The Wolf And The Lamb",
                 "The Ass And The Grasshopper"]),
    dict(id="r-002", title="Aesop II: Together and Alone",
         titleZh="伊索寓言（二）· 合与分", topic="伊索寓言", level="easy", builtin=False,
         fables=["The Father And His Sons", "The Charcoal-Burner And The Fuller",
                 "The Boy Hunting Locusts", "The Mule"]),
    dict(id="r-003", title="Aesop III: Hunger and Haste",
         titleZh="伊索寓言（三）· 饿与急", topic="伊索寓言", level="easy", builtin=False,
         fables=["The Thirsty Pigeon", "The Two Frogs", "The Swollen Fox"]),
    dict(id="r-004", title="Aesop IV: Boasting and Age",
         titleZh="伊索寓言（四）· 夸与老", topic="伊索寓言", level="easy", builtin=False,
         fables=["The Boasting Traveler", "The Old Hound", "The Sick Stag"]),
]

# 素材在 GitHub 上的根地址，App 拼上 items 里的相对路径去下载
BASE_URL = "https://cdn.jsdelivr.net/gh/sparkl525/-v2.0@main"

# ---- 每篇一句中文梗概 ----
SUMMARIES = {
    "The Lion And The Mouse":
        "老鼠把狮子吵醒，狮子放它一条生路；后来狮子落入罗网，正是这只老鼠咬断绳索救了它。",
    "The Wolf And The Lamb":
        "狼一心要吃小羊，先后编出几个借口，被小羊一一驳倒后干脆撕下讲理的伪装。",
    "The Ass And The Grasshopper":
        "驴子羡慕蚱蜢的歌喉，听说它们靠露水为生便也只吃露水，最后活活饿死。",
    "The Father And His Sons":
        "父亲让儿子们折筷子：一根一折就断，捆成一把谁也折不断——团结才站得住。",
    "The Charcoal-Burner And The Fuller":
        "烧炭匠邀漂布匠同住，漂布匠婉拒：你手上的活儿把东西弄黑，我恰恰要把它弄白。",
    "The Boy Hunting Locusts":
        "男孩抓蚱蜢时误把蝎子当成蚱蜢，被蜇之后才学会先看清再伸手。",
    "The Mule":
        "骡子吃饱了闲得发慌，自以为继承了赛马的血统；被赶了一天长路后累得直叹气：我父亲大概是头驴。",
    "The Thirsty Pigeon":
        "口渴的鸽子把画上的水杯当成真的，一头撞上去，翅膀受了伤才明白莽撞比口渴更糟。",
    "The Two Frogs":
        "住深池塘的青蛙劝住路边浅沟的邻居搬过来，邻居舍不得离开老地方，几天后被大车碾死。",
    "The Swollen Fox":
        "饿狐狸钻进树洞把牧羊人留下的肉吃了个饱，肚子撑得钻不出来，只好再饿瘦才脱身。",
    "The Boasting Traveler":
        "游历归来的旅人吹嘘自己在罗得岛跳得多远，旁人说：这里就是罗得岛，你现在跳一个。",
    "The Old Hound":
        "老猎犬咬不住猎物被主人责骂，它回答：我年轻时你夸我勇猛，如今老了，别只记得我这次失手。",
    "The Sick Stag":
        "生病的鹿躺在草地上，同伴们把草料吃光，它最后不是病死，而是被同伴断绝了活路。",
}


def parse_toc(raw):
    """CONTENTS 就是权威标题表。目录行行首有缩进，正文标题没有，据此区分"""
    toc = set()
    for l in raw.split("\n"):
        if l == l.strip():
            continue                      # 行首无缩进 → 不是目录条目
        s = l.strip()
        if not s or len(s) > 60 or len(s.split()) > 9:
            continue
        if any(ch in s for ch in ".;:;?!—"):
            continue
        words = s.split()
        if not words or not words[0][0].isalpha() or not words[0][0].isupper():
            continue
        # 连词/冠词允许小写（"The Wolf and the Crane"），所以只要求过半首字母大写
        cap = sum(1 for w in words if w[0].isalpha() and w[0].isupper())
        if cap >= max(1, len(words) // 2):
            toc.add(s)
    return toc


def split_fables():
    raw = open(SRC, encoding="utf-8", errors="ignore").read().replace("\r", "")
    start = raw.find("*** START OF THE PROJECT GUTENBERG EBOOK")
    end = raw.find("*** END OF THE PROJECT GUTENBERG EBOOK")
    toc = parse_toc(raw)
    lines = raw[start if start > 0 else 0: end if end > 0 else len(raw)].split("\n")

    out = {}
    title = None
    seg, paras = [], []

    def flush():
        nonlocal title, seg, paras
        if seg:
            paras.append(seg)
            seg = []
        if title and paras:
            ps = [re.sub(r"\s+", " ", " ".join(p)).strip() for p in paras if p]
            text = "\n\n".join(ps)
            if text:
                out[title] = (ps, len(text.split()))
        title, paras = None, []

    # 这个版本的古登堡文本每行后面都跟一个空行：
    #   1 个空行 = 同一段内的软换行（要并回一行）
    #   2 个及以上 = 段落分隔
    blank = 0
    for i, l in enumerate(lines):
        s = l.rstrip()
        key = s.strip()
        if not key:
            blank += 1
            if blank >= 2 and seg:
                paras.append(seg)
                seg = []
            continue
        next_blank = (i + 1 >= len(lines)) or (lines[i + 1].strip() == "")
        # 正文里的篇名：行首无缩进、在目录里、前后都有空行（孤立成行）
        if key in toc and s == key and blank >= 1 and next_blank:
            flush()
            title = key
            blank = 0
            continue
        if title is None:
            blank = 0
            continue
        if seg and blank >= 2:
            paras.append(seg)
            seg = []
        seg.append(key)
        blank = 0
    flush()
    return out


def main():
    fables = split_fables()
    os.makedirs(OUT_REPO, exist_ok=True)
    os.makedirs(OUT_APK, exist_ok=True)
    items = []

    for g in GROUPS:
        lines_en, lines_zh, total = [], [], 0
        for name in g["fables"]:
            if name not in fables:
                print("!! 缺篇目：", name)
                continue
            paras, n = fables[name]
            total += n
            lines_en.append("## " + name)      # "## " 开头的行是篇名，App 据此分段
            lines_en.append("")
            lines_en.extend(paras)
            lines_en.append("")
            zh = SUMMARIES.get(name, "")
            lines_zh.append("《%s》%s" % (name, zh))
        en = "\n".join(lines_en).strip() + "\n"
        zh = "\n".join(lines_zh).strip() + "\n"

        for d in (OUT_REPO,):
            open(os.path.join(d, g["id"] + ".txt"), "w", encoding="utf-8").write(en)
            open(os.path.join(d, g["id"] + ".zh.txt"), "w", encoding="utf-8").write(zh)
        if g.get("builtin"):
            # 只有内置期次进 APK，其余留给「素材包」在线下载，避免 APK 越滚越大
            open(os.path.join(OUT_APK, g["id"] + ".txt"), "w", encoding="utf-8").write(en)
            open(os.path.join(OUT_APK, g["id"] + ".zh.txt"), "w", encoding="utf-8").write(zh)

        items.append(dict(
            id=g["id"], title=g["title"], titleZh=g["titleZh"], topic=g["topic"],
            level=g["level"], words=total, builtin=bool(g.get("builtin")),
            bytes=os.path.getsize(os.path.join(OUT_REPO, g["id"] + ".txt"))
                  + os.path.getsize(os.path.join(OUT_REPO, g["id"] + ".zh.txt")),
            text="articles/%s.txt" % g["id"],
            translation="articles/%s.zh.txt" % g["id"],
            license="public-domain",
        ))
        print("%s  %-24s %3d 词  %s" % (g["id"], g["titleZh"], total,
                                        "内置" if g.get("builtin") else "在线"))

    manifest = dict(version=1, updated="2026-09-22", baseUrl=BASE_URL,
                    note="简译 App 阅读素材清单。builtin=true 的已内置在 APK，"
                         "其余由「素材包」开关联网下载到手机。",
                    items=items)
    # APK 里叫 manifest.json（App 读这个）；仓库根用 reading.json，免得和听力清单互相覆盖
    open(os.path.join(OUT_APK, "manifest.json"), "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=2))
    open(os.path.join(WS + r"\repo-v2.0", "reading.json"), "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=2))

    print("\n=== 每篇首句（用来核对梗概有没有对上）===")
    for g in GROUPS:
        for name in g["fables"]:
            if name in fables:
                ps, n = fables[name]
                print("* %-34s %4d 词 | %s" % (name, n, ps[0][:80]))


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""扫描简译工程源码，生成单文件 HTML 源码导读（双击浏览器即可阅读）"""
import html
import os
import re

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


OUT = DESKTOP + r"\简译-源码导读.html"

# (路径, 分组, 一句话用途)
FILES = [
    (PROJ + r"\app\src\main\java\com\jianyi\dict\MainActivity.java", "核心代码",
     "App 的总控台。界面上每个按钮点下去会发生什么，全在这一个文件里：查词、翻译（离线+云端）、拍照识别、历史记录、崩溃日志。最大也最值得看的一个。"),
    (PROJ + r"\app\src\main\java\com\jianyi\dict\DictDb.java", "核心代码",
     "离线词典的开箱与查表。首次启动把内置的 20.7MB 词典包解压到手机，之后按单词查释义、按前缀猜你想查的词。"),
    (PROJ + r"\app\src\main\java\com\jianyi\dict\HistoryDb.java", "核心代码",
     "查词的小本子。本地数据库，最多记 200 条，支持长按删单条。"),
    (PROJ + r"\app\src\main\java\com\jianyi\dict\EdgeTts.java", "核心代码",
     "单词发音的引擎。自己手写了一个迷你 WebSocket 客户端，连微软 Edge 的朗读服务拿神经语音（英美各男女声）。不引第三方库，所以 Android 上也能跑。"),
    (PROJ + r"\app\src\main\java\com\jianyi\dict\Pron.java", "核心代码",
     "发音管家。记住你选的音色、把发音结果存到手机里（下次点同一个词秒出）、查词时提前偷偷取回发音、用播放器放出来。"),
    (PROJ + r"\app\src\main\java\com\jianyi\dict\ThemeHelper.java", "核心代码",
     "外观开关。跟随系统 / 浅色 / 深色三选一，靠改写系统的深色标志实现，不依赖 AndroidX。"),
    (PROJ + r"\app\src\main\java\com\jianyi\dict\SwipeHost.java", "核心代码",
     "三页横向滑动的容器。手写的：跟手拖拽、松手吸附、滑到头有橡皮筋回弹；横向移动大过竖向才接管手势，所以列表滚动和打字不受影响。"),

    (PROJ + r"\app\src\main\res\layout\activity_main.xml", "界面与资源",
     "界面长什么样。四个页面（查词 / 翻译 / 拍照 / 历史）上每个输入框、按钮、文字框的位置和样式。"),
    (PROJ + r"\app\src\main\res\values\colors.xml", "界面与资源",
     "浅色模式的配色表。深色模式的对应值在 values-night/colors.xml（同名资源会被自动覆盖）。"),
    (PROJ + r"\app\src\main\res\values-night\colors.xml", "界面与资源",
     "深色模式的配色表。只覆盖需要变的颜色：背景、文字、分隔线，以及六级/高频标记色。"),
    (PROJ + r"\app\src\main\res\values\themes.xml", "界面与资源",
     "浅色主题（状态栏怎么显示、窗口底色等）。"),
    (PROJ + r"\app\src\main\res\values-night\themes.xml", "界面与资源",
     "深色主题。基底换成 Material 深色，状态栏图标自动反白。"),
    (PROJ + r"\app\src\main\res\values\strings.xml", "界面与资源",
     "界面上的文字。App 名称、默认提示语等。"),
    (PROJ + r"\app\src\main\res\drawable\ic_speaker.xml", "界面与资源",
     "结果卡片上的小喇叭图标（矢量图，颜色跟随深浅色自动变）。"),
    (PROJ + r"\app\src\main\res\mipmap-anydpi-v26\ic_launcher.xml", "界面与资源",
     "图标（新版安卓的自适应图标定义）。"),
    (PROJ + r"\app\src\main\res\drawable\ic_launcher_foreground.xml", "界面与资源",
     "图标的图案部分（一个矢量图形，不依赖图片文件）。"),

    (PROJ + r"\app\src\main\AndroidManifest.xml", "工程配置",
     "App 的身份证与权限声明。告诉安卓：这 App 叫什么、图标是哪个、启动页是哪个、要哪几个权限。"),
    (PROJ + r"\app\build.gradle.kts", "工程配置",
     "模块构建配置。版本号、最低支持安卓版本、引用了哪两个 Google 离线引擎、只打包 64 位 ARM 架构、词典不能被压缩（这行有血泪史）。"),
    (PROJ + r"\build.gradle.kts", "工程配置",
     "工程级构建配置（插件来源）。"),
    (PROJ + r"\settings.gradle.kts", "工程配置",
     "告诉 Gradle 这个工程包含哪些模块、去哪下依赖。"),
    (PROJ + r"\gradle.properties", "工程配置",
     "全局开关。比如是否启用 AndroidX、分配给 Gradle 多少内存。"),

    (WS + r"\tools\build_dict.py", "辅助工具",
     "词典制作脚本。从 340 万条 ECDICT 原始数据里筛出 35.5 万条常用词，压缩成 App 内置的词典包。不属于 App 本体，是「备料」工具。"),
    (WS + r"\tools\diff_apk.py", "辅助工具",
     "两个 APK 版本的差异比对工具。上一轮就是用它证明 v1.3.0 和 v1.2.4 功能一模一样。"),
    (WS + r"\tools\verify_p2a.py", "辅助工具",
     "出包后的自检脚本：确认发音引擎、深色资源、标记标签真的打进 APK 了（构建成功 ≠ 功能进包）。"),
    (WS + r"\tools\EdgeTtsProbe.java", "辅助工具",
     "发音协议的独立探针。在电脑上直接跑，验证微软那边通不通、快不快，不依赖手机。"),
    (WS + r"\tools\test_zh_lookup.py", "辅助工具",
     "中英反查的性能测试。用它在电脑上量出「拿中文搜 35.5 万条释义」到底多快、结果准不准。"),
]

JAVA_KW = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while",
    "true", "false", "null", "var", "val", "fun", "when", "object", "override", "open",
    "sealed", "suspend", "Unit", "String", "Object", "Integer", "Boolean", "Long",
}
PY_KW = {
    "def", "return", "if", "elif", "else", "for", "while", "import", "from", "as", "with",
    "try", "except", "finally", "class", "lambda", "None", "True", "False", "and", "or",
    "not", "in", "is", "global", "pass", "break", "continue", "raise", "yield", "assert",
    "del", "async", "await", "print", "self",
}

CLS = {"comment": "c", "string": "s", "anno": "a", "kw": "k", "num": "n",
       "tag": "t", "attr": "at", "entity": "e"}


def esc(t):
    return html.escape(t, quote=False)


def _make_lines():
    return [[]]


def _push(lines, text, cls=None):
    """把一段文本按行拆开塞进 lines（跨行 token 也能正确包 span）"""
    parts = text.split("\n")
    for i, p in enumerate(parts):
        if i > 0:
            lines.append([])
        if p:
            lines[-1].append('<span class="%s">%s</span>' % (cls, esc(p)) if cls else esc(p))


def highlight(src, mode):
    if mode == "java":
        pattern = r"""
          (?P<comment>/\*[\s\S]*?\*/|//[^\n]*)|
          (?P<string>"(?:\\.|[^"\\\n])*")|
          (?P<anno>@[A-Za-z_]\w*)|
          (?P<kw>\b(?:%s)\b)|
          (?P<num>\b(?:0[xX][0-9a-fA-F]+|\d+(?:\.\d+)?[fFdDlL]?)\b)
        """ % "|".join(sorted(JAVA_KW, key=len, reverse=True))
    elif mode == "python":
        pattern = r"""
          (?P<comment>\#[^\n]*)|
          (?P<string>""" + '"""' + r'[\s\S]*?' + '"""' + r"""|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|
          (?P<anno>@[A-Za-z_]\w*)|
          (?P<kw>\b(?:%s)\b)|
          (?P<num>\b\d+(?:\.\d+)?\b)
        """ % "|".join(sorted(PY_KW, key=len, reverse=True))
    elif mode == "xml":
        pattern = r"""
          (?P<comment><!--[\s\S]*?-->)|
          (?P<string>"[^"]*")|
          (?P<tag></?[A-Za-z_][\w:.-]*)|
          (?P<attr>[A-Za-z_][\w:.-]*(?=\s*=))|
          (?P<entity>&[#\w]+;)|
          (?P<num>\b\d+(?:\.\d+)?(?:dp|sp|dip|px|%|)\b)
        """
    else:  # props
        pattern = r"""
          (?P<comment>\#[^\n]*)|
          (?P<attr>^[\w.\-]+(?=\s*[=:]))|
          (?P<kw>\b(?:true|false)\b)
        """
    tok = re.compile(pattern, re.X | re.M)
    lines = _make_lines()
    pos = 0
    for m in tok.finditer(src):
        if m.start() > pos:
            _push(lines, src[pos:m.start()])
        _push(lines, m.group(), CLS[m.lastgroup])
        pos = m.end()
    _push(lines, src[pos:])
    return lines


def mode_of(path):
    ext = os.path.splitext(path)[1].lower()
    if ext == ".java" or ext == ".kts":
        return "java"
    if ext == ".py":
        return "python"
    if ext == ".xml":
        return "xml"
    return "props"


def main():
    entries = []
    for path, group, desc in FILES:
        if not os.path.isfile(path):
            print("  [跳过·文件不存在] %s" % path)
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            src = f.read()
        src = src.replace("\r\n", "\n").replace("\r", "\n")
        lines = highlight(src, mode_of(path))
        entries.append({
            "path": path,
            "group": group,
            "desc": desc,
            "name": os.path.basename(path),
            "nlines": len(lines),
            "bytes": os.path.getsize(path),
            # 注意：片段之间不能加换行符 —— pre 环境下换行会成为真的空行，
            # 而 .l 本身是 block，会渲染出双倍行距。
            "html": "".join(
                '<span class="l" data-n="%d">%s</span>' % (i + 1, "".join(c) or "&#8203;")
                for i, c in enumerate(lines)),
        })

    total_lines = sum(e["nlines"] for e in entries)
    total_bytes = sum(e["bytes"] for e in entries)

    groups = []
    for e in entries:
        if e["group"] not in groups:
            groups.append(e["group"])

    # ---- 侧边栏 ----
    nav = []
    for g in groups:
        nav.append('<div class="navg">%s</div>' % esc(g))
        for i, e in enumerate(entries):
            if e["group"] != g:
                continue
            nav.append(
                '<a class="navi" data-i="%d" href="#f%d" title="%s">'
                '<span class="nm">%s</span><span class="ln">%d 行</span></a>'
                % (i, i, esc(e["path"]), esc(e["name"]), e["nlines"]))
    nav = "\n".join(nav)

    # ---- 文件地图（导读页）----
    map_rows = []
    for g in groups:
        map_rows.append('<tr class="gh"><td colspan="3">%s</td></tr>' % esc(g))
        for i, e in enumerate(entries):
            if e["group"] != g:
                continue
            map_rows.append(
                '<tr><td class="fn"><a href="#f%d">%s</a></td>'
                '<td class="num">%d</td><td class="ds">%s</td></tr>'
                % (i, esc(e["name"]), e["nlines"], esc(e["desc"])))
    map_rows = "\n".join(map_rows)

    # ---- 代码页 ----
    panes = []
    for i, e in enumerate(entries):
        panes.append(
            '<section class="pane" id="f%d" hidden>'
            '<div class="phead"><div class="ptitle">%s</div>'
            '<div class="pmeta">%d 行 · %.1f KB · <span class="path">%s</span></div></div>'
            '<div class="pdesc">%s</div>'
            '<div class="codewrap"><pre class="code">%s</pre></div>'
            '</section>' % (i, esc(e["name"]), e["nlines"], e["bytes"] / 1024.0,
                            esc(e["path"]), esc(e["desc"]), e["html"]))
    panes = "\n".join(panes)

    doc = (HTML_HEAD
           .replace("__NFILE__", str(len(entries)))
           .replace("__NLINES__", str(total_lines))
           .replace("__KB__", "%.0f" % (total_bytes / 1024.0))
           .replace("__NAV__", nav)
           .replace("__MAPROWS__", map_rows)
           .replace("__PANES__", panes)
           .replace("__PROJPATH__", PROJ))

    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(doc)
    print("生成完成：%s" % OUT)
    print("文件数 %d，代码 %d 行，%.0f KB，页面 %.0f KB"
          % (len(entries), total_lines, total_bytes / 1024.0,
             os.path.getsize(OUT) / 1024.0))


HTML_HEAD = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>简译 · 源码导读</title>
<style>
  :root {
    --bg: #ffffff;
    --panel: #f6f7f9;
    --line: #e4e6eb;
    --ink: #1f2328;
    --dim: #6b7280;
    --accent: #0a6cff;
    --code-bg: #ffffff;
    --c-k: #0550ae;   /* 关键字 */
    --c-s: #0a7b3e;   /* 字符串 */
    --c-c: #8b949e;   /* 注释 */
    --c-n: #953800;   /* 数字 */
    --c-a: #8250df;   /* 注解 */
    --c-t: #116329;   /* 标签 */
    --c-at: #0a6cff;  /* 属性 */
    --c-e: #953800;   /* 实体 */
  }
  * { box-sizing: border-box; }
  html, body { height: 100%; margin: 0; }
  body {
    background: var(--bg); color: var(--ink);
    font-family: "Segoe UI", "Microsoft YaHei", system-ui, sans-serif;
    font-size: 14px; line-height: 1.6;
  }
  #app { display: flex; height: 100vh; overflow: hidden; }

  /* 侧边栏 */
  #side {
    width: 298px; flex: none; background: var(--panel);
    border-right: 1px solid var(--line);
    display: flex; flex-direction: column;
  }
  .brand { padding: 16px 16px 10px; }
  .bt { font-weight: 650; font-size: 15px; letter-spacing: .2px; }
  .bs { color: var(--dim); font-size: 12px; margin-top: 3px; }
  #q {
    margin: 0 12px 10px; padding: 7px 10px; font: inherit; font-size: 13px;
    border: 1px solid var(--line); border-radius: 7px; background: #fff; color: var(--ink);
    outline: none;
  }
  #q:focus { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(10,108,255,.10); }
  #nav { overflow-y: auto; padding: 0 8px 20px; flex: 1; }
  .navg {
    font-size: 11px; font-weight: 650; color: var(--dim); letter-spacing: .8px;
    padding: 12px 8px 5px; text-transform: uppercase;
  }
  .navi {
    display: flex; justify-content: space-between; align-items: baseline; gap: 8px;
    padding: 6px 9px; border-radius: 7px; text-decoration: none; color: var(--ink);
    font-size: 13px;
  }
  .navi:hover { background: #eceef2; }
  .navi.on { background: #e2ecff; color: #0a4fc4; font-weight: 600; }
  .navi.home { margin: 4px 8px 0; font-weight: 600; }
  .navi .ln { color: var(--dim); font-size: 11px; flex: none; }
  .navi.on .ln { color: #5b82c9; }

  /* 主区 */
  #main { flex: 1; overflow-y: auto; }
  .pane { padding: 26px 34px 60px; max-width: 1180px; }
  h1 { font-size: 21px; margin: 0 0 10px; letter-spacing: .2px; }
  .lead { color: #3d444d; margin: 0 0 20px; max-width: 760px; }
  .lead b { color: var(--ink); }

  table.map { border-collapse: collapse; width: 100%; max-width: 900px; margin: 0 0 22px; }
  table.map th {
    text-align: left; font-size: 12px; color: var(--dim); font-weight: 600;
    border-bottom: 1px solid var(--line); padding: 6px 10px;
  }
  table.map td { padding: 7px 10px; border-bottom: 1px solid #f0f1f3; vertical-align: top; }
  tr.gh td {
    background: #f6f7f9; font-size: 11px; font-weight: 650; color: var(--dim);
    letter-spacing: .8px; text-transform: uppercase; padding: 5px 10px;
  }
  td.fn { font-family: "Cascadia Mono", Consolas, monospace; font-size: 12.5px; white-space: nowrap; }
  td.fn a { color: var(--accent); text-decoration: none; }
  td.fn a:hover { text-decoration: underline; }
  td.num { color: var(--dim); font-size: 12px; text-align: right; white-space: nowrap; }
  td.ds { color: #3d444d; font-size: 13px; }

  .content {
    background: #f6f7f9; border: 1px solid var(--line); border-radius: 10px;
    padding: 14px 16px; max-width: 900px; font-size: 13px; color: #3d444d;
  }
  kbd {
    background: #fff; border: 1px solid #c9ccd1; border-bottom-width: 2px;
    border-radius: 4px; padding: 0 5px; font-size: 11.5px; font-family: inherit;
  }

  /* 代码 */
  .phead { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
  .ptitle { font-family: "Cascadia Mono", Consolas, monospace; font-size: 16px; font-weight: 650; }
  .pmeta { color: var(--dim); font-size: 12px; }
  .pdesc {
    margin: 8px 0 16px; padding: 10px 14px; background: #f6f7f9;
    border-left: 3px solid var(--accent); border-radius: 0 8px 8px 0;
    font-size: 13px; color: #3d444d; max-width: 900px;
  }
  .codewrap { border: 1px solid var(--line); border-radius: 10px; overflow: auto; max-height: 74vh; }
  pre.code {
    margin: 0; padding: 14px 0 14px 0; background: var(--code-bg);
    font-family: "Cascadia Mono", Consolas, "Courier New", monospace;
    font-size: 12.5px; line-height: 1.62; tab-size: 4;
  }
  .l { display: block; white-space: pre; padding: 0 18px 0 4.6em; position: relative; min-height: 1.62em; }
  .l::before {
    content: attr(data-n); position: absolute; left: 0; width: 3.6em; text-align: right;
    color: #b6bcc6; user-select: none; -webkit-user-select: none;
  }
  .l:hover { background: #fafbfc; }
  .path { font-family: "Cascadia Mono", Consolas, monospace; font-size: 11.5px; color: #6b7280; }
  .c { color: var(--c-c); font-style: italic; }
  .s { color: var(--c-s); }
  .k { color: var(--c-k); }
  .n { color: var(--c-n); }
  .a { color: var(--c-a); }
  .t { color: var(--c-t); }
  .at { color: var(--c-at); }
  .e { color: var(--c-e); }

  @media (max-width: 900px) {
    #side { width: 210px; }
    .pane { padding: 18px 16px 50px; }
  }
</style>
</head>
<body>
<div id="app">
  <aside id="side">
    <div class="brand">
      <div class="bt">简译 · 源码导读</div>
      <div class="bs">__NFILE__ 个文件 · __NLINES__ 行 · __KB__ KB</div>
    </div>
    <input id="q" type="text" placeholder="筛选文件名…" autocomplete="off">
    <nav id="nav">
      <a class="navi home" data-i="-1" href="#home"><span class="nm">▸ 文件地图（先看这个）</span></a>
      __NAV__
    </nav>
  </aside>
  <main id="main">
    <section class="pane" id="home">
      <h1>简译 App 源码地图</h1>
      <p class="lead">这份页面把整个 App 的源代码都装进来了，带高亮和行号，左边点文件名就能看。
      代码总量不大——<b>__NLINES__ 行</b>、<b>__KB__ KB</b>，其中真正的业务逻辑只集中在 3 个 Java 文件里，其余都是界面和配置。</p>
      <table class="map">
        <thead><tr><th>文件</th><th>行数</th><th>它管什么</th></tr></thead>
        <tbody>__MAPROWS__</tbody>
      </table>
      <div class="note">
        <b>先看哪个？</b>想搞懂功能怎么跑的，按 <i>MainActivity.java → DictDb.java → activity_main.xml</i> 的顺序看。想改颜色或文案，直接翻 <i>colors.xml / strings.xml</i>。
        <br><br>
        <b>想改代码？</b>这里只能看，改不了。用 VS Code 打开工程文件夹 <span class="path">__PROJPATH__</span> 改，改完告诉我重新打包。
        <br><br>
        <b>找不到想看的？</b>按 <kbd>Ctrl</kbd>+<kbd>F</kbd> 在当前文件里搜；要全局搜索就用 VS Code。
        <br><br>
        <b>词典数据说明：</b><span class="path">app\src\main\assets\db\dict.dbz</span>（20.7 MB）是 35.5 万条词典数据，不是代码，别用记事本打开会卡死。
      </div>
    </section>
    __PANES__
  </main>
</div>

<script>
(function () {
  var panes = document.querySelectorAll('.pane');
  var navs = document.querySelectorAll('.navi');

  function show(id) {
    var found = false;
    for (var i = 0; i < panes.length; i++) {
      var on = panes[i].id === id;
      panes[i].hidden = !on;
      if (on) { found = true; }
    }
    if (!found) { panes[0].hidden = false; }
    for (var j = 0; j < navs.length; j++) {
      navs[j].classList.toggle('on', navs[j].getAttribute('href') === '#' + id);
    }
    document.getElementById('main').scrollTop = 0;
  }

  function route() {
    var id = (location.hash || '#home').slice(1);
    show(id);
  }
  window.addEventListener('hashchange', route);
  route();

  var q = document.getElementById('q');
  q.addEventListener('input', function () {
    var v = q.value.trim().toLowerCase();
    var items = document.querySelectorAll('#nav .navi:not(.home)');
    for (var i = 0; i < items.length; i++) {
      var t = items[i].textContent.toLowerCase();
      items[i].style.display = (v && t.indexOf(v) < 0) ? 'none' : '';
    }
    var heads = document.querySelectorAll('#nav .navg');
    for (var k = 0; k < heads.length; k++) { heads[k].style.display = v ? 'none' : ''; }
  });
})();
</script>
</body>
</html>
"""

if __name__ == "__main__":
    main()

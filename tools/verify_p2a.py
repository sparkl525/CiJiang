# -*- coding: utf-8 -*-
"""核验 v1.4.0 APK：确认发音引擎、深色资源、标记标签真的打进去了。"""
import sys
import zipfile
import os

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


apk = sys.argv[1] if len(sys.argv) > 1 else PROJ + r"\app\build\outputs\apk\release\app-release.apk"
z = zipfile.ZipFile(apk)
names = z.namelist()
print("APK:", apk)
print("条目总数:", len(names))

# 1) 深色资源
night = sorted(n for n in names if "-night" in n)
print("\n[1] 深色资源(values-night):", len(night), "项")
for n in night[:20]:
    print("    ", n)

# 2) 发音引擎（代码字符串在 dex 里）
dex = [n for n in names if n.endswith(".dex")]
print("\n[2] 发音引擎代码:")
kws = [b"RyanNeural", b"SoniaNeural", b"GuyNeural", b"AriaNeural",
       b"speech.platform.bing.com", b"Sec-MS-GEC", b"TrustedClientToken",
       b"audio-24khz-48kbitrate-mono-mp3"]
hit = set()
for d in dex:
    data = z.read(d)
    for kw in kws:
        if kw in data:
            hit.add(kw.decode())
for kw in kws:
    print("    ", kw.decode().ljust(34), "OK" if kw.decode() in hit else "缺失")

# 3) 资源 ID（图标、颜色、设置入口）
arsc = z.read("resources.arsc")
print("\n[3] 资源表:")
for kw in [b"ic_speaker", b"mark_cet6", b"mark_high", b"on_accent", b"btnSettings", b"bg"]:
    print("    ", kw.decode().ljust(14), "OK" if kw in arsc else "缺失")

# 4) 权限
print("\n[4] 权限与结构:")
print("     dex 数量:", len(dex))
print("     词典 dict.dbz:", any(n.endswith("dict.dbz") for n in names))
print("     合并 dex 大小: %.2f MB" % (sum(z.getinfo(d).file_size for d in dex) / 1048576))
print("     APK 总大小: %.2f MB" % (sum(z.getinfo(n).file_size for n in names) / 1048576))

# -*- coding: utf-8 -*-
"""统计简译 APK 内部构成 + 词典解压后体积，用于回答"数据为什么这么大"。
不落临时文件：gzip 流式计数，只取解压后字节数。
"""
import gzip
import io
import os
import zipfile

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


APK = PROJ + r"\app\build\outputs\apk\release\app-release.apk"
DBZ = PROJ + r"\app\src\main\assets\db\dict.dbz"


def mb(n):
    return n / (1024 * 1024)


print("=== APK 内部 TOP 占用 ===")
items = []
with zipfile.ZipFile(APK) as z:
    for i in z.infolist():
        items.append((i.file_size, i.filename, i.compress_type))
items.sort(reverse=True)
total = sum(i[0] for i in items)
for size, name, ct in items[:14]:
    kind = "存储" if ct == zipfile.ZIP_STORED else "压缩"
    print(f"  {mb(size):8.2f} MB  [{kind}]  {name}")
print(f"  APK 内文件总大小：{mb(total):.2f} MB（实际 APK 文件 {mb(os.path.getsize(APK)):.2f} MB）\n")

print("=== 词典 ===")
raw = os.path.getsize(DBZ)
n = 0
with gzip.open(DBZ, "rb") as f:
    while True:
        b = f.read(1 << 20)
        if not b:
            break
        n += len(b)
print(f"  dict.dbz（打包内，gzip）：{mb(raw):.2f} MB")
print(f"  解压后 dict.db（落在手机 files 目录）：{mb(n):.2f} MB")

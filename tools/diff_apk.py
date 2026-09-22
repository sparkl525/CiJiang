"""对比两个 APK 的内部结构差异（用于版本间"到底改了什么"的客观核验）"""
import zipfile
import os

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


A = DESKTOP + r"\简译-v1.2.4.apk"
B = DESKTOP + r"\简译-v1.3.0.apk"

za, zb = zipfile.ZipFile(A), zipfile.ZipFile(B)
ia = {i.filename: i for i in za.infolist()}
ib = {i.filename: i for i in zb.infolist()}

na = sum(i.file_size for i in ia.values())
nb = sum(i.file_size for i in ib.values())
print("== 总体 ==")
print("v1.2.4  条目 %d  解压总量 %.1f MB  压缩后 %.1f MB"
      % (len(ia), na / 1048576, sum(i.compress_size for i in ia.values()) / 1048576))
print("v1.3.0  条目 %d  解压总量 %.1f MB  压缩后 %.1f MB"
      % (len(ib), nb / 1048576, sum(i.compress_size for i in ib.values()) / 1048576))
print()

print("== 只在某一边存在的条目 ==")
only_a = sorted(set(ia) - set(ib))
only_b = sorted(set(ib) - set(ia))
print("仅 v1.2.4 有 (%d): %s" % (len(only_a), only_a[:30]))
print("仅 v1.3.0 有 (%d): %s" % (len(only_b), only_b[:30]))
print()

rows = []
for n in sorted(set(ia) & set(ib)):
    x, y = ia[n], ib[n]
    if (x.file_size != y.file_size or x.compress_size != y.compress_size
            or x.compress_type != y.compress_type or x.CRC != y.CRC):
        rows.append((n, x.file_size, y.file_size, x.compress_size, y.compress_size,
                     x.compress_type, y.compress_type))

print("== 内容有差异的条目：%d / %d ==" % (len(rows), len(set(ia) & set(ib))))
for r in sorted(rows, key=lambda r: -abs(r[2] - r[1]))[:50]:
    print("  %-64s raw %9d -> %9d | comp %9d -> %9d | method %d -> %d" % r)

same = len(set(ia) & set(ib)) - len(rows)
print()
print("完全相同(字节级)的条目：%d" % same)
print()

# 词典资源必须以 method 0 存储
for tag, z in (("v1.2.4", za), ("v1.3.0", zb)):
    for n in z.namelist():
        if n.endswith(".dbz") or n.endswith("dict.db"):
            i = z.getinfo(n)
            print("%s  %s : method=%d (0=存储) size=%.1fMB" %
                  (tag, n, i.compress_type, i.file_size / 1048576))

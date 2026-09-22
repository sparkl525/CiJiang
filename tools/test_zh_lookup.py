# -*- coding: utf-8 -*-
"""中英反查性能测试：中文 LIKE 全表扫描在词典上到底多快、结果质量如何。"""
import gzip
import os
import sqlite3
import time

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")


SRC = PROJ + r"\app\src\main\assets\db\dict.dbz"
DB = WS + r"\logs\dict_test.db"

if not os.path.exists(DB):
    t0 = time.time()
    with gzip.open(SRC, "rb") as f, open(DB, "wb") as out:
        while True:
            b = f.read(1 << 16)
            if not b:
                break
            out.write(b)
    print("解压词典 %.1fs -> %s (%.1f MB)" % (time.time() - t0, DB, os.path.getsize(DB) / 1048576))

con = sqlite3.connect(DB)

QUERIES = ["猫", "苹果", "打", "漂亮", "电脑", "帮助", "猫头鹰", "愉快"]

print("\n=== 按 frq 升序（高频优先）LIKE 反查 ===")
for q in QUERIES:
    t0 = time.time()
    rows = con.execute(
        "SELECT word, frq, translation FROM stardict "
        "WHERE translation LIKE ? AND frq > 0 ORDER BY frq LIMIT 8",
        ("%" + q + "%",),
    ).fetchall()
    ms = (time.time() - t0) * 1000
    top = ", ".join(w for w, _, _ in rows[:5])
    print("  %-4s %6.0f ms  %2d 条  前五: %s" % (q, ms, len(rows), top))

print("\n=== 单次最坏情况（含无 frq 词条） ===")
t0 = time.time()
n = con.execute(
    "SELECT COUNT(*) FROM stardict WHERE translation LIKE ?", ("%的%",)
).fetchone()[0]
print("  匹配「的」: %d 条, %.0f ms" % (n, (time.time() - t0) * 1000))

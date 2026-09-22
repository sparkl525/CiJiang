import asyncio, edge_tts, os
import os

# ─── 路径推导：公开仓库里不写死个人路径，一律相对脚本位置算出来 ───
_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)                  # 简译工程根目录
WS = os.path.dirname(PROJ)                      # 开发工作区（外部素材/日志放这）
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")

VOICES = {
    "英式男声": "en-GB-RyanNeural",
    "英式女声": "en-GB-SoniaNeural",
    "美式男声": "en-US-GuyNeural",
    "美式女声": "en-US-AriaNeural",
}
OUT = WS + r"\logs\tts_test"
os.makedirs(OUT, exist_ok=True)
async def main():
    for name, v in VOICES.items():
        try:
            c = edge_tts.Communicate("abandon", v)
            data = b""
            async for chunk in c.stream():
                if chunk["type"] == "audio":
                    data += chunk["data"]
            p = os.path.join(OUT, "%s.mp3" % name)
            open(p, "wb").write(data)
            head = data[:3]
            ok = head == b"ID3" or data[:2] == b"\xff\xfb" or data[:2] == b"\xff\xf3"
            print("  %s %-18s %7d bytes  头=%s  %s" % ("OK " if ok else "BAD", v, len(data), head.hex(), name))
        except Exception as e:
            print("  FAIL %-18s %s: %s" % (v, type(e).__name__, str(e)[:80]))
asyncio.run(main())

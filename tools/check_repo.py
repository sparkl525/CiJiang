# -*- coding: utf-8 -*-
"""验证素材仓库是否可匿名访问（App 端要靠这个拉文件）。仓库名以 '-' 开头，命令行里不好处理，所以写成脚本。"""
import json
import ssl
import urllib.request

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
OWNER, REPO = "sparkl525", "-v2.0"
UA = {"User-Agent": "jianyi-app/1.0"}


def head(url):
    try:
        req = urllib.request.Request(url, headers=UA, method="HEAD")
        with urllib.request.urlopen(req, timeout=25, context=CTX) as r:
            return r.status, r.headers.get("Content-Length")
    except Exception as e:
        return "ERR", str(e)[:60]


print("=== 仓库是否存在 ===")
try:
    req = urllib.request.Request("https://api.github.com/repos/%s/%s" % (OWNER, REPO), headers=UA)
    with urllib.request.urlopen(req, timeout=25, context=CTX) as r:
        info = json.load(r)
    print("仓库名：", info.get("full_name"))
    print("私有：", info.get("private"), "（必须是 False，App 才能匿名拉取）")
    print("默认分支：", info.get("default_branch"))
    print("创建时间：", info.get("created_at"))
    print("大小：", info.get("size"), "KB")
except Exception as e:
    print("查不到仓库：", str(e)[:90])

print("\n=== 各下载通道连通性 ===")
for name, url in [
    ("raw 直取 manifest", "https://raw.githubusercontent.com/%s/%s/main/manifest.json" % (OWNER, REPO)),
    ("jsDelivr CDN manifest", "https://cdn.jsdelivr.net/gh/%s/%s@main/manifest.json" % (OWNER, REPO)),
    ("仓库主页", "https://github.com/%s/%s" % (OWNER, REPO)),
]:
    st, extra = head(url)
    print("%-22s %s %s" % (name, st, extra if st == "ERR" else ""))

# -*- coding: utf-8 -*-
"""进包校验：dex 里确认新增符号存在、已删除功能的符号不存在；resources.arsc 的字符串也应同步。"""
import os, zipfile, sys

apk = os.path.join(os.environ.get("TEMP", "/tmp"), "chk.apk")
z = zipfile.ZipFile(apk)

dex = b""
for n in z.namelist():
    if n.endswith(".dex"):
        dex += z.read(n)
arsc = z.read("resources.arsc") if "resources.arsc" in z.namelist() else b""

def has(name, enc="utf-8"):
    b = name.encode(enc)
    return (b in dex) or (b in arsc)

# ---- 必须存在（v1.0.11 新增/保留） ----
MUST = [
    # 广告拦截（点击劫持剥离）
    "nav-redirect-config", "stripRedirectHijack", "stripNode", "isDocument",
    # ★ v1.0.10：GTM 广告源拦截
    "stripGtm", "googletagmanager.com", "googletagservices.com",
    "google-analytics.com", "GTM-", "aswift",
    # ★ v1.0.11：GTM <noscript> 包裹也要删
    "noscript",
    # 广告域名
    "1wm.top", "hai8g.com",
    # 书架隐藏
    "我的書架", "我的书架", "dashboard", "shelf",
    "aria-label",
    # 隐藏右上角书架的 CSS 选择器（站点 href 指向 m.xipmh.com/dashboard；
    # v1.0.10 起选择器放宽为 a[href*="xipmh.com"] / a[href*="dashboard"]，故不再出现完整拼接串）
    "xipmh.com",
    # ★ v1.0.11：服务端直接删书架锚点（不再依赖客户端 JS，避开 Astro 软导航）
    "stripShelfAnchors",
    # ★ v1.0.11：Astro 软导航补清理
    "astro:after-swap", "astro:page-load",
    # ★ v1.0.10：自建按钮插入位置修正（搜索点击修复）
    "barHost", "insertBefore", "__hipBox",
    # ★ v1.0.11：历史页底部「登入」按钮精确属性
    "data-navbar-sidebar-signin", "navbar-sidebar-user-not-authed",
    # 搜索/记录入口
    "m.hipmh.com/search", "m.hipmh.com/history",
]
# 必须消失（书架/收藏功能已删）
GONE = [
    "setupReaderBar", "showReaderBar", "hideReaderBar", "makeReaderBtn",
    "refreshFavBtn", "applyReaderBarMargin", "openFavList",
    "addFav", "readWorkTitle", "btnFavList", "btnFavToggle", "readerBar",
    "tabFav", "btnClearFav", "setFab", "__hipFab",
    "收藏本页", "我的收藏", "清空我的收藏", "历史与收藏",
]

print("dex total = %d bytes, arsc = %d bytes" % (len(dex), len(arsc)))
print("")
print("== MUST EXIST ==")
bad = 0
for s in MUST:
    # 中文用 utf-16-le 也查一遍（arsc 里是 UTF-16）
    hit = (s.encode("utf-8") in dex) or (s.encode("utf-8") in arsc)
    if not hit:
        hit = (s.encode("utf-16-le") in arsc) or (s.encode("utf-16-le") in dex)
    print("  %s  %s" % ("OK  " if hit else "MISS", s))
    if not hit:
        bad += 1

print("")
print("== MUST BE GONE ==")
for s in GONE:
    hit = (s.encode("utf-8") in dex) or (s.encode("utf-8") in arsc)
    if not hit:
        hit = (s.encode("utf-16-le") in arsc) or (s.encode("utf-16-le") in dex)
    print("  %s  %s" % ("FAIL" if hit else "OK  ", s + ("  <-- 仍存在!" if hit else "")))
    if hit:
        bad += 1

print("")
print("==== %s ====" % ("ALL OK" if bad == 0 else ("%d 项不符" % bad)))
sys.exit(0 if bad == 0 else 1)

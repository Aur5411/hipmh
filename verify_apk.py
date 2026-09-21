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
    #   ★ v1.7：不再自建按钮，改为劫持站点原生「更多」按钮 ——
    #   barHost / insertBefore / __hipBox 已全部移除，不再要求存在。
    # ★ v1.0.11：历史页底部「登入」按钮精确属性
    "data-navbar-sidebar-signin", "navbar-sidebar-user-not-authed",
    # 搜索页仍保留
    "m.hipmh.com/search",
    # ★ v1.8：详情页右上角站点原生「爱心/收藏」按钮（点击要登录）—— 彻底删除
    "stripFavButtons", "mobile-favorite-btn", "favorite-btn",
    "data-action",
    #   「我的收藏」原本是 GONE（v1.30 的旧按钮），v1.8 起改作站点收藏按钮的
    #   aria-label 兜底选择器，因此改为 MUST。
    "我的收藏",
    # ★ v1.6：书架（本地收藏）
    "openFavList", "isFav", "toggleFav", "favKeyFor", "favTitleFor",
    "__hipFavBtn", "__hipFavCss", "data-hipfav",
    "m.hipmh.com/works/", "setFavStatus", "ST_READING", "ST_DONE",
    # ★ v1.8：把站点「更多」抽屉原地改造成本地书架（不再劫持按钮）
    "drawerJs", "DRAWER_JS", "injectDrawer", "refreshDrawer",
    "__hipDrawerCss", "__hipShelfWrap", "__hipDrawerRender",
    "__hipItm", "__hipMark", "__hipTt", "__hipEmpty",
    "navbar-sidebar-history-list", "navbar-sidebar-history-loading",
    "navbar-sidebar-history-empty", "data-navbar-sidebar-content",
    "data-hipurl",
    # ★ v1.8：新增的桥接方法
    "shelfJson", "removeFav", "openUrl", "favMenu",
    "showShelfMenu", "jsonStr",
    # v1.9.1：抽屉条目上的「×」已删，删除只走长按菜单，"已从书架移除" 随之不再触发。
    #   改为校验长按菜单 + 冷却标记。
    "__hipLp",
    # ★ v1.9.2：抽屉布局校正 —— 书架必须从顶部开始排（不再被站点 flex 容器挤到中间）。
    "layoutFix", "insertBefore", "justify-content:flex-start", "align-self:stretch",
    # ★ v1.9.2：阅读页返回按钮改成「真半透明」，且建完就 flash 的旧行为已移除。
    "opacity:.55", "rgba(24,26,32,.22)",
    # ★ v1.9.3：阅读页 URL（reader.hipmh.top 的 /chapter/<b64> 形态）反解作品 id。
    #   ★ v1.10.0 现状：这条链路**只服务 favKeyFor()**（把阅读页地址归一到详情页 key，
    #   让详情页收藏与阅读页收藏落到同一条记录）；v1.9.3 那套「返回时靠它反推详情页
    #   再 loadUrl」的用法已废（见下方 GONE 里的 backToBookNow / bookUrlFromCurrent）。
    "idFromB64",
    # ★ v1.9.3：抽屉布局改用 data-hip-hide 标记（比 inline style 更稳、且不会越界）
    "data-hip-hide",
    # ★ v1.4：阅读页图片预加载（多线程并发抓进 ResCache）
    "preloadImages", "preloadChapter", "preload_chapter",
    "preloadPool", "preloadSeen", "newFixedThreadPool",
    "__hipCollectImgs", "__hipPreload", "__hipImgUrl",
    "ResCache", "isCacheable", "/chapter/", "MutationObserver",
    # ★ v1.5：在途去重 + 下一章预抓（隐藏后台 WebView）
    "inflight", "putIfAbsent", "FutureTask", "doFetch",
    "preloadNext", "preloadNextChapter", "ensureBgWeb",
    "bgWeb", "interceptRes", "injectReaderInto", "readerJs",
    # ★ v1.9：书目封面采集 —— ★ 走站点自己的历史记录数据源 [data-cover-url]
    #   （逆向 _MangaDetailPage.astro + readingHistoryStorage.js 得到），
    #   ⚠️ 绝不能用 img[alt="cover image"]（那是顶部 blur-[70px] 的模糊背景大图）
    "COVER_JS", "injectCover", "setCover", "coverOf",
    "data-cover-url", "data-cover-color", "data-manga-title", "data-manga-path",
    "og:image", "application/ld+json", "blur-\\[",
    "lastCoverUrl", "lastCoverFor", "lastCoverTitle",
    # ★ v1.9：书架列表重做（封面图 + 长按删除）
    "item_shelf", "shelf_delete", "shelf_cover_desc",
    "ListActivity", "coverMem", "coverLoading", "loadCover",
    # ★ v1.9.1：抽屉条目封面 + 封面预热 + 长按防双弹窗
    "__hipCv", "__hipCvPh", "warmCovers", "coverPool",
    "__hipLp", "__hipDrawerRender",
    # ★ v1.9.4：长按书架条目时只允许 JS 的收藏菜单弹出 ——
    #   屏蔽 WebView 自带的 setOnLongClickListener（会在封面 <img> 上命中
    #   HitTestResult.IMAGE_TYPE 而弹出「全屏查看图片」）。
    #   做法：Java 侧 volatile 开关 + 桥接方法 shelfTouch(boolean)，
    #   JS 侧在 touchstart 立即上报 true、touchend/cancel/大幅滑动时上报 false。
    "shelfTouchActive", "shelfTouch",
    "-webkit-touch-callout:none", "pointer-events:none;",
    # ★ v1.10.0：阅读器拆成**独立的第二个 WebView**（rdWeb）——
    #   用户要求「点进漫画 = 新开一个浏览器窗口，返回 = 关闭窗口」。
    #   这样主 WebView 全程停在详情页原地、历史栈干净，
    #   「返回详情页后，再返回又回到阅读页」这个老问题从结构上根除。
    "rdWeb", "setupReaderWeb", "enterReader", "exitReader",
    "readerOpen", "readerUrl", "isReaderUrl", "handleReaderUrl",
    "injectReaderWindow", "applyCommonSettings", "rbSync",
    # ★ v1.10.0：书架抽屉开合上报 + 返回键「先收起抽屉」
    "drawerOpen", "drawerState", "closeDrawer",
    #   站点原生关闭按钮的属性名（实测 Sidebar.astro 绑的 click 目标）
    "data-navbar-sidebar-close",
    # ★ v1.10.0：阅读器窗口的两支注入变体
    "injectT2sInto", "injectCleanInto",
    # ★ v2.0.0：阅读进度（继续阅读）——
    #   阅读器每章实时上报（reportProgress），prog 表所有读过的书都记（不依赖收藏），
    #   详情页把「開始閱讀」换成「继续阅读 第X话 · 共N章」并直达上次章节。
    "reportProgress", "progressOf", "saveProgress",
    "injectDetailProgress", "DETAIL_PROGRESS_JS",
    "chapterIdFromApiHid", "b64UrlEncode", "workUrlIdB64",
    "data-api-hid", "data-total-chapters", "/v2/chapter?hid=",
    "data-hip-cont", "继续阅读",
    "prog",
    # ★ v2.1.0：进度上报重做 ——
    #   直接读 #chapcontent 服务端渲染属性（data-chapter-num 等）同步上报，
    #   fetch 只作兜底且失败清去重标记；bgWeb 预抓窗整块门控不上报；
    #   progressOf 双 key 兜底（长形态详情 URL 查不到按短 key 补查）；
    #   chapterFeHid 把 hid 归一成前端形态。
    "__hipProg", "__hipProgLast", "__hipProgT", "__hipProgObs",
    "data-chapter-num", "data-chapter-number-format", "data-frontend-hid",
    "chapterFeHid", "attributeFilter",
]
# 必须消失
# 注意：v1.6 重新引入了书架/收藏（openFavList、btnClearFav 等），
#       这些老名字已从「必须消失」里移除，改列在上面的 MUST 中。
#       v1.7 起「浏览记录」功能整体下线，相关符号必须彻底消失。
GONE = [
    "setupReaderBar", "showReaderBar", "hideReaderBar", "makeReaderBtn",
    "refreshFavBtn", "applyReaderBarMargin",
    "addFav", "readWorkTitle", "btnFavList", "btnFavToggle", "readerBar",
    "setFab", "__hipFab",
    "收藏本页", "清空我的收藏", "历史与收藏",
    # ★ v1.7：浏览记录下线 —— 这些都必须从 dex / arsc 里消失
    "openHistList", "clearHist",
    "浏览历史", "清空浏览历史", "暂无浏览记录", "历史已清空",
    "btnClearHistory", "历史与书架",
    # ★ v1.7：自建按钮方案已废弃 —— 唯一例外是 __hipBox/__hipShelf/__hipSearch，
    #   它们仍以「清理升级残留」的用途出现在 cleanJs 的 hide 列表里，故意保留，故不列此。
    "barHost",
    # ★ v1.8：劫持「更多」按钮的方案下线 —— 改为原地改造抽屉内容后，
    #   这几个符号必须彻底消失（否则说明旧逻辑还在。
    #   注意 data-navbar-more-trigger 是站点自身的属性名、只用于测试脚本，
    #   不会进 dex，所以不在此列。）
    "rawMoreBtn", "bindMore",
    # ★ v1.9.2：返回按钮不能再被页面级事件闪成实心，也不能有 backdrop-filter
    #   （blur 会把背后的漫画糊成实心感 —— 用户反复反馈「不透明」的元凶）。
    #   注意：收藏星 __hipFavBtn 自己有 blur，那是另一个元素，不能一刀切禁掉。
    "appendChild(b);__hipFlash()",
    # ★ v1.9：设置菜单里删掉的四项（前进 / 后退 / 刷新 / 我的书架）
    #   —— 菜单项文字从 Java 字符串常量里移除后，dex/arsc 中不应再有。
    #   注意：这四项在 ListActivity / 抽屉里各有自己的入口，
    #   它们不是被「功能删除」而是被「菜单入口移除」，
    #   所以只校验这几个纯菜单文案字面量。
    "\u524d\u8fdb", "\u540e\u9000", "\u5237\u65b0",
    "menu_back", "menu_forward", "menu_refresh", "menu_my_shelf",
    # ★ v1.9.1：抽屉条目上的「×」删除按钮已按用户要求移除 ——
    #   删除统一走长按菜单，所以这两个符号必须彻底消失。
    "data-hipdel", "__hipDel",
    # ★ v1.9.3：v1.6 引入的「先回上一章」逻辑已整体删除 ——
    #   用户明确要求阅读页返回一律回书籍详情页，这个方法连同其符号都不应再出现。
    "goBackPrevChapter",
    # ★ v1.10.0：v1.9.3 的「用 loadUrl 硬跳详情页」方案已整体废弃 ——
    #   阅读器现在是独立 WebView，「关窗」即可原地露出详情页，
    #   所以这三个符号必须彻底消失（否则说明旧逻辑还在，
    #   那就会重现「返回详情页后，再返回又是阅读页」的 bug）。
    "backToBookNow", "bookUrlFromCurrent", "lastNonReaderUrl",
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

# -*- coding: utf-8 -*-
"""
test_preload.py —— 阅读页图片预加载（v1.4）+ 在途去重/下一章预抓（v1.5）回归测试
1) 源码接线断言：Prefs 开关 / 线程池 / JsBridge.preloadImages / 设置页绑定 / readerJs 注入函数
2) 算法断言：复刻 __hipImgUrl / __hipCollectImgs，对真实形态的阅读页 <img> 验证
   - 懒加载图（data-src / data-original / data-lazy-src）取真地址，不取占位 src
   - data:/blob: 跳过
   - 非图片（svg 图标）不收
   - 容器内的全部 <img> 都收集（= 预加载"本章所有图片"）
"""
import os, re, sys

PROJ = os.path.dirname(os.path.abspath(__file__))
MAIN = os.path.join(PROJ, "app", "src", "main", "java", "com", "hipmh", "app", "MainActivity.java")
RESCACHE = os.path.join(PROJ, "app", "src", "main", "java", "com", "hipmh", "app", "ResCache.java")
PREFS = os.path.join(PROJ, "app", "src", "main", "java", "com", "hipmh", "app", "Prefs.java")
SETTINGS = os.path.join(PROJ, "app", "src", "main", "java", "com", "hipmh", "app", "SettingsActivity.java")
LAYOUT = os.path.join(PROJ, "app", "src", "main", "res", "layout", "activity_settings.xml")
GRADLE = os.path.join(PROJ, "app", "build.gradle")

ok = 0
fail = 0
def chk(name, cond):
    global ok, fail
    if cond:
        ok += 1
        print("  PASS %s" % name)
    else:
        fail += 1
        print("  FAIL %s" % name)

main = open(MAIN, encoding="utf-8").read()
rescache = open(RESCACHE, encoding="utf-8").read()
prefs = open(PREFS, encoding="utf-8").read()
settings = open(SETTINGS, encoding="utf-8").read()
layout = open(LAYOUT, encoding="utf-8").read()
gradle = open(GRADLE, encoding="utf-8").read()

print("== 1) 源码接线断言 ==")
chk("Prefs 新增 K_PRELOAD", "K_PRELOAD" in prefs)
chk("Prefs.preloadChapter() getter", "boolean preloadChapter()" in prefs)
chk("Prefs.preloadChapter(v) setter", "void preloadChapter(boolean v)" in prefs)
chk("MainActivity 线程池 preloadPool", "ExecutorService preloadPool" in main and "newFixedThreadPool(8)" in main)
chk("MainActivity 去重集合 preloadSeen", "Set<String> preloadSeen" in main and "ConcurrentHashMap.newKeySet()" in main)
chk("JsBridge.preloadImages @JavascriptInterface", '@JavascriptInterface' in main and "public void preloadImages(String json)" in main)
chk("preloadImages 跳过广告", "MainActivity.this.isAd(u)" in main and "MainActivity.this.isPromoImg(u)" in main)
chk("preloadImages 跳过已缓存/已提交", "ResCache.get(u) != null" in main and "preloadSeen.add(u)" in main)
chk("preloadImages 走 ResCache.fetch 并发", "preloadPool.execute" in main and "ResCache.fetch(fu" in main)
chk("shouldInterceptRequest 预加载开启也服务缓存", "prefs.cacheAssets() || prefs.preloadChapter()" in main)
chk("readerJs 注入 __hipCollectImgs", "__hipCollectImgs" in main)
chk("readerJs 注入 __hipImgUrl", "__hipImgUrl" in main)
chk("readerJs 注入 __hipPreload", "__hipPreload" in main)
chk("readerJs 用 MutationObserver 增量预加载", "new MutationObserver" in main and "__hipPreload" in main)
chk("readerJs 限定 /chapter/ 页", "location.pathname.indexOf('/chapter/')" in main)
chk("设置页新增 swPreload 控件", 'android:id="@+id/swPreload"' in layout)
chk("SettingsActivity 绑定 swPreload", "R.id.swPreload, prefs.preloadChapter()" in settings)
chk("版本号 versionCode 15", "versionCode 15" in gradle)
chk("版本号 versionName 1.5", "versionName '1.5'" in gradle)

print("")
print("== 1b) v1.5 在途去重 + 下一章预抓 接线断言 ==")
chk("ResCache 在途去重 inflight 表", "inflight" in rescache and "ConcurrentHashMap<String, Future<byte[]>>" in rescache)
chk("ResCache putIfAbsent 合并并发下载", "putIfAbsent" in rescache)
chk("ResCache FutureTask 实际下载", "FutureTask" in rescache and "doFetch" in rescache)
chk("JsBridge.preloadNext @JavascriptInterface", '@JavascriptInterface' in main and "public void preloadNext(String nextHid)" in main)
chk("MainActivity.preloadNextChapter 后台预抓入口", "preloadNextChapter" in main)
chk("隐藏后台 WebView bgWeb 字段", "private WebView bgWeb" in main)
chk("ensureBgWeb 懒创建后台 WebView", "ensureBgWeb" in main)
chk("后台 WebView 复用共享 interceptRes", "interceptRes(req)" in main)
chk("后台 WebView 注入 allowNext=false", "injectReaderInto(v, u, false)" in main)
chk("主 WebView 注入 allowNext=true", "injectReaderInto(web, u, true)" in main)
chk("readerJs 抓取 next_hid 调 preloadNext", "HipApp.preloadNext(__nh)" in main or "HipApp.preloadNext" in main)
chk("onDestroy 回收 bgWeb", "if (bgWeb != null)" in main and "bgWeb.destroy()" in main)

print("")
print("== 2) 算法断言（复刻 JS 收集逻辑） ==")

def hip_img_url(attrs):
    """复刻 JS __hipImgUrl：按优先级取真地址，跳过 data:/blob: 与空"""
    c = (attrs.get('data-src') or attrs.get('data-original') or attrs.get('data-lazy-src')
         or attrs.get('data-lazy') or attrs.get('data-url') or attrs.get('currentsrc')
         or attrs.get('src') or '')
    if not c or c.startswith('data:') or c.startswith('blob:'):
        return ''
    return c

def parse_imgs(html):
    out = []
    for m in re.finditer(r'(?s)<img\b([^>]*)>', html):
        body = m.group(1)
        attrs = {}
        for am in re.finditer(r'([a-zA-Z0-9_\-]+)\s*=\s*"([^"]*)"', body):
            attrs[am.group(1).lower()] = am.group(2)
        u = hip_img_url(attrs)
        if u:
            out.append(u)
    return out

# 真实形态的阅读页：懒加载图 data-src 为真地址，src 为占位 gif，另含 svg 图标 / data: 占位
sample = """
<div id="chapcontent">
  <div class="chapter-image"><img data-src="https://s3-nl-01.mangabuddy.in/x/001.webp" src="data:image/gif;base64,AAAA"></div>
  <div class="chapter-img-container"><img data-original="https://img.hipmh.com/y/002.jpg" src="/placeholder.png"></div>
  <img data-lazy-src="https://cdn.hipmh.com/z/003.png" src="about:blank">
  <img src="https://s3-nl-01.mangabuddy.in/x/004.webp">
  <img src="data:image/svg+xml;base64,ICON" class="icon">
  <img src="blob:https://m.hipmh.com/abc">
</div>
"""
collected = parse_imgs(sample)
chk("收集到 4 张真实图片（data-src/data-original/data-lazy-src/src）", len(collected) == 4)
chk("取到 data-src 真地址 (001.webp)", any("x/001.webp" in c for c in collected))
chk("取到 data-original 真地址 (002.jpg)", any("y/002.jpg" in c for c in collected))
chk("取到 data-lazy-src 真地址 (003.png)", any("z/003.png" in c for c in collected))
chk("普通 src 也收 (004.webp)", any("x/004.webp" in c for c in collected))
chk("data: svg 图标被跳过", not any("ICON" in c for c in collected))
chk("blob: 被跳过", not any(c.startswith("blob:") for c in collected))
chk("占位 data: gif 未被误当作图片", not any("AAAA" in c for c in collected))

# 懒加载优先：data-src 存在时不应回退到占位 src
lazy = '<img data-src="https://x/real.webp" src="data:image/gif;base64,PLACEHOLDER">'
ci = parse_imgs(lazy)
chk("懒加载：data-src 优先于占位 src", ci == ["https://x/real.webp"])

# 非章节页不应收集（__hipCollectImgs 的 pathname 守卫在 JS 侧，这里验证选择器只在 /chapter/ 触发）
chk("收集函数仅在 /chapter/ 调用（pathname 守卫存在）",
    "location.pathname.indexOf('/chapter/')<0)return '[]'" in main)

print("")
print("== 结果：%d PASS / %d FAIL ==" % (ok, fail))
sys.exit(1 if fail else 0)

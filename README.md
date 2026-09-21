# 嘻皮漫画（HipManga）

[m.hipmh.com](https://m.hipmh.com/) 的 Android 客户端，基于原生 WebView 封装。

当前版本 **v2.1.0**（versionCode 26）。

## 功能特性

- **★ 继续阅读（v2.0.0 引入 / v2.1.0 根治章号滞后）**：在阅读器里**每打开一章就实时上报本地进度**
  （`prog` 表，**不依赖收藏**，读过就有）。书籍详情页的「開始閱讀」按钮自动变成
  **「继续阅读 第X话 · 共N章」**，点击直达上次看到的那一章；关掉阅读器窗口的瞬间
  详情页按钮立即刷新。
  **v2.1.0 修复「读到第六章还显示第五章」**：改为**直接读阅读页服务端渲染的章号属性**
  （`data-chapter-num` 等），不再依赖一次性的章接口 fetch（失败即永久丢章的根因）；
  fetch 降级为兜底且失败会自动重试；下一章后台预抓窗口不再误报进度；长/短两种详情页
  URL 形态都能查到进度。详见文末 v2.0.0 / v2.1.0 章节。
- 浏览、搜索、按分类/排行/标签发现漫画
- **广告拦截**：过滤页面广告容器、广告 iframe、Google Tag Manager 容器，
  以及站点自建的点击劫持脚本（在拦截层直接把配置块从 HTML 中摘除）
- **界面净化**：隐藏右上角「我的書架」图标、去掉站内「免费图库」外链推广卡
  （`g-mh.com` / `18gallery.com`）、隐藏侧边栏与历史页的「登入」提示块
- **★ 本地书架（v1.8，不用登录）**：站点右上角的「更多」抽屉被**原地改造成「我的书架」** ——
  保留站点自己的开合交互，只把抽屉里原有的「閱讀記錄」列表换成 App 本地的收藏列表。
  抽屉里的条目样式、状态符号与原生书架页完全一致；点条目直接打开作品，
  **长按**弹出标记菜单（在看 ◐ / 已读完 ● / 清除标记 / 从书架删除 / 取消收藏）。
  > v1.9.1 起**抽屉条目右侧的 `×` 已移除**（用户要求，避免误触）；
  > 删除统一走长按菜单，并加了 700ms 点击冷却，防止长按后又触发一次跳转。
- **★ 书架列表重做（v1.9 / 封面来源修正 v1.9.1）**：书架页改为**封面缩略图 + 标题 + 阅读状态**
  的卡片式行（`item_shelf.xml`，行高固定 96dp、垂直居中，封面 52×72dp 直挂）。
  - **封面来源（v1.9.1 修正）**：改为**完全对齐站点自己的阅读记录逻辑** ——
    站点 `_MangaDetailPage.astro` 是读 `[data-manga-id]` 元素上的
    `data-cover-url`（并同时给出 `data-manga-title` / `data-manga-path` / `data-cover-color`），
    客户端 `COVER_JS` 现在**首选该属性**，依次兜底 `og:image` → `ld+json` 的 `@graph[].image`
    → 正文海报图 `div[class*="w-40"] img[class*="object-cover"]`。
    ⚠️ **绝不能用 `img[alt="cover image"]`** —— 那是页面顶部 `blur-[70px]` 的**模糊背景大图**，
    正是 v1.9 封面看起来「拿不到/糊」的根因。
  - 封面经桥接回传入库（`fav.cover` 列，DB v3），列表侧走 `ResCache` 磁盘缓存 +
    内存位图缓存异步加载；抽屉打开前由 `warmCovers()` 用 3 线程池预热。
  - 同时修掉了「上半部分一片空白」的布局 bug（列表与空态改为同层互斥）。
- **★ 收藏星标（v1.6 / 收窄 v1.9）**：作品**详情页**注入半透明收藏星（`__hipFavBtn`）。
  v1.9 起**阅读页不再注入收藏星** —— 站点阅读页 URL 无法反推作品 key，
  点收藏只会提示「不能收藏当前页面」，故整体移除；阅读页的收藏请回详情页操作。
- **★ 隐藏站点收藏入口（v1.8）**：站点详情页右上角自带的「爱心/收藏」按钮点击要求登录站点账号，
  已在服务端 HTML 层彻底删除（手机端 `#mobile-favorite-btn` + 桌面端 `#favorite-btn`），
  客户端再补一层兜底（软导航重新灌入时同样收掉并拦点击）。
- **★ 菜单精简（v1.9）**：左下角菜单去掉「后退 / 前进 / 刷新 / 我的书架」四项 ——
  前三个系统手势与下拉刷新已够用，书架入口统一走右上角「更多」抽屉。
- **浏览记录已下线（v1.7）**：菜单项、数据库写入、`/history` 路由、设置页清理按钮全部移除
  （站点自带阅读记录，App 不再重复实现）。
- **阅读器返回优化（v1.9.3 重做）**：在阅读页按返回键 **一律回到所属书籍详情页**
  （即用户「刚点进书籍时」那一屏），**不再回上一章**。右上角提供半透明「返回书籍」悬浮按钮，
  与系统返回键共用同一套逻辑（`backToBookNow()`）。
  - **v1.6 的「先退上一章」已删除**：用户明确要求「返回书籍详情页，而不是返回上一章」，
    `goBackPrevChapter()` 方法连同符号一起移除（见 `verify_apk.py` 的 GONE）。
  - **为什么不能靠 `goBack()`**：站点真实结构是「详情页 → 中间跳转页 → 真实阅读器」三段式，
    阅读器跑在 `reader.hipmh.top`，WebView 历史里**混着 m.hipmh.com 的中间页**，
    逐条回退只会退到中间页/上一章。正确做法是从当前 URL 解析作品 id → 直接 `loadUrl(详情页)`。
  - **新增 `/chapter/<b64>` 解析（`idFromB64`）**：`reader.hipmh.top/chapter/<b64url("m:<id>-c:<章节id>")>`
    这种 URL **既没有 `m=` 也没有 `hid=`**，旧的两条规则都取不到作品 id，
    于是只能退化到 `goBack()`（= 回上一章）—— 这正是本次 bug 的根因。
    现在 `workIdOf()` 增加第 3 条规则，直接解 `/chapter/` 路径段的 base64。
  - **新增 `lastBookUrl` 兜底**：每次打开 `/works/` 页就记住该详情页 URL；
    若当前阅读器 URL 解析不出作品 id（站点改版/异常），仍能回到记住的那一屏，
    最后才兜底回首页 —— **任何一级都不会退到上一章**。
  - **v1.9.2 修正「不透明」**：三处一起改 —— ① 背景 `rgba(24,26,32,.22)` + `opacity:.55`
    （更透）；② **去掉 `backdrop-filter:blur(2px)`** —— 它把背后的漫画糊成一片，
    视觉上就是个实心圆盘；③ **去掉页面级 `scroll` / `touchstart` 触发 flash**，
    并且**建完按钮不再自动 flash**（旧版一进阅读页就是 `__act` 实心态，
    这才是用户反复反馈「不透明」的真正原因）。现在只在手指按住按钮时短暂变实心。
  - 已有 CSS 回归测试断言 alpha ≤ .35、无 backdrop-filter、无自动 flash、无页面级触发源。
- **★ 阅读页图片预加载（v1.4）**：进入章节后，原生 **8 线程池并发**把本章图片提前抓进磁盘缓存
  （`ResCache`），翻页时 WebView 直接从本地取、几乎不转圈。懒加载图按 `data-src` /
  `data-original` / `data-lazy-src` 取真地址；滚动新增的图片也会自动增量预加载。
  设置页「预加载本章图片」开关，默认开启（`ResCache` 上限 120MB 自动清理）。
- **★ 在途去重 + 下一章预抓（v1.5）**：
  - **在途去重**：`ResCache.fetch` 用 `ConcurrentHashMap<String, Future<byte[]>>` 合并
    同一 URL 的并发下载（WebView 的 `shouldInterceptRequest` 与预加载线程池），
    消除重复带宽与两个线程同时写同一文件导致的缓存损坏。
  - **下一章预抓**：阅读页 JS 请求站点章节 API 拿到 `next_hid`，原生启动一个**隐藏后台 WebView**
    加载 `reader.hipmh.top/chapter/<next_hid>`，复用站点自身 JS 解密 + 现有 `readerJs`
    把下一章图片收集并预抓进**共享 `ResCache`**。翻章时主 WebView 命中磁盘缓存、**零网络等待**。
    后台 WebView 永不被加入视图层级、复用同一 `JsBridge`/`ResCache`/`UA`，`onDestroy` 时回收。
- **繁体转简体**：站点仅提供繁体内容，App 本地将页面文本转为简体（默认开启，内置 4700+ 字映射表）
- **沉浸式阅读**：进入章节自动隐藏站内顶栏/底栏，点击屏幕唤出工具栏
- **滚动穿透修复**：章节目录、设置抽屉打开时锁定背景滚动
- 图片查看器（保存/分享）、桌面/移动 UA 切换、文字缩放、音量键翻页、无痕模式等
- 支持自定义站点（设置页可改首页地址，适配同类 Discuz/漫画站）

## 技术栈

- 语言：Java
- 目标：Android，minSdk 21，targetSdk 34
- 纯 WebView 壳 + JavaScript 注入（无第三方页面脚本依赖）
- 依赖：AndroidX（appcompat、swiperefreshlayout、webkit）

## 实现要点

站点是 **Astro** 构建的 MPA，站内跳转走客户端软导航（`astro:after-swap`）
替换整棵 `<body>`，**不触发 `onPageFinished`**。因此：

- 注入脚本**不能带一次性守卫**（`if(window.__hipXxx)return`），否则软导航后彻底失效；
  必须可重入，并监听 `astro:after-swap` / `astro:page-load` / `popstate` / `hashchange` 补清理。
- 要隐藏/删除的静态元素，**优先在主文档 HTML 层直接删掉**
  （`shouldInterceptRequest` → 改写响应），与路由、水合、异步时序全部无关。

`shouldInterceptRequest` 里对主文档的处理链：

```
stripNode("nav-redirect-config")   // 点击劫持配置块
  → stripGtm                       // GTM 脚本 + noscript 包裹的 iframe
  → stripShelfAnchors              // 右上角与侧栏的「我的書架」
  → stripFavButtons                // 详情页右上角站点原生「爱心/收藏」（要登录）
  → stripPromoCards                // 两个「免费图库」推广卡 + 空壳回收
  → stripSigninPrompt              // 侧栏/历史页的「登入」块
```

全部用**标签配对扫描**（注释感知），不用正则——属性里含 `>` 会被截断。

### 抽屉书架「挤到中间 / 上半空白」的根因（v1.9.2 定位，v1.9.3 修正实现）

站点抽屉正文的真实结构是：

```html
<div data-navbar-sidebar-content
     class="… flex flex-col h-full z-10">
  <div class="flex items-center … py-4 border-b …">          <!-- 标题栏（含 <h2>） -->
  <div class="flex-1 overflow-y-auto min-h-0 flex flex-col">  <!-- ★ 历史记录滚动区 -->
    <div id="navbar-sidebar-history-list"   class="px-5 py-4 …">
    <div id="navbar-sidebar-history-loading" class="hidden py-12 …">
    <div id="navbar-sidebar-history-empty"   class="flex flex-col items-center justify-center … min-h-[200px]">
  <div class="border-t … px-5 py-3 …">                        <!-- 底部按钮行 -->
```

问题在于：正文是 `flex flex-col h-full`，而历史记录那层是 **`flex-1`**。
我们以前只把**内层三件套** `display:none` 掉，外层 `flex-1` 容器**依然占着 flex 份额**，
于是后 `appendChild` 的 `#__hipShelfWrap` 只能拿到剩余空间 → 被挤到下半段、上方一大片空白。

`layoutFix()` 三步解决（v1.9.3 重写实现）：

1. 给三个 `#navbar-sidebar-history-*` 元素本身打 `data-hip-hide` 标记（局部隐藏，最稳）；
2. 遍历 `[data-navbar-sidebar-content]` 的**直接子元素**，凡「不是标题栏、也不是书架容器」的
   `div` 一律打 `data-hip-hide`（= 那个 `flex-1` 历史层，它才是把书架挤到中间的元凶）；
   标题栏用「层内含 `h1/h2/h3`」识别并**保留**；
3. 以标题栏为锚点，把 `#__hipShelfWrap` `insertBefore` 到它的 `nextSibling` 之前
   （标题栏仍在最上、书架紧贴其下）。CSS 侧再兜底：正文 `justify-content:flex-start !important`、
   书架容器 `display:flex; flex-direction:column; align-self:stretch`。

三处一起做才稳：单靠 CSS 选择器（如 `:has()`）在旧 WebView 上不可靠，
单靠 JS 又会被站点后续的异步水合重新排布，所以 `layoutFix()` 在
`tick()`（每 600ms）、`__hipDrawerRender()`、以及抽屉 `aria-hidden` 变化的
`MutationObserver` 回调里都会跑一次（幂等）。

> **v1.9.3 的关键修正：`layoutFix` 不能「爬出 host」。**
> v1.9.2 用的是 `while(n.parentNode !== host) n = n.parentNode;` 从内层元素向上找宿主直接子层 ——
> 一旦该元素**不在 host 子树里**（站点改版、异步水合中途），循环会一路爬到 `<html>`，
> 接着对 `<html>` 设 `display:none` → **整个页面变空白**。
> v1.9.3 改为**只遍历 `host.children`（直接子元素）**，绝不越界；
> 并有专门的回归测试（`test_drawer.js` 测试 8b）把历史元素挂到 `body` 下，
> 断言 `body` / `content` 都不会被误隐藏。
>
> **另注：测试桩的 DOM 必须与真实结构一致。** v1.9.3 把桩里「三个历史元素直接挂 content」
> 改成真实的「包一层 `.flex-1`」，否则这个 bug 在单测里永远测不出来
> （旧桩是扁平结构，`layoutFix` 只需隐藏三个内层元素就能"通过"）。
> 同时桩必须提供 `el.tagName`（真实 DOM 属性名），只写 `el.tag` 会让
> `layoutFix` 里的 `tagName !== 'div'` 判断永远 `continue`，`.flex-1` 层漏标记。

### 长按书架条目会弹两个弹窗（v1.9.4 修正）

**现象**：长按「我的书架」里的条目，会**同时**冒出两个弹窗 ——
① 自己的标记/删除菜单（在看 ◐ / 已读完 ● / 清除标记 / 从书架删除），
② WebView 自带的图片菜单（「全屏查看图片 / 复制图片链接 / 在新页面打开」）。

**根因**：这两个弹窗来自**两条互不知情的通路**。

- ② 来自 Java 侧 `web.setOnLongClickListener(...)`。WebView 自有长按处理会在
  命中处做 HitTest：书架条目的封面是 `<img>`，命中结果是 `HitTestResult.IMAGE_TYPE`，
  于是走进了我们早先为阅读页图片写的 `showLongPressMenu()`。
- ① 来自注入脚本里 500ms 的长按计时器（`st_` 里 `setTimeout(… HipApp.favMenu(cur), 500)`）。

两者都会在 ~500ms 时触发，所以看起来是「两个弹窗一起弹」。

**修法**：让 JS 告诉 Java「现在手指按在书架条目上」，Java 直接吞掉这一次长按。

Java 侧（`MainActivity`）：

```java
private volatile boolean shelfTouchActive = false;   // 跨线程可见

web.setOnLongClickListener(v -> {
    // ★ 手指按在书架条目上时，长按菜单由 JS 全权负责
    if (shelfTouchActive) return true;               // 必须在 getHitTestResult() 之前
    WebView.HitTestResult r = web.getHitTestResult();
    … showLongPressMenu(extra, t); return true;
});

@JavascriptInterface
public void shelfTouch(boolean on) { shelfTouchActive = on; }
```

JS 侧（`drawerJs`）要点：

1. `touchstart` 命中 `.__hipItm` 时**立刻**上报 `HipApp.shelfTouch(true)`——
   必须早于 500ms 计时器，否则 Java 已经弹过了；
2. `touchend` / `touchcancel` 上报 `false`；
3. `touchmove` 用 **12px 位移阈值** 判定：小抖动（长按时的自然手抖）**不**复位，
   真正在滑动才复位（否则滑动时会漏出原生图片菜单）；
4. 兜底 `arm()`：每次 `touchstart` 起 1.5s 保险丝，到点强制复位 `false`，
   防止 `touchend` 丢失（如被系统手势吞掉）导致开关永久卡在 `true`，
   那样之后**全 App 的图片长按菜单都会失灵**。

CSS 再加一层保险：封面 `__hipCv` 加 `pointer-events:none`（命中点落到条目 `<a>` 上，
而不是 `<img>`），条目与封面都加 `-webkit-touch-callout:none`（顺手压掉系统的
「存储图片 / 拷贝」气泡）。

> **回归测试**：`test_longpress.py`（19 项，源码级正则校验 Java/JS/CSS 三侧约定）
> + `test_drawer.js` 测试 9（5 项桩行为断言：touchstart 立即上报 true、
> touchend 上报 false、4/3px 小抖动**不**复位、60px 拖动**要**复位、非条目按下不上报）。

### ★ 阅读器是「独立窗口」——返回逻辑的根治方案（v1.10.0）

**用户的两个诉求**：

1. 书架（右上角「更多」抽屉）打开时，按返回 = **收起书架**（等同点右上角那个「×」），
   而不是退出软件、也不是退页面。
2. 阅读漫画时，返回 = **关闭阅读器窗口**，露出「刚点进书籍时」的详情页；
   再按一次返回 = 详情页 → 主页。
   —— 并明确建议：「**进入阅读器时相当于新开一个浏览器窗口**，上下章都在里面进行，返回就是关窗」。

#### 老实现为什么修不好

站点跳转链路是三段式的：

```
详情页 m.hipmh.com/works/<b64url("m:<id>")>
   ↓ 点章节
中间跳转页 m.hipmh.com/chapter/go?hid=...&m=...      ← GoPage 脚本立即 location.href
   ↓
真实阅读器 reader.hipmh.top/chapter/<b64url("m:<id>-c:<章节>")>
```

⚠️ 注意域名从 `m.hipmh.com` **换到了 `reader.hipmh.top`**。

因为全程用**同一个 WebView**，这条链路整体压进同一个历史栈。于是 v1.9.3 的做法
（从阅读页 `loadUrl(详情页)` 硬跳）会产生一个隐蔽的后果：

```
首页 → 详情页 → 中间页 → 阅读器 → 详情页(loadUrl 新开的一格) ← 用户在这里
```

`loadUrl` 是**追加一格**，不是「回退」。所以第二次按返回时 `readingMode` 已为 false，
走 `web.canGoBack()` → `goBack()` → **退到了历史里的「阅读器」那一格**，
完全复现用户报的「返回详情页后，再返回又是漫画阅读页」。

要绕开它，返回逻辑只能不停 `loadUrl`，主历史栈永远理不清 —— 这是修不完的。

#### v1.10.0 的解法：把阅读器拆成第二个 WebView

`activity_main.xml` 里加了一个铺满全屏、默认 `gone` 的 `WebView(@id/rdWeb)`，
叠在主 WebView 之上：

```xml
<WebView android:id="@+id/web"   .../>   <!-- 主窗口：首页/详情页/搜索/书架 -->
<WebView android:id="@+id/rdWeb" ... android:visibility="gone"/>  <!-- 阅读器窗口 -->
```

- **进入阅读**：在 `handleUrl()` 里**于主 WebView 发起导航之前**截胡
  `isReaderUrl(u)`（认 `/chapter/` 与 `/chapter/go`）→ `enterReader(u)`：
  显示 `rdWeb` 并让**它**去加载，主 WebView **一个字节都不动**。
  ⚠️ 顺序关键：这个判断必须排在 `isInternal(u)` 放行**之前**，否则就 load 进主窗口了。
- **关窗**：`exitReader()` —— `rdWeb.stopLoading()` + `setVisibility(GONE)`。
  **不碰主 WebView 的 URL**，用户看到的就是详情页「原地」露出来，视觉上就是关窗。
- 上下章在 `rdWeb` 内部自由翻页，主 WebView 的历史栈里**从来没有过阅读器**。

于是返回逻辑变得又短又确定，五级优先级：

```java
if (drawerOpen) { closeDrawer(); return; }                    // ① 收起书架
if (readerOpen) { exitReader();  return; }                    // ② 关闭阅读器窗口
if (rdWeb != null && rdWeb.getVisibility() == VISIBLE) {      // ③ 兜底
    exitReader(); return;
}
if (web.canGoBack()) { web.goBack(); return; }                // ④ 详情页 → 首页
// ⑤ 「再按一次退出」
```

#### ① 收起书架：复用站点自己的关闭按钮

实测线上 `Sidebar.astro_astro_type_script_index_0_lang.*.js`：

```js
n = r("[data-navbar-sidebar-close]")          // 抽屉右上角那个「×」
n.addEventListener("click", i => { i.preventDefault(); v(); })   // v() = 关闭函数
```

站点**自己**在 click 上绑好了关闭逻辑（`v()` 内部处理 aria-hidden / inert /
`translateX` 动画，并把焦点还给「更多」按钮）。所以 `closeDrawer()` 就**模拟点一下它**，
比我们自己去改样式干净得多，也不会和站点的动画打架：

```java
private void closeDrawer() {
    String js = "(function(){try{"
        + "var c=document.querySelector('[data-navbar-sidebar-close]');"
        + "if(c&&typeof c.click==='function'){c.click();}"
        + "else{/* 兜底：直接改 #navbar-sidebar 的 aria-hidden / inert / 样式 */}"
        + "}catch(e){}})();";
    web.evaluateJavascript(js, null);
    drawerOpen = false;   // 立刻翻转，防连按两次返回时第二次又走这个分支
}
```

**抽屉开合状态怎么来的**：`drawerJs` 里本来就有个 `tick()` 每 600ms 轮询
`#navbar-sidebar` 的 `aria-hidden`，顺手加一行：

```js
if (open !== window.__hipDrawerOpen) {
  window.__hipDrawerOpen = open;
  if (window.HipApp && HipApp.drawerState) HipApp.drawerState(open);
}
```

Java 侧 `private volatile boolean drawerOpen` + 桥接 `drawerState(boolean)`。
返回键读这个标志即可 —— 不能在返回键里注入 JS 去查 DOM，那是异步的，赶不上同步判断。

> **两个坑，都踩过**：
> 1. **软导航必须强制上报 `false`**：Astro 换整棵 body 后旧抽屉节点已消失，
>    若不重置，原生 `drawerOpen` 会一直停在 `true`，返回键就永远只做「关抽屉」。
>    所以在 `astro:after-swap` 等事件里补一句强制上报。
> 2. **`applyMode()` 必须让位**：主 WebView 在阅读器打开期间仍活着，它自己
>    `onPageFinished` 会调 `applyMode()` → 把沉浸模式/下拉刷新/悬浮按钮**恢复回来**，
>    顶掉阅读器的沉浸态（表现为「阅读时状态栏又冒出来」）。
>    所以 `applyMode()` 开头直接 `if (readerOpen) return;`。

#### 两个窗口的配置必须一致

抽了 `applyCommonSettings(WebSettings)` 给两边共用（UA / `textZoom` / 缓存策略 / 缩放…），
否则用户在设置里改「字号」只有主窗口生效 → **详情页字号正常、进阅读页字号变了**。

只有一处**故意不同**：`setSupportMultipleWindows(true)` 只给主 WebView。
阅读器不需要开新窗口，给了反而会把站内跳转丢进 `onCreateWindow` 的临时 WebView，
多一条不可控分支。

另外 `preloadNextChapter()` 里判断「是不是当前正在读的章」要改用 **`readerUrl`**——
主 WebView 的 `web.getUrl()` 是详情页，永远不匹配，否则会把当前章自己当成下一章预抓。

### ★ 详情页「继续阅读 第X话 · 共N章」（v2.0.0）

**用户的诉求**：书籍详情页要能**实时记录**继续阅读到第几章，回来一点就能接着看。

#### 站点自带的「继续阅读」为什么是坏的

详情页本来就有 `#chapters-config`（`data-locate-from-history="true"`），想让「開始閱讀」
定位到上次读的章。但站点把阅读历史记在 **`reader.hipmh.top` 域的 localStorage** 里——
主域 `m.hipmh.com` 读不到（跨域隔离），走站点账号恢复又要登录。所以这个功能在无痕/未登录
状态下永远是坏的，必须由 App 本地实现。

#### 数据链路：阅读器每章实时上报 → prog 表 → 详情页按钮

**① 阅读器上报（`readerJs` 的 `progressBlock`）**：阅读页 DOM 自带
`data-api-hid` / `data-manga-id` / `data-total-chapters` / `data-api-base-url`，直接取，
`fetch(base + '/v2/chapter?hid=<api-hid>')` 拿 `chapter_number` / `chapter_title`，
经桥 `HipApp.reportProgress(mid, num, title, total, ah)` 回传 Java 入库。
JS 侧 `window.__hipProgLast` 按 hid 去重，同一章只 fetch 一次；
`astro:after-swap` / `popstate` / `hashchange` 翻章后重跑 `__hipProg()`。

⚠️ **API hid 与前端 hid 是两种形态**（逆向 `chapters-manager.js` + 实测确认）：

```
前端 hid（详情页按钮 / go URL）  = b64url("m:<作品id>-c:<章节id>")   例 bToxNTAzMS1jOjEzOTc1
API  hid（阅读器 data-api-hid）  = b64url("c:<章节id>")             例 YzoxMzk3NQ
```

两者都可能带 `-<杂项>` 后缀，解码只取 `-` 前段。上报链路用的是 **API hid**（`c:` 形态），
还原回前端跳转时再**重编码**成前端 hid（`m:<id>-c:<ch>`）——与站点「開始閱讀」按钮同构，
`/chapter/go?hid=…&m=…` 会被 `handleUrl` 的 `isReaderUrl` 拦进阅读器窗口，天然闭环。
另外 `/v2/chapter` 响应里**没有 `total` 字段**（只有 chapter_number/chapter_title/manga_id/
next_hid/images），总数必须从 DOM `data-total-chapters` 取，不能指望接口。

**② 存储（`Store` DB v5，新表 `prog`）**：

```sql
CREATE TABLE IF NOT EXISTS prog(url TEXT PRIMARY KEY, ch INTEGER DEFAULT 0,
    ch_title TEXT, ch_total INTEGER DEFAULT 0, ch_hid TEXT, ts INTEGER)
```

设计要点：**所有读过的书都记**（`saveProgress` 无 fav 门禁，REPLACE 写入），
书架用的 `fav` 表四列（ch/ch_title/ch_total/ch_hid）**只作镜像**（已收藏才 update）——
进度功能不该绑架收藏；`progressOf(url)` 先查 prog、空则回退 fav（老数据兜底），
`ch<=0` 视为无进度。作品 key 与收藏一致：mid → `workUrlOfId()` 归一成 `/works/<b64>`。

**③ 详情页按钮改造（`DETAIL_PROGRESS_JS` / `injectDetailProgress()`）**：
仅在 `/works/` 页生效。桥 `progressOf(url)` 拿到进度后，把 `#reading-btn` 原地改造成
「继续阅读 第X话 · 共N章」（站点原文案含章号就不重复加「第X话」），
`href = /chapter/go?hid=<前端hid>&m=<作品id>`，并打 `data-hip-cont` 标记。
没有进度（没读过）**完全不动按钮**，保持站点原样。

**防站点脚本回写**：幂等守卫（已是自己的文案就直接 return，防观察器自激）+
`MutationObserver`（60ms 防抖）兜站点晚改写 + astro 事件/定时重试。
`GUARD_JS` 的点击豁免列表同步加了 `data-hip-cont`，否则按钮点击会被守卫吞掉。

**关窗即刷新**：`exitReader()` 末尾补跑 `injectDetailProgress()`——读完关窗回到详情页，
按钮**立刻**变成新章号，不用刷新页面。

> **回归测试**：`test_progress.py`（65 项，含 b64 编解码闭环复刻：API hid `YzoxMzk3NQ`
> → `c:13975` → 拼前端 hid `b64url("m:15031-c:13975")` = `bToxNTAzMS1jOjEzOTc1`，
> 与站点「開始閱讀」按钮逐字节一致）；`extract_js` 新增 `hip_detailprog` 目标做语法校验。

### ★★ 「读到第 6 章还显示第 5 章」三缺陷根治（v2.1.0）

**用户的反馈**：v2.0.0 上真机后，连续读到第 6 章，详情页「继续阅读」仍停在第五章。

抓真实阅读页（`/chapter/go?…` 落地的 `reader.hipmh.top` 页面）比对后发现：
**`#chapcontent` 的章号属性（`data-chapter-num="6"`、`data-chapter-number-format="第{num}話"`）
是服务端渲染的**——页面 HTML 一到手章号就是对的，根本不需要任何接口。
而 v2.0.0 的上报链路却绕远路：先去 `fetch /v2/chapter` 拿章号。三处缺陷叠加：

#### 缺陷 ① 上报太脆弱：fetch 失败 = 该章进度永久丢失（直接根因）

v2.0.0 的 `__hipProg()` 每章只跑一次有效逻辑：**先置去重标记 `__hipProgLast`，再 fetch**。
fetch 一旦失败（预抓窗口挤占、弱网、请求被并发吞掉），标记已经置上、章号没拿到——
这个标记又**没有任何重试机制**（不清洗、不轮询），该章进度就永远丢了。
读到第 6 章时第 6 章的上报失败，详情页自然还显示第 5 章。

#### 缺陷 ② 后台预抓窗口也在上报：进度可能「超前」

`preloadNextChapter` 用隐藏 WebView（bgWeb）加载下一章做图片预抓，
注入的 `readerJs(false)` 与主阅读器共用 progressBlock，而 bgWeb 上桥的也是同一个
`HipApp`——**没读过的下一章被写进进度**。虽然 key 归一后多数被同 key 覆盖，
但时序上它可能把「未来章节」写进表里，与缺陷 ③ 叠加时表现更乱。

#### 缺陷 ③ key 分裂：详情页查不到已写入的进度（隐藏根因）

上报侧把进度记在**短 key**（`workUrlOfId(mid)` = `/works/<纯b64>`），
而详情页查询走 `favKeyFor(当前URL)`——真实详情页 URL 是**长形态**
`/works/bToxNTAzMQ-wo-du-zi-sheng-ji-zhu-shen-huang-hun-15022`（带 slug），
`workUrl()` 只截断到 `/ ? # &`、**保留 slug**，于是得到**长 key**。
两把钥匙开一把锁：prog 表里明明有记录，长形态详情页却查不到。

#### 修法（三处一次性根治）

1. **上报直接读服务端渲染 DOM**（`progressBlock` 重写）：`#chapcontent` 上现成的
   `data-chapter-num` / `data-chapter-number-format` / `data-frontend-hid` /
   `data-manga-id` / `data-total-chapters` 直接取，取到就同步上报，**不再依赖 fetch**；
   fetch 降级为 DOM 缺章号时的兜底，且**失败时清空 `__hipProgLast`**。
2. **多重自动重试**：2 秒 `setInterval` 轮询（`__hipProgT`）+ `MutationObserver`
   盯 `#chapcontent` 的章号属性变化（`attributeFilter` 精确过滤）+
   astro 事件与定时 setTimeout 重跑。去重标记只在**上报成功**后置位，失败必被补报。
3. **bgWeb 门控**：progressBlock 整块改为 `allowNext ? (…) : ""`，
   预抓窗口注入 `injectReaderInto(v, u, false)`——**后台预抓彻底不上报**。
4. **hid 归一（`chapterFeHid`）**：上报直接带**前端形态 hid**（`m:<mid>-c:<ch>`，
   阅读页 DOM 的 `data-frontend-hid` 原样取，老数据 API 形态则重编码），
   详情页按钮拼跳转 URL 不再需要二次换算。
5. **双 key 兜底查询（`progressOf` 桥）**：长 key 查不到时按短 key
   （`workUrlOfId(id)`）补查一次——v2.0.0 写入的旧进度记录也能读到。

> **回归测试**：`test_progress.py` 升到 **79 项**——[F] 双 key 兜底断言、[G] chapterFeHid
> 定义与两形态判定、[H] 逻辑复刻闭环、[I] 上报块 14 项（门控/直读 DOM/重试清标记/轮询/
> 观察器/`injectReaderInto(v, u, false)`）、[K] exitReader 关窗补跑 `__hipProg()`。
> `verify_apk.py` MUST 清单新增 `__hipProg*` / `data-chapter-num` /
> `data-chapter-number-format` / `data-frontend-hid` / `chapterFeHid` / `attributeFilter`。

### 详情页封面的正确来源（v1.9.1 逆向确认）

站点自己的阅读记录是这么存的（`_MangaDetailPage.astro…js`）：

```js
const t = document.querySelector("[data-manga-id]");
readingHistoryManager.recordMangaOnly({
  mangaId:   t.getAttribute("data-manga-id"),
  mangaTitle:t.getAttribute("data-manga-title"),
  mangaPath: t.getAttribute("data-manga-path"),
  coverUrl:  t.getAttribute("data-cover-url"),   // ← 封面在这里
});
```

真实详情页锚点形如：

```html
<div data-manga-id="15031"
     data-manga-title="我独自升级 : 诸神黄昏"
     data-manga-path="/works/bToxNTAzMQ"
     data-cover-url="https://cover.s3imgs.top/kk/vertical/….webp"
     data-cover-color="#586ea1">
```

另有 `<meta property="og:image">`、`<script type="application/ld+json">` 的 `@graph[].image`、
`#chapters-config[data-cover]` 等旁证，可作为兜底。

> ⚠️ **陷阱**：`img[alt="cover image"]` 是顶部那层**模糊背景大图**
> （`blur-[70px] md:blur-[10px]`），不是海报。真正的海报在 `div.w-40.h-[226px]` 里。
> v1.9 误取了前者，所以封面看起来不对——这是 v1.9.1 修正的核心。

## 构建

用 Android Studio 打开工程，或命令行：

```bash
gradle assembleRelease
```

> 签名密钥 `hipmh.keystore` 与口令**不包含在本仓库**，需自行准备并配置
> `keystore.properties`（或修改 `app/build.gradle` 的 signingConfigs）后方可出 release 包。

## 测试

仓库内含离线回归测试（对线上真实 HTML 跑，覆盖每个修复点）：

```bash
python extract_js.py        # 反解 8 份注入 JS + node --check 语法校验
python test_strip.py        # nav-redirect-config 剥离
python test_gtm.py          # GTM 剥离
python test_v1011.py        # 书架 / GTM 端到端
python test_css.py          # 注入 CSS 规则解析与选择器核查
python test_promo.py        # 两个「免费图库」推广卡
python test_signin.py       # 侧栏/历史页「登入」块
python test_reader_back.py  # 阅读器返回 URL 反推（含端到端校验）
python test_preload.py      # 阅读页图片预加载（源码接线 + URL 收集算法）
python test_favbtn.py       # ★v1.8 详情页「爱心/收藏」按钮剥离（含真实详情页）
                            #   ★v1.9.1 追加 [C] 段：阅读页返回按钮必须保持半透明
python test_progress.py     # ★v2.0.0/v2.1.0 阅读进度（上报/存储/按钮/hid 编解码闭环）
python verify_apk.py        # 产物 dex 符号核对
node test_v107.js           # 注入脚本桩 DOM 行为回归
node test_shelf.js          # 书架判定（真实站点链接数据）
node test_nav_stub.js       # nav 桩 DOM + CSS 命中核查
node test_drawer.js         # ★v1.8「更多」抽屉改造成书架（桩 DOM 行为）
                            #   ★v1.9.2 追加测试 8：抽屉布局校正（历史容器移出 flex）
node test_cover.js          # ★v1.9 详情页封面采集（含真实详情页 fixture 回归）
```

一键跑全套：`python _run_all.py`（结果落 `_jscheck.txt`）。

## Release

APK 见 [Releases](../../releases) 页面。

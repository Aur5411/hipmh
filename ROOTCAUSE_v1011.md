# v1.0.11 根因分析：书架仍在 / 广告仍有 / 历史页登入按钮

## 结论一句话

**v1.0.10 的「书架隐藏」「广告拦截」在客户端都写了正确逻辑，但都被
`window.__hipXxx` 一次性守卫挡住，只在首次页面加载执行一次。
而站点是 Astro 客户端路由（`astro:after-swap`），站内跳转会替换整棵
`<body>` 并重新渲染 nav（含新的「我的書架」图标），此时注入脚本直接
`return`，新 DOM 永远不被清理 —— 于是「书架一直在、广告又出现」。**

## 证据链

### 1. 站点是标准 UTF-8，`Content-Type: text/html`（无 charset 参数）

```
Content-Type  : 'text/html'
has 'html'?   : True          ← stripRedirectHijack 的 ctype.contains("html") 命中，会生效
bytes         : 129685 / chars 125727
```

→ 排除编码问题，排除 `stripRedirectHijack` 未生效。

### 2. 书架是**静态 HTML**（不是 hydration 注入）

`https://m.hipmh.com/` 原始 HTML 里 **2 处** `aria-label="我的書架"`：

```html
<!-- nav 第一行右侧图标容器（手机端可见的「第 2 个图标」） -->
<div class="flex items-center justify-end md:space-x-6 space-x-4 flex-shrink-0 pl-0 md:pl-3">
  <form action="/search" class="... hidden md:flex">…</form>        <!-- 桌面搜索框，手机隐藏 -->
  <a href="/search" aria-label="搜尋" class="… block md:hidden">     <!-- 图标 1：搜索 -->
  <a href="https://m.xipmh.com/dashboard?lang=zh"
     aria-label="我的書架" class="text-foreground transition-colors p-1">  <!-- 图标 2：书架 ★ -->
  <button aria-label="更多" class="… p-1">                            <!-- 图标 3：更多 -->
</div>

<!-- #navbar-sidebar 底部第 2 处同款 -->
<a href="https://m.xipmh.com/dashboard?lang=zh" aria-label="我的書架">
```

→ **用户看到的「右上角没文字、第二个图标」就是上面那个纯 SVG 图标的 `<a aria-label="我的書架">`。**
它在服务端 HTML 里就存在，所以纯 CSS 方案在「能跑到的时侯」是有效的；
失败原因是**没跑到**，不是**选择器不对**。

### 3. 站点用 Astro 客户端路由

内联脚本里出现：

```js
document.readyState==="loading"?document.addEventListener("DOMContentLoaded",s):s();
document.addEventListener("astro:after-swap",s);
```

`astro:after-swap` 只在 Astro 的客户端路由软导航时派发。
→ 站内跳转**不触发完整页面加载**，`onPageFinished` 不一定回调；
即使回调，`cleanJs` 首行的 `if(window.__hipClean)return;` 会让它立刻返回。

### 4. GTM noscript iframe 残留（广告仍在的服务端证据）

站点首页只有 **1 个 iframe**，且被包在 `<noscript>` 里：

```html
<body class="min-h-screen …">
  <noscript>
    <iframe src="https://www.googletagmanager.com/ns.html?id=GTM-KWM3FNGT"
            height="0" width="0" style="display:none;visibility:hidden"></iframe>
  </noscript>
```

v1.0.10 的 `stripGtm` **只删 `<iframe…>…</iframe>`，没有删外层 `<noscript>`**。
复现验证：删完 iframe 后，HTML 里仍留下 `<noscript>  </noscript>`，
`googletagmanager` 引用残留 1 处 —— GTM 容器并未被根除。

### 5. ResCache 不是元凶（已排除）

`ResCache.isCacheable(u)` 只匹配 `.css/.js/.png/.webp/…` 等静态资源后缀，
**HTML 主文档永不命中**，因此 `shouldInterceptRequest` 里缓存分支不会绕过
`stripRedirectHijack`。

### 6. 历史页底部「登入」按钮的真实结构

`https://m.hipmh.com/history`：

```html
<div id="navbar-sidebar-user-not-authed" class="hidden">
  <button type="button" class="w-full px-4 py-2.5 rounded-lg bg-primary …"
          data-navbar-sidebar-signin> 登入 </button>
</div>
```

v1.0.10 的 `HIST_JS` 只按「文字 + href」猜，且同样有 `__hipHistJs` 一次性守卫。

---

## v1.0.11 修复方案

| 项 | 手段 | 为什么可靠 |
|---|---|---|
| **书架隐藏** | ① **服务端**：`stripShelfAnchors()` 直接把 2 个 `<a aria-label="我的書架">` 从 HTML 删掉 ② 客户端 CSS 兜底 | 服务端删除与客户端路由/水合无关，DOM 里根本不存在该书架元素 |
| **广告拦截** | `stripGtm()` 补删 `<noscript>` 外层包裹 → GTM 容器（脚本 + ns.html iframe）整体消失 | 容器不存在则运行时无法注入任何广告 |
| **历史页登入按钮** | `HIST_JS` 改用站点精确属性 `data-navbar-sidebar-signin` / `#navbar-sidebar-user-not-authed` | 与语言、文字、href 无关，不会误伤 |
| **所有注入的「只跑一次」问题** | 去掉 `__hipClean` / `__hipHistJs` / `__hipT2s` 三个一次性守卫，改可重入 + 节流 | Astro 软导航后能重新清理新 DOM |
| **软导航补清理** | 监听 `astro:after-swap` / `astro:page-load` / `popstate` / `hashchange`，事件后重置节流并重扫 | 软导航不触发 `onPageFinished`，必须补挂 |

### 服务端处理顺序（`stripRedirectHijack`）

```java
String cleaned = stripNode(html, "nav-redirect-config");  // 点击劫持配置
cleaned = stripGtm(cleaned);                              // GTM（含 noscript 包裹）
cleaned = stripShelfAnchors(cleaned);                      // 我的書架 × 2 ← v1.0.11 新增
```

---

## 验证

| 测试 | 结果 |
|---|---|
| `extract_js.py`（5 份注入 JS 语法） | `SYNTAX_ALL_OK = True` |
| `test_v1011.py`（新，真实站点 strip 回归） | **13 passed / 0 failed** |
| `test_v107.js` | 42 passed / 0 failed |
| `test_shelf.js` | 19 passed / 0 failed |
| `test_nav_stub.js` | 17 passed / 0 failed |
| `test_strip.py` | 16 passed / 0 failed |
| `test_css.py` | ALL OK |
| `test_gtm.py` | 12 passed / 0 failed |
| `verify_apk.py`（dex/arsc 进包核对） | ALL OK |

`test_v1011.py` 关键断言（对线上真实 HTML）：

- 处理后 `aria-label` 含「书架」的元素 = **0**
- 处理后 `xipmh.com` / `dashboard` 链接 = **0**
- 处理后 `<iframe>` 数 = **0**，`gtm.js` / `dataLayer` / `GTM-` = **0**
- 未误删：「搜尋」图标 ✓、「更多」按钮 ✓、logo ✓、首页/人气榜/探索/随机 ✓
- 删除量 5790 字符（未过度删除）

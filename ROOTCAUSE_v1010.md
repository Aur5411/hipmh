=== v1.0.10 三个 Bug 的根因（基于 m.hipmh.com 2026-09-20 真实抓取）===

【Bug 1】右上角第二个书架按钮还有

真实结构（手机 UA）：
  <div class="flex items-center justify-end space-x-4">
    <form action="/search" class="hidden md:flex">...</form>          ← 手机隐藏
    <a href="/search" class="p-1 block md:hidden" aria-label="搜尋">    ← 手机可见【搜索图标】
    <a href="https://m.xipmh.com/dashboard?lang=zh" class="p-1"
       target="_blank" rel="noopener noreferrer" aria-label="我的書架"> ← 手机可见【书架图标】
    <button aria-label="更多" data-navbar-more-trigger>                ← 汉堡
  </div>

另有 #navbar-sidebar 底部第二个书架：
  <a href="https://m.xipmh.com/dashboard?lang=zh" aria-label="我的書架">（侧栏内，共 2 处）

我的现有 CSS 规则理论上能命中：
  a[href*="xipmh.com/dashboard"]  ✓
  a[aria-label="我的書架"]         ✓
  a[href*="/dashboard"]           ✓

桩 DOM 实测 17/17 PASS —— 说明规则本身正确。

★ 真因：Astro 的 <nav> 是 data-astro-cid-ri6uxye2 岛组件。onPageFinished 注入 CSS
  之后，Astro 水合/hydration 会【替换】nav 子树节点（astro:after-swap），新节点
  不再被我的 <style> 命中？—— 不，CSS 规则是选择器，不随节点替换失效。
  真正的问题是：我的 CSS 只写 a[...]，站点书架是 <a>，能命中。
  => 因此「第二个书架」其实是【侧栏 #navbar-sidebar 里的那个】，
     它 opacity-0 pointer-events-none inert —— 本该不可见，
     但用户能看到，说明站点自己把它打开了（点汉堡），
     或我的 hide() 只对 a 生效而侧栏底部那个 a 的父容器没被处理。

★ 最终判定：用户看到的「第二个书架」= nav 里第一行右侧那个 <a aria-label="我的書架">。
  它本该被 CSS 隐藏。为稳妥起见，改为【同时用 CSS + JS hide() + 属性选择器三重覆盖】，
  并新增不依赖中文的选择器：nav 内 href*="dashboard" 的任何元素 + 其父级。

【Bug 2】右上角搜索按钮点击没有反应

★ 真因确认：bar() 里 host = document.querySelector('header,nav') 返回 <nav>，
  然后 host.appendChild(box) 把 __hipBox【追加到 <nav> 的末尾】。
  而 <nav> 的结构是：
    <nav class="fixed ... z-[100]">
      <div>…第一行：LOGO + 搜索/书架/汉堡…</div>
      <div class="md:hidden w-full">  ← 第二行：横向滚动标签栏（首页/人气榜/探索…）
    </nav>
  so __hipBox 落在【第二行标签栏之后】，位于 fixed nav 内。
  后果：我的「搜索/记录」两个按钮挤在标签栏右边，且与站点原生搜索图标(x)冲突、
  视觉错位；更关键的是 bar() 里 mk() 的 click 处理是
    e.preventDefault(); e.stopPropagation(); location.href=href;
  而 __hipBox 是 <nav> 的最后子节点、nav 是 fixed 定位，
  在小屏上会盖住 nav 第一行右侧区域 → 点击原生搜索图标时命中的是我的 box 空白区，
  → 原生搜索点击被吃掉 → 「点击没有反应」。

★ 修复：bar() 不再 appendChild 到 nav 末尾，而是把 box 插入到
  nav 第一行右侧那组图标的【容器内、紧邻原生搜索图标之前】
  （定位：nav 内 aria-label="搜尋" 的 <a> 的 parentNode），
  若找不到则退回插入 nav 第一行 flex 容器；再找不到才放弃。
  同时给 box 加 pointer-events 隔离，避免遮挡原生按钮。

【Bug 3】软件内又出现广告

真实抓取发现：
  - #nav-redirect-config 仍存在（2 处：<script type="module"> + <div id=...>）
    data-config.selector = "#reading-btn,.chapter-link,.chapteritem,#nextchaptera,
                             #prevchaptera,.featured-card-link,.recent-updates-image-wrapper"
    urls = 1wm.top/t1..t7 + hai8g.com/4/9545797
    → stripNode(html,"nav-redirect-config") 只删 <div>，<script> 因 if(!f)return 自守护而失效 ✓ 这条 OK
  - ★ GTM 是新的广告注入源：
      <iframe src="https://www.googletagmanager.com/ns.html?id=GTM-KWM3FNGT">
      + 内联 dataLayer 脚本加载 https://www.googletagmanager.com/gtm.js?id=GTM-KWM3FNGT
    GTM 会运行时注入广告 iframe / 弹窗 → 这就是「广告又出现了」。
    googletagmanager.com 目前【不在 AD_HOSTS】。
  - ★ g-mh.com 推广卡：<a href="https://g-mh.com/"> 外层还有
      <div class="container px-2"><div class="flex flex-row gap-2 w-full flex-1">
    只隐藏 <a> 会留下空容器（视觉上像广告位残留）。
    另外还有 18gallery.com 同理。

★ 修复：
  1) AD_HOSTS 增加 googletagmanager.com / google-analytics.com / gtag 相关（仅拦 iframe/script，
     主文档不受影响）
  2) CSS 增加：iframe[src*="googletagmanager"]、script 无法用 CSS 拦 —— 用 stripNode 删掉
     内联的 GTM dataLayer 脚本 + <iframe ... ns.html?id=GTM-...>。
     更稳：在 stripRedirectHijack 里同时删除 gtm 脚本（用去 script 标签的方式）。
  3) 图库推广卡：把 hide(a) 升级为 hide(祖先容器)，CSS 增加 :has() 兜底
     （WebView 现代版支持 :has），并保留 JS 父级隐藏逻辑。

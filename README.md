# 嘻皮漫画（HipManga）

[m.hipmh.com](https://m.hipmh.com/) 的 Android 客户端，基于原生 WebView 封装。

当前版本 **v1.3**（versionCode 13）。

## 功能特性

- 浏览、搜索、按分类/排行/标签发现漫画
- **广告拦截**：过滤页面广告容器、广告 iframe、Google Tag Manager 容器，
  以及站点自建的点击劫持脚本（在拦截层直接把配置块从 HTML 中摘除）
- **界面净化**：隐藏右上角「我的書架」图标、去掉站内「免费图库」外链推广卡
  （`g-mh.com` / `18gallery.com`）、隐藏侧边栏与历史页的「登入」提示块
- **阅读器返回优化**：在阅读页按返回键回到**作品详情页**（而非退出软件），
  并在右上角提供「返回书籍」悬浮按钮
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
  → stripPromoCards                // 两个「免费图库」推广卡 + 空壳回收
  → stripSigninPrompt              // 侧栏/历史页的「登入」块
```

全部用**标签配对扫描**（注释感知），不用正则——属性里含 `>` 会被截断。

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
python extract_js.py        # 反解 5 份注入 JS + node --check 语法校验
python test_strip.py        # nav-redirect-config 剥离
python test_gtm.py          # GTM 剥离
python test_v1011.py        # 书架 / GTM 端到端
python test_css.py          # 注入 CSS 规则解析与选择器核查
python test_promo.py        # 两个「免费图库」推广卡
python test_signin.py       # 侧栏/历史页「登入」块
python test_reader_back.py  # 阅读器返回 URL 反推（含端到端校验）
python verify_apk.py        # 产物 dex 符号核对
node test_v107.js           # 注入脚本桩 DOM 行为回归
node test_shelf.js          # 书架判定（真实站点链接数据）
node test_nav_stub.js       # nav 桩 DOM + CSS 命中核查
```

## Release

APK 见 [Releases](../../releases) 页面。

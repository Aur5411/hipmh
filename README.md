# 嘻皮漫画（HipManga）

[m.hipmh.com](https://m.hipmh.com/) 的 Android 客户端，基于原生 WebView 封装。

## 功能特性

- 浏览、搜索、按分类/排行/标签发现漫画，收藏与阅读历史
- **广告拦截**：过滤页面广告容器与广告 iframe
- **隐藏登录入口**：去掉右上角「我的書架 / 登入」及站内「免费图库」外链推广卡
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

## 构建

用 Android Studio 打开工程，或命令行：

```bash
gradle assembleRelease
```

> 签名密钥 `hipmh.keystore` 与口令**不包含在本仓库**，需自行准备并配置
> `keystore.properties`（或修改 `app/build.gradle` 的 signingConfigs）后方可出 release 包。

## Release

APK 见 [Releases](../../releases) 页面。

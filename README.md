# 嘻皮漫画（HipManga）

[m.hipmh.com](https://m.hipmh.com/) 的 Android 客户端，原生 WebView 封装。

当前版本 **v2.1.0**（versionCode 26）· APK 从 [Releases](https://github.com/Aur5411/hipmh/releases/latest) 下载

## 主要功能

- **继续阅读**：实时记录每本书读到的章节（本地存储，不依赖登录/收藏），
  详情页「開始閱讀」自动变成「继续阅读 第X话 · 共N章」，点击直达上次章节
- **本地书架**：不用登录的收藏 + 封面缩略图列表 + 长按标记（在看 / 已读完），
  入口在站点右上角「更多」抽屉
- **独立阅读器窗口**：点进漫画新开一个窗口，返回即关窗；阅读页按返回一律回书籍详情页
- **快速翻章**：本章图片 8 线程预加载 + 下一章后台预抓，翻章几乎零等待
- **广告拦截**：点击劫持配置块、GTM 容器、免费图库推广卡、站点收藏/登入提示块
- **阅读体验**：繁体转简体（本地映射表）、沉浸式阅读、音量键翻页、无痕模式、图片查看器

## 构建

Android Studio 打开工程，或命令行 `gradle assembleRelease`。
签名密钥不包含在仓库内，需自行配置 `keystore.properties` 后方可出 release 包。

## 测试

离线回归测试（对真实站点 HTML 夹具跑，覆盖每个修复点）：

```bash
python _run_all.py   # 10 步全绿，结果落 _jscheck.txt
```

## 说明

- 版本演变与实现细节见 git 提交历史及 `ROOTCAUSE_*.md` 文档
- 仓库不含签名密钥、APK 与构建产物

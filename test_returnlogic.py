# -*- coding: utf-8 -*-
"""v1.10.0 返回逻辑回归测试（源码级 + 逻辑复刻）。

覆盖用户明确要求的两件事：
  ① 书架（「更多」抽屉）打开时，返回键 = **收起抽屉**（等同点右上角「×」），不是退页面/退出软件；
  ② 阅读漫画时，返回 = **关闭阅读器窗口** → 露出详情页；再返回 = 详情页 → 主页。

v1.10.0 的关键结构变化：阅读器被拆成**独立的第二个 WebView**（rdWeb），
于是「返回详情页后，再返回又回到漫画阅读页」这个老问题从根上消失
——因为主 WebView 的历史栈里根本没有阅读器。
"""
import io
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA = os.path.join(HERE, 'app', 'src', 'main', 'java', 'com', 'hipmh', 'app',
                    'MainActivity.java')
XML = os.path.join(HERE, 'app', 'src', 'main', 'res', 'layout', 'activity_main.xml')

java = io.open(JAVA, encoding='utf-8').read()
xml = io.open(XML, encoding='utf-8').read()

npass = 0
nfail = 0


def ok(name, cond, extra=''):
    global npass, nfail
    if cond:
        npass += 1
        print('  PASS  ' + name)
    else:
        nfail += 1
        print('  FAIL  ' + name + ('  -> ' + str(extra) if extra else ''))


def block(src, header, size=4000):
    """取从 header 开始的 size 字符，用于局部断言（避免全文误命中）。"""
    i = src.find(header)
    if i < 0:
        return ''
    return src[i:i + size]


print('[A] 布局：阅读器窗口 WebView 存在且默认隐藏')
ok('activity_main.xml 有 rdWeb 节点', 'android:id="@+id/rdWeb"' in xml)
ok('rdWeb 默认 visibility=gone',
   bool(re.search(r'@\+id/rdWeb[\s\S]{0,300}?android:visibility="gone"', xml)))
ok('rdWeb 铺满（match_parent x match_parent）',
   bool(re.search(r'@\+id/rdWeb[\s\S]{0,300}?layout_width="match_parent"[\s\S]{0,200}?layout_height="match_parent"', xml)))

print('')
print('[B] Java：阅读器窗口的字段与生命周期')
ok('声明了 rdWeb 字段', 'private WebView rdWeb;' in java)
ok('声明了 readerOpen 状态', 'private boolean readerOpen' in java)
ok('声明了 readerUrl 状态', 'private String readerUrl' in java)
ok('onCreate 里 findViewById(R.id.rdWeb)', 'findViewById(R.id.rdWeb)' in java)
ok('onCreate 调用 setupReaderWeb()', 'setupReaderWeb();' in java)
ok('存在 setupReaderWeb() 定义', 'private void setupReaderWeb()' in java)

print('')
print('[C] Java：进入/退出阅读器窗口')
enter = block(java, 'private void enterReader(String u)')
ok('存在 enterReader()', bool(enter))
ok('enterReader 里置 readerOpen = true', 'readerOpen = true;' in enter)
ok('enterReader 里 rdWeb 加载（而不是 web 加载）', 'rdWeb.loadUrl(u)' in enter)
ok('enterReader 里显示 rdWeb', 'rdWeb.setVisibility(View.VISIBLE)' in enter)
ok('enterReader 里 web 不参与导航（不得出现 web.loadUrl）',
   'web.loadUrl' not in enter, 'enterReader 不应再动主 WebView')

exitb = block(java, 'private void exitReader()')
ok('存在 exitReader()', bool(exitb))
ok('exitReader 里置 readerOpen = false', 'readerOpen = false;' in exitb)
ok('exitReader 里隐藏 rdWeb', 'rdWeb.setVisibility(View.GONE)' in exitb)
ok('exitReader 里 stopLoading（免得后台空跑）', 'stopLoading()' in exitb)
ok('exitReader 不碰 web 的 URL（原地露出详情页）',
   'web.loadUrl' not in exitb, '关窗不该是一次跳转')

print('')
print('[D] Java：主 WebView 拦截进入阅读器的跳转')
hu = block(java, 'private boolean handleUrl(String u)')
ok('handleUrl 里判断 isReaderUrl', 'isReaderUrl(u)' in hu)
ok('命中后调用 enterReader 并 return true',
   bool(re.search(r'isReaderUrl\(u\)\)\s*\{\s*enterReader\(u\);\s*return true;', hu)))
# ★ 顺序：必须在 isInternal 放行之前 —— 否则就 load 进主窗口了
i_rd = hu.find('isReaderUrl(u)')
i_int = hu.find('isInternal(u)')
ok('isReaderUrl 判断排在 isInternal 之前（关键顺序）',
   0 <= i_rd < i_int, 'i_rd=%d i_int=%d' % (i_rd, i_int))
ok('存在 isReaderUrl() 定义', 'private static boolean isReaderUrl(String u)' in java)

irl = block(java, 'private static boolean isReaderUrl(String u)', 400)
ok('isReaderUrl 认 /chapter/ ', '"/chapter/"' in irl)
ok('isReaderUrl 认 /chapter/go（中间跳转页也算阅读器）', '/chapter/go' in irl)

print('')
print('[E] Java：onBackPressed 五级优先级')
bp = block(java, 'public void onBackPressed()', 3000)
ok('抽屉优先于阅读器',
   bool(re.search(r'if \(drawerOpen\)[\s\S]{0,300}?closeDrawer\(\);[\s\S]{0,300}?return;', bp)))
i_dr = bp.find('if (drawerOpen)')
i_rd2 = bp.find('if (readerOpen)')
i_gb = bp.find('web.canGoBack()')
ok('顺序为 抽屉 → 阅读器 → 主历史',
   0 <= i_dr < i_rd2 < i_gb, 'dr=%d rd=%d gb=%d' % (i_dr, i_rd2, i_gb))
ok('抽屉分支调用 closeDrawer()', 'closeDrawer()' in bp)
ok('阅读器分支调用 exitReader()', 'exitReader()' in bp)
ok('主历史分支仍是 web.goBack()', 'web.goBack()' in bp)
ok('最后是「再按一次退出」', 'exit_hint' in bp)
# ★ 最关键的：不能再出现「用 loadUrl 硬跳详情页」的旧方案
ok('onBackPressed 不再调用 backToBookNow（旧方案已删）', 'backToBookNow' not in bp)

print('')
print('[F] Java：旧的 URL 兜底方案已彻底移除')
ok('backToBookNow 方法已删除', 'private void backToBookNow()' not in java)
ok('bookUrlFromCurrent 方法已删除', 'private String bookUrlFromCurrent()' not in java)
ok('lastNonReaderUrl 方法已删除', 'private String lastNonReaderUrl()' not in java)
ok('lastBookUrl 字段已删除', 'private String lastBookUrl' not in java)

print('')
print('[G] Java：收起抽屉复用站点原生关闭按钮')
cd = block(java, 'private void closeDrawer()', 2000)
ok('存在 closeDrawer()', bool(cd))
ok('优先点站点的 data-navbar-sidebar-close', 'data-navbar-sidebar-close' in cd)
ok('有兜底：直接改 #navbar-sidebar 样式', 'navbar-sidebar' in cd)
ok('兜底会设 aria-hidden=true', "setAttribute('aria-hidden','true')" in cd)
ok('兜底会设 inert（站点原始语义）', "setAttribute('inert','')" in cd)
ok('本地立刻置 drawerOpen=false（防连按两次）', 'drawerOpen = false;' in cd)

print('')
print('[H] Java：drawerOpen 状态上报')
ok('存在 drawerOpen 字段且 volatile', 'private volatile boolean drawerOpen' in java)
ok('JsBridge 有 drawerState(boolean) 桥接',
   bool(re.search(r'@JavascriptInterface\s*\n\s*public void drawerState\(boolean open\)', java)))
ok('drawerState 写入 drawerOpen', bool(re.search(
    r'public void drawerState\(boolean open\)\s*\{\s*drawerOpen = open;', java)))

print('')
print('[I] JS：drawerJs 上报开合状态')
dj = block(java, 'private static String drawerJs(', 20000)
ok('drawerJs 里调用 HipApp.drawerState', 'HipApp.drawerState' in dj)
ok('只在状态变化时上报（有 __hipDrawerOpen 记忆位）', '__hipDrawerOpen' in dj)
ok('软导航后强制上报 false（防状态卡死）',
   bool(re.search(r'__hipDrawerOpen=false;[\s\S]{0,200}?HipApp\.drawerState\(false\)', dj)))
ok('上报使用 aria-hidden===\'false\' 判定',
   "getAttribute('aria-hidden')==='false'" in dj)

print('')
print('[J] Java：阅读页返回按钮改走「关窗」，且删掉 history.back 兜底')
rj = block(java, 'private String readerJs(boolean allowNext)', 20000)
ok('readerJs 里调用 HipApp.backToBook', 'HipApp.backToBook' in rj)
ok('返回按钮不再有 history.back() 兜底',
   'else history.back()' not in rj and "catch(y){}}" not in rj.split('HipApp.backToBook')[1][:200],
   'history.back 在旧结构里会退到中间页/上一章')
b2b = block(java, 'public void backToBook()', 500)
ok('backToBook 桥接改为 exitReader()', 'exitReader()' in b2b)
ok('backToBook 不再调 backToBookNow', 'backToBookNow' not in b2b)

print('')
print('[K] Java：阅读器窗口的注入 / 资源拦截 / 长按')
ok('injectReaderWindow 只注入阅读页脚本',
   'injectReaderInto(rdWeb' in java and 'injectT2sInto(rdWeb)' in java
   and 'injectCleanInto(rdWeb)' in java)
ok('injectReaderWindow 定义存在且方法体完整',
   bool(re.search(r'private void injectReaderWindow\(String u\)[\s\S]{0,500}?\n    \}', java)))
iw = block(java, 'private void injectReaderWindow(String u)', 500)
ok('injectReaderWindow 里没有 injectDrawer', 'injectDrawer' not in iw)
ok('injectReaderWindow 里没有 injectCover', 'injectCover' not in iw)
ok('rdWeb 有自己的 shouldInterceptRequest 拦广告', bool(re.search(
    r'rdWeb\.setWebViewClient[\s\S]{0,3000}?shouldInterceptRequest', java)))
ok('rdWeb 注入了 HipApp 桥', bool(re.search(r'rdWeb\.addJavascriptInterface', java)))
ok('rdWeb 有长按菜单（图片另存）', bool(re.search(
    r'rdWeb\.setOnLongClickListener', java)))

print('')
print('[L] Java：公共 WebSettings 抽函数，两个窗口同步')
ok('存在 applyCommonSettings()', 'private void applyCommonSettings(WebSettings s)' in java)
# ★ 阅读器必须与主窗口同样的 UA / 字号，否则「详情页正常、阅读页字号变了」
acs = block(java, 'private void applyCommonSettings(WebSettings s)', 2500)
# 截到方法真正结束（下一个 "    private " / "    @SuppressLint" 或 "    public "）
acs = re.split(r'\n    (?:private|public|protected|@SuppressLint|@Override)\b', acs)[0]
ok('applyCommonSettings 里设置 textZoom', 'setTextZoom' in acs)
ok('applyCommonSettings 里设置 UserAgent', 'setUserAgentString' in acs)
ok('setupWeb 调用 applyCommonSettings', bool(re.search(
    r'private void setupWeb\(\)[\s\S]{0,300}?applyCommonSettings\(s\)', java)))
ok('setupReaderWeb 调用 applyCommonSettings', bool(re.search(
    r'private void setupReaderWeb\(\)[\s\S]{0,300}?applyCommonSettings\(s\)', java)))
# ★ 多窗口只给主 WebView：阅读器不需要开新窗口，给了反而会把站内跳转
#   丢给 onCreateWindow 的临时 WebView，多一条不可控的分支。
sw = block(java, 'private void setupWeb()', 400)
ok('setupWeb 里设置 setSupportMultipleWindows（主窗口独有）',
   'setSupportMultipleWindows' in sw)
ok('applyCommonSettings 里没有 setSupportMultipleWindows',
   'setSupportMultipleWindows' not in acs)

print('')
print('[M] Java：阅读器窗口与主窗口的界面状态互不打架')
am = block(java, 'private void applyMode(String u)', 2000)
ok('applyMode 在 readerOpen 时直接返回（不顶掉沉浸态）',
   bool(re.search(r'private void applyMode\(String u\)\s*\{\s*if \(readerOpen\) return;', am)))
ok('存在 rbSync() 同步主窗口 UI', 'private void rbSync()' in java)
ok('enterReader 调用 rbSync', 'rbSync()' in enter)
ok('preloadNextChapter 判断用 readerUrl（不再是 web.getUrl）',
   bool(re.search(r'private void preloadNextChapter\(String nextHid\)[\s\S]{0,800}?readerUrl\.contains\(nextHid\)', java)))
ok('onSaveInstanceState 保存 rdWeb 状态', bool(re.search(
    r'onSaveInstanceState\(Bundle out\)[\s\S]{0,600}?rdWeb\.saveState', java)))

print('')
print('==== %d passed, %d failed ====' % (npass, nfail))
raise SystemExit(1 if nfail else 0)

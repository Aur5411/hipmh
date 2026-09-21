# -*- coding: utf-8 -*-
"""v2.1.0 阅读进度（继续阅读）回归测试（源码级 + 逻辑复刻）。

覆盖用户明确要求的：书籍详情页要能实时记录「继续阅读到第几章」。
v2.0.0 上线后用户反馈「读到第六章还显示第五章」，v2.1.0 重写上报链路：
  - **直接读服务端渲染的 DOM 属性同步上报**（#chapcontent 的 data-chapter-num /
    data-frontend-hid / data-manga-id，站点加载器还会补 data-chapter-number /
    data-chapter-title），不再依赖一次性的 fetch（fetch 失败 = 该章进度永久丢失）；
  - DOM 缺章号时才回退站点 API，失败清去重标记，轮询自动补报；
  - 2 秒 setInterval 轮询 + MutationObserver 盯 #chapcontent 属性 + astro 事件；
  - **整块只在 allowNext=true（真实阅读窗口）注入** —— 后台预抓 WebView 不再上报；
  - exitReader 关窗时 rdWeb 补跑一次 __hipProg（幂等）；
  - progressOf 桥按「当前页 key + 短形态 key」双 key 兜底查询
    （详情页 URL 可能是长形态 /works/<b64>-<slug>，上报按短形态落库）；
  - chapterFeHid 归一：落库 hid 已是前端形态原样返回，老数据（API hid）重编码。
链路：__hipProg → HipApp.reportProgress → Store.saveProgress（prog 表无条件写）
  → 详情页 DETAIL_PROGRESS_JS 调 HipApp.progressOf(url)
  → 把 #reading-btn「開始閱讀」改成「继续阅读 第X话 · 共N章」并直达上次章节
"""
import base64
import io
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
MA = os.path.join(HERE, 'app', 'src', 'main', 'java', 'com', 'hipmh', 'app',
                  'MainActivity.java')
ST = os.path.join(HERE, 'app', 'src', 'main', 'java', 'com', 'hipmh', 'app',
                  'Store.java')
GRADLE = os.path.join(HERE, 'app', 'build.gradle')

java = io.open(MA, encoding='utf-8').read()
store = io.open(ST, encoding='utf-8').read()
gradle = io.open(GRADLE, encoding='utf-8').read()

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


def method(src, sig):
    """按花括号配对取方法体（与 extract_js.find_method 同规则）。"""
    i = src.find(sig)
    if i < 0:
        return ''
    j = src.find('{', i)
    depth = 0
    k = j
    instr = False
    while k < len(src):
        c = src[k]
        if instr:
            if c == '\\':
                k += 2
                continue
            if c == '"':
                instr = False
            k += 1
            continue
        if c == '"':
            instr = True
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[j:k + 1]
        k += 1
    return ''


def js_of_field(src, name):
    """把 Java 字符串字段反解成 JS（简化版：拼接相邻字面量，处理常用转义）。"""
    m = re.search(r'String\s+' + re.escape(name) + r'\s*=\s*', src)
    if not m:
        return ''
    i = m.end()
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j + 1
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == ';':
            body = src[m.end():i]
            parts = re.findall(r'"((?:[^"\\]|\\.)*)"', body)
            out = []
            for p in parts:
                buf = []
                q = 0
                while q < len(p):
                    ch = p[q]
                    if ch == '\\' and q + 1 < len(p):
                        nx = p[q + 1]
                        mp = {'n': '\n', 't': '\t', 'r': '\r', 'b': '\b',
                              'f': '\f', '"': '"', "'": "'", '\\': '\\'}
                        if nx in mp:
                            buf.append(mp[nx])
                            q += 2
                            continue
                    buf.append(ch)
                    q += 1
                out.append(''.join(buf))
            return ''.join(out)
        i += 1
    return ''


print('[A] Store：prog 表与迁移')
ok('DB 版本升到 5', 'private static final int VER = 5;' in store)
ok('onCreate 建 prog 表', 'CREATE TABLE IF NOT EXISTS prog(' in store)
ok('prog 表含 ch/ch_title/ch_total/ch_hid/ts 列',
   bool(re.search(r'CREATE TABLE IF NOT EXISTS prog\([\s\S]{0,200}?'
                  r'ch INTEGER DEFAULT 0[\s\S]{0,80}?ch_title TEXT[\s\S]{0,80}?'
                  r'ch_total INTEGER DEFAULT 0[\s\S]{0,80}?ch_hid TEXT[\s\S]{0,80}?ts INTEGER',
                  store)))
ok('onUpgrade 有 o<5 分支建 prog 表',
   bool(re.search(r'if \(o < 5\)[\s\S]{0,400}?CREATE TABLE IF NOT EXISTS prog\(', store)))
ok('fav 表也带 ch/ch_title/ch_total/ch_hid 四列',
   'ch INTEGER DEFAULT 0, ch_title TEXT, ch_total INTEGER DEFAULT 0,'
   in store.replace('"', '').replace('\n', ' ').replace('  ', ' ') or
   bool(re.search(r'ch INTEGER DEFAULT 0, ?\n?\s*ch_title TEXT, ?\n?\s*ch_total INTEGER DEFAULT 0, ?\n?\s*ch_hid TEXT',
                  store)))

print('')
print('[B] Store：saveProgress 语义')
sp = method(store, 'void saveProgress(String url, int num, String chTitle, int total, String chHid)')
ok('存在 saveProgress(url,num,chTitle,total,chHid)', bool(sp))
ok('prog 表无条件 upsert（CONFLICT_REPLACE）',
   'insertWithOnConflict("prog"' in sp and 'CONFLICT_REPLACE' in sp)
ok('fav 只镜像已收藏（有 has("fav") 守卫）', 'has("fav", url)' in sp)
ok('num<=0 忽略', 'num <= 0' in sp)
ok('total<=0 保留旧值', 'if (total > 0)' in sp)
ok('hid 为空保留旧值', 'if (chHid != null && !chHid.isEmpty())' in sp)

print('')
print('[C] Store：progressOf 读取（prog 优先，fav 回退）')
po = method(store, 'Progress progressOf(String url)')
ok('存在 progressOf()', bool(po))
ok('先查 prog 表', po.find('FROM prog') < po.find('FROM fav') and 'FROM prog' in po)
ok('fav 回退兜底老数据', 'FROM fav' in po)
ok('ch<=0 视为无进度（返回 null）', 'n > 0' in po)
ok('Progress 快照带 chHid', re.search(r'class Progress[\s\S]{0,400}?String chHid', store) is not None)

print('')
print('[D] Store：Item 扩展')
ok('Item 新增 ch/chTitle/chTotal/chHid 字段',
   all(k in store for k in ('public final int ch;', 'public final String chTitle;',
                            'public final int chTotal;', 'public final String chHid;')))
ok('list() 查询带新列',
   'SELECT title,url,ts,st,cover,ch,ch_title,ch_total,ch_hid' in store.replace('\n', ' ')
   .replace('  ', ' ') or bool(re.search(
       r'SELECT title,url,ts,st,cover,ch,ch_title,ch_total,ch_hid', store.replace('\n', ' '))))

print('')
print('[E] MainActivity：reportProgress 桥')
rp = method(java, 'public void reportProgress(String mid, int num, String title, int total, String hid)')
ok('存在 reportProgress 五参桥', bool(rp))
ok('mid 解析成数字 id', 'Integer.parseInt' in rp)
ok('id<=0 或 num<=0 直接忽略', 'id <= 0 || num <= 0' in rp)
ok('按 workUrlOfId(id) 归一成作品 key', 'workUrlOfId(id)' in rp)
ok('不再要求已收藏（prog 表人人可记）', 'store.has("fav", key)' not in rp)
ok('调 store.saveProgress', 'store.saveProgress(key, num, title, total, hid)' in rp)

print('')
print('[F] MainActivity：progressOf 桥（详情页同步取进度）')
pf = method(java, 'public String progressOf(String url)')
ok('存在 progressOf 桥', bool(pf))
ok('favKeyFor 归一', 'favKeyFor(url)' in pf)
ok('调 store.progressOf', 'store.progressOf(k)' in pf)
ok('返回 JSON 含 num/title/total/mid/hid',
   all(k in pf for k in ('\\"num\\"', '\\"title\\"', '\\"total\\"', '\\"mid\\"', '\\"hid\\"')))
ok('★ v2.1.0 双 key 兜底：长形态查不到按短 key 补查',
   'p == null && id > 0' in pf and 'workUrlOfId(id)' in pf and 'store.progressOf(sk)' in pf)
ok('★ v2.1.0 chapterFeHid 归一 hid', 'chapterFeHid(p.chHid, id)' in pf)

print('')
print('[G] MainActivity：hid 还原辅助函数')
ok('workUrlIdB64：从 /works/<b64> 抠出 b64 段', 'private static String workUrlIdB64' in java)
ok('chapterIdFromApiHid：解 b64url("c:<id>")', 'private static int chapterIdFromApiHid' in java)
ca = method(java, 'private static int chapterIdFromApiHid(String apiHid)')
ok('chapterIdFromApiHid 找 "c:" 前缀', 'dec.indexOf("c:")' in ca)
ok('chapterIdFromApiHid 用 decodeB64Url（自动去 - 杂尾）', 'decodeB64Url(apiHid)' in ca)
be = method(java, 'private static String b64UrlEncode(String s)')
ok('b64UrlEncode 与站点一致（URL_SAFE/NO_PADDING/NO_WRAP）',
   all(k in be for k in ('URL_SAFE', 'NO_PADDING', 'NO_WRAP')))
ok('chapterFeHid 定义存在', 'private static String chapterFeHid(String hid, int mid)' in java)
fe = method(java, 'private static String chapterFeHid(String hid, int mid)')
ok('chapterFeHid：前端形态原样返回（含 m: 且 c: 在后）',
   'dec.indexOf("m:") >= 0' in fe and 'dec.indexOf("c:") > dec.indexOf("m:")' in fe)
ok('chapterFeHid：API 形态重编码 m:<mid>-c:<chId>',
   'b64UrlEncode("m:" + mid + "-c:" + chId)' in fe)

print('')
print('[H] 逻辑复刻：hid 编码/解码闭环')
def b64url(enc):
    return base64.urlsafe_b64encode(enc.encode()).decode().rstrip('=')


def dec_b64(s):
    dash = s.find('-')
    head = s[:dash] if dash > 0 else s
    t = head.replace('-', '+').replace('_', '/')
    t += '=' * ((4 - len(t) % 4) % 4)
    return base64.b64decode(t).decode('utf-8', 'ignore')


ok('前端 hid = b64url("m:15031-c:13975")（与站点開始閱讀按钮同构）',
   b64url('m:15031-c:13975') == 'bToxNTAzMS1jOjEzOTc1',
   b64url('m:15031-c:13975'))
ok('API hid = b64url("c:13975")（阅读器 data-api-hid 形态）',
   b64url('c:13975') == 'YzoxMzk3NQ', b64url('c:13975'))
ok('API hid 解码取前段 → c:13975', dec_b64('YzoxMzk3NQ-MTUwMzE6MS4wMA') == 'c:13975',
   dec_b64('YzoxMzk3NQ-MTUwMzE6MS4wMA'))
# 复刻 chapterIdFromApiHid 的取数规则（找 "c:" 前缀后取连续数字）
api_hid = 'YzoxMzk3NQ-MTUwMzE6MS4wMA'
d = dec_b64(api_hid)
ci = d.find('c:')
mch = re.match(r'\d+', d[ci + 2:])
chid = int(mch.group(0)) if mch else -1
ok('复刻解析章节 id = 13975', chid == 13975, chid)
ok('闭环：由 API hid + 作品id 还原前端 hid',
   b64url('m:15031-c:%d' % chid) == 'bToxNTAzMS1jOjEzOTc1')
# 复刻 chapterFeHid：前端形态原样返回
fe_hid = 'bToxNTAzMS1jOjEzOTc1-MTUwMzE6MS4wMA'
dfe = dec_b64(fe_hid)
ok('复刻 chapterFeHid：前端形态（含 m: 且 c: 在后）原样返回',
   dfe.find('m:') >= 0 and dfe.find('c:') > dfe.find('m:') and dfe.startswith('m:'))
ok('复刻 chapterFeHid：API 形态（无 m:）走重编码分支',
   not dec_b64(api_hid).startswith('m:'))

print('')
print('[I] readerJs：进度上报块（v2.1.0 重写）')
rbody = method(java, 'private String readerJs(boolean allowNext) {')
ok('readerJs 存在 __hipProg 函数', '__hipProg' in rbody)
ok('★ v2.1.0 整块只在 allowNext=true 注入（bgWeb 预抓不上报）',
   'String progressBlock = allowNext ? (' in rbody)
ok('★ 直接读 #chapcontent 服务端渲染属性', "getElementById('chapcontent')" in rbody)
ok('读 data-chapter-num / data-chapter-number（章号不再依赖 fetch）',
   'data-chapter-num' in rbody and 'data-chapter-number' in rbody)
ok('读 data-frontend-hid（上报带前端 hid）', 'data-frontend-hid' in rbody)
ok('章号格式化 data-chapter-number-format', 'data-chapter-number-format' in rbody)
ok('站点加载器补的 data-chapter-title 优先', 'data-chapter-title' in rbody)
ok('上报 HipApp.reportProgress(mid, ns|0, …, fh||ah)',
   'HipApp.reportProgress(mid,ns|0' in rbody and 'fh||ah' in rbody)
ok('JS 侧按 __hipProgLast 去重（同一章只报一次）', '__hipProgLast' in rbody)
ok('兜底 fetch /v2/chapter 保留（DOM 缺章号时）', '/v2/chapter?hid=' in rbody)
ok('★ fetch 失败清去重标记（轮询自动补报，不再永久丢章）',
   'window.__hipProgLast=null' in rbody)
ok('★ 2 秒 setInterval 轮询兜底', 'setInterval(__hipProg,2000)' in rbody)
ok('★ MutationObserver 盯 #chapcontent 属性变化（attributeFilter）',
   'attributeFilter' in rbody and 'data-chapter-num' in rbody)
ok('软导航后重跑（astro:after-swap 等）',
   "'astro:after-swap'" in rbody and "'astro:page-load'" in rbody)
ok('progressBlock 真的拼进返回串（+ progressBlock）',
   re.search(r'\+\s*nextBlock\s*\n\s*\+\s*progressBlock', rbody) is not None or
   ('nextBlock' in rbody and 'progressBlock' in rbody))
ok('bgWeb 注入用 allowNext=false（preloadNextChapter 只预抓不上报）',
   'injectReaderInto(v, u, false)' in java)

print('')
print('[J] DETAIL_PROGRESS_JS：详情页按钮改造')
djs = js_of_field(java, 'DETAIL_PROGRESS_JS')
ok('DETAIL_PROGRESS_JS 存在且非空', len(djs) > 500, len(djs))
ok('只在 /works/ 详情页生效', "indexOf('/works/')<0" in djs)
ok('定位 #reading-btn', "getElementById('reading-btn')" in djs)
ok('文案「继续阅读 第X话」', '继续阅读' in djs and "'第'+num+'话'" in djs)
ok('带总章数「共N章」', "共'+tot+'章" in djs)
ok('跳转 /chapter/go?hid=…&m=…（进阅读器窗口）',
   "'/chapter/go?hid='" in djs and "'&m='" in djs)
ok('打 data-hip-cont 标记', 'data-hip-cont' in djs)
ok('幂等：已是我们的文案就不再改（防自激）',
   "indexOf('继续阅读')===0" in djs)
ok('MutationObserver 兜站点脚本晚改写', 'MutationObserver' in djs and '__hipContObs' in djs)
ok('无进度时不动按钮（保留站点 開始閱讀）', 'if(!pr){return;}' in djs)
ok('软导航/定时重试', 'astro:after-swap' in djs and 'setTimeout(tick,1200)' in djs)

print('')
print('[K] 注入链路与守卫')
ok('onPageFinished 调 injectDetailProgress()',
   bool(re.search(r'injectCover\(\);\s*\n\s*injectDetailProgress\(\);', java)))
ok('exitReader 里也重跑（关窗立即刷新按钮）',
   'injectDetailProgress();' in method(java, 'private void exitReader() {'))
ok('exitReader 关窗补跑 __hipProg（幂等，防最后一章进度丢）',
   'window.__hipProg&&__hipProg()' in method(java, 'private void exitReader() {'))
ok('存在 injectDetailProgress 定义', 'private void injectDetailProgress()' in java)
ok('GUARD_JS 豁免 data-hip-cont（点击不被守卫吞掉）',
   "a.getAttribute('data-hip-cont')" in java)

print('')
print('[L] 版本号')
ok('versionCode 26', 'versionCode 26' in gradle)
ok("versionName '2.1.0'", "versionName '2.1.0'" in gradle)

print('')
print('==== %d passed, %d failed ====' % (npass, nfail))
import sys
sys.exit(0 if nfail == 0 else 1)

#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""离线验证 stripNode / stripRedirectHijack 的配对扫描逻辑（用真实站点 HTML）"""
import io, re, ssl, gzip, urllib.request, os

def strip_node(html, node_id):
    """与 MainActivity.stripNode 同一算法（Python 复刻）"""
    key = 'id="%s"' % node_id
    at = html.find(key)
    if at < 0:
        return html, False
    start = html.rfind('<', 0, at)
    if start < 0:
        return html, False
    tag = html[start + 1:].lstrip()
    sp = 0
    while sp < len(tag) and not tag[sp].isspace() and tag[sp] not in '>/':
        sp += 1
    name = tag[:sp]
    if not name:
        return html, False

    i = start; depth = 0; end = -1
    while i < len(html):
        lt = html.find('<', i)
        if lt < 0:
            break
        if html.startswith('<!--', lt):
            ce = html.find('-->', lt + 4)
            i = len(html) if ce < 0 else ce + 3
            continue
        gt = html.find('>', lt)
        if gt < 0:
            break
        inner = html[lt + 1:gt].strip()
        closing = inner.startswith('/')
        body = inner[1:].strip() if closing else inner
        e2 = 0
        while e2 < len(body) and not body[e2].isspace() and body[e2] not in '>/':
            e2 += 1
        n2 = body[:e2]
        if n2.lower() == name.lower():
            if closing:
                depth -= 1
                if depth <= 0:
                    end = gt + 1
                    break
            else:
                depth += 1
        i = gt + 1
    if end < 0:
        return html, False
    return html[:start] + html[end:], True


def fetch(u):
    ctx = ssl.create_default_context(); ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ua = ('Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 '
          '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36')
    req = urllib.request.Request(u, headers={'User-Agent': ua, 'Accept-Encoding': 'gzip'})
    r = urllib.request.urlopen(req, timeout=25, context=ctx)
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.decompress(raw)
    return raw.decode('utf-8', 'ignore')


npass = 0; nfail = 0
def ok(name, cond, extra=''):
    global npass, nfail
    if cond:
        npass += 1; print('  PASS  ' + name)
    else:
        nfail += 1; print('  FAIL  ' + name + ('  -> ' + str(extra) if extra else ''))


print('[A] 合成用例（含属性里带 > 的陷阱）')
tricky = ('<html><body>'
          '<div id="nav-redirect-config" class="hidden" '
          'data-config="{\\"selector\\":\\"#reading-btn\\",\\"a\\">\\" ></div>'
          '<script>var x=1;</script>'
          '<p>正文</p>'
          '</body></html>')
out, hit = strip_node(tricky, 'nav-redirect-config')
ok('能找到并删除', hit)
ok('配置块已消失', 'nav-redirect-config' not in out)
ok('没有多删正文', '<p>正文</p>' in out)
ok('没有把后面的 script 一起吃掉', '<script>var x=1;</script>' in out)

print('\n[B] 真实站点 HTML')
for url in ['https://m.hipmh.com/', 'https://m.hipmh.com/history']:
    try:
        h = fetch(url)
    except Exception as e:
        print('  SKIP  %s (%s)' % (url, e)); continue
    has = "nav-redirect-config" in h
    had_div = 'id="nav-redirect-config"' in h
    out, hit = strip_node(h, "nav-redirect-config")
    print("  -- %s  len=%d  hasConfig=%s  stripped=%s" % (url, len(h), has, hit))
    if had_div:
        # 配置「元素」必须消失；脚本里那句 getElementById(...) 留着无害（f 为 null 直接 return）
        ok("%s 配置元素 id=nav-redirect-config 已移除" % url,
           'id="nav-redirect-config"' not in out)
        ok("%s 广告目标 1wm.top 已从配置中消失" % url, "1wm.top" not in out)
        ok("%s 广告目标 hai8g.com 已从配置中消失" % url, "hai8g.com" not in out)
        ok("%s 页面主体仍在（container 存在）" % url, "container" in out)
        removed = len(h) - len(out)
        ok("%s 只删掉配置块，量级合理 (<%d 字节)" % (url, 3000),
           removed < 3000, "实际删除 %d 字节" % removed)
        # 脚本仍能被它自己的 getElementById 兜住（f=null -> return）
        ok("%s 劫持脚本的 f=null 守卫仍在（脚本不会报错）" % url,
           "nav-redirect-config" not in out or "if(!f)return" in out)

print('\n==== %d passed, %d failed ====' % (npass, nfail))

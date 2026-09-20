#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""离线验证 stripGtm / stripRedirectHijack 的 GTM 摘除逻辑（用真实站点 HTML）

背景（v1.0.10）：m.hipmh.com 首页含
  - 内联 dataLayer 脚本，异步加载 https://www.googletagmanager.com/gtm.js?id=GTM-KWM3FNGT
  - <iframe src="https://www.googletagmanager.com/ns.html?id=GTM-KWM3FNGT">
GTM 容器在运行时注入广告 iframe/弹窗 —— 这就是「广告又出现了」的来源。
本测试复刻 MainActivity.stripGtm 的算法并核对结果。
"""
import io, re, ssl, gzip, urllib.request, os, sys

# ---------------------------------------------------------------- 复刻 Java 算法
def strip_gtm(html):
    """与 MainActivity.stripGtm 同一算法（Python 复刻）"""
    if not html:
        return html
    out = html
    # 1) 内联/外链 script 块里含 gtm 特征的整块删
    guard = 0
    while guard < 40:
        guard += 1
        s = out.find('<script')
        hit_start = -1
        scan = 0
        while s >= 0:
            e = out.find('</script>', s)
            if e < 0:
                break
            body = out[s:e + 9]
            if ('googletagmanager.com' in body or 'gtm.js' in body
                    or 'dataLayer' in body or 'GTM-' in body):
                hit_start = s
                break
            scan = e + 9
            s = out.find('<script', scan)
        if hit_start < 0:
            break
        he = out.find('</script>', hit_start)
        if he < 0:
            break
        out = out[:hit_start] + out[he + 9:]
    # 2) GTM noscript iframe
    guard = 0
    while guard < 40:
        guard += 1
        i = out.find('<iframe')
        hit = -1
        scan = 0
        while i >= 0:
            e = out.find('>', i)
            if e < 0:
                break
            tag = out[i:e + 1]
            if 'googletagmanager.com' in tag or 'google-analytics.com' in tag:
                hit = i
                break
            scan = e + 1
            i = out.find('<iframe', scan)
        if hit < 0:
            break
        e2 = out.find('>', hit)
        if e2 < 0:
            break
        ce = out.find('</iframe>', e2)
        stop = ce + 9 if (ce >= 0 and ce - e2 < 600) else e2 + 1
        out = out[:hit] + out[stop:]
    return out


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


print('[A] 合成用例')
synth = ('<html><head>'
         '<script>(function(w,d,s,l,i){w[l]=w[l]||[];w[l].push({"gtm.start":1});'
         'var f=d.getElementsByTagName(s)[0],j=d.createElement(s);'
         'j.src="https://www.googletagmanager.com/gtm.js?id=GTM-ABC123";'
         'f.parentNode.insertBefore(j,f);})(window,document,"script","dataLayer");</script>'
         '<script>var keep=1;console.log("normal script");</script>'
         '</head><body>'
         '<noscript><iframe src="https://www.googletagmanager.com/ns.html?id=GTM-ABC123" '
         'height="0" width="0" style="display:none;visibility:hidden"></iframe></noscript>'
         '<p>正文</p>'
         '<iframe src="https://example.com/embed"></iframe>'
         '</body></html>')
o = strip_gtm(synth)
ok('GTM dataLayer 脚本已删', 'googletagmanager.com/gtm.js' not in o)
ok('GTM noscript iframe 已删', 'ns.html' not in o)
ok('GTM 容器 id 已消失', 'GTM-ABC123' not in o)
ok('普通 script 未误删', 'var keep=1' in o)
ok('正文未误删', '<p>正文</p>' in o)
ok('非 GTM iframe 未误删', 'example.com/embed' in o)

print('[B] 真实站点 HTML')
for url in ['https://m.hipmh.com/', 'https://m.hipmh.com/history']:
    try:
        h = fetch(url)
    except Exception as e:
        print('  SKIP  %s (%s)' % (url, e)); continue
    o = strip_gtm(h)
    print("  -- %s  len=%d -> %d" % (url, len(h), len(o)))
    if 'googletagmanager' in h:
        ok('%s googletagmanager 引用已清除' % url, 'googletagmanager' not in o)
    ok('%s 正文主体仍在 (container)' % url, 'container' in o)
    removed = len(h) - len(o)
    ok('%s 删除量级合理 (<20000 字节)' % url, removed < 20000, '实际 %d' % removed)

print('\n==== %d passed, %d failed ====' % (npass, nfail))
sys.exit(0 if nfail == 0 else 1)

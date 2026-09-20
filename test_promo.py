#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""离线验证 stripPromoCards / cleanupEmptyPromoWrappers（v1.3 新增）
用真实站点 HTML：两个「免费图库」推广卡必须被删，banner / 作品链接 / 正文不得被误伤。
Python 侧为 MainActivity.java 的逐行复刻（同算法）。
"""
import re, ssl, gzip, urllib.request

PROMO_HOSTS = ['g-mh.com', '18gallery.com']
PROMO_IMGS = ['g-mh-900', '18gallery-1']


# ---------- Java 复刻 ----------
def strip_promo_cards(html):
    if not html:
        return html
    out = html
    for _ in range(8):
        at = -1
        for host in PROMO_HOSTS:
            k = out.find(host)
            while k >= 0:
                lt = out.rfind('<a', 0, k)
                if lt >= 0:
                    gt = out.find('>', lt)
                    if gt >= 0 and gt > k:
                        if at < 0 or lt < at:
                            at = lt
                        break
                k = out.find(host, k + 1)
        if at < 0:
            break
        i = at
        depth = 0
        end = -1
        while i < len(out):
            lt = out.find('<', i)
            if lt < 0:
                break
            if out.startswith('<!--', lt):
                ce = out.find('-->', lt)
                i = ce + 3 if ce > 0 else len(out)
                continue
            if out.startswith('</a', lt):
                gt = out.find('>', lt)
                if gt < 0:
                    break
                depth -= 1
                if depth <= 0:
                    end = gt + 1
                    break
                i = gt + 1
                continue
            if out.startswith('<a', lt):
                gt = out.find('>', lt)
                if gt < 0:
                    break
                if out[lt:gt + 1].endswith('/>'):
                    i = gt + 1
                    continue
                depth += 1
                i = gt + 1
                continue
            i = lt + 1
        if end < 0:
            break
        out = out[:at] + out[end:]
    return cleanup_empty_promo_wrappers(out)


def is_blank_markup(s):
    if not s:
        return True
    t = re.sub(r'(?s)<script\b[^>]*>.*?</script>', '', s)
    t = re.sub(r'(?s)<style\b[^>]*>.*?</style>', '', t)
    t = re.sub(r'(?s)<!--.*?-->', '', t)
    t = re.sub(r'\s+', '', t)
    return t.replace('&nbsp;', '') == ''


def scan_empty_div(html, needle):
    k = html.find(needle)
    while k >= 0:
        lt = html.rfind('<', 0, k)
        if lt >= 0 and html.startswith('<div', lt):
            gt = html.find('>', k)
            if gt >= 0:
                depth = 0
                i = lt
                end = -1
                while i < len(html):
                    l2 = html.find('<div', i)
                    c2 = html.find('</div>', i)
                    if c2 < 0:
                        break
                    if l2 >= 0 and l2 < c2:
                        depth += 1
                        i = l2 + 4
                    else:
                        depth -= 1
                        i = c2 + 6
                        if depth <= 0:
                            end = i
                            break
                if end > gt:
                    if is_blank_markup(html[gt + 1:end - 6]):
                        return (lt, end)
        k = html.find(needle, k + 1)
    return None


def find_first_empty_wrapper(html):
    best = None
    for needle in ['class="flex flex-row gap-2 w-full flex-1"',
                   'class="container px-2"',
                   'class>',
                   'class=""']:
        r = scan_empty_div(html, needle)
        if r and (best is None or r[0] < best[0]):
            best = r
    return best


def cleanup_empty_promo_wrappers(html):
    if not html:
        return html
    out = html
    for _ in range(24):
        hit = find_first_empty_wrapper(out)
        if hit is None:
            break
        out = out[:hit[0]] + out[hit[1]:]
    return out


def fetch(u):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ua = ('Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 '
          '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36')
    req = urllib.request.Request(u, headers={'User-Agent': ua, 'Accept-Encoding': 'gzip'})
    r = urllib.request.urlopen(req, timeout=25, context=ctx)
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.decompress(raw)
    return raw.decode('utf-8', 'ignore')


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


print('[A] 合成用例：卡 A（div.container 包裹）+ 卡 B（空 class 包裹）')

A = ('<main><div class="container px-2">'
     '<div class="flex flex-row gap-2 w-full flex-1">'
     '<a href="https://g-mh.com/" class="flex bg-muted/80 h-12 my-2">'
     '<img src="https://s3-nl-01.mangabuddy.in/g-mh-900.webp" alt="G-MH">'
     '<span>免费高清图库</span></a>'
     '</div></div>'
     '<h1>正文标题</h1>'
     '<a href="/works/abc" class="block"><img src="/x.webp">真作品</a>'
     '</main>')
out = strip_promo_cards(A)
ok('卡 A 的 <a> 已被删除', 'g-mh.com' not in out)
ok('卡 A 的图片已删除', 'g-mh-900' not in out)
ok('卡 A 的空包裹 div 已收掉', 'flex flex-row gap-2 w-full flex-1' not in out)
ok('卡 A 的空 container 已收掉', '<div class="container px-2">' not in out)
ok('正文标题保留', '<h1>正文标题</h1>' in out)
ok('真实作品链接未误删', '/works/abc' in out and '真作品' in out)

B = ('<main><h1>正文</h1>'
     '<div class>'
     '<div class="flex flex-row gap-2 w-full flex-1">'
     '<a href="https://18gallery.com/" class="flex h-12">'
     '<img src="https://x/18gallery-1.webp" alt="18gallery">'
     '<span>免费图库</span></a>'
     '</div></div>'
     '<footer>页脚</footer></main>')
outB = strip_promo_cards(B)
ok('卡 B（空 class 外层）<a> 已删除', '18gallery.com' not in outB)
ok('卡 B 图片已删除', '18gallery-1' not in outB)
ok('卡 B 空包裹 div 已收掉', 'flex flex-row gap-2 w-full flex-1' not in outB)
ok('卡 B 空 <div class> 已收掉', '<div class>' not in outB)
ok('卡 B 正文保留', '<h1>正文</h1>' in outB and '<footer>页脚</footer>' in outB)
# 幂等 + 无推广卡时不动
outC = strip_promo_cards(outB)
ok('幂等（再跑一次结果不变）', outC == outB)
plain = '<html><body><p>没有推广卡</p><a href="/works/z">作品</a></body></html>'
ok('无推广卡时原样返回', strip_promo_cards(plain) == plain)
ok('空串安全', strip_promo_cards('') == '')

# 属性里带 > 的陷阱：不能截断
tricky = ('<div class="container px-2"><div class="flex flex-row gap-2 w-full flex-1">'
          '<a href="https://g-mh.com/" data-x="a>b">推广</a></div></div>'
          '<p>后文</p>')
outT = strip_promo_cards(tricky)
ok('属性含 > 时不截断（<a> 完整删除）', 'g-mh.com' not in outT)
ok('属性含 > 时后续内容不丢', '<p>后文</p>' in outT)

print('\n[B] 真实站点 HTML')
for url in ['https://m.hipmh.com/', 'https://m.hipmh.com/history']:
    try:
        h = fetch(url)
    except Exception as e:
        print('  SKIP  %s (%s)' % (url, e))
        continue
    had = [host for host in PROMO_HOSTS if host in h]
    had_img = [im for im in PROMO_IMGS if im in h]
    print('  -- %s  len=%d  命中 host=%s img=%s' % (url, len(h), had, had_img))
    out = strip_promo_cards(h)
    removed = len(h) - len(out)
    if had:
        for host in had:
            ok('%s 推广站点 %s 已删除' % (url, host), host not in out)
        for im in had_img:
            ok('%s 推广图 %s 已删除' % (url, im), im not in out)
        ok('%s 只删推广卡，量级合理 (<20000 字节)' % url, removed < 20000,
           '实际删除 %d 字节' % removed)
        ok('%s 页面主体仍在' % url, 'container' in out)
        ok('%s 作品链接未被误伤（/works/ 仍在）' % url, '/works/' in out)
    else:
        ok('%s 本次未下发推广卡 → 不应改动' % url, out == h)
        ok('%s 内容长度不变' % url, len(out) == len(h))

print('\n==== %d passed, %d failed ====' % (npass, nfail))

# -*- coding: utf-8 -*-
"""验证 bookUrlFromCurrent / workIdOf / decodeB64Url 的 URL 反推逻辑（Python 复刻）。
流程：从首页找一部作品 → 打开详情页 → 抽取阅读器链接 → 反推回详情页，比对是否一致。
"""
import re, ssl, gzip, base64, urllib.request

UA = ('Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36')


def fetch(u):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    req = urllib.request.Request(u, headers={'User-Agent': UA, 'Accept-Encoding': 'gzip'})
    r = urllib.request.urlopen(req, timeout=30, context=ctx)
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.decompress(raw)
    return raw.decode('utf-8', 'ignore')


def decode_b64_url(s):
    if not s:
        return None
    dash = s.find('-')
    head = s[:dash] if dash > 0 else s
    t = head.replace('-', '+').replace('_', '/')
    t += '=' * ((4 - len(t) % 4) % 4)
    try:
        return base64.b64decode(t).decode('utf-8', 'ignore')
    except Exception:
        return None


def work_id_of(u):
    if not u:
        return -1
    q = u.find('m=')
    while q >= 0:
        s = q + 2
        e = s
        while e < len(u) and u[e].isdigit():
            e += 1
        if e > s and (q == 0 or not u[q - 1].isalnum()):
            return int(u[s:e])
        q = u.find('m=', q + 2)
    h = u.find('hid=')
    if h >= 0:
        s = h + 4
        e = s
        while e < len(u) and u[e] not in '&#':
            e += 1
        dec = decode_b64_url(u[s:e])
        if dec:
            mi = dec.find('m:')
            if mi >= 0:
                p = mi + 2
                z = p
                while z < len(dec) and dec[z].isdigit():
                    z += 1
                if z > p:
                    return int(dec[p:z])
    return -1


def book_url_from(u):
    i = work_id_of(u)
    if i <= 0:
        return None
    b64 = base64.b64encode(('m:%d' % i).encode()).decode().rstrip('=')
    return 'https://m.hipmh.com/works/' + b64


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


print('[A] 单元用例')
# 已知实测样本：hid=bToxNTAzMS1jOjEzOTc1-... → m:15031
hid = 'bToxNTAzMS1jOjEzOTc1-MTUwMzE6MS4wMA'
ok('decodeB64Url 解出 m:15031-c:13975',
   decode_b64_url(hid) == 'm:15031-c:13975', decode_b64_url(hid))
reader = 'https://m.hipmh.com/chapter/go?hid=%s&m=15031&ct=abc' % hid
ok('workIdOf: m= 参数优先 → 15031', work_id_of(reader) == 15031, work_id_of(reader))
ok('bookUrlFromCurrent 得到 /works/bToxNTAzMQ',
   book_url_from(reader) == 'https://m.hipmh.com/works/bToxNTAzMQ', book_url_from(reader))
# 无 m= 参数时走 hid
reader2 = 'https://m.hipmh.com/chapter/go?hid=%s&ct=x' % hid
ok('无 m= 时回退解 hid → 15031', work_id_of(reader2) == 15031, work_id_of(reader2))
# 普通路径形态 /works/xxx/chapter/12
ok('/works/bToxNTAzMQ/chapter/12 无 m=/hid → -1（交给历史回退）',
   work_id_of('https://m.hipmh.com/works/bToxNTAzMQ/chapter/12') == -1)

print('\n[B] 真实站点端到端反推')
page = fetch('https://m.hipmh.com/')
links = re.findall(r'href="(/works/[^"#]+)"', page)
links = [l for l in links if '/chapter/' not in l]
print('  首页找到 %d 个作品链接' % len(links))
ok('首页有作品链接', len(links) > 0)

tested = 0
for lk in links[:8]:
    u = 'https://m.hipmh.com' + lk
    try:
        d = fetch(u)
    except Exception as e:
        print('  SKIP %s (%s)' % (u, e))
        continue
    m = re.search(r'href="(/chapter/go\?[^"]+)"', d)
    if not m:
        print('  -- %s 未找到 /chapter/go 链接' % lk)
        continue
    rd = 'https://m.hipmh.com' + m.group(1).replace('&amp;', '&')
    back = book_url_from(rd)
    # 详情页 URL 形态：/works/<b64("m:<id>")>-<slug>-<id>
    # 反推出的短形式 /works/<b64("m:<id>")> 站点 200 且 title 一致（已实测），
    # 所以这里按「b64 段一致」判定，而不是整串相等。
    want_b64 = lk.split('/works/')[1].split('-')[0]
    got_b64 = back.split('/works/')[1] if back else ''
    print('  -- 详情 %s' % lk)
    print('     阅读器 %s' % rd[:130])
    print('     反推   %s   (b64: %s vs %s)' % (back, got_b64, want_b64))
    ok('反推 b64 段与原详情页一致', got_b64 == want_b64, '%s vs %s' % (got_b64, want_b64))
    # 再确认短形式确实能打开同一本书（比对 title）
    try:
        t_canon = re.search(r'<title>(.*?)</title>', d, re.S)
        d2 = fetch(back)
        t_short = re.search(r'<title>(.*?)</title>', d2, re.S)
        same_title = (t_canon and t_short
                      and t_canon.group(1).strip() == t_short.group(1).strip())
        ok('短形式打开的书籍 title 一致', bool(same_title),
           '%r vs %r' % (t_canon.group(1).strip() if t_canon else None,
                         t_short.group(1).strip() if t_short else None))
        ok('短形式页含阅读器入口', '/chapter/go?' in d2)
    except Exception as e:
        ok('短形式可打开', False, str(e))
    tested += 1
    if tested >= 3:
        break

ok('至少完成 1 组端到端验证', tested > 0)

print('\n==== %d passed, %d failed ====' % (npass, nfail))

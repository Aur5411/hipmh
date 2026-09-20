# -*- coding: utf-8 -*-
"""验证 stripSigninPrompt（v1.3）：历史页/侧边栏的登入块必须消失，
但已登录的 #navbar-sidebar-user-authed（个人中心+登出）必须原样保留。
Python 侧为 MainActivity.java 的逐行复刻。
"""
import re, ssl, gzip, urllib.request


def strip_node(html, node_id):
    key = 'id="%s"' % node_id
    at = html.find(key)
    if at < 0:
        return html
    start = html.rfind('<', 0, at)
    if start < 0:
        return html
    tag = html[start + 1:].lstrip()
    sp = 0
    while sp < len(tag) and not tag[sp].isspace() and tag[sp] not in '>/':
        sp += 1
    name = tag[:sp]
    if not name:
        return html
    i = start
    depth = 0
    end = -1
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
        return html
    return html[:start] + html[end:]


def strip_element_by_attr(html, marker):
    at = html.find(marker)
    if at < 0:
        return html
    start = html.rfind('<', 0, at)
    if start < 0:
        return html
    tag = html[start + 1:].lstrip()
    sp = 0
    while sp < len(tag) and not tag[sp].isspace() and tag[sp] not in '>/':
        sp += 1
    name = tag[:sp]
    if not name:
        return html
    i = start
    depth = 0
    end = -1
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
                if not html[lt:gt + 1].endswith('/>'):
                    depth += 1
        i = gt + 1
    if end < 0:
        return html
    return html[:start] + html[end:]


def strip_signin_prompt(html):
    if not html:
        return html
    out = html
    for _ in range(4):
        nxt = strip_node(out, 'navbar-sidebar-user-not-authed')
        if nxt == out:
            break
        out = nxt
    for _ in range(8):
        nxt = strip_element_by_attr(out, 'data-navbar-sidebar-signin')
        if nxt == out:
            break
        out = nxt
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


print('[A] 合成用例')

page = ('<div id="navbar-sidebar">'
        '<div data-navbar-sidebar-content>'
        '<span>登入以同步您的資料</span>'
        '<div id="navbar-sidebar-user-not-authed" class="hidden">'
        '<button type="button" class="w-full px-4 py-2.5 rounded-lg bg-primary" '
        'data-navbar-sidebar-signin> 登入 </button>'
        '</div>'
        '<div id="navbar-sidebar-user-authed" class="hidden">'
        '<a href="/user/profile" aria-label="個人中心"><span>用户</span></a>'
        '<button type="button" data-navbar-sidebar-signout aria-label="登出">登出</button>'
        '</div>'
        '<div id="navbar-sidebar-history-list"></div>'
        '<button data-navbar-sidebar-close aria-label="Close">X</button>'
        '</div></div>')
out = strip_signin_prompt(page)
ok('未登录容器 #navbar-sidebar-user-not-authed 已删除',
   'navbar-sidebar-user-not-authed' not in out)
ok('登入按钮 data-navbar-sidebar-signin 已删除',
   'data-navbar-sidebar-signin' not in out)
ok('登入文字已消失', '登入 </button>' not in out)
ok('已登录容器 #navbar-sidebar-user-authed 保留', 'navbar-sidebar-user-authed' in out)
ok('个人中心链接保留', '/user/profile' in out)
ok('登出按钮保留', 'data-navbar-sidebar-signout' in out)
ok('侧边栏主体保留', 'data-navbar-sidebar-content' in out)
ok('关闭按钮保留', 'data-navbar-sidebar-close' in out)
ok('阅读记录列表容器保留', 'navbar-sidebar-history-list' in out)

# 容器 id 被改掉时，靠 attrs 兜底也能删
alt = ('<div><div id="whatever-box"><button data-navbar-sidebar-signin>登入</button></div>'
       '<p>后文</p></div>')
outAlt = strip_signin_prompt(alt)
ok('兜底：容器 id 变了也能删掉登入按钮', 'data-navbar-sidebar-signin' not in outAlt)
ok('兜底：不误删后续内容', '<p>后文</p>' in outAlt)

# 幂等 & 无目标时不动
ok('幂等', strip_signin_prompt(out) == out)
plain = '<html><body><p>没有登入按钮</p><a href="/works/1">作品</a></body></html>'
ok('无目标时原样返回', strip_signin_prompt(plain) == plain)
ok('空串安全', strip_signin_prompt('') == '')

print('\n[B] 真实站点 HTML')
for url in ['https://m.hipmh.com/history', 'https://m.hipmh.com/']:
    try:
        h = fetch(url)
    except Exception as e:
        print('  SKIP  %s (%s)' % (url, e))
        continue
    had_container = 'id="navbar-sidebar-user-not-authed"' in h
    had_btn = 'data-navbar-sidebar-signin' in h
    out = strip_signin_prompt(h)
    print('  -- %s  len=%d  原含容器=%s 按钮=%s' % (url, len(h), had_container, had_btn))
    if had_container or had_btn:
        ok('%s 登入容器已删除' % url, 'id="navbar-sidebar-user-not-authed"' not in out)
        ok('%s 登入按钮已删除' % url, 'data-navbar-sidebar-signin' not in out)
        ok('%s 已登录容器未被误删' % url,
           ('navbar-sidebar-user-authed' in out) == ('navbar-sidebar-user-authed' in h))
        ok('%s 侧边栏主体保留' % url, 'data-navbar-sidebar' in out)
        ok('%s 页面结构保留（nav 仍在）' % url, '<nav' in out)
        ok('%s 删除量合理 (<4000 字节)' % url, len(h) - len(out) < 4000,
           '实际 %d' % (len(h) - len(out)))
    else:
        ok('%s 无登入块 → 不应改动' % url, out == h)

print('\n==== %d passed, %d failed ====' % (npass, nfail))

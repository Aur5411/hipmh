#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""离线验证 stripFavButtons（v1.8：删掉详情页右上角站点原生「爱心/收藏」按钮）。

算法与 MainActivity.stripFavButtons 一致（Python 复刻），
并用**真实详情页 HTML** 复核：删完后手机端「喜歡」按钮与桌面端 #favorite-btn 都不在，
而页面主体（作品标题、章节列表、我们的 nav「更多」）必须完好。
"""
import gzip
import io
import os
import re
import ssl
import sys
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

FAV_IDS = ["mobile-favorite-btn", "favorite-btn"]


def strip_fav_buttons(html):
    """与 MainActivity.stripFavButtons 同算法。返回 (新html, 删除次数)。"""
    if not html:
        return html, 0
    out = html
    n = 0
    for _ in range(6):
        at = -1
        open_tag = None
        close_tag = None
        for idv in FAV_IDS:
            k = out.find('id="%s"' % idv)
            if k < 0:
                continue
            lt = out.rfind('<button', 0, k)
            tag = 'button'
            if lt < 0 or out.find('>', lt) < k:
                lt = out.rfind('<div', 0, k)
                tag = 'div'
            if lt < 0:
                continue
            gt = out.find('>', lt)
            if gt < 0 or gt < k:
                continue
            if at < 0 or lt < at:
                at = lt
                open_tag = '<' + tag
                close_tag = '</' + tag
        if at < 0 or open_tag is None:
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
                i = len(out) if ce < 0 else ce + 3
                continue
            if out.startswith(close_tag, lt):
                gt = out.find('>', lt)
                if gt < 0:
                    break
                depth -= 1
                if depth <= 0:
                    end = gt + 1
                    break
                i = gt + 1
                continue
            if out.startswith(open_tag, lt):
                gt = out.find('>', lt)
                if gt < 0:
                    break
                if not out[lt:gt + 1].endswith('/>'):
                    depth += 1
                i = gt + 1
                continue
            i = lt + 1
        if end < 0:
            break
        out = out[:at] + out[end:]
        n += 1
    return out, n


def fetch(u):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    req = urllib.request.Request(u, headers={
        'User-Agent': UA, 'Accept-Encoding': 'gzip',
        'Accept-Language': 'zh-CN,zh;q=0.9'})
    r = urllib.request.urlopen(req, timeout=25, context=ctx)
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.decompress(raw)
    return raw.decode('utf-8', 'replace')


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


# ---------- A. 合成用例：还原真实结构 + 嵌套陷阱 ----------
print('[A] 合成用例')
syn = ('<html><body>'
       '<div class="flex justify-end">'
       '<button type="button" id="mobile-favorite-btn" class="active:opacity-30 mr-[16px] use-gpu" '
       'aria-label="喜歡" data-action="like" data-source-key="15031">'
       '<svg id="mobile-heart-icon" aria-hidden="true"><path d="M10 19"/></svg>'
       '</button>'
       '</div>'
       '<div class="hidden md:flex">'
       '<div id="favorite-btn" class="justify-center cursor-pointer" title="收藏" data-source-key="15031">'
       '<svg id="heart-icon" aria-hidden="true"><path d="M10 19"/></svg>'
       '</div>'
       '</div>'
       '<button type="button" class="p-1" aria-label="更多" data-navbar-more-trigger>汉堡</button>'
       '<main><h1>我独自升级</h1><a href="/works/AAA">章节</a></main>'
       '</body></html>')
out, n = strip_fav_buttons(syn)
ok('删掉了 2 个收藏按钮', n == 2, n)
ok('手机端 #mobile-favorite-btn 已移除', 'mobile-favorite-btn' not in out)
ok('桌面端 #favorite-btn 已移除', 'favorite-btn' not in out)
ok('aria-label="喜歡" 已随之消失', '喜歡' not in out)
ok('data-action="like" 已消失', 'data-action="like"' not in out)
ok('★ 没有误删「更多」按钮', 'data-navbar-more-trigger' in out)
ok('★ 没有误删页面正文标题', '<h1>我独自升级</h1>' in out)
ok('★ 没有误删章节链接', 'href="/works/AAA"' in out)
ok('删完仍能正常闭合（div 配对完整）',
   out.count('<div') - out.count('</div>') == 0, out.count('<div') - out.count('</div>'))

# 单个按钮（只有手机端那种情况）
syn2 = ('<div class="bar">'
        '<button id="mobile-favorite-btn" aria-label="喜歡" data-action="like">x</button>'
        '<span>keep</span></div>')
out2, n2 = strip_fav_buttons(syn2)
ok('只有手机端按钮时也能删', n2 == 1 and 'mobile-favorite-btn' not in out2, n2)
ok('同容器里的其它元素保留', '<span>keep</span>' in out2)

# 不存在的 id -> 原样返回
out3, n3 = strip_fav_buttons('<div>没有收藏按钮</div>')
ok('没有该按钮时不改动 HTML', n3 == 0 and out3 == '<div>没有收藏按钮</div>')

# 自闭合/属性里含 '>' 的陷阱
syn4 = ('<button id="mobile-favorite-btn" data-x="a>b" aria-label="喜歡">'
        '<i>inner</i></button><p>after</p>')
out4, n4 = strip_fav_buttons(syn4)
ok('属性含 > 时不提前截断', n4 == 1 and 'mobile-favorite-btn' not in out4)
ok('后续内容未被吞掉', '<p>after</p>' in out4, out4[:80])


# ---------- B. 真实详情页 ----------
print('\n[B] 真实站点详情页')
detail = None
try:
    home = fetch('https://m.hipmh.com/')
    m = re.search(r'href="(/works/[^"#]+)"', home)
    if m:
        detail = 'https://m.hipmh.com' + m.group(1)
except Exception as e:
    print('  SKIP  取首页失败: %s' % e)

if detail:
    try:
        html = fetch(detail)
    except Exception as e:
        print('  SKIP  取详情页失败: %s' % e)
        html = None
    if html:
        print('  详情页 len=%d' % len(html))
        had_mobile = 'id="mobile-favorite-btn"' in html
        had_desktop = 'id="favorite-btn"' in html
        print('  含手机端收藏按钮=%s  含桌面端收藏按钮=%s' % (had_mobile, had_desktop))

        out, n = strip_fav_buttons(html)
        every = [
            ('id="mobile-favorite-btn"', '手机端收藏按钮'),
            ('id="favorite-btn"', '桌面端收藏按钮'),
            ('id="mobile-heart-icon"', '手机端心形 SVG'),
            ('id="heart-icon"', '桌面端心形 SVG'),
            ('data-action="like"', 'like 动作'),
            ('aria-label="喜歡"', '喜歡 aria-label'),
        ]
        for needle, label in every:
            if needle in html:
                ok('%s 已删除' % label, needle not in out)
            else:
                print('  SKIP  %s（原页面就没有）' % label)

        # 主体必须完好
        ok('作品标题仍在（<h1>）', '<h1' in out)
        ok('我们的「更多」按钮仍在', 'data-navbar-more-trigger' in out)
        ok('章节区仍在（/chapter/ 或 章節）',
           ('/chapter/' in out) or ('章節' in out) or ('章节' in out))
        removed = len(html) - len(out)
        ok('删除量级合理 (<4000 字节)', removed < 4000, '实际 %d' % removed)
        ok('div 配对完整（没有破坏文档结构）',
           abs(out.count('<div') - out.count('</div>')) <= 1,
           '%d vs %d' % (out.count('<div'), out.count('</div>')))
else:
    print('  SKIP  未拿到详情页 HTML（离线也能跑合成用例）')

# ---------- C. 阅读页「返回书籍」按钮必须保持半透明 ----------
# 背景：v1.9.1 改 CSS 时误把 #__hipBack 做成不透明，用户立刻反馈「返回按钮不透明了」。
# 这里直接从 Java 源码里抠出 #__hipBack 那段 CSS 文本做断言，防止回归。
print('\n[C] 阅读页返回按钮透明度')
HERE = os.path.dirname(os.path.abspath(__file__))
try:
    src = open(os.path.join(HERE, 'app', 'src', 'main', 'java', 'com', 'hipmh', 'app',
                            'MainActivity.java'), encoding='utf-8').read()
except OSError:
    src = ''
if not src:
    print('  SKIP  读不到 MainActivity.java')
else:
    # Java 源码里每段 CSS 长这样：  + "+'#__hipBack{...}'"
    # 注意：这里的单引号是**普通 ASCII 单引号**（不是转义序列），
    # 所以用  +'...'"  这个模式把片段抠出来拼接即可。
    Q = "'"
    i = src.find("#__hipBack{")
    seg = src[max(0, i - 200):i + 2000] if i >= 0 else ''
    parts = re.findall(r"\+" + Q + r"(.*?)" + Q + r'"', seg, re.S)
    css = ''.join(parts)
    ok('找到 #__hipBack 规则', i >= 0)
    ok('抠出了 CSS 片段', len(css) > 100, 'len=%d' % len(css))
    # 背景必须是 rgba(...) 且 alpha < 1（半透明），不能是 #fff / rgb() 这类实色
    m = re.search(r'background:rgba\([^)]*?,\s*([0-9.]+)\s*\)', css)
    ok('背景用 rgba 半透明', bool(m), css[:200])
    if m:
        alpha = float(m.group(1))
        ok('背景 alpha < 1（%.2f）' % alpha, alpha < 1.0)
        # ★ v1.9.2：alpha 要真的够透（<= .35）。之前 .46 视觉上仍偏实，
        #   用户体感就是「不透明」。这里把上限卡住防止回退。
        ok('背景足够透（alpha <= .35）', alpha <= 0.35)
    # 整体 opacity 必须是 0 < x < 1
    m2 = re.search(r'opacity:([0-9.]+)', css)
    ok('设置了元素 opacity', bool(m2), css[:200])
    if m2:
        op = float(m2.group(1))
        ok('opacity 在 (0,1) 开区间（%.2f）' % op, 0.0 < op < 1.0)
    ok('不含实色 background:#', 'background:#' not in css)
    ok('保留了 __act 高亮态', '__act' in src)
    # ★ v1.9.2 关键回归：绝不能加 backdrop-filter —— 它会把背后的漫画糊成一片，
    #   视觉上就是个实心圆盘（用户反馈「不透明」的元凶之一）。
    ok('未使用 backdrop-filter（否则背景会被糊成实心感）',
       'backdrop-filter' not in css, css[:200])
    # ★ v1.9.2 关键回归：新建按钮后**不能**立刻 flash，否则一进阅读页就是实心态。
    ok('建完按钮不再自动 __hipFlash()',
       'appendChild(b);__hipFlash()' not in src)
    # ★ v1.9.2 关键回归：页面级 scroll/touchstart 不能再触发 flash
    #   （旧版把它们都当触发源 → 翻页时按钮一直实心）。
    ok('页面 scroll 不再触发 flash',
       "addEventListener('scroll',__hipFlash" not in src)
    ok('页面 touchstart 不再触发 flash',
       "document.addEventListener('touchstart',__hipFlash" not in src)
    # 但按钮自身的按压反馈要保留
    ok('按钮自身 touchstart 仍给反馈',
       "b.addEventListener('touchstart',function(){__hipFlash();}" in src)

print('\n==== %d passed, %d failed ====' % (npass, nfail))
sys.exit(1 if nfail else 0)
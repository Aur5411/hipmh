# -*- coding: utf-8 -*-
"""校验 cleanJs 拼出的 CSS 选择器串是否合法（括号配对、引号配对、无空选择器段）。"""
import io, os, re, sys

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   'app/src/main/java/com/hipmh/app/MainActivity.java')
java = io.open(SRC, encoding='utf-8').read()

m = re.search(r'String\s+cleanJs\s*\(boolean blockAd\)\s*\{', java)
body = java[m.end(): java.find('\n    }', m.end())]

# 抓所有 css+='...' 片段（Java 里已被 \" 转义 -> 还原成 "）
frags = re.findall(r"css\+='((?:[^'\\]|\\.)*)'", body)
out = []
for f in frags:
    s = f.replace('\\"', '"').replace("\\'", "'").replace('\\\\', '\\')
    out.append(s)

css = ''.join(out)
# 去掉 if(AD){} 包裹里那段（它在 {display:none!important;} 前面自带闭合）
print("CSS 原始拼接 (len=%d):" % len(css))
print(css)

fails = []

# 1) 括号/引号配对
if css.count('"') % 2 != 0:
    fails.append('双引号数量为奇数')
# 2) 每个顶层段应形如  sel1,sel2,...{...}
#    把 css 按 {...} 分段检查
blocks = re.findall(r'([^{}]*)\{([^{}]*)\}', css)
print("\n---- 解析出 %d 个 {..} 规则块 ----" % len(blocks))
for i, (sel, decl) in enumerate(blocks):
    print("  [%d] decl=%r" % (i, decl.strip()))
    for s in sel.split(','):
        s = s.strip()
        if not s:
            fails.append('块%d 含空选择器' % i)
        elif s.count('[') != s.count(']'):
            fails.append('块%d 选择器 %r 方括号不配对' % (i, s))
        elif s.count('"') % 2 != 0:
            fails.append('块%d 选择器 %r 引号不成对' % (i, s))

# 3) 关键选择器必须在
must = ['a[aria-label="我的書架"]', 'a[aria-label="个人中心"]',
        'a[href*="xipmh.com"]', 'a[href*="dashboard"]', 'a[href*="shelf"]',
        'a[href$="/login"]', 'a[href*="/history"]' ]
print("\n---- 关键选择器核查 ----")
for k in must:
    hit = k in css
    print("  %s  %s" % ('OK ' if hit else ('MISS' if 'history' in k else 'INFO'), k))
    if not hit and 'history' not in k:
        fails.append('缺少选择器 %s' % k)

# 4) 不能出现会误伤站内功能页的选择器
bad = ['a[href*="/search"]', 'a[href*="/history"]', 'a[href*="/works/"]']
print("\n---- 误伤检查 ----")
for k in bad:
    hit = k in css
    print("  %s  %s" % ('FAIL' if hit else 'OK  ', k + ('  <-- 会误伤!' if hit else '')))
    if hit:
        fails.append('误伤选择器 %s' % k)

print("\n==== %s ====" % ('ALL OK' if not fails else ('FAIL: ' + '; '.join(fails))))
sys.exit(0 if not fails else 1)

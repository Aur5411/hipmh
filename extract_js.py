#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
从 MainActivity.java 精确反解注入脚本，供 node --check / 行为测试使用。
要点：
  - 用配对扫描定位方法/字段的结束 ';'，不用固定分隔串（避免跨方法串味）
  - Java 字符串里的 \\uXXXX / \\n / \\' / \\" 需按 Java 规则还原，而不是 unicode_escape
"""
import io, os, re, sys, subprocess, json

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   'app/src/main/java/com/hipmh/app/MainActivity.java')
OUT = os.environ.get('TEMP', '/tmp')

java = io.open(SRC, encoding='utf-8').read()


def find_field(name):
    """返回 name 字段声明到其结束分号的正文。

    注意：必须先剥掉 Java 注释再数分号 —— 注释里出现的 `；` 或 `;`
    （例如中文说明里的分号）会被误判成字段结尾，导致抽取被截断
    （v1.8 的 DRAWER_JS 就踩过这个坑）。字符串内部的分号仍然要忽略。
    """
    m = re.search(r'String\s+' + re.escape(name) + r'\s*=\s*', java)
    if not m:
        return None
    i = m.end()
    n = len(java)
    while i < n:
        c = java[i]
        # 跳过字符串字面量（含转义），里面的分号不算
        if c == '"':
            i += 1
            while i < n:
                if java[i] == '\\':
                    i += 2
                    continue
                if java[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        # 跳过 // 行注释
        if c == '/' and i + 1 < n and java[i + 1] == '/':
            j = java.find('\n', i)
            i = n if j < 0 else j + 1
            continue
        # 跳过 /* */ 块注释
        if c == '/' and i + 1 < n and java[i + 1] == '*':
            j = java.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == ';':
            return java[m.end():i]
        i += 1
    return None


def find_method(sig):
    """返回方法体（花括号配对）"""
    i = java.find(sig)
    if i < 0:
        return None
    j = java.find('{', i)
    depth = 0
    k = j
    instr = False
    while k < len(java):
        c = java[k]
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
                return java[j:k + 1]
        k += 1
    return None


def strip_java_comments(body):
    """去掉 Java 的 // 行注释 与 /* */ 块注释。
    必须在抽取字符串字面量【之前】做——否则注释里出现的引号
    （例如 // 站点 <nav class="fixed z-[100]"> 的末尾…）会被
    字符串正则误当成 JS 字面量，污染反解结果。"""
    out = []
    i = 0
    n = len(body)
    while i < n:
        c = body[i]
        if c == '"':
            # 字符串字面量整体保留（含转义）
            j = i + 1
            while j < n:
                if body[j] == '\\':
                    j += 2
                    continue
                if body[j] == '"':
                    break
                j += 1
            out.append(body[i:min(j + 1, n)])
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if body[j] == '\\':
                    j += 2
                    continue
                if body[j] == "'":
                    break
                j += 1
            out.append(body[i:min(j + 1, n)])
            i = j + 1
            continue
        if c == '/' and i + 1 < n and body[i + 1] == '/':
            j = body.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and i + 1 < n and body[i + 1] == '*':
            j = body.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def java_str_to_js(body):
    """把 Java 源码正文里的字符串字面量按 Java 转义规则拼成 JS"""
    body = strip_java_comments(body)
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', body)
    out = []
    for p in parts:
        buf = []
        i = 0
        while i < len(p):
            c = p[i]
            if c == '\\' and i + 1 < len(p):
                n = p[i + 1]
                if n == 'u' and i + 5 < len(p) + 1:
                    try:
                        buf.append(chr(int(p[i + 2:i + 6], 16)))
                        i += 6
                        continue
                    except ValueError:
                        pass
                mp = {'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', 'f': '\f',
                      '"': '"', "'": "'", '\\': '\\', '0': '\0'}
                if n in mp:
                    buf.append(mp[n])
                    i += 2
                    continue
            buf.append(c)
            i += 1
        out.append(''.join(buf))
    return ''.join(out)


targets = {}

b = find_method('String cleanJs(boolean blockAd) {')
targets['hip_clean'] = java_str_to_js(b).replace('__AD__', 'true')

b = find_field('GUARD_JS')
targets['hip_guard'] = java_str_to_js(b)

# HIST_JS 在 v1.7 已废弃（浏览记录下线），不再抽取
# b = find_field('HIST_JS')
# targets['hip_hist'] = java_str_to_js(b)

# ★ v1.6：作品详情 / 阅读器页的收藏按钮脚本
b = find_field('FAV_JS')
targets['hip_fav'] = java_str_to_js(b)

# ★ v1.8：把站点右上角「更多」弹出的「閱讀記錄」抽屉改造成本地书架
b = find_field('DRAWER_JS')
targets['hip_drawer'] = java_str_to_js(find_method('String drawerJs() {') or '')

# ★ v1.9：详情页封面图采集（回传原生，供书架列表显示封面）
b = find_field('COVER_JS')
targets['hip_cover'] = java_str_to_js(b)

# ★ v2.0.0：详情页「继续阅读」按钮改造（阅读进度直达上次章节）
b = find_field('DETAIL_PROGRESS_JS')
targets['hip_detailprog'] = java_str_to_js(b)

b = find_field('T2S_JS')
targets['hip_t2s'] = java_str_to_js(b)

b = find_method('String readerJs(boolean allowNext) {')
targets['hip_reader'] = java_str_to_js(b)

ok = True
for name, js in targets.items():
    f = os.path.join(OUT, name + '.js')
    io.open(f, 'w', encoding='utf-8').write(js)
    r = subprocess.run(['node', '--check', f], capture_output=True, text=True)
    flag = 'OK ' if r.returncode == 0 else 'ERR'
    if r.returncode != 0:
        ok = False
    print('%-11s %s len=%-6d %s' % (name, flag, len(js), r.stderr.strip()[:200]))

# 打印关键正则，肉眼核对中文是否正常
for name in ('hip_clean',):
    js = targets[name]
    m = re.search(r'var LB=(/[^;]*?/);', js)
    print(name, 'LB =', m.group(1) if m else '(none)')

print('SYNTAX_ALL_OK =', ok)
sys.exit(0 if ok else 1)
